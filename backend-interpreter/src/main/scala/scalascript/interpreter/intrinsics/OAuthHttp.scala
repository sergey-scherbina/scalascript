package scalascript.interpreter.intrinsics

import scalascript.backend.spi.NativeContext
import scalascript.interpreter.{Computation, Value}
import scalascript.oauth.*

/** v1.17.x — wire `OAuthRoutes` (pure handlers) into the interpreter's
 *  WebServer via `NativeContext.registerRoute`.  One call installs the
 *  full standard endpoint set:
 *
 *    POST  <base>/token
 *    POST  <base>/introspect
 *    POST  <base>/revoke
 *    POST  <base>/register
 *    GET   <base>/authorize
 *    GET   <base>/.well-known/oauth-authorization-server
 *
 *  Scripts use this through the `oauth.serveAuthServer(...)` intrinsic;
 *  Scala callers can use `OAuthHttp.installRoutes(...)` directly.
 *
 *  The /authorize endpoint needs to know who the current user is —
 *  that's the `subjectFor: Map[String, String] => Option[String]`
 *  hook (typically reads a session cookie / Auth header).  Optionally
 *  the caller also supplies a `loginUrl` builder so unauthenticated
 *  requests bounce to the login page. */
object OAuthHttp:

  /** Install all OAuth endpoints under `basePath` on the running
   *  WebServer.  Endpoint paths follow RFC 8414 conventions; pass a
   *  prefix like "/oauth" to mount the AS under a sub-path. */
  def installRoutes(
    as:         AuthServer,
    ctx:        NativeContext,
    basePath:   String                                                    = "",
    subjectFor: Map[String, String] => Option[String]                     = _ => None,
    loginUrl:   Option[String => String]                                  = None,
    selfUrl:    Option[String]                                            = None
  ): Unit =
    val prefix = basePath.stripSuffix("/")

    // ─── POST /token ─────────────────────────────────────────────────
    register(ctx, "POST", prefix + "/token", "oauth.http.token") { fields =>
      val body    = stringField(fields, "body").getOrElse("")
      val headers = extractHeaderMap(fields)
      OAuthRoutes.handleToken(as, body, headers)
    }

    // ─── POST /introspect ────────────────────────────────────────────
    register(ctx, "POST", prefix + "/introspect", "oauth.http.introspect") { fields =>
      val body    = stringField(fields, "body").getOrElse("")
      val headers = extractHeaderMap(fields)
      OAuthRoutes.handleIntrospect(as, body, headers)
    }

    // ─── POST /revoke ────────────────────────────────────────────────
    register(ctx, "POST", prefix + "/revoke", "oauth.http.revoke") { fields =>
      val body    = stringField(fields, "body").getOrElse("")
      val headers = extractHeaderMap(fields)
      OAuthRoutes.handleRevoke(as, body, headers)
    }

    // ─── POST /register ──────────────────────────────────────────────
    register(ctx, "POST", prefix + "/register", "oauth.http.register") { fields =>
      val body    = stringField(fields, "body").getOrElse("")
      val headers = extractHeaderMap(fields)
      OAuthRoutes.handleRegister(as, body, headers)
    }

    // ─── GET /authorize ──────────────────────────────────────────────
    register(ctx, "GET", prefix + "/authorize", "oauth.http.authorize") { fields =>
      val query   = extractQueryMap(fields)
      val headers = extractHeaderMap(fields)
      OAuthRoutes.handleAuthorize(as, query, headers, subjectFor, loginUrl, selfUrl)
    }

    // ─── GET /.well-known/oauth-authorization-server ─────────────────
    register(ctx, "GET", prefix + "/.well-known/oauth-authorization-server",
             "oauth.http.metadata") { _ => OAuthRoutes.handleMetadata(as) }

  // ─── helpers ────────────────────────────────────────────────────────

  /** Register one route that maps a Request → RouteOutcome. */
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
        "status"  -> Value.IntV(400L),
        "headers" -> Value.MapV(Map.empty),
        "body"    -> Value.StringV("expected Request")
      ))
    })
    ctx.registerRoute(method, path, handler)

  /** Adapt a typed `RouteOutcome` to the `Response` InstanceV that the
   *  WebServer's response writer understands. */
  def routeOutcomeToValue(o: OAuthRoutes.RouteOutcome): Value = o match
    case OAuthRoutes.RouteOutcome.Json(status, body, extra) =>
      val hdrs = ujsonHeaders("Content-Type" -> "application/json") ++ extra.iterator.map {
        (k, v) => (Value.StringV(k): Value) -> (Value.StringV(v): Value)
      }
      Value.InstanceV("Response", Map(
        "status"  -> Value.IntV(status.toLong),
        "headers" -> Value.MapV(hdrs.toMap),
        "body"    -> Value.StringV(body.render())
      ))
    case OAuthRoutes.RouteOutcome.Redirect(status, location) =>
      Value.InstanceV("Response", Map(
        "status"  -> Value.IntV(status.toLong),
        "headers" -> Value.MapV(Map(
          (Value.StringV("Location"): Value) -> (Value.StringV(location): Value)
        )),
        "body"    -> Value.StringV("")
      ))
    case OAuthRoutes.RouteOutcome.Empty(status) =>
      Value.InstanceV("Response", Map(
        "status"  -> Value.IntV(status.toLong),
        "headers" -> Value.MapV(Map.empty),
        "body"    -> Value.StringV("")
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
      // Fallback: parse from rawQuery string if the WebServer didn't
      // pre-decode it.  Matches `OAuthRoutes.parseForm` semantics.
      stringField(fields, "rawQuery").map(OAuthRoutes.parseForm)
    ).getOrElse(Map.empty)
