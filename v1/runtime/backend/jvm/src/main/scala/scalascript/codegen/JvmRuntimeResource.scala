package scalascript.codegen

import java.util.concurrent.ConcurrentHashMap

/** Loads a JVM runtime-source fragment from a classpath resource under `/scalascript/jvm-runtime/`,
 *  cached, applying `.stripMargin` (the fragments are margin-formatted Scala source templates).
 *
 *  Mirrors [[JsRuntimeResource]] for the JVM backend (`specs/js-runtime-resources.md`): the large
 *  emitted-Scala runtime templates move out of `JvmGenRuntimeSources` string constants into real
 *  files, while the `val X: String` API is unchanged. The resource holds the verbatim `|`-margined
 *  body and the loader applies `.stripMargin`, so the result is **byte-identical** to the former
 *  `"""…""".stripMargin` literal by construction (same input, same `stripMargin`). */
object JvmRuntimeResource:
  private val cache = new ConcurrentHashMap[String, String]()

  /** The `/scalascript/jvm-runtime/<name>` resource (UTF-8), `.stripMargin`-applied + cached. */
  def load(name: String): String =
    cache.computeIfAbsent(name, n => read(s"/scalascript/jvm-runtime/$n").stripMargin)

  private def read(path: String): String =
    val in = getClass.getResourceAsStream(path)
    if in == null then
      throw new IllegalStateException(s"JVM runtime resource not found on classpath: $path")
    try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally in.close()
