package scalascript.wallet.vault.mpc.zengo

import scala.concurrent.ExecutionContext
import scalascript.wallet.vault.mpc.McpVault

object ZenGoVault:
  def apply(
    apiKey:    String,
    secretKey: String,
    baseUrl:   String,
    options:   ZenGoOptions = ZenGoOptions.Default,
  )(using ExecutionContext): McpVault =
    McpVault(
      id     = s"zengo:$apiKey",
      client = ZenGoRemoteSigningClient(baseUrl, apiKey, secretKey, options),
    )

class ZenGoPlugin:
  def provider: String = "zengo"

  def vault(
    apiKey:    String,
    secretKey: String,
    baseUrl:   String,
    options:   ZenGoOptions = ZenGoOptions.Default,
  )(using ExecutionContext): McpVault =
    ZenGoVault(apiKey, secretKey, baseUrl, options)
