# Conformance corpus and its freeze — backlog

Can-wait and not-yet-started work whose code lives in `tests/conformance/`. When an item is
picked up it moves to `tests/conformance/SPRINT.md` as `[~]` and gets a row on the root board —
in the same commit as the claim. Layout: `specs/work-tracking-layout.md`.

Sections below were carried over whole from the flat root `SPRINT.md`/`BACKLOG.md`,
verbatim, on 2026-07-30.

## 2026-07-30 — the 77 corpus SKIPs: 15% of the corpus where v2 has NO verdict (Sergiy: "цель — получить работающий 100% ssc v2")

Measured, not estimated: `specs/skip-triage.md` probes all 77 on the golden lane from a fresh build.
A SKIP is not "one red lane" — `contract.sc:690` establishes the golden BEFORE the `backends:` gate,
so a case int cannot run is dropped for EVERY lane. 77/528 is larger than the 30 v2 non-PASS rows the
freeze does record, and it is invisible in the PASS-cell count because those cells never exist.

- [x] **SKIP-1 — probe all 77 on the golden lane and bucket by measured cause.** Done;
      `specs/skip-triage.md`. 21 servers (SKIP correct, permanent) · 25 provider `Undefined: <Name>` ·
      11 std-module symbol gaps (the only bug class that is v1's own fault) · 2 genuinely
      nondeterministic · 18 assorted.
- [x] **SKIP-2 — `std/mcp` export lists (`9b758d807`).** 4 cases now run on the golden lane with real
      output, stable across two runs: `mcp-server-tool`, `mcp-server-tools`, `mcp-server-resource`,
      `agent-mcp-server`. v1's own `std/agent-mcp.ssc:26` was one of the broken consumers.
- [x] **SKIP-3 — DONE (`91c326d1f`) + a follow-on export fix.** The resolver now accepts both shapes;
      `Tree.collect` over the whole subtree is load-bearing (a `package:` module wraps its stats in
      `object`s, so a top-level-stat match returned `Set()` for every real std module while passing a
      package-less fixture — my own fixture hole, found by instrumenting). `dsl-sql-recovery` and
      `dsl-yaml-like` now run on the golden lane with stable output; the latter also needed six
      missing exports in `std/parsing/layout.ssc`. `dsl-mini-language` advances to
      `int-extension-on-function-type-alias-does-not-dispatch` (extension receiver is an alias of a
      FUNCTION type; dispatch sees `FunV`). **Net 6 of 77 closed.** Superseded text:
      `std-import-resolver-blind-to-type-alias-and-extension`. Cheapest item on the list: no freeze
      change to land, and it un-skips `dsl-mini-language`, `dsl-sql-recovery`, `dsl-yaml-like` — three
      PURE-COMPUTE cases, i.e. three new v2 verdicts. Not yet localized to a file: find whatever
      builds a module's exported-symbol table and teach it these two shapes. Prove it fail-first with
      the one-line consumer in the bug entry.
- [ ] **SKIP-9 — the 4 un-skipped mcp cases FAIL on v2 with `unbound global: mcpServer`.** Measured
      once they became visible: the native runtime registers no MCP server provider. Same class as the
      opt-in-provider cases. Either register it on the v2 lane or declare these cases' lanes honestly —
      but not by putting the SKIP back.
- [ ] **SKIP-8 — `int-extension-on-function-type-alias-does-not-dispatch`.** Resolve an alias to its
      underlying runtime shape at extension-REGISTRATION time, or widen the receiver's type name at
      dispatch. Shared by every lane, so it wants a fail-first test per alias shape (function, tuple,
      collection) rather than a spot fix for `Pass`. Unblocks `dsl-mini-language`.
      ⚠️ **Both candidate fix sites were held when this was filed** — registration at
      `StatRuntime.scala:582` (claim `v1-interp-object-named-arg-slot`) and dispatch at
      `DispatchRuntime.scala:79` / `:3898` (claim `infix2-jit-split`). Checked, not assumed; that is why
      it is queued rather than attempted. Re-check ownership before starting.
- [ ] **SKIP-4 — refresh the freeze for SKIP-2's four improvements** (now SIX: + `dsl-sql-recovery`,
      `dsl-yaml-like`). Blocked only by ownership:
      `corpus-baseline.tsv` / `contract-roster.tsv` are held by `v1-interp-object-named-arg-slot`.
      Should land in ONE commit naming SKIP-2.
- [ ] **SKIP-5 — `fetchUrlSignalTo`: implement it or delete the declaration.**
      `std-ui-fetchUrlSignalTo-declared-never-implemented`. Declared, exported, documented, zero
      registrations anywhere. Leaving it is the only option that keeps lying.
- [ ] **SKIP-6 — let a case DECLARE it cannot produce a golden.** ~46 cases (21 servers + 25
      provider) pay a 30 s golden probe TWICE per full run to re-prove the already-known. Needs a
      front-matter key `contract.sc` honours; `contract.sc` was held by another claim, so this is
      queued rather than attempted.
- [ ] **SKIP-7 — `sys` and `mutable` in the Undefined bucket.** Unlike the provider names around
      them these read like Scala-standard (`sys.exit`, `scala.collection.mutable`), so they may be a
      real gap rather than a by-design absence. 5 cases. Triage before assuming.

## 2026-07-28 — corpus contract: refresh the paired freeze (48 unrostered cases)

Filed by `f-try-multistmt-def-body`; **not claimed**. `BUGS.md`
`corpus-contract-roster-drift-48-cases`. `contract-roster.tsv` has one commit ever
(`fc5f07f28`); 48 cases have been added to the corpus since, and `contract.sc` exits 1 on any
case absent from the roster — so the always-on differential gate is red on `origin/main` for
bookkeeping, which is how a real regression gets to hide in the noise.

- [ ] **CCR-1 — one unsharded full-corpus `--update-baseline` run on a quiet machine.** It
      rewrites the non-PASS matrix AND the roster together, and the tool already refuses to do it
      from a scoped selection (`corpus-baseline-update-scoped-run-truncates`), so a shard or an
      `--only` cannot substitute. Reproduce the drift list first (seconds, runs nothing):
      `contract.sc -- --list | sort` vs `tail -n +2 contract-roster.tsv | sort`, then `comm -23`.
      Names + counts are frozen in the BUGS entry, so the run can be checked against them.
