package scalascript.cli

import scalascript.ast.*
import scalascript.codegen.{JsGen, JsRuntimeBrowserPatch, JsRuntimeMcpBrowser, ScalaJsBackend}
import scalascript.backend.spi.{CompileResult, Segment}
import scalascript.backend.spi.OpenApiGenerator.OpenApiOptions
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import scalascript.transform.Normalize

// `ssc emit-*` transpile/export commands: emit-js, emit-wasm, emit-openapi,
// emit-spa, emit-wc, emit-scala. Extracted from Main.scala. (emit-spark /
// submit stay with the Spark machinery.)

final class EmitJsCmd extends CliCommand:
  def name = "emit-js"
  override def summary =
    "Transpile .ssc to JavaScript (Node) and print to stdout [NON-CONFORMING: 32-bit Int]"
  override def category = "Emit & transpile"
  override def details = List(
    "Flags: --no-tree-shake, --stats",
    "NON-CONFORMING for integer semantics: ssc `Int` is 64-bit; this lane folds integer "
      + "constants at 32 bits (2147483647+1 emits -2147483648) and carries values in a JS "
      + "double, losing exactness above 2^53 -- silently, exit 0. See specs/numeric-widths.md "
      + "§4. Slated for deletion with the v1 hybrid tier; use `run-js --v2` instead."
  )
  def run(args: List[String]): Unit =
    // Parse --no-tree-shake and --stats flags before processing files.
    var noTreeShake = false
    var printStats  = false
    val files = args.filter {
      case "--no-tree-shake" => noTreeShake = true; false
      case "--stats"         => printStats  = true; false
      case _                 => true
    }
    if files.isEmpty then { println("Error: No files specified"); System.exit(1) }
    for file <- files do
      val path    = os.Path(file, os.pwd)
      val baseDir = Some(path / os.up)
      if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
      else
        try
          val module   = Parser.parse(os.read(path))
          val segments = compileJsSegments(path, noTreeShake = noTreeShake)
          val hasSSBlocks = segments.exists {
            case Segment.Code("javascript", _) => true
            case _                             => false
          }
          if hasSSBlocks then
            val caps = JsGen.detectCapabilities(module, baseDir)
            print(JsGen.generateRuntime(caps))
          if printStats && !noTreeShake then
            // Re-run tree-shaking to get stats (shake result is separate from segmented output)
            val (_, statsOpt) = JsGen.generateWithStats(module, baseDir, noTreeShake = false)
            statsOpt.foreach(s => System.err.println(s.summary))
          for seg <- segments do seg match
            case Segment.Code("javascript", code) =>
              println(code)
              // Flush the ScalaScript output buffer before the next Scala.js segment runs
              println("""if (typeof process !== 'undefined' && process.stdout) { process.stdout.write(_output.join('\n') + (_output.length ? '\n' : '')); } else if (_output.length) { console.log(_output.join('\n')); } _output = [];""")
            case Segment.Source("scala", src) =>
              val bundle = ScalaJsBackend.compileSourceToJs(src, baseDir)
              if bundle.nonEmpty then println(bundle)
            case _ => ()
        catch case e: Exception =>
          System.err.println(s"JS generation error: ${e.getMessage}")
          System.exit(1)

final class EmitWasmCmd extends CliCommand:
  def name = "emit-wasm"
  override def summary = "Compile scala/scalascript blocks to WebAssembly via Scala.js"
  override def category = "Emit & transpile"
  def run(args: List[String]): Unit =
    if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
    for file <- args do
      val path = os.Path(file, os.pwd)
      if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
      else
        try
          val stem = path.last.stripSuffix(".ssc")
          compileViaBackend("wasm", path) match
            case CompileResult.Segmented(segs) =>
              if segs.isEmpty then
                System.err.println("emit-wasm: no compilable scala/scalascript blocks found in source")
                System.exit(1)
              for seg <- segs do seg match
                case Segment.Asset(name, bytes, _) =>
                  val out = os.pwd / name
                  os.write.over(out, bytes)
                  System.err.println(s"Wrote $out (${bytes.length} bytes)")
                case Segment.Code("javascript", glue) =>
                  val out = os.pwd / s"$stem.js"
                  os.write.over(out, glue)
                  System.err.println(s"Wrote $out")
                case _ => ()
            case CompileResult.Failed(diags) =>
              diags.foreach(d => System.err.println(s"[error] $d"))
              System.exit(1)
            case other =>
              System.err.println(s"emit-wasm: unexpected ${other.getClass.getSimpleName}")
              System.exit(1)
        catch case e: Exception =>
          System.err.println(s"WASM generation error: ${e.getMessage}")
          System.exit(1)

