# Cross-module import name-conflict policy

Source: `busi-p3-module-fn-name-conflict` (busi feedback wave 2026-06-06).

## Problem

When the same function name is imported from two different modules — e.g.
`htmlEsc` defined in both `a.ssc` and `b.ssc`, both imported into a third
module — the second import silently overwrites the first in `interp.globals`.
The collision is invisible at import time; in busi's case it later surfaced as
a confusing runtime error in unrelated code (`No key 'toString' in map`), with
no hint that a name had been shadowed.

## Policy: last import wins + warning

The **last import wins** (matching how `interp.globals` already binds explicit
imports), and a one-time **warning** is emitted so the shadow is never silent.
This mirrors the sibling intrinsic-shadow policy
([`specs/intrinsic-shadow-policy.md`](intrinsic-shadow-policy.md)) and never
breaks existing code: programs that already relied on the last-wins behaviour
keep working, they just gain a diagnostic.

```
[warn] 'htmlEsc' imported from 'b.ssc' shadows the 'htmlEsc' imported from 'a.ssc' — last import wins
```

Rejected alternatives (the user chose last-wins + warning over both):

- *Compile-time error on ambiguity* — safest, but breaks any existing program
  that imports a colliding name today.
- *Require qualified access* (`a.htmlEsc`) — cleanest long-term, but a larger
  resolver change and a breaking surface change.

## Scope and guards

- Fires only when **both** the displaced binding and the newly-imported binding
  are **callable** (`FunV` or `NativeFnV`) — i.e. a genuine function-name
  conflict. This deliberately leaves the intentional status-val /
  case-constructor disambiguation alone (one side is an `InstanceV`; see
  `busi-p0-statusval-eventcase-collision` and `interp.shadowedAlternatives`).
- Tracks the import path that last bound each name in
  `interp.importedFnOrigin` (name → path). A conflict is "the existing origin
  path differs from the path now binding the name".
- **Idempotent re-import** of the same name from the **same** module does not
  warn (origin path equal).
- Each colliding name warns **once** per interpreter (tracked in
  `importConflictWarnings`) to avoid noise.
- Recorded in `interp.importNameConflictWarnings` (a read-only `Set[String]` of
  colliding names) so tests and tools can assert on the set without scraping
  stderr.

## Implementation

- `SectionRuntime.runImport` — at the explicit-binding site
  (`interp.globals(targetName) = imported`), compare `importedFnOrigin(targetName)`
  to the current import path before overwriting; warn on a callable-vs-callable
  mismatch, then record the new origin. Helper `isCallableBinding`.
- `Interpreter` — `importedFnOrigin`, `importConflictWarnings`,
  `importNameConflictWarnings`, `warnImportConflict`.

## Behavior checklist

- [ ] Two modules exporting the same fn name → last import wins; name recorded
      in `importNameConflictWarnings`; one `[warn]` to stderr.
- [ ] No collision → no warning.
- [ ] Re-import of the same name from the same module → no warning.
- [ ] A status-val / case-constructor cross-module pair (callable vs InstanceV)
      → no spurious conflict warning (existing disambiguation preserved).

## Not covered (open)

The downstream `No key 'toString' in map` crash busi originally reported could
not be reproduced from the plain two-module collision (which already last-wins
cleanly). The import-time warning now surfaces the collision before any such
downstream symptom. If the crash recurs, busi to supply the minimal failing
module set so the specific corrupting shape can be fixed separately.
