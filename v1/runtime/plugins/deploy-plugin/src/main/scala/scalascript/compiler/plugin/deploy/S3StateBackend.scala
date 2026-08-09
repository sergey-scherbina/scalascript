package scalascript.compiler.plugin.deploy

import scala.util.Try

/** S3-backed state backend using `aws s3api` subprocess — no SDK dependency.
 *
 *  State object key: `<prefix>/<app>/<env>/<target>[.<slot>].json`
 *  Lock object key:  `<prefix>/<app>/<env>/<target>[.<slot>].lock`
 *
 *  Optimistic locking via `--if-none-match` on lock object put; falls back to
 *  mtime-based TTL check (S3 Object Last-Modified header).
 *
 *  Config keys:
 *    bucket  — S3 bucket name (required)
 *    prefix  — key prefix (default: "ssc-state")
 *    app     — application name (default: "app")
 *    region  — AWS region (default: us-east-1)
 *    profile — AWS CLI profile (optional)
 */
class S3StateBackend(config: Map[String, Any]) extends StateBackend:

  private val bucket  = config.get("bucket").collect { case s: String => s }.getOrElse(throw DeployError("[deploy/state/s3] missing 'bucket' config key"))
  private val prefix  = config.get("prefix").collect { case s: String => s }.getOrElse("ssc-state")
  private val app     = config.get("app").collect { case s: String => s }.getOrElse("app")
  private val region  = config.get("region").collect { case s: String => s }.getOrElse("us-east-1")
  private val profile = config.get("profile").collect { case s: String => s }

  def read(key: StateKey): Option[StateRecord] =
    val objKey = stateKey(key)
    val result = awsRun(List("s3api", "get-object", "--bucket", bucket, "--key", objKey, "/dev/stdout"))
    if result.exitCode != 0 then None
    else Try(JsonState.parse(result.stdout)).toOption

  def write(key: StateKey, record: StateRecord): Unit =
    val objKey = stateKey(key)
    val json   = JsonState.serialize(record)
    val tmp    = java.io.File.createTempFile("ssc-state", ".json")
    try
      tmp.deleteOnExit()
      java.nio.file.Files.writeString(tmp.toPath, json)
      val result = awsRun(List("s3api", "put-object", "--bucket", bucket, "--key", objKey,
        "--body", tmp.getAbsolutePath, "--content-type", "application/json"))
      if result.exitCode != 0 then throw DeployError(s"[deploy/state/s3/write-failed] ${result.stderr}")
    finally
      tmp.delete()

  def lock(key: StateKey, ttlSeconds: Int): LockHandle =
    val objKey = lockKey(key)
    val token  = java.util.UUID.randomUUID().toString

    val headResult = awsRun(List("s3api", "head-object", "--bucket", bucket, "--key", objKey))
    if headResult.exitCode == 0 then
      val lastMod = extractLastModified(headResult.stdout)
      val ageMs   = System.currentTimeMillis() - lastMod
      if ageMs < ttlSeconds * 1000L then
        throw DeployError(s"[deploy/state-lock-contention] lock on ${key.env}/${key.target} held (age: ${ageMs/1000}s, ttl: ${ttlSeconds}s)")

    val tmp = java.io.File.createTempFile("ssc-lock", ".txt")
    try
      tmp.deleteOnExit()
      java.nio.file.Files.writeString(tmp.toPath, token)
      val putResult = awsRun(List("s3api", "put-object", "--bucket", bucket, "--key", objKey,
        "--body", tmp.getAbsolutePath))
      if putResult.exitCode != 0 then throw DeployError(s"[deploy/state/s3/lock-failed] ${putResult.stderr}")
    finally
      tmp.delete()

    LockHandle(key, token)

  def unlock(handle: LockHandle): Unit =
    val objKey = lockKey(handle.key)
    awsRun(List("s3api", "delete-object", "--bucket", bucket, "--key", objKey))

  private def stateKey(key: StateKey): String =
    val slot = key.slot.map(s => s".$s").getOrElse("")
    s"$prefix/$app/${key.env}/${key.target}$slot.json"

  private def lockKey(key: StateKey): String =
    val slot = key.slot.map(s => s".$s").getOrElse("")
    s"$prefix/$app/${key.env}/${key.target}$slot.lock"

  private def extractLastModified(headOutput: String): Long =
    val pattern = """"LastModified"\s*:\s*"([^"]+)"""".r
    pattern.findFirstMatchIn(headOutput)
      .flatMap(m => Try(java.time.Instant.parse(m.group(1)).toEpochMilli).toOption)
      .getOrElse(0L)

  private case class RunResult(exitCode: Int, stdout: String, stderr: String)

  private def awsRun(args: List[String]): RunResult =
    val base = List("aws", "--region", region) ++ profile.map(p => List("--profile", p)).getOrElse(Nil)
    val cmd  = base ++ args
    val pb   = ProcessBuilder(cmd*)
    pb.redirectErrorStream(false)
    val p      = pb.start()
    val stdout = scala.io.Source.fromInputStream(p.getInputStream).mkString
    val stderr = scala.io.Source.fromInputStream(p.getErrorStream).mkString
    p.waitFor()
    RunResult(p.exitValue(), stdout, stderr)
