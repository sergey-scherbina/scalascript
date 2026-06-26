# Core IR conformance fixtures

The minimal acceptance set for the K1 evaluator ([`../specs/10-core-ir.md`](../specs/10-core-ir.md),
format [`../specs/12-ir-format.md`](../specs/12-ir-format.md)). Each `.coreir` file is a
canonical-S-expr program (pretty-printed across lines + `;` comments — the reader is lenient,
see 12 §reader-leniency). Running `ssc2 run <file>` must produce the expected value.

| Fixture | Exercises | Expected result |
|---|---|---|
| `fact.coreir`   | `If`, recursion via `global`, `i.le`/`i.mul`/`i.sub` | `Int 120` |
| `map.coreir`    | `Match`, `Ctor`, higher-order `App`, de Bruijn shift under a match arm | `Cons 2 (Cons 4 (Cons 6 Nil))` |
| `thunk.coreir`  | nullary `Lam 0` / zero-arg `App` (the laziness mechanism) | `Int 42` |
| `letrec.coreir` | mutually-recursive `LetRec`, `Bool` literals, `i.eq` | `Bool true` |
| `tco.coreir`    | guaranteed proper tail calls — constant stack at 1e6 deep | `Int 500000500000` |

Result rendering (how the evaluator prints a value) is defined by K1; the data shape, not the
exact text, is what conformance fixes. `tco.coreir` is the TCO guarantee (invariant 7): a
non-tail-call evaluator overflows the stack and fails it.
