package scalascript.compiler.plugin.oauth

import scalascript.backend.spi.NativeContext
import scalascript.interpreter.{Value, InterpretError, Computation}
import scalascript.oauth.*
import scalascript.oidc.*
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable

object OidcIntrinsicHelpers:

  private val registry = ConcurrentHashMap[String, OidcServer]()

  def resolveOidcServer(v: Value): Option[OidcServer] = v match
    case Value.InstanceV("OidcServer", fields) =>
      fields.get("_id").collect { case Value.StringV(id) => id }
        .flatMap(id => Option(registry.get(id)))
    case _ => None

  def makeOidcServerInstance(idp: OidcServer): Value =
    val id = "idp-" + OAuth.randomOpaqueToken(12)
    registry.put(id, idp)
    val fields = mutable.LinkedHashMap.empty[String, Value]
    fields("_id") = Value.StringV(id)
    fields("issuer") = Value.StringV(idp.as.config.issuer)

    fields("addUser") = Value.NativeFnV("OidcServer.addUser", Computation.pureFn {
      case List(claimsV: Value) =>
        idp.userInfo.put(decodeUserClaims(claimsV))
        Value.UnitV
      case _ => throw InterpretError("idp.addUser(claims)")
    })

    fields("userInfo") = Value.NativeFnV("OidcServer.userInfo", Computation.pureFn {
      case List(Value.StringV(token)) => idp.userInfoFor(token) match
        case UserInfoOutcome.Found(c)    =>
          Value.OptionV(Some(OAuthIntrinsicHelpers.ujsonToValue(c)))
        case _                            => Value.NoneV
      case _ => throw InterpretError("idp.userInfo(token)")
    })

    fields("mintIdToken") = Value.NativeFnV("OidcServer.mintIdToken", Computation.pureFn {
      case List(Value.StringV(sub), Value.StringV(cid), scopesV) =>
        Value.StringV(idp.mintIdToken(sub, cid, OAuthIntrinsicHelpers.toStringSet(scopesV)))
      case _ => throw InterpretError("idp.mintIdToken(subject, clientId, scopes)")
    })

    fields("discovery") = Value.NativeFnV("OidcServer.discovery", Computation.pureFn {
      _ => OAuthIntrinsicHelpers.ujsonToValue(idp.discoveryJson())
    })

    Value.InstanceV("OidcServer", fields.toMap)

  def serveOidc(idpValue: Any, basePath: String, ctx: NativeContext): Unit =
    val idp = idpValue match
      case v: Value => resolveOidcServer(v).getOrElse(
        throw InterpretError("oidc.serve: argument is not an OidcServer (use oidc.server(authServer))"))
      case _ => throw InterpretError("oidc.serve(idp[, basePath])")
    OidcHttp.installRoutes(idp, ctx, basePath)

  private def decodeUserClaims(v: Value): UserClaims =
    val fields = v match
      case Value.MapV(m)         => m.iterator.collect { case (Value.StringV(k), vv) => k -> vv }.toMap
      case Value.InstanceV(_, fs) => fs
      case _ => throw InterpretError("idp.addUser: expected a Map or record")
    val subject = fields.get("subject").orElse(fields.get("sub")).collect {
      case Value.StringV(s) => s
    }.getOrElse(throw InterpretError("idp.addUser: missing 'subject'"))
    val extraFields = Set("subject", "sub", "name", "email", "emailVerified", "email_verified",
                          "picture", "locale", "preferredUsername", "preferred_username")
    val extraJson = ujson.Obj()
    fields.iterator.foreach { (k, v) =>
      if !extraFields.contains(k) then
        extraJson(k) = OAuthIntrinsicHelpers.valueToUjson(v)
    }
    UserClaims(
      subject           = subject,
      name              = fields.get("name").collect { case Value.StringV(s) => s },
      email             = fields.get("email").collect { case Value.StringV(s) => s },
      emailVerified     = fields.get("emailVerified")
                            .orElse(fields.get("email_verified"))
                            .collect { case Value.BoolV(b) => b },
      picture           = fields.get("picture").collect { case Value.StringV(s) => s },
      locale            = fields.get("locale").collect { case Value.StringV(s) => s },
      preferredUsername = fields.get("preferredUsername")
                            .orElse(fields.get("preferred_username"))
                            .collect { case Value.StringV(s) => s },
      extra             = extraJson
    )
