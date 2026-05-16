package scalascript.server

import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URI

/** OAuth2 / OIDC helpers — authorization-code flow.
 *
 *  Two operations cover 90% of real use:
 *
 *  1. `authorizeUrl(provider, clientId, redirectUri, state, scope?)`
 *     — pure URL builder.  Hand-rolling these is the part developers
 *     get wrong (mismatched scopes, missing `response_type=code`,
 *     state forgotten); this packs the right defaults per provider.
 *
 *  2. `exchangeCode(provider, code, clientId, clientSecret, redirectUri)`
 *     — POSTs to the provider's token endpoint and returns the
 *     decoded JSON object as `Option[Map[String, String]]`.  Token,
 *     refresh token, expiry seconds, scope etc. are surfaced as the
 *     keys the provider sends.  Returns `None` on non-2xx or malformed
 *     responses; callers can `.flatMap(_.get("access_token"))` to get
 *     the token to use against the provider's API.
 *
 *  Built-in providers: `"google"` and `"github"`.  Custom providers
 *  can be supplied as `Map("authorizeUrl" -> ..., "tokenUrl" -> ...,
 *  "defaultScope" -> ...)`.
 *
 *  State token: the caller generates one (e.g. `csrfToken()`), stashes
 *  it in the session, and verifies it matches `req.query("state")` on
 *  the callback.  This module does NOT manage state — keeps the surface
 *  small and lets sessions/CSRF do their job. */
