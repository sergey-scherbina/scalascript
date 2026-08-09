package scalascript.compiler.plugin.deploy

import scala.util.Try
import java.net.{URI, http => jhttp}
import java.nio.charset.StandardCharsets

/** Consul KV-backed state backend using the Consul HTTP API v1.
 *
 *  Key layout: `<prefix>/<app>/<env>/<target>[/<slot>]`
 *  Lock key:   `<prefix>/<app>/<env>/<target>[/<slot>].lock`
 *
 *  Locking via Consul sessions (PUT /v1/session/create → PUT ?acquire=<session>).
 *
 *  Config keys:
 *    address — Consul base URL (default: http://127.0.0.1:8500)
 *    prefix  — KV prefix (default: ssc-state)
 *    app     — application name (default: app)
 *    token   — Consul ACL token (optional, sent as X-Consul-Token header)
 */
class ConsulStateBackend(config: Map[String, Any]) extends StateBackend:

  private val address = config.get("address").collect { case s: String => s }.getOrElse("http://127.0.0.1:8500")
  private val prefix  = config.get("prefix").collect { case s: String => s }.getOrElse("ssc-state")
  private val app     = config.get("app").collect { case s: String => s }.getOrElse("app")
  private val token   = config.get("token").collect { case s: String => s }

  private val http = jhttp.HttpClient.newHttpClient()

  def read(key: StateKey): Option[StateRecord] =
    val url    = s"$address/v1/kv/${kvKey(key)}?raw"
    val result = get(url)
    if result.statusCode() == 200 then Try(JsonState.parse(result.body())).toOption
    else None

  def write(key: StateKey, record: StateRecord): Unit =
    val url  = s"$address/v1/kv/${kvKey(key)}"
    val body = JsonState.serialize(record)
    val resp = put(url, body)
    if resp.statusCode() >= 400 then throw DeployError(s"[deploy/state/consul/write-failed] HTTP ${resp.statusCode()}")

  def lock(key: StateKey, ttlSeconds: Int): LockHandle =
    val ttl = s"${ttlSeconds}s"
    val sessionBody = s"""{"TTL":"$ttl","Behavior":"delete"}"""
    val sessResp    = put(s"$address/v1/session/create", sessionBody)
    if sessResp.statusCode() >= 400 then throw DeployError(s"[deploy/state/consul/session-failed] HTTP ${sessResp.statusCode()}")
    val sessionId = extractField(sessResp.body(), "ID").getOrElse(
      throw DeployError(s"[deploy/state/consul/session-id-missing] ${sessResp.body()}"))

    val lockUrl  = s"$address/v1/kv/${lockKey(key)}?acquire=$sessionId"
    val lockResp = put(lockUrl, sessionId)
    if lockResp.statusCode() >= 400 || lockResp.body().trim == "false" then
      throw DeployError(s"[deploy/state-lock-contention] lock on ${key.env}/${key.target} already held")

    LockHandle(key, sessionId)

  def unlock(handle: LockHandle): Unit =
    val sessionId = handle.token
    put(s"$address/v1/session/destroy/$sessionId", "")

  private def kvKey(key: StateKey): String =
    val slot = key.slot.map(s => s"/$s").getOrElse("")
    s"$prefix/$app/${key.env}/${key.target}$slot"

  private def lockKey(key: StateKey): String =
    s"${kvKey(key)}.lock"

  private def extractField(json: String, field: String): Option[String] =
    s""""$field"\\s*:\\s*"([^"]+)"""".r.findFirstMatchIn(json).map(_.group(1))

  private def get(url: String): jhttp.HttpResponse[String] =
    val builder = jhttp.HttpRequest.newBuilder(URI.create(url)).GET()
    token.foreach(t => builder.header("X-Consul-Token", t))
    http.send(builder.build(), jhttp.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

  private def put(url: String, body: String): jhttp.HttpResponse[String] =
    val builder = jhttp.HttpRequest.newBuilder(URI.create(url))
      .PUT(jhttp.HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
      .header("Content-Type", "application/json")
    token.foreach(t => builder.header("X-Consul-Token", t))
    http.send(builder.build(), jhttp.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
