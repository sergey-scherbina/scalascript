package scalascript.typer

/** A single member declaration inside a structural `Refinement`.
 *  We don't capture the full Scala member body — only the declaration
 *  shape (kind + name + signature) is sufficient for interface round-trip.
 *
 *  `kind` is one of `def`, `val`, `type`.
 */
case class RefMember(kind: String, name: String, sig: SType)

/** A single case of a match-type — `case pattern => rhs`.
 *  Surface-only: the typer / interpreter never reduces these. */
case class MatchCase(pattern: SType, rhs: SType)

/** A single algebraic-effect operation, optionally parameterized by type args.
 *  `EffectOp("Logger", Nil)` — non-parameterized (classic scalar effect).
 *  `EffectOp("Stream", List(SType.Int))` — parameterized with one type arg (v1.51.6+).
 *  Supports arbitrary arity: `EffectOp("State", List(S, A))` for two-arg effects (future). */
case class EffectOp(name: String, args: List[SType] = Nil):
  def show: String =
    if args.isEmpty then name
    else s"$name[${args.map(_.show).mkString(", ")}]"

/** Internal type representation for type-checking.
 *  Kept separate from the AST so the typer can introduce unification variables
 *  and apply substitutions without modifying source trees.
 */
enum SType:
  case Named(name: String, args: List[SType])
  case Var(id: Int)
  case Function(params: List[SType], result: SType, effects: EffectRow = EffectRow(None, Set.empty[EffectOp]))
  case Tuple(elems: List[SType])
  case Union(types: List[SType])
  case Intersection(types: List[SType])
  /** A higher-kinded type parameter such as `F[_]` (arity 1) or
   *  `F[_, _]` (arity 2).  Surface-only — never participates in
   *  unification or runtime semantics, but round-trips through
   *  `show` / `parseSType` so interface artifacts stay structural. */
  case HigherKinded(name: String, arity: Int)
  /** Structural refinement type — `Base { def foo: Int; val bar: String }`.
   *  Surface-only: the typer / interpreter never inspect the members,
   *  they exist purely so an interface artifact can round-trip the
   *  declared shape through `show` / `parseSType`. */
  case Refinement(base: SType, members: List[RefMember])
  /** Match-type — `T match { case Int => String; case _ => Any }`.
   *  Surface-only: never reduced, never unified.  Round-trips through
   *  `show` / `parseSType` so interface artifacts retain the declared
   *  shape verbatim. */
  case Match(scrutinee: SType, cases: List[MatchCase])
  /** An effect row `{ Eff1, Eff2[T], … }` with an optional open tail variable.
   *  Ops may be non-parameterized (`Logger`) or parameterized (`Stream[A]`, `State[S, A]`).
   *  Only appears as the `effects` field of `SType.Function`; never stands alone as a value type. */
  case EffectRow(tail: Option[Int], ops: Set[EffectOp])
  case Error(msg: String)

  def show: String = this match
    case Named(name, Nil)     => name
    case Named(name, args)    => s"$name[${args.map(_.show).mkString(", ")}]"
    case Var(id)              => s"?$id"
    // Function-type parameters and tuples must be parenthesised on the
    // left of `=>` so the printed form round-trips: `Int => String => Boolean`
    // is right-associative, so a function-as-param needs explicit parens.
    case Function(List(p), r, effs) =>
      val effStr = showEffects(effs)
      s"${showFnParam(p)} => ${r.show}$effStr"
    case Function(params, r, effs) =>
      val effStr = showEffects(effs)
      s"(${params.map(_.show).mkString(", ")}) => ${r.show}$effStr"
    // Unit (0-tuple) renders as "Unit" for .scim backward compatibility.
    // 1-tuple uses trailing comma to distinguish (A,) from parenthesised (A).
    case Tuple(Nil)           => "Unit"
    case Tuple(List(t))       => s"(${t.show},)"
    case Tuple(elems)         => s"(${elems.map(_.show).mkString(", ")})"
    // `&` binds tighter than `|`, so nested `Intersection` inside `Union`
    // prints without parens; the reverse needs them so the precedence
    // round-trips correctly.
    case Union(types)         => types.map(showUnionAlt).mkString(" | ")
    case Intersection(types)  => types.map(showInterAlt).mkString(" & ")
    case HigherKinded(name, arity) =>
      s"$name[${List.fill(arity)("_").mkString(", ")}]"
    case Refinement(base, members) =>
      val body = members.map(m => s"${m.kind} ${m.name}: ${m.sig.show}").mkString("; ")
      s"${showRefBase(base)} { $body }"
    case Match(scrutinee, cases) =>
      val body = cases.map(c => s"case ${c.pattern.show} => ${c.rhs.show}").mkString("; ")
      s"${showMatchScrutinee(scrutinee)} match { $body }"
    case EffectRow(_, ops)    => s"{ ${ops.map(_.show).mkString(", ")} }"
    case Error(msg)           => s"<error: $msg>"

  /** Render a type that appears as the *parameter* of a unary function
   *  arrow.  Both `Function` and `Tuple` need an outer set of parens to
   *  disambiguate on the way back:
   *   - without parens, `Int => String => Boolean` is right-associative
   *     and would lose the (Int => String) grouping;
   *   - without an extra pair, `(Int, String) => Boolean` reads as a
   *     two-argument function rather than a unary one taking a tuple. */
  private def showFnParam(t: SType): String = t match
    case _: Function | _: Tuple | _: Union | _: Intersection => s"(${t.show})"
    case _                                                   => t.show

  /** Alternative of a top-level `Union`: a nested `Function` or another
   *  `Union` does not need parens (function arrows bind looser than `|`,
   *  and `|` is associative-by-flattening here).  A nested `Intersection`
   *  is fine without parens since `&` binds tighter than `|`. */
  private def showUnionAlt(t: SType): String = t match
    case _: Function => s"(${t.show})"
    case _           => t.show

  /** Alternative of an `Intersection`: a nested `Union` must be
   *  parenthesised since `&` binds tighter than `|`; a nested `Function`
   *  similarly needs parens since `=>` is even looser. */
  private def showInterAlt(t: SType): String = t match
    case _: Function | _: Union => s"(${t.show})"
    case _                      => t.show

  /** Base of a `Refinement`: needs parens when the base itself contains
   *  a looser-binding construct (function arrow, union, intersection,
   *  another refinement or match), otherwise the printed form would
   *  re-parse with the wrong grouping. */
  private def showRefBase(t: SType): String = t match
    case _: Function | _: Union | _: Intersection | _: Refinement | _: Match =>
      s"(${t.show})"
    case _ => t.show

  /** Scrutinee of a `Match`: same parenthesisation as a refinement base. */
  private def showMatchScrutinee(t: SType): String = t match
    case _: Function | _: Union | _: Intersection | _: Refinement | _: Match =>
      s"(${t.show})"
    case _ => t.show

  private def showEffects(row: EffectRow): String =
    if row.ops.isEmpty then ""
    else if row.ops.size == 1 then s" ! ${row.ops.head.show}"
    else s" ! (${row.ops.map(_.show).mkString(", ")})"

  def isError: Boolean = this match
    case Error(_) => true
    case _        => false

  def subst(m: scala.collection.immutable.IntMap[SType]): SType =
    if m.isEmpty then return this
    this match
      case Var(id)                  => m.getOrElse(id, this)
      case Named(_, Nil)            => this  // no type args → no type vars → invariant
      case Named(n, args)           => Named(n, args.map(_.subst(m)))
      case Function(params, result, effs) =>
        val newTail = effs.tail.flatMap(id => m.get(id).collect { case SType.Var(newId) => newId })
                                .orElse(effs.tail.filterNot(id => m.contains(id)))
        val newOps = effs.ops.map(op => EffectOp(op.name, op.args.map(_.subst(m))))
        Function(params.map(_.subst(m)), result.subst(m), EffectRow(newTail, newOps))
      case Tuple(elems)             => Tuple(elems.map(_.subst(m)))
      case Union(types)             => Union(types.map(_.subst(m)))
      case Intersection(types)      => Intersection(types.map(_.subst(m)))
      case Refinement(base, mem)    =>
        Refinement(base.subst(m), mem.map(rm => RefMember(rm.kind, rm.name, rm.sig.subst(m))))
      case Match(scrut, cs)         =>
        Match(scrut.subst(m), cs.map(c => MatchCase(c.pattern.subst(m), c.rhs.subst(m))))
      case _                        => this

  /** Short-circuit occurs check: true if unification variable `id` appears in this type.
   *  Avoids building a Set[Int] — returns as soon as the variable is found. */
  def containsFreeVar(id: Int): Boolean = this match
    case Var(i)                        => i == id
    case Named(_, args)                => args.exists(_.containsFreeVar(id))
    case Function(params, result, effs) =>
      params.exists(_.containsFreeVar(id)) || result.containsFreeVar(id) ||
      effs.tail.contains(id) ||
      effs.ops.exists(op => op.args.exists(_.containsFreeVar(id)))
    case Tuple(elems)                  => elems.exists(_.containsFreeVar(id))
    case Union(types)                  => types.exists(_.containsFreeVar(id))
    case Intersection(types)           => types.exists(_.containsFreeVar(id))
    case Refinement(base, mem)         => base.containsFreeVar(id) || mem.exists(_.sig.containsFreeVar(id))
    case Match(scrut, cs)              =>
      scrut.containsFreeVar(id) || cs.exists(c => c.pattern.containsFreeVar(id) || c.rhs.containsFreeVar(id))
    case _                             => false

  def freeVars: Set[Int] =
    val acc = scala.collection.mutable.BitSet.empty
    collectFreeVars(acc)
    acc.toSet

  private def collectFreeVars(into: scala.collection.mutable.BitSet): Unit = this match
    case Var(id)                        => into += id
    case Named(_, args)                 => args.foreach(_.collectFreeVars(into))
    case Function(params, result, effs) =>
      params.foreach(_.collectFreeVars(into))
      result.collectFreeVars(into)
      effs.tail.foreach(into += _)
      effs.ops.foreach(op => op.args.foreach(_.collectFreeVars(into)))
    case Tuple(elems)                   => elems.foreach(_.collectFreeVars(into))
    case Union(types)                   => types.foreach(_.collectFreeVars(into))
    case Intersection(types)            => types.foreach(_.collectFreeVars(into))
    case Refinement(base, mem)          =>
      base.collectFreeVars(into)
      mem.foreach(_.sig.collectFreeVars(into))
    case Match(scrut, cs)               =>
      scrut.collectFreeVars(into)
      cs.foreach { c => c.pattern.collectFreeVars(into); c.rhs.collectFreeVars(into) }
    case _                              => ()

