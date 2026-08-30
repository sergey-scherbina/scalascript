## aggregator-production-readiness-slice-1 — DONE: laws conformance case, bench entry, spec §14 status section

<!-- status: fixed
     lane: multi
     area: runtime
     kind: feature
     gate: tests/conformance/std-aggregator-properties.ssc
     fixed-in: aggregator-production-slice claim, 2026-08-30
     reported-by: self (user ask: production readiness for the aggregation algebra)
     reported-at: 2026-08-30 -->

First measured production-readiness slice for `std/aggregator.ssc` (claim
`aggregator-production-slice`, 2026-08-30). Landed: (1)
`tests/conformance/std-aggregator-properties.ssc` — the algebraic laws production use depends on,
as a gated case: §9-bridge partition-invariance across five partitionings (incl. empty parts and a
3-way split), monoid associativity/commutativity/identity (incl. CMS merge and the `Group` inverse
laws), `SlidingWindow` retraction vs recompute-from-scratch at every push, and
empty/single-element edges for every canonical aggregator — 30 law checks, green on `int` and
`js`, known-red `jvm` against the module-level transpile blockers its declaration names; (2)
`bench/corpus/aggregator-fold.ssc` — the algebra's hot shapes (tuple-accumulator typeclass fold +
keyed `Map` fold) in the runtime-bench corpus; numbers deliberately deferred to a quiet host (this
landing ran during parallel agent builds — a number under load is a hypothesis); (3)
`specs/aggregation-algebra.md` §14 "Production readiness — measured status" — what is measured vs
designed-but-unintegrated (live DStream wiring and accumulator-state durability stated plainly as
uncovered, with the owning specs named). Writing the laws case FOUND a real `int` bug the existing
cases masked: `v1/runtime/backend/interpreter/BUGS.md`
`imported-generic-fold-with-a-tuple-accumulator-presents-one-component` (filed open; the case
works around it with an inline fold and cites the slug at the site).

## aggregation-algebra-canonical-and-effectful — DONE: §5–§11 of the aggregation-algebra spec, landed section by section after the core

<!-- status: fixed
     lane: multi
     area: runtime
     kind: feature
     gate: tests/conformance/std-aggregator.ssc, tests/conformance/std-aggregator-approx.ssc, tests/conformance/std-order.ssc
     fixed-in: aggregator-approx claim, 2026-08-30 (final section; see body for every prior claim)
     reported-by: self (claim aggregator-core-impl)
     reported-at: 2026-08-29
     ssc-version: bin/ssc-tools built from e6b956e47+
     repro: n/a
     impact: none — deliberate scope cut, not a defect
     confirmed: no -->

`specs/aggregation-algebra.md` §2.2–§5 (`Group`, `Aggregator[In, Acc, Out]`, `zip`/`map`
composition, and all of §5's canonical exact aggregators — claims `aggregator-core-impl`,
`aggregator-canonical-exact`, `aggregator-first-last`, 2026-08-29) landed as
[`std/aggregator.ssc`](std/aggregator.ssc). Everything past that is real, separate work the spec
itself scopes out of the first slice — queued here rather than attempted in the same pass, per
"central primitive first":

- **§5 canonical exact aggregators — DONE.** `sum`/`count`/`mean` (§4.3), `min`/`max` (`Option[A]`
  as the `Top`/`Bottom` sentinel, over any `Order[A]`), `variance`/`stddev` (Chan/Golub/LeVeque,
  §5.1), and `first`/`last` (§5.2 — `MinByAgg`/`MaxByAgg` generalize `min`/`max` to compare by a
  projected key; the caller attaches the ordering key with `.zipWithIndex` before folding, since
  fold order alone isn't a valid substitute once partitions can merge in either order) all ship.
  Landing `first`/`last` found one more front bug: a tuple-destructuring 2-parameter lambda
  (`acc.map((a, i) => a)`) passed to `Option.map` silently double-wraps the result on the reference
  front (`Some(Some((10.0, 0)))` instead of `Some(10.0)`) — worked around with explicit tuple-field
  access (`acc.map(p => p._1)`), not filed as a BUGS entry (out of scope to chase further right now;
  every tuple-destructuring lambda in this module already uses the field-access form for this
  reason, so nothing here still depends on the broken path).
- Landing `min`/`max` found **`std/order.ssc` shipped completely broken, with zero test coverage** —
  three stacked defects (unnamed `given` instances unresolvable by `summon`/`using`; helpers
  delegating to trait DEFAULT methods, which don't carry through a `given` instance per
  `std/foldable-traversable.ssc`'s own note; and `compare` bodies calling `.compareTo`, which
  ScalaScript's primitives don't implement). Fixed in the same claim, now with real coverage
  (`tests/conformance/std-order.ssc`). Also found and filed at the time (out of scope for a
  std-library task): `v1/runtime/backend/js/BUGS.md`
  `js-codegen-drops-generic-typeclass-resolution-when-multiple-instances-exist` — the JS backend
  lost a `using` parameter or a `summon` binding once more than one named instance of a generic
  trait was in scope. **Now fixed** (claim `js-multi-given-resolution`, 2026-08-30): the
  object/package-nested `Defn.Def` codegen dropped `using` clauses entirely where the top-level
  path handled them (extracted into a shared `usingParamGuards` helper used by both), and
  `summon`'s fallback for an explicit user given emitted a bare identifier spelled like the
  registry key, which no given was ever bound to (now `_ssc_givens[key] ?? _resolveGiven(key)`).
  `std-order`'s conformance case now PASSES on `js` outright (known-red deleted);
  `std-aggregator`'s stayed known-red on `js` against a third, later bug this fix exposed in
  `groupByAgg` (`js-codegen-map-dot-empty-has-no-companion-handling` — the JS backend's own
  `Map.empty` gap, mechanically different from the interpreter's same-named bug fixed earlier the
  same day). **That third bug is also now fixed** (claim `js-map-dot-empty`, 2026-08-30): a
  `Term.Select(Map, empty)` case lowers to `_Map()` directly, both spellings (`Map.empty`,
  `Map.empty[K, V]`). Landing IT exposed a FOURTH pre-existing defect, in `runEffAggregator`:
  a method reference through a receiver in argument position (`accF.flatMap(agg.present)`) is
  invoked with zero args instead of eta-expanded to a function
  (`js-codegen-method-reference-in-argument-position-is-invoked-not-eta-expanded`, filed not
  fixed, minimal repro verified standalone against `int`). `std-aggregator` stays known-red on
  `js` against the fourth bug.
- **§6 approximate aggregators — DONE** (claim `aggregator-approx`, 2026-08-30). `ApproxAggregator`
  (adds `errorBound`), `HLLAgg` (a real `Aggregator`), and `CMSMonoid`/`TDigestMonoid` (kept as
  `Monoid` plus `add`/`estimate`/`addPoint`/`quantile` methods, per the spec's own reasoning —
  `present` needs an extra argument for both, which doesn't fit the plain `Aggregator` shape)
  all ship, each verified against real, measured accuracy (not assumed): HLL gives 1.2% error on
  10,000 known-distinct keys against a 1.6% theoretical bound, and correctly estimates a UNION after
  merging two overlapping partial sketches; CMS gives exact counts at the tested scale; T-Digest
  compresses 200 points to 20 centroids and estimates the median/p99 within the expected range —
  on the reference front. **This closes out the last open section of the spec** — every section
  except `Arrow`/`Category`/a new `Future`/`Task`/`Result` type (§11.4, deliberately deferred, not a
  gap) is now landed. Found landing it: `HLLAgg` was known-red on `int` (`math.log` missing from the
  interpreter's `math` object) and was known-red on `js` (a sibling zero-arg `def` referenced by bare
  name resolved to nothing — fixed, claim `js-sibling-zeroarg-def`, 2026-08-30). That fix surfaced a
  sibling defect re-testing against the real `HLLAgg`: a class-body `val` field was never computed
  or attached to the instance object at all — **also now fixed** (claim `js-classbody-val-field`,
  2026-08-30, shared helper used by both `Defn.Class` codegen sites). Landing THAT fix surfaced a
  THIRD, unrelated defect: `Array.fill`/`List.fill`/`*.tabulate` reject a `BigInt` count, which
  `1 << precision`-style `Int` arithmetic produces on `js`
  (`js-codegen-array-fill-and-tabulate-reject-a-bigint-count`, filed not fixed — likely a specific
  manifestation of the broader, already-acknowledged `int-width` non-conformance, which carries no
  BUGS slug of its own to fix against). `js` stays known-red against this third bug.
  `TDigestMonoid` was known-red on `int` (`List.sortBy` on a `Double` key
  sorted lexicographically, not numerically); the whole `std-aggregator-approx` conformance case is
  also known-red on `jvm` (the pre-existing, already-tracked `int-width` non-conformance, triggered
  by a `Long` literal `HLLAgg`'s hash mixer needs). **Both `int`-lane bugs are now fixed** (claim
  `interp-math-log-and-sortby`, 2026-08-30) — `math.log`/`math.exp` added to the interpreter's
  intrinsics and wired into the `math` object; `sortBy` gained a second numeric fast path for
  `Double`/mixed `Int`+`Double` keys, leaving the existing `Int`-only path and the string fallback
  for genuinely non-numeric keys untouched. `std-aggregator-approx` is green on `int`; `js`/`jvm`
  stay known-red against their own separate, still-open bugs. Also found and fixed IN the original
  claim (not filed, since it was `std/aggregator.ssc`'s own
  bug): `groupByAgg` (§8) used `in` as a `val`-tuple-destructured name, which is a JS reserved word
  — this had been silently masking part of an already-filed JS bug's diagnosis in every conformance
  case landed since §8, since a JS syntax error prevents the file from running AT ALL regardless of
  which line actually gets reached. The underlying JS codegen defect (a reserved word used as a
  `val`-tuple name wasn't escaped, though the identical shape in a lambda PARAMETER's destructuring
  correctly was) is **now fixed for real** (claim `js-val-tuple-reserved-word`, 2026-08-30):
  `genPatDestructure`'s `Pat.Var` case now escapes a reserved-word name and registers the mapping
  into `paramRenames` so later bare references resolve too (a destructuring statement has no
  enclosing-body scope to thread a rename through the way a lambda parameter's `withParamRenames`
  does). `groupByAgg`'s `item` reverted to `in`, the original name.
  Also found, and now fixed (claim `interp-class-body-val-field`, 2026-08-30): a class-body `val`
  field (not a constructor parameter — `HLLAgg`'s `hllMonoid`/`m`) threw `Undefined: <name>` when
  read from a different method of the same class — the class-body scan collected only `Defn.Def`
  members, so a `Defn.Val` statement was silently dropped and its name never reached the per-instance
  field array or `typeFieldOrder` (the type-level registry external field access, pattern matching,
  and `derives` all key off). Fixed in `StatRuntime.scala`'s `Defn.Class` case: body `val`s are now
  evaluated per instance (their RHS can reference constructor params, so — unlike the name list —
  this can't be done once at class-declaration time) and appended to both the instance's field array
  and `typeFieldOrder`. `HLLAgg`'s `hllMonoid`/`m` are ordinary `val`s again, no `def` workaround
  needed. Verified: 1898/1898 `backendInterpreter` sbt tests pass, 118/118 smoke-ci checks green,
  `std-aggregator`/`std-aggregator-approx`/`std-order` conformance unchanged (still `int`-green).
  The other v1-interpreter bug found alongside it, `array-tabulate-lambda-loses-a-sibling-top-level-
  def-cross-module`, turned out **not to be a real defect** (claim
  `interp-array-tabulate-cross-module`, 2026-08-30): re-tested with a faithful reconstruction of
  `CMSMonoid.add`'s actual failing shape against both the current interpreter and the exact pre-
  class-body-val-fix binary, and it did not reproduce on either. Most likely the class-body-val bug
  above — active in the same file at the same time — was the real cause and got misattributed.
  Closed `wontfix`; `CMSMonoid.add` now uses its natural, un-worked-around form (verified against
  the full conformance suite, no regression).
- **§7's `Group`-backed sliding window — DONE** (claim `aggregator-sliding-window`, 2026-08-29).
  `GroupAggregator`/`SlidingWindow`/`emptyWindow` ship in `std/aggregator.ssc`; `push` retracts the
  aging-out element via `inverse` in O(1) once the window is full. Found landing it, and this is the
  more important finding of the two: the design's own "should be a compile-time rejection" claim for
  passing a non-`Group`-backed `Aggregator` **does not hold** — `emptyWindow(MinAgg(...), 3)`
  compiles and runs today, failing only the first time `.group` is actually accessed. Reproduced
  with a minimal, aggregator-unrelated example and filed as real, separate type-checker work:
  `v2/BUGS.md` `trait-typed-parameter-accepts-a-non-conforming-argument`. Every design in this
  codebase that leans on a narrower trait to make a constraint type-level (`ApproxAggregator` in the
  still-queued §6, for instance) inherits the same caveat — worth keeping in mind rather than
  re-discovering per feature.
- **§8 `groupBy` — DONE** (claim `aggregator-groupby`, 2026-08-29). `MapMonoid[K, Acc]` +
  `groupByAgg[K, In, Acc, Out](xs: List[(K, In)], agg): Map[K, Out]` ship in `std/aggregator.ssc`.
  Found landing it: `Map.empty` threw under the v1 interpreter (`--v1`) — it read `"empty"` as a
  literal key lookup on an already-empty map instead of the companion accessor, "No key 'empty' in
  map" — while `int`/`native`/`jvm` via `Map[K, V]()` all worked correctly. Worked around throughout
  `std/aggregator.ssc` with `Map[K, V]()` instead of `Map.empty` at the time. **Now fixed** (claim
  `interp-map-dot-empty`, 2026-08-30): `v1/runtime/backend/interpreter/BUGS.md`
  `map-dot-empty-reads-empty-as-a-literal-key-not-the-companion-accessor` — `Map`, unlike
  `List`/`Vector`/`Array`/`Set`, was never wrapped in a companion object, so `Map.empty` fell
  through a heuristic meant for things like `args.length` ("auto-call a parameterless native global
  in receiver position"), calling `Map()` then reading `"empty"` as a key on the result. Fixed by
  wrapping `Map` in a companion too (`BuiltinsRuntime.wrapMapCompanion`), tagged `"MapCompanion"`
  rather than `"Map"` to avoid colliding with an unrelated pre-existing `CallRuntime` case for a
  `Map[String, Any]` handler-param value, and re-applied from `setupPluginCompanions` too since the
  interpreter backend re-registering itself as an in-process plugin on first lazy plugin load was
  independently resetting the wrapping back to a raw native — reproduced ONLY cross-module.
  `MapMonoid.empty` uses `Map.empty` again. Verified: 1898/1898 `backendInterpreter` sbt tests pass,
  `std-aggregator`/`std-aggregator-approx` conformance PASS on `int` including the real cross-module
  `groupByAgg` call that originally surfaced this.
- **§9 bridge to `DStream`/`Pipeline` and `Dataset` — DONE** (claim `aggregator-dstream-bridge`,
  2026-08-30). `aggregatorSeqOp`/`aggregatorCombOp` turn an `Aggregator`'s `(monoid, prepare)` into
  the `zero`/`seqOp`/`combOp` triple `aggregatePerKey`/`runFold` already accept, verified by the
  equivalence property the spec itself names: folding two partitions with `seqOp` then merging with
  `combOp` equals folding the whole input with `seqOp` directly. This proves the bridge's mechanism,
  not a live `DStream` integration — no `aggregatePerKey`/windowing/backend was exercised, per the
  spec's own scoping (that needs plugin infrastructure beyond `std/aggregator.ssc`).
  **The live integration is now WIRED** (claim `dstreams-aggregate-per-key`, 2026-08-30,
  owner-approved slice A): `aggregatePerKey(zero)(seqOp)(combOp)` implemented as a native `DStream`
  operator in `v1/runtime/plugins/dstreams-plugin/` (mirrors `combinePerKey`'s DAG-node shape;
  output is `KV(key, accumulator)`, `present` not applied at the raw level), and `std/dstreams.ssc`
  exports `aggregateWith(stream, agg)` — the convenience layer deriving all three from any
  `Aggregator` and applying `present` per key (placed in dstreams, NOT aggregator, so
  `std/aggregator.ssc` stays compilable on lanes with no dstreams plugin). Gated by
  `tests/conformance/std-dstreams-aggregator.ssc` (`backends: [interpreter]` — the plugin is
  v1-interpreter-only): per-key Sum/Count/Variance accumulators equal the reference fold, `mean`
  via `aggregateWith` equals `groupByAgg`, per-key HLL distinct-count equals per-key
  `runAggregator`, the two-partition split-and-merge law holds over REAL pipeline outputs
  (exercising `combOp`; the DirectRunner itself is one partition where `combOp` applies zero
  times, as a one-partition run of any backend would), and empty input yields no keys.
  Fault-injection checked and DEFERRED honestly: the DirectRunner has no retry machinery; the
  retry that exists (`distributed-failure-retry`) is the v1.22 mapreduce actor subsystem,
  unreachable from these lanes — slice B alongside Spark (v2.1.3), see
  `specs/aggregation-algebra.md` §9's fault-tolerance note.
- **§10 rendering — DONE** (claim `aggregator-rendering`, 2026-08-30). `mapToRows`/`renderTableHtml`
  bridge a `groupByAgg` result to `std/ui/data.ssc`'s live-table shape; `jsonStringify` already
  worked with no new code (§10.2). Found landing it: `v.toString` on a `Double` isn't lane-portable
  (`"13.0"` on `run-jvm`'s real Scala vs `"13"` everywhere else) — `s"$v"` string interpolation is
  consistent across every lane and is what `mapToRows` uses. §10.3's chart renderer stays explicitly
  out of scope for the whole document (§13), not just deferred here.
- **§11 effects — base shape DONE** (claim `aggregator-effects`, 2026-08-30). `EffAggregator`,
  `runEffAggregator`, and `ContramapAgg`/`dimapAgg` (§11.3's profunctor shape) all ship, verified
  with `ValidatingSum` over `Option`. **`LiftAgg`/`ZipEffAgg` (composing effectful aggregators) do
  NOT ship** — landing them found a serious front defect (a class parameterized by `F[_]`, holding
  an `F`-involving constructor field, throws a bogus arity error once ANY `val`-fielded class exists
  anywhere in the import graph — which `std/aggregator.ssc`'s own `VarianceAcc` now always is).
  Filed: `v2/BUGS.md`
  `higher-kinded-generic-field-corrupts-arity-of-an-unrelated-val-class-elsewhere-in-scope`. This
  blocks `LiftAgg`/`ZipEffAgg` specifically until either that bug is fixed, or a different design
  is found for composing effectful aggregators that avoids an `F[_]`-parameterized class holding an
  `F`-involving field — worth thinking about explicitly rather than re-attempting the same shape.
  Also found and filed: `run-jvm` emits `.flatMap` on a completely unconstrained generic `F`, which
  real Scala 3 rejects — `v1/runtime/backend/jvm/BUGS.md`
  `jvm-gen-emits-flatmap-on-an-unconstrained-generic-type-param`; `std-aggregator`'s conformance
  case now carries `known-red: jvm` alongside its pre-existing `known-red: js`.
- **`Arrow`/`Category`/a new `Future`/`Task`/`Result` type** — explicitly NOT queued here. §11.4
  gives the reason (`Either`/`! Async` already cover the latter; neither `Arrow` nor `Category` has
  a second concrete use beyond §11.3's `dimap`) and stands until a second genuine use surfaces —
  revisit the reason, not just the deferral, before picking this up.

No gate yet because none of the above has code to gate — the first item taken from this list should
get its own conformance case the same way `std/aggregator.ssc` got
`tests/conformance/std-aggregator.ssc`, mirroring `std/semigroup-monoid.ssc`'s existing pattern.

## cross-module-type-checking — the typer types IMPORTED names loosely, in every front and every backend, so a cross-module type mismatch is never caught anywhere

<!-- status: open
     lane: multi
     kind: feature
     area: front
     gate: none -->

Owner-directed 2026-08-30 ("нужно будет сделать во всех фронтах и бекендах"). Found while
diagnosing `v2/BUGS.md` `trait-typed-parameter-accepts-a-non-conforming-argument` (diagnosis landed
`62b235d59`): the v1 `Typer` (`bin/ssc-tools check`) correctly rejects the single-file repro
(`Type mismatch: expected Dog, found Cat`) and is corpus-clean — 408/408 conformance cases pass
`check`, 9.4s for the whole corpus — but the SAME mismatch through a module boundary passes:
importing `GroupAggregator`/`MinAgg` from `std/aggregator.ssc` and calling `emptyWindow(MinAgg(...),
3)`, `check` says OK, because imported names are typed loosely (effectively `Any`-shaped), so any
argument conforms. That is exactly the motivating case (`specs/aggregation-algebra.md` §7's "should
be a compile-time rejection" claim) — single-file gating alone would not catch it.

The gap is EVERYWHERE, not one component's bug, which is why it is a feature and not a BUGS entry:

- **v1 Typer** — types imported bindings loosely; needs module interfaces (the `.scim`
  `emit-interface` artifact already exists — check whether it carries enough signature detail to
  feed the typer, that is the natural vehicle) threaded into `check`'s environment per import.
- **v2 self-hosted fronts (F + legacy)** — erase parameter types at parse time by design
  (`parseParams`, `specs/v2.2-p6.5-fsub.ssc:2627` — see the trait-param diagnosis in `v2/BUGS.md`);
  a nominal cross-module check there means giving the self-hosted front a typer, sized as a project.
- **v3** — has the `check`-vs-`run` gating question too once its front grows types; note in
  `v3/BUGS.md`/BACKLOG when the time comes.
- **Backends (js/jvm/rust codegen)** — anything type-directed (given resolution, numeric-width
  evidence, arity checks) currently re-derives facts per file and goes blind at the same boundary;
  a shared interface artifact fixes them all at once, per-backend hacks fix them one at a time.

Related, sequenced BEFORE this: gating `run` through the v1 `check` at all (owner approved option
(a), default-on — its own claim) — that lands the single-file guarantee this entry then extends
across module boundaries. Do that first; this entry is the second, larger step.

## process-needs-a-detached-spawn — std/process can only run children it WAITS for, so a served program cannot start anything that outlives the request

<!-- status: fixed
     lane: multi
     area: runtime
     kind: feature
     gate: tests/e2e/process-spawn-gate.sh
     fixed-in: ef44b001d
     reported-by: rozum (sergey-scherbina/rozum, agent claude-code)
     reported-at: 2026-08-16
     ssc-version: bin/ssc-tools built from bad74ecdf
     repro: examples/reported/process-needs-a-detached-spawn.ssc
     impact: blocks
     confirmed: no -->

Routed from `INBOX.md` on 2026-08-16. Everything below is the reporter's, in their words.

We ported fifteen HTTP routes of a control console (rozum's UCC) from Rust to ScalaScript. Every
route that STARTS something — an agent run, a coder session, a terminal, a benchmark — stopped at
the same wall, and it is not "no process control": `std/process` has exactly one primitive and it
WAITS.

    extern def exec(cmd, args, opts): ProcessResult      // stdout, stderr, exitCode

By construction that cannot return before the child is finished. A request handler starting a
five-minute agent run can hold the connection for five minutes or not start it. Measured with the
attached repro, built with `build-rust`:

    before exec
    after exec, child exit 0
    real 2.14        # a /bin/sleep 2 the handler had to sit through

**What would be enough:** a spawn that returns a handle instead of a result.

    case class Child(pid: Int)
    extern def spawn(cmd, args, opts): Child

`pid` alone carries us — the caller records it, and killing already works through
`exec("kill", [pid], …)`. `wait`/`isAlive`/streams are welcome but are not what blocks the port.
Detaching from the parent's lifetime is part of the ask: the child must survive the server that
started it.

**What we do instead:** the Rust half keeps every launch route (`Command::spawn()`, pid into a
registry file, handler returns immediately). The ScalaScript half serves the routes that read
files, call HTTP, or run a child to completion — the boundary between the two halves is exactly
this primitive, not a preference.

### Measured 2026-08-16 while sizing this: the lane it is wanted on ignored ProcessOptions entirely

Both this and its sibling `process-needs-a-stdin-pipe` want to EXTEND `ProcessOptions` on the rust
lane. Sizing that turned up something neither report could have seen: `_exec` took `_opts: O` and
threw it away, so `cwd`, `env` and `inheritEnv` were obeyed under `run` and silently dropped under
`build-rust`. Adding a field to a record the lane ignores would have been write-only metadata that
passed every check. Fixed first (`rust-exec-silently-ignores-every-processoptions-field`, BUGS.md,
gated), which is the prerequisite for either ask here.

**Two more findings, both filed and both in the way of this entry:**

* `rust-exec-ignores-processoptions-timeout` — `timeout` is still accepted and NOT enforced on this
  lane. `std::process::Command` has none, so honouring it means spawn/poll/kill — which is very
  nearly the machinery this entry asks for, so the two should be implemented together rather than
  twice.
* `rust-named-ctor-args-drop-the-defaulted-fields` — `ProcessOptions(cwd = Some("/tmp"))`, the
  spelling anyone would write, does not build at all (E0063, the defaulted fields are not filled in).
  Whatever field this entry adds will be unusable in named form until that is fixed.

**On the shape of the ask itself — I raised a concern and then checked it, and it was wrong.** I
wrote that `Child(pid)` makes this native-only by construction, because `exec("kill", [pid], …)` has
no meaning on js or jvm. A pid does: `Process.pid()` (Java 9) and Node's `child.pid` both give one.
Killing BY SHELLING OUT is the POSIX part, and that is the CALLER's idiom, not this type's. The
concern was mine, not the reporter's, and looking dissolved it — so nothing was blocked on an answer
nobody needed to give.

### Done — `spawn(cmd, args, opts): Child`, five lanes

```scalascript
case class Child(pid: Int)
extern def __spawnPid(cmd: String, args: List[String], opts: ProcessOptions): Int
def spawn(cmd: String, args: List[String], opts: ProcessOptions): Child =
  Child(__spawnPid(cmd, args, opts))
```

**The extern returns an Int and the WRAPPER builds the `Child`**, which is not a style choice: on the
rust lane that struct is GENERATED into the crate from the `case class`, and the runtime template —
emitted verbatim, knowing no user type — cannot name it. Every backend can return an Int.
Constructing `Child` in `.ssc` means all five lanes build the same value from one source, instead of
five hand-written constructions that can disagree about a field.

**The child must OUTLIVE the parent, and that is where an implementation goes wrong quietly.** On the
JVM lanes the standard streams are redirected to `DISCARD` rather than inherited — a child holding
this process's pipes keeps its descriptors alive and shares its fate; on rust, stdio is null and the
`Child` handle is dropped without `wait`, which does not kill or reap; on js it needs BOTH
`detached: true` (its own process group) and `unref()` (release node's event-loop reference), and one
without the other looks correct until the parent tries to exit.

**`timeout` is the one option `spawn` cannot honour, and it does not pretend to** — the call returns
before there is anything to time. `cwd`, `env`, `inheritEnv` and `stdin` all apply. On v2 the option
reading is now a SHARED helper used by both `exec` and `spawn`, so a `cwd` honoured by one and
dropped by the other is not merely absent today, it is unreachable.

**js REFUSES `opts.stdin` for `spawn` rather than dropping it.** With `stdio: 'ignore'` there is no
pipe, and wiring one back means holding a handle open across a call that has already returned. A
refusal is the honest half: the alternative is a token that silently never arrives, which is exactly
what `process-needs-a-stdin-pipe` exists to prevent.

**Not captured, by design:** no stdout/stderr. Reading a pipe means staying to drain it, and staying
is what `spawn` exists not to do. A caller who wants output wants `exec`, or a child that writes its
own file.

**Verified:** `tests/e2e/process-spawn-gate.sh` PASS, 12 rows over run / --v1 / build-rust. Row 4 is
asserted from OUTSIDE the program, because the program is the parent: the child writes a marker three
seconds later and the gate looks for it after the parent has exited. Row 3 asserts "returned before
the child finished" by a FACT — the marker is not there yet — rather than by a stopwatch, which on a
contended host measures JVM startup as much as the primitive. Negative control with every
implementation reverted and the launcher rebuilt: 11 rows red, both interpreter lanes producing
nothing and `build-rust` refusing `spawn` by name. Sibling gates re-run and still green
(`process-stdin-gate`, `rust-exec-options-gate`); `rust-std-survey-gate` 77 REFUSED / 55 COMPILES,
BADRUST not grown; `v1-jit-size` PASS.

`confirmed: no` — rozum has not checked this against their own build.
## process-needs-a-stdin-pipe — exec cannot write to a child's stdin, so a secret can only reach it through argv where every local process can read it

<!-- status: fixed
     lane: multi
     area: runtime
     kind: feature
     gate: tests/e2e/process-stdin-gate.sh
     fixed-in: d6b77103f
     reported-by: rozum (sergey-scherbina/rozum, agent claude-code)
     reported-at: 2026-08-16
     ssc-version: bin/ssc-tools built from bad74ecdf
     repro: examples/reported/process-needs-a-stdin-pipe.ssc
     impact: blocks
     confirmed: no -->

Routed from `INBOX.md` on 2026-08-16. Everything below is the reporter's, in their words.

One route of our control console installs a Telegram bot, which means handing a bot token to a
child process. The Rust implementation writes it to the child's STDIN precisely so it never appears
in an argument vector:

    let child = Command::new(&exe).args(args).stdin(Stdio::piped()).spawn()?;
    child.stdin.take().write_all(token.as_bytes())?;

`ProcessOptions` has `cwd`, `env`, `timeout`, `inheritEnv` — and no stdin — so a ScalaScript port of
that route would have to pass the token as an argument, where any local process can read it:

    $ ps -axo command | grep bot-add
    rozum-gateway messenger bot-add mybot --token 7712345678:AA…

That route is the ONLY one of the whole port blocked for a security reason rather than a capability
one — everything else either moved or is blocked by the detached-spawn gap. A workaround exists and
is worse: writing the token to a temp file and passing the path leaves the secret on disk, which is
what the stdin design was avoiding.

**What would be enough:** one field, or one overload.

    case class ProcessOptions(…, stdin: Option[String] = None)   // written to the child, then closed

A string is enough here — the value is short and known before the call. A streaming handle would be
more, and is not what blocks us.

The attached repro shows the shape we want beside the one that exists; it passes the secret on the
command line, which is exactly what we will not ship.

### The prerequisite is done; the ask itself is not

`ProcessOptions` was INERT on the rust lane until 2026-08-16 — `_exec` accepted it and threw it away
— so a `stdin` field added then would have been accepted, ignored, and the token would have reached
the child through nothing at all. That is fixed and gated
(`rust-exec-silently-ignores-every-processoptions-field`, BUGS.md); `cwd`, `env` and `inheritEnv`
are now read from the record, which is the machinery a fifth field plugs into.

**The field goes LAST in the declaration, and that is not cosmetic.** Every backend reads
`ProcessOptions` POSITIONALLY — the v2 os-plugin by `field(3)`, the rust runtime by `f.get(0..3)`,
because `Value::Obj` carries no field names by design. Appending keeps indices 0–3 valid; inserting
anywhere else silently re-points every existing reader at the wrong field, on lanes whose tests
would still pass.

**Implementation sites, counted rather than assumed** — six, and a fix in one is worth nothing in
the others:

```text
v1/runtime/plugins/os-plugin/…/OsIntrinsics.scala          run --v1
v1/runtime/backend/interpreter/…/BuiltinsRuntime.scala     interpreter
v1/runtime/backend/js/…/JsRuntimeFs.scala                  js
v1/runtime/backend/jvm/…/JvmGenRuntimeSources.scala        jvm
v1/runtime/backend/rust/…/RuntimeModRs                     build-rust
v2/runtime/std/os-plugin/…/OsNativePlugin.scala            bin/ssc run
```

**The security argument in this report should survive into the gate.** A row asserting only "the
child received the value on stdin" passes on an implementation that ALSO leaves it in argv. The
gate that closes this must assert the secret is absent from the child's own `/proc`-visible command
line (or the platform equivalent), because that absence is the whole point of the request.

Blocked on nothing else. Sized ahead of `process-needs-a-detached-spawn` because it is one field
rather than a new primitive, and because it is the only route of that port blocked for a security
reason.

### Done — one field, five implementations, and one hang found on the way

`ProcessOptions.stdin: Option[String] = None` — written to the child, then the pipe is CLOSED so the
child sees EOF. `None` leaves the previous behaviour. Implemented on all five backends that serve
`std.process`, because a field honoured on one lane and dropped on another is the silent divergence
this repository keeps paying for:

```text
v1/runtime/plugins/os-plugin/…/OsIntrinsics.scala          ssc-tools run --v1   ✓ gated
v2/runtime/std/os-plugin/…/OsNativePlugin.scala            bin/ssc run          ✓ gated
v1/runtime/backend/rust/…/RuntimeModRs                     build-rust           ✓ gated
v1/runtime/backend/jvm/…/processRuntime                    jvm codegen          ✓ implemented, not gated here
v1/runtime/backend/js/…/JsRuntimeFs.scala                  js codegen           ✓ implemented, not gated here
```

The jvm and js rows are honest about what the gate does NOT reach: exercising them needs a different
harness, and claiming coverage a script does not have is worse than naming the gap.

**THE CLOSE IS THE LOAD-BEARING HALF, and it exposed a hang that predates this feature.**
`ProcessBuilder` pipes stdin by default, so a child that reads to EOF never sees one:
`exec("cat", List(), ProcessOptions())` blocked FOREVER on the v1 lane, no output, no timeout. The
v2 plugin had closed the pipe since it was written, with a comment saying exactly why; v1 and the
jvm runtime had not. Both now close unconditionally, and the gate's fourth row plus a per-lane
`timeout` is what keeps it that way — a gate without the timeout would not fail there, it would
never finish. Filed as `v1-exec-hangs-when-the-child-reads-stdin` for the record.

**The security property is asserted, not assumed, and its check is itself controlled.** The child
prints its own command line (`ps -o command= -p $$`); one row asserts the token is ABSENT from it,
and the next passes the same token as an ARGUMENT and asserts the same probe DOES see it. Without
that second row the first could be green because `ps` was broken or the token never existed.

**Rust needed a different shape, not just a field.** `Command::output()` wires stdin to null and
hands back no handle, so the stdin path spawns, writes, drops the handle (that drop IS the EOF) and
collects with `wait_with_output()`. The no-stdin path keeps `.output()` byte-for-byte, so every
existing caller emits and runs exactly as before.

**Not fixed, filed:** the js lane still ignores `cwd`, `env`, `timeout` and `inheritEnv` — `stdin`
now works there while its four siblings are silently dropped, which is the same shape the rust lane
had until this week (`js-exec-ignores-every-processoptions-field-but-stdin`).

**Verified:** `tests/e2e/process-stdin-gate.sh` PASS, 12 rows over three lanes. Negative control with
every implementation reverted and the launcher rebuilt: 9 rows red, and the `--v1` lane HANGS at row
4, which is the pre-existing bug above showing itself. `rust-std-survey-gate` 77 REFUSED /
55 COMPILES, BADRUST not grown; `v1-jit-size` PASS; `scripts/smoke-ci` green.

`confirmed: no` — rozum has not checked this against their own build.
## sbt-plugin-build-tool-parity — make it an sbt plugin first, then widen it

<!-- status: open
     lane: apparatus
     area: build
     kind: task
     gate: tests/e2e/sbt-plugin-scripted.sh -->

Sergiy asked what the plugin still needs, having noted that assembling distributions is missing. It
is — and two things ahead of it decide whether the plugin behaves like an sbt plugin at all. Order
agreed with him; **nothing is to be published to Maven** by this work, only the wiring so that
`publishLocal`/`publish` work when he chooses to.

Measured surface: 7 of 67 CLI commands (`build`, `link`, `test`, `run`, `repl`, `watch`,
`generate-facade`), every one of them exercised by a scripted scenario. The gap is surface, not
coverage. Multi-backend already works (`sscBackends`, exercised by `cross-build`).

### S1 — the linked jar reaches sbt's artifacts — DONE

`sscLink` produces a runnable JAR and hands it to nobody: `packagedArtifacts`, `artifacts`,
`addArtifact` and `publish` appear nowhere in the plugin. So a ScalaScript library cannot be
published from sbt at all — which is the most sbt-native expectation there is, and the reason people
install a build-tool plugin in the first place.

Do the wiring only. Publishing anywhere is the owner's action, not this task's.

### S2 — sbt must see the compile's inputs and outputs — DONE

`sscCompile` has no `FileFunction.cached`, no `lastModified` check, nothing: **every** `sbt compile`
forks `ssc build --incremental` unconditionally. The incrementality lives inside ssc, where sbt
cannot see it, so sbt can neither skip the task nor invalidate what depends on it.

Measure before and after rather than asserting an improvement — the claim "this is slow" is mine
from reading the code, not from a stopwatch.

### S3 — the distribution family — build-jvm and build-rust DONE

Not one command but several, and they differ in kind. Checked against `ssc --help`, these are NOT
aliases of `build --backend`, so the existing multi-backend support does not cover them:

| command | produces |
| --- | --- |
| `build-jvm` | a compiler-free executable JAR |
| `build-rust` | a native binary in one step (rust backend + cargo) |
| `build --target desktop` | an Electron bundle |
| `build --target ios\|macos` | a SwiftUI Swift package |
| `emit-spa`, `emit-lib`, `bundle`, `package`, `install` | the rest of the ship set |

`sscBuildJvm` and `sscBuildRust` landed, with `sscDistDir` (default `target/ssc-dist`) and
`sscMainSource`. The two commands differ in a way worth stating: **build-jvm takes many sources,
build-rust takes exactly one**. Rather than pick an entry point, `sscBuildRust` refuses when a
project has several and lists the candidates — silently building the wrong entry is worse than not
building. Scenario `distributions` asserts all three: jvm receives BOTH sources, rust REFUSES with
two candidates, and once told, builds that one and not the other.

`sscBundle` and `sscEmitLib` landed too. `emit-lib` writes a DIRECTORY rather than a file and takes
a `--version`, so the project's own `version` is passed — letting the CLI default to 0.1.0 would ship
a library whose version disagrees with what sbt publishes.

**Two entries in the original list were wrong, and checking the signatures is what caught it:**

- `install` installs the TOOLCHAIN (`ssc install [--prefix <dir>]`), not the user's project. It has
  no business being a per-project sbt task.
- `package` is a **cluster** subcommand (`ClusterCommands`), not a distribution command at all.

Both are struck from this family rather than wrapped.

**`emit-spa` is deferred, deliberately.** It has no output flag — its parser takes files,
`--frontend` and `--server-url` and nothing else — so an sbt task cannot name the artifact it
produced without inventing a location. Wrapping it would mean guessing where its output lands, and a
task whose result path is a guess is worse than no task. Wants a look at where it actually writes
before it gets a wrapper.

`sscEmitSpa` and `sscBuildTarget` landed too, and both needed the CLI read rather than guessed:

- **`emit-spa` PRINTS its html and writes nothing.** The task captures stdout and names the file
  itself — which is what a build tool is for. `SscRunner.runCapture` was added for it, capturing
  stdout only: folding stderr in would paste diagnostics into somebody's generated page, a
  corruption that surfaces in a browser and nowhere else. One html per source, since emit-spa
  renders each file it is given.
- **`build --target` uses `--out`, not `-o`,** and defaults to `target/build` when omitted, so
  passing `-o` would have silently put the bundle somewhere the task did not name. It is an input
  task (`sscBuildTarget desktop <file.ssc>`): the platform is a choice made per invocation, not a
  property of the project.

**The family is complete.** 14 scripted scenarios, all green. Nothing was published to Maven.

**And one of them now uses the REAL toolchain.** The other thirteen mock `ssc`, which proves the
wiring — which command, which arguments, what the task returns — and proves nothing about whether
the toolchain accepts any of it. `real-ssc` points `sscBinary` at the staged launcher and requires a
compile to produce artifacts. It found a defect on its first run: the backend stamp file introduced
by S2 sat in the artifact directory, so `sscCompile` returned it AS an artifact — it fed `sscLink`
and would have been published. No mocked scenario could see that; their mocks write nothing else
there. The stamp moved to the cache directory and the scenario now refuses any plugin bookkeeping
among the artifacts.

(The commit that landed the last two says "14/14". It is 13 — three scenarios were added to the
original ten, not four. Counted rather than remembered, after the gate's own hardcoded "10
scenarios" turned out to be stale for the same reason.)

### Deliberately out of scope

`lsp`, `tui`, `oauth`, `bench`, `cluster`, `search` and friends. Interactive or operational; an sbt
task wrapping them is noise that then has to be maintained and tested.

### The property to keep

Every plugin task has a scripted scenario — that is true today and is what made the deleted fixtures
visible the moment anything ran them. Each slice adds its own scenario.

## sbt-plugin-covers-7-of-67-cli-commands — the matrix, measured

<!-- status: open
     lane: apparatus
     area: build
     kind: task
     gate: tests/e2e/sbt-plugin-scripted.sh -->

Sergiy's question: does the sbt plugin do what the CLI does — including building for several
backends and assembling distributions? Counted rather than estimated.

**The CLI exposes 67 commands. The plugin invokes 7:**

| ssc command | in the plugin | exercised by a scripted scenario |
| --- | --- | --- |
| `build` | `sscCompile` (with `--incremental`, `--backend`) | yes — `compile-sources`, `cross-build` |
| `link` | `sscLink` | yes — `package-link` |
| `test` | `sscTest` (`--output-format junit-xml`) | yes — `test-integration` |
| `generate-facade` | `sscGenerateFacade` | yes — 4 scenarios |
| `run` | `sscRun` | yes — `dev-tools` |
| `repl` | `sscRepl` | yes — `dev-tools` |
| `watch` | `sscWatch` | yes — `dev-tools` |

So everything the plugin invokes IS covered by a scenario — the ten scenarios are well aimed. The
gap is not coverage, it is surface.

**Multi-backend: supported.** `sscBackends` builds each target in one `compile`; a single backend
writes to the flat `sscArtifactDir`, several write to `sscArtifactDir/<backend>/`. Exercised by
`cross-build`.

**Distributions: absent.** None of `bundle`, `package`, `install`, `deploy`, `publish` is invoked,
and neither is any of the `emit-*` family (`emit-spa`, `emit-lib`, `emit-js`, `emit-wasm`,
`emit-rust`, `emit-swift`, `emit-openapi`, …). An sbt build cannot produce a ScalaScript
distribution today.

**The 60 commands the plugin does not reach:**

```
add bench build-jvm build-rust bundle check check-compat check-types
check-with-iface clean cluster compile-js compile-jvm compile-runtime debug deploy
deps emit-interface emit-ir emit-js emit-lib emit-openapi emit-rust emit-scala
emit-spa emit-spark emit-swift emit-wasm emit-wc fmt help info
install lint-jit lock lsp new oauth package parse
plugin preview profile publish render run-batch run-js run-jvm
run-rust run-swift search serve ssc submit toolchain tui
update verify version watch-bench
```

Not all of these belong in an sbt plugin — `lsp`, `tui`, `oauth`, `bench`, `cluster`, `search` are
interactive or operational, and wrapping them would be noise. The ones worth arguing about are the
build-and-ship set: the `emit-*` family, `bundle`/`package`/`install`, `compile-jvm`, `build-rust`,
`build-jvm`, `run-js`/`run-jvm`/`run-rust`, plus `check-types`, `fmt`, `deps`/`lock`/`update`.

### Slices

- [ ] **S1 — decide the intended surface.** Not every CLI command should exist as an sbt task. The
      question to answer first is which ones a *build tool* owes its user, and that is a product
      call rather than a coverage number.
- [ ] **S2 — the emit/ship family**, if S1 wants it: this is what "assemble a distribution from
      sbt" actually means, and it is the largest single gap.
- [ ] **S3 — a scenario per new task.** The ten existing scenarios cover every task that exists,
      and that property is worth keeping: it is what made the fixture loss visible at all.

Context: until 2026-08-08 nothing ran these tests, so a JS commit had deleted their fixtures three
weeks earlier and two scenarios sat red with a third green-but-blind
(`tests/BUGS.md sbt-plugin-fixtures-deleted-by-an-unrelated-commit-and-unrestorable`). They are in
smoke now.

# . — backlog

Can-wait and not-yet-started work whose code lives in `./`. When an item is
picked up it moves to `./SPRINT.md` as `[~]` and gets a row on the root board —
in the same commit as the claim. Layout: `specs/work-tracking-layout.md`.

Sections below were carried over whole from the flat root `SPRINT.md`/`BACKLOG.md`,
verbatim, on 2026-07-30.

## build-rust-mcp-client-unsupported — std/mcp/client.ssc cannot be lowered for the Rust backend (Unsupported(McpClient,rust)) while the SERVER half is supported there, so a .ssc program that consumes MCP runs under the interpreter but can never be built into a binary
<!-- status: fixed
     fixed-in: 28100232d
     lane: v2-rust
     area: codegen
     kind: feature
     gate: tests/e2e/rust-std-survey-gate.sh
     reported-by: rozum (sergey-scherbina/rozum, agent claude-code)
     reported-at: 2026-08-13
     ssc-version: bin/ssc-tools built from 7eecad50a
     repro: the import-only program in the body
     impact: workaround
     confirmed: no -->

Minimal repro — an empty program whose only content is the import:

```scalascript
[McpClient, mcpConnect, Transport](std/mcp/client.ssc)
@main def run(): Unit = println("built")
```

`ssc-tools emit-rust` → `[error] Unsupported(McpClient,rust)`. The same file runs under `ssc run`.

What makes this worth a report rather than a shrug is the asymmetry: `RustCapabilities` already
declares `Feature.McpServer` — "MCP server over stdio (JSON-RPC 2.0, hand-rolled; only serde_json
dep)" — so the Rust backend speaks MCP in one direction. The client half is the missing one, and
`Feature.McpClient` is annotated `// std.mcp client-side (jvm, js)` in the SPI.

Consequence for us: our agent has three implementations of one contract (Rust, Scala 3,
ScalaScript), and the ScalaScript one uses `std/mcp/client.ssc` to reach MCP servers. It runs
correctly under the interpreter — measured 2026-08-01, a real `rozum mcp-proxy` listed its seven
tools and the model called one end to end — but it cannot be built into a binary, so that leg of the
contract can never ship as an artifact.

Not urgent for us: the interpreter path works and the Rust and Scala legs cover shipping. Filed
because "supported in one direction only" is the kind of gap that is cheaper to know about than to
rediscover.

**Routed from `INBOX.md` on 2026-08-13. Everything above the line is the reporter's, in their
words. What follows is ours.**

**Reproduced from the reporter's own repro, unchanged:** `ssc-tools emit-rust` on the import-only
program answers `[error] Unsupported(McpClient,rust)`. The asymmetry they name is exact and I
confirmed it independently before reading the report — `RustCapabilities` declares
`Feature.McpServer` and not `Feature.McpClient`, and the survey baseline already records the
consequence in three rows:

    std/mcp/client.ssc   REFUSED  Unsupported(McpClient,rust)
    std/mcp/index.ssc    REFUSED  Unsupported(McpClient,rust)
    std/agent-mcp.ssc    REFUSED  Unsupported(McpClient,rust)
    std/mcp/server.ssc   COMPILES

So this is a declared gap rather than a codegen slip, which is why it is `feature` and not `bug`.

**Sized against what already exists.** The server half is `McpRs`, 101 lines: a tool registry and
a stdio loop answering `initialize`, `tools/list` and `tools/call`, hand-rolled on `serde_json`
alone. The client is its mirror and needs no new crate either — `std::process::Command` with piped
stdio for `Transport.Spawn`, the same JSON-RPC framing, and responses matched by id. What must be
built: the runtime template, the `mcpConnect` / `listTools` / `callTool` / `close` intrinsic
lowerings, and the `Feature.McpClient` declaration. `Transport.Http` and `Transport.Ws` stay
refused, which is consistent — the server is stdio-only on this lane too, so the boundary does not
move.

**SIZED BY EXPERIMENT, 2026-08-13, and the estimate was wrong twice before it was right.**
Declaring `Feature.McpClient` and building — with no runtime, no intrinsics, nothing else — is a
one-line change that answers what the real wall is, and it is cheaper than reasoning about it:

- The reporter's repro **emits successfully** with only that line. It is import-only, so it
  exercises the CAPABILITY CHECK and nothing else; passing it proves nothing about lowering.
  Worth stating, because "the repro now builds" would have been a false close.
- A program that actually CALLS the client gives the real answer, and it is good news:

      Generic(`mcpConnect` is declared `extern` and the rust backend has no implementation
              for it (no `@rust(...)`, no intrinsic); called from `run`)

  …and the same for `listTools` and `close`. **The lane refuses neither the receiver shape nor
  the overloading.** Both were prior suspicions worth naming: an `extern class` with eight
  methods where the server half is flat functions, and `mcpConnect` declared at two arities on a
  lane that refuses overloading elsewhere — it is what makes `std/mcp/types.ssc` REFUSED. Neither
  is the blocker here. Member-style intrinsic keys already exist (`Console.println`,
  `Bench.opaque`), so `McpClient.listTools` maps the same way.

**So the work is four bounded parts, not an open question:** the `Feature.McpClient` line; nine
intrinsic map entries (`mcpConnect` plus the eight methods); a `McpClientRs` runtime mirroring
`McpRs`'s framing over a spawned child's stdio, no new crate; and `scanMcpClientUsage` plus the
emission wiring in `RustGen`, copied from the server's.

**ONE OF THE TWO UNKNOWNS IS NOW ANSWERED, AND IT IS THE BLOCKER.** `std/mcp/client.ssc`
imports `std/mcp/types.ssc`, which is REFUSED — and the reason is not MCP's. The lane flattens
members of distinct receivers into one namespace and refuses on a name collision, calling it
overloading: `object Tool: def text` versus `object Resource: def text`. Worse for this entry,
fixing that file is not enough, because `McpClient.close` collides with `SseStream.close` from
`std/http.ssc` the same way. Filed as its own defect,
`rust-member-names-are-flattened-so-two-receivers-cannot-share-a-member` in `BUGS.md`, with a
six-module census. **This entry is blocked on it**: the nine intrinsic entries and the runtime
are still the right work, and none of it can compile until member names are qualified.

**The other unknown remains:** how `Transport.Spawn(cmd,
args)` — an `.ssc` enum — arrives at the intrinsic, and whether the return types drag in
`std/mcp/types.ssc`, which is REFUSED for an unrelated overloading reason and would block this
independently of anything done here.

**The capability line is deliberately NOT left declared.** Declaring support with no
implementation turns an honest `Unsupported(McpClient,rust)` into "no implementation for it" at
every call site — a promise the lane cannot keep, which is the worse direction of error and the
one this migration has spent the day avoiding.

**The reporter's own priority is recorded and respected:** not urgent for them, the interpreter
path works and their Rust and Scala legs cover shipping. Filed so the asymmetry is known rather
than rediscovered, which is the right reason to file.

**MEASURED 2026-08-14, AND IT FALSIFIES THE PLAN ABOVE — the nine intrinsic entries do NOT lower
the eight methods, and adding them is worse than doing nothing.**

The sentence above says "member-style intrinsic keys already exist (`Console.println`,
`Bench.opaque`), so `McpClient.listTools` maps the same way". They do not map the same way, and the
difference is the whole feature. `Console.println` is an OBJECT-QUALIFIED name; `c.listTools()` is a
method on a runtime VALUE. Four entries were added experimentally — `mcpConnect`, `listTools`,
`callTool`, `close` — pointing at `crate::runtime::mcp_client::_*`, and the emitted `main` is:

    let c = crate::runtime::mcp_client::_mcp_connect(Transport.stdio("echo", vec![…]));
    let ts = c.listTools();
    c.close();

The FREE function lowered. Both RECEIVER methods came out VERBATIM. The intrinsic map is consulted
for free names only.

**AND THE FAILURE IS SILENT-SHAPED, which is why this is recorded loudly.** The unimplemented-extern
refusal keys on the BARE name — `intrinsics.contains(QualifiedName(n))` at
`RustCodeWalk.scala:259` — so adding the entry SATISFIES THE CHECK while lowering nothing. It
converts an honest `no implementation for it` into rustc complaining about `no method named
listTools` in code the user never wrote. That is exactly the trade this entry's own last paragraph
refuses to make for the capability line, and it applies to the intrinsic entries for the same reason.

**The other mechanism does not cover it either.** `@rust("expr")` renders an extern def as a free
`pub fn`; an `extern class` member carrying the same annotation emits NOTHING AT ALL — measured with
a two-line probe, `extern class Handle: @rust("1i64") def num(): Int` produced no function and no
`impl`. So the lane has NO lowering for extern-class members by either route.

**What the work actually needs, restated:** receiver-method lowering for extern-class members —
either an inherent `impl` per extern class, or a rewrite of `recv.m(args)` to `m(recv, args)` when
`m` is an extern member. That lives in `RustCodeWalk.scala`. The runtime, the `Feature.McpClient`
line and the `RustGen` wiring are still right and still bounded; they are simply not sufficient, and
none of them can be verified until a call site lowers.

`Transport.stdio(…)` in the same emitted line is the object-member flattening defect
(`rust-object-member-call-emits-invalid-rust`), so that blocker stands here too and is now confirmed
by emission rather than inferred.

**Not landed, deliberately: the experiment was reverted.** No capability line, no intrinsic entries.
The refusal this entry describes is still the honest one, and it is better than the alternative
measured above.

**DONE — landed 28100232d. `std/mcp/client.ssc` and `std/mcp/index.ssc` COMPILE, and a built Rust
binary drives a built Rust MCP server end to end:** `open=true / tools=1:greet / call=hello from
ssc`.

**The SDK-shaped members were not implemented, and could not be.** `listTools(): List[
ToolDescriptor]`, `callTool(…): ToolResult` and `mcpConnect(t: Transport)` traffic entirely in
GENERATED types, which exist only once a program reaches them — no runtime can write a signature
naming one. So `client.ssc` grew a PRIMITIVE surface beside them: `listToolNames`, `callToolText`,
`readResourceText`, `isOpen`, and a free `mcpConnectSpawn(command, args)`, in strings, lists of
strings and booleans only. The SDK members are untouched; JVM and JS are unaffected, and on Rust
those members refuse only if a program calls them.

**The handle is an `i64`,** because `mapType` sends an unknown type name there — so `extern class
McpClient` is naturally an index into a table the runtime owns, and its members lower as FREE
FUNCTIONS WITH THE RECEIVER FIRST. An extern class has no Rust type here, so a member cannot be an
inherent method.

**Two more places where a bare name was the wrong key** — the third and fourth in this chain. The
member intrinsic is keyed `Class.member`, because a bare `close` says nothing about whose it is and
std has six. And the unimplemented-extern REFUSAL keyed on the bare name too, so it fired on members
that lower perfectly well; it now resolves the owner by def POSITION.

**Measured against a prediction stated first:** client.ssc moves, the other two not predicted
because a refusal short-circuits. Result: REFUSED 80 → 78, COMPILES 52 → 54, BADRUST 0. client.ssc
and index.ssc COMPILE; `std/agent-mcp.ssc` stays REFUSED with its REASON CHANGED — the capability
gate was its first blocker and the overloading refusal behind it is now its first, which is trait
and given-instance members rather than object members.

**Gate:** `tests/e2e/build-rust-refuses-loudly.sh` builds BOTH halves and runs them; an emission
check would pass on a client that connects and then hangs, and the survey cannot see any of it
because client.ssc is a declaration module. Negative control: with the receiver map emptied it fails
with `the MCP client half does not build`.

**The SDK-shaped members are DONE too.** `listTools()` returns real `ToolDescriptor`s with name and
description; `callTool(name, Map(…))` takes the map, delivers it to the server as JSON and returns a
`ToolResult` whose `content: List[Content]` destructures as `case Text(t)`. `listResources` and
`readResource` are wired the same way.

**The mechanism is general, not MCP-specific.** The runtime answers in `Value`, naming a generated
type only as a STRING tag in `Value::Obj`, and the CALL SITE assembles the declared type — struct,
enum variant (including a field-less one, which is `Role::User` and not `Role::User {}`), or a list
of either, recursing through field types. Arguments cross the other way by being coerced to their
DECLARED parameter types, which is what lifts a `Map("k" -> "v")` literal at a `Map[String, Any]`
parameter; without it the argument is a `HashMap<String, String>` and rustc rejects it.

**Three defects of my own on the way, each found by running rather than reading:** the enum arm
matched case CLASSES too, because a standalone struct carries its own name in `enumName`; a field
typed `List[Content]` went through `coerceFromValue`, which leaves a user type a `Value`; and the
argument side was not coerced at all. All three were compile errors rather than wrong answers, which
is the good direction.

**The gate destructures rather than counts.** A variant rebuilt with the wrong tag still compiles
and still reports one element — only `case Text(t)` tells the difference — and the server echoing
`{"k":"v"}` is what shows the argument arrived as JSON. Negative control: with the struct assembly
suppressed the gate fails on that case.

**ALL NINE MEMBERS NOW WORK, and the two that were left are done.** `listPrompts` and `getPrompt`
needed what the note below them said they needed — `prompts/*` on the wire — and the reason nothing
had exercised one is that the ssc MCP SERVER answered only `initialize`, `tools/list` and
`tools/call`. So the server grew `resources/list`, `resources/read`, `prompts/list` and
`prompts/get`, with `mcpRegisterResource` and `mcpRegisterPrompt` beside `mcpRegisterTool`.

**THAT ALSO CLOSES CODE I SHIPPED WITHOUT A GATE.** `listResources` and `readResource` were wired in
28100232d and 4997222cd and never exercised — there was no server to call them against, so no case
could cover them. Naming that plainly rather than leaving it in a footnote: it is the exact failure
this repository's own notes describe as write-only code, and it stood for two commits.

**`Role` is the interesting half.** `Message(role: Role, content: Content)` puts a FIELD-LESS enum
variant inside a struct inside a list, and Rust spells that `Role::User`, not `Role::User {}`. The
adapter had handled it since the SDK-member work and it had never once been built; the gate now
destructures all three roles, because a variant rebuilt with the wrong tag still compiles and still
counts one message.

**Capabilities are DERIVED, not announced.** `initialize` advertises `resources` and `prompts` only
when something is registered — a client told a server has resources and then handed an empty list
has been told something untrue. An unknown uri or prompt name answers `-32602` rather than empty
content, because "no such resource" and "a resource whose text is empty" are different answers and a
client cannot tell them apart from the same shape. Also found while
probing this and filed separately: a QUALIFIED enum PATTERN, `case Content.Text(t)`, is refused
(`rust-qualified-enum-pattern-is-refused`). The qualified CONSTRUCTOR was fixed in `19ebadf00`; its
pattern twin was not.

## mcp-2026-07-28 — speak the stateless MCP revision, dual-era

Spec: [`specs/mcp-2026-07-28.md`](specs/mcp-2026-07-28.md). Cross-module by construction —
`mcp/common`, `v1/runtime/std/mcp-plugin`, the jvm/js runtimes and `v2/runtime/providers/mcp-plugin`
all speak the protocol, which is why this sits in the root backlog and not a module's.

We advertise `2024-11-05` while implementing roughly the `2025-06-18` feature set. Upstream
`2026-07-28` drops the `initialize` handshake entirely and moves version, identity and
capabilities into per-request `_meta`. Decision: serve **both** eras off one server — the spec
names that configuration "dual-era" and gives it a passing row in its own compatibility matrix.
A cutover would break every currently-shipping client for the sake of one constant.

Free by accident: `Mcp-Session-Id` and SSE resumability are the revision's two hardest removals
and we never built either (grep, 2026-08-09, zero hits repo-wide).

- [~] **P1 — core stateless envelope.** `server/discover`; `_meta` request context and its
  validation (`-32022` unsupported version, `-32602` missing `clientCapabilities`); `resultType`
  + `serverInfo` stamped by ONE post-processing site so the legacy path stays byte-identical and
  the gate can prove it.
- [x] **`mcp-module-extraction` — make the library's independence structural.** Sergiy,
  2026-08-09. ✓ Landed: the 44 v1-free `Mcp*`/`OAuth*` test files moved to
  `mcp/common/src/test/` as **44 renames, 0 insertions, 0 deletions**; no `build.sbt` change, no
  new dependency. `mcpCommon/test` 462 green, the 7 genuinely-v1 files stay put, v1 module 71
  green. The v1/v2 plugins were already thin per-front adapters and were not touched.
  **Deliberately NOT done — see `std-is-not-v1s-std` below:** moving `std/mcp/*.ssc` out of
  `v1/runtime/std/` would make MCP the one exception among 49 and gain nothing.
- [x] **P1b — version honesty.** ✓ 2026-08-09, and the constant reached `2025-06-18` on
  2026-08-13 (`e2e1955c8`) — not at the same time, because the audit this line asked for found
  three missing requirements rather than the one the plan named. `specs/mcp-2026-07-28.md` §13.
  Original: legacy `initialize` echoes the client's version when supported;
  bump the legacy constant to `2025-06-18` at all four call sites at once, after auditing that we
  really implement it. Split out of P1 deliberately: three of those sites are in files P1 does not
  claim, and bumping the shared constant without the matching negotiation is the
  second-decision-site trap.
- [x] **P2a — CacheableResult.** ✓ 3eb1e5e47. `ttlMs`/`cacheScope` on **six** operations, not the
  five written here — `server/discover` is cacheable too. `cacheScope` derived from whether auth
  is on; `resources/read` defaults to `ttlMs: 0` because a user handler's freshness is not ours
  to assert. `tools/list` order was already deterministic (insertion-ordered registry), so that
  line needed no work — checked rather than assumed.
- [x] **P2b — server-side header validation.** ✓ 7eef2525b. `MCP-Protocol-Version`, `Mcp-Method`,
  `Mcp-Name` vs the body, `-32020` on any disagreement, base64 sentinel decoded first, modern
  requests only. **The named gap is CLOSED:** `McpHttpRouteTest` was written for exactly it and
  drives the real route through `PluginContext.fromNative`, so the feed is now proven and not
  merely read. Original gap text: the plumbing in `McpIntrinsics.dispatchAuthorized` is verified
  statically only — nothing drives that function in any test — so the validator is proven and the
  feed is not.
- [x] **P2c — `x-mcp-header`.** ✓ 2026-08-10, server half. §4/§6 of the spec.
  Original: tool params mirrored into `Mcp-Param-{Name}`. Own constraint set
  (primitives only, no `number`, statically reachable via `properties` chains), and a conforming
  client must EXCLUDE offending tools from `tools/list`. Bigger than it looks.
- [x] **P2d — the client half.** ✓ 2026-08-10…12 as P2d-1 … P2d-4b. All three clients now
  negotiate through one shared `negotiateEra`; the duplication that made this look large was
  deleted rather than triplicated. The sentence below about the client is no longer true and is
  kept as the dated plan. Original: `McpHttpClient` speaks the legacy era only: it sends
  `initialize` and emits neither `_meta` nor the mirrored headers.
- [x] **P3 — MRTR.** ✓ P3a 2026-08-10, P3b and P3c 2026-08-12. The default is a PARKED VIRTUAL
  THREAD (Sergiy's decision): the handler runs once and continues where it stood. `Replay` and
  `ParkThenReplay` are opt-in and carry an idempotence precondition. Spec §8.5b, §8.8, §8.9.
  Original: largest semantic change — `McpServerBuilder.request(...)` stops being the mechanism.
- [x] **P4 — `subscriptions/listen`.** ✓ 2026-08-10, P4a and P4b-1 … P4b-4, including stdio
  cancellation. Spec §6.
- [x] **P5 — deprecations, extensions, auth.** ✓ P5a (RFC 9207) 2026-08-12, P5b 2026-08-12,
  P5c-1/P5c-2 (Tasks) 2026-08-13. Three of the items listed here turned out to have NO SUBJECT —
  `application_type` on DCR, credentials keyed by issuer and Client ID Metadata Documents are
  client-side and we implement the authorization SERVER — and Sampling was never implemented at
  all. Recorded as non-items with the evidence: spec §9, §9.3, §10.

**Status: the migration is complete** (2026-08-13). Every phase above is landed; the spec's own
phase list carries 21 landed and 4 superseded-by-sub-phase entries and nothing without a status.
The legacy answer is `2025-06-18` and the modern one `2026-07-28`, served off one dispatcher.

### Queued after the migration (2026-08-13)

**The framing of the first item was corrected by measuring it.** It was written here an hour
earlier as "pre-existing and structural, closing it is its own piece of work" — too mild. The
gap is not v2 completeness; it is the reason the 2026-07-28 work is not usable where users run.
`ssc run` IS the v2 native lane, and each of `elicit`, `asTask`, `requestState`, `isCancelled`,
`log` and `notifyToolsListChanged` failed there with `no field '<x>' on named-method-obj` on a
freshly built toolchain. The interpreter has all forty, but `ssc run --v1` needs the optional
compatibility tier, so a standard install has only the lane that was missing them.

Nothing was red about it because the entire MCP corpus — both examples and the conformance case —
uses `tool`, `onConnected` and `onDisconnected`: three of the four members v2 had. The gate was a
real differential and never crossed the boundary. `v21-standard-mcp-smoke.sh` now carries a case
that does, proven in both directions.

- [x] **v2 MRTR core.** `setMrtrMode`, `asTask`, `clientSupportsTasks`, `requestState`,
      `setRequestState`, `isCancelled` reach the default lane, plus the boundary-crossing gate.
- [x] **`elicit` on v2.** ✓ 2026-08-16. Both measurements taken before any code, and both changed
      what the work was.
      **(1) The return shape.** v1 builds `PluginValue.instance("ElicitationResult", …)` with NAMED
      fields — and NOTHING DECLARED IT. Neither `ElicitationResult` nor `elicit` appeared in any
      `.ssc`; v1 reached them because its records answer field access by name. So the problem was
      not that the two lanes might disagree, it is that there was no contract to agree WITH. Both
      are now declared in `std/mcp/types.ssc` and `std/mcp/server.ssc`, and the declared FIELD ORDER
      is what a positional lane builds against.
      **(2) The MRTR signal through `context.invoke`.** It survives — measured, not assumed: a
      Throwable raised inside a v2 handler propagates out and reaches the shared core, which answered
      `isError: true` carrying the message. The park path is therefore reachable from v2.
      **The `.ssc` surface lost an overload, deliberately.** `elicit` was declared at two arities;
      a v2 plugin member is resolved by `getField(name)` — a value handed back before any argument
      exists — and every `Value.ClosV` carries a FIXED arity, so one name cannot serve two. v1's
      variadic native could, which is how the overload survived unnoticed. It is now ONE declaration
      with `timeoutMs = 0` defaulted, which both protocols can express. Verified that the default is
      filled at the call site rather than assumed: a two-argument call reaches the arity-3 closure.
      **AND IT CANNOT SUCCEED ON EITHER LANE, for a reason that is not v2's.** `elicit` blocks the
      handler waiting for an answer that can only arrive through the single-threaded serve loop it
      is blocking. Same program, same driver, same 60 s timeout on `ssc run` and `ssc run --v2`; a
      client sending an ordinary `tools/list` during the window gets nothing for the full 60 s,
      which is what rules out the driver. Filed as `mcp-elicit-deadlocks-the-serve-loop`. The fix is
      to raise the MRTR `InputRequiredSignal` instead of waiting — the machinery exists and this is
      the one caller that does not use it.
- [~] **The remaining `srv` members on v2**, in groups that stand alone: auth (7),
      roots/sampling — **DONE**, notifications and progress — **DONE**, registration — **DONE**,
      completions — **DONE**, paging — **DONE**, subscriptions — **DONE**, `currentLogLevel` —
      **DONE**. Counted by running the two member lists against each other rather than by reading
      §11.1: 40 members on v1, 33 on v2, **7 left — all of them auth, and all BLOCKED**, not
      merely unported. Auth runs in `McpServerCore.authorizeHttp`, reached only from
      `handleHttpRequest`; the stdio loop `serve(...)` — the one v2 uses — never consults the
      validator, and v2's `serveMcp` refuses every transport except `Transport.Stdio` by name.
      Implemented today the six setters would resolve, accept arguments, set builder state and have
      no observable effect anywhere, with no wire for a gate to read: the exact shape this project
      spent the week removing. `useAuthServer` waits on a second thing — it resolves through
      `OAuthBridge.authServers`, and `v2/runtime/providers/` has no oauth plugin at all.
      Filed as `mcp-v2-auth-cannot-be-ported-until-v2-serves-http`. **The next MCP item is
      therefore an HTTP transport for the v2 provider, not seven more members.**
      ✓ 2026-08-16 — roots and the raw request: `clientSupportsRoots`, `onRootsListChanged`,
      `listRoots`, `request`, plus `Root(uri, name)` DECLARED in std/mcp/types.ssc — the same
      undeclared-record shape `ElicitationResult` had. `clientSupportsRoots` is driven BOTH WAYS
      from one program, with and without `roots` in the client's initialize capabilities, because a
      member hard-wired to either answer passes a one-sided case; the control that returns a
      constant `true` reds exactly the second half. `listRoots` and `request` BLOCK over stdio for
      the reason `elicit` does (mcp-elicit-deadlocks-the-serve-loop), so the row asserts the
      REQUEST on the wire with `timeoutMs = 1`, not its answer.
      ✓ 2026-08-16 — `prompt` done, and it was the one member on this list that was already
      DECLARED in `std/mcp/server.ssc`: the declared surface and the default lane disagreed, so a
      conforming program died on `ssc run` with `no field 'prompt'` while the interpreter served it.
      Doing it surfaced a SECOND defect that was not on any list, in a member counted as DONE:
      `srv.resource` never decoded its handler's result, so `resources/read` answered with
      `ResourceResult("mem://a", List(Text("BODY-42")))` — the rendered VALUE — as the resource
      body. The lesson for the rest of this item: **the census counted whether a member exists, and
      a member that exists can still answer the wrong bytes.** Both filed
      (`mcp-v2-srv-prompt-missing`, `mcp-v2-resource-body-is-show-output`) and gated by a row that
      compares the WIRE on both lanes, since stdout and resolution checks are blind to it.
      The remaining members should be taken the same way: implement, then drive against the
      interpreter as the oracle, not against the fact that the call returns.
      ✓ 2026-08-16 — registration group CLOSED: `toolWithSchema` and `resourceTemplate` implemented
      on v2 and DECLARED in `std/mcp/server.ssc`, which neither was. Taken the way the line above
      asks for, and the gate row asserts the two bytes a resolution check cannot see: the SCHEMA
      GIVEN reaches `tools/list` (a member that discarded its schema argument would still list a
      tool and still answer calls — `properties.a.type` is what separates them), and the template
      SUBSTITUTES, so `resources/read mem://note/7` reaches the handler as the concrete uri rather
      than as `mem://note/{id}`. Two controls, one per byte: discarding the schema reds the first
      and leaves the template correct; passing the template instead of the uri reds the third and
      leaves the schema and the template LISTING correct — so the assertions are independent.
      ✓ 2026-08-16 — subscriptions, paging and completions CLOSED: `onResourceSubscribe`,
      `onResourceUnsubscribe`, `setPageSize`, `currentPageSize`, `completionForPrompt`,
      `completionForResource`, all declared and driven on both lanes. The completions assertion is
      the one worth copying: `completion/complete` answers `{"values":[]}` for a MISSING handler by
      design (graceful degradation, per spec), so a success check would pass on a member that does
      not exist — the row derives the suggestions FROM what the client typed instead.
      **A control that removes a member entirely proves less than it looks.** The first three
      controls all reported the SAME first assertion, because a missing member kills the builder
      block before the server starts, so nothing reaches the wire. Re-run as NO-OP controls — member
      present, effect removed — each named its own assertion (`nextCursor`, `values`,
      `notifications/subbed`). That is the version that shows the assertions are independent.
      ✓ 2026-08-16 — notifications, progress and logging CLOSED on v2: all eight
      (`notifyToolsListChanged`, `notifyResourcesListChanged`, `notifyPromptsListChanged`,
      `notifyResourceUpdate`, `notifyProgress`, `notify`, `log`, `currentLogLevel`) implemented and
      driven on BOTH lanes. THREE of them are CONDITIONAL and the gate sets each precondition up,
      because without them the row would pass against a member that does nothing: subscribe before
      `notifyResourceUpdate`, `logging/setLevel` before `log`, `_meta.progressToken` on the call
      before `notifyProgress`. Control: dropping one member on v2 reds that frame on `--v2` only.
      ALL EIGHT ARE NOW DECLARED. They were not, for a day: declaring a member whose parameters are
      empty or all plain scalars, with no default, made the interpreter serve the `__extern__` stub
      instead of the plugin, so the member worked UNDECLARED and died DECLARED. Both that
      (`interp-declaring-a-plain-extern-class-member-breaks-it`) and the second defect the same work
      surfaced (`interp-same-name-class-methods-collapse-to-the-last` — a `.toMap` kept only the
      last of two same-name methods, so `c.f(7)` answered `missing argument for parameter 'b'`) are
      FIXED, and the declarations went back. The constraint that survives for the remaining groups
      is narrower than it looked: one signature with a default is still the right shape for a v2
      member, because `getField(name)` produces the value BEFORE arguments exist and every `ClosV`
      carries a fixed arity — that is about the v2 plugin protocol, not about the interpreter.
- [x] **`specs/mcp.md` §11 — the open design questions** ✓ 2026-08-17 — re-read against the tree,
      every bullet now carries a measured verdict (ANSWERED / UNBLOCKED / STILL OPEN / SUPERSEDED).
      Three corrections fell out, and the count was one of them: **ELEVEN bullets, not thirteen** —
      the number lived in this line and had never been recounted.
      * §11.4's premise is half false. "Hide the init handshake" is not what the code does: three
        capability predicates read it, and TWO of them (`clientSupportsElicitation`,
        `clientSupportsTasks`) were implemented on both lanes and declared NOWHERE. Declared here,
        and the smoke gate now drives all three against a deliberately PARTIAL advertisement
        (roots + elicitation advertised, tasks not) — a predicate wired to a constant or to the
        wrong key cannot produce `roots=true elicit=true tasks=false`.
      * §11.5 is UNBLOCKED, not blocked: it defers "once Generators land" and `std/generators.ssc`
        ships at 0.1.0. **A deferral with a named precondition has to be re-read when that
        precondition lands, and nothing did.** Streaming resources are now a build, not a wait.
      * §11.2 is half answered by `toolWithSchema`, which shipped by another route; what remains is
        DERIVING the schema from a type.
- [ ] **Generator-backed streaming resources** — §11.5's precondition is met (`std/generators.ssc`
      0.1.0) and nothing in `std/mcp/` consumes it. This is the item §11.5 was waiting for.
- [x] **User-facing MCP documentation** ✓ 2026-08-17 — `docs/mcp.md`, written by RUNNING every
      example, and the outputs on the page are pasted from the run. The complete server is
      EXTRACTED FROM THE PAGE by `tests/e2e/v21-standard-mcp-smoke.sh` and driven on both lanes, so
      the doc cannot drift silently: the control that changes one greeting word in the example reds
      the gate with `wanted: "text":"hello ann"`.
      The warning in the old version of this item was right and now has evidence: **the MCP examples
      already in `docs/tutorial.md` did not compile.** `Message.user(...)` — no such method, `Message`
      is a case class — and `Transport.stdio`, where the enum case is `Stdio`. Both fixed, and the
      tutorial now points at the page that is actually driven.
      Found on the way, and NOT fixed here: 107 of the 143 links in `docs/README.md` resolve to
      nothing, all with one cause (`X.md` where `../specs/X.md` was meant). I repaired the one link
      this claim owns and filed the census as
      `docs-readme-links-107-of-143-point-at-files-that-are-not-there` — fixing the two I cared about
      and calling the index fixed would have been true about my two and false about the index.
- [x] **`std/mcp/server.ssc` says "Not available on interpreter"** ✓ 2026-08-16 — and the line was
      HALF wrong, which is why it was checked rather than just corrected. The interpreter part is
      false: it declares `Feature.McpServer` and, driven over stdio, answers `initialize` with
      `2025-06-18` advertising tools, resources, prompts, logging and completions — capabilities
      byte-identical to the native lane's, differing only in `serverInfo`. NOT compared against
      `jvm`/`js`: an earlier draft of this line called it "the fullest surface of any lane", which
      was a comparison against two lanes nobody had driven. The `scalajs-spa` part is TRUE: that
      backend declares only
      `Feature.McpClient`. Both the descriptor and a second sentence in the prose said the same
      false thing; both now name the interpreter and the native lane and keep the scalajs-spa
      exclusion.

Not a goal: removing the legacy era. Not until legacy traffic is measurably zero.

## std-is-not-v1's-std — ✓ CLOSED 2026-08-09, in two steps and not the way it was written

The entry below was written from a wrong premise and is kept for the reasoning, not the plan.
**It said "49 shared std modules". Measured, there were two populations, not one:**

- **108 `.ssc` modules — genuinely shared,** zero v1 imports, every one staged into the native
  front. Moved to the repo-root `std/` (`std-to-repo-root`, 3724798dc).
- **42 Scala modules — genuinely v1's.** Every dependency they have lives under `v1/`:
  `scalascript.backend` → `v1/runtime/backend/spi`, `scalascript.ir` → `v1/lang/ir`,
  `scalascript.plugin` → `v1/runtime/scalascript-plugin-api`, `scalascript.interpreter` →
  `v1/lang/core`. Moving them to a root `std/` would produce modules whose every import points
  back into `v1/` — that hides the coupling rather than removing it. They stay.

What was left afterwards was not a misplacement but a **lie in a directory name**:
`v1/runtime/std/` held **zero** `.ssc` and 42 plugin modules, while
`specs/project-partitioning.md` §4 had already called the shared word "a genuine trap for a
reader". Closed by renaming it to `v1/runtime/plugins/` (Sergiy chose the name, 2026-08-09), so
"std" now means one thing everywhere: the root `std/` and `v2/runtime/std/`.

<details><summary>Original entry (superseded 2026-08-09) — the premise was half wrong</summary>

## (superseded) 49 shared std modules live under a directory named `v1`

Found while doing `mcp-module-extraction` (2026-08-09), and it is why the `.ssc` half of that
task was deliberately left undone.

`v1/runtime/std/` holds **49** std modules — `json-plugin`, `sql-plugin`, `auth-plugin`,
`mcp-plugin`, and the `.ssc` API files beside them. `build.sbt:2360` sets
`stdSourceRoot = v1/runtime/std` and stages every `*.ssc` under it into
`bin/lib/standard/**native-front**/`. So this directory is **already the whole toolchain's std
library, serving both fronts** — the `v1/` in its path is a name that stopped being true and
now misleads every reader into thinking a shared library belongs to one front. It cost real
minutes on 2026-08-09: the natural reading of "mcp lives under v1" is that mcp is v1's, and it
is not.

Moving one module out is the wrong fix and was rejected: it makes that module the single
exception among 49, needs a special case in what is currently one `stdSourceRoot` path, and
buys nothing because the files already serve both fronts.

The right fix is repo-wide — relocate the whole tree (`std/` at the root, say) and repoint
`stdSourceRoot`, the two staged trees, and whatever else resolves that path. That is a large
mechanical change with a real blast radius across 49 modules and the launcher staging, so it
is **Sergiy's call, not an agent's**: raising it here rather than doing it quietly under an
unrelated claim. Blocked on that decision.

</details>

## generated-rust-unreachable-pattern-is-an-unread-diagnostic — rustc names a whole class of walker defects in every build and nothing reads it

<!-- status: fixed
     lane: native
     area: build
     kind: apparatus
     gate: tests/e2e/rust-std-survey-gate.sh
     fixed-in: 0f1d8046b
     reported-by: claude-code
     reported-at: 2026-08-16
     ssc-version: 8f8c65b1d
     repro: none
     impact: workaround -->

Found while fixing `rust-type-pattern-on-a-local-val-matches-anything` (BUGS.md). That defect was
reported by a human comparing two implementations byte for byte, after it had reached a running
server. The compiler had already found it, in the same build, twice:

```text
warning: unreachable pattern
   = note: `#[warn(unreachable_patterns)]` (part of `#[warn(unused)]`) on by default
```

one per broken function, printed and discarded. `build-rust` reads rustc's exit status and its
errors; its warnings go nowhere.

**In GENERATED code an unreachable pattern is not a style note.** A human writing a dead arm has
made a tidiness mistake. The walker cannot: every arm it emits came from an arm the user wrote, so
"this arm can never fire" means the walker dropped the thing that discriminated between them — which
is precisely the defect above, and it is silent by construction, because the wrong arm still returns
a plausible value.

The proposal is narrow on purpose: fail `build-rust` on `unreachable_patterns` in the crate's own
generated modules, not on user-visible warnings generally and not on the vendored runtime. Two
questions to settle first, and neither is answered here:

- **How many crates in the corpus emit it today?** If `std/` is full of them the gate cannot start
  red; it starts as a baseline like `rust-std-survey-baseline.tsv` and ratchets. Measure before
  choosing, because "surely only a few" is exactly the assumption that gets a gate reverted.
- **Which other lints belong with it?** `unused_variables` fires on every arm binder the walker
  emits and is pure noise; `unreachable_code` may be worth the same argument. Do not sweep in a
  category — pick the lints whose meaning changes when the author is a compiler.

Until then, individual gates assert it for their own crate (`rust-type-pattern-local-val-gate.sh`
has such a row), which covers one file each and is why this entry exists rather than being closed by
that gate.

### Measured 2026-08-17 — the corpus is at ZERO, and this entry's own proposal was wrong

**How many corpus crates emit it today: none.** 55 of 55 compiling modules, 0 warnings. So no
baseline and no ratchet — a check can start green and stay there. The assumption this entry warned
against ("surely only a few") was not needed; the number is zero, and it is measured.

**But `build-rust` must NOT refuse on it, which corrects the proposal above.** Measured with a plant:
a three-arm `match` on a Boolean with a trailing `case _` is a LEGAL ScalaScript program, compiles on
every lane, and emits exactly this warning. Failing the compiler on it would break working user code
to catch a codegen defect. So the check went into `rust-std-survey-gate.sh`, over the corpus we own —
where an unreachable arm really does mean the walker lost a discriminator — and it costs NOTHING
extra, because that sweep already builds all 132 modules and the warning is already in the log it
already captures.

**THE FIRST PLANT DID NOT FIRE, AND THAT IS THE PART WORTH CARRYING.** I added a dead-armed
`def __plantDead` to `std/bench.ssc`, rebuilt, ran the survey — GREEN. The check was not blind; the
PLANT was. The walker does not emit an unreferenced def, so `__plantDead` never reached rustc
(`grep -c '__plantDead'` in that build log: 0). A plant has to land in code that is actually EMITTED.
The second — a redundant `case _` inside `isRight` in `std/either.ssc`, a def the module really
emits — produced 1 warning and turned the survey RED, naming the file and the count. Reading the
first green as "corpus clean, check works" would have shipped a check that cannot fail.

**Still not done, deliberately:** the other lints. `unused_variables` fires on every emitted arm
binder and is noise; `unreachable_code` may deserve the same argument. They need the same
measure-first treatment and are not bundled here.

**Verified:** `rust-std-survey-gate.sh` PASS on a clean tree — 77 REFUSED / 55 COMPILES, BADRUST not
grown, no unreachable arms. Plant control: exit 1 with `std/either.ssc  1 warning(s)`. Instrument
control: the same plant built standalone shows the warning the gate's grep counts, so the zero is a
measurement rather than a silence.

## json-parse-has-no-fallible-spelling — a caller cannot ask "was this text JSON at all?": the strict parse aborts and the tolerant one answers `isNull` for invalid input AND for the literal `null`

<!-- status: fixed
     lane: multi
     area: runtime
     kind: feature
     gate: tests/e2e/json-parse-either-gate.sh
     fixed-in: a291c4014
     reported-by: rozum (sergey-scherbina/rozum, agent claude-code)
     reported-at: 2026-08-16
     ssc-version: bin/ssc-tools built from 539079f43
     repro: none
     impact: workaround
     confirmed: no -->

Split out of `rust-serve-dies-permanently-after-one-handler-panic` (BUGS.md), whose second ask this
is. That entry is closed on its first ask — a handler panic no longer takes the server down — and
deliberately does not close this one.

**The report says there is no `Option`/`Either` spelling and that a server handling input it did not
write therefore cannot refuse it. Measured, the shape is slightly different and the gap is real
anyway.** `std/json.ssc` has three entry points, and one of them IS total:

```text
jsonValue(s)  tolerant  → JsonValue, never fails
jsonParse(s)  strict    → Any, aborts the thread on bad input
jsonRead(s)   strict    → JsonValue, aborts the thread on bad input
```

so the reporter's workaround was not needed for TOTALITY. It was needed for something the total
spelling cannot express. Measured on the interpreter (`bin/ssc run`), one row per input:

```text
""          → isNull
"not json"  → isNull
"null"      → isNull      ← the literal JSON null is indistinguishable from "this was not JSON"
"{\"a\":"   → isNull
"{\"a\":1}" → value
```

A caller who must tell a malformed body from a body that legitimately contains `null` has no
spelling for it on any lane. That is the ask: an `Option`/`Either`-returning parse (or an
`isInvalid` distinct from `isNull`), so refusing bad input does not mean inspecting the first
byte by hand.

**On the rust lane the total spelling is not merely awkward, it is UNREACHABLE**, which is why the
report concluded no such spelling exists — from where they stand, correct. `build-rust` refuses
`std/json.ssc`'s tolerant path outright while the panicking strict path builds and runs. That half
is a defect, not an API decision, and is filed separately as
`rust-lane-refuses-the-tolerant-json-parse-while-the-panicking-one-builds` in BUGS.md. Until it is
fixed, this feature would land on a lane that cannot call it.

**Decide the API before implementing.** The same argument `std-fs-failure-contract` (below) is still
open on applies here — total variants add surface to `std`, and the sibling entry records a reporter
arguing AGAINST making a library total by default for exactly this reason: a caller must be able to
tell "empty" from "not there". These two should be decided together rather than one at a time.

### Decided and done 2026-08-17 — `jsonParseEither`

Sergiy chose `Either[String, Any]` over an `Option` or an `isInvalid` accessor, and the reason it is
the right pick shows in the gate: `Left` carries the parser's own message, so a handler can answer
400 with something the caller can act on rather than a bare "bad request".

```text
""            Left    not JSON
"not json"    Left    not JSON
"{"a":"     Left    not JSON (truncated)
"null"        Right   VALID JSON whose value is null
"{"a":1}"   Right   valid
```

That fourth row is the entire feature. Every other row would pass on an implementation that simply
called the tolerant parser and reported `isNull` as failure — and that implementation tells a caller
their valid `null` is malformed.

**ONE DEF, NOT FIVE IMPLEMENTATIONS.** `jsonParseEither` is ordinary ScalaScript over
`__jsonParseError`, which is itself a def written on json-core. So int, `--v1`, jvm and js get it
from one body. Only the rust lane needs anything: `__jsonParseError` is replaced there by an
INTRINSIC over serde, the same arrangement `jsonParse` already relies on — which is why this feature
works on rust even though `std/json-core.ssc` cannot be lowered there at all
(`rust-lane-refuses-the-tolerant-json-parse-while-the-panicking-one-builds`, still open). The
dependency this entry was assumed to have on that one does not exist.

**THE MESSAGE TEXT DIFFERS ON RUST, and the gate does not pretend otherwise.** json-core says
`invalid JSON at 5: expected JSON value`; serde says `invalid JSON: EOF while parsing a value at line
1 column 5`. Both name where it broke. Asserting one exact string would fail on a lane doing the
right thing, so the classification rows are compared exactly and the message row asserts only that
it is present and substantial.

**Verified:** `tests/e2e/json-parse-either-gate.sh` PASS — 30 rows, six per lane, across `run`,
`--v1`, `run-jvm`, `run-js` and `build-rust`. Negative control with std/json.ssc and the rust
intrinsic reverted: every lane red, `build-rust` refusing `jsonParseEither` by name. Corpus unmoved:
`rust-std-survey-gate` 77 REFUSED / 55 COMPILES with BADRUST not grown; `v1-jit-size` PASS;
`rust-json-core-gate` still PASS.

`confirmed: no` — rozum has not checked this against their own build.

## std-fs-failure-contract — std.fs's failure behaviour is undocumented and differs per backend: specs/std-fs-os.md maps listDir to Files.list / fs.readdirSync / fs::read_dir, of which the first two raise on a missing path and the third returns a Result. Please state the failure contract per function and per backend, and consider total variants (listDirOpt/readFileOpt) alongside the partial ones.

<!-- status: open
     lane: multi
     area: runtime
     kind: feature
     gate: specs/std-fs-os.md §2.1 (the measured table; no conformance case yet)
     fixed-in: -
     reported-by: nadia (sibling repo, rozum meeting room: nadia-ucc)
     reported-at: 2026-08-04
     ssc-version: toolchain built from 4f45611c6 (bin/ssc), checkout at 143dba514+
     repro: none
     reporter-suspects: The failure semantics were never specified because each backend's primitive was mapped directly; std.json and resolveWithin already chose totality, so fs is the outlier rather than the rule.
     impact: workaround -->

Routed from `INBOX.md` on 2026-08-07. Everything below is the reporter's, in their words.

### Ask 1 is DISCHARGED; ask 2 is why this stays open

**Ask 1 — state the failure contract — done in `specs/std-fs-os.md` §2.1** (`54dd1d5db`), measured
one probe per cell across int / js / jvm / v2-native for a missing path and a wrong type. Two cells
are deliberately blank with their reason given: permission denial (needs a mode-000 fixture, and a
probe running as root measures nothing) and the whole Rust column (`run-rust` emits no binary for a
hello-world on this toolchain, and the backend rejects `try`/`catch`, so "does it raise" has no
expressible answer there — filed at the root as `rust-lane-produces-no-binary-for-hello-world`).

**The measurement contradicted the report's own premise, in the reporter's favour.** They expected
JVM and Node to raise and Rust to return a `Result`. What is actually there: `listDir` on a missing
directory answers `List()` on **jvm**, silently, exit 0, while int, js and native all raise — which
is the behaviour this very report argues against ("a `listDir` that answers `[]` for a missing
directory hides a typo"). Filed as `jvm-listdir-answers-empty-where-every-other-lane-raises`. And
`v2/native` erases the exception TYPE, so *missing* and *wrong type* are indistinguishable there
even with a `catch` — the reporter's "permission denial and missing give the same answer" is worse
than they knew.

**Ask 2 — total variants (`listDirOpt` / `readFileOpt`) — OPEN, and it is an API decision.** It adds
surface to `std`, and the reporter is explicit that they would NOT make `fs` total by default,
because a caller must be able to tell "empty" from "not there" — the exact distinction the jvm lane
destroys today. Their argument for variants is that the vocabulary already exists in the library:
`std.json` navigation is explicitly total and `resolveWithin` returns an `Option`, so `fs` is the
outlier rather than the rule. Their own 40-line implementation and its 28-case contract are named
below; a decision should read those first.

**Gated 2026-08-07** by two conformance cases, `std-fs-failure` (int/jvm/js/v2 — the rows all four
lanes agree on) and `std-fs-failure-raises` (int/js/v2 — that the read-shaped operations raise, the
fact only, never the message). The split is the finding, not a convenience: one expected output
cannot express a divergence, and weakening a single case until it passed everywhere would have
hidden what §2.1 measured.

`jvm` absent from the second `backends:` line IS the assertion, and it sharpened the finding —
with jvm added the case fails on exactly two rows, both `listDir`, and every other row passes, so
the divergence is `listDir` alone rather than the lane broadly. Adding `jvm` to that line is how
`jvm-listdir-answers-empty-where-every-other-lane-raises` gets closed.

Still ungated, and named so it is not read as finished: the native lane's erasure of the exception
TYPE (missing and wrong-type indistinguishable even inside a `catch`), permission denial, and the
Rust column.

### The reporter's note about the tooling, acted on

Their closing note — `inbox-add --body-file` accepted a body whose headings began at `##`, and
`inbox-gate` then read each as a new entry, turning one report into five malformed ones — is a real
defect and is **not** fixed by this entry. It belongs with `scripts/inbox-add`, which now has a
sibling, `scripts/inbox-route`; the demotion or refusal they suggest should live in one of the two.

### What I ran into

Building the coding agent `nadia` (a consumer of `std`, sibling repo), one call to `listDir` on a
directory that had been deleted raised and took the run down. That is my bug and I fixed it. What
I am reporting is what made it possible, because I do not think I am the last person it will get:

**`specs/std-fs-os.md` does not state what any `std.fs` function does when the path is missing.**
The table maps each name to its backend implementation —

| | JVM | Node | Rust |
|---|---|---|---|
| `listDir` | `Files.list` | `fs.readdirSync` | `fs::read_dir` |

— and those three do not agree: the first two raise, the third returns a `Result`. So the contract
a caller programs against is "whatever the host platform does". A program that behaves on one
backend can behave differently on another, and nothing in the spec says so.

### Why it lands where it does

In my repository the convention "guard with `exists`/`isDir` before every read" held at 12 of 13
call sites. The miss was not random: it was in a DIAGNOSTIC, code that runs only after something
else has already failed — and one of the ways it fails is that the workspace is gone. Partial
operations get used as if they were total in exactly the code that runs when things are already
wrong, which is the least-tested code there is.

Following the same thread through my tool surface found a second, worse one, this time entirely
mine: `read_file` guarded with `exists`, which is **true for a directory**, so a model asking to
read a directory killed the agent with `Is a directory` instead of receiving an error it could act
on. Two sibling implementations of the same spec (Rust, Scala 3) return a tool error there,
because their fs calls are total by construction — `Result` and `Try`. Only the ScalaScript one
raised. That asymmetry is downstream of this contract being unstated.

### What I am asking for — two things, the first much more important

**1. State the failure behaviour in `specs/std-fs-os.md`,** per function and per backend: what
happens on a missing path, a wrong type (a directory where a file was asked for), and a permission
denial. This is a documentation change with a cross-backend correctness consequence and it costs
no runtime code. Right now a careful reader cannot answer "does `listDir` raise?" from the spec.

**2. Consider total variants** — `listDirOpt` / `readFileOpt`, or whatever spelling fits — so
consumers stop each writing their own. The vocabulary is already in the library: `std.json`
navigation is explicitly total ("a missing key, wrong shape, or parse failure funnels to a Null
JsonValue, never a crash") and `resolveWithin` returns an `Option`. This asks only that `fs` get
the principle `json` and paths already have.

I would NOT make `fs` total by default, and that is a real trade-off rather than politeness:
a `listDir` that answers `[]` for a missing directory hides a typo, and the caller can no longer
tell "empty" from "not there". Variants let the caller choose and make the choice visible at the
call site.

### What I built meanwhile, in case it is useful as a starting point

A small module in my own repo rather than a patch here — `nadia:src/fsx.ssc`, ~40 lines:
`entriesOf` (`[]`), `textOf` (`Option`), `textOr(default)`, `isDirSafe` / `isFileSafe` (never
raise). Contract runs alongside it: `nadia:src/fsx-check.ssc` (16 cases, including a directory
that disappears between two calls) and `nadia:src/tools-check.ssc` (12 cases on the tool surface).
Design and reasoning: `nadia:docs/specs/total-fs.md`. Take, adapt or ignore — the ask above stands
either way, and #1 stands even if nobody writes a line of code.

One limitation of my module that only `std` can fix: a permission denial and a missing file give
the same answer, because there is no way to tell them apart through the current API.


*(Note for whoever triages this: `scripts/inbox-add --body-file` accepted a body whose headings
started at `##`, and `tests/e2e/inbox-gate.sh` then read each of them as a new entry — my first
attempt turned one report into five malformed ones. Rewritten at `###` here. The tool could refuse
or demote them; I have not filed that separately, since you may prefer to fix it in either place.)*
## cli-reporter-silent-on-module-blocks — the main-file half of a lane divergence, still open

<!-- status: open
     lane: multi
     area: cli
     owner: unassigned -->

`reportCodeBlockParseErrors` (`v1/tools/cli/.../RenderHelpers.scala:64`) does not report a code
block that failed to parse when the file's front matter carries `package:`. Its condition is
`cb.tree.isEmpty && cb.parseError.isDefined`, and a `package:` module re-parses each block wrapped
in `object pkg:` and CLEARS the stored error when that retry also fails (`Parser.scala:120`) — so a
failing module block has an empty tree and NO recorded position, and nothing prints.

Measured with `val broken = (((`, which no parser accepts: every path stayed silent.

**The change, which is two edits:**

    case cb: Content.CodeBlock if cb.isProgramCode && cb.tree.isEmpty =>

drop `parseError.isDefined`, add `isProgramCode`, and make the message say the position is missing
rather than inventing one. `isProgramCode` is `isParseable(lang) && !isDocOnly` and is NOT optional
here: without it the reporter starts flagging `@doc` examples, which is most of the standard
library's documentation.

**The risk is already paid down.** The same fix landed on the IMPORT path in `1c84683fa`, which is
where the lane divergence actually bit — the interpreter ran programs `v2` and the native front
reject. Turning it on found five conformance failures at once, all one cause (an import example
inside a code fence in `examples/std-ui/index.ssc`); four more of the same shape were found by
census and all are now marked `@doc`. Conformance on int, `--no-memo`: 358/358.
`tests/e2e/import-parse-error-gate.sh` covers both directions on all three lanes and would cover
this half too.

**Why it is here and not done.** `RenderHelpers.scala` is held by `release-graalvm-pin`, which is
live by commit activity and visibly working on CLI diagnostics. The overlap guard refused the
widening and that refusal was correct. Offered to that claim in the room on 2026-08-06; this entry
is so the work survives whether or not they take it.

**Why the root board.** `v1/tools/cli` has no `BACKLOG.md`; creating one for a single entry is
heavier than the entry.


## smoke-budget-has-no-owner — the per-push cap is a shared resource nobody is responsible for
<!-- status: open
     lane: multi
     area: build
     kind: apparatus
     gate: scripts/smoke-ci -->

**DEFERRED DELIBERATELY, 2026-08-04, by agreement — recorded so the decision is visible rather than
forgotten.** The acute breach is gone (`tests/BUGS.md smoke-suite-over-its-own-budget`, four runs
since at 278–346 s). What is left is the structural half, and it is a DECISION, not a defect: it
cannot be taken by whoever happens to notice.

**The measurement that makes it a decision.** Two consecutive CI runs of near-identical content:

| run | total | what changed since the previous |
|---|---:|---|
| 30839675049 | 418.6 s | — |
| 30840744973 | **425.6 s** | one gate trimmed 17.7 s → 10.9 s, i.e. work was REMOVED |

The suite got 7 s slower across a change that only took work out. **Runner variance is ±14 s**, so a
cap with less than ~20 s of headroom flaps regardless of what is in the suite — every "fix" that
shaves ten seconds off the newest gate is inside the noise.

**Two ways out, and picking between them is the point:**

1. **Several checks move to tier 2 of `ci.yml`** (which is also per-push, so no per-commit coverage
   is lost). The five most expensive were `route-handler-shapes` 45.9 s, `render-lane-builtins`
   42.0 s, `corpus-lane-breadth` 34.1 s, `launchers-not-dead` 30.9 s,
   `no-test-reaches-an-exiting-cli` 27.9 s — 180.8 s of a 425.6 s run between them. **Each has an
   owner**, and that is exactly why this sat: an author may move their own gate without a
   negotiation, and may not move anyone else's.
2. **The design point is restated.** `smoke.yml`'s own comment says the suite is "27 checks, ~157 s".
   It has 58. Either that sentence is now wrong and should be rewritten with a defended number, or
   the suite really should be 27 checks and 31 of them belong elsewhere.

**Not option 3.** Raising `SSC_SMOKE_BUDGET` stays refused, for the reason `scripts/smoke-ci.ssc`
has always given: that is how the old 13.4-minute push path happened.

**The rule that has been applied so far, and its limit:** *when a shared budget is exhausted, the
additions that arrived last leave first.* It is the only rule an author can apply to their own work
unilaterally, which is what made it possible to unblock main twice in one day — and it buys ~15 s
at a time. It does not scale to 180 s.

## std-os-does-not-resolve-on-js-or-jvm — two lanes have no environment surface at all
<!-- status: open
     lane: multi
     area: runtime
     kind: feature
     gate: none -->

**Measured 2026-07-31 while fixing `std-has-no-stdin-primitive`.** It is not `readLine` that is
missing on those lanes — it is the whole module. `envOrElse` from `std/os.ssc`:

```
int   -> present          jvm -> value envOrElse is not a member of object std.os
v2    -> present          js  -> not callable: ()
native-> present
```

So `env` · `args` · `cwd` · `pathJoin` · `platform` · `exit` — the entire environment surface — are
unavailable to a program on `js` and `jvm`, while `os.ssc`'s own doc block promises "JVM: `System`…;
Node: `process`, `node:path`, `node:os`". This is the next thing the reporter of
[#76](https://github.com/sergey-scherbina/scalascript/issues/76) walks into, and the reason
`std-os-readline` gates only `[int, v2]`.

**ANSWERED for js, 2026-07-31, and the framing above was wrong: nothing is missing.** All eighteen
functions ARE implemented in the JS runtime and emitted into the bundle. The binding built for the
`package:`-module object probes only `_ssc_ui_<name>` and `globalThis.<name>`, and a runtime function
that keeps its OWN name satisfies neither — so every one of them binds to `undefined`. Mechanism,
the TDZ constraint that causes it, and the fix direction are in
`v1/runtime/backend/js/BUGS.md` `js-identity-named-runtime-fn-unreachable-from-a-package-module`.

The jvm half is still unmeasured; its error ("not a member of object std.os") is a different shape
and should not be assumed to share this cause.

## bugs-index-fixed-in-and-the-shallow-clone — decide what `fixed-in` must prove
<!-- status: open
     lane: apparatus
     area: build
     kind: apparatus
     gate: none -->

`tests/e2e/bugs-index-gate.sh:99` asks `git cat-file -e <sha>^{commit}` — resolvability, which is
true for any object in the LOCAL store including one a rebase orphaned. Reachability from
`origin/main` is the property that matters, and it is NOT a one-line switch: CI clones at
`fetch-depth: 1`, where almost nothing is reachable and the check would fail on every honest entry.

The decision, not the code, is the work: fetch enough history to answer, or answer a weaker question
deliberately and say so in the gate's header. Detail and the incident that found it are in `BUGS.md`
`bugs-index-fixed-in-checks-resolvable-not-reachable`.

## native-image-classpath-is-the-staging-classpath — the binary compiles in what it should load

<!-- status: open
     lane: apparatus
     area: build
     kind: task
     gate: .github/workflows/native-release.yml -->

**Decided by Sergiy 2026-08-05:** shrink the image rather than buy a bigger runner or ship without
arm64. The release waits for this.

**Why the image is 200 MB.** `cli` depends on every std plugin *directly*, and build.sbt says why:
`installBin` stages `bin/lib/jars` from cli's `fullClasspath`. That is a staging list, and the
native image is built from the same classpath — so everything meant to be *loaded at runtime from
`bin/lib/`* is instead *compiled into the binary*.

Measured from the build's own breakdown (`-H:+BuildOutputBreakdowns`, on by default):

| origin | code |
| --- | --- |
| java.base | 15.68 MB |
| **jdk.compiler** | 7.35 MB |
| **h2-2.2.224.jar** | 7.03 MB |
| **trees2 (scalameta)** | 6.76 MB |
| svm.jar | 5.39 MB |
| backend-interpreter | 5.23 MB |
| java.xml | 4.63 MB |
| core | 4.29 MB |
| scala-library | 4.27 MB |
| cli | 3.95 MB |
| 174 more packages | 40.02 MB |

A Java compiler, an embedded SQL database and a Scala parser the self-hosted native front does not
use. h2 also brings postgresql and sqlite drivers.

**Why it matters beyond size.** The 7 GB arm64 runner cannot build this image at any heap value —
that is measured and closed (`tests/BUGS.md` `native-release-native-image-three-defects`, defects
8–9). Less to compile is the only remaining lever that helps *every* platform rather than paying for
one.

### Slices

- [x] **S1 — measured, and the first cut was wrong.** Dropping every jar the plugins pull (17 jars,
      60.6 MB) gave a 85 MB binary that built in 1m18s and passed **all four checks the release
      qualifier makes** — and was broken: `run --v1` and `compile-jvm` both died with
      `NoClassDefFoundError: Could not initialize class scalascript.parser.Parser`. scalameta and
      scala3-compiler are load-bearing for the v1 front and the JVM backend.

      Controlled comparison, same probes, same machine:

      | binary | size | build | `run --v2` | `run --v1` | `compile-jvm` | `--bytecode` |
      | --- | --- | --- | --- | --- | --- | --- |
      | full | 192 MB | 4m36s | 84 | 84 | env error | refuses |
      | **refined** | **145 MB** | 1m55s | 84 | 84 | *same* env error | refuses |
      | over-cut | 85 MB | 1m18s | 84 | **NoClassDefFound** | **NoClassDefFound** | refuses |

      `compile-jvm` fails identically on full and refined, so that is the probe environment, not a
      regression — which is the whole reason the control was run.

      **Refined = drop only plugin RUNTIME deps** (sqlite-jdbc, h2, postgresql, HikariCP, pdfbox,
      fontbox, openhtmltopdf, graphics2d, jsoup, xmpbox — 11 jars, 22.5 MB): behaviourally
      indistinguishable from full, 47 MB smaller.

- [ ] **S1b — the qualifier does not test `--v1` or `compile-jvm`.** The over-cut binary passed
      every check it makes. That gap is worth closing on its own merits, independent of image size:
      it is what would have let a broken binary ship.
- [ ] **S1c — was measure what the binary actually needs.** The plugins are loaded at run time by the
      plugin host from `bin/lib/`; the question is which classes the *binary* touches before that.
      Do not guess from names: `--emit build-report` / `-H:+BuildReport` gives a per-package
      breakdown, and the qualifier already exercises the real entry points.
- [ ] **S2 — separate the two classpaths.** `installBin` keeps cli's full classpath (that is its
      job); the image gets a narrower one. The risk to check, not assume: anything the binary
      resolves eagerly rather than through the plugin host.
- [ ] **S3 — re-qualify all three targets.** Success is not "smaller": it is the qualifier green,
      including the refusal contract and the staged-front checks. A binary that shrank by dropping
      something it needed fails at a user's first command, not at build time.

## ui-fetch-credentials — a token that cannot be baked into a generated binary

<!-- status: open
     lane: multi
     area: runtime
     kind: feature
     reported-by: rozum (github.com/sergey-scherbina/rozum, docs/specs/ucc-meetings-in-tk.md)
     reported-at: 2026-08-04
     ssc-version: ec70eb062 (staged bin/, dev build)
     confirmed: no
     gate: tests/conformance/credential-vocabulary.ssc -->

Routed here from `INBOX.md`, which carried this as two entries — `ui-fetch-credentials` (the design
proposal) and `std-auth-client-half` (the missing concept). The reporter said plainly that if the
second is taken the first folds inside it, so they are specced and planned as one and both were
deleted from the queue. Spec and the four decisions with their rejected alternatives:
`specs/ui-fetch-credentials.md`.

**The problem.** On the terminal target the view runs in the *emitting* process, so `env("TOKEN")`
is read at build time and lands in the generated Rust as a literal — the secret ends up inside the
binary. The web bundle has the same shape. `std.auth` is a complete vocabulary for *being* an
authorization server and has no word for *presenting* a credential on an outbound call, so every
caller hand-rolls one; `std/agent.ssc:443` and `:452` both spell `"Bearer " + endpoint.authToken`
six lines apart.

**The approach.** A `Credential` names *where the secret comes from* and carries no secret, so an
emitter has nothing to bake and the target resolves it at call time. That makes the wrong thing
impossible rather than discouraged.

### Slices

- [x] **S1 — the vocabulary.** DONE. `v1/runtime/std/credential.ssc`:
      `Credential(kind, source, scheme)` with `credentialEnv` / `credentialFile` /
      `credentialLiteral` / `credentialNone`, plus `withScheme` and `credentialBasic` so the scheme
      is applied by the consumer instead of by callers concatenating strings. Gate
      `tests/conformance/credential-vocabulary.ssc` passes INT + JS; its load-bearing line
      constructs `credentialEnv("HOME")` where `HOME` is genuinely set and asserts `source` is still
      the NAME, so making resolution eager turns it red with a machine-dependent path — verified by
      mutation through the runner, not through `bin/ssc`.

- [ ] **S2 — resolution in the TUI emitter.** BLOCKED on sibling claim `tui-table-selection`, which
      holds `TuiEmitter.scala`. Emit a resolver per `kind` into the generated crate; a kind the
      target cannot resolve is a compile error, never an empty header. Gate: the generated crate
      contains no secret, and the 401/200 cargo fixture still passes — i.e. it authenticates
      *without* the token appearing in the source.

- [ ] **S3 — collapse the hand-rolled sites.** `AgentEndpoint` gains a `credential` field beside
      `authToken`; the two `"Bearer " + …` sites become one resolution call. Additive by decision 2
      — `authToken` keeps working, because rozum and busi are mid-flight against it.

- [ ] **S4 — the web target.** Same resolver contract for `emit-spa`, where `credentialEnv` must be
      refused at emit time rather than inlined into the bundle.

## ssc-tools-stdin-belongs-to-the-program — give sops its own channel, stop taking stdin
<!-- status: open
     lane: apparatus
     area: cli
     kind: feature
     gate: none -->

**DECIDED 2026-07-31 by Sergiy** — implement the proposal below. The defect and the full option
table are `BUGS.md` `ssc-tools-swallows-piped-stdin`; this is the queued work, not a second copy of
the analysis.

**The conflict.** `Main.scala:72` reads stdin to EOF as a YAML secrets document for every
`ssc-tools` command except `lsp` and `repl` (`loadSopsSecrets` → `Source.stdin.mkString`). Two
features now want that one stream and only one can have it: `sops -d secrets.enc.yaml | ssc app.ssc`,
which is the shipped and intended invocation, and `std.os.readLine`, which since `862a19adb` lets a
program read what the user typed or piped. Today the CLI always wins and the program sees EOF.

**The decision: stdin belongs to the program; secrets get an explicit channel.** `--secrets-file
<path>`, which composes with `<(sops -d secrets.enc.yaml)` or an explicit `/dev/stdin` for anyone who
wants the old shape. The argument is not preference: stdin belonging to the program is the universal
convention, the DEFAULT lane (`bin/ssc`) already never slurps, and it is therefore the tools route
that is the anomaly. A runtime that silently consumes stdin starves every program that reads input,
and that failure is only ever discovered as somebody else's bug — it took a new primitive and a
user report to surface this one.

### Slices

- [x] **S1 — `--secrets-file <path>`.** DONE. Reads and flattens exactly as `loadSopsSecrets` does,
      same `SopsSecrets.load`; an explicit file SUPPRESSES the implicit stdin slurp, because "where
      did this secret come from" must not have two answers. Additive: with no flag, today's
      behaviour is byte-identical. Unlike the implicit path it is LOUD on a bad path, an empty file
      or a non-YAML document (exit 2) — the implicit one has to stay quiet because it fires on any
      pipe, but a caller who wrote `--secrets-file` stated an intent, and silently ignoring a typo
      would hand them a program running without the secrets it asked for.
      Measured: `--secrets-file f | run` → the program receives the pipe; no flag → the CLI still
      eats it (unchanged); `<(sops -d …)` works; missing path → exit 2.
- [x] **S2 — deprecation notice on the implicit slurp.** DONE. Fires on the CONSUMPTION, not on the
      parse: whether the bytes turned out to be YAML is irrelevant to the program that lost them.
      Silent when nothing was taken (empty stdin), when `--secrets-file` was used, and under
      `SSC_SOPS_STDIN=1` — the same variable that becomes the opt-in switch in S3, so migrating away
      from the warning and migrating to the new default are one action rather than two.
      Names the tracking item, NOT a release: the build is `0.1.0-SNAPSHOT` and the changelog is
      dated, so a version number here would have been invented. Behaviour unchanged; the four
      assertions in `tests/e2e/stdin-belongs-to-the-program.sh` pin it, including that it stays on
      stderr — a notice on stdout would corrupt the output of exactly the piped programs it warns.
- [x] **S3 — the implicit slurp becomes opt-in** DONE 2026-08-05. (`SSC_SOPS_STDIN=1`), then the escape is deleted a
      release later. CHANGELOG entry is part of this slice, not a follow-up.
      (Corrected 2026-07-31: the original S2/S3 split said "becomes opt-in" and then "flip the
      default", which is the same flip written twice. The missing step was the WARNING — nobody
      should discover a default change by their pipeline going quiet.)
- [x] **S4 — the gate.** DONE: `tests/e2e/stdin-belongs-to-the-program.sh`, wired into the push path.
      Four assertions, and the second is deliberately about TODAY's wrong behaviour — the tools route
      without `--secrets-file` still swallows stdin — so that flipping it in S3 turns this red and has
      to be acknowledged in the same commit instead of slipping through as a silent default change.

**Do not start at S2.** S1 first means the replacement exists before the old path is discouraged;
reversing that order leaves a window where the documented way to pass secrets is the one being
deprecated and its successor is not written yet.

## agents-md-paraphrases-policy — four MANDATORY sections restate rules that now live in POLICY.md
<!-- status: fixed
     lane: n/a
     area: docs
     kind: apparatus
     fixed-in: unrecorded
     gate: tests/e2e/policy-single-source.sh -->

**Status:** FIXED 2026-07-30, same day it was filed. Six sections separated line by line; nothing
lost, verified probe by probe. `fixed-in: unrecorded` because the commit is this one.

**How the separation was decided, since "rule vs detail" is not self-evident:**

- A **rule** says what you must do. It moved to `POLICY.md`, or was already there.
- **Detail** is the command, the flag, the failure mode, the measurement. It stayed.
- A **citation names the SUBJECT, not the content.** "When a doc update is required is P-1.4" is a
  signpost; "a feature with no doc update is incomplete — see P-1.4" is a second copy wearing a
  link. The first draft of three citations did the latter and was rewritten.

**Six rules existed ONLY in `AGENTS.md` and were moved INTO `POLICY.md` rather than dropped** —
this was the risk in the task and the reason it was done as its own change: the shared checkout's
three permitted uses (P-1.3), a claim counts when it is on `origin/main` (P-2.4b), `verify-<slug>`
is legitimate (P-2.4c), `cancelled` is RED and a release names its evidence level (P-6.7), and docs
ship with the feature (P-1.4).

`AGENTS.md` 1333 → 1215 lines. The gate cannot prove a paraphrase is gone, so the check was manual
and by probe: every distinctive phrase from the old sections still resolves in one file or the
other.

`POLICY.md` is the single source for the process rules and the five entry points now link to it.
But four `## MANDATORY` sections in `AGENTS.md` — THE WORKFLOW, "decide it yourself", the room, and
narrowest-scope — still **paraphrase** P-1, P-4, P-5 and P-2.

**The gate cannot see this, by construction.** `policy-single-source.sh` pins verbatim phrases;
deciding whether two paragraphs state the same rule is not mechanical, and a keyword heuristic
there would be the same mistake that made `area:` read `front` for 256 of 621 entries. So the gate
is green and the duplication is real — exactly the shape this repository keeps paying for, which is
why it is written down instead of trusted to memory.

**Why it was not just done.** `AGENTS.md` carries detail that `POLICY.md` deliberately does not:
the three-level CI evidence ladder, the conformance-before-push commands, the sbt and worktree
mechanics. Cutting the sections wholesale would lose it. The work is to separate rule from detail
line by line, leave the detail, and replace each rule statement with a pointer — a surgical edit of
a file every agent reads, and worth its own task and its own review.

**Done means:** each of the four sections states no rule of its own, only mechanics plus a `P-n`
citation; and a reader who follows only `AGENTS.md` still ends up able to do the work.

## 2026-07-27 — Sergiy's decisions, and the two arcs they unblock

Answered this session: **portable-capsule-integrity → (c)**, **vector 26 D1 → a distinct
`TooLateToCancel`**, **D4 → cancellation checked first**, **AGENTS.md §4c → relaxed**. D2/D3/D5 were
not named; this agent's recommendations were taken and are marked ASSUMED in
`specs/durable-cancellation-open-decisions.md` — cheap to revisit before the oracle text freezes,
not after. §4c is already relaxed (three-level evidence ladder; `cancelled` stays RED and the claim
must say WHICH level it has). Remaining work, in the order the decisions imply:

- [x] **F1 — DONE** (`f19499d65..173cba71e`). Envelope v1→v2 with the host lane's HMAC seal +
      audience/tenant/budget; gate `portable-capsule.sh` 36/36 including the pair that makes it
      mean something — the same frame edit REJECTED under a key and still RUNNING unkeyed.
      Original scope: Give the VM Portable capsule the host lane's
      seal: HMAC signature + audience / tenant / quota, envelope v1 → v2. Host side is
      `v2/host/scala/control/.../DurableCapsule.scala`; VM side `v2/src/Capsule.scala` (today: v1,
      code-only `resume-digest`). **Keep both existing guards** — E2's `validateFrame` and E3's
      Fx-closed run check are orthogonal and the seal replaces neither. The committed fixture
      `v2/conformance/fixtures/fx-open.portable` is pinned to the current envelope, so re-freeze or
      version-pin it in the same commit. Gate: `v2/conformance/portable-capsule.sh` (25 lines today)
      plus a new tamper case — a capsule whose FRAME was edited must now be REJECTED, which is the
      thing (c) buys and the current 111-vs-17 line documents as accepted.
- [x] **F2 — DONE** (`4a1ae5ca6..33bd98b29`), durable 24/26 → 25/26. Four slices; Scala 165/165
      (incl. 200 rounds of two-thread contention where the loser must NAME the winner), JS 71/71,
      vectors 29/29. Flip on `scala-explicit` only — the JS lane is not a qualified conformance
      lane, and claiming it would have been bending. Original scope: The transition table is decided (see the spec's
      DECIDED section). Host-only (`structured`) on both lanes, oracle demonstrating
      cancel-then-resume → `Cancelled`, resume-then-cancel → **`TooLateToCancel`**,
      reusable-cancel-blocks-run → `Cancelled`, idempotent-cancel → `Cancelled`. Two new §13 rows
      (`Cancelled`, `TooLateToCancel`) + their boundary projections; cancellation checked FIRST in
      the §11.1 admission order. Takes durable to **25/26**. ⚠️ Do not flip by bending the
      realization to the pending's wording — that is the failure mode this arc was warned about.
- [x] **F3 — DONE** (`d0e5a6bf6..3814f0b7c`), durable **26/26, pending directory EMPTY**. A
      non-JVM admitter (`v2/host/js/portable-admitter`) admits AND runs a JVM-frozen capsule; the
      gate freezes FRESH bytes each run rather than trusting a committed fixture, and the lane
      SKIPS loudly when it cannot. int64 parity including two's-complement wrap. Original scope: Second admitting backend, built against the SEALED format.
      Scouting notes are in Batch E's E4 item (the JS control host is the ExactArtifact lane, NOT a
      candidate; `Int` is 64-bit so a JS admitter needs BigInt). Building it before F1 means writing
      it twice.

**Batch E — vector 15 (Portable CodeMode) FULL ARC — Sergiy chose "всё, включая второй бэкенд"
(2026-07-27).** This is the only path that actually flips vector 15 and takes durable to 25/26;
it is a multi-session arc, not a slice. Staging is already fixed by `specs/portable-save-region.md`
§6 — follow it, do not re-plan it. Slices 1 + 1a are LANDED (`SaveRegion.reify` explicit slots;
`reifyAuto` free-outer-variable liveness + depth-aware de-Bruijn rewrite). Remaining, in order —
each is independently landable and each must keep `v2/conformance/portable-capsule.sh` PASS:

- [x] **E1 — DONE (slice 2, global closure).** `SaveRegion.globalsOf` + `closeGlobals` compute the
      transitive closure over the source `defs`, returned in the **source program's own relative
      order** (that order is what made the program valid; re-sorting could break a mutual
      reference), with a name marked reached BEFORE its body is scanned so recursion terminates.
      A root naming no def is a LOUD error. New `reifyAuto(region, defs)` / `reify(slots, lam, defs)`
      overloads (the 1-arg forms keep slice-1 behaviour) + `ssc freeze-region-global`.
      **Gate `v2/conformance/portable-capsule.sh` PASS, 14/14** — including the three new lines that
      make it mean something: `quad`+`dbl` travel transitively, `unused` does NOT (selects, not
      dumps), fresh-process runs give 17/9, and deleting a carried def from the bytes is **rejected
      at admission** rather than run with a missing global. Original scope: Today `reifyAuto` returns `Program(Nil, entry)`, so a region
      body that calls a user `def` produces an unbound `Global` and `Reader.validate` fail-closes.
      Collect the `Term.Global` names reachable from the rewritten body, take their **transitive**
      closure over the source program's `defs`, and emit them as `resume.defs` in dependency order.
      Watch: a global whose body is itself a `Lam` capturing nothing is fine (defs are closed by
      construction); a *missing* name must stay a loud error, never a silent drop — that is the
      fail-closed property `Reader.validate` exists to give. Verify: extend
      `v2/conformance/portable-capsule.sh` with a region calling a def that calls another def,
      frozen in one JVM and run in a second (the existing 42/45 + tamper-rejected cases stay green).
- [x] **E2 — DONE for the nominal half; the byte-format alignment is split out (see below).**
      Slice 3 has to answer "what may a frame contain" before it can allow non-scalar slots — and
      answering it uncovered a fail-open: `Capsule.decode` validated the resume program and its
      digest but took the **frame** as an arbitrary `Term` and spliced it into the driver.
      **Measured (BUGS `portable-capsule-frame-unvalidated`):** a frame carrying `(global dbl)` was
      admitted and injected a real closure into the resume (E1 now carries the reached defs, so the
      target exists); `(local 0)` reached `Compiler.compile` and died with
      `ArrayIndexOutOfBoundsException` instead of a diagnostic. Fixed with `Capsule.validateFrame`
      — a frame is DATA: literals and constructors recursively, everything else rejected naming the
      node (`Lam` too: a lambda is a value at runtime but *code* in the bytes). That definition is
      exactly what admits nominal slots, so the guard and the feature are the same change:
      `frameOfTerms` + `ssc freeze-region-nominal` carry `Pair(3,4)` as a slot and the resume
      destructures it with an ordinary `Match`.
      **Gates:** `portable-capsule.sh` **21/21 PASS** — including `data-only frame edit still runs`
      (111), which is what keeps the three rejection lines honest: without it a blanket "any frame
      edit fails" would pass them for the wrong reason. Semantic **248/248 GREEN, MISMATCH 0**.
      **NOT done, split out on purpose:** the §9.1/§9.3 **byte-format alignment** with the host
      `DurableCodec` (cross-lane frame identity, not just cross-process). It ran into a
      format-level question that is the owner's, not a slice's — the VM capsule has no integrity
      seal over its data half while the host lane has HMAC format v3 — now queued as
      `BACKLOG.md portable-capsule-integrity` with three options and a recommendation. **E4's N→M
      matrix is what makes that difference observable, so decide it before E4.** Original scope: Non-scalar frame slots through the §9.1/§9.3 codecs;
      align the VM frame with the host `DurableCodec` byte format so the frame is cross-lane
      identical, not merely cross-process. This is what makes E4's N→M meaningful.
- [x] **E3 — DONE, and the queued premise was wrong.** This slice was queued as "needs a local CPS
      of the region"; **measured, it needed none.** An Fx-CLOSED region (perform AND handler inside)
      already reified and ran machine-less — `50` in a fresh process, with the frame slot read from
      *inside* the handler lambda. The effect prims are ordinary `Prim`s (`Runtime.scala:1231` →
      `PortableEffects.eval`) and a plain `Lam(1,…)` handler is always `Matched`, so the existing
      pass covers it. **Writing the CPS pass first would have been weeks of work for a problem that
      did not exist** — the measurement cost ten minutes.
      What the measurement DID find is the opposite defect: the **Fx-OPEN** case was fail-open — a
      region performing with no handler froze happily and the resume returned
      `Op("E.get", 8, <closure>)`, i.e. a LIVE continuation handed to a runner with no machine and
      no handlers. Refused now in two places: `assertFxClosed` at reify time (a `handle` protects
      its computation only, not its own handler body; a call to a performing def counts at the call
      site) and `Capsule.run` at run time for foreign capsules — the latter pinned by a committed
      fixture `v2/conformance/fixtures/fx-open.portable` frozen by the pre-guard build, because the
      current tool can no longer produce one. Gate `portable-capsule.sh` **25/25 PASS**.
      Out of scope (unchanged): an effect whose handler is OUTSIDE the region = whole-program CPS.
      Original scope: A region whose body performs effects handled INSIDE the
      region (`Fx` closed, §11.3). Needs a local CPS of the region only — NOT whole-program CPS
      (explicit non-goal in the spec §7). The VM's runtime continuation is a live `ClosV`, which
      §10.2 forbids serializing; that is why the pass is syntactic.
- [ ] **E4 — slice 5, second admitting backend + the flip.** ⚠️ **Scouted 2026-07-27 — read this
      before picking a target.** The JS control host (`v2/host/js/control`) is **not** a candidate:
      its README states it "does not depend on CoreIR, a compiler, a backend, or a runtime" — it is
      the **ExactArtifact** lane (the machine stays per-host; only frame/id/ABI travel). Portable is
      the opposite: the resume PROGRAM travels, so a second *admitting* backend must actually be a
      small CoreIR admitter. Concretely it needs, all matching the JVM one byte-for-byte:
      (a) a reader for the canonical S-expr envelope + program; (b) `validate`'s scope rules
      (locals in range; a global is a def of the same program or an `@`-cell — E1 relies on this);
      (c) the SHA-256 resume digest with the SAME domain separator `ssc-portable-capsule-v1\0`;
      (d) `validateFrame` (E2) and the Fx-closed run guard (E3) — a second backend that omits these
      re-opens the holes on its own lane; (e) an evaluator for the node set a resume actually uses
      (`Lit/Local/Global/Lam/App/Prim/Let/LetRec/If/Ctor/Match/Seq`) plus the effect prims.
      **The trap that already cost this project a revert: `Int` is 64-bit.** A JS admitter must use
      `BigInt`, not `number`, or `2^31`/`max64`/`2^53+1` diverge — exactly the int64 class that
      reverted the bytecode-default switch once (`specs/v2-f5c-typed-bytecode.md` §8).
      **Settle `BACKLOG.md portable-capsule-integrity` FIRST** — the N→M matrix freezes goldens on
      whatever a capsule is promised to guarantee, and the two lanes currently disagree.
      Original scope: A non-JVM runtime that admits and runs
      the Portable capsule, giving §14.4 its N→M cross product. **Only after this does vector 15
      flip** — and only if the realization matches the pending's stated mechanism. ⚠️ Do NOT flip by
      bending the realization to the pending text; that is the BENDING failure mode the durable arc
      has already been warned about once.

**Batch D — DONE as a decision, 2026-07-27.** Sergiy answered both:
- **vec 26** — did NOT ratify the proposal wholesale; asked to see the disputed points first.
  Delivered `specs/durable-cancellation-open-decisions.md` (`6f3015d50`): five forks with their
  downstream cost and a recommendation each — D1 resume-then-cancel reported as `AlreadyResumed`
  (collapses two facts, in tension with §13 non-collapsibility), D2 in-flight runs not stopped,
  D3 `cancellationStatus` not in the capsule, D4 the unspecified expired-AND-cancelled tie,
  D5 naming/ABI. **Vector 26 stays `pending-spec` until he answers those five.** Do not implement.
- **vec 15** — chose the FULL arc including the second backend → queued as Batch E above.

**Batch E — `corpus-contract-shard-fix` (the broken measuring apparatus; claimed 2026-07-27).**
Not in Batches A-D and not on anybody's board: **the `Corpus Contract` nightly has NEVER produced a
green verdict.** Added `48110001c` (2026-07-14) as *"the always-on differential gate for the v2
migration"* / *"strangler-fig safety net"*; since then **13 runs = 3 `failure` (07-15..07-17) + 10
`cancelled` (07-18..07-27), 0 `success`.** The `cancelled` ones are **not** hangs — they are the
`timeout-minutes: 60` wall: run `30244286812` (today, `56d7d705f`) logged `… 350/485` at
`07:53:40` and was killed at `07:55:17`. Measured rate from that log: 25 cases per ~2.5 min early,
per ~7.2 min late (≈9.6 s/case mean, ≈17 s/case in the tail) ⇒ the full 485 needs **~95-100 min**,
not the *"~415 cases … ~25 min"* the workflow header still claims. Two compounding causes: the
corpus grew (383 → 485 cases) and the per-case cost grew (F-default is 2-4× slower per Batch B).

Why it went unnoticed for 12 days is the point: **GitHub reports a job timeout as `cancelled`, not
`failure`** — so `gh run list` reads as "someone cancelled it", the repo's own red-CI radar ignores
it, and the whole F4 front-flip therefore landed *without* its main differential net. We caught
those regressions by hand instead (`case-object`, multi-file order, md-interpolator) — exactly the
work this gate exists to do. This is the recorded `measurement-must-compare-not-prejudge` failure
mode in its purest form: the apparatus that establishes trust was itself untested and looked benign.

- [x] **E1 — DONE: `--shard i/N` in `tests/conformance/contract.sc`.** Round-robin (`idx % N == i`) over
      the already-sorted/deduped `cases` list, NOT contiguous blocks: the corpus is name-sorted and
      the slow cases cluster, so blocks would give wildly uneven shards. The baseline compare is
      **already subset-safe** (`inScope` filters the baseline down to `ranNames`), so a shard gates
      honestly against its own slice with no other change. **Guard: `--update-baseline` must REFUSE
      to run under `--shard`** — it rewrites the whole file from `current`, so a sharded update would
      silently truncate the baseline to 1/N of it. Exit 2 with a message.
- [x] **E2 — DONE: `corpus-contract.yml` → 4-way matrix.** `strategy: matrix: shard: [0,1,2,3]` +
      `fail-fast: false`, each job `scala-cli tests/conformance/contract.sc --shard ${{ }}/4`.
      Budget per shard ≈ 6 min setup + ~24 min cases ≈ 30 min, inside the (kept) 60-min guard.
      Update the stale header comment with the measured numbers instead of the 2026-07-14 estimate.
- [x] **E3 — DONE: prove the partition locally before pushing.** Assert the N shards are disjoint and
      their union is exactly the unsharded case list (a shard bug silently shrinks coverage while
      every shard reports GREEN — the same class of lie as the timeout). Cheap check: run each shard
      with `--lanes int --only '<small glob>'`-scale sampling and compare name sets.
- [x] **E4 (original plan — outcome recorded in the E4 entry immediately below).** Prediction held:
      the gate came back RED, and both cases named here showed up in it. Original text:
      `workflow_dispatch` on the landed SHA.
      The gate will almost certainly come back RED — the last run that *finished* (07-17,
      `29559215512`) reported 2 regressions + 2 improvements, all baseline drift on
      `rozum-agent-schema-derived` (SKIP → runnable, then FAIL on js+v2) and `dataset-from-generator`
      (js now PASS), and 10 more days of corpus churn have landed since. Triage each entry as
      REGRESSION (fix or file in `BUGS.md`) vs closed-gap (record via `--update-baseline`, unsharded).
      **Do not `--update-baseline` a regression away.**
- [x] **E4 — DONE: first verdict obtained and triaged.** Run `30281019432` (on `e8214a277`) is the
      first time this gate ever finished: 4 shards, **20-23 min each** against the 60-min guard, all
      `failure` — i.e. an actual verdict instead of a `cancelled`. Totals: **PASS 879/968 cells,
      78 SKIP cases, 14 non-PASS-not-in-baseline, 2 improvements.** Every one of the 14 was triaged
      by hand in the real harness (never from the gate's own label):

      | entry | verdict |
      |---|---|
      | `head-field-effect-shadow v2 FAIL` | **REAL correctness regression** → fixed `c3841d01e` (see BUGS `bytecode-opanf-purity-registry-marks-every-def-pure`) |
      | 7× `scljet-* v2 TIMEOUT` | **NOT correctness** — F compile cost: `scljet-crud` 28.2 s under F vs 4.16 s legacy, identical output, against a 30 s budget → BUGS `f-front-compile-cost-7x-on-scljet` + lane budget split `afa5981a2` |
      | `scljet-write-deep-btree js`, `scljet-balance-delete-merge int` TIMEOUT | CI contention (4.7 s / 13.0 s locally, rc 0) → same budget split |
      | `int-width js DIVERGE` | **NOT a regression** — a DECLARED `known-red:` the contract could not see → `afa5981a2` teaches it the front-matter `run.sc` already honoured |
      | `coroutine-demo * SKIP` | **REAL bug** (`Import cycle detected: coroutine.ssc → coroutine.ssc`) AND a gate artifact: the case was added 07-21, after the 07-17 freeze, so "was PASS in the baseline" was false → BUGS `coroutine-demo-import-cycle-on-interpreter` |
      | `rozum-agent-schema-derived js+v2 FAIL` | two real lane gaps on a newly-runnable case → BUGS `rozum-agent-schema-derived-js-and-v2-gaps` |
      | `dataset-from-generator js` (improvement) | genuine closed gap → belongs in the baseline |
      | `rozum-agent-schema-derived *` (improvement) | case became runnable on int |

      **Baseline deliberately NOT updated yet.** Three of these are real bugs; recording them would
      be exactly the "update-baseline a regression away" this task exists to prevent. The baseline
      update comes after the next run, and only for the entries proven to be documented state
      (`int-width js KNOWN-RED`, `dataset-from-generator js`).

- [x] **E6 — DONE, and the decision is NOT to update the baseline.** Final run `30285845478` (on
      `30f9a2f03`, all fixes live, corpus now 487): **shard 2/4 reports `✓ contract GREEN`** — the
      first green shard this gate has ever produced — and the whole matrix is down from **14
      non-PASS to 5**, every one accounted for:

      | remaining entry | what it is |
      |---|---|
      | `coroutine-demo * SKIP` | **open bug** `coroutine-demo-import-cycle-on-interpreter` |
      | `rozum-agent-schema-derived js FAIL` + `v2 FAIL` | **open bug** `rozum-agent-schema-derived-js-and-v2-gaps` |
      | `int-width js KNOWN-RED` | declared red, now correctly bucketed (was reported as a regression before `afa5981a2`) |
      | `scljet-jdbc v2 TIMEOUT` | the one survivor of the 9 timeouts — the largest scljet case still exceeds even the 90 s lane budget under F (see `f-front-compile-cost-7x-on-scljet`, and it also carries `scljet-jdbc-facade-bytecode-class-too-large`) |
      | *(improvements)* `dataset-from-generator js`, `rozum-agent-schema-derived *` | genuine closed gap / case became runnable |

      **Why no `--update-baseline`.** Two of the five are open bugs, and `--update-baseline` is
      all-or-nothing — it rewrites the whole matrix from the current run, so there is no way to
      record the documented rows without also recording the bugs. **A gate that is red because two
      filed bugs are open is a gate working correctly.** The way to green here is to fix them, not to
      re-freeze the baseline around them. Re-baseline only when `coroutine-demo` and
      `rozum-agent-schema-derived` are fixed (or the latter is skipped on *non-hermetic* grounds,
      which is a judgement about the case, not about the gate) — and do it UNSHARDED.

      Confirmed in CI on the full corpus along the way: `head-field-effect-shadow v2 FAIL` is gone
      after `c3841d01e` (run `30282931604`), and the timeout set is provably contention-flaky — run 3
      surfaced `scljet-write-index-deep js TIMEOUT`, which run 2 did not, on identical code.

- [x] **E7 — DONE: paired freeze distinguishes NEW from REGRESSION (found while triaging E4).** The
      baseline records only NON-PASS entries, so a case added after the freeze that is non-PASS looks
      identical to a case that regressed — `coroutine-demo` was reported as a regression on exactly
      that confusion. `contract-roster.tsv` now freezes every name beside the non-PASS rows and binds
      both halves with canonical SHA-256, so NEW, regression, improvement, status change, and removal
      are evidence-backed classifications. **Landed 2026-07-27 as
      `corpus-contract-baseline-roster`: implementation `fc5f07f28`, operator docs
      `2a796b258`.** Plan/spec:
      `specs/corpus-contract-baseline-roster.md`.
      - [x] **E7.1 — freeze the selected-case universe beside the failure rows.** Add sorted,
            unique `tests/conformance/contract-roster.tsv`, paired by SHA-256 to both the
            canonical-LF baseline serialization and its own canonical-LF roster body. Seed it
            from the commit that produced the current baseline, not from current HEAD — otherwise
            the motivating post-freeze `coroutine-demo` would be mislabeled REGRESSION again.
      - [x] **E7.2 — classify from evidence, not absence.** A non-PASS for a rostered case that has
            no baseline row is REGRESSED; a case absent from the roster is NEW whether it currently
            passes or fails. Any unfiltered run, including a production shard, reports
            removed/stale names from the complete pre-shard `selected` universe; only `--only`
            suppresses global removal inference.
      - [x] **E7.3 — close the other partial-update holes found during the read.** Reject
            `--update-baseline` under `--only`, `--shard`, `--list`, or a non-canonical lane set
            (BUGS `corpus-baseline-update-scoped-run-truncates`). A full update rewrites the
            baseline+roster pair and its digest together.
      - [x] **E7.4 — prove the classifier fails loudly.** A lightweight self-test must distinguish a
            synthetic NEW red case from an existing-case regression, report a new PASS case so the
            roster cannot silently age, retain true improvement detection, reject baseline and
            roster-body digest mismatches plus malformed metadata, and prove scoped baseline
            updates exit 2. Then run the real roster check and an affected Corpus Contract slice.
            Do not hand-edit `corpus-baseline.tsv`: the live
            `corpus-gate-remaining-reds` claim owns its re-baseline.
      - [x] **E7.5 — classify by observed cell key, not whole-row absence.** Fix BUGS
            `corpus-contract-delta-false-improvements`: `FAIL → DIVERGE` and
            `KNOWN-RED → FAIL` are status changes only; a backend-excluded lane is unobserved, not
            improved. A frozen red is an improvement only when that exact cell ran and now passes.
            A frozen wildcard SKIP improves only if the case becomes runnable and every observed
            eligible cell passes.
      - [x] **E7.6 — refuse zero-evidence green.** Fix BUGS
            `corpus-contract-zero-evidence-green`: validate option arity/values, reject empty or
            duplicate/unknown lane lists, and exit 2 when a normal gate selects zero cases or
            observes zero case cells. Assertions must check the diagnostic, not just the exit code.
      - [x] **E7.7 — make operator commands executable as written.** Fix BUGS
            `corpus-contract-usage-missing-arg-separator`: every scala-cli example that passes
            contract options includes the required `--` separator; run the displayed self-test
            and slice forms verbatim.
      - [x] **E7.8 — document the lane that production actually executes.** Fix BUGS
            `corpus-contract-doc-mislabels-v2-lane`: `bin/ssc run --v2` is the
            standard/native `RunNativeV2` tier and defaults to direct ASM
            (`bytecode=true`, link-time VM fallback), not the VM-only retired bridge.
            `ssc-tools run --v2` now uses the same native front with
            `bytecode=false`; correct the operator docs plus stale inline comments and
            pin `bin/ssc info --execution-plan --v2` as the architecture check.
      **Verification:** initial roster 465 sorted/unique names; self-test 29/29;
      14/14 CLI refusal diagnostics; real `arithmetic,int-width` slice labels only
      `int-width` as NEW; production-form shard GREEN; conformance 2/2 with both v2
      cells PASS and only the two declared v1 known-red cells. **CI evidence level
      3:** exact-SHA run `30307158170` for `9975a0c0c` was `cancelled` with zero
      jobs (RED/no verdict); release uses the named local gates above.

- [x] **E8 — DONE: restore the repository markdownlint gate.** Fix BUGS
      `markdownlint-bugs-lane-labels`: two existing `BUGS.md` lane summaries use
      adjacent `[INT][JS][JVM]` text (MD052), and the TSV example in
      `specs/claim-mutex.md` uses eight invisible hard tabs (MD010). Render the lane
      summaries as inline code and the delimiters as explicit `<TAB>` markers, then run
      the exact CI command `markdownlint '**/*.md' --ignore node_modules`; do not
      disable either rule.
      **Landed:** `ffb7b4695`. Exact full-repository A/B is 10 diagnostics before
      versus 0 after; MD052/MD010 remain enabled. Mandatory `arithmetic`
      conformance is 1/1 with INT, JS, and JVM all passing.
      **CI evidence level 3:** exact-SHA run `30307690766` for `90745ec27`
      remained `pending` with zero jobs at release (no CI verdict); release uses
      full-repository markdownlint, `git diff --check`, and the 1/1 conformance
      slice as its named local gates.

- [x] **E5 — DONE: make a timeout impossible to misread as benign.** After E2 the gate fits its budget,
      but the *detection* hole stays: any future budget breach reappears as `cancelled`. Cheapest
      honest fix — record the hazard in `MILESTONES.md` §Health next to the CI-radar note so the next
      status sweep counts `cancelled` as red for scheduled workflows.

## control-interoperability — target-neutral control ABI plus mandatory host/runner milestones (2026-07-14, Sergiy)

Goal: implement [`specs/control-interoperability.md`](specs/control-interoperability.md)
once, then expose it through native typed bidirectional value-and-call bridges for
Scala/JVM, JavaScript/TypeScript, Rust, and Swift, plus independently qualified
portable runners (including WASM/WASI). Pure values, effects, handlers, multi-prompt
`shift`/`reset`, callbacks, mixed tail calls, and saved continuations retain one
observable semantics across every lane. Host profiles never put platform types into
CoreIR or become semantic owners; runner delivery order is selected by measured
readiness, while all four host families and the N→M matrix remain mandatory.

Durable control uses the simple reusable **save/run** idiom, not replay:
`continuation.save(): Eff[Save,SavedContinuation.Aux[A,Fx,R]]` freezes a compiler-managed continuation;
every admitted `saved.run(value)` invokes its resume entry once directly at the capture point. The
prefix is never re-executed; the suffix then follows its own effects/loops/multi-shot behavior. The
opaque transport envelope contains either a portable closed CoreIR resume program
`(FrozenFrame,input) => Eff` plus a separately hashed frame, or exact-artifact
`target + toolchain + artifactDigest + resumePointId + frame` state for managed host
code. `CodeMode` is independent from `FrameGate`: exact artifacts never rescue raw
foreign values or live resources. Atomic one-shot workflow execution remains an
optional policy, not the default continuation semantics.

### Specification and contract freeze

> **GATE LIFTED 2026-07-16 — Sergiy's call, on measured evidence.** These three items (and, behind
> them, `save()`/`run()` and `control-interop-examples`) waited on "the UniML P6.5 literal-fixed-point
> sequence `F1 → F2/F3 → L1 → X1` green and frozen". **X1 now holds and was verified independently
> by the coordinator, twice, from a clean `scala-cli --power package v2/src --assembly` build:**
> `specs/v2.2-p6.5-fsub.sh --self` → **89 ok / 0 FAIL, exit 0**; `F(F_src) == ssc1-front(F_src)`
> byte-identical (79,667 B); `stage1 == stage2` byte-identical; the self-produced compiler C1 is
> byte-identical to the reference and its IR runs → 120.
>
> **Read the boundary before you rely on this.** The fixpoint is REAL but SCOPE-BOUNDED: `F` compiles
> the subset `S` that `F` is itself written in — **not** all of ScalaScript. Still outside S:
> given/summon dict-passing, enums, extensions, for-comprehensions, `var`/`while`, string
> interpolation, the prelude-selector table, the List-var registry. (Case classes landed as X1h.)
> P6.5 stays `[~]`, not `[x]`, and its HONEST BOUNDARY note is the authority — re-read it, don't
> infer scope from the word "fixpoint". Sergiy's rationale for lifting anyway: X1 proves the Core IR
> byte contract is stable and self-consistent, which is exactly what these items depend on; P6.5
> breadth grows in parallel and re-proves `--self` on every slice.
>
> **Unblock order (do not skip):** these three → `save()`/`run()` → `control-interop-examples`.

- [x] **coreir-canonical-contract-reconcile — ✓ LANDED 2026-07-16** (`69f3ad4d3`/`06f55e621`/`7bcb6d87a`) — reconcile the frozen-count/no-loop claims in
  `v2/specs/10-core-ir.md` with the current canonical Reader/Writer and `CoreIR.scala`, which already
  serialize `While` and `Seq`. Pin one canonical node/value inventory before freezing the capsule
  encoding; this is documentation/contract drift, not permission to add a continuation node. After
  landing, re-run and re-freeze the literal stage1==stage2 fixed point against the reconciled bytes.

  **MEASURED BASELINE (2026-07-16, before any change — reproduce before trusting):**
  `scala-cli --power package v2/src --assembly -o /tmp/ssc.jar`; then
  `SSC_JAR=/tmp/ssc.jar V2_DIR=<repo>/v2 ./specs/v2.2-p6.5-fsub.sh --self` → **89 ok / 0 FAIL, exit 0**;
  fixpoint `stage1 == stage2` = **79,667 B**, sha256 `c5d9b5ed034d81a446f60dbe3b8dab15dcb910bbf318b6bfc01e4f63769c8f81`.
  **De-risking measurement:** that 79,667 B corpus contains **zero `(float …)` and zero `(bytes …)`
  constants** — so the float/`-0.0`/`IrBytes` codec fixes provably cannot move the fixpoint. Verified
  by `grep -c float stage1.ir` = 0 on a work dir preserved from the script.

  **The drift, measured (not read off the source):**
  | Claim in spec | Reality in `v2/src/CoreIR.scala` |
  |---|---|
  | `10-core-ir.md` header: "node set (11 nodes) are frozen" | `enum Term` has **13** cases (adds `While`, `Seq`) |
  | `10-core-ir.md` §3: "**`Seq a b` is dropped**" | `Term.Seq(terms)` exists, Reader head `seq`, Writer emits `(seq …)` |
  | `10-core-ir.md` inv.7: "**no loop node is needed** in Core IR" | `Term.While(cond, body)`, Reader head `while`, Writer emits `(while …)` |
  | `12-ir-format.md` grammar: `term := lit \| local \| … \| prim` | no `while`/`seq` production, though both round-trip |

  Both `While` and `Seq` are documented in-source as **optimizations** (no trampoline bounce / no
  Let-binding overhead), not new semantics — reconcile them as such. NOT permission to add a
  continuation node (§9 "Explicitly NOT in Core IR" stands).

  - [x] **R1 — pin ONE inventory.** Reconcile `10-core-ir.md` (11→13 nodes, retract "Seq is dropped",
    restate inv.7 as "TCO makes a loop node unnecessary *for semantics*; `While` is a permitted
    optimization node with `LetRec`-equivalent meaning") + `12-ir-format.md` (add `while`/`seq`
    grammar + canonicalization). Value domain stays **10 shapes** (verified: matches `Value` — no drift).
  - [x] **R2 — make the inventory machine-checked (gate BEFORE feature, AGENTS.md §measurement).**
    A drift test asserting spec inventory == `Term`/`Const` enum cases == Reader heads == Writer cases.
    Docs drifted silently for ~3 weeks precisely because nothing compared them. Prints want/got/diff.
  - [x] **R3 — re-run + re-freeze** the literal fixed point; docs-only ⇒ bytes MUST NOT move (89/0, 79,667 B).

  **RESULT.** `specs/coreir-inventory-gate.sh` (new) compares 6 sources — the pinned block, `enum Term`,
  `enum Const`, Reader heads, Writer heads, IrEncode/IrDecode tags — **10/10 green**. Inventory pinned at
  **13 nodes / 7 constants** in `10-core-ir.md` §3.2; §3.1 explains why `While`/`Seq` are legitimate
  (exact semantics-preserving desugarings) and why that is **not** a precedent for a continuation node.
  Fixpoint re-run: **89 ok / 0 FAIL, 79,667 B, output byte-identical to baseline**.
  *Also found and reconciled, beyond the brief:* §2's "Ten shapes" undercounts — the evaluator has 14
  `Value` subclasses = the 10 semantic ones + 3 private `Foreign` representations
  (`MapV`/`LongCellV`/`DoubleCellV`, already covered by `Foreign`'s "hash map, growable array, mutable
  cell") + **`DecimalV`, a genuine 11th shape** (language-visible via 11 `dec.*` prims). `Decimal` is
  deliberately **not** capsule-encodable (no `Const` case), which is why the value domain is 11 while the
  constant inventory is 7 — different questions, and conflating them is what let "ten shapes" survive.
  *Apparatus note:* the gate's FIRST run reported 6 failures of which **4 were the gate's own fault**
  (order-sensitivity + a regex scooping up `arm`, a sub-form). Same ratio as the `newfront-diff.sh`
  incident. Fixed the apparatus before believing it; reasoning kept in the script's comments.

- [x] **coreir-canonical-codec-hardening — ✓ FULLY LANDED 2026-07-17** (`69f3ad4d3` H1/H2/H6/H7; `644543cf5` **H4/H5**; H3 doc-only) — make the canonical codec match its contract before it is
  used for untrusted persisted capsules: preserve floating-point bit identity including `-0.0`, add
  `IrBytes` encode parity, reconcile `coreir.encode`'s promised Bytes with its actual String, provide
  the specified text/bytes decode path, validate symbols/closed globals/arities, and enforce bounded
  decoding. Add encode/decode/canonicalization vectors for every node and constant, then re-run the
  literal fixed point. Canonical Reader/Writer remains kernel-owned.

  **⚠️ THE LANDMINE (measured — do not skip).** `Writer.floatStr` is **not** an IR-only function. It is
  shared by `f->str` (`Runtime.scala:2560,4217`), `FloatV.toString` (`:3039`), Float↔String concat
  (`:4011,4015`) and **`Show`** (`:4408,4655` — program output). Collapsing whole doubles (`2.0`→`"2"`)
  there is **deliberate v1 output parity** (see the comment at `:4008`; cf. the scljet `0.0.toString`→`"0"`
  gotcha). Measured in the real runtime: `f->str 2.0` = `2`, `f->str 7.5` = `7.5`, `f->str -0.0` = `0`.
  ⇒ **Fix by SPLITTING a new IR-only `Writer.floatLit` from `floatStr`; never "fix" `floatStr` itself.**
  A run-ir-only gate would NOT catch that regression (memory: changing shared VM `Show` once broke ~28
  mira JS/Rust checks that run-ir-only gates masked; the catch is full `v2/conformance/check.sh`).

  **Contract violations, each measured end-to-end:**
  1. `coreir.encode(IrFloat(-0.0))` → `(lit (float 0))` → decodes to **+0.0**. Bit identity lost.
     `12-ir-format.md` §Tokens is explicit: "Negative zero is `-0.0`".
  2. Integral floats emit `(float 2)`, but §Tokens requires FLOAT "always containing a `.` or an
     exponent". Value still round-trips (the `float` tag disambiguates), so this is *form* drift.
  3. `IrEncode.const` has **no `IrBytes` case** (`Runtime.scala:4573-4580`) though `IrDecode.constant`
     has one (`:4635`) and `Const.CBytes`/Reader/Writer all support bytes ⇒ `coreir.encode` of a bytes
     literal dies with `sys.error("bad const")`. Asymmetric codec.
  4. `10-core-ir.md` §5 promises `coreir.encode v`→**`Bytes`**; `Runtime.scala:2919` returns **`StrV`**.
  5. `coreir.decode` is **not registered as a prim at all** (only `coreir.encode`/`coreir.eval` exist);
     §5 itself admits "Still deferred at the kernel level: `coreir.decode`". `12-ir-format.md` promises
     `encode ∘ decode = canonicalize` — currently unimplementable from `.ssc`.
  6. **Fail-open decoding** (this codec is for **untrusted** capsules — fail-open here is a security
     property, not a nicety): `Reader.parseHex` accepts odd-length hex (`"abc"`→2 bytes) and `parseInt`
     accepts `+1`/`-1`/uppercase; `(local -1)`/`(lam -1 …)`/`(arm T -1 …)` accepted (spec: NAT);
     `readAtom` accepts any non-delimiter run as SYMBOL (spec: `[A-Za-z_][A-Za-z0-9_.]*`); `letrec`
     accepts non-`Lam` bindings (spec §4: "bindings must be Lam"); unbound `(global g)` accepted;
     `Reader.P.sexpr()` is **unboundedly recursive** ⇒ a deeply-nested hostile capsule is a
     StackOverflowError, not a diagnostic (note `v2.2-p6.5-fsub.sh` already needs `-Xss512m`; and CI
     runs a 1m default stack where macOS gives 2m — the exact shape of the 192-run CI red).

  - [x] **H1 — float bit identity.** New IR-only `Writer.floatLit` per §12 (`-0.0`, always `.`/exp,
    specials `nan`/`inf`/`-inf`, else shortest round-trip). Used by `Writer.const` + `IrEncode.const`
    ONLY. `floatStr` untouched. Verify: full `v2/conformance/check.sh`, not just run-ir.
  - [x] **H2 — `IrBytes` encode parity** in `IrEncode.const` (`(bytes)` empty / lowercase hex).
  - [x] **H3 — reconcile encode's Bytes-vs-String.** ✓ *spec reconciled to reality (`coreir.encode`→`Str`); the rejected alternative is recorded in `10-core-ir.md` §5.* Format is canonical **text** (§12 v1). Land as:
    spec says `coreir.encode v`→`Str` (canonical S-expr text; UTF-8 via `str->utf8`). Changing the prim
    to return `Bytes` would break every existing caller (`lib/ssct-emit.ssc0`, `bin/mirac.ssc0`, the
    p6.5 driver) for no gain — record that as the rejected alternative.
  - [x] **H4 — the specified decode path. ✓ DONE 2026-07-17 (`644543cf5`).** `coreir.decode : Str|Bytes -> IrProg`
    is registered (`Runtime.scala`), backed by new `IrToData` (Data-level mirror of `IrDecode`) over the
    kernel `Reader`. `encode ∘ decode = canonicalize` and `decode ∘ encode = id` are now expressible from
    `.ssc`; `specs/coreir-codec-vectors.sh` round-trips all 13 nodes + 7 constants (incl. `-0.0`/`nan`/`inf`/
    bytes) through decode and pins the canonicalize property on a lenient/commented program. Kernel-owned.
  - [x] **H5 — ✓ DONE 2026-07-17 (`644543cf5`). Reader fails CLOSED** (`CoreIR.scala`). Strict `NAT`/`INT`/`HEX`
    token parsers reject `(local -1)`, `(int +1)`/`(int 01)`, `(lam +1 …)`, `(arm T -1 …)`, odd/signed/non-hex
    `(bytes …)`. New `Reader.validate` (run on every `parseProgram`) scope-checks every de Bruijn `Local` in
    range, requires `letrec` bindings to be `Lam`, and rejects unbound globals (closed = a top-level def or an
    `@`-cell). Each rejection names the offending node. **Measured RED-before / GREEN-after:** the vectors are
    47/47 on the pre-fix kernel, 94/0 after. Keystone: an unbound global in a never-evaluated branch used to
    run clean; now rejected at decode. Global-closedness verified low-risk: the 79,853 B self-hosted compiler
    IR has 254 defs / 208 globals with **zero** unbound; full `check.sh` shows no regression.

  **EXECUTION PLAN (agent coreir-codec-h4h5, 2026-07-17) — decisions pinned so a fresh agent resumes cold:**
  - **Where H5 lives:** kernel-owned `Reader` in `v2/src/CoreIR.scala`. Wire a `Validator` into
    `Reader.parseProgram` so it runs on EVERY decode (the untrusted-capsule entry point: `run-ir`,
    `coreir.decode`). Strict token parsers (`natOf`/`intOf`/`bigOf`/`hexOf`) replace the lenient
    `i.toInt`/`a.toInt`/`n.toLong`/`grouped(2)` in `toTerm`/`toArm`/`toConst`.
  - **Scope model (verified against `Runtime.scala` compiler + `10-core-ir.md` §4):** de Bruijn
    depth. entry & each Def body start depth 0. `Local(i)`: `0<=i<depth`. `Lam(ar,b)`: body at
    `depth+ar`. `Let(rhs,body)`: rhs(i) at `depth+i`, body at `depth+len` (let* sequential). `LetRec`:
    each lam & body at `depth+len` (all mutually visible), each binding MUST be `Lam`. `Match`: scrut &
    default at depth, arm body at `depth+arm.arity`. `App/If/Ctor/Prim/While/Seq`: subterms at depth.
  - **NAT** = `0|[1-9][0-9]*` (local idx, lam arity, arm arity); **INT** = `-?`NAT no `-0` (int/big
    literal); **HEX** = even-length `[0-9a-fA-F]`. All reject `+`, negatives-where-NAT, garbage.
  - **Global-closedness DECISION (measured, not guessed):** reject `Global(g)` unless `g` is a
    top-level `Def` name OR starts with `@` (mirrors the runtime's own resolve fallback at
    `Runtime.scala:1016`; the kernel Reader cannot see the plugin registry). MEASURED SAFE: the
    79,853 B self-hosted compiler IR has 254 defs / 208 globals and **every global is a def** (0
    unbound); the 7 `.coreir` fixtures likewise. The native front's plugin-`Global` programs go
    through `Lower`→`Compiler` directly, NOT `parseProgram`, so they are not on this path. Full
    `check.sh` is the final guard — if any run-ir program legitimately references a plugin global,
    scope this down and record it OPEN rather than break the corpus.
  - **H4:** new `IrToData` object in `Runtime.scala` (mirror of `IrDecode`: `Program -> IrProg` Data
    value), then prim `coreir.decode : Str|Bytes -> IrProg` = `IrToData.program(Reader.parseProgram(text))`
    where `Bytes` is decoded UTF-8. Property from `.ssc`: `encode(decode(t)) == canonicalize(t)` and
    `decode(encode(x))` reconstructs `x`. Add both to `specs/coreir-codec-vectors.sh` (round-trip every
    node+const incl. floats `-0.0`/`nan`/`inf` and bytes; rejection vector per fail-open).
  - [x] **H6 — bounded decoding.** ✓ *reader half only — see the note below.* Depth + node-count + input-size limits; iterative or depth-capped
    reader. Hostile input ⇒ diagnostic, never StackOverflowError. Must hold on a **1m** stack (CI), not
    just a macOS 2m default — test with an explicit small `-Xss`, or the gate lies exactly like the
    192-run CI red did.
  - [x] **H7 — vectors for EVERY node and constant** (13 nodes × encode/decode/canonicalize + all 7
    consts + the negative/hostile cases above). Every vector prints name/want/got/diff — never bare
    `[[ $(…) == "$want" ]]` under `set -e` (that gate prints nothing and has been red for days before).
  - [x] **H8 — re-run the literal fixed point** (expect unchanged 89/0, 79,667 B — corpus has no
    float/bytes consts; if it moves, STOP and coordinate with p65-fixpoint + newfront).

  **RESULT (measured, every gate actually run).**
  - `coreir.encode(-0.0)` → `(lit (float -0.0))` (was `(float 0)` → decoded as `+0.0`). `-0.0` and `0.0`
    now encode **differently**, and the sign survives the reader — witnessed by `1/-0.0 = -inf` vs
    `1/0.0 = inf`, because `-0.0 == 0.0` is true in IEEE-754 so `f.eq` cannot tell them apart.
  - Fixed by **splitting** an IR-only `Writer.floatLit` from `Writer.floatStr`. `floatStr` is shared with
    `f->str`/`.toString`/concat/`Show`, where `2.0`→`"2"` is deliberate v1 parity — verified unchanged
    before *and* after. Editing `floatStr` would have regressed program output corpus-wide and the
    run-ir-only fixpoint gate would **not** have caught it (the corpus has zero float constants).
  - `coreir.encode` of a bytes literal used to **crash** ("bad const"); now `(bytes 4869)` / `(bytes)`.
    `IrEncode.const` renders through the kernel-owned `Writer.const`, so the duplication that let this
    drift is gone.
  - Bounded decoding: a 300 KB **well-formed** depth-50000 capsule was a `StackOverflowError` at `-Xss1m`;
    now a diagnostic. `Reader.MaxDepth` = 1000 (`-Dssc.coreir.maxDepth=N`), ~40× headroom — measured, real
    IR is shallow: the X1 fixpoint's own IR is depth **25**, fixtures 6-12.
  - **Gates:** inventory 10/10 · codec vectors **43/43** · fixpoint **89 ok / 0 FAIL, 79,667 B,
    byte-identical to baseline** · full `v2/conformance/check.sh` **639 ok / 3 FAIL — all 3 pre-existing**,
    proven by running the suite on a *pristine `origin/main` worktree* (identical 639/3 and an identical
    ok-set). `ssc0c uselib.ssc0 ir differs` is **not** a regression from this work.
  - **H4/H5 UPDATE (2026-07-17, `644543cf5`).** H5 fail-open class CLOSED: the reader now rejects
    `(local -1)`, out-of-range locals, `(lam +1 …)`/`(arm T -1 …)`, `(int +1)`/`(int 01)`,
    odd-length/signed/non-hex `(bytes …)`, non-`Lam` `letrec` bindings, and unbound globals — each with a
    node-naming diagnostic (`Reader.validate` + strict token parsers, run on every `parseProgram`). H4:
    `coreir.decode` registered (`IrToData`). **NOT closed** (recorded, not papered over): (1) the
    SYMBOL-charset check on `op`/`tag`/`name` is deliberately NOT enforced — real prim ops (`f->str`,
    `str->utf8`, `i->big`) contain `->` and are not `[A-Za-z_][A-Za-z0-9_.]*`, so validating them against
    the spec's SYMBOL regex would reject the corpus; the grammar's `op := SYMBOL` is itself drift.
    (2) the `Compiler.valuePositionsNeedEffectThreading`/`FastCode.tryFC` unbounded-recursion overflow at
    ~depth 500 on `-Xss1m` (`BUGS.md` → `coreir-compiler-unbounded-depth`) is a *compiler*, not *reader*,
    bound and still open — the capsule path is not fully DoS-safe until it lands.
- [~] **numeric-width-reconciliation — LANDED 2026-07-17 (option A); one slice deferred, see below** (Sergiy's call,
  asked with all three options + their costs on the table; raised by coreir-contract, who correctly
  refused to choose unilaterally) — retain source `Int`/`Long`
  width evidence and implement canonical public `I32`/`I64` semantics over the current signed
  wrapping-64 CoreIR value. Add per-backend wrap/round-trip/overload vectors and reject legacy
  ambiguous exports; this is semantic lowering work, not descriptor-only mapping.

  **THE DECISION — (A) `Int` → `I64`.** Make the descriptor truthful to the measured semantics.
  NOT (B) (making surface `Int` genuinely 32-bit — rejected: it contradicts the frozen
  `10-core-ir.md` §2 value domain, `Int = Long` v1 parity, and measured behaviour, and would force a
  Core IR version bump across every backend and the whole corpus). NOT (C) as a whole for now (I64
  plus a fully-implemented narrowing ABI) — (A) is (C)'s first slice, so (C) stays reachable and is
  not foreclosed; do not delete its analysis above.
  **What (A) obliges you to do, and it is the whole difficulty:** once `Int` and `Long` both map to
  `I64` they are **indistinguishable in the descriptor**, so overload IDs collide unless the source
  spelling is retained separately — that is exactly what "retain source `Int`/`Long` width evidence"
  means, and it is not optional garnish. Also flips 6 live expectations
  (`PreBodyApiDescriptorProducerTest.scala:100,130,132,136,267,1212`) and **re-hashes every
  descriptor** (`AbiPrimitive` feeds the frozen Slice A `apiHash`) — a deliberate contract change,
  so land it as such: announce in rozum, and do not bundle it with unrelated work.

  **THE CONTRADICTION (measured 2026-07-16, in the real runtime — not read off source):**
  `v1/lang/core/src/main/scala/scalascript/artifact/PreBodyApiDescriptorProducer.scala:2066` maps
  source `Int` → `AbiPrimitive.I32`, and `:2067` maps `Long` → `I64`. But **ssc `Int` is 64-bit**:
  ```
  java -jar /tmp/ssc.jar run  #  (#i.add(2147483647, 1))          => 2147483648        <- did NOT wrap at 32
  #                              (#i.add(9223372036854775807, 1)) => -9223372036854775808 <- DID wrap at 64
  ```
  Corroborated by `v2/specs/10-core-ir.md` §2 ("`Int` is 64-bit two's-complement, wrapping (matches
  `ssc 1.0`'s `Int = Long`)") and the durable memory note `project_interp_int64_and_entrypoint.md`
  ("ssc Int is 64-bit"). ⇒ **The descriptor currently tells every foreign host (JS/TS, Rust, Swift,
  WASM-WASI) that an ssc `Int` is 32 bits when it is 64.** A host marshalling per the descriptor
  silently truncates any value > 2^31−1 **at the ABI boundary** — a fail-open of exactly the kind the
  descriptor exists to prevent, and it is the *interop* surface, so it is cross-language.

  **Why this is NOT a unilateral fix:** `Int → I32` is not dead code — it is asserted by live tests
  (`PreBodyApiDescriptorProducerTest.scala:100,130,132,136,267,1212`), and `AbiPrimitive` is part of
  the **frozen Slice A schema** feeding `apiHash`. Changing the mapping changes the meaning and the
  hash of every descriptor ever emitted. That is a contract change and needs Sergiy's call.

  **The three options (pick one before any code):**
  - **(A) `Int` → `I64` — truthful to measured semantics.** `Int`/`Long` both become `I64` and are
    indistinguishable in the descriptor ⇒ needs separate retained source-spelling width evidence to
    keep overload IDs distinct (this is what "retain source `Int`/`Long` width evidence" reads as).
    Flips the 6 test expectations above; re-hashes every descriptor. *Cheapest that stops the lie.*
  - **(B) Make surface `Int` genuinely 32-bit.** Matches Scala and makes the existing descriptor
    correct retroactively, but contradicts the **frozen** `10-core-ir.md` §2 value domain, the
    `Int = Long` v1 parity, and measured behavior; blast radius across every backend + the whole
    corpus. Would be a Core IR **version bump (v2)** per `10-core-ir.md`'s own freeze rule.
  - **(C) `Int` → `I64` public, with an explicit narrowing ABI.** Where a host genuinely wants 32-bit
    it becomes an explicit, *implemented* wrap/checked-narrow at the boundary (real semantic lowering,
    per the item's own "not descriptor-only mapping"), and ambiguous legacy exports are rejected.
    *Most faithful to the item text; most work.*
  My read: the item text ("over the current signed wrapping-64 CoreIR value", "reject legacy ambiguous
  exports", "not descriptor-only mapping") points at **(C)**, with **(A)** as its first slice. But
  (B) is a coherent reading of "canonical public I32 semantics" too, so I am not choosing.
  Tracked as a live fail-open in `BUGS.md` (`coreir-abi-int-width-declared-i32-actually-i64`).

  **EXECUTION (agent `int64-abi`, 2026-07-17).** Spec: [`specs/numeric-width-reconciliation.md`](specs/numeric-width-reconciliation.md).
  Baselines measured in a clean worktree BEFORE any change: P6.5 fixpoint **89 ok / 0 FAIL**,
  `stage1 == stage2` byte-identical at **79,667 B**; producer suite green. The descriptor leaf is
  outside `v2/src` ⇒ the fixpoint bytes MUST NOT move; if they do, stop and coordinate with P6.5.

  **COLLISION PROVEN, not assumed** (bare one-line mapping flip, nothing else changed, probe
  `def widen(value: Int)` / `def widen(value: Long)`): before flip overloadIds `dde22763…` vs
  `922b20fa…` (distinct only because `I32 != I64`); after flip →
  `DUPLICATE_SYMBOL_ID at $.symbols: ssc:symbol:v1:5ddf0353…`. Note it fails **closed** (the factory
  rejects the module) rather than silently merging overloads — but `Int`/`Long` overloads become
  unexportable, so the bare flip is not a fix. Width evidence is mandatory.

  **DESIGN:** `AbiType.Primitive(value: AbiPrimitive, declaredWidth: Option[NumericWidthEvidence] = None)`
  + `enum NumericWidthEvidence: case DeclaredInt, DeclaredLong`. `value` = the **wire width** (the
  marshalling contract; hosts read this alone and are correct). `declaredWidth` = the **source
  spelling**, evidence only — it keeps identity exact and is where (C)'s narrowing binds.
  `AbiPrimitive` keeps all 9 cases; `I32` becomes unreachable from ssc source and is RESERVED for (C).

  Slices:
  - [x] **width evidence retained pre-body** — `Int -> Primitive(I64, Some(DeclaredInt))`,
    `Long -> Primitive(I64, Some(DeclaredLong))` in `PreBodyApiDescriptorProducer:2066-2067`;
    evidence survives `Normalization.abiType`/`TypeWire.identityType` (both pass `Primitive` through
    whole) ⇒ overload IDs stay distinct.
  - [x] **public I32/I64 semantics** — `I64` = 64-bit two's-complement wrapping, identical to the
    Core IR value domain. NOTE: under (A) there is deliberately **no wrap to implement at the
    boundary** — every ssc integer is I64 end to end, so nothing narrows. A "real wrap at the
    boundary" belongs to (C)'s narrowing ABI; inventing one here would be the same guess in the
    other direction. Validator invariants instead (fail-closed): evidence ⇒ `value ∈ {I32,I64}`
    (`INVALID_NUMERIC_WIDTH_EVIDENCE`); `value ∈ {I32,I64}` ⇒ evidence (`AMBIGUOUS_NUMERIC_WIDTH`).
  - [x] **reject legacy ambiguous exports** — falls out of the above + `exactObject`: a legacy
    `{"tag":"Primitive","value":…}` fails loudly `SCHEMA_MISMATCH … missing=[declaredWidth]`
    instead of defaulting to `Long`.
  - [ ] **per-backend vectors — SCOPE CORRECTED, measured 2026-07-17.** The item assumed machinery
    that **does not exist**: there is NO code anywhere mapping `AbiPrimitive`/`AbiType` to a host
    type — no binding generator, no FFI emitter, no marshaller for JS/TS, Rust, Swift or WASM-WASI
    (`v2/host/` has only `js/` + `scala/`; `js/control/index.d.ts` is control-only, no numeric
    bridge). `tests/interop-conformance` is control-law/stdout-oracle shaped (TSV has no width or
    backend column, runner knows 4 hard-coded adapters) and all 5 generated host lanes are
    `pending`/adapter `none`. Swift + WASM-WASI have **no spec'd numeric mapping at all** to write
    against. ⇒ A cross-language "vector" here would exercise nothing and pass vacuously — exactly
    the apparatus-that-reports-green failure AGENTS.md §"measurement apparatus" bans. Honest split:
    - [x] vectors at the seam that REALLY exists (producer + codec round-trip + overload identity),
      each carrying a **negative control** that proves the check fails on a truncating 32-bit
      mapping — a vector that can't fail is not a vector.
    - [~] the per-host numeric contract as a **pinned table** (boundary 2^31-1, overflow32 2^31,
      max64, min64) each host must satisfy WHEN its lane lands. **PARTIAL, honestly:** the table is
      pinned in `NumericWidthAbiVectorTest` ("host carriers … 64-bit capable in every required
      profile") and the per-host mapping is now written into all five profile specs (Swift and
      WASM-WASI had none and were authored). **NOT done:** no record was added to
      `tests/interop-conformance/pending/`, because that catalogue's schema is control-law shaped
      (`law/capabilities/phase/expectedExit/expectedStream/oracle` — no width or backend column) and
      its runner knows only 4 hard-coded adapters. Adding numeric vectors there means extending the
      TSV schema **and** `run.sh`, which is the sibling harness's contract, not mine to change
      mid-flight. **Do this when the first host lane stops being `pending`/adapter `none`** — until
      then a row there would execute nothing.
  - [x] **fix the specs this change makes FALSE** — `specs/scala3-bidirectional-control.md:402-404`
    states "ScalaScript source Int and Long map to canonical I32 and I64"; that is now a lie.
    `specs/javascript-typescript-bidirectional-control.md:480-482` already says "I64 | bigint;
    conversion through number rejects" + prohibits representing I64 as `number` — under (A) this now
    binds ssc `Int` too, i.e. **JS hosts must marshal an ssc `Int` as `bigint`, never `number`**.
    That is a real, user-visible consequence and must be written down.
  - [x] **re-run the literal fixed point** — stayed **89 ok / 0 FAIL** at **79,667 B**; the gate's
    whole output is byte-identical to the pre-change baseline, so the contract did not move.

  **LANDED 2026-07-17** — spec `4bdd5e986`, feature `9c49438d4`, docs `ccc47efe1`, bookkeeping `b40a0f9ae`. Verified:
  producer 83/83, descriptor 32/32, `core/test` 1138/1138, interop 36/36, plugin-profile 23/23,
  conformance `modules*,import-dir*` 2/2 on INT/JS/JVM, P6.5 89 ok / 0 FAIL @ 79,667 B.
  The new vectors were proven non-vacuous (reintroducing `Int -> I32` reddens all 5 with
  `vector overflow32: … changed the value from 2147483648 to -2147483648`; a validator sabotage
  probe reddens the 3 rejection controls). BUGS entry → FIXED.

  **WHAT REMAINS (why this item is `[~]` and not `[x]`):** only the interop-conformance `pending/`
  record above, which is blocked on a host lane existing at all. **(C) is untouched and reachable**
  — `AbiPrimitive.I32` is still in the frozen schema, is now unreachable from ssc source, and
  `declaredWidth` is exactly the node a narrowing contract binds to. Nothing here forecloses it.

### Common ABI and portable semantic baseline

- [ ] **ssc-api-descriptor-v3** — replace best-effort string-only interop signatures with an additive,
  versioned pre-body `ApiDescriptor`, post-body `ControlSummary`, and post-link `ArtifactManifest`:
  canonical types/generics/effect rows, callback convention + invocation/escape/thread policy,
  prompt metadata, stable overload IDs/JVM entrypoints, `apiHash`, control/save/tail summaries,
  `programDigest`, `artifactDigest`, and dependency-profile binding. Preserve old `.scim` meanings.
  Resume-cold slices are frozen in [`specs/ssc-api-descriptor-v3.md`](specs/ssc-api-descriptor-v3.md):
  - [x] **A — canonical descriptor leaf + additive carrier (landed 2026-07-15):** target-neutral
    `v2/interop/descriptor`, bounded canonical JSON/SHA-256 identities and validation, checked
    factories for all three phase records, and only a defaulted opaque `apiDescriptorV3` JSON
    payload on legacy `.scim` (`286de7cee`). Verified: descriptor 27/27, artifact ABI 73/73,
    core 1046/1046, interop 36/36, leaf has no project dependencies, and affected conformance
    `modules*,import-dir*` is 2/2 on interpreter/JS/JVM. Slices B/C/D remain open below.
  - [ ] **B — pre-body producers:** project declarations/real width evidence into v3 before body
    compilation; reject ambiguous/dynamic managed exports and never parse legacy `tpe` to invent v3.
    - [ ] Reject retained declaration source whose parsed section AST was lost; preserve the
      reviewer repro that copies a valid module with `sections = Nil`.
    - [ ] Make effect-header evidence comment/string invariant and bind it to the exact lexical
      effect owner/order; an ordinary same-name object must never steal another effect's header.
    - [ ] Close nominal losslessness gaps for trait constructors/self types, template exports,
      and constructor `val`/`var` accessors until receiver/member metadata can represent them.
    - [ ] Replace `PreBodyApiDescriptorProducer.topLevelStats` count-only pairing with exact
      per-block declaration-header correspondence between canonically reparsed retained source
      and the stored section AST. Ignore bodies/comments, but reject the reviewer's tamper where
      retained `effect Real:` is paired with a stale AST still containing `read`; require one
      exact package-wrapper chain, normalize placeholder aliases on both sides, and keep stale
      body/RHS/default-expression-only edits descriptor/hash invariant. Explicitly witness every
      current ScalaMeta definition form whose header can survive parsing (`GivenAlias`, template
      `Given`, extension groups, and macros included); a product-prefix-only fallback is not exact,
      and unknown future definition forms must compare conservatively rather than accepting a
      changed header.
    - [ ] Make every manifest package wrapper exact/plain before unwrapping: the expected name and
      singleton child are insufficient if the stored object has modifiers, parents/inits, derives,
      self type, or any other non-body header state. Preserve a faithful stored
      `object demo extends Serializable: object api: ...` versus retained plain-source repro and
      reject it at the section/block path.
    - [ ] Treat parseable executable `ast.Content.CodeBlock.source` as mandatory retained evidence,
      including when `module.document = None`; reparse it and unwrap manifest wrappers as required
      before comparing body-erased headers with the stored tree. If both document and code-block
      sources exist, verify both against the stored AST and fail closed on semantic header
      disagreement; only after agreement prefer document source for effect-header evidence, else
      use the code-block source. Regress the documentless packaged stale-source case and dual-source
      disagreement without breaking stale body/RHS/default-expression invariance.
    - [ ] Index every known local type/alias with effective owner visibility. A public signature
      resolving to a private/internal local owner or alias must fail `UNSUPPORTED_PUBLIC_TYPE`
      before external `AbiType.Named` fallback or callback classification; regress relative and
      absolute `private Hidden.T`, an `@internal` callback alias both directly and through a
      public wrapper alias, a private local `Array` shadowing the `Array[Byte]` fast path, and a
      qualified private local effect row. The built-in bytes fast path applies only when no lexical
      local `Array` exists: a public local `Array` follows ordinary local-constructor projection,
      while a known non-public local `Array` rejects before the fast path.
    - [ ] Gate the `Array[Byte]` → primitive `Bytes` shortcut on lexical resolution of both
      components, not spelling alone. A binder named `Array` or `Byte`, and any known local
      `Array`/`Byte`, shadows the built-ins; non-public locals reject normally, while public/bound
      components take ordinary projection and never become `Bytes`. Regress generic `Byte`, private
      and `@internal` local `Byte`, and public local `Byte`, preserving both existing local-`Array`
      cases and the real built-in bytes positive.
    - [ ] Reject selected public/exported `Defn.Var` with
      `UNSUPPORTED_PUBLIC_DECLARATION` until an additive descriptor revision represents
      mutability. Keep equivalent `val` positive and do not change the frozen Slice A schema.
    - Baseline: focused producer suite is `18/25` green; the seven new faithful regressions
      fail as 1 lost-AST + 2 effect-evidence + 4 nominal-surface cases before the fix.
      The first correction checkpoint restored all `25/25`; the second frozen-checkpoint
      re-review then found the three fail-open classes above (four faithful new regressions,
      because non-public local resolution needs qualified-owner and alias cases). Red
      baseline from `scripts/sbtc "core/testOnly
      scalascript.artifact.PreBodyApiDescriptorProducerTest"` is exactly `25/29`:
      all prior regressions pass and each new faithful repro fails by returning `Right`.
    - Frozen local checkpoint after the second corrections: implementation `abf6d909a` on
      `origin/main@b1e93d0f9`; focused producer 38/38, descriptor 27/27, core 1084/1084,
      interop 36/36, `ir/test` success, and affected `modules*,import-dir*` conformance 2/2.
      Keep all three BUGS entries `open` and this slice unchecked until a fresh independent
      read-only review returns APPROVE; do not push/release this checkpoint beforehand.
    - Fresh rereview of frozen `8a8886557`/rebased `28535c87d`: REJECT, no P0 and three new P1
      classes above. The 38/38 suite did not cover package-wrapper header forgery, documentless or
      dual retained-source evidence, or the `Byte` side of `Array[Byte]` shadowing. Keep all six
      descriptor BUGS entries `open`; update the spec before implementation, then add faithful red
      vectors and preserve every earlier regression.
    - Third-review red baseline (`387a10384`): exact focused result 39/46, with seven failures
      returning `Right` — 1 non-plain wrapper, 2 code-block/document source-evidence, and 4
      Array/Byte binder-or-local shadowing tests. All prior 38 regressions plus the new unshadowed
      built-in `Array[Byte]` positive pass.
    - Third-correction local checkpoint: implementation `72e6a2897`, spec verification
      `7fde36cf9`, rebased on `origin/main@790366a9d`. Focused producer 46/46,
      descriptor 27/27, core 1092/1092, interop 36/36, `ir/test` success, artifact ABI
      73/73, and affected `modules*,import-dir*` conformance 2/2 are green. All nine
      Slice B BUGS entries remain `open`, and Slice B remains unchecked until a fresh
      independent read-only review approves the clean checkpoint; do not push/release first.
    - Fresh review of exact frozen `4cd2a4aaa` (rebased as `05e498a72`): REJECT, no
      P0 and three P1 fail-open classes. The reviewer confirmed the previous wrapper,
      mandatory CodeBlock-source, and Array/Byte binder/local fixes, but found that
      imports still bypass every bare builtin resolution, raw effect evidence can
      disagree across dual carriers after preprocessing, and derives/early template
      headers are absent from both correspondence and nominal losslessness gates.
    - Fourth-correction resume-cold plan (spec-update before tests/code):
      1. collect source-ordered import scope in `projectStat`; before every bare builtin
         mapping, fail closed for direct, rename-to-name, or wildcard imports unless an
         exclusion/rename-away proves the name unavailable. Cover both Array/Byte,
         representative Int/List, qualified positives, platform isolation, stable paths;
      2. compare a raw semantic effect-evidence witness for CodeBlock and Document
         carriers before selecting effect source: effect/object kind, name/order,
         multiplicity, and unsupported generic/parent shape matter; line offsets do not.
         Because package wrapping otherwise erases an empty effect's origin, retain only
         reserved `private type` parser sentinels (never runtime values/API members), reject
         a packaged ordinary-object sentinel collision, and prove EffectAnalysis/backends remain
         unchanged. Cover empty effect/object, multi/ordinary, stale dual carriers,
         documentless safety, focused parser/analysis tests, and effect conformance;
      3. include `Template.derives` and `earlyClause` in exact header witnesses and
         reject them on real public nominal class/trait/enum/object declarations until
         representable. Cover direct parseable forms plus stale carrier mismatches.
      Record the exact red baseline, preserve all prior vectors, run focused + descriptor/
      core/interop/IR/ABI + `modules*,import-dir*` conformance, spec-verify/bookkeeping,
      rebase current origin at a clean checkpoint, then request a new independent review.
      Keep all 12 Slice B BUGS `open`; never push/release before APPROVE.
    - Fourth-review red baseline (`f08ab9943`): focused producer is exactly 50/60,
      with 10 failures — 5 import-resolution, 2 raw effect-evidence, and 3 derives/
      early correspondence/losslessness. All previous 46 regressions plus new
      rename-away/unimport, qualified-name, plain/multi, line-offset, unsupported-
      effect-shape, and direct early-clause positives are green. Implement all three
      spec gaps before changing these expectations.
    - Fourth-correction local checkpoint: implementation `43d41e88d`, spec
      verification `38597ae85`, rebased on `origin/main@f63714680`. Focused
      producer/parser/effect tests pass 75/75 (producer 63/63), descriptor 27/27,
      core 1111/1111, interop 36/36, `ir/test` succeeds, artifact ABI 73/73, and
      affected conformance passes 2/2 modules/import-dir plus 9/9 effect cases.
      All 12 Slice B BUGS remain `open`, every Slice B task marker remains unchecked,
      and the claim stays active until a fresh independent read-only review returns
      APPROVE; do not push/release this checkpoint first.
    - Fifth-review of exact frozen `0cb46c3cd`: REJECT, no P0, five P1 families,
      and no standalone P2. The reviewer confirmed the fourth correction's
      derives/early, direct-import ordering, raw effect/object comparison, and
      architecture: the canonical model remains only in `v2/interop/descriptor`,
      with the v1/lang core compatibility producer depending one-way on it.
    - Fifth-correction resume-cold plan (recorded before rebasing or coding):
      1. include exact ordered `Import` semantics in declaration correspondence.
         Preserve importer plus direct/rename/wildcard/unimport/given selector shape
         and lexical owner/order, so stored AST, CodeBlock, and optional Document
         cannot disagree on an import that changes projection;
      2. replace partial `ImportScope` handling with one source-ordered lexical
         identity resolver for bare names, selected types, importer qualifiers,
         chained aliases, and callback alias classification. Detect cycles,
         conflicts, wildcards, exclusions, platform roots, and private/local
         identities after expansion. Faithful repros: `java.{lang as jl}` then
         `jl.String`; chained `jl.{Integer as Int}`; imported local function alias
         must receive conservative callback policy;
      3. validate effect origin sentinels by exact count and canonical private-type
         shape. Reject user duplicates or malformed `__effectDecl__` and
         `__effectUnsupportedShape__` markers in Document-backed and documentless
         packaged modules before filtering them from the public/runtime surface;
      4. make raw effect evidence declaration-scope-aware and reuse that single
         validated model in `bindEffectHeaders`. A local effect written only inside
         an exported method body is body evidence and must not change descriptor
         success/bytes/hash; retain genuine top-level effect binding;
      5. recursively inventory local types, aliases, and effects under class, trait,
         enum, and object owners with inherited effective visibility and owner
         representability. `private class Hidden { type T }` followed by public
         `Hidden.T` must reject before external-name fallback.
      Before implementation, update/commit the normative feature spec; then add
      faithful regressions and record the exact red baseline. Preserve all previous
      vectors and run focused producer/parser/effect tests, descriptor 27/27, full
      core 1111+, interop 36/36, IR, ABI 73/73, modules/import-dir conformance, and
      forced effect conformance. Keep all 17 Slice B BUGS `open`, every Slice B task
      marker unchecked, and never push/release before a new independent APPROVE.
    - Fifth-review red baseline (`c1f57d99f`): focused producer is exactly 63/70.
      Seven new tests fail — one ordered-import witness, three unified-resolver
      cases (selected platform alias, chained platform alias, imported callback),
      one duplicate sentinel, one body-local effect, and one nested nominal-owner
      inventory — while all previous 63 producer regressions remain green.
    - Resolver/inventory audit addendum (must be covered before the next freeze):
      preserve the active source-ordered `ImportScope` on every transparent alias
      declaration; use the same leading-qualifier expansion for selected local
      callbacks and imported local effect rows; fail closed on wildcard-selected
      prefixes and imported private identities; include abstract `Decl.Type` in the
      recursive identity inventory; reject public class receiver-owned identities
      and nested objects below a nonrepresentable nominal owner; retain a positive
      public-object namespace control. A known local owner with an unknown member
      must not silently fall back to an external identity.
    - Audit-hardening regression checkpoint (`e7069ad59`) after the isolated import-
      witness fix: focused producer is exactly `66/78`. Twelve tests remain red:
      the six original resolver/effect/inventory failures plus alias import-snapshot,
      selected local callback prefix, selected imported effect, wildcard selected
      prefix, receiver-owned/abstract nominal identities, and known-owner/unknown-
      member fallback. The imported-private identity and public-object namespace
      controls are already green and must stay green.
    - Effect-hardening checkpoint (`2bdb4114e`) after resolver/inventory repair:
      focused producer is exactly `77/82`. Five tests remain red: duplicate canonical
      declaration marker, malformed/non-type reserved collision, unsupported marker
      on an ordinary object, body-local-only header, and a body-local same-name header
      preceding a genuine top-level effect. A plain effect carrying an unexpected
      unsupported-shape marker already fails and remains a control.
    - Fifth-correction post-rebase checkpoint on `origin/main@6603e6c29`:
      implementation commits `c55ac86e9` (exact import witness), `f4d4c01ec`
      (unified resolver + recursive inventory), and `ff0e2580b` (exact sentinels +
      single declaration-scope effect binding). Focused producer/parser/effect
      passes 94/94 (82/8/4); descriptor
      27/27, core 1132/1132, interop 36/36, IR success, ABI 73/73; modules/import-
      dir conformance 2/2 and forced non-memoized effect conformance 9/9. Next:
      commit this open-ledger/spec verification, freeze the resulting exact clean
      head, then request a fresh independent read-only review. Do not push/release
      and do not close any of the 17 Slice B BUGS beforehand.
    - Done when the focused regressions and affected core/interop/conformance gates pass and a
      fresh independent read-only review returns APPROVE with no P1/P2 blocker.
  - [ ] **C — post-body summaries:** extract managed/foreign/tail edges, save sites, frame schemas,
    and barriers; cross-check callback `ManagedControl` claims.
  - [ ] **D — post-link manifests and consumers:** populate target entrypoints and exact program,
    artifact, runtime, control, and dependency-profile digests; switch facades/admission/runners to
    v3 with explicit legacy fallback only for ordinary non-managed interop.
- [x] **control-semantic-vectors** — add target-neutral vectors for nested/fresh prompts, nearest-match
  `reset`, zero/one/many resume, deep handler reinstall, residual effects, mutation (control copied;
  heap shared), stack safety, cancellation, managed-boundary negatives, and exact diagnostics. Run the
  same vectors on explicit API, v2 VM/direct ASM, generated JVM, JS, Rust, WASM, and Swift as those
  portable lanes become available, plus the managed Scala direct-style lane. Landed 2026-07-15:
  one validated 26-vector/9-lane catalog; portable VM and ASM pass 13/13 exact process vectors each,
  explicit Scala passes 17 semantic vectors plus coverage (18/18), the whole control leaf is 57/57,
  and nine malformed/omitted/lane-substitution regressions are rejected. Future direct/generated host lanes,
  durable vectors 10--17, and cancellation 26 remain visibly phased rather than counted green.

### ScalaScript lowering and Scala/JVM host profile

Planning, descriptors, reference API, and semantic vectors may proceed now. Changes to the v2
frontend/lowering, canonical CoreIR codec/loader, or any byte-affecting kernel contract begin only
after the active UniML P6.5 literal-fixed-point sequence `F1 → F2/F3 → L1 → X1` is green and frozen;
every later compiler/kernel change re-runs the literal fixed point.

- [ ] **ssc-shift-reset-lowering** — add compiler-known `std.control` typing and outer lowering of
  direct `reset`/`shift` regions to the existing `Pure | Op(..., reusable-k)` protocol, either through
  `Ctor`/`Lam` or the equivalent stable generic `effect.*` Prim ABI. Direct syntax needs a compiler-known
  capture boundary because current compatibility handlers see only inline bodies; this is not a
  semantic restriction on first-class explicit `Eff` values or the explicit reset fold.
- [ ] **scala3-control-macros** — publish `_3` inline macros for local direct-style `reset` regions;
  lower to the same explicit ABI and reject a `shift` crossing an untransformed callback/resource
  frame. Preserve exact source positions and make explicit-vs-macro differential tests mandatory.
  Resume-cold M1 contract: [`specs/scala3-control-macros.md`](specs/scala3-control-macros.md).
  Keep the existing `scala3ControlApi` / `scalascript-control_3` artifact; add
  `scalascript.control.direct.{Scope,reset,shift}`, transform only bounded lexical ANF sequences into
  explicit `reset`/`shift`/`flatMap`/`pure`, and fail closed with `UNMANAGED_CAPTURE`,
  `CAPTURE_BARRIER`, or `DIRECT_STYLE_UNSUPPORTED`. Done-when the `scala-direct` adapter executes the
  applicable shared vectors, the full control leaf/package/POM gates pass, exported source signatures expose
  no quotes/runtime type, and the compiler-required macro implementation remains private at Scala source
  level. M1 nested resets lower only their own matching markers; a marker targeting
  an outer scope across a nested reset is rejected until the compiler-plugin tier can preserve the
  residual outer control row explicitly. Accordingly the direct lane claims vector 18/23 `shift-reset`;
  vector 22 also requires `prompt-isolation` and remains explicit/plugin evidence. Current pre-review
  checkpoint: the bounded lexical transform emits only the existing explicit API; seventeen direct
  semantic tests, twenty-two exact diagnostic tests, and source-access guards pass inside the 101/101
  control leaf. The validated
  `scala-direct` lane runs vectors 18/23 plus coverage (3/3) with explicit differential oracles; package,
  POM, packaged-JAR runnable example, and five-case affected conformance gates are green. The tracked
  `scala-direct-deferred-nonlocal-return` gap now rejects external returns before they move under
  `Eff.defer` while preserving a return local to a nested method. Independent review of `fa992fd92`
  rejected three additional P1 families; complete these in order:
  - [x] Clone/rebind strict prefix `val`/`var`/`given` symbols across each capture, including
    destructuring synthetic binds, values used by `ShiftBody`, and values between sequential shifts;
    prove a local multi-shot mutable cell is shared. Fail closed for crossing local method/class/type
    and lazy declarations until M1 models their ownership/state explicitly.
  - [x] Keep a marker in a lazy initializer behind exact `CAPTURE_BARRIER`, and reject
    binding/provenance-bearing inline wrappers with stable `DIRECT_STYLE_UNSUPPORTED` at the marker
    before any wrapper prompt or body side effect can run.
  - [ ] **Fresh-rereview remediation (`ec4eb279e`, four P1 families; resume cold):**
    update `specs/scala3-control-macros.md` first, then change only the existing Scala host leaf.
    Preserve the already-green 14 semantic + 16 diagnostic regressions and complete these in order:
    - [x] Rebind dependent/singleton references in cloned prefix `ValDef.tpt.tpe` as well as term
      trees, supporting the common local `freshPrompt` / `Prompt[scope.Key, R]` flow across capture.
      Audit `var`, parameterless `given`, and destructuring dependencies; any type shape that cannot
      be rebound soundly must fail closed with stable `DIRECT_STYLE_UNSUPPORTED`, never raw E007 or
      quote-owner output.
    - [x] Inspect the otherwise-opaque rank-2 `ShiftBody` for a surviving exact `direct.shift` and
      reject that nested marker at its call site. Ordinary explicit `Eff`/`shift` code and a nested
      managed `direct.reset` remain legal and need positive regressions.
    - [x] Report a transparent-inline expansion at the nearest provenance-bearing `Inlined.call`
      wrapper invocation, with exact message/line/column; keep the separately compiled unexpanded-
      inline application path unchanged and covered.
    - [x] Reject every `scala.util.boundary.break` in M1 before `Eff.defer`/continuation movement,
      with stable direct diagnostics in pure-prefix and captured-suffix shapes. Returns local to a
      nested method remain accepted; M1 conservatively treats all boundary breaks as outside scope.
    - [x] Narrow the nested-managed-`direct.reset` exception in the enclosing `ShiftBody` audit:
      inspect its eager prompt/other call arguments and reject an exact outer-scope `direct.shift`
      there, but leave the nested reset's managed body/inline expansion to its own transform.
      Add the exact negative regression and retain positive regressions for an ordinary nested
      managed reset body and explicit `scalascript.control.shift`; update the spec before code.
      Landed on the feature branch in `fdde23d93`; clean focused suites pass 39/39 and the rebuilt
      packaged consumer reports the exact direct diagnostic instead of raw owner output.
    - [x] Run clean focused semantic/diagnostic tests, then
      `scripts/sbtc "scala3ControlApi/test;scala3ControlApi/packageBin;scala3ControlApi/makePom"`,
      packaged-JAR consumer/example, catalog validation 26/9, negatives 9/9, direct lane 3/3,
      `tests/conformance/run.sh --only 'effect*,effects*'`, Markdown checks, and `git diff --check`.
      Update spec checkboxes/results, leave the five BUGS entries open pending approval/landing,
      refresh SPRINT/CHANGELOG counts in separate docs/bookkeeping commits, rebase only at a clean
      checkpoint, repeat critical gates, and freeze for a new independent review. Do not push/release.
      Final clean checkpoint is based on `origin/main` `76f9706cf`: focused suites pass 39/39,
      the full leaf/package/POM pass 101/101, packaged positive and exact-negative consumers are
      green, catalog validation is 26 vectors/9 lanes, negatives are 9/9, direct is 3/3, and
      affected conformance is 5/5. Markdown and diff checks are the final freeze gate.
  - [x] Fresh independent read-only rereview of frozen checkpoint `708dec2f1`. Expanded direct semantics
    (17/17), diagnostics after clean compile (22/22), full leaf/package/POM (101/101), catalog validation
    (26/9), negatives (9/9), direct lane (3/3), affected conformance (5/5), and packaged-JAR compile/run
    were green, but review rejected the checkpoint with three P1 owner-safety gaps and P2 regression/
    bookkeeping gaps. Do not mark M1 done or push.
  - [ ] **Post-`708dec2f1` owner remediation (three P1 + P2; resume cold):** update and commit
    `specs/scala3-control-macros.md` before code, then change only
    `v2/host/scala/control` (`scala3ControlApi`, `scalascript-control_3`). Preserve the explicit ABI;
    do not touch v1, CoreIR, UniML, backends, CLI, seed, or self-hosting. Complete in order:
    - [x] Rebind the captured result type `A` through the active prefix replacements before opening
      it with `asType` or typing the moved rank-2 body. Support both `owner.type` and
      `Prompt[inner.Key, Int]` captured values, with packaged direct-vs-explicit results of `42`;
      otherwise fail closed at the marker, never raw E007/owner² output.
    - [x] Audit every owner-bearing type in moved prefix RHS and captured-suffix terms, including
      nested lambda/result symbols. Support `val f: () => owner.type = () => owner` across capture
      in both prefix and suffix declaration shapes, or reject an unrepresentable graph before code
      construction with stable `DIRECT_STYLE_UNSUPPORTED`; add packaged explicit differentials.
    - [x] Make supported crossing contextual values two-phase: allocate all fresh `ValDef` symbols
      before moving RHS trees, then move with the complete replacement map. Preserve compiler
      `Given`/`Lazy` flags and accept unused forward/mutual parameterless givens whose explicit
      equivalent prints `42`; fail closed only for an actually unsupported dependent type cycle.
    - [x] Commit exact diagnostic regressions for `scala.util.boundary.break` through an imported
      method alias, explicit label application, module alias, and transparent-inline provenance.
      Correct over-broad dependent-owner completion wording in the feature spec and CHANGELOG.
    - [x] Run clean focused semantics/diagnostics, full
      `scala3ControlApi/test;scala3ControlApi/packageBin;scala3ControlApi/makePom`, packaged positive
      and negative consumers, catalog validation 26/9, negatives 9/9, direct lane 3/3, affected
      conformance 5/5, Markdown, and diff checks. Update spec verification/results and BUGS/SPRINT/
      CHANGELOG in separate commits, rebase only while clean, repeat critical gates, freeze an exact
      clean head, and require another independent review. Do not push or release the claim. Gotcha:
      after changing a macro implementation, incremental `typeCheckErrors` test compilation can retain
      the prior macro class and report an obsolete primary column; use `scala3ControlApi/clean` before
      freezing exact diagnostic evidence.
      Owner remediation is implemented in `a8f321d5c` on `origin/main` base `f4e860ed7`:
      clean focused suites pass 47/47 (21 semantics, 26 diagnostics), the full leaf/package/POM pass
      109/109, packaged positive consumers print the expected general output and eight differential
      `42` values, and the packaged negative reports stable `DIRECT_STYLE_UNSUPPORTED`. The POM has
      only the Scala library in production scope; catalog validation is 26 vectors/9 lanes, validator
      negatives are 9/9, `scala-direct` is 3/3, and affected conformance is 5/5. Markdown/diff checks
      are the final freeze gate. Keep this remediation item and M1 itself pending until a fresh
      independent review approves; do not push or release the claim.
  - [ ] **Post-`408f23c11` strict-polymorphic-value remediation (P1; resume cold):** fresh
    independent review rejected the `f4e860ed7..408f23c11` candidate. A real Scala CLI 3.8.3
    consumer compiled only against the packaged control JAR declares
    `val identity: [A] => A => A = [A] => (a: A) => a` before capture and calls
    `identity[Int](2)` in the suffix. Direct source fails at that call with raw typer output
    `undefined: identity.<none> ... TermRef(... val <none>)` twice; the explicit equivalent runs
    and prints `42`. Preserve the accepted strict-value grammar or reject a genuinely unsupported
    graph with stable `DIRECT_STYLE_UNSUPPORTED`; never leak a compiler crash. Complete in order:
    - [x] Update and commit `specs/scala3-control-macros.md` before code with structural-select
      resolution and closed `MethodType`/`PolyType`/`ParamRef` binder invariants.
    - [x] Add the faithful red direct-vs-explicit regression plus adjacent monomorphic structural
      apply, prefix/suffix polymorphic calls, explicit `.apply[Int]`, owner-dependent nested generic
      polyfunctions, and `ParamRef`-only result/bounds coverage.
    - [x] Resolve structural selections through the transformed qualifier/member graph. Preserve a
      self-contained binder graph atomically, rebind only graphs that depend on replaced owners, and
      fail closed for an owner-dependent nested polyfunction that Quotes cannot represent soundly.
    - [x] Run clean focused semantics/diagnostics, full leaf/package/POM, packaged positive and
      negative consumers, catalog 26/9 + negatives 9/9 + direct 3/3, affected conformance 5/5,
      Markdown, and diff checks. Rebase only from a clean checkpoint, repeat critical gates, freeze
      an exact base/head for a new independent reviewer, and do not push or release the claim.
      Implementation `b6d2cd262` is green on `origin/main` base `6603e6c29`: clean focused suites
      pass 51/51 (24 semantics and 27 diagnostics), the full leaf/package/POM passes 113/113, and
      the packaged Scala CLI 3.8.3 positive consumer prints fourteen differential `42` values. The
      packaged negative reports stable `DIRECT_STYLE_UNSUPPORTED`; the POM has only the Scala
      library in production scope. Catalog validation is 26 vectors/9 lanes, validator negatives
      are 9/9, `scala-direct` is 3/3, and affected conformance is 5/5. Keep this remediation item and
      M1 pending until a fresh independent review approves; do not push or release the claim.
- [ ] **scala3-control-plugin** — publish a `CrossVersion.full` compiler plugin for cross-method CPS,
  managed callback propagation, effect metadata, and generated ABI entrypoints. Precompiled Scala/Java
  code remains callable but is a deterministic control-capture barrier while active on the stack.

### Mandatory host and runner profiles (delivery order by measured readiness)

- [ ] **javascript-typescript-control-host-runner** — deliver the ESM/npm + `.d.ts`
  typed bidirectional value/call bridge, explicit `Eff`, managed source transform,
  callback/event-loop policies, static-SCC dispatcher, init-free exact bundle runner,
  and hardened dynamic portable runner from
  `specs/javascript-typescript-bidirectional-control.md`. Promise/async/generators
  remain adapters or barriers; I64 uses `bigint`.
  - [x] **Explicit local control slice + two review rounds (2026-07-15)** — the
    compiler-independent `@scalascript/control` leaf is reachable on `origin/main`
    as landing `cf8f96200`. It rejects inferred/explicit union owners, publishes a
    canonical non-broken contract link, and includes the owner, prompt,
    WeakMap/constructor-authority, and Apache-license hardening. Package tests pass
    31/31 including all 17 applicable catalog vectors; TypeScript,
    1,000,000-bind/state and 100,000-operation stress, exact five-file pack
    (11,059/42,353 bytes), markdown/node checks, and affected conformance 5/5 are
    green. Independent rereview confirmed both second-review bugs closed; their
    `BUGS.md` entries are `done`.
  - [ ] **Remaining host/runner profile** — generated facades and typed value/call
    bridges, managed source transformation and callback policies, mixed-language
    SCC dispatch, exact and portable runners, and shared lane wiring.
    - [ ] **T1 — closed synchronous lexical direct transform (`javascript-typescript-control-direct`).**
      Specify and ship the zero-runtime-side-effect package `v2/host/js/control-direct`
      (`@scalascript/control-direct`, transformer subpath `/transform`, CLI
      `ssc-control-tsc`) that lowers the bounded grammar
      `direct.reset(prompt, () => { ... const/let x = direct.shift(prompt, body) ... })`
      into the existing explicit `@scalascript/control` `Eff/reset/shift/flatMap` ABI.
      This slice is deliberately local and closed (`Fx = never`): preserve exact-import
      ownership, lexical shadowing, nearest resets, true shift, prompt isolation,
      prefix-once/suffix-per-resume, sequential markers, and shared local mutable heap;
      reject all asynchronous/generator and capture-barrier shapes with stable
      `JS_DIRECT_*` codes and source spans. Use the TypeScript compiler API as the
      established syntax/binding authority when it is already available, keep the
      published package free of production dependencies, and leave descriptors,
      CoreIR/frontends, runners, lane registration, and the rest of the host profile
      open. Resume from `specs/javascript-typescript-control-direct.md`; done when its
      behavior checklist is verified, catalog 18/22/23/24 differentials and negative
      syntax/import/source-map/type-diagnostic/package tests are green, the existing
      explicit package remains 31/31 plus typecheck, exact npm packs are audited, and
      affected `effect*,effects*` conformance passes. Keep code, docs, spec verification,
      and bookkeeping in separate commits and require an independent read-only review
      before integration.
      - [x] **REJECT repair: forward/own lexical capture.** Frozen review snapshot
        `f6fa34fac` emits a shift body outside the `.flatMap` frame that owns later
        declarations, so a saved nested closure reading `const later = 42` throws
        `ReferenceError`. Specify fail-closed ownership for every symbol declared by
        the marker itself or its continuation suffix, detect uses recursively through
        nested closures with checker symbol identity, and add forward/own/shadowing plus
        declaration-initializer order regressions. Track in
        `BUGS.md#js-control-direct-forward-lexical-capture`. Implemented in cumulative
        repair `c19d42401`; marker-layer generalization is in `4c6b8e2a9`.
      - [x] **Pre-rereview repair: prefix TDZ/outer-binding escape.** Cumulative
        repair `c19d42401` checked only the shift body: a pure prefix read of a later block
        binding is left outside the generated continuation and can resolve to an
        outer name instead of throwing from the original TDZ (`99`/`141` reproduced,
        no direct diagnostic). For each marker, scan its layer's prefix statements
        plus shift body against own/later declaration symbols, ignoring type-only and
        genuinely shadowed references; cover real `.js` with `checkJs: false` and
        file-atomic ignored-diagnostic emit. Track in
        `BUGS.md#js-control-direct-prefix-tdz-binding-escape`. Implemented and covered
        by real-JavaScript plus type-only regressions in `4c6b8e2a9`.
      - [x] **REJECT repair: preserve JavaScript marker declarations.** Lower each
        accepted `const`/`let x = direct.shift(...)` to a collision-safe fresh resume
        parameter followed by the original declaration kind initialized from that
        parameter; do not use `x` itself as the callback parameter. Verify real `.js`
        input under `allowJs: true, checkJs: false`, const assignment behavior, let
        mutation, a fresh-name collision, and source maps. Track in
        `BUGS.md#js-control-direct-js-marker-binding-semantics`. Implemented in
        `c19d42401`.
      - [x] **REJECT repair: file-wide intrinsic direct-eval barrier.** In every file
        selected for transformation, reject direct `eval(...)` even at top level or in
        nested closures and even through parentheses/`as`/non-null/type-assertion
        wrappers; emit no JavaScript for that file. Normatively allow indirect eval and
        `Function` as global-only JavaScript operations, then test each accepted and
        rejected form with stable spans. Track in
        `BUGS.md#js-control-direct-eval-capture-unsound`. Implemented in
        `c19d42401`, with the selected-file closure completed by `4c6b8e2a9`.
      - [x] **Pre-rereview repair: import-only direct eval.** Repair `c19d42401`
        removes an unused named marker import even with no marker call, but gates eval
        scanning on `filesWithMarkerCalls`; `eval("typeof direct")` therefore observes
        a changed lexical environment. Gate intrinsic direct eval on every candidate
        file rewrite (including import-only erasure), retain eval-free unused-import
        removal, and prove diagnostic + file-atomic ignored-diagnostic emit. Track in
        `BUGS.md#js-control-direct-import-only-eval-erasure`. Implemented and covered
        by an executing import-only regression in `4c6b8e2a9`.
      - [x] **REJECT repair: real installed npm bin.** Replace the raw
        `import.meta.url === pathToFileURL(argv[1])` guard with deterministic realpath
        entry detection, including missing/unreadable argv handling. Build a tarball,
        install it in a fresh consumer, invoke exactly
        `node_modules/.bin/ssc-control-tsc`, and prove successful emit plus non-zero
        invalid-option failure. Track in `BUGS.md#js-control-direct-cli-symlink-noop`.
        Implemented in `c19d42401`.
      - [x] **REJECT repair: erase the build-time marker import safely.** Require every
        value use of each exact named `direct` binding to be a successfully transformed
        marker call; diagnose survivors. Remove only completed marker specifiers and an
        import declaration only when it becomes empty, preserving unrelated bindings
        and imports. Run emitted production JavaScript with control runtime installed
        but no control-direct package. Track in
        `BUGS.md#js-control-direct-marker-import-survives-emit`. Implemented in
        `c19d42401`.
      - [x] **REJECT repair: consumer-owned TypeScript resolution.** Resolve the CLI
        compiler via Node `createRequire` from the explicit project/config directory or
        cwd, never from the extracted tool store and never from a global fallback. The
        packed installed-bin fixture keeps TypeScript only in consumer `node_modules`;
        a twin fixture without it must fail actionably. Track in
        `BUGS.md#js-control-direct-consumer-typescript-resolution`. Implemented in
        `c19d42401`.
      - [x] **REJECT repair: transparent marker wrappers.** Recursively unwrap only
        parentheses, `as`, non-null, and type assertions for exact checker-symbol
        ownership, covering `(direct).reset`, `direct!.reset`, and
        `(direct as typeof direct).reset` plus corresponding shift/negative forms.
        Emitted JavaScript must contain no owned marker call. Track in
        `BUGS.md#js-control-direct-wrapped-marker-receiver-missed`. Implemented in
        `c19d42401`.
      - [x] **REJECT repair: supported TypeScript API gate.** Pin the accepted compiler
        API line in the feature contract, enforce it before programmatic/CLI transform,
        and test both TypeScript 5.9.x acceptance and deterministic rejection outside
        that line without adding a bundled/production compiler. Track in
        `BUGS.md#js-control-direct-typescript-version-ungated`. Implemented in
        `c19d42401`.
      - [x] **Rereview repair: shorthand value-symbol capture.** Frozen reviewed HEAD
        `c4377fabb` asks `checker.getSymbolAtLocation` for an identifier inside a
        `ShorthandPropertyAssignment`, which returns the property symbol rather than
        the referenced lexical value. A shift body such as `({ later }).later` can
        therefore capture a suffix `const later = 42` and fail at runtime. Specify and
        centralize runtime-value symbol resolution with
        `checker.getShorthandAssignmentValueSymbol`, preserve ordinary/shadowed/type-
        only identities, and cover shorthand plus assignment-initializer syntax where
        TypeScript exposes it. Track in
        `BUGS.md#js-control-direct-shorthand-value-symbol-capture`. Implemented in
        `ec95c4c65`; real-JavaScript property and assignment-initializer regressions
        prove one stable capture diagnostic and unchanged ignored-diagnostic emit.
      - [x] **Rereview repair: surviving marker shorthand/local exports.** Runtime
        shorthand `{ direct }` and local exports `export { direct }` or
        `export { direct as alias }` currently evade owned-marker value scanning; the
        import may then be erased while emitted code keeps an unbound value/export.
        Reuse one central runtime-value symbol resolver, including
        `getExportSpecifierLocalTargetSymbol` for local export aliases, diagnose each
        surviving owned value once as `JS_DIRECT_UNSUPPORTED`, and cancel every rewrite
        in the file. Track in
        `BUGS.md#js-control-direct-marker-shorthand-export-survivor`. Implemented in
        `ec95c4c65`; shorthand/assignment and local/source export aliases each produce
        exactly one file-atomic diagnostic.
      - [x] **Rereview repair: erased type-only exports.** The fail-closed re-export
        scan currently rejects valid erased forms: local
        `export type { direct as Marker }`, direct
        `export type { direct } from "@scalascript/control-direct"`, and inline
        `export { type direct } from ...`. Specify these as type-only/non-runtime uses,
        preserve their normal TypeScript erasure without a direct diagnostic, and add
        positive tests alongside runtime export aliases and shadowing. Track in
        `BUGS.md#js-control-direct-type-only-export-false-positive`. Implemented in
        `ec95c4c65`; five local/source declaration/specifier spellings erase normally,
        and a shadowed local runtime export remains ordinary.
      - [x] **Fresh-rereview P1: mixed type-only marker imports must stay valid JavaScript.**
        Exact reviewed HEAD `71ae452ea5` (current rebased equivalent `82aee139a`)
        rewrites a mixed import such as
        `import { direct, type DirectMarkerContractError as ErrorType } from
        "@scalascript/control-direct"` after TypeScript has performed its normal
        type erasure. Removing `direct` can leave `import { type ErrorType }`, which
        the packed CLI reports as success but `node --check` rejects. Define separate
        JavaScript/declaration channels: JavaScript rewriting discards type-only
        specifiers while retaining ordinary runtime values; declaration emit observes
        the original import. Prove both `verbatimModuleSyntax` modes through the real
        packed CLI, JavaScript syntax validation, and emitted `.d.ts`. Track in
        `BUGS.md#js-control-direct-mixed-type-import-invalid-js`. Implemented in
        `fabad7d84`; both verbatim modes pass packed-CLI syntax, declaration, and
        production-without-marker checks.
      - [x] **Fresh-rereview P1: erased source exports must not retain a runtime module
        link.** With `verbatimModuleSyntax: true`, specifier-level
        `export { type direct as Marker } from "@scalascript/control-direct"` can emit
        `export {} from "@scalascript/control-direct"`; production then resolves the
        dev-only package and fails with `ERR_MODULE_NOT_FOUND`. Select exact-module
        type-only source exports for JavaScript normalization, remove an empty linked
        export, preserve mixed ordinary runtime specifiers, and leave declaration emit
        unchanged. Cover aliases, mixed lists, both verbatim modes, packed CLI syntax,
        production execution without the marker package, and `.d.ts`. Track in
        `BUGS.md#js-control-direct-type-export-runtime-link`. Implemented in
        `fabad7d84`; pure aliases lose the linked JavaScript export, mixed runtime
        specifiers remain, and declaration emit retains both.
      - [x] **Fresh-rereview P1: external `import = require` must follow marker import
        ownership.** Under CommonJS/Node10,
        `import markers = require("@scalascript/control-direct")` bypasses the existing
        default/namespace-import fail-closed scan and can survive as a runtime require.
        Detect exact external-module `ImportEqualsDeclaration` syntax, reject every
        runtime form once with stable `JS_DIRECT_UNSUPPORTED`, and cancel the complete
        JavaScript rewrite. Normatively allow `import type markers = require(...)`
        because it is erased, while declaration emit remains TypeScript-owned. Cover
        used and unused runtime forms, the erased type-only form, ignored-diagnostic
        file atomicity, and the real packed CLI in CommonJS/Node10. Track in
        `BUGS.md#js-control-direct-import-equals-bypass`. Implemented in `fabad7d84`;
        runtime used/unused forms receive one stable diagnostic while type-only forms
        emit no require edge and retain `.d.ts` under both verbatim modes.
      - [x] **Fresh-rereview P1: the exact tarball must not publish repository-local
        dependency edges.** Exact reviewed range `445f7faf7..d66ed988df` includes
        `devDependencies["@scalascript/control"] = "file:../control"` inside the
        eight-file tarball manifest. Extracting that tarball outside the repository
        and running ordinary `npm install --ignore-scripts` creates a dangling local
        control link (or otherwise depends on the absent sibling); importing it fails
        with `ERR_MODULE_NOT_FOUND`. Specify a self-contained manifest: keep the
        qualified TypeScript tool pin, forbid `file:`, `link:`, `workspace:`, absolute,
        and relative-local dependency specifications in every dependency/tooling map,
        and retain the no production/peer dependency decision. Add a regression that
        reads `package/package.json` from the exact tarball, then extracts and installs
        it at a clean boundary with no sibling. Move local control resolution to test
        fixtures/TypeScript paths or test-created symlinks and regenerate the lock
        mechanically. Track in
        `BUGS.md#js-control-direct-packed-local-dev-dependency`. Implemented in
        `9baf6d2bf`: the manifest/lock contain only TypeScript 5.9.3, local
        compiler/runtime tests use explicit paths/symlinks, and the 39th package test
        exact-packs, extracts, ordinary-installs, and self-imports without a sibling.
      - [ ] **Repair-cycle closure.** After spec-first and code commits, update package
        README/project docs, run direct package tests+typecheck+node checks+exact pack,
        existing explicit control 31/31+typecheck, catalog positive/negative validators,
        and `tests/conformance/run.sh --only 'effect*,effects*'`. Then fix stale explicit
        control bookkeeping to reachable landing `cf8f96200`, mark its two confirmed
        review bugs `done`, freeze a clean HEAD, and obtain a fresh independent APPROVE
        before any push or claim release. All local gates and spec verification are
        green at `abfcb03e6`: direct 35/35 and exact eight-file pack
        (14,909/56,527 bytes), explicit control 31/31 and exact five-file pack,
        catalog 26/9, negative validator 9/9, and conformance 5/5. The next fresh
        review rejected exact `71ae452ea5` on three emit-channel P1s. Cumulative
        repair `fabad7d84` on current base `6603e6c29` is fully green: direct 38/38 plus
        exact eight-file pack (15,423/59,187 bytes), explicit control 31/31 plus
        exact five-file pack (11,059/42,353 bytes), catalog 26/9, negative validator
        9/9, and conformance 5/5. Only fresh independent APPROVE and subsequent
        landing remained. The following fresh review rejected exact range
        `445f7faf7..d66ed988df` on the packed local dev-dependency P1. Fourth repair
        `9baf6d2bf` on current base `6603e6c29` is fully green: direct 39/39 plus
        exact eight-file pack (15,618/59,636 bytes), explicit control 31/31 plus
        exact five-file pack (11,059/42,353 bytes), catalog 26/9, negative validator
        9/9, and conformance 5/5. Only fresh independent APPROVE and subsequent
        landing remain.
- [ ] **rust-control-host-runner** — deliver the Cargo host facade, stable-Rust
  explicit `Eff`, proc-macro/generated state machines, ownership/borrow/RAII barrier
  checks, typed mixed-SCC dispatcher, target/toolchain-pinned exact runner, and
  hardened portable runner from `specs/rust-bidirectional-control.md`. `Future`,
  `FnOnce`, borrows, pointers, and active `Drop` frames never become reusable/durable.
- [ ] **swift-control-host-runner** — deliver the SwiftPM facade, explicit `SscEff`,
  generated managed state machine, actor/`Sendable`/affinity policies, mixed-SCC
  dispatcher, installed/signed init-free exact entry, and a hardened portable runner
  where platform policy permits, per `specs/swift-bidirectional-control.md`.
- [ ] **wasm-wasi-control-runner** — qualify the runner-only profile in
  `specs/wasm-wasi-control-runner.md`: bounded atomic admission, explicit
  WASI/WIT capability imports, stackless portable CoreIR execution, fresh memory per
  run, and rejection of `_start`, linear-memory/table/ref/resource snapshots. This
  does not claim a host SDK.

Each host milestone is independently shippable but none is optional. Passing an
existing AOT backend suite proves code generation only, not the typed host bridge or
dynamic saved-capsule runner.

### Bidirectional modules, build graph, and TCO

- [ ] **scala-to-ssc-exports** — support explicitly selected typed Scala exports (provisional surface
  `@sscExport`) by emitting the shared descriptor plus JVM glue and generated `.ssc` declarations.
  Portable signatures import naturally; Scala/JVM-only types require an explicit adapter/codec or
  backend-specific boundary and never weaken the platform-type ban in portable `.ssc`.
- [ ] **ssc-to-scala-effectful-exports** — generalize natural-FQN/JAR facades so pure exports expose
  host-native Scala types and effectful exports expose the public `Eff` ABI. `Future`/`Either`/throwing
  forms are terminal runner adapters only; remove the thunk/catch-by-class-name and actor stubs from
  the correctness path.
- [ ] **mixed-build-interface-first** — extend sbt/scala-cli integration to extract both interface sets,
  generate facades/stubs, compile bodies, and link one managed runtime scope. Start with a module DAG;
  reserve a two-phase interface graph for later same-module Scala↔SSC source cycles.
- [ ] **mixed-global-tail-scc** — build a typed mixed-language call graph and rewrite instrumented
  Scala↔SSC tail SCCs through a JVM tail-call ABI/dispatcher. Keep this ABI separate from `Eff/Op`;
  every SCC member/direct edge must be instrumented and the generated `TailStep` never leaks `Any`.
  Indirect/virtual/foreign/finalizer edges are deterministic barriers. Verify 1e6-depth two- and
  three-function alternating-language recursion with no unbounded JVM stack.

### Reusable continuation save/run — common capsule, then profile runners

- [~] **saved-continuation-format** — after X1/CoreIR reconciliation, define the versioned self-hosted
  envelope + durable-frame codec/verifier with independent axes
  `CodeMode = Portable | ExactArtifact` and `FrameGate = Savable | Unsavable`:
  **PART 1 LANDED 2026-07-21 (`0c815910d`, claim `durable-frame-codec`):** the canonical §9.1
  durable-frame BYTE codec `DurableCodec[S]` (reference row) — scalars incl. f64 bit-identity +
  pair/either/list/imap over a deterministic, bounded, self-delimiting big-endian format;
  `snapshot = decode∘encode`; `DurableBytes` immutable; exact/bounded decode with typed
  `DurableDecodeError`. Spec `specs/durable-frame-codec.md`; control suite 124/124 + ABI gate.
  **PART 2 LANDED 2026-07-22 (`eed2fc010`, claim `durable-capsule-envelope`):** the capsule ENVELOPE
  + resume points — `DurableCapsule` (versioned header + domain-separated SHA-256 `frameDigest`,
  encoded via Part 1 combinators; inert `decode` per §9.2) and `ResumePoint`
  (`define`/`savable`/`freeze`/`restore`). `freeze→encode→decode→restore→run` round-trips; `restore`
  admits — rejecting stale version / cross-point id / tampered frame with typed `CapsuleRejected` —
  then rebinds to the `ExactArtifact`-bound machine (never travels as bytes) and returns a reusable
  `SavedContinuation`. Spec `specs/durable-capsule-envelope.md`; control suite 132/132 + ABI gate.
  **JS-LANE CODEC MIRROR LANDED 2026-07-22 (claim `durable-frame-codec-js`):** the Part 1 frame codec
  now exists on the JS lane (`v2/host/js/control`), byte-identical to Scala — verified by a shared
  GOLDEN hex table asserted on BOTH lanes (Scala `DurableCodecTest` + JS `control.test.js`). Reconciled
  the one cross-lane divergence: NaN normalizes to canonical `0x7ff8000000000000` on both lanes
  (a JS `Number` can't round-trip a NaN payload; signed-zero/finite/inf bits stay exact). Scala
  133/133, JS 39/39. Spec `specs/durable-frame-codec.md` §4a.
  **JS-LANE CAPSULE MIRROR LANDED 2026-07-22 (claim `durable-capsule-envelope-js`):** `DurableCapsule`
  + `ResumePoint` + `CapsuleRejected` now on the JS lane, envelope byte-identical to Scala. The frame
  digest uses a self-contained SYNC SHA-256 (package stays import-free/zero-dep; parallel to Scala's
  `MessageDigest`). Cross-lane proof: both lanes assert one shared GOLDEN capsule hex (id `cell`,
  state 100) whose embedded digest was computed independently by Node crypto — so the match validates
  the hand-rolled SHA-256 == Node crypto == Java MessageDigest. Scala 134/134, JS 45/45. Spec
  `specs/durable-capsule-envelope.md` §5a. **BOTH host lanes now have full durable parity (codec + capsule).**
  **PART 3a LANDED 2026-07-22 (`durable-ref` claim): `DurableRef` + real `Restore` effect.** Added
  `DurableRef[A]` (§9.2 inert reference: providerId + opaque bytes) + `DurableRef.codec[A]`, and turned
  `Restore` from a phantom marker into a REAL row — `Restore.Resolve[A]` op + `Restore.resolve` +
  `Restore.withResolver(resolver)` (resolves post-admission, once per resolve, per-run independent).
  Decode stays inert (a capsule frame containing a ref contacts no resource); `admitLocally` fails loudly
  if a run resolves with no provider. Scala reference row; control suite 139/139 + ABI gate. Spec
  `specs/durable-ref.md`. **JS MIRROR LANDED 2026-07-22 (`durable-ref-js`): `DurableRef` + real
  `Restore` now on the JS lane too (JS 50/50) — BOTH host lanes at full durable parity.**
  **CANONICAL-KEY MAP CODEC LANDED 2026-07-22 (`durable-map-codec`, both lanes):** `DurableCodec.map[K,V]`
  sorts entries by unsigned-lexicographic key-encoding so bytes are insertion-order-independent (§9.1);
  decode rejects non-ascending keys. Shared golden hex on both lanes proves cross-lane canonical
  identity. Scala 142/142, JS 53/53. **§9.1 baseline value algebra complete.**
  **NOMINAL VERSIONED-SCHEMA CODEC LANDED 2026-07-22 (`durable-nominal-schema`, both lanes):**
  `DurableCodec.schema(schemaId, version, codec)` prefixes `string(schemaId) ++ int(version)` before
  the wrapped codec's bytes and rejects, on decode, a value written under a different name/version
  with a typed `DurableDecodeError` (byte-identical messages both lanes). Shared golden hex
  (`schema("Point",1,pair(int,int)).encode((3,4))` = `00000005506f696e74000000010000000300000004`)
  proves cross-lane identity. Scala 146/146, JS 57/57, ABI 6/6. Spec `specs/durable-nominal-schema.md`.
  **This closes §9.1's "immutable nominal … data with versioned schema identity".**
  **REMAINING (Part 3b+):** the `Portable` CoreIR resume-program payload (this is v2/native — the
  CoreIR-free leaf can't host it); signature/audience/tenant + capability policy for `DurableRef`;
  a dynamic id→resume-point registry; graph codecs (§9.3);
  `RunOutcomeUnknown`; Rust/Swift lanes (don't exist yet). The runners consume the capsule.
  `Portable(resumeCodeDigest, closed Program((frame,input)=>Eff))` or
  `ExactArtifact(artifactDigest,target,resumePointId)`, both with `FrozenFrame`, A/R codec schemas,
  exact resolver/plugin implementation profile, lifecycle, bounded policy,
  domain-separated hashes, and signature. `DurableRef` decodes inertly and resolves
  only as a typed post-admission effect. The
  exact-artifact form is not required to decode into CoreIR. Canonical CoreIR encoding stays
  kernel-owned; no Java serialization or application-visible frame bytes.
- [ ] **continuation-exact-artifact-runner-jvm** — implement the practical managed-JVM path first:
  compiler-generated resume point + codec-safe transitive frame + exact JAR/runtime bundle binding;
  run in a fresh/on-demand exact-artifact runner without calling `main` or effectful/static application
  initialization. Provider references use expiry/removal; inline exact-artifact values require finite
  `notAfter` so retention is bounded.
- [~] **continuation-save-run** — implement `continuation.save()` as typed
  `Eff[Save,SavedContinuation.Aux[A,Fx,R]]` plus reusable local/remote `saved.run(value)`. Each run
  decodes an isolated frame, freshens captured prompt ids, and invokes the resume entry once; no prefix
  or automatic admitted-run retry. External redelivery is a distinct run; post-admission disconnect is
  `RunOutcomeUnknown`. Remote residual effects require a closed row or explicit authenticated
  `RemoteRunEnvironment[Fx]`. One-shot sources remain one-shot.
  **IN-PROCESS KEYSTONE LANDED 2026-07-21 (`329f18758`, scala-explicit lane):** new public
  `Continuation.savable(state, machine, codec: DurableValue[S])` builder + authority-guarded
  `SavedContinuation.Reusable`; `save()` snapshots and returns a reusable value, `run()` re-snapshots
  an independent frame per admitted run (§8.2), resumes once at the capture point, multi-shot, no
  prefix replay; `Restore.admitLocally` discharges the in-process `Restore` row. Unmanaged/codec-less
  stay `Rejected`. Spec `specs/durable-continuation-save-run.md`; control suite 117/117 + ABI gate.
  **REMAINING:** prompt-id alpha-renaming per run (no prompts captured in the keystone state-machine
  case yet — needs the shift/reset capture path); byte-frame decode (needs `saved-continuation-format`);
  remote `saved.run` + `RemoteRunEnvironment[Fx]` + `RunOutcomeUnknown` (needs a runner). Those ride on
  the format/runner slices below.
- [ ] **portable-coreir-capsule-runner-jvm** — after the exact-artifact proof, closure-convert/link a
  state-abstracted `(FrozenFrame,input)=>Eff` CoreIR resume Program, materialize every application
  Global, and run the packed capsule on a generic managed-JVM CoreIR runner with no application JAR.
  Reject target-specific/unavailable plugin profiles. This establishes the first
  dynamic row; JS/TS, Rust, Swift, and WASM/WASI dynamic rows are mandatory profile
  milestones above rather than optional backlog work.
- [ ] **continuation-artifact-retention** — keep exact JAR/runtime bundles addressable only while a
  provider-backed identity/finite inline lease may still run; start a compatible exact-artifact runner
  on demand. Packed portable capsules pin no application artifact. No base cross-version frame
  migration, effect journal, automatic retry, or exactly-once external-effect claim.

### End-to-end completion gates

- [ ] **control-interop-nxm-matrix** — for Scala/JVM, JS/TS, Rust, and Swift,
  prove host `reset`→SSC `shift`, SSC `reset`→managed host `shift`, capture on one
  side/resume on the other, host→SSC→host→SSC callback ping-pong, handlers
  written on either side, separate compilation, mixed TCO, and every portable
  producer N→qualified runner M direction. Include save→network transfer→remote run,
  save→process restart→many runs, concurrent multi-shot runs with isolated captured data, repeated
  suffix effects, true-shift-vs-shift0, prompt isolation, wrong ABI/A-R-codec/plugin/artifact,
  exact-runner no-main/initializer replay, capture/transitive-mutable-global barriers,
  pre-admission unavailable vs post-admission unknown, residual remote handler placement,
  missing resolver vs unavailable resource, raw foreign rejection, one-shot-source
  rejection, lifecycle expiry/revocation, signature/quota, and tampered/cross-tenant
  rejection. The portable-VM is the reference evidence row; it does not own laws.
- [~] **control-interop-examples** — PARTIAL 2026-07-22 (`control-interop-example-save-run` claim):
  the **runnable same-process save→run example landed** — `examples/durable-save-run.ssc` captures a
  reusable continuation via `multi effect` and runs it several times with a prefix counter proving the
  prefix fires exactly once (no replay). Validated on BOTH the interpreter and native `.ssc` lanes;
  wired into `ExamplesSmokeTest`. This is the honest same-process demonstration using the EXISTING
  multi-shot `resume` (the semantic core of save/run). **VECTORS 14/17 FLIPPED to `specified` 2026-07-22
  (`ssc-save-run-vectors` claim, `5777a3fad`):** avoided bending by using a distinct `.ssc`
  `SavedContinuation` VALUE with `.run(...)` (a case class over the reusable `resume`), NOT a bare
  returned function — probes pass byte-exact on portable-vm + portable-asm (15/15 each), JS host lane
  runs them through the real `Continuation.savable`/`save`/`run` (control.test.js 59/59, oracle 10,20 /
  10,20,1), Scala lane unaffected (scala-explicit lacks `durable-save` cap). run.sh catalog PASS.
  **SCALA HOST LANE SYMMETRY DONE (`ssc-save-run-vectors-scala`):** scala-explicit now advertises
  `durable-save,no-replay` and `SemanticVectorConformanceTest` covers 14/17 through the reference
  library — all three applicable lanes (process portable-vm/asm, JS host, Scala host) now cover
  durable save/run. **SHIFT/RESET GAP CLOSED (`ssc-shift-reset-vectors`):** the `.ssc` process lanes
  (portable-vm/asm) now cover the multi-prompt delimited-control vectors 18/22/23 (1007/7/11), realized
  natively via algebraic effects with deep handlers — nested `handle`s = nested resets (18), distinct
  effects = distinct prompts (22), `resume` reinstalls the handler = shift-not-shift0 (23). `lanes.tsv`
  advertises `shift-reset,prompt-isolation` on both process lanes; run.sh catalog PASS, portable-vm/asm
  18/18. **ADMISSION VECTOR 11 FLIPPED (`durable-admission-resolver`):** `missing-resolver-reject`
  pending-codec→specified — `ResumePoint.define(...requiredResolvers)` + `restore(capsule,
  availableResolvers)` reject an absent resolver ATOMICALLY at admission with typed
  `CapsuleRejected(kind=MissingDependency)`; both host lanes. **VECTOR 12 FLIPPED
  (`durable-capsule-abi-manifest`):** capsule format bumped 1→2 with an `ArtifactProfile` manifest
  (`codecAbiVersion`+`artifactAbiId`+`requiredDependencies`) a resume point pins at freeze; `restore`
  now emits distinct `CapsuleRejected` kinds `CodecMismatch`/`AbiMismatch`/`MissingDependency` (+ the
  integrity ones); golden capsule hex regenerated byte-identical both lanes. **VECTOR 10 FLIPPED
  (`durable-frame-gate-barrier`):** the §8.3 FrameGate `Unsavable` side — `DurableValue.unsavable(failure)`
  builds evidence whose `captureBarrier` carries a `CaptureFailure`; `savable(...).save()` consults it and
  rejects with `Save.Rejected(CaptureFailure.CaptureBarrier)` instead of a capsule, so a raw foreign
  frame never spills. `raw-foreignv-reject` pending-codec→specified (host-only, `structured`, oracle
  `CaptureBarrier`); distinct from vector 25's `UnmanagedCapture` (state IS captured, frame is not
  durable); both host lanes; catalog negative meta-test repointed 10→13. **VECTOR 13 FLIPPED
  (`durable-signature-quota`):** the §11.1-step-2 security envelope. Capsule format bumped 2→3 with an
  `AdmissionPolicy` (`audience`+`tenant`+`requiredBudget`+`signingKey`) a resume point pins at freeze;
  `freeze` signs the canonical body with a hand-rolled **HMAC-SHA256** (`ssc-capsule-sig-v1\0` domain,
  key never in the capsule), and `restore(...availableBudget)` emits two new `CapsuleRejected` kinds —
  `TamperedCapsule` (missing/forged signature or wrong audience/tenant) and `ResourceLimit` (declared
  budget > available), checked after integrity and before codec/ABI. `signature-quota-negative`
  pending-codec→specified (host-only, `structured`, oracle `TamperedCapsule|ResourceLimit`); both host
  lanes (Scala MessageDigest-HMAC == JS hand-rolled HMAC, golden hex regenerated +20 trailing bytes);
  catalog negative meta-test repointed 13→15. **VECTOR 16 FLIPPED (`durable-concurrent-multishot`):**
  concurrent-multi-shot on the **Scala host lane only**. One immutable saved capsule is `run()` 100×
  CONCURRENTLY (100 threads released together via `CountDownLatch`) against a machine that mutates its
  own decoded frame; each result is independent (`1000+i`), proving the per-run `codec.snapshot` frame
  reconstruction has zero cross-run interference — no new runtime needed. Host-lane-only: the
  `concurrency` cap (added to `scala-explicit`) is structurally unavailable on single-threaded JS, so
  `control.test.js` now filters its coverage by a `jsUnsupportedCapabilities` set and asserts the skip
  is justified + pinned (`["16"]`), NOT silent. `concurrent-multi-shot` pending-codec→specified
  (host-only, `structured`, oracle `100-independent-runs`); verified deterministic across repeated
  runs. Now **24/26 vectors specified** (Scala 153/153, JS 64/64, ABI 6/6, catalog PASS 26/9, validator
  negatives 9/9). **CROSS-HOST ExactArtifact FOUNDATION landed (`durable-crosshost-capability`, does NOT
  flip vector 15):** a canonical `cross-host` capsule (timesTen machine, int frame, format v3) is FROZEN
  to byte-identical bytes by both host lanes AND decoded+restored+run by both (Scala CrossHostResumeTest
  + JS control.test.js `7→70/3→30`), closing the JVM↔JS N→M cross product transitively (§14.3 item 9,
  §14.4) — the axis the whole DurableValue model exists to enable. ExactArtifact CodeMode (machine held
  per host; only frame/id/ABI travel); `pending/15` updated. Scala 155/155, JS 66/66. STILL OPEN (2):
  **15** (Portable CodeMode; the ExactArtifact half is done, AND the VM now has a Portable fresh-process
  foundation — `run-capsule` (`durable`... claim `portable-run-capsule`): `v2/src/Capsule.scala` +
  `ssc freeze-capsule`/`run-capsule` run a capsule whose resume PROGRAM travels as closed CoreIR bytes,
  admitted+run in a SEPARATE process holding no machine, digest-verified; `v2/conformance/portable-capsule.sh`
  PASS (freeze in one JVM, run in another → 42/45, tamper rejected). **§10.2 GENERATION pass, first slice
  LANDED (`portable-save-region`):** `v2/src/SaveRegion.scala` closure-converts a compiler-declared
  saveable region into a GENERATED closed resume Program (frame tuple + `Lam(2,...)` that destructures it
  + applies the region lambda); `ssc freeze-region` → reified capsule runs machine-less → 19/10;
  `specs/portable-save-region.md` design (staged straight-line→effectful→2nd-backend). VM effects =
  runtime `ClosV` continuations → pass works on a SYNTACTIC region, not whole-program CPS. **Slice 2
  AUTO-LIVENESS LANDED (`portable-region-liveness`):** `SaveRegion.reifyAuto` DERIVES the frame slots from
  a free-outer-variable analysis of `(input)=>body` + a depth-aware de-Bruijn rewrite folding free refs
  into frame-tuple reads (verified on a nested-lambda region → 23/11); `ssc freeze-region-auto`. STILL NOT
  FLIPPED: first-order scalars; global closure (defs→resume.defs) + effectful regions + a 2nd admitting
  backend for §14.4 N→M remain),
  **26** (cancellation — `pending-spec`, DELIBERATELY owner-unspecified:
  the pending record forbids inventing the race/report/diagnostic rules — needs the semantic owner to
  freeze them, not a harness flip). **NON-BINDING PROPOSAL drafted for 26**
  (`specs/durable-cancellation-proposal.md`, claim `durable-cancellation-proposal`): recommended answers
  to all three open questions (cancel-vs-resume race modelled on the atomic one-shot claim; cancelled
  reusable → new typed `Cancelled` admission failure distinct from `ExpiredOrRevoked`/`AlreadyResumed`;
  `CANCELLED` boundary projection; in-flight interrupt left out of base contract as target-specific).
  Flips nothing; `pending/26` points at it; awaits owner ratify/amend/reject. Parallel: a v2/native
  Portable-runner exploration for 15 is scoping the CoreIR resume-program-payload build.
  Original blocked note (now superseded)
  preserved: BLOCKED, do not start: every one of its three
  deliverables is gated on work that does not exist yet. Measured 2026-07-16 on
  `0891ed8cf` with the assembled `bin/ssc` and `tests/interop-conformance/run.sh --list`
  (re-measure, don't trust this line). Ship runnable ScalaScript typed multi-prompt
  shift/reset and save→run-twice examples with a prefix counter proving no replay,
  plus one ordinary managed callback example for each qualified host profile. Run
  through assembled package/artifact paths and link from the common/profile specs.
  - **`.ssc` shift/reset — no surface exists.** `freshPrompt`/`reset`/`shift`/`save`
    are each `ssc: unbound global` on the assembled launcher; no `.ssc` library
    defines them (`v1/runtime/std/monad-control.ssc` is monadic loop combinators, not
    delimited control). `lanes.tsv` gives `portable-vm`/`portable-asm` no `shift-reset`
    capability, so vectors 18/22/23 print `UNSUPPORTED missing: shift-reset`. The
    `.ssc` control surface is `effect`/`handle`/`resume` only — spec §4.3's
    `freshPrompt`/`reset`/`shift` is explicitly *"conceptually provides"*, i.e. design,
    not implementation.
  - **save→run-twice + prefix counter — impossible on EVERY lane, by design.** No lane
    advertises `durable-save`/`no-replay`. Vectors `14-durable-save-run-same-process`
    and `17-no-prefix-main-replay` are `pending-codec` on all 9 lanes *including*
    `scala-explicit`. Vector 17's pending file already specifies exactly this task's
    prefix-counter obligation; its `needs:` is `save()/run() + ExactArtifact init-free
    resume entry`. No `SavedContinuation` is constructible on any profile: Scala's
    `Continuation.save()` always performs `Save.Rejected(CaptureFailure.UnmanagedCapture)`
    (`Save` has *only* the `Rejected` operation), JS `index.js:315/344` does the same, and
    `SavedContinuation.Authority` is commented *"Reserved for post-X1 library-owned
    successful save plans."* Spec §2.4 forbids the implementation until the P6.5
    `F1→F2/F3→L1→X1` fixed point is green and frozen — the same X1 gate that already
    blocks the three `coreir-*`/`numeric-width` items at the top of this section.
  - **"each qualified host profile" — the set is empty.** `jvm-generated`,
    `js-generated`, `rust-generated`, `wasm-generated`, `swift-generated` are all
    `pending`/"not qualified" in `lanes.tsv`. `specs/scala3-bidirectional-control.md`
    says *"explicit Tier 1 and lexical macro M1 implemented; remaining host profile
    planned"*; `docs/user-guide.md` says the JS package *"does not yet claim the complete
    JavaScript/TypeScript host profile."* Per spec §5.1 neither is a complete host claim.
    The landed Scala/JS control work is host-language (Scala/JS) library surface, not
    `.ssc`-callable, so AGENTS.md §3a's `examples/` rule does not bite it; both already
    carry surface examples (`ControlApiExample.scala`, the two package READMEs).
  - **Unblock order:** P6.5 X1 green/frozen → `coreir-canonical-contract-reconcile` +
    `coreir-canonical-codec-hardening` → DurableValue codec + `save()`/`run()` → then
    vectors 14/17 flip from `pending-codec` and this item becomes writable. Requalify a
    host lane in `lanes.tsv` before promising per-profile callback examples. Rewrite this
    item to match whatever actually lands; do not bend an example around the gap.
  - Found while investigating: `v2-zero-arg-unknown-method-fails-open` (BUGS.md) — an
    unknown zero-arg method silently returns `<closure>`/`Stub` and exits 0 on the default
    native lane while v1 errors correctly. This is why a `.ssc` `resume.save()` attempt
    *looks* like it runs. Fix it before trusting any example as evidence.
- [ ] **host-sdk-feature-coverage** — derive a CI-enforced matrix from existing
  feature/capability/module metadata. Every portable ScalaScript capability declares,
  for Scala/JVM, JS/TS, Rust, and Swift, one exposure form: native API, generated
  facade, managed transform, tooling-only, or target-specific. “Unavailable” is
  permitted only with a normative target-inapplicable reason; it cannot waive a
  portable runtime/library capability. Reuse existing `emit-lib` pilots rather than
  building parallel hand-maintained standard libraries.

## security-hardening — toolchain audit findings (2026-07-11, Sergiy: "аудит секюрити … запиши все проблемы в спеку и в спринт и исправь")

Spec: `specs/security-hardening.md`. Report artifact:
`https://claude.ai/code/artifact/e069a55c-a49f-4aac-bc68-4077e4d88d1b`.
Defensive audit of fs/process/http-client/http-server+json/codegen+cache across all
backends. Structural defenses (no-shell exec, TLS verify, escaped codegen literals) verified
sound. `✎` = in code shipped this session. Fix order + full exploit/fix per finding in the spec.

### Batch A — "your turf" — ✓ LANDED (rust 7d1d854d4 · jvm 1caace5f3 · json c7f116e45)
- [x] **H3 ✎ httpClient scope bypass** — `resolve()` now joins base+raw as a leading-`/` path
      (blocks `@`-userinfo host re-point); absolute only on `http://`/`https://`. VERIFIED cargo.
- [x] **H6 Rust deleteFile recursive** — `remove_file` only; no `remove_dir_all`.
- [x] **M3 ✎ Rust redirect SSRF** — `.redirects(0)` (unified with JVM/interp).
- [x] **M4 ✎ exec honours opts.timeout** — `waitFor(t,MS)` + `destroyForcibly()`; ALSO required
      draining stdout on a thread (inline `.mkString` blocked for the child's full lifetime and
      defeated the timeout). VERIFIED scala-cli: sleep-5 killed at ~312ms, code=-1.
- [x] **M5 ✎ JVM exec stderr deadlock** — drain BOTH stdout+stderr on daemon threads. VERIFIED:
      200KB stderr flood drains in ~14ms, no deadlock.
- [x] **M8 ✎ native jsonQuote parity** — escapes all `c<0x20 || c>0x7e` as `\uXXXX`.
- [x] **M9 ✎ Rust overall timeout** — AgentBuilder `.timeout(timeout)`.
- [x] **L2 ✎ Rust header CRLF** — skip header k/v containing `\r`/`\n`.
- [x] follow-up: H3 join + M9 stream-timeout mirrored to OutboundClients/HttpIntrinsics/ws-server;
      H5 JVM config → ThreadLocal. LANDED ef7fd23e7. (M3 JS redirect deferred — manual mode = opaque resp.)

### Batch B — cross-backend one-liners
- [x] **M6 JS exec exitCode masking** — `status!=null ? status : (signal||error?-1:0)`. LANDED 473bf2d71.
- [x] **M11 static-file prefix traversal** — `target.toPath.startsWith(rootDir.toPath)`. LANDED 473bf2d71.
- [x] **L6 OpenApiGenerator.jsonEscape** — delegates to `jsonStr` (−outer quotes). LANDED 46e2aa06c.
- [x] **L5 escapers omit newline** — JsGen → `jsStringLit`; JvmGenStringUtils adds `\n\r\t`. LANDED 46e2aa06c.

### Batch C — decisions made (2026-07-12, "делай автономно"); executing in order
Chosen approaches (autonomous — non-breaking defaults):
- **H2**: opt-in env flag `SSC_HTTP_BLOCK_INTERNAL=1` (default OFF = no behavior change). When set,
  after URL resolve, block hosts resolving to loopback/link-local/site-local/any-local
  (JVM+interp via `InetAddress`, catches DNS→internal; Rust via `to_socket_addrs`; JS literal+localhost).
- **H4**: NO key mgmt — reject a cached artifact whose `.ssc-artifacts` dir is group/other-writable
  (treat as stale → regenerate from source). Cheap, no secrets. Full HMAC signing → BACKLOG.
- **M1/M2**: default body caps (16 MB request on legacy JDK serve via counted read; 10 MB response on
  JVM/interp/JS clients via bounded read). Env `SSC_HTTP_MAX_BODY` overrides.
- **L1**: cap retries at 10; exponential backoff (delay·2^attempt) + ±20% jitter.
- **L3**: add `inheritEnv: Boolean = true` to ProcessOptions; `false` clears child env first.
- **M10 / L8 / M3-JS → BACKLOG**: confined-fs API (new externs, own spec), shared conformance suite,
  and JS manual-redirect (opaque-response) each need their own slice; do the M10 doc-warning inline.

- [x] **H1 SSR XSS** — `signals.mjs` `_ssc_json_html_safe` escapes `<>&`/U+2028/2029 to `\uXXXX`
      before inlining into `<script>` (both renderPage + serve). LANDED fc8cbce00. VERIFIED node.
- [x] **H2 SSRF guard** — opt-in `SSC_HTTP_BLOCK_INTERNAL=1`; JVM/interp InetAddress (catches
      DNS→internal), Rust to_socket_addrs, JS literal+localhost. LANDED 81ba4efce. VERIFIED all 3
      (127.0.0.1/localhost/10.x/169.254.169.254 blocked on, external+off allowed).
      interp HttpIntrinsics also wired (shared resolveAndGuard). All 4 backends done.
- [~] **H4 cache integrity** — DONE (cheap half): isJvmStale/isJsStale reject a group/other-writable
      `.ssc-artifacts` dir → regenerate from source. LANDED (see git). VERIFIED 755/775/777.
      → BACKLOG: full HMAC signing of `.scjvm`/`.scjs`/`classBundle` with an install-private key.
- [x] **H5 JVM outbound global vars** — base/timeout/retries/delay → `ThreadLocal`. LANDED ef7fd23e7.
- [x] **M1 request-body cap** — readBoundedBody (counted, aborts mid-stream; fixes chunked bypass) + 16MB default. LANDED (git). VERIFIED 150 http-server tests green.
- [x] **M2 response-body cap** — JVM+interp ofInputStream+bounded read (10MB, SSC_HTTP_MAX_BODY); Rust already 10MB. LANDED (git). JS lane too (byte-counted reader). ALL 4 BACKENDS.
- [x] **M7 secure temp files** — Rust `create_new`+pid/nanos / JS `'wx' 0o600`+randomBytes. LANDED a2b11223b.
      (Bonus 921a5da7c: fixed BorrowedArgIntrinsics so &str fs/path intrinsics compile on Rust — E0308.)
- [~] **M10 confined fs variants** — INLINE PART DONE 2026-07-13: `std.fs.resolveWithin(root, rel)`
      (pure ssc, cross-backend) lexically normalises `rel` (drops `.`, pops `..`) and rejects `..`
      escapes + absolute paths so the result stays under `root`; the raw helpers are now documented as
      trusted-input-only. Conformance `fs-confined` PASSES INT/JS/JVM. (Found+fixed a real correctness
      bug the shallow int cases missed — `..` popped the wrong stack element with `:+`-append; fixed to
      prepend+reverse. Also avoided a `case h :: t =>` binder the JS backend mis-binds.)
      → BACKLOG (full API): symlink-safe confinement needs an OS `realPath`/NOFOLLOW extern (JVM
      toRealPath / Node realpathSync / Rust canonicalize) + `readFileWithin`/`readBytesWithin`.
      INVESTIGATED 2026-07-13 (tried to add the read wrappers): each is blocked by a SEPARATE
      pre-existing codegen bug, so they can't land cross-backend cleanly yet:
        · `readBytesWithin` (`List[Int]`): v1 JVM/JS mis-type `readBytes`'s `List[Long]` return vs the
          `List[Int]` annotation — the int-2 ssc-Int→Long gap. CONFIRMED the DECIDED resolution works:
          `run --bytecode` (v2, natively 64-bit) reads `List[Int]` correctly (verified 2/104). So this
          wrapper is v2-codegen-only until v2 is the default codegen — NOT a v1-fixable item.
        · `readFileWithin` (`Option[String]`, no Int): compiles+runs on INT+JVM but the JS backend
          binds the imported def to `()` → "not callable" at runtime (a std-def-import codegen bug,
          distinct from Int→Long). Reverted; not landed.
        · Bonus find: importing TWO `std.fs` members via separate `[x](std/fs.ssc)` links fails on the
          v1 JVM codegen (`value writeFile is not a member`); a single link + direct intrinsic calls
          works. Pre-existing literate-import codegen bug.
      resolveWithin (the lexical primitive, `5786aac4a`) is landed + all-lanes-green; document remains.
- [x] **L1 retry backoff/cap** — cap 10 + exp backoff·2^n ±20% jitter, all 4 clients. LANDED (git).
- [x] **L3 env-scrub** — ProcessOptions.inheritEnv (JVM codegen + std/process.ssc). LANDED (git). VERIFIED scrub. + M5 interp-exec deadlock completed. (interp/Rust/JS opts-wiring → BACKLOG)
- [x] **L4 mkdir TOCTOU** — Rust+JVM create directly, tolerate AlreadyExists. LANDED a2b11223b.
- [ ] **L8 cross-backend conformance** — shared suite pinning identical fs/process/http semantics.

## Active tasks

### ▶ ci-green-final (2026-07-10, Sergiy: "занеси в спринт и делай") — the last 2 CI reds

After the CI-green sweep (jsgen `__ssc`, facade installBin, pickIosSimulator,
Conformance timeout, Lint tabs, graph-edge-display, tkv2 skip, my own
type-ascription-conformance backends:[int]) two reds remain. Both are REAL
(not paper-over-able). See [[project_ci_green_sweep_0710]] for full diagnosis.

**A. money-Currency (sbt job — `v2 Currency companion remains compatible`) — BOUNDED, doing first.**
The payments-bridge Currency companion features aren't wired on v2 alongside the
std/money.ssc case class. Validatable against the full money/payments suite.
- [x] **cur-1 arity-ctor-routing** ✓ — FrontendBridge ~2217: route `Currency(1-arg)` →
      companion global (`currencyV`, fills scale/symbol defaults the case class
      lacks); `Currency(3-arg)` → std Ctor; `Money(2-arg)` == case arity → Ctor
      (v2-money-decimal-regression fix preserved). Condition:
      `functionConstructors(name) && (!userCaseClasses(name) || args.length != fieldRegistry(name).length)`.
- [x] **cur-2 companion-statics** ✓ — `Currency.USD`/`.EUR`/… companion constants
      (from payments `currencyV`) aren't implemented on v2 (fail even via `bin/ssc
      run`; `Currency.USD` compiles to a zero-arg ctor `DataV("USD",[])`). Register
      them + route `Currency.<CODE>` select on a functionConstructor to the constant.
- [x] **cur-3 validate+land** ✓ FrontendBridgeTest 47/0, V2ConformanceTest 104/0, money smoke (Decimal preserved, shorthand+statics+3-arg) — FrontendBridgeTest (Currency green) + FULL
      money/payments suite (NO 61→25 cascade) + V2ConformanceTest, then land.

**B. int-width (Conformance job — `deep-tail-recursion`) — LARGE, language-semantics.**
- [x] **int-2v2 RESOLVED via v2-routing (2026-07-10, 70d8b0b25)** ✓ — text-rewrite of v1
      codegen proven net-negative (84→11 best, synthetic-Int boxing irreducible, scalameta
      `.transform` is a Scala-2.13 macro absent in Scala 3). The v2 pipeline (CoreIR) is
      NATIVELY 64-bit (run --bytecode / run --v2 / run-js --v2 all → 5000050000). Added a
      `codegen: v2` frontmatter opt-in to conformance run.sc: such a case runs its JVM lane
      via `run --bytecode` and JS via `run-js --v2` (INT stays interpreter). deep-tail-recursion
      opts in → PASS on all 3 backends; only codegen:v2 cases affected, 0 regressions. FIRST
      slice of the v1→v2 codegen migration; more cases can opt in as v2 codegen coverage grows.
ssc `Int` is documented 64-bit and the interpreter + v2 VM honor it, but JS AND
JVM codegen treat Int as 32-bit UNIVERSALLY (measured: non-TCO `100000*100000` →
JS `1410065408` = mod 2^32; JVM emits Scala's 32-bit `Int`). Huge blast radius
(interop, perf, every numeric test, output formatting) — NOT a bolt-on.
- [x] **int-1 decision** ✓ Sergiy chose **Option A: Int is 64-bit everywhere** (honor
      the spec/interpreter; codegen must stop treating Int as Scala's 32-bit Int).
      MECHANISM CONFIRMED: JVM + JS(scala.js) codegen are pass-through — `def f(n: Int)`
      emits verbatim Scala `Int` (32-bit); `xs.length` (scala.Int) used directly. So the
      fix = ssc `Int` → Scala `Long` in emitted code + boundary conversions.
- [ ] **int-2a type-rewrite** — emit ssc `Int` type annotations as Scala `Long`
      (`def f(n: Int): Int` → `def f(n: Long): Long`), Int literals widen (Scala allows
      `5` for a Long param). Find the JVM-codegen type-emission point (pass-through vs
      IR pass at JvmGen ~3750). **BLOCKER FOUND:** JVM emission is heavy TEXT-SLICE
      pass-through (`out.append(src.substring(...))`, scalameta `.syntax` verbatim) —
      there is NO clean type-emission point; `Int` flows as raw text in dozens of
      places. int-2a requires either re-architecting emission to AST-render (not
      text-slice) OR a fragile whole-output text rewrite (Int appears in strings,
      comments, identifiers, runtime preamble). Major re-architecture, multi-session.
- [x] **int-2b BREAKTHROUGH — the given-conversion design (2026-07-10)** ✓ The stdlib-Int
      boundary (the CRUX) is bridged AUTOMATICALLY by one conversion, NOT a multi-week
      type-aware pass. `Int→Long` widens automatically; the one missing direction
      `Long→scala.Int` is supplied by emitting ONCE before the user blocks:
      `import scala.language.implicitConversions` + `given _sscLongToInt: Conversion[Long,
      scala.Int] = _.toInt`. Fires only on a real mismatch → the (already-compiling)
      preamble is untouched. Naive `\bInt\b`→Long ALONE = 70/84; **+given = MEASURED
      84 fails → 11.** deep-tail-recursion JVM → 5000050000; content-introspection passes.
- [x] **int-2c consistency (SUPERSEDED — text-rewrite proven net-negative, best 84→11)** — see int-2v2 below
- [x] **_int-2c-orig_ — SUPERSEDED with int-2e (2026-08-15); the analysis below is still the record
      of WHY the text-only approach floors at 11** — close the last 10 JVM (generic/inferred): the given bridges
      VALUES but not TYPE CONSTRUCTORS (`List[Long]` vs `List[Int]` invariance, `Int=>Int`
      vs `Long=>Long`) nor runtime BOXING (`generator[Int]`→`[Long]` but inferred `var i=1`
      stays Int → Integer boxed → `unboxToLong` CCE). ROOT = PARTIAL rewrite: I rewrite
      explicit `Int` but Scala INFERS `Int` for `var i=1`, `List(1,2,3)`. FIX = also
      rewrite integer LITERALS `N`→`NL` (so inference yields Long) — regex must skip
      decimals/hex/exponents/already-`L`. With types+literals+given CONSISTENT, generic
      pipelines box Long uniformly. Iterate to 0 JVM regressions.
- [x] **int-2d JS lane — SUPERSEDED with int-2e (2026-08-15), and it was never needed: the v1 JS
      lane genuinely PASSES** (measured 2026-07-17, recorded in the case's own `known-red`) because
      it carries values in a double and 5000050000 is exactly representable as one. Original plan,
      kept for the record: JsGen→scala.js is a SEPARATE emission (JsGen.genModuleSegmented
      scala segments); apply the same given + Int/literal rewrite there.
- [x] **int-2e validate+land per-stage — SUPERSEDED, and its branch is gone (2026-08-15)** — the
      goal (deep-tail-recursion green) was met by int-2v2's v2 routing, landed `70d8b0b25`, not by
      landing the text rewrite. The rewrite is proven net-negative, and the project's position is now
      stronger than "not landable": `tests/conformance/deep-tail-recursion.ssc` carries a `known-red`
      that says the v1 JVM 32-bit truncation **EXPIRES when the v1 codegen is deleted — do not fix
      the v1 codegen**. int-2c/int-2d above are the same approach and are superseded with it.

      THE POINTER THIS LINE USED TO CARRY WAS ALREADY DEAD. It named `ff8e90fc2` on
      `feature/int64b`; that commit was orphaned by an amend 38 minutes later (the branch head became
      `345ab11fd`) and was reachable from no branch and no tag — alive only until the next `git gc`.
      A note that names a sha is only as durable as the ref that keeps it, which is why what the
      experiment MEASURED lives in int-2b above, in prose, with the conversion written out. The
      branch has been deleted; nothing it held is missing here.

### ▶ v1→v2 codegen migration — BASELINE + SCOPE REFRAMING (2026-07-13, chosen path A for Int→Long)
- [~] **v2-codegen-migration-baseline** — measured the v2 bytecode lane (`ssc run --bytecode`) over the
      whole conformance corpus vs `expected/`: **99 PASS / 76 FAIL / 20 SKIP**. KEY REFRAMING: making
      v2 the default codegen (which resolves Int→Long, since CoreIR is natively 64-bit) is NOT an
      Int→Long task — it is **v2-codegen FEATURE COMPLETENESS**. The 76 fails are dominated by v2
      feature gaps, NOT Int: ~33 feature-ish (actors/http/ws/effect/distributed/ui/sql/…), and of the
      ~40 first-order fails the clusters are:
        · typeclass / tagless (~12: tagless-*, std-functor/monad/monaderror/selective/bifunctor/
          semigroup-monoid, typeclass-extension) → v2 emits `__missing_tc_<Cls>` unbound global.
        · case-class body methods (case-class-body-methods: no-arg `base.size()` → falls through to a
          plugin-bridge `DataV("Stub")`, while `base.get(i)` works — a v2 method-dispatch gap for
          empty-param-list methods).
        · self-hosted std intrinsics on v2 (json-read → "jsonParse is self-hosted; import std/json.ssc").
        · optics/datasets/content-projection/html-dsl each their own gap.
      ⇒ The Int→Long *benefit* of v2 is incidental; the *cost* is closing ~76 v2 feature gaps, i.e. the
      whole v2 codegen project (multi-quarter). DECISION NEEDED: commit to v2-feature-completeness one
      class at a time (biggest lever = typeclass/given dispatch, ~12 cases), OR accept the documented v1
      Int-32-bit limitation + `codegen: v2` opt-in for the rare 64-bit-Int case (current: 1 case,
      deep-tail-recursion). Sweep artifact: scratch `sweep_v2bc.txt`.
  - [ ] **A1-typeclass-resolution** (chosen 2026-07-13; biggest lever ~12 cases) — FULLY SCOPED +
        DESIGNED, kernel change deferred to a deliberate pass. Gap is in the v2 SELF-HOSTED lowering
        `v2/lib/ssc1-lower.ssc0` (rebuild: edit source → `installBin` → tower copy):
          · buildGivenTable (:454) registers givens as (TC, TypeName)→name — Semigroup[Int]→intSum,
            [String]→stringConcat, [List]→listConcat ARE registered with types. buildSigTable (:511)
            maps a fn with `__tc_X` params → its TC list. So registration is FINE.
          · buildGivenArgs (:628) resolves `findGiven(tc, typeOfExpr(firstArg))` then falls back to
            findAnyGiven (:565, first-match). THE BUG: `typeOfExpr` (:618) is LITERAL-ONLY (returns "?"
            for `List(1,2,3)`), and for `combineAll[A](xs: List[A])(using Semigroup[A])` the given is for
            the ELEMENT type A, not the arg's own type. So the direct lookup returns "?"→None→
            findAnyGiven picks intSum for EVERYTHING → the "0"/garbage output + `__missing_tc_` when a
            TC has no given at all (combineAllOption).
        FIX (bounded, additive — only fires where the current lookup already yields "?"/None, so low
        kernel-regression risk): (1) extend `typeOfExpr` to return "List" for an "app" expr whose fn is
        `var "List"` (else keep "?"); (2) in buildGivenArgs, when `findGiven(tc, argType)` is None AND
        firstArg is a `List(...)` app, retry `findGiven(tc, typeOfExpr(firstElement))` (the element
        type) before falling to findAnyGiven. Handles both direct `show(42)` (argType "Int") and
        `combineAll(List[X])` (element type) without full HM. VERIFY: v2 bytecode sweep (99→?) MUST not
        regress any of the current 99 pass + should recover the semigroup/monoid/functor cluster; run
        the v2 kernel test suite too (self-hosted lowering is correctness-critical for ALL v2 codegen).
        NON-general (documented limitation): nested/other containers (Option[A], Map) still need real
        unification — a later slice. AST note: calls are tag "app" (:88); List(...) is an app of var
        "List"; element extraction = first arg of that app.
        ATTEMPTED 2026-07-13 (the buildGivenArgs element-fallback above) → INEFFECTIVE, REVERTED. Root
        is DEEPER + MULTI-SITE: `combineAll`'s dict is resolved TYPE-BLIND at `computeActiveCtx` (:585)
        — `findGiven(tc, "*")` (only matches wildcard-typed givens) → falls to `findAnyGiven` → the
        FIRST given (intSum) for EVERYTHING. So `combineAll(List(...))` always gets intSum regardless of
        element type (int case passes by luck; string/list give the "0"/garbage; a TC with no given at
        all → `__missing_tc_`). The call-site `buildGivenArgs` fix never bites because the dict is
        already bound blind at the def/ctx level. A CORRECT fix must thread the concrete element/arg
        type from the CALL SITE into the dict selection at BOTH `computeActiveCtx` and the call-site
        injection (i.e. real type-directed resolution / light unification), not a single-site heuristic.
        That is a genuine multi-session type-inference slice — the "biggest lever" but also the deepest;
        needs a deliberate design pass on the whole given-dict flow in ssc1-lower.ssc0, not a bolt-on.
        A1-CONT (deliberate pass, 2026-07-13) → hit the ARCHITECTURAL wall, reverted. Implemented the
        3-part fix (typeOfExpr List; buildGivenArgs element-type selection; computeActiveCtx → param
        name). Result: `__tc_Monoid_empty` UNBOUND. ROOT ARCHITECTURE: v2 typeclass dispatch is fully
        STATIC — a given instance is a set of GLOBALS (`intSum_combine`, `intSum_empty`, …) and a method
        call lowers to `<given>_<method>` resolved at COMPILE time. There is NO runtime dictionary: a
        ctx param is not a value carrying methods, so mapping it to the param name yields
        `__tc_<Cls>_<method>` globals that don't exist. And a polymorphic `combineAll` body is lowered
        ONCE while the needed instance depends on the CALL — static `<given>_<method>` dispatch cannot
        bridge that. The current code "works" for the int case only because `computeActiveCtx` blindly
        binds the FIRST instance (intSum). ⇒ Correct polymorphic typeclass dispatch requires either
        (a) MONOMORPHISATION (emit a specialised `combineAll_<T>` per instantiation with the instance
        baked in) or (b) RUNTIME DICTIONARIES (pass the instance's methods as values; lower method
        access to field/value access, not a mangled global). BOTH are MAJOR v2-lowering architecture
        changes, not a patch — a dedicated design+build effort. This diagnostic pass pinned the exact
        wall; no code landed (kernel reverted clean).
  - [ ] **A1-mono (chosen 2026-07-13) — DESIGN DONE, build is the next dedicated effort** →
        `specs/v2-typeclass-monomorphization.md`. Decision: MONOMORPHISATION over runtime dicts (fits
        the static `<given>_<method>` model, REUSES `computeActiveCtx` — emit one specialised copy of
        the polymorphic body per needed instance, each lowered with active ctx = the CONCRETE instance;
        rewrite calls to `f$<instanceKey>(args)`). Phased plan in the spec: Collect (call sites →
        (fn,instance) set, by the List-element type rule) → Emit (specialised defs, memoised) → Rewrite
        → Transitivity (worklist to fixpoint). Fallback = today's first-instance for unknown types (no
        regression). VERIFY: v2 bytecode sweep must hold 99 + recover the ~12 typeclass cluster; land
        per-slice (direct case first, then transitivity), revert on any regression.
    - [x] **A1-mono SLICE 1 (inline typeclass fns)** `4a6ba79d4` — DONE. monoInstanceFor (call-site
          rewrite → `fn__mono__instance`, instance by List[A]-element type) + emitMonoDefs (re-lower the
          fn body with active ctx = the CONCRETE instance, so `summon[TC].m` → `<instance>_m`) +
          typeOfExpr List. Ctx param kept (unused) → arity unchanged. v2 bytecode 99→102, 0 regressions.
          CORRECTNESS FIX `21c11c7ae` — the mono hook is in lowerE (post-resolveE), where `List(1,2,3)`
          is already `ctorap(Cons, [1, …])`, NOT `app(var "List")`; the helpers now read the ctorap/Cons
          form (element = head of the outer Cons). VERIFIED the pass ACTUALLY FIRES inline now:
          `combineAll[A: Monoid](List(1,2,3))`→6 (intSum), `List("a","b","c")`→"abc" (stringConcat,
          correctly selected — was "0abc"). (The +3 sweep cases predate this fix; the mono is now
          genuinely functional for INLINE typeclass fns.)
    - [x] **A1-mono SLICE 2 (imported typeclass fns + TC subtyping)** `599ab81b8`/`e9ffd4ee6` — DONE.
          The "imports are the blocker" theory (prev bullet) was WRONG: a PROBE showed mono ALREADY
          fires for the imported `combineAll` (its sig/def ARE visible to lowerProg via the shared
          globals). The two real gaps, now fixed:
          (1) List[elem] instance key — typeOfExpr recurses into the `ctorap(Cons,..)` head so the key
              is "List[Int]" not bare "List", so combineAll specialises per element type.
          (2) Typeclass/trait SUBTYPING for context bounds — `combineAllOption[A: Semigroup]` needs a
              Semigroup[A] but std only has `given intSum: Monoid[Int]` (Monoid extends Semigroup). New
              shared `tcExtendsCell` (ssc1-front captures `trait Child extends Parent` at the trait-parse
              hook, header-bounded scan) + buildGivenTable emits each given under its TC AND every
              ancestor TC (tcAncestors/givenEntriesFor). std-semigroup-monoid now 6/6.
          VERIFIED: v2 bytecode sweep 102 → 103, 0 regressions. REMAINING (each falls back to today's
          behaviour = no regression until done): transitivity (follow calls inside specialised bodies to
          a fixpoint), multi-ctx-param mono, non-List containers (Option[A], Map) via real unification.
    - [x] **A1-mono SLICE 3 (chained bounds + TC-correct summon dispatch)** `3e772f6b2` — DONE.
          Made `tagless-context-bounds` pass 7/7 via three fixes (v2 bytecode sweep 103 → 104, 0 regr):
          (1) CHAINED context bounds `[A: Monoid: Pretty]` — ssc1-front parseTypeParams read only the
              FIRST bound per type var, silently dropping the rest (no `__tc_Pretty` param). Now a
              readBounds loop reads all chained `: TC` bounds.
          (2) summon[TC].method / .field respect the EXPLICIT TC — both sites resolved to
              `firstActiveGiven` (the first active ctx instance), a silent miscompile in any 2+-given
              body (`summon[Pretty[A]].pretty` → `intSum_pretty`). Summon receiver now carries its TC as
              a `summon_tc` node; dispatch resolves it via lookupActiveCtx (fallback firstActiveGiven).
          (3) summon-as-value `val m = summon[TC[A]]` — a given is a set of globals, not a value. New
              block-local summon-alias registry (summonAliasCell): the val registers m→tc in resolveBlock,
              m.method/m.field dispatch via active ctx, lowerBlock drops the vestigial binding, reset per
              def. An escaping summon value lowers to a loud `__summon_value_<TC>` (no silent miscompile).
    - [x] **A1-mono SLICE 4 (extension-method instance dispatch)** `9cdb260ca`/`99e63dbb4` — DONE.
          `extension … def m` in a `given g: TC[T] with …` body is emitted prefixed `g_m`, but the call
          `recv.m(args)` uses the BARE `m` (__methodOrExt__) → with 2+ instances no `m` bound → Stub.
          Fixes: (a) collectExtensionMethods now DESCENDS into given bodies (else `recv.m` misroutes to
          __method__); (b) new dispatch pass — collectExtDispatch records [method→(typeHead, g_method,
          arity)] per given body, emitExtDispatchers emits a bare `m` dispatcher
          `if <recv is T1> then g1_m(…) else … else <fallback>` (orTagTests + extTypeTags built-in tag
          table); (c) a method with BOTH a top-level ext AND given-body instances gets its top-level
          impl mangled to `m__ext_default` and the dispatcher falls back to it (handleError: Either
          top-level + Option given). v2 bytecode sweep +4: typeclass-extension, std-functor-applicative-
          monad, std-selective, tagless-sealed-dispatch. 0 regressions.
    - [x] **A1-mono SLICE 5 (named `using` param auto-resolution)** `a36f886b4` — DONE. Named
          `using s: Show[A]` params were parsed (type discarded) and appended after regular params, but
          ctxTCsOf only saw `__tc_TC` params → no injection → "arity: 2N expected, N given". Now:
          ssc1-front parseUsingParams captures each using param's TC head (skipTypeAnnot advancement —
          readTypeStr over-read past the depth-0 `,`), parseDef registers (defName→([tcHead],fullCount))
          in shared usingSigCell; ssc1-lower injectGivens appends buildUsingGivenArgs at the END (ctx
          givens still prepend), guarded by `len(args) < fullCount` so explicit `(using x)` isn't
          double-injected. → **tagless-resolution 5/5**. sweep 109→112 (+ 2 flaky scljet-write), 0 regr.
    - [x] **A1-mono SLICE 6 (higher-kinded type params + extension-after in given body)** `7a78f09cb`/
          `1ea4f1720` — DONE. Closed the last two tagless cases, both PARSER bugs (not the checker/lowering):
          - `tagless-program` → `def f[F[_]](…)` mis-parsed: parseTypeParams read tyvar `F` then stopped
            at the INNER `]` of `[_]`, misaligning the signature → spurious tuple the Mira checker rejected
            ("cannot unify Tuple with non-Tuple"). Fix: skipTypeArgs over a higher-kinded param's own
            `[_]`/`[_,_]` before the bound/comma (unchanged for simple params + chained bounds).
          - `tagless-multi-file` → a regular `def` AFTER an `extension` group in a `given … with` body was
            dropped: parseObj closed the given at the extension's virtual E-frame `}` (an extension closes
            `} extension_end`, the given closes bare `}`). Fix: on `}`, peek extension_end → reset
            extensionParams, keep the marker, continue the body.
    - [x] **✅ ENTIRE tagless/typeclass/functor conformance cluster GREEN (12/12)** on the v2 bytecode lane:
          std-semigroup-monoid, tagless-context-bounds, typeclass-extension, std-functor-applicative-monad,
          std-selective, tagless-sealed-dispatch, tagless-resolution, tagless-program, tagless-multi-file,
          tagless-direct-syntax, + **std-bifunctor, std-monaderror** (`027250d4d`): extTypeTags("Tuple2")→
          ["Pair","Tuple2"] (tuple literal is IrCtor("Pair"), runtime ops make DataV("Tuple2")) and
          firstTypeArg extracts the container type from a multi-param TC (`MonadError[Option,Unit]`→Option,
          was taking the whole "Option, Unit" → None fell to the Either impl → "no arm for None"). v2
          bytecode sweep 102 → 117 across the session, 0 real regressions.
          Backlog (not blocking any conformance case): mono transitivity, multi-ctx-param mono, non-tuple
          multi-arg containers (Map).
    - [x] **v2-bytecode effect threading over curried collection methods** `bb8b0230c` — `perform().foldLeft(z)(f)`
          failed "no arm for Op/3": the self-hosted CURRIED `_sel_foldLeft` (IrLam(2,IrLam(1,…)))'s inner
          go-match runs on a Local holding the Op, but the bytecode backend only A-normalizes/threads Op
          scrutinees when `mayOp` is true, and `mayOp(Local)`=false (single helpers like `_sel_map` don't
          hit this). Fix: route foldLeft/foldRight through runtime `__method__` (its methodOp threads an Op
          receiver, Runtime:2728; handles List/ArrayBuffer/Map). → effect-imported-handler,
          effect-transitive-handler. sweep 117 → 119, 0 regr.
    - [ ] **v2-bytecode effects REMAINING** (head-field-effect-shadow, coroutine-basic/error,
          js-applyunary-effect-cps) — perform in ARGUMENT position (`scoredGigs(GigSource.fetch())`, then
          `if gigs.isEmpty`) leaks a raw Op; the bytecode backend threads Ops only in Match/Let/Seq
          scrutinee + receiver/arith/fn positions, NOT function-argument position. LESSON: applying
          `OpAnf.lift` (the v1-bridge arg-lifting pass) to the whole self-hosted program via runBytecode
          REGRESSED the working effect cases — the self-hosted lane (like the Mira lane OpAnf excludes)
          passes Ops to functions legitimately (resume/handle), so blanket arg-lifting forwards Ops past
          their handlers. Needs SELECTIVE arg-lifting (only unresolved-perform args to non-handler
          consumers) = effect analysis. Reverted.
    - [x] **fenceless bare .ssc on the native checker** `29a96effc` — a heading-less .ssc with no
          ```scalascript fences is code in full ("код целиком"). The RUNNER (ssc1-run sscProgramSource)
          already handled it, but the CHECKER (ssc1-check-run) extracted only fenced blocks → "no
          scalascript blocks" → rejected before the runner ran. Fix: mira-md.bareCodeFallback (whole body
          past shebang+front-matter when heading-less; doc-only when headings present). → fenceless-bare-
          code, parenless-def-value, user-request-shadow, predef-notimplemented (last needed a sibling's
          `notImplemented` fix `b77862d7f` too). v2 bytecode sweep 119 → 122, 0 regr.
    - [x] **exception subsystem — try/catch/finally + throw on the native lane** `50bf0f89c` — the native
          lane had NO exception support (`throw` → plugin global `__throw__` unbound on native; `try`/
          `catch` didn't parse). Added: (front) `try BODY catch {case…} [finally F]` → prim
          __tryCatch__/__tryCatchFinally__ (thunks + PF over the caught value); `throw e` → prim __throw__;
          `catch`/`finally` as continuation tokens (isCont/canStartLine) so multi-line `try{}`\n`catch{}`
          doesn't split. (Runtime/Prims) SscThrow(value); __tryCatch__ catches SscThrow (→ thrown value)
          and host RuntimeException (→ DataV("RuntimeException",[msg])); getMessage on exception DataVs;
          finally runs unconditionally. (lower) exception ctor prelude defs so `new RuntimeException(m)` →
          DataV. → **dataset-agg, http-client** (sweep 122 → 124, 0 regr). LIMIT: a brace-less indented
          def body `def f =\n try{}\n catch{}` still splits (layout).
          - `tagless-program` → `TYPEERR: cannot unify Tuple with non-Tuple` (typer/tuple, distinct).
    - [x] **optics — .index/.at + rendering + mixed-arg copy** `982ea9952` — (1) OpticsNativePlugin
          step/setPath/modifyAll gained OIndex(i) (bounds-checked List) + OAt(k) (Map key) — get/set/
          modify now work; (2) the optic renders its source path (`Lens(_.x)`), and Show.show consults a
          NamedMethodObj's `_show` before `<foreign>`; (3) mixed positional+named `.copy(10, z=99)` encodes
          positionals as `#i` (was stripping labels → z applied to y). → optic-polish, optics-index-at,
          signal-id-bridged (sweep 126→129).
    - [x] **actors — supervision + cluster/phi + scientific floats** `7ad97307e`/`c78268db1` — added the
          Erlang supervision layer (link/monitor/trapExit/exit → Exit/Down propagation; propagate BEFORE
          `dead` so quiescence can't end the scope early; queue.offer not put; io.println made atomic for
          concurrent actors) + single-node cluster stubs (joinCluster/broadcastHealth no-op, clusterIsDown
          ⇒false, phiOf⇒+Inf, isSuspect⇒true) + scientific-notation float lexing (`1.0e100`). → actors-
          supervision, actors-cluster-discovery, actors-cluster-isdown, actors-phi-accrual (sweep 129→136).
          NOTE: actors-supervision is virtual-thread-heavy — passes serially; can flake under 8-way parallel
          sweep CPU contention (the official runner is serial).

### ▶ ssc-toolkit-v2 (2026-07-07, owner-directed via busi: the busi SPA must move React→ScalaScript)

Requirements source: busi `src/v2/specs/frontend-on-scalascript.md` (owner 2026-07-06). busi is the
**conformance target** — toolkit v2 is done when busi's `App.tsx` (99 pieces of state, ~91 form
interactions, offline-first PWA, WebAuthn, 4 locales) is expressible in `.ssc`. Design + full slice
detail: **[`specs/ssc-toolkit-v2.md`](specs/ssc-toolkit-v2.md)**. Additive over `std/ui` — no breaking
changes for existing consumers (rozum control-center, busi server pages). Every slice ships
conformance cases (INT==JS) and runs the affected-slice conformance before push (AGENTS.md 4b).

- [x] **tkv2-components** ✓ DONE 2026-07-07 — `std/ui/component.ssc`: `component(kind, key)(Ctx => N)`
      + `ctxSignal` → `<kind>__<key>__<name>` (SANITIZED — emitter contract: signal ids must be JS
      identifiers `[A-Za-z_][A-Za-z0-9_]*`; React derives useState var names from them, so `/`
      separators are rejected at emit). `childCtx` nesting; pure .ssc. Disposal DEFERRED to
      tkv2-keyed-for (tree is built once today). Conformance `tkv2-component` INT==JS; example
      `component-demo` browser-driven. Fixed 2 JsGen bugs en route (BUGS.md: Signal-import-vs-preamble,
      reserved-word param body rename). GOTCHA for later slices: char comparisons + regex replaceAll
      diverge between lanes — sanitize with substring+contains (see ctxClean).
- [x] **tkv2-offline** ✓ DONE 2026-07-07 — `std/ui/offline.ssc`: `localStorageGet/Set/Remove` +
      `onlineSignal()` + `persistedSignal(name, default)` externs (frontend-plugin JVM lowering:
      per-process map + constant-true; signals.mjs `_ssc_ui_*` shims: real localStorage/navigator.onLine
      in-browser, mem-map/true on Node). ALSO: interp dispatch for `sig.get()`/`sig.set(v)` on
      ReactiveSignal (JS-lane parity) — makes ui-signal BEHAVIOR conformance-testable INT==JS for all
      future slices. Conformance `tkv2-offline`; browser-driven via emit-spa (type → localStorage →
      reload restores → offline badge flips). GOTCHAS: persist via effect-subscription, NOT a set-wrapper
      (DOM/fetch write through `_signalSet` by id, bypassing the object's .set — caught in the real
      browser, invisible to the Node conformance run); use `window.localStorage`, not the bare global
      (Node 26 defines a warning getter). `fetchOrLocal` DEFERRED to the busi-home slice (needs the
      fetch machinery + a local compute fn — design it against the real screen, not speculatively).
- [x] **tkv2-forms** ✓ DONE 2026-07-07 — `std/ui/form.ssc`: `FieldSpec` data-DSL (required/min/max/
      pattern — pure `validateField`, same rules every backend) + `form(ctx, specs)` (drafts =
      component-scoped signals) + `fieldError`/`formErrors`/`formValid` (computed, live) +
      `formField`/`submitGate` widgets. ALSO: `String.matches` added to the JS lane (anchored,
      Scala full-match semantics; guard `string-matches` INT==JS==JVM); interp `computedSignal`/
      `eqSignal` now RECOMPUTE ON READ (JS read-freshness parity → reactive derived state is
      conformance-testable). Conformance `tkv2-forms` INT==JS; form-demo browser-driven (live
      errors, gate opens/closes). GOTCHAS: `.toMap` on List-of-pairs isn't dispatched on interp
      (use foldLeft+updated); JsGen capability detection reads the ENTRY file only — every new
      std/ui module must register its API names in the hasUiHelpers list or import-only usage
      emits without signals.mjs; SPA drivers must assert page.innerText (textContent includes
      script source + display:none branches). DEFERRED: touched-state (errors show from start),
      submit busy/error tri-state (needs an onFailure fetch effect).
- [x] **tkv2-spa-pipeline** ✓ DONE 2026-07-07 — audited: `emit-spa --frontend custom` output has
      ZERO external script/link/import tags (offline-demo + form-demo bundles); the only http(s)
      strings are inert jwt-auth endpoint constants riding the serve→HtmlDsl→Jwt capability chain
      (tree-shake candidate, size-only). Production path documented in user-guide §17.9; all
      toolkit-v2 primitives already verified on this path (slices 1–3 browser drives).
- [x] **tkv2-pwa-adopt** ✓ DONE 2026-07-07 (code+tests; .ssc drive PENDING on
      plugin-lazyload-extern-imports) — `std/pwa.ssc` extended: `cacheVersion` (cache-name bump +
      activate cleanup), `networkFirst` (fresh-online/cached-offline read routes; never list write
      routes), `offlineHtml` (navigation fallback page), `maskableIcon`. Everything busi's
      hand-written `http/pwa.ssc` does. PwaPluginTest 4/4 (generators); conformance `tkv2-pwa`
      written but `pending:` — FOUND pre-existing regression: lazy-loaded plugin externs
      (smtp/tcp/pwa) are dead from .ssc on main (BUGS.md plugin-lazyload-extern-imports; stock
      pwa-demo example fails). busi-side adoption happens at the migration pilot (needs a pin bump).
- [x] **tkv2-busi-home-conformance** ✓ DONE 2026-07-07 — `tkv2-busi-home` corpus case (INT==JS):
      busi-shaped obligation ids → per-card instance-scoped expand; income form (digits/date
      patterns) with live gate; persisted home payload surviving the reload shape; onlineSignal.
      Browser twin `examples/frontend/busi-home-demo` driven via emit-spa (only the toggled card
      expands; Record appears on valid form). GOTCHA found+fixed in form.ssc: a computed thunk
      invoked from ANOTHER module's context doesn't resolve this module's globals (load-order/
      global-resolution trap) — bind module functions to local vals before closing over them.
- [x] **tkv2-keyed-for** ✓ DONE 2026-07-09 — `forKeyed(items, key)(render)` landed for the
      JsGen/custom browser runtime (`ea79e003a`; docs `8b9c47e25`, `f129df583`): std/ui node
      + primitive, `_ForKeyed` render marker, scoped `_ssc_ui_mount` binder for dynamically
      inserted rows, keyed reconcile by direct child `data-ssc-key`, JVM/interpreter static
      fallback, conformance case, and `examples/frontend/keyed-for-demo`. Gates:
      `backendInterpreter/testOnly scalascript.JsGenStdImportTest scalascript.JsRuntimeKeyedForTest`
      (43/43), affected module compiles, `tests/conformance/run.sh --only 'tkv2-keyed-for'
      --no-memo`, and `bin/ssc emit-spa --frontend custom examples/frontend/keyed-for-demo/keyed-for-demo.ssc`.
      Note: same-key item value changes intentionally do not re-render in this slice.
- [x] **tkv2-webauthn** ✓ DONE 2026-07-09 — browser `navigator.credentials.create/get`
      actions (register/assert) for the production `emit-spa --frontend custom` path.
      Feature `e61a89b4c`, docs `6801d977c`: `std/ui/webauthn.ssc` exports
      `webauthnRegister` / `webauthnAssert` EventHandlers, `signals.mjs` runs the
      begin -> browser credential -> complete ceremony with base64url payloads and
      caller headers, off-browser fallbacks report a clear unavailable error, and
      the adjacent `std/auth.ssc` WebAuthn declaration drift is fixed.
      Active plan 2026-07-09 (`feature/tkv2-webauthn` / codex):
      - [x] Spec first in `specs/tkv2-webauthn.md`, then commit/push it before implementation.
      - [x] Add UI-facing WebAuthn EventHandler externs in `std/ui/webauthn.ssc`, not to core:
            `webauthnRegister(beginUrl, completeUrl, rpName, result, error, headers, timeoutMs,
            userVerification)` and `webauthnAssert(beginUrl, completeUrl, result, error, headers,
            timeoutMs, userVerification)`.
      - [x] Implement the browser/custom runtime in `signals.mjs`: POST begin JSON, call
            `navigator.credentials.create/get`, base64url-encode browser ArrayBuffers, POST complete JSON,
            write response text into `result`, and write user-visible failures into `error`.
      - [x] Keep Node/interpreter behavior deterministic: off-browser handler creation is allowed, but
            invoking it reports a clear "WebAuthn unavailable" error instead of silently succeeding.
      - [x] Fix the adjacent std-auth WebAuthn declaration drift recorded in `BUGS.md`
            (`std-auth-webauthn-signature-drift`): declarations must match the existing JVM/JS runtime
            implementations and examples.
      - [x] Add focused runtime tests with stubbed `navigator.credentials` and `fetch`, plus a conformance
            API smoke case. Gate before push with targeted Scala tests, affected compiles,
            `tests/conformance/run.sh --only 'tkv2-webauthn,webauthn-server-verify' --no-memo`, and an
            `emit-spa --frontend custom` smoke of the new example.
      Gates: affected compiles green; `backendInterpreter/testOnly
      scalascript.JsRuntimeWebAuthnClientTest scalascript.JsGenStdImportTest` green (43 tests);
      conformance `tkv2-webauthn,webauthn-server-verify` green (2/2, INT+JS pass);
      `bin/ssc emit-spa --frontend custom examples/frontend/webauthn-toolkit-demo/webauthn-toolkit-demo.ssc`
      emitted the expected WebAuthn browser runtime markers. Gotcha recorded in
      `specs/tkv2-webauthn.md`: stale local `bin/ssc` required `scripts/sbtc "installBin"`
      before real-harness conformance.
- [x] **tkv2-typed-client** — DONE 2026-07-09 (`4656f9629`): route-derived
      `.ssc` API clients now produce callable path-param methods. `RouteDeriver`
      defaults no-body/no-param endpoints to `Unit`, one no-body path param to
      `String`, multiple no-body path params to `Any`, and body methods to
      `Any`, while explicit `apiClients:` metadata and existing validation
      warnings remain unchanged. Browser JS clients now accept the derived
      input and substitute it into the `fetch` path; JVM/Swing sees the same
      metadata and emits callable in-process methods. Gates: `RouteDeriverTest`
      16/16; `JsGenTypedRouteClientTest` + `JvmGenTypedRouteClientTest` 57/57;
      affected compiles; `installBin`; conformance `tkv2-typed-client-derived`
      1/1 JS; `emit-js` and `emit-spa --frontend custom --server-url` smokes for
      `examples/derived-route-clients.ssc`. Gotcha: CLI/conformance use
      installed `bin/ssc`, so run `scripts/sbtc "installBin"` after
      RouteDeriver/codegen changes.
      Original: route-derived `.ssc` API client; browser transport = fetch, JVM =
      existing in-process transport (fullstack spec phases 0–5).
      Active plan 2026-07-09 (`feature/tkv2-typed-client` / codex):
      - [x] Claim/worktree created; stale `bin/ssc` gotcha re-confirmed and fixed locally with
            `scripts/sbtc "installBin"` before CLI smoke.
      - [x] Spec first in `specs/tkv2-typed-client.md` and bug ledger entry
            `route-deriver-path-param-unit-client` in `BUGS.md`, then commit/push before code.
      - [x] Fix `RouteDeriver.makeEndpoint`: no explicit `apiClients:` and no typed handler evidence
            should derive `String` for one non-body path parameter, `Any` for multiple non-body path
            parameters, `Unit` only when no body and no path params; body methods stay `Any`.
      - [x] Add/adjust tests: `RouteDeriverTest` for route/mount/routes path-param defaults;
            `JsGenTypedRouteClientTest` Node harness proving derived `Api.get...("42")` fetches
            `/api/.../42`; `JvmGenTypedRouteClientTest` proving Swing/JVM emits callable derived
            methods over in-process transport.
      - [x] Add a JS-only conformance smoke `tkv2-typed-client-derived` with stubbed `fetch` and
            `awaitClient(Api.get...("42"))`; update `examples/derived-route-clients.ssc` so the
            no-manual-`apiClients:` example is actually browser-callable.
      - [x] Docs/bookkeeping: update `specs/typed-route-clients.md`, `specs/ssc-toolkit-v2.md`,
            README/user-guide/example index as needed, then mark BUGS/SPRINT/CHANGELOG done.
      Done-when: targeted core/codegen tests pass, affected compiles pass, conformance
      `tests/conformance/run.sh --only 'tkv2-typed-client-derived' --no-memo` passes, and
      `bin/ssc emit-spa --frontend custom --server-url http://server.example:49155 <example>`
      contains a derived `Api` client whose path-param method accepts an input argument.
- [x] **tkv2-theme-css-vars** ✓ DONE 2026-07-07 (taken out of order — small) — `cssVariables(t: Theme)`
      in theme.ssc: the theme as `:root { --ssc-* }` custom properties; one ssc value drives toolkit
      AND hand-kept CSS. Conformance `tkv2-theme-css-vars` INT==JS.

### Local model session help (2026-07-07)

- [x] **qwen-rozum-session** — help Sergiy start a local `rozum` chat session with a Qwen 3.6 model.
      Why: user wants an actionable on-machine launch path, not compiler work.
      How: inspect existing repo docs/scripts/examples for `rozum` gateway/client commands and Qwen/OpenAI-compatible model configuration; avoid code changes unless a missing script/doc is discovered and explicitly needed. Verify commands with non-destructive `--help`/status/list checks first, then provide the minimal terminal sequence. If the requested exact model name is not present locally, explain the likely model id/config place and how to list/install it.
      Done-when: Sergiy has concrete commands for starting the model backend/gateway and opening a `rozum` chat/session, plus any prerequisites or unknowns called out.
      Result: `rozum` and `ollama` are installed; meeting daemon is running with rooms including
      `scalascript`; no shared gateway is running. The exact installed Qwen 3.6 model is
      `mlx-community:Qwen3.6-35B-A3B-4bit-DWQ` (19 GiB on disk). Verified launch shape from
      `USER_MANUAL.md`: start gateway on `8089`, run `rozum meetings participant --gateway-url
      http://127.0.0.1:8089/v1`, then attach with `rozum meetings attach --room <room>`.
      Current dry-run refuses Qwen3.6: even `--n-ctx 4096 --min-free-ram-gb 0` needs 21.84 GiB
      available vs 21.45 GiB, short ~0.4 GiB; with normal margin it is short ~2.35 GiB.
      `mlx-community:Qwen3-4B-4bit` dry-run passes and can be used as a small-model smoke.

### Green main recovery (2026-07-06, user asked to finish the stabilization)

- [x] **green-main-crypto-ci** — restore `origin/main` to a buildable state before more v2 feature work.
      Why: the latest CI push is red in markdownlint, `sbt compile cli/assembly`, and conformance; v2 parity
      work is hard to trust while the main branch cannot assemble the launcher.
      How: first fix the concrete compile blocker in `payments/crypto/bouncycastle/BouncyCastleBackend.scala`
      by adapting it to the current portable crypto APIs (`ChaCha20Poly1305.seal/open`,
      `X25519.derivePublicKey/sharedSecret`, random private key generation). Then run targeted compile for
      `cryptoBouncycastle` and the affected crypto tests; if compile is green, re-check `sbt compile cli/assembly`
      with an explicit worktree `cd`. After code is green, triage whether CI conformance failures are downstream
      of the failed launcher or a separate runner issue, and record any remaining follow-up separately.
      Done-when: `cd <worktree> && sbt "cryptoBouncycastle/compile"` passes; broader compile/assembly is either
      green or has a newly diagnosed next blocker recorded here.
      Result: fixed the compile blocker by replacing the wildcard `scalascript.crypto.*` import in
      `BouncyCastleBackend.scala` with explicit SPI imports, so unqualified `ChaCha20Poly1305` and `X25519`
      resolve to the JVM/BouncyCastle package helpers again. Verified:
      `sbt "cryptoBouncycastle/compile"`, `sbt "cryptoBouncycastle/test"` (55/55), and
      `sbt "compile" "cli/assembly"` all pass in `/Users/sergiy/work/my/scalascript-wt-finish-green-main`.

- [x] **green-main-conformance-gating** — DONE 2026-07-08 (`3008b2677`):
      full default conformance is green with
      `tests/conformance/run.sh --no-memo` => **122 passed, 0 failed out of
      122 tests (+2 pending)**. Pending cases are intentional metadata gates:
      `http-client` (external httpbin.org dependency) and `sql-browser-basic`
      (needs npm install in the JS lane, pinned by its capture test). This slice
      fixed the deterministic blockers found after the original 102/20 baseline:
      actors/effects INT, JVM CPS cluster/distributed/effect cases, JS std/json
      intrinsic targets, JS product rendering, INT SQL block scope, std
      typeclass INT/JVM aggregate gaps, JVM std-ui generated braces, stale
      `.scjvm` codegen cache invalidation, INT while assignment order, and INT
      Semigroup-via-Monoid given resolution.
      ORIGINAL PLAN: fix the remaining CI conformance failures separately from the crypto
      compile blocker. Repro from the same worktree after `bash install.sh --dev`:
      `scripts/conformance -- --no-memo` starts running but shows multiple pre-existing non-crypto clusters:
      INT actor/cluster tests print empty output while JS/JVM pass; JVM-only cluster/distributed/effect-imported
      tests print empty output; `http-client` returns `0`/empty and then stalls on a network-adjacent section.
      A single-case check `scripts/conformance -- --only js-crypto-extern-standalone --no-memo` also fails INT
      because `crypto-plugin.sscpkg` is staged under `bin/lib/compiler/plugin-available/` (advanced, opt-in),
      while the test is marked `backends: [int, js]`. Decide per case whether to auto-load the plugin, add an
      explicit plugin flag to the runner, or narrow/pending the conformance case to the backend it actually
      validates. 2026-07-07 targeted check: `scripts/conformance -- --only mcp-types` passes INT but JS fails
      with `SyntaxError: Identifier 'args' has already been declared` because the fixture's `val args` collides
      with the JS preamble `function args()` (tracked in BUGS.md `jsgen-toplevel-name-vs-preamble`). Narrow fix:
      rename that fixture local to `mcpArgs` so the MCP conformance case is not blocked by the known unrelated
      JS top-level-name bug; done in `2e1f2c287`, and
      `scripts/conformance -- --only mcp-types --no-memo` now passes INT/JS. Done-when: CI conformance job no longer expects environment-gated or opt-in-plugin behavior
      from the default `bin/ssc` launcher.
      UPDATE 2026-07-08 (`conformance-http-client-external-httpbin`): current
      `scripts/conformance -- --only 'http-client' --no-memo` returned five INT
      `503` statuses from live `https://httpbin.org` and then stalled in the JS
      lane. Reclassified this fixture with `pending:` because default conformance
      must not depend on an external network service. Follow-up: replace it with a
      local deterministic HTTP fixture before re-enabling. Remaining fresh
      deterministic failures after the p3-remaining-ten landing: `actors-supervision`
      INT, `effects` INT, `effect-transitive-handler` JVM, and JVM-only
      `cluster-connect` / `distributed-failure-*` / `distributed-heterogeneous` /
      `distributed-shuffle`.
      UPDATE 2026-07-08 (`conformance-actors-exit-os-shadow`,
      `conformance-effects-choose-one-shot`): INT cluster fixed in two shippable
      slices. `actors-supervision` root cause was lazy os-plugin `exit(code)`
      shadowing the core actor `exit(pid, reason)`; fix `96bf969ed` preserves the
      previous native fallback and makes OS `exit` report a usage mismatch for
      non-code arguments. `effects` root cause was a conformance source bug:
      `Choose` was declared one-shot despite the expected multi-shot handler; fix
      `edda7c5d3` declares `multi effect Choose`. Verification:
      `backendInterpreterPluginTests/testOnly scalascript.ActorSupervisionTest`,
      direct `bin/ssc run --v1` checks, and
      `scripts/conformance -- --only 'actors-supervision' --no-memo` /
      `scripts/conformance -- --only 'effects' --no-memo` pass INT/JS/JVM.
      Remaining known failures in this claim are JVM-only generated-Scala compile
      errors: `effect-transitive-handler` and the cluster/distributed cases where
      local values are inferred/emitted as `Any`.
      UPDATE 2026-07-08 (`conformance-jvm-cps-any-typing-and-effect-args`,
      `conformance-jvm-cps-local-unit-effect-cast`): fixed the remaining
      deterministic JVM-only slice in `df7cfb613`. Root causes: CPS continuations
      widened untyped vals from known constructors/defs to `Any`; effectful lambdas
      nested under call argument clauses could bypass CPS emission; and local
      actor-loop defs declared `Unit` cast unresolved `receive` computations to
      `Unit`, causing workers to exit before health-check replies. Verification:
      `scripts/sbtc "backendInterpreter/compile"`, `scripts/sbtc "installBin"`,
      direct `bin/ssc run-jvm tests/conformance/cluster-connect.ssc` prints
      `unhealthy nodes: 0`, and
      `tests/conformance/run.sh --only 'cluster-connect,distributed-failure-*,distributed-heterogeneous,distributed-shuffle,effect-transitive-handler' --no-memo`
      passes **6/6**. Next: run the full default conformance gate with the
      serverless wrapper and either mark this item done or record any newly exposed
      blockers before release.
      FULL-GATE BASELINE 2026-07-08: after `scripts/sbtc "installBin"` and the
      landed JVM CPS fix, `tests/conformance/run.sh --no-memo` reports
      **102 passed, 20 failed out of 122 tests (+2 pending)**. New blockers are
      recorded in `BUGS.md`: `conformance-js-json-stringify-missing-global`,
      `conformance-js-product-show-synthetic-tag`,
      `conformance-int-sql-block-scope`,
      `conformance-std-typeclass-int-jvm-gaps`,
      `conformance-jvm-std-ui-generated-braces`, and
      `conformance-int-variables-while-update`.
      Active-claim subslice plan, do not claim separately while
      `green-main-conformance-gating` is active:
      - [x] **conformance-js-json-stringify-missing-global** — smallest JS-only
            crash: `bin/ssc run-js tests/conformance/json-read.ssc` fails with
            `ReferenceError: jsonStringify is not defined`. Fix the JS global/import
            path or std-json JS intrinsic registration; verify with
            `tests/conformance/run.sh --only 'json-read' --no-memo`.
            FIXED 2026-07-08 in `718d04027`: JS JSON intrinsics now target the
            existing `_ssc_ui_jsonStringify` / `_ssc_ui_jsonValue` runtime helpers
            instead of undefined bare globals, and `JsGenStdImportTest` covers the
            bare intrinsic path. Verification: `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenStdImportTest"`,
            `scripts/sbtc "installBin"`, direct `bin/ssc run-js tests/conformance/json-read.ssc`,
            and `tests/conformance/run.sh --only 'json-read' --no-memo` (**1/1 green**).
      - [x] **conformance-js-product-show-synthetic-tag** — JS product rendering
            includes ADT/case-class synthetic tag indexes, breaking `prisms`,
            `optic-polish`, `optics-index-at`, and `optional`. Verify with
            `tests/conformance/run.sh --only 'prisms,optic-polish,optics-index-at,optional' --no-memo`.
            FIXED 2026-07-08 in `4e8cbb635`: JS runtime `_show` skips internal
            `_tag`, and positional `.copy(...)` skips `_type`/`_tag` when mapping
            arguments over product fields. Direct JS repros for `prisms` and
            `optic-polish` now match expected output; the affected conformance
            slice is **4/4 green**.
      - [x] **conformance-int-sql-block-scope** — INT SQL interpolation cannot see
            preceding Scala block vals (`newId`); verify `sql-basic,sql-transaction`.
            FIXED 2026-07-08 in `c31389b25`: `Denormalize` now re-parses parseable
            embedded `scala`/`ssc`/`scalascript` blocks after the CLI
            `Normalize -> Denormalize` backend path, so the interpreter executes the
            preceding Scala block and SQL bind expressions see its globals.
            Verification: `scripts/sbtc "sqlPlugin/testOnly scalascript.compiler.plugin.sql.SqlPluginInterpreterTest"`,
            `scripts/sbtc "installBin"`, direct `bin/ssc run --v1` for
            `sql-basic` and `sql-transaction`, and
            `tests/conformance/run.sh --only 'sql-basic,sql-transaction' --no-memo`
            (**2/2 green**).
      - [x] **conformance-std-typeclass-int-jvm-gaps** — INT `std-index` stack
            overflows after two lines; JVM typeclass aggregate imports miss exported
            helpers/`Left`/`Right`; verify `std-*` typeclass cases.
            FIXED 2026-07-08 in `f92d147b0` / `7328e35db`: INT dispatch now
            prefers real built-in members over same-named imported extensions,
            preventing `Option.map` recursion in std typeclass helpers. JVM
            codegen records imported type/extension metadata even across
            de-duplicated imports, imports standalone top-level extensions,
            preserves re-export provenance for std/index aggregate names, hoists
            uppercase type specs from mixed std imports into `object std`, and
            lowers explicit contextual instance args to Scala `(using ...)`
            calls. Std typeclass manifests now export/import their type names
            explicitly for strict import resolution. Verification:
            `scripts/sbtc "backendJvm/compile"`,
            `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenUsingTest"`,
            `scripts/sbtc "installBin"`, direct INT/JVM repros, and
            `tests/conformance/run.sh --only 'std-functor-applicative-monad,std-foldable-traversable,std-index,std-bifunctor,std-monaderror,std-selective' --no-memo`
            (**6/6 green**).
      - [x] **conformance-jvm-std-ui-generated-braces** — JVM `std-ui-extended*`
            generated Scala has an unmatched brace/EOF; inspect imported UI
            component object emission.
            FIXED 2026-07-08 in `9bd6cb87d`: `JvmGen` now preserves
            triple-quoted JavaScript/CSS literals while converting `object X:`
            blocks and while merging duplicate package/object blocks, so braces
            inside imported UI strings no longer close Scala objects early. The
            regression covers both a minimal duplicate-object source and the real
            `tests/conformance/std-ui-extended.ssc` directory import. Verification:
            `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenUsingTest"`
            (**14/14 green**); direct
            `bin/ssc run-jvm tests/conformance/std-ui-extended.ssc` after forced
            regeneration of stale local `std-ui*.scjvm`; and
            `tests/conformance/run.sh --only 'std-ui-aggregator,std-ui-extended*' --no-memo`
            (**5/5 green**). Follow-up cache invalidation risk tracked separately
            as `jvm-scjvm-cache-codegen-version`.
      - [x] **conformance-int-variables-while-update** — INT `variables` prints
            `sum=10` for the first while loop; inspect mutable var read-after-write
            inside interpreter while sequencing.
            FIXED 2026-07-08 in `4e67a2f41`: the closed-form while optimizer now
            bails when an accumulator RHS reads a counter that was assigned earlier
            in the same loop body, preserving ScalaScript's sequential assignment
            order. This keeps `x = x + 1; sum = sum + x` on the sequential loop path
            so `sum` sees the post-update `x`. Verification:
            `scripts/sbtc 'backendInterpreter/testOnly scalascript.SscVmTest -- -z "closed-form"'`
            (**6/6 green**); `scripts/sbtc "installBin"`; direct
            `bin/ssc run --v1 tests/conformance/variables.ssc`; and
            `tests/conformance/run.sh --only 'variables' --no-memo`
            (**1/1 green**).
      - [x] **jvm-scjvm-cache-codegen-version** — production cache follow-up found
            while fixing std-ui: `run-jvm` reused source-fresh `.scjvm` artifacts
            emitted by an older JVM backend, so the assembled CLI kept failing until
            `tests/conformance/.ssc-artifacts/std-ui*.scjvm` was removed. Tracked in
            `BUGS.md`. Done-when `.scjvm` freshness accounts for compiler/backend
            codegen version (or an equivalent invalidation signal) and a CLI
            regression proves stale source-fresh artifacts regenerate after the
            version changes.
            FIXED 2026-07-08 in `322ee868f`: JVM `.scjvm` artifacts now carry a
            `codegenVersion` cache key set by `JvmArtifactIO`, and
            `ModuleGraph.isJvmStale` invalidates source-fresh artifacts whose
            codegen key is missing or old. Legacy artifacts remain ABI-readable
            and regenerate instead of being reused. Verification:
            `scripts/sbtc "core/testOnly scalascript.artifact.ModuleGraphTest"`
            (**15/15 green**), `scripts/sbtc "cli/testOnly scalascript.cli.VerifyCliTest"`
            (**7/7 green**), `scripts/sbtc "installBin"`, and
            `tests/conformance/run.sh --only 'std-ui-aggregator,std-ui-extended*' --no-memo`
            (**5/5 green**). Next: run full default conformance with the
            serverless wrapper and either mark `green-main-conformance-gating`
            complete or record the next blocker before releasing the claim.
      FULL-GATE UPDATE 2026-07-08: after `322ee868f` / `4463a6117`,
      `tests/conformance/run.sh --no-memo` reports **121 passed, 1 failed out of
      122 tests (+2 pending)**. The only remaining blocker is
      `std-semigroup-monoid`, failing only on INT with expected lines 4-6
      missing (`Some(24)`, `42`, `foo`) while JS/JVM pass. Tracked in `BUGS.md`
      as `conformance-int-std-semigroup-monoid`.
      - [x] **conformance-int-std-semigroup-monoid** — final full-gate blocker:
            reproduce with `bin/ssc run --v1 tests/conformance/std-semigroup-monoid.ssc`
            and `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo`;
            inspect INT handling of std Semigroup/Monoid givens/extensions or
            imported typeclass dispatch; add a focused interpreter/std regression.
            Done-when direct INT output includes all expected lines, the targeted
            conformance slice is green across enabled backends, and the full
            default conformance gate is rerun.
            FIXED 2026-07-08 in `e571fd3ae`: INT concrete/parametric given
            registration now exposes parent typeclass aliases through
            `parentTypes`, so a `Monoid[Int]` given also satisfies a
            `Semigroup[Int]` demand. Root cause: `combineAllOption[A: Semigroup]`
            failed after the first three lines because `intSum` was only
            registered as `Monoid[Int]`; JS/JVM inherited Scala's subtype
            evidence behavior. Verification: direct
            `bin/ssc run --v1 tests/conformance/std-semigroup-monoid.ssc`;
            `scripts/sbtc "backendInterpreter/testOnly scalascript.FinalTaglessConformanceTest scalascript.GivenUsingTest"`
            (**17/17 green**); and
            `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo`
            (**1/1 green**). Next: rerun full default conformance; if green,
            mark `green-main-conformance-gating` complete and release the claim.
      FINAL GATE 2026-07-08: `tests/conformance/run.sh --no-memo` reports
      **122 passed, 0 failed out of 122 tests (+2 pending)**. No deterministic
      conformance blockers remain in this claim.

- [x] **green-main-full-sbt-test-gating** — fix the root `sbt "test"` gate after the
      `PluginCliTest` compile blocker. Repro: `cd /Users/sergiy/work/my/scalascript-wt-finish-green-main &&
      sbt "test"`. The first run hit a transient Scala 3 compiler crash in `clientEvm/Test/compile`;
      targeted `clientEvm/Test/compile` passed immediately. The second full run completed in 29:08 and
      confirmed `PluginCliTest` passes, but failed unrelated suites: `CrossBackendIntrinsicParityTest`
      (`webauthnConfigureStore`/`webauthnStoreRemove` JS-only drift; fixed in `8dfd2989e`),
      `JvmGenSwingRuntimeTest` (local helper resolved repo root as `v1`, fixed in `395e8aab3`),
      `StableSpiEnforcementTest` (`tcp-plugin` imported `scalascript.interpreter.Value` from a
      value-surface plugin; fixed in `484d56101`), `AgentConformanceTest` (`Address already in use`
      in `beforeAll`, fixed in `eae491e11`), plus
      Scala.js `loadedTestFrameworks` fallout after a Node non-zero exit. Remaining targeted blockers
      reproduced on 2026-07-07:
      `backendWasm/testOnly scalascript.codegen.WasmBackendTest` has 7 effectful-WASM failures
      (handler/resume, effectful `String*` mains, arithmetic/HOF effect bodies, cross-module effects);
      `v2PluginBridge/testOnly ssc.bridge.PluginBridgeTest` had one value-shape failure in
      `loadBackend` (`Long` vs `DataValue.IntV`, fixed in `7e2650e2c`); and
      `v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest` had one `mcp-types` failure
      (`user.name` blank; missing-field validation printed `no error`, fixed in `2e1f2c287`).
      Next slice: fix WASM effects, then re-check the Scala.js fallout, and only then rerun root `sbt "test"`.
      **2026-07-07 session ledger (claude takeover after codex stalled):** WASM effects FIXED
      (adopted codex's in-flight preserveTotalEffectfulReturnTypes, backendWasm 48/48, 9f04f8a29);
      jvmgen-block-call-empty-parens 3-bug chain FIXED (7bc09fffa — see BUGS.md, all 4 JVM-lane
      conformance repros green + SwiftUI 118/118 + JvmGen/Effect 193/193); runActors fat-jar
      family FIXED (a36e74fa0: cli dependsOn actorsPlugin + ActorInterp lazy-load seam —
      MultiNodeClusterTest 0/4→4/4, full cli/test 18-fail→5-fail); EmitScalaFacadeCliTest harness
      FIXED (bce70aaeb: -Dssc.lib.path derivation). REMAINING, precisely diagnosed in BUGS.md:
      `bytecode-shared-runtime-routes-unbound` (genRuntime gating emits _routes refs without defs —
      blocks the 5 facade tests + compile-jvm --bytecode) and `scalajs-jsenv-run-terminated`
      (node-26 jsEnv, 6 JS test modules, serial + CI). Root `sbt test` after those two = the gate.
      ACTIVE CLAIM PLAN 2026-07-08 (`green-main-full-sbt-test-gating` / codex):
      - [x] **bytecode-shared-runtime-routes-unbound** — fixed in `83fc339e2`. Reproduced with
            `scripts/sbtc "cli/testOnly *EmitScalaFacadeCliTest"` from this worktree;
            root cause was split `JvmGen.genRuntime` omitting `stubServeRuntime` when
            `Serve` was absent even though the always-included common/effects runtime
            references `_routes`, `route`, `onWebSocket`, and `_httpDoRequest`.
            Verified `backendInterpreter/testOnly scalascript.JvmGenRuntimeSeparationTest`,
            `installBin`, `cli/assembly`, `cli/testOnly *EmitScalaFacadeCliTest`, and
            `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo`.
      - [x] **scalajs-jsenv-run-terminated** — fixed in `1da48bfd5`. Serial repro
            `scripts/sbtc "cryptoNobleJs/test"` resolved to Node `MODULE_NOT_FOUND`
            for `@noble/ciphers/aes` because npm deps were never installed in clean
            worktrees/CI. Added idempotent `npmInstallForScalaJsTest` and wired it
            into `Test / loadedTestFrameworks` for `cryptoNobleJs`,
            `walletVaultEncryptedJs`, `walletStrategyErc4337Js`,
            `blockchainEvmAbiJs`, `walletConnectJs`, and `markupNode`.
            Verified those six suites plus `tests/conformance/run.sh --only
            'std-semigroup-monoid' --no-memo`.
      ROOT RETEST 2026-07-08: started `scripts/sbtc "test"` from
      `/Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating`.
      The PTY session was lost before the final sbt summary, so do not treat this
      as the authoritative complete failure list. Observed root-gate blockers were
      recorded in `BUGS.md` and must be reproduced targeted before coding:
      - [x] **root-test-command-registry-other-category** — fixed in `631ed8052`.
            Root cause: `VersionCmd` used the unclassified fallback-style
            category `Other`; `version` now appears under the existing `Help`
            bucket, preserving the registry test that catches future commands
            without explicit help grouping. Verified
            `scripts/sbtc "cli/testOnly scalascript.cli.CommandRegistryTest"`
            (**8/8 green**) and `tests/conformance/run.sh --only
            'std-semigroup-monoid' --no-memo` (**1/1 green**).
            Original repro: deterministic-looking
            `CommandRegistryTest` failure: `every command category is in the help
            ordering` reports `List("Other")`. First repro/fix because it is a
            narrow CLI test and not entangled with cluster timing:
            `scripts/sbtc "cli/testOnly scalascript.cli.CommandRegistryTest"`.
      - [x] **root-test-sealed-extension-option-dispatch** — fixed in `1e503de04`.
            Root cause: built-in `Option.orElse` accepted any single argument, so
            `Some(42).orElse(0)` returned the built-in receiver `Some(42)` before
            the user extension `def orElse(default: A): A` could run. Built-in
            `orElse` now handles only Option-valued alternatives; non-Option
            defaults fall through to extension dispatch. Verified
            `scripts/sbtc "backendInterpreter/testOnly scalascript.SealedExtensionDispatchTest"`
            (**4/4 green**), the filtered `InterpreterTest` built-in-priority /
            `option orElse` slice, and `tests/conformance/run.sh --only
            'option,optional,typeclass-extension,std-functor-applicative-monad,std-monaderror'
            --no-memo` (**5/5 green** on INT/JS/JVM). Original repro:
            `SealedExtensionDispatchTest` expected `42\n99`, got `Some(42)\n99`
            for the `Some` case.
      - [x] **root-test-cluster-cli-runtime-readiness** — fixed in `da63bb96a`.
            Root cause: after the v2 default switch, these v1 actor-cluster
            integration tests spawned node fixtures with `java -jar ssc.jar
            <node.ssc>`, so the node scripts ran on v2/default. Minimal fat-jar
            repro showed `sendAfter` actor flows print under `--v1` but exit 0
            with no delayed message under default/`--v2`; the v2 gap is tracked
            separately as `v2-actors-sendafter-cli-default-noop`. Harness fix:
            node fixture subprocesses now pass explicit `--v1`; CLI subcommands
            (`cluster status`, `cluster drain`, `cluster step-down`, etc.) still
            run normally against those nodes. Verified the expanded cluster
            slice `scripts/sbtc "cli/testOnly scalascript.cli.ClusterStepDownCliTest
            scalascript.cli.ClusterStatusCliTest scalascript.cli.ClusterAuthCliTest
            scalascript.cli.MultiNodeClusterTest scalascript.cli.ClusterBullyStatusConvergenceTest
            scalascript.cli.PartitionHealingTest scalascript.cli.SingletonFailoverTest
            scalascript.cli.ClusterDrainCliTest scalascript.cli.ClusterEventsCliTest
            scalascript.cli.PartitionTest"` (**13/13 green**) and
            `tests/conformance/run.sh --only 'actors*,cluster-connect,distributed*'
            --no-memo` (**14 passed, 0 failed**). Original repro: cluster CLI/runtime
            family: `ClusterStepDownCliTest`, `ClusterStatusCliTest`,
            `ClusterAuthCliTest`, `MultiNodeClusterTest`,
            `ClusterBullyStatusConvergenceTest`, `PartitionHealingTest`, and
            `SingletonFailoverTest` showed node bind/readiness/leader marker
            failures. Repro the family after the two narrow failures:
            `scripts/sbtc "cli/testOnly scalascript.cli.ClusterStepDownCliTest scalascript.cli.ClusterStatusCliTest scalascript.cli.ClusterAuthCliTest scalascript.cli.MultiNodeClusterTest scalascript.cli.ClusterBullyStatusConvergenceTest scalascript.cli.PartitionHealingTest scalascript.cli.SingletonFailoverTest"`.
      Done-when: run root `scripts/sbtc "test"` after both fixed slices are on the branch;
      if green, mark this gate done and release the claim. If red, record the next deterministic
      blocker in BUGS.md + SPRINT before fixing it.
      - [x] **root-test-v2-array-companion-foreign-sum** — fixed in
            `f6e6383ac`. New deterministic
            `V2ConformanceTest` blocker discovered after rebasing the jobpanel
            fix onto `origin/main@9e48204e5`: full
            `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest"`
            fails only `array-companion-statics` with
            `RuntimeException: __method__: no dispatch for .sum on <foreign>`.
            Targeted repro first: `scripts/sbtc "v2FrontendBridge/testOnly
            ssc.bridge.V2ConformanceTest -- -z array-companion-statics"` plus
            `tests/conformance/run.sh --only 'array-companion-statics' --no-memo`.
            Root cause: Array companion statics now intentionally return real
            `ForeignV(ArrayBuffer)` values for mutable arrays, but collection
            methods still only accepted Cons/Nil lists. Runtime fix: treat
            ArrayBuffer as list-like for read-only collection dispatch. Gates:
            targeted `array-companion-statics`, affected conformance, and full
            `V2ConformanceTest` are green.
      - [x] **root-test-sbt-aggregate-heap-oom** — root
            `scripts/sbtc "test"` on `origin/main@c9d300335` is now blocked by
            sbt/JVM heap stability, not a known deterministic v2 conformance
            failure. The run progressed through many suites, then printed
            repeated `OutOfMemoryError: Java heap space` from `pool-453`
            threads; the sbt JVM was non-responsive to `jcmd`, Ctrl-C did not
            stop it, SIGTERM only removed 47 node children, and SIGKILL was
            required. Work loop: identify whether this is root aggregate
            parallelism, Scala.js jsEnv node fan-out, or one leaking module; try
            bounded root-equivalent test invocation / focused module groups; then
            encode the stable production gate command or build setting. Done-when:
            a root-equivalent gate completes without heap OOM/hung sbt JVM and
            the command/result are recorded.
            Progress 2026-07-08 (uncommitted): a global sbt
            `Tags.Test` concurrency cap, env-overridable via
            `SSC_SBT_TEST_CONCURRENCY` and defaulting to 4, made the next root
            `scripts/sbtc "test"` complete in about 27m32s without the prior
            OOM/hung sbt JVM symptom. It still exited 1 because two later
            deterministic/root-runner blockers surfaced; fix those next, then
            rerun the root gate before marking this item fixed.
      - [x] **root-test-js-rowpost-runtime-contract** — new backendInterpreter
            blocker from the bounded root gate. Repro in root stream:
            `scalascript.JsGenStdImportTest` case `JS signal runtime defines the
            std/ui row-data natives` failed because generated JS did not contain
            `_RowPost` body payload resolution
            `body: resolvePayload(r, act.bodyField)`. Work loop: run focused
            `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenStdImportTest -- -z row-data"`;
            inspect `_RowPost`/`resolvePayload` runtime generation; either
            restore the real row POST body resolver or update the assertion if
            current code is semantically equivalent. Done-when: focused
            `JsGenStdImportTest` is green plus affected std/ui conformance.
      - [x] **root-test-cli-fork-exit-after-green** — new CLI aggregate blocker
            from the bounded root gate. Repro in root stream: `cli / Test / test`
            reported all CLI tests passed (488 succeeded, 0 failed, 19 canceled),
            then sbt failed because the forked `sbt.ForkMain` JVM exited 1.
            Work loop: reproduce with focused `cli/testOnly` suites starting
            from the last emitted CLI suite, then widen to `cli/test`; inspect
            late JVM/process cleanup and generated `v1/tools/cli/ssc-storage.json`
            rather than masking the fork exit. Done-when: `scripts/sbtc
            "cli/test"` exits 0 and the final root-equivalent gate no longer
            reports the CLI task failure.
            Progress 2026-07-08 (uncommitted): focused
            `ElectronJvmRestCliTest` is green with fork exit 0 after updating
            stale fake-Electron greps for the typed-route client signatures and
            fetch retry loop. Full `cli/test` no longer shows the old after-green
            fork exit; it now reports ordinary assertion failures below.
      - [x] **root-test-cli-toolkit-electron-duplicate-seqmap** — new full
            `cli/test` blocker after the fork-exit fix. Focused repro:
            `scripts/sbtc "cli/testOnly scalascript.cli.ToolkitElectronSmokeTest"`.
            Full-run symptom: Electron renderer throws
            `Uncaught SyntaxError: Identifier '_seqMap' has already been declared`,
            causing `SMOKE_FAIL initial render missing`. Work loop: inspect the
            generated toolkit Electron bundle and deduplicate/scope repeated JS
            helper preamble emission so `_seqMap` is declared once. Done-when:
            focused smoke test is green and full `cli/test` no longer reports it.
      - [x] **root-test-cli-spark-submit-dry-run-deps** — new full `cli/test`
            blocker after the fork-exit fix. Focused repro:
            `scripts/sbtc "cli/testOnly scalascript.cli.SubmitCommandTest"`.
            Failures: dry-run output no longer contains
            `org.apache.spark::spark-core:4.0.0` for the default Spark version
            nor `spark-core:3.5.1` for `--spark-version 3.5.1`. Work loop:
            inspect current `submit` dry-run output/contract; either restore the
            dependency strings/options or update the stale test expectations if
            the dependency surface intentionally moved. Done-when: focused
            `SubmitCommandTest` is green and full `cli/test` no longer reports it.
      Result 2026-07-08 (`cea0c3aed`): root gate is green. Fixes included a
      bounded root Test concurrency cap (`SSC_SBT_TEST_CONCURRENCY`, default 4),
      strict-mode-safe JS runtime helper emission for Electron/browser bundles,
      repeat-safe typed JSON facade bindings, updated typed route client smoke
      assertions, sharper `_RowPost` payload-resolver assertions, and Spark
      submit dry-run assertions against the generated package source. Verified:
      `scripts/sbtc "cli/test"` (554 succeeded, 29 canceled, 0 failed),
      `tests/conformance/run.sh --only
      'collections,dataset-from-file,dataset-shape,json-*,std-ui-*,tkv2-*'
      --no-memo` (19/19), and bounded root `scripts/sbtc "test"` (`[success]`
      elapsed 1668s / 0:27:48.0).

- [x] **green-main-plugin-cli-oslib-shadow** — fix the remaining `sbt test` CI blocker in
      `v1/tools/cli/src/test/scala/scalascript/plugin/PluginCliTest.scala`.
      Repro: `cd /Users/sergiy/work/my/scalascript-wt-finish-green-main && sbt "cli/Test/compile"` fails with
      `type Path is not a member of scalascript.compiler.plugin.os` plus missing `temp`, `read`, `write`,
      `makeDir`, etc. Root cause hypothesis: the test is in package `scalascript.compiler.plugin`, where the
      local `scalascript.compiler.plugin.os` package shadows os-lib's root `os` package. Qualify os-lib as
      `_root_.os` (or an explicit alias) inside the test, then rerun `cli/Test/compile` and the affected
      `cli/testOnly scalascript.compiler.plugin.PluginCliTest`. Done-when: the CI `sbt - compile and test`
      job no longer fails at `PluginCliTest.scala` test compilation.
      Result: fixed in `6d133361a` by qualifying os-lib as `_root_.os` inside `PluginCliTest`, avoiding the
      local `scalascript.compiler.plugin.os` package shadow. Verified:
      `sbt "cli/Test/compile"` and
      `sbt "cli/testOnly scalascript.compiler.plugin.PluginCliTest"` (8/8).

- [x] **green-main-markdownlint-policy** — make the Markdown lint job match the repository's actual historical
      documentation style instead of failing on legacy board/spec/changelog formatting. Current CI fails before
      useful validation on rules already violated broadly (`MD007`, `MD009`, `MD011`, `MD012`, `MD014`, `MD022`,
      `MD026`, `MD029`, `MD034`, `MD037`, `MD038`, `MD050`, `MD058`). Update `.markdownlint.json` rather than
      mass-reformatting durable project history. Done-when: `markdownlint '**/*.md' --ignore node_modules`
      exits 0 locally.
      Result: disabled the legacy-violated rules in `.markdownlint.json`; verified locally with
      `npx --yes markdownlint-cli '**/*.md' --ignore node_modules` (exit 0).

### Workflow polish (2026-07-06, Sergiy approved proposals 1-2)

- [x] **ws-1 workflow-verify-step**: THE WORKFLOW gains step 4b — run the affected
      conformance slice (`run.sc --only`) before every push; now cheap enough to require.
- [x] **ws-2 nightly-sanitizer** — installed (LaunchAgent io.scalascript.kill-stale-builders, daily 03:00, script copied to ~/.local/bin so any repo branch state is fine; kickstart-verified exit 0): scripts/install-build-sanitizer (idempotent crontab
      entry, 03:00 daily `kill-stale-builders --kill`) + installed on this host.


### Build-perf wave 2 (2026-07-06, Sergiy: "зроби усе що можеш")

- [x] **bp2-1 agents-workflow-banner**: AGENTS.md top-of-file THE WORKFLOW section
      (plan→sprint, worktree, claim, push-to-main, cleanup). (this commit)
- [x] **bp2-2 f4-batch-runner** — DONE (run-batch cmd; INT one-JVM, JS one-emit-JVM; identical results batched vs not on 22 cases; 6-case slice 36.1->15.2s with warm JVM lane default): `ssc run-batch --delim <s> <files…>` (one JVM runs many
      cases, delimiter-separated output) + run.sc uses it for the INT lane (one JVM instead
      of 193); JS lane: emit all sources in the same batch JVM, execute per-case in ONE
      node process via vm contexts. Measure before/after on a 20-case slice.
- [x] **bp2-3 env-heap-cleanup** — DONE (-Xmx12g removed from ~/.zshenv, backup kept): remove -Xmx12g from JDK_JAVA_OPTIONS in ~/.zshenv
      (backup kept); build-level heaps are explicit since bp-1.
- [ ] **bp2-4 ci-test-shard** — DEFERRED with verdict: hand-partitioning 259 modules is brittle and untestable locally; bp-5 classes-cache + pipelining (-25%) already cut CI compile; revisit only if CI wall-time still hurts after those land: split the CI `sbt test` job into parallel matrix shards
      by module groups.
- [x] **bp2-5 pipelining-measure** — MEASURED: clean cli-chain compile 34.5s WITH vs 46.3s WITHOUT usePipelining (-25% wall, CPU util 745% vs 577%); flag stays ON: one clean-compile A/B timing for usePipelining
      (document the number; revert flag if it turns out negative).
- [x] **bp2-6 exportjars-scope** — INVESTIGATED, NO CHANGE: warm touch-recompile loop through the 20-module chain is 13.2s with jars; toggling the flag invalidates zinc (A/B misleading); jar packaging is not the dominant term: measure whether ThisBuild/exportJars
      actually costs in the dev loop; scope or document.
- [x] **bp2-7 worktree-warm-targets** — INVESTIGATED, NEGATIVE: zinc analysis is absolute-path-bound — copied targets recompile anyway (57 modules, 34s = same as cold-with-pipelining). New-worktree cold cost is acceptable post-pipelining; do NOT build target-copying: zinc analysis stores absolute
      paths — verify whether target-copy into a new worktree survives; document verdict.

### Build-perf + conformance-perf sprint (2026-07-06, Sergiy directive: "запиши у спринт і зроби")

Build optimization (from the 2026-07-06 build audit: 259 modules, ~8s/31s CPU per cold sbt -batch
invocation, JDK_JAVA_OPTIONS=-Xmx12g inherited by every forked JVM, 2 orphaned sbt servers at 2.5GB
each, CI recompiles all modules, v2 parity harness rebuilds v2.jar per run):

- [x] **bp-1 test-heap-default** (= BACKLOG conformance-test-heap-default L1): explicit env-gated
      `-Xmx` for forked test JVMs in build.sbt (`SSC_TEST_XMX`, default 2g) so tests stop inheriting
      the ambient 12g; JMH/proguard pins stay. Verify: v2FrontendBridge suite green under 2g.
- [x] **bp-2 pipelining**: `ThisBuild / usePipelining := true` (sbt 1.10 + Scala 3.8 support it);
      verify full compile + suite; revert if zinc misbehaves.
- [x] **bp-3 worktree-server-hygiene**: scripts/new-worktree (and a new scripts/rm-worktree) kill the
      worktree's sbt server on removal; add scripts/kill-stale-builders for orphans.
- [x] **bp-4 v2-jar-cache**: v2/backend/check.sh caches v2.jar keyed by hash of v2/src/*.scala
      (skip scala-cli --assembly when unchanged).
- [x] **bp-5 ci-class-cache**: ci.yml caches **/target (classes+zinc) keyed by SHA with restore-keys
      so PR builds recompile only changed modules.
- [x] **bp-6 sbt-client-docs**: AGENTS.md note + scripts/sbtc thin-client helper (8s -> <1s per command).

Conformance-PERF (BACKLOG items, specs/conformance-perf.md):

- [x] **cp-1 conformance-affected-only (F1)**: `run.sc --only <glob|files>` so the fix-test loop runs
      just touched cases; full corpus stays for CI.
- [x] **cp-2 conformance-memoize (F2)**: skip cases whose (input, ssc.jar hash, expected) is unchanged
      since last green (cache file under target/); `--no-memo` escape hatch.
- [x] **cp-3 conformance-warm-runner (F3 subset)**: JVM lane compiles через warm bloop server instead
      of cold `--server=false` scala-cli per case; INT lane stays bin/ssc (already one JVM per case).
- [x] **cp-4** covered by bp-1 (same L1 item).


### ▶ v1→v2 migration (2026-07-03 — planned, not started)
Spec: `specs/v1-to-v2-migration.md`

Three phases — execute in order, each phase gated by the previous:

- [x] **Phase 1: restructure** — DONE 2026-07-03. `git mv lang/ → v1/lang/`, `runtime/ → v1/runtime/`,
      `tools/ → v1/tools/`. Updated `.in(file("..."))` paths in `build.sbt` (75 entries). Also updated
      `install.sh`, `scripts/runtime-bench.sh`, `tests/perf/{coldstart,serverrss}/run.sh`, and 3 CI
      workflows. `sbt compile` green, `ssc run examples/hello.ssc` prints `Hello, World!`.
- [x] **Phase 2a: v2 sbt module** — DONE 2026-07-03. Added `lazy val v2Core = project.in(file("v2/src"))` to `build.sbt`; added `v2Core` to root aggregate. `sbt "v2Core/compile"` green (5 sources, 4 s). `//> using` scala-cli directives in `v2/src/project.scala` are valid Scala comments, silently ignored by sbt.
- [x] **Phase 2b: v1-plugin bridge** — DONE 2026-07-03. `V2PluginRegistry` added to `v2/src/Runtime.scala`
      (fallback in `Prims.resolve` before throwing). `v2/plugin-bridge/` sbt module created;
      `PluginBridge.loadAll()` ServiceLoader-discovers v1 `Backend` plugins, extracts `NativeImpl`
      intrinsics, translates `v2Value ↔ v1Value` (scalars + DataV/InstanceV/List/Option/Tuple),
      registers wrapped handlers with `V2PluginRegistry`. 22 tests green. Non-bridgeable: `InlineCode`,
      `RuntimeCall` (compile-time only), `BlockForm` effect runners (deferred). Spec original description
      (shift/reset SPI) is a later phase; this bridges the existing NativeImpl surface first.
- [x] **Phase 2c: v2 JVM backend** — DONE 2026-07-03; TCO fixed 2026-07-03.
      `v2/backend/jvm/JvmBackend.scala`: reads Core IR (S-expression text), emits a self-contained
      Scala 3 source file. When compiled with `scalac` and run with `java`, produces byte-identical
      output to `ssc run-ir`. 29/29 pass (all conformance + all 23 v2 examples incl. `tco.coreir`
      — 1M tail calls complete without stack overflow via `@tailrec def`). Preamble handles all
      Core IR constructs + full prim set. TCO: global self-tail-recursive defs → `@tailrec def`;
      single-lam LetRec self-tail-calls → `@tailrec def`; mutual LetRec → closure vars (no trampoline).
- [x] **Phase 2c: v2 JS backend** — DONE 2026-07-03. `v2/backend/js/JsBackend.scala`:
      reads Core IR S-expr, emits a self-contained .js file. Trampoline TCO ($tco/$c),
      full prim set, ADTs as {t,f}, cells as arrays, maps as wrappers. All 5 conformance
      fixtures + 15 kc examples pass (output identical to ssc run-ir); 100k-deep TCO ok.
- [x] **Phase 2c: v2 Rust backend** — DONE 2026-07-03. `v2/backend/rust/RustBackend.scala`:
      Core IR → self-contained Rust source via `scala-cli run v2/backend/rust/`. 29/31 bench corpus
      pass (2 failures are pre-existing ssc1c IR bugs that also fail the v2 VM). Key: forward-ref
      cells (`__fwd`) for all global Lam defs (self/mutual recursion), 256MB thread for deep
      tail recursion, `v_sconcat` handles any Data++Data (Pair++Pair→Tuple4).
- [x] **Phase 2d: full checklist** — DONE 2026-07-03. Verification pass results:
      • JVM: 5/5 conformance (fact/letrec/map/tco/thunk), 3/3 bench corpus (arith-loop/recursion-fib/list-fold) — PASS. TCO verified (tco.coreir = 500000500000 without stack overflow).
      • JS: 5/5 conformance, 2/2 bench corpus (arith-loop/recursion-fib) — PASS. Trampoline TCO correct.
      • Rust: 29/31 bench corpus PASS. 2 failures = known ssc1c IR bugs (bool-predicate/@count global, mutual-recursion) — both also fail the v2 VM. See BACKLOG: v2-ssc1c-globals-bug.
      • sbt v2Core/compile: SUCCESS (5 sources, 4 s).
      GOTCHA: macOS `echo` processes `\n` as a real newline (unlike Linux). Use `program > file` (redirect) or `printf '%s\n' "$var"` when writing backend output to files. The generated Scala/Rust code contains literal `\n` in preamble strings; `echo "$VAR"` corrupts them silently.
- [x] **Phase 3: switch** — RECONCILED 2026-07-09: the actual CLI default switch
      landed in `v2-prod-default-switch` (`719943f40`, `d2ba78c0a`,
      `89a38f1e3`) and the stale duplicate queue row `p4-default-flip` was
      closed on 2026-07-08. Plain `ssc run <file>` defaults to v2; `ssc run
      --v1 <file>` remains the rollback path; `ssc run --v2 <file>` remains the
      explicit force flag. Historical planning notes below are kept for context.
      Original:
      CLI default → v2; `ssc --v1` escape hatch retained.
      - [x] **`ssc run --v2` flag** DONE 2026-07-05 (`RunV2.scala`, `feature/v2-cli-run-flag`): additive
        preview flag routing a source through the v1 frontend → FrontendBridge → v2 VM (default runner
        unchanged). `ssc run --v2 examples/hello.ssc` == v1 output. Makes v1-vs-v2 output parity checkable
        from the CLI; the eventual default-switch builds on this.
      - **OUTPUT-PARITY FINDING (for Track 4 / conformance):** `examples/algebraic-effects.ssc` exits 0 on
        v2 (PASS in the exit-0 coverage harness) but prints DIFFERENT output than v1 (v2: `List() / 1 / …`
        vs v1: `0 / 10 / 11 / List(11,21,…) / done / (42,…)`). The 96.4% exit-0 coverage OVERSTATES real
        compat; the Phase-3 gate needs an **output-equality** check. First concrete effects-semantics gap
        found this way — a v2 VM effects divergence, not a bridge/flag bug (the flag mirrors `bridgeCli`).

### ▶ v2 full compatibility (2026-07-03 — Track 1 through 5)
Spec: `specs/v2-full-compat.md`
Goal: v2 handles ALL v1 programs with full language features + performance parity.
Phase 3 (CLI switch) is gated on this entire track completing.

**Track 1 — v1 IrExpr → Core IR (foundation — do first)**
- [x] **T1.1: FrontendBridge** — DONE 2026-07-03. `v2/frontend-bridge/` sbt module created.
      `FrontendBridge.scala`: scalameta → Core IR via de Bruijn scope (List[String]), convertExpr/convertMatch/convertPat.
      `ModuleBridge.scala`: walks Module sections → scalameta stats → FrontendBridge.
      BridgeCli `run`/`run-module`/`emit` commands.
      Gate met: unit tests (12 pass) + examples via `sbt "v2FrontendBridge/run run-module"`.
- [x] **T1.2: NormalizedModule → Program** — DONE (ModuleBridge.convert). Gate met: hello.ssc runs.
- [x] **T1.3: CLI wiring** — DONE via BridgeCli `run-module`. Gate met: `sbt "v2FrontendBridge/run run-module examples/hello.ssc"` prints `Hello, World!`.
- [x] **T1.4: Examples verification (core language)** — DONE 2026-07-03 (2a828e9f1).
      Pure-language examples passing: hello, functional, enums, data-types, typed-data, bitwise-operators, extensions, default-params.
      Key fixes: extension methods (Defn.ExtensionGroup), for-do loops (Term.For), nested ctor patterns (flat flattenPattern/shiftLocals),
      `->` operator, String+Int concat, String*Int repeat, __isTag__ prim, __unsupported__ global.
      Plugin-dependent examples (effects, actors, async, algebra, dsl-*-with-std-imports): EXPECTED FAIL (require T2.1+).
      Remaining pure-language items: algebraic-effects.ssc (needs `handle` keyword), generators.ssc (generators plugin).
      Gate: 8/8 pure language examples pass; 0 unexpected failures.

**Track 2 — Plugin parity**
- [x] **T2.1: BlockForm effects** — DONE 2026-07-04. All 7 effect plugins (Logger/State/
      Random/Clock/Env/Retry/Cache) wired to v2 via V2EffectContext ThreadLocal + PluginBridge.
      Three fixes needed: (1) FastCode global-lookup paths bypass V2PluginRegistry → added
      lookupGlobal fallback to all 3 paths; (2) FrontendBridge emitted block args as eager
      Seq → added Lam(0) thunk wrap for statement blocks (lambdas detected by
      `Block(List(Function|AnonFn))` heuristic); (3) `__arith__` unknown-op catch-all for
      `effect Logger:` declaration prims. Gate: runLogger+runLoggerToList+runState all correct.
- [x] **T2.2: HTTP/SQL intrinsics** — DONE 2026-07-04. httpPlugin+sqlPlugin added to
      v2PluginBridge deps; NativeImpl registration now also registers as v2 global ClosV
      (env-as-arglist) so App(Global(name), args) resolves correctly. Fixed raw-arg
      conversion: NativeImpl expects unwrapped primitives (String/Long/Boolean) not v1 Value
      objects — added v2ToRaw/rawToV2 helpers (mirrors Interpreter.unwrapValueAsAny).
      Gate: `httpGet("https://httpbin.org/get")` returns HTTP 200 Response with JSON body.
- [x] **T2.3: Actors (spike)** — DONE 2026-07-04. VirtualThread-per-actor model implemented in
      PluginBridge: spawn/receive/self/exit/runActors registered as v2 globals; `!` wired via
      __arith__ → actor.send. Fixes: (1) v2 Match non-DataV scrutinees fall through to default arm
      instead of erroring (needed for `case s: String => ...` on StrV); (2) @timeout cell registered
      as ForeignV so cell.set works; new FastCode path in Runtime.scala also needed lookupGlobal
      fallback; (3) exit() needs dead flag (interrupt alone races with LinkedBlockingQueue.take if msg
      already present); (4) 2-arg globals (exit) need arity=2 (v2 App is non-curried n-arg).
      Gate: examples/actors-pingpong.ssc passes all checks (ping-pong, timeout-None, timeout-Some,
      exit+ignored message, done).

**Track 3 — Performance parity**
- [x] **T3.1: Baseline benchmarks** — DONE 2026-07-03. All 22 bench programs run through v2 bridge.
      Key correctness fixes in this session: vector-index (list O(n) indexed access), array-update
      (Array factory + ForeignV apply), map-ops (Map.updated/getOrElse/apply), streams-pipeline
      (Bench.opaque identity stub + Range.to list), lazylist-take (LazyList stored as ForeignV Scala LazyList),
      typeclass-monoid (Bench.opaque), Either/Option methods, Int.toInt/toLong.
      typeclass-fold: DEFERRED (requires summon[T] typeclass dict-passing — T2 scope).

      | program          | v1 (ms)  | v2 bridge (ms) | ratio |
      |------------------|----------|----------------|-------|
      | arith-loop       | 0.244    | 6.1            | 25×   |
      | nested-loop      | 0.256    | 31.6           | 123×  |
      | recursion-fib    | 1.22     | 257            | 211×  |
      | list-fold        | ~0.5     | 16.5           | 33×   |
      | recursion-tco    | ~0.5     | 10.9           | 22×   |
      | mutual-recursion | ~1       | 81.2           | 81×   |
      | string-concat    | ~1       | 13.6           | 14×   |
      | hof-pipeline     | ~0.1     | 0.93           | 9×    |
      | pattern-match    | ~2       | 194            | 97×   |
      | literal-match    | ~0.3     | 2.4            | 8×    |
      | option-chain     | ~0.1     | 2.8            | 28×   |
      | either-chain     | ~0.1     | 3.2            | 32×   |
      | range-sum        | ~0.1     | 1.2            | 12×   |
      | tuple-monoid     | ~0.5     | 407            | 814×  |
      | vector-index     | 1.14     | 258            | 226×  |
      | bool-predicate   | ~0.1     | 1.8            | 18×   |
      | map-ops          | ~0.3     | 2.7            | 9×    |
      | array-update     | ~4       | 347            | 87×   |
      | instance-field   | ~0.5     | 8.4            | 17×   |
      | streams-pipeline | ~0.02    | 0.20           | 10×   |
      | typeclass-monoid | ~0.01    | 0.07           | 7×    |
      | lazylist-take    | ~1.5     | 181            | 121×  |

      Top gaps: tuple-monoid 814× (++ creates new tuples via trampoline), recursion-fib 211× (each call
      traverses trampoline), vector-index 226× (O(n) list traversal instead of O(1)), array-update 87×
      (each a(idx)=x is __assign__ → ArrayBuffer update — could FastCode), nested-loop 123×, lazylist-take 121×.
      Root cause: v2 FastCode is ~25-100× slower than v1 JIT for arithmetic loops (JVM lambda call overhead
      vs JIT-compiled bytecode); no v2 JIT yet.
      Gate: baselines recorded ✓. Top gaps identified.
- [x] **T3.2a: FastCode phase 1** — DONE 2026-07-04. `ClosV.fcEntry` (direct body call, no trampoline
      Done alloc per call), `tryFCValue` (Float-safe arm body FC via `Prims.arithOp` instead of FLC-first),
      `tryFC(Match)` (full arm dispatch: armMap O(1) lookup, field binding, avoids Done allocs from match),
      `tryFLC(App)` uses `fcEntry` (direct call when callee is simple), `cell.set resolveArg` with compile-time
      fcEntry fast path + pre-allocated sharedArgEnv (safe: bodyFC is synchronous, no trampoline).
      Results (v1 baseline → v2 before → v2 after):
      | program          | v1 (ms) | v2 before | v2 after | ratio |
      |------------------|---------|-----------|----------|-------|
      | pattern-match    | ~2      | 194       | ~22      | 11×   |
      | instance-field   | ~0.5    | 8.4       | ~3       | 6×    |
      | list-fold        | ~0.5    | 16.5      | ~1.4     | 2.8×  |
      | recursion-tco    | ~0.5    | 10.9      | ~2.5     | 5×    |
      | nested-loop      | 0.256   | 31.6      | ~20      | 78×   |
      | mutual-recursion | ~1      | 81.2      | ~18      | 18×   |
      | tuple-monoid     | ~0.5    | 407       | ~15      | 30×   |
      GOTCHA: sharedArgEnv unsafe in tryFLC(App) for Runtime.run path (trampoline aliases env=argEnv,
      recursive fns corrupt it) — use `.clone()` or fresh array for the fcEntry=None branch.
      GOTCHA: tryFC(While) regressed nested-loop 19.6→22ms despite fewer allocs (JVM JIT unfavorable
      code shape) — left reverted.
      Gate: T3.2 ongoing. Still above 5× on several programs.
- [x] **T3.2b: FastCode phase 2** — INVESTIGATED 2026-07-04; architecturally blocked for numeric benchmarks.
      Progress (committed 53b39b05a, 8b62517ae):
      - DataV.fields: Vector→IndexedSeq + ArraySeq hot paths: tuple-monoid 26→22ms (~15%).
      - tryFC(While) case: nested while loops FC-compilable (nested-loop unchanged, inner FC dominates).
      - Carrier opt in tryFCMutual (dead code, pass 1b was removed — direct JVM frames > trampoline).
      Current state (v2 FC interpreter vs v1):
        arith-loop: ~5ms vs 0.244ms = 21× | nested-loop: ~35ms vs 0.256ms = 137×
        tuple-monoid: ~22ms vs 2.06ms = 10.7× | mutual-recursion: ~31ms vs 1.35ms = 23×
      ROOT CAUSE: FC interpreter closure dispatch ~10ns/op vs v1 JIT ~0.5ns/op; fundamentally
      blocked until v2 has a bytecode JIT backend. Remaining gap analysis:
      - tuple-monoid: needs Let scalarization (detect Ctor++Ctor Let binding, inline field accesses
        bypassing DataV creation entirely); no-tuple baseline 14ms → target ~14ms = 6.8×.
      - mutual-recursion: trampoline already optimal (pass 1b re-enabling was 4% slower — 1001 JVM
        frames > trampoline with EA). No practical fix without JIT.
      - arith-loop/nested-loop: LongCellV dispatch overhead; needs JIT.
      T3.2b gate (5× max) NOT achievable without v2 JIT. Closing as investigated.
- [x] **T3.3: v2 JVM backend quality** — DONE 2026-07-04; Long-cell specialization ships.
      Fixes: safeName() appends 'x' to trailing-_ identifiers (Scala3 parse error);
      `__arith__` added to prim3 dispatch; Long-cell specialization (lcell.new(intLit) →
      `var name: Long`, lcell.get/set → direct read/assign, __arith__(Long,Long) → inline).
      MEASUREMENT: arith-loop before=43ms/op, after=0.53ms/op = 80× speedup; within 2× of
      native Scala (0.6ms/op). Gate (within 2× of v1 JVM backend) ACHIEVED for arithmetic loops.
      Conformance fixtures (fact=120, tco=500000500000) still correct.
      Non-arithmetic programs (using __method__ dispatch) still go through prim dispatch.
- [x] **T3.4: v2 Rust backend ownership/perf** — FULLY COMPLETE 2026-07-05
      Phase 1 (feature/v2-rust-ownership-perf): (1) Data(Rc<str>, Rc<Vec<V>>) ADT deep-copy fix:
      list-fold 140.8→8.2ms (17×); (2) SelfRecNative fn(i64)->i64: recursion-fib 107.5→1.37ms (78×).
      Phase 2 (feature/v2-rust-backend-ownership): LCell direct-ownership + inline arith:
      (a) lcell.new not captured by Lam → `let mut name: i64` (no Rc<RefCell> overhead);
      (b) lcell.get/set on longVar → direct i64 read/assign; (c) while condition inline
      (genBoolExpr) + assignment (genIntExpr) avoid all V boxing; (d) genStmt for While body
      and Seq intermediates eliminates V::Unit creation in hot loops.
      Result: arith-loop 100M iters: v2=16ms vs v1-native=16ms (1.0× — gate MET).
      All 8 fixtures × 3 backends GREEN (feature/v2-rust-backend-ownership, merged 55be1ea94).

**Track 4 — Full compatibility verification**
- [x] **T4.1: All examples** — UPDATED 2026-07-05: **176/178 PASS (98.9%)** via
      `feature/v2-frontend-bridge` merge (merged 7277dfaa0).
      Previous: 129/178 (72.5%); added OIDC batch stubs (discoverAs/exchangeAuthorizationCode/
      http.parseUrl/makeLocalhostGetResp), Mirror.Of[X] synthesis, Defn.Object→__mk_method_obj__,
      general typeclass derivation (Tc.derived(mirror)), mcpConnect fake client,
      String.take/drop/takeRight/dropRight in Runtime, OidcHelpers.findByIssuer,
      userInfo fallback to first user, BatchCli resetState() per example.
      Remaining 2 FAIL: x402-cardano*.ssc — eager `throw RuntimeException(...)` before `getOrElse`
      evaluates; requires real Blockfrost API keys (unfixable without real credentials or CT semantics change).
      Gate (0 failures): deferred — 2 unresolvable external-API examples are hard floor.
- [x] **T4.x measurement slice** — DONE 2026-07-05 (`feature/v2-t4-verification`):
      compat-coverage RE-RUN: **176/178 = 98.9%** (was 129/178) — the content-toolkit,
      Spark/Dataset-dispatch and plugin-method clusters are all FIXED; the only 2 FAILs
      are environmental (missing BLOCKFROST keys). `v2/compat-baseline.md` updated.
      Server-shaped examples (x402-server, ws-chat, webauthn-demo) PASS under the
      bridge, partially covering T4.3's intent.
- [x] **T4.2: Stdlib plugins** — DONE 2026-07-05. All `v1/runtime/std/*.ssc` files are
      library modules (YAML frontmatter + exports, no standalone executables). Their plugin
      behavior is exercised by the 176/178 passing BatchCli examples (actors, http, auth,
      effects, content, crypto, etc.). The 40 failures in `backendInterpreterPluginTests/test`
      are pre-existing v1-interpreter Scala tests (not `.ssc`). Gate (0 stdlib-related .ssc
      failures under v2): MET — no stdlib library broke the bridge examples.
- [x] **T4.3: Full application** — DONE 2026-07-05. `examples/v2-http-sql-demo.ssc`:
      HTTP client (httpGet → status=200) + H2 in-process SQL (CREATE TABLE, 3 INSERTs,
      SELECT with row iteration) both work end-to-end under v2 bridge.
      Key fixes: (1) `__method__` dispatch for DataV singleton objects (Db, Http) now
      checks `V2PluginRegistry.lookup("Tag.method")` BEFORE effect-Op fallthrough —
      Db.execute/Db.query were silently returning lazy Free-monad Ops; (2) FrontendBridge
      `parseDatabasesFromFrontmatter` registers H2 connections from YAML frontmatter;
      (3) v1→v2 InstanceV field ordering uses registered field-name order (Response.status
      at index 0); (4) H2 returns uppercase column names — demo uses `row("ID")`/`row("MSG")`.
      GOTCHA: `__method__("Op", IndexedSeq())` (empty-field DataV) was the effect-Op path;
      plugin singletons also have empty fields — fix is registry-first lookup.
      Output: `SQL results: 1: Hello from v2! / 2: SQL works... / 3: H2...`; HTTP status: 200.
- [~] **T4.4: Conformance suite** — INSTRUMENT BUILT + BASELINED 2026-07-05
      (`feature/v2-t44-conf2`, adopting orphaned in-flight work from
      `.worktrees/feature/v2-t44-conformance`): **V2ConformanceTest** runs
      `tests/conformance/*.ssc` through FrontendBridge → v2 VM and diffs stdout against
      `tests/conformance/expected/` — TRUE output-equality (vs the batch runner's
      exit-0). BASELINE: **22/58 succeeded**, 36 failed, 57 skip-listed (actors/async/
      dataset/network). Failure clusters (self-describing via breadcrumbs):
      default-params (unbound default exprs), tuple extension methods
      (Tuple2.bimap/leftMap/rightMap), effects output shape, json-*/optics/parsing/sql
      std families. NEXT: work the clusters largest-first; also merged: DataV FIELD
      access dispatch (function-typed fields callable) before the Stub fallback.
      Run: `sbt "v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest"`.
      **UPDATE 2026-07-05 (Sergiy relay): score 94/138 → 103/138 (+9).** All 35 remaining failures are
      **plugin-gated** (no v2 bridge registered for the feature): actors, cluster, distributed, coroutines,
      html-dsl, http-client, node, rest-validate, mcp-client.
      **WAVE 6 (2026-07-05, PR #73 merged):** batch-conformance fixes forward-ported to main — all string
      interpolators as concat (html/sql/f), qualified ctor fillDefaults, object val/method CDefs,
      Signal[T]→ClosV, scope/raw/attr stubs.
      - [x] **v2-conf-pure-gated** — DONE 2026-07-06 (`feature/v2-conf-pure-gated`, PR #75).
        **html-dsl**: full tag DSL in PluginBridge (div/p/ul/li/a/h1-h6/em/strong/nav/img/hr + void tags);
        `attr` NamedMethodObj with cls/id/href/title/src/alt/… + `:=` AttrKey operator; `raw(s)`;
        v1Show `_Raw` DataV pass-through. Runtime: `:=` in `__arith__` dispatches via `NamedMethodObj.getField`;
        tuple-spreading in map/flatMap for 2-param lambdas `(a, b) => …` on tuple lists.
        **rest-validate**: thread-local error accumulator via `validate { }` + requireString/requireRange/
        requireRangeDouble/requireOneOf; `reqLookup` reads case-class fields via `lookupFieldNames`.
        Conformance: 59→60/61 (mcp-types pre-existing); skipSet −2. (webauthn-server-verify was already passing.)
      - [ ] **v2-conf-env-gated** (NOT this slice) — actors/cluster/distributed/coroutines/http-client/ws/tls:
        environmental (non-daemon threads hang the JVM, or need real network/multi-node). Needs the v2 actor
        runtime + network bridging; a sibling/env concern, deliberately deferred here.
      - [x] **t44-pr72-summon-using-integration** — DONE 2026-07-07 (salvage merge of PR #72).
        VERDICT after full review: the branch's summon/using layer (`__rt_summon__`/`__reg_given__`/cb-params)
        was a PARALLEL EARLIER implementation of main's landed dict-passing (`defContextBounds`/
        `givenByTcHead`/`__resolve_given__`) — main won every overlapping hunk (all 31 FrontendBridge
        conflicts → main; branch's DataV-based optics stripped as dead vs main's PluginBridge optics).
        Salvaged: String `indexOf`/`lastIndexOf` char+from overloads, `matchPrefix`, char-predicate
        `filter/forall/exists`, `__match_fail__` prelude def + prim (was an UNBOUND global — failed
        matches crashed with an opaque unknown-global error), batch-path `V2EffectContext.peek`
        alignment, Show pretty List/Tuple. Gate: V2ConformanceTest 63/3-preexisting-tkv2 — identical
        to pure origin/main; v2PluginBridge 22/22.
### ▶▶ v2-replaces-v1 — remaining work to close the true output-parity gap (2026-07-06)

TRUE parity is **11/47 ≈ 23%** (not the exit-0 96%), per `v2/output-parity-baseline.md`. Roadmap to raise it,
prioritised by leverage. Verify each with `SSC="bin/ssc" scripts/v2-output-parity --all` after `sbt installBin`.

- [x] **v2 parity fixes — 7 landed 2026-07-06, parity 11→16/46 (23%→35%).** FrontendBridge (`feature/v2-main-entry`)
      + VM (`feature/v2-foldlt-double`).
  - [x] **VM: tryFLC-over-Double corruption (broad correctness).** `tryFLC` reads a `Local` optimistically as
    Long and returns `0L` for a `FloatV`; unguarded fast paths therefore corrupted Doubles: ordering `<`/`>`
    inside a fold/loop compared `0<0`→false (foldLeft over Doubles returned the LAST element — min/max broken,
    `imports`), and `__arith__` Double `/` compiled `0L/0L`→`ArithmeticException` (`dsl-ast-builder`). Guarded
    both fast paths with `flcProvablyLong`; Double operands fall back to the general Double-aware ops. This is
    broad — any Double reduction/comparison/division in a loop across the whole corpus.
  - [x] **user `def main()` wins over html tag globals** (main/label/title/form/…) — was shadowed; broke every
    `def main()`-entry + `def label(…)`-style program (`_Raw("<main></main>")` / `_Raw("<label>…")`). data-types ✅.
  - [x] **`main()` called even alongside top-level stmts** (entry was either/or). default-params ✅.
  - [x] **Mirror.elemTypes real field types** (String/Int) not `Any`. custom-derives-mirror ✅.
  - [x] v2 now invokes user `def main()` — was skipped because the html `<main>` tag plugin-global shadowed
    it (FrontendBridge:784 collision-skip); excepted `main`. `def main()=println(x)` now runs on v2. Fixes
    every `def main()`-entry program that had ONLY the entry-invocation bug.
  - [x] `default-params` **FIXED** — the entry logic was either/or: `if entryStmts.nonEmpty ... else if main`,
    so a program with BOTH top-level defs (case-class/enum default params emit entry stmts) AND `def main()`
    never called main(). Now always appends the `main()` call after entry stmts (v1 semantics). default-params
    byte-identical v1==v2.
- [x] **real v2-only V2-ERROR gaps** RECONCILED 2026-07-09 — stale 2026-07-06 list;
  the current production gate after `cdd032f03` + `70969362f` has **0 v2-error**:
  `64/98 identical · 11 mismatch · 0 v2-error · 23 v1-only`
  (26 both-fail, 36 true-server, 33 backend-lane, 2 nondet, 195 total).
  Historical list was: `content-form-submit`,
  `content-live-rows`, `content-slot`, `ui-fetch-json` (FrontendBridge parser: `'=>' expected but '('`),
  `ui-remote-table`, `graph-codecs`, `typed-object-codec` (codec/derives), `object-store-jdbc`,
  `spark-schema-mapping` (Op-execution — sibling `corpus-tails`), `uuid-v7` (uuid native, non-det).
- [x] **17 mismatches** RECONCILED 2026-07-09 — stale 2026-07-06 bucket;
  the current full gate has 11 mismatches, none currently classified as a new
  v2-error blocker in this slice: `async-parallel-demo`, `distributed-streams`,
  `dsl-calc-parser`, `effects`, `graph-neo4j-storage`, `lang-split`,
  `mcp-server-protected`, `oauth-mcp-full-stack`, `os-env`, `scala-js-demo`,
  `streams`. Historical bucket was: SQL/Spark/content/rails `Stub`/`Op`
  (sibling corpus-tails), effects shape, derives/mirror (`String|Int`→`Any|Any`),
  quoted macros (`TermSplicedMacroExprImpl`), `validate` language form.
- Coordination: `PluginBridge` html-dsl/rest-validate is claude-sonnet-4-6 (`v2-conf-pure-gated`); Op-execution
  is `corpus-tails`. I own FrontendBridge entry/parser/derives + the harness.

- [~] **v2-plugin-native-registration** (Option B — split from `v2-corpus-tails`; holds `PluginBridge.scala`) —
      register plugin natives the PluginBridge ServiceLoader loop skips (`BuiltinsRuntime` builtins /
      `RuntimeCall` / `InlineCode`) so `unbound global` examples run on v2.
      - [x] **filesystem builtins DONE 2026-07-06** (`registerFsBuiltins`): mkdirs/mkdir/writeFile/appendFile/
        readFile/deleteFile/exists/listDir. `fs-roundtrip` v2-error→MATCH (parity 27→28/52); conformance 59/59.
      - **Remaining are NOT simple native registration (engine/bridge, hand to corpus-tails owner):**
        `validate {}` is a language special form (EvalRuntime/Typer special-case) → needs FrontendBridge
        desugaring; html-dsl needs the `attr` DSL + `renderTag` port; `uuidV7` is non-deterministic (no parity
        win). PluginBridge released after this — corpus-tails may resume it.
- [x] **v2-output-parity-full-corpus** (Option C) DONE 2026-07-06 — `scripts/v2-output-parity --all` sweeps all 193
      examples (auto-skips 130 server/actor/dataset). **Authoritative: 30/63 = 48% output-identical** (22 mismatch,
      11 v2-error). See `v2/output-parity-baseline.md`. The real "does v2 replace v1?" number vs 96.4% exit-0.
- [x] **v2-parity-current-errors** DONE 2026-07-09 — refreshed the production
      output-parity gate after toolkit-v2 completion, fixed the two deterministic
      v2-error layers exposed by the fresh sweep, and reconciled stale broad rows.
      Current gate: `64/98 identical · 11 mismatch · 0 v2-error · 23 v1-only`
      (26 both-fail, 36 true-server, 33 backend-lane, 2 nondet, 195 total).
      Active plan 2026-07-09 (`feature/v2-parity-current-errors` / codex):
      - [x] Restage the CLI in this worktree with `scripts/sbtc "installBin"`
            because `scripts/v2-output-parity` uses `bin/ssc`.
      - [x] Run `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all`
            and record the exact counts in `v2/output-parity-baseline.md` and
            `specs/v2-full-compat.md`.
            Fresh result before fixing: `62/93 identical · 7 mismatch ·
            6 v2-error · 18 v1-only` (31 both-fail, 36 true-server,
            33 backend-lane, 2 nondet, 195 total). The cleanup path is canceled:
            all six v2-error rows are standard-`scala`-fence examples skipped
            by v2 (`BUGS.md` `v2-standard-scala-fences-skipped`).
      - [x] If the gate still has 0 v2-error and only the already-classified
            non-blocker mismatches, mark the stale broad SPRINT rows
            `real v2-only V2-ERROR gaps`, `17 mismatches`, and the superseded
            full-corpus duplicate as reconciled/superseded with the fresh
            counts. Done above with the 2026-07-09 full gate counts.
      - [x] If a new v2-error or clear v2-regression mismatch appears, stop the
            cleanup path, file a `BUGS.md` entry with the exact repro, and fix
            the first narrow deterministic blocker with affected conformance.
            Finding: `v2-standard-scala-fences-skipped` filed; fix the standard
            Scala fence extraction first.
      - [x] Fix `FrontendBridge.extractCode` / source extraction so standard
            `scala` fences that are the document's runnable source are included
            in the v2 program, without re-enabling illustrative Scala snippets
            in mixed ScalaScript docs. Landed in `cdd032f03`.
      - [x] Add focused regression coverage: a minimal markdown `scala` fence
            through `FrontendBridge.convertSource`, plus a real-harness CLI or
            parity check for `examples/cluster-capability.ssc`.
            Gates before push: `v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest`
            (17/17), `tests/conformance/run.sh --only 'standard-scala-fence' --no-memo`
            (INT/JS/JVM pass), `scripts/sbtc "installBin"`, and a minimal
            real-harness `bin/ssc run --v1/--v2` standard-`scala`-fence repro.
      - [x] Re-run targeted parity for the six affected standard-Scala-fence
            examples after `cdd032f03`. Result: `1/6 identical · 4 mismatch ·
            1 v2-error`; `graph-storage.ssc` now matches, `cluster-capability.ssc`
            reaches a distinct `unbound global: clusterOf` v2 error, and the
            other four now produce non-empty v2 output mismatches instead of
            silent empty programs.
      - [x] Fix the newly exposed `v2-cluster-stdlib-import-gap`
            (`BUGS.md`): inspect `runtime/std/cluster/index.ssc` import/export
            lowering, reproduce through `bin/ssc run --v2 examples/cluster-capability.ssc`,
            add focused import-boundary regression coverage, and make the
            targeted parity row match. Landed in `70969362f`; the root cause was
            missing v2 actor-cluster globals plus `__methodOrExt__` falling back
            to the shadowing case-class method global before plugin method dispatch.
      - [x] Re-run the full output-parity gate and record the new counts in
            `v2/output-parity-baseline.md` and `specs/v2-full-compat.md`.
            Result after `installBin`: `64/98 identical · 11 mismatch ·
            0 v2-error · 23 v1-only` (26 both-fail, 36 true-server,
            33 backend-lane, 2 nondet, 195 total).
      - [x] Update `CHANGELOG.md` and release the claim/worktree.
            CHANGELOG is updated in the bookkeeping commit; claim/worktree release
            follows after this commit lands.
      Done-when: the board no longer advertises stale old parity blockers and
      the current production gate is either green-by-scope or has a concrete
      bug/fix commit for the first newly exposed blocker.
- [x] **~~v2-output-parity-full-corpus~~ (superseded)** — RECONCILED 2026-07-09:
      the full-corpus harness shipped earlier as `v2-output-parity-harness` and
      the current production gate was refreshed by `v2-parity-current-errors`.
      Latest recorded gate after `installBin`: `64/98 identical · 11 mismatch ·
      0 v2-error · 23 v1-only` across 195 examples; see
      `v2/output-parity-baseline.md` and `specs/v2-full-compat.md`.
      Original:
      extend `scripts/v2-output-parity` to the full 193
      examples with server/actor timeout handling for the authoritative "N/193 output-identical" number
      (current sample: 28/52 terminating). Does NOT touch PluginBridge.
- [x] **v2-output-parity-harness** DONE 2026-07-05 (`scripts/v2-output-parity`, `feature/v2-conf-pure-gated`) —
      runs each example on v1 (`ssc run`) AND v2 (`ssc run --v2`) and diffs stdout → per-example MATCH/
      MISMATCH/V2-ERROR + parity %. Point `$SSC` at an assembled `ssc` for a fast full-corpus run.
      **RE-MEASURE 2026-07-06 — still 27/52** after conformance 22→59/59 GREEN + batch 144→154/193: the
      conformance/exit-0 gates did NOT move real `examples/` output-parity. `v2/output-parity-baseline.md`
      now has per-example v2-error ROOT CAUSES for the `v2-corpus-tails` owner (unbound `uuidV7`/`mkdirs`/`ws`
      plugin natives; `ui-fetch-json` parser gap; `index` path bug; default-params + jdbc/spark silent-empty).
      Suggest gating corpus-tails on this harness, not just exit-0/conformance.
      **FULL SWEEP 2026-07-05 — 52 terminating examples: 27/52 = 52% output-identical** (16 mismatch,
      9 v2-error). Details + divergence clusters in `v2/output-parity-baseline.md`. The exit-0 coverage
      (96.4%) massively overstates real compat. Biggest lever: SQL/Spark/content/rails plugin natives return
      `Stub`/`Op` on v2 instead of executing. Also: effects shape, derives/mirror (`String|Int`→`Any|Any`),
      quoted macros unsupported, 9 empty-output errors. (`os-env`/`uuid-v7` mismatches are v2-fine, not bugs.)
      Runner: `sbt installBin` now stages the v2 classes (since `cli dependsOn v2FrontendBridge`), so
      **`bin/ssc run --v2` works natively** — use `SSC="bin/ssc" scripts/v2-output-parity …` for fast sweeps.
      **First sample (4 pure examples): 2/4 identical.** Surfaced two real v2 output divergences (exit-0 but
      wrong output — the gap the 96.4% coverage hides): `algebraic-effects` (effects output shape) and
      **`custom-derives-mirror`** (v1 prints union `String|Int`, v2 widens to `Any|Any` — a derives/mirror
      type-handling bug). Both are v2-VM/bridge semantics for Track-4 conformance to fix. NEXT: assemble `ssc`
      and run the full 193-corpus for the authoritative "N/193 output-identical" number.

**Track 4 (cont.) — T4.4 conformance waves**
      WAVE 1 DONE 2026-07-05 (`feature/v2-t44-clusters`): given-nested extensions with
      per-name RECEIVER-TAG dispatch (Bifunctor[Tuple2] vs [Either] coexist); v1Show
      display parity for bridged println (tuples/(a,b), List(...), raw strings,
      integral doubles); **ALL-fences entry semantics** — suite 22 → **32/58**.
      HONESTY CORRECTION: first-fence-only had inflated batch coverage; full-fence
      honest = **152/193** (see compat-baseline.md). The ~32 newly-honest batch fails +
      26 suite fails = the visible next queue (json/optics/parsing/sql/effects
      clusters). WAVE 2 (2026-07-05): **applyFallback SHIPPED** — bridged v1 facade
      objects (NamedMethodObj: json wrapJson etc.) are applicable via their `apply`
      field at all 7 App sites; json-value: crash → near-identical output (remaining:
      rendering a facade's INNER value as `Map(k -> v)` inside containers — add a
      `raw`-field-aware branch to v1Show). WAVE 3 (2026-07-05): **default-params SHIPPED**
      (raw-term registry + call-site wrapper Lam/Let so defaults see earlier params;
      suite 33/58). WAVE 4 (2026-07-05): **optics SHIPPED** — Focus
      path-lens extraction from lambda AST (fields/.some/.index/.at) + NamedMethodObj
      optic runtime + variant Prisms; lenses/optional/prisms PASS (suite **36/58**).
      WAVE 5: OPTICS CLUSTER COMPLETE (5/5 — runtime .copy positional/mixed
      by ACTUAL tag, field-application s.users(1), optic labels via _show; suite
      **39/58**). WAVE 6: PARSING CLUSTER COMPLETE (multi-line imports joined; as-pattern/named/
      typed catch-alls -> general chain; PHANTOM WILDCARD BINDING removed — a fake "_"
      shifted default-arm bodies by one, the -1 AIOOBE class; entry-val hoisting guard;
      method-obj globals win over zero-arg Ctor; matchPrefix intrinsic; suite **42/58**,
      corpus 153/193). WAVE 7: CONTEXT-BOUND DICTIONARY PASSING (trailing __tc_ params, explicit-instance
      passthrough, __resolve_given__ witness tables, tc-hierarchy walk) + extension
      SELF-RECURSION fix (member beats extension inside impl bodies — std monad
      instances hung). std-semigroup/index/functor + tagless-context-bounds PASS;
      suite **46/58**. WAVE 8 (2026-07-05/06): **T4.4 COMPLETE — suite 59/59 GREEN** (was 22). using-clauses,
      Free-monad Op lifting (effects x3 without CPS), String.toInt kernel parity,
      facade raw display + LinkedHashMap order, direct vars, Enum.values, object-method
      defaults/varargs, REAL try/catch (BridgeThrow carries the value), qualified case
      patterns, sql/transaction fenced blocks (JDBC H2, fail-soft drivers). Corpus
      155/193 (record). Regression discipline: full-history bisection worktree; two
      systemic fixes (Op application lift; lossless Signal round-trip). Remaining:
      optic-polish (runtime `.copy` on DataV), parsing/sql/effects clusters, v1Show
      facade-INNER rendering (json-value's last line).
- [~] **v2-bridge-last-gaps** — PARTIAL 2026-07-05 (2 waves): **trapExit + link/monitor
      SHIPPED** (full Erlang supervision surface on the VirtualThread mailbox model:
      bidirectional links kill-or-message, monitors get Down(reason); death fires on
      completion/crash/kill) + **mapreduce stdlib AUTO-INJECT** (v1 auto-available
      symbols; index.ssc chain pulls the family). The 3 distributed examples now run
      DEEP into the real stdlib and fail further along: word-count at a String+Int `-`
      inside the mapreduce code (suspect: a bridged field/method returning String where
      Int expected — find via arithOp breadcrumb); wire-protocol/shuffle at
      `expected a list, got Stub` (an unbridged `__method__` on a data value hits the
      batch Stub fallback — identify the method, bridge it). STILL OPEN: (b)
      `registerBehavior` typed-actor registry; (d) Dataset typed codecs (`Op/3`).
      WAVE 3 (2026-07-05 evening): **ambient effect ops (Random.uuid/int/double,
      Clock.now/nanos) + asInstanceOf identity + Stub/arith breadcrumbs SHIPPED** —
      join/log-aggregation/streams PASS; every remaining failure is self-describing.
      SHARPENED ROOT: all 6 remaining real FAILs are ONE surface — unbridged
      Dataset/typed-data plugin methods (DatasetCodec.*, DatasetWire.*,
      DistributedDataset.runShuffle, WorkerProtocol .collect/.toList) fall to the
      free-monad Op sentinel → Stub chains. RESOLVED 2026-07-05 night
      (`feature/v2-typeddata-bridge`): probing against the REAL v1 interpreter showed
      the whole remaining set is OUT OF PARITY SCOPE — the 4 dataset files are
      `backend: jvm` codegen examples (v1 does NOT interpret them), word-count and
      actors-typed-remote-spawn fail on the v1 interpreter too, pg/x402 are env-gated.
      **v1-INTERPRETER PARITY REACHED on the examples corpus.** Optional follow-up
      track (not parity): run the `backend: jvm` examples through the Phase-2c JVM
      source generator with the typed-data jars.
      Batch counts: FIXED 2026-07-05 night — per-file registry snapshot/restore in batchCli; deterministic 184/193.
- [x] **T4.5: hang-list ELIMINATED** — DONE 2026-07-05 (`feature/v2-t45-hanglist`).
      All 16 entries terminate (probe with per-file forked watchdog); the true batch
      killer was a bridged v1 `exit` (System.exit) shadowing the actor exit. Fixed:
      `Runtime.exitHandler` hook (batchCli intercepts; exit-0 = PASS), polymorphic
      variadic exit (actorRef → kill actor; code → hook), registerActors() last in
      loadAll. **Coverage: 186/193 = 96.4% of the FULL corpus, zero skips** (was
      176/178 + 16 skipped). Remaining 5 real FAILs: registerBehavior, trapExit ×2,
      runDistributed, Dataset Op/3.

**Track 5 — ssc1c fixes**
- [x] **T5.1: @count/@sum bug** — DONE. TWO independent root causes, one per pipeline,
      both fixed:
      (a) 2026-07-04, FrontendBridge pipeline: Rust backend eagerly evaluated
      `prim __math_obj__` at startup (`def math = prim __math_obj__` prelude) → `panic!`;
      fix = lazy stub closure in RustBackend.
      (b) 2026-07-05, ssc1c pipeline (`feature/v2-ssc1c-globals-bug`): the
      expression-position `"assign"` case in `lowerE` (`v2/lib/ssc1-lower.ssc0`) only
      looked up `@name` — it missed `@@name` LongCell vars (introduced by
      v2-arith-loop-jit), and `lookupVar`'s IrGlobal fallback then emitted a bogus
      `(global @count)` (byte-verified in the emitted IR). Statement-position assigns were
      correct; only assigns inside `if`-then branches (expression position) broke. Fix
      mirrors the statement-position logic (`lookupVarOpt` on `@@name` → `lcell.set`,
      else `@name` → `cell.set`).
      Gate met on the ssc1c pipeline: bool-predicate (243) + mutual-recursion (1000)
      correct on VM + JVM + JS + Rust (see `v2/backend/check.sh`); conformance green.
- [x] **T5.2: JS backend 64-bit ints (BigInt)** — DONE 2026-07-05, found while verifying
      T5.1: `v2/backend/js/JsBackend.scala` emitted plain JS numbers for `i.*`, so any
      program with real 64-bit overflow — the corpus LCG anti-fold idiom — silently
      computed WRONG values on JS (bool-predicate: 6 instead of 243; arith-loop/
      recursion-fib stay under 2^53 so Phase 2d missed it). Fix: ints are BigInt
      end-to-end (literals `Nn`; `i.add/sub/mul/neg/shl` wrapped in `BigInt.asIntN(64,…)`;
      shift counts masked `&63n`; string/array index sites bridged via `Number(…)`;
      `slen`/`scodeAt`/`arr.len`/`scmp`/`map.size` return BigInt; conversions
      `i->str`/`i->f`/`f->i`/`i->big`/`big->i`/`big->f`/`big->str`/`f->str`/`tagOf`/`arity`
      added — they previously hit the `$prim` throw; `$strToI`/`$sfromCodes` fixed;
      match-error `JSON.stringify` → `$show` since stringify throws on BigInt).
      NOTE: JS bench numbers will regress (BigInt is slower than doubles) — correctness
      first; a hybrid small-int fast mode is a future perf item.
      Also fixed: `backend/js/project.scala` lacked `//> using file ../../src/CoreIR.scala`
      (JsBackend only compiled when extra sources were passed by hand).
- [x] **T5.3: backend parity harness** — DONE 2026-07-05: `v2/backend/check.sh` runs every
      `conformance/*.coreir` + the bool-predicate/mutual-recursion IRs through
      run-ir vs JVM vs JS vs Rust; outputs must be byte-identical. ALL GREEN
      (7 fixtures × 3 backends). (Phase 2c/2d verification was manual — nothing guarded
      the three generators until now.) Three more generator bugs it caught, all fixed:
      (a) the Rust backend never printed a non-Unit entry result (VM `Main.out`
      semantics; bench programs print explicitly so 29/31 hid it) — added
      `show_entry` (strings quoted) + entry match; (b) `tco.coreir` (1M non-TCO frames)
      overflowed the 256MB thread — stack bumped to a 2GB virtual reservation; real
      trampoline TCO queued as **v2-rust-backend-tco** in BACKLOG; (c) post-merge with
      the 2026-07-04 T3.3 Long-cell specialization: JvmBackend emitted bare `_asLong(...)`
      at generated top level but the helper was `private` inside `object R` — ssc1c-emitted
      `i.add` prims on Long-cell vars (vs FrontendBridge's inlined `__arith__`) exposed it;
      top-level `_asLong` added to the preamble.
- [x] **T5.4: VM sconcat fast-path regression** — DONE 2026-07-05, found chasing the last
      bench SKIP: `string-concat` crashed the VM with `sconcat: bad types` — the
      `Prims.resolve2` fast path (added by v2-arith-loop-jit) shadowed the general prim
      table's lenient `sconcat` (`anyStr(a)+anyStr(b)` coercion, i.e. `"item-" + n`) with
      a strict Str+Str-only version. Fast path now mirrors the general table. bench.sh
      masked the crash as `SKIP(no-main)` — with T5.1+T5.4 the corpus is a true **31/31**
      (string-concat = 188890 verified on VM + JS + Rust).
- [x] **T5.5: kc5 type-error conformance probe was wrong** — DONE 2026-07-05 (pre-existing
      FAIL on origin/main, the ONLY red conformance check): the probe used `1 + "a"`, which
      is LEGAL Scala (string concat "1a") and KC5-micro correctly lowers it to sconcat, so
      ssc1c rightly does not reject it. Probe changed to a genuinely ill-typed `1 - "a"`
      (checker: `- requires Int right operand`). conformance now fully green: 634 ok / 0 FAIL.

**Track 6 — WASM unblock (new 2026-07-05)**
- [x] **v2-wasm-unblock** — ✅ DONE 2026-07-05 (`feature/v2-wasm-unblock`): `rustup target add
      wasm32-wasip1` installed; `v2/ssc0-wasm` launcher (Rust backend + Node built-in WASI
      host, `v2/scripts/run-wasi.mjs`); quicksort byte-identical to VM, tco = 1e6 tail calls
      in constant stack, Mira programs work via the same target; toolchain-gated conformance
      checks added. The historically-only-open v2 language backlog item is CLOSED. Original
      plan below: — `rustup` is now present in this environment. Try
      `rustup target add wasm32-wasip1`; if it installs, the v2 Rust backend output can
      target WASM (v2/ROADMAP K3 "reuse the Rust backend"). Runtime: check `wasmtime`/
      `wasmer`; if absent, Node's built-in WASI (`node:wasi`) is a candidate host.
      Gate: one conformance program (e.g. quicksort.ssc0) compiled via
      `ssc0-rust → rustc --target wasm32-wasip1` runs under a WASI host with output
      identical to the VM.

**Track 7 — empirical baseline + coverage instrument + correctness bugs (addendum 2026-07-05)**
> Grounding for Tracks 1/4/5 from a two-agent audit of the *current* state (ran the real
> `examples/*.ssc` corpus through ssc1; audited plugin-bridge + JVM backend). Three findings:
> - **Measured baseline.** The self-hosted **ssc1** frontend runs **1 of 194** real
>   `examples/*.ssc` cleanly (only `hello.ssc`). It is a *toy-example runner*, not a v1 runtime.
>   (This is the ssc1 path — the **FrontendBridge** path of Track 1 is the compat road and is now
>   far ahead: T1–T2 DONE, 8/8 pure-language examples.)
> - **Strategic confirmation.** Do **not** grow ssc1's parser to chase example coverage — that is
>   Track 1's job. ssc1/Track 5 is for the pure self-hosted story only (a `.ssc` on all 3 backends
>   with no JVM v1 tree). Keep the two goals separate so neither agent duplicates the other.
> - **plugin-bridge is a scaffold, not E2E-functional** on its own; Track 2 wired the real path
>   (BlockForm effects + HTTP/SQL) through FrontendBridge instead.

- [x] **T7.1: compat-coverage harness + baseline snapshot** — DONE 2026-07-05.
      `scripts/v2-compat-coverage` wraps `ssc.bridge.batchCli` (one JVM, whole corpus) → PASS/FAIL
      + coverage %. Baseline committed in `v2/compat-baseline.md`. **Post Track-1+2 baseline: 129/178
      ran = 72.5% (129/194 = 66.5% of the corpus)** — up from 1/194 (0.5%) via the ssc1 path. The 49
      fails: ~7 environmental (no network/keys), ~42 real, clustered in content-toolkit run-context
      (~10), Spark/Dataset free-monad (~8), and plugin-object method dispatch (Graph/SQL/vault).
      FOLLOW-UP (next slices, ranked in the baseline doc): content-toolkit context → Dataset executor
      → method-dispatch breadth. Harness enhancement: diff stdout vs v1 (output-equality, not just exit).
- [x] **T5.6: numeric-poly i.* prims everywhere** — DONE 2026-07-05
      (`feature/v2-ssc1-float-toplevel`). The VM's general table was already numeric-
      polymorphic (numBin/numCmp); the resolve2 fast paths and all THREE source
      generators were inconsistent patchworks (`7.5 / 2.5` crashed `expected Int`;
      i.le/ge/gt/eq Int-only). Aligned: VM resolve2 + resolve1 i.neg; Rust v_i* 4-case
      poly; JVM div/mod/neg via _numBinI; JS $n* helpers (bigint wrapped / float
      number math, $show Scala-style floats). New floatnum.coreir fixture (parity 8×3
      GREEN) + examples/kc-float.ssc gate via ssc1c. The sconcat/T5.4 lesson,
      systematically applied.
- [x] **T5.7: ssc1 top-level statements** — DONE 2026-07-05 (same branch).
      lowerProg now collects top-level expression statements in document order into the
      entry (Seq(exprs…, main() if present)); top-level `val (a, b) = e` (tuppat) emits
      value defs ($vd + _sel__K accessors). Prelude `_sel_until`/`_sel_to` rewritten
      TAIL-recursively (old shape stack-overflowed on `(1 to 10000)`); `_sel_toList`
      added. Parser: `parseBlockArg` parses `{ (a, b) => stmts… }` lambda-header-FIRST
      (val/def stmts inside block-lambda bodies work; foldLeft block-args were 0-arity
      thunks → arity crash); plain top-level val consumes trailing block args.
      GATE MET: examples/recursion.ssc prints all 13 outputs via ssc1 (Collatz 871/178,
      100k-deep mutual recursion, destructuring val, block-lambda, interpolation).
      conformance 639 ok / 0 FAIL; bench 31/31; parity 8×3 GREEN.


### ▶ agent-sdk P3b + conformance (2026-07-03 — roadmap #2 next slice)
Remaining work on agent-sdk-remainder: MCP round-trip test + mock gateway + golden transcripts.
Spec: `specs/agent-sdk.md`. The MCP bridge (`runtime/std/agent-mcp.ssc`) is done in both directions;
what's missing is an end-to-end test that runs both sides.

- [x] **agent-mcp-roundtrip-test** — DONE 2026-07-03. `AgentMcpRoundTripTest.scala` (3 tests, all
      green): contentJson round-trip, isError propagation, multiple tools. In-process
      LinkedBlockingQueue transport; mirrors McpEndToEndTest. Spec: `specs/agent-mcp-roundtrip.md`.
- [x] **agent-mock-gateway** — DONE 2026-07-05. `AgentConformanceTest.scala`: a fake gateway
      (in-process HttpServer) replays a recorded FIFO sequence of model responses; 3 golden
      transcripts (tool-use loop, multi-turn, error path) assert run STRUCTURE. 3/3 green. No
      `agent.ssc` change needed — the loop's only seam is the endpoint URL, so the mock is a Scala
      test fixture (the spec's suggested `ModelClient` injection seam does not exist; not invented
      for a test). Complements the content-keyed `AgentSdkInterpreterTest`; adds the multi-turn case.

### ▶ v2 bench performance (2026-07-03 — slow programs in v2 VM) [arith-loop DONE]
v2 bench shows several programs 100-500× slower than the main interpreter. Target the biggest gaps
with ssc1c optimizations (better IR generation) or v2 VM fast-paths.

- [x] **v2-arith-loop-jit** — `arith-loop` 258ms → 17ms (15× speedup, < 20ms target ✓).
      Root cause: tight counter loop in v2 VM does 20+ JVM allocations/iter (Done boxing, IntV boxing,
      env-array extension per letrec bounce). Fixes implemented end-to-end:
      1. `Term.While` + `Term.Seq` in CoreIR — Java while-loop, no trampoline per iter; Seq = same env for all terms.
      2. `IrWhile`/`IrSeq` in `ssc1-lower.ssc0` — replaces letrec-based while; assign chains use IrSeq (no _blk_ env extension).
      3. `FastCode`/`FastLongCode` in Runtime.scala — Value-returning closures (no Done boxing); FLC = Env => Long (no IntV boxing for cond/body).
      4. `LongCellV(var v: Long)` in Value — mutable long cell; `lcell.new/get/set` primitives; `@@name` scope prefix for int-lit vars.
      5. `resolve1/2/3` in Prims — avoids `List[Value]` alloc for 1/2/3-arg prims.
      6. Empty App fast-path: `Call(c, emptyEnv)` instead of `toArray` on empty list.
      **Result:** arith-loop 258ms → ~15-17ms; nested-loop similarly under 20ms.
- [x] **v2-recursion-opt** — DONE 2026-07-05 (`feature/v2-recursion-opt`).
      **recursion-fib 65.7 → 8.2 ms = 8.0×** (same flags BENCH_WARMUP=10 REPS=15, same
      machine state, A/B vs origin/main). Design: **SelfRecLL** (`v2/src/Runtime.scala`) —
      an arity-1 self-recursive def whose body is pure Int arithmetic over `Local(0)`,
      Int literals and DIRECT self-calls in NON-TAIL (operand) position compiles to a
      plain JVM `Long => Long` (zero allocation, no trampoline/Done/global-lookup per
      call; knot tied via a captured var). A bare tail-position self-call BAILS — tail
      recursion keeps the trampoline's constant-stack TCO (Core IR invariant 7);
      recursion-tco is unaffected. Non-Int args fall back to the generally-compiled body.
      Covers `i.*` and `__arith__` shapes + the ssc1c `<=`-desugar (`if (i.eq..) true
      (i.lt..)` Bool-ifs in `goB`). Wired in `compileWithGlobals` pass 1 (both `code`
      and `fcEntry`). Verification: conformance 634 ok / 0 FAIL; `backend/check.sh` 7×3
      ALL GREEN; bench corpus **31/31 no SKIP**; 10 var-heavy programs byte-compared
      old-vs-new (identical outside the map-ops fix below).
      **BONUS — critical corruption fix found en route** (BUGS.md
      `v2-cellset-flc-corruption`): the FastCode phase-1/2 batch (2026-07-04) made
      `tryFLC` optimistic (App/cell.get/arr.get/fieldAt/Local coerce non-Int → 0L),
      which broke the `cell.set` FLC fast path's "tryFLC fails for non-Int" assumption —
      `m = m.updated(k, v)` stored `IntV(0)` over a Map (map-ops crashed
      `expected Map, got 0`; silent corruption possible in the general case). Fix:
      `flcProvablyLong` structural gate — `cell.set` takes the FLC path only for
      provably-Long bodies. map-ops restored: 124750 correct, 0.56 ms.
- [x] **v2-pattern-match-opt** — RE-SCOPED + CLOSED 2026-07-05. Fresh baseline
      **82–88 ms** (was 362 pre-FastCode; the old number is obsolete). Source is
      Float-typed (`area(s): Double`, `var total = 0.0`) → the Long-cell/FLC tier and
      SelfRecLL cannot apply; remaining cost is diffuse (closure foreach dispatch +
      match arm dispatch + FloatV boxing + generic-cell read/write per element), which
      is exactly the ~10 ns/op FC-dispatch floor T3.2b measured — JIT-gated. The one
      concrete non-JIT lever is a symmetric **Float-cell specialization tier**
      (`dcell.*` analog of LongCellV/FLC) — queued in BACKLOG as
      **v2-float-cell-fastpath** (cross-cutting: kernel prims + ssc1c lowering + all 3
      backend generators must learn dcell.*).

### ▶ ui-fetch-credentials — outbound credentials have no concept, and the documented pattern bakes the secret (2026-08-04)

Two rozum reports, **specced as one** because the reporter says the first folds into the second:
`ui-fetch-credentials` (design proposal, `impact: fyi`) and `std-auth-client-half` (`impact:
blocks`). Spec: [`specs/ui-fetch-credentials.md`](specs/ui-fetch-credentials.md).

**Verified, not accepted on report:** `TuiEmitter.emitSignalSeed` writes each signal's initial value
as a Rust literal, so a headers signal built from `env("TOKEN")` at emit time puts the token in
`src/main.rs` and in the binary. `tui-fetch-headers` (landed today) is what made terminal
authentication possible, so the framework currently makes the wrong thing the easy thing.

- [x] **the warning** — `fetchUrlSignal`'s contract in `v1/runtime/std/ui/primitives.ssc` now says
      the headers signal's INITIAL VALUE is emitted as a literal. Not the fix; the notice that the
      fix has not happened.
- [ ] **the decision** — four questions the spec states and does not answer: where `Credential`
      lives relative to `std.auth`; whether the three existing shapes migrate additively or in one
      cut (`std/agent`'s `authToken: String` is public API); what each target runtime may read, with
      "unsupported here" allowed only as a compile error rather than an empty string; and whether a
      release build should refuse `Credential.literal`.
- [ ] **the implementation** — `Credential` names a source (`env` / `file` / `literal`) and is
      resolved by the TARGET runtime at call time, which is what makes baking impossible rather than
      discouraged. Then `std/http` verbs, `std/ui` fetches and `AgentEndpoint` take one, and
      `std/agent`'s two hand-built `"Bearer " + token` sites — six lines apart in one file — become
      one call.

### ▶ release-v0-1-0 — the tag exists, the workflow cannot build it (2026-08-04)

**State: tag `v0.1.0` is pushed and points at `7afcb808c`.** The release commit itself is done and
gated — version `0.1.0-SNAPSHOT → 0.1.0` in `build.sbt` and in the dependency coordinate
`Main.scala` emits, verified by `ssc-tools --version` printing `ssc 0.1.0`, with smoke-ci 58/58 and
conformance 645/0 on the tagged tree.

**Blocked by** [`tests/BUGS.md`](tests/BUGS.md) `native-release-blocked-by-testutils-clean-compile`:
all three `Qualify` jobs fail in the sbt build step and `Publish qualified tag` is skipped, so no
release has ever been published (the only earlier run, 2026-07-28, failed the same way).

- [x] ~~the diagnostic job~~ — **not needed; cause found locally.** `ThisBuild / usePipelining :=
      true` made testUtils compile against absent early tasty output in the artifact-collection
      branch. Fixed by `testUtils / usePipelining := false`. The premise of this slice was wrong:
      the environments did NOT "differ only in the JDK" — the CI step runs THREE sbt commands and
      every local reproduction ran only the first, so nothing local had ever compiled testUtils.
      Reproduced on Temurin with no GraalVM, then A/B'd from `git clean -xdf` on both sides.
- [ ] **the tag does not contain the fix.** `v0.1.0` points at `7afcb808c`, which predates it, so
      re-running the workflow on the existing tag would fail exactly as before. Decide between
      moving `v0.1.0` (it has never published, so nothing consumes it) and cutting a fresh tag.
      A `workflow_dispatch` qualification run on `main` verifies the fix without touching either.
- [x] ~~declare `core` on `testUtils`~~ — **refuted** by run `30907972567`: identical failure. Kept
      because the declaration is correct on its own merits, but it is not the fix.
- [ ] **after it goes green:** nothing else is needed — the workflow publishes from the existing tag,
      so no re-tagging. Then bump `main` to `0.2.0-SNAPSHOT`, or an intermediate build will keep
      calling itself the release.

### ▶ tui-emitter-selfchecks — two defects the rozum work exposed in our own emitter (2026-08-04)

Both found while implementing `tui-fetch-headers`, both outliving that fix, both filed in
[`BUGS.md`](BUGS.md). They are ordered FIRST because each one makes the next rozum feature riskier:
`tui-fetch-post` walks into both.

- [ ] **tui-cargo-deps-are-a-hand-maintained-disjunction** — `cargoToml` decides `serde_json` from a
      condition written against the features that use it (`hasRemoteTable`, now `|| any fetch has
      headers`). Emitted source and emitted manifest are two independent statements kept in
      agreement by memory, and disagreement means a crate that does not compile — invisible to
      string-matching emitter tests. **Fix: derive the dependency from the emitted text** rather than
      gate the disjunction; that deletes the class. Spec:
      [`specs/tui-cargo-deps-derived.md`](specs/tui-cargo-deps-derived.md).
- [ ] **tui-interactive-widgets-have-no-compile-coverage** — the cargo smoke compiles six shapes;
      slice 3 (`TextInput`/`Button`/`Toggle`, focus ring, traversal) is compiled by none of them, so
      its emissions are asserted only by string match. The headers work hit this class one layer
      over — a mutable/immutable borrow of the signal store — and was saved only because the fetch
      path has a cargo test. `tui-fetch-post` must write a signal from an event handler, i.e. the
      same shape with no backstop.

### ▶ tui-fetch-gaps — three capability gaps reported by rozum (2026-08-04) — ALL THREE LANDED

<!-- reported-by: rozum (github.com/sergey-scherbina/rozum, docs/specs/ucc-meetings-in-tk.md)
     reported-at: 2026-08-04
     ssc-version: ec70eb062 (staged bin/, dev build)
     kind: feature
     impact: blocks
     confirmed: no -->

**ROUTED OUT OF `INBOX.md` 2026-08-07, which should have happened when they landed.** All three
were implemented and on `main` — `5616c18b0`, `30ccb0562`, `4ec7e570f` — while their reports still
sat in the inbound queue reading `triage: new`. The reporter had no signal, and the queue said four
untriaged reports when it had one. Two copies of one record is the exact failure P-3.10 exists to
prevent, and pointing at `INBOX.md` from here instead of carrying the reporter's fields is what let
it happen: the routed set is derived from `git grep -l 'reported-by:'`, so an entry that names the
inbox rather than the reporter is invisible to it. The header above fixes that.

`confirmed: no` is deliberate and is what `reported-by` is for — fixed and gated here, but **rozum
has not confirmed against their own client**. That is the open half.

Three `kind: feature`, `impact: blocks` reports from **rozum**, originally registered in
`INBOX.md` via `scripts/inbox-add` by the reporter themselves. They emit ONE `.ssc`
source to two targets — `emit-spa --frontend react` for the web control centre and
`emit(view(), "tui-out")` for a ratatui client — and every gap below is a place where the web target
honours something the terminal target silently drops. Silently is the common thread: none of these
is a build error, each produces a client that renders and then does the wrong thing.

**Their ordering, kept as given** — headers first because it blocks the *read-only* client:

- [x] **tui-fetch-headers** — LANDED `5616c18b0`. `FetchUrlSignal` carries `headersId`;
      `TuiEmitter.collectFetches` dropped it and the emitted helper was a bare
      `ureq::get(url).call()`. Their daemon requires HTTP Basic on every route, so the terminal
      target could not read real data at all.
      Spec: [`specs/frontend-tui-fetch-headers.md`](specs/frontend-tui-fetch-headers.md).
      **Gate:** `TuiCargoSmokeTest` — "a headers signal authenticates the GET via cargo — 401
      without it, 200 with it". It compiles the emitted crate and runs it against a local server, so
      it distinguishes the two states rather than matching a string.
      Also widened the `serde_json` Cargo dependency condition, as the entry warned it would.
- [x] **tui-fetch-url-signal** — LANDED `30ccb0562`. `FetchInfo` captured the URL literal at EMIT
      time, only the tick was dynamic, so a room switcher or day-pager kept reading the endpoint
      chosen at build time. The reporter called this the cheaper of the remaining two and said it
      unblocks read-side navigation on its own.
      **Gate:** `TuiFetchCargoTest` — "a signal URL retargets the GET, with the tick untouched", plus
      "choosing a table row retargets the dependent fetch".
- [x] **tui-fetch-post** — LANDED `4ec7e570f`. No `fetchAction`/POST binding existed on the TUI
      target, so a `TextInput` composer rendered and could never submit. Wanted, and delivered: a
      POST with a body that bumps a tick on success so the bound GET re-reads.
      **Gate:** `TuiFetchCargoTest` — "a fetchAction posts the body, bumps the tick, and clears only
      on success". That gate earned its keep on its first run: a composer's body/tick/headers are
      reachable only through the `EventHandler`, `collectHandlerSignal` had no `FetchAction` case,
      and the POST went out with an EMPTY body while reporting success — with every string
      assertion green. Caught and fixed before merge (`77bc1dc84`).

**What makes all three findable at once, and is worth more than any of them:** the dual-target proof
so far — this repo's `specs/frontend-tui-fetch-refresh.md` gate and the reporter's own PoC smoke —
has only ever run against **fixtures with no auth and a fixed URL**. Every gap here was invisible
until a generated client was pointed at a production endpoint. Gates written against a fixture that
never authenticates cannot see a dropped credential.

### ▶ rust-tui-toolkit (2026-06-23, with Sergiy — "делай вариант [полный транспайл .ssc → Rust]")
Make `computedSignal` (and any thunk) run LIVE in the terminal by routing std/ui through the Rust codegen
backend (RustCodeWalk) — the rust-web-toolkit path where computedSignal is already a re-runnable Rust closure —
and rendering the `View` to **ratatui** instead of HTML/SSR. Spec **[`specs/rust-tui-toolkit.md`](specs/rust-tui-toolkit.md)**
(grounded: reuses the import inliner + signal store + computed closures; obstacle = HTML-collapsed Rust `View`;
seam = `BackendOptions.extra("uiTarget"->"tui")`). The terminal analog of rust-web-toolkit (was S1-S5).

- [x] **rust-tui-1-seam-render** ✓ DONE 2026-06-23 (RustGenTuiToolkitTest 3/3 incl. cargo smoke: computed value renders in terminal) — — thread `uiTarget` into `RustGen` (gating sites :54/:128/:161/:362); minimal
      `TuiRs` (`_tui_render(View)→ratatui`: Text/Fragment/Element core tags → Paragraph/Layout; read
      `data-ssc-text` from `ssc_signals()`); `serve`→`_tui_run` (draw-once snapshot). **Gate:** a
      `serve(lower(vstack(heading,text,signalText(computedSignal(...))),theme),0)` `.ssc` transpiles via
      RustCodeWalk and `cargo run` (SSC_TUI_SNAPSHOT) prints the computed value. Proves transpile→ratatui e2e.
- [x] **rust-tui-2-event-loop** ✓ DONE 2026-06-23 (cargo test: button activate → ssc_recompute_all → frame shows recomputed value; computedSignal LIVE in terminal) — — crossterm loop + focus ring over `data-ssc-*` + Enter→action→`ssc_recompute_all`→
      redraw. **Gate:** counter+computedSignal; cargo test feeds the key, computed text changes (LIVE).
- [x] **rust-tui-3-tag-mapping** ✓ DONE 2026-06-23 (flex-direction:row→horizontal Layout, CSS color/background/font-weight→ratatui fg/bg/bold; cargo test asserts hstack side-by-side) — — CSS flex/gap parse + all std/ui chrome (card/badge/divider/input/toggle/show)
      + focus highlight + colors. **Gate:** rozum-meeting-style toolkit renders faithfully.
- [x] **rust-tui-4-fetch-datatable** ✓ DONE 2026-06-23 (intrinsic overlay -> tui.rs ureq fetch + serde_json rowsPath drill + ratatui Table; cargo test fetches a live {data:[...]} envelope + renders rows) — — Rust runtimes for fetchUrlSignal/fetchRowsSource/staticRowsSource +
      rowsOf envelope drill + `_tui_data_table_view` (fetch→Table). (Absent on the Rust path entirely today.)
      **Gate:** remoteTable renders fetched rows vs a local server.
- [x] **rust-tui-5-converge** ✓ DONE 2026-06-23 (new `ssc tui`/`run-tui` live runner + `run --frontend tui` routes to the rust-codegen path via TuiRunner, cargo fallback to interpreter; CLI test asserts the emit yields the ratatui crate) — — point `frontend: tui` / `--frontend tui` at this path (supersede the static
      emitter for dynamic apps) or unify the two pipelines.

Driven by the agreed roadmap (BACKLOG.md → "Roadmap — agreed priority order, 2026-06-17").
Work top-to-bottom, one major theme at a time. **Maven/centralized publication is LAST.**

### ▶ frontend-tui (ratatui) backend (2026-06-23, with Sergiy — "мы ведём всю компиляторную сторону сами. Оформляй спеку, вноси в спринт и делай все что нужно")
Scalascript-side half of the rozum **Unified Control Center** (`rozum:docs/specs/unified-control-center.md`):
the one missing render backend so a single `std/ui` Tk `.ssc` app compiles to a **terminal UI** (ratatui) as
well as web/desktop. We own the **entire compiler side** (operator decision). Full plan + the 3 answered
questions (backend selection / focus-keyboard / ownership) + lowering table: **[`specs/frontend-tui-ratatui.md`](specs/frontend-tui-ratatui.md)**.
Route = `emitNative` (the Swing/JavaFX native pattern), emitting a self-contained ratatui+crossterm Rust crate
(NOT via RustCodeWalk). Each slice gate = emitted crate `cargo build`s + a ratatui `TestBackend` buffer
snapshot matches (assume(cargo)-gated, like `RustGenCargoSmokeTest`). Drive top-to-bottom.

- [x] **frontend-tui-0-scaffold** ✓ DONE 2026-06-23 — new sbt module `frontendTui` (`frontend/tui`) +
      `TuiFrameworkBackend extends FrontendFrameworkSpi` (`name="tui"`, `emit` throws, `emitNative` → minimal
      buildable crate via `TuiEmitter`) + `META-INF/services` + `Platform.Terminal` & `AppFormat.RatatuiApp`
      added to `frontend/core` (additive) + registered in build.sbt `allFrontends`. **Gate met:**
      `frontendTui/test` 8/8 incl. `TuiCargoSmokeTest` (assume(cargo): emitted crate `cargo run`s, ratatui 0.29
      headless `TestBackend`, prints `ssc-tui: ok`); sibling frontend backends recompile clean. CLI
      `--frontend tui` native-emit wiring deferred (selection already works via `-Dscalascript.frontend=tui` /
      front-matter / inline).
- [x] **frontend-tui-1-static-layout** ✓ DONE 2026-06-23 — `TuiEmitter` lowers the static `View` IR to a
      recursive `render_root`: `Column/Fragment/For`→vertical `Layout` (measured `Length`), `Row`→horizontal
      (`Ratio(1,n)`), `Text/SignalText/TextNode`→`Paragraph`, `Divider`→top-border `Block`, `Spacer`→blank rows,
      `Stack/ScrollView/Styled` pass-through, `Show/ShowSignal` static-eval; interactive nodes render as static
      text (events → slice 3); Style mapping deferred. **Gate met:** `frontendTui/test` 18/18 — 10 fast
      `TuiEmitterTest` + `TuiCargoSmokeTest` (assume(cargo)) renders heading+text+divider+row, buffer snapshot
      has laid-out text + row children side-by-side.
- [x] **frontend-tui-2-signals-redraw** ✓ DONE 2026-06-23 — emitted crate holds a runtime signal store
      (`HashMap<String,Value>` + `Value` S/I/B) seeded from the View tree; `render_root(frame,area,signals)`
      reads `SignalText`/`Toggle`/`TextInput` from it and `ShowSignal`→runtime `if sig_truthy(...)`; `main` runs a
      crossterm loop (raw mode + alt screen → draw → `event::poll` → quit on q/Esc) via ratatui's crossterm
      re-export; headless `SSC_TUI_SNAPSHOT` path for CI. **Gate met:** `frontendTui/test` 20/20 — cargo smoke
      builds the loop crate, renders a signal-bound frame headlessly, AND `cargo test` runs a generated
      `reactive_rerender` proving a signal mutation re-renders.
- [x] **frontend-tui-3-focus-events** ✓ DONE 2026-06-23 — document-order focus ring (`FOCUS_COUNT`,
      `is_text_input`, `focus_mark`), `handle_key` (Tab/↓ + Shift-Tab/↑, Enter/Space→`activate`, typing→
      `type_char`, Backspace, Esc/`q`→quit), generated `activate`/`type_char`/`backspace` match arms; declarative
      `EventHandler`s (`SetSignalLiteral`/`IncrementSignal`/`ToggleSignal` + `TextInput` `InputChange`) mutate the
      store, `Simple`/`WithEvent`→no-op; `render_root(...,focus)` shows the focus marker. **Gate met:**
      `frontendTui/test` 21/21 — cargo smoke builds an interactive crate (signal+button+text-input) and
      `cargo test` runs generated `event_handlers_run`/`text_input_typing`/`tab_moves_focus`/`reactive_rerender`.
      (UCC PoC step 2: composer.) Follow-ups: `A11y.focusOrder` seeding + hidden-`ShowSignal`-branch focus skip.
- [x] **frontend-tui-4-table-routing** ✓ DONE 2026-06-23 — `DataTable(StaticRows)`→ratatui `Table` (header from
      column titles, cells from row `fieldPath`); `TabBar`→focusable tab headers (`Set(current,idx)` activation) +
      runtime `match sig_int(current)` content; `NavigationStack`→runtime `match sig(current).as_str()` routes;
      `sig_int` accessor added; `Badge/Spinner/Pill/Tag` already render as text via std/ui lowering. **Gate met:**
      `frontendTui/test` 25/25 — 3 fast emitter cases + a 2nd cargo smoke building `TabBar[DataTable,…]` (snapshot
      shows active `[Rooms]` + table header + rows). (UCC PoC step 3.) Follow-ups: hidden-tab focus skip,
      ForModel/EditableCell, Sheet/AlertDialog overlays.
- [x] **frontend-tui-5-fetch-binding** ✓ DONE 2026-06-23 — `collectFetches` finds every `FetchUrlSignal`
      (a `ReactiveSignal[String]` carrying a URL) in `SignalText`/`DataTable.Remote`/`ModelView`; emits
      `fetch_text(url)` (blocking `ureq` GET) + `bootstrap(signals)` populating each at startup (before first
      render, both snapshot + interactive); a fetch-bound `SignalText` then renders the body. `ureq` added to
      Cargo.toml only when the app fetches. **Gate met:** `frontendTui/test` 28/28 — 2 fast emitter cases + a
      3rd cargo smoke that starts a local JDK `HttpServer`, builds a crate bound to it, and asserts the snapshot
      shows the fetched body. This is the seam the rozum control-API binds to over HTTP. Follow-up: dynamic
      `DataTable.Remote` rows + typed-model views from fetched JSON (needs `serde_json`).

  **▶ frontend-tui MILESTONE COMPLETE (slices 0–5).** The ratatui terminal-UI backend lowers the full `View`
  IR; rozum can author its control center as one `std/ui` `.ssc` app and compile it to a terminal binary,
  retiring the hand-written `crates/rozum-meeting/src/tui`. Spec `specs/frontend-tui-ratatui.md`. Open
  follow-ups (not blocking): Style/Theme colors, A11y.focusOrder seeding, typed-model dynamic tables,
  Sheet/AlertDialog overlays, CLI `--frontend tui` native-emit flag.

### ▶ Crypto/finance roadmap (2026-06-23, with Sergiy — "да хочу. все хочу. … внеси все это в спринт или в беклог")
Sergiy asked to queue the whole forward-looking crypto/blockchain/identity/payments brainstorm. Plan + per-item
"what / why / where / benefit" + slices: **[`docs/crypto-finance-roadmap.md`](docs/crypto-finance-roadmap.md)**
(explainer) + **[`specs/crypto-finance-roadmap.md`](specs/crypto-finance-roadmap.md)** (engineering plan). The
near-term, codeable-now slices are below; the larger/later epics are in `BACKLOG.md` → "Crypto/finance roadmap —
later epics". Every slice follows **reference → seam → gate → native** (the FROST template). Recommended order is
foundations first (Blake2b + JS-HD) → make three chains backend-agnostic (highest architectural value).

- [x] **crypto-spi-blake2b** ✓ DONE 2026-06-23 — added `Blake2b224`/`Blake2b256` to `HashAlgo`
      (`payments/crypto/spi/shared/.../HashAlgo.scala`); implement in `bouncycastle` (`Blake2bDigest`) +
      `noble-js` (`@noble/hashes/blake2b`); add a pure-Scala `Blake2b` reference fallback (mirrors FROST's
      `Sha512`). **Why:** Blake2b is the one hash missing from the SPI (Keccak-256 + RIPEMD-160 already there);
      it's Cardano's last direct-BouncyCastle dependency. **Gate:** RFC 7693 vectors + Cardano address fixtures
      match across both backends + the reference. Unblocks `chains-backend-agnostic` (Cardano).

- [x] **noble-js-hd-derivation** ✓ DONE 2026-06-23 — implemented `deriveMaster`/`deriveChild` in
      `payments/crypto/noble-js` (they currently THROW "not yet implemented on Scala.js") via `@scure/bip32` /
      HMAC-SHA512, for secp256k1 + Ed25519 (SLIP-0010). **Why:** without BIP-32 HD on JS, wallets + chain
      adapters sign on JVM but not in-browser. **Gate:** byte-for-byte equal to the BouncyCastle backend for the
      existing JVM HD fixtures (BIP-32 + SLIP-0010 vectors).

- [x] **chains-backend-agnostic** ✓ COMPLETE 2026-06-23 (all 3 slices) — route Cardano/Bitcoin/Cosmos crypto
      through the `CryptoBackend` SPI instead of importing `org.bouncycastle.*` directly, then make each a
      crossProject (currently all three are JVM-only `project`s). **Why:** this is the only crypto path still
      bypassing the SPI, and the sole reason these three are JVM-only + carry a heavy dep. The "FROST move",
      repeated → 3 chains gain JS + shed BouncyCastle.
      - [x] Slice 1 (Cardano) ✓ DONE 2026-06-23 — `CardanoAddress` Blake2b-224 + `CardanoChainAdapter.txBodyHash`
        Blake2b-256 now use the portable `scalascript.crypto.Blake2b` reference (zero `org.bouncycastle` in
        `src/main`). `blockchainCardano` → `crossProject(JVM, JS)` `CrossType.Full`: the portable address / CBOR /
        Blake2b / tx-type core moved to `shared/` (cross-compiles to JS); the Blockfrost-backed adapter stays in
        `jvm/` (sttp4 + Future I/O). New `CardanoPortableTest` (shared, no `CryptoBackend`) pins byte-exact CIP-19
        address goldens + RFC 7693 BLAKE2b vectors + tx-body-hash + bech32 + CBOR roundtrips → **JVM 42 / JS 19
        green**, proving browser-wallet bytes are byte-identical to the JVM. HD-on-JS already covered by
        `noble-js-hd-derivation`. Downstream `x402*Cardano*` consumers recompile clean (`.jvm` keeps the id).
      - [x] Slice 2 (Bitcoin) ✓ DONE 2026-06-23 — Sergiy chose "port secp256k1 from scratch" over routing
        through the SPI (Bitcoin also needs Taproot/Schnorr BIP-340/341, which no generic sign/hash SPI can
        express). Built a full **from-scratch portable secp256k1 stack** in `crypto-spi/shared` (no
        `org.bouncycastle`, identical JVM+JS): `Sha256`/`Ripemd160`/`HmacSha256` (NIST/RFC vectors),
        `Secp256k1Group` (Jacobian, multiples-of-G table), `Secp256k1Ecdsa` (RFC-6979 + low-S DER — the d=1
        vector reproduced byte-exact, **resolving the low-S gotcha**), `Secp256k1Schnorr` (BIP-340 vector 1
        byte-exact + BIP-341 Taproot tweak). `BitcoinCrypto` rewritten as a thin shim over it; `blockchainBitcoin`
        → `CrossType.Pure` crossProject (adapter is stub-only, so the WHOLE module — addresses/ECDSA/PSBT/Taproot
        — cross-compiles, no shared/jvm split). cryptoBouncycastle dep dropped. **JVM 45 / JS 45 green** + 38
        portable-stack vectors JVM+JS. Downstream walletVaultLedgerBitcoin recompiles clean. The portable
        secp256k1 is **reusable for Slice 3 (Cosmos)**.
      - [x] Slice 3 (Cosmos) ✓ DONE 2026-06-23 — `CosmosCrypto` + `CosmosSignDoc` rewritten as thin shims over
        the portable stack (secp256k1 via `Secp256k1Ecdsa`, RIPEMD-160 via `Ripemd160`, **Ed25519 via the new
        portable RFC-8032 `Ed25519`** built on the relocated `Ed25519Group`/`Sha512`). `blockchainCosmos` →
        `CrossType.Full` crossProject (Full, not Pure, because the `ServiceLoader` discovery test is JVM-only →
        moved to `jvm/src/test`; `META-INF/services` registration moved to `jvm/src/main/resources`). cosmos
        test de-BouncyCastled (Ed25519 pubkey via `deriveEd25519PublicKey`). cryptoBouncycastle dep dropped.
        **JVM 41 / JS 40 green** (Amino sign-doc, secp256k1 + Ed25519 sign/verify, addresses — all byte-identical
        cross-platform).
      - **Gate (all): ✓ MET** — all three chains: per-chain tests green on JVM **and** newly pass on JS; zero
        `org.bouncycastle` code in any `src/main`. **chains-backend-agnostic COMPLETE (Cardano + Bitcoin +
        Cosmos).** Byproduct: a full portable from-scratch crypto stack in `crypto-spi/shared` (SHA-256/512,
        RIPEMD-160, HMAC-SHA256, secp256k1 ECDSA+Schnorr+Taproot, Ed25519) reusable by any chain/wallet on JS.

- [x] **client-solana-rpc** ✓ DONE 2026-06-23 — new `payments/client/solana` (`clientSolana`): typed
      `SolanaClient` (sttp4 JSON-RPC: getBalance/getLatestBlockhash/getTokenAccountsByOwner/getTransaction/
      sendTransaction/getAccountInfo + raw `rpc`) mirroring `clientEvm`, PLUS the deliverable — `Solana.chainContext(config)`
      returns a turnkey `ChainContext` so callers stop hand-rolling one (`SolanaChainContext` wraps a
      `SolanaClient`; `rpcCall` returns the raw result envelope the adapter unwraps). **Gate MET:** a mock-RPC
      build→sign→broadcast through `SolanaChainAdapter` + the turnkey context (signing with the portable
      `crypto.Ed25519`) — asserts getLatestBlockhash + sendTransaction fire and a base64 tx (sig64+message) is
      submitted; config/shape parity with clientEvm; a devnet-gated live test (getLatestBlockhash/getBalance,
      cancels if offline) — ran green against live Solana devnet. `clientSolana` 5/5. main deps blockchainSpi;
      test deps blockchainSolana + cryptoSpi (% Test). Added to root aggregate. No `examples/` dir — followed the
      clientEvm precedent (mock test + reachability-gated live test = the runnable example).

- [x] **frost-secp256k1** ✓ DONE 2026-06-23 — FROST threshold Schnorr on secp256k1 producing **standard BIP-340**
      signatures, in `FrostSecp256k1` (cryptoFrost/shared), built directly on the portable `Secp256k1Group` +
      `Secp256k1Schnorr` from chains-backend-agnostic. Trusted-dealer Shamir over the scalar field `n` (even-`y`
      group key forced at keygen) + two-round signing (per-signer binding via SHA-256, aggregate nonce `R` forced
      even-`y` with per-signer nonce flip, BIP-340 tagged-hash challenge, Lagrange-weighted partials). **Gate MET:**
      every `t`-of-`n` aggregate verifies under the standard BIP-340 verifier `Secp256k1Schnorr.verify` (2-of-3 all
      subsets, 3-of-5, 5-of-5, 1-of-1, over-quorum) — **cryptoFrost JVM 27 / JS 13 green**, plus a 600-run random
      soak (0 failures). In-process quorum (matches `FrostSign`); the networked transport is the separate
      `frost-distributed-transport` slice. **Also fixed a latent origin/main regression**: the new
      `scalascript.crypto.Ed25519` (added in the Cosmos slice) shadowed BouncyCastle's `object Ed25519` via
      `import scalascript.crypto.*`, breaking `cryptoBouncycastle` compile (uncaught — that module wasn't
      recompiled then); renamed the BC helper → `BcEd25519`. cryptoBouncycastle 52 green. GOTCHA: BIP-340
      `Secp256k1Schnorr.verify` REQUIRES a 32-byte message — short test strings silently return false (not a sig
      bug); always sign a 32-byte hash.

- [x] **frost-distributed-transport** ✓ DONE 2026-06-23 (protocol + in-process transport; network binding noted) —
      refactored `FrostSecp256k1` signing into composable rounds (`commit`/`prepare`/`partial`/`aggregate`;
      `thresholdSign` reimplemented on top, so in-process and distributed paths are byte-identical) and added
      `FrostDistributedSigning`: a `Participant` holds exactly ONE share (`private`, no accessor — never leaves the
      host); a `Coordinator` (`coordinate`) holds the group key + signer set but **no shares**, driving round 1
      (public commitments) → public package → round 2 (public partials) → aggregate over a `Transport`
      abstraction. `LocalTransport` runs participants in-process (the no-co-location simulation). **Gate MET:** a
      `t`-of-`n` distributed run produces a valid BIP-340 signature (2-of-3 all subsets, 3-of-5, 5-of-5);
      byte-identical to the in-process path for the same nonces; only public data (33-byte commitments + partial
      scalars, never a share) crosses the transport (asserted via a recording transport). cryptoFrost JVM 39 / JS
      25. **Concrete HTTP transport DONE 2026-06-24** (walletVaultMpcFrost): `FrostParticipantServer` (JDK
      HttpServer, one share/host, `/round1` `/round2` `/health`) + `DistributedFrostSigningClient` (share-free
      coordinator over HTTP/JSON) → multi-host distributed FROST-Ed25519, verified under standard Ed25519, plugged
      into `McpVault` = **threshold-custody-wallet DONE** (BACKLOG). WS/actor transport = same protocol, different
      pipe. **Also hardened the pre-existing `shamir-secret-backup` tamper test** (single-byte high/padding flips
      are truncation-masked by design → corrupt the whole share).

- [x] **totp-hotp** ✓ DONE 2026-06-23 — HOTP (RFC 4226, counter) + TOTP (RFC 6238, time) in `Totp`
      (cryptoSpi/shared), fully PORTABLE (no SPI backend): added portable `Sha1` (FIPS 180) + generic `Hmac`
      (sha1/sha256/sha512) to crypto-spi/shared, then HOTP dynamic-truncation + TOTP time-step + a
      `validate(window=±1)` skew check. Configurable digits + SHA-1/256/512 (`Totp.Algo`). **Gate MET:** byte-exact
      RFC 4226 App. D (HOTP counters 0-9) + RFC 6238 App. B (TOTP 8-digit, SHA-1/256/512 at 6 timestamps) + FIPS
      SHA-1 + RFC 2202 HMAC-SHA1 vectors. cryptoSpi JVM 51 / JS 51. (SHA-1 is collision-broken — included ONLY for
      these legacy HMAC standards, documented as such.) **Now exposed to `.ssc`** (2026-06-24) via the crypto
      plugin: `hotp`/`totp`/`totpValidate` intrinsics in `CryptoIntrinsics` (secret as base64, algo
      SHA1/256/512); RFC-vector tests through the interpreter + `examples/totp-shamir-demo.ssc`.

- [x] **shamir-secret-backup** ✓ DONE 2026-06-23 — `ShamirSecretSharing` (cryptoFrost/shared): `t`-of-`n` split /
      recover of ARBITRARY byte secrets (seed phrases, keys, blobs) over the prime field `GF(2^255−19)`
      (`Ed25519Group.P`), generalizing FROST's single-element Shamir. Length-prefixed secret → 31-byte chunks
      (`< 2^248 < p`), each split by an independent degree-`(t-1)` polynomial; shares = `id ‖ 32-byte-per-chunk`.
      `recover` is total (truncates each reconstructed chunk to 31 bytes — raw Shamir has no integrity check, so
      `<t`/tampered shares yield a wrong value, not the secret). **Gate MET:** round-trips across sizes
      (0/1/16/31/32/33/64/100/256 B) × thresholds (1-of-1…5-of-5); every t-subset recovers the same secret;
      `<t` reveals nothing; tampered → wrong. cryptoFrost JVM 34 / JS 20. NOT SLIP-0039 wire-compatible
      (SLIP-0039 = GF(256)+mnemonics; this is the prime-field generalization the roadmap asked for). **Now
      exposed to `.ssc`** (2026-06-24) via the crypto plugin: `shamirSplit`/`shamirRecover` intrinsics (secret +
      shares as base64, shares space-separated); round-trip tests through the interpreter +
      `examples/totp-shamir-demo.ssc`.

### ▶ JVM / interp perf (2026-07-02 — "JVM, interp perf -> sprint")

- [x] **jit-value-class-names** — ALREADY IN MAIN (commit `2a563020c`, branch `feature/jit-class-names-fix`).
      AsmJitBackend + JavacJitBackend updated for value-unification: scalar leaves in `DataValue$XxxV`,
      container types in `Value$package$Value$XxxV`, `Value` union erases to `java/lang/Object`.
      JitClasspathTest probe updated to reference `DataValue.class`. 1878 backendInterpreter tests pass.

- [x] **recursionFib-perf** — FLOOR CONFIRMED. `JavacJitBackend.tryCompile` (Phase C) already compiles
      `def fib(n)` body to JVM bytecode via javac → static `long fib(long)` method; HotSpot JIT-compiles
      that further to native code. The 1.193 ms/op IS the compiled floor for binary-recursive fib(30)
      (~2.7M recursive calls as native JVM). Phase C delivered 23.8× over tree-walk (was ~28 ms).
      No further improvement feasible without changing algorithm semantics. Verdict: floor, not a JIT gap.

- [x] **jit-cast-isinstanceof-fix** ✓ DONE 2026-07-03 (feature/jit-cast-isinstanceof-fix) — fixed silent
      exception in `asInstanceOf[WhileLongRunFn]` cast after `cls.getConstructor().newInstance()` in all 8
      JIT compile sites (4 in `JavacJitBackend`, 4 in `AsmJitBackend`). Root cause: Scala 3 catches an
      exception silently when `asInstanceOf` follows `newInstance()` in certain class-loader contexts; fix
      splits into `isInstanceOf` check before the cast. Confirmed with `ssc.jit.bytecode=off` bench:
      `multiVal` 12ms (interpreter) → 0.59ms (JIT) = 20× speedup. Poly closed form done next.

- [x] **interp-poly-closed-form** ✓ DONE 2026-07-03 (f7b243288, feature/interp-poly-closed-form → main) —
      `walkQuadPoly` + `tryExtractPolyAddend` + inline-poly fast path in `tryClosedFormPolyLoop`.
      Peels `acc` from left-assoc `acc + X1 + X2 + …` chains, sums `walkQuadPoly` coefficients, then
      computes `Σ a2*(S+j*stp)^2 + a1*(S+j*stp) + a0` in O(1) BigInt. `multiVal` bench: was 0.59ms (JIT)
      → effectively 0 (O(1) closed form). `PolyClosedFormTest` 7/7 differential tests green. Also catches
      linear inline addends. `JitLintTest` updated (linear acc now closed-form not JIT path). 189/189 pass.

### ▶ Promoted to active by Sergiy (2026-06-23 — "все эти задачи внеси в спринт")
Sergiy explicitly OVERRODE the deferred/backlog status of these four — they are now active sprint work, to be
done (each is genuinely codeable; the external parts are called out). Drive top-to-bottom.

- [x] **coremin-actors-codemove** ✓ DONE 2026-07-02 (4578c8e4f, feature/actors-plugin-move → main) — ActorScheduler.scala (2846 lines) + ActorClusterRoutes.scala extracted to actors-plugin; ActorInterp.scala slimmed 2956 → 98 lines (provider/session + host bridge only); MissingActorRuntimeProvider default (clear error if plugin not loaded); 23 actor/cluster tests moved to backendInterpreterPluginTests (install ActorsInterpreterPlugin); backendInterpreterPluginTests 839 pass; all actor suites 66/0 green.
~~- [ ] **coremin-actors-codemove** (stale — superseded by [x] above; full scope done 2026-07-02)~~

- [x] **theme-a-stable-plugin-spi — Phase 3 (versioning)** ✓ DONE 2026-07-02 (a3b3f6d31, feature/stable-spi-phase3-load-compat → main) — load-time API compat check COMPLETE: `Backend.pluginApiVersion: String = "1.0.0"` (default; third-party plugins override with `PluginApiVersion.Current` at build time); `BackendRegistry` warns on incompatible `pluginApiVersion` for in-process + `.sscpkg` loads (non-fatal, mirrors `spiVersion` pattern); `PluginManifest` + `SscpkgManifest` gain optional `pluginApiVersion` field; `PluginApiVersionCompatTest` 7/0 + `PluginManifestTest` 7/0 + core 1033/0 + pluginApi 22/0 all green. Phase 3 FULLY COMPLETE (migration + signature lock + compat check).

- [~] **remote-package-registry** (Tier 3 strategic — unlocks the 3rd-party plugin ecosystem) — the local story
      is done (`~/.scalascript/registry.yaml` + `pkg:` resolver + `ssc install`, `.sscpkg`). **Slice 1 DONE
      2026-06-23:** the registry protocol + reference server — `RemoteRegistry` (`Entry(id,version,sha256,desc)`
      + JSON index wire format + `compareVersions` + `sha256Hex`) and `FileRegistry` (directory-backed catalog:
      publish [immutable releases] / search / resolve [exact or latest] / versions / fetch [checksum-verified]).
      `RemoteRegistryTest` 7/0. Greenfield/additive. Spec `specs/arch-build-registry.md` §6b. Follow-up slices
      below (do gradually, one at a time). EXTERNAL (deploy, not code): host `registry.scalascript.io`.
      **RECONCILE NOTE 2026-06-23 (probed existing infra):** the registry CLIENT already exists more fully than
      slice 1 assumed — `RegistryClient` fetches+caches `packages.yaml` from a configurable URL, and `ssc search`
      + `ssc install` + `LocalRegistry` consume it; `ssc publish` is TAKEN (app-store upload). So the real gap is
      the SERVER/publish side, and `FileRegistry` must speak the client's **`packages.yaml`** format (not its own
      `index.json`). Slices corrected accordingly:
  - [x] **registry-packages-yaml-bridge** (slice 2) ✓ DONE 2026-06-23 — `FileRegistry.exportPackagesYaml(baseUrl)`
        / `writePackagesYaml` project the catalog into the client `LocalRegistry.Entry` `packages.yaml` shape
        (id→url+version+description, one entry per id at its latest version, `url`→stored artifact), so the
        EXISTING `RegistryClient`/`ssc search`/`ssc install` consume a `FileRegistry`-served dir unchanged; the
        richer `index.json` (sha256/all-versions) stays the publish-side record. Test round-trips through
        `LocalRegistry.parseFile`/`resolve`. `RemoteRegistryTest` 8/0.
  - [x] **registry-publish-cmd** (slice 3) ✓ DONE 2026-06-23 — `ssc plugin registry publish <pkg.sscpkg>
        [--registry <dir>] [--base-url <url>] [--description <t>]` (the existing `ssc plugin registry` subcommand
        group — not `ssc publish`, which is app-store). New `SscpkgLoader.loadManifest` (manifest-only) reads
        id/version; calls `FileRegistry.publish` (content + index.json) + `writePackagesYaml`. Round-trip tested
        (temp `.sscpkg` → loadManifest → publish → fetch → client `LocalRegistry.resolve`). `RemoteRegistryTest`
        9/0; cli compiles.
  - [x] **registry-http-server** (slice 4) ✓ DONE 2026-06-23 — `RegistryHttpServer` (JDK `com.sun.net.httpserver`,
        dependency-free): `GET /packages.yaml` + `GET /packages/<id>/<version>.sscpkg` + `POST /publish/<id>/<version>`;
        auto-derives its self-referencing base URL from the bound port; loopback by default. In-process round-trip
        test (`java.net.http.HttpClient`). `RegistryHttpServerTest`+`RemoteRegistryTest` 10/0.
  - [x] **registry-publish-auth** (slice 5) ✓ DONE 2026-06-23 — `RegistryHttpServer` optional
        `publishTokens: Set[String]`: non-empty ⇒ `POST /publish` needs `Authorization: Bearer <token>` (else
        401); empty ⇒ open (dev default); GET reads stay public. `RegistryHttpServerTest` 2/0.
        **→ remote-package-registry CODE COMPLETE** (slices 1-5: protocol + `FileRegistry` + `packages.yaml`
        bridge + `ssc plugin registry publish` + HTTP server + auth). Only EXTERNAL deploy (host the domain + TLS)
        remains — the `[~]` parent stays open on that deploy step alone.

- [x] **FROST-Ed25519** ✓ DONE (slices 1–8 all complete — threshold Ed25519 signing — wallet MPC stack) — **FEASIBILITY PROBED + PLANNED INTO
      SUB-SLICES 2026-06-23.** FROST = flexible round-optimized Schnorr threshold signatures over Ed25519, as a
      self-contained `walletVaultMpcFrost` variant (the existing `walletVaultMpc*` are REMOTE/external-provider
      clients — Fireblocks/Coinbase/Lit/Zengo — not in-house threshold crypto, so FROST is the first). **KEY
      FINDING:** the codebase exposes NO usable Ed25519 GROUP operations — `payments/crypto/bouncycastle/Ed25519.scala`
      is high-level sign/verify only (BC `Ed25519Signer`); FROST needs scalar field (mod L), point add, base+arbitrary
      scalar mult, encode/decode. So **do NOT hand-roll curve math** (correctness-critical) — add a vetted group-ops
      library (e.g. `cafe.cryptography:ed25519-elisabeth`, pure-Java Edwards-point + Scalar arithmetic). Correctness
      gate throughout: a FROST signature MUST verify under the EXISTING standard verifier (`Ed25519.verify`) against
      the group public key. Substantial multi-session crypto — do as discrete green sub-slices, one at a time:
  - [x] **frost-groupops** (slice 1) ✓ DONE 2026-06-23 — FROM-SCRATCH (Sergiy's call, no new dep). New
        `cryptoFrost` module (`payments/crypto/frost`, pure; BC test-only). `Ed25519Group` = RFC 8032 reference
        group arithmetic (BigInteger): field mod 2^255-19, twisted-Edwards extended-coord add, scalar mult,
        encode/decode, base point B, order L, scalar field, `secretScalar`. `Ed25519GroupTest` 6/0 incl. the
        gate — generated pubkeys match BouncyCastle Ed25519 bit-for-bit (25 random seeds). Spec `specs/frost-ed25519.md`.
  - [x] **frost-keygen** (slice 2) ✓ DONE 2026-06-23 — `FrostKeygen`: trusted-dealer `t`-of-`n` Shamir over the
        scalar field (degree-(t-1) poly, shares `(id,f(id))`, group key `B·sk`) + Feldman VSS commitments `B·a_j`
        (`verifyShare`) + Lagrange `reconstruct` at x=0; `generateFrom` (explicit coeffs) for determinism + as the
        DKG building block. `FrostKeygenTest` 4/0 (cryptoFrost 10/0): t-subsets recover sk + match group key; <t
        don't; VSS accepts good / rejects tampered shares.
  - [x] **frost-signing + frost-aggregate-verify** (slices 3+4) ✓ DONE 2026-06-23 (combined — signing isn't
        verifiable until aggregation yields a checkable signature). `FrostSign`: round1 nonces `(d,e)`+commitments
        `(D,E)`; `ρ_i=SHA512(domain‖id‖msg‖commits) mod L`; `R=Σ(D_i+ρ_i·E_i)`; `c=SHA512(R‖A‖msg) mod L`;
        `z_i=d_i+ρ_i·e_i+λ_i·c·s_i`; aggregate → 64-byte `encode(R)‖scalarLE(z)`. **GATE PASSED:** `FrostSignTest`
        4/0 (cryptoFrost 14/0) — 2-of-3 AND every 3-of-5 subset verifies under BouncyCastle Ed25519; tampered
        partial + wrong message rejected. **FROST-Ed25519 functionally complete** (group ops + keygen + signing).
  - [x] **frost-ops-seam** (slice 5) ✓ DONE 2026-06-23 — the substitution mechanism. `Ed25519Ops` trait (point
        ops + scalar field + `secretScalar` + `sha512`) with `Ed25519Ops.Reference` (pure `Ed25519Group` + JDK
        SHA-512) as DEFAULT + registry (`current`/`register`/`reset`). `FrostKeygen`/`FrostSign` route ONLY through
        `Ed25519Ops.current` (incl. SHA-512 — no direct `java.security`), so a native backend substitutes
        transparently. Behaviour-preserving (14 prior tests pass through the seam) + a substitution test (a
        registered spy backend IS exercised by keygen+sign; reset restores reference). cryptoFrost 16/0.
  - [~] **frost-crossbuild** (slice 6) — make the REFERENCE FROST compile+run on JS. PROBE: the JVM-only deps
        are `java.security` SHA-512 AND `java.security.SecureRandom` (Scala.js 1.20 has neither). Split:
    - [x] **6a portable SHA-512** ✓ DONE 2026-06-23 — pure-Scala `Sha512` (Long-based, FIPS 180-4); routed
          `Ed25519Ops.Reference.sha512` + `Ed25519Group.secretScalar` through it; **removed `java.security` from
          hashing**. `Sha512Test` (abc/empty FIPS vectors + matches `java.security` across padding boundaries);
          cryptoFrost 19/0.
    - [x] **6b RNG via seam** ✓ DONE 2026-06-23 — `Ed25519Ops.randomBytes(n)`/`randomScalar()` (Reference = JVM
          `SecureRandom`). `FrostKeygen.generate`/`FrostSign.round1` dropped their `rng: SecureRandom` params and
          source from `Ed25519Ops.current` → FROST logic is fully `java.security`-free (only the JVM default's
          `randomBytes` uses it; 6c splits per-platform) AND the RNG is a substitutable primitive. cryptoFrost 19/0.
    - [x] **6c crossProject** ✓ DONE 2026-06-23 — `cryptoFrost` is a `crossProject(JVM,JS)`; reference (Ed25519
          math + own SHA-512 + keygen + signing + seam) is pure → compiles+RUNS on JS. `PlatformEntropy` per-platform
          (JVM `SecureRandom` / JS WebCrypto). Shared tests run on BOTH: **JS 6/6 on Node** (incl. `generate(3,5)`
          via WebCrypto + the substitution test), JVM 19/0 (BC/java.security tests in `jvm/`). **→ FROST
          cross-platform story COMPLETE: one reference, identical on JVM + JS, native RNG, transparent substitution.**
  - [x] **frost-native-backend** (slice 7) ✓ DONE 2026-06-23 — `CryptoBackedEd25519Ops`: an `Ed25519Ops` backend
        delegating SHA-512 + RNG to the project's `CryptoBackend` SPI (BC/JVM, noble/JS), group math stays the
        reference. `cryptoFrost dependsOn cryptoSpi` (no external dep). Verified (JVM 20/0): BC SHA-512 == our
        reference SHA-512; a BC-backed 2-of-3 FROST signature verifies under BouncyCastle Ed25519; JS still 6/0
        (bridge cross-compiles). Closes the loop — portable reference + transparent substitution down to the crypto provider.
  - [x] **frost-vault-integration** (slice 8) ✓ DONE 2026-06-23 — FROST wired into the wallet stack as an
        in-house threshold provider. `FrostSigningClient extends RemoteSigningClient` runs the FROST 2-round
        protocol locally over a `FrostQuorum` (instead of an external TSS service), plugging straight into the
        existing `McpVault` (kind=Mpc) delegate seam whose own doc already names "FROST for Ed25519" — so a
        threshold wallet is just `McpVault("…", new FrostSigningClient(Seq(quorum)))`, no new `Vault` impl. New
        module `walletVaultMpcFrost` dependsOn `walletVaultMpc` + `cryptoFrost` (BC test-only). Verified 3/0:
        vault unlock → getSigner(Ed25519) → sign → 64-byte sig verifies under standard BouncyCastle Ed25519
        (distinct subsets); non-Ed25519/unknown-account/sub-threshold rejected. **Closes the FROST track
        (slices 1–8).** Remaining FROST refinements (constant-time field, full DKG, distributed transport,
        JS @noble mirror) are future work, not slices.

### ▶ Autonomous queue (2026-06-23, with Sergiy — "все кроме мавена — в спринт и делай")
When the clean autonomous coremin slices ran out (value-unification is sibling-active; NFC/wallet-ws are
device/browser-blocked; Maven publish is explicit-go only), Sergiy directed: queue everything except Maven
and execute autonomously. In priority order:

**▶▶ stable-SPI Phase 3 — FULL breakdown (2026-06-23, Sergiy: "делай Phase 3 автономно … заноси в спринт
сразу всё, потом делай постепенно").** GOAL: the **28** plugin `*Intrinsics.scala` that `import
scalascript.interpreter.{Value, InterpretError, Computation, …}` depend ONLY on the stable
`scalascript-plugin-api`, so a core/interpreter refactor (or a third-party plugin) can't break them, and the
build can reject any plugin jar containing `scalascript/interpreter/`. **PROBED FINDING:**
`PluginValue`/`PluginComputation` are opaque `Any` with NO accessors; `evalLegacy`'s own doc says full
Value-decoupling is "v2.x". So import-removal is GATED on a **Value-surface in the stable API** — it does NOT
come from `evalLegacy` (which only decouples the *context*). Cycle-checked: `pluginApi → core` is acyclic
(core deps = `valueData, backendSpi, …`, not pluginApi). Do gradually, one plugin/small-batch per slice, each
validated + pushed:
- [x] **p3-foundation** ✓ DONE 2026-06-23 — `scalascript-plugin-api` now `dependsOn(core)` (acyclic seam);
      `PluginValue` exposes stable extractors (`asString/asInt/asDouble/asBool/asChar/asList/asTuple/asMap/
      asOption`) + constructors (`string/int/double/bool/char/list/tuple/map/some/none/unit`) + `show`, backed
      by the interpreter `Value`; `PluginError` builds the real `InterpretError` + `raise(msg)`. PROOF:
      `mime-plugin` migrated off `scalascript.interpreter` end-to-end. `pluginApi/test` 14/0, `mimePlugin/test`
      4/0, `PluginExamplesSmokeTest` 1/0. The surface may need a few more accessors as later batches surface new
      shapes — extend `PluginValue` as needed.
  ~~- [ ] p3-foundation (original)~~ — expose a stable Value-surface through
      `scalascript-plugin-api` so plugins stop importing `scalascript.interpreter.Value`. DESIGN (decided):
      `pluginApi` gains a `core` dep = the ONE controlled seam (moves the coupling 28→1; opaque `PluginValue` +
      stable extractors/constructors keep the plugin ABI stable even as core's `Value` repr changes — e.g.
      value-unification). Add to `PluginValue`: extractors `asString/asInt/asDouble/asBool/asChar/asList/asTuple/
      asMap/asOption` + constructors `string/int/double/bool/list/tuple/map/unit/some/none` + `show`; keep
      `PluginError(msg)` (= InterpretError) + `PluginComputation.pure`. Stable bridges for the non-Value imports:
      `JsonParser`/`jsonToJson` → `JsonCodec` (exists) or a parser bridge; `OAuthBridge` (mcp/oauth) → a
      capability/stable surface. PROOF in this slice: migrate `mime-plugin` (simplest) end-to-end off
      `scalascript.interpreter`. VERIFY: `pluginApi` compiles with the core dep (no cycle); mime compiles with no
      `scalascript.interpreter` import + its tests green.
- [x] **p3-batch-A** ✓ DONE 2026-06-23 — ALL 10 migrated off scalascript.interpreter: mime/pdf/fs/crypto/payment-request/nfc/auth/fetch/graph/yaml (tests green). Surface complete: full Value-surface + extractor objects (Str/Num/Dbl/Bool/Chr/Lst/Tpl/Inst/Opt/Big/MapVal/Foreign/NativeFn) + foreign/nullV/isUnitOrNull/showAny/isRuntimeValue + asInstance via effectiveFields. Recipe mature (stateful line-aware swap; mid-line .collect{case}; strip pattern type-tests; bare Value types; OptionV-ctor->some/option; structural store->PluginValue+wrap; showAny for Value-vs-native).
      **BREAKTHROUGH 2026-06-23 — the hard problem is solved.** The blocker on the pattern-matching plugins:
      they use `Value.StringV(x)` etc. BOTH as constructors AND as `case` PATTERNS, and `PluginValue` (opaque)
      can't be pattern-matched. SOLUTION: added **extractor objects** to `PluginValue` — `Str/Num/Dbl/Bool/Chr/
      Lst/Tpl/Inst/Opt/Big/MapVal/Foreign/NativeFn` (each `unapply(v: Any)`), plus `foreign`/`nullV`/`isUnitOrNull`.
      Now `args match { case List(Str(label), Bool(p)) => … }` works without importing `Value`. Migration recipe
      (proven on payment-request): **line-aware** swap — on `case` lines (left of `=>`) use the extractors
      (`Value.StringV`→`Str`), elsewhere use constructors (`Value.StringV`→`PluginValue.string`); `.asInstanceOf
      [Value]`→`.asInstanceOf[PluginValue]`; `Map[String, Value]`→`Map[String, PluginValue]`; `throw
      InterpretError`→`PluginError.raise`. **`Value.Foreign(tn, handle: Any)` IS exposable** (generic host-object
      wrapper, not interpreter-internal) — so fetch is NOT blocked, just Foreign-heavy.
      REMAINING (yaml only — last batch-A): **auth** (heavy: MapV/OptionV/Instance), **graph/yaml**
      (also move internal `Value` store to `PluginValue`/`Any`)
      RECIPE REFINEMENTS (from auth): the line-aware script must also handle (a) MID-LINE patterns in
      `.collect { case (Str(k), Str(v)) => … }` (not only line-start `case`), and (b) bare `Value` TYPE
      annotations (`Option[Value]`/`: Value`/`[Value]`) → `PluginValue` (the `Value.`-only residual check
      misses them).
- [x] **p3-batch-B** ✓ DONE (all 7: ws/pwa/json + oauth/dstreams/graphql/streams — giants done in p3-giants)
- [x] **p3-batch-C** ✓ DONE (all 10: uuid/os/request/smtp/sql/remote/frontend + mcp/content — giants done in p3-giants)
- [x] **p3-giants** ✓ DONE — all migrated; `actors` is a PERMANENT exemption (interpreter-only runtime provider). All ctx is covered by EXISTING caps — big-but-mechanical value/NativeFnV
      passes PLUS one bridge each. Per-plugin scope:
  - **http** ✓ DONE (4 unit + 58 integration tests: MountHandler/TypedHandler/HttpClient/TypedRpcBinary).
        jsonToJson → `jsonEncode`; the `TypedHandlerWrapper.wrapIfTyped` coupling → new `PluginValue.wrapTypedHandler`
        seam + `funArity` (FunV param count for the mount static/handler shape check); globalsView was just
        `Map.empty`. 21 NativeFnV → `nativeFn`. All 33 ctx methods were already on HttpCap&WsCap&Storage&Mount.
  - **dstreams** ✓ DONE (59 tests). Internal Value-DAG engine (136 InstanceV, 56 NativeFnV, 11 `.fields`/`.typeName`
        sites). New PluginApi accessors: `pv.field(name)`, `pv.typeNameOf`, `InstAny` extractor (binds a whole
        instance value, replacing `case x: Value.InstanceV`). `.fields.get`→`.field`, `Inst`/`InstAny`/`Lst`/`Str`
        extractors, all 56 `Computation.pureFn`→`nativeFn`. ctx (featureGet/Set, invokeCallback, registerRoute) on caps.
  - **oauth** ✓ DONE (4 unit + 58 integration: McpOAuthBridge/OAuthGuard/OAuthRsa/OAuthScript/Oidc/OAuthAuthServer).
        5-file web (OAuthIntrinsics 334 + OAuthHttp + OidcHttp + OAuthClientIntrinsics + OidcHelpers) migrated together;
        `OAuthBridge` (1-field ConcurrentHashMap) RELOCATED lang/core/interpreter → `scalascript.plugin.api.OAuthBridge`
        (core only defined it; mcp + the test reference it indirectly). ujson.Value protected via `(?<![A-Za-z.])`
        anchored regex; shared `Value`-typed helpers (toStringSet/resolveAuthServer) retyped to `Any`.
  - **mcp** ✓ DONE (2 unit + 184 integration: 30 Mcp* test files incl McpOAuthBridge/McpHttpBidi/McpBidiSampling).
        Single file (1508 loc, 87 NativeFnV, 151 StringV, 72 ujson). OAuthBridge already moved; ctx on caps; the 4
        `: Value.InstanceV` were return types (→ `PluginValue`). ujson.Value protected via anchored regex; `Mcp`
        value helpers (valueToStringList/valueToJson/valueToAuthResult) retyped to `Any`.
  - **streams** ✓ DONE (88 tests). dstreams' sibling — same recipe; extra: 26 `.asInstanceOf[Value]`→`[PluginValue]`
        (valid no-op cast, PluginValue erases to Any), OptionV/TupleV unfold inspection → `Opt`/`asTuple`, NativeFnV
        type-tests → `Fn`, Foreign signal patterns → `Foreign`. GOTCHA: the `X: Value.InstanceV`→`InstAny(X)` regex
        also hit a def PARAM (revert to `X: PluginValue`); stripping `case X: Value` ascriptions can shadow a
        following catch-all (restore with an `isRuntimeValue` guard).
  - **content** ✓ DONE (29 tests). Largest (2144 loc), no Computation/NativeFnV (pure value construction).
        NEW accessors: `pv.fields` (whole field map), `PluginValue.orderedInstance` (array-backed field ORDER —
        content nodes are read positionally via `inst.fieldNames`, a behavioral bug caught by tests). GOTCHAS:
        the AST `ast.ContentValue.*` ADT (137 uses) collides with `Value.*` replaces → anchor every regex with
        `(?<![A-Za-z])`; the `InstAny`/`: Value` regexes also hit DEF PARAMS (revert to `: PluginValue`).
  - **graphql** ✓ DONE (162 tests, incl GraphQLSubscriptionTest). 2-file web; carrier case classes
        (`GraphQLResolvers`/`ScalarCodec`/`GraphQLFederationEntities`) hold `AnyRef` (NOT `Any`).
        ROOT CAUSE of the earlier "blocker": `GraphQLSubscriptionTest` asserts `res.subscription("e") eq fn`;
        with an `Any` carrier `res.subscription("e")` is statically `Any` (no `.eq`), so scalatest's `assert`
        macro routes it through its `Equalizer` implicit and casts the WRAPPER to `AnyRef` — comparing the
        wrapper, not the value (always false). `AnyRef` carrier → `.eq` is direct reference equality, like the
        original `Map[String, Value]` (`Value = DataValue|ValueRest` is `<: AnyRef`). NOT a scalac bug; the
        debug-println "passes" were my explicit `.asInstanceOf[AnyRef]` casts bypassing the Equalizer. anchored
        regex protects `ujson.Value`; `valueToJava`/`addResolver`/`byType`/`entities` retyped to AnyRef.
  - **actors** — PERMANENT exemption (correct, not unfinished). Interpreter-only runtime PROVIDER
        (`intrinsics = Map.empty`); its `ActorRuntimeProvider` SPI is interpreter-coupled BY DESIGN —
        `ActorRuntimeHost` traffics in `Computation`/`Value`/`Env`/`scala.meta.Case`, and the SPI doc says
        actors "cannot use the host-neutral `BlockForm` SPI without leaking interpreter internals". No
        host-neutral form exists to migrate to. `StableSpiEnforcementTest` exempts it; the stale-exemption
        guard keeps the allowlist honest.
- [x] **p3-enforce** ✓ DONE — BUILD CHECK: `StableSpiEnforcementTest` (backendInterpreterPluginTests) scans every
      `runtime/std/*-plugin/src/main` and fails if a value-surface plugin references `scalascript.interpreter`;
      a second test guards against STALE exemptions. Exemption: `actors-plugin` (runtime provider) only — graphql now migrated. The 27 migrations are locked in. REMAINING:
      `PluginNative.evalLegacy` stays (still the legitimate untyped `(ctx, args)=>Any` entry the migrated plugins
      use — bodies are clean, so it's no longer "transitional"; only its scaladoc's "may use Value.*" note is now
      stale). Bytecode-level jar scan + the graphql/actors special cases are the only open items.
      STATUS: 27/28 plugins clean (batch-A 10 + ws/pwa/json + uuid/os/request/smtp/
      sql/remote/frontend/http/dstreams/streams/content/oauth/mcp/graphql). PluginApi seam now exposes: nativeFn/callFn, Fn/isCallable, jsonEncode/
      jsonFacade/fromHostAny/parseJson/lookupKey, decimal/asDecimal/Dec, funArity/wrapTypedHandler, field/typeNameOf/
      InstAny, fields/orderedInstance, OAuthBridge(relocated). Remaining: actors only (runtime-provider — permanent exemption is the right call). 27/28 value-surface
      migrations COMPLETE; graphql resolved (carrier must be AnyRef not Any, for scalatest eq).

In priority order:
- [x] **autonomous-hardening** ✓ DONE 2026-06-23 — broad sweep of the coremin-affected surface (cli
      `ExamplesSmokeTest` + interpreter `StdEffectsTest`/`InterpreterTest`/`Actor*`/`*Effect*`/`Stream*`):
      **all green, 2/0 + 338/0, no new breakages.** The one real stale-example breakage (`algebraic-effects.ssc`
      ran `Undefined: runState` in the no-plugin cli smoke) was already caught+fixed in the advanced-optin turn.
      So the effect extractions + prelude minimization did not leave other regressions in the high-signal areas.
      (Did NOT run the ~20-min scala-cli `CrossBackendPropertyTest` — that's a codegen-vs-interp regression
      catcher, orthogonal to the coremin churn; siblings exercise it.)
- **coremin-actors-codemove** → PROMOTED to active 2026-06-23 (Sergiy "внеси в спринт") — see the "Promoted to
      active" queue at the top of Active tasks. (Probe context retained there: atomic ~3500-LOC move of
      `ActorInterp`+`ActorGlobals`+`ActorWireProtocol`, `private[interpreter]`-coupled via the `ActorRuntimeProvider`
      seam; prefer lifting the touched core internals into a typed seam, then moving the file.)
- [x] **strategic-theme-survey** ✓ DONE 2026-06-23 — surveyed BACKLOG strategic themes: the audit shows
      Themes A/E/F/H/J are ALREADY BUILT (FFI = `GlueClasspathRegistry`/`GlueJsPreambleRegistry` landed;
      modularity = `SsclibManifest` landed; stable-SPI Phases 1+2 landed). The only open strategic item is
      `remote-package-registry` (registry.scalascript.io), explicitly DEMAND-DRIVEN (build when a real external
      plugin author needs it — needs hosting/domain, not codeable autonomously). So no greenfield strategic
      slice is ready. Maven publication stays EXCLUDED per Sergiy.
- [x] **advanced-example-check-ux** ✓ DONE 2026-06-23 — concrete follow-up to advanced-optin: the 7 examples
      using advanced-plugin names (`x402-*`→payments, `oauth`/`oidc`→oauth) now `ssc check`-flag unless the
      plugin is added (verified: `undefined name: DefaultSyncBackend/basicRequest`). Added a uniform "Advanced
      plugin" note to each pointing at `--plugin`. Fence-lint + cli smoke 2/0.
- [x] **check-autoload-plugin-by-import** ✓ DONE 2026-06-23 (Sergiy: build it) — `ssc check` now auto-resolves
      advanced names when the file imports the plugin's namespace, no manual `--plugin`. SHIPPED: SPI
      `Backend.providesImports: List[String] = Nil`; payments→`scalascript.x402`, oauth→`scalascript.oauth`+
      `scalascript.oidc`, spark→`scalascript.spark` declare it. `importPrefixesOf(module)` extracts import refs
      from the ```scalascript code-block trees (`scala.meta.Import.importers.ref.syntax`) + doc-level
      `Content.Import`; `BackendRegistry.importMatchedPreludeSymbols(prefixes, availableDirs)` scans
      `lib/compiler/plugin-available` `.sscpkg` packages with a THROWAWAY `URLClassLoader` (non-matching plugins
      never committed to the runtime) and folds in matching `preludeSymbols`. Wired into `ssc check` (Main ~5293)
      AND `check-with-iface`; `-Dscalascript.pluginAvailableDir=` override for tests/custom layouts.
      **Verified end-to-end** against the real staged `payments-plugin.sscpkg`: `ssc check examples/x402-client.ssc`
      → `OK` (was `undefined DefaultSyncBackend/basicRequest`); still errors without the dir; `hello.ssc` unaffected
      (import-gated). `CheckAutoloadImportTest` 3/0, plugin-tests 712/0, cli smoke 2/0. The 7 advanced-example notes
      were updated to reflect the auto-detection. GOTCHA: Scala 3 nested comments — `/*` inside a `/** */` opens a
      nested comment (bit me in a test doc-string).

- [x] **board-spec-hygiene** ✓ DONE 2026-06-23 — reconciled stale core-min/polyglot board/spec wording.
      Updated `specs/polyglot-libraries.md` to the 2026-06-23 landed state, removed future-looking optics
      follow-ups from completed SPRINT entries now that JS/JVM/Rust/Java optics all ship, clarified that
      advanced opt-in prelude cleanup landed after `coremin-hybrid-split`, and changed old block-form template
      notes from "next work" to historical "later landed" wording. No code changed; active `core-min-value-unification`
      claim/worktree untouched.
- [x] **backlog-hygiene** ✓ DONE 2026-06-23 — docs-only classification pass for stale BACKLOG open items.
      Added a status-hygiene note to `BACKLOG.md`; marked `@wasmExport/@wasmImport` out-of-scope by design;
      converted history-only perf rows (`hof-glue-jit-compile`, `vectorize-pure-loop`, `direct-style-eval`) and
      `demand-driven-from-busi` to non-checkbox notes; consolidated duplicate `registry.scalascript.io` under
      `remote-package-registry`; and labelled the remaining intentional `[ ]` rows as `BLOCKED` or `DEFERRED`
      where appropriate. No code changed; active value-unification work untouched.

### ▶ Unblocked & claimable now (2026-06-22 eve, with Sergiy — "занеси в спринт всё что не заблокировано")

These need NO design decision — claimable immediately, in priority/tractability order. Full blueprints
live in the `polyglot-phase2-optics-allhosts` entry below (Task B = cross-language reuse, proven on the JS
slice). Each is one host of the optics-library packaging, individually claimable.

- [x] **polyglot-optics-board-hygiene** ✓ DONE 2026-06-22 — reconciled stale optics packaging entries at the top of `SPRINT.md`.
      **How:** compare the open `emit-lib-cli` / `polyglot-optics-jvm` entries here with the later completed
      `optics-emit-lib-cli`, `optics-jvm-facade`, `polyglot-optics-rust`, and `polyglot-optics-java` entries
      plus `CHANGELOG.md`; mark stale duplicates as done/superseded instead of letting agents re-claim already
      landed work. Do not touch implementation. **Verify:** grep shows no open `[ ]` optics packaging duplicate
      remains in the top claimable queue; active claims are unchanged.
- [x] **emit-lib-cli** ✓ SUPERSEDED/DONE 2026-06-22 — duplicate of the later `optics-emit-lib-cli` entry:
      `ssc emit-lib --host js --feature optics -o <dir>` is already user-reachable through `EmitLibCmd`
      (`EmitLibCmdTest` 2/2, README/user-guide updated).
- [x] **polyglot-optics-jvm** ✓ SUPERSEDED/DONE 2026-06-22 — duplicate of the later `optics-jvm-facade`
      entry: `emit-lib --host jvm` already emits the native Scala optics library with a compiled smoke and
      golden API coverage.
- [x] **polyglot-optics-rust** ✓ DONE 2026-06-22 (`f13427d4b`, mellow-shrew) — `RustLibPackager`
      (counterpart of Js/JvmLibPackager) emits a dependency-free `ssc-optics` Rust crate (Cargo.toml +
      src/lib.rs + README) via `emit-lib --host rust --feature optics`. lib.rs = faithful dynamic port of
      the JS/JVM optics over a `Value` enum (Obj/Arr/Opt/Str/Int/Bool/Null + `_type` sums): Lens/Optional/
      Traversal/Prism + steps field/index/at/some/each. `RustLibPackagerTest` 4/4: golden (file-set + API +
      dep-free) + a Rust-toolchain-gated cargo smoke (writes the crate + an integration test exercising all
      4 optics + `cargo test` — the emitted Rust compiles AND behaves). user-guide + README updated. 3rd of
      4 optics hosts; Java landed next, so all four hosts now ship.
- [x] **polyglot-optics-java** ✓ DONE 2026-06-22 (`09e174612`, mellow-shrew) — `JavaLibPackager` emits a
      dependency-free `ssc-optics` Java/Maven project (pom.xml + Optics.java + README) via `emit-lib --host
      java`. Optics.java = faithful Java 17 port over dynamic `Object` (Map/List/Optional/`_type` sums):
      Lens/Optional_/Traversal/Prism + steps. `JavaLibPackagerTest` 5/5: golden + emit-lib layout + a
      javac-gated compile/run smoke (exercises all 4 optics → 5/9/10/false/[1, 2]/true/false). **ALL FOUR
      optics hosts now ship: JS (npm) + JVM (sbt) + Rust (cargo) + Java (maven) — Task B optics COMPLETE.**

### ▶ JS-runtime + polyglot follow-ups (2026-06-22 eve, with Sergiy — "запиши в спринт все эти задачи и делай автономно")

Queued after the JS `.mjs`-resource cleanup + rename. Drive top-to-bottom (tractability order).

- [x] **optics-emit-lib-cli** ✓ DONE 2026-06-22 — `ssc emit-lib --host js --feature optics -o <dir>` writes the
      `@scalascript/optics` npm package (package.json + index.mjs + optics.d.ts) from `JsLibPackager`. New
      `EmitLibCmd` registered via the ServiceLoader `CliCommand` SPI; `EmitLibCmdTest` 2/2; README CLI row +
      user-guide section. The optics packager is now user-reachable (was test-only). More host/feature combos
      follow the same shape (see `optics-jvm-facade`).
- [x] **jvm-rust-runtime-resources** ✓ DONE 2026-06-22 (JVM + Rust; §3 #8 closed all backends) — mirror the JS `.mjs`-resource cleanup (polyglot §3 #8) for JVM
      (`JvmGenRuntimeSources`) + Rust (`RustRuntimeTemplates`). **PROBED 2026-06-22 (bright-quail) — NOT a clean
      mechanical copy like JS; more involved:**
      • **JVM** `JvmGenRuntimeSources.scala` (3656 lines): 13 runtime strings, each
        `JvmGenRuntimeCache.memo("key"): """|…|""".stripMargin` — plain (NOT interpolated) but **margin-based**,
        and lazily memo-cached. Migratable: strip the `|` margins → write the post-`stripMargin` content to a
        resource (a `.scala`-fragment file), replace body with `memo("key"): JvmRuntimeResource.load("key")`.
        Byte-identity = `stripMargin` output == resource (NOT a verbatim source copy like JS). Needs a new
        `JvmRuntimeResource` loader.
      • **Rust** `RustRuntimeTemplates.scala` (1570 lines): ~17 `stripMargin` strings (migratable, same shape) +
        **1 `s"""` INTERPOLATED** template (computed at runtime — CANNOT move to a static resource; leave it).
        Needs a `RustRuntimeResource` loader.
      • Scope: feasible + bounded per backend, but each string needs `stripMargin`-output verification and the
        win is smaller than JS (the `|`-margin source is already editable; gain = a real `.scala`/`.rs` file with
        no margin noise + lint/highlight). Do JVM and Rust as **separate slices**. NOT a one-shot mechanical
        sweep — budget per-backend. Spec: extend `specs/js-runtime-resources.md`.
- [x] **optics-jvm-facade** ✓ DONE 2026-06-22 (emit-lib --host jvm; native Scala optics lib, scala-cli-compiled; Rust crate + Java facade + typed/macro optics remain) — Phase 2 next host (`specs/polyglot-libraries.md` §4/§6): publish optics as a JVM
      jar facade + golden API-signature test. Optics has no `.ssc` defs (AST-level) → author a thin Scala facade
      object `Ssc.Optics` (or a `.ssc` facade) over the same 4 optic shapes; reuse `FacadeGenerator`/`ssc link
      --emit-scala-facade`/`JarCommands`. Golden: mirror the JS `optics.d.ts` golden with a Scala signature golden.
      Rust crate and Java facade later followed the same packager shape; all four optics hosts now ship.
- [x] **rust-multishot-unbounded** ✓ DONE 2026-06-23 — **Tier-3 UNBOUNDED (recursion)**: a `multi effect`
      performed inside recursion (dynamic depth) lowers via a Free-monad `MComp` builder (`fn __comp`) +
      multi-shot interpreter (`fn __run`, `resume(v)`→`__run(k(Value::from(v)))`, re-invokable `Rc<dyn Fn>`);
      runtime `MComp`+`and_then` in `runtime/effect.rs`. Recursive Amb `program(2)` → `4`, cargo-run;
      `backendRust` 252/0. + recursive/nested effectful-call reborrow fix (`&mut *_eff`). **Multi-shot effects
      on Rust are now COMPLETE for realistic programs** (Tier-1 List/Option, Tier-2 static-nested, Tier-3
      unbounded recursion). Follow-ups (additive, no consumer): loop-form unbounded, op-args/multi-op in Tier-3.
- [x] **rust-effects-multishot-r6** ✓ ACTIONABLE SCOPE DONE 2026-06-22 — bounded Rust multi-shot support is done:
      Tier-1 List (`effect-multishot` bench now runs on rust), Tier-1 Option, and Tier-2 static-depth general
      handlers all landed and cargo-ran (`RustGenMultiShotTest`: List, Option, 1-flip Amb, 2-nested-flip Amb).
      Unbounded **recursion** later landed too (`rust-multishot-unbounded`, 2026-06-23, Free-monad MComp); only
      the *loop* form (vs recursion) remains additive with no current consumer. No Rust code in this closeout.
- [x] **rust-multishot-r6-closeout** ✓ DONE 2026-06-22 — docs-only closeout for R.6 after bounded Rust multi-shot
      slices landed. Updated the detailed `rust-effects-multishot-r6` SPRINT entry to actionable-scope done and
      replaced the obsolete BACKLOG wording that said the Rust bench was unavailable; the only deferred work is unbounded
      perform-in-loop / explicit trampoline, with no current consumer.
- [x] **rust-multishot-board-reconcile** ✓ DONE 2026-06-22 — docs-only cleanup after R.6 Tier-2 nested/static-depth landed.
      The older open `[ ] rust-effects-multishot-r6` entry later in `SPRINT.md` is stale/duplicative: Tier-1 List,
      Tier-1 Option, and Tier-2 static-depth are all done; only unbounded perform-in-loop remains, explicitly
      additive with no current consumer. Marked the duplicate open entry as superseded by the detailed `[~]`
      status above; no Rust code touched. Verify: `rg -n "^- \\[ \\] \\*\\*rust-effects-multishot-r6" SPRINT.md`
      returns no matches.

### ▶ Newly queued (2026-06-22, with Sergiy — "бери все эти задачи если других нет, заноси в спринт")

Queued after closing rust-web-toolkit follow-ons + fixing the index-read move bug it shipped.

- [x] **worktree-guardrail** ✓ DONE 2026-06-22 (`bffef3447`, mellow-shrew, with Sergiy) — structural fix so
      feature commits can't land in the shared `main` checkout again (root cause of the parked-feature-branch
      mess: a prior session committed rust-web-toolkit directly in shared main instead of a worktree, partly
      due to the `EnterWorktree` false-positive, claude-code #27881). **`.githooks/pre-commit`** blocks a
      non-`.work/` commit when in the main checkout (`git-dir==git-common-dir`) OR on branch `main`; feature
      worktrees unaffected; `--no-verify` escape hatch. **`scripts/new-worktree <name>`** = external-path
      worktree recipe (NOT under `.worktrees/`, which siblings prune). **`scripts/setup-hooks`** sets
      `core.hooksPath`. Spec `specs/worktree-guardrail.md`; `scripts/test-worktree-guardrail` 5/5.
      **ACTIVATED** on the shared repo (`core.hooksPath=.githooks`) + verified live: a feature commit in
      shared main is refused, a `.work/` coordination commit passes. (Other clones: run `scripts/setup-hooks`
      once; worktrees off current `origin/main` already carry `.githooks/`.)

- [x] **rust-cargo-smoke-coverage** ✓ DONE 2026-06-22 (`2c8032a5c`, mellow-shrew) — `RustGenCargoSmokeTest`:
      a Rust-toolchain-gated suite (`assume(cargoAvailable)` — probes `cargo --version` directly, since
      `backendRust` doesn't depend on the CLI's `RustToolchain`) that emits a feature-exercising program
      to a temp crate, `cargo run`s it, and asserts real stdout. Covers collection ops (take/drop/
      takeRight/dropRight/sorted/distinct/sum), string ops (replace/startsWith/endsWith/contains), and
      the `Vec<String>` index-read regression (E0507). Closes the move/borrow/type bug class the
      string-match suite can't see. `backendRust` 236/0. BACKLOG `rust-backend-cargo-smoke-coverage` landed.

- [x] **metaprogramming-v2-track-c2** ✓ DONE 2026-06-22 (mellow-shrew, with Sergiy — CONSERVATIVE slice).
      Probed first: the full ambition (Typer over expanded code + map errors to `.ssc` positions) is a real
      trap — both expanders flatten trees→string→re-parse (positions destroyed; a position map would have to
      be built inside 4 hand-written char-scanners) AND full inference over expanded macro-runtime constructs
      risks false positives (confirmed; spec deferred it for good reason). Built the SAFE slice instead:
      `MacroCodegen.expansionTypeWarnings` (wired into `ssc check` `checkOneFile`) catches a macro/inline
      **expansion** that references an undefined name (source type-checks, expansion doesn't). **Zero false
      positives** via a pre/post `Reference to undefined name` DIFF (machinery cancels; user's own undefined
      names stay with the normal check); warning-only; file-level (no position map); excludes builtins/stripped
      names/`_`-helpers; never breaks `ssc check`. Reach is bounded by the strict Typer's position-sensitive
      undefined-name check (val-rhs/bare-stmt). `MacroCodegenTest` +5 (broken→1, valid const-fold/direct-quote/
      interpreter→0, no-op→0); core artifact+typer 496/0; verified end-to-end via `ssc check`. Spec
      `specs/arch-metaprogramming-v2.md` C2 updated. DEFERRED still: precise positions + full-inference recheck.

### ▶ emit-js whole-program effect analysis (2026-06-22, with Sergiy — "берись, запиши в спринт, напиши спеку, и делай") — busi-reported #3, transitive piece

Closes the last open piece of the emit-js effect-handler cluster (BUGS.md
`jsgen-emitjs-effect-handler`; #1/#2/#4 done, #3 core done on `6def53541`, #5
documented). Spec: **`specs/emitjs-effect-whole-program.md`**. The per-module
`EffectAnalysis` doesn't see effects reachable through a 3+-level import chain
(busi: `ledger.accountBalance` → `journal.query` → `Journal`), so a function
calling a transitively-imported effectful function isn't CPS-lowered and its Free
value leaks at runtime. Raw `emit-js` of such a program throws on Node; the JIT
path is fine.

- [x] **emitjs-effect-whole-program** ✓ DONE 2026-06-22 — busi `ledger.ssc` (+ obligation/plan/payment/gate/income) now run end-to-end as raw `emit-js` standalone bundles on Node; guard `tests/conformance/effect-transitive-handler.ssc` (3-level, INT==JS==JVM); busi `make v2-test`+`v2-test-js` + cross-backend green. (1) `JsGen.analyzeEffects` collects trees
      recursively across the import graph (reuse `genImport`'s resolution; parse
      once; visited-set for cycles) and runs `EffectAnalysis.analyze` on the union;
      (2) `effectOps`/`effectfulFuns`/`multiShotEffects` become shared constructor
      params threaded to child gens (like `topLevelConsts`), populated once by the
      entry gen's whole-program pre-pass; (3) drop the now-redundant per-`genImport`
      `analyzeEffects`+merge. Guard: `tests/conformance/effect-transitive-handler.ssc`
      (3-level, INT==JS==JVM) + `ssc emit-js tests/v2/ledger.ssc | node` runs e2e +
      `CrossBackendPropertyTest`/conformance/busi `make v2-test`+`v2-test-js` green.

- [x] **emitjs-standalone-frontiers** ✓ DONE 2026-06-22 (claude-code, `fix/js-standalone-frontiers`) —
      closes the three remaining busi standalone-bundle frontiers recorded under
      `jsgen-emitjs-effect-handler` so `tests/v2/{trust,qr}.ssc` now run end-to-end as raw
      `emit-js | node` bundles and `ksef.ssc` passes `node --check`. Three JS-codegen fixes +
      one refinement: (1) `Term.ApplyUnary` CPS-lowers an effectful operand (`!x`/`-x`) via
      `_bind` instead of `_run`-wrapping it outside the handler (fixes `trust.ssc`); (2) `_dispatch`
      routes `Array.fill/tabulate/range/empty` to the `List` companion since `Array(...)` emits a
      bare native-constructor value (fixes `qr.ssc`); (3) the 14 std/fs file-ops are seeded into
      `declaredBindings` so importing them never re-emits a colliding top-level `const readFile`
      (fixes `ksef.ssc` syntax); (4) refined the `fn-typed-field` `_dispatch` guard from a blanket
      "_type instance → return field as-is" to a precise variadic-lambda check, so genuine zero-arg
      methods (`JsonValue.asString`) auto-invoke again (`json-value` FAIL→PASS). Guards:
      `tests/conformance/{js-applyunary-effect-cps,array-companion-statics}.ssc` + the existing
      `fn-typed-field`/`json-value`. **Before/after emit-js+node sweep over all 113 conformance
      tests: zero PASS→FAIL regressions** (82→85 PASS); busi `make v2-test`+`v2-test-js` green
      (26 files, both backends).

- [x] **emitjs-standalone-capability** ✓ DONE 2026-06-22 (claude-code) — the follow-on frontier:
      emit `nowMillis` (clock) + crypto capabilities into the raw `emit-js` standalone bundle so
      `inbox`/`ksef`/`repo*` run under `ssc emit-js | node`. Two bugs (see BUGS.md
      `jsgen-emitjs-capability-standalone`): (1) a `RuntimeCall` intrinsic (`nowMillis`→`Date.now`)
      reached via the CPS path wasn't rewritten — `genCpsApply` now applies it (new helper
      `intrinsicRuntimeTarget`); (2) a `std/crypto` extern (`sha256`) bound to the `undefined` host
      stub and shadowed its `_sha256` intrinsic — `genObjectAsExpr` now falls back to the intrinsic
      target (guarded by `typeof` + `target != fname` so std/auth's identity webauthn externs don't
      self-reference→TDZ). Standalone emit-js+node sweep **13/21 → 20/21** v2 domain files; guards
      `tests/conformance/{js-cps-intrinsic-rewrite,js-crypto-extern-standalone}.ssc` (INT==JS);
      before/after conformance sweep **zero PASS→FAIL** (84→84); busi `make v2-test`+`v2-test-js`
      green. **Remaining:** `auth.ssc` standalone needs Node WebAuthn impls (host-only externs, no
      `_webauthn*` preamble) — a separate feature, not a capability-emission gap.

### ▶ Core-minimization + polyglot-libraries program (2026-06-22, with Sergiy — "минимизировать ядро всех рантаймов и компиляторов, все вынести в библиотеки и плагины" + "сделать все переиспользуемым со всех рантаймов — из скалы, джавы, джаваскрипт, раста — в виде библиотек, сначала написать спеку")

Two complementary directives, ONE program. **Design spec written: `specs/polyglot-libraries.md`**
(grounded in a full core-vs-plugin extraction analysis). A self-contained module is the unit of reuse:
extract a feature behind the SPI (A) → publish it as a per-host library (B) is the same artifact.

**DECIDED DIRECTION (2026-06-22, with Sergiy — "вынести в плагины всё что возможно"; spec §7a):**
**B→A (enabler-first)**; language forms + hot-path stdlib stay core **forever**; **hybrid** distribution
(essential plugins bundled, advanced opt-in via `pkg:`). Task sequence:

- [x] **coremin-prelude-spi** ✓ KEYSTONE DONE 2026-06-22 (`0ef0bde11`, mellow-shrew) — the SPI hook so a
      plugin declares its check-time public symbols WITH type-signatures and `ssc check` resolves AND
      type-checks calls to them, no hardcoded core list. Decided shape: names+full signatures. Reuse, don't
      invent: `ExportedSymbol` already encodes typed symbols; `InterfaceScope.parseSType`/`parseKind`
      (made `private[scalascript]`) invert `SType.show`. **`Backend.preludeSymbols: List[ExportedSymbol]`**
      (chose the flat symbol list over a full `ModuleInterface` wrapper — no magic/abiVersion/sourceHash
      boilerplate); Typer gains a `preludeSymbols` ctor param → `createPrelude` defines each with its declared
      type (not the untyped `variadic`); `ssc check` (`Main.scala`) collects
      `BackendRegistry.inProcess.flatMap(_.preludeSymbols)` + threads it in; `pluginBuiltins` (names-only) kept
      as fallback. Additive/no-op when empty. Proof `TyperPreludeSymbolsTest` (without→undefined; with→resolves;
      declared type flows — return-mismatch flagged, correct call passes); typer+artifact 499/0. Spec
      `specs/core-min-prelude-spi.md`. NOTE: hook lives at the Typer/`check` layer only (codegen backends are a
      separate concern).
- [x] **sprint-stale-open-items-reconcile** ✓ DONE 2026-06-22 — reconciled stale open items that are already superseded/done.
      **How:** mark `coremin-prelude-migrate-ORIG` as superseded by the immediately preceding
      `coremin-prelude-migrate` finding, and mark `polyglot-phase2-optics-allhosts` as complete because
      JS/JVM/Rust/Java optics hosts now all ship (`optics-emit-lib-cli`, `optics-jvm-facade`,
      `polyglot-optics-rust`, `polyglot-optics-java`). Do not change code. Leave genuinely open items
      (`coremin-actors-migrate`, `coremin-hybrid-split`, `core-min-phase3plus`, etc.) untouched.
      **Verify:** grep shows no open `[ ]` entries for `coremin-prelude-migrate-ORIG` or
      `polyglot-phase2-optics-allhosts`; active claims remain unchanged.
- [x] **coremin-prelude-migrate** ✓ ACTIONABLE SCOPE DONE 2026-06-22 — bundled-effect runner prelude migration is complete: 16 bundled-effect runner names moved from the hardcoded Typer prelude into plugin `preludeSymbols`, and the unused typed `runnerType2` helper was removed. This closes the safe actionable scope for this item. Remaining prelude work is split into separate items: advanced/non-bundled `pluginObjects`/`pluginBuiltins` strict opt-in via complete plugin `preludeSymbols`, plus Stream/Actors runner extraction.
  **UPDATE 2026-06-22: finding (2) partially DISPROVED for VARIADIC runner names.** `runRandom` (proof, `754139832`) + a batch of 6 more (`runRetry`/`runRetryNoSleep`/`runCache`/`runCacheBypass`/`runClock`/`runEnv`) now migrate cleanly off `effectBuiltins` into their plugins' `preludeSymbols` — a variadic block-form runner needs NO effect-type to travel (it types as `def … : Any`), so it does NOT wait on `coremin-effecthandlers-spi`. **7 bundled-effect runner names now off the core prelude; locked by `PreludeMigratedRunnersTest` (668/0).** STILL blocked: the NON-bundled `pluginObjects`/`pluginBuiltins` names (→ `coremin-hybrid-split`). Remaining bundled variadic runner candidates: audit `effectBuiltins` for any not-yet-migrated (e.g. `runStorage`/`runTx`/`runActors`/`runAsync` — only if their plugin is default-bundled AND the keyword is variadic).
  **UPDATE-2 2026-06-22: finding (2) FULLY DISPROVED for bundled runners — even the TYPED ones migrate.** `runRandomSeeded`/`runClockAt`/`runEnvWith` (formerly `runnerType2` `s.define`s) are now in their plugins' `preludeSymbols` too. The unlock: the typer does **NOT enforce effect discharge** (no "unhandled effect" diagnostic anywhere in `lang/core/.../typer/`), so the runner's `! Eff` row is tracked-but-not-checked → declaring the name `Any` is sufficient for `ssc check`; the interpreter resolves the runner via the plugin's block-form, not the typer type. So typed runners do NOT wait on `coremin-effecthandlers-spi` after all. **Production-soundness CONFIRMED:** `installBin` stages all of `allPlugins` (effect plugins included) onto the shipped classpath, so `BackendRegistry.inProcess.flatMap(_.preludeSymbols)` loads them in the real `ssc check` (the `cli/run` compile classpath lacking them is a dev-only artifact). **10 bundled-effect runner names now off the core prelude** (`runRandom` + 6 variadic + 3 typed); `PreludeMigratedRunnersTest` 671/0.
  **UPDATE-3 2026-06-22: SWEEP COMPLETE — the last 6 bundled runners migrated.** `runLogger`/`runLoggerJson`/`runLoggerToList` (logger-plugin), `runState` (state-plugin), `runHttp`/`runHttpStub` (http-plugin) are now in their plugins' `preludeSymbols`; the now-unused typed `runnerType2` prelude helper was removed (`runnerType` stays for `runStream`). **16 bundled-effect runner names total are off the core prelude; only `runStream` remains** (owned by `coremin-stream-migrate`). Verified runtime-unaffected: `StdEffectsTest` runs `runHttp`/`runState`/… end-to-end (15/0). `PreludeMigratedRunnersTest` locks all 15 migrated runners (677/0). **This sub-thread of `coremin-prelude-migrate` (bundled effect runners) is now DONE.** Remaining prelude work is entirely on the OTHER two axes: NON-bundled `pluginObjects`/`pluginBuiltins` names (→ `coremin-hybrid-split`) and the Stream/Actors runners (entangled, separate SPI additions).
  **UPDATE-4 2026-06-23: runStream prelude name MIGRATED — the runner prelude axis is now 100% (`coremin-stream-prelude-migrate`).** `runStream` + the `Stream` object moved from the hardcoded Typer prelude into `StreamsInterpreterPlugin.preludeSymbols` (`ExportedSymbol("runStream","runStream","def","Any")` + `("Stream","Stream","object","Any")`); the now-dead `runnerType`/`bodyWithEff` typer helpers were removed (core compiles strict `-Werror`). This is the **prelude-name** axis only — Stream's RUNTIME (Free-monad driver + `tryStreamEmitWhileFast` FastTier + `installStreamGlobal`) stays in core per `coremin-stream-migrate` (a `BlockForm` only sees `SpiValue`, no AST). streams-plugin is bundled (installBin stages it; META-INF/services Backend provider) → production `ssc check` resolves via `BackendRegistry.inProcess`. `PreludeMigratedRunnersTest` now locks 16 runners incl. `runStream` (16/16). **NO effect-runner name is hardcoded in the core Typer prelude anymore.** (Pre-existing unrelated failure observed: `StreamsPluginInterpreterTest` "runStream result supports runForeach" — `var buf` captured in `runForeach` loses the first emission; fails on clean origin/main too → filed separately as a runtime var-capture bug, NOT introduced here.)
  **UPDATE-5 2026-06-23: ACTORS keyword set + ADVANCED-OPTIN prelude names DONE — the prelude is now fully minimized.** (a) actors-prelude (`2d9b02588`): ~55 actor/process/cluster keywords → `ActorsInterpreterPlugin.preludeSymbols`. (b) advanced-optin (Sergiy chose "strict opt-in for advanced names"): the hardcoded `pluginObjects`/`pluginBuiltins` PLUGIN-owned names moved to their owning plugins' `preludeSymbols` by tier — essential (Source→streams, setHttpServerBackend→ws, http→http; auto-loaded, no UX change), advanced (oauth/oidc→oauth, Wallets/X402*/Cardano*/PaymentConfig/DefaultSyncBackend/basicRequest→payments, spark/PipelineModel→SparkBackend; resolve only via `--plugin` = strict opt-in). `pluginObjects` deleted; `pluginBuiltins` 21→11 (only interpreter-core globals Async/Await/Signal/Future/Storage + stdlib-.ssc HandlerRegistry/Cluster/ShuffleStage/Stage/runDistributed/runDistributedShuffle remain — no owning compiled plugin). `AdvancedOptInPreludeTest` (710/0). **Caught+fixed a PRE-EXISTING regression**: `algebraic-effects.ssc` (uses runState/runLogger/… = extracted plugins) was still in the cli core-smoke `runnableExamples` (no plugins) → failed at runtime `Undefined: runState` since the first effect extraction; moved it to `PluginExamplesSmokeTest`. **The Typer prelude `effectBuiltins` (language forms + not-yet-extracted runners runAsync/runAuthWith/runStorage/runTx/httpClient/async-primitives + test helpers) and `pluginBuiltins` (11 core/stdlib names) are now the irreducible hardcoded remainder** — everything plugin-owned is declared by its plugin. LESSON: run the cli `ExamplesSmokeTest` after ANY effect extraction (effect examples become plugin-backed, the cli smoke interp has no plugins).
- [x] **coremin-prelude-board-closeout** ✓ DONE 2026-06-22 — docs-only closeout for `coremin-prelude-migrate`
      after UPDATE-3. Marked the actionable scope done, kept future work explicit under the advanced strict
      opt-in and Stream/Actors entries, and added the `CHANGELOG.md` note. No Typer/plugin code changed.
      **Verify:** grep shows no open `[~] coremin-prelude-migrate` and no open
      `[ ] coremin-prelude-board-closeout`; conflict-marker grep is clean.
- [x] **coremin-prelude-migrate-ORIG** ✓ SUPERSEDED 2026-06-22 — original blind-migration plan is superseded
      by the `coremin-prelude-migrate` finding above. The original blocker framing is now stale:
      `coremin-hybrid-split` landed, bundled-effect runner typing proved unnecessary for plugin
      `preludeSymbols`, and the remaining prelude work belongs to separate advanced strict opt-in and
      Stream/Actors tasks. Do not re-claim this original plan as-is.
- [x] **coremin-http-migrate** ✓ DONE 2026-06-22 (`f8f9ac4d3`, mellow-shrew) — the Http effect runner
      (`runHttp` real I/O + `runHttpStub(routes)` stub) extracted from interpreter core into the
      already-bundled `http-plugin`'s `blockForms` — 8th effect off core. Two new SPI capabilities:
      `BlockContext.makeRecord` (handler replies with a `Response` record) + `BlockContext.featureLocal`
      (handler reads the base-url/timeout/retry config the core `httpClient(baseUrl)` form sets).
      `HttpEffectRunner` ports the java.net request logic (Option-based). Removed from core: EvalRuntime
      cases + 2 `reservedApplyHeads` + `EffectHandlers.httpRun`/`doHttpRequest`. `httpClient(baseUrl)` setter
      stays core by design. Tests moved StdEffectsTest→HttpEffectPluginTest (4/4, lazy ServiceLoader);
      StdEffectsTest 15/15. NOTE follow-up: `Interpreter.mkHttpCtx` now dead (minor cleanup).

- [x] **coremin-actors-board-reconcile** ✓ DONE 2026-06-22 — collapsed duplicate open `coremin-actors-migrate` entries.
      **How:** keep one actionable actors item that states the real blocker (scheduler/message-loop seam)
      and mark the older duplicate as superseded; do not touch code or claim the actual actors migration.
      **Verify:** grep shows exactly one open `[ ] **coremin-actors-migrate**` in `SPRINT.md`.
- [x] **coremin-actors-migrate** ✓ SUPERSEDED 2026-06-22 — duplicate of the more precise
      `coremin-actors-migrate (A, entangled)` item below; keep that one as the single open actors entry.
- [x] **coremin-effecthandlers-spi** ✓ RECONCILED → SUBSUMED 2026-06-22 (mellow-shrew). The "3rd keystone
      hook" turned out already covered by the **block-form SPI** (the 1st keystone): a plugin owns a custom
      effect's `Perform` resolution via `Backend.blockForms` (`BlockForm.effectName` + `EffectHandler.reply`),
      dispatched through the core `runWithHandler` trampoline — proven by **8 effects** migrated this way
      (Logger/Random/Clock/Env/State/Retry/Cache/Http). The capability set is complete: stateful per-op reply,
      config args (`newHandler`), closure-apply (`applyFn`), record-build (`makeRecord`), feature-local-read
      (`featureLocal`), result-combination (`result`), stdout (`out`). No separate hook needed.
- [x] **coremin-stream-migrate** ✓ ACTIONABLE SCOPE CLOSED 2026-06-22 — investigated and deliberately deferred; the Stream effect stays in core for now because extraction is low-ROI without a clean consumer for new SPI.
      `runStream` has a **FastTier** (`tryStreamEmitWhileFast`, AST-level `while … Stream.emit` bypass of the
      Free-monad trampoline — zero-FlatMap fast path) that is interp-internal and CANNOT move to a plugin
      (a `BlockForm` only sees `SpiValue` replies, no AST). So a migration is necessarily *partial*: the
      ~40-line `streamRun` handler could move (it'd need a new trampoline **terminate-signal** SPI for
      `Stream.complete/error` short-circuit + `BlockContext.callGlobal` for `Source.from`), but the
      `runStream` case + FastTier + `installStreamGlobal` stay in core. ~40 lines shrunk for real complexity +
      a shared-trampoline change → not worth it. The two new SPI capabilities (terminate-signal + callGlobal)
      are designed + validated (runWithHandler: a resolver returning `Pure(term)` abandons the body) — add
      them only when a clean consumer appears. No code changed for this closeout.
- [x] **coremin-actors-migrate** ✓ DONE (superseded by coremin-actors-codemove, 4578c8e4f) — provider seam + prelude migration + session slice all landed 2026-06-22/23; the "optional hard code-move" was completed by the dedicated `coremin-actors-codemove` task (2026-07-02). Full history:
      `specs/coremin-actors-plugin.md` (`6538c10c6`) defines the interpreter-local actor runtime seam.
      `ea898ca82` adds `ActorRuntimeProvider` / `ActorRuntimeHost`; `ActorInterp.actorInterp` now dispatches
      through `CoreActorRuntimeProvider`, which delegates to the existing core scheduler, so behavior is unchanged.
      `539105e3c` adds the essential bundled `runtime/std/actors-plugin` skeleton, ServiceLoader descriptor,
      provider installation via `ActorRuntimeProviderBackend`, actor `preludeSymbols`, and
      `ActorsPluginProviderTest` (2/0). `cli/installBin` passed and now stages 26 essential `.sscpkg` files
      plus 13 advanced.
      Verified: `backendInterpreter/compile` passed; actor targeted suites
      (`ActorSupervisionTest`, `ActorStopOutsideTest`, `ActorGroupTest`, `ActorDistributedTest`) passed 29/0
      (ScalaTest printed a reporter `InterruptedException`, but sbt finished `[success]`).
      **PRELUDE-NAMES SLICE DONE 2026-06-23 (this session):** the ~55-name actor/process/cluster keyword set
      (`runActors` + spawn/self/send/receive/timeout/recvFrom + membership/leader/gossip/config/drain/metric +
      timers) is now removed from the Typer `effectBuiltins` and DECLARED in `ActorsInterpreterPlugin.preludeSymbols`
      (bundled → production `ssc check` resolves via `BackendRegistry.inProcess`; runtime stays in core via the
      seam, so `spawn`/`self`/… still resolve through `ActorInterp`/`ActorGlobals`). Verified runtime-unaffected:
      `ActorDistributedTest`+`ActorBinaryWsTest` 53/0; `ActorsPreludeMigrationTest` locks a representative name per
      category; typer 196/0, plugin-tests 693/0. `effectBuiltins` now holds only language forms + the not-yet-bundled
      runners (runAsync/runAuthWith/runStorage/runTx/httpClient/async primitives) + test helpers.
      **SESSION-SEAM SLICE DONE 2026-06-23:** `ActorRuntimeProvider` now opens a per-host
      `ActorRuntimeSession`; `ActorInterp` lazily caches one session per `Interpreter` and clears it when a
      replacement provider is installed. This records the state ownership boundary before any future runtime code
      move, without moving scheduler code today. Verified:
      `cd /Users/sergiy/work/my/scalascript-wt-core-min-phase3plus && sbt "actorsPlugin/compile" "backendInterpreter/compile" "backendInterpreterPluginTests/testOnly scalascript.ActorsPluginProviderTest"`
      passed 3/0, and `cd /Users/sergiy/work/my/scalascript-wt-core-min-phase3plus && sbt "backendInterpreter/testOnly scalascript.ActorSupervisionTest scalascript.ActorStopOutsideTest scalascript.ActorGroupTest scalascript.ActorDistributedTest scalascript.ActorBinaryWsTest"`
      passed 53/0 (known ScalaTest reporter `InterruptedException`, sbt `[success]`).
      **Remaining (the hard code-move, optional):** move `ActorRuntime`, scheduler loop, `handleActorOp`, and
      cluster/event drains behind the provider into `runtime/std/actors-plugin`; keep `receive` syntax capture in
      core. **Gotcha:** do not store actor/cluster mutable state on the ServiceLoader backend singleton; today's
      state is per `Interpreter`, so the move slice needs per-host/per-interpreter state ownership. This code-move is
      a large interpreter-internal refactor with NO user-visible change (the seam already lets the runtime live
      either side); deferred as low-ROI like Stream. **Net: the coremin prelude + extraction program is at its
      practical end — all bundled effects + actor names off core, hybrid-split done; only the optional Stream/Actors
      interpreter-internal code-moves remain, both deliberately deferred.**
- [x] **coremin-hybrid-split** ✓ DONE 2026-06-22 (codex) — no-domain hybrid plugin distribution slice.
      `PluginSpec` now carries an essential/advanced tier; `installBin` stages 25 essential bundled
      `.sscpkg` files in `bin/lib/compiler/plugins` (auto-loaded) and 13 advanced bundled `.sscpkg`
      files in `bin/lib/compiler/plugin-available` (opt-in via `ssc --plugin <path>` or
      `ssc plugin install <path>`). No registry domain or hosting required. This slice deliberately did NOT remove
      Typer hardcoded advanced compatibility names; that strict opt-in prelude cleanup later landed in
      `advanced-optin` (2026-06-23). Verification: `cd /Users/sergiy/work/my/scalascript-wt-coremin-hybrid-split && sbt "cli/compile"` passed in 82s; `cd /Users/sergiy/work/my/scalascript-wt-coremin-hybrid-split && sbt "cli/installBin"` passed and produced the two directories/counts above. Bonus guardrail: `installBin` now fails if the explicit `pluginPkgs` list is missing or duplicating an `allPlugins` id; this caught and fixed the pre-existing omission of `fs`/`os`/`yaml` from staged `.sscpkg` files.

- [x] **polyglot-libraries-spec** ✓ SPEC CLOSED 2026-06-22 — `specs/polyglot-libraries.md` now reflects that the
      original draft has implementation slices landed. It unifies A (minimize core) + B (cross-language reuse);
      the original baseline found ~6–7.5K LOC of feature code still baked into interpreter core, but since then
      the block-form SPI, typed `SpiValue`, plugin `preludeSymbols`, multiple effect migrations, JS runtime-resource
      extraction, and no-domain bundled plugin distribution split have landed. Remaining implementation work is
      tracked by separate active/deferred items (`coremin-actors-migrate` optional hard code-move,
      `core-min-value-unification` deep value refactor).
- [x] **core-min-phase1-logger-keystone** (A — the SPI keystone) ✓ KEYSTONE PROVEN END-TO-END 2026-06-22. The
      block-form + effect-handler plugin SPI now works: a plugin can contribute a `keyword { body }` effect-runner
      and the interpreter dispatches to it. 5 increments on origin/main: (1) `c2eec8d3c` generic effect trampoline
      `EffectHandlers.runWithHandler`; (2) `f2d8b5304` SPI contract `BlockForm`/`EffectHandler`/`BlockContext`;
      (3) `7dc508c3b` made it **type-safe** — a host-neutral `SpiValue` ADT instead of `Any` (per Sergiy's review);
      (4) `af58335bc` interp wiring — `valueToSpi`/`spiToValue`, a `_blockForms` registry populated by
      `installPlugins`/`ensurePluginsLoaded`, and an `EvalRuntime` generic block-form case; (5) `0a578ab88` **proof**:
      `reservedApplyHeads` fast-path also excludes `interp.blockForms` names so a plugin keyword reaches the
      dispatch (empty until a plugin loads → plugin-free scripts unchanged). `BlockFormSpiTest`: a `runTally { }`
      plugin block-form + stateful handler → `25`, Int args/replies round-tripped `Value↔SpiValue`. **No
      regression** (StdEffectsTest 48/0, InterpreterTest 141/0). Historical follow-up status: the template was
      used for Logger/Random/Clock/Env/State/Retry/Cache/Http; actors use the separate provider/session seam
      because they own a scheduler rather than a simple block-form handler.
- [x] **core-min-logger-migrate** (A) — ✓ DONE 2026-06-22 (`0353e51ae`). Logger fully extracted from
      interpreter core into `runtime/std/logger-effect-plugin` (`LoggerEffectPlugin extends Backend` with
      `blockForms = Map(runLogger→text, runLoggerJson→json, runLoggerToList→collect-with-`result`-tuple)`,
      handlers over `SpiValue`/`ctx.out`) + `META-INF/services/scalascript.backend.spi.Backend`; build.sbt wired
      via the `allPlugins` registry (`PluginSpec("logger", …)` → auto aggregate + `installBin` + plugin-tests
      classpath). Removed from core: 3 `runLogger*` cases + the 3 names in `reservedApplyHeads` (`EvalRuntime`),
      `loggerRun`/`loggerToListRun`/`loggerJsonStr` (`EffectHandlers`; generic `runWithHandler` stays). The 4
      Logger tests moved `StdEffectsTest`→`LoggerPluginTest` (`interpreter-plugin-tests`) and run with NO
      `installPlugins` — proving production lazy-ServiceLoader dispatch. Verified: StdEffectsTest+InterpreterTest
      **185 green**, LoggerPluginTest+BlockFormSpiTest **7 green**. This became the reusable template for the
      later Random/Clock/Env/State/Retry/Cache/Http plugin migrations; actors use the separate scheduler seam.
- [x] **core-min-random-migrate** (A) — ✓ DONE 2026-06-22 (`2d525ea59`). Random extracted to
      `runtime/std/random-effect-plugin` (`RandomEffectPlugin`; one `RandomBlockForm` registered under both
      `runRandom` and `runRandomSeeded`; per-block `java.util.Random`, replies over `SpiValue` —
      nextInt/nextDouble/uuid/pick, `pick` round-trips arbitrary list elements via `SpiValue.Opaque`). **This
      slice GENERALIZED the block-form SPI to CONFIG ARGS** — `keyword(config…){body}`, not just `keyword{body}`:
      `dispatchBlockForm` now evaluates leading config terms → `newHandler(ctx, cfgArgs)` (the seed). Added the
      generic *curried* block-form cases in `EvalRuntime` (loaded + lazy-load mirror), placed AFTER all hardcoded
      curried special-forms (runClockAt/runEnvWith/httpClient/…) so they only catch genuinely-unmatched applies.
      Removed core `randomRun` + 2 cases + 2 `reservedApplyHeads` names. Tests moved
      `StdEffectsTest`→`RandomPluginTest` (no `installPlugins`). Verified: StdEffectsTest+InterpreterTest **179
      green**, RandomPluginTest+LoggerPluginTest+BlockFormSpiTest **13 green** + full-suite sweep.
- [x] **core-min-clock-env-migrate** (A) — ✓ DONE 2026-06-22. Clock + Env extracted to
      `clock-effect-plugin` + `env-effect-plugin` (one effect = one library). Both curried-config siblings, so
      they REUSE the config-args SPI path from `core-min-random-migrate` with ZERO new dispatch machinery:
      `runClockAt(t0)` → `newHandler` reads frozen-ms; `runEnvWith(map)` → reads the overlay (exercises the
      SPI's `MapV` config path). `ClockBlockForm`/`EnvBlockForm` registered under both plain+curried keywords;
      handlers reply over `SpiValue` (Clock now/nowIso/sleep, frozen=no-op; Env get/set/required with per-block
      mutable overlay + real-`getenv` fallback). Removed core `clockRun`/`envRun` + 4 cases + 4
      `reservedApplyHeads` names. Tests moved `StdEffectsTest`→`ClockPluginTest`+`EnvPluginTest`. Verified:
      interpreter **169 green**, full plugin-tests **647 green** (1 env-gated cancel). FOUR effects are now
      plugins: Logger, Random, Clock, Env.
- [x] **core-min-state-migrate** (A) — ✓ DONE 2026-06-22. State extracted to `state-effect-plugin`. State is
      the first NON-pure-reply effect: `State.modify(f)` must *apply a ScalaScript closure*, which the
      pure-reply SPI couldn't do. **Grew the SPI by exactly one capability — `BlockContext.applyFn(fn, args)`**
      (defaulted to throw → backward-compatible; the interpreter overrides it, routing back through
      `callValue` + synchronous `Computation.run`, parity with the old `callValue1`). `StateBlockForm` under
      `runState`; `newHandler` takes the initial state (config arg); get/set/modify reply over `SpiValue`;
      the `result` hook returns `(finalState, bodyResult)`. Removed core `stateRun` + case + `reservedApplyHeads`
      name. Tests `StdEffectsTest`→`StatePluginTest`. Verified: interpreter **165 green**, full plugin-tests
      **651 green** (1 env cancel). **FIVE effects now plugins: Logger, Random, Clock, Env, State.** Probed and
      recorded: the REMAINING runners (Retry/Cache/Http/Actors) also need interp callbacks — Retry/Cache via
      `applyFn` (thunks); Http additionally needs to construct a `Response` record (no `SpiValue` record case
      yet → would need a `BlockContext.makeRecord` or an Opaque-instance helper); Actors need the message loop.
- [x] **core-min-retry-cache-migrate** (A) — ✓ DONE 2026-06-22. Retry + Cache extracted to `retry-effect-plugin` +
      `cache-effect-plugin`, copying the State template (both re-invoke the body thunk via `BlockContext.applyFn`).
      `RetryBlockForm(sleep)` under `runRetry`/`runRetryNoSleep`; `CacheBlockForm(bypass)` under
      `runCache`/`runCacheBypass`. The Cache TTL store moved into the plugin (process-local `object CacheStore`,
      was `interp._cacheStore`); per-block `bypass` replaces the `_cacheBypass` ThreadLocal (each block's handler
      carries it; trampoline dynamic-scope == ThreadLocal). Removed from core: 4 `EvalRuntime` cases + 4
      `reservedApplyHeads` names; `EffectHandlers.retryRun`/`cacheRun`; `Interpreter._cacheStore`/`_cacheBypass`.
      Wired into `allPlugins` (auto aggregate + plugin-tests classpath) + the explicit `pluginPkgs` installBin list.
      Tests moved `StdEffectsTest`→`RetryPluginTest`(3)+`CachePluginTest`(2) (no `installPlugins`, lazy dispatch).
      Verified: plugin-tests **656/0** (1 env-gated cancel) + InterpreterTest+StdEffectsTest **160/0**. **SEVEN
      effects now plugins: Logger, Random, Clock, Env, State, Retry, Cache.** NOTE: emitters (`Retry`/`Cache`
      globals in `StdEffectsRuntime`) stay in core per the State precedent — only the heavy handlers move.
- [x] **polyglot-phase2-optics-allhosts** ✓ DONE 2026-06-22 — per-host optics library packaging now ships for
      all four hosts: JS/npm (`optics-emit-lib-cli`), JVM/Scala (`optics-jvm-facade`), Rust/cargo
      (`polyglot-optics-rust`), and Java/Maven (`polyglot-optics-java`). Spec §4 + §6. Historical blueprint:
      • Optics is **NOT** a `.ssc` module or named intrinsics — it's AST-level: `Focus[T](_.a.b)`
        (`EvalRuntime.scala:4591`→`OpticsRuntime.evalFocus`) + `Prism[Outer,Variant]` (`:4318`→`buildPrism`); JS at
        `JsGen.scala:4542`/`3746`, runtime `JsRuntimeOptics.scala` gated by `Capability.Optics`. **There is no
        exported symbol table to read — the public facade must be AUTHORED.** The canonical contract is the 4 synth
        optic shapes: Lens(get/set/modify/andThen), Optional(getOption/set/modify/andThen),
        Traversal(getAll/modify/set/andThen), Prism(getOption/reverseGet/set/modify/andThen) — IDENTICAL between
        `OpticsRuntime` (interp/JVM) and `JsRuntimeOptics` (JS). `PathStep`=Field/Some/Each/Index/AtKey.
      • Packaging infra TODAY: `ssc package --lib` (`SsclibPackaging.scala`) emits a `.ssclib` SOURCE zip (NOT a
        host artifact). `emit-js`/`emit-rust`/`emit-scala` emit programs. `ssc link --backend jvm --bytecode
        --emit-scala-facade` (`FacadeGenerator`) is the closest jar/facade path. **Spec §4's `emit-rust --lib` is
        FICTIONAL** — Rust lib mode = "module has no `@main`" (`RustGen.scala:62` → `renderLibRs()`/`src/lib.rs`,
        Cargo `[lib]`, golden-tested in `RustGenRuntimeFilesTest`/`RustGenCargoTomlTest`).
      • Per-host state: **JS = most tractable** (runtime exists+gated; only need ESM wrapper + `package.json` +
        hand-written `.d.ts`; no new codegen). **JVM** = facade/link-to-jar exists but optics has no compilable
        `.ssc` defs → author a thin facade. **Rust** = lib-crate skeleton exists but optic `pub fn` codegen is
        GREENFIELD. **Java** = fully greenfield (`JavaFacadeEmitter` + value-mapping seam). Golden pattern: mirror
        `RustGenCargoTomlTest` exact-string asserts, or `WireGoldenVectorTest` table.
      • **First slice = JS optics npm package**: call `JsGen.generateRuntime(Set(Capability.Optics,Core))`, wrap as
        ESM re-exporting `makeLens/makeOptional/makeTraversal/makePrism`, emit `package.json` + curated `optics.d.ts`
        (the 4 shapes above); golden test asserts the `.d.ts` + exported symbols. Then JVM/Rust/Java follow the
        same packager shape. Rank to ship: JS → JVM → Rust → Java.
      • **✓ JS SLICE LANDED 2026-06-22** — `JsLibPackager` (in `backendJs`) emits the `@scalascript/optics` npm
        ESM package (`package.json` + `index.mjs` + curated `optics.d.ts`); bundles the `JsRuntimeOptics` `_make*`
        factories + only the `_None`/`_Some`/`_isMap` deps (HAMT narrowed to native `Map` at the edge) + step
        builders; re-exports stable `makeLens/makeOptional/makeTraversal/makePrism/Some/None/field/index/at/some/each`.
        `JsLibPackagerTest` 5/5 incl. a node ESM smoke that imports the generated package + exercises all 4 optics.
        The `.d.ts` is the frozen API golden. **Later slices all landed:** (a) user-reachable
        `emit-lib --host js --feature optics -o <dir>` via `EmitLibCmd`; (b) JVM facade jar; (c) Rust crate;
        (d) Java facade. Golden API-signature tests now cover each host.
- [x] **js-runtime-resources** ✓ DONE 2026-06-22 (optics pilot) — first slice of polyglot-libraries §3 #8:
      move JS backend runtime fragments out of big Scala string constants into real `.mjs` resource files
      (lintable / `node --check`-able / editor-friendly). `JsRuntimeResource.load(name)` reads + caches a
      classpath resource under `/scalascript/js-runtime/`; `JsRuntimeOptics` is now a thin wrapper
      (`load("optics.mjs")`) keeping its `val X: String` API → call sites + emitted JS unchanged, verified
      **byte-identical** (7555B, `diff`-empty; `JsLibPackager` golden+node-smoke unchanged). `JsRuntimeResourceTest`
      5/5. Spec `specs/js-runtime-resources.md`. **✓ REST DONE 2026-06-22 (js-runtime-resources-rest):** the
      remaining 17 fragments (`Part1a`–`d`, `Part2a/2b`, `AsyncA/B`, `Signals`, `Dataset`, `IndexedDb`,
      `BrowserPatch`, `Graphql`, `Mcp`, `McpBrowser`, `Payment`, `V14Effects`) all migrated — `diff`-verified
      byte-identical, backendJs compiles, 65 JS codegen tests green. **§3 #8 closed for JS** (all 18 fragments
      now `.mjs`; the `JsRuntime`/`JsRuntimeAsync` aggregators in `JsGen.scala` stay computed). FOLLOW-UPS: same
      pattern for JVM/Rust runtime strings; optional `tsc --checkJs`/`eslint` CI gate (needs JSDoc first).
- [x] **rust-effects-multishot-r6** ✓ SUPERSEDED 2026-06-22 — duplicate of the detailed `[~] rust-effects-multishot-r6`
      status above. Tier-1 List, Tier-1 Option, and Tier-2 static-depth are done; remaining unbounded
      perform-in-loop is additive with no current consumer. ORIGINAL: multi-shot algebraic effects on Rust (resume invoked
      more than once, e.g. NonDet `{1,2}×{10,20}`). One-shot handle/resume already SHIPPED (`a87afba34`, tagless-
      final, no trampoline). lucky-otter flagged multi-shot as out-of-scope/hard: needs an `FnMut` continuation
      that can be re-invoked — the tagless-final one-shot lowering (`resume(v)`→`v` tail-substitution) can't express
      it. RESEARCH slice: probe whether a captured-closure continuation (`Box<dyn FnMut>`) or a CPS/defunctionalized
      re-entry is tractable in `RustCodeWalk`'s handle lowering; if not bounded, SCOPE DOWN + document the blocker
      in `specs/rust-effects.md` §R.6 and BACKLOG. Spec `specs/rust-effects.md`. Lower confidence than the other two.
- [x] **core-min-phase3plus** ✓ ACTIONABLE SCOPE DONE 2026-06-23 — the practical core-min/polyglot Phase 3+
      queue has landed or been split into sharper items. Landed: Logger/Random/Clock/Env/State/Retry/Cache/Http
      effect runners moved to plugins; JS/JVM/Rust runtime resources moved out of backend string blobs where
      bounded; optics ships as native JS/JVM/Rust/Java host libraries via `emit-lib`; bundled prelude names are
      minimized (`runStream`/`Stream`, actors keyword set, and advanced/essential plugin-owned names now come from
      plugin `preludeSymbols`); actors have a provider + per-interpreter session seam. Not closed here:
      `core-min-value-unification` stays as its own deep refactor, and the hard Stream/Actors interpreter-internal
      code moves stay deferred/optional because they have low ROI without a new consumer.
- [x] **core-min-value-unification** ✓ SCALARS-ONLY SCOPE DONE 2026-06-23 — **SPEC + Slices 1-6 LANDED**
      (`specs/value-unification.md`), on two complementary tracks. PROBED the real surface: **4387
      `Value.<Case>` sites across 46 files**; `Value` = sealed trait co-defined with `Computation`/`Env`/
      `FrameMap` (circular) + perf pools; the SPI conversion was lossless via `Opaque` EXCEPT `Char`→`StrV`
      and `Vector`→`ListV` (coerced). **Structural blockers found:** a sealed trait can't be split across
      modules, and data cases can't `extend` a core type if they must live *below* core (a `DataValue extends
      Value` marker is the WRONG direction) → end-state = standalone low-module `DataValue` enum + `Value =
      DataValue | carriers`, `type SpiValue = DataValue`, conversion deleted. NO early slice deletes duplication
      (payoff lands at the final merge), so the work is a sequence of safe always-green slices.
      **Track A — SpiValue completion:** added `SpiValue.CharV`/`VectorV` so the SPI boundary is LOSSLESS for
      all immutable data cases (mutable `Array` + case instances stay `Opaque`, correct); `SpiValueDataRoundTripTest`,
      plugin-tests 712/0. **Track B — disentangle `Value.scala`:** extracted `Computation`+runtime signals →
      `Computation.scala` and `Env`/`FrameMap`/`MutableEnvView` → `Env.scala` (byte-identical, zero-behavior;
      InterpreterTest 158/0, effects 33/0, closure/pattern/tuple 186/0). **Slice 3 spike DONE 2026-06-23:**
      validated `type Value = DataValue | Callable` (union) + `export DataValue.*` from `object Value` — existing
      `Value.IntV(n)` construct + `case Value.IntV(n)` patterns compile unchanged, DataValue lives below core,
      exhaustiveness preserved under -Werror (rejected: `DataValue extends Value` marker; bare union w/o export).
      **SCOPE DECISION 2026-06-23 (Sergiy): SCALARS-ONLY — full merge OFF the table.** The container/closure
      obstacle: the interp stores closures INSIDE containers (`List(() => 10)` = `ListV(List(FunV))`), so a
      fully-merged low data type would force closures-as-`Opaque` → a cast on the HOT function-dispatch path
      (perf regression Sergiy declined). So only the scalar leaves are shared; containers + carriers stay core;
      the conversion shrinks (scalars→identity) but is NOT deleted. **Slice 4 DONE 2026-06-23:** flipped `Value`
      to a union `type Value = DataValue | ValueRest` — `DataValue` (new enum, `DataValue.scala`) = 9 scalar
      leaves; `ValueRest` (sealed) = 14 container/instance/carrier cases; `object Value` re-exports scalars via
      `export DataValue.*` so all ~4387 sites are UNCHANGED. Astonishingly clean: the ONLY friction was one
      `java.util.Arrays.sort` over a union array (→ `Array[AnyRef]` cast); exhaustiveness preserved. Verified
      core+backendInterpreter+all plugins+server+dap compile; core/test 1019/0, plugin-tests 712/0, broad
      interp/value/effects 218/0, numeric/collection/JIT 77/0 (~2026 green). **Slice 5 DONE:** moved `DataValue`
      to a new low leaf module `lang/value-data` (below core+backendSpi). **Slice 6 DONE:** `SpiValue` is now
      `type SpiValue = DataValue | SpiRest` — scalar leaves are the SAME shared `DataValue` classes (SpiRest =
      SPI-private containers + Opaque; `object SpiValue` re-exports `DataValue` w/ `StringV as StrV`, so the 9
      plugins + all `SpiValue.*` sites are unchanged); `valueToSpi`/`spiToValue` convert scalars by IDENTITY.
      **✅ SCALARS-ONLY UNIFICATION COMPLETE** — one shared set of scalar classes across `Value` + `SpiValue`;
      the scalar half of the conversion is gone; the container half stays by design (closure-bearing obstacle).
      plugin-tests 712/0, round-trip+effects+numeric 183/0. The actionable scope of this task is now CLOSED
      (full merge deliberately off — perf). Original goal/notes below (NOTE: the "delete the conversion / one
      type" end-state is SUPERSEDED by the scalars-only decision — the container half is correct to keep).
      <br>**Goal (original):** collapse the duplication
      between the interpreter's `Value` and the SPI's `SpiValue` into ONE value type. Today they're separate by
      necessity: `interpreter.Value` (in `core`) is entangled with *execution* — `FunV(closure: Env)`,
      `NativeFnV(f: List[Value] => Computation)`, mutable `InstanceV`, `type Env = Map[String, Value]` — and
      `backendSpi` (which `core` depends on, not vice versa) can't reference it, so the boundary uses the
      host-neutral `SpiValue` (+ a `Value↔SpiValue` conversion). **Goal:** un-entangle `Value` from execution —
      split the *pure-data* cases (`Int/Double/Str/Bool/Char/Unit/List/Vector/Array/Map/Tuple/Option/Instance`)
      from the *runtime-carrier* cases (closures/native-fns hold an `Env`/`Computation`), moving closures +
      `Computation` out of the `Value` ADT into a separate runtime structure. Then the data ADT can live in a
      low shared module and **be** `SpiValue` — one value type across interp + SPI + host libraries (Task B),
      deleting the conversion. **Caveat (why it's LATER):** it's a deep refactor touching every `Value` match in
      the interpreter (DispatchRuntime/PatternRuntime/EvalRuntime), and it still privileges the interpreter's
      shape, so it's lower-priority than the keystone extractions; the current `SpiValue` (= the safe data
      subset) is correct in the meantime. **Verify:** full interp suite green; `Value↔SpiValue` conversion gone;
      no `Env`/`Computation` reachable from the SPI value type.

### ▶ Prioritized build queue (2026-06-18, with Sergiy — "внеси всё и делай автономно")

The genuine remaining **autonomously-actionable** build work, in priority order. Drive top-to-bottom,
one theme at a time, per-feature worktrees + claims. Everything below the queue is either history (`[x]`)
or blocked/deferred (kept for record, NOT actionable now — see "Excluded from the sprint").

> **Status 2026-06-18 (autonomous pass):** queue worked top-to-bottom. #1 meta-v2-track-c —
> verified already complete (no build). #2 sbt-plugin dep-resolution — ✓ built + tested (residuals
> design-/Maven-gated). #3 wasm-effects — **effectively COMPLETE**: arithmetic (2a) + `_dispatch`
> collection-HOFs (2b) + multi-shot (2c) + cross-module (2d) all built + run-verified on node (36 tests);
> `@main` args/non-Unit edge later closed by `wasm-main-edge` (40 tests). #4 build-registry-phase4 — assessed, no concrete target → no
> action. Then `sscBackends` cross-build ✓ DONE (user picked spec open-Q #2 → parallel outputs in one
> `compile`; scripted `cross-build/`). **What remains is Maven-gated only:** Maven Central + Plugin Portal
> publication (LAST, explicit-go). No bounded autonomous build work left.

### ▶ Quality / perf queue (2026-06-20, with Sergiy — "все эти задачи занеси в спринт и начинай делать")

After the perf series (foldLeft VM compile + typeclass-fold memo) micro-throughput is at the floor. The
next autonomously-actionable work is quality + unmeasured-axis perf, priority order. Drive top-to-bottom,
per-feature worktrees + claims.

> **Status 2026-06-20 (queue worked top-to-bottom — ALL DONE):** #1 real-workload-perf ✓ all three axes:
> (a) cold-start AppCDS −51% + harness, (b)+(c) steady-state server RSS+GC harness (~195 MB STABLE, no leak).
> #2 xbackend full+CI ✓ generator already broad (12 kinds) + wired into CI. #3 xbackend-test-hardening ✓
> `runCaptured` hang-proof runner. #4 rust-web-toolkit ✓ verified essentially complete + shipped the one
> bounded deferred slice (set/toggle client wiring); rest is browser/rozum-driven. **Queue fully resolved.**
> Follow-ups also DONE 2026-06-20 (per "сделай всё кроме maven"): **xbackend hang-proof sweep** — converted
> all 17 deadlock-risk (both-streams) subprocess-test files to `ProcTestUtil.runOrThrow`/`runCaptured` (the
> 22 single-stream `redirectErrorStream` files are deadlock-safe + behaviour-subtle → left as-is, standard
> set for new tests); 54 converted tests run green. **Server leak-hunt** — 4-min sustained-load run:
> definitively no leak (RSS peaked 205 MB, *ended 80 MB* as the JVM reclaimed heap; GC light/steady). **Only
> Maven publication (gated, excluded) + rozum/browser-driven rust refinements remain.**

### ▶ Rust-web computed-signal queue (2026-06-20, with Sergiy — "делай всё, заноси в спринт и делай")

The rust-web S5 refinements turned out to be autonomously buildable + curl/cargo-verifiable (set/toggle,
SSE, computed-read compile+SSR all DONE). Remaining, priority order:

- [x] **computed-live-recompute** ✓ DONE 2026-06-20 — computed signals are now fully reactive. Moved the
      signal store to `value.rs` (so `signal_value` can read it) + a computed-closure registry +
      `ssc_register_computed`/`ssc_recompute_all`; `_ui_computed_signal` is a re-runnable `Fn` returning a
      NAMED signal; `/__ssc/push` recomputes before broadcasting (SSE). **Verified cargo+curl:** push a dep →
      the computed signal auto-updates (`{"__c0":"fr"}` → `{"__c0":"de"}`). `backendRust` 224/0.
- [x] **computed-typed-reads** ✓ DONE 2026-06-20 — `collectLocalSignals` carries the element type; the apply
      emits `.parse::<i64>()`/`.parse::<f64>()` for `Signal[Int]`/`[Double]`, `.show()` for String. Verified:
      `signal("n", 10)` + `n() + 5` → `15`. `backendRust` 225/0.
- [x] **direct-WS** ✓ DONE 2026-06-20 — a `serve(view)` program also exposes a WS signal endpoint on
      `port + 1` for external clients (rozum bridge), bidirectional + sharing the SSE store/broadcast/recompute.
      `ssc_ws_serve` (accept_async) sends state on connect, streams updates, and an incoming `name=value` frame
      sets+recomputes. **Verified cargo + raw-WS client (python):** WS-push `locale=de` → `{"__c0":"de"}`.
      `backendRust` 226/0. **rust-web S5 now FULLY COMPLETE** (set/toggle, SSE, computed compile+SSR + live
      recompute, typed reads, direct-WS — all built + cargo/curl/WS-verified).

### ▶ Benchmark perf-divergence queue (2026-06-21, with Sergiy — "разбирайся в чем дела — в jit? В codegen? В bench?")

The big per-workload outliers from the same `./bench.sh` sweep, each ROOT-CAUSED by hand (emit + read the
generated code / toggle the JIT). Verdict per case: **codegen**, **jit**, or **bench** (intentional anti-fold).

- [x] **asm-jit-effect-pathology** (JIT) ✓ DONE 2026-06-21 — `ssc-asm` `effect-oneshot` **9.46 → 0.032
      ms/iter**, now effectively matching default `ssc` (0.025 ms/iter). Root cause: Javac bytecode JIT lowered
      active one-shot tail-resume effect ops through `JitGlobals.resolveEffectLong*`, but ASM `walkLong` did
      not, so `Bump.tick().toLong` bailed out to the slow effect trampoline. Fix `0d5e03b87`: ASM mirrors the
      resolver lowering and treats resolved effect calls as Long-shaped for `.toLong`/`.toInt`. Verified with
      `AsmEffectJitTest`, `EffectOneShotFastPathTest`, `JitLintTest` (85/85), `sbt -no-colors cli/installBin`,
      and `./bench.sh effect-oneshot --backend ssc{,-asm}`.
- [x] **js-tuple-monoid-alloc** (CODEGEN) ✓ DONE 2026-06-21 — **`js` `tuple-monoid` 7.40 → 2.60 ms (2.85×)**,
      no longer the slowest cell. Two general JsGen fixes: (1) `t._N` on a statically-known tuple lowers to a
      direct `t[N-1]` array read (new `tupleVars` tracking + `isTupleExpr`), skipping the megamorphic
      `_dispatch(t,'_N',[])`; case classes never match `isTupleExpr` so their Product `._N` is untouched.
      (2) a tuple-LITERAL concat `(a,b) ++ (c,d)` flattens into ONE `Object.assign([a,b,c,d],{_isTuple:true})`
      instead of `_tupleConcat(Object.assign(..),Object.assign(..))` (3 allocs → 1); a variable operand still
      uses `_tupleConcat`. **Verified:** 281 JS unit tests green; interp == js on tuple flatten/`._N`/show/eq.
      NOT done (left): native `+` for the `_arith('+')` on tuple-element reads (needs tuple-element type
      tracking) — lower value. The `s` LCG interp/js delta in this workload is the separate 64-bit-Long-on-JS
      precision limitation, not a tuple bug.
- NOTE (no task — **bench**, intentional): rust `arith-loop` **1.52 ms (4.7× jvm)** is largely the harness's
      anti-fold — `run.sc` wraps every rust closure body + per-iter reassignment in `std::hint::black_box(...)`,
      blocking LLVM loop optimization (the comment at `run.sc:176` even tunes this so rust "stops looking 3–4×
      slower"). Not a codegen bug; leave as-is unless we want a lighter rust anti-fold.

### ▶ Benchmark backend-gap queue (2026-06-21, with Sergiy — "Запиши в спринт все n/a")

Every `n/a` from a full `./bench.sh` sweep (31 workloads × ssc/ssc-asm/jvm/js/rust), each VERIFIED by hand
against the current toolchain (the corpus comments were stale). The bench measures time only (no correctness
check — that's `CrossBackendPropertyTest`, green); `n/a` = that backend's emit/build/run failed.

- [x] **rust-effects-handle-resume** (R.4.2, ONE-SHOT) ✓ DONE 2026-06-22 — **`effect-oneshot` n/a → 0.0020 ms
      on rust** (the fastest backend on it). Custom algebraic effects with explicit `handle`/`resume` now
      compile + run on rust via **tagless-final traits** (per `specs/rust-effects.md §10`), NOT the Free-monad
      CPS port the old `rust-backend.md §R.4` implied — so the `while`-loop case needs **no trampoline** (the
      loop runs directly; `Bump.tick()` is `_eff.tick()`). 3 gaps implemented: (1) a custom `effect E:` object
      emits a `trait ${E}Effect` with required methods (`collectEffectOps` + `renderTaglessEffectsRs`); (2)
      `Eff.op(args)` → `_eff.op(args)`; (3) `handle(body){ case Eff.op(binders, resume) => arm }` → a handler
      `struct __H_E; impl ${E}Effect for __H_E { fn op(&mut self, binders) -> ret { <resume(v)⇒v> } }` +
      `{ let mut _eff = __H_E; <body> }`. **Verified:** minimal probe cargo-builds → `10`; the real
      `effect-oneshot.ssc` workload → `962` (== interp/jvm); `backendRust` 230/0 + 3 new `RustGenR44Test`
      cases. **Remaining (R.6 follow-up, NOT this task): multi-shot.** `effect-multishot` stays `n/a` — its
      `opts.flatMap(opt => resume(opt))` calls `resume` many times, which a single trait-method return can't
      model (needs FnMut continuation re-invocation); it fails cargo cleanly (out of scope by design).
- [x] **jvm-multishot-result-type** ✓ DONE 2026-06-21 — `effect-multishot` was `n/a` on **jvm** because
      CPS def emission widened total handled-effect wrappers from their declared result type to `Any`:
      `def workload(seed: Long): Long` emitted as `def workload(seed: Long): Any`, and the bench wrapper's
      typed sink failed with `Found: Any; Required: Long`. Fix (`39b7c665f`): keep declared non-effect-row
      result types at CPS def boundaries and cast the final CPS result there; effect-row defs (`A ! Eff`)
      still return `Any` so handlers can unwrap Free computations. Guard: `JvmGenEffectsRuntimeTest`
      `addLong(workload(0L))` e2e. **Verified:** `backendInterpreter/testOnly scalascript.JvmGenEffectsRuntimeTest`
      34/34; `sbt -no-colors cli/installBin`; `./bench.sh effect-multishot --backend jvm` `n/a` -> 0.075 ms.
- [x] **rust-either-chain-closure-type** (E0282) ✓ DONE 2026-06-21 — `either-chain` was `n/a` on **rust**
      (`cargo build` → `error[E0282]: type annotations needed` because the chained `match match match …`
      emitted each Either arm as `(move |x| { … })(v)`, whose closure param type rustc couldn't infer). Fix:
      a new `inlineArm` lowers a 1-param Either map/flatMap/fold arm to a `{ let x = v; body }` block instead
      of an immediately-applied closure — the `let` flows `x`'s type straight from `v`. Function-reference args
      keep `(f)(v)`. **Verified:** `cargo build` green; interp == rust (`R=632`); `./bench.sh either-chain
      --backend rust` n/a → **0.0040 ms**; `backendRust` 229/0 + a new `RustGenR23Test` E0282 regression test.
- [x] **bench-stale-jvm-na-hygiene** ✓ DONE 2026-06-21 — the stale JVM `n/a` was not a cache issue; it shared
      the `jvm-multishot-result-type` root cause. Total CPS wrappers declared as `Long` emitted as `Any`, so
      the bench sink rejected both `effect-oneshot` and `effect-multishot`. Corpus comments were refreshed.
      **Verified:** `./bench.sh effect-oneshot --backend jvm` = 0.160 ms; `./bench.sh effect-multishot --backend jvm`
      = 0.075 ms; `./bench.sh effect-oneshot effect-multishot --backend js` = 0.347 / 0.224 ms.

### ▶ Improvement queue (2026-06-20, with Sergiy — "занеси все в спринт и делай")

Fresh do-soon queue after rust-web S5 closed. Work top-to-bottom, one claim/worktree per slice. Maven Central
publication remains explicit-go only; the registry work below is intentionally domain-independent first.

- [x] **wasm-main-edge** ✓ DONE 2026-06-20 — closed the last WASM effects tail. Effectful WASM now derives
      the user `@main` from the AST, preserves a single Scala 3 `@main` parameter clause (including
      `String*` splicing), discards non-`Unit` returns in the synthetic wrapper, and rejects raw
      `Array[String]` `@main` args with a clear "use `String*`" diagnostic. **Verified:**
      `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/wasm-main-edge && sbt "backendWasm/testOnly scalascript.codegen.WasmBackendTest"`
      → 40/40 green. Gotcha recorded in `specs/wasm-main-edge.md`: Scala.js ES-module launcher argument
      delivery is out of scope; a direct Node probe supplies empty `String*` args.
- [x] **stable-plugin-spi-p3** ✓ DONE 2026-06-21 — completed one small Phase 3 SPI cleanup slice:
      `bench-plugin` now implements `Bench.opaque` through `PluginNative.eval` / `PluginValue` instead of
      importing `scalascript.interpreter.Value` directly. Added `BenchIntrinsicsTest` to lock identity
      behavior (including empty args -> `Unit`) and to scan `bench-plugin/src/main` for direct interpreter
      imports so this slice does not regress. **Verified:** `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/stable-plugin-spi-p3 && sbt -no-colors "benchPlugin/test; pluginApi/test; benchPlugin/checkPluginBoundary"`
      → `BenchIntrinsicsTest` 2/2 green, `PluginApiTest` 14/14 green, `benchPlugin/checkPluginBoundary` green.
- [x] **js-char-wrapper-string-map** ✓ DONE 2026-06-21 — added a JS `_Char` box (`JsRuntimePart2a`):
      `valueOf`→code point, `toString`→1-char string (so concat/arith/`_show` coerce). Iterated chars
      (`map`/`filter`/`foreach`/`flatMap`/`charAt`/`head`/`last`/`toList`/`forall`/`exists`/`count`) box;
      `String.map` returns a String only when every result is a `_Char`, else a Seq (mirrors `strMapResult`).
      `_dispatch` got a `_Char` branch mirroring the interp `dispatchChar` (`toInt`→code, `isDigit`/`toUpper`/
      `asDigit`/…); `_eq` bridges `_Char` ↔ 1-char String literal and ↔ Int. `CrossBackendPropertyTest`
      "String.map char vs non-char" now asserts interp == JS == JVM (+ a char-method map/filter case).
      **Verified:** 280 JS unit tests green (23 suites, 0 fail); String.map + string-method-gaps cross-backend
      green on all 3 backends; direct node probe matches interp byte-for-byte. Residual (BUGS.md): a char
      *literal*'s `.toInt` (`'5'.toInt`) still diverges (literals stay strings to avoid literal-pattern
      `===` codegen) — separate, lower-value follow-up.
- [x] **rust-web-example** ✓ DONE 2026-06-21 (a55e101f2) — added `examples/rust/web-signals.ssc`
      (signal + computedSignal + signalText + serve), emit-rust + `cargo build` green, binary serves SSR and
      `/__ssc/push?name=locale&value=de` recomputes the computed signal (`{"__c0":"fr"}` → `{"__c0":"de"}`).
      Building it (vs the string-match tests, which never cargo-build) surfaced + fixed **two real bugs**:
      (1) computed move-closure use-after-move (cargo E0382) — `renderClosure` now clone-captures read signal
      locals; new regression test, backendRust 228/0; (2) docs showed `POST /__ssc/push -d` but the endpoint
      reads query params `?name=&value=` — corrected example + rust-backend.md + user-guide.md.
- [x] **real-workload-perf** (roadmap-next #1) ✓ DONE 2026-06-20 (all three axes). **(a) cold-start:**
      `tests/perf/coldstart/` + AppCDS in `bin/ssc`/`install.sh` → **378 → 182 ms (−51%)**, peak RSS −32%.
      **(b)+(c) steady-state RSS + GC:** `tests/perf/serverrss/` boots a real server under load → interp
      server **~195 MB RSS, STABLE** (no leak), light GC (~41 pauses/27 ms). Long minutes-scale leak-hunt
      left to demand (`secs=300+`). BACKLOG `real-workload-perf`.
- [x] **xbackend-property-equivalence (full + CI)** ✓ DONE 2026-06-20 — broaden was already complete (12
      kinds incl. effects/Option/Either/closures/nested; node leg 74 programs / 0 skipped) so the work was
      reconciling that + **wiring into CI**: added Node.js setup to the `sbt` job so the interp==JS
      differential now runs in CI (it was skipping). Made hang-safe first (next item). BACKLOG `xbackend-property-equivalence`.
- [x] **xbackend-test-hardening** ✓ DONE 2026-06-20 — root cause was NOT bloop per se: `runProc` read
      subprocess streams with blocking `mkString` BEFORE the bounded `awaitExit`, so a wedged child parked
      the read forever (and could pipe-buffer-deadlock). Fixed via `ProcTestUtil.runCaptured` (threaded
      stream drain + hard timeout that actually fires); `ProcTestUtilTest` proves a `sleep 60`@2s returns
      <15s + a stderr flood doesn't deadlock. `CrossBackendPropertyTest.runProc` delegates. (~9 other test
      files share the old antipattern but run fixed small programs — follow-up sweep, lower risk.)
- [x] **rust-web-toolkit finish** ✓ VERIFIED ESSENTIALLY COMPLETE 2026-06-20 (the "~56 cargo errors" was
      badly stale). Checked against the authoritative signal: **`backendRust` 221/0**, **`RustGenWebToolkitTest`
      17/17** green. Per `specs/rust-web-toolkit.md`: cargo `build` of the std/ui crate is **290 → 0** (whole
      toolkit compiles on Rust), **S4** named/curried args DONE, **S5a** (SSR initial value) + **S5b.1** (local
      client reactivity) + **S5b.2 A/B/C** (generic push / rozum bridge / computed-derived) all DELIVERED at
      poll-transport depth. **REMAINING = explicitly-deferred refinements**, NOT bounded build work: SSE/WS
      streaming transport, client recompute of computed signals, set/toggle/show client wiring, direct-WS
      client. All are **browser-dependent** (can't verify autonomously without a browser) and **rozum-driven**
      (spec method: "drive from the target … ultimately `rozum-web.ssc`"). Hand back to the rozum driver; do
      NOT push speculative client-JS refinements onto `feature/rust-web-toolkit` (rozum's active branch).


- [x] **meta-v2-track-c** ✓ DONE 2026-06-18 (verified, no build needed) — Track C is COMPLETE. C1
      (multi-clause inline) ✓ done 2026-06-18. C2's high-value slice ✓ already done + wired:
      `MacroCodegen.codegenWarnings(module)` is computed in `ssc check` (`Main.scala:5265`, merged into
      `CheckResult.errors:5267`) and warns up-front on interpreter-only macros that can't compile to JVM/JS —
      `MacroCodegenTest` 6/6 green. The remaining C2 ambition (run the Typer over *arbitrary* macro-expanded
      source, map type-errors to `.ssc` positions) is **DEFERRED by design** in the spec: needs a position
      map (re-parse loses positions) + risks false positives (Typer may not grok expanded macro-runtime
      constructs), niche audience — low ROI vs the codegen warning that covers the real failure mode. Building
      it now = busywork against the spec's own judgment. **→ Next pick: sbt-plugin-finish.**
- [x] **board-meta-v2-reconcile** ✓ DONE 2026-06-21 — removed stale meta-v2 Track C/C2 "still open"
      guidance from the board.
      **How:** reconcile `SPRINT.md`'s later `[~] metaprogramming-v2` paragraph and `BACKLOG.md` roadmap text
      with the authoritative `meta-v2-track-c` done entry plus `specs/arch-metaprogramming-v2.md` §4b, which
      says the remaining arbitrary post-expansion re-typecheck ambition is deferred by design. Keep the
      historical spec rationale; change only active queue/backlog wording so future agents do not pick C2 as
      buildable work. **Verify:** targeted grep now leaves only spec/history/deferred wording; active
      `SPRINT.md`/`BACKLOG.md` guidance no longer presents C2 as buildable work.
- [~] **sbt-plugin-finish** (roadmap #4, Phase 5) — **dep-resolution ✓ DONE 2026-06-18**: the concrete
      actionable Phase 5 slice. `SscFrontMatter` lifts `.ssc` front-matter `dependencies:` `dep:` Maven
      coords into `sscManagedDependencies` → `libraryDependencies` (Java `%`, Scala-cross `%%`, local paths
      ignored); scripted `dep-resolution/` + full scripted suite green (9). Spec §3h/Phase 5 reconciled.
      **`sscBackends` cross-build ✓ DONE 2026-06-18** (user picked spec open-Q #2 → design A = parallel
      outputs in one `compile`): `sscBackends: Seq[String]` (default `Seq(sscBackend)`); `sscCompile` forks
      `ssc build --backend <b>` per backend — single = flat dir (backward-compat), multiple = per-backend
      subdirs. Scripted `cross-build/`; full suite green (10). RESIDUALS (NOT done): (a) LSP/BSP "polish" —
      `BspIntegration`/`sscBspSetup` already landed Phase 4, no concrete remaining deliverable; (b) Maven
      Central publish + Plugin Portal — Maven-gated (LAST). So the only buildable remainder here is
      Maven-gated.
- [x] **wasm-effects** ✓ COMPLETE 2026-06-20 — additive, wasm-only.
      **arithmetic ✓ DONE (slice 2a):** `_binOp` (+`_bigIntOp`/`_bigDecOp`) — `a + b`/`sum * 2` over effect-op
      results link + run (test → 40). **`_dispatch` ✓ DONE (slice 2b):** collection HOFs on `Any` —
      `xs.map(..).filter(..).head` in a handler links + runs (test → 6); copied the pure subset of `_dispatch`
      + `_seqX`/`_seq`/`_isFree`, reflection fallback → clear error. **multi-shot ✓ DONE (slice 2c):** did NOT
      need a `_handle` rewrite (probe disproved it) — just the pure `_anyFlatMap` helper + a `usesEffects` fix
      to recognise `multi effect Foo:`; NonDet `{1,2}×{10,20}` runs on node (test → 4). **cross-module ✓ DONE
      (slice 2d, no code change):** an imported `effect` already works — `generateUserOnly` resolves imports via
      `baseDir`; run test → `hello\nworld`. **`@main` args/non-Unit edge ✓ DONE (wasm-main-edge):** effectful
      `@main` wrappers preserve Scala 3 main parameter clauses, discard non-Unit returns, and reject invalid raw
      `Array[String]` args clearly. **Complete:** common + advanced cases all run; `WasmBackendTest` 40/40 green.
      BACKLOG `wasm-effects`.
- [x] **build-registry-phase4** ✓ ASSESSED → no action 2026-06-18 (demand-driven). Surveyed the ~24
      `*Registry` classes: they are domain-distinct (Preprocessor / Interpolator / Backend / Capability /
      Route / Command / GlueClasspath / GlueJsPreamble / …), each registering a different kind of thing —
      **not** a duplicated template. The closest pairs (`Glue*`, `Interpolator*`) are small and cohesive;
      consolidating them would be speculative refactoring, exactly what the spec's "only where they remove
      real duplication" guard rules out. No concrete duplication target → no build. Revisit only if one
      appears. (Phases 1–2 landed; Phase 3 moot/load-bearing.)

---

- [x] **rust-web-toolkit** (external driver: rozum) — bring the declarative std/ui toolkit
      (`vstack/heading/text` → `lower(theme)` → `View` → `serve(view, port)`), which works on JVM,
      up on the **Rust** backend via an HTML/SSR binding (operator path A; native GUI rejected as
      too costly). **DONE 2026-06-19:** I1 `s"…${expr}…"` splices + S1a HTML/SSR View primitives
      (`element/textNode/fragment` → `runtime/ui.rs`, gated) + S1b `renderHtml` SSR — `textNode`/
      `fragment` compile AND run end-to-end (`renderHtml(...)` → escaped HTML via `ssc run-rust`).
      `backendRust` 211/0. + S1c `element` (`->` → tuple; non-empty `Map(k->v)` → HashMap-insert;
      `_ui_element` key-sorted attrs) — `renderHtml(element("div",Map("class"->"root"),…))` →
      `<div class="root" …>…</div>` end-to-end, `backendRust` 212/0. + S2 `serve(view, port)` SSR
      overload (`_ui_serve` in `http.rs`, gated on uiUsage) — `curl :8099` → SSR'd HTML, proven
      end-to-end, `backendRust` 214/0. + S1d void elements (`<meta>` self-close) + **capstone
      `examples/ssr-page.ssc`**: full nested HTML page built from primitives → `ssc build-rust` →
      `curl :8123` returns the SSR'd page. **The Rust-SSR web goal is reachable today via primitives.**
      + **S3 (a–k) the std/ui library now CODEGEN-transpiles** (import inliner + block exprs +
      partial fns + patterns + placeholder `_`-lambdas + varargs type + `++`/try/null + struct
      field types + String-match `.as_str()` + opaque-type mapping + signal SSR stubs). Cascade:
      codegen 28→11→6→3→**0**; cargo 290→170→108→70→**56**. **REMAINING:** a finicky cargo
      type-reconciliation tail (~56: TkNode/i64 + String/Value + struct-field i64 + curried-vararg
      **call-site** `vec![]` wrapping + `defaultTheme` val) — converging, multi-session. Then S4
      named/curried args · S5 signal reactivity (stubs are static-only). Spec `specs/rust-web-toolkit.md`.
      **✓ CLOSED 2026-06-22:** S1–S5 all landed on `origin/main` (S4 named/curried args + omitted-default
      fill; S5 SSR + local client + server-push + SSE/direct-WS + computed live recompute + typed signal
      reads — see CHANGELOG 2026-06-19/06-20). The driving use case `examples/rozum-meeting.ssc` builds to a
      binary and SSRs over hyper. General Rust-backend follow-ons (Vec `take/drop/sorted/distinct`, String
      `.replace`, http prefix-routing/no-store/POST-body/MIME, indexable `split/toList`) landed on main via
      `rwt-followons` (613c2bb21, `backendRust` 233/0). The `feature/rust-web-toolkit` branch is rozum's own.

- [x] **agent-sdk-remainder** ✓ DONE 2026-06-17 (actionable scope) — consolidated `specs/agent-sdk.md`
      + **P3a MCP bridge both directions** (`runtime/std/agent-mcp.ssc`: `serveAgentToolsMcp` +
      `mcpToolSource`; examples `agent-mcp-{server,toolsource}.ssc`; all `ssc check` OK). Loop
      conformance already covered by `AgentSdkInterpreterTest`. DEFERRED (reasons in spec): bridge
      round-trip test (heavy jvm/js infra for thin glue), golden transcripts, P3b embedded (blocked
      on rozum `rozum-embed`). spec `specs/agent-sdk.md`. → **Next: package-registry.**

- [x] **package-registry** (roadmap #3) ✓ DONE 2026-06-17 — found ALREADY BUILT (spec was stale):
      `ssc search`/`info`/`add` over `RegistryClient` (URL-priority + 1h-TTL cache + `--refresh`) +
      seed `registry/packages.yaml`. spec `specs/arch-registry.md` reconciled. Added the minor
      `--offline` flag (cached-only search, `RegistryClient.loadOffline()`). REMAINING (external only):
      the `scalascript/registry` GitHub repo + Pages HTML + validate/publish CI.

- [x] **sbt-plugin-finish** ✓ ACTIONABLE SCOPE DONE 2026-06-18 — this duplicate open marker was stale.
      Front-matter `dependencies:` → Coursier and `sscBackends` cross-build are done + scripted-tested;
      LSP/BSP Phase 4 already landed with no concrete remaining deliverable. Publishing the plugin artifact
      itself is the deferred Maven Central / sbt Plugin Portal step and remains excluded from autonomous work.

- [x] **metaprogramming-v2** ✓ ACTIONABLE SCOPE DONE 2026-06-21 — AUDIT 2026-06-17: NOT a from-scratch build. All three
      phases have working bases (P3 Linker `inlineTable`/`expandInlineSource`; P4 `${impl('x)}` + direct
      `'{ $x+1 }` + interp parity + `MacroImpl` IR; P5 runtime `Mirror` + user `derived(m: Mirror)`).
      PROGRESS: **Track A** (P5 cross-backend derives conformance) ✓ DONE (A1a/b/c + A2 + A3,
      2026-06-17; only deferred edge cases remain — sum-type/enum mirrors, generics, mixed-derives clauses).
      **Track B** (P4 const-folding `Expr.asValue match`): **B1 + B2 ✓ DONE 2026-06-18** (interp splice
      unwraps `Expr(v)`; `Linker.expandMacroSource` const-folds literal args to the `Some` branch, else the
      `None` direct quote; `LinkerRewriteTest` +7 / `InlineDerivesTest`; `examples/quoted-macro-constfold.ssc`).
      **B3 ✓ DONE 2026-06-18 — JVM + JS** (was blocked — quoted macros were interpreter-only): the
      `macro-codegen-backends` pass (`MacroCodegen.expand`, hooked into `JvmGen` + `JsGen` generate entry
      points) expands + strips macros pre-codegen, no-op for macro-free modules;
      `QuotedMacroJvmConformanceTest` (scala-cli) + `QuotedMacroJsConformanceTest` (node) match interp.
      **Track B is complete (B1+B2+B3).** **Track C:** C1 (multi-clause inline) ✓ DONE 2026-06-18
      (curry tail clauses into the body — no scanner/wire change); C2's practical backend guard is already
      wired through `MacroCodegen.codegenWarnings`, and the broader arbitrary post-expansion re-typecheck +
      source-positioned-error ambition is deferred by design (position-map requirement + false-positive risk).
      No bounded autonomous meta-v2 build slice remains on the board.

### Tier 2 — AUDIT 2026-06-17: most "themes" are already BUILT (specs stale)

While pulling these in I audited each against the code — and like agent-sdk + package-registry,
most are already implemented; the specs/BACKLOG were stale. So Tier 2 is mostly **reconcile +
verify residuals**, NOT from-scratch builds:

**RECONCILED 2026-06-18 (`tier2-spec-reconcile`)** — verified each theme against the code:
- [x] **theme-f-dsl-platform-hooks** — spec Status already accurate ("implemented through Phase 4",
      `InterpolatorRegistry`). No change needed.
- [x] **theme-h-library-modularity** — spec Status already accurate ("implemented through Phase 6",
      `SsclibManifest`). No change needed.
- [x] **theme-j-lightweight-ffi** — ✓ DONE: `@jvm`/`@js` (Phases 1–4) + `@rust` + **`@wasm`** all wired.
      The WASM backend exists (`runtime/backend/wasm`, Scala.js → `.wasm`); `WasmGen` lowers `@wasm("expr")`
      externs to a `def` (2026-06-18, `WasmBackendTest`). Only `@wasmExport`/`@wasmImport` (raw WASM ABI)
      stay out of scope **by design** (the Scala.js path owns the ABI). The "no WASM backend wiring" note
      was stale.
- [~] **theme-a-stable-plugin-spi** — Phases 1+2 landed (stable surface exists). Residual = **Phase 3 versioned
      stable API module → PROMOTED to active 2026-06-23** (Sergiy "внеси в спринт"); see the "Promoted to active"
      queue at the top of Active tasks.
- [x] **ssc-new-audit** ✓ DONE 2026-06-19 — verified and tightened the local `ssc new` /
      standalone-install surface without touching Maven/publication. Fixed `NewProject.create` to best-effort
      `git init -q`; fixed `ssc new` usage to list all bundled templates; made root `install.sh` match docs
      (`./install.sh` prints standalone Coursier/Homebrew/curl guidance, `./install.sh --dev` runs monorepo
      staging); clarified `specs/arch-ssc-new.md` (plugin template intentionally has no `project/plugins.sbt`;
      live channel publication remains deferred); updated the old benchmark note to use `install.sh --dev`.
      Added tests for all six templates, output-dir aliases, placeholder-free rendering, git-init, and release
      fixtures. Verify: `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/ssc-new-audit && sbt
      "cli/testOnly scalascript.cli.NewProjectTest scalascript.cli.StandaloneInstallFixturesTest"` → 8/8 green.
- [x] **board-ledger-hygiene** ✓ DONE 2026-06-19 — docs-only cleanup. Marked the duplicate
      `sbt-plugin-finish` open item as actionable-scope done/Maven-gated, and removed three stale
      `Status: open` lines inside fixed `BUGS.md` entries (`jvmgen-multishot-handle-result-any`,
      `jvmgen-handle-in-arg-position`, `js-self-handling-cps-fn-not-run`). Verify:
      `git grep -n "\*\*Status:\*\* open\|Status: open" -- BUGS.md` → no matches, and
      `git grep -n "^- \[ \] \*\*sbt-plugin-finish" -- SPRINT.md` → no matches.
- [x] **theme-b-build-registry-consolidation** — Phase 3 is **MOOT** (triaged 2026-06-18):
      `PluginManifest`/`LocalRegistry` are the **implementation** the facade is built on (not removable
      wrappers — `BackendRegistry` uses `PluginManifest`; `ImportResolver`/`PluginCommands` use
      `LocalRegistry`), and `isStdPluginInterpreterTest` is already gone. Nothing to remove. OPTIONAL
      Phase 4 (family registries) remains, demand-driven.
- [x] **module-graph-grouping** — ✓ INVESTIGATED → leave-as-is (2026-06-18, `docs/module-graph-findings.md`):
      197 modules; the per-impl module IS the SPI boundary; grouping either collapses it or is a no-op on
      the graph. No action.
- [ ] **std-nfc-packager-adapters** — BLOCKED autonomously: needs real iOS/Android/Web-NFC packager
      integration + device/browser harnesses. Native platform follow-up; can't verify without targets.
- [ ] **wallet-browser-ws-itest** — BLOCKED autonomously: real browser-WebSocket integration; full run
      needs a browser.

**Genuine remaining BUILD work** (across Tiers): no bounded autonomous build slice is currently ready here.
The old sbt-plugin build pieces are done and publication is Maven-gated; build-registry Phase 3 is moot and
Phase 4 is demand-driven; meta-v2 Tracks A/B/C are actionable-scope done with only deferred edge cases. The
small residuals above are blocked by real browser/device/external inputs. See BACKLOG "Roadmap reality check".

### Excluded from the sprint (deferred / blocked — stay in BACKLOG, NOT actionable now)

- **Maven Central + sbt Plugin Portal** (roadmap #8 / Theme C) — LAST, explicit-go only.
- **direct-style-eval** — DEFERRED, data-disproven ("do not start").
- **hof-glue-jit-compile**, **vectorize-pure-loop** — deferred perf (sub-15% ceiling / speculative SIMD).
- **agent P3b embedded transport** — blocked on rozum shipping the `rozum-embed` crate.
- **WalletConnect project-ID** — blocked on an external decision.
- **Hardware-wallet Vault (Ledger)**, **MPC Vault** — need real hardware / external SDKs; can't verify autonomously.

## Control and mixed-build extensions deferred from the base milestone (2026-07-14)

The first milestone deliberately keeps answer types stable, builds a module DAG, supports only
direct statically resolved mixed-tail SCCs, closes captured prompt binders, and executes residual
effects at the destination. The following extensions require separate designs and conformance:

- [ ] **control-answer-type-modification** — design answer-type-modifying `shift`/`reset` only after
      the answer-type-preserving ABI is stable; do not weaken the initial `Prompt[P,R]` laws.
- [ ] **mixed-build-same-module-cycles** — add a two-phase interface/body graph for Scala↔ScalaScript
      source cycles inside one module. The base build accepts an acyclic inter-module graph.
- [ ] **mixed-tail-advanced-call-shapes** — extend mixed global TCO to proven-safe
      curried/default-argument/polymorphic and indirect call shapes. The base transform rejects such
      SCCs instead of offering a partial stack guarantee.
- [ ] **saved-continuation-durable-external-prompts** — define an explicit durable prompt capability
      for a saved continuation with a free prompt reference. The base format saves only closed prompt
      binders and alpha-renames them independently per run.
- [ ] **saved-continuation-distributed-residual-forwarding** — optionally route an unhandled residual
      `Op` from a remote runner back to the originating caller/handler. The base remote API instead
      requires a closed effect row or an authenticated destination `RemoteRunEnvironment[Fx]`.
