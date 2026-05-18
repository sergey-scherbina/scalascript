package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Unit tests for v1.6.x scheduled sends — sendAfter / sendInterval / cancelTimer. */
class ScheduledSendsTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    Interpreter(ps).run(module)
    ps.flush()
    buf.toString.trim

  test("sendAfter delivers message to actor after delay"):
    captured("""
      runActors {
        val me = spawn { () =>
          val pid = self()
          sendAfter(10, pid, "hello")
          receive {
            case msg => println("got: " + msg)
          }
        }
      }
    """) shouldBe "got: hello"

  test("sendAfter returns a cancellable TimerRef"):
    captured("""
      runActors {
        val me = spawn { () =>
          val pid = self()
          val ref = sendAfter(10, pid, "should arrive")
          receive {
            case msg => println("got: " + msg)
          }
        }
      }
    """) shouldBe "got: should arrive"

  test("cancelTimer prevents the message from arriving"):
    captured("""
      runActors {
        val actor = spawn { () =>
          val pid = self()
          val ref = sendAfter(5, pid, "cancelled msg")
          cancelTimer(ref)
          val fallback = sendAfter(15, pid, "fallback")
          receive {
            case msg => println("got: " + msg)
          }
        }
      }
    """) shouldBe "got: fallback"

  test("sendInterval delivers message repeatedly until cancelled"):
    captured("""
      runActors {
        val _ = spawn { () =>
          val pid = self()
          val ref = sendInterval(5, pid, "tick")
          receive { case _ => () }
          receive { case _ => () }
          receive { case _ => () }
          cancelTimer(ref)
          println("three ticks")
        }
      }
    """) shouldBe "three ticks"

  test("multiple sendAfter timers fire in order"):
    captured("""
      runActors {
        val _ = spawn { () =>
          val pid = self()
          sendAfter(20, pid, "b")
          sendAfter(5, pid, "a")
          receive { case msg => print(msg) }
          receive { case msg => println(msg) }
        }
      }
    """) shouldBe "ab"
