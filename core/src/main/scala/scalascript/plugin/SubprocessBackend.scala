package scalascript.plugin

import scalascript.backend.spi.*
import scalascript.ir
import upickle.default.*
import java.io.{BufferedReader, DataInputStream, DataOutputStream, InputStreamReader, OutputStreamWriter, PrintWriter}
import scala.util.{Try, Success, Failure}

/** Wire framing variants — selected by `plugin.yaml#protocol`.
 *  Stage 6+/A. */
enum WireFraming:
  /** Newline-delimited JSON over UTF-8 streams. */
  case Json
  /** 4-byte big-endian length prefix + MsgPack payload.
   *  Same case-class shapes as Json, round-tripped via upickle's
   *  `writeBinary` / `readBinary`. */
  case MsgPack

object WireFraming:
  def fromManifest(protocol: String): WireFraming = protocol match
    case "stdio-msgpack" => MsgPack
    case _               => Json

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
    initialDesc:  MessageBodies.DescribeResult,
    framing:      WireFraming = WireFraming.Json
) extends Backend:

  /** Set once the initial `describe` handshake completes inside
   *  `SubprocessBackend.spawn`; never mutated thereafter.  Held as a
   *  var so the spawn path can construct the instance, kick off the
   *  stderr pump under the temporary stub id, then update the id once
   *  the plugin self-identifies. */
  @volatile private var descriptor: MessageBodies.DescribeResult = initialDesc

  private val nextId = new java.util.concurrent.atomic.AtomicLong(1L)

  // Framing-dependent IO handles.  Initialised exactly one pair —
  // can't share a single InputStream across reader + DataInputStream
  // since they'd race for bytes.
  private val (jsonReader, jsonWriter, binIn, binOut) = framing match
    case WireFraming.Json =>
      val r = new BufferedReader(new InputStreamReader(proc.getInputStream, "UTF-8"))
      val w = new PrintWriter(new OutputStreamWriter(proc.getOutputStream, "UTF-8"), /* autoFlush */ true)
      (r, w, null.asInstanceOf[DataInputStream], null.asInstanceOf[DataOutputStream])
    case WireFraming.MsgPack =>
      val in  = new DataInputStream(proc.getInputStream)
      val out = new DataOutputStream(proc.getOutputStream)
      (null.asInstanceOf[BufferedReader], null.asInstanceOf[PrintWriter], in, out)

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
      framing match
        case WireFraming.Json    => Try(jsonWriter.close()); Try(jsonReader.close())
        case WireFraming.MsgPack => Try(binOut.close()); Try(binIn.close())
      if !proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) then
        proc.destroyForcibly()

  // ─── Internal wire dispatch ───────────────────────────────────────────

  /** One round-trip request → response.  Returns the `result` value or
   *  the `error` envelope.  Thread-safe under sync call from a single
   *  caller; concurrent compile() invocations must serialise externally
   *  (registry-level pool when needed). */
  private[plugin] def call(method: String, params: ujson.Value): Either[ResponseError, ujson.Value] =
    val req = Request(method = method, params = params, id = nextId.getAndIncrement())
    this.synchronized {
      framing match
        case WireFraming.Json    => callJson(req)
        case WireFraming.MsgPack => callMsgPack(req)
    }

  private def callJson(req: Request): Either[ResponseError, ujson.Value] =
    val line = write(req)
    jsonWriter.println(line)
    jsonWriter.flush()
    val responseLine = jsonReader.readLine()
    if responseLine == null then
      Left(ResponseError(ErrorCodes.PluginCrash, s"plugin/${descriptor.id} closed stdout"))
    else parseResponse(read[Response](responseLine), responseLine)

  private def callMsgPack(req: Request): Either[ResponseError, ujson.Value] =
    val bytes = writeBinary(req)
    binOut.writeInt(bytes.length)
    binOut.write(bytes)
    binOut.flush()
    try
      val len = binIn.readInt()
      if len < 0 || len > 64 * 1024 * 1024 then
        Left(ResponseError(ErrorCodes.ParseError, s"plugin/${descriptor.id}: bogus msgpack frame length $len"))
      else
        val buf = new Array[Byte](len)
        binIn.readFully(buf)
        parseResponse(readBinary[Response](buf), s"<msgpack ${len} bytes>")
    catch case _: java.io.EOFException =>
      Left(ResponseError(ErrorCodes.PluginCrash, s"plugin/${descriptor.id} closed stdout"))

  private def parseResponse(parse: => Response, repr: => String): Either[ResponseError, ujson.Value] =
    Try(parse) match
      case Success(resp) =>
        (resp.result, resp.error) match
          case (Some(r), _) => Right(r)
          case (_, Some(e)) => Left(e)
          case (_, _)       => Right(ujson.Null)
      case Failure(t) =>
        Left(ResponseError(ErrorCodes.ParseError, s"unparseable response: $t — $repr"))

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
      args:       List[String]   = Nil,
      workingDir: Option[os.Path] = None,
      framing:    WireFraming    = WireFraming.Json
  ): Try[SubprocessBackend] =
    Try {
      val pb = new ProcessBuilder((executable +: args)*)
      workingDir.foreach(d => pb.directory(d.toIO))
      pb.redirectErrorStream(false)
      val proc = pb.start()
      val inst = new SubprocessBackend(proc, initialDesc = stubDescriptor(executable), framing = framing)
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
