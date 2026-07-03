package scalascript.cli

// `ssc deploy [subverb] [target|group] [--env] [flags]` (v1.52): resolves the
// deploy manifest, builds the target/group DAG, and drives the deploy SPI.
// Extracted from Main.scala; the deploy SPI types are imported locally in
// each method as before.

// ssc deploy [subverb] [target|group] [--env=<env>] [flags]  —  v1.52
// ─────────────────────────────────────────────────────────────────────────────

final class DeployCmd extends CliCommand:
  def name = "deploy"
  override def summary = "Deploy to hostings, clouds & Kubernetes-like environments"
  override def category = "Build, bundle & package"
  def run(args: List[String]): Unit =
    import scalascript.compiler.plugin.deploy.*
    import scalascript.compiler.plugin.deploy.DeployManifest.{parse => parseDeployManifest, *}
    import scalascript.parser.Parser

    var envFlag:       Option[String] = None
    var dryRun:        Boolean        = false
    var verbose:       Boolean        = false
    var manifestPath:  Option[String] = None
    var slotOverride:  Option[String] = None
    val positional     = scala.collection.mutable.ListBuffer.empty[String]

    val it = args.iterator
    while it.hasNext do
      it.next() match
        case s if s.startsWith("--env=")      => envFlag      = Some(s.stripPrefix("--env="))
        case "--env" if it.hasNext            => envFlag      = Some(it.next())
        case "--dry-run"                      => dryRun       = true
        case "--verbose" | "-v"               => verbose      = true
        case s if s.startsWith("--manifest=") => manifestPath = Some(s.stripPrefix("--manifest="))
        case "--manifest" if it.hasNext       => manifestPath = Some(it.next())
        case s if s.startsWith("--slot=")     => slotOverride = Some(s.stripPrefix("--slot="))
        case "--slot" if it.hasNext           => slotOverride = Some(it.next())
        case other => positional += other

    val subverb = positional.headOption.getOrElse("deploy")
    val subject = if positional.size > 1 then positional(1) else ""

    // ─── Load project manifest ────────────────────────────────────────────────
    val sscFile: os.Path = manifestPath.map(p => os.Path(p, os.pwd))
      .orElse(findProjectSsc())
      .getOrElse {
        System.err.println("ssc deploy: no .ssc project file found"); System.exit(1); ???
      }
    val source  = os.read(sscFile)
    val module  = Parser.parse(source)
    val mf      = module.manifest.getOrElse {
      System.err.println("ssc deploy: .ssc file has no front-matter manifest"); System.exit(1); ???
    }

    // Merge the raw YAML from all four deploy keys into one map for parsing
    val deployRaw: Map[String, Any] = Map(
      "deploy"       -> mf.raw.getOrElse("deploy", null),
      "groups"       -> mf.raw.getOrElse("groups", null),
      "environments" -> mf.raw.getOrElse("environments", null),
      "state"        -> mf.raw.getOrElse("state", null),
    ).collect { case (k, v) if v != null => k -> v }

    val dm = parseDeployManifest(deployRaw)

    if dm.targets.isEmpty && dm.environments.isEmpty then
      System.err.println("ssc deploy: manifest has no deploy: or environments: blocks")
      System.exit(1)

    val resolvedEnv = envFlag.getOrElse(defaultEnv(dm))

    subverb match

      case "envs" =>
        if dm.environments.isEmpty then println("No environments declared.")
        else
          println(f"${"ENVIRONMENT"}%-20s ${"PURPOSE"}%-12s ${"SLOT"}%-8s ${"GROUPS"}")
          for (name, env) <- dm.environments do
            val slot = env.blueGreen.map(bg => bg.activeSlot).getOrElse("-")
            val grps = env.activeGroups.mkString(", ")
            println(f"${name}%-20s ${env.purpose.toString.toLowerCase}%-12s ${slot}%-8s ${grps}")

      case "plan" =>
        val groupName = if subject.nonEmpty then subject
                        else dm.groups.headOption.map(_._1).getOrElse {
                          System.err.println("ssc deploy plan: specify a group name"); System.exit(1); ???
                        }
        val group = dm.groups.getOrElse(groupName,
          throw DeployError(s"[deploy/unknown-group] Group '$groupName' not found. Available: ${dm.groups.keys.mkString(", ")}"))
        val sorted = DeployDag.topoSort(group.members, group.deps)
        val stages = group.mode match
          case ExecMode.Parallel            => DeployDag.toStages(sorted, group.deps)
          case ExecMode.Sequence            => sorted.map(List(_))
          case ExecMode.Pipeline(explicit)  => explicit
        println(s"Deploy plan: group=$groupName env=$resolvedEnv mode=${group.mode} on_failure=${group.onFailure}")
        println(s"Resolved execution stages:")
        stages.zipWithIndex.foreach { case (stage, i) =>
          println(s"  Stage ${i + 1}: ${stage.mkString(", ")}")
          stage.foreach { t =>
            val cfg    = dm.targets.getOrElse(t, Map.empty)
            val kind   = cfg.getOrElse("kind", "?").toString
            val deps   = group.deps.getOrElse(t, Nil)
            val depStr = if deps.nonEmpty then s" (depends on: ${deps.mkString(", ")})" else ""
            println(s"           [$t] kind=$kind$depStr")
          }
        }
        if dryRun then println("[dry-run] No actions taken.")

      case "status" =>
        val targets = if subject.nonEmpty then List(subject)
                      else dm.targets.keys.toList
        println(f"${"TARGET"}%-25s ${"KIND"}%-15s ${"HEALTHY"}%-8s ${"REVISION"}")
        for t <- targets do
          val cfg     = dm.targets.getOrElse(t, Map.empty)
          val kind    = cfg.getOrElse("kind", "?").toString
          println(f"${t}%-25s ${kind}%-15s ${"?"}%-8s -")

      case "deploy" | "" =>
        val groupOrTarget = subject
        val env = resolveEnv(dm, resolvedEnv)
        val activeGroups =
          if groupOrTarget.nonEmpty then List(groupOrTarget)
          else env.activeGroups.filter(dm.groups.contains)
        if activeGroups.isEmpty && dm.targets.nonEmpty then
          // Deploy all targets in declaration order as a single sequence
          runDeployTargets(dm.targets.keys.toList, dm, env, resolvedEnv, slotOverride, dryRun, verbose, sscFile.toNIO.getParent.toString)
        else
          for gn <- activeGroups do
            val group = dm.groups.getOrElse(gn,
              throw DeployError(s"[deploy/unknown-group] Group '$gn' not found."))
            runDeployGroup(group, dm, env, resolvedEnv, slotOverride, dryRun, verbose, sscFile.toNIO.getParent.toString)

      case other =>
        System.err.println(s"ssc deploy: unknown subverb '$other'. Use: deploy, plan, envs, status, build, push, rollback, logs, diff, destroy, switch, promote")
        System.exit(1)

