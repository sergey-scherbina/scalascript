package scalascript.compiler.plugin.oauth

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule, ExportedSymbol}

/** Interpreter-only plugin: OAuth AS, OAuth Client, and OIDC intrinsics.
 *  Registration: META-INF/services/scalascript.backend.spi.Backend */
class OAuthInterpreterPlugin extends Backend:
  def id:          String = "scalascript-oauth-interpreter"
  def displayName: String = "OAuth + OIDC Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics: Map[QualifiedName, IntrinsicImpl] =
    OAuthIntrinsics.table ++ OAuthClientIntrinsics.table

  def acceptedSources: Set[String] = Set.empty

  // core-min check-autoload: a file importing `scalascript.oauth.*` / `scalascript.oidc.*` auto-loads
  // these preludeSymbols in `ssc check`, even though oauth is advanced/opt-in.
  override def providesImports: List[String] = List("scalascript.oauth", "scalascript.oidc")

  /** core-min-advanced-optin: this plugin DECLARES its prelude name(s) for `ssc check`,
   *  removed from the hardcoded Typer `pluginObjects`/`pluginBuiltins`. For an ADVANCED (opt-in)
   *  plugin these resolve only when the plugin is added (`--plugin`); for an essential plugin they
   *  always resolve (auto-loaded). The names are typer-prelude declarations only. */
  override def preludeSymbols: List[ExportedSymbol] = List(
    ExportedSymbol("oauth", "oauth", "object", "Any"),
    ExportedSymbol("oidc", "oidc", "object", "Any"),
  )

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("OAuthInterpreterPlugin does not compile — intrinsic provider only")))
