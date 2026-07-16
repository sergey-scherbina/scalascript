# Enum Value Support (Specification)

Status: **implemented**. Tracked as `enum-value-support` in `BACKLOG.md` /
`WORK_QUEUE.md`.

---

## 1. Goal

Make Scala 3 `enum` declarations fully usable as **values** in ScalaScript —
referencing cases, matching on them, and listing them with `EnumName.values` —
identically on the interpreter, JVM, and JS backends.

```scala
enum Side:
  case Debit, Credit

def label(s: Side): String = s match
  case Debit  => "Dr"
  case Credit => "Cr"

label(Debit)        // "Dr"
Side.Credit         // qualified reference
Side.values.length  // 2
```

## 2. The gap

`enum` *parsed* (scalameta Scala 3 dialect) but case **values** didn't resolve:

- A comma-separated case line `case Debit, Credit` parses as a single
  `Defn.RepeatedEnumCase`, **not** individual `Defn.EnumCase`s. Both the
  interpreter (`StatRuntime`) and JS codegen (`JsGen`) matched only
  `Defn.EnumCase` and silently dropped the repeated form — so `Debit` /
  `Side.Debit` were undefined.
- JS codegen emitted only the case constants, never an `EnumName` companion, so
  `EnumName.values` and qualified `EnumName.Case` were undefined there.
- The JVM backend (which emits native Scala) already handled enums.

Single-line parameterless cases (`case Debit`) and parametrized cases
(`case Circle(r: Int)`) already worked.

## 3. Design

A parameterless case is a **singleton value**; a parametrized case is a
**constructor**. Each enum also exposes a companion with its cases and a
`values` list (the parameterless cases, in declaration order).

| Form | Binding |
|---|---|
| `case Debit` / `case Debit, Credit` | singleton value, reachable bare (`Debit`) and qualified (`Side.Debit`), matchable |
| `case Circle(r: Int)` | constructor function returning a tagged instance |
| `EnumName.values` | list of the parameterless cases, in order |

Bare exposure is a convenience and does not displace the core ADT bindings
`None`, `Some`, or `Nil`. An enum case using one of those names remains
available through its companion (`DataClass.None`, for example). This keeps
Option/List construction deterministic inside imported modules while preserving
the enum's full qualified API.

Representations:
- **Interpreter:** nullary case → `InstanceV(caseName, Map.empty)`; bound in the
  env (bare, except for the core-ADT collision rule above) and in the enum
  companion's fields (qualified); `parentTypes` links case → enum for matching.
  `values` → `ListV` of the singletons.
- **JS:** nullary case → `const Case = {_type:'Case'}`; a companion
  `const Enum = { Case: Case, …, values: [...] }`.
- **JVM:** native Scala `enum` — unchanged.

## 4. Verification

- Interpreter tests: repeated/single/parametrized cases, bare + qualified
  references, matching, `values`, a busi-style `Element`/`Side` domain, and an
  imported module whose `DataClass.None` coexists with Option `None`.
- Cross-backend conformance: the same enum program runs on the interpreter, JVM
  (scala-cli), and JS (node) with byte-identical output.
- No regression in the existing suite (1167 green).

## 5. Out of scope

- `ordinal` / `valueOf` / `fromOrdinal` enum helpers (add when needed).
- Typer-level exhaustiveness checking for enum matches (the typer stays
  permissive; runtime semantics are the contract here).
