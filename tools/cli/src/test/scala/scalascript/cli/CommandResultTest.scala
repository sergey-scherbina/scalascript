package scalascript.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CommandResultTest extends AnyFunSuite with Matchers:

  test("legacy CliCommand.runResult defaults to success after run returns"):
    var seenArgs = List.empty[String]
    val cmd = new CliCommand:
      def name = "legacy"
      def run(args: List[String]): Unit =
        seenArgs = args

    cmd.runResult(List("a", "b")) shouldBe CommandResult.Success
    seenArgs shouldBe List("a", "b")

  test("commands can override runResult without calling run"):
    val cmd = new CliCommand:
      def name = "result"
      def run(args: List[String]): Unit =
        fail("run should not be used when runResult is overridden")
      override def runResult(args: List[String]): CommandResult =
        CommandResult.failure(ExitCode.UsageError)

    cmd.runResult(Nil).exitCode shouldBe ExitCode.UsageError

  test("CommandRegistry.dispatchResult returns a result or None for fallback"):
    val baos = new ByteArrayOutputStream()
    val ps   = new PrintStream(baos)
    val helpResult = Console.withOut(ps) {
      CommandRegistry.dispatchResult("help", Nil)
    }

    helpResult shouldBe Some(CommandResult.Success)
    CommandRegistry.dispatchResult("definitely-not-a-command", Nil) shouldBe None
