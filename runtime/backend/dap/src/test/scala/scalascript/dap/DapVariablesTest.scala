package scalascript.dap

import org.scalatest.funsuite.AnyFunSuite
import java.net.{ServerSocket, Socket}
import ujson.*

/** Phase 4: integration tests for variable inspection (scopes / variables).
 *
 *  Verifies that after the interpreter is stopped at a breakpoint, the client
 *  can retrieve the current local scope and inspect values including nested
 *  structures (List, InstanceV).
 */
class DapVariablesTest extends AnyFunSuite:

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

  /** Drive initialize → setBreakpoints → launch → configurationDone → stopped.
   *  Returns the client message seq after configurationDone. */
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

  test("scopes returns Locals scope with positive variablesReference"):
    val source =
      """|# Test
         |
         |```scala
         |val x = 42
         |val y = "hello"
         |val z = x + 1
         |```
         |""".stripMargin

    withSession(source) { (client, tmp) =>
      // Breakpoint on line 6 (val z = x + 1): x and y are in scope
      var seq = stopAt(client, tmp.toString, line = 6)

      send(client, req(seq, "scopes", Obj("frameId" -> Num(0)))); seq += 1
      val resp = recv(client)
      assert(resp("type").str == "response" && resp("command").str == "scopes")
      val scopes = resp("body")("scopes").arr
      assert(scopes.nonEmpty, "expected at least one scope")
      val locals = scopes.find(_("name").str == "Locals").getOrElse(
        fail("no 'Locals' scope in response")
      )
      val localsRef = locals("variablesReference").num.toInt
      assert(localsRef > 0, "Locals variablesReference must be positive")

      send(client, req(seq, "disconnect")); seq += 1
    }

  test("variables for Locals scope contains user-defined values"):
    val source =
      """|# Test
         |
         |```scala
         |val x = 42
         |val y = "hello"
         |val z = x + 1
         |```
         |""".stripMargin

    withSession(source) { (client, tmp) =>
      var seq = stopAt(client, tmp.toString, line = 6)

      send(client, req(seq, "scopes", Obj("frameId" -> Num(0)))); seq += 1
      val scopesResp = recv(client)
      val localsRef  = scopesResp("body")("scopes").arr
        .find(_("name").str == "Locals").get("variablesReference").num.toInt

      send(client, req(seq, "variables", Obj("variablesReference" -> Num(localsRef)))); seq += 1
      val varResp = recv(client)
      assert(varResp("type").str == "response" && varResp("command").str == "variables")

      val vars = varResp("body")("variables").arr
      val byName = vars.map(v => v("name").str -> v).toMap

      // x = 42
      assert(byName.contains("x"), s"expected 'x' in variables, got: ${byName.keys.toSeq.sorted}")
      assert(byName("x")("value").str == "42")
      assert(byName("x")("type").str == "Int")
      assert(byName("x")("variablesReference").num.toInt == 0)  // leaf

      // y = "hello"
      assert(byName.contains("y"), "expected 'y' in variables")
      assert(byName("y")("value").str == "\"hello\"")
      assert(byName("y")("type").str == "String")

      send(client, req(seq, "disconnect")); seq += 1
    }

  test("List value has positive variablesReference and expandable children"):
    val source =
      """|# Test
         |
         |```scala
         |val xs = List(10, 20, 30)
         |val done = true
         |```
         |""".stripMargin

    withSession(source) { (client, tmp) =>
      // breakpoint on line 5 (val done = true): xs is already bound
      var seq = stopAt(client, tmp.toString, line = 5)

      send(client, req(seq, "scopes", Obj("frameId" -> Num(0)))); seq += 1
      val scopesResp = recv(client)
      val localsRef  = scopesResp("body")("scopes").arr
        .find(_("name").str == "Locals").get("variablesReference").num.toInt

      send(client, req(seq, "variables", Obj("variablesReference" -> Num(localsRef)))); seq += 1
      val varResp = recv(client)
      val vars    = varResp("body")("variables").arr
      val byName  = vars.map(v => v("name").str -> v).toMap

      assert(byName.contains("xs"), s"expected 'xs', got: ${byName.keys.toSeq.sorted}")
      assert(byName("xs")("type").str == "List")
      val xsRef = byName("xs")("variablesReference").num.toInt
      assert(xsRef > 0, "List value must have positive variablesReference")

      // expand xs
      send(client, req(seq, "variables", Obj("variablesReference" -> Num(xsRef)))); seq += 1
      val xsResp  = recv(client)
      val xsItems = xsResp("body")("variables").arr
      assert(xsItems.length == 3)
      assert(xsItems(0)("name").str == "[0]" && xsItems(0)("value").str == "10")
      assert(xsItems(1)("name").str == "[1]" && xsItems(1)("value").str == "20")
      assert(xsItems(2)("name").str == "[2]" && xsItems(2)("value").str == "30")

      send(client, req(seq, "disconnect")); seq += 1
    }
