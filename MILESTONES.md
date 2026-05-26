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
- **Queue empty** — all current tasks complete. Add new tasks via `BACKLOG.md` to continue.

### Next up (top of BACKLOG)
- **v1.12** — Algebraic effects feasibility study (BLOCKED on Scala 3.3.7 / Scalus constraint)

### Recently completed
- v1.48 (SwiftUI Phase 3) — Reactive list lowering + `@Observable` AppModel ✓ (2026-05-26)
- v1.46 — Typed Route Clients (all phases, including pagination) ✓ (2026-05-26)
- v1.48 — JavaFX Typed Route Clients ✓ (2026-05-26)
- v1.47 — JavaFX Desktop Frontend ✓ (2026-05-26)
- v1.45 — JVM Desktop Frontend ✓ (2026-05-26)
- v1.44 — Full-Stack In-Process Transport ✓ (2026-05-26)
- v1.43 — Electron JVM REST Backend ✓ (2026-05-26)
- v1.42 — Native Platform P3: Electron Renderer ✓ (2026-05-23)

See [CHANGELOG.md](CHANGELOG.md) for the full list.

---

## Parallel directions (all independent)

| Direction | Top task | Spec |
|-----------|----------|------|
| **Frontend & Clients** | _(queue empty)_ | — |
| **Language & Compiler** | v2.0-sep-compile | `BACKLOG.md # Language & Compiler` |
| **Database** | _(queue empty)_ | — |
| **Payments & Blockchain** | _(queue empty)_ | — |
| **Native Platform** | _(queue empty)_ | — |

Multiple agents can work in parallel — one per direction. Tell each: `"работай над <Direction>"`.

## For agents

- **Pick next task**: read [WORK_QUEUE.md](WORK_QUEUE.md) by direction; claim via `AGENTS.md §"Task claiming protocol"`.
- **Mark landed**: update `BACKLOG.md` (remove entry from direction section) + add one-liner to `CHANGELOG.md`.
- **Start new milestone**: add it to the right direction section in `BACKLOG.md`.
