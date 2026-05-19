package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import scalascript.typer.Typer

/** v2.0 — end-to-end test for the separate-compilation artifact pipeline.
 *
 *  Walks the full happy-path of the new infrastructure:
 *    1. Write two `.ssc` files to a temp dir (`a.ssc`, `b.ssc`).
 *    2. Extract + persist `a.ssc`'s `.scim` interface.
 *    3. Re-read the interface and type-check `b.ssc` with it as scope.
 *    4. Normalise both to `NormalizedModule` and link them.
 *    5. Assert the linked result contains both modules' sections.
 *
 *  Backend code-generation is NOT exercised — that's covered by separate
 *  backend tests; here we lock down the artifact pipeline only. */
class EndToEndArtifactTest extends AnyFunSuite:

  private def withTempDir[A](body: os.Path => A): A =
    val d = os.temp.dir(prefix = "ssc-v2-test-")
    try body(d) finally os.remove.all(d)

  private val aSrc: String =
    """---
      |name: a
      |version: 0.1.0
      |---
      |
      |# A
      |
      |```scalascript
      |def add(a: Int, b: Int): Int = a + b
      |```
      |""".stripMargin

  private val bSrc: String =
    """---
      |name: b
      |version: 0.1.0
      |---
      |
      |# B
      |
      |[add](./a.ssc)
      |
      |```scalascript
      |val total = add(1, 2)
      |```
      |""".stripMargin

  test("end-to-end — extract a.scim, type-check b with it, link both, assert structure"):
    withTempDir { d =>
      // ── Step 1: write sources ───────────────────────────────────────────
      val aPath = d / "a.ssc"
      val bPath = d / "b.ssc"
      os.write(aPath, aSrc)
      os.write(bPath, bSrc)

      // ── Step 2: extract + persist a.ssc's interface ─────────────────────
      val aBytes  = os.read.bytes(aPath)
      val aModule = Parser.parse(new String(aBytes, "UTF-8"))
      val aIface  = InterfaceExtractor.extract(aModule, aBytes)
      val scimPath = d / "a.scim"
      ArtifactIO.writeInterfaceFile(aIface, scimPath)
      assert(os.exists(scimPath), ".scim file should be written")

      // The interface should announce `add` as an exported def.
      val addSym = aIface.exports.find(_.name == "add")
      assert(addSym.isDefined,
        s"a.ssc should export `add`, exports were: ${aIface.exports.map(_.name)}")
      assert(addSym.get.kind == "def")

      // ── Step 3: re-read the interface; type-check b with it ────────────
      val readBack = ArtifactIO.readInterfaceFile(scimPath) match
        case Right(i)  => i
        case Left(err) => fail(s"unexpected interface read failure: $err")
      assert(readBack == aIface, ".scim round-trip via disk preserves the interface")

      val bModule = Parser.parse(bSrc)
      val typed   = Typer.typeCheckWithInterfaces(bModule, Map("a" -> readBack))
      // Type-checker must not throw; pre-existing typer behaviour may still
      // surface errors — we lock the public API only.
      assert(typed.sections.nonEmpty, "typed module must have at least one section")
      // The Typer api returns a TypedModule with a possibly-empty errors list;
      // we don't assert error-free because cross-module type-info is
      // best-effort today (every imported symbol resolves at SType.Any).
      // TODO(v2.0): tighten when the typer carries through real types.

      // ── Step 4: normalise + link both modules ──────────────────────────
      val aIr     = Normalize(aModule)
      val bIr     = Normalize(bModule)
      val bBytes  = os.read.bytes(bPath)
      val bIface  = InterfaceExtractor.extract(bModule, bBytes)
      val linked = Linker.link(List(
        Linker.CompiledModule(aIface, aIr),
        Linker.CompiledModule(bIface, bIr)  // b is the entry module
      ))

      // ── Step 5: assertions on the linked module ────────────────────────
      // Both modules' top-level sections must be present in the merged result.
      val titles = linked.sections.map(_.heading.text)
      assert(titles.contains("A"), s"merged module missing A's section, got: $titles")
      assert(titles.contains("B"), s"merged module missing B's section, got: $titles")
      // The merged section count equals the sum of input section counts.
      assert(linked.sections.length == aIr.sections.length + bIr.sections.length,
        "merged section count must equal sum of inputs")
      // Entry-module manifest wins: b's name should surface.
      assert(linked.manifest.flatMap(_.name) == Some("b"))
      // No collisions for these disjoint exports.
      val cols = Linker.detectCollisions(List(
        Linker.CompiledModule(aIface, aIr),
        Linker.CompiledModule(bIface, bIr)
      ))
      assert(cols.isEmpty, s"unexpected collisions: $cols")
    }

  test("end-to-end — Typer(importedInterfaces) constructor accepts an empty map"):
  // Smoke test: confirms the v2.0 constructor coexists with the legacy
  // zero-arg `Typer.typeCheck` factory.
    val module = Parser.parse(aSrc)
    val viaLegacy = Typer.typeCheck(module)
    val viaNew    = Typer.typeCheckWithInterfaces(module, Map.empty)
    assert(viaLegacy.sections.length == viaNew.sections.length)
