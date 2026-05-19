package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Quorum-aware Bully: a single node with quorum=3 should refuse to
 *  self-claim leadership (no peers ⇒ visible = 1 < 3).  Same node
 *  with quorum=1 should claim self normally. */
class QuorumBullyTest extends AnyFunSuite with Matchers:

  test("setQuorumSize(3) blocks self-claim when only one node is visible"):
    scalascript.server.Routes.clear()
    scalascript.server.WsRoutes.clear()
    val src = """# T
```scalascript
runActors {
  startNode("solo", "ws://127.0.0.1:0/_ssc-actors")
  setQuorumSize(3)
  electLeader()
  println("LEADER:" + currentLeader())
}
```"""
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf)).run(Parser.parse(src))
    val out = buf.toString.linesIterator.toList
    info(out.mkString("\n"))
    // Quorum=3, visible=1 ⇒ self-claim declined, currentLeader stays "".
    out should contain ("LEADER:")

  test("setQuorumSize(1) lets a single node claim self"):
    scalascript.server.Routes.clear()
    scalascript.server.WsRoutes.clear()
    val src = """# T
```scalascript
runActors {
  startNode("solo", "ws://127.0.0.1:0/_ssc-actors")
  setQuorumSize(1)
  electLeader()
  println("LEADER:" + currentLeader())
}
```"""
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf)).run(Parser.parse(src))
    val out = buf.toString.linesIterator.toList
    info(out.mkString("\n"))
    out should contain ("LEADER:solo")

  test("setQuorumSize(0) is the legacy no-quorum behaviour"):
    scalascript.server.Routes.clear()
    scalascript.server.WsRoutes.clear()
    val src = """# T
```scalascript
runActors {
  startNode("solo", "ws://127.0.0.1:0/_ssc-actors")
  // 0 = default; the check is bypassed.
  setQuorumSize(0)
  electLeader()
  println("LEADER:" + currentLeader())
}
```"""
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf)).run(Parser.parse(src))
    val out = buf.toString.linesIterator.toList
    info(out.mkString("\n"))
    out should contain ("LEADER:solo")
