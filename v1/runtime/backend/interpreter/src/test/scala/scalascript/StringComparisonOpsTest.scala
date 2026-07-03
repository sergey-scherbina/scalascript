package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** busi-p1-string-comparison-ops — lexicographic `<`, `<=`, `>`, `>=`,
 *  `compareTo` on `String` were missing from `dispatchString`, throwing
 *  `[ERROR] No method '<=' on StringV(...)` at runtime.  Needed by busi
 *  for UUID v7 time-ordering and sort-key comparisons. */
class StringComparisonOpsTest extends AnyFunSuite:

  private def runProgram(body: String): String =
    val src =
      s"""# T
         |
         |```scalascript
         |$body
         |```
         |""".stripMargin
    val baos = new java.io.ByteArrayOutputStream()
    val ps   = new java.io.PrintStream(baos, true, "UTF-8")
    interpreter.Interpreter(ps).run(Parser.parse(src))
    baos.toString("UTF-8").stripLineEnd

  test("String < — lexicographic less-than"):
    assert(runProgram("""println("apple" < "banana")""") == "true")
    assert(runProgram("""println("banana" < "apple")""") == "false")
    assert(runProgram("""println("apple" < "apple")""")  == "false")

  test("String > — lexicographic greater-than"):
    assert(runProgram("""println("banana" > "apple")""") == "true")
    assert(runProgram("""println("apple" > "banana")""") == "false")
    assert(runProgram("""println("apple" > "apple")""")  == "false")

  test("String <= — lexicographic less-or-equal"):
    assert(runProgram("""println("apple" <= "banana")""") == "true")
    assert(runProgram("""println("apple" <= "apple")""")  == "true")
    assert(runProgram("""println("banana" <= "apple")""") == "false")

  test("String >= — lexicographic greater-or-equal"):
    assert(runProgram("""println("banana" >= "apple")""") == "true")
    assert(runProgram("""println("apple" >= "apple")""")  == "true")
    assert(runProgram("""println("apple" >= "banana")""") == "false")

  test("String.compareTo — returns sign of comparison"):
    assert(runProgram("""println("apple".compareTo("banana") < 0)""")  == "true")
    assert(runProgram("""println("banana".compareTo("apple") > 0)""")  == "true")
    assert(runProgram("""println("apple".compareTo("apple") == 0)""")  == "true")

  test("UUID v7-style ordering via string comparison"):
    // Real busi use case: UUID v7 values are time-orderable as Strings
    // because the timestamp prefix dominates lexicographic order.
    val body =
      """val u1 = "0190a4c5-0000-7000-8000-000000000001"
        |val u2 = "0190a4c5-0001-7000-8000-000000000000"
        |println(u1 < u2)""".stripMargin
    assert(runProgram(body) == "true")

  test("String comparison on identical strings via != still works"):
    // Make sure equality / inequality coexist with the new operators.
    assert(runProgram("""println("x" != "x")""")  == "false")
    assert(runProgram("""println("x" == "x")""")  == "true")

  test("sortBy of strings works correctly"):
    // Indirect verification via List.sortBy — needs ordering.
    val body =
      """val xs = List("banana", "apple", "cherry")
        |val sorted = xs.sortWith((a, b) => a < b)
        |println(sorted)""".stripMargin
    assert(runProgram(body) == "List(apple, banana, cherry)")
