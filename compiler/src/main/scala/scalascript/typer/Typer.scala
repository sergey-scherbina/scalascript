package scalascript.typer

import scalascript.ast.*
import scala.collection.mutable.ListBuffer
import scala.meta.*

class Typer:
  private val errors = ListBuffer[TypeError]()

  def typeCheck(module: Module): TypedModule =
    val prelude  = createPrelude()
    val sections = module.sections.map(s => typeCheckSection(s, prelude))
    TypedModule(
      name     = module.manifest.flatMap(_.name).getOrElse("<anonymous>"),
      version  = module.manifest.flatMap(_.version).getOrElse("0.0.0"),
      sections = sections,
      errors   = errors.toList
    )

  private def createPrelude(): Scope =
    val s = Scope()
    // Variadic builtins — represented with a single Any param (treated as variadic)
    s.define(Symbol("println", SType.Function(List(SType.Any), SType.Unit),  SymbolKind.Def))
    s.define(Symbol("print",   SType.Function(List(SType.Any), SType.Unit),  SymbolKind.Def))
    s.define(Symbol("assert",  SType.Function(List(SType.Any), SType.Unit),  SymbolKind.Def))
    s.define(Symbol("require", SType.Function(List(SType.Any), SType.Unit),  SymbolKind.Def))
    s.define(Symbol("Some",    SType.Function(List(SType.Any), SType.option(SType.Any)), SymbolKind.Def))
    s.define(Symbol("None",    SType.option(SType.Nothing), SymbolKind.Val))
    // List / Map constructors are variadic — single Any param is the sentinel
    s.define(Symbol("List",    SType.Function(List(SType.Any), SType.list(SType.Any)), SymbolKind.Def))
    s.define(Symbol("Map",     SType.Function(List(SType.Any), SType.map(SType.Any, SType.Any)), SymbolKind.Def))
    s.define(Symbol("math",    SType.Named("math", Nil), SymbolKind.Object))
    s.define(Symbol("doc",     SType.Function(List(SType.Any), SType.Any), SymbolKind.Def))
    s.define(Symbol("render",  SType.Function(List(SType.Any), SType.Unit), SymbolKind.Def))
    s.define(Symbol("serve",   SType.Function(List(SType.Any), SType.Unit), SymbolKind.Def))
    s

  private def typeCheckSection(section: Section, parent: Scope): TypedSection =
    val scope = parent.child(section.heading.text)
    val defs  = section.content.flatMap(typeCheckContent(_, scope))
    TypedSection(
      name        = section.heading.text,
      level       = section.heading.level,
      definitions = defs,
      subsections = section.subsections.map(s => typeCheckSection(s, scope))
    )

  private def typeCheckContent(content: Content, scope: Scope): Option[TypedDef] =
    content match
      case cb: Content.CodeBlock =>
        val isScala = Lang.isParseable(cb.lang)
        if isScala && cb.tree.isEmpty then
          errors += TypeError(s"Failed to parse ${cb.lang} code block", None)
          Some(TypedDef.CodeBlock(cb.lang, parsed = false, Nil))
        else if isScala then
          val blockDefs = typeCheckBlock(cb, scope)
          Some(TypedDef.CodeBlock(cb.lang, parsed = true, blockDefs))
        else
          Some(TypedDef.CodeBlock(cb.lang, parsed = false, Nil))

      case imp: Content.Import =>
        imp.bindings.foreach { b =>
          scope.define(Symbol(b.name, SType.Any, SymbolKind.Val))
        }
        Some(TypedDef.Import(imp.path, imp.bindings.map(_.name)))

      case _ => None

  /** Walk scalameta statements in a code block, collect definitions into scope,
   *  and return a summary of what was found. */
  private def typeCheckBlock(cb: Content.CodeBlock, scope: Scope): List[DefSummary] =
    val summaries = ListBuffer[DefSummary]()
    cb.tree.foreach { node =>
      ScalaNode.fold(node) {
        case Source(stats)     => stats.foreach(s => checkStat(s, scope, summaries))
        case Term.Block(stats) => stats.foreach(s => checkStat(s, scope, summaries))
        case t: Term           => val _ = inferType(t, scope)
        case _                 => ()
      }
    }
    summaries.toList

  private def checkStat(
      stat: scala.meta.Tree,
      scope: Scope,
      out: ListBuffer[DefSummary]
  ): Unit = stat match

    // val name: T = rhs
    case Defn.Val(_, pats, tpeOpt, rhs) =>
      val rhsType  = inferType(rhs, scope)
      val declType = tpeOpt.map(typeAnnotToSType).getOrElse(rhsType)
      checkAssignable(rhsType, declType, rhs.pos)
      pats.foreach {
        case Pat.Var(name) =>
          scope.define(Symbol(name.value, declType, SymbolKind.Val))
          out += DefSummary(name.value, SymbolKind.Val, declType, Nil)
        case _ => ()
      }

    // var name: T = rhs
    case Defn.Var.After_4_7_2(_, List(Pat.Var(name)), tpeOpt, rhs) =>
      val rhsType  = inferType(rhs, scope)
      val declType = tpeOpt.map(typeAnnotToSType).getOrElse(rhsType)
      checkAssignable(rhsType, declType, rhs.pos)
      scope.define(Symbol(name.value, declType, SymbolKind.Var, mutable = true))
      out += DefSummary(name.value, SymbolKind.Var, declType, Nil)

    // def name(params...): T = body
    case d: Defn.Def =>
      val allParamVals = d.paramClauseGroups
        .flatMap(_.paramClauses)
        .flatMap(_.values)
        .toList
      val paramSTypes = allParamVals
        .map(p => p.decltpe.map(typeAnnotToSType).getOrElse(SType.Any))
      val retType = d.decltpe.map(typeAnnotToSType).getOrElse(SType.Any)
      val fnType  = SType.Function(paramSTypes, retType)
      scope.define(Symbol(d.name.value, fnType, SymbolKind.Def))
      out += DefSummary(d.name.value, SymbolKind.Def, fnType, paramSTypes)
      // Type-check body in a child scope with params bound
      val bodyScope = scope.child(d.name.value)
      d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).foreach { p =>
        val pt = p.decltpe.map(typeAnnotToSType).getOrElse(SType.Any)
        bodyScope.define(Symbol(p.name.value, pt, SymbolKind.Param))
      }
      val bodyType = inferType(d.body, bodyScope)
      if retType != SType.Any then
        checkAssignable(bodyType, retType, d.body.pos)

    // class Name(params...)
    case d: Defn.Class =>
      val paramSTypes = d.ctor.paramClauses.flatMap(_.values).map { p =>
        p.decltpe.map(typeAnnotToSType).getOrElse(SType.Any)
      }.toList
      val classType = SType.Named(d.name.value, Nil)
      val ctorType  = SType.Function(paramSTypes, classType)
      scope.define(Symbol(d.name.value, ctorType, SymbolKind.Class))
      out += DefSummary(d.name.value, SymbolKind.Class, classType, paramSTypes)

    // object Name
    case d: Defn.Object =>
      val objType = SType.Named(d.name.value, Nil)
      scope.define(Symbol(d.name.value, objType, SymbolKind.Object))
      out += DefSummary(d.name.value, SymbolKind.Object, objType, Nil)

    // enum Name
    case d: Defn.Enum =>
      val enumType = SType.Named(d.name.value, Nil)
      scope.define(Symbol(d.name.value, enumType, SymbolKind.Enum))
      d.templ.body.stats.foreach {
        case ec: Defn.EnumCase =>
          val caseSTypes = ec.ctor.paramClauses.flatMap(_.values).map { p =>
            p.decltpe.map(typeAnnotToSType).getOrElse(SType.Any)
          }.toList
          val caseType =
            if caseSTypes.isEmpty then enumType
            else SType.Function(caseSTypes, enumType)
          scope.define(Symbol(ec.name.value, caseType, SymbolKind.Val))
        case _ => ()
      }
      out += DefSummary(d.name.value, SymbolKind.Enum, enumType, Nil)

    // Top-level expressions
    case t: Term => val _ = inferType(t, scope)

    case _ => ()

  /** Very lightweight type inference — returns an SType label for a term.
   *  Does not attempt full HM inference; focuses on literals and known names. */
  private def inferType(term: scala.meta.Tree, scope: Scope): SType = term match
    case Lit.Int(_)     => SType.Int
    case Lit.Long(_)    => SType.Long
    case Lit.Double(_)  => SType.Double
    case Lit.Float(_)   => SType.Double
    case Lit.String(_)  => SType.String
    case Lit.Boolean(_) => SType.Boolean
    case Lit.Char(_)    => SType.Char
    case Lit.Unit()     => SType.Unit
    case Lit.Null()     => SType.Null

    case Term.Name(name) =>
      scope.lookup(name) match
        case Some(sym) => sym.tpe
        case None      => SType.Any  // unknown names — tolerated, no cascade errors

    case Term.Apply.After_4_6_0(fun, argClause) =>
      inferType(fun, scope) match
        case SType.Function(paramTypes, retType) =>
          val args = argClause.values
          // Only check arity for non-variadic functions.
          // Variadic: represented as single SType.Any param in our prelude.
          val isVariadic = paramTypes == List(SType.Any)
          // Underflow is permitted — trailing parameters may have defaults that
          // the lightweight typer does not track. Only flag overflow.
          if !isVariadic && paramTypes.nonEmpty && args.length > paramTypes.length then
            errors += TypeError(
              s"Wrong number of arguments: expected ${paramTypes.length}, got ${args.length}",
              posToSpan(argClause.pos)
            )
          // Check argument types for known-param functions (only those provided).
          if !isVariadic then
            args.zip(paramTypes).foreach { (arg, expected) =>
              val actual = inferType(arg, scope)
              checkAssignable(actual, expected, arg.pos)
            }
          retType
        case _ => SType.Any

    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val lhsType  = inferType(lhs, scope)
      val rhsType  = argClause.values.headOption.map(inferType(_, scope)).getOrElse(SType.Any)
      checkBinaryOp(lhsType, op.value, rhsType, op.pos)

    case Term.Block(stats) =>
      val blockScope = scope.child("<block>")
      var lastType: SType = SType.Unit
      stats.foreach { s =>
        s match
          case t: Term =>
            lastType = inferType(t, blockScope)
          case stat =>
            val dummyOut = ListBuffer[DefSummary]()
            checkStat(stat, blockScope, dummyOut)
            lastType = SType.Unit
      }
      lastType

    case t: Term.If =>
      val condType = inferType(t.cond, scope)
      if condType != SType.Boolean && condType != SType.Any then
        errors += TypeError(
          s"If condition must be Boolean, found ${condType.show}",
          posToSpan(t.cond.pos)
        )
      val thenType = inferType(t.thenp, scope)
      val elseType = inferType(t.elsep, scope)
      if thenType == elseType then thenType else SType.Any

    case Term.Interpolate(Term.Name(p), _, _) if p == "s" || p == "f" || p == "md" =>
      SType.String

    case Term.Select(_, _)        => SType.Any
    case Term.New(_)              => SType.Any
    case _: Term.Function         => SType.Any
    case _: Term.PartialFunction  => SType.Any
    case _: Term.AnonymousFunction => SType.Any
    case _                        => SType.Any

  /** Check that `actual` is assignable to `expected`, emitting an error if not. */
  private def checkAssignable(actual: SType, expected: SType, pos: scala.meta.Position): Unit =
    if expected == SType.Any || actual == SType.Any || expected == SType.Nothing then ()
    else if isCompatible(actual, expected) then ()
    else
      errors += TypeError(
        s"Type mismatch: expected ${expected.show}, found ${actual.show}",
        posToSpan(pos)
      )

  private def isCompatible(actual: SType, expected: SType): Boolean =
    actual == expected ||
    actual == SType.Nothing ||
    expected == SType.Any   ||
    (actual == SType.Null && isNullable(expected)) ||
    // Numeric widening: Int literal is valid where Long or Double is expected
    (actual == SType.Int  && (expected == SType.Long || expected == SType.Double)) ||
    (actual == SType.Long && expected == SType.Double)

  private def isNullable(t: SType): Boolean = t match
    case SType.Named(_, _) => true
    case _                 => false

  /** Basic type rules for infix operators. */
  private def checkBinaryOp(lhs: SType, op: String, rhs: SType, pos: scala.meta.Position): SType =
    (lhs, op, rhs) match
      // Numeric arithmetic — same type
      case (SType.Int,    op2, SType.Int)    if isArith(op2) => SType.Int
      case (SType.Long,   op2, SType.Long)   if isArith(op2) => SType.Long
      case (SType.Double, op2, SType.Double) if isArith(op2) => SType.Double
      // Widening
      case (SType.Int,    op2, SType.Double) if isArith(op2) => SType.Double
      case (SType.Double, op2, SType.Int)    if isArith(op2) => SType.Double
      case (SType.Long,   op2, SType.Int)    if isArith(op2) => SType.Long
      case (SType.Int,    op2, SType.Long)   if isArith(op2) => SType.Long
      // String concat
      case (SType.String, "+", _)  => SType.String
      case (_, "+", SType.String)  => SType.String
      // Comparison — always Boolean
      case (_, "==" | "!=" | "<" | ">" | "<=" | ">=", _) => SType.Boolean
      // Logical
      case (SType.Boolean, "&&" | "||", SType.Boolean) => SType.Boolean
      // Type mismatch for arithmetic on known incompatible types
      case (l, op2, r)
          if isArith(op2) && l != SType.Any && r != SType.Any &&
             !isNumericOrString(l) =>
        errors += TypeError(
          s"Operator '$op2' is not applicable to ${l.show}",
          posToSpan(pos)
        )
        SType.Error(s"${l.show} $op2 ${r.show}")
      case _ => SType.Any

  private def isArith(op: String): Boolean = Set("+", "-", "*", "/", "%").contains(op)
  private def isNumericOrString(t: SType): Boolean =
    t == SType.Int || t == SType.Long || t == SType.Double || t == SType.String

  /** Convert a scalameta type annotation to our internal SType. */
  private def typeAnnotToSType(tpe: scala.meta.Type): SType = tpe match
    case Type.Name(name) => name match
      case "Int"     => SType.Int
      case "Long"    => SType.Long
      case "Double"  => SType.Double
      case "Float"   => SType.Double
      case "String"  => SType.String
      case "Boolean" => SType.Boolean
      case "Char"    => SType.Char
      case "Unit"    => SType.Unit
      case "Any"     => SType.Any
      case "Nothing" => SType.Nothing
      case "Null"    => SType.Null
      case other     => SType.Named(other, Nil)
    case Type.Apply.After_4_6_0(Type.Name("List"),   argClause) =>
      SType.list(typeAnnotToSType(argClause.values.head))
    case Type.Apply.After_4_6_0(Type.Name("Option"), argClause) =>
      SType.option(typeAnnotToSType(argClause.values.head))
    case Type.Apply.After_4_6_0(Type.Name("Map"), argClause) if argClause.values.length == 2 =>
      SType.map(typeAnnotToSType(argClause.values.head), typeAnnotToSType(argClause.values(1)))
    case Type.Function.After_4_6_0(params, ret) =>
      SType.Function(params.values.map(typeAnnotToSType).toList, typeAnnotToSType(ret))
    case Type.Tuple(elems) => SType.Tuple(elems.map(typeAnnotToSType))
    case _                 => SType.Any

  private def posToSpan(pos: scala.meta.Position): Option[Span] =
    if pos.isEmpty then None
    else Some(Span(
      scalascript.ast.Position(pos.startLine, pos.startColumn, pos.start),
      scalascript.ast.Position(pos.endLine, pos.endColumn, pos.end)
    ))

