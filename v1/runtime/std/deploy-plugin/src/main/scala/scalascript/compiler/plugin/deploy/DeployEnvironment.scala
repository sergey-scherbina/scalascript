package scalascript.compiler.plugin.deploy

import scala.jdk.CollectionConverters.*

// ── Helpers ─────────────────────────────────────────────────────────────────

private def jMapToScala(m: java.util.Map[?, ?]): Map[String, Any] =
  m.entrySet().asScala.collect {
    case e if e.getKey.isInstanceOf[String] => e.getKey.asInstanceOf[String] -> (e.getValue: Any)
  }.toMap

private def jListToStrings(xs: java.util.List[?]): List[String] =
  xs.asScala.map(_.toString).toList

// ── Environment purpose ─────────────────────────────────────────────────────

enum EnvironmentPurpose:
  case Local
  case Test
  case Staging
  case Production

object EnvironmentPurpose:
  def parse(s: String): EnvironmentPurpose = s.toLowerCase match
    case "local"      => Local
    case "test"       => Test
    case "staging"    => Staging
    case "production" => Production
    case other        => throw DeployError(s"[deploy/unknown-purpose] Unknown environment purpose: $other")

// ── Fault tolerance ─────────────────────────────────────────────────────────

enum FailoverStrategy:
  case ActiveActive
  case ActivePassive
  case ActiveStandby

case class FaultToleranceConfig(
  multiRegion:      List[String],
  quorum:           Int,
  failoverStrategy: FailoverStrategy
)

// ── Blue-green ──────────────────────────────────────────────────────────────

enum SwitchStrategy:
  case Instant
  case Gradual(steps: List[Int], intervalSeconds: Int)

case class BlueGreenConfig(
  enabled:        Boolean,
  activeSlot:     String,
  switchStrategy: SwitchStrategy,
  healthGate:     String,
  smokeTests:     List[String],
  holdSeconds:    Int
)

// ── Autoscale policy ────────────────────────────────────────────────────────

case class AutoscalePolicy(
  minReplicas: Int,
  maxReplicas: Int,
  targets:     List[AutoscaleTarget]
)

object AutoscalePolicy:
  def parse(raw: Map[String, Any]): AutoscalePolicy =
    val min  = raw.get("min_replicas").collect { case n: Integer => n.toInt }.getOrElse(1)
    val max  = raw.get("max_replicas").collect { case n: Integer => n.toInt }.getOrElse(min)
    val cpuT = raw.get("cpu_percent").collect { case n: Integer => n.toInt }.map { p =>
      AutoscaleTarget.Cpu(CpuTarget(p))
    }
    val custT = (
      raw.get("custom_metric").collect { case s: String => s },
      raw.get("custom_target").collect { case n: Integer => n.toInt }
    ) match
      case (Some(m), Some(v)) => Some(AutoscaleTarget.Custom(CustomTarget(m, v)))
      case _                  => None
    AutoscalePolicy(min, max, List(cpuT, custT).flatten)

// ── Environment declaration ─────────────────────────────────────────────────

case class DeployEnvironment(
  name:            String,
  purpose:         EnvironmentPurpose,
  base:            Option[String],
  targetOverrides: Map[String, Map[String, Any]],
  activeGroups:    List[String],
  faultTolerance:  Option[FaultToleranceConfig],
  blueGreen:       Option[BlueGreenConfig],
  autoscale:       Option[AutoscalePolicy] = None
)

// ── Parser ──────────────────────────────────────────────────────────────────

object DeployEnvironment:

  def parse(name: String, raw: Map[String, Any]): DeployEnvironment =
    val purpose = raw.get("purpose").collect { case s: String => EnvironmentPurpose.parse(s) }
                    .getOrElse(EnvironmentPurpose.Local)
    val base    = raw.get("base").collect { case s: String => s }

    val overrides: Map[String, Map[String, Any]] = raw.get("target_overrides") match
      case Some(m: java.util.Map[?, ?]) =>
        jMapToScala(m).collect {
          case (k, v: java.util.Map[?, ?]) => k -> jMapToScala(v)
        }
      case _ => Map.empty

    val activeGroups: List[String] = raw.get("active_groups") match
      case Some(xs: java.util.List[?]) => jListToStrings(xs)
      case _ => Nil

    val ft: Option[FaultToleranceConfig] = raw.get("fault_tolerance") match
      case Some(m: java.util.Map[?, ?]) =>
        val mm = jMapToScala(m)
        val regions = mm.get("multi_region") match
          case Some(xs: java.util.List[?]) => jListToStrings(xs)
          case _ => Nil
        val quorum = mm.get("quorum").collect { case n: Integer => n.toInt }.getOrElse(1)
        val fs = mm.get("failover_strategy").collect { case s: String => s }.getOrElse("active-active") match
          case "active-passive" => FailoverStrategy.ActivePassive
          case "active-standby" => FailoverStrategy.ActiveStandby
          case _                => FailoverStrategy.ActiveActive
        Some(FaultToleranceConfig(regions, quorum, fs))
      case _ => None

    val bg: Option[BlueGreenConfig] = raw.get("blue_green") match
      case Some(m: java.util.Map[?, ?]) =>
        val mm      = jMapToScala(m)
        val enabled  = mm.get("enabled").collect { case b: java.lang.Boolean => b.booleanValue }.getOrElse(false)
        val slot     = mm.get("active_slot").collect { case s: String => s }.getOrElse("blue")
        val gate     = mm.get("health_gate").collect { case s: String => s }.getOrElse("/_health")
        val smoke    = mm.get("smoke_tests") match
          case Some(xs: java.util.List[?]) => jListToStrings(xs)
          case _ => Nil
        val hold     = mm.get("hold_duration").collect { case s: String => parseDurationSeconds(s) }.getOrElse(1800)
        val strategy = mm.get("switch_strategy").collect { case s: String => s }.getOrElse("instant") match
          case "gradual" =>
            val steps = mm.get("steps") match
              case Some(xs: java.util.List[?]) => xs.asScala.collect { case n: Integer => n.toInt }.toList
              case _ => List(10, 50, 100)
            val interval = mm.get("step_interval").collect { case s: String => parseDurationSeconds(s) }.getOrElse(300)
            SwitchStrategy.Gradual(steps, interval)
          case _ => SwitchStrategy.Instant
        Some(BlueGreenConfig(enabled, slot, strategy, gate, smoke, hold))
      case _ => None

    val asPol: Option[AutoscalePolicy] = raw.get("autoscale") match
      case Some(m: java.util.Map[?, ?]) => Some(AutoscalePolicy.parse(jMapToScala(m)))
      case _                            => None

    DeployEnvironment(name, purpose, base, overrides, activeGroups, ft, bg, asPol)

  private def parseDurationSeconds(s: String): Int =
    val c = s.trim.toLowerCase
    if c.endsWith("m") then c.dropRight(1).trim.toIntOption.map(_ * 60).getOrElse(0)
    else if c.endsWith("h") then c.dropRight(1).trim.toIntOption.map(_ * 3600).getOrElse(0)
    else if c.endsWith("s") then c.dropRight(1).trim.toIntOption.getOrElse(0)
    else c.toIntOption.getOrElse(0)
