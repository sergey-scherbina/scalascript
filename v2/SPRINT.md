# ssc 2.0 — Sprint (active task queue)

Self-contained queue for the isolated **`v2/`** subproject (separate from the repo-root
`SPRINT.md`, which serves ssc 1.0). Milestone view: [`ROADMAP.md`](ROADMAP.md). Pipeline:
`ssc0 → ir → ssc(VM) → cpu`. Work each slice in its own worktree off `origin/main`.

## Done

- [x] Core IR **frozen v1** + `12-ir-format` + `15-ssc0` + `conformance/*.coreir` (K0,
      2026-06-25).
- [x] **runtime compiler `v2/ssc`** (2026-06-26) — one Scala 3 binary, `src/`: CoreIR
      (ir + reader/writer), Runtime (**compile-to-closures** VM + trampoline TCO + δ), Ssc0
      (lexer/parser/lower), Main (CLI `run`/`compile`/`run-ir`) + `./ssc` launcher. All
      modes green via `conformance/check.sh` (ssc0 examples + ir fixtures + `ssc0→ir` map-def
      reproduction; `tco` 1e6 deep in constant stack). Renamed `ssc2/ → v2/`. (Fused the
      previously-separate K1 VM and K-seed front into one binary.)

## Pending (K2 — grow the tower)

- [x] **ssct — the typed layer** (2026-06-27) — `lib/ssct.ssc0` (136 lines): a typed lambda
      calculus with `infer` (synthesis-only type checker) + erased `evalTerm`, **written in
      ssc0** (D1: types as an outer library, kernel stays untyped). `check` = type-check then
      run. Spec `40-typer-as-library.md`.
- [x] **ssct textual surface** (2026-06-27) — `lib/ssct-front.ssc0` (170 lines): a real
      **lexer + parser written in ssc0** for `.ssct` text → `Term`. Driver `bin/ssct.ssc0` +
      `v2/ssct` launcher: `./ssct examples/id.ssct` ⟶ text→lex→parse→typecheck→run, all ssc0.
      Examples id/cond (`Typed(...)`) + bad (`TypeError`) + conformance. **Kernel byte-for-byte
      unchanged** (still 851 lines). Deferred: erase-to-ir via `coreir.encode`, HM/unification.
- [x] **erase-to-ir + coreir.encode** (2026-06-27) — closes the loop `.ssct → ir → run-ir`.
      Kernel +`coreir.encode` prim (`IrEncode`: IR-as-Data tree → canonical bytecode; the ONE
      place the kernel grew, +~60 LOC → 911) + Main skips printing `Unit`. `lib/ssct-emit.ssc0`
      (~25 ssc0): `erase` (de Bruijn + drop types) + `emit`. `bin/ssctc.ssc0` + `v2/ssctc`
      launcher. `./ssctc id.ssct | ./ssc run-ir` ⟶ 42; conformance asserts exact bytecode +
      run-ir result. The typed program now runs on the real VM.
- [x] **delta-widen** (2026-06-26) — full `δ`: `big.*`, `f.*` + numeric conversions, string
      group (UTF-16 units), bytes, data reflection (`tagOf`/`arity`/`fieldAt`),
      `map.*`/`arr.*`/`cell.*` (Foreign mutable), I/O (`readFile`/`writeFile`/`env`/`exit`).
      +103 LOC (722→825). Examples greet/bigfact/mapdemo + conformance. Lexer fix: `#i->big`
      prim names. Still deferred: `coreir.encode/decode` (with self-hosting), `mathx.*`.
- [x] **ssc0-imports** (2026-06-26) — `import "path"` (flat global namespace) via `Loader`:
      relative resolution, load-once / cycle-safe, duplicate-def-name error. `lib/list.ssc0`
      + `examples/uselib.ssc0` (sum(range(100))=4950) + conformance.
- [x] **stdlib + interpreter** (2026-06-26) — `lib/list.ssc0` (foldl/foldr/map/filter/append/
      reverse/length/sum/head/range), `lib/option.ssc0`; `examples/pipeline.ssc0`
      (sum∘map∘filter∘range = 120) + `examples/calc.ssc0` — a real expression-language
      interpreter in ~20 lines of ssc0 (ADTs, match, env, let → 42). Lexer: `;` now an
      optional separator. Demonstrates the thesis: rich behaviour = small ssc0 on a tiny kernel.
- [x] **self-hosting — FIXPOINT REACHED** (2026-06-27) — `lib/ssc0c.ssc0`: the ssc0 compiler
      written in ssc0 (lex+parse+lower+emit). Differential invariant `ssc0c X == ssc compile X`
      holds byte-for-byte: M1 (fact/tco) + M2 (match/ctor/let/letrec/str → map/calc).
      **M4: `examples/ssc0c-self.ssc0` (lib + main), compiled by the Scala front then run on its
      OWN source, reproduces itself byte-for-byte (gen1==gen2==gen3, 20413 bytes) — a stable
      self-hosting fixpoint.** `bin/ssc0c.ssc0` + `v2/ssc0c` launcher (-Xss512m for deep non-tail
      recursion) + conformance + spec `20-bootstrap.md`. **Kernel: +0 lines (still 913).** Left:
      M3 (ssc0c `import` resolution → multi-file self-compile).

## K3 — ssc 1.0 feature parity (all libraries/elaborations on the frozen kernel)

- [x] **algebraic effects + handlers** (2026-06-27) — `lib/effects.ssc0` (pure/perform/bind +
      handlers). State (one-shot) + nondeterminism (**multi-shot** continuations) examples +
      conformance + spec `50-effects.md`. Kernel +0. Effects = data + closures, no kernel node.
- [x] **async / cooperative concurrency** (2026-06-27) — `lib/async.ssc0`: `yield`/`fork`/`log`
      ops + a round-robin scheduler handler on `lib/effects.ssc0`. Demos: async-tasks (two tasks
      interleaved → 1,10,2,20,3) + async-fork (spawn → 1,2,100,3,200). Spec `51-async.md`.
      Kernel +0 — concurrency is a library. NEXT in async: await/futures/channels/mailboxes.
- [x] **typeclasses** (2026-06-27) — (a) `lib/typeclass.ssc0`: standalone type-directed resolution
      + dict passing, incl. conditional instances `Show a => Show (List a)`. (b) **Integrated into
      the `ssct` typer**: `show e` use site is instance-agnostic; `infer` enforces the `Show`
      constraint (rejects `show` on a function) and `elaborate` resolves+inserts the dict from the
      *inferred* type (`ShowM` → `ShowDispatch(instFor(typeOf e), …)`). Examples tc-show-int/bool
      (`Typed("String", …)`) + tc-show-err (`TypeError`). Specs `52`. Kernel +0. NEXT: multi-method
      classes (Eq/Num/Ord) = multi-field dicts; polymorphism (type vars) for real generic constraints.
- [x] **actors** (2026-06-27) — `lib/actors.ssc0`: behavior `(state, msg) -> (state', [Msg])`
      + a delivery loop (route by id, per-actor state, enqueue outputs). Demo: ping-pong
      bounce → Ball 0..5. Spec `53-actors.md`. Kernel +0. NEXT: concurrent actors with blocking
      `receive` on the async scheduler; supervision; wire protocol via `coreir.encode`.
- [ ] `do`-notation sugar for `bind` in the surface; typed effect rows in `ssct`.

