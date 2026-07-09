package scalascript

import org.scalatest.funsuite.AnyFunSuite

import scalascript.codegen.JvmGen
import scalascript.parser.Parser

/** backend-specific-blocks Phase 3 — JvmGen emission for `scala` and `java`
 *  fenced blocks.  Text-shape assertions only; actual scala-cli compilation
 *  is an integration concern (see specs/backend-specific-blocks.md §6 Phase 3). */
class JvmGenBackendBlockTest extends AnyFunSuite:

  private def emit(ssc: String): String =
    JvmGen.generate(Parser.parse(ssc))

  // ── scala blocks — JVM-native Scala 3 implementation code ──────────────

  test("scala block: source included in generated code"):
    val code = emit(
      """|# Module
         |
         |```scalascript
         |extern def currentPid(): Int
         |```
         |
         |```scala
         |def currentPid(): Int = ProcessHandle.current().pid().toInt
         |```
         |""".stripMargin
    )
    assert(code.contains("currentPid"), "scala block def should appear in output")
    assert(code.contains("ProcessHandle"), "java stdlib call in scala block should be preserved")

  test("scala block: extern def declaration is included alongside implementation"):
    val code = emit(
      """|# Ops
         |
         |```scalascript
         |extern def hostname(): String
         |```
         |
         |```scala
         |def hostname(): String = java.net.InetAddress.getLocalHost.getHostName
         |```
         |""".stripMargin
    )
    assert(code.contains("hostname"), "hostname def must appear in output")
    assert(code.contains("InetAddress"), "java.net reference in scala block should pass through")

  test("module without backend blocks: no //> using sources emitted"):
    val code = emit(
      """|# Plain
         |
         |```scalascript
         |val x = 42
         |```
         |""".stripMargin
    )
    assert(!code.contains("""//> using sources"""),
      "module with no java blocks must not emit //> using sources")

  test("no-arg mkString rewrite emits Scala parameterless call"):
    val code = emit(
      """|# Plain
         |
         |```scalascript
         |println(List("a", "b").mkString())
         |```
         |""".stripMargin
    )
    assert(code.contains("""List("a", "b").map(_show).mkString)"""), code)
    assert(!code.contains(""".map(_show).mkString()"""), code)

  // ── java blocks — compiled as separate .java source files ──────────────

  test("java block: //> using sources directive is emitted"):
    val code = emit(
      """|# JvmModule
         |
         |```java
         |public class Pid {
         |  public static int get() { return (int) ProcessHandle.current().pid(); }
         |}
         |```
         |""".stripMargin
    )
    assert(code.contains("""//> using sources"""),
      "java block must produce //> using sources directive")
    assert(code.contains(".java"),
      "//> using sources must reference a .java file")

  test("java block: directive references deterministic filename"):
    val code = emit(
      """|# JvmModule
         |
         |```java
         |public class Foo { public static int x = 1; }
         |```
         |""".stripMargin
    )
    assert(code.contains("""_ssc_java_0.java"""),
      "first java block should be named _ssc_java_0.java")

  test("multiple java blocks: one directive per block with sequential names"):
    val code = emit(
      """|# JvmModule
         |
         |```java
         |public class A { }
         |```
         |
         |```java
         |public class B { }
         |```
         |""".stripMargin
    )
    assert(code.contains("""_ssc_java_0.java"""), "_ssc_java_0.java expected for first block")
    assert(code.contains("""_ssc_java_1.java"""), "_ssc_java_1.java expected for second block")

  test("java block: directive appears before user code"):
    val code = emit(
      """|# JvmModule
         |
         |```scalascript
         |extern def x(): Int
         |```
         |
         |```java
         |public class X { public static int x() { return 1; } }
         |```
         |""".stripMargin
    )
    val sourcesIdx = code.indexOf("""//> using sources""")
    val externIdx  = code.indexOf("extern")
    assert(sourcesIdx >= 0, "//> using sources must be present")
    // The directive is in the preamble (before user code), so it may appear
    // before or at the same region — at minimum, it must be present.
    assert(sourcesIdx < externIdx || externIdx < 0,
      "//> using sources should appear before user ScalaScript code")
