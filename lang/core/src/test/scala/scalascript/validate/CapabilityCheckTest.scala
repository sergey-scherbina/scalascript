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

  test("detect — std.nfc import requires NfcNdef"):
    val src =
      """# NFC
        |
        |[nfcCapabilities](std/nfc.ssc)
        |
        |```scalascript
        |val caps = nfcCapabilities()
        |```
        |""".stripMargin
    val m = Normalize(Parser.parse(src))
    val features = CapabilityCheck.detect(m)
    assert(features.contains(Feature.ModuleImports))
    assert(features.contains(Feature.NfcNdef))

  test("validate — std.nfc against backend without NfcNdef fails"):
    val src =
      """# NFC
        |
        |[nfcCapabilities](std/nfc.ssc)
        |
        |```scalascript
        |val caps = nfcCapabilities()
        |```
        |""".stripMargin
    val m = Normalize(Parser.parse(src))
    val diags = CapabilityCheck.validate(m, cap(Set(Feature.ModuleImports)), "stub")
    assert(diags.exists {
      case Diagnostic.Unsupported(Feature.NfcNdef, "stub") => true
      case _ => false
    }, s"expected Unsupported(NfcNdef, stub), got: $diags")

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

  // NOTE: the interim "effect `perform` inside a `while` loop" honesty-diagnostic
  // tests were removed 2026-06-12 — `effect-cps-loops-{jvm,js}` landed the real
  // lowering, so the gate (and these tests) are obsolete. The real lowering is
  // exercised by JvmGenEffectsRuntimeTest (scala-cli) and JsEffectLoopTest (node).

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

  // ── v1.26 — sql blocks gate on the `sql` block-language capability ──────

  private val sqlModule: ir.NormalizedModule =
    val src =
      """|# Queries
         |
         |```sql
         |SELECT id, name FROM users WHERE id = ${userId}
         |```
         |""".stripMargin
    Normalize(Parser.parse(src))

  test("validate — sql block against backend without blockLanguages → UnknownBlockLanguage"):
    val noBlockLangs = cap(Set.empty)
    val diags = CapabilityCheck.validate(sqlModule, noBlockLangs, "js-stub")
    assert(diags.exists {
      case Diagnostic.UnknownBlockLanguage("sql") => true
      case _ => false
    }, s"expected UnknownBlockLanguage(sql), got: $diags")

  test("validate — sql block against backend that declares it → no diagnostic"):
    val jvmCap = Capabilities(
      features       = Set.empty,
      outputs        = Set(OutputKind.ExecutionResult),
      options        = Set.empty,
      spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
      blockLanguages = Set("sql")
    )
    val diags = CapabilityCheck.validate(sqlModule, jvmCap, "jvm")
    assert(!diags.exists(_.isInstanceOf[Diagnostic.UnknownBlockLanguage]),
      s"expected no UnknownBlockLanguage when sql is declared, got: $diags")

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

  // ── v1.27 Phase 6 — UnsupportedJdbcUrl on JS-family targets ───────

  /** A JS-family target that declares sql but emits JavaScript / Wasm
   *  output — matches the JsCapabilities / NodeCapabilities /
   *  WasmCapabilities shape `unsupportedJdbcUrls` checks for. */
  private val jsFamilyCap = Capabilities(
    features       = Set.empty,
    outputs        = Set(OutputKind.JavaScriptSource),
    options        = Set.empty,
    spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
    blockLanguages = Set("sql"),
  )

  /** A JVM-family target.  Also declares sql, but accepts jdbc:
   *  URLs natively — no UnsupportedJdbcUrl ever fires. */
  private val jvmFamilyCap = Capabilities(
    features       = Set.empty,
    outputs        = Set(OutputKind.JvmBytecode, OutputKind.ExecutionResult),
    options        = Set.empty,
    spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
    blockLanguages = Set("sql"),
  )

  private def sqlModuleWithDatabase(url: String, dbName: String = "default"): ir.NormalizedModule =
    val src =
      s"""|---
          |databases:
          |  $dbName: { url: "$url" }
          |---
          |# Q
          |
          |```sql
          |SELECT 1
          |```
          |""".stripMargin
    Normalize(Parser.parse(src))

  test("validate — jdbc: URL on JS-family target → UnsupportedJdbcUrl diagnostic"):
    val m     = sqlModuleWithDatabase("jdbc:postgresql://localhost:5432/x")
    val diags = CapabilityCheck.validate(m, jsFamilyCap, "node")
    val jdbc  = diags.collect { case d: Diagnostic.UnsupportedJdbcUrl => d }
    assert(jdbc.size == 1, s"expected one UnsupportedJdbcUrl, got: $diags")
    assert(jdbc.head.db      == "default")
    assert(jdbc.head.url     == "jdbc:postgresql://localhost:5432/x")
    assert(jdbc.head.backend == "node")

  test("validate — sqlite: URL on JS-family target → no UnsupportedJdbcUrl"):
    val m     = sqlModuleWithDatabase("sqlite::memory:")
    val diags = CapabilityCheck.validate(m, jsFamilyCap, "node")
    assert(!diags.exists(_.isInstanceOf[Diagnostic.UnsupportedJdbcUrl]),
      s"expected no UnsupportedJdbcUrl, got: $diags")

  test("validate — duckdb: URL on JS-family target → no UnsupportedJdbcUrl"):
    val m     = sqlModuleWithDatabase("duckdb:")
    val diags = CapabilityCheck.validate(m, jsFamilyCap, "wasm")
    assert(!diags.exists(_.isInstanceOf[Diagnostic.UnsupportedJdbcUrl]),
      s"expected no UnsupportedJdbcUrl, got: $diags")

  test("validate — jdbc: URL on JVM target → no UnsupportedJdbcUrl (JVM accepts JDBC natively)"):
    val m     = sqlModuleWithDatabase("jdbc:h2:mem:x")
    val diags = CapabilityCheck.validate(m, jvmFamilyCap, "jvm")
    assert(!diags.exists(_.isInstanceOf[Diagnostic.UnsupportedJdbcUrl]),
      s"expected no UnsupportedJdbcUrl on JVM target, got: $diags")

  test("validate — multiple jdbc: entries → one diagnostic per offending db"):
    val src =
      """|---
         |databases:
         |  primary:   { url: "jdbc:postgresql://primary/x" }
         |  secondary: { url: "jdbc:mysql://secondary/y" }
         |  cache:     { url: "sqlite::memory:" }
         |---
         |# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin
    val m     = Normalize(Parser.parse(src))
    val diags = CapabilityCheck.validate(m, jsFamilyCap, "js")
    val jdbc  = diags.collect { case d: Diagnostic.UnsupportedJdbcUrl => d }
    assert(jdbc.map(_.db).toSet == Set("primary", "secondary"),
      s"expected diagnostics for primary + secondary only, got: $jdbc")

  test("validate — Wasm target (WasmBytecode output) treated as JS-family for JDBC gating"):
    val wasmCap = jsFamilyCap.copy(outputs = Set(OutputKind.WasmBytecode))
    val m       = sqlModuleWithDatabase("jdbc:h2:mem:x")
    val diags   = CapabilityCheck.validate(m, wasmCap, "wasm")
    assert(diags.exists(_.isInstanceOf[Diagnostic.UnsupportedJdbcUrl]),
      s"Wasm output kind should trigger jdbc: gating, got: $diags")

  // ── v1.30 — @side=client validation ─────────────────────────────────────

  private def moduleWithClientSideSql(dbUrl: String, side: String = "client"): ir.NormalizedModule =
    val src =
      s"""|---
          |databases:
          |  local:
          |    url: "$dbUrl"
          |---
          |# Q
          |
          |```sql @db=local @side=$side
          |SELECT 1
          |```
          |""".stripMargin
    Normalize(Parser.parse(src))

  test("validate — @side=client with sqlite: → no UnsupportedClientSideDbUrl"):
    val m     = moduleWithClientSideSql("sqlite::memory:")
    val diags = CapabilityCheck.validate(m, jvmFamilyCap, "jvm")
    assert(!diags.exists(_.isInstanceOf[Diagnostic.UnsupportedClientSideDbUrl]),
      s"sqlite: should be allowed on @side=client, got: $diags")

  test("validate — @side=client with sqlite-opfs: → no UnsupportedClientSideDbUrl"):
    val m     = moduleWithClientSideSql("sqlite-opfs:./app.db")
    val diags = CapabilityCheck.validate(m, jvmFamilyCap, "jvm")
    assert(!diags.exists(_.isInstanceOf[Diagnostic.UnsupportedClientSideDbUrl]),
      s"sqlite-opfs: should be allowed on @side=client, got: $diags")

  test("validate — @side=client with postgres: → UnsupportedClientSideDbUrl"):
    val m     = moduleWithClientSideSql("postgres://localhost/db")
    val diags = CapabilityCheck.validate(m, jvmFamilyCap, "jvm")
    val bad   = diags.collect { case d: Diagnostic.UnsupportedClientSideDbUrl => d }
    assert(bad.size == 1, s"expected one UnsupportedClientSideDbUrl, got: $diags")
    assert(bad.head.db == "local")
    assert(bad.head.url == "postgres://localhost/db")

  test("validate — @side=client with h2: → UnsupportedClientSideDbUrl"):
    val m     = moduleWithClientSideSql("h2:mem:test")
    val diags = CapabilityCheck.validate(m, jvmFamilyCap, "jvm")
    assert(diags.exists(_.isInstanceOf[Diagnostic.UnsupportedClientSideDbUrl]),
      s"h2: should not be allowed on @side=client, got: $diags")

  test("validate — @side=server (default) with postgres: → no UnsupportedClientSideDbUrl"):
    val m     = moduleWithClientSideSql("postgres://localhost/db", side = "server")
    val diags = CapabilityCheck.validate(m, jvmFamilyCap, "jvm")
    assert(!diags.exists(_.isInstanceOf[Diagnostic.UnsupportedClientSideDbUrl]),
      s"@side=server should not trigger client-side URL check, got: $diags")

  test("validate — target without sql in blockLanguages → no UnsupportedJdbcUrl (orthogonal)"):
    // A backend that doesn't declare sql at all — UnknownBlockLanguage
    // fires for the sql fence; UnsupportedJdbcUrl is irrelevant.
    val m     = sqlModuleWithDatabase("jdbc:h2:mem:x")
    val diags = CapabilityCheck.validate(m, jsFamilyCap.copy(blockLanguages = Set.empty), "js")
    assert(!diags.exists(_.isInstanceOf[Diagnostic.UnsupportedJdbcUrl]),
      s"target without sql shouldn't emit jdbc-url diag, got: $diags")

  // ── v1.55.3 — Feature.Markup gating ─────────────────────────────────────

  /** Module with an xml"..." interpolator in a scalascript fence. */
  private def xmlInterpolatorModule: ir.NormalizedModule =
    val src =
      """|# Test
         |
         |```scalascript
         |val doc = xml"<root/>"
         |```
         |""".stripMargin
    Normalize(Parser.parse(src))

  /** Module with a fenced xml block. */
  private def xmlFencedModule: ir.NormalizedModule =
    val src =
      """|# Test
         |
         |```xml
         |<root><child/></root>
         |```
         |""".stripMargin
    Normalize(Parser.parse(src))

  private val markupCapable: Capabilities = Capabilities(
    features       = Set(Feature.Markup),
    outputs        = Set(OutputKind.ExecutionResult),
    options        = Set.empty,
    spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
    blockLanguages = Set(scalascript.ast.Lang.Xml)
  )

  private val markupIncapable: Capabilities = Capabilities(
    features       = Set.empty,
    outputs        = Set(OutputKind.ExecutionResult),
    options        = Set.empty,
    spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
  )

  test("detect — xml\"...\" interpolator detects Feature.Markup"):
    val m = xmlInterpolatorModule
    assert(CapabilityCheck.detect(m).contains(Feature.Markup),
      s"xml interpolator should detect Feature.Markup, got: ${CapabilityCheck.detect(m)}")

  test("detect — fenced xml block detects Feature.Markup"):
    val m = xmlFencedModule
    assert(CapabilityCheck.detect(m).contains(Feature.Markup),
      s"fenced xml block should detect Feature.Markup, got: ${CapabilityCheck.detect(m)}")

  test("validate — xml\"...\" on markup-capable backend passes (no diagnostics)"):
    val m     = xmlInterpolatorModule
    val diags = CapabilityCheck.validate(m, markupCapable, "jvm")
    assert(!diags.exists { case Diagnostic.Unsupported(Feature.Markup, _) => true; case _ => false },
      s"xml interpolator should pass on markup-capable backend, got: $diags")

  test("validate — xml\"...\" on markup-incapable backend rejected with Unsupported(Markup)"):
    val m     = xmlInterpolatorModule
    val diags = CapabilityCheck.validate(m, markupIncapable, "js")
    assert(diags.exists {
      case Diagnostic.Unsupported(Feature.Markup, "js") => true
      case _ => false
    }, s"expected Unsupported(Markup, js) for xml interpolator on incapable backend, got: $diags")

  // ── v1.56 — Feature.Xslt gating ─────────────────────────────────────────

  /** Module that calls `.transform(` on a Markup.Doc value. */
  private def xsltTransformModule: ir.NormalizedModule =
    val src =
      """|# Test
         |
         |```scalascript
         |val result = doc.transform(xslt)
         |```
         |""".stripMargin
    Normalize(Parser.parse(src))

  private val xsltCapable: Capabilities = Capabilities(
    features       = Set(Feature.Markup, Feature.Xslt),
    outputs        = Set(OutputKind.ExecutionResult),
    options        = Set.empty,
    spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
    blockLanguages = Set(scalascript.ast.Lang.Xml)
  )

  private val xsltIncapable: Capabilities = Capabilities(
    features       = Set(Feature.Markup),   // has Markup but not Xslt
    outputs        = Set(OutputKind.ExecutionResult),
    options        = Set.empty,
    spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
  )

  test("detect — .transform( call detects Feature.Xslt"):
    val m = xsltTransformModule
    assert(CapabilityCheck.detect(m).contains(Feature.Xslt),
      s".transform( call should detect Feature.Xslt, got: ${CapabilityCheck.detect(m)}")

  test("validate — .transform( on xslt-capable backend passes (no diagnostics)"):
    val m     = xsltTransformModule
    val diags = CapabilityCheck.validate(m, xsltCapable, "jvm")
    assert(!diags.exists { case Diagnostic.Unsupported(Feature.Xslt, _) => true; case _ => false },
      s".transform( should pass on xslt-capable backend, got: $diags")

  test("validate — .transform( on xslt-incapable backend rejected with Unsupported(Xslt)"):
    val m     = xsltTransformModule
    val diags = CapabilityCheck.validate(m, xsltIncapable, "js")
    assert(diags.exists {
      case Diagnostic.Unsupported(Feature.Xslt, "js") => true
      case _ => false
    }, s"expected Unsupported(Xslt, js) for .transform( on incapable backend, got: $diags")
