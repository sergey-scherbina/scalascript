# JIT-compiled Boolean-returning method calls boxed as IntV

**Status**: LANDED 2026-06-10. Reported by busi (cross-module pwhash repro, rozum seq 61/73).

## 1  Symptom

`def isHashed(s: String): Boolean = s.startsWith("pbkdf2s:")` — when JIT-compiled
(busi's hasher runs 25k iterations, so the module's functions get hot) —
returned `IntV(1)` instead of `BoolV(true)`. `isHashed(<hashed>).toString` printed
`"1"`, and any `== true` comparison saw `false`. Reproduces single-module **and**
cross-module (it is not a marshalling bug; the JIT box is wrong everywhere).

## 2  Root cause

The JIT computes booleans as 0/1 longs in the long bank. At the call boundary the
0/1 long is boxed back to a `Value` using `JitResult.resultIsBool` — `pureBool(raw
!= 0)` when set, else `IntV`. `resultIsBool = JitPredicates.isBoolReturning(body)`.

`isBoolReturning` recognised comparison/logical infix ops (`< == && …`), `!`,
`if`, `block`, and `match` (a prior fix for the same class of bug — busi's
`isDebit`), but **not Boolean-returning method calls** — `s.startsWith(p)`,
`xs.contains(x)`, `s.endsWith(p)`, `s.isEmpty` — which are `Term.Apply` /
`Term.Select`, not `ApplyInfix`. So such bodies got `resultIsBool = false` and
the 0/1 long was boxed as `IntV`.

## 3  Fix

`JitPredicates.isBoolReturning` now also returns true for receiver methods that
always return Boolean and that the JIT compiles to a 0/1 long:
`startsWith`/`endsWith`/`contains` (with one arg) and the nullary
`isEmpty`/`nonEmpty`/`isDefined`. Both JIT backends (`AsmJitBackend`,
`JavacJitBackend`) delegate to this shared predicate. Tagging a method the JIT
cannot actually compile is harmless — the function then bails to the interpreter,
which boxes correctly anyway.

## 4  Verify (`SscVmTest`)

- [x] `JavacJitBackend.tryCompile` and `AsmJitBackend.tryCompile` of
  `def isHashed(s): Boolean = s.startsWith("h:")` both set `resultIsBool = true`;
  `direct.apply(StringV("h:x")) == 1`, `StringV("nope") == 0`.
- [x] End-to-end: busi's pwhash repro now prints `isHashed(hashed): got=true`
  (was `1`).

## 5  Not fixed here (separate bug)

busi's `verifyPassword` still returns wrong results — but that is a **different,
deeper** bug, not Boolean marshalling: `hashPasswordWith(plain, salt, 25000)` is
deterministic (two direct calls match), yet computing it **via `hashPassword`**
vs a **direct cross-module call** with the same plain/salt/iters yields different
25k-iteration HMAC digests, **even with `SSC_JIT=off`**. This points at while-loop
`var` state across cross-module call depth, not the JIT boolean box. Tracked
separately; non-blocking for busi (pbkdf2 externs supersede the hand-rolled
hasher).
