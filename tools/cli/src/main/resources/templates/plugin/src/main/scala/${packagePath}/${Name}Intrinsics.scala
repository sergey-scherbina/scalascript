package ${packageName}

import scalascript.backend.spi.*
import scalascript.interpreter.Value
import scalascript.ir.QualifiedName

object ${Name}Intrinsics:
  val table: Map[QualifiedName, IntrinsicImpl] = Map(
    QualifiedName("hello") -> NativeImpl((_, args) =>
      val who = args.collectFirst { case s: String => s }.getOrElse("ScalaScript")
      Value.StringV(s"hello, $who")
    )
  )
