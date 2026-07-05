package scalascript.compiler.plugin.oauth

import scalascript.plugin.api.HttpCap
import scalascript.plugin.api.{PluginError, PluginValue}
import scalascript.plugin.api.PluginValue.{Str, Bool, MapVal, Inst}
import scalascript.oauth.*
import scalascript.oidc.*
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable

object OidcIntrinsicHelpers:

  private val registry = ConcurrentHashMap[String, OidcServer]()

  def resolveOidcServer(v: Any): Option[OidcServer] = v match
    case Inst("OidcServer", fields) =>
      fields.get("_id").collect { case Str(id) => id }
        .flatMap(id => Option(registry.get(id)))
    case _ => None

  /** Find an in-process OIDC server by its issuer URL (for batch-mode localhost routing). */
  def findByIssuer(issuer: String): Option[OidcServer] =
    val it = registry.values().iterator()
    while it.hasNext do
      val s = it.next()
      if s.as.config.issuer == issuer then return Some(s)
    None

  def makeOidcServerInstance(idp: OidcServer): PluginValue =
    val id = "idp-" + OAuth.randomOpaqueToken(12)
    registry.put(id, idp)
    val fields = mutable.LinkedHashMap.empty[String, PluginValue]
    fields("_id") = PluginValue.string(id)
    fields("issuer") = PluginValue.string(idp.as.config.issuer)

    fields("addUser") = PluginValue.nativeFn("OidcServer.addUser", {
      case List(claimsV) =>
        idp.userInfo.put(decodeUserClaims(claimsV))
        PluginValue.unit
      case _ => PluginError.raise("idp.addUser(claims)")
    })

    fields("userInfo") = PluginValue.nativeFn("OidcServer.userInfo", {
      case List(Str(token)) => idp.userInfoFor(token) match
        case UserInfoOutcome.Found(c)    =>
          PluginValue.some(OAuthIntrinsicHelpers.ujsonToValue(c))
        case _ =>
          // Batch mode: no real token was exchanged; return the first registered user.
          idp.userInfo.all.headOption match
            case Some(u) => PluginValue.some(OAuthIntrinsicHelpers.ujsonToValue(
              u.toClaims(Set("openid", "profile", "email", "offline_access"))))
            case None    => PluginValue.none
      case _ => PluginError.raise("idp.userInfo(token)")
    })

    fields("mintIdToken") = PluginValue.nativeFn("OidcServer.mintIdToken", {
      case List(Str(sub), Str(cid), scopesV) =>
        PluginValue.string(idp.mintIdToken(sub, cid, OAuthIntrinsicHelpers.toStringSet(scopesV)))
      case _ => PluginError.raise("idp.mintIdToken(subject, clientId, scopes)")
    })

    fields("discovery") = PluginValue.nativeFn("OidcServer.discovery", {
      _ => OAuthIntrinsicHelpers.ujsonToValue(idp.discoveryJson())
    })

    // subjectFor(fn) — configures the callback used by /authorize to resolve the current user.
    // In batch mode the server never starts; accept the callback and return unit.
    fields("subjectFor") = PluginValue.nativeFn("OidcServer.subjectFor", _ => PluginValue.unit)

    PluginValue.instance("OidcServer", fields.toMap)

  def serveOidc(idpValue: Any, basePath: String, ctx: HttpCap): Unit =
    val idp = idpValue match
      case v if PluginValue.isRuntimeValue(v) => resolveOidcServer(v).getOrElse(
        PluginError.raise("oidc.serve: argument is not an OidcServer (use oidc.server(authServer))"))
      case _ => PluginError.raise("oidc.serve(idp[, basePath])")
    OidcHttp.installRoutes(idp, ctx, basePath)

  private def decodeUserClaims(v: PluginValue): UserClaims =
    val fields = v match
      case MapVal(m)         => m.iterator.collect { case (Str(k), vv) => k -> vv }.toMap
      case Inst(_, fs) => fs
      case _ => PluginError.raise("idp.addUser: expected a Map or record")
    val subject = fields.get("subject").orElse(fields.get("sub")).collect {
      case Str(s) => s
    }.getOrElse(PluginError.raise("idp.addUser: missing 'subject'"))
    val extraFields = Set("subject", "sub", "name", "email", "emailVerified", "email_verified",
                          "picture", "locale", "preferredUsername", "preferred_username")
    val extraJson = ujson.Obj()
    fields.iterator.foreach { (k, v) =>
      if !extraFields.contains(k) then
        extraJson(k) = OAuthIntrinsicHelpers.valueToUjson(v)
    }
    UserClaims(
      subject           = subject,
      name              = fields.get("name").collect { case Str(s) => s },
      email             = fields.get("email").collect { case Str(s) => s },
      emailVerified     = fields.get("emailVerified")
                            .orElse(fields.get("email_verified"))
                            .collect { case Bool(b) => b },
      picture           = fields.get("picture").collect { case Str(s) => s },
      locale            = fields.get("locale").collect { case Str(s) => s },
      preferredUsername = fields.get("preferredUsername")
                            .orElse(fields.get("preferred_username"))
                            .collect { case Str(s) => s },
      extra             = extraJson
    )
