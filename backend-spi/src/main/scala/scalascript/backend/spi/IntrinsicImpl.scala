package scalascript.backend.spi

import scalascript.ir.{IrExpr, EmitContext, TargetCode}

/** How a backend implements a single platform intrinsic (`extern def`
 *  marker, see docs/backend-spi.md §8).
 *
 *  Backends populate `Backend.intrinsics: Map[QualifiedName, IntrinsicImpl]`
 *  to claim implementations.  `CapabilityCheck` (Stage 4) refuses to
 *  invoke `compile` on a program that calls an extern symbol the
 *  selected backend has no entry for. */
sealed trait IntrinsicImpl

/** Inline target-source generated at each call site.  `emit` receives
 *  the call's IR argument expressions and a context for accessing
 *  surrounding state. */
case class InlineCode(emit: (List[IrExpr], EmitContext) => TargetCode) extends IntrinsicImpl

/** Call a runtime function the backend ships with its emitted output
 *  (e.g. a `_http_serve(port, handler)` helper baked into the JS runtime
 *  preamble). */
case class RuntimeCall(targetSymbol: String) extends IntrinsicImpl

/** Out-of-process backends route platform calls back into core via a
 *  named host callback that core dispatches.  See §12.2 wire protocol. */
case class HostCallback(name: String) extends IntrinsicImpl

/** Interpretive intrinsic: an in-process function the runtime calls
 *  directly with the evaluated arguments.  Unlike `InlineCode` /
 *  `RuntimeCall` (which produce target source for compiled backends),
 *  `NativeImpl` is the variant interpretive backends consume —
 *  `InterpreterBackend` registers each one as a native function in
 *  the interpreter's global table at session start.
 *
 *  Arguments and return value are typed `Any` because the SPI sits
 *  in `backend-spi` while concrete value types live in the
 *  interpreter (which depends on `backend-spi`, not vice versa);
 *  the interpreter casts to its own `Value` ADT at the boundary. */
case class NativeImpl(eval: List[Any] => Any) extends IntrinsicImpl
