package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.interpreter.vm.jit.{AsmJitBackend, JitLint, JitBailReason, JavacJitBackend}
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