/** `ssc emit-rust <file.ssc>` — emit a Cargo crate via the rust backend.
 *  See specs/rust-backend.md §10. */
final class EmitRustCmd extends CliCommand:
  def name = "emit-rust"
  override def summary = "Compile scala/scalascript blocks to a Cargo crate (rust backend)"
  override def category = "Emit & transpile"
  override def details = List(
    "Flags: -o <dir>, --print-only, --bin-name <name>",
    "Default output dir: ./<stem>-rust/"
  )
  def run(args: List[String]): Unit =
    var outputDir: Option[String] = None
    var printOnly                 = false
    var binName:   Option[String] = None
    val files = scala.collection.mutable.ArrayBuffer.empty[String]
    val it = args.iterator
    while it.hasNext do
      it.next() match
        case "-o" | "--output" if it.hasNext => outputDir = Some(it.next())
        case "--print-only"                  => printOnly = true
        case "--bin-name" if it.hasNext      => binName   = Some(it.next())
        case f                               => files += f
    if files.isEmpty then
      System.err.println(
        "Usage: ssc emit-rust [-o <dir>] [--print-only] [--bin-name <name>] <file.ssc>"
      )
      System.exit(1)
    for file <- files.toList do
      val path = os.Path(file, os.pwd)
      if !os.exists(path) then
        System.err.println(s"emit-rust: file not found: $file"); System.exit(1)
      val stem = path.last.stripSuffix(".ssc")
      try
        val extras  = binName.fold(Map.empty[String, String])(n => Map("binName" -> n))
        compileViaBackend("rust", path, extras) match
          case CompileResult.Segmented(segs) =>
            val assets = segs.collect { case a: Segment.Asset => a }
            if assets.isEmpty then
              System.err.println(
                "emit-rust: no rust output produced from source (no compilable blocks?)"
              )
              System.exit(1)
            if printOnly then
              for a <- assets do
                println(s"// ── ${a.name} ──")
                println(new String(a.bytes, "UTF-8"))
            else
              val outDir = outputDir.map(os.Path(_, os.pwd))
                .getOrElse(os.pwd / s"$stem-rust")
              os.makeDir.all(outDir)
              for a <- assets do
                val out = outDir / os.RelPath(a.name)
                os.makeDir.all(out / os.up)
                os.write.over(out, a.bytes)
                System.err.println(s"Wrote $out (${a.bytes.length} bytes)")
              System.err.println(s"Cargo crate written to $outDir")
          case CompileResult.Failed(diags) =>
            diags.foreach(d => System.err.println(s"[error] $d"))
            System.exit(1)
          case other =>
            System.err.println(s"emit-rust: unexpected ${other.getClass.getSimpleName}")
            System.exit(1)
      catch case e: Exception =>
        System.err.println(s"emit-rust: ${e.getMessage}")
        System.exit(1)

