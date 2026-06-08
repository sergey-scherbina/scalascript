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
      |
      |// ── R.3.4 — process & env intrinsics (no extra crate deps) ──
      |
      |/// `args()` — command-line arguments after the binary name.
      |/// Returns an empty Vec when invoked with no args.
      |#[allow(dead_code)]
      |pub fn _args() -> Vec<String> {
      |    std::env::args().skip(1).collect()
      |}
      |
      |/// `env(name)` — value of an environment variable, or empty string
      |/// when unset.  Returns an owned `String`; SS code can compare it
      |/// to `""` to detect unset.
      |#[allow(dead_code)]
      |pub fn _env(name: &str) -> String {
      |    std::env::var(name).unwrap_or_default()
      |}
      |
      |/// `exit(code)` — terminate the process immediately with the
      |/// given exit code.  Wraps `i64` from the SS surface to `i32`
      |/// for `std::process::exit`.
      |#[allow(dead_code)]
      |pub fn _exit(code: i64) -> ! {
      |    std::process::exit(code as i32)
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

  /** R.3.3 — JSON helpers, appended only when at least one of
   *  `jsonParse` / `jsonStringify` is reached.  Pulls in `serde_json`. */
  val JsonRs: String =
    """
      |// ── R.3.3 — JSON (uses `serde_json` crate; emitted on demand) ──
      |
      |/// `jsonParse(s)` — parse a JSON string and re-emit it in the
      |/// canonical compact form.  Panics on parse error to match the
      |/// interpreter's fail-fast contract.
      |#[allow(dead_code)]
      |pub fn _json_parse(input: &str) -> String {
      |    let v: serde_json::Value = serde_json::from_str(input)
      |        .unwrap_or_else(|e| panic!("jsonParse: {}", e));
      |    serde_json::to_string(&v)
      |        .unwrap_or_else(|e| panic!("jsonParse re-emit: {}", e))
      |}
      |
      |/// `jsonStringify(s)` — pretty-print a JSON string (`{ ... }` with
      |/// 2-space indent).  Input must already be valid JSON; the round-
      |/// trip through `serde_json::Value` validates it.
      |#[allow(dead_code)]
      |pub fn _json_stringify(input: &str) -> String {
      |    let v: serde_json::Value = serde_json::from_str(input)
      |        .unwrap_or_else(|e| panic!("jsonStringify: {}", e));
      |    serde_json::to_string_pretty(&v)
      |        .unwrap_or_else(|e| panic!("jsonStringify re-emit: {}", e))
      |}
      |""".stripMargin

  /** R.4.1 — algebraic-effects runtime, emitted as a standalone module
   *  `src/runtime/effect.rs` only when the program reaches at least one
   *  of `perform`, `handle`, `resume`, or declares an `effect E:` block.
   *  No external crate deps — pure `std`.
   *
   *  R.4.1 ships *only* the runtime infrastructure; IR lowering for
   *  `Perform` / `Handle` / `Resume` is the R.4.2 follow-up.  Programs
   *  that try to use effect ops compile their bodies through the
   *  R.2 fallback path; the effect runtime is reachable from Rust code
   *  (including the embedded `#[test]` smoke) without going through
   *  ScalaScript codegen. */
  val EffectRs: String =
    """//! Algebraic-effects runtime (Free-monad shape).
      |//!
      |//! R.4.1 ships the runtime infrastructure only — `Perform` /
      |//! `Handle` / `Resume` IR-node lowering lands in R.4.2.  The
      |//! `#[test]` at the bottom exercises a `Pure` + a single-shot
      |//! `Effect` to verify the runtime independently of codegen.
      |//!
      |//! Emitted verbatim by RustGen; do not edit by hand.
      |
      |use std::collections::HashMap;
      |
      |/// A single effect operation: `name` identifies the effect entry
      |/// (e.g. `"State.get"`), `args` carry the call-site arguments
      |/// as opaque `EffArg` values the handler interprets.
      |#[allow(dead_code)]
      |#[derive(Debug, Clone)]
      |pub struct Op {
      |    pub name: String,
      |    pub args: Vec<EffArg>,
      |}
      |
      |/// Boxed effect-runtime value.  Kept small + Clone so handlers can
      |/// inspect arguments without taking ownership.  Distinct from the
      |/// crate-wide `Value` enum so the effect runtime stays usable
      |/// without a value-system dep.
      |#[allow(dead_code)]
      |#[derive(Debug, Clone)]
      |pub enum EffArg {
      |    Unit,
      |    Bool(bool),
      |    Int(i64),
      |    Str(String),
      |}
      |
      |/// Free-monad value over an output type `A`.
      |///
      |/// `Pure(a)` is a finished computation; `Effect(op, k)` is a
      |/// suspended computation that needs the runtime to:
      |/// 1. look up `op.name` in the handler stack,
      |/// 2. invoke the handler with the args,
      |/// 3. resume the continuation `k` with the handler's reply.
      |#[allow(dead_code)]
      |pub enum Computation<A> {
      |    Pure(A),
      |    Effect(Op, Box<dyn FnOnce(EffArg) -> Computation<A>>),
      |}
      |
      |/// One handler entry — given the operation args, produce the
      |/// reply the continuation should resume with.
      |pub type Handler = Box<dyn Fn(&[EffArg]) -> EffArg>;
      |
      |/// Stack of handlers searched in registration order.  A handler
      |/// covers a single effect-op name; an unhandled op panics with a
      |/// clearly-labelled runtime error (multi-shot continuations are an
      |/// R.6 follow-up).
      |#[allow(dead_code)]
      |pub struct HandlerStack {
      |    handlers: HashMap<String, Handler>,
      |}
      |
      |#[allow(dead_code)]
      |impl HandlerStack {
      |    pub fn new() -> Self { Self { handlers: HashMap::new() } }
      |    pub fn with(mut self, name: impl Into<String>, h: Handler) -> Self {
      |        self.handlers.insert(name.into(), h);
      |        self
      |    }
      |    pub fn lookup(&self, name: &str) -> Option<&Handler> {
      |        self.handlers.get(name)
      |    }
      |}
      |
      |/// Drive a `Computation` to its `Pure` result by dispatching every
      |/// `Effect` through the handler stack.
      |#[allow(dead_code)]
      |pub fn run_with<A>(mut c: Computation<A>, h: &HandlerStack) -> A {
      |    loop {
      |        match c {
      |            Computation::Pure(a) => return a,
      |            Computation::Effect(op, k) => {
      |                let handler = h.lookup(&op.name).unwrap_or_else(|| {
      |                    panic!("no handler for effect op `{}`", op.name)
      |                });
      |                let reply = handler(&op.args);
      |                c = k(reply);
      |            }
      |        }
      |    }
      |}
      |
      |/// Convenience constructor for `Pure(a)`.
      |#[allow(dead_code)]
      |pub fn pure<A>(a: A) -> Computation<A> { Computation::Pure(a) }
      |
      |/// Convenience constructor for a single-arg effect.
      |#[allow(dead_code)]
      |pub fn perform<A>(
      |    name: impl Into<String>,
      |    args: Vec<EffArg>,
      |    k: impl FnOnce(EffArg) -> Computation<A> + 'static,
      |) -> Computation<A> {
      |    Computation::Effect(
      |        Op { name: name.into(), args },
      |        Box::new(k),
      |    )
      |}
      |
      |#[cfg(test)]
      |mod tests {
      |    use super::*;
      |
      |    #[test]
      |    fn pure_returns_its_value() {
      |        let c: Computation<i64> = pure(42);
      |        let h = HandlerStack::new();
      |        assert_eq!(run_with(c, &h), 42);
      |    }
      |
      |    #[test]
      |    fn single_effect_dispatches_through_handler() {
      |        // perform("ask", []) → handler returns Int(7) → +1 → Pure(8)
      |        let c: Computation<i64> = perform("ask", vec![], |reply| {
      |            match reply {
      |                EffArg::Int(n) => pure(n + 1),
      |                other => panic!("expected Int, got {:?}", other),
      |            }
      |        });
      |        let h = HandlerStack::new().with("ask",
      |            Box::new(|_args| EffArg::Int(7)));
      |        assert_eq!(run_with(c, &h), 8);
      |    }
      |
      |    #[test]
      |    #[should_panic(expected = "no handler for effect op `mystery`")]
      |    fn unhandled_effect_panics_with_named_diagnostic() {
      |        let c: Computation<i64> = perform("mystery", vec![], |_| pure(0));
      |        let h = HandlerStack::new();
      |        let _ = run_with(c, &h);
      |    }
      |}
      |""".stripMargin
