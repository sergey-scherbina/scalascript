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
  def renderTaglessEffectsRs(effectNames: Set[String]): String =
    val sb = new StringBuilder
    sb.append(
      """//! Tagless-final effect traits — generated by RustGen (R.4.2).
        |//! Do not edit by hand.
        |
        |""".stripMargin
    )
    for effName <- effectNames.toList.sorted do
      if effName == "Stream" then
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
