package scalascript.plugin

import scalascript.backend.spi.{
  Backend, BackendOptions, CompileResult, Diagnostic,
  InteractiveBackend, Segment, Session
}
import scalascript.compiler.plugin.*
import scalascript.ir
import upickle.default.*
import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter, PrintWriter}
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Success, Failure}

/** JVM-only subprocess host for native `ssc`.
 *
 *  Usage: java -cp plugin.jar:ssc-plugin-host.jar scalascript.plugin.SubprocessHost plugin.jar
 *
 *  Loads the plugin JAR via URLClassLoader, discovers Backend implementations
 *  via ServiceLoader, then serves the stdio-json wire protocol (same as any
 *  other subprocess plugin) so native `ssc` can use the plugin unchanged. */
object SubprocessHost:

  def main(args: Array[String]): Unit =
    if args.isEmpty then
      System.err.println("[ssc-plugin-host] Usage: SubprocessHost <plugin.jar>")
      System.exit(1)

    val jarPath  = java.nio.file.Paths.get(args(0)).toAbsolutePath
    val backends = loadBackends(jarPath)

    if backends.isEmpty then
      System.err.println(s"[ssc-plugin-host] No Backend found in ${args(0)}")
      System.exit(1)

    val backend   = backends.head
    val sessions  = new ConcurrentHashMap[String, Session]()
    val counter   = new AtomicLong(1L)

    val reader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"))
    val writer = new PrintWriter(new OutputStreamWriter(System.out, "UTF-8"), true)

    var running = true
    while running do
      val line = reader.readLine()
      if line == null then
        running = false
      else if line.nonEmpty then
        Try(read[Request](line)) match
          case Failure(_) => ()
          case Success(req) =>
            val resp = dispatch(req, backend, sessions, counter)
            writer.println(write(resp))
            writer.flush()
            if req.method == Methods.Shutdown then running = false

  private def loadBackends(jar: java.nio.file.Path): List[Backend] =
    val url = jar.toUri.toURL
    val cl  = new URLClassLoader(Array(url), classOf[Backend].getClassLoader)
    ServiceLoader.load(classOf[Backend], cl).iterator.asScala.toList

  private def dispatch(
      req:      Request,
      backend:  Backend,
      sessions: ConcurrentHashMap[String, Session],
      counter:  AtomicLong
  ): Response =
    try req.method match
      case Methods.Describe =>
        val desc = MessageBodies.DescribeResult(
          id              = backend.id,
          displayName     = backend.displayName,
          spiVersion      = backend.spiVersion,
          role            = "backend",
          acceptedSources = backend.acceptedSources,
          interactive     = backend.isInstanceOf[InteractiveBackend]
        )
        Response(id = req.id, result = Some(writeJs(desc)))

      case Methods.Compile =>
        val params = read[MessageBodies.CompileParams](req.params)
        val module = read[ir.NormalizedModule](params.irJson)
        val opts   = BackendOptions(
          baseDir = params.baseDir.map(s => java.nio.file.Paths.get(s)),
          extra   = params.extra
        )
        val result = backend.compile(module, opts)
        Response(id = req.id, result = Some(writeJs(toWire(result))))

      case Methods.OpenSession =>
        backend match
          case ib: InteractiveBackend =>
            val params    = read[MessageBodies.OpenSessionParams](req.params)
            val opts      = BackendOptions(
              baseDir = params.baseDir.map(s => java.nio.file.Paths.get(s)),
              extra   = params.extra
            )
            val session   = ib.openSession(opts)
            val sessionId = s"s${counter.getAndIncrement()}"
            sessions.put(sessionId, session)
            Response(id = req.id, result = Some(writeJs(MessageBodies.OpenSessionResult(sessionId))))
          case _ =>
            errResp(req.id, ErrorCodes.UnsupportedRole, s"${backend.id} does not support openSession")

      case Methods.SessionFeed =>
        val params  = read[MessageBodies.SessionFeedParams](req.params)
        val session = sessions.get(params.sessionId)
        if session == null then errResp(req.id, ErrorCodes.InvalidParams, s"unknown session ${params.sessionId}")
        else Response(id = req.id, result = Some(writeJs(toWire(session.feed(params.block)))))

      case Methods.SessionClose =>
        val params  = read[MessageBodies.SessionCloseParams](req.params)
        val session = sessions.remove(params.sessionId)
        if session != null then session.close()
        Response(id = req.id, result = Some(ujson.Null))

      case Methods.InvokeHandler =>
        val params  = read[MessageBodies.InvokeHandlerParams](req.params)
        val session = sessions.get(params.sessionId)
        if session == null then errResp(req.id, ErrorCodes.InvalidParams, s"unknown session ${params.sessionId}")
        else
          val value = session.invokeHandler(params.handlerRef, params.args)
          Response(id = req.id, result = Some(writeJs(MessageBodies.InvokeHandlerResult(value))))

      case Methods.Shutdown =>
        sessions.values.asScala.foreach(s => Try(s.close()))
        sessions.clear()
        Response(id = req.id, result = Some(ujson.Null))

      case other =>
        errResp(req.id, ErrorCodes.MethodNotFound, s"unknown method: $other")
    catch case e: Exception =>
      errResp(req.id, ErrorCodes.InternalError, e.getMessage)

  private def errResp(id: Long, code: Int, msg: String): Response =
    Response(id = id, error = Some(ResponseError(code, msg)))

  private def toWire(result: CompileResult): MessageBodies.CompileResultWire = result match
    case CompileResult.TextOutput(code, language, _) =>
      MessageBodies.CompileResultWire(kind = "text", code = Some(code), language = Some(language))
    case CompileResult.Segmented(segments) =>
      MessageBodies.CompileResultWire(kind = "segmented", segments = Some(segments.map(segToWire)))
    case CompileResult.BinaryOutput(bytes, mime, _) =>
      MessageBodies.CompileResultWire(kind = "binary", bytes = Some(bytes), mime = Some(mime))
    case CompileResult.Executed(stdout, stderr, exit) =>
      MessageBodies.CompileResultWire(kind = "executed", stdout = Some(stdout), stderr = Some(stderr), exit = Some(exit))
    case CompileResult.Failed(diags) =>
      MessageBodies.CompileResultWire(kind = "failed", diagnostics = Some(diags.map(diagToWire)))

  private def segToWire(seg: Segment): MessageBodies.SegmentWire = seg match
    case Segment.Code(language, code)     => MessageBodies.SegmentWire("code",   language = language, code = code)
    case Segment.Source(language, source) => MessageBodies.SegmentWire("source", language = language, source = source)
    case Segment.Asset(name, _, mime)     => MessageBodies.SegmentWire("asset",  language = mime,     code = name)

  private def diagToWire(diag: Diagnostic): MessageBodies.DiagnosticWire = diag match
    case Diagnostic.Unsupported(feature, backend) =>
      MessageBodies.DiagnosticWire("unsupported",   s"unsupported: $feature", feature.toString, backend)
    case Diagnostic.UnknownBlockLanguage(lang) =>
      MessageBodies.DiagnosticWire("unknown-block", s"unknown block language: $lang", lang)
    case Diagnostic.Generic(message, _) =>
      MessageBodies.DiagnosticWire("generic",       message)
    case other =>
      MessageBodies.DiagnosticWire("generic",       other.toString)