## K4 — backends (ir → target, each an ssc0 program; "one source, many targets")

- [x] **backends are multi-file** (2026-06-27) — `lib/loader.ssc0` (shared DFS import loader)
      wired into the JS + Rust drivers, so stdlib-importing programs compile to every target.
      `examples/quicksort-lib.ssc0` (imports `lib/list`) runs identically on VM / JS / Rust.
      Plus `examples/quicksort.ssc0` (self-contained) — a real algorithm on all 3. Kernel +0.

- [x] **backend: ir → JS** (2026-06-27) — `lib/backend-js.ssc0` (reuses ssc0c front; walks IR
      → JS). **Now TCO-correct**: tail-aware codegen (`genE` with a tail flag) emits `bounce(f,a)`
      for a tail `IrApp`; `app` trampolines in a `while`. `./ssc0-js f.ssc0 | node` == VM for
      fact/map/calc **and tco** (1e6 in constant stack). Spec `60-backend-js.md`. Kernel +0.
- [x] **backend: ir → Rust** (2026-06-27) — `lib/backend-rust.ssc0`: emit Rust over a dynamic
      `V` enum + `Rc<dyn Fn>` closures. **Now TCO-correct**: closures return `Step=Val|Bounce`,
      tail `IrApp`→`Step::Bounce`, `app` loops (genV/genT split). `./ssc0-rust f.ssc0 | rustc` →
      native binary; output == VM for fact/map/calc **and tco** (1e6, constant stack). Spec
      `61-backend-rust.md`. Kernel +0. **3 targets, all TCO-correct: JVM / JS / native Rust.**
- [x] **ssc0c multi-file imports (M3)** (2026-06-27) — `bin/ssc0c.ssc0` resolves `import "path"`
      (DFS, load-once loader; `parseImports` + `parseTop` skips imports). `uselib.ssc0` (imports
      `lib/list.ssc0`) compiles byte-identically to Scala; **multi-file fixpoint**: `bin/ssc0c.ssc0`
      compiles itself across the import, reproduces itself (22533 bytes). Self-hosting spans files.
- [ ] **backend: ir → WASM** — BLOCKED on toolchain: only `node`'s WebAssembly API is present
      (binary `.wasm` only; no `wat2wasm`/`wasmtime`/rust-wasm-target). A from-scratch binary-wasm
      backend would need a kernel byte-builder primitive + a heap/GC runtime for closures/ADTs, and
      runs only integer programs without it. Options: install `wabt`/`wasmtime` or
      `rustup target add wasm32-wasip1` (then reuse the Rust backend), or build the binary emitter.

## Backlog

- [ ] bare-`#prim` η-expansion (needs a prim-arity table); Array-env for speed; `v2-bin`
      compact binary ir.
- [ ] `mathx.*` transcendental floats; structural map keys; `hash.sha256`. (Deferred opens,
      `10-core-ir.md §8`.)
- [ ] K3: stdlib, full type system, effects/actors as libraries, JVM/JS/WASM backends as
      ssc-compiled programs `ir → target`.

## K5 — ssct-hm: a Hindley-Milner typed language (DONE 2026-06-27)

- [x] **ssct-hm** — a complete HM-inferred typed FP language in ssc0 (`lib/ssct-hm*.ssc0`):
      Algorithm-W inference + let-polymorphism; Int/Bool/String; polymorphic lists `[a]` with
      literals; **user `data` types + pattern matching** (`match { | }`); full arith/cmp/string
      ops. Source text → infer → interpret OR erase → Core IR → **VM / JS / native Rust**.
      Showcases: factorial, map, quicksort (dups), a typed expression interpreter — all compile
      to native code. Spec `41-ssct-hm.md`. 161 conformance checks. Kernel +0 (still 913).

## K6 — ssct-hm: full ssc 1.0 feature parity (plan)

Toolchain confirmed: kernel has the full **`f.*`** float group + **`i->f`/`str->f`**, and
**reflection** (`tagOf`/`arity`/`fieldAt`) — so generic `show`/`eq`/`compare` are expressible.

- [x] **Float** (K6.1) — `TyFloat` + float literals (`3.14`) + float ops (`+. -. *. /.`, `<.` etc.) +
      `toFloat`/`floor`; add `f.*`/`i->f` to the JS + Rust genPrims. All 3 backends.
- [x] **Tuples** (K6.2) — `(a, b)` (n-ary) + projections; `TyTup [t]`; erase → IrCtor("Tuple", …).
- [x] **Records** (K6.5) — `{x = e, y = e}` + field access `r.x`; closed structural record types.
- [x] **Polymorphic `show` (Show typeclass)** (K6.3) — one generic structural renderer via `tagOf`/`arity`/`fieldAt`
      (+ those prims in JS/Rust gen); `show` works on any type, same output everywhere.
- [x] **Polymorphic `eq`/`compare` (Eq/Ord)** (K6.4) — generic structural equality + ordering via reflection;
      makes equality/ordering work on any type, consistent across VM/JS/Rust.
- [x] **User typeclasses** (K6.12) — `method m` + `instance m T = impl`, resolved monomorphically by the
      argument's type-head. (Full dictionary-passing with constraints-in-the-type is a deferred research
      item, same class as effect-rows below.)
- [x] **More surface features** (K6.13–K6.28, this batch) — boolean `&& || not`; string ops `strLen/charAt/
      substr`; currying (`fun x y =>`, `let f x y =`); pattern matching: wildcard `_`, variable catch-all,
      Int/String/Bool literals, **nested patterns** (backtracking compiler) + list `Cons`/`Nil`; `//`
      comments; **monadic do-notation**; **type ascription** `(e : T)`; a 32-function auto-injected prelude.
      Conformance 161 → 277, all 3 backends.

## K7 — Typed algebraic effects in the ssct-hm surface

Bring ssc 1.0's signature feature — algebraic effects + handlers (one-shot AND multi-shot) — into the
TYPED surface, all 3 backends. The untyped library (`lib/effects.ssc0`, `Comp = Pure | Op(label,arg,
resume)`) already proves the mechanism on the VM. The blocker is that `Op`'s `arg`/`resume`-reply are
existential. Two complementary tracks (chosen design; full effect-row inference deferred as research):

Track P — **type-safe per-effect free monads** (no `Dyn`, no existentials): each effect is a free monad
over its own functor = a plain user `data` type with **function-typed fields**.
- [x] **P1. function types in data fields** — `data F a = Op (Int -> F a) | Ret a`; `parseFieldType`
      accepts `->` (right-assoc, `TyFun`). Function-in-ctor-field already runs at runtime; this enables it
      at the type level. All 3 backends.
- [x] **P2. State effect (one-shot)** — `data StateF a = Ret a | Get (Int -> StateF a) | Put Int (StateF a)`
      + `get`/`put` + `bindS`/`pureS` + `runState : StateF a -> Int -> Pair a Int`; via do-notation. Typed
      end-to-end (e.g. `get; put (get+1); …` ⇒ `Pair(2,2)`-style). All 3 backends.
- [x] **P3. Nondeterminism (MULTI-SHOT)** — `data NondetF a = Ret a | Choose [Int] ([Int]? )` (choose +
      runAll collecting every branch); verify multi-shot resume on run-ir / node / rust.

