# `package:` must bind a namespace on the native lane

**Status:** in progress (`native-package-namespace-impl`). Entry: `v2/BUGS.md`
`native-front-has-no-package-namespace`. Gate: `tests/e2e/package-keyword-smoke.sh` — its INT row is
the acceptance test; its other two rows carry separate defects (see §Acceptance).

**The design in §Design is the SECOND one.** The first is kept, with the measurement that killed it,
because it was killed by a property that holds on one front and not the other — the kind of thing
that is cheap to re-propose and expensive to re-discover.

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

## The design this spec first proposed, and the measurement that killed it (2026-08-05)

The first draft said: keep the flat splice, and emit `object <pkg>: def <name> = <name>` beside it —
one member per exported def, at the SAME name, relying on the probe above showing that a same-named
member resolves OUTWARD to the top-level def rather than recursing.

**That probe was run on ONE front.** Re-run on both:

| source | `SSC_FRONT=F` | `SSC_FRONT=legacy` |
|---|---|---|
| `object ns: def ui = ui` | `ui-hi` | **`unbound global: ns_ui`** |
| `object ns: def ui(s) = ui(s)` | `ui-hi` | **hangs — infinite recursion** |
| `object ns: def render = Card.render` | `ui-card-hi` | `ui-card-hi` |

So "a same-name member resolves outward" is an **F-only** property; the reference front resolves it
INWARD, and the failure it produces is a hang. Building the namespace on it would have shipped a
feature that works until a file is big enough for F to decline it — the shape recorded as
`two-fronts-disagree-on-name-resolution`. The third row is the one both fronts agree on: an alias
whose right-hand side names something that is **not** a member of the enclosing object.

## Design

Two facts about this lane decide the shape. Both were measured, not assumed:

1. **`object O: def f(x)` lowers to the global `O_f`** (`v2/lib/ssc1-lower.ssc0:4470` `prefixDefs`),
   and `O` is registered in `kc7bObjectsCell` by a PRE-PASS (`collectObjects`, :5202) — so
   declaration order does not matter.
2. **A dotted selection is resolved by joining segments with `_` and requiring EVERY prefix to be a
   registered object.** `object org_example: def g` makes `org.example.g()` work *only* if `org` is
   also a registered object; without it the error is `unbound global: org`. A nested `object` inside
   an object body is DROPPED by `prefixDefs`, which is why `object a: object b: def f` gives
   `unbound global: a_b`.

Therefore a package `a.b.c` is expressed as a CHAIN of flat objects, and every alias goes through a
top-level indirection so no member ever shadows its own right-hand side:

```
def __pkgref_a_b_c__ui = ui          -- top level: `ui` cannot be a member here
object a:        def __pkg = 0       -- registration stub for the prefix
object a_b:      def __pkg = 0
object a_b_c:    def __pkg = 0
                 def ui = __pkgref_a_b_c__ui
object a_b_c_Card:
                 def render = Card.render   -- a member of an EXPORTED OBJECT
```

The flat splice is untouched, so `ui("x")` keeps working. Every namespace member is a value alias of
the one definition the flat splice produced — one definition, two names, no copy.

## Implementation

All in the tower, `v2/bin/ssc1-run.ssc0`, inside `sscLoadMod`:

```
let defs = sscDefsOnly(parse(modSrc)) in
match sscLoadImps(…) { case Pair(impDefs, seen2) => Pair(sscApp(impDefs, defs), seen2) }
```

1. **Read the package name** from the front matter of `fileStr` (not of `modSrc` — `sscProgramSource`
   has already stripped it). `extractFrontmatter` (`v2/lib/mira-md.ssc0:175`) returns
   `FrontmatterText(txt)`; scan its lines for `package:`.
2. **Take the member names from `defs`, which is already parsed** — `Pair("def", Pair(name, …))`,
   `Pair("object", Pair(name, members))`. This is strictly better than re-scanning the source: it
   cannot disagree with what was actually spliced, and it needs no parameter lists, because the
   alias is a VALUE alias.
3. **Emit the block above as TEXT, `parse` it, `sscDefsOnly` it, and append it AFTER `defs`.** After,
   not before: `def ui = __pkgref…` is a parameterless property and therefore EAGER, so its
   right-hand side must already be defined.

## Limits, named rather than left to be found

- **Value aliases are eager, so a `var` is snapshotted.** A top-level `var hits` is reachable as
  `a.b.c.hits` with its value at load time; `a.b.c.hits = 5` does not write the cell, and a later
  write through the flat name is not seen through the namespace. `var` is therefore NOT aliased —
  silently exposing a stale copy is worse than not exposing it. Whether package-qualified mutable
  state must work is a separate decision.
- **`exports:` does not gate the namespace, and that MATCHES v1** — measured, not assumed. A module
  exporting only `shown` still answers `p.hidden()` on both lanes, because v1 wraps the whole module
  in the package objects and gates only the flat import bindings. Where the two lanes differ is
  narrower and is filed as `package-root-import-needs-an-exports-entry-on-int`: with `exports:`
  declared but the package ROOT absent from it, `[p](./lib.ssc)` is refused outright by the
  interpreter (`'p' is not exported by ./lib.ssc`) and accepted here. Adding `p` to `exports:` makes
  both lanes agree.
- **The stub member `__pkg`** exists because a registration object must be non-empty. It is
  reachable as `a.__pkg`. Harmless, and the alternative — teaching the front to register an empty
  object — is a front change for a cosmetic gain.
- **`object` members alias only their `def`/`val` members**, one level deep. An object nested inside
  an exported object is dropped by `prefixDefs` before this code ever sees it.
- **Extension methods are excluded, and this one is a trap rather than a limit.** The parsed
  statement list is FLAT: the members of an `extension [A](p: Parser[A]) …` block arrive as ordinary
  `def` statements, indistinguishable from top-level ones except by the `extension_start` /
  `extension_end` markers around them. `std/parsing/combinators.ssc` declares `def ~`, `def |`,
  `def ~>` and `def <~` that way, so the first implementation emitted `def __pkgref_…__~ = ~` and
  every program importing that module died with `structural CoreIR contains parser sentinel _err` —
  a parse failure in GENERATED source, reported against the user's file. Caught by the corpus, not
  by the gate: two `indent-*` cases went red. Names are therefore filtered twice, by position (skip
  everything between the markers, because an extension method belongs to its receiver and not to the
  module) and by shape (`[A-Za-z_][A-Za-z0-9_]*`). A `package:` whose segments are not identifiers
  emits nothing at all, for the same reason: bad generated source names the wrong file.

## Acceptance

- The entry's own two-file repro prints `ui-card-hi` on `ssc run`, matching `ssc-tools run --v1`.
- **Unqualified names keep working** — the regression this design exists to avoid, and the one a
  namespace-INSTEAD-of-splice implementation would break.
- Both fronts, checked explicitly with `SSC_FRONT=F` and `SSC_FRONT=legacy`, because the table above
  is what a single-front measurement costs. F declines a file carrying a deep dotted selection and
  the reference front runs it; that fallback is correct but it means an F-only check proves nothing.
- `tests/e2e/package-keyword-smoke.sh` — its **INT row** (which runs `bin/ssc`, the native lane) is
  the acceptance test. Its other two rows fail for reasons that are NOT this defect and are filed
  separately: the JVM row invokes `bin/sscc`, a COMPILER, and compares its "artifact written to …"
  message against a page; the JS row emits `const org = org.example.ui.org;`, a self-referential
  binding. Do not read a red suite as this feature being broken — read the rows.

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
