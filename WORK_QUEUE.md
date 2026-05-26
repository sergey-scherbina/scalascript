# Work Queue

Agents: claim the top available task using the protocol in `AGENTS.md §"Task claiming protocol"`.
Work top-to-bottom within each group. A task is "available" if its slug has no file in `.work/active/`.

**Loop control** — pause: push `.work/paused` to `origin/main`. Resume: remove it and push.
Start: tell the agent `"работай"` / `"go"`. Status: ask `"статус"` / `"status"`.

---

## Typed Route Clients (v1.46)

- [ ] **v1.46-phase5-derivation** — Route derivation from `route()`/`mount()` handlers  
  _Auto-generate `apiClients:` metadata from typed route declarations. Cross-file type analysis. Spec: `docs/typed-route-clients.md` §Phase 5._


## Payments & Blockchain

- [ ] **v1.38-payment-request** — Payment Request API (browser + server)  
  _Browser Payment Request API integration + server-side payment session handling. Spec: `BACKLOG.md §v1.38`._

- [ ] **x402-http-payment** — x402 HTTP payment protocol  
  _402-gated routes, x402 payment channel, token verification. Spec: `BACKLOG.md §x402`._

- [ ] **blockchain-spi** — Blockchain SPI  
  _Chain abstraction layer for x402 + wallet. Spec: `BACKLOG.md §Blockchain SPI`._

- [ ] **wallet-key-mgmt** — Wallet key management + dApp connectivity  
  _Key storage, signing, WalletConnect bridge. Spec: `BACKLOG.md §Wallet SPI — key management`._

- [ ] **mcp-x402-wallet** — MCP × x402 × Wallet agentic payments  
  _Agent-initiated micropayments via MCP tool calls. Spec: `BACKLOG.md §MCP × x402 × Wallet`._

- [ ] **micropayment-platform** — Micropayment platform (channel-based)  
  _Fee amortisation for microtransactions. Spec: `BACKLOG.md §Micropayment Platform`._

## Database

- [ ] **v1.26-sql-jdbc** — `sql` fenced code blocks (JDBC)  
  _`sql { SELECT ... }` blocks compiled to JDBC calls. Full spec: `BACKLOG.md §v1.26`._

- [ ] **v1.27-browser-sql** — Browser-side SQL (sql.js / DuckDB-Wasm)  
  _Same `sql { }` syntax in JS target via sql.js or DuckDB-Wasm. Spec: `BACKLOG.md §v1.27`._

- [ ] **v1.30-side-sql** — `@side=client|server` for SQL blocks in full-stack modules  
  _Annotate which SQL blocks run client-side vs server-side. Spec: `BACKLOG.md §v1.30 @side`._

- [ ] **v1.31-transaction** — `transaction` fenced block  
  _Database transaction scope as a language construct. Spec: `BACKLOG.md §v1.31`._

## Native Platform

- [ ] **v1.48-swiftui** — SwiftUI Native Frontend (iOS + macOS)  
  _SwiftUI renderer backend; `ssc run --frontend swiftui`. Spec: `BACKLOG.md §v1.48 SwiftUI`._

## Compiler & Runtime

- [ ] **v2.0-sep-compile** — Separate compilation (full pipeline)  
  _Per-module IR artifacts, interface files, linker pass. MVP landed; full pipeline remains. Spec: `BACKLOG.md §v2.0`._

- [ ] **interpreter-ergonomics** — Interpreter ergonomics (v1.1 carryover)  
  _Better error messages, REPL completion, source maps. Spec: `BACKLOG.md §Interpreter ergonomics`._

---

## Done

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
- [x] **v1.46-pagination** — Pagination helpers (`paginated: true` → `<name>Paged(page, size, ...)` for JVM/Swing and JS; appends `?page=N&size=M` to URL)

---

> Finish a task: remove `.work/active/<slug>.claim`, mark `[x]` here — same push.
> See `AGENTS.md §"Task claiming protocol"`.
