package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** HTTP server intrinsics for the JVM (Scala-CLI) backend.
 *
 *  route, serve, and stop are Scala `def` declarations in the
 *  `serveRuntime` preamble that JvmGen prepends when `blocksUseRoutes`
 *  fires.  `RuntimeCall` entries here satisfy `CapabilityCheck` so that
 *  any program calling these `extern def`s is accepted by the JVM backend. */
val JvmHttpIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("route") -> RuntimeCall("route"),
  QualifiedName("serve") -> RuntimeCall("serve"),
  QualifiedName("stop")  -> RuntimeCall("stop")
)
