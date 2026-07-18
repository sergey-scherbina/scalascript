# ssc 2.0 — Roadmap

Clean-room self-hosting build, grounded on one binary `v2/ssc` (front + compiler +
runtime). Architecture & decisions: [`specs/00-overview.md`](specs/00-overview.md).
Pipeline: `ssc0 → ir → ssc(VM) → cpu`.

> **⚠️ RECONCILED 2026-07-18 (audit) — read this first.** This file was 9 days stale (last real
> edit 2026-07-09 at K62). Full evidence + backend matrix + kernel analysis:
> [`../specs/v2-state-2026-07-18.md`](../specs/v2-state-2026-07-18.md). Corrections are inlined below
> with `⚠️`. Measured deltas at a glance:
> - **The two self-host fixpoints HOLD** — P6.5 X1 **79,667 B**, P6.6 `C_min` **32,824 B**, byte-identical
>   `stage1==stage2`, re-verified from a clean build.
> - **"~4 source files under `src/`" is FALSE** — it is **9 files / 6355 lines** (`Runtime.scala` 4754).
> - **"Green: `conformance/check.sh`" is FALSE on HEAD** — `check.sh` **exits 1** (a multi-file `ssc0c
>   uselib` IR divergence + K62.3 StackOverflows).
> - **K3 "JS … TCO-correct + covered by conformance" is OVERSTATED** — full-`.ssc` v2-JS (`run-js --v2`)
>   crashes on `List.foldLeft`, `Map` access, and effects; tower Rust/WASM silently drop BigInt.
> - **This file omits the entire post-07-09 v2.2 P6.0→P6.18 self-hosting arc**, case classes (X1h),
>   the int-width law, CoreIR codec H4/H5, and the Swift renderer port. Add them when this roadmap
>   is next rewritten (they belong above/around K2–K3).

## K0 — Freeze Core IR  ✅ COMPLETE (2026-06-25)

- [x] `specs/10-core-ir.md` **frozen v1** — 10 values, 11 nodes, big-step semantics, TCO,
      primitive table. Decisions D1–D8 in `specs/00-overview.md`.
- [x] `specs/12-ir-format.md` — canonical S-expr bytecode (v1).
- [x] `specs/15-ssc0.md` — `ssc₀` grammar + lowering.
- [x] `conformance/*.coreir` fixtures.

## ssc — the runtime compiler (front + VM)  ✅ COMPLETE (2026-06-26)

One binary `v2/ssc`, scala-cli + Scala 3.8.3, zero deps on
the `ssc 1.0` tree. Fuses what was scoped as K1 (VM) + K-seed (ssc0 front). Decisions D9–D11.

> ⚠️ **CORRECTED 2026-07-18:** "~4 source files under `src/`" is **false** — `wc -l v2/src/*.scala` →
> **9 files, 6355 lines** (`Runtime.scala` 4754, `CoreIR` 415, `Emit` 292, `Ssc0` 311, `PortableEffects`
> 221, `PortableDecimal` 171, `NativeUiSites` 127, `Main` 62). Of these, `Emit`/`PortableEffects`/
> `PortableDecimal`/`NativeUiSites` and ~1200 lines of the δ table are **accretion** beyond the minimal
> kernel (the self-host fixpoint needs only **23** distinct `#`-prims; the whole tower uses **66**).
> See the kernel/tower analysis in `specs/v2-state-2026-07-18.md` §4.

- [x] **ir layer** (`CoreIR.scala`): Core IR ADTs + lenient S-expr reader + canonical writer
      (= `coreir.encode`).
- [x] **VM** (`Runtime.scala`): **compile-to-closures** (the JIT — each node compiled once
      into a closure) + trampoline driver (proper tail calls, constant stack) + δ.
- [x] **front** (`Ssc0.scala`): ssc0 lexer + parser + lower (name resolution to de Bruijn).
- [x] **CLI** (`Main.scala`): `run` (ssc0→ir→run), `compile` (ssc0→ir), `run-ir` (ir→run);
      `./ssc` launcher.
