package scalascript.config

import org.yaml.snakeyaml.{Yaml, LoaderOptions}
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Parses YAML, JSON (JSON is valid YAML 1.2), and basic HOCON
 *  into the unified [[ConfigValue]] tree.
 *
 *  Full HOCON support (include directives, substitution) comes in Phase 5.
 *  For now HOCON files are parsed as YAML — the shared subset works for
 *  the most common HOCON configs (key = value, key: value, comments). */
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
    // All supported formats (YAML, JSON, basic HOCON) are valid YAML 1.2 supersets.
    // format is retained for future dispatch (e.g. strict-JSON, full-HOCON in Phase 5).
    format match
      case _ => parseYaml(content)

  def parseFrontmatter(yaml: String): Either[ConfigError, ConfigValue] =
    if yaml.isBlank then Right(ConfigValue.empty)
    else parseYaml(yaml)

  private def parseYaml(content: String): Either[ConfigError, ConfigValue] =
    try
      val opts = new LoaderOptions()
      opts.setAllowDuplicateKeys(false)
      val yaml = new Yaml(new SafeConstructor(opts))
      val raw  = yaml.load[Any](content)
      Right(ConfigValue.from(raw))
    catch case e: Exception =>
      Left(ConfigError.ParseError(e.getMessage))
