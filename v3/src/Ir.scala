package ssc3

// SSC IR — the executable core of ScalaScript 3. Design: v3/specs/10-ssc-ir.md.
//
// This is NOT a pruned AST. A function is a linear sequence of instructions over a fixed-size
// register frame; control flow is structured (Block/Loop/If + relative Br) and arbitrary jumps
// cannot be expressed.
//
// Written in the Scala 3 ∩ ScalaScript 2 subset (v3/specs/30-portable-subset.md): immutable case
// classes and enums, List, Option, tuples, local var/while, String operations, Array. No mutable
// collections, no StringBuilder, no regex, no host Char classification.

/** Constant-pool entry.
  *
  * `LBig` holds decimal digits and `LBytes` holds hex, both as `String`, rather than `BigInt` and
  * `Vector[Byte]`. That keeps the IR's data model inside the portable subset — the value is exact
  * either way, and the decision about how to represent a big integer at RUN time belongs to the
  * runtime rather than to the serialized program.
  */
enum Lit:
  case LUnit
  case LBool(b: Boolean)
  case LInt(n: Long) // 64-bit, wrapping — `Int` is i64 in ScalaScript
  case LBig(digits: String)
  case LFloat(d: Double)
  case LStr(s: String)
  case LBytes(hex: String)

/** The numeric kind an arithmetic instruction was proved to operate on.
  *
  * A FIELD of the instruction rather than a separate opcode (spec §3): the front emits `Dyn` unless
  * it can prove the operand type, and the specializer rewrites the field in place. Optimization is
  * then a rewrite of data, not a change of representation.
  */
enum NumKind:
  case Dyn, I64, F64, Big

enum UnOp:
  case Neg, Not, BNot

enum BinOp:
  case Add, Sub, Mul, Div, Rem
  case BAnd, BOr, BXor, Shl, Shr, UShr
  case Lt, Le, Gt, Ge, Eq, Ne

/** A constructor's layout. THE source of truth for field indices, consulted by the emitter and by
  * the verifier alike. The alternative — a compile-time name→index map that can disagree with the
  * runtime record layout — is a whole bug family in this repository, and every disagreement is a
  * silent wrong-field read. */
/** A constructor: its name, how many fields it has, and — when the front knew them — what those
  * fields are CALLED.
  *
  * `fieldNames` is EITHER empty or exactly `fields` long, and empty means "not known here" rather
  * than "none". The builtin constructors this file's lowering registers (`Cons`, `Nil`, `Tuple2`, …)
  * have no user-written field names and pass none; a `case class` declaration has them and does.
  *
  * WHY THE IR CARRIES THEM AT ALL, decided by the owner on 2026-08-22 against the alternative of
  * asking a plugin: a value has to be able to say how it PRINTS. `Exec.showV` renders a data value
  * by its `_show` field when the class declares one, and it can only find that field by NAME. v2 has
  * had the same rule for HOST values since optics needed it (`Runtime.scala`, `NamedMethodObj`'s
  * `_show`); this is the same capability for values written in the language, and the bridge lane
  * renders with v2, so both halves exist or the two lanes disagree.
  *
  * READERS THAT WANT THE COUNT ARE UNTOUCHED — `Verify` and `BridgeV2` read `fields`. */
final case class TypeDef(name: String, fields: Int, fieldNames: List[String] = Nil)

final case class GlobalDef(name: String)

/** One arm of a `Switch`: the constructor tag it matches, and what to run. */
final case class SwitchArm(tag: Int, body: List[Instr])

/** One arm of a `Handle`: the effect operation it handles, and what to run. */
/** One arm of a `Handle`: the effect operation it handles, where its arguments land, where the
  * continuation lands, and what to run.
  *
  * `params` and `k` are registers OF THE HANDLING FUNCTION'S FRAME — see `specs/10-ssc-ir.md` §3
  * "The protocol". They are explicit rather than positional so that rule 1 of §4 covers them: the
  * verifier already checks every register index against the function's `nregs`, and a second frame
  * with its own numbering would be an invariant this IR states in prose and cannot check. */
final case class HandlerArm(op: Int, params: List[Int], k: Int, body: List[Instr])

