package scalascript.cli

/** A single `ssc` subcommand. See docs/cli-command-spi.md.
 *
 *  Providers are discovered via `ServiceLoader`, so each must be a concrete
 *  class with a public no-arg constructor — a Scala `object` cannot be loaded
 *  by ServiceLoader. */
trait CliCommand:
  /** Primary subcommand token, e.g. "build". */
  def name: String

  /** Additional tokens that map to this command, e.g. "--help", "-h". */
  def aliases: List[String] = Nil

  /** One-line summary for `ssc help`. May be empty during incremental migration. */
  def summary: String = ""

  /** Grouping bucket for `ssc help`, e.g. "Run & develop". Commands with the
   *  same category are listed together; see `Help.categoryOrder`. */
  def category: String = "Other"

  /** Extra indented lines shown under the command in `ssc help` (typically
   *  `Flags: …`). Empty for commands whose summary says it all. */
  def details: List[String] = Nil

  /** True for commands that shouldn't appear in the `ssc help` listing
   *  (alias-only or meta tokens like `--list-backends`). */
  def hidden: Boolean = false

  /** Execute with the post-subcommand argument list (i.e. `args.tail`).
   *
   *  This compatibility method remains the public command surface while the
   *  in-tree CLI incrementally migrates to `runResult`.
   */
  def run(args: List[String]): Unit

  /** Execute and return an internal command result.
   *
   *  Existing commands inherit the legacy behavior: call `run(args)` and report
   *  success if it returns. Commands that no longer need direct `System.exit`
   *  override this method first; the public command SPI can stay unchanged.
   */
  def runResult(args: List[String]): CommandResult =
    run(args)
    CommandResult.Success
