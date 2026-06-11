# JIT: ref-returning function calls

Source: busi seq-74 regression (rozum `scalascript` room, 2026-06-11).

## Symptom

A cross-module function that delegates to another module's ref-returning
(String / collection) function returned `IntV(0)` instead of the value:

```scalascript
// persons.ssc
def normalizePersonHandle(raw: String): String = raw.trim.toLowerCase
// public_address.ssc  (imports normalizePersonHandle)
def normalizeBusiLocalPart(raw: String): String = normalizePersonHandle(raw)

normalizeBusiLocalPart("Sig_Main")   // => 0  (IntV), expected "sig_main"
```

Downstream this surfaced as `No method '+' on IntV(0)`, breaking every busi
phase87 public-address test and gating their re-bump off pin `351cdaf4`.

## Root cause (two layers in the SscVm register-JIT path)

1. **`VmCompiler` typed every non-double user-fn `CALL` result as `TInt`** — there
   was no `TRef` branch (`VmCompiler.scala`, the `Some(slot)` call site). A callee
   returning a reference therefore unified the enclosing function's return type to
   numeric, so `JitRuntime` boxed the raw long `0` as `IntV` instead of returning
   the ref.
2. **The `CALL` opcode dropped the ref result.** A ref-returning callee stashes
   its value in `tlRefReturn` and returns `0L` (see `RETREF`); the `CALL` opcode
   wrote only that `0L` into the numeric bank and never copied the stashed ref
   into the caller's ref bank (`refStack`). So even once the destination was typed
   `TRef`, it read `null`.

## Fix

1. `JitPredicates.isRefReturning(t: Term)` — mirror of `isBoolReturning`:
   recognises ref-producing forms (String literal, interpolation, `+` concat with
   a ref operand, curated String/collection methods, and `if`/`block`/`match`
   tails). Conservative — unknown forms return `false`, so a numeric callee is
   never mis-typed as ref.
2. `VmCompiler.calleeReturnsRef(callee)` — uses `isRefReturning` on the callee
   body, and for a body that simply delegates to another user function
   (`def f(x) = g(x)`) resolves and recurses (visited set + depth cap) so
   transitive delegation chains classify correctly. The `CALL` result register is
   typed `TRef` when this holds.
3. `SscVm` `CALL` (and the `CALLREF` compiled fast path) copy `tlRefReturn` into
   `refStack(dst)` when `callee.retIsRef`, so the ref reaches the caller's ref
   bank. Gated on the callee's *actual* `retIsRef`, never on the predicate.

## Correctness invariant

The caller's destination type (predicate-driven) must agree with the callee's
actual `retIsRef` (which drives the `SscVm` ref-copy). The two are independent
mechanisms, so a mismatch would read a stale/`null` ref. They align because
`isRefReturning`'s method sets are exactly the ones the callee's own builder types
`TRef`; the full interpreter + SscVm + cross-backend suite is the guard against
drift. The conservative direction (predicate says numeric while callee is ref)
merely leaves the original under-optimisation — never a wrong ref read.

## Scope

- Only the SscVm register-JIT path. The Asm/Javac bytecode backends type method
  returns as JVM `Object` and are unaffected.
- The HOF `CALLREF` slow path (interpreter bridge) already handled refs; only its
  compiled fast path needed the same ref-copy.

## Behavior checklist

- [ ] Cross-module `def f(x: String): String = g(x)` returns the String.
- [ ] Transitive `f → g → h` String delegation returns the String.
- [ ] Numeric cross-module delegation still returns the Int (no mis-typing).
- [ ] Full interpreter + SscVm + cross-backend suites stay green.
