package scalascript.imports

import scalascript.parser.SimpleYaml
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** One entry in the ScalaScript package registry (`packages.yaml`).
 *
 *  Registry `packages.yaml` format — a YAML sequence of entries:
 *  ```yaml
 *  - name: io.example/json-extra
 *    version: 1.2.0
 *    description: "Extra JSON utilities for ScalaScript"
 *    keywords: [json, serialization, codec]
 *    backends: [jvm, js]
 *    url: "github:example/json-extra@v1.2.0"
 *    license: Apache-2.0
 *    author: "Jane Example <jane@example.com>"
 *    homepage: "https://github.com/example/json-extra"
 *    changelog: "https://github.com/example/json-extra/blob/main/CHANGELOG.md"
 *    scala-script-version: ">=1.60"
 *  ```
 *
 *  `name` and `version` are required; all other fields are optional.
 *  Allowed `url` schemes: `github:`, `jitpack:`, `dep:`, `https:`.
 *
 *  See `docs/specs/arch-registry.md §3b`. */
case class RegistryEntry(
  name:               String,
  version:            String,
  description:        String       = "",
  keywords:           List[String] = Nil,
  backends:           List[String] = Nil,
  url:                String       = "",
  license:            String       = "",
  author:             String       = "",
  homepage:           String       = "",
  changelog:          String       = "",
  scalaScriptVersion: String       = "",
  deprecated:         Boolean      = false,
)

object RegistryEntry:

  val AllowedUrlSchemes = Set("github:", "jitpack:", "dep:", "https://", "http://")

  /** Parse and validate a complete `packages.yaml` document.
   *  Returns `Left(errors)` if any entry fails validation. */
  def parseAll(yaml: String): Either[List[String], List[RegistryEntry]] =
    val entries = parseAllRaw(yaml)
    val (errors, valid) = entries.partitionMap {
      case Left(e)  => Left(e)
      case Right(e) => validate(e).toLeft(e)
    }
    val flatErrors = errors.flatten
    if flatErrors.nonEmpty then Left(flatErrors) else Right(valid)

  /** Parse without validation — returns each entry or a parse error string. */
  def parseAllRaw(yaml: String): List[Either[List[String], RegistryEntry]] =
    Try {
      val raw = SimpleYaml.load[java.util.List[Any]](yaml)
      if raw == null then Nil
      else
        raw.asScala.toList.zipWithIndex.flatMap { (item, idx) =>
          item match
            case m: java.util.Map[?, ?] =>
              val fields = m.asInstanceOf[java.util.Map[String, Any]].asScala
              def str(k: String, dflt: String = "") =
                fields.get(k).map(_.toString.trim).filter(_.nonEmpty).getOrElse(dflt)
              def strList(k: String): List[String] =
                fields.get(k) match
                  case Some(l: java.util.List[?]) =>
                    l.asScala.toList.map(_.toString.trim).filter(_.nonEmpty)
                  case Some(s: String) =>
                    s.split(',').map(_.trim).filter(_.nonEmpty).toList
                  case _ => Nil
              val name    = str("name")
              val version = str("version")
              if name.isEmpty then
                List(Left(List(s"entry[$idx]: missing required field 'name'")))
              else if version.isEmpty then
                List(Left(List(s"entry[$idx] '$name': missing required field 'version'")))
              else
                List(Right(RegistryEntry(
                  name               = name,
                  version            = version,
                  description        = str("description"),
                  keywords           = strList("keywords"),
                  backends           = strList("backends"),
                  url                = str("url"),
                  license            = str("license"),
                  author             = str("author"),
                  homepage           = str("homepage"),
                  changelog          = str("changelog"),
                  scalaScriptVersion = str("scala-script-version"),
                  deprecated         = fields.get("deprecated").exists {
                    case b: java.lang.Boolean => b
                    case s: String            => s.toLowerCase == "true"
                    case _                    => false
                  },
                )))
            case _ =>
              List(Left(List(s"entry[$idx]: expected a mapping")))
        }
    }.getOrElse(List(Left(List(s"YAML parse error"))))

  /** Validate a single entry.  Returns `Some(errors)` if invalid. */
  def validate(e: RegistryEntry): Option[List[String]] =
    val errs = List.newBuilder[String]
    // name must be <group>/<artifact>
    if !e.name.contains('/') then
      errs += s"'${e.name}': name must be in '<group>/<artifact>' format"
    // version must be non-empty and look like semver
    if e.version.isEmpty then
      errs += s"'${e.name}': version is required"
    else if !e.version.matches("\\d+\\.\\d+.*") then
      errs += s"'${e.name}': version '${e.version}' does not look like semver (X.Y...)"
    // url scheme check
    if e.url.nonEmpty && AllowedUrlSchemes.forall(s => !e.url.startsWith(s)) then
      errs += s"'${e.name}': url scheme not allowed (got '${e.url.takeWhile(_ != ':')}:')"
    // homepage / changelog must start with https:// if provided
    if e.homepage.nonEmpty && !e.homepage.startsWith("https://") && !e.homepage.startsWith("http://") then
      errs += s"'${e.name}': homepage must be an https:// URL"
    val result = errs.result()
    if result.isEmpty then None else Some(result)

  def toYaml(entries: List[RegistryEntry]): String =
    val sb = new StringBuilder
    entries.foreach { e =>
      sb.append(s"- name: ${e.name}\n")
      sb.append(s"  version: ${e.version}\n")
      if e.description.nonEmpty     then sb.append(s"  description: \"${e.description}\"\n")
      if e.keywords.nonEmpty        then sb.append(s"  keywords: [${e.keywords.mkString(", ")}]\n")
      if e.backends.nonEmpty        then sb.append(s"  backends: [${e.backends.mkString(", ")}]\n")
      if e.url.nonEmpty             then sb.append(s"  url: \"${e.url}\"\n")
      if e.license.nonEmpty         then sb.append(s"  license: ${e.license}\n")
      if e.author.nonEmpty          then sb.append(s"  author: \"${e.author}\"\n")
      if e.homepage.nonEmpty        then sb.append(s"  homepage: \"${e.homepage}\"\n")
      if e.changelog.nonEmpty       then sb.append(s"  changelog: \"${e.changelog}\"\n")
      if e.scalaScriptVersion.nonEmpty then sb.append(s"  scala-script-version: \"${e.scalaScriptVersion}\"\n")
      if e.deprecated               then sb.append(s"  deprecated: true\n")
    }
    sb.toString
