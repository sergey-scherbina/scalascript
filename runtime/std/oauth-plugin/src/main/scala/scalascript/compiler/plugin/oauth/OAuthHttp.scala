package scalascript.compiler.plugin.oauth

import scalascript.backend.spi.NativeContext
import scalascript.interpreter.{Computation, Value}
import scalascript.oauth.*

object OAuthHttp:

  def installRoutes(
    as:         AuthServer,
    ctx:        NativeContext,
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
    ctx:    NativeContext,
    method: String,
    path:   String,
    name:   String
  )(run: Map[String, Value] => OAuthRoutes.RouteOutcome): Unit =
    val handler = Value.NativeFnV(name, Computation.pureFn {
      case List(Value.InstanceV("Request", fields)) =>
        routeOutcomeToValue(run(fields))
      case _ => Value.InstanceV("Response", Map(
        "status"  -> Value.intV(400L),
        "headers" -> Value.EmptyMap,
        "body"    -> Value.StringV("expected Request")
      ))
    })
    ctx.registerRoute(method, path, handler)

  def routeOutcomeToValue(o: OAuthRoutes.RouteOutcome): Value = o match
    case OAuthRoutes.RouteOutcome.Json(status, body, extra) =>
      val hdrs = ujsonHeaders("Content-Type" -> "application/json") ++ extra.iterator.map {
        (k, v) => (Value.StringV(k): Value) -> (Value.StringV(v): Value)
      }
      Value.InstanceV("Response", Map(
        "status"  -> Value.intV(status.toLong),
        "headers" -> Value.MapV(hdrs.toMap),
        "body"    -> Value.StringV(body.render())
      ))
    case OAuthRoutes.RouteOutcome.Redirect(status, location) =>
      Value.InstanceV("Response", Map(
        "status"  -> Value.intV(status.toLong),
        "headers" -> Value.MapV(Map(
          (Value.StringV("Location"): Value) -> (Value.StringV(location): Value)
        )),
        "body"    -> Value.EmptyStr
      ))
    case OAuthRoutes.RouteOutcome.Empty(status) =>
      Value.InstanceV("Response", Map(
        "status"  -> Value.intV(status.toLong),
        "headers" -> Value.EmptyMap,
        "body"    -> Value.EmptyStr
      ))

  private def ujsonHeaders(pairs: (String, String)*): Map[Value, Value] =
    pairs.iterator.map((k, v) => (Value.StringV(k): Value) -> (Value.StringV(v): Value)).toMap

  private def stringField(fields: Map[String, Value], name: String): Option[String] =
    fields.get(name).collect { case Value.StringV(s) => s }

  def extractHeaderMap(fields: Map[String, Value]): Map[String, String] =
    fields.get("headers").collect {
      case Value.MapV(m) => m.iterator.collect {
        case (Value.StringV(k), Value.StringV(v)) => k -> v
      }.toMap
    }.getOrElse(Map.empty)

  private def extractQueryMap(fields: Map[String, Value]): Map[String, String] =
    fields.get("query").collect {
      case Value.MapV(m) => m.iterator.collect {
        case (Value.StringV(k), Value.StringV(v)) => k -> v
      }.toMap
    }.orElse(
      stringField(fields, "rawQuery").map(OAuthRoutes.parseForm)
    ).getOrElse(Map.empty)
