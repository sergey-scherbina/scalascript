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
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = JvmIntrinsics
  def acceptedSources: Set[String]                     = Set("scala", "html", "css")

  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    val astModule = Denormalize(module)
    val baseDir   = opts.baseDir.map(p => os.Path(p.toAbsolutePath.toString))
    val code      = intrinsicPrelude + JvmGen.generate(astModule, baseDir)
    CompileResult.TextOutput(code = code, language = "scala", sources = Nil)

  /** Stage 5+/A.3 — surface every `RuntimeCall` intrinsic as a Scala
   *  `def` injected before the JvmGen-emitted script.  The Scala
   *  compiler then resolves the user-side call site to this def.
   *
   *  Crude but effective: a per-call-site intrinsic-table consult
   *  during emit (the spec's eventual design) needs JvmGen surgery;
   *  pre-prepending the runtime alias is the minimum-surface
   *  demonstration that the SPI's `intrinsics` map drives a compiled
   *  backend's behaviour. */
  private def intrinsicPrelude: String =
    intrinsics.collect {
      case (qn, RuntimeCall(target)) =>
        s"def ${qn.value}() = $target()"
    }.mkString("", "\n", if intrinsics.nonEmpty then "\n" else "")
