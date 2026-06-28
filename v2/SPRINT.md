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

BLOCKED (not doable here): **ir → WASM** — no `rustup`/`wasmtime`/`wabt` toolchain in this environment
(only node's WebAssembly API). Documented in K4; revisit when the toolchain is available.
