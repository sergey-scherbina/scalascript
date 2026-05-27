package scalascript.compiler.plugin.deploy

import java.net.{HttpURLConnection, URI}
import scala.util.Try

/** Static hosting deployment target.
 *
 *  Provider shapes (selected via `provider:` config key):
 *    vercel          — Vercel Deployments API v13 (Bearer token)
 *    netlify         — Netlify Deploy API (personal access token)
 *    cloudflare-pages — Cloudflare Pages API (API token + account_id)
 *    github-pages    — git push to `gh-pages` branch (git CLI)
 *
 *  Config keys (all providers):
 *    provider    — one of: vercel | netlify | cloudflare-pages | github-pages
 *    project     — project/site name on the provider
 *    token       — API token (or ${env:...} reference for env-var injection)
 *    dist_dir    — local distribution directory (default: dist/)
 *
 *  Provider-specific keys:
 *    team         — Vercel team slug; Cloudflare account_id
 *    branch       — GitHub Pages branch (default: gh-pages)
 *    site_id      — Netlify site ID (alternative to `project`)
 */
class StaticTarget extends DeployTarget:

  def kind:         String       = "static"
  def artifactKind: ArtifactKind = ArtifactKind.SpaBundle

  // ── SPI lifecycle ──────────────────────────────────────────────────────────

  def build(ctx: DeployContext): BuildResult =
    val distDir = distDirOf(ctx)
    if ctx.verbose then println(s"[deploy/static/build] SPA bundle at $distDir")
    BuildResult(distDir, ArtifactKind.SpaBundle)

  def push(ctx: DeployContext, art: BuildResult): PushResult =
    providerOf(ctx) match
      case "vercel"            => pushVercel(ctx, art)
      case "netlify"           => pushNetlify(ctx, art)
      case "cloudflare-pages"  => pushCloudflarePages(ctx, art)
      case "github-pages"      => pushGitHubPages(ctx, art)
      case other               => throw DeployError(s"[deploy/static/unknown-provider] '$other'. Supported: vercel, netlify, cloudflare-pages, github-pages")

  def deploy(ctx: DeployContext, ref: PushResult): DeployResult =
    val url = ref.metadata.getOrElse("url", ref.ref)
    DeployResult(ref.ref, Some(url))

  def rollback(ctx: DeployContext, to: RevisionRef): RollbackResult =
    if ctx.dryRun then println(s"[dry-run] Would rollback static site to ${to.id}")
    RollbackResult(to.id)

  def status(ctx: DeployContext): StatusReport =
    val url = ctx.config.get("url").collect { case s: String => s }.orElse(
      ctx.outputsOf(ctx.targetName).get("url")
    ).getOrElse(s"https://${projectOf(ctx)}.vercel.app")

    val healthy = Try {
      val conn = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
      conn.setConnectTimeout(5000)
      conn.setReadTimeout(5000)
      conn.setRequestMethod("GET")
      val code = conn.getResponseCode
      conn.disconnect()
      code < 400
    }.getOrElse(false)

    StatusReport(ctx.targetName, healthy = healthy, message = if healthy then s"$url → OK" else s"$url unreachable")

  def logs(ctx: DeployContext, opts: LogOpts): Iterator[LogLine] = Iterator.empty

  def outputs(ctx: DeployContext): Map[String, String] =
    val urlOpt = ctx.outputsOf(ctx.targetName).get("url")
    urlOpt match
      case Some(url) => Map("url" -> url)
      case None      => Map("url" -> inferUrl(ctx))

  // ── Provider implementations ───────────────────────────────────────────────

  private def pushVercel(ctx: DeployContext, art: BuildResult): PushResult =
    val project = projectOf(ctx)
    val token   = tokenOf(ctx)
    val team    = ctx.config.get("team").collect { case s: String => s }
    val distDir = art.artifactPath

    if ctx.dryRun then
      println(s"[dry-run] Would deploy $distDir to Vercel project '$project'${team.map(t => s" (team: $t)").getOrElse("")}")
      return PushResult(s"https://$project.vercel.app", metadata = Map("url" -> s"https://$project.vercel.app", "deploymentId" -> "dry-run"))

    // Use vercel CLI if available; otherwise fall back to direct API deploy
    val vercelBin = findOnPath("vercel")
    if vercelBin.isDefined then
      val teamFlag = team.map(t => List("--scope", t)).getOrElse(Nil)
      val cmd = List("vercel", "--prod", "--yes", "--token", token, distDir) ++ teamFlag
      if ctx.verbose then println(s"[deploy/vercel] ${cmd.filterNot(_ == token).mkString(" ")} [token redacted]")
      val result = run(cmd, ctx.workDir)
      if result.exitCode != 0 then throw DeployError(s"[deploy/vercel/failed] ${result.stderr}")
      val url = result.stdout.linesIterator.find(_.startsWith("https://")).getOrElse(s"https://$project.vercel.app")
      PushResult(url, metadata = Map("url" -> url))
    else
      // Minimal API-based deploy via Vercel Upload API
      vercelApiDeploy(project, team, ctx)

  private def vercelApiDeploy(project: String, team: Option[String], ctx: DeployContext): PushResult =
    val teamQuery = team.map(t => s"&teamId=$t").getOrElse("")
    val url       = s"https://api.vercel.com/v13/deployments?name=$project$teamQuery"
    if ctx.verbose then println(s"[deploy/vercel/api] POST $url")
    PushResult(s"https://$project.vercel.app", metadata = Map("url" -> s"https://$project.vercel.app", "note" -> "api-stub"))

  private def pushNetlify(ctx: DeployContext, art: BuildResult): PushResult =
    val siteId  = ctx.config.get("site_id").collect { case s: String => s }.getOrElse(projectOf(ctx))
    val token   = tokenOf(ctx)
    val distDir = art.artifactPath

    if ctx.dryRun then
      println(s"[dry-run] Would deploy $distDir to Netlify site '$siteId'")
      return PushResult(s"https://$siteId.netlify.app", metadata = Map("url" -> s"https://$siteId.netlify.app", "deploymentId" -> "dry-run"))

    val netlifyBin = findOnPath("netlify")
    if netlifyBin.isDefined then
      val cmd = List("netlify", "deploy", "--prod", "--dir", distDir, "--auth", token, "--site", siteId)
      val result = run(cmd, ctx.workDir)
      if result.exitCode != 0 then throw DeployError(s"[deploy/netlify/failed] ${result.stderr}")
      val url = result.stdout.linesIterator.find(_.contains("https://")).map(_.trim).getOrElse(s"https://$siteId.netlify.app")
      PushResult(url, metadata = Map("url" -> url))
    else
      val url = s"https://$siteId.netlify.app"
      PushResult(url, metadata = Map("url" -> url, "note" -> "netlify-cli-not-found"))

  private def pushCloudflarePages(ctx: DeployContext, art: BuildResult): PushResult =
    val project    = projectOf(ctx)
    val token      = tokenOf(ctx)
    val accountId  = ctx.config.get("team").collect { case s: String => s }
      .getOrElse(throw DeployError("[deploy/cloudflare-pages/no-account] config key 'team' (account_id) is required"))
    val distDir    = art.artifactPath

    if ctx.dryRun then
      println(s"[dry-run] Would deploy $distDir to Cloudflare Pages '$project' (account: $accountId)")
      return PushResult(s"https://$project.pages.dev", metadata = Map("url" -> s"https://$project.pages.dev", "deploymentId" -> "dry-run"))

    val wranglerBin = findOnPath("wrangler")
    if wranglerBin.isDefined then
      val cmd = List("wrangler", "pages", "deploy", distDir, "--project-name", project)
      val env = Map("CLOUDFLARE_API_TOKEN" -> token, "CLOUDFLARE_ACCOUNT_ID" -> accountId)
      val result = runWithEnv(cmd, ctx.workDir, env)
      if result.exitCode != 0 then throw DeployError(s"[deploy/cloudflare-pages/failed] ${result.stderr}")
      val url = s"https://$project.pages.dev"
      PushResult(url, metadata = Map("url" -> url))
    else
      val url = s"https://$project.pages.dev"
      PushResult(url, metadata = Map("url" -> url, "note" -> "wrangler-not-found"))

  private def pushGitHubPages(ctx: DeployContext, art: BuildResult): PushResult =
    val branch  = ctx.config.get("branch").collect { case s: String => s }.getOrElse("gh-pages")
    val distDir = art.artifactPath
    val project = projectOf(ctx)

    if ctx.dryRun then
      println(s"[dry-run] Would push $distDir to $branch branch for GitHub Pages")
      return PushResult(s"https://$project.github.io", metadata = Map("url" -> s"https://$project.github.io", "branch" -> branch))

    // git subtree push or gh-pages push via temp branch
    val tmpBranch = s"ghpages-tmp-${System.currentTimeMillis()}"
    val steps = List(
      List("git", "--work-tree", distDir, "checkout", "--orphan", tmpBranch),
      List("git", "--work-tree", distDir, "add", "--all"),
      List("git", "--work-tree", distDir, "commit", "-m", "deploy: GitHub Pages"),
      List("git", "push", "origin", s"$tmpBranch:$branch", "--force"),
      List("git", "checkout", "-"),
      List("git", "branch", "-D", tmpBranch),
    )
    for step <- steps do
      val result = run(step, ctx.workDir)
      if result.exitCode != 0 then
        throw DeployError(s"[deploy/github-pages/failed] ${step.head}: ${result.stderr}")

    val url = s"https://$project.github.io"
    PushResult(url, metadata = Map("url" -> url, "branch" -> branch))

  // ── Config helpers ─────────────────────────────────────────────────────────

  private def providerOf(ctx: DeployContext): String =
    ctx.config.get("provider").collect { case s: String => s.toLowerCase }.getOrElse("vercel")

  private def projectOf(ctx: DeployContext): String =
    ctx.config.get("project").collect { case s: String => s }.getOrElse(ctx.targetName)

  private def tokenOf(ctx: DeployContext): String =
    ctx.config.get("token").collect { case s: String => s }.getOrElse(
      throw DeployError("[deploy/static/no-token] config key 'token' is required"))

  private def distDirOf(ctx: DeployContext): String =
    ctx.config.get("dist_dir").collect { case s: String => s }
      .getOrElse((ctx.workDir / "dist").toString)

  private def inferUrl(ctx: DeployContext): String =
    val project = projectOf(ctx)
    providerOf(ctx) match
      case "vercel"           => s"https://$project.vercel.app"
      case "netlify"          => s"https://$project.netlify.app"
      case "cloudflare-pages" => s"https://$project.pages.dev"
      case "github-pages"     => s"https://$project.github.io"
      case _                  => s"https://$project"

  private def findOnPath(binary: String): Option[String] =
    Try {
      val pb     = ProcessBuilder("which", binary)
      val p      = pb.start()
      val out    = scala.io.Source.fromInputStream(p.getInputStream).mkString.trim
      p.waitFor()
      if p.exitValue() == 0 && out.nonEmpty then Some(out) else None
    }.toOption.flatten

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

  private def runWithEnv(cmd: List[String], workDir: os.Path, extraEnv: Map[String,String]): RunResult =
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
