package scalascript.typer

import scalascript.ast.*
import scala.collection.mutable.ListBuffer

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
    s.define(Symbol("println", SType.Function(List(SType.Any), SType.Unit), SymbolKind.Def))
    s.define(Symbol("print",   SType.Function(List(SType.Any), SType.Unit), SymbolKind.Def))
    s.define(Symbol("assert",  SType.Function(List(SType.Boolean), SType.Unit), SymbolKind.Def))
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
        val isScala = cb.lang == "scala" || cb.lang == "ssc"
        if isScala && cb.tree.isEmpty then
          errors += TypeError(s"Failed to parse ${cb.lang} code block", None)
        Some(TypedDef.CodeBlock(cb.lang, isScala && cb.tree.isDefined))

      case imp: Content.Import =>
        imp.bindings.foreach { b =>
          scope.define(Symbol(b.name, SType.Any, SymbolKind.Val))
        }
        Some(TypedDef.Import(imp.path, imp.bindings.map(_.name)))

      case _ => None

case class TypeError(msg: String, span: Option[Span])

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
      sb ++= "Errors:\n"
      errors.foreach(e => sb ++= s"  - ${e.msg}\n")
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
      case TypedDef.CodeBlock(lang, ok) =>
        val status = if ok then "OK" else if lang.isEmpty then "untyped" else "PARSE ERROR"
        sb ++= s"$prefix  [$lang: $status]\n"
      case TypedDef.Import(path, bindings) =>
        sb ++= s"$prefix  [import $path → ${bindings.mkString(", ")}]\n"
    }
    subsections.foreach(s => sb ++= s.show(indent + 1))
    sb.toString

enum TypedDef:
  case CodeBlock(lang: String, parsed: Boolean)
  case Import(path: String, bindings: List[String])

object Typer:
  def typeCheck(module: Module): TypedModule = Typer().typeCheck(module)
