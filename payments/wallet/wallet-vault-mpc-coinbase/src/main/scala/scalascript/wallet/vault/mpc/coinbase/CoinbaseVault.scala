package scalascript.wallet.vault.mpc.coinbase

import scala.concurrent.ExecutionContext
import scalascript.wallet.vault.mpc.McpVault

object CoinbaseVault:
  def apply(
    apiKey:        String,
    privateKeyPem: String,
    baseUrl:       String,
    options:       CoinbaseOptions = CoinbaseOptions.Default,
  )(using ExecutionContext): McpVault =
    McpVault(
      id     = s"coinbase:${options.portfolioId}",
      client = CoinbaseRemoteSigningClient(baseUrl, apiKey, privateKeyPem, options),
    )

class CoinbasePlugin:
  def provider: String = "coinbase"

  def vault(
    apiKey:        String,
    privateKeyPem: String,
    baseUrl:       String,
    options:       CoinbaseOptions = CoinbaseOptions.Default,
  )(using ExecutionContext): McpVault =
    CoinbaseVault(apiKey, privateKeyPem, baseUrl, options)
