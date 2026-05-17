package scalascript.plugin

import scalascript.backend.spi.*
import scalascript.ir
import upickle.default.*
import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter, PrintWriter}
import scala.util.{Try, Success, Failure}

/** A Backend implementation that delegates to a subprocess speaking
 *  the newline-delimited JSON protocol from `WireProtocol.scala`.
 *
 *  Stage 6.1: process management + sync request/response only.  Each
 *  `Backend` method serialises params, writes one line to the
 *  subprocess's stdin, reads one line from stdout, parses the
 *  response.  Stderr is forwarded line-by-line to `System.err` via a
 *  background pump thread.
 *
 *  Lifecycle: a fresh subprocess is spawned at construction;
 *  `shutdown()` sends a `shutdown` request then closes streams.
 *  Process exit is monitored by `BackendRegistry` (Stage 6.2).
 *
 *  Out-of-scope for 6.1: MsgPack framing, plugin crash detection /
 *  restart, host-callback dispatch (the `HostCallback` IntrinsicImpl
 *  variant), interactive session methods.  All land in 6.2 or as
 *  follow-up. */
class SubprocessBackend private (
    proc:         Process,
    initialDesc:  MessageBodies.DescribeResult
) extends Backend:

  /** Set once the initial `describe` handshake completes inside
   *  `SubprocessBackend.spawn`; never mutated thereafter.  Held as a
   *  var so the spawn path can construct the instance, kick off the
   *  stderr pump under the temporary stub id, then update the id once
   *  the plugin self-identifies. */
  @volatile private var descriptor: MessageBodies.DescribeResult = initialDesc

  private val nextId = new java.util.concurrent.atomic.AtomicLong(1L)
  private val stdout = new BufferedReader(new InputStreamReader(proc.getInputStream, "UTF-8"))
  private val stdin  = new PrintWriter(new OutputStreamWriter(proc.getOutputStream, "UTF-8"), /* autoFlush */ true)
  startStderrPump()

  private def startStderrPump(): Unit =
    val t = new Thread(() =>
      val r = new BufferedReader(new InputStreamReader(proc.getErrorStream, "UTF-8"))
      var line = r.readLine()
      while line != null do
        System.err.println(s"[plugin/${descriptor.id}] $line")
        line = r.readLine())
    t.setDaemon(true)
    t.setName(s"sscplugin-stderr-pump")
    t.start()

  // ─── Backend SPI surface (driven by `descriptor`) ──────────────────────

  def id:              String = descriptor.id
  def displayName:     String = descriptor.displayName
  def spiVersion:      String = descriptor.spiVersion
  def acceptedSources: Set[String] = descriptor.acceptedSources

  def capabilities: Capabilities =
    // The descriptor sends feature/output names as strings; map back to
    // the spi enums.  Unknown names are ignored — a future plugin may
    // declare features core doesn't recognise; core treats the program
    // as unsupported for that feature in that case.
    val features = descriptor.features.flatMap(n => Feature.values.find(_.toString == n))
    val outputs  = descriptor.outputs.flatMap(n => OutputKind.values.find(_.toString == n))
    Capabilities(
      features = features,
      outputs  = outputs,
      options  = Set.empty,
      spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
    )

  def intrinsics: Map[ir.QualifiedName, IntrinsicImpl] = Map.empty

  private[plugin] def updateDescriptor(d: MessageBodies.DescribeResult): Unit =
    descriptor = d

  // ─── compile dispatch ─────────────────────────────────────────────────

  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    val params = MessageBodies.CompileParams(
      irJson  = ujson.read(write(module)),
      baseDir = opts.baseDir.map(_.toAbsolutePath.toString),
      extra   = opts.extra
    )
    call(Methods.Compile, writeJs(params)) match
      case Right(json) =>
        Try(read[MessageBodies.CompileResultWire](json)) match
          case Success(wire) => fromWire(wire)
          case Failure(t)    =>
            CompileResult.Failed(List(Diagnostic.Generic(
              message = s"plugin/${descriptor.id}: malformed compile response: ${t.getMessage}",
              source  = None
            )))
      case Left(err) =>
        CompileResult.Failed(List(Diagnostic.Generic(
          message = s"plugin/${descriptor.id}: compile failed: ${err.message}",
          source  = None
        )))

  /** Send `shutdown` and close streams.  Idempotent: a second call is a
   *  no-op.  Forces process termination if the plugin doesn't exit on
   *  its own within 2 seconds. */
  def shutdown(): Unit =
    if proc.isAlive then
      Try(call(Methods.Shutdown, ujson.Obj()))
      Try(stdin.close())
      Try(stdout.close())
      if !proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) then
        proc.destroyForcibly()

  // ─── Internal wire dispatch ───────────────────────────────────────────

  /** One round-trip request → response.  Returns the `result` JSON or
   *  the `error` envelope.  Thread-safe under sync call from a single
   *  caller; concurrent compile() invocations must serialise externally
   *  (registry-level pool when needed). */
  private[plugin] def call(method: String, params: ujson.Value): Either[ResponseError, ujson.Value] =
    val req = Request(method = method, params = params, id = nextId.getAndIncrement())
    val line = write(req)
    this.synchronized {
      stdin.println(line)
      stdin.flush()
      val responseLine = stdout.readLine()
      if responseLine == null then
        Left(ResponseError(ErrorCodes.PluginCrash, s"plugin/${descriptor.id} closed stdout"))
      else
        Try(read[Response](responseLine)) match
          case Success(resp) =>
            (resp.result, resp.error) match
              case (Some(r), _) => Right(r)
              case (_, Some(e)) => Left(e)
              case (_, _)       => Right(ujson.Null)
          case Failure(t) =>
            Left(ResponseError(ErrorCodes.ParseError, s"unparseable response: $t — line: $responseLine"))
    }

  private def fromWire(w: MessageBodies.CompileResultWire): CompileResult = w.kind match
    case "text"      => CompileResult.TextOutput(
                          code     = w.code.getOrElse(""),
                          language = w.language.getOrElse(""),
                          sources  = Nil
                        )
    case "segmented" => CompileResult.Segmented(
                          w.segments.getOrElse(Nil).map(segmentFromWire)
                        )
    case "binary"    => CompileResult.BinaryOutput(
                          bytes = w.bytes.getOrElse(Array.empty[Byte]),
                          mime  = w.mime.getOrElse("application/octet-stream"),
                          files = Nil
                        )
    case "executed"  => CompileResult.Executed(
                          stdout = w.stdout.getOrElse(""),
                          stderr = w.stderr.getOrElse(""),
                          exit   = w.exit.getOrElse(0)
                        )
    case "failed"    => CompileResult.Failed(
                          w.diagnostics.getOrElse(Nil).map(diagnosticFromWire)
                        )
    case other       => CompileResult.Failed(List(Diagnostic.Generic(
                          message = s"plugin/${descriptor.id}: unknown CompileResult kind '$other'",
                          source  = None
                        )))

  private def segmentFromWire(w: MessageBodies.SegmentWire): Segment = w.kind match
    case "code"   => Segment.Code(language = w.language, code = w.code)
    case "source" => Segment.Source(language = w.language, source = w.source)
    case other    => Segment.Code(language = other, code = w.code)

  private def diagnosticFromWire(w: MessageBodies.DiagnosticWire): Diagnostic = w.kind match
    case "unsupported"           =>
      Feature.values.find(_.toString == w.feature) match
        case Some(f) => Diagnostic.Unsupported(f, w.backend)
        case None    => Diagnostic.Generic(w.message, None)
    case "unknown-block"         => Diagnostic.UnknownBlockLanguage(w.feature)
    case _                       => Diagnostic.Generic(w.message, None)


object SubprocessBackend:

  /** Spawn the subprocess described by a manifest, perform the
   *  initial `describe` handshake, return a usable `SubprocessBackend`. */
  def spawn(
      executable: String,
      args:       List[String]  = Nil,
      workingDir: Option[os.Path] = None
  ): Try[SubprocessBackend] =
    Try {
      val pb = new ProcessBuilder((executable +: args)*)
      workingDir.foreach(d => pb.directory(d.toIO))
      pb.redirectErrorStream(false)
      val proc = pb.start()
      val inst = new SubprocessBackend(proc, initialDesc = stubDescriptor(executable))
      inst.call(Methods.Describe, ujson.Obj()) match
        case Right(json) =>
          inst.updateDescriptor(read[MessageBodies.DescribeResult](json))
          inst
        case Left(err) =>
          inst.shutdown()
          throw RuntimeException(s"plugin handshake failed: ${err.message}")
    }

  private def stubDescriptor(executable: String): MessageBodies.DescribeResult =
    MessageBodies.DescribeResult(
      id          = s"<pending:${executable}>",
      displayName = "(handshake pending)",
      spiVersion  = SpiVersion.Current,
      role        = "backend"
    )
