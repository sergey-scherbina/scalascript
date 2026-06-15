package scalascript.interpreter

import scala.meta.*
import Computation.{Pure, FlatMap}

/** Pattern matching, for-comprehension evaluation, and collection iteration helpers. */
private[interpreter] object PatternRuntime:

  /** Compiled form of a single `Term.Match` case list.
   *  Each handler returns a `Computation` on success or `null` on no-match.
   *  The while-loop avoids `Option` allocations in the hot dispatch path. */
  /** Sentinel returned by a value-handler when its pattern matched but the body
   *  could not be folded to a bare Value at run time (e.g. a name resolved to a
   *  non-numeric value). It tells `runValue` to abandon the value path for the
   *  whole match — the caller then re-evaluates monadically, which is correct
   *  because no side effect has been observed on the value path. */
  private val NeedMonadic: AnyRef = new AnyRef

  /** Sentinel "no match" return for a `dhandler` (the raw-double arm). A NaN
   *  with payload `0xDEADBEEF` — distinguishable from any NaN user arithmetic
   *  can produce (HotSpot canonicalises NaNs from finite IEEE-754 ops to
   *  `0x7FF8000000000000L`). Carried as a `Double` constant so arms return
   *  without allocating; the dispatcher recovers the bit pattern via
   *  `doubleToRawLongBits` and compares against `NaNMissBits`. */
  private val NaNMissBits: Long = 0x7FFDEADBEEFDEADBL
  private val NaNMiss: Double   = java.lang.Double.longBitsToDouble(NaNMissBits)

  /** Sentinel "no match" return for an `lhandler` (the raw-long arm). Picked
   *  as `Long.MinValue` — the user's `+`/`*`/etc. on case-class `Int` fields
   *  almost never produce this exact value for realistic inputs. A pathological
   *  overflow that does produce it falls back to the monadic path for that
   *  one element (correctness preserved, perf taken just for that call). */
  private val LongMiss: Long = Long.MinValue

  final class CompiledMatch(
    private val handlers:  Array[(Value, Env) => Computation | Null],
    private val vhandlers: Array[(Value, Env) => AnyRef | Null] | Null,
    /** Raw-double parallel of vhandlers: each arm matches the same shape; on
     *  match the body's value is folded to a primitive `double` and returned;
     *  on no-match the handler returns `NaNMiss` (a sentinel NaN bit pattern
     *  the body's arithmetic cannot produce). Populated only when every arm
     *  body folds via `compileSlotD` (the pure double arithmetic that already
     *  runs unboxed inside `compileSlotBody` before its terminal
     *  `Value.doubleV` box). When non-null, `runValueDouble` exposes the
     *  unboxed result so callers can skip the `DoubleV` allocation. Throws
     *  `NotDouble` if a body cannot fold (env name not numeric); caller
     *  catches and falls back to monadic. */
    private val dhandlers: Array[(Value, Env) => Double] | Null,
    /** Raw-long parallel of vhandlers — mirrors dhandlers but for primitive
     *  `Long`-typed (a.k.a. `IntV.v`) arm bodies. Populated only when every
     *  arm body folds via `compileSlotI`. No-match returns `LongMiss`. Throws
     *  `NotDouble` (the shared slot-eval bail signal) when a body cannot fold
     *  (env name resolves to non-`IntV`). Null when any arm is outside the
     *  long-fold subset (e.g. arm body has a Double literal or a comparison). */
    private val lhandlers: Array[(Value, Env) => Long] | Null,
    /** Per-arm ctor type-name for `Pat.Extract` arms (null = catch-all / Var /
     *  Wildcard / Lit arm). Non-null only when at least one arm is a ctor
     *  pattern; null field in the array marks a catch-all.  Used by
     *  `runValueDouble` and `runValueLong` to replace the O(N)-handler-call
     *  linear scan with a cheap O(N) String-comparison scan — much cheaper
     *  because a String == on a 5-15 char name is ~3 ns vs a full lambda
     *  dispatch + InstanceV match + return-sentinel at ~20 ns per failed arm.
     *  Also used by `runValue` for the same reason. */
    private val ctorTags:  Array[String | Null] | Null,
    /** Int-tag parallel of `ctorTags` — populated when every non-null arm name
     *  is a registered ADT type (`interp.typeTagFor(name) > 0`). When non-null,
     *  the `runValue*` tag-scan compares `inst.typeTag` (single Int load) against
     *  these entries instead of doing a String equality probe; on a 5-arm match
     *  this drops the per-iter scan cost from ~25 ns to ~3 ns. Sentinel `-1`
     *  marks a catch-all entry (mirrors `ctorTags(i) == null`). The String
     *  array stays around for paths that still need the name (OptionV/NoneV
     *  arms whose ctor names aren't InstanceV-backed). */
    private val ctorTagsInt: Array[Int] | Null,
    val allSlot:           Boolean,
    /** Union of free names read across all value-handler bodies, or null if any
     *  arm had an unknown shape. Lets a caller verify a name (e.g. the function
     *  parameter) is never read before dropping its env frame. */
    val slotFreeNames:     Set[String] | Null
  ):
    /** True when every arm is guard-free with a pure (thunk-foldable) body and a
     *  fast-path pattern — so `runValue` can yield the result with no Computation
     *  allocation. Computed once at compile time. */
    def valueCapable: Boolean = vhandlers != null

    /** True when every arm folds to a pure double arithmetic expression
     *  (so `runValueDouble` is safe). Implies `valueCapable`. */
    def doubleCapable: Boolean = dhandlers != null

    /** True when every arm folds to a pure long arithmetic expression
     *  (so `runValueLong` is safe). Implies `valueCapable`. */
    def longCapable: Boolean = lhandlers != null

    def run(scrutV: Value, env: Env, interp: Interpreter): Computation =
      var i = 0
      while i < handlers.length do
        val r = handlers(i)(scrutV, env)
        if r != null then return r.asInstanceOf[Computation]
        i += 1
      interp.located(s"Match failure: ${Value.show(scrutV)}")

    /** Direct-style match: returns the arm's result Value with no `Pure`
     *  wrapper, or null to signal "fall back to monadic `run`" (no arm matched,
     *  or a matched body could not be value-folded). Only valid when
     *  `valueCapable`; callers must check first. */
    def runValue(scrutV: Value, env: Env): Value | Null =
      val vh = vhandlers
      if vh == null then return null
      // Fast path for InstanceV scrutinees when ctor tags are known.
      // Prefer the Int-tag scan when available; if the scrutinee's typeTag is
      // 0 (unregistered InstanceV, e.g. constructed without going through
      // StatRuntime) the Int scan misses and we fall through to the String
      // scan as a safety net so semantics never diverge.
      val cti = ctorTagsInt
      if cti != null then
        scrutV match
          case inst: Value.InstanceV if inst.typeTag != 0 =>
            val tag = inst.typeTag
            var i = 0
            while i < cti.length do
              val t = cti(i)
              if t == -1 then                  // catch-all arm
                val r = vh(i)(inst, env)
                return if r == null || (r eq NeedMonadic) then null else r.asInstanceOf[Value]
              if t == tag then                 // ctor match (Int compare)
                val r = vh(i)(inst, env)
                return if r == null || (r eq NeedMonadic) then null else r.asInstanceOf[Value]
              i += 1
            return null
          case _ =>
      val ct = ctorTags
      if ct != null then
        scrutV match
          case inst: Value.InstanceV =>
            val tag = inst.typeName
            var i = 0
            while i < ct.length do
              val t = ct(i)
              if t == null then               // catch-all arm
                val r = vh(i)(inst, env)
                return if r == null || (r eq NeedMonadic) then null else r.asInstanceOf[Value]
              if t == tag then               // ctor match
                val r = vh(i)(inst, env)
                return if r == null || (r eq NeedMonadic) then null else r.asInstanceOf[Value]
              i += 1
            return null
          case _ =>
      var i = 0
      while i < vh.length do
        val r = vh(i)(scrutV, env)
        if r != null then
          return if r eq NeedMonadic then null else r.asInstanceOf[Value]
        i += 1
      null

    /** Raw-double direct-style match. Returns the matched arm's body folded to
     *  a primitive `double`. Throws `NotDouble` (control-flow signal) when no
     *  arm matches, when a matched arm cannot fold to a double at runtime (a
     *  non-numeric free-name lookup), or when the match shape is not double
     *  -capable. Caller catches and falls back to the monadic path; semantics
     *  preserved because the double path has no observable side effects.
     *
     *  No-match dispatch uses the `NaNMiss` sentinel (a specific NaN bit
     *  pattern with payload `0xDEADBEEFL` — the user code's `+`/`*`/etc. cannot
     *  produce this pattern from finite operands; any NaN it does produce is
     *  the canonical `0x7FF8000000000000L`). */
    def runValueDouble(scrutV: Value, env: Env): Double =
      val dh = dhandlers
      if dh == null then throw NotDouble
      // Fast path: for InstanceV scrutinees, scan Int tags (a single Int load +
      // compare per arm; the JIT lowers this to a tight CPU loop) instead of
      // String.equals chains. Falls through to the String-tag scan when either
      // (a) the match has any ctor name that isn't a registered ADT type, or
      // (b) the scrutinee's `typeTag` is 0 (constructed without going through
      // StatRuntime). The final linear scan still catches non-InstanceV
      // scrutinees and ctor-less matches.
      val cti = ctorTagsInt
      if cti != null then
        scrutV match
          case inst: Value.InstanceV if inst.typeTag != 0 =>
            val tag = inst.typeTag
            var i = 0
            while i < cti.length do
              val t = cti(i)
              if t == -1 then return dh(i)(inst, env)  // catch-all arm
              if t == tag then return dh(i)(inst, env) // ctor match (Int compare)
              i += 1
            throw NotDouble
          case _ =>
      val ct = ctorTags
      if ct != null then
        scrutV match
          case inst: Value.InstanceV =>
            val tag = inst.typeName
            var i = 0
            while i < ct.length do
              val t = ct(i)
              if t == null then return dh(i)(inst, env)  // catch-all arm
              if t == tag  then return dh(i)(inst, env)  // ctor match
              i += 1
            throw NotDouble
          case _ =>
      // Linear scan fallback: non-InstanceV scrutinee or no ctor tags in match.
      var i = 0
      while i < dh.length do
        val r = dh(i)(scrutV, env)
        if java.lang.Double.doubleToRawLongBits(r) != NaNMissBits then return r
        i += 1
      throw NotDouble

    /** Raw-long direct-style match. Long-typed parallel of `runValueDouble`:
     *  returns the matched arm's body folded to a primitive `Long`. Throws
     *  `NotDouble` (the shared slot-eval bail signal) on no-match-or-bail.
     *  No-match dispatch uses `LongMiss = Long.MinValue` (see the constant
     *  for the rare-overflow edge-case note). */
    def runValueLong(scrutV: Value, env: Env): Long =
      val lh = lhandlers
      if lh == null then throw NotDouble
      val cti = ctorTagsInt
      if cti != null then
        scrutV match
          case inst: Value.InstanceV if inst.typeTag != 0 =>
            val tag = inst.typeTag
            var i = 0
            while i < cti.length do
              val t = cti(i)
              if t == -1 then return lh(i)(inst, env)  // catch-all arm
              if t == tag then return lh(i)(inst, env) // ctor match (Int compare)
              i += 1
            throw NotDouble
          case _ =>
      val ct = ctorTags
      if ct != null then
        scrutV match
          case inst: Value.InstanceV =>
            val tag = inst.typeName
            var i = 0
            while i < ct.length do
              val t = ct(i)
              if t == null then return lh(i)(inst, env)  // catch-all arm
              if t == tag  then return lh(i)(inst, env)  // ctor match
              i += 1
            throw NotDouble
          case _ =>
      var i = 0
      while i < lh.length do
        val r = lh(i)(scrutV, env)
        if r != LongMiss then return r
        i += 1
      throw NotDouble

  /** Compile a Term.Match into a cached handler array.
   *  Called once per match expression (keyed by AST node identity).
   *  Falls back to the generic matchPat for complex patterns. */
  def compileMatch(t: scala.meta.Term.Match, interp: Interpreter): CompiledMatch =
    val cases    = t.casesBlock.cases
    val handlers = cases.map(compileCase(_, interp)).toArray
    buildCompiled(handlers, cases, interp)

  /** Compile a Term.PartialFunction into a cached handler array (same machinery). */
  def compilePF(t: scala.meta.Term.PartialFunction, interp: Interpreter): CompiledMatch =
    val handlers = t.cases.map(compileCase(_, interp)).toArray
    buildCompiled(handlers, t.cases, interp)

  /** A value-handler plus whether it is fully frame-free (slot-based), the set
   *  of free names its body reads (names resolved via `env`, i.e. not pattern
   *  bindings), and an optional raw-double parallel handler. `freeNames == null`
   *  means "unknown shape" — treat conservatively. `dfn != null` means the body
   *  folds to a primitive `double` (see `compileSlotDoubleBody`); the parallel
   *  arm returns `NaNMiss` on no-match. */
  private final class VHandler(
    val fn:        (Value, Env) => AnyRef | Null,
    val dfn:       ((Value, Env) => Double) | Null,
    val lfn:       ((Value, Env) => Long)   | Null,
    val slot:      Boolean,
    val freeNames: Set[String] | Null
  )

  /** Extract the ctor type-name for a single case's pattern, or null when the
   *  pattern is not a simple `Pat.Extract` (wildcard, var, lit, alternative).
   *  Used to build `ctorTags` for O(N-string-compare) dispatch. */
  private def armCtorTag(c: Case): String | Null = c.pat match
    case Pat.Extract.After_4_6_0(fn, _) => fn match
      case Term.Name(n)                 => n
      case Term.Select(_, Term.Name(n)) => n
      case _                            => null
    case _ => null

  private def buildCompiled(
    handlers: Array[(Value, Env) => Computation | Null], cases: List[Case], interp: Interpreter
  ): CompiledMatch =
    val n  = cases.length
    val vh = new Array[(Value, Env) => AnyRef | Null](n)
    val dh = new Array[(Value, Env) => Double](n)
    val lh = new Array[(Value, Env) => Long](n)
    val ct  = new Array[String | Null](n)  // ctor tags for fast dispatch
    val cti = new Array[Int](n)             // parallel Int tags (0 = unregistered fallback)
    var allSlot   = true
    var allDouble = true
    var allLong   = true
    var hasCtorArm = false
    var allIntTagged = true     // every non-catch-all arm resolves to a registered Int tag
    var free: Set[String] | Null = Set.empty
    var rest = cases
    var i = 0
    while rest.nonEmpty do
      val c = rest.head
      val h = valueHandlerFor(c, interp)
      if h == null then return new CompiledMatch(handlers, null, null, null, null, null, false, null)
      vh(i) = h.fn
      if h.dfn != null then dh(i) = h.dfn else allDouble = false
      if h.lfn != null then lh(i) = h.lfn else allLong   = false
      allSlot &&= h.slot
      if free != null then
        if h.freeNames == null then free = null else free = free ++ h.freeNames
      val tag = armCtorTag(c)
      ct(i) = tag
      if tag != null then
        hasCtorArm = true
        // Resolve to a registered Int tag if the ctor name is an ADT type.
        // OptionV ("Some"/"None") and other non-InstanceV ctor names won't be
        // in `typeTagMap` — those arms keep `cti(i) = 0` and force allIntTagged
        // false, so the String-based scan stays the dispatch path.
        val it = interp.typeTagMap.getOrElse(tag, 0)
        if it == 0 then allIntTagged = false
        cti(i) = it
      else
        cti(i) = -1 // catch-all sentinel
      i += 1
      rest = rest.tail
    new CompiledMatch(
      handlers, vh,
      if allDouble then dh else null,
      if allLong   then lh else null,
      if hasCtorArm then ct else null,
      if hasCtorArm && allIntTagged then cti else null,
      allSlot, free
    )

  private def evalGuard(cond: Option[Term], env: Env, interp: Interpreter): Boolean =
    cond match
      case None    => true
      case Some(g) =>
        Computation.run(interp.eval(g, env)) match
          case Value.BoolV(b) => b
          case _              => false

  private def compileLit(lit: Lit): Value = lit match
    case Lit.Int(v)     => Value.intV(v.toLong)
    case Lit.Long(v)    => Value.intV(v)
    case Lit.String(v)  => Value.StringV(v)
    case Lit.Boolean(v) => Value.boolV(v)
    case Lit.Double(v)  => Value.doubleV(v.toString.toDouble)
    case Lit.Null()     => Value.NullV
    case _              => Value.NullV

  /** A compiled pure expression: maps an env to a Value, or null when the
   *  expression cannot be evaluated on the fast path at runtime (e.g. an
   *  undefined name or a non-numeric operand) — the caller then falls back
   *  to the full `interp.eval`. */
  private type ValThunk = Env => Value | Null

  private def isFastOp(op: String): Boolean = op match
    case "+" | "-" | "*" | "/" | "%" | "<" | ">" | "<=" | ">=" => true
    case _                                                     => false

  private def isArithOp(op: String): Boolean = op match
    case "+" | "-" | "*" | "/" | "%" => true
    case _                           => false

  // ── Primitive-double body compilation ──────────────────────────────────────
  // A case body that is pure arithmetic and contains a Double literal (e.g.
  // `3.14159 * r * r`, `0.5 * b * h`) is Double-typed in Scala — every Int
  // operand promotes — so we can fold the whole expression in unboxed `double`
  // space and box the result exactly once, eliding the intermediate `DoubleV`
  // (and its `Computation` threading) that the per-sub-expression value path
  // allocates. Bodies without a Double literal keep the Int/Value path so Int
  // arithmetic stays Int-typed.

  private sealed trait DExpr
  private final case class DConst(d: Double)                 extends DExpr
  private final case class DName(name: String)               extends DExpr
  private final case class DBin(op: Char, l: DExpr, r: DExpr) extends DExpr

  /** Stackless control signal: a name in a double-arithmetic body resolved to a
   *  non-numeric value (or was undefined) at run time. The thunk catches this
   *  and falls back to the full evaluator, so semantics are never altered.
   *  Visibility widened to `private[interpreter]` so that `EvalRuntime.LApply`
   *  (the raw-Long LExpr function call) can bail through the same control-flow
   *  signal when a function is reassigned mid-loop. */
  private[interpreter] object NotDouble extends scala.util.control.ControlThrowable

  private def containsDoubleLit(t: Term): Boolean = t match
    case Lit.Double(_) => true
    case Term.ApplyInfix.After_4_6_0(l, _, _, ac) if ac.values.lengthCompare(1) == 0 =>
      containsDoubleLit(l) || containsDoubleLit(ac.values.head)
    case _ => false

  /** Compile a pure arithmetic term into a `DExpr` tree, or null if any part is
   *  outside the supported subset (calls, blocks, comparisons, …). */
  private def compileDExpr(t: Term): DExpr | Null = t match
    case Lit.Double(v) => DConst(v.toString.toDouble)
    case Lit.Int(v)    => DConst(v.toDouble)
    case Lit.Long(v)   => DConst(v.toDouble)
    case tn: Term.Name => DName(tn.value)
    case Term.ApplyInfix.After_4_6_0(l, op, _, ac)
        if ac.values.lengthCompare(1) == 0 && isArithOp(op.value) =>
      val lf = compileDExpr(l)
      if lf == null then null
      else
        val rf = compileDExpr(ac.values.head)
        if rf == null then null else DBin(op.value.charAt(0), lf, rf)
    case _ => null

  private def evalD(e: DExpr, env: Env, interp: Interpreter): Double = e match
    case DConst(d)  => d
    case DName(name) =>
      val v  = env.getOrElse(name, null)
      val rv = if v != null then v else interp.globals.getOrElse(name, null)
      rv match
        case Value.DoubleV(d) => d
        case Value.IntV(n)    => n.toDouble
        case _                => throw NotDouble
    case DBin(op, l, r) =>
      val a = evalD(l, env, interp)
      val b = evalD(r, env, interp)
      op match
        case '+' => a + b
        case '-' => a - b
        case '*' => a * b
        case '/' => a / b
        case '%' => a % b
        case _   => throw NotDouble
    case _ => throw NotDouble   // slot nodes never reach the env-keyed evalD

  // ── Slot-aware pure body compilation (array-slot env) ───────────────────────
  // A pure, guard-free case body whose free variables are exactly the pattern
  // bindings (plus globals/closure) is compiled to take those bindings as direct
  // arguments — `v0`, `v1` in pattern field order — rather than reading them from
  // a heap-allocated `FrameMap`. This removes the per-binding frame allocation
  // (and, on the pure-call path, the function-param frame too): the JVM call
  // frame *is* the slot array. Free names fall back to `env` (the function
  // closure / globals). Returns null for anything outside the supported subset.

  /** Bound locals passed positionally (`v0`/`v1`, unused = null); free names via env. */
  private[interpreter] type SlotBody = (Value, Value, Env) => Value | Null

  /** Sentinel for the pure-body cache (EvalRuntime.pureCallValue): "tried to
   *  compile, body is not in the fast-path subset". A separate AnyRef so the
   *  IdentityHashMap distinguishes "not yet tried" (null) from "tried, bail". */
  private[interpreter] val PureBodyMiss: AnyRef = new AnyRef

  // Slot variants of DExpr for the unboxed-double fold. DSlot reads a bound local
  // by index; DFreeName reads a free name from env/globals.
  private final case class DSlot(idx: Int)        extends DExpr
  private final case class DFreeName(name: String) extends DExpr

  private def slotToD(v: Value | Null): Double = v match
    case Value.DoubleV(d) => d
    case Value.IntV(n)    => n.toDouble
    case _                => throw NotDouble

  private def compileSlotD(t: Term, n0: String | Null, n1: String | Null): DExpr | Null = t match
    case Lit.Double(v) => DConst(v.toString.toDouble)
    case Lit.Int(v)    => DConst(v.toDouble)
    case Lit.Long(v)   => DConst(v.toDouble)
    case tn: Term.Name =>
      val name = tn.value
      if name == n0 then DSlot(0)
      else if name == n1 then DSlot(1)
      else DFreeName(name)
    case Term.ApplyInfix.After_4_6_0(l, op, _, ac)
        if ac.values.lengthCompare(1) == 0 && isArithOp(op.value) =>
      val lf = compileSlotD(l, n0, n1)
      if lf == null then null
      else
        val rf = compileSlotD(ac.values.head, n0, n1)
        if rf == null then null else DBin(op.value.charAt(0), lf, rf)
    case _ => null

  private def evalSlotD(e: DExpr, v0: Value, v1: Value, env: Env, interp: Interpreter): Double = e match
    case DConst(d)       => d
    case DSlot(0)        => slotToD(v0)
    case DSlot(_)        => slotToD(v1)
    case DFreeName(name) =>
      val v  = env.getOrElse(name, null)
      slotToD(if v != null then v else interp.globals.getOrElse(name, null))
    case DBin(op, l, r) =>
      val a = evalSlotD(l, v0, v1, env, interp)
      val b = evalSlotD(r, v0, v1, env, interp)
      op match
        case '+' => a + b
        case '-' => a - b
        case '*' => a * b
        case '/' => a / b
        case '%' => a % b
        case _   => throw NotDouble
    case _ => throw NotDouble   // env-keyed DName never appears in the slot fold

  /** Value-returning slot compiler for the non-double subset: literals, slot/free
   *  name reads, and nested Int/Double/comparison infix. Returns null outside it. */
  private def compileSlotVal(t: Term, n0: String | Null, n1: String | Null, interp: Interpreter): SlotBody | Null =
    t match
      case lit: Lit =>
        val v = compileLit(lit)
        (_, _, _) => v
      case tn: Term.Name =>
        val name = tn.value
        if name == n0 then (v0, _, _) => v0
        else if name == n1 then (_, v1, _) => v1
        else
          (_, _, env) =>
            val v = env.getOrElse(name, null)
            if v != null then v else interp.globals.getOrElse(name, null)
      case Term.ApplyInfix.After_4_6_0(lhs, op, _, ac)
          if ac.values.lengthCompare(1) == 0 && isFastOp(op.value) =>
        val opStr = op.value
        val lf    = compileSlotVal(lhs, n0, n1, interp)
        if lf == null then null
        else
          val rf = compileSlotVal(ac.values.head, n0, n1, interp)
          if rf == null then null
          else
            (v0, v1, env) =>
              val lv = lf(v0, v1, env)
              if lv == null then null
              else
                val rv = rf(v0, v1, env)
                if rv == null then null else DispatchRuntime.numericFastValue(lv, opStr, rv)
      case _ => null

  /** Compile a pure body into a `SlotBody`, or null if outside the subset.
   *  `n0`/`n1` name the (≤2) bound locals in pattern field order. */
  private[interpreter] def compileSlotBody(term: Term, n0: String | Null, n1: String | Null, interp: Interpreter): SlotBody | Null =
    term match
      case ai @ Term.ApplyInfix.After_4_6_0(_, op, _, ac)
          if ac.values.lengthCompare(1) == 0 && isArithOp(op.value) && containsDoubleLit(ai) =>
        val de = compileSlotD(ai, n0, n1)
        if de == null then null
        else
          (v0, v1, env) =>
            try Value.doubleV(evalSlotD(de, v0, v1, env, interp))
            catch case NotDouble => null
      case _ => compileSlotVal(term, n0, n1, interp)

  /** Parallel to `compileSlotBody` returning the raw `double` result instead of
   *  boxing into `Value.doubleV`. Accepts arms whose body folds via
   *  `compileSlotD` regardless of whether a Double literal appears — the
   *  decision to read every name as a `double` (via `slotToD`) lives at the
   *  call site, which only invokes this when the caller statically expects a
   *  double result (e.g. an `acc + fn(s)` accumulator). Returns null when the
   *  body is outside the arith-fold subset. Throws `NotDouble` at run time if
   *  a free name resolves to a non-numeric value. */
  private def compileSlotDoubleBody(term: Term, n0: String | Null, n1: String | Null, interp: Interpreter): ((Value, Value, Env) => Double) | Null =
    val de = compileSlotD(term, n0, n1)
    if de == null then null
    else (v0, v1, env) => evalSlotD(de, v0, v1, env, interp)

  /** Parallel to `compileSlotDoubleBody` for raw `Long`-typed bodies. Reuses
   *  the `DExpr`/`DSlot`/`DFreeName` AST tree but evaluates in unboxed `Long`
   *  via `evalSlotI` (a name resolving to `DoubleV` throws `NotDouble` to bail
   *  — the Long path refuses to demote precision). Returns null when the body
   *  is outside the arith-fold subset OR when any nested literal is a Double
   *  (a Double literal mid-tree poisons Long-typing semantically: `x + 0.5`
   *  must stay Double). */
  private def compileSlotLongBody(term: Term, n0: String | Null, n1: String | Null, interp: Interpreter): ((Value, Value, Env) => Long) | Null =
    if containsDoubleLit(term) then null
    else
      val de = compileSlotD(term, n0, n1)
      if de == null then null
      else
        // Peephole for the canonical 2-slot binop body (`case Pair(a, b) => a OP b`)
        // — the most common shape on real arms. Bypasses the recursive evalSlotI
        // (1 DBin frame + 2 DSlot frames per iter) with a direct two-load + arith
        // closure. JFR-targeted: instanceFieldAccess's `Pair(a, b) => a + b`
        // had ~25% CPU split between evalSlotI / slotToL recursion.
        de match
          case DBin('+', DSlot(0), DSlot(1)) =>
            (v0, v1, _) => slotToL(v0) + slotToL(v1)
          case DBin('-', DSlot(0), DSlot(1)) =>
            (v0, v1, _) => slotToL(v0) - slotToL(v1)
          case DBin('*', DSlot(0), DSlot(1)) =>
            (v0, v1, _) => slotToL(v0) * slotToL(v1)
          case DBin('/', DSlot(0), DSlot(1)) =>
            (v0, v1, _) => slotToL(v0) / slotToL(v1)
          case DBin('%', DSlot(0), DSlot(1)) =>
            (v0, v1, _) => slotToL(v0) % slotToL(v1)
          // Single-slot identity (`case Num(n) => n`) — also common on the
          // recursiveEval-style Num/Add/Mul shape.
          case DSlot(0) =>
            (v0, _, _) => slotToL(v0)
          case DSlot(_) =>
            (_, v1, _) => slotToL(v1)
          case _ =>
            (v0, v1, env) => evalSlotI(de, v0, v1, env, interp)

  /** Raw-Long-arg variant of `compileSlotLongBody` for the `EvalRuntime.LApply`
   *  path: instead of taking the arg as a `Value` slot and re-boxing through
   *  `IntV`, the resulting fn takes a primitive `Long` directly. Used by
   *  `tryLongWhileAssign` to compile `f(x)` calls inside an unboxed-long
   *  while-loop body without per-call `IntV` allocation. Returns null when the
   *  body is outside the arith-fold subset; throws `NotDouble` at run time if
   *  a free name resolves to non-`IntV`. */
  private[interpreter] def compileSlotLongFn1(body: Term, paramName: String, interp: Interpreter): EvalRuntime.LongEnvFn1 | Null =
    if containsDoubleLit(body) then null
    else
      val de = compileSlotD(body, paramName, null)
      if de == null then null
      else
        new EvalRuntime.LongEnvFn1:
          def apply(arg: Long, env: Env): Long = evalSlotILongArg(de, arg, env, interp)

  /** 1-param Long-arg slot evaluator. The `DSlot(0)` case returns `arg`
   *  directly (no `Value` boxing); other slots bail (this is a 1-param-only
   *  evaluator). Free names still require an `IntV` lookup. */
  private def evalSlotILongArg(e: DExpr, arg: Long, env: Env, interp: Interpreter): Long = e match
    case DConst(d)       => d.toLong   // containsDoubleLit guard rules out Double lits
    case DSlot(0)        => arg
    case DSlot(_)        => throw NotDouble   // > 0 not supported in 1-param fn
    case DFreeName(name) =>
      val v  = env.getOrElse(name, null)
      slotToL(if v != null then v else interp.globals.getOrElse(name, null))
    case DBin(op, l, r) =>
      val a = evalSlotILongArg(l, arg, env, interp)
      val b = evalSlotILongArg(r, arg, env, interp)
      op match
        case '+' => a + b
        case '-' => a - b
        case '*' => a * b
        case '/' => a / b
        case '%' => a % b
        case _   => throw NotDouble
    case _ => throw NotDouble

  /** 2-param Long-arg variant of `compileSlotLongFn1`. Body's `n0` slot reads
   *  `arg0`, `n1` slot reads `arg1`. Used by `EvalRuntime.LApply2` to inline
   *  `g(x, y)` calls into an unboxed-Long while loop without boxing either
   *  arg or result. */
  private[interpreter] def compileSlotLongFn2(body: Term, n0: String, n1: String, interp: Interpreter): EvalRuntime.LongEnvFn2 | Null =
    if containsDoubleLit(body) then null
    else
      val de = compileSlotD(body, n0, n1)
      if de == null then null
      else
        new EvalRuntime.LongEnvFn2:
          def apply(a0: Long, a1: Long, env: Env): Long = evalSlotILongArg2(de, a0, a1, env, interp)

  /** 2-param Long-arg slot evaluator. `DSlot(0)` → arg0, `DSlot(1)` → arg1,
   *  free names go through `slotToL`. Higher slot indices bail. */
  private def evalSlotILongArg2(e: DExpr, a0: Long, a1: Long, env: Env, interp: Interpreter): Long = e match
    case DConst(d)       => d.toLong
    case DSlot(0)        => a0
    case DSlot(1)        => a1
    case DSlot(_)        => throw NotDouble
    case DFreeName(name) =>
      val v  = env.getOrElse(name, null)
      slotToL(if v != null then v else interp.globals.getOrElse(name, null))
    case DBin(op, l, r) =>
      val a = evalSlotILongArg2(l, a0, a1, env, interp)
      val b = evalSlotILongArg2(r, a0, a1, env, interp)
      op match
        case '+' => a + b
        case '-' => a - b
        case '*' => a * b
        case '/' => a / b
        case '%' => a % b
        case _   => throw NotDouble
    case _ => throw NotDouble

  /** Long-typed slot evaluator. Reuses the `DExpr` AST tree compiled by
   *  `compileSlotD`. Names resolve via `slotToL` which accepts only `IntV`
   *  (Double would lose precision — bail to monadic via `NotDouble`). */
  private def evalSlotI(e: DExpr, v0: Value, v1: Value, env: Env, interp: Interpreter): Long = e match
    case DConst(d)       =>
      // `compileSlotLongBody` guards `containsDoubleLit`, so any `DConst` here
      // came from an `Lit.Int`/`Lit.Long` (already an integral value cast to
      // double during compilation). Round-trip safely.
      d.toLong
    case DSlot(0)        => slotToL(v0)
    case DSlot(_)        => slotToL(v1)
    case DFreeName(name) =>
      val v  = env.getOrElse(name, null)
      slotToL(if v != null then v else interp.globals.getOrElse(name, null))
    case DBin(op, l, r) =>
      val a = evalSlotI(l, v0, v1, env, interp)
      val b = evalSlotI(r, v0, v1, env, interp)
      op match
        case '+' => a + b
        case '-' => a - b
        case '*' => a * b
        case '/' => a / b
        case '%' => a % b
        case _   => throw NotDouble
    case _ => throw NotDouble   // env-keyed DName never appears in the slot fold

  private def slotToL(v: Value | Null): Long = v match
    case Value.IntV(n)    => n
    case _                => throw NotDouble

  /** Free names (env-resolved, i.e. neither slot `n0`/`n1` nor literals) read by a
   *  slot body. Returns null for shapes outside the slot subset. Mirrors the
   *  structure `compileSlotBody`/`compileSlotVal`/`compileSlotD` accept so the two
   *  never disagree on what a body can reference. */
  private def slotFreeNames(t: Term, n0: String | Null, n1: String | Null): Set[String] | Null =
    t match
      case _: Lit => Set.empty
      case tn: Term.Name =>
        val nm = tn.value
        if nm == n0 || nm == n1 then Set.empty else Set(nm)
      case Term.ApplyInfix.After_4_6_0(lhs, op, _, ac)
          if ac.values.lengthCompare(1) == 0 && (isArithOp(op.value) || isFastOp(op.value)) =>
        val ls = slotFreeNames(lhs, n0, n1)
        if ls == null then null
        else
          val rs = slotFreeNames(ac.values.head, n0, n1)
          if rs == null then null else ls ++ rs
      case _ => null

  /** Compile a guard-free, side-effect-free case body into a `ValThunk`,
   *  eliminating per-call AST re-dispatch and Computation-monad threading.
   *  Handles the pure subset: literals, name lookups, and nested primitive
   *  Int/Double arithmetic & comparisons. Returns null for anything outside
   *  the subset (calls, blocks, ifs, …), so the body keeps using `interp.eval`. */
  private def compileExpr(term: Term, interp: Interpreter): ValThunk | Null = term match
    case lit: Lit =>
      val v = compileLit(lit)
      (_ => v)
    case tn: Term.Name =>
      val name = tn.value
      // Two getOrElse calls with `null` literal defaults rather than nesting the
      // globals lookup inside the env default: a by-name default holding a method
      // call (`interp.globals.getOrElse(...)`) allocates a Function0 capturing
      // `interp`+`name` on every lookup, even on the common env-hit path.
      (env =>
        val v = env.getOrElse(name, null)
        if v != null then v else interp.globals.getOrElse(name, null))
    case ai @ Term.ApplyInfix.After_4_6_0(_, op, _, argClause)
        if argClause.values.lengthCompare(1) == 0 && isArithOp(op.value)
           && containsDoubleLit(ai) && compileDExpr(ai) != null =>
      // Double-typed arithmetic body: fold in unboxed `double`, box once.
      val de = compileDExpr(ai).asInstanceOf[DExpr]
      (env =>
        try Value.doubleV(evalD(de, env, interp))
        catch case NotDouble => null)
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if argClause.values.lengthCompare(1) == 0 && isFastOp(op.value) =>
      val opStr = op.value
      val lf    = compileExpr(lhs, interp)
      if lf == null then null
      else
        val rf = compileExpr(argClause.values.head, interp)
        if rf == null then null
        else
          (env =>
            val lv = lf(env)
            if lv == null then null
            else
              val rv = rf(env)
              if rv == null then null
              else
                DispatchRuntime.numericFastValue(lv, opStr, rv))
    case _ => null

  /** Wrap a fast-path Value in a Pure, reusing the pooled Pure instances for
   *  small ints and booleans so the thunk path does not regress allocation
   *  versus the pooled `Computation.pureIntV` the full evaluator would hit. */
  private def pureOf(v: Value): Computation = v match
    case Value.IntV(n)  => Computation.pureIntV(n)
    case Value.BoolV(b) => Computation.pureBool(b)
    case _              => Pure(v)

  /** Build the pattern env from precomputed field order and binding names.
   *  When `arr` is non-null (populated by Phase 3 constructors when the
   *  `SSC_INSTANCEV_ARRAY` flag is on), field reads use positional
   *  `arr(i)` access instead of `fields.getOrElse(fo(i), null)` HashMap
   *  lookup. Returns null if any required field is missing. */
  private def buildPatEnv(
    fo:        Array[String],
    bindNames: Array[String | Null],
    fields:    Map[String, Value],
    arr:       Array[Value] | Null,
    env:       Env
  ): Env | Null =
    inline def at(i: Int): Value =
      if arr != null then arr(i)
      else                fields.getOrElse(fo(i), null)
    val n = fo.length
    n match
      case 0 => env
      case 1 =>
        val bname = bindNames(0)
        if bname == null then env
        else
          val v = at(0)
          if v == null then null else FrameMap.one(bname, v, env)
      case 2 =>
        val b0 = bindNames(0)
        val b1 = bindNames(1)
        if b0 == null && b1 == null then env
        else if b0 == null then
          val v1 = at(1)
          if v1 == null then null else FrameMap.one(b1, v1, env)
        else if b1 == null then
          val v0 = at(0)
          if v0 == null then null else FrameMap.one(b0, v0, env)
        else
          val v0 = at(0)
          val v1 = at(1)
          if v0 == null || v1 == null then null
          else FrameMap.two(b0, v0, b1, v1, env)
      case _ =>
        // General case: collect only non-wildcard bindings
        var bindCount = 0
        var i = 0
        while i < n do
          if bindNames(i) != null then bindCount += 1
          i += 1
        if bindCount == 0 then env
        else
          val names = new Array[String](bindCount)
          val vals  = new Array[Value](bindCount)
          var j = 0
          i = 0
          while i < n do
            val bname = bindNames(i)
            if bname != null then
              val v = at(i)
              if v == null then return null
              names(j) = bname
              vals(j)  = v
              j += 1
            i += 1
          FrameMap.of(names, vals, env)

  /** Compile a single Case into a fast handler.
   *  Simple patterns (Extract with Var/Wildcard args, Wildcard, Var, Lit) get
   *  fast-path compilation.  Complex patterns fall back to `matchPat`. */
  private def compileCase(c: Case, interp: Interpreter): (Value, Env) => Computation | Null =
    // Pre-compile a guard-free pure body once; runBody uses the thunk when it
    // applies and falls back to the full evaluator otherwise (incl. all guarded
    // cases, where bodyThunk is null).
    val bodyThunk: ValThunk | Null = if c.cond.isEmpty then compileExpr(c.body, interp) else null
    def runBody(e: Env): Computation =
      val bt = bodyThunk
      if bt != null then
        val v = bt(e)
        if v != null then pureOf(v) else interp.eval(c.body, e)
      else interp.eval(c.body, e)

    c.pat match

      case Pat.Wildcard() =>
        if c.cond.isEmpty then
          (_, env) => runBody(env)
        else
          (_, env) =>
            if evalGuard(c.cond, env, interp) then runBody(env)
            else null

      case Pat.Var(n) =>
        val name = n.value
        if c.cond.isEmpty then
          (scrutV, env) =>
            val patEnv = FrameMap.one(name, scrutV, env)
            runBody(patEnv)
        else
          (scrutV, env) =>
            val patEnv = FrameMap.one(name, scrutV, env)
            if evalGuard(c.cond, patEnv, interp) then runBody(patEnv)
            else null

      case lit: Lit =>
        val litV = compileLit(lit)
        if c.cond.isEmpty then
          (scrutV, env) =>
            if scrutV == litV then runBody(env) else null
        else
          (scrutV, env) =>
            if scrutV == litV && evalGuard(c.cond, env, interp) then runBody(env)
            else null

      case Pat.Extract.After_4_6_0(fn, argClause) =>
        val typeName: String | Null = fn match
          case Term.Name(n)                 => n
          case Term.Select(_, Term.Name(n)) => n
          case _                            => null
        val argPats = argClause.values.toArray
        val allSimple = argPats.forall(p => p.isInstanceOf[Pat.Var] || p.isInstanceOf[Pat.Wildcard])
        if typeName != null && allSimple then
          // Precompute binding names (null = wildcard, skip binding)
          val bindNames: Array[String | Null] = argPats.map {
            case Pat.Var(nn) => nn.value
            case _           => null
          }
          // Lazily populated field order on first successful match
          var fieldOrderCache: Array[String] = null
          val tn = typeName  // capture for closure
          val noGuard = c.cond.isEmpty
          (scrutV, env) =>
            scrutV match
              case ov: Value.OptionV if ov.inner != null && tn == "Some" && bindNames.length == 1 =>
                val v = ov.inner
                val bname = bindNames(0)
                val patEnv = if bname == null then env else FrameMap.one(bname, v, env)
                if noGuard || evalGuard(c.cond, patEnv, interp) then runBody(patEnv)
                else null
              case Value.NoneV if tn == "None" && bindNames.isEmpty =>
                if noGuard || evalGuard(c.cond, env, interp) then runBody(env)
                else null
              case inst: Value.InstanceV if inst.typeName == tn =>
                val fields = inst.fields
                if fieldOrderCache == null then
                  fieldOrderCache = interp.typeFieldOrder
                    .getOrElse(tn, fields.keys.toList)
                    .toArray
                val fo = fieldOrderCache
                if bindNames.length != fo.length then null
                else
                  val arr = inst.fieldsArr
                  val patEnv = buildPatEnv(fo, bindNames, fields, arr, env)
                  if patEnv == null then null
                  else if noGuard || evalGuard(c.cond, patEnv, interp) then runBody(patEnv)
                  else null
              case _ => null
        else
          fallbackCase(c, interp)

      case Pat.Alternative(lhs, rhs) =>
        // Compile each alternative; return first match
        val lhsH = compileCase(c.copy(pat = lhs), interp)
        val rhsH = compileCase(c.copy(pat = rhs), interp)
        (scrutV, env) =>
          val r = lhsH(scrutV, env)
          if r != null then r else rhsH(scrutV, env)

      case _ => fallbackCase(c, interp)

  /** Value-returning twin of a single arm's handler, or null when the arm is not
   *  value-capable (guarded, non-thunk body, or a pattern outside the fast set).
   *  Returns: a `Value` on match+fold, `NeedMonadic` on match-but-cannot-fold,
   *  or null on no-match (try the next arm). Mirrors `compileCase`'s pattern
   *  logic exactly so semantics never diverge. */
  private def valueHandlerFor(c: Case, interp: Interpreter): VHandler | Null =
    if c.cond.nonEmpty then return null
    c.pat match
      case Pat.Wildcard() =>
        val body = compileSlotBody(c.body, null, null, interp)
        if body == null then null
        else
          val fn: (Value, Env) => AnyRef | Null =
            (_, env) =>
              val v = body(null, null, env); if v != null then v else NeedMonadic
          val dbody = compileSlotDoubleBody(c.body, null, null, interp)
          val dfn: ((Value, Env) => Double) | Null =
            if dbody == null then null else (_, env) => dbody(null, null, env)
          val lbody = compileSlotLongBody(c.body, null, null, interp)
          val lfn: ((Value, Env) => Long) | Null =
            if lbody == null then null else (_, env) => lbody(null, null, env)
          new VHandler(fn, dfn, lfn, true, slotFreeNames(c.body, null, null))

      case Pat.Var(n) =>
        val name = n.value
        val body = compileSlotBody(c.body, name, null, interp)
        if body == null then null
        else
          val fn: (Value, Env) => AnyRef | Null =
            (scrutV, env) =>
              val v = body(scrutV, null, env); if v != null then v else NeedMonadic
          val dbody = compileSlotDoubleBody(c.body, name, null, interp)
          val dfn: ((Value, Env) => Double) | Null =
            if dbody == null then null else (scrutV, env) => dbody(scrutV, null, env)
          val lbody = compileSlotLongBody(c.body, name, null, interp)
          val lfn: ((Value, Env) => Long) | Null =
            if lbody == null then null else (scrutV, env) => lbody(scrutV, null, env)
          new VHandler(fn, dfn, lfn, true, slotFreeNames(c.body, name, null))

      case lit: Lit =>
        val litV = compileLit(lit)
        val body = compileSlotBody(c.body, null, null, interp)
        if body == null then null
        else
          val fn: (Value, Env) => AnyRef | Null =
            (scrutV, env) =>
              if scrutV == litV then
                val v = body(null, null, env); if v != null then v else NeedMonadic
              else null
          val dbody = compileSlotDoubleBody(c.body, null, null, interp)
          val dfn: ((Value, Env) => Double) | Null =
            if dbody == null then null
            else (scrutV, env) =>
              if scrutV == litV then dbody(null, null, env) else NaNMiss
          val lbody = compileSlotLongBody(c.body, null, null, interp)
          val lfn: ((Value, Env) => Long) | Null =
            if lbody == null then null
            else (scrutV, env) =>
              if scrutV == litV then lbody(null, null, env) else LongMiss
          new VHandler(fn, dfn, lfn, true, slotFreeNames(c.body, null, null))

      case Pat.Extract.After_4_6_0(fn0, argClause) =>
        val typeName: String | Null = fn0 match
          case Term.Name(n)                 => n
          case Term.Select(_, Term.Name(n)) => n
          case _                            => null
        val argPats   = argClause.values.toArray
        val allSimple = argPats.forall(p => p.isInstanceOf[Pat.Var] || p.isInstanceOf[Pat.Wildcard])
        if typeName == null || !allSimple then null
        else
          val bindNames: Array[String | Null] = argPats.map {
            case Pat.Var(nn) => nn.value
            case _           => null
          }
          // Field positions of the actual (non-wildcard) bindings, in field order.
          val bindPos = bindNames.indices.filter(bindNames(_) != null).toArray
          if bindPos.length <= 2 then
            // Slot path: pass the (≤2) bound field values directly as v0/v1; no
            // pattern FrameMap is built. n0/n1 name those slots for the body.
            val p0 = if bindPos.length >= 1 then bindPos(0) else -1
            val p1 = if bindPos.length >= 2 then bindPos(1) else -1
            val n0 = if p0 >= 0 then bindNames(p0) else null
            val n1 = if p1 >= 0 then bindNames(p1) else null
            val body = compileSlotBody(c.body, n0, n1, interp)
            if body == null then null
            else
              var fieldOrderCache: Array[String] = null
              val tn = typeName
              val fn: (Value, Env) => AnyRef | Null =
                (scrutV, env) =>
                  scrutV match
                    case ov: Value.OptionV if ov.inner != null && tn == "Some" && bindNames.length == 1 =>
                      val v0 = if p0 == 0 then ov.inner else null
                      val v = body(v0, null, env); if v != null then v else NeedMonadic
                    case Value.NoneV if tn == "None" && bindNames.isEmpty =>
                      val v = body(null, null, env); if v != null then v else NeedMonadic
                    case inst: Value.InstanceV if inst.typeName == tn =>
                      val fields = inst.fields
                      if fieldOrderCache == null then
                        fieldOrderCache = interp.typeFieldOrder
                          .getOrElse(tn, fields.keys.toList)
                          .toArray
                      val fo = fieldOrderCache
                      if bindNames.length != fo.length then null
                      else
                        // Direct fieldsArr access when the array repr is
                        // populated (default since Direction B activation);
                        // fall back to fields.getOrElse otherwise.
                        val arr = inst.fieldsArr
                        val v0 =
                          if p0 < 0 then null
                          else if arr != null then arr(p0)
                          else fields.getOrElse(fo(p0), null)
                        val v1 =
                          if p1 < 0 then null
                          else if arr != null then arr(p1)
                          else fields.getOrElse(fo(p1), null)
                        // A bound field missing from the instance means no match
                        // (parity with buildPatEnv), not a fold failure.
                        if (p0 >= 0 && v0 == null) || (p1 >= 0 && v1 == null) then null
                        else
                          val v = body(v0, v1, env); if v != null then v else NeedMonadic
                    case _ => null
              val dbody = compileSlotDoubleBody(c.body, n0, n1, interp)
              val dfn: ((Value, Env) => Double) | Null =
                if dbody == null then null
                else
                  // Mirror the fn match shape exactly so semantics stay aligned.
                  // The fieldOrderCache is per-VHandler — share via closure.
                  val tn2 = tn
                  val p0c = p0
                  val p1c = p1
                  val bnLen = bindNames.length
                  (scrutV, env) =>
                    scrutV match
                      case ov: Value.OptionV if ov.inner != null && tn2 == "Some" && bnLen == 1 =>
                        val v0 = if p0c == 0 then ov.inner else null
                        dbody(v0, null, env)
                      case Value.NoneV if tn2 == "None" && bnLen == 0 =>
                        dbody(null, null, env)
                      case inst: Value.InstanceV if inst.typeName == tn2 =>
                        val fields = inst.fields
                        if fieldOrderCache == null then
                          fieldOrderCache = interp.typeFieldOrder
                            .getOrElse(tn2, fields.keys.toList)
                            .toArray
                        val fo = fieldOrderCache
                        if bnLen != fo.length then NaNMiss
                        else
                          val arr = inst.fieldsArr
                          val v0 =
                            if p0c < 0 then null
                            else if arr != null then arr(p0c)
                            else fields.getOrElse(fo(p0c), null)
                          val v1 =
                            if p1c < 0 then null
                            else if arr != null then arr(p1c)
                            else fields.getOrElse(fo(p1c), null)
                          if (p0c >= 0 && v0 == null) || (p1c >= 0 && v1 == null) then NaNMiss
                          else dbody(v0, v1, env)
                      case _ => NaNMiss
              val lbody = compileSlotLongBody(c.body, n0, n1, interp)
              val lfn: ((Value, Env) => Long) | Null =
                if lbody == null then null
                else
                  // Parallel field-order cache for the long path (separate
                  // closure from dfn so the two never alias state).
                  var lFieldOrderCache: Array[String] = null
                  val tn3 = tn
                  // Pre-resolve the Int tag once; 0 marks "unregistered name"
                  // (OptionV "Some"/"None" or any non-ADT ctor) — the Int
                  // short-circuit then always misses and the String compare
                  // path stays correct. JFR-targeted: dropping the per-iter
                  // String.equals from the hot arm-closure guard.
                  val tn3Tag = interp.typeTagMap.getOrElse(tn3, 0)
                  val p0d = p0
                  val p1d = p1
                  val bnLen = bindNames.length
                  (scrutV, env) =>
                    scrutV match
                      case ov: Value.OptionV if ov.inner != null && tn3 == "Some" && bnLen == 1 =>
                        val v0 = if p0d == 0 then ov.inner else null
                        lbody(v0, null, env)
                      case Value.NoneV if tn3 == "None" && bnLen == 0 =>
                        lbody(null, null, env)
                      case inst: Value.InstanceV if (tn3Tag != 0 && inst.typeTag == tn3Tag) || inst.typeName == tn3 =>
                        val fields = inst.fields
                        if lFieldOrderCache == null then
                          lFieldOrderCache = interp.typeFieldOrder
                            .getOrElse(tn3, fields.keys.toList)
                            .toArray
                        val fo = lFieldOrderCache
                        if bnLen != fo.length then LongMiss
                        else
                          val arr = inst.fieldsArr
                          val v0 =
                            if p0d < 0 then null
                            else if arr != null then arr(p0d)
                            else fields.getOrElse(fo(p0d), null)
                          val v1 =
                            if p1d < 0 then null
                            else if arr != null then arr(p1d)
                            else fields.getOrElse(fo(p1d), null)
                          if (p0d >= 0 && v0 == null) || (p1d >= 0 && v1 == null) then LongMiss
                          else lbody(v0, v1, env)
                      case _ => LongMiss
              new VHandler(fn, dfn, lfn, true, slotFreeNames(c.body, n0, n1))
          else
            frameValueHandler(c, typeName, bindNames, interp)

      case Pat.Alternative(lhs, rhs) =>
        val lh = valueHandlerFor(c.copy(pat = lhs), interp)
        val rh = valueHandlerFor(c.copy(pat = rhs), interp)
        if lh == null || rh == null then null
        else
          val lf = lh.fn; val rf = rh.fn
          val fn: (Value, Env) => AnyRef | Null =
            (scrutV, env) =>
              val r = lf(scrutV, env); if r != null then r else rf(scrutV, env)
          val ldfn = lh.dfn; val rdfn = rh.dfn
          val dfn: ((Value, Env) => Double) | Null =
            if ldfn == null || rdfn == null then null
            else
              (scrutV, env) =>
                val r = ldfn(scrutV, env)
                if java.lang.Double.doubleToRawLongBits(r) == NaNMissBits then rdfn(scrutV, env)
                else r
          val llfn = lh.lfn; val rlfn = rh.lfn
          val lfn: ((Value, Env) => Long) | Null =
            if llfn == null || rlfn == null then null
            else
              (scrutV, env) =>
                val r = llfn(scrutV, env)
                if r == LongMiss then rlfn(scrutV, env) else r
          val fns =
            if lh.freeNames == null || rh.freeNames == null then null
            else lh.freeNames ++ rh.freeNames
          new VHandler(fn, dfn, lfn, lh.slot && rh.slot, fns)

      case _ => null

  /** Value-handler for an Extract pattern with >2 bindings: cannot use the ≤2
   *  slot machinery, so it builds a pattern FrameMap (env-keyed `compileExpr`
   *  body). `slot = false` so the caller keeps the function-param frame. */
  private def frameValueHandler(
    c: Case, typeName: String, bindNames: Array[String | Null], interp: Interpreter
  ): VHandler | Null =
    val bodyThunk = compileExpr(c.body, interp)
    if bodyThunk == null then null
    else
      val bt = bodyThunk
      var fieldOrderCache: Array[String] = null
      val tn = typeName
      val fn: (Value, Env) => AnyRef | Null =
        (scrutV, env) =>
          scrutV match
            case inst: Value.InstanceV if inst.typeName == tn =>
              val fields = inst.fields
              if fieldOrderCache == null then
                fieldOrderCache = interp.typeFieldOrder
                  .getOrElse(tn, fields.keys.toList)
                  .toArray
              val fo = fieldOrderCache
              if bindNames.length != fo.length then null
              else
                val arr = inst.fieldsArr
                val patEnv = buildPatEnv(fo, bindNames, fields, arr, env)
                if patEnv == null then null
                else { val v = bt(patEnv); if v != null then v else NeedMonadic }
            case _ => null
      // >2 binding patterns require a pattern FrameMap (not slot-based), which
      // the raw-double/long paths cannot serve without re-introducing the
      // boxing they exist to avoid. Keep dfn/lfn = null so callers fall back
      // to the monadic path for these arms — the compileSlot*Body guards are
      // the contract surface, not the env shape.
      new VHandler(fn, null, null, false, null)

  private def fallbackCase(c: Case, interp: Interpreter): (Value, Env) => Computation | Null =
    if c.cond.isEmpty then
      (scrutV, env) =>
        val patEnv = matchPat(c.pat, scrutV, env, interp)
        if patEnv != null then interp.eval(c.body, patEnv) else null
    else
      (scrutV, env) =>
        val patEnv = matchPat(c.pat, scrutV, env, interp)
        if patEnv != null && evalGuard(c.cond, patEnv, interp) then interp.eval(c.body, patEnv)
        else null

  /** JVM exception supertypes a `catch` pattern may use to match a synthesized
   *  exception InstanceV (whose `typeName` is the thrown throwable's simple name). */
  private def isExceptionSupertype(typeName: String): Boolean =
    typeName == "Throwable" || typeName == "Exception" ||
      typeName == "RuntimeException" || typeName == "Error"

  /** Match pattern against scrutinee. Returns the extended env on success, null on failure.
   *  Uses null instead of Option to avoid allocating Some wrappers on the hot match path. */
  def matchPat(pat: Pat, scrutinee: Value, env: Env, interp: Interpreter): Env | Null = pat match
    case Pat.Wildcard()  => env
    case Pat.Var(name)   => FrameMap.one(name.value, scrutinee, env)
    case lit: Lit =>
      val litV: Value = lit match
        case Lit.Int(v)     => Value.intV(v.toLong)
        case Lit.Long(v)    => Value.intV(v)
        case Lit.String(v)  => Value.StringV(v)
        case Lit.Boolean(v) => Value.boolV(v)
        case Lit.Double(v)  => Value.doubleV(v.toString.toDouble)
        case Lit.Null()     => Value.NullV
        case _              => Value.NullV
      if litV == scrutinee then env else null
    case Pat.Tuple(pats) =>
      scrutinee match
        case Value.TupleV(elems) if elems.length == pats.length =>
          var curEnv: Env | Null = env; var ps = pats; var es = elems
          while curEnv != null && ps.nonEmpty do
            curEnv = matchPat(ps.head, es.head, curEnv.asInstanceOf[Env], interp)
            ps = ps.tail; es = es.tail
          curEnv
        case _ => null
    case Pat.Extract.After_4_6_0(fn, argClause) =>
      val typeName: String | Null = fn match
        case Term.Name(n)                 => n
        case Term.Select(_, Term.Name(n)) => n
        case _                            => null
      if typeName == null then null
      else
        val args = argClause.values
        scrutinee match
          case inst: Value.InstanceV if inst.typeName == typeName =>
            val arr   = inst.fieldsArr
            val order = interp.typeFieldOrder.getOrElse(inst.typeName,
              if arr != null then Nil else inst.fields.keys.toList)
            if args.length != order.length then null
            else
              var curEnv: Env | Null = env; var as = args; var os = order; var oi = 0
              while curEnv != null && as.nonEmpty do
                val fv: Value | Null =
                  if arr != null then arr(oi)
                  else inst.fields.getOrElse(os.head, null)
                curEnv = if fv == null then null
                         else matchPat(as.head, fv, curEnv.asInstanceOf[Env], interp)
                as = as.tail; os = os.tail; oi += 1
              curEnv
          case ov: Value.OptionV if ov.inner != null && typeName == "Some" && args.length == 1 =>
            matchPat(args.head, ov.inner, env, interp)
          case Value.NoneV if typeName == "None" && args.isEmpty =>
            env
          case _ => null
    // List cons pattern: `head :: tail` matches a non-empty ListV.
    case Pat.ExtractInfix.After_4_6_0(headPat, Term.Name("::"), tailClause) =>
      scrutinee match
        case Value.ListV(h :: t) if tailClause.values.length == 1 =>
          val e = matchPat(headPat, h, env, interp)
          if e != null then matchPat(tailClause.values.head, Value.ListV(t), e.asInstanceOf[Env], interp) else null
        case _ => null
    case Pat.Typed(inner, tpe) =>
      val typeName = tpe match
        case Type.Name(n)   => n
        case ta: Type.Apply => ta.tpe match { case Type.Name(n) => n; case _ => "" }
        case _              => ""
      // `Any` / `AnyRef` are universal supertypes — match any value. Required so
      // `try … catch { case e: Any => … }` actually fires: the catch scrutinee is
      // a synthesized exception InstanceV carrying the JVM exception's simple name,
      // which never equals "Any". (busi: try/catch not catching extern throws.)
      if typeName.isEmpty || typeName == "Any" || typeName == "AnyRef" then
        matchPat(inner, scrutinee, env, interp)
      else
        val matches = scrutinee match
          case Value.InstanceV(t, _) =>
            // Exception supertypes match any InstanceV — a `catch` scrutinee is a
            // synthesized exception (any JVM throwable's simple name), and users
            // catch it by supertype (Throwable / Exception / RuntimeException /
            // Error) without knowing the concrete JVM class.
            t == typeName || isExceptionSupertype(typeName) || {
              var p = interp.parentTypes.getOrElse(t, null); var ok = false
              while p != null && !ok do { ok = p == typeName; p = interp.parentTypes.getOrElse(p, null) }
              ok
            }
          // IntV represents both Int and Long (stored as JVM Long internally).
          case _: Value.IntV    => typeName == "Int" || typeName == "Long"
          case _: Value.DoubleV => typeName == "Double" || typeName == "Float" || typeName == "Number"
          case _: Value.StringV => typeName == "String"
          case _: Value.BoolV   => typeName == "Boolean"
          case _: Value.CharV   => typeName == "Char"
          case _: Value.ListV   => typeName == "List"
          case _: Value.OptionV => typeName == "Option"
          case _: Value.MapV    => typeName == "Map"
          case _                => false
        if matches then matchPat(inner, scrutinee, env, interp) else null
    case Pat.Alternative(lhs, rhs) =>
      val l = matchPat(lhs, scrutinee, env, interp)
      if l != null then l else matchPat(rhs, scrutinee, env, interp)
    // @ binder: `xs @ pattern` — bind `xs` to the whole scrutinee, then match `pattern`
    case Pat.Bind(lhs: Pat.Var, rhs) =>
      val e = matchPat(rhs, scrutinee, env, interp)
      if e != null then FrameMap.one(lhs.name.value, scrutinee, e.asInstanceOf[Env]) else null
    case t: Term.Name =>
      val v = env.getOrElse(t.value, interp.globals.getOrElse(t.value, null))
      if v != null && v == scrutinee then env else null
    case Term.Select(qual, Term.Name(n)) =>
      // Evaluate the qualifier to support imported enum singletons like `Role.User`.
      // For `case Role.User =>`, look up `Role` then access its `User` field.
      // Fall back to the bare-name lookup for unknown or non-InstanceV qualifiers.
      val recv: Value | Null = qual match
        case Term.Name(qn) => env.getOrElse(qn, interp.globals.getOrElse(qn, null))
        case _             => null
      val v: Value | Null = recv match
        case inst: Value.InstanceV =>
          val arr = inst.fieldsArr
          if arr != null then
            val fo = interp.typeFieldOrder.getOrElse(inst.typeName, Nil)
            val idx = fo.indexOf(n)
            if idx >= 0 && idx < arr.length then arr(idx) else null
          else inst.fields.getOrElse(n, null)
        case _                     => env.getOrElse(n, interp.globals.getOrElse(n, null))
      if v != null && v == scrutinee then env else null
    case _ => null

  def patVarNames(pat: Pat): Set[String] = pat match
    case Pat.Var(n)           => Set(n.value)
    case Pat.Wildcard()       => Set.empty
    case Pat.Tuple(pats)      => pats.flatMap(patVarNames).toSet
    case Pat.Extract.After_4_6_0(_, argClause) => argClause.values.flatMap(patVarNames).toSet
    case Pat.Typed(inner, _)  => patVarNames(inner)
    case Pat.Bind(lhs: Pat.Var, rhs) => patVarNames(rhs) + lhs.name.value
    case _                    => Set.empty

  def evalCollection(v: Value, interp: Interpreter): List[Value] = v match
    case Value.ListV(ls)        => ls
    case ov: Value.OptionV => if ov.inner != null then ov.inner :: Nil else Nil
    case _ => interp.located(s"Cannot iterate over ${Value.show(v)}")

  /** Monad-polymorphic `for`-comprehension: a generator over a NON-`List` monad (Option / Either /
   *  Set / Map …) desugars to `recv.flatMap(pat => <rest>)` (or `recv.map(pat => body)` for the last
   *  generator) dispatched on the actual value — so `for x <- Some(3); y <- Some(4) yield x+y` yields
   *  `Some(7)`, not a `List`. (interp-monadic-forcomp.) Only used for irrefutable patterns + an all-
   *  simple-generator tail; `List` keeps its allocation-light fast path. */
  private def forCompIrrefutable(p: Pat): Boolean = p match
    case _: scala.meta.Pat.Var | _: scala.meta.Pat.Wildcard => true
    case _                                                   => false
  private def forCompRestSimple(es: List[Enumerator]): Boolean = es.forall {
    case Enumerator.Generator(p, _) => forCompIrrefutable(p)
    case _                          => false
  }
  private def monadicForYield(mv: Value, pat: Pat, rest: List[Enumerator], body: Term,
                              env: Env, interp: Interpreter): Computation =
    val method = if rest.isEmpty then "map" else "flatMap"
    val fn = Value.NativeFnV("__forComp", {
      case arg :: _ =>
        val patEnv = matchPat(pat, arg, env, interp)
        val e2 = if patEnv == null then env else patEnv.asInstanceOf[Env]
        if rest.isEmpty then interp.eval(body, e2) else evalForYield(rest, body, e2, interp)
      case _ => Computation.PureUnit
    })
    DispatchRuntime.dispatch1(mv, method, fn, env, interp)

  def evalForYield(enums: List[Enumerator], body: Term, env: Env, interp: Interpreter): Computation =
    enums match
      case Nil => interp.eval(body, env)
      // Fast path: `for x <- items yield expr` — avoids N Option/Some allocations and
      // intermediate List[Computation] from the general branches+sequence approach.
      case Enumerator.Generator(pv @ scala.meta.Pat.Var(vn), rhs) :: Nil =>
        val varName = vn.value
        def go(rhsV: Value): Computation =
          // Non-List monad (Option/Either/…) → `recv.map(x => body)`; List keeps the fast path.
          if rhsV.isInstanceOf[Value.ListV] then
            Computation.mapSequence(evalCollection(rhsV, interp),
              item => interp.eval(body, FrameMap.one(varName, item, env)))
          else monadicForYield(rhsV, pv, Nil, body, env, interp)
        interp.eval(rhs, env) match
          case Pure(rhsV) => go(rhsV)
          case rhsC       => FlatMap(rhsC, go)
      case Enumerator.Generator(pat, rhs) :: rest =>
        // Non-List monad with an irrefutable pattern + all-simple tail → `recv.flatMap(pat => <rest>)`.
        def goGen(rhsV: Value): Computation =
          if !rhsV.isInstanceOf[Value.ListV] && forCompIrrefutable(pat) && forCompRestSimple(rest) then
            monadicForYield(rhsV, pat, rest, body, env, interp)
          else genBody(rhsV)
        @inline def genBody(rhsV: Value): Computation =
          val items  = evalCollection(rhsV, interp)
          val isLast = rest.isEmpty
          val branches = items.flatMap { item =>
            val patEnv = matchPat(pat, item, env, interp)
            if patEnv == null then Nil else evalForYield(rest, body, patEnv, interp) :: Nil
          }
          Computation.sequence(branches).map {
            case Value.ListV(results) if isLast =>
              Value.ListV(results)
            case Value.ListV(results) =>
              Value.ListV(results.flatMap {
                case Value.ListV(ls) => ls
                case v               => v :: Nil
              })
            case other => other
          }
        interp.eval(rhs, env) match
          case Pure(rhsV) => goGen(rhsV)
          case rhsC       => FlatMap(rhsC, goGen)
      case Enumerator.Guard(cond) :: rest =>
        interp.eval(cond, env) match
          case Pure(Value.BoolV(true)) => evalForYield(rest, body, env, interp)
          case Pure(_)                 => Computation.PureEmptyList
          case c => FlatMap(c, {
            case Value.BoolV(true) => evalForYield(rest, body, env, interp)
            case _                 => Computation.PureEmptyList
          })
      case Enumerator.Val(pat, rhs) :: rest =>
        @inline def valBody(v: Value): Computation =
          val patEnv = matchPat(pat, v, env, interp)
          if patEnv == null then Computation.PureEmptyList
          else evalForYield(rest, body, patEnv, interp)
        interp.eval(rhs, env) match
          case Pure(v) => valBody(v)
          case rhsC    => FlatMap(rhsC, valBody)
      case _ :: rest => evalForYield(rest, body, env, interp)

  // evalForDo keeps loop vars separate so assignments to outer vars are visible.
  // `interp.eval` already checks interp.globals as fallback for Term.Name lookups,
  // so we do not need to merge globals into env here.
  // `afterIter` runs after each loop-body iteration; the for-do eval in EvalRuntime
  // uses it to push body-assigned enclosing vars (which land in interp.globals) back
  // into the caller's mutable local view so subsequent reads see the update.
  def evalForDo(enums: List[Enumerator], body: Term, outerEnv: Env, loopVars: Env, interp: Interpreter,
                afterIter: () => Unit = () => ()): Computation =
    val env = if loopVars.isEmpty then outerEnv else outerEnv ++ loopVars
    enums match
      case Nil =>
        interp.eval(body, env) match
          case Pure(_) => afterIter(); Computation.PureUnit
          case c       => FlatMap(c, { v => afterIter(); Computation.discardToUnit(v) })
      // Fast path: `for x <- items do body` (single Pat.Var generator, no guards).
      // Avoids matchPat Option/Some, patVarNames Set, and loopVars Map per iteration.
      case Enumerator.Generator(scala.meta.Pat.Var(vn), rhs) :: Nil =>
        val varName = vn.value
        @inline def doLoop(rhsV: Value): Computation =
          val items = evalCollection(rhsV, interp)
          def forDoLoop(remaining: List[Value]): Computation = remaining match
            case Nil => Computation.PureUnit
            case item :: tail =>
              FlatMap(interp.eval(body, FrameMap.one(varName, item, env)),
                _ => { afterIter(); forDoLoop(tail) })
          forDoLoop(items)
        interp.eval(rhs, env) match
          case Pure(rhsV) => doLoop(rhsV)
          case rhsC       => FlatMap(rhsC, doLoop)
      // Fast path: single generator — avoids patVarNames Set + newVars Map + recursive evalForDo per item.
      // patEnv from matchPat already extends `env` with the pattern bindings, so it's
      // equivalent to the outerEnv ++ loopVars ++ newVars that the general path would build.
      case Enumerator.Generator(pat, rhs) :: Nil =>
        @inline def patLoop(rhsV: Value): Computation =
          val items = evalCollection(rhsV, interp)
          def forDoLoop(remaining: List[Value]): Computation = remaining match
            case Nil => Computation.PureUnit
            case item :: tail =>
              val patEnv = matchPat(pat, item, env, interp)
              if patEnv == null then forDoLoop(tail)
              else FlatMap(interp.eval(body, patEnv), _ => { afterIter(); forDoLoop(tail) })
          forDoLoop(items)
        interp.eval(rhs, env) match
          case Pure(rhsV) => patLoop(rhsV)
          case rhsC       => FlatMap(rhsC, patLoop)
      case Enumerator.Generator(pat, rhs) :: rest =>
        @inline def genLoop(rhsV: Value): Computation =
          val items = evalCollection(rhsV, interp)
          def loop(remaining: List[Value]): Computation = remaining match
            case Nil => Computation.PureUnit
            case item :: tail =>
              val patEnv = matchPat(pat, item, env, interp)
              if patEnv == null then loop(tail)
              else
                val newVars = patVarNames(pat).map(k => k -> patEnv(k)).toMap
                FlatMap(evalForDo(rest, body, outerEnv, loopVars ++ newVars, interp, afterIter), _ => loop(tail))
          loop(items)
        interp.eval(rhs, env) match
          case Pure(rhsV) => genLoop(rhsV)
          case rhsC       => FlatMap(rhsC, genLoop)
      case Enumerator.Guard(cond) :: rest =>
        interp.eval(cond, env) match
          case Pure(Value.BoolV(true)) => evalForDo(rest, body, outerEnv, loopVars, interp, afterIter)
          case Pure(_)                 => Computation.PureUnit
          case c => FlatMap(c, {
            case Value.BoolV(true) => evalForDo(rest, body, outerEnv, loopVars, interp, afterIter)
            case _                 => Computation.PureUnit
          })
      case Enumerator.Val(pat, rhs) :: rest =>
        @inline def valBind(v: Value): Computation =
          val patEnv = matchPat(pat, v, env, interp)
          if patEnv == null then Computation.PureUnit
          else
            val newVars = patVarNames(pat).map(k => k -> patEnv(k)).toMap
            evalForDo(rest, body, outerEnv, loopVars ++ newVars, interp, afterIter)
        interp.eval(rhs, env) match
          case Pure(v) => valBind(v)
          case rhsC    => FlatMap(rhsC, valBind)
      case _ :: rest => evalForDo(rest, body, outerEnv, loopVars, interp, afterIter)
