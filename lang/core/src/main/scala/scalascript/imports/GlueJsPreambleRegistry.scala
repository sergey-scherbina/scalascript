package scalascript.imports

import scala.collection.concurrent.TrieMap

/** Tracks `js/glue.js` content strings extracted from `.ssclib` archives.
 *
 *  JS codegen prepends every registered preamble before the generated
 *  user code so that glue helpers defined in library-shipped JS are
 *  available at runtime.
 *
 *  Populated by `ImportResolver.extractSsclib` when it detects a
 *  `glue.js:` entry in `ssclib-manifest.yaml`.
 *
 *  See `docs/arch-ffi.md §6 Phase 4`. */
object GlueJsPreambleRegistry:

  private val _preambles = TrieMap.empty[String, String]  // key = dep URI, value = JS content

  def addPreamble(key: String, content: String): Unit = _preambles(key) = content

  def preambles: List[String] = _preambles.values.toList

  def isEmpty: Boolean = _preambles.isEmpty

  def contains(key: String): Boolean = _preambles.contains(key)

  def clear(): Unit = _preambles.clear()