final class EmitOpenapiCmd extends CliCommand:
  def name = "emit-openapi"
  override def summary = "Export OpenAPI 3.1 JSON/YAML without starting a server"
  override def category = "Emit & transpile"
  override def details = List("Flags: --format <json|yaml>, -o <file>, --title <s>, --version <v>, --server <url>, --require-declared")
  def run(args: List[String]): Unit =
    var outputArg: Option[String] = None
    var formatArg: Option[String] = None
    var title: String = "ScalaScript API"
    var version: String = "1.0.0"
    var requireDeclared = false
    val servers = scala.collection.mutable.ArrayBuffer.empty[String]
    val files = scala.collection.mutable.ArrayBuffer.empty[String]

    val it = args.iterator
    while it.hasNext do
      it.next() match
        case "-o" | "--output" if it.hasNext =>
          outputArg = Some(it.next())
        case "--format" if it.hasNext =>
          formatArg = Some(it.next().toLowerCase)
        case "--title" if it.hasNext =>
          title = it.next()
        case "--version" if it.hasNext =>
          version = it.next()
        case "--server" if it.hasNext =>
          servers += it.next()
        case "--require-declared" =>
          requireDeclared = true
        case flag if flag.startsWith("-") =>
          System.err.println(s"emit-openapi: unknown flag $flag")
          System.exit(1)
        case file =>
          files += file
    if files.isEmpty then
      System.err.println("Usage: ssc emit-openapi [--format json|yaml] [-o file] [--title s] [--version v] [--server url] <file.ssc>")
      System.exit(1)
    if files.size > 1 then
      System.err.println("emit-openapi: exactly one input file is supported")
      System.exit(1)

    val path = os.Path(files.head, os.pwd)
    if !os.exists(path) then
      System.err.println(s"Error: File not found: ${files.head}")
      System.exit(1)

    val inferredFormat = outputArg.flatMap { out =>
      val lower = out.toLowerCase
      if lower.endsWith(".yaml") || lower.endsWith(".yml") then Some("yaml")
      else if lower.endsWith(".json") then Some("json")
      else None
    }
    val format = formatArg.orElse(inferredFormat).getOrElse("json")
    if format != "json" && format != "yaml" then
      System.err.println("emit-openapi: --format must be json or yaml")
      System.exit(1)

    val nullOut = java.io.PrintStream(java.io.OutputStream.nullOutputStream)
    val module = Parser.parseFile(path)
    scalascript.server.Routes.clear()
    val interp = Interpreter(out = nullOut, baseDir = Some(path / os.up), headless = true, openApiDryRun = true)
    try interp.run(module)
    catch
      case scalascript.backend.spi.OpenApiDryRun.Sentinel => ()
      case e: Exception =>
        System.err.println(s"emit-openapi: error running ${files.head} in dry mode: ${e.getMessage}")
        System.exit(1)

    val options = OpenApiOptions(title = title, version = version, servers = servers.toList)
    val responseTypes = openApiResponseTypes(module)
    val securitySchemes    = scalascript.interpreter.OpenApiRuntime.openApiSecuritySchemes(interp)
    val schemaComponents   = scalascript.interpreter.OpenApiRuntime.openApiSchemaComponents(interp)
    val rendered =
      if format == "yaml" then
        scalascript.interpreter.OpenApiRuntime.generateOpenApiYaml(
          interp.routeRegistry,
          securitySchemes,
          options,
          responseTypes,
          schemaComponents
        )
      else
        scalascript.interpreter.OpenApiRuntime.generateOpenApiJson(
          interp.routeRegistry,
          securitySchemes,
          options,
          responseTypes,
          schemaComponents
        )

    outputArg match
      case Some(out) =>
        val outPath = os.Path(out, os.pwd)
        os.makeDir.all(outPath / os.up)
        os.write.over(outPath, rendered)
      case None =>
        print(rendered)

    if requireDeclared then
      val warnings = openApiEvidenceDiagnostics(module)
      if warnings.nonEmpty then
        warnings.foreach(w => System.err.println(s"[emit-openapi] unknown type evidence: $w"))
        System.exit(1)

