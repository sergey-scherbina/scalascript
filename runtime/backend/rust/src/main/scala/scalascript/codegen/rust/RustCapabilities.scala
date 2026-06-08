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
    Feature.ModuleImports,
    // R.2.2 — `var` / reassignment / `while`.
    Feature.MutableState,
    Feature.WhileLoops,
    // R.2.3 — Scala 3 `enum` + `match`.
    Feature.PatternMatching,
    // R.2.5 — `for x <- xs yield expr` + extension-method calls on
    // List/Vec; default parameters parsed but not yet used by the
    // backend, so declaring the flag now keeps it on the supported
    // surface as later slices grow.
    Feature.ForComprehensions,
    Feature.ExtensionMethods,
    Feature.DefaultParameters,
    // R.3.1 — readFile / writeFile via std::fs; nowMillis via
    // std::time::SystemTime.  No extra crate dependencies.
    Feature.FileSystem,
    // R.3.2 — sha256 + base64Encode/Decode.  Each intrinsic gates a
    // crate dependency in Cargo.toml; see RustGen.scanCryptoUsage.
    Feature.Crypto,
    // R.4.1 — algebraic-effects runtime infrastructure (Free monad
    // over `Computation<A>`) ships in `src/runtime/effect.rs` when
    // `RustGen.scanEffectUsage` detects any of `perform` / `handle` /
    // `resume` / `effect E:`.  IR-node lowering (Perform / Handle /
    // Resume → `Computation` ops) lands in R.4.2 — declaring the
    // capability now keeps CapabilityCheck from blocking programs
    // that exercise the runtime via hand-written `rust` blocks.
    Feature.AlgebraicEffects,
    // R.5 — HTTP server via hyper + tokio.  Pulled in only when the
    // program uses `serve` / `route`; programs without HTTP stay dep-free.
    Feature.HttpServer,
    // R.6 — password hashing (argon2) + JWT (jsonwebtoken + serde).
    // Pulled in only when at least one of hashPassword / verifyPassword /
    // jwtSign / jwtVerify is reached; programs without auth stay dep-free.
    Feature.Auth,
    // R.6 — guaranteed TCO via while-loop rewrite for self-tail-recursive defs.
    Feature.TailCallOptimization,
    // R.6 — WebSocket server (`wsRoute`/`wsServe`) + client (`wsConnectSync`).
    Feature.WebSockets,
    // R.6 — MCP server over stdio (JSON-RPC 2.0, hand-rolled; only serde_json dep).
    Feature.McpServer
  ),
  outputs        = Set(OutputKind.RustSource),
  options        = Set("optimizationLevel", "emitAssertions", "cargoEdition"),
  spiRange       = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  blockLanguages = Set.empty
)
