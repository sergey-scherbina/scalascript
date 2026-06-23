package scalascript.wallet.vault.mpc.frost

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import scala.collection.concurrent.TrieMap
import scalascript.crypto.frost.{FrostKeygen, FrostSign}

/** A FROST signing **participant** served over HTTP, holding EXACTLY ONE secret share.
 *
 *  In a real distributed threshold-custody deployment one of these runs on each host (a phone, an HSM box, a
 *  separate cloud account). It exposes the two FROST rounds; the share never leaves this process, and no single
 *  party — including the coordinator ([[DistributedFrostSigningClient]]) — ever sees `sk`.
 *
 *  Endpoints (JSON over HTTP):
 *  - `POST /round1`  `{sessionId}`                          → `{id, D, E}`  (this signer's commitment; base64)
 *  - `POST /round2`  `{sessionId, msg, commitments:[…]}`    → `{partial}`   (this signer's partial; base64)
 *  - `GET  /health`                                         → `{ok:true}`
 *
 *  The round-1 secret nonces are kept in-process keyed by `sessionId` and consumed once in round 2 (a nonce is
 *  never reused — that would break Schnorr). */
final class FrostParticipantServer(
    share:          FrostKeygen.Share,
    groupPublicKey: Array[Byte],
    port:           Int = 0,
):
  private val sessions = TrieMap.empty[String, FrostSign.Nonce]
  private val server   = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)

  private def b64e(b: Array[Byte]): String = java.util.Base64.getEncoder.encodeToString(b)
  private def b64d(s: String): Array[Byte] = java.util.Base64.getDecoder.decode(s)

  private def readBody(ex: HttpExchange): ujson.Value =
    ujson.read(new String(ex.getRequestBody.readAllBytes(), "UTF-8"))

  private def respond(ex: HttpExchange, code: Int, body: ujson.Value): Unit =
    val bytes = ujson.write(body).getBytes("UTF-8")
    ex.getResponseHeaders.set("Content-Type", "application/json")
    ex.sendResponseHeaders(code, bytes.length)
    val os = ex.getResponseBody
    try os.write(bytes) finally os.close()

  private def handle(ex: HttpExchange)(f: ujson.Value => ujson.Value): Unit =
    try respond(ex, 200, f(readBody(ex)))
    catch case e: Throwable => respond(ex, 500, ujson.Obj("error" -> ujson.Str(String.valueOf(e.getMessage))))

  server.createContext("/round1", (ex: HttpExchange) =>
    handle(ex) { req =>
      val (nonce, commitment) = FrostSign.round1(share.id)
      sessions.put(req("sessionId").str, nonce)
      ujson.Obj("id" -> share.id, "D" -> b64e(commitment.D), "E" -> b64e(commitment.E))
    })

  server.createContext("/round2", (ex: HttpExchange) =>
    handle(ex) { req =>
      val msg = b64d(req("msg").str)
      val commitments = req("commitments").arr.map { c =>
        FrostSign.Commitment(c("id").num.toInt, b64d(c("D").str), b64d(c("E").str))
      }.toList
      val nonce = sessions.remove(req("sessionId").str)
        .getOrElse(throw new IllegalStateException(s"participant ${share.id}: no round-1 nonce for session"))
      val partial = FrostSign.partialSign(nonce, share, msg, commitments, groupPublicKey)
      ujson.Obj("partial" -> b64e(partial.toByteArray))
    })

  server.createContext("/health", (ex: HttpExchange) =>
    respond(ex, 200, ujson.Obj("ok" -> true)))

  /** The signer id this participant holds the share for. */
  def signerId: Int = share.id

  /** Base URL once started (the OS-assigned port when constructed with `port = 0`). */
  def url: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  def start(): Unit = server.start()
  def stop():  Unit = server.stop(0)
