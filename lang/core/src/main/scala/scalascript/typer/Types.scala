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

/** Internal type representation for type-checking.
 *  Kept separate from the AST so the typer can introduce unification variables
 *  and apply substitutions without modifying source trees.
 */
enum SType:
  case Named(name: String, args: List[SType])
  case Var(id: Int)
  case Function(params: List[SType], result: SType, effects: EffectRow = EffectRow(None, Set.empty))
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
  /** An effect row `{ Eff1, Eff2, … }` with an optional open tail variable.
   *  Only appears as the `effects` field of `SType.Function`; never stands
   *  alone as a value type. */
  case EffectRow(tail: Option[Int], ops: Set[String])
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
    case EffectRow(_, ops)    => s"{ ${ops.mkString(", ")} }"
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
    else if row.ops.size == 1 then s" ! ${row.ops.head}"
    else s" ! (${row.ops.mkString(", ")})"

  def isError: Boolean = this match
    case Error(_) => true
    case _        => false

  def subst(m: Map[Int, SType]): SType = this match
    case Var(id)                  => m.getOrElse(id, this)
    case Named(n, args)           => Named(n, args.map(_.subst(m)))
    case Function(params, result, effs) =>
      val newTail = effs.tail.flatMap(id => m.get(id).collect { case SType.Var(newId) => newId })
                              .orElse(effs.tail.filterNot(id => m.contains(id)))
      Function(params.map(_.subst(m)), result.subst(m), effs.copy(tail = newTail))
    case Tuple(elems)             => Tuple(elems.map(_.subst(m)))
    case Union(types)             => Union(types.map(_.subst(m)))
    case Intersection(types)      => Intersection(types.map(_.subst(m)))
    case Refinement(base, mem)    =>
      Refinement(base.subst(m), mem.map(rm => RefMember(rm.kind, rm.name, rm.sig.subst(m))))
    case Match(scrut, cs)         =>
      Match(scrut.subst(m), cs.map(c => MatchCase(c.pattern.subst(m), c.rhs.subst(m))))
    case _                        => this

  def freeVars: Set[Int] = this match
    case Var(id)                  => Set(id)
    case Named(_, args)           => args.flatMap(_.freeVars).toSet
    case Function(params, result, effs) =>
      params.flatMap(_.freeVars).toSet ++ result.freeVars ++ effs.tail.toSet
    case Tuple(elems)             => elems.flatMap(_.freeVars).toSet
    case Union(types)             => types.flatMap(_.freeVars).toSet
    case Intersection(types)      => types.flatMap(_.freeVars).toSet
    case Refinement(base, mem)    => base.freeVars ++ mem.flatMap(_.sig.freeVars).toSet
    case Match(scrut, cs)         =>
      scrut.freeVars ++ cs.flatMap(c => c.pattern.freeVars ++ c.rhs.freeVars).toSet
    case _                        => Set.empty

object SType:
  val Unit: SType    = Named("Unit", Nil)
  val Boolean: SType = Named("Boolean", Nil)
  val Int: SType     = Named("Int", Nil)
  val Long: SType    = Named("Long", Nil)
  val Double: SType  = Named("Double", Nil)
  val String: SType  = Named("String", Nil)
  val Char: SType    = Named("Char", Nil)
  val Any: SType     = Named("Any", Nil)
  val Nothing: SType = Named("Nothing", Nil)
  val Null: SType    = Named("Null", Nil)

  def list(elem: SType): SType        = Named("List", scala.List(elem))
  def option(elem: SType): SType      = Named("Option", scala.List(elem))
  def map(k: SType, v: SType): SType  = Named("Map", scala.List(k, v))

case class Symbol(name: String, tpe: SType, kind: SymbolKind, mutable: Boolean = false)

enum SymbolKind:
  case Val, Var, Def, Type, Class, Object, Trait, Enum, Param, TypeParam

case class TypeScheme(typeParams: List[Int], body: SType):
  def instantiate(fresh: () => Int): SType =
    if typeParams.isEmpty then body
    else body.subst(typeParams.map(p => p -> SType.Var(fresh())).toMap)

class Scope(val parent: Option[Scope] = None, val name: String = "<root>"):
  private val symbols = collection.mutable.Map[String, Symbol]()
  private val types   = collection.mutable.Map[String, TypeScheme]()

  def define(sym: Symbol): Unit                  = symbols(sym.name) = sym
  def defineType(n: String, s: TypeScheme): Unit = types(n) = s
  def lookup(n: String): Option[Symbol]          = symbols.get(n).orElse(parent.flatMap(_.lookup(n)))
  def lookupType(n: String): Option[TypeScheme]  = types.get(n).orElse(parent.flatMap(_.lookupType(n)))
  def child(childName: String): Scope            = Scope(Some(this), childName)

enum Constraint:
  case Equal(lhs: SType, rhs: SType)
  case Subtype(sub: SType, sup: SType)

enum UnifyResult:
  case Success(subst: Map[Int, SType])
  case Failure(msg: String)

object Unifier:
  def unify(constraints: List[Constraint]): UnifyResult =
    var subst = Map.empty[Int, SType]
    var error = Option.empty[String]

    def solve(t1: SType, t2: SType): Unit =
      if error.isDefined then return
      val s1 = t1.subst(subst)
      val s2 = t2.subst(subst)
      (s1, s2) match
        case (a, b) if a == b => ()
        case (SType.Var(id), t) if !t.freeVars.contains(id) => subst = subst + (id -> t)
        case (t, SType.Var(id)) if !t.freeVars.contains(id) => subst = subst + (id -> t)
        case (SType.Var(id), _) => error = Some(s"Occurs check failed: ${s1.show}")
        case (SType.Named(n1, a1), SType.Named(n2, a2)) if n1 == n2 && a1.length == a2.length =>
          a1.zip(a2).foreach { case (x, y) => solve(x, y) }
        case (SType.Function(p1, r1, e1), SType.Function(p2, r2, e2))
            if p1.length == p2.length =>
          p1.zip(p2).foreach { case (x, y) => solve(x, y) }
          solve(r1, r2)
          solveEffectRow(e1, e2)
        case (SType.Tuple(e1), SType.Tuple(e2)) if e1.length == e2.length =>
          e1.zip(e2).foreach { case (x, y) => solve(x, y) }
        case (SType.Nothing, _) | (_, SType.Any) => ()
        case _ => error = Some(s"Cannot unify ${s1.show} with ${s2.show}")

    def solveEffectRow(e1: SType.EffectRow, e2: SType.EffectRow): Unit =
      if error.isDefined then return
      (e1.tail, e2.tail) match
        case (None, None) =>
          if e1.ops != e2.ops then
            error = Some(s"Effect row mismatch: {${e1.ops.mkString(", ")}} ≠ {${e2.ops.mkString(", ")}}")
        case (Some(_), None) =>
          if !(e2.ops subsetOf e1.ops) then
            error = Some(s"Effect row mismatch: open {${e1.ops.mkString(", ")}} vs closed {${e2.ops.mkString(", ")}}")
        case (None, Some(_)) =>
          if !(e1.ops subsetOf e2.ops) then
            error = Some(s"Effect row mismatch: closed {${e1.ops.mkString(", ")}} vs open {${e2.ops.mkString(", ")}}")
        case (Some(_), Some(_)) =>
          ()

    constraints.foreach {
      case Constraint.Equal(t1, t2)   => solve(t1, t2)
      case Constraint.Subtype(s1, s2) => solve(s1, s2)
    }
    error.fold(UnifyResult.Success(subst))(UnifyResult.Failure(_))
