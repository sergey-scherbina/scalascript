# JS persistent Map (HAMT) — `js-persistent-map-hamt` (Tier 2 / T2.2)

## Problem

The JS runtime models the ssc immutable `Map` as a **native `Map`**, copied on
every update: `case 'updated': { const m2 = new Map(obj); m2.set(k,v); return m2; }`
(`JsRuntimePart2b.scala:174`). That is **O(n) per update → O(n²)** over a loop of
updates. Measured: `map-ops` JS ≈ 1.06 ms, ~40× the JVM/interp (which use
persistent HashMaps with structural sharing). Node micro: copy 1.31 ms vs mutable
0.014 ms (91×) — the copy is ~100% of the cost
(`docs/bench/cross-backend-gap-analysis.md` §1).

## Why it is not a quick edit (the constraint)

The ssc immutable `Map` and many **internal** runtime maps share one
representation (native `Map`), tested via `obj instanceof Map`. There are **71
`instanceof Map` sites** across the JS runtime; sampled categories:

| File | sites | kind |
|---|---|---|
| `JsRuntimePart1d` | 16 | HTTP headers/routing — consume **user** ssc Maps (`Object.fromEntries(headers.entries())`) |
| `JsRuntimePart2b` | 13 | collection-method dispatch — the user Map surface (`get/updated/filter/...`) |
| `JsRuntimeGraphql` | 11 | resolver results — user Maps (`for (const [k,v] of m)`) |
| `JsRuntimePart1c` | 9 | JWT/OAuth claims/config/session — user Maps |
| `JsRuntimeOptics`/`V14Effects`/`Part1b`/`Signals`/`Mcp`/… | ~22 | mixed |

A new HAMT type that does **not** satisfy these `instanceof Map` checks would
silently fall through every one of them (completeness risk). A copy-on-write
mutation hack risks aliasing corruption (silent-corruption risk). Both are why
this was deferred as a dedicated sub-project.

## Strategy (de-risked): duck-typed `_HAMT` + `_isMap`

The tractable migration — keep native `Map` for internal mutable maps, introduce
a persistent `_HAMT` for the **user immutable Map**, and make the two
interchangeable at every read site via a helper instead of `instanceof Map`:

1. **`_HAMT` class** — a Hash-Array-Mapped-Trie (or a simpler persistent
   hash-map) that exposes the **native-Map read interface** so existing consumers
   work unchanged once they recognise it: `get(k)`, `has(k)`, `size`, `keys()`,
   `values()`, `entries()`, `[Symbol.iterator]()` (yields `[k,v]`), `forEach`.
   Plus **persistent writers**: `updated(k,v)` / `removed(k)` returning a new
   `_HAMT` with **structural sharing** (O(log₃₂ n), no full copy). Key equality
   must match the current ssc semantics (`_eq`/value equality, not JS `===`) —
   audit how native `Map` keys behave today (object keys use reference identity in
   JS `Map`; verify ssc Map currently relies on that or on value keys, and
   preserve it).

2. **`_isMap(x)` helper** = `x instanceof Map || x instanceof _HAMT` (or a `_tag`
   check). Mechanically replace the **71 `instanceof Map`** sites with `_isMap(x)`.
   Sites that then iterate (`for..of`, `.entries()`, `Object.fromEntries`) work
   for both because `_HAMT` implements the same iteration protocol.

3. **Route ssc-immutable-Map creation to `_HAMT`** — every site that *produces* a
   user Map: `Map(...)` literals (in `JsGen`), and the Map-returning dispatch ops
   in `JsRuntimePart2b` (`updated`, `removed`, `filter`, `groupBy`, map builders
   at lines ~148/174/175/178/399/503/724). Internal mutable maps keep `new Map()`.

4. **Writers become O(log n)** — `updated`/`removed` call `_HAMT.updated/removed`
   (structural sharing) instead of `new Map(obj)`.

## Staged plan (infra → integration → activation; never one megacommit)

Per the project's split-commit safety discipline:

- **p1 — infra (zero risk):** add the `_HAMT` class + `_isMap` helper to the JS
  runtime as standalone code, **not yet wired** (creation still native Map).
  Unit-test `_HAMT` directly (get/has/updated/removed/iteration/equality, and
  structural-sharing: an `updated` does not mutate the original). Gate behind a
  capability/flag if preamble-size for Map-less programs is a concern.
- **p2 — integration (mechanical, reviewable):** sweep the 71 `instanceof Map`
  → `_isMap`. No behaviour change yet (no `_HAMT` is created), so the full JS
  conformance suite must stay green — this proves the sweep is correct in
  isolation.
- **p3 — activation:** route ssc-immutable-Map creation + `updated`/`removed`
  through `_HAMT`. Now `map-ops` should drop toward JVM/interp. Run the **full JS
  conformance + map tests**; spot-check the consumer sites (headers, claims,
  GraphQL) end-to-end.
- **p4 — bench + close:** re-measure `map-ops` (`scripts/bench cross` / wall);
  record before/after; confirm no conformance regression.

## Acceptance (from `specs/backend-perf-gaps.md` T2.2)

- [ ] HAMT (or persistent) `Map` runtime type added (p1).
- [ ] All native-`Map` coupling sites migrated/audited via `_isMap` (p2).
- [ ] `map-ops` JS gap to JVM closed; no conformance regression (p3/p4).

## Risks & mitigations

- **Key equality drift** — JS `Map` uses SameValueZero (reference identity for
  objects). If ssc Maps currently key on value equality somewhere, `_HAMT` must
  replicate it; if they rely on JS identity, match that. Audit first; add tests
  for object-keyed and tuple-keyed maps.
- **Missed coupling site** — the p2 sweep must be exhaustive; grep
  `instanceof Map` must return **0** in the JS runtime afterwards (all via
  `_isMap`). A leftover site silently mis-handles a `_HAMT`.
- **Serialization / `_show` / JSON** — confirm `_HAMT` flows through `_show`,
  `toWire`/`fromWire`, and any structural-clone paths the way native Map does.
- **Preamble size** — `_HAMT` is ~150 lines; gate by the Map capability if it
  bloats Map-less programs.

## Out of scope

- Interp / JVM Map (already persistent).
- Changing ssc Map *semantics* — this is representation only; observable behaviour
  must be identical (verified by the conformance suite).

## Status

Design only (2026-06-11). Implementation is the staged p1–p4 above — a dedicated
multi-session effort, re-claimed per slice. Strategy de-risks the 71-site
completeness problem via `_isMap` duck-typing.
