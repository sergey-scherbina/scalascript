package scalascript.frontend

import scalascript.ast.{ModelDef, ModelFieldType}

/** Emits Scala 3 case classes from `ModelDef` descriptors.
 *
 *  Used by JVM backends (Swing, JavaFX) to generate typed model classes
 *  alongside emitted Scala source so the `JsonDecoder` can deserialize
 *  typed instances at runtime.
 *
 *  Example:
 *  {{{
 *    @model case class BalanceSheet(id: String, total: Double, lines: List[AssetLine])
 *  }}}
 *  emits:
 *  {{{
 *    case class BalanceSheet(id: String, total: Double, lines: List[AssetLine])
 *      extends scalascript.frontend.SscModel
 *  }}}
 */
object ModelCaseClassEmitter:

  /** Emit a single Scala 3 case class from a `ModelDef`. */
  def emitClass(model: ModelDef, pkg: Option[String] = None): String =
    val pkgLine = pkg.map(p => s"package $p\n\n").getOrElse("")
    val fields = model.fields.map(f => s"  ${f.name}: ${scalaType(f.tpe)}").mkString(",\n")
    val extendsClause = " extends scalascript.frontend.SscModel"
    s"${pkgLine}case class ${model.name}(\n$fields\n)$extendsClause"

  /** Emit all models in a single source file. */
  def emitAll(models: List[ModelDef], pkg: Option[String] = None): String =
    val pkgLine = pkg.map(p => s"package $p\n\n").getOrElse("")
    val imports = "import scalascript.frontend.SscModel\n\n"
    val classes = models.map(m => emitClassBody(m)).mkString("\n\n")
    s"$pkgLine$imports$classes"

  private def emitClassBody(model: ModelDef): String =
    val fields = model.fields.map(f => s"  ${f.name}: ${scalaType(f.tpe)}").mkString(",\n")
    s"case class ${model.name}(\n$fields\n) extends SscModel"

  private def scalaType(tpe: ModelFieldType): String = tpe match
    case ModelFieldType.Str            => "String"
    case ModelFieldType.IntF           => "Int"
    case ModelFieldType.DblF           => "Double"
    case ModelFieldType.BoolF          => "Boolean"
    case ModelFieldType.Nested(name)   => name
    case ModelFieldType.ListOf(inner)  => s"List[${scalaType(inner)}]"
    case ModelFieldType.Optional(inner) => s"Option[${scalaType(inner)}]"

/** Marker trait mixed into every emitted model class so JVM runtimes can
 *  detect model instances via `isInstanceOf[SscModel]`. */
trait SscModel
