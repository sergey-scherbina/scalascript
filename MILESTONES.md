# Milestones

> This file is a navigation index. Full content lives in three files:

| File | Contents |
|------|----------|
| [BACKLOG.md](BACKLOG.md) | Open and planned milestones — what still needs to be done |
| [ACTIVE.md](ACTIVE.md) | Milestones currently in progress (synced with `WORK_QUEUE.md`) |
| [CHANGELOG.md](CHANGELOG.md) | Completed milestones, newest first |

---

## Quick status (2026-05-26)

### In progress
- **v1.46** — Typed Route Clients: Phases 0–7 complete. Remaining: Phase 5 cross-file route derivation, WebSocket subscriptions, pagination. See [ACTIVE.md](ACTIVE.md).

### Next up (top of BACKLOG)
- **v1.46 remaining** — route derivation + WS subscriptions + pagination (see [WORK_QUEUE.md](WORK_QUEUE.md))
- **v1.48** — SwiftUI Native Frontend (iOS + macOS)
- **v1.38** — Payment Request API (browser + server)
- **v1.26** — `sql` fenced code blocks (JDBC)
- **v1.27** — Browser-side SQL (sql.js / DuckDB-Wasm)
- **v2.0** — Separate compilation (full pipeline; MVP landed)
- **x402** — HTTP payment protocol
- **v1.12** — Algebraic effects feasibility study (BLOCKED on Scala 3.3.7 / Scalus constraint)

### Recently completed
- v1.47 — JavaFX Desktop Frontend ✓ (2026-05-26)
- v1.48 — JavaFX Typed Route Clients ✓ (2026-05-26)
- v1.45 — JVM Desktop Frontend ✓ (2026-05-26)
- v1.44 — Full-Stack In-Process Transport ✓ (2026-05-26)
- v1.43 — Electron JVM REST Backend ✓ (2026-05-26)
- v1.42 — Native Platform P3: Electron Renderer ✓ (2026-05-23)

See [CHANGELOG.md](CHANGELOG.md) for the full list.

---

## For agents

- **Pick next task**: read [BACKLOG.md](BACKLOG.md) and [WORK_QUEUE.md](WORK_QUEUE.md); claim via the protocol in `AGENTS.md §"Task claiming protocol"`.
- **Mark landed**: update the entry in whichever file it lives in (BACKLOG → remove it; CHANGELOG → add it).
- **Start new milestone**: add it to BACKLOG.md with the spec structure from `AGENTS.md §"Spec-driven development"`.
