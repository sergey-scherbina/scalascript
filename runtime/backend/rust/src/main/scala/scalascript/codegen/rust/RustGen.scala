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
    val cargoToml = renderCargoToml(crateName, version, descr, hasMain)
    val astModule = Denormalize(module)

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
          else renderCargoToml(crateName, version, descr, effectiveBin)
        val generatedMod = renderGeneratedMod(crateName)
        val rootFile     =
          if effectiveBin then renderMainRs(crateName, entry.get)
          else                 renderLibRs()
        val rootName     = if effectiveBin then "src/main.rs" else "src/lib.rs"
        CompileResult.Segmented(List(
          Segment.Asset("Cargo.toml",                   cargoTomlFinal.getBytes("UTF-8"),                    "application/toml"),
          Segment.Asset("src/value.rs",                 RustRuntimeTemplates.ValueRs.getBytes("UTF-8"),      "text/x-rust"),
          Segment.Asset("src/runtime/mod.rs",           RustRuntimeTemplates.RuntimeModRs.getBytes("UTF-8"), "text/x-rust"),
          Segment.Asset("src/generated/mod.rs",         generatedMod.getBytes("UTF-8"),                      "text/x-rust"),
          Segment.Asset(s"src/generated/$crateName.rs", walked.generated.getBytes("UTF-8"),                  "text/x-rust"),
          Segment.Asset(rootName,                       rootFile.getBytes("UTF-8"),                          "text/x-rust")
        ))

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
      crateName: String,
      version:   String,
      descr:     Option[String],
      hasMain:   Boolean
  ): String =
    val descrLine = descr match
      case Some(d) => s"""description = "${escapeTomlString(d)}"
""" // ↵ trailing newline so the block stays uniform
      case None    => ""
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
       |$target""".stripMargin

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
