package scalascript.wallet.vault.mpc.frost

import java.math.BigInteger
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.{ExecutionContext, Future}
import scalascript.crypto.{Curve, HashAlgo}
import scalascript.crypto.frost.FrostSign
import scalascript.wallet.vault.mpc.{McpAccount, RemoteSigningClient}

/** A [[RemoteSigningClient]] that runs FROST-Ed25519 over a set of remote [[FrostParticipantServer]]s — one
 *  secret share per host — and assembles their partials into a standard Ed25519 signature.
 *
 *  This is the **production counterpart of the in-process `FrostQuorum`**: the coordinator holds the group
 *  public key + the participants' URLs but **no shares**, and the protocol messages it exchanges (round-1
 *  commitments, round-2 partials) are all public. So a `t`-of-`n` threshold-custody wallet can sign with its
 *  shares living on genuinely separate hosts. Because it implements `RemoteSigningClient`, it drops straight
 *  into `McpVault` exactly like the external-provider clients (Fireblocks / Coinbase / …).
 *
 *  Only `Curve.Ed25519` is supported (FROST-Ed25519). The transport here is plain HTTP/JSON; a WS or
 *  actor-cluster transport is the same protocol over a different pipe (the bodies are unchanged). */
final class DistributedFrostSigningClient(
    val accountId:   String,
    label:           String,
    groupPublicKey:  Array[Byte],
    participantUrls: List[String],
    threshold:       Int,
    timeoutMs:       Long = 10_000L,
)(using ec: ExecutionContext) extends RemoteSigningClient:

  require(participantUrls.size >= threshold,
    s"distributed FROST '$accountId' needs >= t=$threshold participants, got ${participantUrls.size}")

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build()

  private def b64e(b: Array[Byte]): String = java.util.Base64.getEncoder.encodeToString(b)
  private def b64d(s: String): Array[Byte] = java.util.Base64.getDecoder.decode(s)

  /** POST a JSON body to `url + path`, returning the parsed `result`; fails the Future on an `{error}` body. */
  private def post(url: String, path: String, body: ujson.Obj): Future[ujson.Value] = Future {
    val req = HttpRequest.newBuilder(URI.create(url + path))
      .timeout(Duration.ofMillis(timeoutMs))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
      .build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
    val json = ujson.read(resp.body())
    if json.obj.contains("error") then
      throw new RuntimeException(s"participant $url$path: ${json("error").str}")
    json
  }

  def listAccounts(): Future[Seq[McpAccount]] =
    Future.successful(Seq(McpAccount(accountId, label, Map(Curve.Ed25519 -> groupPublicKey))))

  def health(): Future[Boolean] =
    Future.sequence(participantUrls.map { u =>
      post(u, "/health", ujson.Obj()).map(_ => true).recover { case _ => false }
    }).map(rs => rs.count(identity) >= threshold)

  def sign(
      accountId:      String,
      curve:          Curve,
      derivationPath: String,
      payload:        Array[Byte],
      hashAlgo:       HashAlgo,
  ): Future[Array[Byte]] =
    if curve != Curve.Ed25519 then
      Future.failed(new IllegalArgumentException(s"distributed FROST only supports Curve.Ed25519, got $curve"))
    else
      // A fresh session id keys each participant's one-time round-1 nonce.
      val sessionId = new BigInteger(128, new java.security.SecureRandom()).toString(16)

      // Round 1: collect each remote participant's commitment.
      Future.sequence(participantUrls.map(u => post(u, "/round1", ujson.Obj("sessionId" -> sessionId)))).flatMap { r1 =>
        val commitments = r1.map(j =>
          FrostSign.Commitment(j("id").num.toInt, b64d(j("D").str), b64d(j("E").str))).toList
        val commitmentsJson = ujson.Arr(
          commitments.map(c => ujson.Obj("id" -> c.id, "D" -> b64e(c.D), "E" -> b64e(c.E)))*)

        // Round 2: send the full commitment set to every participant; collect partials.
        Future.sequence(participantUrls.map(u =>
          post(u, "/round2", ujson.Obj(
            "sessionId"   -> sessionId,
            "msg"         -> b64e(payload),
            "commitments" -> commitmentsJson)))).map { r2 =>
          val partials = r2.map(j => new BigInteger(b64d(j("partial").str))).toList
          // The coordinator aggregates — only public data (commitments + partials), never a share.
          FrostSign.aggregate(payload, commitments, partials)
        }
      }
