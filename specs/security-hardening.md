# Security hardening — toolchain audit (2026-07-11)

Defensive security audit of the runtime/toolchain surfaces where **untrusted
input meets the runtime**: filesystem, process/`exec`, outbound HTTP client,
HTTP server + JSON rendering, and code-generation / artifact cache — across the
JVM, JS, Rust, and interpreter backends.

Report (rendered): security-audit artifact
`https://claude.ai/code/artifact/e069a55c-a49f-4aac-bc68-4077e4d88d1b`

## Threat model

- **HTTP server**: untrusted requests hit `route`/`serve` handlers; untrusted
  data is serialized into JSON responses and sometimes embedded in HTML.
- **HTTP client**: the URL and/or headers may be influenced by untrusted input
  (→ SSRF); the response comes from a possibly-malicious server (→ TLS
  downgrade, redirect/header abuse, huge/gzip-bombed bodies).
- **fs / process**: a `.ssc` program may pass untrusted request params / file
  contents into `exec` / `readFile` / `writeFile` / path helpers.
- **codegen / cache**: `.ssc` may come from a semi-trusted source (shared
  registry, submitted snippet, LLM output); the artifact cache is trusted as
  executable code.

## Structural defenses verified sound (do not regress)

- **No command injection** — every `exec` is argv-form (`ProcessBuilder(list*)`
  / `Command::new().args()` / `spawnSync(shell:false)`); no shell anywhere.
- **TLS verification on everywhere** — rustls / JDK default / fetch; no
  `danger_*`, no `rejectUnauthorized:false`, no `trustAll`.
- **No codegen code-injection** — every backend escapes `\` and `"` in
  `Lit.String` emission; the `httpClient(base){block}` closure emit and the
  `HttpClientRs` runtime are clean.
- **No deserialization-gadget RCE** — artifacts are upickle plain-data case
  classes; no `ObjectInputStream` in the tree.
- **No artifact path traversal** — artifact path derives from the filename
  component only, never from attacker front-matter.
- **Fast/native HTTP engine** rejects `Transfer-Encoding`+`Content-Length`
  (smuggling) and enforces 64 KB header / 16 MB body caps mid-read.
- **Self-hosted JSON renderer** (`json-core.ssc`) escapes `"`, `\`, all control
  chars, and every non-ASCII as `\uXXXX`.
- **CORS** reflects `Origin` only when allow-listed; no `*`+credentials.

## Findings

Severity, id, one-line, primary location. Full exploit/fix in the artifact.
"✎ session" = lives in code shipped in this session (Rust HTTP client, JVM
`processRuntime`, `--native` JSON fallback, cache stamp).

| Sev | ID | Finding | Location |
|-----|----|---------|----------|
| High | H1 | XSS: `JSON.stringify` state inlined into an inline `<script>` (SSR hydration) | `js-runtime/signals.mjs:1329` |
| High | H2 | SSRF: raw URL reaches the HTTP stack with no validation (all backends) | `HttpClientRs:42` · `OutboundClients.scala:25` · `HttpIntrinsics.scala:614` · `ws-server.mjs:526` |
| High | H3 ✎ | `httpClient(base){}` scope bypass → confused-deputy SSRF (`base+raw` concat; `@`/`http` escape) | `HttpClientRs:42` (+ mirrors) |
| High | H4 ✎ | Artifact cache trusts attacker-writable `.scjvm`/`.scjs` behind a forgeable freshness gate | `ModuleGraph.scala:280` · `JvmArtifactIO.scala:40` |
| High | H5 | JVM outbound HTTP config in process-global `var`s → cross-request state bleed | `OutboundClients.scala:13` |
| High | H6 | Rust `deleteFile` recursively wipes directories (`remove_dir_all`) | `RuntimeModRs:145` |
| Med | M1 | Unbounded request-body buffering on legacy JDK serve (chunked bypass; default `Long.MaxValue`) | `RequestBuilder.scala:55` · `WebServer.scala:47` |
| Med | M2 | Unbounded response body → OOM (JVM/interp/JS; Rust bounded 10 MB) | `OutboundClients.scala:37` · `HttpIntrinsics.scala:631` · `ws-server.mjs:545` |
| Med | M3 ✎ | Rust/JS follow redirects → SSRF amplifier + auth-header forward (JVM/interp don't) | `HttpClientRs:65` · `ws-server.mjs:543` |
| Med | M4 ✎ | `exec` ignores `opts.timeout` → indefinite hang / DoS (all backends) | `processRuntime:22` · `RuntimeModRs:251` · `JsRuntimeFs.scala:174` |
| Med | M5 ✎ | JVM `exec` deadlocks when the child fills the stderr pipe | `processRuntime:20` · `OsIntrinsics.scala:140` |
| Med | M6 | JS `exec` reports exitCode 0 for killed/failed commands → security-gate bypass | `JsRuntimeFs.scala:180` |
| Med | M7 | Insecure temp files (predictable name, no O_EXCL) on Rust/JS | `RuntimeModRs:217` · `JsRuntimeFs.scala:148` |
| Med | M8 ✎ | Native `jsonQuote` leaks U+2028/2029 + `<` (weaker than self-hosted) | `NativeJsonCodec.scala:60` |
| Med | M9 ✎ | Timeout does not bound the whole request (Rust client; JVM stream) | `HttpClientRs:65` · `OutboundClients.scala:82` |
| Med | M10 | No path confinement on fs helpers (arbitrary read/write + symlink follow) | `fsRuntime` · `RuntimeModRs:41` · `JsRuntimeFs` · `BuiltinsRuntime:859` |
| Med | M11 | Static-file path traversal via prefix match (missing separator) | `StaticAssetServer.scala:23` |
| Low | L1 | Retry amplification — no backoff/jitter/cap on `httpRetry(n)` | client, all backends |
| Low | L2 ✎ | Header names/values not sanitized by the runtime (Rust; JVM/JS safe via lib) | `HttpClientRs:72` |
| Low | L3 | Child processes inherit the full parent env (secret leakage; no scrub option) | all exec impls |
| Low | L4 | TOCTOU in `mkdir` (check-exists-then-create) | `fsRuntime:35` · `RuntimeModRs:123` |
| Low | L5 | JS interpolator / JVM config string escapers omit newline → generated-program DoS | `JsGen.scala:3860` · `JvmGenStringUtils.scala:6` |
| Low | L6 | `OpenApiGenerator.jsonEscape` escapes only `"`/`\` (invalid JSON on ctrl chars) | `OpenApiGenerator.scala:483` |
| Info | L7 | No backend escapes U+2028/2029 in emitted literals (pre-ES2019 JS DoS) | codegen escapers |
| Info | L8 | Cross-backend semantic divergence is itself a hazard | fs/process/http |

