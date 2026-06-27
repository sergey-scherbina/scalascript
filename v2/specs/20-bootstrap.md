# 20 — Bootstrap & self-hosting

> Status: **in progress** (2026-06-27). `ssc0c` — the ssc0 compiler written in ssc0 — exists
> and, on the subset it supports, emits **byte-identical** Core IR to the Scala compiler.

## The differential invariant

There are two compilers from ssc0 source to Core IR bytecode:

- the **Scala** front in the kernel — `ssc compile X.ssc0` (`Lexer`/`Parser`/`Lower` in
  `v2/src/Ssc0.scala`), and
- **`ssc0c`** — the same pipeline written *in ssc0* (`v2/lib/ssc0c.ssc0`), run on the VM
  (`ssc run bin/ssc0c.ssc0 X.ssc0`, or `./ssc0c X.ssc0`).

The bootstrap invariant is that they agree, byte for byte, on every program:

```
ssc compile X.ssc0   ==   ssc0c X.ssc0        (canonical Core IR text, identical bytes)
```

Because the IR is canonical (specs/12), this is a plain string compare — a permanent CI
check that two independent compilers (one Scala, one ssc0) produce the same semantics. It is
the v2 form of the "seed as an independent oracle" idea (decision D4): the kernel's compiler
and the in-language compiler keep each other honest.

## Milestones

- [x] **M1 (2026-06-27)** — `ssc0c` subset: `def` / lambda / `if` / application / `var` /
      `int` / `#prim` / parens. `fact.ssc0` and `tco.ssc0` compile **byte-identically** to the
      Scala compiler (`conformance/check.sh`), and the ssc0c-emitted bytecode runs on the VM
      (`fact => 120`). Building blocks: the `δ` string prims, `coreir.encode`, `run-ir`.
- [ ] **M2** — add `match` / constructor patterns / `Ctor` / `let` / `let rec` / string
      literals to `ssc0c`. Target: `map.ssc0`, `calc.ssc0`, `lib/list.ssc0` compile identically.
- [ ] **M3** — multi-file `import` resolution in the `ssc0c` driver (read + merge, like the
      Scala `Loader`).
- [ ] **M4 — the fixpoint.** `ssc0c` written in the subset it supports, compiling **its own
      source**:
      ```
      gen1 = ssc compile ssc0c.ssc0        # the self-compiler, built by the Scala front
      gen2 = run gen1 on ssc0c.ssc0         # the self-compiler compiling itself
      assert gen1 == gen2                    # self-hosting fixpoint
      ```
      After M4 the Scala front is no longer in the trusted path for ssc0 → ir; `ssc0c` is.

## Why the kernel stays frozen through all of this

`ssc0c` is an ssc0 program. The only kernel facility it needed beyond the existing VM was the
`coreir.encode` primitive (already added for `ssct`). M2–M4 add **zero** kernel lines — they
grow `ssc0c` (ssc0). The trusted base remains the ~913-line Scala kernel; self-hosting is
built on top, in the language itself.