- [~] ⚠️ **PARTIAL 2026-07-18:** `conformance/check.sh` **exits 1** on HEAD (639 ok, 3 failing):
      (a) `FAIL ssc0c uselib.ssc0 — ir differs` (multi-file self-compiler differential; the single-file
      + multi-file *fixpoints* still pass); (b) 2× `ssc-run` `StackOverflowError` in
      `Compiler.compileEffectAwareApplication` — the known-open `coreir-compiler-unbounded-depth` /
      **K62.3**, surfacing at `check.sh`'s default-stack `ssc()` helper (the `bin/ssc` launcher's
      `-Xss64m` avoids it). `tco` at 1e6 depth in constant stack still passes. Evidence:
      `bash v2/conformance/check.sh > log 2>&1; echo $?` (NOT `| tail` — that reports tail's exit).

Resolved on the tower after the freeze: δ was widened, ssc0 imports became multi-file,
bare-`#prim` values are eta-expanded by `ssc0c`, and `v2-bin` provides a compact binary IR
tooling format. ⚠️ **`coreir.decode` is now a kernel primitive (done, 2026-07-18 — no longer open).**
Still deliberately open: Array-env for VM speed.

## K2 — grow the tower (toward self-hosting)  ✅ COMPLETE (2026-06-27)

Each layer is a program compiled/run by the layer below: `ssc0 → ssc.1 → ssct → …`.

- [x] δ-widened (full primitive set) + ssc0 `import`s + an ssc0 stdlib (2026-06-26).
- [x] **ssct — the typed layer** (2026-06-27, `lib/ssct.ssc0`, 136 lines): a typed lambda
      calculus type checker + erased evaluator, **written in ssc0** — types as an outer
      library, kernel stays untyped (D1). Spec `40-typer-as-library.md`. The thesis, live.
- [x] **textual `.ssct` surface** (2026-06-27, `lib/ssct-front.ssc0`, 170 lines): a real
      lexer + parser in ssc0; `./ssct <file.ssct>`.
- [x] **erase-to-ir + `coreir.encode`** (2026-06-27): the loop closes — `.ssct → typecheck →
      erase (ssc0) → ir bytecode → `ssc run-ir` (VM) → cpu`. Kernel gained exactly one
      primitive (`coreir.encode`). `./ssctc <file.ssct>` emits bytecode.
- [x] **SELF-HOSTING — fixpoint REACHED** (2026-06-27): `ssc0c` (`lib/ssc0c.ssc0`), the ssc0
      compiler written in ssc0, compiled by the Scala front and run on its **own source**,
      reproduces itself byte-for-byte (gen1==gen2==gen3). Differential invariant `ssc0c X ==
      ssc compile X` holds on fact/tco/map/calc. `specs/20-bootstrap.md`. **Kernel: +0.**
- [x] **Richer types / HM layer** — `Mira` grew Algorithm W, let-polymorphism,
      products/tuples, records, sums/user ADTs, pattern matching, qualified-type-style
      dictionary passing for Num/Ord/user methods, effect rows, and typed resumes for
      single-op effects — all in ssc0, Kernel +0.

## K3 — Regrow the world (on the tower)  ✅ NON-WASM COMPLETE through K45 (2026-06-29)

Incremental dogfood, each an ssc-compiled program: standard library; full type system;
effects/actors/async as libraries (lowered to ir, no kernel change); JVM/JS/WASM backends
as programs `ir → target`.

- [x] **Stdlib breadth** — list/string/option/stream helpers, structural `mapx`/`set`,
      Either/Result combinators, SHA-256, and full pure float math/rounding/trig/log library.
- [x] **Typed language breadth** — **Mira** (formerly `ssct-hm`) is the main typed surface
      and compiles real programs to VM, JS, and native Rust; `examples/hm-json.hm` is the
      current whole-language showcase.
- [~] **Backends** — ⚠️ **OVERSTATED (corrected 2026-07-18).** TCO is real (VM/Rust/WASM run `tco.ssc0`
      → 500000500000 in constant stack). But there are **two "JS" surfaces**: the *tower* JS
      (`ssc0 → lib/backend-js.ssc0`) passes conformance on ssc0 programs, while the **full-`.ssc` v2-JS
      lane** (`ssc-tools run-js --v2`) **crashes on `List.foldLeft` (`no dispatch`), `Map` access
      (`not callable: <map>`), and effects (`unimplemented primitive: effect.perform.oneshot`)** — it
      is NOT at full-language parity and NOT held to it by conformance. Tower **Rust/WASM silently drop
      BigInt** (`bigfact` → empty; the backend emits `V::U`). native + JVM-bytecode ARE at full parity.
      Full matrix: `specs/v2-state-2026-07-18.md` §3.
- [~] **Effects / async / actors** — ⚠️ **PARTLY IN-KERNEL (corrected 2026-07-18).** Algebraic effects
      are **not** purely a tower library: `PortableEffects.scala` (221 lines, 4 δ prims:
      `effect.pure/perform/perform.oneshot/handle`) is a continuation-manipulating handler **driver in
      the kernel** — which also tensions the "no effects/continuations in kernel" invariant below.
      Effects run on VM + JVM-bytecode + tower Rust (conformance: `effect run (State) -> Rust`), but
      NOT on v2-JS. `async-future` runs on the VM only (Rust/WASM hit the K62.3 overflow).
- [x] **WASM** — ✅ UNBLOCKED + SHIPPED (2026-07-05): `rustup` appeared in the environment;
      `rustup target add wasm32-wasip1` + reuse of the Rust backend, exactly as planned.
      `v2/ssc0-wasm` launcher (ssc0 → Rust → wasm32-wasip1 → Node's built-in WASI host,
      `scripts/run-wasi.mjs`); TCO carries over (tco.ssc0 = 1e6 tail calls, constant stack).
      Mira programs work identically (`mira-rust` output compiles with the same target).
      Toolchain-gated conformance checks in `conformance/check.sh`.

## K60 — Mira: rename + fence language registry

Rename `ssct-hm` → **Mira** throughout (files, binaries, conformance, docs).
Register Mira as a first-class fence language in v2 (` ```mira` blocks).
Spec: `specs/61-fence-languages.md`.

- [ ] **K54** — rename: files (`ssct-hm-front.ssc0` → `mira-front.ssc0`, etc.),
      launchers (`v2/mira`), conformance sections, SPRINT/ROADMAP.
- [ ] **K55** — Markdown extractor (KC1): `.ssc` → `(lang, source)` list, written in Mira.
      Fence-language dispatch table wired to existing compilers.

## K61 — v1.0-compat frontend (KC1–KC8)

Full `.ssc` v1.0 file support on the v2 kernel. Spec: `specs/60-compat-frontend.md`.
Phases: KC1 Markdown extractor → KC2 lexer → KC3 parser → KC4 functional lowering →
KC5 type checker → KC6 intrinsics → KC7 OOP lowering → KC8 given/using.

- [ ] **KC1** — Markdown extractor: written in Mira using K51 parser combinators.
- [ ] **KC2** — v1.0 lexer (keywords, identifiers, operators, literals, comments).
- [ ] **KC3** — v1.0 parser: functional subset AST (def/val/match/case class/import).
- [ ] **KC4** — functional subset lowering to Core IR. `println("hello")` works.
- [ ] **KC5** — HM-style type checker for the functional subset.
- [ ] **KC6** — intrinsics mapping: string/int/IO primitives. Add `scatstr`/`str->i`/`str->f`.
- [ ] **KC7** — OOP lowering: class/trait/object → records + vtable dicts.
- [ ] **KC8** — given/using → dict passing (same mechanism as Mira type classes).

## K62 — scalameta-free frontend parity

Measured 2026-07-09. Bring the native (scalameta-free) frontend to parity so
scalameta can be dropped from `v1/lang/core` and the `v2FrontendBridge` seam
retired. Spec: `specs/62-scalameta-free-frontend-parity.md`; tasks in `SPRINT.md`.

Baseline: the native `mira-md`→`ssc1-front`→`ssc1-lower` path already parses+lowers
**186/195 (95.4%)** of the real `examples/*.ssc` corpus. Remaining surface gap =
2 statement-sequence lowering bugs in `ssc1-lower.ssc0` (8 files). The parser is
not the hard part; the bulk of "v2 without v1" is runtime/stdlib parity (axis 3),
which is scalameta-independent.

- [x] **K62.0** — fence-tag policy fix (accept ` ```scala `): +32 files.
- [ ] **K62.1/2** — close the 2 lowering gaps (`Assign` mid-block; empty-tail seq).
- [ ] **K62.3** — compile-recursion robustness (StackOverflow on large programs).
- [ ] **K62.4** — measure native type-checker (`ssc1-check`) coverage (axis 2).

## Invariants across all milestones

- The kernel (the `ssc` binary) stays minimal and untyped: no type checker, no surface
  parser beyond ssc0, no effects/continuations, no target backend baked in. If it can be a
  program on the tower, it is.
- The ir is a real artifact: `ssc compile` emits it, `ssc run-ir` runs it; the canonical
  form makes a fixpoint/diff a byte compare.

> ⚠️ **INVARIANT DRIFT measured 2026-07-18** (analysis only — nothing moved; see
> `specs/v2-state-2026-07-18.md` §4):
> - **"no effects/continuations"** — violated by `PortableEffects.scala` (an effect-handler driver in
>   the kernel).
> - **"no target backend baked in"** — violated by `Emit.scala` (292 lines): the JVM-bytecode ASM
>   emitter's runtime surface ships in the kernel jar.
> - **"if it can be a program on the tower, it is"** — `NativeUiSites.scala` (127, std/ui ABI pass) and
>   `PortableDecimal.scala` (171, `dec.*` over BigDecimal) are kernel Scala that could be tower programs;
>   `NativeUiSites` isn't even used by the v2 kernel's own pipeline (only the v1 CLI + a v2 UI plugin +
>   the Swift backend reference it).
> - **Kernel size:** minimal-kernel target ≈ **2,400–2,800 lines** (~40–45 % of today's 6,355) once the
>   perf layers, `Emit`, `NativeUiSites`, `PortableDecimal`/`PortableEffects`, the interop glue, and the
>   ~1200 FrontendBridge/method-dispatch δ prims move off the kernel.
