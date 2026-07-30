# Inbox — my open tasks that are NOT performance

`TASK/v2-perfomance.md` holds the speed work. This file holds everything else I have open, grouped
by the categories I would propose. **I did not assign `b<N>` numbers** — the category policy is
being written and the numbering is not mine to invent. Each group below is a candidate category;
rename, merge or renumber freely.

Every item already exists in a per-module `BUGS.md` / `BACKLOG.md` with the full report. The path
after each title is where the real entry lives; this file is an index, not a second copy.

---

## Candidate category A — silently wrong answers

The worst class in the repo: the program runs, exits 0, and prints something false. No gate catches
these unless a golden happens to cover the shape.

### A-1 · `a(i) = v` is parsed away on the DEFAULT front
`v1/runtime/backend/interpreter/BUGS.md · v2-array-indexed-store-silently-dropped`
**HALF FIXED — do not close.** The legacy front is correct now (`finishAssignment` lowers to
`update`); **F, which is the default, still drops the store**. The array is silently unmodified and
the program prints the wrong number.

### A-2 · `a ++ b` on a user type becomes a STRING
`v2/BUGS.md · v2-infix-extension-operator-stringifies`
**PARTS 1+2 LANDED, part 3 open — do not close.** Correct under `SSC_FRONT=legacy`. On F an infix
operator that resolves to a user `extension` stringifies both operands instead of dispatching.

### A-3 · `x += 1` compiles to a dynamic method call that always throws
`v2/BUGS.md · js-compound-assign-dispatches` — OPEN, filed not fixed. JS codegen.

**Why these three belong together:** same failure mode (wrong output, exit 0), same detection gap,
and two of the three are the same F-vs-legacy asymmetry — worth fixing in one pass.

---

## Candidate category B — front coverage: F accepts less Scala than legacy

F is the default front. Everything it cannot parse is either a delegation to legacy (slow, and
hides the gap) or a wrong answer.

### B-1 · `0d` / `1.5f` — the float literal suffix lexes as an identifier
`v1/runtime/backend/interpreter/BUGS.md · v2-front-drops-float-literal-suffix`
OPEN, **diagnosed and handed over deliberately** — the fix belongs in the front, not where I found
it. This is the "float globals unbound" half of the four dead corpus workloads (see D-2).

### B-2 · curried parameter lists — `def f(a)(b)`
No entry of its own yet; the finding is in `v2/BACKLOG.md`. Flattening curried defs into one arity
was tried and **refuted**: call sites emit `(app (app f a) b)`, so flattening yields
`arity: 2 expected, 1 given`. Nested lambdas are the correct shape; that is the work.

---

## Candidate category C — gates and measuring apparatus

Not a product defect. A gate that is red, blind, or lying is worse than no gate, because work gets
certified against it.

### C-1 · The Core IR parity gate is red on 3 of 4 generators
`v2/BUGS.md · backend-check-mutual-recursion-drops-output` — OPEN, **pre-existing**, found while
verifying an unrelated change. jvm / rust / wasm drop output on mutual recursion.
**Consequence:** no generator change can currently be certified by this gate — including my own. It
should be fixed before, not after, the next codegen slice.

### C-2 · Corpus-contract baseline is stale
Three `wasm-*` rows have IMPROVED since the baseline was frozen, and there are cases in the corpus
with no row at all. Bookkeeping, but it is the kind that makes a real regression look normal.

---

## Candidate category D — bookkeeping / hygiene

### D-1 · My BUGS entries are mis-routed after the per-module split
Four of my reports landed in modules that do not own them:

| Entry | Sits in | Owner should be |
|---|---|---|
| `v2-array-indexed-store-silently-dropped` | `v1/runtime/backend/interpreter/` | v2 front |
| `v2-front-drops-float-literal-suffix` | `v1/runtime/backend/interpreter/` | v2 front |
| ~~`v1-interpreter-hot-path-never-jits`~~ | ~~`v1/runtime/backend/js/`~~ | **moved 2026-07-30** by `room-policy-and-module-table` |
| `v2-lanes-cannot-run-four-corpus-workloads` | `v1/runtime/backend/jvm/` | v2 |

They were filed where the *symptom* surfaced, which is exactly what `specs/work-tracking-layout.md`
routes by. Worth one `git mv`-equivalent pass, but only once the claim policy settles — moving text
between BUGS files touches several module claims at once.

### D-2 · `v2-lanes-cannot-run-four-corpus-workloads` is an umbrella, not a bug
`v1/runtime/backend/jvm/BUGS.md` — OPEN. It is really **two front bugs (A-1 and B-1) plus a bench
wrapper defect that is already fixed**. It should be re-pointed at those two and closed as an
umbrella, so nobody re-investigates it as a fifth thing. **This is what "reproduce a cannot-run
claim outside the harness" caught** — see the house rules in `v2-perfomance.md`.

---

## Claims

I hold **none**, deliberately: Sergiy asked me to drop them and wait for the granular-claim policy
(2026-07-30). `.work/active` has zero entries of mine and my worktrees are removed. Nothing above
is claimed by me — anyone can take any of it.
