package scalascript.interpreter.intrinsics

import scalascript.backend.spi.NativeContext
import scalascript.interpreter.{Computation, Value}
import scalascript.oauth.*
import scalascript.oidc.*

/** v1.17.x — wire OIDC (OpenID Connect) routes into the interpreter's
 *  embedded WebServer.  Superset of `OAuthHttp.installRoutes`:
 *
 *    POST <base>/token                         — id_token-aware (OIDC)
 *    GET  <base>/userinfo                      — OIDC
 *    GET  <base>/.well-known/openid-configuration — OIDC
 *    POST <base>/introspect                    — OAuth (unchanged)
 *    POST <base>/revoke                        — OAuth (unchanged)
 *    POST <base>/register                      — OAuth (unchanged)
 *    GET  <base>/authorize                     — OAuth (unchanged)
 *    GET  <base>/.well-known/oauth-authorization-server — OAuth (unchanged)
 *
 *  Both discovery docs are served so OAuth-only clients can still find
 *  the AS via the RFC 8414 path while OIDC clients hit the OIDC path. */
object OidcHttp:

  def installRoutes(
    idp:        OidcServer,
    ctx:        NativeContext,
    basePath:   String                                  = "",
    subjectFor: Map[String, String] => Option[String]   = _ => None,
    loginUrl:   Option[String => String]                = None,
    selfUrl:    Option[String]                          = None
  ): Unit =
    val as     = idp.as
    val prefix = basePath.stripSuffix("/")

    // ─── OIDC-specific routes ────────────────────────────────────────
    // /token: same path as OAuth but routes through OidcRoutes for id_token
    register(ctx, "POST", prefix + "/token", "oidc.http.token") { (body, headers, _) =>
      OidcRoutes.handleToken(idp, body, headers)
    }
    register(ctx, "GET", prefix + "/userinfo", "oidc.http.userinfo") { (body, headers, query) =>
      OidcRoutes.handleUserInfo(idp, body, headers, query)
    }
    // OIDC also tolerates POST on /userinfo (spec §5.3)
    register(ctx, "POST", prefix + "/userinfo", "oidc.http.userinfo.post") { (body, headers, query) =>
      OidcRoutes.handleUserInfo(idp, body, headers, query)
    }
    register(ctx, "GET", prefix + "/.well-known/openid-configuration",
             "oidc.http.discovery") { (_, _, _) => OidcRoutes.handleDiscovery(idp) }

    // ─── OAuth-side endpoints (delegated to OAuthRoutes) ─────────────
    register(ctx, "POST", prefix + "/introspect", "oauth.http.introspect") { (body, headers, _) =>
      OAuthRoutes.handleIntrospect(as, body, headers)
    }
    register(ctx, "POST", prefix + "/revoke", "oauth.http.revoke") { (body, headers, _) =>
      OAuthRoutes.handleRevoke(as, body, headers)
    }
    register(ctx, "POST", prefix + "/register", "oauth.http.register") { (body, headers, _) =>
      OAuthRoutes.handleRegister(as, body, headers)
    }
    register(ctx, "GET", prefix + "/authorize", "oauth.http.authorize") { (_, headers, query) =>
      OAuthRoutes.handleAuthorize(as, query, headers, subjectFor, loginUrl, selfUrl)
    }
    register(ctx, "GET", prefix + "/.well-known/oauth-authorization-server",
             "oauth.http.metadata") { (_, _, _) => OAuthRoutes.handleMetadata(as) }
    register(ctx, "GET", prefix + "/.well-known/jwks.json",
             "oauth.http.jwks") { (_, _, _) => OAuthRoutes.handleJwks(as) }

  // ─── helpers ────────────────────────────────────────────────────────

  private def register(
    ctx:    NativeContext,
    method: String,
    path:   String,
    name:   String
  )(run: (String, Map[String, String], Map[String, String]) => OAuthRoutes.RouteOutcome): Unit =
    val handler = Value.NativeFnV(name, Computation.pureFn {
      case List(Value.InstanceV("Request", fields)) =>
        val body    = fields.get("body").collect { case Value.StringV(s) => s }.getOrElse("")
        val headers = OAuthHttp.extractHeaderMap(fields)
        val query   = extractQueryMap(fields)
        OAuthHttp.routeOutcomeToValue(run(body, headers, query))
      case _ => Value.InstanceV("Response", Map(
        "status"  -> Value.IntV(400L),
        "headers" -> Value.MapV(Map.empty),
        "body"    -> Value.StringV("expected Request")
      ))
    })
    ctx.registerRoute(method, path, handler)

  private def extractQueryMap(fields: Map[String, Value]): Map[String, String] =
    fields.get("query").collect {
      case Value.MapV(m) => m.iterator.collect {
        case (Value.StringV(k), Value.StringV(v)) => k -> v
      }.toMap
    }.orElse(
      fields.get("rawQuery").collect { case Value.StringV(s) => OAuthRoutes.parseForm(s) }
    ).getOrElse(Map.empty)
