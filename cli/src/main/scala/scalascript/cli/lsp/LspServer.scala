package scalascript.cli.lsp

import java.io.{InputStream, OutputStream, PrintStream}
import LspProtocol.*

/** Hand-rolled LSP server.  Reads JSON-RPC frames from `in`, dispatches to
 *  `Handlers`, and writes responses (plus side-effect notifications such as
 *  `publishDiagnostics`) to `out`.
 *
 *  Lifecycle:
 *   - `initialize`  → returns capabilities; client must send `initialized`
 *                     notification before issuing other requests.
 *   - `shutdown`    → flips an internal flag so subsequent requests return
 *                     `InvalidRequest`.
 *   - `exit`        → terminates the run loop (exit code 0 if shutdown was
 *                     received first, otherwise 1).
 *
 *  Logging goes to `err` (stderr by default) so it doesn't corrupt the
 *  framed stdout channel. */
class LspServer(
    in:  InputStream,
    out: OutputStream,
    err: PrintStream = System.err
):
  private val docs     = new Documents
  private val handlers = new Handlers(docs)

  @volatile private var shutdownRequested = false
  @volatile private var exitCode: Int     = 1

  /** Run the server until `exit` is received (or stdin is closed).
   *
   *  Returns the exit code we should propagate (0 if `shutdown` preceded
   *  `exit`, 1 otherwise). */
  def run(): Int =
    var running = true
    while running do
      LspProtocol.readFrame(in) match
        case Left(errMsg) =>
          err.println(s"[lsp] frame error: $errMsg")
          // Per LSP spec: send back an InvalidRequest error response.
          // We don't have an id; reply with null id.
          val resp = LspProtocol.failure(ujson.Null, ErrorCodes.ParseError, errMsg)
          LspProtocol.writeFrame(out, LspProtocol.encode(resp))

        case Right(None) =>
          // Clean EOF — client closed stdin.
          running = false

        case Right(Some(payload)) =>
          LspProtocol.decode(payload) match
            case Left(errMsg) =>
              err.println(s"[lsp] decode error: $errMsg")
              val resp = LspProtocol.failure(ujson.Null, ErrorCodes.ParseError, errMsg)
              LspProtocol.writeFrame(out, LspProtocol.encode(resp))

            case Right(msg) =>
              dispatch(msg) match
                case DispatchResult.Continue          => ()
                case DispatchResult.Exit(code)        =>
                  exitCode = code; running = false
    exitCode

  // ─── Dispatch ───────────────────────────────────────────────────────

  private enum DispatchResult:
    case Continue
    case Exit(code: Int)

  private def dispatch(msg: Message): DispatchResult = msg match
    case Request(id, method, params) =>
      handleRequest(id, method, params); DispatchResult.Continue

    case Notification(method, params) =>
      handleNotification(method, params)

    case _: Response =>
      // Server doesn't send requests TO the client in MVP, so any
      // incoming Response is unexpected — log and continue.
      err.println("[lsp] unexpected response from client")
      DispatchResult.Continue

  private def handleRequest(id: ujson.Value, method: String, params: ujson.Value): Unit =
    if shutdownRequested && method != "exit" then
      // After shutdown, only `exit` is honoured; any other request gets
      // an InvalidRequest error.
      reply(LspProtocol.failure(id, ErrorCodes.InvalidRequest, "server shut down"))
      return

    try
      method match
        case "initialize" =>
          val result = handlers.initialize(params)
          reply(LspProtocol.success(id, result))

        case "shutdown" =>
          shutdownRequested = true
          reply(LspProtocol.success(id, ujson.Null))

        case "textDocument/definition" =>
          val result = handlers.definition(params)
          reply(LspProtocol.success(id, result))

        case "textDocument/hover" =>
          val result = handlers.hover(params)
          reply(LspProtocol.success(id, result))

        case "textDocument/completion" =>
          val result = handlers.completion(params)
          reply(LspProtocol.success(id, result))

        case "textDocument/references" =>
          val result = handlers.references(params)
          reply(LspProtocol.success(id, result))

        case "textDocument/prepareRename" =>
          val result = handlers.prepareRename(params)
          reply(LspProtocol.success(id, result))

        case "textDocument/rename" =>
          val result = handlers.rename(params)
          reply(LspProtocol.success(id, result))

        case "textDocument/documentSymbol" =>
          val result = handlers.documentSymbol(params)
          reply(LspProtocol.success(id, result))

        case "workspace/symbol" =>
          val result = handlers.workspaceSymbol(params)
          reply(LspProtocol.success(id, result))

        case other =>
          reply(LspProtocol.failure(id, ErrorCodes.MethodNotFound, s"method not found: $other"))
    catch case e: Exception =>
      err.println(s"[lsp] handler error for $method: ${e.getClass.getSimpleName}: ${e.getMessage}")
      reply(LspProtocol.failure(id, ErrorCodes.InternalError, s"${e.getClass.getSimpleName}: ${e.getMessage}"))

  private def handleNotification(method: String, params: ujson.Value): DispatchResult =
    method match
      case "initialized" =>
        // No-op; the client tells us it's ready to receive requests.
        DispatchResult.Continue

      case "exit" =>
        val code = if shutdownRequested then 0 else 1
        DispatchResult.Exit(code)

      case "textDocument/didOpen" =>
        try handlers.didOpen(params).foreach(pushNotification)
        catch case e: Exception =>
          err.println(s"[lsp] didOpen error: ${e.getMessage}")
        DispatchResult.Continue

      case "textDocument/didChange" =>
        try handlers.didChange(params).foreach(pushNotification)
        catch case e: Exception =>
          err.println(s"[lsp] didChange error: ${e.getMessage}")
        DispatchResult.Continue

      case "textDocument/didClose" =>
        try handlers.didClose(params)
        catch case e: Exception =>
          err.println(s"[lsp] didClose error: ${e.getMessage}")
        DispatchResult.Continue

      case _ =>
        // Unknown notifications are ignored per LSP spec.
        DispatchResult.Continue

  private def reply(resp: Response): Unit =
    LspProtocol.writeFrame(out, LspProtocol.encode(resp))

  private def pushNotification(n: Notification): Unit =
    LspProtocol.writeFrame(out, LspProtocol.encode(n))

end LspServer

object LspServer:

  /** CLI entry point: `ssc lsp`. Reads framed JSON-RPC from stdin, writes
   *  to stdout, logs to stderr. */
  def runStdio(): Int =
    val server = new LspServer(System.in, System.out, System.err)
    server.run()
