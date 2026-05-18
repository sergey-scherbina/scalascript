package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir
import scalascript.transform.Denormalize

/** Backend SPI adapter for the JsGen code generator (target id `"js"`).
 *
 *  Stage 5.1: thin shim around `JsGen.generate` (one-shot text) and
 *  `JsGen.generateSegmented` (SPA bundle).  Choice between the two
 *  modes is driven by `BackendOptions.extra("mode")` — defaults to
 *  one-shot.  Stage 5.4+ replaces the hardcoded SPA wiring with
 *  intrinsic-table dispatch. */
class JsBackend extends Backend:
  def id:              String                              = "js"
  def displayName:     String                              = "JavaScript (Node / SPA)"
  def spiVersion:      String                              = SpiVersion.Current
  def capabilities:    Capabilities                        = JsCapabilities
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = JsIntrinsics
  def acceptedSources: Set[String]                         = Set("html", "css")

  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    val astModule = Denormalize(module)
    val baseDir   = opts.baseDir.map(p => os.Path(p.toAbsolutePath.toString))
    // Stage 5+/A.5 — intrinsics flow through to JsGen for per-call-site
    // dispatch in `genExpr`.  The Stage 5+/A.3 `intrinsicPrelude`
    // (prepended `const` aliases) is gone — per-call-site is the
    // proper home for both `RuntimeCall` and `InlineCode` variants.
    opts.extra.getOrElse("mode", "oneshot") match
      case "segmented" =>
        val segments = JsGen.generateSegmented(astModule, baseDir, intrinsics).map {
          case JsGen.Segment.ScalaScriptJs(code) =>
            Segment.Code(language = "javascript", code = code)
          case JsGen.Segment.ScalaSource(src)    =>
            Segment.Source(language = "scala", source = src)
        }
        CompileResult.Segmented(segments)
      case _ =>
        val code = JsGen.generate(astModule, baseDir, intrinsics)
        CompileResult.TextOutput(code = code, language = "javascript", sources = Nil)
