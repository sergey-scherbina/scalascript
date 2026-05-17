package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir
import scalascript.transform.Denormalize

/** Backend SPI adapter for the Scala.js code generator (target id
 *  `"scalajs-spa"`).  Compiles `scala` blocks via `scala-cli --js`
 *  into a self-contained Node.js script.
 *
 *  Stage 5.1: delegates to the existing `ScalaJsBackend.compileToJs`. */
class ScalaJsPluginBackend extends Backend:
  def id:              String                              = "scalajs-spa"
  def displayName:     String                              = "Scala.js SPA bundle"
  def spiVersion:      String                              = SpiVersion.Current
  def capabilities:    Capabilities                        = ScalaJsCapabilities
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                         = Set("scala", "html", "css")

  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    val astModule = Denormalize(module)
    val baseDir   = opts.baseDir.map(p => os.Path(p.toAbsolutePath.toString))
    val code      = ScalaJsBackend.compileToJs(astModule, baseDir)
    CompileResult.TextOutput(code = code, language = "javascript", sources = Nil)
