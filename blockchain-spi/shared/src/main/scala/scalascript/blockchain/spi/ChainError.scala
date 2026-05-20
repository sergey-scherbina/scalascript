package scalascript.blockchain.spi

/** Cross-chain error vocabulary that facilitators, wallets, and agents
 *  pattern-match against. Chain adapters MAY extend with chain-specific
 *  subtypes for diagnostics; consumers should handle the common cases
 *  here and fall through to a generic `Other` branch. */
sealed trait ChainError extends Exception:
  def message: String
  override def getMessage: String = message

object ChainError:
  case class Network(message: String) extends ChainError
  case class InsufficientFunds(have: BigInt, need: BigInt) extends ChainError:
    def message: String = s"Insufficient funds: have $have, need $need"
  case class InvalidSignature(reason: String) extends ChainError:
    def message: String = s"Invalid signature: $reason"
  case class TxRejected(reason: String) extends ChainError:
    def message: String = s"Transaction rejected: $reason"
  case class Other(message: String) extends ChainError
