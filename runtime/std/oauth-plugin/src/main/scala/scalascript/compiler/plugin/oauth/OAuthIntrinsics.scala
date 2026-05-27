package scalascript.compiler.plugin.oauth

import scalascript.backend.spi.{IntrinsicImpl, NativeImpl, NativeContext}
import scalascript.ir.QualifiedName
import scalascript.interpreter.{Value, InterpretError, Computation, OAuthBridge}
import scalascript.oauth.*
import scalascript.oidc.OidcServer
import scala.collection.mutable

object OAuthIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("oauth.authServer") -> NativeImpl((_, args) =>
      args match
        case List(cfg) =>
          val as = OAuthIntrinsicHelpers.buildAuthServer(cfg)
          OAuthIntrinsicHelpers.makeAuthServerInstance(as)
        case _ => throw InterpretError("oauth.authServer(config)")
    ),

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

    QualifiedName("oauth.issueHmacToken") -> NativeImpl((_, args) =>
      args match
        case List(secret: String, subject: String, scopesV: Value, expSec: Long) =>
          Value.StringV(OAuth.issueHmacToken(
            secret, subject,
            OAuthIntrinsicHelpers.toStringSet(scopesV), expSec))
        case _ => throw InterpretError("oauth.issueHmacToken(secret, subject, scopes, expiresInSeconds)")
    ),

    QualifiedName("oauth.pkceVerifier") -> NativeImpl((_, _) =>
      Value.StringV(OAuth.randomOpaqueToken(32))
    ),
    QualifiedName("oauth.pkceChallenge") -> NativeImpl((_, args) =>
      args match
        case List(v: String) => Value.StringV(OAuth.pkceS256(v))
        case _               => throw InterpretError("oauth.pkceChallenge(verifier)")
    ),

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

    QualifiedName("oauth.hmacValidator") -> NativeImpl((_, args) =>
      args match
        case List(secret: String) =>
          val v = OAuth.hmacValidator(secret)
          Value.NativeFnV("oauth.hmacValidator.fn", Computation.pureFn {
            case List(Value.StringV(token)) =>
              OAuthIntrinsicHelpers.authResultToValue(v(token))
            case _ => throw InterpretError("validator(token)")
          })
        case _ => throw InterpretError("oauth.hmacValidator(secret)")
    ),

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