object SType:
  /** Unit is the 0-tuple — the monoid identity for tuple concatenation. */
  val Unit: SType    = Tuple(Nil)
  val Boolean: SType = Named("Boolean", Nil)
  val Int: SType     = Named("Int", Nil)
  val Long: SType    = Named("Long", Nil)
  val Double: SType  = Named("Double", Nil)
  val String: SType  = Named("String", Nil)
  val Char: SType    = Named("Char", Nil)
  val Any: SType     = Named("Any", Nil)
  val Nothing: SType = Named("Nothing", Nil)
  val Null: SType    = Named("Null", Nil)

  /** Intern cache for zero-arg Named types.  Returns the same object for every
   *  call with the same name, so type annotations for e.g. `MyClass` produce
   *  a single allocation per distinct class name rather than one per use-site. */
  private val namedCache =
    new java.util.concurrent.ConcurrentHashMap[String, SType.Named]()

  /** Returns a canonical (interned) `Named(name, Nil)`. */
  def named0(name: String): SType.Named =
    namedCache.computeIfAbsent(name, n => Named(n, Nil))

  def list(elem: SType): SType        = Named("List", scala.List(elem))
  def option(elem: SType): SType      = Named("Option", scala.List(elem))
  def map(k: SType, v: SType): SType  = Named("Map", scala.List(k, v))

  /** Monoid concatenation of two tuple types.
   *  Flattens eagerly: both sides are expanded to their elem lists, then
   *  concatenated.  A 1-element result collapses to its element (so
   *  `() ++ A = A` and `A ++ () = A` satisfy the identity laws).
   *
   *  Non-tuple arguments are treated as 1-elem "virtual tuples":
   *    `Int ++ String  =  (Int, String)`
   *    `() ++ Int      =  Int`
   *    `(Int,) ++ ()   =  Int`
   */
  def tupleConcat(t1: SType, t2: SType): SType =
    def elems(t: SType): scala.List[SType] = t match
      case Tuple(es) => es
      case _         => scala.List(t)
    val combined = elems(t1) ++ elems(t2)
    combined match
      case Nil         => Tuple(Nil)         // () ++ () = ()
      case scala.List(t) => t               // singleton flattens to element
      case _           => Tuple(combined)

