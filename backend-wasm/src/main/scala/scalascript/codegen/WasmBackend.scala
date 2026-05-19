package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir
import scalascript.transform.Denormalize

/** Backend SPI adapter for the Scala.js WASM code generator (target id `"wasm"`).
 *  Compiles `scala` blocks via `scala-cli --power package --js --js-module-kind es --js-emit-wasm`
 *  into a WebAssembly binary + two JavaScript ES-module files.
 *
 *  Returns `Segmented` with:
 *    - `Segment.Asset("module.wasm", bytes, "application/wasm")` — the WASM binary
 *    - `Segment.Code("javascript", mainJs)`                      — ES-module entry point
 *    - `Segment.Code("javascript", loaderJs)`                    — WASM loader / runtime glue
 *
 *  When the module has no `scala` blocks the result is `Segmented(Nil)`.
 */
class WasmBackend extends Backend:
  def id:              String                               = "wasm"
  def displayName:     String                               = "WebAssembly (Scala.js)"
  def spiVersion:      String                               = SpiVersion.Current
  def capabilities:    Capabilities                         = WasmCapabilities
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                          = Set("scala")

  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    val astModule = Denormalize(module)
    val baseDir   = opts.baseDir.map(p => os.Path(p.toAbsolutePath.toString))
    if !WasmGen.hasBlocks(astModule) then
      return CompileResult.Segmented(Nil)
    try
      val bundle = WasmGen.compileToWasm(astModule, baseDir)
      val segments = List.newBuilder[Segment]
      if bundle.wasmBytes.nonEmpty then
        segments += Segment.Asset("module.wasm", bundle.wasmBytes, "application/wasm")
      if bundle.mainJs.nonEmpty then
        segments += Segment.Code("javascript", bundle.mainJs)
      if bundle.loaderJs.nonEmpty then
        segments += Segment.Asset("__loader.js", bundle.loaderJs.getBytes("UTF-8"), "application/javascript")
      CompileResult.Segmented(segments.result())
    catch case e: Exception =>
      CompileResult.Failed(List(Diagnostic.Generic(e.getMessage)))
