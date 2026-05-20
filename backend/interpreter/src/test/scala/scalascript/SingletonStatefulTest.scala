package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** In-process smoke test for `Singleton.useStateful`.  Single node ⇒
 *  exercises the bootstrap + checkpoint round-trip without a full
 *  cluster: the actor saves state, the test inspects it via
 *  `clusterConfigGet`. */
class SingletonStatefulTest extends AnyFunSuite with Matchers:

  test("Singleton.useStateful bootstraps from initialState; saveState persists") {
    scalascript.server.Routes.clear()
    scalascript.server.WsRoutes.clear()
    val repoRoot = os.pwd / os.up
    val src = """# T

[Singleton](std/cluster/singleton.ssc)

```scalascript
runActors {
  startNode("solo-stateful", "ws://127.0.0.1:0/_ssc-actors")
  electLeader()
  Singleton.useStateful("ctr", "10", { (state: String) =>
    spawn { () =>
      println("RESUMED:" + state)
      Singleton.saveState("ctr", "42")
      stop()
    }
  })
  spawn { () =>
    val s = self()
    sendAfter(300, s, "check")
    receive { case "check" =>
      println("STATE:" + Singleton.peekState("ctr").getOrElse("<none>"))
      stop()
    }
  }
}
```"""
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf), baseDir = Some(repoRoot)).run(Parser.parse(src))
    val out = buf.toString.linesIterator.toList
    info(out.mkString("\n"))
    // First call: no prior state in clusterConfig ⇒ resume from "10".
    out should contain ("RESUMED:10")
    // After saveState, peekState returns the latest value.
    out should contain ("STATE:42")
  }