case class Symbol(name: String, tpe: SType, kind: SymbolKind, mutable: Boolean = false)

enum SymbolKind:
  case Val, Var, Def, Type, Class, Object, Trait, Enum, Param, TypeParam

case class TypeScheme(typeParams: List[Int], body: SType):
  def instantiate(fresh: () => Int): SType =
    if typeParams.isEmpty then body
    else body.subst(scala.collection.immutable.IntMap.from(typeParams.map(p => p -> SType.Var(fresh()))))

class Scope(val parent: Option[Scope] = None, val name: String = "<root>"):
  private val symbols = collection.mutable.Map[String, Symbol]()
  private val types   = collection.mutable.Map[String, TypeScheme]()

  def define(sym: Symbol): Unit                  = symbols(sym.name) = sym
  def defineType(n: String, s: TypeScheme): Unit = types(n) = s

  def lookup(n: String): Option[Symbol] =
    var scope: Scope = this
    while scope != null do
      val hit = scope.symbols.get(n)
      if hit.isDefined then return hit
      scope = scope.parent.orNull
    None

  def lookupType(n: String): Option[TypeScheme] =
    var scope: Scope = this
    while scope != null do
      val hit = scope.types.get(n)
      if hit.isDefined then return hit
      scope = scope.parent.orNull
    None

  def child(childName: String): Scope = Scope(Some(this), childName)

