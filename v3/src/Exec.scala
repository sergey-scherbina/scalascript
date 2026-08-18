package ssc3

// SSC3-3b — the executor. v3/specs/10-ssc-ir.md §1, v3/SPRINT.md SSC3-3b.
//
// The bridge (SSC3-3) makes v3 USABLE by inheriting v2's backends. This makes v3 BETTER than v2,
// and only in the three ways the bridge structurally cannot:
//
//   1. `TailCall` runs in CONSTANT STACK. v2 has no TCO — which is why its launchers pass
//      `-Xss512m` and why `mutual-recursion` (isEven(100000)) overflows through the bridge.
//   2. A frame is DATA. `Frame(func, regs)` is an object, not a position in the host call stack,
//      which is what makes a paused computation inspectable and, later, serializable.
//   3. The `kind` field is available to specialize on. v2's primitives are dynamically dispatched
//      and discard it.
//
// Written in the Scala 3 ∩ ScalaScript 2 subset. `Array` is the register file — the reason SSC3-1
// had to fix `new Array[T](n)` before any of this could exist.

enum Value:
  case VUnit
  case VBool(b: Boolean)
  case VInt(n: Long)
  case VFloat(d: Double)
  case VStr(s: String)
  /** A char is an INTEGER that prints as a character — v2's model (`CharV extends IntV`), kept so
    * the two lanes agree on `'x' + 1` (121) as well as on `println('x')` (x). */
  case VChar(c: Char)
  /** A BYTE STRING — v2's `BytesV`, and it exists so the two lanes can share one IR.
    *
    * `std/fs.ssc` declares `readFile(path): String`; v2's `io.readFile` answers with bytes. The
    * extern's body is an expression, so `Lower.hostPrims` composes the two — `utf8->str(io.readFile
    * p)` — and the IR is SHARED, which means `Exec` has to hold what `io.readFile` returns even
    * though no ScalaScript program can name the type.
    *
    * NOT USER-VISIBLE, deliberately. There is no literal, no method and no `typeName` a program can
    * observe beyond a diagnostic: bytes reach the language only through prims that consume them in
    * the same expression that produced them. That is what keeps this from being a Tier 0 type
    * addition (invariant I-2) — it is a value the RUNTIME needs, not one the language gained.
    *
    * `Array[Byte]` rather than a `List[Int]`: it is what both `java.nio.file.Files` hands over and
    * what `getBytes` produces, so neither direction copies, and nothing compares two of them —
    * reference equality would be wrong and structural equality is never asked for. */
  case VBytes(b: Array[Byte])
  case VData(tag: Int, fields: Array[Value])
  case VClos(f: Int, captured: List[Value])
  /** A CONTINUATION, and the reason it is a value of its own rather than the `VClos` it wraps.
    *
    * A deep handler's continuation is `k(w) = h(rest(w))`: resuming has to pass what the rest
    * produces through the handler's RETURN CLAUSE. `Resume` used to find that clause through
    * `handlers.headOption` — the DYNAMIC stack — which is right while the continuation is called
    * inside its `handle` and wrong the moment it escapes. An arm that returns a closure calling
    * `resume` is exactly that: the `handle` has finished, the stack is empty, and
    * `handlers.headOption` answered `None`, so `resume(())` gave back the computation's bare value
    * instead of the return clause's function — and the program then called an `Int`.
    * (BUGS.md v3-an-escaped-continuation-resumes-without-the-return-clause.)
    *
    * Carrying the frame is what makes the lookup STATIC: the clause is found from the continuation,
    * which is where it belongs, so an escaped `k` behaves exactly like one called in place. */
  case VCont(clos: Value, h: Exec.HandlerFrame, seg: List[Exec.PendingFrame])
  case VArr(items: Array[Value])
  /** A BUILT-IN method applied to only some of its arguments — `xs.foldLeft(0)` waiting for its
    * function. v3 has no partial application in general; a `VClos` needs a lifted function index
    * and a built-in has none, so this is the shape that lets a curried BUILT-IN call work at all.
    * `CallV` on one of these finishes the invoke. */
  case VPartial(recv: Value, name: String, got: List[Value])
  /** A `Map`. INSERTION-ORDERED, because that is what the reference lane prints:
    * `Map(a -> 1, b -> 2)` in the order written, not sorted and not hashed. A `Vector` of pairs
    * rather than a hash map — v3's maps are small and written by hand, and preserving the order is
    * worth more here than a lookup that never gets long enough to matter. */
  case VMap(entries: scala.collection.mutable.ArrayBuffer[(Value, Value)])
  /** A `Set`, INSERTION-ORDERED and de-duplicated on construction — `Set(1, 2, 2, 3)` prints
    * `Set(1, 2, 3)` on the reference lane, in the order written. */
  case VSet(elems: List[Value])
  /** A LAZY sequence: a thunk yielding either nothing or a head and the rest.
    *
    * `LazyList.from(n)` is INFINITE, so the representation has to be one that can be infinite —
    * `bench/corpus/lazylist-take.ssc` maps over the whole thing and only then takes 8. The obvious
    * cheap alternative, materialising some generous prefix and calling it a LazyList, passes that
    * exact row and is a lie the moment a `filter` appears: `from(0).filter(_ > 1_000_000).take(1)`
    * would come back empty instead of running on. A cons-thunk cannot be wrong that way; it can
    * only be slow or, on a genuinely unbounded fold, refuse.
    *
    * NOT memoised, unlike Scala's LazyList. Traversing the same value twice recomputes it, which is
    * invisible for the pure functions Tier 0 has and would not be once effects arrive. Stated here
    * because the name promises memoisation to anyone who knows the Scala type. */
  case VLazy(step: () => Option[(Value, Value)])
  /** A HANDLE OWNED BY A HOST PLUGIN, carried through v3 and handed back untouched.
    *
    * `coroutineCreate` answers a `CoroutineState`, the program keeps it and passes it to
    * `coroutineResume`. Nothing in v3 can look inside, and nothing needs to: every operation on it is
    * an ordinary call with the handle in argument position, which is why this is a value and not a
    * new dispatch mechanism. `tag` is the owner's name for it and exists ONLY for diagnostics — a
    * refusal that says `<handle CoroutineState>` is worth the field.
    *
    * `AnyRef` rather than the plugin's type keeps invariant I-1 intact: the kernel gains a JVM
    * reference, not a dependency on v2 or on any plugin, and it cannot act on the value at all. That
    * inability is the point — an opaque handle that the executor could inspect would be a Tier 0
    * type addition (I-2), and this is a value the RUNTIME passes through, like `VBytes`. */
  case VForeign(handle: AnyRef, tag: String)
  /** HOST DATA NAMED BY A STRING — a constructor the host returned and this program never declared.
    *
    * v2 names a constructor with a string and v3 numbers it, so a `VData` is an index into THIS
    * program's type table and a tag with no entry cannot be built at all. `coroutineResume` answers
    * `Yielded(1)`, and `coroutine-basic.ssc` only ever PRINTS it — the program has no reason to
    * declare a type it never writes down, and the driver does not merge the prelude for a program
    * that lowers cleanly without it.
    *
    * SO THE TWO CASES SPLIT CLEANLY AND NEITHER LOSES ANYTHING. A program that WRITES the
    * constructor — `case Errored(_) =>` — declares it, and the ordinary indexed `VData` carries it
    * with pattern matching intact. A program that only prints one gets this, which renders and
    * nothing else. No pattern can mention a constructor the program does not declare, so being
    * un-matchable costs exactly nothing.
    *
    * This is a value the RUNTIME passes through, like `VBytes` — not a Tier 0 type the language
    * gained (invariant I-2). Nothing constructs one except the plugin bridge. */
  case VHostData(tag: String, fields: Array[Value])

final case class ExecError(message: String) extends RuntimeException(message)

/** A `throw` FROM THE PROGRAM, carrying the thrown VALUE rather than a rendering of it.
  *
  * `__throw__` used to build an `ExecError(showV(value))` and `catch` bound `VStr(e.message)`, so
  * the value was lost at the THROW — one lane turning every exception into its own text. The bridge
  * and the reference front both bind the value as thrown, so a typed arm matched on one lane and not
  * the other: `catch case e: Boom => …` answered `caught-boom` on the bridge and fell through to the
  * rethrow arm here (BUGS.md `v3-executor-catches-a-string-where-the-bridge-catches-the-value`).
  *
  * SEPARATE FROM `ExecError`, which keeps its meaning: a failure the EXECUTOR raised, which has a
  * message and no value. `catch` handles both — see the arm — because v3 draws no line between a
  * language-level runtime error (`/ by zero`, catchable in Scala) and an internal one, and refusing
  * the second would refuse the first with it. */
final case class ExecThrow(value: Value, rendered: String) extends RuntimeException(rendered)

/** What running a region produced. Structured control flow means these are the only four
  * possibilities, and each is a value rather than an exception — an exception would unwind the host
  * stack, which is exactly what `TailCall` must not do. */
enum Signal:
  case Done
  case Branch(depth: Int)
  case Ret(v: Value)
  case Tail(f: Int, args: List[Value])

