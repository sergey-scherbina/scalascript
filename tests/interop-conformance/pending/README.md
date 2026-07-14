# Pending conformance axes

These axes are part of the normative conformance matrix (joint `#interoperability`
resolution, point 7) but are **not yet measurable** on any runner: they require
the `DurableValue` wire codec, the `save()`/`run()` durable surface, and the
atomic admission layer. Per the v2.2 gate, byte-affecting codec work starts only
**after the X1 self-compilation fixed-point**, so these stay `pending-codec`.

Each `*.pending` file records the axis name, what it needs, and the exact
behaviour it must verify — so that when the codec lands, converting a stub into a
runnable `probes/*.ssc` + `expected/*.txt` pair is mechanical. The runner
(`run.sh`) enumerates these as `PENDING` rows so the matrix is never silently
reported as fully green.

Grouping:

- **durable / cross-host** — `06` same-process save/run, `07` cross-host resume,
  `08` concurrent multi-shot, `09` no prefix/main replay.
- **negative (must reject)** — `10` raw ForeignV → Unsavable, `11` missing
  resolver → admission reject, `12` codec/artifact mismatch → typed reject,
  `13` signature/audience/tenant/quota → admission reject.
