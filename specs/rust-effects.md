# Rust backend — algebraic effects via tagless-final traits

Status: **draft spec** (2026-06-08). Not yet implemented.

---

## 1. Background: how ScalaScript effects work

ScalaScript uses a **free-monad effect system**.  The runtime represents
effectful computations as a tree:

```
Pure(value)                        — done
Perform(effectName, op, args)      — request an operation
FlatMap(sub, k: Value => Comp)     — sequencing
```

`runLogger { body }` walks the tree, intercepts every `Perform("Logger", ...)`,
calls the real I/O, and resumes.

---

## 2. Mapping to Rust: tagless-final (mtl style)

The free-monad tree doesn't exist in Rust — allocating `FlatMap` nodes for
every `>>= ` is expensive and unidiomatic.  Instead we use the
**tagless-final** pattern: each effect becomes a Rust **trait**, and every
function that uses an effect receives an extra `eff: &mut impl EffectName`
parameter.  The handler is a concrete struct passed at the call site.

This is:

- **Correct** — semantics match the interpreter (operations are dispatched,
  handlers control their output).
- **Composable** — multiple effects combine naturally (see §4).
- **Zero-cost** — monomorphised by rustc; no heap allocation.
- **Complete** — covers Logger, Random, Clock, Env, Http, Retry, Cache, State;
  Stream is a special case (see §6).

---

## 3. One effect: Logger

### Generated trait (once per crate, if Logger is used)

```rust
pub trait LoggerEffect {
    fn log_info (&mut self, msg: &str) {}   // default: no-op
    fn log_warn (&mut self, msg: &str) {}
    fn log_error(&mut self, msg: &str) {}
    fn log_debug(&mut self, msg: &str) {}
}
```

Default no-op bodies mean any struct `impl LoggerEffect {}` is a silent
handler — no boilerplate needed for the bench case.

### Generated handler (bench / production)

```rust
pub struct NoOpLogger;
impl LoggerEffect for NoOpLogger {}

pub struct VecLogger { pub entries: Vec<(String, String)> }
impl LoggerEffect for VecLogger {
    fn log_info(&mut self, msg: &str) { self.entries.push(("info".into(), msg.into())); }
    // …
}
```

Only `NoOpLogger` is generated automatically.  `VecLogger` and a printing
logger are generated when the source calls `runLogger` without a custom
handler.

### Source function with `! Logger`

```scalascript
def compute(n: Int): Int ! Logger = …
```

becomes:

```rust
fn compute(n: i64, eff: &mut impl LoggerEffect) -> i64 { … }
```

### `runLogger { body }`

```scalascript
def workload(): Int = runLogger { compute(10000) }
```

becomes:

```rust
fn workload() -> i64 {
    compute(10000, &mut NoOpLogger)
}
```

`runLogger` without explicit handler → inject `NoOpLogger`.
`runLogger` with a capturing handler → inject the matching concrete struct.

---

## 4. Multiple effects

`T ! Logger ! Random ! Clock` — three options considered:

| Option | Pros | Cons |
|--------|------|------|
| A: one param per effect | simple type inference | signature grows with each effect |
| B: combined supertrait `trait Eff: Logger + Random` | one param | must generate a new combined trait per combination |
| C: single `EffEnv` struct implementing all effects used in the module | one param always, easy to extend | slightly over-captures |

**Decision: Option C** — one `EffEnv` struct per crate, implementing every
effect trait used anywhere in the module.  Functions receive `eff: &mut EffEnv`
(or `eff: &mut impl LoggerEffect + RandomEffect` for the precise bound).

```rust
pub struct EffEnv {
    pub rng_seed: u64,
    pub log_entries: Vec<(String, String)>,
    pub clock_ms: i64,
    // … one field per stateful effect
}

impl LoggerEffect for EffEnv { … }
impl RandomEffect for EffEnv { … }
impl ClockEffect  for EffEnv { … }
```

`runLogger { runRandom(42) { body } }` → `body(&mut EffEnv::default())`
where `EffEnv` collects all handlers' state in one place.

This is **fully composable**: adding a new effect to a function that already
takes `eff: &mut EffEnv` requires zero changes to the call site.

---

## 5. All standard effects

