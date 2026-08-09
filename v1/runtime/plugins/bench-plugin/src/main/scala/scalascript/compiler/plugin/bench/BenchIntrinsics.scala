package scalascript.compiler.plugin.bench

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginNative, PluginValue}

/** Bench intrinsics — identity helpers used by the bench harness to defeat
 *  ahead-of-time constant folding on AOT backends.
 *
 *  `Bench.opaque(x)` is the identity function — it returns its argument
 *  unchanged.  Its job is to prevent the **compiler** from proving the
 *  surrounding expression is constant.  On the interpreter / JVM / JS
 *  backends this is just a function call; on the Rust backend it maps to
 *  `std::hint::black_box` which is the canonical anti-folding barrier.
 *
 *  Use sparingly — only in bench corpus loops where LLVM constant-folding
 *  would otherwise replace the loop body with a single closed-form
 *  constant (e.g. `for i in 0..N { sum += i }` → Gauss' formula).
 */
object BenchIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // Stable-SPI identity: NativeImpl callers pass unwrapped Scala values,
    // and the interpreter wraps the returned Any back into Value at the
    // boundary. The hot interpreter path is still special-cased in EvalRuntime.
    QualifiedName("Bench.opaque") -> PluginNative.eval { (_, args) =>
      PluginComputation.pure(args.headOption.getOrElse(PluginValue.wrap(())))
    }
  )
