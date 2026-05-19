package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Smoke test for `conformance/actors-cluster-raft.ssc` on the
 *  interpreter backend.  The conformance .ssc file itself is also
 *  consumed by the JS / JVM backends via the e2e cross-backend
 *  shell-script harness; this test pins the INT path. */
class RaftConformanceTest extends AnyFunSuite with Matchers:

  test("Raft single-node election claims self"):
    val src = os.read(os.pwd / os.up / "conformance" / "actors-cluster-raft.ssc")
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf)).run(Parser.parse(src))
    val out = buf.toString.linesIterator.toList
    info(out.mkString("\n"))
    out should contain ("proto=raft")
    out should contain ("histSize=1")
    out should contain ("leader=''")
    out should contain ("ok")
