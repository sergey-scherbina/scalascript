# `package:` must bind a namespace on the native lane

**Status:** designed, not implemented. Entry: `v2/BUGS.md`
`native-front-has-no-package-namespace`. Gate: `tests/e2e/package-keyword-smoke.sh` (currently RED,
which is correct — it is the acceptance test).

## The defect

A module declares a package in its front matter; a consumer imports it and calls through the
package name.

```
--- cards.ssc                        --- consumer.ssc
package: org                         [org](./cards.ssc)
---
def ui(s: String): String =          def main() =
  "ui-" + s                            println(org.ui("card-hi"))
```

```
ssc-tools run --v1   ->  ui-card-hi
ssc run              ->  ssc: unbound global: org
```

Both lanes splice a module's definitions FLAT — unqualified `ui("x")` works on both, checked
explicitly. What v1 has in addition is the package as a NAMESPACE
(`Interpreter.scala:876,910,1643`: `modulePkg` / `exportedPkg` from `manifest.flatMap(_.pkg)`). The
native lane has no such binding: `package` appears nowhere in `v2/src`, and the tower's loader
(`sscLoadMod`, `v2/bin/ssc1-run.ssc0:474`) takes `sscDefsOnly(parse(modSrc))` and appends the
definitions to the caller's scope. There is no object for `org.` to select from, which is why the
error is `unbound global` and not `no method ui`.

## Why this sat unstarted, and what closed it

The entry deferred the work as a semantic choice rather than a difficulty: a namespace has to come
IN ADDITION to the flat splice, because both lanes rely on the flat names. The same definitions
would then be reachable by two paths — and *"for anything holding state that is a decision about
identity, not a patch"*.

**Measured 2026-08-05 on the native lane, and the language already answers it:**

```scalascript
def ui(s: String): String = "ui-" + s
object org:
  def ui(s: String): String = ui(s)      -- SAME name
println(org.ui("x"))                     -- ui-x, NOT infinite recursion
```

1. A member of an `object` can call a top-level `def` — `org.ui2("x")` → `ui-x`.
2. At the **same name** it still resolves OUTWARD, so `def ui = ui` inside `object org` is an
   ALIAS, not a self-call. That was the whole difficulty.
3. This lane keeps object-level mutable state correctly — `object org: var hits` gives `1 2 2`,
   where `int` gives `1 1 0` (`int-object-var-mutation-does-not-persist`, a separate defect on a
   separate lane). So the identity question is not merely answerable here, it is already answered
   in the direction a namespace needs.

**Therefore: one definition, two names, no copy.** There is no identity decision left to make.

## Design

Keep the flat splice exactly as it is. Emit, alongside it, a synthetic alias object:

```
object <pkg>:
  def <name>(<params>) = <name>(<params>)     -- one per exported def
```

Every member forwards to the top-level definition the flat splice already produced, so the two
paths reach the same closure and — for a `var` — the same cell.

## Implementation

All in the tower. `v2/bin/ssc1-run.ssc0`:

```
def sscLoadMod = (rawPath, seen) =>
  …
  let defs = sscDefsOnly(parse(modSrc)) in
  match sscLoadImps(…) { case Pair(impDefs, seen2) => Pair(sscApp(impDefs, defs), seen2) }
```

1. **Read the package name.** `collectFrontmatter` (`v2/lib/mira-md.ssc0:168`) returns the
   front-matter TEXT as `FrontmatterText(...)`; scan it for a `package:` line. `frontmatterLines`
   and `skipYaml` (same file, :150/:162) are how `sscProgramSource` already reaches it.
2. **Collect the exported def names and their parameter lists** from `modSrc`. A line scan is
   idiomatic here — `sscImports`/`sscScanLines` (:405) already scan the source this way — and it
   avoids reconstructing them from AST nodes.
3. **Build the alias source as TEXT and parse it**, then `sscDefsOnly` it and `sscApp` it onto the
   list `sscLoadMod` already returns. Emitting source and re-parsing is far less code than
   constructing AST nodes, and it is the same construct the measurement above proved.

## Limits, named rather than left to be found

- **The alias is per `def`.** `org.hits` exposed as `def hits = hits` READS the live cell;
  `org.hits = 5` would not write it. Whether package-qualified assignment must work is a smaller,
  separate decision than the one this spec closes — do not let it hold up the rest.
- **Filter to what is actually exported.** A module's `exports:` surface, where declared, gates the
  members; without it, top-level defs.
- **Nested packages** (`package: a.b`) are out of scope for the first slice. Say so in the entry if
  the first implementation only handles a single segment.

## Acceptance

- `tests/e2e/package-keyword-smoke.sh` goes GREEN. It is red today, and it is the gate named on the
  entry — no new gate is needed.
- The entry's own two-file repro prints `ui-card-hi` on `ssc run`, matching `ssc-tools run --v1`.
- **Unqualified names keep working**, on both lanes: that is the regression this design exists to
  avoid, and it is the one a namespace-INSTEAD-of-splice implementation would break.
- One identity check, since it is the question that blocked this: mutate through the namespace path
  and read through the flat one (or the reverse) and get the same cell.

## A build hazard specific to this file

`v2/bin/ssc1-run.ssc0` is part of the self-hosting tower. On 2026-08-04 a single extra closing paren
in a sibling tower file made the compiled front emit `_err` for EVERY program — a plain
`def f(a: Int) = a + 1` stopped lowering — and the symptom pointed nowhere near the edit. It was
found by diffing the file against a reconstruction built edit-by-edit.

Two things make that cheap to avoid:

- **Count the parens of any line you touch** before building.
- **The tower is READ AT RUN TIME from `bin/lib/native-front/tower/`**, so a candidate can be tested
  by writing it into BOTH `bin/lib/native-front/tower/bin/ssc1-run.ssc0` and
  `bin/lib/standard/native-front/tower/bin/ssc1-run.ssc0` and re-running — seconds instead of a
  ~7-minute rebuild per iteration. Note that `git stash` of `v2/` does NOT revert those copies, and
  a real `./install.sh --dev` overwrites them from source, so the final verification must be a
  genuine rebuild.
