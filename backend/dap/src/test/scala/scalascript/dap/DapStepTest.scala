package scalascript.dap

import org.scalatest.funsuite.AnyFunSuite
import java.net.{ServerSocket, Socket}
import ujson.*

/** Phase 3: integration tests for step execution (next / stepIn / stepOut).
 *
 *  Each test spins a DapSession server on a random port, connects a test client,
 *  drives the DAP protocol to a stopped state, exercises a step command, and
 *  asserts the resulting `stopped` event.
 */
class DapStepTest extends AnyFunSuite:

  // ─── helpers ─────────────────────────────────────────────────────────────

  private def withSession(source: String)(f: (Socket, os.Path) => Unit): Unit =
    val tmpFile   = os.temp(suffix = ".ssc")
    os.write.over(tmpFile, source)
    val serverSock = ServerSocket(0)
    serverSock.setReuseAddress(true)
    val port = serverSock.getLocalPort

    val serverThread = Thread.ofVirtual().start(() => {
      val conn = serverSock.accept()
      try
        val session = DapSession(conn)
        val hooks   = session.mkHooks()
        val interp  = scalascript.interpreter.Interpreter(out = System.out, baseDir = Some(tmpFile / os.up))
        interp.setDebugSourceFile(tmpFile.toString)
        interp.setDebugHooks(Some(hooks))
        val module     = scalascript.parser.Parser.parse(os.read(tmpFile))
        val interpThread = Thread.ofVirtual().start(() => {
          session.awaitReady()
          try interp.run(module) catch case _: Throwable => ()
        })
        session.run()
        interpThread.interrupt()
      finally
        conn.close()
        serverSock.close()
    })

    val client = Socket("127.0.0.1", port)
    try f(client, tmpFile)
    finally
      client.close()
      serverThread.join(5000)
      os.remove(tmpFile)

  private def send(client: Socket, msg: Value): Unit =
    DapProtocol.writeMessage(client.getOutputStream, msg)

  private def recv(client: Socket): Value =
    DapProtocol.readMessage(client.getInputStream)

  private def request(seq: Int, cmd: String, args: Value = Obj()): Value =
    Obj("seq" -> Num(seq), "type" -> Str("request"), "command" -> Str(cmd), "arguments" -> args)

  /** Drive through initialize → setBreakpoints → launch → configurationDone,
   *  collect messages until the first `stopped` event, return (client seq after, stopped msg). */
  private def initAndWaitForStop(client: Socket, filePath: String, breakLine: Int): (Int, Value) =
    var seq = 1

    send(client, request(seq, "initialize", Obj("clientName" -> Str("test"))))
    seq += 1
    recv(client) // initialize response
    recv(client) // initialized event

    send(client, request(seq, "setBreakpoints", Obj(
      "source"      -> Obj("path" -> Str(filePath)),
      "breakpoints" -> Arr(Obj("line" -> Num(breakLine))),
    )))
    seq += 1
    recv(client) // setBreakpoints response

    send(client, request(seq, "launch", Obj("program" -> Str(filePath))))
    seq += 1
    recv(client) // launch response

    send(client, request(seq, "configurationDone"))
    seq += 1
    recv(client) // configurationDone response

    val stopped = recv(client) // stopped event (breakpoint)
    (seq, stopped)

  // ─── next (step over) ────────────────────────────────────────────────────

  /** Three sequential `val` declarations; breakpoint on first, then `next` twice. */
  test("next steps over sequential statements"):
    val source =
      """|# Test
         |
         |```scala
         |val a = 1
         |val b = 2
         |val c = 3
         |```
         |""".stripMargin

    withSession(source) { (client, tmp) =>
      val (seq0, stopped1) = initAndWaitForStop(client, tmp.toString, breakLine = 4)
      assert(stopped1("body")("reason").str == "breakpoint")
      var seq = seq0

      // first next → should stop at line 5
      send(client, request(seq, "next", Obj("threadId" -> Num(1))))
      seq += 1
      val r1 = recv(client)  // next response
      assert(r1("type").str == "response" && r1("command").str == "next")
      val s2 = recv(client)  // stopped event
      assert(s2("event").str == "stopped")
      assert(s2("body")("reason").str == "step")
      assert(s2("body")("threadId").num.toInt == 1)

      // second next → should stop at line 6
      send(client, request(seq, "next", Obj("threadId" -> Num(1))))
      seq += 1
      recv(client)  // next response
      val s3 = recv(client)
      assert(s3("event").str == "stopped")
      assert(s3("body")("reason").str == "step")

      // continue → let interpreter finish
      send(client, request(seq, "continue", Obj("threadId" -> Num(1))))
      seq += 1
      recv(client) // continue response

      send(client, request(seq, "disconnect"))
    }

  // ─── stepIn ──────────────────────────────────────────────────────────────

  /** Breakpoint on the call site; stepIn should enter the function body. */
  test("stepIn enters function body"):
    val source =
      """|# Test
         |
         |```scala
         |def double(n: Int): Int = n * 2
         |val x = double(5)
         |val y = 0
         |```
         |""".stripMargin
    // Lines: 4 = def double, 5 = val x = double(5), 6 = val y = 0

    withSession(source) { (client, tmp) =>
      val (seq0, stopped1) = initAndWaitForStop(client, tmp.toString, breakLine = 5)
      assert(stopped1("body")("reason").str == "breakpoint")
      var seq = seq0

      // stepIn → should stop inside double (line 4, depth 1)
      send(client, request(seq, "stepIn", Obj("threadId" -> Num(1))))
      seq += 1
      val r1 = recv(client)
      assert(r1("type").str == "response" && r1("command").str == "stepIn")
      val s2 = recv(client)
      assert(s2("event").str == "stopped")
      assert(s2("body")("reason").str == "step")

      // continue → let interpreter finish
      send(client, request(seq, "continue", Obj("threadId" -> Num(1))))
      seq += 1
      recv(client)

      send(client, request(seq, "disconnect"))
    }

  // ─── stepOut ─────────────────────────────────────────────────────────────

  /** Stop inside function, stepOut should return to caller scope. */
  test("stepOut returns to caller"):
    val source =
      """|# Test
         |
         |```scala
         |def double(n: Int): Int = n * 2
         |val x = double(5)
         |val y = 0
         |```
         |""".stripMargin
    // breakpoint on line 4 (inside double's body), then stepOut

    withSession(source) { (client, tmp) =>
      val (seq0, stopped1) = initAndWaitForStop(client, tmp.toString, breakLine = 4)
      assert(stopped1("body")("reason").str == "breakpoint")
      var seq = seq0

      // stepOut → should stop at caller scope (line 5 or beyond)
      send(client, request(seq, "stepOut", Obj("threadId" -> Num(1))))
      seq += 1
      val r1 = recv(client)
      assert(r1("type").str == "response" && r1("command").str == "stepOut")
      val s2 = recv(client)
      assert(s2("event").str == "stopped")
      assert(s2("body")("reason").str == "step")

      // continue → let interpreter finish
      send(client, request(seq, "continue", Obj("threadId" -> Num(1))))
      seq += 1
      recv(client)

      send(client, request(seq, "disconnect"))
    }
