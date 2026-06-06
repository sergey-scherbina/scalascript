package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.ir

/** Emits Cargo crate assets for a `NormalizedModule`.  Phase R.1
 *  (hello-cargo-toml slice): only `Cargo.toml` is emitted; subsequent
 *  slices add `src/value.rs`, `src/runtime/mod.rs`, `src/generated/`,
 *  and `src/main.rs`.  See specs/rust-backend.md. */
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
    val _ = (opts, intrinsics, runtimePreamble) // wired but not consumed before the code-walk slice
    val crateName = sanitizeCrateName(module.manifest.flatMap(_.name).getOrElse(DefaultCrateName))
    val version   = module.manifest.flatMap(_.version).getOrElse(DefaultVersion)
    val descr     = module.manifest.flatMap(_.description).filter(_.nonEmpty)
    val hasMain   = moduleDeclaresMain(module)
    val cargoToml = renderCargoToml(crateName, version, descr, hasMain)
    CompileResult.Segmented(List(
      Segment.Asset(
        name  = "Cargo.toml",
        bytes = cargoToml.getBytes("UTF-8"),
        mime  = "application/toml"
      ),
      Segment.Asset(
        name  = "src/value.rs",
        bytes = RustRuntimeTemplates.ValueRs.getBytes("UTF-8"),
        mime  = "text/x-rust"
      ),
      Segment.Asset(
        name  = "src/runtime/mod.rs",
        bytes = RustRuntimeTemplates.RuntimeModRs.getBytes("UTF-8"),
        mime  = "text/x-rust"
      )
    ))

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
    val cleaned = raw.trim.toLowerCase.map { c =>
      if c.isLetterOrDigit || c == '_' || c == '-' then c else '_'
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
