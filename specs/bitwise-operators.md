# Bitwise operators on Int (Specification)

Status: **shipped** (interpreter backend). Example: [`examples/bitwise-operators.ssc`](../examples/bitwise-operators.ssc).
Test: `BitwiseTest` in the `backendInterpreter` module.

---

## 1. Goal

Expose the standard integer bitwise operators so `.ssc` code can do bit math
(masks, shifts, GF(256)/Reed–Solomon for QR payloads, flags, hashing) without
reaching for arithmetic emulation (`div`/`mod` by powers of two).

## 2. Operators

`Int` is `Long`-backed in the interpreter, so each operator maps to the host
`Long` operation and results are 64-bit. Use `& 0xFF` (etc.) for byte semantics.

| Operator | Meaning | Example | Result |
|---|---|---|---|
| `a & b`   | bitwise and            | `0xF0 & 0x0F` | `0`   |
| `a \| b`  | bitwise or             | `0xF0 \| 0x0F`| `255` |
| `a ^ b`   | bitwise xor            | `0xF0 ^ 0xFF` | `15`  |
| `a << b`  | shift left             | `1 << 4`      | `16`  |
| `a >> b`  | arithmetic shift right | `256 >> 2`    | `64`  |
| `a >>> b` | logical shift right    | `255 >>> 4`   | `15`  |
| `~a`      | bitwise not (unary)    | `~0`          | `-1`  |

## 3. Implementation

These are not classified as arithmetic operators (`PatternRuntime`'s arith set is
`+ - * / % < > <= >=`), so `a & b` parses as the method call `a.&(b)` and is served
by `DispatchRuntime.dispatchInt` alongside the existing bit helpers (`toBinary`,
`toHex`, `isEven`, `isOdd`). Both operands must be `Int`; any other right-hand
operand falls through to the normal dispatch (extensions / error), unchanged.

Because they ride the general method-dispatch path (not the fast arith fold), they
work uniformly in plain expressions and compiled function bodies on the interpreter.

## 4. Scope / follow-ups

- **Interpreter backend only.** Fast-path folding (`numericFast`) and codegen
  backends (JS/Rust/WASM/JVM) are a separate follow-up: JS/Rust/WASM have native
  bitwise operators, but emitting them needs the operators added to each emitter's
  operator table and to `PatternRuntime`'s arith classification. Tracked in `SPRINT.md`.
- **BigInt bitwise** (`&`/`|`/`^`/`testBit`/`setBit`) is likewise not yet exposed;
  add to `dispatchBigInt` if a use-case appears.