| ScalaScript | Rust trait | Key ops | Handler state |
|---|---|---|---|
| `! Logger` | `LoggerEffect` | `log_{info,warn,error,debug}(msg)` | `Vec<(level,msg)>` or no-op |
| `! Random` | `RandomEffect` | `next_int(bound) -> i64`, `next_float() -> f64` | seed `u64` (LCG/xoshiro) |
| `! Clock`  | `ClockEffect`  | `now_ms() -> i64` | frozen `i64` or `SystemTime` |
| `! Env`    | `EnvEffect`    | `get(key) -> Option<String>` | `HashMap<String,String>` |
| `! Http`   | `HttpEffect`   | `request(method, url, body) -> String` | stub / real hyper |
| `! Retry`  | `RetryEffect`  | `retry(n, f)` | policy struct |
| `! Cache`  | `CacheEffect`  | `get(k)`, `put(k,v)` | `HashMap<K,V>` |
| `! State`  | `StateEffect`  | `get() -> S`, `put(s: S)` | generic `S` field |

Each one follows the same pattern as Logger above.

---

## 6. Stream effects (special case)

`Stream.emit` is a **multi-shot effect**: the handler collects every emitted
value.  Traits alone can't model this without calling the continuation
multiple times — which is safe in Rust (closures are `FnMut`).

Proposed lowering for `! Stream[A]`:

```rust
pub trait StreamEffect<A> {
    fn emit(&mut self, value: A);
}

pub struct VecStream<A> { pub items: Vec<A> }
impl<A> StreamEffect<A> for VecStream<A> {
    fn emit(&mut self, value: A) { self.items.push(value); }
}
```

`runStream { body }` becomes:

```rust
fn workload() -> i64 {
    let mut sink: VecStream<i64> = VecStream { items: Vec::new() };
    _run_body(&mut sink);
    sink.items.len() as i64
}
```

`src.runToList()` — `src` IS the `VecStream`; `.runToList()` returns
`sink.items.clone()`.

This correctly models `effect-stream` semantics and compiles without
coroutines or unsafe.

---

## 7. Codegen changes required

### `mapType`

```scala
// T ! E  → mapType returns (rustType, effectSet)
case m.Type.Apply("!", List(t, eff)) =>
  (mapType(t), Set(eff.name))   // strip effect from Rust return type
```

The effect set is collected at the `def` level and used to add the `eff`
parameter.

### `renderDef`

```scala
if effectSet.nonEmpty then
  s"fn $name($params, eff: &mut impl ${effectSet.map(traitName).mkString(" + ")}) -> $ret"
else
  s"fn $name($params) -> $ret"
```

### `renderCall` (call sites)

When calling a function that has effects, the current `eff` is forwarded:

```rust
compute(10000, eff)   // eff threads through automatically
```

### `runLogger / runRandom / …` call sites

```scala
case m.Term.Apply("runLogger", List(body)) =>
  s"{ let mut _eff = NoOpLogger; ${renderTerm(body)}(&mut _eff) }"
```

### Trait and handler struct emission

One `runtime/effects.rs` file generated alongside `runtime/mod.rs`, emitted
when any effect is used.  Contains all trait definitions + `NoOpLogger` etc.

---

## 8. What this does NOT cover

- **Resumable effects with complex control flow** (e.g. a handler that runs
  the body twice, or aborts mid-computation).  Traits model "callback into
  handler" but not "handler redirects continuation".  This covers all
  standard ScalaScript effects since none require multi-resume.
- **Delimited continuations / shift-reset**.  Out of scope for R.2.
- **Effect polymorphism in user-defined functions** — a user writing
  `def f[E](x: Int): Int ! E` is not handled; only concrete effect names
  are supported.

---

## 9. Implementation order

1. **Phase 1 — Logger only** (`effect-pure` bench)
   - `mapType` strips `! E` and records effect name
   - `renderDef` adds `eff: &mut impl LoggerEffect` param
   - emit `trait LoggerEffect` + `NoOpLogger` in `runtime/effects.rs`
   - `runLogger { body }` → `{ body(&mut NoOpLogger) }`
   - call-site threading of `eff`

2. **Phase 2 — Random, Clock, Env** (similar to Logger, stateful handlers)

3. **Phase 3 — EffEnv multi-effect composition**

4. **Phase 4 — Stream** (`effect-stream` bench)

Each phase is independently shippable and doesn't break the previous one.

---

## 10. Custom effects + explicit `handle`/`resume` (R.4.2 — ONE-SHOT DONE 2026-06-22)

