package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser
import scalascript.ir.{ExportedSymbol, ModuleInterface}

/** v2.0 — typer strict mode pins down undefined-name diagnostics.
 *
 *  In permissive mode (the default, used by `ssc compile`, `emit-interface`,
 *  and `emit-ir`) an unknown identifier silently collapses to `SType.Any` so
 *  that downstream compilation never cascades on a single resolution miss.
 *
 *  In strict mode (used by `ssc check-with-iface`) the same unknown identifier
 *  is still typed as `SType.Any` (no cascade), but the typer additionally
 *  records a `TypeError` naming the missing symbol.
 */
class TyperStrictModeTest extends AnyFunSuite:

  /** Build a single-block scalascript module from a code body. */
  private def moduleOf(scalascriptSource: String): scalascript.ast.Module =
    val withFence =
      s"""# Test
         |
         |```scalascript
         |$scalascriptSource
         |```
         |""".stripMargin
    Parser.parse(withFence)

  // ── 1. Permissive default — undefined name silently yields SType.Any ──────

  test("permissive default — undefined name produces no diagnostic"):
    val typed = Typer.typeCheck(moduleOf(
      """def useUnknown(): Int = thisNameDoesNotExist(1, 2)"""
    ))
    assert(!typed.hasErrors,
      s"permissive mode should accept undefined names; got errors:\n" +
      typed.errors.map(_.show).mkString("\n"))

  // ── 2. Strict mode — undefined name records a TypeError ───────────────────

  test("strict mode — undefined name produces a diagnostic naming the symbol"):
    val typed = Typer.typeCheckStrict(moduleOf(
      """def useUnknown(): Int = thisNameDoesNotExist(1, 2)"""
    ))
    assert(typed.hasErrors,
      "strict mode should reject undefined names")
    val msgs = typed.errors.map(_.msg).mkString(" | ")
    assert(msgs.contains("thisNameDoesNotExist"),
      s"expected diagnostic to name the missing symbol; got: $msgs")

  // ── 3. Strict + import — names from interface resolve; others don't ───────

  test("strict + import — name in the imported interface resolves cleanly"):
    val iface = ModuleInterface(
      magic         = scalascript.ir.ArtifactVersion.magic,
      abiVersion    = scalascript.ir.ArtifactVersion.current,
      pkg           = List("a"),
      moduleName    = Some("a"),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = List(ExportedSymbol(
        name = "add",
        fqn  = "a.add",
        kind = "def",
        tpe  = "(Int, Int) => Int"
      ))
    )
    val typed = Typer.typeCheckWithInterfaces(
      moduleOf("""def useAdd(): Int = add(1, 2)"""),
      interfaces = Map("a" -> iface),
      strict     = true
    )
    assert(!typed.hasErrors,
      s"expected `add` from the imported interface to resolve; got errors:\n" +
      typed.errors.map(_.show).mkString("\n"))

  test("strict + import — a name NOT exported by the interface is rejected"):
    val iface = ModuleInterface(
      magic         = scalascript.ir.ArtifactVersion.magic,
      abiVersion    = scalascript.ir.ArtifactVersion.current,
      pkg           = List("a"),
      moduleName    = Some("a"),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = List(ExportedSymbol(
        name = "add",
        fqn  = "a.add",
        kind = "def",
        tpe  = "(Int, Int) => Int"
      ))
    )
    val typed = Typer.typeCheckWithInterfaces(
      moduleOf("""def useMul(): Int = mul(1, 2)"""),
      interfaces = Map("a" -> iface),
      strict     = true
    )
    assert(typed.hasErrors,
      "expected `mul` (not exported by the interface) to be flagged")
    val msgs = typed.errors.map(_.msg).mkString(" | ")
    assert(msgs.contains("mul"),
      s"expected diagnostic to name the missing `mul`; got: $msgs")

  // ── 4. Sanity: builtins are never flagged ─────────────────────────────────

  test("strict mode — builtin prelude names (println, Some, List, Map, math, runActors) pass"):
    val typed = Typer.typeCheckStrict(moduleOf(
      """val r1 = println("hi")
        |val r2 = Some(42)
        |val r3 = List(1, 2, 3)
        |val r4 = Map("a" -> 1)
        |val n  = None""".stripMargin
    ))
    assert(!typed.hasErrors,
      s"builtin names should not be flagged; got errors:\n" +
      typed.errors.map(_.show).mkString("\n"))

  // ── 5. Same-module defs are in scope, even in strict mode ─────────────────

  test("strict mode — references to same-module defs resolve via the scope chain"):
    val typed = Typer.typeCheckStrict(moduleOf(
      """def add(x: Int, y: Int): Int = x + y
        |def useAdd(): Int = add(1, 2)""".stripMargin
    ))
    assert(!typed.hasErrors,
      s"same-module defs should resolve; got errors:\n" +
      typed.errors.map(_.show).mkString("\n"))

  // ── 6. Function parameters are in scope inside the function body ──────────

  test("strict mode — function parameters resolve inside the body"):
    val typed = Typer.typeCheckStrict(moduleOf(
      """def addOne(x: Int): Int = x + 1"""
    ))
    assert(!typed.hasErrors,
      s"function params should resolve in body; got errors:\n" +
      typed.errors.map(_.show).mkString("\n"))