- [ ] **CCR-2 — classify, do not rubber-stamp.** Each of the 48 has to be looked at: a case that
      is legitimately non-PASS on a lane belongs in the baseline with that status, not silently
      absorbed as PASS. Several belong to claims that were live on 2026-07-28 (`scljet-*`,
      `std-ui-native-*`, `v2-system-clock`) — coordinate rather than overwrite.

## corpus-contract — differential gate + v2 migration portability (2026-07-14, Sergiy: "усиливаем, разбираем, портируем" + "запиши в спеку, добавь в спринт")

Spec: `specs/corpus-contract.md`. Mechanism: `tests/conformance/CONTRACT.md`. Gap map: `tests/conformance/V2-GAP.md`.
Gate: `scala-cli tests/conformance/contract.sc`. Baseline: `tests/conformance/corpus-baseline.tsv` (int golden = 0;
**js ~60, v2 ~67 non-PASS** = the migration progress bar). After a fix that closes a gap, remove its baseline line
(or `contract.sc -- --update-baseline`). `--v2` = the v1-frontend→v2-VM BRIDGE lane; the self-hosted `--bytecode`
lane is the separate `v2-native-conformance` section below.

### DONE this arc
- [x] **contract.sc** — differential gate (both corpora × int/js/v2, golden = expected-or-live-INT, frozen baseline),
  `CONTRACT.md`, nightly `corpus-contract.yml`. усиливаем: bounded parallelism (≤4) + retry-on-timeout → ~8-10 min, stable.
- [x] **V2-GAP.md** — 67 v2 failures clustered (разбираем): the gap is INTEGRATION, not language (~57% = wire an
  existing v1 plugin/intrinsic into v2; ~19% = frontend parse gaps; rest = small tail).
- [x] **js-parenless-def-value** — a bare parenless-def reference now evaluates (`f`→`f()`), not passed uncalled.
- [x] **js-user-operator-dispatch** — overloaded operators on user types (`++`/`/`/…) dispatch to their extension
  (`_tupleConcat`/`_arith` → `_dispatch` for `_type` objects); fixed dsl-ast-builder.
- [x] **KEY FINDING** — effect runners can't be portable `.ssc` (handle only catches a *syntactic inline* effectful
  call, never a param-passed body). So "портируем runtime" = eliminate cross-backend divergences, NOT rewrite.

### Strengthen — promote to per-PR
- [ ] Add memo (F2: skip cases whose source + jar identity are unchanged since last green) + batch lanes
  (F4: `run-batch --v1 / --emit-js / v2`) to `contract.sc`, per `conformance-perf.md`; then move `corpus-contract.yml`
  from nightly to a per-PR gate.
- [ ] Add the `jvm` lane (and later native/rust) to the default set once fast enough.

### js divergences (remaining, root-caused)
- [x] **js-imported-def-int-division-loses-truncation** (BUGS.md) — closes the 6 `scljet-write-*`. An Int-param
  `value / N` in a TRANSITIVELY-imported namespace-member def (`std/scljet/write.ssc` `writeBe32`) lowers to the float
  `_arith('/')` instead of `Math.trunc` because the param's Int evidence doesn't reach the emitting childGen (3+
  def-emission paths; a genObjectAsExpr `withParamTypeEvidence` wrap was a confirmed no-op for these). Fix the exact
  childGen/grandchild path that emits transitively-imported namespace-member defs to apply param evidence.
- [x] **actors-cluster js DIVERGE ×3** (actors-cluster-coordinator, actors-cluster-raft, actors-leader-protocol) —
  `hist=1` vs `hist=0`: JS gated the leaderHistory write behind `prev !== _localNodeId`, so single-node mode (empty
  `_localNodeId`) dropped the initial entry. Fixed by recording every accepted claim unconditionally in all three
  single-node self-claim paths (useExternalCoordinator / _raftAdoptLeader / _startElection empty-id + quorum),
  mirroring ActorScheduler; `_fireLeaderEvent` stays guarded. (f89e5f708)

### js codegen/runtime bugs closed this arc (2026-07-14 sweep #2)
All found via the corpus contract; each is a class that also lurks in the other backends.
- [x] **js-cons-infix-pattern** — `case h :: t =>` was a SILENT NO-OP. genPattern had no `Pat.ExtractInfix` case →
  fell to the `("true", Nil)` default → no shape test, no head/tail binder (`h` undefined). A `::` nested in a Tuple
  pattern (`case (ah :: at, bh :: _) if g`) lost both. Added the ExtractInfix `::` case (modeled on Cons extract).
  **The big one** — cons patterns are idiomatic and were wholesale broken. (9f5815200)
- [x] **js-summon-mirror** — `summon[Mirror.Of[T]]` matched `Type.Select(Type.Name("Mirror"),…)` but the qualifier
  is a `Term.Ref` (`Term.Name`), so it never matched → emitted invalid `const m = ?_T;` (SyntaxError). (8dad1a549)
- [x] **js-notimplemented** — `???` was emitted literally (whole-file SyntaxError even when never reached) → throwing
  IIFE. (025772318)
- [x] **js-actor-option-tag** — actor intrinsics returned `{_type:'Some'}` not the canonical `_Some`, so every
  `case Some(v)` on clusterConfigGet/whereis/processInfo/gateway/coordHolder failed "Match failure". (025772318)
- [x] **js-string-takewhile** — `String.takeWhile/dropWhile/span` were missing from _dispatch. (4cce01156)
- [x] **js-f-interp-format-spec** — `f"${x}%.2f"` leaked the spec as literal text; added `_fmtSpec` (Java-format
  in JS, all reachable conversions/flags/width/precision) + look-ahead wiring on both f-interp paths. (7a4a594d7)
- [x] **js-destructure-val-in-block + splitAt** — a block-scoped `val (a,b) = e` fell to `/* stat */` (binders
  dropped); `List.splitAt` was missing from _dispatch. Closes scala-js-demo. (a5ea021b2)
- [x] **stale baseline** — 8 scljet cases were already PASS (js-imported-def-int-division), lines removed. (dfac0581f)

