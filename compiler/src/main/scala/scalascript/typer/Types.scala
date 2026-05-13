package scalascript.typer

import scalascript.ast.{Type as AstType, TypeParam, TypeBounds, Variance}

/** Internal type representation for type checking */
enum SType:
  case Named(name: String, args: List[SType])
  case Var(id: Int)  // Type variable for inference
  case Function(params: List[SType], result: SType)
  case Tuple(elems: List[SType])
  case Union(types: List[SType])
  case Intersection(types: List[SType])
  case Error(msg: String)

  def show: String = this match
    case Named(name, Nil) => name
    case Named(name, args) => s"$name[${args.map(_.show).mkString(", ")}]"
    case Var(id) => s"?$id"
    case Function(List(p), r) => s"${p.show} => ${r.show}"
    case Function(params, r) => s"(${params.map(_.show).mkString(", ")}) => ${r.show}"
    case Tuple(elems) => s"(${elems.map(_.show).mkString(", ")})"
    case Union(types) => types.map(_.show).mkString(" | ")
    case Intersection(types) => types.map(_.show).mkString(" & ")
    case Error(msg) => s"<error: $msg>"

  def isError: Boolean = this match
    case Error(_) => true
    case _ => false

  /** Substitute type variables */
  def subst(mapping: Map[Int, SType]): SType = this match
    case Var(id) => mapping.getOrElse(id, this)
    case Named(name, args) => Named(name, args.map(_.subst(mapping)))
    case Function(params, result) => Function(params.map(_.subst(mapping)), result.subst(mapping))
    case Tuple(elems) => Tuple(elems.map(_.subst(mapping)))
    case Union(types) => Union(types.map(_.subst(mapping)))
    case Intersection(types) => Intersection(types.map(_.subst(mapping)))
    case _ => this

  /** Collect free type variables */
  def freeVars: Set[Int] = this match
    case Var(id) => Set(id)
    case Named(_, args) => args.flatMap(_.freeVars).toSet
    case Function(params, result) => params.flatMap(_.freeVars).toSet ++ result.freeVars
    case Tuple(elems) => elems.flatMap(_.freeVars).toSet
    case Union(types) => types.flatMap(_.freeVars).toSet
    case Intersection(types) => types.flatMap(_.freeVars).toSet
    case _ => Set.empty

object SType:
  // Primitive types
  val Unit: SType = Named("Unit", Nil)
  val Boolean: SType = Named("Boolean", Nil)
  val Int: SType = Named("Int", Nil)
  val Long: SType = Named("Long", Nil)
  val Double: SType = Named("Double", Nil)
  val String: SType = Named("String", Nil)
  val Char: SType = Named("Char", Nil)
  val Any: SType = Named("Any", Nil)
  val Nothing: SType = Named("Nothing", Nil)
  val Null: SType = Named("Null", Nil)

  def list(elem: SType): SType = Named("List", scala.List(elem))
  def option(elem: SType): SType = Named("Option", scala.List(elem))
  def map(key: SType, value: SType): SType = Named("Map", scala.List(key, value))
  def set(elem: SType): SType = Named("Set", scala.List(elem))

  /** Convert AST type to SType */
  def fromAst(t: AstType): SType = t match
    case AstType.Named(name, args, _) => Named(name, args.map(fromAst))
    case AstType.Function(params, result, _) => Function(params.map(fromAst), fromAst(result))
    case AstType.Tuple(elems, _) => Tuple(elems.map(fromAst))
    case AstType.Union(types, _) => Union(types.map(fromAst))
    case AstType.Intersection(types, _) => Intersection(types.map(fromAst))
    case AstType.Wildcard(_, _) => Any // Simplified

/** Symbol representing a definition */
case class Symbol(
  name: String,
  tpe: SType,
  kind: SymbolKind,
  mutable: Boolean = false
)

enum SymbolKind:
  case Val, Var, Def, Type, Class, Object, Trait, Enum, Param, TypeParam

/** Type scheme for polymorphic types */
case class TypeScheme(typeParams: List[Int], body: SType):
  def instantiate(freshVars: () => Int): SType =
    if typeParams.isEmpty then body
    else
      val mapping = typeParams.map(p => p -> SType.Var(freshVars())).toMap
      body.subst(mapping)

/** Scope for name resolution */
class Scope(
  val parent: Option[Scope] = None,
  val name: String = "<root>"
):
  private val symbols = collection.mutable.Map[String, Symbol]()
  private val types = collection.mutable.Map[String, TypeScheme]()

  def define(sym: Symbol): Unit =
    symbols(sym.name) = sym

  def defineType(name: String, scheme: TypeScheme): Unit =
    types(name) = scheme

  def lookup(name: String): Option[Symbol] =
    symbols.get(name).orElse(parent.flatMap(_.lookup(name)))

  def lookupType(name: String): Option[TypeScheme] =
    types.get(name).orElse(parent.flatMap(_.lookupType(name)))

  def child(childName: String): Scope = Scope(Some(this), childName)

  def allSymbols: Map[String, Symbol] =
    parent.map(_.allSymbols).getOrElse(Map.empty) ++ symbols.toMap

/** Constraint for type inference */
enum Constraint:
  case Equal(lhs: SType, rhs: SType)
  case Subtype(sub: SType, sup: SType)

/** Unification result */
enum UnifyResult:
  case Success(subst: Map[Int, SType])
  case Failure(msg: String)

object Unifier:
  def unify(constraints: List[Constraint]): UnifyResult =
    var subst = Map.empty[Int, SType]

    def solve(t1: SType, t2: SType): Option[String] =
      val s1 = t1.subst(subst)
      val s2 = t2.subst(subst)

      (s1, s2) match
        case (a, b) if a == b => None

        case (SType.Var(id), t) if !t.freeVars.contains(id) =>
          subst = subst + (id -> t)
          None

        case (t, SType.Var(id)) if !t.freeVars.contains(id) =>
          subst = subst + (id -> t)
          None

        case (SType.Var(id), t) =>
          Some(s"Occurs check failed: ${s1.show} in ${t.show}")

        case (SType.Named(n1, a1), SType.Named(n2, a2)) if n1 == n2 && a1.length == a2.length =>
          a1.zip(a2).foldLeft[Option[String]](None) {
            case (None, (t1, t2)) => solve(t1, t2)
            case (err, _) => err
          }

        case (SType.Function(p1, r1), SType.Function(p2, r2)) if p1.length == p2.length =>
          val paramErrs = p1.zip(p2).foldLeft[Option[String]](None) {
            case (None, (t1, t2)) => solve(t1, t2)
            case (err, _) => err
          }
          paramErrs.orElse(solve(r1, r2))

        case (SType.Tuple(e1), SType.Tuple(e2)) if e1.length == e2.length =>
          e1.zip(e2).foldLeft[Option[String]](None) {
            case (None, (t1, t2)) => solve(t1, t2)
            case (err, _) => err
          }

        case (SType.Nothing, _) => None  // Nothing is subtype of everything
        case (_, SType.Any) => None      // Everything is subtype of Any

        case _ =>
          Some(s"Cannot unify ${s1.show} with ${s2.show}")

    for c <- constraints do
      c match
        case Constraint.Equal(t1, t2) =>
          solve(t1, t2) match
            case Some(err) => return UnifyResult.Failure(err)
            case None => ()
        case Constraint.Subtype(sub, sup) =>
          // Simplified: treat subtype as equality for now
          solve(sub, sup) match
            case Some(err) => return UnifyResult.Failure(err)
            case None => ()

    UnifyResult.Success(subst)