private def runDeployGroup(
  group:      scalascript.compiler.plugin.deploy.DeployGroup,
  dm:         scalascript.compiler.plugin.deploy.DeployManifest,
  env:        scalascript.compiler.plugin.deploy.DeployEnvironment,
  envName:    String,
  slot:       Option[String],
  dryRun:     Boolean,
  verbose:    Boolean,
  workDirStr: String,
): Unit =
  import scalascript.compiler.plugin.deploy.*
  val collected = scala.collection.concurrent.TrieMap.empty[String, Map[String, String]]
  def emit(e: DeployEvent): Unit = e match
    case DeployEvent.Started(t, en, s)          => println(s"▶ [$t] env=$en${s.map(x => s" slot=$x").getOrElse("")}")
    case DeployEvent.Building(t, p)             => if verbose then println(s"  building $t: $p")
    case DeployEvent.Pushed(t, ref)             => if verbose then println(s"  pushed $t: $ref")
    case DeployEvent.Deployed(t, outs)          => println(s"✓ [$t] ${outs.get("url").map(u => s"→ $u").getOrElse("")}")
    case DeployEvent.Failed(t, err)             => System.err.println(s"✗ [$t] $err")
    case DeployEvent.RolledBack(t)              => println(s"↩ [$t] rolled back")
    case DeployEvent.SkippedDependency(t, by)   => println(s"— [$t] skipped (depends on failed: $by)")
    case DeployEvent.GroupComplete(g, ok, _)    => println(s"${if ok then "✓" else "✗"} group $g complete")

  def runTarget(targetName: String, @annotation.unused prereqOutputs: Map[String, String]): Option[Map[String, String]] =
    val baseCfg = dm.targets.getOrElse(targetName, Map.empty)
    val overCfg = env.targetOverrides.getOrElse(targetName, Map.empty)
    val cfg = DeployManifest.effectiveTargetConfig(baseCfg, overCfg)
    val kind = cfg.getOrElse("kind", "traditional").toString
    val transport = cfg.getOrElse("transport", "").toString
    val workDir = os.Path(workDirStr)
    val ctx = DeployContext(targetName, cfg, envName, slot, dryRun, verbose,
      n => collected.getOrElse(n, Map.empty), workDir)
    emit(DeployEvent.Started(targetName, envName, slot))
    try
      val adapter: scalascript.compiler.plugin.deploy.DeployTarget =
        if kind == "traditional" && transport == "subprocess" then
          val port = cfg.getOrElse("port", 8080) match
            case n: Integer => n.toInt
            case s: String  => s.toIntOption.getOrElse(8080)
            case _          => 8080
          LocalSubprocessTarget(port)
        else
          StubDeployTarget(kind)
      val art  = adapter.build(ctx)
      emit(DeployEvent.Building(targetName, art.artifactPath))
      val ref  = adapter.push(ctx, art)
      emit(DeployEvent.Pushed(targetName, ref.ref))
      adapter.deploy(ctx, ref)
      val outs = adapter.outputs(ctx)
      collected(targetName) = outs
      emit(DeployEvent.Deployed(targetName, outs))
      Some(outs)
    catch case e: Throwable =>
      emit(DeployEvent.Failed(targetName, e.getMessage))
      None

  val (_, failed, _) = DeployOrchestrator.run(group, runTarget, emit)
  if failed.nonEmpty then
    System.err.println(s"${failed.size} target(s) failed: ${failed.mkString(", ")}")
    System.exit(1)

