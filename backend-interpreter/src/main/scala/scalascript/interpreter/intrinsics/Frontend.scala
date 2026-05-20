package scalascript.interpreter

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Frontend-framework selection intrinsics for the tree-walking interpreter
 *  (v1.18 / Phase A7).
 *
 *  Single entry point: `setFrontendFramework(name)` — picks which
 *  `FrontendFrameworkSpi` impl downstream codegen / SPA emit will route
 *  through.  Names are determined by which `frontend-*` sbt modules are
 *  on the classpath: the default ssc bundle ships all four (`custom`,
 *  `react`, `solid`, `vue`).
 *
 *  Mirrors `setHttpServerBackend` in `Ws.scala`.  Loud failure: an
 *  unrecognised name throws so the user sees the typo immediately
 *  instead of silently falling back to the first-registered impl. */
val FrontendIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(

  // NB: `args` is `List[Any]` post-unwrap (Interpreter.installNativeIntrinsics
  // strips `Value.StringV(s)` to a raw `String` before invoking the eval),
  // so the pattern matches the primitive type — not the `Value` wrapper.
  QualifiedName("setFrontendFramework") -> NativeImpl((_, args) =>
    args match
      case List(name: String) =>
        scalascript.frontend.FrontendFrameworks.setBackend(name)
        ()
      case _ => throw InterpretError("setFrontendFramework(name)")
  )
)
