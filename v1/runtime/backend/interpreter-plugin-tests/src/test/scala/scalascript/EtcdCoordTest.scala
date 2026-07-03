package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.interpreter.actors.ActorsInterpreterPlugin
import scalascript.parser.Parser

/** `std/cluster/coord-etcd.ssc` smoke test: imports `Etcd`, calls
 *  `Etcd.use` against a bogus address (etcd isn't running in CI),
 *  verifies the runtime still flips the protocol to "coord" without
 *  throwing — `safePost` swallows network errors and the lease-grant
 *  returns "", so the tick thread loops failing to acquire. */
class EtcdCoordTest extends AnyFunSuite with Matchers:

  test("Etcd.use survives no-etcd-running"):
    val repoRoot = TestPaths.repoRoot
    val src = """# T

[Etcd](std/cluster/coord-etcd.ssc)

```scalascript
runActors {
  Etcd.use("http://localhost:65535", "scalascript/test-leader")
  println("proto=" + leaderProtocol())
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
