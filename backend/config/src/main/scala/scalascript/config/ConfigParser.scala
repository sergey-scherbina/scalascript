package scalascript.config

import scalascript.parser.SimpleYaml

/** Parses YAML, JSON (JSON is valid YAML 1.2), and basic HOCON
 *  into the unified [[ConfigValue]] tree.
 *
 *  Phase 5: HOCON include directives are pre-processed before YAML parsing.
 *  Full HOCON parser (proper grammar, all substitution forms) is future work. */
object ConfigParser:

  enum Format:
    case Yaml, Json, Hocon

  def detectFormat(fileName: String): Format =
    fileName.toLowerCase.split('.').lastOption.getOrElse("") match
      case "yaml" | "yml"   => Format.Yaml
      case "json"           => Format.Json
      case "conf" | "hocon" => Format.Hocon
      case _                => Format.Yaml

  def parse(content: String, format: Format = Format.Yaml): Either[ConfigError, ConfigValue] =
    parse(content, format, java.nio.file.Path.of("."))

  def parse(content: String, format: Format, basePath: java.nio.file.Path): Either[ConfigError, ConfigValue] =
    format match
      case Format.Hocon => parseHocon(content, basePath)
      case _            => parseYaml(content)

  def parseFrontmatter(yaml: String): Either[ConfigError, ConfigValue] =
    if yaml.isBlank then Right(ConfigValue.empty)
    else parseYaml(yaml)

  /** Parse HOCON content, resolving include directives relative to `basePath`.
   *
   *  Supported directives:
   *  - `include "file.conf"`           — required include (error if missing)
   *  - `include "optional: file.conf"` — optional include (silently ignored if missing)
   *
   *  After include expansion the content is normalised (key = value → key: value)
   *  and handed to the YAML parser. */
  def parseHocon(content: String, basePath: java.nio.file.Path = java.nio.file.Path.of(".")): Either[ConfigError, ConfigValue] =
    val OptionalInclude = """^\s*include\s+"optional:\s*([^"]+)"\s*$""".r
    val IncludePattern  = """^\s*include\s+"([^"]+)"\s*$""".r

    // Expand include directives line by line; short-circuit on first required-include error
    val expanded: Either[ConfigError, String] =
      val sb   = new StringBuilder
      var err: Option[ConfigError] = None
      val iter = content.linesIterator
      while iter.hasNext && err.isEmpty do
        val line = iter.next()
        OptionalInclude.findFirstMatchIn(line) match
          case Some(m) =>
            val incPath = basePath.resolve(m.group(1))
            if java.nio.file.Files.exists(incPath) then
              try sb.append(java.nio.file.Files.readString(incPath)).append('\n')
              catch case _: Exception => () // silently ignore read errors for optional includes
          case None =>
            IncludePattern.findFirstMatchIn(line) match
              case Some(m) =>
                val incPath = basePath.resolve(m.group(1))
                if java.nio.file.Files.exists(incPath) then
                  try sb.append(java.nio.file.Files.readString(incPath)).append('\n')
                  catch case e: Exception =>
                    err = Some(ConfigError.FileLoadError(m.group(1), e.getMessage))
                else
                  err = Some(ConfigError.FileLoadError(m.group(1), "included file not found"))
              case None =>
                sb.append(line).append('\n')
      err.toLeft(sb.toString)

    expanded.flatMap { raw =>
      // Normalise HOCON key = value → key: value for SnakeYAML
      val normalized = raw.linesIterator.map { line =>
        """^(\s*[A-Za-z0-9_.-]+)\s*=\s*(.*)$""".r.replaceFirstIn(line, "$1: $2")
      }.mkString("\n")
      parseYaml(normalized)
    }

  private def parseYaml(content: String): Either[ConfigError, ConfigValue] =
    try
      val raw = SimpleYaml.load[Any](content)
      Right(ConfigValue.from(raw))
    catch case e: Exception =>
      Left(ConfigError.ParseError(e.getMessage))
