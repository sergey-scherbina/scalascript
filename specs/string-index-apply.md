# `String` index-apply `s(i)` — return the i-th char

**Status**: ✓ Landed 2026-06-09 (interpreter `CallRuntime`). Reported by busi, 2026-06-09.
**Priority**: medium. Workaround exists (`s.drop(i).head`), but `s(i)` is standard
Scala and its absence silently breaks code that looks correct.

## 1  Symptom

Indexing a `String` with apply syntax throws instead of returning the character:

```scalascript
val s = "0515C1234"
s.head        // '0'   — works
s.drop(4).head// 'C'   — works
s(4)          // ERROR: Not callable: 0515C1234
```

The interpreter treats `s(4)` as applying the string as a function. In Scala,
`s(i)` is `s.apply(i): Char` (i.e. `charAt`).

## 2  Expected behavior

`s(i)` returns the `Char` at index `i`, matching Scala:

- `"abc"(0) == 'a'`, `"abc"(2) == 'c'`.
- Out-of-range index throws `IndexOutOfBoundsException` (or the runtime's
  equivalent), not a misleading "Not callable".
- Consistent with `List`/`Map`/`Set` apply, which already work — `String` is the
  outlier.

## 3  Implementation

In the call/apply dispatch (`CallRuntime.callValue`), when the callee is a
`StringV` and there is one integer argument, return the char at that index (as a
`CharV`) instead of failing the callable check. Mirror the existing
index-apply handling for `ListV`.

## 4  Behavior checklist

- [x] `"abc"(1)` returns `'b'`.
- [x] `s(i)` inside an `if`/`&&` expression evaluates lazily as today (no change
      to short-circuit semantics).
- [x] Negative / out-of-range index raises a clear index error, not "Not callable".
- [x] Existing `List`/`Map`/`Set` apply unaffected (regression guard).

Done in `StringIndexApplyTest` (interpreter). **JS/Node + JvmGen + Rust** still treat
`s(i)` per their own apply paths — if a backend other than the interpreter needs
`s(i)`, extend there as a follow-up (busi's `parseMt940Tx` runs on the interpreter).

## 5  Verification

`StringIndexApplyTest`: positive indices, boundary, out-of-range, and use inside a
boolean expression. Re-run busi's MT940 parser path (`parseMt940Tx`) with the
`s(i)` form restored.

## 6  busi context

busi's `domain/polish_bank.ssc` `parseMt940Tx` used `afterDate(4)` / `afterDate(5)`
to detect the credit/debit mark in an MT940 `:61:` line that carries a booking
date — the **common** bank statement format. This crashed with "Not callable";
`phase60`'s test fixtures omitted the booking date, so the bug stayed latent until
the phase85a bank-statement fidelity test exercised the realistic format. busi
worked around it with `.drop(n).head`; this spec removes the foot-gun.
