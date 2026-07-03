package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.JvmLibPackager

/** Golden + compile test for the JVM optics library packager (Task B, `specs/polyglot-libraries.md`
 *  §4 — publish optics as a buildable Scala `sbt` library). The public signature is asserted; an
 *  `assume(scala-cli)` check compiles the emitted `Optics.scala` to prove it is valid Scala 3. */
class JvmLibPackagerTest extends AnyFunSuite with Matchers:

  test("optics Scala package has build.sbt + Optics.scala + README"):
    JvmLibPackager.opticsScalaPackage("1.2.3").keySet shouldBe
      Set("build.sbt", "src/main/scala/ssc/optics/Optics.scala", "README.md")

  test("build.sbt carries the version + a stable artifact name"):
    val sbt = JvmLibPackager.opticsBuildSbt("4.5.6")
    sbt should include ("""version      := "4.5.6"""")
    sbt should include ("""name         := "ssc-optics"""")

  test("Optics.scala exposes the four optic shapes + factories (public API signature)"):
    val src = JvmLibPackager.opticsScalaSource
    src should include ("package ssc.optics")
    src should include ("final case class Lens(path: List[String]):")
    src should include ("final case class Optional(steps: List[Step]):")
    src should include ("final case class Traversal(steps: List[Step]):")
    src should include ("final case class Prism(variant: String):")
    src should include ("def makeLens(path: List[String]): Lens")
    src should include ("def makeOptional(steps: List[Step]): Optional")
    src should include ("def makeTraversal(steps: List[Step]): Traversal")
    src should include ("def makePrism(variant: String): Prism")
    for m <- List("field", "index", "at", "some", "each") do src should include (m)

  test("emit-lib --host jvm writes the nested Scala project layout"):
    val dir = os.temp.dir(prefix = "ssc-emit-lib-jvm")
    try
      new EmitLibCmd().run(List("--host", "jvm", "--feature", "optics", "-o", dir.toString))
      os.exists(dir / "build.sbt") shouldBe true
      os.exists(dir / "src" / "main" / "scala" / "ssc" / "optics" / "Optics.scala") shouldBe true
    finally os.remove.all(dir)

  test("the emitted Optics.scala compiles as valid Scala 3 (assume scala-cli)"):
    assume(scalaCliAvailable, "scala-cli not on PATH — skipping compile check")
    val f = os.temp(suffix = ".scala")
    try
      os.write.over(f, JvmLibPackager.opticsScalaSource)
      val code = scala.sys.process.Process(Seq("scala-cli", "compile", f.toString))
        .!(scala.sys.process.ProcessLogger(_ => (), _ => ()))
      code shouldBe 0
    finally os.remove(f)

  private def scalaCliAvailable: Boolean =
    try scala.sys.process.Process(Seq("scala-cli", "version"))
          .!(scala.sys.process.ProcessLogger(_ => (), _ => ())) == 0
    catch case _: Throwable => false