Remaining js baseline = genuine feature-gaps (missing plugins on js), NOT portability bugs: Graph/graph-storage,
totp/shamir, crypto (aesGenKey/verifyEd25519), fetchUrlSignal, quoted-macro (`__ssc_macro__`), yaml ConfigBlockInlineYAML,
JDBC (h2 — inherently jvm), MCP/rozum/nfc/pdf/invoice, dataset/codec typeddata (`backend: jvm` examples). Each is a
plugin port, not a codegen fix — track under v2/js plugin-wiring, lower priority than the divergence class above.
Follow-ups still open: js-glue-component (`${…}` template leak), the `backend: jvm` typeddata-codec family.

### js feature-gap ports (remaining js baseline = 34; ROOT-CAUSED 2026-07-14 sweep #2)
The divergence/codegen class is CLOSED (see above). Every remaining js FAIL is a **missing plugin/
intrinsic on the js runtime** (`ReferenceError: X is not defined` / `Method not found`), not a codegen
bug — each is a genuine port. Clustered by shared root (do the multi-case ones first):
- [ ] **DatasetCodec (5)** — dataset-typed-mapping, distributed-dataset-{codec,typed-helpers,wire-protocol,
  wire-shuffle}. `ReferenceError: DatasetCodec is not defined`. These are `backend: jvm` typeddata examples;
  needs the typeddata derived-codec runtime (`ObjectCodec`/`DatasetCodec`/`VertexCodec`) ported to js — the
  same subsystem as `typed-object-codec` + `graph-codecs`. Biggest single lever (5+3 cases). LARGE.
- [ ] **htmlToPdfBase64 / PDF (3)** — invoice-email, invoice-pdf, pdf-extract-demo. Needs a js PDF impl (or a
  documented js-unsupported skip — PDF gen is arguably jvm/native-only).
- [ ] **JDBC / h2 (3)** — object-store-jdbc, sql-h2-quickstart, typed-sql-crud. `UnsupportedJdbcUrl` — h2 is a
  JVM library; these are inherently jvm-only. Recommend SKIP-listing (not a js target). CHEAP (skip-list).
- [ ] **actor-plugin js gaps** — cluster-capability (`SeedResolver.staticList`), actors-typed-remote-spawn
  (`registerBehavior`). NOTE: the `_routes`-crash prefix is FIXED (actors-only bundle no longer throws on
  startNode, commit above), but each still needs its remaining actor globals ported. MEDIUM.
- [ ] **singles** — crypto-encrypt (`aesGenKey`), crypto-verify (`verifyEd25519`), totp-shamir (`totp`),
  quoted-macro-interpreter (`__ssc_macro__`), yaml-parse (`ConfigBlockInlineYAML`), ui-fetch-json
  (`fetchUrlSignal`), sync-todo (`Sync`), tls-smoke (`tls`), graph-storage×2 (`Graph`), js-glue-component
  (`${…}` template leak — a JS-glue codegen quirk, possibly a real fix), indexeddb-sync-client, mcp-agent /
  mcp-filesystem-server, nfc-ndef, rozum-agent×3, dataset-from-generator. Each = one plugin port.

### v2 lane — DEFINITIVE architecture map (measured 2026-07-14, corrects earlier partial finding)
The contract's `v2` lane runs `bin/ssc run --v2`. Traced end-to-end (do NOT re-derive — this is measured):
- `bin/ssc` = the **STANDARD/NATIVE tier**: launcher runs `scalascript.cli.StandardMain` (a physical
  class-level allowlist jar — `RunV2` is NOT even included) → `RunNativeV2.run` → the **native ssc1
  frontend** (scalameta-free) → CoreIR → v2 VM, with `NativePluginHost.loadAll` (the v2-NATIVE
  `NativePlugin` SPI, 20 plugins). `"frontend":"native"`, NO PluginBridge, NO FrontendBridge.
- `bin/ssc-tools run --v2` = a DIFFERENT tier: `Main` → `RunV2.run` → v1-frontend → `FrontendBridge` →
  v2 VM, with `PluginBridge.loadAll` (v1 `Backend` SPI). The contract does NOT use this lane. (My earlier
  "two-plugin-system / swap the launcher = +36/−23" note measured THIS tier — not comparable; ignore it.)

**So the v2 baseline failures are NATIVE-TIER gaps, in 3 kinds:**
1. **native-front std-import resolution (largest).** `jsonRead`/`contentToolkitSection`/`div`/… are
   `unbound` because they're SELF-HOSTED in `bin/lib/standard/native-front/runtime/std/*.ssc` (e.g.
   std/json.ssc defines `def jsonRead(s) = __jsonCoreWrap(jsonCoreParseTolerant(s))` over `extern
   __jsonCore*` the json NativePlugin provides) — and the native frontend is NOT pulling in
   `import std.json.*`. Fixing native-front std-import resolution closes json/content/graph/html-dsl/…
   at once. This is the `uniml-portable` / native-frontend track.
