package scalascript.validate

import scalascript.ir
import scalascript.ast.Lang
import scalascript.backend.spi.{Capabilities, Feature, Diagnostic, OutputKind}

/** Validate a normalised module against a backend's declared capabilities.
 *
 *  Per specs/backend-spi.md §11: core walks the IR, tags the features it
 *  uses, intersects with `backend.capabilities.features`, and emits
 *  `Diagnostic.Unsupported` entries for misses.  CLI / WebServer call
 *  this between `Normalize` and `backend.compile`.
 *
 *  Stage 4 implementation: feature detection is currently coarse — the
 *  IR carries scalascript blocks as raw `source: String`, so we scan
 *  with a small set of keyword / pattern heuristics rather than walking
 *  parsed IR.  Stage 5+ populates `Content.CodeBlock.body` with
 *  `IrExpr` nodes and the detector can switch to structural traversal
 *  without changing the public API. */
object CapabilityCheck:

  /** Detect which features a normalised module exercises. */
  def detect(module: ir.NormalizedModule): Set[Feature] =
    val detected = scala.collection.mutable.Set.empty[Feature]

    def scanSource(src: String): Unit =
      // Language features
      if hasKeyword(src, "effect")              then detected += Feature.AlgebraicEffects
      if hasKeyword(src, "handle")              then detected += Feature.AlgebraicEffects
      if hasKeyword(src, "perform")             then detected += Feature.AlgebraicEffects
      if hasKeyword(src, "var")                 then detected += Feature.MutableState
      if hasKeyword(src, "match") && src.contains("case") then detected += Feature.PatternMatching
      if hasKeyword(src, "extension")           then detected += Feature.ExtensionMethods
      if hasKeyword(src, "given") || hasKeyword(src, "using") then detected += Feature.TypeClasses
      if hasKeyword(src, "for") && (src.contains("yield") || src.contains(" do "))
                                                then detected += Feature.ForComprehensions
      if hasKeyword(src, "while")               then detected += Feature.WhileLoops
      if src.contains("@tailrec") || hasKeyword(src, "@scala.annotation.tailrec")
                                                then detected += Feature.TailCallOptimization
      if InterpolatorPat.findFirstIn(src).isDefined then detected += Feature.StringInterpolators
      // Default parameters: a `def name(...= ...)` shape.
      if DefaultParamPat.findFirstIn(src).isDefined then detected += Feature.DefaultParameters

      // Platform capabilities — match the std.* intrinsic packages a
      // future Stage 5 refactor will route through Backend.intrinsics.
      if src.contains("println") || src.contains("print(") then detected += Feature.ConsoleIO
      if src.contains("route(") || src.contains("serve(")  then detected += Feature.HttpServer
      if src.contains("onWebSocket") || src.contains("WsRoom") || src.contains("ws.send")
                                                            then detected += Feature.WebSockets
      if src.contains("hashPassword") || src.contains("verifyPassword") ||
         src.contains("signJwt") || src.contains("verifyJwt") ||
         src.contains("csrfToken") || src.contains("withSession") then detected += Feature.Auth
      if src.contains("os.read") || src.contains("os.write") || src.contains("os.list")
                                                            then detected += Feature.FileSystem
      if src.contains("crypto.") || src.contains("hashSha256") then detected += Feature.Crypto
      if src.contains("mcpServer(") || src.contains("serveMcp(")  then detected += Feature.McpServer
      if src.contains("mcpConnect(")                              then detected += Feature.McpClient
      if src.contains("Dataset.of(") || src.contains("Dataset.fromList(") ||
         src.contains("Dataset.fromGenerator(") || src.contains("Dataset.fromFile(") ||
         src.contains(".runLocal()") || src.contains(".runParallel()")
                                                                  then detected += Feature.Dataset
      if XmlInterpolatorPat.findFirstIn(src).isDefined            then detected += Feature.Markup
      if XsltTransformPat.findFirstIn(src).isDefined             then detected += Feature.Xslt
      // Registry-based interpolator capability detection: if a registered
      // interpolator's prefix appears in source, gate on its requiredFeatures.
      scalascript.compiler.plugin.InterpolatorRegistry.all.foreach { impl =>
        if impl.requiredFeatures.nonEmpty then
          val pat = s"""\\b${java.util.regex.Pattern.quote(impl.name)}"[^"]""".r
          if pat.findFirstIn(src).isDefined then
            detected ++= impl.requiredFeatures
      }

    def scanContent(c: ir.Content): Unit = c match
      case ir.Content.CodeBlock(source, _, _) => scanSource(source)
      case ir.Content.EmbeddedBlock(lang, _, _, _) if Lang.isXml(lang) =>
        // Fenced ```xml ... ``` blocks require Feature.Markup
        detected += Feature.Markup
      case ir.Content.EmbeddedBlock(_, source, _, _) =>
        // Other foreign-language fences imply the StringInterpolators feature is
        // *consumed* (host blocks reference them) — too coarse to detect
        // perfectly here; leave it to Stage 9's SourceLanguage plugins.
        ()
      case ir.Content.Import(path, _, _) =>
        detected += Feature.ModuleImports
        if path.contains("std/mcp/server") || path.contains("std/mcp/index") then
          detected += Feature.McpServer
        if path.contains("std/mcp/client") || path.contains("std/mcp/index") then
          detected += Feature.McpClient
        if path.contains("std/mapreduce") then
          detected += Feature.Dataset
      case _                          => ()

    def scanSection(s: ir.Section): Unit =
      s.content.foreach(scanContent)
      s.subsections.foreach(scanSection)

    module.sections.foreach(scanSection)
    detected.toSet

  /** Walk the module collecting every opaque-executable fenced block
   *  whose lang tag is not declared in `cap.blockLanguages`.  Lang-tag
   *  classes are matched via `Lang.isOpaqueExec` — today `node.js` /
   *  `node` (verbatim-linked) and `sql` (parameterised, carried as
   *  `ir.Content.SqlBlock`).  String blocks
   *  (`html` / `css` / `javascript`) and unknown inert tags are
   *  ignored. */
  private def unknownBlockLanguages(
    module: ir.NormalizedModule,
    cap:    Capabilities
  ): List[Diagnostic] =
    val seen = scala.collection.mutable.LinkedHashSet.empty[String]

    def scanContent(c: ir.Content): Unit = c match
      case ir.Content.EmbeddedBlock(language, _, _, _)
          if Lang.isOpaqueExec(language) && !cap.blockLanguages.contains(language) =>
        seen += language
      case _: ir.Content.SqlBlock if !cap.blockLanguages.contains(Lang.Sql) =>
        seen += Lang.Sql
      case _: ir.Content.TransactionBlock if !cap.blockLanguages.contains(Lang.Transaction) =>
        seen += Lang.Transaction
      case _ => ()

    def scanSection(s: ir.Section): Unit =
      s.content.foreach(scanContent)
      s.subsections.foreach(scanSection)

    module.sections.foreach(scanSection)
    seen.toList.map(Diagnostic.UnknownBlockLanguage.apply)

  /** Compute which required features the backend doesn't declare and
   *  which opaque-exec block languages it doesn't claim.  Empty list
   *  means compilation may proceed. */
  def validate(
    module:     ir.NormalizedModule,
    cap:        Capabilities,
    backendId:  String
  ): List[Diagnostic] =
    val required = detect(module)
    val missing  = required -- cap.features
    val unsupported = missing.toList.sortBy(_.toString).map { f =>
      Diagnostic.Unsupported(f, backendId)
    }
    unsupported ++ unknownBlockLanguages(module, cap) ++ unsupportedDbUrls(module, cap, backendId) ++ unsupportedClientSideDbUrls(module) ++ jvmOnlyExternDefs(module, cap, backendId)

  /** Validate `databases:` URL schemes against the target backend.
   *
   *  JS-family targets (js / node / wasm) only support schemes that have a
   *  `jsPrefix` in [[scalascript.db.DbScheme]] (currently `sqlite:` and
   *  `duckdb:`).  Any other scheme — including raw `jdbc:` URLs — produces
   *  `Diagnostic.UnsupportedJdbcUrl` so the renderer can direct the user to
   *  use a supported scheme or switch to the JVM target.
   *
   *  JVM / interpreter targets accept all canonical schemes (DbUrl.toJdbc
   *  handles translation at connect time), so this check never fires for them. */
  private def unsupportedDbUrls(
    module:    ir.NormalizedModule,
    cap:       Capabilities,
    backendId: String
  ): List[Diagnostic] =
    if !cap.blockLanguages.contains(Lang.Sql) then return Nil
    val isJsFamily = cap.outputs.contains(OutputKind.JavaScriptSource) ||
                     cap.outputs.contains(OutputKind.WasmBytecode)
    if !isJsFamily then return Nil
    module.manifest.toList.flatMap(_.databases)
      .filterNot(d => scalascript.db.DbUrl.isJsSupported(d.url))
      .map(d => Diagnostic.UnsupportedJdbcUrl(d.name, d.url, backendId))

  /** v1.30 — `@side=client` sql blocks may only reference databases whose
   *  URL scheme is JS-supported (sqlite:, sqlite-opfs:, duckdb:).  Fires on
   *  all targets — the constraint is about client-side executability, not
   *  about which target is currently being compiled. */
  private def unsupportedClientSideDbUrls(module: ir.NormalizedModule): List[Diagnostic] =
    val dbByName: Map[String, String] =
      module.manifest.toList.flatMap(_.databases).map(d => d.name -> d.url).toMap
    module.sections.flatMap(_.content).collect {
      case ir.Content.SqlBlock(src, _, Some(dbName), _, ir.SqlSide.Client) =>
        dbByName.get(dbName).filterNot(scalascript.db.DbUrl.isJsSupported).map { url =>
          Diagnostic.UnsupportedClientSideDbUrl(dbName, url, src.take(40).trim)
        }
      case ir.Content.SqlBlock(src, _, None, _, ir.SqlSide.Client) =>
        dbByName.get("default").filterNot(scalascript.db.DbUrl.isJsSupported).map { url =>
          Diagnostic.UnsupportedClientSideDbUrl("default", url, src.take(40).trim)
        }
    }.flatten

  /** arch-ffi-p1 — detect `@jvm`-only extern defs in modules compiled for the
   *  JS backend.  An `extern def` annotated `@jvm(...)` without a companion
   *  `@js(...)` will throw at runtime on JS; this check surfaces the issue at
   *  compile time.
   *
   *  Uses source-text heuristics (no parsed AST available in CapabilityCheck).
   *  Pattern: collect all annotation lines for each `extern def`; if any
   *  function has `@jvm(` but no `@js(`, it is JVM-only. */
  private def jvmOnlyExternDefs(
    module:    ir.NormalizedModule,
    cap:       Capabilities,
    backendId: String
  ): List[Diagnostic] =
    val isJsFamily = cap.outputs.contains(OutputKind.JavaScriptSource) ||
                     cap.outputs.contains(OutputKind.WasmBytecode)
    if !isJsFamily then return Nil

    val found = scala.collection.mutable.ListBuffer.empty[String]

    def scanSource(src: String): Unit =
      val lines = src.linesIterator.toArray
      var i = 0
      while i < lines.length do
        val trimmed = lines(i).trim
        // Collect annotation lines immediately before an `extern def`
        if trimmed.startsWith("extern def ") then
          // Look back for annotation lines (up to 10 lines back)
          val start = math.max(0, i - 10)
          val annotLines = lines.slice(start, i).map(_.trim).filter(_.startsWith("@"))
          val hasJvm = annotLines.exists(l => l.startsWith("@jvm(") || l == "@jvm")
          val hasJs  = annotLines.exists(l => l.startsWith("@js(")  || l == "@js")
          if hasJvm && !hasJs then
            // Extract the function name from "extern def name(..."
            val nameMatch = """^extern def (\w+)""".r.findFirstMatchIn(trimmed)
            nameMatch.foreach(m => found += m.group(1))
        i += 1

    def scanContent(c: ir.Content): Unit = c match
      case ir.Content.CodeBlock(source, _, _) => scanSource(source)
      case _                                  => ()

    def scanSection(s: ir.Section): Unit =
      s.content.foreach(scanContent)
      s.subsections.foreach(scanSection)

    module.sections.foreach(scanSection)
    found.toList.map(Diagnostic.JvmOnlyExternDef(_, backendId))

  // ─── Internal: tiny tokenisation that ignores comments ──────────────────

  /** True if `src` contains `kw` as a word boundary (cheap heuristic;
   *  doesn't strip string literals or comments — fine for v1 since
   *  validation gates compile, not runtime). */
  private def hasKeyword(src: String, kw: String): Boolean =
    val pat = ("\\b" + java.util.regex.Pattern.quote(kw) + "\\b").r
    pat.findFirstIn(src).isDefined

  // `s"..."`, `html"..."`, `css"..."`, `md"..."`, …
  private val InterpolatorPat    = """\b[a-zA-Z_][a-zA-Z0-9_]*"[^"]""".r
  // `xml"..."` string interpolator — requires Feature.Markup
  private val XmlInterpolatorPat = """\bxml"[^"]""".r
  // `.transform(` method call on a Markup.Doc value — requires Feature.Xslt
  private val XsltTransformPat   = """\.transform\s*\(""".r
  // `def name(x: T = expr, …)` — a `=` inside a param clause.
  private val DefaultParamPat    = """def\s+[A-Za-z_][\w]*\s*\([^)]*=\s""".r
