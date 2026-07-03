package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.interpreter.actors.ActorsInterpreterPlugin
import scalascript.parser.Parser

/** Raft persistence: single-node election writes `(currentTerm,
 *  votedFor)` to `.ssc-raft-state-default.json` in the JVM cwd.
 *  Spec §4.1 — "Persisted to disk so a restart cannot double-vote".
 *  This test pins the write side (file exists, term recorded). */
class RaftPersistenceTest extends AnyFunSuite with Matchers:

  test("Raft single-node election writes term + votedFor to disk"):
    // Path the runtime will write — `.ssc-raft-state-default.json`
    // when `_localNodeId.isEmpty` (no startNode call).
    val path = os.pwd / ".ssc-raft-state-default.json"
    // Clean any leftover from a previous run.
    if os.exists(path) then os.remove(path)

    val src = """# T

```scalascript
runActors {
  useRaftLeaderElection()
  electLeader()
  println("ok")
}
```"""
    val buf = java.io.ByteArrayOutputStream()
    try
      val _i = Interpreter(java.io.PrintStream(buf)); _i.installPlugins(List(new ActorsInterpreterPlugin)); _i.run(Parser.parse(src))
      buf.toString should include ("ok")

      assert(os.exists(path), s"expected $path to exist after Raft election")
      val contents = os.read(path)
      contents should include ("\"currentTerm\":")
      contents should include ("\"votedFor\":")
      // Term should be at least 1 (we ran one election round).
      val termIdx = contents.indexOf("\"currentTerm\":")
      val termVal = contents.substring(termIdx + 14).takeWhile(c => c.isDigit).toInt
      assert(termVal >= 1, s"expected currentTerm >= 1, got $termVal")
    finally
      if os.exists(path) then os.remove(path)
