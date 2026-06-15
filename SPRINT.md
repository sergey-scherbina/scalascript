# Sprint

Agent task queue — **active pending tasks only**. A task is "available" only if
`.work/active/<slug>.claim` does not exist on `origin/main`. Completed work lives in
`CHANGELOG.md`; open (not-yet-started) work lives in `BACKLOG.md`.

**Loop control** — pause: push `.work/paused` to `origin/main`; resume: remove it and push.
Start: tell the agent "go" / "работай". Status: ask "status" / "статус".

---

## Active tasks

- [ ] **std-nfc** — Add mobile NFC support as a portable `std.nfc` capability.
      WHY: NFC is a platform capability (Android `NfcAdapter`, iOS Core NFC,
      Web NFC), so `.ssc` user code must call `std.nfc` rather than importing
      `android.*`, `CoreNFC`, or browser globals directly. HOW: first write
      and commit `specs/std-nfc.md`; then add `Feature.NfcNdef` (plus deferred
      `NfcTagTech`/`NfcCardEmulation` gates if the enum shape allows), create
      `runtime/std/nfc.ssc`, scaffold `runtime/std/nfc-plugin/`, wire it in
      `build.sbt` and ServiceLoader, add interpreter test coverage with a mock
      adapter/clear `NotSupported` behavior, update docs/user guide, and add a
      small `examples/nfc-ndef.ssc`. MVP is NDEF read/write/status only.
      Rejected: direct backend blocks or raw platform types in user `.ssc`
      (violates backend-specific-blocks rule); card emulation in MVP (Android
      HCE and iOS NFC/SE entitlements have different security/product gates).
      Done when the spec behavior checklist is checked, focused tests pass via
      explicit `cd <worktree> && sbt "..."`, and unsupported targets fail
      predictably instead of exposing platform APIs.

Strategic-review proposals (2026-06-15) — the feature roadmap is built out; leverage has shifted from
building features to validating/hardening/enabling what exists. Work top-to-bottom.

- [x] **compile-time-at-scale** ✓ DONE 2026-06-15 — measured parse/type/jvmGen/jsGen across N=50→6400 defs: frontend LINEAR, codegen ~linear+mild tail, NO O(n²); 6400-def module compiles <0.5s. Added CompileScaleBench guard + docs/compile-scale-findings.md. ORIGINAL — every compile/codegen bench uses TINY inputs (6-line
      programs; jvmgen-codegen-time −94% was on those). Compile-time at REAL scale is UNMEASURED. HOW:
      build a large-program compile-time bench (generate a big synthetic `.ssc` — many defs/blocks/types
      and/or a deep import chain), profile parser/typer/normalize/codegen scaling vs input size, find any
      O(n²) hotspot. Fix if a clear one exists (A/B); else ship the scaling profile + a guard bench as the
      deliverable. This is the real perf frontier now that micro-perf is at floor.
- [x] **xbackend-property-equivalence** ✓ SLICE 1 DONE 2026-06-15 — CrossBackendPropertyTest generates core Int programs (arith/val/if/fns) from seeds + asserts interp==JS(node) over 40 + interp==JVM(scala-cli) over 5; all agree (core holds). Harness extensible; broadening (ADTs/match/effects) + overflow dimension = BACKLOG full-suite. ORIGINAL (slice 1) — harden the core "one source, many targets" guarantee:
      a property/fuzz test that GENERATES small programs over a core expression/stmt subset and asserts
      `interp output == jvm == js`. Start bounded (arithmetic, lets, if/match, simple functions); reuse the
      existing 127-case conformance harness + the ~70 property/fuzz test files as scaffolding. Each found
      divergence is a real cross-backend bug. Grows the conformance guarantee from fixed cases to generated.

_See BACKLOG.md "Strategic-review proposals" for the broader/deferred items (real-workload perf, registry, demand-driven)._ Tidied 2026-06-15 — the sprint was a backlog of already-completed work (autonomous
batches, busi fixes, JIT stages, rust/std milestones, the 4 architecture refinements, the
effect-vm-continuations performance thread). All of it has shipped and is recorded in
`CHANGELOG.md` + git history. Pick the next item from `BACKLOG.md` (open `[ ]` items) or take
a fresh direction.
