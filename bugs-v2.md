# What does NOT work in v2 — the measured list

**Measured 2026-07-28** on `origin/main` with a fresh `sbt installBin`, by one full canonical run:

```bash
scala-cli --server=false tests/conformance/contract.sc -- --update-baseline --workers 4
```

522 cases × lanes `int, js, v2`, on a quiet machine (load 4.8 — earlier attempts were blocked at
8-35 with four agents building, and a contended run records timeout flakes as truth). The run also
refreshed the paired freeze, which had one commit ever while 48 cases had been added since.

**v2 is non-PASS on 39 of 522 cases** — 31 `FAIL`, 8 `DIVERGE`. The previous baseline claimed 43;
six of those were already fixed and had gone stale.

The 39 cases are **seven causes**, not 39 problems. Each cluster below was pinned by re-running the
case with the contract's exact v2 command (`bin/ssc run --v2 <file>`) and reading the first error,
then — for the parse rejections — dumping the lowered IR and locating the `_err` sentinel.

> ⚠️ **Method note.** v2 does not fail by raising. A missing member evaluates to a `Stub` sentinel
> and the program continues at **exit 0** — `Mirror.isProduct`, `Mirror.fromProduct` and
> `List.apply` all did exactly that today, and `isProduct`'s sentinel then drove an `if`. Any
> inventory of v2 must compare OUTPUT against the INT lane; an exit-status check reports these as
> success.

---

## A — native front rejects the parse (`_err`) — 9 cases, ≥4 distinct gaps

`ssc: native frontend rejected incomplete parse …: structural CoreIR contains parser sentinel _err`.
The message names no location, so each was pinned by dumping the IR and reading around `(global _err)`.

| case | construct that produced `_err` |
|---|---|
| `wasm-matrix` | **fewer-braces trailing lambda**: `matrix(a.length, cols): (i, j) =>` on the next indented line (`examples/wasm-matrix.ssc:25`) |
| `quoted-macro-constfold`, `quoted-macro-interpreter` | **quoted-macro splice syntax**: `inline def label(x: Int): String = ${ labelImpl('x) }` (`:22`) |
| `wasm-http` | **`for … yield`** (`:38`) |
| `graph-codecs`, `typed-object-codec` | a top-level construct emitted directly AFTER the Mirror block — not yet reduced to a minimal repro |
| `graph-storage`, `wasm-collections`, `wasm-scalascript` | not yet pinned individually; grouped here by symptom only |

**Status: 4 of 9 pinned to a named construct, 5 grouped by symptom only.** Cheapest next step is a
per-file bisect; each is likely to collapse into one of the four above.

**❓ Question for Sergiy — `quoted-macro-*`:** is the quoted-macro surface (`${ }` / `'x` /
`Expr[T]` / `QuotedContext`) *meant* to work on the native lane, or is it a v1-only feature? Two of
these 39 cases exist only to exercise it. If it is v1-only, the honest fix is a `backends:` gate on
those two cases, not a parser feature.

---

## B — `derives` on the native lane: the derived instance is never defined — 7 cases

| symptom | cases |
|---|---|
| `ssc: unbound global: JsonCodec_derived` | `dataset-typed-mapping`, `distributed-dataset-codec`, `distributed-dataset-typed-helpers`, `distributed-dataset-wire-protocol`, `distributed-dataset-wire-shuffle` |
| `ssc: unbound global: ObjectCodec_derived` | `object-store-jdbc` |
| `ssc: RowCodec.derived expects Mirror metadata` | `typed-sql-crud` |

**This is the same shape as two defects already fixed today, on the third lane.** `derives TC`
needs (1) the typeclass object to be found, including when it lives in an IMPORTED module, and (2)
the derived instance to actually be emitted and reachable. The JS lane had exactly this in two
places (`js-lane-missing-derives-and-coroutinecancel`, `js-treeshake-prunes-mirror-ctor`), both
fixed. The native lane's `ssc1-lower` registers `__derived_TC_T` in the givens table
(`derivedGivenEntries`) and initialises it via `derivedInitIrs` calling `TC_derived` — and
`TC_derived` is unbound, i.e. the *typeclass's own* `derived` method is not being emitted under the
name the initializer calls.

**Highest-leverage cluster in the list**: 7 cases, one mechanism, and the neighbouring code was
touched today so it is fresh. `typed-sql-crud`'s different wording (`expects Mirror metadata`)
suggests the same root reached one step later.

---

## C — plugin global missing on the native lane — 9 cases

`ssc: unbound global: <name>` where the name is a plugin-provided function that the INT lane has.

| missing global | cases |
|---|---|
| `htmlToPdfBase64` | `invoice-email`, `invoice-pdf`, `pdf-extract-demo` |
| `mcpServer` | `mcp-agent`, `mcp-filesystem-server` |
| `attr` | `html-dsl` |
| `Widget` | `js-glue-component` |
| `nfcCapabilities` | `nfc-ndef` |
| `validate` | `rest-validate` |
| `clusterOf` (via `ssc: Actors scope failed:`) | `cluster-capability` |

