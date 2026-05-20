package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*

/** v1.17.x — Iter PP: final security hardening pass.  Closes the
 *  minor gaps the honest audit flagged: body size DoS, missing
 *  security headers, weak HMAC secret silently accepted, sensitive
 *  data potentially leaking through logs. */
class OAuthFinalHardeningTest extends AnyFunSuite with Matchers:

  private def newAs(cfg: AuthServerConfig => AuthServerConfig = identity): AuthServer =
    val as = new AuthServer(cfg(AuthServerConfig(
      issuer        = "https://auth.x",
      signingSecret = "k" * 40,  // long enough — no warning
      supportedScopes = Set("read"))))
    as.clients.register(Client(
      id = "svc", secret = Some("s"), redirectUris = Set.empty,
      scopes = Set("read"), grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential))
    as

  // ─── Body size guard ────────────────────────────────────────────

  test("payload too large → 413"):
    val as = newAs(_.copy(maxRequestBytes = 50))
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s" +
      "&padding=" + ("X" * 100)
    OAuthRoutes.handleToken(as, body, Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(413, js, _) =>
        js("error").str shouldBe "invalid_request"
        js("error_description").str should include ("body")
      case other => fail(s"got $other")

  test("payload within limit → normal processing"):
    val as = newAs()
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read"
    OAuthRoutes.handleToken(as, body, Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(200, _, _) => succeed
      case other => fail(s"got $other")

  test("default maxRequestBytes is 64 KiB (large form bodies allowed)"):
    val as = newAs()
    as.config.maxRequestBytes shouldBe 65_536

  // ─── Security headers ───────────────────────────────────────────

  test("security headers attached by default"):
    val as = newAs()
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read"
    OAuthRoutes.handleToken(as, body, Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(200, _, hdrs) =>
        hdrs("X-Content-Type-Options") shouldBe "nosniff"
        hdrs("X-Frame-Options")         shouldBe "DENY"
        hdrs("Referrer-Policy")         shouldBe "no-referrer"
        // HSTS only with TLS enabled
        hdrs.keys.find(_.equalsIgnoreCase("Strict-Transport-Security")) shouldBe None
      case other => fail(s"got $other")

  test("HSTS header attached when requireTls is on"):
    val as = newAs(_.copy(requireTls = true))
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read"
    OAuthRoutes.handleToken(as, body,
      Map("Host" -> "auth.example.com", "X-Forwarded-Proto" -> "https")) match
      case OAuthRoutes.RouteOutcome.Json(200, _, hdrs) =>
        hdrs("Strict-Transport-Security") should include ("max-age=")
      case other => fail(s"got $other")

  test("security headers OFF respects the config flag"):
    val as = newAs(_.copy(securityHeaders = false))
    val body = "grant_type=client_credentials&client_id=svc&client_secret=s&scope=read"
    OAuthRoutes.handleToken(as, body, Map.empty) match
      case OAuthRoutes.RouteOutcome.Json(200, _, hdrs) =>
        hdrs.keys.find(_.equalsIgnoreCase("X-Content-Type-Options")) shouldBe None
        hdrs.keys.find(_.equalsIgnoreCase("X-Frame-Options"))        shouldBe None
      case other => fail(s"got $other")

  // ─── HMAC secret strength warning ───────────────────────────────

  test("AS with short HMAC secret surfaces a startup warning"):
    val as = new AuthServer(AuthServerConfig(
      issuer = "https://x", signingSecret = "short"))
    as.signingSecretWarning.isDefined shouldBe true
    as.signingSecretWarning.get should include ("RFC 7518")

  test("AS with long HMAC secret has no warning"):
    val as = new AuthServer(AuthServerConfig(
      issuer = "https://x", signingSecret = "k" * 32))
    as.signingSecretWarning shouldBe None

  test("AS with custom RSA signer skips the HMAC check"):
    val as = new AuthServer(
      AuthServerConfig(issuer = "https://x", signingSecret = "short-unused"),
      customSigner = Some(OAuth.RsaTokenSigner.generate()))
    as.signingSecretWarning shouldBe None

  // ─── Log scrubbing ──────────────────────────────────────────────

  test("scrubSensitive: Authorization bearer header"):
    val line = """request: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"""
    OAuthRoutes.scrubSensitive(line) should include ("<redacted>")
    OAuthRoutes.scrubSensitive(line) should not include ("eyJ")

  test("scrubSensitive: form-encoded sensitive params"):
    val line = "POST body: grant_type=refresh_token&refresh_token=tok-abc&client_secret=hush"
    val cleaned = OAuthRoutes.scrubSensitive(line)
    cleaned should include ("refresh_token=<redacted>")
    cleaned should include ("client_secret=<redacted>")
    cleaned should not include ("tok-abc")
    cleaned should not include ("hush")
    // grant_type itself is fine to keep
    cleaned should include ("grant_type=refresh_token")

  test("scrubSensitive: JSON access_token + refresh_token"):
    val line = """{"access_token":"sec-1","refresh_token":"ref-2","scope":"read"}"""
    val cleaned = OAuthRoutes.scrubSensitive(line)
    cleaned should include ("\"access_token\":\"<redacted>\"")
    cleaned should include ("\"refresh_token\":\"<redacted>\"")
    cleaned should include ("\"scope\":\"read\"")  // non-sensitive preserved
    cleaned should not include ("sec-1")
    cleaned should not include ("ref-2")

  test("scrubSensitive: null + empty input safe"):
    OAuthRoutes.scrubSensitive(null) shouldBe ""
    OAuthRoutes.scrubSensitive("")   shouldBe ""
    OAuthRoutes.scrubSensitive("nothing to scrub") shouldBe "nothing to scrub"

  test("scrubSensitive: code_verifier (PKCE)"):
    val line = "POST /token: code=abc&code_verifier=verifier-secret"
    val cleaned = OAuthRoutes.scrubSensitive(line)
    cleaned should include ("code_verifier=<redacted>")
    cleaned should not include ("verifier-secret")
