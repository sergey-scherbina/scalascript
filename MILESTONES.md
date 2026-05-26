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
- **v1.12.1** — Typed Algebraic Effects: type system + parser (Language & Compiler queue)

### Next up (top of BACKLOG)
- **v1.12.1** — `EffectRow` in `SType`, row unification, `!` operator, `multi effect`, handler discharge (`BACKLOG.md §v1.12`)

### Recently completed
- v1.12 — Typed Algebraic Effects spec (`docs/algebraic-effects.md`) + go decision ✓ (2026-05-26)
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
| **Language & Compiler** | v1.12.1-effects-types | `docs/algebraic-effects.md` |
| **Database** | _(queue empty)_ | — |
| **Payments & Blockchain** | _(queue empty)_ | — |
| **Native Platform** | _(queue empty)_ | — |

Multiple agents can work in parallel — one per direction. Tell each: `"работай над <Direction>"`.

## For agents

- **Pick next task**: read [WORK_QUEUE.md](WORK_QUEUE.md) by direction; claim via `AGENTS.md §"Task claiming protocol"`.
- **Mark landed**: update `BACKLOG.md` (remove entry from direction section) + add one-liner to `CHANGELOG.md`.
- **Start new milestone**: add it to the right direction section in `BACKLOG.md`.
