package scalascript.oidc

import scalascript.oauth.*
import java.util.concurrent.ConcurrentHashMap

/** v1.17.x — OpenID Connect (OIDC) Identity Provider layer on top of
 *  `scalascript.oauth.AuthServer`.  Adds the two OIDC-specific
 *  capabilities that an OAuth 2.1 AS lacks out of the box:
 *
 *    1. `id_token` minting — when the granted scope includes `openid`
 *       the token response carries an HMAC-signed JWT with the
 *       standard identity claims (`iss`, `sub`, `aud`, `exp`, `iat`,
 *       and any profile/email claims unlocked by the matching scope).
 *
 *    2. `/userinfo` endpoint — bearer-validated; returns the subject's
 *       claim set filtered by the access token's scope per spec
 *       §5.4 (`profile` → name+picture+locale, `email` → email +
 *       email_verified, etc.).
 *
 *  Decoupled from the AS by composition (`oidc.as`) — same AS can be
 *  used for plain OAuth without OIDC opt-in.  Tokens are still signed
 *  by the AS's `signingSecret`; production setups should swap in a
 *  JWKS-backed signer when that lands. */
class OidcServer(
  val as:        AuthServer,
  val userInfo:  UserInfoStore  = new InMemoryUserInfoStore,
  /** Names of the claims the IdP advertises in `claims_supported`. */
  val supportedClaims: Set[String] = Set(
    "sub", "name", "email", "email_verified", "picture", "locale", "preferred_username"
  )
):

  /** OIDC version of `as.issueToken`.  When the granted scope includes
   *  `openid` and the grant flow has a user subject (auth code +
   *  refresh, not client credentials), this wraps the AS reply with
   *  an `id_token`.  Otherwise behaves exactly like the AS. */
  def issueToken(req: TokenRequest): TokenOutcome =
    as.issueToken(req) match
      case err: TokenOutcome.Error => err
      case TokenOutcome.Issued(resp) =>
        if !resp.scope.contains("openid") then TokenOutcome.Issued(resp)
        else identityFor(resp.accessToken) match
          case None => TokenOutcome.Issued(resp)  // can't lift identity → no id_token
          case Some((subject, clientId)) =>
            val idTok = mintIdToken(subject, clientId, resp.scope)
            TokenOutcome.Issued(resp.copy(idToken = Some(idTok)))

  /** Decode the access token to surface (subject, client_id).  Returns
   *  None for tokens that don't decode (e.g. external opaque tokens —
   *  OIDC isn't supported for those in v1) or those without a `client_id`
   *  claim (e.g. client_credentials grant — no end user). */
  def identityFor(accessToken: String): Option[(String, String)] =
    as.signer.verify(accessToken).toOption.flatMap { p =>
      for
        sub <- p.obj.get("sub").flatMap(_.strOpt)
        cid <- p.obj.get("client_id").flatMap(_.strOpt)
        if sub != cid  // skip client_credentials — sub == cid means no real user
      yield (sub, cid)
    }

  /** Build a signed id_token (JWT) for the given subject + audience.
   *  `scope` controls which profile/email claims get embedded; the
   *  always-present claims are `iss / sub / aud / exp / iat`. */
  def mintIdToken(subject: String, clientId: String, scope: Set[String]): String =
    val claims = userInfo.find(subject) match
      case Some(u) => u.toClaims(scope).obj
      case None    => ujson.Obj("sub" -> subject).obj
    // v1.17.x — surface the OIDC `nonce` captured at /authorize time.
    // Required by the spec when the client supplied one; defeats
    // replay of the id_token against a different authorize request.
    val extra = ujson.Obj.from(claims.iterator.filter((k, _) => k != "sub").toMap)
    as.consumeNonceForSubject(subject, scope).foreach(n => extra("nonce") = n)
    // Identity claims live alongside the JWT-mandatory iss/aud/exp/iat
    // pair.  Signs via the AS's `signer` so an RSA-backed AS produces
    // RS256 id_tokens automatically.
    as.signer.sign(OAuth.buildAccessTokenPayload(
      subject          = subject,
      scopes           = Set.empty,              // id_token doesn't carry the OAuth scope claim
      expiresInSeconds = as.config.accessTokenTtlSeconds,
      issuer           = Some(as.config.issuer),
      audience         = Some(clientId),
      extra            = extra
    ))

  /** Handle a `/userinfo` request.  Validates the bearer token via the
   *  AS's token validator (so revocation + expiry are honoured), looks
   *  up the matching subject, and emits claims filtered by the token's
   *  scope.  Spec §5.3: 401 when token is missing/invalid, 200 with
   *  the claim set when valid. */
  def userInfoFor(accessToken: String): UserInfoOutcome =
    as.tokenValidator(accessToken) match
      case OAuth.AuthResult.Invalid(code, descr) =>
        UserInfoOutcome.Unauthorized(code, descr)
      case OAuth.AuthResult.Valid(claims) =>
        userInfo.find(claims.subject) match
          case None    => UserInfoOutcome.NotFound(claims.subject)
          case Some(u) => UserInfoOutcome.Found(u.toClaims(claims.scopes))

  /** OIDC discovery document — extends the AS's RFC 8414 metadata with
   *  OIDC-specific fields (`subject_types_supported`, `claims_supported`,
   *  `id_token_signing_alg_values_supported`, plus the `/userinfo`
   *  endpoint). */
  def discoveryJson(userInfoEndpoint: String = "/userinfo"): ujson.Value =
    val base = as.config.issuer.stripSuffix("/")
    val obj  = as.metadataJson()
    obj("userinfo_endpoint")                       = base + userInfoEndpoint
    obj("subject_types_supported")                 = ujson.Arr(ujson.Str("public"))
    obj("id_token_signing_alg_values_supported")   = ujson.Arr(ujson.Str(as.signer.alg))
    obj("claims_supported") = ujson.Arr.from(
      ("iss" +: "aud" +: "exp" +: "iat" +: supportedClaims.toList).distinct.sorted.map(ujson.Str(_))
    )
    // OIDC scopes that gate matching claim disclosure on /userinfo
    obj("scopes_supported") = ujson.Arr.from(
      ((as.config.supportedScopes ++ Set("openid", "profile", "email")).toList.sorted).map(ujson.Str(_))
    )
    obj

