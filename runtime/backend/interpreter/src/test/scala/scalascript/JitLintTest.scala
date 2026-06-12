package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.meta.*
import scalascript.interpreter.{Interpreter, Value}
import scalascript.interpreter.vm.jit.{AsmJitBackend, JitLint, JitBailReason, JitPredicates, JavacJitBackend, JitHofShape, JitHofDispatch}
import scalascript.parser.Parser

/** Lint fixtures. Each test exercises exactly one category of `JitBailReason`
 *  so a regression in the classifier shows up as a precise test failure.
 *
 *  Convention: every fixture defines `def f(...) = ...` so we can look
 *  up the report for "f" without colliding with stdlib intrinsics
 *  (which all start with `_ssc_` or are not top-level FunVs). */
class JitLintTest extends AnyFunSuite with Matchers:

  private def lintFor(code: String): JitLintReportLookup =
    lintForBackend(code, JavacJitBackend)

  private def lintForAsm(code: String): JitLintReportLookup =
    lintForBackend(code, AsmJitBackend)

  private def lintForBackend(
    code:    String,
    backend: scalascript.interpreter.vm.jit.JitBackend
  ): JitLintReportLookup =
    val src = s"# Test\n\n```scalascript\n$code\n```\n"
    val module = Parser.parse(src)
    val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    val interp = Interpreter(devNull)
    interp.runSections(module)
    new JitLintReportLookup(JitLint.lintInterpreter(interp, backend))

  private def lintCompareFor(code: String): JitLintCompareLookup =
    val src = s"# Test\n\n```scalascript\n$code\n```\n"
    val module = Parser.parse(src)
    val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    val interp = Interpreter(devNull)
    interp.runSections(module)
    new JitLintCompareLookup(JitLint.lintInterpreterCompare(interp))

  private def classifyBody(
    body:       String,
    params:     List[String],
    paramTypes: List[String]
  ): List[JitBailReason] =
    val term = body.parse[Term].get
    JitPredicates.classifyBailReasons(
      Value.FunV(params, term, Map.empty, "f", paramTypes = paramTypes))

  private final class JitLintReportLookup(reports: List[scalascript.interpreter.vm.jit.JitLintReport]):
    def forDef(name: String): scalascript.interpreter.vm.jit.JitLintReport =
      reports.find(_.defName == name).getOrElse(
        fail(s"No report for def '$name'. Available: ${reports.map(_.defName).mkString(", ")}"))

  private final class JitLintCompareLookup(
    reports: List[scalascript.interpreter.vm.jit.JitLintCompareReport]
  ):
    def forDef(name: String): scalascript.interpreter.vm.jit.JitLintCompareReport =
      reports.find(_.defName == name).getOrElse(
        fail(s"No compare report for def '$name'. Available: ${reports.map(_.defName).mkString(", ")}"))

  // ── happy path ────────────────────────────────────────────────────

  test("def f(x: Int) = x + 1 — should JIT cleanly"):
    val r = lintFor("def f(x: Int): Int = x + 1\nf(3)").forDef("f")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("stage7-refchain: local ref val getOrElse JITs on both backends"):
    val r = lintCompareFor(
      """def parse(n: Int): Option[Int] =
        |  if n > 0 then Some(n + 1) else None
        |def f(n: Int): Int =
        |  val r = parse(n)
        |  r.getOrElse(7)
        |f(1)""".stripMargin
    ).forDef("f")
    withClue(r.humanReadable):
      r.bothJit shouldBe true
      r.javac.bailReasons shouldBe empty
      r.asm.bailReasons shouldBe empty

  test("stage7-hof-method: numeric HOF chains JIT on both backends"):
    val option = lintCompareFor(
      """def lookup(k: Int): Option[Int] =
        |  if k % 2 == 0 then Some(k * 2) else None
        |def f(n: Int): Int =
        |  Some(n).flatMap(x => lookup(x)).map(x => x + 1).getOrElse(0)
        |f(2)""".stripMargin
    ).forDef("f")
    withClue(option.humanReadable):
      option.bothJit shouldBe true
      option.javac.bailReasons shouldBe empty
      option.asm.bailReasons shouldBe empty

    val list = lintCompareFor(
      """val xs: List[Int] = List(1, 2, 3, 4, 5, 6)
        |def f(): Int =
        |  xs.map(x => x * 2).filter(x => x % 3 == 0).foldLeft(0)((a, b) => a + b)
        |f()""".stripMargin
    ).forDef("f")
    withClue(list.humanReadable):
      list.bothJit shouldBe true
      list.javac.bailReasons shouldBe empty
      list.asm.bailReasons shouldBe empty

  test("bare `.length`/`.size` on a global String / collection JITs on both backends"):
    // Regression: a bare `.length` on a non-local-String receiver (a global
    // `val csv: String`, or a collection) used to bail the WHOLE enclosing loop
    // to a tree-walk (~280× slower). `walkString` can't resolve a global String,
    // so `.length` now falls back to ref-dispatch `sizeLong`.
    val r = lintCompareFor(
      """val csv: String = "1,2,3,4,5"
        |val xs: List[Int] = List(10, 20, 30)
        |def f(): Long =
        |  var sum = 0L
        |  var i = 0
        |  while i < 10 do
        |    sum = sum + csv.length.toLong + xs.length.toLong + xs.size.toLong
        |    i = i + 1
        |  sum
        |f()""".stripMargin
    ).forDef("f")
    withClue(r.humanReadable):
      r.bothJit shouldBe true
      r.javac.bailReasons shouldBe empty
      r.asm.bailReasons shouldBe empty

  test("bare `.isEmpty`/`.nonEmpty` on a global String / collection JITs on both backends"):
    // Same family as `.length`: bare zero-arg Bool accessors on a global /
    // collection receiver had no bare-Select route and bailed the whole loop.
    val r = lintCompareFor(
      """val csv: String = "hi"
        |val ys: List[Int] = List()
        |def f(): Long =
        |  var sum = 0L
        |  var i = 0
        |  while i < 10 do
        |    if csv.nonEmpty then sum = sum + 1L
        |    if ys.isEmpty then sum = sum + 100L
        |    i = i + 1
        |  sum
        |f()""".stripMargin
    ).forDef("f")
    withClue(r.humanReadable):
      r.bothJit shouldBe true
      r.javac.bailReasons shouldBe empty
      r.asm.bailReasons shouldBe empty

  test("numeric `.last.toLong` JITs on both backends (looksLongValue covers .last)"):
    // `.last`/`.head` on a numeric list return Long via lastLong/headLong.
    // `looksLongValue` must list `.last` so the wrapping `.toLong` stays on the
    // numeric path (a ref element throws at runtime → JIT guard falls back).
    val r = lintCompareFor(
      """val xs: List[Int] = List(1, 2, 3)
        |def f(): Long =
        |  var sum = 0L
        |  var i = 0
        |  while i < 10 do
        |    sum = sum + xs.last.toLong + xs.head.toLong
        |    i = i + 1
        |  sum
        |f()""".stripMargin
    ).forDef("f")
    withClue(r.humanReadable):
      r.bothJit shouldBe true
      r.javac.bailReasons shouldBe empty
      r.asm.bailReasons shouldBe empty

  // ── loop fusion (jit-loop-fusion) ─────────────────────────────────

  test("jit-loop-fusion: fusedFoldLong applies a string map op (OpStringTrimToInt) without throwing"):
    // Regression: fusedFoldLong's ListV loop called asLong(elem) BEFORE the map op,
    // which throws on a StringV — so `xs.map(s => s.trim.toInt).foldLeft(0)(_+_)`
    // (a recognised fuse) always fell back to the tree-walk (~16× slower). It must
    // apply the map op on the raw Value via applyUnary instead.
    val strs: Value = Value.ListV(List("1", " 2 ", "3").map(Value.StringV(_)))
    val sum = JitHofDispatch.fusedFoldLong(
      strs, /*hasMap*/true, JitHofDispatch.OpStringTrimToInt, 0L,
      /*hasFilter*/false, 0, 0L, 0L, /*init*/0L, JitHofDispatch.FoldAdd)
    sum shouldBe 6L
    // map + filter (keep evens) over string elements stays correct too.
    val evens = JitHofDispatch.fusedFoldLong(
      Value.ListV(List("1", "2", "3", "4").map(Value.StringV(_))),
      true, JitHofDispatch.OpStringTrimToInt, 0L,
      true, JitHofDispatch.PredModEq, 2L, 0L, 0L, JitHofDispatch.FoldAdd)
    evens shouldBe 6L  // 2 + 4

  test("jit-loop-fusion: map+filter foldLeft receiver decomposes to a FoldChain"):
    val recv = "xs.map(x => x * 2).filter(x => x % 3 == 0)".parse[Term].get
    val chain = JitHofShape.fuseFoldChain(recv)
    chain should not be null
    chain.map should not be null
    chain.map.op shouldBe JitHofDispatch.OpMul
    chain.map.c shouldBe 2L
    chain.filter should not be null
    chain.filter.pred shouldBe JitHofDispatch.PredModEq
    chain.filter.c1 shouldBe 3L
    chain.filter.c2 shouldBe 0L
    chain.base.syntax shouldBe "xs"

  test("jit-loop-fusion: map-only receiver fuses with no filter stage"):
    val recv = "(0 until n).map(x => x + 1)".parse[Term].get
    val chain = JitHofShape.fuseFoldChain(recv)
    chain should not be null
    chain.map should not be null
    chain.map.op shouldBe JitHofDispatch.OpAdd
    chain.filter shouldBe null

  test("jit-loop-fusion: filter-only receiver fuses with no map stage"):
    val recv = "xs.filter(x => x % 2 == 0)".parse[Term].get
    val chain = JitHofShape.fuseFoldChain(recv)
    chain should not be null
    chain.map shouldBe null
    chain.filter should not be null
    chain.filter.c1 shouldBe 2L

  test("jit-loop-fusion: bare receiver does not fuse"):
    JitHofShape.fuseFoldChain("xs".parse[Term].get) shouldBe null

  test("jit-loop-fusion: unrecognised lambda shape does not fuse"):
    // closure over a free variable, not a constant arith op
    JitHofShape.fuseFoldChain("xs.map(x => x + y)".parse[Term].get) shouldBe null

  test("jit-range-fusion: `until` range bounds are exclusive"):
    val rb = JitHofShape.rangeBounds("0 until n".parse[Term].get)
    rb should not be null
    rb.lo.syntax shouldBe "0"
    rb.hi.syntax shouldBe "n"
    rb.inclusive shouldBe false

  test("jit-range-fusion: `to` range bounds are inclusive"):
    val rb = JitHofShape.rangeBounds("1 to 10".parse[Term].get)
    rb should not be null
    rb.inclusive shouldBe true

  test("jit-range-fusion: non-range receiver has no bounds"):
    JitHofShape.rangeBounds("xs".parse[Term].get) shouldBe null
    JitHofShape.rangeBounds("xs.map(x => x + 1)".parse[Term].get) shouldBe null

  test("stage7-typeclass-fold: context-bound fold is classified as typeclass dispatch"):
    val r = lintCompareFor(
      """trait Monoid[A]:
        |  def empty: A
        |  def combine(a: A, b: A): A
        |given intMonoid: Monoid[Int] with
        |  def empty: Int = 0
        |  def combine(a: Int, b: Int): Int = a + b
        |def combineAll[A: Monoid](xs: List[A]): A =
        |  xs.foldLeft(summon[Monoid[A]].empty)(summon[Monoid[A]].combine)
        |combineAll(List(1, 2, 3, 4))""".stripMargin
    ).forDef("combineAll")
    withClue(r.humanReadable):
      r.bothJit shouldBe false
      r.javac.bailReasons should contain (JitBailReason.TypeclassUsingDispatch)
      r.asm.bailReasons should contain (JitBailReason.TypeclassUsingDispatch)
      r.javac.bailReasons should not contain JitBailReason.UsingParams
      r.asm.bailReasons should not contain JitBailReason.UsingParams

  test("stage7-refchain-bucket-split: primitive local/direct reads stay RefChainCall"):
    val local = classifyBody("{ val r = parse(n); r.getOrElse(7) }", List("n"), List("Int"))
    val direct = classifyBody("parse(n).getOrElse(7)", List("n"), List("Int"))
    local should contain (JitBailReason.RefChainCall)
    local should not contain JitBailReason.QualifiedRefCall
    local should not contain JitBailReason.RefChainObjectCall
    direct should contain (JitBailReason.RefChainCall)
    direct should not contain JitBailReason.QualifiedRefCall
    direct should not contain JitBailReason.RefChainObjectCall

  test("stage7-refchain-bucket-split: qualified helper calls are not RefChainCall"):
    val reasons = classifyBody("Parser.string(s)", List("s"), List("String"))
    reasons should contain (JitBailReason.QualifiedRefCall)
    reasons should not contain JitBailReason.RefChainCall

  test("stage7-refchain-bucket-split: object-producing computed chains are split"):
    val reasons = classifyBody("""Some(n).getOrElse("miss")""", List("n"), List("Int"))
    reasons should contain (JitBailReason.RefChainObjectCall)
    reasons should not contain JitBailReason.RefChainCall

  test("stage8-refchain-residual: known dispatchable methods classify as RefChainCall"):
    // Stage 8 residual fix — these shapes all dispatch through JitRefDispatch
    // helpers and should not be bucketed as RefChainObjectCall.
    val containsR  = classifyBody("Set(a, b).contains(a)", List("a", "b"), List("Int", "Int"))
    containsR should not contain JitBailReason.RefChainObjectCall
    val mkString3R = classifyBody("""xs.map(x => x + 1).mkString("[", ",", "]")""",
      List("xs"), List("List[Int]"))
    mkString3R should not contain JitBailReason.RefChainObjectCall

  test("stage7-refchain-object-dispatch: numeric object method calls are split"):
    val reasons = classifyBody("BigInt(10).pow(n)", List("n"), List("Int"))
    reasons should contain (JitBailReason.NumericObjectMethodCall)
    reasons should not contain JitBailReason.RefChainObjectCall
    reasons should not contain JitBailReason.RefChainCall

  test("stage7-unknownshape-tagging: interpolated strings are classified"):
    val reasons = classifyBody("""s"n=$n"""", List("n"), List("Int"))
    reasons should contain (JitBailReason.InterpolatedString)

  test("stage7-unknownshape-tagging: ref-like infix operators are classified"):
    val reasons = classifyBody("xs ++ ys", List("xs", "ys"), List("List[Int]", "List[Int]"))
    reasons should contain (JitBailReason.ApplyInfixRefOp)

  test("stage7-unknownshape-tagging: type applications are classified"):
    val reasons = classifyBody("identity[Int](n)", List("n"), List("Int"))
    reasons should contain (JitBailReason.TypeApplicationCall)

  // ── Stage 8: UnknownShape tail buckets ────────────────────────────────

  test("stage8-unknownshape-tail: throw expression classified as ThrowExpression"):
    val reasons = classifyBody("throw new RuntimeException(\"boom\")", List("n"), List("Int"))
    reasons should contain (JitBailReason.ThrowExpression)

  test("stage8-unknownshape-tail: tuple construction classified as TupleConstruction"):
    val reasons = classifyBody("(n, n + 1)", List("n"), List("Int"))
    reasons should contain (JitBailReason.TupleConstruction)

  test("stage8-unknownshape-tail: explicit return classified as ExplicitReturn"):
    val reasons = classifyBody("return n", List("n"), List("Int"))
    reasons should contain (JitBailReason.ExplicitReturn)

  // ── Stage 8: NonExtractPattern residual split ─────────────────────────

  test("stage8-nonextract-residual: nested tuple destructure classified as NestedTuplePattern"):
    val reasons = classifyBody("t match { case (a, (b, c)) => a + b + c }",
      List("t"), List("(Int, (Int, Int))"))
    reasons should contain (JitBailReason.NestedTuplePattern)
    reasons should not contain JitBailReason.NonExtractPattern

  test("stage8-nonextract-residual: simple typed pattern compiles (no flag)"):
    // Stage 8 typed-pattern slice: `case x: T =>` over a Type.Name compiles via
    // walkArm Pat.Typed path, so the classifier should NOT flag TypedPattern.
    val reasons = classifyBody("s match { case x: String => 1; case _ => 0 }",
      List("s"), List("Any"))
    reasons should not contain JitBailReason.TypedPattern
    reasons should not contain JitBailReason.NonExtractPattern


  test("stage8-nonextract-residual: alternative with bindings classified as AlternativeWithBindings"):
    val reasons = classifyBody("o match { case Some(x) | None => 0 }",
      List("o"), List("Option[Int]"))
    reasons should contain (JitBailReason.AlternativeWithBindings)

  test("stage7-unknownshape-tagging: for-comprehensions and new objects are classified"):
    val forReasons = classifyBody("for x <- xs yield x", List("xs"), List("List[Int]"))
    val newReasons = classifyBody("""new RuntimeException("x")""", Nil, Nil)
    forReasons should contain (JitBailReason.ForComprehension)
    newReasons should contain (JitBailReason.ObjectConstruction)

  test("stage7-unknownshape-tagging: higher-order apply expressions are classified"):
    val reasons = classifyBody("foo(n)(n)", List("n"), List("Int"))
    reasons should contain (JitBailReason.HigherOrderApplyShape)

  test("stage7-unknownshape-tagging: direct global or constructor calls are classified"):
    val ctorReasons = classifyBody("PRegex(pattern)", List("pattern"), List("String"))
    val macroReasons = classifyBody("""__ssc_macro__(plusOneImpl(__ssc_quote__("x", x)))""", List("x"), List("Int"))
    ctorReasons should contain (JitBailReason.DirectGlobalOrCtorCall)
    macroReasons should contain (JitBailReason.DirectGlobalOrCtorCall)

  test("stage7-unknownshape-tagging: known direct JIT constructors stay out of global-call bucket"):
    classifyBody("Some(n)", List("n"), List("Int")) should not contain JitBailReason.DirectGlobalOrCtorCall
    classifyBody("Right(n)", List("n"), List("Int")) should not contain JitBailReason.DirectGlobalOrCtorCall
    classifyBody("Left(n)", List("n"), List("Int")) should not contain JitBailReason.DirectGlobalOrCtorCall

  test("ADT match with guard — should JIT (walkArmAsIfBranch path)"):
    val r = lintFor(
      """sealed trait E
        |case class A(n: Int) extends E
        |def f(e: E): Int = e match
        |  case A(n) if n > 0 => n
        |  case A(n)          => -n
        |f(A(3))""".stripMargin
    ).forDef("f")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  // ── one cliff per category ────────────────────────────────────────

  test("non-extract guard (Int match) reports PatternGuard"):
    val r = lintFor(
      """def f(x: Int): Int = x match
        |  case n if n > 0 => n
        |  case n          => -n
        |f(3)""".stripMargin
    ).forDef("f")
    r.willJit shouldBe false
    r.bailReasons should contain (JitBailReason.PatternGuard)

  test("vararg param reports VarargParam"):
    val r = lintFor(
      """def f(xs: Int*): Int = xs.sum
        |f(1, 2, 3)""".stripMargin
    ).forDef("f")
    r.bailReasons should contain (JitBailReason.VarargParam)

  test("try/catch body now JITs (Stage 5.3 — simple wildcard/typed catch)"):
    val r = lintFor(
      """def f(x: Int): Int =
        |  try x + 1
        |  catch case _: Exception => 0
        |f(3)""".stripMargin
    ).forDef("f")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("try/catch with finally still reports TryCatch"):
    val r = lintFor(
      """def f(x: Int): Int =
        |  try x + 1
        |  catch case _: Exception => 0
        |  finally {}
        |f(3)""".stripMargin
    ).forDef("f")
    r.bailReasons should contain (JitBailReason.TryCatch)

  test("bool-returning body now JITs (compiled as 0/1 long, unwrapped to BoolV at runtime)"):
    val r = lintFor(
      """def f(x: Int): Boolean = x > 0
        |f(3)""".stripMargin
    ).forDef("f")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("zero-param function with a constant body now JITs"):
    val r = lintFor(
      """def f(): Int = 42
        |f()""".stripMargin
    ).forDef("f")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("three-param function compiles (Stage 4 ceiling lifted to 3)"):
    val r = lintFor(
      """def f(a: Int, b: Int, c: Int): Int = a + b + c
        |f(1, 2, 3)""".stripMargin
    ).forDef("f")
    r.willJit shouldBe true

  test("four-param function reports TooManyParams"):
    val r = lintFor(
      """def g(a: Int, b: Int, c: Int, d: Int): Int = a + b + c + d
        |g(1, 2, 3, 4)""".stripMargin
    ).forDef("g")
    r.willJit shouldBe false
    r.bailReasons should contain (JitBailReason.TooManyParams(4))

  // ── multi-def module ──────────────────────────────────────────────

  test("multiple defs — JITable one stays JITable alongside bails"):
    val r = lintFor(
      """def jitable(x: Int): Int = x + 1
        |def withGuard(x: Int): Int = x match
        |  case n if n > 0 => n
        |  case n          => -n
        |def withVararg(xs: Int*): Int = xs.sum
        |def withTry(x: Int): Int =
        |  try x / 2
        |  catch case _: Exception => -1
        |def withBool(x: Int): Boolean = x > 0
        |def withZero(): Int = 0
        |def withThree(a: Int, b: Int, c: Int): Int = a + b + c
        |jitable(3)""".stripMargin
    )
    r.forDef("jitable").willJit shouldBe true
    r.forDef("withGuard").bailReasons should contain (JitBailReason.PatternGuard)
    r.forDef("withTry").willJit shouldBe true  // Stage 5.3: simple try/catch now compiles
    r.forDef("withVararg").bailReasons should contain (JitBailReason.VarargParam)
    r.forDef("withBool").willJit shouldBe true   // bool bodies now compile (0/1 encoded)
    r.forDef("withZero").willJit shouldBe true
    r.forDef("withThree").willJit shouldBe true   // arity 3 now supported (Stage 4)

  // ── recursive ADT eval ─────────────────────────────────────────────

  test("recursive ADT eval ObjToLong — should JIT (arm self-calls)"):
    val code =
      """sealed trait Expr
        |case class Num(n: Int) extends Expr
        |case class Add(l: Expr, r: Expr) extends Expr
        |case class Mul(l: Expr, r: Expr) extends Expr
        |def eval(e: Expr): Int = e match
        |  case Num(n)    => n
        |  case Add(l, r) => eval(l) + eval(r)
        |  case Mul(l, r) => eval(l) * eval(r)
        |eval(Num(1))""".stripMargin
    val r = lintFor(code).forDef("eval")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("recursive ADT eval — JIT direct interface evaluates tree correctly"):
    val src = s"# Test\n\n```scalascript\n${
      """sealed trait Expr
        |case class Num(n: Int) extends Expr
        |case class Add(l: Expr, r: Expr) extends Expr
        |case class Mul(l: Expr, r: Expr) extends Expr
        |def eval(e: Expr): Int = e match
        |  case Num(n)    => n
        |  case Add(l, r) => eval(l) + eval(r)
        |  case Mul(l, r) => eval(l) * eval(r)
        |def build(d: Int): Expr =
        |  if d <= 0 then Num(1)
        |  else Add(build(d - 1), Mul(build(d - 1), Num(2)))
        |val tree = build(3)
        |eval(tree)""".stripMargin}\n```\n"
    val module = scalascript.parser.Parser.parse(src)
    val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    val interp = Interpreter(devNull)
    interp.runSections(module)
    import scalascript.interpreter.Value
    import scalascript.interpreter.vm.jit.{JitBackend, JitGlobals, LongToObject, ObjToLong}
    val evalFun = interp.exportedGlobals("eval")
    val jitR = JitBackend.default.tryCompile(evalFun.asInstanceOf[Value.FunV], interp)
    assert(jitR != null, "eval should JIT-compile")
    assert(jitR.direct != null, "eval JitResult should have a direct interface")
    assert(jitR.direct.isInstanceOf[ObjToLong], s"direct should be ObjToLong, got ${jitR.direct.getClass.getName}")
    val tree = interp.exportedGlobals("tree")
    assert(tree.isInstanceOf[Value.InstanceV], "tree should be an InstanceV")
    val result = JitGlobals.withInterp(interp) {
      jitR.direct.asInstanceOf[ObjToLong].apply(tree.asInstanceOf[AnyRef])
    }
    // build(3) = Add(build(2), Mul(build(2), Num(2))); eval(build(2))=9 → 9 + 9*2 = 27
    assert(result == 27L, s"eval(build(3)) via JIT should be 27, got $result")
    val buildFun = interp.exportedGlobals("build")
    val buildJit = JitBackend.default.tryCompile(buildFun.asInstanceOf[Value.FunV], interp)
    assert(buildJit != null, "build should JIT-compile")
    assert(buildJit.direct != null, "build JitResult should have a direct interface")
    assert(buildJit.direct.isInstanceOf[LongToObject], s"direct should be LongToObject, got ${buildJit.direct.getClass.getName}")
    val built = JitGlobals.withInterp(interp) {
      buildJit.direct.asInstanceOf[LongToObject].apply(3L)
    }
    assert(built.isInstanceOf[Value.InstanceV], "build(3) via JIT should return an InstanceV")
    val builtResult = JitGlobals.withInterp(interp) {
      jitR.direct.asInstanceOf[ObjToLong].apply(built)
    }
    assert(builtResult == 27L, s"eval(build(3) via LongToObject) should be 27, got $builtResult")

  // ── 2-param recursive ADT eval (gEval) ───────────────────────────

  test("gEval (scale: Int, e: Expr) — should JIT (2-param arm self-calls)"):
    val r = lintFor(
      """sealed trait Expr
        |case class Num(n: Int) extends Expr
        |case class Add(l: Expr, r: Expr) extends Expr
        |case class Mul(l: Expr, r: Expr) extends Expr
        |def gEval(scale: Int, e: Expr): Int = e match
        |  case Num(n)    => n * scale
        |  case Add(l, r) => gEval(scale, l) + gEval(scale, r)
        |  case Mul(l, r) => gEval(scale, l) * gEval(scale, r)
        |gEval(2, Num(1))""".stripMargin
    ).forDef("gEval")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("gEval — JIT direct interface evaluates correctly"):
    val src = s"# Test\n\n```scalascript\n${
      """sealed trait Expr
        |case class Num(n: Int) extends Expr
        |case class Add(l: Expr, r: Expr) extends Expr
        |case class Mul(l: Expr, r: Expr) extends Expr
        |def gEval(scale: Int, e: Expr): Int = e match
        |  case Num(n)    => n * scale
        |  case Add(l, r) => gEval(scale, l) + gEval(scale, r)
        |  case Mul(l, r) => gEval(scale, l) * gEval(scale, r)
        |val tree = Add(Num(1), Mul(Num(2), Num(3)))
        |gEval(2, tree)""".stripMargin}\n```\n"
    val module = scalascript.parser.Parser.parse(src)
    val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    val interp = Interpreter(devNull)
    interp.runSections(module)
    import scalascript.interpreter.Value
    import scalascript.interpreter.vm.jit.{JitBackend, JitGlobals}
    import scalascript.interpreter.vm.jit.LongObjToLong
    val gEvalFun = interp.exportedGlobals("gEval")
    val jitR = JitBackend.default.tryCompile(gEvalFun.asInstanceOf[Value.FunV], interp)
    assert(jitR != null, "gEval should JIT-compile")
    assert(jitR.direct != null, "gEval JitResult should have a direct interface")
    assert(jitR.direct.isInstanceOf[LongObjToLong], s"direct should be LongObjToLong, got ${jitR.direct.getClass.getName}")
    val tree = interp.exportedGlobals("tree")
    assert(tree.isInstanceOf[Value.InstanceV], "tree should be an InstanceV")
    // gEval(2, Add(Num(1), Mul(Num(2), Num(3)))) = (1*2) + (2*2)*(3*2) = 2 + 24 = 26
    val result = JitGlobals.withInterp(interp) {
      jitR.direct.asInstanceOf[LongObjToLong].apply(2L, tree.asInstanceOf[AnyRef])
    }
    assert(result == 26L, s"gEval(2, Add(Num(1),Mul(Num(2),Num(3)))) via JIT should be 26, got $result")

  // ── wildcard / catch-all arms ─────────────────────────────────────

  test("ADT match with wildcard catch-all arm — should JIT"):
    val r = lintFor(
      """sealed trait Expr
        |case class Num(n: Int) extends Expr
        |case class Add(l: Expr, r: Expr) extends Expr
        |def isNum(e: Expr): Int = e match
        |  case Num(n) => 1
        |  case _      => 0
        |isNum(Num(1))""".stripMargin
    ).forDef("isNum")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("ADT match with wildcard catch-all arm — evaluates correctly via JIT"):
    val src = s"# Test\n\n```scalascript\n${
      """sealed trait Expr
        |case class Num(n: Int) extends Expr
        |case class Add(l: Expr, r: Expr) extends Expr
        |def isNum(e: Expr): Int = e match
        |  case Num(n) => 1
        |  case _      => 0
        |val a = isNum(Num(42))
        |val b = isNum(Add(Num(1), Num(2)))
        |a + b * 10""".stripMargin}\n```\n"
    val module = scalascript.parser.Parser.parse(src)
    val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    val interp = Interpreter(devNull)
    interp.runSections(module)
    import scalascript.interpreter.Value
    interp.exportedGlobals.get("a") match
      case Some(Value.IntV(v)) => assert(v == 1L, s"isNum(Num(42)) should be 1, got $v")
      case other => fail(s"unexpected a=$other")
    interp.exportedGlobals.get("b") match
      case Some(Value.IntV(v)) => assert(v == 0L, s"isNum(Add(...)) should be 0, got $v")
      case other => fail(s"unexpected b=$other")

  test("ADT match with named catch-all arm (Pat.Var) — should JIT"):
    val r = lintFor(
      """sealed trait Shape
        |case class Circle(r: Double) extends Shape
        |case class Rect(w: Double, h: Double) extends Shape
        |def classify(s: Shape): Int = s match
        |  case Circle(_) => 1
        |  case other     => 2
        |classify(Circle(1.0))""".stripMargin
    ).forDef("classify")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  // ── fallback ──────────────────────────────────────────────────────

  test("function with no detectable cliff but JIT bail reports UnknownShape"):
    // `def f(x: String) = x.length` — JIT won't handle String params today,
    // but the AST walk sees only Term.Select; classifier falls through.
    val r = lintFor(
      """def f(x: String): Int = x.length
        |f("hi")""".stripMargin
    ).forDef("f")
    r.willJit shouldBe false
    r.bailReasons should not be empty

  // ── explicit backend parameter ────────────────────────────────────

  test("lintFun with explicit JavacJitBackend — same result as default"):
    val r = lintFor("def f(x: Int): Int = x + 1\nf(3)").forDef("f")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("lintFun with explicit AsmJitBackend — simple arith JITs on ASM"):
    val r = lintForAsm("def f(x: Int): Int = x + 1\nf(3)").forDef("f")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("lintFun with AsmJitBackend — zero-param constant body JITs"):
    val r = lintForAsm("def f(): Int = 42\nf()").forDef("f")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("lintFun with AsmJitBackend — simple try/catch now JITs (Stage 5.3)"):
    val r = lintForAsm(
      """def f(x: Int): Int = try x + 1 catch case _: Exception => 0
        |f(3)""".stripMargin
    ).forDef("f")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("lintFun with AsmJitBackend — block-body f(x) = { val y = x+1; y } JITs"):
    val r = lintForAsm(
      """def f(x: Int): Int = { val y = x + 1; y }
        |f(3)""".stripMargin
    ).forDef("f")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("lintFun with AsmJitBackend — if-body f(x) = if x>0 then x else -x JITs"):
    val r = lintForAsm(
      """def f(x: Int): Int = if x > 0 then x else -x
        |f(3)""".stripMargin
    ).forDef("f")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  // ── lintInterpreterCompare ───────────────────────────────────────

  test("lintInterpreterCompare — simple arith shows BOTH OK"):
    val r = lintCompareFor("def f(x: Int): Int = x + 1\nf(3)").forDef("f")
    r.bothJit shouldBe true
    r.asmOnlyFails shouldBe false
    r.javacOnlyFails shouldBe false

  test("lintInterpreterCompare — zero-param constant body shows BOTH OK"):
    val r = lintCompareFor("def f(): Int = 42\nf()").forDef("f")
    r.bothJit shouldBe true
    r.asmOnlyFails shouldBe false
    r.javacOnlyFails shouldBe false

  test("lintInterpreterCompare — 3-param now shows BOTH OK (Stage 4)"):
    val r = lintCompareFor("def f(a: Int, b: Int, c: Int): Int = a+b+c\nf(1,2,3)").forDef("f")
    r.bothFail shouldBe false

  test("lintInterpreterCompare — 4-param TooManyParams shows BOTH FAIL"):
    val r = lintCompareFor("def f(a: Int, b: Int, c: Int, d: Int): Int = a+b+c+d\nf(1,2,3,4)").forDef("f")
    r.bothFail shouldBe true
    r.javac.bailReasons should contain (JitBailReason.TooManyParams(4))
    r.asm.bailReasons   should contain (JitBailReason.TooManyParams(4))

  test("lintInterpreterCompare — ADT eval JITs on both backends"):
    val r = lintCompareFor(
      """sealed trait Expr
        |case class Num(n: Int) extends Expr
        |case class Add(l: Expr, r: Expr) extends Expr
        |def eval(e: Expr): Int = e match
        |  case Num(n)    => n
        |  case Add(l, r) => eval(l) + eval(r)
        |eval(Num(1))""".stripMargin
    ).forDef("eval")
    r.bothJit shouldBe true

  test("lintInterpreterCompare — humanReadable contains JAVAC and ASM tags"):
    val r = lintCompareFor("def f(x: Int): Int = x + 1\nf(3)").forDef("f")
    val txt = r.humanReadable
    txt should include ("[JAVAC OK]")
    txt should include ("[ASM OK]")

  // ── while-loop coverage ─────────────────────────────────────────────

  /** Helper: load a module and return the while-loop lint reports. */
  private def lintWhileFor(code: String): List[scalascript.interpreter.vm.jit.JitLintWhileReport] =
    lintWhileForBackend(code, JavacJitBackend)

  private def lintWhileForBackend(
    code:    String,
    backend: scalascript.interpreter.vm.jit.JitBackend
  ): List[scalascript.interpreter.vm.jit.JitLintWhileReport] =
    val src    = s"# Test\n\n```scalascript\n$code\n```\n"
    val module = Parser.parse(src)
    val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    val interp = Interpreter(devNull)
    interp.runSections(module)
    JitLint.lintWhileLoops(interp, backend)

  test("simple Int counter while — lintWhileLoops reports [JIT OK]"):
    val reports = lintWhileFor(
      """|var i = 0
         |while i < 10 do
         |  i = i + 1""".stripMargin
    )
    reports should not be empty
    reports.head.willJit shouldBe true
    reports.head.bailReasons shouldBe empty

  test("while loop not executed — does not appear in lintWhileLoops"):
    // The condition is false from the start, so EvalRuntime never reaches the
    // JIT path and the cache has no entry for this while node.
    val reports = lintWhileFor(
      """|var i = 0
         |while i > 100 do
         |  i = i + 1""".stripMargin
    )
    reports shouldBe empty

  test("while with unsupported condition — reports WhileCondShape"):
    // A string comparison that the while-JIT cond walker can't handle,
    // but wraps a supported inner body so the body check would pass.
    // We use a loop whose cond is a method call on a non-slot expression.
    // The simplest way: an Int condition that uses a nested function call
    // that the while-cond walker doesn't support (not an infix comparison).
    val reports = lintWhileFor(
      """|def pred(n: Int): Boolean = n < 5
         |var i = 0
         |while pred(i) do
         |  i = i + 1""".stripMargin
    )
    // pred(i) is a Term.Apply, not an infix comparison — WhileCondShape expected
    reports should not be empty
    reports.head.willJit shouldBe false
    reports.head.bailReasons should contain (JitBailReason.WhileCondShape)

  test("while with two Int assigns — lintWhileLoops reports [JIT OK]"):
    val reports = lintWhileFor(
      """|var i = 0
         |var acc = 0
         |while i < 100 do
         |  acc = acc + i
         |  i = i + 1""".stripMargin
    )
    reports should not be empty
    reports.head.willJit shouldBe true

  test("lintWhileLoops — zero top-level while loops returns empty list"):
    val reports = lintWhileFor("def f(x: Int): Int = x + 1\nf(3)")
    reports shouldBe empty

  test("lintWhileLoops — multiple loops sorted by condition line"):
    val reports = lintWhileFor(
      """|var i = 0
         |var j = 0
         |while i < 5 do
         |  i = i + 1
         |while j < 3 do
         |  j = j + 1""".stripMargin
    )
    reports should have size 2
    reports.forall(_.willJit) shouldBe true
    // Reports are sorted by condLine; first loop's line < second loop's line
    for
      r1 <- reports.headOption
      r2 <- reports.tail.headOption
      l1 <- r1.condLine
      l2 <- r2.condLine
    do l1 should be < l2

  test("lintWhileLoops with AsmJitBackend — compiled loop reports [JIT OK]"):
    val reports = lintWhileForBackend(
      """|var i = 0
         |while i < 10 do
         |  i = i + 1""".stripMargin,
      AsmJitBackend
    )
    reports should not be empty
    reports.head.willJit shouldBe true

  test("JitLintWhileReport.humanReadable — [JIT OK] when compiled"):
    val reports = lintWhileFor(
      """|var i = 0
         |while i < 10 do
         |  i = i + 1""".stripMargin
    )
    reports should not be empty
    reports.head.humanReadable should include ("[JIT OK]")

  test("JitLintWhileReport.humanReadable — [will NOT JIT] when bail"):
    val reports = lintWhileFor(
      """|def pred(n: Int): Boolean = n < 5
         |var i = 0
         |while pred(i) do
         |  i = i + 1""".stripMargin
    )
    reports should not be empty
    val txt = reports.head.humanReadable
    txt should include ("[will NOT JIT]")

  test("lintWhileLoopsCompare — compiled loop reports BOTH OK"):
    val src    = s"# Test\n\n```scalascript\n" +
      """|var i = 0
         |while i < 10 do
         |  i = i + 1""".stripMargin + "\n```\n"
    val module = Parser.parse(src)
    val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    val interp = Interpreter(devNull)
    interp.runSections(module)
    val reports = JitLint.lintWhileLoopsCompare(interp)
    reports should not be empty
    reports.head.bothJit shouldBe true
    reports.head.asmOnlyFails shouldBe false

  test("lintWhileLoopsCompare — humanReadable contains JAVAC and ASM tags"):
    val src    = s"# Test\n\n```scalascript\n" +
      """|var i = 0
         |while i < 10 do
         |  i = i + 1""".stripMargin + "\n```\n"
    val module = Parser.parse(src)
    val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    val interp = Interpreter(devNull)
    interp.runSections(module)
    val reports = JitLint.lintWhileLoopsCompare(interp)
    reports should not be empty
    val txt = reports.head.humanReadable
    txt should include ("[JAVAC OK]")
    txt should include ("[ASM OK]")

  // ── Stage 5.4: Pat.Alternative (`case A | B =>`) ────────────────────────────

  test("stage5.4: Pat.Alternative arm — Javac lints as willJit"):
    val r = lintFor(
      """sealed trait Tok
        |case class TokA(v: Int) extends Tok
        |case class TokB(v: Int) extends Tok
        |case class TokC(v: Int) extends Tok
        |def classify(t: Tok): Int = t match
        |  case TokA(_) | TokB(_) => 1
        |  case TokC(_) => 2""".stripMargin
    ).forDef("classify")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("stage5.4: Pat.Alternative arm — ASM lints as willJit"):
    val r = lintForAsm(
      """sealed trait Tok
        |case class TokA(v: Int) extends Tok
        |case class TokB(v: Int) extends Tok
        |case class TokC(v: Int) extends Tok
        |def classify(t: Tok): Int = t match
        |  case TokA(_) | TokB(_) => 1
        |  case TokC(_) => 2""".stripMargin
    ).forDef("classify")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("stage5.4: Pat.Alternative arm — lintInterpreterCompare shows BOTH OK"):
    val r = lintCompareFor(
      """sealed trait Tok
        |case class TokA(v: Int) extends Tok
        |case class TokB(v: Int) extends Tok
        |case class TokC(v: Int) extends Tok
        |def classify(t: Tok): Int = t match
        |  case TokA(_) | TokB(_) => 1
        |  case TokC(_) => 2""".stripMargin
    ).forDef("classify")
    r.javac.willJit shouldBe true
    r.asm.willJit   shouldBe true
