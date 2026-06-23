package scalascript.compiler.plugin.oauth

import scalascript.plugin.api.HttpCap
import scalascript.plugin.api.PluginValue
import scalascript.plugin.api.PluginValue.{Str, MapVal, Inst}
import scalascript.oauth.*

object OAuthHttp:

  def installRoutes(
    as:         AuthServer,
    ctx:        HttpCap,
    basePath:   String                                                    = "",
    subjectFor: Map[String, String] => Option[String]                     = _ => None,
    loginUrl:   Option[String => String]                                  = None,
    selfUrl:    Option[String]                                            = None
  ): Unit =
    val prefix = basePath.stripSuffix("/")

    register(ctx, "POST", prefix + "/token", "oauth.http.token") { fields =>
      val body    = stringField(fields, "body").getOrElse("")
      val headers = extractHeaderMap(fields)
      OAuthRoutes.handleToken(as, body, headers)
    }
    register(ctx, "POST", prefix + "/introspect", "oauth.http.introspect") { fields =>
      val body    = stringField(fields, "body").getOrElse("")
      val headers = extractHeaderMap(fields)
      OAuthRoutes.handleIntrospect(as, body, headers)
    }
    register(ctx, "POST", prefix + "/revoke", "oauth.http.revoke") { fields =>
      val body    = stringField(fields, "body").getOrElse("")
      val headers = extractHeaderMap(fields)
      OAuthRoutes.handleRevoke(as, body, headers)
    }
    register(ctx, "POST", prefix + "/register", "oauth.http.register") { fields =>
      val body    = stringField(fields, "body").getOrElse("")
      val headers = extractHeaderMap(fields)
      OAuthRoutes.handleRegister(as, body, headers)
    }
    register(ctx, "GET", prefix + "/authorize", "oauth.http.authorize") { fields =>
      val query   = extractQueryMap(fields)
      val headers = extractHeaderMap(fields)
      OAuthRoutes.handleAuthorize(as, query, headers, subjectFor, loginUrl, selfUrl)
    }
    register(ctx, "GET", prefix + "/.well-known/oauth-authorization-server",
             "oauth.http.metadata") { _ => OAuthRoutes.handleMetadata(as) }
    register(ctx, "GET", prefix + "/.well-known/jwks.json",
             "oauth.http.jwks") { _ => OAuthRoutes.handleJwks(as) }
    register(ctx, "GET", prefix + "/passkey/challenge",
             "oauth.http.passkey-challenge") { _ => OAuthRoutes.handlePasskeyChallenge(as) }

  private def register(
    ctx:    HttpCap,
    method: String,
    path:   String,
    name:   String
  )(run: Map[String, PluginValue] => OAuthRoutes.RouteOutcome): Unit =
    val handler = PluginValue.nativeFn(name, {
      case List(Inst("Request", fields)) =>
        routeOutcomeToValue(run(fields))
      case _ => PluginValue.instance("Response", Map(
        "status"  -> PluginValue.int(400L),
        "headers" -> PluginValue.mapOf(Map.empty[PluginValue, PluginValue]),
        "body"    -> PluginValue.string("expected Request")
      ))
    })
    ctx.registerRoute(method, path, handler)

  def routeOutcomeToValue(o: OAuthRoutes.RouteOutcome): PluginValue = o match
    case OAuthRoutes.RouteOutcome.Json(status, body, extra) =>
      val hdrs = ujsonHeaders("Content-Type" -> "application/json") ++ extra.iterator.map {
        (k, v) => (PluginValue.string(k): PluginValue) -> (PluginValue.string(v): PluginValue)
      }
      PluginValue.instance("Response", Map(
        "status"  -> PluginValue.int(status.toLong),
        "headers" -> PluginValue.mapOf(hdrs.toMap),
        "body"    -> PluginValue.string(body.render())
      ))
    case OAuthRoutes.RouteOutcome.Redirect(status, location) =>
      PluginValue.instance("Response", Map(
        "status"  -> PluginValue.int(status.toLong),
        "headers" -> PluginValue.mapOf(Map(
          (PluginValue.string("Location"): PluginValue) -> (PluginValue.string(location): PluginValue)
        )),
        "body"    -> PluginValue.string("")
      ))
    case OAuthRoutes.RouteOutcome.Empty(status) =>
      PluginValue.instance("Response", Map(
        "status"  -> PluginValue.int(status.toLong),
        "headers" -> PluginValue.mapOf(Map.empty[PluginValue, PluginValue]),
        "body"    -> PluginValue.string("")
      ))

  private def ujsonHeaders(pairs: (String, String)*): Map[PluginValue, PluginValue] =
    pairs.iterator.map((k, v) => (PluginValue.string(k): PluginValue) -> (PluginValue.string(v): PluginValue)).toMap

  private def stringField(fields: Map[String, PluginValue], name: String): Option[String] =
    fields.get(name).collect { case Str(s) => s }

  def extractHeaderMap(fields: Map[String, PluginValue]): Map[String, String] =
    fields.get("headers").collect {
      case MapVal(m) => m.iterator.collect {
        case (Str(k), Str(v)) => k -> v
      }.toMap
    }.getOrElse(Map.empty)

  private def extractQueryMap(fields: Map[String, PluginValue]): Map[String, String] =
    fields.get("query").collect {
      case MapVal(m) => m.iterator.collect {
        case (Str(k), Str(v)) => k -> v
      }.toMap
    }.orElse(
      stringField(fields, "rawQuery").map(OAuthRoutes.parseForm)
    ).getOrElse(Map.empty)
