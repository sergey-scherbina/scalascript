package scalascript.interpreter

import scalascript.backend.spi.{IntrinsicImpl, NativeImpl, NativeContext}
import scalascript.ir.QualifiedName
import scalascript.interpreter.intrinsics.OAuthHttp
import scalascript.oauth.*
import scalascript.oidc.OidcServer
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable

/** v1.17.x — script-side OAuth intrinsics.  Mirrors `Mcp.scala` for the
 *  authorization-server half of the spec.
 *
 *  Top-level entry points exposed to .ssc scripts:
 *    oauth.authServer(config)                — make an AS handle
 *    oauth.serveAuthServer(as[, basePath])   — install routes on
 *                                              the embedded WebServer
 *
 *  Methods on the returned `InstanceV("AuthServer", ...)` cover the
 *  programmatic surface:
 *    as.registerClient(metadata): Map           — RFC 7591 DCR
 *    as.issueClientCredentialsToken(            — test/integration helper
 *      clientId, clientSecret, scopes
 *    ): String
 *    as.introspect(token): Map                  — RFC 7662 shape
 *    as.revokeToken(token): Unit
 *    as.metadata(): Map                          — RFC 8414 doc
 *
 *  MCP integration: `srv.useAuthServer(asValue)` reads the AS handle's
 *  `_id` field, looks up the JVM instance in the registry, and wires
 *  its tokenValidator + metadata into the MCP server. */
val OAuthIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(

  // ─── oauth.authServer(config) ─────────────────────────────────────

  QualifiedName("oauth.authServer") -> NativeImpl((_, args) =>
    args match
      case List(cfg) =>
        val as = OAuthIntrinsicHelpers.buildAuthServer(cfg)
        OAuthIntrinsicHelpers.makeAuthServerInstance(as)
      case _ => throw InterpretError("oauth.authServer(config)")
  ),

  // ─── oauth.serveAuthServer(as[, basePath]) ────────────────────────

  QualifiedName("oauth.serveAuthServer") -> NativeImpl((ctx, args) =>
    args match
      case List(asValue)                       =>
        OAuthIntrinsicHelpers.serveAuthServer(asValue, "", ctx); Value.UnitV
      case List(asValue, basePath: String)     =>
        OAuthIntrinsicHelpers.serveAuthServer(asValue, basePath, ctx); Value.UnitV
      case List(asValue, Value.StringV(bp))    =>
        OAuthIntrinsicHelpers.serveAuthServer(asValue, bp, ctx); Value.UnitV
      case _ => throw InterpretError("oauth.serveAuthServer(authServer[, basePath])")
  ),

  // ─── oauth.issueHmacToken(secret, subject, scopes, expSec) ────────

  /** Convenience: mint a test bearer token without needing a full AS.
   *  Useful for test fixtures and trusted-internal deployments. */
  QualifiedName("oauth.issueHmacToken") -> NativeImpl((_, args) =>
    args match
      case List(secret: String, subject: String, scopesV: Value, expSec: Long) =>
        Value.StringV(OAuth.issueHmacToken(
          secret, subject,
          OAuthIntrinsicHelpers.toStringSet(scopesV), expSec))
      case _ => throw InterpretError("oauth.issueHmacToken(secret, subject, scopes, expiresInSeconds)")
  ),

  // ─── oauth.pkceVerifier() / oauth.pkceChallenge(verifier) ─────────

  QualifiedName("oauth.pkceVerifier") -> NativeImpl((_, _) =>
    Value.StringV(OAuth.randomOpaqueToken(32))
  ),
  QualifiedName("oauth.pkceChallenge") -> NativeImpl((_, args) =>
    args match
      case List(v: String) => Value.StringV(OAuth.pkceS256(v))
      case _               => throw InterpretError("oauth.pkceChallenge(verifier)")
  ),

  // ─── oauth.guard(as[, scopes][, realm])(handler) — RS SDK ─────────

  /** Wrap a request handler in bearer-token validation.  The script
   *  passes an AuthServer handle (or a raw validator function via
   *  `oauth.guardWithValidator`); the SDK extracts the bearer from
   *  `req.headers`, runs it through `as.tokenValidator`, checks
   *  required scopes, and either invokes the user's handler with the
   *  decoded `AuthClaims` or returns a 401/403 Response.
   *
   *  Usage (one-shot curried form so the handler reads naturally):
   *  ```
   *  val protected = oauth.guard(as, List("read")) { (req, claims) =>
   *    Response.json(Map("hello" -> claims.subject))
   *  }
   *  route("GET", "/api/data", protected)
   *  ```
   */
  QualifiedName("oauth.guard") -> NativeImpl((ctx, args) =>
    val (asVal, scopes, realm) = args match
      case List(asVal)                                 => (asVal, Set.empty[String], "api")
      case List(asVal, scopesV)                        =>
        (asVal, OAuthIntrinsicHelpers.toStringSet(scopesV.asInstanceOf[Value]), "api")
      case List(asVal, scopesV, realm: String)         =>
        (asVal, OAuthIntrinsicHelpers.toStringSet(scopesV.asInstanceOf[Value]), realm)
      case _ => throw InterpretError("oauth.guard(authServer[, scopes][, realm])(handler)")
    val asValue: Value = asVal match
      case v: Value => v
      case _ => throw InterpretError("oauth.guard: first argument must be an AuthServer handle")
    val as = OAuthIntrinsicHelpers.resolveAuthServer(asValue).getOrElse(
      throw InterpretError("oauth.guard: argument is not an AuthServer (use oauth.authServer(...))"))
    OAuthIntrinsicHelpers.makeGuardCurry(as.tokenValidator, scopes, realm, ctx)
  ),

  /** Same shape as `oauth.guard` but takes a raw validator function
   *  (String => AuthResult instance) instead of an AS handle.  Use
   *  this when validating tokens issued by an external AS (e.g. via
   *  JWKS lookup wired through `oauth.hmacValidator`-like helpers). */
  QualifiedName("oauth.guardWithValidator") -> NativeImpl((ctx, args) =>
    val (validatorFn, scopes, realm) = args match
      case List(v)                                  => (v, Set.empty[String], "api")
      case List(v, scopesV)                         =>
        (v, OAuthIntrinsicHelpers.toStringSet(scopesV.asInstanceOf[Value]), "api")
      case List(v, scopesV, realm: String)          =>
        (v, OAuthIntrinsicHelpers.toStringSet(scopesV.asInstanceOf[Value]), realm)
      case _ => throw InterpretError("oauth.guardWithValidator(validator[, scopes][, realm])(handler)")
    val validator: OAuth.TokenValidator = token =>
      val res = ctx.invokeCallback(validatorFn, List(Value.StringV(token)))
      OAuthIntrinsicHelpers.valueToAuthResult(res match
        case v: Value => v
        case _        => Value.StringV(String.valueOf(res)))
    OAuthIntrinsicHelpers.makeGuardCurry(validator, scopes, realm, ctx)
  ),

  // ─── oauth.hmacValidator(secret) — convenience factory ────────────

  QualifiedName("oauth.hmacValidator") -> NativeImpl((_, args) =>
    args match
      case List(secret: String) =>
        // Wrap as a script-side function so users can pass it directly
        // into oauth.guardWithValidator and other APIs.
        val v = OAuth.hmacValidator(secret)
        Value.NativeFnV("oauth.hmacValidator.fn", Computation.pureFn {
          case List(Value.StringV(token)) =>
            OAuthIntrinsicHelpers.authResultToValue(v(token))
          case _ => throw InterpretError("validator(token)")
        })
      case _ => throw InterpretError("oauth.hmacValidator(secret)")
  ),

  // ─── oidc.server(as) — wrap an AuthServer with an Identity Provider ─

  QualifiedName("oidc.server") -> NativeImpl((_, args) =>
    args match
      case List(asValue) =>
        val v: Value = asValue match
          case v: Value => v
          case _ => throw InterpretError(
            "oidc.server: argument is not an AuthServer (use oauth.authServer(...) first)")
        OAuthIntrinsicHelpers.resolveAuthServer(v) match
          case None => throw InterpretError(
            "oidc.server: argument is not an AuthServer (use oauth.authServer(...) first)")
          case Some(as) =>
            val idp = new OidcServer(as)
            OidcIntrinsicHelpers.makeOidcServerInstance(idp)
      case _ => throw InterpretError("oidc.server(authServer)")
  ),

  // ─── oidc.serve(idp[, basePath]) — install all OIDC + OAuth routes ──

  QualifiedName("oidc.serve") -> NativeImpl((ctx, args) =>
    args match
      case List(idpValue: Value)                          =>
        OidcIntrinsicHelpers.serveOidc(idpValue, "", ctx); Value.UnitV
      case List(idpValue: Value, basePath: String)        =>
        OidcIntrinsicHelpers.serveOidc(idpValue, basePath, ctx); Value.UnitV
      case List(idpValue: Value, Value.StringV(bp))       =>
        OidcIntrinsicHelpers.serveOidc(idpValue, bp, ctx); Value.UnitV
      case _ => throw InterpretError("oidc.serve(idp[, basePath])")
  )
)