private[cli] def openApiEvidenceDiagnostics(module: Module): List[String] =
  Normalize(module).manifest.toList.flatMap { manifest =>
    val endpoints = manifest.apiClients.flatMap(_.endpoints)
    val handlers  = manifest.remoteHandlers

    val endpointWarnings = endpoints.flatMap { e =>
      val ev = e.typeEvidence
      val reqKind  = ev.flatMap(_.request).map(_.kind).getOrElse("Unknown")
      val respKind = ev.flatMap(_.response).map(_.kind).getOrElse("Unknown")
      if reqKind == "Declared" && respKind == "Declared" then None
      else Some(s"${e.method} ${e.path} (request: $reqKind, response: $respKind)")
    }

    val handlerWarnings = handlers.flatMap { h =>
      val ev = h.typeEvidence
      val reqKind  = ev.flatMap(_.request).map(_.kind).getOrElse("Unknown")
      val respKind = ev.flatMap(_.response).map(_.kind).getOrElse("Unknown")
      if reqKind == "Declared" && respKind == "Declared" then None
      else Some(s"handler ${h.name} (request: $reqKind, response: $respKind)")
    }

    endpointWarnings ++ handlerWarnings
  }

private[cli] def openApiResponseTypes(module: Module): Map[(String, String), String] =
  val entries =
    module.manifest.toList.flatMap(_.apiClients).flatMap(_.endpoints).collect {
      case endpoint if endpoint.responseType.nonEmpty && endpoint.responseType != "Any" =>
        (endpoint.method.toUpperCase -> endpoint.path) -> endpoint.responseType
    }
  entries.foldLeft(Map.empty[(String, String), String]) { case (acc, (key, value)) =>
    if acc.contains(key) then acc else acc.updated(key, value)
  }

final class EmitSpaCmd extends CliCommand:
  def name = "emit-spa"
  override def summary = "Wrap .ssc as a browser SPA (HTML + embedded JS)"
  override def category = "Emit & transpile"
  override def details = List("Flags: --frontend <custom|react|solid|vue>")
  def run(args: List[String]): Unit =
    // v1.18 / Phase A7 — optional --frontend <custom|react|solid|vue>
    // picks which FrontendFrameworkSpi impl downstream SPA codegen routes
    // through.  Today the SPA path doesn't yet consume the registry (that
    // lands in A8), but validating + selecting here keeps the flag stable.
    var frontendBackend: Option[String] = None
    var serverUrl:       Option[String] = None
    val files = scala.collection.mutable.ArrayBuffer.empty[String]
    val it    = args.iterator
    while it.hasNext do
      it.next() match
        case "--frontend" if it.hasNext =>
          val name = it.next()
          if !browserFrontendNames.contains(name) then
            System.err.println(
              s"emit-spa: unknown --frontend '$name' " +
              s"(valid: ${browserFrontendNames.toList.sorted.mkString(" / ")})")
            System.exit(1)
          frontendBackend = Some(name)
        case "--server-url" if it.hasNext =>
          serverUrl = Some(it.next())
        case f => files += f
    if files.isEmpty then { println("Error: No files specified"); System.exit(1) }
    frontendBackend.foreach(applyFrontendBackend)
    for file <- files.toList do
      val path    = os.Path(file, os.pwd)
      if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
      else
        try
          println(renderSpaHtml(path, serverUrl))
        catch case e: Exception =>
          System.err.println(s"SPA generation error: ${e.getMessage}")
          System.exit(1)

