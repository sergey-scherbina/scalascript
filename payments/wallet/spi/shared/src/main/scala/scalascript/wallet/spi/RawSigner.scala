package scalascript.wallet.spi

import scala.concurrent.Future
import scalascript.crypto.{Curve, HashAlgo, PublicKey}

/** Chain-agnostic signing primitive. Implementations: an
 *  encrypted-seed-derived signer (`crypto-bouncycastle` extracting a
 *  private key), an MPC-network proxy, a Ledger hardware signer, a
 *  WebAuthn passkey signer. All of them obey the same contract: given
 *  `msg` bytes, produce a curve-specific signature.
 *
 *  Invariant: a `RawSigner` does not know which chain its signature
 *  will be used on. It knows only its `curve`. Chain-specific
 *  encoding (e.g. EIP-712 digest, EIP-3009 prefix) lives one layer up
 *  in `AccountStrategy` / `ChainAdapter`.
 *
 *  See docs/specs/wallet-spi.md §5 for the design rationale. */
trait RawSigner:
  def curve: Curve
  def publicKey: PublicKey

  /** Sign the given `msg`. If `hash != None` the signer may hash the
   *  message internally per the curve's convention; if `None`, the
   *  caller is supplying a pre-computed digest.
   *
   *  The Future shape lets hardware / MPC / passkey signers do I/O
   *  without forcing software signers to fake async. */
  def sign(msg: Array[Byte], hash: HashAlgo = HashAlgo.None): Future[Array[Byte]]
