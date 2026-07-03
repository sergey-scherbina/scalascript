package scalascript.backend.spi

/** Kinds of artifact a Backend can produce.  A Backend declares its set
 *  of supported outputs in `Capabilities.outputs`; callers (cli, server)
 *  pick a backend by intersecting their requested output with the set. */
enum OutputKind:
  case ScalaSource
  case JavaScriptSource
  case CssSource
  case HtmlSource
  case JvmBytecode
  case WasmBytecode
  case NativeBinary
  case DotNetIL
  case RustSource             // Cargo crate emitted by backend-rust (specs/rust-backend.md)
  case ExecutionResult        // for interpreter / one-shot eval
