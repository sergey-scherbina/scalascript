package scalascript.codegen

import scalascript.ast
import scalascript.backend.spi.*
import scalascript.ir
import scalascript.transform.Denormalize

/** Backend SPI adapter for the Node.js target (target id `"node"`).
 *
 *  Pipeline:
 *
 *    1. Collect every `node.js` / `node` opaque-executable fenced
 *       block in document order — concatenated verbatim as the
 *       *glue prefix*.
 *    2. Run `JsGen.generate` over the rest (the `scalascript` /
 *       `scala` blocks via the existing JS code generator, with
 *       html / css / javascript string blocks rendered as values).
 *    3. Emit `<runtime preamble>\n<glue prefix>\n<jsgen output>` as
 *       a single `.mjs`-ready JavaScript source.
 *
 *  No JS parser is invoked on the glue blocks — they are linked, not
 *  compiled.  Phase 4 of v1.25 wires `extern def` declarations to
 *  `globalThis.<name>` so ScalaScript can call into JS-defined
 *  symbols; for Phase 3b the contract is purely "you can ship JS
 *  bytes alongside your ssc". */
class NodeBackend extends Backend:
  def id:              String                               = "node"
  def displayName:     String                               = "Node.js"
  def spiVersion:      String                               = SpiVersion.Current
  def capabilities:    Capabilities                         = NodeCapabilities
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = JsIntrinsics
  def acceptedSources: Set[String]                          = Set.empty

  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    val gluePrefix = collectNodeGlue(module)
    val astModule  = Denormalize(module)
    val baseDir    = opts.baseDir.map(p => os.Path(p.toAbsolutePath.toString))
    val caps       = JsGen.detectCapabilities(astModule, baseDir, intrinsics)
    val jsRuntime  = JsGen.generateRuntime(caps)
    val js         = JsGen.generate(astModule, baseDir, intrinsics)
    // v1.27 Phase 4 — when the module has sql blocks, JsGen wraps the
    // user body in an async IIFE.  The original `NodeFlushEpilogue`
    // runs synchronously *after* the IIFE is scheduled (not awaited),
    // so `println` calls inside the IIFE never reach stdout.  Switch to
    // write-through: every `_println` immediately writes a line to
    // stdout.  Buffered semantics are preserved for any external reader
    // (push still happens).  Applied unconditionally — the post-runtime
    // flush is no longer needed since lines write through eagerly.
    val parts      = List(jsRuntime, NodePrintlnWriteThrough, gluePrefix, js).filter(_.nonEmpty)
    val code       = parts.mkString("\n")
    val sources    = emitPackageJson(module) ++ emitNodeMain()
    CompileResult.TextOutput(code = code, language = "javascript", sources = sources)

  /** v1.27 Phase 4 — when the module has any sql block, ship a
   *  ready-to-`npm install` `package.json` alongside the .mjs.
   *  Deps are gated on actual provider references in the module's
   *  `databases:` front-matter: a module that only uses sqlite ships
   *  without the `@duckdb/duckdb-wasm` dep and vice versa.  When the
   *  module has sql blocks but no `databases:` (relying on
   *  `@sscBrowserSqlConnection` annotation override), ship both —
   *  the override path can connect to any provider. */
  private def emitPackageJson(module: ir.NormalizedModule): List[SourceArtifact] =
    if !hasSqlBlock(module) then Nil
    else
      val refs = referencedProviders(module)
      // Stable ordered list of npm deps + version pins.  Pin shape
      // mirrors `ProviderId.npmVersionRange`.
      import scalascript.sql.js.ProviderId
      val pinned = scala.collection.mutable.LinkedHashMap.empty[String, String]
      if refs.contains(ProviderId.SqlJs)      then pinned += ProviderId.SqlJs.npmPackage      -> ProviderId.SqlJs.npmVersionRange
      if refs.contains(ProviderId.DuckDbWasm) then
        pinned += ProviderId.DuckDbWasm.npmPackage -> ProviderId.DuckDbWasm.npmVersionRange
        // DuckDB-Wasm's Node code path needs `web-worker` over
        // `node:worker_threads` (sql-runtime.mjs imports it explicitly).
        pinned += "web-worker" -> "^1.5.0"
      val depsJson =
        if pinned.isEmpty then "{}"
        else pinned.map { case (k, v) => s"""    "$k": "$v"""" }.mkString("{\n", ",\n", "\n  }")
      // Bundle file extension: `.cjs`.  The JsRuntime preamble uses
      // CommonJS `require('fs')` / `require('http')` etc. — switching
      // to ESM would require rewriting the entire runtime.  Dynamic
      // `import('sql.js')` inside `sql-runtime.mjs` still works under
      // CJS (it's the *static* `import` statements that don't), so
      // sql blocks compose fine with the CJS runtime.
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

  /** Determine which providers the module references.  When the module
   *  has sql blocks but no `databases:` declaration, returns the full
   *  provider set so the emitted `package.json` lists every npm dep
   *  the runtime might lazily import. */
  private def referencedProviders(module: ir.NormalizedModule): Set[scalascript.sql.js.ProviderId] =
    import scalascript.sql.js.ProviderId
    val declared = module.manifest.toList.flatMap(_.databases)
    if declared.isEmpty then ProviderId.all
    else
      declared.iterator.flatMap { d =>
        ProviderId.fromUrl(d.url).toOption.toList
      }.toSet

  /** Companion `node:test`-runner entry stub.  Phase 4 only emits this
   *  when sql blocks are present — gives downstream tooling a
   *  predictable `main` script name to invoke without scanning the
   *  generated .mjs.  Just a thin re-export shim so `node main.mjs`
   *  works after `npm install`. */
  private def emitNodeMain(): List[SourceArtifact] = Nil   // reserved for future use

  /** True when the IR has at least one `SqlBlock` content node. */
  private def hasSqlBlock(module: ir.NormalizedModule): Boolean =
    def walkContent(c: ir.Content): Boolean = c match
      case _: ir.Content.SqlBlock => true
      case _                      => false
    def walkSection(s: ir.Section): Boolean =
      s.content.exists(walkContent) || s.subsections.exists(walkSection)
    module.sections.exists(walkSection)

  /** v1.27 Phase 4 — replace JsRuntime's buffered `_println` with a
   *  write-through that immediately hits `process.stdout`.  The
   *  buffer push is preserved (some user code may read `_output`
   *  directly), but the visible-to-user side effect happens now,
   *  not at end-of-script.
   *
   *  This matters when JsGen wraps user code in an async IIFE (sql
   *  blocks, `runAsyncParallel`): the original post-IIFE flush ran
   *  synchronously before the IIFE's async work completed, so
   *  `println` calls inside it were silently dropped.  Write-through
   *  is the universal fix — no special-casing needed. */
  private val NodePrintlnWriteThrough: String =
    """|// v1.27 — write-through println for the Node target.  Replaces
       |// JsRuntime's buffered _println so async/IIFE-wrapped user code
       |// reaches stdout promptly.  Rebinds `Console.println` too —
       |// the runtime captures `_println` by reference at init time so
       |// reassigning `_println` alone leaves CPS-emitted dispatches
       |// (`_dispatch(Console, 'println', …)` from actor bodies)
       |// pointing at the buffered original.
       |_println = function(s) {
       |  _output.push(s);
       |  if (typeof process !== 'undefined' && process.stdout) {
       |    process.stdout.write(s + '\n');
       |  }
       |};
       |if (typeof Console !== 'undefined') Console.println = _println;
       |""".stripMargin

  /** Walk the module's IR in document order and concatenate the
   *  source of every `node.js` / `node` `EmbeddedBlock`.  An empty
   *  string when no such block exists — the resulting bundle is
   *  then equivalent to a plain JS-backend compile. */
  private def collectNodeGlue(module: ir.NormalizedModule): String =
    val sb = StringBuilder()

    def walkContent(c: ir.Content): Unit = c match
      case ir.Content.EmbeddedBlock(language, source, _) if ast.Lang.isNode(language) =>
        if sb.nonEmpty then sb.append("\n")
        sb.append(source.stripTrailing())
      case _ => ()

    def walkSection(s: ir.Section): Unit =
      s.content.foreach(walkContent)
      s.subsections.foreach(walkSection)

    module.sections.foreach(walkSection)
    sb.toString
