package scalascript.compiler.plugin.deploy

import scala.util.Try
import scala.jdk.CollectionConverters.*

/** Kubernetes deployment target.
 *
 *  Config keys (from manifest `deploy:` block):
 *    namespace      — K8s namespace (default: "default")
 *    cluster        — kubectl cluster name (informational)
 *    kubeconfig     — path to kubeconfig file
 *    context        — kubectl context name
 *    replicas       — pod count (default: 2)
 *    app_port       — container port (default: 8080)
 *    image          — OCI image reference (consumed from ContainerTarget outputs)
 *    host           — Ingress hostname (optional)
 *    ingress_class  — Ingress class annotation (default: nginx)
 *    blue_green     — "true" to enable blue-green slot management
 *    active_slot    — current active slot ("blue"|"green", default: "blue")
 *    resources_cpu_request / resources_cpu_limit / resources_memory_request / resources_memory_limit
 */
class K8sTarget extends ClusterTarget:

  def kind:         String       = "k8s"
  def artifactKind: ArtifactKind = ArtifactKind.OciImage

  // ── SPI lifecycle ──────────────────────────────────────────────────────────

  def build(ctx: DeployContext): BuildResult =
    val image = imageOf(ctx)
    if ctx.verbose then println(s"[deploy/k8s/build] using image: $image")
    BuildResult(image, ArtifactKind.OciImage, Map("image" -> image))

  def push(ctx: DeployContext, art: BuildResult): PushResult =
    PushResult(art.artifactPath)

  def deploy(ctx: DeployContext, ref: PushResult): DeployResult =
    val name = ctx.targetName
    val cfg  = k8sCfg(ctx, ref.ref)
    val host = ctx.config.get("host").collect { case s: String => s }

    if clusterModeEnabled(ctx) then
      val manifest = K8sManifestGenerator.clusterBundle(name, cfg, host)
      applyManifest(manifest, ctx)
      DeployResult(ref.ref, host.map(h => s"https://$h"))
    else if blueGreenEnabled(ctx) then
      deployBlueGreen(name, cfg, host, ctx)
    else
      val manifest = K8sManifestGenerator.bundle(name, cfg, host)
      applyManifest(manifest, ctx)
      DeployResult(ref.ref, host.map(h => s"https://$h"))

  def rollback(ctx: DeployContext, to: RevisionRef): RollbackResult =
    val name      = ctx.targetName
    val namespace = namespaceOf(ctx)

    if ctx.dryRun then
      println(s"[dry-run] Would rollback deployment/$name in $namespace")
      val dryRev = if to.id.nonEmpty then to.id else "rolled-back"
      return RollbackResult(dryRev)

    val cmd    = kubectl(ctx) ++ List("rollout", "undo", s"deployment/$name", s"-n=$namespace")
    val result = run(cmd, ctx.workDir)
    if result.exitCode != 0 then
      throw DeployError(s"[deploy/k8s/rollback-failed] kubectl rollout undo:\n${result.stderr}")
    val revId = if to.id.nonEmpty then to.id else "previous"
    RollbackResult(revId)

  def status(ctx: DeployContext): StatusReport =
    val name      = ctx.targetName
    val namespace = namespaceOf(ctx)
    val cmd       = kubectl(ctx) ++ List("rollout", "status", s"deployment/$name", s"-n=$namespace", "--timeout=10s")
    val result    = run(cmd, ctx.workDir)
    if result.exitCode != 0 then
      StatusReport(ctx.targetName, healthy = false, message = result.stderr.take(200))
    else
      val revCmd = kubectl(ctx) ++ List("get", "deployment", name, s"-n=$namespace",
        "-o=jsonpath={.metadata.annotations.deployment\\.kubernetes\\.io/revision}")
      val rev = run(revCmd, ctx.workDir).stdout.trim
      StatusReport(ctx.targetName, healthy = true,
        revision = if rev.nonEmpty then Some(rev) else None,
        message  = "healthy")

  def logs(ctx: DeployContext, opts: LogOpts): Iterator[LogLine] =
    val name      = ctx.targetName
    val namespace = namespaceOf(ctx)
    val tailFlag  = if opts.tail then List("--follow") else Nil
    val sinceFlag = opts.since.map(s => List("--since", s)).getOrElse(Nil)
    val slotLabel = opts.slot.map(s => s"slot=$s").getOrElse(s"app=$name")
    val cmd = kubectl(ctx) ++ List("logs", s"-n=$namespace", s"-l=$slotLabel") ++ tailFlag ++ sinceFlag
    Try {
      val result = run(cmd, ctx.workDir)
      result.stdout.linesIterator.map { line =>
        LogLine(java.time.Instant.now().toString, "INFO", line)
      }
    }.getOrElse(Iterator.empty)

  def outputs(ctx: DeployContext): Map[String, String] =
    val name      = ctx.targetName
    val namespace = namespaceOf(ctx)
    val serviceIP = Try {
      run(kubectl(ctx) ++ List("get", "service", name, s"-n=$namespace",
        "-o=jsonpath={.spec.clusterIP}"), ctx.workDir).stdout.trim
    }.getOrElse("unknown")
    val ingressHost = Try {
      run(kubectl(ctx) ++ List("get", "ingress", name, s"-n=$namespace",
        "-o=jsonpath={.status.loadBalancer.ingress[0].hostname}"), ctx.workDir).stdout.trim
    }.getOrElse("unknown")
    val host = ctx.config.get("host").collect { case s: String => s }.getOrElse(ingressHost)
    Map("serviceIP" -> serviceIP, "ingressHostname" -> ingressHost, "url" -> s"https://$host")

  // ── Blue-green: switch Service selector ───────────────────────────────────

  def switch(ctx: DeployContext): String =
    val name      = ctx.targetName
    val namespace = namespaceOf(ctx)
    val current   = activeSlotOf(ctx)
    val next      = if current == "blue" then "green" else "blue"

    if ctx.dryRun then
      println(s"[dry-run] Would switch $name service selector $current → $next in $namespace")
      return next

    val patch  = s"""{"spec":{"selector":{"app":"$name","slot":"$next"}}}"""
    val cmd    = kubectl(ctx) ++ List("patch", "service", name, s"-n=$namespace", "--type=merge", s"--patch=$patch")
    val result = run(cmd, ctx.workDir)
    if result.exitCode != 0 then
      throw DeployError(s"[deploy/k8s/switch-failed] kubectl patch service:\n${result.stderr}")
    if ctx.verbose then println(s"[deploy/k8s/switch] $name: $current → $next")
    next

  /** Scale the standby slot to replicas, switch traffic, then scale old slot to 0. */
  def promote(ctx: DeployContext, fromSlot: String, toSlot: String): Unit =
    val name      = ctx.targetName
    val namespace = namespaceOf(ctx)
    val replicas  = replicasOf(ctx)

    if ctx.dryRun then
      println(s"[dry-run] Would promote $name: scale $toSlot to $replicas, switch, scale $fromSlot to 0 in $namespace")
      return

    val scaleUp = kubectl(ctx) ++ List("scale", s"deployment/$name-$toSlot", s"-n=$namespace", s"--replicas=$replicas")
    val upResult = run(scaleUp, ctx.workDir)
    if upResult.exitCode != 0 then
      throw DeployError(s"[deploy/k8s/promote-failed] scale $toSlot:\n${upResult.stderr}")

    // Flip the Service
    val switchCtx = ctx.copy(config = ctx.config + ("active_slot" -> fromSlot))
    switch(switchCtx)

    val scaleDown = kubectl(ctx) ++ List("scale", s"deployment/$name-$fromSlot", s"-n=$namespace", "--replicas=0")
    run(scaleDown, ctx.workDir)
    if ctx.verbose then println(s"[deploy/k8s/promote] $name: $fromSlot → $toSlot")

  // ── Internal helpers ───────────────────────────────────────────────────────

  private def deployBlueGreen(name: String, cfg: K8sManifestGenerator.K8sConfig, host: Option[String], ctx: DeployContext): DeployResult =
    val inactiveSlot = if activeSlotOf(ctx) == "blue" then "green" else "blue"
    val inactiveCfg  = cfg.copy(slot = Some(inactiveSlot))
    val manifest     = K8sManifestGenerator.bundle(name, inactiveCfg, host)
    if ctx.verbose then println(s"[deploy/k8s/blue-green] deploying to slot: $inactiveSlot")
    applyManifest(manifest, ctx)
    val svcManifest = K8sManifestGenerator.service(name, cfg, Some(activeSlotOf(ctx)))
    applyManifest(svcManifest, ctx)
    DeployResult(cfg.image, host.map(h => s"https://$h"),
      Map("slot" -> inactiveSlot, "ready_to_switch" -> "true"))

  private def applyManifest(yaml: String, ctx: DeployContext): Unit =
    if ctx.dryRun then { println(s"[dry-run] kubectl apply:\n$yaml"); return }
    val cmd = kubectl(ctx) ++ List("apply", "-f", "-")
    val pb  = ProcessBuilder(cmd*)
    pb.directory(ctx.workDir.toIO)
    val p = pb.start()
    p.getOutputStream.write(yaml.getBytes("UTF-8"))
    p.getOutputStream.close()
    val stderr = scala.io.Source.fromInputStream(p.getErrorStream).mkString
    p.waitFor()
    if p.exitValue() != 0 then
      throw DeployError(s"[deploy/k8s/apply-failed] kubectl apply:\n$stderr")

  private def k8sCfg(ctx: DeployContext, image: String): K8sManifestGenerator.K8sConfig =
    val hpaOpt = ctx.config.get("autoscale") match
      case Some(m: java.util.Map[?, ?]) =>
        val mm: Map[String, Any] = m.entrySet().asScala.collect {
          case e if e.getKey.isInstanceOf[String] => e.getKey.asInstanceOf[String] -> (e.getValue: Any)
        }.toMap
        Some(HpaConfig.parse(mm))
      case _ => None
    val wm = ctx.config.get("workload_mode").collect { case s: String => s }.getOrElse("deployment") match
      case "statefulset" | "StatefulSet" => WorkloadMode.StatefulSet
      case "daemonset"   | "DaemonSet"   => WorkloadMode.DaemonSet
      case _                             => WorkloadMode.Deployment
    K8sManifestGenerator.K8sConfig(
      namespace        = namespaceOf(ctx),
      replicas         = replicasOf(ctx),
      appPort          = appPortOf(ctx),
      image            = image,
      ingressClass     = ctx.config.get("ingress_class").collect { case s: String => s }.getOrElse("nginx"),
      resources        = resourcesOf(ctx),
      annotations      = annotationsOf(ctx),
      nodeSelector     = nodeSelectorOf(ctx),
      clusterMode      = clusterModeEnabled(ctx),
      workloadMode     = wm,
      authTokenSecret  = ctx.config.get("auth_token_secret").collect { case s: String => s },
      hpa              = hpaOpt,
    )

  private def kubectl(ctx: DeployContext): List[String] =
    val base  = List("kubectl")
    val kcfg  = ctx.config.get("kubeconfig").collect { case s: String => List("--kubeconfig", s) }.getOrElse(Nil)
    val kctx  = ctx.config.get("context").collect   { case s: String => List("--context",    s) }.getOrElse(Nil)
    base ++ kcfg ++ kctx

  private def namespaceOf(ctx: DeployContext): String =
    ctx.config.get("namespace").collect { case s: String => s }.getOrElse("default")

  private def replicasOf(ctx: DeployContext): Int =
    ctx.config.get("replicas").collect { case n: Int => n; case s: String => s.toInt }.getOrElse(2)

  private def appPortOf(ctx: DeployContext): Int =
    ctx.config.get("app_port").collect { case n: Int => n; case s: String => s.toInt }.getOrElse(8080)

  private def imageOf(ctx: DeployContext): String =
    ctx.config.get("image").collect { case s: String => s }
      .orElse(ctx.outputsOf(ctx.targetName).get("image"))
      .getOrElse(s"${ctx.targetName}:latest")

  private def activeSlotOf(ctx: DeployContext): String =
    ctx.config.get("active_slot").collect { case s: String => s }.getOrElse("blue")

  private def blueGreenEnabled(ctx: DeployContext): Boolean =
    ctx.config.get("blue_green").collect {
      case s: String  => s.toLowerCase == "true"
      case b: Boolean => b
    }.getOrElse(false)

  private def clusterModeEnabled(ctx: DeployContext): Boolean =
    _currentCtx = Some(ctx)
    ctx.config.get("cluster_mode").collect {
      case s: String  => s.toLowerCase == "true"
      case b: Boolean => b
    }.getOrElse(false)

  private def resourcesOf(ctx: DeployContext): K8sManifestGenerator.ResourceRequirements =
    K8sManifestGenerator.ResourceRequirements(
      cpuRequest    = ctx.config.get("resources_cpu_request").collect    { case s: String => s }.getOrElse("100m"),
      cpuLimit      = ctx.config.get("resources_cpu_limit").collect      { case s: String => s }.getOrElse("1000m"),
      memoryRequest = ctx.config.get("resources_memory_request").collect { case s: String => s }.getOrElse("256Mi"),
      memoryLimit   = ctx.config.get("resources_memory_limit").collect   { case s: String => s }.getOrElse("512Mi"),
    )

  private def toStringMap(v: Any): Map[String,String] = v match
    case m: Map[?,?] => m.collect { case (k: String, v2: String) => k -> v2 }.asInstanceOf[Map[String,String]]
    case _           => Map.empty

  private def annotationsOf(ctx: DeployContext): Map[String,String] =
    ctx.config.get("annotations").map(toStringMap).getOrElse(Map.empty)

  private def nodeSelectorOf(ctx: DeployContext): Map[String,String] =
    ctx.config.get("node_selector").map(toStringMap).getOrElse(Map.empty)

  // ── ClusterTarget SPI (v1.63.7) ───────────────────────────────────────────

  private var _currentCtx: Option[DeployContext] = None

  def seedUrlsFor(env: DeployEnvironment): List[String] =
    _currentCtx.flatMap(ctx => ctx.config.get("seed_urls")).collect {
      case xs: java.util.List[?] => xs.asScala.map(_.toString).toList
      case s: String             => s.split(",").map(_.trim).toList
    }.getOrElse(Nil)

  def injectAuthToken(env: DeployEnvironment, secretRef: String): Unit =
    if _currentCtx.isDefined && !_currentCtx.get.dryRun then
      val ctx = _currentCtx.get
      val ns  = namespaceOf(ctx)
      val cmd = kubectl(ctx) ++ List("create", "secret", "generic", secretRef,
        s"--namespace=$ns",
        "--from-literal=SSC_CLUSTER_TOKEN=<token>",
        "--dry-run=client", "-o=yaml")
      run(cmd, ctx.workDir)
      ()

  def emitWorkloadManifest(mode: WorkloadMode): String =
    _currentCtx.map { ctx =>
      val cfg = k8sCfg(ctx, ctx.config.get("image").collect { case s: String => s }.getOrElse("app:latest"))
      mode match
        case WorkloadMode.StatefulSet | WorkloadMode.DaemonSet => K8sManifestGenerator.statefulSet(ctx.targetName, cfg)
        case _                                                  => K8sManifestGenerator.deployment(ctx.targetName, cfg)
    }.getOrElse("")

  def emitHeadlessService(): String =
    _currentCtx.map { ctx =>
      K8sManifestGenerator.headlessService(ctx.targetName,
        k8sCfg(ctx, ctx.config.get("image").collect { case s: String => s }.getOrElse("app:latest")))
    }.getOrElse("")

  def emitAutoscaler(policy: ScalePolicy): Option[String] =
    _currentCtx.flatMap { ctx =>
      val cfg = k8sCfg(ctx, ctx.config.get("image").collect { case s: String => s }.getOrElse("app:latest"))
      policy match
        case ScalePolicy.None                  => None
        case ScalePolicy.Cpu(pct)              => Some(K8sManifestGenerator.hpa(ctx.targetName, cfg,
          HpaConfig(1, 10, List(AutoscaleTarget.Cpu(CpuTarget(pct))))))
        case ScalePolicy.Custom(metric, value) => Some(K8sManifestGenerator.hpa(ctx.targetName, cfg,
          HpaConfig(1, 10, List(AutoscaleTarget.Custom(CustomTarget(metric, value.toInt))))))
    }

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
