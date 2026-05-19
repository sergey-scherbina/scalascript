package scalascript.wallet.walletconnect

import scalascript.blockchain.spi.{AccountId, ChainId}

/** WalletConnect v2 wire-protocol value types.
 *
 *  This module defines the *shape* of the protocol — the JSON-RPC
 *  methods a relay exchanges over an encrypted ws channel — without
 *  the relay/encryption machinery itself. A future commit plugs in
 *  the actual transport (JVM: JDK `WebSocket` + chacha20-poly1305 +
 *  X25519 pairing; Scala.js: facade over `@walletconnect/sign-client`).
 *
 *  Spec reference: https://docs.walletconnect.com/specs (sign API). */

/** A CAIP-2-keyed slice of capabilities that the wallet advertises (or
 *  the dApp requests) for a session — methods it'll honour, events it
 *  emits, chains it speaks for. */
case class WcNamespace(
  chains:   Seq[ChainId],
  methods:  Seq[String],
  events:   Seq[String],
  accounts: Seq[AccountId] = Seq.empty,
)

/** Incoming dApp proposal — sent by the dApp when scanning a wallet
 *  pairing URI. The wallet evaluates `requiredNamespaces` against its
 *  capabilities and either approves or rejects. */
case class WcSessionProposal(
  id:                    Long,
  pairingTopic:          String,
  proposer:              WcParticipant,
  requiredNamespaces:    Map[String, WcNamespace],
  optionalNamespaces:    Map[String, WcNamespace] = Map.empty,
  sessionProperties:     Map[String, String]      = Map.empty,
)

/** Wallet's response to a proposal. `approved=false` carries the
 *  `reason` for telemetry / dApp display. */
case class WcSessionResponse(
  proposalId: Long,
  approved:   Boolean,
  reason:     String                    = "",
  namespaces: Map[String, WcNamespace]  = Map.empty,
  sessionTopic: Option[String]          = None,
)

/** Metadata about a peer (dApp or wallet) — surfaced to the user in
 *  approval flows. */
case class WcParticipant(
  publicKey: String,
  metadata:  WcAppMetadata,
)

case class WcAppMetadata(
  name:        String,
  description: String,
  url:         String,
  icons:       Seq[String],
)

/** An active session after approval. */
case class WcSession(
  topic:      String,
  expiry:     Long,              // unix seconds
  acknowledged: Boolean,
  peer:       WcParticipant,
  namespaces: Map[String, WcNamespace],
)

/** A signing / send request inbound on an active session topic. The
 *  `request` payload mirrors EIP-1193 `request({method, params})`,
 *  so the wallet can route through `Eip1193Provider.request` for
 *  EVM chains (and equivalent translators for non-EVM). */
case class WcSessionRequest(
  id:      Long,
  topic:   String,
  chainId: ChainId,
  method:  String,
  params:  ujson.Value,
)

/** Wallet's response to a `WcSessionRequest`. */
sealed trait WcSessionResult
object WcSessionResult:
  case class Ok(id: Long, value: ujson.Value)                 extends WcSessionResult
  case class Error(id: Long, code: Int, message: String)      extends WcSessionResult

/** Inbound events the wallet must react to. */
sealed trait WcInbound
object WcInbound:
  case class Proposal(p: WcSessionProposal)                       extends WcInbound
  case class Request(r: WcSessionRequest)                         extends WcInbound
  case class SessionDelete(topic: String, reason: String)         extends WcInbound
  case class SessionUpdate(topic: String, namespaces: Map[String, WcNamespace]) extends WcInbound
  case class Ping(topic: String)                                  extends WcInbound

/** Outbound messages the wallet emits to the relay. */
sealed trait WcOutbound
object WcOutbound:
  case class ApproveSession(response: WcSessionResponse)           extends WcOutbound
  case class RejectSession(response: WcSessionResponse)            extends WcOutbound
  case class RequestResult(result: WcSessionResult)                extends WcOutbound
  case class EmitEvent(topic: String, name: String, data: ujson.Value, chain: ChainId) extends WcOutbound
  case class Disconnect(topic: String, reason: String)             extends WcOutbound
