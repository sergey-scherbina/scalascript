package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir
import scalascript.transform.Denormalize

/** Backend SPI adapter for the JvmGen code generator.
 *
 *  Stage 5.1: thin shim — delegates to `JvmGen.generate(astModule, …)`
 *  after reconstructing the AST view from IR via `Denormalize`.
 *
 *  Stage 5.4+ will populate `intrinsics` so platform calls (`route`,
 *  `serve`, `hashPassword`, …) flow through `Backend.intrinsics`
 *  instead of being hardcoded in JvmGen. */
class JvmBackend extends Backend:
  def id:              String                          = "jvm"
  def displayName:     String                          = "JVM (Scala 3 source)"
  def spiVersion:      String                          = SpiVersion.Current
  def capabilities:    Capabilities                    = JvmCapabilities
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                     = Set("scala", "html", "css")

  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    val astModule = Denormalize(module)
    val baseDir   = opts.baseDir.map(p => os.Path(p.toAbsolutePath.toString))
    val code      = JvmGen.generate(astModule, baseDir)
    CompileResult.TextOutput(code = code, language = "scala", sources = Nil)
