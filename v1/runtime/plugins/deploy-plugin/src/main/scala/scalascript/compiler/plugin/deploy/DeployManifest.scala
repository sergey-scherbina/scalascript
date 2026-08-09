package scalascript.compiler.plugin.deploy

import scala.jdk.CollectionConverters.*

/** Parsed deploy configuration extracted from a .ssc module's raw front-matter. */
case class DeployManifest(
  targets:      Map[String, Map[String, Any]],
  groups:       Map[String, DeployGroup],
  environments: Map[String, DeployEnvironment],
  stateConfig:  Map[String, Any],
)

object DeployManifest:

  val empty: DeployManifest = DeployManifest(Map.empty, Map.empty, Map.empty, Map.empty)

  def parse(raw: Map[String, Any]): DeployManifest =
    val targets: Map[String, Map[String, Any]] = raw.get("deploy") match
      case Some(m: java.util.Map[?, ?]) =>
        jMapToScala(m).collect { case (k, v: java.util.Map[?, ?]) => k -> jMapToScala(v) }
      case _ => Map.empty

    val groups: Map[String, DeployGroup] = raw.get("groups") match
      case Some(m: java.util.Map[?, ?]) =>
        jMapToScala(m).collect {
          case (name, v: java.util.Map[?, ?]) => name -> parseGroup(name, jMapToScala(v))
        }
      case _ => Map.empty

    val environments: Map[String, DeployEnvironment] = raw.get("environments") match
      case Some(m: java.util.Map[?, ?]) =>
        jMapToScala(m).collect {
          case (name, v: java.util.Map[?, ?]) => name -> DeployEnvironment.parse(name, jMapToScala(v))
        }
      case _ => Map.empty

    val stateConfig: Map[String, Any] = raw.get("state") match
      case Some(m: java.util.Map[?, ?]) => jMapToScala(m)
      case _ => Map.empty

    DeployManifest(targets, groups, environments, stateConfig)

  private def parseGroup(name: String, raw: Map[String, Any]): DeployGroup =
    val members: List[String] = raw.get("members") match
      case Some(xs: java.util.List[?]) => jListToStrings(xs)
      case _ => Nil

    val mode: ExecMode = raw.get("mode").collect { case s: String => s }.getOrElse("parallel") match
      case "sequence" => ExecMode.Sequence
      case "pipeline" =>
        val stages: List[List[String]] = raw.get("stages") match
          case Some(xs: java.util.List[?]) =>
            xs.asScala.toList.collect { case inner: java.util.List[?] => jListToStrings(inner) }
          case _ => members.map(List(_))
        ExecMode.Pipeline(stages)
      case _ => ExecMode.Parallel

    val deps: Map[String, List[String]] = raw.get("depends_on") match
      case Some(m: java.util.Map[?, ?]) =>
        jMapToScala(m).collect { case (k, v: java.util.List[?]) => k -> jListToStrings(v) }
      case _ => Map.empty

    val onFailure: FailurePolicy = raw.get("on_failure").collect { case s: String => s }.getOrElse("rollback_all") match
      case "continue_remaining" => FailurePolicy.ContinueRemaining
      case "abort_remaining"    => FailurePolicy.AbortRemaining
      case _                    => FailurePolicy.RollbackAll

    val maxPar: Option[Int] = raw.get("max_parallelism").collect { case n: Integer => n.toInt }

    DeployGroup(name, members, mode, deps, onFailure, maxPar)

  // ── Env inheritance resolution ────────────────────────────────────────────

  def resolveEnv(dm: DeployManifest, envName: String): DeployEnvironment =
    val env = dm.environments.getOrElse(envName,
      throw DeployError(s"[deploy/unknown-env] Environment '$envName' not found. Available: ${dm.environments.keys.mkString(", ")}"))
    env.base match
      case None       => env
      case Some(base) =>
        val baseEnv = resolveEnv(dm, base)
        env.copy(
          activeGroups    = if env.activeGroups.nonEmpty then env.activeGroups else baseEnv.activeGroups,
          targetOverrides = baseEnv.targetOverrides ++ env.targetOverrides,
          faultTolerance  = env.faultTolerance.orElse(baseEnv.faultTolerance),
          blueGreen       = env.blueGreen.orElse(baseEnv.blueGreen),
        )

  def effectiveTargetConfig(base: Map[String, Any], overrides: Map[String, Any]): Map[String, Any] =
    base ++ overrides

  def defaultEnv(dm: DeployManifest): String =
    dm.environments.find { case (_, e) => e.purpose == EnvironmentPurpose.Local }.map(_._1)
      .orElse(dm.environments.headOption.map(_._1))
      .getOrElse("local")
