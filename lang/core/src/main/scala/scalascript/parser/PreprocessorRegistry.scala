package scalascript.parser

import scalascript.backend.spi.Preprocessor
import scala.collection.concurrent.TrieMap

/** Registry for `Preprocessor` instances.
 *
 *  Built-in preprocessors are registered at object-init time with priorities
 *  10–60, in the same order as the old hard-coded `preprocessForScala` chain.
 *  Plugin-provided preprocessors register via `Backend.preprocessors` (loaded
 *  through `BackendRegistry`) using higher priorities (100+).
 *
 *  See `docs/arch-dsl-hooks.md §4c — PreprocessorRegistry`. */
object PreprocessorRegistry:

  private[parser] val registry: TrieMap[String, Preprocessor] = TrieMap.empty

  def register(p: Preprocessor): Unit = registry(p.name) = p

  def lookup(name: String): Option[Preprocessor] = registry.get(name)

  /** All registered preprocessors sorted by priority (ascending). */
  def all: List[Preprocessor] =
    registry.values.toList.sortBy(p => (p.priority, p.name))

  /** Apply all registered preprocessors in priority order to `source`. */
  def applyAll(source: String): String =
    all.foldLeft(source)((s, p) => p.apply(s))

  /** Register all preprocessors from an already-loaded backend. */
  def registerFrom(b: scalascript.backend.spi.Backend): Unit =
    b.preprocessors.foreach(register)

  // ── Built-in preprocessor registrations ──────────────────────────────────
  // These delegate to the existing private[parser] methods in Parser so the
  // implementations remain co-located with the Parser logic while becoming
  // extensible through the registry.
  // Priority 10–60 reserves the low end for the core pipeline; plugins use 100+.

  locally {
    register(new Preprocessor {
      override val name     = "inline-imports"
      override val priority = 10
      override def apply(s: String) = Parser.preprocessInlineImports(s)
    })
    register(new Preprocessor {
      override val name     = "numeric-literals"
      override val priority = 15
      override def apply(s: String) = Parser.preprocessNumericLiterals(s)
    })
    register(new Preprocessor {
      override val name     = "list-literals"
      override val priority = 20
      override def apply(s: String) = Parser.preprocessListLiterals(s)
    })
    register(new Preprocessor {
      override val name     = "slash-imports"
      override val priority = 30
      override def apply(s: String) = Parser.preprocessSlashImports(s)
    })
    register(new Preprocessor {
      override val name     = "remote-defs"
      override val priority = 40
      override def apply(s: String) = Parser.preprocessRemoteDefs(s)
    })
    register(new Preprocessor {
      override val name     = "openapi-annotations"
      override val priority = 45
      override def apply(s: String) = Parser.preprocessOpenApiAnnotations(s)
    })
    register(new Preprocessor {
      override val name     = "effects"
      override val priority = 50
      override def apply(s: String) = Parser.preprocessEffects(s)
    })
    register(new Preprocessor {
      override val name     = "quoted-macros"
      override val priority = 55
      override def apply(s: String) = Parser.preprocessQuotedMacros(s)
    })
    register(new Preprocessor {
      override val name     = "extern"
      override val priority = 60
      override def apply(s: String) = Parser.preprocessExtern(s)
    })
  }