Track E — **universal `Comp` via a localized `Dyn` escape-hatch** (option B): one monad for all effects.
- [x] **E1. `Dyn` type** — `TyDyn`, unifies with any type (both directions); surface type `Dyn`;
      ascription round-trip `((x : Dyn) : Int)`. The single, documented unsafe escape-hatch.
- [x] **E2. universal `Comp` + `perform`/`pure`/`bind` + a multi-op handler** using `Dyn` payloads;
      typed operation wrappers (`get : Comp Int`, …) so user code stays type-safe.

- [x] **DOC/CONF** — `specs/50-effects.md` (typed-surface section) + `specs/41-ssct-hm.md`; conformance for
      State (one-shot) + Nondeterminism (multi-shot), both tracks, all backends.

OPEN (deferred, research): **full effect-row inference** — `Comp` tracks WHICH effects (row polymorphism,
Koka/Frank style). Disproportionate for the ~430-line inferrer; Track P already types effects per-effect.

## K8 — Overloaded numeric operators (Int + Float)

Make `+ - *` and `< =` work on **Float** as well as `Int`, resolved by operand type (today Float needs
`fadd`/`fsub`/`flt`/…). HM has no qualified types, so use the proven id-tagged-node + `tcReg` mechanism
plus **eager numeric defaulting**: at an operator node, unify the operands; concrete `Int`/`Float` ⇒ use
it; a still-unresolved type-var ⇒ default to `Int`; any other type ⇒ reject. Sound (a later non-numeric
constraint conflicts). One documented sharp edge: an all-type-var chain defaults to Int, so `r*r*pi`
needs a leading concrete float or `(r : Float)*…` (ascription). `fadd`/etc. stay as-is (back-compat).

- [x] **K8.1 — overloaded `+ - *`** — id-tag `Add`/`Sub`/`Mul`; `inferNum` resolves Int/Float (eager
      default), records the type in `tcReg`; erase emits `i.*`/`f.*` by the recorded type; eval value-
      dispatches (IntVal/FloatVal). `1.5 + 2.5` ⇒ Float, `1 + 2` ⇒ Int, all 3 backends. `"a" + "b"` rejected.
- [x] **K8.2 — overloaded `< =`** (and the derived `> <= >= <>`) — same mechanism for `Lt`/`Eq`; result
      Bool, operands Int or Float. `1.5 < 2.5`, `1.0 = 1.0` work; all 3 backends.
- [x] **K8.3 — overloaded `/`** — same id-tag / `inferNum` / eager-default mechanism for a new `Div(id,a,b)`
      node (lexer already emits `TPunct("/")`; `//` is the comment, caught first; `/` is multiplicative
      precedence, left-assoc). `20 / 6` ⇒ `Int` `3` (truncating), `9.0 / 2.0` ⇒ `Float` `4.5`, `9.0 / 2`
      rejected. All 3 backends. Found+fixed a latent JS bug: `i.div` emitted JS `/` (always float) → now
      `Math.trunc(a / b)` (truncates toward zero like JVM/Rust; the old `div` function was wrong on JS too).
      conformance +5.
- [x] **DOC/CONF** — spec 41 (numeric operators are overloaded; the defaulting note) + conformance.

OPEN (deferred): Fully-general numeric polymorphism (e.g. `r*r*pi` with `r` a param) needs qualified types
(`Num a =>`), the same research-level work as effect rows; eager defaulting is the sound pragmatic choice.

## K9 — Concurrency in the typed surface (on the typed effects)

- [x] **typed async** — `examples/hm-async.hm`: yield/log ops over the universal `Comp`, a round-robin
      `runSched` handler, cooperative interleaving of tasks ⇒ `[1, 2, 101, 102]`. All 3 backends.
- [x] **typed actors** — `examples/hm-actors.hm`: a stateful behavior `(state, msg) -> (state', out)`
      over a message stream ⇒ `[2, 3, 2]`. All 3 backends.

## Remaining (genuinely blocked, not "todo")

- **Effect rows** — now in progress as **K10** below (light version: track the effect *set*); the full
  Koka-style system (typed payloads) stays deferred.
- **WASM backend** — toolchain-blocked, re-confirmed 2026-06-28: no `rustup` (so `rustc` can't build
  `wasm32-unknown-unknown` — "can't find crate for std"), and no `wabt`/`wasmtime`/`wasmer` to assemble or
  run wasm. Unblock = install `rustup` + `rustup target add wasm32-unknown-unknown`, or `wabt`+`wasmtime`.

## K10 — Effect rows (research; light implementation)

Track effects in the type: `Comp {get, put | ρ} a`; `run : Comp {} a -> a` enforces "no unhandled
effects". Chosen scope (agreed): **doc + spike + light** (rows track labels over the existing `Dyn`
`Comp`; payloads stay `Dyn`; full Koka-style typed-operations deferred). Spec `54-effect-rows.md`.

- [x] **K10.1 — row-unification spike** — Rémy-style row unification in ssc0, validated on the three
      canonical cases (`{get|ρ}~{put,get}`; `{get}~{put}` fails; `{get|ρ1}~{put|ρ2}` shares a tail).
- [x] **K10.2 — design doc** — `specs/54-effect-rows.md`.
- [x] **K10.3 — rows in the inferrer** — `TyRowEmpty`/`TyRowExt`/`TyRowVar` + `rowUnify`/`rowRewrite`
      (fresh row-vars via a global cell, based high) + `unify` dispatch (incl. `Comp[r,a]`) +
      appTy/occurs/freeTy/renameTy/showTyR. Validated; no regression.
- [x] **K10.4a — type-level effect surface** — built-in `pureE`/`bindE`/`getE`/`putE`/`runStateE`/`runE`
      with row-carrying types; `Comp {S | ρ}` tracks effects, `runStateE` removes `State`, `runE : Comp {}
      a -> a` so an **unhandled effect is a type error** (`runE getE` rejected). Demonstrated at the type
      level (`examples/hm-effrow.hm` ⇒ `(Int, Int)`; `runE getE` ⇒ TypeError). conformance +3.
- [x] **K10.4b — runtime (runs on all backends)** — `erase` lowers the effect built-ins to the universal
      `Comp` (`Pure`/`Op`) + global helper defs `__effBind`/`__effRun`/`__effRunSt` (hand-coded de Bruijn,
      appended by `progOf` when effects are used). Effectful programs now RUN, not just type-check:
      `examples/hm-effrow.hm` (put 5; get; return get+100, handled by `runStateE`, then `runE`) ⇒
      `Pair(105, 5)` on run-ir / node / rustc. conformance +3 (314 → 317).
- [x] **K10.4c — a SECOND effect (proves rows, not one monad)** — added a `Log` effect (`logE` /
      `runLogE : Comp {Log | ρ} a -> Comp ρ (Pair a [Dyn])`, collects logged values in emission order via
      an accumulator + `__effRevApp`; its handler forwards non-Log ops so it composes in either order).
      `examples/hm-eff2.hm` (put 3; **log 7**; get; return get+100) has row `{State, Log}` — the type tracks
      **both** and `runE` demands **both** handled: `runE (runLogE (runStateE … 0))` ⇒ type
      `((Int, Int), [Dyn])`, value `Pair(Pair(103, 3), Cons(7, Nil))` on run-ir / node / rustc; forgetting
      `runLogE` ⇒ `TypeError: effect not handled: Log`. This is the headline payoff of ROWS over a single
      effect monad. conformance +5 (317 → 322).
