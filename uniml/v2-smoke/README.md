# UniML × v2 compile smoke

Probes that check whether the v2 self-hosted `.ssc` compiler can compile UniML-shape code.
Run: `./run.sh` (invokes `v2/ssc1` per probe). See `specs/uniml-portable-gapmap.md` for the map.

- `works.ssc` — constructs v2 already compiles (PASS).
- `core-blocks.ssc` — the **immutable-core** building blocks (generic case classes, generic 3-param
  `trait` + dispatch, `enum`+`match`, `.copy`, `Vector` `:+`/`.last`/`.dropRight`/`.length`,
  `Option.forall`, `var`/`while`). PASS since v2 gained `Vector`/`List` `.dropRight`/`.takeRight`
  (the immutable stack-pop idiom the whole rewrite uses).
- `gap-array.ssc`, `gap-anon.ssc` — known v2 gaps (`new Array[T](n)` / anonymous `new Trait:`). These
  are kept as v2-gap documentation but are **no longer uniml-portable blockers**: Phase 1 + 1d made
  UniML fully immutable, so the standalone lib uses neither construct. They still FAIL on v2.

**Status:** the UniML-relevant probes (`works`, `core-blocks`) PASS — the immutable `uniml/core`
compiles and runs on v2. The remaining dual-compilation work is in the **dialects** (plain classes,
regex, `java.lang.Character`) — the `uniml-portable-1c-compat` scope; see the gapmap's 2026-07-13
UPDATE section. `gap-array`/`gap-anon` keep `run.sh` RED but no longer gate UniML.
