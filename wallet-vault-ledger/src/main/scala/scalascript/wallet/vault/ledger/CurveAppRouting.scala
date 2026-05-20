package scalascript.wallet.vault.ledger

import scalascript.blockchain.spi.ChainId
import scalascript.crypto.Curve

/** Maps `(curve, chain-id-namespace)` pairs to the Ledger on-device
 *  app name that must be open to service the request. The Vault
 *  layer reads the active app name via the dashboard's `getAppName`
 *  APDU (`B0 01 00 00`) and, if mismatched, raises
 *  [[AppSwitchRequired]].
 *
 *  Only canonical mainnet apps are listed here — Ledger ships one
 *  "Ethereum" app that handles every EVM chain id, one "Solana"
 *  app, etc. The reference column corresponds to CAIP-2 namespaces
 *  defined in [[scalascript.blockchain.spi.ChainId]].
 *
 *  See docs/wallet-spi.md §5.1 (table at the bottom). */
object CurveAppRouting:

  /** Canonical on-device app names (case-sensitive — matches what
   *  the Ledger dashboard returns via `getAppName`). */
  val EthereumApp: String = "Ethereum"
  val SolanaApp:   String = "Solana"
  val BitcoinApp:  String = "Bitcoin"
  val CardanoApp:  String = "Cardano ADA"

  /** Resolve the required app from `(curve, namespace)`. Returns
   *  `None` if the pair is not supported by any Ledger app this
   *  routing table knows about — callers should treat that as
   *  "unsupported curve / chain on hardware vault". */
  def appFor(curve: Curve, namespace: String): Option[String] =
    (curve, namespace) match
      case (Curve.Secp256k1, "eip155") => Some(EthereumApp)
      case (Curve.Secp256k1, "bip122") => Some(BitcoinApp)
      case (Curve.Ed25519,   "solana") => Some(SolanaApp)
      case (Curve.Ed25519,   "cardano") => Some(CardanoApp)
      case _                            => None

  /** Convenience wrapper that takes a full [[ChainId]]. */
  def appFor(curve: Curve, chain: ChainId): Option[String] =
    appFor(curve, chain.namespace)

  /** Pure derivation-path heuristic for routing without a known
   *  [[ChainId]] in hand — useful for cases where the caller has
   *  only the BIP-44 path (e.g. `m/44'/60'/0'/0/0` → Ethereum,
   *  `m/44'/501'/0'/0'` → Solana). Returns `None` when the path
   *  doesn't follow BIP-44 or its coin-type is not in the table. */
  def appForPath(curve: Curve, derivationPath: String): Option[String] =
    val parts = derivationPath
      .stripPrefix("m/")
      .split('/')
      .map(_.stripSuffix("'"))
      .toList
    parts match
      case "44" :: ct :: _ =>
        (curve, ct) match
          case (Curve.Secp256k1, "60")  => Some(EthereumApp)
          case (Curve.Secp256k1, "0")   => Some(BitcoinApp)
          case (Curve.Ed25519,   "501") => Some(SolanaApp)
          case _                         => None
      case "1852" :: _ if curve == Curve.Ed25519 => Some(CardanoApp)
      case _                                      => None
