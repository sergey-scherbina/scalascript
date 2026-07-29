# v2m-2g — mark handler arms in the front, stop guessing them in the backend

Status: **LANDED** 2026-07-29 (`v2-backend-matrix-gaps`). Written before the code, per `AGENTS.md`;
results appended at the bottom.

## The problem, measured

`bench/corpus/range-sum` is

```scalascript
(0 until 50).map(i => i * i).foldLeft(0)((a, b) => a + b)
```

No effects. No handlers. No `handle` anywhere in the program. Its profile nevertheless spends
**36 samples in `Runtime.withHandlerDispatchInvocation`**, which per invocation does:

- a `ThreadLocal.get`,
- an allocation of `HandlerDispatchInvocation`,
- an allocation of a fresh `Object` for the activation,
- a list cons,
- and a `try/finally` that restores the ThreadLocal.

That cost is paid by any closure built through `Emit.handlerClos` instead of `Emit.clos`.

## Why an ordinary lambda gets built as a handler

`HandlerDispatchShape.isRoot` (`v2/src/CoreIR.scala`) decides it in the BACKEND, from the term's
shape:

```scala
def isRoot(arity: Int, body: Term): Boolean =
  arity == 1 && (body match
    case Term.Match(Term.Local(0), _, _) => true          // ← any `x => x match { … }`
    case other                           => containsDecisionMarker(other))
```

The first arm is an over-approximation of one of the most ordinary shapes in the language — every
`case` lambda, every partial function, every `xs.map(x => x match …)`.

## Why the obvious narrowing does not work — measured, not argued

Dropping the shape arm and keeping only `containsDecisionMarker`:

```
tests/conformance/contract.sc --lanes int,v2 --only 'effect*,handler*,control*,coroutine*,resume*'
  → 2 REGRESSIONS: effects v2 FAIL, effects-handler v2 FAIL
```

Reverted; effects returned to 12/12. **The shape arm is load-bearing.** The reason is in the front,
not the backend.

## Root cause: the front marks handler arms on ONE of its two paths

`v2/lib/ssc1-lower.ssc0`, `lowerHandlerMatch`:

```
def lowerHandlerMatch = (scope, eventName, arms) =>
  let general = … bind/guard/typed/nested/lpat/alt/named-catch-all/duplicate-ctor … in
  if general then
    lowerMatch(scope, Pair("var", eventName),
      handlerMarkedArms(eventName, arms, hasCatchAll(flatArms)))   -- ← MARKED
  else lowerDirectHandlerMatch(scope, flatArms)                     -- ← NOT MARKED
```

`handlerMarkedArms` wraps every arm body in `__handler_dispatch_selected__(event)` and appends a
`__handler_dispatch_miss__(event)` fallback when there is no catch-all.
`lowerDirectHandlerMatch` — the fast path for plain constructor patterns — emits
`IrMatch(IrLocal(0), arms, default)` and **no markers at all**.

So a handler written with simple constructor arms reaches the backend as a bare
`Lam(1, Match(Local(0), …))`: byte-for-byte the shape of an ordinary matching lambda. The backend
cannot tell them apart, and the shape arm exists precisely to avoid mis-classifying those handlers
as ordinary — at the price of mis-classifying every ordinary matching lambda as a handler.

**The information exists in the front** — `lowerHandlerMatch` is only ever called while lowering a
`handle` construct — and is discarded before the backend sees the term.

## The change

**Mark on both paths, then narrow the predicate.** In that order, and verified in that order.

1. `lowerDirectHandlerMatch` marks its arm bodies the way the general path does: each arm body
   becomes `handlerSelectedBody(eventName, body)`, and a `__handler_dispatch_miss__` default is
   supplied when the arms have no catch-all — mirroring `handlerMarkedArms`.
2. Only once every genuine handler carries a marker, `HandlerDispatchShape.isRoot` drops the shape
   arm and becomes `arity == 1 && containsDecisionMarker(body)`.

