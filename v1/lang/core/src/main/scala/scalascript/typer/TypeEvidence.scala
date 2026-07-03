package scalascript.typer

import scalascript.ast.Span
import scalascript.ir

enum TypeEvidenceKind:
  case Declared
  case Inferred
  case Derived
  case Imported
  case PluginProvided
  case Dynamic
  case Unknown

case class TypeEvidence(
    tpe: SType,
    kind: TypeEvidenceKind,
    source: Option[Span] = None,
    reason: Option[String] = None
):
  def containsAny: Boolean = tpe.containsAny

object TypeEvidence:
  def declared(
      tpe: SType,
      source: Option[Span] = None,
      reason: Option[String] = None
  ): TypeEvidence =
    TypeEvidence(tpe, TypeEvidenceKind.Declared, source, reason)

  def inferred(
      tpe: SType,
      source: Option[Span] = None,
      reason: Option[String] = None
  ): TypeEvidence =
    TypeEvidence(tpe, TypeEvidenceKind.Inferred, source, reason)

  def derived(
      tpe: SType,
      source: Option[Span] = None,
      reason: Option[String] = None
  ): TypeEvidence =
    TypeEvidence(tpe, TypeEvidenceKind.Derived, source, reason)

  def imported(
      tpe: SType,
      source: Option[Span] = None,
      reason: Option[String] = None
  ): TypeEvidence =
    TypeEvidence(tpe, TypeEvidenceKind.Imported, source, reason)

  def pluginProvided(
      tpe: SType,
      source: Option[Span] = None,
      reason: Option[String] = None
  ): TypeEvidence =
    TypeEvidence(tpe, TypeEvidenceKind.PluginProvided, source, reason)

  def dynamic(
      tpe: SType = SType.Any,
      source: Option[Span] = None,
      reason: Option[String] = None
  ): TypeEvidence =
    TypeEvidence(tpe, TypeEvidenceKind.Dynamic, source, reason)

  def unknown(
      tpe: SType = SType.Any,
      source: Option[Span] = None,
      reason: Option[String] = None
  ): TypeEvidence =
    TypeEvidence(tpe, TypeEvidenceKind.Unknown, source, reason)

case class AnyEvidenceCounts(
    declared: Int = 0,
    inferred: Int = 0,
    derived: Int = 0,
    imported: Int = 0,
    pluginProvided: Int = 0,
    dynamic: Int = 0,
    unknown: Int = 0
):
  def total: Int =
    declared + inferred + derived + imported + pluginProvided + dynamic + unknown

  def increment(kind: TypeEvidenceKind): AnyEvidenceCounts = kind match
    case TypeEvidenceKind.Declared       => copy(declared = declared + 1)
    case TypeEvidenceKind.Inferred       => copy(inferred = inferred + 1)
    case TypeEvidenceKind.Derived        => copy(derived = derived + 1)
    case TypeEvidenceKind.Imported       => copy(imported = imported + 1)
    case TypeEvidenceKind.PluginProvided => copy(pluginProvided = pluginProvided + 1)
    case TypeEvidenceKind.Dynamic        => copy(dynamic = dynamic + 1)
    case TypeEvidenceKind.Unknown        => copy(unknown = unknown + 1)

object AnyEvidenceInventory:
  def count(summaries: Iterable[DefSummary]): AnyEvidenceCounts =
    summaries.foldLeft(AnyEvidenceCounts()) { (counts, summary) =>
      val evidence = summary.evidence.getOrElse {
        TypeEvidence.unknown(
          summary.tpe,
          reason = Some("missing type evidence")
        )
      }
      if evidence.containsAny then counts.increment(evidence.kind)
      else counts
    }

/** Counts declared / unknown evidence for API endpoints and remote handlers in a
 *  compiled manifest.  Missing `typeEvidence` (legacy artifacts) is treated as
 *  Unknown so old `.scir` files remain backward-compatible. */
