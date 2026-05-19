package scalascript.codegen

import scalascript.ast.*

/** Compiles `scala` blocks to a Scala.js WASM bundle via
 *  `scala-cli --power package --js --js-module-kind es --js-emit-wasm`.
 *
 *  scala-cli writes a directory with three artefacts:
 *    main.wasm      — the WebAssembly binary
 *    main.js        — ES-module entry point (imports __loader.js + main.wasm)
 *    __loader.js    — WASM loader / runtime glue
 *
 *  All three are returned so the CLI / server can write them together.
 */
object WasmGen:

  case class WasmBundle(
    wasmBytes:  Array[Byte],
    mainJs:     String,
    loaderJs:   String,
  )

  def hasBlocks(module: Module): Boolean =
    module.sections.exists(sectionHas)

  private def sectionHas(s: Section): Boolean =
    s.content.exists {
      case cb: Content.CodeBlock => Lang.isStandardScala(cb.lang)
      case _                     => false
    } || s.subsections.exists(sectionHas)

  def collectSource(module: Module): String =
    val sb = StringBuilder()
    module.sections.foreach(collectSection(_, sb))
    sb.toString

  private def collectSection(s: Section, sb: StringBuilder): Unit =
    s.content.foreach {
      case Content.CodeBlock(lang, src, _, _, _, _) if Lang.isStandardScala(lang) =>
        sb.append(src.stripTrailing()).append("\n\n")
      case _ => ()
    }
    s.subsections.foreach(collectSection(_, sb))

  /** Compile all `scala` blocks to a WASM bundle.
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
