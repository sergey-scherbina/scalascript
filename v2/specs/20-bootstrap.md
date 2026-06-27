# 20 — Bootstrap & self-hosting

> Status: **SELF-HOSTING REACHED** (2026-06-27). `ssc0c` — the ssc0 compiler written in
> ssc0 — compiled by the Scala front and run on **its own source**, reproduces itself
> byte-for-byte (a stable fixpoint). The Scala front is now an interchangeable bootstrap, not
> a hard dependency.

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
      `int` / `#prim` / parens. `fact.ssc0`, `tco.ssc0` compile **byte-identically** to the
      Scala compiler. Building blocks: the `δ` string prims, `coreir.encode`, `run-ir`.
- [x] **M2 (2026-06-27)** — added `match` / constructor patterns / `Ctor` / `let` / `let rec`
      / string literals. `map.ssc0` and `calc.ssc0` now compile **byte-identically** too.
- [x] **M4 — the fixpoint (2026-06-27): REACHED.** `examples/ssc0c-self.ssc0` (= `lib/ssc0c.ssc0`
      + a `main` that compiles its argv file):
      ```
      gen1 = ssc compile ssc0c-self.ssc0        # the self-compiler, built by the Scala front
      gen2 = run gen1 on ssc0c-self.ssc0         # the self-compiler compiling itself
      gen1 == gen2 == gen3   (20413 bytes, byte-for-byte; gen3 = run gen2 again)
      ```
      The self-compiler reproduces itself exactly — a stable fixpoint. (The VM needs a large
      `-Xss`: the lexer/parser use deep *non-tail* recursion over the input; the bytecode is
      correct, it just needs stack. The `v2/ssc0c` launcher sets `-Xss512m`.)
- [x] **M3 — multi-file imports (2026-06-27): DONE.** `ssc0c` resolves `import "path"` in its
      driver (`bin/ssc0c.ssc0`): a DFS, load-once loader mirroring the Scala `Loader` (entry's
      defs first, then imports'). `parseImports` collects the paths; `parseTop` skips them. A
      multi-file program (`examples/uselib.ssc0` importing `lib/list.ssc0`) compiles
      **byte-identically** to `ssc compile`. And the **multi-file fixpoint**: `bin/ssc0c.ssc0`
      (which imports `lib/ssc0c.ssc0`) compiles *itself* across the import, reproducing itself
      byte-for-byte (22533 bytes). Self-hosting now spans files.

## Why the kernel stays frozen through all of this

`ssc0c` is an ssc0 program. The only kernel facility it needed beyond the existing VM was the
`coreir.encode` primitive (already added for `ssct`). M2–M4 add **zero** kernel lines — they
grow `ssc0c` (ssc0). The trusted base remains the ~913-line Scala kernel; self-hosting is
built on top, in the language itself.
