package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsGen, JsRuntime, JsRuntimeAsync}
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

/** Regression for the JS-backend supertype type-test bug (2026-06-15, found in busi):
 *  `case x: SealedTrait =>` never matched a subtype instance under the JS backend.
 *
 *  Emitted case-class/enum-case objects carry only their own leaf `_type`
 *  (`{_type:'SignalHeadingNode', ...}`). `genPattern`'s `Pat.Typed` branch tested a
 *  non-tagged type name with an exact `_type === 'TkNode'` check — so a supertype test
 *  (`case h: TkNode`) always failed and fell through to the wildcard. busi symptom:
 *  `cardWithHeader(header)` lowers `header match { case h: TkNode => render; case _ => [] }`,
 *  so every card title was silently dropped on every screen in the SPA. This is the JS
 *  analogue of the interpreter/JIT supertype-type-test fix (BUGS #1/#3).
 *
 *  The fix widens a supertype test to the closure of concrete descendants. These run the
 *  generated JS through node and compare against the documented interpreter semantics. */
class SupertypeTypeTestJsTest extends AnyFunSuite with Matchers:

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def runJs(code: String): String =
    val flush = """process.stdout.write(_output.join('\n') + (_output.length ? '\n' : '')); _output = [];"""
    val js   = JsRuntime + "\n" + JsRuntimeAsync + "\n" + JsGen.generate(module(code)) + "\n" + flush
    val tmp  = java.io.File.createTempFile("ssc-supertype-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val proc = ProcessBuilder("node", tmp.getAbsolutePath).redirectErrorStream(true).start()
    val out  = Source.fromInputStream(proc.getInputStream).mkString
    ProcTestUtil.awaitExit(proc)
    out.trim

  test("case x: <sealed trait> matches a direct case-class subtype (the busi card-header repro)"):
    assume(hasNode, "node not available")
    runJs("""
      sealed trait TkNode
      case class HeadingNode(text: String) extends TkNode
      case class TextNode(text: String) extends TkNode
      def isTk(x: Any): String = x match
        case h: TkNode => "tk"
        case _         => "other"
      println(isTk(HeadingNode("hi")))
      println(isTk(TextNode("yo")))
      println(isTk(42))
    """) shouldBe "tk\ntk\nother"

  test("case x: <sealed trait> matches a transitive enum-case subtype"):
    assume(hasNode, "node not available")
    runJs("""
      sealed trait Event
      sealed trait CoreEvent extends Event
      enum LedgerEvent extends CoreEvent:
        case AccountCreated(code: String)
        case Posted(amount: Int)
      def isCore(e: Any): Boolean = e match
        case c: CoreEvent => true
        case _            => false
      def isEvent(e: Any): Boolean = e match
        case x: Event => true
        case _        => false
      println(isCore(LedgerEvent.AccountCreated("1000")))
      println(isEvent(LedgerEvent.Posted(5)))
      println(isCore("nope"))
    """) shouldBe "true\ntrue\nfalse"

  test("case x: <intermediate trait> narrows correctly across a 3-level hierarchy"):
    assume(hasNode, "node not available")
    runJs("""
      sealed trait Shape
      sealed trait Rounded extends Shape
      case class Circle(r: Int) extends Rounded
      case class Square(s: Int) extends Shape
      def kind(x: Any): String = x match
        case r: Rounded => "rounded"
        case s: Shape   => "shape"
        case _          => "other"
      println(kind(Circle(3)))
      println(kind(Square(4)))
      println(kind(7))
    """) shouldBe "rounded\nshape\nother"

  test("supertype type-test binds the value for use in the body"):
    assume(hasNode, "node not available")
    runJs("""
      sealed trait Box
      case class IntBox(n: Int) extends Box
      def describe(x: Any): String = x match
        case b: Box => "box"
        case _      => "?"
      println(describe(IntBox(9)))
    """) shouldBe "box"
