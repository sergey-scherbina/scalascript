package scalascript.compiler.plugin.deploy

import scala.util.Try

/** etcd v3 state backend using `etcdctl` subprocess — no SDK dependency.
 *
 *  Key layout: `<prefix>/<app>/<env>/<target>[/<slot>]`
 *  Lock key:   `<prefix>/<app>/<env>/<target>[/<slot>].lock`
 *
 *  Locking via etcd lease-backed key with `etcdctl lease grant` + `put --lease`.
 *
 *  Config keys:
 *    endpoints — etcd endpoints (default: http://127.0.0.1:2379)
 *    prefix    — key prefix (default: /ssc-state)
 *    app       — application name (default: app)
 *    username  — etcd username (optional)
 *    password  — etcd password (optional)
 */
class EtcdStateBackend(config: Map[String, Any]) extends StateBackend:

  private val endpoints = config.get("endpoints").collect { case s: String => s }.getOrElse("http://127.0.0.1:2379")
  private val prefix    = config.get("prefix").collect { case s: String => s }.getOrElse("/ssc-state")
  private val app       = config.get("app").collect { case s: String => s }.getOrElse("app")
  private val username  = config.get("username").collect { case s: String => s }
  private val password  = config.get("password").collect { case s: String => s }

  def read(key: StateKey): Option[StateRecord] =
    val k      = etcdKey(key)
    val result = etcd(List("get", k, "--print-value-only"))
    if result.exitCode != 0 || result.stdout.isBlank then None
    else Try(JsonState.parse(result.stdout)).toOption

  def write(key: StateKey, record: StateRecord): Unit =
    val k      = etcdKey(key)
    val result = etcd(List("put", k, JsonState.serialize(record)))
    if result.exitCode != 0 then throw DeployError(s"[deploy/state/etcd/write-failed] ${result.stderr}")

  def lock(key: StateKey, ttlSeconds: Int): LockHandle =
    val leaseResult = etcd(List("lease", "grant", ttlSeconds.toString))
    if leaseResult.exitCode != 0 then throw DeployError(s"[deploy/state/etcd/lease-failed] ${leaseResult.stderr}")
    val leaseId = extractLeaseId(leaseResult.stdout).getOrElse(
      throw DeployError(s"[deploy/state/etcd/lease-id-missing] ${leaseResult.stdout}"))

    val lockK   = lockKey(key)
    val token   = java.util.UUID.randomUUID().toString
    val putResult = etcd(List("put", "--lease", leaseId, lockK, token))
    if putResult.exitCode != 0 then
      throw DeployError(s"[deploy/state-lock-contention] lock on ${key.env}/${key.target} already held")

    LockHandle(key, leaseId)

  def unlock(handle: LockHandle): Unit =
    etcd(List("lease", "revoke", handle.token))

  private def etcdKey(key: StateKey): String =
    val slot = key.slot.map(s => s"/$s").getOrElse("")
    s"$prefix/$app/${key.env}/${key.target}$slot"

  private def lockKey(key: StateKey): String =
    s"${etcdKey(key)}.lock"

  private def extractLeaseId(output: String): Option[String] =
    """lease\s+([0-9a-f]+)\s+granted""".r.findFirstMatchIn(output).map(_.group(1))

  private case class RunResult(exitCode: Int, stdout: String, stderr: String)

  private def etcd(args: List[String]): RunResult =
    val base = List("etcdctl", s"--endpoints=$endpoints") ++
      username.map(u => List(s"--user=$u")).getOrElse(Nil) ++
      password.map(p => List(s"--password=$p")).getOrElse(Nil)
    val cmd = base ++ args
    val pb  = ProcessBuilder(cmd*)
    pb.redirectErrorStream(false)
    val p      = pb.start()
    val stdout = scala.io.Source.fromInputStream(p.getInputStream).mkString
    val stderr = scala.io.Source.fromInputStream(p.getErrorStream).mkString
    p.waitFor()
    RunResult(p.exitValue(), stdout, stderr)
