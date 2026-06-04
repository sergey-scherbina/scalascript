package scalascript.wallet.walletconnect

import scala.concurrent.{ExecutionContext, Future}
import scalascript.blockchain.spi.*
import scalascript.wallet.spi.*

/** Wallet-side `DappConnector` for WalletConnect v2.
 *
 *  Connects to a relay (via the injected `WcRelayTransport`),
 *  advertises namespaces derived from the host's `AccountManager`,
 *  accepts session proposals automatically when their required
 *  namespaces are a subset of what the wallet supports, and routes
 *  inbound signing/send requests through the `Eip1193`-equivalent
 *  semantics to `AccountStrategy` / `ChainAdapter`.
 *
 *  This is a **scaffold**: the session-acceptance + request-routing
 *  paths are real, but they delegate signing to a user-supplied
 *  `requestHandler` (in production: `Eip1193Provider.request`).
 *  The transport itself (relay ws + chacha20 envelope encryption)
 *  is injected.
 *
 *  See specs/wallet-spi.md §7.3 for the design. */
class WalletConnectConnector(
  val manager:        AccountManager,
  transport:          WcRelayTransport,
  requestHandler:     (ChainId, String, ujson.Value) => Future[ujson.Value],
  projectId:          String,
  val walletMetadata: WcAppMetadata,
)(using ec: ExecutionContext) extends DappConnector:

  def protocol: String = "walletconnect-v2"

  @volatile private var attached = false
  private val sessions = scala.collection.mutable.Map.empty[String, WcSession]

  def attach(unused: AccountManager): Unit =
    attached = true
    transport.onInbound(handleInbound)
    transport.connect(projectId)

  def detach(): Unit =
    attached = false
    transport.close()

  // ── inbound handling ─────────────────────────────────────────────────

  private def handleInbound(msg: WcInbound): Unit = msg match
    case WcInbound.Proposal(p)             => handleProposal(p)
    case WcInbound.Request(r)              => handleRequest(r)
    case WcInbound.SessionDelete(t, _)     => sessions.remove(t); transport.unsubscribe(t)
    case WcInbound.SessionUpdate(t, ns)    =>
      sessions.get(t).foreach(s => sessions(t) = s.copy(namespaces = ns))
    case WcInbound.Ping(_)                 => // no-op; the relay handles pong

  /** Default proposal policy: approve when the required namespaces are
   *  a subset of what the wallet currently supports. Hosts that want a
   *  user-approval prompt subclass and override `evaluateProposal`. */
  protected def evaluateProposal(p: WcSessionProposal): Either[String, Map[String, WcNamespace]] =
    val supportedChains = manager.chains
    val approvedNs      = scala.collection.mutable.Map.empty[String, WcNamespace]
    val rejection       = p.requiredNamespaces.iterator.flatMap { case (nsKey, requested) =>
      val missing = requested.chains.filterNot(supportedChains.contains)
      if missing.nonEmpty then
        Some(s"unsupported chains in namespace $nsKey: ${missing.mkString(", ")}")
      else
        val accounts = requested.chains.flatMap { c =>
          // Build a placeholder CAIP-10; in production the host populates
          // with the result of strategy.getAddress(adapter).
          manager.strategyFor(c).map(_ => AccountId(c, ""))
        }
        approvedNs(nsKey) = requested.copy(accounts = accounts)
        None
    }.nextOption()
    rejection match
      case Some(reason) => Left(reason)
      case None         => Right(approvedNs.toMap)

  private def handleProposal(p: WcSessionProposal): Unit =
    evaluateProposal(p) match
      case Left(reason) =>
        transport.send(WcOutbound.RejectSession(
          topic    = p.pairingTopic,
          response = WcSessionResponse(
            proposalId = p.id, approved = false, reason = reason,
          ),
        ))
      case Right(approvedNs) =>
        val topic = newSessionTopic(p)
        val session = WcSession(
          topic        = topic,
          expiry       = System.currentTimeMillis() / 1000 + 7 * 24 * 3600,  // 7d
          acknowledged = true,
          peer         = p.proposer,
          namespaces   = approvedNs,
        )
        sessions(topic) = session
        transport.subscribe(topic)
        transport.send(WcOutbound.ApproveSession(
          topic    = p.pairingTopic,
          response = WcSessionResponse(
            proposalId   = p.id,
            approved     = true,
            namespaces   = approvedNs,
            sessionTopic = Some(topic),
          ),
        ))

  private def handleRequest(r: WcSessionRequest): Unit =
    sessions.get(r.topic) match
      case None =>
        transport.send(WcOutbound.RequestResult(
          topic  = r.topic,
          result = WcSessionResult.Error(r.id, -32601, s"Unknown session topic ${r.topic}"),
        ))
      case Some(_) =>
        requestHandler(r.chainId, r.method, r.params).onComplete {
          case scala.util.Success(value) =>
            transport.send(WcOutbound.RequestResult(
              topic = r.topic, result = WcSessionResult.Ok(r.id, value),
            ))
          case scala.util.Failure(ex) =>
            transport.send(WcOutbound.RequestResult(
              topic  = r.topic,
              result = WcSessionResult.Error(r.id, -32603, ex.getMessage),
            ))
        }

  // ── helpers ────────────────────────────────────────────────────────────

  /** Deterministic-but-unique session topic. Production transports
   *  derive this from the X25519 symmetric key handshake; here we hash
   *  the proposal id + pairing topic for testability. The transport
   *  layer can override (set its own derived topic before subscribing). */
  protected def newSessionTopic(p: WcSessionProposal): String =
    s"session-${p.id}-${p.pairingTopic.takeRight(8)}"

  /** Inspect active sessions — used by hosts to surface "connected
   *  dApps" UI. */
  def activeSessions: Seq[WcSession] = sessions.values.toSeq

  /** Whether the connector is currently attached. Helpful for tests. */
  def isAttached: Boolean = attached
