package scalascript.dap

import org.scalatest.funsuite.AnyFunSuite
import java.net.{ServerSocket, Socket}
import ujson.*

/** Phase 5: integration tests for stack frame inspection (stackTrace).
 *
 *  Verifies that after the interpreter is stopped at a breakpoint inside a
 *  function body, the client receives a multi-frame stackTrace with correct
 *  names and source lines.
 */
class DapStackTraceTest extends AnyFunSuite:

  // ─── helpers ─────────────────────────────────────────────────────────────

  private def withSession(source: String)(f: (Socket, os.Path) => Unit): Unit =
    val tmpFile    = os.temp(suffix = ".ssc")
    os.write.over(tmpFile, source)
    val serverSock = ServerSocket(0)
    serverSock.setReuseAddress(true)
    val port = serverSock.getLocalPort

    val serverThread = Thread.ofVirtual().start(() => {
      val conn = serverSock.accept()
      try
        val session    = DapSession(conn)
        val hooks      = session.mkHooks()
        val interp     = scalascript.interpreter.Interpreter(out = System.out, baseDir = Some(tmpFile / os.up))
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
    client.setSoTimeout(10000)
    try f(client, tmpFile)
    finally
      client.close()
      serverThread.join(5000)
      os.remove(tmpFile)

  private def send(client: Socket, msg: Value): Unit =
    DapProtocol.writeMessage(client.getOutputStream, msg)

  private def recv(client: Socket): Value =
    DapProtocol.readMessage(client.getInputStream)

  private def req(seq: Int, cmd: String, args: Value = Obj()): Value =
    Obj("seq" -> Num(seq), "type" -> Str("request"), "command" -> Str(cmd), "arguments" -> args)

  private def stopAt(client: Socket, filePath: String, line: Int): Int =
    var s = 1
    send(client, req(s, "initialize", Obj("clientName" -> Str("test")))); s += 1
    recv(client); recv(client)   // initialize resp + initialized event

    send(client, req(s, "setBreakpoints", Obj(
      "source"      -> Obj("path" -> Str(filePath)),
      "breakpoints" -> Arr(Obj("line" -> Num(line))),
    ))); s += 1
    recv(client)   // setBreakpoints resp

    send(client, req(s, "launch", Obj("program" -> Str(filePath)))); s += 1
    recv(client)   // launch resp

    send(client, req(s, "configurationDone")); s += 1
    recv(client)   // configurationDone resp

    recv(client)   // stopped event
    s

  // ─── tests ───────────────────────────────────────────────────────────────

  // Source layout (1-indexed lines):
  //  1: # Test
  //  2: (blank)
  //  3: ```scala
  //  4: def foo(x: Int): Int =
  //  5:   val result = x * 2
  //  6:   result
  //  7: (blank)
  //  8: val y = foo(5)
  //  9: val done = true
  // 10: ```
  //
  // lineOffset = 3 (0-indexed first code line), so:
  //   block line 1 → docLine 5  (val result = x * 2)
  //   block line 4 → docLine 8  (val y = foo(5))  ← call site pushed to callStack

  private val source =
    """|# Test
       |
       |```scala
       |def foo(x: Int): Int =
       |  val result = x * 2
       |  result
       |
       |val y = foo(5)
       |val done = true
       |```
       |""".stripMargin

  test("stackTrace inside function returns two frames with correct names and lines"):
    withSession(source) { (client, tmp) =>
      // Break at line 5: val result = x * 2 — inside foo
      var seq = stopAt(client, tmp.toString, line = 5)

      send(client, req(seq, "stackTrace", Obj("threadId" -> Num(1)))); seq += 1
      val resp = recv(client)
      assert(resp("type").str == "response" && resp("command").str == "stackTrace")

      val frames = resp("body")("stackFrames").arr
      assert(frames.length == 2, s"expected 2 frames, got ${frames.length}: $frames")

      // Frame 0 — stopped inside foo at line 5
      assert(frames(0)("id").num.toInt == 0)
      assert(frames(0)("line").num.toInt == 5)

      // Frame 1 — call site: foo was invoked on line 8
      assert(frames(1)("name").str == "foo")
      assert(frames(1)("line").num.toInt == 8)

      send(client, req(seq, "disconnect")); seq += 1
    }

  test("stackTrace at top level returns single frame"):
    withSession(source) { (client, tmp) =>
      // Break at line 9: val done = true — top level, no callers
      var seq = stopAt(client, tmp.toString, line = 9)

      send(client, req(seq, "stackTrace", Obj("threadId" -> Num(1)))); seq += 1
      val resp = recv(client)
      val frames = resp("body")("stackFrames").arr
      assert(frames.length == 1, s"expected 1 frame, got ${frames.length}")
      assert(frames(0)("line").num.toInt == 9)

      send(client, req(seq, "disconnect")); seq += 1
    }

  test("stackTrace source path matches the .ssc file"):
    withSession(source) { (client, tmp) =>
      var seq = stopAt(client, tmp.toString, line = 5)

      send(client, req(seq, "stackTrace", Obj("threadId" -> Num(1)))); seq += 1
      val resp = recv(client)
      val frames = resp("body")("stackFrames").arr
      // Every frame should report the source file
      frames.foreach { f =>
        val path = f("source")("path").str
        assert(path == tmp.toString, s"frame source path mismatch: $path")
      }

      send(client, req(seq, "disconnect")); seq += 1
    }