case class TypeError(msg: String, span: Option[Span]):
  def show: String = span match
    case Some(s) => s"$s: $msg"
    case None    => msg

// ─── Summary of a single definition found in a code block ────────

case class DefSummary(name: String, kind: SymbolKind, tpe: SType, paramTypes: List[SType]):
  def show: String =
    val kindStr = kind.toString.toLowerCase
    s"$kindStr $name: ${tpe.show}"

// ─── Typed IR ─────────────────────────────────────────────────────

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
      sb ++= s"Errors (${errors.length}):\n"
      errors.foreach(e => sb ++= s"  - ${e.show}\n")
      sb ++= "\n"
    sections.foreach(s => sb ++= s.show(1))
    sb.toString

case class TypedSection(
  name: String,
  level: Int,
  definitions: List[TypedDef],
  subsections: List[TypedSection]
):
  def show(indent: Int): String =
    val prefix = "  " * indent
    val sb = StringBuilder()
    sb ++= s"$prefix${"#" * level} $name\n"
    definitions.foreach {
      case TypedDef.CodeBlock(lang, ok, defs) =>
        val status = if ok then "OK" else if lang.isEmpty then "untyped" else "PARSE ERROR"
        sb ++= s"$prefix  [$lang: $status]\n"
        defs.foreach(d => sb ++= s"$prefix    ${d.show}\n")
      case TypedDef.Import(path, bindings) =>
        sb ++= s"$prefix  [import $path -> ${bindings.mkString(", ")}]\n"
    }
    subsections.foreach(s => sb ++= s.show(indent + 1))
    sb.toString

enum TypedDef:
  case CodeBlock(lang: String, parsed: Boolean, defs: List[DefSummary])
  case Import(path: String, bindings: List[String])

object Typer:
  def typeCheck(module: Module): TypedModule = Typer().typeCheck(module)
