package scalascript.uniml

trait DialectAdapter:
  def id: String

  def aliases: Set[String] = Set.empty

  def instructions(source: SourceInput): Processor[SourceChunk, VmToken]

  def project(tree: UniNode): Projection = Projection.Identity(tree)

enum Projection:
  case Identity(tree: UniNode)

final case class DialectRegistry private (byName: Map[String, DialectAdapter]):
  def get(id: String): Option[DialectAdapter] = byName.get(id)

  def register(adapter: DialectAdapter): Either[String, DialectRegistry] =
    val names = adapter.aliases + adapter.id
    names.find(byName.contains) match
      case Some(duplicate) => Left(s"dialect name '$duplicate' is already registered")
      case None            => Right(DialectRegistry(byName ++ names.map(_ -> adapter)))

object DialectRegistry:
  val empty: DialectRegistry = DialectRegistry(Map.empty)

  def apply(adapters: DialectAdapter*): Either[String, DialectRegistry] =
    adapters.foldLeft[Either[String, DialectRegistry]](Right(empty)) { (result, adapter) =>
      result.flatMap(_.register(adapter))
    }
