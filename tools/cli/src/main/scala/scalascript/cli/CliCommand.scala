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

  /** Execute with the post-subcommand argument list (i.e. `args.tail`). */
  def run(args: List[String]): Unit
