# Sprint

Agent task queue — **active pending tasks only**. A task is "available" only if
`.work/active/<slug>.claim` does not exist on `origin/main`. Completed work lives in
`CHANGELOG.md`; open (not-yet-started) work lives in `BACKLOG.md`.

**Loop control** — pause: push `.work/paused` to `origin/main`; resume: remove it and push.
Start: tell the agent "go" / "работай". Status: ask "status" / "статус".

---

## Active tasks

Strategic-review proposals (2026-06-15) — the feature roadmap is built out; leverage has shifted from
building features to validating/hardening/enabling what exists. Work top-to-bottom.

- [ ] **std-nfc-native-adapters** — Continue NFC support beyond the portable
      `std.nfc` NDEF API skeleton by adding the next backend-facing slice from
      `specs/std-nfc.md`. HOW: first re-read `specs/std-nfc.md` and inspect the
      existing target/backend structure; keep the `.ssc` API unchanged; implement
      target adapters behind the existing `runtime/std/nfc-plugin` boundary; wire
      Android NFC permissions/foreground scanning, iOS Core NFC entitlements/session
      constraints, and Web NFC secure-context/user-activation gating where the
      current target infrastructure supports it. If full hardware execution is not
      yet possible in this repo, land the concrete adapter/SPI/packaging contract
      and focused tests that make the unsupported boundary explicit, then record the
      remaining real-device harness work. Done when `readNdef()` / `writeNdef()`
      behavior is verified in the real available harnesses, focused sbt tests pass
      with explicit `cd <worktree> && sbt "..."`, and unchecked behavior items in
      `specs/std-nfc.md` are either checked off or explicitly deferred with a
      follow-up.
- [ ] **rozum-integration** — Add ScalaScript integration with the adjacent Rust `rozum`
      app, following `../rozum/integration.md` and `../rozum/sdk.md`. HOW: read the
      rozum docs first, write and commit `specs/rozum-integration.md`, then implement
      the smallest ScalaScript surface that matches the documented SDK/integration
      contract. Keep any new user-facing APIs under `std.*` and implement required
      intrinsics in a `runtime/std/<feature>-plugin/` plugin, not interpreter core.
      Add a runnable `examples/` script and focused tests. Done when the example and
      relevant sbt tests pass from the worktree with explicit `cd`.
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
