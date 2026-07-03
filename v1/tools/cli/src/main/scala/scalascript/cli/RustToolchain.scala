package scalascript.cli

/** Shared cargo-presence check + fixed missing-cargo message for the
 *  `build-rust` / `run-rust` CLI commands.  Wording pinned in
 *  `specs/rust-backend.md §10` — any drift is caught by the
 *  `cargo-missing.{build,run}.expected.txt` integration fixtures. */
object RustToolchain:

  /** Probe the user's `PATH` for an executable `cargo`.  Returns the
   *  absolute path when found, `None` otherwise. */
  def findCargo(): Option[os.Path] =
    val sep = java.io.File.pathSeparator
    val ext = if scala.util.Properties.isWin then List(".exe", ".bat", ".cmd", "") else List("")
    val path = Option(System.getenv("PATH")).getOrElse("")
    path
      .split(sep)
      .iterator
      .filter(_.nonEmpty)
      .flatMap { dir =>
        ext.iterator.map(e => os.Path(dir, os.pwd) / s"cargo$e")
      }
      .find(p => os.exists(p) && os.isFile(p))

  /** Fixed message printed when `cargo` is not on `PATH`.  Caller
   *  prefixes the command name (e.g. `ssc build-rust:` or
   *  `ssc run-rust:`) so the user always sees which command produced
   *  the message; nothing else is printed and the process exits 1. */
  val cargoMissingMessage: String =
    """cargo not found on PATH.
      |
      |Rust is not installed on this machine. Install it and re-run the
      |command. Two ways to install:
      |
      |  • Homebrew (macOS):   brew install rust
      |  • Official installer: https://www.rust-lang.org/tools/install
      |""".stripMargin

  /** Print the missing-cargo diagnostic for `cmd` and exit 1.  Does
   *  nothing else — no partial emit, no env dump, no suggestion to
   *  use a different command. */
  def failMissingCargo(cmd: String): Nothing =
    System.err.println(s"ssc $cmd: $cargoMissingMessage")
    sys.exit(1)

  /** Sanitise a `.ssc` file stem the same way RustGen sanitises the
   *  crate name — so callers (`build-rust` / `run-rust`) can predict
   *  the binary's filename inside `target/<profile>/`.  Mirror of
   *  `scalascript.codegen.rust.RustGen.sanitizeCrateName`. */
  def sanitizeBinName(raw: String): String =
    val cleaned = raw.trim.toLowerCase.map { c =>
      if c.isLetterOrDigit || c == '_' then c else '_'
    }
    val nonEmpty = if cleaned.isEmpty then "ssc_program" else cleaned
    if nonEmpty.head.isDigit then "_" + nonEmpty else nonEmpty
