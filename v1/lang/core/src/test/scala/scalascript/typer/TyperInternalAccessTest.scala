package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser
import scalascript.ir.{ExportedSymbol, ModuleInterface}

/** arch-lib-p2 — @internal annotation: cross-package access control.
 *
 *  `@internal` defs in an imported module must not be called by the
 *  consumer module (which comes from a different package).  The Typer
 *  emits a hard error at every use-site in the importing module.
 */
class TyperInternalAccessTest extends AnyFunSuite:

  private def moduleOf(src: String): scalascript.ast.Module =
    Parser.parse(
      s"""# Test
         |
         |```scalascript
         |$src
         |```
         |""".stripMargin
    )

  /** Build a synthetic `ModuleInterface` with one `@internal` symbol. */
  private def libWithInternal(name: String): ModuleInterface =
    ModuleInterface(
      magic         = scalascript.ir.ArtifactVersion.magic,
      abiVersion    = scalascript.ir.ArtifactVersion.current,
      pkg           = List("example"),
      moduleName    = Some("example"),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = List(
        ExportedSymbol(name = name, fqn = s"example_$name", kind = "def", isInternal = true),
        ExportedSymbol(name = "publicFn", fqn = "example_publicFn", kind = "def", isInternal = false)
      )
    )

  // ─── Cross-package @internal access → error ───────────────────────────

  test("@internal def from imported module — call site emits an error"):
    val iface = libWithInternal("secretImpl")
    val tm = Typer.typeCheckWithInterfaces(
      moduleOf("""def caller(): String = secretImpl()"""),
      interfaces = Map("example" -> iface),
      strict     = false
    )
    assert(tm.hasErrors,
      "accessing @internal def should be an error")
    val msgs = tm.errors.map(_.msg).mkString(" | ")
    assert(msgs.contains("secretImpl"),
      s"error message should name the @internal symbol; got: $msgs")
    assert(msgs.contains("@internal"),
      s"error message should mention @internal; got: $msgs")

  test("non-@internal def from imported module — no error"):
    val iface = libWithInternal("secretImpl")
    val tm = Typer.typeCheckWithInterfaces(
      moduleOf("""def caller(): String = publicFn()"""),
      interfaces = Map("example" -> iface),
      strict     = false
    )
    val internalErrors = tm.errors.filter(_.msg.contains("@internal"))
    assert(internalErrors.isEmpty,
      s"publicFn is not @internal; got unexpected errors: ${internalErrors.map(_.msg).mkString(", ")}")

  test("@internal def NOT in imported interface (local def) — no error"):
    val iface = libWithInternal("secretImpl")
    val tm = Typer.typeCheckWithInterfaces(
      moduleOf("""
        @internal
        def localHelper(): String = "ok"
        def caller(): String = localHelper()
      """),
      interfaces = Map("example" -> iface),
      strict     = false
    )
    val internalErrors = tm.errors.filter(_.msg.contains("@internal"))
    assert(internalErrors.isEmpty,
      "@internal on a LOCAL def should not produce a cross-package error; " +
      s"got: ${internalErrors.map(_.msg).mkString(", ")}")

  test("@internal def — multiple call sites each produce an error"):
    val iface = libWithInternal("secretImpl")
    val tm = Typer.typeCheckWithInterfaces(
      moduleOf("""
        def a(): String = secretImpl()
        def b(): String = secretImpl()
      """),
      interfaces = Map("example" -> iface),
      strict     = false
    )
    val internalErrors = tm.errors.filter(e => e.msg.contains("@internal") && e.msg.contains("secretImpl"))
    assert(internalErrors.length >= 2,
      s"expected at least 2 errors (one per call site); got ${internalErrors.length}")

  test("no imported interfaces — @internal annotation on local def has no cross-pkg effect"):
    val tm = Typer.typeCheck(moduleOf("""
      @internal
      def helperFn(): Int = 42
      def caller(): Int = helperFn()
    """))
    val internalErrors = tm.errors.filter(_.msg.contains("@internal"))
    assert(internalErrors.isEmpty,
      s"no cross-package access, so @internal should not trigger an error; " +
      s"got: ${internalErrors.map(_.msg).mkString(", ")}")

  // ─── InterfaceExtractor — @internal flag in emitted interface ─────────

  test("InterfaceExtractor — @internal def is marked isInternal in emitted interface"):
    import scalascript.artifact.InterfaceExtractor
    val m = moduleOf(
      """@internal
        |def secretImpl(): String = "hidden"
        |def publicFn(): String = "visible"
        |""".stripMargin
    )
    val iface = InterfaceExtractor.extract(m, Array.emptyByteArray)
    val secretSym = iface.exports.find(_.name == "secretImpl")
    assert(secretSym.isDefined, "secretImpl should appear in exports")
    assert(secretSym.get.isInternal,
      "secretImpl annotated @internal should have isInternal = true in the interface")

  test("InterfaceExtractor — non-@internal def has isInternal = false"):
    import scalascript.artifact.InterfaceExtractor
    val m = moduleOf(
      """def publicFn(): String = "visible"
        |""".stripMargin
    )
    val iface = InterfaceExtractor.extract(m, Array.emptyByteArray)
    val publicSym = iface.exports.find(_.name == "publicFn")
    assert(publicSym.isDefined, "publicFn should appear in exports")
    assert(!publicSym.get.isInternal,
      "publicFn without @internal should have isInternal = false")

  // ─── ExportedSymbol backward-compat ───────────────────────────────────

  test("ExportedSymbol isInternal defaults to false for backward compat"):
    val sym = ExportedSymbol(name = "foo", fqn = "foo", kind = "def")
    assert(!sym.isInternal,
      "isInternal default must be false for backward compatibility")
