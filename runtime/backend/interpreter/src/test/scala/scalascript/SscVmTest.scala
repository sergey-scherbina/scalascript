package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.interpreter.Value
import scalascript.interpreter.vm.{SscVm, VmCompiler}
import scalascript.interpreter.vm.jit.{AsmJitBackend, JavacJitBackend, JitGlobals, LongFn1, ObjToLong, ObjToObject, WhileJitEntry}
import scalascript.ast.{Content, ScalaNode}
import scalascript.parser.Parser
import scala.meta.{Source, Term}

/** Verifies the proof-of-concept bytecode VM (specs/vm-jit-spec.md):
 *  compiling real parsed integer functions and checking VM results equal
 *  the known mathematical values, plus that unsupported functions bail to None.
 */
class SscVmTest extends AnyFunSuite with Matchers:

  private val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())

  /** Define top-level `defs` (no call), return the named closure. */
  private def funOf(name: String, defs: String): Value.FunV =
    val interp = Interpreter(devNull)
    interp.run(Parser.parse(s"# T\n\n```scala\n$defs\n```\n"))
    interp.globalsView(name).asInstanceOf[Value.FunV]

  /** Define top-level `defs`, return the interpreter so callers can build a
   *  resolver over its globals (for mutual / sibling-call compilation). */
  private def interpOf(defs: String): Interpreter =
    val interp = Interpreter(devNull)
    interp.run(Parser.parse(s"# T\n\n```scala\n$defs\n```\n"))
    interp

  /** Resolve a free name to a sibling/top-level FunV from `interp`'s globals. */
  private def globalsResolve(interp: Interpreter): VmCompiler.Resolve =
    (_, name) =>
      interp.globalsView.get(name) match
        case Some(fv: Value.FunV) => fv
        case _                    => null

  private def whileParts(defs: String, whileSrc: String): (Interpreter, Term.While, Array[String], Array[Term]) =
    val interp = interpOf(defs)
    val module = Parser.parse(s"# T\n\n```scala\n$defs\n$whileSrc\n```\n")
    val w = firstWhile(module)
    val assigns: List[Term.Assign] = w.body match
      case b: Term.Block => b.stats.collect { case a: Term.Assign => a }
      case a: Term.Assign => List(a)
      case _ => fail(s"Unsupported while body: ${w.body}")
    val names = assigns.map {
      case Term.Assign(Term.Name(n), _) => n
      case other => fail(s"Unsupported assignment lhs: $other")
    }.toArray
    val rhs = assigns.map(_.rhs).toArray
    (interp, w, names, rhs)

  private def firstWhile(module: scalascript.ast.Module): Term.While =
    var found: Term.While | Null = null
    def loop(t: scala.meta.Tree): Unit =
      if found == null then t match
        case w: Term.While => found = w
        case _             => t.children.foreach(loop)
    module.sections.foreach { section =>
      section.content.foreach {
        case cb: Content.CodeBlock =>
          cb.tree.foreach(node => ScalaNode.fold(node) {
            case Source(stats)     => stats.foreach(loop)
            case Term.Block(stats) => stats.foreach(loop)
            case other             => loop(other)
          })
        case _ => ()
      }
    }
    if found != null then found.asInstanceOf[Term.While] else fail("No while term found")

  private def mixedWhileParts(defs: String, whileSrc: String): (Interpreter, Term.While, Term, Array[String], Array[Term]) =
    val interp = interpOf(defs)
    val module = Parser.parse(s"# T\n\n```scala\n$defs\n$whileSrc\n```\n")
    val w = firstWhile(module)
    val stats = w.body match
      case b: Term.Block => b.stats
      case other         => fail(s"Unsupported mixed while body: $other")
    val foreachApply = stats.collectFirst {
      case ap: Term.Apply =>
        ap.fun match
          case Term.Select(_, Term.Name("foreach")) => ap
          case _                                    => null
    }.orNull
    if foreachApply == null then fail("No foreach apply found")
    val assigns = stats.collect { case a: Term.Assign => a }
    val names = assigns.map {
      case Term.Assign(Term.Name(n), _) => n
      case other => fail(s"Unsupported assignment lhs: $other")
    }.toArray
    val rhs = assigns.map(_.rhs).toArray
    (interp, w, foreachApply, names, rhs)

  private def resolveWhileRefs(entry: WhileJitEntry, interp: Interpreter): Array[AnyRef] =
    entry.refNames.map { name =>
      if name.indexOf('.') >= 0 then fail(s"Direct test resolver only supports simple ref globals, got `$name`")
      interp.globalsView.getOrElse(name, null) match
        case inst: Value.InstanceV => inst.asInstanceOf[AnyRef]
        case other => fail(s"Ref `$name` did not resolve to InstanceV: $other")
    }

  private def runWhileEntry(entry: WhileJitEntry, interp: Interpreter, slots: Array[Long]): Unit =
    JitGlobals.withInterp(interp) {
      JitGlobals.withRefs(resolveWhileRefs(entry, interp), entry.refFns, entry.refObjFns) {
        entry.runFn.run(slots)
      }
    }

  private def runMixedEntry(entry: WhileJitEntry, interp: Interpreter, receiverName: String, slots: Array[Long]): Unit =
    val receiver = interp.globalsView(receiverName) match
      case mv: Value.MapV =>
        val arr =
          if entry.mapIsKeyMode then mv.entries.keysIterator.toArray
          else mv.entries.valuesIterator.toArray
        arr.asInstanceOf[AnyRef]
      case lv: Value.ListV if entry.listPreExtract =>
        lv.items.toArray[AnyRef].asInstanceOf[AnyRef]
      case other => other.asInstanceOf[AnyRef]
    JitGlobals.withInterp(interp) {
      JitGlobals.withRefs(Array(receiver), entry.refFns, Array.empty[ObjToObject], entry.refDoubleFns) {
        entry.runFn.run(slots)
      }
    }

  test("compiles and runs recursive fib") {
    val fib = funOf("fib",
      """def fib(n: Int): Int =
        |  if n <= 1 then n else fib(n - 1) + fib(n - 2)""".stripMargin)
    val cfn = VmCompiler.compile(fib)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(10L)) shouldBe 55L
    SscVm.run(cfn.get, Array(20L)) shouldBe 6765L
    SscVm.run(cfn.get, Array(30L)) shouldBe 832040L
  }

  test("compiles and runs two-arg tail-recursive sum") {
    val f = funOf("sumTco",
      """def sumTco(n: Int, acc: Int): Int =
        |  if n <= 0 then acc else sumTco(n - 1, acc + n)""".stripMargin)
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(100L, 0L)) shouldBe 5050L
    // Tail recursion is compiled to a loop, so deep input needs no extra stack.
    SscVm.run(cfn.get, Array(100000L, 0L)) shouldBe 5000050000L
  }

  test("handles arithmetic, division and modulo") {
    val f = funOf("g",
      """def g(x: Int): Int =
        |  if x % 2 == 0 then x / 2 else x * 3 + 1""".stripMargin)
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(10L)) shouldBe 5L
    SscVm.run(cfn.get, Array(7L))  shouldBe 22L
  }

  test("compiles a three-argument integer function") {
    val f = funOf("f3",
      """def f3(a: Int, b: Int, c: Int): Int = a * 100 + b * 10 + c""".stripMargin)
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(1L, 2L, 3L)) shouldBe 123L
    SscVm.run(cfn.get, Array(9L, 0L, 7L)) shouldBe 907L
  }

  test("compiles a call to another integer function via the resolver") {
    val interp = interpOf(
      """def dbl(x: Int): Int = x * 2
        |def useDbl(n: Int): Int = dbl(n) + dbl(n + 1)""".stripMargin)
    val useDbl = interp.globalsView("useDbl").asInstanceOf[Value.FunV]
    val cfn = VmCompiler.compile(useDbl, globalsResolve(interp))
    cfn shouldBe defined
    // useDbl(3) = dbl(3) + dbl(4) = 6 + 8 = 14
    SscVm.run(cfn.get, Array(3L)) shouldBe 14L
  }

  test("compiles mutual recursion (isEven / isOdd)") {
    val interp = interpOf(
      """def isEven(n: Int): Int = if n == 0 then 1 else isOdd(n - 1)
        |def isOdd(n: Int): Int  = if n == 0 then 0 else isEven(n - 1)""".stripMargin)
    val isEven = interp.globalsView("isEven").asInstanceOf[Value.FunV]
    val cfn = VmCompiler.compile(isEven, globalsResolve(interp))
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(10L)) shouldBe 1L
    SscVm.run(cfn.get, Array(7L))  shouldBe 0L
    SscVm.run(cfn.get, Array(0L))  shouldBe 1L
  }

  test("Javac bytecode JIT co-emits a sibling pure-int function") {
    val interp = interpOf(
      """def dbl(x: Int): Int = x * 2
        |def useDbl(n: Int): Int = dbl(n) + dbl(n + 1)""".stripMargin)
    val useDbl = interp.globalsView("useDbl").asInstanceOf[Value.FunV]
    val jitR = JavacJitBackend.tryCompile(useDbl, interp)
    jitR should not be null
    jitR.paramIsRef shouldBe Array(false)
    jitR.resultIsDouble shouldBe false
    jitR.direct.isInstanceOf[LongFn1] shouldBe true
    jitR.direct.asInstanceOf[LongFn1].apply(3L) shouldBe 14L
  }

  test("Javac bytecode JIT co-emits mutually recursive pure-int functions") {
    val interp = interpOf(
      """def isEven(n: Int): Int = if n == 0 then 1 else isOdd(n - 1)
        |def isOdd(n: Int): Int  = if n == 0 then 0 else isEven(n - 1)""".stripMargin)
    val isEven = interp.globalsView("isEven").asInstanceOf[Value.FunV]
    val jitR = JavacJitBackend.tryCompile(isEven, interp)
    jitR should not be null
    jitR.paramIsRef shouldBe Array(false)
    jitR.resultIsDouble shouldBe false
    jitR.direct.isInstanceOf[LongFn1] shouldBe true
    val direct = jitR.direct.asInstanceOf[LongFn1]
    direct.apply(10L) shouldBe 1L
    direct.apply(7L) shouldBe 0L
    direct.apply(0L) shouldBe 1L
  }

  test("Javac bytecode JIT co-emits mutually recursive ref-param match functions") {
    val interp = interpOf(
      """sealed trait Chain
        |case class Leaf(n: Int) extends Chain
        |case class Link(next: Chain) extends Chain
        |def a(c: Chain): Int = c match
        |  case Leaf(n)  => n
        |  case Link(x)  => b(x) + 1
        |def b(c: Chain): Int = c match
        |  case Leaf(n)  => n * 2
        |  case Link(x)  => a(x) + 2
        |val chain = Link(Link(Leaf(5)))""".stripMargin)
    val a = interp.globalsView("a").asInstanceOf[Value.FunV]
    val chain = interp.globalsView("chain").asInstanceOf[AnyRef]
    val jitR = JavacJitBackend.tryCompile(a, interp)
    jitR should not be null
    jitR.paramIsRef shouldBe Array(true)
    jitR.resultIsDouble shouldBe false
    jitR.direct.isInstanceOf[ObjToLong] shouldBe true
    jitR.direct.asInstanceOf[ObjToLong].apply(chain) shouldBe 8L
  }

  test("ASM bytecode JIT handles unary ops and block expressions") {
    val interp = interpOf(
      """def adjusted(n: Int): Int =
        |  val base = -n
        |  val bump = +3
        |  base + bump""".stripMargin)
    val adjusted = interp.globalsView("adjusted").asInstanceOf[Value.FunV]
    val jitR = AsmJitBackend.tryCompile(adjusted, interp)
    jitR should not be null
    jitR.paramIsRef shouldBe Array(false)
    jitR.resultIsDouble shouldBe false
    jitR.direct.isInstanceOf[LongFn1] shouldBe true
    jitR.direct.asInstanceOf[LongFn1].apply(10L) shouldBe -7L
  }

  test("ASM bytecode JIT co-emits a sibling pure-int function") {
    val interp = interpOf(
      """def dbl(x: Int): Int = x * 2
        |def useDbl(n: Int): Int = dbl(n) + dbl(n + 1)""".stripMargin)
    val useDbl = interp.globalsView("useDbl").asInstanceOf[Value.FunV]
    val jitR = AsmJitBackend.tryCompile(useDbl, interp)
    jitR should not be null
    jitR.paramIsRef shouldBe Array(false)
    jitR.resultIsDouble shouldBe false
    jitR.direct.isInstanceOf[LongFn1] shouldBe true
    jitR.direct.asInstanceOf[LongFn1].apply(3L) shouldBe 14L
  }

  test("ASM bytecode JIT co-emits mutually recursive pure-int functions") {
    val interp = interpOf(
      """def isEven(n: Int): Int = if n == 0 then 1 else isOdd(n - 1)
        |def isOdd(n: Int): Int  = if n == 0 then 0 else isEven(n - 1)""".stripMargin)
    val isEven = interp.globalsView("isEven").asInstanceOf[Value.FunV]
    val jitR = AsmJitBackend.tryCompile(isEven, interp)
    jitR should not be null
    jitR.paramIsRef shouldBe Array(false)
    jitR.resultIsDouble shouldBe false
    jitR.direct.isInstanceOf[LongFn1] shouldBe true
    val direct = jitR.direct.asInstanceOf[LongFn1]
    direct.apply(10L) shouldBe 1L
    direct.apply(7L) shouldBe 0L
    direct.apply(0L) shouldBe 1L
  }

  test("ASM bytecode JIT co-emits mutually recursive ref-param match functions") {
    val interp = interpOf(
      """sealed trait Chain
        |case class Leaf(n: Int) extends Chain
        |case class Link(next: Chain) extends Chain
        |def a(c: Chain): Int = c match
        |  case Leaf(n)  => n
        |  case Link(x)  => b(x) + 1
        |def b(c: Chain): Int = c match
        |  case Leaf(n)  => n * 2
        |  case Link(x)  => a(x) + 2
        |val chain = Link(Link(Leaf(5)))""".stripMargin)
    val a = interp.globalsView("a").asInstanceOf[Value.FunV]
    val chain = interp.globalsView("chain").asInstanceOf[AnyRef]
    val jitR = AsmJitBackend.tryCompile(a, interp)
    jitR should not be null
    jitR.paramIsRef shouldBe Array(true)
    jitR.resultIsDouble shouldBe false
    jitR.direct.isInstanceOf[ObjToLong] shouldBe true
    jitR.direct.asInstanceOf[ObjToLong].apply(chain) shouldBe 8L
  }

  test("ASM bytecode JIT compiles ref-returning matches as ObjToObject") {
    val interp = interpOf(
      """sealed trait Chain
        |case class Leaf(n: Int) extends Chain
        |case class Link(next: Chain) extends Chain
        |def unwrap(c: Chain): Chain = c match
        |  case Link(x) => x
        |  case x       => x
        |val leaf = Leaf(7)
        |val chain = Link(leaf)""".stripMargin)
    val unwrap = interp.globalsView("unwrap").asInstanceOf[Value.FunV]
    val leaf = interp.globalsView("leaf").asInstanceOf[AnyRef]
    val chain = interp.globalsView("chain").asInstanceOf[AnyRef]
    val jitR = AsmJitBackend.tryCompile(unwrap, interp)
    jitR should not be null
    jitR.paramIsRef shouldBe Array(true)
    jitR.resultIsDouble shouldBe false
    jitR.direct.isInstanceOf[ObjToObject] shouldBe true
    val out = jitR.direct.asInstanceOf[ObjToObject].apply(chain).asInstanceOf[AnyRef]
    (out eq leaf) shouldBe true
  }

  test("ASM while JIT compiles inline ref match RHS") {
    val defs =
      """enum Shape:
        |  case Circle(r: Int)
        |  case Square(s: Int)
        |val shape: Shape = Shape.Circle(5)""".stripMargin
    val whileSrc =
      """var total = 0
        |var i = 0
        |while i < 100 do
        |  total = total + (shape match { case Shape.Circle(r) => r; case Shape.Square(s) => s })
        |  i = i + 1""".stripMargin
    val (interp, w, names, rhs) = whileParts(defs, whileSrc)
    names shouldBe Array("total", "i")
    val entry = AsmJitBackend.tryCompileWhileLong(w.expr, names, rhs, interp, Map.empty)
    entry should not be null
    entry.refNames shouldBe Array("shape")
    entry.refFns shouldBe empty
    entry.refObjFns shouldBe empty
    val slots = Array(0L, 0L)
    runWhileEntry(entry, interp, slots)
    slots shouldBe Array(500L, 100L)
  }

  test("ASM while JIT compiles ObjToObject ref-arg chains") {
    val defs =
      """sealed trait Node
        |case class Leaf(v: Int) extends Node
        |case class Branch(left: Node, right: Node) extends Node
        |val leaf5 = Leaf(5)
        |val tree  = Branch(leaf5, Leaf(99))
        |def getLeft(b: Node): Node = b match
        |  case Branch(l, _) => l
        |  case x            => x
        |def leafVal(n: Node): Int = n match
        |  case Leaf(v)      => v
        |  case Branch(_, _) => -1""".stripMargin
    val whileSrc =
      """var sum = 0
        |var i = 0
        |while i < 100 do
        |  sum = sum + leafVal(getLeft(tree))
        |  i = i + 1""".stripMargin
    val (interp, w, names, rhs) = whileParts(defs, whileSrc)
    names shouldBe Array("sum", "i")
    val entry = AsmJitBackend.tryCompileWhileLong(w.expr, names, rhs, interp, Map.empty)
    entry should not be null
    entry.refNames shouldBe Array("tree")
    entry.refFns.length shouldBe 1
    entry.refObjFns.length shouldBe 1
    val slots = Array(0L, 0L)
    runWhileEntry(entry, interp, slots)
    slots shouldBe Array(500L, 100L)
  }

  test("ASM mixed while JIT fuses List foreach with ObjToLong accumulator") {
    val defs =
      """sealed trait Node
        |case class Leaf(v: Int) extends Node
        |val xs = List(Leaf(1), Leaf(2), Leaf(3))
        |def leafVal(n: Node): Int = n match
        |  case Leaf(v) => v""".stripMargin
    val whileSrc =
      """var acc = 0
        |var i = 0
        |while i < 4 do
        |  xs.foreach(x => acc = acc + leafVal(x))
        |  i = i + 1""".stripMargin
    val (interp, w, foreachApply, names, rhs) = mixedWhileParts(defs, whileSrc)
    names shouldBe Array("i")
    val entry = AsmJitBackend.tryCompileWhileMixed(w.expr, names, rhs, foreachApply, "acc", accIsDouble = false, interp)
    entry should not be null
    // Inline path: fn is inlined, refFns empty; dispatch path: refFns has 1 entry.
    entry.refFns.length should be <= 1
    entry.refDoubleFns shouldBe empty
    val slots = Array(0L, 0L)
    runMixedEntry(entry, interp, "xs", slots)
    slots shouldBe Array(4L, 24L)
  }

  test("ASM mixed while JIT fuses Set foreach with ObjToDouble accumulator") {
    val defs =
      """sealed trait Node
        |case class Leaf(v: Int) extends Node
        |val xs = Set(Leaf(1), Leaf(2))
        |def weight(n: Node): Double = n match
        |  case Leaf(v) => v + 0.5""".stripMargin
    val whileSrc =
      """var acc = 0.0
        |var i = 0
        |while i < 3 do
        |  xs.foreach(x => acc = acc + weight(x))
        |  i = i + 1""".stripMargin
    val (interp, w, foreachApply, names, rhs) = mixedWhileParts(defs, whileSrc)
    names shouldBe Array("i")
    val entry = AsmJitBackend.tryCompileWhileMixed(w.expr, names, rhs, foreachApply, "acc", accIsDouble = true, interp)
    entry should not be null
    entry.refFns shouldBe empty
    // Inline path: fn is inlined, refDoubleFns empty; dispatch path: has 1 entry.
    entry.refDoubleFns.length should be <= 1
    val slots = Array(0L, java.lang.Double.doubleToRawLongBits(0.0))
    runMixedEntry(entry, interp, "xs", slots)
    slots(0) shouldBe 3L
    java.lang.Double.longBitsToDouble(slots(1)) shouldBe 12.0
  }

  test("ASM mixed while JIT fuses Map foreach value mode with Long accumulator") {
    val defs =
      """val m = Map("a" -> 1, "b" -> 2, "c" -> 3)""".stripMargin
    val whileSrc =
      """var acc = 0
        |var i = 0
        |while i < 4 do
        |  m.foreach((k, v) => acc = acc + v)
        |  i = i + 1""".stripMargin
    val (interp, w, foreachApply, names, rhs) = mixedWhileParts(defs, whileSrc)
    names shouldBe Array("i")
    val entry = AsmJitBackend.tryCompileWhileMixed(w.expr, names, rhs, foreachApply, "acc", accIsDouble = false, interp)
    entry should not be null
    entry.mapIsKeyMode shouldBe false
    entry.refFns shouldBe empty
    entry.refDoubleFns shouldBe empty
    val slots = Array(0L, 0L)
    runMixedEntry(entry, interp, "m", slots)
    slots shouldBe Array(4L, 24L)
  }

  test("ASM mixed while JIT fuses Map foreach key mode with Double accumulator") {
    val defs =
      """val m = Map(1 -> "a", 2 -> "b")""".stripMargin
    val whileSrc =
      """var acc = 0.0
        |var i = 0
        |while i < 3 do
        |  m.foreach((k, v) => acc = acc + k)
        |  i = i + 1""".stripMargin
    val (interp, w, foreachApply, names, rhs) = mixedWhileParts(defs, whileSrc)
    names shouldBe Array("i")
    val entry = AsmJitBackend.tryCompileWhileMixed(w.expr, names, rhs, foreachApply, "acc", accIsDouble = true, interp)
    entry should not be null
    entry.mapIsKeyMode shouldBe true
    entry.refFns shouldBe empty
    entry.refDoubleFns shouldBe empty
    val slots = Array(0L, java.lang.Double.doubleToRawLongBits(0.0))
    runMixedEntry(entry, interp, "m", slots)
    slots(0) shouldBe 3L
    java.lang.Double.longBitsToDouble(slots(1)) shouldBe 9.0
  }

  // ── Generalized LICM: Set / Map / non-match pure-fn foreach hoisting ─

  test("Javac LICM hoists Set+match foreach out of outer while (long acc)") {
    val defs =
      """sealed trait Node
        |case class Leaf(v: Int) extends Node
        |val xs = Set(Leaf(1), Leaf(2), Leaf(3))
        |def leafVal(n: Node): Int = n match
        |  case Leaf(v) => v""".stripMargin
    val whileSrc =
      """var acc = 0
        |var i = 0
        |while i < 5 do
        |  xs.foreach(x => acc = acc + leafVal(x))
        |  i = i + 1""".stripMargin
    val (interp, w, foreachApply, names, rhs) = mixedWhileParts(defs, whileSrc)
    names shouldBe Array("i")
    val entry = JavacJitBackend.tryCompileWhileMixed(w.expr, names, rhs, foreachApply, "acc", accIsDouble = false, interp)
    entry should not be null
    val slots = Array(0L, 0L)
    runMixedEntry(entry, interp, "xs", slots)
    slots(0) shouldBe 5L           // i = 5
    slots(1) shouldBe 30L          // acc = 5 * (1+2+3) = 30
  }

  test("Javac LICM hoists Map foreach out of outer while (long acc)") {
    val defs = """val m = Map("a" -> 1, "b" -> 2, "c" -> 3)"""
    val whileSrc =
      """var acc = 0
        |var i = 0
        |while i < 4 do
        |  m.foreach((k, v) => acc = acc + v)
        |  i = i + 1""".stripMargin
    val (interp, w, foreachApply, names, rhs) = mixedWhileParts(defs, whileSrc)
    names shouldBe Array("i")
    val entry = JavacJitBackend.tryCompileWhileMixed(w.expr, names, rhs, foreachApply, "acc", accIsDouble = false, interp)
    entry should not be null
    entry.mapIsKeyMode shouldBe false
    val slots = Array(0L, 0L)
    runMixedEntry(entry, interp, "m", slots)
    slots(0) shouldBe 4L           // i = 4
    slots(1) shouldBe 24L          // acc = 4 * (1+2+3) = 24
  }

  test("Javac LICM hoists List+fn foreach out of outer while (non-match body)") {
    // inlineMatchSwitch == null path: fn compiles as ObjToLong, LICM via _fn0
    val defs =
      """sealed trait Node
        |case class Leaf(v: Int) extends Node
        |val xs = List(Leaf(10), Leaf(20), Leaf(30))
        |def leafVal(n: Node): Int = n match
        |  case Leaf(v) => v""".stripMargin
    val whileSrc =
      """var acc = 0
        |var i = 0
        |while i < 3 do
        |  xs.foreach(x => acc = acc + leafVal(x))
        |  i = i + 1""".stripMargin
    val (interp, w, foreachApply, names, rhs) = mixedWhileParts(defs, whileSrc)
    names shouldBe Array("i")
    val entry = JavacJitBackend.tryCompileWhileMixed(w.expr, names, rhs, foreachApply, "acc", accIsDouble = false, interp)
    entry should not be null
    val slots = Array(0L, 0L)
    runMixedEntry(entry, interp, "xs", slots)
    slots(0) shouldBe 3L           // i = 3
    slots(1) shouldBe 180L         // acc = 3 * (10+20+30) = 180
  }

  test("end-to-end: Set LICM foreach while gives correct result") {
    val out = captured(
      """sealed trait Item
        |case class V(n: Int) extends Item
        |val xs = Set(V(1), V(2), V(3))
        |def f(x: Item): Int = x match
        |  case V(n) => n * 2
        |var total = 0
        |var i = 0
        |while i < 5 do
        |  xs.foreach(x => total = total + f(x))
        |  i = i + 1
        |println(total)""".stripMargin)
    out shouldBe "60"   // 5 * (2+4+6) = 60
  }

  test("end-to-end: Map LICM foreach while gives correct result") {
    val out = captured(
      """val m = Map("x" -> 3, "y" -> 7)
        |var total = 0
        |var i = 0
        |while i < 10 do
        |  m.foreach((k, v) => total = total + v)
        |  i = i + 1
        |println(total)""".stripMargin)
    out shouldBe "100"  // 10 * (3+7) = 100
  }

  // ── Double-domain support (raw VM result is double *bits*) ──────────
  private def asD(raw: Long): Double = java.lang.Double.longBitsToDouble(raw)
  private def bitsOf(d: Double): Long = java.lang.Double.doubleToRawLongBits(d)

  test("compiles and runs a double-typed arithmetic function") {
    val f = funOf("h", """def h(x: Double): Double = x * 2.0""")
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    cfn.get.retIsDouble shouldBe true
    cfn.get.paramIsDouble shouldBe Array(true)
    asD(SscVm.run(cfn.get, Array(bitsOf(3.5)))) shouldBe 7.0
    asD(SscVm.run(cfn.get, Array(bitsOf(-1.25)))) shouldBe -2.5
  }

  test("promotes an Int param into the double domain (Int -> Double)") {
    val f = funOf("scale", """def scale(x: Int): Double = x * 1.5""")
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    cfn.get.retIsDouble shouldBe true
    cfn.get.paramIsDouble shouldBe Array(false)   // x is Int — caller passes a raw int
    asD(SscVm.run(cfn.get, Array(4L))) shouldBe 6.0
    asD(SscVm.run(cfn.get, Array(10L))) shouldBe 15.0
  }

  test("compiles a tail-recursive double accumulator") {
    val f = funOf("dsum",
      """def dsum(n: Int, acc: Double): Double =
        |  if n <= 0 then acc else dsum(n - 1, acc + 0.5)""".stripMargin)
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    cfn.get.retIsDouble shouldBe true
    cfn.get.paramIsDouble shouldBe Array(false, true)
    asD(SscVm.run(cfn.get, Array(4L, bitsOf(0.0)))) shouldBe 2.0
    asD(SscVm.run(cfn.get, Array(100L, bitsOf(1.0)))) shouldBe 51.0
  }

  test("double comparison drives a branch") {
    val f = funOf("clamp",
      """def clamp(x: Double): Double = if x > 10.0 then 10.0 else x""".stripMargin)
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    cfn.get.retIsDouble shouldBe true
    asD(SscVm.run(cfn.get, Array(bitsOf(3.5)))) shouldBe 3.5
    asD(SscVm.run(cfn.get, Array(bitsOf(42.0)))) shouldBe 10.0
  }

  test("bails (None) on unsupported string operations") {
    val f = funOf("s", """def s(x: Int): String = "n=" + x""")
    VmCompiler.compile(f) shouldBe None
  }

  // ── ref-value support: recursive ADT evaluator (VM 2a) ──────────────
  test("compiles and runs a recursive ADT tree evaluator") {
    val interp = interpOf(
      """sealed trait Expr
        |case class Num(n: Int) extends Expr
        |case class Add(l: Expr, r: Expr) extends Expr
        |case class Mul(l: Expr, r: Expr) extends Expr
        |def eval(e: Expr): Int = e match
        |  case Num(n)    => n
        |  case Add(l, r) => eval(l) + eval(r)
        |  case Mul(l, r) => eval(l) * eval(r)""".stripMargin)
    val eval = interp.globalsView("eval").asInstanceOf[Value.FunV]
    val meta: VmCompiler.Meta = {
      case "Num" => (List("n"), List("Int"))
      case "Add" => (List("l", "r"), List("Expr", "Expr"))
      case "Mul" => (List("l", "r"), List("Expr", "Expr"))
      case _     => null
    }
    val cfn = VmCompiler.compile(eval, globalsResolve(interp), meta)
    cfn shouldBe defined
    cfn.get.paramIsRef shouldBe Array(true)
    // Add(Num(2), Mul(Num(3), Num(4))) = 2 + (3 * 4) = 14
    def num(n: Int)            = Value.InstanceV("Num", Map("n" -> Value.intV(n.toLong)))
    def add(l: Value, r: Value) = Value.InstanceV("Add", Map("l" -> l, "r" -> r))
    def mul(l: Value, r: Value) = Value.InstanceV("Mul", Map("l" -> l, "r" -> r))
    val tree = add(num(2), mul(num(3), num(4)))
    SscVm.runRef(cfn.get, Array.empty[Long], Array[AnyRef](tree)) shouldBe 14L
    // Single leaf
    SscVm.runRef(cfn.get, Array.empty[Long], Array[AnyRef](num(42))) shouldBe 42L
  }

  /** Run `code` through the full interpreter, returning trimmed stdout — so the
   *  live JIT bridge (warm-up, compile, VM execution + tree-walk fallback) is
   *  exercised end to end, not just the compiler. */
  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# T\n\n```scala\n$code\n```\n"))
    ps.flush(); buf.toString.trim

  test("tryHoistedPureWhile: var self-assignment is treated as no-op, counter still advances") {
    // `last = last` is a self-assignment (var RHS == LHS name) — hoisted out of the
    // loop as a no-op so `i = i + 1` can run via tryLongWhileAssign. Result: `last`
    // keeps its initial value and `i` reaches the iteration count.
    val out = captured(
      """var i = 0
        |var last = (0, 0, 0, 0)
        |while i < 100 do
        |  last = last
        |  i = i + 1
        |println(last)
        |println(i)""".stripMargin)
    out shouldBe "(0, 0, 0, 0)\n100"
  }

  test("tryHoistedPureWhile: self-assignment with multiple non-int vars all hoisted") {
    val out = captured(
      """var i = 0
        |var a = (1, 2)
        |var b = (3, 4)
        |while i < 50 do
        |  a = a
        |  b = b
        |  i = i + 1
        |println(a)
        |println(b)
        |println(i)""".stripMargin)
    out shouldBe "(1, 2)\n(3, 4)\n50"
  }

  test("end-to-end: recursive ADT eval is JIT-compiled and stays correct") {
    // eval(tree) is called 1000× → crosses the warm-up threshold → compiles to
    // the ref-value VM, recursing internally. total must equal 1000 * 14.
    val out = captured(
      """sealed trait Expr
        |case class Num(n: Int) extends Expr
        |case class Add(l: Expr, r: Expr) extends Expr
        |case class Mul(l: Expr, r: Expr) extends Expr
        |def eval(e: Expr): Int = e match
        |  case Num(n)    => n
        |  case Add(l, r) => eval(l) + eval(r)
        |  case Mul(l, r) => eval(l) * eval(r)
        |val tree = Add(Num(2), Mul(Num(3), Num(4)))
        |var total = 0
        |var i = 0
        |while i < 1000 do
        |  total = total + eval(tree)
        |  i = i + 1
        |println(total)""".stripMargin)
    out shouldBe "14000"
  }

  // ── Gauss closed-form polynomial reduction ─────────────────────────────────
  // tryClosedFormPolyLoop replaces while+acc+f(counter) with the Gauss sum.
  // All tests verify the numerical result, which is the same regardless of
  // whether the closed form or the normal JIT path runs.

  test("closed-form: 1-param linear f(x)=x+1 folds Σ(i+1) correctly") {
    // Σ_{i=0}^{999999} (i+1) = 1+2+...+1000000 = 1000000*1000001/2 = 500000500000
    val out = captured(
      """def f(x: Int): Int = x + 1
        |var total = 0
        |var i = 0
        |while i < 1000000 do
        |  total = total + f(i)
        |  i = i + 1
        |println(total)""".stripMargin)
    out shouldBe "500000500000"
  }

  test("closed-form: 2-param f(x,y)=x+y both bound to counter folds Σ2i correctly") {
    // Σ_{i=0}^{999999} (i+i) = 2*Σi = 2*999999*1000000/2 = 999999000000
    val out = captured(
      """def g(x: Int, y: Int): Int = x + y
        |var total = 0
        |var i = 0
        |while i < 1000000 do
        |  total = total + g(i, i)
        |  i = i + 1
        |println(total)""".stripMargin)
    out shouldBe "999999000000"
  }

  test("closed-form: block-wrapped body {x+1} is still recognized as linear") {
    // pureCallSumBlock shape: f's body is a single-stmt block.
    val out = captured(
      """def fblk(x: Int): Int = { x + 1 }
        |var total = 0
        |var i = 0
        |while i < 1000000 do
        |  total = total + fblk(i)
        |  i = i + 1
        |println(total)""".stripMargin)
    out shouldBe "500000500000"
  }

  test("closed-form: val-bound global constant folded into linear polynomial") {
    // f(x) = x + k where k is an immutable val; closed form applies with b=k.
    // Σ_{i=0}^{99999} (i+5) = 99999*100000/2 + 5*100000 = 4999950000 + 500000 = 5000450000
    val out = captured(
      """val k = 5
        |def f(x: Int): Int = x + k
        |var total = 0
        |var i = 0
        |while i < 100000 do
        |  total = total + f(i)
        |  i = i + 1
        |println(total)""".stripMargin)
    out shouldBe "5000450000"
  }

  test("closed-form does not fire when arg is not the counter (invariant fold handles it)") {
    // f(42) is loop-invariant: tryFoldInvariantAccumLoop fires instead.
    // Both paths give the correct answer 42*1000 = 42000.
    val out = captured(
      """def f(x: Int): Int = x + 1
        |var total = 0
        |var i = 0
        |while i < 1000 do
        |  total = total + f(42)
        |  i = i + 1
        |println(total)""".stripMargin)
    out shouldBe "43000"
  }
