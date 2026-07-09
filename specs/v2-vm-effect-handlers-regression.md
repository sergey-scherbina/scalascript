# v2 VM Effect Handlers Regression

## Overview

The v2 VM conformance suite currently returns raw `Op(...)` values for
algebraic and typed effect-handler examples that should be interpreted by their
handlers. The JS and Rust lanes pass the same effect rows, so this slice is
scoped to the VM/runtime path and must restore the target-independent semantics
promised by `SPEC.md` section 7.2.

## Interface

No user-facing language or CLI syntax changes are intended. Verification uses
the existing v2 conformance harness and a minimal packaged-jar repro:

```bash
./v2/conformance/check.sh
java -jar <v2-jar> run examples/effects-state.ssc0
java -jar <v2-jar> run examples/effects-nondet.ssc0
java -jar <v2-jar> run bin/mirac.ssc0 examples/hm-eff-comp.hm > /tmp/hm-eff-comp.coreir
java -jar <v2-jar> run-ir /tmp/hm-eff-comp.coreir
```

## Behavior

- [x] Reproduce at least `effects-state`, `effects-nondet`, `async-tasks`, and
      one typed-effect row in the real packaged v2 harness before code changes.
- [x] Identify why the VM lane returns raw `DataV("Op", ...)`/`Op(...)` values
      where handlers should resume, without changing JS/Rust effect semantics.
- [x] Add focused regression coverage for the VM handler path: at minimum one
      direct ssc0 effect handler case and one typed-effect CoreIR/mira case.
- [x] Restore full `./v2/conformance/check.sh` or record the exact residual rows
      if a separate pre-existing failure remains after the effect fix.
- [x] Run `tests/conformance/run.sh --only 'litdoc'` before push.

## Out of scope

- Bytecode-lane ambient effect provider work (`p4-bc-ambient-effects`).
- Performance work for `pattern-match-heavy` or other v2 VM corpus rows.
- Rewriting the effect representation or changing the language-level effect
  surface.

## Design

Start from the smallest failing family: VM `run` and VM `run-ir` both return
unhandled operations, while source-generated JS/Rust rows pass. Inspect the
runtime path for:

1. effect operation value shape (`DataV("Op", ...)` and any aliases);
2. handler dispatch/method cases that recognize those values;
3. recent match/type-test changes that could make `Op` cases unreachable or
   bypassed;
4. the ssc0 compiled CoreIR for `examples/effects-state.ssc0` and the CoreIR
   emitted from `examples/hm-eff-comp.hm`.

Prefer a narrow normalization or dispatch fix in `v2/src/Runtime.scala` if the
bug is a value-shape mismatch. Do not broaden into bytecode or generated-source
backends unless the repro proves they share the same failing path.

## Decisions

- **Prioritize correctness before the next performance row** — chosen because
  full v2 conformance is a production gate and this failure affects language
  semantics. Rejected: continuing immediately with `pattern-match-heavy` while
  the VM effect family is red.
- **Keep the fix VM-local unless proven otherwise** — chosen because JS and Rust
  pass the same rows, so a cross-backend semantic rewrite would add risk without
  evidence.

## Results

Fixed in `b6f88744c`. The VM compiler's `Match` path was auto-threading every
`DataV("Op", ...)` scrutinee before normal ADT matching. That behavior is only
valid for bridge/runtime expression-position operations, whose labels are
dotted and already recognized by `Runtime.isAutoThreadOp`; pure free-monad
effect handlers in `lib/effects.ssc0` and Mira typed effects intentionally
match `Op` as ordinary data. The fix applies the same `isAutoThreadOp` guard to
the `Match` lift that `Let`/`Seq` already use, leaving user `Op("get", ...)`,
`Op("choose", ...)`, and `Op("double", ...)` matchable by handlers.

Pre-fix packaged-jar repros returned raw operations:

```text
examples/effects-state.ssc0 => Op("get", (), <closure>)
examples/effects-nondet.ssc0 => Op("choose", List(1, 2), <closure>)
examples/async-tasks.ssc0 => Op("log", 1, <closure>)
hm-eff-comp => Op("double", 10, <closure>)
```

Post-fix repros and full gate are green:

```text
examples/effects-state.ssc0 => Pair(2, 2)
examples/effects-nondet.ssc0 => List(11, 21, 12, 22)
examples/async-tasks.ssc0 => List(1, 10, 2, 20, 3)
hm-eff-comp => 41
```

Verification:

- `scripts/sbtc 'v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest -- -z "effect handlers"'`
- `./v2/conformance/check.sh`
- `scripts/sbtc "installBin"`
- `tests/conformance/run.sh --only 'litdoc'`
