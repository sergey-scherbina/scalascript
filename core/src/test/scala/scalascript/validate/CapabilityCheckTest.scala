package scalascript.validate

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir
import scalascript.parser.Parser
import scalascript.transform.Normalize
import scalascript.backend.spi.*

class CapabilityCheckTest extends AnyFunSuite:

  // ── Fixture helpers ────────────────────────────────────────────────────

  /** Wrap source so it parses as a scalascript fence inside a module. */
  private def moduleOf(scalascriptSource: String): ir.NormalizedModule =
    val withFence =
      s"""# Test
         |
         |```scalascript
         |$scalascriptSource
         |```
         |""".stripMargin
    Normalize(Parser.parse(withFence))

  private def cap(features: Set[Feature]): Capabilities =
    Capabilities(
      features = features,
      outputs  = Set(OutputKind.ExecutionResult),
      options  = Set.empty,
      spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
    )

  // ── Detection ──────────────────────────────────────────────────────────

  test("detect — algebraic effects: `handle` + `perform` keywords"):
    val m = moduleOf(
      """effect Log:
        |  def info(msg: String) = __effectOp__
        |val x = handle(perform(Log.info("hi"))) { case ... => ... }""".stripMargin)
    assert(CapabilityCheck.detect(m).contains(Feature.AlgebraicEffects))

  test("detect — mutable state via `var`"):
    val m = moduleOf("var x = 0; x = x + 1")
    assert(CapabilityCheck.detect(m).contains(Feature.MutableState))

  test("detect — pattern matching"):
    val m = moduleOf("val x = 1 match { case 1 => \"one\" case _ => \"other\" }")
    assert(CapabilityCheck.detect(m).contains(Feature.PatternMatching))

  test("detect — extension methods"):
    val m = moduleOf("extension (s: String) def shout = s.toUpperCase + \"!\"")
    assert(CapabilityCheck.detect(m).contains(Feature.ExtensionMethods))

  test("detect — module imports surface in IR"):
    val src =
      """# Test
        |
        |[Foo](./other)
        |
        |```scalascript
        |val x = Foo
        |```
        |""".stripMargin
    val m = Normalize(Parser.parse(src))
    assert(CapabilityCheck.detect(m).contains(Feature.ModuleImports))

  test("detect — empty program detects no features"):
    val m = moduleOf("val x = 1")
    // Plain val and Int literal — no language or platform features triggered.
    val features = CapabilityCheck.detect(m)
    assert(features.isEmpty, s"expected empty, got: ${features.mkString(", ")}")

  // ── Validate ───────────────────────────────────────────────────────────

  test("validate — effects program against backend without AlgebraicEffects fails"):
    val m = moduleOf("val x = handle(perform(Log.info(\"hi\"))) { case ... => ... }")
    val noEffectsCap = cap(Set(Feature.MutableState, Feature.PatternMatching))
    val diags = CapabilityCheck.validate(m, noEffectsCap, "stub")
    assert(diags.exists {
      case Diagnostic.Unsupported(Feature.AlgebraicEffects, "stub") => true
      case _ => false
    }, s"expected Unsupported(AlgebraicEffects, stub), got: $diags")

  test("validate — capable backend produces zero diagnostics"):
    val m = moduleOf("val x = handle(perform(Log.info(\"hi\"))) { case ... => ... }")
    val fullCap = cap(Feature.values.toSet)
    val diags = CapabilityCheck.validate(m, fullCap, "full")
    assert(diags.isEmpty, s"expected no diagnostics, got: $diags")

  test("validate — multiple missing features produce multiple diagnostics"):
    val m = moduleOf("var x = 0; while x < 10 do x = x + 1")
    val noStateNoLoops = cap(Set(Feature.AlgebraicEffects))
    val diags = CapabilityCheck.validate(m, noStateNoLoops, "limited")
    val missingFeatures = diags.collect { case Diagnostic.Unsupported(f, _) => f }.toSet
    assert(missingFeatures.contains(Feature.MutableState))
    assert(missingFeatures.contains(Feature.WhileLoops))

  // ── Block-language axis (v1.25 Phase 3a) ───────────────────────────────

  /** Module with a `node.js` fenced block alongside a regular scalascript
   *  block — the latter so feature-detection has something to chew on too. */
  private val nodeJsModule: ir.NormalizedModule =
    val src =
      """|# Test
         |
         |```node.js
         |globalThis.add = (a, b) => a + b;
         |```
         |
         |```scalascript
         |val sum = 1 + 2
         |```
         |""".stripMargin
    Normalize(Parser.parse(src))

  test("validate — node.js block against backend without blockLanguages → UnknownBlockLanguage"):
    val noBlockLangs = cap(Set.empty) // blockLanguages defaults to Set.empty
    val diags = CapabilityCheck.validate(nodeJsModule, noBlockLangs, "js-stub")
    assert(diags.exists {
      case Diagnostic.UnknownBlockLanguage("node.js") => true
      case _ => false
    }, s"expected UnknownBlockLanguage(node.js), got: $diags")

  test("validate — node.js block against backend that declares it → no diagnostic"):
    val nodeCap = Capabilities(
      features       = Set.empty,
      outputs        = Set(OutputKind.ExecutionResult),
      options        = Set.empty,
      spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
      blockLanguages = Set("node.js", "node")
    )
    val diags = CapabilityCheck.validate(nodeJsModule, nodeCap, "node")
    assert(!diags.exists(_.isInstanceOf[Diagnostic.UnknownBlockLanguage]),
      s"expected no UnknownBlockLanguage, got: $diags")

  test("validate — string blocks (html/css/javascript) never trigger UnknownBlockLanguage"):
    val src =
      """|# Widget
         |
         |```html
         |<p>hi</p>
         |```
         |
         |```css
         |.x { color: red; }
         |```
         |
         |```javascript
         |const x = 1;
         |```
         |""".stripMargin
    val m = Normalize(Parser.parse(src))
    val noBlockLangs = cap(Set.empty)
    val diags = CapabilityCheck.validate(m, noBlockLangs, "js-stub")
    assert(!diags.exists(_.isInstanceOf[Diagnostic.UnknownBlockLanguage]),
      s"string blocks must be universally supported, got: $diags")

  test("validate — unknown inert tag (e.g. python) is ignored, not flagged"):
    val src =
      """|# Test
         |
         |```python
         |print('hello')
         |```
         |""".stripMargin
    val m = Normalize(Parser.parse(src))
    val diags = CapabilityCheck.validate(m, cap(Set.empty), "any")
    // python is not opaque-exec — it's inert prose.  No diagnostic.
    assert(!diags.exists(_.isInstanceOf[Diagnostic.UnknownBlockLanguage]),
      s"inert tags must not be flagged, got: $diags")

  test("validate — duplicate node.js blocks deduplicate to one diagnostic"):
    val src =
      """|# Test
         |
         |```node.js
         |globalThis.a = 1;
         |```
         |
         |```node.js
         |globalThis.b = 2;
         |```
         |""".stripMargin
    val m = Normalize(Parser.parse(src))
    val diags = CapabilityCheck.validate(m, cap(Set.empty), "stub")
    val unknownBlocks = diags.collect { case d: Diagnostic.UnknownBlockLanguage => d }
    assert(unknownBlocks.size == 1,
      s"expected exactly one UnknownBlockLanguage per lang tag, got: $unknownBlocks")
