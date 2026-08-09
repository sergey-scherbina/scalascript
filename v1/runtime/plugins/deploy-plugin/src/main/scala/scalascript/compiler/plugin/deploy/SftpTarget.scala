package scalascript.compiler.plugin.deploy

/** Traditional hosting via SFTP — uploads a tarball to a remote path.
 *
 *  Config keys:
 *    host         — remote hostname (required)
 *    user         — SFTP user (default: root)
 *    key_file     — SSH private key path (optional)
 *    port         — SFTP port (default: 22)
 *    remote_path  — remote destination directory (default: /var/www)
 *    remote_file  — remote file name (default: deploy.tar.gz)
 *    unpack_cmd   — remote shell command to run after upload (e.g. "tar -xzf deploy.tar.gz")
 */
class SftpTarget extends DeployTarget:

  def kind:         String       = "sftp"
  def artifactKind: ArtifactKind = ArtifactKind.Tarball

  // ── SPI lifecycle ──────────────────────────────────────────────────────────

  def build(ctx: DeployContext): BuildResult =
    val workDir = ctx.workDir
    val artDir  = workDir / ".ssc-artifacts"
    os.makeDir.all(artDir)
    val tarPath = (artDir / "deploy.tar.gz").toString
    if ctx.verbose then println(s"[deploy/sftp/build] tarball → $tarPath")
    BuildResult(tarPath, ArtifactKind.Tarball)

  def push(ctx: DeployContext, art: BuildResult): PushResult =
    val host       = hostOf(ctx)
    val user       = userOf(ctx)
    val remotePath = remotePathOf(ctx)
    val remoteFile = remoteFileOf(ctx)
    val localPath  = art.artifactPath

    if ctx.dryRun then
      println(s"[dry-run] Would sftp $localPath → $user@$host:$remotePath/$remoteFile")
      return PushResult(s"$user@$host:$remotePath/$remoteFile")

    val batchFile = ctx.workDir / ".ssc-deploy/sftp-batch.txt"
    os.makeDir.all(ctx.workDir / ".ssc-deploy")
    os.write.over(batchFile,
      s"""|mkdir $remotePath
          |put $localPath $remotePath/$remoteFile
          |bye
          |""".stripMargin)

    val keyFlag  = ctx.config.get("key_file").collect { case s: String => List("-i", s) }.getOrElse(Nil)
    val portFlag = ctx.config.get("port").collect { case n: Int => List("-P", n.toString); case s: String => List("-P", s) }.getOrElse(Nil)
    val cmd = List("sftp", "-b", batchFile.toString, "-o", "StrictHostKeyChecking=no") ++
      keyFlag ++ portFlag ++ List(s"$user@$host")
    if ctx.verbose then println(s"[deploy/sftp/push] ${cmd.mkString(" ")}")
    val result = run(cmd, ctx.workDir)
    if result.exitCode != 0 then
      throw DeployError(s"[deploy/sftp/push-failed] ${result.stderr}")

    PushResult(s"$user@$host:$remotePath/$remoteFile")

  def deploy(ctx: DeployContext, ref: PushResult): DeployResult =
    val host = hostOf(ctx)

    if ctx.dryRun then
      println(s"[dry-run] Would run unpack_cmd on $host")
      return DeployResult("uploaded", Some(s"http://$host"))

    ctx.config.get("unpack_cmd").collect { case s: String =>
      sshRun(ctx, s)
    }
    DeployResult("uploaded", Some(s"http://$host"))

  def rollback(ctx: DeployContext, to: RevisionRef): RollbackResult =
    if ctx.dryRun then println(s"[dry-run] sftp rollback: re-upload previous tarball")
    RollbackResult("manual-re-upload-required")

  def status(ctx: DeployContext): StatusReport =
    val remotePath = remotePathOf(ctx)
    val remoteFile = remoteFileOf(ctx)
    val result     = sshRun(ctx, s"ls $remotePath/$remoteFile 2>&1 && echo OK")
    val exists     = result.stdout.contains("OK")
    StatusReport(ctx.targetName, healthy = exists,
      message = if exists then "tarball present" else "tarball not found")

  def logs(ctx: DeployContext, opts: LogOpts): Iterator[LogLine] = Iterator.empty

  def outputs(ctx: DeployContext): Map[String, String] =
    Map("host" -> hostOf(ctx), "path" -> s"${remotePathOf(ctx)}/${remoteFileOf(ctx)}")

  // ── Helpers ────────────────────────────────────────────────────────────────

  private def hostOf(ctx: DeployContext): String =
    ctx.config.get("host").collect { case s: String => s }
      .getOrElse(throw DeployError("[deploy/sftp/no-host] config key 'host' is required"))

  private def userOf(ctx: DeployContext): String =
    ctx.config.get("user").collect { case s: String => s }.getOrElse("root")

  private def remotePathOf(ctx: DeployContext): String =
    ctx.config.get("remote_path").collect { case s: String => s }.getOrElse("/var/www")

  private def remoteFileOf(ctx: DeployContext): String =
    ctx.config.get("remote_file").collect { case s: String => s }.getOrElse("deploy.tar.gz")

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
