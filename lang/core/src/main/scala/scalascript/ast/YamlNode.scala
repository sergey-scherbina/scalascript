package scalascript.ast

import scala.jdk.CollectionConverters.*

/** Opaque wrapper for a parsed YAML value (Map, List, or scalar AnyRef).
 *  SimpleYaml returns Java collection types with no common supertype, hence AnyRef.
 */
opaque type YamlNode = AnyRef

object YamlNode:
  def apply(obj: AnyRef): YamlNode = obj

  given ASTNode[YamlNode] with
    def kind(n: YamlNode): String = n match
      case _: java.util.Map[?, ?]  => "Mapping"
      case _: java.util.List[?]    => "Sequence"
      case null                    => "Null"
      case _                       => "Scalar"
    def children(n: YamlNode): List[YamlNode] = n match
      case m: java.util.Map[?, ?]  => m.values.asScala.map(_.asInstanceOf[AnyRef]).toList
      case l: java.util.List[?]    => l.asScala.map(_.asInstanceOf[AnyRef]).toList
      case _                       => Nil
    override def text(n: YamlNode): Option[String] = n match
      case null                                             => Some("null")
      case _: java.util.Map[?, ?] | _: java.util.List[?]  => None
      case other                                            => Some(other.toString)
