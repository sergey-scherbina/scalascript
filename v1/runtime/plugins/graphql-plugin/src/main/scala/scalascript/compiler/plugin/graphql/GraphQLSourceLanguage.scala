package scalascript.compiler.plugin.graphql

import scalascript.backend.spi.*
import scalascript.ir
import scalascript.ir.{GraphQLBlockEvidenceWire, GraphQLTypeEvidenceWire, GraphQLFieldEvidenceWire}
import graphql.schema.idl.{SchemaParser, TypeDefinitionRegistry}
import graphql.schema.idl.errors.SchemaProblem
import graphql.language.{
  ObjectTypeDefinition, InterfaceTypeDefinition, InputObjectTypeDefinition,
  UnionTypeDefinition, EnumTypeDefinition, ScalarTypeDefinition,
  TypeName, NonNullType, ListType
}
import scala.jdk.CollectionConverters.*

/** SourceLanguage plugin for `graphql` fenced blocks (compile-time path).
 *
 *  Phase 1: passthrough — SDL embedded in IR verbatim.
 *  Phase 4: validate SDL through graphql-java's SchemaParser; populate
 *  `BlockArtifact.diagnostics` with `Diagnostic.GraphQLSdlError` entries.
 *  Invalid SDL blocks still produce an `EmbeddedBlock` so downstream passes
 *  can continue; callers that care about errors (CLI build, LSP) inspect
 *  `BlockArtifact.diagnostics`.
 *  P4d-α: on successful parse, retain the `TypeDefinitionRegistry` and
 *  build `GraphQLBlockEvidenceWire` classifying each field type as
 *  Declared (known SDL scalar or same-block type) vs Unknown.
 *
 *  Registration: META-INF/services/scalascript.backend.spi.SourceLanguage
 */
class GraphQLSourceLanguage extends SourceLanguage:
  def id:            String = "graphql-source"
  def displayName:   String = "GraphQL SDL"
  def spiVersion:    String = SpiVersion.Current
  def canonicalName: String = "graphql"

  def signatures(source: String, scope: ScopeContext): List[SymbolExport] = Nil

  def compileBlock(source: String, scope: ScopeContext, opts: BackendOptions): BlockArtifact =
    try
      val tdr = new SchemaParser().parse(source)
      val evidence = buildEvidence(tdr, scope)
      BlockArtifact(ir.Content.EmbeddedBlock(language = canonicalName, source = source, evidence = Some(evidence)))
    catch
      case e: SchemaProblem =>
        val diags = e.getErrors.asScala.toList.map { err =>
          val loc = Option(err.getLocations).flatMap(_.asScala.headOption)
          Diagnostic.GraphQLSdlError(
            message = err.getMessage,
            line    = loc.map(_.getLine).getOrElse(0),
            col     = loc.map(_.getColumn).getOrElse(0)
          )
        }
        BlockArtifact(ir.Content.EmbeddedBlock(canonicalName, source), diagnostics = diags)
      case e: Exception =>
        BlockArtifact(ir.Content.EmbeddedBlock(canonicalName, source),
          diagnostics = List(Diagnostic.GraphQLSdlError(e.getMessage, 0, 0)))

  private val BuiltinScalars: Set[String] =
    Set("Int", "Float", "String", "Boolean", "ID")

  private def baseTypeName(t: graphql.language.Type[?]): String = t match
    case nn: NonNullType => baseTypeName(nn.getType)
    case lt: ListType    => baseTypeName(lt.getType)
    case tn: TypeName    => tn.getName
    case _               => ""

  private def buildEvidence(tdr: TypeDefinitionRegistry, scope: ScopeContext): GraphQLBlockEvidenceWire =
    val definedNames: Set[String] = tdr.types().asScala.keySet.toSet
    def classifyTypeName(name: String): String =
      if BuiltinScalars.contains(name) || definedNames.contains(name) then "Declared"
      else if scope.resolve(ir.QualifiedName(name)).isDefined then "Declared"
      else "Unknown"

    val typeEntries = tdr.types().asScala.toList.sortBy(_._1).map { (name, td) =>
      td match
        case o: ObjectTypeDefinition =>
          val fields = o.getFieldDefinitions.asScala.toList.map { f =>
            val base = baseTypeName(f.getType)
            GraphQLFieldEvidenceWire(f.getName, base, classifyTypeName(base))
          }
          GraphQLTypeEvidenceWire(name, "Object", fields)
        case i: InterfaceTypeDefinition =>
          val fields = i.getFieldDefinitions.asScala.toList.map { f =>
            val base = baseTypeName(f.getType)
            GraphQLFieldEvidenceWire(f.getName, base, classifyTypeName(base))
          }
          GraphQLTypeEvidenceWire(name, "Interface", fields)
        case inp: InputObjectTypeDefinition =>
          val fields = inp.getInputValueDefinitions.asScala.toList.map { f =>
            val base = baseTypeName(f.getType)
            GraphQLFieldEvidenceWire(f.getName, base, classifyTypeName(base))
          }
          GraphQLTypeEvidenceWire(name, "Input", fields)
        case _: UnionTypeDefinition  => GraphQLTypeEvidenceWire(name, "Union")
        case _: EnumTypeDefinition   => GraphQLTypeEvidenceWire(name, "Enum")
        case _: ScalarTypeDefinition => GraphQLTypeEvidenceWire(name, "Scalar")
        case _                       => GraphQLTypeEvidenceWire(name, "Unknown")
    }
    GraphQLBlockEvidenceWire(typeEntries)
