package scalascript.typeddata

import scala.annotation.StaticAnnotation

final class fieldName(val name: String) extends StaticAnnotation
final class aliases(val names: String*) extends StaticAnnotation
final class key extends StaticAnnotation
final class rejectUnknown extends StaticAnnotation
final class graphLabel(val label: String) extends StaticAnnotation
final class graphEdge(val label: String) extends StaticAnnotation
final class graphFrom extends StaticAnnotation
final class graphTo extends StaticAnnotation
final class rdfClass(val iri: String) extends StaticAnnotation
final class rdfId extends StaticAnnotation
final class rdf(val predicate: String) extends StaticAnnotation
