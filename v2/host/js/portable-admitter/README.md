# @scalascript/portable-admitter

A **non-JVM admitting backend** for ScalaScript Portable capsules — the second runtime conformance
vector 15 (`cross-host-resume`, §14.4 N→M) requires. It admits and runs a capsule whose resume
program travels as closed CoreIR bytes, holding **no machine** and no source.

```js
import { admit } from "@scalascript/portable-admitter"
import { evaluate } from "@scalascript/portable-admitter/eval.js"

const capsule = admit(bytes, { key, audience, tenant })  // inert: never runs the program
const result = evaluate(capsule.program, capsule.frame, 3n)
```

## What it deliberately reproduces rather than reinterprets

A second admitter that is more permissive than the first proves nothing about parity — it hides the
divergence. So each of these is copied from the JVM lane, not paraphrased:

- the two domain separators (`ssc-portable-capsule-v1\0` for the code digest,
  `ssc-portable-capsule-sig-v1\0` for the keyed signature);
- the signing message: the canonical body with an **empty signature slot**, so an edit to any field
  — including the frame — breaks the HMAC;
- the admission ORDER (`specs/portable-capsule-seal.md` §5), all fail-closed;
- `validate`'s scope rules — a `(local i)` in range, a `(global g)` that is a def of the same program
  or an `@`-cell, with no builtin escape hatch;
- `validateFrame`: a frame is DATA — literals and constructors, recursively. `lam` is refused too,
  because a lambda is a value at runtime but **code** in the bytes.

## Two properties worth stating

**`Int` is 64-bit.** Every integer is a `BigInt`, never a JS `number`, and arithmetic wraps
two's-complement. Using `number` diverges at 2^31, max64 and 2^53+1 — the exact class that once
reverted the bytecode-default switch (`specs/v2-f5c-typed-bytecode.md` §8). The tests pin all three
against values the JVM produced on the same bytes.

**Unknown nodes are errors.** The evaluator covers the node set a reified resume program uses and
refuses anything else. An admitter that quietly mis-evaluates a node it does not really support is
worse than one that refuses it.

## Testing

`npm test`. Every fixture in `test/fixtures/` was **frozen by the JVM lane** and is committed here.
That is the point: an admitter tested against capsules it produced itself is a self-consistent
oracle and cannot observe a cross-lane divergence.
