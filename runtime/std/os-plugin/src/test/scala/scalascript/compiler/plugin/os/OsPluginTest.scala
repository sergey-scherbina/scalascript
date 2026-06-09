package scalascript.compiler.plugin.os

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** std-fs-os Phase 2 — JVM OsPlugin intrinsics. */
class OsPluginTest extends AnyFunSuite:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val jvmInterp = Interpreter(out = ps)
    jvmInterp.installPlugins(List(OsInterpreterPlugin()))
    jvmInterp.run(Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n"))
    ps.flush()
    buf.toString.trim

  private def check(actual: String, expected: String): Unit =
    assert(actual == expected, s"\nActual: '$actual'\nExpected: '$expected'")

  test("env returns None for a missing key"):
    check(run("""println(env("DEFINITELY_NOT_SET_XYZ123").isDefined)"""), "false")

  test("envOrElse returns default for missing key"):
    check(run("""println(envOrElse("DEFINITELY_NOT_SET_XYZ123", "fallback"))"""), "fallback")

  test("cwd returns a non-empty string"):
    check(run("""println(cwd().length > 0)"""), "true")

  test("sep is / or \\ depending on OS"):
    val result = run("""println(sep())""")
    assert(result == "/" || result == "\\", s"sep must be / or \\, got: $result")

  test("pathJoin joins path components"):
    check(run("""println(pathJoin("/tmp", "sub", "file.txt"))"""), "/tmp/sub/file.txt")

  test("pathDirname returns parent directory"):
    check(run("""println(pathDirname("/tmp/sub/file.txt"))"""), "/tmp/sub")

  test("pathBasename returns filename"):
    check(run("""println(pathBasename("/tmp/sub/file.txt"))"""), "file.txt")

  test("pathExtname returns extension"):
    check(run("""println(pathExtname("/tmp/sub/file.txt"))"""), ".txt")

  test("pathExtname returns empty for no extension"):
    check(run("""println(pathExtname("/tmp/Makefile"))"""), "")

  test("pathIsAbsolute returns true for absolute path"):
    check(run("""println(pathIsAbsolute("/tmp/foo"))"""), "true")

  test("pathIsAbsolute returns false for relative path"):
    check(run("""println(pathIsAbsolute("relative/path"))"""), "false")

  test("tempDir returns a non-empty string"):
    check(run("""println(tempDir().length > 0)"""), "true")

  test("hostname returns a non-empty string"):
    check(run("""println(hostname().length > 0)"""), "true")

  test("homedir returns a non-empty string"):
    check(run("""println(homedir().length > 0)"""), "true")
