package scalascript.config

import java.nio.file.{Path, Files}

/** Specification of a named fenced config block from a .ssc file. */
final case class FencedConfigBlock(
  name:    String,       // empty = root-level (no name tag)
  content: String,
  format:  ConfigParser.Format = ConfigParser.Format.Yaml,
)

/** Reference to an external config file. */
final case class ExternalConfigFile(
  path:     String,
  optional: Boolean = false,
  basePath: Path = Path.of("."),
)

/** Loads and merges all config sources for a .ssc module run.
 *
 *  Typical call sequence:
 *  {{{
 *  val loader = ConfigLoader(
 *    frontmatterYaml = "...",
 *    fencedBlocks    = List(FencedConfigBlock("server", yamlText)),
 *    externalFiles   = List(ExternalConfigFile("app.yaml")),
 *  )
 *  val config: ConfigValue = loader.load().fold(throw _, identity)
 *  }}}
 */
final class ConfigLoader(
  frontmatterYaml: String                   = "",
  fencedBlocks:    List[FencedConfigBlock]  = Nil,
  externalFiles:   List[ExternalConfigFile] = Nil,
  priorityOrder:   List[Priority]           = Priority.DefaultOrder,
  envLookup:       String => Option[String] = sys.env.get,
  sopsLookup:      String => Option[String] = _ => None,
  @annotation.unused basePath: Path         = Path.of("."),
):

  def load(): Either[ConfigError, ConfigValue] =
    for
      fm     <- parseFrontmatter()
      blocks <- parseBlocks()
      files  <- loadFiles()
      merged =  MergeEngine.mergeAll(fm, files, blocks, priorityOrder)
      // Resolve substitutions after merge so ${config:x} cross-refs work on merged tree
      resolved <- resolveSubstitutions(merged)
    yield resolved

  private def parseFrontmatter(): Either[ConfigError, ConfigValue] =
    ConfigParser.parseFrontmatter(frontmatterYaml)

  private def parseBlocks(): Either[ConfigError, List[ConfigValue]] =
    fencedBlocks.foldLeft(Right(Nil): Either[ConfigError, List[ConfigValue]]) {
      case (accE, block) =>
        for
          acc    <- accE
          parsed <- ConfigParser.parse(block.content, block.format)
          wrapped = if block.name.isEmpty then parsed
                    else ConfigValue.empty.set(block.name, parsed)
        yield acc :+ wrapped
    }

  private def loadFiles(): Either[ConfigError, List[ConfigValue]] =
    externalFiles.foldLeft(Right(Nil): Either[ConfigError, List[ConfigValue]]) {
      case (accE, extFile) =>
        for
          acc     <- accE
          loaded  <- loadFile(extFile)
        yield acc :+ loaded
    }

  private def loadFile(f: ExternalConfigFile): Either[ConfigError, ConfigValue] =
    val resolved = f.basePath.resolve(f.path)
    if f.optional && !Files.exists(resolved) then
      Right(ConfigValue.empty)
    else if !Files.exists(resolved) then
      Left(ConfigError.FileLoadError(f.path, "file not found"))
    else
      try
        val content = Files.readString(resolved)
        val fmt     = ConfigParser.detectFormat(f.path)
        ConfigParser.parse(content, fmt, resolved.getParent)
      catch case e: Exception =>
        Left(ConfigError.FileLoadError(f.path, e.getMessage))

  /** Return a new ConfigLoader with a different priority order. */
  def withPriority(order: List[Priority]): ConfigLoader =
    new ConfigLoader(
      frontmatterYaml = frontmatterYaml,
      fencedBlocks    = fencedBlocks,
      externalFiles   = externalFiles,
      priorityOrder   = order,
      envLookup       = envLookup,
      sopsLookup      = sopsLookup,
      basePath        = basePath,
    )

  private def resolveSubstitutions(cv: ConfigValue): Either[ConfigError, ConfigValue] =
    // Build a config-cross-reference lookup from the merged (pre-substitution) tree
    val configLookup: String => Option[String] = path =>
      cv.get(path).flatMap(_.getString)
    SubstitutionEngine.resolveTree(cv, envLookup, configLookup = configLookup,
      sopsLookup = sopsLookup)

object ConfigLoader:

  /** Build a ConfigLoader from raw front-matter YAML + optional extras.
   *  Automatically extracts the `config.files` list from parsed front-matter,
   *  resolving `${env:...}` substitutions in file paths. */
  def fromFrontmatter(
    frontmatterYaml: String,
    fencedBlocks:    List[FencedConfigBlock] = Nil,
    basePath:        Path                    = Path.of("."),
    envLookup:       String => Option[String] = sys.env.get,
    sopsLookup:      String => Option[String] = _ => None,
  ): ConfigLoader =
    val externalFiles = extractFileList(frontmatterYaml, basePath, envLookup)
    val loader = new ConfigLoader(
      frontmatterYaml = frontmatterYaml,
      fencedBlocks    = fencedBlocks,
      externalFiles   = externalFiles,
      envLookup       = envLookup,
      sopsLookup      = sopsLookup,
      basePath        = basePath,
    )
    // Auto-detect `config.priority` override from front-matter
    val parsed  = ConfigParser.parseFrontmatter(frontmatterYaml).getOrElse(ConfigValue.empty)
    val orderOv = PriorityConfig.fromConfigValue(parsed)
    orderOv.fold(loader)(loader.withPriority)

  private def extractFileList(
    yaml:      String,
    basePath:  Path,
    envLookup: String => Option[String],
  ): List[ExternalConfigFile] =
    if yaml.isBlank then return Nil
    ConfigParser.parseFrontmatter(yaml) match
      case Left(_)   => Nil
      case Right(cv) =>
        // config: [file1, file2]  OR  config.files: [...]
        val filesNode: List[ConfigValue] = cv.get("config") match
          case Some(ConfigValue.Lst(items)) =>
            // config: [app.yaml, prod.hocon]  shorthand
            items
          case Some(ConfigValue.Map(m)) =>
            m.get("files") match
              case Some(ConfigValue.Lst(items)) => items
              case _                             => Nil
          case _ => Nil

        filesNode.flatMap {
          case ConfigValue.Str(path) =>
            SubstitutionEngine.resolveString(path, envLookup = envLookup).toOption
              .map(resolved => ExternalConfigFile(resolved, optional = false, basePath = basePath))
          case ConfigValue.Map(m) =>
            val path     = m.get("path").flatMap(_.getString).getOrElse("")
            val optional = m.get("optional").flatMap(_.getBool).getOrElse(false)
            val resolved = SubstitutionEngine.resolveString(path, envLookup = envLookup)
              .getOrElse(path)
            if resolved.nonEmpty then
              Some(ExternalConfigFile(resolved, optional = optional, basePath = basePath))
            else None
          case _ => None
        }
