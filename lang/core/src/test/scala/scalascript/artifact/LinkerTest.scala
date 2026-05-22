package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir.*

/** v2.0 — tests that `Linker.link` correctly merges compiled modules,
 *  preserves their sections in dependency order, and that
 *  `Linker.detectCollisions` flags cross-module name conflicts. */
class LinkerTest extends AnyFunSuite:

  // ── Fixture helpers ────────────────────────────────────────────────────

  private def mkSection(text: String): Section =
    Section(
      heading     = Heading(level = 1, text = text),
      content     = List(Content.Prose(s"prose for $text")),
      subsections = Nil
    )

  private def mkNormalizedModule(name: String, pkg: List[String], sectionTitles: List[String]): NormalizedModule =
    NormalizedModule(
      manifest = Some(Manifest(
        name         = Some(name),
        version      = Some("0.0.1"),
        description  = None,
        dependencies = Map.empty,
        exports      = Nil,
        targets      = Nil,
        routes       = Nil,
        pkg          = Some(pkg)
      )),
      sections = sectionTitles.map(mkSection)
    )

  private def mkInterface(pkg: List[String], exports: List[ExportedSymbol]): ModuleInterface =
    ModuleInterface(
      magic         = ArtifactVersion.magic,
      abiVersion    = ArtifactVersion.current,
      pkg           = pkg,
      moduleName    = Some(pkg.lastOption.getOrElse("anon")),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = exports
    )

  private def mkExport(pkg: List[String], shortName: String, kind: String = "val"): ExportedSymbol =
    ExportedSymbol(
      name = shortName,
      fqn  = Linker.mangle(pkg, shortName),
      kind = kind,
      tpe  = "Any"
    )

  // ── Empty input ────────────────────────────────────────────────────────

  test("link — empty module list yields an empty NormalizedModule"):
    val res = Linker.link(Nil)
    assert(res.manifest.isEmpty)
    assert(res.sections.isEmpty)

  // ── Concatenation of sections ──────────────────────────────────────────

  test("link — sections of disjoint modules are concatenated in input order"):
    val a = Linker.CompiledModule(
      iface = mkInterface(List("a"), List(mkExport(List("a"), "ax"))),
      body  = mkNormalizedModule("a", List("a"), List("AHead1", "AHead2"))
    )
    val b = Linker.CompiledModule(
      iface = mkInterface(List("b"), List(mkExport(List("b"), "bx"))),
      body  = mkNormalizedModule("b", List("b"), List("BHead1"))
    )
    val merged = Linker.link(List(a, b))
    val titles = merged.sections.map(_.heading.text)
    assert(titles == List("AHead1", "AHead2", "BHead1"),
      s"expected concatenated sections, got: $titles")

  // ── Manifest selection ─────────────────────────────────────────────────

  test("link — manifest of the linked module is the LAST input's manifest"):
  // Document current behaviour: the last module is treated as the "entry
  // point" by the linker and its manifest wins.  See Linker.scala docs.
    val a = Linker.CompiledModule(
      iface = mkInterface(List("first"), Nil),
      body  = mkNormalizedModule("first-name", List("first"), Nil)
    )
    val b = Linker.CompiledModule(
      iface = mkInterface(List("entry"), Nil),
      body  = mkNormalizedModule("entry-name", List("entry"), Nil)
    )
    val merged = Linker.link(List(a, b))
    assert(merged.manifest.flatMap(_.name) == Some("entry-name"),
      s"last module's manifest should win, got: ${merged.manifest.flatMap(_.name)}")

  // ── Collision detection ────────────────────────────────────────────────

  test("detectCollisions — two modules exporting the same short name are reported"):
    val pkgA = List("pkg", "a")
    val pkgB = List("pkg", "b")
    val a = Linker.CompiledModule(
      iface = mkInterface(pkgA, List(mkExport(pkgA, "Card"))),
      body  = mkNormalizedModule("a", pkgA, Nil)
    )
    val b = Linker.CompiledModule(
      iface = mkInterface(pkgB, List(mkExport(pkgB, "Card"))),
      body  = mkNormalizedModule("b", pkgB, Nil)
    )
    val cols = Linker.detectCollisions(List(a, b))
    assert(cols.nonEmpty, "expected `Card` collision between two modules")
    val cardCol = cols.find(_._1 == "Card")
    assert(cardCol.isDefined, s"expected `Card` in collisions, got: ${cols.map(_._1)}")
    val pkgs = cardCol.get._2.toSet
    assert(pkgs == Set(pkgA, pkgB), s"expected both packages reported, got: $pkgs")

  test("detectCollisions — single-module exports are NOT reported as collisions"):
    val pkgA = List("pkg", "a")
    val a = Linker.CompiledModule(
      iface = mkInterface(pkgA, List(mkExport(pkgA, "Card"), mkExport(pkgA, "Button"))),
      body  = mkNormalizedModule("a", pkgA, Nil)
    )
    val cols = Linker.detectCollisions(List(a))
    assert(cols.isEmpty, s"single-module exports must not collide, got: $cols")

  test("detectCollisions — no collisions across disjoint exports"):
    val pkgA = List("pkg", "a")
    val pkgB = List("pkg", "b")
    val a = Linker.CompiledModule(
      iface = mkInterface(pkgA, List(mkExport(pkgA, "AOne"))),
      body  = mkNormalizedModule("a", pkgA, Nil)
    )
    val b = Linker.CompiledModule(
      iface = mkInterface(pkgB, List(mkExport(pkgB, "BOne"))),
      body  = mkNormalizedModule("b", pkgB, Nil)
    )
    val cols = Linker.detectCollisions(List(a, b))
    assert(cols.isEmpty, s"disjoint exports must not collide, got: $cols")

  test("detectCollisions — three-way collision lists all three packages"):
    val pkgs = List(List("a"), List("b"), List("c"))
    val modules = pkgs.map { p =>
      Linker.CompiledModule(
        iface = mkInterface(p, List(mkExport(p, "Same"))),
        body  = mkNormalizedModule(p.head, p, Nil)
      )
    }
    val cols = Linker.detectCollisions(modules)
    assert(cols.length == 1)
    assert(cols.head._1 == "Same")
    assert(cols.head._2.toSet == pkgs.toSet)

  // ── Mangling ───────────────────────────────────────────────────────────

  test("mangle — joins package segments with `_` then appends the name"):
    assert(Linker.mangle(List("org", "example", "ui"), "Card") == "org_example_ui_Card")
    assert(Linker.mangle(List("a"), "b") == "a_b")

  test("mangle — empty package returns the bare name"):
    assert(Linker.mangle(Nil, "Card") == "Card")
