package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.ir
import scalascript.transform.Denormalize
import scala.meta as m
import scalascript.ast

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
    val astModule = synthesizeTopLevelEntry(Denormalize(module))
    // R.3.2 — IR walk: which crypto intrinsics does the program reach?
    // Drives both the conditional Cargo deps and the conditional
    // runtime-helper emit so a hello-world stays dep-free.
    val cryptoUsage = scanCryptoUsage(astModule)
    val effectUsage = scanEffectUsage(astModule)
    val httpUsage   = scanHttpUsage(astModule)
    val authUsage   = scanAuthUsage(astModule)
    val wsUsage     = scanWsUsage(astModule)
    val mcpUsage    = scanMcpUsage(astModule)
    val mcpClientUsage = scanMcpClientUsage(astModule)
    // Outbound HTTP client — independent of the http SERVER scan above, so a
    // pure-client program pulls only `ureq` (not hyper/tokio).
    val httpClientUsage = scanHttpClientUsage(astModule)
    // `.matches(` anywhere in a code block. A textual scan like the effect one above: the member is
    // not an intrinsic name, so there is nothing to look up — and over-including costs one unused
    // dependency line in a crate that mentions the word, never a wrong lowering.
    val regexUsage  = scanRegexUsage(astModule)
    // rust-tui-toolkit (S1): `uiTarget=tui` renders the View to ratatui instead of
    // HTML/SSR — `serve` routes to a ratatui run (not a hyper server), so the http
    // server runtime + deps are suppressed and a `tui.rs` + ratatui deps are emitted.
    val tuiTarget   = opts.extra.get("uiTarget").contains("tui")
    // tui.rs always imports `crate::runtime::ui::View`, and an S4 fetch/DataTable
    // program may use no bare View primitive the scan recognises — so the tui target
    // always emits the `ui` module.
    val uiUsage     = scanUiUsage(astModule) || tuiTarget
    val cargoToml   = renderCargoToml(regexUsage, crateName, version, descr, hasMain, cryptoUsage, httpUsage, authUsage, wsUsage, mcpUsage ++ mcpClientUsage, uiUsage, tuiTarget, httpClientUsage)

    val importedDefs =
      opts.extra.get("importedDefs").map(_.split(",").filter(_.nonEmpty).toSet).getOrElse(Set.empty)
    RustCodeWalk.walk(astModule, intrinsics, importedDefs) match
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
          else renderCargoToml(regexUsage, crateName, version, descr, effectiveBin, cryptoUsage, httpUsage, authUsage, wsUsage, mcpUsage ++ mcpClientUsage, uiUsage, tuiTarget, httpClientUsage)
        val generatedMod = renderGeneratedMod(crateName)
        // ```rust blocks, emitted verbatim into `mod inline_native` — the mechanism `Lang.scala`
        // has specified all along and nothing implemented.
        val inlineNativeSrc = collectInlineNative(module)
        val hasInlineNative = inlineNativeSrc.nonEmpty
        val rootFile     =
          if effectiveBin then renderMainRs(crateName, entry.get, hasInlineNative)
          else                 renderLibRs(hasInlineNative)
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
          if httpClientUsage.nonEmpty then
            sb.append("\n// ── outbound HTTP client runtime ──\n")
            sb.append("pub mod http_client;\n")
          // `String.matches` — the helper references the `regex` crate, and that dependency is added
          // only for a program that uses the member, so the helper must be too. Emitting it always
          // put `regex::Regex::new` into every crate and turned twenty-odd std modules that never
          // mention `matches` from COMPILES into BADRUST.
          if regexUsage then sb.append(RustRuntimeTemplates.StrMatchesRs)
          if authUsage.nonEmpty then
            sb.append("\n// ── R.6 — auth runtime ──\n")
            sb.append("pub mod auth;\n")
          if wsUsage.nonEmpty then
            sb.append("\n// ── R.6 — WebSocket runtime ──\n")
            sb.append("pub mod ws;\n")
          if mcpUsage.nonEmpty then
            sb.append("\n// ── R.6 — MCP server runtime ──\n")
            sb.append("pub mod mcp;\n")
          if mcpClientUsage.nonEmpty then
            sb.append("\n// ── R.6 — MCP client runtime ──\n")
            sb.append("pub mod mcp_client;\n")
          if uiUsage then
            sb.append("\n// ── std/ui — SSR View runtime ──\n")
            sb.append("pub mod ui;\n")
          if tuiTarget then
            sb.append("\n// ── rust-tui-toolkit — ratatui View renderer ──\n")
            sb.append("pub mod tui;\n")
          sb.toString
        val baseAssets = List(
          Segment.Asset("Cargo.toml",                   cargoTomlFinal.getBytes("UTF-8"),       "application/toml"),
          Segment.Asset("src/value.rs",                 RustRuntimeTemplates.ValueRs.getBytes("UTF-8"), "text/x-rust"),
          Segment.Asset("src/runtime/mod.rs",           runtimeMod.getBytes("UTF-8"),           "text/x-rust"),
          Segment.Asset("src/generated/mod.rs",         generatedMod.getBytes("UTF-8"),         "text/x-rust"),
          Segment.Asset(s"src/generated/$crateName.rs", walked.generated.getBytes("UTF-8"),     "text/x-rust"),
          Segment.Asset(rootName,                       rootFile.getBytes("UTF-8"),             "text/x-rust")
        )
        // Emitted ONLY when a block exists: an empty `mod inline_native` is dead weight, and an
        // unused module is a rustc warning in every crate that has no such block — which is all of
        // them today.
        val inlineNativeAsset =
          if !hasInlineNative then Nil
          else List(Segment.Asset(
            "src/inline_native.rs",
            (s"//! Verbatim ```rust blocks from the .ssc source.  Emitted by RustGen.\n\n" +
             inlineNativeSrc + "\n").getBytes("UTF-8"),
            "text/x-rust"
          ))
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
            RustRuntimeTemplates.renderTaglessEffectsRs(walked.effectNames, walked.customEffectOps).getBytes("UTF-8"),
            "text/x-rust"
          ))
        val httpAsset =
          if !httpUsage then Nil
          else if tuiTarget then
            // tui target: `serve(view, port)` → `crate::runtime::http::_ui_serve` is a
            // shim that runs the ratatui renderer (no hyper SSR server).
            List(Segment.Asset(
              "src/runtime/http.rs",
              RustRuntimeTemplates.TuiServeShimRs.getBytes("UTF-8"),
              "text/x-rust"
            ))
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
        val mcpClientAsset =
          if mcpClientUsage.isEmpty then Nil
          else List(Segment.Asset(
            "src/runtime/mcp_client.rs",
            RustRuntimeTemplates.McpClientRs.getBytes("UTF-8"),
            "text/x-rust"
          ))
        val mcpAsset =
          if mcpUsage.isEmpty then Nil
          else List(Segment.Asset(
            "src/runtime/mcp.rs",
            RustRuntimeTemplates.McpRs.getBytes("UTF-8"),
            "text/x-rust"
          ))
        val httpClientAsset =
          if httpClientUsage.isEmpty then Nil
          else List(Segment.Asset(
            "src/runtime/http_client.rs",
            RustRuntimeTemplates.HttpClientRs.getBytes("UTF-8"),
            "text/x-rust"
          ))
        val uiAsset =
          if !uiUsage then Nil
          else List(Segment.Asset(
            "src/runtime/ui.rs",
            RustRuntimeTemplates.UiRs.getBytes("UTF-8"),
            "text/x-rust"
          ))
        val tuiAsset =
          if !tuiTarget then Nil
          else List(Segment.Asset(
            "src/runtime/tui.rs",
            RustRuntimeTemplates.TuiRs.getBytes("UTF-8"),
            "text/x-rust"
          ))
        CompileResult.Segmented(baseAssets ++ inlineNativeAsset ++ effectAsset ++ taglessEffectAsset ++ httpAsset ++ httpClientAsset ++ authAsset ++ wsAsset ++ mcpAsset ++ mcpClientAsset ++ uiAsset ++ tuiAsset)

  /** R.3.2 — IR walk for crypto-intrinsic usage.  Returns the set of
   *  intrinsic names actually reached so RustGen can decide which
   *  crates to add to `Cargo.toml` and whether to append the crypto
   *  runtime helpers. */
  /** R.5 — detect `serve` / `route` calls anywhere in the module source.
   *  Triggers the hyper + tokio dep emit and the `src/runtime/http.rs`
   *  asset. */
  /** `String.matches(p)` — a full-match regex test, lowered to `_str_matches`. Keyed on the MEMBER
   *  name rather than on an intrinsic, because it is a member and there is nothing to look up; the
   *  dependency it drives is added only for a program that uses it. Over-including would cost one
   *  unused line in `Cargo.toml`, never a wrong lowering. */
  private[rust] def scanRegexUsage(astModule: scalascript.ast.Module): Boolean =
    val found = scala.collection.mutable.Set.empty[String]
    astModule.sections.foreach(s => scanSectionForNames(s, Set("matches"), found))
    found.nonEmpty

  private[rust] def scanHttpUsage(astModule: scalascript.ast.Module): Boolean =
    val found = scala.collection.mutable.Set.empty[String]
    astModule.sections.foreach(s => scanSectionForNames(s, Set("serve", "route", "requestCookie"), found))
    found.nonEmpty

  /** std/ui — detect `element` / `textNode` / `fragment` View-primitive
   *  usage; triggers the `src/runtime/ui.rs` SSR asset + `pub mod ui`. */
  private[rust] def scanUiUsage(astModule: scalascript.ast.Module): Boolean =
    val found = scala.collection.mutable.Set.empty[String]
    astModule.sections.foreach(s => scanSectionForNames(s,
      Set("element", "textNode", "fragment", "renderHtml", "componentScope"), found))
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
  /** R.6 — scan for MCP CLIENT calls. Separate from `scanMcpUsage` for the same reason
   *  `scanHttpClientUsage` is separate from the server's: a program that only CONSUMES MCP should
   *  not carry the server loop. The member names are scanned unqualified because that is how they
   *  appear at a call site (`c.listToolNames()`); the false positive costs one unused module in a
   *  program that happens to define its own `listToolNames`, and the emitted file is
   *  `#[allow(dead_code)]` throughout. */
  private[rust] def scanMcpClientUsage(astModule: scalascript.ast.Module): Set[String] =
    val names = Set("mcpConnectSpawn", "listToolNames", "callToolText", "readResourceText", "isOpen",
                    "listTools", "listResources", "listPrompts", "callTool", "readResource", "getPrompt")
    val found = scala.collection.mutable.Set.empty[String]
    astModule.sections.foreach(s => scanSectionForNames(s, names, found))
    found.toSet

  private[rust] def scanMcpUsage(astModule: scalascript.ast.Module): Set[String] =
    val names = Set("mcpRegisterTool", "mcpRegisterResource", "mcpRegisterPrompt", "mcpServe")
    val found = scala.collection.mutable.Set.empty[String]
    astModule.sections.foreach(s => scanSectionForNames(s, names, found))
    found.toSet

  /** Scan for outbound HTTP-client intrinsic calls. Separate from
   *  `scanHttpUsage` (server) so a pure-client program pulls only `ureq`, not
   *  hyper/tokio. Non-empty emits `src/runtime/http_client.rs` + the ureq dep. */
  private[rust] def scanHttpClientUsage(astModule: scalascript.ast.Module): Set[String] =
    val names = Set("httpGet", "httpPost", "httpPut", "httpPatch", "httpDelete",
                    "httpClient", "httpTimeout", "httpRetry")
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
  private[rust] def renderMainRs(crateName: String, entry: String, inlineNative: Boolean = false): String =
    s"""//! Crate entry point.  Emitted by RustGen; do not edit by hand.
       |
       |mod runtime;
       |mod value;
       |mod generated;
       |${if inlineNative then "mod inline_native;\n" else ""}
       |fn main() {
       |    generated::$crateName::$entry();
       |}
       |""".stripMargin

  /** `src/lib.rs` for a library crate (no `@main` in the source). */
  private[rust] def renderLibRs(inlineNative: Boolean = false): String =
    s"""//! Crate library root.  Emitted by RustGen; do not edit by hand.
       |
       |pub mod runtime;
       |pub mod value;
       |pub mod generated;
       |${if inlineNative then "pub mod inline_native;\n" else ""}""".stripMargin

  /** Render the `Cargo.toml` text for a crate with no dependencies and
   *  a single `[[bin]]` entry when `hasMain` is true, or a `[lib]` entry
   *  otherwise.  Format is fixed so goldens stay stable across builds. */
  private[rust] def renderCargoToml(
      // `String.matches` drives a `regex` dependency, added only for a program that uses it — the
      // same rule the http-client and server deps follow. Threaded rather than re-scanned: the scan
      // belongs where every other one already happens.
      regexUsage:  Boolean,
      crateName:   String,
      version:     String,
      descr:       Option[String],
      hasMain:     Boolean,
      cryptoUsage: Set[String] = Set.empty,
      httpUsage:   Boolean     = false,
      authUsage:   Set[String] = Set.empty,
      wsUsage:     Set[String] = Set.empty,
      mcpUsage:    Set[String] = Set.empty,
      uiUsage:     Boolean     = false,
      tuiTarget:   Boolean     = false,
      httpClientUsage: Set[String] = Set.empty
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
    // rust-tui-toolkit — a tui-target UI program renders to the terminal, so it
    // depends on ratatui (not the hyper/tokio HTTP server) and `serve` is a shim.
    if tuiTarget then
      depLines += "ratatui = \"0.29\""
      // S4 — the tui DataTable fetches (blocking GET) + drills/renders JSON rows.
      depLines += "ureq = \"2\""
      if !depLines.exists(_.startsWith("serde_json")) then depLines += "serde_json = \"1.0\""
    // R.5 — HTTP server deps, only when serve/route are used (NOT on the tui target,
    // where `serve` is the ratatui run shim).
    if httpUsage && !tuiTarget then
      // `sync` → broadcast channel for the SSE push transport (/__ssc/events);
      // tokio-stream wraps the broadcast receiver as a Stream for StreamBody.
      depLines += "tokio = { version = \"1\", features = [\"rt-multi-thread\", \"net\", \"macros\", \"sync\"] }"
      depLines += "tokio-stream = { version = \"0.1\", features = [\"sync\"] }"
      depLines += "hyper = { version = \"1\", features = [\"server\", \"http1\"] }"
      depLines += "hyper-util = { version = \"0.1\", features = [\"tokio\"] }"
      depLines += "http-body-util = \"0.1\""
      depLines += "bytes = \"1\""
    // R.6 — auth deps, only when at least one auth intrinsic is reached.
    if authUsage.nonEmpty then
      // `password_hash::rand_core::OsRng` — which AuthRs imports to generate a salt — is re-exported
      // only with the `rand` feature. Without it the emitted crate does not build at all:
      // `error[E0432]: unresolved import argon2::password_hash::rand_core::OsRng, no OsRng in the
      // root`, in std/auth.ssc. `std` comes with it in argon2 0.5 but is named for the same reason
      // the others are: a default that changes is a build that breaks with no local cause.
      depLines += "argon2 = { version = \"0.5\", features = [\"std\", \"password-hash\", \"rand\"] }"
      depLines += "jsonwebtoken = \"9\""
      depLines += "serde = { version = \"1\", features = [\"derive\"] }"
    // R.6 — WebSocket deps (tokio-tungstenite + futures-util). Also added for
    // a `serve(view, …)` UI program: it exposes a direct-WS signal endpoint.
    // Dedup is by the Set below; tokio is added by the http/ui path already.
    if (wsUsage.nonEmpty || uiUsage) && !tuiTarget then
      depLines += "tokio-tungstenite = \"0.21\""
      depLines += "futures-util = \"0.3\""
      if !httpUsage then
        depLines += "tokio = { version = \"1\", features = [\"rt-multi-thread\", \"net\", \"macros\"] }"
    // R.6 — MCP deps: only serde_json (already present when JSON intrinsics used).
    // Do not add a duplicate serde_json when JSON crypto is also used.
    val needsSerdeJson = cryptoUsage.exists(n => n == "jsonParse" || n == "jsonStringify")
    if mcpUsage.nonEmpty && !needsSerdeJson then
      depLines += "serde_json = \"1.0\""
    // Outbound HTTP client — blocking `ureq` (rustls TLS). Independent of the http
    // server; dedup with the tui path which also adds ureq.
    if httpClientUsage.nonEmpty && !depLines.exists(_.startsWith("ureq")) then
      depLines += "ureq = \"2\""
    // `String.matches` is a full-match REGEX test, lowered to `_str_matches` in the runtime. The
    // dependency is added only for a program that uses it — the same rule `ureq` and the http server
    // deps follow, so a hello-world crate does not pay for a regex engine it never calls.
    if regexUsage then depLines += "regex = \"1\""
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
    // ScalaScript `Int`/`Long` are 64-bit **wrapping** (Java `Long` semantics; the interpreter,
    // JVM and JS all wrap on overflow). Rust `i64` `*`/`+`/`-` panic on overflow in `cargo` *debug*
    // builds (release already wraps). Turn overflow checks off in both profiles so emitted programs
    // match the other backends instead of debug-panicking (BACKLOG `rust-long-wrapping-arithmetic`).
    val profiles =
      """|
         |[profile.dev]
         |overflow-checks = false
         |
         |[profile.release]
         |overflow-checks = false
         |""".stripMargin
    s"""[package]
       |name = "$crateName"
       |version = "$version"
       |edition = "$CargoEdition"
       |${descrLine}
       |[dependencies]
       |$deps$target$profiles""".stripMargin

  /** Detect an `@main` annotation by scanning the module's `scalascript`
   *  / `ssc` fenced blocks textually.  A real AST walk lands in the
   *  hello-code-walk slice; for R.1 the text scan is enough to decide
   *  bin vs lib in `Cargo.toml`. */
  /** Bare top-level statements ARE a program, and every other lane runs them.
    *
    * This backend walks only top-level `def`s, so a source that is just statements produced an
    * EMPTY generated module and a `[lib]` crate — `run-rust` then had nothing to run. Rather than
    * teach the walker a second shape, the statements become the body of a synthesized
    * `def main(): Unit`, so entry detection, `[[bin]]`, `src/main.rs` and top-val inlining all
    * apply unchanged.
    *
    * ONLY WHEN THE PROGRAM HAS NO ENTRY POINT OF ITS OWN. A file with `@main` or a zero-argument
    * `def main` keeps it; synthesizing a second one would emit two candidates and pick by accident.
    *
    * TOP-LEVEL `val`s ARE LEFT ALONE, deliberately. They are already collected as `topVals` and
    * inlined into every def that references them, so moving them inside the synthetic `main` would
    * take them away from the other defs — the statements move, the bindings do not.
    */
  private[rust] def synthesizeTopLevelEntry(module: ast.Module): ast.Module =
    def topStats(node: ast.ScalaNode): List[m.Tree] = node.tree match
      case m.Source(stats)     => stats.toList
      case m.Term.Block(stats) => stats.toList
      case single              => List(single)

    def isEntry(t: m.Tree): Boolean = t match
      case d: m.Defn.Def =>
        d.mods.exists {
          case m.Mod.Annot(m.Init.After_4_6_0(m.Type.Name("main"), _, _)) => true
          case _                                                          => false
        } || (d.name.value == "main" && d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).isEmpty)
      case _ => false

    // A STATEMENT here is a term that is not a declaration: `println(x)`, `xs.foreach(…)`, an `if`
    // used for effect. Definitions, imports and package wrappers are not.
    def isStatement(t: m.Tree): Boolean = t match
      case _: m.Defn | _: m.Decl | _: m.Import | _: m.Pkg => false
      case _: m.Term                                      => true
      case _                                              => false

    val blocks = module.sections.flatMap(sectionBlocks)
    if blocks.exists(b => b.tree.exists(n => topStats(n).exists(isEntry))) then module
    else
      val statements = blocks.flatMap(b => b.tree.toList.flatMap(topStats)).filter(isStatement)
      if statements.isEmpty then module
      else
        val body = m.Term.Block(statements.collect { case t: m.Term => t })
        val synthetic = m.Defn.Def(
          mods = Nil,
          name = m.Term.Name("main"),
          paramClauseGroups = List(m.Member.ParamClauseGroup(m.Type.ParamClause(Nil), List(m.Term.ParamClause(Nil)))),
          decltpe = Some(m.Type.Name("Unit")),
          body = body,
        )
        var appended = false
        def rewriteBlock(b: ast.Content): ast.Content = b match
          case cb: ast.Content.CodeBlock if !appended && cb.tree.exists(n => topStats(n).exists(isStatement)) =>
            cb.tree match
              case Some(node) =>
                appended = true
                // Keep the statements where they are AND add the entry: the walker ignores bare
                // statements, so leaving them costs nothing and keeps the block's source honest.
                cb.copy(tree = Some(ast.ScalaNode(m.Source(topStats(node).collect { case s: m.Stat => s } :+ synthetic))))
              case None => cb
          case other => other
        module.copy(sections = module.sections.map(rewriteSection(_, rewriteBlock)))

  private def sectionBlocks(section: ast.Section): List[ast.Content.CodeBlock] =
    section.content.collect { case cb: ast.Content.CodeBlock if isScalaFence(cb.lang) => cb } ++
      section.subsections.flatMap(sectionBlocks)

  private def rewriteSection(section: ast.Section, f: ast.Content => ast.Content): ast.Section =
    section.copy(
      content = section.content.map {
        case cb: ast.Content.CodeBlock if isScalaFence(cb.lang) => f(cb)
        case other                                             => other
      },
      subsections = section.subsections.map(rewriteSection(_, f)),
    )

  private def isScalaFence(lang: String): Boolean =
    val l = lang.trim.toLowerCase
    l == "scala" || l == "scalascript" || l == "ssc" || l.isEmpty

  /** Concatenate the source of every ```` ```rust ```` `EmbeddedBlock`, in document order.
    *
    *  `Lang.scala` has specified this since the block language was introduced — "`rust` — Rust
    *  source for the Rust backend. Emitted verbatim into `mod inline_native` in the generated
    *  crate" — and nothing implemented it: `RustCapabilities.blockLanguages` was empty, so
    *  `CapabilityCheck` answered `UnknownBlockLanguage(rust)` and the block never reached here.
    *  `examples/rust/effect-runtime.ssc` and `mixed.ssc` have carried such a block, and said so in
    *  their prose, since 2026-06.
    *
    *  The shape is `NodeBackend.collectNodeGlue`'s, deliberately: same walk, same document order,
    *  same "empty string when there is no such block" so a module without one is byte-identical to
    *  before. Verbatim means verbatim — no interpolation, no parsing; the block is the author's
    *  Rust, and `rustc` is the thing that judges it. */
  private[rust] def collectInlineNative(module: ir.NormalizedModule): String =
    val sb = StringBuilder()

    def walkContent(c: ir.Content): Unit = c match
      case ir.Content.EmbeddedBlock(language, source, _, _) if ast.Lang.isRust(language) =>
        if sb.nonEmpty then sb.append("\n")
        sb.append(source.stripTrailing())
      case _ => ()

    def walkSection(s: ir.Section): Unit =
      s.content.foreach(walkContent)
      s.subsections.foreach(walkSection)

    module.sections.foreach(walkSection)
    sb.toString

  private[rust] def moduleDeclaresMain(module: ir.NormalizedModule): Boolean =
    module.sections.exists(sectionDeclaresMain)

  private def sectionDeclaresMain(section: ir.Section): Boolean =
    section.content.exists(contentDeclaresMain) ||
      section.subsections.exists(sectionDeclaresMain)

  private def contentDeclaresMain(c: ir.Content): Boolean = c match
    case ir.Content.CodeBlock(source, _, _, _) => sourceHasMain(source)
    case _                                  => false

  /** A `@main` marker is recognised when it appears at column-zero or
   *  after whitespace at the start of a line.  Conservative to avoid
   *  false positives inside string literals or comments. */
  private[rust] def sourceHasMain(source: String): Boolean =
    source.linesIterator.exists { line =>
      val l = line.stripLeading
      // `def main` counts too — it is the entry point every other lane uses. This is only a HINT
      // (the AST walk below decides, and re-renders Cargo.toml when the two disagree), but a hint
      // that is right avoids rendering the manifest twice for every ordinary program.
      l.startsWith("@main") || l.startsWith("def main(") || l.startsWith("def main:")
    }

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
