package scalascript.codegen.jvm

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Dataset[T] intrinsics for the JVM backend.
 *
 *  Full implementation lives in `JvmRuntimeDataset` emitted as a Scala
 *  preamble.  These entries satisfy `CapabilityCheck` and route
 *  `extern` call sites to the matching Scala functions. */
val JvmDatasetIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  // Constructors
  QualifiedName("Dataset.of")            -> RuntimeCall("_Dataset.of"),
  QualifiedName("Dataset.fromList")      -> RuntimeCall("_Dataset.fromList"),
  QualifiedName("Dataset.fromGenerator") -> RuntimeCall("_Dataset.fromGenerator"),
  QualifiedName("Dataset.fromFile")      -> RuntimeCall("_Dataset.fromFile"),
)