> **Status: one-shot custom effects SHIPPED 2026-06-22** (`effect-oneshot` n/a → 0.0020 ms; the 3 gaps below
> are implemented in `RustCodeWalk`/`RustGen`/`RustRuntimeTemplates`, guarded by `RustGenR44Test`). The
> `while`-loop case needs no trampoline, as predicted. **Multi-shot (`effect-multishot`) remains out of scope
> (R.6)** — a single trait-method return can't re-invoke the continuation. The original scoping is kept below
> for the record.


The bench `effect-oneshot` / `effect-multishot` workloads use a USER-declared effect plus
an explicit `handle(body) { case Eff.op(args, resume) => … }`, not a `runXxx` standard
handler. This section scopes that case. **Probed 2026-06-21: the tagless-final infra is
~70 % built; three concrete codegen gaps remain.** (Verified by emitting a minimal probe:
`effect Bump: def tick(): Int` + `def useEff(): Int ! Bump = Bump.tick() + Bump.tick()` +
`handle(useEff()) { case Bump.tick(resume) => resume(5) }`.)

**Already built (reusable):**
- `! E` return types are detected (`defEffectName`) and stripped (`mapType`).
- An effectful def already gets `_eff: &mut impl ${E}Effect` (`renderParams`/`renderMutParams`).
- Call sites already thread `&mut _eff` to effectful callees (`withEff` in `renderTerm`).
- The Stream effect is fully lowered (`_eff.stream_emit` + a `VecStream` handler) — the
  template to copy for a custom handler struct.
- `effect Bump:` is already preprocessed (in `Parser`) to `object Bump { def tick(): Int = __effectOp__ }`;
  `EffectAnalysis.isEffectOpDef` recognises the `__effectOp__` marker.

**The 3 remaining gaps (each cargo-tested; nothing runs until all 3 land — all-or-nothing):**

1. **Custom-effect trait emission.** Collect each `Defn.Object` whose op bodies are
   `__effectOp__` → its name + ops (op name, params, return type). Emit
   `pub trait ${Name}Effect { fn ${op}(&mut self, ${args}) -> ${ret}; }` in
   `runtime/effects.rs`. Currently `RustRuntimeTemplates` emits only the *standard* traits
   (State/Random/Stream) keyed by name; it must accept the parsed custom-effect op
   signatures (plumb `Map[effectName, List[(op, params, ret)]]` from `RustCodeWalk` →
   `RustGen` → `RustRuntimeTemplates`).
2. **`Eff.op(args)` → `_eff.op(args)`.** In `renderTerm`, recognise a `Term.Select(Term.Name(eff), op)`
   where `eff` is a known effect object and lower the apply to `_eff.${op}(${args})` (rust
   snake-cases multi-word ops; `tick` stays `tick`).
3. **`handle[Eff](body)(cases)` → handler struct + apply.** This is the current blocker
   (`unsupported pattern: Pat.Extract (Bump.tick(resume))` at `RustCodeWalk` renderPattern).
   For each `case Eff.op(binders…, resume) => handlerBody`, generate
   `struct __H_${Eff}; impl ${Eff}Effect for __H_${Eff} { fn ${op}(&mut self, ${binders}) -> ${ret} { ${handlerBody with resume(v) ⇒ v} } }`,
   then emit the handle expression as `{ let mut _eff = __H_${Eff}; ${render(body)} }` (the
   body is an effectful call that already threads `&mut _eff`). `resume(v)` in **tail
   position** lowers to just `v` (one-shot tail-resume); a non-tail or **multi-shot** resume
   (e.g. `opts.flatMap(opt => resume(opt))` in `effect-multishot`) is OUT of scope here —
   traits model "callback into handler", not "handler redirects the continuation" (see §8;
   multi-shot stays `n/a`, an R.6 follow-up).

With (1)–(3) the `while`-loop case (`effect-oneshot`) needs NO trampoline: the loop runs
directly and `Bump.tick()` is just an `_eff.tick()` method call — the key advantage of the
tagless-final lowering over the Free-monad CPS port. Multi-shot (`effect-multishot`) needs
`FnMut`-style re-invocation and remains deferred.

**Verify:** `cargo build` the minimal probe above → `10`; then `./bench.sh effect-oneshot
--backend rust` goes from `n/a` to a real number; add a `RustGenR42Test`/`R44` codegen test.
