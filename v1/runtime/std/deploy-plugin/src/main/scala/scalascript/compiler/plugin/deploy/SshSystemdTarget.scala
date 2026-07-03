package scalascript.compiler.plugin.deploy

import scala.util.Try

/** Traditional hosting via SSH + SCP + systemd.
 *
 *  Config keys:
 *    host        — remote hostname or IP (required)
 *    user        — SSH user (default: root)
 *    key_file    — path to SSH private key (optional; falls back to ssh-agent)
 *    port        — SSH port (default: 22)
 *    remote_dir  — remote working directory (default: /opt/<targetName>)
 *    service     — systemd service name (default: <targetName>)
 *    jvm_opts    — JVM options (default: -Xmx512m)
 *    app_port    — application port (default: 8080)
 *    pre_deploy  — shell command to run on remote before restart (optional)
 *    post_deploy — shell command to run on remote after restart (optional)
 */
class SshSystemdTarget extends DeployTarget:

  def kind:         String       = "traditional"
  def artifactKind: ArtifactKind = ArtifactKind.FatJar

  // ── SPI lifecycle ──────────────────────────────────────────────────────────

  def build(ctx: DeployContext): BuildResult =
    val artPath = ArtifactRegistry.build(ArtifactKind.FatJar, ctx.workDir, ctx.verbose)
    BuildResult(artPath, ArtifactKind.FatJar)

  def push(ctx: DeployContext, art: BuildResult): PushResult =
    val host      = hostOf(ctx)
    val remoteDir = remoteDirOf(ctx)
    val artFile   = art.artifactPath

    if ctx.dryRun then
      println(s"[dry-run] Would scp $artFile → $host:$remoteDir/app.jar")
      return PushResult(s"$host:$remoteDir/app.jar")

    sshRun(ctx, s"mkdir -p $remoteDir")
    val scpResult = scp(ctx, artFile, s"$remoteDir/app.jar")
    if scpResult.exitCode != 0 then
      throw DeployError(s"[deploy/ssh/scp-failed] ${scpResult.stderr}")

    // Upload systemd unit
    val unitContent = SystemdUnitGenerator.generate(ctx.targetName, systemdCfg(ctx))
    val unitName    = SystemdUnitGenerator.unitName(serviceOf(ctx))
    val tmpUnit     = ctx.workDir / s".ssc-deploy/$unitName"
    os.makeDir.all(ctx.workDir / ".ssc-deploy")
    os.write.over(tmpUnit, unitContent)
    scp(ctx, tmpUnit.toString, s"/etc/systemd/system/$unitName")
    sshRun(ctx, "systemctl daemon-reload")

    PushResult(s"$host:$remoteDir/app.jar")

  def deploy(ctx: DeployContext, ref: PushResult): DeployResult =
    val service = serviceOf(ctx)
    val host    = hostOf(ctx)

    if ctx.dryRun then
      println(s"[dry-run] Would systemctl restart $service on $host")
      return DeployResult("deployed", Some(s"http://$host:${appPortOf(ctx)}"))

    ctx.config.get("pre_deploy").collect { case s: String => sshRun(ctx, s) }
    val result = sshRun(ctx, s"systemctl enable $service && systemctl restart $service")
    if result.exitCode != 0 then
      throw DeployError(s"[deploy/ssh/restart-failed] systemctl restart $service:\n${result.stderr}")
    ctx.config.get("post_deploy").collect { case s: String => sshRun(ctx, s) }
    DeployResult(s"$host:$service", Some(s"http://$host:${appPortOf(ctx)}"))

  def rollback(ctx: DeployContext, to: RevisionRef): RollbackResult =
    val service = serviceOf(ctx)
    if ctx.dryRun then
      println(s"[dry-run] Would systemctl stop $service on ${hostOf(ctx)}")
      return RollbackResult("stopped")
    sshRun(ctx, s"systemctl stop $service")
    RollbackResult("stopped")

  def status(ctx: DeployContext): StatusReport =
    val service = serviceOf(ctx)
    val result  = sshRun(ctx, s"systemctl is-active $service")
    val active  = result.stdout.trim == "active"
    StatusReport(ctx.targetName, healthy = active,
      message = if active then "active" else result.stdout.trim)

  def logs(ctx: DeployContext, opts: LogOpts): Iterator[LogLine] =
    val service   = serviceOf(ctx)
    val followFlag = if opts.tail then " -f" else ""
    val sinceFlag  = opts.since.map(s => s" --since $s").getOrElse("")
    Try {
      val result = sshRun(ctx, s"journalctl -u $service --no-pager$followFlag$sinceFlag")
      result.stdout.linesIterator.map { line =>
        LogLine(java.time.Instant.now().toString, "INFO", line)
      }
    }.getOrElse(Iterator.empty)

  def outputs(ctx: DeployContext): Map[String, String] =
    val host = hostOf(ctx)
    val port = appPortOf(ctx)
    Map("host" -> host, "port" -> port.toString, "url" -> s"http://$host:$port")

  // ── Helpers ────────────────────────────────────────────────────────────────

  private def systemdCfg(ctx: DeployContext): SystemdUnitGenerator.SystemdConfig =
    SystemdUnitGenerator.SystemdConfig(
      description  = s"ScalaScript ${ctx.targetName}",
      user         = userOf(ctx),
      workingDir   = remoteDirOf(ctx),
      artifactKind = ArtifactKind.FatJar,
      jvmOpts      = ctx.config.get("jvm_opts").collect { case s: String => s }.getOrElse("-Xmx512m"),
      appPort      = appPortOf(ctx),
    )

  private def hostOf(ctx: DeployContext): String =
    ctx.config.get("host").collect { case s: String => s }
      .getOrElse(throw DeployError("[deploy/ssh/no-host] config key 'host' is required"))

  private def userOf(ctx: DeployContext): String =
    ctx.config.get("user").collect { case s: String => s }.getOrElse("root")

  private def remoteDirOf(ctx: DeployContext): String =
    ctx.config.get("remote_dir").collect { case s: String => s }
      .getOrElse(s"/opt/${ctx.targetName}")

  private def serviceOf(ctx: DeployContext): String =
    ctx.config.get("service").collect { case s: String => s }.getOrElse(ctx.targetName)

  private def appPortOf(ctx: DeployContext): Int =
    ctx.config.get("app_port").collect { case n: Int => n; case s: String => s.toInt }.getOrElse(8080)

  private def sshArgs(ctx: DeployContext): List[String] =
    val keyFlag = ctx.config.get("key_file").collect { case s: String => List("-i", s) }.getOrElse(Nil)
    val portFlag = ctx.config.get("port").collect {
      case n: Int => List("-p", n.toString); case s: String => List("-p", s)
    }.getOrElse(Nil)
    List("ssh", "-o", "StrictHostKeyChecking=no") ++ keyFlag ++ portFlag

  private def sshRun(ctx: DeployContext, cmd: String): RunResult =
    val host    = hostOf(ctx)
    val user    = userOf(ctx)
    val fullCmd = sshArgs(ctx) ++ List(s"$user@$host", cmd)
    run(fullCmd, ctx.workDir)

  private def scp(ctx: DeployContext, localPath: String, remotePath: String): RunResult =
    val host    = hostOf(ctx)
    val user    = userOf(ctx)
    val keyFlag = ctx.config.get("key_file").collect { case s: String => List("-i", s) }.getOrElse(Nil)
    val portFlag = ctx.config.get("port").collect {
      case n: Int => List("-P", n.toString); case s: String => List("-P", s)
    }.getOrElse(Nil)
    val cmd = List("scp", "-o", "StrictHostKeyChecking=no") ++ keyFlag ++ portFlag ++
      List(localPath, s"$user@$host:$remotePath")
    run(cmd, ctx.workDir)

  // ── Subprocess helper ──────────────────────────────────────────────────────

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
