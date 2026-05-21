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
    val preamble  = if runtimePreamble.isEmpty then "" else runtimePreamble + "\n"
    // Stage 5+/A.5 — intrinsics flow through to JsGen for per-call-site
    // dispatch in `genExpr`.  Stage 5+/A.6 (Б-2) — intrinsic-shipped
    // runtime helpers via Backend.runtimePreamble prepend before
    // JsGen's output.
    opts.extra.getOrElse("mode", "oneshot") match
      case "segmented" =>
        val segments = JsGen.generateSegmented(astModule, baseDir, intrinsics).map {
          case JsGen.Segment.ScalaScriptJs(code) =>
            Segment.Code(language = "javascript", code = preamble + code)
          case JsGen.Segment.ScalaSource(src)    =>
            Segment.Source(language = "scala", source = src)
        }
        CompileResult.Segmented(segments)
      case _ =>
        val code    = preamble + JsGen.generate(astModule, baseDir, intrinsics)
        val sources = emitPackageJson(module)
        CompileResult.TextOutput(code = code, language = "javascript", sources = sources)

  private def emitPackageJson(module: ir.NormalizedModule): List[SourceArtifact] =
    if !hasSqlBlock(module) then Nil
    else
      import scalascript.sql.js.ProviderId
      val declared = module.manifest.toList.flatMap(_.databases)
      val refs =
        if declared.isEmpty then ProviderId.all
        else declared.iterator.flatMap(d => ProviderId.fromUrl(d.url).toOption).toSet
      val pinned = scala.collection.mutable.LinkedHashMap.empty[String, String]
      if refs.contains(ProviderId.SqlJs)      then pinned += ProviderId.SqlJs.npmPackage      -> ProviderId.SqlJs.npmVersionRange
      if refs.contains(ProviderId.SqliteWasm) then pinned += ProviderId.SqliteWasm.npmPackage -> ProviderId.SqliteWasm.npmVersionRange
      if refs.contains(ProviderId.DuckDbWasm) then
        pinned += ProviderId.DuckDbWasm.npmPackage -> ProviderId.DuckDbWasm.npmVersionRange
        pinned += "web-worker" -> "^1.5.0"
      val depsJson =
        if pinned.isEmpty then "{}"
        else pinned.map { case (k, v) => s"""    "$k": "$v"""" }.mkString("{\n", ",\n", "\n  }")
      val pkg =
        s"""{
           |  "name": "scalascript-module",
           |  "version": "0.0.0",
           |  "private": true,
           |  "main": "main.cjs",
           |  "dependencies": $depsJson
           |}
           |""".stripMargin
      List(SourceArtifact("package.json", pkg))

  private def hasSqlBlock(module: ir.NormalizedModule): Boolean =
    def walk(s: ir.Section): Boolean =
      s.content.exists(_ match { case _: ir.Content.SqlBlock => true; case _ => false }) ||
      s.subsections.exists(walk)
    module.sections.exists(walk)
