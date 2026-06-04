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
 *  See `docs/specs/arch-dsl-hooks.md §4c — PreprocessorRegistry`. */
object PreprocessorRegistry:

  private[parser] val registry: TrieMap[String, Preprocessor] = TrieMap.empty

  // Cache the priority-sorted view; `applyAll` runs per code-block (hot path),
  // so re-sorting the registry on every call is pure waste. Invalidated on
  // every `register` (registration happens at init / plugin-load, not on the
  // hot path).
  @volatile private var sortedCache: Array[Preprocessor] = Array.empty

  def register(p: Preprocessor): Unit =
    registry(p.name) = p
    sortedCache = registry.values.toArray.sortBy(x => (x.priority, x.name))

  def deregister(name: String): Unit =
    registry.remove(name)
    sortedCache = registry.values.toArray.sortBy(x => (x.priority, x.name))

  def lookup(name: String): Option[Preprocessor] = registry.get(name)

  /** All registered preprocessors sorted by priority (ascending). */
  def all: List[Preprocessor] =
    registry.values.toList.sortBy(p => (p.priority, p.name))

  /** Apply all registered preprocessors in priority order to `source`. */
  def applyAll(source: String): String =
    val ps = sortedCache
    var s = source
    var i = 0
    while i < ps.length do { s = ps(i).apply(s); i += 1 }
    s

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
      override val name     = "model-defs"
      override val priority = 42
      override def apply(s: String) = Parser.preprocessModelDefs(s)
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
