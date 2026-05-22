package scalascript.config

/** Extracts fenced config blocks from raw `.ssc` source text.
 *
 *  Recognized patterns (case-insensitive language tag):
 *  {{{
 *  ```yaml config "server"
 *  port: 8080
 *  ```
 *
 *  ```json config
 *  {"key": "value"}
 *  ```
 *
 *  ```hocon config "database"
 *  url = "jdbc:h2:mem:test"
 *  ```
 *  }}}
 *
 *  Blocks are returned in document order.  The extractor is intentionally
 *  simple — it does not parse the full `.ssc` AST, so it runs even before
 *  the main parser and can be used for config-only pre-flight checks. */
object FencedConfigExtractor:

  /** A raw (unparsed) config block as found in the source. */
  final case class RawBlock(
    name:    String,              // "" = unnamed (root-level)
    content: String,
    format:  ConfigParser.Format,
  )

  // Matches: ```<lang> config ["<name>"]
  // Groups: 1=lang, 2=name (may be null/absent)
  private val OpenFence =
    """(?m)^```(yaml|json|hocon)\s+config(?:\s+"([^"]*)")?\s*$""".r

  private val CloseFence = """(?m)^```\s*$""".r

  /** Extract all config blocks from `source` in document order. */
  def extract(source: String): List[RawBlock] =
    val result  = scala.collection.mutable.ListBuffer.empty[RawBlock]
    val lines   = source.split("\n", -1).toList
    var i       = 0

    while i < lines.size do
      val line = lines(i)
      OpenFence.findFirstMatchIn(line) match
        case Some(m) =>
          val lang    = m.group(1).toLowerCase
          val name    = Option(m.group(2)).getOrElse("")
          val fmt     = lang match
            case "yaml"           => ConfigParser.Format.Yaml
            case "json"           => ConfigParser.Format.Json
            case "hocon" | "conf" => ConfigParser.Format.Hocon
            case _                => ConfigParser.Format.Yaml
          // Collect lines until closing ```
          val body    = scala.collection.mutable.ListBuffer.empty[String]
          i += 1
          var closed  = false
          while i < lines.size && !closed do
            if CloseFence.matches(lines(i)) then
              closed = true
            else
              body += lines(i)
            i += 1
          result += RawBlock(name, body.mkString("\n"), fmt)
        case None =>
          i += 1

    result.toList

  /** Parse all extracted blocks into `FencedConfigBlock` records. */
  def extractAndParse(source: String): List[FencedConfigBlock] =
    extract(source).map(b => FencedConfigBlock(b.name, b.content, b.format))
