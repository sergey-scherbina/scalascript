package scalascript.source

import scalascript.backend.spi.*
import scalascript.ir

/** SourceLanguage plugin for `html` fence blocks (docs/backend-spi.md §9).
 *
 *  Stage 9+/B.1 skeleton.  Claims the `html` fence tag via
 *  ServiceLoader so `Normalize` routes html blocks through this
 *  plugin instead of the EmbeddedBlock fallback.  `compileBlock`
 *  currently passes the source through verbatim — the plugin
 *  produces the same `EmbeddedBlock` shape Normalize would have
 *  emitted, so existing programs are unaffected.
 *
 *  Stage 9+/B.2-B.4 follow-ups (multi-session):
 *
 *    - 9+/B.2: take ownership of the `html"…"` interpolator —
 *      hardcoded in JvmGen / JsGen / Interpreter today.
 *    - 9+/B.3: provide the `Html` type + `div` / `p` / `body` / …
 *      tag bindings through `preludeFiles` so user blocks compile
 *      against plugin-supplied symbols instead of `nativeP("div")` /
 *      synthesised DSL emission.
 *    - 9+/B.4: codegen-side delete-down — `containerTagNames`,
 *      `voidTagNames`, `_Raw` emission paths in JvmGen / JsGen, plus
 *      the `nativeP("div")` block in Interpreter, all migrate into
 *      the plugin. */
class HtmlSourceLanguage extends SourceLanguage:
  def id:            String = "html-source"
  def displayName:   String = "HTML (DSL + html\"…\" interpolator)"
  def spiVersion:    String = SpiVersion.Current
  def canonicalName: String = "html"

  /** Stage 9+/B.1 stub — no cross-block exports.  Stage 9+/B.3 will
   *  surface the prelude's `Html` / `Css`-related top-level symbols
   *  through `signatures` so other foreign-language blocks can
   *  reference them via §10 cross-block typing. */
  def signatures(source: String, scope: ScopeContext): List[SymbolExport] = Nil

  /** Stage 9+/B.1: passthrough.  Stage 9+/B.2 parses `${expr}`
   *  splices and turns them into IR fragments that produce the
   *  user-facing `Html` value the codegens consume. */
  def compileBlock(source: String, scope: ScopeContext, opts: BackendOptions): BlockArtifact =
    BlockArtifact(
      fragment    = ir.Content.EmbeddedBlock(language = "html", source = source),
      diagnostics = Nil
    )
