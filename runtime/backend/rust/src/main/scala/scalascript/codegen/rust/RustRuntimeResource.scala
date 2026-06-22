package scalascript.codegen.rust

import java.util.concurrent.ConcurrentHashMap

/** Loads a Rust runtime-source template from a classpath resource under `/scalascript/rust-runtime/`,
 *  cached, applying `.stripMargin` (the templates are margin-formatted Rust source).
 *
 *  Mirrors `JsRuntimeResource` / `JvmRuntimeResource` for the Rust backend
 *  (`specs/js-runtime-resources.md`): the large emitted-Rust runtime templates move out of
 *  `RustRuntimeTemplates` string constants into real `.rs` files, while the `val X: String` API is
 *  unchanged. The resource holds the verbatim `|`-margined body and the loader applies `.stripMargin`,
 *  so the result is **byte-identical** to the former `"""…""".stripMargin` literal by construction. */
object RustRuntimeResource:
  private val cache = new ConcurrentHashMap[String, String]()

  /** The `/scalascript/rust-runtime/<name>` resource (UTF-8), `.stripMargin`-applied + cached. */
  def load(name: String): String =
    cache.computeIfAbsent(name, n => read(s"/scalascript/rust-runtime/$n").stripMargin)

  private def read(path: String): String =
    val in = getClass.getResourceAsStream(path)
    if in == null then
      throw new IllegalStateException(s"Rust runtime resource not found on classpath: $path")
    try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally in.close()
