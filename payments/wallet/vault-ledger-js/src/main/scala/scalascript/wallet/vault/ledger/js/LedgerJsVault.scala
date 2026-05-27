package scalascript.wallet.vault.ledger.js

import scala.concurrent.ExecutionContext
import scalascript.wallet.vault.ledger.LedgerTransport
import scalascript.wallet.vault.ledger.ethereum.LedgerEthereumVault

/** Browser Ledger vault entry point.  The Ethereum app signer is the
 *  first Vault implementation available on Scala.js; Cardano CIP-8
 *  helpers live in [[scalascript.wallet.vault.ledger.js.cardano.CardanoApp]]
 *  until wallet-spi grows a typed multi-chain hardware signer surface. */
class LedgerJsVault(
  transport: LedgerTransport,
  override val id: String = "ledger-js",
)(using ec: ExecutionContext) extends LedgerEthereumVault(transport, id)