object Exec:

  // Module globals. A module-scope `var` is genuinely mutable state, so it lives in one array for
  // the run rather than being threaded through every call — the same decision the register frame
  // makes, one level up.
  private var globals: Array[Value] = new Array[Value](0)

  // ── SSC3-J0a — the derived tables ──────────────────────────────────────────────────────────────
  //
  // `Module` holds its pools as `List`, which is right for a serialized program and wrong for a
  // machine: **`List.apply` is O(index)**. `m.consts(k)` walks k cons cells EVERY TIME the
  // instruction executes, and an `Instr.Const` inside a loop executes as often as the loop runs —
  // `bench/corpus/arith-loop.ssc` has two of them in its body, so a million iterations pay a million
  // list walks for a value that never changes. `m.funcs(fi)` is the same cost per CALL, and
  // `m.prims(p)` per host call.
  //
  // The constant pool is materialised to VALUES rather than to `Lit`s, which removes the second
  // cost: `constOf` allocated a fresh `Value` on every execution of every `Const`. Sharing one
  // instance is safe because `constOf` produces only `VUnit`/`VBool`/`VInt`/`VFloat`/`VStr` — every
  // case is immutable. It would NOT be safe for `VData`, `VArr` or `VMap`, which is why this is
  // stated rather than assumed.
  // ── SSC3-J2 — the closure lane ────────────────────────────────────────────────────────────────
  //
  // OFF by default and selected with `ssc3 exec --closures`, which is the measurement design and not
  // caution: two execution strategies over one IR, in one binary, is the A/B rig this host actually
  // permits (`specs/ssc3-jit.md` §8.1) and a differential at the same time — `jit-gate.sh
  // --identity` makes every corpus program run both ways and compares the bytes.
  private var closureLane: Boolean = false
  private var compiled: Array[Array[Op]] = new Array[Array[Op]](0)

  /** Set BEFORE the program runs; `prepare` reads it when it builds the tables. Flipping it
    * mid-run would leave half a module compiled, so `Cli` sets it once from the command line. */
  private[ssc3] def useClosures(on: Boolean): Unit =
    closureLane = on
    // The memo has to go with it: `prepare` builds `compiled` only when this is on, so a lane
    // switched after a prepare would otherwise run with tables built for the other one.
    preparedOwner = null
    compiled = new Array[Array[Op]](0)  // force `prepare` to rebuild rather than serve the old lane

  private var constPool: Array[Value] = new Array[Value](0)
  /** The same pool as STRINGS, for `Invoke`, whose name is a pool index that must be an `LStr`. */
  private var constStr: Array[String] = new Array[String](0)
  private var funcTable: Array[Func] = new Array[Func](0)
  private var primTable: Array[String] = new Array[String](0)

  /** Build the tables for `m`. Idempotent and cheap to re-check, because the alternative — building
    * them only in `run` — leaves any caller that reaches `callFunc` first reading an empty array.
    * `bench` is exactly such a caller; it calls `run` first today, and a check costs three integer
    * comparisons per CALL against a list walk per INSTRUCTION.
    *
    * The comparison is on LENGTHS, not identity: `eq` is host reference equality and this file has
    * to compile on ScalaScript 2 as well (invariant I-2). The residual case is a second module with
    * exactly the same pool sizes in one process, which `Cli` does not create — one program is
    * loaded, prepared and run. Named here rather than left for someone to find.
    */
  // SSC3-J4d. `prepare` runs at the top of EVERY `callFunc` — once per function call and once per
  // closure application — and its guard read `m.consts.length`, `m.funcs.length` and
  // `m.prims.length`. All three are `List`, so all three are O(n): a corpus program walks 6 to 32
  // cons cells on every call before deciding it has nothing to do. `hof-pipeline` walks 32,
  // `list-fold` 17.
  //
  // The fast path is the module's IDENTITY, the same key and the same argument as the type-tag cache
  // below: `Module` is an immutable case class, so one reference is one set of tables.
  //
  // IT ALSO CLOSES A LATENT HOLE. The length guard rebuilt only when a length DIFFERED, so two
  // different modules whose consts, funcs and prims happened to match in count would have shared the
  // first one's tables — a wrong-program answer with no diagnostic. Reference identity cannot do
  // that: a different module always rebuilds.
  private var preparedOwner: Module | Null = null
  private var prepareCacheOn: Boolean = true

  private[ssc3] def usePrepareCache(on: Boolean): Unit =
    prepareCacheOn = on
    preparedOwner = null

  // SSC3-J4. Default ON, and the default is a measurement rather than an intention.
  //
  // It shipped OFF first: the version that tested for the pair INSIDE the walk measured slower, and
  // the row that proved it was `recursion-fib` — zero fusable pairs, so the test there can only
  // cost, and it moved (5 of 20 against a control of 9). Hoisting the decision to the loop ENTRY,
  // where it belongs because `b` is immutable and `Loop` is entered once however long it runs,
  // turned that around: `arith-loop` 20 of 20 at mean 0.770 against a control of exactly 10 of 20,
  // and `recursion-fib` back to 11 of 20 — a dead null, which is what the prediction demanded.
  // Same code doing the fusion; only the placement of the decision changed.
  private var fuseCmpBrOn: Boolean = true

  private[ssc3] def useFuseCmpBr(on: Boolean): Unit =
    fuseCmpBrOn = on

  private def prepare(m: Module): Unit =
    if prepareCacheOn && (preparedOwner eq m) then return
    preparedOwner = if prepareCacheOn then m else null
    if constPool.length != m.consts.length
       || funcTable.length != m.funcs.length
       || primTable.length != m.prims.length then
      constPool = new Array[Value](m.consts.length)
      constStr = new Array[String](m.consts.length)
      var cs = m.consts
      var i = 0
      while cs.nonEmpty do
        constPool(i) = constOf(cs.head)
        constStr(i) = cs.head match
          case Lit.LStr(x) => x
          case _           => ""
        cs = cs.tail
        i = i + 1
      funcTable = new Array[Func](m.funcs.length)
      var fs = m.funcs
      i = 0
      while fs.nonEmpty do
        funcTable(i) = fs.head
        fs = fs.tail
        i = i + 1
      primTable = new Array[String](m.prims.length)
      var ps = m.prims
      i = 0
      while ps.nonEmpty do
        primTable(i) = ps.head
        ps = ps.tail
        i = i + 1
      // Compiled ONCE per program, here, where the other derived tables are built — the closure
      // lane's whole claim is that decode happens once per instruction in the program rather than
      // once per execution, and compiling lazily per call would put a check on the path this exists
      // to shorten.
      if closureLane then
        compiled = new Array[Array[Op]](m.funcs.length)
        var fi = 0
        while fi < funcTable.length do
          compiled(fi) = Compile.func(m, funcTable(fi))
          fi = fi + 1

  /** One live `Handle`: the arms, and the FRAME they read. `specs/10-ssc-ir.md` §3 puts an arm's
    * `params` and `k` in the handling function's frame, so the frame has to travel with the arms —
    * a perform can happen any number of host calls deeper, and the arm still writes here. */
  /** `performed` records whether an ARM of this handler ran during the computation just finished.
    *
    * It is what makes the return clause apply EXACTLY ONCE: `handle` lifts a body that completed
    * without performing, and a `resume` lifts a continuation that completed without performing —
    * but when either DID perform, the value is the arm's own result, already lifted by the resume
    * inside it. Lifting again nested the list-monad answer three deep:
    * `List(List(List(11, 21), List(12, 22)))` where the answer is `List(11, 21, 12, 22)`. */
  private[ssc3] final case class HandlerFrame(m: Module, arms: List[HandlerArm], regs: Array[Value],
                                             var performs: Long = 0L)
  private var handlers: List[HandlerFrame] = Nil

  /** ONE CALLER FRAME BETWEEN A `handle` AND A `perform`, so the continuation can cross it.
    *
    * `Cps.split` makes the rest of the PERFORMING function a closure. That is the whole continuation
    * only when the perform's value is the handled expression's value; with a caller in between —
    * `handle(body())` where `body` calls a function that performs and then goes on — the caller's
    * remaining instructions belong to the continuation too, and they were in nobody's closure. The
    * arm's value landed in a discarded register and the caller ran on.
    * (BUGS.md v3-handler-arm-value-dropped-when-the-perform-is-a-statement.)
    *
    * This is the reified stack the old comments said v3 does not have — and it is much less than one:
    * only the SEGMENT between the handler and the perform, and only what the walker already holds in
    * hand at a call, which is the instruction suffix and the register array. Nothing about the host's
    * stack is copied, which is why this fits an executor that keeps its call stack on the host's.
    *
    * `regs` is SNAPSHOT at capture, not shared. A multi-shot arm resumes the same segment twice and
    * the second resume has to start from the register state the first one saw, not from what the
    * first one left behind. */
  /** `fall` is what this LEVEL produces when its remainder runs off the end, and it exists because
    * falling through means two different things. A FUNCTION body returns unit — `callFunc`'s rule. The
    * `handle` BODY's value is read out of the `Handle`'s destination register instead, so it carries
    * that register number. A REGION — an `if` branch, a loop body, a `try` — carries `RegionLevel`:
    * its remainder is a suffix of the REGION and not of the enclosing list, so a continuation captured
    * there would silently drop everything after the region. That one is REFUSED rather than answered,
    * which is this executor's standing rule about effects it cannot run. */
  private[ssc3] final case class PendingFrame(m: Module, rest: List[Instr], regs: Array[Value],
                                              d: Int, fall: Int)
  private[ssc3] inline val FuncLevel   = -1
  private[ssc3] inline val RegionLevel = -2
  private var pending: List[PendingFrame] = Nil
  /** Non-zero exactly while a `handle` is live. An `Int` field rather than `handlers.isEmpty` because
    * the walker tests it once per INSTRUCTION: a program with no effects must pay one integer compare
    * and no allocation, which is what keeps the frame recording out of the hot loop's cost. */
  private var handleDepth: Int = 0

  /** THE UNWIND. A handler arm's value is the value of the `handle`, not of the function that
    * performed — so when a caller sits in between, returning normally would let that caller carry on
    * with a value it should never see AND run its remainder a second time, since the continuation now
    * owns it. Carries the frame it is aimed at: a nested `handle` must not catch the inner one's. */
  /** ONE ACTIVATION of a handler — the `handle` itself, or a `resume` that re-entered it.
    *
    * The distinction is the whole of multi-shot. `case op(k) => k(1) + k(2)` over a function that
    * performs TWICE re-enters the arm while the outer activation is still evaluating `k(1)`, and the
    * inner arm's value belongs to `k(1)`, not to the `handle`. Aiming every unwind at the `handle`
    * answered 5 where the answer is 12: the inner unwind flew past `+ k(2)`. A resume therefore
    * installs a delimiter of its own and catches what is aimed at it. */
  private[ssc3] final class Delim(val h: HandlerFrame, val pendingDepth: Int)
  private var actives: List[Delim] = Nil

  private final class PerformAbort(val d: Delim, val value: Value)
      extends RuntimeException(null, null, false, false)
  /** Set by `Resume`, read by the `Perform` that ran the arm. A field rather than a return value
    * because an arm's body is an ordinary instruction list and `Signal` has no case for "resumed"
    * — adding one would put effects into every control-flow path in the executor for a value that
    * only ever travels one frame. */
  private var resumedWith: Option[Value] = None

  /** Is this arm the one class the executor can run — a single `resume` as its LAST act?
    *
    * Checked STRUCTURALLY and before the arm runs, not by watching what it does. A dynamic check
    * ("did it resume exactly once?") cannot see the difference between an arm that resumed and
    * stopped and one that resumed and then did work whose effect is already gone. The refusal has
    * to arrive before anything is observable.
    */
  private def tailResumptive(arm: HandlerArm): Boolean =
    def countResumes(body: List[Instr]): Int =
      body.map {
        case Instr.Resume(_, _, _) => 1
        case other                 => countResumes(Instr.children(other))
      }.sum
    // A TRAILING `Ret` IS SKIPPED. The lowering appends one to every arm so the arm's value has a
    // single place to come from; that made the last instruction a `Ret` and this check — which
    // wants "the last ACT is a resume" — started refusing every handler it used to accept. The
    // shape it is really asking about is unchanged.
    val body = arm.body match
      case init :+ Instr.Ret(_) => init
      case other                => other
    body.nonEmpty && (body.last match
      case Instr.Resume(_, _, _) => countResumes(arm.body) == 1
      case _                     => false)


  /** The `case x => …` arm of a handler, if it wrote one. Marked by `op = -1`, which no real
    * operation index can be. */
  private def retArm(arms: List[HandlerArm]): Option[HandlerArm] = arms.find(_.op == -1)

  /** RESUME A CONTINUATION — the one place that knows how, so `resume(v)`, `k(v)` and a `k` that
    * escaped its `handle` cannot disagree.
    *
    * The return clause is found from the CONTINUATION'S OWN frame, never from `handlers`. That is
    * the fix for `v3-an-escaped-continuation-resumes-without-the-return-clause`: an arm may return a
    * closure that resumes later, at which point the `handle` has finished and the dynamic stack is
    * empty — and reading the head of an empty stack silently skipped the lifting.
    *
    * The performs COUNTER is read before and after, on that same frame rather than on the dynamic
    * head. A deep handler's `k(w)` is `h(rest(w))`, so the clause applies exactly when the rest did
    * NOT perform; when it did, the value is already an arm's result and lifting twice nested the
    * list-monad answer three deep. */
  private def resumeCont(m: Module, c: Value.VCont, v: Value): Value =
    val before = c.h.performs
    // THE HANDLER IS REINSTALLED FOR THE DURATION. A deep handler's continuation may perform again —
    // two ticks of the same effect — and by then the `handle` has returned, so `handlers` no longer
    // holds the frame that must answer. Without this the second perform said "no handler for effect
    // operation 0", which is `v3-no-handler-error-has-no-position`'s one true shape.
    // THE OUTSTANDING FRAMES ARE ON `pending` FOR THE WHOLE RESUME, not only while the walk below
    // reaches them. The closure runs FIRST and may perform again — two ticks of the same effect —
    // and that inner perform's continuation owes the frames this one has not yet run. Setting
    // `pending` only inside the walk left the second perform capturing nothing, so its `resume`
    // answered unit where the rest of the handled computation said `END`.
    //
    // `pendingDepth` is 0 for the same reason: everything on `pending` from here up belongs to a
    // perform that happens inside this resume, the outstanding frames included.
    val save = pending
    pending  = c.seg
    val act  = new Delim(c.h, 0)
    handlers = c.h :: handlers
    actives  = act :: actives
    handleDepth = handleDepth + 1
    val raw =
      try
        // The closure first: the rest of the PERFORMING function. Then each caller frame the
        // continuation owes, innermost out, each on a FRESH copy of its registers so a second resume
        // starts where the first one did. `pending` is set to the frames still outstanding, so a
        // perform inside a resumed frame captures its own rest and not this one's.
        var acc  = apply1(m, c.clos, v)
        var seg  = c.seg
        while seg.nonEmpty do
            val f = seg.head
            seg = seg.tail
            pending = seg
            val r = f.regs.clone()
            r(f.d) = acc
            // FALLING THROUGH MEANS TWO DIFFERENT THINGS, and getting them the same way was the one
            // bug this walk had. A FUNCTION body that runs off its end returns unit — `callFunc`'s
            // rule, and these frames are function-body remainders. The OUTERMOST frame is not: it is
            // the `handle` BODY's own level, whose value the `Handle` instruction reads out of its
            // destination register rather than from a `ret`. Treating it like a function gave unit,
            // so `resume(())` answered `()` where the rest of the handled computation said `END` —
            // the segment was walked correctly and then its result was thrown away at the last step.
            acc = exec(f.m, f.rest, r, f.fall) match
              case Signal.Ret(x) => x
              case Signal.Done   => if f.fall >= 0 then r(f.fall) else Value.VUnit
              case other         => throw ExecError(
                "a continuation frame ended with " + other + "; only a value or a fall-through can " +
                "leave one")
        acc
      catch
        // A PERFORM INSIDE THE RESUMED COMPUTATION belongs HERE, not to the `handle`: `k(1)` in
        // `k(1) + k(2)` must come back with the inner arm's value so the outer arm can go on adding.
        case a: PerformAbort if a.d eq act => a.value
      finally
        handlers = handlers.tail
        actives  = actives.tail
        handleDepth = handleDepth - 1
        pending = save
    if c.h.performs != before then raw
    else retArm(c.h.arms).map(r => applyRet(c.h.m, r, c.h.regs, raw)).getOrElse(raw)

  /** Run the return clause on one value, in a FRESH copy of the handling frame — the same rule the
    * operation arms follow, and for the same reason: one array cannot serve two activations, and a
    * multi-shot handler applies this once per branch. */
  private def applyRet(m: Module, arm: HandlerArm, regs: Array[Value], v: Value): Value =
    val frame = regs.clone()
    if arm.params.nonEmpty then frame(arm.params.head) = v
    exec(m, arm.body, frame, FuncLevel) match
      case Signal.Ret(r) => r
      case _             => v

  /** The runtime type, for diagnostics only. Never for output — `show` owns that, and the two must
    * not be conflated: `show` is tuned for lane parity and deliberately hides `0.0` as `0`. */
  private def typeName(v: Value): String = v match
    case Value.VUnit       => "Unit"
    case Value.VBool(_)    => "Boolean"
    case Value.VInt(_)     => "Int"
    case Value.VFloat(_)   => "Double"
    case Value.VChar(_)    => "Char"
    case Value.VStr(_)     => "String"
    case Value.VData(_, _) => "data"
    case Value.VClos(_, _) => "function"
    case Value.VCont(_, _, _) => "function"
    case Value.VArr(_)     => "Array"
    case Value.VSet(_)     => "Set"
    case Value.VMap(_)     => "Map"
    case Value.VLazy(_)    => "LazyList"
    case _                 => "value"

  def show(v: Value): String = v match
    case Value.VUnit      => "()"
    case Value.VBool(b)   => if b then "true" else "false"
    case Value.VInt(n)    => n.toString
    case Value.VFloat(d)  => showFloat(d)
    case Value.VChar(c)   => c.toString
    case Value.VStr(s)    => s
    case Value.VData(t, f) =>
      if f.isEmpty then "#" + t else "#" + t + "(" + f.toList.map(show).mkString(", ") + ")"
    case Value.VSet(xs)    => "Set(" + xs.length + " elements)"
    case Value.VLazy(_)    => "LazyList(<not forced>)"
    case Value.VMap(es)    => "Map(" + es.length + " entries)"
    case Value.VClos(f, _) => "<closure " + f + ">"
    case Value.VCont(_, _, _) => "<continuation>"
    case Value.VPartial(_, nm, _) => "<partial " + nm + ">"
    case Value.VArr(xs)    => "Array(" + xs.toList.map(show).mkString(", ") + ")"
    // A COUNT, not the bytes. This value is never user-visible — no program can name it — so the
    // only way here is a diagnostic, and a diagnostic that dumps a file's contents is worse than
    // one that says how much of it there was. `show` is the ONE match in this file with no
    // catch-all, which is why the compiler named it and the other eight stayed silent.
    case Value.VBytes(b)   => "<" + b.length.toString + " bytes>"
    case Value.VForeign(_, tag) => "<handle " + tag + ">"
    case Value.VHostData(tag, fs) =>
      if fs.isEmpty then tag else tag + "(" + fs.toList.map(show).mkString(", ") + ")"

  /** How the LANGUAGE prints a Double — deliberately NOT `Text.floatText`, which is the canonical
    * `.ssir` form. Sharing one helper between an IR serialisation and a program's output is the
    * duplicated/shared-helper trap this repository has paid for before: the two have different
    * contracts and only one of them may ever change for a formatting reason.
    *
    * The rule is the REFERENCE LANE's, measured rather than invented — `ssc3 run` goes through v2
    * and the corpus expectations are the ones every other lane is held to:
    *
    *     3.0 -> 3      -3.0 -> -3      -0.0 -> 0      123456789.0 -> 123456789
    *     2.5 -> 2.5    0.1+0.2 -> 0.30000000000000004      1/0.0 -> inf      0/0.0 -> nan
    *
    * Real Scala prints `3.0` here, so this is v1-parity behaviour rather than Scala behaviour. That
    * is a decision the reference lane already made; v3's job is that its TWO lanes agree, and if the
    * repository ever changes it, v3 inherits the change rather than forking it.
    *
    * The whole-number test is `d == d.toLong.toDouble` — pure arithmetic, no host library, so it
    * holds in the portable subset. It is also self-limiting: past 2^63 `toLong` saturates, the
    * round trip fails, and the value falls through to the general form instead of printing a lie. */
  private def showFloat(d: Double): String =
    if d.isNaN then "nan"
    else if d.isInfinite then (if d > 0.0 then "inf" else "-inf")
    else if d == d.toLong.toDouble then d.toLong.toString
    else d.toString

  /** How a value reaches the USER — deliberately separate from `show`, which names raw tags and is
    * for the executor's own diagnostics.
    *
    * `show` alone printed `#0(1, 2)` where the v2 lane prints `P(1, 2)`, and a list as its nested
    * Cons cells rather than `List(1, 2)`. That is EVERY program that prints a constructed value,
    * and the differential gate could not see it because no fixture printed one. The type names were
    * there all along, in the module `show` did not have.
    *
    * The shapes are the reference lane's, measured: `P(1, 2)`, `Some(3)`, `None` (no parens for a
    * nullary constructor), `List(1, 2)`. */
  def showV(m: Module, v: Value): String = v match
    case Value.VData(t, f) =>
      if isList(m, v) then "List(" + listOut(m, v).map(x => showV(m, x)).mkString(", ") + ")"
      else if t == tagOf(m, "Nil") then "List()"
      else
        val nm = if t >= 0 && t < m.types.length then m.types(t).name else "#" + t
        // A tuple prints as `(1, a)`, NOT `Tuple2(1, a)` — measured on the v1 interpreter, which is
        // the language's reference for this. The synthetic class is an implementation detail and
        // must not reach the output.
        if nm.startsWith("Tuple") && f.length >= 2 then
          "(" + f.toList.map(x => showV(m, x)).mkString(", ") + ")"
        else if f.isEmpty then nm
        else nm + "(" + f.toList.map(x => showV(m, x)).mkString(", ") + ")"
    // `<foreign>`, because that is what BOTH reference lanes print — an array is a host object to
    // v1 and v2, and they say so. Printing the contents would read better and would make the two
    // v3 lanes disagree on every program that prints an array, which invariant I-3 forbids. The
    // executor's own diagnostics still use `show`, which does print the contents.
    // `Map(a -> 1, b -> 2)`, in INSERTION order and with the arrow — measured off v1, which is what
    // the corpus expectations encode.
    case Value.VSet(xs) => "Set(" + xs.map(x => showV(m, x)).mkString(", ") + ")"
    case Value.VMap(es) =>
      "Map(" + es.toList.map((k, v) => showV(m, k) + " -> " + showV(m, v)).mkString(", ") + ")"
    case Value.VArr(_) => "<foreign>"
    case Value.VPartial(_, nm, _) => "<partial " + nm + ">"
    case other          => show(other)

  private def a0(as: List[Int]): Int = as.head

  private def regs0(m: Module, xs: List[Value], idx: Value): Value = idx match
    case Value.VInt(i) =>
      if i < 0 || i >= xs.length then
        throw ExecError("index " + i + " out of bounds for a list of " + xs.length)
      xs(i.toInt)
    case v => throw ExecError("list index " + show(v))

  private def intArg(v: Value, what: String): Int = v match
    case Value.VInt(n) => n.toInt
    case other         => throw ExecError(what + " expects an integer, got " + show(other))

  /** A Double argument, REFUSING an Int exactly as v2's `flt` does (`Runtime.scala:3712`,
    * `expected Float, got 16`).
    *
    * Strictness here is lane agreement, not pedantry, and the first version of this helper had it
    * backwards: it widened, on the unmeasured assumption that v2 does. It does not. A program
    * reaching this prim with an Int must fail on both lanes or neither, and the place to make
    * `math.sqrt(16)` work is the prelude's `.toDouble`, above the prim and shared by both. */
  private def dbl(v: Value, what: String): Double = v match
    case Value.VFloat(d) => d
    case other           => throw ExecError(what + " expects a Double, got " + show(other))

  /** `distinct` by VALUE equality, not by reference — `eq` is the same comparison `==` uses, so a
    * list of equal data values collapses the way a reader expects. */
  private def dedup(xs: List[Value]): List[Value] =
    var out: List[Value] = Nil
    xs.foreach { x => if !out.exists(y => eq(y, x)) then out = out :+ x }
    out

  private def apply2(m: Module, f: Value, a: Value, b: Value): Value = f match
    case Value.VClos(fi, cap) => callFunc(m, fi, cap ++ List(a, b))
    case other                => throw ExecError("not a two-argument function: " + show(other))

  private[ssc3] def truthy(v: Value): Boolean = v match
    case Value.VBool(b) => b
    case Value.VInt(n)  => n != 0L
    case Value.VUnit    => false
    case _              => true

  def run(m: Module): Value =
    val e = Verify.module(m)
    // Invariant I-4: nothing executes unverified, and the executor is not an exception to it just
    // because it happens to be in the same process as the verifier.
    if e.isDefined then throw ExecError("refusing to run invalid IR: " + e.get.render)
    prepare(m)
    globals = new Array[Value](m.globals.length)
    var i = 0
    while i < m.globals.length do
      // `unit`, not a zero: a cell read before its initialiser runs is a real possibility, and
      // `unit` is what the other lane starts it as. Two lanes, one starting value.
      globals(i) = Value.VUnit
      i = i + 1
    callFunc(m, m.entry, Nil)

  /** The trampoline. A `TailCall` returns here and re-enters the loop with a FRESH argument list
    * and no added host frame, which is the whole of the constant-stack guarantee. */
  def callFunc(m: Module, f0: Int, args0: List[Value]): Value =
    prepare(m)
    var fi = f0
    var args = args0
    var result: Value = Value.VUnit
    var running = true
    while running do
      val fn = funcTable(fi)
      val regs = new Array[Value](fn.nregs)
      // ONE PASS over the argument list, doing all three jobs it used to take three for: copy the
      // arguments into the low registers, count them, and initialise the rest to `unit`.
      //
      // What it replaces, per CALL: `args.length`, which is O(n) on a `List`; a `while` filling
      // every register with `unit` including the ones about to be overwritten; and `args.foreach`,
      // whose closure is an allocation. `recursion-fib` makes about 2.7 million calls, and it is the
      // one row of the J0 measurement that did NOT move — the frame is what dominates it.
      //
      // The arity check keeps its exact message, so a wrong-arity program still fails the same way.
      var i = 0
      var as = args
      while as.nonEmpty do
        if i < fn.nregs then regs(i) = as.head
        as = as.tail
        i = i + 1
      if i != fn.nparams then
        throw ExecError(fn.name + " takes " + fn.nparams + " argument(s), given " + i)
      // `unit`, not `null`: a register read before its initialiser runs is a real possibility and
      // `unit` is what the other lane starts it as. Only the registers no argument covered.
      while i < fn.nregs do
        regs(i) = Value.VUnit
        i = i + 1
      val sig = if closureLane then Compile.run(compiled(fi), regs) else exec(m, fn.body, regs, FuncLevel)
      sig match
        case Signal.Ret(v)       => result = v; running = false
        case Signal.Done         => result = Value.VUnit; running = false
        case Signal.Branch(d)    => throw ExecError("a branch left the function body (depth " + d + ")")
        case Signal.Tail(g, as)  => fi = g; args = as
    result

  /** SSC3-J4, the compare-and-branch fusion — DECIDED ONCE PER LOOP, executed per iteration.
    *
    * The pair is `(bin cmp)` immediately followed by `(brif)` on the register the comparison wrote,
    * which after J4b is the canonical shape every counted loop ends with. Running both in one
    * dispatch needs no new opcode and no private flat encoding: `exec` already holds a cursor, so
    * the second half is `rest.tail.head`. `Ir.scala`, the verifier, the text form and its round-trip
    * gate, `BridgeV2` and §1's charter are untouched.
    *
    * WHAT IT SAVES is more than the census counted. `Bin` is one of the three opcodes `step` keeps
    * inline; `BrIf` is in `stepRest` — 5684 bytecodes, never inlined — so every counted loop paid a
    * call there once per iteration.
    *
    * ⚠️ THE FIRST VERSION TESTED FOR THE PAIR ON EVERY INSTRUCTION AND MEASURED SLOWER, and the
    * reason is the defect this series has now found four times: an answer that is a property of an
    * IMMUTABLE list, re-derived on a path that runs per instruction. `Loop` calls `exec` once per
    * ITERATION over the same `List[Instr]`, so `arith-loop` asked "is this pair fusable?" five
    * million times to keep learning about one pair. The control said so before the experiment did:
    * `recursion-fib` has ZERO fusable pairs, so the test there can only cost, and it moved.
    * Same shape as `tagOf` scanning the type table per call (J4c), `prepare` walking three `List`s
    * per call (J4d) and a `Const` re-executed per iteration (J4a) — all three of them WINS once the
    * work moved to where it is done once.
    *
    * So the decision is hoisted to the LOOP, which is entered once however long it runs:
    * `fusablePairs` walks the body a single time and returns the positions, or `null` when there
    * are none. A body with no pairs then walks through the untouched `exec` and pays NOTHING — the
    * `recursion-fib` case is not merely cheap, it is absent.
    */
  /** One instruction, recording the caller's REMAINDER when a `handle` is live and the instruction
    * is a call — the two conditions under which a `perform` deeper down needs this frame. */
  private def stepFramed(m: Module, ins: Instr, rest: List[Instr], regs: Array[Value],
                         fall: Int): Signal =
    val d = ins match
      case Instr.Call(dd, _, _)  => dd
      case Instr.CallV(dd, _, _) => dd
      case _                     => -1
    if d < 0 then step(m, ins, regs)
    else
      pending = PendingFrame(m, rest, regs, d, fall) :: pending
      try step(m, ins, regs) finally pending = pending.tail

  /** TWO WALKERS, CHOSEN ONCE PER BODY. The frame recording below is needed only inside a `handle`,
    * and asking that question per INSTRUCTION cost about 9% on a tight loop — measured, alternating
    * two built kernels with a matched fix-vs-fix control, 2287 ms against 2102 on 30M iterations.
    * A program with no effects must not pay for effects, so the test moved to the ONE place it is
    * still correct at: a body runs entirely inside a handle or entirely outside one, because a
    * `Handle` met inside this body opens its own nested level and is back to depth 0 when it
    * returns. Same shape `execFused` already uses to pick its walker. */
  private def exec(m: Module, body: List[Instr], regs: Array[Value], fall: Int): Signal =
    if handleDepth == 0 then execPlain(m, body, regs) else execFramed(m, body, regs, fall)

  private def execPlain(m: Module, body: List[Instr], regs: Array[Value]): Signal =
    var rest = body
    var out: Signal = Signal.Done
    var running = true
    while running && rest.nonEmpty do
      step(m, rest.head, regs) match
        case Signal.Done => rest = rest.tail
        case other       => out = other; running = false
    out

  private def execFramed(m: Module, body: List[Instr], regs: Array[Value], fall: Int): Signal =
    var rest = body
    var out: Signal = Signal.Done
    var running = true
    while running && rest.nonEmpty do
      stepFramed(m, rest.head, rest.tail, regs, fall) match
        case Signal.Done => rest = rest.tail
        case other       => out = other; running = false
    out

  /** The positions at which a fusable `(bin cmp) (brif)` pair starts, or `null` if there are none.
    *
    * Computed once per loop ENTRY, never per iteration. `null` rather than an empty array so the
    * caller can pick the untouched walker by a reference test and leave bodies without pairs
    * exactly as fast as they were before this pass existed.
    */
  private def fusablePairs(body: List[Instr]): Array[Int] | Null =
    var rest = body
    var k = 0
    var found: List[Int] = Nil
    while rest.nonEmpty do
      val t = rest.tail
      if t.nonEmpty then
        rest.head match
          case Instr.Bin(_, _, d, _, _) =>
            t.head match
              case Instr.BrIf(c, _) if c == d => found = k :: found
              case _                          => ()
          case _ => ()
      rest = t
      k = k + 1
    if found.isEmpty then null else found.reverse.toArray

  /** The walk for a body that HAS at least one fusable pair. One integer compare per instruction —
    * `k == nextFuse` — instead of the two type tests and a call the first version paid.
    */
  private def execFused(m: Module, body: List[Instr], regs: Array[Value], fus: Array[Int],
                        fall: Int): Signal =
    // Decided once, for the reason `exec` gives above.
    val framed = handleDepth != 0
    var rest = body
    var out: Signal = Signal.Done
    var running = true
    var k = 0
    var fp = 0
    var nextFuse = fus(0)
    while running && rest.nonEmpty do
      if k == nextFuse then
        val f = fuseCmpBr(m, rest.head, rest.tail.head, regs)
        if f == Signal.Done then
          rest = rest.tail.tail
          k = k + 2
          fp = fp + 1
          nextFuse = if fp < fus.length then fus(fp) else -1
        else
          out = f.asInstanceOf[Signal]; running = false
      else
        (if framed then stepFramed(m, rest.head, rest.tail, regs, fall)
         else step(m, rest.head, regs)) match
          case Signal.Done => rest = rest.tail; k = k + 1
          case other       => out = other; running = false
    out

  /** The pair itself. Reached only at a position `fusablePairs` already approved, so the type tests
    * here run once per pair per iteration rather than once per instruction — which is the whole
    * difference between this version and the one that measured slower.
    */
  private def fuseCmpBr(m: Module, i: Instr, next: Instr, regs: Array[Value]): Signal | Null =
    next match
      case Instr.BrIf(c, depth) =>
        i match
          case Instr.Bin(op, kind, d, a, b) if d == c =>
            val v = binK(m, op, kind, regs(a), regs(b))
            regs(d) = v
            if truthy(v) then Signal.Branch(depth) else Signal.Done
          case _ => null
      case _ => null

  /** THE HOT OPCODES, and only as many as fit under `-XX:FreqInlineSize` (325 bytecodes).
    *
    * `exec` calls this once per instruction, so whether it INLINES is the difference between a
    * dispatch and a virtual call plus a megamorphic switch. The full `step` was 5867 bytecodes:
    * compiled, because that is under 8000, and never inlined, because it is eighteen times the
    * inline limit. Splitting off the three opcodes that dominate a loop body — a constant, a move
    * and an arithmetic operation, which is every instruction in `arith-loop`'s inner loop except
    * the branch — leaves a dispatcher small enough to be inlined and sends everything else one call
    * further, where it was already going.
    *
    * The three cases were MOVED, not copied. Leaving them in `stepRest` as well would make it a
    * second decision site reachable by nobody: the next person to fix a `Bin` bug there would fix
    * dead code, and this repository has that failure written down more than once.
    */
  /** ONE interpreted instruction, for the closure lane to fall back on.
    *
    * SSC3-J2. `Compile` specializes the opcodes that pay for it and delegates the rest here, which
    * is what makes that lane COMPLETE from its first commit rather than a bail list that grows a
    * spec of its own the way v1's did. The delegation is one closure deep and the arm it reaches is
    * the same arm the tree-walker reaches, so the two lanes cannot disagree about an opcode that
    * neither of them specializes. */
  private[ssc3] def stepOne(m: Module, i: Instr, regs: Array[Value]): Signal = step(m, i, regs)

  private def step(m: Module, i: Instr, regs: Array[Value]): Signal = i match
    case Instr.Const(d, k) => regs(d) = constPool(k); Signal.Done
    case Instr.Move(d, a)  => regs(d) = regs(a); Signal.Done
    // The kind dispatch is a METHOD and not three lines here, and the reason is a measurement:
    // inline, `step` came to 326 bytes and HotSpot refused it with "hot method too big" — one byte
    // over `FreqInlineSize`. Observed with `-XX:+PrintInlining`, not deduced from the size, because
    // `--sizes` reports the last instruction's OFFSET and is therefore a lower bound by exactly the
    // margin that mattered here.
    case Instr.Bin(op, kind, d, a, b) => regs(d) = binK(m, op, kind, regs(a), regs(b)); Signal.Done
    case _ => stepRest(m, i, regs)

  /** Pick the arithmetic path the specializer proved. Small enough to inline in its own right, so
    * splitting it out of `step` costs a call the JIT removes and buys `step` its own inlining. */
  private[ssc3] def binK(m: Module, op: BinOp, kind: NumKind, x: Value, y: Value): Value =
    if kind == NumKind.I64 then binI64(m, op, x, y)
    else if kind == NumKind.F64 then binF64(m, op, x, y)
    else binOp(m, op, x, y)


  private def stepRest(m: Module, i: Instr, regs: Array[Value]): Signal = i match
    case Instr.Un(op, _, d, a) =>
      regs(d) = op match
        case UnOp.Neg  => regs(a) match
          case Value.VInt(n)   => Value.VInt(-n)
          case Value.VFloat(x) => Value.VFloat(-x)
          case v               => throw ExecError("neg on " + show(v))
        case UnOp.Not  => Value.VBool(!truthy(regs(a)))
        case UnOp.BNot => regs(a) match
          case Value.VInt(n) => Value.VInt(~n)
          case v             => throw ExecError("bnot on " + show(v))
      Signal.Done
    // SSC3-J1b — the `kind` field is READ here, and this is the whole point of `Specialize.scala`
    // writing it. The generic `binOp` is a 40-arm match on a `(op, a, b)` tuple that has to rule out
    // sets, strings, chars and lists before it reaches two longs; when the specializer has proved
    // both operands, that work is already done and `binI64` is a `long` operation behind one type
    // test each.
    //
    // THE KIND IS A CLAIM, NOT A GUARANTEE, and every fast path below falls back to `binOp` when
    // the values are not the shape the claim says. That is deliberate: it makes a defect in the
    // specializer a PERFORMANCE outcome rather than a wrong answer — the same trade v1 makes when an
    // un-compilable function is simply never compiled, and v2 when a backend answers null. Without
    // the fallback, one over-eager rewrite in a pass nothing else can see would silently change what
    // a program computes.

    // Structured control flow. A `Branch` propagates outward, losing one level per region — the
    // same rule the bridge implements with a counter, here as a returned value.
    case Instr.Block(b) =>
      exec(m, b, regs, RegionLevel) match
        case Signal.Branch(0) => Signal.Done
        case Signal.Branch(d) => Signal.Branch(d - 1)
        case other            => other
    case Instr.Loop(b) =>
      // SSC3-J4. THE DECISION IS TAKEN HERE, and that placement is the whole pass. `b` is an
      // immutable `List[Instr]`, so which positions hold a fusable pair cannot change however long
      // the loop runs — and a `Loop` is ENTERED once while `exec` below is called once per
      // ITERATION. The first version asked inside the walk and measured slower for exactly that
      // reason. A body with no pair gets `null` and the untouched `exec`, so it pays nothing.
      val fus = if fuseCmpBrOn then fusablePairs(b) else null
      var out: Signal = Signal.Done
      var looping = true
      while looping do
        (if fus == null then exec(m, b, regs, RegionLevel)
         else execFused(m, b, regs, fus.asInstanceOf[Array[Int]], RegionLevel)) match
          // A branch to a LOOP repeats it; a branch past it keeps unwinding. Falling off the end
          // EXITS, which is WebAssembly's rule and not the one most people expect.
          case Signal.Branch(0) => ()
          case Signal.Branch(d) => out = Signal.Branch(d - 1); looping = false
          case Signal.Done      => looping = false
          case other            => out = other; looping = false
      out
    case Instr.If(c, t, e) =>
      exec(m, if truthy(regs(c)) then t else e, regs, RegionLevel) match
        case Signal.Branch(0) => Signal.Done
        case Signal.Branch(d) => Signal.Branch(d - 1)
        case other            => other
    case Instr.Br(d)      => Signal.Branch(d)
    case Instr.BrIf(c, d) => if truthy(regs(c)) then Signal.Branch(d) else Signal.Done

    case Instr.Call(d, f, as) => regs(d) = callFunc(m, f, as.map(r => regs(r))); Signal.Done
    case Instr.CallV(d, c, as) =>
      regs(c) match
        case cv: Value.VCont =>
          val xs = as.map(r => regs(r))
          if xs.length != 1 then
            throw ExecError("a continuation takes exactly one value and was called with " + xs.length)
          regs(d) = resumeCont(m, cv, xs.head); Signal.Done
        case Value.VClos(f, cap) => regs(d) = callFunc(m, f, cap ++ as.map(r => regs(r))); Signal.Done
        case Value.VPartial(recv, nm, got) =>
          regs(d) = invoke(m, nm, recv, got ++ as.map(r => regs(r))); Signal.Done
        // `a(i)` on an ARRAY is an index, not a call. That is not a v3 invention: the bridge has
        // relied on it from the start — a frame read is `(app frame idx)` — so this is the executor
        // catching up with the semantics both lanes were already built on.
        // `m(k)` on a MAP is a lookup that yields the VALUE, not an Option — v1's `m("a")` is `1`.
        // A missing key is an error rather than a unit, because a silent unit is a wrong answer
        // that flows on and surfaces somewhere else.
        // `xs(i)` on a LIST. The corpus case that found it says so in its own description: `xs(i)`
        // and `xs.apply(i)` are the same operation and must agree on every lane.
        case Value.VData(_, _) if as.length == 1 && isList(m, regs(c)) =>
          val xs = listOut(m, regs(c))
          regs(a0(as)) match
            case Value.VInt(i) =>
              if i < 0 || i >= xs.length then
                throw ExecError("index " + i + " out of bounds for a list of " + xs.length)
              regs(d) = xs(i.toInt); Signal.Done
            case v => throw ExecError("list index " + show(v))
        case Value.VMap(es) if as.length == 1 =>
          val k = regs(a0(as))
          es.find((kk, _) => eq(kk, k)) match
            case Some((_, v)) => regs(d) = v; Signal.Done
            case None         => throw ExecError("key not found: " + show(k))
        case Value.VArr(xs) if as.length == 1 =>
          regs(a0(as)) match
            case Value.VInt(i) =>
              if i < 0 || i >= xs.length then
                throw ExecError("array index " + i + " out of bounds for length " + xs.length)
              regs(d) = xs(i.toInt); Signal.Done
            case v => throw ExecError("array index " + show(v))
        // A HOST-OWNED VALUE MAY BE CALLABLE — v2 keeps a `taggedApply` table for exactly this, and
        // `std-ui-i18n` reads a `NativeUiSignal` by calling it. Same door as a method, under the
        // name `apply`; anything no provider claims still gets this refusal unchanged.
        case v @ (Value.VForeign(_, _) | Value.VHostData(_, _)) =>
          regs(d) = applyValue(m, v, as.map(r => regs(r))); Signal.Done
        case v                   => throw ExecError("calling a non-function: " + show(v))
    case Instr.MkClos(d, f, caps) => regs(d) = Value.VClos(f, caps.map(r => regs(r))); Signal.Done
    // The point of the whole file: this does NOT recurse. It hands the trampoline a new target.
    case Instr.TailCall(f, as) => Signal.Tail(f, as.map(r => regs(r)))
    case Instr.Ret(a)          => Signal.Ret(regs(a))

    case Instr.MkData(d, t, as) => regs(d) = Value.VData(t, as.map(r => regs(r)).toArray); Signal.Done
    case Instr.Field(d, a, _, idx) =>
      regs(a) match
        case Value.VData(_, fs) => regs(d) = fs(idx); Signal.Done
        case v                  => throw ExecError("field read on " + show(v))
    case Instr.Tag(d, a) =>
      regs(a) match
        case Value.VData(t, _) => regs(d) = Value.VInt(t.toLong); Signal.Done
        // TOTAL, matching the bridge. Not a defensive default: a nested pattern tests the tag of a
        // FIELD, and a field is routinely not Data. `Right(42)` against `case Right(ByteRead(v, _))`
        // is a non-match in Scala, and throwing here made it a crash on one lane only.
        case _                 => regs(d) = Value.VInt(-1L); Signal.Done
    case Instr.Switch(s, arms, dflt) =>
      // A scrutinee that is not `Data` takes the DEFAULT rather than failing. That is v2's `match`
      // semantics and it is what makes a name that is both a field and a method resolvable at run
      // time: `r.head` on a record takes an arm, `xs.head` on a list falls through to dispatch.
      val chosen = regs(s) match
        case Value.VData(tg, _) => arms.find(a => a.tag == tg).map(a => a.body).getOrElse(dflt)
        case _                  => dflt
      exec(m, chosen, regs, RegionLevel) match
        case Signal.Branch(0) => Signal.Done
        case Signal.Branch(d) => Signal.Branch(d - 1)
        case other            => other

    case Instr.NewArr(d, n) =>
      val len = regs(n) match
        case Value.VInt(x) => x.toInt
        case v             => throw ExecError("array length " + show(v))
      val a = new Array[Value](len)
      var i = 0
      while i < len do
        a(i) = Value.VInt(0L)
        i = i + 1
      regs(d) = Value.VArr(a)
      Signal.Done
    case Instr.ArrGet(d, a, ix) =>
      (regs(a), regs(ix)) match
        case (Value.VArr(xs), Value.VInt(n)) =>
          if n < 0 || n >= xs.length then
            throw ExecError("index " + n + " is outside an array of " + xs.length)
          regs(d) = xs(n.toInt); Signal.Done
        case (x, _)                          => throw ExecError("array read on " + show(x))
    case Instr.ArrSet(a, ix, v) =>
      (regs(a), regs(ix)) match
        case (Value.VArr(xs), Value.VInt(n)) =>
          if n < 0 || n >= xs.length then
            throw ExecError("index " + n + " is outside an array of " + xs.length)
          xs(n.toInt) = regs(v); Signal.Done
        case (x, _)                          => throw ExecError("array write on " + show(x))
    case Instr.ArrLen(d, a) =>
      regs(a) match
        case Value.VArr(xs) => regs(d) = Value.VInt(xs.length.toLong); Signal.Done
        case v              => throw ExecError("array length of " + show(v))

    case Instr.GlobGet(d, g) => regs(d) = globals(g); Signal.Done
    case Instr.GlobSet(g, a) => globals(g) = regs(a); Signal.Done
    // ── effects, TAIL-RESUMPTIVE ONLY ───────────────────────────────────────────────────────────
    //
    // `Handle` pushes its arms and the frame they read; `Perform` walks out to the nearest arm for
    // the operation, runs it, and takes the resumed value as its own result.
    //
    // NO CONTINUATION IS CAPTURED, and that is the whole reason this fits in an executor that keeps
    // its call stack on the host's. It is correct for exactly one class — an arm that resumes ONCE,
    // as its last act — because for that class "run the arm, use the value" and "capture the rest,
    // hand it over, resume it" are the same computation. Outside that class they are not, so the
    // shape is CHECKED before the arm runs and anything else is refused BY NAME: a wrong answer
    // here would be indistinguishable from a working effect system.
    //
    // The protocol is `specs/10-ssc-ir.md` §3: `params` and `k` are registers of the HANDLING
    // function's frame, so the arm reads its arguments where the spec says they are, and nothing
    // about this is invented locally.
    case Instr.Handle(d, body, arms) =>
      // The segment depth is recorded HERE so a perform can take exactly the frames above it: the
      // handler must not own its own caller's remainder, only the ones opened inside its body.
      val hf = HandlerFrame(m, arms, regs)
      val act = new Delim(hf, pending.length)
      handlers = hf :: handlers
      actives  = act :: actives
      handleDepth = handleDepth + 1
      val sig =
        try exec(m, body, regs, d)
        catch
          // ITS OWN UNWIND, and the identity test is what makes nesting work: an inner `handle`'s
          // arm value must not be caught by an outer one.
          case a: PerformAbort if a.d eq act =>
            regs(d) = a.value
            Signal.Done
        finally
          handlers = handlers.tail
          actives  = actives.tail
          handleDepth = handleDepth - 1
      // THE RETURN CLAUSE, applied to the body's own value. `case x => List(x)` is what makes
      // `handle` produce the HANDLED type — a `List` for the list-monad handler — and without it
      // `resume(opt)` gave back the rest of the computation's `Int`, `flatMap` saw a non-list per
      // element and yielded nothing, and `effect-multishot` answered 0. A wrong answer that looks
      // like an answer. (BUGS.md v3-handle-has-no-return-clause.)
      //
      // `op = -1` marks it: an operation index is an index into the effect's declared operations
      // and is never negative, so no arm can collide with it.
      if sig == Signal.Done && hf.performs == 0L then
        retArm(arms).foreach(r => regs(d) = applyRet(m, r, regs, regs(d)))
      sig

    case Instr.Perform(d, op, as) =>
      val args = as.map(r => regs(r))
      handlers.find(h => h.arms.exists(_.op == op)) match
        case None =>
          throw ExecError("no handler for effect operation " + op)
        case Some(h) =>
          h.performs = h.performs + 1L
          val arm = h.arms.find(_.op == op).get
          // CPS MODE: one argument more than the arm binds, and the extra one is the continuation
          // (`specs/10-ssc-ir.md` §3, "Who PRODUCES the continuation"). The arm gets a real closure
          // in `k`, runs to completion, and ITS result is the perform's — which is the whole answer,
          // because `Cps.split` ended the performing function with `Ret` at this instruction.
          //
          // No tail-resumptive check here, and that is the point of the whole design: an arm may
          // resume once, not at all, or many times, because resuming is calling a closure.
          if args.length == arm.params.length + 1 then
            // A FRESH COPY OF THE HANDLER'S FRAME PER ACTIVATION.
            //
            // The arm reads its `params` and `k` from the HANDLING function's registers, and one
            // array cannot serve two activations at once. With multi-shot that happens immediately:
            // `case op(k) => k(1) + k(2)` over a function that performs TWICE re-enters the same arm
            // while the outer one is still live, and the inner binding overwrote the outer `k`.
            //
            // Measured before the fix: two performs with `k(1) + k(2)` gave 8, where the answer is
            // 12 — (1+1)+(1+2) resumed from a=1, plus (2+1)+(2+2) from a=2. A WRONG ANSWER, not a
            // refusal, which is the failure mode this executor is otherwise careful to avoid.
            val frame = h.regs.clone()
            var i = 0
            while i < arm.params.length do
              frame(arm.params(i)) = args(i)
              i = i + 1
            // THE ARM GETS A CONTINUATION, not the bare closure the lowering built. The closure
            // alone cannot answer "which handler's return clause lifts what I produce", and reading
            // that from the dynamic stack breaks the moment the continuation outlives the `handle`.
            //
            // It also carries the CALLER FRAMES between this perform and that handler — everything
            // `pending` gained since the `handle` pushed. Their registers are snapshot now: the
            // unwind below abandons the live arrays, and a multi-shot arm needs each resume to start
            // from the same state.
            val act   = actives.find(_.h eq h).getOrElse(
              throw ExecError("a handler for operation " + op + " is on the stack with no live " +
                              "activation; this is an executor invariant, not a program error"))
            val depth = pending.length - act.pendingDepth
            val seg   = pending.take(if depth > 0 then depth else 0)
                               .map(f => f.copy(regs = f.regs.clone()))
            // A FRAME OPENED INSIDE A REGION CANNOT BE RESUMED, so it is refused rather than
            // half-answered: its remainder is a suffix of the `if`/`loop`/`try` it sits in, and
            // resuming it would silently skip everything after that region.
            seg.find(_.fall == RegionLevel).foreach { _ =>
              throw ExecError(
                "operation " + op + " was performed under a call made inside an `if`, `loop` or " +
                "`try`, and the continuation would have to resume into that region — v3 captures a " +
                "continuation only across whole function bodies and the `handle` body")
            }
            frame(arm.k) = Value.VCont(args.last, h, seg)
            // The arm RETURNS its value — the lowering ends every arm body with a `Ret`, so there
            // is one place the answer comes from rather than a register the executor has to guess.
            // THE ARM RUNS AT THE HANDLER'S OWN SEGMENT DEPTH. Its body is part of the handling
            // function, not of the computation that performed, so a perform inside the arm must
            // capture from here and not inherit frames the continuation already owns.
            val savedPending = pending
            pending = pending.drop(if depth > 0 then depth else 0)
            val armSig = try exec(h.m, arm.body, frame, FuncLevel) finally pending = savedPending
            armSig match
              // THE VALUE IS THE `handle`'S, so the frames in between are abandoned rather than
              // returned through. Returning normally let the caller both see a value it should not
              // and re-run a remainder the continuation had taken over.
              case Signal.Ret(v) => throw PerformAbort(act, v)
              case _ =>
                throw ExecError(
                  "the handler for operation " + op + " ended without a value; a handler arm must " +
                  "produce one, and the lowering appends the `ret` that does it")
          else
            // THE UNCONVERTED PATH, unchanged. Reached only when the perform carries no
            // continuation — the function it came from was not split, so the tail-resumptive
            // fast path is the only thing that can run it.
            if arm.params.length != args.length then
              throw ExecError(
                "effect operation " + op + " was performed with " + args.length +
                " argument(s) and its handler binds " + arm.params.length)
            if !tailResumptive(arm) then
              throw ExecError(
                "this handler for operation " + op + " is not tail-resumptive — the executor " +
                "implements only an arm whose LAST act is a single `resume`. Capturing a " +
                "continuation needs the reified stack v3 does not have yet")
            var i = 0
            while i < arm.params.length do
              h.regs(arm.params(i)) = args(i)
              i = i + 1
            // The continuation register is bound to a marker rather than left stale: `Resume` reads
            // it, and a leftover value from a previous perform would resume the wrong thing silently.
            h.regs(arm.k) = Value.VUnit
            resumedWith = None
            exec(h.m, arm.body, h.regs, FuncLevel)
            resumedWith match
              case Some(v) => regs(d) = v; resumedWith = None; Signal.Done
              case None =>
                throw ExecError(
                  "the handler for operation " + op + " finished without resuming; the executor " +
                  "implements only tail-resumptive handlers")

    case Instr.Resume(d, k, v) =>
      regs(k) match
        // A REAL CONTINUATION. `Cps.split` put the rest of the performing function into a closure,
        // `Perform` paired it with the handler frame it belongs to, and resuming is `resumeCont` —
        // which is also what a CALL on the same value does, so the two spellings cannot drift.
        // Multi-shot needs no second mechanism: a closure is not consumed by being called, so an arm
        // may resume zero, one or many times and each call runs the rest of that function again.
        //
        // There is no `VClos` case here any more, and that is not a simplification: `arm.k` is bound
        // by `Perform` and by nothing else, so after it started binding a `VCont` a bare closure can
        // no longer arrive. The history it carried — why the return clause applies here at all, and
        // why the perform counter is read twice rather than reset — moved to `resumeCont`, which is
        // now the single place that knows it.
        case c: Value.VCont =>
          regs(d) = resumeCont(m, c, regs(v))
          Signal.Done
        // The unconverted path, unchanged: `k` is `unit`, the arm is tail-resumptive, and the value
        // travels back to the `Perform` that ran the arm.
        case _ =>
          resumedWith = Some(regs(v))
          regs(d) = regs(v)
          Signal.Done
    // The executor's own guard. `ExecError` is the only thing thrown by this lane, so catching it
    // is catching exactly what an SSC3 program can raise — a bare `catch Throwable` would also
    // swallow a StackOverflowError and report it as a caught user exception.
    case Instr.Try(d, b, x, h) =>
      try exec(m, b, regs, RegionLevel)
      catch
        // BOTH, and the difference is what gets bound. A program `throw` carries its VALUE; a
        // runtime failure the executor raises — `/ by zero`, an unimplemented method — has only a
        // message, and binding that is what this lane did for everything before.
        //
        // NARROWING THIS TO `ExecThrow` ALONE WAS TRIED AND REVERTED, which is why it is spelled
        // out: `try-catch.ssc` catches a division by zero, and it went uncatchable. That is not an
        // internal error being swallowed — it is a language-level exception, and Scala lets a
        // program catch it. v3 does not distinguish "the language raised" from "the executor
        // failed": both are `ExecError`, so refusing to catch them would refuse the first to be rid
        // of the second. Making that distinction is a real change and wants its own entry.
        case e: ExecThrow =>
          regs(x) = e.value
          exec(m, h, regs, RegionLevel)
        case e: ExecError =>
          regs(x) = Value.VStr(e.message)
          exec(m, h, regs, RegionLevel)
    case Instr.Invoke(d, nm, r, as) =>
      // `constStr` holds "" for a pool entry that is not an `LStr`, which is the same refusal the
      // list-walking version made — an empty method name reaches no arm of `invoke` and is reported
      // with the receiver. The verifier is what keeps this from being reachable at all.
      val name = constStr(nm)
      if name == "" then throw ExecError("an invoke whose name const is not a string")
      regs(d) = invoke(m, name, regs(r), as.map(x => regs(x)))
      Signal.Done
    case Instr.Prim(d, p, as) => regs(d) = prim(m, primTable(p), as.map(r => regs(r))); Signal.Done

    // `Const`, `Move` and `Bin` live in `step` (SSC3-J0c, which is what gets `step` under the inline
    // limit) and cannot arrive here — `step` answers them and only delegates what it does not.
    //
    // NAMED rather than left off. Without this arm the match is not exhaustive, so a broken
    // invariant would surface as a bare `MatchError` with no opcode in it, and the compiler's
    // warning about exactly these three is not free either: it landed in a gate's captured stderr
    // on the first build after a source change and was read as a program producing different
    // output. A gate should not be able to fail because the compiler had something to say.
    case _ =>
      throw ExecError("stepRest reached '" + Text.opcode(i) + "', which step is supposed to answer")

  // ── the method table ────────────────────────────────────────────────────────
  //
  // Enough of a library for the front's own programs to run on THIS lane as well as through the
  // bridge, which is what puts them under the differential gate. Every method here is one the
  // corpus or a fixture actually calls — the table grows by measurement, not by anticipation.
  //
  // A list is `Cons(head, tail)` / `Nil` as `VData`, and the tags are looked up BY NAME in the
  // module's type table rather than assumed, because they are per-module indices.

  // SSC3-J4c. `tagOf` used to run `m.types.indexWhere(_.name == name)` on EVERY call: a linear scan
  // of the module's type table with a string comparison per entry. It is on the hottest path there
  // is — `isList` calls it twice, and `listIn` and `listOut` twice each — so a single `xs.foreach`
  // costs four scans before any element is touched, and `list-fold` is the corpus row that spends
  // its life there.
  //
  // The cache is keyed on the MODULE BY REFERENCE. `Module` is an immutable case class, so the same
  // reference always has the same table, and a different one misses and recomputes; that is the
  // whole invariant. It sits beside `globals` and `compiled`, which are per-run executor state for
  // the same reason, and it is not thread-safe for the same reason they are not: this executor runs
  // one program on one thread.
  //
  // `--no-tag-cache` is the OFF arm of its measurement, the same shape `--no-specialize`,
  // `--no-optimize` and `--no-hoist` already have. A cache is exactly the kind of change whose
  // effect a reader will want to re-check on a quieter host a month from now, and that is only
  // possible in ONE binary if the old path is still reachable.
  private var tagCacheOn: Boolean = true
  private var tagCacheOwner: Module | Null = null
  private var tagCacheNames: Array[String] = new Array[String](0)
  private var tagCacheTags: Array[Int] = new Array[Int](0)

  private[ssc3] def useTagCache(on: Boolean): Unit =
    tagCacheOn = on
    tagCacheOwner = null

  private def tagOf(m: Module, name: String): Int =
    if !tagCacheOn then
      val i = m.types.indexWhere(t => t.name == name)
      return if i < 0 then -1 else i
    if !(tagCacheOwner eq m) then
      tagCacheOwner = m
      tagCacheNames = new Array[String](0)
      tagCacheTags = new Array[Int](0)
    var i = 0
    while i < tagCacheNames.length do
      // Reference equality first: every caller passes a literal, so the hit is a pointer compare and
      // the `==` behind it is the fallback for a name built at run time rather than the common path.
      if (tagCacheNames(i) eq name) || tagCacheNames(i) == name then return tagCacheTags(i)
      i = i + 1
    val found = m.types.indexWhere(t => t.name == name)
    val tag = if found < 0 then -1 else found
    val ns = new Array[String](tagCacheNames.length + 1)
    val ts = new Array[Int](tagCacheTags.length + 1)
    System.arraycopy(tagCacheNames, 0, ns, 0, tagCacheNames.length)
    System.arraycopy(tagCacheTags, 0, ts, 0, tagCacheTags.length)
    ns(tagCacheNames.length) = name
    ts(tagCacheTags.length) = tag
    tagCacheNames = ns
    tagCacheTags = ts
    tag

  /** Walks a `Cons` chain into a Scala list, and REFUSES anything that is neither `Cons` nor `Nil`.
    *
    *  It used to stop SILENTLY, which is a third behaviour and the wrong one: `Nil` ends the walk
    *  correctly — it IS the terminator — but so did an Int, and that element then contributed
    *  NOTHING. `xs.flatMap(f)` where `f` returned a number gave the EMPTY list, and a `foldLeft`
    *  over that produced a number that looked like an answer. `7730f6039` stopped the swallow, on
    *  the owner's instruction and rightly.
    *
    *  WHERE THAT LANDED IS NOW SPLIT, because the callers do not want one answer. This walk is for
    *  the operands that MUST be lists, and today that is `zip` — which refuses a non-list on every
    *  lane, so v3 refusing agrees with all of them. `flatMap` and `++` take `listOrOne` instead:
    *  measured 2026-08-14, both the reference lane and v3's OWN BRIDGE treat a non-list there as one
    *  element, so refusing made `ssc3 run` disagree with `ssc3 run --bridge` — an I-3 violation
    *  inside one compiler. See `listOrOne` for the table and for why "v2's runtime still swallows",
    *  written here on 2026-08-12, was wrong about which of the three behaviours v2 has.
    *
    *  The message still names the SITE, because a caller that reaches this walk has genuinely been
    *  handed the wrong shape and the site is what tells the reader which operand to look at.
    */
  private def listOut(m: Module, v: Value): List[Value] = listOut(m, v, "a list operation")

  /** A list operand that MAY be a bare value, in which case it is ONE ELEMENT.
    *
    * `xs.flatMap(f)` where `f` returns a number, and `xs ++ y` where `y` is not a list. Every other
    * lane answers, and they all answer the same thing — measured 2026-08-14 rather than reasoned
    * about, because the previous answer here was reasoned about:
    *
    *     op                     reference (bin/ssc run)    v2 VM (v3 --bridge)   v3 exec, before
    *     List.flatMap non-list  List(10, 20, 30)           List(10, 20, 30)      REFUSED
    *     List ++ non-list       List(1, 2, 5)              —                     REFUSED
    *     List.zip non-list      refuses "expected a list"  —                     refuses
    *
    * SO THIS IS NOT `listOut` WITH A FLAG, AND THAT IS THE WHOLE REASON IT IS A SECOND FUNCTION.
    * `listOut` serves `flatMap`, `zip` and `++`, and `zip` REFUSES on every lane. Relaxing the
    * shared walker would have made `zip` agree with nobody — the shape this repository keeps paying
    * for, where one helper is asked to answer for two representations.
    *
    * THREE BEHAVIOURS, NOT TWO, and collapsing them is what went wrong before. `7730f6039` changed
    * this walk from SWALLOWING a non-list to REFUSING it, on the owner's instruction, and it was
    * right that the swallow was a defect: a swallowed element made `xs.flatMap(f)` produce the
    * EMPTY list and a `foldLeft` over that returned a number that looked like an answer. What it got
    * wrong is one sentence — "v2's runtime still swallows". It does not; it WRAPS, which is this,
    * and `git log -S flatMap 4a93c440c..HEAD -- v2/src/` is empty, so it wrapped then too. Swallow,
    * wrap and refuse are three different answers and only one of them is every other lane's.
    *
    * THE REFUSAL WAS A LIVE I-3 VIOLATION, which is the fact that settles it without appeal to any
    * other implementation: `v3/ssc3 run --bridge` printed `List(10, 20, 30)` for a program
    * `v3/ssc3 run` refused. Two lanes of ONE compiler disagreeing is what I-3 exists to forbid.
    *
    * The corpus agrees independently: `js-effect-multishot-long-fold` carries a checked-in
    * expectation of 204, which is what a WRAPPING lane produces and what v3 answered 0 against —
    * and 0 is also what swallowing produces, so that case is the check that tells the two apart. */
  private def listOrOne(m: Module, v: Value): List[Value] =
    val consT = tagOf(m, "Cons")
    val nilT  = tagOf(m, "Nil")
    v match
      case Value.VData(t, f) if (t == consT && f.length == 2) || t == nilT => listOut(m, v)
      case other                                                          => List(other)

  private def listOut(m: Module, v: Value, site: String): List[Value] =
    val consT = tagOf(m, "Cons")
    val nilT  = tagOf(m, "Nil")
    var out: List[Value] = Nil
    var cur = v
    var go = true
    while go do
      cur match
        case Value.VData(t, f) if t == consT && f.length == 2 =>
          out = f(0) :: out
          cur = f(1)
        case Value.VData(t, _) if t == nilT => go = false
        // The `flatMap` arm that used to hang off this message is GONE with the caller: `flatMap`
        // and `++` take `listOrOne` now, so nothing reaching here is a handler-return-clause
        // problem, and advice naming one would send the reader to a construct they did not write.
        case other =>
          throw ExecError(site + " needs a List and got " + shapeOf(other))
    out.reverse

  /** A cheap description of what terminated the walk — the CONSTRUCTOR or primitive kind, never the
    * value's full rendering, because `showV` on a deep structure would make the error message cost
    * more than the operation that failed. */
  private def shapeOf(v: Value): String = v match
    case Value.VData(t, f) => "a value of constructor #" + t + " with " + f.length + " field(s)"
    case Value.VInt(n)     => "the Int " + n
    case Value.VFloat(d)   => "the Double " + d
    case Value.VStr(s)     => "a String"
    case Value.VBool(b)    => "the Boolean " + b
    case Value.VChar(c)    => "a Char"
    case other             => other.getClass.getSimpleName

  /** A total order over values, for `sorted`/`sortBy`/`min`/`max`. Numbers compare numerically,
    * strings lexicographically, and everything else by its printed form — which is what keeps the
    * result DEFINED rather than dependent on iteration order. The reference lane has its own
    * `valueOrdering`; the differential is what says whether these two agree, so any disagreement
    * shows up as a failing probe rather than as an argument. */
  private def cmp(a: Value, b: Value): Int = (a, b) match
    case (Value.VInt(x), Value.VInt(y))     => x.compareTo(y)
    case (Value.VFloat(x), Value.VFloat(y)) => x.compareTo(y)
    case (Value.VInt(x), Value.VFloat(y))   => x.toDouble.compareTo(y)
    case (Value.VFloat(x), Value.VInt(y))   => x.compareTo(y.toDouble)
    case (Value.VChar(x), Value.VChar(y))   => x.compareTo(y)
    case (Value.VStr(x), Value.VStr(y))     => x.compareTo(y)
    case (Value.VBool(x), Value.VBool(y))   => x.compareTo(y)
    case (x, y)                             => show(x).compareTo(show(y))

  private def someOf(m: Module, v: Value): Value =
    val t = tagOf(m, "Some")
    if t < 0 then throw ExecError("this module declares no `Some`")
    val f = new Array[Value](1)
    f(0) = v
    Value.VData(t, f)

  /** How far a fold will walk a LazyList before deciding the source is unbounded. Large enough that
    * no honest corpus program reaches it, small enough that the refusal arrives while someone is
    * still watching. */
  private val LazyStepBudget = 10000000L

  /** Walk a lazy sequence to its end, or refuse by name. `sum` on `LazyList.from(0)` has no answer;
    * the only three things this could do are hang, guess, or say so. */
  private def forceLazy(step: () => Option[(Value, Value)], why: String): List[Value] =
    val buf = scala.collection.mutable.ListBuffer.empty[Value]
    var cur = step
    var go = true
    while go do
      if buf.length >= LazyStepBudget then
        throw ExecError(
          why + " walked " + buf.length + " elements without reaching the end; a LazyList this " +
          "long is either infinite or a mistake — `take(n)` first")
      cur() match
        case None => go = false
        case Some((h, t)) =>
          buf += h
          cur = (t match { case Value.VLazy(ts) => ts; case _ => () => None })
    buf.toList

  private def rightOf(m: Module, v: Value): Value =
    val t = tagOf(m, "Right")
    if t < 0 then throw ExecError("this module declares no `Right`")
    val f = new Array[Value](1)
    f(0) = v
    Value.VData(t, f)

  private def noneOf(m: Module): Value =
    val t = tagOf(m, "None")
    if t < 0 then throw ExecError("this module declares no `None`")
    Value.VData(t, new Array[Value](0))

  private def tup2(m: Module, a: Value, b: Value): Value =
    val t = tagOf(m, "Tuple2")
    if t < 0 then throw ExecError("this module declares no `Tuple2`")
    val f = new Array[Value](2)
    f(0) = a
    f(1) = b
    Value.VData(t, f)

  private def listIn(m: Module, xs: List[Value]): Value =
    val consT = tagOf(m, "Cons")
    val nilT = tagOf(m, "Nil")
    if consT < 0 || nilT < 0 then throw ExecError("this module declares no list constructors")
    var acc: Value = Value.VData(nilT, new Array[Value](0))
    xs.reverse.foreach { x =>
      val f = new Array[Value](2)
      f(0) = x
      f(1) = acc
      acc = Value.VData(consT, f)
    }
    acc

  private def isList(m: Module, v: Value): Boolean = v match
    case Value.VData(t, _) => t == tagOf(m, "Cons") || t == tagOf(m, "Nil")
    case _                 => false

  // SSC3-J5. `cap :+ x` built a FRESH `List` on every application — `:+` copies the capture list and
  // appends, so a closure with k captures allocated k+1 cons cells per element — and `callFunc` then
  // walked that list straight back into the frame array. The list exists for one purpose: to be
  // taken apart again, one instruction later.
  //
  // `callClos1` writes the captures and the argument into the frame directly. Same frame, same arity
  // message, same tail-call behaviour — the list in the middle is the only thing that goes.
  //
  // This is the path `xs.foreach(f)`, `xs.map(f)` and every other one-argument callback take, which
  // is once PER ELEMENT: `list-fold` applies it 100 000 times per `workload()` iteration.
  private var fastApply: Boolean = true

  private[ssc3] def useFastApply(on: Boolean): Unit = fastApply = on

  /** APPLY A CLOSURE FROM OUTSIDE THE INSTRUCTION LOOP — the door a host callback comes back
    * through.
    *
    * `coroutineCreate(body)` hands a plugin a function and the plugin CALLS it later, from its own
    * thread. Nothing else in the executor needs this: every ordinary call is an instruction, and a
    * closure only escapes the loop when a host holds one. So the surface is deliberately one method
    * and it takes the module, because a closure is a function INDEX and means nothing without the
    * table it indexes.
    *
    * Re-entrant by construction — it goes through the same `callFunc` an instruction would — and
    * that is what makes a host callback that itself calls back into the program work rather than
    * being a special case. */
  def applyValue(m: Module, f: Value, args: List[Value]): Value = f match
    case Value.VClos(g, cap) => callFunc(m, g, cap ++ args)
    // A CONTINUATION IS CALLABLE, not only resumable. `case op(k) => (s: Int) => k(())(s)` reaches
    // this path rather than `Instr.Resume`, and routing it through `resumeCont` is what keeps the
    // two spellings from disagreeing about the return clause.
    case c: Value.VCont if args.length == 1 => resumeCont(m, c, args.head)
    case Value.VForeign(_, _) | Value.VHostData(_, _) => hostApply(m, f, args)
    case v => throw ExecError("not a function: " + show(v))

  private def apply1(m: Module, f: Value, x: Value): Value = f match
    case c: Value.VCont => resumeCont(m, c, x)
    case Value.VClos(g, cap) =>
      if fastApply then callClos1(m, g, cap, x) else callFunc(m, g, cap :+ x)
    case Value.VForeign(_, _) | Value.VHostData(_, _) => hostApply(m, f, List(x))
    case v => throw ExecError("not a function: " + show(v))

  /** CALLING A FUNCTION THE HOST OWNS. A plugin can RETURN a function — `signal-id-bridged` gets one
    * back from the ui provider — and v3 cannot represent a foreign closure as a `VClos`, which is a
    * function INDEX into this module. It travels as a handle instead, and calling it goes back out
    * through the same door a method does, under the name `apply`.
    *
    * Refused with the ordinary "not a function" when no provider claims it, so a handle that is not
    * callable reads exactly as any other non-function does. */
  private def hostApply(m: Module, f: Value, args: List[Value]): Value =
    Plugins.method(m, f, "apply", args)
      .getOrElse(throw ExecError("not a function: " + show(f)))

  /** One argument appended to a capture list, without the list. The frame-filling loop is
    * `callFunc`'s, kept identical on purpose — including that the arity check counts what was
    * OFFERED rather than what fitted, so a wrong-arity program fails with the same message. */
  private def callClos1(m: Module, f0: Int, cap: List[Value], x: Value): Value =
    prepare(m)
    val fn = funcTable(f0)
    val regs = new Array[Value](fn.nregs)
    var i = 0
    var cs = cap
    while cs.nonEmpty do
      if i < fn.nregs then regs(i) = cs.head
      cs = cs.tail
      i = i + 1
    if i < fn.nregs then regs(i) = x
    i = i + 1
    if i != fn.nparams then
      throw ExecError(fn.name + " takes " + fn.nparams + " argument(s), given " + i)
    while i < fn.nregs do
      regs(i) = Value.VUnit
      i = i + 1
    val sig = if closureLane then Compile.run(compiled(f0), regs) else exec(m, fn.body, regs, FuncLevel)
    sig match
      case Signal.Ret(v)      => v
      case Signal.Done        => Value.VUnit
      case Signal.Branch(d)   => throw ExecError("a branch left the function body (depth " + d + ")")
      // A tail call out of a one-argument callback is rare and its arguments are already a list, so
      // it rejoins `callFunc`'s loop rather than duplicating it here.
      case Signal.Tail(g, as) => callFunc(m, g, as)

  private def invoke(m: Module, name: String, recv: Value, args: List[Value]): Value =
    (recv, name) match
      // `a until b` / `a to b`. Materialised as a list rather than a lazy Range: every consumer in
      // the corpus immediately does `.map`/`.foldLeft`, and a lazy view would be a second sequence
      // kind for the executor to carry before anything needs one.
      //
      // The bound is checked. An unbounded or reversed range is a REFUSAL WITH A NAME, not an
      // out-of-memory kill: `0 until -1` is empty, which is correct, but a range wider than the
      // corpus could ever want is far more likely a bug in the program than an intention, and
      // silently allocating it turns a small mistake into a dead machine.
      case (Value.VInt(a), "until" | "to") =>
        args match
          case Value.VInt(b) :: Nil =>
            val last = if name == "to" then b else b - 1L
            if last - a >= 100000000L then
              throw ExecError(name + " would build a list of " + (last - a + 1L) + " elements")
            else if last < a then listIn(m, Nil)
            else listIn(m, (a to last).map(x => Value.VInt(x)).toList)
          case other =>
            throw ExecError(name + " takes one Int, given " + other.map(show).mkString(", "))
      case (Value.VStr(s), "length")      => Value.VInt(s.length.toLong)
      case (Value.VStr(s), "toUpperCase") => Value.VStr(s.toUpperCase)
      // A LANE DIVERGENCE, measured 2026-08-05: all four ran on the bridge and refused on the
      // executor. Not silence — the executor names the method — but a program that works on one
      // lane and not the other is exactly what invariant I-3 exists to prevent.
      case (Value.VStr(s), "substring") =>
        // The bounds are checked HERE rather than left to the host. A raw
        // `StringIndexOutOfBoundsException` is a CRASH to the corpus report — the bucket that says
        // "v3 neither ran it nor refused it cleanly" — while a named error is a refusal a reader
        // can act on. The reference lane throws too, so this is about the QUALITY of the failure.
        args match
          case Value.VInt(a) :: Nil =>
            if a < 0 || a > s.length then
              throw ExecError("substring(" + a + ") of a string of length " + s.length)
            Value.VStr(s.substring(a.toInt))
          case Value.VInt(a) :: Value.VInt(b) :: Nil =>
            if a < 0 || b > s.length || a > b then
              throw ExecError("substring(" + a + ", " + b + ") of a string of length " + s.length)
            Value.VStr(s.substring(a.toInt, b.toInt))
          case _ => throw ExecError("substring takes one or two integers")
      // ONE arm, all argument shapes. The first version split it in two and put the string case
      // first, so a CHARACTER argument hit the general throw before the arm meant for it was ever
      // reached — the arms were ordered by when they were written rather than by specificity.
      // TWO arguments: the second is a START OFFSET. Ignoring it did not fail — it returned the
      // first occurrence from zero, so a scan loop got an index BEHIND its own cursor and the
      // `substring(from, at)` that followed was backwards. The crash was three calls downstream of
      // the cause, which is what an ignored argument buys you.
      case (Value.VStr(s), "indexOf") =>
        val from = args.tail.headOption match
          case Some(Value.VInt(n)) => n.toInt
          case _                   => 0
        args.head match
          case Value.VStr(x)  => Value.VInt(s.indexOf(x, from).toLong)
          case Value.VChar(c) => Value.VInt(s.indexOf(c.toInt, from).toLong)
          case Value.VInt(n)  => Value.VInt(s.indexOf(n.toInt, from).toLong)
          case v              => throw ExecError("indexOf " + show(v))
      case (Value.VStr(s), "replace") =>
        (args.head, args.tail.head) match
          case (Value.VStr(a), Value.VStr(b)) => Value.VStr(s.replace(a, b))
          case _                              => throw ExecError("replace takes two strings")
      case (Value.VStr(s), "contains") =>
        args.head match
          case Value.VStr(x) => Value.VBool(s.contains(x))
          case v             => throw ExecError("contains " + show(v))
      case (Value.VStr(s), "startsWith") =>
        args.head match
          case Value.VStr(x) => Value.VBool(s.startsWith(x))
          case v             => throw ExecError("startsWith " + show(v))
      case (Value.VStr(s), "endsWith") =>
        args.head match
          case Value.VStr(x) => Value.VBool(s.endsWith(x))
          case v             => throw ExecError("endsWith " + show(v))
      case (Value.VStr(s), "nonEmpty") => Value.VBool(s.nonEmpty)
      case (Value.VStr(s), "reverse")  => Value.VStr(s.reverse)
      // A full-string regex match. Delegated to the HOST rather than given a matcher of its own:
      // the language has `matches` on every lane, so the portable subset can reach it, and a
      // hand-written engine would be a second regex semantics to keep in step with the reference.
      // This is I-1's boundary read correctly — the ban is on host CHARACTER CLASSIFICATION
      // deciding the language's syntax, not on using the host to implement a library method.
      case (Value.VStr(s), "matches") =>
        args.head match
          case Value.VStr(re) => Value.VBool(s.matches(re))
          case v              => throw ExecError("matches " + show(v))
      case (Value.VStr(s), "take")     => Value.VStr(s.take(intArg(args.head, "take")))
      case (Value.VStr(s), "drop")     => Value.VStr(s.drop(intArg(args.head, "drop")))
      case (Value.VStr(s), "toList")   => listIn(m, s.toList.map(c => Value.VChar(c)))
      case (Value.VStr(s), "lastIndexOf") =>
        val before = args.tail.headOption match
          case Some(Value.VInt(n)) => n.toInt
          case _                   => s.length
        args.head match
          case Value.VStr(x)  => Value.VInt(s.lastIndexOf(x, before).toLong)
          // A CHARACTER argument, which in this language is an integer that prints differently —
          // so both spellings arrive here and both must work.
          case Value.VChar(c) => Value.VInt(s.lastIndexOf(c.toInt, before).toLong)
          case Value.VInt(n)  => Value.VInt(s.lastIndexOf(n.toInt, before).toLong)
          case v              => throw ExecError("lastIndexOf " + show(v))
      case (Value.VStr(s), "count") =>
        Value.VInt(s.count(c => truthy(apply1(m, args.head, Value.VInt(c.toLong)))).toLong)
      case (Value.VInt(n), "abs")      => Value.VInt(if n < 0 then -n else n)
      case (Value.VFloat(d), "abs")    => Value.VFloat(if d < 0.0 then -d else d)
      // `charAt` returns a CHAR, and the comment that stood here — "an INT on the reference lane,
      // matching that is the point" — was true when written and is what changed underneath it.
      // `f39448c96` moved the reference onto `CharV extends IntV`: char LITERALS were already chars
      // there, `charAt` simply had never been moved onto the same model, so `println(s.charAt(0))`
      // printed a number.
      //
      // v3's bridge lane RUNS on that runtime and followed the same hour; this interpreter is a
      // fourth implementation of the primitive and did not, so the two v3 lanes disagreed for four
      // commits against invariant I-3 — `x/121/A/98/true/sq` here against `x/121/A/b/true/sq`
      // through the bridge. Nothing caught it because `v3.yml` watched only `v3/**` while half of
      // what `exec-gate.sh` compares lives in `v2/`.
      //
      // NOTHING ELSE NEEDS TO CHANGE, and that was checked rather than hoped: `Value.VChar` is
      // already the same "integer that prints as a character" — `binOp` coerces a VChar operand to
      // VInt, `eq` covers VChar against VInt, `toInt`/`toLong` are there, and `show` renders the
      // character. So `'x' + 1` stays 121 and `s.charAt(i) != 92` keeps meaning what it meant.
      case (Value.VStr(s), "charAt") =>
        args.head match
          case Value.VInt(i) =>
            if i < 0 || i >= s.length then throw ExecError("charAt " + i + " of a string of length " + s.length)
            Value.VChar(s.charAt(i.toInt))
          case v => throw ExecError("charAt " + show(v))
      case (Value.VStr(s), "toLowerCase") => Value.VStr(s.toLowerCase)
      case (Value.VStr(s), "isEmpty")     => Value.VBool(s.isEmpty)
      case (Value.VStr(s), "trim")        => Value.VStr(s.trim)
      // NO string `++` here, deliberately. v2's `__method__` has no dispatch for it — `"ab" ++ "cd"`
      // dies with `no dispatch for .++ on "ab"` through the bridge — so implementing it on this lane
      // alone would make the same program behave differently depending on which backend ran it.
      // Two lanes disagreeing is the thing the differential exists to catch, and adding a
      // convenience that only one of them has is manufacturing exactly that. `+` concatenates
      // strings on both.
      case (Value.VStr(s), "split") =>
        args.head match
          // THE SEPARATOR IS A REGULAR EXPRESSION, as it is in Scala and on the reference lane.
          // `Pattern.quote` made it a literal, so `"a **b** c".split("\\*\\*")` — the escaped-regex
          // form real code writes — matched nothing and returned the whole string as one part.
          // `v1/runtime/std/litdoc.ssc:155` does exactly that, and `litdoc` printed
          // `P(buy a **new** dress)` where it should print three spans.
          //
          // NEITHER FRONT COULD HAVE FOUND THIS. They agree on the tree, so both lanes printed the
          // same wrong line and the differential was green: agreement is not correctness. What
          // found it was the corpus, comparing against a recorded expectation from another lane.
          case Value.VStr(sep) =>
            val parts =
              try s.split(sep, -1)
              // An invalid pattern is not an executor crash: a `.` or a `|` in a separator someone
              // meant literally is ordinary, and the reference lane throws there too — but a raw
              // `PatternSyntaxException` reads as a v3 defect in every report.
              catch case e: java.util.regex.PatternSyntaxException =>
                throw ExecError("split by an invalid pattern '" + sep + "'")
            listIn(m, parts.toList.map(x => Value.VStr(x)))
          case v               => throw ExecError("split by " + show(v))
      case (Value.VSet(xs), "size")     => Value.VInt(xs.length.toLong)
      case (Value.VSet(xs), "isEmpty")  => Value.VBool(xs.isEmpty)
      case (Value.VSet(xs), "nonEmpty") => Value.VBool(xs.nonEmpty)
      case (Value.VSet(xs), "contains") => Value.VBool(xs.exists(y => eq(y, args.head)))
      case (Value.VSet(xs), "toList")   => listIn(m, xs)
      case (Value.VSet(xs), "sum") =>
        var acc = 0L
        xs.foreach { case Value.VInt(n) => acc = acc + n; case v => throw ExecError("sum over " + show(v)) }
        Value.VInt(acc)
      case (Value.VSet(xs), "mkString") =>
        val (pre, sep, post) = args match
          case Value.VStr(a) :: Value.VStr(b) :: Value.VStr(c) :: Nil => (a, b, c)
          case Value.VStr(a) :: Nil                                   => ("", a, "")
          case Nil                                                    => ("", "", "")
          case _ => throw ExecError("mkString takes no arguments, one, or three strings")
        Value.VStr(pre + xs.map(x => showV(m, x)).mkString(sep) + post)
      case (Value.VSet(xs), "subsetOf") =>
        args.head match
          case Value.VSet(ys) => Value.VBool(xs.forall(x => ys.exists(y => eq(y, x))))
          case v              => throw ExecError("subsetOf " + show(v))
      case (Value.VSet(xs), "++") =>
        args.head match
          case Value.VSet(ys) =>
            var out = xs
            ys.foreach { y => if !out.exists(z => eq(z, y)) then out = out :+ y }
            Value.VSet(out)
          case v => throw ExecError("++ with " + show(v))
      case (Value.VSet(xs), "map") =>
        var out: List[Value] = Nil
        xs.foreach { x =>
          val y = apply1(m, args.head, x)
          if !out.exists(z => eq(z, y)) then out = out :+ y
        }
        Value.VSet(out)
      case (Value.VSet(xs), "filter") =>
        Value.VSet(xs.filter(x => truthy(apply1(m, args.head, x))))
      case (Value.VSet(xs), "exists") =>
        Value.VBool(xs.exists(x => truthy(apply1(m, args.head, x))))
      case (Value.VSet(xs), "foreach") =>
        xs.foreach(x => apply1(m, args.head, x)); Value.VUnit
      case (Value.VSet(xs), "union") =>
        args.head match
          case Value.VSet(ys) =>
            var out = xs
            ys.foreach { y => if !out.exists(z => eq(z, y)) then out = out :+ y }
            Value.VSet(out)
          case v => throw ExecError("union with " + show(v))
      case (Value.VSet(xs), "intersect") =>
        args.head match
          case Value.VSet(ys) => Value.VSet(xs.filter(x => ys.exists(y => eq(y, x))))
          case v              => throw ExecError("intersect with " + show(v))
      case (Value.VSet(xs), "diff") =>
        args.head match
          case Value.VSet(ys) => Value.VSet(xs.filter(x => !ys.exists(y => eq(y, x))))
          case v              => throw ExecError("diff with " + show(v))
      case (Value.VMap(es), "size")     => Value.VInt(es.length.toLong)
      case (Value.VMap(es), "isEmpty")  => Value.VBool(es.isEmpty)
      case (Value.VMap(es), "nonEmpty") => Value.VBool(es.nonEmpty)
      case (Value.VMap(es), "contains") => Value.VBool(es.exists((k, _) => eq(k, args.head)))
      case (Value.VMap(es), "keys")     => listIn(m, es.toList.map(_._1))
      case (Value.VMap(es), "values")   => listIn(m, es.toList.map(_._2))
      case (Value.VMap(es), "get") =>
        es.find((k, _) => eq(k, args.head)) match
          case Some((_, v)) => someOf(m, v)
          case None         => noneOf(m)
      // ── LazyList ────────────────────────────────────────────────────────────────────────────
      // `LazyList.from(n)`. The lowering turns it into an invoke on the Int, so the receiver here
      // is the starting value and there is no `LazyList` name at run time to resolve.
      case (Value.VInt(n), "__lazyFrom__") =>
        def gen(i: Long): Value = Value.VLazy(() => Some((Value.VInt(i), gen(i + 1L))))
        gen(n)
      case (Value.VLazy(step), "map") =>
        val f = args.head
        def go(s: () => Option[(Value, Value)]): Value =
          Value.VLazy(() =>
            s() match
              case None => None
              case Some((h, t)) =>
                Some((apply1(m, f, h), t match { case Value.VLazy(ts) => go(ts); case other => other })))
        go(step)
      // `filter` is the reason this is a thunk and not a materialised prefix. It may skip
      // arbitrarily far before yielding, and on an infinite source that is the correct behaviour
      // rather than a hang — bounded by the same step budget as the folds below.
      case (Value.VLazy(step), "filter") =>
        val f = args.head
        def go(s: () => Option[(Value, Value)]): Value =
          Value.VLazy { () =>
            var cur = s
            var out: Option[(Value, Value)] = None
            var seen = 0L
            var searching = true
            while searching do
              cur() match
                case None => searching = false
                case Some((h, t)) =>
                  val rest = t match { case Value.VLazy(ts) => ts; case _ => () => None }
                  if truthy(apply1(m, f, h)) then
                    out = Some((h, go(rest))); searching = false
                  else
                    seen += 1
                    if seen > LazyStepBudget then
                      throw ExecError(
                        "filter scanned " + seen + " elements without a match; if the source is " +
                        "infinite this cannot terminate")
                    cur = rest
            out
          }
        go(step)
      case (Value.VLazy(step), "take") =>
        val k = args.head match
          case Value.VInt(x) => x
          case other         => throw ExecError("take needs an Int, given " + show(other))
        def go(s: () => Option[(Value, Value)], left: Long): Value =
          Value.VLazy(() =>
            if left <= 0 then None
            else
              s() match
                case None => None
                case Some((h, t)) =>
                  Some((h, t match { case Value.VLazy(ts) => go(ts, left - 1); case o => o })))
        go(step, k)
      case (Value.VLazy(step), "drop") =>
        val k = args.head match
          case Value.VInt(x) => x
          case other         => throw ExecError("drop needs an Int, given " + show(other))
        var cur = step
        var i = 0L
        var go = true
        while go && i < k do
          cur() match
            case None         => go = false
            case Some((_, t)) => cur = (t match { case Value.VLazy(ts) => ts; case _ => () => None }); i += 1
        Value.VLazy(cur)
      case (Value.VLazy(step), "head") =>
        step() match
          case Some((h, _)) => h
          case None         => throw ExecError("head of an empty LazyList")
      case (Value.VLazy(step), "isEmpty")  => Value.VBool(step().isEmpty)
      case (Value.VLazy(step), "nonEmpty") => Value.VBool(step().nonEmpty)
      // FORCING. An unbounded fold cannot finish, and hanging is the worst of the three possible
      // behaviours — worse than a wrong answer, because nothing says anything at all. The budget
      // turns it into a named refusal, exactly as the `until` range guard does.
      case (Value.VLazy(step), "toList" | "sum" | "size" | "length" | "foreach" | "foldLeft") =>
        val xs = forceLazy(step, name)
        name match
          case "toList" => listIn(m, xs)
          case "sum" =>
            if xs.exists { case Value.VFloat(_) => true; case _ => false } then
              Value.VFloat(xs.map { case Value.VFloat(d) => d; case Value.VInt(i) => i.toDouble
                                    case o => throw ExecError("sum over " + show(o)) }.sum)
            else Value.VInt(xs.map { case Value.VInt(i) => i
                                     case o => throw ExecError("sum over " + show(o)) }.sum)
          case "size" | "length" => Value.VInt(xs.length.toLong)
          case "foreach"         => xs.foreach(x => apply1(m, args.head, x)); Value.VUnit
          case _ /* foldLeft */  =>
            if args.length < 2 then Value.VPartial(recv, name, args)
            else xs.foldLeft(args.head)((a, b) => apply2(m, args(1), a, b))
      case (Value.VMap(es), "getOrElse") =>
        es.find((k, _) => eq(k, args.head)).map(_._2).getOrElse(args.tail.head)
      // `updated` COPIES. VMap wraps a mutable ArrayBuffer, so the one-line version that appends or
      // overwrites in place would work on `map-ops` and be wrong: `val b = a.updated(k, v)` must
      // leave `a` alone, and every later read of `a` would silently see `b`'s entry. That is a
      // defect no corpus row would catch, because no corpus row keeps the old map.
      case (Value.VMap(es), "updated") if args.length == 2 =>
        val k = args.head
        val v = args.tail.head
        val copy = es.clone()
        val i = copy.indexWhere((kk, _) => eq(kk, k))
        if i >= 0 then copy(i) = (k, v) else copy += ((k, v))
        Value.VMap(copy)
      // `(a, b) ++ (c, d)` is `(a, b, c, d)`. Tuples are synthetic `TupleN` case classes, and the
      // lowering pre-registers `Tuple2`..`Tuple8` whenever a `_n` accessor appears, so the widened
      // type already exists by the time this runs. Checking `t == tagOf(m, "Tuple" + f.length)` is
      // what makes "is this a tuple" answerable without a second type table to keep in sync.
      case (Value.VData(t, f), "++") if t == tagOf(m, "Tuple" + f.length) =>
        args match
          case Value.VData(t2, f2) :: Nil if t2 == tagOf(m, "Tuple" + f2.length) =>
            val n = f.length + f2.length
            val tag = tagOf(m, "Tuple" + n)
            if tag < 0 then
              throw ExecError("++ would build a Tuple" + n + ", which this module does not declare")
            else Value.VData(tag, f ++ f2)
          case other =>
            throw ExecError("++ on a tuple takes a tuple, given " + other.map(show).mkString(", "))
      case (Value.VArr(xs), "length") => Value.VInt(xs.length.toLong)
      case (Value.VArr(xs), "size")   => Value.VInt(xs.length.toLong)
      case (_, "toString")            => Value.VStr(showV(m, recv))
      // A cast is the IDENTITY here. Types are erased at Tier 0 — `20-core-language.md` §2 — so
      // `asInstanceOf` has nothing to check and nothing to change; refusing it would refuse a
      // no-op. Any value, which is why it sits before the per-type arms.
      case (_, "asInstanceOf")        => recv
      // SSC3-J0b — the receiver shapes that are not a distinct `Value` case (a LIST is a
      // `VData` chain, an `Option`, an `Either`, a tuple) plus the numeric conversions, lifted
      // WHOLE into `invokeRest`. A pure extraction: not one arm reordered, not one guard
      // changed, because `invoke` at 13415 bytecodes was over HotSpot's
      // `DontCompileHugeMethods` limit of 8000 and therefore never compiled at all — every
      // method call in every v3 program ran in the JVM's own bytecode interpreter, for the life
      // of the process. Splitting is the entire fix; the code is the same code.
      case _ => invokeRest(m, name, recv, args)

  /** The tail of `invoke`, lifted out to get that method under the JVM's compilation limit.
    * See the comment at its `case _` arm. Reached only when no arm with a concrete receiver
    * shape matched, so its own order is exactly the order it had inside the match. */
  private def invokeRest(m: Module, name: String, recv: Value, args: List[Value]): Value =
    if isList(m, recv) then
      val xs = listOut(m, recv)
      name match
        case "size" | "length" => Value.VInt(xs.length.toLong)
        case "isEmpty"         => Value.VBool(xs.isEmpty)
        case "nonEmpty"        => Value.VBool(xs.nonEmpty)
        case "head"            => if xs.isEmpty then throw ExecError("head of an empty list") else xs.head
        case "tail"            => listIn(m, if xs.isEmpty then Nil else xs.tail)
        case "sum" =>
          var acc = 0L
          xs.foreach { case Value.VInt(n) => acc = acc + n; case v => throw ExecError("sum over " + show(v)) }
          Value.VInt(acc)
        case "map"     => listIn(m, xs.map(x => apply1(m, args.head, x)))
        case "filter"  => listIn(m, xs.filter(x => truthy(apply1(m, args.head, x))))
        case "flatMap" => listIn(m, xs.flatMap(x => listOrOne(m, apply1(m, args.head, x))))
        // Every one of these ran on the BRIDGE and refused here. Found by probing the two
        // lanes with one program per method rather than by reading either implementation:
        // 23 of 32 probes were bridge-only, which no amount of code reading had suggested.
        case "exists"  => Value.VBool(xs.exists(x => truthy(apply1(m, args.head, x))))
        case "forall"  => Value.VBool(xs.forall(x => truthy(apply1(m, args.head, x))))
        case "count"   => Value.VInt(xs.count(x => truthy(apply1(m, args.head, x))).toLong)
        case "find" =>
          xs.find(x => truthy(apply1(m, args.head, x))) match
            case Some(v) => someOf(m, v)
            case None    => noneOf(m)
        case "sorted"  => listIn(m, xs.sortWith((a, b) => cmp(a, b) < 0))
        case "sortBy" =>
          listIn(m, xs.sortWith((a, b) => cmp(apply1(m, args.head, a), apply1(m, args.head, b)) < 0))
        case "zip" =>
          listIn(m, xs.zip(listOut(m, args.head, "zip")).map((a, b) => tup2(m, a, b)))
        case "take"     => listIn(m, xs.take(intArg(args.head, "take")))
        case "drop"     => listIn(m, xs.drop(intArg(args.head, "drop")))
        case "distinct" => listIn(m, dedup(xs))
        case "contains" => Value.VBool(xs.exists(x => eq(x, args.head)))
        case "indexOf"  => Value.VInt(xs.indexWhere(x => eq(x, args.head)).toLong)
        case "last" =>
          if xs.isEmpty then throw ExecError("last of an empty list") else xs.last
        case "init" =>
          if xs.isEmpty then throw ExecError("init of an empty list") else listIn(m, xs.init)
        case "min" =>
          if xs.isEmpty then throw ExecError("min of an empty list")
          else xs.reduce((a, b) => if cmp(a, b) <= 0 then a else b)
        case "max" =>
          if xs.isEmpty then throw ExecError("max of an empty list")
          else xs.reduce((a, b) => if cmp(a, b) >= 0 then a else b)
        // `xs.foldLeft(z)(f)` — two argument lists, so the first invoke gets one argument and
        // must return something the second can apply. Revealed the moment curried application
        // became parseable: the construct existed on the bridge and the executor had no way to
        // express it.
        // Found by running the CORPUS through this lane rather than a hand-picked probe set.
        // The probes were the top ~30 method names by frequency and they all passed; the corpus
        // reaches the tail, and the tail is where the lane was still behind.
        case "toList"   => recv
        case "toSet" =>
          var out: List[Value] = Nil
          xs.foreach { x => if !out.exists(y => eq(y, x)) then out = out :+ x }
          Value.VSet(out)
        case "apply"    => 
          regs0(m, xs, args.head)
        case "filterNot" => listIn(m, xs.filterNot(x => truthy(apply1(m, args.head, x))))
        case "takeWhile" => listIn(m, xs.takeWhile(x => truthy(apply1(m, args.head, x))))
        case "zipWithIndex" =>
          listIn(m, xs.zipWithIndex.map((x, i) => tup2(m, x, Value.VInt(i.toLong))))
        case "dropWhile" => listIn(m, xs.dropWhile(x => truthy(apply1(m, args.head, x))))
        case "headOption" =>
          if xs.isEmpty then noneOf(m) else someOf(m, xs.head)
        case "lastOption" =>
          if xs.isEmpty then noneOf(m) else someOf(m, xs.last)
        case "slice"    => listIn(m, xs.slice(intArg(args.head, "slice"), intArg(args.tail.head, "slice")))
        case "scanLeft" if args.length == 1 => Value.VPartial(recv, "scanLeft", args)
        case "scanLeft" =>
          listIn(m, xs.scanLeft(args.head)((acc, x) => apply2(m, args.tail.head, acc, x)))
        case "foldLeft" if args.length == 1  => Value.VPartial(recv, "foldLeft", args)
        case "foldRight" if args.length == 1 => Value.VPartial(recv, "foldRight", args)
        case "foldLeft" =>
          xs.foldLeft(args.head)((acc, x) => apply2(m, args.tail.head, acc, x))
        case "foldRight" =>
          xs.foldRight(args.head)((x, acc) => apply2(m, args.tail.head, x, acc))
        case "reduce" =>
          if xs.isEmpty then throw ExecError("reduce of an empty list")
          else xs.reduce((a, b) => apply2(m, args.head, a, b))
        case "reverse" => listIn(m, xs.reverse)
        // A LANE DIVERGENCE, not a missing feature: the bridge ran `foreach` all along and the
        // executor did not. Invisible because no fixture used it.
        case "foreach" =>
          xs.foreach(x => apply1(m, args.head, x))
          Value.VUnit
        // `++` KEEPS `listOut`, and that is a MEASUREMENT rather than an oversight. The reference
        // native lane wraps here — `List(1,2) ++ 5` is `List(1, 2, 5)` — so widening looked right,
        // and I did widen it. The parity probe added in the same commit immediately said no: the v2
        // VM REFUSES (`expected a list, got 5`, `Prims.unlistPub`), so wrapping made `ssc3 run`
        // disagree with `ssc3 run --bridge` — it CREATED the I-3 violation this commit exists to
        // remove, in the other operator. v2 wraps for `flatMap` and refuses for `++`; the asymmetry
        // is v2's, and v3's two lanes agreeing is what I-3 asks. Filed rather than papered over.
        // `listOrOne`, matching the doc block on that function — which described this call site
        // and was true of an earlier commit rather than of this line. The widening was reverted in
        // 2026-08-14 because it made `ssc3 run` disagree with `ssc3 run --bridge`: v3 wrapped and
        // the v2 VM refused. The bridge now wraps too (v2/src/Runtime.scala, same commit), so the
        // reason for the revert is gone and the two lanes agree again — which is the condition the
        // entry set for widening this. (v3/BUGS.md v3-concat-nonlist-splits-three-ways.)
        case "++"      => listIn(m, xs ++ listOrOne(m, args.head))
        case ":+"      => listIn(m, xs :+ args.head)
        case "+:"      => listIn(m, args.head :: xs)
        case "mkString" =>
          // THREE forms, not one: `mkString`, `mkString(sep)` and `mkString(start, sep, end)`.
          // Only the middle one was implemented, and the three-argument call silently used its
          // FIRST argument as the separator — `mkString("[", ", ", "]")` printed
          // `5[3[8[1[9[2`. A wrong answer, not a refusal, because the arity was never checked.
          val (pre, sep, post) = args match
            case Value.VStr(a) :: Value.VStr(b) :: Value.VStr(c) :: Nil => (a, b, c)
            case Value.VStr(a) :: Nil                                   => ("", a, "")
            case Nil                                                    => ("", "", "")
            case other => throw ExecError("mkString takes no arguments, one, or three strings")
          // `showV`, not `show`: this is OUTPUT, and `show` names raw tags. A zipped list
          // printed as #4(1, a) instead of (1, a) — the last of 32 parity probes to fall, and
          // the only one whose cause was in the PRINTER rather than in a missing method.
          Value.VStr(pre + xs.map(x => showV(m, x)).mkString(sep) + post)
        case other => throw ExecError("list method '" + other + "' is not implemented on this lane")
    else
      recv match
        // `Some`/`None` are ordinary constructors here, so their methods are too.
        // EITHER. `f1a82c9b8` made `Right`/`Left` constructible; nothing could be done with the
        // value afterwards, so `either-chain` got one step further and then stopped with
        // `method 'map' on #6(3) is not implemented`. Right-biased, as Scala is: `map` and
        // `flatMap` act on a `Right` and pass a `Left` through untouched.
        case Value.VData(t, f) if t == tagOf(m, "Right") && name == "map" =>
          rightOf(m, apply1(m, args.head, f(0)))
        case Value.VData(t, _) if t == tagOf(m, "Left") && name == "map" => recv
        // `flatMap` returns the function's result AS IS — it is already an Either, and wrapping
        // it in another `Right` is the classic off-by-one-layer that type-checks in a language
        // with types and here would silently produce `Right(Right(x))`.
        case Value.VData(t, f) if t == tagOf(m, "Right") && name == "flatMap" =>
          apply1(m, args.head, f(0))
        case Value.VData(t, _) if t == tagOf(m, "Left") && name == "flatMap" => recv
        // `fold(onLeft, onRight)` — two functions, and the LEFT one comes first, which is the
        // order Scala uses and the opposite of what the right-biased methods above suggest.
        case Value.VData(t, f) if t == tagOf(m, "Right") && name == "fold" && args.length == 2 =>
          apply1(m, args(1), f(0))
        case Value.VData(t, f) if t == tagOf(m, "Left") && name == "fold" && args.length == 2 =>
          apply1(m, args.head, f(0))
        case Value.VData(t, _) if t == tagOf(m, "Right") && name == "isRight" => Value.VBool(true)
        case Value.VData(t, _) if t == tagOf(m, "Left") && name == "isRight" => Value.VBool(false)
        case Value.VData(t, _) if t == tagOf(m, "Right") && name == "isLeft" => Value.VBool(false)
        case Value.VData(t, _) if t == tagOf(m, "Left") && name == "isLeft" => Value.VBool(true)
        case Value.VData(t, f) if t == tagOf(m, "Right") && name == "getOrElse" => f(0)
        case Value.VData(t, _) if t == tagOf(m, "Left") && name == "getOrElse" => args.head
        // `Either.toOption` — `Right(v)` is `Some(v)`, `Left(_)` is `None`. Scala's, and the
        // shape `scljet` uses to reach into a result it has already checked.
        case Value.VData(t, f) if t == tagOf(m, "Right") && name == "toOption" => someOf(m, f(0))
        case Value.VData(t, _) if t == tagOf(m, "Left") && name == "toOption" => noneOf(m)
        case Value.VData(t, f) if t == tagOf(m, "Some") && name == "get" => f(0)
        case Value.VData(t, _) if t == tagOf(m, "Some") && name == "isEmpty" => Value.VBool(false)
        case Value.VData(t, _) if t == tagOf(m, "None") && name == "isEmpty" => Value.VBool(true)
        case Value.VData(t, f) if t == tagOf(m, "Some") && name == "getOrElse" => f(0)
        case Value.VData(t, _) if t == tagOf(m, "Some") && name == "isDefined" => Value.VBool(true)
        case Value.VData(t, _) if t == tagOf(m, "None") && name == "isDefined" => Value.VBool(false)
        case Value.VData(t, f) if t == tagOf(m, "Some") && name == "map" =>
          someOf(m, apply1(m, args.head, f(0)))
        case Value.VData(t, _) if t == tagOf(m, "None") && name == "map" => noneOf(m)
        // `flatMap` returns the function's result AS IS — it is already an Option. Wrapping it
        // in another `Some` type-checks nowhere and here would silently build `Some(Some(x))`,
        // which then reads as a present value at every later `isDefined`. Same shape as the
        // Either case above; `map` is directly beside it, and the contrast is the point.
        case Value.VData(t, f) if t == tagOf(m, "Some") && name == "flatMap" =>
          apply1(m, args.head, f(0))
        case Value.VData(t, _) if t == tagOf(m, "None") && name == "flatMap" => noneOf(m)
        case Value.VData(t, f) if t == tagOf(m, "Some") && name == "foreach" =>
          apply1(m, args.head, f(0)); Value.VUnit
        case Value.VData(t, _) if t == tagOf(m, "None") && name == "foreach" => Value.VUnit
        case Value.VData(t, f) if t == tagOf(m, "Some") && name == "exists" =>
          Value.VBool(truthy(apply1(m, args.head, f(0))))
        case Value.VData(t, _) if t == tagOf(m, "None") && name == "exists" => Value.VBool(false)
        case Value.VData(t, f) if t == tagOf(m, "Some") && name == "filter" =>
          if truthy(apply1(m, args.head, f(0))) then recv else noneOf(m)
        case Value.VData(t, _) if t == tagOf(m, "None") && name == "filter" => recv
        case Value.VData(t, f) if t == tagOf(m, "Some") && name == "toList" => listIn(m, List(f(0)))
        case Value.VData(t, _) if t == tagOf(m, "None") && name == "toList" => listIn(m, Nil)
        case Value.VData(t, _) if t == tagOf(m, "None") && name == "getOrElse" => args.head
        // Numeric conversions. `toInt` is IDENTITY on an integer because ScalaScript's `Int` is
        // 64-bit — it is not a narrowing here, and treating it as one would silently change
        // large values. Every arm below was checked against the v2 lane on the same program
        // rather than assumed; the two lanes must agree or the differential gate is worthless.
        // A STRING parsed as a number. `"3".toInt` — the markdown module reads an ordered
        // list's start attribute that way, and it was the last thing between this lane and
        // that corpus case.
        case Value.VStr(x) if name == "toInt" || name == "toLong" =>
          try Value.VInt(java.lang.Long.parseLong(x.trim))
          catch case _: NumberFormatException => throw ExecError("'" + x + "' is not an integer")
        case Value.VStr(x) if name == "toDouble" =>
          try Value.VFloat(x.trim.toDouble)
          catch case _: NumberFormatException => throw ExecError("'" + x + "' is not a number")
        case Value.VInt(n) if name == "toInt"  => Value.VInt(n.toInt.toLong)
        case Value.VInt(n) if name == "toLong" => Value.VInt(n)
        case Value.VInt(n) if name == "toDouble" => Value.VFloat(n.toDouble)
        case Value.VFloat(d) if name == "toInt"  => Value.VInt(d.toLong.toInt.toLong)
        case Value.VFloat(d) if name == "toLong" => Value.VInt(d.toLong)
        case Value.VFloat(d) if name == "toDouble" => Value.VFloat(d)
        // `65.toChar` is the one-character STRING "A", not a char value — which is not what I
        // reached for first. The reference lane is explicit (`v2/src/Runtime.scala:2000`:
        // `StrV((n & 0xffff).toChar.toString)`), and the corpus case depends on it: it prints
        // `65.toChar + 8364.toChar` as `A€` and `List(65,66,67).map(_.toChar).mkString` as
        // `ABC`, both of which are string behaviour. A `VChar` here read `A` correctly and then
        // diverged from the bridge on the very next method.
        //
        // MASKING THE LOW 16 BITS is the reference's too, and it is a real rule rather than
        // tidiness: `toChar` is a UTF-16 CODE UNIT, so it wraps rather than failing.
        case Value.VInt(n)  if name == "toChar" => Value.VStr((n & 0xffffL).toChar.toString)
        // A char IS an integer on both lanes — v2 has `CharV extends IntV`, so its `toInt` arm
        // catches one by inheritance. v3's `VChar` is a separate case, so it needs the arm
        // written out to answer the same.
        case Value.VChar(c) if name == "toInt" || name == "toLong" => Value.VInt(c.toLong)
        // Refused BY NAME, with the receiver shown. The tail of this message used to read
        // "`ssc3 run` uses the v2 runtime", which stopped being true on 2026-08-07 when `run`
        // switched to v3's own executor — a diagnostic that sends the reader to the wrong lane
        // is worse than one that says less.
        // A VALUE THE HOST OWNS GETS ASKED FIRST — and only a host-owned one, so nothing else in
        // this dispatch changes. `<handle GeneratorValue>.next` and `ProcessResult(…).exitCode` are
        // methods v2 resolves through its own tables; without this arm the programs that reach them
        // die here instead of refusing, and reaching them is exactly what the plugin path made
        // possible. Measured on the executor lane: five cases went from an honest refusal to a
        // crash when the path opened, which is the wrong half of the floor.
        // ASKED ONCE, and the `once` is load-bearing rather than tidy: `next` on a generator ADVANCES
        // it, so a version that tested `isDefined` in a guard and called again in the body would
        // consume two elements per call and produce a plausible, wrong answer.
        //
        // Only a host-owned receiver is offered. Everything else refuses with v3's own diagnostic,
        // unchanged — `<handle GeneratorValue>.next` and `ProcessResult(…).exitCode` are methods v2
        // resolves through tables of its own, and reaching them at all is what the plugin path made
        // possible: five cases went from an honest refusal to a crash when it opened.
        case _ =>
          val hosted = recv match
            case Value.VForeign(_, _) | Value.VHostData(_, _) =>
              Plugins.method(m, recv, name, args)
            case _ => None
          hosted.getOrElse(throw ExecError("method '" + name + "' on " + show(recv) +
                    "' is not implemented by v3's executor — `ssc3 run --bridge` runs it on v2"))

  private[ssc3] def constOf(l: Lit): Value = l match
    case Lit.LUnit     => Value.VUnit
    case Lit.LBool(b)  => Value.VBool(b)
    case Lit.LInt(n)   => Value.VInt(n)
    case Lit.LFloat(d) => Value.VFloat(d)
    case Lit.LStr(s)   => Value.VStr(s)
    case Lit.LBig(d)   => Value.VStr(d)
    case Lit.LBytes(h) => Value.VStr(h)

  // Takes the module for ONE arm: `"x = " + P(1, 2)` has to name the constructor the same way
  // `println` does, or a value prints one way on its own and another inside a string.
  /** Both operands proved `I64` by `Specialize`. SSC3-J1b.
    *
    * SMALL ON PURPOSE. Past `-XX:FreqInlineSize` (325 bytecodes) HotSpot stops inlining a hot method
    * into its caller, and the entire value of this method is being inlined into the dispatch loop —
    * `binOp` is 4352 and never will be. `v3/jit-gate.sh` reports the number.
    *
    * Every arm answers what `binOp` answers for the same pair, which is the property that makes the
    * `kind` field an optimization rather than a second semantics. Two that had to be checked in the
    * source rather than assumed: the zero cases throw `ExecError` with those exact messages (the
    * corpus compares OUTPUT, so letting the host's `ArithmeticException` out would change what a
    * program prints), and `Eq`/`Ne` route through `eq`, whose `VInt` arm is `x == y`.
    */
  private def binI64(m: Module, op: BinOp, x: Value, y: Value): Value = (x, y) match
    case (Value.VInt(p), Value.VInt(q)) => op match
      case BinOp.Add  => Value.VInt(p + q)
      case BinOp.Sub  => Value.VInt(p - q)
      case BinOp.Mul  => Value.VInt(p * q)
      case BinOp.Div  => if q == 0L then throw ExecError("/ by zero") else Value.VInt(p / q)
      case BinOp.Rem  => if q == 0L then throw ExecError("% by zero") else Value.VInt(p % q)
      case BinOp.Lt   => Value.VBool(p < q)
      case BinOp.Le   => Value.VBool(p <= q)
      case BinOp.Gt   => Value.VBool(p > q)
      case BinOp.Ge   => Value.VBool(p >= q)
      case BinOp.Eq   => Value.VBool(p == q)
      case BinOp.Ne   => Value.VBool(p != q)
      case BinOp.BAnd => Value.VInt(p & q)
      case BinOp.BOr  => Value.VInt(p | q)
      case BinOp.BXor => Value.VInt(p ^ q)
      case BinOp.Shl  => Value.VInt(p << q)
      case BinOp.Shr  => Value.VInt(p >> q)
      case BinOp.UShr => Value.VInt(p >>> q)
    // THE CLAIM WAS WRONG, so honour the values and not the annotation. A specializer defect is a
    // performance outcome here; without this arm it would be a wrong answer, in a field no output
    // gate can see until this method exists.
    case _ => binOp(m, op, x, y)

  /** Both operands proved `F64`. The same contract as `binI64`, and it is NOT symmetric with it:
    * `binOp` has no `Rem` arm for two doubles and no bitwise arms, so `5.0 % 2.0` throws there. Both
    * are delegated rather than implemented, because adding them here would give the fast path a
    * capability the generic path does not have — which is a divergence between two lanes of the same
    * executor, and the hardest kind to find. Division by zero is likewise NOT checked: the generic
    * arm produces an infinity, and so must this one. */
  private def binF64(m: Module, op: BinOp, x: Value, y: Value): Value = (x, y) match
    case (Value.VFloat(p), Value.VFloat(q)) => op match
      case BinOp.Add => Value.VFloat(p + q)
      case BinOp.Sub => Value.VFloat(p - q)
      case BinOp.Mul => Value.VFloat(p * q)
      case BinOp.Div => Value.VFloat(p / q)
      case BinOp.Lt  => Value.VBool(p < q)
      case BinOp.Le  => Value.VBool(p <= q)
      case BinOp.Gt  => Value.VBool(p > q)
      case BinOp.Ge  => Value.VBool(p >= q)
      case BinOp.Eq  => Value.VBool(p == q)
      case BinOp.Ne  => Value.VBool(p != q)
      case _         => binOp(m, op, x, y)
    case _ => binOp(m, op, x, y)

  private def binOp(m: Module, op: BinOp, a: Value, b: Value): Value = (op, a, b) match
    // `s + x` on a SET adds an element, and `s - x` removes one. The reference lane treats a set as
    // a value with these operators; without them `Set(1,2) + 3` reported an arithmetic error on a
    // program that is not arithmetic.
    case (BinOp.Add, Value.VSet(xs), y) =>
      if xs.exists(z => eq(z, y)) then Value.VSet(xs) else Value.VSet(xs :+ y)
    case (BinOp.Sub, Value.VSet(xs), y) => Value.VSet(xs.filter(z => !eq(z, y)))
    case (BinOp.Add, Value.VStr(x), y)               => Value.VStr(x + showV(m, y))
    // `"ab" * 3` IS "ababab" — string repetition, which Scala gives on `StringOps` and which every
    // other lane of this repository already answers, INCLUDING v3's own bridge:
    //
    //     native, interp, v3 --bridge   "ab" * 3  ->  ababab      "ab" * -1  ->  (empty)
    //     v3 exec, before               Mul on String ab and Int 3
    //
    // So this deletes an executor-only refusal rather than inventing a fifth behaviour — invariant
    // I-3, and the same reasoning the mixed Int/Double widening below was landed on. It is not a
    // hypothetical: `tests/conformance/indent-block-statements.ssc` builds its indentation with
    // `" " * depth` and was the corpus's last exec-side DIFF, correct on the bridge and dead here.
    //
    // NEGATIVE AND ZERO GIVE THE EMPTY STRING, measured on the three answering lanes rather than
    // taken from Scala's docs, because that is the value the corpus's callers actually see.
    //
    // ONE DIRECTION ONLY. `3 * "ab"` is not written here because Scala does not have it either;
    // adding it would be a fourth answer to a question nobody asks.
    case (BinOp.Mul, Value.VStr(x), Value.VInt(n))   =>
      Value.VStr(if n <= 0L then "" else x * n.toInt)
    // …and the OTHER way round. `1 + "x"` is a string on the reference lane; the executor handled
    // only a string on the LEFT and threw on the right, so `p._1 + p._2` over a mixed tuple failed
    // on one lane and printed on the other.
    case (BinOp.Add, x, Value.VStr(y))               => Value.VStr(showV(m, x) + y)
    // Past the two string arms, a char IS its code point — which is what makes `'x' + 1` 121 and
    // `'a' == 'a'` true without a second set of comparison arms.
    case (o, Value.VChar(c), b)                     => binOp(m, o, Value.VInt(c.toLong), b)
    case (o, a, Value.VChar(c))                     => binOp(m, o, a, Value.VInt(c.toLong))
    // MIXED Int/Double arithmetic widens the Int, exactly as the char arms above widen a char, and
    // for the same reason: every arm below is HOMOGENEOUS, so `1 * 2.0` reached none of them and
    // fell through to the failure case as `Mul on Int 1 and Double 2` — which reads as "v3 cannot
    // multiply" rather than "v3 has no widening".
    //
    // The direction was measured on the other lanes before it was chosen (2026-08-11), because the
    // other reading — that v3 is deliberately strict — would have made this a REGRESSION: interp,
    // native and the v2 bridge all widen, `7 / 2.0` is 3.5 on all three, and `r * 1000000.0` with
    // an Int `r` is what the shared bench wrapper's last line does on every column. So this deletes
    // a v3-only refusal instead of inventing a fourth behaviour (invariant I-3).
    //
    // FOUR OPERATORS, not five, and the omission is the measurement's: `%` on a Double is refused
    // by every lane (native `TYPEERR: % requires Int left operand`, interp `No method '%' on
    // Double`, v3 `Rem on Double`), so widening it would only swap one refusal's message for
    // another and would claim support that no lane has.
    //
    // ORDERING COMPARISONS ARE HERE TOO, and they were deliberately left out one day earlier. The
    // reason then: interp says `1 < 2.0` is true while native and v2 refuse it at type-check time
    // ("cannot unify Int vs Float"), so widening looked like picking a side in someone else's
    // divergence. That reasoning was answered by ONE measurement it had not made — v3's OWN bridge:
    //
    //     v3/ssc3 run --bridge   `1 < 2.0`  ->  true          `1 == 1.0`  ->  true
    //     v3/ssc3 run            `1 < 2.0`  ->  Lt on Int 1 and Double 2   ->  false
    //
    // So this was never a choice between v1's answer and v2's. v3's two lanes disagreed with each
    // other on every mixed comparison, which is invariant I-3, and the bridge's answers are also
    // the ones Scala gives. Refusing here was not neutrality; it was the executor being wrong in a
    // way that only showed up against its own other lane.
    //
    // `Eq`/`Ne` are in this list and NOT in `eq`, which is the narrowest place that fixes the
    // scalar case. Widening inside `eq` also reaches collection equality and pattern matching, and
    // measurement said stop: `List(1) == List(1.0)` is `false` on the executor, on the bridge AND
    // on interp — they agree, so there is nothing there to repair and changing it would open a
    // divergence where none existed. The first draft of this fix did exactly that, and the
    // both-lanes fixture caught it in one run.
    case (BinOp.Add | BinOp.Sub | BinOp.Mul | BinOp.Div |
          BinOp.Lt  | BinOp.Le  | BinOp.Gt  | BinOp.Ge |
          BinOp.Eq  | BinOp.Ne, Value.VInt(x), Value.VFloat(_)) =>
      binOp(m, op, Value.VFloat(x.toDouble), b)
    case (BinOp.Add | BinOp.Sub | BinOp.Mul | BinOp.Div |
          BinOp.Lt  | BinOp.Le  | BinOp.Gt  | BinOp.Ge |
          BinOp.Eq  | BinOp.Ne, Value.VFloat(_), Value.VInt(y)) =>
      binOp(m, op, a, Value.VFloat(y.toDouble))
    case (BinOp.Add, Value.VInt(x), Value.VInt(y))   => Value.VInt(x + y)
    case (BinOp.Sub, Value.VInt(x), Value.VInt(y))   => Value.VInt(x - y)
    case (BinOp.Mul, Value.VInt(x), Value.VInt(y))   => Value.VInt(x * y)
    // Converted AT THE SOURCE rather than caught wholesale. A blanket `catch RuntimeException`
    // around every instruction would also swallow a genuine executor bug and hand it to the
    // program's `catch` as if the program had caused it. Each of these has a message we wrote.
    case (BinOp.Div, Value.VInt(x), Value.VInt(y)) =>
      if y == 0L then throw ExecError("/ by zero") else Value.VInt(x / y)
    case (BinOp.Rem, Value.VInt(x), Value.VInt(y)) =>
      if y == 0L then throw ExecError("% by zero") else Value.VInt(x % y)
    case (BinOp.Add, Value.VFloat(x), Value.VFloat(y)) => Value.VFloat(x + y)
    case (BinOp.Sub, Value.VFloat(x), Value.VFloat(y)) => Value.VFloat(x - y)
    case (BinOp.Mul, Value.VFloat(x), Value.VFloat(y)) => Value.VFloat(x * y)
    case (BinOp.Div, Value.VFloat(x), Value.VFloat(y)) => Value.VFloat(x / y)
    case (BinOp.Lt, Value.VInt(x), Value.VInt(y))    => Value.VBool(x < y)
    case (BinOp.Le, Value.VInt(x), Value.VInt(y))    => Value.VBool(x <= y)
    case (BinOp.Gt, Value.VInt(x), Value.VInt(y))    => Value.VBool(x > y)
    case (BinOp.Ge, Value.VInt(x), Value.VInt(y))    => Value.VBool(x >= y)
    // Doubles could be ADDED and not COMPARED. The four arithmetic arms above have had `VFloat`
    // since they were written and these four never did, so `var i: Double = 0.0` with
    // `while i < 1000000.0` compiled, ran one instruction and died (`float-loop.ssc`, SSC3-7l).
    // Measured before it was fixed: the constant pool holds `(float 0.0)`, so the lowering was
    // right and only the executor was missing arms — worth checking rather than assuming, because
    // the other reading (literals lowered as ints) would have put the fix in a different file.
    case (BinOp.Lt, Value.VFloat(x), Value.VFloat(y)) => Value.VBool(x < y)
    case (BinOp.Le, Value.VFloat(x), Value.VFloat(y)) => Value.VBool(x <= y)
    case (BinOp.Gt, Value.VFloat(x), Value.VFloat(y)) => Value.VBool(x > y)
    case (BinOp.Ge, Value.VFloat(x), Value.VFloat(y)) => Value.VBool(x >= y)
    // STRINGS ORDER LEXICOGRAPHICALLY, as they do in Scala and on the reference lane, which spells
    // it `scmp` (`v2/src/Runtime.scala:1456`, `compareTo`). The four arithmetic-shaped arms above
    // covered `VInt` and `VFloat` and nothing else, so `"alice" < "carol"` reached the fallthrough
    // and died with `Lt on String alice and String carol` — 30 corpus cases, every one of them a
    // `scljet` SQL query with an ORDER BY.
    //
    // `compareTo` and not a locale collator: the reference compares code units, and a program that
    // sorts differently on two machines is worse than one that sorts unexpectedly on both.
    case (BinOp.Lt, Value.VStr(x), Value.VStr(y))    => Value.VBool(x.compareTo(y) < 0)
    case (BinOp.Le, Value.VStr(x), Value.VStr(y))    => Value.VBool(x.compareTo(y) <= 0)
    case (BinOp.Gt, Value.VStr(x), Value.VStr(y))    => Value.VBool(x.compareTo(y) > 0)
    case (BinOp.Ge, Value.VStr(x), Value.VStr(y))    => Value.VBool(x.compareTo(y) >= 0)
    // NO CHAR ARMS HERE. I wrote four and the compiler called them unreachable: two arms further
    // down already normalise a `VChar` to a `VInt` before dispatching, so chars have ordered
    // correctly all along. Recorded rather than silently deleted — an arm added where one already
    // existed is the same misreading as an arm missing where one is needed, and the compiler is
    // the only reason this one cost nothing.
    case (BinOp.Eq, x, y)                            => Value.VBool(eq(x, y))
    case (BinOp.Ne, x, y)                            => Value.VBool(!eq(x, y))
    case (BinOp.BAnd, Value.VInt(x), Value.VInt(y))  => Value.VInt(x & y)
    case (BinOp.BOr, Value.VInt(x), Value.VInt(y))   => Value.VInt(x | y)
    case (BinOp.BXor, Value.VInt(x), Value.VInt(y))  => Value.VInt(x ^ y)
    case (BinOp.Shl, Value.VInt(x), Value.VInt(y))   => Value.VInt(x << y)
    case (BinOp.Shr, Value.VInt(x), Value.VInt(y))   => Value.VInt(x >> y)
    case (BinOp.UShr, Value.VInt(x), Value.VInt(y))  => Value.VInt(x >>> y)
    // The TYPE, not only the rendered value. `show` prints a Double the way the reference lane
    // prints it, so `0.0` renders as `0` — correct for program output and actively misleading in a
    // diagnostic: `Lt on 0 and 1000000` reads as an Int problem, and it cost a wrong first
    // hypothesis about where the missing arms were. An operator arm is missing for a pair of TYPES,
    // so the types are what the message has to name.
    case (o, x, y) =>
      throw ExecError(
        o.toString + " on " + typeName(x) + " " + show(x) + " and " + typeName(y) + " " + show(y))

  private def eq(a: Value, b: Value): Boolean = (a, b) match
    case (Value.VInt(x), Value.VInt(y))     => x == y
    case (Value.VStr(x), Value.VStr(y))     => x == y
    case (Value.VChar(x), Value.VChar(y))   => x == y
    case (Value.VChar(x), Value.VInt(y))    => x.toLong == y
    case (Value.VInt(x), Value.VChar(y))    => x == y.toLong
    case (Value.VBool(x), Value.VBool(y))   => x == y
    case (Value.VFloat(x), Value.VFloat(y)) => x == y
    // NO Int/Double arm here, and that is a decision rather than an omission — `1 == 1.0` is made
    // true in `binOp` instead. `eq` is shared by scalar `==`, by COLLECTION equality and by pattern
    // matching, so widening here also makes `List(1) == List(1.0)` true. Measured 2026-08-11: that
    // is what Scala says, but it is not what any lane in this project says — the bridge, interp and
    // the executor all answer `false` and AGREE. Widening here therefore repaired one divergence
    // (scalar `==`, where the executor said false and the bridge said true) by creating another in
    // a place that was consistent. Fix what disagrees; leave what agrees.
    case (Value.VUnit, Value.VUnit)         => true
    // A SET is equal by CONTENT, not by order — `Set(1,2) == Set(2,1)` is true, and it is the
    // membership equality the corpus case is named for. Without this arm the comparison fell to the
    // catch-all `false`, so two identical sets were unequal.
    case (Value.VSet(a), Value.VSet(b)) =>
      a.length == b.length && a.forall(x => b.exists(y => eq(y, x)))
    case (Value.VMap(a), Value.VMap(b)) =>
      a.length == b.length &&
        a.forall((k, v) => b.exists((k2, v2) => eq(k, k2) && eq(v, v2)))
    case (Value.VData(t1, f1), Value.VData(t2, f2)) =>
      t1 == t2 && f1.length == f2.length && f1.indices.forall(i => eq(f1(i), f2(i)))
    case _ => false

  /** The host boundary, and the only place in the executor that touches the outside world — which
    * is what invariant I-1 asks of the whole kernel. An unknown primitive is refused by NAME. */
  private def prim(m: Module, name: String, args: List[Value]): Value = name match
    case "io.println" =>
      println(if args.isEmpty then "" else showV(m, args.head))
      Value.VUnit
    // A CLOCK, and it is `io.nanoTime` because that is what v2 already calls it
    // (`v2/src/Runtime.scala`): the bridge emits `(prim <name> …)` verbatim, so a name v2 does not
    // know would be a program that runs on one lane and is refused on the other. Same name, one
    // prim, both lanes.
    //
    // This is what `Prim` is FOR. Invariant I-1 says everything outside the kernel reaches the
    // language through `Prim` and the plugin SPI, so routing a clock through it is the boundary
    // WORKING, not the boundary moving — the note in SSC3-3d that implied otherwise was wrong.
    case "io.nanoTime" => Value.VInt(System.nanoTime())
    // FLOATING-POINT MATH, and the names and the SEMANTICS are both v2's — `v2/src/Runtime.scala`
    // lines 1434-1437 — for the reason the clock's name is: the bridge emits `(prim <name> …)`
    // verbatim, so a prim this lane implements differently is a program that answers twice.
    //
    // `f.round` IS `rint`, NOT Scala's `math.round`. That is the whole reason this comment exists.
    // `math.round` is half-up and returns a Long; `rint` is half-to-EVEN and returns a Double, and
    // v2 chose `rint`. They agree on every value the corpus contains and disagree at exactly `.5`
    // — `rint(2.5)` is 2.0, `round(2.5)` is 3 — so writing the obvious one here would have left a
    // divergence that no fixture in the tree can see, waiting for the first program to round a
    // half. Reading v2 rather than reaching for the familiar name is the whole of it.
    //
    // STRICT ABOUT THE ARGUMENT, and I wrote the opposite here first. The comment said "v2's `flt`
    // accepts either" and coerced an Int — plausible, and NOT MEASURED. A probe written in the same
    // commit put `math.sqrt(16)` through both lanes: the executor answered 4 and the bridge died
    // with `expected Float, got 16` out of `Prims.flt`. v2 does not widen, so widening here is a
    // divergence, not a kindness.
    //
    // The WIDENING LIVES IN THE PRELUDE instead — `def sqrt(x: Double) = __mathSqrt(x.toDouble)` —
    // where one line in ScalaScript serves both lanes and `math.sqrt(16)` works on each. That is
    // the same rule the IO entries follow: when the lanes must agree, put the adaptation ABOVE the
    // prim, never inside one lane's copy of it.
    // BINARY, so it reads both arguments — and it is `Math.pow`, matching v2's `math.pow` and the
    // reference lane bit for bit. `pow(2.0, 0.1)` is 1.0717734625362931 on every lane; a series or
    // a repeated-sqrt expansion would land ~1e-12 out, which is why this waited for a v2 prim
    // instead of being approximated in the prelude.
    case "f.pow"   => Value.VFloat(Math.pow(dbl(args.head, "f.pow"), dbl(args(1), "f.pow")))
    case "f.sqrt"  => Value.VFloat(Math.sqrt(dbl(args.head, "f.sqrt")))
    case "f.floor" => Value.VFloat(Math.floor(dbl(args.head, "f.floor")))
    case "f.ceil"  => Value.VFloat(Math.ceil(dbl(args.head, "f.ceil")))
    case "f.round" => Value.VFloat(Math.rint(dbl(args.head, "f.round")))
    // FILE IO, and the name is v2's for the same reason the clock's is: the bridge emits
    // `(prim <name> …)` verbatim, so the only host functions v3 may perform are ones the v2 VM
    // performs identically. That intersection IS invariant I-3 — a host function on one lane and
    // not the other is a program that runs here and dies there — and `Lower.hostPrims` is the table
    // that keeps both halves in step.
    //
    // ONE PRIM, AND THE OTHER TWO WERE REMOVED RATHER THAN LEFT LYING HERE. I first implemented
    // `io.readFile`/`io.writeFile` too, over Scala `String`s, because the NAMES matched
    // `std/fs.ssc`. v2's do not: `Runtime.scala:1943` reads to `BytesV` and writes from bytes, and
    // the bridge died with `expected Bytes, got "hello from ScalaScript"` on the first probe.
    // Deleting them is the point — an implementation that disagrees with v2's semantics, reachable
    // by nothing today, is a trap set for whoever next adds a line to `hostPrims`. When those two
    // arrive they arrive on both lanes together, with v3 modelling v2's bytes.
    case "io.exists" =>
      args.head match
        case Value.VStr(path) => Value.VBool(new java.io.File(path).exists)
        case v                => throw ExecError("exists takes a path: " + show(v))
    // THE ENVIRONMENT, and it returns an `Option` because that is what v2 returns
    // (`Runtime.scala:1985`: `sys.env.get(…).fold(none)(some)`). The shape has to match or the two
    // lanes disagree about a missing variable — the executor would hand back a bare string where
    // the bridge hands back `None`, and `envOrElse`'s `.getOrElse` would then be applied to the
    // wrong thing on one of them.
    case "io.env" =>
      args.head match
        case Value.VStr(k) => sys.env.get(k).map(s => someOf(m, Value.VStr(s))).getOrElse(noneOf(m))
        case v             => throw ExecError("io.env takes a name: " + show(v))
    // BYTES IN AND OUT, matching v2's shapes exactly — `Runtime.scala:1943` reads a file to
    // `BytesV` and `io.writeFile` takes bytes at argument 1. Getting this wrong once already cost a
    // lane divergence (`expected Bytes, got "hello from ScalaScript"`), which is why the table in
    // `Lower.hostPrims` composes rather than renames.
    case "io.readFile" =>
      args.head match
        case Value.VStr(path) =>
          try Value.VBytes(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)))
          // THE LANGUAGE'S OWN THROW, catchable by a ScalaScript `try/catch`. An escaping
          // `NoSuchFileException` would kill the interpreter with a host stack trace, which the
          // corpus report classifies as CRASH — worse than a wrong answer.
          catch case e: java.io.IOException =>
            val msg = "readFile: " + path + ": " + e.getClass.getSimpleName
            throw ExecThrow(Value.VStr(msg), msg)
        case v => throw ExecError("readFile takes a path: " + show(v))
    case "io.writeFile" =>
      (args.head, args.tail.head) match
        case (Value.VStr(path), Value.VBytes(b)) =>
          try
            val f = new java.io.File(path)
            if f.getParentFile != null then f.getParentFile.mkdirs()
            java.nio.file.Files.write(java.nio.file.Paths.get(path), b)
            Value.VUnit
          catch case e: java.io.IOException =>
            val msg = "writeFile: " + path + ": " + e.getClass.getSimpleName
            throw ExecThrow(Value.VStr(msg), msg)
        case (p, v) => throw ExecError("writeFile takes a path and bytes: " + show(p) + ", " + show(v))
    // THE TWO HALVES OF THE COMPOSITION, and v2 spells them exactly this way
    // (`Runtime.scala:1554`, `:3464`). UTF-8 is not a choice made here — it is v2's, and the two
    // lanes must decode a file identically or the same program prints differently.
    case "utf8->str" =>
      args.head match
        case Value.VBytes(b) => Value.VStr(new String(b, "UTF-8"))
        // Already a string: v2's own `utf8->str` accepts one, and a lane that refused it would
        // diverge on a program that round-trips text through the conversion twice.
        case Value.VStr(s)   => Value.VStr(s)
        case v               => throw ExecError("utf8->str takes bytes: " + show(v))
    // `blen`/`bget`/`hex->bytes` — v2's spellings again, because `Lower.bytesToList` and
    // `listToBytes` synthesise a loop over them and that loop runs on BOTH lanes.
    case "blen" =>
      args.head match
        case Value.VBytes(b) => Value.VInt(b.length.toLong)
        case v               => throw ExecError("blen takes bytes: " + show(v))
    case "bget" =>
      (args.head, args.tail.head) match
        // `& 0xff` — a JVM byte is SIGNED and the language's is not. Without it a byte above 127
        // arrives as a negative Int, which is why the round-trip fixture uses 200: every ASCII
        // probe passes with or without this mask. v2 masks identically (`Runtime.scala:1551`).
        case (Value.VBytes(b), Value.VInt(i)) => Value.VInt((b(i.toInt) & 0xff).toLong)
        case (x, y) => throw ExecError("bget takes bytes and an index: " + show(x) + ", " + show(y))
    // AN OPTION, because v2 returns one (`Runtime.scala:1487`): bad hex is `none` there, so a bare
    // `Bytes` here would be the two lanes disagreeing about the SHAPE of a result — the same
    // mistake as mapping `readFile` by name, caught the same way, by the bridge refusing with
    // `expected Bytes, got Some(#00417fc8ff)`. The hex string this is called with is built digit by
    // digit and cannot be malformed; matching v2 anyway is what keeps the rule "shape, not name".
    case "hex->bytes" =>
      args.head match
        case Value.VStr(h) =>
          val ok = h.length % 2 == 0 &&
                   h.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))
          if !ok then noneOf(m)
          else
            val out = new Array[Byte](h.length / 2)
            var k = 0
            while k < out.length do
              out(k) = Integer.parseInt(h.substring(k * 2, k * 2 + 2), 16).toByte
              k = k + 1
            someOf(m, Value.VBytes(out))
        case v => throw ExecError("hex->bytes takes a hex string: " + show(v))
    case "bytes->hex" =>
      args.head match
        case Value.VBytes(b) =>
          val sb = new StringBuilder
          b.foreach(x => sb.append("0123456789abcdef".charAt((x & 0xff) >> 4))
                           .append("0123456789abcdef".charAt(x & 0x0f)))
          Value.VStr(sb.toString)
        case v => throw ExecError("bytes->hex takes bytes: " + show(v))
    case "str->utf8" =>
      args.head match
        case Value.VStr(s)   => Value.VBytes(s.getBytes("UTF-8"))
        case Value.VBytes(b) => Value.VBytes(b)
        case v               => throw ExecError("str->utf8 takes a string: " + show(v))
    case "__autoOutput__" =>
      // Prints only a non-Unit value, exactly as v2 does — the rule the front relies on so that a
      // `println(…)` tail does not print twice.
      if args.nonEmpty && args.head != Value.VUnit then println(showV(m, args.head))
      Value.VUnit
    // The reference lane's `char`: an Int in, a character out.
    // The map primitives, the same names v2 exposes — so the BRIDGE emits `(prim map.put …)` and
    // this lane implements the identical vocabulary rather than a parallel one.
    case "set.of" =>
      var out: List[Value] = Nil
      args.foreach { x => if !out.exists(y => eq(y, x)) then out = out :+ x }
      Value.VSet(out)
    case "map.new" => Value.VMap(scala.collection.mutable.ArrayBuffer.empty)
    case "map.put" =>
      args.head match
        case Value.VMap(es) =>
          val k = args.tail.head
          val i = es.indexWhere((kk, _) => eq(kk, k))
          if i < 0 then es.append((k, args.tail.tail.head)) else es(i) = (k, args.tail.tail.head)
          Value.VUnit
        case v => throw ExecError("map.put on " + show(v))
    case "map.get" =>
      args.head match
        case Value.VMap(es) => es.find((k, _) => eq(k, args.tail.head)).map(_._2).getOrElse(Value.VUnit)
        case v              => throw ExecError("map.get on " + show(v))
    case "char" =>
      args.head match
        case Value.VInt(n) => Value.VChar(n.toChar)
        case v             => throw ExecError("char of " + show(v))
    // `case s: String =>` — the NOMINAL, FLAT type test the reference front emits
    // (`ssc1-lower.ssc0:3559`) and v2 implements (`Runtime.scala:1683`). Mirrored here rather than
    // reinvented: the two lanes must answer identically, and the frozen conformance goldens encode
    // the reference's answers — including the parts that look like quirks.
    //
    //   • ALIASES. A list is `Cons`/`Nil`, so `case _: List[?]` can never equal the tag; `List`,
    //     `Seq` and `Iterable` all name it. Same for `Option` over `Some`/`None`.
    //   • EXCEPTION SUPERTYPES are permissive. A caught error arrives tagged with the thrown
    //     class's simple name, and `catch { case e: Throwable => … }` must reach it without the
    //     user naming the concrete class. There is no subtype graph here to consult.
    //   • ARITY < 0 means "any", which is what a type ascription passes: it carries no field
    //     patterns, while `case Cons(h, t)` goes through the constructor path with its real arity.
    case "__isTag__" =>
      val expected = args(1) match
        case Value.VStr(x) => x
        case v             => throw ExecError("__isTag__ expects a type name, got " + show(v))
      val arity = args(2) match
        case Value.VInt(n) => n.toInt
        case _             => -1
      def superTag(n: String): Boolean =
        n == "Throwable" || n == "Exception" || n == "RuntimeException" || n == "Error"
      val yes = args.head match
        // THE TAG IS AN INDEX HERE, not a name — v3's `VData` carries the type table's slot while
        // v2's `DataV` carries the string. The comparison is against the NAME, so the table is
        // consulted; comparing the index to the name would have made every type test false, which
        // is a pattern that silently never matches.
        case Value.VData(ti, fs) =>
          val t = if ti >= 0 && ti < m.types.length then m.types(ti).name else ""
          val listTag  = t == "Cons" || t == "Nil"
          val listName = expected == "List" || expected == "Seq" || expected == "Iterable"
          val optTag   = t == "Some" || t == "None"
          val optName  = expected == "Option"
          if (listTag && listName) || (optTag && optName) then true
          else (t == expected || superTag(expected)) && (arity < 0 || fs.length == arity)
        case other =>
          val prim = other match
            case Value.VUnit      => expected == "Unit"
            case Value.VBool(_)   => expected == "Boolean" || expected == "Bool"
            case Value.VInt(_)    => expected == "Int" || expected == "Long"
            case Value.VFloat(_)  => expected == "Float" || expected == "Double"
            case Value.VStr(_)    => expected == "String"
            // A char is an INTEGER that prints as one, so it answers to both — matching the
            // model the two lanes already share for `'x' + 1`.
            case Value.VChar(_)   => expected == "Char" || expected == "Int"
            case Value.VMap(_)    => expected == "Map"
            case Value.VSet(_)    => expected == "Set" || expected == "Iterable"
            case Value.VArr(_)    => expected == "Array"
            case _                => false
          prim && (arity < 0 || arity == 0)
      Value.VBool(yes)
    // `0 until n` / `1 to n`. The reference builds a cons LIST rather than a lazy range
    // (`v2/src/Runtime.scala:2978`), so `for i <- 0 until n` is an ordinary list walk on both
    // lanes and `.map`/`.filter` on the result behave as they do anywhere else.
    //
    // Only the two range names. `__arith__` is v2's whole binary-operator door and v3 reaches it
    // for nothing else — every other operator is a real `Bin` instruction here — so answering the
    // rest would be inventing a second arithmetic path that nothing uses and nothing tests.
    case "__arith__" =>
      val op = args.head match
        case Value.VStr(x) => x
        case v             => throw ExecError("__arith__ expects an operator name, got " + show(v))
      val lo = args(1) match
        case Value.VInt(x) => x
        case v             => throw ExecError("a range bound must be an Int, got " + show(v))
      val hi = args(2) match
        case Value.VInt(x) => x
        case v             => throw ExecError("a range bound must be an Int, got " + show(v))
      if op != "to" && op != "until" then
        throw ExecError("the operator '" + op + "' is not implemented by v3's executor")
      val last = if op == "to" then hi else hi - 1
      var xs: List[Value] = Nil
      var i = last
      while i >= lo do
        xs = Value.VInt(i) :: xs
        i = i - 1
      listIn(m, xs)
    case "__throw__" =>
      // The VALUE travels; the rendering is only for an uncaught throw's message.
      val v = if args.isEmpty then Value.VStr("throw") else args.head
      throw ExecThrow(v, showV(m, v))
    // A PROVIDER'S HOST FUNCTION, consulted only after every prim the kernel performs itself.
    // Kernel-first is deliberate: a plugin must not be able to redefine `io.println` or `f.sqrt`
    // by registering the same name, so the door opens onto what is left over rather than onto
    // everything. `Plugins` is a name -> function table in the kernel and nothing more; the
    // providers live outside `v3/src` — see that file for why the same names must also be
    // answerable on the bridge.
    case other =>
      Plugins.lookup(other) match
        case Some(fn) => fn(m, args)
        case None     => throw ExecError("unknown primitive '" + other + "'")
