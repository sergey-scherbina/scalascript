package scalascript.interpreter

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** HTTP server intrinsics for the tree-walking interpreter.
 *
 *  route / serve / stop were previously registered via hardcoded `nativeP`
 *  calls in `Interpreter.initBuiltins`; Stage 5+/B migrated them here so
 *  they flow through the shared `IntrinsicImpl` pipeline.
 *
 *  `NativeContext` carries the HTTP-specific hooks (`headless`,
 *  `registerRoute`, `registerHealthDefaults`) that `Interpreter.installNativeIntrinsics`
 *  overrides with live interpreter state. */
val HttpIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  // route(method, path) — curried; returns a NativeFnV that accepts the handler.
  // wrapAnyAsValue passes Value through unchanged so the returned NativeFnV
  // is callable directly by the interpreter's dispatch path.
  QualifiedName("route") -> NativeImpl((ctx, args) =>
    args match
      case List(method: String, path: String) =>
        Value.NativeFnV("route.handler", Computation.pureFn {
          case List(handler) =>
            ctx.registerRoute(method, path, handler)
            Value.UnitV
          case _ => throw InterpretError("route(method, path) { handler }")
        })
      case _ => throw InterpretError("route(method, path) { handler }")
  ),
  // serve(port) / serve(port, dir) — starts the HTTP server.
  QualifiedName("serve") -> NativeImpl((ctx, args) =>
    args match
      case List(port: Long) =>
        ctx.registerHealthDefaults()
        if !ctx.headless then scalascript.server.WebServer.start(port.toInt, ".", ctx.out)
        ()
      case List(port: Long, dir: String) =>
        ctx.registerHealthDefaults()
        if !ctx.headless then scalascript.server.WebServer.start(port.toInt, dir, ctx.out)
        ()
      case _ => throw InterpretError("serve(port) or serve(port, dir)")
  ),
  QualifiedName("stop") -> NativeImpl((ctx, _) =>
    if !ctx.headless then scalascript.server.WebServer.stop()
  )
)
