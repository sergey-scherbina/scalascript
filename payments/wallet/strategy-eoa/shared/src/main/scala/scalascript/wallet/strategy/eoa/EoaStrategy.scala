package scalascript.wallet.strategy.eoa

import scala.concurrent.{ExecutionContext, Future}
import scalascript.blockchain.spi.{ChainAdapter, TypedData}
import scalascript.crypto.HashAlgo
import scalascript.wallet.spi.{AccountStrategy, RawSigner}

/** Externally-owned-account strategy: one key, one address per chain,
 *  signatures produced by the underlying `RawSigner` and assembled into
 *  chain-native txs by the `ChainAdapter`. Works for any chain whose
 *  EOA model fits curve + address-from-pubkey + sign-the-digest — that
 *  is, EVM, Solana, Cosmos, Tron, Bitcoin P2WPKH.
 *
 *  Smart accounts (ERC-4337) use a different strategy — see
 *  `wallet-strategy-erc4337` (Phase 6). */
class EoaStrategy(val signer: RawSigner)(using ec: ExecutionContext) extends AccountStrategy:

  def kind: String = "eoa"

  def getAddress(chain: ChainAdapter): Future[String] =
    Future.successful(chain.addressFromPublicKey(signer.publicKey))

  def signTransaction(chain: ChainAdapter)(tx: chain.Tx): Future[chain.SignedTx] =
    val payload = chain.prepareSigningPayload(tx, signer.publicKey)
    signer.sign(payload.bytes, payload.hash).map { sig =>
      chain.assembleSignedTransaction(tx, sig, signer.publicKey)
    }

  def signMessage(chain: ChainAdapter, msg: Array[Byte]): Future[Array[Byte]] =
    // The chain wraps `msg` with its message-signing prefix (e.g. EVM's
    // `personal_sign`) via TypedData.Raw and hashes accordingly.
    val digest = chain.typedDataDigest(TypedData.Raw(msg))
    signer.sign(digest, HashAlgo.None)

  def signTypedData(chain: ChainAdapter, typed: TypedData): Future[Array[Byte]] =
    val digest = chain.typedDataDigest(typed)
    signer.sign(digest, HashAlgo.None)
