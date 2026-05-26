package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

class GraphInterpreterIntrinsicTest extends AnyFunSuite:

  private def runProgram(ssc: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(ssc))
    ps.flush()
    buf.toString.trim

  test("interpreter Graph facade stores vertices, edges, and typed neighbors"):
    val out = runProgram(
      """# Test
        |
        |```scalascript
        |case class Module(id: String, path: String)
        |case class Imports(from: String, to: String, reason: String)
        |
        |Graph.putVertex("deps", Module("A", "a.ssc"))
        |Graph.putVertex("deps", Module("B", "b.ssc"))
        |Graph.putEdge("deps", Imports("A", "B", "direct"))
        |
        |val imported = Graph.neighbors("deps", "A", Some("imports")).map(_.path).mkString(",")
        |println(s"imports:$imported")
        |```
        |""".stripMargin)

    assert(out == "imports:b.ssc")

  test("interpreter Graph facade stores and loads RDF-shaped values"):
    val out = runProgram(
      """# Test
        |
        |```scalascript
        |case class Person(id: String, name: String)
        |Graph.putRdf("kg", Person("urn:person:1", "Ada"))
        |val loaded = Graph.getRdf("kg", "urn:person:1").get
        |val triples = Graph.triples("kg")
        |println(loaded.name + ":" + triples.size)
        |```
        |""".stripMargin)

    assert(out == "Ada:1")
