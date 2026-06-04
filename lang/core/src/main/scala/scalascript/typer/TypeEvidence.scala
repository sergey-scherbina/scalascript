package scalascript.typer

import scalascript.ast.Span

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
