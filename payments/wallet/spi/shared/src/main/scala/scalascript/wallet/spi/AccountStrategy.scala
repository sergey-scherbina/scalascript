package scalascript.wallet.spi

import scala.concurrent.Future
import scalascript.blockchain.spi.{ChainAdapter, TypedData}

/** Pairs a `RawSigner` with chain-specific orchestration. Concrete
 *  strategies:
 *
 *  - `EoaStrategy` (in `wallet-strategy-eoa`): the classic
 *    one-key-one-address path — sign the chain's digest with the
 *    underlying RawSigner and let the chain adapter assemble the tx.
 *
 *  - `SmartAccountStrategy` (in `wallet-strategy-erc4337`, Phase 6):
 *    wraps an EVM `ChainAdapter` to use an ERC-4337 contract wallet
 *    instead of an EOA. Owner is any `RawSigner` (typically a passkey).
 *
 *  See docs/specs/wallet-spi.md §6 for the design rationale. */
trait AccountStrategy:

  /** Discriminator: "eoa", "smart-account", "mpc-eoa", …. Used by
   *  hosts for UI affordances and policy decisions. */
  def kind: String

  /** Address as the given chain represents it. */
  def getAddress(chain: ChainAdapter): Future[String]

  /** Sign a fully-built native transaction. The result is the
   *  chain-specific signed-tx representation, ready to broadcast. */
  def signTransaction(chain: ChainAdapter)(tx: chain.Tx): Future[chain.SignedTx]

  /** Sign an arbitrary message with the chain's message-signing
   *  convention (e.g. EVM's `personal_sign` prefix). */
  def signMessage(chain: ChainAdapter, msg: Array[Byte]): Future[Array[Byte]]

  /** Sign a typed-data envelope (EIP-712, CIP-8, Cosmos sign_doc, …).
   *  The chain adapter knows how to hash; this strategy just signs
   *  the digest. */
  def signTypedData(chain: ChainAdapter, typed: TypedData): Future[Array[Byte]]
