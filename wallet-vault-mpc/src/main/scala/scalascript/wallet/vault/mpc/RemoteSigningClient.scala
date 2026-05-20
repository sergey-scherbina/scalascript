package scalascript.wallet.vault.mpc

import scala.concurrent.Future
import scalascript.crypto.{Curve, HashAlgo}

/** A single account known to the remote MPC provider.
 *
 *  An MPC account is shorthand for a key-share group: the provider
 *  holds threshold-distributed shares across its nodes, but exposes
 *  them to clients as a single logical key with a public component
 *  per curve. The `id` is the provider-side opaque account id (e.g.
 *  Fireblocks vault id, Zengo wallet id, Web3Auth address). */
case class McpAccount(
  id:         String,
  label:      String,
  publicKeys: Map[Curve, Array[Byte]],
)

/** Provider-side abstraction. An `McpVault` delegates to one of these.
 *
 *  All operations are asynchronous because every signing call requires
 *  a network round-trip to a threshold of MPC nodes; even the
 *  "synchronous" case (e.g. an in-cluster TSS group) is HTTP-bound.
 *
 *  Implementations:
 *   - `HttpRemoteSigningClient` — the reference JSON-over-HTTPS impl
 *     in this module.
 *   - Provider-specific clients (Fireblocks, ZenGo, Coinbase MPC, …)
 *     can wrap their SDK behind this trait without changing `McpVault`.
 *   - Test impls (`MockRemoteSigningClient`) inject canned responses. */
trait RemoteSigningClient:
  /** Lists every account this client can sign with under the current
   *  credentials. Returned `publicKeys` carry the provider's recorded
   *  public component per curve and are what `McpVault` returns up
   *  through `AccountDescriptor` for chain address derivation. */
  def listAccounts(): Future[Seq[McpAccount]]

  /** Request a signature from the provider for `(accountId, curve,
   *  derivationPath)` over `payload`. `hashAlgo` describes whether
   *  the payload is a pre-computed digest (`None`) or whether the
   *  provider should apply the named hash before signing.
   *
   *  Returns the raw signature bytes — curve-specific encoding
   *  (DER / 64-byte concat / etc.) is the provider's responsibility
   *  and is documented per integration. */
  def sign(
    accountId:      String,
    curve:          Curve,
    derivationPath: String,
    payload:        Array[Byte],
    hashAlgo:       HashAlgo,
  ): Future[Array[Byte]]

  /** Liveness / authentication probe. The `McpVault` treats `true` as
   *  "unlocked" — i.e. the credentials are valid and the provider is
   *  reachable. False (or a failed Future) means the vault is
   *  effectively locked even if `unlock` was previously called. */
  def health(): Future[Boolean]
