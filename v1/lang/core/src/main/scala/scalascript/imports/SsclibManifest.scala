package scalascript.imports

import scalascript.parser.SimpleYaml
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Parsed `ssclib-manifest.yaml` from a `.ssclib` archive.
 *
 *  Archive layout (`my-lib-1.0.ssclib`):
 *  ```
 *  ssclib-manifest.yaml
 *  src/
 *    main.ssc          # public entry point (declared by `entry:`)
 *    internal/
 *      utils.ssc
 *  ir/                 # optional pre-compiled IR (Phase 6)
 *    main.scim
 *  ```
 *
 *  YAML schema:
 *  ```yaml
 *  name: io.example/my-lib
 *  version: 1.0.0
 *  entry: src/main.ssc
 *  scala-script-version: ">=1.60"
 *  dependencies:
 *    - dep: io.scalascript/json:1.0.0
 *    - dep: io.example/utils:2.1.0
 *  description: optional human-readable description
 *  author: optional author
 *  glue:
 *    jvm: jvm/glue.jar    # optional JVM glue archive (arch-ffi Phase 3)
 *    js:  js/glue.js      # optional JS glue preamble (arch-ffi Phase 4)
 *  ``` */
case class SsclibManifest(
  name:               String,
  version:            String       = "0.1.0",
  entry:              String       = "src/main.ssc",
  scalaScriptVersion: String       = ">=1.60",
  dependencies:       List[String] = Nil,
  description:        Option[String] = None,
  author:             Option[String] = None,
  glueJvm:            Option[String] = None,  // archive path for jvm/glue.jar
  glueJs:             Option[String] = None,  // archive path for js/glue.js
):
  /** Best-effort short identifier used in cache directory naming. */
  def cacheId: String = name.replace('/', '_').replace(':', '_')

object SsclibManifest:

  val FileName = "ssclib-manifest.yaml"

  def parseString(yaml: String): Try[SsclibManifest] = Try {
    val raw = SimpleYaml.load[java.util.Map[String, Any]](yaml)
    val m = Option(raw)
      .map(_.asScala.toMap)
      .getOrElse(throw RuntimeException(s"$FileName: empty document"))

    def requireStr(key: String): String =
      m.getOrElse(key, throw RuntimeException(s"$FileName: missing required field '$key'"))
        .asInstanceOf[Object].toString

    def str(key: String, default: String): String =
      m.get(key).map(_.toString).getOrElse(default)

    def optStr(key: String): Option[String] =
      m.get(key).map(_.toString)

    def depsList(): List[String] =
      m.get("dependencies") match
        case Some(l: java.util.List[?]) =>
          l.asScala.toList.flatMap {
            case s: String =>
              List(s.trim).filter(_.nonEmpty)
            case item: java.util.Map[?, ?] =>
              val fields = item.asInstanceOf[java.util.Map[String, Any]].asScala
              fields.get("dep").map(_.toString).toList
            case _ => Nil
          }
        case Some(s: String) => List(s.trim).filter(_.nonEmpty)
        case _               => Nil

    def glueMap(): Map[String, Any] =
      m.get("glue") match
        case Some(gm: java.util.Map[?, ?]) =>
          gm.asInstanceOf[java.util.Map[String, Any]].asScala.toMap
        case _ => Map.empty

    val glue = glueMap()
    SsclibManifest(
      name               = requireStr("name"),
      version            = str("version", "0.1.0"),
      entry              = str("entry", "src/main.ssc"),
      scalaScriptVersion = str("scala-script-version", ">=1.60"),
      dependencies       = depsList(),
      description        = optStr("description"),
      author             = optStr("author"),
      glueJvm            = glue.get("jvm").map(_.toString),
      glueJs             = glue.get("js").map(_.toString),
    )
  }

  def toYaml(m: SsclibManifest): String =
    val sb = new StringBuilder
    sb.append(s"name: ${m.name}\n")
    sb.append(s"version: ${m.version}\n")
    sb.append(s"entry: ${m.entry}\n")
    sb.append(s"scala-script-version: \"${m.scalaScriptVersion}\"\n")
    m.description.foreach(d => sb.append(s"description: $d\n"))
    m.author.foreach(a    => sb.append(s"author: $a\n"))
    if m.dependencies.nonEmpty then
      sb.append("dependencies:\n")
      m.dependencies.foreach(dep => sb.append(s"  - dep: $dep\n"))
    if m.glueJvm.isDefined || m.glueJs.isDefined then
      sb.append("glue:\n")
      m.glueJvm.foreach(j => sb.append(s"  jvm: $j\n"))
      m.glueJs.foreach(j  => sb.append(s"  js: $j\n"))
    sb.toString
