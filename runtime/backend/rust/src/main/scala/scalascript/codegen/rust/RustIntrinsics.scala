package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Intrinsic table for the rust target.  Phase R.1 skeleton — empty;
 *  the hello-world emit slice (rust-backend-r1-hello-emit) wires
 *  `println`/`print` to `RuntimeCall("crate::runtime::_println")`. */
val RustIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map.empty
