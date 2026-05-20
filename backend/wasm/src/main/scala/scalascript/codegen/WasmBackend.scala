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
 *  v1.27 Phase 5 — when the module also has `sql` blocks, the result
 *  additionally includes:
 *    - `Segment.Asset("sql-runtime.mjs", bytes, "application/javascript")`
 *      — the shared sql-runtime JS source from `backend-sql-runtime-js`.
 *    - `Segment.Asset("sql-registry.mjs", bytes, "application/javascript")`
 *      — per-module registry-init derived from `manifest.databases`.
 *    - `Segment.Asset("package.json", bytes, "application/json")`
 *      — ready-to-`npm install` deps gated on referenced providers
 *      (`sql.js`, `@duckdb/duckdb-wasm`, `web-worker`).
 *  Wasm body itself is unaffected by sql blocks.  When the module has
 *  sql blocks but no scala blocks, the Wasm-related assets are omitted
 *  but the sql assets still ship so the JS shim can execute the
 *  emitted bundle under Node.
 *
 *  When the module has neither scala nor sql blocks the result is
 *  `Segmented(Nil)`.
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
    val hasScala  = WasmGen.hasBlocks(astModule)
    val hasSql    = hasSqlBlock(module)
    if !hasScala && !hasSql then
      return CompileResult.Segmented(Nil)
    val segments = List.newBuilder[Segment]
    if hasScala then
      try
        val bundle = WasmGen.compileToWasm(astModule, baseDir)
        if bundle.wasmBytes.nonEmpty then
          segments += Segment.Asset("module.wasm", bundle.wasmBytes, "application/wasm")
        if bundle.mainJs.nonEmpty then
          segments += Segment.Code("javascript", bundle.mainJs)
        if bundle.loaderJs.nonEmpty then
          segments += Segment.Asset("__loader.js", bundle.loaderJs.getBytes("UTF-8"), "application/javascript")
      catch case e: Exception =>
        return CompileResult.Failed(List(Diagnostic.Generic(e.getMessage)))
    if hasSql then
      segments ++= emitJsShim(module)
    CompileResult.Segmented(segments.result())

  /** v1.27 Phase 5 — assets that piggyback on the wasm bundle's JS shim
   *  when the module has any sql block: the shared `sql-runtime.mjs`
   *  source, a per-module registry-init, and a `package.json` listing
   *  the npm deps for actually-referenced providers.  Mirrors
   *  `NodeBackend.emitPackageJson` for the deps; mirrors `JsGen`'s
   *  preamble emit for runtime + registry. */
  private def emitJsShim(module: ir.NormalizedModule): List[Segment] =
    import scalascript.sql.js.SqlRuntimeJsEmit
    val out = List.newBuilder[Segment]
    val runtimeBytes  = SqlRuntimeJsEmit.runtimeSource.getBytes("UTF-8")
    out += Segment.Asset("sql-runtime.mjs", runtimeBytes, "application/javascript")
    val entries = module.manifest.toList.flatMap(_.databases).map { d =>
      SqlRuntimeJsEmit.DatabaseEntry(
        name = d.name, url = d.url, user = d.user, password = d.password, driver = d.driver
      )
    }
    val registryBytes = SqlRuntimeJsEmit.emitRegistryInit(entries).getBytes("UTF-8")
    out += Segment.Asset("sql-registry.mjs", registryBytes, "application/javascript")
    val pkgBytes = emitPackageJson(module).getBytes("UTF-8")
    out += Segment.Asset("package.json", pkgBytes, "application/json")
    out.result()

  /** Render `package.json` content with deps gated on referenced
   *  providers.  Mirrors `NodeBackend.emitPackageJson` shape so a
   *  cross-backend `ssc compile` produces consistent dep sets.  When
   *  the module has sql blocks but no `databases:`, ships every
   *  provider so the `@sscBrowserSqlConnection` annotation override
   *  path (Phase 6) can still resolve. */
  private def emitPackageJson(module: ir.NormalizedModule): String =
    import scalascript.sql.js.ProviderId
    val refs = referencedProviders(module)
    val pinned = scala.collection.mutable.LinkedHashMap.empty[String, String]
    if refs.contains(ProviderId.SqlJs) then
      pinned += ProviderId.SqlJs.npmPackage -> ProviderId.SqlJs.npmVersionRange
    if refs.contains(ProviderId.DuckDbWasm) then
      pinned += ProviderId.DuckDbWasm.npmPackage -> ProviderId.DuckDbWasm.npmVersionRange
      // DuckDB-Wasm's Node code path needs `web-worker` over
      // `node:worker_threads` (sql-runtime.mjs imports it explicitly).
      pinned += "web-worker" -> "^1.5.0"
    val depsJson =
      if pinned.isEmpty then "{}"
      else pinned.map { case (k, v) => s"""    "$k": "$v"""" }.mkString("{\n", ",\n", "\n  }")
    s"""{
       |  "name": "scalascript-wasm-module",
       |  "version": "0.0.0",
       |  "private": true,
       |  "type": "module",
       |  "dependencies": $depsJson
       |}
       |""".stripMargin

  /** Determine which providers the module references — same logic as
   *  `NodeBackend.referencedProviders`. */
  private def referencedProviders(module: ir.NormalizedModule): Set[scalascript.sql.js.ProviderId] =
    import scalascript.sql.js.ProviderId
    val declared = module.manifest.toList.flatMap(_.databases)
    if declared.isEmpty then ProviderId.all
    else
      declared.iterator.flatMap { d =>
        ProviderId.fromUrl(d.url).toOption.toList
      }.toSet

  /** True when the IR has at least one `SqlBlock` content node. */
  private def hasSqlBlock(module: ir.NormalizedModule): Boolean =
    def walkContent(c: ir.Content): Boolean = c match
      case _: ir.Content.SqlBlock => true
      case _                      => false
    def walkSection(s: ir.Section): Boolean =
      s.content.exists(walkContent) || s.subsections.exists(walkSection)
    module.sections.exists(walkSection)
