package scalascript.config

/** Resolves secret / variable references in config string values.
 *
 *  Supported syntax:
 *  - `${env:NAME}`            — environment variable (required)
 *  - `${env:NAME | default}`  — environment variable with fallback
 *  - `${file:/path/to/file}`  — read file content (trimmed)
 *  - `${file:/path | default}` — file with fallback
 *  - `${sops:key.path}`       — key from sops-decrypted YAML (via SopsLookup)
 *  - `${config:some.key}`     — cross-reference another key in the config tree
 *  - `${?VAR}` (HOCON-style)  — optional env var, empty string if missing
 *  - `${VAR}`  (HOCON-style)  — required env var
 *
 *  Resolution is applied recursively to all string leaves in a ConfigValue tree. */
object SubstitutionEngine:

  // ${scheme:ref} or ${scheme:ref | default}
  private val SchemePattern =
    """\$\{([A-Za-z][A-Za-z0-9_-]*):([^|}]+?)(?:\s*\|\s*([^}]*))?\}""".r

  // HOCON ${?VAR}
  private val HoconOpt = """\$\{\?([A-Za-z_][A-Za-z0-9_]*)\}""".r

  // HOCON ${VAR}
  private val HoconReq = """\$\{([A-Za-z_][A-Za-z0-9_]*)\}""".r

  /** Resolve all substitutions in a single string template. */
  def resolveString(
    template:    String,
    envLookup:   String => Option[String]  = sys.env.get,
    fileLookup:  String => Option[String]  = readFile,
    sopsLookup:  String => Option[String]  = _ => None,
    configLookup: String => Option[String] = _ => None,
  ): Either[ConfigError, String] =
    try
      var s = template

      // 1. ${scheme:ref | default}
      s = SchemePattern.replaceAllIn(s, m =>
        val scheme  = m.group(1)
        val ref     = m.group(2).trim
        val default = Option(m.group(3)).map(_.trim)
        val value   = scheme match
          case "env"    => envLookup(ref).orElse(default)
            .getOrElse(throw ConfigError.MissingVariable(s"env:$ref"))
          case "file"   => fileLookup(ref).orElse(default)
            .getOrElse(throw ConfigError.MissingFile(ref))
          case "sops"   => sopsLookup(ref).orElse(default)
            .getOrElse(throw ConfigError.MissingVariable(s"sops:$ref"))
          case "config" => configLookup(ref).orElse(default)
            .getOrElse(throw ConfigError.MissingKey(ref))
          case other    => default
            .getOrElse(throw ConfigError.UnknownScheme(other))
        java.util.regex.Matcher.quoteReplacement(value)
      )

      // 2. HOCON ${?VAR} — optional
      s = HoconOpt.replaceAllIn(s, m =>
        java.util.regex.Matcher.quoteReplacement(envLookup(m.group(1)).getOrElse(""))
      )

      // 3. HOCON ${VAR} — required (only if not already matched by SchemePattern)
      s = HoconReq.replaceAllIn(s, m =>
        java.util.regex.Matcher.quoteReplacement(
          envLookup(m.group(1))
            .getOrElse(throw ConfigError.MissingVariable(s"env:${m.group(1)}"))
        )
      )

      Right(s)
    catch case e: ConfigError => Left(e)

  /** Recursively resolve all string leaves in a ConfigValue tree. */
  def resolveTree(
    cv:          ConfigValue,
    envLookup:   String => Option[String] = sys.env.get,
    fileLookup:  String => Option[String] = readFile,
    sopsLookup:  String => Option[String] = _ => None,
    configLookup: String => Option[String] = _ => None,
  ): Either[ConfigError, ConfigValue] =
    cv match
      case ConfigValue.Str(s) =>
        resolveString(s, envLookup, fileLookup, sopsLookup, configLookup)
          .map(ConfigValue.Str(_))
      case ConfigValue.Map(m) =>
        m.foldLeft(Right(Map.empty): Either[ConfigError, Map[String, ConfigValue]]) {
          case (accE, (k, v)) =>
            for
              acc <- accE
              rv  <- resolveTree(v, envLookup, fileLookup, sopsLookup, configLookup)
            yield acc + (k -> rv)
        }.map(ConfigValue.Map(_))
      case ConfigValue.Lst(vs) =>
        vs.foldLeft(Right(List.empty): Either[ConfigError, List[ConfigValue]]) {
          case (accE, v) =>
            for
              acc <- accE
              rv  <- resolveTree(v, envLookup, fileLookup, sopsLookup, configLookup)
            yield acc :+ rv
        }.map(ConfigValue.Lst(_))
      case other => Right(other)

  private def readFile(path: String): Option[String] =
    try
      val f = java.io.File(path)
      if f.exists() && f.canRead then
        Some(scala.io.Source.fromFile(f).mkString.strip())
      else None
    catch case _: Exception => None
