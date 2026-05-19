package scalascript.mcp.wallet

import scalascript.oauth.OAuth

/** Per-request OAuth context propagation. Set by the host
 *  immediately before invoking a tool handler (typically inside an
 *  HTTP+SSE serve loop, after `OAuthGuard.check(...)` has produced
 *  `AuthClaims`); read by `McpWalletServer` to pick a per-claims
 *  `Policy` via the configured resolver.
 *
 *  Local-stdio deploys (a single trusted client) leave this unset
 *  and pass a static `Policy` to the server — that's the Phase 1
 *  path and remains the default.
 *
 *  Usage from a host:
 *  {{{
 *    val decision = OAuthGuard.check(reqHeaders, validator, scopes)
 *    decision match
 *      case GuardDecision.Allow(claims) =>
 *        McpWalletAuth.withClaims(claims) {
 *          // invoke tool handler
 *        }
 *      case GuardDecision.Deny(out) => writeResponse(out)
 *  }}}
 *  The `withClaims` helper sets + clears the thread-local so the
 *  context never leaks between requests on a re-used worker. */
object McpWalletAuth:

  private val tl: ThreadLocal[Option[OAuth.AuthClaims]] =
    new ThreadLocal[Option[OAuth.AuthClaims]]:
      override def initialValue(): Option[OAuth.AuthClaims] = None

  /** Currently-bound auth claims, or None for local-stdio /
   *  unauthenticated deploys. */
  def currentClaims(): Option[OAuth.AuthClaims] = tl.get()

  /** Run `body` with `claims` bound; clears the thread-local on
   *  exit even if `body` throws. */
  def withClaims[A](claims: OAuth.AuthClaims)(body: => A): A =
    val prev = tl.get()
    tl.set(Some(claims))
    try body
    finally tl.set(prev)

  /** Explicit clear — for hosts that manage the lifecycle outside
   *  the `withClaims` block. */
  def clear(): Unit = tl.set(None)

/** Strategy for selecting the `Policy` for an inbound request.
 *  Either static (local stdio: one wallet, one trust level) or
 *  resolved from the OAuth claims (HTTP+SSE: different OAuth scopes
 *  map to different policies — read-only for a public scope, full
 *  signing for a scope tied to a specific user / agent). */
sealed trait PolicyProvider:
  /** Effective policy for the current request. */
  def policyFor(claims: Option[OAuth.AuthClaims]): Policy

object PolicyProvider:
  /** One policy for every request — Phase 1 default. */
  case class Static(p: Policy) extends PolicyProvider:
    def policyFor(claims: Option[OAuth.AuthClaims]): Policy = p

  /** Policy chosen per-request from the OAuth claims. Unauthenticated
   *  requests (no claims in context) fall back to `fallback` — a
   *  read-only / deny-most policy is the sensible default. */
  case class FromAuth(
    resolver: OAuth.AuthClaims => Policy,
    fallback: Policy = Policy.readOnly,
  ) extends PolicyProvider:
    def policyFor(claims: Option[OAuth.AuthClaims]): Policy = claims match
      case Some(c) => resolver(c)
      case None    => fallback
