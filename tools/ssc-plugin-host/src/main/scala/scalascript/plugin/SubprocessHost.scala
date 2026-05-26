package scalascript.plugin

import scalascript.backend.spi.*
import upickle.default.*
import java.io.{BufferedReader, DataInputStream, DataOutputStream, InputStreamReader, OutputStreamWriter, PrintWriter}
import java.net.URLClassLoader
import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Success, Failure}

/** Host process for JAR-based plugins when running under GraalVM native-image.
 *
 *  Native-image cannot load arbitrary class files at runtime via URLClassLoader,
 *  so `--plugin foo.jar` can't work in-process.  This thin JVM process bridges
 *  the gap: native `ssc` spawns
 *
 *    java -cp foo.jar:<path>/ssc-plugin-host.jar scalascript.plugin.SubprocessHost foo.jar [--msgpack]
 *
 *  and connects via the existing stdio wire protocol already used by
 *  `BackendRegistry` for out-of-process plugins.  Plugin authors change nothing.
 *
 *  Protocol: same Request/Response JSON-RPC-ish shape as `WireProtocol.scala`,
 *  JSON (default) or MsgPack (`--msgpack`).
 *
 *  Stage 6 wire methods supported here (mirrors SubprocessBackend dispatch):
 *    describe, compile, openSession, session.feed, session.close,
 *    invokeHandler, shutdown, host.* (forwarded back to plugin if plugin
 *    itself issues host callbacks — not applicable here since we are the host). */
