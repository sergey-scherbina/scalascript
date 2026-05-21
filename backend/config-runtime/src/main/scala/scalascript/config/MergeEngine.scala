package scalascript.config

/** Priority levels for config sources (highest to lowest in default order). */
enum Priority:
  case Blocks      // fenced config blocks — highest by default
  case Files       // external config files
  case Frontmatter // YAML front-matter — lowest by default

object Priority:
  val DefaultOrder: List[Priority] = List(Blocks, Files, Frontmatter)

/** Merges multiple [[ConfigValue]] trees according to a priority order.
 *
 *  Sources earlier in the priority list WIN over later ones.
 *  Within the same priority tier, sources listed LATER in the input win
 *  (document order for blocks, file list order for external files). */
object MergeEngine:

  /** Merge sources in priority order. `sources` is a list of (priority, value) pairs.
   *  Returns a single merged ConfigValue. */
  def merge(
    sources: List[(Priority, ConfigValue)],
    order:   List[Priority] = Priority.DefaultOrder
  ): ConfigValue =
    // Build tiers in reverse-priority order (lowest first so higher-priority layers win)
    val reverseTiers = order.reverse
    val byTier = reverseTiers.flatMap(p => sources.collect { case (`p`, v) => v })
    byTier.foldLeft(ConfigValue.empty)(_.deepMerge(_))

  /** Convenience: merge frontmatter + external files + fenced blocks with default priority. */
  def mergeAll(
    frontmatter:  ConfigValue,
    externalFiles: List[ConfigValue],  // in listed order (later wins within tier)
    blocks:       List[ConfigValue],   // in document order (later wins within tier)
    order:        List[Priority] = Priority.DefaultOrder
  ): ConfigValue =
    val sources: List[(Priority, ConfigValue)] =
      List((Priority.Frontmatter, frontmatter)) ++
      externalFiles.map(Priority.Files -> _) ++
      blocks.map(Priority.Blocks -> _)
    merge(sources, order)
