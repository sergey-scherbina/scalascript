# Effects: `perform` inside an imperative loop (jvm/js codegen gap)

Status: **diagnosed; honesty diagnostic landed 2026-06-12; real codegen fix still
open.** `effect-cps-loops-honesty` shipped (jvm/js now refuse to compile instead of
emitting broken code — see "Interim honesty option" below). The real lowering
(`effect-cps-loops-{jvm,js}`) is still a focused codegen task. This doc is the deep
write-up so the next person doesn't re-derive it.

## TL;DR

A custom algebraic effect performed **inside an imperative `var` + `while` loop**
does not lower correctly to jvm (and js) — the generated Scala fails to compile.
The interpreter handles it fine, so the corpus benches `effect-oneshot` /
`effect-multishot` are green on `ssc`/`ssc-asm` but `n/a` on jvm/js/rust.

It is **NOT** "effects are unsupported on jvm." Two things prove the gap is
narrow:

1. **Built-in** effect runners work cross-backend: `runLogger`/`runStream`
   (`effect-pure`, `effect-stream`) are green on all five backends.
2. **Non-loop** custom effects compile + run on jvm. This minimal program emits
   valid Scala and runs:
   ```scalascript
   effect Bump:
     def tick(): Int
   def once(): Int ! Bump =
     val a = Bump.tick()
     val b = Bump.tick()
     a + b
   ```

The break is specifically a `perform` reachable from inside a `while` whose body
also mutates `var`s.

## Symptom (reproduce)

```
./bin/ssc emit-scala bench/corpus/effect-oneshot.ssc > /tmp/eos.scala
scala-cli compile /tmp/eos.scala
```
yields:
```
error: value < is not a member of Any        (the `i < n` loop guard)
error: Reassignment to val s                  (the `s = …` loop-body mutation)
```

The generated `loop` looks like:
```scala
def loop(n: Int, start: Long): Any =
  _bind(_binOp("+", start, 1), (s: Any) =>      // var s  → immutable lambda param
    _bind(0L, (acc: Any) =>                       // var acc → immutable lambda param
      _bind(0, (i: Any) =>                         // var i  → immutable lambda param
        _bind(while (i < n) {                       // while body NOT CPS-transformed
          s = s * 2862933555777941757L + 3037000493L   // ← Reassignment to val s
          acc = acc + Bump.tick().toLong + s % 7
          i = i + 1
        }, (_t: Any) => acc))))
```

## Root cause (two coupled defects)

The CPS transform (`runtime/backend/jvm/.../JvmGenCpsTransform.scala`) makes the
whole effectful function `Any`-typed and threads sub-results through `_bind`. In
that world:

1. **`var` is bound like `val`** — as an immutable `(_: Any) => …` lambda
   parameter. So any later `x = …` is a "Reassignment to val", and the value is
   `Any` so `i < n` / `s * …` don't typecheck.
2. **No `Term.While` CPS case** — the `while` is wrapped in `_bind(while {…}, k)`
   with its body left raw, so a `perform` inside the body (`Bump.tick()`, an
   `_Computation`) is never threaded through `_bind`/the continuation.

Why non-loop effects work: with no loop, each `val a = Bump.tick()` is exactly a
`_bind(perform, a => …)` — the natural CPS shape. The trouble is only mutable
state + a loop.

## Intended fix (shape)

- **Keep `var`s as real, typed, mutable `var`s** in the generated Scala (declared
  before the loop, reassigned in the body). Then `i < n`, `s = …` compile. An
  effectful var-rhs still `_bind`s into the var.
- **Add a `Term.While` lowering** to a trampolined recursive helper:
  ```scala
  { def _w(): Any = if (i < n) _bind(<cps body>, (_: Any) => _w()) else (); _w() }
  ```
  `_bind(c, f)` is **lazy** when `c` is a `_Computation` (`_FlatMap(c, f)`; see
  `JvmGenRuntimeSources` `def _bind`), and `_run`/`_handle` trampoline it — so a
  body that performs every iteration recurses without growing the JVM stack. (A
  body that performs only *sometimes* would recurse eagerly on the non-perform
  iterations; fine for these benches, worth noting for pathological loops.)