object OAuth:

  /** Preset provider configs.  Each entry's `authorizeUrl` is missing
   *  query params (built in `authorizeUrl`); `tokenUrl` is hit as-is
   *  with form-encoded body.  User-registered providers via
   *  [[registerProvider]] are layered on top — they can override
   *  individual fields or introduce wholly new providers. */
  private val builtin: Map[String, Map[String, String]] = Map(
    "google" -> Map(
      "authorizeUrl" -> "https://accounts.google.com/o/oauth2/v2/auth",
      "tokenUrl"     -> "https://oauth2.googleapis.com/token",
      "userinfoUrl"  -> "https://www.googleapis.com/oauth2/v3/userinfo",
      "defaultScope" -> "openid email profile",
    ),
    "github" -> Map(
      "authorizeUrl" -> "https://github.com/login/oauth/authorize",
      "tokenUrl"     -> "https://github.com/login/oauth/access_token",
      "userinfoUrl"  -> "https://api.github.com/user",
      "defaultScope" -> "user:email",
    ),
  )

  /** Runtime-registered providers — merged on top of `builtin`.  Each
   *  call replaces / overrides any prior entry under the same name. */
  private val custom: java.util.concurrent.ConcurrentHashMap[String, Map[String, String]] =
    java.util.concurrent.ConcurrentHashMap[String, Map[String, String]]()

  /** Register a new OAuth provider (or override fields of a built-in).
   *  Required keys: `authorizeUrl`, `tokenUrl`.  Optional: `userinfoUrl`
   *  (`oauthUserinfo` returns None if absent) and `defaultScope`. */
  def registerProvider(name: String, config: Map[String, String]): Unit =
    custom.put(name, config)

  /** All known providers — builtins layered under any runtime override. */
  def providers: Map[String, Map[String, String]] =
    builtin ++ scala.jdk.CollectionConverters.MapHasAsScala(custom).asScala.toMap

  private def cfg(provider: String, override_ : Map[String, String]): Map[String, String] =
    providers.getOrElse(provider, Map.empty) ++ override_

  private def urlEnc(s: String): String = URLEncoder.encode(s, "UTF-8")

  /** Build the provider's authorize URL.  Caller picks a random
   *  `state`, stashes it in the session, and verifies on callback. */
  def authorizeUrl(
      provider:    String,
      clientId:    String,
      redirectUri: String,
      state:       String,
      scope:       String = "",
      extras:      Map[String, String] = Map.empty,
      providerCfg: Map[String, String] = Map.empty
  ): String =
    val c    = cfg(provider, providerCfg)
    val base = c.getOrElse("authorizeUrl",
      throw IllegalArgumentException(s"unknown OAuth provider: $provider"))
    val effectiveScope =
      if scope.nonEmpty then scope else c.getOrElse("defaultScope", "")
    val params = scala.collection.mutable.LinkedHashMap[String, String](
      "response_type" -> "code",
      "client_id"     -> clientId,
      "redirect_uri"  -> redirectUri,
      "state"         -> state,
    )
    if effectiveScope.nonEmpty then params("scope") = effectiveScope
    extras.foreach((k, v) => params(k) = v)
    val qs = params.iterator.map((k, v) => s"${urlEnc(k)}=${urlEnc(v)}").mkString("&")
    s"$base?$qs"

  /** Exchange an authorization code for tokens.  Returns the parsed
   *  response body as `Map[String, String]` on 2xx, otherwise `None`. */
  def exchangeCode(
      provider:     String,
      code:         String,
      clientId:     String,
      clientSecret: String,
      redirectUri:  String,
      providerCfg:  Map[String, String] = Map.empty
  ): Option[Map[String, String]] =
    val c        = cfg(provider, providerCfg)
    val tokenUrl = c.getOrElse("tokenUrl",
      throw IllegalArgumentException(s"unknown OAuth provider: $provider"))
    val form = Map(
      "grant_type"    -> "authorization_code",
      "code"          -> code,
      "client_id"     -> clientId,
      "client_secret" -> clientSecret,
      "redirect_uri"  -> redirectUri,
    )
    val body = form.iterator.map((k, v) => s"${urlEnc(k)}=${urlEnc(v)}").mkString("&")
    try
      val client = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build()
      val req = HttpRequest.newBuilder()
        .uri(URI.create(tokenUrl))
        .timeout(java.time.Duration.ofSeconds(30))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() < 200 || resp.statusCode() >= 300 then None
      else parseTokenResponse(resp.body(), resp.headers().firstValue("content-type").orElse(""))
    catch case _: Throwable => None

  /** Refresh-token grant: trade a long-lived refresh token for a fresh
   *  access token.  Returns the parsed token response just like
   *  `exchangeCode`; providers typically include a new `access_token`,
   *  `expires_in`, and sometimes a rotated `refresh_token`. */
  def refreshToken(
      provider:     String,
      refreshToken: String,
      clientId:     String,
      clientSecret: String,
      providerCfg:  Map[String, String] = Map.empty
  ): Option[Map[String, String]] =
    val c        = cfg(provider, providerCfg)
    val tokenUrl = c.getOrElse("tokenUrl",
      throw IllegalArgumentException(s"unknown OAuth provider: $provider"))
    val form = Map(
      "grant_type"    -> "refresh_token",
      "refresh_token" -> refreshToken,
      "client_id"     -> clientId,
      "client_secret" -> clientSecret,
    )
    val body = form.iterator.map((k, v) => s"${urlEnc(k)}=${urlEnc(v)}").mkString("&")
    try
      val client = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build()
      val req = HttpRequest.newBuilder()
        .uri(URI.create(tokenUrl))
        .timeout(java.time.Duration.ofSeconds(30))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept",        "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() < 200 || resp.statusCode() >= 300 then None
      else parseTokenResponse(resp.body(), resp.headers().firstValue("content-type").orElse(""))
    catch case _: Throwable => None

  /** Fetch the provider's userinfo endpoint with the given access token.
   *  Returns the parsed JSON object as `Map[String, String]` (nested
   *  objects are flattened to their `toString`).  `None` on non-2xx
   *  or malformed responses.  GitHub requires a User-Agent header;
   *  we send a generic one. */
  def userinfo(
      provider:    String,
      accessToken: String,
      providerCfg: Map[String, String] = Map.empty
  ): Option[Map[String, String]] =
    val c   = cfg(provider, providerCfg)
    val url = c.getOrElse("userinfoUrl",
      throw IllegalArgumentException(s"no userinfoUrl for provider: $provider"))
    try
      val client = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build()
      val req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(java.time.Duration.ofSeconds(30))
        .header("Authorization", s"Bearer $accessToken")
        .header("Accept",        "application/json")
        .header("User-Agent",    "scalascript-oauth/0.6")
        .GET()
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() < 200 || resp.statusCode() >= 300 then None
      else parseJsonObject(resp.body())
    catch case _: Throwable => None

  /** Provider responses are either `application/json` or
   *  `application/x-www-form-urlencoded` (GitHub's default).  Accept
   *  both. */
  private def parseTokenResponse(body: String, contentType: String): Option[Map[String, String]] =
    if contentType.toLowerCase.contains("application/json") || body.trim.startsWith("{") then
      parseJsonObject(body)
    else
      Some(body.split('&').iterator.flatMap { pair =>
        val i = pair.indexOf('=')
        if i < 0 then None
        else Some(
          java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8") ->
          java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8"))
      }.toMap)

  /** Tiny JSON-object reader: keys + string/number/bool values only.
   *  Provider token responses don't nest, so this is enough. */
  private def parseJsonObject(s: String): Option[Map[String, String]] =
    val t = s.trim
    if !t.startsWith("{") || !t.endsWith("}") then None
    else
      val inner = t.substring(1, t.length - 1).trim
      if inner.isEmpty then Some(Map.empty)
      else try
        val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
        var i   = 0
        def skipWs(): Unit = while i < inner.length && inner.charAt(i).isWhitespace do i += 1
        def readStr(): String =
          if inner.charAt(i) != '"' then throw RuntimeException("expected quote")
          i += 1
          val sb = StringBuilder()
          while i < inner.length && inner.charAt(i) != '"' do
            val c = inner.charAt(i)
            if c == '\\' && i + 1 < inner.length then
              inner.charAt(i + 1) match
                case '"'  => sb.append('"');  i += 2
                case '\\' => sb.append('\\'); i += 2
                case 'n'  => sb.append('\n'); i += 2
                case 'r'  => sb.append('\r'); i += 2
                case 't'  => sb.append('\t'); i += 2
                case _    => sb.append(c); i += 1
            else { sb.append(c); i += 1 }
          i += 1
          sb.toString
        def readScalar(): String =
          val sb = StringBuilder()
          while i < inner.length && inner.charAt(i) != ',' && inner.charAt(i) != '}' do
            sb.append(inner.charAt(i)); i += 1
          sb.toString.trim
        // Skip a nested JSON value (object or array) and return its raw
        // source so the caller surfaces it as a single string.  Balances
        // braces/brackets and respects string literals containing them.
        def readNested(open: Char, close: Char): String =
          val sb = StringBuilder().append(inner.charAt(i)); i += 1
          var depth = 1
          while i < inner.length && depth > 0 do
            val c = inner.charAt(i)
            sb.append(c)
            if c == '"' then
              i += 1
              while i < inner.length && inner.charAt(i) != '"' do
                if inner.charAt(i) == '\\' && i + 1 < inner.length then
                  sb.append(inner.charAt(i)).append(inner.charAt(i + 1)); i += 2
                else { sb.append(inner.charAt(i)); i += 1 }
              if i < inner.length then { sb.append('"'); i += 1 }
            else
              if c == open  then depth += 1
              if c == close then depth -= 1
              i += 1
          sb.toString
        while i < inner.length do
          skipWs(); val k = readStr()
          skipWs()
          if inner.charAt(i) != ':' then throw RuntimeException("expected colon")
          i += 1
          skipWs()
          val v =
            if inner.charAt(i) == '"' then readStr()
            else if inner.charAt(i) == '{' then readNested('{', '}')
            else if inner.charAt(i) == '[' then readNested('[', ']')
            else readScalar()
          out(k) = v
          skipWs()
          if i < inner.length then
            if inner.charAt(i) != ',' then throw RuntimeException("expected comma")
            i += 1
            skipWs()
        Some(out.toMap)
      catch case _: Throwable => None
