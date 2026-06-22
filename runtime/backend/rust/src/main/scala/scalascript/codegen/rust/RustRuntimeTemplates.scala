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
    RustRuntimeResource.load("ValueRs")

  /** `src/runtime/mod.rs` — the helpers `RustIntrinsics` references by
   *  the `crate::runtime::_*` symbol names.  Keeping the names stable
   *  is part of the SPI between the emitter and the runtime. */
  val RuntimeModRs: String =
    RustRuntimeResource.load("RuntimeModRs")

  /** R.3.2 — sha256 helper, appended only when `sha256` is reached.
   *  The `sha2` crate dep is added to Cargo.toml in lockstep. */
  val Sha256Rs: String =
    RustRuntimeResource.load("Sha256Rs")

  /** R.3.2 — base64 helpers, appended only when at least one of
   *  `base64Encode` / `base64Decode` is reached. */
  val Base64Rs: String =
    RustRuntimeResource.load("Base64Rs")

  /** R.3.3 — JSON helpers, appended only when at least one of
   *  `jsonParse` / `jsonStringify` is reached.  Pulls in `serde_json`. */
  val JsonRs: String =
    RustRuntimeResource.load("JsonRs")

  /** R.6 — Auth helpers: argon2 password hashing + HS256 JWT.
   *  Emitted as `src/runtime/auth.rs` only when at least one of
   *  `hashPassword` / `verifyPassword` / `jwtSign` / `jwtVerify` is reached.
   *  Pulls in `argon2 = "0.5"`, `jsonwebtoken = "9"`,
   *  `serde = { version = "1", features = ["derive"] }`. */
  val AuthRs: String =
    RustRuntimeResource.load("AuthRs")

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
    RustRuntimeResource.load("EffectRs")

  /** std/ui — server-side `View` tree + HTML render (SSR).  Emitted as
   *  `src/runtime/ui.rs` only when `element`/`textNode`/`fragment` are
   *  reached.  `_ui_render` walks the tree, escaping text + attribute
   *  values.  This is the HTML/SSR binding of the std/ui primitives
   *  (spec: rust-web-toolkit.md, increment S1). */
  val UiRs: String =
    RustRuntimeResource.load("UiRs")

  /** R.5 — HTTP server runtime helpers.  Emitted as `src/runtime/http.rs`
   *  only when `serve` / `route` are reached in the program source.
   *  Handler takes `String` (matches SS `String => String` surface). */
  val HttpRs: String =
    RustRuntimeResource.load("HttpRs")

  /** std/ui `serve(view, port)` — SSR overload.  Appended to `http.rs` ONLY when
   *  the program also uses the View primitives (uiUsage), since it references
   *  `crate::runtime::ui`.  A pure `route`/`serve(port)` program omits it. */
  val UiServeRs: String =
    RustRuntimeResource.load("UiServeRs")

  /** R.6 — WebSocket server + client helpers.
   *  `wsRoute(path, handler)` registers a string-echo handler; `wsServe(port)`
   *  starts the server; `wsConnectSync(url, handler)` is a blocking client.
   *  Emitted only when any ws intrinsic is reached.
   *  Deps: `tokio-tungstenite = "0.21"`, `futures-util = "0.3"`, `tokio` (shared with HTTP). */
  val WsRs: String =
    RustRuntimeResource.load("WsRs")

  /** R.6 — MCP server over stdio (JSON-RPC 2.0, hand-rolled).
   *  `mcpRegisterTool(name, desc, handler: String->String)` registers a tool;
   *  `mcpServe()` runs the server loop (reads stdin, writes stdout, blocks).
   *  Only `serde_json` dep is required — no rmcp or extra async crate. */
  val McpRs: String =
    RustRuntimeResource.load("McpRs")

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
    RustRuntimeResource.load("StateEffectRs")

  /** Random effect — LCG `RandomHandler` for bench-friendly deterministic values.
   *  `runRandom(seed) { body }` injects `RandomHandler { seed: seed as u64 }`. */
  private val RandomHandlerRs: String =
    RustRuntimeResource.load("RandomHandlerRs")

  /** Verbatim Stream effect block — generic `VecStream<T>` + `StreamEffect<T>` trait.
   *  `runStream { body }` injects `VecStream::new()` and collects every `stream_emit` call. */
  private val StreamEffectRs: String =
    RustRuntimeResource.load("StreamEffectRs")

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
