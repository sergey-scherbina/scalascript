package scalascript.codegen

import java.util.concurrent.ConcurrentHashMap

/** Loads a JS runtime fragment from a classpath resource under `/scalascript/js-runtime/`, cached.
 *
 *  Lets the JS backend's runtime helper code live in real `.mjs` files — lintable, `node --check`-
 *  able, editor-friendly — instead of large Scala string constants, while keeping the public
 *  `val X: String` API unchanged so no call site is affected and the emitted JS stays byte-identical.
 *  See `specs/js-runtime-resources.md`. */
object JsRuntimeResource:
  private val cache = new ConcurrentHashMap[String, String]()

  /** The verbatim UTF-8 text of `/scalascript/js-runtime/<name>`. Cached; throws loudly if absent. */
  def load(name: String): String =
    cache.computeIfAbsent(name, n => read(s"/scalascript/js-runtime/$n"))

  private def read(path: String): String =
    val in = getClass.getResourceAsStream(path)
    if in == null then
      throw new IllegalStateException(s"JS runtime resource not found on classpath: $path")
    try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally in.close()
