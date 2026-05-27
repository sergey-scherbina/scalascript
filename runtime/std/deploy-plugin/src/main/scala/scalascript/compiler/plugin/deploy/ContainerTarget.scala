package scalascript.compiler.plugin.deploy

import scala.util.Try

/** OCI container deployment target.
 *
 *  Config keys (from manifest `deploy:` block):
 *    registry   — e.g. "ghcr.io/myorg"
 *    tag        — image tag (default: "latest")
 *    platform   — e.g. "linux/amd64,linux/arm64" (triggers buildx)
 *    build_args — comma-separated KEY=VALUE pairs forwarded to docker build
 *    cache_from — image ref used as --cache-from
 *    app_port   — port exposed in Dockerfile (default: 8080)
 *    container  — name of the running container for deploy/rollback/logs
 *    dockerfile — path to an existing Dockerfile; if absent, one is generated
 */
class ContainerTarget extends DeployTarget:

  def kind:         String       = "container"
  def artifactKind: ArtifactKind = ArtifactKind.OciImage

  // ── Internal state for rollback ────────────────────────────────────────────
  private var previousDigest: Option[String] = None

  // ── SPI lifecycle ──────────────────────────────────────────────────────────

  def build(ctx: DeployContext): BuildResult =
    val cfg     = dockerfileCfg(ctx)
    val buildDir = ctx.workDir / ".ssc-docker"
    os.makeDir.all(buildDir)

    // Resolve or generate Dockerfile; copy it into the build dir
    ctx.config.get("dockerfile").collect { case s: String => os.Path(s, ctx.workDir) } match
      case Some(supplied) => os.copy.over(supplied, buildDir / "Dockerfile", replaceExisting = true)
      case None =>
        val kind = artifactFromConfig(ctx)
        if ctx.verbose then println(s"[deploy/dockerfile] generating for $kind")
        DockerfileGenerator.writeDockerfile(kind, buildDir, cfg)

    // Copy the build artifact into the Docker build context
    val artSrc = ctx.config.get("artifact").collect { case s: String => s }
      .getOrElse(ArtifactRegistry.build(artifactFromConfig(ctx), ctx.workDir, ctx.verbose))
    val artFile = os.Path(artSrc, ctx.workDir)
    if os.exists(artFile) then
      os.copy.over(artFile, buildDir / artFile.last, replaceExisting = true, createFolders = true)

    val imageRef = imageTag(ctx)
    val builder  = detectBuilder()
    val platform = ctx.config.get("platform").collect { case s: String => s }

    val buildCmd = buildCommand(builder, imageRef, buildDir, platform, cfg)
    if ctx.verbose then println(s"[deploy/build] $buildCmd")

    if ctx.dryRun then
      println(s"[dry-run] Would run: $buildCmd")
      return BuildResult(imageRef, ArtifactKind.OciImage, Map("image" -> imageRef))

    val result = run(buildCmd.split(" ").toList, ctx.workDir)
    if result.exitCode != 0 then
      throw DeployError(s"[deploy/build-failed] docker build exited ${result.exitCode}:\n${result.stderr}")

    BuildResult(imageRef, ArtifactKind.OciImage, Map("image" -> imageRef))

  def push(ctx: DeployContext, art: BuildResult): PushResult =
    val imageRef = art.artifactPath
    if ctx.dryRun then
      println(s"[dry-run] Would push: $imageRef")
      return PushResult(imageRef, registry = Some(registryOf(ctx)))

    val cmd = List("docker", "push", imageRef)
    if ctx.verbose then println(s"[deploy/push] ${cmd.mkString(" ")}")
    val result = run(cmd, ctx.workDir)
    if result.exitCode != 0 then
      throw DeployError(s"[deploy/push-failed] docker push exited ${result.exitCode}:\n${result.stderr}")

    // Capture digest for potential rollback
    val digest = inspectDigest(imageRef).getOrElse(imageRef)
    PushResult(imageRef, registry = Some(registryOf(ctx)), metadata = Map("digest" -> digest))

  def deploy(ctx: DeployContext, ref: PushResult): DeployResult =
    val containerName = containerOf(ctx)
    val imageRef      = ref.ref

    if ctx.dryRun then
      println(s"[dry-run] Would restart container '$containerName' with image '$imageRef'")
      return DeployResult(imageRef, Some(s"http://localhost:${appPortOf(ctx)}"))

    // Save current image for rollback
    previousDigest = inspectDigest(containerName)

    // docker pull + restart or docker run
    run(List("docker", "pull", imageRef), ctx.workDir)
    run(List("docker", "stop", containerName), ctx.workDir)
    run(List("docker", "rm",   containerName), ctx.workDir)
    val runCmd = List("docker", "run", "-d",
      "--name", containerName,
      "-p", s"${appPortOf(ctx)}:${appPortOf(ctx)}",
      imageRef)
    if ctx.verbose then println(s"[deploy/run] ${runCmd.mkString(" ")}")
    val runResult = run(runCmd, ctx.workDir)
    if runResult.exitCode != 0 then
      throw DeployError(s"[deploy/run-failed] docker run exited ${runResult.exitCode}:\n${runResult.stderr}")

    DeployResult(imageRef, Some(s"http://localhost:${appPortOf(ctx)}"))

  def rollback(ctx: DeployContext, to: RevisionRef): RollbackResult =
    val containerName = containerOf(ctx)
    val prevRef = to.id.nonEmpty match
      case true  => to.id
      case false => previousDigest.getOrElse(throw DeployError("[deploy/rollback-no-prev] no previous digest"))

    if ctx.dryRun then
      println(s"[dry-run] Would rollback '$containerName' to '$prevRef'")
      return RollbackResult(prevRef)

    run(List("docker", "stop", containerName), ctx.workDir)
    run(List("docker", "rm",   containerName), ctx.workDir)
    val runCmd = List("docker", "run", "-d",
      "--name", containerName,
      "-p", s"${appPortOf(ctx)}:${appPortOf(ctx)}",
      prevRef)
    val result = run(runCmd, ctx.workDir)
    if result.exitCode != 0 then
      throw DeployError(s"[deploy/rollback-failed] docker run exited ${result.exitCode}:\n${result.stderr}")

    RollbackResult(prevRef)

  def status(ctx: DeployContext): StatusReport =
    val containerName = containerOf(ctx)
    val result = run(List("docker", "inspect", "--format", "{{.State.Running}}", containerName), ctx.workDir)
    if result.exitCode != 0 then
      return StatusReport(ctx.targetName, healthy = false, message = "container not found")
    val running = result.stdout.trim == "true"
    if !running then
      StatusReport(ctx.targetName, healthy = false, message = "container stopped")
    else
      val digest = inspectDigest(containerName).getOrElse("unknown")
      StatusReport(ctx.targetName, healthy = true, revision = Some(digest), message = "running")

  def logs(ctx: DeployContext, opts: LogOpts): Iterator[LogLine] =
    val containerName = containerOf(ctx)
    val tailFlag = if opts.tail then List("--follow") else Nil
    val sinceFlag = opts.since.map(s => List("--since", s)).getOrElse(Nil)
    val cmd = List("docker", "logs") ++ tailFlag ++ sinceFlag ++ List(containerName)
    try
      val result = run(cmd, ctx.workDir)
      result.stdout.linesIterator.map { line =>
        LogLine(java.time.Instant.now().toString, "INFO", line)
      }
    catch case _: Exception => Iterator.empty

  def outputs(ctx: DeployContext): Map[String, String] =
    val imageRef = imageTag(ctx)
    val digest   = inspectDigest(imageRef).getOrElse("unknown")
    Map("image" -> imageRef, "digest" -> digest)

  // ── Helpers ────────────────────────────────────────────────────────────────

  private def dockerfileCfg(ctx: DeployContext): DockerfileGenerator.DockerfileConfig =
    val port = appPortOf(ctx)
    val buildArgs = ctx.config.get("build_args").collect { case s: String =>
      s.split(",").flatMap { kv => kv.split("=", 2) match
        case Array(k, v) => Some(k -> v)
        case _           => None
      }.toMap
    }.getOrElse(Map.empty)
    DockerfileGenerator.DockerfileConfig(appPort = port, buildArgs = buildArgs)

  private def artifactFromConfig(ctx: DeployContext): ArtifactKind =
    ctx.config.get("artifact_kind").collect { case s: String =>
      ArtifactRegistry.artifactKindFor(s)
    }.getOrElse(ArtifactKind.FatJar)

  private def imageTag(ctx: DeployContext): String =
    val registry = registryOf(ctx)
    val appName  = ctx.config.get("name").collect { case s: String => s }.getOrElse(ctx.targetName)
    val tag      = ctx.config.get("tag").collect { case s: String => s }.getOrElse("latest")
    if registry.nonEmpty then s"$registry/$appName:$tag" else s"$appName:$tag"

  private def registryOf(ctx: DeployContext): String =
    ctx.config.get("registry").collect { case s: String => s }.getOrElse("")

  private def containerOf(ctx: DeployContext): String =
    ctx.config.get("container").collect { case s: String => s }.getOrElse(ctx.targetName)

  private def appPortOf(ctx: DeployContext): Int =
    ctx.config.get("app_port").collect {
      case i: Int  => i
      case s: String => s.toInt
    }.getOrElse(8080)

  private def detectBuilder(): String =
    if Try(runQuiet(List("buildctl", "version"))).isSuccess then "buildctl"
    else if Try(runQuiet(List("docker", "buildx", "version"))).isSuccess then "buildx"
    else "docker"

  private def buildCommand(
    builder:  String,
    imageRef: String,
    buildDir: os.Path,
    platform: Option[String],
    cfg:      DockerfileGenerator.DockerfileConfig,
  ): String =
    val platformFlag = platform.map(p => s" --platform $p").getOrElse("")
    val cacheFlag    = cfg.buildArgs.get("cache_from").map(c => s" --cache-from $c").getOrElse("")
    builder match
      case "buildctl" =>
        s"buildctl build --frontend dockerfile.v0 --local context=$buildDir --local dockerfile=$buildDir --output type=docker,name=$imageRef$platformFlag"
      case "buildx" =>
        s"docker buildx build$platformFlag$cacheFlag -t $imageRef $buildDir"
      case _ =>
        s"docker build$platformFlag$cacheFlag -t $imageRef $buildDir"

  private def inspectDigest(nameOrRef: String): Option[String] =
    Try {
      val result = runQuiet(List("docker", "inspect", "--format", "{{index .RepoDigests 0}}", nameOrRef))
      val out = result.stdout.trim
      if out.nonEmpty && out != "<no value>" then Some(out) else None
    }.toOption.flatten

  // ── Subprocess helpers ─────────────────────────────────────────────────────

  private case class RunResult(exitCode: Int, stdout: String, stderr: String)

  private def run(cmd: List[String], workDir: os.Path): RunResult =
    val pb = ProcessBuilder(cmd*)
    pb.directory(workDir.toIO)
    pb.redirectErrorStream(false)
    val p = pb.start()
    val stdout = scala.io.Source.fromInputStream(p.getInputStream).mkString
    val stderr = scala.io.Source.fromInputStream(p.getErrorStream).mkString
    p.waitFor()
    RunResult(p.exitValue(), stdout, stderr)

  private def runQuiet(cmd: List[String]): RunResult =
    run(cmd, os.pwd)
