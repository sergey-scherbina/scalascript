package scalascript.compiler.plugin.oauth

import scalascript.backend.spi.IntrinsicImpl
import scalascript.plugin.api.{HttpCap, MountCap}
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{OAuthBridge, PluginError, PluginValue}
import scalascript.plugin.api.PluginValue.{Str, Num, Dbl, Bool, Lst, MapVal, Inst, Opt}
import scalascript.oauth.*
import scalascript.oidc.OidcServer
import scala.collection.mutable
import scalascript.plugin.api.PluginNative

object OAuthIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("oauth.authServer") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(cfg) =>
          val as = OAuthIntrinsicHelpers.buildAuthServer(cfg)
          OAuthIntrinsicHelpers.makeAuthServerInstance(as)
        case _ => PluginError.raise("oauth.authServer(config)")
    },

    QualifiedName("oauth.serveAuthServer") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(asValue)                       =>
          OAuthIntrinsicHelpers.serveAuthServer(asValue, "", ctx); PluginValue.unit
        case List(asValue, basePath: String)     =>
          OAuthIntrinsicHelpers.serveAuthServer(asValue, basePath, ctx); PluginValue.unit
        case List(asValue, Str(bp))    =>
          OAuthIntrinsicHelpers.serveAuthServer(asValue, bp, ctx); PluginValue.unit
        case _ => PluginError.raise("oauth.serveAuthServer(authServer[, basePath])")
    },

    QualifiedName("oauth.issueHmacToken") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(secret: String, subject: String, scopesV, expSec: Long) =>
          PluginValue.string(OAuth.issueHmacToken(
            secret, subject,
            OAuthIntrinsicHelpers.toStringSet(scopesV), expSec))
        case _ => PluginError.raise("oauth.issueHmacToken(secret, subject, scopes, expiresInSeconds)")
    },

    QualifiedName("oauth.pkceVerifier") -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.string(OAuth.randomOpaqueToken(32))
    },
    QualifiedName("oauth.pkceChallenge") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(v: String) => PluginValue.string(OAuth.pkceS256(v))
        case _               => PluginError.raise("oauth.pkceChallenge(verifier)")
    },

    QualifiedName("oauth.guard") -> PluginNative.evalLegacy { (ctx, args) =>
      val (asVal, scopes, realm) = args match
        case List(asVal)                                 => (asVal, Set.empty[String], "api")
        case List(asVal, scopesV)                        =>
          (asVal, OAuthIntrinsicHelpers.toStringSet(scopesV), "api")
        case List(asVal, scopesV, realm: String)         =>
          (asVal, OAuthIntrinsicHelpers.toStringSet(scopesV), realm)
        case _ => PluginError.raise("oauth.guard(authServer[, scopes][, realm])(handler)")
      val asValue: PluginValue = asVal match
        case v if PluginValue.isRuntimeValue(v) => PluginValue.wrap(v)
        case _ => PluginError.raise("oauth.guard: first argument must be an AuthServer handle")
      val as = OAuthIntrinsicHelpers.resolveAuthServer(asValue).getOrElse(
        PluginError.raise("oauth.guard: argument is not an AuthServer (use oauth.authServer(...))"))
      OAuthIntrinsicHelpers.makeGuardCurry(as.tokenValidator, scopes, realm, ctx)
    },

    QualifiedName("oauth.guardWithValidator") -> PluginNative.evalLegacy { (ctx, args) =>
      val (validatorFn, scopes, realm) = args match
        case List(v)                                  => (v, Set.empty[String], "api")
        case List(v, scopesV)                         =>
          (v, OAuthIntrinsicHelpers.toStringSet(scopesV), "api")
        case List(v, scopesV, realm: String)          =>
          (v, OAuthIntrinsicHelpers.toStringSet(scopesV), realm)
        case _ => PluginError.raise("oauth.guardWithValidator(validator[, scopes][, realm])(handler)")
      val validator: OAuth.TokenValidator = token =>
        val res = ctx.invokeCallback(validatorFn, List(PluginValue.string(token)))
        OAuthIntrinsicHelpers.valueToAuthResult(res match
          case v if PluginValue.isRuntimeValue(v) => PluginValue.wrap(v)
          case _        => PluginValue.string(String.valueOf(res)))
      OAuthIntrinsicHelpers.makeGuardCurry(validator, scopes, realm, ctx)
    },

    QualifiedName("oauth.hmacValidator") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(secret: String) =>
          val v = OAuth.hmacValidator(secret)
          PluginValue.nativeFn("oauth.hmacValidator.fn", {
            case List(Str(token)) =>
              OAuthIntrinsicHelpers.authResultToValue(v(token))
            case _ => PluginError.raise("validator(token)")
          })
        case _ => PluginError.raise("oauth.hmacValidator(secret)")
    },

    QualifiedName("oidc.server") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(asValue) =>
          val v: PluginValue = asValue match
            case v if PluginValue.isRuntimeValue(v) => PluginValue.wrap(v)
            case _ => PluginError.raise(
              "oidc.server: argument is not an AuthServer (use oauth.authServer(...) first)")
          OAuthIntrinsicHelpers.resolveAuthServer(v) match
            case None => PluginError.raise(
              "oidc.server: argument is not an AuthServer (use oauth.authServer(...) first)")
            case Some(as) =>
              val idp = new OidcServer(as)
              OidcIntrinsicHelpers.makeOidcServerInstance(idp)
        case _ => PluginError.raise("oidc.server(authServer)")
    },

    QualifiedName("oidc.serve") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(idpValue)                          =>
          OidcIntrinsicHelpers.serveOidc(idpValue, "", ctx); PluginValue.unit
        case List(idpValue, basePath: String)        =>
          OidcIntrinsicHelpers.serveOidc(idpValue, basePath, ctx); PluginValue.unit
        case List(idpValue, Str(bp))       =>
          OidcIntrinsicHelpers.serveOidc(idpValue, bp, ctx); PluginValue.unit
        case _ => PluginError.raise("oidc.serve(idp[, basePath])")
    }
  )


