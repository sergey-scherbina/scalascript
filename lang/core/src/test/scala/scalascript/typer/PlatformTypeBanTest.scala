package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** backend-specific-blocks Phase 2 — platform-type ban in scalascript blocks.
 *
 *  `java.*`, `javax.*`, `sun.*`, `com.sun.*` imports in `scalascript` blocks
 *  are compile errors (E_PlatformType).  The ban does not apply to `scala`
 *  fenced blocks — those are JVM-native by design.
 *  See specs/backend-specific-blocks.md §5.1. */
class PlatformTypeBanTest extends AnyFunSuite:

  private def typeCheck(src: String) =
    Typer.typeCheck(Parser.parse(src))

  private def sscBlock(code: String): String =
    s"""|# Module
        |
        |```scalascript
        |$code
        |```
        |""".stripMargin

  private def scalaBlock(code: String): String =
    s"""|# Module
        |
        |```scala
        |$code
        |```
        |""".stripMargin

  // ── banned imports in scalascript blocks ────────────────────────────────

  test("import java.io.File in scalascript block is E_PlatformType"):
    val typed = typeCheck(sscBlock("import java.io.File"))
    assert(typed.hasErrors, "import java.io.File must produce an error")
    assert(typed.errors.exists(_.msg.contains("E_PlatformType")))
    assert(typed.errors.exists(_.msg.contains("java.io")))

  test("import javax.sql.DataSource in scalascript block is E_PlatformType"):
    val typed = typeCheck(sscBlock("import javax.sql.DataSource"))
    assert(typed.hasErrors)
    assert(typed.errors.exists(_.msg.contains("E_PlatformType")))

  test("import sun.misc.Unsafe in scalascript block is E_PlatformType"):
    val typed = typeCheck(sscBlock("import sun.misc.Unsafe"))
    assert(typed.hasErrors)
    assert(typed.errors.exists(_.msg.contains("E_PlatformType")))

  test("import com.sun.net.httpserver in scalascript block is E_PlatformType"):
    val typed = typeCheck(sscBlock("import com.sun.net.httpserver.HttpServer"))
    assert(typed.hasErrors)
    assert(typed.errors.exists(_.msg.contains("E_PlatformType")))

  test("wildcard import java.io._ in scalascript block is E_PlatformType"):
    val typed = typeCheck(sscBlock("import java.io.*"))
    assert(typed.hasErrors)
    assert(typed.errors.exists(_.msg.contains("E_PlatformType")))

  // ── allowed imports in scalascript blocks ───────────────────────────────

  test("import std.fs in scalascript block is allowed"):
    val typed = typeCheck(sscBlock("import std.fs.{readFile}"))
    assert(!typed.errors.exists(_.msg.contains("E_PlatformType")),
      s"std.fs import must not trigger ban; errors: ${typed.errors.map(_.show).mkString(", ")}")

  test("arbitrary user import in scalascript block is allowed"):
    val typed = typeCheck(sscBlock("import mylib.utils.{helper}"))
    assert(!typed.errors.exists(_.msg.contains("E_PlatformType")))

  // ── scala blocks are exempt — they ARE JVM-native code ─────────────────

  test("import java.io.File in scala block is allowed (scala is JVM-native)"):
    val typed = typeCheck(scalaBlock("import java.io.File"))
    assert(!typed.errors.exists(_.msg.contains("E_PlatformType")),
      s"java import in scala block must not trigger ban; errors: ${typed.errors.map(_.show).mkString(", ")}")

  test("import javax.sql.DataSource in scala block is allowed"):
    val typed = typeCheck(scalaBlock("import javax.sql.DataSource"))
    assert(!typed.errors.exists(_.msg.contains("E_PlatformType")))

  // ── error message quality ───────────────────────────────────────────────

  test("E_PlatformType message mentions std.* and scala block alternative"):
    val typed = typeCheck(sscBlock("import java.io.InputStream"))
    val msg = typed.errors.find(_.msg.contains("E_PlatformType")).map(_.msg).getOrElse("")
    assert(msg.contains("std.*") || msg.contains("std."),
      s"error message should suggest std.* alternative; got: $msg")
    assert(msg.contains("scala") || msg.contains("block"),
      s"error message should mention scala fenced block escape hatch; got: $msg")
