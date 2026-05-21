package scalascript.dap

import ujson.*
import java.net.Socket

/** Handles one DAP client connection. Phase 1: lifecycle only. */
final class DapSession(conn: Socket):
  private val in  = conn.getInputStream
  private val out = conn.getOutputStream
  private var seq = 0

  def run(): Unit =
    try
      while !conn.isClosed do
        val msg = DapProtocol.readMessage(in)
        handleMessage(msg)
    catch case _: java.io.EOFException | _: java.io.IOException => ()
    finally conn.close()

  private def handleMessage(msg: Value): Unit =
    val msgType = msg("type").str
    if msgType == "request" then
      val cmd    = msg("command").str
      val reqSeq = msg("seq").num.toInt
      cmd match
        case "initialize"        => handleInitialize(reqSeq, msg)
        case "launch"            => handleLaunch(reqSeq, msg)
        case "configurationDone" => sendResponse(reqSeq, "configurationDone", Obj())
        case "disconnect"        => handleDisconnect(reqSeq, msg)
        case other               => sendResponse(reqSeq, other, Obj(), success = false, message = s"unknown command: $other")

  private def handleInitialize(reqSeq: Int, @annotation.unused msg: Value): Unit =
    val caps = Obj(
      "supportsConfigurationDoneRequest" -> True,
      "supportTerminateDebuggee"         -> True,
    )
    sendResponse(reqSeq, "initialize", caps)
    sendEvent("initialized", Obj())

  private def handleLaunch(reqSeq: Int, @annotation.unused msg: Value): Unit =
    sendResponse(reqSeq, "launch", Obj())

  private def handleDisconnect(reqSeq: Int, @annotation.unused msg: Value): Unit =
    sendResponse(reqSeq, "disconnect", Obj())
    sendEvent("terminated", Obj())
    conn.close()

  private def sendResponse(reqSeq: Int, command: String, body: Value,
                           success: Boolean = true, message: String = ""): Unit =
    seq += 1
    val resp = Obj(
      "seq"         -> Num(seq),
      "type"        -> Str("response"),
      "request_seq" -> Num(reqSeq),
      "success"     -> (if success then True else False),
      "command"     -> Str(command),
      "body"        -> body,
    )
    if message.nonEmpty then resp("message") = Str(message)
    DapProtocol.writeMessage(out, resp)

  private def sendEvent(event: String, body: Value): Unit =
    seq += 1
    val evt = Obj(
      "seq"   -> Num(seq),
      "type"  -> Str("event"),
      "event" -> Str(event),
      "body"  -> body,
    )
    DapProtocol.writeMessage(out, evt)
