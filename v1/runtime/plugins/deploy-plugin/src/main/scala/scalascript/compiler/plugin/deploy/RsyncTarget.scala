package scalascript.compiler.plugin.deploy

import scala.util.Try

/** Traditional hosting via rsync — syncs a local directory tree to a remote webroot.
 *
 *  Config keys:
 *    host          — remote hostname (required)
 *    user          — SSH user (default: root)
 *    key_file      — SSH private key path (optional)
 *    remote_path   — remote destination directory (default: /var/www/html)
 *    rsync_opts    — extra rsync options (default: "")
 *    post_deploy   — remote shell command to run after sync (optional)
 */
class RsyncTarget extends DeployTarget:

  def kind:         String       = "rsync"
  def artifactKind: ArtifactKind = ArtifactKind.RsyncTree

  // ── SPI lifecycle ──────────────────────────────────────────────────────────

  def build(ctx: DeployContext): BuildResult =
    val artPath = ArtifactRegistry.build(ArtifactKind.SpaBundle, ctx.workDir, ctx.verbose)
    BuildResult(artPath, ArtifactKind.RsyncTree)

  def push(ctx: DeployContext, art: BuildResult): PushResult =
    val host       = hostOf(ctx)
    val remotePath = remotePathOf(ctx)
    val localPath  = art.artifactPath.stripSuffix("/") + "/"
    val user       = userOf(ctx)

    if ctx.dryRun then
      println(s"[dry-run] Would rsync $localPath → $user@$host:$remotePath")
      return PushResult(s"$user@$host:$remotePath")

    val sshCmd   = buildSshCmd(ctx)
    val extraOpts = ctx.config.get("rsync_opts").collect { case s: String => s }.getOrElse("").trim
    val rsyncArgs = List("rsync", "-avz", "--delete",
      s"--rsh=$sshCmd", localPath, s"$user@$host:$remotePath") ++
      (if extraOpts.nonEmpty then extraOpts.split(" ").toList else Nil)

    if ctx.verbose then println(s"[deploy/rsync] ${rsyncArgs.mkString(" ")}")
    val result = run(rsyncArgs, ctx.workDir)
    if result.exitCode != 0 then
      throw DeployError(s"[deploy/rsync/failed] ${result.stderr}")

    PushResult(s"$user@$host:$remotePath")

  def deploy(ctx: DeployContext, ref: PushResult): DeployResult =
    val host = hostOf(ctx)

    if ctx.dryRun then
      println(s"[dry-run] rsync deployment to $host complete (dry-run)")
      return DeployResult("synced", Some(s"http://$host"))

    ctx.config.get("post_deploy").collect { case s: String => sshRun(ctx, s) }
    DeployResult("synced", Some(s"http://$host"))

  def rollback(ctx: DeployContext, to: RevisionRef): RollbackResult =
    if ctx.dryRun then
      println(s"[dry-run] rsync rollback: re-push previous revision")
    RollbackResult("manual-re-push-required")

  def status(ctx: DeployContext): StatusReport =
    val host       = hostOf(ctx)
    val remotePath = remotePathOf(ctx)
    val result     = sshRun(ctx, s"ls $remotePath 2>&1 && echo OK")
    val healthy    = result.stdout.contains("OK")
    StatusReport(ctx.targetName, healthy = healthy,
      message = if healthy then s"$host:$remotePath exists" else "remote path not found")

  def logs(ctx: DeployContext, opts: LogOpts): Iterator[LogLine] =
    Try {
      val result = sshRun(ctx, "journalctl -u nginx --no-pager")
      result.stdout.linesIterator.map { line =>
        LogLine(java.time.Instant.now().toString, "INFO", line)
      }
    }.getOrElse(Iterator.empty)

  def outputs(ctx: DeployContext): Map[String, String] =
    Map("host" -> hostOf(ctx), "path" -> remotePathOf(ctx), "url" -> s"http://${hostOf(ctx)}")

  // ── Helpers ────────────────────────────────────────────────────────────────

  private def hostOf(ctx: DeployContext): String =
    ctx.config.get("host").collect { case s: String => s }
      .getOrElse(throw DeployError("[deploy/rsync/no-host] config key 'host' is required"))

  private def userOf(ctx: DeployContext): String =
    ctx.config.get("user").collect { case s: String => s }.getOrElse("root")

  private def remotePathOf(ctx: DeployContext): String =
    ctx.config.get("remote_path").collect { case s: String => s }.getOrElse("/var/www/html")

  private def buildSshCmd(ctx: DeployContext): String =
    val keyFlag = ctx.config.get("key_file").collect { case s: String => s"-i $s" }.getOrElse("")
    s"ssh -o StrictHostKeyChecking=no $keyFlag".trim

  private def sshRun(ctx: DeployContext, cmd: String): RunResult =
    val host    = hostOf(ctx)
    val user    = userOf(ctx)
    val keyFlag = ctx.config.get("key_file").collect { case s: String => List("-i", s) }.getOrElse(Nil)
    val fullCmd = List("ssh", "-o", "StrictHostKeyChecking=no") ++ keyFlag ++ List(s"$user@$host", cmd)
    run(fullCmd, ctx.workDir)

  private case class RunResult(exitCode: Int, stdout: String, stderr: String)

  private def run(cmd: List[String], workDir: os.Path): RunResult =
    val pb = ProcessBuilder(cmd*)
    pb.directory(workDir.toIO)
    pb.redirectErrorStream(false)
    val p      = pb.start()
    val stdout = scala.io.Source.fromInputStream(p.getInputStream).mkString
    val stderr = scala.io.Source.fromInputStream(p.getErrorStream).mkString
    p.waitFor()
    RunResult(p.exitValue(), stdout, stderr)
