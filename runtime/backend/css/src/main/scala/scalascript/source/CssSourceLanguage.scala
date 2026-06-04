package scalascript.source

import scalascript.backend.spi.*
import scalascript.ir

/** SourceLanguage plugin for `css` fence blocks (specs/backend-spi.md §9).
 *
 *  Stage 9+/C.1 skeleton — sibling of `HtmlSourceLanguage`.  Claims
 *  the `css` fence tag via ServiceLoader; `compileBlock` currently
 *  passes the source through verbatim as an `EmbeddedBlock`.
 *
 *  Stage 9+/C.2-C.3 follow-ups (multi-session):
 *
 *    - 9+/C.2: take ownership of the `css"…"` interpolator (today
 *      hardcoded in JvmGen / JsGen / Interpreter).
 *    - 9+/C.3: ship `Css` case class + escape helpers through
 *      `preludeFiles`; delete the parallel hardcoded paths from the
 *      codegens. */
class CssSourceLanguage extends SourceLanguage:
  def id:            String = "css-source"
  def displayName:   String = "CSS (string + css\"…\" interpolator)"
  def spiVersion:    String = SpiVersion.Current
  def canonicalName: String = "css"

  def signatures(source: String, scope: ScopeContext): List[SymbolExport] = Nil

  def compileBlock(source: String, scope: ScopeContext, opts: BackendOptions): BlockArtifact =
    BlockArtifact(
      fragment    = ir.Content.EmbeddedBlock(language = "css", source = source),
      diagnostics = Nil
    )
