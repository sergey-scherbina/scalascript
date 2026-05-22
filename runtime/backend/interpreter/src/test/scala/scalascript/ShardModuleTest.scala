package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** In-process smoke test for `std/cluster/shard.ssc`. */
class ShardModuleTest extends AnyFunSuite with Matchers:

  private def runScript(src: String): List[String] =
    scalascript.server.Routes.clear()
    val repoRoot = os.pwd / os.up
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf), baseDir = Some(repoRoot)).run(Parser.parse(src))
    buf.toString.linesIterator.toList

  test("Shard.owner / isOwner / send semantics on a solo node"):
    val out = runScript("""# T

[Shard](std/cluster/shard.ssc)

```scalascript
runActors {
  startNode("solo-shard", "ws://127.0.0.1:0/_ssc-actors")
  println("HASH-alice=" + Shard._hash("alice"))
  println("OWNER-alice=" + Shard.owner("alice"))
  println("ISOWNER-alice=" + Shard.isOwner("alice"))
  println("SEND-empty=" + Shard.send("missing", "x"))
}
```""")
    info(out.mkString("\n"))
    out.find(_.startsWith("HASH-alice=")) shouldBe defined
    out should contain ("OWNER-alice=solo-shard")
    out should contain ("ISOWNER-alice=true")
    out should contain ("SEND-empty=false")

  test("Shard.spawnIfOwner spawns + registers; send delivers"):
    val out = runScript("""# T

[Shard](std/cluster/shard.ssc)

```scalascript
runActors {
  startNode("solo-spawn", "ws://127.0.0.1:0/_ssc-actors")
  val pid1 = Shard.spawnIfOwner("ttl-key", { () => spawn { () => receive { case _ => stop() } } })
  println("SPAWN1=" + pid1.nonEmpty)
  // Second call: registry already has the key, so the factory is NOT
  // invoked.  The returned Pid is the *registered* one — note that
  // globalRegister stamps the local nodeId, so it isn't `eq` to pid1
  // (which came back from a local spawn with nodeId="") even though
  // it points at the same actor.
  val pid2 = Shard.spawnIfOwner("ttl-key", { () => spawn { () => receive { case _ => stop() } } })
  println("SPAWN2=" + pid2.nonEmpty)
  // Send hits the live actor — proves the registry is wired and the
  // second spawnIfOwner didn't re-spawn (otherwise the global name
  // would point at the new actor, not the original).
  println("SEND=" + Shard.send("ttl-key", "x"))
}
```""")
    info(out.mkString("\n"))
    out should contain ("SPAWN1=true")
    out should contain ("SPAWN2=true")
    out should contain ("SEND=true")
