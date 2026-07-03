package scalascript.dap

import org.scalatest.funsuite.AnyFunSuite
import java.net.{ServerSocket, Socket}
import ujson.*

/** Phase 2: integration test for breakpoints.
 *
 *  Spins a DapSession server on a random port, connects a test client,
 *  sets a breakpoint on line 4 of the fixture, and asserts that:
 *  1. `setBreakpoints` returns a verified breakpoint,
 *  2. the interpreter fires a `stopped` event when line 4 is reached, and
 *  3. `continue` resumes execution normally.
 *
 *  The interpreter thread blocks on [[DapSession.awaitReady]] until the
 *  client sends `configurationDone`, ensuring all breakpoints are
 *  registered before user code starts executing.
 */
class DapBreakpointTest extends AnyFunSuite:

  /** Minimal .ssc source: three `val` statements starting at document line 4. */
  private val sscSource =
    """|# Test
       |
       |```scala
       |val x = 1
       |val y = 2
       |val z = 3
       |```
       |""".stripMargin

  test("setBreakpoints + stopped event + continue"):
    val tmpFile = os.temp(suffix = ".ssc")
    os.write.over(tmpFile, sscSource)

    val serverSock = ServerSocket(0)
    serverSock.setReuseAddress(true)
    val port = serverSock.getLocalPort

    // ── Server side: one connection, DapSession wired to a debug interpreter ──
    val serverThread = Thread.ofVirtual().start(() => {
      val conn = serverSock.accept()
      try
        val session = DapSession(conn)
        val hooks   = session.mkHooks()

        val interp = scalascript.interpreter.Interpreter(
          out     = System.out,
          baseDir = Some(tmpFile / os.up),
        )
        interp.setDebugSourceFile(tmpFile.toString)
        interp.setDebugHooks(Some(hooks))

        val src    = os.read(tmpFile)
        val module = scalascript.parser.Parser.parse(src)

        // Start interpreter in a virtual thread.  It waits for configurationDone
        // via awaitReady() so all breakpoints are in place before execution.
        val interpThread = Thread.ofVirtual().start(() => {
          session.awaitReady()
          try interp.run(module)
          catch case _: Throwable => ()
        })

        // Handle DAP messages until disconnect.
        session.run()
        interpThread.interrupt()
      finally
        conn.close()
        serverSock.close()
    })

    // ── Client side ───────────────────────────────────────────────────────
    val client = Socket("127.0.0.1", port)
    client.setSoTimeout(10000)

    def send(msg: Value): Unit = DapProtocol.writeMessage(client.getOutputStream, msg)
    def recv(): Value          = DapProtocol.readMessage(client.getInputStream)

    try
      // 1. initialize
      send(Obj(
        "seq"       -> Num(1),
        "type"      -> Str("request"),
        "command"   -> Str("initialize"),
        "arguments" -> Obj("clientName" -> Str("test")),
      ))
      val initResp = recv()
      assert(initResp("type").str == "response" && initResp("command").str == "initialize")
      val initEvt = recv()
      assert(initEvt("type").str == "event" && initEvt("event").str == "initialized")

      // 2. setBreakpoints — document line 4 (1-based) is `val x = 1`.
      send(Obj(
        "seq"     -> Num(2),
        "type"    -> Str("request"),
        "command" -> Str("setBreakpoints"),
        "arguments" -> Obj(
          "source"      -> Obj("path" -> Str(tmpFile.toString)),
          "breakpoints" -> Arr(Obj("line" -> Num(4))),
        ),
      ))
      val bpResp = recv()
      assert(bpResp("type").str == "response" && bpResp("command").str == "setBreakpoints")
      assert(bpResp("body")("breakpoints").arr.head("line").num.toInt == 4)
      assert(bpResp("body")("breakpoints").arr.head("verified").bool == true)

      // 3. launch — just acknowledges; interpreter hasn't started yet.
      send(Obj(
        "seq"       -> Num(3),
        "type"      -> Str("request"),
        "command"   -> Str("launch"),
        "arguments" -> Obj("program" -> Str(tmpFile.toString)),
      ))
      val launchResp = recv()
      assert(launchResp("type").str == "response" && launchResp("command").str == "launch")

      // 4. configurationDone — unblocks the interpreter thread.
      send(Obj(
        "seq"       -> Num(4),
        "type"      -> Str("request"),
        "command"   -> Str("configurationDone"),
        "arguments" -> Obj(),
      ))
      val cdResp = recv()
      assert(cdResp("type").str == "response" && cdResp("command").str == "configurationDone")

      // 5. Expect a `stopped` event when the breakpoint is hit.
      val stopped = recv()
      assert(stopped("type").str == "event",    s"expected event, got: $stopped")
      assert(stopped("event").str == "stopped", s"expected stopped event, got: $stopped")
      assert(stopped("body")("reason").str == "breakpoint")

      // 6. continue — let the interpreter finish.
      send(Obj(
        "seq"       -> Num(5),
        "type"      -> Str("request"),
        "command"   -> Str("continue"),
        "arguments" -> Obj("threadId" -> Num(1)),
      ))
      val contResp = recv()
      assert(contResp("type").str == "response" && contResp("command").str == "continue")

      // 7. disconnect.
      send(Obj(
        "seq"       -> Num(6),
        "type"      -> Str("request"),
        "command"   -> Str("disconnect"),
        "arguments" -> Obj(),
      ))
    finally
      client.close()
      serverThread.join(5000)
      os.remove(tmpFile)
