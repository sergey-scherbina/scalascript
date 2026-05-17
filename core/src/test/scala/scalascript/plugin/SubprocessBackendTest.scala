package scalascript.plugin

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir
import scalascript.backend.spi.{BackendOptions, CompileResult}

/** Exercises the subprocess wire protocol against a mock plugin written
 *  inline as a Bash one-liner.  The mock implements just enough of the
 *  protocol to support the handshake + a `compile` round-trip — no real
 *  compilation, just canned responses.  Lets us validate framing,
 *  serialisation, and the SubprocessBackend lifecycle without standing
 *  up a real subprocess plugin binary. */
class SubprocessBackendTest extends AnyFunSuite:

  // Mock plugin script — reads one JSON line per request, writes one JSON
  // line per response.  Recognises three methods: describe, compile, shutdown.
  // Implemented as a bash + jq pipeline so the test has zero runtime deps
  // beyond what the JVM environment usually carries.  If jq is missing,
  // tests are tagged `cancel`d at the cost of a noisy stderr line.
  private val mockScript: String =
    """
      |while IFS= read -r line; do
      |  method=$(printf '%s' "$line" | jq -r '.method')
      |  id=$(printf '%s' "$line" | jq -r '.id')
      |  case "$method" in
      |    describe)
      |      jq -nc --argjson id "$id" '{id:$id, result:{id:"mock",displayName:"Mock Plugin",spiVersion:"0.1.0",role:"backend",acceptedSources:[],features:["MutableState","PatternMatching"],outputs:["JavaScriptSource"]}}'
      |      ;;
      |    compile)
      |      jq -nc --argjson id "$id" '{id:$id, result:{kind:"text",code:"console.log(42)",language:"javascript"}}'
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