/** IS THIS ARM THE SHAPE THAT NEEDS NO CONTINUATION — a single `resume` as its LAST act?
  *
  * IT LIVES HERE BECAUSE THREE PLACES DECIDE ON IT and two of them already had a private copy each
  * (`Exec.tailResumptive`, `BridgeV2.isTailResumptive`, character-for-character the same rule under
  * two names). The third is `Cps`, which uses it to decide whether to SPLIT at all — and a pass and
  * a runtime disagreeing about this predicate is not a style problem: the split makes the performing
  * function return the ARM's value, and a lane that then declines to carry the caller's remainder
  * runs the rest of the program on a value the split already finished with. Measured as `1001` where
  * the answer is `1100`.
  *
  * "Last act" and "exactly once" are both required. Once, because two resumes are a multi-shot
  * continuation and there is nothing to fold away; last, because an arm with work after the resume
  * has a remainder of its own that only a real continuation can carry.
  *
  * CHECKED STRUCTURALLY, never by watching what the arm does. A dynamic check cannot tell an arm
  * that resumed and stopped from one that resumed and then did work whose effect is already gone,
  * and the answer has to arrive before anything is observable.
  *
  * A TRAILING `Ret` IS SKIPPED. The lowering appends one to every arm so its value has a single
  * place to come from; that makes the last instruction a `Ret` and this check — which wants "the
  * last ACT is a resume" — would otherwise refuse every handler it exists to accept. */
extension (arm: HandlerArm)
  def tailResumptive: Boolean =
    def countResumes(body: List[Instr]): Int =
      body.map {
        case Instr.Resume(_, _, _) => 1
        case other                 => countResumes(Instr.children(other))
      }.sum
    val body = arm.body match
      case init :+ Instr.Ret(_) => init
      case other                => other
    body.nonEmpty && (body.last match
      case Instr.Resume(_, _, _) => countResumes(arm.body) == 1
      case _                     => false)

/** `d`/`a`/`b`/`c` are register indices; `k` indexes the constant pool, `g` the globals, `t` the
  * types, `f` the functions. */
enum Instr:
  // Constants and moves
  case Const(dst: Int, k: Int)
  case Move(dst: Int, a: Int)

  // Arithmetic and comparison. `&&` and `||` are NEVER here — they short-circuit, so they lower
  // to `If`. An IR that lets them be strict binary operators has already lost the semantics.
  case Un(op: UnOp, kind: NumKind, dst: Int, a: Int)
  case Bin(op: BinOp, kind: NumKind, dst: Int, a: Int, b: Int)

  // Structured control flow (spec §2). Regions carry NO result values: a register machine does not
  // need them — write the value to a register before branching.
  case Block(body: List[Instr])
  case Loop(body: List[Instr])
  case If(cond: Int, thenB: List[Instr], elseB: List[Instr])
  case Br(depth: Int)
  case BrIf(cond: Int, depth: Int)

  // Calls. `TailCall` reuses the current frame, so self- and mutual tail recursion run in constant
  // stack — a stability requirement, not an optimization.
  case Call(dst: Int, f: Int, args: List[Int])
  case CallV(dst: Int, callee: Int, args: List[Int])
  case MkClos(dst: Int, f: Int, captures: List[Int])
  case TailCall(f: Int, args: List[Int])
  case Ret(a: Int)

  // Data
  case MkData(dst: Int, t: Int, args: List[Int])
  // `t` is carried because validation rule 4 is otherwise UNCHECKABLE: without the type there is
  // no field count to compare `idx` against, and the rule degrades to "valid for some type",
  // which is not an invariant. The front always knows it — it has just matched the constructor.
  case Field(dst: Int, a: Int, t: Int, idx: Int)
  case Tag(dst: Int, a: Int)
  case Switch(scrut: Int, arms: List[SwitchArm], default: List[Instr])

  // Mutable storage
  case NewArr(dst: Int, len: Int)
  case ArrGet(dst: Int, arr: Int, idx: Int)
  case ArrSet(arr: Int, idx: Int, v: Int)
  case ArrLen(dst: Int, arr: Int)
  case GlobGet(dst: Int, g: Int)
  case GlobSet(g: Int, a: Int)

  // Effects — the dispatcher. `Perform` suspends and hands (op, args) plus the captured frame to
  // the nearest enclosing `Handle`. Because a frame is data, what the handler receives is a value
  // it may store, queue, or resume later from another thread.
  /** `try`/`catch`. BOTH regions leave their result in `dst`, so the instruction has one result
    * register rather than two that a later pass would have to reconcile. `exn` is where the caught
    * value is bound for the handler.
    *
    * Its own instruction rather than an effect: `Perform`/`Handle` are reserved for Tier 2 and are
    * resumable, and an exception is not — conflating them would give one instruction two control
    * semantics. */
  case Try(dst: Int, body: List[Instr], exn: Int, handler: List[Instr])

  case Perform(dst: Int, op: Int, args: List[Int])
  case Handle(dst: Int, body: List[Instr], arms: List[HandlerArm])
  case Resume(dst: Int, k: Int, v: Int)

  // Dynamic method dispatch. NOT a `Prim`: `Prim` is the door to the HOST — I/O, interop, plugin
  // SPI — and dispatching a method on a value is language semantics. Folding it into `Prim` would
  // hide a call from every pass that wants to see calls, and would make the host boundary a lie.
  //
  // `name` indexes the CONSTANT POOL and must name an `LStr`. A register would have been the other
  // option and is worse: the name is known when the front emits, so a pool index lets a backend
  // resolve it at translation time instead of carrying a string through the frame.
  case Invoke(dst: Int, name: Int, recv: Int, args: List[Int])

  // The single door to everything the IR does not define: I/O, host interop, plugin SPI. This is
  // what makes the zero-dependency invariant checkable rather than aspirational.
  case Prim(dst: Int, prim: Int, args: List[Int])

