# ScalaScript 3 — backlog

Work that can wait, and **alternatives that were considered and parked with their trade-offs**
(P-4.2). A parked alternative costs nothing and is there the day it becomes right; the same
alternative held as "I should ask about this someday" is lost at the next reboot.

## ~~v3's parser CONTINUES an expression onto a line starting with `(`~~ — FIXED 2026-08-07

Filed by whoever narrowed the front differential's 74 corpus disagreements, and fixed the same day.
Kept here rather than deleted, because the *shape* recurs and the note that stood here was right
about the direction when the premise on record said otherwise.

The claim in the parser was: "a newline is its own token, so a `(` opening the next line is a new
statement and is not reached here." **False whenever the expression ended with an INDENTED BLOCK** —
closing that block consumes the newline AND the dedent, so the `(` becomes adjacent and `while … do
… ⏎ (0 :: Nil) ++ xs` read as applying the `while`'s result. `parsePostfix` now requires the `(` to
be on the line the expression ENDS on.

Two things went wrong on the way to a three-line fix, and both are worth more than the fix:

- **Identity by reference did not hold.** The end-of-expression line was found by walking the token
  list until it reached the remaining suffix, compared with `eq` — and `dropDedents` removes tokens
  from the middle, so the list is rebuilt and `eq` never matched. The helper returned "unknown"
  every time and, degrading permissively by design, changed nothing at all. The measurement said so
  immediately: the fronts still differed. Identity is now the head token's POSITION, which is unique.
- **A guard maintained only where it is read is wrong everywhere else.** The line was updated in the
  `(` branch alone, so after `Dataset.of(⏎ … ⏎).reduceByKey(a)(b)` it still held the line of
  `Dataset`, three lines up, and a legitimate second argument list was refused. It is updated after
  every postfix step now.

Result: front agreement on the corpus went **74 differing → 0**, 219 of 219.

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
