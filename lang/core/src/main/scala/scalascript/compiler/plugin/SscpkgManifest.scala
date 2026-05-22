package scalascript.compiler.plugin

import org.yaml.snakeyaml.Yaml
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Parsed `manifest.yaml` from a `.sscpkg` archive.
 *  See docs/milestones.md §v1.7 Tier 2 for the archive layout.
 *
 *  A `.sscpkg` covers three shapes:
 *  - **library** — `.ssc` source files only (no JAR, no intrinsics)
 *  - **plugin** — compiled intrinsics JAR + optional runtime helpers
 *  - **hybrid** — both shapes combined */
case class SscpkgManifest(
  id:               String,
  version:          String            = "0.1.0",
  spiVersion:       String            = "0.1.0",
  kind:             List[String]      = List("library"),
  targets:          List[String]      = Nil,
  externDefs:       List[String]      = Nil,
  featuresRequired: List[String]      = Nil,
  featuresDeclared: List[String]      = Nil,
  dependencies:     Map[String, String] = Map.empty,  // depId → version constraint
):
  def isLibrary: Boolean = kind.contains("library")
  def isPlugin:  Boolean = kind.contains("plugin")

object SscpkgManifest:

  def parseString(yaml: String): Try[SscpkgManifest] = Try {
    val raw = new Yaml().load[java.util.Map[String, Any]](yaml)
    val m = Option(raw)
      .map(_.asScala.toMap)
      .getOrElse(throw RuntimeException("empty manifest.yaml"))

    def str(key: String, default: String): String =
      m.get(key).map(_.toString).getOrElse(default)

    def requireStr(key: String): String =
      m.getOrElse(key, throw RuntimeException(s"manifest.yaml: missing required field '$key'"))
        .asInstanceOf[Object].toString

    def strList(key: String): List[String] =
      m.get(key) match
        case Some(l: java.util.List[?]) => l.asScala.map(_.toString).toList
        case Some(s: String)            => List(s)
        case _                          => Nil

    def nestedStrList(parent: String, child: String): List[String] =
      m.get(parent) match
        case Some(pm: java.util.Map[?, ?]) =>
          val p = pm.asInstanceOf[java.util.Map[String, Any]].asScala.toMap
          p.get(child) match
            case Some(l: java.util.List[?]) => l.asScala.map(_.toString).toList
            case _                          => Nil
        case _ => Nil

    // dependencies: either a map {id: version} or a list [{id: x, version: y}]
    def parseDeps(): Map[String, String] =
      m.get("dependencies") match
        case Some(dm: java.util.Map[?, ?]) =>
          dm.asInstanceOf[java.util.Map[String, Any]].asScala.map { (k, v) => k -> v.toString }.toMap
        case Some(dl: java.util.List[?]) =>
          dl.asScala.collect {
            case item: java.util.Map[?, ?] =>
              val mm = item.asInstanceOf[java.util.Map[String, Any]].asScala
              mm.get("id").map(_.toString) -> mm.get("version").map(_.toString).getOrElse("*")
          }.collect { case (Some(id), ver) => id -> ver }.toMap
        case _ => Map.empty

    SscpkgManifest(
      id               = requireStr("id"),
      version          = str("version", "0.1.0"),
      spiVersion       = str("spiVersion", "0.1.0"),
      kind             = strList("kind") match { case Nil => List("library"); case ks => ks },
      targets          = strList("targets"),
      externDefs       = nestedStrList("exports", "externDefs"),
      featuresRequired = nestedStrList("capabilities", "features"),
      featuresDeclared = nestedStrList("capabilities", "declares"),
      dependencies     = parseDeps(),
    )
  }
