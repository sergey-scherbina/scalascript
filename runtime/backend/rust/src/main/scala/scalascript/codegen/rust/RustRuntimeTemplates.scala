package scalascript.codegen.rust

/** Fixed-template runtime files emitted into every Cargo crate by the
 *  rust target.  Phase R.1.3b — values and helpers needed by the
 *  intrinsic-emit slice that follows.
 *
 *  The contents are byte-identical across crates so the emitted assets
 *  hash predictably and downstream goldens can diff them line-for-line.
 *  Edit with care: every change requires regenerating
 *  `tests/cross/rust/hello/` goldens. */
object RustRuntimeTemplates:

  /** `src/value.rs` — closed sum-type representing every runtime value
   *  the generated code may produce.  R.2 will widen it (Closure,
   *  Computation, etc.); R.1 keeps it small. */
  val ValueRs: String =
    """//! ScalaScript runtime value enum (rust target).
      |//! Emitted verbatim by RustGen; do not edit by hand.
      |
      |#[allow(dead_code)]
      |#[derive(Debug, Clone, PartialEq)]
      |pub enum Value {
      |    Unit,
      |    Bool(bool),
      |    Int(i64),
      |    Double(f64),
      |    Str(String),
      |    Tuple(Vec<Value>),
      |    List(Vec<Value>),
      |}
      |
      |impl Value {
      |    pub fn show(&self) -> String {
      |        match self {
      |            Value::Unit      => "()".to_string(),
      |            Value::Bool(b)   => b.to_string(),
      |            Value::Int(n)    => n.to_string(),
      |            Value::Double(f) => format_double(*f),
      |            Value::Str(s)    => s.clone(),
      |            Value::Tuple(xs) => render_seq("(", ")", xs),
      |            Value::List(xs)  => render_seq("List(", ")", xs),
      |        }
      |    }
      |}
      |
      |fn format_double(f: f64) -> String {
      |    if f.fract() == 0.0 && f.is_finite() {
      |        format!("{:.1}", f)
      |    } else {
      |        f.to_string()
      |    }
      |}
      |
      |fn render_seq(open: &str, close: &str, xs: &[Value]) -> String {
      |    let parts: Vec<String> = xs.iter().map(|v| v.show()).collect();
      |    format!("{}{}{}", open, parts.join(", "), close)
      |}
      |""".stripMargin

  /** `src/runtime/mod.rs` — the helpers `RustIntrinsics` references by
   *  the `crate::runtime::_*` symbol names.  Keeping the names stable
   *  is part of the SPI between the emitter and the runtime. */
  val RuntimeModRs: String =
    """//! ScalaScript runtime helpers (rust target).
      |//! Emitted verbatim by RustGen; do not edit by hand.
      |
      |use crate::value::Value;
      |use std::fmt::Display;
      |
      |#[allow(dead_code)]
      |pub fn _show(v: &Value) -> String {
      |    v.show()
      |}
      |
      |#[allow(dead_code)]
      |pub fn _print<T: Display>(s: T) {
      |    use std::io::Write;
      |    print!("{}", s);
      |    let _ = std::io::stdout().flush();
      |}
      |
      |#[allow(dead_code)]
      |pub fn _println<T: Display>(s: T) {
      |    println!("{}", s);
      |}
      |
      |// ── R.3.1 — time + filesystem intrinsics (no extra crate deps) ──
      |
      |/// `nowMillis` — current Unix time in milliseconds, signed i64.
      |/// Mirrors the JVM target's `java.lang.System.currentTimeMillis`.
      |#[allow(dead_code)]
      |pub fn _now_millis() -> i64 {
      |    use std::time::{SystemTime, UNIX_EPOCH};
      |    SystemTime::now()
      |        .duration_since(UNIX_EPOCH)
      |        .map(|d| d.as_millis() as i64)
      |        .unwrap_or(0)
      |}
      |
      |/// `readFile(path)` — read a UTF-8 file to a String.  Panics on
      |/// I/O error to match the interpreter's fail-fast contract.
      |/// Takes the path by reference so the caller keeps ownership.
      |#[allow(dead_code)]
      |pub fn _read_file(path: &str) -> String {
      |    std::fs::read_to_string(path)
      |        .unwrap_or_else(|e| panic!("readFile({}): {}", path, e))
      |}
      |
      |/// `writeFile(path, contents)` — overwrite a file's bytes with
      |/// the given UTF-8 string.  Takes both args by reference so the
      |/// caller keeps ownership of its variables.
      |#[allow(dead_code)]
      |pub fn _write_file(path: &str, contents: &str) {
      |    std::fs::write(path, contents)
      |        .unwrap_or_else(|e| panic!("writeFile({}): {}", path, e))
      |}
      |""".stripMargin

  /** R.3.2 — sha256 helper, appended only when `sha256` is reached.
   *  The `sha2` crate dep is added to Cargo.toml in lockstep. */
  val Sha256Rs: String =
    """
      |// ── R.3.2 — sha256 (uses `sha2` crate; emitted on demand) ──
      |
      |/// `sha256(s)` — lowercase hex digest of the input bytes,
      |/// matching the interpreter's and JVM target's contract.
      |#[allow(dead_code)]
      |pub fn _sha256(input: &str) -> String {
      |    use sha2::{Sha256, Digest};
      |    format!("{:x}", Sha256::digest(input.as_bytes()))
      |}
      |""".stripMargin

  /** R.3.2 — base64 helpers, appended only when at least one of
   *  `base64Encode` / `base64Decode` is reached. */
  val Base64Rs: String =
    """
      |// ── R.3.2 — base64 (uses `base64` crate; emitted on demand) ──
      |
      |/// `base64Encode(s)` — standard base64 of the input bytes.
      |#[allow(dead_code)]
      |pub fn _base64_encode(input: &str) -> String {
      |    use base64::{Engine, engine::general_purpose};
      |    general_purpose::STANDARD.encode(input.as_bytes())
      |}
      |
      |/// `base64Decode(s)` — inverse of `base64Encode`.  Panics if the
      |/// input is not valid base64 or not valid UTF-8 after decoding.
      |#[allow(dead_code)]
      |pub fn _base64_decode(input: &str) -> String {
      |    use base64::{Engine, engine::general_purpose};
      |    let bytes = general_purpose::STANDARD.decode(input.as_bytes())
      |        .unwrap_or_else(|e| panic!("base64Decode: {}", e));
      |    String::from_utf8(bytes)
      |        .unwrap_or_else(|e| panic!("base64Decode utf8: {}", e))
      |}
      |""".stripMargin
