package scalascript.blockchain.spi

/** CAIP-10 account identifier: `<caip2>:<address>`. Used wherever an
 *  address needs to be qualified with its chain (e.g. WalletConnect v2
 *  namespaces, x402 multi-chain payment routing). */
case class AccountId(chain: ChainId, address: String):
  def caip10: String = s"${chain.caip2}:$address"
  override def toString: String = caip10

object AccountId:
  /** Parse a CAIP-10 string. Returns None for malformed inputs. */
  def parse(s: String): Option[AccountId] =
    s.lastIndexOf(':') match
      case -1 => None
      case i  =>
        val chain = s.substring(0, i)
        val addr  = s.substring(i + 1)
        if !chain.contains(':') then None
        else Some(AccountId(ChainId(chain), addr))
