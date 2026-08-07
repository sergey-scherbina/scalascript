# ScalaScript 3 — backlog

Work that can wait, and **alternatives that were considered and parked with their trade-offs**
(P-4.2). A parked alternative costs nothing and is there the day it becomes right; the same
alternative held as "I should ask about this someday" is lost at the next reboot.

## v3's parser CONTINUES an expression onto a line starting with `(` — and UniML is the one that is right

Found 2026-08-07 by `v3/front-diff.sh` while narrowing its 74 corpus disagreements, and the
direction is the surprise: `40-front-on-uniml.md` §5b's hand-over said "all 11 remaining
differences are on this side", meaning UniML's. This one is v3's.

    def f(): List[Int] =
      var r = 1 :: 2 :: Nil
      var acc: List[Int] = Nil
      while r.nonEmpty do
        acc = r.head :: acc
        r = r.tail
      (0 :: Nil) ++ acc.reverse

The last line starts with `(` at the SAME column as the `while`, so it is a sibling statement and
the function's result. The reference front agrees — it prints `List(0, 1, 2)`.

    uniml   (do (while …)) then (bin "++" …)          two statements — matches the reference
    v3      (bin "++" (apply (while …) …) …)          applies the `while`'s result to `(0 :: Nil)`

Applying the result of a `while` is not a thing the language has, which is the corroboration: v3 is
continuing the expression across a newline because the next line opens a paren, where Scala's
offside rule ends the statement at equal indentation.

**Why it matters more than one construct.** It is the second of the two differences in
`scljet/sql.ssc`, and that one file is imported by all 74 disagreeing corpus cases. The other was
UniML's — an `else` binding to an `if` inside a block it closes, fixed in `16ef08948`, which took
`scljet-jdbc-basic` from 8 differing lines to 2. `front-diff.sh` counts FILES, so **the corpus
number cannot move from 145/74 until this one lands too**; the fix and the number are one construct
apart.

Filed here rather than fixed: `v3/src/Parser.scala` is v3's own, and the front differential's
premise — that the remaining differences are UniML's — needs correcting along with it.

## v3 carries its own copy of the character alphabet — decide, do not drift

Left open deliberately when `specs/20-core-language.md` §3 was corrected (`41534ad3c`,
`c42173618`). §3 bans baked tables; Sergiy adopted a Unicode CASE table on 2026-08-05 after the
tableless answer was implemented, measured and rejected on the measurement, and UniML now ships it
as a shared module. **v3 keeps a separate copy in `Chars`.**

Two acceptable outcomes, and "leave it" is not one of them:

- adopt the shared module, or
- record that two copies are intentional, and state **what they must agree on** and what checks it.

Two copies agreeing only by memory is the shape that has cost this repository repeatedly — the
version coordinate is on its fourth declaration
(`sbt-plugin-version-and-the-coordinate-templates-emit-disagree`, `tests/BUGS.md`).

An identifier alphabet that disagrees between the front and the language it compiles does not
produce a build error; it produces two parses of one file.

## Parked design alternatives

- **Flat basic blocks + SSA instead of structured regions.** Rejected for
  [`specs/10-ssc-ir.md`](specs/10-ssc-ir.md) §2: source-language output is a stated requirement, and
  recovering loops from a block graph is the relooper problem. Becomes right if v3 ever grows an
  optimizer whose passes genuinely need SSA and dominance — the conversion is then a *pass* on a
  module, not a change of the canonical form. Note the cost honestly: some classical optimizations
  (global value numbering, aggressive code motion) are meaningfully harder on regions.

- **A stack machine instead of a register machine.** More compact encoding and a simpler emitter;
  rejected because interpretation pays push/pop per operand and native lowering needs registers
  anyway. Reconsider only if `.ssirb` size ever becomes a real constraint.

- **Typed IR.** `kind` currently carries only what the front proved. A fully typed IR would let the
  verifier reject far more, and would let backends emit unboxed primitives directly. Blocked on
  v3 having a type checker at all; the `kind` field is the forward-compatible seam.

- **Reusing v2's Core IR so v3 inherits its backends.** Rejected: it would tie a linear register IR
  to a term tree and give up the property the version exists for. The backends are re-earned from
  the new IR instead — cheaply, because structured control flow makes source emission direct.

## Deferred capability

- Separate compilation — a module is a whole program in v1 of the IR.
- Garbage collection — values are host objects.
- Effects and handlers in the **front**. The IR reserves `Perform` / `Handle` / `Resume`, so this is
  a front gap rather than a representation gap; that is the whole reason they are in the instruction
  set from day one.
- Tier 2 language surface: implicits and `given`/`using`, macros, typeclass derivation
  ([`specs/20-core-language.md`](specs/20-core-language.md) §2).
- `.ssirb` binary form — `.ssir` text is canonical and sufficient until artifact size or load time
  is measured to be a problem.
- Backends beyond the executor: JVM bytecode, JS, Rust, Swift source. Each is a `Module → Artifact`
  function; none is scheduled until the IR has stopped moving.
