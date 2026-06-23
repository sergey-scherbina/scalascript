package scalascript.compiler.plugin.oauth

import scalascript.backend.spi.IntrinsicImpl
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginError, PluginValue}
import scalascript.plugin.api.PluginValue.{Str, Num, Dbl, Lst, MapVal, Inst, Opt}
import scalascript.oauth.*
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scalascript.plugin.api.PluginNative

object OAuthClientIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("oauth.client.discoverAs") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(issuer: String) =>
          OAuthClientIntrinsicHelpers.ujsonToValue(OAuthClient.discoverAs(issuer))
        case _ => PluginError.raise("oauth.client.discoverAs(issuer)")
    },
    QualifiedName("oauth.client.discoverRs") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(resourceUrl: String) =>
          OAuthClientIntrinsicHelpers.ujsonToValue(OAuthClient.discoverRs(resourceUrl))
        case _ => PluginError.raise("oauth.client.discoverRs(resourceUrl)")
    },

    QualifiedName("oauth.client.freshPkce") -> PluginNative.evalLegacy { (_, _) =>
      val p = OAuthClient.freshPkce()
      PluginValue.mapOf(Map(
        PluginValue.string("verifier")  -> PluginValue.string(p.verifier),
        PluginValue.string("challenge") -> PluginValue.string(p.challenge),
        PluginValue.string("method")    -> PluginValue.string(p.method)
      ))
    },
    QualifiedName("oauth.client.freshState") -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.string(OAuthClient.freshState())
    },
    QualifiedName("oauth.client.verifyState") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(a: String, b: String) =>
          PluginValue.bool(OAuthClient.verifyState(a, b))
        case _ => PluginError.raise("oauth.client.verifyState(expected, presented)")
    },

    QualifiedName("oauth.client.authorizationUrl") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(ep: String, cid: String, redirect: String, scopesV,
                  state: String, challenge: String, method: String) =>
          val scopes = OAuthIntrinsicHelpers.toStringSet(scopesV)
          PluginValue.string(OAuthClient.authorizationUrl(
            authorizationEndpoint = ep,
            clientId              = cid,
            redirectUri           = redirect,
            scopes                = scopes,
            state                 = state,
            pkce                  = OAuthClient.PkcePair(verifier = "", challenge = challenge, method = method)
          ))
        case _ => PluginError.raise(
          "oauth.client.authorizationUrl(endpoint, clientId, redirectUri, scopes, state, challenge, method)")
    },

    QualifiedName("oauth.client.exchangeAuthorizationCode") -> PluginNative.evalLegacy { (_, args) =>
      val (ep, cid, redirect, code, verifier, secret) = args match
        case List(ep: String, cid: String, redirect: String, code: String, verifier: String) =>
          (ep, cid, redirect, code, verifier, None)
        case List(ep: String, cid: String, redirect: String, code: String, verifier: String,
                  secret: String) =>
          (ep, cid, redirect, code, verifier, Some(secret))
        case _ => PluginError.raise(
          "oauth.client.exchangeAuthorizationCode(endpoint, clientId, redirectUri, code, verifier[, secret])")
      OAuthClientIntrinsicHelpers.tokenResultToValue(
        OAuthClient.exchangeAuthorizationCode(ep, cid, redirect, code, verifier, secret))
    },

    QualifiedName("oauth.client.refresh") -> PluginNative.evalLegacy { (_, args) =>
      val (ep, cid, refresh, scopes, secret) = args match
        case List(ep: String, cid: String, refresh: String) =>
          (ep, cid, refresh, Set.empty[String], None)
        case List(ep: String, cid: String, refresh: String, scopesV) =>
          (ep, cid, refresh, OAuthIntrinsicHelpers.toStringSet(scopesV), None)
        case List(ep: String, cid: String, refresh: String, scopesV, secret: String) =>
          (ep, cid, refresh, OAuthIntrinsicHelpers.toStringSet(scopesV), Some(secret))
        case _ => PluginError.raise(
          "oauth.client.refresh(endpoint, clientId, refreshToken[, scopes][, secret])")
      OAuthClientIntrinsicHelpers.tokenResultToValue(
        OAuthClient.refresh(ep, cid, refresh, scopes, secret))
    },

    QualifiedName("oauth.client.clientCredentials") -> PluginNative.evalLegacy { (_, args) =>
      val (ep, cid, secret, scopes) = args match
        case List(ep: String, cid: String, secret: String) =>
          (ep, cid, secret, Set.empty[String])
        case List(ep: String, cid: String, secret: String, scopesV) =>
          (ep, cid, secret, OAuthIntrinsicHelpers.toStringSet(scopesV))
        case _ => PluginError.raise(
          "oauth.client.clientCredentials(endpoint, clientId, secret[, scopes])")
      OAuthClientIntrinsicHelpers.tokenResultToValue(
        OAuthClient.clientCredentials(ep, cid, secret, scopes))
    },

    QualifiedName("oauth.client.tokenHolder") -> PluginNative.evalLegacy { (_, args) =>
      val (ep, cid, leadSec, secret) = args match
        case List(ep: String, cid: String) =>
          (ep, cid, 60L, None)
        case List(ep: String, cid: String, leadSec: Long) =>
          (ep, cid, leadSec, None)
        case List(ep: String, cid: String, leadSec: Long, secret: String) =>
          (ep, cid, leadSec, Some(secret))
        case _ => PluginError.raise(
          "oauth.client.tokenHolder(endpoint, clientId[, refreshLeadSeconds][, secret])")
      OAuthClientIntrinsicHelpers.makeTokenHolderInstance(
        new OAuthClient.TokenHolder(ep, cid, secret, leadSec))
    }
  )


