package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.ir

/** Backend SPI adapter for the rust target — emits a Cargo crate that
 *  `cargo build` compiles to a native binary.
 *
 *  Phase R.1 skeleton (specs/rust-backend.md): registered through
 *  `META-INF/services` so `ServiceLoader[Backend]` discovers it, but
 *  `compile` returns an empty `Segmented` payload — actual emit lands in
 *  the next R.1 slice (rust-backend-r1-hello-emit). */
class RustBackend extends Backend with IntrinsicOverlayAwareBackend:
  def id:              String                              = "rust"
  def displayName:     String                              = "Rust (Cargo crate)"
  def spiVersion:      String                              = SpiVersion.Current
  def capabilities:    Capabilities                        = RustCapabilities
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = RustIntrinsics
  def acceptedSources: Set[String]                         = Set("scala", "scalascript", "ssc", "rust")

  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    // rust-tui-toolkit S4 — the tui target overlays the fetch + DataTable family
    // onto tui-specific runtimes (ureq fetch + ratatui Table); web keeps its stubs.
    val eff =
      if opts.extra.get("uiTarget").contains("tui") then intrinsics ++ RustTuiIntrinsics
      else intrinsics
    compileWithOverlay(module, opts, eff, runtimePreamble)

  def compileWithOverlay(
      module: ir.NormalizedModule,
      opts: BackendOptions,
      effectiveIntrinsics: Map[ir.QualifiedName, IntrinsicImpl],
      effectiveRuntimePreamble: String
  ): CompileResult =
    RustGen.generate(module, opts, effectiveIntrinsics, effectiveRuntimePreamble)
