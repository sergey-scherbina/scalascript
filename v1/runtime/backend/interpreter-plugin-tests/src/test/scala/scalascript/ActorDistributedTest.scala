package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.interpreter.actors.ActorsInterpreterPlugin
import scalascript.parser.Parser

/** Unit tests for v1.6 Phase 3 — distributed actor node primitives.
 *  This test suite covers single-process distributed semantics (register /
 *  whereis / startNode local routing) without actual WS connections. */
class ActorDistributedTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    val _i = Interpreter(ps); _i.installPlugins(List(new ActorsInterpreterPlugin)); _i.run(module)
    ps.flush()
    buf.toString.trim

  test("register + whereis: known name returns Some"):
    captured("""
      runActors {
        val w = spawn { () => receive { case _ => () } }
        register("srv", w)
        whereis("srv") match
          case Some(_) => println("found")
          case None    => println("missing")
        w ! "done"
      }
    """) shouldBe "found"

  test("whereis unknown name returns None"):
    captured("""
      runActors {
        whereis("nope") match
          case Some(_) => println("found")
          case None    => println("none")
      }
    """) shouldBe "none"

  test("startNode sets node identity — local send still works"):
    captured("""
      runActors {
        startNode("node1@localhost:9100")
        val a = spawn { () =>
          receive {
            case "ping" => println("pong")
          }
        }
        a ! "ping"
      }
    """) shouldBe "pong"

  test("Pid carries nodeId and localId fields"):
    captured("""
      runActors {
        startNode("test@host:1")
        val me = spawn { () =>
          val p = self()
          p match
            case Pid(nId, lId) => println(nId)
          ()
        }
        me ! ()
      }
    """) shouldBe "test@host:1"

  test("typed ActorRef helpers expose address/local metadata and publish"):
    captured("""
      runActors {
        startNode("typed-node")
        val worker = spawn { () =>
          receive {
            case "ping" => println("pong")
          }
        }
        val ref: ActorRef[String] = actorRef(worker)
        println(ref.address == Some("typed-node"))
        println(ref.isLocal)
        ref.tryLocal match
          case Some(_) => println("local")
          case None    => println("remote")
        val published = ref.publishAs("typed-worker")
        globalWhereis("typed-worker") match
          case Some(_) => println("published")
          case None    => println("missing")
        published.tell("ping")
      }
    """) shouldBe "true\ntrue\nlocal\npublished\npong"

  test("spawnRemote uses local BehaviorRegistry path when nodeId is local"):
    captured("""
      runActors {
        startNode("local-spawn")
        registerBehavior("echo", (arg: Any) =>
          receive {
            case "ping" => println("spawnRemote local")
          }
        )
        val ref = spawnRemote[String]("local-spawn", "echo", ())
        ref.tell("ping")
      }
    """) shouldBe "spawnRemote local"

  test("cluster capability exposes seed resolver and code identity"):
    captured("""
      runActors {
        startNode("cap-node")
        setClusterAuthToken("secret")
        val seeds = SeedResolver.staticList(List("ws://seed:9100/_ssc-actors"))
        val cluster = clusterOf(seeds)
        println(cluster.localNodeId)
        println(cluster.authToken == Some("secret"))
        println(cluster.resolveSeeds().head)
        val id = codeIdentity()
        println(id.algorithm)
        println(id.digest.length)
        assertCodeIdentity(id)
      }
    """) shouldBe "cap-node\ntrue\nws://seed:9100/_ssc-actors\nsha256\n64"

  test("unsupported seed resolver fails clearly"):
    val err = intercept[scalascript.interpreter.InterpretError] {
      captured("""
        runActors {
          resolveSeeds(SeedResolver.consulCatalog("demo"))
        }
      """)
    }
    err.getMessage should include ("consulCatalog resolver is declared but not implemented")

  test("dns seed resolver maps host addresses to actor urls"):
    val out = captured("""
      runActors {
        val seeds = resolveSeeds(SeedResolver.dnsSrv("localhost", 9100))
        println(seeds.nonEmpty)
        println(seeds.head.startsWith("ws://"))
        println(seeds.head.endsWith(":9100/_ssc-actors"))
      }
    """)
    out shouldBe "true\ntrue\ntrue"

  test("ValueSerializer round-trips IntV"):
    import scalascript.interpreter.{Value, ValueSerializer}
    val v = Value.IntV(42L)
    val json = ValueSerializer.serialize(v)
    val back = ValueSerializer.deserialize(json)
    back shouldBe v

  test("ValueSerializer round-trips StringV"):
    import scalascript.interpreter.{Value, ValueSerializer}
    val v = Value.StringV("hello \"world\"")
    val json = ValueSerializer.serialize(v)
    val back = ValueSerializer.deserialize(json)
    back shouldBe v

  test("ValueSerializer round-trips Pid"):
    import scalascript.interpreter.{Value, ValueSerializer}
    val v = Value.InstanceV("Pid", Map("nodeId" -> Value.StringV("n@h:1"), "localId" -> Value.IntV(5L)))
    val json = ValueSerializer.serialize(v)
    val back = ValueSerializer.deserialize(json)
    back shouldBe v

  test("ValueSerializer round-trips nested InstanceV"):
    import scalascript.interpreter.{Value, ValueSerializer}
    val v = Value.InstanceV("Down", Map(
      "ref"    -> Value.IntV(1L),
      "from"   -> Value.InstanceV("Pid", Map("nodeId" -> Value.EmptyStr, "localId" -> Value.IntV(3L))),
      "reason" -> Value.StringV("oops")
    ))
    val json = ValueSerializer.serialize(v)
    val back = ValueSerializer.deserialize(json)
    back shouldBe v
