package scalascript.plugin

import scalascript.backend.spi.*
import scalascript.ir
import upickle.default.*
import java.io.{BufferedReader, DataInputStream, DataOutputStream, InputStreamReader, OutputStreamWriter, PrintWriter}
import scala.jdk.CollectionConverters.*
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

/** Delegates to a subprocess speaking stdio-json or stdio-msgpack.
 *
 *  Stage 6.1: process management + compile.
 *  Stage 6+/A: MsgPack framing.
 *  Stage 6+/B: InteractiveBackend — openSession / session.feed /
 *              invokeHandler forwarded over the same wire. */
class SubprocessBackend private (
    proc:         Process,
    initialDesc:  MessageBodies.DescribeResult,
    framing:      WireFraming
) extends InteractiveBackend:

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
    t.setName(s"ssc.plugin-stderr-pump")
    t.start()

  // ─── Backend SPI surface (driven by `descriptor`) ──────────────────────

  def id:              String   = descriptor.id
  def displayName:     String   = descriptor.displayName
  def spiVersion:      String   = descriptor.spiVersion
  def acceptedSources: Set[String] = descriptor.acceptedSources
  /** True if the plugin declared `interactive: true` in its describe response. */
  def isInteractive:   Boolean  = descriptor.interactive

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

  // ─── InteractiveBackend surface ────────────────────────────────────────

  /** Open a stateful session on the subprocess plugin.
   *  The plugin must declare `interactive: true` in its describe result;
   *  throws `UnsupportedOperationException` otherwise. */
  def openSession(opts: BackendOptions): Session =
    if !descriptor.interactive then
      throw UnsupportedOperationException(
        s"plugin/${descriptor.id} does not declare interactive support"
      )
    val params = MessageBodies.OpenSessionParams(
      baseDir = opts.baseDir.map(_.toAbsolutePath.toString),
      extra   = opts.extra
    )
    call(Methods.OpenSession, writeJs(params)) match
      case Right(json) =>
        val result = read[MessageBodies.OpenSessionResult](json)
        new SubprocessSession(result.sessionId, this)
      case Left(err) =>
        throw RuntimeException(s"plugin/${descriptor.id}: openSession failed: ${err.message}")

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

  // ─── HostCallback registry (Stage 6+/C) ──────────────────────────────

  private val hostCallbacks =
    new java.util.concurrent.ConcurrentHashMap[String, (List[ir.Value]) => ir.Value]().asScala

  /** Register a host function the subprocess plugin may call back into
   *  during `compile` or `session.feed` via a `host.<name>` wire request.
   *  Replaces any previously registered function for the same name. */
  def registerHostCallback(name: String, fn: (List[ir.Value]) => ir.Value): Unit =
    hostCallbacks.put(name, fn)

  // ─── Internal wire dispatch ───────────────────────────────────────────

  /** One round-trip request → response, dispatching any interleaved
   *  `host.*` callback requests inline before returning.
   *  Thread-safe: all calls serialise through `this.synchronized`. */
  private[plugin] def call(method: String, params: ujson.Value): Either[ResponseError, ujson.Value] =
    val req = Request(method = method, params = params, id = nextId.getAndIncrement())
    this.synchronized {
      framing match
        case WireFraming.Json    => callJson(req)
        case WireFraming.MsgPack => callMsgPack(req)
    }

  private def callJson(req: Request): Either[ResponseError, ujson.Value] =
    jsonWriter.println(write(req))
    jsonWriter.flush()
    var done: Either[ResponseError, ujson.Value] = null
    while done == null do
      val line = jsonReader.readLine()
      if line == null then
        done = Left(ResponseError(ErrorCodes.PluginCrash, s"plugin/${descriptor.id} closed stdout"))
      else
        val raw = ujson.read(line)
        if raw.obj.contains("method") then
          handleIncomingCallback(raw)
        else
          done = Try(read[Response](line)) match
            case Success(resp) => decodeResponse(resp)
            case Failure(t)    => Left(ResponseError(ErrorCodes.ParseError,
                s"unparseable response: $t — $line"))
    done

  private def callMsgPack(req: Request): Either[ResponseError, ujson.Value] =
    val bytes = writeBinary(req)
    binOut.writeInt(bytes.length)
    binOut.write(bytes)
    binOut.flush()
    var done: Either[ResponseError, ujson.Value] = null
    while done == null do
      try
        val len = binIn.readInt()
        if len < 0 || len > 64 * 1024 * 1024 then
          done = Left(ResponseError(ErrorCodes.ParseError,
            s"plugin/${descriptor.id}: bogus msgpack frame length $len"))
        else
          val buf = new Array[Byte](len)
          binIn.readFully(buf)
          Try(readBinary[ujson.Value](buf)) match
            case Success(raw) if raw.obj.contains("method") =>
              handleIncomingCallback(raw)
            case _ =>
              done = Try(readBinary[Response](buf)) match
                case Success(resp) => decodeResponse(resp)
                case Failure(t)    => Left(ResponseError(ErrorCodes.ParseError,
                    s"unparseable response: $t — <msgpack ${len} bytes>"))
      catch case _: java.io.EOFException =>
        done = Left(ResponseError(ErrorCodes.PluginCrash, s"plugin/${descriptor.id} closed stdout"))
    done

  private def decodeResponse(resp: Response): Either[ResponseError, ujson.Value] =
    (resp.result, resp.error) match
      case (Some(r), _) => Right(r)
      case (_, Some(e)) => Left(e)
      case (_, _)       => Right(ujson.Null)

  /** Handle an incoming `host.<name>` callback from the plugin.
   *  Dispatches to the registered host function and writes the result
   *  back to the plugin on the same framing channel. */
  private def handleIncomingCallback(raw: ujson.Value): Unit =
    val method   = Try(raw("method").str).getOrElse("")
    val callId   = Try(raw("id").num.toLong).getOrElse(0L)
    val params   = raw.obj.get("params").getOrElse(ujson.Obj())
    val args     = Try(read[List[ir.Value]](params("args"))).getOrElse(Nil)
    val callbackName = method.stripPrefix(Methods.HostPrefix)
    val resp: Response =
      if !method.startsWith(Methods.HostPrefix) then
        Response(id = callId, error = Some(ResponseError(ErrorCodes.InvalidRequest,
          s"unexpected method from plugin: $method")))
      else hostCallbacks.get(callbackName) match
        case Some(fn) =>
          Try(fn(args)) match
            case Success(v) =>
              Response(id = callId, result = Some(writeJs(MessageBodies.HostCallbackResult(v))))
            case Failure(t) =>
              Response(id = callId, error = Some(ResponseError(ErrorCodes.InternalError,
                s"host callback '$callbackName' threw: ${t.getMessage}")))
        case None =>
          Response(id = callId, error = Some(ResponseError(ErrorCodes.MethodNotFound,
            s"no host callback registered for '$callbackName'")))
    sendDirectResponse(resp)

  private def sendDirectResponse(resp: Response): Unit = framing match
    case WireFraming.Json =>
      jsonWriter.println(write(resp))
      jsonWriter.flush()
    case WireFraming.MsgPack =>
      val b = writeBinary(resp)
      binOut.writeInt(b.length)
      binOut.write(b)
      binOut.flush()

  private[plugin] def fromWire(w: MessageBodies.CompileResultWire): CompileResult = w.kind match
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


