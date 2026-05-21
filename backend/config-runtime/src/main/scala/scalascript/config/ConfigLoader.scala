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
  frontmatterYaml: String                  = "",
  fencedBlocks:    List[FencedConfigBlock] = Nil,
  externalFiles:   List[ExternalConfigFile] = Nil,
  priorityOrder:   List[Priority]          = Priority.DefaultOrder,
  envLookup:       String => Option[String] = sys.env.get,
  sopsLookup:      String => Option[String] = _ => None,
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
        ConfigParser.parse(content, fmt)
      catch case e: Exception =>
        Left(ConfigError.FileLoadError(f.path, e.getMessage))

  private def resolveSubstitutions(cv: ConfigValue): Either[ConfigError, ConfigValue] =
    // Build a config-cross-reference lookup from the merged (pre-substitution) tree
    val configLookup: String => Option[String] = path =>
      cv.get(path).flatMap(_.getString)
    SubstitutionEngine.resolveTree(cv, envLookup, configLookup = configLookup,
      sopsLookup = sopsLookup)
