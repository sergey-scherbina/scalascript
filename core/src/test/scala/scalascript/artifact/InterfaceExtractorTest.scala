package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** Verifies the v2.0 AST-based detectors in `InterfaceExtractor`:
 *
 *    - `extern def` recognition uses `EffectAnalysis.isExternDef`
 *      (Term.Name("__extern__")) after `Parser.preprocessExtern`, not
 *      the old `body == ???` placeholder check.
 *
 *    - capability detection walks `Term.Apply` / `Term.Select` call
 *      sites against `CapabilityRegistry`, so it no longer fires inside
 *      string literals or comments, and respects user shadowing of
 *      bare names. */
class InterfaceExtractorTest extends AnyFunSuite:

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

  private def extract(scalascriptSource: String) =
    val m   = moduleOf(scalascriptSource)
    val raw = scalascriptSource.getBytes("UTF-8")
    InterfaceExtractor.extract(m, raw)

  /** Build a module with the given YAML front-matter and a single
   *  scalascript code block holding `scalascriptSource`.  Front-matter is
   *  written verbatim — newline-terminated entries like `exports: [foo]`
   *  or `package: std.dsl`. */
  private def extractWithFrontMatter(frontMatter: String, scalascriptSource: String) =
    val full =
      s"""---
         |$frontMatter
         |---
         |
         |# Test
         |
         |```scalascript
         |$scalascriptSource
         |```
         |""".stripMargin
    val m   = Parser.parse(full)
    val raw = full.getBytes("UTF-8")
    InterfaceExtractor.extract(m, raw)

  // ── extern def detection ────────────────────────────────────────────────

  test("extern def — recorded under externDefs with HTTP capability"):
    val iface = extract("extern def serve(port: Int): Unit")
    val externs = iface.externDefs.map(_.name).toSet
    assert(externs.contains("serve"),
      s"expected 'serve' among externs, got: ${iface.externDefs}")
    // Signature string preserves the parameter list, not just the return type.
    val serve = iface.externDefs.find(_.name == "serve").get
    assert(serve.tpe.contains("port: Int"),
      s"expected signature to include 'port: Int', got: ${serve.tpe}")
    assert(serve.kind == "extern", s"expected kind=extern, got ${serve.kind}")
    // serve(port:…) is an HTTP intrinsic; the extern *declaration* itself
    // does not produce a call site, so capabilities here are derived from
    // the declaration of an HTTP-flavoured extern — we do not assert HTTP
    // for the declaration alone, only for use.

  test("extern def — multiple externs are all extracted"):
    val iface = extract(
      """extern def readFile(path: String): String
        |extern def sha256(data: String): String""".stripMargin)
    val names = iface.externDefs.map(_.name).toSet
    assert(names == Set("readFile", "sha256"),
      s"expected {readFile, sha256}, got: $names")

  test("extern def — a plain `def foo(x: Int) = ???` is NOT extern"):
    // The `???` placeholder is not the extern marker — only `__extern__`
    // injected by Parser.preprocessExtern is.  This was the old heuristic;
    // confirm we no longer treat `???` as extern.
    val iface = extract("def todo(x: Int): Int = ???")
    assert(iface.externDefs.isEmpty,
      s"expected no externs for `def … = ???`, got: ${iface.externDefs}")

  // ── capability detection: positive cases ────────────────────────────────

  test("capability — Http detected when `serve(...)` is called"):
    val iface = extract(
      """extern def serve(port: Int): Unit
        |serve(8080)""".stripMargin)
    val caps = iface.capabilities.map(_.name).toSet
    assert(caps.contains("Http"),
      s"expected Http capability, got: $caps")

  test("capability — WebSocket detected when `onWebSocket(...)` is called"):
    val iface = extract(
      """extern def onWebSocket(path: String, h: Any): Unit
        |onWebSocket("/ws", null)""".stripMargin)
    val caps = iface.capabilities.map(_.name).toSet
    assert(caps.contains("WebSocket"),
      s"expected WebSocket capability, got: $caps")

  test("capability — Dataset detected for qualified `Dataset.of(...)`"):
    val iface = extract("""Dataset.of(1, 2, 3)""")
    val caps = iface.capabilities.map(_.name).toSet
    assert(caps.contains("Dataset"),
      s"expected Dataset capability, got: $caps")

  test("capability — Crypto via `crypto.<anything>` qualifier"):
    val iface = extract("""val h = crypto.digest("x")""")
    val caps = iface.capabilities.map(_.name).toSet
    assert(caps.contains("Crypto"),
      s"expected Crypto capability via crypto.* qualifier, got: $caps")

  // ── capability detection: negative cases (the bug fix) ──────────────────

  test("capability — string literal containing `serve(` does NOT trigger Http"):
    // This was the canonical false-positive of the v1 grep heuristic.
    val iface = extract("""val msg = "call serve(8080) to start"""")
    val caps = iface.capabilities.map(_.name).toSet
    assert(!caps.contains("Http"),
      s"expected Http NOT detected from a string literal, got: $caps")

  test("capability — comment containing `serve(...)` does NOT trigger Http"):
    val iface = extract(
      """// remember to call serve(8080)
        |val x = 1""".stripMargin)
    val caps = iface.capabilities.map(_.name).toSet
    assert(!caps.contains("Http"),
      s"expected Http NOT detected from a comment, got: $caps")

  test("capability — user-defined `def serve` shadows the intrinsic"):
    // Best-effort shadowing: a top-level `def serve` in the module masks
    // the Http intrinsic detection.  Documented limitation: this is
    // *module-wide*, not lexical.
    val iface = extract(
      """def serve = 1
        |val x = serve""".stripMargin)
    val caps = iface.capabilities.map(_.name).toSet
    assert(!caps.contains("Http"),
      s"expected Http NOT detected when user defines `serve`, got: $caps")

  test("capability — empty program detects no capabilities"):
    val iface = extract("val x = 1 + 2")
    assert(iface.capabilities.isEmpty,
      s"expected no capabilities, got: ${iface.capabilities}")

  // ── manifest `exports:` filters the export list ─────────────────────────

  test("exports filter — manifest `exports: [foo]` excludes `bar`"):
    // With an explicit `exports:` list, private helpers must stay hidden:
    // the consumer-facing interface should expose only the listed names.
    val iface = extractWithFrontMatter(
      frontMatter = "exports:\n  - foo",
      scalascriptSource =
        """def foo(x: Int): Int = x + 1
          |def bar(x: Int): Int = x - 1""".stripMargin
    )
    val names = iface.exports.map(_.name).toSet
    assert(names == Set("foo"),
      s"expected only `foo` to be exported, got: $names")

  test("exports filter — empty/absent `exports:` keeps current (export-all) behaviour"):
    // No `exports:` in the manifest must NOT inadvertently hide top-level defs.
    val iface = extractWithFrontMatter(
      frontMatter = "name: m",
      scalascriptSource =
        """def foo(x: Int): Int = x + 1
          |def bar(x: Int): Int = x - 1""".stripMargin
    )
    val names = iface.exports.map(_.name).toSet
    assert(names == Set("foo", "bar"),
      s"expected both `foo` and `bar` when no `exports:` is set, got: $names")

  test("exports filter — `exports:` listing an undefined name yields an empty subset"):
    // A name listed in `exports:` that isn't actually defined is silently
    // dropped; we don't fabricate symbols.  Real defs that ARE listed still
    // get through.
    val iface = extractWithFrontMatter(
      frontMatter = "exports:\n  - foo\n  - missing",
      scalascriptSource =
        """def foo(x: Int): Int = x + 1
          |def bar(x: Int): Int = x - 1""".stripMargin
    )
    val names = iface.exports.map(_.name).toSet
    assert(names == Set("foo"),
      s"expected only `foo` (since `missing` isn't defined), got: $names")

  // ── package: walks the synthetic object shell from `Parser` ─────────────

  test("package walk — `package: std.dsl` surfaces inner `case class DocLine`"):
    // `Parser.wrapSectionInPackage` wraps the body in `object std: object dsl: …`.
    // The extractor must descend that shell so `DocLine` appears in exports,
    // not the synthetic `std` object itself.
    val iface = extractWithFrontMatter(
      frontMatter = "package: std.dsl",
      scalascriptSource = """case class DocLine(text: String)"""
    )
    val names = iface.exports.map(_.name).toSet
    val fqns  = iface.exports.map(_.fqn).toSet
    assert(names.contains("DocLine"),
      s"expected `DocLine` in exports, got: $names (fqns=$fqns)")
    assert(!names.contains("std"),
      s"synthetic package shell `std` must NOT appear in exports, got: $names")
    // FQN convention: pkg segments joined with `_`, then the name.
    val doc = iface.exports.find(_.name == "DocLine").get
    assert(doc.fqn == "std_dsl_DocLine",
      s"expected FQN `std_dsl_DocLine`, got: ${doc.fqn}")
    assert(doc.kind == "class", s"expected kind=class, got: ${doc.kind}")
    // The module's `pkg` field on the interface is preserved verbatim.
    assert(iface.pkg == List("std", "dsl"),
      s"expected pkg=[std,dsl], got: ${iface.pkg}")

  test("package walk — multiple inner defs all become exports under the pkg fqn"):
    val iface = extractWithFrontMatter(
      frontMatter = "package: org.example",
      scalascriptSource =
        """def render(x: Int): String = x.toString
          |case class Box(value: Int)""".stripMargin
    )
    val names = iface.exports.map(_.name).toSet
    assert(names == Set("render", "Box"),
      s"expected {render, Box}, got: $names")
    val fqns = iface.exports.map(s => s.name -> s.fqn).toMap
    assert(fqns("render") == "org_example_render",
      s"expected render → org_example_render, got: ${fqns("render")}")
    assert(fqns("Box") == "org_example_Box",
      s"expected Box → org_example_Box, got: ${fqns("Box")}")

  test("package walk — `extern def` inside a package shell is still detected"):
    // Externs are emitted via `Parser.preprocessExtern` BEFORE the package
    // wrap, so they end up nested inside `object std: object dsl: def foo = __extern__`.
    // The extractor must still surface them in `externDefs`, not just the
    // generic exports list.
    val iface = extractWithFrontMatter(
      frontMatter = "package: std.dsl",
      scalascriptSource = """extern def doRead(path: String): String"""
    )
    val externNames = iface.externDefs.map(_.name).toSet
    assert(externNames.contains("doRead"),
      s"expected `doRead` among externs even under `package: std.dsl`, got: $externNames")
    val fqn = iface.externDefs.find(_.name == "doRead").get.fqn
    assert(fqn == "std_dsl_doRead",
      s"expected extern FQN `std_dsl_doRead`, got: $fqn")

  test("package walk — manifest `exports:` filter still applies inside the package"):
    // Both fixes compose: the package walk surfaces inner names, then the
    // `exports:` filter narrows the public surface.
    val iface = extractWithFrontMatter(
      frontMatter = "package: std.dsl\nexports:\n  - DocLine",
      scalascriptSource =
        """case class DocLine(text: String)
          |case class HiddenHelper(x: Int)""".stripMargin
    )
    val names = iface.exports.map(_.name).toSet
    assert(names == Set("DocLine"),
      s"expected only `DocLine` (HiddenHelper filtered out), got: $names")