object OAuthIntrinsicHelpers:

  private def registry = OAuthBridge.authServers

  def resolveAuthServer(v: Value): Option[AuthServer] = v match
    case Value.InstanceV("AuthServer", fields) =>
      fields.get("_id").collect { case Value.StringV(id) => id }
        .flatMap(id => Option(registry.get(id)).collect { case as: AuthServer => as })
    case _ => None

  def buildAuthServer(cfg: Any): AuthServer =
    val fields = cfg match
      case Value.MapV(m) =>
        m.iterator.collect { case (Value.StringV(k), v) => k -> v }.toMap
      case Value.InstanceV(_, fs) => fs
      case _ => throw InterpretError("oauth.authServer: config must be a Map or record")
    val issuer = fields.get("issuer").collect { case Value.StringV(s) => s }
      .getOrElse(throw InterpretError("oauth.authServer: missing 'issuer'"))
    val signerAlg = fields.get("signer").collect { case Value.StringV(s) => s }.getOrElse("HS256")
    val secret    = fields.get("signingSecret").collect { case Value.StringV(s) => s }.getOrElse {
      if signerAlg == "HS256" then
        throw InterpretError("oauth.authServer: missing 'signingSecret' (required for HS256)")
      else
        "unused-rsa-mode"
    }
    val scopes = fields.get("scopes").map(toStringSet).getOrElse(Set.empty)
    val accessTtl  = fields.get("accessTokenTtl").collect { case Value.IntV(i) => i }.getOrElse(3600L)
    val refreshTtl = fields.get("refreshTokenTtl").collect { case Value.IntV(i) => i }.getOrElse(86400L * 30)
    val codeTtl    = fields.get("authorizationCodeTtl").collect { case Value.IntV(i) => i }.getOrElse(600L)
    val pkce       = fields.get("requirePkce").collect { case Value.BoolV(b) => b }.getOrElse(true)
    val allowDcr   = fields.get("allowDynamicClientRegistration").collect { case Value.BoolV(b) => b }.getOrElse(true)
    val customSigner: Option[OAuth.TokenSigner] = signerAlg match
      case "HS256" => None
      case "RS256" =>
        val kid = fields.get("signingKid").collect { case Value.StringV(s) => s }.getOrElse("rsa-key-1")
        Some(OAuth.RsaTokenSigner.generate(kid))
      case other =>
        throw InterpretError(s"oauth.authServer: unknown signer '$other' (supported: HS256, RS256)")
    val dpopNonce = fields.get("dpopNonce").collect { case Value.IntV(i) => i }
    new AuthServer(AuthServerConfig(
      issuer                          = issuer,
      signingSecret                   = secret,
      accessTokenTtlSeconds           = accessTtl,
      refreshTokenTtlSeconds          = refreshTtl,
      authorizationCodeTtlSeconds     = codeTtl,
      supportedScopes                 = scopes,
      requirePkce                     = pkce,
      allowDynamicClientRegistration  = allowDcr,
      dpopNonceLifetimeSeconds        = dpopNonce
    ), customSigner = customSigner)

  def toStringSet(v: Value): Set[String] = v match
    case Value.ListV(xs) => xs.collect { case Value.StringV(s) => s }.toSet
    case Value.StringV(s) => s.split(' ').iterator.filter(_.nonEmpty).toSet
    case _ => Set.empty

  def makeAuthServerInstance(as: AuthServer): Value =
    val id = "as-" + OAuth.randomOpaqueToken(12)
    registry.put(id, as: Any)
    val fields = mutable.LinkedHashMap.empty[String, Value]
    fields("_id") = Value.StringV(id)
    fields("issuer") = Value.StringV(as.config.issuer)

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

    fields("introspect") = Value.NativeFnV("AuthServer.introspect", Computation.pureFn {
      case List(Value.StringV(token)) => ujsonToValue(as.introspect(token).toJson)
      case _ => throw InterpretError("as.introspect(token)")
    })

    fields("revokeToken") = Value.NativeFnV("AuthServer.revokeToken", Computation.pureFn {
      case List(Value.StringV(token)) =>
        as.revokeToken(token); Value.UnitV
      case _ => throw InterpretError("as.revokeToken(token)")
    })

    fields("metadata") = Value.NativeFnV("AuthServer.metadata", Computation.pureFn {
      _ => ujsonToValue(as.metadataJson())
    })

    Value.InstanceV("AuthServer", fields.toMap)

  def serveAuthServer(asValue: Any, basePath: String, ctx: NativeContext): Unit =
    val as = asValue match
      case v: Value => resolveAuthServer(v).getOrElse(
        throw InterpretError("oauth.serveAuthServer: argument is not an AuthServer (use oauth.authServer(...))"))
      case _ => throw InterpretError("oauth.serveAuthServer(authServer[, basePath])")
    OAuthHttp.installRoutes(as, ctx, basePath)

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

  def makeGuardCurry(
    validator: OAuth.TokenValidator,
    scopes:    Set[String],
    realm:     String,
    ctx:       NativeContext
  ): Value = Value.NativeFnV("oauth.guard.curry", Computation.pureFn {
    case List(innerHandler) =>
      Value.NativeFnV("oauth.guard.wrapped", Computation.pureFn {
        case List(Value.InstanceV("Request", fields)) =>
          val headers = OAuthHttp.extractHeaderMap(fields)
          OAuthGuard.check(headers, validator, scopes, realm) match
            case OAuthGuard.GuardDecision.Allow(claims) =>
              ctx.invokeCallback(innerHandler,
                List(Value.InstanceV("Request", fields), authClaimsToValue(claims))) match
                case v: Value => v
                case other    => Value.StringV(String.valueOf(other))
            case OAuthGuard.GuardDecision.Deny(rout) =>
              OAuthHttp.routeOutcomeToValue(rout)
        case _ => Value.InstanceV("Response", Map(
          "status"  -> Value.IntV(400L),
          "headers" -> Value.MapV(Map.empty),
          "body"    -> Value.StringV("expected Request")))
      })
    case _ => throw InterpretError("guard(handler) — handler must be (req, claims) => Response")
  })

  def authClaimsToValue(c: OAuth.AuthClaims): Value =
    Value.InstanceV("AuthClaims", Map(
      "subject" -> Value.StringV(c.subject),
      "scopes"  -> Value.ListV(c.scopes.toList.sorted.map(s => Value.StringV(s))),
      "extra"   -> ujsonToValue(c.extra)
    ))

  def valueToAuthResult(v: Value): OAuth.AuthResult = v match
    case Value.InstanceV("Valid", fs)   => decodeValidClaims(fs)
    case Value.InstanceV("Invalid", fs) => decodeInvalid(fs)
    case Value.MapV(m) =>
      val fs = m.iterator.collect { case (Value.StringV(k), vv) => k -> vv }.toMap
      fs.get("action").orElse(fs.get("kind")).collect { case Value.StringV(s) => s } match
        case Some("valid")    => decodeValidClaims(fs)
        case Some("invalid")  => decodeInvalid(fs)
        case _                => decodeValidClaims(fs)
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
