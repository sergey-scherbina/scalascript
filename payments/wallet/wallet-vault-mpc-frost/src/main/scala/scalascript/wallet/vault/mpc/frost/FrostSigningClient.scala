package scalascript.wallet.vault.mpc.frost

import java.math.BigInteger
import scala.concurrent.Future
import scala.util.Try

import scalascript.crypto.{Curve, HashAlgo}
import scalascript.crypto.frost.{FrostKeygen, FrostSign}
import scalascript.wallet.vault.mpc.{McpAccount, RemoteSigningClient}

/** The FROST signing context for one logical MPC account.
 *
 *  A quorum is a group public key plus the secret shares the local
 *  coordinator can sign with. `signerIds` selects which `t`-of-`n`
 *  participants form the active signing set (the rest stay offline).
 *
 *  Trust model: this reference coordinator holds the signing subset's
 *  shares in-process — i.e. a *trusted coordinator* or a single-node
 *  simulation of the protocol. A real distributed deployment keeps each
 *  share on its own host and gathers the round-1 commitments and round-2
 *  partials over a transport (the production counterpart of
 *  `HttpRemoteSigningClient`); the protocol arithmetic below is identical
 *  either way — only where the shares live differs. The whole point of
 *  FROST is that no party ever sees `sk`, and that holds here too: `sk`
 *  is never reconstructed, only the partials are summed. */
final class FrostQuorum(
    val accountId: String,
    val label:     String,
    keyShares:     FrostKeygen.KeyShares,
    signerIds:     List[Int],
):
  require(
    signerIds.size >= keyShares.threshold,
    s"FROST quorum '$accountId' needs at least t=${keyShares.threshold} signers, got ${signerIds.size}",
  )
  private val signers: List[FrostKeygen.Share] =
    val chosen = keyShares.shares.filter(s => signerIds.contains(s.id))
    require(
      chosen.size == signerIds.size,
      s"FROST quorum '$accountId' references signer ids not present in the key shares: " +
        signerIds.filterNot(id => keyShares.shares.exists(_.id == id)).mkString(", "),
    )
    chosen

  /** The 32-byte Ed25519 group public key — the single logical pubkey the
   *  threshold group signs as. */
  def groupPublicKey: Array[Byte] = keyShares.groupPublicKey

  /** Run the FROST 2-round protocol over the active signing set and return
   *  the assembled 64-byte standard Ed25519 signature over `msg`.
   *
   *  Round 1: each signer draws fresh nonces and publishes commitments.
   *  Round 2: each signer produces a partial; the coordinator sums them.
   *  The result verifies under plain Ed25519 against `groupPublicKey`. */
  def sign(msg: Array[Byte]): Array[Byte] =
    val rounds: List[(FrostSign.Nonce, FrostSign.Commitment, FrostKeygen.Share)] =
      signers.map { share =>
        val (nonce, commitment) = FrostSign.round1(share.id)
        (nonce, commitment, share)
      }
    val commitments: List[FrostSign.Commitment] = rounds.map(_._2)
    val partials: List[BigInteger] = rounds.map { case (nonce, _, share) =>
      FrostSign.partialSign(nonce, share, msg, commitments, keyShares.groupPublicKey)
    }
    FrostSign.aggregate(msg, commitments, partials)

/** An in-house `RemoteSigningClient` backed by FROST-Ed25519 threshold
 *  signing — the counterpart to the external provider clients
 *  (Fireblocks / Coinbase / Lit / Zengo). Instead of delegating to a
 *  third-party TSS service, every `sign` call runs the project's own
 *  FROST protocol over a [[FrostQuorum]].
 *
 *  It plugs straight into `McpVault`: that vault's contract is "delegate
 *  signing to a threshold provider", and its own documentation already
 *  names "FROST for Ed25519" as the protocol such a provider runs. So a
 *  FROST vault is simply:
 *  {{{
 *    val client = new FrostSigningClient(Seq(quorum))
 *    val vault  = new McpVault("treasury", client)   // kind = Mpc
 *  }}}
 *  with no new `Vault` implementation.
 *
 *  Only `Curve.Ed25519` is supported (FROST-Ed25519). Other curves fail
 *  the signing Future, mirroring how a provider rejects an unsupported
 *  curve. `health()` is always `true` — an in-process quorum is always
 *  reachable; a distributed transport would probe its peers here. */
final class FrostSigningClient(quorums: Seq[FrostQuorum]) extends RemoteSigningClient:

  private val byId: Map[String, FrostQuorum] = quorums.map(q => q.accountId -> q).toMap

  def listAccounts(): Future[Seq[McpAccount]] =
    Future.successful(
      quorums.map(q =>
        McpAccount(q.accountId, q.label, Map(Curve.Ed25519 -> q.groupPublicKey))
      )
    )

  def sign(
      accountId:      String,
      curve:          Curve,
      derivationPath: String,
      payload:        Array[Byte],
      hashAlgo:       HashAlgo,
  ): Future[Array[Byte]] =
    byId.get(accountId) match
      case None =>
        Future.failed(new IllegalArgumentException(
          s"FROST signing client has no quorum for account '$accountId'"))
      case Some(_) if curve != Curve.Ed25519 =>
        Future.failed(new IllegalArgumentException(
          s"FROST signing client only supports Curve.Ed25519, got $curve"))
      case Some(quorum) =>
        // FROST signs the raw message bytes (Ed25519's internal hashing is
        // part of the protocol); `hashAlgo` is informational here.
        Future.fromTry(Try(quorum.sign(payload)))

  def health(): Future[Boolean] = Future.successful(true)
