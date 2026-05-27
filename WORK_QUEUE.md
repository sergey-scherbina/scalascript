# Work Queue

Agents: claim the top available task using the protocol in `AGENTS.md §"Task claiming protocol"`.
Work top-to-bottom within each group. A task is "available" if its slug has no file in `.work/active/`.

**Loop control** — pause: push `.work/paused` to `origin/main`. Resume: remove it and push.
Start: tell the agent `"работай"` / `"go"`. Status: ask `"статус"` / `"status"`.

---

## Frontend & Clients

_(all done — see Done section below)_

## Payments & Blockchain

_(all done — see Done section below)_

## Database

_(all done — see Done section below)_

## Native Platform

- [x] **v1.48.2-swiftui-ios-run** — `ssc run --target ios` (iOS Simulator) (2026-05-26)

- [x] **v1.48.3-swiftui-device-run** — `ssc run --target ios --device` (real device via ios-deploy) (2026-05-26)

- [x] **v1.48.4-ios-package** — `ssc package --target ios` → signed .ipa (2026-05-26)

- [x] **v1.48.5-ios-publish** — `ssc publish --target ios` (TestFlight + App Store via fastlane) (2026-05-26)

- [x] **v1.49-macos-distribution** — `ssc package/publish --target macos` (notarize + DMG + Mac App Store) (2026-05-26)

## Distribution & Tooling

- [x] **v1.50-native-p1-snakeyaml** — Replace snakeyaml with pure-Scala frontmatter parser (2026-05-27)

- [x] **v1.50-native-p2-graalvm** — GraalVM native-image build for `ssc` (2026-05-27)

- [x] **v1.50-native-p3-plugin-bridge** — `ssc-plugin-host.jar` + automatic bridge (existing plugins unchanged) (2026-05-27)
  _New `ssc-plugin-host` sbt subproject: `SubprocessHost` main loads any existing plugin JAR via URLClassLoader + ServiceLoader + wire protocol. Native `ssc` auto-spawns it when `--plugin foo.jar` given. Plugin authors change nothing. Spec: `BACKLOG.md §Phase 3`._

- [ ] **v1.50-native-p4-plugin-guide** — Plugin-author guide: compile your plugin to a native binary via GraalVM native-image (docs only, no core changes)
  _Spec: `BACKLOG.md §Phase 4`. Deliverable: `docs/native-plugin-guide.md` + example plugin config snippet. Unlocks fully JVM-free `ssc (native) → wire protocol → plugin (native)` deployments._

- [x] **ws-load-10k** — Smoke test: 10 000 concurrent WebSocket connections without OOM (2026-05-27)

- [ ] **watch-100ms** — Watch cycle optimization: `ssc --watch rest-api.ssc` target < 100 ms per cycle
  _Spec: `BACKLOG.md §Compiler — AST cache`. Core checkpoint+incremental already landed. Need benchmark harness + profile-driven fixes to hit target._

- [ ] **sbt-interop-plugin** — Build-tool integration: `sbt-scalascript-interop` plugin + Mill module trait + scala-cli directive
  _Spec: `BACKLOG.md §Tier 3 — build-tool integration`. Deliberate separate repo (`scalascript-sbt-plugin`). ~15 fixture tests._

## Language & Compiler

- [x] **v1.12.1-effects-types** — Add `EffectRow` to `SType`, Rémy-style row unification, `!` operator in `TypeParser`, `multi effect` keyword, handler discharge in typer, `EffectAnalysis` verifier mode, §9 diagnostics. (2026-05-26)
  _Spec: `docs/algebraic-effects.md` §3, §4, §5.1, §13 v1.12.1._

- [x] **v1.12.2-effects-runtime** — JS `function*`/`yield` fast path for one-shot effects; coroutine VT wiring on JVM/interpreter; dynamic one-shot-violation check; cross-backend parity tests.
  _Spec: `docs/algebraic-effects.md` §5.3, §13 v1.12.2._

- [x] **v1.12.3-effects-stdlib** — Re-type `runLogger`/`runRandomSeeded`/etc. with discharge signatures; add `Reader[R]` capability; add `NonDet` multi-shot exemplar; `examples/algebraic-effects.ssc`; promote `EffectAnalysis` warnings to errors. (2026-05-26)
  _Spec: `docs/algebraic-effects.md` §6, §8.2, §13 v1.12.3._

