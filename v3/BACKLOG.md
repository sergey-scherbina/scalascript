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

**A `v3/tests/front/` FIXTURE CANNOT GATE THIS, and that was measured rather than assumed.** A
second agent wrote one — a `while` body and an `if` body each followed by a line opening a paren —
then planted the defect back to watch it fail. It did not. With `endLineBetween` forced to its
permissive `-1` the tree returns to `(apply (while …) …)` and `(apply (if …) …)`, and the program
still prints `List(0, 1, 2)` and `4`: those fixtures compare program OUTPUT, both parses evaluate to
the same output, so the fixture is green in BOTH states. It was deleted rather than committed as a
passing test that proves nothing.

So the corpus AST differential is the only gate for this class, and that is the argument for its
existence: a difference that changes the TREE and not the ANSWER is invisible to every
output-comparing gate this repository has. `front-diff.sh`'s ceiling of 0 is what holds it.

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

## THE TYPE CHECKER — the decision v3 has not made, framed 2026-08-08

Not a task. A decision, with what is known about it, so that whoever makes it is not starting from a
blank page — and so that nobody makes it by accident while implementing something else.

**What it is gating, measured, not guessed.** `SPRINT.md` SSC3-6 walked the corpus blocker chain by
measurement and ended at a wall rather than another link:

| blocker | corpus cases | why a checker |
|---|---|---|
| `trait` | 137 | needs dispatch, and dispatch needs to know a value's type |
| `[` generics | 36 | |
| `given` / `using` | 2 bench rows | `20-core-language.md`: *"needs type-directed resolution. This is the one item on the list that Tier 0 cannot reach by adding syntax."* |
| type lambdas `[A] =>> …` | 1 bench row | behind generics |

Everything else in that chain fell to one construct each. These do not, and that is the difference
between "the next construct" and "the next decision".

**What makes it a decision rather than a task.** Tier 0's stated bargain is that types are ERASED —
which is why `skipType` discards them, why `asInstanceOf` is the identity in the executor, and why
`Vector` could share `Array`'s representation at all (SSC3-7j). A checker does not add a feature to
that design; it changes what the design IS. Three things follow that nobody should discover midway:

1. **The erasure bargain gets partly taken back.** SSC3-7j is the worked example already on the
   board: `v(i) = x` is accepted on a `Vector` today and a checker must reject it. Every place the
   tier traded checking for simplicity becomes a decision to re-open, one at a time.
2. **Two lanes, one answer.** `exec-gate.sh` requires v3's executor and the v2 bridge to agree. A
   checker that rejects a program v2 accepts is a lane divergence by construction (I-3), so the
   question "what does the bridge do with a program v3's checker refuses" has to be answered in the
   design, not after.
3. **`N` may only rise** (I-5). A checker that refuses previously-accepted programs collides with
   that invariant unless the two are reconciled deliberately.

**What can be measured BEFORE deciding, and has not been.** All of it is a day or less and none of
it commits anything:

- run the corpus with `trait` parsed-but-undispatched, to separate "needs a checker" from "needs
  dispatch" — they are assumed to be the same question and have not been shown to be;
- count how many of the 137 `trait` cases use dispatch at all, versus a trait as a bare namespace;
- take the `given` rows apart: `typeclass-monoid` may only need a value resolved by NAME, which is
  not type-directed resolution and would not need a checker.

Doing that measurement first is what SSC3-6 did for the chain, and it is why the chain's answers
were repeatedly not the ones anyone predicted — link 6 being the standing example, where 116 of 126
cases in one symptom bucket were a construct nobody guessed from the message.

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