2. **true v2-VM gaps** — derived-codec effects (`unhandled runtime effect: VertexCodec/ObjectCodec/
   DatasetCodec`, 6), actor-cluster methods (`unbound: clusterConfigSet`, 10 — the ActorsNativePlugin
   doesn't register the cluster surface).
3. **native-frontend PARSE gaps** — `std-ui-aggregator: ] expected but identifier found`, wasm-* "native
   frontend rejected incomplete parse" (13) — the same frontend track.

**Takeaway:** the v2 lane is the NATIVE tier; its 64 gaps = native-frontend completion (std-import + parse)
+ native-plugin registration (actor-cluster, derived codecs). There is NO launcher/packaging shortcut —
this is the `v2-native-conformance` / `uniml-portable` deep track, actively built by the sibling arc.

#### native-front ambient-prelude — LANDED 2026-07-14 (975e06c5b)
- [x] **ambient std-module prelude** (RunNativeV2.ambientPrelude): INT/JS expose plugin globals (jsonRead…)
  without an import; the native tier's are self-hosted std modules. RunNativeV2 now injects a known-clean
  ambient std module as a leading prelude source file when the program references a DISTINCTIVE exported name
  and doesn't already import it (the runner merges all source files into one scope). **Closes json-lookup.**
  Mechanism confirmed by `bin/ssc run --v2 std/json.ssc <user>.ssc` → jsonRead resolves. Grow `ambientModules`
  as more std modules are confirmed clean.
- [x] **json-value CLOSED** (32e3040bc) — the native JsonValue needed an OPTIONAL-JsonBox for `.get`: renders
  `Some(inner)`/`None` like INT YET keeps every JsonValue method (asString on an absent key → "", `.map` →
  Some/None). This RECONCILES json-value's Option usage (`v.get(k).map(...)`, prints Some/None) with
  json-deep-import/ui-typed-json's apply usage (`v.get(k).asString`) — INT's `get` returns exactly such a rich
  optional (v1 `navJson`). Plus JsonBox `_show` = `NativeJsonCodec.interpShow` rendering objects as
  `Map(k -> v)` / arrays `List(...)` (unquoted, matching INT) instead of `<foreign>`. LESSON: `get` is NEITHER
  plain-Option NOR plain-apply — it's an optional-with-methods; a plain-Option `get` measured
  +json-value/−json-deep-import/−ui-typed-json before the optional-box reconciled all.
- [ ] **json-read** (1 case) — 2 remaining jsonParse REPRESENTATION nuances, both finicky/risky (deferred):
  (a) `jsonParse("null")` → INT `None`, v2 `()` (toRaw JsonCoreNull → UnitV; toRaw is broadly used → risky to
  change); (b) `jsonParse("0.0")` → INT `0`, v2 `0.0` (rawNumber keeps `DecimalV("0.0")`; INT normalizes whole
  decimals to int — BigDecimal zero-normalization is finicky, risks other number renders). Needs a targeted,
  gate-verified pass; not worth the regression risk for 1 case at the tail of a large sweep.
- [ ] **content-toolkit NOT prelude-injectable** — std/ui/content.ssc as a root trips the content plugin's
  "structural ABI root identity" check; contentToolkitSection is an extern the v2 content NativePlugin doesn't
  register. Needs native-plugin work, not a prelude.
- NOTE (sibling): `scljet/write.ssc` (tracked, native-front staged copy) currently has an UNTERMINATED fenced
  block → breaks scljet-bytes/full/write-table + content-to-markdown on the v2 lane. Pre-existing on main
  (sibling's scljet-SQL arc), NOT from the prelude — flag to the scljet owner.

### v2 lane — REMAINING CLUSTERS surveyed 2026-07-14 (55 non-PASS; all tractable ones CLOSED)

> **RE-MEASURED 2026-07-28 by `v2-native-error-diagnostic` — two of these clusters are STALE.**
> Checked before claiming any of them, which is the only reason the claim was not wasted:
>
> - **std-ui-\* (5): CLOSED.** `bin/ssc run tests/conformance/std-ui-{aggregator,extended,extended-b}.ssc`
>   all print `true` on the native lane today. The blocker this note names — "needs a new
>   `#io.fileExists` prim" — is also gone: `v2/src/Runtime.scala` has `#io.exists`. Do not
>   re-derive the ImportResolver-fallback plan below; the cases pass.
> - **typeddata codecs (6): still red, and still OUT OF SCOPE by design** — confirmed
>   `unbound global: JsonCodec_derived` on `dataset-typed-mapping` and
>   `distributed-dataset-codec`. `tests/conformance/V2-GAP.md` (2026-07-14) files these under
>   "mechanical — register the plugin", which contradicts the paragraph below; **this paragraph
>   is the correct one** (they are `backend: jvm` and `import scalascript.typeddata`). The
>   registration pattern a future port would copy is
>   `v2/runtime/std/sql-plugin/…/SqlNativePlugin.scala:225`, `native(context, "RowCodec_derived")`.

The clean/tractable clusters are DONE this arc: json (4/5), content-toolkit (6/8), scljet-fence (+4).
The remaining 55 are each a DEEP subsystem effort (surveyed + root-caused, none quick):
- **typeddata codecs — JVM-ONLY (6)**: `unbound global: JsonCodec_derived/ObjectCodec_derived` on
  dataset-typed-mapping / distributed-dataset-* / object-store-jdbc. They `import scalascript.typeddata`
  (a JVM package, `backend: jvm`). NOT portable to the native tier without porting the whole typeddata
  codec runtime to v2-native — out of scope; these are jvm-target examples.
- **std-ui-* (5)** — std-ui-aggregator/extended-a/b/c/d all fail on the SAME import: `[…](../examples/std-ui)`
  resolves to `tests/examples/std-ui/index.ssc` (missing) on the native front, but v1's ImportResolver has a
  LIBRARY FALLBACK (`stdPath/../examples` = repo-root `examples/`, ImportResolver.scala:138) the native ssc1
  runner (`v2/bin/ssc1-run.ssc0` sscResolve/sscLoadMod) lacks. Fix = add the fallback there, but it needs a
  new `#io.fileExists` prim (readFile THROWS on miss) AND the native-front stdRoot is `bin/lib/standard/
  native-front/runtime` (not repo `runtime/`), so the fallback base differs — fiddly + high-risk in the
  component underpinning the WHOLE v2 lane. Frontend track.
- **std-index (+ typeclass agg)** — `arity: 2 expected, 3 given` on `combineAll(list, monoid)`: a context-bound
  `[A: Monoid]` + curried `foldLeft(z)(op)` VM-lowering mismatch on the native VM. Frontend/VM lowering.
- **feature ports** — htmlToPdfBase64 (PDF ×3), mcpServer (×2), nfcCapabilities (nfc), awaitClient (sync-todo),
  SeedResolver.staticList (cluster-capability), IndexedDb (indexeddb-sync-client), quoted-macro (×2), Widget
  (js-glue), validate (rest-validate), div (html-dsl). Each = a real per-plugin/feature port.
- **wasm-* (5)** — "native frontend rejected incomplete parse" — native-front PARSE gaps (uniml/frontend track).
- **actor-cluster (10)** — clusterConfigSet/electLeader/… need the ActorScheduler surface in the v2-native
  actors plugin (deep).
- **content-tables/introspection (2)** — kernel `null`→`None` rendering + inlineText Code/Link (see above).
- **scljet-crud/scljet-full** — sibling B-tree mutable-pager divergence on v2 (`0 pages`, rollback); their area.

### v2 (bridge lane `--v2`) — wire plugins (ROOT-CAUSE FOUND 2026-07-14: two plugin systems)
**Mechanism (investigated):** `ssc run --v2` calls `PluginBridge.loadAll()` which ServiceLoads every v1
`Backend`-SPI plugin on the classpath and AUTO-bridges each `NativeImpl` intrinsic to the v2 VM as both a
prim and a `registerGlobal` (`v2/plugin-bridge/.../PluginBridge.scala:315-353`). So "unbound global" means
EITHER (a) the providing plugin's jar/`Backend` service isn't on the `--v2` classpath, OR (b) the intrinsic
isn't a `NativeImpl` (InlineCode/RuntimeCall are compile-time only, skipped at line 347), OR (c) it's provided
by the **v2-NATIVE** plugin set (`NativePluginHost` / `NativePlugin` SPI — the bundled
`scalascript-v2-native-{json,content}-plugin` jars) which is MUTUALLY EXCLUSIVE with PluginBridge. `jsonRead`
(json-plugin `stableNative`→NativeImpl) and `contentToolkitSection` (content-plugin `evalLegacy`→NativeImpl)
BOTH return NativeImpl and BOTH declare a Backend service — so the gap is classpath/loader selection, not the
intrinsic kind. **First step for this track: add a debug to `--v2` PluginBridge.loadAll to log which Backends
ServiceLoader actually finds, and check whether the json/content/actor plugin jars are on the `bin/ssc --v2`
classpath.** If they're absent, the fix is bundling/classpath (bounded); if present-but-not-registering, the
intrinsics need marshaling review. Order (V2-GAP.md leverage):
- **content-toolkit → v2** — FOUNDATION LANDED 2026-07-14 (0886bc43a): ported the core toolkit engine to
  ContentNativePlugin (`contentToolkitSection` → toolkitSectionNode → toolkitBlockNode → toolkitControl,
  building TkNode DataVs from `@ui=toolkit` YAML). Primitive controls done (vstack/hstack/heading/text/badge/
  divider/fragment/slot). [x] **content-slot, content-live-rows CLOSED** (gate-verified).
  [x] **signal ENV + table control** (94193448a) — threaded a signal env through toolkitControl; added
  textField/checkbox/signalText/button(action+signal)/show/rawText/card + `table`(ContentRowBinding→DataTableNode).
  **content-action-onsuccess / content-data-source / content-toolkit-yaml-controls CLOSED**; content-linked-
  namespaces was stale-PASS (removed). **5 content-toolkit cases closed total.** Signals/actions/slots resolve
  from a UNIFIED option registry (collects name→value across ALL ContentToolkitOptions Map fields).
  - **KEY FINDING — v2 native-frontend NAMED-ARG bug**: `contentToolkitOptionsWithActions(actions, computed=…)`
    field-SCRAMBLES ContentToolkitOptions (the named `computed=`/`rowBindings=` value lands in the wrong
    positional slot — measured: `computed` at field 6 not 10). Worked around by the unified registry, but the
    REAL fix is native-frontend named-arg-with-defaults binding — likely affects OTHER named-arg calls corpus-wide
    (uniml/native-frontend track). Also v2 `Map(pair)`→`{pair→pair}` (Map(pair) doesn't destructure).
  [x] **content-form-submit CLOSED** (79f87a7ce) — added `NativePluginContext.resolveGlobal(name)` SPI hook
  (default None; NativePluginHost → V2PluginRegistry.lookupGlobal); the content plugin now builds real
  NativeUiSignals for YAML `signals:` by resolving+invoking the ui plugin's `signal(name,default)` cross-plugin.
  **6 content-toolkit cases pass on v2.** The resolveGlobal hook is broadly reusable for cross-plugin construction.
  Remaining 2 (content-tables / content-introspection) — REAL-rendering, MULTIPLE divergences (each measured):
  - [ ] **contentToolkitBlock** — register the block-level variant (`findBlock`→TableNode for a markdown Table:
    headers→TableColumn(inlineText,`col$i`), rows→TextNode_(inlineText(cell)); + component= rendering). Registerable.
  - [ ] **v2 inlineText Code/Link gap** — the content plugin's `inlineText` drops link href (renders `buy` not
    `buy (/buy)`) and inline-code backticks (`text` not `` `text` ``); v1 renders `label (href)` / `` `text` ``.
    Fix is v1-matching but there's no PASSING case that exercises Code/Link plaintext to guard it in isolation.
  - [ ] **v2 `null` literal renders `None` (KERNEL)** — `TableNode(…, null)` prints `null` on INT but `None` on v2
    (measured: `val x: Any = null; println(x)` → `None`). No v2 Value renders `null`; content-tables' line 23 can't
    match without a kernel null-literal-rendering change (broad risk). This gates content-tables independent of the
    toolkit port. So content-tables/introspection = native-frontend/kernel work, not a content-plugin add.
  - NOTE: TkNode DataV field order must match std/ui/nodes.ssc; a signal must be a real NativeUiSignal (ui plugin's
    `signal()`), NOT a bare `ForeignV(Array)`. Port ref: v1 ContentIntrinsics.scala toolkitControl (line 870).
- [ ] **actor-cluster methods → v2 scope (10)** — actors-cluster-* + actors-leader-protocol. electLeader /
  useRaftLeaderElection / clusterConfigSet / useExternalCoordinator ("Actors scope failed: unbound global").
  Needs the ActorScheduler logic reachable from the v2 VM (bridge or reimpl) — the hardest cluster.
- [ ] **json → v2 (3)** — json-read/value/lookup (`unbound global: jsonRead`). stableNative→NativeImpl + has
  Backend service → likely the v2-native-vs-Backend loader selection; probe first (may be cheapest).
- [ ] **derived codecs in v2 (6)** — dataset/typed-object/graph codecs (same typeddata subsystem as the js gap).
- [ ] **std-ui aggregator (5)** — std-index, std-ui-{aggregator,extended-a/b/c/d}: UI toolkit on v2.
- [ ] **wasm-* (5)** — wasm-{collections,http,matrix,scalascript,sorting}: "native frontend rejected incomplete
  parse" — a FRONTEND PARSE gap, not a plugin (the UniML/frontend track, see `uniml-portable`).
- [ ] **dsl (2)** — dsl-ast-builder (partial output), dsl-calc-parser (empty value; v2 parser-combinator bug).
- [ ] **plugin singles** — html-dsl (`div`), rest-validate (`validate`), htmlToPdfBase64 (PDF ×3), mcpServer
  (×2), NFC, oauth, Widget, fetchUrlSignal, Sync, ConfigBlockInlineYAML, indexeddb, rozum ×3 — mostly the SAME
  feature-gaps as the js lane; a shared typeddata/plugin port closes both lanes at once.
- [ ] **v2 frontend parse gaps (13)** — "native frontend rejected" / "checker exit"; the UniML/frontend track.

---

## v2-native-conformance — remaining self-hosted native-lane gaps (2026-07-14, Sergiy: "запиши в спринт все что осталось")

Metric: `bin/ssc run --bytecode` over `tests/conformance/*.ssc` (the self-hosted ssc1 frontend →
CoreIR → v2 VM/bytecode). This multi-session sweep took the lane 102 → ~148 PASS. Landed this arc:
OpAnfNative (effect arg-position), ambient `Random`, actor globals (register/whereis/selfNode/
clusterHealth), `case m: Map` MapV type-test, `nanoTime`, compound-assign `+=/-=/*=`, imported-enum
registration, object-method default params + varargs, enum-case ctor defaults (→ **mcp-types**),
Dataset user-exception propagation (→ **dataset-error**). ~44 failures remain, categorized below.
Harness: scratch per-case `sweep.sh` (compares `expected/<name>.txt`), `xargs -P 6`; `rm -rf
tests/conformance/.ssc-artifacts` before re-testing a compiler change (the `.scjvm` cache keys on
SOURCE, not the compiler).

### Object model — plain classes & mutable fields (Sergiy asked 2026-07-14; EMPIRICALLY VERIFIED)
The native frontend's object model is currently immutable-only: `case class`(ctor params) + methods.
Confirmed by direct test — see also the documented constraint under uniml-portable below.
- [x] **v2-plain-class DONE (`f01224d3a`)** — plain (non-`case`) `class X(params): <body>` now parses:
  `parseOneStmt` routes a top-level `class` through `parseCaseClass`, so plain classes reuse the
  case-class DataV representation, positional field accessors, method dispatch, and `new X(a)`==`X(a)`.
  Verified: ctor+methods, `new`/no-`new`, field access, pattern match `case X(a,b)`, class-holding-class,
  braced `{}` + layout `:` body, `extends Parent`. At full parity with case classes.
- [x] **v2-class-method-self DONE (`bbaa2edfc`+`0b0a8a66f`)** — a class method calling a sibling NULLARY
  method by bare name (`def describe = area.toString`) resolved `unbound global: area` — `caseSiblingGlobal`
  was only consulted in the app case (`helper(5)`); added it to the lowerE var branch. And `this` resolves
  to `__self` inside a method body (`this.field`/`this.method`). BOTH also fixed the pre-existing case-class
  gap. Verified.
- [x] **v2-class-var-fields DONE — opt-in via `--mutable` flag** (Sergiy 2026-07-14: "мутабельность
  опциональной флаг ... по умолчанию выключен ... в ошибке написано какой флаг"). Commits: flag+error
  (`9e89c3ef7`), cell-backed mutation internal+external-read (`8dc4b3e75`), external-write + multi-stmt
  (`4b9a17f8f`). Design: a `var` field is stored as a CELL in the object DataV. **Flag OFF (default)**: a
  `var` field → specific error "mutable class fields … disabled by default; pass the --mutable flag"
  (`mutableFlagCell`/`mutableViolationCell` in ssc1-front; checker TYPEERR + RunNativeV2 mutableFieldSentinel;
  `_err_mutable_fields`). **Flag ON** (`ssc run --mutable`): construction wraps var-position ctor args in
  `cell.new` (cellWrapCtorArgs @ resolveCtorArgs, idempotent); method-internal read → `cell.get(_sel_f(__self))`
  + write → `cell.set` (lowerCaseMethodBody skips var fields; lowerE var branch + assign + block-assign);
  external read `o.f` → safe `cell.getOr` (new Runtime prim; no-op on non-cells so a same-named plain field
  elsewhere is unaffected); external write `o.f = x` → `sel_assign` parse → `cell.set`. Plumbing:
  StandardMain `--mutable` → RunNativeV2 → both tower invocations → sscNativeArgs / checker strip it →
  `mutableFlagCell`. Verified: Counter.inc, multi-field moveBy, while-loop accumulator, BankAccount
  (mixed val/var + conditional withdraw), collision-safe. Full sweep 163, 0 regr (flag-off unchanged).
  This DELIBERATELY relaxes the immutable-only model as an OPT-IN (Sergiy's earlier "prefer immutable"
  stance stays the default).
- [x] **v2-object-var-read DONE (`70d87809f`)** — external `O.f` read of an object's `var` field now
  resolves to `cell.get(O_f__cell)` (objectVarsCell registry + resolveObjProp/resolveObjMember at the 4
  isKnownObject property/member sites). Object var fields are module-level singleton state, NOT
  --mutable-gated. Verified + --mutable class hardening (instance independence, defaults, mutable
  collections, mixed val/var, case-class var). Full sweep 171, 0 regr. The class object-model gaps
  (plain class, method-self, class var fields, object var read) are now ALL closed.

### scala-class-body-fields — body-declared fields (Sergiy 2026-07-14: "класс написанным на scala … мутабельные или lazy поля … проблем быть не должно")
FOUND (empirically): ctor-param fields (`val x`/`var x`/plain) work, but a field declared in the class
BODY is DROPPED — `parseCaseClass`'s body capture takes only `def`s, skipping `val`/`var`/`lazy val`.
So `class C(a): val y = a*2` → `c.y` = `Stub` / internal `y` = unbound; `var y` (--mutable) = unbound;
`lazy val` = Stub. Fix: capture body fields (name + init + kind); the DataV field list becomes ctor
params ++ body-field names; the generated constructor (lowerCaseCls @ 3340) computes body inits in a
let-chain (each sees ctor params + earlier fields) before IrCtor. Hook: constructor generator already
emits `def C(a) = IrCtor(C, [a])`; extend to `def C(a) = let y=a*2 in IrCtor(C, [a, y])`.
- [x] **scala-body-val DONE (`1dca4677a`)** — body `val y = expr` → nullary method desugar (pure computed
  field ≡ recomputed method; reuses method dispatch). Braced + layout; following top-level `val` not
  mis-captured.
- [x] **scala-body-var + scala-lazy-val DONE (`1d42de036`)** — synthesized constructor (lowerCaseCls):
  body fields captured to classBodyFieldsCell, DataV field list = params ++ body names, ctor binds each
  init in a let-chain then IrCtor. `var` → cell (reuses var machinery); `lazy val` → cell holding
  `__lazyThunk__(() => init)`, forced+memoized via new `__lazyForce__` prim (internal + external access
  routed; NOT bound as a method local so the cache persists). GOTCHA: `lazy` lexes as an ID (not a kw) —
  `kwIs("lazy")` silently fails; use an id-value check. Verified full field spectrum + laziness
  (`before,computing,6,6`). ALL Scala class field forms now work; non-scljet sweep 142, 0 regr.
  NOTE: scljet-* sweep cases (~30) fail on a PRE-EXISTING main bug (`scljet/write.ssc` unterminated fence
  — 1 opening ``` , no close), CONFIRMED independent of this work (fails with these files reverted to
  pre-commit main); scljet is main's active M4 WIP, left untouched.

### Effects / runtime providers
- [x] **v2-distributed-failure-retry** — DONE 2026-07-28
  (`a373460c3`, `ea21eb8a5`): closed the reported `Stub` in the
  faulty-worker retry path without misrepresenting the local-loopback provider
  as a remote failure detector.
  - Run `distributed-failure-retry` unchanged on its declared lane and through
    default/legacy × native VM/direct ASM. Compare exact stdout/stderr/exit
    before classifying the reported post-`Random.uuid` failure. Baseline
    (2026-07-28): JVM 1/1; V2 0/4 with identical exit-0 output
    `failures: 0 / 11 / 12 / Stub / 15 / 16`, so the failed two-element
    partition is a missed-dispatch breadcrumb rather than a reported failure.
  - Reduce coordinator setup, faulty-worker receive/exit, `Cluster`
    construction, and `runDistributed(... retries = 1 ...)` independently to
    identify the first wrong observable and whether the native intrinsic is
    reached.
  - Reconcile the result with `specs/v2.1-native-distributed-loopback.md`
    (remote failure/retry explicitly out of scope) and `specs/mapreduce.md`
    (actor retry semantics). If behavior must expand, widen the claim and
    commit the updated spec before implementation; do not make retries a
    silently ignored argument.
  - Add the smallest fail-first regression at the owning boundary and opt the
    existing corpus case into V2 only after its checked-in output is exact.
  - Run distributed/actor plugin suites, focused retry + partial/map neighbors,
    and default/legacy × VM/direct ASM. Done when the original output agrees
    without a `Stub`, a fallback, or duplicate coordinator state.
  - Result: the first wrong observable was the coordinator's
    `List[(partitionId, partition)].toMap`, unsupported by V2 dynamic dispatch.
    Ordinary and typed-wire lookups now use the portable `foldLeft` + `updated`
    Map surface. The local-loopback provider remains unchanged. Retry is exact
    4/4; full distributed corpus 6/6; actors/distributed plugins 4/4 and 5/5;
    partial/map/heterogeneous/shuffle neighbors exact 16/16.

### Actor features (medium; some timing-flaky)
- [ ] **v2-actors-bounded-mailbox** — UNCLAIMED (claim released 2026-07-28 in
  triage: `codex`, heartbeat 2.7 h stale, no live process). Implement the existing
  `spawnBounded(capacity, Overflow.X, thunk)` contract in the sole native V2
  actors provider.
  - **Triage note — the released session's only uncommitted change was
    `backends: [jvm]` → `[jvm, v2]`, and it was reverted, not lost.** That is the
    last bullet of this very task ("opt the corpus case into V2 **only after**
    exact output"), attempted first. Re-measured on `89ed397f8`: JVM 1/1 PASS,
    V2 `FAIL` at `unbound global: Overflow` — the gap is exactly as this entry
    already describes, so nothing was learned by landing it and the gate would
    have gone red without a declaration.
  - **Blocked on a gate defect for the honest middle option.** Declaring the gap
    with `known-red: v2 — …` does nothing: the V2 lane is the only one of six
    that calls bare `check` instead of `checkLane`, so the declaration is parsed,
    validated and then ignored. See BUGS.md
    §`conformance-known-red-silently-ignored-on-v2`. Until that is fixed, opting
    this case into V2 means an undeclared red; keep it at `backends: [jvm]`.
  - Run `actors-bounded-mailbox` unchanged on its declared JVM lane and through
    default/legacy × native VM/direct ASM; compare exact stdout/stderr/exit and
    repeat enough times to expose virtual-thread scheduling races.
    Baseline on `3341a35a9`: the JVM lane is 1/1; all four V2 combinations
    exit 1 with empty stdout and exact stderr
    `ssc: Actors scope failed: unbound global: Overflow`. Native CoreIR selects
    each `Overflow.X` from `(global Overflow)` before calling
    `(global spawnBounded)`, so this is a provider ownership gap rather than a
    frontend or scheduler result. Repeat scheduling checks resume after the
    provider reaches the fixture.
  - Update `specs/v2.1-native-actors-provider.md` before code: positive
    capacity, `Block`, `DropOldest`, `DropNewest`, and the already-public
    `Fail` strategy; dead-target and quiescence behavior; no compatibility
    fallback or second scheduler.
  - Add fail-first provider tests for all four strategies, validation, blocked
    sender wake-up, and exact retained order. Reuse the existing mailbox and
    run scope; preserve unbounded `spawn`, timeout, exit, supervision, and
    typed-ref behavior.
  - Implement one synchronized enqueue/dequeue boundary so overflow decisions
    are atomic with receives. `Block` must wake when space opens or the target
    dies; `Fail` must surface `mailbox_overflow` through `runActors`.
  - Opt the existing corpus case into V2 only after exact output, then run the
    actors provider suite, actor lifecycle/supervision/timer neighbors, and
    default/legacy × VM/direct ASM. Done when repeated runs are stable and no
    overflow path silently returns a placeholder.
- [ ] **v2-actors-process-info** — `processInfo(pid)` (ProcessInfo record: mailboxSize/links) +
  `spawn_link`. TIMING-SENSITIVE: asserts `mailboxSize=2` before the worker consumes → needs a
  cooperative-scheduler ordering a thread-per-actor model can't guarantee; likely flaky.
- [ ] **v2-actors-receive-timeout** — cluster-connect: advances past register/whereis (landed), needs
  `receiveWithTimeout`.
- [ ] **v2-actors-supervision-flake** — actors-supervision is a KNOWN parallel-contention flake
  (passes serially every time); not a real failure.

### Typeclass
- [ ] **v2-typeclass-explicit-instance** — std-index: `combineAll(xs, intSum)` (explicit typeclass
  instance passed positionally) → `arity: 2 expected, 3 given`. The IMPLICIT path works (mono
  monomorphizes `combineAll(xs)` → `combineAll__mono__intSum`); the explicit path falls to
  injectGivens which prepends a given (ctx-first layout) → arg count AND order wrong. Fix is in the
  mono/injection core — RISKY to the 12 passing tagless/typeclass cases; needs an explicit-instance
  detection + reorder/re-route, verified against the full cluster.

### Content / literate (bespoke rendering)
- [ ] **v2-content-current-section** — content-introspection: `contentCurrentSection() unavailable on
  native 2.1 without source-aware call identity`.
- [ ] **v2-content-linked-namespaces** — root-relative import `tests/conformance/lib/x.ssc` resolves
  in `NativeSourceClosure` (importer-relative doubles the prefix → CWD-relative fallback), but a
  SECOND resolver (content/lowerer) re-doubles the display path; plus content-module features
  (`contentModule`/`contentModuleSection`). Multi-resolver + plugin gap.
- [ ] **v2-content-markdown-render** — content-tables / content-to-markdown: markdown rendering parity
  (bold `**…**`, links `[t](u)`, `@meta` comments) + frontmatter/heading-attribute detection.
- [ ] **v2-named-literate-sections** — sql-transaction (`Transfer.sql`) / sql-browser-basic
  (`Update.sql`) resolve named `## Section` blocks to `Stub`; sql-browser also `.count on 1` dispatch.
- [ ] **v2-graph-edge-display** — custom Show/toString (reordered fields, unquoted strings) not
  produced by the default case-class rendering.

### Missing plugin globals / features
- [ ] **v2-validate-blockform** — rest-validate: `validate { … }` accumulator block-form.
- [ ] **v2-html-dsl** — html-dsl: `attr.cls := …` HTML DSL (attr namespace + element builders
  div/a/img with `:=` attributes and escaping).
- [ ] **v2-exec-subprocess** — std-process-import / v2-native-result-unregistered-field: `exec(cmd,
  args, ProcessOptions)` subprocess runner returning `{stdout, exitCode}`. SECURITY-SENSITIVE (drain
  stdout+stderr on separate threads — see security-hardening).
- [ ] **v2-webauthn** — webauthn-server-verify (`webauthnChallenge`) / tkv2-webauthn
  (`webauthnRegister`).
- [ ] **v2-extern-ffi** — node-basic: `extern def add(...)` FFI (JS/native target concept; out of
  native-VM scope).
- [ ] **v2-scljet-varint** — scljet-write-record: `expected Int, got 2251799813685248` (2^51) in the
  SQLite varint encoder (deep plugin numeric).
- [ ] **v2-scljet-journal-recover** — StackOverflowError (deep recursion in journal recovery).

### tkv2 UI runtime (deep — needs the component/form/draft/signal runtime)
- [ ] **v2-tkv2-parse-err** — tkv2-component / tkv2-forms / tkv2-busi-home: parser `_err` on an
  UNIDENTIFIED construct (verified NOT compound-assign / `[…]` bracket-list / named-args / curried
  calls — those all parse). Bisect the remaining construct.
- [ ] **v2-tkv2-ui-runtime** — the tkv2 cases (component/form/draft/fieldError/formErrors/formValid/
  ctxSignal/childCtx) need the full native UI-component runtime; tkv2-offline (`duplicate native UI
  signal 'draft'`), tkv2-pwa (`unbound global: pwa`), tkv2-tri-state / tkv2-typed-client-derived /
  tkv2-select-reactive.

### Not compiler bugs (fixture / design / out-of-scope)
- [ ] **v2-js-only-tests** — js-cps-intrinsic-rewrite (`nowMillis`) / js-state-effect-runner /
  js-symbolic-infix-operator (custom multi-char operators `<~>`/`~~` — a real lexer gap but the test
  is `backends: [int, js]`) / if-then-no-else-after-while (`backends: [int]` AND the test file is
  genuinely missing its closing ``` fence). All out of the native/v2 backend set by their frontmatter.

---

## QA — conformance skip-debt audit + un-skip (2026-07-11, opus)

Audited all 55 real V2ConformanceTest skipped cases via `bridgeCli run` + the TEST classpath
(FrontendBridge → v2 VM, = the harness's `capture`). Result: **19 STALE-PASS** (now pass, skip
is stale) + 36 genuinely need actor/cluster/coroutine/http/UI runtime.

- [x] UN-SKIP 7 SAFE, DETERMINISTIC stale-pass cases (VERIFIED via real sbt V2ConformanceTest = 115/0):
      content, content-introspection, content-linked-namespaces, content-tables,
      content-to-markdown (frontend runtime now loaded on the Test cp), and
      js-applyunary-effect-cps, js-cps-intrinsic-rewrite, js-crypto-extern-standalone (v2-VM now).
      content-linked-namespaces stays SKIPPED (passes in isolation, FAILS in the sequential
      harness — cross-test state dep; the sbt run caught this — bridgeCli alone would have
      mislanded it). BONUS: std-ui-jobpanel was a PRE-EXISTING RED (missing from skipSet, needs
      the nativeui intrinsic like other std-ui-*) → added to skipSet, greening the test.
- [ ] KEPT SKIPPED (stale-pass but the skip guards a real hazard, NOT a failure): async /
      async-parallel ("may hang"), dataset-parallel-int/sortBy/top/union-intersect +
      distributed-heterogeneous/map/shuffle ("free-monad executor → infinite loop"), storage
      ("filesystem not in batch"), tls-smoke ("network"). Un-skipping these risks CI hangs/
      flakiness — leave until the executor/runtime hazards are addressed.
- [ ] The 36 hard-skips (actors/coroutine/http/mcp/UI-signals) need their runtime — out of scope.
