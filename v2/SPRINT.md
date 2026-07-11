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
- [x] `do`-notation sugar + typed effect rows — **DONE in the ssct-hm surface** (`doE { x <- m ; … }`
      + `Comp {Eff} a` row syntax, K10/K11; row INFERENCE incl. effect-polymorphic HOFs in K41). The original
      lower `ssct` annotated layer is superseded by ssct-hm for these.

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
## Backlog

- [~] bare-`#prim` η-expansion **DONE (K38)** + `v2-bin` compact binary ir **DONE (K40)**. Only **Array-env
      for speed** (a VM perf optimization, not a feature gap) remains here.
- [x] `mathx.*` transcendental floats — **DONE in ssct-hm (K33)**: `exp`/`ln`/`sin`/`cos`/`tan`/
      `pow`/`sqrt`/`pi` as a pure prelude (Taylor/Maclaurin series over `+ - * /` + the kernel's
      `fsqrt`/`fneg`; `ln` range-reduced by `e` so it's accurate for all `x>0`). 0 kernel change,
      0 backend change. Bit-identical across run-ir/JS/Rust (all IEEE-754 doubles, same op order).
      structural map keys **DONE (K37, lib/mapx.ssc0)**; `hash.sha256` **DONE (K36, lib/sha256.ssc0)**.
      (`10-core-ir.md §8`.)
- [x] K3 non-WASM breadth **DONE through K45**: stdlib, HM typed surface, effects/actors/async
      as libraries, and VM/JS/native-Rust backends as ssc-compiled programs.
- [x] **backend: ir → WASM** — ✅ DONE 2026-07-05: `rustup` is now present;
      `wasm32-wasip1` target installed, Rust backend reused as planned. `v2/ssc0-wasm`
      launcher + `scripts/run-wasi.mjs` (Node built-in WASI host — no wasmtime needed).
      quicksort byte-identical to VM; tco = 1e6 tail calls constant-stack; Mira programs
      work via the same target. Toolchain-gated checks in `conformance/check.sh`.

## K5 — Mira (ssct-hm): a Hindley-Milner typed language (DONE 2026-06-27)

- [x] **ssct-hm** — a complete HM-inferred typed FP language in ssc0 (`lib/ssct-hm*.ssc0`):
      Algorithm-W inference + let-polymorphism; Int/Bool/String; polymorphic lists `[a]` with
      literals; **user `data` types + pattern matching** (`match { | }`); full arith/cmp/string
      ops. Source text → infer → interpret OR erase → Core IR → **VM / JS / native Rust**.
      Showcases: factorial, map, quicksort (dups), a typed expression interpreter — all compile
      to native code. Spec `41-mira.md`. 161 conformance checks. Kernel +0 (still 913).

## K6 — Mira (ssct-hm): feature parity backlog (plan)

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

## K7 — Typed algebraic effects in the Mira surface

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

- [x] **DOC/CONF** — `specs/50-effects.md` (typed-surface section) + `specs/41-mira.md`; conformance for
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
- **WASM backend** — ✅ UNBLOCKED + SHIPPED 2026-07-05 (see K4): `rustup` appeared in the
  environment; wasm32-wasip1 + Rust-backend reuse + Node WASI host. Nothing remains blocked here.

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
- [x] **K13.4 — extend dict-passing to `Ord` (Int / Float / String) SHIPPED (2026-06-29)**. Two parts:
      (1) **Detection fix** — `firstPendingIn` checked `isPending` on the *quantified* vars, but a `<`-only
      dict-fn records its pending var on a var that unification later folds into a *different* representative,
      so it was missed (the `+` cases passed by luck). New `dictVarOf(s, pend, qs)` maps each pending var through
      the substitution and returns the representative that is in `qs`; `unpendRep` removes (by representative) so
      the top-level default leaves it alone. This alone fixed `<`-using dict-fns at Float.
      (2) **String/Ord helpers** — `tagOfType`/`tagOfArg` gained `String`; the comparison helpers became 3-way:
      `__nlt` = Float→`f.lt` / String→`__strLt` / Int→`i.lt`, and a new `__neq` = Float→`f.eq` / String→`seq` /
      Int→`i.eq` (Eq erase now dict-dispatches too). `__strLt` is auto-injected whenever the dict helpers are.
      A single recursive `maxOf` (using `<`) now runs at Int, Float AND String — `Triple(5, 9.5, "z")` — and a
      `countEq` (using `=`) at Int and String, all on run-ir/JS/Rust. conformance +5.
- [x] **K13.5 — USER-CLASS dict-passing SHIPPED (2026-06-29)**. A recursive function using a USER typeclass
      `method` on a still-polymorphic receiver now works (even at one type — it failed before, since
      `methodImplOf` can't resolve a polymorphic receiver). The **dict is the instance impl itself** (a
      function), which sidesteps pre-inferring impls. INFER: when the Num/Ord path doesn't fire, `methodDictOf`
      scans `pendingMethods` for a deferred method whose receiver var (after subst) is quantified → mark `f` a
      method-dict-fn (`methodDictReg: name → (method, argPos)`) and record the specific method-call ids that
      dispatch via the dict (`mdictCallReg`). ERASE: the lam gets a leading `$mdict` param; a method call whose
      id is in `mdictCallReg` becomes `$mdict arg`; at a call site (`eraseMethodDictCall`) the dict is the
      enclosing `$mdict` (recursive) or the instance impl `lookupInstance(method, tagOfArg(receiver))`.
      `tagOfArg` gained `Bool` and **user-ADT** support (`ConApp`/`Var` → `conTyName` via `conReg`’s `ConSig`).
      A recursive `acc` over a user method `tone` runs at user types `Color` AND `Shape` (and at Int/Bool/String)
      on run-ir/JS/Rust — `(acc Red 2, acc Dot 3)`. **Limitations:** one method dict per binding; impls that
      themselves use unresolved overloaded ops need pre-inference (no-ops / match / concrete-op impls are fine);
      a receiver whose type isn't readable from the argument's form (a bare polymorphic `Var` in a monomorphic
      external call) isn't supported. conformance +5. **Qualified types: closed (inlining) + non-closed
      dict-passing for Num, Ord, and user classes — COMPLETE.**
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
      front-end desugar (0 backend change). conformance +5.

- [x] **K26.2 — left operator sections `(e op)`** = `fun x => e op x`, the complement to K26.1:
      `(10 -)`, `(100 /)`, `(2 *)`, `(5 <)`, `("hi" ++)`. The `-` ambiguity that excludes `-` from *right*
      sections doesn't apply here — in a left section `-` *follows* the operand, so `(10 -)` is unambiguously
      `fun x => 10 - x` (never a negative literal). Parse strategy = **try-then-fallback** (no lookahead needed):
      `tryLeftSection` runs `parseApp` on the paren body to grab an application-level left operand `e`, then
      `isLeftSection` checks the remainder is exactly `<op> )` (a `isLeftSectionOp` punct = `-` ∪ `isSectionOp`,
      then `)`); on a hit it builds `Lam("$lsec", mkSectionOp(op, e, Var "$lsec"))` and drops the `)`, otherwise
      it re-parses from the *original* tokens via `parseParenBody`. `mkSectionOp` gains a `-`→`Sub` case (reached
      only from left sections; right sections never pass `-`). `parseParenOrTuple`'s non-right-section branches
      now call `tryLeftSection`. No false positives: `(a < b)`, `(a, b)`, `(a : T)`, `(3 + 4)`, `(- 5)` negation,
      binary `10 - 3`, nested `(2 * (3 + 4))` all unchanged (verified — the `parseApp` probe either fails the
      `isLeftSection` shape or is discarded by the fallback re-parse). `map (100 -) [10,20,30]`→`[90,80,70]`,
      `(20 /) 4`→`5`, `filter (10 <) …` all green on run-ir/JS/Rust. Pure front-end desugar (0 backend change).
      conformance +5.

## K27 — string split / words / lines

- [x] **K27.1 — `split` / `words` / `lines`** (prelude). `split c s` splits string `s` at every occurrence of
      separator **char code** `c` (so `split ',' s` works thanks to K21 char literals = Int codes, or `split 44
      s`); returns `[String]`. Implemented in ssct-hm via a `let rec go` that walks the string with `charAt` +
      `substr` + `strLen`. `words = split 32` (space), `lines = split 10` (newline). Roundtrips with `join`
      (`join "," (split ',' "x,y,z")` = `"x,y,z"`). `words`/`lines` are listed BEFORE `split` (depender-before-
      dependency). Pure prelude — auto-injected when used, 0 backend change, all green on run-ir/JS/Rust.
      conformance +5. (Basic splitter: consecutive separators yield empty fields, like a naive `split`.)

## K28 — single var-arm match (robustness)

- [x] **K28.1 — `match scrut { x => body }` no longer crashes.** A match whose only arm is a variable
      catch-all desugared (via `mkMatch`) to `match x { _ => body }` — a `match` with NO constructor arms — and
      the VM crashed inspecting a scalar scrutinee's (nonexistent) tag. `mkMatch` now special-cases a single
      var arm (`dropLast(arms)` is `Nil`) to `Let(x, scrut, body)` — i.e. exactly `let x = scrut in body`, no
      match node. `match 7 { x => x+1 }` ⇒ 8 on all 3 backends; multi-arm matches with a trailing var arm,
      ctor/literal/list/tuple/as-patterns all unchanged. (⚠️ ssc0 kernel pattern parser rejects a bare
      lowercase var pattern like `case others =>` — use `case _ =>`.) conformance +5.

## K29 — prelude, batch 2

- [x] **K29.1 — `takeWhile` / `dropWhile` / `span` / `partition` / `scanl` / `lookup` / `maximum` / `minimum` /
      `count` / `nub` / `enumerate`** added to the prelude (all were unbound). One-liner ssct-hm defs; inserted
      at the TOP of the `prelude` list so they can reference the existing combinators (depender-before-dependency
      injection rule). `maximum`/`minimum` (over `<`) and `lookup`/`nub` (over `=`) are polymorphic and ride the
      Num/Ord dict-passing. `span`/`partition` return a tuple; `enumerate` = `zip (range 0 (length xs)) xs`;
      `lookup` returns an `Option`. All green on run-ir/JS/Rust. conformance +5.

## K30 — string functions, batch 2

- [x] **K30.1 — `startsWith` / `endsWith` / `strContains` / `trim`** (prelude). All built from the existing
      `substr` / `charAt` / `strLen` intrinsics + string `=` (K14) — no new kernel/IR primitive, so they run on
      run-ir/JS/Rust unchanged. `trim` walks in from both ends skipping spaces (code 32) then `substr`s;
      `strContains` is a sliding `substr =` search. `trim "  hi  "` ⇒ `"hi"`, `startsWith "he" "hello"` ⇒ true.
      (⚠️ `toUpper`/`toLower` NOT added — they need to rebuild a string from char codes via `sfromCodes`, which
      the JS/Rust gen `genPrim` doesn't expose as an IR prim; would need backend support.) conformance +5.

## K31 — empty match (robustness)

- [x] **K31.1 — `match s { }` (no arms) → clean error, not a crash.** `parseArms` always tried to parse ≥1 arm,
      choking on the closing `}` → a `ParseErr` that crashed downstream. Now `parseArms` returns no arms when the
      next token is `}`, so `inferArms` reaches its existing `Nil`→`ErrI("empty match")` branch and reports a
      clean `TypeError: empty match`. Non-empty matches (ctor/literal/var/guard/as/record/tuple) unchanged.
      conformance +1.

## K32 — string building (fromCodes → toUpper/toLower/chr)

- [x] **K32.1 — `fromCodes : [Int] -> String` intrinsic + `chr` / `toUpper` / `toLower`.** This was the one
      true *capability* gap (the SPRINT K30 note flagged toUpper/toLower as backend-blocked). The kernel run-ir
      already has the `sfromCodes` IR prim, so the work was: a new `FromCodes(e)` node (recognized as a 1-arg
      intrinsic in `dsApp`, like `strLen`; infer `[Int]->String`; erase → `IrPrim("sfromCodes", …)`; interp via
      `hostCodesToKernel` + `#sfromCodes`), plus **backend support** — JS `genPrim` emits an inline IIFE that
      walks the `{t,f}` Cons-list `String.fromCharCode`-ing each, and the Rust `genPrim`/preamble gain an
      `sfromcodes(&V)` helper that walks the `V::D("Cons", …)` list building a `String`. Prelude then defines
      `chr c = fromCodes (cons c nil)` and `toUpper`/`toLower` (walk the string, shift `a..z`/`A..Z` codes,
      `fromCodes` the result). `toUpper "ab3X!z"` ⇒ `"AB3X!Z"`, `toLower (toUpper "Hi") ` round-trips; all green
      on run-ir/JS/Rust. conformance +5. (String-building is now general — any char-list transform works.)

## K33 — transcendental floats (mathx: exp / ln / sin / cos / tan / pow / sqrt / pi)

- [x] **K33.1 — `exp` / `ln` / `sin` / `cos` / `tan` / `pow` / `sqrt` / `pi`.** The kernel exposes only
      `fsqrt`/`fneg` as float prims; `fabs`/`fmin`/`fmax`/`fsign` were already prelude-built. This closes the
      `mathx.*` Backlog open — and entirely as **pure ssct-hm prelude source** (0 kernel change, 0 backend
      change): each transcendental is a finite Taylor/Maclaurin series over `+ - * /` plus `fneg`, driven by a
      `let rec go` term-accumulator (`exp` 30 terms, `sin`/`cos` 20, `ln` 40). `ln` is **range-reduced by `e`**
      first (`let rec red` divides/multiplies `y` by `e` into `[1/e, e]`, accumulating the count `k`, then runs
      the `atanh`-series `2·Σ t^(2n+1)/(2n+1)` which converges fast there) — so it's accurate for *all* `x>0`,
      not just `x≈1` (the naive series diverges at `ln (exp 5)≈148`; range reduction fixed `1011111`→`1111111`).
      `pow x y = exp (y · ln x)`, `tan = sin / cos`, `sqrt = fsqrt`, `pi` a constant. Each is monomorphic
      `Float -> Float` (the float literals + `flt` anchor the type — no numeric-overload ambiguity). Prelude
      ordering is depender-before-dependency (`pow` above `exp`/`ln`, `tan` above `sin`/`cos`).
      **Cross-backend: bit-identical.** run-ir (Scala `Double`), JS (`Number`), Rust (`f64`) are all IEEE-754
      doubles and the op order is fixed by the shared source, so `exp 1.0` = `2.7182818284590455` *exactly* on all
      three (conformance asserts this directly). The mathx example sums seven `near`-comparisons → `1111111` on
      run-ir/JS/Rust; extra checks cover `ln 1000`, `ln 0.01`, `pow 3 4`. conformance +5.

## K34 — float rounding (floor / ceil / round / trunc / rint)

- [x] **K34.1 — `floor` / `ceil` / `round` / `trunc` / `rint`.** The K6 float-math note (and the conformance
      comment) claimed *"floor/ceil need a kernel prim"* — **wrong** (another "too-hard" misjudgment): they need
      no float→int conversion at all. `rint` (round to nearest integer, ties-to-even) is the classic IEEE trick
      `(x + 1.5·2^52) - 1.5·2^52` where `1.5·2^52 = 6755399441055744.0`. Adding the magic number forces `x` into
      `[2^52, 2^53)` where the ULP is exactly `1.0`, so the IEEE round-to-nearest-even of the addition lands on
      the nearest integer; subtracting it back recovers `rint x` (valid for `|x| < 2^51`). Then `floor x = let r =
      rint x in if x < r then r-1 else r`, `ceil` symmetric, `round x = floor (x + 0.5)` (half up toward +∞),
      `trunc x = if x<0 then ceil x else floor x`. **First magic constant `2^52` alone is wrong for negatives**
      (`x+2^52 < 2^52` drops into the ULP=0.5 band → off by 0.5); `1.5·2^52` keeps it in the ULP=1.0 band for both
      signs. All pure prelude (0 kernel/backend change), monomorphic `Float -> Float`. Round-to-nearest-even is
      the IEEE default on run-ir (`Double`), JS (`Number`), Rust (`f64`), so results are **identical across all 3
      backends** (`rint 2.5 = 2.0`, `rint 3.5 = 4.0` — banker's rounding everywhere). Example sums seven
      near-checks → `1111111` on all backends. conformance +4.

## K35 — math library complete (inverse trig / hyperbolic / log bases / cbrt / hypot)

- [x] **K35.1 — `atan`/`asin`/`acos`, `sinh`/`cosh`/`tanh`, `cbrt`, `hypot`, `log2`/`log10`/`logBase`; `exp`
      hardened.** Rounds the mathx float library out to typical-stdlib parity, all pure prelude (0 kernel/backend
      change), all via the K33 series pattern. **`atan`** is the only non-trivial one: the Maclaurin series
      `x - x³/3 + …` converges hopelessly near `|x|=1` (Leibniz), so it's preceded by a **double half-angle
      reduction** `atan x = 4·atanSeries(r2)` where `r = a/(1+√(1+a²))` applied twice — shrinks any argument
      (even `atan 10`) into the fast-converging zone. `asin x = atan(x/√(1-x²))`, `acos = π/2 - asin`. Hyperbolics
      are one-liners off `exp` (`sinh = (eˣ-e⁻ˣ)/2`, etc.; `tanh = sinh/cosh` is even self-correcting for large
      `x` since `e⁻ˣ→0`). `cbrt` = sign-aware `exp(ln|x|/3)`, `hypot = √(a²+b²)`, `log2`/`log10` divide `ln x` by
      the constant, `logBase b x = ln x / ln b`. **`exp` is now range-reduced by halving** (`exp x = exp(x/2)²`
      recursing until `|x|≤1`, then the 30-term series) so it (and `sinh`/`cosh`/`pow`) is accurate for large `|x|`
      too — and `exp 1.0` stays *bit-identical* (`|1.0|>1.0` is false → still the plain series). All deterministic
      across run-ir/JS/Rust (IEEE-754, fixed op order); a 14-check example sums to `14` identically on all three.
      conformance +4. **The float-math story is now complete: arithmetic + comparison + abs/sign/min/max +
      rounding + sqrt/cbrt + exp/ln/log-bases + full trig + inverse trig + hyperbolic + pi/hypot.**

## K36+ — clear the remaining backlog (everything except WASM) — user mandate 2026-06-29

User: "бери все, занеси в спринт, и делай … все кроме wasm". All non-WASM Backlog/Remaining items, planned as slices:

- [x] **K36 — `hash.sha256` DONE** (conformance +1). From-scratch SHA-256 in raw ssc0 (`lib/sha256.ssc0`,
      ~70 defs) over the kernel's bitwise (`#i.and/or/xor/shl/ushr/not`) + byte (`#str->utf8`/`#blen`/`#bget`) +
      mutable-array (`#arr.new/push/get/set/len`) prims, masked to 32 bits (`#i.and(x, 4294967295)`); imperative
      sieve-style threading (`let u = #arr.set(…) in …`). `rotr`/`shr`/`Ch`/`Maj`/`Σ`/`σ` helpers, 64-word
      schedule, 64-round compression, padded big-endian length, hex via `#sfromCodes`. **VM-only (run-ir) BY
      DESIGN** — JS bitwise is 32-bit-signed vs run-ir/Rust 64-bit, so raw bitwise isn't cross-backend-sound
      (same reason `#arr`/`#map` programs are VM-only). **Vector-gated**: `sha256Hex` of `""`/`"abc"`/`"hello"`/
      an 85-byte multi-block input all match the standard vectors (self-check returns 4). `sha256Hex "abc"` =
      `ba7816bf…20015ad`. Kernel +0 (still 913).
- [x] **K37 — structural map keys DONE** (conformance +1). `lib/mapx.ssc0` = a structural-equality `valEq` +
      an immutable assoc-list map (`mapxInsert/Lookup/Has/Remove/Keys/Size/FromList`) keyed by it, so ANY value —
      ints, strings, tuples, ADTs, nested — is a valid key. **`valEq` trick:** the frozen kernel has no generic
      equality prim, and `#tagOf` errors on scalars (no typeof to discriminate scalar-vs-Data), BUT the mutable
      `#map` is a Scala `HashMap[Value,Value]` that compares keys STRUCTURALLY — so
      `valEq a b = let m=#map.new() in (#map.put(m,a,0); #map.has(m,b))` uses a one-shot map as an equality
      ORACLE (true iff `a` structurally equals `b`). VM-only (like all `#map`/`#arr` programs). Demo:
      tuple/triple/int/nested-ADT keys looked up by FRESHLY-BUILT equal keys (structural, not identity) →
      `pair|triple|int|nested|?`; overwriting a key keeps `size=4` (structural dedup). Kernel +0.
- [x] **K38 — bare-`#prim` η-expansion DONE** (conformance +1). A bare `#op` used as a *value* (not applied)
      used to error ("wrap it"). The self-hosted **`lib/ssc0c.ssc0`** now lowers it to `(x0..xn-1) => #op(x0..xn-1)`
      via a `primArity` table (`isArity0`/`isArity1`/`isArity3` sets over the kernel prims, default 2 = the binary
      majority; max arity 3 → fixed param names `$e0..$e2`, no string-building). So primitives are first-class:
      `map #i.neg [1,2,3]` → `[-1,-2,-3]`, `foldl #i.add 0 [1,2,3,4]` → `10`. **Kept the frozen Scala bootstrap
      front untouched** (still 913, still rejects bare prims by design) — ssc0c is the real compiler, so the demo
      compiles via `bin/ssc0c.ssc0` → ir → run-ir. The self-hosting fixpoint + differential checks still pass
      (the new η-code itself uses no bare prims, so the Scala front and ssc0c agree on it). Kernel +0.
- [x] **K39 — typed handler resumes DONE** (bounded, sound; conformance +5). The general `handle` op clause was
      typed `String -> Dyn -> (Dyn -> Comp r b) -> Comp r b` (op arg AND resume input both untyped `Dyn`). Now,
      for a **single-op TYPED effect** `effect L { op : A -> R }`, `singleOpSigOf(L)` finds the lone op's
      signature and the clause is typed `String -> A -> (R -> Comp r b) -> Comp r b` — so the handler's op-arg is
      `A` and its **resume `k` is `R -> Comp r b`** (typed!), with no `Dyn` ascriptions. Multi-op / untyped
      effects keep the Dyn fallback (one op-clause lambda can't type resumes for several differently-typed ops —
      that's the per-op-syntax research case, left as the boundary). **Purely static** (erase/runtime unchanged →
      all 3 backends): `effect Ask { ask : Int -> String }` + a handler using `(a + 1)` (a:Int) and
      `k (showInt …)` (k:String→) type-checks → `2` on run-ir/JS/Rust; resuming with the wrong type
      (`k (a + 1)` where k expects String) is a clean `TypeError`. SAFE: `TyDyn` unifies with anything and every
      existing effect test uses untyped/multi-op decls, so none is affected. Kernel +0.
- [x] **K40 — `v2-bin` compact binary IR DONE** (conformance +2). `lib/irbin.ssc0` = a bidirectional binary
      codec for the Core-IR Data tree: `binEncode : ir -> #arr of bytes`, `binDecode : #arr -> ir`. Each of the
      14 node tags + 6 const tags + `Cons`/`Nil` + `Some`/`None` gets a 1-byte tag; integers are **LEB128
      varints** (unsigned for tags/arities/locals/lengths, **zig-zag** for signed int literals) so small values
      are 1 byte (compact); strings = uvarint char-count + one uvarint per UTF-16 code unit (`#scodeAt`/
      `#sfromCodes` — no UTF-8 juggling); floats via `#f->str`/`#str->f`; BigInt via `#big->i`/`#i->big`. The
      decoder threads a position (`Pair(node, pos')`). **Round-trip invariant** `#coreir.encode(binDecode(
      binEncode(ir))) == #coreir.encode(ir)` holds on a tree exercising EVERY node type; the binary is **108
      bytes vs 334 S-expr chars** (~3× smaller). Plus an EXECUTABLE round-trip: a runnable program's IR →
      binary → back to S-expr → `run-ir` → `42` (the format preserves executable semantics). VM-only (`#arr`);
      the kernel still reads S-expr — bin is a tooling layer. Kernel +0. GOTCHAS: raw ssc0 has no `-` literal
      (use `#i.neg(1)`); no `;` sequencing in arm bodies (use `let u = … in`); `#str->f`/`#str->i` return
      `Option` (unwrap); emit raw via `#io.print` (not a bare String, which prints quoted).
- [x] **K41 — effect-row inference DONE** (conformance +5). On investigation this was already substantially
      **implemented by K10/K11** and is more complete than the "research/deferred" note suggested — so this slice
      verifies it at its hardest point and documents it. The HM layer has full **Rémy/scoped-label row
      unification** (`rowUnify`/`rowRewrite`/`rowFresh`), computation types `Comp ρ a`, row vars generalized at
      `let` (`freeTy` counts `TyRowVar`), and `runE` demanding `TyRowEmpty`. Concretely it ALREADY infers
      (no annotation): `getE : Comp {State | e0} Dyn` (polymorphic tail); `runE getE` → "effect not handled:
      State"; two effects track `{State, Log}` with partial handling rejected; AND — the hardest case —
      **effect-POLYMORPHIC higher-order functions**: `traverseE` infers `(a -> Comp e b) -> [a] -> Comp e [b]`,
      the row var `e` threading from the callback through the whole traversal. The new `hm-eff-traverse.hm`
      runs a State-performing traversal (running prefix-sum) → `([1,3,6], 6)` identically on run-ir/JS/Rust, and
      the type-level check asserts `traverseE`'s principal type carries the propagated row var. **Boundary**
      (genuinely the research frontier, not shipped): nothing blocking found at this level — what remains is only
      exotica like first-class effect-row abstraction beyond `let`-polymorphism. Kernel +0.

## K42+ — K3 breadth roadmap (stdlib + showcases) — user mandate 2026-06-29

User: "Сделай K3 breadth roadmap". The actionable breadth is stdlib + real showcase programs (the backends —
JS/Rust as ssc0 programs — are done; WASM toolchain-blocked; JVM = the VM itself). Slices:

- [x] **K42 — tuple types in ADT field positions DONE** (conformance +4). `data T = C (A, B)` used to fail
      ("unbound variable"): `parseFieldType`'s `(` case ran `parseFnType` + `drop1` (closing paren), handling
      `(a -> b)` / `(F a)` but treating a comma as garbage. New `parseParenType` parses `T (',' T)*  ')'` and
      builds `TyCon("Pair", [t1,t2])` / `TyCon("Triple", [t1,t2,t3])` (matching value-tuple desugaring), falling
      back to the bare parenthesized type when there's no comma — so single fn-type fields (`Box (Int -> Int)`)
      are unchanged. `data Rec = Rec (String, Int) (Int, Int, Int)` → Pair + Triple fields, 5+1+2+3 = 11 on
      run-ir/JS/Rust; nested ADTs + existing tuple tests unaffected. Unblocks idiomatic ADTs incl. JSON's
      `[(String, Json)]`. Kernel +0.
- [x] **K43 — JSON library showcase DONE** (conformance +4). `examples/hm-json.hm`: `data Json = JNull |
      JBool Bool | JNum Int | JStr String | JArr [Json] | JObj [(String, Json)]` (uses K42 tuple fields) + a
      recursive **serializer** `showJson` (compact) + a full **recursive-descent parser** (mutual recursion
      `parseValue`/`parseArr`/`parseObj`, char-code dispatch via `charAt`/`strLen`, whitespace skipping, strings,
      signed numbers, bool/null) + accessors (`lookupJ`/`numOf`). Roundtrips a whitespace-formatted
      `{ "name": "ada", … "neg": -7 }` → compact `{"name":"ada",…,"neg":-7}` (len 72), idempotently, extracting
      `age`=36. Encoded as Int **3610072** (= 36·1e5 + idempotent·1e4 + 72) — Int dodges the cross-backend
      string-DISPLAY divergence (run-ir shows inner quotes raw, JS/Rust escape; value identical). Green on
      run-ir/JS/native-Rust. Big program → VM-interpreted typechecker needs `-Xss512m` (like the ssc0c fixpoint).
      GOTCHAS: ssct-hm equality is `=` (not `==`); comments `//`; `\"` escapes work.
- [x] **K44 — Either / Result combinators DONE** (conformance +4). 8 prelude functions over the built-in
      `Left`/`Right`: `mapRight`/`mapLeft` (map one side), `either` (eliminate), `isLeft`/`isRight`,
      `fromRight`/`fromLeft` (with default), `partitionEithers : [Either a b] -> ([a], [b])` (via `foldr`). All
      properly polymorphic (`mapRight : (b -> c) -> Either a b -> Either a c`). Error-handling breadth alongside
      the Option combinators. Example exercises all 8 -> `143` on run-ir/JS/Rust. Kernel +0.
- [x] **K45 — `lib/set.ssc0` structural Set DONE** (conformance +1). A set keyed by any value (int/str/tuple/
      ADT/nested) over the K37 `#map` equality oracle: `setEmpty`/`setInsert`/`setMember`/`setFromList`/
      `setToList`/`setSize`/`setUnion`/`setInter`/`setDiff`/`setSubset`. VM-only (like `#map`/`#arr`). Demo:
      `{1,2,3}` (deduped from `[1,2,3,2,1]`) vs `{2,3,4}` → ∪=4, ∩=2, ∖=1, member ✓; plus structural tuple dedup
      (`{(1,2),(1,2),(3,4)}` → size 2). Result `234211`. Kernel +0.

- [x] **K46 — reconcile stale v2 status docs + async/actor breadth DONE** (claim:
      `v2-k46-async-actors-roadmap`). Reconciled `v2/ROADMAP.md`, `v2/README.md`,
      `specs/10-core-ir.md`, and `specs/60-backend-js.md` with K45 reality. Added
      `specs/56-async-actors-breadth.md`, then shipped `runAsync` in `lib/async.ssc0`:
      futures/promises (`future`/`await`), buffered integer channels (`send`/`recv`), and
      mailbox aliases (`mailboxSend`/`mailboxReceive`) on the existing `Comp` effect model.
      Examples: `async-future`, `async-channel`, `async-channel-buffer`, `async-mailbox`;
      all run on VM/JS/Rust via `conformance/check.sh`. Kernel +0. Gotcha: direct `yield =
      Op(...)` avoids eager top-level value ordering issues in generated JS; JS/Rust generation for
      the richer raw scheduler uses `java -Xss512m -jar` like the JSON showcase.

- [x] **K48 — Multi-op typed handler resumes DONE** (2026-06-29; spec:
      `specs/57-multi-op-handler-resumes.md`). Added `handleM "L" m { | op1 a k => b1 |
      op2 a k => b2 } retf`, a total per-operation handler form for declared effect ops. Each
      arm's arg/resume comes from that op's signature (`ask`: `k : Int -> Comp`; `tell`:
      `k : String -> Comp`), and type checking rejects missing, duplicate, unknown, or foreign-label
      arms so the generated dispatcher's fallback is unreachable. Erases to existing `__effHandle`;
      no kernel/backend change. Added `examples/hm-eff-multiop.hm` and conformance coverage for HM
      type, run-ir, JS, Rust, row composition with `Log`, wrong-resume, missing-arm, foreign-arm, and
      duplicate-arm negatives. Targeted verification passed via launchers and `/tmp/ssc-conformance.jar`:
      `"Int"`, VM/JS/Rust `42`, negatives as `TypeError`. Full `conformance/check.sh` exposed an
      unrelated intermittent empty-output/rustc flake, queued as K49 below.

- [x] **K49 — full conformance intermittent empty-output flake DONE** — `./conformance/check.sh`
      twice produced a contiguous block of unrelated `got []` failures after an unrelated Rust
      backend `(rustc err)` while direct reruns of the first failing examples passed. Observed
      2026-06-29 while testing K48: first run failed around `hm-method-self`/mutual/quad; second
      run failed around `hm-eff-handle` through K48 happy-path checks, then recovered. Targeted K48
      checks via both launchers and the assembled `/tmp/ssc-conformance.jar` passed (`"Int"`,
      VM/JS/Rust `42`, negatives as TypeError). Likely harness/tooling flake, not a feature
      regression. Done when `check.sh` captures per-command stderr/logs (especially Java/rustc),
      avoids opaque empty stdout failures, and a full run is stable across two consecutive runs.
      Closed 2026-07-01 in `d4ca120bf`: diagnostics reproduced the real cause as the shared
      `/tmp/ssc-conformance.jar` being overwritten/corrupted by concurrent or repeated harness runs
      while Java was still executing it (`NoClassDefFoundError: ssc/Program$`, then `Invalid or
      corrupt jarfile`). The harness now builds the jar under its unique
      `$TMPDIR/ssc-conformance-logs-$$/` directory, captures Java/Rust stderr and stdout artifacts,
      retries empty Java stdout once, and prints a diagnostic summary on failure. Verification:
      `bash -n v2/conformance/check.sh`; two consecutive full `cd v2 && ./conformance/check.sh`
      runs passed after the per-run jar change (`run1 exit=0`, `run2 exit=0`); after rebasing on
      KC7, a final full run including KC7 checks also passed (`final exit=0`).

- [x] **K47 — Array-env VM optimization DONE** (`type Env = Array[Value]` replacing
      `List[Value]`; `Local(i)` is now O(1) via `env(env.length - 1 - i)` instead of
      O(i) linked-list scan). `extend`/`appendOne` replace `prepend`; de Bruijn convention
      unchanged (last binding = Local(0), achieved by appending in order). `LetRec` cyclic
      frame-tie unchanged (still `var env`). All changes in `v2/src/Runtime.scala` +
      `v2/src/Main.scala`. `conformance/check.sh` all green. Kernel +0.

- [x] **K50 — binary method signatures (`method m : self -> R`)** — DONE 2026-06-30 (commit 96475b20e).
      Two fixes: (1) `selfRes` now recurses into `TyFun`/`TyList`/`TyCon` args; (2) `parseMethodDecl` uses
      `parseFnType` instead of `parseAscType` so `->` parses in method sigs. Example: `hm-method-binary.hm`
      (`method smaller : self -> Bool`; `myMin`; type `(Int, Float)`; all 3 backends → `Pair(3, 1.5)`).

- [x] **K51 — ssct-hm stdlib expansion** — DONE 2026-06-30. Added 13 prelude functions in two groups:
      (a) Assoc-list map ops: `assocInsert`, `assocDelete`, `assocMapKV`, `assocUnionWith` (lookup was existing).
      (c) Parser combinators: `pResult`, `pChar`, `pStr`, `pDigit`, `pSeq`, `pAlt`, `pMap`, `pMany`, `pInt`.
      (b+d) `sortBy` was already in prelude; Writer/Reader effect wrappers deferred to BACKLOG.
      Fix: injectPrelude is a left-fold so new entries must precede their prelude dependencies in the list.
      `assocUnionWith` works on JS (polymorphic `===`); VM/Rust get "Int" type tag for String keys (light-qt limit).
      Examples: `hm-stdlib-map.hm` (30055, JS-only full test) + `hm-parser-comb.hm` (11, all 3 backends).
      Conformance: chk_hm + JS for map; chk_hm + run-ir + JS + Rust for parser-comb (all pass). 2c0824c73.

- [ ] **K51-followup — tagOfArg element-type inference for literal list/pair args** — salvaged from the
      dropped duplicate-K51 branch `feature/v2-dict-pass-showcases` (deleted 2026-07-07 hygiene sweep; K51
      itself landed independently). Its unique fix: dict-fns called with LITERAL list/alist arguments
      default the element type to Int. Add to `tagOfArg` in `v2/lib/mira-emit.ssc0` two cases:
      `case LCons(h, t) => tagOfArg(h)` and inside ConApp:
      `case ConApp(name, args) => (if #seq(name, "Pair") then tagOfArg(nthArg(args, 0)) else conTyName(name))`.
      Repro/gate: a dict-fn over a literal `[("a",1)]`-style alist with String keys must infer "String", not
      "Int"; add a conformance case (the dropped branch verified green on run-ir/JS/Rust with this shape).

- [x] **K52 — showcase programs** — DONE 2026-06-30. Two self-contained programs on all 3 backends:
      (a) `hm-lambda.hm`: lambda calculus interpreter (ADTs + subst + reduce + showE); `(const (id a) b)` → `"a"`.
      (b) `hm-arith-parser.hm`: recursive-descent arithmetic parser; `"1+2*3"` → 7 (correct * > + precedence).
      Both have conformance tests in check.sh (type check + run-ir + JS + Rust).

- [x] **K53 — benchmarks / profiling DONE** 2026-06-30. (a) `scripts/bench interp` post-K47 baseline
      captured (29 InterpreterBench benchmarks; key: `recursionFib`=1.176ms, `typeclassFoldMacro`=1.350ms,
      `tupleMonoid`=0.007ms, `valIntermediate`=0.254ms). Full table in `v2/specs/k53-bench-baseline.md`.
      (b) ssct-hm on hm-json.hm: ~3s wall / ~0.5s user CPU; JVM startup ~2.5s dominates. Hot path =
      HM unifier + let-poly over 90-fn prelude + 300-node JSON program. (c) No >20% CPU win
      identified from timing alone — JFR of short-lived scala-cli process is non-trivial; optimization
      deferred to BACKLOG. Merged: `feature/v2-k53-bench-profile` (964b28113).

**K3 BREADTH STATUS:** the actionable K3 roadmap is substantially delivered — stdlib now has list/string/map/
mapx/set/option/stream + a ~90-fn Mira prelude (incl. Either + full math); the type system is a complete
HM language (now with tuple-typed ADT fields); effects/actors/async are libraries (K46 adds futures/channels/
mailboxes); backends JS+Rust are ssc0 programs; and the JSON showcase proves a real program compiles to all
3 targets. Remaining is open-ended breadth (more libs/showcases on demand) + WASM (toolchain-blocked).

---

## K60 — Mira rename + fence language registry

- [x] **K54 — rename ssct-hm → Mira DONE** 2026-07-01. 66 files changed: lib/ssct-hm*.ssc0 →
      lib/mira*.ssc0; bin/ssct-hm*.ssc0 → bin/mira*.ssc0/mirac.ssc0; launchers v2/ssct-hm → v2/mira
      + v2/mira-js + v2/mira-rust; specs/41-ssct-hm.md → specs/41-mira.md; all imports+comments
      updated; conformance green (all 568+ ok). `v2/mira examples/hm-fact.hm` → "Int";
      `v2/mira-js` → 120 (node); `mirac` → Core IR → run-ir → 120. Merged: 84d6b28c6.

- [x] **K55 — Markdown extractor DONE** 2026-07-01. `lib/mira-md.ssc0` (ssc0, 130 lines):
      splitLines/stripYaml/startsWith3bt/isClosingFence/getFenceLang/go/extractFences.
      `bin/ssc-front.ssc0` driver + `v2/ssc-front` launcher. Conformance: `ssc run
      bin/ssc-front.ssc0 examples/hm-md-demo.ssc` → 2 blocks (mira + ssc0), YAML skipped.
      Implementation in ssc0 (not Mira) — avoids cross-language FFI; same pattern as mira.ssc0.
      Spec: `specs/61-fence-languages.md`.

---

## K61 — v1.0-compat frontend (KC1–KC8)

Goal: run existing v1.0 `.ssc` files on the v2 kernel (functional subset first, OOP later).
All written in Mira. Spec: `specs/60-compat-frontend.md`.
Prerequisite: K55 (Markdown extractor).

- [x] **KC2 — v1.0 lexer DONE** 2026-07-01. `examples/hm-lex.mira` (Mira, 130 lines):
      Token ADT (TKw/TId/TUId/TOp/TInt/TStr/TLParen-TRBrace/TComma/TDot/TColon/TSemi/TEq/TArrow/
      TUArrow/TAt/THash/TColonColon/TEof), skipWS+line-comments, scanEnd, scanStr/buildStr,
      parseIntR, lexPunct/lexOp helpers (split to reduce HM unifier depth), lex1 main loop.
      `lex "def f(x: Int) = x + 1"` → 12-token list. VM+JS+Rust all pass.
      Needs `-Xss512m` for type-checking (same as hm-json.hm). Conformance in check.sh.

- [x] **KC3 — v1.0 parser (functional subset) DONE** 2026-07-01. `lib/ssc1-front.ssc0` (ssc0,
      ~350 lines): combined KC2+KC3 lexer+parser. Lexer: 26 token kinds (includes `==`, `=>`, `->`,
      `::`). Parser: recursive-descent, tag-encoded AST (`Pair(tag, data)` — avoids ssc0 ADT
      limitations). Handles: `def`/`val` stmts, infix precedence climbing (prec 3–8), postfix
      `.field`/`(args)`/`[types]`, `if/then/else`, tuples, string/int/bool literals, prefix `-`/`!`.
      Type annotations stripped. Multi-stmt: semicolon-separated. ssc0 patterns: avoid nested
      constructor patterns (use nested match), avoid `-1` literal (use `#i.neg(1)`).
      Conformance: `ssc run examples/kc3-test.ssc0` → `SDef("f",[x],EInfix("+",EVar(x),EInt(1)))`.
      Tests: factorial, main(println(f(5))), multi-stmt parsing all pass.

- [x] **KC4 — functional lowering → Core IR DONE** 2026-07-01. `lib/ssc1-lower.ssc0` (~200 lines
      ssc0): de Bruijn name resolution (`lookupVar`), all arithmetic/comparison/boolean/string ops →
      Core IR prims, `def`→IrDef+IrLam, `val`→IrDef, `if`/`app`/`tup`/`pre`/infix all lowered.
      Injected builtins: `println`/`print` → `IrPrim("io.print")`. Entry = `IrApp(IrGlobal("main"),Nil)`.
      `bin/ssc1c.ssc0` + `v2/ssc1c` launcher. ssc0 GOTCHA: `_` inside constructor patterns (`case
      Cons(_, t)`) is INVALID in kernel parser — use real var names (`u`, `bodyIgnored`, etc.).
      Done-when test: `kc4-hello.ssc` ("Hello, World!") + `kc4-fact.ssc` (120) both run via `ssc run-ir`.

- [x] **KC6 — intrinsics mapping** — Map v1.0 stdlib calls to v2 primitives.
      Implemented as a **resolve-pass** (`resolveE`) in `lib/ssc1-lower.ssc0` that pre-processes
      the KC3 AST before de Bruijn lowering. No kernel changes needed — prims `slen`/`scodeAt`/
      `sslice`/`str->i`/`sconcat` all already existed.
      **New AST tags:** `"ctorap"` (IrCtor), `"prim"` (IrPrim/IrApp-to-helper).
      **Resolved:** `None`→IrCtor("None",[]), `Nil`→IrCtor("Nil",[]), `Some(x)`→IrCtor("Some",[x]),
      `List(...)` → nested Cons/Nil, `Left(x)/Right(x)/Cons(h,t)`.
      **String fields:** `.length/.size` → `slen`, `.substring(f,t)` → `sslice`,
      `.charAt(i)` → `scodeAt`, `.toString` → `i->str`, `.toInt` → helper `__str_toInt`.
      **List fields:** `.head/.tail/.isEmpty/.nonEmpty` → injected helper defs.
      **List methods:** `.map(f)/.filter(f)` → injected 2-arg `_sel_map/_sel_filter` defs.
      `.foldLeft(z)(f)` → curried 2-arg+1-arg `_sel_foldLeft` def (all with letrec, de Bruijn).
      **Infix `::` added:** `elem :: list` → `IrCtor("Cons",[elem,list])`.
      **Conformance:** kc6-str ("hello".length=5), kc6-substr (substring→"ell"),
      kc6-list (List.map.head=20), kc6-fold (List.foldLeft sum=6) — all green.
      **Deferred:** string `+` (type-ambiguous without KC5), list `.length` (vs string `.length`),
      `str.split`, `str.toUpperCase/toLowerCase`, `list.append(++)`.
      Done-when: ✓ string length/charAt/substring + List.map/filter/foldLeft + ctors.

- [x] **KC5 — type checker** DONE 2026-07-02. `lib/ssc1-check.ssc0` (425 lines): HM type
      inference (Algorithm W) over ssc1-front Pair-tagged AST. Types: Int|Str|Bool|Float|Dyn|
      Var(n)|Fun(a,b)|List(e)|Tup(es). TyDyn = escape hatch for OOP/constructors/builtins.
      Two-pass: collect all names → TyDyn, then infer bodies. Let-generalization + fresh vars
      (global cell). Context dict params (__tc_*) filtered before inference. Operators: `+`
      unifies operands (Int+Str → error); `-/*///%` force Int; `==/</>` same-type → Bool.
      ssc1c.ssc0 exits 1 with clear error on type mismatch. All 21 KC examples pass.
      conformance/check.sh: kc5 type-error 1+"a" test added.

- [x] **KC7 — OOP lowering DONE** 2026-07-01. Match expressions + case class → Core IR.
      **Parser** (`ssc1-front.ssc0`): `parsePat` (cpat/vpat/wpat), `parseMatchArm`,
      `parseMatchArms`, `parseMatchExpr` (prefix `match e {}`), postfix `e match {}` in
      `buildPostfix`, `parseCaseClass`, `skipToStmt`, `parseOneStmt` extended for
      `case class`, `sealed`, `abstract`, `object` (skipped). Two new AST tags: `"match"`,
      `"casecls"`.
      **Lowering** (`ssc1-lower.ssc0`): `appendL` (global), `buildCtorArgs` (builds
      `[IrLocal(n-1)..IrLocal(0)]`), `lowerMatch` (→ `IrLet + IrMatch`; pure vpat/wpat
      catch-all skips `IrMatch` to avoid crashing on non-Data scrutinee), `lowerCaseCls`
      (injects constructor `IrDef` + `_sel_field` accessor defs), `lowerStmtToList` (replaces
      `lowerStmt`; `casecls` emits multiple defs via `appendL`). `resolveE` + `lowerE` handle
      `"match"` tag recursively. `lowerProg` uses global `appendL`.
      **De Bruijn conventions** (arm scope): `appendL(revL(patVars), letScope)` — last field of
      ctor = local 0; first field = local(arity-1). vpat default uses `Cons(varName, scope)`
      so the variable maps to local 0 without IrMatch.
      **Conformance:** kc7-match (List head via Cons/Nil match=42), kc7-casecls (Point(3,4).x+y=7),
      kc7-opt (vpat+list head=10) — all green.
      **Deferred:** nested patterns, object methods (bodies are skipped), full inheritance.

- [x] **KC10 — var/while loops + if-without-else DONE** 2026-07-01.
      `var x = e` → `cell.new(e)` with scope entry `"@x"`. `x = v` → `cell.set`. Reads of `x`
      check for `"@x"` in scope → `cell.get`. `while (cond) body` → IrLetRec([IrLam(0,
      IrIf(cond, IrLet([body], recurse-via-Local(1)), Unit))], call). `if (cond) sideEffect`
      without else → else branch = mkTup(Nil) = Unit. All lowered in `lowerBlock`+`lowerE`.
      Done: kc10-while (sumTo(5)=10), kc10-ifnoelse (positivedone).

- [x] **KC9 — block expressions DONE** 2026-07-01.
      `{ val x=e; def f(p)=body; sideEffect; result }` in function bodies.
      Parser: `parseBlock` on `{` in `parseAtom` (val/def/expr stmts until `}`).
      Lowering: `lowerBlock(scope, stmts)` — val→IrLet, def→IrLetRec (self-scope for recursion),
      side-effect expr → IrLet with `_blk_` discard, final expr → lowerE directly.
      resolveE handles `"block"` recursively (each item's subexpressions resolved).
      Done: kc9-block (49), kc9-sideeffects (abc), kc9-localdef (49).

- [x] **KC5-micro + KC7b — string `+` heuristic + object methods DONE** 2026-07-01.
      **KC5-micro**: In `resolveE`, for `inf("+")`, if either side is a string literal/prim
      → upgrade op to `"++"` (sconcat). Handles `"Hello, " + name + "!"` without type env.
      `isStrExpr(e)` checks tag="str", or prim op in {i->str, sslice}.
      **KC7b**: Parse `object O { defs }` body into `Pair("object", Pair(name, stmts))` instead
      of skipping. Resolver: uid receiver in `resolveMethodCall` → static dispatch `O_method(args)`
      instead of `_sel_method(O, args)`. Lowering: `lowerStmtToList("object")` prefixes each def
      as `O_def → IrDef("O_def", IrLam(...))`. Add `skipToBrace` parser helper.
      Done-when: `kc5-strcat` ("Hello, World!") + `kc7b-object` (Math.square+double=31) pass.

- [x] **KC11 — lambda expressions + return DONE** 2026-07-01.
      **Lambda**: `(x: T) => body` and `x => body` (param type annotations stripped).
      `tryLamParams` speculatively parses `(name [: T], ...)` list → `Some(names)` or `None`;
      `parseExpr` checks `id =>` and `(params) =>` before falling to `parseInfix`.
      `return` in `parseAtom`: keyword → `Pair("return", parseExpr)`.
      **Lowering**: `lowerE` for `"lam"` → `IrLam(n, lowerE(appendL(revL(params), scope), body))`.
      `lowerBlock` for `"return"` → evaluate return value (ignore remaining stmts).
      `if (cond) return e; rest` in block → `IrIf(cond, e, lowerBlock(rest))`.
      GOTCHA: ssc0 pattern match can't have string literals as pattern args (`case Pair("if", x)`
      is invalid — use variable + `#seq` guard).
      Done: `kc11-lambda.ssc` (`compose(double, inc)(5)` = 12),
            `kc11-return.ssc` (`abs(-7) + abs(3)` = 10).

- [x] **KC8 — `given`/`using` DONE** 2026-07-02.
      `given name: T = body` → `val name = body` via `parseOneStmt` `given` branch.
      `(using p: T, ...)` in def param lists → `parseUsingParams` helper in `parseDef`.
      `f(a, b)(using sep)` at call sites: `buildPostfix` strips `using` keyword and
      merges the using arg list into the preceding call (`append(eargs, newArgs)`) so the
      runtime sees a single N-arg call (no partial application needed).
      GOTCHA: `appendL` does not exist in `ssc1-front.ssc0` — use `append` from `list.ssc0`.
      Done: `kc8-given.ssc` prints "hello, world".

- [x] **KC12 — string interpolation DONE** 2026-07-02.
      `s"Hello, $name!"` → `"Hello, " ++ name ++ "!"` concatenation AST via
      `readInterpId`/`interpParts`/`partsToExpr`/`buildSInterp` in `ssc1-front.ssc0`.
      `parseAtom` detects `id("s"|"f"|"raw")` + next token `str` → `buildSInterp`.
      `++` lowered to `IrPrim("sconcat", ...)` already in place (KC5-micro).
      Only `$identifier` works; `${expr}` is skipped as literal.
      Done: `kc12-interp.ssc` prints "Hello, World!".

- [x] **KC5 — context bounds + given auto-injection DONE** 2026-07-02.
      **Parser** (`ssc1-front.ssc0`): `parseTypeParams` extracts `[A: TC, ...]` bounds → prepend
      `"__tc_TC"` dict params to `allParams`. `readTypeStr` collects type annotation tokens (uses
      `tokKind` for punctuation since `tokVal=""` for `[`, `]`, etc.). `given` branch captures
      type string as 3rd Pair field: `Pair("given", Pair(name, Pair(typeStr, body)))`.
      `skipTypeArgs` skips `[...]` after TC name in bound position.
      `joinStrs` concatenates token values.
      **Lowering** (`ssc1-lower.ssc0`): `kc5GivenCell`/`kc5SigCell` mutable cells.
      `parseGivenType("Show[Int]")` → `Pair("Show","Int")` (scans for `[`, slices).
      `buildGivenTable` indexes `given` stmts by `(TC, Type)` key.
      `buildSigTable` collects `__tc_`-prefixed params per def → maps fn→[TC...].
      `isCtxParam`/`ctxParamTC` identify/extract TC from `__tc_TC` names.
      `findGiven` looks up `(TC, typeName)` or wildcard `*`.
      `typeOfExpr` heuristic: `"int"`→"Int", `"str"`→"String", `"bool"`→"Bool", else"*".
      `injectGivens` called in `lowerE` app case: prepends given globals before user args.
      `lowerProg` initializes both cells before lowering statements.
      **Runtime** (`v2/src/Runtime.scala`): added `io.println` primitive (print + newline);
      `printlnDef` updated to use `io.println`.
      **GOTCHA**: `tokVal=""` for `[`, `]` punctuation — must use `tokKind` in `readTypeStr`.
      Done: `kc5-typeclass.ssc` prints "shown\nshown" ✓. `kc5-strcat` + `kc7b-object` still pass.

## [x] KC13 — end-to-end `.ssc` runner + `${ident}` interpolation fix (2026-07-03)

`ssc run bin/ssc1-run.ssc0 examples/kc13-hello.ssc | ssc run-ir /dev/stdin` → "Hello, World!" ✓.
Conformance clean (also fixed 3 pre-existing harness bugs: kc5-type-error, kc9-sideeffects, kc10-ifnoelse).

**Goal:** `v2/ssc1 file.ssc` runs a real v1.0 `.ssc` Markdown file end-to-end on the v2 kernel.

**Slices:**

1. **`${ident}` interpolation** — KC12 only handles `$name` (bare). `examples/hello.ssc` uses
   `s"Hello, ${name}!"`. Fix `readInterpId` in `ssc1-front.ssc0`: if next char after `$` is `{`
   read the identifier inside braces, skip the `}`. Pure front-end; no backend/kernel change.

2. **Multi-block concatenation** — real `.ssc` files have multiple `scalascript` fenced blocks.
   `bin/ssc1-run.ssc0` (ssc0): import `mira-md.ssc0` + `ssc1-front.ssc0` + `ssc1-lower.ssc0`;
   read file → `extractFences` → filter `Pair("scalascript",src)` blocks → join with `"\n\n"` →
   lex → parse → lower → emit Core IR. Reuses all KC3-KC12 machinery; no new language features.

3. **`v2/ssc1` launcher** (bash): `exec scala-cli run "$DIR/src" -- run "$DIR/bin/ssc1-run.ssc0" "$@"`

4. **`v2/examples/kc13-hello.ssc`** — the canonical Markdown-wrapped hello example (YAML front-matter +
   two `scalascript` blocks, `greet` + `main`). Tests `${ident}` + multi-block.

5. **Conformance** — `check.sh` entry: `ssc run bin/ssc1-run.ssc0 examples/kc13-hello.ssc | ssc run-ir`
   → "Hello, World!".

**How / files to touch:**
- `v2/lib/ssc1-front.ssc0`: fix `readInterpId` for `${...}` (add `{`/`}` branch).
- `v2/bin/ssc1-run.ssc0` (NEW): Markdown → `extractFences` → filter scalascript → join → parse/lower/emit.
- `v2/ssc1` (NEW launcher script).
- `v2/examples/kc13-hello.ssc` (NEW).
- `v2/conformance/check.sh` (append KC13 check).

**Done-when:** `cd v2 && ssc run bin/ssc1-run.ssc0 examples/kc13-hello.ssc | ssc run-ir /dev/stdin`
outputs "Hello, World!"; `./conformance/check.sh` exits 0.

---

## K62 — scalameta-free frontend parity (measured 2026-07-09)

Goal: bring the **native** (scalameta-free) `.ssc` frontend tower
(`mira-md` → `ssc1-front` → `ssc1-check` → `ssc1-lower`) to parity with the v1
scalameta parser, so scalameta can eventually be dropped from `v1/lang/core` and
the `v2FrontendBridge` seam retired. Spec: `specs/62-scalameta-free-frontend-parity.md`.

**Baseline (measured, native parse+lower over the real 195-file `examples/*.ssc`
corpus):** 186/195 = 95.4% PASS after the fence-tag fix + `-Xss16m`. The parser is
NOT the hard part — scalameta is only parser+typer, and the native parser already
covers 95% of the corpus surface. Full method + numbers in the spec.

Reproduce the measurement:
`scala-cli --power package v2/src --assembly -f -o /tmp/ssc.jar`, then loop
`java -Xss16m -jar /tmp/ssc.jar run bin/ssc1-run.ssc0 <examples/*.ssc>` (exit 0 ⇔
frontend accepted the file).

- [x] **K62.0 — fence-tag policy fix DONE** 2026-07-09. `bin/ssc1-run.ssc0`:
      broadened the block filter from `#seq(lang,"scalascript")` to also accept
      `scala` (both are executable ScalaScript in v1 — `Lang.isParseable`).
      Moved 32 corpus files from FAIL→PASS. `ssc0` note: `#or` is not a primitive;
      use a nested `if` to build the boolean.

- [x] **K62.1 — `Pair/2` DONE** 2026-07-09. Root cause was NOT "assign mid-block"
      (that already works). `buildPostfix` (`ssc1-front.ssc0`) never consumed a
      trailing `{ block }` arg, so top-level `route(...) { req => … }` parsed as a
      bare call + a *separate* standalone block; inside it, `id = expr` became
      `idx_assign` whose lowering does `match ldata { case Pair(arrFn, idxArgs) }`
      on a bare var → `no arm for Pair/2`. Fix: add a trailing-`{` arm to
      `buildPostfix` that consumes the block via lambda-aware `parseBlockArg`
      (`e { body } → e(body)`). All 6 files pass; conformance 640/640 green.

- [x] **K62.2 — `Nil/0` DONE** 2026-07-09. Root cause was single-arg
      `String.substring(from)`. `resolveMethodCall` matched only two-arg substring
      (`match r0 { case Cons(too, r1) => … }`), no `Nil` arm → `no arm for Nil/0`.
      (The tuple/`var`/`while` context was a red herring; atomic repro:
      ``val s="abc"`` ⏎ ``s.substring(1)``.) Fix: add the `Nil` arm —
      `substring(from) == substring(from, length)` → `sslice(s, frm, slen(s))`.
      Verified `"hello".substring(2)` → `"llo"`. Both files pass.

- [x] **K62.3 — compile-recursion robustness DONE** 2026-07-09. Added `-J-Xss512m`
      to the `v2/ssc` and `v2/ssc1` launchers (matching the existing `ssc0c` /
      `sscx` convention) so the VM's deep `Compiler.compile`/`FastCode` recursion on
      large programs (`control-center-live`, `auth-full`, `x402-cardano-scalus`)
      no longer StackOverflows. **Parse+lower now 194/195** — only `deploy.ssc`
      (sh-only, no code) remains, correctly out of scope.

- [x] **K62.4 — axis 2 (native type-checker) MEASURED** 2026-07-09.
      `bin/ssc1-check-run.ssc0` over the corpus: **162/195 pass, 32 false-positive
      rejections** in ~4 operator-inference categories (`++`/`+` concat ×11; Float
      `/`/`%`/`*` ×8; String/Int/Bool unify ×9; if-branch ×4). The checker is
      Dyn-lenient elsewhere (doesn't reject `val x: Int = "hello"`). **Off the
      critical path** — `ssc1-run` skips type-check — so it doesn't block dropping
      scalameta; it's a quality gate to close before making `ssc1-check` mandatory.

- [x] **K62.5 — axis 3 (native end-to-end run) MEASURED** 2026-07-09.
      `ssc1-run` → `run-ir` over the corpus: **3/195 run to completion.** Errors split:
      **Class A (~40 files)** — hidden parse-completeness gaps surfacing as
      `unbound global: _err`/bare-keyword. Roots (instrumented `parseAtom`): bitwise
      ops (`& | ^ ~ << >>`), `@` annotations, `$`, char literals, Markdown-link
      imports. **Class B (~150 files)** — missing stdlib/plugin/effect intrinsics
      (http `route`/`authServer`, `Dataset_*`/`spark`, `runActors`/`runAsync`/
      `signal`, `Graph_*`/`Db_query`/`IndexedDb_store`, `mcpConnect`/`agentTool`,
      crypto `verifyEd25519`/`totp`/`uuidV7`, …). Full breakdown in spec 62.

### K62 remaining (concrete, prioritized — the real path to scalameta-free)

- **K62.6 — parse-completeness (Class A).** Bounded frontend work in `ssc1-front.ssc0`.
      Native end-to-end (plugin runtime) 3 → 14 across these slices; conformance stays 640/640.
  - [x] **K62.6 DONE** 2026-07-09: skip Markdown-link imports `[a,b](path)` (23 files)
        + Scala `import a.b.{x,y}` (both were keyword/`_err` leaks).
  - [x] **K62.6b DONE** 2026-07-09: top-level `var` + assignment (global cells via
        `topVarsCell`; init in doc order; refs/assigns from def bodies resolve).
  - [x] **K62.6d DONE** 2026-07-09: skip `@Name` / `@Name("args")` annotations.
  - [x] **K62.6c-ops DONE** 2026-07-09: wildcard `import a.b.*`, cons `::`, pair
        `->`, char literal `'x'`/`'\n'`, bitwise `& | ^`, shifts `<< >> >>>`, prefix
        `~` (VM had `i.and/or/xor/shl/shr/ushr/not`). End-to-end 14→18, `_err` 20→11.
  - [x] **K62.6c-for DONE** 2026-07-09: `for x <- xs [if g] yield/do e` (single
        generator + guard) → map/foreach/filter; lex `<-`.
  - [x] **K62.6c-map DONE** 2026-07-09: `Map(k -> v, …)` initial entries (was
        silently empty — `map.put` mutates+returns Unit, so built via IIFE), `new
        Foo(x)` == `Foo(x)`, multi-generator `for` (flatMap; + `_sel_flatMap`
        List/Cons default arm → `_list_flatMap`). End-to-end 18→22.
  - [x] **K62.6c-under DONE** 2026-07-09: underscore placeholder `filter(_ % 2 == 0)`
        → `filter(x => x % 2 == 0)` (arg-level tree-walk `exprHasPh`/`replacePh`,
        wrap compound-ph args in a lambda; bare `f(_)` left as-is). Closed the `_` class.
  - [x] **K62.6c-indent DONE** 2026-07-09: **significant indentation**. Lexer emits
        `NL <indent>`; a layout pass (offside → virtual `{ ; }`) converts brace-less
        indented blocks (`def f() = <indent> stmts`, `if/while/for` bodies), with
        continuation handling (`else`/infix/`.` on a new line). Also: while-parser
        skips `do`. **End-to-end 22→33** (+11). Conformance 640/640.
  - [ ] **K62.6c-rest** (small tail): `$`, missing methods (`takeWhile`/`dropWhile`),
        multi-generator `for` with pattern binders. `throw` needs a VM error prim.
        Pre-existing (not indentation): match-in-def returns the wrong arm (`v2` block
        value / match-dispatch bug — verify on `def f(x)=x match {...}`).
  - [ ] **K62.6e — field access `_sel_get`/`_sel_env` → `__method__`.** Tried (route
        `resolveField` fallback for non-case-fields through `__method__`, with a
        `caseFieldsCell` registry to keep case-class field projection). REVERTED:
        net −1 because it triggers the same latent bug as below. Re-land after fixing it.
  - [ ] **K62.7a-fix — latent `match: scrutinee not Data: "__method__"`** (VM compile,
        Runtime.scala:577). A `__method__`-dispatched call feeding a match scrutinee
        FRONTERRs 3 files (`mcp-search-server`, `traditional-payments`, `x402-metamask`).
        Blocks K62.6e too. Needs VM-side investigation, not a frontend fix.
- **K62.7 — dispatch alignment (Class B). SUPERSEDES the old "re-grow stdlib"
      framing** — see K62.5b: the v1 stdlib ALREADY exists in the v2 runtime
      (`V2PluginRegistry`, loaded by `PluginBridge.loadAll()`; the bridge path uses
      it, busi 61/61). Native failures were a name/dispatch mismatch, not missing
      intrinsics. My bare-VM 3/195 measurement had an empty registry — artifact.
  - [x] **K62.7a DONE** 2026-07-09: `ssc1-lower` generic `_sel_<method>` fallback →
        `IrPrim("__method__", [str name, recv, args])`, matching FrontendBridge +
        Runtime dispatch (unhandled → free `Op`). Conformance 640/640. Added
        `BridgeCli run-ir` (loadAll + run native IR) to measure against the
        plugin runtime.
  - [x] **K62.7b DONE** 2026-07-09: uid-static `Foo.method(args)` on an UNKNOWN
        uppercase object → `IrPrim(__method__, [str method, Ctor(Foo,[]), args])`, so
        the plugin runtime's `__fallback__.Foo.method` resolves it. User objects
        (isKnownObject via a `collectObjects` pre-scan) keep static `Foo_method`.
        Pure lowering, no runtime change. **End-to-end 33→38** (+5). Conformance 640/640.
        Also A: `for (a,b) <- pairs` tuple-pattern binder. NOTE: `System.out` /
        `_sel_get` FIELD access on uid still fails (blocked by the K62.7a `__method__`
        VM-scrutinee bug — sibling-owned); takeWhile/dropWhile on strings = follow-up.
  - [ ] **K62.7c** — run native front → plugin-enabled runtime for real: wire the
        plugin registry into the `ssc`/`ssc1` launchers (or use `BridgeCli run-ir`),
        then re-measure end-to-end.
- [ ] **K62.8 — (optional) close type-check false positives (K62.4).** Only needed
      if `ssc1-check` becomes mandatory.

**Bottom line (revised, K62.5b):** dropping scalameta is NOT a stdlib rewrite — the
stdlib already exists in the v2 runtime and the bridge uses it. It reduces to two
bounded, scalameta-independent frontend/lowering jobs: **K62.6 parse-completeness**
(~40 `_err` files) and **K62.7 dispatch alignment** (started). They compound per
file, so end-to-end pass-count lags until both close. See spec 62.

## K63 — Conformance runner speedup (v2/conformance/check.sh)

Baseline: ~12 min, **0 parallelism on 14 cores**, everything sequential. Measured
costs: rustc 240 compiles (~2-3 min, `ld` slow+flaky), ~300+ cold `java -jar` starts
(219 ms each ⇒ ~1.5-2 min), assembly-jar build (~2-3 min), node 177 (~30 s), wasm 9
+ compute (~2 min). Do INCREMENTALLY, each slice keeps the default run at 640/640 and
identical pass/FAIL set (diff old-vs-new output before landing).

- [x] **K63.1 (89120ab3e) — fast mode `CONF_FAST=1`**: guard the rustc/node/wasm blocks
      (`[ -z "$CONF_FAST" ]`) so front/lower iteration runs only the VM (run-ir) lane.
      Lowest risk (default unchanged). ~12 min → ~4 min for iteration. VERIFY:
      `CONF_FAST=1` skips Rust/JS/WASM; default still runs+passes all 640.
- [x] **K63.2 — robust+fast rustc**: install-guarded `-C link-arg=-fuse-ld=lld` (if
      `lld` present) + `RUSTC_WRAPPER=sccache` (if present); no-op when absent. Kills
      the `ld: file is empty` disk-pressure flakes + caches repeat compiles. VERIFY:
      rustc lane still green; 2nd run faster.
- [ ] **K63.3 — batch run-ir into one JVM**: add `ssc run-ir-batch <list>` (one JVM
      runs many IRs) or a persistent JVM; replace 316× cold `java -jar run-ir`. ~1.5-2
      min saved. VERIFY: batched stdout matches per-invocation, byte-for-byte.
- [x] **K63.4 — OPTIONAL parallelism (opt-in `CONF_JOBS=N`, default 1=sequential)**:
      infra = bounded bg pool + barrier; landed for the stateless `chk`/`chk_hm` VM lane
      (188 tests) → parallel fast-mode 210s→111s (~2×), IDENTICAL 406/0 set. Sequential
      default byte-identical. Fix/expand the other lanes gradually:
  - [x] K63.4a+b — parallelize the inline rustc (62) + node (62) blocks: wrap each
        `if have_X; then <body> fi` with a sequential/parallel split; the parallel branch
        runs `<body>` in a bg subshell with an ISOLATED `TMPDIR=$_PAR_DIR/<n>` (no temp
        collisions) + captured output. Structural blocks (fn-defs / else-branch) skipped.
        Validated CONF_JOBS=6 → 640/0. NOTE: backend jobs are HEAVY (JVM emit + rustc);
        keep CONF_JOBS ≈ cores/2 to avoid oversubscription (CONF_JOBS=14 thrashed).
  - [x] K63.4c — ORDERED output in parallel mode via exec-redirect SEGMENTS. In CONF_JOBS>1
        mode stdout is redirected to an indexed segment file; each enqueue seals the current
        segment (inline output since the last job), runs the job into the next index, then
        opens a fresh segment (`_pseg`). The barrier restores real stdout (fd 3) and cats
        every `[0-9]*` file in index order → inline headers/tests and backgrounded job
        results interleave EXACTLY as sequential. stderr stays live (one-line note). Verified:
        FAST SEQ vs FAST PAR6 stdout diff = IDENTICAL. Tradeoff: parallel stdout is buffered
        until the barrier (silent run + note), which is the standard output-sync cost.
- [x] **K63.5 — cache the assembly jar**: hash `src/` → skip `scala-cli package` when
      unchanged (keyed jar in a stable cache dir). ~2-3 min/iteration. VERIFY: rebuilds
      on any src change, reuses otherwise; stale-cache guard.

## K62.13 — native enum support (parser-axis) — 2026-07-10

Measured native-frontend coverage (`-Xss512m`): **stage1 parse+lower = 192/195** (parser
axis essentially closed); the real gap is stage2 execution. Genuine frontend blockers:
`_err` fallback (13 files: annotations/`derives`), **enum `case` (4 files)**, dsl no-arm.
Enum is the memory-flagged ceiling and unblocks the `=>`/`match` layout openers (they were
net-negative ONLY because enum cases are skipped → `North` unbound). Slices:

- [x] E1 — front: parse `enum E[T](p) <: S:` / `enum E { … }`, then `case X` (nullary) and
      `case X(p1: T, p2: T)` (parametrized, multi-field) → `("enum", Pair(name,
      [Pair(caseName, params)…]))`. Layout `;` between case lines; loop skips semis, reads
      while next kw is `case`, stops at first non-case. Braced form skips to matching `}`.
- [x] E2 — lower: expand the `enum` node in the stmt→def pass. Nullary case → `IrDef(name,
      IrCtor(name, Nil))` (VALUE — bare `North` → IrGlobal → ctor value). Parametrized case →
      `lowerCaseCls` (ctor fn + `_sel_` accessors).
- [x] E2b — front: add `match` to `isLayoutOpener` so brace-less `def f = e match`⏎<arms>
      opens a layout block that closes at dedent — the arms no longer swallow the following
      top-level statements (a PRE-EXISTING bug this enum work exposed; braced `match {…}`
      unaffected). This is the last `=>`/`match` opener the spec flagged; enum support made it
      net-positive (was net-negative only because enum cases were skipped → `North` unbound).
- [x] E3 — lower: extend `collectCaseFields` + `collectCaseClassOrder` to walk `enum` cases
      (named-args reorder + accessor-routing parity with case classes).
- [~] E4 — verify: enums.ssc Direction block runs (`North -> South …`), was fully broken.
      Fast conformance 406/0 (parity w/ origin/main), full corpus stage1 192/195 = zero parse
      regressions. Shape/Tree still blocked on stdlib `math` + literal-pattern `case 0` (both
      PRE-EXISTING, non-parser gaps).
- [ ] E5 (stretch) — subtypesOf registration so `case _: Shape` type-tests resolve on enums.

## K62.14 — native literal patterns in match (parser-axis correctness) — 2026-07-10

- [x] Integer/char/string/bool/float LITERAL patterns (`case 0`, `case '+'`, `case "s"`,
      `case true`) were silently lowered as catch-all defaults (lowerMatch else-branch), so
      `case 0 => a; case _ => b` always returned the LAST default (b). The front already
      emits `("lpat", Pair(ty, v))`; the lower ignored it. Fix (ssc1-lower only): `hasLpat`
      detects a literal match → `lowerLitArms` builds an `IrIf(__eq__(scrutinee, litIr))`
      fallthrough chain (structural `__eq__`, all value types; vpat/wpat terminates; a ctor
      arm becomes a one-arm IrMatch with the rest as default). Chars tokenize as int codes,
      so char patterns are int lpat and are handled too.
- [x] Verified: synthetic int + char (`sign 0/1/9`→zero/one/many, `op '+'/'-'/'*'`→
      add/sub/other) all correct. Non-literal matches skip the new branch (unchanged).
      The 3 corpus files with literal patterns (data-types, dsl-calc-parser,
      actors-typed-remote-spawn) stay compound-blocked by UNRELATED gaps (Person case-class
      match, Parser_regex/runActors plugins) — the fix is a correctness improvement, not a
      corpus flip.

## K62.15 — merged field accessors across shared field names (parser-axis) — 2026-07-10

- [x] Two case classes sharing a field name (`Person(name,age)` + `Student(name,grade)`)
      each emitted `IrDef("_sel_name", …)` in lowerCaseCls; the SECOND overwrote the first,
      so `.name` on the earlier class hit `no arm for <Ctor>/N`. Fix (ssc1-lower only):
      lowerCaseCls emits only the ctor def; buildMergedAccessors generates ONE `_sel_<field>`
      per unique field with a match arm for EVERY ctor declaring it (each returning its own
      index) from caseFieldOrderCell (covers case classes + enum cases + imported modules).
- [x] Verified: synthetic Person/Student/Company (`name` at idx 0,0,1) → all correct incl.
      the index-1 case; data-types.ssc now runs 8+ lines (Point, Person, enum toHex colors,
      Shape/area) — was crashing at Person immediately. Compounds with K62.13 (enum) + K62.14
      (literal patterns). ~7 corpus files had shared field names. Conformance + stage1 pending.

## K62.16 — structural ==/!= (string equality) — 2026-07-10

- [x] `==`/`!=` lowered to `IrPrim("i.eq",…)`; the VM i.eq compile path (Runtime.scala:1725)
      coerces operands via asInt, so `"a"=="a"` and any string equality CRASHED ("expected
      Int, got …"). Corpus-wide correctness bug. Fix (ssc1-lower only): lower `==`/`!=` via
      the structural `__eq__` prim (Runtime.scala:2174 — value equality over StrV/IntV/FloatV/
      BoolV/DataV). Trade-off: an int `==`/`!=` loop condition no longer matches the i.eq JIT
      fast-path (falls to the correct general interpreter). Verified conformance-neutral.
- [x] Verified: `"foo"=="foo"`→T, `!="bar"`→T, int/float/char == still correct; recursion.ssc
      (100k-iter recursions w/ `==` base cases) 0.5s + correct; fast conformance 406/0 @164s
      (JVM-startup-dominated, no perf regression). String `<`/`<=`/`>=` (ordering) still use
      i.eq/i.lt — out of scope (rare; needs a string-compare prim).

## K62.17 — call-site default-parameter synthesis — 2026-07-10

The honest BridgeCli metric (RUN=42/194) flagged `arity: N expected, M given` (17 content-*/
datatable files) as the #1 blocker: K62.6g PARSED `def f(x=v)` defaults but discarded them,
so calling with omitted trailing args crashed. Fix (front + lower, zero ripple — a SHARED cell
since ssc1-lower imports ssc1-front and both run in one ssc0 process):

- [x] FRONT: `paramDefaultsCell` (per-param-list accumulator) + `funcDefaultsCell`
      (funcName → positional defaults). parseParam records `Pair(name, dfltExpr)` on a default
      (return unchanged, zero ripple). parseDef clears the accumulator before its params and
      snapshots `Pair(name, positionalDflts)` into funcDefaultsCell after — nested defs clear
      before the outer reads the body, so no interference.
- [x] LOWER: `padDefaults(fn, rargs)` at the resolveE app binding — a var/uid call whose name
      is registered and given < arity gets its omitted TRAILING defaults appended (resolveE'd).
      Constants (`[]`/`""`) are the common toolkit case; scope-independent.
- [x] Verified: synthetic `greet("Alice")`→"Hello, Alice!" (2 defaults), `greet("Bob","Hi")`,
      `tally(List(1,2,3))`→0 all correct; content-data-source / content-slot / datatable-static-spa
      PROGRESS PAST arity (→ next blockers `unbound null` / plugin module-context). Remaining:
      content-introspection is CURRIED (`contentComponent(name)(render)`), a separate gap.
      Conformance + stage1 pending.

## K62.18 — null + throw literals (bridge parity) — 2026-07-11

Blockers revealed behind the K62.17 arity fix (honest BridgeCli measure). Both mirror the
scalameta bridge exactly:
- [x] `null` → `None` — parseAtom kw branch → `mkUVar("None")` (front already lowers `None`
      → `ctorap None`). Bridge: FrontendBridge Lit.Null → Ctor("None"). Verified:
      `val x = null; x match { case None => … }` → "was null"; content-data-source /
      datatable-static-spa PROGRESS PAST `unbound null` (→ deeper arity residuals).
- [x] `throw e` → `__throw__(e)` — parseAtom id branch (`throw` is an id, not a kw). Bridge:
      Term.Throw → App(Global("__throw__")). __throw__ is registered by the plugin runtime
      (loadAll), so it resolves under BridgeCli (the bare VM has no throw). inline (macros,
      `${}` quote/splice) and ctx (context var) stay out of scope.

## K62.19 — native tuple field access ._N → _sel__N (fix recursion conformance red) — 2026-07-11

Native v2/conformance red `recursion.ssc via ssc1` (405/406 fast): the Collatz longest-sequence
finder returned (1,0) not (871,178). Root: tuple positional field access `._N` returned a Stub.
selOrMethod routes `._N` → __method__ (unchanged since K62.6e), but the VM only resolves `_N` via
a Long fast-path (Runtime.scala:1256) that fires in numeric contexts — so `println(x._2)` and
`best._2` in the fold lambda got a Stub (methodOp has no general `_N` handler). A VM fast-path
change stopped `best._2` (in `s > best._2`) resolving.

- [x] FIX (ssc1-lower, lower-only, no kernel rebuild): selOrMethod routes `_1`/`_2`/`_3`/`_4` to
      `_sel_<field>` — the built-in prelude accessors `_sel__1.._sel__4` (match Pair/Tuple4),
      correct in ALL contexts, no fast-path dependency. Verified: (5,7)._1/._2 → 5/7,
      foldLeft tuple accumulator → correct, recursion.ssc via ssc1 → Collatz (871,178) GREEN.
- NOTE for kernel owners: the VM `methodOp` (Runtime.scala:3225) lacks a general `_N` positional
      handler; `__method__("_2", Pair)` returns Stub outside the Long fast-path. A VM fix there
      would also cover any other `._N`→__method__ path (e.g. records with `_N` fields).

## Native-front correctness program (2026-07-11, opus) — accurate worklist + K62.20 tuples

Accurate native-parity (ssc1-run → BridgeCli run-ir WITH plugins vs tests/conformance/expected):
MATCH=44, MISMATCH=110. Categories: Stub-dispatch 7 (bimap/fmap/copy — extension/typeclass, v2.1
lane), Op-unperformed 11 (plugin), empty/early-halt 70 (bulk, mostly plugin-runtime + a few native
early-halts like Array.tabulate curried-static). The native front is far less correct on the broad
corpus than v2/conformance 406/0 (curated) suggests — this is a multi-session program.

- [x] K62.20 — 3+ tuples: FLAT `Tuple${N}` ctors, not nested Pairs. lowerTuple built
      `Pair(a,Pair(b,c))` for 3-tuples while parsePat + the bridge use `Tuple3` → `case (a,b,c)`
      never matched and `._2`/`._3` were wrong. Fix: lowerTuple emits `Tuple${N}` for N≥3 (2-tuples
      stay Pair, shared with `->`), + Tuple3 arms on _sel__1/_sel__2/_sel__3. Verified: 2/3/4-tuple
      `._N`, `val (a,b,c)=` destructure, and `case (n,s,flag)=>` all correct; tuples.ssc GREEN.
- [ ] Remaining program (v2.1 track's lane — coordinate): extension/typeclass dispatch Stub
      (bimap/fmap/copy), plugin-Op unperformed, per-file early-halts (Array.tabulate static+curried,
      Option ops, string %-format). RECOMMEND: wire the native-parity check into CI (ssc1-run vs
      expected/, non-plugin subset) so these regressions are caught.
