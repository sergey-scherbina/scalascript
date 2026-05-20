package scalascript.cli

import scalascript.oauth.*

/** v1.17.x — `ssc oauth ...` admin / debug subcommands.  Reachable
 *  from Main.scala via `case "oauth" => OAuthCli.run(args.tail)`.
 *
 *  Five commands, each backed by the in-process `scalascript.oauth`
 *  helpers (no network for `mint` / `introspect`, network for
 *  `discover` / `dcr-register` / `jwks`):
 *
 *    ssc oauth discover     <issuer>            — fetch + pretty-print AS metadata
 *    ssc oauth jwks         <issuer>            — fetch the AS's JWKS document
 *    ssc oauth dcr-register <issuer> <redirect> — register a new client via DCR
 *    ssc oauth mint         <secret> <sub> [scopes…]
 *                                               — issue a quick HS256 test token
 *    ssc oauth introspect   <secret> <token>    — decode + show JWT claims */
object OAuthCli:

  def run(args: List[String]): Unit =
    args.headOption match
      case Some("discover")     => discover(args.tail)
      case Some("jwks")         => jwks(args.tail)
      case Some("dcr-register") => dcrRegister(args.tail)
      case Some("mint")         => mint(args.tail)
      case Some("introspect")   => introspect(args.tail)
      case Some("help") | Some("--help") | Some("-h") | None => usage()
      case Some(other) =>
        System.err.println(s"Unknown subcommand: $other")
        usage()
        sys.exit(2)

  private def usage(): Unit =
    println(
      """ssc oauth — OAuth 2.1 admin + debug commands
        |
        |Usage:
        |  ssc oauth discover     <issuer>
        |  ssc oauth jwks         <issuer>
        |  ssc oauth dcr-register <issuer> <redirect-uri> [more redirect URIs…]
        |  ssc oauth mint         <secret> <subject> [scopes…]
        |  ssc oauth introspect   <secret> <token>
        |
        |Discover, register and inspect tokens against any OAuth 2.1 AS
        |that follows RFC 8414 + RFC 7591.  The `mint` and `introspect`
        |commands act locally on HS256 secrets — no network round-trip —
        |so they work offline for test fixtures.""".stripMargin)

  // ─── discover ──────────────────────────────────────────────────────

  private def discover(args: List[String]): Unit =
    args.headOption match
      case Some(issuer) =>
        try println(OAuthClient.discoverAs(issuer).render(indent = 2))
        catch case e: Throwable =>
          System.err.println(s"discover failed: ${e.getMessage}")
          sys.exit(1)
      case None =>
        System.err.println("ssc oauth discover <issuer>")
        sys.exit(2)

  // ─── jwks ──────────────────────────────────────────────────────────

  private def jwks(args: List[String]): Unit =
    args.headOption match
      case Some(issuer) =>
        try
          val url = issuer.stripSuffix("/") + "/.well-known/jwks.json"
          val req = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .timeout(java.time.Duration.ofMillis(5000))
            .GET().build()
          val resp = java.net.http.HttpClient.newBuilder().build()
            .send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
          if resp.statusCode() != 200 then
            System.err.println(s"jwks fetch returned HTTP ${resp.statusCode()}")
            sys.exit(1)
          else
            println(ujson.read(resp.body()).render(indent = 2))
        catch case e: Throwable =>
          System.err.println(s"jwks fetch failed: ${e.getMessage}")
          sys.exit(1)
      case None =>
        System.err.println("ssc oauth jwks <issuer>")
        sys.exit(2)

  // ─── dcr-register ──────────────────────────────────────────────────

  private def dcrRegister(args: List[String]): Unit =
    if args.length < 2 then
      System.err.println("ssc oauth dcr-register <issuer> <redirect-uri> [more redirect URIs…]")
      sys.exit(2)
    else
      val issuer    = args(0)
      val redirects = args.tail.toList
      try
        // Discover the registration endpoint via metadata
        val md       = OAuthClient.discoverAs(issuer)
        val regUri   = md.obj.get("registration_endpoint").flatMap(_.strOpt)
        regUri match
          case None =>
            System.err.println(s"AS at $issuer doesn't advertise registration_endpoint (DCR disabled)")
            sys.exit(1)
          case Some(url) =>
            val body = ujson.Obj(
              "redirect_uris" -> ujson.Arr.from(redirects.map(ujson.Str(_)))
            ).render()
            val req = java.net.http.HttpRequest.newBuilder()
              .uri(java.net.URI.create(url))
              .timeout(java.time.Duration.ofMillis(5000))
              .header("Content-Type", "application/json")
              .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
              .build()
            val resp = java.net.http.HttpClient.newBuilder().build()
              .send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            if resp.statusCode() != 201 && resp.statusCode() != 200 then
              System.err.println(s"DCR returned HTTP ${resp.statusCode()}: ${resp.body()}")
              sys.exit(1)
            else
              println(ujson.read(resp.body()).render(indent = 2))
      catch case e: Throwable =>
        System.err.println(s"DCR failed: ${e.getMessage}")
        sys.exit(1)

  // ─── mint (test token) ─────────────────────────────────────────────

  private def mint(args: List[String]): Unit =
    if args.length < 2 then
      System.err.println("ssc oauth mint <secret> <subject> [scopes…]")
      sys.exit(2)
    else
      val secret  = args(0)
      val subject = args(1)
      val scopes  = args.drop(2).toSet
      if secret.length < 32 then
        System.err.println(
          s"WARN: secret is ${secret.length} bytes — RFC 7518 §3.2 recommends ≥ 32 for HS256")
      val token = OAuth.issueHmacToken(secret, subject, scopes, 3600L)
      println(token)

  // ─── introspect (decode + claim summary) ──────────────────────────

  private def introspect(args: List[String]): Unit =
    if args.length < 2 then
      System.err.println("ssc oauth introspect <secret> <token>")
      sys.exit(2)
    else
      val secret = args(0)
      val token  = args(1)
      OAuth.decodeHmacToken(secret, token) match
        case Left(reason) =>
          System.err.println(s"introspect failed: $reason")
          sys.exit(1)
        case Right(payload) =>
          println(payload.render(indent = 2))