Per-plugin work rather than one fix, but each is small and independent: the plugin exists for INT
and is not registered on the native host. `htmlToPdfBase64` alone is 3 cases.

**❓ Question for Sergiy:** are all of these *supposed* to be available natively? Some (`Widget`,
`nfcCapabilities`) look browser/JS-shaped. If a plugin is deliberately not native, its cases want a
`backends:` gate, not a port.

---

## D — effect unhandled on the native lane — 2 cases

* `indexeddb-sync-client` — `ssc: unhandled runtime effect: IndexedDb.store`
* `sync-todo` — `ssc: unhandled runtime effect: Sync.put`

Same family as C (a capability the native host does not provide), but it surfaces through the
effect system instead of a bare global. **Note:** per an earlier finding, *any* unbound qualified
call on the native lane renders as "unhandled runtime effect" — so do not read these as
effect-system bugs without checking.

---

## E — F declined the file and the run fell back — 2 cases

`ssc: F did not lower this file; compiled with the default front instead` — `dsl-calc-parser`,
`std-index`. The F4a delegate-fallback fired, so the file still ran; the case is non-PASS for what
happened *after*. `std-index` exits 1, `dsl-calc-parser` exits 0 with wrong output.

Overlaps the existing `f-delegation-reason-census` work. Not a separate defect until the delegation
reason is read.

---

## F — runs, output differs (`DIVERGE`, exit 0) — 8 cases

| cases | note |
|---|---|
| `rozum-agent`, `rozum-agent-pool`, `rozum-agent-streaming`, `rozum-agent-schema-derived` | **one cause**: the INT lane prints a 3-line server banner (`ScalaScript web · …` / `(backend=…)` / `Ctrl+C to stop.`) that the native lane does not. Program output is byte-identical. Filed as `v2-serve-banner-missing`; **already claimed** by `v2-stub-apply-and-serve-banner` |
| `content-tables` | first line `plain=` — content/table rendering differs |
| `dsl-ast-builder` | first line `s1:     0..5` — spacing/format differs |
| `json-read` | first line `42` — differs later in the output |

`rozum-agent-schema-derived` reached this bucket today: it was `FAIL` (`_err`, then
`Stub("Mirror.isProduct")`) and now runs and only diverges on the banner.

---

## G — single cases with their own reason — 2

* `content-introspection` — `ssc: contentCurrentSection() is unavailable on native 2.1 without
  source-aware call identity`. A **deliberate, documented** native limitation, not a defect.
  **❓ Should this case carry a `backends:` gate or a `known-red:` declaration** so it stops being
  counted as a v2 failure?
* `deploy` — `ssc: checker exit 2`. The native checker rejects the file; the reason is not in the
  message. Needs the checker's own output.

---

## Not v2 problems, do not read them as such

The refreshed baseline also carries **77 `* SKIP`** rows. A `SKIP` means the **INT lane — the
golden — could not run the case at all** (`int-nonzero` / `int-timeout`), so no lane was compared
and v2 was never asked. They are a separate backlog (mostly servers and network-dependent demos),
and counting them against v2 would be wrong.

Also in the refreshed baseline: 33 `js FAIL`, 4 `js KNOWN-RED`, 1 `js TIMEOUT`, 2 `int KNOWN-RED` —
other lanes, out of scope for this list.

---

## Summary

| cluster | cases | leverage |
|---|---|---|
| B — `derives` / `TC_derived` unbound | 7 | **one mechanism** — best next fix |
| A — parse rejections | 9 | ≥4 gaps; `quoted-macro` may not belong at all |
| C — missing plugin globals | 9 | per-plugin; `htmlToPdfBase64` = 3 cases |
| F — output diverges | 8 | 4 are one banner, already claimed |
| D — unhandled effect | 2 | same family as C |
| E — F delegation | 2 | overlaps an existing census |
| G — one-offs | 2 | one is a documented limitation |

**39 cases, ~7 causes.** Fixing B and the banner would take v2 from 39 non-PASS to about 28 — and
the four `_err` gaps in A are each the kind of one-construct parser fix that has landed four times
today.

## Open questions for Sergiy

1. **Quoted macros on native** (`quoted-macro-constfold`, `quoted-macro-interpreter`) — supported
   surface, or v1-only? Determines "fix the parser" vs "gate the cases".
2. **Which plugins must be native** (`htmlToPdfBase64`, `mcpServer`, `attr`, `Widget`,
   `nfcCapabilities`, `validate`, `clusterOf`, `IndexedDb`, `Sync`)? Some look browser-shaped.
3. **`content-introspection`** — the message says the limitation is by design. Gate it as
   `known-red` so the number reflects reality?
4. **The banner** (`v2-serve-banner-missing`) — make native print it (contract-preserving), or move
   it to stderr in both lanes (arguably right, but rewrites every serving example's golden)? Filed
   with both options; currently claimed by another agent.
