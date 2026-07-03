package scalascript.compiler.plugin.auth

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Interpreter-only plugin: Auth intrinsics (CSRF, WebAuthn, TOTP, JWT, etc.).
 *  Registration: META-INF/services/scalascript.backend.spi.Backend */
class AuthInterpreterPlugin extends Backend:
  def id:          String = "scalascript-auth-interpreter"
  def displayName: String = "Auth Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = AuthIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("AuthInterpreterPlugin does not compile — intrinsic provider only")))
