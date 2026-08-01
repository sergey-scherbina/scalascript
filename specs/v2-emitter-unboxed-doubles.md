# v2 emitter — unboxed doubles past the match boundary

**Status:** spec / design (no code yet). Written 2026-08-01, out of
[`v2-wide-jit.md`](v2-wide-jit.md) §9, which finished the JIT work and named this as the thing that
remains. **This is not a JIT spec** — it is about `JvmByteGen`, and every change lands in the AOT
lane and the JIT at once, because they share the walker.

## 1. The measurement that motivates it

After the wide-JIT programme, three of the four representative rows sit at **1.00–1.04× of the AOT
lane** — there is nothing left for a JIT to win on them. The fourth does not, and the reason is not
the JIT:

| row | JIT | AOT | v1 (`ssc`) | AOT vs v1 |
|---|---:|---:|---:|---:|
| `arith-loop` | 0.596 | 0.565 | 0.243 | 2.3× |
| `recursion-fib` | 1.21 | 1.15 | 1.17 | 0.98× |
| `recursion-tco` | 0.0271 | 0.0241 | 0.029 | 0.83× |
| `pattern-match-heavy` | 10.7 | **8.5** | **0.052** | **163×** |

On `pattern-match-heavy` the **AOT lane itself** is 163× off v1's JIT'd interpreter. Closing the
JIT's remaining 1.28× would leave that row two orders of magnitude behind, so the limit is the code
the emitter produces, not when it produces it.

## 2. The defect, precisely

`JvmByteGen.canDouble` — the predicate deciding whether a term can be computed in unboxed `double`
— accepts exactly three shapes:

```scala
case Term.Lit(Const.CFloat(_))                     => true   // a literal
case Term.Prim("dcell.get", List(Term.Local(_)))   => true   // a double cell read
case DArithB(op, a, b) if "+-*/".contains(op)      => canDouble(a) && canDouble(b)
case _                                             => false
```

The workload is:

```scala
def area(s: Shape): Double = s match
  case Circle(r)      => 3.14159 * r * r
  case Rect(w, h)     => w * h
  …
```

`r`, `w`, `h` are **`Local`s bound by a match arm**, and that shape is not in the list. So every
multiplication falls to the boxed `Emit.arith`, allocating a `FloatV` per operation, 500 000 times.
v1's JIT keeps the same arithmetic in unboxed `double` registers — hence 163×.

**Two adjacent holes of the same kind**, worth fixing together because they share the mechanism:

- **no `canParamDouble`.** `canParamLong` lifts an all-`Int` def onto a `$long(J…)J` entry behind an
  `INSTANCEOF IntV` guard. There is no `$double` twin, so a `def f(x: Double): Double` is boxed
  end to end even when every operation is arithmetic.
- **no unboxed return.** A `Double`-returning def re-boxes at every `RET`, so a caller that
  immediately unboxes pays for a `FloatV` that exists for one instruction.

## 3. Mechanism — a runtime guard, not a type system

Core IR is untyped and F types only `Int | String | BigInt` (`v2/SPRINT.md` VC-2c), so "this Local
is a Double" cannot be proven statically today. It does not have to be: **the Long path already
solves this exact problem at run time** — `canParamLong` emits `INSTANCEOF IntV` per parameter and
falls to the generic boxed body when the guard fails.

The same shape applies here:

1. `canDouble` accepts a match-bound `Local` **when the arm's emitted code has already established
   the field is a `FloatV`** — the arm knows the constructor it matched, so the guard is one
   `INSTANCEOF` per bound field, hoisted to the top of the arm, not per operation.
2. On guard failure the arm runs its existing boxed body. No new semantics, and no case where a
   program computes a different answer — the same rule that makes the Long path safe.
3. `canParamDouble` + a `$double(D…)D` entry mirrors `canParamLong` exactly.

**Why this is the right layer:** both lanes share `JvmByteGen`, so this lands in `--bytecode` and in
the JIT in one commit. That was the argument for one walker, and this is the first slice that
collects on it.

## 4. Slices

| id | what | gate |
|---|---|---|
| **E-0** | Baseline + a JFR allocation profile of `pattern-match-heavy` on the AOT lane, to confirm `FloatV` is the dominant allocation rather than assuming it from the code read. | the profile, recorded in §6 |
| **E-1** | `canDouble` accepts a match-bound `Local` under an arm-level `INSTANCEOF FloatV` guard. | `pattern-match-heavy` on **both** lanes; byte-identical output; conformance |
| **E-2** | `canParamDouble` + the `$double(D…)D` entry, twin of `canParamLong`. | `float-loop`, `float-fold`; the guard proven live by a rename probe |
| **E-3** | Unboxed `Double` return where the callee and caller are both compiled. | the same rows; no regression on `arith-loop` |

Each slice must show the number on **both** lanes — a change that helps the JIT and not the AOT lane
means it was made in the wrong place.

## 5. Non-goals

- Typing Core IR. This is a guard-based unboxing pass; the typed-IR route is `v2-f5c-typed-bytecode`
  and is orthogonal.
- Touching the VM lane's interpreter. It stays boxed; the JIT is how the VM lane gets this.
- `pattern-match-heavy` reaching v1. 163× will not close in one slice, and claiming a target this
  spec cannot hold would make the gates meaningless.

## 6. Results

*(empty — E-0 fills it)*
