# UniML × v2 compile smoke

Probes that check whether the v2 self-hosted `.ssc` compiler can compile UniML-shape code.
Run: `./run.sh` (invokes `v2/ssc1` per probe). See `specs/uniml-portable-gapmap.md` for the map.

- `works.ssc` — constructs v2 already compiles (should PASS).
- `gap-*.ssc` — known gaps (currently FAIL); each becomes PASS as v2 closes the gap.

Currently **RED** on `gap-array` (broken `new Array[T](n)`/indexed update) and `gap-anon`
(anonymous `new Trait:` → `unbound global: _err`).