private[cli] def renderSpaHtml(sscFile: os.Path, backendBaseUrl: Option[String]): String =
  val baseDir = Some(sscFile / os.up)
  val module = Parser.parse(os.read(sscFile))
  val segments = compileJsSegments(sscFile)
  val title    = module.manifest.flatMap(_.name).getOrElse(sscFile.last.stripSuffix(".ssc"))
  // Concatenate user JS — same segment loop as emit-js but no
  // process.stdout flushes (browser-only output goes to console).
  val userJs = segments.collect {
    case Segment.Code("javascript", code) => code
    case Segment.Source("scala", src)     =>
      ScalaJsBackend.compileSourceToJs(src, baseDir)
  }.filter(_.nonEmpty).mkString("\n")
  val rawJs = rawJavaScriptBlocks(module)
  // v1.17 Phase 3 — when the user's JS references `mcpConnect`,
  // splice in the browser-compatible MCP client preamble.  The
  // Node-side `JsRuntimeMcp` would import worker_threads etc.,
  // which crashes in a browser; the browser variant uses sync XHR
  // with zero deps.
  val browserJs = userJs + "\n" + rawJs
  val mcpPreamble =
    if browserJs.contains("mcpConnect") || browserJs.contains("mcpServer") then
      "\n" + JsRuntimeMcpBrowser
    else ""
  // Tree-shake: detect which runtime blocks are actually needed,
  // then exclude Node-only capabilities (Mcp, Dataset) that would
  // crash in a browser environment.
  val allCaps    = JsGen.detectCapabilities(module, baseDir)
  val spaCaps    = allCaps - JsGen.Capability.Mcp - JsGen.Capability.Dataset
  val spaRuntime = JsGen.generateRuntime(spaCaps)
  val backendInit = backendBaseUrl.fold("") { url =>
    s"globalThis.__sscBackendBaseUrl = ${jsStringLiteral(url)}; // injected by ssc --server-url\n"
  }
  s"""<!doctype html>
     |<html lang="en">
     |<head>
     |  <meta charset="utf-8">
     |  <meta name="viewport" content="width=device-width, initial-scale=1">
     |  <title>$title</title>
     |</head>
     |<body>
     |<script>
     |$backendInit$spaRuntime
     |$JsRuntimeBrowserPatch$mcpPreamble
     |$rawJs
     |$userJs
     |</script>
     |</body>
     |</html>""".stripMargin

private[cli] def rawJavaScriptBlocks(module: Module): String =
  def collect(section: Section): List[String] =
    section.content.collect {
      case cb: Content.CodeBlock if Lang.isJavaScript(cb.lang) => cb.source
    } ++ section.subsections.flatMap(collect)

  module.sections.flatMap(collect).filter(_.nonEmpty).mkString("\n")

/** v0.8 — emit a JS bundle that registers each component object in the
 *  file as a W3C Custom Element.  Detection rule: a top-level
 *  `object Foo { val css: String; def render(<params>): String }`
 *  becomes `<foo-component>` (PascalCase → kebab-case + `-component`).
 *  Each render parameter is read from the same-name HTML attribute as
 *  a String; Shadow DOM scopes the CSS automatically. */
private case class WcComponent(name: String, params: List[String], hasJs: Boolean)

/** PascalCase → kebab-case (uppercase letters introduce a hyphen). */
private def wcKebab(s: String): String =
  val sb = StringBuilder()
  s.zipWithIndex.foreach { (c, i) =>
    if c.isUpper then
      if i > 0 then sb.append('-')
      sb.append(c.toLower)
    else sb.append(c)
  }
  sb.toString

/** Inspect a scala.meta `Defn.Object` for the component shape:
 *  `object Foo { val css: …; def render(<params>): … }`.  When that
 *  shape is found, append it (plus an `hasJs` flag if a `val js` is
 *  also present) to `into`. */
private def detectWcComponent(
    d:    scala.meta.Defn.Object,
    into: scala.collection.mutable.ArrayBuffer[WcComponent]
): Unit =
  import scala.meta.{Defn, Pat}
  var cssOk = false
  var jsOk  = false
  var params: Option[List[String]] = None
  d.templ.body.stats.foreach {
    case Defn.Val(_, List(Pat.Var(n)), _, _) if n.value == "css" => cssOk = true
    case Defn.Val(_, List(Pat.Var(n)), _, _) if n.value == "js"  => jsOk = true
    case dd: Defn.Def if dd.name.value == "render" =>
      params = Some(
        dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value))
    case _ => ()
  }
  if cssOk && params.isDefined then
    into += WcComponent(d.name.value, params.get, jsOk)

/** v0.8 — emit a JS bundle that registers each component object in the
 *  file as a W3C Custom Element.  Detection rule: a top-level
 *  `object Foo { val css: String; def render(<params>): String }`
 *  becomes `<foo-component>` (PascalCase → kebab-case + `-component`).
 *  Each render parameter is read from the same-name HTML attribute as
 *  a String; Shadow DOM scopes the CSS automatically. */
