package scalascript.compiler.plugin

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir
import scalascript.ir.Value
import scalascript.ir.LitValue
import scalascript.backend.spi.{BackendOptions, CompileResult}
import upickle.default.*

/** Exercises the subprocess wire protocol against a mock plugin written
 *  inline as a Bash one-liner.  The mock implements just enough of the
 *  protocol to support the handshake + a `compile` round-trip — no real
 *  compilation, just canned responses.  Lets us validate framing,
 *  serialisation, and the SubprocessBackend lifecycle without standing
 *  up a real subprocess plugin binary. */
class SubprocessBackendTest extends AnyFunSuite:

  // Mock plugin: supports describe, compile, openSession, session.feed,
  // invokeHandler, session.close, shutdown, AND host.* callback dispatch.
  // The `compile-with-cb` method triggers a host.greet callback mid-compile.
  private val mockScript: String =
    """
      |while IFS= read -r line; do
      |  method=$(printf '%s' "$line" | jq -r '.method')
      |  id=$(printf '%s' "$line" | jq -r '.id')
      |  case "$method" in
      |    describe)
      |      jq -nc --argjson id "$id" '{id:$id, result:{id:"mock",displayName:"Mock Plugin",spiVersion:"0.1.0",role:"backend",interactive:true,acceptedSources:[],features:["MutableState","PatternMatching"],outputs:["JavaScriptSource"]}}'
      |      ;;
      |    compile)
      |      jq -nc --argjson id "$id" '{id:$id, result:{kind:"text",code:"console.log(42)",language:"javascript"}}'
      |      ;;
      |    compile-with-cb)
      |      # Fire a host.greet callback before replying
      |      jq -nc --argjson id "999" '{id:999, method:"host.greet", params:{args:[{"$type":"Prim","v":{"$type":"StringL","value":"world"}}]}}'
      |      # Read core reply (the callback response)
      |      IFS= read -r cbreply
      |      # Extract greeted string from the callback result value
      |      greeted=$(printf '%s' "$cbreply" | jq -r '.result.value."$type"')
      |      jq -nc --argjson id "$id" '{id:$id, result:{kind:"text",code:"ok",language:"text"}}'
      |      ;;
      |    openSession)
      |      jq -nc --argjson id "$id" '{id:$id, result:{sessionId:"sess-1"}}'
      |      ;;
      |    "session.feed")
      |      jq -nc --argjson id "$id" '{id:$id, result:{kind:"executed",stdout:"fed",stderr:"",exit:0}}'
      |      ;;
      |    invokeHandler)
      |      jq -nc --argjson id "$id" '{id:$id, result:{value:{"$type":"Prim","v":{"$type":"IntL","value":99}}}}'
      |      ;;
      |    "session.close")
      |      jq -nc --argjson id "$id" '{id:$id, result:{}}'
      |      ;;
      |    shutdown)
      |      jq -nc --argjson id "$id" '{id:$id, result:{ok:true}}'
      |      exit 0
      |      ;;
      |    *)
      |      jq -nc --argjson id "$id" --arg m "$method" '{id:$id, error:{code:-32601, message:("unknown method: " + $m)}}'
      |      ;;
      |  esac
      |done
      |""".stripMargin

  private def haveJq: Boolean =
    try os.proc("which", "jq").call(check = false).exitCode == 0
    catch case _: Throwable => false

  private def spawnMock(): Option[SubprocessBackend] =
    if !haveJq then None
    else
      val script = os.temp(mockScript, suffix = ".sh", deleteOnExit = true)
      os.perms.set(script, "rwxr--r--")
      SubprocessBackend.spawn("bash", List(script.toString)).toOption

  // ── Tests ──────────────────────────────────────────────────────────────

  test("describe handshake fills descriptor from plugin response"):
    spawnMock() match
      case None      => cancel("jq not available — skipping subprocess test")
      case Some(plg) =>
        assert(plg.id              == "mock")
        assert(plg.displayName     == "Mock Plugin")
        assert(plg.spiVersion      == "0.1.0")
        // features/outputs are surfaced through the Capabilities map
        assert(plg.capabilities.features.exists(_.toString == "MutableState"))
        plg.shutdown()

  test("compile round-trip returns the canned TextOutput"):
    spawnMock() match
      case None      => cancel("jq not available — skipping subprocess test")
      case Some(plg) =>
        val module = ir.NormalizedModule(manifest = None, sections = Nil)
        val opts   = BackendOptions()
        plg.compile(module, opts) match
          case CompileResult.TextOutput(code, lang, _) =>
            assert(code == "console.log(42)")
            assert(lang == "javascript")
          case other =>
            fail(s"expected TextOutput, got $other")
        plg.shutdown()

  test("shutdown is idempotent"):
    spawnMock() match
      case None      => cancel("jq not available — skipping subprocess test")
      case Some(plg) =>
        plg.shutdown()
        plg.shutdown()      // no-throw on second call
        succeed

  // ── Stage 6+/B — ir.Value round-trip ──────────────────────────────────

  test("ir.Value serialises and deserialises via upickle"):
    val v: Value = Value.Prim(LitValue.IntL(42L))
    val json = write(v)
    val back = read[Value](json)
    assert(back == v)

  test("ir.Value.Arr and Dict round-trip"):
    val v: Value = Value.Arr(List(
      Value.Prim(LitValue.StringL("hello")),
      Value.Dict(Map("x" -> Value.Prim(LitValue.BoolL(true)))),
      Value.Null
    ))
    assert(read[Value](write(v)) == v)

  // ── Stage 6+/B — interactive session over subprocess ──────────────────

  test("openSession returns a working SubprocessSession"):
    spawnMock() match
      case None      => cancel("jq not available — skipping subprocess test")
      case Some(plg) =>
        assert(plg.isInteractive)
        val session = plg.openSession(BackendOptions())
        val block   = ir.Content.CodeBlock(source = "println(1)", body = Nil)
        plg.call(Methods.SessionFeed,
          writeJs(MessageBodies.SessionFeedParams("sess-1", block))) match
          case Right(_) => succeed
          case Left(e)  => fail(s"session.feed failed: $e")
        session.close()
        plg.shutdown()

  test("invokeHandler round-trip returns Value.Prim(IntL(99))"):
    spawnMock() match
      case None      => cancel("jq not available — skipping subprocess test")
      case Some(plg) =>
        val ref    = ir.SymbolRef(ir.QualifiedName("test.handler"))
        val params = MessageBodies.InvokeHandlerParams("sess-1", ref, Nil)
        plg.call(Methods.InvokeHandler, writeJs(params)) match
          case Right(json) =>
            val result = read[MessageBodies.InvokeHandlerResult](json)
            assert(result.value == Value.Prim(LitValue.IntL(99L)))
          case Left(e) =>
            fail(s"invokeHandler failed: $e")
        plg.shutdown()

  // ── Stage 6+/C — HostCallback dispatch ────────────────────────────────

  test("registerHostCallback dispatched when plugin calls host.greet"):
    spawnMock() match
      case None      => cancel("jq not available — skipping subprocess test")
      case Some(plg) =>
        var receivedArg: ir.Value = Value.Null
        plg.registerHostCallback("greet", args => {
          receivedArg = args.headOption.getOrElse(Value.Null)
          Value.Prim(LitValue.StringL("hello, world!"))
        })
        // compile-with-cb triggers host.greet before replying
        plg.call("compile-with-cb", ujson.Obj()) match
          case Right(_) => succeed
          case Left(e)  => fail(s"compile-with-cb failed: $e")
        assert(receivedArg == Value.Prim(LitValue.StringL("world")))
        plg.shutdown()

  test("unregistered host callback returns MethodNotFound error to plugin"):
    spawnMock() match
      case None      => cancel("jq not available — skipping subprocess test")
      case Some(plg) =>
        // compile-with-cb fires host.greet but no callback registered →
        // plugin still gets an error response and then sends back its result
        plg.call("compile-with-cb", ujson.Obj()) match
          case Right(_) => succeed   // plugin proceeds despite callback error
          case Left(e)  => fail(s"expected plugin to recover, got error: $e")
        plg.shutdown()
