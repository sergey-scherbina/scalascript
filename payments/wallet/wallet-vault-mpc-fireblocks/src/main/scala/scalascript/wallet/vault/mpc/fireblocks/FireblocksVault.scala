package scalascript.wallet.vault.mpc.fireblocks

import scala.concurrent.ExecutionContext
import scalascript.wallet.vault.mpc.McpVault

object FireblocksVault:
  def apply(
    apiKey:        String,
    privateKeyPem: String,
    baseUrl:       String,
    options:       FireblocksOptions = FireblocksOptions.Default,
  )(using ExecutionContext): McpVault =
    McpVault(
      id     = s"fireblocks:${options.vaultAccountId}",
      client = FireblocksRemoteSigningClient(baseUrl, apiKey, privateKeyPem, options),
    )

class FireblocksPlugin:
  def provider: String = "fireblocks"

  def vault(
    apiKey:        String,
    privateKeyPem: String,
    baseUrl:       String,
    options:       FireblocksOptions = FireblocksOptions.Default,
  )(using ExecutionContext): McpVault =
    FireblocksVault(apiKey, privateKeyPem, baseUrl, options)
