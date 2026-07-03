package scalascript.compiler.plugin.deploy

import scala.util.Try
import java.io.{FileOutputStream, BufferedOutputStream}
import java.util.zip.{ZipOutputStream, ZipEntry}

/** FaaS / serverless deployment target.
 *
 *  Provider shapes (selected via `provider:` config key):
 *    lambda          — AWS Lambda (LambdaZip via `aws lambda update-function-code`)
 *    cloudflare-workers — Cloudflare Workers (NodeBundle via `wrangler deploy`)
 *    cloud-run       — GCP Cloud Run (OciImage — delegates build/push to ContainerTarget)
 *    vercel-functions — Vercel Functions (NodeBundle via vercel CLI)
 *
 *  Config keys (all providers):
 *    provider     — one of the above
 *    function     — function / worker / service name (default: <targetName>)
 *    region       — AWS region / GCP region (default: us-east-1)
 *    token        — API token (Cloudflare) or unused for AWS (uses AWS_* env vars)
 *    memory_mb    — memory in MB (Lambda, default: 512)
 *    timeout_s    — timeout in seconds (Lambda/Workers, default: 30)
 *    handler      — Lambda handler class (default: scalascript.cli.LambdaHandler)
 *    runtime      — Lambda runtime (default: java21)
 *    role         — Lambda execution role ARN (required for Lambda)
 *    team         — Cloudflare account_id
 */