- The body's `x = … perform …` assignments already CPS correctly via the existing
  `Term.Assign` arm — **but** they assign an `Any` value into a typed `var`, so
  each needs a cast (`x = v.asInstanceOf[T]`). The transform must know each var's
  type (track it where `var`s are introduced).

## ⚠️ Trap found while attempting it (2026-06-12, reverted)

Editing the obvious spot — `emitCpsBlock`'s `build`/`Defn.Var` case + a new
`Term.While` case in `emitCpsExpr` — **did not change the generated code at all**.
A *minimal* `def f(): Int ! Bump = { var x = 0; x = x + Bump.tick(); x }` still
emitted `_bind(0, (x: Any) => …)` for the var, even though the only remaining
`emitCpsBindWithType` callers were the last-stat + `Defn.Val` cases.

**Conclusion: a TOP-LEVEL effectful `def` body is CPS-emitted through a path other
than `emitCpsBlock`.** Before touching anything, MAP that path:
- grep the main `JvmGen` def-emission and every `emitEffectfulParamGroups` caller;
- find where a top-level `def … : Any = <cps>` body's block statements get walked
  (it is *not* the `emitCpsBlock`/`build` recursion the nested-def case uses);
- the `var → _bind` happens there. Fix it there (and likely mirror in the nested
  `emitCpsBlock` path too).

## Acceptance

- `emit-scala bench/corpus/effect-oneshot.ssc` compiles with scala-cli and runs.
- `./bench.sh effect-oneshot effect-multishot` flips jvm (then js) `n/a` → a number.
- **Zero regressions** across the effect suites: `StdEffectsTest`,
  `JvmGenEffectsRuntimeTest`, `CoroutineTest`, `AlgebraicEffects*`, and the corpus
  `effect-pure`/`effect-stream` cells. HIGH regression risk — gate on all of them.

## Interim honesty option — ✓ LANDED 2026-06-12 (`effect-cps-loops-honesty`)

`CapabilityCheck.performInWhileLoop` (in `lang/core/.../validate/CapabilityCheck.scala`)
now refuses compilation with a clear `Diagnostic.Generic` message when a custom effect
`perform` is reachable inside a `while` loop, **for source-emitting CPS backends only**
(gated on `OutputKind.ScalaSource` / `JavaScriptSource`; the interpreter is unaffected).
Previously `emit-scala`/`emit-js` exited 0 with broken output that only failed downstream
in scala-cli / node — a silent `n/a`. Now it's a loud message naming the workaround.

Detection is **precise and conservative** (no false positives):
- Parses the module's OWN scalascript blocks (imports are `Content.Import`, never inlined
  source — so library effect runners can't trip it) and runs `EffectAnalysis.analyze` to
  get the in-module effect ops + effectful funs.
- Flags a `Term.While` only when its body subtree actually performs an in-module effect op
  (`Eff.op(…)`) or calls an effectful function. A non-loop effect, a pure `while`, and an
  imported/unknown call are all left alone.

Tests: `CapabilityCheckTest` — perform-in-while on jvm/js → diagnostic; interpreter,
non-loop effect, and pure `while` → none; plus the real `bench/corpus/effect-oneshot.ssc`
is flagged on jvm. Remove this gate (or narrow it) once `effect-cps-loops-{jvm,js}` lands
the real lowering.

## Related

- `runtime/backend/jvm/src/main/scala/scalascript/codegen/JvmGenCpsTransform.scala`
  (CPS transform), `JvmGenRuntimeSources.scala` (`_bind`/`_run`/`_perform`/`_handle`).
- `runtime/backend/interpreter/.../EffectsRuntime.scala` — the interpreter does
  this correctly via eager one-shot dispatch + a mutable env; commit `e29c5b182`
  explains why multi-shot dispatch stays eager.
- `bench/corpus/effect-oneshot.ssc`, `effect-multishot.ssc` (the dashboards).
- js mirror lives in `JsGen` CPS codegen — same fix after jvm.
