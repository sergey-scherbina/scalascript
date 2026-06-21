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
      |    // A reactive signal: its name (for client-side `data-ssc-*` wiring) + current value.
      |    Signal(String, Box<Value>),
      |}
      |
      |// ── Reactive signal store + computed-signal recompute (live server state) ──────
      |// String-valued (matches the `data-ssc-text` client wiring). Lives here so both
      |// `Value::signal_value` (read) and the serve runtime (read/write) reach it. Dead
      |// code for non-serve programs.
      |#[allow(dead_code)]
      |static SSC_SIGNALS: std::sync::OnceLock<std::sync::Mutex<std::collections::HashMap<String, String>>> =
      |    std::sync::OnceLock::new();
      |#[allow(dead_code)]
      |pub fn ssc_signals() -> &'static std::sync::Mutex<std::collections::HashMap<String, String>> {
      |    SSC_SIGNALS.get_or_init(|| std::sync::Mutex::new(std::collections::HashMap::new()))
      |}
      |// Computed signals registered for recompute: (generated-name, re-runnable closure).
      |type SscComputed = Vec<(String, Box<dyn Fn() -> String + Send + Sync>)>;
      |#[allow(dead_code)]
      |static SSC_COMPUTED: std::sync::OnceLock<std::sync::Mutex<SscComputed>> = std::sync::OnceLock::new();
      |#[allow(dead_code)]
      |fn ssc_computed() -> &'static std::sync::Mutex<SscComputed> {
      |    SSC_COMPUTED.get_or_init(|| std::sync::Mutex::new(Vec::new()))
      |}
      |#[allow(dead_code)]
      |static SSC_COMPUTED_N: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
      |// Register a computed signal: evaluate once for SSR, seed the store, return its name.
      |#[allow(dead_code)]
      |pub fn ssc_register_computed(f: Box<dyn Fn() -> String + Send + Sync>) -> String {
      |    let n = SSC_COMPUTED_N.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
      |    let name = format!("__c{}", n);
      |    let v = f();
      |    ssc_signals().lock().unwrap().insert(name.clone(), v);
      |    ssc_computed().lock().unwrap().push((name.clone(), f));
      |    name
      |}
      |// Re-run every computed closure (its reads now see the updated store) + write back.
      |#[allow(dead_code)]
      |pub fn ssc_recompute_all() {
      |    let results: Vec<(String, String)> = {
      |        let reg = ssc_computed().lock().unwrap();
      |        reg.iter().map(|(n, f)| (n.clone(), f())).collect()
      |    };
      |    let mut store = ssc_signals().lock().unwrap();
      |    for (n, v) in results { store.insert(n, v); }
      |}
      |
      |impl Value {
      |    pub fn show(&self) -> String {
      |        match self {
      |            Value::Unit       => "()".to_string(),
      |            Value::Bool(b)    => b.to_string(),
      |            Value::Int(n)     => n.to_string(),
      |            Value::Double(f)  => format_double(*f),
      |            Value::Str(s)     => s.clone(),
      |            Value::Tuple(xs)  => render_seq("(", ")", xs),
      |            Value::List(xs)   => render_seq("List(", ")", xs),
      |            Value::Signal(_, v) => v.show(),
      |        }
      |    }
      |    // Unwrap a signal to its current value (or itself if not a signal). Takes
      |    // `&self` + clones so a signal read `loc.signal_value()` inside a (possibly
      |    // repeatedly-called) computed-signal closure doesn't move the captured value.
      |    // LIVE: prefer the server-side store (updated by push + recompute) over the
      |    // inline SSR value, so a re-run of a computed closure sees new dependency values.
      |    #[allow(dead_code)]
      |    pub fn signal_value(&self) -> Value {
      |        match self {
      |            Value::Signal(name, v) => {
      |                if !name.is_empty() {
      |                    if let Some(s) = ssc_signals().lock().unwrap().get(name) {
      |                        return Value::Str(s.clone());
      |                    }
      |                }
      |                (**v).clone()
      |            }
      |            other => other.clone(),
      |        }
      |    }
      |}
      |
      |// Lets a `Value` be formatted with `{}` (e.g. by the std/ui `_ui_attr` coercion),
      |// reusing the same rendering as `.show()`.
      |impl std::fmt::Display for Value {
      |    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
      |        write!(f, "{}", self.show())
      |    }
      |}
      |
      |impl Value {
      |    // Truthiness for `showSignal(cond, …)` SSR: only `false`/`Unit` are falsey.
      |    // A signal unwraps to its current value.
      |    #[allow(dead_code)]
      |    pub fn is_truthy(&self) -> bool {
      |        match self {
      |            Value::Bool(false) | Value::Unit => false,
      |            Value::Signal(_, v)              => v.is_truthy(),
      |            _                                => true,
      |        }
      |    }
      |}
      |
      |// `From` conversions so a signal's initial value (`signal(name, default)`) of a
      |// primitive type can be carried as a `Value` and SSR-rendered.
      |impl From<String> for Value { fn from(s: String) -> Self { Value::Str(s) } }
      |impl From<&str>   for Value { fn from(s: &str)   -> Self { Value::Str(s.to_string()) } }
      |impl From<bool>   for Value { fn from(b: bool)   -> Self { Value::Bool(b) } }
      |impl From<i64>    for Value { fn from(n: i64)    -> Self { Value::Int(n) } }
      |impl From<f64>    for Value { fn from(f: f64)    -> Self { Value::Double(f) } }
      |// (`From<Value> for Value` is already provided by std's reflexive `impl<T> From<T> for T`.)
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
      |/// `env(key)` — value of an environment variable as `Option[String]`.
      |/// Returns `None` when the variable is unset or contains invalid UTF-8.
      |#[allow(dead_code)]
      |pub fn _env(name: &str) -> Option<String> {
      |    std::env::var(name).ok()
      |}
      |
      |/// `envOrElse(key, default)` — env var value or a fallback string.
      |#[allow(dead_code)]
      |pub fn _env_or_else(name: &str, default: &str) -> String {
      |    std::env::var(name).unwrap_or_else(|_| default.to_string())
      |}
      |
      |/// `exit(code)` — terminate the process immediately with the
      |/// given exit code.  Wraps `i64` from the SS surface to `i32`
      |/// for `std::process::exit`.
      |#[allow(dead_code)]
      |pub fn _exit(code: i64) -> ! {
      |    std::process::exit(code as i32)
      |}
      |
      |// ── std.fs — full filesystem API (pure std::fs, no extra crates) ──
      |
      |#[allow(dead_code)]
      |pub fn _append_file(path: &str, contents: &str) {
      |    use std::io::Write;
      |    let mut f = std::fs::OpenOptions::new().create(true).append(true)
      |        .open(path)
      |        .unwrap_or_else(|e| panic!("appendFile({}): {}", path, e));
      |    f.write_all(contents.as_bytes())
      |        .unwrap_or_else(|e| panic!("appendFile({}): {}", path, e));
      |}
      |
      |#[allow(dead_code)]
      |pub fn _read_bytes(path: &str) -> Vec<i64> {
      |    std::fs::read(path)
      |        .unwrap_or_else(|e| panic!("readBytes({}): {}", path, e))
      |        .into_iter().map(|b| b as i64).collect()
      |}
      |
      |#[allow(dead_code)]
      |pub fn _write_bytes(path: &str, bytes: Vec<i64>) {
      |    let data: Vec<u8> = bytes.into_iter().map(|b| b as u8).collect();
      |    std::fs::write(path, data)
      |        .unwrap_or_else(|e| panic!("writeBytes({}): {}", path, e));
      |}
      |
      |#[allow(dead_code)]
      |pub fn _exists(path: &str) -> bool { std::path::Path::new(path).exists() }
      |
      |#[allow(dead_code)]
      |pub fn _is_file(path: &str) -> bool { std::path::Path::new(path).is_file() }
      |
      |#[allow(dead_code)]
      |pub fn _is_dir(path: &str) -> bool { std::path::Path::new(path).is_dir() }
      |
      |#[allow(dead_code)]
      |pub fn _mkdir(path: &str) {
      |    let p = std::path::Path::new(path);
      |    if !p.exists() { std::fs::create_dir(p)
      |        .unwrap_or_else(|e| panic!("mkdir({}): {}", path, e)); }
      |}
      |
      |#[allow(dead_code)]
      |pub fn _mkdirs(path: &str) {
      |    std::fs::create_dir_all(path)
      |        .unwrap_or_else(|e| panic!("mkdirs({}): {}", path, e));
      |}
      |
      |#[allow(dead_code)]
      |pub fn _list_dir(path: &str) -> Vec<String> {
      |    std::fs::read_dir(path)
      |        .unwrap_or_else(|e| panic!("listDir({}): {}", path, e))
      |        .filter_map(|e| e.ok())
      |        .map(|e| e.file_name().to_string_lossy().into_owned())
      |        .collect()
      |}
      |
      |#[allow(dead_code)]
      |pub fn _delete_file(path: &str) {
      |    let p = std::path::Path::new(path);
      |    if p.is_dir() { std::fs::remove_dir_all(p) } else { std::fs::remove_file(p) }
      |        .unwrap_or_else(|e| panic!("deleteFile({}): {}", path, e));
      |}
      |
      |#[allow(dead_code)]
      |pub fn _copy_file(src: &str, dst: &str) {
      |    std::fs::copy(src, dst)
      |        .unwrap_or_else(|e| panic!("copyFile({}, {}): {}", src, dst, e));
      |}
      |
      |#[allow(dead_code)]
      |pub fn _move_file(src: &str, dst: &str) {
      |    std::fs::rename(src, dst)
      |        .unwrap_or_else(|e| panic!("moveFile({}, {}): {}", src, dst, e));
      |}
      |
      |// ── std.os — OS environment (pure std::env, std::path) ──
      |
      |#[allow(dead_code)]
      |pub fn _cwd() -> String {
      |    std::env::current_dir()
      |        .unwrap_or_else(|e| panic!("cwd: {}", e))
      |        .to_string_lossy().into_owned()
      |}
      |
      |#[allow(dead_code)]
      |pub fn _sep() -> String { std::path::MAIN_SEPARATOR.to_string() }
      |
      |#[allow(dead_code)]
      |pub fn _path_join(parts: Vec<String>) -> String {
      |    parts.iter().fold(std::path::PathBuf::new(), |mut p, s| { p.push(s); p })
      |        .to_string_lossy().into_owned()
      |}
      |
      |#[allow(dead_code)]
      |pub fn _path_dirname(path: &str) -> String {
      |    std::path::Path::new(path).parent()
      |        .map(|p| p.to_string_lossy().into_owned())
      |        .unwrap_or_default()
      |}
      |
      |#[allow(dead_code)]
      |pub fn _path_basename(path: &str) -> String {
      |    std::path::Path::new(path).file_name()
      |        .map(|n| n.to_string_lossy().into_owned())
      |        .unwrap_or_default()
      |}
      |
      |#[allow(dead_code)]
      |pub fn _path_extname(path: &str) -> String {
      |    std::path::Path::new(path).extension()
      |        .map(|e| format!(".{}", e.to_string_lossy()))
      |        .unwrap_or_default()
      |}
      |
      |#[allow(dead_code)]
      |pub fn _path_resolve(path: &str) -> String {
      |    std::fs::canonicalize(path)
      |        .unwrap_or_else(|_| std::path::PathBuf::from(path))
      |        .to_string_lossy().into_owned()
      |}
      |
      |#[allow(dead_code)]
      |pub fn _path_is_absolute(path: &str) -> bool {
      |    std::path::Path::new(path).is_absolute()
      |}
      |
      |#[allow(dead_code)]
      |pub fn _temp_dir() -> String {
      |    std::env::temp_dir().to_string_lossy().into_owned()
      |}
      |
      |#[allow(dead_code)]
      |pub fn _temp_file(prefix: &str, suffix: &str) -> String {
      |    let dir = std::env::temp_dir();
      |    let name = format!("{}{}{}", prefix, std::process::id(), suffix);
      |    dir.join(name).to_string_lossy().into_owned()
      |}
      |
      |/// `platform` — always "Native" on the Rust target.
      |#[allow(dead_code)]
      |pub fn _platform() -> String { "Native".to_string() }
      |
      |#[allow(dead_code)]
      |pub fn _homedir() -> String {
      |    std::env::var("HOME")
      |        .or_else(|_| std::env::var("USERPROFILE"))
      |        .unwrap_or_else(|_| ".".to_string())
      |}
      |
      |#[allow(dead_code)]
      |pub fn _hostname() -> String {
      |    std::env::var("HOSTNAME")
      |        .or_else(|_| std::env::var("COMPUTERNAME"))
      |        .unwrap_or_else(|_| "localhost".to_string())
      |}
      |
      |// ── std.process — exec via std::process::Command ──
      |
      |#[derive(Debug, Clone)]
      |pub struct ProcessResult {
      |    pub stdout:   String,
      |    pub stderr:   String,
      |    pub exitCode: i64,
      |}
      |
      |#[allow(dead_code)]
      |pub fn _exec(cmd: &str, args: Vec<String>) -> ProcessResult {
      |    let out = std::process::Command::new(cmd)
      |        .args(&args)
      |        .output()
      |        .unwrap_or_else(|e| panic!("exec({}): {}", cmd, e));
      |    ProcessResult {
      |        stdout:   String::from_utf8_lossy(&out.stdout).into_owned(),
      |        stderr:   String::from_utf8_lossy(&out.stderr).into_owned(),
      |        exitCode: out.status.code().unwrap_or(-1) as i64,
      |    }
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

  /** R.6 — Auth helpers: argon2 password hashing + HS256 JWT.
   *  Emitted as `src/runtime/auth.rs` only when at least one of
   *  `hashPassword` / `verifyPassword` / `jwtSign` / `jwtVerify` is reached.
   *  Pulls in `argon2 = "0.5"`, `jsonwebtoken = "9"`,
   *  `serde = { version = "1", features = ["derive"] }`. */
  val AuthRs: String =
    """
      |// ── R.6 — auth (argon2 + jsonwebtoken; emitted on demand) ──
      |
      |use argon2::{
      |    password_hash::{
      |        PasswordHash, PasswordHasher, PasswordVerifier,
      |        SaltString, rand_core::OsRng,
      |    },
      |    Argon2,
      |};
      |use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
      |use serde::{Deserialize, Serialize};
      |
      |#[derive(Serialize, Deserialize)]
      |struct _JwtClaims {
      |    sub: String,
      |    exp: u64,
      |}
      |
      |/// `hashPassword(password)` — Argon2id hash with random salt.
      |/// Returns the PHC-format hash string.
      |#[allow(dead_code)]
      |pub fn _hash_password(password: &str) -> String {
      |    let salt = SaltString::generate(&mut OsRng);
      |    Argon2::default()
      |        .hash_password(password.as_bytes(), &salt)
      |        .expect("hashPassword: argon2 hash failed")
      |        .to_string()
      |}
      |
      |/// `verifyPassword(hash, password)` — verify a PHC-format argon2 hash.
      |/// Returns `true` if the password matches the hash.
      |#[allow(dead_code)]
      |pub fn _verify_password(hash: &str, password: &str) -> bool {
      |    let parsed = PasswordHash::new(hash)
      |        .expect("verifyPassword: invalid hash format");
      |    Argon2::default()
      |        .verify_password(password.as_bytes(), &parsed)
      |        .is_ok()
      |}
      |
      |/// `jwtSign(payload, secret)` — sign `payload` string as the `sub`
      |/// claim in an HS256 JWT.  Expiry is set to the far future.
      |#[allow(dead_code)]
      |pub fn _jwt_sign(payload: &str, secret: &str) -> String {
      |    let claims = _JwtClaims { sub: payload.to_string(), exp: 9_999_999_999 };
      |    encode(
      |        &Header::default(),
      |        &claims,
      |        &EncodingKey::from_secret(secret.as_bytes()),
      |    )
      |    .expect("jwtSign: encode failed")
      |}
      |
      |/// `jwtVerify(token, secret)` — verify an HS256 JWT and return the `sub`
      |/// claim (i.e. the payload passed to `jwtSign`).  Panics on invalid token.
      |#[allow(dead_code)]
      |pub fn _jwt_verify(token: &str, secret: &str) -> String {
      |    let mut val = Validation::new(jsonwebtoken::Algorithm::HS256);
      |    val.validate_exp = true;
      |    decode::<_JwtClaims>(
      |        token,
      |        &DecodingKey::from_secret(secret.as_bytes()),
      |        &val,
      |    )
      |    .expect("jwtVerify: invalid token")
      |    .claims
      |    .sub
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

  /** std/ui — server-side `View` tree + HTML render (SSR).  Emitted as
   *  `src/runtime/ui.rs` only when `element`/`textNode`/`fragment` are
   *  reached.  `_ui_render` walks the tree, escaping text + attribute
   *  values.  This is the HTML/SSR binding of the std/ui primitives
   *  (spec: rust-web-toolkit.md, increment S1). */
  val UiRs: String =
    """//! std/ui — server-side rendering of the View tree to HTML (SSR).
      |//! Emitted verbatim by RustGen when element/textNode/fragment are reached.
      |
      |#[derive(Clone, Debug)]
      |pub enum View {
      |    Element { tag: String, attrs: Vec<(String, String)>, children: Vec<View> },
      |    Text(String),
      |    Fragment(Vec<View>),
      |}
      |
      |pub fn _ui_element(
      |    tag: String,
      |    attrs: std::collections::HashMap<String, String>,
      |    events: std::collections::HashMap<String, crate::value::Value>,
      |    children: Vec<View>,
      |) -> View {
      |    let mut attrs: Vec<(String, String)> = attrs.into_iter().collect();
      |    // A signal-bound event handler (e.g. `inputChange`) is encoded as a marker string;
      |    // surface it as a `data-ssc-*` attribute the client reactivity runtime reads.
      |    for (_evt, handler) in events {
      |        if let crate::value::Value::Str(s) = handler {
      |            if let Some(name) = s.strip_prefix("ssc-input:") {
      |                attrs.push(("data-ssc-input".to_string(), name.to_string()));
      |            } else if let Some(rest) = s.strip_prefix("ssc-set:") {
      |                // rest = "<name>:<value>"; client splits on the first ':'.
      |                attrs.push(("data-ssc-set".to_string(), rest.to_string()));
      |            } else if let Some(name) = s.strip_prefix("ssc-toggle:") {
      |                attrs.push(("data-ssc-toggle".to_string(), name.to_string()));
      |            }
      |        }
      |    }
      |    // Key-sorted attribute order → deterministic SSR output (HashMap is unordered).
      |    attrs.sort_by(|a, b| a.0.cmp(&b.0));
      |    View::Element { tag, attrs, children }
      |}
      |
      |pub fn _ui_text(s: String) -> View { View::Text(s) }
      |
      |pub fn _ui_fragment(children: Vec<View>) -> View { View::Fragment(children) }
      |
      |// Client reactivity runtime appended by `serve(view, port)`:
      |//  (1) local — a signal-bound input (`data-ssc-input`) mirrors into `[data-ssc-text]`;
      |//  (2) set/toggle — a `data-ssc-set="<name>:<value>"` element sets the signal on click,
      |//      a `data-ssc-toggle="<name>"` flips its boolean; both patch `[data-ssc-text]` locally
      |//      (via `_sscState`) and persist to the server (`/__ssc/push`) so the poll doesn't revert;
      |//  (3) server-push — poll `/__ssc/state` and patch `[data-ssc-text]` from server-side
      |//      signal updates (e.g. a new chat message pushed to `/__ssc/push`).
      |#[allow(dead_code)]
      |pub const _UI_CLIENT_SCRIPT: &str = "<script>var _sscState={};function _sscSet(n,v){_sscState[n]=v;document.querySelectorAll('[data-ssc-text=\"'+n+'\"]').forEach(function(el){el.textContent=v;});}function _sscPush(n,v){try{fetch('/__ssc/push?name='+encodeURIComponent(n)+'&value='+encodeURIComponent(v));}catch(e){}}document.addEventListener('input',function(e){var n=e.target.getAttribute('data-ssc-input');if(n==null)return;_sscSet(n,e.target.value);});document.addEventListener('click',function(e){var s=e.target.getAttribute('data-ssc-set');if(s!=null){var i=s.indexOf(':');var n=s.slice(0,i);var v=s.slice(i+1);_sscSet(n,v);_sscPush(n,v);}var t=e.target.getAttribute('data-ssc-toggle');if(t!=null){var nv=(_sscState[t]==='true'||_sscState[t]===true)?'false':'true';_sscSet(t,nv);_sscPush(t,nv);}});function _sscApply(s){for(var n in s){_sscSet(n,s[n]);}}if(typeof EventSource!=='undefined'){var _es=new EventSource('/__ssc/events');_es.onmessage=function(e){try{_sscApply(JSON.parse(e.data));}catch(_){}};}else{setInterval(function(){fetch('/__ssc/state').then(function(r){return r.json();}).then(_sscApply).catch(function(){});},1000);}</script>";
      |
      |/// Render a `View` to an HTML string (SSR).  Takes the tree by value so it
      |/// can be the tail of an SSR entry (`renderHtml(view)`); recursion borrows
      |/// through `_ui_render_ref`.
      |pub fn _ui_render(v: View) -> String { _ui_render_ref(&v) }
      |
      |fn _ui_render_ref(v: &View) -> String {
      |    match v {
      |        View::Text(s) => _ui_escape_text(s),
      |        View::Fragment(cs) => cs.iter().map(_ui_render_ref).collect::<Vec<_>>().join(""),
      |        View::Element { tag, attrs, children } => {
      |            let mut s = String::new();
      |            s.push('<');
      |            s.push_str(tag);
      |            for (k, val) in attrs {
      |                s.push(' ');
      |                s.push_str(k);
      |                s.push_str("=\"");
      |                s.push_str(&_ui_escape_attr(val));
      |                s.push('"');
      |            }
      |            s.push('>');
      |            // HTML void elements (meta/br/img/…) take no closing tag.
      |            if !(_ui_is_void(tag) && children.is_empty()) {
      |                for c in children { s.push_str(&_ui_render_ref(c)); }
      |                s.push_str("</");
      |                s.push_str(tag);
      |                s.push('>');
      |            }
      |            s
      |        }
      |    }
      |}
      |
      |fn _ui_is_void(tag: &str) -> bool {
      |    matches!(tag,
      |        "area" | "base" | "br" | "col" | "embed" | "hr" | "img" | "input"
      |        | "link" | "meta" | "param" | "source" | "track" | "wbr")
      |}
      |
      |fn _ui_escape_text(s: &str) -> String {
      |    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;")
      |}
      |
      |fn _ui_escape_attr(s: &str) -> String {
      |    _ui_escape_text(s).replace('"', "&quot;")
      |}
      |
      |// ── std/ui signal primitives — SSR stubs ──────────────────────────────────
      |// Signals/EventHandlers are `crate::value::Value` on Rust. For static SSR these
      |// render the initial branch / no-op; full reactivity ships via the JS target.
      |use crate::value::Value;
      |
      |// Coerce any attribute value (String / bool / i64 / Value) to a String — HTML
      |// attribute values are strings, and a Rust HashMap needs one value type.
      |#[allow(dead_code)]
      |pub fn _ui_attr<T: std::fmt::Display>(v: T) -> String { v.to_string() }
      |
      |// Signals carry their initial value as a `Value` so the *initial* render is SSR'd
      |// (static reactivity).  Live updates (client JS / server push) are a later slice;
      |// the event-handler primitives below stay inert for SSR.
      |// A signal carries its name (for client `data-ssc-*` wiring) + current value.
      |#[allow(dead_code)]
      |pub fn _ui_signal<T: Into<Value>>(name: String, default: T) -> Value {
      |    let v = default.into();
      |    // Seed the live store with the initial value (only if absent) so signal reads
      |    // and recompute resolve against it consistently from the first render.
      |    let sv = v.show();
      |    crate::value::ssc_signals().lock().unwrap().entry(name.clone()).or_insert(sv);
      |    Value::Signal(name, Box::new(v))
      |}
      |// signalText(s) → a `<span data-ssc-text="<name>">value</span>` so the client runtime
      |// can live-patch its text; a non-signal value renders as plain text.
      |#[allow(dead_code)]
      |pub fn _ui_signal_text(s: Value) -> View {
      |    match s {
      |        Value::Signal(name, v) => View::Element {
      |            tag: "span".to_string(),
      |            attrs: vec![("data-ssc-text".to_string(), name)],
      |            children: vec![View::Text(v.show())],
      |        },
      |        other => View::Text(other.show()),
      |    }
      |}
      |#[allow(dead_code)]
      |pub fn _ui_show_signal(cond: Value, when_true: View, when_false: View) -> View {
      |    if cond.is_truthy() { when_true } else { when_false }
      |}
      |// setSignal(s, v) in an event map (e.g. `["click" -> setSignal(sig, v)]`) marks the
      |// element so the client runtime sets the bound signal to `v` on the event. Encoded as
      |// `ssc-set:<name>:<value>` (name is an identifier → first ':' after the prefix splits it).
      |#[allow(dead_code)]
      |pub fn _ui_set_signal<T: Into<Value>>(s: Value, v: T) -> Value {
      |    match s {
      |        Value::Signal(name, _) => Value::Str(format!("ssc-set:{}:{}", name, v.into().show())),
      |        _                      => Value::Unit,
      |    }
      |}
      |// inputChange(s) marks the bound input so the client runtime mirrors its value into
      |// every `[data-ssc-text="<name>"]`.  `_ui_element` turns this marker into an attribute.
      |#[allow(dead_code)]
      |pub fn _ui_input_change(s: Value) -> Value {
      |    match s {
      |        Value::Signal(name, _) => Value::Str(format!("ssc-input:{}", name)),
      |        _                      => Value::Unit,
      |    }
      |}
      |// toggleSignal(s) marks the element so the client runtime flips the bound boolean signal
      |// on the event (e.g. `["change" -> toggleSignal(checked)]`). Encoded as `ssc-toggle:<name>`.
      |#[allow(dead_code)]
      |pub fn _ui_toggle_signal(s: Value) -> Value {
      |    match s {
      |        Value::Signal(name, _) => Value::Str(format!("ssc-toggle:{}", name)),
      |        _                      => Value::Unit,
      |    }
      |}
      |#[allow(dead_code)]
      |pub fn _ui_eq_signal<T: Into<Value>>(s: Value, value: T) -> Value {
      |    Value::Bool(s.signal_value() == value.into())
      |}
      |// computedSignal(f) — derive a String signal; evaluate the thunk for the initial SSR
      |// value (it reads other signals' current values).  Client recompute on a dependency
      |// change is a later slice; the result is an anonymous signal (no `data-ssc-*` name).
      |#[allow(dead_code)]
      |pub fn _ui_computed_signal<F: Fn() -> String + Send + Sync + 'static>(f: F) -> Value {
      |    // Register the closure for live recompute (named `__cN`), seed the store with its
      |    // initial value, and return the NAMED signal so signalText emits data-ssc-text="__cN".
      |    let name = crate::value::ssc_register_computed(Box::new(f));
      |    let v = crate::value::ssc_signals().lock().unwrap().get(&name).cloned().unwrap_or_default();
      |    Value::Signal(name, Box::new(Value::Str(v)))
      |}
      |// seedSignal(name, source) — a named signal seeded from another signal's current value.
      |#[allow(dead_code)]
      |pub fn _ui_seed_signal(name: String, source: Value) -> Value {
      |    Value::Signal(name, Box::new(source.signal_value()))
      |}
      |#[allow(dead_code)]
      |pub fn _ui_data_table_view(_source: Value, _columns: Vec<Value>, _actions: Vec<Value>) -> View {
      |    View::Fragment(vec![])
      |}
      |""".stripMargin

  /** R.5 — HTTP server runtime helpers.  Emitted as `src/runtime/http.rs`
   *  only when `serve` / `route` are reached in the program source.
   *  Handler takes `String` (matches SS `String => String` surface). */
  val HttpRs: String =
    """//! HTTP server runtime (R.5).
      |//! Emitted verbatim by RustGen when serve/route are reached.
      |
      |use std::sync::{Arc, Mutex};
      |use std::net::SocketAddr;
      |use bytes::Bytes;
      |use http_body_util::Full;
      |use hyper::{Request, Response, StatusCode};
      |use hyper::body::Incoming;
      |use hyper::server::conn::http1;
      |use hyper::service::service_fn;
      |use tokio::net::TcpListener;
      |
      |pub type RouteHandler = Box<dyn Fn(&str) -> String + Send + Sync>;
      |
      |static ROUTES: std::sync::OnceLock<
      |    Arc<Mutex<Vec<(String, String, Arc<RouteHandler>)>>>
      |> = std::sync::OnceLock::new();
      |
      |fn routes() -> &'static Arc<Mutex<Vec<(String, String, Arc<RouteHandler>)>>> {
      |    ROUTES.get_or_init(|| Arc::new(Mutex::new(Vec::new())))
      |}
      |
      |/// `route(method, path, handler)` — register a route.
      |/// Handler takes `String` (matching ScalaScript String surface).
      |#[allow(dead_code)]
      |pub fn _http_route(
      |    method:  String,
      |    path:    String,
      |    handler: impl Fn(String) -> String + Send + Sync + 'static,
      |) {
      |    let h: RouteHandler = Box::new(move |s: &str| handler(s.to_string()));
      |    routes().lock().unwrap().push((
      |        method.to_uppercase(),
      |        path,
      |        Arc::new(h),
      |    ));
      |}
      |
      |/// `serve(port)` — start the HTTP server and block until killed.
      |#[allow(dead_code)]
      |pub fn _http_serve(port: i64) {
      |    let rt = tokio::runtime::Runtime::new().expect("tokio runtime");
      |    rt.block_on(async move {
      |        let addr = SocketAddr::from(([0, 0, 0, 0], port as u16));
      |        let listener = TcpListener::bind(addr).await
      |            .unwrap_or_else(|e| panic!("serve({}): {}", port, e));
      |        eprintln!("Listening on http://{}", listener.local_addr().unwrap());
      |        loop {
      |            let (stream, _) = listener.accept().await
      |                .unwrap_or_else(|e| panic!("accept: {}", e));
      |            let io = hyper_util::rt::TokioIo::new(stream);
      |            tokio::task::spawn(async move {
      |                let _ = http1::Builder::new()
      |                    .serve_connection(io, service_fn(handle_request))
      |                    .await;
      |            });
      |        }
      |    });
      |}
      |
      |async fn handle_request(
      |    req: Request<Incoming>,
      |) -> Result<Response<Full<Bytes>>, hyper::Error> {
      |    let method = req.method().to_string();
      |    let path   = req.uri().path().to_owned();
      |    let guard  = routes().lock().unwrap();
      |    for (m, p, h) in guard.iter() {
      |        if m == &method && p == &path {
      |            let body = h(&path);
      |            return Ok(Response::builder()
      |                .status(StatusCode::OK)
      |                .header("Content-Type", "text/plain; charset=utf-8")
      |                .body(Full::new(Bytes::from(body)))
      |                .unwrap());
      |        }
      |    }
      |    Ok(Response::builder()
      |        .status(StatusCode::NOT_FOUND)
      |        .body(Full::new(Bytes::from("Not Found")))
      |        .unwrap())
      |}
      |""".stripMargin

  /** std/ui `serve(view, port)` — SSR overload.  Appended to `http.rs` ONLY when
   *  the program also uses the View primitives (uiUsage), since it references
   *  `crate::runtime::ui`.  A pure `route`/`serve(port)` program omits it. */
  val UiServeRs: String =
    """
      |// Server-side signal store: a `broadcastSignal(name, value)` or a `GET /__ssc/push?name=
      |// &value=` updates it; connected clients pick up the change on their next `/__ssc/state`
      |// poll (the client runtime patches every `[data-ssc-text="<name>"]`).  This is the
      |// server-push bridge — e.g. rozum's meeting daemon hits `/__ssc/push` on a new message.
      |// The signal store lives in `crate::value` so `Value::signal_value` can read it too.
      |use crate::value::{ssc_signals, ssc_recompute_all};
      |// SSE push transport: a broadcast channel carrying the full signal-state JSON.
      |// `/__ssc/push` (and `_ui_broadcast_signal`) send on it; `/__ssc/events` streams
      |// `data: <json>\n\n` to every connected client, so updates arrive immediately
      |// instead of on the next 1 s poll. The poll endpoint stays as a fallback.
      |static SSC_EVENTS: std::sync::OnceLock<tokio::sync::broadcast::Sender<String>> =
      |    std::sync::OnceLock::new();
      |fn ssc_events() -> &'static tokio::sync::broadcast::Sender<String> {
      |    SSC_EVENTS.get_or_init(|| tokio::sync::broadcast::channel::<String>(64).0)
      |}
      |// Snapshot the signal store as a JSON object string (shared by /__ssc/state and
      |// the SSE broadcast).
      |fn ssc_state_json() -> String {
      |    let m = ssc_signals().lock().unwrap();
      |    let entries: Vec<String> = m.iter()
      |        .map(|(k, v)| format!("{}:{}", ssc_json_escape(k), ssc_json_escape(v)))
      |        .collect();
      |    format!("{{{}}}", entries.join(","))
      |}
      |// Update a signal, recompute every derived (computed) signal so they reflect the new
      |// dependency value, then notify SSE subscribers with the full state. `let _ =` on send:
      |// an error just means no clients are currently subscribed, which is fine.
      |fn ssc_set_and_notify(name: String, value: String) {
      |    ssc_signals().lock().unwrap().insert(name, value);
      |    ssc_recompute_all();
      |    let _ = ssc_events().send(ssc_state_json());
      |}
      |#[allow(dead_code)]
      |pub fn _ui_broadcast_signal(name: String, value: String) {
      |    ssc_set_and_notify(name, value);
      |}
      |fn ssc_json_escape(s: &str) -> String {
      |    let mut o = String::with_capacity(s.len() + 2);
      |    o.push('"');
      |    for c in s.chars() {
      |        match c {
      |            '"'  => o.push_str("\\\""),
      |            '\\' => o.push_str("\\\\"),
      |            '\n' => o.push_str("\\n"),
      |            '\r' => o.push_str("\\r"),
      |            '\t' => o.push_str("\\t"),
      |            _    => o.push(c),
      |        }
      |    }
      |    o.push('"');
      |    o
      |}
      |fn ssc_urldecode(s: &str) -> String {
      |    let b = s.as_bytes();
      |    let mut o = String::with_capacity(b.len());
      |    let mut i = 0;
      |    while i < b.len() {
      |        match b[i] {
      |            b'+' => { o.push(' '); i += 1; }
      |            b'%' if i + 2 < b.len() => {
      |                let h = |c: u8| (c as char).to_digit(16);
      |                match (h(b[i + 1]), h(b[i + 2])) {
      |                    (Some(hi), Some(lo)) => { o.push((hi * 16 + lo) as u8 as char); i += 3; }
      |                    _ => { o.push('%'); i += 1; }
      |                }
      |            }
      |            c => { o.push(c as char); i += 1; }
      |        }
      |    }
      |    o
      |}
      |
      |/// `serve(view, port)` — SSR the View tree once, then serve it (with a tiny reactivity
      |/// runtime) plus `/__ssc/state` (signal JSON) and `/__ssc/push` (server-side update).
      |#[allow(dead_code)]
      |// direct-WS signal transport: a WebSocket server on `ws_port` (http port + 1) for
      |// external/programmatic clients (the rozum bridge). Bidirectional + shares the same
      |// store/broadcast/recompute as SSE: on connect it sends the current state JSON and then
      |// streams every update; each incoming text frame `name=value` SETS a signal (→ recompute
      |// + broadcast), so a WS client both observes and drives signals.
      |async fn ssc_ws_serve(ws_port: u16) {
      |    use futures_util::{SinkExt, StreamExt};
      |    use tokio_tungstenite::tungstenite::Message;
      |    let addr = SocketAddr::from(([0, 0, 0, 0], ws_port));
      |    let listener = match TcpListener::bind(addr).await {
      |        Ok(l) => l,
      |        Err(e) => { eprintln!("signal ws bind {}: {}", ws_port, e); return; }
      |    };
      |    eprintln!("Signal WS on ws://{}", listener.local_addr().unwrap());
      |    loop {
      |        let (stream, _) = match listener.accept().await { Ok(p) => p, Err(_) => continue };
      |        tokio::spawn(async move {
      |            let ws = match tokio_tungstenite::accept_async(stream).await { Ok(w) => w, Err(_) => return };
      |            let (mut tx, mut rx) = ws.split();
      |            if tx.send(Message::Text(ssc_state_json())).await.is_err() { return; }
      |            let mut sub = ssc_events().subscribe();
      |            loop {
      |                tokio::select! {
      |                    incoming = rx.next() => match incoming {
      |                        Some(Ok(Message::Text(t))) => {
      |                            if let Some(eq) = t.find('=') {
      |                                let (n, v) = (t[..eq].to_string(), t[eq + 1..].to_string());
      |                                if !n.is_empty() { ssc_set_and_notify(n, v); }
      |                            }
      |                        }
      |                        Some(Ok(_)) => {}
      |                        _ => break,
      |                    },
      |                    update = sub.recv() => match update {
      |                        Ok(json) => { if tx.send(Message::Text(json)).await.is_err() { break; } }
      |                        Err(_)   => {}
      |                    },
      |                }
      |            }
      |        });
      |    }
      |}
      |
      |pub fn _ui_serve(tree: crate::runtime::ui::View, port: i64) {
      |    let body = crate::runtime::ui::_ui_render(tree);
      |    let html = Arc::new(format!(
      |        "<!doctype html><meta charset=\"utf-8\">{}{}",
      |        body, crate::runtime::ui::_UI_CLIENT_SCRIPT));
      |    let rt = tokio::runtime::Runtime::new().expect("tokio runtime");
      |    rt.block_on(async move {
      |        let addr = SocketAddr::from(([0, 0, 0, 0], port as u16));
      |        let listener = TcpListener::bind(addr).await
      |            .unwrap_or_else(|e| panic!("serve(view, {}): {}", port, e));
      |        eprintln!("Listening on http://{}", listener.local_addr().unwrap());
      |        // direct-WS signal endpoint for external/programmatic clients (e.g. the rozum
      |        // bridge), on http port + 1. Shares the store/broadcast/recompute with SSE.
      |        tokio::spawn(ssc_ws_serve((port as u16).wrapping_add(1)));
      |        loop {
      |            let (stream, _) = listener.accept().await
      |                .unwrap_or_else(|e| panic!("accept: {}", e));
      |            let io = hyper_util::rt::TokioIo::new(stream);
      |            let page = html.clone();
      |            tokio::task::spawn(async move {
      |                let svc = service_fn(move |req: Request<Incoming>| {
      |                    let page = page.clone();
      |                    async move {
      |                        use http_body_util::BodyExt;
      |                        type SscBody = http_body_util::combinators::BoxBody<Bytes, std::convert::Infallible>;
      |                        let path = req.uri().path().to_owned();
      |                        let resp: Response<SscBody> = if path == "/__ssc/events" {
      |                            // Server-Sent Events: push signal-state updates immediately.
      |                            use tokio_stream::StreamExt;
      |                            let rx = ssc_events().subscribe();
      |                            let initial = tokio_stream::once(Ok::<_, std::convert::Infallible>(
      |                                hyper::body::Frame::data(Bytes::from(
      |                                    format!("data: {}\n\n", ssc_state_json())))));
      |                            let updates = tokio_stream::wrappers::BroadcastStream::new(rx)
      |                                .filter_map(|m| m.ok().map(|json| Ok::<_, std::convert::Infallible>(
      |                                    hyper::body::Frame::data(Bytes::from(format!("data: {}\n\n", json))))));
      |                            let body = http_body_util::StreamBody::new(initial.chain(updates));
      |                            Response::builder()
      |                                .status(StatusCode::OK)
      |                                .header("Content-Type", "text/event-stream")
      |                                .header("Cache-Control", "no-cache")
      |                                .body(body.boxed())
      |                                .unwrap()
      |                        } else {
      |                            let (ctype, payload) = if path == "/__ssc/state" {
      |                                ("application/json", ssc_state_json())
      |                            } else if path == "/__ssc/push" {
      |                                let q = req.uri().query().unwrap_or("").to_owned();
      |                                let (mut name, mut value) = (String::new(), String::new());
      |                                for kv in q.split('&') {
      |                                    let mut it = kv.splitn(2, '=');
      |                                    match (it.next(), it.next()) {
      |                                        (Some("name"),  Some(v)) => name  = ssc_urldecode(v),
      |                                        (Some("value"), Some(v)) => value = ssc_urldecode(v),
      |                                        _ => {}
      |                                    }
      |                                }
      |                                if !name.is_empty() { ssc_set_and_notify(name, value); }
      |                                ("text/plain; charset=utf-8", "ok".to_string())
      |                            } else {
      |                                ("text/html; charset=utf-8", (*page).clone())
      |                            };
      |                            Response::builder()
      |                                .status(StatusCode::OK)
      |                                .header("Content-Type", ctype)
      |                                .body(Full::new(Bytes::from(payload)).boxed())
      |                                .unwrap()
      |                        };
      |                        Ok::<_, hyper::Error>(resp)
      |                    }
      |                });
      |                let _ = http1::Builder::new().serve_connection(io, svc).await;
      |            });
      |        }
      |    });
      |}
      |""".stripMargin

  /** R.6 — WebSocket server + client helpers.
   *  `wsRoute(path, handler)` registers a string-echo handler; `wsServe(port)`
   *  starts the server; `wsConnectSync(url, handler)` is a blocking client.
   *  Emitted only when any ws intrinsic is reached.
   *  Deps: `tokio-tungstenite = "0.21"`, `futures-util = "0.3"`, `tokio` (shared with HTTP). */
  val WsRs: String =
    """//! WebSocket server + client runtime (R.6).
      |//! Emitted verbatim by RustGen when ws intrinsics are reached.
      |
      |use std::sync::{Arc, Mutex};
      |use futures_util::{SinkExt, StreamExt};
      |use tokio::net::TcpListener;
      |use tokio_tungstenite::accept_async;
      |use tokio_tungstenite::tungstenite::Message;
      |
      |pub type WsHandler = Arc<dyn Fn(String) -> String + Send + Sync>;
      |
      |static WS_ROUTES: std::sync::OnceLock<Arc<Mutex<Vec<(String, WsHandler)>>>> =
      |    std::sync::OnceLock::new();
      |
      |fn ws_routes() -> &'static Arc<Mutex<Vec<(String, WsHandler)>>> {
      |    WS_ROUTES.get_or_init(|| Arc::new(Mutex::new(Vec::new())))
      |}
      |
      |/// `wsRoute(path, handler)` — register a WebSocket echo handler.
      |/// Handler receives each text frame and its return value is sent back.
      |#[allow(dead_code)]
      |pub fn _ws_route(
      |    path:    String,
      |    handler: impl Fn(String) -> String + Send + Sync + 'static,
      |) {
      |    ws_routes().lock().unwrap().push((path, Arc::new(handler)));
      |}
      |
      |/// `wsServe(port)` — start the WebSocket server and block until killed.
      |/// Routes registered with `wsRoute` are served on every connection.
      |#[allow(dead_code)]
      |pub fn _ws_serve(port: i64) {
      |    let rt = tokio::runtime::Runtime::new().expect("tokio runtime");
      |    rt.block_on(async move {
      |        let addr = format!("0.0.0.0:{}", port);
      |        let listener = TcpListener::bind(&addr).await
      |            .unwrap_or_else(|e| panic!("wsServe({}): {}", port, e));
      |        eprintln!("WS listening on ws://{}", listener.local_addr().unwrap());
      |        loop {
      |            let (stream, _) = listener.accept().await
      |                .unwrap_or_else(|e| panic!("ws accept: {}", e));
      |            let routes = ws_routes().clone();
      |            tokio::spawn(async move {
      |                let ws_stream = match accept_async(stream).await {
      |                    Ok(s)  => s,
      |                    Err(_) => return,
      |                };
      |                let (mut write, mut read) = ws_stream.split();
      |                while let Some(Ok(msg)) = read.next().await {
      |                    if let Message::Text(txt) = msg {
      |                        let reply = {
      |                            let guard = routes.lock().unwrap();
      |                            guard.first().map(|(_, h)| h(txt.to_string()))
      |                                 .unwrap_or_default()
      |                        };
      |                        let _ = write.send(Message::Text(reply)).await;
      |                    }
      |                }
      |            });
      |        }
      |    });
      |}
      |
      |/// `wsConnectSync(url, handler)` — synchronous WS client.
      |/// Connects to `url`, calls `handler(msg)` for each text frame,
      |/// and returns when the server closes the connection.
      |#[allow(dead_code)]
      |pub fn _ws_connect_sync(url: String, handler: impl Fn(String) + Send + 'static) {
      |    let rt = tokio::runtime::Runtime::new().expect("tokio runtime");
      |    rt.block_on(async move {
      |        let (ws_stream, _) = tokio_tungstenite::connect_async(&url).await
      |            .unwrap_or_else(|e| panic!("wsConnectSync({}): {}", url, e));
      |        let (_, mut read) = ws_stream.split();
      |        while let Some(Ok(msg)) = read.next().await {
      |            if let Message::Text(txt) = msg {
      |                handler(txt.to_string());
      |            }
      |        }
      |    });
      |}
      |""".stripMargin

  /** R.6 — MCP server over stdio (JSON-RPC 2.0, hand-rolled).
   *  `mcpRegisterTool(name, desc, handler: String->String)` registers a tool;
   *  `mcpServe()` runs the server loop (reads stdin, writes stdout, blocks).
   *  Only `serde_json` dep is required — no rmcp or extra async crate. */
  val McpRs: String =
    """//! MCP server runtime (R.6) — JSON-RPC 2.0 over stdio.
      |//! Emitted verbatim by RustGen when mcp intrinsics are reached.
      |
      |use std::sync::{Arc, Mutex};
      |use std::io::{BufRead, Write};
      |
      |pub type McpToolFn = Arc<dyn Fn(String) -> String + Send + Sync>;
      |
      |#[derive(Clone)]
      |pub struct McpTool {
      |    pub name:        String,
      |    pub description: String,
      |    pub handler:     McpToolFn,
      |}
      |
      |static MCP_TOOLS: std::sync::OnceLock<Arc<Mutex<Vec<McpTool>>>> =
      |    std::sync::OnceLock::new();
      |
      |fn mcp_tools() -> &'static Arc<Mutex<Vec<McpTool>>> {
      |    MCP_TOOLS.get_or_init(|| Arc::new(Mutex::new(Vec::new())))
      |}
      |
      |/// `mcpRegisterTool(name, description, handler)` — register an MCP tool.
      |/// Handler receives the arguments JSON object as a String and returns a String result.
      |#[allow(dead_code)]
      |pub fn _mcp_register_tool(
      |    name:        String,
      |    description: String,
      |    handler:     impl Fn(String) -> String + Send + Sync + 'static,
      |) {
      |    mcp_tools().lock().unwrap().push(McpTool {
      |        name,
      |        description,
      |        handler: Arc::new(handler),
      |    });
      |}
      |
      |/// `mcpServe()` — run the MCP server loop over stdin/stdout.
      |/// Responds to `initialize`, `tools/list`, and `tools/call`.
      |/// Blocks until stdin is closed.
      |#[allow(dead_code)]
      |pub fn _mcp_serve() {
      |    let stdin  = std::io::stdin();
      |    let stdout = std::io::stdout();
      |    let mut out = std::io::BufWriter::new(stdout.lock());
      |
      |    for line in stdin.lock().lines() {
      |        let line = match line {
      |            Ok(l) if !l.trim().is_empty() => l,
      |            _ => continue,
      |        };
      |        let req: serde_json::Value = match serde_json::from_str(&line) {
      |            Ok(v)  => v,
      |            Err(_) => continue,
      |        };
      |        let id     = req.get("id").cloned().unwrap_or(serde_json::Value::Null);
      |        let method = req["method"].as_str().unwrap_or("");
      |
      |        let result: serde_json::Value = match method {
      |            "initialize" => serde_json::json!({
      |                "protocolVersion": "2024-11-05",
      |                "capabilities":    { "tools": {} },
      |                "serverInfo":      { "name": "ssc-mcp", "version": "1.0.0" }
      |            }),
      |            "tools/list" => {
      |                let tools: Vec<serde_json::Value> = mcp_tools().lock().unwrap()
      |                    .iter().map(|t| serde_json::json!({
      |                        "name":        t.name,
      |                        "description": t.description,
      |                        "inputSchema": { "type": "object" }
      |                    }))
      |                    .collect();
      |                serde_json::json!({ "tools": tools })
      |            }
      |            "tools/call" => {
      |                let params  = &req["params"];
      |                let name    = params["name"].as_str().unwrap_or("");
      |                let args    = params.get("arguments")
      |                    .map(|a| a.to_string())
      |                    .unwrap_or_else(|| "{}".to_string());
      |                let guard   = mcp_tools().lock().unwrap();
      |                let reply   = guard.iter()
      |                    .find(|t| t.name == name)
      |                    .map(|t| (t.handler)(args.clone()))
      |                    .unwrap_or_else(|| format!("unknown tool: {}", name));
      |                serde_json::json!({
      |                    "content": [{ "type": "text", "text": reply }]
      |                })
      |            }
      |            _ => serde_json::json!({ "error": { "code": -32601, "message": "Method not found" } }),
      |        };
      |
      |        let response = serde_json::json!({
      |            "jsonrpc": "2.0",
      |            "id":      id,
      |            "result":  result
      |        });
      |        let _ = writeln!(out, "{}", response);
      |        let _ = out.flush();
      |    }
      |}
      |""".stripMargin

  /** R.4.2 — Generate `src/runtime/effects.rs` with tagless-final traits.
   *
   *  One trait per named effect (e.g. `LoggerEffect`), each with default
   *  no-op method bodies.  A `NoOpLogger` (etc.) struct is emitted as the
   *  default handler injected by `runLogger { … }`.
   *
   *  `Stream` is special: it uses a generic `VecStream<T>` collector rather
   *  than a simple no-op struct. */
  def renderTaglessEffectsRs(
      effectNames: Set[String],
      customEffectOps: Map[String, List[String]] = Map.empty
  ): String =
    val sb = new StringBuilder
    sb.append(
      """//! Tagless-final effect traits — generated by RustGen (R.4.2).
        |//! Do not edit by hand.
        |
        |""".stripMargin
    )
    for effName <- effectNames.toList.sorted do
      if customEffectOps.contains(effName) then
        // A user-declared `effect E:` — emit a trait with REQUIRED methods (no no-op
        // default, no `NoOp` struct): the `handle { … }` handler struct supplies the impl.
        val methodLines = customEffectOps(effName).map(sig => s"    $sig;\n").mkString
        sb.append(s"pub trait ${effName}Effect {\n$methodLines}\n\n")
      else if effName == "Stream" then
        sb.append(StreamEffectRs)
      else if effName == "State" then
        sb.append(StateEffectRs)
      else if effName == "Random" then
        sb.append(RandomHandlerRs)
      else
        val traitName = s"${effName}Effect"
        val noopName  = s"NoOp${effName}"
        val ops: List[String] = knownEffectOps.getOrElse(effName, Nil)
        val opLines = ops.map(op => s"    $op {}\n").mkString
        sb.append(
          s"""#[allow(unused_variables)]
             |pub trait $traitName {
             |$opLines}
             |
             |pub struct $noopName;
             |impl $traitName for $noopName {}
             |
             |""".stripMargin
        )
    sb.toString

  /** State effect — concrete `i64` state, `StateHandler` carries the mutable state cell.
   *  `runState(init) { body }` injects `StateHandler { state: init }`. */
  private val StateEffectRs: String =
    """pub trait StateEffect {
      |    fn get_state(&mut self) -> i64;
      |    fn put_state(&mut self, s: i64);
      |}
      |
      |pub struct StateHandler {
      |    pub state: i64,
      |}
      |
      |impl StateEffect for StateHandler {
      |    fn get_state(&mut self) -> i64 { self.state }
      |    fn put_state(&mut self, s: i64) { self.state = s; }
      |}
      |
      |""".stripMargin

  /** Random effect — LCG `RandomHandler` for bench-friendly deterministic values.
   *  `runRandom(seed) { body }` injects `RandomHandler { seed: seed as u64 }`. */
  private val RandomHandlerRs: String =
    """pub trait RandomEffect {
      |    fn next_int(&mut self, bound: i64) -> i64;
      |    fn next_float(&mut self) -> f64;
      |}
      |
      |pub struct RandomHandler {
      |    pub seed: u64,
      |}
      |
      |impl RandomEffect for RandomHandler {
      |    fn next_int(&mut self, bound: i64) -> i64 {
      |        self.seed = self.seed.wrapping_mul(6364136223846793005)
      |                              .wrapping_add(1442695040888963407);
      |        (self.seed % bound.max(1) as u64) as i64
      |    }
      |    fn next_float(&mut self) -> f64 {
      |        self.seed = self.seed.wrapping_mul(6364136223846793005)
      |                              .wrapping_add(1442695040888963407);
      |        (self.seed >> 11) as f64 / 9007199254740992.0
      |    }
      |}
      |
      |""".stripMargin

  /** Verbatim Stream effect block — generic `VecStream<T>` + `StreamEffect<T>` trait.
   *  `runStream { body }` injects `VecStream::new()` and collects every `stream_emit` call. */
  private val StreamEffectRs: String =
    """pub trait StreamEffect<T> {
      |    fn stream_emit(&mut self, value: T);
      |}
      |
      |pub struct VecStream<T> {
      |    pub items: Vec<T>,
      |}
      |
      |impl<T> VecStream<T> {
      |    pub fn new() -> Self { VecStream { items: Vec::new() } }
      |}
      |
      |impl<T> StreamEffect<T> for VecStream<T> {
      |    fn stream_emit(&mut self, value: T) { self.items.push(value); }
      |}
      |
      |""".stripMargin

  /** Known effect → list of method signatures (no-op default body assumed).
   *  State and Random are special-cased in renderTaglessEffectsRs (concrete handlers). */
  private val knownEffectOps: Map[String, List[String]] = Map(
    "Logger" -> List(
      "fn log_info (&mut self, _msg: &str)",
      "fn log_warn (&mut self, _msg: &str)",
      "fn log_error(&mut self, _msg: &str)",
      "fn log_debug(&mut self, _msg: &str)"
    ),
    "Clock" -> List(
      "fn now_ms(&mut self) -> i64 { 0 }"
    ),
    "Env" -> List(
      "fn get_env(&mut self, _key: &str) -> Option<String> { None }"
    )
  )