class FaasTarget extends DeployTarget:

  def kind:         String       = "faas"
  def artifactKind: ArtifactKind = ArtifactKind.LambdaZip

  // ── SPI lifecycle ──────────────────────────────────────────────────────────

  def build(ctx: DeployContext): BuildResult =
    providerOf(ctx) match
      case "lambda" =>
        val artPath = buildLambdaZip(ctx)
        BuildResult(artPath, ArtifactKind.LambdaZip)
      case "cloudflare-workers" | "vercel-functions" =>
        val artPath = ArtifactRegistry.build(ArtifactKind.NodeBundle, ctx.workDir, ctx.verbose)
        BuildResult(artPath, ArtifactKind.NodeBundle)
      case "cloud-run" =>
        BuildResult(imageOf(ctx), ArtifactKind.OciImage)
      case other =>
        throw DeployError(s"[deploy/faas/unknown-provider] '$other'. Supported: lambda, cloudflare-workers, cloud-run, vercel-functions")

  def push(ctx: DeployContext, art: BuildResult): PushResult =
    providerOf(ctx) match
      case "lambda"              => pushLambda(ctx, art)
      case "cloudflare-workers"  => pushCloudflareWorkers(ctx, art)
      case "vercel-functions"    => pushVercelFunctions(ctx, art)
      case "cloud-run"           => PushResult(art.artifactPath)
      case other                 => throw DeployError(s"[deploy/faas/unknown-provider] '$other'")

  def deploy(ctx: DeployContext, ref: PushResult): DeployResult =
    providerOf(ctx) match
      case "lambda"             => deployLambda(ctx, ref)
      case "cloudflare-workers" => DeployResult(ref.ref, ref.metadata.get("url"))
      case "vercel-functions"   => DeployResult(ref.ref, ref.metadata.get("url"))
      case "cloud-run"          => deployCloudRun(ctx, ref)
      case other                => throw DeployError(s"[deploy/faas/unknown-provider] '$other'")

  def rollback(ctx: DeployContext, to: RevisionRef): RollbackResult =
    val fn = functionOf(ctx)
    providerOf(ctx) match
      case "lambda" =>
        if ctx.dryRun then
          println(s"[dry-run] Would rollback Lambda $fn to alias ${to.id}")
          return RollbackResult(to.id)
        val cmd = awsCli(ctx) ++ List("lambda", "update-alias",
          "--function-name", fn, "--name", "live",
          "--function-version", to.id)
        val result = run(cmd, ctx.workDir)
        if result.exitCode != 0 then throw DeployError(s"[deploy/lambda/rollback-failed] ${result.stderr}")
        RollbackResult(to.id)
      case _ =>
        if ctx.dryRun then println(s"[dry-run] Would rollback FaaS ${functionOf(ctx)} to ${to.id}")
        RollbackResult(to.id)

  def status(ctx: DeployContext): StatusReport =
    val fn = functionOf(ctx)
    providerOf(ctx) match
      case "lambda" =>
        val cmd = awsCli(ctx) ++ List("lambda", "get-function", "--function-name", fn,
          "--query", "Configuration.State", "--output", "text")
        val result = run(cmd, ctx.workDir)
        val state  = result.stdout.trim
        StatusReport(ctx.targetName, healthy = state == "Active", message = state)
      case _ =>
        StatusReport(ctx.targetName, healthy = true, message = "status unavailable for this provider")

  def logs(ctx: DeployContext, opts: LogOpts): Iterator[LogLine] =
    val fn = functionOf(ctx)
    providerOf(ctx) match
      case "lambda" =>
        Try {
          val logGroup = s"/aws/lambda/$fn"
          val cmd = awsCli(ctx) ++ List("logs", "tail", logGroup, "--format", "short")
          val result = run(cmd, ctx.workDir)
          result.stdout.linesIterator.map { line =>
            LogLine(java.time.Instant.now().toString, "INFO", line)
          }
        }.getOrElse(Iterator.empty)
      case _ => Iterator.empty

  def outputs(ctx: DeployContext): Map[String, String] =
    val fn = functionOf(ctx)
    providerOf(ctx) match
      case "lambda" =>
        val arnCmd = awsCli(ctx) ++ List("lambda", "get-function-url-config",
          "--function-name", fn, "--query", "FunctionUrl", "--output", "text")
        val invokeUrl = Try(run(arnCmd, ctx.workDir).stdout.trim).getOrElse("unknown")
        Map("functionArn" -> fn, "invokeUrl" -> invokeUrl, "aliasVersion" -> "live")
      case "cloudflare-workers" =>
        val team = ctx.config.get("team").collect { case s: String => s }.getOrElse("unknown")
        Map("invokeUrl" -> s"https://$fn.$team.workers.dev", "aliasVersion" -> "latest")
      case _ =>
        Map("functionArn" -> fn, "aliasVersion" -> "latest")

  // ── Lambda helpers ─────────────────────────────────────────────────────────

  private def buildLambdaZip(ctx: DeployContext): String =
    val artDir  = ctx.workDir / ".ssc-artifacts"
    os.makeDir.all(artDir)
    val zipPath = (artDir / "lambda.zip").toString
    val jarPath = (artDir / "app.jar").toString

    if !ctx.dryRun then
      val jarBytes =
        if os.exists(os.Path(jarPath)) then
          os.read.bytes(os.Path(jarPath))
        else
          if ctx.verbose then println(s"[deploy/lambda/build] app.jar not found — creating empty zip stub")
          Array.empty[Byte]
      writeZip(zipPath, jarBytes)
    else if ctx.verbose then
      println(s"[dry-run] Would create lambda.zip from app.jar")

    zipPath

  private def writeZip(zipPath: String, bytes: Array[Byte]): Unit =
    val fos = FileOutputStream(zipPath)
    val zos = ZipOutputStream(BufferedOutputStream(fos))
    try
      zos.putNextEntry(ZipEntry("app.jar"))
      zos.write(bytes)
      zos.closeEntry()
    finally
      zos.close()
      fos.close()

  private def pushLambda(ctx: DeployContext, art: BuildResult): PushResult =
    val fn      = functionOf(ctx)
    val zipPath = art.artifactPath

    if ctx.dryRun then
      println(s"[dry-run] Would upload $zipPath to Lambda '$fn'")
      return PushResult(fn, metadata = Map(
        "functionArn"  -> s"arn:aws:lambda:${regionOf(ctx)}:000000000000:function:$fn",
        "aliasVersion" -> "dry-run"))

    val createCmd = awsCli(ctx) ++ List("lambda", "create-function",
      "--function-name", fn,
      "--runtime", ctx.config.get("runtime").collect { case s: String => s }.getOrElse("java21"),
      "--handler", ctx.config.get("handler").collect { case s: String => s }.getOrElse("scalascript.cli.LambdaHandler"),
      "--zip-file", s"fileb://$zipPath",
      "--role", ctx.config.get("role").collect { case s: String => s }.getOrElse("arn:aws:iam::000000000000:role/lambda-role"),
      "--memory-size", memoryOf(ctx).toString,
      "--timeout", timeoutOf(ctx).toString)
    val createResult = run(createCmd, ctx.workDir)
    if createResult.exitCode != 0 && !createResult.stderr.contains("ResourceConflictException") then
      val updateCmd = awsCli(ctx) ++ List("lambda", "update-function-code",
        "--function-name", fn, "--zip-file", s"fileb://$zipPath")
      val updateResult = run(updateCmd, ctx.workDir)
      if updateResult.exitCode != 0 then
        throw DeployError(s"[deploy/lambda/push-failed] ${updateResult.stderr}")

    PushResult(fn, metadata = Map("functionArn" -> fn))

  private def deployLambda(ctx: DeployContext, ref: PushResult): DeployResult =
    val fn = ref.ref
    if ctx.dryRun then
      println(s"[dry-run] Would publish new Lambda version and update 'live' alias for $fn")
      return DeployResult(fn, None, Map("aliasVersion" -> "dry-run"))

    val pubCmd    = awsCli(ctx) ++ List("lambda", "publish-version", "--function-name", fn,
      "--query", "Version", "--output", "text")
    val pubResult = run(pubCmd, ctx.workDir)
    val version   = pubResult.stdout.trim

    val aliasCmd    = awsCli(ctx) ++ List("lambda", "update-alias",
      "--function-name", fn, "--name", "live", "--function-version", version)
    val aliasResult = run(aliasCmd, ctx.workDir)
    if aliasResult.exitCode != 0 then
      val createAlias = awsCli(ctx) ++ List("lambda", "create-alias",
        "--function-name", fn, "--name", "live", "--function-version", version)
      run(createAlias, ctx.workDir)

    DeployResult(fn, None, Map("aliasVersion" -> version))

  private def pushCloudflareWorkers(ctx: DeployContext, art: BuildResult): PushResult =
    val fn      = functionOf(ctx)
    val token   = tokenOf(ctx)
    val account = ctx.config.get("team").collect { case s: String => s }.getOrElse("")

    if ctx.dryRun then
      println(s"[dry-run] Would deploy ${art.artifactPath} to Cloudflare Worker '$fn'")
      return PushResult(fn, metadata = Map("url" -> s"https://$fn.$account.workers.dev"))

    val baseEnv = Map("CLOUDFLARE_API_TOKEN" -> token)
    val env     = if account.nonEmpty then baseEnv + ("CLOUDFLARE_ACCOUNT_ID" -> account) else baseEnv
    val cmd     = List("wrangler", "deploy", art.artifactPath, "--name", fn)
    val result  = runWithEnv(cmd, ctx.workDir, env)
    if result.exitCode != 0 then throw DeployError(s"[deploy/workers/failed] ${result.stderr}")
    PushResult(fn, metadata = Map("url" -> s"https://$fn.$account.workers.dev"))

  private def pushVercelFunctions(ctx: DeployContext, art: BuildResult): PushResult =
    val fn    = functionOf(ctx)
    val token = tokenOf(ctx)

    if ctx.dryRun then
      println(s"[dry-run] Would deploy ${art.artifactPath} to Vercel Functions '$fn'")
      return PushResult(fn, metadata = Map("url" -> s"https://$fn.vercel.app"))

    val cmd    = List("vercel", "--prod", "--yes", "--token", token, art.artifactPath)
    val result = run(cmd, ctx.workDir)
    if result.exitCode != 0 then throw DeployError(s"[deploy/vercel-functions/failed] ${result.stderr}")
    val url = result.stdout.linesIterator.find(_.startsWith("https://")).getOrElse(s"https://$fn.vercel.app")
    PushResult(fn, metadata = Map("url" -> url))

  private def deployCloudRun(ctx: DeployContext, ref: PushResult): DeployResult =
    val fn     = functionOf(ctx)
    val region = regionOf(ctx)
    val image  = ref.ref

    if ctx.dryRun then
      println(s"[dry-run] Would deploy Cloud Run service '$fn' with image '$image' in $region")
      return DeployResult(fn, Some(s"https://$fn-stub-run.app"))

    val cmd = List("gcloud", "run", "deploy", fn,
      "--image", image, "--region", region, "--platform", "managed", "--allow-unauthenticated",
      "--memory", s"${memoryOf(ctx)}Mi", "--timeout", s"${timeoutOf(ctx)}s")
    val result = run(cmd, ctx.workDir)
    if result.exitCode != 0 then throw DeployError(s"[deploy/cloud-run/failed] ${result.stderr}")
    val url = result.stdout.linesIterator.find(_.startsWith("https://")).getOrElse(s"https://$fn.run.app")
    DeployResult(fn, Some(url))

  // ── Config helpers ─────────────────────────────────────────────────────────

  private def providerOf(ctx: DeployContext): String =
    ctx.config.get("provider").collect { case s: String => s.toLowerCase }.getOrElse("lambda")

  private def functionOf(ctx: DeployContext): String =
    ctx.config.get("function").collect { case s: String => s }.getOrElse(ctx.targetName)

  private def regionOf(ctx: DeployContext): String =
    ctx.config.get("region").collect { case s: String => s }.getOrElse("us-east-1")

  private def memoryOf(ctx: DeployContext): Int =
    ctx.config.get("memory_mb").collect { case n: Int => n; case s: String => s.toInt }.getOrElse(512)

  private def timeoutOf(ctx: DeployContext): Int =
    ctx.config.get("timeout_s").collect { case n: Int => n; case s: String => s.toInt }.getOrElse(30)

  private def imageOf(ctx: DeployContext): String =
    ctx.config.get("image").collect { case s: String => s }.getOrElse(s"${functionOf(ctx)}:latest")

  private def tokenOf(ctx: DeployContext): String =
    ctx.config.get("token").collect { case s: String => s }.getOrElse("")

  private def awsCli(ctx: DeployContext): List[String] =
    List("aws", "--region", regionOf(ctx))

  // ── Subprocess helpers ─────────────────────────────────────────────────────

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

  private def runWithEnv(cmd: List[String], workDir: os.Path, extraEnv: Map[String, String]): RunResult =
    val pb  = ProcessBuilder(cmd*)
    pb.directory(workDir.toIO)
    val env = pb.environment()
    extraEnv.foreach { case (k, v) => env.put(k, v) }
    pb.redirectErrorStream(false)
    val p      = pb.start()
    val stdout = scala.io.Source.fromInputStream(p.getInputStream).mkString
    val stderr = scala.io.Source.fromInputStream(p.getErrorStream).mkString
    p.waitFor()
    RunResult(p.exitValue(), stdout, stderr)
