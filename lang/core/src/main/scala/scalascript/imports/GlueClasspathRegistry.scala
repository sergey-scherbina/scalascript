package scalascript.imports

import scala.collection.concurrent.TrieMap

/** Tracks `jvm/glue.jar` paths that have been unpacked from `.ssclib`
 *  archives and wired into the JVM classpath.
 *
 *  Backends (JvmGen, the interpreter) can query `jars` to discover glue
 *  archives added by the current set of resolved dependencies.  The
 *  registry is populated eagerly by `ImportResolver.extractSsclib` when it
 *  detects a `glue.jvm:` entry in `ssclib-manifest.yaml`.
 *
 *  See `docs/arch-ffi.md §6 Phase 3`. */
object GlueClasspathRegistry:

  private val _jars = TrieMap.empty[String, os.Path]   // key = canonical path string

  def addJar(path: os.Path): Unit = _jars(path.toString) = path

  def jars: List[os.Path] = _jars.values.toList

  def contains(path: os.Path): Boolean = _jars.contains(path.toString)

  def clear(): Unit = _jars.clear()
