package scalascript.solana

/** Connection config for a Solana JSON-RPC endpoint. `commitment` is the default finality level
 *  (`processed` / `confirmed` / `finalized`) attached to read calls. */
case class SolanaConfig(rpcUrl: String, commitment: String = "confirmed")

/** Well-known Solana clusters. */
object SolanaNetworks:
  val MainnetBeta = SolanaConfig("https://api.mainnet-beta.solana.com")
  val Devnet      = SolanaConfig("https://api.devnet.solana.com")
  val Testnet     = SolanaConfig("https://api.testnet.solana.com")

/** A confirmed-transaction summary (subset of `getTransaction`). */
case class SolanaTxStatus(slot: Long, err: Option[ujson.Value], confirmations: Option[Long])

case class SolanaRpcError(code: Int, message: String)
  extends Exception(s"Solana RPC error $code: $message")
