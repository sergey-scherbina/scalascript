package scalascript.mcp.wallet

import scalascript.blockchain.spi.ChainId
import scalascript.crypto.Curve

/** Consent mode for signing operations. Read-only tools always run
 *  without external approval; signing tools — when they land in
 *  Phase 2 — gate on this. */
enum ConfirmationMode:
  /** No external approval; intended only for session keys with strict
   *  allow-lists + amount caps. */
  case Implicit

  /** Server emits `elicitation/create` for every signing op, paused
   *  until the host satisfies it. Production default once signing
   *  tools land. */
  case ElicitationPerCall

  /** Like ElicitationPerCall but cached for a configurable TTL after
   *  the first approval. */
  case ElicitationCached(ttlSeconds: Int)

case class Budget(
  cap:          BigInt,
  consumedSoFar: BigInt = BigInt(0),
)

/** Host-controlled policy that gates what the mcp-wallet-server
 *  exposes and how it behaves. The Policy is constructed by the host
 *  and is **not** mutable via MCP — an MCP client cannot loosen
 *  policy. See docs/mcp-x402-wallet.md §5.3 / §9 for the security
 *  rationale. */
case class Policy(
  /** MCP client identifiers (origin / app id) allowed to talk to the
   *  server. Empty = unrestricted (suitable for local stdio where
   *  there is only one client by construction). */
  allowedOrigins: Set[String]      = Set.empty,
  /** Which `wallet.*` tools are exposed. Empty = expose all
   *  read-only tools; signing tools require explicit listing once
   *  Phase 2 lands. */
  allowedTools:   Set[String]      = Set.empty,
  /** Chains the wallet will report on. Empty = mirror
   *  `AccountManager.chains`. */
  allowedChains:  Set[ChainId]     = Set.empty,
  allowedCurves:  Set[Curve]       = Set(Curve.Secp256k1, Curve.Ed25519, Curve.P256),
  /** Per-chain per-call native-unit cap. Reserved for Phase 2
   *  signing tools. */
  maxPerCall:     Map[ChainId, BigInt] = Map.empty,
  sessionBudget:  Option[Budget]   = None,
  confirmation:   ConfirmationMode = ConfirmationMode.ElicitationPerCall,
):
  def exposes(tool: String): Boolean =
    allowedTools.isEmpty || allowedTools.contains(tool)

  def visibleChains(supported: Set[ChainId]): Set[ChainId] =
    if allowedChains.isEmpty then supported else allowedChains.intersect(supported)

object Policy:
  /** Reasonable read-only default — only the three read-only tools
   *  are exposed; signing tools are explicitly excluded. Suitable as
   *  the `fallback` for `PolicyProvider.FromAuth` when an
   *  unauthenticated / unrecognised request reaches the server. */
  def readOnly: Policy = Policy(
    allowedTools = Set("wallet.listAccounts", "wallet.getAddress", "wallet.getBalance"),
    confirmation = ConfirmationMode.Implicit, // no signing surface to confirm
  )
