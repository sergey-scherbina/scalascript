# ScalaScript 3 — backlog

Work that can wait, and **alternatives that were considered and parked with their trade-offs**
(P-4.2). A parked alternative costs nothing and is there the day it becomes right; the same
alternative held as "I should ask about this someday" is lost at the next reboot.

## The two fronts disagree on WHERE a by-name argument becomes a thunk — 29 corpus cases

Measured 2026-08-08 **on `origin/main` in a clean worktree**, before any of my own uncommitted work:
`front-diff.sh` reads 263 corpus cases printed by both fronts, 234 agreeing and **29 differing**,
and the gate is RED. It is not a regression from the boxing or infix work in the same session —
that tree measures the same 29.

    v3     (call "runActors" (block …))
    uniml  (call "runActors" (lam (params) (block …)))

UniML's projection emits the THUNK; v3's own front passes the block eagerly and `rewriteByName`
wraps it in the lowering. Both readings can be made to work, but they are two places, and a feature
implemented in two places is two implementations that will disagree — which is what the differential
is reporting.

**Worth checking before choosing a side:** if `rewriteByName` wraps an argument that UniML has
ALREADY wrapped, the uniml lane gets a thunk of a thunk, and that is a wrong answer rather than a
printed difference. All 29 are `actors-*` cases and every one is currently UNSUPPORTED for other
reasons, so the corpus cannot see it either way yet.

Owned by `ssc3-effect-protocol`, which holds `v3/src/Lower.scala` and landed the by-name rewrite.
Filed here rather than fixed because choosing where by-name lives is that claim's decision.

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
