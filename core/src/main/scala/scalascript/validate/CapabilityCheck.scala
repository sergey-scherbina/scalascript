package scalascript.validate

import scalascript.ir
import scalascript.ast.Lang
import scalascript.backend.spi.{Capabilities, Feature, Diagnostic}

/** Validate a normalised module against a backend's declared capabilities.
 *
 *  Per docs/backend-spi.md §11: core walks the IR, tags the features it
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

    def scanContent(c: ir.Content): Unit = c match
      case ir.Content.CodeBlock(source, _, _) => scanSource(source)
      case ir.Content.EmbeddedBlock(_, source, _) =>
        // Foreign-language fences imply the StringInterpolators feature is
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
      case ir.Content.EmbeddedBlock(language, _, _)
          if Lang.isOpaqueExec(language) && !cap.blockLanguages.contains(language) =>
        seen += language
      case _: ir.Content.SqlBlock if !cap.blockLanguages.contains(Lang.Sql) =>
        seen += Lang.Sql
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
    unsupported ++ unknownBlockLanguages(module, cap)

  // ─── Internal: tiny tokenisation that ignores comments ──────────────────

  /** True if `src` contains `kw` as a word boundary (cheap heuristic;
   *  doesn't strip string literals or comments — fine for v1 since
   *  validation gates compile, not runtime). */
  private def hasKeyword(src: String, kw: String): Boolean =
    val pat = ("\\b" + java.util.regex.Pattern.quote(kw) + "\\b").r
    pat.findFirstIn(src).isDefined

  // `s"..."`, `html"..."`, `css"..."`, `md"..."`, …
  private val InterpolatorPat = """\b[a-zA-Z_][a-zA-Z0-9_]*"[^"]""".r
  // `def name(x: T = expr, …)` — a `=` inside a param clause.
  private val DefaultParamPat = """def\s+[A-Za-z_][\w]*\s*\([^)]*=\s""".r
