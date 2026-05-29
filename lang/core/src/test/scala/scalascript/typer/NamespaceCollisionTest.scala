package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser
import scalascript.ir.{ExportedSymbol, ModuleInterface}
import scalascript.artifact.{InterfaceScope, NamespaceCollision}

/** arch-lib-p3 — Namespace collision detection.
 *
 *  When two imported modules export the same top-level name, the typer emits
 *  a warning.  `--strict-namespaces` turns warnings into hard errors.
 *  The qualified import `[Name from Alias](path)` suppresses a known collision.
 */
class NamespaceCollisionTest extends AnyFunSuite:

  private def moduleOf(src: String): scalascript.ast.Module =
    Parser.parse(
      s"""# Test
         |
         |```scalascript
         |$src
         |```
         |""".stripMargin
    )

  private def ifaceWith(alias: String, exportNames: String*): (String, ModuleInterface) =
    alias -> ModuleInterface(
      magic         = scalascript.ir.ArtifactVersion.magic,
      abiVersion    = scalascript.ir.ArtifactVersion.current,
      pkg           = List(alias),
      moduleName    = Some(alias),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = exportNames.toList.map { n =>
        ExportedSymbol(name = n, fqn = s"$alias.$n", kind = "def", tpe = "Any")
      }
    )

  // ─── InterfaceScope.detectCollisions unit tests ───────────────────────────

  test("detectCollisions — single import: no collisions"):
    val ifaces = List(ifaceWith("A", "Http", "Json"))
    assert(InterfaceScope.detectCollisions(ifaces).isEmpty)

  test("detectCollisions — two imports, no overlap: no collisions"):
    val ifaces = List(ifaceWith("A", "Http", "Json"), ifaceWith("B", "Sql", "Csv"))
    assert(InterfaceScope.detectCollisions(ifaces).isEmpty)

  test("detectCollisions — two imports, one shared name: single collision"):
    val ifaces = List(ifaceWith("A", "Http", "Json"), ifaceWith("B", "Http", "Csv"))
    val cols   = InterfaceScope.detectCollisions(ifaces)
    assert(cols.length == 1)
    assert(cols.head == NamespaceCollision("Http", "A", "B"))

  test("detectCollisions — two imports, two shared names: two collisions"):
    val ifaces = List(ifaceWith("A", "Http", "Json"), ifaceWith("B", "Http", "Json"))
    val cols   = InterfaceScope.detectCollisions(ifaces)
    assert(cols.length == 2)
    val names = cols.map(_.name).toSet
    assert(names == Set("Http", "Json"))

  test("detectCollisions — suppressed collision: not reported"):
    val ifaces = List(ifaceWith("A", "Http"), ifaceWith("B", "Http"))
    val suppressed = Set("Http" -> "A")
    val cols = InterfaceScope.detectCollisions(ifaces, suppressed)
    assert(cols.isEmpty, s"expected no collision after suppression; got: $cols")

  // ─── Typer integration ────────────────────────────────────────────────────

  test("collision warning — two modules export same name → warning in TypedModule"):
    val tm = Typer.typeCheckWithCollisionWarnings(
      moduleOf("def use(): Any = Http"),
      Map(ifaceWith("A", "Http"), ifaceWith("B", "Http"))
    )
    assert(!tm.hasErrors, s"collision should be warning, not error; errors: ${tm.errors.map(_.msg).mkString(", ")}")
    val warns = tm.warnings
    assert(warns.nonEmpty, "expected a collision warning")
    assert(warns.exists(_.msg.contains("Http")),
      s"warning should mention 'Http'; got: ${warns.map(_.msg).mkString(" | ")}")
    assert(warns.exists(w => w.msg.contains("'A'") && w.msg.contains("'B'")),
      s"warning should mention both aliases; got: ${warns.map(_.msg).mkString(" | ")}")

  test("collision — single import, no overlap: no warning"):
    val tm = Typer.typeCheckWithCollisionWarnings(
      moduleOf("def use(): Any = Http"),
      Map(ifaceWith("A", "Http", "Json"))
    )
    assert(tm.warnings.isEmpty,
      s"no collision expected for single import; got: ${tm.warnings.map(_.msg).mkString(", ")}")

  test("strict-namespaces — collision becomes hard error"):
    val tm = Typer.typeCheckStrictNamespaces(
      moduleOf("def use(): Any = Http"),
      Map(ifaceWith("A", "Http"), ifaceWith("B", "Http"))
    )
    assert(tm.hasErrors, "strict-namespaces mode should turn collision into a hard error")
    assert(tm.errors.exists(e => !e.isWarning && e.msg.contains("Http")),
      s"expected error (not warning) mentioning 'Http'; got: ${tm.errors.map(_.show).mkString(" | ")}")

  test("strict-namespaces — no collision: passes cleanly"):
    val tm = Typer.typeCheckStrictNamespaces(
      moduleOf("def use(): Any = Http"),
      Map(ifaceWith("A", "Http", "Json"), ifaceWith("B", "Sql"))
    )
    assert(!tm.hasErrors,
      s"no collision → should pass strictly; got errors: ${tm.errors.map(_.msg).mkString(", ")}")

  // ─── Qualified import syntax `[Name from Module]` ─────────────────────────

  test("qualified import `[Name from Module]` parses without error"):
    val src =
      """# Test
        |
        |[Http from A](https://example.com/a.ssc)
        |
        |```scalascript
        |def use(): Any = Http
        |```
        |""".stripMargin
    val module = Parser.parse(src)
    val imports = module.sections.flatMap(_.content).collect {
      case imp: scalascript.ast.Content.Import => imp
    }
    assert(imports.exists(_.bindings.exists(b => b.name == "Http" && b.fromModule == Some("A"))),
      s"expected ImportBinding(name=Http, fromModule=Some(A)); got: ${imports.map(_.bindings)}")

  test("qualified import suppresses collision warning"):
    val tm = Typer.typeCheckWithCollisionWarnings(
      moduleOf("def use(): Any = Http"),
      Map(ifaceWith("A", "Http"), ifaceWith("B", "Http")),
      suppressedCollisions = Set("Http" -> "A")
    )
    assert(tm.warnings.isEmpty,
      s"suppressed collision should produce no warning; got: ${tm.warnings.map(_.msg).mkString(", ")}")

  // ─── NamespaceCollision.message ───────────────────────────────────────────

  test("NamespaceCollision.message mentions both aliases and suggests qualified form"):
    val col = NamespaceCollision("Http", "A", "B")
    val msg = col.message
    assert(msg.contains("Http"),  s"message should mention 'Http': $msg")
    assert(msg.contains("'A'"),   s"message should mention 'A': $msg")
    assert(msg.contains("'B'"),   s"message should mention 'B': $msg")
    assert(msg.contains("from"),  s"message should suggest qualified form with 'from': $msg")
