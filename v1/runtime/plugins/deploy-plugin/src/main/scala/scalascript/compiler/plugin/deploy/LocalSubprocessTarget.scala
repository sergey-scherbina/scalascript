package scalascript.compiler.plugin.deploy

import scala.util.Try
import java.net.{HttpURLConnection, URI}

/** Local environment adapter — spawns a fat-JAR process, polls `/_health`.
 *  Activated when `kind: traditional` + `transport: subprocess` in the manifest. */
class LocalSubprocessTarget(port: Int) extends DeployTarget:

  def kind:         String       = "traditional"
  def artifactKind: ArtifactKind = ArtifactKind.FatJar

  private var process: Option[Process] = None

  def build(ctx: DeployContext): BuildResult =
    if ctx.verbose then println(s"[deploy/build] local subprocess — no build step (uses existing jar)")
    BuildResult("(in-process)", ArtifactKind.FatJar)

  def push(ctx: DeployContext, art: BuildResult): PushResult =
    PushResult("local")

  def deploy(ctx: DeployContext, ref: PushResult): DeployResult =
    if ctx.dryRun then
      println(s"[dry-run] Would start: java -jar ${art(ctx)} on port $port")
      return DeployResult("dry-run", Some(s"http://localhost:$port"))

    val jar = art(ctx)
    if !os.exists(os.Path(jar, ctx.workDir)) && jar != "(in-process)" then
      throw DeployError(s"[deploy/artifact-build-failed] Jar not found: $jar — run `ssc deploy build` first")

    // Stop previous instance if running
    process.foreach { p => p.destroy(); p.waitFor() }

    val javaArgs = List("java", "-jar", jar, s"--port=$port")
    val pb = ProcessBuilder(javaArgs*)
    pb.redirectErrorStream(true)
    val p = pb.start()
    process = Some(p)

    if ctx.verbose then println(s"[deploy/started] PID ${p.pid()} — polling /_health on :$port")
    pollHealth(port, timeoutSeconds = 30, verbose = ctx.verbose)
    DeployResult(s"local-${System.currentTimeMillis()}", Some(s"http://localhost:$port"))

  def rollback(ctx: DeployContext, to: RevisionRef): RollbackResult =
    process.foreach { p => p.destroy(); p.waitFor() }
    process = None
    RollbackResult("stopped")

  def status(ctx: DeployContext): StatusReport =
    val running = process.exists(_.isAlive)
    if !running then
      StatusReport(ctx.targetName, healthy = false, message = "not running")
    else
      val ok = Try(checkHealth(port)).getOrElse(false)
      StatusReport(ctx.targetName, healthy = ok, revision = Some("local"), message = if ok then "healthy" else "unhealthy")

  def logs(ctx: DeployContext, opts: LogOpts): Iterator[LogLine] =
    process.map { p =>
      scala.io.Source.fromInputStream(p.getInputStream).getLines().map { line =>
        LogLine(java.time.Instant.now().toString, "INFO", line)
      }
    }.getOrElse(Iterator.empty)

  def outputs(ctx: DeployContext): Map[String, String] =
    Map("url" -> s"http://localhost:$port", "port" -> port.toString)

  private def art(ctx: DeployContext): String =
    ctx.config.get("jar").collect { case s: String => s }.getOrElse("app.jar")

  private def pollHealth(port: Int, timeoutSeconds: Int, verbose: Boolean): Unit =
    val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
    while System.currentTimeMillis() < deadline do
      if checkHealth(port) then
        if verbose then println(s"[deploy/healthy] localhost:$port/_health → 200 OK")
        return
      Thread.sleep(500)
    throw DeployError(s"[deploy/target-unreachable] localhost:$port/_health did not return 200 within ${timeoutSeconds}s")

  private def checkHealth(port: Int): Boolean =
    Try {
      val conn = URI.create(s"http://localhost:$port/_health").toURL.openConnection().asInstanceOf[HttpURLConnection]
      conn.setConnectTimeout(1000)
      conn.setReadTimeout(1000)
      conn.getResponseCode == 200
    }.getOrElse(false)
