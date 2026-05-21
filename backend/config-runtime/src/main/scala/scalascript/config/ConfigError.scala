package scalascript.config

sealed class ConfigError(message: String) extends RuntimeException(message)

object ConfigError:
  final case class ParseError(msg: String)
      extends ConfigError(s"Config parse error: $msg")
  final case class MissingVariable(ref: String)
      extends ConfigError(s"Missing config variable: ${ref}")
  final case class MissingFile(path: String)
      extends ConfigError(s"Config secret file not found: $path")
  final case class UnknownScheme(scheme: String)
      extends ConfigError(s"Unknown config scheme '$scheme' — built-in: env, file, sops, config")
  final case class FileLoadError(path: String, cause: String)
      extends ConfigError(s"Cannot load config file '$path': $cause")
  final case class MissingKey(path: String)
      extends ConfigError(s"Config key '$path' not found")
  final case class TypeError(path: String, expected: String, got: String)
      extends ConfigError(s"Config key '$path': expected $expected, got $got")
