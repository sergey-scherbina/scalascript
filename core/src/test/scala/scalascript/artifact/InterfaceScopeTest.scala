package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir.*
import scalascript.typer.{Scope, Symbol, SType, SymbolKind as TSymbolKind}

/** v2.0 — tests that `InterfaceScope.fromInterface(s)` correctly populates
 *  a typer `Scope` from a pre-compiled `ModuleInterface` so that the typer
 *  can resolve cross-module names without re-parsing source. */
class InterfaceScopeTest extends AnyFunSuite:

  private def baseInterface(
      exports:    List[ExportedSymbol] = Nil,
      externDefs: List[ExportedSymbol] = Nil
  ): ModuleInterface =
    ModuleInterface(
      magic         = ArtifactVersion.magic,
      abiVersion    = ArtifactVersion.current,
      pkg           = List("org", "example"),
      moduleName    = Some("demo"),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = exports,
      externDefs    = externDefs
    )

  // ── Population from exports ────────────────────────────────────────────

  test("fromInterface — exported symbol becomes resolvable in the scope"):
    val iface = baseInterface(exports = List(
      ExportedSymbol("Foo", "org_example_Foo", "def", "Int => String")
    ))
    val scope = InterfaceScope.fromInterface(iface)
    val sym   = scope.lookup("Foo")
    assert(sym.isDefined, "Foo should resolve in the scope")
    assert(sym.get.name == "Foo")

  test("fromInterface — function type string `Int => String` parses to SType.Function"):
    val iface = baseInterface(exports = List(
      ExportedSymbol("Foo", "org_example_Foo", "def", "Int => String")
    ))
    val scope = InterfaceScope.fromInterface(iface)
    val sym   = scope.lookup("Foo").get
    sym.tpe match
      case SType.Function(List(SType.Named("Int", Nil)), SType.Named("String", Nil)) =>
        ()   // ok
      case other =>
        fail(s"expected SType.Function(Int => String), got: ${other.show}")

  test("fromInterface — named primitive types map to SType primitives"):
    val iface = baseInterface(exports = List(
      ExportedSymbol("a", "org_example_a", "val", "Int"),
      ExportedSymbol("b", "org_example_b", "val", "String"),
      ExportedSymbol("c", "org_example_c", "val", "Boolean"),
      ExportedSymbol("d", "org_example_d", "val", "Unit")
    ))
    val scope = InterfaceScope.fromInterface(iface)
    assert(scope.lookup("a").get.tpe == SType.Int)
    assert(scope.lookup("b").get.tpe == SType.String)
    assert(scope.lookup("c").get.tpe == SType.Boolean)
    assert(scope.lookup("d").get.tpe == SType.Unit)

  test("fromInterface — `Any` type string yields SType.Any (current best-effort)"):
  // TODO(v2.0): tighten when the typer surfaces real types instead of Any.
    val iface = baseInterface(exports = List(
      ExportedSymbol("blob", "org_example_blob", "val", "Any")
    ))
    val scope = InterfaceScope.fromInterface(iface)
    assert(scope.lookup("blob").get.tpe == SType.Any)

  test("fromInterface — kind strings map to the right SymbolKind"):
    val iface = baseInterface(exports = List(
      ExportedSymbol("v", "v", "val",    "Int"),
      ExportedSymbol("f", "f", "def",    "Any"),
      ExportedSymbol("O", "O", "object", "Any"),
      ExportedSymbol("T", "T", "trait",  "Any")
    ))
    val scope = InterfaceScope.fromInterface(iface)
    assert(scope.lookup("v").get.kind == TSymbolKind.Val)
    assert(scope.lookup("f").get.kind == TSymbolKind.Def)
    assert(scope.lookup("O").get.kind == TSymbolKind.Object)
    assert(scope.lookup("T").get.kind == TSymbolKind.Trait)

  // ── Extern signatures ──────────────────────────────────────────────────

  test("fromInterface — extern defs populate the scope as Def symbols"):
    val iface = baseInterface(externDefs = List(
      ExportedSymbol("serve", "org_example_serve", "extern", "Int => Unit")
    ))
    val scope = InterfaceScope.fromInterface(iface)
    val sym   = scope.lookup("serve")
    assert(sym.isDefined, s"extern `serve` should resolve in the scope")
    assert(sym.get.kind == TSymbolKind.Def)

  test("fromInterface — both exports and externs are present in the same scope"):
    val iface = baseInterface(
      exports    = List(ExportedSymbol("Foo", "org_example_Foo", "val", "Int")),
      externDefs = List(ExportedSymbol("nowMs", "org_example_nowMs", "extern", "Long"))
    )
    val scope = InterfaceScope.fromInterface(iface)
    assert(scope.lookup("Foo").isDefined)
    assert(scope.lookup("nowMs").isDefined)

  // ── Parent scope chaining ──────────────────────────────────────────────

  test("fromInterface — parent-scope lookups fall through to the parent"):
    val parent = Scope(None, "<prelude>")
    parent.define(Symbol("println", SType.Any, TSymbolKind.Def))
    val iface  = baseInterface(exports = List(
      ExportedSymbol("foo", "org_example_foo", "val", "Int")
    ))
    val scope = InterfaceScope.fromInterface(iface, parent = Some(parent))
    assert(scope.lookup("foo").isDefined, "interface symbol resolves locally")
    assert(scope.lookup("println").isDefined, "parent symbol resolves via fallback")

  // ── Merged from multiple interfaces ────────────────────────────────────

  test("fromInterfaces — symbols from multiple interfaces are merged into one scope"):
    val ifA = baseInterface(exports = List(
      ExportedSymbol("a1", "pkg_a_a1", "val", "Int")
    )).copy(pkg = List("pkg", "a"))
    val ifB = baseInterface(exports = List(
      ExportedSymbol("b1", "pkg_b_b1", "val", "String")
    )).copy(pkg = List("pkg", "b"))
    val merged = InterfaceScope.fromInterfaces(List("a" -> ifA, "b" -> ifB))
    assert(merged.lookup("a1").isDefined)
    assert(merged.lookup("b1").isDefined)

  test("fromInterfaces — later interface shadows earlier on name conflict"):
    val ifEarly = baseInterface(exports = List(
      ExportedSymbol("name", "early_name", "val", "Int")
    ))
    val ifLate  = baseInterface(exports = List(
      ExportedSymbol("name", "late_name",  "val", "String")
    ))
    val merged = InterfaceScope.fromInterfaces(List("e" -> ifEarly, "l" -> ifLate))
    val sym = merged.lookup("name").get
    assert(sym.tpe == SType.String,
      s"later interface should shadow earlier; got tpe=${sym.tpe.show}")
