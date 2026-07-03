package scalascript.compiler.plugin.swing

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

object SwingIntrinsics:
  val table: Map[QualifiedName, IntrinsicImpl] = Map.empty
