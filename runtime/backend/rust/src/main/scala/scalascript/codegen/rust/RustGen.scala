package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.ir

/** Phase R.1 skeleton — `generate` returns an empty `Segmented` payload
 *  so the backend is discoverable by `ServiceLoader[Backend]` without
 *  pretending it can emit code yet.  The actual Cargo crate emit lands
 *  in the next slice (rust-backend-r1-hello-emit). */
object RustGen:
  def generate(
      module:           ir.NormalizedModule,
      opts:             BackendOptions,
      intrinsics:       Map[ir.QualifiedName, IntrinsicImpl],
      runtimePreamble:  String
  ): CompileResult =
    val _ = (module, opts, intrinsics, runtimePreamble)
    CompileResult.Segmented(Nil)
