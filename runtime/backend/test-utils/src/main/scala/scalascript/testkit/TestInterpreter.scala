package scalascript.testkit

import scalascript.backend.spi.Backend
import scalascript.interpreter.{Interpreter, Value}
import scalascript.parser.Parser

/** Small test harness for interpreter intrinsic plugins.
 *
 *  It installs only the explicitly supplied plugins, then runs a snippet as a
 *  normal ScalaScript module and returns the interpreter's last expression
 *  result converted to plain Scala values where practical.
 */
final class TestInterpreter(
    plugins: List[Backend],
    out: java.io.PrintStream = TestInterpreter.nullPrintStream
):
  def eval(snippet: String): Any =
    val interp = Interpreter(out = out)
    interp.installPlugins(plugins)
    interp.run(Parser.parse(s"# Test\n\n```scala\n$snippet\n```\n"))
    TestInterpreter.unwrap(interp.lastResult)

object TestInterpreter:
  private val nullPrintStream: java.io.PrintStream =
    new java.io.PrintStream(java.io.OutputStream.nullOutputStream())

  def apply(plugins: List[Backend]): TestInterpreter =
    new TestInterpreter(plugins)

  def unwrap(value: Value): Any = value match
    case Value.IntV(n)       => n
    case Value.DoubleV(d)    => d
    case Value.StringV(s)    => s
    case Value.BoolV(b)      => b
    case Value.UnitV         => ()
    case Value.ListV(items)  => items.map(unwrap)
    case Value.TupleV(items) => items.map(unwrap)
    case Value.MapV(entries) =>
      entries.map { case (k, v) => unwrap(k) -> unwrap(v) }
    case Value.OptionV(opt)  => opt.map(unwrap)
    case other               => other