/** A `Session` that forwards every call to a subprocess plugin session
 *  identified by `sessionId`.  Stage 6+/B. */
private class SubprocessSession(
    sessionId: String,
    backend:   SubprocessBackend
) extends Session:
  import upickle.default.*

  def feed(block: ir.NormalizedBlock): CompileResult =
    val params = MessageBodies.SessionFeedParams(sessionId = sessionId, block = block)
    backend.call(Methods.SessionFeed, writeJs(params)) match
      case Right(json) =>
        Try(read[MessageBodies.CompileResultWire](json)) match
          case Success(wire) => backend.fromWire(wire)
          case Failure(t)    => CompileResult.Failed(List(Diagnostic.Generic(
            message = s"plugin/${backend.id}: malformed session.feed response: ${t.getMessage}",
            source  = None
          )))
      case Left(err) =>
        CompileResult.Failed(List(Diagnostic.Generic(
          message = s"plugin/${backend.id}: session.feed failed: ${err.message}",
          source  = None
        )))

  def invokeHandler(handlerRef: ir.SymbolRef, args: List[ir.Value]): ir.Value =
    val params = MessageBodies.InvokeHandlerParams(
      sessionId  = sessionId,
      handlerRef = handlerRef,
      args       = args
    )
    backend.call(Methods.InvokeHandler, writeJs(params)) match
      case Right(json) =>
        Try(read[MessageBodies.InvokeHandlerResult](json)) match
          case Success(r) => r.value
          case Failure(t) =>
            throw RuntimeException(s"plugin/${backend.id}: malformed invokeHandler response: ${t.getMessage}")
      case Left(err) =>
        throw RuntimeException(s"plugin/${backend.id}: invokeHandler failed: ${err.message}")

  def close(): Unit =
    val params = MessageBodies.SessionCloseParams(sessionId = sessionId)
    backend.call(Methods.SessionClose, writeJs(params))
    ()

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
