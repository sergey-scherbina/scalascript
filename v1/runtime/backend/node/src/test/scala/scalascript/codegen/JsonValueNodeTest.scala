package scalascript.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize

/** std.json — `jsonValue` navigable decode must behave identically on the JS/Node
 *  backend as on the JVM interpreter (busi UI proposals P1).  Runs a decode
 *  snippet through real Node and pins stdout: total accessors, exact `asDecimal`
 *  for money, missing-key totality, and array navigation. */
class JsonValueNodeTest extends AnyFunSuite:

  private val backend = NodeBackend()

  private def hasNode: Boolean =
    try ProcessBuilder("node", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private def workDir(label: String): Path =
    val p = Path.of(sys.props.getOrElse("user.dir", ".")).resolve(s"target/node-json-test/$label")
    Files.createDirectories(p)
    p

  // Resolve `[jsonValue](std/json.ssc)` against the project tree (walk up from
  // the test CWD to the repo root's runtime/std), exactly like emit-spa / the CLI.
  private val repoBase = Path.of(sys.props.getOrElse("user.dir", "."))

  private def runNode(src: String): String =
    val ir = Normalize(Parser.parse(src))
    val code = backend.compile(ir, BackendOptions(baseDir = Some(repoBase))) match
      case CompileResult.TextOutput(c, "javascript", _) => c
      case other => fail(s"expected javascript TextOutput, got: $other")
    val dir = workDir("decode")
    Files.writeString(dir.resolve("main.cjs"), code, StandardCharsets.UTF_8)
    val run = ProcessBuilder("node", "main.cjs")
      .directory(dir.toFile).redirectErrorStream(true).start()
    val out = new String(run.getInputStream.readAllBytes(), "UTF-8")
    val ec  = run.waitFor()
    if ec != 0 then fail(s"node failed (exit $ec):\n$out")
    out.stripTrailing

  test("jsonValue decode on Node: totality, money, array nav"):
    assume(hasNode, "node not available")
    val src =
      """|# Decode
         |
         |[jsonValue](std/json.ssc)
         |
         |```scalascript
         |val j = jsonValue("{\"name\":\"Ada\",\"n\":3,\"amt\":\"1000.01\",\"xs\":[{\"k\":\"a\"},{\"k\":\"b\"}]}")
         |println(j.get("name").asString)
         |println(j.get("n").asInt)
         |println(j.get("amt").asDecimal)
         |println("[" + j.get("missing").asString + "]")
         |println(j.get("missing").isNull)
         |println(j.get("xs").asList.map(e => e.get("k").asString).mkString(","))
         |```
         |""".stripMargin
    val out = runNode(src)
    assert(out == "Ada\n3\n1000.01\n[]\ntrue\na,b",
      s"jsonValue decode mismatch on Node:\n$out")
