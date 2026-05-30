package scalascript.wallet.vault.mpc.lit

import scala.concurrent.ExecutionContext
import scalascript.wallet.vault.mpc.McpVault

object LitVault:
  def apply(
    baseUrl:      String,
    pkpPublicKey: String,
    authSig:      String,
    options:      LitOptions = LitOptions.Default,
  )(using ExecutionContext): McpVault =
    val opts = options.copy(pkpPublicKey = pkpPublicKey, authSig = authSig)
    McpVault(
      id     = s"lit:$pkpPublicKey",
      client = LitRemoteSigningClient(baseUrl, opts),
    )

class LitPlugin:
  def provider: String = "lit"

  def vault(
    baseUrl:      String,
    pkpPublicKey: String,
    authSig:      String,
    options:      LitOptions = LitOptions.Default,
  )(using ExecutionContext): McpVault =
    LitVault(baseUrl, pkpPublicKey, authSig, options)
