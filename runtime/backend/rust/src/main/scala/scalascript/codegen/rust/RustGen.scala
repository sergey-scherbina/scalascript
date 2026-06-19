package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.ir
import scalascript.transform.Denormalize

/** Emits Cargo crate assets for a `NormalizedModule`.
 *
 *  Phase R.1.3c — runs `Denormalize` to obtain the scalameta-backed
 *  AST and walks top-level `Defn.Def` nodes via `RustCodeWalk`.  Emits
 *  Cargo.toml + value.rs + runtime/mod.rs + `src/generated/<crate>.rs`.
 *  Anything outside the narrow R.1 subset (parameters, non-Unit return
 *  types, expressions beyond `Apply(println, Lit.String)` + literals)
 *  flows through as a `Diagnostic.Generic` and surfaces as
 *  `CompileResult.Failed`.  The main-assembly slice (`src/main.rs`
 *  shim) is the next slice. */
object RustGen:

  /** Default crate name when the module manifest carries none. */
  private val DefaultCrateName: String = "ssc_program"
  /** Default version pinned to Cargo's de-facto SemVer when no manifest. */
  private val DefaultVersion:   String = "0.1.0"
  /** Pinned Cargo edition for the rust target — `specs/rust-backend.md §9`. */
  private val CargoEdition:     String = "2021"

  def generate(
      module:           ir.NormalizedModule,
      opts:             BackendOptions,
      intrinsics:       Map[ir.QualifiedName, IntrinsicImpl],
      runtimePreamble:  String
  ): CompileResult =
    val _ = runtimePreamble // not used in R.1
    // BackendOptions.extra("binName") overrides the manifest name so a
    // CLI caller (`ssc build-rust hello.ssc`) can pin the produced
    // binary to the source file stem regardless of front-matter.
    val crateName = sanitizeCrateName(
      opts.extra.get("binName")
        .orElse(module.manifest.flatMap(_.name))
        .getOrElse(DefaultCrateName)
    )
    val version   = module.manifest.flatMap(_.version).getOrElse(DefaultVersion)
    val descr     = module.manifest.flatMap(_.description).filter(_.nonEmpty)
    val hasMain   = moduleDeclaresMain(module)
    val astModule = Denormalize(module)
    // R.3.2 — IR walk: which crypto intrinsics does the program reach?
    // Drives both the conditional Cargo deps and the conditional
    // runtime-helper emit so a hello-world stays dep-free.
    val cryptoUsage = scanCryptoUsage(astModule)
    val effectUsage = scanEffectUsage(astModule)
    val httpUsage   = scanHttpUsage(astModule)
    val authUsage   = scanAuthUsage(astModule)
    val wsUsage     = scanWsUsage(astModule)
    val mcpUsage    = scanMcpUsage(astModule)
    val uiUsage     = scanUiUsage(astModule)
    val cargoToml   = renderCargoToml(crateName, version, descr, hasMain, cryptoUsage, httpUsage, authUsage, wsUsage, mcpUsage)

    RustCodeWalk.walk(astModule, intrinsics) match
      case Left(diags) =>
        CompileResult.Failed(diags)
      case Right(walked) =>
        val entry        = walked.mainEntry
        val effectiveBin = entry.isDefined
        // Re-render Cargo.toml against the AST-resolved entry check —
        // the textual `@main` scan is a hint; if the walker found no
        // annotated def, fall back to [lib].
        val cargoTomlFinal =
          if effectiveBin == hasMain then cargoToml
          else renderCargoToml(crateName, version, descr, effectiveBin, cryptoUsage, httpUsage, authUsage, wsUsage, mcpUsage)
        val generatedMod = renderGeneratedMod(crateName)
        val rootFile     =
          if effectiveBin then renderMainRs(crateName, entry.get)
          else                 renderLibRs()
        val rootName     = if effectiveBin then "src/main.rs" else "src/lib.rs"
        val runtimeMod =
          val sb = new StringBuilder(RustRuntimeTemplates.RuntimeModRs)
          if cryptoUsage.contains("sha256") then sb.append(RustRuntimeTemplates.Sha256Rs)
          if cryptoUsage.exists(n => n == "base64Encode" || n == "base64Decode") then
            sb.append(RustRuntimeTemplates.Base64Rs)
          if cryptoUsage.exists(n => n == "jsonParse" || n == "jsonStringify") then
            sb.append(RustRuntimeTemplates.JsonRs)
          // R.4.1 — when effect keywords are present, re-export the
          // standalone `effect` submodule from runtime/mod.rs.
          if effectUsage.nonEmpty then
            sb.append("\n// ── R.4.1 — algebraic-effects runtime ──\n")
            sb.append("pub mod effect;\n")
          // R.4.2 — tagless-final effect traits (Logger etc.)
          if walked.effectNames.nonEmpty then
            sb.append("\n// ── R.4.2 — tagless-final effect traits ──\n")
            sb.append("pub mod effects;\n")
          if httpUsage then
            sb.append("\n// ── R.5 — HTTP server runtime ──\n")
            sb.append("pub mod http;\n")
          if authUsage.nonEmpty then
            sb.append("\n// ── R.6 — auth runtime ──\n")
            sb.append("pub mod auth;\n")
          if wsUsage.nonEmpty then
            sb.append("\n// ── R.6 — WebSocket runtime ──\n")
            sb.append("pub mod ws;\n")
          if mcpUsage.nonEmpty then
            sb.append("\n// ── R.6 — MCP server runtime ──\n")
            sb.append("pub mod mcp;\n")
          if uiUsage then
            sb.append("\n// ── std/ui — SSR View runtime ──\n")
            sb.append("pub mod ui;\n")
          sb.toString
        val baseAssets = List(
          Segment.Asset("Cargo.toml",                   cargoTomlFinal.getBytes("UTF-8"),       "application/toml"),
          Segment.Asset("src/value.rs",                 RustRuntimeTemplates.ValueRs.getBytes("UTF-8"), "text/x-rust"),
          Segment.Asset("src/runtime/mod.rs",           runtimeMod.getBytes("UTF-8"),           "text/x-rust"),
          Segment.Asset("src/generated/mod.rs",         generatedMod.getBytes("UTF-8"),         "text/x-rust"),
          Segment.Asset(s"src/generated/$crateName.rs", walked.generated.getBytes("UTF-8"),     "text/x-rust"),
          Segment.Asset(rootName,                       rootFile.getBytes("UTF-8"),             "text/x-rust")
        )
        val effectAsset =
          if effectUsage.isEmpty then Nil
          else List(Segment.Asset(
            "src/runtime/effect.rs",
            RustRuntimeTemplates.EffectRs.getBytes("UTF-8"),
            "text/x-rust"
          ))
        val taglessEffectAsset =
          if walked.effectNames.isEmpty then Nil
          else List(Segment.Asset(
            "src/runtime/effects.rs",
            RustRuntimeTemplates.renderTaglessEffectsRs(walked.effectNames).getBytes("UTF-8"),
            "text/x-rust"
          ))
        val httpAsset =
          if !httpUsage then Nil
          else
            // Append the std/ui `serve(view, port)` SSR overload only when the
            // program also uses the View primitives (it references `runtime::ui`).
            val httpSrc = RustRuntimeTemplates.HttpRs +
              (if uiUsage then RustRuntimeTemplates.UiServeRs else "")
            List(Segment.Asset(
              "src/runtime/http.rs",
              httpSrc.getBytes("UTF-8"),
              "text/x-rust"
            ))
        val authAsset =
          if authUsage.isEmpty then Nil
          else List(Segment.Asset(
            "src/runtime/auth.rs",
            RustRuntimeTemplates.AuthRs.getBytes("UTF-8"),
            "text/x-rust"
          ))
        val wsAsset =
          if wsUsage.isEmpty then Nil
          else List(Segment.Asset(
            "src/runtime/ws.rs",
            RustRuntimeTemplates.WsRs.getBytes("UTF-8"),
            "text/x-rust"
          ))
        val mcpAsset =
          if mcpUsage.isEmpty then Nil
          else List(Segment.Asset(
            "src/runtime/mcp.rs",
            RustRuntimeTemplates.McpRs.getBytes("UTF-8"),
            "text/x-rust"
          ))
        val uiAsset =
          if !uiUsage then Nil
          else List(Segment.Asset(
            "src/runtime/ui.rs",
            RustRuntimeTemplates.UiRs.getBytes("UTF-8"),
            "text/x-rust"
          ))
        CompileResult.Segmented(baseAssets ++ effectAsset ++ taglessEffectAsset ++ httpAsset ++ authAsset ++ wsAsset ++ mcpAsset ++ uiAsset)

  /** R.3.2 — IR walk for crypto-intrinsic usage.  Returns the set of
   *  intrinsic names actually reached so RustGen can decide which
   *  crates to add to `Cargo.toml` and whether to append the crypto
   *  runtime helpers. */
  /** R.5 — detect `serve` / `route` calls anywhere in the module source.
   *  Triggers the hyper + tokio dep emit and the `src/runtime/http.rs`
   *  asset. */
  private[rust] def scanHttpUsage(astModule: scalascript.ast.Module): Boolean =
    val found = scala.collection.mutable.Set.empty[String]
    astModule.sections.foreach(s => scanSectionForNames(s, Set("serve", "route"), found))
    found.nonEmpty

  /** std/ui — detect `element` / `textNode` / `fragment` View-primitive
   *  usage; triggers the `src/runtime/ui.rs` SSR asset + `pub mod ui`. */
  private[rust] def scanUiUsage(astModule: scalascript.ast.Module): Boolean =
    val found = scala.collection.mutable.Set.empty[String]
    astModule.sections.foreach(s => scanSectionForNames(s, Set("element", "textNode", "fragment", "renderHtml"), found))
    found.nonEmpty

  private[rust] def scanCryptoUsage(astModule: scalascript.ast.Module): Set[String] =
    // R.3.2 + R.3.3 — scan covers both crypto/base64 and JSON intrinsics
    // so RustGen can drive Cargo deps + runtime-template emit on demand.
    val names = Set("sha256", "base64Encode", "base64Decode", "jsonParse", "jsonStringify")
    val found = scala.collection.mutable.Set.empty[String]
    astModule.sections.foreach(s => scanSectionForNames(s, names, found))
    found.toSet

  /** R.6 — scan for auth intrinsic calls (hashPassword, verifyPassword, jwtSign, jwtVerify).
   *  Returns the set of names actually reached; non-empty triggers argon2 + jsonwebtoken deps. */
  private[rust] def scanAuthUsage(astModule: scalascript.ast.Module): Set[String] =
    val names = Set("hashPassword", "verifyPassword", "jwtSign", "jwtVerify")
    val found = scala.collection.mutable.Set.empty[String]
    astModule.sections.foreach(s => scanSectionForNames(s, names, found))
    found.toSet

  /** R.6 — scan for WebSocket intrinsic calls (wsRoute, wsServe, wsConnectSync).
   *  Returns the set of names actually reached; non-empty triggers tokio-tungstenite deps. */
  private[rust] def scanWsUsage(astModule: scalascript.ast.Module): Set[String] =
    val names = Set("wsRoute", "wsServe", "wsConnectSync")
    val found = scala.collection.mutable.Set.empty[String]
    astModule.sections.foreach(s => scanSectionForNames(s, names, found))
    found.toSet

  /** R.6 — scan for MCP intrinsic calls (mcpRegisterTool, mcpServe).
   *  Returns the set of names actually reached; non-empty triggers serde_json dep. */
  private[rust] def scanMcpUsage(astModule: scalascript.ast.Module): Set[String] =
    val names = Set("mcpRegisterTool", "mcpServe")
    val found = scala.collection.mutable.Set.empty[String]
    astModule.sections.foreach(s => scanSectionForNames(s, names, found))
    found.toSet

  /** R.4.1 — textual scan of code-block sources for effect-related
   *  keywords (`perform`, `handle`, `resume`, top-level `effect E:`).
   *  Drives whether RustGen emits `src/runtime/effect.rs` and adds a
   *  `pub mod effect;` line to `src/runtime/mod.rs`.  Returns the set
   *  of keywords actually seen; emit fires iff non-empty. */
  private[rust] def scanEffectUsage(astModule: scalascript.ast.Module): Set[String] =
    val found = scala.collection.mutable.Set.empty[String]
    astModule.sections.foreach(s => scanSectionForEffects(s, found))
    found.toSet

  private def scanSectionForEffects(
      s: scalascript.ast.Section, found: scala.collection.mutable.Set[String]
  ): Unit =
    s.content.foreach(c => scanContentForEffects(c, found))
    s.subsections.foreach(sub => scanSectionForEffects(sub, found))

  /** Effect detection is textual to keep the dependency surface tiny —
   *  the scalameta tree for `effect E: case op(...)` requires a fully
   *  parsed Scala 3 enum-like shape, and the keywords `perform` /
   *  `handle` / `resume` may surface in macro-rewritten forms.  A
   *  conservative text scan catches every shape the lowering slice
   *  (R.4.2) will actually consume. */
  private def scanContentForEffects(
      c: scalascript.ast.Content, found: scala.collection.mutable.Set[String]
  ): Unit = c match
    // Scan every code block we accept (scalascript/ssc/scala + rust)
    // so a `rust` block that pokes the runtime directly still triggers
    // the conditional `effect.rs` emit.
    case scalascript.ast.Content.CodeBlock(lang, source, _, _, _, _, _)
        if lang.equalsIgnoreCase("scalascript") || lang.equalsIgnoreCase("ssc") ||
           lang.equalsIgnoreCase("scala")        || lang.equalsIgnoreCase("rust") =>
      val text = source.linesIterator
        .map(_.replaceFirst("//.*", ""))
        .mkString("\n")
      if EffectKeywordRegexes("perform").findFirstIn(text).isDefined then found += "perform"
      if EffectKeywordRegexes("handle") .findFirstIn(text).isDefined then found += "handle"
      if EffectKeywordRegexes("resume") .findFirstIn(text).isDefined then found += "resume"
      if EffectKeywordRegexes("effect") .findFirstIn(text).isDefined then found += "effect"
    case _ => ()

  /** Keyword regexes — bounded by word edges so `superformula` doesn't
   *  match `perform`, and `effective` doesn't match `effect`. */
  private val EffectKeywordRegexes: Map[String, scala.util.matching.Regex] = Map(
    "perform" -> raw"\bperform\s*\(".r,
    "handle"  -> raw"\bhandle\s*[({]".r,
    "resume"  -> raw"\bresume\s*\(".r,
    "effect"  -> raw"\beffect\s+[A-Z]\w*\s*[:({]".r
  )

  private def scanSectionForNames(
      s: scalascript.ast.Section, names: Set[String], found: scala.collection.mutable.Set[String]
  ): Unit =
    s.content.foreach(c => scanContentForNames(c, names, found))
    s.subsections.foreach(sub => scanSectionForNames(sub, names, found))

  private def scanContentForNames(
      c: scalascript.ast.Content, names: Set[String], found: scala.collection.mutable.Set[String]
  ): Unit =
    import scala.meta.transversers.XtensionCollectionLikeUI
    c match
      case scalascript.ast.Content.CodeBlock(lang, _, Some(node), _, _, _, _)
          if lang.equalsIgnoreCase("scalascript") || lang.equalsIgnoreCase("ssc") ||
             lang.equalsIgnoreCase("scala") =>
        node.tree.collect { case scala.meta.Term.Name(n) if names.contains(n) => found += n }
      case _ => ()

  /** `src/generated/mod.rs` — one-line re-export for the crate module. */
  private[rust] def renderGeneratedMod(crateName: String): String =
    s"""//! Generated module index — re-exports per-source modules.
       |
       |pub mod $crateName;
       |""".stripMargin

  /** `src/main.rs` shim for a binary crate.  Wires the three top-level
   *  modules and calls the `@main`-annotated entry point. */
  private[rust] def renderMainRs(crateName: String, entry: String): String =
    s"""//! Crate entry point.  Emitted by RustGen; do not edit by hand.
       |
       |mod runtime;
       |mod value;
       |mod generated;
       |
       |fn main() {
       |    generated::$crateName::$entry();
       |}
       |""".stripMargin

  /** `src/lib.rs` for a library crate (no `@main` in the source). */
  private[rust] def renderLibRs(): String =
    """//! Crate library root.  Emitted by RustGen; do not edit by hand.
      |
      |pub mod runtime;
      |pub mod value;
      |pub mod generated;
      |""".stripMargin

  /** Render the `Cargo.toml` text for a crate with no dependencies and
   *  a single `[[bin]]` entry when `hasMain` is true, or a `[lib]` entry
   *  otherwise.  Format is fixed so goldens stay stable across builds. */
  private[rust] def renderCargoToml(
      crateName:   String,
      version:     String,
      descr:       Option[String],
      hasMain:     Boolean,
      cryptoUsage: Set[String] = Set.empty,
      httpUsage:   Boolean     = false,
      authUsage:   Set[String] = Set.empty,
      wsUsage:     Set[String] = Set.empty,
      mcpUsage:    Set[String] = Set.empty
  ): String =
    val descrLine = descr match
      case Some(d) => s"""description = "${escapeTomlString(d)}"
""" // ↵ trailing newline so the block stays uniform
      case None    => ""
    // R.3.2 — only emit crate deps the program actually reaches.
    val depLines = scala.collection.mutable.ArrayBuffer.empty[String]
    if cryptoUsage.contains("sha256") then depLines += "sha2 = \"0.10\""
    if cryptoUsage.exists(n => n == "base64Encode" || n == "base64Decode") then
      depLines += "base64 = \"0.22\""
    // R.3.3 — serde_json gates on either JSON intrinsic.
    if cryptoUsage.exists(n => n == "jsonParse" || n == "jsonStringify") then
      depLines += "serde_json = \"1.0\""
    // R.5 — HTTP server deps, only when serve/route are used.
    if httpUsage then
      depLines += "tokio = { version = \"1\", features = [\"rt-multi-thread\", \"net\", \"macros\"] }"
      depLines += "hyper = { version = \"1\", features = [\"server\", \"http1\"] }"
      depLines += "hyper-util = { version = \"0.1\", features = [\"tokio\"] }"
      depLines += "http-body-util = \"0.1\""
      depLines += "bytes = \"1\""
    // R.6 — auth deps, only when at least one auth intrinsic is reached.
    if authUsage.nonEmpty then
      depLines += "argon2 = \"0.5\""
      depLines += "jsonwebtoken = \"9\""
      depLines += "serde = { version = \"1\", features = [\"derive\"] }"
    // R.6 — WebSocket deps (tokio-tungstenite + futures-util).
    // Tokio is only added when HTTP is not also present (HTTP already adds it).
    if wsUsage.nonEmpty then
      depLines += "tokio-tungstenite = \"0.21\""
      depLines += "futures-util = \"0.3\""
      if !httpUsage then
        depLines += "tokio = { version = \"1\", features = [\"rt-multi-thread\", \"net\", \"macros\"] }"
    // R.6 — MCP deps: only serde_json (already present when JSON intrinsics used).
    // Do not add a duplicate serde_json when JSON crypto is also used.
    val needsSerdeJson = cryptoUsage.exists(n => n == "jsonParse" || n == "jsonStringify")
    if mcpUsage.nonEmpty && !needsSerdeJson then
      depLines += "serde_json = \"1.0\""
    val deps = if depLines.isEmpty then "" else depLines.mkString("\n") + "\n"
    val target =
      if hasMain then
        s"""
           |[[bin]]
           |name = "$crateName"
           |path = "src/main.rs"
           |""".stripMargin
      else
        s"""
           |[lib]
           |name = "$crateName"
           |path = "src/lib.rs"
           |""".stripMargin
    s"""[package]
       |name = "$crateName"
       |version = "$version"
       |edition = "$CargoEdition"
       |${descrLine}
       |[dependencies]
       |$deps$target""".stripMargin

  /** Detect an `@main` annotation by scanning the module's `scalascript`
   *  / `ssc` fenced blocks textually.  A real AST walk lands in the
   *  hello-code-walk slice; for R.1 the text scan is enough to decide
   *  bin vs lib in `Cargo.toml`. */
  private[rust] def moduleDeclaresMain(module: ir.NormalizedModule): Boolean =
    module.sections.exists(sectionDeclaresMain)

  private def sectionDeclaresMain(section: ir.Section): Boolean =
    section.content.exists(contentDeclaresMain) ||
      section.subsections.exists(sectionDeclaresMain)

  private def contentDeclaresMain(c: ir.Content): Boolean = c match
    case ir.Content.CodeBlock(source, _, _) => sourceHasMain(source)
    case _                                  => false

  /** A `@main` marker is recognised when it appears at column-zero or
   *  after whitespace at the start of a line.  Conservative to avoid
   *  false positives inside string literals or comments. */
  private[rust] def sourceHasMain(source: String): Boolean =
    source.linesIterator.exists(_.stripLeading.startsWith("@main"))

  /** Cargo's package name accepts `[A-Za-z0-9_-]`.  Map ScalaScript
   *  manifest names (which may contain dots, spaces, …) to that
   *  alphabet; collapse anything else into `_`. */
  private[rust] def sanitizeCrateName(raw: String): String =
    // The same name doubles as a Rust module name (`pub mod <name>;`),
    // which forbids hyphens.  Collapse anything outside `[a-z0-9_]` to
    // `_` even though Cargo itself would accept hyphens in package
    // names.
    val cleaned = raw.trim.toLowerCase.map { c =>
      if c.isLetterOrDigit || c == '_' then c else '_'
    }
    val nonEmpty = if cleaned.isEmpty then DefaultCrateName else cleaned
    if nonEmpty.head.isDigit then "_" + nonEmpty else nonEmpty

  /** Escape a TOML basic-string per spec: backslash, double-quote, and
   *  control characters.  Newlines must be escaped because the value
   *  lives inside a single-line `"..."` form. */
  private[rust] def escapeTomlString(s: String): String =
    val sb = new StringBuilder(s.length)
    s.foreach {
      case '\\' => sb.append("\\\\")
      case '"'  => sb.append("\\\"")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c    => sb.append(c)
    }
    sb.toString
