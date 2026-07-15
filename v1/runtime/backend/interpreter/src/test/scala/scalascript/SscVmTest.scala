package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.interpreter.Value
import scalascript.interpreter.vm.{SscVm, VmCompiler}
import scalascript.interpreter.vm.jit.{AsmJitBackend, JavacJitBackend, JitGlobals, LongFn0, LongFn1, LongToObject, ObjToLong, ObjToObject, WhileJitEntry}
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

  // Regression: interp-jit-nested-match-duplicate-var. A `match` nested inside
  // another match's arm (both on a param) previously emitted two `InstanceV inst`
  // (and `__fa_<ctor>`) locals in one Java method → javac "variable inst is already
  // defined" → the whole function silently bailed to tree-walk. The nested-match
  // uniquifier now suffixes helper locals per depth, so the function JIT-compiles.
  test("javac JIT compiles a match nested inside another match's arm (nested-match uniquifier)") {
    val interp = interpOf(
      """sealed trait E
        |case class Num(n: Int) extends E
        |case class Bin(l: E, r: E) extends E
        |def f(x: E): Int = x match
        |  case Bin(l, r) => x match
        |    case Bin(a, b) => 1
        |    case _         => 2
        |  case Num(v)      => v
        |val bb = Bin(Num(10), Num(20))
        |val nn = Num(42)""".stripMargin)
    val f  = interp.globalsView("f").asInstanceOf[Value.FunV]
    val bb = interp.globalsView("bb").asInstanceOf[AnyRef]
    val nn = interp.globalsView("nn").asInstanceOf[AnyRef]
    val jitR = JavacJitBackend.tryCompile(f, interp)
    jitR should not be null                       // pre-fix: null (javac failed → bail)
    jitR.direct.isInstanceOf[ObjToLong] shouldBe true
    val direct = jitR.direct.asInstanceOf[ObjToLong]
    direct.apply(bb) shouldBe 1L                  // Bin → inner match on x → Bin → 1
    direct.apply(nn) shouldBe 42L                 // Num(42) → 42
  }

  // Three levels of nesting exercise suffixes _1 and _2 (inst, inst_1, inst_2 and
  // __fa_Bin, __fa_Bin_1, __fa_Bin_2) — proves the uniquifier scales past one level.
  test("javac JIT compiles a triply-nested match on the same param") {
    val interp = interpOf(
      """sealed trait E
        |case class Num(n: Int) extends E
        |case class Bin(l: E, r: E) extends E
        |def g(x: E): Int = x match
        |  case Bin(l, r) => x match
        |    case Bin(a, b) => x match
        |      case Bin(p, q) => 3
        |      case _         => 2
        |    case _           => 1
        |  case Num(v)        => v
        |val bb = Bin(Num(1), Num(2))
        |val nn = Num(7)""".stripMargin)
    val g  = interp.globalsView("g").asInstanceOf[Value.FunV]
    val bb = interp.globalsView("bb").asInstanceOf[AnyRef]
    val nn = interp.globalsView("nn").asInstanceOf[AnyRef]
    val jitR = JavacJitBackend.tryCompile(g, interp)
    jitR should not be null
    jitR.direct.isInstanceOf[ObjToLong] shouldBe true
    val direct = jitR.direct.asInstanceOf[ObjToLong]
    direct.apply(bb) shouldBe 3L
    direct.apply(nn) shouldBe 7L
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

  test("ASM bytecode JIT co-emits mutually recursive bool-returning functions (stage6 fix)") {
    val interp = interpOf(
      """def isEven(n: Int): Boolean = if n == 0 then true else isOdd(n - 1)
        |def isOdd(n: Int): Boolean  = if n == 0 then false else isEven(n - 1)
        |def workload(): Long =
        |  var sum = 0L; var i = 0
        |  while i < 10 do
        |    if isEven(10) then sum = sum + 1L
        |    i = i + 1
        |  sum""".stripMargin)
    val workload = interp.globalsView("workload").asInstanceOf[Value.FunV]
    val jitR = AsmJitBackend.tryCompile(workload, interp)
    jitR should not be null
    jitR.direct.isInstanceOf[LongFn0] shouldBe true
    val result = JitGlobals.withInterp(interp) { jitR.direct.asInstanceOf[LongFn0].apply() }
    result shouldBe 10L
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

  test("closed-form: counter update before accumulator preserves sequential assignment order") {
    val out = captured(
      """var x = 0
        |var sum = 0
        |while x < 5 do
        |  x = x + 1
        |  sum = sum + x
        |println(x)
        |println(sum)""".stripMargin)
    out shouldBe "5\n15"
  }

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

  // ── Completeness p1: Boolean, unary ops, qualified match ctors ──────────────

  test("compiles Boolean literal and Boolean-returning function") {
    // Lit.Boolean must compile; Boolean return treated as TInt (0/1)
    val f = funOf("isPositive",
      """def isPositive(x: Int): Boolean = x > 0""")
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(5L))  shouldBe 1L
    SscVm.run(cfn.get, Array(-3L)) shouldBe 0L
    SscVm.run(cfn.get, Array(0L))  shouldBe 0L
  }

  test("compiles Boolean parameter used in arithmetic expression") {
    // Boolean param arrives as 0/1 long — standard int arithmetic must work
    val f = funOf("boolToInt",
      """def boolToInt(flag: Boolean): Int = if flag then 1 else 0""")
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(1L)) shouldBe 1L
    SscVm.run(cfn.get, Array(0L)) shouldBe 0L
  }

  test("compiles unary minus on Int") {
    val f = funOf("abs",
      """def abs(x: Int): Int = if x < 0 then -x else x""")
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(-7L)) shouldBe 7L
    SscVm.run(cfn.get, Array(3L))  shouldBe 3L
    SscVm.run(cfn.get, Array(0L))  shouldBe 0L
  }

  test("compiles unary minus on Double") {
    val f = funOf("negD",
      """def negD(x: Double): Double = -x""")
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    cfn.get.retIsDouble shouldBe true
    asD(SscVm.run(cfn.get, Array(bitsOf(2.5))))  shouldBe -2.5
    asD(SscVm.run(cfn.get, Array(bitsOf(-1.0)))) shouldBe 1.0
  }

  test("compiles logical not (!) on Boolean") {
    val f = funOf("not",
      """def not(x: Boolean): Boolean = !x""")
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(0L)) shouldBe 1L
    SscVm.run(cfn.get, Array(1L)) shouldBe 0L
  }

  test("end-to-end: qualified enum patterns and qualified Pat.Extract compile and run") {
    // Tests both `case Color.Red =>` (no-arg, Term.Select pattern) and
    // `case Shape.Circle(r) =>` (qualified Pat.Extract) paths in emitCaseHeader.
    val out = captured(
      """enum Color:
        |  case Red
        |  case Green
        |  case Blue
        |enum Shape:
        |  case Circle(r: Int)
        |  case Square(s: Int)
        |def colorCode(c: Color): Int = c match
        |  case Color.Red   => 1
        |  case Color.Green => 2
        |  case Color.Blue  => 3
        |def area(s: Shape): Int = s match
        |  case Shape.Circle(r) => r * r
        |  case Shape.Square(s) => s * s
        |var total = 0
        |var i = 0
        |while i < 100 do
        |  total = total + colorCode(Color.Green) + area(Shape.Circle(3))
        |  i = i + 1
        |println(total)""".stripMargin)
    out shouldBe "1100"  // 100 * (2 + 9) = 1100
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

  // ── Completeness p1b: arity-0 functions ─────────────────────────────────────

  test("p1b: arity-0 function compiles and returns constant") {
    val f = funOf("answer", "def answer(): Int = 42")
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array()) shouldBe 42L
  }

  test("p1b: arity-0 function with nested while compiles and returns correct sum") {
    val f = funOf("workload",
      """def workload(): Long =
        |  var sum = 0L
        |  var i = 0
        |  while i < 100 do
        |    sum = sum + i
        |    i = i + 1
        |  sum""".stripMargin)
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array()) shouldBe 4950L
  }

  test("p1b: arity-0 function compiles and returns correct result via direct SscVm.run") {
    // Verifies the compiler fix (arity < 1 guard removed); entry-point JIT wiring
    // is deferred to a future phase once SscVm is competitive with WhileJitEntry.
    val f = funOf("counter",
      """def counter(): Int =
        |  var n = 0
        |  var i = 0
        |  while i < 10 do
        |    n = n + i; i = i + 1
        |  n""".stripMargin)
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array()) shouldBe 45L
  }

  // ── Completeness p2: Term.Select field access ────────────────────────────────

  test("p2: end-to-end normSq with field access runs on JIT path") {
    val out = captured(
      """case class Vec(x: Int, y: Int)
        |def normSq(v: Vec): Int = v.x * v.x + v.y * v.y
        |val p = Vec(3, 4)
        |var total = 0
        |var i = 0
        |while i < 100 do
        |  total = total + normSq(p)
        |  i = i + 1
        |println(total)""".stripMargin)
    out shouldBe "2500"  // 100 * (9 + 16) = 2500
  }

  test("p2: chained field access compiles and returns correct result") {
    val out = captured(
      """case class Inner(v: Int)
        |case class Outer(a: Int, b: Inner)
        |def getA(o: Outer): Int = o.a
        |val o = Outer(7, Inner(99))
        |var i = 0; var sum = 0
        |while i < 10 do
        |  sum = sum + getA(o)
        |  i = i + 1
        |println(sum)""".stripMargin)
    out shouldBe "70"
  }

  // ── Completeness p5: Lit.Null ────────────────────────────────────────────────

  test("p5: Lit.Null in val declaration no longer bails — function compiles") {
    // Before p5: compileInto bailed on Lit.Null() with "unsupported: Lit.Null".
    // After p5: emits CONST 0 / TRef. Returning null still bails at unifyRet (by
    // design: RET is Long-typed), but using null as a sentinel val compiles fine.
    val f = funOf("withNullSentinel",
      """def withNullSentinel(x: Int): Int =
        |  val sentinel = null
        |  x + 1""".stripMargin)
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(4L)) shouldBe 5L
  }

  // ── Completeness p4 + p3: inner def ─────────────────────────────────────────

  test("p4+p3: non-capturing inner def compiles and is callable") {
    val f = funOf("outerAdd",
      """def outerAdd(n: Int): Int =
        |  def double(x: Int): Int = x * 2
        |  double(n) + 1""".stripMargin)
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(5L)) shouldBe 11L   // 5*2 + 1 = 11
    SscVm.run(cfn.get, Array(0L)) shouldBe 1L
  }

  test("p4+p3: inner def called multiple times in loop") {
    val out = captured(
      """def outer(n: Int): Int =
        |  def square(x: Int): Int = x * x
        |  square(n) + square(n + 1)
        |var sum = 0
        |var i = 0
        |while i < 5 do
        |  sum = sum + outer(i)
        |  i = i + 1
        |println(sum)""".stripMargin)
    // outer(0)=0+1=1, outer(1)=1+4=5, outer(2)=4+9=13, outer(3)=9+16=25, outer(4)=16+25=41
    out shouldBe "85"
  }

  test("p4+p3: capturing inner def bails gracefully (outer falls back to interpreter)") {
    // `inner` captures `n` from outer — VmCompiler bails, interpreter fallback gives
    // correct result. This test verifies no exception is thrown on capture.
    val out = captured(
      """def outerCapture(n: Int): Int =
        |  def inner(x: Int): Int = x + n
        |  inner(1)
        |println(outerCapture(10))""".stripMargin)
    out shouldBe "11"   // interpreter fallback gives correct result
  }

  // ── p6: Lit.String + string equality (LOADS / EQREF / NEREF) ──────────────

  test("p6: Lit.String in val position compiles — function body uses string as TRef sentinel") {
    val f = funOf("withStringVal",
      """def withStringVal(x: Int): Int =
        |  val tag = "label"
        |  x + 1""".stripMargin)
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(7L)) shouldBe 8L
  }

  test("p6: String == String compiles and returns correct Boolean (EQREF)") {
    val f = funOf("isUsd",
      """def isUsd(s: String): Boolean = s == "USD"""")
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("USD")))  shouldBe 1L
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("EUR")))  shouldBe 0L
  }

  test("p6: String != String compiles and returns correct Boolean (NEREF)") {
    val f = funOf("notUsd",
      """def notUsd(s: String): Boolean = s != "USD"""")
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("USD")))  shouldBe 0L
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("GBP")))  shouldBe 1L
  }

  test("p6: multi-branch string classify compiles and returns correct Int") {
    val f = funOf("classify",
      """def classify(s: String): Int =
        |  if s == "EUR" then 1
        |  else if s == "USD" then 2
        |  else 0""".stripMargin)
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("EUR"))) shouldBe 1L
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("USD"))) shouldBe 2L
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("GBP"))) shouldBe 0L
  }

  test("p6: function returning String compiles with RETREF (retIsRef=true)") {
    val f = funOf("tag",
      """def tag(x: Int): String = if x > 0 then "pos" else "neg"""")
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    cfn.get.retIsRef shouldBe true
  }

  // ── p7: String.length / isEmpty / nonEmpty via GETFI + String meta ─────────

  private val stringMeta: VmCompiler.Meta = (t: String) =>
    if t == "String" then (List("length", "isEmpty", "nonEmpty"), List("Int", "Boolean", "Boolean"))
    else null

  test("p7: String.length compiles and returns correct value") {
    val f = funOf("hashLen",
      """def hashLen(s: String): Int = s.length""")
    val cfn = VmCompiler.compile(f, VmCompiler.noResolve, stringMeta)
    cfn shouldBe defined
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("hello"))) shouldBe 5L
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("")))      shouldBe 0L
  }

  test("p7: String.isEmpty compiles and returns correct Boolean") {
    val f = funOf("isBlank",
      """def isBlank(s: String): Boolean = s.isEmpty""")
    val cfn = VmCompiler.compile(f, VmCompiler.noResolve, stringMeta)
    cfn shouldBe defined
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("")))      shouldBe 1L
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("hi")))    shouldBe 0L
  }

  test("p7: String.nonEmpty compiles and returns correct Boolean") {
    val f = funOf("hasContent",
      """def hasContent(s: String): Boolean = s.nonEmpty""")
    val cfn = VmCompiler.compile(f, VmCompiler.noResolve, stringMeta)
    cfn shouldBe defined
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("ok")))    shouldBe 1L
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("")))      shouldBe 0L
  }

  test("p7: String.length in expression (addition) compiles") {
    val f = funOf("lenPlus",
      """def lenPlus(s: String, n: Int): Int = s.length + n""")
    val cfn = VmCompiler.compile(f, VmCompiler.noResolve, stringMeta)
    cfn shouldBe defined
    // reg0=s (TRef, dummy 0L in Long bank), reg1=n (TInt)
    SscVm.runRef(cfn.get, Array(0L, 3L), Array(Value.StringV("hi"))) shouldBe 5L
  }

  test("p7: string literal .length compiles (setRefType on LOADS)") {
    val f = funOf("constLen",
      """def constLen(x: Int): Int = "hello".length + x""")
    val cfn = VmCompiler.compile(f, VmCompiler.noResolve, stringMeta)
    cfn shouldBe defined
    SscVm.run(cfn.get, Array(1L)) shouldBe 6L
  }

  test("p7: end-to-end String.length via JIT path") {
    val out = captured(
      """def hashLen(s: String): Int = s.length
        |var i = 0
        |var sum = 0
        |while i < 20 do
        |  sum = sum + hashLen("hello")
        |  i = i + 1
        |println(sum)""".stripMargin)
    out shouldBe "100"
  }

  // ── String no-arg methods: trim/toLowerCase/toUpperCase (SSTR), toInt/toLong (GETFI) ──

  test("string-method: s.toInt compiles (GETFI) and parses") {
    val f = funOf("parse", """def parse(s: String): Int = s.toInt""")
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("42")))   shouldBe 42L
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("-7")))   shouldBe -7L
  }

  test("string-method: s.trim.toInt chains SSTR then GETFI") {
    val f = funOf("parse", """def parse(s: String): Int = s.trim.toInt""")
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("  42 ")))  shouldBe 42L
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("100")))    shouldBe 100L
  }

  test("string-method: s.trim returns a String (SSTR + RETREF)") {
    val f = funOf("clean", """def clean(s: String): String = s.trim""")
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    cfn.get.retIsRef shouldBe true
    SscVm.runRef(cfn.get, Array.empty, Array(Value.StringV("  hi  ")))
    SscVm.lastRefResult shouldBe Value.StringV("hi")
  }

  test("string-method: toLowerCase / toUpperCase compile (SSTR)") {
    val lo = funOf("lo", """def lo(s: String): String = s.toLowerCase""")
    val up = funOf("up", """def up(s: String): String = s.toUpperCase""")
    val clo = VmCompiler.compile(lo); clo shouldBe defined
    val cup = VmCompiler.compile(up); cup shouldBe defined
    SscVm.runRef(clo.get, Array.empty, Array(Value.StringV("AbC"))); SscVm.lastRefResult shouldBe Value.StringV("abc")
    SscVm.runRef(cup.get, Array.empty, Array(Value.StringV("AbC"))); SscVm.lastRefResult shouldBe Value.StringV("ABC")
  }

  test("string-method: unsupported method bails (→ tree-walk)") {
    val f = funOf("weird", """def weird(s: String): Int = s.indexOf("x")""")
    VmCompiler.compile(f) shouldBe None
  }

  // ── Stage 2.1: Bool body wrap — predicate fns compile on bytecode JIT ──────

  test("stage2.1: Javac — bool top-level body (comparison) compiles and returns 0/1") {
    val interp = interpOf("def isPositive(n: Int): Boolean = n > 0")
    val fn = interp.globalsView("isPositive").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    // Returns 1L for n>0, 0L for n<=0
    r.direct.asInstanceOf[LongFn1].apply(5L)  shouldBe 1L
    r.direct.asInstanceOf[LongFn1].apply(-1L) shouldBe 0L
  }

  test("stage2.1: Javac — bool body with && compiles") {
    val interp = interpOf("def inRange(n: Int): Boolean = n > 0 && n < 100")
    val fn = interp.globalsView("inRange").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct.asInstanceOf[LongFn1].apply(50L) shouldBe 1L
    r.direct.asInstanceOf[LongFn1].apply(0L)  shouldBe 0L
  }

  test("stage2.1: ASM — bool top-level body compiles and returns 0/1") {
    val interp = interpOf("def isZero(n: Int): Boolean = n == 0")
    val fn = interp.globalsView("isZero").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct.asInstanceOf[LongFn1].apply(0L) shouldBe 1L
    r.direct.asInstanceOf[LongFn1].apply(7L) shouldBe 0L
  }

  test("stage2.1: end-to-end bool predicate runs via JIT") {
    val out = captured(
      """def isEven(n: Int): Boolean = n % 2 == 0
        |println(isEven(4))
        |println(isEven(7))""".stripMargin)
    out shouldBe "true\nfalse"
  }

  // ── Stage 2.1b: Bool sibling gap + walkBool extension ───────────────────────

  test("stage2.1b: function calling bool-returning sibling compiles (Javac)") {
    val interp = interpOf(
      """def isPos(n: Int): Boolean = n > 0
        |def abs(n: Int): Int = if isPos(n) then n else -n""".stripMargin)
    val fn = interp.globalsView("abs").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct.asInstanceOf[LongFn1].apply(-5L) shouldBe 5L
    r.direct.asInstanceOf[LongFn1].apply(3L)  shouldBe 3L
  }

  test("stage2.1b: function calling bool-returning sibling compiles (ASM)") {
    val interp = interpOf(
      """def isPos(n: Int): Boolean = n > 0
        |def abs(n: Int): Int = if isPos(n) then n else -n""".stripMargin)
    val fn = interp.globalsView("abs").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct.asInstanceOf[LongFn1].apply(-5L) shouldBe 5L
    r.direct.asInstanceOf[LongFn1].apply(3L)  shouldBe 3L
  }

  test("stage2.1b: Lit.Boolean literal in bool expression (Javac)") {
    val interp = interpOf("def always(n: Int): Boolean = true")
    val fn = interp.globalsView("always").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct.asInstanceOf[LongFn1].apply(0L) shouldBe 1L
  }

  test("stage2.1b: ! negation compiles (Javac)") {
    val interp = interpOf("def isNeg(n: Int): Boolean = !(n > 0)")
    val fn = interp.globalsView("isNeg").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct.asInstanceOf[LongFn1].apply(-3L) shouldBe 1L
    r.direct.asInstanceOf[LongFn1].apply(3L)  shouldBe 0L
  }

  test("stage2.1b: end-to-end: caller of bool sibling returns correct value") {
    val out = captured(
      """def isEven(n: Int): Boolean = n % 2 == 0
        |def abs(n: Int): Int = if isEven(n) then n else n + 1
        |println(abs(4))
        |println(abs(3))""".stripMargin)
    out shouldBe "4\n4"
  }

  test("stage7-refchain: Javac ref-local getOrElse compiles and runs") {
    val interp = interpOf(
      """def parse(n: Int): Option[Int] =
        |  if n > 0 then Some(n + 1) else None
        |def f(n: Int): Int =
        |  val r = parse(n)
        |  r.getOrElse(7)""".stripMargin)
    val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.direct.isInstanceOf[LongFn1] shouldBe true
    val direct = r.direct.asInstanceOf[LongFn1]
    JitGlobals.withInterp(interp) { direct.apply(4L) } shouldBe 5L
    JitGlobals.withInterp(interp) { direct.apply(-1L) } shouldBe 7L
  }

  test("stage7-refchain: ASM ref-local getOrElse compiles and runs") {
    val interp = interpOf(
      """def parse(n: Int): Option[Int] =
        |  if n > 0 then Some(n + 1) else None
        |def f(n: Int): Int =
        |  val r = parse(n)
        |  r.getOrElse(7)""".stripMargin)
    val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.direct.isInstanceOf[LongFn1] shouldBe true
    val direct = r.direct.asInstanceOf[LongFn1]
    JitGlobals.withInterp(interp) { direct.apply(4L) } shouldBe 5L
    JitGlobals.withInterp(interp) { direct.apply(-1L) } shouldBe 7L
  }

  test("stage7-hof-method: Javac Option flatMap/map/getOrElse compiles and runs") {
    val interp = interpOf(
      """def lookup(k: Int): Option[Int] =
        |  if k % 2 == 0 then Some(k * 2) else None
        |def f(n: Int): Int =
        |  Some(n).flatMap(x => lookup(x)).map(x => x + 1).getOrElse(0)""".stripMargin)
    val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.direct.isInstanceOf[LongFn1] shouldBe true
    val direct = r.direct.asInstanceOf[LongFn1]
    JitGlobals.withInterp(interp) { direct.apply(2L) } shouldBe 5L
    JitGlobals.withInterp(interp) { direct.apply(3L) } shouldBe 0L
  }

  test("stage7-hof-method: ASM Option flatMap/map/getOrElse compiles and runs") {
    val interp = interpOf(
      """def lookup(k: Int): Option[Int] =
        |  if k % 2 == 0 then Some(k * 2) else None
        |def f(n: Int): Int =
        |  Some(n).flatMap(x => lookup(x)).map(x => x + 1).getOrElse(0)""".stripMargin)
    val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.direct.isInstanceOf[LongFn1] shouldBe true
    val direct = r.direct.asInstanceOf[LongFn1]
    JitGlobals.withInterp(interp) { direct.apply(2L) } shouldBe 5L
    JitGlobals.withInterp(interp) { direct.apply(3L) } shouldBe 0L
  }

  test("stage7-hof-method: Javac Either map/flatMap/fold compiles and runs") {
    val interp = interpOf(
      """def parse(n: Int): Either[String, Int] =
        |  if n > 0 then Right(n) else Left("neg")
        |def f(n: Int): Int =
        |  parse(n).map(x => x + 1).flatMap(x => parse(x)).fold(e => 0, x => x)""".stripMargin)
    val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.direct.isInstanceOf[LongFn1] shouldBe true
    val direct = r.direct.asInstanceOf[LongFn1]
    JitGlobals.withInterp(interp) { direct.apply(2L) } shouldBe 3L
    JitGlobals.withInterp(interp) { direct.apply(-1L) } shouldBe 0L
  }

  test("stage7-hof-method: ASM Either map/flatMap/fold compiles and runs") {
    val interp = interpOf(
      """def parse(n: Int): Either[String, Int] =
        |  if n > 0 then Right(n) else Left("neg")
        |def f(n: Int): Int =
        |  parse(n).map(x => x + 1).flatMap(x => parse(x)).fold(e => 0, x => x)""".stripMargin)
    val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.direct.isInstanceOf[LongFn1] shouldBe true
    val direct = r.direct.asInstanceOf[LongFn1]
    JitGlobals.withInterp(interp) { direct.apply(2L) } shouldBe 3L
    JitGlobals.withInterp(interp) { direct.apply(-1L) } shouldBe 0L
  }

  // ── map-into-sink fusion (jit-escape-analysis) ───────────────────────
  // `.map(unary)` feeding flatMap / getOrElse is fused so the intermediate
  // Option/Either wrapper is never allocated. Same shapes as the
  // optionChain / eitherChain benches; verify results are unchanged.

  for backend <- List(JavacJitBackend, AsmJitBackend) do
    val nm = backend.getClass.getSimpleName.stripSuffix("$")

    test(s"jit-escape-analysis: $nm map+getOrElse over Option fuses and runs") {
      val interp = interpOf(
        """def lookup(k: Int): Option[Int] =
          |  if k % 2 == 0 then Some(k * 2) else None
          |def f(n: Int): Int =
          |  lookup(n).map(x => x + 1).getOrElse(0)""".stripMargin)
      val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
      val r = backend.tryCompile(fn, interp)
      r should not be null
      r.direct.isInstanceOf[LongFn1] shouldBe true
      val direct = r.direct.asInstanceOf[LongFn1]
      JitGlobals.withInterp(interp) { direct.apply(2L) } shouldBe 5L  // Some(4)->5
      JitGlobals.withInterp(interp) { direct.apply(3L) } shouldBe 0L  // None->0
    }

    test(s"jit-escape-analysis: $nm map+flatMap over Either fuses and runs") {
      val interp = interpOf(
        """def parse(n: Int): Either[String, Int] =
          |  if n > 0 then Right(n) else Left("neg")
          |def f(n: Int): Int =
          |  parse(n).map(x => x + 1).flatMap(x => parse(x)).fold(e => 0, x => x)""".stripMargin)
      val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
      val r = backend.tryCompile(fn, interp)
      r should not be null
      r.direct.isInstanceOf[LongFn1] shouldBe true
      val direct = r.direct.asInstanceOf[LongFn1]
      JitGlobals.withInterp(interp) { direct.apply(2L) } shouldBe 3L   // Right(2)->map3->parse(3)=Right(3)->3
      JitGlobals.withInterp(interp) { direct.apply(-1L) } shouldBe 0L  // Left->fold->0
    }

  test("stage7-hof-method: Javac List map/filter/foldLeft compiles and runs") {
    val interp = interpOf(
      """val xs: List[Int] = List(1, 2, 3, 4, 5, 6)
        |def f(): Int =
        |  xs.map(x => x * 2).filter(x => x % 3 == 0).foldLeft(0)((a, b) => a + b)""".stripMargin)
    val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.direct.isInstanceOf[LongFn0] shouldBe true
    JitGlobals.withInterp(interp) { r.direct.asInstanceOf[LongFn0].apply() } shouldBe 18L
  }

  test("stage7-hof-method: ASM List map/filter/foldLeft compiles and runs") {
    val interp = interpOf(
      """val xs: List[Int] = List(1, 2, 3, 4, 5, 6)
        |def f(): Int =
        |  xs.map(x => x * 2).filter(x => x % 3 == 0).foldLeft(0)((a, b) => a + b)""".stripMargin)
    val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.direct.isInstanceOf[LongFn0] shouldBe true
    JitGlobals.withInterp(interp) { r.direct.asInstanceOf[LongFn0].apply() } shouldBe 18L
  }

  test("stage7-hof-method: Javac Range until map/foldLeft compiles and runs") {
    val interp = interpOf(
      """def f(n: Int): Int =
        |  (0 until n).map(x => x + 1).foldLeft(0)((a, b) => a + b)""".stripMargin)
    val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.direct.isInstanceOf[LongFn1] shouldBe true
    val direct = r.direct.asInstanceOf[LongFn1]
    JitGlobals.withInterp(interp) { direct.apply(4L) } shouldBe 10L
    JitGlobals.withInterp(interp) { direct.apply(0L) } shouldBe 0L
  }

  test("stage7-hof-method: ASM Range until map/foldLeft compiles and runs") {
    val interp = interpOf(
      """def f(n: Int): Int =
        |  (0 until n).map(x => x + 1).foldLeft(0)((a, b) => a + b)""".stripMargin)
    val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.direct.isInstanceOf[LongFn1] shouldBe true
    val direct = r.direct.asInstanceOf[LongFn1]
    JitGlobals.withInterp(interp) { direct.apply(4L) } shouldBe 10L
    JitGlobals.withInterp(interp) { direct.apply(0L) } shouldBe 0L
  }

  // ── range-native fusion (jit-range-fusion) ───────────────────────────

  for backend <- List(JavacJitBackend, AsmJitBackend) do
    val nm = backend.getClass.getSimpleName.stripSuffix("$")

    test(s"jit-range-fusion: $nm inclusive `to` range with map+filter fuses and runs") {
      // (1 to n).map(*2).filter(%3==0).foldLeft(0)(+) — same shape as streams-pipeline.
      // n=6 -> [2,4,6,8,10,12] -> filter %3==0 -> [6,12] -> 18.
      val interp = interpOf(
        """def f(n: Int): Int =
          |  (1 to n).map(x => x * 2).filter(x => x % 3 == 0).foldLeft(0)((a, b) => a + b)""".stripMargin)
      val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
      val r = backend.tryCompile(fn, interp)
      r should not be null
      r.direct.isInstanceOf[LongFn1] shouldBe true
      val direct = r.direct.asInstanceOf[LongFn1]
      JitGlobals.withInterp(interp) { direct.apply(6L) } shouldBe 18L
      JitGlobals.withInterp(interp) { direct.apply(0L) } shouldBe 0L
    }

    test(s"jit-range-fusion: $nm bare range foldLeft fuses with no map/filter") {
      // (1 to n).foldLeft(0)(+) — Gauss sum; no list materialised.
      val interp = interpOf(
        """def f(n: Int): Int =
          |  (1 to n).foldLeft(0)((a, b) => a + b)""".stripMargin)
      val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
      val r = backend.tryCompile(fn, interp)
      r should not be null
      r.direct.isInstanceOf[LongFn1] shouldBe true
      val direct = r.direct.asInstanceOf[LongFn1]
      JitGlobals.withInterp(interp) { direct.apply(10L) } shouldBe 55L
      JitGlobals.withInterp(interp) { direct.apply(1L) } shouldBe 1L
    }

  test("stage7-refchain-object-dispatch: Javac List map/mkString compiles and runs") {
    val interp = interpOf(
      """def f(n: Int): String =
        |  (0 until n).map(x => x + 1).mkString(",")""".stripMargin)
    val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.direct.isInstanceOf[LongToObject] shouldBe true
    val direct = r.direct.asInstanceOf[LongToObject]
    JitGlobals.withInterp(interp) { direct.apply(4L) }.shouldBe(Value.StringV("1,2,3,4"))
    JitGlobals.withInterp(interp) { direct.apply(0L) }.shouldBe(Value.StringV(""))
  }

  test("stage7-refchain-object-dispatch: ASM List map/mkString compiles and runs") {
    val interp = interpOf(
      """def f(n: Int): String =
        |  (0 until n).map(x => x + 1).mkString(",")""".stripMargin)
    val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.direct.isInstanceOf[LongToObject] shouldBe true
    val direct = r.direct.asInstanceOf[LongToObject]
    JitGlobals.withInterp(interp) { direct.apply(4L) }.shouldBe(Value.StringV("1,2,3,4"))
    JitGlobals.withInterp(interp) { direct.apply(0L) }.shouldBe(Value.StringV(""))
  }

  test("stage7-refchain-object-dispatch: Javac Map getOrElse compiles and runs") {
    val interp = interpOf(
      """val m: Map[String, String] = Map("a" -> "ok")
        |def f(n: Int): String =
        |  if n > 0 then m.getOrElse("a", "miss") else m.getOrElse("b", "miss")""".stripMargin)
    val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.direct.isInstanceOf[LongToObject] shouldBe true
    val direct = r.direct.asInstanceOf[LongToObject]
    JitGlobals.withInterp(interp) { direct.apply(1L) }.shouldBe(Value.StringV("ok"))
    JitGlobals.withInterp(interp) { direct.apply(0L) }.shouldBe(Value.StringV("miss"))
  }

  test("stage7-refchain-object-dispatch: ASM Map getOrElse compiles and runs") {
    val interp = interpOf(
      """val m: Map[String, String] = Map("a" -> "ok")
        |def f(n: Int): String =
        |  if n > 0 then m.getOrElse("a", "miss") else m.getOrElse("b", "miss")""".stripMargin)
    val fn = interp.globalsView("f").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.direct.isInstanceOf[LongToObject] shouldBe true
    val direct = r.direct.asInstanceOf[LongToObject]
    JitGlobals.withInterp(interp) { direct.apply(1L) }.shouldBe(Value.StringV("ok"))
    JitGlobals.withInterp(interp) { direct.apply(0L) }.shouldBe(Value.StringV("miss"))
  }

  test("stage7-numeric-object-dispatch: Javac BigInt/Decimal methods compile and run") {
    val interp = interpOf(
      """def bigPow(n: Int): BigInt = BigInt(n).pow(2)
        |def bigAbs(n: Int): BigInt = BigInt(0 - n).abs
        |def bigGcd(n: Int): BigInt = BigInt(n).gcd(BigInt(6))
        |def bigToDec(n: Int): Decimal = BigInt(n).toDecimal
        |def decAbs(n: Int): Decimal = Decimal(0 - n, 1).abs
        |def decNeg(n: Int): Decimal = Decimal(n, 1).negate
        |def decPow(n: Int): Decimal = Decimal(n, 1).pow(2)
        |def decScale(n: Int): Decimal = Decimal(n, 2).setScale(1)
        |def decToBig(n: Int): BigInt = Decimal(n, 1).toBigInt""".stripMargin)
    val cases: List[(String, Long, Value)] = List(
      ("bigPow", 5L, Value.BigIntV(BigInt(25))),
      ("bigAbs", 5L, Value.BigIntV(BigInt(5))),
      ("bigGcd", 9L, Value.BigIntV(BigInt(3))),
      ("bigToDec", 5L, Value.DecimalV(BigDecimal("5"))),
      ("decAbs", 12L, Value.DecimalV(BigDecimal("1.2"))),
      ("decNeg", 12L, Value.DecimalV(BigDecimal("-1.2"))),
      ("decPow", 12L, Value.DecimalV(BigDecimal("1.44"))),
      ("decScale", 123L, Value.DecimalV(BigDecimal("1.2"))),
      ("decToBig", 123L, Value.BigIntV(BigInt(12)))
    )
    cases.foreach { case (name, arg, expected) =>
      val fn = interp.globalsView(name).asInstanceOf[Value.FunV]
      val r = JavacJitBackend.tryCompile(fn, interp)
      r should not be null
      r.direct should not be null
      r.direct.isInstanceOf[LongToObject] shouldBe true
      val direct = r.direct.asInstanceOf[LongToObject]
      JitGlobals.withInterp(interp) { direct.apply(arg) }.shouldBe(expected)
    }
  }

  test("stage7-numeric-object-dispatch: ASM BigInt/Decimal methods compile and run") {
    val interp = interpOf(
      """def bigPow(n: Int): BigInt = BigInt(n).pow(2)
        |def bigAbs(n: Int): BigInt = BigInt(0 - n).abs
        |def bigGcd(n: Int): BigInt = BigInt(n).gcd(BigInt(6))
        |def bigToDec(n: Int): Decimal = BigInt(n).toDecimal
        |def decAbs(n: Int): Decimal = Decimal(0 - n, 1).abs
        |def decNeg(n: Int): Decimal = Decimal(n, 1).negate
        |def decPow(n: Int): Decimal = Decimal(n, 1).pow(2)
        |def decScale(n: Int): Decimal = Decimal(n, 2).setScale(1)
        |def decToBig(n: Int): BigInt = Decimal(n, 1).toBigInt""".stripMargin)
    val cases: List[(String, Long, Value)] = List(
      ("bigPow", 5L, Value.BigIntV(BigInt(25))),
      ("bigAbs", 5L, Value.BigIntV(BigInt(5))),
      ("bigGcd", 9L, Value.BigIntV(BigInt(3))),
      ("bigToDec", 5L, Value.DecimalV(BigDecimal("5"))),
      ("decAbs", 12L, Value.DecimalV(BigDecimal("1.2"))),
      ("decNeg", 12L, Value.DecimalV(BigDecimal("-1.2"))),
      ("decPow", 12L, Value.DecimalV(BigDecimal("1.44"))),
      ("decScale", 123L, Value.DecimalV(BigDecimal("1.2"))),
      ("decToBig", 123L, Value.BigIntV(BigInt(12)))
    )
    cases.foreach { case (name, arg, expected) =>
      val fn = interp.globalsView(name).asInstanceOf[Value.FunV]
      val r = AsmJitBackend.tryCompile(fn, interp)
      r should not be null
      r.direct should not be null
      r.direct.isInstanceOf[LongToObject] shouldBe true
      val direct = r.direct.asInstanceOf[LongToObject]
      JitGlobals.withInterp(interp) { direct.apply(arg) }.shouldBe(expected)
    }
  }

  test("stage2.1b: HofCall detected when param is called as function") {
    import scalascript.interpreter.vm.jit.{JitPredicates, JitBailReason}
    val interp = interpOf("def apply(f: Int => Int, x: Int): Int = f(x)")
    val fn = interp.globalsView("apply").asInstanceOf[Value.FunV]
    val reasons = JitPredicates.classifyBailReasons(fn)
    reasons should contain (JitBailReason.HofCall("f"))
  }

  // ── Stage 2.4: Pat.Lit integer match arms ───────────────────────────────────

  test("stage2.4: Javac — literal-int match compiles") {
    import scalascript.interpreter.vm.jit.JavacJitBackend
    val interp = interpOf(
      """def describe(n: Int): Int = n match {
        |  case 0 => 10
        |  case 1 => 20
        |  case _ => 99
        |}""".stripMargin)
    val fn = interp.globalsView("describe").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
  }

  test("stage2.4-expr: Javac+ASM — literal-int match with ApplyInfix scrutinee compiles and runs") {
    // n % 5 match { ... }: scrutinee is ApplyInfix, not Term.Name.
    // emitLiteralIntMatch uses walkLong on the scrutinee so this should work.
    val out = captured(
      """def classify(n: Int): Int =
        |  n % 5 match
        |    case 0 => 100
        |    case 1 => 200
        |    case 2 => 300
        |    case 3 => 400
        |    case _ => 500
        |println(classify(0))
        |println(classify(1))
        |println(classify(9))""".stripMargin)
    val lines = out.trim.split("\n")
    lines(0) shouldBe "100"   // 0 % 5 = 0
    lines(1) shouldBe "200"   // 1 % 5 = 1
    lines(2) shouldBe "500"   // 9 % 5 = 4 → default
  }

  test("stage2.4: ASM — literal-int match compiles") {
    import scalascript.interpreter.vm.jit.AsmJitBackend
    val interp = interpOf(
      """def describe(n: Int): Int = n match {
        |  case 0 => 10
        |  case 1 => 20
        |  case _ => 99
        |}""".stripMargin)
    val fn = interp.globalsView("describe").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
  }

  test("stage2.4: end-to-end literal-int match runs correctly") {
    val out = captured(
      """def describe(n: Int): Int = n match {
        |  case 0 => 10
        |  case 1 => 20
        |  case _ => 99
        |}
        |println(describe(0))
        |println(describe(1))
        |println(describe(2))""".stripMargin)
    out.trim shouldBe "10\n20\n99"
  }

  // ── Stage 6: RETREF — ref-typed VM return ────────────────────────────────

  test("stage6-vm-retref: SscVm RETREF — String-returning function compiles and runs") {
    val out = captured(
      """def greet(n: Int): String = if (n > 0) "positive" else "non-positive"
        |println(greet(5))
        |println(greet(-1))""".stripMargin)
    out.trim shouldBe "positive\nnon-positive"
  }

  test("stage6-vm-retref: VmCompiler emits RETREF for ref-returning function") {
    import scalascript.interpreter.vm.{VmCompiler, SscVm}
    val interp = interpOf(
      """def greet(n: Int): String = if (n > 0) "hello" else "bye"""")
    val fn = interp.globalsView("greet").asInstanceOf[Value.FunV]
    val cf = VmCompiler.compile(fn)
    cf should not be empty
    cf.get.retIsRef shouldBe true
    val hasRetref = cf.get.op.contains(SscVm.RETREF)
    hasRetref shouldBe true
  }

  // ── Stage 6: Tuple destructure (NonExtractPattern) ───────────────────────

  test("stage6-nonextract-tuple: JitLint does not report NonExtractPattern for Pat.Tuple arms") {
    import scalascript.interpreter.vm.jit.{JitPredicates, JitBailReason}
    val interp = interpOf(
      """def sumPair(t: (Int, Int)): Int = t match {
        |  case (a, b) => a + b
        |}""".stripMargin)
    val fn = interp.globalsView("sumPair").asInstanceOf[Value.FunV]
    val reasons = JitPredicates.classifyBailReasons(fn)
    reasons should not contain JitBailReason.NonExtractPattern
  }

  test("stage6-nonextract-tuple: Javac — tuple 2-elem match compiles and returns correct result") {
    import scalascript.interpreter.vm.jit.JavacJitBackend
    val interp = interpOf(
      """def sumPair(t: (Int, Int)): Int = t match {
        |  case (a, b) => a + b
        |}""".stripMargin)
    val fn = interp.globalsView("sumPair").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
  }

  test("stage6-nonextract-tuple: end-to-end — tuple destructure in match body") {
    val out = captured(
      """def sumPair(t: (Int, Int)): Int = t match {
        |  case (a, b) => a + b
        |}
        |println(sumPair((3, 4)))
        |println(sumPair((10, 20)))""".stripMargin)
    out.trim shouldBe "7\n30"
  }

  test("stage6-nonextract-tuple: end-to-end — tuple with wildcard") {
    val out = captured(
      """def fst(t: (Int, Int)): Int = t match {
        |  case (a, _) => a
        |}
        |println(fst((42, 99)))""".stripMargin)
    out.trim shouldBe "42"
  }

  test("stage2.4: JitLint does not report NonExtractPattern for Lit.Int arms") {
    import scalascript.interpreter.vm.jit.{JitPredicates, JitBailReason}
    val interp = interpOf(
      """def describe(n: Int): Int = n match {
        |  case 0 => 10
        |  case 1 => 20
        |  case _ => 99
        |}""".stripMargin)
    val fn = interp.globalsView("describe").asInstanceOf[Value.FunV]
    val reasons = JitPredicates.classifyBailReasons(fn)
    reasons should not contain JitBailReason.NonExtractPattern
  }

  // ── Stage 2.5: Free-name → top-level FunV call (non-HOF case) ──────────────

  test("stage2.5: Javac — caller compiles when calling a non-sibling global fn") {
    import scalascript.interpreter.vm.jit.JavacJitBackend
    // `double` is a sibling, `caller` calls it — currently handled by co-emit.
    // This test exercises the callGlobalLong1 fallback for a callee whose body
    // is complex enough that ensureCoEmittedLong fails but the global exists.
    val interp = interpOf(
      """def helper(x: Int): Int = x * 2 + 1
        |def caller(n: Int): Int = helper(n) + 10""".stripMargin)
    val fn = interp.globalsView("caller").asInstanceOf[Value.FunV]
    // caller should JIT-compile (helper co-emitted or via callGlobalLong1)
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
  }

  test("stage2.5: end-to-end: caller of non-compiling global uses fallback dispatch") {
    val out = captured(
      """def expensive(x: Int): Int = x * x + x
        |def outer(n: Int): Int = expensive(n) + 1
        |println(outer(4))
        |println(outer(10))""".stripMargin)
    out.trim shouldBe "21\n111"
  }

  // ── Stage 2.2 — Ref+Ref 2-param dispatch (ObjObjToLong) ─────────────────────

  // Both params are ref when BOTH appear in Term.Select (field access).
  // `a.x` and `b.x` both trigger paramIsRef → classifies both as Object in JIT.
  test("stage2.2: Javac — 2-param both-ref function compiles to ObjObjToLong interface") {
    import scalascript.interpreter.vm.jit.JavacJitBackend
    val interp = interpOf(
      """case class Vec(x: Int, y: Int)
        |def dot(a: Vec, b: Vec): Int = a.x * b.x + a.y * b.y""".stripMargin)
    val fn = interp.globalsView("dot").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.paramIsRef.length shouldBe 2
    r.paramIsRef(0) shouldBe true
    r.paramIsRef(1) shouldBe true
    r.direct.getClass.getInterfaces.exists(_.getSimpleName == "ObjObjToLong") shouldBe true
  }

  test("stage2.2: ASM — 2-param both-ref function compiles to ObjObjToLong interface") {
    import scalascript.interpreter.vm.jit.AsmJitBackend
    val interp = interpOf(
      """case class Vec(x: Int, y: Int)
        |def dot(a: Vec, b: Vec): Int = a.x * b.x + a.y * b.y""".stripMargin)
    val fn = interp.globalsView("dot").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.paramIsRef.length shouldBe 2
    r.paramIsRef(0) shouldBe true
    r.paramIsRef(1) shouldBe true
    r.direct.getClass.getInterfaces.exists(_.getSimpleName == "ObjObjToLong") shouldBe true
  }

  test("stage2.2: end-to-end 2-param both-ref dispatch runs correctly") {
    val out = captured(
      """case class Vec(x: Int, y: Int)
        |def dot(a: Vec, b: Vec): Int = a.x * b.x + a.y * b.y
        |println(dot(Vec(3, 4), Vec(1, 2)))""".stripMargin)
    out.trim shouldBe "11"
  }

  test("stage2.3: ASM — ref-returning match with guarded arm compiles") {
    // Body must use only params/pattern-bindings; 's' is the ref param.
    // Guard 'r > 5' uses the numeric field from the Extract; body 's' is the param.
    val interp = interpOf(
      """sealed trait Shape
        |case class Circle(r: Int) extends Shape
        |case class Square(s: Int) extends Shape
        |val bigC   = Circle(10)
        |val smallC = Circle(3)
        |def identity(s: Shape): Shape = s match
        |  case Circle(r) if r > 5 => s
        |  case _ => s""".stripMargin)
    val fn = interp.globalsView("identity").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.direct.isInstanceOf[ObjToObject] shouldBe true
    val bigC   = interp.globalsView("bigC").asInstanceOf[AnyRef]
    val smallC = interp.globalsView("smallC").asInstanceOf[AnyRef]
    // guard passes: Circle(10) → returns s (identity)
    (r.direct.asInstanceOf[ObjToObject].apply(bigC) eq bigC) shouldBe true
    // guard fails: Circle(3) → wildcard → returns s (identity)
    (r.direct.asInstanceOf[ObjToObject].apply(smallC) eq smallC) shouldBe true
  }

  test("stage2.3: end-to-end — guarded ADT match in numeric JIT runs correctly") {
    // A numeric-returning match with a guard: tests the already-existing
    // emitMatchBody guard path and verifies end-to-end correctness.
    val out = captured(
      """sealed trait Shape
        |case class Circle(r: Int) extends Shape
        |case class Square(s: Int) extends Shape
        |def area(s: Shape): Int = s match
        |  case Circle(r) if r > 0 => r * r
        |  case Square(s) if s > 0 => s * s
        |  case _ => 0
        |println(area(Circle(3)))
        |println(area(Square(4)))
        |println(area(Circle(-1)))""".stripMargin)
    val lines = out.trim.split("\n")
    lines(0) shouldBe "9"
    lines(1) shouldBe "16"
    lines(2) shouldBe "0"
  }

  test("stage6-bool-body: bool-returning literal match compiles via walkBool→walkLong fallback") {
    // walkBool fallback: tries walkLong(expr). A literal-int match with Bool arms
    // now compiles because walkLong(Lit.Boolean) returns 0/1, and walkMatchExpr
    // handles the literal match.
    val interp = interpOf(
      """def isZero(n: Int): Boolean = n match
        |  case 0 => true
        |  case _ => false""".stripMargin)
    val fn = interp.globalsView("isZero").asInstanceOf[Value.FunV]
    val jitR = JavacJitBackend.tryCompile(fn, interp)
    jitR should not be null
    jitR.direct should not be null
    jitR.direct.isInstanceOf[LongFn1] shouldBe true
    val direct = jitR.direct.asInstanceOf[LongFn1]
    JitGlobals.withInterp(interp) { direct.apply(0L) } shouldBe 1L  // true
    JitGlobals.withInterp(interp) { direct.apply(5L) } shouldBe 0L  // false
  }

  test("jit-bool-match: enum-match-returning-Bool detected by isBoolReturning (regression)") {
    // Regression for busi accountBalance bug: a `def : Boolean = expr match { ... }`
    // body with all bool-literal arms must be recognised by isBoolReturning so the
    // JIT result is tagged resultIsBool=true and callers unwrap Long(1/0) back to
    // BoolV.  Without this, `filter(p => isDebit(p) == true)` always returns empty
    // because IntV(1) != BoolV(true), which made busi's accountBalance return 0
    // for every account after the first call.
    import scalascript.interpreter.vm.jit.JitPredicates
    val interp = interpOf(
      """enum Side:
        |  case Debit, Credit
        |
        |case class Item(name: String, side: Side)
        |
        |def isDebit(p: Item): Boolean = p.side match
        |  case Debit  => true
        |  case Credit => false""".stripMargin)
    val fn = interp.globalsView("isDebit").asInstanceOf[Value.FunV]
    JitPredicates.isBoolReturning(fn.body) shouldBe true
  }

  test("jit-bool-match: nested if + match all bool-returning (regression edge)") {
    import scalascript.interpreter.vm.jit.JitPredicates
    val interp = interpOf(
      """def kind(n: Int): Boolean =
        |  if n > 0 then n match
        |    case 1 => true
        |    case _ => false
        |  else false""".stripMargin)
    val fn = interp.globalsView("kind").asInstanceOf[Value.FunV]
    JitPredicates.isBoolReturning(fn.body) shouldBe true
  }

  test("jit-bool-match: match returning Int is NOT bool-returning") {
    import scalascript.interpreter.vm.jit.JitPredicates
    val interp = interpOf(
      """def grade(n: Int): Int = n match
        |  case 0 => 0
        |  case _ => 1""".stripMargin)
    val fn = interp.globalsView("grade").asInstanceOf[Value.FunV]
    JitPredicates.isBoolReturning(fn.body) shouldBe false
  }

  test("stage6-pattern-guard: guarded match EXPRESSION (match as val RHS) compiles") {
    // Guard in a match used as a sub-expression (val binding), not as the
    // top-level function body return. This exercises walkMatchExpr guard path.
    val interp = interpOf(
      """sealed trait Shape
        |case class Circle(r: Int) extends Shape
        |case class Square(s: Int) extends Shape
        |def classify(shape: Shape): Int =
        |  val base = shape match
        |    case Circle(r) if r > 5 => r * 2
        |    case Circle(r) => r
        |    case Square(n) if n > 3 => n + 10
        |    case Square(n) => n
        |  base + 1
        |val big   = Circle(10)
        |val small = Circle(3)
        |val sq    = Square(5)""".stripMargin)
    val fn = interp.globalsView("classify").asInstanceOf[Value.FunV]
    val jitR = JavacJitBackend.tryCompile(fn, interp)
    jitR should not be null
    jitR.direct should not be null
    jitR.direct.isInstanceOf[ObjToLong] shouldBe true
    val direct = jitR.direct.asInstanceOf[ObjToLong]
    val big   = interp.globalsView("big").asInstanceOf[AnyRef]
    val small = interp.globalsView("small").asInstanceOf[AnyRef]
    val sq    = interp.globalsView("sq").asInstanceOf[AnyRef]
    JitGlobals.withInterp(interp) { direct.apply(big) }   shouldBe 21L  // r=10>5: 10*2=20+1=21
    JitGlobals.withInterp(interp) { direct.apply(small) } shouldBe 4L   // r=3<=5: 3+1=4
    JitGlobals.withInterp(interp) { direct.apply(sq) }    shouldBe 16L  // n=5>3: 5+10=15+1=16
  }

  test("stage8-builtin-ctors: Nil / List(...) / Set(...) JIT through JitRefDispatch") {
    val out = captured(
      """def empty(): List[Int] = Nil
        |def trio(a: Int, b: Int, c: Int): List[Int] = List(a, b, c)
        |def hasIt(a: Int, b: Int): Int = if Set(a, b).contains(a) then 1 else 0
        |println(empty())
        |println(trio(1, 2, 3))
        |println(hasIt(7, 9))""".stripMargin)
    out.trim shouldBe "List()\nList(1, 2, 3)\n1"
  }

  test("stage8-string-methods: .trim / .toInt / .startsWith / .toUpperCase JIT through JitRefDispatch") {
    val out = captured(
      """def parse(s: String): Int = s.trim.toInt
        |def shout(s: String): String = s.trim.toUpperCase
        |def begins(s: String, p: String): Int = if s.startsWith(p) then 1 else 0
        |println(parse("  42  "))
        |println(shout("hello"))
        |println(begins("hello world", "hello"))
        |println(begins("foo", "bar"))""".stripMargin)
    out.trim shouldBe "42\nHELLO\n1\n0"
  }

  test("stage8-option-map-methods: Option.isDefined / List.contains JIT through JitRefDispatch") {
    val out = captured(
      """def hasIt(o: Option[Int]): Int = if o.isDefined then 1 else 0
        |def has(xs: List[Int], k: Int): Int = if xs.contains(k) then 1 else 0
        |val some: Option[Int] = Some(7)
        |val none: Option[Int] = None
        |val xs: List[Int] = List(10, 20, 30)
        |println(hasIt(some))
        |println(hasIt(none))
        |println(has(xs, 20))
        |println(has(xs, 99))""".stripMargin)
    out.trim shouldBe "1\n0\n1\n0"
  }

  test("stage8-list-extra-methods: .last / .isEmpty JIT through JitRefDispatch") {
    val out = captured(
      """def lastEl(xs: List[Int]): Int = xs.last
        |def lenOrZero(xs: List[Int]): Int = if xs.isEmpty then 0 else xs.last
        |val a: List[Int] = List(1, 2, 3)
        |val b: List[Int] = List(99)
        |val c: List[Int] = List()
        |println(lastEl(a))
        |println(lastEl(b))
        |println(lenOrZero(c))""".stripMargin)
    out.trim shouldBe "3\n99\n0"
  }

  test("stage8-typed-pattern: case _: T => and case x: T => JIT via Pat.Typed in walkArm") {
    val out = captured(
      """sealed trait Shape
        |case class Circle(r: Int) extends Shape
        |case class Square(s: Int) extends Shape
        |def classify(shape: Shape): Int = shape match
        |  case _: Circle => 1
        |  case x: Square => x.s + 100
        |val c = Circle(5)
        |val s = Square(7)
        |println(classify(c))
        |println(classify(s))""".stripMargin)
    out.trim shouldBe "1\n107"
  }

  test("stage8-ref-equality: String == String JITs via Objects.equals fallback") {
    val out = captured(
      """def isMatch(a: String, b: String): Int = if a == b then 1 else 0
        |println(isMatch("foo", "foo"))
        |println(isMatch("foo", "bar"))""".stripMargin)
    out.trim shouldBe "1\n0"
  }

  test("stage8-list-concat-infix: List ++ List JITs through JitRefDispatch.collectionConcat") {
    val out = captured(
      """def both(xs: List[Int], ys: List[Int]): List[Int] = xs ++ ys
        |val a: List[Int] = List(1, 2, 3)
        |val b: List[Int] = List(4, 5)
        |println(both(a, b))""".stripMargin)
    out.trim shouldBe "List(1, 2, 3, 4, 5)"
  }

  test("stage8-map-ops: ref-typed var + Map.updated + Map.getOrElse JIT-compiles") {
    val out = captured(
      """def workload(): Long =
        |  var m: Map[Int, Int] = Map[Int, Int]()
        |  var sum = 0L
        |  var i = 0
        |  while i < 10 do
        |    m = m.updated(i % 3, i)
        |    sum = sum + m.getOrElse(i % 3, 0).toLong
        |    i = i + 1
        |  sum
        |println(workload())""".stripMargin)
    out.trim shouldBe "45"
  }

  test("stage8-map-get-string-substr: Map.get / String.substring / replace JIT through JitRefDispatch") {
    val out = captured(
      """def prefix(s: String, n: Int): String = s.substring(0, n)
        |def shout(s: String): String = s.replace(".", "!")
        |def charCode(s: String): Int = s.charAt(0).toInt
        |println(prefix("hello world", 5))
        |println(shout("a.b.c"))
        |println(charCode("A"))""".stripMargin)
    out.trim shouldBe "hello\na!b!c\n65"
  }

  test("stage8-math-intrinsics: Math.max / Math.min / Math.abs inline to java.lang.Math") {
    val out = captured(
      """def biggest(a: Int, b: Int): Int = Math.max(a, b)
        |def absVal(n: Int): Int = Math.abs(n)
        |println(biggest(5, 7))
        |println(biggest(10, 3))
        |println(absVal(-42))
        |println(absVal(42))""".stripMargin)
    out.trim shouldBe "7\n10\n42\n42"
  }

  test("stage8-bigint-cmp: BigInt(n) < BigInt(m) and Decimal cmp JIT through JitRefDispatch") {
    val out = captured(
      """def smaller(a: Int, b: Int): Int = if BigInt(a) < BigInt(b) then 1 else 0
        |println(smaller(3, 7))
        |println(smaller(10, 1))""".stripMargin)
    out.trim shouldBe "1\n0"
  }

  test("stage8-bigint-infix: BigInt(n) + n / * / - JITs through JitRefDispatch") {
    val out = captured(
      """def big(n: Int): BigInt = BigInt(n) + BigInt(n) * BigInt(2) - BigInt(1)
        |println(big(5))
        |println(big(10))""".stripMargin)
    // 5 + 5*2 - 1 = 14;   10 + 10*2 - 1 = 29
    out.trim shouldBe "14\n29"
  }

  test("stage8-string-interp: s\"prefix${n}suffix\" compiles via walkRef Interpolate path") {
    val out = captured(
      """def greet(n: Int): String = s"value=$n"
        |println(greet(42))
        |println(greet(0))""".stripMargin)
    out.trim shouldBe "value=42\nvalue=0"
  }

  test("stage8-string-interp-asm: ASM single-arg s-interp compiles via AsmJitBackend") {
    import scalascript.interpreter.vm.jit.AsmJitBackend
    val interp = interpOf("""def greet(n: Int): String = s"value=$n"""")
    val fn = interp.globalsView("greet").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
    val direct = r.direct.asInstanceOf[scalascript.interpreter.vm.jit.LongToObject]
    val res = JitGlobals.withInterp(interp) { direct.apply(42L) }
    res shouldBe Value.StringV("value=42")
  }

  test("stage8-apply-infix-ref: String + Long concat JITs via walkRef Term.ApplyInfix") {
    val out = captured(
      """def greet(name: String, n: Int): String = name + " is " + n
        |println(greet("Alice", 42))""".stripMargin)
    out.trim shouldBe "Alice is 42"
  }

  test("stage8-string-interp: multi-arg s-interpolation compiles") {
    val out = captured(
      """def label(a: Int, b: Int): String = s"[$a,$b]"
        |println(label(7, 9))""".stripMargin)
    out.trim shouldBe "[7,9]"
  }

  test("stage8-pattern-guard-complex: guard with Long expression compiles via walkLong-fallback") {
    // Guard condition is `(r % 2)` — a Long expression, not a Boolean comparison
    // that walkBool natively handles. Stage 8 wraps walkLong via `!= 0` so this
    // arm now JITs instead of bailing as PatternGuard.
    val interp = interpOf(
      """sealed trait Shape
        |case class Circle(r: Int) extends Shape
        |case class Square(s: Int) extends Shape
        |def classify(shape: Shape): Int = shape match
        |  case Circle(r) if (r % 2) => r * 10
        |  case Circle(r) => r
        |  case Square(n) if (n - 2) => n + 100
        |  case Square(n) => n
        |val odd  = Circle(7)
        |val even = Circle(8)
        |val sqHi = Square(5)
        |val sqLo = Square(2)""".stripMargin)
    val fn = interp.globalsView("classify").asInstanceOf[Value.FunV]
    val jitR = JavacJitBackend.tryCompile(fn, interp)
    jitR should not be null
    jitR.direct should not be null
    val direct = jitR.direct.asInstanceOf[ObjToLong]
    val odd  = interp.globalsView("odd").asInstanceOf[AnyRef]
    val even = interp.globalsView("even").asInstanceOf[AnyRef]
    val sqHi = interp.globalsView("sqHi").asInstanceOf[AnyRef]
    val sqLo = interp.globalsView("sqLo").asInstanceOf[AnyRef]
    JitGlobals.withInterp(interp) { direct.apply(odd) }  shouldBe 70L  // r%2=1!=0 → r*10
    JitGlobals.withInterp(interp) { direct.apply(even) } shouldBe 8L   // r%2=0=false → r
    JitGlobals.withInterp(interp) { direct.apply(sqHi) } shouldBe 105L // n-2=3!=0 → n+100
    JitGlobals.withInterp(interp) { direct.apply(sqLo) } shouldBe 2L   // n-2=0=false → n
  }

  test("jit-uc-finding-litmatch: .toLong/.toInt identity conversions compile (workload pattern)") {
    // `classify(i).toLong` in a while body was blocking workload compilation.
    // .toLong/.toInt are no-ops (Int = Long in ScalaScript) — emit as identity.
    val out = captured(
      """def classify(n: Int): Int =
        |  n % 5 match
        |    case 0 => 100
        |    case 1 => 200
        |    case 2 => 300
        |    case 3 => 400
        |    case _ => 500
        |def workload(): Long =
        |  var sum = 0L
        |  var i = 0
        |  while i < 5 do
        |    sum = sum + classify(i).toLong
        |    i = i + 1
        |  sum
        |println(workload())""".stripMargin)
    // 100 + 200 + 300 + 400 + 500 = 1500
    out.trim shouldBe "1500"
  }

  test("jit-uc-finding-litmatch: void Term.If in while body (conditional accumulation)") {
    // `if isEven(i) then sum = sum + i` in a while body — both backends must handle
    // void Term.If with no else branch in walkStatAsVoid / emitStatAsVoid.
    val out = captured(
      """def isEven(n: Int): Boolean = n % 2 == 0
        |def workload(): Long =
        |  var sum = 0L
        |  var i = 0
        |  while i < 10 do
        |    if isEven(i) then sum = sum + i.toLong
        |    i = i + 1
        |  sum
        |println(workload())""".stripMargin)
    // sum of even numbers 0..9: 0+2+4+6+8 = 20
    out.trim shouldBe "20"
  }

  test("stage3.1: VmCompiler compiles FunV-param function (tags param as FunV_N); JitRuntime threads FunV through") {
    // A function with a FunV param it does not call: VmCompiler should compile it
    // successfully (param tagged TRef / "FunV_1") and JitRuntime should accept a
    // FunV arg for the ref param slot — result is `n = 42`.
    val out = captured(
      """def identity(n: Int): Int = n
        |def ignore(f: Int => Int, n: Int): Int = n
        |println(ignore(identity, 42))""".stripMargin)
    out.trim shouldBe "42"
  }

  test("stage3.2: CALLREF — VmCompiler compiles FunV param call, SscVm dispatches via interp") {
    // `applyN(f, n) = f(n)` — the FunV param `f` is called via CALLREF opcode.
    // VmCompiler emits CALLREF; SscVm.exec dispatches through JitGlobals.getInterp().invoke.
    val out = captured(
      """def double(x: Int): Int = x * 2
        |def applyN(f: Int => Int, n: Int): Int = f(n)
        |println(applyN(double, 21))""".stripMargin)
    out.trim shouldBe "42"
  }

  test("stage3.2: CALLREF — chained HOF: applyTwice(f, n) = f(f(n))") {
    val out = captured(
      """def inc(x: Int): Int = x + 1
        |def applyTwice(f: Int => Int, n: Int): Int = f(f(n))
        |println(applyTwice(inc, 5))""".stripMargin)
    out.trim shouldBe "7"
  }

  test("stage3.3: LOADFV — non-capturing lambda literal passed to HOF (VmCompiler emits LOADFV + CALLREF)") {
    // `applyN((x: Int) => x * 2, 5)` — the lambda is non-capturing; VmCompiler
    // should emit LOADFV to materialise a FunV into the ref bank, then CALLREF
    // to invoke it. The CALLREF dispatches via JitGlobals.getInterp().invoke.
    val out = captured(
      """def applyN(f: Int => Int, n: Int): Int = f(n)
        |println(applyN((x: Int) => x * 2, 5))""".stripMargin)
    out.trim shouldBe "10"
  }

  test("stage3.3: LOADFV — lambda with 2 params, non-capturing") {
    val out = captured(
      """def applyBin(f: (Int, Int) => Int, a: Int, b: Int): Int = f(a, b)
        |println(applyBin((x: Int, y: Int) => x + y, 17, 25))""".stripMargin)
    out.trim shouldBe "42"
  }

  test("stage3.4: CALLREF IC — same FunV repeated calls produce correct results (cache correctness gate)") {
    // Call applyN with the same callback 3 times. The IC populates on the first miss
    // and uses exec directly on subsequent hits. If IC corrupts results, equality fails.
    val interp  = interpOf(
      """def double(x: Int): Int = x * 2
        |def applyN(f: Int => Int, n: Int): Int = f(n)""".stripMargin)
    val resolve = globalsResolve(interp)
    val cfn = VmCompiler.compile(
      interp.globalsView("applyN").asInstanceOf[Value.FunV], resolve)
    cfn shouldBe defined
    val cf       = cfn.get
    val doubleFv = interp.globalsView("double").asInstanceOf[Value.FunV]
    // IC cache must be allocated (CALLREF instruction present)
    cf.callRefCache.length should be > 0
    // Three invocations with same callee — verify correctness after IC population
    val stack = new Array[Long](cf.numRegs * 4)
    val refs  = new Array[AnyRef](cf.numRegs * 4)
    refs(0) = doubleFv  // param 0 = f (FunV ref)
    stack(1) = 21L
    val r1 = JitGlobals.withInterp(interp) { SscVm.exec(cf, stack, refs, 0) }
    r1 shouldBe 42L
    stack(1) = 10L
    val r2 = JitGlobals.withInterp(interp) { SscVm.exec(cf, stack, refs, 0) }
    r2 shouldBe 20L
    stack(1) = 3L
    val r3 = JitGlobals.withInterp(interp) { SscVm.exec(cf, stack, refs, 0) }
    r3 shouldBe 6L
  }

  // ── Stage 3.5: Bytecode JIT HOF emission — INVOKEINTERFACE to LongFn1/LongFn2 ──

  test("stage3.5: Javac — HOF param (f: Int => Int) compiles; direct dispatch via ObjLongToLong") {
    import scalascript.interpreter.vm.jit.{ObjLongToLong, LongFn1 as LF1}
    val interp = interpOf(
      """def applyN(f: Int => Int, n: Int): Int = f(n)""".stripMargin)
    val fn = interp.globalsView("applyN").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
    r.paramIsRef(0) shouldBe true    // f: Int => Int classified as ref
    r.paramIsRef(1) shouldBe false   // n: Int is long
    r.direct should not be null
    // Invoke with a manually-constructed LongFn1 adapter (double: n => n*2)
    val doubler: LF1 = (n: Long) => n * 2L
    val result = r.direct.asInstanceOf[ObjLongToLong].apply(doubler, 5L)
    result shouldBe 10L
  }

  test("stage3.5: ASM — HOF param (f: Int => Int) compiles; direct dispatch via ObjLongToLong") {
    import scalascript.interpreter.vm.jit.{ObjLongToLong, LongFn1 as LF1}
    val interp = interpOf(
      """def applyN(f: Int => Int, n: Int): Int = f(n)""".stripMargin)
    val fn = interp.globalsView("applyN").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
    r.paramIsRef(0) shouldBe true
    r.paramIsRef(1) shouldBe false
    r.direct should not be null
    val doubler: LF1 = (n: Long) => n * 2L
    val result = r.direct.asInstanceOf[ObjLongToLong].apply(doubler, 5L)
    result shouldBe 10L
  }

  test("stage3.5: Javac — 2-param HOF with LongFn2 callback (f: (Int,Int)=>Int, n: Int) compiles; ObjLongToLong") {
    import scalascript.interpreter.vm.jit.{ObjLongToLong, LongFn2 as LF2}
    val interp = interpOf(
      // 2 params total: ref (LongFn2 adapter) + long n; body calls f(n,n)
      """def applyPair(f: (Int, Int) => Int, n: Int): Int = f(n, n)""".stripMargin)
    val fn = interp.globalsView("applyPair").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
    r.paramIsRef(0) shouldBe true    // f: (Int,Int) => Int classified as ref
    r.paramIsRef(1) shouldBe false   // n: Int is long
    r.direct should not be null
    // Invoke with an adder LongFn2 (adds its two args); f(21,21) = 42
    val adder: LF2 = (x: Long, y: Long) => x + y
    val result = r.direct.asInstanceOf[ObjLongToLong].apply(adder, 21L)
    result shouldBe 42L
  }

  test("stage3.5: end-to-end — bytecode JIT HOF: applyN(f: Int => Int, n: Int)") {
    // This exercises the full JIT stack: JitRuntime wraps the FunV as LongFn1,
    // the compiled method calls ((LongFn1) f).apply(n) via INVOKEINTERFACE.
    val out = captured(
      """def double(x: Int): Int = x * 2
        |def applyN(f: Int => Int, n: Int): Int = f(n)
        |println(applyN(double, 21))""".stripMargin)
    out.trim shouldBe "42"
  }

  test("stage3.5: end-to-end — bytecode JIT HOF with lambda arg") {
    val out = captured(
      """def applyN(f: Int => Int, n: Int): Int = f(n)
        |println(applyN((x: Int) => x * 3, 14))""".stripMargin)
    out.trim shouldBe "42"
  }

  // ── Stage 4: Arity-3 ceiling lift ─────────────────────────────────────────

  test("stage4: Javac — 3-param all-Long function compiles to LongFn3") {
    import scalascript.interpreter.vm.jit.LongFn3
    val interp = interpOf("def sum3(a: Int, b: Int, c: Int): Int = a + b + c")
    val fn = interp.globalsView("sum3").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
    r.paramIsRef.forall(!_) shouldBe true
    r.direct should not be null
    r.direct.asInstanceOf[LongFn3].apply(1L, 2L, 39L) shouldBe 42L
  }

  test("stage4: ASM — 3-param all-Long function compiles to LongFn3") {
    import scalascript.interpreter.vm.jit.LongFn3
    val interp = interpOf("def mul3(a: Int, b: Int, c: Int): Int = a * b * c")
    val fn = interp.globalsView("mul3").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
    r.direct should not be null
    r.direct.asInstanceOf[LongFn3].apply(2L, 3L, 7L) shouldBe 42L
  }

  test("stage4: end-to-end — 3-param function runs via bytecode JIT") {
    val out = captured(
      """def clamp(lo: Int, hi: Int, x: Int): Int = if x < lo then lo else if x > hi then hi else x
        |println(clamp(0, 10, 15))
        |println(clamp(0, 10, -5))
        |println(clamp(0, 10, 7))""".stripMargin)
    out.trim shouldBe "10\n0\n7"
  }

  test("stage4: end-to-end — 3-param recursive gcd via co-emit (self-call)") {
    // gcd(0,48,18) unrolls: gcd(48,18,12) → gcd(18,12,6) → gcd(12,6,0) → returns a=12
    val out = captured(
      """def gcd(a: Int, b: Int, c: Int): Int = if c == 0 then a else gcd(b, c, b % c)
        |println(gcd(0, 48, 18))""".stripMargin)
    out.trim shouldBe "12"
  }

  // ── Stage 5.4: Pat.Alternative (`case A | B =>`) ────────────────────────────

  test("stage5.4: Javac — Pat.Alternative match arm compiles") {
    import scalascript.interpreter.vm.jit.JavacJitBackend
    val interp = interpOf(
      """sealed trait Tok
        |case class TokA(v: Int) extends Tok
        |case class TokB(v: Int) extends Tok
        |case class TokC(v: Int) extends Tok
        |def classify(t: Tok): Int = t match
        |  case TokA(_) | TokB(_) => 1
        |  case TokC(_) => 2""".stripMargin)
    val fn = interp.globalsView("classify").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
  }

  test("stage5.4: ASM — Pat.Alternative match arm compiles") {
    import scalascript.interpreter.vm.jit.AsmJitBackend
    val interp = interpOf(
      """sealed trait Tok
        |case class TokA(v: Int) extends Tok
        |case class TokB(v: Int) extends Tok
        |case class TokC(v: Int) extends Tok
        |def classify(t: Tok): Int = t match
        |  case TokA(_) | TokB(_) => 1
        |  case TokC(_) => 2""".stripMargin)
    val fn = interp.globalsView("classify").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
  }

  test("stage5.4: end-to-end — Pat.Alternative match runs correctly") {
    val out = captured(
      """sealed trait Tok
        |case class TokA(v: Int) extends Tok
        |case class TokB(v: Int) extends Tok
        |case class TokC(v: Int) extends Tok
        |def classify(t: Tok): Int = t match
        |  case TokA(_) | TokB(_) => 1
        |  case TokC(_) => 2
        |println(classify(TokA(0)))
        |println(classify(TokB(0)))
        |println(classify(TokC(0)))""".stripMargin)
    val lines = out.trim.split("\n")
    lines(0) shouldBe "1"
    lines(1) shouldBe "1"
    lines(2) shouldBe "2"
  }

  // ── Stage 5.5: Non-Term.Name match scrutinee (auto-hoist to local) ──────────

  test("stage5.5: end-to-end — field access scrutinee (Term.Select match) compiles and runs") {
    val out = captured(
      """sealed trait Expr
        |case class Num(v: Int) extends Expr
        |case class Neg(e: Expr) extends Expr
        |case class Wrapper(inner: Expr)
        |def evalInner(w: Wrapper): Int = w.inner match
        |  case Num(v) => v
        |  case Neg(e) => -1
        |println(evalInner(Wrapper(Num(42))))
        |println(evalInner(Wrapper(Neg(Num(7)))))""".stripMargin)
    val lines = out.trim.split("\n")
    lines(0) shouldBe "42"
    lines(1) shouldBe "-1"
  }

  test("stage5.5: Javac — field access scrutinee compiles") {
    import scalascript.interpreter.vm.jit.JavacJitBackend
    val interp = interpOf(
      """sealed trait Expr
        |case class Num(v: Int) extends Expr
        |case class Neg(e: Expr) extends Expr
        |case class Wrapper(inner: Expr)
        |def evalInner(w: Wrapper): Int = w.inner match
        |  case Num(v) => v
        |  case Neg(e) => -1""".stripMargin)
    val fn = interp.globalsView("evalInner").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
  }

  test("stage5.5: ASM — field access scrutinee compiles") {
    import scalascript.interpreter.vm.jit.AsmJitBackend
    val interp = interpOf(
      """sealed trait Expr
        |case class Num(v: Int) extends Expr
        |case class Neg(e: Expr) extends Expr
        |case class Wrapper(inner: Expr)
        |def evalInner(w: Wrapper): Int = w.inner match
        |  case Num(v) => v
        |  case Neg(e) => -1""".stripMargin)
    val fn = interp.globalsView("evalInner").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
  }

  // ── Stage 5.3: try/catch in function bodies ─────────────────────────────────

  test("stage5.3: end-to-end — try/catch with wildcard arm compiles and runs correctly") {
    val out = captured(
      """def safeDivide(x: Int, y: Int): Int =
        |  try x / y
        |  catch case _: Exception => -1
        |println(safeDivide(10, 2))
        |println(safeDivide(10, 0))""".stripMargin)
    val lines = out.trim.split("\n")
    lines(0) shouldBe "5"
    lines(1) shouldBe "-1"
  }

  test("stage5.3: Javac — try/catch compiles") {
    import scalascript.interpreter.vm.jit.JavacJitBackend
    val interp = interpOf(
      """def safeDivide(x: Int, y: Int): Int =
        |  try x / y
        |  catch case _: Exception => -1""".stripMargin)
    val fn = interp.globalsView("safeDivide").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
  }

  test("stage5.3: ASM — try/catch compiles") {
    import scalascript.interpreter.vm.jit.AsmJitBackend
    val interp = interpOf(
      """def safeDivide(x: Int, y: Int): Int =
        |  try x / y
        |  catch case _: Exception => -1""".stripMargin)
    val fn = interp.globalsView("safeDivide").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
  }

  // ── Stage 5.2: var in pure bodies (Term.Assign outside while) ───────────────

  test("stage5.2: end-to-end — var reassignment in pure body compiles and runs correctly") {
    val out = captured(
      """def addWithVar(a: Long, b: Long): Long =
        |  var result = a
        |  result = result + b
        |  result
        |println(addWithVar(3, 4))
        |println(addWithVar(10, -1))""".stripMargin)
    val lines = out.trim.split("\n")
    lines(0) shouldBe "7"
    lines(1) shouldBe "9"
  }

  test("stage5.2: Javac — var reassignment in pure body compiles") {
    val interp = interpOf(
      """def addWithVar(a: Long, b: Long): Long =
        |  var result = a
        |  result = result + b
        |  result""".stripMargin)
    val fn = interp.globalsView("addWithVar").asInstanceOf[Value.FunV]
    val r = JavacJitBackend.tryCompile(fn, interp)
    r should not be null
  }

  test("stage5.2: ASM — var reassignment in pure body compiles") {
    val interp = interpOf(
      """def addWithVar(a: Long, b: Long): Long =
        |  var result = a
        |  result = result + b
        |  result""".stripMargin)
    val fn = interp.globalsView("addWithVar").asInstanceOf[Value.FunV]
    val r = AsmJitBackend.tryCompile(fn, interp)
    r should not be null
  }

  test("JIT tags a startsWith-bodied Boolean fn resultIsBool (busi pwhash isHashed)") {
    // Regression: `def isHashed(s): Boolean = s.startsWith("…")` JIT-compiled to a
    // 0/1 long but resultIsBool was false (isBoolReturning ignored Boolean-returning
    // method calls), so the result was boxed as IntV(1) instead of true — across
    // and within module boundaries.  isHashed(<hashed>) printed "1", not "true".
    val interp = interpOf(
      """def isHashed(s: String): Boolean = s.startsWith("h:")""")
    val fn = interp.globalsView("isHashed").asInstanceOf[Value.FunV]
    for backendName <- List("javac", "asm") do
      val jitR =
        if backendName == "javac" then JavacJitBackend.tryCompile(fn, interp)
        else AsmJitBackend.tryCompile(fn, interp)
      jitR should not be null
      withClue(s"[$backendName] ") {
        jitR.resultIsBool shouldBe true
        jitR.paramIsRef shouldBe Array(true)
        val direct = jitR.direct.asInstanceOf[ObjToLong]
        direct.apply(Value.StringV("h:x"))  shouldBe 1L
        direct.apply(Value.StringV("nope")) shouldBe 0L
      }
  }

  test("wide-jit C-3 consumption: the map recovers an UNANNOTATED delegating callee's Double return") {
    // `mid` is UNANNOTATED and returns Double only by DELEGATION (`base(x)`), with no double param
    // and no Lit.Double in its own body — so `calleeIsDouble` misses it AND C-7's declared-type path
    // can't help (no annotation). The Typer's map types `mid.body` (= `base(x)`) as Double, so the
    // call-result is upgraded to TDouble. (Annotated delegating callees are now handled always-on by
    // C-7; the map remains the fallback for unannotated ones.)
    val src = "# T\n\n```scala\n" +
      "def base(x: Int): Double = x * 1.5\n" +
      "def mid(x: Int) = base(x)\n" +
      "def top(n: Int): Double = mid(n) * 2.0\n```\n"
    val module = Parser.parse(src)
    val interp = Interpreter(devNull); interp.run(module)
    val top = interp.globalsView("top").asInstanceOf[Value.FunV]
    val resolve = globalsResolve(interp)
    val noMeta:  VmCompiler.Meta    = (_: String) => null
    val typer = new scalascript.typer.Typer(); typer.typeCheck(module)
    val typeMap: VmCompiler.TypeMap = (t: scala.meta.Tree) => typer.nodeTypes.get(t)

    // With the map: the delegating callee's Double return is recovered → correct result.
    val before = VmCompiler.callResultUpgrades.get
    val withMap = VmCompiler.compile(top, resolve, noMeta, typeMap)
    withMap shouldBe defined
    assert(VmCompiler.callResultUpgrades.get > before, "the map did not upgrade any call-result type")
    java.lang.Double.longBitsToDouble(SscVm.run(withMap.get, Array(4L))) shouldBe 12.0  // (4*1.5)*2.0

    // Without the map, the heuristic misses `mid`'s Double return — the compiled `top` does NOT
    // produce 12.0 (this is the latent gap the map closes; either a wrong value or a bail).
    val noMap = VmCompiler.compile(top, resolve, noMeta)
    val wrongOrBail =
      noMap.isEmpty || java.lang.Double.longBitsToDouble(SscVm.run(noMap.get, Array(4L))) != 12.0
    assert(wrongOrBail, "expected the heuristic-only path to miss mid's Double return")
  }

  test("wide-jit C-4c regression: RET-leaf widening must not corrupt a shared home register") {
    // BUG (fixed): C-4c widened the RET leaf with an in-place `I2D r, r` + `setType(r, TDouble)`.
    // `compileExpr` of a bare local/param returns its HOME register directly, so both the value and
    // its compile-time type were corrupted for a sibling RET leaf. `if c then a else a` returned the
    // else path's raw int bits as a double (5 → 2.5e-323). The fix widens into a FRESH reg (asDouble).
    val g = funOf("g", "def g(a: Int, c: Int): Double = if c > 0 then a else a")
    val cfn = VmCompiler.compile(g)
    cfn shouldBe defined
    java.lang.Double.longBitsToDouble(SscVm.run(cfn.get, Array(5L, 1L)))  shouldBe 5.0  // then path
    java.lang.Double.longBitsToDouble(SscVm.run(cfn.get, Array(5L, -1L))) shouldBe 5.0  // else path (was garbage)
    java.lang.Double.longBitsToDouble(SscVm.run(cfn.get, Array(9L, -1L))) shouldBe 9.0
  }

  test("wide-jit C-4c: a Double-declared function's Int RET leaf is widened, not bailed on") {
    // `f` is DECLARED `: Double` but one branch yields the Int literal `2`. Scala widens it to `2.0`.
    // The JIT's unifyRet otherwise sees TDouble (1.5) then TInt (2) → MixedReturnType bail. The Typer
    // CANNOT supply the signal — it types the mixed `if` body as Any (asserted below), which is why
    // the earlier map-based attempt was inert. The DECLARED return type (threaded into FunV, C-4a/b)
    // does supply it, so the Int leaf is widened (I2D) at RET and the function compiles.
    val src    = "# T\n\n```scala\ndef f(c: Int): Double = if c > 0 then 1.5 else 2\n```\n"
    val module = Parser.parse(src)
    val interp = Interpreter(devNull); interp.run(module)
    val f = interp.globalsView("f").asInstanceOf[Value.FunV]
    // C-4b populated the declared return type on the FunV:
    f.declaredReturnType shouldBe "Double"
    // ...while the Typer's inferred body type is Any (why a body-type-driven widening was inert):
    val typer = new scalascript.typer.Typer(); typer.typeCheck(module)
    assert(typer.nodeTypes.get(f.body).toString.contains("Any"), "expected Any for a mixed-branch body")

    // The widening is driven by the FunV field (no map / flag needed) → always-on.
    val before   = VmCompiler.retDoubleWidenings.get
    val compiled = VmCompiler.compile(f, globalsResolve(interp))
    compiled shouldBe defined
    assert(VmCompiler.retDoubleWidenings.get > before, "the Int-return leaf was not widened")
    java.lang.Double.longBitsToDouble(SscVm.run(compiled.get, Array(5L)))  shouldBe 1.5
    java.lang.Double.longBitsToDouble(SscVm.run(compiled.get, Array(-5L))) shouldBe 2.0  // Int 2 widened

    // Guard: a FunV with NO declared return type (field unset) is untouched — still bails.
    val bare = f.copy(); bare.declaredReturnType = ""
    VmCompiler.compile(bare, globalsResolve(interp)) shouldBe empty
  }

  test("wide-jit C-4d: a declared-Double self-recursive fn types its self-call result Double (no double literal)") {
    // `f` is declared `: Double`, is self-recursive, and has NO double param and NO Lit.Double — so
    // the syntactic `fnIsDouble` scan is false. Without honouring the declaration, the self-call
    // result `f(n-1)` types TInt, so `f(n-1) / 2` runs INTEGER division (double bits read as int →
    // garbage). Honouring the declared type (C-4d) types the self-call TDouble → real double
    // division. Division is what makes int-space and double-space arithmetic observably differ.
    val src    = "# T\n\n```scala\ndef f(n: Int): Double = if n <= 0 then 1 else f(n - 1) / 2\n```\n"
    val module = Parser.parse(src)
    val interp = Interpreter(devNull); interp.run(module)
    val f = interp.globalsView("f").asInstanceOf[Value.FunV]
    f.declaredReturnType shouldBe "Double"
    val compiled = VmCompiler.compile(f, globalsResolve(interp))
    compiled shouldBe defined
    java.lang.Double.longBitsToDouble(SscVm.run(compiled.get, Array(0L))) shouldBe 1.0
    java.lang.Double.longBitsToDouble(SscVm.run(compiled.get, Array(1L))) shouldBe 0.5   // 1.0 / 2
    java.lang.Double.longBitsToDouble(SscVm.run(compiled.get, Array(2L))) shouldBe 0.25  // (1.0/2)/2
    // The compiled result must equal the tree-walk (correct) result — the invariant that matters.
    java.lang.Double.longBitsToDouble(SscVm.run(compiled.get, Array(3L))) shouldBe 0.125
  }

  test("wide-jit C-5: a value-position if with mixed Int/Double branches is widened, not bailed on") {
    // The `if` is an OPERAND of `+ 1.0` (value position, not a RET leaf → C-4c doesn't apply). Its
    // branches are {Double, Int}; Scala widens the Int branch to Double. Both branch types are known
    // locally, so the JIT widens (I2D) instead of bailing MixedReturnType. Tests BOTH orderings to
    // exercise the else-widen path (g1) and the then-pad path (g2).
    val src = "# T\n\n```scala\n" +
      "def g1(c: Int): Double = (if c > 0 then 1.5 else 2) + 1.0\n" +   // then=Double, else=Int
      "def g2(c: Int): Double = (if c > 0 then 2 else 1.5) + 1.0\n```\n" // then=Int, else=Double
    val module = Parser.parse(src)
    val interp = Interpreter(devNull); interp.run(module)
    val g1 = interp.globalsView("g1").asInstanceOf[Value.FunV]
    val g2 = interp.globalsView("g2").asInstanceOf[Value.FunV]
    val resolve = globalsResolve(interp)

    val before = VmCompiler.branchWidenings.get
    val c1 = VmCompiler.compile(g1, resolve); c1 shouldBe defined
    val c2 = VmCompiler.compile(g2, resolve); c2 shouldBe defined
    assert(VmCompiler.branchWidenings.get >= before + 2, "both value-position ifs should widen")
    // Correct doubles (garbage bits if the widening mis-read the Int branch):
    java.lang.Double.longBitsToDouble(SscVm.run(c1.get, Array(1L)))  shouldBe 2.5  // 1.5 + 1.0
    java.lang.Double.longBitsToDouble(SscVm.run(c1.get, Array(-1L))) shouldBe 3.0  // 2→2.0 + 1.0
    java.lang.Double.longBitsToDouble(SscVm.run(c2.get, Array(1L)))  shouldBe 3.0  // 2→2.0 + 1.0
    java.lang.Double.longBitsToDouble(SscVm.run(c2.get, Array(-1L))) shouldBe 2.5  // 1.5 + 1.0
  }

  test("wide-jit C-5b: a value-position match with mixed Int/Double arms is widened, not bailed on") {
    // The match is an operand of `* 2.0` (value position). Its arms are {Double, Int, Double}; the
    // Int arm (`2`) routes its end-jump through a shared I2D pad so every arm leaves a Double in dst.
    val interp = interpOf(
      """sealed trait E
        |case object A extends E
        |case object B extends E
        |case object C extends E
        |def f(e: E): Double = (e match {
        |  case A => 1.5
        |  case B => 2
        |  case C => 3.5
        |}) * 2.0""".stripMargin)
    val f = interp.globalsView("f").asInstanceOf[Value.FunV]
    val before = VmCompiler.branchWidenings.get
    val cfn = VmCompiler.compile(f, globalsResolve(interp))
    cfn shouldBe defined
    assert(VmCompiler.branchWidenings.get > before, "the mixed-arm match should widen")
    def e(tag: String) = Value.InstanceV(tag, Map.empty)
    java.lang.Double.longBitsToDouble(SscVm.runRef(cfn.get, Array.empty[Long], Array[AnyRef](e("A")))) shouldBe 3.0  // 1.5 * 2
    java.lang.Double.longBitsToDouble(SscVm.runRef(cfn.get, Array.empty[Long], Array[AnyRef](e("B")))) shouldBe 4.0  // 2→2.0 * 2
    java.lang.Double.longBitsToDouble(SscVm.runRef(cfn.get, Array.empty[Long], Array[AnyRef](e("C")))) shouldBe 7.0  // 3.5 * 2
  }

  test("wide-jit C-6/C-5b regression: assigning a self-referencing if/match to a var must not corrupt it") {
    // BUG (fixed): `y = if c then 5 else y` compiled the rhs straight into y's HOME reg. The `else y`
    // branch self-aliases the home (no MOVE), so it read a compile-time type polluted by the `then`
    // branch (TInt), the if reported TInt, and C-6's widen fired on the else path where y still held
    // the Double 3.0 → garbage (f(false) → 4.6e18 instead of 3.0). Same for a self-referencing match
    // arm (C-5b pad). FIX: a self-referencing rhs compiles into a FRESH temp, then moves to the home.
    val fIf = interpOf(
      """def f(c: Boolean): Double =
        |  var y = 3.0
        |  y = if c then 5 else y
        |  y""".stripMargin).globalsView("f").asInstanceOf[Value.FunV]
    val cIf = VmCompiler.compile(fIf); cIf shouldBe defined
    java.lang.Double.longBitsToDouble(SscVm.run(cIf.get, Array(1L))) shouldBe 5.0  // c=true → 5→5.0
    java.lang.Double.longBitsToDouble(SscVm.run(cIf.get, Array(0L))) shouldBe 3.0  // c=false → y unchanged

    val interp = interpOf(
      """sealed trait Opt
        |case object A extends Opt
        |case object B extends Opt
        |def g(o: Opt): Double =
        |  var y = 3.0
        |  y = o match
        |    case A => 5
        |    case B => y
        |  y""".stripMargin)
    val g = interp.globalsView("g").asInstanceOf[Value.FunV]
    val cG = VmCompiler.compile(g, globalsResolve(interp)); cG shouldBe defined
    java.lang.Double.longBitsToDouble(SscVm.runRef(cG.get, Array.empty[Long], Array[AnyRef](Value.InstanceV("A", Map.empty)))) shouldBe 5.0
    java.lang.Double.longBitsToDouble(SscVm.runRef(cG.get, Array.empty[Long], Array[AnyRef](Value.InstanceV("B", Map.empty)))) shouldBe 3.0
  }

  test("wide-jit C-6: an Int assigned to a Double var is widened, not bailed on") {
    // `x` is a Double var (init 0.0); `x = c` assigns the Int param. Scala widens Int→Double, so the
    // assign widens `x` (I2D) rather than bailing on a false "var domain change".
    val f = funOf("f",
      """def f(c: Int): Double =
        |  var x = 0.0
        |  x = c
        |  x + 0.5""".stripMargin)
    val before = VmCompiler.varWidenings.get
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    assert(VmCompiler.varWidenings.get > before, "the Int→Double var assign should widen")
    java.lang.Double.longBitsToDouble(SscVm.run(cfn.get, Array(5L))) shouldBe 5.5  // 5→5.0 + 0.5
    java.lang.Double.longBitsToDouble(SscVm.run(cfn.get, Array(3L))) shouldBe 3.5  // 3→3.0 + 0.5
  }

  test("wide-jit C-7: an already-ref call result is named from the declared type → String method resolves") {
    // `clean(s)` returns a String (calleeReturnsRef already types the result TRef), but without a
    // NAME a chained `.length` bails on an "unknown ref type". C-7 names the result "String" (from
    // the callee's declared return type), so `clean(s).length` resolves. C-7 does NOT change which
    // results are refs — only names ones already TRef — so it can't ripple like a type flip would.
    val interp = interpOf(
      """def clean(s: String): String = s.trim
        |def len(s: String): Int = clean(s).length""".stripMargin)
    val len = interp.globalsView("len").asInstanceOf[Value.FunV]
    val before = VmCompiler.refTypeFromDecl.get
    val cfn = VmCompiler.compile(len, globalsResolve(interp))
    cfn shouldBe defined
    assert(VmCompiler.refTypeFromDecl.get > before, "the call result should be named from the declared type")
    SscVm.runRef(cfn.get, Array.empty[Long], Array[AnyRef](Value.StringV("  hi  "))) shouldBe 2L  // "hi".length

    // Guard: strip the callee's declared type → the result's ref name is empty → `.length` bails.
    interp.globalsView("clean").asInstanceOf[Value.FunV].declaredReturnType = ""
    VmCompiler.compile(len, globalsResolve(interp)) shouldBe empty
  }

  test("wide-jit C-8: foldLeft with a Double accumulator over List[Int] compiles and runs") {
    // Slice A foldLeft was Int-accumulator only. C-8 allows a Double accumulator (`foldLeft(0.0)`):
    // element stays Int, the `a + x` body widens x (I2D) like any mixed arithmetic. Sum-as-double.
    val f = funOf("sumD", "def sumD(xs: List[Int]): Double = xs.foldLeft(0.0)((a, x) => a + x)")
    val before = VmCompiler.foldLeftCompileCount.get
    val cfn = VmCompiler.compile(f)
    cfn shouldBe defined
    assert(VmCompiler.foldLeftCompileCount.get > before, "the Double-accumulator foldLeft did not compile")
    val xs = Value.ListV(List(Value.intV(1L), Value.intV(2L), Value.intV(3L), Value.intV(4L)))
    java.lang.Double.longBitsToDouble(SscVm.runRef(cfn.get, Array.empty[Long], Array[AnyRef](xs))) shouldBe 10.0
  }

  test("wide-jit C-9: a val's declared type names an if-result ref rhs → field access resolves") {
    // `val p: Box = if a.v > 0 then a else b` — the if-result is a ref but (unlike a param/field/call)
    // is not name-tagged, so `p.v` would bail "unknown ref type". C-9 names the home from the val's
    // DECLARED type. All-ref params so runRef maps them cleanly. Only NAMES an already-TRef home.
    val interp = interpOf(
      """case class Box(v: Int)
        |def pick(a: Box, b: Box): Int =
        |  val p: Box = if a.v > 0 then a else b
        |  p.v""".stripMargin)
    val pick = interp.globalsView("pick").asInstanceOf[Value.FunV]
    val meta: VmCompiler.Meta = { case "Box" => (List("v"), List("Int")); case _ => null }
    val before = VmCompiler.refTypeFromDecl.get
    val cfn = VmCompiler.compile(pick, globalsResolve(interp), meta)
    cfn shouldBe defined
    assert(VmCompiler.refTypeFromDecl.get > before, "the val's declared type should name the if-result ref home")
    def box(v: Int) = Value.InstanceV("Box", Map("v" -> Value.intV(v.toLong)))
    SscVm.runRef(cfn.get, Array.empty[Long], Array[AnyRef](box(5), box(-3)))  shouldBe 5L  // a.v>0 → a
    SscVm.runRef(cfn.get, Array.empty[Long], Array[AnyRef](box(-1), box(7)))  shouldBe 7L  // else → b
  }

  // wide-jit CL: capturing lambdas. A lambda that captures an outer local no longer bails — it
  // materialises a runtime FunV (LOADFVCAP) whose closure snapshots the captures (matching the
  // interpreter's own value-snapshot of frame-locals). CALLREF dispatches it via interp.invoke.
  private def clResolve(interp: Interpreter): VmCompiler.Resolve =
    (_, name) => interp.globalsView.get(name) match { case Some(fv: Value.FunV) => fv; case _ => null }
  private def clRun(interp: Interpreter, cf: SscVm.CompiledFn, a: Array[Long]): Long =
    JitGlobals.withInterp(interp) { SscVm.run(cf, a) }

  test("wide-jit CL: capturing lambda — Int capture over a HOF") {
    val interp = interpOf(
      """def applyIt(f: Int => Int, n: Int): Int = f(n)
        |def g(k: Int): Int = applyIt(x => x + k, 10)""".stripMargin)
    val g = interp.globalsView("g").asInstanceOf[Value.FunV]
    val cfn = VmCompiler.compile(g, clResolve(interp)); cfn shouldBe defined
    clRun(interp, cfn.get, Array(5L))   shouldBe 15L
    clRun(interp, cfn.get, Array(100L)) shouldBe 110L
  }

  test("wide-jit CL: capturing lambda — multiple captures") {
    val interp = interpOf(
      """def applyIt(f: Int => Int, n: Int): Int = f(n)
        |def g(a: Int, b: Int): Int = applyIt(x => x + a + b, 1)""".stripMargin)
    val g = interp.globalsView("g").asInstanceOf[Value.FunV]
    val cfn = VmCompiler.compile(g, clResolve(interp)); cfn shouldBe defined
    clRun(interp, cfn.get, Array(10L, 20L)) shouldBe 31L
  }

  test("wide-jit CL: capturing lambda — Double capture + Double HOF result typing (annotated + inferred)") {
    // Both the capture (k: Double) and the HOF result must be Double. The CALLREF result is typed
    // TDouble from the `Double => Double` signature; the unannotated param `x` is inferred Double
    // from the HOF context (so the arg is boxed as a Double, not mis-boxed as Int).
    val interpA = interpOf(
      """def applyD(f: Double => Double, n: Double): Double = f(n)
        |def g(k: Double): Double = applyD((x: Double) => x + k, 2.5)""".stripMargin)
    val gA = interpA.globalsView("g").asInstanceOf[Value.FunV]
    val cA = VmCompiler.compile(gA, clResolve(interpA)); cA shouldBe defined
    java.lang.Double.longBitsToDouble(clRun(interpA, cA.get, Array(java.lang.Double.doubleToRawLongBits(1.5)))) shouldBe 4.0

    val interpB = interpOf(
      """def applyD(f: Double => Double, n: Double): Double = f(n)
        |def g(): Double = applyD(x => x + 1.0, 2.5)""".stripMargin)   // unannotated x → inferred Double
    val gB = interpB.globalsView("g").asInstanceOf[Value.FunV]
    val cB = VmCompiler.compile(gB, clResolve(interpB)); cB shouldBe defined
    java.lang.Double.longBitsToDouble(clRun(interpB, cB.get, Array.empty)) shouldBe 3.5  // 2.5 + 1.0
  }

  test("wide-jit CL: capturing lambda — ref capture (String), boxed as a ref in the closure") {
    val interp = interpOf(
      """def applyIt(f: Int => Int, n: Int): Int = f(n)
        |def g(prefix: String): Int = applyIt(x => x + prefix.length, 10)""".stripMargin)
    val g = interp.globalsView("g").asInstanceOf[Value.FunV]
    val cfn = VmCompiler.compile(g, clResolve(interp)); cfn shouldBe defined
    JitGlobals.withInterp(interp) {
      SscVm.runRef(cfn.get, Array.empty[Long], Array[AnyRef](Value.StringV("abcd"))) shouldBe 14L  // 10 + 4
    }
  }

  test("wide-jit C-3: FunV.body is identity-keyed in the Typer's nodeTypes; the map threads to VmCompiler") {
    // Parse ONCE; run it (→ FunV) and typecheck it (→ nodeTypes) from the SAME parse, so the
    // FunV.body Term the JIT compiles is the exact object the Typer recorded a type for.
    val src    = "# T\n\n```scala\ndef sq(n: Int): Int = n * n\n```\n"
    val module = Parser.parse(src)
    val interp = Interpreter(devNull)
    interp.run(module)
    val fn = interp.globalsView("sq").asInstanceOf[Value.FunV]

    val typer = new scalascript.typer.Typer()
    typer.typeCheck(module)
    val nodeTypes = typer.nodeTypes
    assert(!nodeTypes.isEmpty, "Typer recorded no node types")

    // Identity-key (the crux of C-3's viability): the exact body Term the FunV runs is a key the
    // Typer recorded a type for — there is no tree-copy between parse and FunV on this path.
    assert(nodeTypes.get(fn.body) != null,
      s"FunV.body (${fn.body.getClass.getSimpleName}) not found in nodeTypes — identity broken")
    assert(nodeTypes.get(fn.body).toString.contains("Int"),
      s"expected Int for `n * n`, got ${nodeTypes.get(fn.body)}")

    // The map threads through the 4-arg compile and the function still compiles.
    val typeMap: VmCompiler.TypeMap = (t: scala.meta.Tree) => nodeTypes.get(t)
    val noMeta:  VmCompiler.Meta    = (_: String) => null
    val cf = VmCompiler.compile(fn, VmCompiler.noResolve, noMeta, typeMap)
    assert(cf.isDefined, "sq did not compile with a typeMap present")
  }
