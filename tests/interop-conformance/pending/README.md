# Pending conformance vectors

These records explain vectors whose target-neutral oracle is not executable on
any lane yet. Vectors 10–17 require the `DurableValue` wire codec, the
`save()`/`run()` durable surface, and the atomic admission layer. Per the v2.2
gate, byte-affecting codec work starts only **after the X1 self-compilation
fixed-point**, so these stay `pending-codec`. Vector 26 stays `pending-spec`
until the semantic owner freezes cancellation states, transition ordering, and
its exact diagnostic.

Prompt vectors 18, 22, and 23 are not pending: they are specified and runnable
on `scala-explicit`. The current portable VM/ASM lanes report them as
`UNSUPPORTED` because those lanes do not advertise `shift-reset`.

Each `*.pending` file records the axis name, what it needs, and the exact
behaviour it must verify — so that when its prerequisite lands, converting a stub
into a process probe or typed host adapter is mechanical. `vectors.tsv` owns the
phase; `run.sh` validates these detail files, and `run.sh --list` prints every
lane-by-vector cell so the matrix is never silently reported as fully green.

Grouping:

- **durable / cross-host** — `14` same-process save/run, `15` cross-host resume,
  `16` concurrent multi-shot, `17` no prefix/main replay.
- **negative (must reject)** — `10` raw ForeignV → Unsavable, `11` missing
  resolver → admission reject, `12` codec/artifact mismatch → typed reject,
  `13` signature/audience/tenant/quota → admission reject.
- **semantic definition** — `26` cancellation transitions and diagnostic remain
  intentionally unspecified rather than being guessed by the harness.