object SubprocessHost:

  def main(args: Array[String]): Unit =
    val (jarPaths, useMsgPack) = parseArgs(args.toList)
    if jarPaths.isEmpty then
      System.err.println("[ssc-plugin-host] usage: SubprocessHost <plugin.jar> [--msgpack]")
      System.exit(1)

    val backends = loadBackends(jarPaths)
    if backends.isEmpty then
      System.err.println(s"[ssc-plugin-host] no Backend implementations found in: ${jarPaths.mkString(", ")}")
      System.exit(1)

    // We serve a single backend per host process.  If the JAR registers
    // multiple, serve the first one.  (Multiple JARs can share one host
    // process — each corresponds to a separate SubprocessBackend instance in core
    // and gets its own process, so this path is effectively always len=1.)
    val backend = backends.head

    if useMsgPack then runMsgPack(backend)
    else runJson(backend)

  // ── Arg parsing ──────────────────────────────────────────────────────────

  private def parseArgs(args: List[String]): (List[String], Boolean) =
    var msgpack = false
    val jars = args.filter {
      case "--msgpack" => msgpack = true; false
      case _           => true
    }
    (jars, msgpack)

  // ── Backend loading ──────────────────────────────────────────────────────

  private def loadBackends(jarPaths: List[String]): List[Backend] =
    val urls = jarPaths.map(p => java.io.File(p).toURI.toURL).toArray
    val cl   = new URLClassLoader(urls, classOf[Backend].getClassLoader)
    ServiceLoader.load(classOf[Backend], cl).iterator().asScala.toList

  // ── Session management ───────────────────────────────────────────────────

  private[plugin] val sessions =
    scala.collection.concurrent.TrieMap.empty[String, Session]
  private val nextSessionId = new java.util.concurrent.atomic.AtomicLong(1L)

  /** Test-only: invoke dispatch without spawning any process. */
  private[plugin] def dispatchForTest(backend: Backend, req: Request): Response =
    dispatch(backend, req)

  // ── JSON framing loop ────────────────────────────────────────────────────

  private def runJson(backend: Backend): Unit =
    val in  = new BufferedReader(new InputStreamReader(System.in,  "UTF-8"))
    val out = new PrintWriter(new OutputStreamWriter(System.out, "UTF-8"), true)
    var running = true
    while running do
      val line = in.readLine()
      if line == null then running = false
      else if line.nonEmpty then
        Try(read[Request](line)) match
          case Success(req) =>
            val resp = dispatch(backend, req)
            out.println(write(resp))
          case Failure(t) =>
            val err = Response(id = 0, error = Some(ResponseError(ErrorCodes.ParseError,
              s"unparseable request: ${t.getMessage}")))
            out.println(write(err))

  // ── MsgPack framing loop ─────────────────────────────────────────────────

  private def runMsgPack(backend: Backend): Unit =
    val in  = new DataInputStream(System.in)
    val out = new DataOutputStream(System.out)
    var running = true
    while running do
      try
        val len = in.readInt()
        if len < 0 || len > 64 * 1024 * 1024 then
          System.err.println(s"[ssc-plugin-host] bogus frame length $len, aborting")
          running = false
        else
          val buf = new Array[Byte](len)
          in.readFully(buf)
          Try(readBinary[Request](buf)) match
            case Success(req) =>
              val resp  = dispatch(backend, req)
              val bytes = writeBinary(resp)
              out.writeInt(bytes.length)
              out.write(bytes)
              out.flush()
            case Failure(t) =>
              val err   = Response(id = 0, error = Some(ResponseError(ErrorCodes.ParseError,
                s"unparseable request: ${t.getMessage}")))
              val bytes = writeBinary(err)
              out.writeInt(bytes.length)
              out.write(bytes)
              out.flush()
      catch case _: java.io.EOFException =>
        running = false

  // ── Request dispatch ─────────────────────────────────────────────────────

  private def dispatch(backend: Backend, req: Request): Response =
    Try(dispatchUnsafe(backend, req)) match
      case Success(resp) => resp
      case Failure(t)    =>
        Response(id = req.id, error = Some(ResponseError(ErrorCodes.InternalError,
          s"${backend.id}: unhandled exception in ${req.method}: ${t.getMessage}")))

  private def dispatchUnsafe(backend: Backend, req: Request): Response =
    req.method match

      case Methods.Describe =>
        val isInteractive = backend.isInstanceOf[InteractiveBackend]
        val caps          = backend.capabilities
        val result = MessageBodies.DescribeResult(
          id              = backend.id,
          displayName     = backend.displayName,
          spiVersion      = backend.spiVersion,
          role            = "backend",
          acceptedSources = backend.acceptedSources,
          features        = caps.features.map(_.toString),
          outputs         = caps.outputs.map(_.toString),
          interactive     = isInteractive
        )
        Response(id = req.id, result = Some(writeJs(result)))

      case Methods.Compile =>
        val params = read[MessageBodies.CompileParams](req.params)
        val ir     = read[scalascript.ir.NormalizedModule](params.irJson)
        val opts   = BackendOptions(
          baseDir = params.baseDir.map(s => java.nio.file.Paths.get(s)),
          extra   = params.extra
        )
        val cr   = backend.compile(ir, opts)
        val wire = toWire(cr)
        Response(id = req.id, result = Some(writeJs(wire)))

      case Methods.OpenSession =>
        backend match
          case ib: InteractiveBackend =>
            val params  = read[MessageBodies.OpenSessionParams](req.params)
            val opts    = BackendOptions(
              baseDir = params.baseDir.map(s => java.nio.file.Paths.get(s)),
              extra   = params.extra
            )
            val session   = ib.openSession(opts)
            val sessionId = s"s${nextSessionId.getAndIncrement()}"
            sessions.put(sessionId, session)
            Response(id = req.id, result = Some(writeJs(MessageBodies.OpenSessionResult(sessionId))))
          case _ =>
            Response(id = req.id, error = Some(ResponseError(ErrorCodes.UnsupportedRole,
              s"${backend.id} is not interactive")))

      case Methods.SessionFeed =>
        val params  = read[MessageBodies.SessionFeedParams](req.params)
        sessions.get(params.sessionId) match
          case Some(session) =>
            val cr   = session.feed(params.block)
            val wire = toWire(cr)
            Response(id = req.id, result = Some(writeJs(wire)))
          case None =>
            Response(id = req.id, error = Some(ResponseError(ErrorCodes.InvalidParams,
              s"unknown sessionId: ${params.sessionId}")))

      case Methods.InvokeHandler =>
        val params = read[MessageBodies.InvokeHandlerParams](req.params)
        sessions.get(params.sessionId) match
          case Some(session) =>
            val value = session.invokeHandler(params.handlerRef, params.args)
            Response(id = req.id, result = Some(writeJs(MessageBodies.InvokeHandlerResult(value))))
          case None =>
            Response(id = req.id, error = Some(ResponseError(ErrorCodes.InvalidParams,
              s"unknown sessionId: ${params.sessionId}")))

      case Methods.SessionClose =>
        val params = read[MessageBodies.SessionCloseParams](req.params)
        sessions.remove(params.sessionId).foreach(_.close())
        Response(id = req.id)

      case Methods.Shutdown =>
        sessions.values.foreach(s => Try(s.close()))
        sessions.clear()
        Response(id = req.id)

      case other =>
        Response(id = req.id, error = Some(ResponseError(ErrorCodes.MethodNotFound,
          s"unknown method: $other")))

  // ── CompileResult → wire ─────────────────────────────────────────────────

  private def toWire(cr: CompileResult): MessageBodies.CompileResultWire = cr match
    case CompileResult.TextOutput(code, lang, _) =>
      MessageBodies.CompileResultWire(kind = "text", code = Some(code), language = Some(lang))
    case CompileResult.Segmented(segs) =>
      MessageBodies.CompileResultWire(kind = "segmented", segments = Some(segs.map(segToWire)))
    case CompileResult.BinaryOutput(bytes, mime, _) =>
      MessageBodies.CompileResultWire(kind = "binary", bytes = Some(bytes), mime = Some(mime))
    case CompileResult.Executed(stdout, stderr, exit) =>
      MessageBodies.CompileResultWire(kind = "executed", stdout = Some(stdout), stderr = Some(stderr), exit = Some(exit))
    case CompileResult.Failed(diags) =>
      MessageBodies.CompileResultWire(kind = "failed", diagnostics = Some(diags.map(diagToWire)))

  private def segToWire(s: Segment): MessageBodies.SegmentWire = s match
    case Segment.Code(lang, code)     => MessageBodies.SegmentWire(kind = "code",   language = lang, code = code)
    case Segment.Source(lang, source) => MessageBodies.SegmentWire(kind = "source", language = lang, source = source)
    case Segment.Asset(name, _, _)    => MessageBodies.SegmentWire(kind = "code",   language = name, code = "")

  private def diagToWire(d: Diagnostic): MessageBodies.DiagnosticWire = d match
    case Diagnostic.Unsupported(f, b)            => MessageBodies.DiagnosticWire(kind = "unsupported", message = s"unsupported feature $f", feature = f.toString, backend = b)
    case Diagnostic.UnknownBlockLanguage(lang)   => MessageBodies.DiagnosticWire(kind = "unknown-block", message = s"unknown block language: $lang", feature = lang)
    case Diagnostic.UnknownIntrinsic(name, back) => MessageBodies.DiagnosticWire(kind = "generic", message = s"unknown intrinsic $name on $back")
    case Diagnostic.UnsupportedJdbcUrl(db, url, back)          => MessageBodies.DiagnosticWire(kind = "generic", message = s"unsupported JDBC URL for $db on $back: $url")
    case Diagnostic.UnsupportedClientSideDbUrl(db, url, block) => MessageBodies.DiagnosticWire(kind = "generic", message = s"unsupported client-side DB URL for $db/$block: $url")
    case Diagnostic.Generic(msg, _)              => MessageBodies.DiagnosticWire(kind = "generic", message = msg)
