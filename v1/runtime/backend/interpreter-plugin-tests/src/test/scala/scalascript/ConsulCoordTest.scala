package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.interpreter.actors.ActorsInterpreterPlugin
import scalascript.parser.Parser

/** `std/cluster/coord-consul.ssc` smoke test: imports `Consul`, calls
 *  `Consul.use` against a bogus address (Consul isn't running in CI),
 *  and verifies the runtime still flips the protocol to "coord".
 *  Network errors are swallowed by the adapter's `safePut`/`safeGet`
 *  wrappers, so the call must not throw.  Real-Consul end-to-end
 *  testing lives in the integration suite (manual, opt-in). */
class ConsulCoordTest extends AnyFunSuite with Matchers:

  test("useConsulCoordinator survives no-Consul-running"):
    val repoRoot = TestPaths.repoRoot
    val src = """# T

[Consul](std/cluster/coord-consul.ssc)

```scalascript
runActors {
  Consul.use("http://localhost:65535", "scalascript/test-leader")
  println("proto=" + leaderProtocol())
  // Lock acquire fails (no Consul) — leader stays empty.
  println("leader='" + currentLeader() + "'")
  println("ok")
}
```"""
    val buf = java.io.ByteArrayOutputStream()
    val _i = Interpreter(java.io.PrintStream(buf), baseDir = Some(repoRoot)); _i.installPlugins(List(new ActorsInterpreterPlugin)); _i.run(Parser.parse(src))
    val out = buf.toString.linesIterator.toList
    info(out.mkString("\n"))
    out should contain ("proto=coord")
    out should contain ("leader=''")
    out should contain ("ok")
