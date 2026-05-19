package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir.*
import scalascript.parser.Parser

/** v2.0 — tests that the `InterfaceExtractor` produces the expected
 *  `ModuleInterface` from a parsed `.ssc` module.
 *
 *  These tests document current best-effort behaviour.  Where the typer
 *  degrades a type to `Any` we encode that in the assertion and tag with
 *  `// TODO(v2.0): tighten when type info is real`. */
class InterfaceExtractorTest extends AnyFunSuite:

  // ── Fixture helpers ────────────────────────────────────────────────────

  /** Extract an interface from raw `.ssc` source. */
  private def extract(src: String): ModuleInterface =
    val module = Parser.parse(src)
    InterfaceExtractor.extract(module, src.getBytes("UTF-8"))

  // ── Envelope + hash invariants ─────────────────────────────────────────

  test("extract — produced interface carries current magic + ABI version"):
    val src =
      """# Tiny
        |
        |```scalascript
        |val one = 1
        |```
        |""".stripMargin
    val iface = extract(src)
    assert(iface.magic      == ArtifactVersion.magic)
    assert(iface.abiVersion == ArtifactVersion.current)

  test("extract — source hash is non-empty and 64-char hex (SHA-256)"):
    val iface = extract("# Empty\n")
    assert(iface.sourceHash.nonEmpty)
    assert(iface.sourceHash.length == 64, s"expected 64-char hex, got: ${iface.sourceHash}")
    assert(iface.sourceHash.forall(c => "0123456789abcdef".contains(c)),
      s"non-hex characters in hash: ${iface.sourceHash}")

  test("extract — identical sources produce identical SHA-256 source hashes"):
    val src =
      """# Demo
        |
        |```scalascript
        |val x = 42
        |def f(a: Int): Int = a + 1
        |```
        |""".stripMargin
    val a = extract(src)
    val b = extract(src)
    assert(a.sourceHash == b.sourceHash, "deterministic SHA-256 expected for identical bytes")

  test("extract — differing sources produce different hashes"):
    val a = extract("# A\n")
    val b = extract("# B\n")
    assert(a.sourceHash != b.sourceHash)

  // ── Manifest plumbing ──────────────────────────────────────────────────

  test("extract — package, name, version and deps flow from front-matter"):
    val src =
      """---
        |name: ui-kit
        |version: 0.2.1
        |package: org.example.ui
        |dependencies:
        |  std: 1.0.0
        |  http: 0.3.2
        |---
        |
        |# Hello
        |""".stripMargin
    val iface = extract(src)
    assert(iface.pkg           == List("org", "example", "ui"))
    assert(iface.moduleName    == Some("ui-kit"))
    assert(iface.moduleVersion == Some("0.2.1"))
    assert(iface.dependencies  == Map("std" -> "1.0.0", "http" -> "0.3.2"))

  test("extract — missing front-matter yields empty pkg and None names"):
    val iface = extract("# Plain\n\n```scalascript\nval z = 0\n```\n")
    assert(iface.pkg           == Nil)
    assert(iface.moduleName    == None)
    assert(iface.moduleVersion == None)
    assert(iface.dependencies.isEmpty)

  // ── Exports — case class ───────────────────────────────────────────────

  test("extract — a `case class Foo(x: Int)` yields an exported symbol named Foo"):
    val src =
      """# Demo
        |
        |```scalascript
        |case class Foo(x: Int)
        |```
        |""".stripMargin
    val iface = extract(src)
    val names = iface.exports.map(_.name)
    assert(names.contains("Foo"), s"expected `Foo` in exports, got: $names")

  test("extract — top-level `def add(a: Int, b: Int): Int` is exported"):
    val src =
      """# Demo
        |
        |```scalascript
        |def add(a: Int, b: Int): Int = a + b
        |```
        |""".stripMargin
    val iface = extract(src)
    val add = iface.exports.find(_.name == "add")
    assert(add.isDefined, s"expected `add` in exports, got: ${iface.exports.map(_.name)}")
    assert(add.get.kind == "def")

  test("extract — top-level `val pi = 3.14` is exported with kind=val"):
    val src =
      """# Demo
        |
        |```scalascript
        |val pi = 3.14
        |```
        |""".stripMargin
    val iface = extract(src)
    val pi = iface.exports.find(_.name == "pi")
    assert(pi.isDefined, s"expected `pi` in exports, got: ${iface.exports.map(_.name)}")
    assert(pi.get.kind == "val")

  test("extract — exports carry a fully-qualified mangled name when a package is set"):
  // When `package:` is set the Parser wraps every scalascript code-block
  // body in nested `object` declarations (one per segment).  So a top-level
  // `def hello` inside `package: org.example.ui` ends up as
  // `object org { object example { object ui { def hello ... } } }`.
  // The extractor sees `org` at the top level — `hello` is nested and is
  // currently NOT visible as a top-level export.  This locks down the
  // FQN-mangling convention applied to the outermost name.
  // TODO(v2.0): tighten when the extractor recurses into the synthetic
  //             package objects to surface nested defs.
    val src =
      """---
        |package: org.example.ui
        |---
        |
        |# Demo
        |
        |```scalascript
        |def hello(): String = "hi"
        |```
        |""".stripMargin
    val iface = extract(src)
    assert(iface.pkg == List("org", "example", "ui"))
    val outerObj = iface.exports.find(_.name == "org")
    assert(outerObj.isDefined,
      s"expected synthetic outer `object org` in exports, got: ${iface.exports.map(_.name)}")
    assert(outerObj.get.fqn == "org_example_ui_org",
      s"expected mangled FQN org_example_ui_org, got: ${outerObj.get.fqn}")

  test("extract — exports use the bare name as FQN when no package is set"):
    val src =
      """# Demo
        |
        |```scalascript
        |def hello(): String = "hi"
        |```
        |""".stripMargin
    val iface = extract(src)
    val hello = iface.exports.find(_.name == "hello")
    assert(hello.isDefined)
    assert(hello.get.fqn == "hello")

  test("extract — prelude / built-in names (e.g. `println`, `serve`) are filtered out"):
    val src =
      """# Demo
        |
        |```scalascript
        |def myFn(): Unit = println("hi")
        |```
        |""".stripMargin
    val iface = extract(src)
    val names = iface.exports.map(_.name)
    assert(!names.contains("println"), s"prelude `println` should not be exported: $names")
    assert(!names.contains("print"),   s"prelude `print` should not be exported: $names")
  // The user's own def is still exported.
    assert(names.contains("myFn"))

  // ── Extern defs ────────────────────────────────────────────────────────

  test("extract — `def serve(port: Int): Unit = ???` shows up in externDefs"):
    val src =
      """# Demo
        |
        |```scalascript
        |def mkServer(port: Int): Unit = ???
        |```
        |""".stripMargin
    val iface = extract(src)
    val ex = iface.externDefs.find(_.name == "mkServer")
    assert(ex.isDefined,
      s"expected `mkServer` in externDefs, got: ${iface.externDefs.map(_.name)}")
    assert(ex.get.kind == "extern")
  // Return type annotation comes through as a string.
    assert(ex.get.tpe.contains("Unit"),
      s"expected Unit in extern signature, got: ${ex.get.tpe}")

  test("extract — non-extern (concrete-body) defs do not appear in externDefs"):
    val src =
      """# Demo
        |
        |```scalascript
        |def concrete(a: Int): Int = a + 1
        |def stub(b: Int): Int = ???
        |```
        |""".stripMargin
    val iface = extract(src)
    val externNames = iface.externDefs.map(_.name)
    assert(externNames.contains("stub"))
    assert(!externNames.contains("concrete"),
      s"`concrete` has a real body, should not be marked extern: $externNames")

  // ── Capabilities ───────────────────────────────────────────────────────

  test("extract — calling `serve(...)` registers the Http capability"):
    val src =
      """# Demo
        |
        |```scalascript
        |val app = serve(8080)
        |```
        |""".stripMargin
    val iface = extract(src)
    val caps = iface.capabilities.map(_.name).toSet
    assert(caps.contains("Http"), s"expected Http capability, got: $caps")

  test("extract — calling `connect(...)` registers the WebSocket capability"):
    val src =
      """# Demo
        |
        |```scalascript
        |val ws = connect("ws://example")
        |```
        |""".stripMargin
    val iface = extract(src)
    val caps = iface.capabilities.map(_.name).toSet
    assert(caps.contains("WebSocket"), s"expected WebSocket capability, got: $caps")

  test("extract — modules with no I/O have empty capabilities"):
    val src =
      """# Pure
        |
        |```scalascript
        |val x = 1 + 2
        |def f(n: Int): Int = n * 2
        |```
        |""".stripMargin
    val iface = extract(src)
    assert(iface.capabilities.isEmpty,
      s"expected no capabilities, got: ${iface.capabilities.map(_.name)}")

  // ── Manifest `exports:` filter ─────────────────────────────────────────

  // The InterfaceExtractor today does NOT consult `manifest.exports` to filter
  // the symbol list; it exports every top-level def found (minus prelude
  // builtins).  We lock that behaviour down so a future change becomes a
  // deliberate decision.
  test("extract — manifest `exports:` is currently NOT used to filter symbols"):
    val src =
      """---
        |name: filtered
        |exports:
        |  - keepMe
        |---
        |
        |# Demo
        |
        |```scalascript
        |def keepMe(): Int = 1
        |def alsoExported(): Int = 2
        |```
        |""".stripMargin
    val iface = extract(src)
    val names = iface.exports.map(_.name).toSet
  // TODO(v2.0): tighten when the extractor honours manifest.exports as a
  //             whitelist.  At that point this assertion will flip — only
  //             `keepMe` should appear and `alsoExported` should be filtered.
    assert(names.contains("keepMe"))
    assert(names.contains("alsoExported"),
      "current behaviour: extractor exports ALL top-level defs regardless of manifest.exports")
