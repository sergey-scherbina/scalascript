package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Dataset[T] intrinsics for the JS backend.
 *
 *  The full implementation lives in `JsRuntimeDataset` (emitted as a
 *  preamble by `JsGen` when dataset usage is detected).  These entries
 *  satisfy `CapabilityCheck` and route `extern` call sites to the
 *  matching JS functions. */
val JsDatasetIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  // Constructors
  QualifiedName("Dataset.of")            -> RuntimeCall("_Dataset.of"),
  QualifiedName("Dataset.fromList")      -> RuntimeCall("_Dataset.fromList"),
  QualifiedName("Dataset.fromGenerator") -> RuntimeCall("_Dataset.fromGenerator"),
  QualifiedName("Dataset.fromFile")      -> RuntimeCall("_Dataset.fromFile"),
)
