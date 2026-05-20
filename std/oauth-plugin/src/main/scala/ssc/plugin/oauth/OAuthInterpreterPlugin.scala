package ssc.plugin.oauth

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

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

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("OAuthInterpreterPlugin does not compile — intrinsic provider only")))