/** Private helpers — kept inside an object so the public intrinsic map
 *  stays a single `val`. */
object OAuthIntrinsicHelpers:

  /** JVM-side registry mapping stable id → live AuthServer instance.
   *  Scripts hold an `InstanceV("AuthServer", { _id: "...", methods })`;
   *  we look the JVM object back up by id to wire it through APIs that
   *  need the real reference (e.g. `srv.useAuthServer(asValue)`). */
  private val registry = ConcurrentHashMap[String, AuthServer]()

  /** Resolve an `InstanceV("AuthServer", ...)` back to the JVM
   *  AuthServer.  Returns None when the value isn't an AS handle or
   *  its id is stale (e.g. across process restarts). */
  def resolveAuthServer(v: Value): Option[AuthServer] = v match
    case Value.InstanceV("AuthServer", fields) =>
      fields.get("_id").collect { case Value.StringV(id) => id }
        .flatMap(id => Option(registry.get(id)))
    case _ => None

  /** Decode an AuthServer config from the script side.  Accepts:
   *    - MapV { issuer, signingSecret, scopes?, accessTokenTtl?, ... }
   *    - InstanceV (any tag) with the same field names */
  def buildAuthServer(cfg: Any): AuthServer =
    val fields = cfg match
      case Value.MapV(m) =>
        m.iterator.collect { case (Value.StringV(k), v) => k -> v }.toMap
      case Value.InstanceV(_, fs) => fs
      case _ => throw InterpretError("oauth.authServer: config must be a Map or record")
    val issuer = fields.get("issuer").collect { case Value.StringV(s) => s }
      .getOrElse(throw InterpretError("oauth.authServer: missing 'issuer'"))
    val secret = fields.get("signingSecret").collect { case Value.StringV(s) => s }
      .getOrElse(throw InterpretError("oauth.authServer: missing 'signingSecret'"))
    val scopes = fields.get("scopes").map(toStringSet).getOrElse(Set.empty)
    val accessTtl  = fields.get("accessTokenTtl").collect { case Value.IntV(i) => i }.getOrElse(3600L)
    val refreshTtl = fields.get("refreshTokenTtl").collect { case Value.IntV(i) => i }.getOrElse(86400L * 30)
    val codeTtl    = fields.get("authorizationCodeTtl").collect { case Value.IntV(i) => i }.getOrElse(600L)
    val pkce       = fields.get("requirePkce").collect { case Value.BoolV(b) => b }.getOrElse(true)
    val allowDcr   = fields.get("allowDynamicClientRegistration").collect { case Value.BoolV(b) => b }.getOrElse(true)
    new AuthServer(AuthServerConfig(
      issuer                          = issuer,
      signingSecret                   = secret,
      accessTokenTtlSeconds           = accessTtl,
      refreshTokenTtlSeconds          = refreshTtl,
      authorizationCodeTtlSeconds     = codeTtl,
      supportedScopes                 = scopes,
      requirePkce                     = pkce,
      allowDynamicClientRegistration  = allowDcr
    ))

  def toStringSet(v: Value): Set[String] = v match
    case Value.ListV(xs) => xs.collect { case Value.StringV(s) => s }.toSet
    case Value.StringV(s) => s.split(' ').iterator.filter(_.nonEmpty).toSet
    case _ => Set.empty

  /** Build the `InstanceV("AuthServer", ...)` that scripts pass around.
   *  All methods capture the JVM AS reference by closure; the `_id`
   *  field lets us recover the same reference from arbitrary intrinsics
   *  via the registry. */
  def makeAuthServerInstance(as: AuthServer): Value =
    val id = "as-" + OAuth.randomOpaqueToken(12)
    registry.put(id, as)
    val fields = mutable.LinkedHashMap.empty[String, Value]
    fields("_id") = Value.StringV(id)
    fields("issuer") = Value.StringV(as.config.issuer)

    // as.registerClient(metadata): Map — wraps RFC 7591 DCR
    fields("registerClient") = Value.NativeFnV("AuthServer.registerClient",
      Computation.pureFn {
        case List(metadataV) =>
          val js = valueToUjson(metadataV)
          as.registerClient(js) match
            case Right(client) =>
              ujsonToValue(as.registrationResponseJson(client))
            case Left(err) =>
              throw InterpretError(s"oauth.registerClient: $err")
        case _ => throw InterpretError("as.registerClient(metadata)")
      })

    // as.issueClientCredentialsToken(clientId, secret, scopes): String
    fields("issueClientCredentialsToken") =
      Value.NativeFnV("AuthServer.issueClientCredentialsToken", Computation.pureFn {
        case List(Value.StringV(cid), Value.StringV(sec), scopesV) =>
          as.issueToken(TokenRequest.ClientCredentialsGrant(
            cid, sec, toStringSet(scopesV))) match
            case TokenOutcome.Issued(resp) => Value.StringV(resp.accessToken)
            case TokenOutcome.Error(code, descr) =>
              throw InterpretError(s"oauth.issueClientCredentialsToken: $code: $descr")
        case _ => throw InterpretError("as.issueClientCredentialsToken(clientId, secret, scopes)")
      })

    // as.introspect(token): Map (RFC 7662 shape)
    fields("introspect") = Value.NativeFnV("AuthServer.introspect", Computation.pureFn {
      case List(Value.StringV(token)) => ujsonToValue(as.introspect(token).toJson)
      case _ => throw InterpretError("as.introspect(token)")
    })

    // as.revokeToken(token): Unit (RFC 7009)
    fields("revokeToken") = Value.NativeFnV("AuthServer.revokeToken", Computation.pureFn {
      case List(Value.StringV(token)) =>
        as.revokeToken(token); Value.UnitV
      case _ => throw InterpretError("as.revokeToken(token)")
    })

    // as.metadata(): Map (RFC 8414 discovery doc)
    fields("metadata") = Value.NativeFnV("AuthServer.metadata", Computation.pureFn {
      _ => ujsonToValue(as.metadataJson())
    })

    Value.InstanceV("AuthServer", fields.toMap)

  /** Install the OAuth HTTP routes on the embedded WebServer. */
  def serveAuthServer(asValue: Any, basePath: String, ctx: NativeContext): Unit =
    val as = asValue match
      case v: Value => resolveAuthServer(v).getOrElse(
        throw InterpretError("oauth.serveAuthServer: argument is not an AuthServer (use oauth.authServer(...))"))
      case _ => throw InterpretError("oauth.serveAuthServer(authServer[, basePath])")
    OAuthHttp.installRoutes(as, ctx, basePath)

  // ─── value/json conversions ─────────────────────────────────────────

  def ujsonToValue(v: ujson.Value): Value = v match
    case ujson.Null    => Value.OptionV(None)
    case ujson.True    => Value.BoolV(true)
    case ujson.False   => Value.BoolV(false)
    case ujson.Str(s)  => Value.StringV(s)
    case ujson.Num(n)  if n == n.toLong.toDouble => Value.IntV(n.toLong)
    case ujson.Num(n)  => Value.DoubleV(n)
    case ujson.Arr(xs) => Value.ListV(xs.iterator.map(ujsonToValue).toList)
    case ujson.Obj(kv) => Value.MapV(kv.iterator.map((k, v) =>
                            Value.StringV(k) -> ujsonToValue(v)).toMap)

  /** Build the curried second-stage function for `oauth.guard` /
   *  `oauth.guardWithValidator`.  Takes the user's `(req, claims) =>
   *  Response` callback, returns a `req => Response` wrapped handler
   *  the script can hand straight to `route(...)`. */
  def makeGuardCurry(
    validator: OAuth.TokenValidator,
    scopes:    Set[String],
    realm:     String,
    ctx:       scalascript.backend.spi.NativeContext
  ): Value = Value.NativeFnV("oauth.guard.curry", Computation.pureFn {
    case List(innerHandler) =>
      Value.NativeFnV("oauth.guard.wrapped", Computation.pureFn {
        case List(Value.InstanceV("Request", fields)) =>
          val headers = scalascript.interpreter.intrinsics.OAuthHttp.extractHeaderMap(fields)
          OAuthGuard.check(headers, validator, scopes, realm) match
            case OAuthGuard.GuardDecision.Allow(claims) =>
              ctx.invokeCallback(innerHandler,
                List(Value.InstanceV("Request", fields), authClaimsToValue(claims))) match
                case v: Value => v
                case other    => Value.StringV(String.valueOf(other))
            case OAuthGuard.GuardDecision.Deny(rout) =>
              scalascript.interpreter.intrinsics.OAuthHttp.routeOutcomeToValue(rout)
        case _ => Value.InstanceV("Response", Map(
          "status"  -> Value.IntV(400L),
          "headers" -> Value.MapV(Map.empty),
          "body"    -> Value.StringV("expected Request")))
      })
    case _ => throw InterpretError("guard(handler) — handler must be (req, claims) => Response")
  })

  /** Adapt AuthClaims to the script-side InstanceV shape. */
  def authClaimsToValue(c: OAuth.AuthClaims): Value =
    Value.InstanceV("AuthClaims", Map(
      "subject" -> Value.StringV(c.subject),
      "scopes"  -> Value.ListV(c.scopes.toList.sorted.map(s => Value.StringV(s))),
      "extra"   -> ujsonToValue(c.extra)
    ))

  /** Decode a script-side InstanceV / Map into a typed AuthResult.
   *  Recognises:
   *    InstanceV("Valid",   { subject, scopes, extra? })
   *    InstanceV("Invalid", { code, description })
   *    Map with the same field names
   *  Anything else collapses to Invalid("invalid_token", ...). */
  def valueToAuthResult(v: Value): OAuth.AuthResult = v match
    case Value.InstanceV("Valid", fs)   => decodeValidClaims(fs)
    case Value.InstanceV("Invalid", fs) => decodeInvalid(fs)
    case Value.MapV(m) =>
      val fs = m.iterator.collect { case (Value.StringV(k), vv) => k -> vv }.toMap
      fs.get("action").orElse(fs.get("kind")).collect { case Value.StringV(s) => s } match
        case Some("valid")    => decodeValidClaims(fs)
        case Some("invalid")  => decodeInvalid(fs)
        case _                => decodeValidClaims(fs)  // fall through to subject-shape
    case _ => OAuth.AuthResult.Invalid("invalid_token", s"validator returned unexpected: ${Value.show(v)}")

  private def decodeValidClaims(fs: Map[String, Value]): OAuth.AuthResult =
    val sub    = fs.get("subject").collect { case Value.StringV(s) => s }.getOrElse("")
    val scopes = fs.get("scopes").map(toStringSet).getOrElse(Set.empty)
    val extra  = fs.get("extra").map(valueToUjson).getOrElse(ujson.Obj())
    OAuth.AuthResult.Valid(OAuth.AuthClaims(sub, scopes, extra))
  private def decodeInvalid(fs: Map[String, Value]): OAuth.AuthResult =
    val code  = fs.get("code").collect { case Value.StringV(s) => s }.getOrElse("invalid_token")
    val descr = fs.get("description").collect { case Value.StringV(s) => s }.getOrElse("")
    OAuth.AuthResult.Invalid(code, descr)

  /** Inverse of `valueToAuthResult` — surface a Scala-side AuthResult
   *  to scripts. */
  def authResultToValue(r: OAuth.AuthResult): Value = r match
    case OAuth.AuthResult.Valid(c) =>
      Value.InstanceV("Valid", Map(
        "subject" -> Value.StringV(c.subject),
        "scopes"  -> Value.ListV(c.scopes.toList.sorted.map(s => Value.StringV(s))),
        "extra"   -> ujsonToValue(c.extra)
      ))
    case OAuth.AuthResult.Invalid(code, descr) =>
      Value.InstanceV("Invalid", Map(
        "code"        -> Value.StringV(code),
        "description" -> Value.StringV(descr)
      ))

  def valueToUjson(v: Value): ujson.Value = v match
    case Value.OptionV(None)    => ujson.Null
    case Value.OptionV(Some(x)) => valueToUjson(x)
    case Value.BoolV(b)         => if b then ujson.True else ujson.False
    case Value.StringV(s)       => ujson.Str(s)
    case Value.IntV(i)          => ujson.Num(i.toDouble)
    case Value.DoubleV(d)       => ujson.Num(d)
    case Value.ListV(xs)        => ujson.Arr.from(xs.map(valueToUjson))
    case Value.MapV(m)          =>
      val obj = ujson.Obj()
      m.foreach {
        case (Value.StringV(k), vv) => obj(k) = valueToUjson(vv)
        case _                       => ()
      }
      obj
    case Value.InstanceV(_, fs) =>
      val obj = ujson.Obj()
      fs.foreach((k, v) => obj(k) = valueToUjson(v))
      obj
    case _                      => ujson.Str(Value.show(v))