final case class Func(name: String, nparams: Int, nregs: Int, body: List[Instr])

final case class Module(
    consts: List[Lit],
    types: List[TypeDef],
    globals: List[GlobalDef],
    prims: List[String],
    funcs: List[Func],
    entry: Int,
)

object Perform:
  /** Functions that can PERFORM an effect — directly, or by calling one that can.
    *
    * ONE definition, on the IR, because `Instr.Perform` and `Instr.Call` are unambiguous here while
    * the AST has two spellings of a call and an op id that is only resolved late. Step 1 of
    * SSC3-7b (`specs/10-ssc-ir.md` §3, "Capturing a continuation"): a continuation is only needed
    * where a perform can reach, and converting every function would pay for effects in programs
    * that have none.
    *
    * CONSERVATIVE where it can be, and the two directions are not symmetric:
    *
    *   - a `Perform` inside the function's OWN `Handle` still counts. The handler catches it, so in
    *     principle nothing is needed at the boundary — but deciding that means matching op ids
    *     against arms through every nested handle, and being wrong loses a continuation silently.
    *     Over-approximating is slower and right.
    *   - a call through a VALUE (`CallV`) contributes nothing, because nothing here knows what it
    *     calls. That is an UNDER-approximation and the one way this set can be wrong; it is the same
    *     limit the lowering's `gapNames` walk records, for the same reason, and it is named rather
    *     than left to be discovered.
    *
    * Fixed point, not one pass: `a` calls `b` calls `c`, and `c` performing must reach `a` whatever
    * order the functions appear in.
    */
  def performing(m: Module): Set[String] =
    def scan(body: List[Instr], f: Instr => Unit): Unit =
      body.foreach { i => f(i); scan(Instr.children(i), f) }
    def directly(fn: Func): Boolean =
      var found = false
      scan(fn.body, { case Instr.Perform(_, _, _) => found = true; case _ => () })
      found
    def calls(fn: Func): List[Int] =
      var out: List[Int] = Nil
      scan(fn.body, {
        case Instr.Call(_, f, _)     => out = f :: out
        case Instr.TailCall(f, _)    => out = f :: out
        case _                       => ()
      })
      out
    var acc = m.funcs.filter(directly).map(_.name).toSet
    var changed = true
    while changed do
      changed = false
      m.funcs.foreach { fn =>
        if !acc.contains(fn.name) && calls(fn).exists(i => i >= 0 && i < m.funcs.length && acc.contains(m.funcs(i).name)) then
          acc = acc + fn.name
          changed = true
      }
    acc

object Instr:
  /** Does this instruction leave the function or the enclosing region unconditionally?
    *
    * Used by validation rule 5. `BrIf` is deliberately NOT a terminator — it may fall through,
    * which is the entire difference between it and `Br`. */
  def isTerminator(i: Instr): Boolean = i match
    case _: Instr.Ret      => true
    case _: Instr.TailCall => true
    case _: Instr.Br       => true
    case Instr.If(_, t, e) => endsInTerminator(t) && endsInTerminator(e)
    case Instr.Switch(_, arms, default) =>
      endsInTerminator(default) && arms.forall(a => endsInTerminator(a.body))
    case _ => false

  def endsInTerminator(body: List[Instr]): Boolean =
    body.nonEmpty && isTerminator(body.last)

  /** Instructions nested inside this one. Structural, so anything walking the IR — coverage,
    * optimization passes, the specializer — asks the data rather than re-listing the cases. */
  def children(i: Instr): List[Instr] = i match
    case Instr.Block(b)            => b
    case Instr.Loop(b)             => b
    case Instr.If(_, t, e)         => t ++ e
    case Instr.Switch(_, arms, df) => arms.flatMap(a => a.body) ++ df
    case Instr.Handle(_, b, arms)  => b ++ arms.flatMap(a => a.body)
    case Instr.Try(_, b, _, h)     => b ++ h
    case _                         => Nil

  /** This instruction and every instruction under it, in source order. */
  def flatten(body: List[Instr]): List[Instr] =
    body.flatMap(i => i :: flatten(children(i)))