Deliberately NOT chosen: adding a flag to `Term.Lam` or a new `HandlerLam` node. Either would
change the frozen CoreIR node inventory (13 nodes / 7 consts, `specs/coreir-inventory-gate.sh`),
force a codec version bump, and require every backend and both fronts to move together. The marker
prims already exist, are already part of the IR, are already emitted on the general path, and are
already what `containsDecisionMarker` tests. This change makes an existing mechanism total instead
of introducing a second one.

One edit reaches both fronts: `ssc1-lower.ssc0` is the SHARED lowering, used by F and by legacy.

## What must be verified, and what must not be assumed

- **The marker must be a no-op outside handler dispatch.** The direct path's arms will now evaluate
  `__handler_dispatch_selected__(event)` where they previously evaluated nothing. That is only safe
  if the prim has no observable effect on the value or on control flow. **Verify by reading both
  implementations** (`Prims` for the VM, `Emit`/`JvmByteGen` for the bytecode lane) — do not infer
  it from the general path working, because the general path also inserts a `Let` the direct path
  deliberately omits, so the two are not the same context.
- **Step 1 alone must be behaviour-preserving.** Run the effect conformance slice after step 1,
  BEFORE touching `isRoot`. If step 1 regresses anything, the design is wrong and step 2 is moot.
- **Step 2 is what removes the cost.** Re-run the same slice, then the full corpus contract on
  `int,v2`.
- **Perf claim by the alternating protocol only** (`specs/v2-runtime-perf-vs-v1.md` §7): three
  before/after rounds swapping only the staged jars, medians. Single runs on this host have swung
  2.5× on identical code.

## Done-when

- `effects`, `effects-handler` and the rest of the effect/handler/control/coroutine slice green on
  `int,v2` after each step.
- Full corpus contract on `int,v2` with no regression.
- `withHandlerDispatchInvocation` absent from a `range-sum` profile.
- A measured before/after on `range-sum` and `hof-pipeline` by the alternating protocol, reported
  as medians — including if the answer is "no effect", in which case the change is reverted and
  recorded as refuted like the narrowing above.


## Result

Implemented in the order this spec required, verified at each step.

**Step 1 — the direct path marks its arms.** Effect/handler/control/coroutine slice on `int,v2`:
**20/20, contract GREEN**. Behaviour-preserving, established before touching the predicate.

**Step 2 — `isRoot` drops the shape arm.** Same slice: **20/20, GREEN**. The narrowing that failed
before this work now holds, which is the causal claim of this spec confirmed: it was never that the
predicate was wrong, it was that the front withheld the fact on one of its two paths.

**Only the SELECTED marker was added.** Reading both implementations, as required rather than
inferring from the general path: `handlerDispatchSelected` → `handlerMatchSelected(active)`, which
does nothing when no dispatch is active — safe. `handlerDispatchMiss` **raises** when none is
active, so it is not interchangeable with the direct path's existing absent-default behaviour and
was left alone.

**Structural done-when:** `withHandlerDispatchInvocation` in a `range-sum` profile: **36 samples →
0**. A program with no effects no longer runs any handler bookkeeping at all.

**Timing, three ALTERNATING rounds** swapping only the staged v2-core jar and tower lowering,
medians, v2-bytecode ms/iter. The unchanged v1 control held to ±2% across all six runs — the
quietest measurement of this arc:

| workload | before | after | |
|---|---:|---:|---|
| `range-sum` | 0.495 | 0.400 | **1.24×** |
| `hof-pipeline` | 0.146 | 0.121 | **1.21×** |
| `literal-match` | 0.175 | 0.173 | flat |

Complete separation on both movers: the worst `after` round (0.407) beat the best `before` round
(0.474). `literal-match` not moving is consistent — its lambda is not a match-on-its-own-argument,
so it never paid the toll.

One edit reached both fronts, as designed: `ssc1-lower.ssc0` is the shared lowering.
