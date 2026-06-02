package scalascript.cli

/** Internal CLI exit-code wrapper.
 *
 *  This is intentionally not part of the plugin command SPI yet; it lets the
 *  in-tree launcher and commands migrate away from direct `System.exit` calls
 *  incrementally while preserving the current `CliCommand.run(args): Unit`
 *  compatibility surface.
 */
final case class ExitCode(value: Int) extends AnyVal:
  def isSuccess: Boolean = value == 0

object ExitCode:
  val Success:      ExitCode = ExitCode(0)
  val GeneralError: ExitCode = ExitCode(1)
  val UsageError:   ExitCode = ExitCode(2)

final case class CommandResult(exitCode: ExitCode):
  def isSuccess: Boolean = exitCode.isSuccess

  /** Preserve launcher behavior: successful commands return normally; failures
   *  terminate the process with the command's exit code. */
  def exitIfFailure(): Unit =
    if !isSuccess then System.exit(exitCode.value)

  /** For compatibility paths that historically called `System.exit` even for
   *  success, such as `ssc lsp` when invoked through `run(args)`. */
  def exitNow(): Unit =
    System.exit(exitCode.value)

object CommandResult:
  val Success: CommandResult = CommandResult(ExitCode.Success)

  def exit(code: Int): CommandResult =
    CommandResult(ExitCode(code))

  def failure(code: ExitCode = ExitCode.GeneralError): CommandResult =
    CommandResult(code)