---

## Done

- [x] **v1.12-spec** — Typed Algebraic Effects spec (`docs/algebraic-effects.md`) — design doc + go/no-go decision (2026-05-26)
- [x] **v1.46-phase1-metadata** — `apiClients:` front-matter → `ApiClientDecl` AST
- [x] **v1.46-phase2-swing-client** — JVM/Swing in-process callable clients
- [x] **v1.46-phase3-http-client** — JS HTTP client + `awaitClient` async
- [x] **v1.46-phase4-shared-codecs** — shared `_ssc_typed_json_encode/decode` facade
- [x] **v1.46-phase5-validation** — static path-param validation warnings
- [x] **v1.46-phase6-auth** — auth/custom header injection
- [x] **v1.46-phase6-per-call-headers** — per-call header overrides
- [x] **v1.46-phase6-retry** — retry policy (`_ssc_api_set_retry`)
- [x] **v1.46-phase6-cancel** — cancellation tokens
- [x] **v1.46-phase7-sse** — SSE streaming (EventSource/fetch JS; HttpURLConnection JVM)
- [x] **v1.46-ws-subscriptions** — WebSocket subscriptions (native WebSocket JS; java.net.http.HttpClient JVM; `_SscWsHandle` with `send()`+`close()`)
- [x] **v1.46-phase5-derivation** — Route derivation: `RouteDeriver` auto-generates `ApiClientDecl` from `route()` calls (2026-05-26)
- [x] **v1.46-pagination** — Pagination helpers: `paginated: true` → `<name>Paged(page, size, ...)` appending `?page=N&size=M` on JVM + JS (2026-05-26)
- [x] **v1.38-payment-request** — Payment Request API (browser + server) — Complete (2026-05-26)
- [x] **x402-http-payment** — x402 HTTP payment protocol — All phases landed (2026-05-19/20)
- [x] **blockchain-spi** — Blockchain SPI — All phases landed (2026-05-19/20)
- [x] **wallet-key-mgmt** — Wallet key management + dApp connectivity — Landed (2026-05-20)
- [x] **mcp-x402-wallet** — MCP × x402 × Wallet agentic payments — All 7 phases landed (2026-05-19)
- [x] **micropayment-platform** — Micropayment platform — All phases landed (2026-05-19/20)
- [x] **v1.26-sql-jdbc** — `sql` fenced code blocks (JDBC) — v1.26 + v1.26.1 + v1.26.2 landed (2026-05-21)
- [x] **v1.27-browser-sql** — Browser-side SQL (sql.js / DuckDB-Wasm) — v1.27 landed (2026-05-21)
- [x] **v1.30-side-sql** — `@side=client|server` for SQL blocks — v1.30 complete (2026-05-21)
- [x] **v1.31-transaction** — `transaction` fenced block — v1.31 landed (2026-05-21)
- [x] **v1.48-swiftui** — SwiftUI Native Frontend (iOS + macOS) — Phases 1–3 all landed (2026-05-26)
- [x] **v1.48.1-swiftui-run** — `ssc run --target macos` + swift build in package; target renamed desktop-macos → macos (2026-05-26)
- [x] **v1.30-repl-web-mode** — REPL web-aware mode + `mount()` intrinsic — All 8 phases landed: Routes refactor, `mount()` intrinsic, `:serve`/`:stop`/`:clear`, `:mount`, `:load`/`:reload`/`:unmount`, `:routes`/`:http`/`:call`, typed handlers, `:help`/`:set` (2026-05-26)
- [x] **v2.0-sep-compile** — Separate compilation — ALL-DELIVERABLES-LANDED (2026-05-20)
- [x] **interpreter-ergonomics** — Interpreter ergonomics — All 3 items landed (v1.13 + 2026-05-19)
- [x] **wasm-backend-phase1** — WASM backend: scalascript/ssc block support (Phase 1), integration tests + example (Phase 2), `//> using dep` hoisting + HTTP Fetch example (Phase 3) — All 3 phases landed (2026-05-26)

---

> Finish a task: remove `.work/active/<slug>.claim`, mark `[x]` here — same push.
> See `AGENTS.md §"Task claiming protocol"`.
