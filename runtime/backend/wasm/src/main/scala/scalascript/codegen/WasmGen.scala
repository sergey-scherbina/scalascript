package scalascript.codegen

import scalascript.ast.*

/** Compiles `scala` and `scalascript` blocks to a Scala.js WASM bundle via
 *  `scala-cli --power package --js --js-module-kind es --js-emit-wasm`.
 *
 *  scala-cli writes a directory with three artefacts:
 *    main.wasm      — the WebAssembly binary
 *    main.js        — ES-module entry point (imports __loader.js + main.wasm)
 *    __loader.js    — WASM loader / runtime glue
 *
 *  All three are returned so the CLI / server can write them together.
 *
 *  Phase 1 note: `scalascript` blocks are treated as Scala 3 source and
 *  compiled as-is.  Blocks must contain valid Scala.js code; ScalaScript
 *  extensions (algebraic effects, handlers) are not yet transpiled.  An
 *  `@main def main(): Unit` entry point is required for executable bundles.
 */
object WasmGen:

  case class WasmBundle(
    wasmBytes:  Array[Byte],
    mainJs:     String,
    loaderJs:   String,
  )

  private def isCompilable(lang: String): Boolean =
    Lang.isStandardScala(lang) || Lang.isScalaScript(lang)

  def hasBlocks(module: Module): Boolean =
    module.sections.exists(sectionHas)

  private def sectionHas(s: Section): Boolean =
    s.content.exists {
      case cb: Content.CodeBlock => isCompilable(cb.lang)
      case _                     => false
    } || s.subsections.exists(sectionHas)

  /** Collect all compilable block sources, hoisting `//> using` directives
   *  to the top of the output so scala-cli sees them before any code.
   *  This lets users embed `//> using dep "org.scala-js::scalajs-dom::2.8.0"`
   *  (or similar) inside their `scalascript` / `scala` blocks.
   */
  def collectSource(module: Module): String =
    val directives = List.newBuilder[String]  // //> using … lines, deduped
    val body       = StringBuilder()
    val seen       = scala.collection.mutable.LinkedHashSet.empty[String]
    module.sections.foreach(collectSection(_, directives, seen, body))
    val dirs = directives.result()
    if dirs.isEmpty then body.toString
    else dirs.mkString("", "\n", "\n\n") + body.toString

  private def collectSection(
    s:          Section,
    directives: scala.collection.mutable.Builder[String, List[String]],
    seen:       scala.collection.mutable.Set[String],
    body:       StringBuilder,
  ): Unit =
    s.content.foreach {
      case Content.CodeBlock(lang, src, _, _, _, _, _) if isCompilable(lang) =>
        val (dirs, rest) = src.linesIterator.partition(_.startsWith("//>"))
        dirs.foreach { d =>
          val trimmed = d.trim
          if seen.add(trimmed) then directives += trimmed
        }
        val restStr = rest.mkString("\n").stripTrailing()
        if restStr.nonEmpty then body.append(restStr).append("\n\n")
      case _ => ()
    }
    s.subsections.foreach(collectSection(_, directives, seen, body))

  /** Compile all `scala` and `scalascript` blocks to a WASM bundle.
   *  Throws `RuntimeException` on compilation failure.
   */
  def compileToWasm(module: Module, baseDir: Option[os.Path] = None): WasmBundle =
    val src = collectSource(module)
    if src.isBlank then return WasmBundle(Array.emptyByteArray, "", "")
    compileSourceToWasm(src, baseDir)

  def compileSourceToWasm(source: String, baseDir: Option[os.Path] = None): WasmBundle =
    if source.isBlank then return WasmBundle(Array.emptyByteArray, "", "")

    // scala-cli requires .scala (not .sc) for @main with WASM output
    val tmp    = os.temp(source, suffix = ".scala", deleteOnExit = true)
    val outDir = os.temp.dir(deleteOnExit = true)
    val name   = "module"
    try
      val result = os.proc(
        "scala-cli", "--power", "package",
        "--js",
        "--js-module-kind", "es",
        "--js-emit-wasm",
        "--force",
        "-o", (outDir / name).toString,
        tmp
      ).call(
        cwd    = baseDir.getOrElse(os.pwd),
        check  = false,
        stderr = os.Pipe
      )
      if result.exitCode != 0 then
        throw RuntimeException(
          s"Scala.js WASM compilation failed (exit ${result.exitCode}):\n${result.err.text()}"
        )

      val bundleDir = outDir / name
      val wasmPath  = bundleDir / "main.wasm"
      val jsPath    = bundleDir / "main.js"
      val loaderPath = bundleDir / "__loader.js"

      if !os.exists(wasmPath) then
        throw RuntimeException(
          s"WASM output not found at $wasmPath — files: ${os.list(bundleDir).map(_.last).mkString(", ")}"
        )

      WasmBundle(
        wasmBytes = os.read.bytes(wasmPath),
        mainJs    = if os.exists(jsPath)    then os.read(jsPath)    else "",
        loaderJs  = if os.exists(loaderPath) then os.read(loaderPath) else "",
      )
    finally
      if os.exists(tmp) then os.remove(tmp)
