package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** In-process smoke tests for cluster-wide pub/sub.
 *
 *  Currently only the `unsubscribe` path is covered here.  The
 *  self-publish-then-receive shape — which would exercise the full
 *  publishQueue drain — wedges ScalaTest's fork runner in a way I
 *  couldn't pin down (the test silently disappears from the report
 *  even though `fail()` in its body shows up correctly), so the
 *  positive-delivery path is covered indirectly via the multi-node
 *  integration tests (see e.g. SingletonFailoverTest patterns). */
class PubSubTest extends AnyFunSuite with Matchers:

  private def runScript(src: String): List[String] = {
    scalascript.server.Routes.clear()
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf)).run(Parser.parse(src))
    buf.toString.linesIterator.toList
  }

  test("unsubscribePublish removes the actor from delivery") {
    val out = runScript("""# T
```scalascript
runActors {
  startNode("pub-unsub", "ws://127.0.0.1:0/_ssc-actors")
  spawn { () =>
    val s = self()
    subscribePublish("t1")
    unsubscribePublish("t1")
    publish("t1", "x")
    sendAfter(150, s, "timeout")
    receive {
      case "timeout" => println("NO-MSG"); stop()
      case other     => println("UNEXPECTED:" + other); stop()
    }
  }
}
```""")
    info(out.mkString("\n"))
    out should contain ("NO-MSG")
    out.exists(_.startsWith("UNEXPECTED:")) shouldBe false
  }
