package scalascript.oauth

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** v1.17.x — production observability for the OAuth AS.  Three
 *  building blocks that pair with the existing `onAuthEvent` audit
 *  hook + the typed `RouteOutcome` HTTP layer:
 *
 *    - `HealthHandler`   — `/health` (live) + `/ready` (ready)
 *    - `Metrics`         — Prometheus-format counter / gauge registry
 *    - `JsonLineAudit`   — file-backed audit log that consumes
 *                          `AuthEvent` and appends a JSON line per
 *                          event.  Wire via `as.onAuthEvent = ...`.
 *
 *  All three are self-contained — no Prometheus client lib, no SLF4J,
 *  no external dependencies.  Drop them on the AS's HTTP layer
 *  alongside the standard `OAuthRoutes`. */

// ─── Health endpoints ────────────────────────────────────────────────

object Health:
  /** Liveness probe — the process is alive + accepting work.
   *  Returns 200 always (the process being able to respond is the
   *  signal).  Kubernetes-style: liveness should NOT depend on
   *  downstreams — that's `readiness`. */
  def liveness: OAuthRoutes.RouteOutcome =
    OAuthRoutes.RouteOutcome.Json(200,
      ujson.Obj("status" -> "ok"),
      Map("Cache-Control" -> "no-store"))

  /** Readiness probe — the AS is ready to serve real traffic.
   *  Caller supplies a `() => Boolean` predicate that checks
   *  downstreams (DB connectivity, signing-key availability, etc.).
   *  Returns 200 when ready, 503 with `{"status":"not_ready"}` otherwise. */
  def readiness(check: () => Boolean): OAuthRoutes.RouteOutcome =
    if check() then
      OAuthRoutes.RouteOutcome.Json(200,
        ujson.Obj("status" -> "ready"),
        Map("Cache-Control" -> "no-store"))
    else
      OAuthRoutes.RouteOutcome.Json(503,
        ujson.Obj("status" -> "not_ready"),
        Map("Cache-Control" -> "no-store"))

// ─── Prometheus-format metrics ───────────────────────────────────────

/** Minimal Prometheus exposition-format registry.  Two metric types:
 *    - counters   — monotonically increasing per-label-set
 *    - gauges     — arbitrarily settable per-label-set
 *
 *  Labels are encoded into a stable cache key (`{a="1",b="2"}`); the
 *  exposition format groups by name with `# HELP` + `# TYPE` headers.
 *
 *  Wire to the audit hook for AS-side telemetry:
 *    val metrics = new Metrics
 *    as.onAuthEvent = {
 *      case AuthEvent.TokenIssued(cid, _, _, gt) =>
 *        metrics.counter("oauth_tokens_issued_total",
 *          "client_id" -> cid, "grant_type" -> gt).inc()
 *      case AuthEvent.TokenRefused(_, gt, err, _) =>
 *        metrics.counter("oauth_tokens_refused_total",
 *          "grant_type" -> gt, "error" -> err).inc()
 *      case _ => ()
 *    } */
