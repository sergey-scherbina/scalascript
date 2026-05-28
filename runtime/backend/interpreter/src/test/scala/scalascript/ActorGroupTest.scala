package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Tests for ActorGroup (router/sharded/role) and proxyActor (v1.63.6). */
class ActorGroupTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# Test\n\n```scala\n$code\n```\n"))
    ps.flush()
    buf.toString.trim

  test("ActorGroup.router is accessible and has kind=router"):
    val result = captured(
      """
      runActors {
        val g = ActorGroup.router[String]("workers", RoutingPolicy.RoundRobin)
        println(g)
      }
      """
    )
    result should include ("ActorGroup")

  test("RoutingPolicy companion constants are accessible"):
    val result = captured(
      """
      runActors {
        println(RoutingPolicy.RoundRobin)
        println(RoutingPolicy.Broadcast)
      }
      """
    )
    result should include ("RoutingPolicy.RoundRobin")
    result should include ("RoutingPolicy.Broadcast")

  test("actorGroupAdd and actorGroupMembers track members"):
    val result = captured(
      """
      runActors {
        val g = ActorGroup.router[String]("test.members", RoutingPolicy.RoundRobin)
        val p1 = spawn { () => receive { case _ => () } }
        val p2 = spawn { () => receive { case _ => () } }
        actorGroupAdd[String](g, p1)
        actorGroupAdd[String](g, p2)
        val members = actorGroupMembers[String](g)
        println(members.length)
      }
      """
    )
    result shouldBe "2"

  test("actorGroupTell routes messages to members"):
    val result = captured(
      """
      runActors {
        val received = spawn { () =>
          val msg = receive { case m => m }
          println("got:" + msg)
        }
        val g = ActorGroup.router[String]("test.route", RoutingPolicy.RoundRobin)
        actorGroupAdd[String](g, received)
        actorGroupTell[String](g, "hello")
      }
      """
    )
    result shouldBe "got:hello"

  test("proxyActor creates a local actor that forwards to target"):
    val result = captured(
      """
      runActors {
        val target = spawn { () =>
          val msg = receive { case m => m }
          println("target:" + msg)
        }
        val proxy = proxyActor[String](target)
        proxy ! "forwarded"
      }
      """
    )
    result shouldBe "target:forwarded"
