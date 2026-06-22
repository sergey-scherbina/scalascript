package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.JavaLibPackager

/** Golden + compile/run test for the Java optics library packager (Task B, `specs/polyglot-libraries.md`
 *  §4 — publish optics as a standalone Java/Maven library). The public signature is asserted; an
 *  `assume(javac)` smoke compiles the emitted `Optics.java` together with a driver exercising all four
 *  optics and runs it — proving the emitted Java compiles AND behaves. */
class JavaLibPackagerTest extends AnyFunSuite with Matchers:

  test("optics Java package has pom.xml + Optics.java + README"):
    JavaLibPackager.opticsJavaPackage("1.2.3").keySet shouldBe
      Set("pom.xml", "src/main/java/ssc/optics/Optics.java", "README.md")

  test("pom.xml carries the version + a stable artifact name"):
    val pom = JavaLibPackager.opticsPomXml("4.5.6")
    pom should include ("<version>4.5.6</version>")
    pom should include ("<artifactId>ssc-optics</artifactId>")

  test("Optics.java exposes the four optic shapes + factories (public API signature)"):
    val src = JavaLibPackager.opticsJavaSource
    src should include ("package ssc.optics")
    src should include ("public static final class Lens")
    src should include ("public static final class Optional_")
    src should include ("public static final class Traversal")
    src should include ("public static final class Prism")
    src should include ("public static Lens makeLens(")
    src should include ("public static Optional_ makeOptional(")
    src should include ("public static Traversal makeTraversal(")
    src should include ("public static Prism makePrism(")
    for m <- List("field", "index", "at", "some", "each") do src should include (m)

  test("emit-lib --host java writes the nested Maven project layout"):
    val dir = os.temp.dir(prefix = "ssc-emit-lib-java")
    try
      new EmitLibCmd().run(List("--host", "java", "--feature", "optics", "-o", dir.toString))
      os.exists(dir / "pom.xml") shouldBe true
      os.exists(dir / "src" / "main" / "java" / "ssc" / "optics" / "Optics.java") shouldBe true
    finally os.remove.all(dir)

  private def javacAvailable: Boolean =
    try os.proc("javac", "-version").call(check = false).exitCode == 0
    catch case _: Throwable => false

  test("the emitted Optics.java compiles and all four optics behave (assume javac)"):
    assume(javacAvailable, "javac not on PATH — skipping the Java optics compile/run smoke")
    val dir = os.temp.dir(prefix = "ssc-optics-java-")
    val srcDir = dir / "ssc" / "optics"
    os.makeDir.all(srcDir)
    os.write.over(srcDir / "Optics.java", JavaLibPackager.opticsJavaSource)
    os.write.over(dir / "Main.java",
      """import static ssc.optics.Optics.*;
        |import ssc.optics.Optics;
        |import java.util.*;
        |
        |public class Main {
        |    static Map<String,Object> obj(Object... kv) {
        |        var m = new LinkedHashMap<String,Object>();
        |        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        |        return m;
        |    }
        |    public static void main(String[] a) {
        |        Object s = obj("a", obj("b", 5));
        |        var l = makeLens(List.of("a", "b"));
        |        System.out.println(l.get(s));
        |        System.out.println(l.get(l.set(s, 9)));
        |        Object s2 = obj("a", List.of(10, 20));
        |        System.out.println(makeOptional(List.of(field("a"), index(0))).getOption(s2).orElse("?"));
        |        System.out.println(makeOptional(List.of(field("a"), index(9))).getOption(s2).isPresent());
        |        Object s3 = List.of(obj("n", 1), obj("n", 2));
        |        var t = makeTraversal(List.of(each(), field("n")));
        |        System.out.println(t.getAll(s3));
        |        var p = makePrism("Some");
        |        System.out.println(p.getOption(obj("_type", "Some", "value", 7)).isPresent());
        |        System.out.println(p.getOption(obj("_type", "None")).isPresent());
        |    }
        |}
        |""".stripMargin)
    val out = dir / "out"
    os.makeDir.all(out)
    val jc = os.proc("javac", "-d", out.toString,
      (srcDir / "Optics.java").toString, (dir / "Main.java").toString).call(cwd = dir, check = false)
    assert(jc.exitCode == 0, s"javac failed:\n${jc.err.text()}\n${jc.out.text()}")
    val run = os.proc("java", "-cp", out.toString, "Main").call(cwd = dir, check = false)
    assert(run.exitCode == 0, s"java run failed:\n${run.err.text()}")
    run.out.text().trim.linesIterator.toList shouldBe
      List("5", "9", "10", "false", "[1, 2]", "true", "false")
    os.remove.all(dir)
