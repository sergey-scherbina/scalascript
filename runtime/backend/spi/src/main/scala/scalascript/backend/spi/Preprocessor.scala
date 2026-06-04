package scalascript.backend.spi

/** A source-to-source transformation applied before the ScalaScript → Scala
 *  parse step.
 *
 *  Preprocessors are `String => String` functions on the raw code-block body.
 *  They run in ascending `priority` order; lower priority = earlier.
 *
 *  Built-in preprocessors registered by `Parser` at class-load time use
 *  priorities 10–60.  Plugin-provided preprocessors should use 100+.
 *
 *  See `docs/specs/arch-dsl-hooks.md §4c` and `scalascript.parser.PreprocessorRegistry`. */
trait Preprocessor:
  /** Unique name, used for diagnostics and deduplication. */
  def name: String

  /** Execution order — lower number runs first. Built-ins use 10/20/30/40/50/60. */
  def priority: Int = 100

  /** Transform `source` and return the result. */
  def apply(source: String): String
