# f-ambient-prelude-drop-in — findings (2026-07-21)

Goal was to make the staged self-hosted front (`SSC_FRONT=F`) a TRUE drop-in for the
ambient-prelude / plugin class (json-read, generators, …), which today fall back to the default
front via the F4a delegate-fallback instead of running directly on F.

## Outcome: the static drop-in is UNSOUND — abandoned. A real F bug was found instead.

### What was tried
The F runner emits its user-program IR as text and re-decodes it through the validating
`coreir.decode`, so any program referencing an unbound global (a plugin `extern def`, or a dead ref
shadowed by a plugin native — e.g. json's `jsonCoreParseTolerant`) is REJECTED at decode and falls
back. A prototype added a non-validating `coreir.decodeRaw` + a `validate(p, allowGlobals)` overload
and, in `RunNativeV2`, ran F directly when `unboundGlobals(F) ⊆ unboundGlobals(default)`
("subset-of-default"): F leaves unbound only refs the default also leaves unbound.

### Why it is unsound (measured, `SSC_DUALRUN_ALL=1` full corpus)
`F.unbound ⊆ default.unbound` is NECESSARY but NOT SUFFICIENT. The curated slice passed (dualrun
EQUAL 45/45, fixpoint byte-identical 385827 B, semantic 248/248, codec-vectors 94/0), but the full
sweep exposed **deterministic regressions**: the prototype promotes `actors-pingpong`,
`actors-typed-remote-spawn`, `auth-demo` from a correct fallback to a BROKEN drop-in. An F-incomplete
program can leave the same globals unbound yet lower its BOUND code differently — the subset test
can't see that. Promoting them regresses `never-worse` (the flip's core invariant), so the prototype
was reverted. No sound purely-static discriminator was found (extern-scan, plugin-registry, and
subset-of-default were all tried; the real difference is reachability/dead-code, undecidable here).

## Two durable conclusions

1. **The flip (step 4) does NOT need this gap closed.** F4a fallback already makes F never-worse for
   the ambient/plugin class. The flip's hard-safety gate is really the multi-file residuals, of which
   `mcp-types` (landed) was the last. `f-ambient-prelude-drop-in` is a step-5 (delete-old-front)
   quality goal, not a step-4 safety blocker — the orchestrator gate can be relaxed accordingly.

2. **The real bug is `f-stmt-partial-function-block-dropped` (BUGS.md).** Running the ambient
   programs on F surfaced a genuine F-front bug: F mishandles a `f { case … }` partial-function block
   argument (the `receive { case … }` idiom), so actor programs silently skip message handling. This,
   not a "deep effects gap," is why actor programs diverge on F. Sergiy chose to fix the real
   F-lowering; the located parser path + the reconciliation step to do first are in BUGS.md.

Next work item: fix `f-stmt-partial-function-block-dropped` in `specs/v2.2-p6.5-fsub.ssc`.
