package scalascript.codegen

import scalascript.ast.*

/** Compiles `scala` blocks to a Scala.js WASM bundle via
 *  `scala-cli --power package --js --wasm`.
 *
 *  Scala.js 1.17+ emits two artefacts for a WASM target:
 *    <name>.wasm  — the WebAssembly binary
 *    <name>.js    — the ES-module glue that loads and instantiates the WASM
 *
 *  Both are returned to the caller so the CLI / server can handle them
 *  together (write to disk, embed in an HTML page, etc.).
 */
object WasmGen:

  case class WasmBundle(wasmBytes: Array[Byte], jsGlue: String)

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
      case Content.CodeBlock(lang, src, _, _, _) if Lang.isStandardScala(lang) =>
        sb.append(src.stripTrailing()).append("\n\n")
      case _ => ()
    }
    s.subsections.foreach(collectSection(_, sb))

  /** Compile all `scala` blocks to a WASM bundle.
   *  Returns `WasmBundle(wasmBytes, jsGlue)` on success.
   *  Throws `RuntimeException` on compilation failure or missing WASM support.
   */
  def compileToWasm(module: Module, baseDir: Option[os.Path] = None): WasmBundle =
    val src = collectSource(module)
    if src.isBlank then return WasmBundle(Array.emptyByteArray, "")
    compileSourceToWasm(src, baseDir)

  def compileSourceToWasm(source: String, baseDir: Option[os.Path] = None): WasmBundle =
    if source.isBlank then return WasmBundle(Array.emptyByteArray, "")

    val tmp    = os.temp(source, suffix = ".sc", deleteOnExit = true)
    val outDir = os.temp.dir(deleteOnExit = true)
    val name   = "module"
    try
      val result = os.proc(
        "scala-cli", "--power", "package",
        "--js",
        "--js-wasm",
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

      val wasmPath = outDir / s"$name.wasm"
      val jsPath   = outDir / s"$name.js"

      if !os.exists(wasmPath) then
        throw RuntimeException(
          s"WASM output not found at $wasmPath — " +
          s"ensure scala-cli and Scala.js support --js-wasm (Scala.js 1.17+)"
        )

      val wasmBytes = os.read.bytes(wasmPath)
      val jsGlue    = if os.exists(jsPath) then os.read(jsPath) else ""
      WasmBundle(wasmBytes, jsGlue)
    finally
      if os.exists(tmp) then os.remove(tmp)
