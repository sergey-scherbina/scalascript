package scalascript.interpreter

import scalascript.backend.spi.{IntrinsicImpl, NativeImpl}
import scalascript.ir.QualifiedName
import scalascript.oauth.*
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable

/** v1.17.x — script-side intrinsics for the OAuth client SDK.
 *  Mirrors `scalascript.oauth.OAuthClient` JVM API for `.ssc` apps —
 *  the third OAuth role (Client) was reachable only from Scala until
 *  this iteration.  Exposed under the `oauth.client.*` namespace via
 *  the existing companion-object pattern (see `Interpreter.scala`). */
val OAuthClientIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(

  // ─── Discovery ─────────────────────────────────────────────────────

  QualifiedName("oauth.client.discoverAs") -> NativeImpl((_, args) =>
    args match
      case List(issuer: String) =>
        OAuthClientIntrinsicHelpers.ujsonToValue(OAuthClient.discoverAs(issuer))
      case _ => throw InterpretError("oauth.client.discoverAs(issuer)")
  ),
  QualifiedName("oauth.client.discoverRs") -> NativeImpl((_, args) =>
    args match
      case List(resourceUrl: String) =>
        OAuthClientIntrinsicHelpers.ujsonToValue(OAuthClient.discoverRs(resourceUrl))
      case _ => throw InterpretError("oauth.client.discoverRs(resourceUrl)")
  ),

  // ─── PKCE + state helpers ──────────────────────────────────────────

  QualifiedName("oauth.client.freshPkce") -> NativeImpl((_, _) =>
    val p = OAuthClient.freshPkce()
    Value.MapV(Map(
      Value.StringV("verifier")  -> Value.StringV(p.verifier),
      Value.StringV("challenge") -> Value.StringV(p.challenge),
      Value.StringV("method")    -> Value.StringV(p.method)
    ))
  ),
  QualifiedName("oauth.client.freshState") -> NativeImpl((_, _) =>
    Value.StringV(OAuthClient.freshState())
  ),
  QualifiedName("oauth.client.verifyState") -> NativeImpl((_, args) =>
    args match
      case List(a: String, b: String) =>
        Value.BoolV(OAuthClient.verifyState(a, b))
      case _ => throw InterpretError("oauth.client.verifyState(expected, presented)")
  ),

  // ─── Authorization URL builder ────────────────────────────────────

  /** Args: `(authorizationEndpoint, clientId, redirectUri, scopes,
   *         state, codeChallenge, codeChallengeMethod)`.  All
   *  positional + required for predictability. */
  QualifiedName("oauth.client.authorizationUrl") -> NativeImpl((_, args) =>
    args match
      case List(ep: String, cid: String, redirect: String, scopesV: Value,
                state: String, challenge: String, method: String) =>
        val scopes = OAuthIntrinsicHelpers.toStringSet(scopesV)
        Value.StringV(OAuthClient.authorizationUrl(
          authorizationEndpoint = ep,
          clientId              = cid,
          redirectUri           = redirect,
          scopes                = scopes,
          state                 = state,
          pkce                  = OAuthClient.PkcePair(verifier = "", challenge = challenge, method = method)
        ))
      case _ => throw InterpretError(
        "oauth.client.authorizationUrl(endpoint, clientId, redirectUri, scopes, state, challenge, method)")
  ),

  // ─── Token endpoint ───────────────────────────────────────────────

  /** Args: `(tokenEndpoint, clientId, redirectUri, code, verifier
   *         [, clientSecret])`. */
  QualifiedName("oauth.client.exchangeAuthorizationCode") -> NativeImpl((_, args) =>
    val (ep, cid, redirect, code, verifier, secret) = args match
      case List(ep: String, cid: String, redirect: String, code: String, verifier: String) =>
        (ep, cid, redirect, code, verifier, None)
      case List(ep: String, cid: String, redirect: String, code: String, verifier: String,
                secret: String) =>
        (ep, cid, redirect, code, verifier, Some(secret))
      case _ => throw InterpretError(
        "oauth.client.exchangeAuthorizationCode(endpoint, clientId, redirectUri, code, verifier[, secret])")
    OAuthClientIntrinsicHelpers.tokenResultToValue(
      OAuthClient.exchangeAuthorizationCode(ep, cid, redirect, code, verifier, secret))
  ),

  QualifiedName("oauth.client.refresh") -> NativeImpl((_, args) =>
    val (ep, cid, refresh, scopes, secret) = args match
      case List(ep: String, cid: String, refresh: String) =>
        (ep, cid, refresh, Set.empty[String], None)
      case List(ep: String, cid: String, refresh: String, scopesV: Value) =>
        (ep, cid, refresh, OAuthIntrinsicHelpers.toStringSet(scopesV), None)
      case List(ep: String, cid: String, refresh: String, scopesV: Value, secret: String) =>
        (ep, cid, refresh, OAuthIntrinsicHelpers.toStringSet(scopesV), Some(secret))
      case _ => throw InterpretError(
        "oauth.client.refresh(endpoint, clientId, refreshToken[, scopes][, secret])")
    OAuthClientIntrinsicHelpers.tokenResultToValue(
      OAuthClient.refresh(ep, cid, refresh, scopes, secret))
  ),

  QualifiedName("oauth.client.clientCredentials") -> NativeImpl((_, args) =>
    val (ep, cid, secret, scopes) = args match
      case List(ep: String, cid: String, secret: String) =>
        (ep, cid, secret, Set.empty[String])
      case List(ep: String, cid: String, secret: String, scopesV: Value) =>
        (ep, cid, secret, OAuthIntrinsicHelpers.toStringSet(scopesV))
      case _ => throw InterpretError(
        "oauth.client.clientCredentials(endpoint, clientId, secret[, scopes])")
    OAuthClientIntrinsicHelpers.tokenResultToValue(
      OAuthClient.clientCredentials(ep, cid, secret, scopes))
  ),

  // ─── TokenHolder handle (mutable — uses stable-id registry) ───────

  /** Args: `(tokenEndpoint, clientId[, refreshLeadSeconds][, clientSecret])`.
   *  Returns an InstanceV with .seed(tokens) / .current() / .clear()
   *  methods that bridge to the underlying JVM TokenHolder.  Mirrors
   *  the same registry pattern AuthServer + OidcServer use. */
  QualifiedName("oauth.client.tokenHolder") -> NativeImpl((_, args) =>
    val (ep, cid, leadSec, secret) = args match
      case List(ep: String, cid: String) =>
        (ep, cid, 60L, None)
      case List(ep: String, cid: String, leadSec: Long) =>
        (ep, cid, leadSec, None)
      case List(ep: String, cid: String, leadSec: Long, secret: String) =>
        (ep, cid, leadSec, Some(secret))
      case _ => throw InterpretError(
        "oauth.client.tokenHolder(endpoint, clientId[, refreshLeadSeconds][, secret])")
    OAuthClientIntrinsicHelpers.makeTokenHolderInstance(
      new OAuthClient.TokenHolder(ep, cid, secret, leadSec))
  )
)

