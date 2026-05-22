package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** In-process smoke test for `std/cluster/singleton.ssc`.  A single
 *  node is its own leader (Bully self-claim when no higher peer is
 *  visible), so `Singleton.use` spawns the factory locally on the
 *  bootstrap path; `Singleton.send` then routes a message into it
 *  via globalWhereis. */
class SingletonModuleTest extends AnyFunSuite with Matchers:

  test("Singleton.use spawns factory on self-elected leader; send delivers"):
    scalascript.server.Routes.clear()
    val repoRoot = TestPaths.repoRoot
    val src = """# T

[Singleton](std/cluster/singleton.ssc)

```scalascript
runActors {
  startNode("solo-node", "ws://127.0.0.1:0/_ssc-actors")
  // Self-elect — no peers, higher set is empty so claim-self fires.
  electLeader()
  // Counter cell that prints on every "tick".
  Singleton.use("global-counter", { () =>
    spawn { () =>
      var n: Int = 0
      while true do
        receive {
          case "tick" =>
            n = n + 1
            println("COUNTER:" + n)
            if n == 3 then stop()
        }
    }
  })
  // Wait for the supervisor to register the name (~tens of ms) by
  // polling globalWhereis with sendAfter retries.
  spawn { () =>
    val s = self()
    sendAfter(200, s, "send1")
    receive { case "send1" =>
      val ok1 = Singleton.send("global-counter", "tick")
      println("SEND1:" + ok1)
      sendAfter(150, s, "send2")
      receive { case "send2" =>
        val ok2 = Singleton.send("global-counter", "tick")
        println("SEND2:" + ok2)
        sendAfter(150, s, "send3")
        receive { case "send3" =>
          val ok3 = Singleton.send("global-counter", "tick")
          println("SEND3:" + ok3)
          stop()
        }
      }
    }
  }
}
```"""
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf), baseDir = Some(repoRoot)).run(Parser.parse(src))
    val out = buf.toString.linesIterator.toList
    info(out.mkString("\n"))
    // All three sends should have a globalWhereis target by the time
    // they fire (supervisor's bootstrap path runs synchronously inside
    // Singleton.use → register-before-return).
    out should contain ("SEND1:true")
    out should contain ("SEND2:true")
    out should contain ("SEND3:true")
    // Counter should have ticked 3 times.
    out should contain ("COUNTER:1")
    out should contain ("COUNTER:2")
    out should contain ("COUNTER:3")
