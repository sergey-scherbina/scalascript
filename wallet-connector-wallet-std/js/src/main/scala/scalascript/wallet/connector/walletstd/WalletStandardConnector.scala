package scalascript.wallet.connector.walletstd

import scala.concurrent.ExecutionContext
import scalascript.blockchain.spi.{ChainAdapter, ChainContext, ChainId}
import scalascript.wallet.spi.AccountManager

/** Scala.js-side concrete connector.  Until a Scala.js Solana
 *  `ChainAdapter` ships (no JS impl yet at Stage 3), the Solana-tx
 *  bridge methods escalate to `UnsupportedOperationException` —
 *  `solana:signMessage` (which doesn't need the tx wrapper) still works.
 *  Wallet Standard `standard:connect` / `standard:disconnect` /
 *  `wallet:setActiveChain` are unaffected.
 *
 *  Once a Solana JS adapter lands the bridge here will translate the
 *  shared [[SolanaMessage]] to whatever Tx type that adapter declares,
 *  matching the JVM bridge. */
class WalletStandardConnector(
  manager:      AccountManager,
  ctxFor:       ChainId => ChainContext,
  initialChain: ChainId,
)(using ec: ExecutionContext)
    extends WalletStandardConnectorBase(manager, ctxFor, initialChain):

  protected def buildSolanaTx(adapter: ChainAdapter, msg: SolanaMessage): Any =
    val _ = (adapter, msg)
    throw new UnsupportedOperationException(
      "Solana ChainAdapter not yet available on Scala.js — " +
        "`solana:signTransaction`/`signAndSendTransaction` need it.  " +
        "Use `solana:signMessage` for now; full support lands once a " +
        "Scala.js Solana chain adapter is in place.",
    )

  protected def extractSignedRawBase64(signed: Any): String =
    val _ = signed
    throw new UnsupportedOperationException(
      "Scala.js Wallet Standard connector cannot extract a signed Solana " +
        "transaction (no JS Solana adapter yet).",
    )

/** Companion for JS-side ABI parity with the JVM `WalletStandardConnector`
 *  object.  Re-exports the error-types of the shared base. */
object WalletStandardConnector:
  type WsError = WalletStandardConnectorBase.WsError
  val  WsError = WalletStandardConnectorBase.WsError