/** Helpers — kept separate so the intrinsic map stays a single `val`. */
object OAuthClientIntrinsicHelpers:

  private val holderRegistry = ConcurrentHashMap[String, OAuthClient.TokenHolder]()

  /** Decode a TokenResult into a script-friendly Map.
   *    Issued → { ok: true, accessToken, tokenType, expiresIn,
   *               refreshToken?, idToken?, scope, raw }
   *    Error  → { ok: false, error, description, raw } */
  def tokenResultToValue(r: OAuthClient.TokenResult): Value = r match
    case OAuthClient.TokenResult.Issued(t, raw) =>
      Value.MapV(Map(
        (Value.StringV("ok"):           Value) -> (Value.BoolV(true):                  Value),
        (Value.StringV("accessToken"):  Value) -> (Value.StringV(t.accessToken):       Value),
        (Value.StringV("tokenType"):    Value) -> (Value.StringV(t.tokenType):         Value),
        (Value.StringV("expiresIn"):    Value) -> (Value.IntV(t.expiresIn):            Value),
        (Value.StringV("refreshToken"): Value) -> Value.OptionV(t.refreshToken.map(Value.StringV(_))),
        (Value.StringV("idToken"):      Value) -> Value.OptionV(t.idToken.map(Value.StringV(_))),
        (Value.StringV("scope"):        Value) -> Value.ListV(t.scope.toList.sorted.map(Value.StringV(_))),
        (Value.StringV("raw"):          Value) -> ujsonToValue(raw)
      ))
    case OAuthClient.TokenResult.Error(err, descr, raw) =>
      Value.MapV(Map(
        (Value.StringV("ok"):          Value) -> (Value.BoolV(false):           Value),
        (Value.StringV("error"):       Value) -> (Value.StringV(err):           Value),
        (Value.StringV("description"): Value) -> (Value.StringV(descr):         Value),
        (Value.StringV("raw"):         Value) -> ujsonToValue(raw)
      ))

  /** Build the TokenHolder InstanceV with .seed / .current / .clear
   *  bridge methods.  `_id` lets future intrinsics resolve back to
   *  the JVM holder (e.g. for an `mcpConnect(..., holder)` overload
   *  that auto-wires the holder's current() as the bearer). */
  def makeTokenHolderInstance(h: OAuthClient.TokenHolder): Value =
    val id = "th-" + OAuth.randomOpaqueToken(12)
    holderRegistry.put(id, h)
    val fields = mutable.LinkedHashMap.empty[String, Value]
    fields("_id") = Value.StringV(id)

    fields("seed") = Value.NativeFnV("TokenHolder.seed", Computation.pureFn {
      case List(tokensV) =>
        h.seed(decodeTokens(tokensV))
        Value.UnitV
      case _ => throw InterpretError("holder.seed(tokens)")
    })

    fields("current") = Value.NativeFnV("TokenHolder.current", Computation.pureFn { _ =>
      h.current() match
        case Some(t) => Value.OptionV(Some(Value.StringV(t)))
        case None    => Value.OptionV(None)
    })

    fields("clear") = Value.NativeFnV("TokenHolder.clear", Computation.pureFn { _ =>
      h.clear(); Value.UnitV
    })

    Value.InstanceV("TokenHolder", fields.toMap)

  /** Resolve a TokenHolder handle back to its JVM instance. */
  def resolveTokenHolder(v: Value): Option[OAuthClient.TokenHolder] = v match
    case Value.InstanceV("TokenHolder", fs) =>
      fs.get("_id").collect { case Value.StringV(id) => id }
        .flatMap(id => Option(holderRegistry.get(id)))
    case _ => None

  /** Decode a script-side tokens record (Map or InstanceV) into the
   *  `OAuthClient.Tokens` shape expected by `holder.seed`. */
  def decodeTokens(v: Value): OAuthClient.Tokens =
    val fs = v match
      case Value.MapV(m)         => m.iterator.collect { case (Value.StringV(k), v) => k -> v }.toMap
      case Value.InstanceV(_, m) => m
      case _ => throw InterpretError("holder.seed: expected Map / record of tokens")
    val access  = fs.get("accessToken").collect { case Value.StringV(s) => s }
      .getOrElse(throw InterpretError("holder.seed: missing 'accessToken'"))
    val ttype   = fs.get("tokenType").collect { case Value.StringV(s) => s }.getOrElse("Bearer")
    val expIn   = fs.get("expiresIn").collect {
      case Value.IntV(i)    => i
      case Value.DoubleV(d) => d.toLong
    }.getOrElse(3600L)
    val refresh = fs.get("refreshToken").collect {
      case Value.OptionV(Some(Value.StringV(s))) => s
      case Value.StringV(s)                       => s
    }
    val idTok = fs.get("idToken").collect {
      case Value.OptionV(Some(Value.StringV(s))) => s
      case Value.StringV(s)                       => s
    }
    val scopeSet = fs.get("scope").map {
      case Value.ListV(xs)  => xs.collect { case Value.StringV(s) => s }.toSet
      case Value.StringV(s) => s.split(' ').iterator.filter(_.nonEmpty).toSet
      case _                => Set.empty[String]
    }.getOrElse(Set.empty[String])
    OAuthClient.Tokens(access, ttype, expIn, refresh, idTok, scopeSet)

  def ujsonToValue(v: ujson.Value): Value = v match
    case ujson.Null    => Value.OptionV(None)
    case ujson.True    => Value.BoolV(true)
    case ujson.False   => Value.BoolV(false)
    case ujson.Str(s)  => Value.StringV(s)
    case ujson.Num(n)  if n == n.toLong.toDouble => Value.IntV(n.toLong)
    case ujson.Num(n)  => Value.DoubleV(n)
    case ujson.Arr(xs) => Value.ListV(xs.iterator.map(ujsonToValue).toList)
    case ujson.Obj(kv) => Value.MapV(kv.iterator.map((k, v) =>
                            (Value.StringV(k): Value) -> ujsonToValue(v)).toMap)