- [x] **K10.4d — USER-EXTENSIBLE effects (`perform` + general `handle`)** — effects are no longer hard-wired
      to the built-in State/Log demos. Two new primitives, no new keyword syntax (they reuse the application
      spine): `perform "Eff" "op" arg` performs any user effect (→ `EffOp`, row-tracked), and
      `handle "Eff" comp (v => ..) (op => arg => k => ..)` is a general **deep handler** — a literal effect
      label so `infer` does the row surgery (`Comp {Eff | ρ} a → Comp ρ b`), and at runtime it FORWARDS other
      effects so handlers compose. Because the resume `k` re-enters the handler, it is deep + **multi-shot**.
      Demo `examples/hm-eff-handle.hm` — nondeterminism: a user-written handler that calls `k` **twice**
      (`k true` ++ `k false`) over `flip; flip` ⇒ type `[Int]`, value `[3,2,1,0]` on run-ir / node / rustc;
      `runE (perform "Choose" "flip" …)` ⇒ `TypeError: effect not handled: Choose` (a *user* effect, tracked).
      Two supporting fixes: (1) **row-var generalization** — `appTy`/`renameTy` now re-tag a freshened
      (instantiated) ordinary var as a row var at row positions (`asRow`), so a `let`-bound polymorphic handler
      instantiates its row var as a row, not an ordinary var (was: `TypeError: not an effect row`). (2)
      **unified runtime convention** — the universal `Op`'s label is now the EFFECT name with the op name in a
      `Pair op arg` payload (handlers match by effect, dispatch by op); `__effRunSt`/`__effLogGo` updated to
      match. conformance +5 (322 → 327). NOTE: K9 async/actors use a *source-level* `Comp`, unaffected.
- [x] **K10.4e.1 — the general `handle` SUBSUMES the built-ins** — `examples/hm-eff-userstate.hm`
      re-implements `runState` **entirely in user source** with only `perform`/`handle`/`bindE`/`pureE` — a
      *parameterized* (state-threading) handler where the handled computation returns a function of the state
      (`b = Int -> Comp ρ (Pair a Int)`; `get` = `λs. (k s) s`, `put a` = `λs. (k ()) a`). ⇒ type `(Int, Int)`,
      value `Pair(105, 5)` on run-ir / node / rustc — identical to the built-in `runStateE`. ZERO compiler
      change: it shows the general deep handler already covers stateful effects, not just the pure/multi-shot
      ones. conformance +4 (327 → 331).
- [x] **K10.4e.2 — `doE` do-notation for effects** — `doE { x <- m ; … ; result }` desugars `<-` to `bindE`
      (and the final stmt is the result), so effect code reads top-to-bottom instead of nested `bindE (..)
      (fun x => ..)`. The existing `do` (→ `bind`, used by Option / the K9 async `Comp`) is untouched —
      `parseDoStmts` is now parameterized by the bind name. `examples/hm-eff-do.hm` (State) ⇒ `(Int, Int)` /
      `Pair(105, 5)`; `examples/hm-eff-do-nondet.hm` uses `doE` in BOTH the handler body and the computation ⇒
      `[Int]` / `[3,2,1,0]` — all on run-ir / node / rustc. conformance +7 (331 → 338).
## K11 — finish everything remaining (2026-06-28, user: "делай всё, не останавливайся")

Close out the whole remaining frontier. Ordered easy→hard; each slice ships green on all 3 backends.

- [x] **K11.1 — `effect` declaration sugar** — `effect Name op1 op2 in <body>` registers each `opK` as an
      operation of effect `Name` (`effOpReg` + `registerEffOp`/`effOpOf`/`isEffOp`/`effLabel`); `opK arg`
      desugars to `EffOp("Name","opK",arg)`, bare `opK` to `EffOp(..,Lit 0)`. `parseEffectDecl` (op-name list
      stops at `in`; `effect` is a keyword); applied ops intercepted in `desugar`'s App case before the
      bare-op Var rule. `handle` stays string-based. `hm-eff-decl.hm` (State) ⇒ `(Int,Int)`/`Pair(105,5)`;
      `hm-eff-decl-choose.hm` (`effect Choose flip` + multi-shot `handle`) ⇒ `[Int]`/`[3,2,1,0]`;
      declared-unhandled ⇒ `TypeError: effect not handled: Choose`. All 3 backends. conformance +8.
- [x] **K11.2 — row syntax in the type parser** — `{}` / `{l}` / `{l, m}` (closed) / `{l | r}` (open, fresh
      tail var) parse as *row* types in ascriptions, and `Comp {row} a` works: `(getE : Comp {State} Dyn)`,
      `(comp : Comp {State} Int)`. `parseRowType`/`parseRowMore`/`parseRowTail` added; `{` starts an asc atom.
      `showTyR` now renders rows as `{State}` / `{State, Log}` / `{State | eN}` (a single open label `{l | eN}`
      is unchanged → existing checks safe). Wrong ascription (`getE : Comp {Log} Dyn`) rejected.
      `examples/hm-eff-rowann.hm` (doE block ascribed `: Comp {State} Int`) ⇒ `(Int,Int)`/`Pair(105,5)`, all 3
      backends. Records (`{x = e}`) untouched (different parse position). conformance +7.
- [x] **K11.3 — NUMERIC POLYMORPHISM (light qualified types via inlining)** — the documented K8 sharp edge
      is GONE for the common case: `let twice = fun x => x + x in (twice 5, twice 2.25)` ⇒ `(Int, Float)` /
      `Pair(10, 4.5)` on all 3 backends. Done WITHOUT dictionary passing (no backend change), via two pieces:
      (1) **inlining** — a non-recursive `let f = <closed numeric fn> in …` is unfolded (pure beta) so each use
      of `f` is an independent copy with FRESH numeric ids; "closed" = `freeVars` empty (auto-excludes recursive
      / prelude-using fns) AND `containsNumOp` (so non-numeric helpers like `id` are untouched → no var-number
      churn). (2) **deferred numeric resolution** — an overloaded op on an unresolved var records the var (not
      eager-Int); it's resolved at let-generalization (`defaultPendingIn` → keeps NON-inlined numeric lets
      monomorphic-Int = sound) and finally at the top (`finalDefault` → Int); `resolveNum(s)` re-bakes `tcReg`
      through the final subst before erase, threaded into all drivers. So each inlined copy resolves its own
      Int/Float from its argument. conformance +5 (358 → 363); `int helper still Int` guards soundness.
- [x] **K11.3b — USER-TYPECLASS POLYMORPHISM (same inlining trick + method result sig)** — a user `method`
      with a RESULT-TYPE signature works POLYMORPHICALLY inside a closed function: `method describe : String
      in … let f = fun x => describe x in (f 5, f true)` ⇒ `(String, String)` / `Pair("an int", "a bool")` on
      all 3 backends — `describe` dispatches to the Int instance for `f 5` and the Bool instance for `f true`.
      `method m : R in` records the result type (`methodSigReg`); `MethodCall` on a still-variable receiver
      DEFERS (`pendingMethods`) returning `R`, and the instance is resolved at the top (`resolveMethods`, in
      `resolveNum`) once inlining has made the receiver concrete. Inlining is relaxed to allow free **method**
      names (capture-safe — methods are global) via `isMethodApp`/`allMethods`. Methods WITHOUT a signature
      keep resolving eagerly/monomorphically (regression `sz 5 => 6`). conformance +5. LIMITATION: only
      fixed-result methods (result not depending on the receiver, e.g. `show`/`compare`/`describe`) in CLOSED
      functions; non-closed method uses still need the full dict-passing design.
