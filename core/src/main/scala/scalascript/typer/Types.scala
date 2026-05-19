package scalascript.typer

/** Internal type representation for type-checking.
 *  Kept separate from the AST so the typer can introduce unification variables
 *  and apply substitutions without modifying source trees.
 */
enum SType:
  case Named(name: String, args: List[SType])
  case Var(id: Int)
  case Function(params: List[SType], result: SType)
  case Tuple(elems: List[SType])
  case Union(types: List[SType])
  case Intersection(types: List[SType])
  case Error(msg: String)

  def show: String = this match
    case Named(name, Nil)     => name
    case Named(name, args)    => s"$name[${args.map(_.show).mkString(", ")}]"
    case Var(id)              => s"?$id"
    // Function-type parameters and tuples must be parenthesised on the
    // left of `=>` so the printed form round-trips: `Int => String => Boolean`
    // is right-associative, so a function-as-param needs explicit parens.
    case Function(List(p), r) => s"${showFnParam(p)} => ${r.show}"
    case Function(params, r)  => s"(${params.map(_.show).mkString(", ")}) => ${r.show}"
    case Tuple(elems)         => s"(${elems.map(_.show).mkString(", ")})"
    case Union(types)         => types.map(_.show).mkString(" | ")
    case Intersection(types)  => types.map(_.show).mkString(" & ")
    case Error(msg)           => s"<error: $msg>"

  /** Render a type that appears as the *parameter* of a unary function
   *  arrow.  Both `Function` and `Tuple` need an outer set of parens to
   *  disambiguate on the way back:
   *   - without parens, `Int => String => Boolean` is right-associative
   *     and would lose the (Int => String) grouping;
   *   - without an extra pair, `(Int, String) => Boolean` reads as a
   *     two-argument function rather than a unary one taking a tuple. */
  private def showFnParam(t: SType): String = t match
    case _: Function | _: Tuple => s"(${t.show})"
    case _                      => t.show

  def isError: Boolean = this match
    case Error(_) => true
    case _        => false

  def subst(m: Map[Int, SType]): SType = this match
    case Var(id)                  => m.getOrElse(id, this)
    case Named(n, args)           => Named(n, args.map(_.subst(m)))
    case Function(params, result) => Function(params.map(_.subst(m)), result.subst(m))
    case Tuple(elems)             => Tuple(elems.map(_.subst(m)))
    case Union(types)             => Union(types.map(_.subst(m)))
    case Intersection(types)      => Intersection(types.map(_.subst(m)))
    case _                        => this

  def freeVars: Set[Int] = this match
    case Var(id)                  => Set(id)
    case Named(_, args)           => args.flatMap(_.freeVars).toSet
    case Function(params, result) => params.flatMap(_.freeVars).toSet ++ result.freeVars
    case Tuple(elems)             => elems.flatMap(_.freeVars).toSet
    case Union(types)             => types.flatMap(_.freeVars).toSet
    case Intersection(types)      => types.flatMap(_.freeVars).toSet
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
        case (SType.Function(p1, r1), SType.Function(p2, r2)) if p1.length == p2.length =>
          p1.zip(p2).foreach { case (x, y) => solve(x, y) }; solve(r1, r2)
        case (SType.Tuple(e1), SType.Tuple(e2)) if e1.length == e2.length =>
          e1.zip(e2).foreach { case (x, y) => solve(x, y) }
        case (SType.Nothing, _) | (_, SType.Any) => ()
        case _ => error = Some(s"Cannot unify ${s1.show} with ${s2.show}")

    constraints.foreach {
      case Constraint.Equal(t1, t2)   => solve(t1, t2)
      case Constraint.Subtype(s1, s2) => solve(s1, s2)
    }
    error.fold(UnifyResult.Success(subst))(UnifyResult.Failure(_))
