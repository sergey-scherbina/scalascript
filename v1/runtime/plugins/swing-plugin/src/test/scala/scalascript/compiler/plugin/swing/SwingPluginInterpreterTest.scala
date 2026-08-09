package scalascript.compiler.plugin.swing

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.CompileResult
import scalascript.parser.Parser
import scalascript.testkit.TestInterpreter
import scalascript.transform.Normalize

class SwingPluginInterpreterTest extends AnyFunSuite:

  test("Swing plugin is an interpreter-only placeholder with no intrinsics yet"):
    val plugin = SwingInterpreterPlugin()

    assert(plugin.id == "scalascript-swing-interpreter")
    assert(plugin.displayName == "Swing Intrinsics (Interpreter)")
    assert(plugin.intrinsics.isEmpty)
    assert(SwingIntrinsics.table.isEmpty)

    val compiled = plugin.compile(Normalize(Parser.parse("# Test\n\n```scala\n1\n```")), scalascript.backend.spi.BackendOptions())
    assert(compiled.isInstanceOf[CompileResult.Failed])

  test("Swing plugin can be installed through the test interpreter as a no-op"):
    val result = TestInterpreter(List(SwingInterpreterPlugin())).eval("1 + 2")

    assert(result == 3L)