object OAuthClientIntrinsicHelpers:

  private val holderRegistry = ConcurrentHashMap[String, OAuthClient.TokenHolder]()

  def tokenResultToValue(r: OAuthClient.TokenResult): PluginValue = r match
    case OAuthClient.TokenResult.Issued(t, raw) =>
      PluginValue.mapOf(Map(
        (PluginValue.string("ok"): PluginValue) -> (PluginValue.bool(true): PluginValue),
        (PluginValue.string("accessToken"): PluginValue) -> (PluginValue.string(t.accessToken): PluginValue),
        (PluginValue.string("tokenType"): PluginValue) -> (PluginValue.string(t.tokenType): PluginValue),
        (PluginValue.string("expiresIn"): PluginValue) -> (PluginValue.int(t.expiresIn): PluginValue),
        (PluginValue.string("refreshToken"): PluginValue) -> PluginValue.option(t.refreshToken.map(PluginValue.string(_))),
        (PluginValue.string("idToken"):      PluginValue) -> PluginValue.option(t.idToken.map(PluginValue.string(_))),
        (PluginValue.string("scope"): PluginValue) -> PluginValue.list(t.scope.toList.sorted.map(PluginValue.string(_))),
        (PluginValue.string("raw"): PluginValue) -> ujsonToValue(raw)
      ))
    case OAuthClient.TokenResult.Error(err, descr, raw) =>
      PluginValue.mapOf(Map(
        (PluginValue.string("ok"): PluginValue) -> (PluginValue.bool(false): PluginValue),
        (PluginValue.string("error"): PluginValue) -> (PluginValue.string(err): PluginValue),
        (PluginValue.string("description"): PluginValue) -> (PluginValue.string(descr): PluginValue),
        (PluginValue.string("raw"): PluginValue) -> ujsonToValue(raw)
      ))

  def makeTokenHolderInstance(h: OAuthClient.TokenHolder): PluginValue =
    val id = "th-" + OAuth.randomOpaqueToken(12)
    holderRegistry.put(id, h)
    val fields = mutable.LinkedHashMap.empty[String, PluginValue]
    fields("_id") = PluginValue.string(id)

    fields("seed") = PluginValue.nativeFn("TokenHolder.seed", {
      case List(tokensV) =>
        h.seed(decodeTokens(tokensV))
        PluginValue.unit
      case _ => PluginError.raise("holder.seed(tokens)")
    })

    fields("current") = PluginValue.nativeFn("TokenHolder.current", { _ =>
      h.current() match
        case Some(t) => PluginValue.some(PluginValue.string(t))
        case None    => PluginValue.none
    })

    fields("clear") = PluginValue.nativeFn("TokenHolder.clear", { _ =>
      h.clear(); PluginValue.unit
    })

    PluginValue.instance("TokenHolder", fields.toMap)

  def resolveTokenHolder(v: PluginValue): Option[OAuthClient.TokenHolder] = v match
    case Inst("TokenHolder", fs) =>
      fs.get("_id").collect { case Str(id) => id }
        .flatMap(id => Option(holderRegistry.get(id)))
    case _ => None

  def decodeTokens(v: PluginValue): OAuthClient.Tokens =
    val fs = v match
      case MapVal(m)         => m.iterator.collect { case (Str(k), v) => k -> v }.toMap
      case Inst(_, m) => m
      case _ => PluginError.raise("holder.seed: expected Map / record of tokens")
    val access  = fs.get("accessToken").collect { case Str(s) => s }
      .getOrElse(PluginError.raise("holder.seed: missing 'accessToken'"))
    val ttype   = fs.get("tokenType").collect { case Str(s) => s }.getOrElse("Bearer")
    val expIn   = fs.get("expiresIn").collect {
      case Num(i)    => i
      case Dbl(d) => d.toLong
    }.getOrElse(3600L)
    val refresh = fs.get("refreshToken").collect {
      case Opt(Str(s)) => s
      case Str(s)                       => s
    }
    val idTok = fs.get("idToken").collect {
      case Opt(Str(s)) => s
      case Str(s)                       => s
    }
    val scopeSet = fs.get("scope").map {
      case Lst(xs)  => xs.collect { case Str(s) => s }.toSet
      case Str(s) => s.split(' ').iterator.filter(_.nonEmpty).toSet
      case _                => Set.empty[String]
    }.getOrElse(Set.empty[String])
    OAuthClient.Tokens(access, ttype, expIn, refresh, idTok, scopeSet)

  def ujsonToValue(v: ujson.Value): PluginValue = v match
    case ujson.Null    => PluginValue.none
    case ujson.True    => PluginValue.bool(true)
    case ujson.False   => PluginValue.bool(false)
    case ujson.Str(s)  => PluginValue.string(s)
    case ujson.Num(n)  if n == n.toLong.toDouble => PluginValue.int(n.toLong)
    case ujson.Num(n)  => PluginValue.double(n)
    case ujson.Arr(xs) => PluginValue.list(xs.iterator.map(ujsonToValue).toList)
    case ujson.Obj(kv) => PluginValue.mapOf(kv.iterator.map((k, v) =>
                            (PluginValue.string(k): PluginValue) -> ujsonToValue(v)).toMap)
