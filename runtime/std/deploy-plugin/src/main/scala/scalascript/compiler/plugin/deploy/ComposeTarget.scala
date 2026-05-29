package scalascript.compiler.plugin.deploy


/** Docker Compose deployment target.
 *
 *  Config keys (from manifest `deploy:` block):
 *    compose_file   — path to docker-compose.yml to write (default: docker-compose.yml)
 *    network        — Docker network name (default: <name>-net)
 *    env_file       — .env file to inject (optional)
 *    replicas       — service replicas (default: 1)
 *    app_port       — container port (default: 8080)
 *    image          — OCI image reference
 *    cluster_mode   — "true" to wire SSC_CLUSTER_TOKEN + seed URLs
 *    auth_token_secret — env var name carrying the cluster token (default: SSC_CLUSTER_TOKEN)
 */
class ComposeTarget extends DeployTarget:

  def kind:         String       = "compose"
  def artifactKind: ArtifactKind = ArtifactKind.OciImage

  def build(ctx: DeployContext): BuildResult =
    val image = imageOf(ctx)
    if ctx.verbose then println(s"[deploy/compose/build] using image: $image")
    BuildResult(image, ArtifactKind.OciImage, Map("image" -> image))

  def push(ctx: DeployContext, art: BuildResult): PushResult =
    PushResult(art.artifactPath)

  def deploy(ctx: DeployContext, ref: PushResult): DeployResult =
    val name    = ctx.targetName
    val compose = generateCompose(name, ctx, ref.ref)
    val outFile = ctx.config.get("compose_file").collect { case s: String => s }
      .getOrElse("docker-compose.yml")
    if ctx.dryRun then
      println(s"[dry-run] docker-compose.yml for $name:\n$compose")
    else
      os.write.over(ctx.workDir / outFile, compose)
      val result = run(List("docker", "compose", "-f", outFile, "up", "-d"), ctx.workDir)
      if result.exitCode != 0 then
        throw DeployError(s"[deploy/compose/up-failed] ${result.stderr}")
    DeployResult(ref.ref, url = None)

  def rollback(ctx: DeployContext, to: RevisionRef): RollbackResult =
    val name    = ctx.targetName
    val outFile = ctx.config.get("compose_file").collect { case s: String => s }.getOrElse("docker-compose.yml")
    if ctx.dryRun then
      println(s"[dry-run] Would docker compose down $name")
    else
      run(List("docker", "compose", "-f", outFile, "down"), ctx.workDir)
    RollbackResult(if to.id.nonEmpty then to.id else "stopped")

  def status(ctx: DeployContext): StatusReport =
    val name    = ctx.targetName
    val outFile = ctx.config.get("compose_file").collect { case s: String => s }.getOrElse("docker-compose.yml")
    val result  = run(List("docker", "compose", "-f", outFile, "ps", "--format", "json"), ctx.workDir)
    StatusReport(name, healthy = result.exitCode == 0, message = if result.exitCode == 0 then "running" else result.stderr.take(200))

  def logs(ctx: DeployContext, opts: LogOpts): Iterator[LogLine] =
    val outFile = ctx.config.get("compose_file").collect { case s: String => s }.getOrElse("docker-compose.yml")
    val tail    = if opts.tail then List("--follow") else Nil
    val result  = run(List("docker", "compose", "-f", outFile, "logs") ++ tail, ctx.workDir)
    result.stdout.linesIterator.map { line =>
      LogLine(java.time.Instant.now().toString, "INFO", line)
    }

  def outputs(ctx: DeployContext): Map[String, String] =
    val port = ctx.config.get("app_port").collect { case n: Int => n; case n: Integer => n.toInt }.getOrElse(8080)
    Map("url" -> s"http://localhost:$port", "composeFile" -> "docker-compose.yml")

  // ── Manifest generation ────────────────────────────────────────────────────

  def generateCompose(name: String, ctx: DeployContext, image: String): String =
    val port        = ctx.config.get("app_port").collect { case n: Int => n; case n: Integer => n.toInt }.getOrElse(8080)
    val replicas    = ctx.config.get("replicas").collect { case n: Int => n; case n: Integer => n.toInt }.getOrElse(1)
    val network     = ctx.config.get("network").collect { case s: String => s }.getOrElse(s"$name-net")
    val clusterMode = ctx.config.get("cluster_mode").collect {
      case s: String  => s.toLowerCase == "true"
      case b: Boolean => b
    }.getOrElse(false)
    val tokenEnvLine = if clusterMode then
      val secret = ctx.config.get("auth_token_secret").collect { case s: String => s }.getOrElse("SSC_CLUSTER_TOKEN")
      s"\n      - $secret=$${$secret}"
    else ""
    val envFileLine = ctx.config.get("env_file").collect { case s: String => s }.map { f =>
      s"\n    env_file:\n      - $f"
    }.getOrElse("")
    s"""|version: '3.8'
        |services:
        |  $name:
        |    image: $image
        |    ports:
        |      - "${port}:${port}"
        |    environment:
        |      - PORT=$port$tokenEnvLine
        |    deploy:
        |      replicas: $replicas$envFileLine
        |    networks:
        |      - $network
        |    healthcheck:
        |      test: ["CMD", "wget", "-qO-", "http://localhost:$port/_health"]
        |      interval: 10s
        |      timeout: 5s
        |      retries: 3
        |networks:
        |  $network:
        |    driver: bridge
        |""".stripMargin

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

  private def imageOf(ctx: DeployContext): String =
    ctx.config.get("image").collect { case s: String => s }
      .orElse(ctx.outputsOf(ctx.targetName).get("image"))
      .getOrElse(s"${ctx.targetName}:latest")
