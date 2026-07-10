package scalascript.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import _root_.ssc.plugin.{NativeDatabaseConfig, NativeRuntimeConfig}

/** Core-free extraction of native runtime configuration from explicit roots. */
private[cli] object NativeFrontmatter:
  def fromFiles(files: List[java.io.File]): NativeRuntimeConfig =
    val databases = collection.mutable.LinkedHashMap.empty[String, NativeDatabaseConfig]
    val owners = collection.mutable.HashMap.empty[String, String]
    files.foreach { file =>
      parseFile(file).foreach { case (name, config) =>
        databases.get(name) match
          case Some(previous) if previous != config =>
            throw new IllegalArgumentException(
              s"conflicting native database '$name' in ${owners(name)} and ${file.getPath}")
          case Some(_) => ()
          case None =>
            databases(name) = config
            owners(name) = file.getPath
      }
    }
    NativeRuntimeConfig(databases.toMap)

  private def parseFile(file: java.io.File): List[(String, NativeDatabaseConfig)] =
    val source = Files.readString(file.toPath, StandardCharsets.UTF_8)
    frontmatter(source) match
      case None => Nil
      case Some(yaml) =>
        val root = asMap(scalascript.parser.SimpleYaml.load[Any](yaml), s"front-matter in ${file.getPath}")
        Option(root.get("databases")) match
          case None => Nil
          case Some(rawDatabases) =>
            val dbs = asMap(rawDatabases, s"databases in ${file.getPath}")
            dbs.asScala.toList.map { case (rawName, rawConfig) =>
              val name = rawName.toString
              val values = asMap(rawConfig, s"databases.$name in ${file.getPath}")
              def required(key: String): String =
                optional(values, key).filter(_.nonEmpty).getOrElse(
                  throw new IllegalArgumentException(
                    s"native database '$name' in ${file.getPath} requires a non-empty $key"))
              name -> NativeDatabaseConfig(
                url = required("url"),
                user = optional(values, "user"),
                password = optional(values, "password"),
                driver = optional(values, "driver"))
            }

  private def frontmatter(source0: String): Option[String] =
    val source = source0.stripPrefix("\ufeff")
    val noShebang =
      if source.startsWith("#!") then
        val newline = source.indexOf('\n')
        if newline < 0 then "" else source.substring(newline + 1)
      else source
    val lines = noShebang.stripLeading().linesIterator.toVector
    if lines.headOption.forall(_.trim != "---") then None
    else
      val end = lines.indexWhere(_.trim == "---", 1)
      if end < 0 then throw new IllegalArgumentException("unterminated native YAML front-matter")
      Some(lines.slice(1, end).mkString("\n"))

  private def asMap(value: Any, label: String): java.util.Map[?, ?] = value match
    case map: java.util.Map[?, ?] => map
    case _ => throw new IllegalArgumentException(s"$label must be a YAML mapping")

  private def optional(values: java.util.Map[?, ?], key: String): Option[String] =
    Option(values.get(key)).map {
      case text: String => text
      case _ => throw new IllegalArgumentException(s"native database $key must be a String")
    }