enum Constraint:
  case Equal(lhs: SType, rhs: SType)
  case Subtype(sub: SType, sup: SType)

enum UnifyResult:
  case Success(subst: Map[Int, SType])
  case Failure(msg: String)

object Unifier:
  def unify(constraints: List[Constraint]): UnifyResult =
    var subst: scala.collection.immutable.IntMap[SType] = scala.collection.immutable.IntMap.empty
    var error = Option.empty[String]

    def solve(t1: SType, t2: SType): Unit =
      if error.isDefined then return
      val s1 = t1.subst(subst)
      val s2 = t2.subst(subst)
      (s1, s2) match
        case (a, b) if a == b => ()
        case (SType.Var(id), t) if !t.containsFreeVar(id) => subst = subst.updated(id, t)
        case (t, SType.Var(id)) if !t.containsFreeVar(id) => subst = subst.updated(id, t)
        case (SType.Var(id), _) => error = Some(s"Occurs check failed: ${s1.show}")
        case (SType.Named(n1, a1), SType.Named(n2, a2)) if n1 == n2 && a1.length == a2.length =>
          a1.zip(a2).foreach { case (x, y) => solve(x, y) }
        case (SType.Function(p1, r1, e1), SType.Function(p2, r2, e2))
            if p1.length == p2.length =>
          p1.zip(p2).foreach { case (x, y) => solve(x, y) }
          solve(r1, r2)
          solveEffectRow(e1, e2)
        // Unit (0-tuple) is the identity — unifies with anything via the monoid laws.
        case (SType.Tuple(Nil), _) | (_, SType.Tuple(Nil)) => ()
        // 1-tuple is isomorphic to its element: (A,) ≅ A.
        case (SType.Tuple(scala.List(t)), other) => solve(t, other)
        case (other, SType.Tuple(scala.List(t))) => solve(other, t)
        case (SType.Tuple(e1), SType.Tuple(e2)) if e1.length == e2.length =>
          e1.zip(e2).foreach { case (x, y) => solve(x, y) }
        case (SType.Nothing, _) | (_, SType.Any) => ()
        case _ => error = Some(s"Cannot unify ${s1.show} with ${s2.show}")

    def solveEffectRow(e1: SType.EffectRow, e2: SType.EffectRow): Unit =
      if error.isDefined then return
      def unifyOp(op1: EffectOp, op2: EffectOp): Unit =
        if op1.name != op2.name then
          error = Some(s"Effect op name mismatch: ${op1.name} ≠ ${op2.name}")
        else if op1.args.length != op2.args.length then
          error = Some(s"Effect op '${op1.name}' arity mismatch: ${op1.args.length} vs ${op2.args.length}")
        else
          op1.args.zip(op2.args).foreach { case (a, b) => solve(a, b) }
      def matchByName(smaller: Set[EffectOp], larger: Set[EffectOp]): Unit =
        for op <- smaller do
          larger.find(_.name == op.name) match
            case Some(other) => unifyOp(op, other)
            case None => error = Some(s"Effect '${op.name}' not in row {${larger.map(_.show).mkString(", ")}}")
      (e1.tail, e2.tail) match
        case (None, None) =>
          val names1 = e1.ops.map(_.name)
          val names2 = e2.ops.map(_.name)
          if names1 != names2 then
            error = Some(s"Effect row mismatch: {${e1.ops.map(_.show).mkString(", ")}} ≠ {${e2.ops.map(_.show).mkString(", ")}}")
          else
            matchByName(e1.ops, e2.ops)
        case (Some(_), None) =>
          if !(e2.ops.map(_.name) subsetOf e1.ops.map(_.name)) then
            error = Some(s"Effect row mismatch: open {${e1.ops.map(_.show).mkString(", ")}} vs closed {${e2.ops.map(_.show).mkString(", ")}}")
          else
            matchByName(e2.ops, e1.ops)
        case (None, Some(_)) =>
          if !(e1.ops.map(_.name) subsetOf e2.ops.map(_.name)) then
            error = Some(s"Effect row mismatch: closed {${e1.ops.map(_.show).mkString(", ")}} vs open {${e2.ops.map(_.show).mkString(", ")}}")
          else
            matchByName(e1.ops, e2.ops)
        case (Some(_), Some(_)) =>
          ()

    constraints.foreach {
      case Constraint.Equal(t1, t2)   => solve(t1, t2)
      case Constraint.Subtype(s1, s2) => solve(s1, s2)
    }
    error.fold(UnifyResult.Success(subst))(UnifyResult.Failure(_))
