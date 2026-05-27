# Milestones

> This file is a navigation index. Full content lives in three files:

| File | Contents |
|------|----------|
| [BACKLOG.md](BACKLOG.md) | Open and planned milestones — what still needs to be done |
| [ACTIVE.md](ACTIVE.md) | Milestones currently in progress (synced with `WORK_QUEUE.md`) |
| [CHANGELOG.md](CHANGELOG.md) | Completed milestones, newest first |

---

## Quick status (2026-05-27)

### In progress
_(none)_

### Next up (top of BACKLOG)
- **v2.1.1-dstream-native-bounded** — Core `DStream[T]` / `Pipeline` types + native bounded backend + `DirectRunner` (`BACKLOG.md §v2.1`)
- **v1.51.1** — Streams plugin scaffolding + `Source` core, interpreter + JVM (`BACKLOG.md §v1.51`)

### Recently completed
- v2.1.0 — Distributed Streams spec (`docs/distributed-streams.md`) — DStream[T], full Beam model, 5 backends, capability system ✓ (2026-05-27)
- v1.52 — Deploy to Hostings/Clouds/K8s spec (`docs/deploy.md`) + go decision ✓ (2026-05-27)
- v1.51 — Streams with Backpressure spec (`docs/streams.md`) + go decision ✓ (2026-05-27)
- v1.50 — GraalVM native-image build + `ssc-plugin-host` bridge + native plugin guide ✓ (2026-05-27)
- v1.12.3 — Effects stdlib: `NonDet`, `Reader`, typed discharge signatures, `examples/algebraic-effects.ssc` ✓ (2026-05-26)
- v1.12.2 — One-shot effect runtime fast path + JS `function*` + dynamic violation check ✓ (2026-05-26)
- v1.12.1 — Typed Algebraic Effects type system + parser + diagnostics ✓ (2026-05-26)
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
| **Language & Compiler** | v2.1.1-dstream-native-bounded + v1.51.1-streams-plugin | `docs/distributed-streams.md`, `docs/algebraic-effects.md`, `docs/streams.md` |
| **Database** | _(queue empty)_ | — |
| **Payments & Blockchain** | _(queue empty)_ | — |
| **Native Platform** | _(queue empty)_ | — |
| **Distribution & Tooling** | v1.52.1-deploy-plugin | `docs/deploy.md` |

Multiple agents can work in parallel — one per direction. Tell each: `"работай над <Direction>"`.

## For agents

- **Pick next task**: read [WORK_QUEUE.md](WORK_QUEUE.md) by direction; claim via `AGENTS.md §"Task claiming protocol"`.
- **Mark landed**: update `BACKLOG.md` (remove entry from direction section) + add one-liner to `CHANGELOG.md`.
- **Start new milestone**: add it to the right direction section in `BACKLOG.md`.
