package scalascript.compiler.plugin.deploy

import scala.jdk.CollectionConverters.*

case class CpuTarget(percent: Int)
case class CustomTarget(metric: String, value: Int)

enum AutoscaleTarget:
  case Cpu(t: CpuTarget)
  case Custom(t: CustomTarget)

case class HpaConfig(min: Int, max: Int, targets: List[AutoscaleTarget])

object HpaConfig:
  private def jm(m: java.util.Map[?, ?]): Map[String, Any] =
    m.entrySet().asScala.collect {
      case e if e.getKey.isInstanceOf[String] => e.getKey.asInstanceOf[String] -> (e.getValue: Any)
    }.toMap

  def parse(raw: Map[String, Any]): HpaConfig =
    val min = raw.get("min").collect { case n: Int => n; case n: Integer => n.toInt }.getOrElse(1)
    val max = raw.get("max").collect { case n: Int => n; case n: Integer => n.toInt }.getOrElse(10)
    val targets: List[AutoscaleTarget] = raw.get("target") match
      case Some(xs: java.util.List[?]) =>
        xs.asScala.toList.collect { case m: java.util.Map[?, ?] =>
          val mm: Map[String, Any] = jm(m)
          mm.get("kind").collect { case s: String => s }.getOrElse("cpu") match
            case "custom" =>
              val metric = mm.get("metric").collect { case s: String => s }.getOrElse("requests_per_second")
              val value  = mm.get("value").collect { case n: Int => n; case n: Integer => n.toInt }.getOrElse(100)
              AutoscaleTarget.Custom(CustomTarget(metric, value))
            case _ =>
              val pct = mm.get("percent").collect { case n: Int => n; case n: Integer => n.toInt }.getOrElse(70)
              AutoscaleTarget.Cpu(CpuTarget(pct))
        }
      case _ => Nil
    HpaConfig(min, max, targets)