object OAuthIntrinsicHelpers:

  private def registry = OAuthBridge.authServers

  def resolveAuthServer(v: Any): Option[AuthServer] = v match
    case Inst("AuthServer", fields) =>
      fields.get("_id").collect { case Str(id) => id }
        .flatMap(id => Option(registry.get(id)).collect { case as: AuthServer => as })
    case _ => None

  def buildAuthServer(cfg: Any): AuthServer =
    val fields = cfg match
      case MapVal(m) =>
        m.iterator.collect { case (Str(k), v) => k -> v }.toMap
      case Inst(_, fs) => fs
      case _ => PluginError.raise("oauth.authServer: config must be a Map or record")
    val issuer = fields.get("issuer").collect { case Str(s) => s }
      .getOrElse(PluginError.raise("oauth.authServer: missing 'issuer'"))
    val signerAlg = fields.get("signer").collect { case Str(s) => s }.getOrElse("HS256")
    val secret    = fields.get("signingSecret").collect { case Str(s) => s }.getOrElse {
      if signerAlg == "HS256" then
        PluginError.raise("oauth.authServer: missing 'signingSecret' (required for HS256)")
      else
        "unused-rsa-mode"
    }
    val scopes = fields.get("scopes").map(toStringSet).getOrElse(Set.empty)
    val accessTtl  = fields.get("accessTokenTtl").collect { case Num(i) => i }.getOrElse(3600L)
    val refreshTtl = fields.get("refreshTokenTtl").collect { case Num(i) => i }.getOrElse(86400L * 30)
    val codeTtl    = fields.get("authorizationCodeTtl").collect { case Num(i) => i }.getOrElse(600L)
    val pkce       = fields.get("requirePkce").collect { case Bool(b) => b }.getOrElse(true)
    val allowDcr   = fields.get("allowDynamicClientRegistration").collect { case Bool(b) => b }.getOrElse(true)
    val customSigner: Option[OAuth.TokenSigner] = signerAlg match
      case "HS256" => None
      case "RS256" =>
        val kid = fields.get("signingKid").collect { case Str(s) => s }.getOrElse("rsa-key-1")
        Some(OAuth.RsaTokenSigner.generate(kid))
      case other =>
        PluginError.raise(s"oauth.authServer: unknown signer '$other' (supported: HS256, RS256)")
    val dpopNonce = fields.get("dpopNonce").collect { case Num(i) => i }
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

  def toStringSet(v: Any): Set[String] = v match
    case Lst(xs) => xs.collect { case Str(s) => s }.toSet
    case Str(s) => s.split(' ').iterator.filter(_.nonEmpty).toSet
    case _ => Set.empty

  def makeAuthServerInstance(as: AuthServer): PluginValue =
    val id = "as-" + OAuth.randomOpaqueToken(12)
    registry.put(id, as: Any)
    val fields = mutable.LinkedHashMap.empty[String, PluginValue]
    fields("_id") = PluginValue.string(id)
    fields("issuer") = PluginValue.string(as.config.issuer)

    fields("registerClient") = PluginValue.nativeFn("AuthServer.registerClient",
      {
        case List(metadataV) =>
          val js = valueToUjson(metadataV)
          as.registerClient(js) match
            case Right(client) =>
              ujsonToValue(as.registrationResponseJson(client))
            case Left(err) =>
              PluginError.raise(s"oauth.registerClient: $err")
        case _ => PluginError.raise("as.registerClient(metadata)")
      })

    fields("issueClientCredentialsToken") =
      PluginValue.nativeFn("AuthServer.issueClientCredentialsToken", {
        case List(Str(cid), Str(sec), scopesV) =>
          as.issueToken(TokenRequest.ClientCredentialsGrant(
            cid, sec, toStringSet(scopesV))) match
            case TokenOutcome.Issued(resp) => PluginValue.string(resp.accessToken)
            case TokenOutcome.Error(code, descr) =>
              PluginError.raise(s"oauth.issueClientCredentialsToken: $code: $descr")
        case _ => PluginError.raise("as.issueClientCredentialsToken(clientId, secret, scopes)")
      })

    fields("introspect") = PluginValue.nativeFn("AuthServer.introspect", {
      case List(Str(token)) => ujsonToValue(as.introspect(token).toJson)
      case _ => PluginError.raise("as.introspect(token)")
    })

    fields("revokeToken") = PluginValue.nativeFn("AuthServer.revokeToken", {
      case List(Str(token)) =>
        as.revokeToken(token); PluginValue.unit
      case _ => PluginError.raise("as.revokeToken(token)")
    })

    fields("metadata") = PluginValue.nativeFn("AuthServer.metadata", {
      _ => ujsonToValue(as.metadataJson())
    })

    PluginValue.instance("AuthServer", fields.toMap)

  def serveAuthServer(asValue: Any, basePath: String, ctx: HttpCap): Unit =
    val as = asValue match
      case v if PluginValue.isRuntimeValue(v) => resolveAuthServer(v).getOrElse(
        PluginError.raise("oauth.serveAuthServer: argument is not an AuthServer (use oauth.authServer(...))"))
      case _ => PluginError.raise("oauth.serveAuthServer(authServer[, basePath])")
    OAuthHttp.installRoutes(as, ctx, basePath)

  def ujsonToValue(v: ujson.Value): PluginValue = v match
    case ujson.Null    => PluginValue.none
    case ujson.True    => PluginValue.bool(true)
    case ujson.False   => PluginValue.bool(false)
    case ujson.Str(s)  => PluginValue.string(s)
    case ujson.Num(n)  if n == n.toLong.toDouble => PluginValue.int(n.toLong)
    case ujson.Num(n)  => PluginValue.double(n)
    case ujson.Arr(xs) => PluginValue.list(xs.iterator.map(ujsonToValue).toList)
    case ujson.Obj(kv) => PluginValue.mapOf(kv.iterator.map((k, v) =>
                            PluginValue.string(k) -> ujsonToValue(v)).toMap)

  def makeGuardCurry(
    validator: OAuth.TokenValidator,
    scopes:    Set[String],
    realm:     String,
    ctx:       MountCap
  ): PluginValue = PluginValue.nativeFn("oauth.guard.curry", {
    case List(innerHandler) =>
      PluginValue.nativeFn("oauth.guard.wrapped", {
        case List(Inst("Request", fields)) =>
          val headers = OAuthHttp.extractHeaderMap(fields)
          OAuthGuard.check(headers, validator, scopes, realm) match
            case OAuthGuard.GuardDecision.Allow(claims) =>
              ctx.invokeCallback(innerHandler,
                List(PluginValue.instance("Request", fields), authClaimsToValue(claims))) match
                case v if PluginValue.isRuntimeValue(v) => PluginValue.wrap(v)
                case other    => PluginValue.string(String.valueOf(other))
            case OAuthGuard.GuardDecision.Deny(rout) =>
              OAuthHttp.routeOutcomeToValue(rout)
        case _ => PluginValue.instance("Response", Map(
          "status"  -> PluginValue.int(400L),
          "headers" -> PluginValue.mapOf(Map.empty[PluginValue, PluginValue]),
          "body"    -> PluginValue.string("expected Request")))
      })
    case _ => PluginError.raise("guard(handler) — handler must be (req, claims) => Response")
  })

  def authClaimsToValue(c: OAuth.AuthClaims): PluginValue =
    PluginValue.instance("AuthClaims", Map(
      "subject" -> PluginValue.string(c.subject),
      "scopes"  -> PluginValue.list(c.scopes.toList.sorted.map(s => PluginValue.string(s))),
      "extra"   -> ujsonToValue(c.extra)
    ))

  def valueToAuthResult(v: PluginValue): OAuth.AuthResult = v match
    case Inst("Valid", fs)   => decodeValidClaims(fs)
    case Inst("Invalid", fs) => decodeInvalid(fs)
    case MapVal(m) =>
      val fs = m.iterator.collect { case (Str(k), vv) => k -> vv }.toMap
      fs.get("action").orElse(fs.get("kind")).collect { case Str(s) => s } match
        case Some("valid")    => decodeValidClaims(fs)
        case Some("invalid")  => decodeInvalid(fs)
        case _                => decodeValidClaims(fs)
    case _ => OAuth.AuthResult.Invalid("invalid_token", s"validator returned unexpected: ${PluginValue.showAny(v)}")

  private def decodeValidClaims(fs: Map[String, PluginValue]): OAuth.AuthResult =
    val sub    = fs.get("subject").collect { case Str(s) => s }.getOrElse("")
    val scopes = fs.get("scopes").map(toStringSet).getOrElse(Set.empty)
    val extra  = fs.get("extra").map(valueToUjson).getOrElse(ujson.Obj())
    OAuth.AuthResult.Valid(OAuth.AuthClaims(sub, scopes, extra))

  private def decodeInvalid(fs: Map[String, PluginValue]): OAuth.AuthResult =
    val code  = fs.get("code").collect { case Str(s) => s }.getOrElse("invalid_token")
    val descr = fs.get("description").collect { case Str(s) => s }.getOrElse("")
    OAuth.AuthResult.Invalid(code, descr)

  def authResultToValue(r: OAuth.AuthResult): PluginValue = r match
    case OAuth.AuthResult.Valid(c) =>
      PluginValue.instance("Valid", Map(
        "subject" -> PluginValue.string(c.subject),
        "scopes"  -> PluginValue.list(c.scopes.toList.sorted.map(s => PluginValue.string(s))),
        "extra"   -> ujsonToValue(c.extra)
      ))
    case OAuth.AuthResult.Invalid(code, descr) =>
      PluginValue.instance("Invalid", Map(
        "code"        -> PluginValue.string(code),
        "description" -> PluginValue.string(descr)
      ))

  def valueToUjson(v: PluginValue): ujson.Value = v match
    case PluginValue.none    => ujson.Null
    case Opt(Some(inner)) => valueToUjson(inner)
    case Bool(b)         => if b then ujson.True else ujson.False
    case Str(s)       => ujson.Str(s)
    case Num(i)          => ujson.Num(i.toDouble)
    case Dbl(d)       => ujson.Num(d)
    case Lst(xs)        => ujson.Arr.from(xs.map(valueToUjson))
    case MapVal(m)          =>
      val obj = ujson.Obj()
      m.foreach {
        case (Str(k), vv) => obj(k) = valueToUjson(vv)
        case _                       => ()
      }
      obj
    case Inst(_, fs) =>
      val obj = ujson.Obj()
      fs.foreach((k, v) => obj(k) = valueToUjson(v))
      obj
    case _                      => ujson.Str(PluginValue.showAny(v))
