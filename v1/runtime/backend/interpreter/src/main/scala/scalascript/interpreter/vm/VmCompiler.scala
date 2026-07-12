package scalascript.interpreter.vm

import scala.meta.*
import scala.collection.mutable
import scalascript.interpreter.Value
import SscVm.*
import java.lang as jl

/** Compiles a `Value.FunV` whose params are all integer-typed and whose body
 *  is in the supported subset (see specs/vm-jit-spec.md §5) into a [[CompiledFn]].
 *  Returns `None` on any unsupported construct — the caller then falls back to
 *  the tree-walking interpreter, so this can never change semantics.
 *
 *  Supports self-recursion (compiled to a loop in tail position) and calls to
 *  *other* integer functions, including mutual recursion. Callees are resolved
 *  through a caller-supplied `resolve` function and compiled on demand into the
 *  same [[Ctx]], so a cyclic call graph terminates (each function compiled once).
 */
object VmCompiler:

  // Short alias for typed bail reasons (Stage 8: typed `[vm]` miss stats).
  private val Br = jit.JitBailReason

  /** Max supported arity. The VM `CALL` copies `arity` registers; the only cost
   *  of a higher cap is slightly larger frames. */
  final val MaxArity = 8

  private final class Bail(val reason: String, val typed: jit.JitBailReason | Null = null)
    extends RuntimeException(null, null, true, false) // no stack trace — control flow only

  /** Static type of a register's contents. The VM stack is `Long`-typed; for a
   *  `TDouble` register the bits are an IEEE-754 double (see SscVm F* opcodes).
   *  Booleans are represented as `TInt` (0/1), matching the int comparison ops. */
  private enum VmType:
    case TInt, TDouble, TRef
  import VmType.*

  private val intTypes    = Set("Int", "Long", "Boolean", "")
  private val doubleTypes = Set("Double", "Float")

  /** A name resolver: given the function currently being compiled and a free
   *  name appearing in its body, return the `FunV` that name refers to (a
   *  sibling/top-level function), or `null` if it is not a compilable function
   *  reference. Self-references are handled by name-match and do NOT go through
   *  this resolver, so it is only consulted for calls to *other* functions. */
  type Resolve = (Value.FunV, String) => Value.FunV | Null

  val noResolve: Resolve = (_, _) => null

  /** ADT constructor metadata: given a constructor name, return its field
   *  names + declared field type strings (parallel lists), or null if the name
   *  is not a known case class/enum case. Drives `match` field extraction. */
  type Meta = String => (List[String], List[String]) | Null

  private val noMeta: Meta = _ => null

  /** wide-jit C-3: per-node static types from the Typer, identity-keyed on the ORIGINAL
   *  scala.meta trees the interpreter runs (the C-2 `compileAstModule` path preserves that
   *  identity — the `Defn.Def` body node the Typer recorded is `eq` to `FunV.body`). Returns
   *  `null` when no type was inferred for a node (partial by design: signature-level, so
   *  closures/some locals are absent). */
  type TypeMap = scala.meta.Tree => scalascript.typer.SType | Null

  private val noTypeMap: TypeMap = _ => null

  /** Measurement (behaviour-neutral): how often a compiled expression node carries a concrete
   *  numeric static type from the TypeMap. Confirms the identity-key hits end-to-end and sizes
   *  the C-4 "remove type-unknown bail" opportunity. Cumulative across compilations. */
  val typeMapSeen = new java.util.concurrent.atomic.AtomicLong(0L)
  val typeMapHits = new java.util.concurrent.atomic.AtomicLong(0L)
  /** wide-jit C-3 consumption: call-result types recovered from the map where the syntactic
    * heuristic gave up (TInt-default → TDouble/TRef). Each one is a call the JIT would otherwise
    * mistype/bail on. */
  val callResultUpgrades = new java.util.concurrent.atomic.AtomicLong(0L)
  /** wide-jit C-4c: Int-typed RET leaves widened to Double because the function's DECLARED return
    * type says Double. Each one is a `MixedReturnType` bail avoided. */
  val retDoubleWidenings = new java.util.concurrent.atomic.AtomicLong(0L)
  /** wide-jit C-5: value-position `if`/match branches with mixed {Int, Double} types, widened
    * locally (Scala's lub widens Int→Double) instead of bailing MixedReturnType. */
  val branchWidenings = new java.util.concurrent.atomic.AtomicLong(0L)
  /** wide-jit C-6: an Int value assigned to a Double var, widened (I2D) rather than bailing on a
    * false "var domain change". */
  val varWidenings = new java.util.concurrent.atomic.AtomicLong(0L)
  /** Opt-in (zero overhead otherwise): count TypeMap coverage at each compiled node. Read the
    * counters directly (tests / future C-4 opportunity sizing). */
  val measureTypes: Boolean =
    sys.env.contains("SSC_JIT_TYPESTATS") || sys.props.contains("ssc.jit.typestats")

  /** Self-recursion only (no sibling calls). Used by tests/bench. */
  def compile(fn: Value.FunV): Option[CompiledFn] = compile(fn, noResolve, noMeta)

  /** Compile `fn`, resolving calls to other functions via `resolve`. */
  def compile(fn: Value.FunV, resolve: Resolve): Option[CompiledFn] = compile(fn, resolve, noMeta)

  /** Compile `fn`, resolving sibling calls via `resolve` and ADT constructors
   *  (for `match` field extraction) via `meta`. */
  def compile(fn: Value.FunV, resolve: Resolve, meta: Meta): Option[CompiledFn] =
    compile(fn, resolve, meta, noTypeMap)

  /** Compile `fn` with, additionally, a `typeMap` of static per-node types (wide-jit C-3). */
  def compile(fn: Value.FunV, resolve: Resolve, meta: Meta, typeMap: TypeMap): Option[CompiledFn] =
    try Some(new Ctx(resolve, meta, typeMap).compileFn(fn))
    catch case b: Bail =>
      if b.typed != null then JitMissStats.record("vm", b.typed)
      else                    JitMissStats.record(b.reason)
      None

  private def bail(reason: String): Nothing = throw new Bail(reason)
  private def bail(reason: String, typed: jit.JitBailReason): Nothing =
    throw new Bail(reason, typed)

  private def isFunType(tpe: String): Boolean = tpe.contains("=>")
  private def funArity(tpe: String): Int =
    val arrow = tpe.indexOf("=>")
    if arrow <= 0 then 1
    else
      val before = tpe.substring(0, arrow).trim
      if before.startsWith("(") then before.count(_ == ',') + 1 else 1

  /** Pretty class name for scala.meta AST nodes: strips `_After_*` version
   *  suffixes and `Impl` endings, inserts a dot after the first capitalised
   *  component (`TermSelect` → `Term.Select`, `LitString` → `Lit.String`). */
  private def termName(t: AnyRef): String =
    t.getClass.getSimpleName
      .replaceAll("_After.*|Impl$", "")
      .replaceFirst("^(Lit|Term|Pat|Defn|Decl|Type|Stat|Import|Mod|Name|Pkg)([A-Z])", "$1.$2")

  // A param whose declared type is neither numeric nor a known ADT-bearing ref
  // is still allowed through the gate: it becomes a TRef register usable only by
  // match/field/call opcodes (ISTAG safely returns 0 for a non-InstanceV, GETF
  // throws → fallback), so an unsupported use bails inside the Builder instead.
  private def typeGateOk(fn: Value.FunV): Boolean =
    fn.usingParams.isEmpty && !fn.defaults.exists(_.isDefined)

  // jit-foldleft: compile `recv.foldLeft(z)((a,b) => body)` into an inline VM loop.
  // ON by default — a function containing a `List[Int].foldLeft` used to bail the
  // WHOLE function to tree-walk; now it compiles (the loop AND the surrounding
  // code). Kill-switch: SSC_JIT_FOLDLEFT=0 (env) or -Dssc.jit.foldleft=0 (prop;
  // also lets differential tests toggle it in process). Statically gated to
  // `List[Int]` receivers so the IntV unbox can never misfire (runVm does not catch
  // arbitrary throws, and a blanket fallback would risk double effects). A `def` so
  // tests see property changes; compilation is infrequent (once per FunV) so the
  // re-read cost is irrelevant.
  private def foldLeftJit: Boolean =
    sys.props.get("ssc.jit.foldleft").orElse(sys.env.get("SSC_JIT_FOLDLEFT"))
      .forall(v => v != "0" && v != "false")

  /** Observability (tests): number of foldLeft loops successfully compiled. Lets a
   *  differential test confirm the JIT path was actually exercised, not vacuously
   *  matched because `f` stayed tree-walked. */
  val foldLeftCompileCount = new java.util.concurrent.atomic.AtomicLong(0L)

  // ── shared compilation context (handles cyclic call graphs) ──────
  private final class Ctx(resolve: Resolve, meta: Meta, typeMap: TypeMap = noTypeMap):
    /** wide-jit C-3: the static `VmType` of a node from the Typer's map, or `null` if the map
      * has no (or a non-scalar) type for it. Mirrors `fieldVmType` but keyed on real `SType`. */
    def vmTypeOf(t: scala.meta.Tree): VmType | Null =
      typeMap(t) match
        case null => null
        case st   => sTypeToVm(st)

    private def sTypeToVm(st: scalascript.typer.SType): VmType | Null =
      import scalascript.typer.SType
      st match
        case SType.Named("Double", _) | SType.Named("Float", _)                        => TDouble
        case SType.Named("Int", _) | SType.Named("Long", _) | SType.Named("Boolean", _) => TInt
        case SType.Named(_, _)                                                          => TRef
        case _                                                                         => null // Var/Function/… → unknown
    // Shells registered BEFORE their callPool is filled, so a cycle (f→g→f)
    // resolves to the in-progress shell instead of recompiling forever.
    private val building = new java.util.IdentityHashMap[Value.FunV, CompiledFn]()

    def compileFn(fn: Value.FunV): CompiledFn =
      val existing = building.get(fn)
      if existing != null then existing
      else
        if !typeGateOk(fn) then bail("typeGate: using or default params", Br.UsingParams)
        // RET is Long-typed; a Boolean-returning body yields 0/1. The JIT bridge
        // (JitRuntime.wrap) reads retIsBool to box that raw Long as BoolV rather
        // than IntV — without it, `true`/`false` would surface as `1`/`0`.
        val b = new Builder(fn, this)
        b.buildInstructions()
        val ops = b.opArr
        val hasCallRef = { var found = false; var i = 0; while i < ops.length do { if ops(i) == CALLREF then found = true; i += 1 }; found }
        val shell = new CompiledFn(
          name = fn.name, arity = b.arityOf, numRegs = b.maxRegOf,
          op = ops, a = b.aArr, b = b.bArr, c = b.cArr,
          constPool = b.constArr, callPool = new Array[CompiledFn](b.callees.length),
          retIsDouble = b.retIsDoubleOf, paramIsDouble = b.paramIsDoubleOf,
          paramIsRef = b.paramIsRefOf, strPool = b.strArr,
          retIsBool = jit.JitPredicates.isBoolReturning(fn.body),
          retIsRef = b.retIsRefOf,
          funVPool = b.funVArr,
          callRefCache = if hasCallRef then new Array[AnyRef](ops.length * SscVm.icStride) else Array.empty,
          icHead       = if hasCallRef then new Array[Byte](ops.length) else Array.empty
        )
        building.put(fn, shell)            // register before filling — breaks cycles
        var i = 0
        while i < b.callees.length do
          shell.callPool(i) = compileFn(b.callees(i))
          i += 1
        shell

    def resolveName(owner: Value.FunV, name: String): Value.FunV | Null =
      resolve(owner, name)

    def metaFor(ctor: String): (List[String], List[String]) | Null =
      meta(ctor)

  // ── per-function compilation state ───────────────────────────────
  private final class Builder(fn: Value.FunV, ctx: Ctx):
    private val ops   = mutable.ArrayBuffer.empty[Int]
    private val as    = mutable.ArrayBuffer.empty[Int]
    private val bs    = mutable.ArrayBuffer.empty[Int]
    private val cs    = mutable.ArrayBuffer.empty[Int]
    private val consts = mutable.ArrayBuffer.empty[Long]
    private val strs   = mutable.ArrayBuffer.empty[String]
    private val locals = mutable.HashMap.empty[String, Int]
    private var nextReg = 0
    private var maxReg  = 0

    // Static type of each register's contents. Drives int-vs-double opcode
    // selection and int→double promotion. Defaults to TInt for any register
    // not explicitly recorded (the all-integer path needs no entries).
    private val regType = mutable.HashMap.empty[Int, VmType]
    private def typeOf(r: Int): VmType = regType.getOrElse(r, TInt)
    private def setType(r: Int, t: VmType): Unit =
      if t == TInt then regType.remove(r) else regType(r) = t

    // Declared ADT type name for TRef registers (e.g. "Vec", "Node").
    // Populated for params and match-bound fields; used by Term.Select to
    // look up field types via ctx.metaFor without knowing the ctor at compile time.
    private val refTypeName = mutable.HashMap.empty[Int, String]
    private def setRefType(r: Int, name: String): Unit = if name.nonEmpty then refTypeName(r) = name

    // Inner `def` names compiled during `compileStmt(Defn.Def(...))`.
    // Checked in `callTarget` so calls to local defs resolve to compiled callees.
    private val innerDefs = mutable.HashMap.empty[String, Value.FunV]

    /** Map a declared field/param type string to a register domain. Anything
     *  that is not numeric is a ref (an InstanceV, threaded through the ref bank). */
    private def fieldVmType(tpe: String): VmType =
      val tt = tpe.trim
      if doubleTypes.contains(tt) then TDouble
      else if intTypes.contains(tt) then TInt
      else TRef

    // Whether this function operates in the double domain. Decided up-front by a
    // syntactic scan (a Double/Float param, or a double literal anywhere in the
    // body). Used to type self-recursive call results, which would otherwise be
    // circular. The actual return type is re-derived from the RET leaves and must
    // agree (see buildInstructions) — so a misclassification bails, never miswraps.
    /** wide-jit C-4c: does the function's DECLARED return annotation say Double/Float? This is the
      * AUTHORITATIVE signal `fnIsDouble` (a syntactic body scan) and the Typer's inferred body type
      * (Any for mixed branches) both lack. Used to widen Int RET leaves to Double (Scala widens
      * Int→Double on every return path) rather than bailing on a false "mixed return". Empty when the
      * def carried no annotation / the FunV came from a site that doesn't populate it ⇒ unchanged. */
    private val declaredDouble: Boolean = doubleTypes.contains(fn.declaredReturnType.trim)

    private val fnIsDouble: Boolean =
      // wide-jit C-4d: the DECLARED return type is authoritative and completes the syntactic scan.
      // Without it, a declared-Double fn whose body has no double param/literal (fnIsDouble=false)
      // types its own self-recursive call result as TInt (the `callee eq fn => fnIsDouble` case
      // below) — so a non-tail self-call reads the callee's double return bits as an int and the
      // arithmetic is garbage. Honouring the declaration types the self-call TDouble → correct.
      val paramDouble = fn.paramTypes.exists(t => doubleTypes.contains(t.trim))
      declaredDouble || paramDouble || fn.body.collect {
        case _: Lit.Double => ()
      }.nonEmpty

    // Unified type of every value reaching a RET/RETREF. None until first leaf.
    private var retType: Option[VmType] = None
    private def unifyRet(t: VmType): Unit =
      retType match
        case None             => retType = Some(t)
        case Some(prev) if prev == t => ()
        case Some(TRef)       => bail("ret: mixed ref/numeric returns", Br.MixedReturnType)
        case Some(_) if t == TRef => bail("ret: mixed ref/numeric returns", Br.MixedReturnType)
        case _                => bail("ret: mixed Int/Double returns", Br.MixedReturnType)
    def retIsDoubleOf: Boolean = retType.contains(TDouble)
    def retIsRefOf:    Boolean = retType.contains(TRef)

    // Callees referenced by CALL, in slot order; deduped by identity.
    val callees = mutable.ArrayBuffer.empty[Value.FunV]
    private val calleeSlot = new java.util.IdentityHashMap[Value.FunV, Integer]()

    def arityOf: Int  = fn.params.length
    def maxRegOf: Int = maxReg
    def paramIsDoubleOf: Array[Boolean] = Array.tabulate(fn.params.length)(i => typeOf(i) == TDouble)
    def paramIsRefOf: Array[Boolean]    = Array.tabulate(fn.params.length)(i => typeOf(i) == TRef)
    def opArr: Array[Int]    = ops.toArray
    def aArr: Array[Int]     = as.toArray
    def bArr: Array[Int]     = bs.toArray
    def cArr: Array[Int]     = cs.toArray
    def constArr: Array[Long]    = consts.toArray
    def strArr: Array[String]    = strs.toArray

    // Non-capturing FunV constants for LOADFV (Stage 3.3).
    private val funvs = mutable.ArrayBuffer.empty[Value.FunV]
    def funVArr: Array[Value.FunV] = funvs.toArray

    private def slotFor(callee: Value.FunV): Int =
      val s = calleeSlot.get(callee)
      if s != null then s.intValue
      else
        val n = callees.length
        callees += callee
        calleeSlot.put(callee, Integer.valueOf(n))
        n

    private def freshReg(): Int =
      val r = nextReg; nextReg += 1
      if nextReg > maxReg then maxReg = nextReg
      r

    private def freshRegs(n: Int): Int =
      val base = nextReg; nextReg += n
      if nextReg > maxReg then maxReg = nextReg
      base

    private def constSlot(v: Long): Int =
      var i = 0
      while i < consts.length do { if consts(i) == v then return i; i += 1 }
      consts += v; consts.length - 1

    private def strSlot(s: String): Int =
      var i = 0
      while i < strs.length do { if strs(i) == s then return i; i += 1 }
      strs += s; strs.length - 1

    private def emit(op: Int, a: Int, b: Int, c: Int): Int =
      ops += op; as += a; bs += b; cs += c; ops.length - 1

    private def opcodeFor(op: String): Int = op match
      case "+"  => ADD; case "-"  => SUB; case "*" => MUL
      case "/"  => DIV; case "%"  => MOD
      case "<"  => LT;  case "<=" => LE; case ">" => GT; case ">=" => GE
      case "==" => EQ;  case "!=" => NE
      case op   => bail(s"unsupported: infix operator '$op'")

    /** Double-domain opcode for `op`. Comparisons reuse the F* compare ops, which
     *  still write a 0/1 boolean into an (int-typed) register. */
    private def fopcodeFor(op: String): Int = op match
      case "+"  => FADD; case "-"  => FSUB; case "*" => FMUL
      case "/"  => FDIV; case "%"  => FMOD
      case "<"  => FLT;  case "<=" => FLE; case ">" => FGT; case ">=" => FGE
      case "==" => FEQ;  case "!=" => FNE
      case op   => bail(s"unsupported: float infix operator '$op'")

    private def isCmp(op: String): Boolean = op match
      case "<" | "<=" | ">" | ">=" | "==" | "!=" => true
      case _                                     => false

    /** Return a register holding `r` as double bits: `r` itself if already
     *  double-typed, else a fresh register with an I2D promotion emitted. */
    private def asDouble(r: Int): Int =
      if typeOf(r) == TDouble then r
      else
        val d = freshReg(); emit(I2D, d, r, 0); setType(d, TDouble); d

    /** Emit `lr op rr` into `dst`, choosing int or double opcodes by operand
     *  type and promoting the int side on a mixed pair. Returns the result type
     *  (TInt for any comparison or all-int arithmetic; TDouble otherwise). */
    private def emitArith(op: String, dst: Int, lr: Int, rr: Int): VmType =
      if typeOf(lr) == TRef || typeOf(rr) == TRef then
        if typeOf(lr) != TRef || typeOf(rr) != TRef then bail("ref: cannot mix ref and numeric", Br.ApplyInfixRefOp)
        op match
          case "==" => emit(EQREF, dst, lr, rr); setType(dst, TInt); TInt
          case "!=" => emit(NEREF, dst, lr, rr); setType(dst, TInt); TInt
          case _    => bail(s"ref: unsupported operator '$op' on ref types")
      else if typeOf(lr) == TDouble || typeOf(rr) == TDouble then
        emit(fopcodeFor(op), dst, asDouble(lr), asDouble(rr))
        val rt = if isCmp(op) then TInt else TDouble
        setType(dst, rt); rt
      else
        emit(opcodeFor(op), dst, lr, rr)
        setType(dst, TInt); TInt

    /** Immediate-RHS opcode for `op` (operand `c` is a constPool index). */
    private def opcodeImmFor(op: String): Int = op match
      case "+"  => ADDI; case "-"  => SUBI; case "*" => MULI
      case "/"  => DIVI; case "%"  => MODI
      case "<"  => LTI;  case "<=" => LEI; case ">" => GTI; case ">=" => GEI
      case "==" => EQI;  case "!=" => NEI
      case op   => bail(s"unsupported: immediate infix operator '$op'")

    /** The literal `Long` value of `t` if it is an integer literal, else None. */
    private def intLiteral(t: Term): Option[Long] = t match
      case Lit.Int(v)  => Some(v.toLong)
      case Lit.Long(v) => Some(v)
      case _           => None

    /** Const-prop Stage 1: evaluate a pure int-op on two literal Longs at compile time.
     *  Returns None for ops whose result isn't a Long (none today) or undefined cases
     *  (div/mod by zero — keep runtime semantics: bail to interp). */
    private def foldIntInt(op: String, a: Long, b: Long): Option[Long] = op match
      case "+"  => Some(a + b)
      case "-"  => Some(a - b)
      case "*"  => Some(a * b)
      case "/"  => if b == 0L then None else Some(a / b)
      case "%"  => if b == 0L then None else Some(a % b)
      case "<"  => Some(if a <  b then 1L else 0L)
      case "<=" => Some(if a <= b then 1L else 0L)
      case ">"  => Some(if a >  b then 1L else 0L)
      case ">=" => Some(if a >= b then 1L else 0L)
      case "==" => Some(if a == b then 1L else 0L)
      case "!=" => Some(if a != b then 1L else 0L)
      case _    => None

    /** Resolve `app.fun` to a compilable callee, or None.
     *  Order mirrors the interpreter: a local/param of that name shadows any
     *  function (and a local holds a Long, not something callable → bail);
     *  then the function's own name (self); then a sibling via the resolver. */
    private def callTarget(app: Term.Apply): Option[Value.FunV] =
      app.fun match
        case n: Term.Name =>
          val nm = n.value
          if locals.contains(nm) then None
          else if fn.name.nonEmpty && nm == fn.name then Some(fn)
          else innerDefs.get(nm).orElse(
            ctx.resolveName(fn, nm) match
              case f: Value.FunV => Some(f)
              case null          => None
          )
        case _ => None

    // Compile an expression, returning the register holding its Long result.
    // A bare name reuses its existing register (no copy); everything else is
    // emitted straight into a fresh register via destination-passing.
    private def compileExpr(t: Term): Int = t match
      case n: Term.Name => locals.getOrElse(n.value, bail(s"undefined: name '${n.value}'"))
      case _            => val r = freshReg(); compileInto(t, r); r

    /** Whether `callee` operates in the double domain — same syntactic scan as
     *  [[fnIsDouble]]. For a self-call this is the function's own classification. */
    private def calleeIsDouble(callee: Value.FunV): Boolean =
      if callee eq fn then fnIsDouble
      else callee.paramTypes.exists(t => doubleTypes.contains(t.trim)) ||
           callee.body.collect { case _: Lit.Double => () }.nonEmpty

    private def calleeParamType(callee: Value.FunV, i: Int): VmType =
      if i < callee.paramTypes.length then fieldVmType(callee.paramTypes(i)) else TInt

    // busi seq-74 — does `callee` return a reference (String / collection /
    // object) rather than a numeric long? Used to type a CALL result TRef.
    // `JitPredicates.isRefReturning` recognises the direct ref-producing forms;
    // for a body that just delegates to another user function (`def f(x) = g(x)`),
    // we resolve and recurse so transitive delegation chains classify correctly.
    // Bounded by a visited set + depth cap; conservative (unknown ⇒ false, so a
    // numeric callee is never mis-typed as ref).
    private def calleeReturnsRef(callee: Value.FunV): Boolean =
      calleeReturnsRefRec(callee, new java.util.IdentityHashMap[Value.FunV, java.lang.Boolean](), 0)

    private def calleeReturnsRefRec(
        callee:  Value.FunV,
        visited: java.util.IdentityHashMap[Value.FunV, java.lang.Boolean],
        depth:   Int
    ): Boolean =
      if depth > 8 || visited.containsKey(callee) then false
      else
        visited.put(callee, java.lang.Boolean.TRUE)
        if jit.JitPredicates.isRefReturning(callee.body) then true
        else tailCallName(callee.body) match
          case Some(name) =>
            ctx.resolveName(callee, name) match
              case f: Value.FunV => calleeReturnsRefRec(f, visited, depth + 1)
              case null          => false
          case None => false

    // The name of the function called in tail position of `t`, if the tail is a
    // bare `name(args)` call (walking through if/block/match tails). Used to
    // follow a delegation chain when classifying a callee's return type.
    private def tailCallName(t: Term): Option[String] = t match
      case ap: Term.Apply =>
        ap.fun match
          case Term.Name(n) => Some(n)
          case _            => None
      case ti: Term.If =>
        tailCallName(ti.thenp).orElse(tailCallName(ti.elsep))
      case tb: Term.Block =>
        tb.stats.lastOption match
          case Some(last: Term) => tailCallName(last)
          case _                => None
      case _ => None

    // Slice A (jit-foldleft): recognise `recv.foldLeft(z)((a,b) => body)` and emit
    // an inline VM loop (no CALLREF — the lambda body is compiled straight into the
    // accumulator). Returns the result type if compiled, or `null` if the term is
    // not a 2-arg-lambda foldLeft (caller falls through to the normal path). Once
    // the shape is confirmed, unsupported sub-cases `bail` so the WHOLE function
    // falls back to the tree-walker (no partial/half-compiled state survives a Bail).
    private def tryCompileFoldLeft(app: Term.Apply, dst: Int): VmType | Null =
      if !foldLeftJit then return null
      val lambda = app.argClause.values match
        case List(f: Term.Function) => f
        case _                      => return null
      val (recv, z) = app.fun match
        case inner: Term.Apply =>
          inner.fun match
            case Term.Select(r, m: Term.Name) if m.value == "foldLeft" =>
              inner.argClause.values match
                case List(zz) => (r, zz)
                case _        => return null
            case _ => return null
        case _ => return null
      val lps = lambda.paramClause.values
      if lps.length != 2 then return null
      // Shape confirmed → committed to compiling this as a foldLeft loop.
      val xsReg = compileExpr(recv)
      if typeOf(xsReg) != TRef then bail("foldLeft: non-ref receiver", Br.VmUnsupportedTerm)
      // Statically require List[Int] so LITERNXI's IntV unbox can never misfire.
      if refTypeName.getOrElse(xsReg, "") != "List[Int]" then
        bail("foldLeft: receiver not statically List[Int] (Slice A)", Br.VmUnsupportedTerm)
      val accReg = freshReg()
      if compileInto(z, accReg) != TInt then bail("foldLeft: non-Int accumulator (Slice A)", Br.VmUnsupportedTerm)
      val cursor = freshReg()
      emit(LITERINIT, cursor, xsReg, 0); setType(cursor, TRef)
      val loopStart = ops.length
      val hn = freshReg()
      emit(LITERHN, hn, cursor, 0)
      val jf = emit(JF, hn, -1, 0)
      val elem = freshReg()
      emit(LITERNXI, elem, cursor, 0); setType(elem, TInt)
      val aName = lps(0).name.value; val bName = lps(1).name.value
      val savedA = locals.get(aName); val savedB = locals.get(bName)
      locals(aName) = accReg; locals(bName) = elem
      val res = freshReg()
      val bt = compileInto(lambda.body, res)
      savedA match { case Some(r) => locals(aName) = r; case None => locals.remove(aName) }
      savedB match { case Some(r) => locals(bName) = r; case None => locals.remove(bName) }
      if bt != TInt then bail("foldLeft: non-Int lambda body (Slice A)", Br.VmUnsupportedTerm)
      emit(MOVE, accReg, res, 0)
      emit(JMP, loopStart, 0, 0)
      bs(jf) = ops.length
      emit(MOVE, dst, accReg, 0); setType(dst, TInt)
      foldLeftCompileCount.incrementAndGet()
      TInt

    // Compile `t`, emitting its result directly into register `dst`, and return
    // the static type written there. Destination-passing avoids the extra MOVE a
    // return-a-register scheme needs at every use site (call args, if-branches,
    // assignments), cutting the instruction count of the hot VM dispatch loop.
    private def compileInto(t: Term, dst: Int): VmType =
      if VmCompiler.measureTypes then
        typeMapSeen.incrementAndGet()
        if ctx.vmTypeOf(t) != null then typeMapHits.incrementAndGet()
      compileIntoImpl(t, dst)

    private def compileIntoImpl(t: Term, dst: Int): VmType = t match
      case Lit.Int(v)       => emit(CONST, dst, constSlot(v.toLong), 0); setType(dst, TInt); TInt
      case Lit.Long(v)      => emit(CONST, dst, constSlot(v), 0); setType(dst, TInt); TInt
      case Lit.Boolean(v)   => emit(CONST, dst, constSlot(if v then 1L else 0L), 0); setType(dst, TInt); TInt
      case Lit.Double(v) =>
        emit(CONST, dst, constSlot(jl.Double.doubleToRawLongBits(v.toString.toDouble)), 0)
        setType(dst, TDouble); TDouble

      case n: Term.Name =>
        val r = locals.getOrElse(n.value, bail(s"undefined: name '${n.value}'"))
        if r != dst then emit(MOVE, dst, r, 0)
        val ty = typeOf(r); setType(dst, ty); ty

      case app: Term.ApplyInfix if app.argClause.values.lengthCompare(1) == 0 =>
        val rhs = app.argClause.values.head
        val op  = app.op.value
        // Const-prop Stage 1: both sides literal Int/Long → fold at compile time.
        intLiteral(app.lhs) match
          case Some(lv) =>
            intLiteral(rhs) match
              case Some(rv) =>
                foldIntInt(op, lv, rv) match
                  case Some(folded) =>
                    emit(CONST, dst, constSlot(folded), 0); setType(dst, TInt); TInt
                  case None =>
                    val lr = compileExpr(app.lhs)
                    val rr = compileExpr(rhs)
                    emitArith(op, dst, lr, rr)
              case None =>
                val lr = compileExpr(app.lhs)
                val rr = compileExpr(rhs)
                emitArith(op, dst, lr, rr)
          case None =>
            intLiteral(rhs) match
              case Some(v) =>
                val lr = compileExpr(app.lhs)
                if typeOf(lr) == TInt then                 // fold literal RHS into an immediate op
                  emit(opcodeImmFor(op), dst, lr, constSlot(v))
                  setType(dst, TInt); TInt
                else                                        // double lhs: promote the int literal
                  val rr = freshReg()
                  emit(CONST, rr, constSlot(jl.Double.doubleToRawLongBits(v.toDouble)), 0)
                  setType(rr, TDouble)
                  emitArith(op, dst, lr, rr)
              case None =>
                val lr = compileExpr(app.lhs)
                val rr = compileExpr(rhs)
                emitArith(op, dst, lr, rr)

      case t: Term.If =>
        val cr  = compileExpr(t.cond)
        if typeOf(cr) != TInt then bail("cond: non-boolean if-condition", Br.VmNonBoolCond)
        val jf  = emit(JF, cr, -1, 0)            // patch to else-start
        val tT  = compileInto(t.thenp, dst)
        val jmp = emit(JMP, -1, 0, 0)            // then-branch exit; patched below
        bs(jf) = ops.length                      // JF else-target: else-branch starts here
        val eT  = compileInto(t.elsep, dst)      // both branches write `dst`
        // wide-jit C-5: mixed {Int, Double} branches unify to Double (Scala's if-lub widens
        // Int→Double). Both branch types are known locally, so no external type is needed — widen the
        // Int branch's `dst` (I2D) rather than bailing MixedReturnType. Whichever branch ran leaves a
        // Double in `dst`. The Double branch already exits via `jmp`/fallthrough; the Int branch gets
        // an I2D on its own path so the other branch skips it.
        val unified: VmType =
          if tT == eT then
            as(jmp) = ops.length                 // then exit → end
            tT
          else if tT == TDouble && eT == TInt then
            emit(I2D, dst, dst, 0)               // else path: dst holds the Int else value → Double
            as(jmp) = ops.length                 // then exit → end (past the widen)
            VmCompiler.branchWidenings.incrementAndGet()
            TDouble
          else if tT == TInt && eT == TDouble then
            val jmp2 = emit(JMP, -1, 0, 0)       // else exit → end, skipping the then-pad
            as(jmp) = ops.length                 // then exit → then-pad (the I2D)
            emit(I2D, dst, dst, 0)               // then path: dst holds the Int then value → Double
            as(jmp2) = ops.length                // else exit → end
            VmCompiler.branchWidenings.incrementAndGet()
            TDouble
          else
            as(jmp) = ops.length
            bail("types: mismatched if-branches", Br.MixedReturnType)
        setType(dst, unified); unified

      // call to self or another compilable function (int or double domain)
      case app: Term.Apply =>
        tryCompileFoldLeft(app, dst) match
          case null => ()
          case t    => return t
        callTarget(app) match
          case Some(callee) =>
            val args = app.argClause.values
            if args.lengthCompare(callee.params.length) != 0 then bail("call: arg count mismatch", Br.VmCallShape)
            val slot    = slotFor(callee)
            val argBase = freshRegs(args.length)
            var i = 0
            while i < args.length do
              val aT   = compileInto(args(i), argBase + i)   // emit each arg straight into its slot
              val want = calleeParamType(callee, i)
              (want, aT) match
                case (TDouble, TInt) =>
                  emit(I2D, argBase + i, argBase + i, 0); setType(argBase + i, TDouble)
                case (TDouble, TDouble) | (TInt, TInt) | (TRef, TRef) => ()
                case _ => bail("call: ref/numeric arg type mismatch", Br.VmCallShape)
              i += 1
            emit(CALL, dst, argBase, slot)
            // busi seq-74 — a callee returning a ref (String/collection/object)
            // must type the call result TRef, else the JIT returns the raw long 0
            // (the regression: cross-module `def f(x:String):String = g(x)` where
            // g = `raw.trim.toLowerCase` came back as IntV(0)). Without a TRef
            // branch here the result was always TInt for non-double callees.
            val rt =
              if calleeIsDouble(callee) then TDouble
              else if calleeReturnsRef(callee) then TRef
              else
                // wide-jit C-3 consumption: the syntactic heuristic gave up (would default to
                // TInt). Take the callee's REAL return type from the Typer's map — but only to
                // UPGRADE the unknown default (never override a heuristic hit). Empty map ⇒ null
                // ⇒ TInt = current behaviour (same-module siblings only; imports re-parse → miss).
                ctx.vmTypeOf(callee.body) match
                  case TDouble => callResultUpgrades.incrementAndGet(); TDouble
                  case TRef    => callResultUpgrades.incrementAndGet(); TRef
                  case _       => TInt
            setType(dst, rt)
            if rt == TRef then refTypeName(dst) = ""
            rt
          case None =>
            app.fun match
              case n: Term.Name =>
                val r = locals.getOrElse(n.value, -1)
                if r >= 0 && typeOf(r) == TRef then
                  val tn = refTypeName.getOrElse(r, "")
                  if tn.startsWith("FunV") then
                    // HOF call: emit CALLREF; args at argBase, FunV at r, result → dst
                    val args    = app.argClause.values
                    val nargs   = args.length
                    val argBase = freshRegs(nargs.max(1))
                    var i = 0
                    while i < nargs do
                      compileInto(args(i), argBase + i)
                      i += 1
                    emit(CALLREF, dst, r, argBase)
                    setType(dst, TInt)  // assume Long return (most HOF cases)
                    return TInt         // explicit return from compileInto match
              case _ =>
            bail("call: no compilable target (free name, closure, or non-function)", Br.FreeNameUnresolvable("call-target"))

      case Term.Block(stats) =>
        compileStatsInto(stats, dst)

      case Term.While(cond, body) =>
        val start = ops.length
        val cr    = compileExpr(cond)
        if typeOf(cr) != TInt then bail("cond: non-boolean while-condition", Br.VmNonBoolCond)
        val jf    = emit(JF, cr, -1, 0)
        // Compile body as void — all stats are statements, no return value needed.
        // Avoids compileStats→compileExpr(last) which bails on Term.Assign.
        body match
          case Term.Block(ss) => ss.foreach(compileStmt)
          case _              => compileStmt(body)
        emit(JMP, start, 0, 0)
        bs(jf) = ops.length
        emit(CONST, dst, constSlot(0L), 0)       // while ⇒ unit ⇒ 0
        setType(dst, TInt); TInt

      case tm: Term.Match =>
        compileMatchInto(tm.expr, tm.casesBlock.cases, dst)

      case Term.ApplyUnary(op, arg) =>
        val ar = compileExpr(arg)
        op.value match
          case "-" =>
            // negate: 0 - ar  (int) or  0.0 - ar  (double)
            if typeOf(ar) == TDouble then
              val zero = freshReg()
              emit(CONST, zero, constSlot(jl.Double.doubleToRawLongBits(0.0)), 0)
              setType(zero, TDouble)
              emit(FSUB, dst, zero, ar); setType(dst, TDouble); TDouble
            else
              val zero = freshReg()
              emit(CONST, zero, constSlot(0L), 0)
              emit(SUB, dst, zero, ar); setType(dst, TInt); TInt
          case "!" =>
            emit(EQI, dst, ar, constSlot(0L)); setType(dst, TInt); TInt  // !x == (x == 0)
          case other => bail(s"unsupported: unary operator '$other'", Br.VmUnsupportedTerm)

      // `obj.field` — standalone field access (outside a match pattern).
      // Only compiles when: (a) obj is a TRef register, (b) its declared type
      // name is known (params + match bindings populate refTypeName), and
      // (c) the field name resolves via meta. Method calls are `Term.Select`
      // inside a `Term.Apply.fun` — those reach callTarget, not here.
      case Term.Select(obj, field: Term.Name) =>
        val or = compileExpr(obj)
        if typeOf(or) != TRef then bail(s"field: non-ref base for .${field.value}", Br.VmFieldShape)
        val typeName = refTypeName.getOrElse(or, bail(s"field: unknown ref type for .${field.value}", Br.VmFieldShape))
        // No-arg String methods: `s.trim`/`s.toLowerCase`/`s.toUpperCase` return a
        // String (SSTR); `s.length`/`isEmpty`/`nonEmpty`/`toInt`/`toLong` return an
        // Int via GETFI (the runtime branch parses/measures the StringV in place).
        // Matches the interpreter's DispatchRuntime string ops exactly.
        if typeName == "String" then
          field.value match
            case "trim" | "toLowerCase" | "toUpperCase" =>
              emit(SSTR, dst, or, strSlot(field.value)); setType(dst, TRef); setRefType(dst, "String"); return TRef
            case "length" | "isEmpty" | "nonEmpty" | "toInt" | "toLong" =>
              emit(GETFI, dst, or, strSlot(field.value)); setType(dst, TInt); return TInt
            case _ =>
              bail(s"field: unsupported String method '.${field.value}'", Br.VmFieldShape)
        val info = ctx.metaFor(typeName)
        if info == null then bail(s"field: no meta for type '$typeName'", Br.VmFieldShape)
        val (names, types) = info
        val idx = names.indexOf(field.value)
        if idx < 0 then bail(s"field: '${field.value}' not found in '$typeName'", Br.VmFieldShape)
        val ft = fieldVmType(types(idx))
        if ft == TRef then
          emit(GETFR, dst, or, strSlot(field.value)); setType(dst, TRef)
          setRefType(dst, types(idx).trim); TRef
        else
          emit(GETFI, dst, or, strSlot(field.value)); setType(dst, ft); ft

      case Lit.Null() =>
        emit(CONST, dst, constSlot(0L), 0); setType(dst, TRef); TRef

      case Lit.String(s) =>
        emit(LOADS, dst, strSlot(s), 0); setType(dst, TRef); setRefType(dst, "String"); TRef

      // Non-capturing lambda: `(x: Int) => body`. Compiles when the lambda's free
      // names are all globals (not outer locals). Creates a FunV, stores it in the
      // per-function funVPool, and emits LOADFV to materialise it at runtime.
      case fn: Term.Function =>
        val lambdaParams = fn.paramClause.values
        val lambdaParamNames = lambdaParams.map(_.name.value).toSet
        val freeNames = mutable.Set.empty[String]
        def collectFree(t: scala.meta.Tree): Unit = t match
          case n: Term.Name if !lambdaParamNames.contains(n.value) => freeNames += n.value
          case other2 => other2.children.foreach(collectFree)
        collectFree(fn.body)
        val captured = freeNames.filter(locals.contains)
        if captured.nonEmpty then bail(s"lambda: captures outer locals: ${captured.mkString(", ")}", Br.CapturedFreeName(captured.headOption.getOrElse("?")))
        val lambdaParamNameList = lambdaParams.map(_.name.value)
        val lambdaParamTypes = lambdaParams.map(p => p.decltpe match
          case Some(tpe) => tpe.syntax
          case None      => "Int"
        )
        val lambdaFunV = Value.FunV(lambdaParamNameList, fn.body, Map.empty, "",
          lambdaParamNameList.map(_ => None), lambdaParamTypes)
        val poolSlot = funvs.length; funvs += lambdaFunV
        emit(LOADFV, dst, poolSlot, 0)
        setType(dst, TRef); setRefType(dst, s"FunV_${lambdaParamNameList.length}"); TRef

      case other =>
        bail(s"unsupported: ${termName(other)}", Br.VmUnsupportedTerm)

    // Compile `t` in TAIL position: every path ends in either a RET or a
    // jump back to instruction 0 (a self-tail-call turned into a loop). This
    // gives constant host-stack depth for self-tail recursion — the same shape
    // the JVM backend produces, and what makes deep `sumTco` not overflow.
    // Tail calls to *other* functions go through compileExpr (a CALL + RET);
    // pathologically deep mutual tail recursion may overflow the host stack and
    // safely fall back to the tree-walker.
    private def compileTail(t: Term): Unit = t match
      case ti: Term.If =>
        val cr = compileExpr(ti.cond)
        if typeOf(cr) != TInt then bail("cond: non-boolean if-condition (tail)", Br.VmNonBoolCond)
        val jf = emit(JF, cr, -1, 0)
        compileTail(ti.thenp)        // then-branch self-terminates
        bs(jf) = ops.length
        compileTail(ti.elsep)        // else-branch self-terminates

      case tm: Term.Match =>
        compileMatchTail(tm.expr, tm.casesBlock.cases)

      case app: Term.Apply if isSelfTailCall(app) =>
        val args = app.argClause.values
        if args.lengthCompare(fn.params.length) != 0 then bail("call: self-tail arg count mismatch", Br.VmCallShape)
        // Evaluate every arg into a temp BEFORE overwriting the param regs,
        // since args (e.g. `acc + n`) read the current params. Promote an int
        // arg to double when the param register is double-typed (and bail on the
        // reverse, which would silently reinterpret double bits as an int).
        val tmp = new Array[Int](args.length)
        var i = 0
        while i < args.length do
          var r = compileExpr(args(i))
          val want = typeOf(i)                       // param reg i's fixed type
          (want, typeOf(r)) match
            case (TDouble, TInt)    => r = asDouble(r)
            case (a, b) if a == b   => ()
            case _                  => bail("call: self-tail arg type mismatch (ref/numeric)", Br.VmCallShape)
          tmp(i) = r; i += 1
        i = 0
        while i < args.length do { emit(MOVE, i, tmp(i), 0); i += 1 } // params = r0..r(n-1)
        emit(JMP, 0, 0, 0)           // loop to start

      case Term.Block(stats) =>
        if stats.isEmpty then bail("block: empty block in tail position", Br.VmEmptyBlock)
        var rest = stats
        while rest.tail.nonEmpty do { compileStmt(rest.head); rest = rest.tail }
        compileTail(rest.head.asInstanceOf[Term])

      case other =>
        val r = compileExpr(other)
        var rt = typeOf(r)
        // wide-jit C-4c: the function is DECLARED to return Double but this leaf came back Int
        // (e.g. `if c > 0 then 1.5 else 2` — the `2`). Scala widens Int→Double implicitly on every
        // return path, so emit the I2D here rather than letting `unifyRet` bail on a false "mixed
        // return". `r` flows only to the RET below (tail position), so the in-place I2D is safe.
        // Gated on the DECLARED annotation — a FunV with no populated return type leaves this alone.
        if rt == TInt && declaredDouble then
          emit(I2D, r, r, 0); setType(r, TDouble); rt = TDouble
          VmCompiler.retDoubleWidenings.incrementAndGet()
        unifyRet(rt)
        if rt == TRef then emit(RETREF, r, 0, 0) else emit(RET, r, 0, 0)

    private def isSelfTailCall(app: Term.Apply): Boolean =
      fn.name.nonEmpty && (app.fun match
        case n: Term.Name => n.value == fn.name && !locals.contains(n.value)
        case _            => false)

    // A statement whose value may be discarded (loop bodies, non-final stmts).
    // `val`/`var` get a stable home register written directly by compileInto;
    // assignments write straight into the bound register — no extra MOVE.
    private def compileStmt(t: Tree): Unit = t match
      case Defn.Val(_, List(Pat.Var(nm: Term.Name)), _, rhs) =>
        val home = freshReg(); compileInto(rhs, home); locals(nm.value) = home
      case Defn.Var.After_4_7_2(_, List(Pat.Var(nm)), _, rhs) =>
        val home = freshReg(); compileInto(rhs, home); locals(nm.value) = home
      case Term.Assign(nm: Term.Name, rhs) =>
        val dst = locals.getOrElse(nm.value, bail(s"undefined: assign to unknown var '${nm.value}'", Br.VmUndefinedName))
        val old = typeOf(dst)
        val nt  = compileInto(rhs, dst)
        if nt != old then
          // wide-jit C-6: assigning an Int to a Double var — Scala widens Int→Double, so widen `dst`
          // (which holds the just-compiled Int rhs) rather than bailing on a false domain change. The
          // reverse (Double into an Int var) is a Scala type error → still bails.
          if old == TDouble && nt == TInt then
            emit(I2D, dst, dst, 0); setType(dst, TDouble)
            VmCompiler.varWidenings.incrementAndGet()
          else bail("types: var domain change (Int↔Double)", Br.MixedReturnType)
      // Inner def: compile to a standalone callee that shares the Ctx call pool.
      // Only non-capturing inner defs compile; a body that references outer locals
      // will bail with "undefined: name '...'" when the inner Builder runs — that
      // bail propagates out and disables the outer function too (correct).
      case d: Defn.Def =>
        val regularParams = d.paramClauseGroups.flatMap(_.paramClauses)
          .filter(_.mod.isEmpty).flatMap(_.values).toList
        val params     = regularParams.map(_.name.value)
        val paramTypes = regularParams.map(p => p.decltpe match
          case Some(t: Type.Name) => t.value
          case Some(t) => t.syntax
          case None    => ""
        )
        val defaults = regularParams.map(_.default)
        val innerFunV = Value.FunV(params, d.body, Map.empty, d.name.value, defaults, paramTypes)
        ctx.compileFn(innerFunV)    // bails if body captures outer locals
        innerDefs(d.name.value) = innerFunV
      case Term.Block(stats) => compileStats(stats); ()
      case e: Term            => compileExpr(e); ()
      case other              => bail(s"unsupported: stmt ${termName(other)}", Br.VmUnsupportedTerm)

    private def compileStats(stats: List[Stat]): Int =
      if stats.isEmpty then bail("block: empty stat list", Br.VmEmptyBlock)
      var rest = stats
      while rest.tail.nonEmpty do { compileStmt(rest.head); rest = rest.tail }
      compileExpr(rest.head.asInstanceOf[Term])

    private def compileStatsInto(stats: List[Stat], dst: Int): VmType =
      if stats.isEmpty then bail("block: empty stat list", Br.VmEmptyBlock)
      var rest = stats
      while rest.tail.nonEmpty do { compileStmt(rest.head); rest = rest.tail }
      compileInto(rest.head.asInstanceOf[Term], dst)

    // ── match compilation (VM 2a) ────────────────────────────────────
    // Only the safe shape compiles: `scrut match { case Ctor(binds...) => body }`
    // over a ref scrutinee, no guards, binders are plain `Pat.Var`/`_`. Each case
    // becomes ISTAG (tag test) + JF (skip to next case) + GETFI/GETFR (bind each
    // field positionally, by declared field type). An unmatched scrutinee hits
    // MFAIL → JIT bridge falls back to the tree-walker (same MatchError). Anything
    // outside this shape bails, preserving semantics by construction.

    /** Emit ISTAG + JF + field bindings for one case. Returns the JF instruction
     *  index, to be patched to where the next case begins. */
    private def emitCaseHeader(scrutReg: Int, c: scala.meta.Case): Int =
      if c.cond.isDefined then bail("match: guard present (guards unsupported)", Br.PatternGuard)
      c.pat match
        case Pat.Extract.After_4_6_0(fn0, argClause) =>
          // Accept both `Bar(...)` and qualified `Foo.Bar(...)` — take the rightmost name
          val ctor = fn0 match
            case n: Term.Name => n.value
            case Term.Select(_, n: Term.Name) => n.value
            case _ => bail("match: constructor is not a name or qualified name", Br.NonExtractPattern)
          val argPats = argClause.values
          val test = freshReg()
          emit(ISTAG, test, scrutReg, strSlot(ctor))
          val jf = emit(JF, test, -1, 0)
          val info = ctx.metaFor(ctor)
          if info == null then bail(s"match: unknown ADT constructor '$ctor'", Br.NonExtractPattern)
          val (names, types) = info
          if argPats.lengthCompare(names.length) != 0 then bail("match: binding count mismatch", Br.NonExtractPattern)
          var i  = 0
          var ps = argPats
          while ps.nonEmpty do
            ps.head match
              case Pat.Var(nm: Term.Name) =>
                val home = freshReg()
                val ft   = fieldVmType(types(i))
                if ft == TRef then
                  emit(GETFR, home, scrutReg, strSlot(names(i))); setType(home, TRef)
                  setRefType(home, types(i).trim)
                else { emit(GETFI, home, scrutReg, strSlot(names(i))); setType(home, ft) }
                locals(nm.value) = home
              case _: Pat.Wildcard => ()              // matched but unbound
              case _               => bail("match: non-Var/wildcard binding", Br.NonExtractPattern)
            i += 1; ps = ps.tail
          jf
        // No-arg enum case or case object: `case Red =>` or `case Color.Red =>`
        case n: Term.Name =>
          val test = freshReg()
          emit(ISTAG, test, scrutReg, strSlot(n.value))
          emit(JF, test, -1, 0)
        case Term.Select(_, n: Term.Name) =>
          val test = freshReg()
          emit(ISTAG, test, scrutReg, strSlot(n.value))
          emit(JF, test, -1, 0)
        case _ => bail("match: unsupported pattern shape", Br.NonExtractPattern)

    /** Tail-position match: each arm self-terminates (RET or a self-tail loop). */
    private def compileMatchTail(scrut: Term, cases: List[scala.meta.Case]): Unit =
      val sr = compileExpr(scrut)
      if typeOf(sr) != TRef then bail("match: non-ref scrutinee", Br.NonAdtScrutinee)
      var rest = cases
      while rest.nonEmpty do
        val jf = emitCaseHeader(sr, rest.head)
        compileTail(rest.head.body)
        bs(jf) = ops.length                           // next case starts here
        rest = rest.tail
      emit(MFAIL, 0, 0, 0)

    /** Expression-position match: each arm writes `dst` then jumps to the end.
     *  All arms must agree on result type — except a mix of only {Int, Double}, which
     *  widens to Double (wide-jit C-5b), the same lub Scala applies to match branches. */
    private def compileMatchInto(scrut: Term, cases: List[scala.meta.Case], dst: Int): VmType =
      val sr = compileExpr(scrut)
      if typeOf(sr) != TRef then bail("match: non-ref scrutinee", Br.NonAdtScrutinee)
      val endJumps = mutable.ArrayBuffer.empty[Int]
      val armTypes = mutable.ArrayBuffer.empty[VmType]
      var rest = cases
      while rest.nonEmpty do
        val jf = emitCaseHeader(sr, rest.head)
        val bt = compileInto(rest.head.body, dst)
        endJumps += emit(JMP, -1, 0, 0)
        armTypes += bt
        bs(jf) = ops.length
        rest = rest.tail
      emit(MFAIL, 0, 0, 0)
      if armTypes.isEmpty then bail("match: no result type (empty cases?)", Br.VmEmptyBlock)
      // wide-jit C-5b: unify arm types. All-equal → that type. A mix of only {Int, Double} widens to
      // Double — Int arms route their end-jump through a shared I2D pad (placed after the terminal
      // MFAIL, so only reached via that jump) so each leaves a Double in `dst`; Double arms jump
      // straight to end. Any other mix (ref vs numeric, …) still bails MixedReturnType.
      val distinct = armTypes.distinct
      val rt: VmType =
        if distinct.lengthCompare(1) == 0 then
          val end = ops.length
          endJumps.foreach(j => as(j) = end)
          distinct.head
        else if distinct.forall(t => t == TInt || t == TDouble) then
          val pad     = ops.length
          emit(I2D, dst, dst, 0)
          val padExit = emit(JMP, -1, 0, 0)
          val end     = ops.length
          as(padExit) = end
          var k = 0
          while k < endJumps.length do
            as(endJumps(k)) = if armTypes(k) == TInt then pad else end
            k += 1
          VmCompiler.branchWidenings.incrementAndGet()
          TDouble
        else
          val end = ops.length
          endJumps.foreach(j => as(j) = end)
          bail("match: mismatched arm types", Br.MixedReturnType)
      setType(dst, rt); rt

    /** Emit the instruction stream; callee shells are resolved later by [[Ctx]]. */
    def buildInstructions(): Unit =
      val arity = fn.params.length
      if arity > MaxArity then bail(s"arity: $arity out of range [0..$MaxArity]", Br.TooManyParams(arity))
      var i = 0
      while i < arity do
        locals(fn.params(i)) = i                         // params occupy r0..r(arity-1)
        if i < fn.paramTypes.length then
          val pt = fn.paramTypes(i).trim
          fieldVmType(pt) match
            case TInt    => ()
            case TRef    =>
              setType(i, TRef)
              setRefType(i, if isFunType(pt) then s"FunV_${funArity(pt)}" else pt)
            case t       => setType(i, t)
        i += 1
      nextReg = arity; maxReg = arity
      compileTail(fn.body)           // emits RET / loop on every path
      // The up-front double classification must agree with the actual return
      // type derived from the RET leaves; otherwise self-call results were typed
      // wrong. Bail (→ tree-walk) rather than risk a miswrapped value.
      if fnIsDouble && !retIsDoubleOf then bail("types: double heuristic/return type mismatch", Br.MixedReturnType)