case class RouteEvidenceCounts(
    endpointsDeclared: Int = 0,
    endpointsUnknown:  Int = 0,
    handlersDeclared:  Int = 0,
    handlersUnknown:   Int = 0
):
  def allDeclared: Boolean = endpointsUnknown == 0 && handlersUnknown == 0
  def totalEndpoints: Int  = endpointsDeclared + endpointsUnknown
  def totalHandlers: Int   = handlersDeclared  + handlersUnknown

object RouteEvidenceInventory:
  def count(manifest: ir.Manifest): RouteEvidenceCounts =
    val endpoints = manifest.apiClients.flatMap(_.endpoints)
    val handlers  = manifest.remoteHandlers

    val endpointsDeclared = endpoints.count(endpointIsDeclared)
    val endpointsUnknown  = endpoints.count(!endpointIsDeclared(_))
    val handlersDeclared  = handlers.count(handlerIsDeclared)
    val handlersUnknown   = handlers.count(!handlerIsDeclared(_))

    RouteEvidenceCounts(endpointsDeclared, endpointsUnknown, handlersDeclared, handlersUnknown)

  private def wireIsDeclared(w: ir.TypeEvidenceWire): Boolean = w.kind == "Declared"

  private def endpointIsDeclared(e: ir.ApiEndpointDecl): Boolean =
    e.typeEvidence match
      case None => false
      case Some(ev) =>
        ev.request.exists(wireIsDeclared) && ev.response.exists(wireIsDeclared)

  private def handlerIsDeclared(h: ir.RemoteHandlerDecl): Boolean =
    h.typeEvidence match
      case None => false
      case Some(ev) =>
        ev.request.exists(wireIsDeclared) && ev.response.exists(wireIsDeclared)

/** Counts declared / unknown evidence for GraphQL types and fields across all
 *  `graphql` fenced blocks in a compiled module's sections.  A block with
 *  missing `evidence` (legacy artifact) counts as 1 unknown type (backward-compat). */
case class GraphQLEvidenceCounts(
    typesDeclared:  Int = 0,
    typesUnknown:   Int = 0,
    fieldsDeclared: Int = 0,
    fieldsUnknown:  Int = 0
):
  def allDeclared: Boolean = typesUnknown == 0 && fieldsUnknown == 0
  def totalTypes:  Int     = typesDeclared + typesUnknown
  def totalFields: Int     = fieldsDeclared + fieldsUnknown

object GraphQLEvidenceInventory:
  def count(module: ir.NormalizedModule): GraphQLEvidenceCounts =
    val objectKinds = Set("Object", "Interface", "Input")
    module.sections.foldLeft(GraphQLEvidenceCounts()) { (acc, section) =>
      sectionCounts(section, objectKinds, acc)
    }

  private def sectionCounts(
    section: ir.Section,
    objectKinds: Set[String],
    acc: GraphQLEvidenceCounts
  ): GraphQLEvidenceCounts =
    val afterContent = section.content.foldLeft(acc) { (c, block) =>
      block match
        case ir.Content.EmbeddedBlock("graphql", _, _, Some(ev)) =>
          ev.types.foldLeft(c) { (cc, t) =>
            if objectKinds.contains(t.kind) then
              val fd = t.fields.count(_.kind == "Declared")
              val fu = t.fields.count(_.kind != "Declared")
              cc.copy(
                typesDeclared  = cc.typesDeclared  + (if t.fields.isEmpty || fd == t.fields.size then 1 else 0),
                typesUnknown   = cc.typesUnknown   + (if t.fields.nonEmpty && fd < t.fields.size  then 1 else 0),
                fieldsDeclared = cc.fieldsDeclared + fd,
                fieldsUnknown  = cc.fieldsUnknown  + fu
              )
            else cc  // Union/Enum/Scalar — not counted as evidence-bearing types
          }
        case ir.Content.EmbeddedBlock("graphql", _, _, None) =>
          c.copy(typesUnknown = c.typesUnknown + 1)
        case _ => c
    }
    section.subsections.foldLeft(afterContent) { (c, sub) => sectionCounts(sub, objectKinds, c) }
