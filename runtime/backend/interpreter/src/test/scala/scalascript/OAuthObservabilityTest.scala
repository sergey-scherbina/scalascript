package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*
import java.nio.file.Files

/** v1.17.x — Iter SS: production observability for the AS.  Three
 *  building blocks: health/ready endpoints, Prometheus metrics,
 *  file-backed audit log. */
class OAuthObservabilityTest extends AnyFunSuite with Matchers:

  // ─── Health endpoints ─────────────────────────────────────────────

  test("Health.liveness: always 200"):
    Health.liveness match
      case OAuthRoutes.RouteOutcome.Json(200, js, hdrs) =>
        js("status").str shouldBe "ok"
        hdrs("Cache-Control") shouldBe "no-store"
      case other => fail(s"got $other")

  test("Health.readiness: 200 when check passes"):
    Health.readiness(() => true) match
      case OAuthRoutes.RouteOutcome.Json(200, js, _) =>
        js("status").str shouldBe "ready"
      case other => fail(s"got $other")

  test("Health.readiness: 503 when check fails"):
    Health.readiness(() => false) match
      case OAuthRoutes.RouteOutcome.Json(503, js, _) =>
        js("status").str shouldBe "not_ready"
      case other => fail(s"got $other")

  // ─── Metrics registry ─────────────────────────────────────────────

  test("Metrics: counter increments + renders in Prometheus format"):
    val m = new Metrics
    m.help("test_counter", "A test counter")
    m.counter("test_counter").incrementAndGet()
    m.counter("test_counter").incrementAndGet()
    val out = m.render()
    out should include ("# HELP test_counter A test counter")
    out should include ("# TYPE test_counter counter")
    out should include ("test_counter 2")

  test("Metrics: labels surface in the exposition output"):
    val m = new Metrics
    m.counter("requests_total", "method" -> "POST", "status" -> "200").incrementAndGet()
    m.counter("requests_total", "method" -> "POST", "status" -> "500").incrementAndGet()
    m.counter("requests_total", "method" -> "POST", "status" -> "500").incrementAndGet()
    val out = m.render()
    out should include ("""requests_total{method="POST",status="200"} 1""")
    out should include ("""requests_total{method="POST",status="500"} 2""")

  test("Metrics: gauges are independent of counters"):
    val m = new Metrics
    m.counter("c").incrementAndGet()
    m.gauge("g").set(42L)
    val out = m.render()
    out should include ("# TYPE c counter")
    out should include ("# TYPE g gauge")
    out should include ("c 1")
    out should include ("g 42")

  test("Metrics: routeOutcome content-type is Prometheus exposition"):
    val m = new Metrics
    m.counter("x").incrementAndGet()
    m.routeOutcome() match
      case OAuthRoutes.RouteOutcome.Json(200, _, hdrs) =>
        hdrs("Content-Type") should include ("text/plain")
        hdrs("Content-Type") should include ("version=0.0.4")
      case other => fail(s"got $other")

  // ─── MetricsBinding: AuthServer audit hook → counters ────────────

  test("MetricsBinding: TokenIssued increments per-client + per-grant counters"):
    val as = new AuthServer(AuthServerConfig(
      issuer = "https://x", signingSecret = "k" * 40,
      supportedScopes = Set("read")))
    as.clients.register(Client(
      id = "svc", secret = Some("s"), redirectUris = Set.empty,
      scopes = Set("read"), grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential))
    val metrics = new Metrics
    MetricsBinding.attachDefault(as, metrics)
    as.issueToken(TokenRequest.ClientCredentialsGrant("svc", "s", Set("read")))
    as.issueToken(TokenRequest.ClientCredentialsGrant("svc", "s", Set("read")))
    val out = metrics.render()
    out should include ("oauth_tokens_issued_total")
    out should include ("""client_id="svc"""")
    out should include ("""grant_type="client_credentials"""")
    out should include ("2")  // two issuances

  test("MetricsBinding: TokenRefused increments error counter"):
    val as = new AuthServer(AuthServerConfig("https://x", "k" * 40))
    as.clients.register(Client(
      id = "svc", secret = Some("right"), redirectUris = Set.empty,
      scopes = Set("read"), grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential))
    val metrics = new Metrics
    MetricsBinding.attachDefault(as, metrics)
    // Drive a wire-level call so the TokenRefused event fires
    OAuthRoutes.handleToken(as,
      "grant_type=client_credentials&client_id=svc&client_secret=WRONG&scope=read",
      Map.empty)
    val out = metrics.render()
    out should include ("oauth_tokens_refused_total")
    out should include ("""error="invalid_client"""")

  test("MetricsBinding: prior onAuthEvent listener still runs after attach"):
    val as = new AuthServer(AuthServerConfig(
      issuer = "https://x", signingSecret = "k" * 40,
      supportedScopes = Set("read")))
    as.clients.register(Client("svc", Some("s"), Set.empty, Set("read"),
      grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential))
    // Wire a counter on the AS hook BEFORE attaching metrics
    val priorCalls = new java.util.concurrent.atomic.AtomicInteger(0)
    as.onAuthEvent = _ => priorCalls.incrementAndGet()
    val metrics = new Metrics
    MetricsBinding.attachDefault(as, metrics)
    // Issue a token — both the prior listener and the metrics should fire
    as.issueToken(TokenRequest.ClientCredentialsGrant("svc", "s", Set("read")))
    priorCalls.get shouldBe 1
    metrics.render() should include ("oauth_tokens_issued_total")

  // ─── JsonLineAudit: file-backed audit log ────────────────────────

  test("JsonLineAudit: appends one JSON line per event"):
    val p = Files.createTempFile("audit-", ".jsonl")
    Files.deleteIfExists(p)
    val as = new AuthServer(AuthServerConfig(
      issuer = "https://x", signingSecret = "k" * 40,
      supportedScopes = Set("read")))
    as.clients.register(Client("svc", Some("s"), Set.empty, Set("read"),
      grantTypes = Set("client_credentials"),
      clientType = ClientType.Confidential))
    val audit = new JsonLineAudit(p)
    as.onAuthEvent = audit.handler
    as.issueToken(TokenRequest.ClientCredentialsGrant("svc", "s", Set("read")))
    as.registerClient(ujson.Obj(
      "redirect_uris" -> ujson.Arr("http://x/cb"),
      "scope"         -> "read"))
    val lines = Files.readAllLines(p)
    lines.size should be >= 2
    val first = ujson.read(lines.get(0))
    first("event").str shouldBe "TokenIssued"
    first.obj.contains("ts") shouldBe true
    first("clientId").str shouldBe "svc"
    first("grantType").str shouldBe "client_credentials"
    Files.deleteIfExists(p)

  test("JsonLineAudit: handler swallows write errors (no AS hot-path break)"):
    // Write to a path the FS can't touch — handler must not throw
    val badPath = java.nio.file.Paths.get("/proc/no-such-dir/audit.jsonl")
    val audit = new JsonLineAudit(badPath)
    // Should not throw even though the write fails
    audit.handler(AuthEvent.TokenIssued("c", "s", Set("read"), "client_credentials"))
    succeed
