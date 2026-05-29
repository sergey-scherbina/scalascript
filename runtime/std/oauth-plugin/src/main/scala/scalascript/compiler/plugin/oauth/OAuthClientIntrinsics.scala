package scalascript.compiler.plugin.oauth

import scalascript.backend.spi.IntrinsicImpl
import scalascript.ir.QualifiedName
import scalascript.interpreter.{Value, InterpretError, Computation}
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
        case _ => throw InterpretError("oauth.client.discoverAs(issuer)")
    },
    QualifiedName("oauth.client.discoverRs") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(resourceUrl: String) =>
          OAuthClientIntrinsicHelpers.ujsonToValue(OAuthClient.discoverRs(resourceUrl))
        case _ => throw InterpretError("oauth.client.discoverRs(resourceUrl)")
    },

    QualifiedName("oauth.client.freshPkce") -> PluginNative.evalLegacy { (_, _) =>
      val p = OAuthClient.freshPkce()
      Value.MapV(Map(
        Value.StringV("verifier")  -> Value.StringV(p.verifier),
        Value.StringV("challenge") -> Value.StringV(p.challenge),
        Value.StringV("method")    -> Value.StringV(p.method)
      ))
    },
    QualifiedName("oauth.client.freshState") -> PluginNative.evalLegacy { (_, _) =>
      Value.StringV(OAuthClient.freshState())
    },
    QualifiedName("oauth.client.verifyState") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(a: String, b: String) =>
          Value.boolV(OAuthClient.verifyState(a, b))
        case _ => throw InterpretError("oauth.client.verifyState(expected, presented)")
    },

    QualifiedName("oauth.client.authorizationUrl") -> PluginNative.evalLegacy { (_, args) =>
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
    },

    QualifiedName("oauth.client.exchangeAuthorizationCode") -> PluginNative.evalLegacy { (_, args) =>
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
    },

    QualifiedName("oauth.client.refresh") -> PluginNative.evalLegacy { (_, args) =>
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
    },

    QualifiedName("oauth.client.clientCredentials") -> PluginNative.evalLegacy { (_, args) =>
      val (ep, cid, secret, scopes) = args match
        case List(ep: String, cid: String, secret: String) =>
          (ep, cid, secret, Set.empty[String])
        case List(ep: String, cid: String, secret: String, scopesV: Value) =>
          (ep, cid, secret, OAuthIntrinsicHelpers.toStringSet(scopesV))
        case _ => throw InterpretError(
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
        case _ => throw InterpretError(
          "oauth.client.tokenHolder(endpoint, clientId[, refreshLeadSeconds][, secret])")
      OAuthClientIntrinsicHelpers.makeTokenHolderInstance(
        new OAuthClient.TokenHolder(ep, cid, secret, leadSec))
    }
  )


object OAuthClientIntrinsicHelpers:

  private val holderRegistry = ConcurrentHashMap[String, OAuthClient.TokenHolder]()

  def tokenResultToValue(r: OAuthClient.TokenResult): Value = r match
    case OAuthClient.TokenResult.Issued(t, raw) =>
      Value.MapV(Map(
        (Value.StringV("ok"):           Value) -> (Value.True:                  Value),
        (Value.StringV("accessToken"):  Value) -> (Value.StringV(t.accessToken):       Value),
        (Value.StringV("tokenType"):    Value) -> (Value.StringV(t.tokenType):         Value),
        (Value.StringV("expiresIn"):    Value) -> (Value.intV(t.expiresIn):            Value),
        (Value.StringV("refreshToken"): Value) -> Value.OptionV(t.refreshToken.map(Value.StringV(_)).orNull),
        (Value.StringV("idToken"):      Value) -> Value.OptionV(t.idToken.map(Value.StringV(_)).orNull),
        (Value.StringV("scope"):        Value) -> Value.ListV(t.scope.toList.sorted.map(Value.StringV(_))),
        (Value.StringV("raw"):          Value) -> ujsonToValue(raw)
      ))
    case OAuthClient.TokenResult.Error(err, descr, raw) =>
      Value.MapV(Map(
        (Value.StringV("ok"):          Value) -> (Value.False:           Value),
        (Value.StringV("error"):       Value) -> (Value.StringV(err):           Value),
        (Value.StringV("description"): Value) -> (Value.StringV(descr):         Value),
        (Value.StringV("raw"):         Value) -> ujsonToValue(raw)
      ))

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
        case Some(t) => Value.OptionV(Value.StringV(t))
        case None    => Value.NoneV
    })

    fields("clear") = Value.NativeFnV("TokenHolder.clear", Computation.pureFn { _ =>
      h.clear(); Value.UnitV
    })

    Value.InstanceV("TokenHolder", fields.toMap)

  def resolveTokenHolder(v: Value): Option[OAuthClient.TokenHolder] = v match
    case Value.InstanceV("TokenHolder", fs) =>
      fs.get("_id").collect { case Value.StringV(id) => id }
        .flatMap(id => Option(holderRegistry.get(id)))
    case _ => None

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
      case Value.OptionV(Value.StringV(s)) => s
      case Value.StringV(s)                       => s
    }
    val idTok = fs.get("idToken").collect {
      case Value.OptionV(Value.StringV(s)) => s
      case Value.StringV(s)                       => s
    }
    val scopeSet = fs.get("scope").map {
      case Value.ListV(xs)  => xs.collect { case Value.StringV(s) => s }.toSet
      case Value.StringV(s) => s.split(' ').iterator.filter(_.nonEmpty).toSet
      case _                => Set.empty[String]
    }.getOrElse(Set.empty[String])
    OAuthClient.Tokens(access, ttype, expIn, refresh, idTok, scopeSet)

  def ujsonToValue(v: ujson.Value): Value = v match
    case ujson.Null    => Value.NoneV
    case ujson.True    => Value.True
    case ujson.False   => Value.False
    case ujson.Str(s)  => Value.StringV(s)
    case ujson.Num(n)  if n == n.toLong.toDouble => Value.intV(n.toLong)
    case ujson.Num(n)  => Value.doubleV(n)
    case ujson.Arr(xs) => Value.ListV(xs.iterator.map(ujsonToValue).toList)
    case ujson.Obj(kv) => Value.MapV(kv.iterator.map((k, v) =>
                            (Value.StringV(k): Value) -> ujsonToValue(v)).toMap)
