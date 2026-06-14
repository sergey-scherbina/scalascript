package scalascript.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scalascript.interpreter.{Interpreter, InterpretError}
import scalascript.parser.Parser
import scalascript.ast.Module
import scala.meta.*

/** JMH microbenchmarks for the ScalaScript interpreter hot paths.
 *
 *  Run via sbt:
 *    sbt "interpreterBench/Jmh/run -i 5 -wi 3 -f 1 .*arithLoop.*"
 *
 *  Or run all:
 *    sbt "interpreterBench/Jmh/run"
 *
 *  Results are printed to stdout in JMH table format; save to file with:
 *    sbt "interpreterBench/Jmh/run -rff bench/jmh-results.json -rf json"
 */

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class InterpreterBench:

  // ── pre-parsed modules ───────────────────────────────────────────

  private def src(code: String): Module =
    Parser.parse(s"# Bench\n\n```scalascript\n$code\n```\n")

  private val modArithLoop: Module = src(
    """var i = 0
      |var sum = 0
      |while i < 1000000 do
      |  sum = sum + i
      |  i = i + 1
      |sum""".stripMargin
  )

  private val modFib: Module = src(
    """def fib(n: Int): Int =
      |  if n <= 1 then n else fib(n - 1) + fib(n - 2)
      |fib(30)""".stripMargin
  )

  private val modTco: Module = src(
    """def sumTco(n: Int, acc: Int): Int =
      |  if n <= 0 then acc else sumTco(n - 1, acc + n)
      |sumTco(100000, 0)""".stripMargin
  )

  private val modPatternMatch: Module = src(
    """sealed trait Shape
      |case class Circle(r: Double) extends Shape
      |case class Rect(w: Double, h: Double) extends Shape
      |case class Triangle(b: Double, h: Double) extends Shape
      |def area(s: Shape): Double = s match
      |  case Circle(r)      => 3.14159 * r * r
      |  case Rect(w, h)     => w * h
      |  case Triangle(b, h) => 0.5 * b * h
      |val shapes = List(Circle(1.0), Rect(2.0, 3.0), Triangle(4.0, 5.0))
      |var total = 0.0
      |var i = 0
      |while i < 1000 do
      |  shapes.foreach(s => { total = total + area(s) })
      |  i = i + 1
      |total""".stripMargin
  )


  // Wide ADT (12 constructors) with Int case bodies — exercises pure-body
  // compilation across many constructors without Double-boxing noise.
  private val modPatternMatchWide: Module = src(
    """sealed trait Op
      |case class A(x: Int) extends Op
      |case class B(x: Int) extends Op
      |case class C(x: Int) extends Op
      |case class D(x: Int) extends Op
      |case class E(x: Int) extends Op
      |case class F(x: Int) extends Op
      |case class G(x: Int) extends Op
      |case class H(x: Int) extends Op
      |case class I(x: Int) extends Op
      |case class J(x: Int) extends Op
      |case class K(x: Int) extends Op
      |case class L(x: Int) extends Op
      |def eval(o: Op): Int = o match
      |  case A(x) => x + 1
      |  case B(x) => x + 2
      |  case C(x) => x + 3
      |  case D(x) => x + 4
      |  case E(x) => x + 5
      |  case F(x) => x + 6
      |  case G(x) => x + 7
      |  case H(x) => x + 8
      |  case I(x) => x + 9
      |  case J(x) => x + 10
      |  case K(x) => x + 11
      |  case L(x) => x + 12
      |val ops = List(A(1), B(1), C(1), D(1), E(1), F(1), G(1), H(1), I(1), J(1), K(1), L(1))
      |var total = 0
      |var i = 0
      |while i < 50000 do
      |  ops.foreach(o => { total = total + eval(o) })
      |  i = i + 1
      |total""".stripMargin
  )

  // Recursive ADT tree-walk: a classic expression evaluator dispatching over
  // constructors and recursing into children — the canonical interpreter
  // workload (and the target of VM 2a). The tree is built once; the loop only
  // measures the recursive `eval`, not construction.
  private val modRecursiveEval: Module = src(
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
      |val tree = build(8)
      |var total = 0
      |var i = 0
      |while i < 1000 do
      |  total = total + eval(tree)
      |  i = i + 1
      |total""".stripMargin
  )

  private val modEffectPure: Module = src(
    """def compute(n: Int): Int ! Logger =
      |  var acc = 0
      |  var i = 0
      |  while i < 10000 do
      |    acc = acc + i
      |    i = i + 1
      |  acc
      |runLogger { compute(10000) }""".stripMargin
  )

  private val modEffectStream: Module = src(
    """val (emitted, _) = runStream {
      |  var i = 0
      |  while i < 10000 do
      |    Stream.emit(i)
      |    i = i + 1
      |}
      |emitted.length""".stripMargin
  )

  // Effect perf trackers (added 2026-06-12). These isolate the two algebraic-
  // effect regimes that bound the direct-style-eval design (specs/
  // direct-style-eval-spec.md §10):
  //   - one-shot: `resume` called exactly once → in principle expressible by the
  //     `EffectPerform` exception fast-path; measures per-`perform` dispatch cost.
  //   - multi-shot: `resume` called once per option → REQUIRES the re-callable
  //     monadic continuation (the trampoline); cannot use the exception model.
  // Compare against `effectPure` (pure body inside an effect context — the pure
  // tree-walked overhead direct-style targets).
  private val modEffectOneShot: Module = src(
    """effect Bump:
      |  def tick(): Int
      |def loop(n: Int): Int ! Bump =
      |  var acc = 0
      |  var i = 0
      |  while i < n do
      |    acc = acc + Bump.tick()
      |    i = i + 1
      |  acc
      |handle(loop(5000)) {
      |  case Bump.tick(resume) => resume(1)
      |}""".stripMargin
  )

  // effect-vm-continuations P2b: an ARG-carrying effect op `Reader.ask(i)` in a loop, handled
  // by a one-shot tail-resume arm that uses the op-arg. Before P2b only 0-arg effect ops JIT'd
  // (the loop tree-walked); now the bridge passes the numeric arg to the resolver so the body
  // JITs. Honest: the effect runs each iteration, resolved through the handler (i-dependent).
  private val modEffectReader: Module = src(
    """effect Reader:
      |  def ask(k: Int): Int
      |def loop(n: Int): Int ! Reader =
      |  var acc = 0
      |  var i = 0
      |  while i < n do
      |    acc = acc + Reader.ask(i)
      |    i = i + 1
      |  acc
      |handle(loop(5000)) {
      |  case Reader.ask(k, resume) => resume(k * 2)
      |}""".stripMargin
  )

  private val modEffectMultiShot: Module = src(
    """multi effect NonDet:
      |  def choose(options: List[Int]): Int
      |def program(): Int ! NonDet =
      |  val a = NonDet.choose(List(1, 2, 3, 4))
      |  val b = NonDet.choose(List(1, 2, 3, 4))
      |  val c = NonDet.choose(List(1, 2, 3, 4))
      |  val d = NonDet.choose(List(1, 2, 3, 4))
      |  a + b + c + d
      |handle(program()) {
      |  case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt))
      |}.length""".stripMargin
  )

  // effect-vm-cont-p3b: a DEEPER multi-shot search with INTERLEAVED per-step scoring — the
  // representative nondeterminism workload (constraint search) the P3 continuation-compile feature
  // targets. 5 levels × 5 options = 3125 paths; each continuation segment between `choose`s does a
  // real `x*x + acc` step, so the per-resume continuation RE-EVALUATION (AST re-walk + Free-monad
  // rebuild) dominates — unlike `effectMultiShot` (256 trivial paths) whose cost is partly per-op
  // `Interpreter` construction. `.length` = 3125 (deterministic).
  private val modEffectMultiShotDeep: Module = src(
    """multi effect NonDet:
      |  def choose(options: List[Int]): Int
      |def search(): Int ! NonDet =
      |  val a = NonDet.choose(List(1, 2, 3, 4, 5))
      |  val sa = a * a
      |  val b = NonDet.choose(List(1, 2, 3, 4, 5))
      |  val sb = b * b + sa
      |  val c = NonDet.choose(List(1, 2, 3, 4, 5))
      |  val sc = c * c + sb
      |  val d = NonDet.choose(List(1, 2, 3, 4, 5))
      |  val sd = d * d + sc
      |  val e = NonDet.choose(List(1, 2, 3, 4, 5))
      |  e * e + sd
      |handle(search()) {
      |  case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt))
      |}.length""".stripMargin
  )

  // honesty (T2.1): the `++` operands are built from the loop counter and every
  // component is accumulated, so the tuple-monoid concat genuinely runs each
  // iteration. The old `(1, 2) ++ (3, 4)` was loop-invariant — `tryHoistedPureWhile`
  // hoisted it out and the empty counter loop folded (0.008 ms, measuring nothing).
  // 1_000 iters: the honest tuple-`++` path is ~20 us/iter in the interp
  // (typeclass dispatch, not JIT'd), so a larger count would make this a
  // multi-hundred-ms suite outlier without changing the per-iteration signal.
  private val modTupleMonoid: Module = src(
    """var i = 0
      |var s = 0
      |while i < 1000 do
      |  val t = (i, i + 1) ++ (i + 2, i + 3)
      |  s = s + t._1 + t._2 + t._3 + t._4
      |  i = i + 1
      |s""".stripMargin
  )

  // val-intermediate accumulation: a leading `val d = <pure-arith>` in the loop body,
  // used in the assignment(s). A ubiquitous real-code shape (compute an intermediate,
  // accumulate it). Before `jit-loop-val-inline`, ANY leading `val` bailed the while-JIT
  // → tree-walk; now the pure-arith val is inlined (d → i - 500) so the body is pure
  // assignments the Long-while JIT compiles. Honest: `s` accumulates an i-dependent value
  // (sum of squared deviations), not folded away. 1M iters.
  private val modValIntermediate: Module = src(
    """var i = 0
      |var s = 0
      |while i < 1000000 do
      |  val d = i - 500
      |  s = s + d * d
      |  i = i + 1
      |s""".stripMargin
  )

  // MULTIPLE + chained leading vals in a loop body (jit-loop-val-extensions). Before, a
  // SECOND leading `val` made collectFastAssignBody bail → tree-walk; now all leading
  // pure-arith/tuple vals (incl. chains like `val b = a * 2`) are inlined so the body is
  // pure assignments the Long-while JIT compiles. Honest: i-dependent accumulation. 1M iters.
  private val modMultiVal: Module = src(
    """var i = 0
      |var s = 0
      |while i < 1000000 do
      |  val a = i - 500
      |  val b = a * 2
      |  s = s + a * a + b
      |  i = i + 1
      |s""".stripMargin
  )

  // Conditional intermediate (jit-loop-if-val): `val x = if … then … else …` in a loop body —
  // conditional accumulation (abs/clamp/select), very common. The pure `if` is now admitted to
  // isPureArith, so the val is inlined and the body JITs (the bytecode JIT compiles Term.If).
  private val modValIf: Module = src(
    """var i = 0
      |var s = 0
      |while i < 1000000 do
      |  val x = if i % 2 == 0 then i else 0 - i
      |  s = s + x
      |  i = i + 1
      |s""".stripMargin
  )

  // Match intermediate (jit-loop-match-val): `val x = p match { case Ctor(a,b) => … }` in a
  // loop body. Previously a `val` bound to a `match` bailed the while-JIT → whole loop
  // tree-walked; now a pure match is admitted to isPureArith and inlined, so the body JITs via
  // the same ref-destructuring match-compile as instanceFieldAccess (~3000×). (An Int/literal-
  // scrutinee match inlines correctly but bails to tree-walk — the JIT compiles only the
  // ref-destructuring form.) Honest: `s` accumulates an i-dependent value, same shape as
  // instanceFieldAccess (constant scrutinee matched per iteration).
  private val modValMatch: Module = src(
    """case class Pt(a: Int, b: Int)
      |val p = Pt(3, 4)
      |var i = 0
      |var s = 0
      |while i < 1000000 do
      |  val x = p match
      |    case Pt(a, b) => a + b
      |  s = s + x + i
      |  i = i + 1
      |s""".stripMargin
  )

  // val-bound constant tuple: `last = k` where k is a val.
  // Without the Term.Name hoist, tryHoistedPureWhile bails and falls through
  // to the value-space loop (~65 ms for 1M iters, similar to counterWithTupleVar).
  // With the fix, `k` is recognised as pure, hoisted once, and the loop runs
  // through tryLongWhileAssign (~2 ms for 1M iters — 33× speedup).
  private val modTupleMonoidVal: Module = src(
    """val k = (1, 2, 3, 4)
      |var i = 0
      |var last = (0, 0, 0, 0)
      |while i < 1000000 do
      |  last = k
      |  i = i + 1
      |last""".stripMargin
  )

  // `last = last` is a self-assignment no-op. `tryHoistedPureWhile` now recognises
  // this pattern and hoists it out, leaving only `i = i + 1` for the fast unboxed
  // path — bringing this down from ~65 ms to ~0.25 ms for 1M iters (arithLoop parity).
  private val modCounterWithTupleVar: Module = src(
    """var i = 0
      |var last = (0, 0, 0, 0)
      |while i < 1000000 do
      |  last = last
      |  i = i + 1
      |last""".stripMargin
  )

  // Pure non-match-bodied 1-param function called in a tight loop with an Int
  // accumulator — exercises the A4 (Tier-2b pure-call) target. `pureCallValue`
  // historically only direct-style-ran match-bodied funcs; A4 generalizes that
  // to any pure compilable body (here just `x + 1`) via
  // `PatternRuntime.compileExpr`/`compileExprSlot1`, returning the result as
  // a bare `Value` so the enclosing `total + f(i)` skips the per-call `Pure`
  // wrapper and the param-frame allocation.
  private val modPureCallSum: Module = src(
    """def f(x: Int): Int = x + 1
      |var total = 0
      |var i = 0
      |while i < 1000000 do
      |  total = total + f(i)
      |  i = i + 1
      |total""".stripMargin
  )

  // 2-param parallel of `pureCallSum` — exercises the LApply2 raw-Long arg
  // inlining inside `tryLongWhileAssign`. `g(x, y) = x + y` keeps the body in
  // the `compileSlotD` arith subset; both args read as raw Long via DSlot(0)
  // / DSlot(1), result stays in the unboxed-Long while loop.
  private val modPureCallSum2: Module = src(
    """def g(x: Int, y: Int): Int = x + y
      |var total = 0
      |var i = 0
      |while i < 1000000 do
      |  total = total + g(i, i)
      |  i = i + 1
      |total""".stripMargin
  )

  // Direction A.2 validation: 1-param pure fn whose body is a `Term.If`
  // expression. Before the walkLocalSlotCtx Term.If extension, `walkLocalSlot`
  // bailed on the `if x < 0 then -x else x` body, forcing the call site to
  // route through the LExpr LApply path (~13 ms). After A.2, the callee
  // compiles to a Java ternary inside the same while-JIT class, matching
  // pureCallSum's JVM-parity speed.
  private val modPureCallSumIf: Module = src(
    """def absIf(x: Int): Int = if x < 0 then -x else x
      |var total = 0
      |var i = 0
      |while i < 1000000 do
      |  total = total + absIf(i)
      |  i = i + 1
      |total""".stripMargin
  )

  // Direction A.1 validation: 1-param pure fn whose body is a single-stmt
  // block (`{ x + 1 }`). Same JIT-able subset as pureCallSum but with an
  // extra Term.Block wrapper that walkLocalSlotCtx bailed on before A.1.
  private val modPureCallSumBlock: Module = src(
    """def fblk(x: Int): Int = { x + 1 }
      |var total = 0
      |var i = 0
      |while i < 1000000 do
      |  total = total + fblk(i)
      |  i = i + 1
      |total""".stripMargin
  )

  // Recursive int fib whose base case multiplies by a top-level `val`
  // constant — exercises the BytecodeJit free-name (global) read path. Before
  // the globals extension, the `mul` reference forces `walkLong` to bail and
  // the function falls through to `SscVm.exec`.
  private val modFibMul: Module = src(
    """val mul = 7
      |def fibMul(n: Int): Int =
      |  if n <= 1 then n * mul else fibMul(n - 1) + fibMul(n - 2)
      |fibMul(30)""".stripMargin
  )

  // Double-typed recursive fib — exercises the BytecodeJit Double subset
  // (params + return as `double`, `Lit.Double` constants, double arithmetic).
  private val modFibD: Module = src(
    """def fibD(n: Double): Double =
      |  if n <= 1.0 then n else fibD(n - 1.0) + fibD(n - 2.0)
      |fibD(30.0)""".stripMargin
  )

  // Double-typed recursive fib whose base case multiplies by a top-level
  // `val` constant of `Double` type — exercises the BytecodeJit Double-globals
  // read path (`readGlobalDouble`). Parallel to `modFibMul` (which exercises
  // the Int-globals path). Before the `phase-c-bytecode-double-globals` slice,
  // `walkDouble`'s `Term.Name` case bailed on any free name and the fn fell
  // through to `SscVm.exec`.
  private val modFibMulD: Module = src(
    """val mul = 7.0
      |def fibMulD(n: Double): Double =
      |  if n <= 1.0 then n * mul else fibMulD(n - 1.0) + fibMulD(n - 2.0)
      |fibMulD(30.0)""".stripMargin
  )

  // while-jit-inline-match lock-in: `p match { case Pair(a,b) => a+b }` inline
  // in a while loop body.  `walkLocalSlotCtx` now handles `Term.Match` at the
  // outer loop level: scrutinee resolves to a `_rN` ref slot; a static helper
  // `fn_imatch_HASH(Object p)` is co-emitted using `walkMatchBody` so the full
  // typeTag-switch + Int field extraction runs in native bytecode.
  // Pre: ~8.4 ms/op (LMatch path). Post: ~0.043 ms/op (~195×, bytecode JIT path).
  private val modInstanceFieldAccess: Module = src(
    """case class Pair(a: Int, b: Int)
      |val p = Pair(3, 4)
      |var total = 0
      |var i = 0
      |while i < 1000000 do
      |  total = total + (p match { case Pair(a, b) => a + b })
      |  i = i + 1
      |total""".stripMargin
  )

  // Map.foreach with a 2-arg `(k, v) =>` closure — exercises the callEntry
  // path in `DispatchRuntime.dispatchMap` "foreach" case. Not currently
  // covered by FastTier (which only handles 1-arg foreach closures via
  // `tryDoubleAccumForeach*`/`tryLongAccumForeach*`); the natural follow-up
  // is a 2-arg LApply2-style fast path for entry-style closures.
  private val modMapForeach: Module = src(
    """val m = Map("a" -> 1, "b" -> 2, "c" -> 3, "d" -> 4, "e" -> 5)
      |var total = 0
      |var i = 0
      |while i < 100000 do
      |  m.foreach((k, v) => { total = total + v })
      |  i = i + 1
      |total""".stripMargin
  )

  // Mixed-type self-recursion — `g(scale: Int, e: Expr): Int` carries an
  // Int accumulator alongside an Expr ref scrutinee. Each recursive call
  // re-passes the Int param and a child Expr field. Exercises the
  // BytecodeJit per-param Object/long marshaling in a single MH signature.
  private val modGEval: Module = src(
    """sealed trait Expr
      |case class Num(n: Int) extends Expr
      |case class Add(l: Expr, r: Expr) extends Expr
      |case class Mul(l: Expr, r: Expr) extends Expr
      |def gEval(scale: Int, e: Expr): Int = e match
      |  case Num(n)    => n * scale
      |  case Add(l, r) => gEval(scale, l) + gEval(scale, r)
      |  case Mul(l, r) => gEval(scale, l) * gEval(scale, r)
      |def build(d: Int): Expr =
      |  if d <= 0 then Num(1)
      |  else Add(build(d - 1), Mul(build(d - 1), Num(2)))
      |val tree = build(8)
      |var total = 0
      |var i = 0
      |while i < 1000 do
      |  total = total + gEval(3, tree)
      |  i = i + 1
      |total""".stripMargin
  )

  // EA / call-site verification probe. `def f(e: E): Int = 1 + (e match …)`
  // has the Match as a *sub-expression*, exercising both
  //   - `JavacJitBackend.walkMatchExpr` (Java switch expression in IIFE), and
  //   - the `LApplyObjRef` LExpr fast-path that lets the outer
  //     `tryLongWhileAssign` loop call the JIT'd ObjToLong directly for a
  //     val-bound ref argument (the original tree-walk fallback ran at
  //     ~2.7 µs/iter; LApplyObjRef brings it into LMatch parity).
  private val modNestedMatchExpr: Module = src(
    """sealed trait E
      |case class A(n: Int) extends E
      |case class B(n: Int) extends E
      |def f(e: E): Int = 1 + (e match
      |  case A(n) => n
      |  case B(n) => n + 100)
      |val item = A(5)
      |var total = 0
      |var i = 0
      |while i < 1000000 do
      |  total = total + f(item)
      |  i = i + 1
      |total""".stripMargin
  )

  // Baseline twin: same `f(item)` call shape in a 1M-iter while loop, but
  // `f`'s body IS `e match { … }` directly. This shape hits the LMatch
  // inline path: `total = total + f(item)` doesn't actually call `f` at
  // run-time — `tryLongWhileAssign` recognises that `f.body` is a Match
  // and inlines it via `PatternRuntime.compileMatch` → `runValueLong`,
  // so the inner loop is fully unboxed Long. Useful as a target for the
  // LApplyObjRef fast-path to match.
  private val modMatchBodyBaseline: Module = src(
    """sealed trait E
      |case class A(n: Int) extends E
      |case class B(n: Int) extends E
      |def f(e: E): Int = e match
      |  case A(n) => 1 + n
      |  case B(n) => 1 + n + 100
      |val item = A(5)
      |var total = 0
      |var i = 0
      |while i < 1000000 do
      |  total = total + f(item)
      |  i = i + 1
      |total""".stripMargin
  )

  // Phase 1 Commit 3 lock-in: `f(item.right)` exercises the LRefFieldGet
  // path. `item` is a val-bound `Pair(A(5), B(10))`; `item.right` resolves
  // to LRefFieldGet(LRefConst(item), 1) at compile time (no per-iter type
  // while-jit-ref-select-chain lock-in: `f(item.right)` — field access on a
  // val-bound InstanceV is now resolved by `walkRefArgCtx` (Term.Select case)
  // to a `_r0` slot whose value is pre-loaded into the TLS `refs` array before
  // the bytecode-JIT loop. ObjToLong `f` runs as `_fn0.apply(_r0)`.
  // Pre: ~9 ms/op (LExpr path). Post: ~0.046 ms/op (200×, bytecode JIT path).
  private val modRefFieldArg: Module = src(
    """sealed trait E
      |case class A(n: Int) extends E
      |case class B(n: Int) extends E
      |case class Pair(left: E, right: E)
      |def f(e: E): Int = 1 + (e match
      |  case A(n) => n
      |  case B(n) => n + 100)
      |val item = Pair(A(5), B(10))
      |var total = 0
      |var i = 0
      |while i < 1000000 do
      |  total = total + f(item.right)
      |  i = i + 1
      |total""".stripMargin
  )

  private val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())

  // helloWorld: measures zero-param function dispatch overhead.
  // Uses a warm interpreter with builtins pre-loaded (println requires plugins).
  // Pre-parses the `workload()` call term so each @Benchmark op pays
  // only eval + dispatch — no parsing, no plugin loading.
  private val modHelloWorld: Module = src(
    """def workload(): Unit = println("hello")"""
  )
  private val helloWorldInterp: Interpreter =
    val i = Interpreter(devNull)
    i.run(modHelloWorld)  // loads builtins + registers workload in globals
    i
  private val helloWorldCallTerm: Term =
    dialects.Scala3("workload()").parse[Term].get

  // ── benchmarks ───────────────────────────────────────────────────

  @Benchmark
  def arithLoop(): Unit =
    Interpreter(devNull).runSections(modArithLoop)

  @Benchmark
  def recursionFib(): Unit =
    Interpreter(devNull).runSections(modFib)

  @Benchmark
  def recursionTco(): Unit =
    Interpreter(devNull).runSections(modTco)

  // `patternMatchHeavy` with a `Set[Shape]` receiver instead of `List[Shape]`.
  // Verifies that `Set.foreach` routes through `DispatchRuntime.dispatchSet`
  // → `dispatchList(s.toList, ...)` → `CallRuntime.foreachReusing` → FastTier,
  // so the foreach-accumulator wins land automatically without any FastTier
  // changes. Uses `.toSet` (rather than the `Set(...)` ctor) because the
  // bench harness skips `BuiltinsRuntime.initBuiltins` (which is where the
  // `Set` companion gets registered) and lazy plugin loading only covers the
  // intrinsics in `intrinsics/Core.scala` — an unrelated harness gap.
  private val modPatternMatchSet: Module = src(
    """sealed trait Shape
      |case class Circle(r: Double) extends Shape
      |case class Rect(w: Double, h: Double) extends Shape
      |case class Triangle(b: Double, h: Double) extends Shape
      |def area(s: Shape): Double = s match
      |  case Circle(r)      => 3.14159 * r * r
      |  case Rect(w, h)     => w * h
      |  case Triangle(b, h) => 0.5 * b * h
      |val shapes = List(Circle(1.0), Rect(2.0, 3.0), Triangle(4.0, 5.0)).toSet
      |var total = 0.0
      |var i = 0
      |while i < 100000 do
      |  shapes.foreach(s => { total = total + area(s) })
      |  i = i + 1
      |total""".stripMargin
  )

  @Benchmark
  def patternMatchHeavy(): Unit =
    Interpreter(devNull).runSections(modPatternMatch)

  @Benchmark
  def patternMatchSet(): Unit =
    Interpreter(devNull).runSections(modPatternMatchSet)

  @Benchmark
  def patternMatchWide(): Unit =
    Interpreter(devNull).runSections(modPatternMatchWide)

  @Benchmark
  def recursiveEval(): Unit =
    Interpreter(devNull).runSections(modRecursiveEval)

  @Benchmark
  def effectPure(): Unit =
    Interpreter(devNull).runSections(modEffectPure)

  @Benchmark
  def effectStream(): Unit =
    Interpreter(devNull).runSections(modEffectStream)

  // Use `run` (not `runSections`): the full entry calls `runInit`, which populates
  // `multiShotEffects` via EffectAnalysis — `runSections` skips it, so a `multi
  // effect` would wrongly trip the one-shot-violation guard. `run` is also the
  // realistic CLI execution path.
  @Benchmark
  def effectOneShot(): Unit =
    Interpreter(devNull).run(modEffectOneShot)

  @Benchmark
  def effectReader(): Unit =
    Interpreter(devNull).run(modEffectReader)

  @Benchmark
  def effectMultiShot(): Unit =
    Interpreter(devNull).run(modEffectMultiShot)

  @Benchmark
  def effectMultiShotDeep(): Unit =
    Interpreter(devNull).run(modEffectMultiShotDeep)

  @Benchmark
  def tupleMonoid(): Unit =
    Interpreter(devNull).runSections(modTupleMonoid)

  @Benchmark
  def tupleMonoidVal(): Unit =
    Interpreter(devNull).runSections(modTupleMonoidVal)

  @Benchmark
  def valIntermediate(): Unit =
    Interpreter(devNull).runSections(modValIntermediate)

  @Benchmark
  def multiVal(): Unit =
    Interpreter(devNull).runSections(modMultiVal)

  @Benchmark
  def valIf(): Unit =
    Interpreter(devNull).runSections(modValIf)

  @Benchmark
  def valMatch(): Unit =
    Interpreter(devNull).runSections(modValMatch)

  @Benchmark
  def counterWithTupleVar(): Unit =
    Interpreter(devNull).runSections(modCounterWithTupleVar)

  @Benchmark
  def pureCallSum(): Unit =
    Interpreter(devNull).runSections(modPureCallSum)

  @Benchmark
  def pureCallSum2(): Unit =
    Interpreter(devNull).runSections(modPureCallSum2)

  @Benchmark
  def pureCallSumIf(): Unit =
    Interpreter(devNull).runSections(modPureCallSumIf)

  @Benchmark
  def pureCallSumBlock(): Unit =
    Interpreter(devNull).runSections(modPureCallSumBlock)

  @Benchmark
  def recursionFibMul(): Unit =
    Interpreter(devNull).runSections(modFibMul)

  @Benchmark
  def recursionFibD(): Unit =
    Interpreter(devNull).runSections(modFibD)

  @Benchmark
  def recursionFibMulD(): Unit =
    Interpreter(devNull).runSections(modFibMulD)

  @Benchmark
  def recursiveEvalMixed(): Unit =
    Interpreter(devNull).runSections(modGEval)

  @Benchmark
  def instanceFieldAccess(): Unit =
    Interpreter(devNull).runSections(modInstanceFieldAccess)

  @Benchmark
  def nestedMatchExpr(): Unit =
    Interpreter(devNull).runSections(modNestedMatchExpr)

  @Benchmark
  def refFieldArg(): Unit =
    Interpreter(devNull).runSections(modRefFieldArg)

  @Benchmark
  def matchBodyBaseline(): Unit =
    Interpreter(devNull).runSections(modMatchBodyBaseline)

  @Benchmark
  def mapForeach(): Unit =
    Interpreter(devNull).runSections(modMapForeach)

  // Pattern guard bench: `case A(n) if n >= 0 => n; case A(n) => -n`.
  // Pre-guard-JIT: guard bails → tree-walk every call.
  // Post-guard-JIT: if-chain form compiled → ObjToLong JIT path hot.
  // Uses pre-built val-bound instances so the outer while-loop routes through
  // LApplyR1(LRefConst, jitResult) and the bench isolates guard dispatch cost.
  private val modPatternGuard: Module = src(
    """sealed trait E
      |case class A(n: Int) extends E
      |case class B(n: Int) extends E
      |def absVal(e: E): Int = e match
      |  case A(n) if n >= 0 => n
      |  case A(n)           => -n
      |  case B(n) if n >= 0 => n
      |  case B(n)           => -n
      |val aPos = A(5)
      |val aNeg = A(-3)
      |val bPos = B(7)
      |val bNeg = B(-2)
      |var total = 0
      |var i = 0
      |while i < 1000000 do
      |  total = total + absVal(aPos) + absVal(aNeg) + absVal(bPos) + absVal(bNeg)
      |  i = i + 1
      |total""".stripMargin
  )

  @Benchmark
  def patternGuard(): Unit =
    Interpreter(devNull).runSections(modPatternGuard)

  // while-jit-ref-select-chain lock-in: `leafVal(getLeft(tree))` — chained
  // ObjToObject+ObjToLong call in a while loop. `walkRefArgCtx` now handles
  // Term.Apply where the fn is ObjToObject: `getLeft(tree)` → `_objFn0.apply(_r0)`,
  // then `leafVal(_objFn0.apply(_r0))` → `_fn0.apply(_objFn0.apply(_r0))`.
  // Both fn instances and the `tree` ref are pre-loaded from TLS before the loop.
  // Pre: ~9.7 ms/op (LExpr path). Post: ~0.31 ms/op (31×, bytecode JIT path).
  private val modRefChainArg: Module = src(
    """sealed trait T
      |case class Leaf(v: Int) extends T
      |case class Branch(left: T, right: T) extends T
      |def getLeft(b: T): T = b match
      |  case Branch(l, _) => l
      |  case x            => x
      |def leafVal(n: T): Int = n match
      |  case Leaf(v)      => v
      |  case Branch(_, _) => 0
      |val leaf = Leaf(7)
      |val tree = Branch(leaf, Leaf(99))
      |var total = 0
      |var i = 0
      |while i < 1000000 do
      |  total = total + leafVal(getLeft(tree))
      |  i = i + 1
      |total""".stripMargin
  )

  private def warmInterp(code: String): Interpreter =
    val i = Interpreter(devNull)
    i.runSections(src(code))
    i

  private val optionChainInterp: Interpreter = warmInterp(
    """def lookup(k: Int): Option[Int] =
      |  if k % 2 == 0 then Some(k * 2) else None
      |def step(n: Int): Int =
      |  Some(n).flatMap(x => lookup(x)).map(x => x + 1).getOrElse(0)""".stripMargin
  )
  private val optionChainTerm: Term =
    dialects.Scala3("step(2) + step(3)").parse[Term].get

  private val eitherChainInterp: Interpreter = warmInterp(
    """def parse(n: Int): Either[String, Int] =
      |  if n > 0 then Right(n) else Left("neg")
      |def step(n: Int): Int =
      |  parse(n).map(x => x + 1).flatMap(x => parse(x)).fold(e => 0, x => x)""".stripMargin
  )
  private val eitherChainTerm: Term =
    dialects.Scala3("step(2) + step(-1)").parse[Term].get

  private val hofPipelineInterp: Interpreter = warmInterp(
    """val xs: List[Int] = List(1, 2, 3, 4, 5, 6)
      |def step(): Int =
      |  xs.map(x => x * 2).filter(x => x % 3 == 0).foldLeft(0)((a, b) => a + b)""".stripMargin
  )
  private val hofPipelineTerm: Term =
    dialects.Scala3("step()").parse[Term].get

  private val rangeSumInterp: Interpreter = warmInterp(
    """def step(n: Int): Int =
      |  (0 until n).map(x => x + 1).foldLeft(0)((a, b) => a + b)""".stripMargin
  )
  private val rangeSumTerm: Term =
    dialects.Scala3("step(20)").parse[Term].get

  private val typeclassFoldInterp: Interpreter = warmInterp(
    """trait Monoid[A]:
      |  def empty: A
      |  def combine(a: A, b: A): A
      |given intMonoid: Monoid[Int] with
      |  def empty: Int = 0
      |  def combine(a: Int, b: Int): Int = a + b
      |def combineAll[A: Monoid](xs: List[A]): A =
      |  xs.foldLeft(summon[Monoid[A]].empty)(summon[Monoid[A]].combine)""".stripMargin
  )
  private val typeclassFoldTerm: Term =
    dialects.Scala3("combineAll(List(1, 2, 3, 4))").parse[Term].get

  // Macro variant matching `bench/corpus/typeclass-fold.ssc`: 300 × combineAll
  // over a 10-element list. Visible A/B for `interp-typeclass-fold-devirt`
  // (the JMH micro above runs combineAll once, so the per-call summon-resolution
  // cost it targets is invisible there). Reuses the same warmed interpreter.
  private val typeclassFoldMacroInterp: Interpreter = warmInterp(
    """trait Monoid[A]:
      |  def empty: A
      |  def combine(a: A, b: A): A
      |given intMonoid: Monoid[Int] with
      |  def empty: Int = 0
      |  def combine(a: Int, b: Int): Int = a + b
      |def combineAll[A: Monoid](xs: List[A]): A =
      |  xs.foldLeft(summon[Monoid[A]].empty)(summon[Monoid[A]].combine)
      |val xs: List[Int] = List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
      |def macroFold(): Long =
      |  var sum = 0L
      |  var i = 0
      |  while i < 300 do
      |    sum = sum + combineAll(xs).toLong
      |    i = i + 1
      |  sum""".stripMargin
  )
  private val typeclassFoldMacroTerm: Term =
    dialects.Scala3("macroFold()").parse[Term].get

  // String parse-and-sum HOF chain: `split(",").map(s => s.trim.toInt).foldLeft`.
  // Mirrors the `bench/corpus/string-split.ssc` macro workload (300 iters over a
  // 20-field CSV) so the cross-backend outlier (interp ~18 ms vs jvm 0.08 ms)
  // is A/B-able under JMH + `scripts/bench profile`. Exercises the generic
  // List.map / foldLeft closure-application path (no JIT — String closures).
  private val stringSplitInterp: Interpreter = warmInterp(
    """val csv: String = "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20"
      |def step(): Long =
      |  var sum = 0L
      |  var i = 0
      |  while i < 300 do
      |    val r = csv.split(",").map(s => s.trim.toInt).foldLeft(0)((a, b) => a + b)
      |    sum = sum + r.toLong
      |    i = i + 1
      |  sum""".stripMargin
  )
  private val stringSplitTerm: Term =
    dialects.Scala3("step()").parse[Term].get

  @Benchmark
  def refChainArg(): Unit =
    Interpreter(devNull).runSections(modRefChainArg)

  @Benchmark
  def stringSplit(): Unit =
    stringSplitInterp.evalTerm(stringSplitTerm)

  @Benchmark
  def optionChain(): Unit =
    optionChainInterp.evalTerm(optionChainTerm)

  @Benchmark
  def eitherChain(): Unit =
    eitherChainInterp.evalTerm(eitherChainTerm)

  @Benchmark
  def hofPipeline(): Unit =
    hofPipelineInterp.evalTerm(hofPipelineTerm)

  @Benchmark
  def rangeSum(): Unit =
    rangeSumInterp.evalTerm(rangeSumTerm)

  @Benchmark
  def typeclassFold(): Unit =
    typeclassFoldInterp.evalTerm(typeclassFoldTerm)

  @Benchmark
  def typeclassFoldMacro(): Unit =
    typeclassFoldMacroInterp.evalTerm(typeclassFoldMacroTerm)

  @Benchmark
  def helloWorld(): Unit =
    helloWorldInterp.evalTerm(helloWorldCallTerm)
