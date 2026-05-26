# Active — Currently In Progress

Tasks currently being worked. Claiming protocol is in `AGENTS.md §"Task claiming protocol"`.
Pending tasks to pick up are in `WORK_QUEUE.md`.

---

## v1.46 — Typed Route Clients (sprint in progress)

**Status:** Phases 0–7 complete. Three tasks remain open in `WORK_QUEUE.md`:

| Task slug | Description |
|-----------|-------------|
| `v1.46-phase5-derivation` | Route derivation from `route()`/`mount()` handlers; cross-file type analysis so users don't need manual `apiClients:` front-matter |
| `v1.46-ws-subscriptions` | WebSocket subscription support (SSE landed as Phase 7; WS bidirectional channels remain) |
| `v1.46-phase6-pagination` | `listPaged(page, size)` helpers for page/limit endpoints |

Spec: [`docs/typed-route-clients.md`](docs/typed-route-clients.md)

**To claim a task**, follow the protocol in `AGENTS.md §"Task claiming protocol"` and pick from `WORK_QUEUE.md`.
Check `.work/active/` for already-claimed slugs before starting.
