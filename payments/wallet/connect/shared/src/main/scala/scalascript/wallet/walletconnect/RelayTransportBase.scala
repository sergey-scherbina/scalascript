package scalascript.wallet.walletconnect

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scalascript.blockchain.spi.ChainId

/** Cross-platform WC v2 relay transport.
 *
 *  Houses every protocol-level piece that doesn't care about the
 *  underlying transport's platform — `irn_publish` framing, the
 *  encrypted-envelope inbound demux, the X25519 + Type-1 handshake
 *  emitted on session approve, all WC JSON-RPC parsers / builders.
 *
 *  The only thing it doesn't own is the [[WsChannel]] itself — that's
 *  injected.  Platform subclasses ([[JvmRelayTransport]] in `jvm/`,
 *  [[JsRelayTransport]] in `js/`) provide a concrete channel and
 *  re-expose the same constructor signature for backwards
 *  compatibility with existing JVM callers (`new JvmRelayTransport(...)`
 *  is the legacy entry point).
 *
 *  This composition is the testability story of WC v2 in Scala — every
 *  concrete platform sees the same parsing / encoding code so a green
 *  shared spec on JVM is equivalent to a green shared spec on JS. */
abstract class RelayTransportBase(
  channel:      WsChannel,
  sessionStore: WcSessionStore,
  jwtSeed:      Array[Byte],                 // 32-byte ed25519 private key
  relayUrl:     String = "wss://relay.walletconnect.com",
)(using @annotation.unused ec: ExecutionContext) extends WcRelayTransport:

  require(jwtSeed.length == 32, s"jwtSeed must be 32 B (ed25519 priv), got ${jwtSeed.length}")

  private val ids                              = new RelayJsonRpc.IdAllocator()
  private val jwtPub: Array[Byte]              = RelayJwt.publicKeyFromPrivate(jwtSeed)
  @volatile private var inboundHandler:        WcInbound => Unit = _ => ()

  /** Last-built ws URL, exposed so tests can verify the JWT it carries. */
  @volatile private var lastConnectUrl: Option[String] = None
  def connectUrl: Option[String] = lastConnectUrl

  /** Wallet's persistent ed25519 client identity public key — same
   *  bytes the relay would derive from the JWT's `iss` claim. */
  def identityPublicKey: Array[Byte] = jwtPub.clone()

  // ── lifecycle ────────────────────────────────────────────────────────

  def connect(projectId: String): Future[Unit] =
    channel.onText(handleText)
    val jwt  = RelayJwt.sign(jwtSeed, jwtPub)
    val url  = s"$relayUrl?projectId=$projectId&auth=$jwt"
    lastConnectUrl = Some(url)
    channel.connect(url)

  def close(): Future[Unit] =
    channel.close()

  // ── subscriptions ────────────────────────────────────────────────────

  def subscribe(topic: String): Future[Unit] =
    val req = RelayJsonRpc.buildSubscribe(ids.next(), topic)
    channel.send(RelayJsonRpc.render(req))

  def unsubscribe(topic: String): Future[Unit] =
    val req = RelayJsonRpc.buildUnsubscribe(ids.next(), topic)
    channel.send(RelayJsonRpc.render(req))

  // ── inbound dispatch ─────────────────────────────────────────────────

  def onInbound(handler: WcInbound => Unit): Unit =
    inboundHandler = handler

  private def handleText(text: String): Unit =
    RelayJsonRpc.parse(text) match
      case Some(sub: RelayJsonRpc.Inbound.Subscription) => handleSubscription(sub)
      case _                                            => () // ack / unknown — drop

  private def handleSubscription(sub: RelayJsonRpc.Inbound.Subscription): Unit =
    sessionStore.lookup(sub.topic).foreach { entry =>
      val tryOpen: Try[(WcEnvelope.Opened, ujson.Value)] = for
        env     <- Try(WcEnvelope.decodeBase64(sub.message))
        opened  <- Try(WcEnvelope.open(entry.symKey, env))
        payload <- Try(ujson.read(new String(opened.plaintext, UTF_8)))
      yield (opened, payload)
      tryOpen match
        case Failure(_)              => () // malformed — drop
        case Success((opened, payload)) =>
          opened.senderPublicKey.foreach { peerPub =>
            sessionStore.register(sub.topic, entry.symKey, Some(peerPub))
          }
          dispatchInner(sub.topic, payload)
    }

  /** Parse the inner WC JSON-RPC payload and dispatch a `WcInbound`. */
  private def dispatchInner(topic: String, payload: ujson.Value): Unit =
    payload.objOpt match
      case None      => ()
      case Some(obj) => dispatchByMethod(topic, obj, payload)

  private def dispatchByMethod(topic: String, obj: collection.Map[String, ujson.Value], payload: ujson.Value): Unit =
    obj.get("method").map(_.str) match
      case Some("wc_sessionPropose")  => parseSessionPropose(topic, payload.obj).foreach(emit)
      case Some("wc_sessionRequest")  => parseSessionRequest(topic, payload.obj).foreach(emit)
      case Some("wc_sessionDelete")   => parseSessionDelete(topic, payload.obj).foreach(emit)
      case Some("wc_sessionUpdate")   => parseSessionUpdate(topic, payload.obj).foreach(emit)
      case Some("wc_sessionPing")     => emit(WcInbound.Ping(topic))
      case Some(_) | None             => () // unhandled — drop

  private def emit(msg: WcInbound): Unit =
    try inboundHandler(msg) catch case _: Throwable => ()

  // ── inbound parsers ──────────────────────────────────────────────────

  private def parseSessionPropose(pairingTopic: String, frame: ujson.Obj): Option[WcInbound.Proposal] =
    try
      val id     = frame("id").num.toLong
      val params = frame("params").obj
      val proposerObj = params("proposer").obj
      val proposerPub = proposerObj("publicKey").str
      val metaObj     = proposerObj("metadata").obj
      val proposer = WcParticipant(
        publicKey = proposerPub,
        metadata  = parseMetadata(metaObj),
      )
      val requiredNs = params.get("requiredNamespaces").map(parseNamespaceMap).getOrElse(Map.empty)
      val optionalNs = params.get("optionalNamespaces").map(parseNamespaceMap).getOrElse(Map.empty)
      val sessionProps = params.get("sessionProperties").map { v =>
        v.obj.iterator.map { case (k, vv) => k -> vv.str }.toMap
      }.getOrElse(Map.empty)
      Some(WcInbound.Proposal(WcSessionProposal(
        id                   = id,
        pairingTopic         = pairingTopic,
        proposer             = proposer,
        requiredNamespaces   = requiredNs,
        optionalNamespaces   = optionalNs,
        sessionProperties    = sessionProps,
      )))
    catch case _: Throwable => None

  private def parseSessionRequest(sessionTopic: String, frame: ujson.Obj): Option[WcInbound.Request] =
    try
      val id      = frame("id").num.toLong
      val params  = frame("params").obj
      val chain   = ChainId(params("chainId").str)
      val req     = params("request").obj
      val method  = req("method").str
      val inner   = req.getOrElse("params", ujson.Arr())
      Some(WcInbound.Request(WcSessionRequest(
        id      = id,
        topic   = sessionTopic,
        chainId = chain,
        method  = method,
        params  = inner,
      )))
    catch case _: Throwable => None

  private def parseSessionDelete(topic: String, frame: ujson.Obj): Option[WcInbound.SessionDelete] =
    try
      val params = frame("params").obj
      val reason = params.get("reason").flatMap(_.objOpt).flatMap(_.get("message")).map(_.str)
        .orElse(params.get("message").map(_.str)).getOrElse("")
      Some(WcInbound.SessionDelete(topic, reason))
    catch case _: Throwable => None

  private def parseSessionUpdate(topic: String, frame: ujson.Obj): Option[WcInbound.SessionUpdate] =
    try
      val params = frame("params").obj
      val ns     = params.get("namespaces").map(parseNamespaceMap).getOrElse(Map.empty)
      Some(WcInbound.SessionUpdate(topic, ns))
    catch case _: Throwable => None

  private def parseMetadata(obj: ujson.Obj): WcAppMetadata =
    val m = obj.value
    WcAppMetadata(
      name        = m.get("name").map(_.str).getOrElse(""),
      description = m.get("description").map(_.str).getOrElse(""),
      url         = m.get("url").map(_.str).getOrElse(""),
      icons       = m.get("icons").flatMap(_.arrOpt).map(_.iterator.map(_.str).toSeq).getOrElse(Seq.empty),
    )

  private def parseNamespaceMap(v: ujson.Value): Map[String, WcNamespace] =
    v.objOpt.getOrElse(scala.collection.mutable.LinkedHashMap.empty).iterator.map { case (k, vv) =>
      val o = vv.obj.value
      val chains = o.get("chains").flatMap(_.arrOpt).map(_.iterator.map(c => ChainId(c.str)).toSeq).getOrElse(Seq.empty)
      val methods = o.get("methods").flatMap(_.arrOpt).map(_.iterator.map(_.str).toSeq).getOrElse(Seq.empty)
      val events  = o.get("events").flatMap(_.arrOpt).map(_.iterator.map(_.str).toSeq).getOrElse(Seq.empty)
      k -> WcNamespace(chains = chains, methods = methods, events = events)
    }.toMap

  // ── outbound encoding ────────────────────────────────────────────────

  def send(message: WcOutbound): Future[Unit] = message match
    case WcOutbound.ApproveSession(topic, resp) => sendApproveSession(topic, resp)
    case WcOutbound.RejectSession(topic, resp)  => sendRejectSession(topic, resp)
    case WcOutbound.RequestResult(topic, res)   => sendRequestResult(topic, res)
    case WcOutbound.EmitEvent(topic, name, data, chain) => sendEmitEvent(topic, name, data, chain)
    case WcOutbound.Disconnect(topic, reason)   => sendDisconnect(topic, reason)

  /** Session-approve goes on the pairing topic. If the pairing
   *  entry has the peer's X25519 pubkey (which the connector
   *  populated from the proposal), we generate a fresh keypair,
   *  derive the session symKey, register the session topic in the
   *  store, and ship the response as a Type 1 envelope so the dApp
   *  learns our pubkey. */
  private def sendApproveSession(pairingTopic: String, resp: WcSessionResponse): Future[Unit] =
    sessionStore.lookup(pairingTopic) match
      case None => missingEntry(pairingTopic)
      case Some(pairingEntry) =>
        val plaintext = buildSessionSettleJson(resp).render().getBytes(UTF_8)
        val sealedBytes = pairingEntry.peerPub match
          case Some(peerPub) =>
            // Fresh X25519 keypair for the session; derive symKey.
            val kp           = WcKeyAgreement.generateKeypair()
            val sharedSecret = WcKeyAgreement.deriveSharedSecret(kp.privateKey, peerPub)
            val symKey       = WcKeyAgreement.deriveSymKey(sharedSecret)
            val sessionTopic = WcKeyAgreement.hexEncode(WcKeyAgreement.topicFromSymKey(symKey))
            sessionStore.register(sessionTopic, symKey, Some(peerPub))
            WcEnvelope.sealType1(pairingEntry.symKey, kp.publicKey, plaintext)
          case None =>
            WcEnvelope.sealType0(pairingEntry.symKey, plaintext)
        publish(pairingTopic, sealedBytes, RelayJsonRpc.Tag.SessionSettle, RelayJsonRpc.Ttl.FiveMinutes)

  private def missingEntry(topic: String): Future[Unit] =
    Future.failed(new IllegalStateException(s"no session entry for topic $topic"))

  private def sendRejectSession(pairingTopic: String, resp: WcSessionResponse): Future[Unit] =
    sessionStore.lookup(pairingTopic) match
      case None        => missingEntry(pairingTopic)
      case Some(entry) =>
        val responseJson = ujson.Obj(
          "id"      -> ujson.Num(resp.proposalId.toDouble),
          "jsonrpc" -> ujson.Str("2.0"),
          "error"   -> ujson.Obj(
            "code"    -> ujson.Num(5000),
            "message" -> ujson.Str(if resp.reason.nonEmpty then resp.reason else "User rejected"),
          ),
        )
        val sealedBytes = WcEnvelope.sealType0(entry.symKey, responseJson.render().getBytes(UTF_8))
        publish(pairingTopic, sealedBytes, RelayJsonRpc.Tag.SessionProposeResponse, RelayJsonRpc.Ttl.FiveMinutes)

  private def sendRequestResult(sessionTopic: String, result: WcSessionResult): Future[Unit] =
    sessionStore.lookup(sessionTopic) match
      case None        => missingEntry(sessionTopic)
      case Some(entry) =>
        val json = result match
          case WcSessionResult.Ok(id, value) =>
            ujson.Obj(
              "id"      -> ujson.Num(id.toDouble),
              "jsonrpc" -> ujson.Str("2.0"),
              "result"  -> value,
            )
          case WcSessionResult.Error(id, code, msg) =>
            ujson.Obj(
              "id"      -> ujson.Num(id.toDouble),
              "jsonrpc" -> ujson.Str("2.0"),
              "error"   -> ujson.Obj(
                "code"    -> ujson.Num(code.toDouble),
                "message" -> ujson.Str(msg),
              ),
            )
        val sealedBytes = WcEnvelope.sealType0(entry.symKey, json.render().getBytes(UTF_8))
        publish(sessionTopic, sealedBytes, RelayJsonRpc.Tag.SessionRequestResponse, RelayJsonRpc.Ttl.FiveMinutes)

  private def sendEmitEvent(sessionTopic: String, name: String, data: ujson.Value, chain: ChainId): Future[Unit] =
    sessionStore.lookup(sessionTopic) match
      case None        => missingEntry(sessionTopic)
      case Some(entry) =>
        val frame = ujson.Obj(
          "id"      -> ujson.Num(ids.next().toDouble),
          "jsonrpc" -> ujson.Str("2.0"),
          "method"  -> ujson.Str("wc_sessionEvent"),
          "params"  -> ujson.Obj(
            "chainId" -> ujson.Str(chain.toString),
            "event"   -> ujson.Obj(
              "name" -> ujson.Str(name),
              "data" -> data,
            ),
          ),
        )
        val sealedBytes = WcEnvelope.sealType0(entry.symKey, frame.render().getBytes(UTF_8))
        publish(sessionTopic, sealedBytes, RelayJsonRpc.Tag.SessionEvent, RelayJsonRpc.Ttl.FiveMinutes)

  private def sendDisconnect(sessionTopic: String, reason: String): Future[Unit] =
    sessionStore.lookup(sessionTopic) match
      case None        => missingEntry(sessionTopic)
      case Some(entry) =>
        val frame = ujson.Obj(
          "id"      -> ujson.Num(ids.next().toDouble),
          "jsonrpc" -> ujson.Str("2.0"),
          "method"  -> ujson.Str("wc_sessionDelete"),
          "params"  -> ujson.Obj(
            "code"    -> ujson.Num(6000),
            "message" -> ujson.Str(if reason.nonEmpty then reason else "User disconnected"),
          ),
        )
        val sealedBytes = WcEnvelope.sealType0(entry.symKey, frame.render().getBytes(UTF_8))
        publish(sessionTopic, sealedBytes, RelayJsonRpc.Tag.SessionDelete, RelayJsonRpc.Ttl.OneDay)

  private def publish(topic: String, sealedBytes: Array[Byte], tag: Int, ttl: Long, prompt: Boolean = false): Future[Unit] =
    val msg = WcEnvelope.encodeBase64(sealedBytes)
    val req = RelayJsonRpc.buildPublish(ids.next(), topic, msg, ttl, tag, prompt)
    channel.send(RelayJsonRpc.render(req))

  /** Build the inner wc_sessionSettle JSON payload from a wallet
   *  approval response. */
  private def buildSessionSettleJson(resp: WcSessionResponse): ujson.Obj =
    val nsJson = ujson.Obj()
    resp.namespaces.foreach { case (key, ns) =>
      nsJson(key) = ujson.Obj(
        "chains"   -> ujson.Arr(ns.chains.map(c => ujson.Str(c.toString))*),
        "methods"  -> ujson.Arr(ns.methods.map(ujson.Str(_))*),
        "events"   -> ujson.Arr(ns.events.map(ujson.Str(_))*),
        "accounts" -> ujson.Arr(ns.accounts.map(a => ujson.Str(a.toString))*),
      )
    }
    val controllerPub = WcKeyAgreement.hexEncode(jwtPub)
    ujson.Obj(
      "id"      -> ujson.Num(resp.proposalId.toDouble),
      "jsonrpc" -> ujson.Str("2.0"),
      "method"  -> ujson.Str("wc_sessionSettle"),
      "params"  -> ujson.Obj(
        "relay"      -> ujson.Obj("protocol" -> ujson.Str("irn")),
        "namespaces" -> nsJson,
        "controller" -> ujson.Obj(
          "publicKey" -> ujson.Str(controllerPub),
          "metadata"  -> ujson.Obj(
            "name"        -> ujson.Str(""),
            "description" -> ujson.Str(""),
            "url"         -> ujson.Str(""),
            "icons"       -> ujson.Arr(),
          ),
        ),
        "expiry" -> ujson.Num((System.currentTimeMillis() / 1000 + 7 * 24 * 3600).toDouble),
      ),
    )
