package scalascript.wallet.connector.walletstd

import scala.concurrent.ExecutionContext
import scalascript.blockchain.solana as bs
import scalascript.blockchain.spi.{ChainAdapter, ChainContext, ChainId}
import scalascript.wallet.spi.AccountManager

/** JVM-side concrete connector that bridges shared [[SolanaMessage]] to
 *  the real `scalascript.blockchain.solana.SolanaTx` / `SolanaSignedTx`
 *  types `SolanaChainAdapter` expects.  Translating between the
 *  connector-local and `blockchain-solana` message records is purely
 *  field-for-field.
 *
 *  This is the class JVM consumers (mcp-wallet, x402-client, gRPC
 *  bridges …) instantiate directly: `new WalletStandardConnector(...)`.
 *  Cross-compiled code that does not need to materialise the platform-
 *  specific tx wrapper can use [[WalletStandardConnectorBase]]. */
class WalletStandardConnector(
  manager:      AccountManager,
  ctxFor:       ChainId => ChainContext,
  initialChain: ChainId,
)(using ec: ExecutionContext)
    extends WalletStandardConnectorBase(manager, ctxFor, initialChain):

  protected def buildSolanaTx(adapter: ChainAdapter, msg: SolanaMessage): Any =
    val _ = adapter   // shape-typed via `adapter.Tx`; the actual JVM Solana adapter
                      // narrows it to `bs.SolanaTx` at the use site.
    bs.SolanaTx(toBlockchainSolana(msg))

  protected def extractSignedRawBase64(signed: Any): String =
    signed.asInstanceOf[bs.SolanaSignedTx].rawBase64

  private def toBlockchainSolana(m: SolanaMessage): bs.SolanaMessage =
    bs.SolanaMessage(
      numRequiredSignatures       = m.numRequiredSignatures,
      numReadonlySignedAccounts   = m.numReadonlySignedAccounts,
      numReadonlyUnsignedAccounts = m.numReadonlyUnsignedAccounts,
      accountKeys                 = m.accountKeys,
      recentBlockhash             = m.recentBlockhash,
      instructions                = m.instructions.map(i =>
        bs.SolanaInstruction(i.programIdIndex, i.accountIndexes, i.data),
      ),
    )

/** Singleton companion — preserves the error-codes ABI the old
 *  `WalletStandardConnector` object exposed (`WsError`,
 *  `MethodNotFound`, …).  All real code lives on
 *  [[WalletStandardConnectorBase]]; this is just a re-export. */
object WalletStandardConnector:
  type WsError = WalletStandardConnectorBase.WsError
  val  WsError = WalletStandardConnectorBase.WsError
