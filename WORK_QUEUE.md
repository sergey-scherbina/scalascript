# Work Queue

Agents: claim the top available task using the protocol in `AGENTS.md §"Task claiming protocol"`.
Do tasks top-to-bottom within each section. A task is "available" if its slug has no
corresponding file in `.work/active/`.

**Loop control** — to pause the autonomous loop between tasks, push `.work/paused` to
`origin/main` (see `AGENTS.md §"Stopping the loop"`). To resume, remove it and push.
To start: tell the agent "работай" / "go" / "start".

---

## Pending

- [ ] **v1.46-phase5-derivation** — v1.46 Phase 5: route derivation from `route()`/`mount()` handlers  
  _Spec: `docs/typed-route-clients.md` §Phase 5. Auto-generate `apiClients:` metadata from existing typed route declarations so users don't need to write front-matter manually._

- [ ] **v1.46-phase6-streaming** — v1.46 Phase 6: streaming / SSE response support  
  _Spec: `docs/typed-route-clients.md` §Phase 6. Server-Sent Events and chunked streaming response types in typed route clients._

- [ ] **v1.46-phase6-pagination** — v1.46 Phase 6: pagination helpers  
  _Spec: `docs/typed-route-clients.md` §Phase 6. Generated `listPaged(page, size)` helpers for endpoints that follow a page/limit pattern._

---

## Done (this sprint)

- [x] **v1.46-phase1-metadata** — Phase 1: `apiClients:` front-matter parse → `ApiClientDecl` AST metadata
- [x] **v1.46-phase2-swing-client** — Phase 2: JVM/Swing in-process callable client objects
- [x] **v1.46-phase3-http-client** — Phase 3: JS HTTP client + async `awaitClient` integration
- [x] **v1.46-phase4-shared-codecs** — Phase 4: shared `_ssc_typed_json_encode/decode` codec facade
- [x] **v1.46-phase5-validation** — Phase 5 partial: static path-param validation warnings
- [x] **v1.46-phase6-auth** — Phase 6: auth/custom header injection (`_ssc_api_set_headers`, `_ssc_set_auth_token`)
- [x] **v1.46-phase6-per-call-headers** — Phase 6: per-call header overrides (optional `headers` param on every method)
- [x] **v1.46-phase6-retry** — Phase 6: retry policy (`_ssc_api_set_retry(maxRetries, delayMs)`)
- [x] **v1.46-phase6-cancel** — Phase 6: cancellation tokens (`_ssc_api_cancel_token()`, `token.cancel()`)

---

> When you finish a task: remove `.work/active/<slug>.claim`, mark the task `[x]` here,
> both in the same final push. See `AGENTS.md §"Task claiming protocol"`.
