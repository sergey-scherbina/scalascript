# `try/catch` with supertype patterns

Source: busi (rozum `scalascript` room, 2026-06-11).

## Symptom

A ScalaScript `try/catch` did not catch a Java exception thrown by an extern /
runtime op when the catch used a supertype pattern:

```scalascript
try aesCbcDecrypt(key, iv, ct)
catch case e: Any => "decrypt failed"   // never fired → padding error escaped
```

The exception (e.g. `BadPaddingException`, `ArithmeticException`) propagated past
the `catch`, so `try` around an extern offered no protection.

## Root cause

`Term.Try` evaluation already catches any JVM `Throwable` and synthesizes a
`Value.InstanceV(<jvm-exception-simple-name>, {"message": …})`, then matches it
against the catch patterns. But `PatternRuntime.matchPat` for a typed pattern
(`Pat.Typed`, i.e. `case e: T`) only matched when the InstanceV's `typeName`
equalled `T` (or a registered `parentTypes` chain). So:

- `case e: Any` — `typeName` is `"BadPaddingException"`, never `"Any"` → no match.
- `case e: Throwable` / `Exception` / `RuntimeException` — likewise never matched,
  because the JVM exception's simple name isn't in any user `parentTypes` chain.

→ no catch arm matched → the original exception was rethrown.

## Fix

`PatternRuntime.matchPat` `Pat.Typed`:

1. **`Any` / `AnyRef`** are universal supertypes — match any scrutinee
   (the correct top-type semantics; also makes `catch case e: Any` fire).
2. **`Throwable` / `Exception` / `RuntimeException` / `Error`** match any
   `InstanceV` (`isExceptionSupertype`). A `catch` scrutinee is a synthesized
   exception carrying an arbitrary JVM throwable's simple name; users catch it by
   supertype without knowing the concrete class.

Specific user types still discriminate precisely (`case c: Circle` only matches a
`Circle`) — only the universal / exception supertypes are broadened. The catch
path uses `matchPat` directly, so no codegen / fast-path changes are needed.

## Scope

- Interpreter pattern matching (`matchPat`), which backs `try/catch` arm
  matching. JS/JvmGen lower their own try/catch and are unaffected by this seam.

## Behavior checklist

- [ ] `catch case e: Any =>` catches a runtime-thrown Java exception.
- [ ] `catch case e: Throwable =>` / `Exception =>` catch it too, with `e.message`.
- [ ] A normal typed match on a user ADT still discriminates by type.
