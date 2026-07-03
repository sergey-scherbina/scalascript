package scalascript.compiler.plugin.deploy

import scala.util.Try
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.nio.charset.StandardCharsets

/** File-based state backend: `~/.ssc-state/<app>/<env>/<target>.json`.
 *
 *  Lock is a sibling `.lock` file containing the lock token (UUID).
 *  No distributed coordination — safe for single-machine or single-runner CI.
 *
 *  Config keys:
 *    app   — application name (default: "app")
 *    path  — override root directory (default: ~/.ssc-state)
 */
class LocalFileStateBackend(config: Map[String, Any] = Map.empty) extends StateBackend:

  private val root: Path =
    config.get("path").collect { case s: String => Paths.get(s) }
      .getOrElse(Paths.get(System.getProperty("user.home"), ".ssc-state"))

  private val app: String =
    config.get("app").collect { case s: String => s }.getOrElse("app")

  def read(key: StateKey): Option[StateRecord] =
    val p = stateFile(key)
    if Files.exists(p) then
      Try(JsonState.parse(Files.readString(p, StandardCharsets.UTF_8))).toOption
    else None

  def write(key: StateKey, record: StateRecord): Unit =
    val p = stateFile(key)
    Files.createDirectories(p.getParent)
    Files.writeString(p, JsonState.serialize(record), StandardCharsets.UTF_8,
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

  def lock(key: StateKey, ttlSeconds: Int): LockHandle =
    val p     = lockFile(key)
    val token = java.util.UUID.randomUUID().toString
    Files.createDirectories(p.getParent)
    if Files.exists(p) then
      val age = (System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis) / 1000
      if age < ttlSeconds then
        throw DeployError(s"[deploy/state-lock-contention] lock on ${key.env}/${key.target} held (age: ${age}s, ttl: ${ttlSeconds}s)")
    Files.writeString(p, token, StandardCharsets.UTF_8,
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    LockHandle(key, token)

  def unlock(handle: LockHandle): Unit =
    val p = lockFile(handle.key)
    if Files.exists(p) then
      val current = Try(Files.readString(p, StandardCharsets.UTF_8).trim).getOrElse("")
      if current == handle.token then Files.deleteIfExists(p)

  private def stateFile(key: StateKey): Path =
    val slot = key.slot.map(s => s".$s").getOrElse("")
    root.resolve(app).resolve(key.env).resolve(s"${key.target}$slot.json")

  private def lockFile(key: StateKey): Path =
    val slot = key.slot.map(s => s".$s").getOrElse("")
    root.resolve(app).resolve(key.env).resolve(s"${key.target}$slot.lock")
