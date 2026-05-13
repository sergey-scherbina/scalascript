package scalascript.typer

import scalascript.ast.*
import scala.collection.mutable.ListBuffer

/** Type checker for ScalaScript */
class Typer:
  private var nextVarId: Int = 0
  private val errors = ListBuffer[TypeError]()
  private val constraints = ListBuffer[Constraint]()

  private def freshVar(): SType =
    nextVarId += 1
    SType.Var(nextVarId)

  def typeCheck(module: Module): TypedModule =
    val rootScope = createPrelude()
    val typedSections = module.sections.map(s => typeCheckSection(s, rootScope))

    // Solve constraints
    Unifier.unify(constraints.toList) match
      case UnifyResult.Success(subst) =>
        // Apply substitution to all types
        ()
      case UnifyResult.Failure(msg) =>
        errors += TypeError(msg, None)

    TypedModule(
      name = module.manifest.flatMap(_.name).getOrElse("<anonymous>"),
      version = module.manifest.flatMap(_.version).getOrElse("0.0.0"),
      sections = typedSections,
      errors = errors.toList
    )

  private def createPrelude(): Scope =
    val scope = Scope(name = "<prelude>")

    // Define built-in types
    scope.defineType("Unit", TypeScheme(Nil, SType.Unit))
    scope.defineType("Boolean", TypeScheme(Nil, SType.Boolean))
    scope.defineType("Int", TypeScheme(Nil, SType.Int))
    scope.defineType("Long", TypeScheme(Nil, SType.Long))
    scope.defineType("Double", TypeScheme(Nil, SType.Double))
    scope.defineType("String", TypeScheme(Nil, SType.String))
    scope.defineType("Char", TypeScheme(Nil, SType.Char))
    scope.defineType("Any", TypeScheme(Nil, SType.Any))
    scope.defineType("Nothing", TypeScheme(Nil, SType.Nothing))

    // Define built-in functions
    scope.define(Symbol("println", SType.Function(List(SType.Any), SType.Unit), SymbolKind.Def))
    scope.define(Symbol("print", SType.Function(List(SType.Any), SType.Unit), SymbolKind.Def))
    scope.define(Symbol("assert", SType.Function(List(SType.Boolean), SType.Unit), SymbolKind.Def))
    scope.define(Symbol("require", SType.Function(List(SType.Boolean), SType.Unit), SymbolKind.Def))

    scope

  private def typeCheckSection(section: Section, scope: Scope): TypedSection =
    val sectionScope = scope.child(section.heading.text)

    // Type check all content
    val typedContent = section.content.flatMap {
      case cb: Content.CodeBlock => typeCheckCodeBlock(cb, sectionScope)
      case imp: Content.Import => typeCheckImport(imp, sectionScope); None
      case _ => None
    }

    // Type check subsections
    val typedSubsections = section.subsections.map(s => typeCheckSection(s, sectionScope))

    TypedSection(
      name = section.heading.text,
      level = section.heading.level,
      definitions = typedContent,
      subsections = typedSubsections,
      scope = sectionScope
    )

  private def typeCheckCodeBlock(cb: Content.CodeBlock, scope: Scope): List[TypedDef] =
    cb.statements.flatMap(s => typeCheckStatement(s, scope))

  private def typeCheckImport(imp: Content.Import, scope: Scope): Unit =
    // For now, just register the import bindings
    for binding <- imp.bindings do
      val name = binding.alias.getOrElse(binding.name)
      // Create a placeholder type for imported items
      scope.define(Symbol(name, SType.Any, SymbolKind.Val))

  private def typeCheckStatement(stmt: Statement, scope: Scope): Option[TypedDef] =
    stmt match
      case Statement.ValDef(name, tpeOpt, rhs, _) =>
        val rhsType = inferExpr(rhs, scope)
        val declaredType = tpeOpt.map(SType.fromAst)
        val finalType = declaredType match
          case Some(t) =>
            constraints += Constraint.Equal(rhsType, t)
            t
          case None => rhsType
        scope.define(Symbol(name, finalType, SymbolKind.Val))
        Some(TypedDef.Val(name, finalType, TypedExpr.from(rhs, rhsType)))

      case Statement.VarDef(name, tpeOpt, rhs, _) =>
        val rhsType = inferExpr(rhs, scope)
        val declaredType = tpeOpt.map(SType.fromAst)
        val finalType = declaredType match
          case Some(t) =>
            constraints += Constraint.Equal(rhsType, t)
            t
          case None => rhsType
        scope.define(Symbol(name, finalType, SymbolKind.Var, mutable = true))
        Some(TypedDef.Var(name, finalType, TypedExpr.from(rhs, rhsType)))

      case Statement.DefDef(name, tparams, paramss, retTpeOpt, body, _) =>
        // Create fresh type variables for type parameters
        val tparamVars = tparams.map(tp => tp.name -> freshVar()).toMap
        val defScope = scope.child(name)

        // Add type parameters to scope
        tparams.foreach { tp =>
          defScope.defineType(tp.name, TypeScheme(Nil, tparamVars(tp.name)))
        }

        // Add parameters to scope and build function type
        val paramTypes = paramss.flatten.map { p =>
          val pType = p.tpe.map(SType.fromAst).getOrElse(freshVar())
          defScope.define(Symbol(p.name, pType, SymbolKind.Param))
          pType
        }

        val bodyType = inferExpr(body, defScope)
        val declaredRetType = retTpeOpt.map(SType.fromAst)
        declaredRetType.foreach(t => constraints += Constraint.Equal(bodyType, t))

        val funcType = if paramTypes.isEmpty then bodyType
          else SType.Function(paramTypes, declaredRetType.getOrElse(bodyType))

        scope.define(Symbol(name, funcType, SymbolKind.Def))
        Some(TypedDef.Def(name, tparams.map(_.name), paramss.flatten.map(_.name), funcType, TypedExpr.from(body, bodyType)))

      case Statement.ClassDef(name, tparams, params, parents, body, isCase, _) =>
        val classType = SType.Named(name, tparams.map(_ => freshVar()))
        scope.defineType(name, TypeScheme(Nil, classType))

        // Constructor
        val paramTypes = params.map(p => SType.fromAst(p.tpe.get))
        val ctorType = SType.Function(paramTypes, classType)
        scope.define(Symbol(name, ctorType, SymbolKind.Class))

        Some(TypedDef.Class(name, tparams.map(_.name), params.map(p => (p.name, SType.fromAst(p.tpe.get))), isCase))

      case Statement.EnumDef(name, tparams, parents, cases, _) =>
        val enumType = SType.Named(name, tparams.map(_ => freshVar()))
        scope.defineType(name, TypeScheme(Nil, enumType))

        // Each case is a constructor
        for c <- cases do
          val caseType = if c.params.isEmpty then enumType
            else SType.Function(c.params.map(p => SType.fromAst(p.tpe.get)), enumType)
          scope.define(Symbol(c.name, caseType, SymbolKind.Val))

        Some(TypedDef.Enum(name, tparams.map(_.name), cases.map(_.name)))

      case Statement.TypeAlias(name, tparams, rhs, _) =>
        val rhsType = SType.fromAst(rhs)
        scope.defineType(name, TypeScheme(Nil, rhsType))
        Some(TypedDef.TypeAlias(name, tparams.map(_.name), rhsType))

      case Statement.ObjectDef(name, parents, body, isCase, _) =>
        val objType = SType.Named(name + ".type", Nil)
        scope.define(Symbol(name, objType, SymbolKind.Object))
        Some(TypedDef.Object(name))

      case Statement.TraitDef(name, tparams, parents, body, _) =>
        val traitType = SType.Named(name, tparams.map(_ => freshVar()))
        scope.defineType(name, TypeScheme(Nil, traitType))
        Some(TypedDef.Trait(name, tparams.map(_.name)))

      case Statement.ExprStmt(expr, _) =>
        inferExpr(expr, scope)
        None

  private def inferExpr(expr: Expr, scope: Scope): SType =
    expr match
      case Expr.Literal(lit, _) => inferLiteral(lit)

      case Expr.Ident(name, _) =>
        scope.lookup(name) match
          case Some(sym) => sym.tpe
          case None =>
            errors += TypeError(s"Unknown identifier: $name", None)
            SType.Error(s"Unknown: $name")

      case Expr.Select(qual, name, _) =>
        val qualType = inferExpr(qual, scope)
        // Simplified: just return Any for field access
        // Full implementation would look up the member
        freshVar()

      case Expr.Apply(fn, args, _) =>
        val fnType = inferExpr(fn, scope)
        val argTypes = args.map(inferExpr(_, scope))

        fnType match
          case SType.Function(params, result) =>
            if params.length == argTypes.length then
              params.zip(argTypes).foreach { (p, a) =>
                constraints += Constraint.Equal(p, a)
              }
              result
            else
              errors += TypeError(s"Wrong number of arguments: expected ${params.length}, got ${argTypes.length}", None)
              SType.Error("arity mismatch")

          case SType.Var(_) =>
            val resultVar = freshVar()
            constraints += Constraint.Equal(fnType, SType.Function(argTypes, resultVar))
            resultVar

          case _ =>
            errors += TypeError(s"Not a function: ${fnType.show}", None)
            SType.Error("not a function")

      case Expr.Lambda(params, body, _) =>
        val lambdaScope = scope.child("<lambda>")
        val paramTypes = params.map { p =>
          val pType = p.tpe.map(SType.fromAst).getOrElse(freshVar())
          lambdaScope.define(Symbol(p.name, pType, SymbolKind.Param))
          pType
        }
        val bodyType = inferExpr(body, lambdaScope)
        SType.Function(paramTypes, bodyType)

      case Expr.If(cond, thenp, elsep, _) =>
        val condType = inferExpr(cond, scope)
        constraints += Constraint.Equal(condType, SType.Boolean)
        val thenType = inferExpr(thenp, scope)
        elsep match
          case Some(e) =>
            val elseType = inferExpr(e, scope)
            val resultVar = freshVar()
            constraints += Constraint.Equal(thenType, resultVar)
            constraints += Constraint.Equal(elseType, resultVar)
            resultVar
          case None =>
            SType.Unit

      case Expr.Match(scrutinee, cases, _) =>
        val scrutType = inferExpr(scrutinee, scope)
        val resultVar = freshVar()

        for c <- cases do
          val caseScope = scope.child("<case>")
          checkPattern(c.pattern, scrutType, caseScope)
          c.guard.foreach { g =>
            val guardType = inferExpr(g, caseScope)
            constraints += Constraint.Equal(guardType, SType.Boolean)
          }
          val bodyType = inferExpr(c.body, caseScope)
          constraints += Constraint.Equal(bodyType, resultVar)

        resultVar

      case Expr.Block(stats, expr, _) =>
        val blockScope = scope.child("<block>")
        stats.foreach(s => typeCheckStatement(s, blockScope))
        inferExpr(expr, blockScope)

      case Expr.Tuple(elems, _) =>
        SType.Tuple(elems.map(inferExpr(_, scope)))

      case Expr.New(tpe, args, _) =>
        SType.fromAst(tpe)

      case Expr.Infix(lhs, op, rhs, _) =>
        val lhsType = inferExpr(lhs, scope)
        val rhsType = inferExpr(rhs, scope)

        op match
          case "+" | "-" | "*" | "/" | "%" =>
            constraints += Constraint.Equal(lhsType, rhsType)
            lhsType
          case "<" | ">" | "<=" | ">=" =>
            constraints += Constraint.Equal(lhsType, rhsType)
            SType.Boolean
          case "==" | "!=" =>
            SType.Boolean
          case "&&" | "||" =>
            constraints += Constraint.Equal(lhsType, SType.Boolean)
            constraints += Constraint.Equal(rhsType, SType.Boolean)
            SType.Boolean
          case "::" =>
            SType.list(lhsType)
          case _ =>
            // Treat as method call
            freshVar()

      case Expr.Prefix(op, e, _) =>
        val eType = inferExpr(e, scope)
        op match
          case "!" =>
            constraints += Constraint.Equal(eType, SType.Boolean)
            SType.Boolean
          case "-" | "+" =>
            eType
          case "~" =>
            constraints += Constraint.Equal(eType, SType.Int)
            SType.Int
          case _ => eType

      case Expr.Ascription(e, tpe, _) =>
        val eType = inferExpr(e, scope)
        val declared = SType.fromAst(tpe)
        constraints += Constraint.Equal(eType, declared)
        declared

      case Expr.TypeApply(fn, targs, _) =>
        val fnType = inferExpr(fn, scope)
        // Simplified: ignore type arguments for now
        fnType

      case Expr.Interpolated(parts, args, _) =>
        args.foreach(inferExpr(_, scope))
        SType.String

  private def inferLiteral(lit: LiteralValue): SType = lit match
    case LiteralValue.IntLit(_) => SType.Int
    case LiteralValue.DoubleLit(_) => SType.Double
    case LiteralValue.StringLit(_) => SType.String
    case LiteralValue.CharLit(_) => SType.Char
    case LiteralValue.BoolLit(_) => SType.Boolean
    case LiteralValue.UnitLit => SType.Unit
    case LiteralValue.NullLit => SType.Null

  private def checkPattern(pattern: Pattern, expected: SType, scope: Scope): Unit =
    pattern match
      case Pattern.Wildcard(_) => ()

      case Pattern.Binding(name, inner, _) =>
        scope.define(Symbol(name, expected, SymbolKind.Val))
        inner.foreach(p => checkPattern(p, expected, scope))

      case Pattern.Literal(lit, _) =>
        val litType = inferLiteral(lit)
        constraints += Constraint.Equal(litType, expected)

      case Pattern.Constructor(name, args, _) =>
        scope.lookup(name) match
          case Some(sym) =>
            sym.tpe match
              case SType.Function(params, result) =>
                constraints += Constraint.Equal(result, expected)
                args.zip(params).foreach { (p, t) => checkPattern(p, t, scope) }
              case t =>
                constraints += Constraint.Equal(t, expected)
          case None =>
            errors += TypeError(s"Unknown constructor: $name", None)

      case Pattern.Tuple(elems, _) =>
        expected match
          case SType.Tuple(types) if types.length == elems.length =>
            elems.zip(types).foreach { (p, t) => checkPattern(p, t, scope) }
          case _ =>
            val elemTypes = elems.map(_ => freshVar())
            constraints += Constraint.Equal(expected, SType.Tuple(elemTypes))
            elems.zip(elemTypes).foreach { (p, t) => checkPattern(p, t, scope) }

      case Pattern.Typed(inner, tpe, _) =>
        val annotatedType = SType.fromAst(tpe)
        constraints += Constraint.Subtype(annotatedType, expected)
        checkPattern(inner, annotatedType, scope)

      case Pattern.Alternative(patterns, _) =>
        patterns.foreach(p => checkPattern(p, expected, scope))