- [x] **K11.3c — RECEIVER-RESULT methods + impl soundness** — (1) `method m : self in` makes the result the
      RECEIVER type, so `method negate : self in … let f = fun x => negate x in (f 5, f 2.5)` ⇒ `(Int, Float)`
      / `Pair(-5, -2.5)` — `negate` polymorphic over Int & Float (`selfRes` returns the receiver var when the
      sig is `self`). (2) SOUNDNESS fix: the deferred path now TYPE-CHECKS the chosen impl with the concrete
      receiver (`checkImpl`), so an impl that itself uses an overloaded op (`0.0 - x`, `x < 3.0`) resolves to
      the right primitive (`f.sub`/`f.lt`) — previously it defaulted to `i.*` and crashed run-ir / panicked
      Rust. (3) `ascAtomStarts` stops at keywords so a method/op result type before `in` parses (`: self in`).
      conformance +4 (method-poly-ops, the op-impl soundness guard) +4 (method-self). All 3 backends.
- [x] **K11.4 — TYPED PAYLOADS (light)** — `effect Name { op : ArgT -> ReplyT , … } in …` gives each op a
      SIGNATURE (`effSigReg`); a typed op's arg is checked against `ArgT` and its reply is `ReplyT` (not `Dyn`),
      so effect code drops the `(x : Int)` / `(5 : Dyn)` ascriptions: `effect State { get : Dyn -> Int , put :
      Int -> Dyn } in doE { u <- put 5 ; x <- get ; pureE (x + 100) }` ⇒ `x : Int` inferred, `(Int, Int)` /
      `Pair(105, 5)` on all 3 backends; `put "x"` ⇒ `TypeError: effect op arg type mismatch`. `EffOp` infer
      uses the signature when present, falls back to `Dyn` otherwise (untyped decls + string `perform`
      unchanged). Built-in handlers (runStateE/…) are generic over the value type, so they consume typed
      replies unchanged. conformance +5. NOTE the **general `handle`** keeps a `Dyn` resume (typing a
      user-written handler's resume against the signature is the deeper Koka step, still open).
- [x] **K11.5 — spec sweep** — specs (41/50/54) updated to the shipped surface (earlier commit) + new
      `55-qualified-types.md` design + `54`'s "Path to full" updated (effect-decl & row-syntax landed; payloads
      build on K11.1/K11.3). Effect examples already demonstrate `doE` in dedicated files (`hm-eff-do*.hm`,
      `hm-eff-decl*.hm`); the older explicit-`bindE` examples are kept intentionally (they document the
      desugaring) rather than churned.

## K12 — mutual recursion

- [x] **K12.1 — `let rec f = .. and g = .. in ..`** — mutual recursion, a genuinely-missing feature. New
      `LetRecM(binds, body)` node (single `let rec` still → `LetRec`); `and` is a keyword. Infer binds all
      names to fresh vars in a shared env, infers each lam, unifies, then generalizes all (numeric vars
      defaulted at generalization, sound). Erases to the IR's existing list-form `IrLetRec` (last bind = idx
      0, matched by `prependAll`). run-ir + JS supported the multi-binding `IrLetRec` already; the **Rust
      backend only did single-binding (bailed to `V::U`) — extended it** with an n-way knot-tie (n
      `RefCell<V>` cells + n self-ref closures + write each real lam into its cell). `examples/hm-mutual.hm`
      (isEven/isOdd) ⇒ `Bool`/`true`; a 3-way `f→g→h→f` ⇒ `1`; all on run-ir / node / rustc. (The tree-walk
      interp `evalT` is `Stuck` for `LetRecM` — not conformance-exercised; effects run via the compile path.)
      conformance +5.
- [x] **K12.2 — 4-tuples (Quad), fix silent truncation** — `(1, 2, 3, 4)` used to **silently drop the 4th
      element** (the tuple builder made a `Triple` from the first 3) — a data-loss bug. Now `(a,b,c,d)` builds a
      `Quad` (new built-in con, arity 4, added to `builtinCon`/showable/`showTyR`/value-show); arity ≥5 nests
      the tail (`Quad(a,b,c,(d,e,…))`) so no element is lost. `mkTuple` + `mkTuplePat` extended. `(1,2,3,4)` ⇒
      `(Int,Int,Int,Int)` / `Quad(1,2,3,4)`; `(a,b,c,d) => a+b+c+d` ⇒ `10`; a 5-tuple `(a,b,c,(d,e))` matches.
      All 3 backends. conformance +5. (NOTE: ssct-hm match syntax is `pat => body | …` — NO `case` keyword;
      var-catch-all and tuple patterns already worked.)
- [x] **K12.3 — pattern guards** — `match x { pat if cond => body | … }`: a failed guard falls through to the
      next arm. No new AST node — a guarded arm parses to `PatArm(pat, If(cond, body, Var "$guardfail"))`
      (forcing the compiler path), and `compileArms` substitutes `$guardfail` with that arm's fail
      continuation (`if cond then body else <next arm>`). Fixed a parser bug it exposed: `patStarts` now
      stops at keywords, so `Som x if x > 0` doesn't slurp `if x` as extra sub-patterns. `examples/hm-guard.hm`
      (a `classify` with `x<0`/`x>0`/else) ⇒ `0`; `Som x if x>10 => 100 | Som x => x` on `Som 5` ⇒ `5`
      (fallthrough). All 3 backends. conformance +6.
