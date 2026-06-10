# `foreach` + outer-`var` mutation does not propagate

**Status**: RESOLVED 2026-06-10 — no longer reproduces on current `origin/main`.
Reported by busi (real testbed), 2026-06-09; the closure-cell write-back was
fixed by intervening interpreter work (the `var` cell is now mutated in its
defining scope). Locked in by `ForeachVarMutationTest` covering every checklist
item below, in both JIT-on and JIT-off eval modes. No code change required.
**Priority**: medium. Workaround exists (`foldLeft`), but the pattern is idiomatic
Scala and silently produced wrong results — no error, just an empty/stale `var`.

## 1  Symptom

Mutating an outer `var` from inside a `foreach` lambda body does not survive the
loop. The `var` keeps its pre-loop value.

### Minimal repro

```scalascript
var state = List[Int]()
List(1, 2, 3).foreach(x => state = state :+ x)
println(state.length)   // expected 3, actual 0
```

Confirmed reproducible on both `b0582b18` and `2300fdc61` (current `origin/main`)
via busi's repro harness. Output: `4-foreach-var-mutation: FAIL len=0`.

## 2  Expected behavior (Scala semantics)

In Scala, a closure captures an outer `var` by reference. Assignments inside the
closure are visible to the enclosing scope after the loop. The program above must
print `3`. The same must hold for `while`-free producers built on `foreach`:

```scalascript
var sum = 0
List(1, 2, 3).foreach(x => sum = sum + x)
// sum == 6
```

## 3  Root cause hypothesis

The interpreter evaluates the `foreach` lambda body in a child scope whose
assignments to a captured `var` write to a copy / shadowed binding rather than
the cell in the defining scope. `foldLeft` works because the accumulator is
threaded through the return value rather than via assignment, so it never relies
on closure-cell write-back.

The fix is to make `Term.Assign` to a name bound in an enclosing scope mutate the
**defining** scope's cell, not the current lambda frame — for all higher-order
combinators that take a side-effecting lambda (`foreach`, and by extension
`map`/`filter` bodies that assign for effect).

## 4  Behavior checklist

- [x] `List(...).foreach(x => outerVar = outerVar :+ x)` leaves `outerVar` with all elements.
- [x] `List(...).foreach(x => sum = sum + x)` accumulates into `sum`.
- [x] Nested `foreach` with mutation of the same outer `var` works.
- [x] `Set(...).foreach(...)` and `Map(...).foreach(...)` mutation also propagate.
- [x] Existing `foldLeft` accumulation behavior is unchanged (regression guard).
- [x] Assignment to a `var` declared *inside* the lambda still stays local (no leak upward).

## 5  Verification

Add `ForeachVarMutationTest` covering each checklist item. Re-run busi's repro
(`4-foreach-var-mutation: PASS`). Run `backendInterpreter/test` green.

## 6  busi context

Found in busi phase50b `allocateAllowances`. busi currently works around every
occurrence with `foldLeft` and an explicit accumulator; this spec removes the
foot-gun at the language level.