## Fix plan

Priority order — root-cause fixes first, then blast-radius reducers.

1. **URL handling (H2 + H3)** — the single highest-leverage fix. Replace the
   shared `base + raw` string-concat join with a real URL resolver, and reject
   authority-bearing `raw` when a base is scoped. Add an **opt-in** SSRF guard
   (reject loopback / link-local / RFC-1918 unless allowed). H3 (join) is a
   pure bug-fix and lands first; the SSRF allow-list (H2) is opt-in policy.
2. **JSON-in-HTML (H1 + M8)** — HTML-safe serialize at the `<script>` embed
   site (`signals.mjs`); bring the `--native` `jsonQuote` to parity with the
   self-hosted renderer (escape all non-ASCII / U+2028/2029).
3. **Unbounded buffering (M1 + M2)** — default body caps + streaming reads with
   a running byte counter, both request (server) and response (client).
4. **`exec` correctness (M4 + M5 + M6)** — enforce `opts.timeout`, drain stderr
   concurrently, stop masking non-zero exit on JS.
5. **Rust fs (H6)** — `deleteFile` = single-file only, matching every other
   backend.
6. **Redirect policy (M3)** — unify: don't follow redirects by default (match
   JVM/interp), or same-host-only + strip `Authorization` cross-host.
7. **Cache integrity (H4)** — HMAC/sign artifacts with an install-private key
   (not the jar mtime); treat group/other-writable artifact dirs as stale.
8. **Hardening tail (M7, M10, M11, L1–L6)** — secure temp files, confined fs
   variants, path-aware static-file check, retry backoff/cap, header CRLF
   reject, full string escapers, env-scrub option.

Cross-cutting: a shared conformance suite pinning identical, documented
semantics per fs/process/http primitive across backends (closes L8, prevents
regressions of H6/M3).

## Landed this session

10 fixes, all in code shipped this session or one-line cross-backend:

- **rust `7d1d854d4`** — H3 (join can't inject userinfo), M3 (`.redirects(0)`),
  M9 (`.timeout(timeout)`), L2 (header CRLF skip), H6 (`deleteFile` = single file).
- **jvm `1caace5f3`** — M4 + M5: `exec` drains stdout AND stderr on daemon
  threads (inline stdout drain both deadlocked on >64 KB stderr and defeated the
  timeout) + `waitFor(opts.timeout)` + `destroyForcibly`. Verified scala-cli:
  200 KB stderr → 14 ms no deadlock; `sleep 5` @ 300 ms timeout → killed, code -1.
- **json `c7f116e45`** — M8: native `jsonQuote` escapes all `c<0x20 || c>0x7e`
  as `\uXXXX` (parity with the self-hosted renderer; covers U+2028/2029).
- **js+server `473bf2d71`** — M6 (JS `exec` exitCode no longer masks
  signal-kill/ENOENT as 0), M11 (static-file containment via `Path.startsWith`).

Second batch:
- **js `fc8cbce00`** — H1: `_ssc_json_html_safe` escapes `<>&`/U+2028/2029 to `\uXXXX`
  before inlining state JSON into the SSR `<script>` (both renderPage + serve).
- **http-client `ef7fd23e7`** — H3 join (blocks `@`-userinfo host re-point) + M9
  stream timeout mirrored to OutboundClients (JVM) / HttpIntrinsics (interp) /
  ws-server (JS); H5 JVM outbound config → ThreadLocal. Verified: scala-cli join
  keeps host=api.internal for a `@169.…` path.

Third batch:
- **fs `a2b11223b`** — M7 (O_EXCL temp files, Rust+JS) + L4 (idempotent mkdir).
- **rust `921a5da7c`** — bonus: BorrowedArgIntrinsics fix; the `&str` fs/path
  intrinsic family (mkdir/tempFile/copyFile/moveFile/path.*) was E0308-broken on
  Rust codegen. Verified: emit-rust + cargo run of a tempFile+mkdir program.
- **codegen `46e2aa06c`** — L5 (interpolator/config escapers add `\n\r\t`) + L6
  (openapi jsonEscape → jsonStr).

Remaining (`SPRINT.md` Batches C): H2, H4, M1, M2, M10, L1, L3, L8, and the JS
redirect policy (M3-JS). H2 (SSRF allow-list) and H4 (artifact signing) need a
design decision (config surface / key management) before implementation.
