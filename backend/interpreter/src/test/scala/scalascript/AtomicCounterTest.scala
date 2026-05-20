package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** In-process smoke test for cluster-wide atomic counters.  Single
 *  node ⇒ verifies the LOCAL atomic semantics — get, set, add, CAS —
 *  before any LWW gossip is involved.  Cross-node convergence is
 *  tested implicitly via the multi-node suites' use of the same
 *  envelope shape as `clusterConfigSet`. */
class AtomicCounterTest extends AnyFunSuite with Matchers:

  private def runScript(src: String): List[String] = {
    scalascript.server.Routes.clear()
    scalascript.server.WsRoutes.clear()
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf)).run(Parser.parse(src))
    buf.toString.linesIterator.toList
  }

  test("atomic get/set/add/CAS on a solo node") {
    val out = runScript("""# T
```scalascript
runActors {
  startNode("atom-solo", "ws://127.0.0.1:0/_ssc-actors")
  // Initial read of a never-set counter ⇒ 0.
  println("INIT:" + clusterAtomicGet("hits"))
  // Set + read back.
  val prev = clusterAtomicSet("hits", 5L)
  println("SET-prev=" + prev + " get=" + clusterAtomicGet("hits"))
  // Atomic add.
  val newVal = clusterAtomicAdd("hits", 3L)
  println("ADD=" + newVal + " get=" + clusterAtomicGet("hits"))
  // CAS success path.
  val cas1 = clusterAtomicCompareAndSet("hits", 8L, 100L)
  println("CAS-ok=" + cas1 + " get=" + clusterAtomicGet("hits"))
  // CAS failure path — current value is 100, expect 99.
  val cas2 = clusterAtomicCompareAndSet("hits", 99L, 200L)
  println("CAS-fail=" + cas2 + " get=" + clusterAtomicGet("hits"))
}
```""")
    info(out.mkString("\n"))
    out should contain ("INIT:0")
    out should contain ("SET-prev=5 get=5")
    out should contain ("ADD=8 get=8")
    out should contain ("CAS-ok=true get=100")
    out should contain ("CAS-fail=false get=100")
  }

  test("multiple counters are independent") {
    val out = runScript("""# T
```scalascript
runActors {
  startNode("atom-multi", "ws://127.0.0.1:0/_ssc-actors")
  clusterAtomicSet("a", 1L)
  clusterAtomicSet("b", 2L)
  clusterAtomicAdd("a", 10L)
  clusterAtomicAdd("b", 20L)
  println("A=" + clusterAtomicGet("a"))
  println("B=" + clusterAtomicGet("b"))
}
```""")
    info(out.mkString("\n"))
    out should contain ("A=11")
    out should contain ("B=22")
  }