// ─── Domain types ────────────────────────────────────────────────────

/** Identity attributes for one subject.  Scope-based filtering on
 *  `toClaims(scopes)` matches the standard OIDC claim families:
 *    `openid`   → sub (always)
 *    `profile`  → name, picture, locale, preferred_username
 *    `email`    → email, email_verified
 *  Extra claims (custom user attributes) are emitted unconditionally
 *  when the access token grants any scope at all — typical IdPs use
 *  this for app-specific roles / groups data. */
case class UserClaims(
  subject:           String,
  name:              Option[String]  = None,
  email:             Option[String]  = None,
  emailVerified:     Option[Boolean] = None,
  picture:           Option[String]  = None,
  locale:            Option[String]  = None,
  preferredUsername: Option[String]  = None,
  extra:             ujson.Value     = ujson.Obj()
):
  def toClaims(scopes: Set[String]): ujson.Value =
    val obj = ujson.Obj("sub" -> subject)
    if scopes.contains("profile") then
      name.foreach            (n => obj("name")               = n)
      picture.foreach         (p => obj("picture")            = p)
      locale.foreach          (l => obj("locale")             = l)
      preferredUsername.foreach(u => obj("preferred_username") = u)
    if scopes.contains("email") then
      email.foreach           (e => obj("email")              = e)
      emailVerified.foreach   (v => obj("email_verified")     = ujson.Bool(v))
    extra match
      case e: ujson.Obj => e.value.foreach((k, v) => obj(k) = v)
      case _            => ()
    obj

enum UserInfoOutcome:
  /** 200 OK with the filtered claim set. */
  case Found(claims: ujson.Value)
  /** 401 — bearer token invalid / expired / revoked. */
  case Unauthorized(code: String, description: String)
  /** Subject is gone from the store after token issuance.  Treat as
   *  401 on the wire per spec (no user, no info). */
  case NotFound(subject: String)

trait UserInfoStore:
  def find(subject: String): Option[UserClaims]
  def put(claims:  UserClaims): Unit
  def all: List[UserClaims]

class InMemoryUserInfoStore extends UserInfoStore:
  private val m = ConcurrentHashMap[String, UserClaims]()
  def find(subject: String): Option[UserClaims] = Option(m.get(subject))
  def put(claims:  UserClaims): Unit            = m.put(claims.subject, claims)
  def all: List[UserClaims] = scala.jdk.CollectionConverters.IteratorHasAsScala(
                                m.values().iterator()).asScala.toList
