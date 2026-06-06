package scalascript.codegen.rust

import scalascript.backend.spi.*

/** Capabilities declared by the rust target (Phase R.1 skeleton).
 *
 *  Only `Feature.ConsoleIO` is supported in the skeleton — every other
 *  feature is rejected by `CapabilityCheck` before `compile` runs,
 *  with a `Diagnostic.Unsupported` naming the missing feature.  Each
 *  later R.x slice grows this set per `specs/rust-backend.md §8`. */
val RustCapabilities: Capabilities = Capabilities(
  features = Set(
    Feature.ConsoleIO,
    // Hello-world surface — the SS pipeline records StringInterpolators
    // on every module that contains any string literal (via the implicit
    // `s"…"` form).  Without the flag CapabilityCheck refuses to invoke
    // the backend for `println("Hello")`.
    Feature.StringInterpolators,
    Feature.ModuleImports
  ),
  outputs        = Set(OutputKind.RustSource),
  options        = Set("optimizationLevel", "emitAssertions", "cargoEdition"),
  spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  blockLanguages = Set.empty
)