- [x] **K12.4 — string escapes** — `\n` `\t` `\r` `\\` `\"` in string literals. The lexer un-escapes the
      source (and `scanStr` skips `\"` so it isn't a terminator); the JS/Rust gens re-escape the string VALUE
      on emit (`escapeStr` in emit, shared) so the generated source is valid; the Core IR already round-trips
      special chars (kernel `strLit`/`readString`), so run-ir needed no change. `strLen "a\nb\tc"` ⇒ `5`;
      `strLen "x\"y"` ⇒ `3`. All 3 backends. conformance +5.
- [x] **K12.5 — record update** — `{ r with f = v , … }` builds a new record like `r` with field `f`
      replaced (multiple fields chain into nested `RecUpd`). New `RecUpd(id, rc, field, val)` node (mirrors
      `FieldGet` across desugar/freeVars/inline/freshen/subst/containsNumOp); infer is type-directed — `r` must
      be a `TyRec` with the field, `val` is checked against the field's type, result = `r`'s type; erase
      matches `r` once (binding all `ar` fields) and rebuilds `__rec` with field `idx` replaced (`recUpdFields`
      + `prependN` to shift the value's scope past the `ar` field binders). `with` is a keyword.
      `{r with x = 9}` then `r2.x + r2.y` ⇒ `11`; `{r with x=9, y=8}` ⇒ `17`; `{r with z = …}` rejected. All 3
      backends (records are generic `__rec` Data → no backend change). conformance +5.
- [x] **K12.6 — negative literals** — unary minus on a numeric literal: `-5`, `-2.5`, usable at any operand
      position (`x * -2`, `6 / -2`, `f (-3)`, `[-1, -2]`). New `parseUMinus` sits between `parseMul` and
      `parseApp`: a leading `-` immediately followed by `TNum`/`TFloat` becomes `0 - n` (object `Sub`, since the
      ssc0 KERNEL has no infix `-`) / a negative float-string `FloatLit`. Used at EVERY operand slot (parseMul
      entry + `*`/`/` right operands in parseMulMore + `+`/`-` right operands already go through parseMul). `-`
      is *not* an `atomStart`, so application args never grab it and binary `f - 5` / `a - 1` stay subtraction.
      `-x` / `-(e)` are still not negation (write `0 - x`). `-3 * -2` ⇒ `6`, `-5 + 3` ⇒ `-2`. Pure front-end
      desugar → all 3 backends unchanged. conformance +5.
- [x] **K12.7 — let-binding type annotation** — `let x : T = e in body` ascribes the bound expr to `T`
      (no params). `parseLetBind` now intercepts a `:` immediately after the binder name, parses the type with
      the existing `parseAscType`, and wraps the bound expr in the existing `Ascribe(e, ty)` node (so the
      annotation is *enforced* — `let x : Int = true` is rejected with "type ascription mismatch"). Refactored
      into `parseLetBindPlain` (the old `name param* = e` path) + `parseLetBindAsc`. No regression: `let x = e`,
      `let f x = e` (fn sugar — token after `f` is a name, not `:`), and `let rec` are untouched. Reuses the
      `(e : T)` ascription machinery → 0 backend change. `let xs : [Int] = [1,2] in xs` works on all 3 backends.
      conformance +5.
BLOCKED (not doable here): **ir → WASM** — no `rustup`/`wasmtime`/`wabt` toolchain in this environment
(only node's WebAssembly API). Documented in K4; revisit when the toolchain is available.

## K13 — non-closed qualified types via dictionary passing (the one substantial remaining feature)

Status of qualified types today: the **closed** case is DONE (K11.3 light qualified types) — a polymorphic
helper whose whole body is visible is `inlineClosed`-unfolded (fresh ids per copy) and its overloaded
numerics / user-method calls are **deferred** (`pendingNum` / `methodSigReg`) and resolved per concrete use,
impls type-checked → sound. What's missing is the **non-closed** case: a top-level polymorphic binding used at
several concrete types **without** inlining (recursive, large, or exported across a module boundary). That
needs real **dictionary passing** (spec `specs/55-qualified-types.md`). The values stay concretely typed; only
the *operations* are passed, so a `Num`/`Ord` dict is just a **type-tag string** + global tag-dispatch helpers.

⚠️ This is a DEEP, PERVASIVE inferrer change (probed 2026-06-28), NOT a tail-of-session slice — do it as a
focused effort, gate conformance after EACH slice. Key cost: `Forall(qs, ty)` must gain a constraints field
→ touched at ~15+ sites (`generalize`, `instantiate`, `freeEnv`, `envWithRecVars`, every `Forall(Nil, …)` in
`infer`). `pendingNum` is today's Num-only side-channel — the seed of the constraint set, extend it.

- [x] **K13.0 — non-self-recursive `let rec` gets let-polymorphism** (2026-06-29). Empirically (2026-06-28) the
      whole practical qualified-types gap was `let rec` poly-numeric bindings used at 2+ types; most such cases
      are *gratuitous* `let rec` — the binding doesn't actually call itself (e.g. `let rec dbl = fun x => x + x
      in (dbl 3, dbl 2.5)`). `inlineClosed`'s `LetRec` case now checks `isParam(f, freeVars(l))`: if `f` is NOT
      free in its own body it's really a plain `let`, so it's rewritten to `Let(f, l, b)` and picks up the
      existing inlining-based let-polymorphism (incl. numeric). `dbl` now types `(Int, Float)` and runs on all 3
      backends; genuinely self-recursive functions (`fact`, `sum`, mutual rec) are untouched. conformance +5.
      **Still open (true dict-passing, K13.1+):** a GENUINELY self-recursive binding polymorphic over a numeric
      type with no anchoring literal, used at 2+ types — e.g. `let rec scale = fun x => fun n => if n=0 then x
      else x + scale x (n-1) in (scale 3 2, scale 2.5 1)` — still "cannot unify Int". This is extremely rare
      (the base case usually anchors the type) and remains sound-reject. It needs the inference change below
      (generalize the rec binding's Num var instead of defaulting) AND dict-param codegen, since one erased copy
      of `scale` can't use both `i.add` and `f.add`.

- [x] **K13.1 — DICT-PASSING FOR `Num` SHIPPED (2026-06-29)** — via a TARGETED design (simpler than the
      Forall-constraints-field plan below). A `let rec f` whose quantified type has a single still-**pending**
      (unanchored) numeric var `dv` is marked a **dict-fn** (`dictFnReg: name -> (dv, argPos)`) instead of
      defaulting `dv` to Int — so `f` generalises and is usable at multiple numeric types. Erase: a dict-fn's
      lam gets a leading `$dict` param (`λ$dict. <lam>`, `curDictV = Some(dv)` + `"$dict"` in scope while
      erasing its body); an overloaded op whose `instOf(id)` IS `dv` dispatches via `__nadd`/`__nsub`/`__nmul`/
      `__ndiv`/`__nlt` `(tag, a, b)` (global helpers that branch on `seq(tag,"Float")` → `f.*` else `i.*`). At a
      call site (`spineHead`/`spineArgs`), the dict is the **enclosing `$dict`** if inside a dict-fn body
      (recursive call), else a **literal tag** read from the dict-typed argument's syntactic form (`tagOfArg`:
      `Lit`→"Int", `FloatLit`→"Float", arith op→`instOf`, ascription→its type). **0 kernel / 0 backend change**
      — `__n*` are ordinary IR globals. `let rec scale = fun x => fun n => if n=0 then x else x + scale x (n-1)`
      used at BOTH Int (`scale 3 2`=9) and Float (`scale 2.5 1`=5.0) now runs on run-ir/JS/Rust; likewise `*`
      (`acc 2 3`=16, `acc 1.5 1`=2.25). Scoping is conservative: anchored numeric `let rec` (e.g. `sum`, `fact`)
      keep a resolved (non-pending) var → NOT dict-fns → unchanged; non-numeric polymorphic recursion (`map`)
      has no pending var → unchanged. **Limitations:** only the `Num` class; a single dict var per binding; the
      external-call tag must be readable from the argument's form (a bare `Var` of unknown concrete type
      defaults to "Int"); the dict-fn must be directly applied (not passed as a value). conformance +5.
- [ ] **K13.4 — extend to `Ord` + user classes** (remaining). `Ord` dict (compare/lt); user classes need
      method signatures + a record-of-impls dict. Generic `min3` over a user `Ord`. (The `Num` mechanism above
      is the template.)
ORIGINAL PLAN (kept for reference — the targeted K13.1 above subsumes K13.1–K13.3/K13.5 for `Num`): a constraint
set threaded in `infer`, `Forall(qs, constraints, ty)`, generalize/instantiate/discharge/default, dict-passing
erase. The targeted approach reuses the existing `pendingNum`/`tcReg`/`instOf` machinery instead of adding a
`Forall` constraints field, so it touched far fewer sites and stayed green.

Designed-but-larger (not blocked): **typed handler resumes** (per-op typed resume, `specs/54`) — current
effects use a uniform `Dyn -> Comp` resume; typing the resume per op is a separate focused effort.

## K14 — string ordering (Ord/Eq for String)

- [x] **K14.1 — `=`, `<>`, `<`, `<=`, `>`, `>=` on String** (lexicographic). Probed (2026-06-28): strings had
      `strEq` but the *operators* only worked on Int/Float (`"a" < "b"` → "need Int or Float operands"); since
      ssct-hm has no module system, this — not dict-passing — was the most broadly-useful real gap. `mkCmp`
      already desugars all six comparison operators into `Lt`/`Eq`, and both route through `inferNum(_,"cmp")`,
      so a single `TyStr` branch in `inferNum` (guarded to the `"cmp"` kind, so string `+`/`-`/`*` stay
      rejected → "use ++") enables all six at once. Erase: `Eq`/`<>` reuse the kernel `seq` primitive; the
      `<`-family use a new `__strLt` IR helper (lexicographic via `slen`+`scodeAt`, recursing through
      `IrGlobal("__strLtGo")` — named-global recursion, no de Bruijn knot), injected like the eff helpers via
      a `usedStrLtCell`. Interp mirrors with a host `strLtV`. **0 kernel / 0 backend change** — `__strLt` is an
      ordinary IR global (like `__effBind`), so it runs identically on run-ir/JS/Rust. Lexicographic order
      verified incl. prefixes (`"ab" < "abc"`, `"abc" < "ab"`). conformance +5.

## K15 — list append (overload `++`)

- [x] **K15.1 — `++` on lists** = append (was String-only: `[1,2] ++ [3,4]` → "needs String operands"). Gave
      `Concat` an `id` (mirroring `Add`/`Lt`/`Eq` across desugar/freeVars/freshen/subst/containsNumOp/inline),
      so `inferConcat` can dispatch by operand type: both operands unify, then `TyStr`→String concat (`sconcat`
      prim), `TyList(e)`→list append (new `__append` IR helper), unresolved `TyVar`→**defaults to String** (so
      the old String-only behaviour is preserved exactly for ambiguous `++`). `__append` = `λxs.λys. match xs
      { Cons h t => Cons h (__append t ys) | Nil => ys }` recursing via `IrGlobal("__append")` (named-global,
      no de Bruijn knot), injected via `usedAppendCell`; interp mirrors with host `appendV`. **0 kernel / 0
      backend change** — `__append` builds plain `Cons`/`Nil` IR. `[1,2]++[3,4]`→`[1,2,3,4]`, `[]++[1]`,
      chaining, list-of-strings all green on run-ir/JS/Rust; string `++` and ambiguous-default unchanged.
      conformance +5.

## K16 — let destructuring

- [x] **K16.1 — `let (pat) = e in body`** (tuples, nested, ctor patterns). Probed (2026-06-28): this used to
      **crash** the compiler (`parseLetE` saw `(` after `let`, fell to `ParseErr`, and a downstream match had
      "no arm for ParseErr"). Now `parseLetE` dispatches a leading `(` to `parseLetDestructure`, which parses
      the parenthesised pattern with the existing `parseParenPat`, then desugars to `match e { pat => body }`
      via the existing `armOfPat` — so all the tuple/nested-pattern machinery (incl. `match`-compiler for
      non-simple sub-patterns) is reused. `let (a,b)=(3,4)`, `let (a,b,c)=…`, `let (a,(b,c))=…`, and
      destructuring a function result all work on run-ir/JS/Rust. 0 infer/erase/backend change (pure
      front-end desugar to `MatchT`). No regression: `let x=e`, `let f x=e`, `let x:T=e`, `let rec` untouched.
      conformance +5. (Other parser robustness gaps noted: `if` w/o else still Java-crashes rather than
      erroring cleanly — lower priority.)

## K17 — literal patterns (negative + float)

- [x] **K17.1 — negative-int / float / negative-float patterns.** Probed (2026-06-28): `match n { -1 => … }`
      mis-parsed (`-` became `PWild`), and `match x { 2.5 => … }` **Java-crashed** (`parseAtomPat` had no
      `TFloat` arm → "no arm for TFloat"). Fixed in `parseAtomPat`: `TFloat(s)` → `PLit(FloatLit s)`; a leading
      `-` on a numeric literal → `PLit(Sub(0, Lit n))` (int) / `PLit(FloatLit("-"++s))` (float) — reusing the
      same object-`Sub` trick as expression negative-literals (the ssc0 kernel has no infix `-`). `patStarts`
      now also admits `TFloat`/`-` so these work as constructor sub-patterns too. `litCond` gained a `FloatLit`
      arm → float patterns compile to `FEq` (float equality), not the Int-only 2-arg `Eq`; negative ints reuse
      the generic `Eq(sv, Sub …)` `_`-case. `match n { 0 => .. | -1 => .. | -5 => .. }`, `match 2.5 { 2.5 => .. }`,
      `match (0.0-2.5) { -2.5 => .. }` all green on run-ir/JS/Rust; positive int/str/bool/ctor/nested patterns
      unchanged. Pure front-end (0 backend change). conformance +5.

## K18 — list bracket patterns

- [x] **K18.1 — `[]`, `[a]`, `[a, b, …]` list patterns** in `match` (previously only `Cons`/`Nil` worked;
      `[a, b]` gave "unbound variable"). New `parseListPat`/`parseListPatElems` parse a `[ … ]` pattern and
      desugar to the nested `Cons`/`Nil` `PCon` chain (`[a,b]` → `PCon Cons [a, PCon Cons [b, PCon Nil []]]`),
      so the existing match-compiler handles length-checking and binding. Wired into `parseAtomPat` (a `[` after
      a pattern position) and `patStarts` (so list patterns nest inside ctor patterns). `[] => …`, `[a] => …`,
      `[a,b] => …` and a `Cons h t` fallthrough all compose in one match; nested element patterns
      (`[Some a, b]`) work. Pure front-end desugar (0 infer/erase/backend change). All green on run-ir/JS/Rust;
      `Cons`/`Nil` patterns, list-literal exprs, tuple patterns unchanged. conformance +5.

## K19 — record destructuring

- [x] **K19.1 — `let { f = v , … } = e in body`** (completes the destructuring story: K16 did tuples, this does
      records). Desugars to `let $r = e in let v = $r.f in … in body` — i.e. one binding per field via the
      existing type-directed `FieldGet`, so it is **order-independent** (binds by field name, not position) and
      fully type-checked, with no new pattern node or match-compiler/infer/erase change. `parseLetE` dispatches a
      leading `{` (after `let`) to `parseLetRecDestr`; `parseRecDestrFields` collects the `field = var` pairs and
      `buildRecDestr` folds them into nested `Let`s over a fresh `$rd<n>` bound to `e` (so `e` is evaluated once).
      `let {x=a, y=b} = r`, `{y=b, x=a}` (order-free), single-field, and destructuring a function's record result
      all green on run-ir/JS/Rust. No regression: record literals + `.f` access, record update, tuple
      destructure, plain `let` untouched. conformance +5.

## K20 — parse-error robustness (clean error, no crash)

- [x] **K20.1 — `ParseErr` → clean type error instead of a Java crash.** `if true then 1` (no `else`), an empty
      `let` body, etc. produced a `ParseErr` node that reached a `match` with no arm → uncaught
      `RuntimeException: match: no arm for ParseErr`. Added `case ParseErr => ErrI("parse error: incomplete or
      malformed expression …")` to `infer` (covers the type-check path) and `case ParseErr => ParseErr`
      passthrough to `desugar` (codegen path). Now any incomplete/malformed input reports a clean
      `TypeError: parse error …` on both `ssct-hm` and `ssctc-hm`. Valid programs unchanged. conformance +1.

## K21 — char literals

- [x] **K21.1 — `'a'` char literals → Int char code.** ssct-hm has no `Char` type and `charAt`/`scodeAt` already
      return Int codes, so a char literal is just its code: `'a'` = 97, `'0'` = 48, `'\n'` = 10. Pure lexer
      change — `lexFrom` gets a `'` (code 39) arm that reads one char (handling a `\`-escape via the existing
      `unEscCode`) and emits `TNum(code)`, so downstream everything treats it as an ordinary `Lit`/Int (no
      type, infer, erase, or backend change). Enables char arithmetic (`'z' - 'a'` = 25), range tests
      (`ch >= 'A'`), and comparison against `charAt` results (`charAt s i = 'a'`). All green on run-ir/JS/Rust.
      conformance +5.

## K22 — float math (prelude)

- [x] **K22.1 — `fabs` / `fmin` / `fmax` / `fsign`** added to the prelude (alongside the existing `fsqrt`/`fneg`).
      All are definable from the kernel float prims that exist (`flt`, `fneg`): `fabs = if flt x 0 then fneg x
      else x`, `fmin`/`fmax` via `flt`, `fsign` via two `flt`s. Pure prelude (ssct-hm source) addition — auto-
      injected only when used, no infer/erase/backend change — so they run identically on run-ir/JS/Rust.
      **`floor`/`ceil`/`round` are NOT added: they need a float→int truncation primitive the frozen ssc0 kernel
      doesn't expose** (kernel float prims are only `add/sub/mul/div/lt/eq/neg/sqrt`; `ToFloat` is Int→Float
      only). conformance +5.

## K23 — record patterns in match arms

- [x] **K23.1 — `match r { {f = subpat, …} => body }`** (records in match, completing record patterns alongside
      K19's `let`-destructure). New `PRec(fields)` pattern node parsed by `parseRecPat`/`parseRecPatFields`
      (`{` wired into `parseAtomPat` + `patStarts` so it also nests, e.g. `{x = Some n}`). Records are
      single-constructor (always match), so `compilePat`'s new `PRec` case binds each field via the
      type-directed `FieldGet` (`compileRecFields`) — **order-independent** (by name), supports nested
      sub-patterns (a `PVar` binds directly; anything else binds to a fresh var then `compilePat`s the
      sub-pattern), and `compileSubs` also gets a `PRec` arm so a record pattern can nest inside a constructor
      pattern. `armOfPat` routes `PRec` to `PatArm` (→ the match compiler); `patVars` gets a `PRec` arm.
      `{x=a, y=b}`, `{y=b, x=a}` (order-free), `{x = Some n}` (nested) all green on run-ir/JS/Rust. **Shared
      pre-existing limitation** (not new): matching a record pattern against a *polymorphic function parameter*
      fails with "field access on a non-record" — exactly like `.f` access and `let`-destructure on a fn param,
      since HM here doesn't infer a record type from field access (would need row polymorphism). The common
      concrete-record-scrutinee case works. No regression: ctor/tuple/list/literal patterns unchanged.
      conformance +5.

## K24 — prelude combinators + utilities

- [x] **K24.1 — `compose` / `flip` / `min` / `max` / `elem` / `notElem` / `product` / `last` / `null` / `join`.**
      Probed (2026-06-29): all were unbound. All are one-liners definable in ssct-hm, added to the auto-injected
      prelude (no infer/erase/backend change). `min`/`max` use overloaded `<` so they are polymorphic via
      inlining (work on Int/Float/String — `min "banana" "apple"` = "apple"). `elem`/`notElem` use overloaded
      `=`. `join` concatenates a `[String]` with a separator via `++`. ⚠️ GOTCHA: the prelude injects so that a
      *depender* must come BEFORE its dependency in the list (like `concatMap` before `append`) — `notElem`
      (uses `elem`) had to be listed before `elem`, else "unbound variable". Also: don't reuse an existing
      example filename (`hm-prelude2.hm` already existed — conformance caught the clobber; used
      `hm-preludecombi.hm`). All green on run-ir/JS/Rust. conformance +5.

## K25 — as-patterns

- [x] **K25.1 — `name@pat`** binds the whole matched value to `name` AND destructures via `pat`. New `PAs(name,
      sub)` pattern node. `parseFullPat` handles a lowercase name followed by `@` (sub = a full pattern, e.g.
      `all@(Cons h t)`, `n@5`, `xs@Nil`); `parseAtomPat` also handles `@` (sub = an atom) so an as-pattern works
      as a *constructor sub-pattern* (`Some xs@(Cons h t)`). `compilePat`'s `PAs` case is `Let(name, scrut,
      compilePat(scrut, sub, …))` — bind then match; `compileSubs` gets a `PAs` arm (nested), `armOfPat` routes
      `PAs`→`PatArm`, `patVars` gets a `PAs` arm. Real `dedup` using `Cons a rest@(Cons b t)` runs on all 3
      backends. No regression. conformance +5. (Note: `match <scalar> { x => … }` — a single var-only arm on a
      scalar — still crashes at run-ir; that's a PRE-EXISTING issue, unrelated to as-patterns.)

## K26 — operator sections

- [x] **K26.1 — right operator sections `(op e)`** = `fun x => x op e`, for point-free `map`/`filter`:
      `(+ 1)`, `(* 2)`, `(/ 2)`, `(< 5)`, `(>= 0)`, `(= 0)`, `(<> 3)`, `(++ "!")`. `parseParenOrTuple` now peeks
      the first token after `(`: if it's a section op (`isSectionOp` = `+`/`*`/`/`/`++` ∪ the comparison ops),
      it parses `op e )` into `Lam("$sec", mkSectionOp(op, Var "$sec", e))`; otherwise the original paren/tuple/
      ascription parser (`parseParenBody`) runs. `-` is deliberately excluded (it's negation — `(-5)` stays a
      negative literal). `map (+ 1) [1,2,3]`→`[2,3,4]`, `filter (< 3) …`, `(++ "!") "hi"`→`"hi!"` all green on
      run-ir/JS/Rust. No regression: `(e)`, `(a, b)`, `(e : T)`, negation, binary subtraction unchanged. Pure
      front-end desugar (0 backend change). conformance +5. (Left sections `(e op)` not done — rarer, and the
      `-` ambiguity makes them fiddly; use `fun x => e op x`.)

## K27 — string split / words / lines

- [x] **K27.1 — `split` / `words` / `lines`** (prelude). `split c s` splits string `s` at every occurrence of
      separator **char code** `c` (so `split ',' s` works thanks to K21 char literals = Int codes, or `split 44
      s`); returns `[String]`. Implemented in ssct-hm via a `let rec go` that walks the string with `charAt` +
      `substr` + `strLen`. `words = split 32` (space), `lines = split 10` (newline). Roundtrips with `join`
      (`join "," (split ',' "x,y,z")` = `"x,y,z"`). `words`/`lines` are listed BEFORE `split` (depender-before-
      dependency). Pure prelude — auto-injected when used, 0 backend change, all green on run-ir/JS/Rust.
      conformance +5. (Basic splitter: consecutive separators yield empty fields, like a naive `split`.)