case class TypeError(msg: String, span: Option[Span])

// ============================================
// Typed IR Output
// ============================================

case class TypedModule(
  name: String,
  version: String,
  sections: List[TypedSection],
  errors: List[TypeError]
):
  def hasErrors: Boolean = errors.nonEmpty

  def show: String =
    val sb = StringBuilder()
    sb ++= s"module $name v$version\n"
    if errors.nonEmpty then
      sb ++= s"Errors:\n"
      errors.foreach(e => sb ++= s"  - ${e.msg}\n")
    sections.foreach(s => sb ++= s.show(0))
    sb.toString

case class TypedSection(
  name: String,
  level: Int,
  definitions: List[TypedDef],
  subsections: List[TypedSection],
  scope: Scope
):
  def show(indent: Int): String =
    val prefix = "  " * indent
    val sb = StringBuilder()
    sb ++= s"$prefix${"#" * level} $name\n"
    definitions.foreach(d => sb ++= s"$prefix  ${d.show}\n")
    subsections.foreach(s => sb ++= s.show(indent + 1))
    sb.toString

enum TypedDef:
  case Val(name: String, tpe: SType, rhs: TypedExpr)
  case Var(name: String, tpe: SType, rhs: TypedExpr)
  case Def(name: String, tparams: List[String], params: List[String], tpe: SType, body: TypedExpr)
  case Class(name: String, tparams: List[String], params: List[(String, SType)], isCase: Boolean)
  case Enum(name: String, tparams: List[String], cases: List[String])
  case Object(name: String)
  case Trait(name: String, tparams: List[String])
  case TypeAlias(name: String, tparams: List[String], rhs: SType)

  def show: String = this match
    case Val(name, tpe, _) => s"val $name: ${tpe.show}"
    case Var(name, tpe, _) => s"var $name: ${tpe.show}"
    case Def(name, _, params, tpe, _) => s"def $name(${params.mkString(", ")}): ${tpe.show}"
    case Class(name, _, params, isCase) =>
      val prefix = if isCase then "case class" else "class"
      s"$prefix $name(${params.map((n, t) => s"$n: ${t.show}").mkString(", ")})"
    case Enum(name, _, cases) => s"enum $name { ${cases.mkString(", ")} }"
    case Object(name) => s"object $name"
    case Trait(name, _) => s"trait $name"
    case TypeAlias(name, _, rhs) => s"type $name = ${rhs.show}"

case class TypedExpr(ast: Expr, tpe: SType)

object TypedExpr:
  def from(expr: Expr, tpe: SType): TypedExpr = TypedExpr(expr, tpe)

object Typer:
  def typeCheck(module: Module): TypedModule =
    Typer().typeCheck(module)
