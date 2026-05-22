package scalascript.interop.facade

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir.*
import scalascript.artifact.ArtifactIO

/** Unit tests for the Tier-2 FacadeGenerator. */
class FacadeGeneratorTest extends AnyFunSuite:

  // ── Fixture helpers ────────────────────────────────────────────────────

  private def iface(
      pkg: List[String],
      moduleName: String,
      exports: List[(String, String)],  // (natural-leaf, kind)
      facade: Map[String, String]
  ): ModuleInterface =
    ModuleInterface(
      magic         = ArtifactVersion.magic,
      abiVersion    = ArtifactVersion.current,
      pkg           = pkg,
      moduleName    = Some(moduleName),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = exports.map { case (n, k) =>
        ExportedSymbol(n, scalascript.artifact.Linker.mangle(pkg, n), k)
      },
      scalaFacade   = facade
    )

  // ── Top-level export generation ────────────────────────────────────────

  test("single top-level def → one package file with one export"):
    val m = iface(
      pkg = List("std", "eq"),
      moduleName = "std-eq",
      exports = List("eqv" -> "def"),
      facade = Map("std.eq.eqv" -> "_ssc_runtime.std_eq_eqv")
    )
    val sources = FacadeGenerator.generateFromInterfaces(List(m))
    assert(sources.keySet == Set("std/eq.scala"),
      s"expected one file at std/eq.scala, got: ${sources.keySet}")
    val src = sources("std/eq.scala")
    assert(src.contains("package std.eq:"),
      s"missing package declaration:\n$src")
    assert(src.contains("export Ssc.std_eq_eqv as eqv"),
      s"missing export line:\n$src")

  test("multiple exports same package → all collected in one file"):
    val m = iface(
      pkg = List("std", "eq"),
      moduleName = "std-eq",
      exports = List("Eq" -> "object", "eqv" -> "def", "neqv" -> "def"),
      facade = Map(
        "std.eq.Eq"   -> "_ssc_runtime.std_eq_Eq",
        "std.eq.eqv"  -> "_ssc_runtime.std_eq_eqv",
        "std.eq.neqv" -> "_ssc_runtime.std_eq_neqv"
      )
    )
    val sources = FacadeGenerator.generateFromInterfaces(List(m))
    val src = sources("std/eq.scala")
    assert(src.contains("export Ssc.std_eq_Eq as Eq"))
    assert(src.contains("export Ssc.std_eq_eqv as eqv"))
    assert(src.contains("export Ssc.std_eq_neqv as neqv"))

  test("exports sorted alphabetically inside the package file"):
    // Stable output across runs and across artifact-dir reorderings.
    val m = iface(
      pkg = List("std", "x"),
      moduleName = "x",
      exports = List("zeta" -> "def", "alpha" -> "def", "mu" -> "def"),
      facade = Map(
        "std.x.zeta"  -> "_ssc_runtime.std_x_zeta",
        "std.x.alpha" -> "_ssc_runtime.std_x_alpha",
        "std.x.mu"    -> "_ssc_runtime.std_x_mu"
      )
    )
    val src = FacadeGenerator.generateFromInterfaces(List(m))("std/x.scala")
    val idxAlpha = src.indexOf("as alpha")
    val idxMu    = src.indexOf("as mu")
    val idxZeta  = src.indexOf("as zeta")
    assert(idxAlpha < idxMu && idxMu < idxZeta,
      s"expected alphabetic order [alpha, mu, zeta]; got positions ($idxAlpha, $idxMu, $idxZeta)")

  // ── Multi-module package sharing ───────────────────────────────────────

  test("two .scim contributing to same package merge into one file"):
    // std/cluster/types.ssc + std/cluster/membership.ssc both `package: std.cluster`.
    val m1 = iface(
      pkg = List("std", "cluster"),
      moduleName = "types",
      exports = List("NodeId" -> "type"),
      facade = Map("std.cluster.NodeId" -> "_ssc_runtime.std_cluster_NodeId")
    )
    val m2 = iface(
      pkg = List("std", "cluster"),
      moduleName = "membership",
      exports = List("join" -> "def"),
      facade = Map("std.cluster.join" -> "_ssc_runtime.std_cluster_join")
    )
    val sources = FacadeGenerator.generateFromInterfaces(List(m1, m2))
    assert(sources.keySet == Set("std/cluster.scala"),
      s"expected one merged file, got: ${sources.keySet}")
    val src = sources("std/cluster.scala")
    assert(src.contains("export Ssc.std_cluster_NodeId as NodeId"))
    assert(src.contains("export Ssc.std_cluster_join as join"))
    // Header lists both source modules.
    assert(src.contains("Source modules:"))
    assert(src.contains("types") && src.contains("membership"))

  // ── Nested-entry skipping ──────────────────────────────────────────────

  test("nested entries (depth > 1 beyond pkg) are skipped in v0.1"):
    // `std.foo.Bar.apply` (depth 2 beyond pkg = std.foo) should NOT
    // appear as a top-level export — JvmGen's nested member shape isn't
    // pinned yet.  Conservative behaviour: only the parent `Bar` shows.
    val m = iface(
      pkg = List("std", "foo"),
      moduleName = "foo",
      exports = List("Bar" -> "object"),
      facade = Map(
        "std.foo.Bar"        -> "_ssc_runtime.std_foo_Bar",
        "std.foo.Bar.apply"  -> "_ssc_runtime.std_foo_Bar_apply"
      )
    )
    val sources = FacadeGenerator.generateFromInterfaces(List(m))
    val src = sources("std/foo.scala")
    assert(src.contains("export Ssc.std_foo_Bar as Bar"),
      s"top-level Bar must be exported:\n$src")
    assert(!src.contains("as apply"),
      s"nested .apply must NOT be exported in v0.1:\n$src")

  // ── No-package module ──────────────────────────────────────────────────

  test("module with empty pkg emits to _root_.scala without package clause"):
    val m = iface(
      pkg = Nil,
      moduleName = "rootless",
      exports = List("greet" -> "def"),
      facade = Map("greet" -> "_ssc_runtime.greet")
    )
    val sources = FacadeGenerator.generateFromInterfaces(List(m))
    assert(sources.keySet == Set("_root_.scala"))
    val src = sources("_root_.scala")
    // No nested `package X:` block — the empty-pkg case puts exports at
    // top level alongside the runtime alias import.
    assert(!src.linesIterator.exists(_.startsWith("package ")),
      s"no `package` clause expected:\n$src")
    // When the leaf name equals the runtime member name, the export is
    // emitted in simplified form (`export Ssc.greet` rather than
    // `export Ssc.greet as greet`).
    assert(src.contains("export Ssc.greet"),
      s"expected simplified export:\n$src")

  // ── Legacy fallback (no scalaFacade field) ─────────────────────────────

  test("legacy .scim without scalaFacade falls back to identity (Tier-5 semantics)"):
    // Tier-5 update: when `scalaFacade` is absent (pre-Tier-1 artifact),
    // we recompute it as the IDENTITY map (natural FQN == JVM symbol,
    // matching the new `package` clause emission).  The generator then
    // skips emission since no `export` is needed — the consumer's
    // `import std.legacy.hello` resolves directly against the JAR.
    val pkg = List("std", "legacy")
    val legacy = iface(
      pkg = pkg,
      moduleName = "legacy",
      exports = List("hello" -> "def"),
      facade = Map.empty    // ← legacy artifact: no table
    )
    val sources = FacadeGenerator.generateFromInterfaces(List(legacy))
    // No facade file emitted — natural FQN works directly.
    assert(sources.isEmpty,
      s"Tier-5 identity facade should not emit a source file; got: ${sources.keySet}")
    // The fallback table IS available via facadeEntriesOf for tooling
    // that needs the natural-FQN list (e.g. ScalascriptLoader).
    val entries = FacadeGenerator.facadeEntriesOf(legacy)
    assert(entries == Map("std.legacy.hello" -> "std.legacy.hello"),
      s"recomputed facade should be identity; got: $entries")

  // ── Generated source carries the runtime import alias ─────────────────

  test("every emitted file starts with `import _ssc_runtime as Ssc`"):
    // Required so nested `package X.Y: export Ssc.*` resolves — the
    // alias is the only way to reference the empty-package runtime
    // object from inside a named package.
    val m = iface(
      pkg = List("p"),
      moduleName = "p",
      exports = List("x" -> "def"),
      facade = Map("p.x" -> "_ssc_runtime.p_x")
    )
    val src = FacadeGenerator.generateFromInterfaces(List(m))("p.scala")
    assert(src.contains("import _ssc_runtime as Ssc"),
      s"facade must alias the runtime; got:\n$src")

  // ── Generated source compiles standalone (header markers) ──────────────

  test("emitted file carries the AUTO-GENERATED banner"):
    val m = iface(
      pkg = List("org"),
      moduleName = "org",
      exports = List("x" -> "def"),
      facade = Map("org.x" -> "_ssc_runtime.org_x")
    )
    val src = FacadeGenerator.generateFromInterfaces(List(m))("org.scala")
    assert(src.contains("AUTO-GENERATED"),
      s"missing AUTO-GENERATED marker:\n$src")
    assert(src.contains("docs/scala-interop.md"),
      s"missing doc-pointer comment:\n$src")

  // ── Disk-based entrypoint ──────────────────────────────────────────────

  test("generate(artifactDir) reads .scim files from disk"):
    val d = os.temp.dir(prefix = "ssc-interop-")
    try
      val m = iface(
        pkg = List("std", "ondisk"),
        moduleName = "ondisk",
        exports = List("ping" -> "def"),
        facade = Map("std.ondisk.ping" -> "_ssc_runtime.std_ondisk_ping")
      )
      ArtifactIO.writeInterfaceFile(m, d / "ondisk.scim")
      val sources = FacadeGenerator.generate(d)
      assert(sources.contains("std/ondisk.scala"),
        s"expected disk-loaded facade, got: ${sources.keySet}")
      assert(sources("std/ondisk.scala").contains("as ping"))
    finally os.remove.all(d)

  test("generate against empty / non-existent dir returns empty map"):
    val d = os.temp.dir(prefix = "ssc-interop-")
    try
      val empty = FacadeGenerator.generate(d)
      assert(empty.isEmpty, "empty dir → empty facade")
      val nonexistent = os.Path("/tmp/does-not-exist-" + System.nanoTime())
      assert(FacadeGenerator.generate(nonexistent).isEmpty,
        "non-existent dir → empty facade (no crash)")
    finally os.remove.all(d)