private def runDeployTargets(
  targets:    List[String],
  dm:         scalascript.compiler.plugin.deploy.DeployManifest,
  env:        scalascript.compiler.plugin.deploy.DeployEnvironment,
  envName:    String,
  slot:       Option[String],
  dryRun:     Boolean,
  verbose:    Boolean,
  workDirStr: String,
): Unit =
  import scalascript.compiler.plugin.deploy.*
  val group = DeployGroup("all", targets, ExecMode.Sequence, Map.empty, FailurePolicy.AbortRemaining, None)
  runDeployGroup(group, dm, env, envName, slot, dryRun, verbose, workDirStr)

/** Placeholder for unimplemented target kinds (v1.52.2+). */
private class StubDeployTarget(targetKind: String) extends scalascript.compiler.plugin.deploy.DeployTarget:
  def kind:         String = targetKind
  def artifactKind: scalascript.compiler.plugin.deploy.ArtifactKind = scalascript.compiler.plugin.deploy.ArtifactKind.FatJar
  def build(ctx: scalascript.compiler.plugin.deploy.DeployContext):   scalascript.compiler.plugin.deploy.BuildResult  =
    if ctx.dryRun then scalascript.compiler.plugin.deploy.BuildResult("(dry-run)", artifactKind)
    else throw scalascript.compiler.plugin.deploy.DeployError(s"[deploy/not-implemented] Target kind '$targetKind' is not yet implemented. Available in v1.52: traditional/subprocess.")
  def push(ctx: scalascript.compiler.plugin.deploy.DeployContext, art: scalascript.compiler.plugin.deploy.BuildResult):   scalascript.compiler.plugin.deploy.PushResult   = scalascript.compiler.plugin.deploy.PushResult("(dry-run)")
  def deploy(ctx: scalascript.compiler.plugin.deploy.DeployContext, ref: scalascript.compiler.plugin.deploy.PushResult):  scalascript.compiler.plugin.deploy.DeployResult = scalascript.compiler.plugin.deploy.DeployResult("(dry-run)")
  def rollback(ctx: scalascript.compiler.plugin.deploy.DeployContext, to: scalascript.compiler.plugin.deploy.RevisionRef): scalascript.compiler.plugin.deploy.RollbackResult = scalascript.compiler.plugin.deploy.RollbackResult("(dry-run)")
  def status(ctx: scalascript.compiler.plugin.deploy.DeployContext):  scalascript.compiler.plugin.deploy.StatusReport = scalascript.compiler.plugin.deploy.StatusReport(ctx.targetName, healthy = false, message = "not implemented")
  def logs(ctx: scalascript.compiler.plugin.deploy.DeployContext, opts: scalascript.compiler.plugin.deploy.LogOpts): Iterator[scalascript.compiler.plugin.deploy.LogLine] = Iterator.empty
  def outputs(ctx: scalascript.compiler.plugin.deploy.DeployContext): Map[String, String] = Map.empty
