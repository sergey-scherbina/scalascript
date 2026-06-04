package scalascript.dap

import org.scalatest.funsuite.AnyFunSuite
import java.net.{ServerSocket, Socket}
import ujson.*

class DapSessionPhase1Test extends AnyFunSuite:

  /** Start a DapSession server on a random port, connect a test client socket,
   *  and run the given test body with the client socket.
   */
  private def withSession(body: Socket => Unit): Unit =
    val server = ServerSocket(0)
    server.setReuseAddress(true)
    val port = server.getLocalPort
    val sessionThread = Thread(() => {
      val conn = server.accept()
      try DapSession(conn).run()
      finally server.close()
    }, "dap-session-test")
    sessionThread.setDaemon(true)
    sessionThread.start()

    val client = Socket("127.0.0.1", port)
    client.setSoTimeout(10000)
    try body(client)
    finally
      client.close()
      sessionThread.join(2000)

  private def sendRequest(sock: Socket, seqN: Int, command: String, args: Value): Unit =
    val msg = Obj(
      "seq"       -> Num(seqN),
      "type"      -> Str("request"),
      "command"   -> Str(command),
      "arguments" -> args,
    )
    DapProtocol.writeMessage(sock.getOutputStream, msg)

  private def readMsg(sock: Socket): Value =
    DapProtocol.readMessage(sock.getInputStream)

  test("initialize response has supportsConfigurationDoneRequest"):
    withSession { sock =>
      sendRequest(sock, 1, "initialize", Obj("adapterID" -> Str("ssc")))
      val resp = readMsg(sock)
      assert(resp("type").str == "response")
      assert(resp("command").str == "initialize")
      assert(resp("success").bool == true)
      assert(resp("body")("supportsConfigurationDoneRequest").bool == true)
      // also expect initialized event
      val evt = readMsg(sock)
      assert(evt("type").str == "event")
      assert(evt("event").str == "initialized")
    }

  test("launch returns success response"):
    withSession { sock =>
      sendRequest(sock, 1, "initialize", Obj("adapterID" -> Str("ssc")))
      readMsg(sock) // initialize response
      readMsg(sock) // initialized event
      sendRequest(sock, 2, "launch", Obj("program" -> Str("test.ssc")))
      val resp = readMsg(sock)
      assert(resp("type").str == "response")
      assert(resp("command").str == "launch")
      assert(resp("success").bool == true)
    }

  test("disconnect sends terminated event"):
    withSession { sock =>
      sendRequest(sock, 1, "initialize", Obj("adapterID" -> Str("ssc")))
      readMsg(sock) // initialize response
      readMsg(sock) // initialized event
      sendRequest(sock, 2, "launch", Obj("program" -> Str("test.ssc")))
      readMsg(sock) // launch response
      sendRequest(sock, 3, "disconnect", Obj())
      val resp = readMsg(sock)
      assert(resp("type").str == "response")
      assert(resp("command").str == "disconnect")
      assert(resp("success").bool == true)
      val evt = readMsg(sock)
      assert(evt("type").str == "event")
      assert(evt("event").str == "terminated")
    }