final class EmitWcCmd extends CliCommand:
  def name = "emit-wc"
  override def summary = "Emit each component as a W3C Custom Element bundle"
  override def category = "Emit & transpile"
  def run(args: List[String]): Unit =
    import scala.meta.Defn
    if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
    for file <- args do
      val path    = os.Path(file, os.pwd)
      val baseDir = Some(path / os.up)
      if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
      else
        try
          val module     = Parser.parse(os.read(path))
          val components = scala.collection.mutable.ArrayBuffer.empty[WcComponent]
          module.sections.foreach { section =>
            section.content.foreach {
              case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
                cb.tree.foreach { node =>
                  scalascript.ast.ScalaNode.fold(node) {
                    case d: Defn.Object => detectWcComponent(d, components)
                    case scala.meta.Source(stats) =>
                      stats.foreach {
                        case d: Defn.Object => detectWcComponent(d, components)
                        case _              => ()
                      }
                    case _ => ()
                  }
                }
              case _ => ()
            }
          }
          val segments = JsGen.generateSegmented(module, baseDir)
          val userJs = segments.collect {
            case JsGen.Segment.ScalaScriptJs(code) => code
            case JsGen.Segment.ScalaSource(src)    =>
              ScalaJsBackend.compileSourceToJs(src, baseDir)
          }.filter(_.nonEmpty).mkString("\n")
          val wcCaps = JsGen.detectCapabilities(module, baseDir)
          print(JsGen.generateRuntime(wcCaps))
          println(userJs)
          components.foreach { c =>
            val tag       = wcKebab(c.name) + "-component"
            val paramsArr = c.params.map(p => "'" + p + "'").mkString(", ")
            val argsExpr  =
              if c.params.isEmpty then ""
              else c.params.map(p => s"this.getAttribute('$p') || ''").mkString(", ")
            val jsHook =
              if c.hasJs then
                s"""
      try { if (typeof ${c.name}.js === 'string' && ${c.name}.js.trim().length > 0) new Function(${c.name}.js).call(shadow); }
      catch (e) { console.error('${c.name}.js failed:', e); }"""
              else ""
            val jsHookIndented = jsHook.replace("\n", "\n  ")
            // Anonymous class via `customElements.define(tag, class extends … {})`
            // — avoids clashing with the heading-bound `<section>Component` object
            // JsGen synthesises for the markdown section that introduces the
            // component.
            // SSR hydration guard: if the element was rendered server-side with
            // declarative shadow DOM (`<template shadowrootmode="open">`), the
            // browser deserialises the shadow root before JS runs — skip
            // attachShadow/innerHTML so the pre-rendered content isn't wiped.
            println(s"""
customElements.define('$tag', class extends HTMLElement {
  static get observedAttributes() { return [$paramsArr]; }
  connectedCallback() {
    if (this.shadowRoot && this.shadowRoot.childNodes.length > 0) {
      const shadow = this.shadowRoot;$jsHookIndented
      return;
    }
    const shadow = this.attachShadow({mode: 'open'});
    const css  = (typeof ${c.name}.css === 'string') ? ${c.name}.css : '';
    const html = ${c.name}.render($argsExpr);
    shadow.innerHTML = '<style>' + css + '</style>' + _show(html);$jsHook
  }
  attributeChangedCallback() { if (this.isConnected) this.connectedCallback(); }
});""")
          }
        catch case e: Exception =>
          System.err.println(s"emit-wc generation error: ${e.getMessage}")
          System.exit(1)

final class EmitScalaCmd extends CliCommand:
  def name = "emit-scala"
  override def summary = "Print generated Scala 3 script to stdout"
  override def category = "Emit & transpile"
  def run(args: List[String]): Unit =
    if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
    for file <- args do
      val path = os.Path(file, os.pwd)
      if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
      else
        try   println(expectText(compileViaBackend("jvm", path), "emit-scala"))
        catch case e: Exception =>
          System.err.println(s"Scala generation error: ${e.getMessage}")
          System.exit(1)

// ─────────────────────────────────────────────────────────────────────────────
// ssc emit-spark  —  Apache Spark backend (Phase 1: local SparkSession)
// ─────────────────────────────────────────────────────────────────────────────
