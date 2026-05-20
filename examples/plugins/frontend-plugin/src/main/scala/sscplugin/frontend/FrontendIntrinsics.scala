package sscplugin.frontend

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.InterpretError

object FrontendIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // NB: `args` is `List[Any]` post-unwrap (Interpreter.installNativeIntrinsics
    // strips `Value.StringV(s)` to a raw `String` before invoking the eval),
    // so the pattern matches the primitive type — not the `Value` wrapper.
    QualifiedName("setFrontendFramework") -> NativeImpl((_, args) =>
      args match
        case List(name: String) =>
          scalascript.frontend.FrontendFrameworks.setBackend(name)
          ()
        case _ => throw InterpretError("setFrontendFramework(name)")
    ),

  )
