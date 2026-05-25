package scalascript.typeddata

import scala.annotation.StaticAnnotation

final class fieldName(val name: String) extends StaticAnnotation
final class aliases(val names: String*) extends StaticAnnotation
final class key extends StaticAnnotation
final class rejectUnknown extends StaticAnnotation
