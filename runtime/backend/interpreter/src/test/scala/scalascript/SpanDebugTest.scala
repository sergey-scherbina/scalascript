package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

class SpanDebugTest extends AnyFunSuite:
  test("spanMerge direct debug") {
    val repoRoot = os.pwd / os.up
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = """# Test
[Span, spanEmpty, spanMerge, Node](std/dsl/ast.ssc)

```scalascript
val s1 = Span(0, 5)
println("s1=" + s1)
val s2 = Span(3, 10)
println("s2=" + s2)
val m  = spanMerge(s1, s2)
println("m=" + m)
println("done")
```
"""
    Interpreter(ps, baseDir = Some(repoRoot)).run(Parser.parse(src))
    ps.flush()
    val out = buf.toString.trim
    println(s"OUTPUT: $out")
  }
