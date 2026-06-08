package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Intrinsic table for the rust target.
 *
 *  Phase R.1.3b — wires the console-I/O intrinsics to the runtime
 *  helpers shipped in `src/runtime/mod.rs`.  The actual call-site
 *  emission lands in `rust-backend-r1-hello-code-walk`; until then the
 *  table is reachable by `CapabilityCheck` and by code reading the
 *  registry, but no `Apply` reaches a `RuntimeCall` rewrite yet. */
val RustIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("println")          -> RuntimeCall("crate::runtime::_println"),
  QualifiedName("print")            -> RuntimeCall("crate::runtime::_print"),
  QualifiedName("Console.println")  -> RuntimeCall("crate::runtime::_println"),
  QualifiedName("Console.print")    -> RuntimeCall("crate::runtime::_print"),
  // R.3.1 — time + filesystem (no extra crate deps; uses std::time / std::fs).
  QualifiedName("nowMillis")        -> RuntimeCall("crate::runtime::_now_millis"),
  QualifiedName("readFile")         -> RuntimeCall("crate::runtime::_read_file"),
  QualifiedName("writeFile")        -> RuntimeCall("crate::runtime::_write_file"),
  // R.3.2 — crypto + base64.  RustGen scans the IR for these names and
  // only adds the `sha2` / `base64` crates to Cargo.toml when reached.
  QualifiedName("sha256")           -> RuntimeCall("crate::runtime::_sha256"),
  QualifiedName("base64Encode")     -> RuntimeCall("crate::runtime::_base64_encode"),
  QualifiedName("base64Decode")     -> RuntimeCall("crate::runtime::_base64_decode"),
  // R.3.3 — JSON.  Pulls `serde_json` into Cargo.toml only when reached.
  QualifiedName("jsonParse")        -> RuntimeCall("crate::runtime::_json_parse"),
  QualifiedName("jsonStringify")    -> RuntimeCall("crate::runtime::_json_stringify"),
  // R.3.4 — process & env (pure std, no extra crate deps).
  QualifiedName("args")             -> RuntimeCall("crate::runtime::_args"),
  QualifiedName("env")              -> RuntimeCall("crate::runtime::_env"),
  QualifiedName("exit")             -> RuntimeCall("crate::runtime::_exit"),
  // R.5 — HTTP server.  Pulls tokio + hyper + http-body-util + bytes +
  // hyper-util into Cargo.toml only when reached.
  QualifiedName("serve")            -> RuntimeCall("crate::runtime::http::_http_serve"),
  QualifiedName("route")            -> RuntimeCall("crate::runtime::http::_http_route")
)