class Metrics:
  private val countersByName = ConcurrentHashMap[String, ConcurrentHashMap[String, AtomicLong]]()
  private val gaugesByName   = ConcurrentHashMap[String, ConcurrentHashMap[String, AtomicLong]]()
  private val helpText       = ConcurrentHashMap[String, String]()

  /** Set the help text that prefixes a metric in the exposition output.
   *  Optional but encouraged — Prometheus tooling shows this on hover. */
  def help(name: String, text: String): Unit = helpText.put(name, text)

  /** Get-or-create the counter cell for `(name, labels)`.  Caller uses
   *  `.inc()` / `.inc(n)`. */
  def counter(name: String, labels: (String, String)*): AtomicLong =
    val byLabels = countersByName.computeIfAbsent(name, _ => new ConcurrentHashMap[String, AtomicLong]())
    byLabels.computeIfAbsent(labelKey(labels), _ => new AtomicLong(0L))

  /** Same shape as `counter` for gauges (arbitrarily settable). */
  def gauge(name: String, labels: (String, String)*): AtomicLong =
    val byLabels = gaugesByName.computeIfAbsent(name, _ => new ConcurrentHashMap[String, AtomicLong]())
    byLabels.computeIfAbsent(labelKey(labels), _ => new AtomicLong(0L))

  /** Render the full registry in Prometheus exposition format.
   *  Each metric gets `# HELP` + `# TYPE` headers followed by one
   *  line per label-set.  Use as the body of a `/metrics` route. */
  def render(): String =
    val sb = new StringBuilder
    renderType("counter", countersByName, sb)
    renderType("gauge",   gaugesByName,   sb)
    sb.toString

  /** RouteOutcome wrapping `render()` — for direct mounting on the AS
   *  HTTP layer.  Prometheus scrapers expect `text/plain; version=0.0.4`. */
  def routeOutcome(): OAuthRoutes.RouteOutcome =
    OAuthRoutes.RouteOutcome.Json(200,
      ujson.Str(render()),  // wrap as string for shape uniformity
      Map(
        "Content-Type"  -> "text/plain; version=0.0.4",
        "Cache-Control" -> "no-store"))

  private def renderType(
    kind: String,
    src:  ConcurrentHashMap[String, ConcurrentHashMap[String, AtomicLong]],
    sb:   StringBuilder
  ): Unit =
    val it = src.entrySet().iterator()
    while it.hasNext do
      val e = it.next()
      val name = e.getKey
      val help = Option(helpText.get(name)).getOrElse(s"$kind $name")
      sb.append(s"# HELP $name $help\n")
      sb.append(s"# TYPE $name $kind\n")
      val labelIt = e.getValue.entrySet().iterator()
      while labelIt.hasNext do
        val le = labelIt.next()
        sb.append(name).append(le.getKey).append(' ').append(le.getValue.get()).append('\n')

  private def labelKey(labels: Seq[(String, String)]): String =
    if labels.isEmpty then ""
    else labels.sortBy(_._1)
      .map((k, v) => s"""$k="${v.replace("\"", "\\\"")}"""")
      .mkString("{", ",", "}")

// ─── Standard AS metrics bindings ────────────────────────────────────

object MetricsBinding:
  /** Wire a Metrics registry to an AuthServer's audit hook with a
   *  reasonable default counter set.  Callers can override after
   *  attaching by chaining their own `onAuthEvent`. */
  def attachDefault(as: AuthServer, metrics: Metrics): Unit =
    metrics.help("oauth_tokens_issued_total",   "Tokens successfully issued, by grant type + client")
    metrics.help("oauth_tokens_refused_total",  "Token requests refused, by grant type + error")
    metrics.help("oauth_clients_registered_total", "Clients registered via DCR")
    metrics.help("oauth_codes_issued_total",    "Authorization codes issued")
    metrics.help("oauth_family_burned_total",   "Refresh-token families revoked (reuse detection)")
    metrics.help("oauth_passkey_accepted_total", "Passkey assertions accepted")
    metrics.help("oauth_passkey_rejected_total", "Passkey assertions rejected")

    val prior = as.onAuthEvent
    as.onAuthEvent = e =>
      try prior(e) catch case _: Throwable => ()
      e match
        case AuthEvent.TokenIssued(cid, _, _, gt) =>
          metrics.counter("oauth_tokens_issued_total",
            "client_id" -> cid, "grant_type" -> gt).incrementAndGet()
        case AuthEvent.TokenRefused(_, gt, err, _) =>
          metrics.counter("oauth_tokens_refused_total",
            "grant_type" -> gt, "error" -> err).incrementAndGet()
        case AuthEvent.ClientRegistered(_, _) =>
          metrics.counter("oauth_clients_registered_total").incrementAndGet()
        case AuthEvent.AuthorizationCodeIssued(cid, _, _) =>
          metrics.counter("oauth_codes_issued_total",
            "client_id" -> cid).incrementAndGet()
        case AuthEvent.RefreshFamilyBurned(_, reason, n) =>
          metrics.counter("oauth_family_burned_total",
            "reason" -> reason).addAndGet(n.toLong)
        case AuthEvent.PasskeyAccepted(_, _) =>
          metrics.counter("oauth_passkey_accepted_total").incrementAndGet()
        case AuthEvent.PasskeyRejected(_, reason) =>
          metrics.counter("oauth_passkey_rejected_total",
            "reason" -> reason).incrementAndGet()
        case _ => ()

// ─── File-backed audit log ───────────────────────────────────────────

/** Consumes `AuthEvent` and appends one JSON line per event to a file.
 *  Designed to be wired via `as.onAuthEvent = audit.handler`.  Output
 *  format matches the standard JSON-line shape SIEM tools expect:
 *
 *    {"ts":"2026-05-20T08:00:00Z","event":"TokenIssued",
 *     "clientId":"...","subject":"...","scope":["read"],"grantType":"..."}
 *
 *  Sensitive fields are scrubbed via `OAuthRoutes.scrubSensitive` so
 *  bearer tokens never reach the log file. */
class JsonLineAudit(val path: Path):
  private val writer = new Object  // append serialisation lock

  /** Hook function — assign to `as.onAuthEvent`. */
  val handler: AuthEvent => Unit = event =>
    try append(encodeEvent(event))
    catch case _: Throwable => ()  // never propagate to the AS hot path

  private def append(event: ujson.Value): Unit = writer.synchronized {
    Files.write(path, (event.render() + "\n").getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE, StandardOpenOption.APPEND)
  }

  private def encodeEvent(e: AuthEvent): ujson.Value =
    val obj = ujson.Obj("ts" -> java.time.Instant.now.toString)
    e match
      case AuthEvent.TokenIssued(cid, sub, scope, gt) =>
        obj("event")     = "TokenIssued"
        obj("clientId")  = cid
        obj("subject")   = sub
        obj("scope")     = ujson.Arr.from(scope.toList.sorted.map(ujson.Str(_)))
        obj("grantType") = gt
      case AuthEvent.TokenRefused(cid, gt, err, descr) =>
        obj("event")       = "TokenRefused"
        obj("clientId")    = cid
        obj("grantType")   = gt
        obj("error")       = err
        obj("description") = descr
      case AuthEvent.TokenRevoked(jti, hint) =>
        obj("event") = "TokenRevoked"
        obj("jti")   = jti
        obj("hint")  = hint
      case AuthEvent.ClientRegistered(cid, uris) =>
        obj("event")        = "ClientRegistered"
        obj("clientId")     = cid
        obj("redirectUris") = ujson.Arr.from(uris.toList.sorted.map(ujson.Str(_)))
      case AuthEvent.AuthorizationCodeIssued(cid, sub, scope) =>
        obj("event")    = "AuthorizationCodeIssued"
        obj("clientId") = cid
        obj("subject")  = sub
        obj("scope")    = ujson.Arr.from(scope.toList.sorted.map(ujson.Str(_)))
      case AuthEvent.PasskeyAccepted(credId, sub) =>
        obj("event")        = "PasskeyAccepted"
        obj("credentialId") = credId
        obj("subject")      = sub
      case AuthEvent.PasskeyRejected(credId, reason) =>
        obj("event")        = "PasskeyRejected"
        obj("credentialId") = credId
        obj("reason")       = reason
      case AuthEvent.RefreshFamilyBurned(fid, reason, n) =>
        obj("event")          = "RefreshFamilyBurned"
        obj("familyId")       = fid
        obj("reason")         = reason
        obj("tokensRevoked")  = ujson.Num(n.toDouble)
    obj
