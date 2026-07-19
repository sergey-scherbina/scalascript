# Changelog

## 2026-07-19 — v2-p65-enums E1: nullary enums in P6.5 F (byte-identical)

`v2-p65-enums`. First slice of the enum cluster. P6.5 F now compiles `enum Name:` with nullary cases
byte-identically to the oracle (ssc1-front+ssc1-lower). Corpus MATCH **156 → 157** (enum-shared-casename);
X1 fixpoint stage1==stage2 byte-identical **121,353 → 131,016 B**; `--self` 136 ok / 0 FAIL; no regression;
no kernel (`v2/src/*`) or `v2/lib` oracle edits.

- **Parse.** enum decl is read as `;`-separated `case` lines — the oracle does NOT make enum a layout
  declHead (ssc1-front goCases `:2810-2834`), and F's ported layout already emits those `;`. Handles
  nullary cases + `case A, B` comma-sugar; parametrized cases reuse `collectFields` (wired for E2).
- **Emit.** nullary case → `(def N (ctor N))` in userDefs, document order (ssc1-lower `:3826`). Regfields
  via a UNIFIED ordered type-case list (case classes + enum cases interleaved, mirroring
  collectCaseFields/collectCaseClassOrder `:4918/4936`), so entry order matches.
- **Resolve.** bare enum case `N` → `(global N)` not `(ctor N)` (resolveE uid `:2049-2054`); `E.Case`
  (nullary) → `(ctor Case)` (`:1703`); `E.values` → the nullary-case ctor cons-list (enumValuesList `:380`).
  cx gains enumCaseNames + enumReg registries; non-enum programs are byte-unchanged (empty registries).

## 2026-07-19 — v2-p65-layout: significant-whitespace / indentation layout ported into P6.5 F

`v2-p65-layout`. The dominant breadth gate (~50% of the corpus uses indented / colon-block style).
Byte-faithful port of the oracle's `NL`-token lexing + stateful `layout` pass into the self-hosting
subset compiler F (`specs/v2.2-p6.5-fsub.ssc`), then the breadth it unblocks. Corpus MATCH
**48 → 156/504 (+108)**; X1 fixpoint held byte-identical at every slice (`--self` 136 ok / 0 FAIL,
97,985 → 121,353 B); no regression; no kernel (`v2/src/*`) or `v2/lib` oracle edits.

- **L1** — F's lexer emits `NL <indent>` tokens; `layout` = the L/B/P/S frame-stack pass + declHead
  vs type-annotation colon disambiguation + all helpers, reproducing `ssc1-front.ssc0:3069-3163`. The
  layout code is written in the subset and self-compiles byte-identically to the oracle. Extension
  frames (E/EB/X + `extension_end`) deferred as faithful no-ops (F has no `extension`). 48 → 49.
- **L2a** applied zero-arg call `recv.m()` → `__method0__` (49→52); **L2b** Upper-case top-level `val`
  reference → `cell.get` (52→53); **L2c** multi-statement blocks `{ s1 ; s2 ; result }` → let-chain,
  the layout-adjacent key for indented match-arm bodies (53→55); **L2d** strip Long-literal suffix
  `5L` (55→56); **L2e** trailing block-argument application `f { block }` / `f(args){ block }` →
  `(app … (lam 0 block))`, unblocking the whole actors cluster + async-parallel* + http-client (56→76).
- **L2f** top-level `import` skipped (module directive, no IR; 50 files); **L2g** applied-uid ctor
  dispatch — `X(args)` → `(ctor X)` only for builtins {Cons,Some,Left,Right,Signal,ComputedSignal},
  every other uid (local case class OR imported like SqlInteger/AchConfig) → `(app (global X) args)`.
  Together these unblocked the entire scljet-sql cluster + scljet-write/mutate/wal (76→**143**).
- **L2h** markdown link-import `[names](path)` skipped (module directive, no IR) — 143→**146**.
- **L3a** string-literal escapes — `scanStr` skips `\X` (one line; F keeps the raw substring and the
  CoreIR text encoder re-escapes identically, so no unescape/re-escape needed) — 146→**154**.
- **L3b** block-arg lambda not double-wrapped — `f { x => body }` uses the lambda directly (not
  `(lam 0 (lam 1 …))`) — 154→**156**.

Remaining (not layout; each a substantial dedicated slice, impact-ordered): bare case-object/enum-case
ctor-vs-global dispatch + enums (~36 — needs enum parse + `enumCasesCell`/subtype-ctor registry so a
bare uid resolves to `(ctor)` vs `(global)`; oracle map `ssc1-lower.ssc0`:140/349-365/4895-4944/5540);
`_sel_` list-var registry (~21, mutable list-type tracking, `ssc1-lower`:246/314/1545-1615);
`__derived_*` codecs/derives (~13); effect-handler-dispatch blocks `f { case … }` (actors `receive` →
`__handler_dispatch_*`); actors `runActors` entry seq-vs-app ordering.

## 2026-07-18 — v2-finish F1/F2/F3: trustworthy acceptance gates + first P6.5 breadth slice

`v2-p65-canonical` (Decision A). No kernel edits (`v2/src/*` untouched).

- **F1 — `specs/newfront-diff.sh` no longer lies green.** Runs the sbt projection from the repo ROOT
  (the moved `ScalaSpikeSpec` is wired only there) and FAILS LOUDLY (exit 2) if any stage projects
  nothing. Proven: `NEWFRONT_SBT_CWD=$PWD/uniml` → exit 2; default → MATCH 491/504, exit 0. Also fixed
  an unquoted worker heredoc that executed comment backticks.
- **F1b — `specs/v2.2-p6.5-corpus.sh` (new): the F3 metric.** P6.5's F vs ssc1-front+ssc1-lower over
  the real 504 corpus, byte-identical, per-file timeout + TIMEOUT bucket, fails loud on empty stages.
  Baseline: **MATCH 1/504** (curried-extern-import); DIFF 115; TIMEOUT 388.
- **F2 measured:** F already owns its per-construct lowerer — only the 7,258 B CONSTANT prelude (9.1%)
  is hardcoded (ssc1-lower emits the same constant as hand-built IR). "Generate the prelude from subset
  source" recorded as a design question for Sergiy.
- **F2/F3 first slice:** tuple-field `._1`.._4 → `_sel__N`; 93 ok/0 FAIL; **X1 fixpoint re-frozen
  79,667 → 80,167 B**, byte-identical.
- **Found:** `p65-fsub-toplevel-val-infinite-loop` (BUGS.md) — F loops on a top-level `val`; the #1
  breadth blocker (388/504 corpus timeouts).
- **Loop FIXED** (`07522696f`): EOF guards on parseParamSkipD/typeText/parsePatVars → corpus TIMEOUT
  388 → 0; every program terminates. --self 93 ok, fixpoint 80,383 B.
- **TOP-LEVEL STATEMENTS** (`253f68231`): top-level `val`→global cell (def/cell.set/cell.get +
  collectTopVals pre-pass for forward refs) and top-level exprs→entry seq in doc order, reproducing
  ssc1-lower. **Corpus MATCH 1 → 34/504 (6%)**; --self 101 ok, fixpoint 86,497 B.
- **Float literals** (`2d63fc63e`): `(lit (float <verbatim>))`; --self 107 ok, fixpoint 87,612 B.
  MATCH 34 unchanged (correct prerequisite for the spark/float cluster).

## 2026-07-18 — v2-finish R1/R2/R4: audit — reconcile the stale ROADMAP with measured reality

`v2-roadmap-reconcile` (R1/R2/R4). Audit only — no kernel/behavior change. Full evidence + exact
commands: `specs/v2-state-2026-07-18.md`; `v2/ROADMAP.md` corrected in place with `⚠️` markers.

- **Fixpoints re-verified GREEN** from a clean build: P6.5 X1 `stage1==stage2` byte-identical at
  **79,667 B**; P6.6 `C_min` **32,824 B**.
- **R1 deltas (claim ≠ reality):** "~4 source files under src/" → actually **9 files / 6355 lines**
  (`Runtime.scala` 4754). **`v2/conformance/check.sh` exits 1 on HEAD** (639 ok / 3 fail:
  `ssc0c uselib.ssc0` multi-file IR-differs, + 2× `StackOverflowError` in
  `Compiler.compileEffectAwareApplication` = the known-open K62.3 / `coreir-compiler-unbounded-depth`
  at check.sh's default-stack helper) — the ROADMAP claimed it green. K3 "JS backend TCO-correct +
  covered by conformance" **overstated**. `coreir.decode` is done (roadmap said "still open"). The
  entire post-07-09 P6.0→P6.18 self-host arc + case classes + int-width + codec H4/H5 + Swift port are
  **absent** from the roadmap.
- **R2 backend matrix (measured):** native + JVM-bytecode = **full parity** (byte-identical). **v2-JS
  (`run-js --v2`) partial** — crashes on `List.foldLeft` (`no dispatch`), `Map` access
  (`not callable: <map>`), effects (`unimplemented effect.perform.oneshot`); big-ints OK. **Tower
  Rust/WASM** (ssc0 surface) run Int/ADT/match/HOF/TCO correctly but **silently drop BigInt**
  (`bigfact`→empty) and can't build async (K62.3 overflow). Swift = emit-only SwiftPM package.
- **R4 kernel/tower:** self-host fixpoint needs **23** δ prims, the whole tower **66**; the kernel's
  δ table is ~1328 lines. Minimal-kernel target ≈ **2,400–2,800 lines (~40–45 %** of 6,355) once
  `Emit` (JVM-backend surface), `PortableEffects` (effect driver — breaks "no continuations"),
  `PortableDecimal`, `NativeUiSites` (unused by the kernel's own pipeline), the perf layers, and the
  FrontendBridge/method-dispatch prims move off the kernel. Analysis only — nothing moved.

## 2026-07-18 — scljet cross-process host lock: the official SQLite driver genuinely waits (test fix)

`scljet-xprocess-lock` — closed the last `SclJetJvmVfsHostTest` failure ("exclusive host lock blocks
official SQLite in another process"), the final suite holding `Test via sbt` red. It was a **test bug,
not a lock-interop bug** — the byte-range lock protocol in `SclJetJvmVfsHost.scala` is correct and was
not touched.

- **Root cause.** The subprocess probe set `busy_timeout=0`, which makes SQLite return `SQLITE_BUSY`
  *immediately* on a lock conflict instead of waiting; the test then asserted `process.isAlive` after a
  500 ms sleep, expecting the query to be blocked. With `busy_timeout=0` the query returns in ~2 ms and
  the subprocess exits, so `process.isAlive()==false`.
- **Evidence (instrumented `LockDiag`, macOS).** Holding scljet's Exclusive host lock: at
  `busy_timeout=0` the official xerial `sqlite-jdbc` printed `busy after 2ms: [SQLITE_BUSY] database is
  locked` (it *detects* the lock, returns instantly); at `busy_timeout=5000` it **blocked** for the
  whole window the lock was held, then printed `ok after 1266ms` **only after** scljet released. So the
  official driver does genuinely wait on scljet's fcntl POSIX write-lock cross-process — JVM
  `FileChannel.tryLock` and SQLite's Unix VFS `fcntl(F_SETLK)` interoperate exactly as designed.
- **Fix (test only).** The probe now uses `busy_timeout=30000` (so SQLite enters its busy-retry loop
  and waits) and prints a `querying` signal immediately before the blocking read. The test synchronizes
  on that signal instead of a sleep, asserts `!process.waitFor(2, SECONDS)` to prove the query stays
  blocked while the lock is held, then releases and asserts the query completes with `ok`. Deterministic:
  the 30 s busy timeout ≫ the 2 s window, and the query cannot return until scljet releases.
- `scljetVfsPlugin/test` 6/0 (×3 for the de-flake), `scljetJdbcPlugin/test` 57/0.

## 2026-07-18 — SwiftUI native renderer gains select/option (menu Picker) + flex-wrap (flow layout)

`swift-renderer-port` — closed the last `SwiftBackendTest` failure ("SwiftUI renderer inventory covers
every shipped lowerer tag and CSS property"). The web lowerer had grown `element("select")`/
`element("option")` and CSS `flex-wrap` with no Swift equivalent; ported all three for real rather than
declaring a known-gap.

- `<select>` → a menu-style `Picker` (`NativeUiSelectControl`) two-way bound to its value `Signal`,
  mirroring the reactive plumbing of the existing text/checkbox controls; `<option>` children decode
  into `(value, label, disabled, hidden, selected)`; a picked value runs the `change` event
  (`inputChange(signal)`). A bare `<option>` outside a `<select>` is a strict sourced `Unsupported`.
- `flex-wrap:wrap` on a `flex-direction:row` `div` → a real wrapping `NativeUiFlowLayout` (custom
  SwiftUI `Layout`, macOS 13/iOS 16) instead of the non-wrapping `HStack` — the nearest faithful
  equivalent (SwiftUI has no direct flex-wrap).
- Fixed a co-latent bug the port surfaced: `width:100%`/`height:100%` — emitted by the shipped
  textField/table styles too, not just select — hit `invalidDeclaration` and rendered a red
  `Unsupported`; now mapped to `frame(maxWidth/maxHeight: .infinity)`. `width:90vw` and other non-px
  lengths stay rejected (pinned by the Swift diagnostic test).
- Added a runtime probe test compiling the generated renderer under `xcrun swiftc -swift-version 6
  -strict-concurrency=complete -warnings-as-errors` and asserting `decodeSelectOptions` + Picker `.body`
  construction — the inventory entries are proven real, not stub-satisfied. `v2SwiftBackend/test` 59/0.

## 2026-07-17 — integer literals span the full 64-bit `Int` range, and overflow fails closed

`int-literal-failopen` — two silent fail-open bugs at the ends of the `Int` range
(`specs/numeric-widths.md` §2: ssc `Int` is 64-bit), each measured on the assembled launcher.

- **v1 reference interpreter** printed `null` (exit 0) for any bare integer LITERAL in
  `(Int.Max, Long.Max]`. Root cause: `Parser.preprocessNumericLiterals` left that band a bare
  decimal that scalameta's 32-bit `Lit.Int` can't hold, so the block failed to parse and was
  swallowed. Fix: emit an `L` suffix for magnitudes `> Int.Max` → `Lit.Long` (64-bit `IntV`).
- **v2 native** printed `0` (exit 0) for `-9223372036854775808` (min64) — and for any bare literal
  past Int64. Root cause: the self-hosted tower's `parseI` defaulted an overflowing `#str->i` to
  `0`, and unary `-` is an eval-time `0 - x`. Fix: `lowerIntLit` fails closed via `_err_int_range`;
  the `pre -` case folds the sign so min64 parses to `Long.MinValue`.
- Both now fail **CLOSED** (a loud error) on a literal genuinely past Int64 (e.g.
  `99999999999999999999999999`) instead of `null`/`0`/wraparound. Arbitrary precision is the
  explicit `BigInt(...)` / `n` suffix; the bare-oversized auto-promote-to-BigInt convenience is
  removed (it silently retyped a literal by magnitude).
- Gated to overflow/min64 literals: the **P6.5 self-compile fixpoint is byte-identical** before and
  after (stage1==stage2, 79,667 B). Regression: `tests/conformance/int-literal.ssc` (5 lanes),
  `tests/e2e/int-literal-failopen-smoke.sh` (both launchers + fail-closed), `NumericLiteralSugarTest`.
  `BUGS.md`: `v1-interp-int-literal-above-2^31-becomes-null`, `v2-native-min64-literal-prints-0` → FIXED.

## 2026-07-17 — the canonical Core IR codec is symmetric and fails CLOSED (H4 + H5)

`coreir-canonical-codec-hardening` **H4 + H5** — the last two open items. The canonical codec is the
reader for **untrusted persisted capsules** (saved continuations crossing hosts), so a codec that
fails *open* is a security defect, not a nicety.

- **H4 — `coreir.decode` now exists.** New prim `coreir.decode : Str|Bytes -> IrProg` (the inverse of
  `coreir.encode`), backed by `IrToData` (`v2/src/Runtime.scala`), the Data-level mirror of
  `IrDecode`, over the kernel `Reader`. `encode ∘ decode = canonicalize` and `decode ∘ encode = id`
  are now expressible from `.ssc` — pinned by `specs/coreir-codec-vectors.sh`: all **13 nodes + 7
  constants** round-trip through decode (including `-0.0`/`nan`/`inf` and bytes), and a lenient/
  pretty/commented program re-emits as the exact canonical bytes. The wire form is still read in
  exactly one place (the `Reader`), so the single-owner rule is untouched.
- **H5 — the reader fails CLOSED on malformed IR** (`v2/src/CoreIR.scala`). It used to accept, silently,
  terms the canonical Writer could never produce; each now rejects with a diagnostic naming the node.
  Strict `NAT`/`INT`/`HEX` token parsers replace lenient `toInt`/`grouped(2)`: `(local -1)`, `(int +1)`,
  `(int 01)`, `(lam +1 …)`, `(arm T -1 …)`, odd-length/signed/non-hex `(bytes …)`. A new
  `Reader.validate` (run on every `parseProgram`) scope-checks every de Bruijn `Local` in range
  (a free local was an out-of-bounds `env` read), requires `letrec` bindings to be `Lam`, and rejects
  unbound globals (a `Global` is *closed* iff it names a top-level `def` or an `@`-cell). The de Bruijn
  scope model matches the evaluator exactly (`10-core-ir.md` §4). The keystone fix: an unbound global in
  a **never-evaluated branch** used to run clean; it is now rejected at decode.
- **Measured, every gate run.** Codec vectors **94/0** (was 47/47 on the pre-fix kernel — 47 new H4/H5
  vectors, RED before, GREEN after); inventory **10/10**; P6.5 literal fixpoint **89 ok / 0 FAIL,
  79,667 B, byte-identical to baseline** (the codec change does not move program bytes); sbt
  descriptor/interop/JS/JVM-bytecode suites green; full `v2/conformance/check.sh` shows no regression
  vs. a pristine `origin/main` (same pre-existing FAILs, none from this work). Docs: `10-core-ir.md` §5
  + `12-ir-format.md` reconciled to the new reality (decode registered; validation table).

## 2026-07-17 — `Int` is 64-bit, normatively, and the suite can no longer route around it

`int-width-conformance` (W1–W5). The same program printed different numbers on different backends,
silently, exit 0 — breaking binding design principle #1 (*backends translate, they do not
reinterpret*).

- **W1** `specs/numeric-widths.md` (new) is THE normative table: width + ABI + host carriers +
  per-backend conformance status. `Int` is 64-bit on every backend; a backend that truncates is
  **non-conforming**. Corrected **`SPEC.md` §4.1, which stated the opposite** ("`Int` | 32-bit
  integer") — so the only backend implementing the canonical spec as written was the *non-conforming*
  one. Law also placed where a backend author hits it (`docs/targets.md`, next to the principle it
  follows from) and cross-referenced from the frozen `v2/specs/10-core-ir.md` §2.
- **W2** `NumericWidthTableAgreementTest` **parses** the table out of the spec and compares it against
  the real consumers (the descriptor producer RUN per row, plus the four host-profile tables) instead
  of restating them — a restated table is an (N+1)th guess. Verified by mutation, not by a green run.
- **W3** the `codegen: v2` reroute is **gone**. It let a case pick the backend that agreed with it.
  Replaced by `known-red:` (declared, reasoned, diffed, and **auto-expiring** — a declared-red lane
  that starts passing fails the suite) and `also-codegen:` (additive; no "instead of" form exists, so
  the reroute is impossible by construction). New `tests/conformance/int-width.ssc`.
  Full suite: **285 passed, 0 failed, 3 declared known-red lanes** — the divergence is now visible in
  the gate output instead of hidden.
- **W4** `run-jvm` / `emit-js` marked `[NON-CONFORMING: 32-bit Int]` in `--help` + docs until deleted.
- **W5** measured: a `scala` fence does **not** mean a different `Int` today (width follows the
  backend, not the fence tag) — but the Scala.js path that would make it so is real, unreachable dead
  code, and two docs promise it. Recorded in `BACKLOG.md` as a latent hole needing a language decision.
- **Two fail-open bugs found by measuring** (`BUGS.md`): the v1 interpreter — the conformance
  *reference* — prints **`null`** for any integer literal ≥ 2^31, exit 0 (its arithmetic is 64-bit,
  which is why the canonical probe `2147483647 + 1` is the one form that works); and v2 native prints
  **`0`** for the `min64` literal.

## 2026-07-17 — coordination status exposes stale heartbeat age

`coord-status` now applies the project's 20-minute heartbeat rule independently from worktree
presence (`52e1d0814`). Strict cross-platform UTC parsing reports timestamp, exact age, reason, and
branch identity for stale, missing, or invalid heartbeats. Hermetic tests separately prove
fresh/live, stale/live, fresh/missing-worktree, invalid, and missing-heartbeat outcomes.

## 2026-07-17 — coordination status trusts an exact declared worktree branch

`coord-status` no longer reports a live claim as stale when its slug has no usable heuristic tokens
(`ci-red-main` filters to an empty set). Explicit claim `branch:` metadata is now compared exactly
against live worktree branches before legacy slug matching (`8ad5f4d1e`). A hermetic e2e fixture
proves both the live and genuinely missing branch outcomes and prints expected/observed branches on
failure.

## 2026-07-17 — CI type-checks examples with the compiler-bearing launcher

After conformance 282/282 and all-examples parity passed, Linux CI still called `check` through the
compiler-free standard launcher and failed on its intentional tier boundary. The workflow now uses
installed `ssc-tools check` for that compiler-bearing step (`a421d9077`). The standard negative
contract remains intact, while the tools command checks the complete examples glob successfully.

## 2026-07-17 — actor leader conformance compares all three backends

Compare-first activation of two previously empty leader gates exposed a real JVM divergence:
single-node `electLeader()` accepted the empty-id claim but kept the history write behind a
leader-value change guard. JVM now records every accepted claim while notifications remain
change-gated, matching INT and JS (`34685277c`); the generated runtime suite passes 35/35.

Only after rebuilt INT/JS/JVM stdout became byte-identical were the two measured fixtures added
(`f403cb952`). `actors-leader-protocol` and `actors-cluster-leader` now execute 2/2 through the
normal conformance wrapper, each passing all three lanes instead of silently skipping 0/0.

## 2026-07-17 — JS actors bridge Long delays at the host timer boundary

Generated JS actors now convert source `Long` delays to host numbers only where they meet
`Date.now()`, covering one-shot sends, intervals, and timed receives (`4a4425f68`). Bare `stop()` in
a CPS actor body lowers through the Actor effect instead of becoming an unbound JavaScript call.
The real Node regression retains all four operations and the full Node suite passes 60/60.

The JVM-codegen + JS-codegen Bully matrix now runs installed `ssc-tools`, discovers Scala runtime
JARs from the test JVM, and reports staged compile/link failures with exit/stdout/stderr
(`74ab54c90`). It executes 1/1 with zero cancellations and converges on one leader;
`actors-supervision` passes INT/JS/JVM.

## 2026-07-17 — staged JVM bytecode/source-map suites compare real artifacts

Five JVM CLI suites now use installed `ssc-tools`, the staged compiler tree, production
`JvmArtifactIO`, and Scala runtime JARs resolved from loaded class resources (`11a9e80e2`). Their
19 tests execute with zero cancellations and pass; command failures retain exit/stdout/stderr, and
reproducibility compares bytes plus actual ZIP order before using hashes as diagnostics. The stale
no-TASTY assertion now follows the Tier 5 contract and requires linked module/runtime TASTY for
downstream Scala 3 compilation. Runtime-separation/facade regressions pass 12/12.

## 2026-07-17 — package registry seed validation is no longer CWD-optional

`RegistrySchemaTest` now finds tracked `registry/packages.yaml` by bounded ancestor search from the
process, JVM, and test-class locations, including aggregate sbt's `v1/lang/core` working directory
(`a99973c16`). Missing checkout data fails with every searched path instead of cancelling. The
focused suite executes all 15 cases with zero cancels and passes.

## 2026-07-17 — bytecode runtime-separation tests execute against current artifacts

All five JVM bytecode runtime-separation checks now run through staged `ssc-tools` instead of
cancelling behind an unset in-process library path (`1c109e49e`). Scala runtime locations come from
the test JVM on macOS and Linux, MessagePack artifacts use production `JvmArtifactIO`, and the size
gate compares module versus shared-runtime payloads from the same build rather than a stale absolute
JAR ceiling. The focused suite executes 5/5 with zero cancels and passes.

## 2026-07-17 — slim distribution failures identify the compared observable

The v2.1 slim-distribution gate no longer exits silently from bare shell assertions. Named helpers
preserve stdout, stderr, and exit status and print expected/actual plus a unified diff; file-state
and negative checks identify themselves before failing (`68ff5dacd`). The full gate passes locally,
and a later Linux run passes the same slim step; the first run containing the new diagnostics is
queued for platform confirmation.

## 2026-07-17 — the Linux sbt tail reaches faithful staged tests

`V2TuplePatternCliTest` now runs the installed `bin/ssc` distribution instead of a fat-jar proxy
that omitted `ssc.lib.path`; all four scenarios execute and pass (`e9567c555`). Its map-reduce
fixture now uses explicit Markdown module links, keeping the unrelated Scala-style import gap open
and visible rather than weakening tuple or worker assertions.

The enclosing sbt CI job first moved to 120/60 minutes (`90c5599dc`), then a second Linux run proved
the step itself still timed out after completing only 12/16 `CrossBackendPropertyTest` cases. The
measured final budget is therefore 150 minutes outer / 90 minutes for tests (`884832696`): both caps
remain bounded, with 30 minutes for the observed tail and 30 minutes of enclosing headroom. Final
confirmation still requires a current Linux run to reach the suite's natural verdict.

## 2026-07-17 — standalone release launcher keeps a safe, overridable stack

The curl/wget release installer now generates `-Xss"${SSC_XSS:-64m}"` instead of hardcoding 64 MB
(`5bde29d37`). Its stale `exec java -jar` fixture now pins the actual java/stack/jar contract, and a
CI-wired e2e executes the installer with fake downloader/java binaries to verify generated source,
default and override stack values, installed jar path, and forwarded argv. Focused fixtures are 2/2.

## 2026-07-17 — all-examples backend matrix follows the installed tools tier

The 17-example INT/JS/JVM parity matrix no longer asks compiler-free standard `bin/ssc` to run
tools-only `emit-js` and `run-jvm` commands. All three comparisons now use one installed
`bin/ssc-tools` and one v1 frontend family (`run --v1`, `emit-js`+node, `run-jvm`), matching the
conformance contract (`ef335ee2c`, `aea328279`). The full matrix exits 0 with byte-identical output;
missing staging names the exact launcher path.

The corrected apparatus also exposed a separate v2-native multi-block auto-output gap instead of
misclassifying it as interpreter drift. That user-facing issue remains tracked and was not papered
over by changing expected output.

## 2026-07-17 — exact-SHA CI truth in the coordination loop

`scripts/ci-status` now asks GitHub for the push-triggered `ci.yml` run of one exact commit and
distinguishes complete green, complete red, pending, and unknown with named job output and stable
exit codes (`c43d8f523`). `scripts/coord-status` shows that verdict without hiding claims/worktrees,
the README exposes the main badge, and the project workflow requires exact-SHA green before final
claim release (`0fe5e5f0d`).

The fixture matrix covers green, failed/cancelled, pending, missing-job, no-run, query failure, and
coordination continuation. Its first version proved why a real oracle remains mandatory: canned
fake-gh output accepted malformed jq that authenticated `gh` rejected. The real query is corrected
and now part of verification; final completion still waits for the workflow containing the guard.

## 2026-07-17 — worktree-safe developer installer

`bash install.sh --dev` no longer initializes or updates `.agents/plugins` inside a linked feature
worktree. It resolves the shared checkout through Git's common directory, reads the already-owned
skills checkout there, and continues the complete CLI staging build. The main-checkout submodule
update path remains intact (`0018dbf0c`).

A CI-wired shell regression creates a real temporary worktree, compares worktree and main-checkout
classification, and proves the linked submodule remains uninitialized. The full documented command
completed successfully from the feature worktree; focused conformance remained green on all three
lanes. That run exposed a separate comments-only launcher-generator drift, now tracked explicitly as
`install-dev-rewrites-tracked-ssc-launcher` rather than being hidden by the successful build.

That second drift is also closed (`b829c8264`). `cli/installBin` is now the single launcher source;
`install.sh` validates its outputs instead of overwriting them from three duplicate heredocs. A CI
gate compares the post-install tracked bytes and prints the actual diff on mismatch. It was first
proven red on the old AppCDS-comment patch, then green after the full installer and focused
INT/JS/JVM conformance runs.

## 2026-07-17 — final JavaScript conformance tail and faithful installed-lane tests

Closed the two JavaScript failures that held the shared corpus at 279/281. `run-js --v2` no longer
turns a successful node process into exit 1: a same-line Scala catch had placed `System.exit(1)`
after the normal/exception join in bytecode (`8333cf97a`). V1 JsGen generator bodies now lower
mutable-name compound assignment as read/base-op/write instead of method-dispatching the literal
name `+=` (`1e6ccb394`). Both focused cases pass on INT/JS/JVM.

The CLI regression now runs the staged `bin/ssc-tools` launcher instead of a fat-jar proxy. That
apparatus correction immediately exposed a third real gap: native-front case classes emitted
`__regfields__`, which JsBackend did not recognize. JS field accesses are already index-resolved,
so the registration is now an explicit documented no-op; direct and installed e2e tests pass 3/3
(`2f23fd9ec`).

## numeric-width-reconciliation — the v3 descriptor now tells hosts the truth about `Int` (2026-07-17)

**A deliberate interop contract change** (option (A), Sergiy's call 2026-07-16; announced in the
rozum `scalascript` room before landing). The `ssc-api-descriptor-v3` surface declared source `Int`
as `AbiPrimitive.I32`, but ssc `Int` is 64-bit — re-measured in the real runtime, not read off
source: `2147483647 + 1 => 2147483648` (no 32-bit wrap), `9223372036854775807 + 1 => -9223372036854775808`
(wraps at 64). Every foreign host (JS/TS, Rust, Swift, WASM-WASI) marshalling per the descriptor
would **silently truncate any value above 2^31−1 at the ABI boundary** — a fail-open on the very
surface that exists to prevent it (`BUGS.md` → `coreir-abi-int-width-declared-i32-actually-i64`).

The root cause was one field carrying two facts: `AbiType.Primitive` held only the wire width, so
the source spelling had nowhere to live and `Int → I32` was accidentally doing identity duty. Fixing
the mapping alone was measured to collapse `def widen(value: Int)` and `def widen(value: Long)` onto
one `stableSymbolId` (`DUPLICATE_SYMBOL_ID`), making such overloads unexportable. The fix splits the
two: `Primitive(value, declaredWidth)` — `value` is the truthful wire width (`Int`/`Long` both `I64`),
`declaredWidth` (`DeclaredInt`/`DeclaredLong`) retains the spelling, carries identity, and never
changes marshalling. Ambiguous integer widths are now **rejected**, not guessed
(`AMBIGUOUS_NUMERIC_WIDTH`; legacy wire nodes fail `SCHEMA_MISMATCH … missing=[declaredWidth]`).
`AbiPrimitive` keeps all nine cases and `I32` is reserved for option (C)'s explicit narrowing ABI, so
(A) is (C)'s first slice. Specs corrected where they now stated a falsehood — notably **an ssc `Int`
crosses to JS as `bigint`, never `number`**.

Verification: every descriptor re-hashes, so 7 producer expectations and 2 normative vectors moved
**because the truth changed** (symbol id `453bfef3…` → `c6231fac…`). Producer 83/83, descriptor 32/32,
`core/test` 1138/1138, interop 36/36, plugin-profile 23/23, affected conformance green. The P6.5
literal fixed point is unmoved: **89 ok / 0 FAIL**, `stage1 == stage2` byte-identical at **79,667 B**.
The new `NumericWidthAbiVectorTest` vectors were proven non-vacuous by reintroducing the bug — all 5
fail loudly (`vector overflow32: … changed the value from 2147483648 to -2147483648`).

## busi-v1-lane-runtime-regressions — supported rollback runtime restored (2026-07-16)

Fixed three import/runtime boundaries exposed by busi's canonical browser lane: enum cases no
longer replace core `None`/`Some`/`Nil`, imported Boolean guards short-circuit, and nested internal
helpers retain their defining module's lexical names instead of resolving a same-named importer
global. The lexical fix is `10e116a63`; the faithful regressions include the actual multi-file
`Unit`-versus-Boolean name collision.

Verification: 1849/1849 interpreter tests, 8/8 affected conformance cases, and busi's remotely
resolvable minimal pin `83941df60` (also carrying the deterministic stack and two older var-frame
fixes) passed `make v2-web-e2e-v1` 9/9 and `make v2-web-e2e-v2` 9/9. Focused adapters were an
insufficient oracle: only the full browser exposed Housing's var-frame requirement and Corporate's
second helper hop. A broad all-global lexical binding was rejected because cyclic structural
closure equality prevents hub boot; only locally declared module functions are bound recursively.

## coreir-canonical-contract-reconcile — the canonical Core IR contract now matches the code, and a gate keeps it that way (2026-07-16)

Pinned **one** canonical inventory (13 nodes / 7 constants) in `v2/specs/10-core-ir.md` §3.2 and
reconciled the drift it had accumulated: the header claimed "11 nodes", §3 claimed "`Seq` is dropped",
invariant 7 claimed "no loop node is needed", and `12-ir-format.md`'s grammar had no `while`/`seq` — all
while `CoreIR.scala` had 13 nodes and both codecs round-tripped `While` and `Seq`. Prose reconciled **to
the code** (the code is what the frozen fixpoint bytes depend on). New §3.1 records why the two
optimization nodes are legitimate (exact semantics-preserving desugarings) and why that is **not** a
precedent for a continuation node. §2's "ten shapes" also undercounted: `DecimalV` is a genuine 11th
value shape, deliberately not capsule-encodable. `specs/coreir-inventory-gate.sh` now compares 6 sources
(10/10) so this cannot rot silently again. Fixpoint unchanged: 89 ok / 0 FAIL, 79,667 B.

## coreir-canonical-codec-hardening — float bit identity, IrBytes parity, bounded decoding (2026-07-16, partial)

Canonical codec fixes before it is used for **untrusted** capsules. `coreir.encode(-0.0)` emitted
`(lit (float 0))` and decoded back as `+0.0` — bit identity silently lost — because encoding went through
`Writer.floatStr`, the *user-visible* renderer where `2.0`→`"2"` is deliberate v1 parity. Fixed by
**splitting** an IR-only `Writer.floatLit`; `floatStr` untouched, so program output is unchanged (a
run-ir-only gate would not have caught that regression — the fixpoint corpus has zero float constants).
`IrEncode` had no `IrBytes` case while `IrDecode` did, so encoding a bytes literal crashed; rendering now
delegates to the kernel-owned `Writer.const`. The reader recursed unboundedly — a 300 KB *well-formed*
depth-50000 capsule was a `StackOverflowError` at `-Xss1m` (the CI/Linux default stack) — now a
diagnostic via `Reader.MaxDepth` (1000; real IR is depth ≤ 25). 43 codec vectors, all printing
want/got/diff. Gates: vectors 43/43, inventory 10/10, fixpoint 89/0 at 79,667 B byte-identical,
conformance 639 ok / 3 FAIL with **all 3 proven pre-existing on a pristine `origin/main` worktree**.
**Still open:** H4 (`coreir.decode` is still unregistered — the spec now says so honestly instead of
promising it) and H5 (symbol/global/arity validation — the largest remaining fail-open).

Completed milestones, newest first. Each entry is a brief summary; git history has full implementation notes.

---

## 2026-07-18 — Native front: `while (paren) do` + multiline curried def, unblocking scljet on `ssc run`

Two parser fixes for the default `bin/ssc run` command, both surgical. A `while` whose condition
STARTS with a parenthesised expression (`while (a && b) && c do`) mis-parsed — the front treated the
leading `(` as wrapping the whole condition and stopped at `)`. This was THE reason the whole
`scljet/jdbc.ssc` façade failed to parse natively (`parseDoubleStr`); `examples/scljet-jdbc.ssc` now
runs on the default command byte-identical to v1. A multiline curried def (`def f(a)⏎  (b) = …`) also
mis-parsed, from inferred-semicolon splitting; fixed by dropping the spurious `;` before a curried
clause's `(`.

Verified against reality first: most of the "doesn't run on ssc run" list in the tracker was already
stale (try/catch braced, `import std.*`, in-fence imports, deep expressions all pass now). The
remaining `try`/`catch` gap was diagnosed as two layers — a parse fix plus a separate native-runtime
bug that fails to deliver a caught exception to the handler (breaks even the braced form) — and
handed off rather than half-landed. scljet 99/99 [int, js]; contract --lanes v2 +3 with no new
regressions.


## 2026-07-18 — SclJet JDBC: inter-process write locking

A writable host-file connection now holds an exclusive advisory lock for its whole lifetime, so a
second writer — in the same JVM or another process — is refused with "database is locked" instead of
silently clobbering the first. `busy_timeout` retries until it frees; read-only and `:memory:`
connections take no lock. The lock lives on a sidecar `<db>.scljet-lock` rather than the database
file, because the durable write replaces the db via an atomic rename (which would drop a lock held
on it). Proven genuinely cross-process by a two-JVM test, so the same-JVM guard cannot mask a broken
OS lock. sbt scljetJdbcPlugin/test 61/61.


## 2026-07-17 — SclJet JDBC: crash-safe durable writes, and the missing example

Host-file writes through the JVM shim were `Files.write(path, bytes)` — truncate then rewrite the
whole image — with no fsync: a crash mid-write corrupted the file, and even a returned-OK write
could be lost on an OS crash. Replaced with the correct primitive for a whole-image model:
stage to a temp file, fsync, atomic rename, fsync the directory. The target is now only ever the
complete old or complete new image, and durable. A test asserts no temp litter and that the
reference `sqlite3` accepts every flushed image.

Inter-process locking was deliberately NOT bolted on: the shim reads the file once at open, so a
write-time lock would be a false fix (each writer rebuilds from a stale snapshot). The
single-writer contract is documented honestly and the real fix filed, rather than shipping a lock
that looks safe.

Also closed two stale gaps: the missing `examples/scljet-jdbc.ssc` (the J2 milestone required it),
and the J1/J2/J3 checklist, which was all-unchecked while the work had shipped on 2026-07-15/16.
Writing the example exposed a native-front parse gap on the façade, now filed.


## 2026-07-17 — SclJet: write by address

The write half of the addressing model: `write (address, type, value)`, plus the commit boundary
(N packets → one image, all-or-nothing).

What the layer adds is not that a cell changes — the engine could already do that — but that a write
to an address that does not resolve FAILS. Measured first: a missing rowid, a missing column and an
`INTEGER PRIMARY KEY` assignment all returned success with no change. For SQL those are right (0
rows matched; the reference agrees); for an address, which names one cell, they are lies. All three
are now explicit errors, and the engine's SQL semantics are untouched. An IPK write is refused with
its reason: that column is the row's identity, and assigning it relocates the row.

Proven where it counts: a value written by address into a file written by the reference `sqlite3` is
read back by `sqlite3` itself, with `PRAGMA integrity_check` ok. Also filed an engine bug the probe
exposed — `UPDATE t SET <ipk> = …` is silently ignored while real SQLite moves the row.


## 2026-07-17 — SclJet opens real SQLite files on the default `ssc run`

The last blocker: the `jvmVfs*` host-file intrinsics existed only as a v1 interpreter plugin, behind
a different SPI and ServiceLoader than the native tier's, so `bin/ssc run` could not open a file at
all — scljet was in-memory only there.

A new native plugin (`v2/runtime/std/scljet-vfs-plugin`) registers them on the native SPI. No code
was duplicated: the 438-line FileChannel/lock/shm host was already dependency-free, so it moved into
a zero-dep module both plugins share — only the value adapter differs per SPI, which keeps the two
lanes from drifting on locking or durability. Following the existing `httpFastEngine` precedent.

All 9 `examples/scljet-*` now run on the default command and match the v1 reference byte-for-byte,
and `ssc run` reads a database written by the reference `sqlite3` 3.51.0 with output byte-identical
to sqlite3's own. Gated by a real-file round-trip test, because the two examples that cover this
end-to-end write to temp paths and the corpus contract skips them as non-deterministic.


## 2026-07-17 — SclJet runs on the default `ssc run` (two v2-native conversion bugs)

Answering "is SclJet real or a fiction": real — byte-identical against the reference `sqlite3`
3.51.0 in both directions through a file (we read its files; it reads ours, `integrity_check` ok,
and writes into them). But it did not run on the default `bin/ssc run` at all.

Two v2-native bugs, both silent. `charAt(i).toString` renders the character CODE (v2 has no Char
box by design), so `upperStr("INSERT")` became `"737883698284"` and the engine stopped recognising
its own SQL keywords; lowercase input survived, which is why it hid. Fixed engine-side with an
idiom that is correct on both lanes. `Double.toLong` was erased by the lowering on the reasoning
"Long IS Int here" — true for an Int receiver, false for a Double — so every REAL write died on the
first bit operation.

7 of 9 `examples/scljet-*` now run on `bin/ssc run` (was 0); scljet conformance stays 98/98 on
`[int, js]`; `contract.sc --lanes v2` green with one closed gap recorded. The last blocker is
filed: the `jvmVfs*` host-file externs come from a v1-style plugin the native tier cannot see, so
scljet on the default command is in-memory only until that plugin is ported.


## 2026-07-16 — P6.5 X1: the literal self-compilation fixed point for a ScalaScript subset

`specs/v2.2-p6.5-fsub.ssc` (131 defs, 208 lines) is a compiler for a ScalaScript subset S, **written in
S**, emitting Core IR **byte-identical to the reference front** (`ssc1-front` + `ssc1-lower`) — and it
compiles its own source. `specs/v2.2-p6.5-fsub.sh --self` → **65 ok / 0 FAIL**:

- 61-program differential corpus: `F(P) == ssc1-front(P)` byte-identical for every P;
- `F(F_src) == ssc1-front(F_src)` byte-identical (61750 B);
- C1, the self-produced compiler, is byte-identical to the reference **and** its IR runs → 120;
- **`stage1 == stage2`, byte-identical.**

No quine (the source is read from a FILE via the ssc0 driver); escape-free (the `"` char is the `dq`
parameter). Distinct from P6.6's C_min, which reached a fixpoint for its own toy language L emitting its
own bare-prim style: X1's object language is **real ScalaScript** and its oracle is **byte-identity with
the trusted front** — including `ssc1-lower`'s constant 7258-byte prelude and both of its literal-driven
specialisations (`+`→`++` when either side is a str-expr; `.length`→`slen` vs `__method__`).

Breadth since (same oracle, fixpoint re-proved by each slice): **lambdas + HOFs**, and **case classes**
end-to-end — the first construct needing a pre-pass (accessors are global and cross-declaration) and a
context-dependent parser (a user case class CALLS its generated ctor, `P(1,2)` → `(app (global P) ..)`,
while builtins like `Cons` stay `(ctor ..)`). Corpus **85 programs**, 89 ok / 0 FAIL.

Three real bugs the exact oracle caught that a review would have shipped: a `(lam 3)`-vs-`(lam 2)` arity
bug on `Map[String, Int]` params (F's lexer dropped `[`/`]`, so the `,` inside read as a param separator
— wrong arity is silent and shifts every local index, and the corpus had stayed green over it); a
`(entry  (app ..))` double space; and a regex rewrite that silently didn't match, leaving `compile`
passing a raw `dq` where the parser wanted a context.

Boundary: S is the subset F is itself written in. Still out: given/summon, enums, extensions,
comprehensions, `var`/`while`, interpolation, the prelude-selector table, the List-var registry.

## 2026-07-16 — v2: a typo'd zero-arg method now FAILS CLOSED (`__method0__`)

Fixed `v2-zero-arg-unknown-method-fails-open`: on the DEFAULT lane (`bin/ssc run`),
an unknown **zero-argument** method silently computed garbage and exited 0 —
`42.bogusMethod()` printed `<closure>`, `List(1,2).bogusMethod()` printed `Stub`,
and `resume.save()` in statement position ran silently. Any typo'd zero-arg method
in any `.ssc` program was affected; it also defeats examples-as-evidence (a
nonexistent method reads as a plausible value).

Root cause was **not** the recorded hypothesis (curried `__method__`). It was two
deliberate fail-open fallbacks in the VM's `Prims.__method__`: the eta-expansion
added by `691334d4e` for `list.exists(lc.contains)` (returns `x => recv.name(x)`
rather than erroring) and the `Stub` missed-method breadcrumb for DataV receivers.
Both are guarded by "no args", which is why arity *looked* like the discriminator.

The keystone: `42.bogusMethod` and `42.bogusMethod()` lower to **byte-identical
Core IR** — the lowerer discards the `()`, so no runtime-only fix could tell a typo
from a method ref. The front does distinguish (`sel` vs `app(sel, [])`). The lowerer
now carries it: an applied zero-arg call emits **`__method0__`** (dispatch or fail,
never eta-expand); bare selections keep `__method__`, so method refs still work.
Other backends never eta-expanded and simply alias the new prim. Fails closed now on
native, `--bytecode` and `build-jvm`, with the same message as the applied-arity case.

Residual (design, documented in BUGS.md): a **bare** `42.bogusMethod` never applied
is still silent — it is the same shape as a legitimate method ref, so closing it
needs a typed frontend.


## 2026-07-16 — scljet: `INTEGER PRIMARY KEY` is a rowid alias (interop with real SQLite)

Fixed `scljet-ipk-rowid-alias-not-substituted`: scljet read `0` for every
`INTEGER PRIMARY KEY` column of a database written by real SQLite — silent wrong
data, nothing failed. Real SQLite stores NULL for an IPK column and keeps the
value in the rowid; scljet returned the stored NULL, so `SELECT id FROM emp` gave
zeros and `WHERE id = 7` matched nothing. The rowid is now materialised into the
IPK column once per query, on the decoded row set, so projection / WHERE /
ORDER BY / GROUP BY / aggregates / joins all see it through the existing
`fieldValueAt` (`14f4da4ac`). Also fixed `lastInsertRowid`, which reported a
sequential counter instead of the rowid actually assigned — making
`getGeneratedKeys` return a nonexistent key for IPK tables (`2fc0a0fd1`); it now
reuses `assignInsertRowids` rather than re-deriving the rowid independently.
Added `INSERT INTO t SELECT …`, previously not parsed at all (`ab436301f`).

The hypothesis that our WRITES were also wrong was **measured and disproved**:
`assignInsertRowids` already uses an explicit IPK value as the rowid, so real
SQLite reads our files correctly and `PRAGMA integrity_check` passes.

New `ScljetIpkRowidDifferentialTest` crosses scljet and `org.xerial:sqlite-jdbc`
**through a file** in both directions — the oracle the suite lacked, since every
existing test asserts "scljet reads back what scljet wrote", which is
self-consistent by construction and cannot see an interop divergence. Gates:
`scljet-*` conformance 97/97, `scljetJdbcPlugin/test` 49/49. Two follow-ups
queued: `scljet-unique-index-not-supported` (SPRINT — needs enforcement, not just
parsing) and `scljet-update-ipk-does-not-move-rowid` (BUGS.md).

## 2026-07-15 — Scala 3 lexical direct-style control macros (M1)

Added `scalascript.control.direct.{Scope,reset,shift}` to the existing
`io.scalascript:scalascript-control_3` artifact. The inline transform accepts a
bounded lexical ANF sequence and emits only the compiler-independent explicit
`Eff`/`shift`/`reset` protocol, preserving prefix/suffix evaluation, sequential
markers, reusable continuation semantics, residual rows, and shared heap state.
Strict local values, contextual/pattern binds, and mutable closure cells retain
their Scala ownership across capture. Covered owner-bearing graphs are rebuilt and
stale-symbol audited: captured `A` supports `owner.type` and
`Prompt[inner.Key, R]`, prefix/suffix lambdas support `() => owner.type`, and
crossing parameterless givens are allocated in two phases with their compiler
flags preserved. Structural `PolyFunction.apply` selections are resolved from the
transformed qualifier, while self-contained `PolyType`/`ParamRef` graphs—including
references present only in a result or bound—remain closed atomically. Richer
owner-dependent graphs that M1 cannot represent fail closed. A ShiftBody
may use ordinary explicit control or a nested managed direct reset body; that
nested reset's eager prompt is still audited by the enclosing ShiftBody. An exact
nested direct marker, including one in that prompt, is rejected before it can
survive lowering. Richer
local definitions, lazy markers, inline wrapper applications, and every
`scala.util.boundary.break` fail closed before code can be moved or evaluated;
method/module aliases, explicit labels, and transparent-inline provenance cannot
hide a break, and inline diagnostics point at the nearest wrapper invocation.
Unsupported expression shapes and callback/control barriers fail closed at compile
time with stable `UNMANAGED_CAPTURE`, `CAPTURE_BARRIER`, or
`DIRECT_STYLE_UNSUPPORTED` diagnostics; there is no exception/TLS/runtime capture
path and no CoreIR, UniML, seed, backend, or self-hosting change.

The `scala-direct` semantic lane is now ready for vectors 18 and 23 plus catalog
coverage (3/3), each differential against explicit Scala. The complete control
leaf passes 113/113 tests (24 direct semantics, 27 exact diagnostics), the
package/POM and packaged-JAR positive/exact-negative consumers are green, and the
positive consumer prints fourteen differential `42` values. Catalog validation
remains 26 vectors/9 lanes with 9/9 negative cases, and affected effect conformance
passes 5/5. Cross-method capture, prompt forwarding across a nested different-prompt
reset, managed callbacks, and saveable frames remain work for the compiler plugin.

The latest strict-polymorphic-value remediation is frozen in feature commit
`b6d2cd262` on `origin/main` base `6603e6c29`; this checkpoint remains unlanded
pending a fresh independent review.

## 2026-07-16 — the site is live on GitHub Pages

<https://sergey-scherbina.github.io/scalascript/> serves the landing page (HTTP 200,
verified live — not merely a green workflow). Pages had been configured API-side for a
while but had never published anything: no workflow uploaded a Pages artifact, and the two
historical Pages deploys predate the repo going public.

The interesting part was what *not* to do. `registry-pages.yml` already deployed to Pages,
so the planned second `pages.yml` would have raced it for the same deployment — the live
site flipping between the landing page and the registry index depending on which run
finished last. Instead that workflow became the single publisher (renamed to `pages.yml`,
"Pages") and composes both trees into one artifact.

The layout is forced by a shipped contract: `RegistryClient.DefaultRegistryUrl` is a
built-in default pointing at `<pages>/packages.yaml`, so the registry machine files had to
stay at the root or every `ssc search|info|add` already in the wild would break.

| Path | Source |
|---|---|
| `/` | `site/index.html` — landing page |
| `/packages.yaml`, `/packages/**`, `/search-index.json` | `registry/site/` — CLI contract, root-pinned |
| `/registry/` | `registry/site/index.html` — browseable registry index (moved from `/`) |

A "Check composed tree" step asserts both contracts hold in the artifact before deploy;
`concurrency: pages` with `cancel-in-progress: false` avoids cancelling an in-flight
deploy; `workflow_dispatch` allows on-demand runs. Docs that claimed the registry HTML
index lived at the root were corrected, and `site/DEPLOY.md` — which described only a
prospective Cloudflare deploy — now documents the host that actually serves the site.

Note for future readers: `GET /repos/:owner/:repo/pages` still reports `status: null` and
`pages/builds` is empty. Those are the *legacy* build pipeline's fields and stay empty
under `build_type: workflow`; the authoritative signals are the deployment state and the
URL responding.

## 2026-07-15 — scljet JDBC introspection (getPrimaryKeys / getIndexInfo / getTypeInfo)

Completes the `DatabaseMetaData` surface a JVM tool actually walks: primary keys (both
SQLite spellings — on the column and as a table constraint, named or not), index info
(one row per index column, UNIQUE and the key list parsed out of `CREATE INDEX` with the
engine's own lexer), type info, and empty-not-throwing foreign-key queries. Two deviations
from the reference driver are deliberate and asserted: `getIndexInfo(unique=true)` filters
per the JDBC contract where Xerial ignores the flag, and `getTypeInfo` reports this
driver's own type codes so it cannot contradict `getColumns`.

The engine cannot create a unique index at all, so that path is tested where it matters —
reading a file written by the reference driver — which also demonstrates catalog
introspection over real SQLite files, and pinned a live engine bug: an `INTEGER PRIMARY
KEY` column in a real SQLite file reads back as 0, because the rowid alias is not
substituted (`BUGS.md` → `scljet-ipk-rowid-alias-not-substituted`). Our own databases read
back fine, which is exactly why no existing test caught it.
`sbt scljetJdbcPlugin/test`: 42/42 (was 29/29).

## 2026-07-15 — scljet JDBC shim J2 hardening (getGeneratedKeys, catalog, durability contract)

The gaps a real JVM client (pool / ORM / DB tool) hits first, closed on the JVM
`java.sql` shim (`v1/runtime/std/scljet-jdbc-plugin/`) with the engine untouched.
`getGeneratedKeys` returns the one-column `last_insert_rowid()` ResultSet — the rowid
already crossed the bridge, every call site just discarded it — tracked per statement
for INSERT/REPLACE; its semantics were probed from Xerial `sqlite-jdbc` rather than
guessed, and are diffed against it. `DatabaseMetaData.getTables`/`getColumns` (plus
`getTableTypes`, empty `getCatalogs`/`getSchemas`) return the mandated JDBC row shapes
over a new JVM-side static ResultSet; since the engine exports no table listing, the
catalog reads `sqlite_schema` structurally through `openReadonly` and parses columns
with the engine's own lexer (a test pins the names to the engine's `imageTableColumns`,
so they cannot drift), mapping SQLite affinity to `java.sql.Types`.

`specs/scljet-jdbc.md` also stopped describing a durability model that was never built:
host files are whole-image read-modify-rewrite with no journal, no fsync and no locking,
so the spec now carries that property table and the explicit single-writer /
single-process / non-crash-durable contract, with the journaled path kept as intended
design and its real prerequisite (a Connection-level MutablePager) recorded.
`sbt scljetJdbcPlugin/test`: 29/29 (was 14/14).

## 2026-07-15 — scalascript.dev landing page (site/)

Marketing landing page for the project domain `scalascript.dev`: a single static,
self-contained page under `site/` (inline CSS/JS, no build, no external requests),
plus `favicon.svg` and `DEPLOY.md` (Cloudflare Pages + domain + a pre-public
git-history secret-scan gate). Design: a literate ScalaScript document whose Markdown
structural tokens glow in the compiler's red accent. Deploy and the repo-public flip
remain owner actions.

## 2026-07-15 — JavaScript/TypeScript explicit local control API

Added the compiler-independent ESM-only `@scalascript/control` reference leaf at
`v2/host/js/control`, with typed reusable/one-shot continuations, deep handlers,
generative prompts and true `shift`/`reset`, structured local-save rejection, and
stackless explicit state machines. The complete reviewed slice is reachable on
`origin/main` as landing `cf8f96200`. Its private runtime ABI is paired with branded
`.d.ts` declarations and has no production dependency or lifecycle script.

Two independent pre-integration review rounds drove and confirmed the final
hardening: named single `unique symbol` owners separate stable effect descriptors
from runtime identity and reject inferred or explicit owner unions; concrete-answer
prompt-key extraction remains invariant; class-backed capabilities keep state in
private WeakMaps; and every reachable internal constructor requires unexported
authority. The exact five-file npm payload includes the repository Apache 2.0
license, points to the canonical HTTPS contract, and validates every relative
README target. Both second-review bug entries are confirmed `done`.

All 17 applicable shared semantic vectors pass without changing the catalog or
lane registry; the complete package suite is 31/31, TypeScript positive/negative
fixtures pass, 1,000,000-bind and 1,000,000-state stress plus 100,000 handled
operations complete, and fresh affected project conformance is 5/5. Generated
bridges, managed transforms, mixed SCCs, exact/portable runners, and lane wiring
remain open under the parent JS/TS profile.

## 2026-07-15 — Target-neutral plugin capability profiles

Added `v2/interop/plugin-profile` and
`io.scalascript:scalascript-plugin-profile_3`: bounded language-neutral framing,
domain-separated semantic/schema identities, exact target implementation digests,
one aggregate descriptor binding, and pure pre-install inventory validation for
target ABI, capabilities, exact transitive dependencies, deterministic order, and
cycles (`e1933fcc6`). Runtime plugin code asserts externally owned contracts; it
does not define effect, control, CoreIR, codec, or interop semantics.

`ssc.plugin.NativePlugin` gains only the concrete optional
`capabilityDeclaration = None` adapter. A Java provider with the old `id`/`install`
bytecode shape still loads through ServiceLoader and inherits the JVM default;
installation ordering and ownership conflicts are unchanged. Profile tests pass
23/23, native SPI tests 12/12, the only project dependency is the canonical
descriptor leaf, independent review is approved, and affected `plugin-*`
conformance reports an explicit 0 matching cases / 0 failures. `.sscpkg` carriers
and automatic linker/admission population remain queued with descriptor consumers.

## 2026-07-15 — Shared control semantic vectors and lane matrix

Replaced independent control-interoperability checklists with one validated
26-vector catalog and nine explicit lane declarations. The default gate now runs
both portable VM and direct ASM, each passing the same 13/13 eligible probes with
exact exit/stream bytes; the compiler-independent `scalascript-control_3` suite
consumes the same catalog and passes 17 typed laws plus its coverage check.

The matrix distinguishes `READY`, `UNSUPPORTED`, `UNAVAILABLE`, `pending-codec`,
and `pending-spec`; prompt laws 18/22/23 are executable on explicit Scala without
pretending `.ssc` lowering already exists, while future host/AOT, durable, and
cancellation obligations cannot disappear or become silently green. Nine
negative catalog regressions, the full 57-test control leaf, and the five-case
effect conformance slice pass from the isolated worktree.

## 2026-07-15 — Canonical interop descriptor v3 foundation (Slice A)

Added the target-neutral `v2/interop/descriptor` leaf and
`io.scalascript:scalascript-interop-descriptor_3`: closed public ABI types,
callback/prompt policy, control summaries, artifact manifests, restricted-JCS
canonical admission, bounded checked factories, domain-separated identities and
digests, and structured validation. The leaf has no project dependencies.

Legacy `.scim` gains only a final defaulted opaque
`apiDescriptorV3: Option[String]`; captured pre-v3 JSON and MessagePack remain
readable as `None`, existing fields retain their meanings, and artifact ABI stays
`2.0` (`286de7cee`, spec verification `deb8fead8`). Descriptor tests pass 27/27,
artifact ABI tests 73/73, core 1046/1046, interop 36/36, and affected conformance
2/2 on interpreter/JS/JVM. Compiler producers, post-body/linker population, and
runtime/admission consumers remain queued as descriptor slices B/C/D.

## 2026-07-15 — Stack-safe portable effect resumptions

Portable VM and direct ASM now execute deep effect resumes through one shared
iterative two-mode driver (`3de5020c5`, `956b42539`). Handler-facing resumes
eagerly invoke the original continuation (preserving the atomic one-shot claim),
then hand the obtained computation to an unforgeable private deferred request;
declared managed program/call boundaries drain escaped requests without changing
the public three-field `Op` or CoreIR. VM and AOT lowering now thread operations
through every consumed value position, conservatively classify plugin results,
protect FastBool/curried-handle paths, lower effectful loops stack-safely, and
restore the caller environment after generated non-tail `LetRec` bodies.

The residual-forwarding integration adds a VM/direct-ASM `Rehandle` regression
with exact inner-then-outer `Return` result `312` (`6db946a86`). Focused
stack/one-shot/residual tests pass 39/39; installed default VM and `--bytecode`
both produce `100000`, `100000`, `20007`, `20000`; affected conformance is 6/6,
full interop is 12/12, and the 133-file stage2 image remains source-exact. Axis
20 is measurable-now (`b7c280792`); the verified contract and evidence are in
`specs/control-effect-stack-safety.md`.

## 2026-07-15 — Portable residual-effect forwarding

Deep portable handlers now forward an unmatched operation to the next enclosing
handler as the existing three-field `Op`, with the skipped handler reinstalled
around the original continuation. The JVM runtime uses a private structured
`Matched | Unhandled | Suspended` dispatch result; exact-event owner/activation
provenance isolates concurrent, nested, and reentrant decisions, while the
original one-shot or reusable base continuation remains authoritative. Matching
arm failures stay fatal, missing `Return` remains identity, and no public CoreIR,
wire value, or primitive-manifest ABI changed (`d764c2ebe`, `84ad12651`,
`9273ae0f6`).

Both compatibility and self-hosted frontends now qualify simple and general
handler partial functions, including effectful guards and selected-only total
catch-alls. Conservative JVM-source/JS/Rust/Swift marker fallbacks preserve
ordinary partial-function behavior outside typed JVM dispatch. Interop axis 19
is measurable-now at exact `57`; 17/17 residual, 4/4 one-shot, and 6/6 native
runner tests pass, as do installed VM/direct-ASM e2e, four real-Swift checks,
6/6 affected conformance, all 11 interop axes, and the source-exact stage2
single/multi fixed points. The verified design and complete commands are in
`specs/control-residual-forwarding.md`.

## 2026-07-15 — Swift implicit effect `Return` parity

Swift AOT now applies the specified identity fallback when an effect handler omits
`case Return(value)` (`f21abfcc8`). The recoverable result is private to the
directly invoked partial-function match: failures inside a selected arm, fallback,
or nested match remain failures. Shared no-`Return` fixtures now produce the same
one-shot violation and reusable result `3` on Swift and JVM VM/direct ASM. Focused
Swift/JVM tests, `installBin`, native effect e2e, and fresh affected conformance
(6/6) pass; the live one-shot specification was reconciled in `8a9300497`.

## 2026-07-15 — Portable one-shot effect continuations

Implemented the typed `.ssc effect` multiplicity contract across the v2
self-hosted and compatibility lowerers, shared JVM VM/direct-ASM runtime, CLI
boundary, and Swift AOT (`cbdc4791a`). Plain effects now own one linearizable
base-continuation claim and fail a second resume with structured
`AlreadyResumed(OperationId)` / stable `ONESHOT_VIOLATION`; `.ssc try/catch`
cannot intercept it. Raw CoreIR/Mira `effect.perform` and `multi effect` remain
reusable, and the three-field `Op` plus canonical CoreIR encoding are unchanged.
User docs landed in `f92594ff3`; verified specification and bug statuses in
`695a7f69e`.

Verification passed 4/4 direct JVM runtime tests (including a 64-way race), 3/3
compatibility-lowering checks, 3/3 real Swift tests (including its 64-way native
race), exact assembled VM/ASM negative and multi=`3` controls, native e2e, 10/10
measurable interop axes, and 6/6 affected conformance cases. The self-hosted
single/multi fixed points and 131-file source-exact image also pass; the gate's
previous symlink-blind manifest was corrected in `13b29852e`. Residual forwarding
(axis 19), stack-safe deep effect recursion (axis 20), and Swift implicit-`Return`
fallback remain explicit follow-ups rather than being reported as completed.

## 2026-07-14 — Scala control host module canonical naming

Simplified the Scala host leaf to the canonical source home
`v2/host/scala/control` and Maven coordinate
`io.scalascript:scalascript-control_3:0.1.0-SNAPSHOT`. The build-local sbt project
id remains `scala3ControlApi`, and the public package remains
`scalascript.control`, so Scala source and class ABI are unchanged. The normative
profile landed in `3402aa949`, the directory/build rename in `9883214d7`, and live
documentation links in `add2a0b78`. Verification passed 39/39 module tests, the
runnable example (`Vector(10, 20)` and `42`), JAR/POM coordinate inspection,
10/10 focused effect/coroutine/tail conformance, and 9/9 measurable portable-VM
interop axes.

## 2026-07-14 — Scala control API moved to the v2 host layer

Corrected the initial ownership error identified by Sergiy: the
`ScalaExplicitControlApi` is a v2.2 Scala/JVM host-profile capability, not a v1
language/compiler feature. The canonical source home is now
`v2/host/scala/control-api`; `v2/host` is explicitly an outer SDK/transform/runner
layer and remains outside the seed, compiler, CoreIR, UniML, and bootstrap graph.
The move is a 100% rename with no source or ABI change (`9b477a128`); package
`scalascript.control`, artifact `scalascript-control-api_3`, and sbt id
`scala3ControlApi` are unchanged. The profile correction landed in `5b24876ca`
and links/layout in `b692338e7`. Verification reran 39/39 tests, the runnable
example, package/POM dependencies, 10/10 focused conformance, and 9/9 measurable
portable-VM interop axes.

## 2026-07-14 — Scala 3 explicit control API (Tier 1)

Implemented the compiler-independent `io.scalascript:scalascript-control-api_3`
leaf and its `scalascript.control` package (`528d73af3`): typed stackless `Eff`,
invariant singleton-owned effects and operations, deep residual-forwarding handlers,
eager reusable/one-shot continuations, generative rank-2 multi-prompt `shift`/`reset`,
typed local-save rejection, and public state-machine builders. A complete JVM ABI
audit guards every capability constructor and rejects project runtime, reflection,
TLS, and erasure leaks. The runnable ordinary-Scala example landed in `7f908e536`;
the verified Tier-1 capability and explicit post-X1 boundary are documented in
`6cef4ee3c`. Verification: 39/39 ScalaTests (1,000,000 binds, 1,000,000 state
transitions, 100,000 handled operations, and a 64-way one-shot race), package/POM,
10/10 focused effect/coroutine/tail conformance, and 9/9 currently measurable
portable-VM interop axes. Successful durable save/run, Scala↔ScalaScript lowering
and bridges, macros/plugin, mixed tail SCCs, and remote runners remain separate
post-X1 milestones.

## 2026-07-14 — control-interop §14.1 semantic vectors (impl wave-1)

First implementation wave of the control-interoperability model (split with
codex-interop; pre-X1, no kernel/codec bytes). Empirically measured each §14.1
semantic vector on the portable VM and expanded `tests/interop-conformance/`:
measurable-now grew 5→9 (added zero-resume/early-return, deep handler reinstall,
return-clause transform, nondeterminism many-resume product). Recorded four
`pending-runtime` gaps with exact measured evidence (distinct from the `pending-codec`
durable axes): delimited `shift`/`reset` unbound; no residual forwarding from inner
to outer handler; effect-performing recursion overflows the native stack ~500–2000
depth (pure TCO unaffected); one-shot violation silently allowed. `run.sh` now
prints each pending axis's own status/reason. These need effect-runtime support
(codex's `scala3-control-api` reference runner), not the codec.

## 2026-07-14 — target-neutral control interoperability and host/runner profiles

Replaced the former Scala/JVM-shaped semantic owner with
`specs/control-interoperability.md` (`6f5f0a53a`): one target-neutral contract for
`Eff`, multi-prompt `shift`/`reset`, managed callbacks/TCO, reusable `save`/`run`,
independent `CodeMode × FrameGate`, `DurableValue`/`DurableRef`, atomic admission,
security, and N→M conformance. The Scala document is now a JVM host profile, joined
by mandatory JS/TS, Rust, Swift, portable-VM, and WASM/WASI profiles
(`92ddecc17`, `92d853d4a`, integration `41e499f3a`). Satellite ownership and indexes
were reconciled in `1769a4bf5`; SPRINT/BACKLOG now make JVM/JS/TS/Rust/Swift runners
mandatory readiness-ordered milestones instead of optional tail work (`7f2be38b3`).
No CoreIR node, compiler, codec, or runtime byte changed; byte-affecting work remains
gated on full P6.5 X1 despite the landed P6.6 `C_min` proof. Verification: Markdown
lint and changed control-link checks pass; focused effect/coroutine/tail conformance
is 12/12 green; the independent portable-VM harness (`a9c354262`) reports 5/5
measurable axes green and 8 durable/cross-host/negative axes explicitly pending
post-X1 rather than falsely green.

## 2026-07-14 — control-interop portable-VM reference runner profile

`specs/control-interop-profile-portable-vm.md`: the runner-target profile for the
portable ScalaScript VM — the reference runner of the control-interoperability matrix.
Records the VM's realisation of the core `CodeMode(Portable)/FrameGate` model, the
control axes it satisfies today (measured reference row), the pending durable/cross-host
axes (post-X1), the value/call-bridge prerequisite, and how other runners are measured
against it. Links `tests/interop-conformance` as the executable reference row. Companion
to codex-interop's forthcoming `specs/control-interoperability.md` (core; inline refs
upgrade to hard links once it lands) and `scala3-bidirectional-control.md` (Scala host profile).

## 2026-07-14 — control-interop conformance harness (portable-VM reference row)

Executable conformance harness for the target-neutral control-interoperability model
(Rozum `#interoperability` joint resolution) under `tests/interop-conformance/`. Runs a
runner against the normative axis matrix; the portable-VM (`ssc run --bytecode`) is the
reference runner. 5 measurable-now axes green (one-shot resume, reusable multi-shot N→M,
deep intra-lane TCO, callback re-entry, capture+resume same-host); 8 pending-codec axes
(durable save/run, cross-host resume, concurrent multi-shot, no prefix/main replay, and the
negatives: raw-ForeignV reject, missing-resolver reject, codec/artifact mismatch,
signature/quota) enumerated so the matrix is never silently reported fully green. `run.sh`
prints the reference row + PENDING rows and exits non-zero on regression. The portable-VM
already does N→M resume in-process; the one gap the model closes is cross-host resume
(durable capsule). Companion to codex-interop's `specs/control-interoperability.md`; the
portable-VM reference *profile* `.md` follows after that spec lands.

## 2026-07-14 — std-ui-hstack-wrap: optional flex-wrap for std/ui `hstack`

busi nav-bar follow-up: up to 15 owner nav buttons with varying Cyrillic label widths were
hand-grouped into fixed-size `hstack` rows (e.g. "always 4 per row"), which either overflowed or
left rows visibly ragged; `runtime/std/ui/lower.ssc`'s `hstack` case never set `flex-wrap`
(implicit `nowrap`), so there was no way to let children flow/wrap naturally. Added `wrap: Boolean
= false` to `hstack`'s first (non-curried) parameter group, before the curried `(children:
TkNode*)` group — `def hstack(gap: Int = 0, wrap: Boolean = false)(children: TkNode*): TkNode` — and
a matching last field on `HStackNode` (`nodes.ssc`). `lower.ssc` appends `; flex-wrap:wrap` to the
emitted style only when `wrap = true`; the default/`false` case's style string stays byte-for-byte
identical to the pre-slice output (flexbox `gap` already spaces wrapped lines evenly, no new
`align-content` param needed). Mirrors `std-ui-button-variant`/`std-ui-button-size`'s scope
discipline: one boolean, additive, JS-custom-frontend-only, no backend churn
(`specs/std-ui-hstack-wrap.md`). Two pre-existing call sites outside `layout.ssc` that construct
`HStackNode` (content-toolkit's interpreter + JVM-codegen paths) needed to stay in sync with the
new field; `ContentToolkitJs.scala`'s hand-written JS object literal verified to need no change.
`tests/conformance/tkv2-hstack-wrap.ssc` + `HStackWrapTest.scala` (a Scala-level test proving the
rendered CSS `flex-wrap` value genuinely differs, since `View` is opaque to `.ssc`) +
`examples/frontend/hstack-wrap/`. Busi-side nav wiring (un-grouping the fixed-4-per-row calls into
one flat `wrap = true` row) is a separate, not-yet-done follow-up.

---

## 2026-07-14 — Scala 3 ↔ ScalaScript bidirectional-control design freeze

Published the normative architecture in `specs/scala3-bidirectional-control.md`
(`96fc5adfb`) and synchronized the global effects contract plus Scala interop,
coroutine, polyglot, v2.2 self-hosting, and v2 effect companions. The frozen design
defines one typed `Pure | Op` semantics for ordinary Scala 3 and ScalaScript,
generative multi-prompt `shift`/`reset`, descriptor-driven callbacks and mixed tail
SCCs, and reusable `save`/`run` without prefix replay. Portable saves are closed
CoreIR resume programs plus isolated frozen frames; mixed Scala/JVM saves are a
separate exact-artifact representation launched by a new compatible runner; no
existing application process is retained. The implementation remains explicitly planned in
`SPRINT.md`, exact-artifact JVM first and packed CoreIR second, behind the P6.5/X1
kernel gate. Focused conformance is green: effects/deep-handler/coroutine/tail
4/4 (memoized); Markdown lint and touched-link checks pass.

---

## 2026-07-14 — std-ui-button-size: sm/md/lg sizing for std/ui button primitives

busi wants 4 nav buttons per row on a 390px-wide phone screen instead of 2 —
at the current fixed font-size/padding, four Cyrillic labels overflow the
row (confirmed via a real browser screenshot). Added `size: String = "md"`
(`sm|md|lg`, unrecognized → `md`, no crash) as the new last param (after
`variant`) on `signalButton`/`actionButton`/`signalLabelButton`/
`signalActionButton` (`runtime/std/ui/input.ssc`), a matching field on their
node case classes (`nodes.ssc`), and `_buttonFontSize`/`_buttonPadding`
resolvers in `lower.ssc`. Both resolvers reuse existing `Theme` tokens
instead of inventing new magic numbers — font-size maps to
`typography.{caption,body,heading}.fontSize` (`md` == the pre-slice
hardcoded default, byte-for-byte); padding shifts the `SpacingScale`
(vertical, horizontal) pair one step per size (`specs/std-ui-button-size.md`,
mirrors the `std-ui-button-variant` precedent, commit `136e6f6bb`). No new
`extern def`/backend change, except the same three call sites `variant`
needed to keep in sync with the node field count (`ContentIntrinsics.scala`,
`JvmGenContentEmit.scala`, `std-ui-jobpanel.ssc`) plus one the task brief
didn't enumerate: `tkv2-button-variant.ssc`'s own five node-deconstruction
patterns, which silently degraded to `"?"` output (caught by the sibling
conformance run before push, not a compile error). `tests/conformance/
tkv2-button-size.ssc` + `ButtonSizeTest.scala` (Scala-level proof the
rendered CSS font-size/padding genuinely differ: sm 12px/4px 8px, md 16px/
8px 16px, lg 24px/16px 24px) + `examples/frontend/button-variants/` extended
with a size row. Busi-side wiring (which button gets `size="sm"`) is a
separate, not-yet-done follow-up.

---

## 2026-07-14 — std-ui-button-variant: colour selection for std/ui button primitives

busi UX audit: every button ("Refresh" and "Prepare a real payment" alike) rendered in the same
hardcoded primary-blue, no visual hierarchy for stakes. Added `variant: String = "primary"`
(`primary|secondary|danger|success|warning`, unrecognized → `primary`, no crash) as the last param
on `signalButton`/`actionButton`/`signalLabelButton`/`signalActionButton` (`runtime/std/ui/
input.ssc`), a matching field on their node case classes (`nodes.ssc`), and a `_buttonColor`
resolver in `lower.ssc` (mirrors the existing `_colorOf`/`_statusColor`/`_linkColor` fallback
shape). Text colour reuses `theme.colors.onPrimary` uniformly for every variant — the same
white-on-accent trade `BadgeNode`/`TagNode`/`PillNode` already make, contrast measured not assumed
(`specs/std-ui-button-variant.md`). No new `extern def`/backend change (mirrors `std-ui-select`)
except three pre-existing call sites outside `input.ssc` that construct these node types
(content-toolkit's interpreter + JVM-codegen paths, `std-ui-jobpanel.ssc`'s pattern match) that
needed to stay in sync with the new field. `tests/conformance/tkv2-button-variant.ssc` +
`ButtonVariantColorTest.scala` (a Scala-level test proving the rendered CSS background genuinely
differs per variant, since `View` is opaque to `.ssc`) + `examples/frontend/button-variants/`.
Busi-side wiring (which button gets which variant) is a separate, not-yet-done follow-up.

---

## 2026-07-13 — v2 front: companion-object `val` members (unblocks UniML `Limits.default` etc.)

Fixed the first of the v2 `.ssc`-frontend gaps blocking UniML from compiling on v2: a companion-object
`val` member (`object Lim: val default = …`) was unresolvable (`Lim.default` → `unbound global:
Lim_default`), because the object lowering in `v2/lib/ssc1-lower.ssc0` handled `def` and `var` members
but silently dropped `val` members. Added `val` handling in three mirror spots (`prefixDefs`,
`staticMemberNames`, `staticMemberObjectArgs`): a `val name = e` inside an object now emits
`IrDef(Owner_name, lowerE(e))` — an eager static global, exactly like a `given` or a parameterless
`def` property. Verified: probe `Lim.default` → `direct=5`/`passed=5` (was unbound), and the flattened
UniML JSON dialect now advances past `Limits.default` to the next frontend gap (multi-param case-class
constructor defaults). Full v2 conformance green (self-hosting preserved). This is pervasive for UniML
(`Limits.default`, `SourcePosition.Start`, `JsonLimits.default`, `DialectRegistry.empty`).

---

## 2026-07-13 — `selectFrom` — reactive-options variant of std/ui `select` (select-from-signal)

Closes the `select-from-signal` BACKLOG entry. `select()` keeps its static
`List[(String,String)]` options unchanged; `selectFrom(items, key, optionFn,
selected, label, placeholder, disabled)` is a new, additive sibling whose
`<option>` children track a `Signal[List[A]]` reactively (busi's motivating
case: a fetched active-contracts list that can gain an item mid-session).
Reuses `forKeyedView`/`KeyedForNode`'s key-based reconcile algorithm, but as
a dedicated `_SelectFrom` View-level construct (mirroring `dataTableView`)
rather than embedding `forKeyedView` as a generic `element("select", ...)`
child — `<select>`'s HTML content model rejects `forKeyedView`'s wrapping
`<span data-ssc-key>` (silently dropped by the browser's "in select"
insertion mode), so the key has to live on the `<option>` directly and the
reconcile container has to be the `<select>` tag itself. New extern
`selectFromView` (interpreter snapshot fallback + a real `signals.mjs`
implementation: `_ssc_ui_selectFromView`/`_SelectFrom`/`_mountSelectFrom`).
Proven live by `JsRuntimeSelectFromTest.scala` (real `signals.mjs` runtime
under real Node, mirroring `JsRuntimeKeyedForTest.scala`'s method):
append/reorder/remove all preserve untouched `<option>` DOM node identity;
selecting an option still writes back to the bound signal. Runnable example
`examples/frontend/select-reactive-demo/` (build via `emit-js`/`emit-spa`).
Conformance: `tkv2-select-reactive` green on `[int, js]`; no regressions in
the sibling `tkv2-*`/`std-ui-*`/`tkv2-keyed-for` suite (10/10).

Found (not fixed) an unrelated pre-existing bug while building this:
`frontend/custom/StaticJsEmitter.scala`'s `jsLiteral` can't encode a
`List`-valued signal referenced by an event handler — crashes `ssc run` for
any such program, including the previously-shipped `keyed-for-demo.ssc`.
Unaffected: `emit-js`/`emit-spa` (the production pipeline). Tracked in
`BUGS.md`/`BACKLOG.md` § `custom-jsemitter-signal-list-literal`.

Spec: `specs/std-ui-select.md` § "Reactive options (selectFrom)".

---

## 2026-07-13 — UniML portable-subset lint + 2 caught misses; complete v2-frontend handoff (uniml-portable Phase 2/3)

Added `uniml/lint-portable-subset.sh`, a guard that scans the dual-compilable UniML sources
(core + json/yaml/markdown) for RUNTIME constructs that don't run on v2 — `scala.collection.mutable`,
`ArrayBuffer`/`StringBuilder`/`newBuilder`/`LinkedHashMap`/`HashSet`, regex `.r`/`.matches`,
`java.lang.Character`, Char-Unicode methods (`.isLetter`/`.isWhitespace`/…), `new Array` — and fails on
any (comments stripped first). It immediately caught two constructs the earlier passes missed: core
`Tree.sourceTokens`' `Vector.newBuilder` and `JsonLexer`'s `.isLetter`; both fixed (immutable `Vector`
accumulation; ASCII-letter check — JSON literals are ASCII). The lint now passes and guards against
regressions. It intentionally does NOT flag idiomatic Scala that is blocked only by the immature v2
`.ssc` frontend (companion `val` members, first-class object values, nested types in objects,
`final`/`sealed` modifiers, `Set.empty`) — those are standard Scala 3 and are the v2.2 track's to
support. Also probed and added to the gapmap the pervasive **companion-object `val` member** gap
(`Lim.default` → `unbound global: Lim_default`; only `def` members resolve) and isolated the remaining
`extends` gap to `JsonDialect`. The gapmap now carries the complete, prioritized v2.2-frontend handoff.

## 2026-07-13 — UniML optional layers immutable + portable: the whole library is construct-clean (uniml-portable Phase 1c complete)

Finished the UniML-side portability by rewriting the remaining optional semantic/projection layers to
be immutable and free of `scala.collection.mutable`, regex, and `java.lang.Character` — completing the
immutable/portable rewrite for the ENTIRE library, not just the parse paths. `JsonProjection`,
`YamlProjection`, and `MarkdownProjection`: all `Vector.newBuilder`/`ArrayBuffer`/`LinkedHashMap`/
`HashSet`/`StringBuilder` → immutable `Vector`/`Map`/`Set`/`Vector[String]`+`.mkString`;
`MarkdownProjection`'s `new String(Character.toChars(cp))` → a portable surrogate-pair encoder.
`YamlSemanticParser` (the delicate keystone): the plain `Parser`/`FlowParser` classes → an immutable
nested-def-over-local-vars shell; the 7 YAML core-schema scalar-type regexes (null/bool/int/float, JSON
int/float) → exact hand-rolled char-scanning predicates; `Character.digit(_, 16)` → `hexDigit`;
`char.isWhitespace` → a portable `isWs`. Behaviour-preserving across all modules (YAML incl. the
differential spec vs snakeyaml). Every UniML dialect file now has zero mutable/regex/Character gap
markers — the UniML side of dual-compilation is complete; the remaining blocker is the v2 `.ssc`
frontend (see the gold-standard finding).

---

## 2026-07-13 — std-ui-select: dropdown/select primitive for std/ui

Added `select(options, selected, label, placeholder, disabled): TkNode` to
`runtime/std/ui/input.ssc` (`SelectNode` in `nodes.ssc`, lowered in
`lower.ssc`) — the first `<select>`/dropdown widget in `std/ui`. Pure `.ssc`
composition of the existing `element`/`textNode`/`inputChange` primitives:
no new `extern def`, no changes needed in `FrontendIntrinsics.scala`,
`StaticJsEmitter.scala`, `CustomFrameworkBackend.scala`, or `JsGen.scala` —
both conformance lanes (interpreter + JS codegen) already implement
everything `select` needs. `options` is a static `(value, label)` list
(matches `TabBarNode.tabs`); reactive/fetched options are an explicit,
recorded follow-up (`select-from-signal` in `BACKLOG.md`), not built here.
Found and documented a pre-existing, unrelated bug in `bin/ssc run`'s
self-hosted standard-tier pipeline while building this (named args that
skip a non-first trailing default mis-bind) — confirmed not to affect the
v1 interpreter, `--v2` compat mode, or `emit-js`/`emit-spa`; tracked in
`BUGS.md`/`BACKLOG.md`, not fixed here. See `specs/std-ui-select.md`.

---

## 2026-07-13 — UniML gold-standard v2 finding: blocker is the `.ssc` frontend, not UniML (uniml-portable Phase 3)

Ran the actual JSON dialect flattened to one `.ssc` (core + json, package/import stripped) through the
self-hosted v2 compiler. Finding: UniML's runtime constructs are all individually v2-verified, but the
current v2 `.ssc` frontend cannot parse the full multi-declaration module — it does not parse the
`final`/`sealed`/`private` modifiers (the generator strips these), does not support type declarations
nested inside an object body (`unbound global: State`), and still fails on some `extends`/declaration
form in the large flattened file. These are v2 `.ssc`-frontend maturity items (the active v2.2
self-hosted-dialect track), not UniML constructs. UniML-side mitigation applied: hoisted
`JsonStructure`'s nested `enum`/`trait`/`case class` to top level (scalac-green, behaviour-preserving,
unimlJson 16/16) — the only nested-in-object types in JSON's parse path. Documented in the gapmap
"Gold-standard finding" section.

## 2026-07-13 — UniML Markdown parse path v2-construct-free + portable Unicode table (uniml-portable Phase 1c)

Made the Markdown parse path (MarkdownLexer/MarkdownInlines/MarkdownBlocks/MdChars) free of v2's
unbound-global constructs, completing all four dialect parse paths. Every `StringBuilder`/
`Vector.newBuilder` token buffer → an immutable `Vector[String]` joined with `.mkString` (the inline
`pending` buffer's hard-break `setLength(len-1)` becomes `dropRight(1)` — correct since a trailing
backslash is always a single-char element). The hard part — `MdChars`' `Character.getType`/
`isSpaceChar` used for CommonMark emphasis flanking — is replaced by a portable BMP range table (199
ranges, binary search) plus an enumerated Unicode-whitespace set. The table is **generated from**
`java.lang.Character.getType`, and a JVM-only `MdCharsParitySpec` proves it byte-for-byte equivalent
across all 0x0000–0xFFFF, so there is no CommonMark-conformance risk and JVM/JS now share one
classification. v2 handles the resulting 398-int `Vector[Int]` literal + binary search (probed).
Behaviour-preserving: markdown 34/34 JVM (32 behavioural + 2 parity) / 32 JS + DocumentContent bridge
11/11. Remaining Markdown work is the optional `MarkdownProjection` semantic layer.

## 2026-07-13 — UniML YAML parse-path structure immutable + v2 `.indices` (uniml-portable Phase 1c)

Probing YamlStructure surfaced two more v2 collection gaps: `.indices` → `no dispatch` (fixed v2-side,
an additive `isList` case in `Runtime.scala` mirroring `dropRight`), and `.groupBy` → returns a list of
`(key, values)` pairs rather than a `Map` (so `getOrElse` gives `Stub`). Then rewrote `YamlStructure`
(the YAML parse path; YamlLexer was already offset-based) to be immutable and v2-construct-free: the
plain `class BlockFrame(var last)` → an immutable `case class` whose `last` advances by replacing the
frame in the stack (`frames.map(_.copy(last = …))`); three `mutable.ArrayBuffer`s → `Vector`s; the
`Vector.newBuilder`s → `Vector`; and the Map-incompatible `groupBy(_.start).getOrElse(index)` →
`filter(_.start == index)`. Behaviour-preserving: unimlYaml 18/17 JVM+JS. Remaining YAML work is the
optional semantic/projection layers (plain classes + regex + `Character`), not the parse path.

## 2026-07-13 — UniML JSON dialect v2-construct-free + full dialect-gap map (uniml-portable Phase 1c)

Probed every dialect-level construct against v2 and mapped the complete 1c-compat surface (gapmap
"Dialect gaps" table): `StringBuilder` and `ArrayBuffer` are `unbound global` (v2 can't construct
them), plain `class` crashes, regex `.r` has no dispatch, and `Character.getType`/`isSpaceChar`/`digit`
lower to unresolved `Op`s. Then made the **JSON dialect** free of all of them: JsonLexer's token buffer
`StringBuilder` → an immutable `Vector[String]` joined with `.mkString`; JsonStructure's three
`ArrayBuffer`s → immutable `Vector`s threaded through nested defs, and its `Frame` state machine's
mutable `var state` → immutable case classes with copy-on-transition (`dropRight`+`:+`). Every
construct used is already v2-probed as working, so core + JSON dialect are now v2-construct-free.
Behaviour-preserving: unimlJson 16/16 JVM+JS. Remaining dialects (YAML: plain classes + regex;
Markdown: `Character` Unicode table) are the harder 1c-compat tail.

## 2026-07-13 — v2: Vector/List `.dropRight`/`.takeRight`; UniML core runs on v2 (uniml-portable Phase 3)

Probed the now-immutable UniML core against the self-hosted v2 `.ssc` compiler and found the
Phase-0.5 "wall" (mutable object fields → needs Array/anon-trait/plain-class support) is obsolete:
after the Phase 1/1d immutable rewrite the standalone lib uses none of those. The one real blocker
was `Vector`/`List` `.dropRight` (and `.takeRight`) — unimplemented in v2 (`no dispatch for
.dropRight on <foreign>`) — which is exactly the immutable stack-pop idiom (`xs.dropRight(1)`) the
rewrite uses in `TreeVm`/`XmlScanner`/`MarkdownBlocks`. Fixed v2-side with two additive cases in the
`isList` method block of `v2/src/Runtime.scala` (mirroring the existing `drop`/`take`); no existing
behavior changes (those calls previously crashed). The full immutable-core building-block probe now
runs end-to-end on v2 — added as `uniml/v2-smoke/core-blocks.ssc` (PASS). Remaining dual-compilation
work is in the dialects only (plain classes, regex, `java.lang.Character`) — the 1c-compat scope. See
`specs/uniml-portable-gapmap.md` (2026-07-13 UPDATE).

## 2026-07-13 — UniML immutable Markdown engine (uniml-portable Phase 1d, complete)

Finished the immutable rewrite of UniML's internal lexers by taking on the delicate Markdown engine
(~1500 LOC), completing Phase 1d. The mutable `TokenSink` (used only by `MarkdownBlocks`) is folded
into `MarkdownBlocks.parse` as local `var`s (token cursor `pos`/`nextId`/`out`/`frames`) with nested
`def` emitters; `MarkdownBlocks`' eight mutable object fields become locals — the container stack an
immutable `Vector`, the reference map an immutable `Map`, diagnostics/paragraph segments `Vector`s —
while the pure classifiers stay class methods and the dead `ListFrame.lastBlank` field is dropped.
`MarkdownInlines.WDelim` becomes an immutable `case class`: CommonMark's emphasis/delimiter algorithm,
which mutated delimiter lexemes in place and spliced a mutable buffer, now rebuilds the node `Vector`
by index (a reduced opener/closer is a fresh copy). Behaviour-preserving: Markdown 32/32 JVM + Scala.js
and the DocumentContent bridge 11/11. The Markdown module now has no mutable object fields; remaining
local builders and `MarkdownProjection`'s local mutable collections are a portability (1c-compat)
concern, not the object-model wall.

## 2026-07-13 — UniML immutable lexers: JSON/YAML/XML (uniml-portable Phase 1d, part 1)

Rewrote three of the four internal dialect lexers from mutable-object-field classes into pure `scan`
functions, eliminating v2's object-model wall (no class-level `var`/`ArrayBuffer`). Each keeps a local
`var`/`while` shell over immutable values with `Vector` accumulation; mutating helpers become nested
`def`s over the locals and pure classifiers stay top-level. `JsonLexer` (was 10+ mutable fields + 2
ArrayBuffers + push/finish/drain) → `JsonLexer.scan`; `YamlLexer`'s inner `Scanner` class (8 fields +
2 `Vector.newBuilder`) inlined into `scan`; `XmlScanner` (3 ArrayBuffers + counters + a local mutable
`HashSet`) → `XmlScanner.scan` with an immutable `Vector` element-stack and immutable `Set` attribute
dedup. Behaviour-preserving: unimlJson 16/16, unimlYaml 18/17, unimlXml 13/13 (JVM+JS). The markdown
block/inline engine (CommonMark's in-place delimiter stack) is the harder remainder, deferred to a
separate batch.

## 2026-07-13 — UniML immutable core (uniml-portable Phase 1)

Rewrote UniML's streaming core from a mutable-object-state design to a pure fold, per Sergiy's
direction ("нужно переписать uniml на чтото более иммутабельное", keeping the `step(state, chunk)`
fold for genuine incrementality). `Processor` is now `trait Processor[S, I, O]: start /
step(state, input): Stepped[S, O] / stop(state): ProcessBatch[O]` — replacing the old `push`/`finish`
+ mutable `finished` flag. `TreeVm` became a pure fold over an immutable `VmState` (frame stack,
counters, roots, diagnostics; local `var`/`while` shell over immutable values, no object fields).
`UniML.parse` threads the dialect processor over chunks then folds tokens through the VM with no
shared mutable state. All five dialect processors (literal/json/yaml/markdown/xml) are pure case
classes. Behaviour-preserving: green on scalac across JVM + Scala.js (core 15, json 16, yaml 18,
markdown 32) and the ScalaScript-side bindings (unimlXml 13, unimlMarkdownBridge 11); net −80 LOC.
Internal lexer mutability (now encapsulated inside a single pure `stop`) is carved out as
`uniml-portable-1d-lexers`.

## 2026-07-13 — UniML × v2 compile gap map (uniml-portable Phase 0.5)

Measured what the self-hosted ScalaScript v2 `.ssc` compiler accepts today, toward compiling UniML's
Scala 3 source unchanged. `specs/uniml-portable-gapmap.md` + a repeatable red v2-compile smoke
(`uniml/v2-smoke/`, `run.sh` via `v2/ssc1`). Result: v2 is **far more capable than its example corpus
suggested** — it already compiles+runs enums with ADT payloads and nested pattern match, generic
`def`s, generic `case class`es, `var`/`while`, traits + generic-trait `[I,O]` dispatch, and string
ops (`.length/.charAt/.substring/toString`) — most of UniML's structural surface. **Two blocking
gaps** remain: (1) `new Array[T](n)` with indexed apply/update is broken (the compat-layer floor);
(2) anonymous `new Trait[..]:` lowers to `unbound global: _err`. The smoke is RED on exactly those
two and GREEN on the rest; it turns green as the v2-side track (`uniml-portable-3`) closes them.

## 2026-07-13 — UniML relocated to a standalone library (uniml-portable Phase 0)

First step of the **uniml-portable** program (make UniML a standalone library whose single Scala 3
source compiles both with scalac and with the self-hosted ScalaScript v2 compiler). The version-
neutral modules (core/json/yaml/markdown) moved from `v1/lang/uniml*` to a top-level `uniml/` with
its **own** sbt build, so `cd uniml && sbt test` builds and tests them (all 8 suites, JVM+JS) with
zero dependency on the ScalaScript trees. The root build references the same sources (path update
only); the two v1 bindings that stay behind — `uniml-xml → Markup` and `uniml-markdown-bridge →
DocumentContent` — keep working unchanged (13/13, 11/11). Full program (compat layer, Scala3∩v2
subset, driving v2 to compile UniML, and ultimately the v2.2 parser on UniML) is planned in
`SPRINT.md`.

## 2026-07-13 — UniML Markdown: continuation-marker trivia + nesting (M4.1 tail)

Closes the last CommonMark edge cases of the M4.1 pass. Paragraphs are now buffered as per-line
segments `(prefix, content, ending)` rather than one woven string: the inline content is the
de-prefixed lines joined by their endings, so multi-line emphasis/links resolve cleanly across a
block-quote or list continuation marker, and each line's continuation prefix (`> `, list indent) is
spliced back in as a trivia token at its exact source position (right after the line's soft/hard
break) instead of leaking into the projected inline text. Combined with lazy continuation and the
earlier list-frame fix, deep/mixed container nesting is now correct — nested quotes/lists,
quote-in-list, list-in-quote, and lazy continuation into a nested quote (`> a / > > b / > c`). Leaf
32/32 on JVM and Scala.js; bridge 11/11. **UniML Markdown M4 and every M4.1 follow-up are complete**;
only the exotic HTML5-only named-entity long-tail remains deferred (unknown names stay literal,
lossless).

## 2026-07-13 — UniML Markdown: behavioral CommonMark conformance (M4.1)

Closed the behavioral edge cases the initial M4 pass deferred, each gated behind tests and keeping
the lossless CST and chunk-invariance intact:
- **Tight/loose list classification** — `ListBlock.tight` is now computed from blank-line placement in
  the CST (loose if items are blank-separated or an item holds two blank-separated blocks; a trailing
  blank stays tight) instead of the hardcoded `true`.
- **Lazy paragraph continuation** — a marker-less line of plain paragraph text continues an open
  paragraph inside a block quote or list item instead of closing the container; laziness stops at a
  blank line or any block start.
- **Full HTML-block type table (1–7)** — each type now uses its correct start and end conditions:
  type 1 (script/pre/style/textarea) ends at its close tag and spans blank lines; types 2–5 end at
  `-->`/`?>`/`>`/`]]>`; types 6–7 end before a blank line; type 7 requires a bare complete tag and
  cannot interrupt a paragraph. A stricter complete-tag check keeps autolinks (`<https://x>`) from
  being misread as tags.
Leaf 30/30 on JVM and Scala.js; bridge 11/11. The last tail (multi-line inline across a continuation
marker; deep/mixed nesting) is queued as `uniml-markdown-m41-tail` in `BACKLOG.md`.

## 2026-07-13 — UniML Markdown: full HTML4/XHTML entity decoding (M4.1)

`MarkdownProjection`'s named-entity table grew from ~15 entries to the complete HTML4/XHTML set
(~250). The 96 Latin-1 Supplement names are generated from their contiguous U+00A0..U+00FF block
(no transcription risk); Greek, general punctuation, arrows, mathematical operators, technical
symbols and suits are listed explicitly. Also corrects the old table's `nbsp`, which mapped to a
normal space rather than U+00A0. Numeric references already decoded; unknown names still stay
literal (lossless). A new spot-check test pins the generated Latin-1 ordering and a representative
sample of symbol decodings. Leaf 26/26 on JVM and Scala.js; exotic HTML5-only names remain deferred.

## 2026-07-13 — UniML Markdown → DocumentContent bridge (M4.1)

New optional `unimlMarkdownBridge` module (JVM-only, depends on `core` and the Markdown leaf; the
leaf never depends on it) projects a compatible `MarkdownDocument` into the existing ScalaScript
`DocumentContent` compiler model — headings build the nested section tree; paragraphs, lists,
images, tables and fenced code map to content blocks; inline emphasis/strong/code/link/expr map to
content inlines — reusing the compiler model without making it the canonical Markdown representation.
Every construct the target model cannot express (block quotes, thematic breaks, raw HTML, standalone
definitions, hard/soft-break distinction, task state, inline images, strikethrough) yields an
explicit model-loss diagnostic. Representable content is mapped exactly as
`Parser.buildDocumentContent` maps it, verified by a differential test against `Parser.parse`. Also
fixes a Markdown-leaf structural bug where a sibling list of a different marker type nested inside the
previous list frame (CST was always lossless). 11 bridge tests green; leaf stays 25/25 on JVM + JS.

## 2026-07-13 — UniML Markdown (M4) lossless CommonMark/GFM/ScalaScript adapter

Completed UniML roadmap M4 (M1–M4 JSON/XML/YAML/Markdown now all done). The new
`unimlMarkdown` / `unimlMarkdownJs` `CrossType.Pure` leaf (depends only on `unimlCross`)
reads Markdown as a lossless presentation language through the token-as-instruction VM: a
bounded, chunk-invariant whole-source scanner emits a lossless CST, and a separate semantic
projection exposes `MarkdownDocument`. CommonMark 0.31.2 is the baseline; GFM 0.29 (tables,
task items, strikethrough, extended autolinks) and ScalaScript (front matter, typed fences,
`${expr}`) are explicit opt-in profiles. Emphasis uses the CommonMark delimiter algorithm;
reference links resolve from collected definitions; raw HTML, destinations and expressions
stay inert (never rendered, fetched, or evaluated). 25 tests green on JVM and Scala.js —
losslessness, full two-chunk-split invariance (CRLF + surrogate pairs), profiles, malformed
input, limits, and a curated CommonMark 0.31.2 corpus. M4.1 follow-ups (lazy continuation,
deeper nesting, the `DocumentContent` bridge, full entity table) are queued in `BACKLOG.md`.

## 2026-07-13 — SclJet M2d read-only interop verification complete

The pinned read-only SQLite corpus is verified across every interpreter
execution tier. `tests/e2e/scljet-m2-corpus-smoke.sh` now runs the pure-`.ssc`
corpus dump, the 25 named corruption checks, and the 32 bounded fuzz mutations
on the default bytecode VM, the ASM JIT backend, and the pure tree-walk
fallback, requiring byte-identical results from each — closing the explicit
VM/ASM corpus-execution requirement. A new `overflow-thresholds.db` pins exact
table-leaf payload vectors straddling SQLite's overflow boundary (`p = X-1/X/X+1`,
the sharp `K > X` fall to the `m`-byte residue, the `K <= X` branch, and a
multi-page overflow chain), reproducible with the same byte-exact SQLite 3.53.3.
Corpus is 24 valid files / 629 exact oracle lines. The two aggregate M2 behavior
gates — byte-for-value corpus and safe-corruption diagnostics — are covered on
the interpreter/VM/ASM/fallback lanes; the JS exactness boundary is recorded
honestly (`scljet-js-m1/m2-parity`), with no JDBC/sql.js substitution.
Remaining M2d hardening (index-btree thresholds + deep corruptions) is queued
in `BACKLOG.md` as `scljet-m2d-hardening`.

## 2026-07-12 — ScalaScript 2.1 zero both-fail release closure

The exhaustive 200-example release corpus now has no VM/direct-ASM `both-fail`,
mismatch, one-sided error, standard gap, or runtime blocker. Fifty-six standard
rows execute byte-identically, 129 retain declared skip categories, and all 15
out-of-tier rows execute through an exact manifest of real provider/tools lanes
(8 provider, 7 target). PDF, MCP, Graph, SWIFT, NFC, quoted macros, WASM, x402,
and both JVM SclJet examples have deterministic launcher regressions. Standard
`ssc`, `ssc-standard`, and reproducible `build-jvm` remain compiler- and
Scalameta-free with zero forbidden references; stage-2 is source-exact, the
negative-toolchain sandbox reports `release.ready=true`, v2 conformance is
11/11, and affected SclJet conformance is 6/6.

## 2026-07-12 — SclJet immutable read-only pager, schema, and B-tree traversal

SclJet M2c now opens clean SQLite-format files through an abstract VFS under a
SHARED lock, keeps a deterministic immutable LRU, validates freelists and
auto-vacuum pointer maps, decodes raw `sqlite_schema`, and streams rowid or
record-key B-trees without mutation. A packaged JVM VFS adapter and runnable
SQLite 3.53.3 file example cover schema, row, public close, and cleanup through
the real plugin. Affected conformance is 6/6 and multi-level table/index cursor
output is exact on interpreter/native VM/direct ASM; the explicit Node
leaf-depth divergence is tracked for M2d. Core landed through `4aba98aef` and
`d52f89ead`, JVM facade/regression in `c281958bd`, docs in `0f5bec401`.

## 2026-07-12 — SclJet pure SQLite header, B-tree page, and record codecs

SclJet M2 now decodes exact database headers, all four B-tree cell layouts,
X/M/K local payloads, freeblock/cell spans, overflow pages/chains, every legal
persistent record serial type, IEEE binary64, and lossless SQLite GIGO text as
encoded bytes plus code points. An official SQLite 3.53.3 fixture and 35-line
golden are exact on interpreter/native VM/direct ASM; `scljet-*` conformance is
4/4 and a runnable no-JDBC example ships. JS matches 34/35 lines, with its known
64-bit bit-pattern gap isolated to binary64. Code landed in `66ff828b9`, docs in
`ae709c40a`.

## 2026-07-12 — SclJet M2 read-only format and traversal contract

The implementation-ready M2 specification now fixes exact APIs and invariants
for database headers, B-tree pages/cells, X/M/K payload splitting, overflow,
freelist/pointer maps, record serial types, SQLite's lossless invalid-UTF policy,
immutable SHARED-locked paging, forward table/index traversal, raw schema
classification, limits, localized errors, and a valid/corrupt oracle corpus.
Writes, recovery/WAL overlays and SQL/DDL semantics remain explicitly later
milestones. The differential oracle is pinned to SQLite 3.53.3. Landed in
`7a6e2e70a`; `scljet-*` conformance remains 3/3.

## 2026-07-12 — SclJet M1 VFS foundations and JVM SQLite lock adapter

SclJet now has complete VM/ASM M1 foundations: immutable chunked bytes and SQLite
codecs, a replayable deterministic memory VFS, and a dedicated JVM std plugin for
positioned durable I/O, rollback byte-range locks, WAL shared memory and barriers.
The assembled distribution autoloads the essential plugin; its suite is 6/6,
including official Xerial SQLite contention across processes, while affected
conformance is 3/3 and all 31/33/6-line portable goldens are exact on
interpreter/native VM/direct ASM. JVM plugin code landed in `2a594b870`, example
in `1b9df2b57`; JS companion/list matching was repaired in `830c0db27` and exact
chunk indexing in `f9518f881`. JS now executes both pure programs, with exact
64-bit codecs and two SHM-lock assertions explicitly retained as open gates.

## 2026-07-12 — UniML lossless safe YAML 1.2.2 profile

The new `scalascript-uniml-yaml` JVM/Scala.js module preserves streams, directives, document markers,
block/flow structure, scalar presentation, tags, anchors, aliases, comments, whitespace, ordering, and
duplicate entries through the common source-token VM. Safe projection supports Core/JSON/Failsafe
schemas, inert tags, ordered duplicates, and preserved or explicitly bounded alias graphs. The shared
suite is 17/17 on both targets, JVM adds a SnakeYAML Engine differential gate (18/18 total), eight
official YAML test-suite cases are pinned, and affected content conformance is 6/6. Core implementation
landed in `48720429c`; corpus gate in `0cf72b971`.

## 2026-07-12 — SclJet deterministic immutable memory VFS

SclJet M1 now includes a replayable pure VFS transition model with random
access, durable sync/crash recovery, rollback and WAL shared-memory locks,
logical time/randomness, exact traces, and scripted error/short-I/O/crash
injection. A 33-line multi-handle golden and runnable recovery example are
identical on v1, native VM, and direct ASM. Landed in `e6d027b92`; docs and
example in `ef9816597`.

## 2026-07-12 — UniML secure lossless XML 1.0 dialect

The new `scalascript-uniml-xml` cross-module reads XML 1.0 Fifth Edition into the common UniML VM,
preserving declarations, opaque DOCTYPE subsets, exact tags/attributes, mixed text, references,
CDATA, comments, PIs, whitespace, QNames, and arbitrary chunk boundaries. It validates root/tag
structure, exact XML Name ranges, namespace scopes/reserved bindings and expanded attribute
uniqueness without loading any external resource. Safe CSTs project into the existing resolved
`Markup.Doc`; custom entities remain lossless but explicitly block projection. Landed in
`54b61ba5b`/`30befecea`; UniML XML is 13/13 and markup-core 17/17 on both JVM and Scala.js, with 6/6
affected content conformance.

## 2026-07-12 — UniML strict RFC 8259 JSON dialect

The new `scalascript-uniml-json` cross-module reads strict RFC 8259 JSON through the common UniML
token-as-instruction VM on JVM and Scala.js. It preserves every punctuation/whitespace token, exact
string and number spelling, object order, duplicate names, Unicode code-point spans, and arbitrary
transport chunk boundaries. Its semantic projection keeps ordered members and exact numbers, warns
on decoded duplicate keys and unpaired surrogate escapes, and requires an explicit policy before
lossy map conversion. JSON5/JSONC extensions remain rejected under the standard id. Landed through
`2a3e2b0d8`, `c84e3c35b`, and `21444f270`; 16/16 tests on each platform and 5/5 assembled JSON/fuzz
conformance pass.

## 2026-07-12 — SclJet immutable byte slices and SQLite codecs

SclJet M1 now has target-neutral immutable 64-byte-chunk storage, bounds-checked
functional updates/slices/copies, endian integer codecs, signed reads, and exact
SQLite varint encoding/decoding. Golden boundary and malformed cases pass on the
v1 interpreter, native VM, and direct ASM; the runnable byte-codec example is
identical on all three. Landed in `58d2e19de` with docs in `3aeb22068`.

## 2026-07-12 — UniML universal token-to-tree VM core

The new dependency-free `scalascript-uniml` module cross-compiles for JVM and Scala.js. It provides
lossless source tokens and ordered CST nodes, a bounded Open/Emit/Close/Report tree VM, structured
diagnostics, synchronous processor chains, dialect registration, and a chunk-invariant Unicode
literal fallback that can retain any unknown language without falsely claiming a semantic parse.
The normative spec defines separate compatibility gates for JSON, YAML, XML, Markdown, and future
programming-language adapters, and keeps projections compatible with existing `Markup` and
`DocumentContent`. Landed in `9815338ea`/`c79787d46`; 10/10 JVM and 10/10 Scala.js tests plus 6/6
neighboring content conformance pass.

## 2026-07-12 — SclJet SQLite-compatible engine specification and M0 contracts

SclJet now has a normative pure-ScalaScript architecture from byte codecs and
abstract VFS through pager, B-trees, rollback/WAL, SQL execution, extensions,
security, and M0-M8 verification gates. The `runtime/std/scljet/` scaffold fixes
portable public value/error/options, random-access locking/shared-memory VFS,
connection/statement/cursor, function, and collation contracts without a fake
engine or platform dependency. Native VM, direct ASM, and affected conformance
are green. Contracts landed in `449cfab0f`.

## 2026-07-12 — Compatibility SQLite startup no longer scans shared temp

The v2 compatibility bridge now gives Xerial SQLite a private per-process
native extraction directory instead of letting its first connection enumerate
the complete shared macOS temp directory. The real in-memory SQLite fixture
finishes in 1.7 seconds rather than exceeding 15 seconds; plugin bridge is
32/32 and affected conformance is 1/1. Landed in `b55811bf9`.

## 2026-07-12 — ScalaScript 2.1 toolchain-independence parents closed

The two stale umbrella rows now reflect their already-landed evidence: native
frontend/checker parity is exhaustive and the standard launcher has no javac,
compiler, or Scalameta dependency. The authoritative negative release gate
remains 194/194 checked, 53/13/129 VM/ASM classification, zero mismatch,
one-sided failures, or runtime blockers, with conformance 11/11. No runtime or
seed code changed in this bookkeeping closeout.

## 2026-07-12 — ScalaScript 2.1 negative toolchain release gate

CI and the consolidated release now re-run the 195-row native frontend/checker
and VM/direct-ASM corpus from a copied standard-only distribution with no
compiler/Scalameta JARs, compiler commands, or compiler modules. Native
providers and an HTTP server pass in the same environment, and a frozen report
self-test rejects launcher/JAR/tool/module/parity/blocker drift. The gate also
found and fixed the reflective `jdk.crypto.ec` Ed25519 provider edge. Landed in
`43fded0f9`; exhaustive release and conformance 11/11 pass.

## 2026-07-12 — Plain `ssc` is the compiler-free ScalaScript 2.1 launcher

Staged, contributor, and self-installed `ssc` now selects `StandardMain` and
the physical standard graph; `ssc-standard` is an equivalent alias. Scalameta,
v1, compiler, and legacy backend commands require explicit `ssc-tools`, and the
standard launcher never delegates or falls back. Slim/module-limited VM, direct
ASM, reproducible `build-jvm`, exhaustive 195-row release, exact zero-blocker
freeze, and conformance 11/11 are green. Landed in `e28560761`, with explicit
compatibility harnesses in `7ed7c630e` and `849907875`.

## 2026-07-12 — ScalaScript 2.1 runtime release taxonomy is frozen

The exhaustive self-hosted-core release gate now enforces the exact zero-blocker
snapshot, not only a migration ceiling: 7 reviewed optional-provider rows, 6
tools-backend rows, 0 standard/language/example blockers, and 13 total. A
synthetic self-test rejects count changes in either direction, blocker growth,
and malformed snapshots. Full release remains 53/13/129 with zero mismatch or
one-sided failures and fresh conformance 11/11. Landed in `3e10ba0d5`.

## 2026-07-12 — typed SQL CRUD closes the final standard runtime blocker

The compiler-free ScalaScript 2.1 frontend now retains `Db.query[A]` nominal
type arguments and derives bounded portable row schemas from `Mirror`. The
core-free SQL provider owns case-insensitive typed reads and fully-bound,
identifier-validated insert/update operations, including nullable `Option` and
deterministic negative diagnostics. Public typed CRUD is exact on standard VM,
direct ASM, slim/JRE, and reproducible `build-jvm`; exhaustive parity reaches
53/13 with 0 language, 0 standard, and 0 blocking both-fail rows. Landed
through `333d0a9bd`.

## 2026-07-12 — raw SQL fences are self-hosted and core-free

The ScalaScript 2.1 document frontend now retains `sql` fences in exact source
order, rewrites `${expr}` to JDBC binds, preserves `$$`, and exposes every
`_sqlBlock_N` plus the first `<Section>.sql` result through the core-free SQL
provider. The public H2 quickstart and focused positive/negative cases are
exact on standard VM, direct ASM, slim/JRE, and reproducible `build-jvm`; no v1,
Scalameta, compiler, bridge, or transparent fallback is loaded. Full release
parity advances to 52/14 with zero mismatch/one-sided rows and one remaining
typed CRUD blocker. Landed through `e3632db14`.

## 2026-07-12 — structural optics are self-hosted and core-free

The compiler-free ScalaScript 2.1 frontend now retains structural
`Focus[T]` field/`.some`/`.each` paths and exact `Prism` variants. A required
core-free provider implements immutable Lens, Optional, Traversal, and Prism
operations on standard VM, direct ASM, slim/JRE, and reproducible `build-jvm`;
unsupported getter expressions fail explicitly without macros, reflection, or
compatibility fallback. All 23 public lenses rows are exact, parity reaches
51/15, and language-runtime blockers reach zero. Landed through `16a4b9f8f`.

## 2026-07-12 — native case-class copy preserves named labels

The compiler-free ScalaScript 2.1 frontend now carries named `.copy` overrides
through portable field metadata instead of erasing them to positional values.
Receiver and overrides evaluate once from left to right, out-of-order labels
rebuild the correct fields, and positional prefix copy is unchanged. Focused
standard VM/direct-ASM/build-jvm output is exact; release remains 50/16 with
three honest blockers until native optics lands, with fresh conformance 11/11.
Landed in `d01d2e9f1`.

## 2026-07-12 — explicit Option/List direct syntax is self-hosted

The compiler-free ScalaScript 2.1 frontend now preserves explicit
`direct[Option]` and `direct[List]` blocks and lowers fresh assignments through
portable `flatMap`, including Option short-circuiting, stable List Cartesian
order, pure and mutable locals, and nesting. Unsupported monads fail explicitly
without v1/runtime fallback. The public 11-line example is exact on VM, direct
ASM, and reproducible `build-jvm`; parity is 50 identical / 16 both-fail with
three blockers and fresh conformance 11/11. Landed through `602c91cb2`.

## 2026-07-12 — product Mirror and custom derives are self-hosted

The compiler-free ScalaScript 2.1 frontend now retains product field type
spellings and `derives` clauses, emits portable `Mirror.Of` /
`Mirror.ProductOf` evidence, and initializes one cached custom dictionary via
the known `TC.derived` companion in source order. The public four-line example
is exact on VM, direct ASM, and reproducible `build-jvm`; parity is 49 identical
/ 17 both-fail with four blockers and fresh conformance 11/11. Landed through
`151fd65b1`.

## 2026-07-12 — parameterless def values and pair callbacks are native

The self-hosted frontend now preserves `def value: T` versus `def value(): T`
and auto-applies only bare references to the former. The shared v2 collection
runtime also reconciles portable `Pair/2` with `Tuple2/2` for matching
two-parameter `List.map`/`flatMap` callbacks without changing direct function
arity. The complete mini-language example is exact on VM, direct ASM, and
reproducible `build-jvm`; parity is 48 identical / 18 both-fail with five
remaining blockers and fresh conformance 11/11. Landed through `06a518685`.

## 2026-07-12 — process-local Graph storage is core-free

The ScalaScript 2.1 standard tier now owns deterministic process-local property
and RDF graph storage, ordered vertex/edge/triple traversal, structural portable
values, and explicit remote SPARQL/Cypher/Gremlin boundaries without the v1
graph plugin or compatibility fallback. The local public example is exact on
VM, direct ASM, slim, and reproducible `build-jvm`; full parity is 47 identical
/ 19 both-fail with zero mismatch/one-sided rows, leaving 6 blockers. Landed
through `ff42d5d57`.

## 2026-07-12 — distributed local-loopback MapReduce is core-free

The ScalaScript 2.1 standard tier now supplies deterministic process-local
named handlers, contiguous partitioned map/filter/flatMap, first-key-order
shuffle group/reduce, portable result fields, and idempotent local cluster
close. Imported uppercase object calls bind exact provider-owned
`HandlerRegistry.*` operations without an effect fallback. The self-hosted
frontend also retains literal and typed tuple-field refinements, making both
public distributed examples exact on VM, direct ASM, slim, and reproducible
`build-jvm`. Full parity is 46 identical / 20 both-fail with zero mismatch or
one-sided rows, leaving 8 blockers. Landed through `e0e7e98c3`.

## 2026-07-12 — local and typed-loopback Actors are core-free

The ScalaScript 2.1 standard tier now supplies FIFO virtual-thread actors with
plain/timed receive, self/send/exit, quiescent scope shutdown, propagated child
failures, and typed process-local named loopback. The self-hosted frontend now
lowers infix `pid ! msg` correctly and primitive typed patterns match `String`;
OS and Actors compose the overloaded `exit` surface without weakening strict
provider ownership. Unit coverage is 4/4 plus OS dispatch 3/3, and focused plus
both public examples are exact on VM/direct ASM/build-jvm. Full parity is 44
identical / 22 both-fail with zero mismatch/one-sided rows, leaving 10 blockers
out of 22 taxonomy rows. Landed through `2230ebc8a`.

## 2026-07-12 — built-in Async runners are core-free

The required ScalaScript 2.1 effect-runners provider now owns deterministic
`runAsync` and ordered virtual-thread `runAsyncParallel`, including opaque
futures, delay, async/await/parallel, nested scopes, explicit failures, and a
bounded `recvFrom` bridge. Unit 4/4 uses latches to prove concurrent start and
ordered join; focused and public demos are exact on VM/direct ASM/build-jvm.
The release gate is ready at 42 identical / 24 both-fail, zero mismatch or
one-sided rows, and 12 remaining blockers. Landed through `7ac63130d`.

## 2026-07-11 — pull generators are core-free on the standard JVM path

ScalaScript 2.1 now supplies a required native Generator provider with
synchronous backpressure, virtual-thread producers, explicit error delivery,
ordered lazy combinators, and cancellation of bounded infinite sources. The
focused lifecycle fixture and complete public demo are exact on VM, direct ASM,
and deterministic `build-jvm`; dependency and slim-distribution gates remain
compiler/Scalameta/bridge-free. Parity improves to 41 identical / 25 both-fail
with zero mismatch/one-sided rows, leaving 13 blocking taxonomy rows. Landed
through `6f3c398e5`.

## 2026-07-11 — local Dataset execution is core-free

The ScalaScript 2.1 standard tier now supplies a required lazy Dataset provider
for VM, direct ASM, and deterministic `build-jvm`, including stable local
transformations, UTF-8 files, iterative 100k-list conversion, and ordered
virtual-thread pointwise execution. Structural List/Option/Either selectors
dynamically fall through for opaque provider receivers, so the canonical
word-count chain stays fluent. All three public Dataset examples are exact;
the full release gate is ready at 50 runtime successes, 40 identical / 26
both-fail, zero mismatch/one-sided rows, and 14 remaining blockers. Landed
through `9feff81a8`.

## 2026-07-11 — nested native documents render as content

The core-free ScalaScript 2.1 host provider now recursively flattens nested
`doc(...)` values while leaving ordinary values on the shared display path.
Empty nested documents add no separator and the reserved `NativeDoc` tag never
leaks into output. The complete content example is exact on VM, direct ASM, and
`build-jvm`; host unit is 3/3, every release/dependency gate passes, and
conformance is 17/17. Landed `fe279650d`.

## 2026-07-11 — native `md` interpolation is self-hosted

ScalaScript 2.1 now parses `$name` / `${expr}` and strips common indentation for
the normative `md"..."` interpolator entirely in the ScalaScript-written front.
It emits the existing portable primitive directly, adds no provider or parser
dependency, and leaves ordinary lexical calls intact. Focused and complete
content output is exact on VM, direct ASM, and `build-jvm`; stage-2 and every
release/dependency gate pass, conformance is 17/17, and the standard runtime
blocker total falls from 19 to 18. Landed through `50715b7a3`.

## 2026-07-11 — native parser recovery loads wrapped imports and bind patterns

The self-hosted module loader now recognizes standalone Markdown import labels
wrapped across physical lines without treating prose or fenced examples as
dependencies. Constructor bind patterns preserve the complete matched value,
nested fields, once-only evaluation, and ordered fallback in portable CoreIR.
`dsl-sql-recovery.ssc` is sentinel-clear and matches its sixteen-line explicit
compatibility output on installed VM/direct ASM. The release gate is green with
37 identical / 29 both-fail / zero mismatch or one-sided rows and 17 remaining
blocking taxonomy rows. Landed through `d25ff19ee`.

## 2026-07-11 — portable standard effect runners complete natively

ScalaScript 2.1 now runs Logger, State, and Stream standard effects through
core-free providers and portable operations, including nested runner
restoration and reusable multi-shot resumes. The obsolete hidden multi-effect
CPS convention is removed, and definition-aware frontend reconciliation
supports multiple source parameter clauses while preserving strict CoreIR
under/over-arity failures. `algebraic-effects.ssc` is exact on installed VM and
direct ASM; the consolidated gate is release-ready with 35 identical, 31
both-fail, zero mismatches/one-sided rows, and 19 remaining blockers. Landed
through `a1166ca52`.

## 2026-07-11 — native `doc` / `render` stay core-free

The ScalaScript 2.1 host provider now supplies lexical-safe `doc` and `render`
handlers to VM and direct ASM without v1 `DocV`, `PluginBridge`, Scalameta, or
a parser/renderer dependency. Ordered values share the `println` display
contract; local definitions still win. VM/ASM/standard/build-jvm output is
exact, dependency and distribution gates pass, and affected conformance is
17/17. Landed through `5b6bb6b5d`.

## 2026-07-11 — fresh native Signal constructors use the reactive provider

Current-source installations no longer let K62.33 `Signal` constructors bypass
the core-free reactive provider for legacy raw cells. VM and direct ASM prefer
the provider and retain the old cell only in a bare kernel, restoring complete
subscription updates in `signals-demo.ssc` and deterministic build-jvm output.
Landed `04b7f5fd1` and `8cf29ede2`.

## 2026-07-11 — ScalaScript 2 ships verified SwiftUI Apple applications

Checked-v2 Xcode artifact authority now drives physical-device deployment,
canonical IPA and Mac PKG exports, Developer-ID verification, bounded
keychain-profile notarization/stapling/DMG, and explicit TestFlight/App Store
uploads. Every route preflights tools and credentials, verifies the exact
platform app, bypasses v1, and prevents fastlane rebuild/product discovery.
Rozum round 3 approved; Swift 43/43, CLI 53/53, assembled e2e, and toolkit
conformance 12/12 pass. The final assembled gate byte-compares complete trees,
bounded-launches the real macOS APPL bundle, and verifies the same checked app
on a concrete iPhone 16 Pro Simulator; money 2/2, effects 4/4, and v2 11/11
complete the release corpus. Landed through `7e4b2e563`.

## 2026-07-11 — JS runtime owns one Node crypto binding

The filesystem runtime now reuses the core-collections `_nodeCrypto` binding
instead of redeclaring it in the concatenated classic-script scope. The new
declaration-count regression passes 22/22 and restores all `tkv2-*` JS lanes;
the assembled no-memo corpus is 12/12. Landed `aab53ab3c`.

## 2026-07-11 — dynamic selected toString preserves BigInt values

The self-hosted lowerer now keeps `i->str` only for proven integer literals and
routes dynamically shaped `.toString` receivers through the portable method
table. Int, BigInt, Float, String, Decimal, and structural fallbacks therefore
share one VM/ASM contract. `content-linked-namespaces.ssc` resolves its imported
section and prints BigInt minor units `1234` identically on native VM, direct
ASM, and deterministic `build-jvm`. Landed `e2511c6ad`.

## 2026-07-11 — content binding executes as pure ScalaScript

`contentBind` now validates and resolves dotted `ContentValue.MapV` paths,
recursively rebuilds documents/sections/lists/tables and nested inline tags,
preserves unresolved expressions, and renders scalar/list/map values entirely
inside `std/content.ssc`. The core-free provider performs no path parse or
binding fallback. Positional record-copy semantics are shared by the permanent
Scala 3 seed and compatibility lanes; INT/JS/JVM conformance, native VM/direct
ASM, standard/slim/JRE, and deterministic `build-jvm` are exact. Landed
`208ec4c60`; contract `75eb9ac0e`; verification `1a50b8276`.

## 2026-07-11 — SwiftUI emits verified macOS and iOS Xcode applications

Checked v2 NativeUi sources now emit one deterministic objectVersion-56
multi-platform application target and app-only shared scheme with exact
AppCore/AppleApp/resources membership. Typed Xcode artifact authority,
destination build-setting discovery, and plist verification keep every
unsigned Apple build/run lane on the real `.app`, never the SwiftPM debug CLI;
manifest-scoped atomic ownership preserves user resources across mode/product
changes. Real macOS launch and installed iOS 26.5 Simulator builds passed,
independent Rozum round 3 approved, Swift backend is 43/43, CLI 8/8, assembled
e2e passes, and toolkit conformance is 12/12. Landed `d1b4350b7`,
`abf9943c8`, and `3942297ca`; docs `40eb9c31f`.

## 2026-07-11 — named givens remain first-class dictionaries

The self-hosted import filter now retains `given_obj`, and given globals reuse
the existing portable method-object representation instead of integer
sentinels. Distinct imported dictionaries preserve properties and callable
members through parameters, returns, aliases, collections, direct calls, and
exact summon; `typeclass.ssc` prints all eighteen compatibility lines exactly
on native VM/direct ASM. The same fresh report retired the already completed
HTTP `mount` row: runtime taxonomy is 10 language / 13 standard / 6 optional /
0 example / 6 tools, 23 blockers / 35 total. The exhaustive compiler-free gate
is green at 194/194 front/check, 44/82 runtime, parity 31/35/129 with zero
mismatch/one-sided rows, zero sentinel standard gaps, and conformance 11/11.
Landed `8822fa710`; regressions `0b596a075`; taxonomy `77da8e8e2`; verified
`943343fd1`.

## 2026-07-11 — SwiftUI isolates trusted HTML in native Apple WebKit

Generated macOS/iOS renderers now mount exact sourced `NativeUiTrustedHtml`
values in per-view nonpersistent WKWebViews with page JavaScript disabled,
network subresources blocked by real compiled content rules, dynamically
bounded height, and shared safe external-link handoff. Serialized document
policy authority, exact navigation identity, generation checks, and teardown
make replacement, delayed compilation, stale callbacks, and failure recovery
fail closed. Independent round-3 Rozum review approved; Swift backend passed
41/41, the final real macOS/iOS16 gate 2/2, and toolkit conformance 12/12.
Landed `7cc1ff978`; docs `3a694d901`.

## 2026-07-11 — native content stays self-hosted across runtime and artifacts

The self-hosted tower now projects canonical Markdown/YAML values into complete
`DocumentContent` modules with normalized direct-import edges before crossing
the permanent Scala 3 seed. A core-free provider exposes current/imported
lookups plus plain-text and semantic-Markdown rendering on VM, ASM, standard,
slim/JRE, and deterministic `build-jvm`; artifacts embed the same immutable
values in `content.bin`. No v1 content bridge, Scalameta, CommonMark/Flexmark,
or host reparse is used. Full parity is 32/34/129 with zero mismatch or
one-sided failure, and runtime blockers fall to 22. Landed `282f1f2c9`; contract
and evidence `cd63d01c4`, `65a796c20`.

## 2026-07-11 — parser DSLs preserve composed values end to end

Imported extension calls now use member-first portable dispatch, local tuple
destructuring binds every component, and typed match arms retain nominal ADT
tags with tag-before-guard fallthrough. Native VM/direct ASM exactly match all
seven JSON and seventeen YAML compatibility lines with empty stderr and no
`Stub`; the focused composed-value and layout probes are exact. The exhaustive
self-hosted release gate reports 194/194 front/check, 40/86 runtime, zero
mismatch/one-sided rows, zero sentinel standard gaps, and fresh conformance
11/11. Landed `9d5f13f95`, `0b5d1c69c`, `d4cc66736`; contract/results
`8a411cd9a`.

## 2026-07-11 — imported two-element tuples match across collection boundaries

The self-hosted lowerer now gives source-level two-element tuples one
observable identity across its internal `Pair/2` representation and JVM
collection `Tuple2/2` values. Positional selectors plus direct and nested tuple
patterns work through imports on native VM/direct ASM; `imports.ssc` and
`extensions.ssc` become identical successes. Full parity is 29/37/129 with no
mismatch or one-sided errors, blocking runtime rows fall to 25, and every
compiler-free release gate plus conformance 11/11 passes. Landed `579679058`;
contract/results `bed01d886` and `b1117a93f`.

## 2026-07-11 — SwiftUI renders transactional keyed native tables

Generated Apple apps now share one strict macOS/iOS Grid table runtime for
static, signal, and fetch sources, with typed dotted row identity,
deterministic columns, last-good retention, exact row payloads, inline-edit
dedupe, and normalized relative request URLs. Row actions authenticate the
current descriptor/action slot/row at launch and completion through the shared
generation-aware URLSession runner, so replacement and disposal cannot publish
stale work. Named table gates passed 6/6 and full Swift backend 40/40, including
macOS execution and iOS 16 strict typecheck; round-3 Rozum review approved with
no lifecycle leak. Landed `d54d02126`; docs `2f7d600f9`.
The final controllable-URLProtocol gate was also made race-free in
`400931f68`; action stress passed 5/5 before another named 6/6 and full 40/40.

## 2026-07-11 — imported recursive records cross the compatibility bridge in linear space

`v2ToV1` now converts each `DataV` field once and shares the result across
named, positional and array `InstanceV` layouts, eliminating the pre-existing
`3^depth` expansion exposed by self-hosted JSON. An 18-level imported fixture
that OOMs the old runtime at 512 MiB now reaches its exact sentinel; bridge
31/31, JSON conformance 4/4, and busi's full JVM/JS/live-HTTP/Chromium 6/6
release matrix are green. Landed `2f3994b31`; contract in
`specs/bridge-v2tov1-openapi-oom.md`.

## 2026-07-11 — HTTP fast provider ships in every compiler-free artifact

The standard distribution and deterministic `build-jvm` artifact now include
the hf-5 fast native provider plus its value-agnostic engine, while the removed
provider is absent. Dependency/provider gates own and scan both artifacts, the
engine remains non-ServiceLoader, and native VM/direct ASM positively execute
`useGzip()`. The focused matrix and consolidated quick release gate are green.
Landed `d503cf856`; verified in `specs/v2-http-fast.md` hf-8.

## 2026-07-11 — storage release gates use the portable container renderer

Native-entry, provider-boundary, and standalone build-jvm storage assertions
now match conformance's `Some(alice)` and `List(user, role)` output. The full
affected release matrix confirmed the reconciliation while closing the HTTP
standard-tier cutover. Landed `befc249d4`; release confirmation `d503cf856`.

## 2026-07-11 — SwiftUI persisted and online signals own native Apple resources

Generated Apple stores now load and transactionally commit `persistedSignal`
Strings through UserDefaults without depending on a rendered cell, while failed
root/keyed work, wrong types, and stale/disposed wrappers are inert. One
process-wide root `onlineSignal` shares a first/last-owner NWPathMonitor across
direct and derived readers; callbacks hop to the main actor and stale monitor
generations cannot publish. Swift backend 31/31, toolkit conformance 12/12, and
independent Rozum review are green. Landed `0ade8bf7c`; docs `d931d759a`.

## 2026-07-11 — curried collection folds share VM and ASM semantics

Portable `foldLeft(z) { f }` now returns a real partial closure after its first
argument list and completes through the existing effect-aware collection
dispatcher. Lists and mutable arrays produce canonical results on native
VM/direct ASM; `functional.ssc` ends with `1, 3, 6, 10, 15`, and full strict
parity returns to zero mismatch/one-sided rows. Landed `4c5254eed`; verified in
`specs/v2.1-curried-collection-fold.md`.

## 2026-07-11 — fast --v2 preserves forms, sessions and authentication

The fast HttpServerSpi backend now feeds its raw request through the shared
transport-neutral `RequestBuilder`, restoring urlencoded/multipart forms,
cookies, signed sessions, bearer/basic auth and cleanup of spooled files. The
real-socket regression proves pair form -> cookie -> authenticated request plus
signed-session round-trip. Common 150/150, fast 5/5, interpreter-server 58/58,
INT/JS/JVM conformance, busi Vault restart/leakage and canonical Chromium 6/6
are green. Landed `d202d2abf`; contract in `specs/v2-http-fast.md` hf-6d.

## 2026-07-11 — imported mapped parsers stay portable across VM and ASM

A clean assembly proved the reported `PMapped/2` exception was a stale staged
distribution, not a missing generic match arm. An exact multi-file regression
now imports the parser evaluator and maps `20` to `22` with unchanged input and
position on native VM/direct ASM; focused JSON/YAML parser smokes enforce clean
exit and byte parity without adding host parser code. Landed `5b16df6df`;
taxonomy reconciliation `06a1ae9bb`; verified in
`specs/v2.1-imported-pmapped-evaluation.md`.

## 2026-07-11 — interpreter HTTP mutations are fair and durable

Interpreter-backed HTTP now shares one weak per-interpreter fair read/write
gate across middleware, routes, streams and WebSocket callbacks. Safe reads
remain concurrent, mutations exclude every callback without writer starvation,
and distinct interpreters plus socket I/O stay parallel. Focused concurrency
passed 8/8, the server module 58/58, `rest-validate` on INT/JS/JVM, and busi's
assembled six-scenario Chromium matrix including offline drain, Housing and all
eleven Vault transitions. Landed `1f7ea78d7`; contract and evidence are in
`specs/http-handler-serial-dispatch.md`.

## 2026-07-11 — SwiftUI events mutate authenticated Host cells safely

Native set/input/toggle/increment dispatch now binds validation, dynamic read,
and write to the same current Host cell, so forged/stale wrapper closures are
inert. Read-only targets are source-rejected, Int64 overflow is non-trapping,
and pristine seed events observe the latest source before becoming dirty.
Strict Swift 6 execution, full backend 30/30, affected conformance 12/12, and
Rozum review are green. Landed `f062a9184`, `9ae1a130b`, and `12fae35e7`.

## 2026-07-11 — nested member layout preserves extension receivers

Indented and explicit-brace extension bodies now own a distinct close marker,
so nested match/lambda layout cannot erase the receiver from later members.
Imported receiver-only and parameterized members retain exact arity on
VM/direct ASM; the YAML-like parser leaves its false arity-zero call and joins
the shared `PMapped/2` evaluator gap. Landed `878474b8d`; spec and baseline in
`specs/v2.1-extension-receiver-scope.md`.

## 2026-07-11 — SwiftUI fetches and actions have native lifecycle ownership

Generated Apple clients now execute `fetchUrlSignal` and `fetchAction*` through
main-actor URLSession tasks with structural request metadata, exact status
capabilities, first/last subscriber ownership, generation-safe cancellation,
form snapshots, 2xx-only ordered effects, bounded failures, and preflighted safe
navigation. Same-key refresh preserves identical work, real descriptor changes
restart once, and stale/late callbacks are inert. Independent Rozum review
approved the slice; Swift backend is 30/30 and `tkv2-*` is 12/12. Landed
`5c0b38ad9`, `068e8b62d`, and `03f2f1fcf`; docs `5d6c13955`.

## 2026-07-11 — typed match arms retain their delimiter and body

The self-hosted frontend now bounds pattern type annotations before a
depth-zero guard or arm arrow while preserving nested type delimiters and the
general function-type scanner. Imported typed and guarded binders agree on
VM/direct ASM; the YAML-like parser leaves its false `Unit` global and reaches
a separately tracked call-arity gap. Landed `aef599a80`; full baseline in
`specs/v2.1-native-typed-pattern-boundaries.md`.

## 2026-07-11 — native case objects are stable imported values

Top-level `case object` declarations now survive the self-hosted parser and
module closure as stable nullary constructor values. Imports, aliases,
structural equality, and pattern matching agree on VM/direct ASM. The
calculator parser becomes fully identical; JSON and YAML advance to separately
tracked runtime gaps. Landed `500ba1668`; taxonomy `9411ebf0e`; documented
`90c11cb88`.

## 2026-07-11 — symbolic extensions coexist with primitive bitwise OR

The self-hosted route now selects imported symbolic `|` extensions for ADT
operands while preserving integer bitwise OR in the same assembled program.
Exact VM/ASM coverage includes chained extension calls, primitive `6 | 3`, and
the honest no-extension failure. The mandatory gate also caught and fixed the
K62.20 nested tuple-pattern regression by aligning 3+ patterns with flat
`TupleN` values. Landed `4a336ddec` and `7f6821856`; documented `3de7049a5`.

## 2026-07-11 — extension ownership survives layout and imports

Indented extension bodies now close at dedent/code-fence boundaries, so a
following top-level function keeps its declared arity. AST boundary markers
also carry extension identity across per-module parsing, letting the combined
lowerer resolve imported selected calls without confusing them with collection
helpers. `dsl-ast-builder.ssc` becomes fully identical; strict parity improves
to 23/43/129 and blockers fall to 31. Landed `f7ff66a1f`; taxonomy
`4feb715ea`; verified `7f21b7e4a`.

## 2026-07-11 — SwiftUI observation and keyed rendering are native and transactional

Generated AppleApp sources now include a main-actor per-signal observation
store and recursive SwiftUI renderer. Derived publications deduplicate,
rendered signal seams subscribe exactly, keyed component owners preserve moves
and dispose deletions without tombstones, failed renders roll back Host and
Store state atomically, and all implemented/malformed/deferred inventory is
explicit behavior or source-located Unsupported. The independent Rozum review
approved the ninth diff; landed `70bee065d`, documented `9e813fc98`.

## 2026-07-11 — layout objects retain owned methods and properties

The self-hosted frontend now gives `object Name:` the same virtual-brace scope
as a braced object. First/later methods, parameterless properties, UID
selectors, and sibling references lower under one owner prefix on native VM and
direct ASM, while type-ascription colons remain non-layout syntax. Three parser
DSLs advance from missing `Parser_*` globals to their next separately tracked
constructor-match boundary. Landed `afe902ec8` and `b703a6bf0`; verified and
documented `626791f64`.

## 2026-07-11 — runtime YAML joins the core-free JVM route

A portable native provider now owns `std.yaml` parsing, total accessors, and
deterministic serialization. The self-hosted scanner retains heading-scoped
`yaml`/`yml` fences across roots and imports, so `<SectionId>.yaml` runs exactly
on VM, direct ASM, and standalone build-jvm without a v1 bridge. Strict parity
improves to 22/44/129 and runtime blockers fall from 33 to 32. Landed
`2da4183f5`; documented `1d28aeeca`.

## 2026-07-11 — native zero-argument println preserves the blank line

The self-hosted lowerer now adapts only `println()` with no arguments to the
portable empty-string print primitive. Native VM and direct ASM emit the exact
blank line and continue with later statements; ordinary `println(value)` calls
keep their existing path. Landed `e74241f5e`.

## 2026-07-11 — example-contract debt reaches zero

The standard parity harness now honors JS/Node/Wasm-only plural `backends:`
front matter without hiding `[jvm]` runtime debt. Two browser SQL examples move
to reviewed backend skips, while typed SQL remains assigned to its genuinely
missing standard SQL provider surface. Strict parity is 21/45/129 with no
mismatch or one-sided error; blockers fall from 35 to 33 and the
`example-contract` category is empty. Landed `d4c953b9c`; taxonomy `39cfe268b`.

## 2026-07-11 — reactive signals run on the core-free JVM route

A dedicated portable provider now owns general `Signal`, `computed`, and
`effect` semantics on native VM, direct ASM, and standalone build-jvm JARs.
Dynamic dependencies refresh per run, diamond consumers deduplicate in order,
and self-writes do not recurse while remaining subscribers observe the write.
The self-hosted parser now distinguishes reactive effect blocks from algebraic
effect declarations. Strict parity is 21/47/127 and runtime blockers fall from
36 to 35. Landed `dae51ecab`; evidence `cda669058`; taxonomy `f2ca9b7ea`.

## 2026-07-11 — native Storage runs without the v1 bridge

A dedicated core-free provider now owns ordered ephemeral and deterministic
JSON-backed Storage scopes on native VM, direct ASM, and standalone build-jvm
artifacts. Dynamic selected `.toInt` is preserved through portable method
dispatch, so the public Storage example completes identically. Strict parity is
20/48/127 and runtime blockers fall from 37 to 36. Landed `63ab041a6` and
`55aae9abe`; taxonomy `98b0d0976`.

## 2026-07-11 — Swift AppCore executes the portable NativeUi ABI

Checked `std/ui` CoreIR now generates a SwiftUI-free AppCore `NativeUiHost`,
retained root session, complete ABI-v1 descriptors, transactional evaluation,
source-rich diagnostics, and a dedicated SwiftPM debug CLI. Real Swift probes
exercise post-root mutable/computed/keyed closures and exact descriptor fields;
the independent Rozum review approved the final diff. Landed `9ef73ac81`;
documentation `b56286df1`.

## 2026-07-11 — toolchain spec reflects the completed self-hosted core

The SQL/front-matter and native JSON migration sections now describe structural
`NativeCompilation/4`, pure `json-core.ssc`, and the installed HTTP renderer.
The release baseline records 27 declared dependency JARs, a source-exact
110-file compiler image, zero external parser edges, and conformance 11/11.
Landed `1cc51ca38`.

## 2026-07-11 — standard native crypto no longer needs the v1 bridge

The core-free crypto provider now covers AES-GCM/CBC, RSA-OAEP and signatures,
X.509 extraction, Ed25519, RFC HOTP/TOTP, and prime-field Shamir recovery using
only JDK runtime crypto plus local helpers. Three public examples agree on
VM/direct ASM, strict parity reaches 19 identical, and blockers fall from 40 to
37. Landed `f40b2b6b8`; taxonomy `6f4f0d13e`.

## 2026-07-11 — native output and provider ownership stay exact

Native `List.mkString` now captures its requested separator, NativeUi keeps
`serve` behind its provenance-qualified ABI global without colliding with HTTP,
and the newly identical remote-table example leaves blocker taxonomy. Strict
parity improves to 16 identical / 52 both-fail / 127 skipped and blockers fall
from 41 to 40. Landed `727c806e8`, `23fddc6a2`, and `4cdca959c`.

## 2026-07-11 — portable NativeUi ABI-v1 runtime and lifecycle

The v2 UI plugin now produces the frozen portable signal/view/action/table/root
ABI with graph-safe canonicalization, semantic equality, transactional keyed
component ownership, bounded retention, and deterministic diagnostics. Legacy
component callbacks remain compatible across INT/JS/JVM/Rust, including a real
Cargo gate. Landed `1f3ca3962`; results `fcfd72903`.

## 2026-07-11 — nested constructor patterns preserve ordered fallback

The self-hosted lowerer now carries nested constructor failures to the next
source arm over the original once-evaluated scrutinee while preserving binder
depth. Repeated outer tags, deeper nesting, wildcard fallback, and
`typed-data.ssc` agree on VM/direct ASM; runtime blockers fall from 42 to 41.
Landed `b6b359b60`.

## 2026-07-11 — exact summon resolves named givens natively

The self-hosted parser now retains nested type text for `summon[TC[T]]`, and the
lowerer resolves exact evidence through its existing named-given table. Positive
and missing-evidence fixtures agree on VM/direct ASM; the typeclass example
advances to its independent dictionary-dispatch gap. Landed `a5b97f0dd`.

## 2026-07-11 — self-hosted standard programs publish portable math

The native lowerer now exposes the v2 kernel's existing `math` receiver without
a compatibility/provider class. Constants and mixed numeric methods agree on
VM/direct ASM, `enums.ssc` completes, mixed Scala fences are honestly skipped,
and runtime blockers fall from 44 to 42. Landed `ee8467442`.

## 2026-07-11 — named layout givens lower to portable static members

Indented `given name: TC[T] with` bodies now retain balanced trait/given layout,
emit deterministic member globals, and resolve sibling members after lexical
scope. Multiple givens run identically on VM/direct ASM; the full typeclass
example advances to its separately tracked top-level `summon` boundary with all
release gates green. Landed `2a223d060`; taxonomy corrected in `625f1b628`.

## 2026-07-11 — document-level while loops use portable IrWhile

Top-level while statements now reuse block parsing/lowering, execute in document
order, and mutate the existing global cells without a loader/runtime special
case. Focused nested loops and the full extension example run on VM/direct ASM;
standard parity reaches 13 identical and blockers shrink from 45 to 44. Landed
`d626f00a6`.

## 2026-07-11 — NativeUi calls carry stable lexical sites and provenance

The checked FrontendBridge now records exact imported std/ui extern ownership
before flattening and applies a pure post-Op-ANF CoreIR pass. Eligible calls use
reserved versioned globals carrying explainable definition/path site ids and
source refs; user shadowing is preserved, while bare/eta, arity, and reserved
namespace mistakes fail deterministically. Focused suites and 12/12 toolkit
conformance are green. Landed `0643fde39`; results `c2f2ab513`.

## 2026-07-11 — dynamic length and size are no longer String-only

Proven String/list paths retain their fast lowering, while unknown receivers now
use the existing portable method contract. Dynamic String/List/Array regressions
agree on VM/direct ASM, invalid receivers fail honestly, and the full extension
example advances from false `slen` conversion to its independent `while` gap.
Landed `5a4e7fd45`.

## 2026-07-11 — v2 maps and plugin data dispatch are portable

The v2 JVM runtime now uses insertion-ordered identity `MapV` values matching
Swift `SscMap`, including core factories, methods, display, HTTP/JSON providers,
and the compatibility adapter. Native plugins gained ownership-checked,
snapshot-safe tag-qualified apply/method handlers, removing the need for
collision-prone global `get`/`set` hooks when NativeUi signals become DataV.
Focused suites and 4/4 map/JSON conformance are green. Landed `689969978`;
results recorded in `561dfe818`.

## 2026-07-11 — self-hosted extensions bind and dispatch receivers

Contiguous top-level extension definitions now retain their receiver as a real
function parameter, and registered property/call syntax lowers to that global
function on both VM and direct ASM. `script.ssc` runs fully, the broader
extension example advances to an independent list-length gap, and runtime
blockers shrink from 46 to 45. Landed `0a89b861d`.

## 2026-07-11 — collection companion factories lower portably

The self-hosted resolver now represents collection companions through the
existing method-object contract and flattens only known curried `tabulate` and
`fill` factories. List/array regressions pass on VM/direct ASM; `lang-split.ssc`
advances from a false collection effect to its standard math-provider boundary
with zero parity regression. Landed `69a0b2a51`.

## 2026-07-11 — NativeUi ABI and SwiftUI lifecycle are frozen before code

The reviewed ABI v1 now defines portable roots, nodes, signals, actions, tables,
source diagnostics, keyed structural identity, component ownership,
transactional rollback, per-signal observation, fetch lifecycle, trusted HTML,
and the complete toolkit tag/style inventory without v1 `ForeignV` or `View`.
Existing Unit-returning `emit`/`serve` register exactly one `ui.root`. Apple UI
mode generates a real Xcode application target/project with a separate SwiftPM
AppCore/debug CLI, because a real local SwiftPM/Xcode probe did not establish an
installable iOS app product. Approved in the `scalascript` Rozum room and landed
in `b801f28ae`.

## 2026-07-11 — native function and constructor defaults preserve evaluation

The self-hosted frontend now expands omitted positional defaults through scoped,
single-evaluation lambdas for functions, case classes, and enum cases. The full
default-parameter example runs on VM/direct ASM, fixed-arity failures remain
honest, standard parity reaches 12 identical / 58 both-fail, and blockers shrink
from 47 to 46. Landed `afb11b082`.

## 2026-07-11 — self-hosted core reaches a reproducible stage-2 release gate

The permanent Scala 3 seed now proves single- and multi-file ssc0 compiler
gen1/gen2/gen3 fixpoints and an exact 110-file native frontend image. One
command composes bounded JSON/YAML/Markdown mutation tests, strict dependency
and class-load checks, tools-deleted slim execution, reproducible build-jvm,
the full 195-document frontend/VM/ASM taxonomy, and 11/11 conformance. Final
taxonomy has zero standard parser gaps, mismatches, or one-sided backend errors;
`release.ready=true`. Landed `88bb53fb5`.

## 2026-07-11 — self-hosted JVM frontend lowers exact Decimal and BigInt

`Decimal(...)`, `BigInt(...)`, rounding constants, and dynamically typed
arithmetic now lower to the target-neutral CoreIR/runtime contract. Exact
VM/direct-ASM regressions and the real multi-module money example pass; standard
parity improves to 11 identical / 59 both-fail with zero one-sided failures, and
the blocking runtime taxonomy shrinks from 48 to 47. Landed `e4a9282d7`.

## 2026-07-11 — checked CoreIR has honest Swift developer and Apple routes

`emit-swift` and `run-swift` now generate/run deterministic macOS/iOS SwiftPM
packages through the checked v2 frontend and target-owned AppCore runtime.
Apple build/run flags work in either order, default to v2, and require explicit
`--v1` for the compatibility generator; unfinished NativeUi-dependent iOS and
distribution operations fail once with no fallback. Executable argv and
top-level `val` registration work in native Swift. Backend tests pass 10/10,
CLI/registry 12/12, legacy Apple compatibility 27/27, and the assembled CLI e2e
plus Money/effect conformance are green. Landed `159e45625` and `0174796ef`;
user commands and the staged adapter boundary are documented in `4c25b4326`.

## 2026-07-11 — parser codecs and ASM are isolated by explicit owner

The closed standard runtime now has 27 dependency JARs and zero strict
parser/codec edges: the optional SQL wire/ujson/upickle/upack/geny family is no
longer staged, while native SQL remains green. Pure JSON/YAML/Markdown have no
host intrinsic/regex path; native VM does not load external ASM and direct
bytecode loads it only when selected. Slim, deterministic build-jvm, provider,
dependency, and 10/10 conformance gates pass. Landed `6f393beea`.

## 2026-07-11 — native module loader accepts multiple imports per line

Standalone Markdown import lines now resolve every whitespace-separated
`[names](path.ssc)` link in source order while lines with prose tails remain
non-imports. A three-file assembled fixture prints `42` identically on VM/ASM;
`multi-link-imports.ssc` now resolves both modules and reaches its independent
Decimal boundary. All corpus/parity/taxonomy gates and conformance 10/10 pass.
Landed `836ceee03`, taxonomy transfer `64fcab537`.

## 2026-07-11 — v2 Swift AppCore executes checked Money and effects

The first-class CoreIR-to-Swift backend now closes its domain-runtime gate:
generated programs carry checked constructor field layouts, self and mutual
tail recursion stay on the trampoline, and dynamic Decimal/BigInt, collections,
methods, and algebraic-effect continuations execute in target-owned Swift. The
unchanged `money-portable-v2.ssc` and `effect-transitive-handler.ssc` fixtures
compile through the checked frontend and real SwiftPM executables with exact VM
output. Swift backend tests pass 8/8; affected Money and effect conformance pass
1/1 and 4/4. Landed `f20b47b35`; detailed gates are recorded in
`specs/v2-swift-swiftui-native.md`.

## 2026-07-11 — standard Markdown parsing is self-hosted and structural

The pure ScalaScript Markdown Profile now covers headings/scopes, pure-link
imports, prose/interpolation source, fences, lists, images, tables, and metadata
directives on native VM and direct ASM. Every standard root crosses the frozen
frontend ABI as a validated `MarkdownDocument`; malformed fences fail before
plugins load, the slim launcher carries the complete ABI, and CommonMark/
Flexmark are absent from standard JARs and runtime class-load. Landed
`54e26493c` and `36d5ef3b6`.

## 2026-07-11 — runtime readiness failures have a fail-closed taxonomy

All 60 standard VM/direct-ASM `both-fail` rows now have a reviewed category,
blocker decision, owner, and reason. The corrected baseline is 20
language-runtime, 25 standard-provider, 6 optional-provider, 3
example-contract, and 6 tools/backend rows: 48 blockers total. Unknown,
duplicate, stale, invalid-blocker, and category-growing entries fail the gate;
fresh conformance is 10/10. Landed `df84e8acd`, ownership corrected in
`6b736d078`.

## 2026-07-11 — standard native frontend reaches zero parser gaps

Parenthesized `if` conditions now continue through infix operators without
turning legacy braced branches into block arguments, and match patterns accept
tuple heads in `head :: tail`. Focused VM/direct-ASM output is identical;
`dsl-mini-language.ssc` and the OIDC regression are sentinel-clear/checker-OK.
All 68 remaining sentinels are explicit server/backend/tools/nondeterministic
rows: standard gaps are zero, parity remains 10/60/125 with no mismatch or
one-sided failure, and fresh conformance is 9/9. Landed `063c64dcd`.

## 2026-07-10 — native frontend parses assignment expressions

The self-hosted expression parser now recognizes a single `=` after a bare
variable and reuses the existing assignment AST/lowering, without changing
named arguments or equality. A real fixture mutates locals inside `if ... then`
and `for ... do` identically on native VM/direct ASM; `extensions.ssc` is
sentinel-clear/checker-OK. The corpus has 69 sentinels and 1 standard gap;
standard parity remains 10/60/125 with zero mismatch/one-sided, and fresh
conformance is 9/9. Landed `6bdfb2ff4` and `1f50dcaa8`.

## 2026-07-10 — native matches support constructor pattern alternatives

The self-hosted parser/lowerer now expands `case A(_) | B(_) => body` into
ordered constructor alternatives over one evaluated scrutinee. A real fixture
prints `hit/hit/miss` identically on native VM/direct ASM, and
`dsl-yaml-like.ssc` is sentinel-clear/checker-OK. The corpus has 70 sentinels
and 2 standard gaps; standard parity remains 10/60/125 with zero
mismatch/one-sided, and fresh conformance is 9/9. Landed `7aee8394e`.

## 2026-07-10 — native frontend recognizes symbolic extension operators

The self-hosted lexer/parser now consumes `~`, `~>`, and `<~` with
first-character precedence and accepts them as symbolic definition names.
Unknown symbolic infix lowering calls an explicit global instead of silently
falling back to integer addition. Three parser DSL examples lose their
sentinels; SQL recovery runs, while calc/JSON reach honest missing-regex runtime
boundaries. The corpus has 71 sentinels and 3 standard gaps; standard parity is
10/60/125 with zero mismatch/one-sided, and fresh conformance is 9/9. Landed
`23fca32a0`.

## 2026-07-10 — sentinel readiness no longer depends on backend exit status

The release taxonomy now keeps the frontend `_err` sentinel authoritative when
VM and direct ASM happen to exit zero identically because the bad node is
uncalled or otherwise unobserved. Synthetic standard/tools regressions and the
real 74-row report pass; measured limits are 6 standard, 26 server, 36 backend,
5 tools/backend, and 1 nondeterministic. Landed `07c1d9b55`.

## 2026-07-10 — native frontend supports portable list append

The self-hosted lexer, checker, and lowerer now implement `List[A] :+ A` at the
collection-concatenation precedence through portable `__arith__`, with no Scala
collection or JVM-only dependency. A real fixture prints `1,2,3,4` identically
on native VM/direct ASM; `dsl-ast-builder.ssc` is sentinel-clear/checker-OK.
The corpus has 74 sentinels and 6 standard gaps; standard parity remains
10 identical / 60 both-fail / 125 skipped with zero mismatch/one-sided, and
fresh conformance is 9/9. Landed `c018ad6a1`.

## 2026-07-10 — native frontend accepts extension declaration headers

The self-hosted parser now consumes `extension [T](receiver: Type)` as a
declaration header and resumes at its following definitions without leaking
type brackets or annotations into expression parsing. This slice deliberately
does not claim receiver binding or extension dispatch semantics. A real
uncalled declaration fixture is byte-identical on native VM/direct ASM;
`script.ssc` is sentinel-clear/checker-OK and reaches its honest missing
`.stars` dispatch. The corpus has 75 sentinels and 7 standard gaps; parity is
10 identical, 60 honest both-fail, 125 skipped, and zero mismatch/one-sided.
Native-entry, taxonomy, and fresh 9/9 conformance pass. Landed `3ddbe8d1d`.

## 2026-07-10 — flat constructor guards fall through on one scrutinee

The self-hosted guard lowerer now scopes flat constructor fields in guard/body
and, on false, continues later arms against the same shifted scrutinee without
leaking failed-arm binder names. A guarded `Some` fixture is byte-identical on
native VM/direct ASM; `direct-syntax-demo.ssc` is sentinel-clear/checker-OK and
reaches its explicit `direct` runtime gap. The corpus has 76 sentinels and 8
standard gaps; native-entry, taxonomy, zero-difference parity, and fresh 9/9
conformance pass. Landed `e87a3aab2`.

## 2026-07-10 — binder match guards preserve ordered fall-through

The self-hosted parser/lowerer now represents `case x if cond =>` and guarded
wildcards explicitly, evaluates the scrutinee once, scopes the binder in guard
and body, and falls through to later literal/constructor/default arms in source
order. A classification fixture is byte-identical on native VM/direct ASM;
`data-types.ssc` is sentinel-clear/checker-OK. The corpus has 77 sentinels and 9
standard gaps; native-entry, taxonomy, zero-difference parity, and fresh 9/9
conformance pass. Landed `91a955171`.

## 2026-07-10 — layout closes multiline lambdas before call delimiters

The self-hosted offside pass now tracks parentheses and brackets alongside
braces, closing only virtual layout blocks nested inside a delimiter before its
`)`/`]`. A multiline tuple-lambda fixture prints `11` identically on native VM
and direct ASM; `std/money.allocate` and `content-linked-namespaces.ssc` are
sentinel-clear/checker-OK. Six corpus rows lose sentinels: 78 remain, with 10
standard gaps and 26 server rows; checker is 194/0 and runtime has 27 successes.
Native-entry, taxonomy, zero-difference parity, and fresh 9/9 conformance pass.
Landed `6440860f7`.

## 2026-07-10 — native frontend returns structural CoreIR and manifest config

The self-hosted tower now returns `IrProg`, parsed Frontmatter YAML values, and
source identities through a frozen `ssc.Value` ABI. The Scala 3 seed only
validates and maps those structures: it no longer reparses frontend CoreIR text
or YAML. `NativeFrontmatter` is deleted, strict standard execution does not load
`SimpleYaml`/`Parser`/`ssc.Reader`, and malformed or conflicting database config
fails before plugin installation. VM/ASM SQL and deterministic `build-jvm`
remain green. Landed `20d9db6db`.

## 2026-07-10 — enum parsing stops before a following case class

The self-hosted layout enum scanner no longer consumes a following top-level
`case class` as an enum case named `class`. A real enum→`Box[A]` fixture prints
`Red` and `Box(7)` identically on native VM/direct ASM. `typed-data.ssc` is now
sentinel-clear/checker-OK and reaches its honest default-argument runtime gap.
The corpus has 84 sentinels and 11 standard parser gaps; native-entry, taxonomy,
zero-difference parity, and fresh 9/9 conformance pass. Landed `ea805bf22`.

## 2026-07-10 — Frontmatter YAML core is self-hosted

`runtime/std/yaml-core.ssc` now parses the bounded Frontmatter YAML Profile in
pure ScalaScript: ordered block/flow mappings and sequences, manifest/database
nesting, scalar and block-string forms, comments, exact numeric text, and stable
source-located rejection of duplicate keys and unsupported YAML graph features.
The native VM and direct ASM outputs are byte-identical, focused conformance is
green, and the standard dependency gate reports no pure-core violation. Landed
`7a06d4a55`; the structural frontend cutover remains the next slice.

## 2026-07-10 — native frontend preserves raw triple-quoted strings

The self-hosted lexer now emits `"""..."""` as one raw multiline string token,
preserving newlines, quotes, and backslashes; ordinary strings and `s`-prefixed
interpolation retain their existing paths. A real fixture is byte-identical on
native VM/direct ASM, and `graph-rdf4j-http-storage.ssc` is now
sentinel-clear/checker/runtime OK. Seven corpus rows lose sentinels: 85 remain,
the checker is 194/0, and taxonomy shrinks to 12 standard, 31 server, 36 backend,
5 tools/backend, and 1 nondeterministic. Native-entry, taxonomy, parity, and
fresh 9/9 affected conformance pass. Landed `7a1802261`.

## 2026-07-10 — compiler-backed x402 client is explicit tools-tier input

`x402-client.ssc` no longer inflates the standard parser queue: its regular
`scalascript` fence imports `scala.concurrent`, sttp, and compiler-backed URI
interpolation, all forbidden on the compiler-free standard path. A reviewed,
sentinel-bound override classifies it as tools/backend and will fail stale after
a future portable rewrite. At that slice the taxonomy was 13 standard gaps, 35 server, 38
backend, 5 tools/backend, and 1 nondeterministic row. Landed `230645b3a`.

## 2026-07-10 — native decimal literals accept separators before `L`

The self-hosted lexer now treats `_` between decimal digits as part of one
numeric token, removes separators before lowering, and then erases the existing
`L`/`l` suffix. A real `100_00L` fixture prints `10000` identically on native VM
and direct ASM. `international-bank-rails.ssc` is sentinel-clear/checker-OK and
now reaches its honest missing-Swift-provider boundary; the full corpus drops
from 93 to 92 sentinels and from 15 to 14 standard parser gaps. Native-entry,
taxonomy smoke, parity, and affected conformance gates pass. Landed
`4bcf6a976`.

## 2026-07-10 — every native parser sentinel has a release category

The release join now classifies all 76 `_err` rows from the native-front and
standard parity reports: 8 standard deterministic parser gaps, 26
server/integration documents, 36 backend-specific documents, 5 explicit
compiler/target tools surfaces, and 1 nondeterministic external-I/O row.
Reviewed overrides and category ceilings reject unknown growth or stale
exceptions. Server detection now recognizes `serve {}` and named `serveX`
entrypoints before execution; backend-only fenced documents are source-classified
without overrides. VM/ASM parity is 10 identical, 60 honest both-fail, 125
skipped, and 0 mismatch/one-sided (`aa9b30f28`, refined through `e87a3aab2`).

## 2026-07-10 — v2 gains portable exact Decimal/Money and algebraic effects

The v2 runtime now exposes scale-preserving, numerically comparable `DecimalV`
through one exact `dec.*` vocabulary and evaluates `Pure`/`Op` computations with
reusable multi-shot continuations independent of JVM handler stacks. JSON, SQL,
HTTP, UI display, and plugin bridges preserve the portable value; dynamic
BigInt arithmetic now carries real `std/money.allocate` through the assembled
v2 lane. The slice passed 94 focused unit tests and 6/6 affected conformance
cases. Landed `ff3a52eba`; detailed results are in
`specs/v2-swift-swiftui-native.md`.

## 2026-07-10 — swiftui-legacy-real-harness: a real `.ssc` module now builds a native macOS/iOS package

Compatibility-only sub-slice of `v2-swift-swiftui-native` (assigned in the
`scalascript` Rozum room to the busi-side `claude-code` agent). `bin/ssc build
--target macos <real .ssc file>` compiled through `JvmGen` for the first time
ever with a genuinely parsed module rather than a hand-built Scala `View`
literal, surfacing six independent, previously-invisible bugs across the
hoisted `ui.primitives` import list (a stale `Signal` entry, six missing real
names), the `frontendName == "swiftui"` preamble branch (missing the
`text(String)` shadow-fix the non-swiftui branch already had), a duplicate
`dataTableView` declaration, two `std/ui/lower.ssc` JVM-codegen-only type
gaps (its intentional idempotent-passthrough catch-all, and a `forKeyedView`
callback type-inference conflict), and an invalid-Swift double-brace bug in
`SwiftUIEmitter`'s `ShowSignal` case. `examples/frontend/ios-hello/ios-hello.ssc`
was rewritten off its stale aspirational DSL onto the real `std/ui` API and now
builds and links a real executable. New regression `SwiftUiRealFixtureBuildTest`
(gated on `assume(swiftAvailable)`) drives that fixture through
`buildSwiftUIPackage(..., runSwiftBuild = true)` end to end — mirrors
`RustGenCargoSmokeTest`'s "actually run the toolchain" gate. 118
`frontendSwiftUI` + 26 `SwiftUIBuildCliTest` + 19 `std/ui`-touching conformance
fixtures all still green. Full detail in `BUGS.md`'s `v2-swift-swiftui-native`
entry. Landed `8d21fb298`..`d15d1e1df`. The v2-native Swift backend itself
remains open under that claim.

## 2026-07-10 — native frontend corpus has no host errors or timeouts

Multiline lambda bodies now open after `=>`, tuple patterns use the same
right-nested `Pair` representation as tuple expressions, and the RDF full-stack
example imports the current `std/ui/data.ssc` table API. The prior
`dsl-mini-language.ssc` `Pair/2` matcher crash and stale-table
`NoSuchFileException` are gone; remaining parser sentinels identify their input
file instead of leaking a host stack. The full 195-file frontend result is 194
successes, 0 host errors/timeouts, and 1 non-code document; standard VM/ASM
remains 11 identical, 0 mismatch/one-sided, 96 honest both-fail, 88 skipped.

## 2026-07-10 — standard VM and direct ASM have no one-sided corpus failures

The self-hosted frontend now preserves multiline function-typed parameters,
direct ASM enforces the VM's closure arity, and the core-free standard UI
provider constructs declarative fetch signals/actions without compatibility
fallback. Together with the braced-interpolation and local-recursion fixes,
all three prior one-sided rows (`index.ssc`, `recursion.ssc`, and
`ui-fetch-json.ssc`) are byte-identical. The 195-file standard sweep is now 11
identical, 0 mismatch, 0 one-sided, 96 honest both-fail, and 88 explicitly
skipped; at that slice the native frontend had 192 successes, 92 sentinels, and
2 tracked host errors. The reproducible artifact baseline was 24,777,543 bytes
with SHA-256
`ee47e75d07ea980d1075c55397e2a07b543dafe55014d03a49b30d5549c32392`.

## 2026-07-10 — direct ASM trampolines local self and mutual recursion

Compiled `LetRec` groups now retain generated peer identity and return tail
calls through a bounce whose frame preserves captured values plus the tied
closure group. The full `recursion.ssc` example produces all 13 rows identically
on VM, in-memory ASM, and deterministic `build-jvm` at `-Xss256k`. The current
compiler-free artifact baseline at that slice was 24,769,060 bytes with SHA-256
`c6791b629f03b1966a039eed3f482a3cb4ba8ba433abb7b9f8cbe1dfe416bfde`.

## 2026-07-10 — public standard JSON now uses the self-hosted codec

`std.json` strict/tolerant parsing, total navigation, exact decimal handling,
string builders, legacy lookup, arbitrary-value stringify, and HTTP
`Response.json` now cross only a small portable-ADT bridge and execute the
ScalaScript scanner/renderer. The v2 JSON provider has no ujson/upickle edge and
JSON/HTTP pass on VM, direct ASM, and the slim launcher after ujson, upickle,
upack, and geny are physically deleted. The remaining external JSON-family JARs
are assigned solely to the SQL `wire-core` plugin family.

## 2026-07-10 — native interpolation parses complete braced expressions

The self-hosted frontend now balances `${...}`, nested braces, and quoted
string literals before parsing the complete inner expression. Calls such as
`${items.mkString(", ")}` no longer become malformed string fragments:
`examples/index.ssc` and a focused fixture produce correct byte-identical output
on the native VM and direct ASM while simple `$name` interpolation stays green.

## 2026-07-10 — standard runtime passes without Java compiler modules

CI now derives a 13-module runtime set from every standard JAR and runs the
native VM, direct ASM, all representative providers, and a generated H2 SQL JAR
through `java --limit-modules`; `java.compiler` and `jdk.compiler` are both
unresolvable. The audit found and removed eight optional H2 `SourceCompiler*`
classes from only the standard-tier driver and strengthened the slim gate so
ServiceLoader/JDBC dependencies can no longer evade static scanning.

## 2026-07-10 — canonical JSON parsing is self-hosted in ScalaScript

The new pure `std/json-core.ssc` character scanner owns strict and tolerant
JSON parsing, portable values, exact decimal text, total navigation, Unicode
and surrogate validation, and deterministic compact rendering without an
external codec, host regex, or `extern`. Its focused corpus is byte-identical on
the native VM and direct ASM. The closed dependency gate now also classifies all
32 TI-7 standard JARs, including configuration-selected plugin dependencies,
while keeping unknown additions a hard failure and pinning the three remaining
parser/codec migration surfaces to named JSON/SQL plugins.

## 2026-07-10 — ScalaScript 2.1 standard tier survives physical tools deletion

The release gate now copies an installed distribution, removes its full CLI,
compatibility JAR/plugin/compiler trees, legacy frontend, `ssc`, and
`ssc-tools`, and runs only the surviving `ssc-standard` files. VM, direct ASM,
imports/argv, FS/OS, JSON, HTTP, SQL, UI, State, and deterministic `build-jvm`
all pass with compiler commands hidden. The current baseline is 33 JARs, 7,052
classes, 31,478,441 bytes, and zero compiler/Scalameta/v1 forbidden references.

## 2026-07-10 — compatibility tooling gets an explicit tier launcher

Full staging and self-install now create `ssc-tools` over the compatibility
runtime/compiler layout. The slim launcher delegates only explicit `run --v1`
or `--compat-frontend` requests when that tier exists; other unsupported
commands fail with a bounded tools-tier/remedy diagnostic instead of silently
discovering compatibility classes.

## 2026-07-10 — ScalaScript 2.1 gains a physically slim standard launcher

`bin/ssc-standard` now starts from a class-filtered native-only entry JAR, 32
explicitly allowlisted runtime/provider dependencies, and its own staged
self-hosted frontend tower. Its JAR names and entry references contain no
Scalameta, v1 AST/frontend/interpreter, compatibility bridge, or compiler
families. The compatibility `bin/ssc` remains unchanged until the TI-8 default
cutover so physical separation lands without regressing open frontend parity.

## 2026-07-10 — managed UI GETs retain last-good data offline

Generated custom SPAs now consume transport and response-body rejection from
`fetchUrlSignal`/`fetchUrlSignalTo`, preserve the current signal value, and keep
their refresh subscriptions alive without leaking unhandled promises. A real
runtime regression proves offline failure followed by successful reconnect;
the assembled SPA and focused INT/JS conformance gates are green.

## 2026-07-10 — TI-6 direct ASM artifact pipeline is release-gated

CI now rebuilds native ASM applications from unrelated clean directories,
compares the complete JAR bytes, runs hello/import/argv/crypto/SQL with compiler
commands hidden, and rejects compiler, Scalameta, compatibility bridge, and v1
frontend/runtime entries or references. The stable TSV baseline records a
26,300,902-byte artifact with SHA-256 `1d078c3ffe330eae72a809f98794333c123d715bbf19012fbdc4f0c686715173`
and module graphs without `java.compiler`/`jdk.compiler`.

## 2026-07-10 — direct ASM artifacts carry native `.ssc` diagnostics

Generated entry/definition/helper methods now carry JVM `SourceFile`, line
tables, and a multi-file JSR-45 `SSC` source map. The Scalameta-free source
closure resolver hashes and maps transitive imports as well as explicit roots,
never embeds checkout paths, and a real runtime failure reports its original
`.ssc` line. In-memory direct ASM now shares the artifact `install()` global
initialization contract instead of running a VM compiler prepass.

## 2026-07-10 — direct ASM artifacts link imports and runtime configuration

`ssc build-jvm` now packages the resolved native module closure and serializes
database configuration into deterministic artifact metadata. Configured SQL
JARs reconstruct the native provider environment before execution and run H2
DDL/DML/query without the ScalaScript installation; their dependency graph
retains the JDBC runtime while excluding H2's optional Java source compiler and
therefore has no `java.compiler` edge.

## 2026-07-10 — direct ASM now builds deterministic executable JVM artifacts

`ssc build-jvm` runs the self-hosted frontend and checker, emits an executable
`ssc.gen.Entry` class directly through ASM, and merges an explicit core-free
runtime/provider allowlist into a self-contained JAR. Fixed ZIP metadata,
lexical entry/service ordering, embedded source hashes, and conflict checks make
repeated builds byte-identical; argv and crypto run through `java -jar` without
Scala CLI, scalac, or javac.

## 2026-07-10 — native immutable values now initialize in document order

Top-level immutable values and tuple bindings are cell-backed and initialized
once from the native entry, so plugin calls cannot run before preceding effects.
Scala-style newline inference also keeps a parenthesized statement after a block
initializer inside its local scope. Faithful SQL and nested-State fixtures now
match exactly on the v2 VM and direct ASM backends.

## 2026-07-10 — representative native plugin boundary completed with State

`NativePluginContext.withEffect` keeps dynamic handler push/pop and exception
cleanup inside the v2 host. The core-free State provider implements nested
`runState` plus get/set/modify callback semantics; together with native
JSON/HTTP/SQL/UI it closes every representative TI-5 family on VM and direct
ASM. Full advanced provider parity remains explicitly queued.

## 2026-07-10 — static UI moved onto core-free v2 native values

The native provider graph now owns mutable/derived signals, callback updates,
event descriptors, and representative text/signal/show/fragment/element views.
Static `emit` writes deterministic escaped UTF-8 HTML; provider, assembled
VM/direct-ASM, ServiceLoader, static dependency, and runtime class-load gates
pass without `frontendCore`, the compatibility bridge, or Scalameta.

## 2026-07-10 — named JDBC moved onto the v2 native provider boundary

Explicit-root `databases:` front-matter now reaches core-free providers through
typed native runtime configuration. A standalone SQL provider reuses
`backendSqlRuntime` for lazy named connections, parameterized H2 DDL/DML, and
map-row `Db.query` reads; VM/direct-ASM, ServiceLoader, static/runtime classpath,
and no-Scala-CLI gates pass without the compatibility bridge or Scalameta.

## 2026-07-10 — native v2 callbacks now drive a JDK HTTP server host

`NativePluginContext.invoke` centralizes trampoline-safe provider callbacks.
The HTTP provider uses it for exact method/path routes on JDK `HttpServer`, v2
`Request` construction, handler closure invocation, `Response` output, and
`serve`/`serveAsync`/`stop`. A self-calling `.ssc` server passes on VM and ASM;
advanced server hooks remain bounded errors rather than bridge fallbacks.

## 2026-07-10 — native HTTP client and Response values moved off v1

The standard native provider graph now owns JDK outbound HTTP, streaming line
callbacks, base URL/timeout/retry state, `Response` builders, shared JSON
serialization, and cache helpers. Loopback tests and assembled VM/ASM smokes are
green; server calls fail with a bounded server-host-SPI diagnostic instead of
loading the compatibility bridge or succeeding silently.

## 2026-07-10 — typed JSON moved onto the v2 native provider boundary

The native VM/direct-ASM path now parses, navigates, looks up, and serializes
JSON without the v1 interpreter, plugin API, bridge, or Scalameta. Tolerant
`JsonValue` accessors, strict parsing, exact string-decimals, compact raw values,
and `.ssc` structured builders share one core-free value model and pass assembled
ServiceLoader/classpath/runtime gates.

## 2026-07-10 — native FS/OS providers removed their v1 runtime edge

The complete JVM `std.fs` surface and the environment/path/temp portion of
`std.os` now install through core-free `ssc.plugin.NativePlugin` providers.
Unit tests cover text, bytes, directories, file management, environment, paths,
and temporary files; assembled VM/direct-ASM round-trips and dependency gates
pass without loading the compatibility bridge or Scalameta.

## 2026-07-10 — core-free v2 native plugin boundary established

`ssc run --native` now loads deterministic `ssc.plugin.NativePlugin` providers
instead of the v1 `PluginBridge`. Process globals and nine crypto helpers have
core-free providers; duplicate ownership is rejected, VM/ASM crypto/argv smokes
pass, and static plus runtime gates show no native bridge/Scalameta class edge.

## 2026-07-10 — native unary operators bind postfix calls correctly

The self-hosted frontend now parses `!f()`, `-f()`, and `~f()` as unary
operations over the call result instead of calling the unary result afterward.
VM/ASM assembled regressions cover all three shapes; the last sentinel-clear
native-checker corpus rejection is removed.

## 2026-07-10 — unresolved v2 runtime results now fail on VM and ASM

All public v2 runners now share final-result validation. Missing-method `Stub`
sentinels and dotted unhandled runtime-effect `Op` values produce a nonzero
diagnostic instead of printable success; the historical bytecode-only
`Wallets.metaMask` false success is covered by an assembled regression.

## 2026-07-10 — native checker numeric/string false positives removed

The self-hosted checker now infers Float literals and mixed numeric arithmetic,
String repetition, and substitution-aware String concatenation. A staged
assembled smoke covers valid combinations plus negative numeric, repetition,
boolean, and condition cases. The direct 195-file checker result improved from
178 to 188 OK; all six remaining rejects are traced to frontend parser gaps.

## 2026-07-10 — staged compiler-free native frontend entry landed

The assembled CLI now exposes `ssc run --native` and direct-ASM
`ssc run --native --bytecode` without spawning Scala CLI or a compiler. The
installed native-front tower and std sources support normalized relative/std
imports, multiple roots, argv, plugin intrinsics, bounded sentinel failures, and
complete extracted-plugin cleanup; `--compat-frontend` keeps the Scalameta bridge
explicit until frontend/checker parity permits the default-route cutover.

## 2026-07-10 — portable toolchain-independence corpus gates landed

VM/ASM parity and the scalameta-free native frontend now have repository-relative,
portable-timeout sweep commands that write one TSV row per corpus input. The
native report separates frontend errors, `_err` sentinels, checker outcomes, and
plugin-runtime execution, preventing partial IR from counting as success. The
195-file baseline is now explicit: bridge VM/ASM has no unexplained deterministic
one-sided/mismatch row after target/server/nondeterministic classification, while
the import-aware native route has 78 frontend OK and only 7 runtime OK rows.

## 2026-07-10 — packaged plugin temp trees cleaned at CLI exit

`SscpkgLoader` no longer leaves one non-empty `sscpkg-*` extraction tree per
plugin and CLI invocation. Extracted intrinsic JARs and source descendants are
registered parent-first so JVM shutdown deletes them in safe reverse order. A
real assembled-CLI smoke now runs with an isolated `java.io.tmpdir` and asserts
that no packaged-plugin tree survives process exit.

## 2026-07-10 — ScalaScript 2.1 toolchain-independence contract frozen

The release-wide spec now separates the standard native-front/CoreIR/VM/ASM
runtime from optional compiler and compatibility tools. It requires the native
checker before cutover, forbids transparent Scalameta/Scala CLI/scalac/javac
fallbacks, gives direct ASM artifacts the unambiguous `build-jvm` command, and
defines portable corpus, classpath, slim-filesystem, reproducibility, and
JRE-module gates. Current frontend, plugin-boundary, packaging, and artifact gaps
are recorded as measured baselines rather than implied backend readiness.

## 2026-07-10 — native scalameta-free front loads `[names](path.ssc)` std modules

The native front (`ssc1-run`) used to treat markdown-link imports as a parse-only
no-op, so ssc-defined std helpers (`jObj`/`jStr`/`lower`/`staticDataTable`) stayed
unbound — the corpus ceiling for the scalameta-free path. `ssc1-run` now resolves
those links (DFS + load-once): scans the raw source for `[names](path.ssc)`,
resolves `std/` against `SSC_STD` (else in-tree `v1/runtime`), fence-extracts each
target `.ssc`, keeps its definitions, and prepends them. Complements the K62 parser
axis (the parser file is untouched). Verified end-to-end (`std/json.ssc` `jObj`/
`jStr`/`jsonEscape` now defined); conformance 640/640; import-free files unchanged.

## 2026-07-10 — v2 production readiness bounded audit green

After the layout/YAML fixes, a bounded v2 production audit found no new
actionable blocker. Gates passed: `installBin`, focused `v2-*,indent-*`
conformance 8/8, full `v2/conformance/check.sh`, four v2 e2e smoke scripts
(`dsl-yaml-like`, `indent-layout`, `route-params`, `req-type-collision`), and a
representative source-backend subset (`fact`, `bool`, `mutual-recursion`, `tco`
including `mutual-tco`, and `letrec`) across JVM/JS/Rust.

## 2026-07-10 — v2 indent layout demos active in conformance

The two indentation parser demos now run on an opt-in V2 conformance lane
instead of being skipped without expected output. Their parser expressions
explicitly group `~` sequences before tuple-field mapping, config blank-line
skipping no longer relies on a nullable regex, and `indent-block-statements`
also covers `while` and `for`. Gates: `installBin`, direct v2 runs, new
`indent-layout-v2-smoke`, indent conformance 2/2 with `PASS [V2 ]`, parser
conformance 3/3, and `git diff --check`.

## 2026-07-10 — v2 layout indentation parsing restored for YAML-style DSLs

`std/parsing/layout.ssc` now preserves explicit `withIndent(n)` on v2, consumes
same/deeper indentation before parsing block items, and skips blank layout lines.
This restores `examples/dsl-yaml-like.ssc` on the v2 path and adds an e2e smoke
that checks nested YAML-style fields such as `server.host` and
`database.pool.max`. Gates: `installBin`, parser conformance 3/3, the new
`dsl-yaml-like-v2-smoke` script, and `git diff --check`.

## 2026-07-10 — v2 helper shell pipes made byte-preserving

Closed the macOS backend helper gotcha. `v2/backend/check.sh` was already safe
because it writes generated backend sources via direct redirects; remaining
helper paths now use `printf '%s\n'` instead of `echo "$..."` for source/IR
text in `v2/scripts/bench.sh` and `v2/ssc1`. The same pass fixed v2 wrappers'
stale Scala CLI stack option spelling by switching `v2/ssc`, `v2/ssc0c`, and
`v2/ssc1` to `--java-opt=-Xss512m`. Gates: backend source smoke (`fact` x
JVM/JS/Rust), bench and wrapper smokes, `installBin`, `litdoc` conformance 1/1,
and `git diff --check`.

## 2026-07-10 — v2 production VM/JIT route-policy gate closed

Closed the stale open `v2-vm-production-jit-gate` backlog row as a route-policy
gate. The documented production policy keeps VM as the global default, uses
bytecode/JVM source for recursion-heavy deployments, and uses VM/Rust source
for scalar-loop and pattern-heavy workloads. `v2-auto-route-selector` remains a
can-wait follow-up, not a production blocker while explicit route flags exist.
Gates: `installBin`, `scripts/bench v2-backends pattern-match-heavy`
(`v2=0.266ms`, `v2-jvm=10.4ms`, `v2-rust=0.293ms`), `list-companion`
conformance 1/1, and `git diff --check`.

## 2026-07-10 — toolkit v2 dev loop verified as already available

Closed the open P2 `tkv2-dev-loop` backlog row after verifying the existing
CLI path: `ssc serve file.ssc` aliases to `watch`, server sources hot-reload
routes without rebinding the port, and `watch-bench` exercises the same reload
path. Gates: CLI focused tests 11/11 (watch-cycle p50 5ms / max 8ms),
`installBin`, real `watch-bench` server-mode smoke on `examples/rest-api.ssc`
(warm 433ms, hot max 42ms), and `tkv2-*` conformance 11/11.

## 2026-07-10 — toolkit v2 tri-state fetched-view helper landed

`std/ui/state.ssc` now provides pure `.ssc` `LoadState`, `stateName`,
`errorText`, `triState`, and `triStateText` helpers for loading/error/empty/ready
views over existing signals. No new backend intrinsic or `TkNode` was needed.
Added a conformance case, an example, and README/user-guide docs. Gates:
`installBin`, `tkv2-tri-state` conformance 1/1, example smoke, and
`git diff --check`.

## 2026-07-10 — SSR ForSignal fallback attr serialization covered

Added a focused toolkit SSR regression for `View.ForSignal(...,
itemTemplate = None)` fallback rendering. The source bug had already been fixed
by the raw-html SSR patch (`bb5342f08`); the new test locks the behavior by
asserting repeated fallback elements serialize each static attr once per item.
Gates: `SsrTest` 33/33, `installBin`, affected conformance `tkv2-raw-html` 1/1,
and `git diff --check`.

## 2026-07-10 — toolkit v2 rawHtml escape hatch landed

`std/ui/reactive.ssc` now exports `rawHtml(html: String): TkNode` for trusted
raw markup, while `rawText` remains escaped text. The custom SPA runtime and
SSR stringifier hide the `data-ssc-raw-html` sentinel and render its value as
children. The emitted-SPA path also now includes the Signals/UI runtime for
static `std/ui` imports that do not call `signal(...)`. Gates:
frontendCustom/frontendToolkit compile, backendJs/CLI compile, `SsrTest` 32/32,
affected conformance 11/11, emitted raw-html demo jsdom smoke, and
`git diff --check`.

## 2026-07-10 — custom emitted SPA i18n live-switch parity restored

The JS backend now lets collision-renamed imports/user bindings win before
intrinsic dispatch. This fixes `examples/std-ui/i18n-demo.ssc` emitted through
`emit-spa --frontend custom`, where `std.ui.primitives.serve` was imported as
`serve__ssc` but the top-level call was emitted as bare `serve(...)` and crashed
before mount. Added a jsdom regression for EN/RU/UK/PL/EN live switching. Gates:
standalone patched-`JsGen` compile + jsdom harness, CLI-shaped emitted HTML
jsdom smoke, affected conformance 10/10, and `git diff --check`.

## 2026-07-10 — v2 production route policy recorded for four representative rows

Reran the bounded route gate after the VM `pattern-match-heavy` fix. The global
default stays VM because no single non-VM route improves all rows: bytecode/JVM
source are the production recursion routes, while VM/Rust source cover
scalar-loop and pattern-heavy rows. No code changed. Gates: `installBin`,
`scripts/bench v2-bytecode` and `scripts/bench v2-backends` across all four
rows, affected conformance 1/1, and `git diff --check`.

## 2026-07-10 — v2 VM pattern-match-heavy reaches Rust route speed

The v2 VM now recognizes the strict static-list Float `foreach` accumulation
shape used by `bench/corpus/pattern-match-heavy.ssc`. It precomputes pure
per-element Float additions once and keeps impure global functions on the
generic fallback. Public `scripts/bench v2-backends pattern-match-heavy` now
reports `v2=0.266ms`, `v2-jvm=10.9ms`, `v2-rust=0.265ms`; `scripts/bench
v2-bytecode pattern-match-heavy` reports `v2=0.266ms`, `v2-bytecode=19.3ms`.
Gates: focused bridge tests, `installBin`, `./v2/conformance/check.sh`,
affected conformance 5/5, final bench rows, and `git diff --check`.

## 2026-07-09 — v2 production route sweep measured JVM bytecode lane

Measured `scripts/bench v2-bytecode` and `scripts/bench v2-backends` across
`arith-loop`, `recursion-fib`, `recursion-tco`, and `pattern-match-heavy`.
The existing bytecode lane is production-relevant for recursion
(`recursion-fib=1.16ms`, `recursion-tco=0.028ms`) but is not a universal
default (`pattern-match-heavy=19.3ms` vs VM 13.7ms and v2 Rust 0.266ms). No
runtime code changed; the next production blocker is a measured
`pattern-match-heavy` slice. Gates: `installBin`, bytecode frontend tests 2/2,
direct `run --bytecode` smoke, affected conformance 1/1, all measured bench
rows, and `git diff --check`.

## 2026-07-09 — v2 JVM source backend uses Long helpers for tail-recursive globals

The v2 JVM source backend now prefers proven Long helpers over boxed
`@tailrec` direct methods when a global call's arguments are statically Long.
Long tail-recursive helpers are annotated with `@tailrec`, and their closure
wrappers call the Long helper via `_asLong` arguments. Public
`scripts/bench v2-backends recursion-tco` moved `v2-jvm` from 3.09ms to
0.027ms (`v2=0.253ms`, `v2-rust=0.658ms`). The closing source-backend sweep
keeps `recursion-fib`, `arith-loop`, and `pattern-match-heavy` in the expected
ranges, so the known JVM/Rust source-backend performance gate is closed; the
separate v2 VM production-performance gate remains open. Gates: JVM backend
compile, `installBin`, backend `tco`/`letrec`, affected recursion conformance
3/3, final and regression/sweep bench rows, and `git diff --check`.

## 2026-07-09 — v2 Rust source backend specializes Float static-list reductions

The v2 Rust source backend now emits optional `f64` helpers for provably
Float-returning global lambdas and a structural static-list reduction fast path
for `topLevelList.foreach(item => total = total + floatFn(item))`. The generic
boxed `V::Fn`/`v_method("foreach")` fallback remains in place. Public
`scripts/bench v2-backends pattern-match-heavy` moved `v2-rust` from 319.1ms to
0.278ms (`v2=15.6ms`, `v2-jvm=10.6ms`). Regression rows stayed stable:
`recursion-fib v2-rust=1.44ms`, `recursion-tco v2-rust=0.668ms`. Gates: Rust
backend compile, `installBin`, backend `bool`/`tco`/`letrec`/`mutual-recursion`,
affected match/list conformance 5/5, final and regression bench rows, and
`git diff --check`.

## 2026-07-09 — v2 source backend production rows remeasured after Rust recursion fixes

The source-backend production gate now has fresh post-helper numbers for the
remaining rows. `arith-loop` is no longer a Rust source hotspot
(`v2-rust=0.000025ms`), `recursion-tco` now reports an honest nonzero Rust row
after a benchmark-only tail-recursive anti-fold fix (`v2=0.279ms`,
`v2-jvm=3.11ms`, `v2-rust=0.721ms`), and `pattern-match-heavy` remains the
largest real Rust source blocker (`v2-rust=318.2ms`). `recursion-fib` regression
check stayed stable at `v2-rust=1.46ms`. Gates: `installBin`, affected
recursion conformance 3/3, the four public v2-backends bench rows, and
`git diff --check`.

## 2026-07-09 — v2 Rust source backend specializes recursive Long globals

The v2 Rust source backend now emits direct `i64` helpers for global lambdas
whose bodies are provably Long-typed, while preserving generic `V::Fn` closures
for first-class and non-Long uses. The v2-rust bench path also applies a
benchmark-only `std::hint::black_box` patch to zero-arg Long helpers so LLVM
cannot fold `workload()` to a constant in the temporary bench binary. Default
`scripts/bench v2-backends recursion-fib` moved `v2-rust` from 226.7ms to
1.44ms (`v2=6.03ms`, `v2-jvm=1.25ms`). Gates: Rust backend compile,
`installBin`, backend parity `bool`/`mutual-recursion`/`tco`/`letrec`,
affected recursion conformance 3/3, final bench row, and `git diff --check`.

## 2026-07-09 — v2 bench temp jars are parallel-safe

`v2/scripts/bench.sh` now uses a macOS-safe suffix-free `mktemp` template for
the temporary bench jar. Parallel short probes for `bool-predicate` and
`mutual-recursion` now receive distinct `/tmp/v2-bench-*` paths instead of
colliding on the literal `/tmp/v2-bench-XXXXXX.jar`.

## 2026-07-09 — v2 backend-check bool and mutual-recursion rows restored

`v2/backend/check.sh bool` and `v2/backend/check.sh mutual-recursion` again
compile through ssc1c, run the VM oracle, and verify JVM/JS/Rust source backend
parity. The invalid `(app (lit (int 1000)) ...)` CoreIR came from
`indent2braces.py` emitting unparenthesized `while i < 1000 { ... }`; converted
while conditions are now parenthesized. Gates: backend `bool`,
`mutual-recursion`, `tco`, `letrec`, affected conformance
`mutual-recursion,variables` 2/2 across INT/JS/JVM, and `git diff --check`.

## 2026-07-09 — v2 bytecode non-tail recursive Int calls keep values

The bytecode backend's Long-specialized self-recursive helper now distinguishes
tail and non-tail self-calls. Tail calls still use the constant-stack
parameter-rebinding loop; non-tail calls such as `fib(n - 1) + fib(n - 2)` now
emit a real recursive Long-helper call so the expression leaves a value on the
operand stack. This fixes the fresh `origin/main` regression where bytecode
`fib(30)` returned `1` instead of `832040`. Gates: focused recursive bridge test
3/3, self-tail bridge test 1/1, affected recursion conformance 3/3, final
`scripts/bench v2-backends recursion-fib`, and `git diff --check`.

## 2026-07-09 — v2 JVM source backend specializes recursive Long globals

The JVM source backend now emits direct `Long` helpers for global lambdas whose
bodies are provably `Long`-typed, while keeping closure lazy vals for first-class
function values and preserving the existing tail-recursive direct path.
`scripts/bench v2-backends recursion-fib` moved the JVM source row from
67.5ms to 1.37ms on the default harness (`v2=6.99ms`, `v2-rust=235.5ms` in the
after run). Gates: JVM backend compile, backend `tco`/`letrec`, affected
recursion conformance, focused recursive bridge tests, final bench row, and
`git diff --check`. An unrelated ssc1c/backend-check bug in the generated
`bool`/`mutual-recursion` rows is tracked separately in `BUGS.md`.

## 2026-07-09 — Default conformance gate restored

The deterministic top-level conformance blocker found after the v2 bytecode
slice is fixed. `dataset-shape` now regenerates stale JVM artifacts; JS codegen
keeps local lambda/pattern binders ahead of top-level collision-safe names; and
unqualified imports now alias the importer-local JS name to the child module's
actual emitted JS name when runtime-collision renames diverge. Scala.js
standard-block compilation and the conformance JVM lane now run `scala-cli`
serverless by default, so the production gate no longer depends on a Bloop BSP
socket. Gates: `backendJs/compile; installBin`, `backendScalajs/compile;
installBin`, the original eight-row repro 8/8, actor Bloop repro 4/4,
fenceless/standard-Scala slice 4/4, full `tests/conformance/run.sh --no-memo`
145 passed, 0 failed (+2 pending), and `git diff --check`.

## 2026-07-09 — JVM dataset conformance regenerates stale mkString artifacts

`dataset-shape` now passes the JVM conformance lane again. The JVM backend's
`_show` routing rewrites `.mkString()` to `.map(_show).mkString`; `_Dataset`
now exposes its no-arg `mkString` as a parameterless method like Scala
collections, and the JVM `.scjvm` codegen cache key was bumped so old generated
Scala with `def mkString()` is treated as stale. Gates: `core/compile`,
`backendJvm/compile`, `installBin`, direct `bin/ssc run-jvm
tests/conformance/dataset-shape.ssc`, focused conformance `dataset-shape` 1/1,
and the original eight-row blocker repro now narrows to 6 remaining JS failures.

## 2026-07-09 — v2 bytecode unboxes hot integer arithmetic and recursion

The `scripts/bench` wrapper now has a `v2-bytecode` corpus lane, and the JVM
bytecode backend has conservative unboxed `long` fast paths for bridge-lowered
integer loops and guarded arity-1 self-recursive Int functions. Hot rows improve
substantially without changing generic `__arith__` fallback semantics:
`arith-loop` bytecode 43.6ms -> 6.80ms, `nested-loop` 52.2ms -> 7.60ms,
`range-sum` remains at parity (0.413ms), and `recursion-fib` 31.9ms -> 1.27ms.
Gates: focused bytecode bridge tests 2/2, `installBin`, affected conformance
`arithmetic,recursion,tail-recursion,mutual-recursion` 4/4 with `--no-memo`,
the four final `scripts/bench v2-bytecode` rows, and `git diff --check`. A
separate default-conformance blocker found during broad gating is tracked as
`green-main-conformance-7fail`.

## 2026-07-09 — v2 busi read_gigs works through the real hub

The live busi `read_gigs` MCP failure was reduced and fixed in v2. A payments
bridge `Currency` metadata collision first broke busi's isolated gigs test with
`arity: 1 expected, 3 given`; `Currency.apply` now accepts both the payments
one-field and std/money three-field constructor shapes. The remaining live hub
leak came from importing `RepoRef(name, head)`: the global field registry
lowered `List.head` to eager `fieldAt`, bypassing effect lifting and letting
`GigSource.fetch` reach a downstream `if`. Common dynamic zero-arg members such
as `head` now stay on the dynamic method path, while data fields still resolve
through tag/arity-aware lookup. Gates included focused bridge tests,
`installBin`, busi `tests/v2/gigs.ssc`, live busi `/api/gigs` and
`/mcp tools/call read_gigs`, conformance `head-field-*,money-multisection`,
full `FrontendBridgeTest`, payment examples, and `git diff --check`.

## 2026-07-09 — JVM codegen avoids Request/Response name collisions in non-server scripts

`ssc run-jvm tests/conformance/user-request-shadow.ssc` failed at scalac time
when user code defined a top-level `Request` case class: the non-server JVM
preamble still inlined the HTTP runtime model with its own public `Request`.
Non-server scripts that collide with `Request`/`Response`/`StreamResponse` now
use a reduced runtime preamble plus private `_SscRuntime*` stubs, while
HTTP/server modules keep the existing public model. Gates: `FrontendBridgeTest`
42/42, `installBin`, direct `run-jvm` repro (`7/9/7/42`), conformance
`money-multisection,v2-*,user-request-shadow` 7/7, and full
`./v2/conformance/check.sh`; `git diff --check`.

## 2026-07-09 — v2 resolves same-named case classes by receiver arity

A file importing two different types with the SAME name (e.g. `std/http.ssc`
`Request` and a domain `Request`) broke field access on one of them on v2: the
field registry is keyed by tag name, so the last-registered layout won and the
other type's fields (http `req.form`/`req.params`) resolved to `Stub`. This hit
busi's real hub `POST /pair` (`req.form.getOrElse("code",...)`). v2 now keeps a
`(tag, arity)` index and resolves each field access against the layout whose
arity matches the receiver — fixing both collision directions. v1 already
tolerated this via dynamic by-name lookup. Gate:
`tests/e2e/req-type-collision-v2-smoke.sh`.

## 2026-07-09 — v2 Option.exists/forall/contains/nonEmpty implemented

The v2 VM had most `Option` methods but not `exists`/`forall`/`contains`/
`nonEmpty` — `None.exists(pred)` surfaced an unhandled `Op` and `Some.exists`
returned a `Stub` instead of a `Boolean`, breaking idiomatic
`identity.exists(hasRole)` auth checks. All four now dispatch (matching the
list-method semantics). Gate: `tests/conformance/v2-option-exists.ssc`.

## 2026-07-09 — v2 HTTP route path params (`req.params(:name)`) resolve correctly

On `--v2`, `req.params("id")` for a `:name` route segment silently returned
`Stub` instead of the matched value (breaking any handler that looks up by ID).
`params`/`query` (and `bearerToken`/`jwtClaims`/`basicAuth`) are runtime-injected
into the Request value and absent from std/http.ssc's `Request` case class;
`registerCaseClass` was clobbering the bridge's 14-field runtime layout with the
9-field declaration, so those fields were dropped. `Request` is now locked to a
single-source-of-truth runtime layout (`PluginBridge.requestFieldNames`) that the
case-class declaration can't override. Gate: `tests/e2e/route-params-v2-smoke.sh`.

## 2026-07-09 — v2 bridge supports payments and bank-rails examples

`examples/traditional-payments.ssc`, `examples/bank-rails-pix.ssc`, and
`examples/bank-rails-fednow.ssc` now run on the v2 VM without leaking unresolved
`Op(...)` or `Stub` values. The bridge registers deterministic no-network
Stripe/Pix/FedNow provider method objects, payment/bank-rails field metadata,
`Money`/`Currency` helpers, pure Pix QR generation, and the small
`Instant`/`Thread` surface used by the FedNow poll example. Server/webhook and
negative-path snippets that need route/platform/disconnected state are marked
`scala no-run`. Gates: `FrontendBridgeTest` 42/42, `installBin`, the three real
`bin/ssc run --v2` examples with a no-`Op(`/no-`Stub` stdout guard, conformance
`money-multisection,v2-*` 4/4, full `./v2/conformance/check.sh`, and
`git diff --check`.

## 2026-07-09 — v2 multi-line list literals no longer crash the parser

`bin/ssc --v2` crashed with scala.meta `illegal start of simple expression`
on fences containing a multi-line `[ … ]` list literal (bracket opens a line,
elements follow) — e.g. `examples/datatable-static-spa.ssc`. The fence
extractor (`FrontendBridge.filterImportLines`) misread a bare `[` as the start
of a multi-line import directive and swallowed the rest of the fence. It now
consumes a multi-line import only when a real `](….ssc)` close actually
follows. Corpus 152/10 → 154/8; pinned by
`tests/conformance/v2-multiline-list-literal.ssc`.

## 2026-07-09 — v2 bridge supports markup/XSLT example

`examples/xslt-transform.ssc` now runs on the v2 VM instead of exiting with
empty output. The v2 bridge lowers `xml` interpolation through XML-escaping
helpers, registers JvmMarkupCodec-backed `MarkupCodec`/`PureMarkupCodec`
method objects, supports `SerializeOpts` named/default construction, passes
`Map[String,String]` XSLT params, and exposes `TransformError.message` through
`Left(TransformError(...))`. JS/Rust/WASM XSLT support and full fenced
` ```xml ``` ` section binding remain out of scope for this slice. Gates:
full `FrontendBridgeTest` 39/39, `installBin`, real `bin/ssc run --v2
examples/xslt-transform.ssc`, conformance `v2-*,content*` 7/7, full
`./v2/conformance/check.sh`, and `git diff --check`.

## 2026-07-09 — v2 bridge supports in-process remote registry

`examples/remote-registry-rpc.ssc` now runs on the v2 VM: `remote def` is
rewritten before scala.meta parsing, manifest/`@remote`/sugar metadata registers
in-process handler closures, and `Remote.function(...).call`, `tryCall`,
`remoteTryCall`, and `Remote.handlers()` use the v2 `PluginBridge` registry.
HTTP fallback, `Remote.http`, `Remote.stub`, and trait-shaped stubs remain out
of scope for this slice. Gates: remote-focused bridge tests, full
`FrontendBridgeTest`, `installBin`, the real v2 example smoke, conformance
`distributed*`, full `./v2/conformance/check.sh` before the final unrelated
native-front rebase, and final-tip `git diff --check`.

## 2026-07-09 — v2 VM foreach lambda avoids hot env materialization

`FastCode.tryFC` now has a conservative no-materialized-env lane for inline
`foreach` `Lam(1, body)` calls: supported bodies read the current element as a
virtual appended `Local(0)` and shifted outer locals directly from the base env,
so the `pattern-match-heavy` `cell.set(total, total + area(s))` loop no longer
allocates `Runtime.appendOne(env, elem)` per list element. Complex or capturing
bodies keep the old fallback; a focused regression stores an escaping nested
lambda from a `foreach` body and verifies it still captures the first element.
The single-row `pattern-match-heavy` v2 result improved from 18.2 ms to 14.4 ms,
but the overall v2 VM production-performance gate remains red. Gates: focused
bridge test, `installBin`, four-row bench, full `./v2/conformance/check.sh`,
conformance `litdoc`, and `git diff --check`.

## 2026-07-09 — v2 VM pattern-match-heavy fast match arms

`FastCode.tryFC(Match(...))` now reuses scratch env arrays for compact
arithmetic-only match arms, guarded by a stricter `armBodyScratchSafe`
predicate so env-capturing or user/plugin-call arms keep the old allocation
path. This targets `bench/corpus/pattern-match-heavy.ssc`'s `area` dispatcher:
the v2 VM row improved from 35.1 ms to 16.4-17.0 ms. The overall v2 VM 2x
performance gate remains open. Gates: focused bridge test, `installBin`, two
full `./v2/conformance/check.sh` runs, conformance `litdoc`, and
`git diff --check`.

## 2026-07-09 — v2 VM effect handlers match free-monad Op values again

The v2 VM no longer lifts every `DataV("Op", ...)` out of `match`
scrutinees. `Match` now auto-threads only bridge/runtime operations recognized
by `Runtime.isAutoThreadOp`, so algebraic and typed effect handlers can match
their own free-monad `Op` constructors normally. Added focused regression
coverage for `effects-state.ssc0` and `hm-eff-comp.hm` via Mira-to-CoreIR.
Gates: focused bridge test, full `./v2/conformance/check.sh`, `installBin`,
and conformance `litdoc`.

## 2026-07-09 — v2 VM closed-form scalar loop for arith-loop

The v2 VM now recognizes the exact bridge-lowered local Long-cell summation
loop used by `bench/corpus/arith-loop.ssc` in both normal `Code` and arity-0
`fcEntry`. The bounded production probe moved the v2 `arith-loop` row from
9.91 ms to 0.000018 ms. The overall Phase-3 VM performance gate remains open:
`pattern-match-heavy`, `recursion-fib`, and `recursion-tco` still need focused
follow-up slices. Gates: focused bridge test, `installBin`, four-row bench,
conformance `litdoc`, and `git diff --check`. Full v2 conformance is currently
red on a pre-existing VM effect-handler regression reproduced on clean
`origin/main` and tracked as `v2-vm-effect-handlers-regression`.

## 2026-07-09 — v2 backend performance harness exposes source backend columns

`scripts/bench v2-backends [workload]` and `./bench.sh --v2-backends ...` now
time representative corpus rows through v2 VM, v2 JVM source backend, and v2
Rust source backend. The first four-row baseline is recorded in
`specs/v2-full-compat.md`; it closes the measurement gap only, so the Phase-3
source backend performance thresholds remain open under
`v2-source-backend-production-perf-gates`. Gates: backend parity `tco`/`bool`,
CLI command tests, `installBin`, conformance `litdoc`, and
`scripts/bench v2-backends arith-loop`.

## 2026-07-09 — v2 VM hot-path triage improves recursion performance

`v2/src/Runtime.scala` now catches bridge-generated Long comparisons in the
`SelfRecLL` fast entry and compiles eligible arity-2 self-tail Long recursion
to a local loop. The bounded production probe improved `recursion-fib` from
68.5 ms to 5.94 ms and `recursion-tco` from 2.52 ms to 0.273 ms, but the
Phase-3 2x v2 VM performance gate remains red: `arith-loop` 42.2x,
`pattern-match-heavy` 682.7x, `recursion-fib` 5.0x, and `recursion-tco` 10.1x
slower than `ssc` with `./bench.sh --warmup-time 500 --reps 20 arith-loop
recursion-fib recursion-tco pattern-match-heavy`. The remaining production
performance work is tracked as `v2-vm-production-jit-gate` in BACKLOG. Gates:
focused `FrontendBridgeTest` filters, `installBin`, v2 conformance, affected
conformance, and before/after bounded bench runs.

## 2026-07-09 — v2 production performance gate baseline recorded

Recorded a bounded production-v2 performance probe in `specs/v2-full-compat.md`.
The current v2 VM remains far outside the Phase-3 2x target on representative
corpus rows (`37.5x` to `355.6x` slower than `ssc`), so the performance
checkboxes stay open. Also fixed `scripts/bench wall` so it finds
`tests/bench` data and uses `ssc run-jvm` for the JVM fallback; the wall harness
now emits usable fib/sum/list-ops rows again. Gates: `installBin`, `bench.sh`
bounded probe, `scripts/bench wall`, and conformance `litdoc` on INT/JS/JVM.

## 2026-07-09 — v2 source JVM backend supports mutual LetRec TCO

`v2/backend/jvm/JvmBackend.scala` now emits a conservative local dispatcher
loop for eligible mutual-tail `LetRec` groups, so deep even/odd-style source
JVM programs run without recursive closure stack growth. Added
`v2/conformance/mutual-tco.coreir` and wired it into the v2 conformance script.
Gates: `scala-cli compile v2/backend/jvm/`, standalone generated-source checks,
`./v2/conformance/check.sh`, `scripts/sbtc "installBin"`, and conformance
`litdoc` on INT/JS/JVM.

## 2026-07-09 — v2 production readiness docs synced to clean default-lane gate

`v2/output-parity-baseline.md` and `specs/v2-full-compat.md` now state the
current default-lane production gate after the post-JS/runtime rebaseline:
`68/91 identical · 0 mismatch · 0 v2-error · 23 v1-only`. The Phase-3
checklist now distinguishes the clean default-lane switch criteria from
remaining performance/backend/server/provider-lane work.

## 2026-07-09 — v2 production parity baseline remains clean after JS/runtime fixes

Refreshed the full v2 output-parity production gate after the JS flat-bundle
runtime-collision fix and stream-family closures. `scripts/sbtc "installBin"`
passed, and `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all`
exited 0 with `68/91 identical · 0 mismatch · 0 v2-error · 23 v1-only`
across 195 examples. Conformance `litdoc` passed on INT/JS/JVM. No new BUGS
entry was needed.

## 2026-07-09 — JS flat bundles rename runtime-colliding declarations

Closed the remaining `jsgen-toplevel-name-vs-preamble` class. Flat JS bundle
generation now renames runtime-colliding top-level `def`, extern stubs,
objects, case classes, enum companions/cases, named givens, and import aliases
using the same derived runtime-name set already used for `val`/`var`. Gates:
`JsGenStdImportTest` 49/49, conformance `litdoc` INT/JS/JVM, and conformance
`mcp-types` INT/JS.

## 2026-07-09 — scjvm cache bug ledger duplicate closed

Closed the older `scjvm-artifact-cache-ignores-compiler-version` BUGS entry as
a duplicate of the landed JVM artifact cache invalidation fix. No code changes.

## 2026-07-09 — JVM artifact cache invalidates old codegen output

`.scjvm` artifacts now carry a JVM codegen cache key and `run-jvm` regenerates
otherwise source-fresh artifacts when that key is legacy or old. Added a CLI
regression for stale-key `run-jvm` regeneration. Gates: `ModuleGraphTest`,
`JvmIncrementalCliTest`, `installBin`, and conformance `litdoc` on INT/JS/JVM.

## 2026-07-09 — litdoc conformance enabled on JS/JVM backend lanes

`tests/conformance/litdoc.ssc` now runs and passes on INT, JS, and JVM. The
slice fixed JS flat-runtime top-level collisions for user `val`/`var` bindings,
JS regex semantics for `String.split`, JVM preamble omission for user-owned
`doc`, and JVM no-arg `mkString()` emission. Gates: backend JS/JVM compile,
`installBin`, direct JS/JVM litdoc runs, conformance `litdoc`, and focused
JsGen/JvmGen regression tests.

## 2026-07-09 — v2: busi hub boots on --v2 (head-field shadow + foreign HOFs) + arith JIT-size fix

The busi hub-boot blocker (BUGS.md v2-head-field-dispatch-shadow, root-caused and guarded by a
sibling): the bridge bakes fieldAt indexes from the GLOBAL field-name registry without receiver
type info, so a case class `Ref(name, head)` made `hits.head` on a List read the Cons tail. The
3-arg fieldAt now resolves by the receiver's OWN registered field names with dynamic-dispatch
fallback (builtin members stay builtin; also fixes same-name-different-index across classes).
Companion: foldLeft/map/foreach on ForeignV(ArrayBuffer) + foldLeft on mutable.Map (next boot
blocker — the hub folds over Array.fill tables at load). busi hub: "listening on 0.0.0.0:8392" on
--v2. Separately, bisected the overnight 15× pattern-match-heavy regression (354 vs 23.6 ms/iter)
to the a2985d911 arith unification — the merged dispatch blew past JVM JIT size limits; arithOp is
now a small hot head (numerics, ->, Op-lifting) delegating to arithRest. All benches back at
baseline; FrontendBridgeTest 25/25.
## 2026-07-09 — v2: stream-family parity blockers closed

v2 now runs `examples/distributed-streams.ssc` and `examples/streams.ssc` to
completion. The slice fixed v2↔v1 plugin bridge shapes for stream-family
externs (`KV`/`Rate` field names, large Cons/Nil conversion, flattened curried
natives, DStreams tuple/option outputs, and `ReactiveSignal.bind`). The
production output-parity gate now has no unexplained strict mismatch:
`68/91 identical · 0 mismatch · 0 v2-error · 23 v1-only` with 4 v1-side skips
across 195 examples. Gates: streams plugin 83/83, DStreams plugin 66/66,
PluginBridge 26/26, FrontendBridge 29/29, conformance `signals`, direct v2
stream examples, targeted stream parity, and full `scripts/v2-output-parity
--all`.

## 2026-07-09 — v2: v1-side parity rows classified

`scripts/v2-output-parity` now classifies `examples/effects.ssc` and
`examples/dsl-calc-parser.ssc` as v1-side/better-output rows: v2 prints the
documented behavior while the rollback v1 runner stops early or truncates the
parser result. The parity harness also fails fast on temp/RC file creation or
write failures, preventing no-space runs from producing false corpus baselines.
The production output-parity gate is now
`68/93 identical · 2 mismatch · 0 v2-error · 23 v1-only` with 5 nondet and
2 v1-side skips across 195 examples; the only strict mismatches left are
`distributed-streams.ssc` and `streams.ssc`. Gates: targeted v1-side parity,
targeted `scala-js-demo`/`lang-split` freshness parity, artificial unwritable
`SSC_PARITY_TMPDIR`, conformance `effects`, and full
`scripts/v2-output-parity --all`.

## 2026-07-09 — v2: standard Scala multi-fence parity fixed

v2 now runs standard-Scala-only `scala` fences in document order and supports an
explicit mixed-document opt-in (`runScalaFences: true` plus aliases) for
runnable `scala` fences alongside `scalascript` fences. The slice also filled
the standard runtime/lowering gaps exposed by `examples/scala-js-demo.ssc`:
`String.takeWhile`/`dropWhile`, `f"..."` interpolation, and guarded
constructor-pattern fall-through. The production output-parity gate is now
`68/95 identical · 4 mismatch · 0 v2-error · 23 v1-only` with 5 nondet skips
across 195 examples. Gates: FrontendBridgeTest 25/25, `installBin`,
conformance `standard-scala-*`, targeted `scala-js-demo`/`lang-split` parity,
and full `scripts/v2-output-parity --all`.

## 2026-07-09 — v2: OAuth MCP generated output classified

`scripts/v2-output-parity` now treats the OAuth-protected MCP demos as
nondeterministic-output by design because they print generated client
ids/secrets and server startup banners. The production output-parity gate is now
`66/95 identical · 6 mismatch · 0 v2-error · 23 v1-only` with 5 nondet skips
across 195 examples. Gates: `installBin`, targeted OAuth/MCP parity,
conformance `mcp-*`, and full `scripts/v2-output-parity --all`.

## 2026-07-09 — v2: os-env parity classified as host-dependent

`scripts/v2-output-parity` now treats `examples/os-env.ssc` as
nondeterministic-output by design instead of a v2 mismatch, preserving the demo's
real platform/CWD/env output. Added `tests/conformance/std-os.ssc` for
deterministic std/os helper coverage. The production output-parity gate is now
`66/97 identical · 8 mismatch · 0 v2-error · 23 v1-only` with 3 nondet skips
across 195 examples. Gates: `installBin`, targeted `os-env` parity, conformance
`std-os`, and full `scripts/v2-output-parity --all`.

## 2026-07-09 — v2: async parallel demo output stabilized

`examples/async-parallel-demo.ssc` no longer prints live wall-clock milliseconds,
so v1/v2 output parity compares the deterministic result lines instead of host
timing noise. The production output-parity gate is now
`66/98 identical · 9 mismatch · 0 v2-error · 23 v1-only` across 195 examples.
Gates: `installBin`, conformance `async-parallel`, targeted async parity, and
full `scripts/v2-output-parity --all`.

## 2026-07-09 — v2: graph plugin edge display parity fixed

Bridged v1 plugin instances with named fields now render through v1 `Value.show`
instead of leaking as `<foreign>` when printed or auto-printed by v2. This fixes
`examples/graph-neo4j-storage.ssc`; the production output-parity gate is now
`65/98 identical · 10 mismatch · 0 v2-error · 23 v1-only` across 195 examples.
Gates: PluginBridgeTest 23/23, `installBin`, conformance `graph-edge-display`,
targeted graph parity, and full `scripts/v2-output-parity --all`.

## 2026-07-09 — v2: post-split parity baseline refreshed

After dynamic arithmetic unification and regex `String.split` parity, the
production output-parity gate remains stable at `64/98 identical · 11 mismatch ·
0 v2-error · 23 v1-only` across 195 examples. The next narrow production
candidate is `graph-neo4j-storage.ssc` (`StoredEdge(...)` vs `<foreign>`).
Gates: `installBin` and full `scripts/v2-output-parity --all`.

## 2026-07-09 — v2: string split regex semantics restored

v2 `String.split` / `str.split` now follows v1 regex semantics instead of
quoting the delimiter as a literal. This fixes litdoc inline bold parsing under
`ssc run --v2`; `tests/conformance/litdoc.ssc` now has an INT expected-file
regression. Gates: `installBin`, conformance `litdoc`, and direct v1/v2 litdoc
diff.

## 2026-07-09 — v2: dynamic arithmetic dispatch unified

`resolve("__arith__")` now delegates to `Prims.arithOp`, eliminating the
literal-vs-non-literal operator semantics split. Focused CoreIR regressions cover
non-literal Map+Tuple2, char-code comparisons, Decimal, actor-send, and fallback
cases. Gates: FrontendBridgeTest 20/20, `installBin`, and affected conformance
`litdoc,arithmetic` (`arithmetic` passed; `litdoc` has a separate inline-bold
follow-up).

## 2026-07-09 — v2: production queue stale entries reconciled

The remaining stale open SPRINT rows for the historical Phase-3 default switch
and the superseded full-corpus parity harness were closed. The queue now points
at the shipped `v2-prod-default-switch`, `v2-output-parity-harness`, and current
`v2-parity-current-errors` gate instead of advertising duplicate work.

## 2026-07-09 — v2: current parity gate has zero v2-error rows again

The production output-parity gate was refreshed after toolkit-v2 work and the
new deterministic v2-error stack was fixed. Standard `scala` fenced documents
now execute under v2 instead of silently compiling to an empty program, and the
v2 actor bridge now exposes cluster capability globals (`SeedResolver`,
`clusterOf`, `resolveSeeds`, `codeIdentity`, `assertCodeIdentity`) with plugin
method dispatch winning over same-named case-class method globals. Fresh full
gate after `installBin`: `64/98 identical · 11 mismatch · 0 v2-error ·
23 v1-only` across 195 examples. Gates: PluginBridge 22/22, FrontendBridge
17/17, targeted cluster v2 regression, conformance `standard-scala-fence`,
real `cluster-capability` v1/v2 run, targeted six-example parity, and full
`scripts/v2-output-parity --all`.

## 2026-07-09 — tkv2: route-derived typed clients handle path params

When users omit manual `apiClients:`, `RouteDeriver` now gives non-body
path-param routes callable request types: one `:param` becomes `String`,
multiple params become `Any`, and no-param endpoints remain `Unit`. Generated
JS/browser clients and JVM/Swing clients now expose methods such as
`Api.getApiItemsById("42")` for derived routes, while explicit `apiClients:`
metadata and validation warnings stay unchanged. Gates: `RouteDeriverTest`
16/16, typed route JS/JVM codegen tests 57/57, affected compiles, conformance
`tkv2-typed-client-derived`, and `derived-route-clients` `emit-js`/`emit-spa`
smokes after `installBin`.

## 2026-07-09 — tkv2: WebAuthn browser passkey actions

`std/ui/webauthn.ssc` now provides `webauthnRegister` and `webauthnAssert`
EventHandlers for passkey buttons on the production `emit-spa --frontend custom`
path. The JS runtime POSTs begin options, calls `navigator.credentials.create/get`,
POSTs verifier-shaped base64url complete payloads, preserves caller headers, and
reports deterministic off-browser errors. The adjacent `std/auth.ssc` WebAuthn
declaration drift was fixed. Gates: affected compiles, `JsRuntimeWebAuthnClientTest`
and `JsGenStdImportTest` 43/43, conformance `tkv2-webauthn,webauthn-server-verify`,
and `examples/frontend/webauthn-toolkit-demo` emit-spa smoke.

## 2026-07-09 — tkv2: keyed browser list reconciliation

`std/ui` now has `forKeyed(items, key)(render)` for the production
`emit-spa --frontend custom` path. The JS runtime renders keyed row wrappers,
moves surviving DOM nodes on reorder, removes missing keys, late-binds newly
inserted row subtrees, and keeps existing `View.ForSignal` semantics unchanged.
Gates: `JsGenStdImportTest` + `JsRuntimeKeyedForTest` 43/43, affected module
compiles, conformance `tkv2-keyed-for`, and `examples/frontend/keyed-for-demo`.

## 2026-07-08 — queue: stale p3 Spark/effects blockers reclassified

The remaining open p3 Spark/effects queue items now point at the newer
`v2-prod-corpus-scope` and `v2-prod-effects-parity audit` decisions: Spark
examples are explicit backend-lane work, `algebraic-effects.ssc` is already
output-identical, and the remaining `effects.ssc` mismatch is v1-side rather
than a v2 production blocker.

## 2026-07-08 — v2: case-class instance methods run on the default lane

`case class ...:` template methods now lower into tag-dispatched v2 method
closures, including constructor-field binding inside method bodies and
field-first precedence for ordinary selectors. `Cluster.close()` no longer
falls through to `Stub("Cluster.close")`, and the distributed examples use the
public shutdown API again. Gates: `V2CaseClassMethodCliTest` 3/3,
`V2TuplePatternCliTest` 4/4, direct default-v2 distributed examples, and
affected conformance `cluster-connect,distributed-*` plus field-selector cases.

## 2026-07-08 — v2: offline distributed MapReduce examples run locally

`std.mapreduce.localLoopbackCluster` now builds explicit local shuffle-capable
actor workers for offline examples and corpus gates, while `Cluster.connect`
remains the real remote-node API. The default v2 lane also now handles the tuple
pattern/destructuring and map-reduce hoisting shapes exposed by those real
workers. Gates: `V2TuplePatternCliTest` 4/4, direct default-v2 runs of
distributed word-count/log aggregation/join, and affected conformance
`cluster-connect,distributed-*` 6/6.

## 2026-07-08 — v2: stale p4 default-flip queue item closed

`p4-default-flip` was a duplicate of the already-landed
`v2-prod-default-switch`: plain `ssc run <file>` defaults to the v2 VM, `--v1`
is the rollback path, and `--v2` remains an explicit force flag. Fresh gates in
the claimed worktree: `V2DefaultSwitchTest` + `CommandRegistryTest` 11/11,
`installBin`, direct default/`--v1`/`--v2` hello smokes, and affected conformance
`tests/conformance/run.sh --only 'dsl*' --no-memo`.

## 2026-07-08 — v2: self-hosted Rust/WASM target gate is green

The self-hosted `ssc0 -> JS/Rust/WASM` lane now matches the VM display contract:
proper lists render as `List(...)`, whole-valued Rust float literals compile as
`f64`, and stale conformance expectations were rebaselined to the accepted
kernel display semantics. The full v2 gate also exposed and fixed a VM-only
typed effect regression: `Let`/`Seq` auto-threading now applies only to
bridge/runtime Ops with dotted labels, while pure free-monad `Op(...)` values
remain data for handlers/schedulers. Gates: `v2/conformance/check.sh`,
`v2/backend/check.sh`, affected effect/async conformance, and the top-level
`rust*,wasm*` selector check (0 matching cases; Rust/WASM covered by v2 gate).

## 2026-07-08 — v2: `ssc run` forwards argv after `--`

The v2 VM runners now support program argv with an explicit separator:
`ssc run <file.ssc> -- [args...]`, `ssc run --v2 <file.ssc> -- [args...]`,
and `ssc run --bytecode <file.ssc> -- [args...]`. Positionals before `--`
remain source files, so multi-file runs are unchanged. The bytecode lane also
matches VM list-application fallback for `args(0)`. Gates:
`cli/assembly`, `cli/testOnly *V2RunArgvCliTest`, `installBin`, direct
default/`--v2`/`--bytecode` argv smokes, conformance `collections`, and
combined `*V2RunArgvCliTest *V2JsLaneCliTest`.

## 2026-07-08 — v2: opt-in JS lane runs through CoreIR

`ssc run-js --v2 <file.ssc> [args...]` now routes `.ssc` sources through
FrontendBridge, emits v2 CoreIR JavaScript in-process, and runs the temporary
CommonJS file with Node. Legacy `ssc run-js <file.ssc>` remains on the existing
v1 JS path. The v2 JS preamble now supports the bridge globals/primitives needed
for hello/argv smoke programs. Gates: `v2JsBackend/compile`,
`cli/assembly`, `cli/testOnly *V2JsLaneCliTest`, `installBin`,
`v2/backend/check.sh`, and affected conformance
`js-cps-intrinsic-rewrite,node-basic`.

## 2026-07-08 — perf: dispatch-class workloads remeasured after bytecode M2

`array-update`, `vector-index`, `pattern-match-heavy`, and `effect-stream` are
closed as one performance class, not four separate hunts. Corpus measurement
after `installBin` shows the `ssc`/target lanes are already healthy for these
workloads, while the explicit `v2` VM column remains the common generic-dispatch
tail. Gates: `scripts/bench smoke`; `./bench.sh --warmup-time 1000 --reps 50
array-update vector-index pattern-match-heavy effect-stream`.

## 2026-07-08 — v2: actor `sendAfter` works under the default CLI runner

The default v2 fat-jar path no longer exits 0 before scheduled actor messages
fire. `PluginBridge.registerActors` now tracks actor-run quiescence and supports
`sendAfter` / `sendInterval` / `cancelTimer`, so the default runner and `--v2`
match `--v1` for the minimal timer actor repro. Gates: `v2PluginBridge/compile`,
`cli/assembly`, `cli/testOnly *V2ActorCliTest`, and conformance `actors-*` (8/8
after `installBin`).

## 2026-07-08 — test: bounded root sbt gate is green

The v2 production-hardening root gate now completes successfully:
`scripts/sbtc "test"` is green in 1668s with bounded root Test concurrency
(`SSC_SBT_TEST_CONCURRENCY`, default 4). The slice also fixed strict-mode JS
runtime duplicate declarations that blanked Electron/toolkit bundles, made the
typed JSON facade repeat-safe, aligned Electron typed-route and Spark submit
CLI smoke tests with the current generated contracts, and sharpened the row POST
payload resolver assertion. Gates: full `cli/test` (554 succeeded, 29 canceled),
affected conformance `collections,dataset-from-file,dataset-shape,json-*,std-ui-*,tkv2-*`
(19/19), and full root `test`.

## 2026-07-08 — v2: frontend bridge conformance toolkit gate is green

The remaining v2 `V2ConformanceTest` blockers from the root gate are fixed.
Curried vararg first clauses such as `cardWithHeader(header)(body*)` no longer
wrap the header in a list, restoring `std-ui-jobpanel`, and real
`ForeignV(ArrayBuffer)` arrays now dispatch through read-only collection methods
such as `sum` and `mkString`. Gates: `std-ui-jobpanel`, `tkv2` (6/6),
`array-companion-statics` affected conformance (INT+JS+JVM), and full
`v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest` (76 succeeded,
54 ignored, 0 failed).

## 2026-07-08 — v2: PWA plugin routes run through the bridge

`tkv2-pwa` now passes on the v2 frontend bridge. The bridge classpath includes
the existing `pwaPlugin`, `pwa(...)` named args/defaults lower positionally, and
plugin-owned `ctx.registerRoute(...)` calls register with the real v2 web server
route registry. Gates: `V2ConformanceTest -z tkv2-pwa`, `V2ConformanceTest -z
tkv2` (6/6), and conformance `tkv2-pwa` (INT pass; JS/JVM skipped by metadata).

## 2026-07-08 — v2: ReactiveSignal method objects expose get/set

v1 `ReactiveSignal` values round-tripped through the v2 plugin bridge as
`NamedMethodObj`s with only `apply`, so `sig()` worked but `sig.set(v)` failed
with `__method__: no field 'set' on named-method-obj`. The wrapper now exposes
`get`/`set` and writes raw host values, restoring toolkit state updates.
Targeted V2 gates: `tkv2-busi-home`, `tkv2-forms`, and `tkv2-offline` green;
affected conformance for the same three cases is 3/3 green across INT+JS.

## 2026-07-08 — std/os: OS plugin uses the stable plugin error surface

`runtime/std/os-plugin` no longer imports interpreter internals from its
value-surface intrinsics. The invalid `exit(...)` fallback now raises through
`PluginError`, keeping stable SPI enforcement green; the old NUL arg separator
literal was normalized to `"\u0000"` for text-safe future diffs. Gates:
`StableSpiEnforcementTest` 2/2 green, `OsPluginTest` 14/14 green, conformance
`std-process-import` 1/1 green.

## 2026-07-08 — cli: verify bounds default source scans for custom artifact dirs

`ssc verify <artifact-dir>` no longer defaults source freshness lookup to the
artifact directory's parent for arbitrary custom output dirs. The parent lookup
is kept only for conventional `.ssc-artifacts` outputs; otherwise implicit
lookup stays inside the artifact dir and callers can still pass `--src-dir`.
This removes the root-test slowdown where tiny temp verify cases recursively
walked the whole temp parent. Gates: `VerifyCliTest` 8/8 green; conformance
`std-process-import` 1/1 green.

## 2026-07-08 — std/ui: rowPostAction surfaces non-2xx errors (was silently swallowed)

_RowPost read the response but only ever bumped the tick — a guarded refusal (e.g. a gateway stop
returning 409 "N client(s) attached; stop them first") re-rendered as if the button did nothing, the
classic "нажимаю — ничего не происходит". Now check res.ok: on a 2xx bump the tick as before; on a
non-2xx parse {error} from the body, alert it, restore the button, and DON'T bump (nothing changed).

---

## 2026-07-08 — v2: busi conformance target GREEN — 61/61 tests on --v2

The full busi tests/v2 suite now passes on the v2 engine (was 47/61 after OpAnf; 0/61 at the day's
start). Seven root causes (BUGS.md `v2-busi-testsweep-gaps` batch): top-level vars referenced from
defs were split across two cells (assignments from defs vanished); tryFBc's UNGUARDED ==/!= made
string equality of two locals always true inside if-conditions (period filters matched everything);
list HOFs collected raw effect Ops from performing lambdas (now mapThreadOp/foldThreadOp defer the
traversal into the op's continuation); Array.fill/tabulate returned lists (now real ArrayBuffers with
indexing/length dispatch); the tolerant 0L length FastCode emptied `while i < xs.length` loops (now
honest + errors on unknown receivers); the all-fences regex matched mid-line fence opens inside
string literals (now line-anchored); OpAnf's letify displaced literal op-names, demoting arith to the
weaker table dispatch (pure args now stay in place; Map+(k->v) added to the table; unification
queued); content section/block/data lookups fall back to imported documents (v2 inlines imports under
the entry document). New conformance regressions: var-topdef-shared, string-eq-locals. Gates: corpus
153/9 = baseline, conformance run.sh 125/125, benches at/below baseline.

## 2026-07-08 — v2: Op-argument lifting (OpAnf) — strict consumers defer unresolved effect Ops

Third and final leak in busi's --v2 ledger chain: a strict call (`formatMoney(...)`, `println`,
Ctor fields, match scrutinees, if conditions) receiving an unresolved effect `Op` consumed it as a
raw value. Runtime lifting would break the Mira/hm kernel lane (Op values are legitimate fn args
there), so the fix is `OpAnf` — a bridge-only CoreIR pass Let-binding may-be-Op argument positions;
the kernel's existing Let/Seq threading does the deferral (de Bruijn cutoff-shifting; `handle(expr)`
paren-form excluded; gated to sources mentioning effect/handle so effect-free hot loops pay zero —
ungated, pattern-match-heavy ran 3-4× slower; gated, all benches at baseline, effect-multishot
5.19 ≈ 5.04). busi tests/v2/ledger.ssc: ALL OK on --v2. Corpus 153/9 = baseline; conformance v2
batch 109/39 — `js-applyunary-effect-cps` flipped to PASS. Companion: the `args` global was
shadowed by a bridged native fn (`args.length` → `.length on <closure>`, masked by the length
FastCode's tolerant `0L`) — the value list now registers post-plugins from `Runtime.argv`.

---

## 2026-07-08 — std/ui: emit-spa wires incSignal (↻ refresh buttons were dead)

The button-handler serialization in the emit-spa mount had cases for _ToggleSignal/_SetSignal/
_InputChange/_FetchAction but NONE for _IncSignal — so `actionButton(incSignal(sig))` produced a
button with no data attribute and no click listener: every incSignal button (↻ refresh across an
emit-spa app) did nothing. Fix: emit `data-ssc-inc="<id>"` and a mount handler wiring click →
`_set(id, (sv|0)+1)`, mirroring _SetSignal. (Interpreter/server-render path already worked via
EventHandler.IncrementSignal; this was the emit-spa gap only.)

---

## 2026-07-08 — test: v1 cluster fixture nodes stay on v1 under v2 default

Cluster CLI integration tests now start their actor-cluster node fixture
subprocesses with explicit `--v1`, while the `ssc cluster ...` CLI subcommands
still run normally against those nodes. This restores the root `sbt test` cluster
family after the v2 default switch: the tests are v1 actor-cluster runtime tests,
and v2 actor timer parity is tracked separately as
`v2-actors-sendafter-cli-default-noop`. Targeted gates: expanded cluster
`cli/testOnly` slice is **13/13 green**; affected conformance
`actors*,cluster-connect,distributed*` is **14 passed, 0 failed**.

---

## 2026-07-08 — fix: Option.orElse extensions handle non-Option defaults

Interpreter `Option.orElse` now applies the built-in method only when the
alternative is Option-valued. Calls such as `Some(42).orElse(0)` can now dispatch
to a user extension `orElse(default: A): A` instead of returning `Some(42)`, while
the built-in `n.orElse(Some(99))` behavior remains intact. Targeted gates:
`backendInterpreter/testOnly scalascript.SealedExtensionDispatchTest` is **4/4
green**; affected conformance slice
`option,optional,typeclass-extension,std-functor-applicative-monad,std-monaderror`
is **5/5 green** on INT/JS/JVM.

---

## 2026-07-08 — fix: version command classified in help output

`ssc version` / `--version` now uses the existing `Help` command category instead
of the fallback-style `Other` bucket. This restores `CommandRegistryTest` as a
gate that catches visible commands without explicit help grouping. Targeted gate:
`cli/testOnly scalascript.cli.CommandRegistryTest` is **8/8 green**; affected
conformance slice `std-semigroup-monoid` is **1/1 green**.

---

## 2026-07-08 — v2: multi-arg effect ops reach handler arms unpacked + cwd-independent std/ imports

busi's first `--v2` blocker (rozum seq31): `Journal.append(scope, fact)` died with
`match: no arm for append/2` — the effect dispatch packed multi-arg payloads into one TupleN, so
handler arms (`case op(a, b, resume)` = op/3) never matched. Now packed as internal `__EffArgs__`
and unpacked by `runEffectLoop` (2ef288004; a genuine single-tuple argument stays op/2). Companion
(d2340f85e): std/ imports on the v2 lane now fall back to `libPath/runtime/<path>` like v1 — the
assembled jar run from another project's cwd no longer leaves `std/money.ssc` unbound. Regression:
`tests/conformance/effect-multiarg-op.ssc` (imported handler, 2-arg + 1-arg ops), green INT/JS/JVM +
v2; examples corpus at the 153/9 baseline. Discovered and queued: `v2-op-arg-lifting` (SPRINT — still
blocks busi's ledger at check #2) and `v1-jvm-state-threaded-handler-codegen` (BACKLOG).

## 2026-07-08 — std/ui: rowPostAction — immediate click feedback + skip empty-body buttons

Two browser-runtime UX fixes for `_RowPost` (no API change): (1) on click, disable the button and
append '…' immediately (restore on a failed fetch; a 2xx re-renders the table and clears it) — a
slow POST (loading a model) no longer looks dead and invites a double-tap; (2) render nothing when
the row's body resolves empty — a rowPost that would POST nothing is never intended, and this lets a
table show a per-row conditional action (e.g. load XOR unload, one field empty per row) with no
hidden column or CSS hack. Interpreter/JS intrinsic arity unchanged; conformance green.

---

## 2026-07-08 — fix: Scala.js npm test deps install automatically

Scala.js test modules with `package.json` no longer depend on a manual
`npm install` before `sbt test`. The build now runs idempotent `npm ci` before
`loadedTestFrameworks` for the npm-dependent JS suites. Targeted gates:
`cryptoNobleJs/test`, `walletVaultEncryptedJs/test`, `walletStrategyErc4337Js/test`,
`blockchainEvmAbiJs/test`, `walletConnectJs/test`, and `markupNode/test` are green.

---

## 2026-07-08 — fix: split JVM runtime emits serve stubs

`compile-jvm --bytecode` no longer fails compiling shared `_ssc_runtime.scala`
for no-server artifacts. Split `JvmGen.generateRuntime` now mirrors the
self-contained JVM path by emitting `stubServeRuntime` whenever the `Serve`
capability is absent. Targeted gate: `cli/testOnly *EmitScalaFacadeCliTest`
is **7/7 green**.

---

## 2026-07-08 — conformance: default gate green

The full default conformance gate now reports **122 passed, 0 failed out of
122 tests (+2 pending)** via `tests/conformance/run.sh --no-memo`. The remaining
pending cases are intentional environment gates (`http-client`,
`sql-browser-basic`); no deterministic conformance failures remain in
`green-main-conformance-gating`.

---

## 2026-07-08 — conformance: INT Semigroup via Monoid givens fixed

`std-semigroup-monoid` now passes INT/JS/JVM. The interpreter registers parent
typeclass aliases for givens, so `Monoid[Int]` evidence also satisfies
`Semigroup[Int]` when `Monoid extends Semigroup`. Targeted gate:
`tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo` is **1/1
green**.

---

## 2026-07-08 — fix: JVM `.scjvm` cache invalidates old codegen

Source-fresh `.scjvm` artifacts now carry a JVM `codegenVersion` cache key, so
`run-jvm` regenerates artifacts emitted by older JVM backend code instead of
reusing stale generated Scala after compiler/runtime fixes. Targeted gate:
`tests/conformance/run.sh --only 'std-ui-aggregator,std-ui-extended*' --no-memo`
is **5/5 green**.

---

## 2026-07-08 — conformance: INT while assignment order fixed

`variables` now passes INT/JS/JVM. The interpreter's closed-form while optimizer
now skips order-dependent accumulator folds when the accumulator reads a counter
assigned earlier in the same loop body, preserving sequential assignment
semantics. Targeted gate:
`tests/conformance/run.sh --only 'variables' --no-memo` is **1/1 green**.

---

## 2026-07-08 — conformance: JVM std-ui generated braces fixed

`std-ui-aggregator` and `std-ui-extended*` now pass INT/JS/JVM. JVM codegen now
keeps braces inside triple-quoted JavaScript/CSS literals out of structural
object/package merging, so imported UI component objects such as `SubmitButton`
stay properly closed. Targeted gate:
`tests/conformance/run.sh --only 'std-ui-aggregator,std-ui-extended*' --no-memo`
is **5/5 green**.

---

## 2026-07-08 — conformance: std typeclass aggregate slice fixed

`std-functor-applicative-monad`, `std-foldable-traversable`, `std-index`,
`std-bifunctor`, `std-monaderror`, and `std-selective` now pass INT/JS/JVM.
INT dispatch no longer recurses through same-named imported extensions when a
built-in member exists, and JVM codegen now preserves std typeclass re-export,
extension-import, type-hoist, and explicit `(using ...)` call semantics. Targeted
gate:
`tests/conformance/run.sh --only 'std-functor-applicative-monad,std-foldable-traversable,std-index,std-bifunctor,std-monaderror,std-selective' --no-memo`
is **6/6 green**.

---

## 2026-07-08 — fix: rowLink selection checkmark was mojibake ("¹3")

`_ssc_ui_ensureRowlinkCss` built `content:"\2713 "` inside a JS STRING literal, where `\271` is a
legacy OCTAL escape (→ U+00B9 '3' = "¹3"), so selected rowLink buttons showed "¹3 <label>" instead
of a ✓. Fixed by using the literal ✓ character (emits clean through the pipeline, same as ★).

---

## 2026-07-08 — conformance: INT SQL block scope survives IR round-trip

`sql-basic` and `sql-transaction` now pass the INT conformance lane. The CLI
`Normalize -> Denormalize` backend path re-parses parseable embedded ScalaScript
blocks, so SQL bind expressions can see globals defined by preceding `scala`
fenced blocks. Targeted gate:
`tests/conformance/run.sh --only 'sql-basic,sql-transaction' --no-memo` is
**2/2 green**.

---

## 2026-07-08 — conformance: JS product rendering hides internal tags

`prisms`, `optic-polish`, `optics-index-at`, and `optional` now pass
INT/JS/JVM in `green-main-conformance-gating`. The JS runtime keeps `_tag` for
pattern matching, but no longer renders it as a product field or consumes it as
a positional `.copy(...)` slot. Targeted gate:
`tests/conformance/run.sh --only 'prisms,optic-polish,optics-index-at,optional' --no-memo`
is **4/4 green**.

---

## 2026-07-08 — conformance: JS std/json intrinsic targets fixed

`json-read` now passes INT/JS/JVM in `green-main-conformance-gating`. The JS
backend routes bare `jsonStringify` / `jsonValue` intrinsic calls to the
renamed `_ssc_ui_*` runtime helpers, preserving the existing std import
duplicate-declaration fix while avoiding undefined globals. Targeted gate:
`tests/conformance/run.sh --only 'json-read' --no-memo` is **1/1 green**.

---

## 2026-07-08 — conformance: JVM CPS cluster/distributed slice fixed

The remaining deterministic JVM-only conformance failures in
`green-main-conformance-gating` now pass. JVM CPS codegen preserves typed
continuations for known constructor/def results, emits nested effectful call args
through CPS, and no longer casts local effectful `Unit` actor loops before
`runActors` can schedule them. Targeted gate:
`tests/conformance/run.sh --only 'cluster-connect,distributed-failure-*,distributed-heterogeneous,distributed-shuffle,effect-transitive-handler' --no-memo`
is **6/6 green**.

---

## 2026-07-08 — conformance runner is now bloop-serverless (tests/conformance/run.sh)

The conformance runner was the last one still documented as `scala-cli tests/conformance/run.sc`,
which spawns a persistent bloop daemon (PPID 1, multi-GB, never exits) whenever the interactive
`--server=false` shell wrapper isn't in effect — non-interactive shells and other agents. Added
`tests/conformance/run.sh` (`exec scala-cli --server=false run.sc`), mirroring the existing
`bench.sh` fix (bloop-serverless-scripts), and pointed AGENTS.md at it. Transparent wrapper —
identical PASS output — but never leaves a bloop daemon behind.

---

## 2026-07-08 — conformance: actors/effects INT lane fixed

`actors-supervision` now passes INT/JS/JVM after the interpreter preserves the
core actor `exit(pid, reason)` native when os-plugin registers `exit(code)`.
`effects` now declares `Choose` as `multi effect`, matching its nondeterminism
handler and the algebraic-effects spec.

---

## 2026-07-08 — conformance: external httpbin fixture pending

`tests/conformance/http-client.ssc` is now pending by default because it depends
on live `https://httpbin.org`; the current run returned 503s and stalled in the JS
lane. The fixture remains documented, with a follow-up to replace it by a local
deterministic HTTP test before re-enabling it in default conformance.

---

## 2026-07-08 — std/parsing recovery conformance fixed

`std/parsing/recovery.ssc` now exports its documented recovery extension methods
and runner helpers (`recoverUntil`, `errorNode`, `parseAll`, `advanceToSync`,
`runParserAll`). The INT-only `parsing-error-node`, `parsing-parse-all`, and
`parsing-recover-until` conformance cases now pass instead of failing before
stdout during import.

---

## 2026-07-08 — v2 production: `ssc run` defaults to v2

Plain default-lane `ssc run <file>` now runs on the v2 VM through FrontendBridge.
`ssc run --v1 <file>` is the explicit rollback path for the v1 tree-walking
interpreter, while explicit target/backend/frontend/server lanes keep their existing
specialized behavior. The v2 output-parity and conformance INT harnesses now use
explicit `--v1` for the v1 side. Full production parity remains
**60/81 identical · 5 mismatch · 0 v2-error · 16 v1-only**.

---

## 2026-07-08 — JS runtime: boxed char ordering in conformance DSL fixed

`dsl-multi-pass` now passes the JS conformance lane. JS `String.forall` predicates
compare boxed `_Char` values against char-literal strings by code point for ordering
operators, preserving normal string concatenation and ordinary string comparison.
The unrelated INT-only `parsing*` empty-output failures found during neighbor
verification are tracked separately in `BUGS.md` / `SPRINT.md`.

---

## 2026-07-08 — v2 production: corpus scope classified

The full output-parity gate was re-run from the corpus-scope worktree and reproduced
**60/81 identical · 5 mismatch · 0 v2-error · 16 v1-only** across 195 examples.
The remaining Spark/backend/server/distributed/external-credential buckets are now
documented as lane-specific rather than default-runner blockers. `v2-prod-default-switch`
is unblocked by corpus scope.

---

## 2026-07-08 — v2 production: rozum schema and streaming parity fixed

`rozum-agent-schema-derived.ssc` and `rozum-agent-streaming.ssc` now match v1
under `ssc run --v2`; all four self-contained rozum agent examples are green in
the output-parity gate. FrontendBridge now preserves positional constructor args
when named args are mixed in, and the v2 runtime dispatches
`AgentSchemaInstance.decode`. Full output parity is now **60/81 identical ·
5 mismatch · 0 v2-error · 16 v1-only** across 195 examples.

---

## 2026-07-08 — v2 production: quoted macro interpreter parity fixed

`quoted-macro-interpreter.ssc` now matches v1 under `ssc run --v2`, including
computed interpreter-only macro bodies using `Expr.asValue.getOrElse(...)` and
`Expr.asTerm.name`. The v2 bridge registers the restricted quoted-macro helper
globals/methods for run-path parity and pre-records `using` metadata for forward
macro helper calls. Full output parity is now **58/81 identical · 7 mismatch ·
0 v2-error · 16 v1-only** across 195 examples.

---

## 2026-07-08 — v2 production: content toolkit section parity fixed

`content-toolkit-yaml-controls.ssc` and `content-slot.ssc` now match v1 on v2.
The fix wires v2 plugin global callback invocation for real content-toolkit lowering
and teaches FrontendBridge to desugar list literals after spaced infix operators.
Full output parity now has **0 v2-error** cases and measures **57/81 identical ·
8 mismatch · 16 v1-only** across 195 examples.

---

## 2026-07-08 — v2 production: post-p3 parity baseline refreshed

After the real v2 web-server / rozum runner work and stable invoice-email output,
the full `scripts/v2-output-parity --all` gate now measures **55/85 identical ·
9 mismatch · 1 v2-error · 20 v1-only** across 195 examples. The remaining v2-error
is isolated to `content-toolkit-yaml-controls.ssc`; `content-slot.ssc`,
`quoted-macro-interpreter.ssc`, and two rozum variants are the next production
parity decisions/fixes.

---

## 2026-07-08 — v2 production: invoice email output stabilized

`examples/invoice-email.ssc` no longer prints the exact generated MIME message byte
count. It still renders the PDF and builds the MIME message, then prints a stable
semantic line (`MIME message assembled: PDF attached`) once the message is non-empty.
This removes the transient parity mismatch class where incidental generated artifact
lengths differed while the v1/v2 behavior was otherwise equivalent.

---

## 2026-07-08 — std/ui: serve(view, port, extraCss) — app-supplied page CSS

`serve`'s extern gained an optional `extraCss: String = ""` third arg, appended to the base
template LAST (so it wins). The js-runtime already threaded a 3rd extraCss arg + a `${extraCss}`
template slot — only the extern hid it, so no .ssc app could override the base template's hardcoded
`body{background:#fff}`. A named overload was rejected (two `const serve` → duplicate-identifier in
the JS extern shim); one defaulted extern keeps `serve(view, port)` working. First consumer: rozum
UCC drops its post-emit `sed` patch of the body background. Conformance unchanged (2-arg form).

---

## 2026-07-08 — v2 production: dataset parallel sum no longer stack-overflows

`Prims.unlistPub` and `listOf` in the v2 runtime are now iterative, so large
ScalaScript lists no longer blow the JVM stack while crossing plugin/runtime
boundaries. This fixes `examples/dataset-parallel-sum.ssc`: full parity now has
zero v2-error cases, and the affected dataset conformance slice is 15/15 green.

---

## 2026-07-08 — v2 production: content structured-block parity

`ssc run --v2` now seeds content document/current-section context and imported content
module documents through the v2 FrontendBridge/PluginBridge path. This closes the
production parity cluster for `content-linked-namespaces`, `content-tables`, and
`content-to-markdown`; full output parity moved from 51/88 to 54/88 identical. The
bridge keeps only `contentToolkitSection` as a selective batch stub until section-level
toolkit lowering is fixed.

---

## 2026-07-07 — std/ui: selection-aware rowLink pickers + onOpenJson effect

Two phone-driven polish items from the rozum UCC: (1) datatable `_RowLink` buttons now mark the
SELECTED row — ✓ via CSS `::before` + accent when the bound signal equals the row value (`::before`
is not a text node, so i18n text-walkers keep translating labels); (2) new onSuccess effect
`onOpenJson(urlTemplate, field)` — on a 2xx the runtime parses the response body as JSON and
`location.href`s the templated URL (`:value` ← field), letting a launch button open the resource it
just created (UCC: straight into the session terminal). `jobPanel` gains a `launchEffects` param.
Tests: SpaOpenJsonEffectTest (real runtime, headless node); std-ui-jobpanel conformance still green.

---

## 2026-07-07 — std/ui: patterns.ssc — jobPanel, the async-job toolkit expression

New composite layer `std/ui/patterns.ssc`: patterns compose the widget layer into whole app
sections. First entry `jobPanel(st, refresh, …)` — the async-job launch/observe pattern extracted
from the rozum UCC: a registry table whose rows carry a server-maintained status
(starting…/running/failed:…/exited) with a per-row ✕ close (`rowPost`), plus a launch form whose
POST returns instantly and bumps the shared refresh tick (the new "starting…" row is the click
feedback; failed rows stay visible until closed). rozum's three job kinds (tmux sessions, chat
agents, batch coders) are three calls. Conformance: std-ui-jobpanel (INT+JS, node-tree shape).

---

## 2026-07-07 — std/ui: formBody by-name field signals fixed in the SPA bridge

`_ssc_ui_signal(name, init)` discarded the user-facing name while `formBody([("k","sigName")])`
references field signals by NAME — the submit-time `_sv` lookup (numeric-id-keyed) resolved nothing
and every such POST sent empty values (found live: rozum UCC session-launch posted
`{"agent":"","model":"","workdir":""}` → 400 with the form visibly filled). Fix: `_signalsByName`
registry + `_ssc_ui_resolveFormFields` — the render walk resolves field refs to bridge ids and
collects the signals so their `_sv` entries stay fresh; unresolved refs pass through verbatim
(sv-by-name runtimes). Regression test: SpaFormBodyNamedSignalsTest (real runtime, headless node).

---

---

## 2026-07-07 — fix: plugin-lazyload-extern-imports — advanced plugins reachable again

The essential/advanced `.sscpkg` split kept startup fast but left the advanced set
(`plugin-available/`: smtp, tcp, pwa, sql, auth, crypto, …) wired into NO load path — every std
module backed by them was dead from `.ssc` (imports failed with "not found", bare externs with
"Undefined"; the stock pwa-demo example crashed). Fix: the CLI registers the available dirs at
startup and the interpreter's lazy `ensurePluginsLoaded()` commits them on first missing
name/extern — startup unchanged, correctness restored. Unblocks busi's ssc pin bump (its hub
imports `[smtpSend](std/smtp.ssc)`). tkv2-pwa conformance un-pended and green.

## 2026-07-07 — std/ui: tkv2-theme-css-vars — the theme as CSS custom properties

`cssVariables(t: Theme)` in `std/ui/theme.ssc` — emits `:root { --ssc-color-*, --ssc-space-*,
--ssc-font-*, --ssc-radius-* }` so one ssc Theme value drives both the toolkit and any hand-kept
CSS (busi's instrument-panel identity without a parallel theme.css). Conformance
`tkv2-theme-css-vars` INT==JS.

## 2026-07-07 — std/ui: stackedColumn — two-line datatable cell

`stackedColumn(title, fieldPath, subFieldPath, align)` in std/ui/primitives.ssc + a `'stacked'`
column kind in the js-runtime datatable renderer: the main field value with a smaller, dimmer
sub-line beneath (empty sub → plain one-line cell). Additive; no existing kinds touched. First
consumer: rozum UCC model pickers (model name with its ★ matrix rating underneath).

---

## 2026-07-07 — v2: PR #72 (t44-conformance) integrated — repo fully de-orphaned

Salvage-merged `feature/v2-t44-conformance` (PR #72), the last branch holding unlanded work. Its
summon/using layer proved to be a parallel earlier implementation of main's landed dict-passing
(`__resolve_given__`); main won all overlapping hunks and the branch's DataV-based optics were
stripped as dead code (main's optics live in PluginBridge). Genuinely new pieces kept: String
`indexOf`/`lastIndexOf` char+from overloads, `matchPrefix`, char-predicate `filter/forall/exists`,
`__match_fail__` prelude def + prim (the global was UNBOUND on main — a failed match crashed with an
opaque unknown-global error), batch-path `V2EffectContext.peek` alignment, Show pretty List/Tuple.
Gate: V2ConformanceTest identical to pure origin/main (63 pass / 3 pre-existing tkv2 fails);
v2PluginBridge 22/22. Also landed `examples/control-center-live.ssc` (347-line rozum control-center
PWA, `ssc check` OK) from the `wip/control-center-live` parking branch. Both branches deleted —
origin now carries no work that is not on main.

## 2026-07-07 — std/ui: tkv2-busi-home-conformance — the integration bar

Sixth slice of `specs/ssc-toolkit-v2.md`. `tests/conformance/tkv2-busi-home.ssc` exercises
components + offline + forms together in busi's home-screen shape (obligation cards with
instance-scoped expand, income form with pattern validators and a live validity gate, persisted
home payload, online flag) — INT==JS. Browser twin `examples/frontend/busi-home-demo` driven over
emit-spa. Fixed in `std/ui/form.ssc` en route: computed thunks must close over LOCAL bindings of
module functions (the module global-resolution trap bit deferred invocation from another module).

## 2026-07-07 — std/pwa: tkv2-pwa-adopt — offline-first PWA parameters

Fifth slice of `specs/ssc-toolkit-v2.md`. `std/pwa.ssc` gains `cacheVersion`, `networkFirst`
(network-first read routes with cache fallback — the busi pattern: reads work offline, writes are
never cached), `offlineHtml` (navigation fallback page) and `maskableIcon`. Covers everything busi
hand-writes in `http/pwa.ssc`. Verified by `PwaPluginTest` (4/4). Found en route: lazy-loaded
plugin externs (smtp/tcp/pwa) are unreachable from `.ssc` on main — filed as
`plugin-lazyload-extern-imports` (open, BUGS.md; the stock pwa-demo example fails); the
`tkv2-pwa` conformance case is `pending:` on it.

## 2026-07-07 — std/ui: tkv2-spa-pipeline — production SPA path audited + documented

Fourth slice of `specs/ssc-toolkit-v2.md` (docs/audit). `ssc emit-spa --frontend custom` confirmed
fully self-contained (no CDN/external tags; the http(s) strings in the bundle are inert jwt-auth
endpoint constants — noted as a tree-shake candidate). The decision "JsGen + framework-free runtime
= THE production path; react/vue/solid emit-spa flavors stay demos" is now in user-guide §17.9.

## 2026-07-07 — std/ui: tkv2-forms — validators as data, live errors, validity gating

Third slice of `specs/ssc-toolkit-v2.md`. New `std/ui/form.ssc`: `FieldSpec` data-DSL
(required/minLength/maxLength/pattern — `validateField` is pure, so the same rules run in the
browser, server-side, and in tests), `form(ctx, specs)` (drafts = component-scoped signals),
`fieldError`/`formErrors`/`formValid` computed signals, `formField`/`submitGate` widgets. Enablers
landed with it: `String.matches` on the JS lane (anchored full-match, guard string-matches
INT==JS==JVM) and read-freshness for interp `computedSignal`/`eqSignal` (recompute on read — JS
parity, so reactive derived state is conformance-testable). Verified: conformance `tkv2-forms`
INT==JS; `examples/frontend/form-demo` browser-driven (errors appear/clear as you type; the real
Submit swaps in only while the form validates). Deferred: touched-state, submit busy/error
tri-state (needs an onFailure fetch effect).

## 2026-07-07 — std/ui: tkv2-offline — localStorage, onlineSignal, persistedSignal

Second slice of `specs/ssc-toolkit-v2.md`. New `std/ui/offline.ssc`: `localStorageGet/Set/Remove`,
`onlineSignal()` (navigator.onLine + events in-browser; constant true off-browser), and
`persistedSignal(name, default)` (initializes from storage, writes every change back — state that
survives a reload). JVM lowering = per-process map, so the same logic is testable server-side.
Plus: interpreter dispatch for programmatic `sig.get()`/`sig.set(v)` on ReactiveSignal (JS-lane
parity) — ui-signal behavior is now conformance-testable INT==JS. Verified: conformance
`tkv2-offline` INT==JS; `examples/frontend/offline-demo` driven in a real browser over the
emit-spa path (typing persists to localStorage, reload restores, offline badge flips live).
Gotcha caught by the browser (not by Node): persistence must subscribe via an effect — DOM/fetch
write signals through `_signalSet` by id, bypassing a wrapped `.set`.

## 2026-07-07 - fix(v2): mcp-types std def shadowing

Fixed the `v2FrontendBridge` `mcp-types` blocker by letting real imported
`.ssc` definitions shadow same-named plugin globals; `std/mcp/types.ssc`
`requireString` now throws `McpError` instead of calling the `validate {}`
helper. Also renamed the conformance fixture local `args` to `mcpArgs` to avoid
the already tracked JS preamble-name collision. Verified full
`v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest` (62/62) and
`scripts/conformance -- --only mcp-types --no-memo` (INT/JS pass).

## 2026-07-07 - fix(test): PluginBridge raw NativeImpl args

Fixed the `v2PluginBridge` root-suite blocker by updating the stub backend test
to match the stable SPI bridge contract: scalar v2 args are unwrapped before
`NativeImpl` and raw scalar returns wrap back into v2 values. Verified
`v2PluginBridge/testOnly ssc.bridge.PluginBridgeTest` (22/22).

## 2026-07-07 — std/ui: tkv2-components — instance-scoped component signals

First slice of `specs/ssc-toolkit-v2.md` (busi's React→ssc migration requirements). New
`std/ui/component.ssc`: `component(kind, key)(Ctx => N)` + `ctxSignal` namespace signals to
`<kind>__<key>__<name>` (sanitized to the emitters' JS-identifier contract), `childCtx` nesting;
pure .ssc over the existing `signal` extern. Two JsGen bugs found + fixed en route (BUGS.md):
the opaque-`Signal`-import vs signals.mjs-preamble collision (#5-class, declaredBindings seed)
and reserved-word params (`default`) unrenamed in object-member def bodies. Conformance
`tkv2-component` INT==JS; example `examples/frontend/component-demo` browser-driven (three
counters, clicks stay per-instance); full-corpus A/B vs main baseline — no failures caused by
this slice (the 4 JVM-lane diffs turned out to be the pre-existing `jvmgen-block-call-empty-parens`
regression on origin/main, masked by the main checkout's stale bin/ssc — see BUGS.md).

## 2026-07-07 - fix(test): AgentConformance ephemeral port

Fixed the `AgentConformanceTest` root-suite port collision by binding the mock
OpenAI gateway to loopback port `0` and using the assigned port in test scripts.
Verified `backendInterpreterPluginTests/testOnly scalascript.AgentConformanceTest`
(3/3).

## 2026-07-07 - fix(test): Swing example repo-root resolution

Fixed `JvmGenSwingRuntimeTest` after the v1 path restructure: the suite's local
repo-root finder stopped at `v1/` and looked for examples under `v1/examples`.
It now uses `TestPaths.repoRoot` and reads the real root `examples/` tree. Verified
`backendInterpreter/testOnly scalascript.JvmGenSwingRuntimeTest` (5/5).

## 2026-07-07 - fix(tcp): stable plugin value surface

Migrated `tcp-plugin` intrinsics off direct `scalascript.interpreter.Value`
constructors and onto `PluginValue`, restoring the stable SPI enforcement gate.
Verified `backendInterpreterPluginTests/testOnly scalascript.StableSpiEnforcementTest`
and `tcpPlugin/test`.

## 2026-07-07 - test: WebAuthn auth-plugin intrinsic parity exceptions

Fixed `CrossBackendIntrinsicParityTest` by documenting
`webauthnConfigureStore` and `webauthnStoreRemove` as JS-core/JVM-`auth-plugin`
registration-location exceptions. Verified
`backendInterpreter/testOnly scalascript.CrossBackendIntrinsicParityTest`.

## 2026-07-07 - fix(test): PluginCliTest os-lib shadow

Fixed the `sbt test` CI blocker in `PluginCliTest`: the test package
`scalascript.compiler.plugin` made unqualified `os.*` resolve to the local
`scalascript.compiler.plugin.os` package instead of os-lib. The test now uses
`_root_.os.*`. Verified `cli/Test/compile` and
`cli/testOnly scalascript.compiler.plugin.PluginCliTest` (8/8).

## 2026-07-07 — ops: Qwen3.6 rozum launch path checked

Checked the local `rozum` setup for starting a room session with Qwen3.6. The installed model is
`mlx-community:Qwen3.6-35B-A3B-4bit-DWQ`; the meeting daemon is already running, but no gateway is active.
The correct room flow is gateway on `8089` plus `rozum meetings participant --gateway-url
http://127.0.0.1:8089/v1`, then `rozum meetings attach --room <room>`. Dry-run currently refuses Qwen3.6
because available RAM is short by roughly 0.4 GiB even with `--n-ctx 4096 --min-free-ram-gb 0`; the small
`mlx-community:Qwen3-4B-4bit` model passes dry-run for smoke testing.

## 2026-07-07 — ci: align markdownlint policy with existing docs

Expanded `.markdownlint.json` to disable the additional markdownlint rules already
violated across historical board/spec/changelog documentation, instead of mass-reformatting
durable project history. Verified locally with
`npx --yes markdownlint-cli '**/*.md' --ignore node_modules`.

## 2026-07-07 — fix(crypto): BouncyCastle helper-name resolution

Fixed the CI compile blocker in `BouncyCastleBackend`: the wildcard `scalascript.crypto.*`
import shadowed the same-package JVM helper objects `ChaCha20Poly1305` and `X25519`, so
the backend tried to call portable-reference APIs that do not expose the JVM helper method
names. The backend now imports only the SPI types it needs, letting the same-package helpers
resolve correctly. Verified `cryptoBouncycastle/compile`, `cryptoBouncycastle/test` (55/55),
and `compile` + `cli/assembly`.

## 2026-07-06 — v2 conformance: html-dsl + rest-validate bridge (v2-conf-pure-gated)

Full HTML tag DSL registered in PluginBridge: `div`, `p`, `ul`, `li`, `a`, `h1`–`h6`, `em`, `strong`,
`nav`, `img`, `hr` + all container/void tags; `attr` NamedMethodObj with `cls`/`id`/`href`/`title`/…
fields + `:=` AttrKey operator; `raw(s)` for pre-escaped HTML; `_Raw` DataV v1Show pass-through.
REST-validate: thread-local error accumulator via `validate { }` + `requireString`/`requireRange`/
`requireRangeDouble`/`requireOneOf` helpers. Runtime fixes: `:=` infix dispatches via `NamedMethodObj`
in `__arith__`; tuple-spreading in `map`/`flatMap` for multi-param lambdas on tuple lists.
V2ConformanceTest: +2 (html-dsl + rest-validate off skipSet); suite 59→60/61. (PR #75)

## 2026-07-06 — v2 plugin-native bridging: filesystem builtins (parity +1)

`PluginBridge.registerFsBuiltins()` registers the filesystem builtins the v1 interpreter exposes via
`BuiltinsRuntime` (not as ServiceLoader `NativeImpl` intrinsics), which the loadAll loop skipped — so they
surfaced as `unbound global` on v2 (e.g. `mkdirs`). Bridged `mkdirs / mkdir / writeFile / appendFile /
readFile / deleteFile / exists / listDir`, each guarded by `isEmpty` so already-bound names are untouched.
`examples/fs-roundtrip.ssc` now produces byte-identical output on v1 and v2 (output-parity 27→28/52);
V2ConformanceTest still 59/59 green. (Option B of the plugin-native cluster; `validate` — a language special
form — and the html-dsl attr DSL remain, being genuine bridge/engine work, not native registration.)

## 2026-07-05 — v2 FrontendBridge + PluginBridge: batch-conformance fixes (T4.4)

Forward-ported T4.4 worktree fixes to origin/main (on top of waves 1-5):
- All string interpolators treated as string concat (`html"..."`, `sql"..."`, `f"..."`, etc.)
- Qualified ctor `fillDefaults`: `Transport.Http(8080)` fills default params same as unqualified ctors
- Object vals/methods emitted as top-level CDefs for intra-object references
- `Signal[T]` → callable `ClosV` in `v1ToV2` (fixes `app: not a function: <foreign>` on `ReactiveSignal`)
- `scope(name)`, `raw(s)`, `attr(name)(value)` stubs registered as globals

## 2026-07-05 — v2 migration: output-parity harness (`scripts/v2-output-parity`)

The real "does v2 replace v1?" gate: run each example on v1 (`ssc run`) AND v2 (`ssc run --v2`) and diff
stdout, reporting per-example MATCH / MISMATCH / V2-ERROR + a parity %. Unlike `scripts/v2-compat-coverage`
(exit-0), this measures actual output equality — a program can exit 0 on v2 while printing different output.
First sample (4 pure-language examples): **2/4 identical**, which immediately surfaced two real v2 output
divergences hidden by the exit-0 number — `algebraic-effects` (effects output shape) and
`custom-derives-mirror` (v1 prints the union `String|Int`, v2 widens to `Any|Any`). Point `$SSC` at an
assembled `ssc` to run the full 193-example corpus for the authoritative number.

## 2026-07-05 — v2 conformance: un-skip webauthn-server-verify (+1)

`webauthn-server-verify` was in the `V2ConformanceTest` skip-set (grouped under "network / external
services") but is actually pure crypto — it mints a challenge and returns `None` for garbage
registration/assertion input, no network. Verified it runs correctly on v2 via `ssc run --v2` and matches
`expected/` byte-for-byte (`true / reg-none / asr-none`), then removed it from the skip-set: `V2Conformance
Test` now runs and passes it. Conformance +1. Remaining plugin-gated tests split into pure-logic ones that
still need native bridging (rest-validate's `validate` special form, html-dsl's ~20 tag builtins + attr DSL)
and environmental ones (actors/cluster/distributed/coroutines/http/ws/tls) — both tracked in SPRINT.

## 2026-07-05 — v2 migration: `ssc run --v2` flag (Phase-3 preview mechanism)

Wired `ssc run --v2 <file.ssc>` in the CLI to route a v1 source through the v1 frontend → `FrontendBridge`
→ the ssc 2.0 VM, instead of the v1 tree-walking interpreter (new `RunV2.scala`; `cli` now `dependsOn`
`v2FrontendBridge`; additive flag — the v1 interpreter stays the default). This is the migration preview
mechanism the Phase-3 CLI switch will build on, and it makes v1-vs-v2 **output parity** checkable from the
normal CLI. Gate: `ssc run --v2 examples/hello.ssc` prints `Hello, World!`, byte-identical to the default.

**Finding it immediately surfaced:** `examples/algebraic-effects.ssc` exits 0 on v2 (so it counts as PASS
in the exit-0 coverage harness) but prints **different output** than v1 — a real effects-semantics gap that
the current 96.4% coverage number hides. Concrete evidence that the Phase-3 gate needs an output-equality
check, not just exit-0. Logged for the Track-4 conformance work.

## 2026-07-05 — crypto: Noise interactive matrix — NX / XN / KK / IN / IX

Added five more interactive Noise patterns to `Noise.scala`, covering the immediate-static (`I…`) and
both-pre-known (`KK`) cases: `NX` (responder auth only), `XN` (initiator auth only), `KK` (both statics
pre-known, mutual auth, nothing transmitted), `IN` (initiator sends its static immediately, in the clear),
and `IX` (mutual, initiator immediate). A data-driven test drives each handshake to completion and
asserts the matching transport keys plus the correct authentication outcome per pattern (who learns whose
static), JVM + Scala.js. The engine now ships 11 patterns: N / NN / NK / NX / XN / XX / XK / KK / IN / IK / IX.

## 2026-07-05 — crypto: more Noise patterns — N (sealed box), NK, XK

Added three more built-in Noise patterns to the `Noise.scala` engine: `N` (a one-way "sealed box" —
anonymous sender to a known, authenticated recipient, a single message), `NK` (interactive, responder
pre-known + authenticated, anonymous initiator), and `XK` (responder pre-known, initiator transmits +
proves its static for mutual auth). Reuses the validated pattern engine (pre-messages + the DH-token
handling). Functional gate per pattern: a full handshake with matching transport keys and the correct
authentication semantics (N/NK: anonymous initiator; XK: mutual), JVM + Scala.js. The engine now ships
N / NN / NK / XX / XK / IK.

## 2026-07-05 — crypto: Noise pattern engine — NN / XX / IK

Generalised the Noise handshake into a pattern-driven engine (`Noise.scala`, replacing the XX-only
`NoiseXX.scala`): CipherState + SymmetricState + HandshakeState with pre-message support and the full
`e s ee es se ss` token set, over the 25519/ChaChaPoly/SHA256 suite. Ships three built-in patterns — `NN`
(unauthenticated), `XX` (mutual auth), and `IK` (initiator pre-knows the responder static, WireGuard /
Lightning style). Pure Scala, JVM + Scala.js. Functional gate per pattern: a full handshake derives
matching transport keys, the authentication semantics hold, encrypted transport round-trips both ways,
and a tampered message fails authentication.

## 2026-07-05 — crypto: HChaCha20 + XChaCha20-Poly1305 (24-byte-nonce AEAD)

Extended `ChaCha20Poly1305.scala` with the HChaCha20 subkey function and XChaCha20-Poly1305
(`xseal`/`xopen`, 24-byte extended nonce; draft-irtf-cfrg-xchacha20poly1305) — the libsodium default AEAD.
Refactored the ChaCha20 permutation into a shared helper (the RFC 8439 block/AEAD KATs still pass byte-
exact). HChaCha20's words 0-3 match the draft §2.2.1 KAT and, since the permutation itself is RFC-8439-
validated, the full 32-byte subkey is correct; the AEAD round-trips and rejects tamper on JVM + Scala.js.
Unblocks the cipher half of PASETO v4.local (keyed BLAKE2b still pending).

## 2026-07-05 — crypto: Noise_XX handshake (25519 / ChaChaPoly / SHA256)

Added `NoiseXX.scala` to `payments/crypto/spi/shared` — a portable `Noise_XX_25519_ChaChaPoly_SHA256`
handshake (CipherState + SymmetricState + HandshakeState) composed entirely from the from-scratch X25519,
ChaCha20-Poly1305, HKDF-SHA256, and SHA-256. Pure Scala, JVM + Scala.js. The first full protocol assembled
on the portable primitive stack. Functional gate: a complete initiator↔responder handshake derives
matching transport keys, both parties authenticate the peer's static key (XX mutual auth), encrypted
transport round-trips in both directions, and a tampered handshake message fails authentication.

## 2026-07-05 — crypto: portable HKDF-SHA256 (RFC 5869)

Added `HkdfSha256.scala` to `payments/crypto/spi/shared` — HMAC-based extract-and-expand KDF over the
portable `HmacSha256`, with `extract` / `expand` / one-call `derive`. Pure Scala, JVM + Scala.js. Verified
byte-exact against the RFC 5869 vectors A.1 (salt + info) and A.3 (zero-length salt + info). The KDF used
by Noise, `age`, and TLS 1.3 — the last building block before those protocols can be assembled from the
portable X25519 + ChaCha20-Poly1305 primitives.

## 2026-07-05 — crypto: portable X25519 (Curve25519 Diffie-Hellman, RFC 7748)

Added `X25519.scala` to `payments/crypto/spi/shared` — a from-scratch X25519: the Montgomery ladder over
`BigInt` in `2^255 − 19`, with RFC 7748 scalar clamping + u-coordinate decoding, plus `derivePublicKey`
and `sharedSecret`. Pure Scala, identical on JVM + Scala.js, no platform crypto. Verified byte-exact
against the RFC 7748 vectors — §5.2 scalar multiplication (both) and the §6.1 Diffie-Hellman shared
secret. The last major missing primitive: with the portable ChaCha20-Poly1305, this unblocks the Noise
protocol and `age` encryption.

## 2026-07-05 — crypto: COSE_Encrypt0 (ChaCha20-Poly1305) — encrypted COSE

Added `CoseEncrypt0.scala` to `payments/crypto/spi/shared` — COSE_Encrypt0 (RFC 8152 §5.2, single
recipient / direct key) with alg 24 = ChaCha20-Poly1305, over the from-scratch `Cbor` + `ChaCha20Poly1305`.
The AEAD's associated data is the CBOR `Enc_structure` `["Encrypt0", protected, external_aad]` and the
12-byte nonce rides in the `{5:iv}` unprotected header. Encrypt/decrypt round-trip + tamper / wrong-key /
wrong-AAD rejection, JVM + Scala.js. COSE now covers both authenticity (COSE_Sign1) and confidentiality
(COSE_Encrypt0), completing the sign+encrypt pair on the portable stack.

## 2026-07-05 — crypto: portable ChaCha20-Poly1305 AEAD (RFC 8439)

Added `ChaCha20Poly1305.scala` to `payments/crypto/spi/shared` — a from-scratch RFC 8439 AEAD: the
ChaCha20 stream cipher (32-bit `Int` arithmetic), the Poly1305 one-time MAC (`BigInt` over `2^130 − 5`),
and the AEAD `seal`/`open` construction with a constant-time tag compare. Pure Scala, identical on JVM +
Scala.js, no platform crypto. Verified byte-exact against the RFC 8439 vectors — ChaCha20 block (§2.3.2),
Poly1305 (§2.5.2), and the AEAD tag (§2.8.2) — plus open round-trip and tamper (tag / ciphertext / AAD)
rejection. Keystone symmetric primitive: unblocks PASETO v4.local, Noise, `age`, and COSE_Encrypt.

## 2026-07-05 — crypto: portable did:key (Ed25519 + P-256) + base58btc

Added `DidKey.scala` and a portable `Base58` (btc/multibase-`z`) codec to `payments/crypto/spi/shared` —
encode a public key as `did:key:z…` and resolve it back, for Ed25519 (multicodec `0xed01`, `did:key:z6Mk…`)
and compressed P-256 (`0x1200`, `did:key:zDn…`), matching the W3C did:key registry prefixes. Pure Scala,
JVM + Scala.js. Tests: hand-verified base58 vectors (leading-zero preservation) + round-trip; did:key
prefix invariants + resolve round-trip + malformed-DID rejection. First slice of the did-vc epic; pairs
the identity layer with the JWS/COSE/WebAuthn keys.

## 2026-07-05 — crypto: WebAuthn assertion verification core (COSE_Key + ES256/EdDSA)

Added `WebAuthnVerify.scala` to `payments/crypto/spi/shared` — the portable crypto core of a WebAuthn /
FIDO2 login: parse the credential's **COSE_Key** (EC2/P-256 → ES256, OKP/Ed25519 → EdDSA) via `Cbor`, then
verify the assertion signature over `authenticatorData ‖ SHA-256(clientDataJSON)` (ES256 as ASN.1/DER via
`P256Ecdsa`, EdDSA raw via `Ed25519`). Pure Scala, JVM + Scala.js, no platform crypto. Tests: COSE_Key
decode + ES256/EdDSA round-trip + tamper (clientData/authData) and wrong-key rejection. This is the
capstone of the token-formats/COSE/P-256 line; registration/attestation + policy checks remain.

## 2026-07-05 — crypto: ES256 (ECDSA P-256) wired into JWS + COSE

`Jws.signES256`/`verifyES256` (+ `Jwt.es256`) and `CoseSign1.signES256`/`verifyES256` (COSE alg `-7`,
protected `{1:-7}`) in `payments/crypto/spi/shared` — ES256 = ECDSA P-256 + SHA-256 with the fixed 64-byte
R‖S, over the portable `P256Ecdsa`. The JWS path **verifies the published RFC 7515 Appendix A.3 ES256
token** (and the RFC private key derives its public key); COSE round-trips with an authenticated alg guard
(cross-algorithm confusion rejected), on JVM and Scala.js. Completes the ES256 unblock: `token-formats`
now covers JWS HS256/EdDSA/ES256K/ES256, PASETO v4.public, and COSE_Sign1 EdDSA/ES256K/ES256.

## 2026-07-05 — crypto: portable NIST P-256 (secp256r1) group + ECDSA reference

Added `P256Group.scala` + `P256Ecdsa.scala` to `payments/crypto/spi/shared` — a from-scratch NIST P-256
reference: short-Weierstrass group with `a = -3` Jacobian doubling, and ECDSA with RFC-6979 deterministic
nonces + SHA-256, low-S, DER and the fixed 64-byte R‖S encoding. Pure Scala, identical on JVM + Scala.js,
no platform crypto. Verified byte-for-byte against the BouncyCastle P-256 backend (public-key derivation
+ signature interop) plus the `d=1 → G` base-point vector. This is the `ES256` / WebAuthn signature
primitive — it unblocks ES256 for JWS/COSE (`Curve.P256` was BouncyCastle-only) and `webauthn-server-verify`.

## 2026-07-05 — crypto: COSE_Sign1 ES256K (ECDSA secp256k1)

`CoseSign1` gains `signES256K` / `verifyES256K` (protected header `{1:-47}`) in
`payments/crypto/spi/shared` — COSE_Sign1 now covers both EdDSA and ES256K (ECDSA secp256k1 + SHA-256,
fixed 64-byte R‖S) over the from-scratch portable secp256k1, reusing the `derToRaw`/`rawToDer` helper.
The protected `alg` header is authenticated, so cross-algorithm confusion (verifying an EdDSA message as
ES256K or vice-versa) is rejected. Structure + round-trip + AAD binding green on JVM and Scala.js.

## 2026-07-05 — crypto: JWS ES256K (ECDSA secp256k1) over the crypto SPI

Added the fixed-length signature conversion `Secp256k1Ecdsa.derToRaw` / `rawToDer` (DER ↔ 64-byte R‖S)
and `Jws.signES256K` / `verifyES256K` (+ `Jwt.es256k`) in `payments/crypto/spi/shared` — ES256K = ECDSA
secp256k1 + SHA-256 with the JOSE/COSE fixed 64-byte R‖S encoding, over the from-scratch portable
secp256k1 (JVM + Scala.js). The raw R‖S is byte-for-byte identical to the BouncyCastle secp256k1 backend
(both RFC-6979 deterministic + low-S). Fourth `token-formats` algorithm after HS256 / EdDSA (JWS) and
COSE_Sign1; ES256 (P-256) remains blocked on a portable P-256 reference.

## 2026-07-05 — v2 full-application demo: HTTP client + SQL end-to-end (T4.3)

`examples/v2-http-sql-demo.ssc`: proves that v2 can run programs combining HTTP client requests
(httpGet → status=200) with in-process SQL (H2 in-memory, 3 rows inserted and queried back).
Root fix: `__method__` dispatch for DataV singleton objects (Db, Http) now checks
`V2PluginRegistry.lookup("Tag.method")` *before* the effect-Op fallthrough path —
previously `Db.execute`/`Db.query` were silently returning lazy Free-monad Ops instead of executing.
Also fixed: FrontendBridge YAML `databases:` frontmatter parsing registers H2 JDBC connections;
v1→v2 InstanceV field ordering uses registered field-name declaration order for `Response.status`.

## 2026-07-05 — crypto: portable CBOR + COSE_Sign1 (EdDSA) over the crypto SPI

Added `Cbor.scala` (a minimal RFC 8949 codec — major types 0-6, definite-length) and `CoseSign1.scala`
to `payments/crypto/spi/shared`: portable COSE_Sign1 (RFC 8152/9052, single-signer) sign+verify with
EdDSA (`alg -8`) on the from-scratch Ed25519, with external-AAD binding and alg/tamper rejection.
Identical on JVM + Scala.js, no platform crypto. CBOR pinned to RFC 8949 Appendix A; COSE structure +
signature round-tripped under the RFC 8037 Ed25519 key. Third `token-formats` slice (after JWS + PASETO)
and the CBOR + COSE structures unblock `webauthn-server-verify`.

## 2026-07-05 — v2 Rust backend: LCell direct-ownership + inline arith (T3.4 complete)

LCell variables not captured by closures are now `let mut name: i64` instead of `Rc<RefCell<i64>>`,
eliminating all reference-counting and borrow overhead. lcell.get/set on these variables generate
direct i64 reads/writes. While-loop conditions use `genBoolExpr` (inline `i < N` instead of
`as_bool(v_ilt(...))`), and loop body uses `genStmt` to avoid V::Unit allocation in each iteration.
arith-loop benchmark: 100M iters v2=16ms vs v1-native=16ms (1.0×). T3.4 gate fully met.
All 8 fixtures × 3 backends green. Merge: 55be1ea94.

## 2026-07-05 — crypto: portable PASETO v4.public over the crypto SPI

Added `PasetoV4.scala` to `payments/crypto/spi/shared` — portable PASETO **v4.public** (signed) token
sign+verify: Ed25519 over the Pre-Authentication Encoding (PAE) of `[header, message, footer, implicit]`,
with footer + implicit-assertion binding and version/purpose/tamper rejection. Built on the from-scratch
portable Ed25519 (JVM + Scala.js, no platform crypto). PAE pinned to the PASETO spec vectors; sign/verify
validated against the official `v4.json` "4-S-1" public key. Second `token-formats` slice after JWS/JWT
(ES256K, PASETO v4.local, and COSE remain).

## 2026-07-05 — v2 bridge: batch PASS 129→176/178 (OIDC/Mirror/MCP stubs)

`feature/v2-frontend-bridge` merged (7277dfaa0). Four more examples fixed:
- **oidc-login-flow.ssc**: OIDC batch stubs — `discoverAs` routes to in-memory server via `findByIssuer`;
  `exchangeAuthorizationCode` returns stub token; `http.parseUrl`/`makeLocalhostGetResp` fake 302 redirect;
  `OidcHelpers.userInfo` falls back to first registered user; `String.take/drop/takeRight/dropRight` added.
- **custom-derives-mirror.ssc**: `summon[Mirror.Of[X]]` synthesized inline; `Defn.Object` compiled to
  `__mk_method_obj__`; general typeclass derivation `Tc.derived(mirror)`; Mirror field dispatch in Runtime.
- **agent-mcp-toolsource.ssc + mcp-client-discover.ssc**: `mcpConnect` fake client (`listTools/listResources/
  listPrompts/close/callTool` all returning empty/unit).
- Also: `BatchCli.resetState()` called before each example to prevent cross-contamination.
Remaining 2 FAIL: `x402-cardano*.ssc` — need real Blockfrost API keys (hard floor).

## 2026-07-05 — crypto: portable JWS/JWT (RFC 7515) — HS256 + EdDSA over the crypto SPI

Added `Jws.scala` (+ a `Jwt` convenience) to `payments/crypto/spi/shared` — portable compact JSON Web
Signature sign+verify for **HS256** (HMAC-SHA256) and **EdDSA** (Ed25519), built on the from-scratch
portable primitives (works identically on JVM + Scala.js, no platform crypto). Verify does a
constant-time MAC compare and returns `None` on a bad signature or malformed token. Gated byte-exact
against RFC 7515 A.1 (HS256) and RFC 8037 A.4 (EdDSA), plus round-trip + tamper-rejection, green on JVM
and JS. First slice of BACKLOG `token-formats` (ES256K, PASETO, COSE remain).

## 2026-07-05 — crypto: portable Keccak-256 reference (the Ethereum hash) in the crypto SPI

Added `Keccak256.scala` to `payments/crypto/spi/shared` — a pure-Scala Keccak-f[1600] sponge (original
Keccak pad `0x01`, rate 1088 bits, 256-bit output), the Ethereum hash. Fills the last missing pure-Scala
reference in the portable crypto stack (Blake2b / RIPEMD-160 / secp256k1 / SHA-256/512 / Ed25519 already
there). Verified bit-for-bit against BouncyCastle across rate-boundary (135/136/137 B) and multi-block
inputs, plus canonical vectors (empty / abc / hello); byte-identical on JVM and Scala.js. Closes the
"references exist" half of BACKLOG `crypto-spi-pure-references` (the register-as-SPI-fallback backend remains).

## 2026-07-05 — MILESTONE: v1-interpreter parity on the examples corpus (T4.x)

Probing the remaining compat FAILs against the real v1 interpreter (`cli/stage` build)
showed every one is env-gated or does not run on the v1 interpreter either (4 dataset
files are `backend: jvm` CODEGEN examples; word-count and actors-typed-remote-spawn
fail on v1 interp too). **The v2 FrontendBridge runs everything the v1 interpreter
runs on the corpus.** The jvm-codegen examples are an optional separate track via the
Phase-2c JVM source generator.

## 2026-07-05 — v2 bridge: Erlang supervision surface (trapExit/link/monitor) + mapreduce auto-inject

Full supervision triad on the VirtualThread actor model (links kill-or-message on death,
monitors get Down; death notification on completion/crash/kill) and the mapreduce stdlib
family auto-injects like v1's auto-available symbols. The 3 distributed examples now
execute deep into the real stdlib (remaining: one String+Int arith deep in word-count;
an unbridged method returning Stub in the wire files). Coverage steady 185-186/193.

## 2026-07-05 — T4.5: hang-list eliminated — 186/193 (96.4%) of the FULL corpus on v2

The 16-file hang-list was stale (everything terminates); the real batch killer was a
bridged v1 `exit` intrinsic shadowing the actor exit — System.exit(0) silently killed
the batch JVM. New `Runtime.exitHandler` hook (batch intercepts), polymorphic
`exit(actorRef|code)`, actor globals registered last. Coverage now runs the whole
corpus with zero skips: 186 PASS / 7 FAIL (2 env keys + 5 real gaps).

## 2026-07-05 — T3.4: Rust generator perf — Rc-shared Data (17× list-fold) + SelfRecNative (78× fib)

ADT values are now structurally shared (`Data(Rc<str>, Rc<Vec<V>>)`) — deep-clone
quadratics gone; fib-shaped defs compile to a native `fn(i64)->i64` beside the general
closure (VM SelfRecLL rules, tail-bail preserved). recursion-fib 1.37 ms/op — faster
than the v1 Rust backend; list-fold 17×, string-concat 4×. arith-loop/mutual-recursion
remain machinery-bound (Long-cell specialization queued). Parity 8×3 GREEN.

## 2026-07-05 — v2-js-smallint: opt-in `--ints=number` fast mode for the JS generator

Plain-number ints (arith-loop ~6×, recursion-fib ~3× faster in node) behind an explicit
flag; the default remains exact 64-bit BigInt — number mode is documented-wrong for
wrap-around programs (bool-predicate demonstrates). Fixtures match the VM in both modes.

## 2026-07-05 — v2-rust-backend-tco: real trampoline TCO in the Rust generator

`v2/backend/rust/RustBackend.scala` closures now return `Step::Val | Step::Bounce`;
`call_fn` drives bounces in a loop, so tail calls run in constant stack (proven:
tco.coreir's 1M tail calls pass with a 1MB thread stack; the 2GB-reservation stopgap
is gone, back to 256MB non-tail headroom). New `genTail` emitter mirrors the
ssc0-level backend's genV/genT split. Parity 8×3 ALL GREEN.

## 2026-07-05 — T5.6+T5.7: numeric-poly prims everywhere + ssc1 top-level statements

- **T5.6**: `i.add/sub/mul/div/mod` + `i.eq/lt/le/gt/ge` are now numeric-POLYMORPHIC
  (Int, Float, mixed — the VM general table's numBin/numCmp semantics) in ALL fast
  paths and all three source generators (VM resolve2/resolve1, Rust v_i*, JVM
  _numBinI, JS $n* helpers). `7.5 / 2.5` used to crash the VM fast path; float
  comparisons/div were Int-only patchwork. New floatnum.coreir parity fixture (8×3
  GREEN) + kc-float.ssc ssc1c gate.
- **T5.7**: ssc1's lowerProg no longer drops top-level statements — expression
  statements run in document order in the entry; `val (a, b) = …` works at top level;
  `{ (a, b) => stmts… }` block-lambdas parse header-first (val/def in bodies OK);
  `_sel_until`/`_sel_to` are tail-recursive (deep ranges no longer stack-overflow);
  `_sel_toList` added. examples/recursion.ssc now runs FULLY via the self-hosted ssc1
  path — all 13 outputs correct. conformance 639 ok / 0 FAIL; bench 31/31.

## 2026-07-05 — v2-wasm-unblock: WASM backend shipped (4th target)

The historically-only-open v2 language backlog item is closed. `rustup` appeared in the
environment; per the ROADMAP plan, the Rust backend is reused with
`rustup target add wasm32-wasip1`, and the module runs on Node's **built-in WASI host**
(`v2/scripts/run-wasi.mjs` — no wasmtime/wabt needed). New `v2/ssc0-wasm` launcher
(compile-and-run or `-o out.wasm`). Verified: quicksort byte-identical to the VM,
tco.ssc0 = 1e6 tail calls in constant stack (the Step-trampoline TCO carries over),
Mira typed programs (hm-qsort) work via the same target. Toolchain-gated conformance
checks added to `v2/conformance/check.sh` (skip cleanly when the toolchain is absent).

## 2026-07-05 — v2-recursion-opt: SelfRecLL 8× on recursion-fib + cell.set FLC corruption fix

- **SelfRecLL** (`v2/src/Runtime.scala`): arity-1 self-recursive Int defs (fib-shaped)
  compile to a plain JVM `Long => Long` — zero allocation, no trampoline/Done/global
  lookup per recursive call. recursion-fib **65.7 → 8.2 ms (8.0×)** same-flags A/B.
  Tail-position self-calls bail (constant-stack TCO preserved); non-Int args fall back
  to the general body. Handles ssc1c's `<=` Bool-if desugar.
- **cell.set FLC corruption** (BUGS.md `v2-cellset-flc-corruption`): the 2026-07-04
  FastCode batch made `tryFLC` optimistic (non-Int → 0L), silently breaking the
  `cell.set` fast path's safety assumption — `m = m.updated(k, v)` stored `IntV(0)`
  (map-ops crashed; silent corruption risk in general). Fixed with a `flcProvablyLong`
  gate; 10 var-heavy corpus programs audited (only map-ops was affected).
- **v2-pattern-match-opt re-scoped + closed**: fresh baseline 82–88 ms (old 362 is
  obsolete); Float-typed workload is outside the Long tiers — JIT-gated per T3.2b;
  concrete non-JIT lever queued as BACKLOG `v2-float-cell-fastpath`.
- Verification: conformance 634 ok / 0 FAIL; backend parity 7×3 ALL GREEN; bench
  corpus 31/31 no SKIP (map-ops 0.56 ms restored).

## 2026-07-05 — v2-ssc1c-globals-bug + JS 64-bit ints + backend parity harness

Three fixes restoring full 31/31 bench-corpus compatibility on v2 and hardening the
Phase 2c code generators (SPRINT Track 5, T5.1–T5.3):

- **ssc1c @count/@sum bug (T5.1)**: `lowerE`'s expression-position `"assign"` case in
  `v2/lib/ssc1-lower.ssc0` missed `@@name` LongCell vars, emitting a bogus
  `(global @count)`. Assigns inside `if`-then branches (e.g. `if p then count = count+1`)
  broke; statement-position assigns were fine. bool-predicate (243) and mutual-recursion
  (1000) now run correctly on VM + JVM + JS + Rust.
- **JS backend 64-bit ints (T5.2)**: `v2/backend/js/JsBackend.scala` used plain JS numbers
  for `i.*` — programs with real 64-bit overflow (the corpus LCG anti-fold idiom) silently
  computed wrong values (bool-predicate: 6 ≠ 243). Ints are now BigInt end-to-end with
  `BigInt.asIntN(64,…)` wrapping, masked shift counts, `Number(…)` bridges at index sites,
  and previously-missing conversion prims (`i->str`/`i->f`/`f->i`/`tagOf`/`arity`/…).
  Also: `backend/js/project.scala` gained the missing `//> using file ../../src/CoreIR.scala`.
- **VM sconcat fast-path regression (T5.4)**: `string-concat` crashed with
  `sconcat: bad types` — the `Prims.resolve2` fast path (v2-arith-loop-jit) shadowed the
  general table's lenient coercion (`"item-" + n`). bench.sh masked it as `SKIP(no-main)`.
  Now mirrors the general table; corpus is a true 31/31 (188890 on VM + JS + Rust).
- **kc5 conformance probe fix (T5.5)**: the type-error probe used `1 + "a"` — legal Scala
  (string concat), correctly lowered by KC5-micro — so the check was red on origin/main.
  Probe now uses genuinely ill-typed `1 - "a"`; conformance fully green (634 ok).
- **Backend parity harness (T5.3)**: new `v2/backend/check.sh` — every conformance
  `.coreir` fixture + the two regression programs through run-ir vs JVM vs JS vs Rust,
  byte-identical outputs required; ALL GREEN (7×3). It immediately caught three more
  generator bugs: Rust never printed a non-Unit entry result; Rust tco stack overflow at
  256MB (now a 2GB virtual reservation, real trampoline TCO queued as v2-rust-backend-tco);
  and JvmBackend's Long-cell specialization referencing `_asLong` that was private to
  `object R` — exposed only by ssc1c-emitted `i.add` prims (top-level helper added).

## 2026-07-05 — agent-mock-gateway: golden-transcript conformance for std.agent

`AgentConformanceTest.scala` drives the agent loop against an in-process HttpServer fake gateway
that replays a **recorded, FIFO sequence of model responses** (the "mock gateway" the agent-sdk
spec asked for) and asserts run STRUCTURE (stop reason, executed ops, request sequence), not model
prose. Three golden transcripts: tool-use loop, multi-turn (two sequential tool round-trips), and
the error path (non-2xx turn). Complements the content-keyed `AgentSdkInterpreterTest` and adds the
previously-missing multi-turn case. 3/3 green; no `agent.ssc` change needed.

## 2026-07-04 — T5.1: bool-predicate + mutual-recursion pass on all 3 v2 backends

Root cause: Rust backend eagerly evaluated `prim __math_obj__` (prelude `def math = ...`)
at startup → `panic!()` before any user code ran. Fix: emit a lazy stub closure for
`__math_obj__` in RustBackend.scala. JVM and JS backends already worked.
All 3 backends (JVM/JS/Rust) now run both bench programs without error.

## 2026-07-04 — T2.3: Actors spike — VirtualThread-per-actor works under v2

`examples/actors-pingpong.ssc` runs fully under v2: spawn/receive/self/exit/runActors
all pass, including receive-with-timeout and exit+drop-send. Implementation in
PluginBridge.scala: VirtualThread per actor, LinkedBlockingQueue mailbox, @timeout cell,
dead flag (interrupt alone races if msg is already queued). Runtime.scala: non-DataV
scrutinees in Match fall through to default arm (for `case s: String =>`); FastCode
global-lookup path also needed lookupGlobal fallback for @timeout.

## 2026-07-04 — T2.2: HTTP/SQL intrinsics work under v2 (httpGet → HTTP 200)

Added httpPlugin+sqlPlugin to v2PluginBridge classpath. NativeImpl intrinsics now
also register as v2 global ClosV in addition to prim handlers (needed because
FrontendBridge emits `App(Global("httpGet"), args)`, not `Prim("httpGet", args)`).
Fixed raw-arg mismatch: NativeImpl expects unwrapped primitives (String/Long/Boolean)
not v1 DataValue objects; `v2ToRaw`/`rawToV2` helpers mirror `Interpreter.unwrapValueAsAny`.

## 2026-07-04 — T2.1: v2 BlockForm effects (Logger/State/Random/Clock/Env/Retry/Cache)

All 7 v1 effect plugins wired to v2 VM via `V2EffectContext` ThreadLocal handlers + `PluginBridge.loadAll()`.
Three fixes: (1) FastCode global-lookup paths (`globalFastPath`, `tryFLC`) had separate `globals` maps
that bypassed `V2PluginRegistry` → added `lookupGlobal` fallback; (2) FrontendBridge emitted block args
(e.g. `runLogger { ... }`) as eager `Seq` — effects ran before handler installed → wrap statement
`Term.Block` args in `CT.Lam(0, ...)` thunk; lambda blocks detected by `Block(List(Fn|AnonFn))` heuristic;
(3) `__arith__` catch-all for `effect Logger:` declaration prims returns `UnitV` instead of crashing.
Gate: `runLogger`, `runLoggerToList`, `runState` all produce correct output under v2.

## 2026-07-04 — T3.3: v2 JVM backend Long-cell specialization; 80× speedup on arith-loop

Three fixes: (1) `safeName()` appends `x` to trailing-`_` names (avoids Scala 3 `name_:` parse error);
(2) `__arith__` added to `prim3` dispatch so FrontendBridge IR compiles;
(3) **Long-cell specialization**: `lcell.new(intLit)` → `var name: Long = n`, `lcell.get` → direct read,
`lcell.set` → direct assign, `__arith__(Long, Long)` → inline arithmetic (`l op r`).

`isLongTyped()` / `genTermAsLong()` helpers; `longVars: Set[String]` threaded through `genTerm`.

**Result**: arith-loop 43ms → 0.53ms = **80× speedup**; within 2× of native Scala (0.6ms/op).
T3.3 gate (within 2× of v1 JVM backend on arithmetic programs) ACHIEVED.

## 2026-07-04 — T4.1: FrontendBridge .ssc file format + runtime fixes; 71/193 examples pass

Full batch result: **71/193 examples PASS** (37%). Pure-language examples all pass;
remaining failures require T2.1 plugin bridge activation.

Fixes this session:
- `extractCode`: strips shebang, YAML front matter, markdown fences; doc-only → empty no-op.
- `.copy(named=val)` and `Ctor(named=val)`: fieldRegistry reorders named args positionally,
  avoiding unbound `@field` globals.
- v1 multi-import line strip: `[X,Y](file.ssc)` lines removed pre-parse (fix parse errors → cleaner runtime errors).
- List/Seq/Vector companion factories: `tabulate(n)(f)`, `fill(n)(v)`, `range(from,to[,step])`,
  `empty` for List/Seq/Vector/Map added to `__method__` dispatch.
- Seq/Map `.empty` companion factory dispatch.

FAIL breakdown (122): 42 multi-import→plugin-globals, 8 Spark, 7 Dataset dispatch,
~50 other plugin globals (sha256/runActors/serve/signal/etc.), 7 pure-language gaps
(direct[M] sugar, `effect Foo:` decl, v1 `[...]` list literal syntax).

## 2026-07-04 — FastCode phase 2: DataV→IndexedSeq/ArraySeq + While FC case

`DataV.fields: Vector[Value]` changed to `IndexedSeq[Value]`. All hot-path Ctor creation
(`compile(Ctor)`, `tryFC(Ctor)`, `ctorFused ++ path`) now uses `ArraySeq.unsafeWrapArray(Array(...))`:
2 allocs (Array + ArraySeq wrapper) vs 4 for Vector (varargs + VectorBuilder + take + Vector1),
eliminating the 256-byte VectorBuilder.prefix1 per Ctor creation. Measurement: tuple-monoid 26→22ms.
`tryFC(While)` added: nested while loops inside Seq/Let/foreach bodies now FC-compile instead of
falling to the general compile() path.
`tryFCMutual` carrier optimization: preallocates LongCellV+Array for single-Long-arg mutual calls
to eliminate 2 allocs/bounce (dead code; tryFCMutual not called — pass 1b was 4% slower for deep
mutual recursion due to JVM frame overhead > trampoline). Investigation result: T3.2b (5× max)
is architecturally blocked without a v2 JIT backend. All FC improvements implemented.

## 2026-07-04 — FastCode phase 1: fcEntry + tryFC(Match) + Float-safe arm bodies

`ClosV.fcEntry: Option[FC]` — set by Compiler for each lambda def; callers skip the trampoline and
call the body FC directly (no Done alloc per call). `tryFCValue` uses `Prims.arithOp` for `__arith__`
(Float-correct vs FLC-first which coerces Float→0L). `tryFC(Match)` full implementation: armMap O(1)
tag dispatch, field binding via appendOne, arm bodies via tryFCValue. `cell.set resolveArg` compile-time
fast path: if callee's fcEntry is already set and env is empty, capture bodyFC + pre-allocate sharedArgEnv
(safe: bodyFC runs synchronously, no trampoline). Net improvements:

| program          | v2 before | v2 after | improvement |
|------------------|-----------|----------|-------------|
| pattern-match    | 194 ms    | ~22 ms   | 8.8×        |
| list-fold        | 16.5 ms   | ~1.4 ms  | 12×         |
| recursion-tco    | 10.9 ms   | ~2.5 ms  | 4.4×        |
| mutual-recursion | 81.2 ms   | ~18 ms   | 4.5×        |
| tuple-monoid     | 407 ms    | ~15 ms   | 27×         |
| instance-field   | 8.4 ms    | ~3 ms    | 2.8×        |

## 2026-07-03 — v2 Phase 2 complete: v2Core sbt + plugin bridge + JVM/JS/Rust backends

Phase 2 of the v1→v2 migration is fully done. Verification pass (Phase 2d) ran 2026-07-03:

- **JVM backend** (`v2/backend/jvm/JvmBackend.scala`): 5/5 conformance tests pass
  (fact=120, letrec=true, map=Cons(2,4,6), tco=500000500000, thunk=42) + 3/3 bench corpus
  spot-checks (arith-loop, recursion-fib, list-fold). TCO via `@tailrec def` verified at 1M calls.
- **JS backend** (`v2/backend/js/JsBackend.scala`): 5/5 conformance + 2/2 bench corpus
  (arith-loop, recursion-fib). Trampoline TCO ($tco/$c) verified.
- **Rust backend** (`v2/backend/rust/RustBackend.scala`): 29/31 bench corpus pass. 2 known
  ssc1c IR bugs (`bool-predicate`/`mutual-recursion` emit `@count`/`@sum` undefined globals)
  also fail the v2 VM — root cause tracked in BACKLOG: v2-ssc1c-globals-bug.
- **sbt v2Core/compile**: SUCCESS (5 sources, 4 s).
- **macOS echo gotcha**: `echo "$var"` processes `\n` as real newline on macOS (unlike Linux),
  corrupting backend-generated preamble strings. Always use direct redirects when capturing output.

Phase 3 (CLI default → v2) remains open in SPRINT.md.

---

## 2026-07-03 — v2-backend-rust: Phase 2c Core IR → Rust code generator

`v2/backend/rust/RustBackend.scala` — self-contained Scala 3 program that reads Core IR
S-expressions from stdin and emits compilable Rust source. Design: `enum V` with
Unit/Bool/Int/Float/Str/Bytes/Data/Fn/Cell/LCell/Map/Arr; `Rc<dyn Fn>` closures; forward-ref
cells (`__fwd`) for all top-level Lam defs; 256MB thread for deep tail recursion; `v_sconcat`
handles any ADT concat. 29/31 bench corpus pass; 2 failures are pre-existing ssc1c IR bugs.

---

## 2026-07-03 — v2-jvm-tco: TCO for v2 JVM backend (@tailrec)

`v2/backend/jvm/JvmBackend.scala`: added tail-call elimination for self-recursive global `Def`s
and single-lam `LetRec`s. Detection via `selfCallTailPositions` — all self-calls must be in tail
position (conservative: only adds `@tailrec` when guaranteed safe). Emits `@tailrec def <name>_direct`
+ a `lazy val <name>` closure wrapper so callers can still pass the function as a value. `tco.coreir`
(1M tail calls, sum 1..1_000_000 = 500000500000) now passes without stack overflow.
All 29 conformance tests pass (fact, letrec, map, thunk, tco). Backlog: `v2-jvm-tco-manual`.

---

## 2026-07-03 — v2-backend-js: Phase 2c — Core IR → JavaScript code generator

`v2/backend/js/JsBackend.scala` (scala-cli runnable): reads Core IR (S-expression text),
emits a self-contained .js file that when run with `node` produces output identical to
`ssc run-ir`. Trampoline TCO ($tco/$c loop), all Core IR nodes, full primitive set,
ADTs as {t,f} objects, cells as [v] arrays, maps as {m,k} wrappers. Passes all 5
conformance/*.coreir fixtures and 15 kc examples; 100k-deep tail recursion ok.
Spec: v2/specs/60-backend-js.md (v2 section added).

---

## 2026-07-03 — v2-backend-jvm: Phase 2c — Core IR → Scala 3 source code generator

`v2/backend/jvm/JvmBackend.scala` (712 lines, scala-cli runnable): reads Core IR (S-expression text
format), emits a self-contained Scala 3 source file that produces byte-identical output to `ssc run-ir`
when compiled with `scalac` and run with `java`. Handles all Core IR constructs (Lit, Local, Global,
Lam, App, Let, LetRec, If, Ctor, Match, Prim, While, Seq) + full prim set (arithmetic, string, list,
ADT, io, lcell, cell, map, arr). Value representation: `type V = Any`; closures = `Array[V] => V`;
ADTs = `(String, Array[V])` tuples; de Bruijn scopes tracked as `List[String]`. 28/29 passing
(conformance tests + all 23 v2 example programs); only `tco.coreir` (1M tail calls) is out of scope.
Spec: `specs/v2-backend-jvm.md`.

## 2026-07-03 — v2-plugin-bridge: Phase 2b — v1 plugins loadable from v2

Added `V2PluginRegistry` (mutable HashMap, checked by `Prims.resolve` before throwing) to
`v2/src/Runtime.scala`. Created `v2/plugin-bridge/` sbt module (`scalascript-v2-plugin-bridge`):
`PluginBridge.loadAll()` discovers all v1 `Backend` plugins via `ServiceLoader`, extracts their
`NativeImpl` intrinsics, translates `v2Value ↔ v1Value` (scalars + DataV↔InstanceV + List→Cons/Nil
+ Option + Tuple), and registers wrapped handlers in `V2PluginRegistry`. 22 unit tests green.
Non-bridgeable: `InlineCode`/`RuntimeCall` (codegen only) and `BlockForm` effect runners (deferred).
## 2026-07-03 — v1-restructure: Phase 1 of v1→v2 migration

Moved `lang/`, `runtime/`, `tools/` into `v1/` using `git mv` (history preserved). Updated all 75
`.in(file("..."))` entries in `build.sbt`, plus `install.sh`, `scripts/runtime-bench.sh`,
`tests/perf/{coldstart,serverrss}/run.sh`, and 3 CI workflows (ci.yml, native-release.yml,
registry-pages.yml). `sbt compile` exits 0; `ssc run examples/hello.ssc` prints `Hello, World!`.
Module names and `dependsOn` references are unchanged — pure path restructure.

## 2026-07-03 — webauthn-persist: disk-persist the WebAuthn credential store

`scalascript.server.WebAuthn`'s `CredentialStore` was process-local (`ConcurrentHashMap`), wiped by
every server restart — busi's szykownia PWA silently lost every enrolled Face ID passkey on every
deploy, falling back to a pairing code the owner had to fetch off the remote server's disk. Added
opt-in `configureStore(path)` (tab-separated file, no JSON dep) + persistence on `storePut`/
`storeUpdateSignCount` (tmp-file + atomic move); no-op/unchanged behavior unless called. New extern
`webauthnConfigureStore`, wired on both JVM (native) and JS (Node `fs`-based) backends, declared in
`std/auth.ssc`. `WebAuthnPersistTest.scala` (6 cases); `runtimeServerCommon/test` 146 green, no
regressions; `authPlugin`/`backendJs` both compile clean.

## 2026-07-03 — agent-mcp-roundtrip: AgentMcpRoundTripTest (agent-sdk P3b)

End-to-end round-trip test for the `std.agent.mcp` bridge (`runtime/std/agent-mcp.ssc`).
`AgentMcpRoundTripTest.scala` (3 tests): exercises the bridge mapping in both directions —
`serveAgentToolsMcp` (AgentTool → MCP tool registration + JSON marshalling) and `mcpToolSource`
(MCP descriptors → AgentTool wrapping + result mapping). Transport: in-process
`LinkedBlockingQueue` pair, mirroring `McpEndToEndTest`. Spec: `specs/agent-mcp-roundtrip.md`.

---

## 2026-07-03 — v2-bench-perf: arith-loop 258ms → 17ms (15×), nested-loop < 20ms

Six-layer optimization stack achieving < 20ms target for tight integer counter loops:

1. **`Term.While` + `Term.Seq` in CoreIR** (`v2/src/CoreIR.scala`): new IR terms; While = Java while-loop (no trampoline bounce per iteration); Seq = evaluate terms with same env (no `appendOne` per statement). Reader/Writer/IrEncode all updated.
2. **IrWhile + IrSeq in `ssc1-lower.ssc0`**: while-block lowering changed from letrec-based tail recursion to `IrWhile(condIr, bodyIr)`; assign chains use `IrSeq` instead of `IrLet` with `_blk_` scope extension (avoids env-array alloc per sequential assign).
3. **FastCode + FastLongCode** (`v2/src/Runtime.scala` — `FastCode` object): `FC = Env => Value` (no Done boxing); `FLC = Env => Long` (no IntV boxing for arithmetic); `FBc = Env => Boolean` (no BoolV boxing for conditions). The While compiler uses FBc/FC fast paths when cond+body are fully compilable.
4. **`LongCellV(var v: Long)`** in `Value`: mutable long cell avoids IntV allocation per cell.set in tight loops. `lcell.new/get/set` primitives added; `ssc1-lower.ssc0` emits `lcell.new/get/set` instead of `cell.new/get/set` for integer-literal-initialized vars (using `@@name` scope prefix).
5. **`resolve1/2/3` in `Prims`**: allocation-free fast paths for 1/2/3-arg primitives; avoids `List[Value]` creation per Prim call on the hot path.
6. **Empty-App fast path**: `Call(c, emptyEnv)` singleton reuse for 0-arg applications.

Result: `arith-loop` 258ms → 17ms (bench.sh); `nested-loop` 18ms; both under 20ms target. All 31 bench programs still pass (same outputs).

## 2026-07-03 — v2-bench-compat KV9: effect-multishot (List monad CPS) — 31/31 bench programs on v2

`multi effect NonDet { def choose(options: List[Int]): Int }` + List monad transform in `ssc1c`:
- `ssc1-front.ssc0`: `multi effect E { ... }` parsed as `("multi_effect", "E")` stmt
- `ssc1-front.ssc0` `parseBlock` val branch: trailing `{ block }` after val RHS consumed as thunk arg (fixes `val all = handle(prog(s)) { handler }`)
- `ssc1-lower.ssc0`: `multiEffectsCell` registry + `isMultiShotVarName` (prefix match `"EffectName_"`)
- `lowerStmtToList`: `"multi_effect"` → registers name; `"def"` → detects multi-shot calls in body via `blockHasMultiShotResolved` → CPS-transforms with extra `k` param via `lowerBlockCps`
- `lowerBlockCps`: `val x = Effect.op(opts)` → `_list_flatMap(opts, x => rest)`, final expr → `k(expr)`
- `resolveE` handle case: `handle(multiShotFn(args))` → `multiShotFn(args, x => Cons(x, Nil))` (initial continuation)
- `kc6Defs`: added `_list_concat` (list append) and `_list_flatMap` (concatMap for List monad)

`effect-multishot` now runs at **4.36 ms** (was SKIP). **31/31 bench corpus programs have timing.**

## 2026-07-03 — v2-bench-compat KV8: effect-oneshot — 30/31 bench programs on v2

Parser and lowering fixes in `ssc1-front.ssc0` + `ssc1-lower.ssc0`:
- `effect` added to keyword list (`isKwD`) → no longer parsed as identifier
- `parseOneStmt`: `kw("effect")` case skips `effect E: { ... }` declaration block
- `skipTypeAt`: added `}` to stop set — prevented `def tick(): Int }` from consuming the closing brace
- `parseDef`: body (`= expr`) now conditional — abstract defs (no `=`) return unit body, fixing parse corruption where everything after an `effect` block was swallowed
- `parseBlock` id branch: trailing `{ block }` after `parseExpr` result consumed as thunk arg → `handle(expr) { handler }` becomes `app(handle(expr), [() => handler])`
- `ssc1-lower.ssc0` `kc6Defs`: added `bumpTickCellDef` (`cell.new(() => 1)`) + `bumpTickDef` (`() => cell.get(Bump_tick_cell)()`)
- `resolveE` app case: `handle(computExpr)(thunk)` → lowered as just `computExpr` (cell default is already the handler)

`effect-oneshot` now runs at **0.64 ms** (was SKIP(no-main)). 30/31 bench corpus programs have timing; `effect-multishot` remains SKIP (needs multi-shot delimited continuations, separate effort).

## 2026-07-03 — v2-bench-compat KV7: effect-pure + effect-stream + _sel_length — 31/31 bench programs on v2

Added to `ssc1-lower.ssc0` + `ssc1-front.ssc0`:
- `id { body }` → `id(() => body)` parse in `parseBlock` `id` branch (top-level statement only)
- `val (a, b) = expr` → `tuppat` AST node (tuple destructuring); `consumeBlockArg` helper for block args after tuple-pattern RHS
- `runLogger` kc6 def: `lam(1, app(local(0), Nil))` — calls the passed thunk
- `__streamBuf` global cell, `Stream_emit`, `runStream` kc6 defs — stream collection machinery
- `_sel_runToList` kc6 def: identity (stream already collected to list)
- `_sel_length` kc6 def: tail-recursive list-length counter; `listVarsCell` + `isListVar` + `isListConstruction` to dispatch `.length` to `_sel_length` for list vars, `slen` for strings/arrays

All **31/31** bench corpus programs now pass on v2.

## 2026-07-03 — v2-bench-compat KV6: Array/Vector/Map/LazyList — 29/31 bench programs on v2

Added to `ssc1-lower.ssc0`+`ssc1-front.ssc0`:
- `Array(v0..vN)` / `Vector(v0..vN)` → `_arr_fill(list)` (mutable ArrayBuffer push-init)
- `a(idx)` indexed read → `arr.get(a,idx)`; `a(idx) = x` → `arr.set` (new `idx_assign` parse tag)
- `Map[K,V]()` → `map.new()`; `m.updated(k,v)` → `_sel_mapUpdated`; `m.getOrElse(k,d)` → `_sel_mapGetOrElse`
- `LazyList.from(n)` → infinite `LazyCons` stream via letrec+thunk; `_sel_take` for LazyList/List
- `_sel_sum` tail-recursive; `_sel_map` extended with `LazyCons`/`LazyNil` arms
- `arrVarsCell`/`mapVarsCell` tracked in BOTH `resolveBlock` (so dispatch works within same block)
  and `lowerBlock`; fixes "unbound global" for map ops following `var m = Map()` in same function.

Programs now running: array-update, vector-index, map-ops, lazylist-take.
Total: **29/31** (effect-pure + effect-stream deferred: need full `runLogger`/`runStream` infra).

## 2026-07-03 — v2-kc13-ssc1-runner: end-to-end `.ssc` Markdown runner (KC13)

`ssc1-run.ssc0`: imports `mira-md.ssc0` + `ssc1-lower.ssc0`; reads a real `.ssc` file,
extracts all `scalascript` fence blocks, concatenates, parses and lowers to Core IR, emits.
`${ident}` interpolation fixed in `ssc1-front.ssc0` (braces branch in `interpParts`).
Removed duplicate `slen`/`sget`/`scat` wrappers from `mira-md.ssc0` (conflict on import).
New: `v2/bin/ssc1-run.ssc0`, `v2/examples/kc13-hello.ssc`, `v2/ssc1` launcher.
Also fixed 3 pre-existing conformance harness bugs (kc5-type-error, kc9-sideeffects, kc10-ifnoelse).

## 2026-07-03 — interp-poly-closed-form: degree-≤2 inline polynomial accumulation in O(1)

`tryClosedFormPolyLoop` now handles inline polynomial addends in left-associative form
(e.g. `acc + (i-500)*(i-500) + (i-500)*2` after val-inlining). `walkQuadPoly` returns
`(a2, a1, a0)` for degree-≤2 polynomials; `tryExtractPolyAddend` peels `acc` from the
left-assoc chain. Closed form `Σ a2*(S+j*stp)^2 + a1*(S+j*stp) + a0` computed in O(1)
BigInt. `multiVal` bench: 0.59 ms (JIT) → effectively 0. `PolyClosedFormTest` 7/7 pass.

## 2026-07-03 — jit-cast-isinstanceof-fix: robust JIT class loading in AsmJit+JavacJit

Fixed a silent cast failure in 8 JIT compile sites (4 × `JavacJitBackend`, 4 × `AsmJitBackend`):
`cls.getConstructor().newInstance().asInstanceOf[T]` was catching an exception in some class-loader
environments and returning null (marking the loop as `WhileLongMiss`). Replaced with an explicit
`isInstanceOf[T]` guard before the cast. Impact: `multiVal` bench confirms 20× speedup (12ms
interpreter → 0.59ms JIT); the JIT now works reliably in all launch modes.

## 2026-07-02 — stable-spi Phase 3 COMPLETE: load-time plugin API compatibility check

`Backend.pluginApiVersion: String = "1.0.0"` (default for all plugins; third-party
plugins override with `PluginApiVersion.Current` at their build time). `BackendRegistry`
warns at load time when a plugin declares an incompatible API version (different MAJOR,
or plugin MINOR > host MINOR) — for both in-process (ServiceLoader) and `.sscpkg`
archives. `PluginManifest` + `SscpkgManifest` gain an optional `pluginApiVersion` field.
`applyTargetedIntrinsicOverlays` propagates `pluginApiVersion` through the wrapper.
Tests: `PluginApiVersionCompatTest` (7), `PluginManifestTest` +2 (7), core (1033),
pluginApi (22). Phase 3 fully complete (migration → signature lock → compat check).

## 2026-07-02 — coremin-actors-codemove: ActorScheduler extracted to actors-plugin

Completed the coremin extraction of the actor cooperative scheduler and distributed
cluster runtime. ActorInterp.scala shrunk from 2956 → 98 lines; all scheduler and
cluster logic is now in ActorScheduler (2846 lines) + ActorClusterRoutes (280 lines)
inside `runtime/std/actors-plugin`. ActorRuntimeHost gained `actorRegisterHttpRoute`,
`actorRemoteHandlerInfos`, and `actorCodeIdentity`. Default provider is
`MissingActorRuntimeProvider` (fails with a clear message if plugin not loaded).
23 actor/cluster tests moved to `backendInterpreterPluginTests` and updated to install
`ActorsInterpreterPlugin`. 839 plugin tests pass; all 66 actor suite tests green.

## 2026-07-02 — KC5: HM type checker for K61 compat pipeline

`lib/ssc1-check.ssc0` (425 lines): Hindley-Milner Algorithm W type inference over the ssc1-front
Pair-tagged AST. Types: `TyInt | TyStr | TyBool | TyFloat | TyDyn | TyVar(n) | TyFun(a,b) |
TyList(e) | TyTup(es)`. `TyDyn` is an escape hatch for OOP/constructors/builtins (unifies with
anything). Two-pass design: first pass collects all def/val names → `Forall(Nil, TyDyn)`
(handles forward references and mutual recursion), then second pass infers each body.
Let-generalization + fresh type vars (via global cell). Context dict params (`__tc_*`) filtered
before inference (injected at runtime by given injection). Operators: `+` unifies both operands
(rejects `Int + Str`); `-/*///%` force Int; `==/</>` require same type → Bool.
`ssc1c.ssc0` calls `ssc1TypeCheck` before lowering; exits 1 with `type error: …` on stderr.
All 21 KC examples pass. `kc5-typechk-err.ssc` + conformance test added.

## 2026-07-02 — KC5: context bounds + given auto-injection

Added context bounds `[A: TC]` and `given`/implicit-dict injection to the K61 pipeline.
`parseTypeParams` extracts bounds from `[A: TC, ...]` → prepends `__tc_TC` dict params.
`readTypeStr` captures type annotations using `tokKind` for punctuation (not `tokVal`, which is `""` for `[`, `]`).
`given` branch emits `Pair("given", Pair(name, Pair(typeStr, body)))` with type string.
Lowering: `buildGivenTable` + `buildSigTable` (using `#cell`) → `injectGivens` in app case.
`typeOfExpr` heuristic infers `Int`/`String`/`Bool` from literal AST tags.
`io.println` primitive added to `v2/src/Runtime.scala`; `printlnDef` updated.
`kc5-typeclass.ssc`: `given showInt: Show[Int]` / `def display[A: Show](x: A)` → "shown\nshown".

## 2026-07-02 — KC8 + KC12: given/using context params + string interpolation

Added two parser features to the K61 v1.0-compat pipeline.
**KC12 — string interpolation** (`ssc1-front.ssc0`): `buildSInterp` splits raw string content
at `$identifier` occurrences (`interpParts` + `partsToExpr`); `parseAtom` detects `id("s"|"f"|"raw")`
followed by a `str` token and calls `buildSInterp`. Result is `"Hello, " ++ name ++ "!"` concatenation
AST, which `lowerE` already handles via `IrPrim("sconcat", ...)` (KC5-micro). `${expr}` unsupported
(skipped as literal).
**KC8 — given/using** (`ssc1-front.ssc0`): `parseOneStmt` gets a `given` branch:
`given name: T = body` → `mkVal(name, body)` (anonymous given skipped). `parseDef` calls
`parseUsingParams` on a `(using ...)` second param list, appending them as regular params.
`buildPostfix`: `(using args)` call sites strip `using` keyword and merge the arg list into the
preceding call's args so the runtime sees a single N-arg application (avoiding absent partial-application support).
GOTCHA: `appendL` does not exist in `ssc1-front.ssc0`; use `append` from the imported `list.ssc0`.
`greet(who)` = "Hello, World!"; `join("hello","world")(using separator)` = "hello, world".

## 2026-07-01 — KC11: lambda expressions + return statement

Added anonymous functions and `return` to the K61 v1.0-compat pipeline.
**Lambda parsing** (`ssc1-front.ssc0`): `tryLamParams` speculatively parses `(name [: T], ...)`
returning `Some(names)` or `None` (no backtracking needed). `parseExpr` checks `id =>` (single
param) and `(params) =>` (multi-param) before falling through to infix. `return` keyword in
`parseAtom` → `Pair("return", parseExpr(rest))` so it works inside `if`-then bodies (else `return -n`
would parse as `return - n = subtraction`). **Lowering** (`ssc1-lower.ssc0`): `lowerE` for `"lam"`
→ `IrLam(n, lowerE(appendL(revL(params), scope), body))`. `lowerBlock` for `"return"` → evaluate
value and stop (ignore trailing stmts). `if (cond) return e; rest` pattern in block → `IrIf(cond, e,
lowerBlock(rest))`. **GOTCHA**: ssc0 patterns can't have string literals as ctor arguments
(`case Pair("if", x)` is a parse error — use a variable + `#seq` guard).
`compose(double, inc)(5)` = 12; `abs(-7) + abs(3)` = 10.

## 2026-07-01 — KC10: var/while loops + if-without-else

**`var x = e`** → `IrPrim("cell.new", [e])` bound as `"@x"` in scope. Reads of `x` in `lowerE`
detect `"@x"` in scope → `IrPrim("cell.get", [ref])`. **`x = v`** in blocks → `cell.set`. 
**`while (cond) body`** → `IrLetRec([IrLam(0, IrIf(cond, IrLet([body], recurse), Unit))],
IrApp(go, Nil))` — 0-arity letrec loop function. Scope inside loop: `"$go$"` at Local(0).
**`if (cond) sideEffect`** without `else` → parser now makes the else branch `mkTup(Nil)` (Unit).
kc10-while: `sumTo(5)=0+1+2+3+4=10`; kc10-ifnoelse: `check(5)→"positivedone"`.

## 2026-07-01 — KC9: block expressions `{ val/def/expr; ...; result }`

Added full block expression support to the K61 v1.0-compat pipeline.
**Parser** (`ssc1-front.ssc0`): `{` in `parseAtom` calls `parseBlock`; handles `val`/`def`/
side-effect expressions until `}`. **Lowering** (`ssc1-lower.ssc0`): `lowerBlock(scope, stmts)` —
`val` → `IrLet`, local `def` → `IrLetRec` (with `Cons(name, scope)` so recursive self-calls resolve),
intermediate expression → `IrLet` with `_blk_` discard, final expression → `lowerE` directly.
`resolveE` recurses into each block item. `f(3)` with two `val` intermediates → 49; `{println("a");
println("b"); println("c")}` → "abc"; local `def square(x)=x*x` → 49.

## 2026-07-01 — KC5-micro + KC7b: string `+` heuristic + object static dispatch

**KC5-micro** (`ssc1-lower.ssc0`): `isStrExpr` predicate + `resolveE` inf branch upgrades `"+"` to
`"++"` when either operand is a string literal or string-returning prim. Handles `"Hello, " + name`.
**KC7b**: `object O { defs }` parsed into `("object",(name,stmts))`; `skipToBrace` helper for
`extends T {`; `resolveMethodCall` uid receiver → `O_method(args)` static dispatch;
`lowerStmtToList` prefixes object defs. Conformance: kc5-strcat + kc7b-object both green.

## 2026-07-01 — K49: conformance harness jar isolation + diagnostics

`v2/conformance/check.sh` no longer shares `/tmp/ssc-conformance.jar` across runs. Each run builds
its assembled jar inside a unique diagnostic log directory, captures Java/Rust stderr and stdout
artifacts, retries empty Java stdout once, and prints a failure summary. Root cause was concurrent
or repeated harness runs overwriting the shared jar mid-run (`NoClassDefFoundError` / corrupt jar).
Verified with two consecutive full `cd v2 && ./conformance/check.sh` passes after the fix, plus a
final full pass after rebasing on KC7.

## 2026-07-01 — KC7: match expressions + case class OOP lowering

Parser extensions (`ssc1-front.ssc0`): `parsePat` (cpat/vpat/wpat), `parseMatchArm/Arms/Expr`
(prefix `match e {}` + postfix `e match {}` in `buildPostfix`), `parseCaseClass` + `skipToStmt`;
`parseOneStmt` now handles `case class`, `sealed`/`abstract` (skip), `object` (skip body).
Two new AST tags: `"match"` (Pair(scrutinee, arms)), `"casecls"` (Pair(name, params)).

Lowering extensions (`ssc1-lower.ssc0`): global `appendL`; `buildCtorArgs` (builds
`[IrLocal(n-1)..IrLocal(0)]` for ctor field order); `lowerMatch` (emits `IrLet + IrMatch`;
pure vpat/wpat with no ctor arms skips `IrMatch` to avoid crashing on non-Data values);
`lowerCaseCls` (injects constructor `IrDef` + `_sel_field` accessor defs per field);
`lowerStmtToList` (replaces `lowerStmt`; `casecls` emits multiple IrDef via `appendL`).
`resolveE` + `lowerE` handle `"match"` tag (recursively resolve arm bodies).

Conformance: 3 new KC7 tests (kc7-match=42, kc7-casecls=7, kc7-opt=10) all green.

## 2026-07-01 — KC6: v1.0 intrinsics mapping (`lib/ssc1-lower.ssc0`)

Resolve-pass (`resolveE`) added to `ssc1-lower.ssc0`: pre-processes the KC3 AST before de Bruijn
lowering, transforming known v1.0 patterns. No kernel changes — prims already existed.
- **Constructor recognition:** `None`/`Nil` → IrCtor; `Some(x)`/`Cons(h,t)`/`Left`/`Right`/`List(...)` → IrCtor
- **String fields:** `.length/.size` → `slen`, `.substring(f,t)` → `sslice`, `.charAt(i)` → `scodeAt`, `.toString` → `i->str`, `.toInt` → `__str_toInt` helper
- **List fields:** `.head/.tail/.isEmpty/.nonEmpty` → injected IR helper defs
- **List methods:** `.map(f)`/`.filter(f)` → 2-arg `_sel_map`/`_sel_filter` with letrec; `.foldLeft(z)(f)` → curried `_sel_foldLeft`
- **Infix `::` :** cons prepend added to lowerE
- Conformance: 4 new KC6 tests all green (string.length=5, substring=ell, list.map.head=20, foldLeft sum=6)

## 2026-07-01 — Lark → Mira rename (71 files)

Language formerly named Lark renamed to **Mira** (user preference). lib/lark*.ssc0 → lib/mira*.ssc0,
launchers v2/lark → v2/mira, examples/hm-lex.lark → hm-lex.mira, all imports/comments/docs updated.
Conformance green (33 ok, 0 FAIL). See commit 9b7d146bb.

## 2026-07-01 — KC4: v1.0 ScalaScript → Core IR lowering (`lib/ssc1-lower.ssc0`)

`lib/ssc1-lower.ssc0` (~200 lines, ssc0): full lowering of KC3 AST to Core IR Data. De Bruijn name
resolution, all arithmetic/comparison/string ops → prims, def/val → IrDef, if/app/tup all handled.
Injected builtins: println/print. Entry = IrApp(IrGlobal("main"), Nil). `bin/ssc1c.ssc0` driver +
`v2/ssc1c` launcher. Pipeline: `ssc1c hello.ssc | ssc run-ir /dev/stdin` → "Hello, World!" end-to-end.
ssc0 GOTCHA: `_` inside constructor patterns invalid in kernel — use real var names.

## 2026-07-01 — KC3: v1.0 ScalaScript parser (`lib/ssc1-front.ssc0`)

`lib/ssc1-front.ssc0` (~350 lines, ssc0): combined KC2+KC3 lexer+parser for ScalaScript v1.0
functional subset. Written in ssc0 (not Mira) to avoid HM unifier stack overflow. Lexer: 26 token
kinds including `==`, `=>`, `->`, `::`. Parser: recursive-descent, tag-encoded AST using `Pair`
instead of ADTs (avoids ssc0 pattern nesting limitations). Handles: `def`/`val` statements, infix
precedence climbing (prec 3–8), postfix `.field`/`(args)`/`[types]`, `if/then/else`, tuples,
literals. Type annotations stripped. Conformance: `parse "def f(x: Int): Int = x + 1"` →
`SDef("f",[x],EInfix("+",EVar(x),EInt(1)))`. Factorial, main(), multi-stmt all tested.
Gotchas: ssc0 forbids nested constructor patterns (use nested match); no `-1` literal (use `#i.neg(1)`).

---

## 2026-07-01 — KC2: v1.0 ScalaScript lexer in Mira (`examples/hm-lex.mira`)

`examples/hm-lex.mira` (130 lines Mira): full lexer for ScalaScript v1.0 source code.
Token ADT with 23 constructors. Features: whitespace+line-comment skipping, identifier/keyword/
integer/string scanning, all standard operators (=> -> :: ++ <= >= != && || etc.). Split into
`lexPunct`+`lexOp` helpers to reduce HM unifier stack depth. `lex "def f(x: Int) = x + 1"` → 12
correct tokens. VM + JS + Rust backends all produce identical output. Needs `-Xss512m` for type-
checking (same as hm-json). Conformance test added to `check.sh`.

---

## 2026-07-01 — K55: Markdown fence extractor (`ssc-front`)

`lib/mira-md.ssc0` (130 lines, ssc0): full Markdown fence-block extractor. Parses `.ssc`
Markdown files into `[(lang, source)]` pairs. Features: YAML front-matter stripping (--- ... ---),
line-by-line scanner, backtick fence detection (startsWith3bt / isClosingFence), CR/LF handling.
Driver `bin/ssc-front.ssc0` + `v2/ssc-front` launcher. Test: `examples/hm-md-demo.ssc` (2 blocks,
YAML skipped). Conformance test added to `conformance/check.sh`.

---

## 2026-07-01 — K54: rename ssct-hm → Mira + K60/K61 roadmap (v1.0-compat frontend)

ssct-hm is now **Mira** — a complete ML/Haskell-family typed FP language with HM inference,
algebraic effects, type classes, ADTs, and ~90-fn prelude. Renamed across 66 files: lib/mira*.ssc0,
bin/mira.ssc0/mira-js.ssc0/mira-rust.ssc0/mirac.ssc0, launchers v2/mira/mira-js/mira-rust,
specs/41-mira.md. Fence tag: ` ```mira`. File extension: `.mira` (`.hm` accepted).
New specs: `60-compat-frontend.md` (KC1–KC8 v1.0-compat design) + `61-fence-languages.md`
(fence registry). New milestones K60 (Mira rename + Markdown extractor K55) + K61 (v1.0-compat
frontend). Conformance all green. 84d6b28c6.

---

## 2026-06-30 — K53: benchmark baseline (post-K47 Array-env) + ssct-hm profiling

Captured post-K47 InterpreterBench baseline via `scripts/bench interp` (29 benchmarks, JMH,
macOS arm64 JDK 21): `recursionFib`=1.176ms, `typeclassFoldMacro`=1.350ms, `tupleMonoid`=0.007ms.
Full table in `v2/specs/k53-bench-baseline.md`. ssct-hm timing on hm-json.hm: ~3s wall / ~0.5s
user CPU (JVM startup dominates wall; hot path = HM unifier + let-poly over 90-fn prelude).
Optimization pass (K53c) deferred to BACKLOG pending JFR profiling of the short-lived process.
964b28113.

---

## 2026-06-30 — K51: ssct-hm stdlib expansion — assoc-list map ops + parser combinators

Added 13 prelude functions to `v2/lib/ssct-hm-front.ssc0`: four assoc-list map operations
(`assocInsert`, `assocDelete`, `assocMapKV`, `assocUnionWith`) and nine parser combinators
(`pResult`, `pChar`, `pStr`, `pDigit`, `pSeq`, `pAlt`, `pMap`, `pMany`, `pInt`).
Key fix: `injectPrelude` is a left-fold so new entries must appear BEFORE the existing prelude
entries they depend on (dependencies processed later = outermost = in scope first).
`assocUnionWith` works on JS (polymorphic `===`); VM/Rust have a known light-qualified-types
limitation for String-keyed maps. Examples: `hm-stdlib-map.hm` (30055) + `hm-parser-comb.hm`
(parse `"3+4*2"` → 11, all 3 backends). Conformance tests added. 2c0824c73.

---

## 2026-06-29 — JS SPA hash-route bridge sync for mounted UIs

Fixed a browser SPA runtime bridge bug that affected hash-routed std/ui apps such as rozum UCC
`clients/control/control-center-live.ssc`: `hashSignal()` updated the reactive graph on `hashchange`, but mounted
`data-ssc-cond` DOM subscribers were only refreshed through `_syncBridgeSignals()` after bridge-owned `_set(...)`
calls. `_ssc_ui_mount()` now also syncs the bridge on native `hashchange`, so route guards and hash-derived
computed displays switch immediately after clicking a navigation link, without requiring a browser refresh.

Guard: `JsGenStdImportTest` now changes the hash after mount and asserts the branch styles toggle; the adjacent
`SpaComputedBodyBridgeTest` computed-to-bridge regression remains green.

## 2026-06-29 — v2 K46: async futures/channels/mailboxes + roadmap reconcile

v2 status docs now match the K45 state: `ROADMAP.md`, `README.md`, Core IR notes, and JS backend notes no
longer list shipped work (`ssct-hm`, mathx, SHA-256, structural map/set, Rust backend) as open. K46 adds
`v2/specs/56-async-actors-breadth.md` and extends `lib/async.ssc0` with `runAsync`: futures/promises
(`future`/`await`), buffered integer channels (`send`/`recv`), and mailbox aliases
(`mailboxSend`/`mailboxReceive`) over the existing `Comp` effect representation. New examples cover future
await + repeated ready await, blocking receive, FIFO buffered receive, and ping/pong mailbox actors; all are in
`v2/conformance/check.sh` and run on VM, JS, and native Rust. Kernel remains frozen (`Kernel +0`).

Also fixed a conformance harness quoting bug where Markdown backticks in `echo` headings produced shell
`command not found` noise despite green assertions.

## 2026-06-23 — UCC web fixes (rozum/sunny-civet): F1 DataTable rowsPath + E3 def view() client-mode mount

Two compiler-side bugs reported from the Unified Control Center frontend:

**F1 — two `remoteTable`s on one `fetchUrlSignal` now both fill.** `DataTableLowering` discarded the
Remote source's `rowsPath` (hardcoded the `ForModel` field-path to `""`), so two DataTables sharing one
fetch signal both read the raw envelope under `sig.id` and collided — only one filled. The rowsPath is now
threaded into the `ForModel` field-path, so each table drills its own dotted path (`installed` /
`residency_metrics` / `result.items`) from the ONE shared raw-envelope state. Empty rowsPath unchanged
(root array). frontend/react DataTableEmitTest 14/14 (+2 F1 regression tests); frontendReact 59,
frontendVue 59, frontendCore 56 green.

**E3 — `def view()` now renders on the static codegen path (client-mode SPA).** The `def view()` convention
(one `.ssc` → web or terminal, no explicit `serve`) was honored only at interpret time
(`Interpreter.autoRunView`); the static JS compile (`compileJsSegments` → `JsGen`, used by
`run --mode client` + `emit-spa`) never saw a `serve(...)`, so a def-view-only module emitted no mount → a
blank client-mode page. New `AutoViewEntry.maybeInject` mirrors the convention for codegen: when a frontend
is selected, the module defines a zero-arg top-level `view`, and it calls no UI entry itself, it synthesizes
a top-level `serve(view(), 8080)` so JsGen emits the SPA + mount (same gating as autoRunView). cli
AutoViewEntryClientModeTest 5/5; JS/SPA/frontend CLI suites green.

## 2026-06-23 — rust-tui-toolkit S5: converge `--frontend tui` onto the live rust path (COMPLETE)

A new `ssc tui <file.ssc>` (alias `run-tui`) transpiles a `.ssc` UI to a Cargo crate with the ratatui View
renderer (`uiTarget=tui`) and `cargo run`s it with the TTY inherited on all three streams — so signal/
computedSignal reactivity is LIVE in the terminal (the crossterm loop). `ssc run --frontend tui` now routes to
the same live path (shared `TuiRunner`), superseding the static `frontend/tui` emitter, and falls back to the
interpreter path when cargo is absent. `RustBackend.compile` applies the tui intrinsic overlay only for
`uiTarget=tui`, so the web path is untouched. CLI tests: `tui`/`run-tui` registered, `validFrontendNames`
includes `tui`, and `compileViaBackend('rust', uiTarget=tui)` emits the ratatui crate (tui.rs + ratatui, no
hyper). This completes rust-tui-toolkit S1–S5 — `computedSignal` now renders AND updates live in the terminal,
with faithful flex/colors and fetched DataTables, all via the full `.ssc → Rust` transpile. Spec
`specs/rust-tui-toolkit.md` (COMPLETE).

## 2026-06-23 — rust-tui-toolkit S4: fetch family + DataTable/remoteTable on the rust path

`remoteTable`/`DataTable.Remote` now works on the rust-tui target end-to-end — the rust codegen path had
NONE of the fetch family before (the SSR `dataTableView` was a stub; `fetchUrlSignal`/`fetchRowsSource`/
`fieldColumn` were unmapped). A tui-only intrinsic OVERLAY (`RustTuiIntrinsics`, applied by `RustBackend.compile`
only when `uiTarget==tui`, so web keeps its stubs) points these at runtimes in `tui.rs`: `fetchUrlSignal` does a
blocking `ureq` GET at construction and seeds the signal store; `fetchRowsSource`/`fieldColumn` carry the
(signal, rowsPath) and (title, fieldPath) pairs; `dataTableView` encodes them into a `<table>` View; the renderer
parses the live signal JSON with `serde_json`, drills the rows path (dotted, or `data`/`rows`/`items`/`results`
envelope keys when empty), projects each row onto the column field paths, and renders a ratatui `Table`. ureq +
serde_json deps are added for the tui target; the tui target always emits the `ui` module (tui.rs needs `View`).
`RustGenTuiToolkitTest` 6/6 — the S4 cargo smoke serves a live `{"data":[...]}` envelope from a local
HttpServer and asserts the fetched rows render. Web rust 38/38 unchanged. Spec `specs/rust-tui-toolkit.md`.

## 2026-06-23 — rust-tui-toolkit S3: faithful tag → ratatui (flex layout + colors)

`tui.rs` now reads the CSS `style` attr (the std/ui `lower.ssc` bakes layout into it): a `div` with
`flex-direction:row` lays its children out horizontally (so `hstack` renders side-by-side; vertical otherwise),
and `color`/`background`/`font-weight` map to ratatui `fg`/`bg`/`BOLD` on the rendered `Paragraph` (`#rrggbb`
and basic color names). `RustGenTuiToolkitTest` 5/5 — the S3 cargo smoke builds a `flex-direction:row` div with
two spans (one `color:#ff0000`) and asserts both render on the same terminal line. Spec
`specs/rust-tui-toolkit.md`.

## 2026-06-23 — rust-tui-toolkit S2: live event loop — computedSignal updates on a keypress in the terminal

The emitted `tui.rs` now runs a real crossterm event loop: Tab/arrows move a focus ring over the action
elements (`data-ssc-set`/`data-ssc-toggle`), Enter/Space runs the focused action → `ssc_recompute_all()` →
redraw, so a `computedSignal` recomputes and re-renders on a keypress (the focused widget shows reversed). A
`SSC_TUI_SNAPSHOT` headless path renders the initial frame, applies the first action + recomputes, and renders
again — so the live update is testable without a TTY. `RustGenTuiToolkitTest` 4/4 — the new S2 cargo smoke
builds a `signal` + `computedSignal(()=>name())` + a `setSignal` button, and asserts frame 1 shows `BEFORE`
while frame 2 (after activating the button) shows `AFTER` — proving the derived value updates LIVE in the
terminal, the thing the static `frontend/tui` emitter could not do. Spec `specs/rust-tui-toolkit.md`.

## 2026-06-23 — rust-tui-toolkit S1: std/ui → ratatui via RustCodeWalk (computedSignal renders in the terminal)

First slice of routing the terminal through the Rust codegen backend so real `.ssc` UI logic — including
`computedSignal` thunks (already re-runnable Rust closures on this path) — transpiles to Rust and renders to
**ratatui** instead of HTML/SSR. `BackendOptions.extra("uiTarget"->"tui")` branches `RustGen`: emits a new
`src/runtime/tui.rs` (a `_tui_render(View)→ratatui` that reads the LIVE `ssc_signals()` store so signal/computed
values are current, + a draw-once `_tui_run`), routes `serve(view,port)` to a ratatui shim instead of the hyper
SSR server, and swaps the Cargo deps (ratatui, not hyper/tokio/ws). `RustGenTuiToolkitTest` 3/3 — string-match
(tui.rs + ratatui dep + shim, no hyper) + an `assume(cargo)` smoke that builds the emitted crate and asserts the
rendered terminal buffer contains the `computedSignal` value ("TUI_OK"). Web path unchanged (38/38 toolkit/
cargo-toml/runtime-files green). The interactive crossterm loop (making the value update live on a key) is S2.
Spec `specs/rust-tui-toolkit.md`.

## 2026-06-23 — frontend: unified `def view()` entrypoint convention (web + TUI from one .ssc)

A module that defines a zero-arg top-level `view` (and no `main`) is now auto-rendered through the active
frontend backend when a frontend is explicitly selected (`frontend:` front-matter / `--frontend` / inline):
`serve(view(), 8080)` for a web backend (React/SSR SPA) or `emit(view(), "tui-out")` for a native backend
(the ratatui `tui` crate). So one `.ssc` compiles to web OR terminal with **no** web-specific `serve(..., port)`
in the source — the only switch is the frontend backend. `Interpreter.autoRunView` (sibling of `autoCallMain`)
implements it, gated three ways so it never disturbs existing apps: it fires only when a frontend is explicitly
selected, when `view` is a 0-arg def, and when the module does **not** already call a UI entry
(`serve`/`emit`/`mount`/`serveAsync`, detected by `SectionRuntime.moduleCallsUiEntry`). `"tui"` is added to the
CLI `validFrontendNames` so `--frontend tui` (the explicit option) is accepted by `ssc run`. `frontendPlugin`
13/13 (incl. a 3-case convention test: native→emit once, no-frontend→no-op, explicit-emit→no double-fire).
Spec `specs/frontend-tui-ratatui.md`.

## 2026-06-23 — std/ui: remoteTable — DataTable from a nested JSON path of one fetch

`remoteTable(fetchSignal, columns, rowsPath = "installed", actions = [])` (in `std/ui/data.ssc`) builds a
`DataTable` whose rows live at a nested field of one fetch's response — e.g. `/control/status` returns an object
and the table is its `.installed` array. It composes the existing `fetchRowsSource(sig, rowsPath)` +
`dataTableView(source, …)` externs, so the `Remote` source carries the dotted `rowsPath` envelope; `""` = the
response root array or the built-in `{data|rows|items|results}` keys. Backend-agnostic: the React/web backend
drills the envelope and the terminal (ratatui) backend parses it (`fetch_rows`) — the same `.ssc` renders on
both. Pure `.ssc` addition (no Scala change); `fetchPlugin` 10/10 (a static check that the builder imports
`fetchRowsSource` and composes it into `dataTableView`, plus the existing behavioral test that the expansion
yields `Remote(sig, rowsPath)`). Example `examples/ui-remote-table.ssc`.

## 2026-06-23 — frontend/tui: DataTable.Remote live tables (fetched JSON → ratatui Table)

`DataTable` with a `Remote(FetchUrlSignal, rowsPath)` source now renders live data: the body is fetched into
`signals[id]` at bootstrap (the slice-5 fetch path) and parsed each frame into a ratatui `Table`. The emitted
crate gets `fetch_rows(json, rows_path, field_paths)` + `json_field` helpers (serde_json) that navigate the
`rowsPath` envelope (dotted, e.g. `result.items`) or the default `{data|rows|items|results}` keys, then extract
each column's `fieldPath` value per row (strings unquoted, numbers/bools via `to_string`, missing → empty).
`serde_json` is added to `Cargo.toml` only when a remote table exists. `frontendTui/test` 34/34 — a fast
emitter case + a cargo smoke that starts a local JSON `HttpServer` and asserts the fetched rows (demo/rozum +
their unread counts) render in the table. This is what the rozum control-API binds to (rooms/models tables).
Follow-up: `SignalRows` source + typed-model views. Spec `specs/frontend-tui-ratatui.md`.

## 2026-06-23 — frontend/tui: Style → ratatui colors + focus highlight

`TuiEmitter` now maps the typed `Style` to a ratatui `Style` per leaf: `text.foreground` / `decoration.background`
`Color` (Rgb/Rgba/Hex/Named/System-token) → `.fg`/`.bg`, `fontWeight` → `BOLD`/`DIM`, `Underline` → `UNDERLINED`;
styles thread through `Styled` (the `.foreground(…)` modifier DSL) and merge parent→child. The focused widget
also renders with `Modifier::REVERSED` (a visible focus highlight beyond the `> ` marker), tab headers included.
Unstyled text emits no `.style` clause. `frontendTui/test` 32/32 (incl. the cargo smokes, which now build with
the style clauses). Caveat: `std/ui` widgets carrying color as a CSS-string `style` attr lose it at
`NativeElementLowering` (a shared native-backend limitation) — colors arrive via the typed `Style`/modifier DSL;
decoding the CSS strings is a separate follow-up. Spec `specs/frontend-tui-ratatui.md`.

## 2026-06-23 — frontend/tui: native-emit dispatch for `emit(view, dir)` (unblocks authoring a TUI `.ssc`)

The `emit(view, outDir)` UI intrinsic now dispatches by the active frontend backend's `supportedPlatforms`
instead of always calling the web `emit`: web backends still write `index.html`/`app.js`/`app.css`, while a
native-only backend (the `tui` ratatui backend) routes through `emitNative` and writes its source tree
(`Cargo.toml` + `src/main.rs`) to `outDir`, printing the build command (`cargo run`). `serve(view, port)` with
a native backend now raises a clear `PluginError` (a terminal app isn't served over HTTP — use `emit`) instead
of the opaque `UnsupportedOperationException` that `tui.emit` threw. Before this, a Tk `.ssc` with
`frontend: tui` could not be built at all — `emit`/`serve` only ever called the web `emit`. So now:
`frontend: tui` front-matter + `emit(view, "out/")` → `cd out && cargo run`. New
`FrontendIntrinsics.emitFrontendArtifact` (the testable dispatch) + `FrontendNativeEmitTest` (2 cases: native
writes the crate tree, web writes html/js); `frontendPlugin/test` 10/10. Spec `specs/frontend-tui-ratatui.md`.

## 2026-06-23 — frontend/tui slice 5: fetch data binding — MILESTONE COMPLETE (slices 0–5)

`TuiEmitter.collectFetches` finds every `FetchUrlSignal` (which is a `ReactiveSignal[String]` carrying a URL)
referenced by `SignalText`/`DataTable.Remote`/`ModelView`; the emitted crate gets `fetch_text(url)` (a blocking
`ureq` GET) plus a `bootstrap(signals)` that populates each fetch signal at startup — called before the first
render in both the snapshot and interactive paths — so a `SignalText` bound to a fetch signal renders the
fetched body. `ureq` is added to `Cargo.toml` only when the app actually fetches (non-fetch crates stay lean).
`frontendTui/test` 28/28 — two fast emitter cases plus a third cargo smoke that starts a local JDK `HttpServer`,
builds a crate whose `SignalText` is bound to it, and asserts the snapshot contains the fetched body.

**This completes the `frontend/tui` ratatui backend (slices 0–5):** scaffold + selection, static layout/text,
reactive signal store + crossterm redraw loop, focus ring + keyboard + events, `DataTable`/`TabBar`/
`NavigationStack`, and HTTP fetch-binding. The scalascript side of the rozum Unified Control Center is done — a
single `std/ui` Tk `.ssc` app now compiles to a terminal binary (as well as web/desktop), so rozum can retire
its hand-written `crates/rozum-meeting/src/tui`. Open follow-ups (non-blocking): `Style`/`Theme` color mapping,
`A11y.focusOrder` seeding, typed-model dynamic tables, `Sheet`/`AlertDialog` overlays, CLI `--frontend tui`
native-emit flag. Spec `specs/frontend-tui-ratatui.md`.

## 2026-06-23 — frontend/tui slice 4: tables + reactive tabs/routing

`TuiEmitter` now lowers the data/navigation `View` nodes. `DataTable` with a `StaticRows` source → a ratatui
`Table` (header from the column titles, cells from each row's `fieldPath`; `Remote`/`SignalRows` render a
placeholder until slice 5). `TabBar` → a header row of focusable tab labels (the active one shown as
`[label]`) whose activation `Set(current, idx)` switches tabs, plus a runtime `match sig_int(current)` that
renders the active tab's content — reusing the slice-3 focus/activate machinery, so Tab-to-a-header + Enter
switches tabs. `NavigationStack` → a runtime `match sig(current).as_str()` over the named routes. Added a
`sig_int` accessor. `Badge`/`Spinner`/`Pill`/`Tag` chrome already lowers to text via `std/ui` and renders as
such (color styling stays deferred). `frontendTui/test` 25/25 — three fast emitter cases plus a second cargo
smoke that builds a `TabBar[DataTable, …]` crate and asserts its snapshot shows the active `[Rooms]` tab, the
table header (Room/Unread), and the rows. This is UCC PoC step 3 (room switcher). Spec
`specs/frontend-tui-ratatui.md`.

## 2026-06-23 — frontend/tui slice 3: focus ring + keyboard + events (interactive)

The emitted ratatui crate is now interactive. `TuiEmitter` assigns each focusable widget
(`Button`/`TextInput`/`Toggle`) a document-order focus index and generates `FOCUS_COUNT`, `is_text_input`,
`focus_mark` (a `> ` indicator the focused widget renders), per-index `activate`/`type_char`/`backspace` match
arms, and a `handle_key` dispatcher (Tab/↓ + Shift-Tab/↑ traversal, Enter/Space → activate, typing → edit a
focused `TextInput`, Backspace, Esc/`q` → quit). `render_root(frame, area, signals, focus)` threads focus and
the crossterm loop holds `mut signals` + `mut focus`. `EventHandler` execution covers the declarative handlers
(`SetSignalLiteral`/`IncrementSignal`/`ToggleSignal`, plus `TextInput` `InputChange`); `Simple`/`WithEvent` are
Scala closures with no Rust equivalent → no-op. `frontendTui/test` 21/21 — the cargo smoke builds an interactive
crate (signal + button + text-input) and `cargo test` runs generated `event_handlers_run` (button mutates the
store), `text_input_typing`, `tab_moves_focus`, and `reactive_rerender`. This is UCC PoC step 2 (composer).
Follow-ups: `A11y.focusOrder` seeding, hidden-branch focus skipping. Spec `specs/frontend-tui-ratatui.md`.

## 2026-06-23 — frontend/tui slice 2: signal store + crossterm redraw loop

The emitted ratatui crate is now reactive. `TuiEmitter` collects the View tree's `ReactiveSignal`s and emits a
runtime signal store (`HashMap<String, Value>` with a `Value` enum `S/I/B`) seeded with their initial values;
`render_root(frame, area, signals)` reads `SignalText` → `sig(...)`, `Toggle` → `toggle_text(...)`, `TextInput`
→ `text_input_display(...)`, and `ShowSignal` → a runtime `if sig_truthy(...)` branch, re-read each frame.
`main` runs a real crossterm event loop (raw mode + alternate screen → draw → `event::poll` → quit on `q`/Esc →
teardown), reached through ratatui's crossterm re-export (no extra dependency); a headless `SSC_TUI_SNAPSHOT`
env path renders one `TestBackend` frame for CI. `frontendTui/test` 20/20 — the cargo smoke builds the loop
crate, renders a signal-bound frame headlessly, and runs `cargo test` on a generated `#[cfg(test)]
reactive_rerender` that mutates a signal and asserts the frame changes. Events that *mutate* signals land in
slice 3. Spec `specs/frontend-tui-ratatui.md`.

## 2026-06-23 — frontend/tui slice 1: static layout + text → ratatui

`TuiEmitter` now lowers the framework-agnostic `View` IR (post `NativeElementLowering`) to a recursive
`render_root` ratatui renderer: `Column/Fragment/For/LazyList` → vertical `Layout` with measured
`Constraint::Length`, `Row` → horizontal `Layout` (`Constraint::Ratio(1,n)`), `Stack`/`ScrollView`/`Styled`
pass-through, `Text/SignalText/TextNode` → `Paragraph`, `Divider` → top-border `Block`, `Spacer` → reserved
rows, `Show/ShowSignal` static-evaluated; interactive nodes (`Button`/`TextInput`/`Toggle`) render as static
text (events land in slice 3); `Style`/`Theme` mapping deferred. Emitted crate carries a headless
`TestBackend` buffer-snapshot harness so it runs without a TTY. `frontendTui/test` 18/18 — 10 fast
`TuiEmitterTest` string-match cases + a `TuiCargoSmokeTest` (assume(cargo)) that builds + runs the crate and
asserts the rendered buffer (heading + text + divider + side-by-side row). Spec `specs/frontend-tui-ratatui.md`.

## 2026-06-23 — frontend/tui (ratatui terminal-UI backend) — spec + slice 0 scaffold

Scalascript-side half of the rozum Unified Control Center: a new `frontend/tui` render backend so one `std/ui`
(Tk) `.ssc` app compiles to a terminal UI (ratatui) as well as web/desktop. Spec `specs/frontend-tui-ratatui.md`
(+ answers to the rozum side's 3 questions: backend selection via `FrontendFrameworks` / focus-keyboard is
backend-side with core `A11y` hints / scalascript owns the whole compiler side). SPRINT track `frontend-tui-*`
(6 slices). **Slice 0 (scaffold) landed:** new `frontendTui` sbt module — `TuiFrameworkBackend extends
FrontendFrameworkSpi` (`name="tui"`) emitting a self-contained ratatui+crossterm Rust crate via the
`emitNative` (Swing/JavaFX native) pattern, NOT via RustCodeWalk; additive `Platform.Terminal` +
`AppFormat.RatatuiApp` in `frontend/core`; registered in build.sbt `allFrontends`. `frontendTui/test` 8/8
including an assume(cargo) smoke that builds + runs the emitted crate (ratatui 0.29). Slices 1–5 (the
`View → ratatui` lowering table) remain.

## 2026-06-23 — Crypto/finance roadmap planned + grouped (docs + spec + SPRINT/BACKLOG)

Turned the loose forward-looking brainstorm in `docs/capabilities.md §7` into a committed, grouped roadmap so
the area isn't scattered: new explainer `docs/crypto-finance-roadmap.md` (what / why / where / benefit for every
item — chains, threshold/MPC signing, identity & token services, "invent our own" products) + companion
engineering plan `specs/crypto-finance-roadmap.md` (sliced work, file pointers, acceptance gates, sequencing,
all on the FROST `reference → seam → gate → native` template). Near-term codeable slices queued in `SPRINT.md`
(crypto-spi-blake2b, noble-js-hd-derivation, chains-backend-agnostic for Cardano/Bitcoin/Cosmos, client-solana-rpc,
frost-secp256k1, frost-distributed-transport, totp-hotp, shamir-secret-backup); larger epics in `BACKLOG.md`
(pure-Scala primitive references, new chain adapters, MuSig2, threshold-ECDSA, VRF/BLS, WebAuthn-verify,
PASETO/JWT/COSE, Noise, DID/VC, age, threshold-custody-wallet, own micropayment scheme, distributed infra).
Corrected two stale claims against the code while grouping: **Keccak-256 + RIPEMD-160 already exist in the SPI —
Blake2b is the one missing hash**; **Solana already broadcasts** (via the generic RPC seam) — its only gap is a
turnkey client module. Planning/docs only — no implementation in this change.

## 2026-06-23 — FROST-Ed25519 slice 8: wallet vault integration (`walletVaultMpcFrost`)

FROST wired into the wallet stack as an in-house threshold provider. `FrostSigningClient` implements the existing
`McpVault` `RemoteSigningClient` seam — the same seam the external MPC providers (Fireblocks/Coinbase/Lit/Zengo)
use — but runs the project's own FROST-Ed25519 protocol locally over a `FrostQuorum` instead of calling a
third-party TSS service. A threshold wallet is therefore just `McpVault("…", new FrostSigningClient(Seq(quorum)))`
(kind `Mpc`) with no new `Vault` implementation. The quorum is the trusted-coordinator / single-node form; a
distributed transport (the production counterpart of `HttpRemoteSigningClient`) is the only remaining piece for a
fully-distributed deployment, and `sk` is never reconstructed either way. New module `walletVaultMpcFrost`
(dependsOn `walletVaultMpc` + `cryptoFrost`; BouncyCastle test-only). Verified 3/0: vault unlock → getSigner →
sign yields a 64-byte signature that verifies under standard BouncyCastle Ed25519 (across distinct signing
subsets); non-Ed25519 curves, unknown accounts, and sub-threshold quorums are rejected. Closes the FROST track
(slices 1–8). Spec `specs/frost-ed25519.md`.

---

## 2026-06-23 — FROST-Ed25519 slice 7: native crypto-provider backend (CryptoBackend → BC/noble)

`CryptoBackedEd25519Ops` — an `Ed25519Ops` backend that delegates the substitutable primitives (SHA-512, secure
randomness) to the project's `CryptoBackend` SPI (BouncyCastle on JVM, `@noble` on JS), keeping the pure-BigInteger
group math of the reference (CryptoBackend has no Ed25519 group ops). Register it and FROST's hashing/RNG
transparently run on the platform-native crypto provider; `reset()` restores the pure reference. `cryptoFrost`
now `dependsOn cryptoSpi` (which itself has no external deps). Verified (cryptoFrost JVM 20/0): the BC SHA-512
equals our reference SHA-512, and a 2-of-3 FROST signature produced with the BC-backed backend verifies under
BouncyCastle Ed25519; JS still 6/0 (the shared bridge cross-compiles). This closes the loop on the architecture
— one portable FROST reference + transparent substitution down to the underlying crypto provider. Remaining:
slice 8 (wallet-vault integration). Spec `specs/frost-ed25519.md`.

## 2026-06-23 — FROST-Ed25519 slice 6c: cross-build — the reference runs on JS (and JVM)

`cryptoFrost` is now a `crossProject(JVM, JS)`: the FROST reference (Ed25519 curve math + own SHA-512 + keygen +
signing + the `Ed25519Ops` seam) is pure `BigInteger`/`Long`, so it compiles and RUNS on both JVM and Scala.js.
Only `PlatformEntropy` is per-platform (JVM `SecureRandom` / JS `globalThis.crypto.getRandomValues`); shared
cross-platform tests (`FrostKeygenTest`, `Ed25519OpsSeamTest`) run on BOTH — **6/6 green on Node** including
`generate(3,5)` (WebCrypto randomness through the seam) and the backend-substitution test; JVM 19/0 (BC
cross-checks + java.security `Sha512` parity stay JVM-only in `jvm/`). This proves the architecture end-to-end:
ONE ScalaScript-stack reference implementation of FROST threshold signing, identical on JVM and JS, with
platform-native randomness and transparent native-backend substitution. **FROST cross-platform story COMPLETE.**
Spec `specs/frost-ed25519.md`. Remaining FROST slices: 7 (JVM BouncyCastle native backend), 8 (wallet-vault
integration).

## 2026-06-23 — FROST-Ed25519 slice 6b: randomness via the Ed25519Ops seam

Routed FROST's randomness through the seam: `Ed25519Ops.randomBytes(n)` + `randomScalar()` (Reference = JVM
`SecureRandom`). `FrostKeygen.generate` and `FrostSign.round1` dropped their hardcoded `rng: SecureRandom`
params and now source secure randomness from `Ed25519Ops.current` — so FROST's LOGIC is fully `java.security`-free
(only the JVM default backend's `randomBytes` uses `SecureRandom`, which slice 6c splits per-platform), AND
randomness is now a substitutable primitive consistent with the architecture (a native backend / a deterministic
test backend can supply it; JS will use `crypto.getRandomValues`). Tests updated (they assert properties —
verify / reconstruct — so any secure randomness is valid); cryptoFrost 19/0. Spec `specs/frost-ed25519.md`.

## 2026-06-23 — FROST-Ed25519 slice 6a: portable SHA-512 (remove java.security from hashing)

Toward cross-platform FROST: a pure-Scala `Sha512` (FIPS 180-4, 64-bit `Long` — identical on JVM and Scala.js,
no platform crypto API). Routed `Ed25519Ops.Reference.sha512` and `Ed25519Group.secretScalar` through it, so
FROST hashing no longer uses `java.security`. `Sha512Test`: matches the FIPS 180-4 'abc' + empty-string vectors
and `java.security.MessageDigest` across all padding boundaries (lengths 0..1000); all FROST tests still pass
through the new hash (cryptoFrost 19/0). Probe finding (refines the cross-build plan): the remaining JVM-only
dep is `java.security.SecureRandom` (Scala.js 1.20 lacks it) — slice 6b abstracts randomness through the
`Ed25519Ops` seam (also making it a substitutable primitive), then 6c does the `crossProject(JVM,JS)` module.
Spec `specs/frost-ed25519.md`.

## 2026-06-23 — FROST-Ed25519 slice 5: pluggable Ed25519Ops seam (transparent native substitution)

Gave FROST the same pluggable-backend model as `CryptoBackend` (per Sergiy's architecture: portable reference +
transparent native substitution). New `Ed25519Ops` trait — the Ed25519 primitives FROST needs (point ops,
scalar field, `secretScalar`, `sha512`) — with `Ed25519Ops.Reference` (the pure-BigInteger `Ed25519Group` + JDK
SHA-512) as the DEFAULT and a registry (`current`/`register`/`reset`). `FrostKeygen`/`FrostSign` now call only
through `Ed25519Ops.current` (incl. SHA-512 — no more direct `java.security`), so a platform may register a
native implementation (BouncyCastle on JVM, @noble on JS, a Rust crate on Rust) that FROST uses transparently;
the reference stays the correctness fallback. Behaviour-preserving (all 14 prior FROST tests pass through the
seam) + a substitution test: a registered spy backend IS exercised by a full keygen+sign, reset restores the
reference. cryptoFrost 16/0. Spec `specs/frost-ed25519.md` §3b. Next: cross-build (JS) + a JVM native backend.

## 2026-06-23 — FROST-Ed25519 slices 3+4: threshold signing — verifies under standard Ed25519

`FrostSign` (cryptoFrost): the FROST two-round threshold signing flow, producing a STANDARD 64-byte Ed25519
signature any RFC 8032 verifier accepts. Round 1: per-signer nonces `(d,e)` + commitments `(D,E)=(B·d,B·e)`.
Round 2: binding factor `ρ_i = SHA-512(domain‖id‖msg‖commitments) mod L`; group commitment `R = Σ(D_i+ρ_i·E_i)`;
Ed25519 challenge `c = SHA-512(R‖A‖msg) mod L`; partial `z_i = d_i + ρ_i·e_i + λ_i·c·s_i`; aggregate
`z = Σ z_i` → `(encode(R) ‖ scalarLE(z))`. Because `B·z = R + c·A`, the result verifies under plain Ed25519,
so a `t`-of-`n` quorum signs without ever reconstructing the key; `ρ_i` binds nonces to the signing
(Drijvers/ROS defense). **THE correctness gate:** `FrostSignTest` (4/0, cryptoFrost 14/0) — a 2-of-3 and EVERY
3-of-5 subset's signature verifies under BouncyCastle Ed25519; a tampered partial and a wrong message are
rejected. FROST-Ed25519 is now functionally complete (group ops + keygen + signing); only wallet-vault
integration (slice 5) remains. Spec `specs/frost-ed25519.md` slices 3-4.

## 2026-06-23 — FROST-Ed25519 slice 2: trusted-dealer Shamir keygen + Feldman VSS

`FrostKeygen` (cryptoFrost): trusted-dealer `t`-of-`n` secret sharing over the Ed25519 scalar field (mod L).
A signing scalar is split via a degree-(t-1) polynomial into shares `(id, f(id))`; the group public key is
`B·sk`; Feldman commitments `B·a_j` let each participant verify its share (`B·value == Σ commitment_j·id^j`).
`reconstruct` Lagrange-interpolates the secret at x=0 from any `t` shares. `generateFrom` (explicit coeffs) is
the deterministic-test hook and the building block a real DKG sums per-party polynomials with. Tested
(`FrostKeygenTest` 4/0, cryptoFrost 10/0): any t-subset recovers sk + B·sk matches the group key; <t do not;
all t-subsets agree; VSS verifies good shares and rejects a tampered one. Spec `specs/frost-ed25519.md` slice 2.

## 2026-06-23 — FROST-Ed25519 slice 1: from-scratch Ed25519 group arithmetic

First slice of the FROST threshold-signing feature (Sergiy chose from-scratch curve math over a new crypto
dependency). New `cryptoFrost` module (`payments/crypto/frost`, pure — BouncyCastle test-only): `Ed25519Group`
implements the RFC 8032 reference group arithmetic over `java.math.BigInteger` — field mod 2^255-19,
twisted-Edwards points (extended coords, unified add), scalar mult (double-and-add), point encode/decode, base
point B, order L, scalar field (add/mul/inv/reduce), and `secretScalar` (clamp∘SHA-512). Correctness-gated by
`Ed25519GroupTest` (6/0): RFC-8032 base-point encoding, L·B = identity, encode/decode round-trip, group
homomorphism, scalar inverse, and the authoritative check — generated public keys match BouncyCastle Ed25519
bit-for-bit for 25 random seeds. Not constant-time (reference-first). Foundation for keygen/signing/aggregate
slices. Spec `specs/frost-ed25519.md`.

## 2026-06-23 — remote-package-registry slice 5: publish auth (Bearer token) — registry code COMPLETE

`RegistryHttpServer` gains optional `publishTokens: Set[String]`: when non-empty, `POST /publish` requires
`Authorization: Bearer <token>` with a token in the set (else 401 + `WWW-Authenticate: Bearer`); empty = open
(the reference/dev default). GET reads stay public. Test: publish rejected with no/wrong token/wrong scheme,
accepted with a valid token; reads still public. `RegistryHttpServerTest` 2/0. This completes the codeable
scope of **remote-package-registry** — protocol + reference catalog (`FileRegistry`) + `packages.yaml` bridge
to the existing client + `ssc plugin registry publish` + reference HTTP server + auth. Only EXTERNAL deploy
(host `registry.scalascript.io` + TLS) remains. Spec `specs/arch-build-registry.md` §6b.

## 2026-06-23 — remote-package-registry slice 4: reference HTTP server over FileRegistry

`RegistryHttpServer` — a minimal, dependency-free (JDK `com.sun.net.httpserver`) reference server over a
`FileRegistry`, the thing a hosted `registry.scalascript.io` runs: `GET /packages.yaml` (the client index the
existing `RegistryClient`/`ssc search` already fetch), `GET /packages/<id>/<version>.sscpkg` (artifact bytes),
`POST /publish/<id>/<version>[?description=…]`. Auto-derives its self-referencing base URL from the bound port
when none is given. Bound to loopback by default; auth/TLS are slice 5/deploy. In-process round-trip test
(`java.net.http.HttpClient`: POST publish → GET packages.yaml → GET artifact → 404). `RemoteRegistryTest` +
`RegistryHttpServerTest` 10/0. Spec `specs/arch-build-registry.md` §6b.

## 2026-06-23 — remote-package-registry slice 3: `ssc plugin registry publish`

A publish command under the existing (non-conflicting) `ssc plugin registry` subcommand group (`ssc publish`
is taken by app-store upload). `ssc plugin registry publish <pkg.sscpkg> [--registry <dir>] [--base-url <url>]
[--description <t>]` reads id/version from the package manifest (new `SscpkgLoader.loadManifest` — manifest-only,
no JAR extraction), publishes into a server-side `FileRegistry` (content store + index.json), and regenerates
the client-facing `packages.yaml`. Round-trip tested (build a temp `.sscpkg` → loadManifest → publish → fetch →
client `LocalRegistry.resolve` sees it). `RemoteRegistryTest` 9/0; cli compiles. Spec
`specs/arch-build-registry.md` §6b.

## 2026-06-23 — remote-package-registry slice 2: FileRegistry emits client `packages.yaml`

Bridged the new server-side `FileRegistry` to the EXISTING client registry format. Probing revealed the
client half is already complete — `RegistryClient` fetches+caches `packages.yaml` from a URL, and `ssc search`
/ `ssc install` / `LocalRegistry` consume it (`ssc publish` is taken by app-store upload). So instead of a new
`index.json` client contract, `FileRegistry.exportPackagesYaml(baseUrl)` / `writePackagesYaml` project the
catalog into the client's `LocalRegistry.Entry` `packages.yaml` shape (one entry per id at its latest version,
`url` → the stored artifact) — so a `FileRegistry`-served directory is consumed by the current client
unchanged; the richer `index.json` (checksums/all-versions) stays the publish-side record. Test round-trips
through the existing `LocalRegistry.parseFile`/`resolve`. `RemoteRegistryTest` 8/0. Spec
`specs/arch-build-registry.md` §6b.

## 2026-06-23 — core-min actors: distributed server hook seam

Added the second green slice of `coremin-actors-codemove`: `ActorRuntimeHost` now exposes the distributed
hooks a moved runtime needs for outbound WebSocket clients, `_ssc-actors` WebSocket route registration, and
cluster-control HTTP route registration. The current core delegate still owns actor scheduling; behavior is
unchanged, and provider tests verify a custom provider can use the new host route hooks.

## 2026-06-23 — core-min actors: explicit runtime host-service seam

Added the first green slice of `coremin-actors-codemove`: `ActorRuntimeHost` now exposes the non-server
interpreter services needed by a moved actors runtime (`out`, closure calls, receive-spec lookup/matching,
and native feature state). The bundled actors plugin still delegates through the core scheduler for now, so
behavior is unchanged; provider tests now prove a custom provider can use the explicit host services without
a direct `Interpreter` self-type.

## 2026-06-23 — remote-package-registry slice 1: registry protocol + file-backed reference registry

Started the remote half of the plugin registry (the client side — `LocalRegistry` alias map +
`RemotePluginInstaller` URI download + `ssc install` — was already done). Added `RemoteRegistry` (protocol)
+ `FileRegistry` (reference server-side catalog): publish a `.sscpkg` by `(id, version)` with a SHA-256
checksum, search by id/description substring, resolve by exact version or `latest`, list versions, and fetch
bytes (checksum-verified). Releases are immutable (idempotent re-publish of identical content; different
content under the same `(id,version)` rejected). The index + entries serialize as JSON — the wire format a
future HTTP `registry.scalascript.io` will serve — so `FileRegistry` is both the round-trip test harness and
the implementation an HTTP service wraps. Locked by `RemoteRegistryTest` (7/0). Follow-ups: `ssc publish`/
`ssc search` CLI, an HTTP server, remote `pkg:` resolution. Greenfield/additive — no existing code changed.
Spec: `specs/arch-build-registry.md` §6b.

## 2026-06-23 — stable-SPI Phase 3: foundation — stable Value-surface in plugin-api (+ mime migrated)

Starts Phase 3 (decouple the 28 plugin `*Intrinsics` from `import scalascript.interpreter.*`). The blocker
(probed) was that `PluginValue`/`PluginComputation` were opaque `Any` with no accessors, so plugins had to
keep importing the interpreter's `Value`. **Foundation:** `scalascript-plugin-api` now `dependsOn(core)` — the
ONE controlled seam (acyclic: core doesn't depend on pluginApi) — and `PluginValue` exposes a stable
Value-surface backed by the interpreter `Value`: extractors `asString/asInt/asDouble/asBool/asChar/asList/
asTuple/asMap/asOption`, constructors `string/int/double/bool/char/list/tuple/map/some/none/unit`, and `show`.
`PluginError` now builds the real `InterpretError` (identical error reporting) and adds `raise(msg): Nothing`.
This moves the interpreter coupling from 28 plugins into one stable module; the opaque `PluginValue` keeps the
plugin ABI stable as core's `Value` repr changes (e.g. value-unification). **Proof:** `mime-plugin` migrated
fully off `scalascript.interpreter` (uses `PluginValue.asString/asList/asTuple/show` + `PluginValue.string` +
`PluginError.raise`). `pluginApi/test` 14/0, `mimePlugin/test` 4/0, `PluginExamplesSmokeTest` (invoice-email)
1/0. Remaining 27 plugins migrate in batches A/B/C (see SPRINT); `evalLegacy` removal + the
`reject scalascript/interpreter in plugin jars` build check come in `p3-enforce`.

## 2026-06-23 — feat(core-min): `ssc check` auto-loads a bundled-opt-in plugin from the file's imports

Removes the UX cliff the advanced-optin strict-opt-in introduced: a file using an advanced plugin's
names (e.g. `x402-*` → payments) no longer needs a manual `--plugin` to `ssc check`. New SPI hook
`Backend.providesImports: List[String]` — the import namespaces a plugin owns (payments →
`scalascript.x402`, oauth → `scalascript.oauth`/`scalascript.oidc`, spark → `scalascript.spark`).
`ssc check` collects the module's import prefixes (from the ```scalascript code-block trees, where
these imports live — `scala.meta.Import` refs — plus doc-level `Content.Import`), and
`BackendRegistry.importMatchedPreludeSymbols` scans `lib/compiler/plugin-available/` `.sscpkg` packages
with a THROWAWAY `URLClassLoader` (so a non-matching plugin is never committed to the runtime), folding
in `preludeSymbols` from packages whose `providesImports` matches. Wired into both `ssc check` and
`check-with-iface`; a `-Dscalascript.pluginAvailableDir=` override aids tests/custom layouts.
**Verified end-to-end:** `ssc check examples/x402-client.ssc` → `OK` with the staged payments package
(was `undefined name: DefaultSyncBackend / basicRequest`), still errors without it (autoload is what
fixes it), and `hello.ssc` is unaffected (import-gated — payments isn't over-loaded). `CheckAutoloadImportTest`
locks the matching + import-extraction; plugin-tests 712/0, cli smoke 2/0. The 7 advanced examples'
notes were updated to say `ssc check` auto-detects the plugin.

## 2026-06-23 — value-unification Slices 5-6: scalar leaves shared between `Value` and `SpiValue` (scalars-only COMPLETE)

Finished the scalars-only value-unification. **Slice 5:** moved `DataValue` (the 9 scalar leaves split out in
Slice 4) into a new low leaf module `lang/value-data` (no deps), below `core` and `backendSpi`. **Slice 6:**
`SpiValue` is now `type SpiValue = DataValue | SpiRest` — its scalar leaves are the **same shared `DataValue`
classes** the interpreter's `Value` uses (`SpiRest` = the SPI-private containers + `Opaque`). `object SpiValue`
re-exports `DataValue` (`StringV` as `StrV`, the historical SPI name) + `SpiRest`, so all `SpiValue.IntV`/`StrV`/
`ListV` sites — incl. the 9 effect plugins — are unchanged. `valueToSpi`/`spiToValue` now convert scalar leaves
by **identity** (`case d: DataValue => d`, no realloc/rewrap), since a scalar `Value` already *is* an `SpiValue`;
only containers + `Opaque` do real work. Bonus: `BigInt`/`Decimal`/`Null` now cross the SPI as structured cases
instead of `Opaque`. The scalar half of the duplication is gone — one set of scalar classes across interp + SPI;
the container half stays converted by design (a container can hold a closure, which isn't host-neutral data —
the obstacle that made full merge a hot-path perf regression). Verified: valueData + backendSpi + interpreter +
all plugins compile; core/test 1019/0, plugin-tests 712/0, round-trip + StdEffects + Interpreter + BigInt +
Decimal 183/0. Spec: `specs/value-unification.md` (Slices 5-6).
## 2026-06-23 — value-unification Slice 4: `Value` is now a union, scalar leaves split into `DataValue`

Flipped the interpreter's `Value` from a `sealed trait` to a **union** `type Value = DataValue | ValueRest`.
`DataValue` (new `enum`, `DataValue.scala`) holds the 9 pure-data **scalar leaves** (`IntV`/`DoubleV`/`BigIntV`/
`DecimalV`/`StringV`/`BoolV`/`CharV`/`UnitV`/`NullV`); `ValueRest` (sealed trait) holds the 14 container/
instance/carrier cases (which recursively hold an arbitrary `Value`, incl. closures — `List(() => 10)` — so
they can't be host-neutral data). `object Value` re-exports the scalars via `export DataValue.*`, so all ~4387
`Value.<Case>` sites are unchanged. This is the scalars-only partial unification (Sergiy's call after the
container/closure obstacle ruled out full conversion-deletion: a fully-merged low data type would force
closures-as-`Opaque`, regressing the hot function-dispatch path). The split is the groundwork for sharing the
scalar leaves with `SpiValue` (next slices) so the scalar half of the conversion becomes identity.
**Remarkably clean:** the only friction across 4387 sites was one `java.util.Arrays.sort` over a union-typed
array (Java-generic inference can't bound a union → cast to `Array[AnyRef]`); everything else compiled via
`export`, and union exhaustiveness checking is preserved. Verified: core + backendInterpreter + all plugins +
interpreter-server + dap compile; core/test 1019/0, plugin-tests 712/0, broad interp/value/effects 218/0,
numeric/collection/JIT 77/0 (~2026 tests green, 0 fail). Spec: `specs/value-unification.md` §3–4.

## 2026-06-23 — docs: advanced-plugin examples note their `ssc check` opt-in requirement

Follow-up to the advanced-optin strict-opt-in (`pluginObjects`/`pluginBuiltins` plugin names moved off
the hardcoded prelude): the 7 examples that use advanced-plugin names (`x402-*` → payments, `oauth`/
`oidc` → oauth) now `ssc check` with `undefined name: …` unless the plugin is added — the intended
strict-opt-in, but a UX cliff. Verified the failure is real (`ssc check examples/x402-client.ssc` →
`undefined name: DefaultSyncBackend / basicRequest`) and added a uniform "Advanced plugin" blockquote to
each, pointing at `--plugin <…/plugin-available/…>` / `ssc plugin install`. Fence-lint + cli smoke 2/0.
(The fuller fix — `ssc check` auto-loading a bundled plugin when the file imports its namespace — was
scoped: it needs a `Backend.providesImports` SPI + `plugin-available` discovery + a per-check scan cost +
installBin staging to verify, i.e. a medium feature disproportionate to the gain; queued in SPRINT as
`check-autoload-plugin-by-import` with the verified premise + design for a future session.)

## 2026-06-23 — docs: BACKLOG open-item hygiene

Classified stale `BACKLOG.md` open rows so agents can distinguish claimable work from
blocked, deferred/product-gated, duplicate, and history-only notes. No code changed.

## 2026-06-23 — rust backend: index `List` parameters as Vecs

Fixed another Rust lowering gap exposed by the rozum meeting client rebuild: `xs(0)` on a
parameter declared as `List[String]` was emitted as a Rust function call `xs(0)`, while only
local/top-level sequence vals were marked indexable. Sequence-typed parameters now participate
in the same Vec indexing path, so reads lower to `xs[(i) as usize].clone()`. Added a regression
test for `def first(xs: List[String]): String = xs(0)`.

## 2026-06-23 — value-unification Slice 3 (spike): union `Value` + `export` decided

De-risked the load-bearing mechanism for the value-unification migration with a throwaway scala-cli spike.
**Decision: `type Value = DataValue | Callable` (union) + `export DataValue.*` from `object Value`.** Validated
that existing `Value.IntV(n)` construction and `case Value.IntV(n)` patterns compile unchanged, that `DataValue`
can live in a module *below* core (it extends nothing core — only the union makes it a `Value`), and that
exhaustiveness checking is preserved (a non-exhaustive `Value` match is flagged, error under `-Werror`). This
means the ~4387 `Value.<Case>` sites should largely survive the eventual module split. Rejected: a `DataValue
extends Value` marker (wrong dep direction) and a bare union without `export` (would churn all 4387 sites).
Spec updated (`specs/value-unification.md` Slice 3). No production code changed — next is Slice 4 (create the
`value-data` module + migrate the first case behind the union+export bridge).

## 2026-06-23 — docs: board/spec hygiene after core-min/polyglot sprint

Reconciled stale future-looking wording in `SPRINT.md` and `specs/polyglot-libraries.md`
after the optics host libraries, runtime-resource moves, advanced opt-in prelude cleanup,
and core-min Phase 3+ slices landed. No code changed.

## 2026-06-23 — core-min: lossless `Char`/`Vector` across the SPI boundary (value-unification slice 1)

The `Value ↔ SpiValue` conversion (block-form/effect-handler boundary) silently coerced two pure-data
cases: a `Char` became a 1-char `StrV` and a `Vector` became a `ListV` — so a value crossing into a
plugin handler and back changed type (e.g. `Random.pick(List('a','b'))` would hand back a `String`, and
a `Vector` would `toString` as `List(…)`). Added dedicated `SpiValue.CharV(Char)` and
`SpiValue.VectorV(List[SpiValue])` cases; `valueToSpi`/`spiToValue` now map `Char`/`Vector` losslessly.
This is the first foundational slice of `core-min-value-unification`: before the data ADT can *be*
`SpiValue`, `SpiValue` has to faithfully cover `Value`'s data cases. Additive (mutable `Array` and case
instances correctly stay `Opaque` to preserve ref-identity); all SpiValue matchers in the effect plugins
use catch-alls, so nothing regressed. `SpiValueDataRoundTripTest` locks the round-trip; plugin-tests
712/0, no exhaustiveness warnings.

## 2026-06-23 — core-min-value-unification: spec + Slices 1-2 (disentangle `Value.scala`)

Started the (multi-week, filed-LATER) unification of the interpreter's `Value` and the SPI's `SpiValue` into
one data type. Wrote `specs/value-unification.md` after probing the real surface — **4387 `Value.<Case>` sites
across 46 files**; `Value` is a sealed trait co-defined with `Computation`/`Env`/`FrameMap` (circular) and
heavily perf-pooled; the `Value↔SpiValue` conversion is already lossless (`Opaque` passthrough). Key
structural findings recorded: a sealed trait can't be split across modules, and the data cases can't `extend`
a core type if they must live *below* core (so a `DataValue extends Value` marker is the wrong direction) →
the end-state is a standalone low-module `DataValue` enum with `Value = DataValue | carriers` and `type
SpiValue = DataValue`, deleting the conversion; and **no early slice reduces duplication** (the payoff lands at
the final merge), so the work proceeds as safe always-green slices. **Slice 1:** extracted the `Computation`
free monad + runtime signal classes out of `Value.scala` into `Computation.scala` (byte-identical, same
module). **Slice 2:** extracted `Env`/`FrameMap*`/`MutableEnvView` into `Env.scala`. Both are pure
reorganization with zero behavior change — `Value.scala` now holds only the value ADT + its pools. Verified:
core compiles, `InterpreterTest` 158/0, effects (Std/VmContinuations/OneShot) 33/0, closure/pattern/tuple
186/0.

## 2026-06-23 — fix(interp): destructuring `val (a, b) = …` no longer marks a pre-existing `var` as a `val`

`interp-stream-runforeach-var-capture`. A correctness bug where a closure that mutates a `var` lost every
write but the last when a **destructuring `val`** appeared between the `var` declaration and the closure —
e.g. `var n = 0; val (a, b) = (7, 8); xs.foreach(_ => n += 1)` ended with `n == 1`, not the element count.
Root cause: `PatternRuntime.matchPat` for a tuple pattern returns the *full threaded env* (`cur ++ new
bindings`), and `StatRuntime.execStat` bound **all** of it — adding every returned name, including the
pre-existing `var n`, to `interp.valNames`. The closure-capture heuristic then treated `n` as a stable
`val` and *snapshot-captured* it instead of re-reading it live from `globals`, so each invocation saw the
stale initial value. Fix: execStat now binds and marks-as-val **only the pattern's own names**
(`PatternRuntime.patVarNames(pat)`), not the whole returned env. Surfaced via `StreamsPluginInterpreterTest`
"runStream result supports runForeach" (was failing on clean main; now 83/83). Locked by two new
`ClosureCaptureSoundnessTest` cases (destructuring-then-var-mutate stays live; destructured names still
captured correctly). Regression-checked: `InterpreterTest` 141/0, closure/pattern/tuple/import-state suites
54/0.

## 2026-06-23 — docs: core-min Phase 3+ actionable scope closed

Closed the stale open `core-min-phase3plus` sprint line. Its bounded work has either landed
(effect plugins, runtime resources, optics host libraries, prelude minimization, actors session seam)
or is tracked separately (`core-min-value-unification`) / deliberately deferred as low-ROI
interpreter-internal Stream/Actors code moves.

## 2026-06-23 — rust backend: do not steal List `.map(functionRef)` as Either.map

Fixed a Rust lowering regression where any top-level `foo(...)` call was treated as "maybe
Either", so `foo().map(roomLineName)` could lower to `match foo() { Either::... }` even when
`foo` was explicitly declared as `List[String]`. `isEitherExpr` now classifies named function
calls as Either-shaped only when their declared return type lowers to `Either<_, _>`. Added a
string regression and extended the cargo smoke with `roomStatusLines().map(roomLineName).toList`,
so the case is checked both in generated Rust shape and by real `cargo run`.

## 2026-06-23 — core-min: strict opt-in for advanced plugin prelude names (advanced-optin)

Removes the hardcoded `pluginObjects`/`pluginBuiltins` *plugin-owned* names from the Typer prelude and
moves each into its owning plugin's `preludeSymbols`, by tier:
- **Essential (auto-loaded, no UX change):** `Source` → streams-plugin, `setHttpServerBackend` →
  ws-plugin, `http` → http-plugin. Still resolve in production because the plugin is bundled+staged.
- **Advanced (strict opt-in):** `oauth`/`oidc` → oauth-plugin, `Wallets`/`X402Client`/`X402`/
  `CardanoFacilitator`/`PaymentConfig`/`DefaultSyncBackend`/`basicRequest` → payments-plugin,
  `spark`/`PipelineModel` → SparkBackend. These now resolve for `ssc check` **only when the plugin is
  added** (`--plugin <jar>` → `BackendRegistry.addPluginJar` → `inProcess` re-scan picks up its
  `preludeSymbols`); a plugin-less `ssc check` flags them — the deliberate strict-opt-in UX.
- **Stay hardcoded (no owning compiled plugin):** interpreter-core globals `Async`/`Await`/`Signal`/
  `Future`/`Storage` and stdlib-`.ssc` library names `HandlerRegistry`/`Cluster`/`ShuffleStage`/`Stage`/
  `runDistributed`/`runDistributedShuffle`.

`pluginObjects` is gone entirely; `pluginBuiltins` drops from 21 to 11 names. No smoke test regresses
(the advanced examples — x402-*/distributed-*/spark-* — aren't smoke-tested). `AdvancedOptInPreludeTest`
locks all three behaviours (advanced flagged-without/resolved-with-plugin; essential declared;
core/stdlib still hardcoded). typer 196/0, plugin-tests 710/0.

## 2026-06-23 — fix(test): recategorize algebraic-effects.ssc as a plugin-backed example

Pre-existing regression (since the first effect extraction, `c30f8e06d` State→plugin et al.):
`algebraic-effects.ssc` uses `runLogger`/`runState`/`runRandomSeeded`/`runClockAt`/`runEnvWith`, which
were extracted from interpreter core into bundled plugins, but it stayed in the cli `ExamplesSmokeTest`
`runnableExamples` list whose interpreter deliberately has **no** plugins — so it failed at run time
with `Undefined: runState`. Nobody had run the cli smoke test after the extractions. Moved it to
`PluginExamplesSmokeTest` (plugin classpath, lazy ServiceLoader) where it runs clean. cli smoke 2/0,
plugin smoke 1/0.

## 2026-06-23 — feat(interpreter): actor runtime providers are bound through per-interpreter sessions

`core-min-phase3plus` actor seam slice. `ActorRuntimeProvider` now opens an `ActorRuntimeSession`
for an `ActorRuntimeHost`; `ActorInterp` caches one session per `Interpreter` and clears it when a
replacement provider is installed. This makes the state ownership boundary explicit before any future
actors-plugin runtime move, while the bundled actors plugin still delegates to the core scheduler.
Verified: actors provider plugin test 3/0 and targeted actor suites 53/0.

## 2026-06-23 — core-min: runStream prelude name migrated — core Typer prelude has ZERO hardcoded effect runners

`coremin-stream-prelude-migrate`. The last effect-runner names hardcoded in the Typer prelude — `runStream`
(the runner) and the `Stream` object — moved into `StreamsInterpreterPlugin.preludeSymbols`
(`ExportedSymbol("runStream","runStream","def","Any")` + `("Stream","Stream","object","Any")`), completing
the prelude-migration axis: **no standard effect runner is hardcoded in core's `ssc check` prelude anymore.**
The now-dead `runnerType`/`bodyWithEff` typer helpers were removed (core still compiles strict `-Werror`).
This is the **prelude-name** axis only — Stream's runtime (Free-monad driver + `tryStreamEmitWhileFast`
FastTier + `installStreamGlobal`) stays in core per `coremin-stream-migrate`, since a `BlockForm` only sees
`SpiValue` (no AST). streams-plugin is bundled (installBin stages it; registered Backend provider), so
production `ssc check` resolves these via `BackendRegistry.inProcess`. `PreludeMigratedRunnersTest` now locks
16 runners including `runStream` (16/16); core compiles clean. Mirrors the actors prelude migration (names in
the plugin, runtime in core via a seam).

## 2026-06-23 — feat(rust): Tier-3 unbounded multi-shot (Free-monad MComp) — R.6 complete for recursion

Final multi-shot tier: a `multi effect` performed inside **recursion** (dynamic, unbounded perform depth)
now runs on Rust. `renderTier3Unbounded` lowers the recursive effectful def to a **Free-monad** builder
`fn __comp(...) -> MComp` (`bodyToComp`: `if`→`if`; `val x = Eff.op(a)`→`MComp::Perform{op,args,k}`;
`val x = self(a)`→`__comp(a).and_then(k)`; pure tail→`MComp::Pure(Value::from(..))`) and interprets it with
`fn __run` whose `Perform` arm runs the handler body with `resume(v)` → `__run(k(Value::from(v)))` — a
**re-invokable** `Rc<dyn Fn>` continuation (vs the one-shot `FnOnce` `Computation` already in the runtime).
New runtime: `MComp` + `and_then` in `runtime/effect.rs`; `Value::as_int`; `Ctx.resumeViaComp`. Verified:
recursive `Amb`/`flip` `program(2)` cargo-runs to `4` (sum over all 2² branches). Also **fixed a
recursive/nested effectful-call reborrow** — inside an effectful def `_eff` is a `&mut impl Effect` param, so
calls must reborrow `&mut *_eff` (not `&mut _eff` = `&mut &mut T`). `backendRust` 252/0; one-shot + Tier-1/2
untouched. **Multi-shot algebraic effects on Rust are now complete for realistic programs** — Tier-1
(List/Option), Tier-2 (static-depth nested), Tier-3 (unbounded recursion). Follow-ups (additive, no
consumer): the loop form (vs recursion), op-args / multi-op in Tier-3.

## 2026-06-23 — core-min: the actor/cluster keyword set migrated off the Typer prelude (actors-prelude)

Builds on the bundled actors provider plugin: the ~55-name actor/process/cluster keyword set
(`runActors`, `spawn`/`spawnLink`/`self`/`send`/`receive`/`timeout`/`recvFrom`, the cluster
membership/leader-election/gossip/config/drain/metric primitives, and the `sendAfter`/`sendInterval`/
`cancelTimer` timers) leaves the hardcoded Typer prelude `effectBuiltins` and is DECLARED by
`ActorsInterpreterPlugin.preludeSymbols`. The actors plugin is bundled (`installBin` stages it), so
production `ssc check` resolves the names via `BackendRegistry.inProcess`; the runtime stays in core
via the `ActorRuntimeProvider` seam (`CoreActorRuntimeProvider`), so `spawn`/`self`/… still resolve at
run time through `ActorInterp`/`ActorGlobals` — verified unaffected (`ActorDistributedTest` +
`ActorBinaryWsTest` 53/0, both `Interpreter.run`). After this the `effectBuiltins` list holds only
language forms (`handle`/`validate`/`effect`/`summon`/`Focus`/`Prism`/…), the not-yet-bundled effect
runners (`runAsync`/`runAuthWith`/`runStorage`/`runTx`/`httpClient`/async primitives), and test
helpers. `ActorsPreludeMigrationTest` locks a representative name per category. typer 196/0,
plugin-tests 693/0.

## 2026-06-22 — feat(actors): bundled actors provider plugin skeleton

Added `runtime/std/actors-plugin` as an essential bundled plugin. It registers through
ServiceLoader, contributes actor `preludeSymbols`, and installs the current provider through
`ActorRuntimeProviderBackend`; the actual scheduler still delegates to core until the next move
slice. Verification: `actorsPlugin/compile`, `backendInterpreter/compile`, provider test 2/0,
and `cli/installBin` passed; install staging is now 26 essential / 13 advanced `.sscpkg` files.

## 2026-06-22 — feat(interpreter): actor runtime provider seam

Started `coremin-actors-migrate`. Added `specs/coremin-actors-plugin.md`, then introduced
`ActorRuntimeProvider` / `ActorRuntimeHost` so `runActors` now dispatches through a provider
while still delegating to the existing core scheduler. This creates the migration seam without
moving runtime code yet. Verification: `backendInterpreter/compile` passed; targeted actor
suites passed 29/0.

## 2026-06-22 — docs: close polyglot libraries spec-writing task

Closed `polyglot-libraries-spec`. The spec now says the original design-only baseline
has implementation slices landed: block-form SPI, typed `SpiValue`, plugin
`preludeSymbols`, several effect migrations, JS runtime resources, and the no-domain
bundled plugin split. Remaining implementation work stays in separate SPRINT items.

## 2026-06-22 — docs: close coremin stream migration board scope

Closed `coremin-stream-migrate` as deliberately deferred. The prior investigation stands:
`runStream` keeps an interpreter-only FastTier path, so moving only the small `streamRun`
handler would add terminate-signal/callGlobal SPI complexity while leaving the dispatch,
FastTier, and stream globals in core. Revisit only when a clean consumer needs that SPI.
No code changed.

## 2026-06-22 — docs: close coremin prelude migration board scope

Closed `coremin-prelude-board-closeout`. `coremin-prelude-migrate` now reflects the landed sweep:
16 bundled-effect runner names moved from the hardcoded Typer prelude into plugin `preludeSymbols`,
`runnerType2` was removed, and only `runStream` remains in core under the separate stream item.
Future strict opt-in for non-bundled/advanced names is separate work; no Typer/plugin code changed.

## 2026-06-22 — docs: close Rust multi-shot R.6 actionable scope

Closed `rust-multishot-r6-closeout`. `SPRINT.md` now marks `rust-effects-multishot-r6`
as actionable-scope done: Tier-1 List, Tier-1 Option, and Tier-2 static-depth all landed
and are verified. `BACKLOG.md` now carries only the future unbounded perform-in-loop /
defunctionalized-trampoline idea, explicitly deferred until a real consumer appears. No Rust code changed.

## 2026-06-22 — core-min: the LAST bundled runners migrated off the Typer prelude (prelude-migrate final)

Finishes the bundled-effect prelude-migrate sweep. The remaining typed runner `s.define`s leave
`Typer.createPrelude` for their plugins' `preludeSymbols`: `runLogger` / `runLoggerJson` /
`runLoggerToList` (logger-effect-plugin), `runState` (state-effect-plugin), `runHttp` / `runHttpStub`
(http-plugin). With their last user gone, the typed `runnerType2` prelude helper is also removed
(`runnerType` stays — `runStream` still uses it). **16 bundled-effect runner names are now off the
core prelude** (`runRandom` + 6 variadic + 3 typed + these 6); only `runStream` remains in core,
pending the in-flight Stream extraction. Same proven mechanism: the typer does not enforce effect
discharge, so an `Any` declaration suffices for `ssc check`, and the interpreter resolves each runner
via the plugin's block-form (verified — `StdEffectsTest` runs `runHttp`/`runState`/… end-to-end, 15/0,
unaffected by the typer-prelude change). `PreludeMigratedRunnersTest` now locks all 15 migrated
runners. typer 196/0, plugin-tests 677/0.

## 2026-06-22 — docs: reconcile stale Rust multi-shot R.6 queue duplicate

Closed `rust-multishot-board-reconcile`. The old open `[ ] rust-effects-multishot-r6`
entry is now marked superseded by the detailed status entry: Tier-1 List, Tier-1 Option,
and Tier-2 static-depth are done; only unbounded perform-in-loop remains, explicitly
additive with no current consumer. No Rust code changed.

## 2026-06-22 — core-min: the 3 TYPED bundled runners migrated off the Typer prelude (prelude-migrate typed)

Completes the bundled-effect prelude-migrate family. The typed runners `runRandomSeeded` /
`runClockAt` / `runEnvWith` — which carried the effect-discharge `runnerType2("…")` signature as
standalone `s.define`s in `Typer.createPrelude` — now ship in their plugins' `preludeSymbols`
(random/clock/env-effect-plugin). Key insight: the ScalaScript typer does **not** enforce effect
discharge (no "unhandled effect" diagnostic), so the runner's `! Eff` row is tracked-but-not-checked;
declaring each name `Any` is sufficient for `ssc check`, and the interpreter resolves the runner via
the plugin's block-form (not the typer type). Production-sound because `installBin` stages all of
`allPlugins` (the effect plugins included) onto the shipped classpath, so
`BackendRegistry.inProcess.flatMap(_.preludeSymbols)` loads them. `PreludeMigratedRunnersTest`
extended to 9 runners (exercising the two-arg `runX(seed){body}` form for the typed ones). With the
earlier variadic batch this is **10 bundled-effect runner names** now off the core prelude
(`runRandom` + 6 variadic + 3 typed). typer 196/0, plugin-tests 671/0. STILL in core: `runState` /
`runHttp` / `runHttpStub` / `runLogger*` typed runners (their plugins can adopt the same pattern next)
and `runStream` (owned by the in-flight Stream extraction).

## 2026-06-22 — feat(rust): Tier-2 multi-shot generalised to nested performs (static depth) — R.6 Slice 3

Generalises the single-perform Tier-2 to **1..N nested performs** of a single-op `multi effect` with an
arbitrary handler. `renderTier2General` emits the handler once as a non-capturing nested
`fn __h(<op-arg params>, __k: &dyn Fn(<opRet>) -> <progRet>) -> <progRet>` (handler body with `resume(v)` →
`__k(v)`) and **nests** each `val xᵢ = Eff.op(argsᵢ)` perform as `__h(<argsᵢ>, &|xᵢ| <rest>)` down to the
pure tail — the continuation re-enters the handler at every perform. Example (`Amb`/`flip` nondeterminism,
2 nested flips): `__h(&|x| __h(&|y| (x?1:0)+(y?10:0)))` with `__h(k)=k(true)+k(false)` → enumerates the 4
combinations → `22`. `RustGenMultiShotTest`: golden + cargo-runs (1 flip → `1`, 2 nested flips → `22`).
One-shot tagless-final + Tier-1 untouched; `backendRust` 250/0. **Only** *unbounded* depth (a perform inside
a loop, where nesting isn't static) remains — the explicit defunctionalized trampoline, a separate slice
with no current consumer.

## 2026-06-22 — core-min: no-domain hybrid plugin distribution split

Closed `coremin-hybrid-split`. The std plugin registry now classifies plugins as essential vs advanced:
`installBin` auto-loads 25 essential `.sscpkg` files from `bin/lib/compiler/plugins` and stages 13 advanced
bundled `.sscpkg` files under `bin/lib/compiler/plugin-available` for explicit `ssc --plugin <path>` or
`ssc plugin install <path>` use. No package registry domain or hosting is required for this local opt-in path.
`deploy` stays transitional-essential because the CLI still imports deploy types directly. The Typer hardcoded
advanced compatibility names remain for now; strict opt-in typing is deferred until advanced plugins publish
complete `preludeSymbols`. Verification: `cli/compile` passed in 82s and `cli/installBin` passed, producing
the 25/13 split. The `installBin` package list now validates against `allPlugins` and caught/fixed the prior
missing `fs`/`os`/`yaml` staged archives.

## 2026-06-22 — core-min: 6 more bundled-effect runner names migrated off the Typer prelude (prelude-migrate batch)

Following the `runRandom` proof, six variadic bundled-effect runner keywords leave the hardcoded Typer
prelude `effectBuiltins` and move into their plugins' `preludeSymbols`: `runRetry` / `runRetryNoSleep`
(retry-plugin), `runCache` / `runCacheBypass` (cache-plugin), `runClock` (clock-plugin), `runEnv`
(env-plugin). Each plugin now DECLARES its runner name(s) via `Backend.preludeSymbols`; `ssc check`
resolves them through the bundled plugin rather than a core hardcode. The typed runner siblings
(`runClockAt` / `runEnvWith`, `runnerType2`-typed) STAY in core for now — moving them needs the
effect-discharge type to travel with the symbol. `PreludeMigratedRunnersTest` locks all six: a
plugin-less strict typecheck flags the name, a typecheck carrying the plugin's `preludeSymbols`
resolves it (so re-hardcoding to core, or dropping the plugin declaration, regresses). typer + full
plugin-tests 668/0.

## 2026-06-22 — feat(rust): Tier-2 single-perform multi-shot (continuation-as-closure) — R.6 Slice 3

Handles multi-shot effect handlers that resume in **arbitrary** ways (not a monad `flatMap` — e.g.
`resume(true) + resume(false)`), which Tier-1 (List/Option) can't express. For a `handle` over a
**single** no-value-arg-op perform, `renderTier2SinglePerform` reifies the continuation (the body after
the perform) as a Rust closure `__k = |x: <opRet>| -> <progRet> { <rest> }` and renders the handler body
with `resume(v)` → `__k(v)` (new `Ctx.resumeClosure`). Example (`Amb`/`flip` nondeterminism): `handle`
with `resume(true) + resume(false)` → `{ let __k = |x: bool| -> i64 { if x {1} else {0} }; __k(true) +
__k(false) }` → `1`. `RustGenMultiShotTest` golden + cargo-run. One-shot tagless-final + Tier-1 untouched;
`backendRust` 249/0. The **general** defunctionalized trampoline for *nested* performs (`Computation`/
`Cont`/`apply` in `runtime/effects.rs`) remains a separate larger slice (clean `unsupported`, no consumer).

## 2026-06-22 — docs: collapse duplicate actors migration queue item

Closed `coremin-actors-board-reconcile`. `SPRINT.md` now has exactly one open `coremin-actors-migrate`
entry: the entangled scheduler/message-loop design item. The older shorter duplicate is marked superseded.
No implementation changed.

## 2026-06-22 — docs: reconcile stale sprint open items

Closed `sprint-stale-open-items-reconcile`. `coremin-prelude-migrate-ORIG` is now explicitly superseded by
the newer blocked/partial `coremin-prelude-migrate` finding, and `polyglot-phase2-optics-allhosts` is marked
done because JS/JVM/Rust/Java optics library hosts all ship. No implementation changed.

## 2026-06-22 — feat(rust): consume native `Option` — `Some`/`None` patterns + `getOrElse` (rust-option-consumption)

The Rust backend produced `Option<T>` values but couldn't consume them in user code. Added: `Some(x)` /
`None` **match patterns** in `renderPattern` (lowering to Rust `Some(x)` / `None`), and `opt.getOrElse(d)`
(one-arg ⇒ Option, so `Map.getOrElse` is excluded) → `opt.unwrap_or(d)`. Also made the Tier-1 Option
multi-shot lowering bind each scrutinee to a **typed `let`** (the op's `Option<…>` param type) so a bare
`None` argument (e.g. `Maybe.get(None)`) has a type to infer. This makes multi-shot **Option** effects
(§11 Slice 2) **end-to-end** on rust — `RustGenMultiShotTest` cargo-runs a Some-chain → `12` and a
short-circuiting `None` → `-1`. `backendRust` 247/0.

## 2026-06-22 — docs: reconcile optics packaging queue

Closed `polyglot-optics-board-hygiene`. The top `SPRINT.md` claimable queue no longer lists stale open
duplicates for `emit-lib-cli` or `polyglot-optics-jvm`; both now point at the later completed
`optics-emit-lib-cli` and `optics-jvm-facade` entries. No implementation changed.

## 2026-06-22 — core-min: first Typer-prelude name migrated to a plugin (`runRandom`, prelude-migrate proof)

The `core-min-prelude-spi` keystone now removes its first real name from the ~150-name hardcoded Typer
prelude: `random-effect-plugin` DECLARES `runRandom` via `Backend.preludeSymbols`, and it's removed from
`Typer.createPrelude`'s `effectBuiltins`. `ssc check` resolves `runRandom` through the bundled plugin's typed
prelude instead of a core hardcode — proving the keystone enables real prelude-shrink for a bundled plugin
with zero breakage (`RandomPluginTest`: plugin-less typecheck flags `runRandom`; a typecheck with the plugin's
`preludeSymbols` resolves it). typer + full plugin-tests 662/0. The safe pattern for variadic bundled-effect
runner names; the rest of the ~150 names migrate incrementally (typed runners need the effect-type to move;
non-bundled names need the hybrid bundling decision). (`754139832`)

## 2026-06-22 — polyglot: Java optics library (`emit-lib --host java`) — Task B optics COMPLETE (4/4 hosts)

`JavaLibPackager` (counterpart of Js/Jvm/RustLibPackager) emits a standalone, dependency-free `ssc-optics`
Java/Maven project (pom.xml + `src/main/java/ssc/optics/Optics.java` + README) for `ssc emit-lib --host java
--feature optics`. `Optics.java` is a faithful Java 17 port of the dynamic optics over `Object`
(`Map<String,Object>` / `List<Object>` / `Optional<Object>` + `"_type"`-tagged sums): Lens / Optional /
Traversal / Prism + `field`/`index`/`at`/`some`/`each`. `JavaLibPackagerTest` 5/5: golden + emit-lib layout +
a javac-gated compile/run smoke exercising all four optics. **With JS + JVM + Rust + Java, all four optic
library hosts now ship — the optics half of Task B (cross-language reuse) is complete.** (`09e174612`)

## 2026-06-22 — feat(rust): multi-shot Tier-1 Option monad lowering (R.6 Slice 2)

Extends Tier-1 multi-shot (`specs/rust-effects.md §11`) from List to **Option**. `inlineMultiShotBody`
now discriminates the monad by the effect op's argument Rust type: `Vec<…>` → nested `for` loops + a
`Vec` (List/NonDet, Slice 1); `Option<…>` → nested `if let Some(x) = <arg> { … } else { None }` with the
pure tail wrapped `Some(tail)` (Option/Maybe — short-circuiting). `RustGenMultiShotTest` golden verifies
the if-let lowering. **NOTE:** end-to-end (cargo-run) Option isn't possible yet — the rust backend can't
*consume* a native `Option` (no `Some`/`None` match patterns, no `getOrElse`), an orthogonal gap filed as
BACKLOG `rust-option-consumption`; the produced `Option<i64>` is well-formed and will run once that lands.
`backendRust` 241/0; List/NonDet end-to-end + one-shot untouched.

## 2026-06-22 — polyglot: Rust optics library (`emit-lib --host rust`) — Task B 3rd host

`RustLibPackager` (counterpart of `JsLibPackager`/`JvmLibPackager`) emits a standalone, dependency-free
`ssc-optics` Rust crate (Cargo.toml + src/lib.rs + README) for `ssc emit-lib --host rust --feature optics`.
`lib.rs` is a faithful dynamic port of the JS `@scalascript/optics` / JVM `ssc-optics`: Lens / Optional /
Traversal / Prism over a dynamic `Value` enum (`Obj`/`Arr`/`Opt`/`Str`/`Int`/`Bool`/`Null` + `"_type"`-tagged
sums), with path steps (`field`/`index`/`at`/`some`/`each`) and `get`/`set`/`modify`/`and_then`/`get_all`/
`get_option`/`reverse_get`. `RustLibPackagerTest` 4/4: golden (file set + API surface + dep-free `[lib]`) + a
Rust-toolchain-gated cargo smoke that writes the crate + an integration test exercising all four optics and
`cargo test`s it — the emitted Rust compiles AND behaves. user-guide + README updated. JS + JVM + Rust optics
hosts now ship; only the Java facade remains. (`f13427d4b`)

## 2026-06-22 — fix(rust): 64-bit wrapping arithmetic (`overflow-checks = false`) — `effect-multishot` now runs on rust

ScalaScript `Int`/`Long` are 64-bit **wrapping** (Java `Long` semantics; the interpreter, JVM and JS all
wrap on overflow), but Rust `i64` `*`/`+`/`-` **panic on overflow in `cargo` debug builds**. The emitted
`Cargo.toml` now sets `overflow-checks = false` in both `[profile.dev]` and `[profile.release]`, so emitted
programs wrap like the other backends instead of debug-panicking. This was the last (orthogonal) blocker
for the `effect-multishot` bench on rust after multi-shot lowering (§11 Slice 1) — its LCG
`s * 2862933555777941757` overflows `i64`. **All three backends (jvm/js/rust) now run `effect-multishot`.**
`RustGenMultiShotTest` cargo-runs the real bench workload; `RustGenCargoTomlTest` / `RustGenMainAssemblyTest`
goldens updated; `backendRust` 240/0. (Chose `overflow-checks = false` over per-op `wrapping_*` codegen:
semantically correct for ScalaScript, minimal, zero codegen risk.)

## 2026-06-22 — core-min: Http effect runner extracted to the http-plugin (8th effect off core)

`runHttp { … }` (real outbound I/O) and `runHttpStub(routes) { … }` (stub) leave the interpreter core for
the already-bundled `http-plugin`'s `blockForms`, via the proven block-form template + two new SPI
capabilities: **`BlockContext.makeRecord`** (a handler replies with a typed `Response { status, headers,
body }` record the body field-accesses) and **`BlockContext.featureLocal`** (the handler reads the
base-url/timeout/retry config the core `httpClient(baseUrl)` form sets). `HttpEffectRunner` ports the
`java.net.http` request logic. Removed from core: the EvalRuntime `runHttp`/`runHttpStub` special-forms +
2 `reservedApplyHeads` names + `EffectHandlers.httpRun`/`doHttpRequest`. `httpClient(baseUrl)` (a
feature-local config setter, not an effect handler) stays a core form by design. Tests moved
`StdEffectsTest` → `HttpEffectPluginTest` (4/4, lazy ServiceLoader, no `installPlugins`); `StdEffectsTest`
15/15, no regression. (`f8f9ac4d3`)

## 2026-06-22 — feat(rust): multi-shot algebraic effects, Tier-1 List monad (R.6 Slice 1)

First multi-shot effect support on the Rust backend (`specs/rust-effects.md §11`). A `multi effect`
handled by `opts.flatMap(opt => resume(opt))` over a straight-line effectful def now lowers to **nested
`for` loops + a `Vec` accumulator** — no reified continuations, no `Box<dyn Fn>`, no dynamic `Value`,
fully typed and stack-safe (the design's Tier-1 fast path). Gated on the parser's `__multiShot__` marker;
the one-shot tagless-final path (and its `resume(v)→v` substitution, which is unsound for multi-shot) is
untouched. `RustCodeWalk`: `multiShotHandle` / `renderMultiShotList` / `inlineMultiShotBody` +
`collectMultiShotEffects` + a `_defBodies` map for inlining the handled def. `RustGenMultiShotTest`:
codegen golden + two `cargo`-runs (cross-product `102`; multi-shot in a `while`-loop + foldLeft `324`).
`backendRust` 239/0; one-shot byte-identical. The `effect-multishot` *bench* still reports `n/a` on rust
for an **orthogonal** reason — its LCG overflows `i64` and Rust debug-panics (JVM/JS `Long` wraps); filed
as BACKLOG `rust-long-wrapping-arithmetic`. Next: Tier-1 Option, then Tier-2 defunctionalized trampoline.

## 2026-06-22 — core-min keystone: typed plugin prelude (`Backend.preludeSymbols`)

The enabler for "extract everything into plugins" (charter: B→A). A plugin declares its public prelude
symbols WITH type-signatures (`Backend.preludeSymbols: List[ExportedSymbol]`, `tpe` = `SType.show` string);
`ssc check` resolves AND type-checks calls to them, with no hardcoded core list. Reuse, don't invent:
`ExportedSymbol` already encodes typed symbols and `InterfaceScope.parseSType`/`parseKind` invert
`SType.show` — the Typer prelude defines each plugin symbol with its declared type instead of the untyped
`variadic`. `ssc check` collects `BackendRegistry.inProcess.flatMap(_.preludeSymbols)` and threads it into the
Typer; `pluginBuiltins` (names-only) stays as fallback; additive/no-op when empty (no regression). Proof
`TyperPreludeSymbolsTest` (resolves only with the hook; declared type flows — return-mismatch flagged,
correct call passes); typer+artifact 499/0. Spec `specs/core-min-prelude-spi.md`. Next: `coremin-prelude-migrate`
moves real plugins' symbols off the ~150-name core lists. (`0ef0bde11`)

## 2026-06-22 — feat(jvm): optics JVM library (`emit-lib --host jvm`) — Phase 2 second host

Second per-host optics library (after JS), polyglot-libraries Phase 2. `ssc emit-lib --host jvm
--feature optics` emits a buildable `ssc-optics` sbt project: `build.sbt` + `src/main/scala/ssc/optics/
Optics.scala` + `README.md`. Because JVM optics has no standalone runtime (it runs through the
interpreter's `OpticsRuntime`), the library is a **native, self-contained Scala optics implementation**
— Lens/Optional/Traversal/Prism over dynamic JSON-like values (`Map[String, Any]` / `List` / `Option` /
`"_type"`-tagged variants), faithful to the JS shapes, with no ScalaScript dependency. `JvmLibPackager`
+ `EmitLibCmd --host jvm` (nested-path writing). `JvmLibPackagerTest` 5/5 incl. a **real `scala-cli`
compile** of the emitted source + a signature golden. Idiomatic typed/macro (Monocle-style) optics +
the Rust crate / Java facade hosts are documented follow-ups.

## 2026-06-22 — refactor(rust): Rust runtime templates → `.rs` resources (polyglot §3 #8 closed)

Mirror of the JS/JVM cleanup for the Rust backend, completing polyglot-libraries §3 #8 across all three
string-heavy backends. 15 `val XxxRs` runtime templates in `RustRuntimeTemplates` (`ValueRs`,
`RuntimeModRs`, `Sha256Rs`, `Base64Rs`, `JsonRs`, `AuthRs`, `EffectRs`, `UiRs`, `HttpRs`, `UiServeRs`,
`WsRs`, `McpRs`, `StateEffectRs`, `RandomHandlerRs`, `StreamEffectRs`) move into
`resources/scalascript/rust-runtime/` via the new cached `RustRuntimeResource.load`. The resource holds
the verbatim `|`-margined body and the loader applies `.stripMargin` ⇒ **byte-identical** (each `diff`-clean
vs `git HEAD`). The interpolated `renderTaglessEffectsRs` def (`s"""`) stays inline (computed at runtime).
`backendRust` 236/0. Spec `specs/js-runtime-resources.md`.

## 2026-06-22 — refactor(jvm): JVM runtime-source templates → `.scala` resources (polyglot §3 #8, JVM)

Mirror of the JS `.mjs`-resource cleanup for the JVM backend. The big emitted-Scala runtime templates in
`JvmGenRuntimeSources` (3656 → 61 lines) move into resource files under
`resources/scalascript/jvm-runtime/`, loaded by a new cached `JvmRuntimeResource.load`. The 5 `memo()`
runtimes become 11 resource chunks (`stubServeRuntime`, `fsRuntime`, `generatorRuntime`, `reactiveRuntime`,
and `effectsRuntime1`–`7` — the effects runtime was a 7-way size-split for the JVM 64 KB string-constant
cap). The resource holds the verbatim `|`-margined body and the loader applies `.stripMargin`, and the
7 effects chunks stay `+`-concatenated in code — so the result is **byte-identical** to the former
`"""…""".stripMargin` literals by construction (verified: each resource `diff`-clean vs `git HEAD`).
`backendJvm` compiles; `JvmGenEffectsRuntimeTest` green. Spec `specs/js-runtime-resources.md`.

## 2026-06-22 — feat(cli): `ssc emit-lib` — emit a feature as a standalone host library

Makes the optics npm packager (`JsLibPackager`, previously test-only) user-reachable. `ssc emit-lib
--host js --feature optics -o <dir>` writes the self-contained `@scalascript/optics` package
(`package.json` + `index.mjs` + `optics.d.ts`) — Lens/Optional/Traversal/Prism + curated TypeScript
types, no `.ssc`/runtime dependency at the consumer's edge. New `EmitLibCmd` registered via the
ServiceLoader `CliCommand` SPI; flags `--host`/`--feature`/`--version`/`-o`. Supported today: `js`/`optics`;
more host/feature combos (JVM jar / Rust crate / Java facade) follow the same shape. `EmitLibCmdTest` 2/2;
README CLI row + user-guide section (`docs/polyglot-libraries` §4).

## 2026-06-22 — meta-v2 Track C2 (conservative): catch undefined refs introduced by macro/inline expansion

`ssc check` now re-type-checks the macro/inline-EXPANDED module and warns when an expansion references an
undefined name — the source type-checks but the expanded code does not (today this only surfaces later at
codegen/run, pointing into synthetic expanded text). `MacroCodegen.expansionTypeWarnings`, wired into
`checkOneFile`. **Zero false positives** by construction: a pre/post `Reference to undefined name` diff
cancels macro machinery (`__ssc_macro__`/`Expr`/…, undefined pre-expansion but stripped post-expansion) and
leaves the user's own undefined names to the normal check; also excludes builtins / stripped entrypoint+impl
names / `_`-helpers; warning-only; never breaks `ssc check`; free no-op for macro-free modules. File-level
(no position map — the deferred hard part). Reach bounded by the strict Typer's position-sensitive
undefined-name check (val-rhs / bare-statement). This is the safe slice of Track C2 — the full version
(precise positions + full-inference recheck) stays deferred for false-positive risk. `MacroCodegenTest` +5;
core artifact+typer 496/0; verified end-to-end. Spec `specs/arch-metaprogramming-v2.md` C2.

## 2026-06-22 — refactor(js): rename cryptic `part1X`/`v14effects` runtime fragments to meaningful names

The JS runtime fragments had size-driven historical names (`part1a`–`d`, `part2a`/`b`, `v14effects`).
Renamed to reflect content/capability (content byte-identical ⇒ emitted JS unchanged): `part1a`→`core`
(base prelude), `part1b`→`http-server` (`HtmlDsl`), `part1c`→`jwt-auth` (`Jwt`), `part1d`→`ws-server`
(`WsServer`), `part2a`→`core-dispatch`, `part2b`→`core-collections`, `v14effects`→`effects` (the v1.4
built-in effects: Logger/Random/Clock/Env). Each `.mjs` resource + its `JsRuntime*` val + `.scala` file
renamed; all references (JsGen assembly/concat/capability comments, `WebServer`, tests) updated and the
now-stale doc comments rewritten. `backendJs` + `interpreterServer` compile; 48 JS tests green
(streams/optics/effects/loader). Spec `specs/js-runtime-resources.md` updated.

## 2026-06-22 — refactor(js): consolidate the async-a/b size-split into one `async.mjs`

Follow-up to the `.mjs` migration: with the JS runtime now in resource files, the JVM 65 535-byte
string-constant cap that forced `JsRuntimeAsyncA`/`AsyncB` to be split no longer applies, so they
merge into a single `async.mjs` (`JsRuntimeAsync = JsRuntimeResource.load("async.mjs")`; the two
`AsyncA/B` vals + files deleted). Byte-identical (`async.mjs` == `asynca` + `asyncb`, `cmp`-clean).
Only the genuinely **size-driven** split was consolidated — all logical boundaries stay: `part1b/1c/1d`
are each capability-gated (`HtmlDsl`/`Jwt`/`WsServer`) and `part2a/2b` are separated by gated `optics`,
so those remain per-fragment to preserve tree-shaking. Obsolete comments naming the removed vals fixed.
`backendJs` compiles; streams/effects/async/optics tests green.

## 2026-06-22 — feat(js): all JS runtime fragments now `.mjs` resources (§3 #8 closed for JS)

Completes the cleanup the optics pilot started: the remaining **17** `JsRuntime*` string constants
(`Part1a`–`d`, `Part2a`/`2b`, `AsyncA`/`B`, `Signals`, `Dataset`, `IndexedDb`, `BrowserPatch`,
`Graphql`, `Mcp`, `McpBrowser`, `Payment`, `V14Effects`) moved out of Scala triple-quoted literals
into real `.mjs` resource files under `resources/scalascript/js-runtime/`, loaded via
`JsRuntimeResource.load`. Each `.mjs` body was mechanically extracted and **`diff`-verified
byte-identical** to the prior literal vs `git HEAD` — so the emitted JS is unchanged. The two computed
aggregators in `JsGen.scala` (`JsRuntime`, `JsRuntimeAsync = AsyncA + AsyncB`) stay as-is. All 18 JS
runtime fragments now live as lintable / editor-friendly `.mjs`, closing polyglot-libraries §3 #8 for
the JS backend. `backendJs` compiles; 65 JS codegen tests green (tree-shaking, transitive imports,
content-toolkit, optics node-smoke). Spec `specs/js-runtime-resources.md`.

## 2026-06-22 — feat(js): JS runtime fragments as `.mjs` resources — optics pilot

The JS backend stored its runtime helper code as large Scala string constants (`val X: String =
""" …real JS… """`) — no syntax highlighting, no `eslint`/`node --check`/`tsc`, late error detection.
First slice of the cleanup (polyglot-libraries §3 #8): move the **optics** runtime into a real
`resources/scalascript/js-runtime/optics.mjs` file, loaded by a tiny cached classpath loader
`JsRuntimeResource.load(name)`. `JsRuntimeOptics` keeps its `val X: String` API, so every call site
and the **emitted JS are unchanged — verified byte-identical** (7555 bytes, `diff`-empty vs the prior
string; `JsLibPackager` golden + node-ESM smoke pass unchanged). The `.mjs` is now lintable /
`node --check`-able / editor-friendly. `JsRuntimeResourceTest` (5/5) pins the loader contract + the
leading/trailing newline `JsGen` relies on + an `assume(node)` `node --check` on the raw resource.
The remaining dozen+ `JsRuntime*` fragments migrate the same way. Spec `specs/js-runtime-resources.md`.

## 2026-06-22 — feat(polyglot-lib): JS optics npm-package packager (Task B, Phase 2 — JS host)

First per-host library-packaging slice (`specs/polyglot-libraries.md` §4 / §6 Phase 2): the pure
**optics** feature (Lens/Optional/Traversal/Prism) now packages as a standalone `@scalascript/optics`
npm ESM package — no `.ssc` source or ScalaScript build dependency at the consumer's edge.

`JsLibPackager` (in `backendJs`) produces `package.json` + `index.mjs` + `optics.d.ts`: it bundles the
verbatim `JsRuntimeOptics` `_make*` factories plus only the Option/Map helpers they reference
(`_None`/`_Some`/`_isMap`, with `_isMap` narrowed to native JS `Map` at the library edge — the internal
HAMT is not part of the boundary), adds step builders (`field`/`index`/`at`/`some`/`each`), and
re-exports stable public names (`makeLens`/`makeOptional`/`makeTraversal`/`makePrism`/`Some`/`None`). The
curated `optics.d.ts` is the **frozen public API signature**.

`JsLibPackagerTest` (5/5): file-set + `package.json` surface + `index.mjs` bundling/exports + an
`optics.d.ts` **verbatim golden** + an `assume(node)`-gated ESM smoke that writes the generated package to
a temp dir, `import`s it in real Node, and exercises all four optics end-to-end (get/set/modify, getOption,
getAll/modify, prism match). This proves the value-mapping + stable-API + golden pipeline on the easy case.
Remaining slices (queued in SPRINT): a `emit-lib` CLI command + example, then JVM facade / Rust crate /
Java facade — each with its own per-host API-signature golden.

## 2026-06-22 — feat(core-min): extract Retry + Cache effects to plugins (core-min-retry-cache-migrate)

Two more effects moved out of the interpreter core into ServiceLoader plugins, copying the
Logger/Random/Clock/Env/State template (core-minimization, `specs/polyglot-libraries.md §2d`).
**Seven effects are now plugins: Logger, Random, Clock, Env, State, Retry, Cache.**

- `runtime/std/retry-effect-plugin` — `RetryBlockForm(sleep)` registered under `runRetry` (real sleep
  between attempts) and `runRetryNoSleep` (test handler). `Retry.attempt(n, delayMs)(thunk)` re-invokes
  the thunk via `BlockContext.applyFn` (like `State.modify`), retrying up to `n + 1` times and rethrowing
  the last error if all fail.
- `runtime/std/cache-effect-plugin` — `CacheBlockForm(bypass)` under `runCache` / `runCacheBypass`. The
  memoized thunk runs via `applyFn`; the process-local TTL store moved into the plugin (`object CacheStore`,
  was `interp._cacheStore`). Per-block `bypass` replaces the former `_cacheBypass` ThreadLocal — each block's
  handler carries it, and the effect trampoline's dynamic scope matches the old ThreadLocal dynamic scope.

Removed from core: 4 `EvalRuntime` block-form cases + their 4 `reservedApplyHeads` names;
`EffectHandlers.retryRun`/`cacheRun`; `Interpreter._cacheStore`/`_cacheBypass`. The lightweight `Retry`/`Cache`
emitter globals stay in `StdEffectsRuntime` (the State precedent — only the heavy handlers move). Wired into
the `allPlugins` registry (auto aggregate + plugin-tests classpath) + the explicit `installBin` `pluginPkgs`
list. Retry/Cache tests moved `StdEffectsTest` → `RetryPluginTest` (3) / `CachePluginTest` (2) in
`interpreter-plugin-tests`, run with NO `installPlugins` — proving production lazy-ServiceLoader dispatch.
The JS/JVM codegen keep their own `_cacheStore` runtime strings (unaffected — this is an interpreter-only change).

## 2026-06-22 — workflow: pre-commit guardrail keeps feature commits out of shared main (worktree-guardrail)

Structural fix for the root cause of the parked-feature-branch mess (a prior session committed
rust-web-toolkit directly in the shared `main` checkout instead of a worktree, abetted by the
`EnterWorktree` false-positive — claude-code #27881). **`.githooks/pre-commit`** refuses a non-`.work/`
commit made in the shared main checkout (`git-dir==git-common-dir`) or on branch `main`; feature
worktrees are never affected; `--no-verify` is the escape hatch. **`scripts/new-worktree <name>`**
packages the safe recipe (external, prune-safe path — not under `.worktrees/`); **`scripts/setup-hooks`**
wires `core.hooksPath`; **`scripts/test-worktree-guardrail`** covers all 5 behaviour-matrix rows. Activated
on the shared repo + verified live. Spec `specs/worktree-guardrail.md`; AGENTS.md §1 updated. (`bffef3447`)

## 2026-06-22 — test(rust): end-to-end cargo-run smoke suite (rust-cargo-smoke-coverage)

`RustGenCargoSmokeTest` — the first `backendRust` test that actually **compiles + runs** the Rust it
emits (the rest of the suite is string-match only, which is why the E0507 index-read bug shipped).
Gated on a Rust toolchain (`assume(cargoAvailable)`, probes `cargo --version`); emits a feature-
exercising program to a temp crate, `cargo run`s it, asserts stdout. Covers collection ops (take/drop/
takeRight/dropRight/sorted/distinct/sum), string ops (replace/startsWith/endsWith/contains), and the
`Vec<String>` index-read regression. Kept out of the fast string-match path; toolchain-less CI skips
cleanly. `backendRust` 236/0. (`2c8032a5c`)

## 2026-06-22 — fix(rust): index read on a non-Copy seq clones (E0507)

Follow-on bug fix to the rwt-followons below, caught by an end-to-end `cargo run` smoke (the
`backendRust` suite is string-match only). `seq(i)` on a `Vec<String>` (from the new indexable
`.split`/`.toList`) emitted a bare `seq[(i) as usize]`, which moves out of Rust's `Index`
(`error[E0507]`). Index *reads* now `.clone()` (elided by rustc for `Copy` elements); the
`seq(i) = v` *store* path stays bare via explicit `Term.Assign` handling. `backendRust` 235/0;
BUGS.md `rust-index-read-moves-noncopy`. (`2aff7c982`)

## 2026-06-22 — feat(rust): collection / string / http codegen follow-ons (rwt-followons)

Six additive Rust-backend codegen lowerings, landed on top of the now-complete rust-web toolkit
(`RustCodeWalk` + `RustRuntimeTemplates`; `backendRust` 233/0). General-purpose, not toolkit-specific —
they fell out of driving the `rozum-meeting.ssc` SSR page end-to-end:

- **`Vec` `.take` / `.drop` / `.takeRight` / `.dropRight` / `.sorted`** lowering.
- **`Vec` `.distinct`** — order-preserving, `HashSet`-filtered (first occurrence wins).
- **`(s: String).replace(from, to)`** → `str::replace` with `&str` patterns.
- **string-method `&str` patterns + borrow intrinsics + `char` / `sum`**, and indexable `split` / `toList`.
- **http**: a route handler now receives the POST body; `text/html` content-type; `no-store`; MIME map;
  exec opts.
- **http prefix routing** — a route path ending in `/` matches any subpath.

These are pure backend additions (no other backend touched), each guarded by `RustGen*Test` cases.

## 2026-06-22 — feat(rust): one-shot algebraic effects (R.4.2) — `effect-oneshot` n/a → 0.0020 ms

Custom algebraic effects with an explicit `handle` / `resume` now compile and run on the Rust backend (the
`effect-oneshot` bench was `n/a`; it's now the *fastest* backend on it). Implemented via **tagless-final
traits** (per `specs/rust-effects.md §10`), not a Free-monad CPS port — so the key win is that the
`while`-loop-with-`perform` case needs **no trampoline**: the loop runs directly and `Bump.tick()` is just an
`_eff.tick()` method call.

Three codegen gaps closed in `RustCodeWalk` / `RustGen` / `RustRuntimeTemplates`:
1. A user `effect Bump: def tick(): Int` (preprocessed to `object Bump { def tick(): Int = __effectOp__ }`)
   emits `pub trait BumpEffect { fn tick(&mut self) -> i64; }` with REQUIRED methods (no no-op default / NoOp
   struct — the handler supplies the impl). The op signatures are plumbed through `WalkResult.customEffectOps`.
2. An effect-op call `Bump.tick(args)` lowers to `_eff.tick(args)` (the `_eff: &mut impl BumpEffect` param +
   call-site threading already existed).
3. `handle(body) { case Bump.tick(resume) => resume(5) }` lowers to an inline handler
   `struct __H_Bump; impl BumpEffect for __H_Bump { fn tick(&mut self) -> i64 { 5 } }` then
   `{ let mut _eff = __H_Bump; <body run against _eff> }`. A tail-position `resume(v)` lowers to just `v`.

Verified: a minimal probe cargo-builds → `10`; the real `effect-oneshot.ssc` workload → `962` (matching
interp/jvm); `backendRust` 230/0 with 3 new `RustGenR44Test` cases. **Multi-shot is out of scope** (R.6):
`effect-multishot`'s `opts.flatMap(opt => resume(opt))` re-invokes the continuation, which a single
trait-method return can't model — it stays `n/a` and fails cargo cleanly.

## 2026-06-21 — docs: reconcile meta-v2 board state

Closed `board-meta-v2-reconcile`. `SPRINT.md` and `BACKLOG.md` no longer present meta-v2 Track C/C2 as
available build work: Track A/B/C are marked actionable-scope done, C2's practical warning guard is recorded
as shipped, and the broader arbitrary post-expansion re-typecheck/source-position ambition is deferred by
design. This keeps future agents from picking stale meta-v2 guidance.

## 2026-06-21 — refactor(bench): migrate `Bench.opaque` to stable plugin API

Closed `stable-plugin-spi-p3` as a small Phase 3 slice. `bench-plugin` no longer imports
`scalascript.interpreter.Value` from main sources; `Bench.opaque` now uses `PluginNative.eval` and
returns the selected `PluginValue` unchanged, with the empty-arg case mapped to `Unit`.

Regression guard: `BenchIntrinsicsTest` checks identity behavior and source-scans `bench-plugin/src/main`
for direct interpreter imports. Verified with `sbt -no-colors "benchPlugin/test; pluginApi/test; benchPlugin/checkPluginBoundary"`
from the `feature/stable-plugin-spi-p3` worktree: bench plugin 2/2, plugin API 14/14, boundary task green.

## 2026-06-21 — fix(jvm): CPS def result types — `effect-multishot` n/a → 0.075 ms

Closed `jvm-multishot-result-type`. JVM CPS codegen no longer widens total handled-effect wrappers to
`Any`: a user declaration such as `def workload(seed: Long): Long` now emits a `Long` result and casts the
final CPS value at the def boundary. Effect-row defs (`A ! Eff`) still emit `Any`, preserving the Free-monad
contract that handlers unwrap.

This fixes the corpus JVM `n/a` for both effect workloads: `effect-multishot` now runs at 0.075 ms/iter and
`effect-oneshot` at 0.160 ms/iter. Regression guard: `JvmGenEffectsRuntimeTest` compiles and runs
`addLong(workload(0L))`, proving the CPS wrapper keeps static type `Long`; full targeted suite 34/34.

## 2026-06-21 — fix(rust): chained Either map/flatMap/fold compiles (E0282) — `either-chain` n/a → 0.0040 ms

The `either-chain` bench workload was `n/a` on the rust backend: `cargo build` failed with
`error[E0282]: type annotations needed`. A chained `parse(n).map(..).flatMap(..).fold(..)` lowered to a
nested `match match match …` where each Either arm was an immediately-applied closure `(move |x| { body })(v)`,
and rustc cannot infer the closure parameter's type through that chain.

Fix: a new `inlineArm` helper lowers a 1-parameter Either map/flatMap/fold arm to a `{ let x = v; body }`
block instead of `(move |x| body)(v)`. The `let` binding flows `x`'s type straight from the matched value
`v`, so no annotation is needed; the form is semantically identical (the closure was applied immediately
anyway). A non-lambda arg (a function reference) keeps `(f)(v)`.

Verified: `cargo build` green; the binary runs and matches the interpreter (`R=632`); `./bench.sh
either-chain --backend rust` goes from `n/a` to **0.0040 ms**; `backendRust` 229/0 with a new `RustGenR23Test`
regression test asserting the `{ let x = v; … }` lowering.

## 2026-06-21 — fix(jit): ASM one-shot effect lowering — `effect-oneshot` 9.46 → 0.032 ms

Closed `asm-jit-effect-pathology`. `AsmJitBackend.walkLong` now mirrors Javac's one-shot tail-resume effect
bridge: active handler resolvers lower to `JitGlobals.resolveEffectLong*`, and resolved effect calls stay on
the Long path through `.toLong` / `.toInt`. This restores `ssc-asm` parity with default `ssc` on the hot
effect loop (`ssc` 0.025 ms/iter, `ssc-asm` 0.032 ms/iter) instead of falling back to the slow trampoline.

Regression guard: `AsmEffectJitTest` compiles and runs `Bump.tick().toLong` through ASM with an active
resolver; `EffectOneShotFastPathTest` and `JitLintTest` remain green.

## 2026-06-21 — perf(js): direct tuple indexing + single-alloc tuple concat — `tuple-monoid` 7.40 → 2.60 ms

The `js` `tuple-monoid` benchmark was the slowest cell in the whole `./bench.sh` table. Two general JsGen
codegen fixes (root-caused by reading the emitted hot loop):

1. **`t._N` → `t[N-1]`.** Tuple element access went through the megamorphic `_dispatch(t, '_N', [])` (a
   function call + type switch per read). New `tupleVars` tracking + an `isTupleExpr` predicate let JsGen
   emit a direct array index when the receiver is statically a tuple (literal, tuple `++` concat, or a val
   bound to one). A case class is an object, never matches `isTupleExpr`, so its Product `._N` is untouched.
2. **Single-allocation tuple concat.** A tuple-LITERAL concat `(a, b) ++ (c, d)` flattened to
   `_tupleConcat(Object.assign([a,b],{_isTuple:true}), Object.assign([c,d],{_isTuple:true}))` — 3 array
   allocations per evaluation. It now emits one `Object.assign([a, b, c, d], {_isTuple: true})` (identical
   value). A variable operand keeps the runtime `_tupleConcat` (shape not statically known).

Result: **7.40 → 2.60 ms (2.85×)** on the bench, and both fixes help any tuple-heavy code, not just the
benchmark. Verified: 281 JS unit tests green; interp == js on tuple flatten / `._N` / `show` / equality.
(The `s`-LCG interp/js value delta in this workload is the separate 64-bit-Long-on-float64 precision
limitation, not a tuple bug.)

## 2026-06-21 — fix(rust): reuse top-level collection vals in hot loops

Closed `rust-foreach-list-realloc`. Rust codegen now references top-level collection vals through the
per-def `let` binding instead of re-inlining the constructor at every use site, and it injects that preamble
only into defs that actually reference the val. This removes the old `for s in vec![...].iter().cloned()`
hot-loop shape and the dead `let shapes = vec![...]` preamble from helper defs like `area`.

Also fixed a clone-insertion false positive: lambda/def parameter binders are no longer counted as reads in
`collectMultiUse`, so a single-use foreach param no longer becomes `area(s.clone())`. Verified with generated
Rust inspection, `backendRust/test` (229 tests), and `./bench.sh pattern-match-heavy list-fold --backend rust`:
`list-fold` improved 0.153→0.044 ms and `pattern-match-heavy` 4.16→1.37 ms.

## 2026-06-21 — fix(js): real Char type — `String.map(nonChar)` returns a Seq (interp-js-string-map-nonchar)

Closed the last open cross-backend character bug. The JS backend had no distinct `Char` type — chars were
JS strings/numbers — so `"abc".map(_.toInt)` rebuilt a String instead of `Seq(97,98,99)`, and `c.toInt`
inside a map was `parseInt` (NaN), not the code point. The interpreter already modelled this with `CharV`.

Added a JS `_Char` box (`JsRuntimePart2a`): `valueOf` returns the code point and `toString` the 1-char
string, so concatenation, arithmetic, and `_show` coerce naturally. A char produced by iterating a String
(`map`/`filter`/`foreach`/`flatMap`/`charAt`/`head`/`last`/`toList`/`forall`/`exists`/`count`) is now boxed,
and `String.map` returns a String only when *every* mapped element is a `_Char` — otherwise a `Seq` —
mirroring the interpreter's `strMapResult`. `_dispatch` gained a `_Char` branch mirroring `dispatchChar`
(`toInt`/`toLong`→code point, `isDigit`/`isLetter`/`isUpper`/`toUpper`/`toLower`/`asDigit`/…), and `_eq`
bridges `_Char` to a 1-char String literal and to an Int (the interp allows `CharV == IntV`), so `c == 'a'`
and char predicates keep working even though char *literals* stay JS strings.

Verified: `CrossBackendPropertyTest` "String.map char vs non-char" now asserts **interp == JS == JVM**
(`"abc".map(_.toInt).sum` = 294, plus a char-method map/filter case); 280 JS unit tests green (23 suites);
a direct node probe matches the interpreter byte-for-byte. Residual (documented in `BUGS.md`): a char
*literal*'s `.toInt` (`'5'.toInt`) still diverges — literals stay JS strings to avoid touching the
literal-pattern `===` codegen; a separate, lower-value follow-up.

## 2026-06-21 — feat(rust-web): runnable web-signals example + 2 bug fixes it surfaced

Added `examples/rust/web-signals.ssc` — the first committed, **cargo-verified** reactive `serve(view, port)`
example (`signal` + `computedSignal` + `signalText`). Building and running it end-to-end — not just
string-matching the codegen, which the 23 existing `RustGenWebToolkitTest` tests do — surfaced two real bugs:

1. **Codegen use-after-move (cargo `E0382`).** A `computedSignal(() => loc())` move-closure captured the
   signal local `loc` by value, so rendering the same signal afterwards (`signalText(loc)`) failed to compile.
   `RustCodeWalk.renderClosure` now clone-captures each signal local a closure reads
   (`{ let loc = loc.clone(); move || … }`), leaving the original usable. New regression test; `backendRust`
   228/0.
2. **Wrong `/__ssc/push` format in the docs.** The endpoint reads query params
   (`/__ssc/push?name=<n>&value=<v>`), not a POST body. Corrected the example plus `docs/rust-backend.md`
   and `docs/user-guide.md` (they showed `curl -X POST -d 'locale=de'`, which silently no-ops).

Verified: `cargo build --release` green; the binary SSRs, and `/__ssc/push?name=locale&value=de` flips state
`{"__c0":"fr","locale":"fr"}` → `{"__c0":"de","locale":"de"}` — the computed signal recomputed server-side.
LESSON: cargo-build the examples; `.contains`-on-codegen tests can't catch a borrow-checker error.

## 2026-06-20 — feat(registry): no-domain static package registry

The package registry now ships without requiring a domain registration. `RegistryClient.DefaultRegistryUrl`
points at `https://sergey-scherbina.github.io/scalascript/packages.yaml`; `registry/site/` contains the
generated Pages artifact (`packages.yaml`, HTML index, search JSON, per-package JSON), and
`.github/workflows/registry-pages.yml` rebuilds/deploys it through GitHub Pages without `CNAME`.

The registry docs/specs now record the hosting decision: use the GitHub Pages project URL for Phase A, add
`registry.scalascript.io` later as an alias only. Verified locally with `scala-cli --server=false
tools/registry-site/generate.sc -- registry/packages.yaml registry/site`, artifact file checks, and
`core/testOnly scalascript.imports.RegistryClientTest scalascript.imports.RegistryPrivateTest
scalascript.imports.RegistrySchemaTest scalascript.imports.RegistrySiteGeneratorTest` (56 tests).

## 2026-06-20 — feat(rust-web): direct-WS signal transport (S5 complete)

The last rust-web S5 refinement. A `serve(view, port)` program now also exposes a WebSocket signal endpoint
on `port + 1` for external/programmatic clients (e.g. the rozum bridge), integrated with the same signal
store / broadcast / recompute as SSE. `ssc_ws_serve` (spawned beside the HTTP listener) accepts WS
connections (`tokio_tungstenite::accept_async`), sends the current state on connect, streams updates, and on
each incoming `name=value` text frame calls `ssc_set_and_notify` (set → recompute → broadcast) — bidirectional.
tokio-tungstenite + futures-util are pulled in for a UI serve program.

Verified end-to-end: a raw WS client (Python stdlib handshake + one masked text frame) pushed `locale=de`,
and HTTP `/__ssc/state` then returned `{"__c0":"de","locale":"de"}` — the WS push set the signal and
recomputed the derived one. `backendRust` 226/0 — no regression. **rust-web S5 is now complete**: set/toggle,
SSE, computed (compile+SSR + live recompute), typed reads, and direct-WS — all built and cargo/curl/WS-verified.

## 2026-06-20 — feat(wasm): effectful `@main` args/non-Unit edge complete

Closed the last `wasm-effects` edge. The effectful WASM path now derives the user `@main` from the AST
instead of a name-only regex, emits a synthetic Scala.js wrapper that preserves a single Scala 3 main
parameter clause (including `String*` splicing), and keeps the wrapper `Unit`-returning by discarding any
user non-`Unit` return value. Raw `Array[String]` `@main` args now fail before scala-cli with a clear
"use `String*`" diagnostic because Scala 3 `@main` does not accept raw argv arrays.

Verified after rebase: `backendWasm/testOnly scalascript.codegen.WasmBackendTest` — 40/40 green. Gotcha
recorded in `specs/wasm-main-edge.md`: Scala.js ES-module launcher argument delivery is out of scope; a
direct Node probe supplies empty `String*` args.

## 2026-06-20 — feat(rust-web): computed-signal LIVE recompute + typed signal reads (S5)

Computed signals on the Rust backend are now **fully reactive**, completing the S5 work. A derived signal
recomputes server-side when a dependency changes and the new value streams to clients via the SSE transport.
The signal store moved to `value.rs` so `Value::signal_value` can read it (store-backed reads); value.rs
gained a computed-closure registry + `ssc_register_computed`/`ssc_recompute_all`; `_ui_computed_signal` is
now a re-runnable `Fn` that registers + returns a NAMED signal (`data-ssc-text="__cN"`); `/__ssc/push`
recomputes before broadcasting. Verified end-to-end (cargo+curl): `signal("locale","fr")` +
`computedSignal(() => loc())` returns `{"__c0":"fr","locale":"fr"}`, and after `push locale=de` returns
`{"__c0":"de","locale":"de"}` — the computed signal auto-recomputed.

Also: **typed signal reads** — the store is String-valued, so a `Signal[Int]` read in arithmetic now coerces
(`collectLocalSignals` carries the element type; the apply emits `.parse::<i64>()`/`.parse::<f64>()` for
Int/Double, `.show()` for String). `signal("n", 10)` + `n() + 5` → renders `15`. `backendRust` 225/0 — no
regression. The only remaining S5 item is direct-WS, now low-value (SSE supersedes it).

## 2026-06-20 — feat(rust-web): computed signal reading another signal compiles + SSRs (S5)

The foundational computed-recompute fix. A computed signal that reads another signal —
`computedSignal(() => loc())` — did **not compile** before (the read `loc()` emitted a bare call, but the
signal lowers to `Value`, which isn't callable: `error[E0618]`). Now a 0-arg apply on a `Signal`-typed local
is recognized as a signal READ and lowers to `loc.signal_value().show()`: a per-def `collectLocalSignals`
pre-pass tracks signal-bound locals into the render `Ctx` (scope-correct, inherited by closures), the apply
lowering emits the read, and `Value::signal_value` now takes `&self` + clones (so a repeatedly-called
computed closure doesn't move the captured signal). `.show()` yields the value's `String` form —
`Signal[String]` is the UI/i18n signal type and `computedSignal` takes `() => String`.

Verified: `computedSignal(() => loc())` cargo-builds and the binary SSRs the dependency's initial value
(`signal("locale","fr")` → `…<span data-ssc-text="">fr</span>…`); `backendRust` 223/0 — no regression (the
lowering is gated to 0-arg applies on tracked signal locals). This is the compile+SSR layer (the real
blocker — it was broken). LIVE recompute (the client picks up dependency changes) is a further layer — named
computed signals + a server-side closure registry + store-backed reads across the value↔http module
boundary — designed, not in this commit.

## 2026-06-20 — feat(rust-web): SSE push transport for signal updates (S5)

Replaces the rust-web SSR server's 1 s state-poll with real-time Server-Sent Events (poll kept as a
fallback). New `/__ssc/events` endpoint streams `data: <signal-state-json>\n\n` frames as a `StreamBody`
from a `tokio::sync::broadcast` channel; `/__ssc/push` and `_ui_broadcast_signal` notify subscribers via
`ssc_set_and_notify`; response bodies unify to `BoxBody`; the client prefers `EventSource('/__ssc/events')`.
Deps: tokio `sync` + `tokio-stream` (gated on http usage). **Verified end-to-end via curl** — a running
server streams `data: {}` on connect then `data: {"count":"42"}` immediately after a push — not just
codegen; `RustGenWebToolkitTest` +1, `backendRust` 223/0.

This closes the SSE half of the S5 "streaming transport" deferral and corrects the earlier "browser-only,
unverifiable" note (the server side is fully curl-verifiable). Remaining S5: **computed-signal recompute**
(the one real remaining feature — deep: needs store-backed signal reads through the core `Value::Signal`
apply path; designed, not rushed) and **direct-WS** (now low-value — SSE provides server→client push).

## 2026-06-20 — test: hang-proof the cross-backend subprocess tests + 4-min server leak-hunt

Two follow-ups completing the quality/perf queue:

- **xbackend hang-proof sweep.** The blocking-read-before-`awaitExit` antipattern (which hung
  `CrossBackendPropertyTest`) was copy-pasted across ~30 test files. Added `ProcTestUtil.runOrThrow` (the
  safe `runCaptured`-backed path) and converted all **17 both-streams (genuine deadlock-risk) files** to it,
  removing the now-unused `scala.io.Source` imports. The ~22 `redirectErrorStream(true)` single-stream files
  are deadlock-safe by construction (and some intentionally ignore exit code), so they're left as-is —
  `runOrThrow`/`runCaptured` is the standard for new tests. Verified: test module compiles (-Werror), 54
  converted tests run green across JS + JVM legs (behavior preserved).
- **Server leak-hunt** (the demand-driven long run for `tests/perf/serverrss`). 4 minutes of sustained
  load: **definitively no leak** — RSS peaked at 205 MB and *ended at 80 MB* (the JVM reclaimed and returned
  heap to the OS as load tapered, the opposite of a leak's climb), GC light/steady (516 short pauses /
  233 ms). Recorded in the serverrss README.

## 2026-06-20 — perf(server): steady-state RSS + GC harness (real-workload-perf complete)

The third and last unmeasured perf axis: a long-running `ssc` HTTP server's memory footprint and GC under
sustained load. New `tests/perf/serverrss/run.sh` boots a real server (`examples/health-defaults.ssc` on the
JVM interpreter, `-Xmx512m` + GC log), drives concurrent load, samples RSS over the run, and reports the
steady-state footprint + start→end drift (a leak signal) + GC pause count/time, with a `SERVERRSS_*`
machine-readable tail. Pure bash + the JVM launcher (no scala-cli/bloop); reliable teardown on exit.

Baseline (20 s / 4 loops, JDK 21): the interpreter server settles at **~195 MB RSS and is STABLE under
load** — ramps from ~184 MB cold to a ~195 MB plateau with no further climb (no leak), and **light GC**
(~41 short pauses / 27 ms). The verdict flips to `GROWING` if start→end drift exceeds 20%; a minutes-scale
leak-hunt (`secs=300+`) is left to demand. With this, `real-workload-perf` is complete — all three axes
(cold-start, steady-state RSS, GC-under-load) now have harnesses + baselines.

## 2026-06-20 — feat(rust-web): set/toggle signal client wiring

Closes "set/toggle client wiring" from the rust-web-toolkit S5 deferred list. `setSignal`/`toggleSignal`
were no-ops in the Rust SSR runtime (`Value::Unit`) — a button `["click" -> setSignal(sig, v)]` or checkbox
`["change" -> toggleSignal(s)]` produced no client wiring. Now they mirror how `inputChange` already works:
`_ui_set_signal`/`_ui_toggle_signal` encode `ssc-set:<name>:<value>` / `ssc-toggle:<name>` markers,
`_ui_element` surfaces them as `data-ssc-set` / `data-ssc-toggle` attributes, and the appended client script
gains a `click` handler that sets/flips the signal locally (new `_sscState` map) and persists to
`/__ssc/push` so the 1 s state-poll doesn't revert it. Verified: `RustGenWebToolkitTest` 18/18 (markers +
attrs + client wiring) and a cargo build of a set/toggle probe confirming the changed runtime compiles as
real Rust; `backendRust` 222/0. Browser *click behaviour* remains browser-dependent (unverified here).

## 2026-06-20 — test(xbackend): hang-proof subprocess runner + cross-backend differential in CI

The cross-backend property differential (interp == JS(node) == JVM(scala-cli) over generated programs) is
now standing in CI, after fixing the reliability bug that made it unsafe to run.

- **Hardening:** `CrossBackendPropertyTest.runProc` read subprocess streams with blocking
  `Source.fromInputStream(...).mkString` *before* the bounded `awaitExit`, so the read-to-EOF parked first —
  a wedged scala-cli could hang the whole suite forever (observed firsthand), and a child flooding stderr
  while we blocked on stdout could pipe-buffer-deadlock. New `ProcTestUtil.runCaptured` drains both streams
  on daemon threads and applies a hard timeout that actually fires (force-kill → streams close → drain
  threads finish). `ProcTestUtilTest` proves it: `sleep 60` bounded to 2s returns in <15s; a 5000-line
  stderr flood doesn't deadlock.
- **Broaden:** already complete — the generator covers 12 program kinds incl. effects / Option / Either /
  closures-as-values / nested collections (the BACKLOG "REMAINING" list was stale); node leg verified at
  74 programs, interp==JS, 0 skipped.
- **CI:** the `sbt` job had only Java + sbt, so the test SKIPPED (`assume` node/scala-cli). Added Node.js
  setup → the interp==JS differential now runs in CI, hang-safe via the timeout. The scala-cli JVM leg
  stays gated (the Conformance job covers it).

## 2026-06-20 — perf(cli): AppCDS cuts `ssc` cold-start ~50% + cold-start harness

`real-workload-perf` slice (a) — the cold-start axis `scripts/bench wall` deliberately excludes (it reports
work-time only). New `tests/perf/coldstart/` harness (pure bash + JVM launcher, no scala-cli/bloop so it
can't hang) measures fresh `ssc run hello.ssc` wall-clock + peak RSS, baseline vs AppCDS, with a
`COLDSTART_MS`/`RSS` tail for regression capture. Measured baseline: ~378 ms / 167 MB peak for
`println("hello")` — JVM boot (~36 ms) + classloading the 88 MB fat jar dominate.

Cut shipped into `bin/ssc` and the `install.sh`-generated launcher: **Application Class-Data Sharing**
(`-XX:+AutoCreateSharedArchive`, JDK 19+; archive auto-created on first run and auto-recreated on classpath
change → no build step). **378 → 182 ms (−51%)**, and peak RSS **167 → 114 MB (−32%)** as a bonus (shared
classes mmap'd read-only). Deliberately CDS-only, not `-XX:TieredStopAtLevel=1` (which would speed startup a
touch more but cripple long-running `ssc serve` throughput). Old JDKs ignore the flags; opt out with
`SSC_NO_CDS=1`; archive in `~/.cache/scalascript`. First-run CDS log silenced so it never pollutes piped
stdout. The GraalVM native binary needs no CDS. Remaining slices (b) steady-state server RSS, (c) GC under
load — documented, need a long-running-server harness.

## 2026-06-19 — feat(interp): typeclass-fold memo — ~19% on `combineAll`-style folds (default-on)

The slow typeclass case (`xs.foldLeft(summon[M].empty)(summon[M].combine)`) is now ~19% faster — achieved
safely at the interpreter level rather than the invasive VM "Slice C". A JFR profile showed the cost is
~79% `evalCore` tree-walk of the `summon[M].empty` / `summon[M].combine` sub-expressions, re-evaluated every
call (not the fold loop or the given lookup). So `evalFusedFoldLeft` memoizes the evaluated `(empty,
combine)` per call-site, keyed by both resolved given identities; repeat calls skip those sub-expressions.

ON by default (kill-switch `-Dssc.jit.foldtc=0` / `SSC_JIT_FOLDTC=0`) — caching the evaluated `empty`
assumes a lawful, referentially-transparent monoid (a side-effecting `empty` is an anti-pattern; disable it
via the switch). No VM changes, no hot-path guard relaxation. Verified: `JitFoldTcTest` 8 differential
tests (memo-on == memo-off across single/repeated/string-monoid/**polymorphic two-given**/nested shapes) +
the full interp suite green WITH IT ON (1839 tests, excluding only the infra-flaky cross-backend
result-equivalence test, which the memo preserves). MEASURED: `typeclassFoldMacro` **1.794 → 1.453 ms/op**
(~19%, non-overlapping error bars). The full VM Slice C stays unbuilt — disproportionate
(`specs/jit-foldleft-compile.md`).

## 2026-06-19 — feat(jit): compile `List[Int].foldLeft` into an inline VM loop

A function containing a `List[Int].foldLeft((a,b) => body)` used to bail the *whole* function to tree-walk;
now it compiles — the fold loop and the surrounding code. New `LITERINIT`/`LITERHN`/`LITERNXI` opcodes (List
cursor = bare `List[Value]` in the ref bank, O(1) tail) + `VmCompiler.tryCompileFoldLeft`, which inlines the
lambda body into the accumulator (no `CALLREF`). Statically gated to `List[Int]` receivers so the `IntV`
unbox can never misfire. ON by default; kill-switch `SSC_JIT_FOLDLEFT=0` / `-Dssc.jit.foldleft=0`.

Verified: `JitFoldLeftTest` 17 differential tests (JIT-on == JIT-off == hand-computed across 15 shapes incl.
fold-inside-a-larger-function) + a counter proving the surrounding function actually compiles; the **full
interp suite is green with the feature ON (1878 tests)** — no mis-fire on existing programs.

Honest perf note: no measured win on the micro-benchmarks (`foldLeftLambda` 0.004↔0.003 ms/op,
`foldLeftThenWork` 0.004 both — within noise), because the interpreter's `foldLeftReusing` fast-path and
while-JIT already optimize the hot parts of every plain-lambda fold. Shipped as a correct, safe capability
that closes the compilation gap. The slow typeclass case (`summon[M].combine` is a type-method,
`typeclassFoldMacro` 1.14 ms) still needs the deeper "Slice C" — verified disproportionate, not pursued
(`specs/jit-foldleft-compile.md`).

## 2026-06-19 — chore(board): clean stale sprint and bug ledger markers

Docs-only coordination cleanup. The duplicate open `sbt-plugin-finish` SPRINT marker is now closed as
actionable-scope done with Maven/plugin-portal publication left deferred, matching the detailed Phase 5
entry above it. `BUGS.md` no longer has stale embedded `Status: open` lines inside fixed entries for
`jvmgen-multishot-handle-result-any`, `jvmgen-handle-in-arg-position`, and
`js-self-handling-cps-fn-not-run`. This prevents agents from reclaiming already-fixed work during queue
sweeps.

## 2026-06-19 — test(cli): complete `ssc new` / standalone install audit

`ssc new` now matches `specs/arch-ssc-new.md` for the local scaffold surface: generated projects run a
best-effort `git init -q`, and CLI usage lists all bundled templates (`app`, `lib`, `plugin`, `dsl`,
`web-app`, `wasm-app`). Root `install.sh` now follows the documented split: no args print standalone
Coursier/Homebrew/curl install guidance, while `--dev` runs the monorepo sbt staging build. The spec now
records the 2026-06-19 audit result, clarifies that the plugin template intentionally has no
`project/plugins.sbt`, and keeps live channel publication deferred. Tests added: all-template rendering
without leftover placeholders, output-dir aliases, git-init, and standalone release fixtures
(`NewProjectTest`, `StandaloneInstallFixturesTest`; 8 targeted tests green).

## 2026-06-19 — feat(interp): bitwise operators on Int — `&` `|` `^` `<<` `>>` `>>>` `~`

`Int` (Long-backed) now answers the standard integer bitwise operators on the interpreter backend.
They are served by `DispatchRuntime.dispatchInt` alongside `toBinary`/`toHex`/`isEven` (they ride the
method-dispatch path, since they are not in `PatternRuntime`'s arith set), so they work uniformly in
plain expressions and compiled function bodies. Both operands must be `Int`; other right-hand operands
fall through to normal dispatch unchanged. Unlocks bit math in `.ssc` (masks/flags, hashing, and the
GF(256)/Reed–Solomon code a QR-payload encoder needs — the busi ZBP-QR use-case). Spec:
`specs/bitwise-operators.md`; example: `examples/bitwise-operators.ssc`; test: `BitwiseTest` (6 cases).
Follow-up (BACKLOG): fast-path folding + codegen-backend (JS/Rust/WASM/JVM) emission + BigInt bitwise.

## 2026-06-18 — chore(perf): close out the three deferred perf items (re-measured)

The three perennially-re-investigated deferred perf items now have permanent, data-backed verdicts (3rd+
round) so they stop being re-litigated. Re-measured on current main:

- **hof-glue-jit-compile** → **DEFERRED to the dual-bank `LExpr` VM roadmap.** `typeclassFoldMacro` =
  1.142 ms/op vs `typeclassFold` = 0.005 ms/op — the static fold fully JITs; the 228× gap is the macro
  version's per-call given/summon glue. Loop+combine are already native+JIT'd (`foldLeftReusing`); the only
  lever left is whole-function JIT of `combineAll`, which needs SscVm List-iteration opcodes + a `foldLeft`
  recognizer + `using`/given-member JIT support — gated on the LExpr VM work, not a bounded slice.
- **vectorize-pure-loop** → **WONTFIX until a motivating workload.** `jdk.incubator.vector` is unreferenced;
  `pureCallSum*` bypass the loop via the Gauss closed-form (`walkLinearPoly`) so SIMD helps them 0%. No
  non-polynomial hot loop exists to justify the incubator dependency / ABI churn.
- **direct-style-eval** → **WONTFIX (data-disproven).** `Computation.Pure` is built at 1261 sites; alloc is
  ~16% Pure / ~66% dispatch (untouched by the migration) → sub-15% win for a 1200-site high-risk change.

No code change — these are close-outs with fresh numbers; the project's micro-perf is at its floor.

## 2026-06-18 — feat(sbt-plugin): `sscBackends` cross-build

Resolves `arch-sbt-plugin.md` open-question #2 → **design A (parallel outputs in one `compile`)**, the fit
for the plugin's thin fork-the-CLI model (separate sbt configs would turn a CLI-flag axis into a
cross-project framework). New `sscBackends: Seq[String]` setting (default `Seq(sscBackend.value)`);
`sscCompile` forks `ssc build --backend <b>` once per backend. A single backend (the default) writes the
flat `sscArtifactDir` — byte-identical to before (backward-compatible); multiple backends each write
`sscArtifactDir/<backend>/`. Scripted `cross-build/` (`Seq("jvm","js")` → per-backend markers with the
right backend id); full scripted suite green (10 tests). Cross-build *publication* stays Maven-gated.

## 2026-06-18 — test(wasm): cross-module effects already work (wasm-effects effectively complete)

The last wasm-effects follow-up needed **no code change**. A probe showed `JvmGen.generateUserOnly(module,
baseDir)` already resolves local `.ssc` imports and lowers the whole graph — so an `effect` declared in an
imported `lib.ssc` and only handled in the consumer lowers correctly (`object Log` + `_perform` + the
inlined `shout()`), and `collectSource` inlines the declaration so `usesEffects` routes to the effect path.
Added a run test ('cross-module effects RUN on wasm') that splits the effect declaration from its handler
and runs the compiled `.wasm` via node → `hello\nworld`; 36 `WasmBackendTest` green. With this the
wasm-effects follow-ups are complete — arithmetic (`_binOp`), collection HOFs (`_dispatch`), multi-shot
(`_anyFlatMap`), and cross-module all run on wasm; only the minor `@main`-with-args/non-`Unit`-return edge
remains.

## 2026-06-18 — feat(wasm): multi-shot resume in effects

Follow-up slice — and it did **not** need a `_handle` rewrite (the earlier-feared blocker). The wasm
`_handle`'s `resume = (v) => interp(fn(v))` already supports calling resume repeatedly (same structure as
the JVM `_handle`). A probe showed the canonical multi-shot handler `opts.flatMap(o => resume(o))` lowers
to `_anyFlatMap(opts, ..)` + `_dispatch(all, "length", ..)` — only `_anyFlatMap` was missing. Two fixes:
added the pure-Scala `_anyFlatMap` to `WasmEffectRuntime`, and fixed `WasmGen.usesEffects` to recognise the
`multi effect Foo:` declaration form (it keyed on a leading `effect`, so a multi-shot module skipped CPS
lowering and its `multi`/`!` syntax reached scala-cli raw). Verified: a `NonDet` program
(`choose(List(1,2)) × choose(List(10,20))`) compiles to `.wasm` and runs via node — `all.length == 4`;
35 `WasmBackendTest` green. Remaining wasm-effects follow-up (BACKLOG): cross-module effects.

## 2026-06-18 — feat(wasm): collection HOFs in effects (`_dispatch` in the wasm effect runtime)

Follow-up to the arithmetic slice. A probe showed `xs.map(..)`/`.filter(..)`/`.head` on an `Any`-typed
effect result lower to `_dispatch(xs, "map", List(fn))`, which `WasmEffectRuntime` lacked → such programs
failed at link. Added the pure-Scala subset of `_dispatch` + its CPS-aware helpers (`_seqMap`/`_seqFlatMap`/
`_seqFilter`/`_seqForeach`/`_seqExists`/`_seqForall`/`_seqCount`/`_seqFind`/`_seqFoldLeft` + `_seq`/`_isFree`).
The JVM version's Java-reflection `case _` fallback (`getClass.getMethods…invoke`) can't link under Scala.js,
so unknown methods raise a clear error instead. Covers List/String/Option/Map/Set/numeric methods (incl.
`sortBy`/`sorted`). Verified: a `xs.map(*2).filter(>4).head` program inside handler + resume compiles to
`.wasm` and runs via node (prints `6`); 34 `WasmBackendTest` green. Remaining wasm-effects follow-ups
(BACKLOG): multi-shot resume, cross-module effects.

## 2026-06-18 — feat(wasm): arithmetic in effects (`_binOp` in the wasm effect runtime)

Follow-up to the wasm-effects first slice. A probe showed an effect program doing arithmetic over op
results (`a + b` where the operands are threaded through `_bind` as `Any`) lowers to `_binOp("+", a, b)`,
which `WasmEffectRuntime` lacked — so such programs failed at link. Added the pure-Scala subset of
`_binOp` (+ `_bigIntOp`/`_bigDecOp`; all Int/Long/Double/String/BigInt/BigDecimal/Set/Map cases are
Scala.js-linkable) to the wasm effect runtime. Verified: a handler + resume + `sum * 2` program compiles
to `.wasm` and runs via node (prints `40`); 33 `WasmBackendTest` green. Still deferred (BACKLOG
`wasm-effects`): `_dispatch` (its reflection fallback can't link under Scala.js → needs a pruned copy),
multi-shot resume, cross-module effects.

## 2026-06-18 — feat(sbt-plugin): Phase 5 dependency resolution

The sbt plugin now lifts Maven dependencies declared in a `.ssc` front-matter `dependencies:` map into
sbt `libraryDependencies`, so Coursier resolves them onto the JVM/test classpath alongside the generated
facade (`arch-sbt-plugin.md` §3h). Phase 5's other parts (Maven Central publish + Plugin Portal) stay
Maven-gated.

- `SscFrontMatter` — a narrow, dependency-free front-matter extractor (the standalone plugin build has no
  YAML library and no core dependency). Reads the `---`…`---` block + `dependencies:` map (block or
  inline-flow) and keeps only Maven coordinates using core's rule: `dep:g:a:v` → `g % a % v` (Java),
  `dep:g::a:v` → `g %% a % v` (Scala-cross). Local `.ssc` paths / URLs / `git:` are ignored.
- New `sscManagedDependencies` setting (derived from Compile `.ssc` sources at project-load);
  `libraryDependencies ++= sscManagedDependencies.value`. `reload` to pick up `.ssc` edits.
- Scripted `dep-resolution/` asserts the wiring (Java `%`, Scala-cross `%%`, local-path ignored) without
  Coursier resolution → no network. Full scripted suite green (9 tests).

## 2026-06-18 — feat(wasm): algebraic effects on the WASM backend (compile + run)

Effects now **work on wasm** — superseding the previous round's clear-error stopgap. The blocker was
never the language: JvmGen's effect-lowered Scala compiles fine under Scala.js; what crashes the
Scala.js linker is the ~300 KB JVM preamble (its `Thread`/`java.nio` generator/coroutine/`std.fs`
parts). So we drop the preamble.

- `WasmGen.compileToWasm` routes effectful modules through `compileEffectfulToWasm`:
  `JvmGen.generateUserOnly` (CPS-lowered user code, **no** full preamble) + `WasmEffectRuntime` (a
  minimal **Scala.js-linkable** effect runtime — the pure-Scala subset of `JvmGenRuntimeSources`'
  `_Computation`/`_bind`/`_perform`/`_run`/`_handle`/`_handleWithReturn`) emitted in `package
  _ssc_runtime`, + a re-added wasm `@main` (generateUserOnly strips the user's `@main`).
- `backendWasm` now `dependsOn backendJvm` to reuse the CPS lowering (no cycle).
- Verified **end-to-end**: `WasmBackendTest` compiles an effect program to a valid `.wasm` **and runs
  it via node** (handler + resume → `hello\nworld`), 32 tests green.
- First slice = single-module one-shot effects on the core runtime + stdlib. Follow-ups (BACKLOG
  `wasm-effects`): handlers needing `_dispatch`/`_binOp`, multi-shot resume, cross-module effects.

## 2026-06-18 — feat(wasm): clear error on effects + scope `wasm-effects`

Investigated algebraic-effects-on-WASM empirically (probe). Finding: JvmGen's effect-lowered Scala
**compiles** under Scala.js (no `java.*` compile blocker), but the Scala.js **linker crashes** on
JvmGen's ~300 KB preamble — and it's **general** (even a trivial `@main` through `JvmGen.generate →
scala-cli --js-emit-wasm` fails to link the same way), so reusing JvmGen verbatim is blocked.

- A real implementation is a multi-day feature: `JvmGen.generateUserOnly` (lowered user code, no full
  preamble) + a hand-written **Scala.js-linkable minimal effect runtime** + verification on a wasm
  runtime. Scoped in BACKLOG `wasm-effects` with the concrete approach so it isn't re-investigated.
- Until then, `WasmGen.compileToWasm` **fails fast with a clear message** when effects are used (keys on
  the unambiguous `effect <Cap>:` declaration), surfaced as `CompileResult.Failed` — instead of the
  cryptic Scala.js linker crash. Effects run on JVM / JS / interpreter. Test added.

## 2026-06-18 — feat(wasm): cross-module wasm — import inlining + macro expansion

Brought the WASM backend up to parity with JVM/JS on the language surface it can support: it used to
silently ignore `Content.Import` (cross-module wasm lost the imported code) and didn't expand macros.

- `WasmGen.compileToWasm` now runs `MacroCodegen.expand(module, baseDir)` first (restricted quoted
  macros, single + cross-module), and `collectSource` **inlines local `.ssc` imports**: resolve (no
  scheme/download) → parse → `MacroCodegen.expand` (strip the import's own macros) → recurse, **deduped +
  cycle-safe** (`seenFiles`). So a wasm `.ssc` importing sibling `.ssc` modules (transitively) and/or
  using quoted macros now compiles.
- Extern-free / import-free blocks stay byte-identical (no regression). 5 new tests incl. transitive +
  diamond-dedup + single-module macro + a `@wasm`-extern compiled **end-to-end to a real `.wasm`** binary.
- Still out of scope (documented in `WasmGen`): algebraic effects / handlers (need the CPS codegen) and
  `std` externs without a `@wasm` impl (dropped). The backend now handles macros, imports, and `@wasm`
  FFI on top of Scala.js-compatible code.

## 2026-06-18 — feat(wasm): wire `@wasm` extern FFI + tidy the WASM backend

The WASM backend (`runtime/backend/wasm`, Scala.js → `.wasm` via `scala-cli --js-emit-wasm`) already
existed and compiled; what was missing was the `@wasm` FFI layer, and `extern def`s made `WasmGen` choke
(it passes block text verbatim to scala-cli, which can't parse `extern`/`__extern__`).

- `WasmGen` now re-emits any extern-carrying block from its parsed tree: each `@wasm("expr")` extern is
  lowered to a real `def` (with `$0`/`$1` → param substitution and the FFI annotations stripped), and
  externs with no `@wasm` impl are dropped (a call then fails clearly, not as an `extern` syntax error).
  Extern-free blocks keep the byte-identical raw passthrough — no regression (the scala-cli → wasm compile
  tests stay green). 3 new `WasmBackendTest` cases.
- Reconciled the stale docs: `specs/arch-ffi.md` no longer claims "no WASM compilation target exists" (it
  does). `@wasmExport`/`@wasmImport` (raw WASM ABI) stay out of scope **by design** — the Scala.js path
  owns the wasm ABI, so they'd need a direct-emit wasm backend.

## 2026-06-18 — docs: triage remaining roadmap items to honest status

Worked through the remaining roadmap menu and resolved each to an accurate status instead of leaving
stale/misleading entries:
- **module-graph-grouping** — ✓ investigated → leave-as-is (`docs/module-graph-findings.md`): 197 module
  defs; the per-impl module *is* the SPI boundary, so grouping the thin families either collapses it or is
  a build-graph no-op (sbt `aggregate` only). No action.
- **build-registry Phase 3** — MOOT: `PluginManifest`/`LocalRegistry` are the implementation the facade is
  built on (not removable wrappers); `isStdPluginInterpreterTest` already gone. Nothing to remove.
- **metaprogramming-v2 C2** — the high-value slice is covered (the new `ssc check` interp-only-macro
  warning); the full re-typecheck-with-positions is deferred as low-ROI (needs a position map + risks
  false positives, niche audience).
- **std-nfc / wallet-browser-ws-itest** — blocked autonomously (need device/browser); **@wasm glue** needs
  a WASM backend; **Maven** is gated/last. Marked accordingly.

## 2026-06-18 — feat(meta-v2): `ssc check` warns on interpreter-only macros

Closes a DX hole in the quoted-macro feature: a macro entrypoint whose impl is defined locally but is
**not expandable for codegen** (an interpreter-only body — not a direct quote `'{ … }` and not an
`Expr.asValue match`, e.g. `x.asValue.getOrElse(…)` / `x.asTerm`) runs on `ssc run` but fails the JVM/JS
target compiler with a cryptic `__ssc_macro__ not found`. `MacroCodegen.codegenWarnings(module)` now
detects these, and `ssc check` surfaces them as warnings (exit code unaffected — `ok` ignores warnings),
mirroring the existing `contentToolkitLintWarnings` wiring. 3 unit tests.

## 2026-06-18 — feat(meta-v2): cross-module macros on JS — completes JVM+JS

A quoted macro defined in an imported module and called from a consumer now works on the **JS backend**
too (JVM landed earlier same day), completing `macro-crossmodule` on both generated backends.

- JsGen has no assembled-block list (it emits imports inline via a string-appending child `JsGen` with
  many code-block passes), so the JVM Approach B doesn't transfer. JS uses **Approach A**: the entry-hook
  `MacroCodegen.expand(module, baseDir)` seeds the call table from the consumer's **local relative `.ssc`
  imports** (`base / RelPath`, no `ImportResolver` download, no std/external parse, fault-tolerant) and
  expands the consumer's call sites; `JsGen.genImport` strips the imported module's own macro defs
  (`MacroCodegen.expand(childModule)`, no `baseDir` → no recursive resolution).
- Strict no-op for macro-free modules — 39 transitive-import + 22 JS/macro/conformance tests green.
- `QuotedMacroCrossModuleJsTest` (node) matches the interpreter (`literal: 7`). Follow-up: transitive
  cross-module macros on JS (rare). The macro feature now works single-module **and** cross-module on
  interp + JVM + JS.

## 2026-06-18 — feat(meta-v2): cross-module macros on JVM

A quoted macro **defined in an imported module** and **called from a consumer** now works on the JVM
backend (single-module already worked; the imported `inline def … = __ssc_macro__(…)` used to be inlined
verbatim → compiler failure).

- `MacroCodegen.expandUnits` — an assembled-block core (Approach B): collects macros across a flat
  `(tree, source)` set and strips/expands each, sharing `collectMacrosFromStats` / `nodeStats` /
  `transformUnit` with the single-module path.
- `JvmGen.expandMacrosInBlocks` runs it at the 4 **top-level** `collectBlocks` sites — the full consumer +
  inlined-imports set, never the nested per-import call — so the imported macro defs and the consumer's
  call sites coexist there: **no import resolution, no double-parse**. Strict no-op for macro-free sets
  (29 JVM codegen/conformance tests green).
- `QuotedMacroCrossModuleJvmTest`: lib defines the macro, consumer imports + calls it, scala-cli matches
  the interpreter (`literal: 7`). **JS slice remains** (JsGen has no assembled-block list — see BACKLOG
  `macro-crossmodule`).

## 2026-06-18 — feat(meta-v2): Track C1 — multi-clause inline cross-module expansion

`inline def f(a)(b) = body` was excluded from the cross-module inline table. Now supported with no
scanner or `.scim` wire-schema change: `InterfaceExtractor.extractInlineInfo` curries the tail
parameter clauses into the body (params = first clause, body = `(b) => body`), so the existing
single-clause call-site scanner expands `f(x)(y)` to `((a) => (b) => body)(x)(y)` — it rewrites the
first clause and leaves the trailing `(y)` as an ordinary application. `using`/`given` clauses are
dropped. No regression to single-clause inline. 4 tests (`LinkerRewriteTest` curried 2-/3-clause +
`InterfaceExtractorTest` multi-clause body / using-drop). Track C2 (post-expansion re-typecheck) remains.

## 2026-06-18 — feat(meta-v2): macro-codegen-backends (JS) — quoted macros run on the JS backend

Completes `macro-codegen-backends` across both generated backends (JVM landed earlier same day), so
**meta-v2 Track B is fully done (B1 + B2 + B3)**.

- The backend-agnostic `MacroCodegen.expand` pass is now hooked into `JsGen.generate` /
  `generateUserOnly` / `generateSegmented` (no-op for macro-free modules). Because the pass produces
  plain ScalaScript source, the same code that landed for JVM works unchanged for JS.
- `QuotedMacroJsConformanceTest` runs an `asValue match` + a direct-quote macro through node and matches
  the interpreter (`literal: 7` / `42`). Verified no JS-codegen regression.
- `macro-codegen-backends` milestone complete (JVM + JS); removed from BACKLOG.

## 2026-06-18 — feat(meta-v2): macro-codegen-backends (JVM) — quoted macros run on the JVM backend

Restricted quoted macros now compile and run on the **JVM backend** (previously interpreter-only on the
generated backends), unblocking meta-v2 Track B3 on JVM.

- New `scalascript.artifact.MacroCodegen.expand` — a pre-codegen `ast.Module` pass hooked into
  `JvmGen.generate` / `generateUserOnly` (`+WithLineMap`). For a module with expandable macro
  entrypoints it builds the macro table + strip-set from the module's own macro defs, drops the
  entrypoint + impl definitions, and rewrites each call site to its beta-reduced expansion (literal arg
  → `Some` branch / const value, else the `None` direct quote; direct-quote macros too), reusing the
  const-fold parsers (`parseAsValueFold` / `normalizeQuotedMacroBody` / `isLiteralArg`).
- The emitter substitutes the bound variable directly (parenthesised) rather than lambda-lifting —
  scalac rejects `((n) => body)(7)` ("missing parameter type") and a block argument re-renders as a
  brace-arg; substitution skips string literals so a binder name in a string isn't corrupted.
- **Strict no-op** for macro-free modules (returned unchanged) → cannot regress working codegen;
  verified across 49 JvmGen codegen tests + Mirror/derives conformance.
- `QuotedMacroJvmConformanceTest` (scala-cli: `asValue match` + direct-quote macro → `literal: 7` / `42`)
  + `MacroCodegenTest`. spec `specs/macro-codegen-backends.md`. **JS slice is the follow-up.**

## 2026-06-18 — feat(meta-v2): Track B1/B2 — `Expr.asValue match` compile-time constant folding

Restricted quoted macros can now branch on whether an argument is a compile-time constant via
`Expr.asValue` — the literal-argument case const-folds.

- **Interpreter parity (B1):** the `${ }` splice (`__ssc_macro__`) unwraps an `Expr(v)` result to `v`,
  so a `Some` branch that returns `Expr(...)` produces the underlying value (matching the link-time fold).
- **Linker const-fold (B1) + `Expr(...)` construction (B2):** `InterfaceExtractor.extractMacroQuotedBody`
  now also captures `asValue match` bodies (not just direct `'{…}` quotes). `Linker.expandMacroSource`
  splits the macro table and const-folds asValue-match call sites per literality (`parseAsValueFold` +
  scalameta `isLiteralArg`): literal arg → the `Some(n)` branch (with `Expr(e)` unwrapped to `e`),
  non-literal → the `None` direct-quote fallback, both lambda-lifted for the backend to beta-reduce.
- `examples/quoted-macro-constfold.ssc`; `LinkerRewriteTest` (+7 cases) + `InlineDerivesTest` (+1).
- **B3 (generated-backend conformance) is BLOCKED**: quoted macros are interpreter-only on JVM/JS today
  (the `emit`/`build` codegen path neither expands nor strips them). Queued as `macro-codegen-backends`
  in BACKLOG; the B1/B2 fold is in place to feed it once that pipeline exists.

## 2026-06-17 — feat(meta-v2): Track A1c (JS) — stdlib structural `derives` cross-backend

Completes stdlib `derives Eq/Show/Hash/Order` on the JS backend (JVM landed earlier same day), so
`StdlibDerivesJvmConformanceTest` is green on interp + JVM + JS. With A1a/A1b/A2 + A1c, **`derives`
(custom AND stdlib) is now cross-backend complete for product (case-class) types.**
- New JS runtime helpers `_ssc_structShow` (renders `TypeName(field=value, ...)` WITH field names —
  unlike `_show`), `_ssc_structCompare` (field-by-field), `_ssc_structHash` (deterministic). `Eq`
  reuses the existing deep `_eq`.
- `JsGen.emitMirrorAndDerives` extended: registers EAGER structural givens in `_ssc_givens` for stdlib
  `derives` (they depend only on runtime helpers, not a user `const`, so no lazy getter needed), and
  routes the `Eq_T`/`Show_T`/`Hash_T`/`Order_T` summon keys through `_resolveGiven`.
- REMAINING Track A (deferred edge cases): sum-type mirrors/derives (enum, sealed), generic case
  classes, mixed user+stdlib+unknown derives clauses.

## 2026-06-17 — feat(meta-v2): Track A1c (JVM) — stdlib structural `derives`

Brings stdlib `derives Eq/Show/Hash/Order` to the JVM backend (was interpreter-only). Unlike custom
`derives` (A1b, which calls a user `derived(m: Mirror)`), the stdlib four define no `derived` — the
JVM synthesizes them STRUCTURALLY, mirroring the interpreter's `DerivesRuntime`.
- The A1b strip pass generalized to handle the stdlib four (`stripHandledDerives`); a `derives` clause
  is stripped only when ALL its typeclasses are handled (custom + stdlib), else left untouched.
- Synthesized givens use Scala `Product`: `Eq` = `a == b`, `Hash` = `a.hashCode`, `Show` =
  `_ssc_structShow` (`TypeName(field=value, ...)` via `productElementName`), `Order` =
  `_ssc_structCompare` (field-by-field, first non-zero). New `_ssc_struct*` preamble helpers.
- `StdlibDerivesJvmConformanceTest` — interp baseline + scala-cli JVM, identical output.
- REMAINING: the JS equivalent of A1c; sum-type mirrors; generic case classes.

## 2026-06-17 — feat(meta-v2): Track A2 — JS Mirror + custom `derives` (cross-backend bar green)

Third build slice of `arch-metaprogramming-v2` §4b Track A. Custom typeclass derivation and the
`Mirror` metadata surface now work on the **JS** backend, so the cross-backend conformance bar
(`CustomDerivesMirrorCrossBackendTest`) is green on **interp + JVM + JS**.
- New JS runtime helpers `_ssc_mkMirror` + `_ssc_def_given` (a lazy given getter via
  `Object.defineProperty`). JS `const` objects aren't hoisted, so an eager custom-derives instance
  registered before the user code would reference an uninitialised `const TC` — the lazy getter
  defers `TC.derived(...)` until the summon site runs.
- **JsGen** registers a per-product-type Mirror object (eager) and a custom-derives given (lazy) in
  `_ssc_givens` before the user blocks (no `derives`-stripping needed — JsGen already emits case
  classes as plain constructor functions), and routes `summon[Mirror.Of[T]]` / `summon[TC[T]]` for
  the synthesized keys through `_resolveGiven` (explicit user-given summon untouched).
- `CustomDerivesMirrorCrossBackendTest` JS case flipped `ignore`→`test` (now green).
- REMAINING Track A: **A1c** (stdlib structural `derives Eq/Show/Hash/Order` on the generated
  backends), sum-type mirrors (enum / sealed), generic case classes.

## 2026-06-17 — feat(meta-v2): Track A1b — JVM custom `derives` synthesis

Second build slice of `arch-metaprogramming-v2` §4b Track A. User-defined typeclass derivation
(`case class T(...) derives Csv` where `object Csv: def derived(m: Mirror)`) now works on the JVM
backend, matching the interpreter.
- `_SscMirror[+A]` is now covariant (phantom tag) so a per-type mirror is accepted where
  `def derived(m: Mirror)` (= `_SscMirror[Any]`) is expected.
- **JvmGen** detects user typeclasses with a `derived` method, strips all-custom `derives TC` clauses
  from the emitted case classes (both the parsed tree and `block.src`, via tree positions — scalac's
  `derives` contract can't satisfy the SS `derived(m: Mirror)` signature), and appends
  `given TC[T] = TC.derived(summon[Mirror.Of[T]]).asInstanceOf[TC[T]]` reusing the A1a per-type Mirror given.
- `CustomDerivesMirrorCrossBackendTest` JVM case flipped `ignore`→`test` (now green).
- DEFERRED: `derives` clauses mixing user + stdlib typeclasses (left untouched); stdlib structural
  derives (A1c); JS (A2). The JS case of the cross-backend test stays `ignore`d.

## 2026-06-17 — feat(meta-v2): Track A1a — JVM `summon[Mirror.Of[T]]` conformance

First build slice of `arch-metaprogramming-v2` §4b Track A (cross-backend `derives`/`Mirror`
conformance). Investigation this session verified `derives` was **interpreter-only** on the generated
backends; A1a brings the public `Mirror` metadata surface to the JVM backend.
- **JvmGen** now emits a phantom-typed `_SscMirror[A]` runtime class + `object Mirror` (`Of`/`ProductOf`/
  `SumOf` aliases) + a bare `type Mirror` into the preamble (gated on the module referencing `Mirror`),
  and appends one `given _SscMirror[T]` per top-level non-generic case class after the user blocks. So
  `summon[Mirror.Of[Person]]` resolves on the JVM with `label`/`elemLabels`/`elemTypes`/`isProduct`/
  `fromProduct`, matching the interpreter's `DerivesRuntime` metadata.
- `MirrorOfJvmConformanceTest` — interpreter baseline + scala-cli JVM run, identical output.
- DEFERRED follow-ups: sum-type mirrors (enum / sealed trait), generic case classes, custom `derives`
  synthesis (A1b), stdlib structural `derives` (A1c), and the JS equivalents (A2). The cross-backend
  `CustomDerivesMirrorCrossBackendTest` JVM/JS cases stay `ignore`d until A1b/A2.

## 2026-06-17 — feat: agent-sdk-remainder — consolidated spec + MCP bridge (both directions)

Closed out the generic LLM-agent SDK's remaining actionable scope (P0–P2 shipped earlier).
- **Consolidated `specs/agent-sdk.md`** — single authoritative scalascript-side SDK spec mirroring
  rozum's `agent-sdk.md` + `integration.md` (the 3 contracts), superseding the 3 `rozum-agent-*` slice specs.
- **P3a MCP bridge** — `runtime/std/agent-mcp.ssc`, both directions (the rozum "one tool definition,
  two consumers"): `serveAgentToolsMcp(tools, transport)` exposes an app's `AgentTool`s over `mcpServer`;
  `mcpToolSource(client)` wraps an MCP server's tools as `AgentTool`s. `Map`↔JSON via `jsonStringify`
  + the existing `jsonParse` intrinsic; the two `ToolResult` types never meet by name (built via
  `Tool.text/error` and `toolOk/toolError`). Examples `agent-mcp-{server,toolsource}.ssc`; module +
  both examples `ssc check` OK.
- **Loop conformance** already covered by `AgentSdkInterpreterTest` (fake gateway + canned
  `stop`/`tool_calls`). DEFERRED with reasons: bridge round-trip test (heavy jvm/js infra for thin
  glue), golden transcripts, P3b embedded transport (blocked on rozum's `rozum-embed` crate).

## 2026-06-17 — refactor: payments-reorg — unify crypto/wallet/payments under `payments/`

All payment-domain interpreter plugins moved out of the flat `runtime/std/` sprawl into the
`payments/` tree (joining the already-nested `payments/{blockchain,wallet,x402,money,…}` libs).
Hybrid layering: plugin-only families → `payments/processors/{spi,stripe,paypal,sepa,ach,swift,
fednow,pix,…}` (21 providers + the `PaymentProvider` SPI); wrapper plugins → next to their lib
(`payments/crypto/plugin`, `payments/payment-request/plugin`). Build-config-only: `git mv`
(history preserved) + `.in(file(...))` paths; Scala packages, `META-INF/services`, sbt val names,
the root aggregate, and the CLI `PluginSpec` list all unchanged → **user `.ssc` code untouched**.
Done incrementally (5 slices, each compiled + pushed); `sepa` 71 / `stripe` 23 / `crypto` 58 tests
green from the new locations; `cli/installBin` stages all plugins; no payment dirs remain in
`runtime/std/`. spec `specs/payments-reorg.md`.

## 2026-06-17 — perf(js): array-update residual — `.toInt`→`(x|0)`, `.toLong`→identity

Closed the last collection perf outlier. JS `array-update` was 17.6 ms (vs vector-index 4.99) because
`s` exceeds V8's SMI range, so `s%16`/`s%100` are *doubles* — indexing/filling a JS array with doubles
drops it off the fast SMI-packed path (node `--prof` bisect: `(x|0)` 6.9 ms vs `Math.trunc` 16.4 ms).
Emitting `(x|0)` for `.toInt` on an integer receiver forces a V8 int32 (array stays PACKED_SMI) **and**
matches Scala's 32-bit `Int`/`Long.toInt` wrap (which `Math.trunc` did not — JS now agrees with interp
for `Long.toInt > 2^31`). `.toLong` on an integer receiver is now identity. array-update 17.6→4.99 ms
(~5× over the session), lazylist-take 1.11→0.556; interp == node (9906427). Also: `hof-glue-jit-compile`
re-confirmed DEFER (`foldLeftReusing` already JITs the loop+combine; the residual is `combineAll`'s
`using`/`summon` glue, which needs whole-function JIT — too large/risky for the sub-15% ceiling).

## 2026-06-16 — feat: cross-backend collection perf — Rust Array, JVM + JS LazyList fusion, JS native seq ops

Closed each backend's remaining weak spot on the collection workloads (dashboard
`./bench.sh vector-index array-update lazylist-take`), all results verified equal to interp:

- **Rust**: `array-update` was `n/a` (the corpus wrongly assumed Rust has no mutable Array — it has
  `Vec<i64>`). A per-def pre-pass tracks local `val a = Array/Vector/List(…)`; `Array(…)`→`let mut
  vec![…]`, `a(i)`→`a[(i) as usize]`, `a(i)=x` store. array-update 0.681 ms/iter, value-correct.
- **JVM**: `LazyList.from(s).map(f)?.take(n).sum` fused (parse→splice in JvmGen) into a native `while`
  loop in the emitted Scala — `lazylist-take` 5.87 → 0.052 ms (~113×).
- **JS**: hot collection/numeric ops now emit native JS instead of the megamorphic
  `_call`/`_dispatch`/`_arith` helpers — `.toInt/.toLong`→`Math.trunc`, `seq(idx)`→`v[idx]`, and the
  LazyList pipeline→a native-loop IIFE. vector-index 17.2→4.96, array-update 24.8→17.6,
  lazylist-take 8.92→1.11 ms/iter.

## 2026-06-16 — feat: jit-collection-ops slice 2 — Array update + ASM parity + LazyList fusion

Finished the collection JIT (array / vector / lazy list) on the interpreter's bytecode JIT.
Three workloads that previously tree-walked now JIT on **both** backends, with the JIT-on result
identical to JIT-off (verified via the assembled jar):

- **Array** read + in-place update (`a(i)` / `a(i)=x`): `array-update` 1580 → 0.66 ms (~2400×).
  A local `val a = Array(...)` is tracked statically via `GenCtx.seqLocals` (a local can't be
  classified by runtime type like a global); `JitRefDispatch.buildArrayRef` + `arrayUpdateLong`.
- **ASM-backend parity** for `seq(i)` + `array(i)=x`: the `ssc-asm` column drops too
  (`vector-index` 1003 → 0.87 ms, `array-update` 1473 → 0.69 ms). The real reason slice 1's ASM
  path looked "inert" was the shared `JitPredicates.looksLongValue` not recognising `seq(idx)` as
  a Long (ASM shape-gates `.toLong` before emitting; Javac try-walks) — fixed with
  `JitShapeCtx.isSeqIndexName`, not reverted.
- **LazyList** pipeline fusion: `LazyList.from(s).map(f)?.take(n).sum` fuses into a native loop
  (no lazy cons/thunk alloc), `lazylist-take` 190 → 0.058 ms (~3275×). The ~35× gap was the
  LazyList machinery, not the arithmetic — so it was tractable, not "inherent cost".

Spec: `specs/jit-collection-ops.md` (all slices DONE).

## 2026-06-15 — feat: real `VectorV` — distinct indexed type with O(1) access

Follow-up to the collection-real-type work: a List-backed `Vector` indexes in O(n), which isn't
"really using" Vector (its whole point is fast indexed access). `Vector`/`IndexedSeq` are now a
distinct `Value.VectorV` backed by a real Scala `Vector[Value]`, so `vec(i)` / `.updated` are
O(log₃₂ n). This also **replaces the `collKind` display-tag hack** (List/Seq/Iterable = `ListV`
displaying `List`; Vector/IndexedSeq = `VectorV` displaying `Vector`) — a net simplification.
`Vector == List` stays `true` (cross-`Seq` equality) via the `==` dispatch path, so `ListV.equals`
is untouched (no blast radius on the hottest value type). `toVector`/`toIndexedSeq` → `VectorV`
everywhere; `toArray` → `ArrayV`. PatternRuntime iterates `VectorV`/`ArrayV` in for-comprehensions
and matches their type-tests. Verified output-for-output vs real Scala; full interp suite
1816/1816; interp==JS 74 programs; cross-backend collection guards green.
`CollectionRealTypeTest` +8 Vector cases. (collection-vector-indexed.)

## 2026-06-15 — feat: interpreter uses REAL Scala collection semantics (Array mutable, LazyList lazy)

Follow-up to the constructors above: the user asked that the interpreter not just *display* the
real collection type but *really use* it. Analysis (agreed): in an **eager** interpreter
`List`/`Seq`/`Vector`/`IndexedSeq`/`Iterable` are observably identical (`Vector(1,2,3) == List(1,2,3)`
is `true` in Scala too) — only **display** differs, handled by a `ListV.collKind` tag preserved
through type-preserving ops. The only two types with genuinely different runtime semantics now have
real backing:
- **`Value.ArrayV(Array[Value])`** — mutable (`a(i) = x` / `a.update(i,x)` mutate in place;
  `EvalRuntime` lowers `recv(idx…) = rhs` to `recv.update`), reference identity
  (`Array(1,2,3) != Array(1,2,3)`), `.map`/etc. return a fresh mutable Array.
- **`Value.LazyListV(LazyList[Value])`** — backed by Scala's own `LazyList`, so laziness, infinite
  streams, memoization, and `toString` parity with JVM (`LazyList(<not computed>)`) come for free;
  `#::` is special-cased to defer its RHS so `def from(n) = n #:: from(n+1)` is an infinite stream.

Verified output-for-output against real Scala / the JVM backend. JS gains a Vector/IndexedSeq
display tag (`_seqKind`) and mutable-Array indexed assignment for cross-backend parity; JS Array
reference-identity and LazyList laziness stay interp-vs-JVM (JS arrays are eager + structural).
Guards: `CollectionRealTypeTest` (19 interp cases) + `CrossBackendPropertyTest` "real collection
type". spec: `specs/collection-real-type.md`.

## 2026-06-15 — feat: Seq/Vector/Array/IndexedSeq/Iterable/LazyList constructors + toX conversions

Despite the user guide listing `Seq`/`Vector`/`Array`/`Set`, the interpreter only had
`List`/`Map`/`Set` companions — `Seq(1,2,3)`, `Vector(...)`, `Array(...)`, `IndexedSeq(...)`
threw `Undefined: Seq`. Added them (+ `LazyList`): the interpreter backs every sequence type
with a single `ListV` (JS with arrays), so these companions alias `List`'s (`BuiltinsRuntime`),
JsGen emits the constructors as arrays, and `toList`/`toSeq`/`toVector`/`toIndexedSeq`/`toArray`/
`toIterable` are identity conversions (interp + JS, List + Map). On the JVM backend each stays
its REAL Scala type (raw emit), guarded by a JvmGen-preserves-type assertion. Caveat: off-JVM
they're not distinct runtime types (Vector/Array = List, LazyList is eager). Guard:
`CrossBackendPropertyTest` "Seq/Vector/Array constructors + conversions cross-backend".

## 2026-06-15 — chore: run scala-cli serverless (`--server=false`) to avoid Bloop contention

The cross-backend test harness invoked `scala-cli run` without `--server=false`, so concurrent
runs shared one Bloop BSP daemon and collided on its socket/port (`BindException: Address
already in use` / `TimeoutException`), intermittently failing JVM cross-backend assertions. Added
`--server=false` to the harness and the one remaining bench runner that lacked it (the rest of the
`ssc` CLI already passes it). With serverless compiles there's no Bloop daemon at all — no idle
ZGC churn and no port contention. (Companion local change: the `scala-cli()` shell wrapper now
appends `--server=false` for build subcommands instead of pre-starting a GC-tuned Bloop daemon.)

---

## 2026-06-15 — fix(interp): String.map returns a Seq when the element fn yields a non-Char

`"abc".map(c => c.toInt).sum` threw in the interpreter (`No method 'sum'`) — interp's
`String.map` always rebuilt a String, but Scala's `String.map(f)` is a `String` only when
`f` yields a `Char`; otherwise a `Seq[B]`. Fixed: `strMapResult` returns a `String` when
every mapped element is a `CharV` (incl. empty), else a `List` — so `"abc".map(_.toInt)` →
`List(97, 98, 99)` (matches JVM = 294), while char-to-char maps stay Strings. Guarded by a
new "String.map char vs non-char cross-backend" test (interp == JVM for the non-Char case;
char-map agrees on all three). 155 interp / cross-backend tests green. The JS side stays open
(BUGS.md interp-js-string-map-nonchar): JS has no distinct Char type — chars are char-code
numbers, so `_.toInt` (int) and `_.toUpper` (char) are indistinguishable at runtime; a correct
JS fix needs a real Char wrapper (larger change, deferred).

---

## 2026-06-15 — fix: stepped Ranges (`by`) + collection/String dispatch gaps (cross-backend)

Fixed the open `xbackend-range-by-step` plus a cluster of stdlib dispatch gaps found by a
wave-7 property probe. `(0 to 10 by 2)` threw on interp + JS (a materialized range has no
`by`, and the JS `by` infix emitted invalid `(range by step)`); `by(step)` now keeps every
step-th element — added to interp `dispatchList`/`dispatchList1`, the JS array `_dispatch`,
and JsGen emits `by` as `_dispatch(range, 'by', [step])`. Also added the missing methods that
failed with `Method not found` / `No method`: JS `List.scanLeft`(curried)/`scanRight`/
`indexWhere`, tuple `.swap`, `String.padTo` (Char arg = char-code number), and interp
`indexWhere`. interp/JS/JVM now agree. Found by `CrossBackendPropertyTest`; guarded by a new
"ranges, collection + string method gaps cross-backend" test (8 shapes). 160 cross-backend /
interp / JS tests green. (Filed open: interp-js-string-map-nonchar — `"abc".map(_.toInt)`
should yield a `Seq[Int]`, but interp/JS `String.map` rebuild a String.)

---

## 2026-06-15 — fix: enum-payload match, partial-fn collect, Option.fold (cross-backend)

A wave-6 property probe (enums / collections / Option) found and fixed three cross-backend
bugs: (1) **jsgen-enum-payload-extract** — matching an enum case WITH a payload bound the
`_tag` slot instead of the field (`case Circle(r)` gave `r = 0`/`1`), because
`caseClassFieldsByType` (used for field-name accessors) indexed only `Defn.Class`, not enum
cases, so the positional `Object.values(scrut).slice(1)[i]` fallback skipped only `_type`, not
`_tag`. Now indexes enum cases too. (2) **collect-partial** — `xs.collect { case x if … => … }`
threw `Match failure` in the interpreter (called the PF as total) and was absent from the JS
array dispatch; both now SKIP elements the PF isn't defined on. (3) **jsgen-option-fold-curried** —
curried `Option.fold(ifEmpty)(f)` was missing from the JS Option dispatch; added it (plus
`exists`/`forall`, structural `Some.contains`). interp/JS/JVM now agree. Found by
`CrossBackendPropertyTest`; guarded by a new "enum payload, collect, Option.fold cross-backend"
test. 159 cross-backend / interp / JS-codegen tests green. (Filed open: xbackend-range-by-step —
`(0 to 10 by 2)` stepped Ranges work on JVM but not interp/JS.)

---

## 2026-06-15 — fix: 4 cross-backend bugs (case-class eq, Set, num+String, JVM auto-output)

A wave-5 property probe (Map/Set, case-class equality, numerics) found four cross-backend
divergences, each fixed: (1) **jvmgen-autooutput-after-classdef** — a top-level `case class`
followed by an auto-output block printed NOTHING; the bare `{ … }` block was parsed as the
class body. `wrapAutoOutput` now emits `locally { … }`. (2) **jsgen-structural-equality** —
JS `==` used reference equality, so `P(1) == P(1)` was false; added a deep `_eq` runtime helper
and routed `_arith('=='/'!=')` through it (also used for Set dedup). (3) **jsgen-set-constructor** —
`Set(...)` hit the JS global `Set` (`requires new`); added a `Set(...)` → `_setOf(...)` case
(deduplicated array, so array `_dispatch` applies). (4) **interp-num-string-concat** — `6 + "_"`
(`any2stringadd`) threw in the interpreter; `dispatchInt`/`dispatchInt1` now concatenate a number
with a String operand. interp/JS/JVM now agree on all four. Found by `CrossBackendPropertyTest`;
guarded by a new "collections, case-class equality, num+string cross-backend" test. 234
cross-backend / interp / codegen tests green (incl. InterpreterTest, both NumericConformance
suites, ConstFold JVM+JS, ExamplesSmokeTest — confirming the core `locally` auto-output and JS
`==` changes don't regress).

---

## 2026-06-15 — fix(interp): monad-polymorphic for-comprehension (Option / Either)

A `for`-comprehension over a non-`List` monad threw in the interpreter while JS + JVM
were correct: `for x <- Some(3); y <- Some(4) yield x + y` → `No method 'getOrElse' on
List`, `for x <- Right(3); … ` → `Cannot iterate over Right(3)`. `PatternRuntime.evalForYield`
was List-specialized — it flattened an Option to a 0/1-element list and always returned a
`ListV`, so the result was a `List` rather than `Some`/`Right`. Fixed by dispatching
`flatMap`/`map` on the generator's actual value (via `DispatchRuntime.dispatch1` + a
`NativeFnV` closure) whenever it isn't a `ListV` — the same desugar the codegen backends
emit. `List` keeps its allocation-light fast path; guards / refutable patterns over a
non-List monad fall through unchanged. Found by `CrossBackendPropertyTest`; guarded by a
new "monadic for-comprehension cross-backend" test (option some/none, either right/left,
single-generator, + a List regression — interp == JS == JVM). 266 interp / cross-backend
tests green (incl. InterpreterTest, ParsingCombinatorsTest, FreeMonadTest).

---

## 2026-06-15 — fix(js): collection dispatch gaps + match case guards

A wave-4 cross-backend property probe (collection HOFs / pattern matching) found two
JS-only bugs (interp + JVM correct). (1) `sortWith` / `sorted` / `partition` / `span`
were missing from the JS `_dispatch` array-method table — added them (`partition`/`span`
return a `[yes, no]` tuple, which `val (a, b) = …` already destructures). (2) JS `match`
codegen DROPPED case guards (`case x if x < 0`): `genMatchAsStmts` and the coroutine match
built arm conditions from the pattern only, so a guarded arm looked like a catch-all
mid-chain and produced malformed `} else if` JS (node syntax error). Fixed all three JS
match paths to fold the guard into the arm condition via an IIFE that scopes the pattern
bindings (`(cond) && (() => { <binds>; return (<guard>); })()`); the switch fast-path
naturally excludes guarded arms. Found by `CrossBackendPropertyTest`; guarded by a new
"collection HOFs and pattern matching cross-backend" test (6 shapes, interp == JS == JVM).
49 JS-codegen / cross-backend tests green. (Open follow-up, filed: interp-monadic-forcomp —
a `for`-comprehension over Option/Either throws in the interpreter; JS + JVM are correct.)

---

## 2026-06-15 — fix(js): supertype type-tests match subtype instances — cross-module

Follow-up to the single-module fix below: the subtype closure now accumulates ACROSS the import
boundary. The JS backend emits each imported module with a fresh child `JsGen`, and a trait + its
subtypes routinely live in a different file than the `match` (busi: `TkNode` in a `package:`
`nodes.ssc`, `case h: TkNode` in `lower.ssc`), so the single-module closure left the real busi
symptom in place (the single-file test gave false confidence). `collectSubtypeEdgesFromModule`
(descends into `package:` wrapping objects) + `recomputeSubtypeClosure` are folded in for the entry
module and each imported module in genImport, propagated into the child gen (mirrors
`importedParamOrder`). Guard `SupertypeTypeTestXModuleJsTest` (multi-file).

## 2026-06-15 — fix(js): supertype type-tests match subtype instances

JS backend: a type-test against a supertype (sealed trait / parent enum / abstract class) —
`case h: TkNode` — never matched a subtype instance, because emitted objects carry only their
leaf `_type` and `genPattern`'s `Pat.Typed` tested an exact `_type === 'TkNode'`. Found by busi:
every `cardWithHeader` title was silently dropped in the SPA on all screens (the interpreter was
correct, so `.ssc` tests passed). Fix: `JsGen.subtypeClosureInModule` builds `supertype → concrete
descendant `_type`s` (transitive) per module; `Pat.Typed` widens a no-tag check to an `_type` OR
over that closure. Leaf-tag / primitive / destructuring (`Pat.Extract`) paths unchanged. The JS
analogue of the interpreter/JIT supertype-type-test fix (BUGS #1/#3). Guard
`SupertypeTypeTestJsTest`; spec `specs/js-supertype-typetest.md`; `BUGS.md#js-supertype-typetest`.

## 2026-06-15 — fix(jvm/js): effect perform inside a collection for-do loop

Completes effect-perform-in-fordo: an effect op performed inside a `for x <- coll do …`
loop (non-Range collection generator) diverged the same way the Range case did before
its fix — JVM compile error (`Int + _perform`), JS garbage; interp correct. Extended the
for-do CPS recognizers in both emitters (`JvmGenCpsTransform` + `JsGenCpsCodegen`) to also
handle a single plain-var generator over a pure non-Range collection, desugaring to the
while-trampoline via `.iterator` (JVM) / array-index (JS) so the body's `perform`s thread
through `_bind`. Multi-generator / guarded / complex-pattern for-do still falls through
unchanged. The "effect perform in for-do loop cross-backend" guard now covers 5 shapes
(range until/to/loop-var + collection elem/side-effect), interp == JS == JVM.

---

## 2026-06-15 — fix(jvm/js): effect perform inside a for-do loop (CPS desugar)

An effect op performed inside a `for i <- 0 until n do …` loop diverged across all
three backends — interp correct, JVM compile error (`Int + _perform`), JS garbage
(`acc + <Computation>`). The `while`-loop form works (dedicated CPS while-trampoline),
but the `for-do` → `foreach` desugar ran the body via non-CPS codegen so the effect
wasn't threaded. Fixed by adding a Range `for i <- (lo until/to hi) do body` recognizer
to BOTH CPS emitters (`JvmGenCpsTransform` + `JsGenCpsCodegen`) that desugars to an
index var + the same while-trampoline, threading the body's `perform`s through `_bind`.
Covers `until`/`to` and bodies reading the loop var; non-Range / multi-generator / guarded
for-do is unchanged. Found by `CrossBackendPropertyTest`; guarded by a new "effect perform
in for-do loop cross-backend" test (interp == JS == JVM). 65 effect/CPS tests green.

---

## 2026-06-15 — fix(js): partial application of curried defs (auto-curry)

Partial application of a curried def failed on the JS backend (`not callable: NaN`)
while interp + JVM were correct: `def add(a)(b) = a + b; val f = add(3); f(4)` — JsGen
flattens curried params to `function add(a, b)`, so `add(3)` runs the body with
`b === undefined` → `NaN`. Full application `add(1)(2)` was fine (it arrives flattened
as `add(1, 2)`); only under-applied calls broke. Fixed with a `_curry(fn, arity, args)`
JS runtime helper plus an auto-curry guard at the top of plain multi-clause def
emission (`if (arguments.length < N) return _curry(fname, N, arguments);`), emitted only
for multi-clause defs without defaults/using/context-bounds. Found by
`CrossBackendPropertyTest`; guarded by a new "curried partial application cross-backend"
test (2-/3-clause, full + partial, interp == JS == JVM) plus an "effects-in-hof and
main-path edge cases" test (perform-in-map/-foldLeft, closures-returning-closures, nested
for-yield, recursion, string interpolation). 95 cross-backend / JS-codegen tests green.
(Also filed BUGS.md effect-perform-in-fordo — effects inside a `for … do` loop diverge on
JS + JVM; tracked open.)

---

## 2026-06-15 — feat(std/ui): fetchActionTo — reactive-URL fetch action

`std/ui/primitives.fetchAction` takes a **static** `url: String`, so a path-id endpoint
(`POST /documents/<selectedId>/submit`) had to interpolate a signal at *render* time —
baking in the then-current (empty) selection → a static `…/documents//submit` that never
updates. New `fetchActionTo(method, urlSig: Signal[String], body, onSuccessTick, headers)`
resolves the URL from a signal at **click** time (the body already worked this way). On the
react/JS SPA backend `_ssc_ui_fetchActionTo` carries `urlSig`; `_ssc_ui_renderBody` collects
it (kept fresh by the computed→`_sv` bridge) + emits `data-ssc-fetch-url-sig`, and the click
handler resolves `_sv[urlSigId]`. INT/JVM are headless (no SPA click loop) so they snapshot
the urlSig's current value into a regular `EventHandler.FetchAction` — no change to the
shared `EventHandler` ADT or any frontend emitter. Guarded by `FetchActionToUrlTest` (real
`JsRuntimeSignals` headless: type → click → URL reflects the typed selection). Spec
`specs/fetch-action-to.md`. Found building busi `web/peer.ssc` doc Submit/Approve/Reject.

---

## 2026-06-15 — fix(jvmgen): Any-taint propagation for handle-result compositions

Closed the two deferred cross-backend bugs from the handle-result-mainpath cluster
by generalizing handle-result tracking into a lightweight Any-taint analysis.
`List(r, r).sum` (`No given Numeric[Any]`) is fixed by broadening the `emitExprDeep`
`_anyCall0` Select routing from "qual IS a handle-result-val" to "qual REFERENCES
one". Tuple-accessor arithmetic `val t = (r, r+1); t._1 + t._2` is fixed by adding
`anyTypedVals` — a superset of `handleResultVals` populated by propagation: an
untyped val whose rhs references an Any-typed val is itself Any-typed. The routing
predicates key off `anyTypedVals`, and the arith-operand check recognizes
`Select(anyTypedVal, _)` so `t._1 + t._2` lowers to `_binOp`. `anyTypedVals` is only
ever non-empty for effect programs (seeded by `handleResultVals`), so pure code is
unaffected. The composition guard test gained `result-in-list-sum` + `result-in-tuple`
(interp == JS == JVM); 331 effect/JVM-codegen/VM tests green. Completes BUGS.md
jvmgen-handle-result-mainpath — the property-test hunt's handle-result vein is now clean.

---

## 2026-06-15 — fix(jvmgen): handle-result val in main-path contexts (match / if / fn-arg)

Continued the cross-backend property-test hunt into effect-result × main-path
compositions and found a cluster of JVM-only bugs (interp + JS ran them): a
`val r = handle(...)` (Any-typed `_handle` result) used in a non-arithmetic
main-path context. Fixed three: `r match { case _ => r * 2 }` (added a `Term.Match`
case to `emitExprDeep` that recurses scrutinee/arms/guards), `if r > 5 then …` (cast
the Any-typed `_binOp` condition to Boolean), and `dbl(r)` (cast main-path call args
that reference a handle-result val to the callee's `calleeParamType`, reusing the
CPS `localDefSigs`/`depDefs` index). Routed any handle-result-referencing term
through `emitExprDeep` via `termRefsHandleResultVal` in `termNeedsCustomEmit`; also
added a `Term.Tuple` recursion case. Guard: `CrossBackendPropertyTest` "effect-result
main-path composition cross-backend" (match / if-cmp / fn-arg / multishot-arith /
nested-handles — interp == JS == JVM); 146 effect/JVM-codegen tests green. Two rarer
contexts deferred (need Any-type propagation): `List(r, r).sum` (Numeric[Any]) and
tuple-accessor arithmetic `t._1 + t._2` — filed open in BUGS.md
jvmgen-handle-result-mainpath.

---

## 2026-06-15 — fix(js/spa): bridge un-displayed computedSignals into the hydration store

A `computedSignal` read ONLY at event time — e.g. a `fetchAction` body that interpolates field
signals, `fetchAction("POST", url, computedSignal(() => "{...:" + fieldSig() + "...}"), tick)` —
POSTed its **load-time** value, ignoring typed input. The SPA click handler reads the body from the
hydration/bridge store `_sv[bodyId]`, not the live reactive `_signals` graph. Field inputs refresh
`_sv` via `_set`, and `_syncBridgeSignals` bridges a signal's reactive value back into `_sv` only for
ids that were `_sub`'d. A computed displayed by `showSignal`/`signalText` gets `_sub`'d and stays
fresh, but one read solely by a `fetchAction`/header never was — so its `_sv` value froze at the
seed while the reactive graph (correctly) recomputed it. Fix: `_ssc_ui_mount` now `_sub`s every
collected computed signal, so `_syncBridgeSignals` keeps its `_sv` entry tracking the reactive graph
— covering bodies, headers, and any event-time computed read uniformly. Found building busi
(`fetchAction` write forms — money/sales/ukraine/access all affected; single-instance, not a
multi-instance issue). Guarded by `SpaComputedBodyBridgeTest` (runs the real `JsRuntimeSignals`
headless with document/fetch shims: type → click → assert the POST body reflects the input). Full
conformance unchanged for this JS-runtime-only change; 48 JS/SPA-path tests green.

---

## 2026-06-15 — feat(std): derive rozum agent tool schemas

Added `AgentSchema[A] derives` and `agentToolFor[A]` to `std.agent` so tool
inputs can be modeled as typed case classes while still emitting
OpenAI-compatible JSON Schema parameters. Explicit `agentTool(...,
parametersJson)` remains the authoritative fallback for custom/unsupported
schemas. Added `examples/rozum-agent-schema-derived.ssc`, README/User Guide/spec
docs, and `AgentSchemaDerivationInterpreterTest`. Verified with
`sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSchemaDerivationInterpreterTest scalascript.AgentSdkInterpreterTest scalascript.AgentSdkStreamingInterpreterTest scalascript.AgentEndpointPoolInterpreterTest"` (25 tests passed).

---

## 2026-06-15 — fix(interp): bail fast-while to trampoline on unresolved effect in loop

A deep return-clause handler over a program that performs an effect inside a
`while` loop threw in the interpreter (`Unhandled effect: Log.emit (no handler in
scope)`) — JS and JVM both ran it correctly. The handler body `7 :: resume(())` is
not a clean tail-resume, so no inline resolver is installed; the effect op must
thread as a `Computation` through the handler, but the fast-while path
(`tryFastWhileAssign`) ran the loop's leading applies eagerly via `Computation.run`,
letting the `Perform` escape. Fixed by capturing `EffectAnalysis.effectOps` into
`Interpreter.effectOpNames` and adding an up-front guard: if the loop body performs
an effect op with no active resolver (`EffectsRuntime.lookupResolver == null`), bail
to the monadic trampoline (which threads effects via `FlatMap`). The one-shot
tail-resume fast path keeps a live resolver, so it's preserved with no perf
regression. Found by `CrossBackendPropertyTest`; the new "effect return-clause
cross-backend (… / while)" guard now runs the while shape interp == JS == JVM, and
the generated JVM differential rose 17 → 19 checked seeds (formerly-skipped
return-clause seeds 23/59 now produce an interp baseline). 366 effect/JIT/VM tests
green. (BUGS.md interp-returnclause-effect-in-while — closes the last of the three
cross-backend bugs found this property-test hunt.)

---

## 2026-06-15 — fix(jvmgen): cast CPS call args to user-module callee param types (effectful recursion)

A return-clause handler over a **recursive** effectful function failed JVM
scala-cli compilation (`Found: (_t3 : Any) / Required: Int`): the CPS transform
emits `def go(n: Int): Any = _bind(..., (_t3: Any) => go(_t3))`, and `applyCalleeCasts`
(which casts Any-bound CPS args to the callee's declared param types) only
consulted imported deps (`depDefs`/`depClasses`), never the user module's own
defs — so the recursive call got no cast. interp + JS ran it fine. Fixed by adding
`localDefSigs`, a pre-pass index of the module's own `Defn.Def`s, consulted as a
fallback in `applyCalleeCasts`/`calleeParamType`/`calleeTypeArgMap`; `go(_t3)` now
emits `go(_t3.asInstanceOf[Int])`. Found by `CrossBackendPropertyTest`; guarded by
a new deterministic "effect return-clause cross-backend (direct / recursion)" test
(interp == JS == JVM). 120 effect/CPS unit tests green. (BUGS.md
jvmgen-returnclause-effect-in-recursion.) Remaining open: interp-returnclause-effect-in-while.

---

## 2026-06-15 — feat(std): add rozum endpoint pool failover

Added `AgentEndpointPool`, `runAgentPool`, `runAgentStreamPool`, and
`collectAgentStreamPool` to `std.agent`. Pool calls preserve the existing
single-endpoint API while adding bounded ordered failover across multiple
OpenAI-compatible rozum gateways for transport failures and HTTP `5xx`; `4xx`,
unknown tools, and handler validation errors do not retry another endpoint.
Added `examples/rozum-agent-pool.ssc`, README/User Guide/spec docs, and
`AgentEndpointPoolInterpreterTest`. Verified with
`sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkInterpreterTest scalascript.AgentSdkStreamingInterpreterTest scalascript.AgentEndpointPoolInterpreterTest"` (21 tests passed).
---

## 2026-06-15 — test(std): avoid agent streaming port collision

Fixed a test-order flake where `examples/rozum-agent.ssc` and
`AgentSdkStreamingInterpreterTest` both bound port `19694`; the streaming suite
now uses `19698`. Verified the formerly failing order with
`sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkInterpreterTest scalascript.AgentSdkStreamingInterpreterTest"` (14 tests passed).

---

## 2026-06-15 — fix(jvmgen): handle-result val in main-path arithmetic; broaden xbackend property test

Broadened `CrossBackendPropertyTest` to 12 program kinds (added closures/HOF,
nested `List[List[Int]]`, richer String ops) plus two effect-composition shapes
that feed a `handle(...)` result into main-path arithmetic (`r * 2 + base`,
`r1 + r2`). The latter immediately surfaced **jvmgen-handle-result-arith**: a
`val` bound to `handle(...)` (Any-typed `_handle` result) used as an arithmetic
operand emitted `r * 2` raw, which scala-cli rejects (`value * is not a member of
Any`); interp + JS ran it fine. Fixed by adding `termContainsHandleResultArith`
(detects a handle-result-val used as an arith/comparison `ApplyInfix` operand) to
`termNeedsCustomEmit`, routing the term through `emitExprDeep` whose existing
`ApplyInfix → _binOp` lowering handles it. Verified: `CrossBackendPropertyTest`
green (interp == JS over 74 seeds, interp == JVM over 19 — sub-shapes 8/9 run
through scala-cli). The same hunt also found two further cross-backend bugs filed
open in BUGS.md (interp-returnclause-effect-in-while, jvmgen-returnclause-effect-in-recursion).

---

## 2026-06-15 — feat(std): stream rozum agent events

Added `runAgentStream`, `collectAgentStream`, `AgentEvent`, and
`AgentStreamResult` to `std.agent`. The streaming loop sends OpenAI-compatible
`stream=true` requests with `Accept: text/event-stream`, emits ordered text,
tool-call, tool-result, error, and stopped events, assembles chunked tool-call
arguments, dispatches local handlers, and preserves the existing sync `runAgent`
surface. Added `examples/rozum-agent-streaming.ssc`, README/User Guide/spec docs,
and interpreter-plugin coverage for callbacks, collection, errors, max steps, the
streaming example, and the P0 sync regression. Verified with
`sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkStreamingInterpreterTest scalascript.AgentSdkInterpreterTest"`.

---

## 2026-06-15 — test(std): add rozum live conformance smoke

Added `RozumLiveConformanceTest`, an opt-in interpreter-plugin test for
`std.agent` against a real OpenAI-compatible rozum gateway. It is gated by
`ROZUM_BASE_URL` + `ROZUM_MODEL` with optional `ROZUM_AUTH_TOKEN`, cancels cleanly
when env is absent, and documents the live command in
`specs/rozum-live-conformance.md`. Verified with
`sbt "backendInterpreterPluginTests/testOnly scalascript.RozumLiveConformanceTest"`;
local no-env path canceled as expected. An attempted adjacent `rozum gateway
--model hello` smoke built the Rust binary but current rozum rejected `hello` as
no backend, so true live execution requires a real cached model or
`ROZUM_BACKEND_URL` upstream.
---

## 2026-06-15 — feat(std): add rozum agent SDK P0

Added `runtime/std/agent.ssc`, a generic app-owned agent loop for stateless
OpenAI-compatible rozum gateways. The P0 surface includes `AgentEndpoint`,
`RunOptions`, `AgentTool`, `ToolResult`, `ExecutedOp`, `AgentResult`, `runAgent`,
and JSON-schema helpers; it uses existing `std.http` + `std.json` and adds no
new intrinsic plugin. Added `examples/rozum-agent.ssc`, README/spec docs, and
`AgentSdkInterpreterTest` covering request shape, bearer auth, direct final text,
tool-call dispatch, unknown-tool feedback, handler validation feedback, max-step
stop, non-2xx errors, and the self-contained example.
---

## 2026-06-15 — feat(nfc): add native requirement contract

Continued `std.nfc` by adding frontend `Capability.NfcNdef` / `NfcTagTech` / `NfcCardEmulation`, a `NativePlatformRequirements` resolver for iOS Info.plist + NFC reader-session entitlements, Android NFC permissions/features, and Web NFC permission-model requirements, plus `.scim` capability detection for NFC calls. Verified with `sbt "frontendCore/testOnly scalascript.frontend.NativePlatformRequirementsTest scalascript.frontend.FrontendFrameworksTest"` and `sbt "core/testOnly scalascript.artifact.InterfaceExtractorTest"`. Real packager consumption and hardware adapters are deferred in `BACKLOG.md` as `std-nfc-packager-adapters`.
---

## 2026-06-15 — feat(std): add portable `std.nfc` NDEF support

Added the `std.nfc` NDEF API surface, `Feature.NfcNdef` capability gating, deferred tag-tech/card-emulation feature flags, an interpreter plugin with deterministic unsupported-backend behavior, portable text/URI/MIME record constructors, capability-check coverage, docs, and `examples/nfc-ndef.ssc`. Verified with `sbt "nfcPlugin/test"`, `sbt "core/testOnly scalascript.validate.CapabilityCheckTest"`, `sbt "installBin"`, and `bin/ssc run examples/nfc-ndef.ssc`.
---

## 2026-06-15 — fix(interpreter): TCO trampoline fills default args on short tail/mutual calls

A tail/mutual call may supply fewer args than the callee declares, relying on default params
(`def g(x, y = 10)` tail-called as `g(5)`). `callFun`'s normal entry runs `applyDefaults`, but the
trampoline's tail-dispatch paths (self `TailCall`, `MutualTailCall`, resume re-entries) bound
`curArgs` raw — so the callEnv builder read `curArgs(1)` off a 1-element list and threw
`IndexOutOfBoundsException` (2 params) / `ArrayIndexOutOfBoundsException` (3+ params). Found via
`std.ui.content.contentView(doc)` (2 params, `options` defaulted) tail-called from a per-locale
content selector in busi. Fix: `TcoRuntime.tcoTrampoline` now fills missing trailing args from
their defaults (`bindShortCall` → `CallRuntime.applyDefaults`) in the callEnv builder — the single
chokepoint all dispatch paths funnel through. The full-arity hot path stays allocation-free
(cons-pattern checks, no `applyDefaults`); only a short call pays. Guarded by
`TailCallDefaultArgsTest` (2-param mutual, self-tail short, 3-param two-defaults). Full conformance
suite (127 fixtures) byte-identical vs baseline; 152 interpreter+content+TCO tests green.
---

## 2026-06-15 — test(xbackend): return-clause deep-handler effect shape (coverage; no bug)

Keep-hunting capstone: added a return-clause deep-accumulation handler shape to CrossBackendPropertyTest (`case Log.emit(resume) => v :: resume(())` + `case Return(_) => List()`, printed via `xs.length`/`.sum`). interp==JS==JVM all green — return-clause codegen holds. After FOUR effect-codegen bugs found+fixed this session, this clean no-bug hunt indicates the effect-handler emitter is now solid; the property test covers 8 program kinds + 8 effect handler shapes cross-backend.
---

## 2026-06-15 — fix(jvmgen): dynamic-dispatch collection methods on the Any-typed handle result

jvmgen-multishot-handle-result-any (4th & hardest property-test find). `_handle` returns Any, so `val all = handle(prog()){…}; all.sum` (multi-shot result is a List) failed JVM ('value sum is not a member of Any'). Fix (mirrors `_anyFlatMap`): runtime `_anyCall0(recv, m)` dynamically dispatches sum/length/min/max/… on an Any Iterable (numeric folds via `_binOp`); codegen tracks `val x = handle(..)` and routes `x.method` through `emitExprDeep` → `_anyCall0`. 96 tests green. All FOUR JVM codegen bugs found via the generated cross-backend property test this session are now fixed.
---

## 2026-06-15 — fix(jvmgen): systemic — emitCaseBody recurses all composite handler-body terms

Killed the recurring JVM effect-handler-codegen bug class at the root. `emitCaseBody` (the mini-emitter for handler case bodies over Any-typed op-args/resume results) had a `.syntax` raw fallback that emitted any un-special-cased composite RAW — so a composite containing Any-typed arithmetic/comparison/flatMap broke JVM compile (the three earlier bugs were all instances). Now emitCaseBody recurses EVERY composite (added Term.Match / Tuple / Ascribe / Select / general ApplyInfix), so the fallback only reaches atoms (Lit/Name). Property test gained match-in-handler + block-handler shapes; 96 tests green, no regression. Surfaced + filed a separate harder bug: jvmgen-multishot-handle-result-any (a method call on the Any-typed `handle(...)` result). FOUR JVM codegen bugs found via the property test this session; three classes fixed.
---

## 2026-06-15 — fix(jvmgen): lower an `if` in an effect-handler body (Any-typed condition)

Third JVM codegen bug found by CrossBackendPropertyTest (control-flow case): a handler `case Reader.ask(k, resume) => if k > 2 then resume(k) else resume(0)` failed scala-cli because `emitCaseBody` had no `Term.If` case -> `k > 2` emitted raw on `Any`. Fix: added a `Term.If` case that recurses (lowers `k > 2` to `_binOp`) and casts the condition to `Boolean`. Property test gained a conditional-resume effect shape; 96 tests green. THREE real JVM codegen bugs found + fixed this session via the generated cross-backend differential -- all in the effect-handler emitter (emitCaseBody / emitExpr).
---

## 2026-06-15 — fix(jvmgen): lower arithmetic on Any-typed effect-handler op-args

Second JVM codegen bug found by `CrossBackendPropertyTest` (jvmgen-effect-handler-arg-arith): a handler
doing arithmetic on op-args — `case Combine.mix(a, b, resume) => resume(a * b + 1)` — failed scala-cli
("value * is not a member of Any") because op-args are bound as `Any` and `emitCaseBody` had no
arithmetic case, so `a * b` emitted raw. Fix: `emitCaseBody` lowers arithmetic/comparison `ApplyInfix`
to the `_binOp("op", l, r)` runtime helper (as `emitExpr` already does). Also broadened the property
test's effect kind to 4 shapes (one-shot / arg-carrying / two-op / multi-shot) run across interp/JS/JVM.
101 tests green. Two real JVM codegen bugs found + fixed via the generated cross-backend differential.
---

## 2026-06-15 — fix(jvmgen): lower a handle/effect nested in a call argument

The JVM codegen bug that `CrossBackendPropertyTest` found (jvmgen-handle-in-arg-position): `handle(...)`
(or any effectful expr) nested in a call argument — `println(handle(...))` — emitted RAW → scala-cli
"Not found: handle". `emitExpr` routes to `emitExprDeep` (which lowers nested effects) only when
`termNeedsCustomEmit` is true, but that predicate's effect check (`termUsesEffects`) only inspected the
top-level shape. Added `termContainsEffectExpr` (walks children) to `termNeedsCustomEmit`, so the term
routes through `emitExprDeep` and lowers to `_handle(...)`. The property test's effect kind now uses the
inline form as the regression guard; interp==JS==JVM verified via scala-cli, 119 effect+jvmgen tests green.
Full loop: the generated cross-backend differential found the bug, root-caused, fixed, and now guards it.
---

## 2026-06-15 — test(xbackend): property generator → 9 kinds (Option/Either/effects); found a real JVM bug

xbackend-property-equivalence slice 3. Added Option, Either, and an algebraic-EFFECT kind (Counter.tick
loop under a one-shot handle — exercising the separate JvmGenCpsTransform / JsGenCpsCodegen / interp CPS
paths). 54 generated programs agree interp==JS(node) + 9 (one per kind) agree interp==JVM(scala-cli). The
effect kind FOUND a real JVM codegen bug — a `handle(...)` in call-argument position (`println(handle(...))`)
emits raw → scala-cli "Not found: handle"; interp+JS handle it. Filed BUGS.md `jvmgen-handle-in-arg-position`
(open, low-severity, workaround: bind to a val). The property test proved its value on slice 3.
---

## 2026-06-15 — test(xbackend): broaden property generator to 6 feature kinds

xbackend-property-equivalence slice 2. Extended the generated cross-backend differential from arith-only
to 6 program kinds — arithmetic, List[Int] ops, Int match, enum ADT + match, String concat/length,
case-class construction + field access (all deterministic Int/String output). 48 generated programs agree
interp==JS(node) + 6 agree interp==JVM(scala-cli), 0 skipped — the one-source-many-targets guarantee holds
across a much wider surface. Follow-on classes (effects, Option/Either, nested data, closures) queued.
---

## 2026-06-15 — test(xbackend): generated cross-backend property/differential test (slice 1)

xbackend-property-equivalence. The conformance suite checks ~127 fixed programs; `CrossBackendPropertyTest`
GENERATES programs over the core deterministic-Int subset (arithmetic, val, if, user functions) from fixed
seeds and asserts `interp == JS(node)` over 40 + `interp == JVM(scala-cli)` over a 5-seed sample — turning
the one-source-many-targets guarantee from fixed cases into generated coverage. Results bounded to all
backends' safe int range so overflow edge cases don't false-fail. Verified: all 40 + 5 agree (0 skipped) —
the core holds. Extensible harness; broadening to ADTs/match/effects/collections + the overflow dimension
are follow-on slices.
---

## 2026-06-15 — perf(compile): compile-time-at-scale measured — no O(n²), + CompileScaleBench guard

Closed the measurement gap: all prior compile/codegen benches used 6-line inputs. Profiled
parse/type/jvmGen/jsGen across N=50→6400 defs (largest ~345 KB single module). Parse + type scale
LINEARLY; jvmGen/jsGen roughly linear with a mild superlinear tail (×2.1–2.4 per doubling, not quadratic).
A 6400-def module compiles in <0.5 s (jvmGen ~465 ms, jsGen ~240 ms). No pathology found, no fix
warranted. Added `CompileScaleBench` (JMH, ~800-def module through each stage) as a standing guard against
a future O(n²) regression; findings in `docs/compile-scale-findings.md`.
---

## 2026-06-15 — chore: tidy SPRINT + BACKLOG (archive completed work)

Housekeeping. SPRINT (2637 lines of already-shipped task history — autonomous batches, busi fixes, JIT
stages, rust/std milestones, the 4 architecture refinements, the effect-vm-continuations perf thread) →
reset to a clean "no active tasks" queue. BACKLOG (7638 lines, 62 sections, 729/740 items done) → slimmed
to **326 lines**: the 7 sections with open `[ ]` work kept (11 open items, all preserved + verified),
their completed `[x]` sub-items dropped, and the 55 fully-landed milestone sections moved to a compact
"Completed milestones — archived" index. Full detailed history of the archived milestones remains
recoverable via `git log -p BACKLOG.md`. Net: the queues now read honestly — SPRINT empty, BACKLOG shows
only the 11 genuinely-open (mostly deferred/strategic/low-pri) items.
---

## 2026-06-15 — refactor(jsgen): extract content-toolkit emission to JsGenContentEmit (JsGen.scala −13%)

codegen-megafile-deflation slice 2 (mirrors the JvmGen extraction). The content-toolkit JS emission
(~650 LOC) moved verbatim from the 5136-line JsGen into a new `JsGenContentEmit` trait (`self: JsGen =>`),
mirroring the existing mixin pattern; baseDir/lockPath exposed as `private[codegen] val`, `line()` emit
helper widened. Behaviour-identical (compiler-verified): JsGen.scala 5136 → 4486 LOC; 224 JS content +
JsGen output-assertion tests green. Both codegen megafiles now have content emission extracted (JvmGen
−20%, JsGen −13%).
---

## 2026-06-15 — refactor(jvmgen): extract content-toolkit emission to JvmGenContentEmit (JvmGen.scala −20%)

codegen-megafile-deflation slice 1. The self-contained content-toolkit Scala-source emission domain
(~1000 LOC) moved verbatim from the 5050-line JvmGen into a new `JvmGenContentEmit` trait (`self: JvmGen =>`),
mirroring the existing mixin pattern; baseDir/lockPath exposed as `private[codegen] val`. Behaviour-identical
(compiler-verified): JvmGen.scala 5050 → 4022 LOC; 63 content + JvmGen output + scala-cli e2e tests green.
JsGen mirror + JvmGen route/registry clusters = follow-on slices.
---

## 2026-06-15 — infra(backend): build-time cross-backend intrinsic-table parity gate

`CrossBackendIntrinsicParityTest` (backendInterpreter/Test) guards the two hand-maintained core codegen
intrinsic tables `JvmIntrinsics` vs `JsIntrinsics` against undocumented drift (an intrinsic added to one
backend but forgotten on the peer) — previously caught only post-hoc by the conformance suite. Diffs the
keysets against a classified allowlist (3 jvm-only native wallet crypto ops; 27 js-only that JVM provides
via crypto/uuid/graphql/json plugins — a registration-location inconsistency, each verified present on
JVM). Exact-match ratchet: new drift OR a stale allowlist entry fails with an actionable message.
Out of scope (other mechanisms; conformance-covered): the sparse interpreter SPI map (hardcoded natives),
plugin overlays, hardcoded codegen dispatch. Verified green + red-on-drift. Spec
`specs/cross-backend-intrinsic-parity.md`; surfaced follow-up `intrinsic-registration-harmonise`.
---

## 2026-06-14 — perf(jvmgen): memoize the runtime-source preamble constants — codegen-time −82..94% (5.6–17×)

`JvmGen.generate` allocates a fresh `JvmGen` instance per call, and the ~180 KB runtime preamble
(`effectsRuntime` ~3300 lines, `commonRuntime`, `serveRuntime`, `fsRuntime`, `generatorRuntime`,
`reactiveRuntime`, `stubServeRuntime`, `loggerRuntime`) lives in traits mixed into `JvmGen` — so every
call re-ran `stripMargin` over the whole preamble AND re-loaded the runtime-server source files from the
classpath. Profile: ~43% of codegen time was instance-init alone. These vals are process-invariant
constants, now wrapped in a process-level `JvmGenRuntimeCache.memo(key){…}` (ConcurrentHashMap, by-name
compute runs once per key). Measured (CrossBackendBench A/B, non-overlapping bars): jvmGen_arithLoop
2.47→0.145 ms (17×), patternMatch 2.81→0.50 ms (5.6×), recursionFib 2.47→0.171 ms (14×), recursionTco
2.55→0.166 ms (15×); jsGen unchanged; output byte-identical (61 JvmGen output-assertion tests green). The
~100× jvmGen/jsGen asymmetry is now ~2–20×; residual is the genuine 180 KB preamble assembly.
---

## 2026-06-14 — perf(interp): P4.1 compiled-eff-block — multi-shot resume replays compiled segments — effectMultiShotDeep −10% (cumulative −73%)

Compile a straight-line effectful continuation block ONCE into a pre-classified `Array[CStep]`
(`CValStep`/`CExprStep`, cached by `stats` AST identity in `Interpreter.effBlockCache`), so a
multi-shot `resume(v)` replays the compiled segments via `runCompiled` instead of re-walking
`BlockRuntime.step`'s per-statement `s match` dispatch (+ list-cons + `Defn.Val`/`Pat.Var` unapply)
on every resume. Pure-Int-arith scoring segments compile to a direct `Array[Long] => Long` closure
(no `fastPrimitiveValue` recursion / IntV intermediate boxing / `evalCore`); every perform/effectful
rhs routes through `interp.eval` unchanged (effects never folded or reordered). Recognition bails to
`step` for any non-straight-line shape; the hot reused-view loop-body path keeps `step`. Measured
(back-to-back stash A/B, 5 pairs): effectMultiShotDeep ~2.33 → ~2.06 ms (−10%, non-overlapping bars),
no regression on non-effect val/fn/pattern benches. Cumulative 7.39 → ~2.0 ms (−73%). Spec
`specs/effect-vm-continuations.md` §5.
---

## 2026-06-14 — perf(interp): flatMap-resume η-reduction in the multi-shot handler (effect CPS-compile slice 1) — effectMultiShotDeep −47% (cumulative −70%)

- Started the CPS perform-eval compile (Task 4 / spec §4) by re-checking where the residual is:
  post-3f it's **handler-side** (`evalApplyGeneral` + `runBody1` — the handler body
  `opts.flatMap(opt => resume(opt))` re-evaluated per perform, 781×), not the block's `step`.
- The canonical multi-shot handler `coll.flatMap(x => resume(x))` is **η-equivalent to
  `coll.flatMap(resume)`** (`resume` is a 1-arg `NativeFnV`). `EffectsRuntime.flatMapResumeColl`
  recognises that shape and `dispatchCase` calls the `flatMap` dispatch with `resume` **directly** —
  eliminating, per perform, the `x => resume(x)` lambda `FunV` creation, the `evalApplyGeneral`
  `flatMap` method-resolution, and the per-element lambda-body re-eval. Bails to `interp.eval(c.body)`
  for any other handler shape (semantics preserved everywhere else).
- **effectMultiShotDeep 4.26 → 2.24 ms (−47.3%)** — clean back-to-back A/B, tight non-overlapping
  bars; the biggest single slice. **Cumulative 7.39 → 2.24 ms (−70%)** across slices 1/2a/3f + this.
  247 effect/interp tests green incl. the 3125/171875 + 256/2560 multi-shot guards (η-reduction is
  result-identical). General win for any `coll.flatMap(x => resume(x))` (textbook nondeterminism).
- Spec: `specs/effect-vm-continuations.md` §4. The fuller compiled-eff-block / JavacJit lowering
  (P4.1/P4.3) remains larger and is now lower-priority (effectMultiShotDeep is 2.24 ms).

## 2026-06-14 — perf(interp): free-var-limited closure capture (compiled-continuation slice 3f) — effectMultiShotDeep −23.4% (cumulative −43%)

- Profiling traced ~14% of effectMultiShotDeep CPU (`MutableEnvView.foreachEntry`) to **lambda
  closure capture**: the multi-shot handler body `opts.flatMap(opt => resume(opt))` is evaluated per
  perform (781×), and creating the `opt => resume(opt)` lambda captured the **entire** env via
  `foreachEntry` — though the body only references `resume` (the env holds all accumulated
  continuation vars). Massive over-capture.
- `EvalRuntime`'s `Term.Function` case now captures **only the names the body could reference**:
  `collectBodyNames(body)` (distinct `Term.Name`s, cached by AST identity in
  `interp.lambdaFreeNamesCache`) drives the capture by env lookup instead of iterating the whole env.
  A **sound over-approximation** of the free vars (also picks up locally-bound / method / nested-lambda
  names — harmless), so no needed binding is ever dropped; same-as-globals names are still re-read
  live at call time.
- **effectMultiShotDeep 5.53 → 4.23 ms (−23.4%)** — biggest single slice; clean back-to-back A/B,
  tight non-overlapping bars. A **general** win for every lambda created in a non-trivial env (HOFs,
  handlers, callbacks). No regression (hofPipeline ≈10⁻³, recursionFib family at baseline); 248
  interp/effects tests green incl. the capture-heavy `GivenUsingTest`. **Cumulative effectMultiShotDeep
  7.39 → 4.23 ms (−43%)** (slices 1 + 2a + 3f). Design: `specs/effect-vm-continuations.md` §3f.

## 2026-06-14 — perf(interp): bind the block result expr via the fast path in `step` (compiled-continuation slice 2a) — effectMultiShotDeep −21.7%

- Re-profiling after slice 1 showed `evalCore` still ~50% leaf — because slice 1 only handled
  `Defn.Val`, while the block's final expression statement (`e*e + sd`) is evaluated **once per
  complete continuation path = 3125×** (more than all intermediate vals combined).
- Extended the slice-1 fast path to `BlockRuntime.step`'s `case t: Term`: a provably-pure expression
  statement (most importantly the block result) binds via `fastPrimitiveValue` (bare Value, no
  `evalCore` megamorphic dispatch / `Pure` alloc); an effectful expr returns null → unchanged monadic
  path (no dropped/reordered effect).
- **effectMultiShotDeep 6.95 → 5.44 ms (−21.7%)** — clean back-to-back A/B, tight non-overlapping
  error bars. Cumulative with slice 1: **7.39 → 5.44 ms (−26%)**. 207 interp/effects tests green; no
  regression. General win for any pure expression statement / block result.
- Compiled-continuation build, slice 2a. Residual now: the `perform` eval (`evalApplyGeneral`),
  `dispatchCase` per-perform recompute, `FlatMap` threading. Design: `specs/effect-vm-continuations.md` §3d.

## 2026-06-14 — perf(interp): bind pure `val`s via the fast path in `step` (effect-vm-continuations / compiled-continuation slice 1) — effectMultiShotDeep −5.6%

- First slice of the compiled-continuation build (P3c CPU profile: effectMultiShotDeep is CPU-bound,
  **49 % `evalCore`** tree-walk of the continuation). `BlockRuntime.step` now binds a single
  `val x = <expr>` via `EvalRuntime.fastPrimitiveValue` — a bare Value with **no `evalCore`
  megamorphic match dispatch and no `Pure` allocation** — falling back to `interp.eval` only when
  that returns null.
- **Safe by construction:** `fastPrimitiveValue` returns non-null ONLY for a provably
  side-effect-free expression (effect ops are `NativeFnV` → `pureCallValue` bails; effectful
  function bodies don't compile via `compileSlotBody`/`valueCapable` → bail), so binding the value
  directly can never drop a `perform` — a `perform` rhs returns null and takes the unchanged monadic
  path. Only the single-`Pat.Var` shape uses the fast path.
- **effectMultiShotDeep 7.39 → 6.98 ms (−5.6 %)** — a real CPU/wall-clock win (clean back-to-back
  A/B, low load, tight non-overlapping error bars), not allocation. A general win for any pure
  single-`val` binding in any block. 231 interp/effects tests green (incl. the 3125/171875 multi-shot
  guard); no regression on recursionFib / block benches.
- First slice — the continuation is still re-walked structurally; subsequent slices push the
  compiled-segment idea further. Design: `specs/effect-vm-continuations.md` Phase 3d.

## 2026-06-14 — perf(interp): cut unapply allocation in the continuation re-eval path (effect-vm-continuations P3b) — effectMultiShotDeep −19.5% alloc

- Added a representative deep-multi-shot stress bench `effectMultiShotDeep` (nondeterministic
  search, 5 levels × 5 options = 3125 paths, interleaved per-step scoring) — the per-resume
  continuation **re-evaluation** dominates here (baseline 7.5 ms, 1.95 MB/op, a real outlier),
  unlike `effectMultiShot` (256 trivial paths, partly per-op `Interpreter`-construction-bound).
- Deep-profiling its allocation found two avoidable **unapply** allocations on the re-eval path —
  a `Some` + `Tuple4` per visit from scalameta version-extractors: `BlockRuntime.step`'s
  `case Defn.Val(_, pats, _, rhs)` and `EvalRuntime.fastPrimitiveValue`'s
  `Term.ApplyInfix.After_4_6_0(lhs, op, _, ac)`. Converted both to the codebase's established
  **type-test + direct field access** (`dv.pats`/`dv.rhs`; `ai.lhs`/`ai.op`/`ai.argClause`) —
  behaviour-identical, no per-visit `Some`/`Tuple4`.
- **effectMultiShotDeep 1.95 → 1.57 MB/op (−19.5% alloc).** A general win for every `val`-binding +
  binary-op evaluation, not just effects. Guard: `EffectVmContinuationsTest` "deep interleaved
  multi-shot (5×5) yields all 3125 paths" (3125/171875). 225 interp/effects tests green; no regression.
- Still **not** the full compiled-continuation feature (the per-resume AST re-walk remains; deferred,
  design in `specs/effect-vm-continuations.md` Phase 3).

## 2026-06-14 — perf(interp): memoise constant collection literals (effect-vm-continuations P3a) — effectMultiShot −13.7% alloc

- Deep-profiling `effectMultiShot` (multi-shot delimited continuations, 0.93 ms — a non-outlier,
  already correct via the Free-monad trampoline) found it alloc-bound at 406 KB/op, with the
  `choose(List(1,2,3,4))` argument **rebuilt ~85×** across the continuation re-runs — a cacheable
  allocation distinct from the inherent flat-map result lists.
- `EvalRuntime.isPureConstExpr` + the `pureConstCache` gate now memoise a constant **immutable**
  collection literal (`List(..)` / `Vector(..)` / `Seq(..)` with all-pure-const args) by AST
  identity — the same mechanism already used for `Term.Tuple` / `Term.ApplyInfix`. The result is an
  immutable value, so sharing the cached instance is safe; a cheap callee-name gate keeps
  non-collection applies (`fib(n-1)`) off the cache path.
- **effectMultiShot 406 → 350 KB/op (−13.7% alloc), ~0.93 → ~0.78 ms.** Also a general win for any
  constant collection literal in a hot loop. No regression on the Apply-heavy benches (recursionFib
  family, arithLoop). Guard: `EffectVmContinuationsTest` "multi-shot over a cached const list yields
  all 256 paths" (256/2560 — the shared cached list must not corrupt the enumeration).
- This is the safe, tractable slice of P3's "cheaper reification" — **not** the full
  compiled-continuation feature (the per-resume AST re-walk + Free-monad rebuild remain; deferred,
  design in `specs/effect-vm-continuations.md` Phase 3).

## 2026-06-14 — fix(interp): report module import cycles instead of StackOverflowError (busi-reported)

- A true module import **cycle** (`A→B→A`, e.g. a sub-module importing back from the facade
  that imports it) overflowed the stack with no diagnostic. `SectionRuntime.runImport`'s
  `moduleCache.getOrElseUpdate` only inserts after the load thunk returns, so a still-loading
  module is absent from the cache and a cyclic re-import re-runs it forever. (Distinct from the
  diamond dedup `moduleCache` already handles — a diamond is acyclic and never re-enters a
  still-loading path.)
- Added a shared, insertion-ordered `moduleLoading: LinkedHashSet[os.Path]` threaded into child
  interpreters like `moduleCache`; `runImport` checks it before `getOrElseUpdate` and throws
  `InterpretError("Import cycle detected: a.ssc → b.ssc → a.ssc")` on a re-entry. The path is
  marked in-progress only while its body runs (`try/finally`), so a later legitimate import of
  the finished module still hits the cache. Purely diagnostic — acyclic graphs/diamonds unchanged.
- Busi-reported during the busi `p5` `dispatch.ssc` decomposition (facade re-export / strict-DAG).
  `InterpImportCycleTest` (2-cycle + facade↔leaf cycle + acyclic-re-export control) + the existing
  `InterpModuleDedupTest` both green. Spec `specs/import-cycle-diagnostic.md`; `BUGS.md`
  `interp-import-cycle-stackoverflow`.

## 2026-06-14 — perf(interp): compile pure-arith resume expr in the resolver (effect-vm-continuations P2c) — effectReader ~15×, effectOneShot ~11×

- Re-measuring the full `InterpreterBench` landscape put **effectReader (2.63 ms)** and
  **effectOneShot (1.64 ms)** as the top two interp outliers — both bounded by the one-shot
  tail-resume resolver re-running `interp.eval` on the resume expr **every perform** (the 1.0 ms
  gap between them was exactly `interp.eval(k*2)` tree-walked 5000×; re-entering the megamorphic
  tree-walker from inside the JIT'd loop also defeated bridge inlining).
- `EffectsRuntime.compileIntArith` compiles a single pure-Int-arith resume expr (`Lit.Int/Long`,
  `Name(op-arg)`, `+`/`-`/`*`, unary `±`) into a direct `Array[Long] => Long` closure; the
  resolver returns `Value.intV(closure(args))`. Falls back to the unchanged `interp.eval` resolver
  for any op-arg that isn't `IntV` at runtime, and `compileIntArith` returns null for any shape not
  provably bit-identical to interp over 64-bit `Long` (`/`/`%`, Double, conversions, free names,
  tuples). `IntV op IntV ⇒ intV` is plain `Long` (`DispatchRuntime`), so the compiled path is exact.
- **effectReader 2.63 → 0.175 ms (~15×); effectOneShot 1.64 → 0.145 ms (~11×)** — the two slowest
  effect benches become among the fastest.
- **Honest, not folded**: the effect still dispatches every iteration (bridge → resolver) and the
  handler still computes the arithmetic — only the redundant tree-walk is removed (same principle as
  P2 compiling the loop body; NOT inlining the value into the loop, which would drop the dispatch).
  effectReader's unfoldable `i*2` runs at ≈ effectOneShot's constant; `loop(5000)` computes the
  exact 24 995 000. Tests: `EffectVmContinuationsTest` +5; 157 effect-suite tests green.
- Spec: `specs/effect-vm-continuations.md` Phase 2c. Open: ref-return/ref-arg ops, >2 args, slot-cache.

## 2026-06-14 — perf(interp): JIT arg-carrying effect ops (effect-vm-continuations P2b) — ~9.8×

- Extends the effect-op JIT (P2) from 0-arg to **arg-carrying** ops `Eff.op(a)` / `Eff.op(a, b)`
  (numeric args) — the common algebraic-effect shape (`Reader.ask(k)`, `Combine.mix(a,b)`).
  `JitGlobals.resolveEffectLong1/2` pass the numeric args to the resolver; `JavacJitBackend
  .walkLong` lowers a ≤2-numeric-arg `Eff.op(args)` with a live resolver to the right bridge.
- Also broadened `EffectsRuntime.isSimpleResumeArg` (the resolver/tail-resume classifier) from
  literals/names to **pure arithmetic** (`+ - * / %`, unary `±`), so a `resume(k * 2)` arm now
  resolves (previously only `resume(1)`-style literals did — which is why arg-effect loops hadn't
  JIT'd). Still side-effect-free (no nested perform), evaluated per perform with the op-args bound.
- **New bench `effectReader` (`acc + Reader.ask(i)` × 5000, `case Reader.ask(k,resume)=>resume(k*2)`):
  26.7 → 2.72 ms (~9.8×).** effectOneShot unchanged (~1.7 ms). `EffectVmContinuationsTest` (+1: a
  2-arg op with `resume(a*b+1)`) + 425 effects/interp/JIT/while tests green; full bench no regression.

## 2026-06-14 — perf(interp): JIT effectful loops (one-shot tail-resume) — effectOneShot ~11×

- `effect-vm-continuations` Phases 1+2 (spec `specs/effect-vm-continuations.md`). `effectOneShot`
  was the #1 interp-bench outlier (~18 ms; 72% leaf `evalCore` — the `perform` forced the
  Free-monad trampoline so the loop never reached the while-JIT). Now **~18 → 1.67 ms (~11×)**.
- A clean one-shot tail-resume arm `case Eff.op(a.., resume) => resume(rexpr)` makes the effect
  equal to a direct value, so `EffectsRuntime.evalHandle` installs a thread-local **resolver**
  for such ops around the body eval; the op `NativeFnV` (`StatRuntime`) returns `Pure(rexpr)`
  instead of a `Perform`, making the handled body pure. (Phase 1 finding: that alone is ~0% —
  the win is **compiling** the body, not skipping the trampoline.) Phase 2: a new
  `JitGlobals.resolveEffectLong` bridge + `JavacJitBackend.walkLong` lowering a 0-arg `Eff.op()`
  with a live resolver to it, so the whole effectful loop JIT-compiles.
- Safe with no resolver-gating: `tryWhileJit` writes slots back only on success, so if the bridge
  throws (the same `loop` later deep-handled — no resolver), the compiled loop discards its
  partial updates and bails to the trampoline → effect handled normally, no double execution.
- Honest: the effect still runs each iteration, resolved through the handler (the loop is not
  folded). `EffectVmContinuationsTest` (5: JIT path, deep-handler bail, op-arg binding,
  multi-shot untouched) + 518 effects/interp/JIT/while tests green; full interp bench no
  regression. Residual vs arithLoop is the per-iteration bridge call (Phase 2b: hoist constant
  resume exprs; N-arg / ref-return ops).

## 2026-06-14 — perf(interp): `val x = p match { … }` intermediates → while-JIT — ~3000×

- Final loop-val-inline slice: a `val` bound to a pure `match` (`val x = p match { case Pt(a,b)
  => a + b }`). `isPureArith` now admits a pure `match` (`isPureMatch`: pure scrutinee, every
  guard pure-or-absent, every case body pure-arith), so the val is inlined and `s = s + (p match
  { … })` JIT-compiles via the same ref-destructuring match path as `instanceFieldAccess`.
- **`valMatch` (`val x = p match { case Pt(a,b) => a+b }; s = s + x + i`, 1M iters): 2848.6 →
  ~0.7 ms/op (~4000×).**
- Honest scope: this wins for **ref/case-class-destructuring** scrutinees (what the while-JIT's
  match-compile supports). An **Int/literal-scrutinee** match (`(i%3) match { case 0 => … }`)
  inlines correctly but bails to tree-walk — measured no speedup, no regression (the JIT doesn't
  compile that match form; left as a documented limit, not shipped as a win).
- `TupleScalarReplaceTest` (24, incl. Int-match correctness, case-class destructuring, scrutinee
  reassign soundness) + 550 while-loop/interp tests green; full interp bench no regression.

## 2026-06-13 — perf(interp): conditional `val x = if …` intermediates → while-JIT — 10980×

- Extends the loop-val inlining to **conditional intermediates** — `val x = if c then a else b`
  (abs / clamp / select, a very common loop shape). `isPureArith` now admits a pure `if`
  (`isPureCond`: comparisons of pure-arith operands, `&&`/`||`, unary `!`) and `substVal`
  recurses through `Term.If`, so the inlined `s = s + (if …)` JIT-compiles (the bytecode JIT
  already lowers `Term.If`). The `if`-expr is pure → free to duplicate; the existing per-val
  reassignment guard covers vars captured in the condition/branches.
- **`valIf` (`val x = if i%2==0 then i else -i; s = s + x`, 1M iters): 4446.54 → 0.405 ms/op
  (~10980×).** `TupleScalarReplaceTest` (21, incl. `&&`-cond + if-cond reassign soundness) + 499
  while-loop/interp tests green; full interp bench shows no regression.

## 2026-06-13 — perf(interp): multiple/chained/destructuring leading vals → while-JIT — 5650×

- Extends `jit-loop-val-inline` (single leading val) to the rest of the common loop-body shapes:
  **multiple** leading vals (a 2nd `val` previously made the whole loop bail to tree-walk),
  **chained** vals (`val b = a * 2`, folded via `substPriorVals` so each resolves to loop-vars
  only), **destructuring** `val (a, b) = (e1, e2)` (each pattern var → its tuple component), and
  pure numeric **conversions** `.toInt/.toLong/.toDouble` in `isPureArith`.
- `collectFastAssignBody` now `peelVals` all leading vals into a resolved list, then inlines each
  into the assignments. The reassignment soundness guard is **per-val** (a use is inlined only
  when none of that val's expr vars were reassigned by an earlier assignment); unused vals aren't
  dropped. All inlinable vals are pure (tuple / pure-arith), so there's no side-effect reordering
  risk; function-call vals still bail.
- **`multiVal` (`val a=i-500; val b=a*2; s=s+a*a+b`, 1M iters): 3248.42 → 0.575 ms/op (~5650×).**
  `TupleScalarReplaceTest` (18, incl. chained/destructuring/multi-val-soundness/conversion) + 537
  while-loop/interp tests green; full interp bench shows no regression.

## 2026-06-13 — perf(interp): inline pure-`val` loop intermediates into the while-JIT — 9124×

- Generalises the tuple scalar-replacement (`jit-loop-tuple`) to the ubiquitous real-code shape
  of a **leading `val` intermediate in a loop body**. Previously *any* leading `val` made
  `EvalRuntime.collectFastAssignBody` bail → the whole loop tree-walked (block scoping per
  iteration); now a leading `val x = <expr>` used only as a whole `x` (pure-arith expr) or as
  `x._K` (static tuple) is inlined into the assignments, so the body becomes pure `name = rhs`
  the Long-while JIT compiles.
- **`valIntermediate` (`val d = i-500; s = s + d*d`, 1M iters): 2289.85 → 0.251 ms/op (~9124×)**
  — now arithLoop parity. `isPureArith` gates the scalar case (`Lit`/`Name`/arith `ApplyInfix`/
  unary `±` — no `Term.Apply`, so duplicating the inlined expr is free, never a function-call
  pessimisation).
- **Soundness** (and a fix for a latent bug in the just-shipped tuple path): inlining is only
  applied when no variable of the val's expr is **reassigned by an earlier statement before a
  use** of the val (`val t=(i,..); i=i+1; s=s+t._1` would otherwise capture the post-increment
  `i`) — verified by reassign-before-use tests for both tuple and scalar. Also refuses to inline
  an *unused* val (its evaluation, e.g. a `/`-by-zero, is preserved) and any non-`_K`/bare-`t`
  use of a tuple val. JIT-bail safety means the original body still tree-walks identically when
  the substituted body can't compile.
- `TupleScalarReplaceTest` (12, incl. soundness + function-call-not-inlined + JIT parity) + 521
  while-loop/interp tests green.

## 2026-06-13 — perf(interp): tuple scalar-replacement in the while-loop JIT — tupleMonoid 2600×

- First slice of "start the JIT lever": the converged fix for the top interp outliers is to make
  the hot loop body JIT-compilable by eliminating the construct that forces tree-walk. Here that
  construct is `TupleV`.
- A `while` body whose only tuple use is a leading `val t = <static tuple>` (`(a, b)` or
  `(a, b) ++ (c, d)`, any nesting of literal tuples joined by `++`) accessed only by `t._K` is
  **scalar-replaced** at the AST level in `EvalRuntime.collectFastAssignBody`: `tupleComponents`
  flattens the tuple to its element terms and `substTupleAccess` inlines each `t._K` → component,
  so the body becomes pure `name = rhs` assignments the existing Long-while JIT compiles — the
  tuple never materialises. (Gotcha learned: `(a,b) ++ (c,d)` parses as `ApplyInfix((a,b), ++,
  ArgClause(c, d))` — the RHS tuple is *flattened* into the arg clause, not a single tuple arg.)
- **Sound by construction**: bails (keeps the materialised tuple, tree-walks — identical result)
  on any non-`_K` use of `t` (e.g. `firstOf(t)`), a single-arg `++` (runtime arity unknown), a
  non-static tuple, or an out-of-range `_K`. Minimal blast radius: `collectFastAssignBody` already
  bailed on any block with a non-`Assign` leading stat, so this only *adds* the tuple case.
- **`tupleMonoid` 13.3 → 0.005 ms/op (~2600×)** — `(i,i+1)++(i+2,i+3)` + `t._1..t._4` accumulation
  now compiles to a pure-Long loop. `TupleScalarReplaceTest` (7, incl. soundness/JIT-vs-tree-walk
  parity) + 454 while-loop/interp tests green. Remaining outliers (`effectOneShot`,
  `typeclassFoldMacro`) need their own slices of the same lever (BACKLOG).

## 2026-06-13 — feat: `exec` per-call environment (busi vr-13 faithful dates)

- Added a 2-arg form `exec(command: List[String], env: List[(String,String)])` to the
  JVM interpreter's `exec` builtin, alongside the existing 1-arg form — the pairs are
  applied to `ProcessBuilder.environment()` (the child otherwise inherits the process
  env). Body refactored into a shared `runExec` helper. Requested by busi to set
  `GIT_AUTHOR_DATE`/`GIT_COMMITTER_DATE` for faithful, deterministic git-semantic
  commits. Forward-compatible with the planned `std.process.exec`'s `ProcessOptions(env)`
  (`specs/std-fs-os.md` §4). Spec `specs/exec-builtin.md` (extended).

## 2026-06-13 — perf(interp): one-shot tail-resume fast path — −39% alloc on effect handlers

- `effect-oneshot-perf`: `effectOneShot` (18.4 ms) is the #1 interp-bench outlier. Investigation
  found it is **O(n)** (the one-shot placeholder loop is O(1)/dispatch) and **CPU-bound on the
  effectful-loop body tree-walk** (JFR ~65% leaf `EvalRuntime.evalCore`; a `var`+`while` loop
  with a `perform` inside can't JIT), not on the effects machinery.
- Shipped a narrow **one-shot tail-resume fast path** in `EffectsRuntime`: a bare
  `case Eff.op(..) => resume(simpleArgs)` arm (no guard, single arm, literal/name resume args)
  is provably tail-position, so it skips the per-perform placeholder `FlatMap` + the `resume`
  NativeFnV and feeds the value into the continuation directly. Anything else (non-tail like
  `msg :: resume(())`, guards, multi-shot) uses the unchanged placeholder path.
- **Allocation −39%** on `effectOneShot` (gc.alloc.rate.norm 4.10 → 2.50 MB/op) — a common
  pattern (state machines / counters / readers). **Honest scope: this is an allocation
  reduction, NOT a wall-clock speedup** (effectOneShot stays ~18.3 ms, CPU-bound as above).
  The wall-clock lever is JIT-compiling the effectful loop body — a large effort in BACKLOG.
- Correctness-preserving: `EffectOneShotFastPathTest` (5) + StdEffects/JvmGen/JsEffect suites green.

## 2026-06-13 — perf(interp): fused `List.foldLeft(z)(g)` fast-path (`hof-glue-jit-compile` slice)

- Shipped the safe, bounded interp slice of `hof-glue-jit-compile`: `evalApplyGeneral` now
  recognizes the curried `Apply(Apply(Select(_, "foldLeft"), [z]), [g])` shape and, for a List
  receiver + FunV combine, goes straight to `foldLeftReusing` — skipping the intermediate
  `NativeFnV` alloc + the ~40-case `dispatchList` name-match the generic curried path does.
  Any other receiver/combine (Range/Set/Vector, NativeFnV combine) completes via the *same*
  generic `foldLeft` dispatch using the already-evaluated values, so semantics + effect ordering
  are preserved exactly. Minimal blast radius (matches only when `app.fun` is itself an `Apply`,
  i.e. curried — plain and method calls never reach it).
- Matched A/B (`-wi3 -i5`): `typeclassFoldMacro` **1.259 → 1.127 ms/op (−10.5%)**; `stringSplit`
  (folds a `.map` result) unchanged at 0.217. `FusedFoldLeftTest` (6 cases) + 224 fold/typeclass
  tests green. Spec `direct-style-eval-spec.md` §11.4.
- The full lever (JIT-compiling `combineAll`'s body so the 300× tree-walk disappears — the
  `call:no-compilable-target` VmCompiler gap, needing SscVm List-iteration opcodes) stays open
  in BACKLOG as `hof-glue-jit-compile`.

## 2026-06-13 — perf/investigation: `hof-dispatch-cpu-devirt` — no targeted win, → BACKLOG

- Investigated the SPRINT item `hof-dispatch-cpu-devirt` (devirtualize the typeclass/HOF
  dispatch that dominates `typeclassFoldMacro`, baseline 1.286 ms/op). **Outcome: no targeted
  ≥15% devirt win exists** — recorded with data, moved to BACKLOG as `hof-glue-jit-compile`.
- Measurements: (1) the inner `combine` (`a+b`) is **already bytecode-JIT'd** — JIT on/off =
  1.26 vs 3.80 ms/op (3×), `SSC_JIT_STATS` reports no bails. (2) Fresh JFR `jdk.ExecutionSample`
  (189 samples): **147 = 78% leaf `EvalRuntime.evalCore`** — the megamorphic `term match`
  itself; no callee is hot. (3) Two targeted levers each measured **0%** and were reverted:
  no-op'ing `trackPos` (per-eval `tree.pos` read) and caching the JIT `Entry` on `FunV` to
  drop the `synchronized` `entryFor` lookup from every dispatch.
- The residual is the 300× tree-walk of `combineAll`'s HOF glue (`foldLeft` + two `summon`
  Selects); the real lever is **compiling that glue** (`combineAll` bails the JIT on the
  `foldLeft` HOF call — the `call:no-compilable-target` gap), a large CALLREF / `LExpr`-roadmap
  effort, not a devirt slice. No product code changed. Spec `direct-style-eval-spec.md` §11.3.

## 2026-06-13 — feat(effects): handler return clause codegen (JVM + JS)

- Lowered the handler return clause (`case Return(x) => expr`, landed earlier on the
  interpreter) to the JVM and JS Free-monad CPS backends, so `handle(body) { … case
  Return(_) => List() }` maps the body's pure completion there too (previously those
  backends silently dropped a `Return` case — it didn't match the qualified `Eff.op` shape).
- **JVM**: new `_handleWithReturn(bodyThunk, handledOps, handlers, retMap)` runtime
  (`JvmGenRuntimeSources`) + `JvmGen.emitHandleForm` partitions the `Return` case into an
  `Any => Any` retMap and routes through it. **JS**: same-shaped `_handleWithReturn`
  (`JsRuntimePart2b`) + `JsGen.genHandleForm` emits a `(_rv) => {…}` retMap. Both are
  recursive evaluators: `resume` re-enters (`v => hwr(continuation(v))`), a bare pure value
  passes through retMap, op-case-body results are returned directly (mapped exactly once).
  Handlers with no `Return` arm keep the unchanged `_handle`/`_handleOneShot` loop.
- Cross-backend parity verified at the same `List(Hello, World!)` deep accumulation +
  `List(hi, hi)` tail-completion shapes: interp `StdEffectsTest` (48), JVM
  `JvmGenEffectsRuntimeTest` (33, +3), JS `JsEffectLoopTest` (7, +3) all green.
- **Rust**: n/a — the base `handle`/`resume`/`perform` IR lowering itself is unstarted
  (R.4.2); `effect.rs` ships the runtime infrastructure only, so there is no handler codegen
  to attach a return clause to. It lands automatically once R.4.2 wires `handle`/`resume`
  through `run_with`. Spec `specs/algebraic-effects.md` §5.2.1 (per-backend table).

## 2026-06-13 — feat: `exec` process-execution global builtin (busi vr-10)

- New global `exec(command: List[String]): (Int, String, String)` = (exitCode, stdout,
  stderr) in the JVM interpreter (`BuiltinsRuntime`), beside the `std.fs` bare globals.
  argv form (no shell ⇒ no injection), blocking, stderr drained on a side thread (no pipe
  deadlock), spawn failure → `InterpretError`. The bare-global primitive of the planned
  `std.process.exec` (`specs/std-fs-os.md` §4 — rich `ProcessResult`/opts/`spawn` + JS/Rust
  backends still planned there). Requested by busi to drive the local `git` CLI for the
  versioned-repository `git-mirror` transport. Spec `specs/exec-builtin.md`.

## 2026-06-13 — perf/investigation: HOF-dispatch (redirect from direct-style-eval)

- `direct-style-eval` re-validated and DEFERRED (data-disproven: `Computation.Pure` ~16% of
  alloc, dispatch machinery ~66%; spec §11.1). Redirected to the data-indicated win, HOF/
  typeclass dispatch.
- Shipped the obvious allocation slice: `DispatchRuntime` matched `case InstanceV(t, f)` at
  3 hot sites, and InstanceV's custom `unapply` allocates a `Some`+`Tuple2` per dispatch.
  Replaced with a type-test (confirmed via JFR: `InstanceV.unapply` gone, `Some` 116→76).
- FINDING (honest): `typeclassFoldMacro` is **CPU-bound on dispatch**, not alloc-bound —
  removing the (tiny) `Some`s left alloc-rate (132 KB/op) and wall-clock (1.30 ms/op)
  unchanged. JFR sample count/weight overstated the byte impact. The real win is
  devirtualizing the dispatch CPU — queued as `hof-dispatch-cpu-devirt` (BACKLOG, deep).

## 2026-06-13 — feat(effects): handler return clause

- A handler may include `case Return(x) => expr` — a RETURN CLAUSE that maps the handled
  computation's final pure value, enabling the textbook deep-handler accumulation
  `handle(body) { case Eff.op(.., resume) => msg :: resume(()); case Return(_) => List() }`
  (→ `List(Hello, World!)`). `evalHandle` partitions out `Return` cases; a handler with one
  uses a direct recursive evaluator (`handleWithReturn`) where `resume` is an eager
  `x => handleWithReturn(continuation(x))` — applying the clause per continuation completion
  (no double application) and yielding a concrete value for non-tail `resume(())` (vs the
  optimized loop's lazy placeholder). The O(1) one-shot loop is kept unchanged for the
  no-return-clause path. Works for one-shot deep accumulation, tail-position mapping, and
  multi-shot branch wrapping. Interp-only (JVM/JS/Rust CPS lowering is a follow-up).
  `examples/algebraic-effects.ssc` restored to the pure `msg :: resume(())` form. Regress:
  `StdEffectsTest` (4 cases); existing effect suites (interp/JVM/JS) green. Spec
  `specs/algebraic-effects.md` §5.2.1.

## 2026-06-13 — test(examples): smoke-run plugin-backed examples

- `PluginExamplesSmokeTest` (in `backendInterpreterPluginTests`, which depends on
  `allPlugins`) runs `crypto-demo`/`crypto-encrypt-demo`/`crypto-verify-demo` + `uuid-v7` +
  `invoice-pdf`/`invoice-email` end-to-end through the interpreter with plugins loaded —
  the gap the cli `ExamplesSmokeTest` can't cover (no plugins on its test classpath). Each
  must complete without throwing or printing an error marker. Closes
  `examples-smoke-run-plugins`.

## 2026-06-13 — feat(interp): mkdir/mkdirs/listDir/isDir global builtins

- busi (testbed) needed directory primitives from plain `.ssc`. `mkdir`/`mkdirs`/
  `listDir` existed only in the fs-plugin (`FsIntrinsics`) + `std/fs.ssc` extern
  decls, but the `std.fs` externs do not link to a native impl on import and a
  bare call was `Undefined`. Registered `mkdir`/`mkdirs`/`listDir`/`isDir` as
  **global builtins** in `BuiltinsRuntime` next to `writeFile`/`readFile`/`exists`
  (`java.nio.file` / `java.io.File`; `listDir` sorted). `FsPluginTest` gains a
  `dir globals (no fs-plugin)` case proving they work without the plugin. Unblocks
  busi vr-2b (the readable `docs/`+`events/` partitioned tree).

## 2026-06-13 — fix(interp): parameterized effect decls + multi-shot effects in subsections

- `effect Name[T]:` (parameterized effect *declaration*) errored `No method 'Name' on native
  effect` — `Parser.effectLinePat` had no type-param clause, so it wasn't rewritten to
  `object Name { … }`. Added an optional `[…]` after the name (shared `lang/core`, all backends).
- A `multi effect` declared under a `##`/`###` subsection was treated as one-shot
  (`One-shot violation`): `Interpreter.runInit` gathered the `multiShotEffects` trees only from
  top-level `module.sections` content, never the nested subsections where blocks live. Made the
  collection recurse `subsections`. (The earlier "cross-section leak" diagnosis was wrong — it
  was a missing traversal; a top-level `multi effect` always worked.)
- `examples/algebraic-effects.ssc` now runs end-to-end (Logger / State / interleaved / NonDet
  multi-shot / capability / stdlib runners / Stream) and joined the `ExamplesSmokeTest` run-set.
  Regress: `StdEffectsTest` (parameterized `effect Box[T]:` + `multi effect` in a `##` subsection).
  Closes BUGS.md `interp-parameterized-effect-decl` + `interp-effect-multishot-in-subsection`.

## 2026-06-13 — docs/fix: algebraic-effects example + 2 interp effect bugs filed

- Resolved `interp-cons-in-effect-handler`: the `examples/algebraic-effects.ssc` Logger
  handler did `msg :: resume(())` (deep-handler list accumulation), but ScalaScript's
  `handle` has no return clause to seed the base case, so `resume(())` yields the
  continuation's pure `()` and `msg :: ()` errored. Not an interp bug — rewrote the section
  to a working `var` accumulator (same `List(Hello, World!)`), and fixed the State section
  (stdlib `State` + `set`, dropped a broken parameterized redecl). All sections now run in
  isolation.
- Two real interp bugs discovered + filed (BUGS.md): `interp-parameterized-effect-decl`
  (`effect Name[T]:` declaration errors `No method 'Name' on native effect`) and
  `interp-effect-multishot-cross-section-leak` (a `multi effect` handler is treated as
  one-shot when run after an earlier one-shot `handle` in the same program — green in
  isolation). The latter blocks the example from running end-to-end, so it's not yet in
  `ExamplesSmokeTest`. Also queued `effect-handler-return-clause` as a future feature.

## 2026-06-13 — fix(normalize): bare `println` reference no longer auto-invoked

- `xs.foreach(println)` / `val f = println` errored `Not callable: ()` (and broke
  `examples/typed-data.ssc`): Normalize rewrote every bare `println` → `Console.println`
  (a Select to a native-fn field), which the interpreter auto-invoked as a 0-arg field
  access → `()`. Fixed by rewriting to `Console.*` **only when applied** (`(?=\s*\()`
  lookahead); a bare reference stays the plain name, bound by every backend to the
  intrinsic function value. Surgical (only `println`/`print` — paren-less 0-arg methods like
  `gen.zipWithIndex` are untouched; an earlier dispatch-level `bareSelect` attempt regressed
  those and was reverted). `typed-data.ssc` now runs end-to-end and joined the
  `ExamplesSmokeTest` curated run-set. BUGS.md `interp-typed-data-not-callable` → fixed.

## 2026-06-13 — fix(interp): universal `.toString` fallback

- The interpreter had no `.toString` dispatch for composite Values — `xs.toString` on a
  list errored `No method 'toString' on ListV`, and `m.toString` on a map was read as a
  key lookup (`No key 'toString'`). Fixed by intercepting `toString` (0-arg) at the top of
  `DispatchRuntime.dispatch` (next to the `asInstanceOf` early-return), rendering via
  `Value.show` — the canonical println / string-interpolation path — so `x.toString ==
  s"$x"` for every value. Case-class instances with a user-defined `toString` keep it
  (`lookupTypeMethod` checked first). Interp-only (JVM/JS codegen emit native `toString`).
  Found while expanding examples smoke-run coverage; `examples/async-parallel-demo.ssc`
  now runs end-to-end. `BugReproTest` + 65 `.toString`-dependent tests (7 suites) green.
  BUGS.md `interp-toString-on-collection` → fixed.

## 2026-06-12 — feat(uuid): uuid-p6 — monotonic v7 (closes the UUID milestone)

- `Uuid.v7Monotonic(): Uuid ! SideEffect` — a strictly-increasing-within-a-millisecond v7
  generator (RFC 9562 §6.2 Method 1: `rand_a` 12-bit dedicated counter, seed-in-lower-half /
  increment / overflow-spin / clock-rewind guard). Shipped on **both** JVM
  (`UuidIntrinsics.generateV7Monotonic`) and JS (`JsRuntimePart2b.uuidV7Monotonic`),
  surfaced as `uuid.ssc`'s `v7Monotonic()`. The generator code landed in `bcb687ec3`; this
  entry closes out the BACKLOG/spec bookkeeping (the item was left as an open `[ ]` and the
  spec still said "optional / no JS equivalent"). `UuidPluginTest` green (17 cases incl. the
  2 monotonic ones). The UUID milestone (p1–p6) is now complete.

## 2026-06-12 — fix(typer): nominal subtyping for `case class … extends Trait`

- busi (testbed) hit a confusing, name-dependent error building its
  `RepositoryBackend` trait SPI: `case class C() extends T; def f(): T = C()`
  errored `Type mismatch: expected T, found C` — but only for multi-character
  names (`A`/`B` passed). Root cause: `Typer.isCompatible` had **no** nominal
  subtype rule; the only `C <: T` upcast that ever passed was an accidental
  `looksLikeTypeVar` match on single-uppercase names.
- Fix (additive): a `classParents` registry from each class/case-class/trait/enum
  `extends` template inits, a transitive cycle-guarded `nominalSubtype`, and an
  `isCompatible` clause. `NominalSubtypeTest` (5 cases) + full typer suite (193)
  green; negative case (unrelated class) still errors. Spec
  `specs/typer-nominal-subtype.md`.

## 2026-06-12 — feat(declarative-ui): rowsPath on the native/server-rendered path (B.3 v2)

- The Remote DataTable envelope-drill (`rowsPath`, Scope B.3) now works on the
  server-rendered custom/static frontend used by the interpreter `serve()`/`emit()` and
  emit-jvm `serve()`, not just the JS browser SPA. Carried on the shared model
  (`TableDataSource.Remote(signal, rowsPath = "")`), threaded through the interp +
  JVM-codegen `fetchRowsSource`, and drilled in `StaticJsEmitter` via a shared
  `__ssc_rowsOf(data, rowsPath)` helper that mirrors the browser `_ssc_ui_rowsOf` (drill the
  dotted path, else fall back to `{data|rows|items|results}`, never throw). `dataTableView`
  now accepts a `TableDataSource` (was a hard cast to `FetchUrlSignal`). The earlier deferral
  premise ("JVM emits no DataTable client JS") was wrong — both serve paths render via
  `FrontendFrameworks.current().emit()` = StaticJsEmitter. Verified end-to-end (`ssc run` +
  `emit` → drilled `app.js`); `RemoteRowsPathTest` runs the emitted helper under Node,
  `JvmGenRowsPathTest` covers the emit-jvm codegen. All 6 frontend suites green.

## 2026-06-12 — feat(declarative-ui): keyed formBody + close declarative-ui-vnext

- A `formBody` field entry may now be a `(jsonKey, signalId)` tuple so the JSON wire key
  can differ from the signal id (a bare string keeps `key == signal`). The ScalaScript
  tuple serialises to a `[jsonKey, signalId]` 2-array (the `_isTuple` marker is dropped by
  `JSON.stringify`); `_ssc_ui_buildFormBody` reads `sv[signalId]` and writes it under
  `jsonKey`. `formBody`'s param widened `List[String]→List[Any]`; no new intrinsic and no
  interpreter change (the interp wraps `fields` opaquely). Regress: `JsGenStdImportTest`
  keyed-tuple case (full ssc→JS + assembler) + example `content-form-submit.ssc`.
- Closed the `declarative-ui-vnext` v-next polish bucket. The one remaining piece —
  `rowsPath` on the native/JVM backend — is deferred to BACKLOG as an architectural item
  (`declarative-ui-rowspath-jvm`): the JVM backend emits no DataTable/Remote client JS at
  all, so `rowsPath` (a browser-runtime envelope-drill) is JS-only by construction; no
  consumer has requested a JVM client-emission path.

---

## 2026-06-12 — fix(jit): break the self-recursive co-emit/tryCompile recursion

- The while-JIT's pure-long callee co-emission and `tryCompile` populated their memo only
  AFTER compiling the callee body, so a self-recursive callee re-co-emitted its own body
  unboundedly → StackOverflowError (caught + bailed, expensively). Fix: reserve the name
  before walking the body in both paths, so a recursive reference emits a self-call instead
  of recursing — structurally impossible SO + enables self-recursive-callee JIT in the
  co-emit path. The documented repro doesn't currently reach that path in sbt (the while-JIT
  bails earlier on recursive-callee accumulator shapes) so it's a verified-safe
  latent-recursion / robustness fix. backendInterpreter 1682 green.

---

## 2026-06-12 — fix(jit): pass the running classpath to the runtime javac JIT

- The runtime `javac` JIT's `getTask` calls used `options=null`, so javac fell back to
  its default classpath (`.`, not `java.class.path`) and bailed to tree-walk under sbt
  `runMain`/unforked runs. New `jitClasspathOptions` harvests the classloader URLs +
  `java.class.path` and passes `-classpath`, so the JIT compiles in every launch mode
  (harmless — tryCompile already catches Throwable). Finding: the forked test suite
  already compiled the JIT via `java.class.path`, so JIT codegen is in-sbt testable
  (no fat jar needed). Regress: JitClasspathTest. backendInterpreter 1680 green.

---

## 2026-06-12 — docs(direct-style-eval): p1 spike → DEFER (data-backed go/no-go)

- Resolved the direct-style-eval strategic question with a cheap JFR-profile-first gate
  (no `evalDirect` infra built). On the representative tree-walked HOF/dispatch workload
  (`typeclassFoldMacro`), `Computation.Pure` is only ~18% of allocation — dwarfed by the
  generic-HOF dispatch machinery (~70%). The JIT already eliminates `Pure` on every shape
  where it dominates; the shapes that still tree-walk are dispatch-heavy where `Pure` is a
  minority. `evalDirect`'s wall-clock ceiling is well below the ≥15% gate, against a HIGH
  migration risk (530 sites + load-bearing effect-boundary detection) → **DEFER**. The real
  win for these shapes is JIT-compiling the generic HOF dispatch. Spec §11. SPRINT cleaned
  up (p2–p6 deferred; stale `(orig)` plan-note lines closed).

## 2026-06-12 — fix(js): run a self-handling effectful function at the value boundary

- A function that handles its own effects internally (no unresolved `perform`) but is
  CPS-emitted (its body contains `handle`) returned an un-run lazy `_FlatMap` on JS (JS
  `_bind` is always lazy, unlike JVM's eager-on-Pure), so `println(workload())` printed
  `[object Object]`. `JsGen.runIfEffectful` now wraps a non-CPS-context call to an
  effectful function in `_run` (idempotent on a resolved value; CPS-context calls go
  through `genCpsApply`, untouched). **`effect-multishot` now runs on JS** (handled-in-while
  → 204; non-loop self-handling → 3; one-shot regression → 5). BUGS.md
  `js-self-handling-cps-fn-not-run` `fixed`. backendInterpreter 1678 green.

## 2026-06-12 — feat(js): custom effect `perform` inside a var+while loop now compiles + runs

- Mirrors the JVM fix on the JS backend (`JsGenCpsCodegen`): a `Term.Assign` threads its
  rhs through `_bind` (so a `perform` in it runs) then mutates the target, and a
  `Term.While` lowers to a trampolined recursive helper (was a raw `genExpr` loop). JS
  arrow-function params are mutable, so the CPS `var`s need no special handling. The
  interim perform-in-while honesty gate (`CapabilityCheck.performInWhileLoop`) is removed
  now that the gap is closed on both source backends. Verified via node (one-shot → 5,
  longer loop → 30). core 976 + backendInterpreter 1678 green. A separate pre-existing JS
  bug — a self-handling CPS function returns an un-run lazy `_FlatMap` on JS, so
  `effect-multishot` stays `n/a` on JS — is recorded in `BUGS.md`
  (`js-self-handling-cps-fn-not-run`) with a verified `_run`-wrap fix.

## 2026-06-12 — feat(jvm): custom effect `perform` inside a var+while loop now compiles + runs

- A `perform` reachable in an imperative `var`+`while` loop was n/a on the JVM backend
  (JvmGenCpsTransform bound `var` as an immutable `_bind` lambda param and had no `while`
  case → un-compilable Scala). Now a CPS-block `var` stays a real typed mutable `var`
  (type inferred + registered for assignment casts), a `Term.While` lowers to a trampolined
  recursive helper that threads each iteration's perform through `_bind`, and effectful
  assigns thread the continuation (a perform nested in an operator arg is detected by a new
  whole-subtree predicate). The runtime `_dispatch` gained numeric `.toX` conversions and
  `_binOp` gained `%`/mixed-width widening (perform results flow through them as `Any`).
  The perform-in-while honesty gate is now JS-only. Verified end-to-end via scala-cli
  (one-shot → 5, multi-shot → 204; both `bench/corpus/effect-{oneshot,multishot}.ssc`
  compile). core 982 + backendInterpreter 1676 green (zero regressions). JS lowering
  (`effect-cps-loops-js`) remains.

## 2026-06-12 — feat(lint): `@ui=toolkit` lint extends to Markdown `toolkit:` link references (B.7)

- `ContentToolkitLint` now harvests `action` / `rows` / `source` references from Markdown
  `toolkit:` links (`[Invoices](toolkit:table?rows=invoices)`) — which live in the document
  tree (`module.document`) — and feeds them through the same registration cross-check as the
  `@ui=toolkit` YAML control references (typo hint + conservative non-empty-registry gate).
  Folded into `collectReferences` so a Markdown-only module is linted too. Link `signal=`/
  `showWhen=`/`enabledWhen=` are deliberately not linted (input controls may declare a signal
  inline → would false-warn). Regress: 6 `ContentToolkitLintTest` cases + the real
  `examples/content-live-rows.ssc` and `examples/markdown-toolkit-links.ssc` lint clean.
  core 981 green. (declarative-ui-vnext B.7; B.3 `rowsPath` + typed `formBody` still open.)

## 2026-06-12 — feat(validate): honest diagnostic for effect `perform` inside a while-loop (jvm/js)

- A custom effect performed inside an imperative `while` loop does not lower to the
  Free-monad CPS source backends (jvm/js) — they silently emitted broken Scala/JS that
  only failed downstream in scala-cli / node (a mystery `n/a`). `CapabilityCheck.performInWhileLoop`
  now refuses compilation with a clear message instead. Precise + conservative: parses the
  module's own scalascript blocks (imports are never inlined, so library effect runners
  can't trip it), runs `EffectAnalysis.analyze`, and flags a `Term.While` only when its
  body actually performs an in-module effect op or calls an effectful function. Gated on
  source-emitting backends (`OutputKind.ScalaSource`/`JavaScriptSource`) so the interpreter
  is untouched. Interim until `effect-cps-loops-{jvm,js}` land the real lowering. Tests:
  `CapabilityCheckTest` + the real `bench/corpus/effect-oneshot.ssc`. core 974 green.

## 2026-06-12 — feat(types): HKT / kind-bound checking in `ssc check` (type-lambda p3b)

- Adds a kind registry `typeCtorKinds` (type-constructor name → its params' kinds; 0 =
  proper type, k>0 = higher-kinded `F[_…]`), populated from built-ins + user
  class/trait/enum tparams + `type` aliases. `checkTypeApplication` (in `typeAnnotToSType`)
  flags, for KNOWN constructors only, wrong arity (`List[Int, String]`, `Map[Int]`) and
  kind-bound violations (`Functor[Int]`/`Functor[Map]` where `trait Functor[F[_]]`,
  `Fix[Map]` where `type Fix = [F[_]] =>> F[Int]`, `Box[List]` where `Box[A]`). Conservative
  by construction — local HK type params and imported/unknown constructors are never
  falsely flagged (validated against `runtime/std/functor-applicative-monad.ssc`). Arity
  errors consolidated out of `expandAlias` into the single checker. Regress: 7
  `TypeLambdaProgressTest` cases. core 968 + backendInterpreter 1673 green.

## 2026-06-12 — feat(types): typer β-reduces type-lambda aliases in `ssc check` (type-lambda feature complete)

- The typer ignored type lambdas twice: `typeAnnotToSType` had no `Type.Lambda` case
  (a `type IntKey = [V] =>> Map[Int, V]` rhs fell through to `SType.Any`), and
  `expandAlias` returned a no-own-params alias's rhs verbatim — so `IntKey[Long]`
  dropped its `[Long]` application. `ssc check` therefore saw `Any` where the rust
  backend already β-reduces. Fixed: parse `Type.Lambda` → `SType.TypeLambda`, and
  β-reduce a `TypeLambda` rhs against the use-site args in `expandAlias` (wrong arity →
  error), via new `SType.substNames` (name-keyed, shadowing-aware) + `SType.applyTo`.
  Both surfaces covered (the parser desugars the placeholder form to a lambda first).
  Regress: `TypeLambdaProgressTest` (pure `applyTo` + `ssc check` integration). core 962
  green. This closes `type-lambda-p3-semantics` — **the type-lambda feature is complete**
  (parse + represent + round-trip across 5 backends + `.sscc` cache + use-site reduction).

## 2026-06-12 — fix(sscc): placeholder type-lambda aliases survive the `.sscc` v3 cache round-trip

- The `.sscc` v3 read (`ScalaNode.deferred`) raw-parses the source reconstructed from
  the token stream, and the write stores the *placeholder* tokens (`Map[Int, _]`)
  verbatim — the placeholder→`=>>` desugar is a tree rewrite, not a string preprocessor,
  so it never ran on read. A placeholder type-lambda alias read from the cache therefore
  reverted to a wildcard, diverging from the direct `Parser` parse. Fixed by exposing
  `Parser.desugarTypeLambdaAliases` (pure tree rewrite, no re-preprocess) and applying it
  in `ScalaNode.deferred`. Native `[X] =>> F[X]` already round-tripped via the stored
  `=>>` token. Regress: `TypeLambdaProgressTest` round-trip case (flipped from pending) +
  two `SsccFormatV3Test` phase-B cases. core 958 green. Closes `type-lambda-sscc-roundtrip`;
  only optional p3 β-reduction remains.

## 2026-06-12 — feat(types): placeholder type-lambda aliases desugar when nested in object/trait/class

- `Parser.desugarPlaceholderTypeAliases` previously only rewrote TOP-LEVEL `type`
  aliases, so a placeholder alias inside an `object`/`trait`/`class` body
  (`object M: type X = Map[Int, _]`) stayed a wildcard and failed jvm codegen ("does
  not take type parameters") when applied. It now recurses into template bodies
  (scalameta 4.17 `templ.body.stats`; `Template.copy` takes the legacy flat `stats`)
  at any depth, reconstructing a template only when a member actually changed so
  unrelated nodes keep their original positions. No JvmGen change — `blockContainsTypeLambda`
  already recurses through children, so the nested `Type.Lambda` routes through the
  tree-emit. Regress: 3 nested cases in `TypeLambdaProgressTest` + new `JvmGenTypeLambdaTest`.
  core 955 green. Closes `type-lambda-nested-aliases`; `.sscc` round-trip + p3 remain.

## 2026-06-12 — fix(interp): three-way mutual-TCO no longer blows up (was a "flaky" gate hang)

- The full `backendInterpreter/test` gate's intermittent "hang at InterpreterTest" was
  not flaky/environmental — it was a deterministic O(n²) blow-up in `mutual-TCO —
  three-way ping-pong` with the JIT on. A 3-function tail-recursion cycle
  (`ping→pong→pang→ping`, no function calling itself) ran the whole cycle through the
  register VM, which lowers a *non-self* tail call to a recursing `CALL`; the nested
  `SscVm.exec` recursion overflowed the 64K register stack, and `runVm` grew + restarted
  `exec` from scratch on each overflow → quadratic (depth 30k = 29 ms, 60k = 115 s,
  99 999 ≈ a hang). `SSC_JIT=off` was unaffected (~390 ms).
- Fix: the TCO trampoline's `MutualTailCall` handler now only takes the JIT fast-path
  when `next` is genuinely **self**-tail-recursive (its hot loop is its own self-call,
  which the JIT compiles to a `while`/JMP — the `workload → sumTco` case), instead of for
  any function lacking a non-tail self-call. A non-self cycle stays on the constant-stack
  tree-walk trampoline (correct, ~390 ms). One-line gate in `TcoRuntime.tcoTrampoline`; no
  `VmCompiler` change. Regression `ThreeWayMutualTcoTest` (3-way / 2-way / self-TCO at
  depth ~100k with wall-clock bounds). Spec `specs/interp-three-way-tco-hang.md`.

## 2026-06-12 — fix(interp): dedup module evaluation across a diamond import (busi seq-132)

- The interpreter module loader (`SectionRuntime.runImport`) created a fresh
  `Interpreter` and re-ran the imported module on **every import edge** — no cache. In a
  diamond import (a module reached by two paths — busi: `dispatch.ssc` ~7942 lines
  imported both directly and via an SPI module) the shared module was re-evaluated once
  per DAG path, **exponential in diamond layers** → OOM / hang at load time, 0 lines of
  the program run. `ssc check` was unaffected (the typer memoizes module loads); only the
  interpreter loader re-evaluated.
- Fix: a shared `moduleCache: Map[os.Path, Interpreter]` on `Interpreter`, threaded into
  every child interpreter; `runImport` builds/runs the child via
  `getOrElseUpdate(resolvedPath)`. Each module is now evaluated **once per run** (init
  side effects run once, matching the typer and ES/Python module semantics); each
  importer still merges the shared child's exports into its own tables, so per-importer
  aliasing/registration is unchanged. Regression `InterpModuleDedupTest` (a diamond + a
  3-layer stacked diamond) asserts the shared module's load side effect fires exactly
  once. Spec `specs/interp-module-loader-dedup.md`. Unblocks busi ph-2 modularization.

- Added a new **`scrumban`** skill (`.agents/plugins/scrumban/`) codifying the
  write-before-do discipline: record intended work in `SPRINT.md` (do-soon) /
  `BACKLOG.md` (can-wait) + `specs/` *before* executing it, so a reboot / context
  clear / parallel agent resumes cold from the board, not from a lost context. It is
  agent-independent (plain markdown, any agent can read it).
- Made the whole `.agents/plugins` submodule usable with **zero per-skill install**:
  new `.agents/plugins/AGENTS.md` is a skill *index* (read-on-demand + glob discovery
  so future skills appear automatically), and `.claude-plugin/marketplace.json`
  exposes the same skills as optional Claude-native slash-commands. This project's
  `AGENTS.md` `MANDATORY: required skills` is now a single pointer to that index —
  new skills need no edit here and no installation. Submodule bumped to `bc2a53a`.

## 2026-06-12 — fix(interp/jit): a JIT codegen crash bails to tree-walk, never crashes the program

- Investigating the dev-only "JIT classpath fallback" (the runtime javac can't find
  `scalascript.interpreter.vm.jit.*` under sbt/`runMain` because app classes live in
  a classloader, not `java.class.path` — the **real `bin/ssc` fat jar is unaffected**
  and JITs fine) surfaced a genuine bug it had hidden: the javac JIT is *effectively
  untested by the suite* (it always bails on the classpath there), and on the **fat
  jar** a JIT codegen `StackOverflowError` in `walkLocalSlotCtx` (a `while`
  accumulator calling a recursive fn) **crashed the program** (`[ERROR] null`, exit 1)
  instead of bailing.
- A JIT codegen bug must never crash a valid program. Wrapped every JIT compile
  entry in `try … catch case _: Throwable => null`: `JavacJitBackend.tryCompile`,
  `AsmJitBackend.tryCompile`, and the three while-loop-JIT call sites in
  `EvalRuntime`. The offending shape now bails to tree-walk and prints the correct
  result on the fat jar; the suite stays green (the guards are pure additions with
  existing bail semantics). Follow-ups (in SPRINT): pass the classloader URLs to the
  runtime javac so the JIT runs/tests under sbt; fix the `walkLocalSlotCtx`
  recursion. Spec `specs/jit-crash-safety-and-cli-classpath.md`.

## 2026-06-12 — test(interp): JsGenWsTest read-timeout so a stuck WS server can't hang the gate

- `JsGenWsTest.readHttpLine` did an **unbounded** blocking socket read (`in.read()`
  with no timeout); if the emitted WebSocket server (run under Node) didn't respond,
  the read — and the whole `backendInterpreter/test` run — blocked forever. Added
  `setSoTimeout(15000)` on the client socket in `openWithRetry`, so a non-responding
  server fails this one test fast with a `SocketTimeoutException` instead of hanging
  the gate. It was the only unbounded blocking I/O in the suite. (Confirmed
  separately: a clean full `backendInterpreter/test` run is 1664 green — the recent
  intermittent gate hangs were this environmental risk, not a code failure.)

## 2026-06-12 — perf(interp/jit): JIT-compile supertype type-tests (instead of bailing)

- Follow-up to the seq-124 fix, which **bailed** the JIT to tree-walk on
  `case _: Supertype`. The JIT now **compiles** it on the default `JavacJitBackend`:
  a new `JitGlobals.isSubtype(typeName, target)` runtime parent-chain check lets a
  JIT'd match narrow by a (possibly imported) supertype, so supertype-narrowing
  dispatch stays JIT-fast — directly relevant to large sealed/`enum` hierarchies
  (busi's 168-variant `Event`). A supertype `case _: T` routes the match to the
  if-chain path (statement + expression forms) and emits
  `JitGlobals.isSubtype(inst.typeName(), "T")`; leaf-type type-tests still switch on
  the exact tag. First-match order is preserved (a leaf arm before a supertype arm
  still wins — no case-collision issue). `AsmJitBackend` keeps the correct
  bail-to-tree-walk (Javac is the default; an asm real-compile is a follow-up).
- Tests: 3 hot-loop `BugReproTest` cases (50k calls each — statement, expression,
  leaf-before-supertype ordering) + a `JitLintTest` assertion that the matcher now
  reports `willJit == true` on Javac (it bailed before).

## 2026-06-12 — fix(interp): enum → trait hierarchy resolution across module imports (busi seq-124 / seq-125)

- Follow-up to the same-file seq-120/121 fix: it did not cover busi's real layout
  (types declared in one module, used in another via `[name](path)`). Two further
  gaps, one per symptom:
  - **Trait methods (`e.kind`) cross-module → `No field 'kind'`.** The import merge
    propagated `parentTypes` etc. but **not** `typeMethods`, so concrete methods of
    an imported trait were absent on instances of imported enum-cases. Fixed with
    `Interpreter.exportedTypeMethods` + a per-type union merge in `SectionRuntime`.
  - **Type-test by a supertype (`case _: CoreEvent`) cross-module → `false`.** The
    tree-walk path already worked (`parentTypes` *is* merged), but the matcher
    function gets **JIT-compiled**, and the JIT lowered `case _: T` to an **exact**
    type-tag switch that can never see a subtype instance. (A general JIT gap for any
    JIT'd supertype type-test; only surfaced cross-module because there the matcher
    is compiled — same-file matchers in `main` run once and never JIT.) Both JIT
    backends now **bail to tree-walk** when an arm type-tests a supertype (a type
    with descendants in `parentTypes`); leaf-type type-tests still JIT.
- 2-file regression `EnumTraitCrossModuleTest`, run with the JIT on (default).

## 2026-06-12 — feat(declarative-ui): `formBody` request-body builder for `@ui=toolkit` actions (Scope B.4+)

- A `fetchActionWith` action body can now be a `formBody([field, …])` that assembles
  the POST/PUT body from the **named field signals** at submit, instead of a single
  hand-maintained `Signal[String]`: `fetchActionWith("POST", url, formBody(["customer",
  "amount"]), [onBumpTick(t)])` serialises `{"customer": <sig>, "amount": <sig>}` from
  the live signal values at click. The top-level analog of `RowPayload` for row
  actions; the capability (URL/method) stays in code.
- `fetchActionWith`'s `body` is widened to `Any` so it accepts **either** a
  `Signal[String]` (unchanged, backward-compatible) **or** a `formBody(...)`
  descriptor. Browser-scoped (where the action runs, same as `onSuccess`): the
  descriptor emits `data-ssc-fetch-body-fields` and a testable
  `_ssc_ui_buildFormBody(fieldsJson, sv)` builds the JSON (a missing signal → `""`,
  never `undefined`). The interpreter accepts `formBody` and builds a `FetchAction`
  with a synthetic empty body (assembly is browser-only).
- New `formBody` intrinsic (interp + JS). Tests: `FetchPluginInterpreterTest` +
  `JsGenStdImportTest` (descriptor thread + assembly) + runnable
  `examples/content-form-submit.ssc`.

## 2026-06-12 — fix(interp): enum → trait hierarchy resolution (busi seq-120 / seq-121)

- A runtime type-test by an **intermediate** sealed-trait supertype now matches
  through the whole chain: `case _: TaxEvent` on a `PolishTaxEvent.PolishVatConfigured`
  value returns `true` (was `false`). Root cause: the interpreter recorded only the
  `case → enum` parent link — the enum's and trait's *own* `extends` parents were
  never recorded, so the single-parent chain broke. `recordFirstParent` now records
  it for enums and traits (it already did for classes). (busi seq-120)
- A **concrete method defined on a sealed trait** now dispatches on enum-case /
  case-class instances of its subtypes — `e.kind` where `def kind = this match …`
  lives on the `Event` trait returns the right value (was `No field 'kind'`). Traits
  now register their concrete methods, member dispatch walks the parent-type chain
  (after instance fields, so a field still shadows an inherited method), and **`this`
  (`Term.This`) is now implemented** — bound to the receiver inside any class / enum
  / trait method body (was `Cannot eval: Term.This`). The `this`-binding allocation is
  paid only by bodies that reference `this` (cached). (busi seq-121)
- 6 regression tests in `BugReproTest`; spec `specs/interp-enum-trait-hierarchy.md`.

## 2026-06-12 — feat(declarative-ui): `ssc check` lints `signal:` toolkit references (Scope B.7+)

- The build-time `@ui=toolkit` id lint (`ContentToolkitLint`) now also validates
  `signal:` / `showWhen:` / `enabledWhen:` references, not just `action:` /
  `source:` / `rows:`. A reference is checked against the **signal universe** — the
  union of `contentComputed(id, …)` registrations and the ids declared in a YAML
  `signals:` block (both harvested, so a reference satisfied by a *local* signal
  default never falsely warns). Unknown id with a non-empty universe → a `signal
  '<id>' is not registered` warning (with the same edit-distance "did you mean"
  hint); an empty universe → no warning (conservative). `source:` now also
  recognises `contentDataSource` registrations (Scope B.3). Markdown `toolkit:`
  *link* references remain deferred (they live in raw prose, not the scanned
  CodeBlock).
- Tests: `ContentToolkitLintTest` (+6, core) + `CheckCommandTest` (+2, CLI).

## 2026-06-12 — feat(declarative-ui): slot escape-hatch for `@ui=toolkit` panels (Scope B.6)

- A `{type: slot, id: <id>}` control injects an arbitrary **ScalaScript-authored
  `TkNode`** registered by id with `contentSlot(id, node)` (via the new
  `contentToolkitOptionsWithSlots(...)` builder). It is the inverse of the
  declarative controls — when the YAML vocabulary can't express a widget (a custom
  chart, a bespoke composite), the author builds it in code with the ordinary
  `std/ui` node helpers and drops it into the declarative panel by id (§0 two-way
  coexistence).
- Reuses the existing registry pattern (parity with `actions`/`rowBindings`/
  `computed`): a new `slots: Map[String, Any]` registry on `ContentToolkitOptions`;
  the control resolves `<id>` in `options.slots` and returns the registered node
  **verbatim** (already a built `TkNode`, so it composes into the tree and lowers
  normally — no re-render, no env resolution). Interpreter (`toolkitControl`
  `case "slot"`) + JS (`_ssc_tk_render_control` `case 'slot'` → `_ssc_tk_slot`)
  parity; an unregistered id is a loud error (fail-soft on JS). Existing builders are
  unchanged (`slots` defaults to empty).
- Tests: `ContentPluginInterpreterTest` (+2: verbatim inject + unregistered loud
  error) + `JsGenStdImportTest` + runnable `examples/content-slot.ssc`. **With B.6,
  declarative-ui Scope B (B.1–B.7) is complete.**

## 2026-06-12 — feat(declarative-ui): structured `onSuccess` for `@ui=toolkit` actions (Scope B.4 v1)

- A registered action can now declare a **structured `onSuccess`** effect list run
  in order after a successful (2xx) write, instead of bumping a single tick:
  `fetchActionWith(method, url, body, [onBumpTick(tick), onSetSignal(sig, value),
  onNavigate(path)])`. So a `{type: button, action: <id>}` control can refresh a
  table *and* flash a status signal *and* route on success — declaratively. A failed
  (non-2xx) write runs none of the effects.
- The handler registers via the existing `contentAction(id, …)` and renders through
  the Scope B.1 action path unchanged. Runtime is **browser-scoped** (the one place
  an action executes; also avoids touching `EventHandler.FetchAction`, which is
  pattern-matched in 11 places across six native backends): the JS marker carries
  `onSuccess`, the button descriptor emits `data-ssc-fetch-onsuccess`, and a new
  testable `_ssc_ui_runOnSuccess(effects, ok, setFn, sv)` applies them. The
  interpreter's `fetchActionWith` builds a plain `EventHandler.FetchAction` (first
  `onBumpTick` → `onSuccessTick`).
- New `onBumpTick` / `onSetSignal` / `onNavigate` / `fetchActionWith` intrinsics
  (interp + JS). `bodyBuilder` is deferred (`RowPayload` already covers per-row
  bodies). Tests: `FetchPluginInterpreterTest` + `JsGenStdImportTest` (structural
  thread + `_ssc_ui_runOnSuccess` apply-on-2xx / skip-on-failure) +
  `examples/content-action-onsuccess.ssc`.

## 2026-06-12 — feat(declarative-ui): named data sources for `@ui=toolkit` tables (Scope B.3 v1)

- A `{type: table, source: <id>}` control may now bind to a **named data source**
  registered with `contentDataSource(id, source, columns, actions)` instead of only
  a raw `contentRows` signal. The author declares *how* the rows are produced with a
  small vocabulary: `staticSource(rows)` (in-memory, no fetch — previously needed a
  hand-wrapped signal), `signalSource(sig)` (reactive), and `fetchSource(id, url,
  tick, headers, rowsPath = "")` (a managed GET that re-fetches when `tick` bumps).
  All lower to the existing `TableDataSource` render, so interpreter / JVM / JS
  parity is unchanged.
- `fetchSource`'s `rowsPath` (the new runtime capability) is a dotted envelope path
  (`result.items`) unwrapped before the built-in `{data|rows|items|results}` keys —
  for an API whose rows are nested non-standardly. It is a **browser-runtime**
  concern (the one place a live fetch envelope is unwrapped at run time): the JS
  shim carries it on the fetch signal, the DataTable descriptor emits
  `data-ssc-datatable-rows-path`, and `_ssc_ui_rowsOf(v, rowsPath)` drills the path
  (a wrong path degrades to the default keys, never crashes). The shared
  `TableDataSource` model and the six native frontend backends are untouched.
- New `fetchRowsSource(signal, rowsPath)` intrinsic (interp + JS). Tests:
  `FetchPluginInterpreterTest` (Remote build) + `JsGenStdImportTest` (`_rowsPath`
  thread + `_ssc_ui_rowsOf` drill/fallback/legacy) + runnable
  `examples/content-data-source.ssc`.

## 2026-06-11 — feat(declarative-ui): build-time id-existence lint for `@ui=toolkit` controls (Scope B.7 v1)

- `ssc check` now warns when a `@ui=toolkit` control references an `action:` /
  `source:` / `rows:` id that no `contentAction(...)` / `contentRows(...)`
  registers. Until now a typo'd id (`action: refesh` for a registered `refresh`)
  surfaced only at *render* time — caught fail-soft into an inline error node,
  invisible in CI and a small red box on a live SPA. It is now caught at build
  time (same spirit as the `examples` smoke-test: silently-broken things must be
  caught by tooling).
- New core pass `scalascript.transform.ContentToolkitLint` (mirrors
  `MarkupInterpolatorCheck`): pure, AST-only — it harvests id *strings* from the
  parsed module (references from `@ui=toolkit` blocks' YAML `source`; registrations
  from `contentAction`/`contentRows` calls in scala trees), never re-rendering
  controls, the interpreter, or the plugin YAML parser. The CLI unions
  registrations across the entry module's transitively-imported `.ssc` modules.
- **Conservative — warnings only, near-zero false positives:** a reference is
  flagged only when its registry is non-empty somewhere in the reachable graph and
  the id is absent (a dynamic/external registry that registers nothing statically
  never warns); a *local* (non-std/library) import that fails to resolve suppresses
  the lint for that file. Warnings carry the file-level YAML line and never change
  the exit code (`OK (with warnings)`). An edit-distance "did you mean '…'?" hint is
  added for a plausible typo. v1 scope is `action`/`source`/`rows`;
  `signal:`/`showWhen:`/`enabledWhen:` and Markdown `toolkit:` links are deferred.
- Tests: `ContentToolkitLintTest` (11, core) + 2 `CheckCommandTest` cases (CLI).

## 2026-06-11 — feat(declarative-ui): typed inline columns in `@ui=toolkit` YAML tables (Scope B.2)

- A `{type: table, source: <id>}` control may now declare its columns inline via a
  `columns:` list of typed specs instead of inheriting the registered source's
  columns. Each spec carries `kind: text|date|money|status|link` (default `text`),
  plus `label`/`path`/`align` and per-kind options (`format` for date, `currency`/
  `locale` for money, `url` for link, `colors:` map for status). The specs lower
  through the very same `fieldColumn`/`dateColumn`/`moneyColumn`/`statusColumn`/
  `linkColumn` builders that code uses — so Markdown-declared columns are byte-for-byte
  the native column values, never a parallel re-implementation.
- New SPI: `IntrinsicImpl.resolveGlobal(name)` (default `None`; overridden in the
  interpreter to look up a top-level global) lets a plugin native reuse another
  plugin's registered intrinsic. The content toolkit resolves the column builders by
  name and drives them with `invokeCallback`, so a missing `import` of `std/ui/data`
  fails loudly with a pointer to `fcol/mcol/scol/dcol/lcol`. Mirrored on the JS
  backend by `_ssc_tk_build_columns` reusing the emitted `_ssc_ui_*Column` runtime.
- `examples/content-toolkit-yaml-controls.ssc` now declares its invoice table's
  columns inline (incl. a `money` column with `currency: PLN`).

## 2026-06-11 — feat(rust): type ascription, for-do loops, match-case guards

- Three bounded Rust-backend control-flow gaps (found by probing), each lowering to
  a native Rust construct: **type ascription** `expr: T` (emit the inner expr, drop
  the annotation), **statement-form `for x <- a to b do body`** (→ `for x in range {
  body }`; for-yield already worked, for-do did not — verified `sum += i` over 1..=5
  gives 15 via cargo run), and **match-case guards** `case p if cond =>` (→ Rust
  match-arm guard). backendRust 194 green + RustGenControlFlowTest (3). (Probing also
  confirmed HOF/closures, Option/List methods, default params, tuple destructuring,
  Either.map already work on Rust.)

## 2026-06-11 — feat(rust): curried / multi-parameter-group functions

- The Rust backend rejected any `def f(a)(b)` with "multiple parameter groups; R.2
  accepts a single group", and a curried call `f(a)(b)` reported "no resolvable
  name" because the callee was a nested `Term.Apply`. Now multi-group defs flatten
  into a single Rust `fn` signature (the param-flatten already existed; only the
  ">1 group" guard was dropped in `renderParams`/`renderMutParams`), and a new
  `renderTerm` case flattens the curried call chain into a single-group synthetic
  `Apply` and recurses (distinct from method chains, whose callee is a `Term.Select`).
  Verified: 2- and 3-group curried defs + calls emit and `cargo build` clean;
  `backendRust` 191 green + `RustGenCurriedDefsTest` (3). The `using`-evidence /
  typeclass case is a separate follow-up — it still needs typeclass→trait
  monomorphisation since `Show[A]` isn't a mappable Rust type — but the
  multi-group plumbing it depends on is now in place.

## 2026-06-11 — test(examples): smoke test so silently-broken examples are caught (ExamplesSmokeTest)

- Adds `ExamplesSmokeTest` (cli) with two guards: (1) a **lint** over every
  `examples/` `.ssc` asserting runnable scala lives inside a ```` ```scalascript ````
  fence (a fenceless `.ssc` is prose by design → `ssc run` silently no-ops; this is
  exactly the mistake that shipped 9 fenceless examples undetected). The matcher is
  structural (`import a.b.*` to EOL, `case class Capitalized(`, `val x =/:`, …) so
  markdown prose starting with "import"/"case class" doesn't false-positive. (2) a
  **run** check that two dependency-free core examples (`hello.ssc`, `script.ssc`)
  actually execute via the in-process interpreter and exit 0. Closes the gap the
  user flagged: "то что результата нет должно проверяться тестами".

## 2026-06-11 — fix(examples): add missing scalascript fence to 9 runnable examples

- Nine `examples/*.ssc` were raw scala with no ```` ```scalascript ```` fence, so
  the parser treated them as prose and `ssc run` silently did nothing (fences are
  by design — code lives in a fenced block, fenceless content is prose). Wrapped
  the code in a `scalascript` fence: `crypto-demo`, `crypto-encrypt-demo`,
  `crypto-verify-demo`, `invoice-email`, `invoice-pdf`, `pdf-extract-demo`,
  `script` (shebang kept outside the fence), `uuid-v7`, and
  `graph-rdf4j-http-storage` (fence after its YAML front-matter). Verified: the
  self-contained ones run (`pdf-extract-demo` → `pages: 1` + extracted
  `PIT-11 / Jan Kowalski / 84210.00`); all nine parse clean. The other 33 bare
  `examples/*.ssc` are declarative front-matter modules (routes/graphs/deploy) with
  no scala body — correctly left as-is.

## 2026-06-11 — docs: reconcile stale feature queue (std.pdf, smtp-send, pdf-mime already shipped)

- Verified-and-closed several SPRINT/BACKLOG items that parallel agents had
  already implemented but never marked done: **std.pdf reader** (pdf-p1..p5 —
  `pdfToMarkdown`/`pdfPageCount` ship in `pdf-plugin` + `runtime/std/pdf-gen.ssc`
  under a base64-`String` API rather than the queue's `List[Int]`), **smtp-send**
  (`smtp-plugin` + `runtime/std/smtp.ssc`), **pdf-mime-generation**
  (`htmlToPdfBase64` + `buildMimeMessage` in `pdf-plugin`/`runtime/std/mime.ssc`).
  Docs-only. Separately flagged a quality finding: 42/175 `examples/*.ssc` are bare
  scala with no ```` ```scalascript ```` fence (e.g. `pdf-extract-demo.ssc`,
  `crypto-demo.ssc`), so `ssc run` parses them as prose and silently does nothing
  (exit 0, no output) — not test-covered. Fix direction (auto-run bare scala vs add
  fences vs fail-loudly on no-runnable-code) is an open decision.

## 2026-06-11 — fix(cli): parse errors fail loudly on run/compile; close 2 stale bugs

- **parser-robustness-npe** — a scalascript code block that fails to parse was
  silently dropped by the `run`/`compile` dispatch (`compileViaBackend` ran partial
  IR → no output), the busi-reported "interpreter hangs with no message". The parser
  already produces a structured `file:line:col` diagnostic and scalameta no longer
  NPEs on the two repro shapes (bare `\"` in arg position; unbalanced parens in deep
  `jObj(jField(...))` nesting); the gap was that `compileViaBackend` never surfaced
  it. It now calls `reportCodeBlockParseErrors` after `loadModule` and returns
  `CompileResult.Failed`, so `ssc run`/`compile`/`emit` exit non-zero with the
  diagnostic (`ssc check` already exited 2). `ParserNpeDiagnosticTest` + 2
  parser-level cases; CLI suite 335 green.
- **foreach-var-mutation** — closed as stale (verified): `xs.foreach(x => outerVar =
  …)` now propagates on interp/jvm/js (`sum=10`), fixed by an earlier interpreter
  change. No new code.

## 2026-06-11 — feat(sql): PostgreSQL LISTEN/NOTIFY receive side — Db.pgListen / Db.getNotifications (pg-listen-notify-extern)

- **pg-listen-notify-extern** (busi df-6, rozum seq-115) — the NOTIFY publish side
  already worked (`Db.query("db","SELECT pg_notify(?,?)",…)`); the receive side needs
  a held connection drained for notifications, which the stateless `Db.query` can't
  express. Added `Db.pgListen(db, channel)` / `Db.unlisten(db, channel)` /
  `Db.getNotifications(db[, timeoutMs])` in `SqlIntrinsics`: they operate on the
  connection `ConnectionRegistry` already caches per database name, so `LISTEN` on it
  and a later `getNotifications` on the same db name drain what arrived. Each
  notification is a `Map { channel, payload, pid }`; `timeoutMs` blocks up to that
  long (0/omitted = non-blocking). PostgreSQL-only — a clear error on a non-PG
  connection; channel names are quoted (case-exact, injection-safe). Enabled by
  `pg-jar-installbin` putting the postgres driver on the classpath. `BuiltinsRuntime`
  now assembles the `Db` namespace object by collecting **all** `Db.*` natives
  generically (was a hardcoded query/execute/insert/update list), so future `Db.*`
  intrinsics need no core change. Example `examples/pg-listen-notify.ssc` (requires a
  running Postgres — H2/SQLite have no LISTEN/NOTIFY, so the receive path is verified
  by busi against real Postgres; tests lock native registration + the PG-only guard).

## 2026-06-11 — fix(sql): bundle the PostgreSQL driver (+ HikariCP) so `jdbc:postgresql:` works out of the box (pg-jar-installbin)

- **pg-jar-installbin** (busi df-6, rozum seq-115) — `ssc` bundled only the H2 +
  SQLite JDBC drivers in `bin/lib/jars/`, so a fresh binary threw **"No suitable
  driver"** on `jdbc:postgresql:` even though `backend/postgres` is first-class;
  busi had to manually stage `postgresql-42.7.3.jar`. Fix: added
  `org.postgresql:postgresql:42.7.3` + `com.zaxxer:HikariCP:5.1.0` to
  `backendSqlRuntime` (`backend/sql`), alongside the existing H2/SQLite — those are
  the runtime deps `installBin` stages into `bin/lib/jars/`, so the Postgres driver
  is now on the launcher classpath and JDBC4-auto-registers. Verified end-to-end:
  `DriverManager.getConnection("jdbc:postgresql://…")` from the staged classpath now
  fails with a *connection* error (refused), not "No suitable driver". `installBin`
  stages `postgresql-42.7.3.jar` + `HikariCP-5.1.0.jar`; backendSqlRuntime (91) +
  clientPostgres (26) + sqlPlugin (4) green.
## 2026-06-11 — fix(cli): parse errors fail loudly on run/compile; close 2 stale bugs

- **parser-robustness-npe** — a scalascript code block that fails to parse was
  silently dropped by the `run`/`compile` dispatch (`compileViaBackend` ran partial
  IR → no output), the busi-reported "interpreter hangs with no message". The parser
  already produces a structured `file:line:col` diagnostic and scalameta no longer
  NPEs on the two repro shapes (bare `\"` in arg position; unbalanced parens in deep
  `jObj(jField(...))` nesting); the gap was that `compileViaBackend` never surfaced
  it. It now calls `reportCodeBlockParseErrors` after `loadModule` and returns
  `CompileResult.Failed`, so `ssc run`/`compile`/`emit` exit non-zero with the
  diagnostic (`ssc check` already exited 2). `ParserNpeDiagnosticTest` + 2
  parser-level cases; CLI suite 335 green.
- **foreach-var-mutation** — closed as stale (verified): `xs.foreach(x => outerVar =
  …)` now propagates on interp/jvm/js (`sum=10`), fixed by an earlier interpreter
  change. No new code.

## 2026-06-11 — verify+close: two stale perf items (ASM ADT-builder parity, bench honesty pass)

- **interp-opt-recursive-build-floor-asm-parity** — closed by discovery +
  verification (no new code). The `AsmJitBackend.tryCompileLongToObject` pure
  ADT-builder path already landed after the item was written; verified `build`/
  `eval` lint `[JIT OK]` under `SSC_JIT_BACKEND=asm` and JMH
  `scripts/bench interp recursiveEval$` = **0.067 ms/op** on ASM (was the stale
  2.106; matches the Javac Phase-1B floor).
- **bench honesty pass (cross-backend-gap item 2)** — was marked BLOCKED on
  `Bench.opaque`; resolved this cycle by `bench-honest-corpus-seed` (the named
  varying-data alternative). With these two, the cross-backend-gap perf program
  (js-numeric-inference, JS-HAMT, const-prop, AOT hoist/mutual-TCO, T3
  object/tuple construction, bench honesty) has no open perf items; remaining
  BACKLOG perf entries (`vectorize-pure-loop`, `direct-style-eval`, range-sum
  fusion) are explicitly deferred/low-ROI.

## 2026-06-11 — perf(interp): JIT tuple ops (construction, t._n, ++ concat) — T3 complete (interp-jit-object-construct)

- **interp-jit-object-construct (tuple)** — tuple operations in hot loops bailed
  the whole function to tree-walk (tuple-monoid corpus: 962 ms, JIT contributing
  nothing). Added tuple construction (`newTupleRef`), numeric element access
  (`tupleIntElem`, placed before the ADT-field case), and `++` concat (TupleV in
  `collectionConcat`) to **both** JavacJitBackend and AsmJitBackend; `isRefValRhs`
  recognises `Term.Tuple`/`++`. **Root cause of the residual bail:** the parser
  lowers `tupleA ++ (3, 4)` to a `Term.ApplyInfix` whose arg clause is the
  *multi-arg* list `[3, 4]` (the RHS tuple literal becomes positional args), so the
  old one-arg-only `++` handling rejected the shape; the `++` case now rebuilds a
  TupleV when `nargs != 1` and matches by type test. **tuple-monoid: 962 → 2.13 ms
  javac (~450×) / 4.03 ms asm (~240×)**, result identical to tree-walk; suite 1635
  green on both backends. With case-class construction (shipped earlier), T3 is
  complete: object/tuple construction in hot loops no longer bails. Spec:
  `specs/backend-perf-gaps.md` §T3.

## 2026-06-11 — feat(declarative-ui): Scope B.5 — registerComputed (code-built signals referenced by id in YAML)

- **declarative-ui-scope-b-p2** — Scope B.5 of busi's declarative-dynamic-UI
  proposal. A code-built derived signal registered via `contentComputed(id, sig)`
  (→ `ContentToolkitOptions.computed` / the `computed =` builder arg) is merged into
  the `@ui=toolkit` signal environment, so a YAML control can reference it by id —
  `{type: signalText, signal: <id>}`, `showWhen: <id>`, `enabledWhen: <id>`. Unlike
  the YAML `signals:` block (scalar defaults only), these can be `computedSignal`s
  derived from other signals (a formatted KPI, `isOwner`). The computed registry
  merges *under* the markdown/YAML signals (a locally-declared signal of the same
  name wins). Interpreter (`toolkitEnvFor` merges `options.computed ++ base.signals`
  at every env-build site) + JS (`_ssc_tk_env`) at parity, reusing the existing
  `signalRef` resolution. Example `examples/content-toolkit-yaml-controls.ssc`
  (extended); interpreter + JS regression tests; spec `specs/declarative-ui-scope-b.md`.
  Spec also records that **B.2 (typed inline columns) is blocked** on a column-model
  decoupling prerequisite (`FieldColumnDef` lives in `fetch-plugin`, the toolkit in
  `content-plugin`).

## 2026-06-11 — feat(declarative-ui): Scope B.1 — YAML control-tree registry resolution

- **declarative-ui-scope-b-p1** — first slice of busi's declarative-dynamic-UI
  proposal Scope B (rozum seq-113). Brings the Scope A `action` / `rowBindings`
  registry resolution into the `@ui=toolkit` **YAML control tree**, not just the
  Markdown `toolkit:` links: `{type: button, action: <id>}` → `ActionButtonNode`
  (honors `enabledWhen`) and `{type: table, source: <id>}` (alias `rows:`) → live
  `DataTableNode` from the registered `ContentRowBinding`. Interpreter
  (`ContentIntrinsics.toolkitControl` / `toolkitButton` + new `table` case; also
  fixed `toolkitUiNode` to forward `rowBindings` into the YAML env, not just
  `actions`) + JS (`ContentToolkitJs._ssc_tk_render_control`, with `options`
  threaded through the YAML control path) at parity; reuses the shared
  `lower.ssc` nodes and Scope A helpers. Fail-soft still applies. Example
  `examples/content-toolkit-yaml-controls.ssc`; interpreter + JS regression tests;
  spec `specs/declarative-ui-scope-b.md` (with the B.2–B.7 slice plan). Remaining
  Scope B: typed inline columns, `registerDataSource`/`registerAction` onSuccess,
  `registerComputed`, slot, build-time lint, JvmGen parity.

## 2026-06-11 — fix(js): reorder named args to imported functions / case-class ctors (js-named-arg-imported-reorder)

- **js-named-arg-imported-reorder** — fixes the latent JsGen bug found while
  landing `js-toolkit-action-rows-registry`: a named-arg call to an imported
  function or case-class constructor on emit-js/emit-spa was emitted positionally
  in *written* order, silently landing values in the wrong fields. Two root
  causes: (1) the param-order pre-pass's `collectDefs` only scanned top-level
  statements, so a `package:` module (compiled to one wrapping `Defn.Object`)
  contributed nothing; (2) imported modules never ran the pre-pass. Fix: new
  `collectParamOrdersFromModule` descends into namespace objects + records
  case-class primary ctors into a separate `importedParamOrder` map, populated for
  every imported module in `genImport` and shared with the child gen; the named-arg
  reorder consults it. Kept **off** the direct-call gate (`funcParamOrder` only) so
  imported calls still go through `_call` — no regression. Regression test in
  `JsGenStdImportTest`; full backendInterpreter suite green. Unblocks named-arg
  option construction on emit-spa (busi can now write `contentToolkitOptionsWith…
  (…, rowBindings = …)` directly).

## 2026-06-11 — feat(js-toolkit): action=/rows= registry parity + fail-soft (declarative-ui Scope A)

- **js-toolkit-action-rows-registry** — Scope A of busi's declarative-dynamic-UI
  proposal (rozum seq-113): browser parity for the *existing* content-toolkit
  `action` / `rowBindings` registries. `ContentToolkitJs` now resolves
  `toolkit:button?action=<id>` → `ActionButtonNode` (bound to the registered
  handler, honoring `&disabled`/`&enabledWhen`) and `toolkit:table?rows=<id>` →
  `DataTableNode` (from the registered `ContentRowBinding`), matching the
  interpreter; both lower through the shared `std/ui/lower.ssc`. Added **fail-soft**:
  toolkit render errors degrade to an inline error node (block / entry-fn
  granularity) instead of throwing, so a bad id no longer blanks the SPA
  (busi seq-102 class). `content.ssc` option builders now construct positionally
  (the JS backend mis-orders named args to imported case-class constructors — see
  Known issues in BACKLOG). Spec `specs/js-toolkit-action-rows-registry.md`;
  2 regression tests in `JsGenStdImportTest`; full backendInterpreter suite green
  (1635). Non-goals (Scope B): typed YAML `table:`/`source:`/`action:` keys,
  `registerDataSource` shape/rowsPath, `onSuccess` vocabulary, slot, build-time
  lint, JvmGen parity.

## 2026-06-11 — perf(interp): JIT case-class construction in loop bodies (interp-jit-object-construct, T3 partial)

- **interp-jit-object-construct (case-class)** — the interpreter bytecode JIT
  bailed any loop whose body constructs a user case class (`val v = Vec(x, y)`)
  to slow tree-walk; the honest `instance-field` corpus exposed this at 57 ms
  (on==off). `walkRef`/`isRefValRhs` only knew builtin List/Set/Map ctors, so a
  user-ADT ctor was an unresolvable free name. Added `JitRefDispatch.newInstanceRef`
  (builds an InstanceV with positional fieldsArr+fieldNames+typeTag) and a walkRef
  case-class case in **both** JavacJitBackend and AsmJitBackend (ASM reuses its
  emitConstructorObject). Builtin ADTs registered in `typeFieldOrder`
  (Some/None/Right/Left/collections) are excluded so they keep their dedicated
  OptionV/EitherV + HOF dispatch. **instance-field: 57 → 0.267 ms javac (213×),
  57 → 0.767 ms asm (74×)**; result identical to tree-walk; backendInterpreter
  suite 1633 green on both backends. tuple-monoid (tuple literal + `++`) remains a
  follow-up. Spec: `specs/backend-perf-gaps.md` §T3.

## 2026-06-11 — bench(honesty): de-fold the corpus wall-table via carried-LCG seed (bench-honest-corpus-seed, Tier 2 / T2.1 follow-up)

- **bench-honest-corpus-seed** — closes the optional T2.1 follow-up: the
  cross-language wall table (`bench/run.sc` → `ssc bench --machine`) still folded
  six `bench/corpus` cells to sub-nanosecond compiled numbers (C2/LLVM/V8
  constant-folded the pure, zero-input workloads). The sink defeats only the
  outer timing loop; a one-shot seed leaves the inner loop closed-form; a linear
  carried recurrence is scalar-evolved. **Fix:** each of `instance-field`,
  `tuple-monoid`, `bool-predicate`, `either-chain`, `option-chain`,
  `literal-match` now advances a non-linear carried **64-bit LCG** and consumes
  every result, so no backend can closed-form or DSE it. `BenchCmd.generateWrapper`
  is arity-aware and feeds an **opaque** seed (JVM `_ssc_sink.get()` atomic load;
  interp/JS a monotonic `_ssc_seed`); `bench/run.sc` passes the opaque rust seed
  `_s`. After (M1): jvm `tuple-monoid` 2 ps → 0.087 ms, `instance-field` 0.32 µs →
  6 µs, `bool-predicate` 19 ns → 0.81 µs; all 5 backends now run real work and
  the rust column is restored. The honest workloads keep their no-arg signature.
  Idiom: `docs/bench/corpus-antifold.md`. Also surfaced + fixed an **emit-rust
  `.toInt`** correctness bug (emitted `as i32` for a value typed `i64` as `Int`
  → E0308; now `as i32 as i64`). These honest numbers expose real interpreter
  gaps (instance-field ~57 ms, tuple-monoid ~960 ms) tracked under T3.

## 2026-06-11 — perf(interp): curried-method dispatch + summon-key + field-access alloc cuts

- **interp-curried-method-dispatch** — Follow-up to the typeclass-fold
  using-cache (`f1917d2ca`). JFR on the honest `combineAll[A: Monoid]` bench
  showed the remaining ~84% was tree-walk *dispatch* overhead (not given
  resolution, not the JITed per-element combine). Three contained `EvalRuntime`
  fixes: (1) curried-method fast-path — `recv.m(a)(b)` (e.g. `xs.foldLeft(z)(op)`)
  routes straight to `evalApplyGeneral` instead of walking all ~40 curried
  special-form extractors whose inner `Term.Apply.unapply` allocated a Tuple2
  each call (the dominant 740 MB/op allocator); (2) `summonKeyCache` — caches the
  per-node `summon[TC[T]]` lookup strings (`"Monoid[A]"` + synthetic `"A$Monoid"`);
  (3) `Term.Select` no-arg field access converted from extractor to type-test
  (kills Tuple2+Some per `a.b`). A/B (`scripts/bench interp typeclassFold`):
  `typeclassFoldMacro` 1.722 → 1.323 ms/op (−23%), alloc 394 → 138 KB/op (−65%);
  no hot-path regression (recursionFib 1.218 ms = baseline). Broad win — lifts
  every curried method call and every no-arg field access. Suite 1629 green.

## 2026-06-11 — fix(js): transitive content-block registration + lookup (busi seq-102)

- **js-content-toolkit-transitive-register** (busi seq-102, follow-up to
  `js-content-toolkit-transitive`) — After the emission gate became transitive the
  toolkit runtime emitted, but a block authored in a transitively-imported module
  (`app.ssc → rulepack_studio.ssc`, `@id=studio-preview @ui=toolkit`) still threw
  `contentToolkitBlock: no block with id`. Two gaps: (1) registration was
  direct-only — `collectDirectImportedContent` → `collectImportedContent` now walks
  the transitive import graph (cycle-protected, child-relative resolution) so every
  module's content document is registered; (2) lookup was entry-document-only —
  `contentToolkitBlock`/`Section` now search the entry document first, then fall
  back across all imported documents (`_ssc_tk_find_block`/`_section`),
  approximating the interpreter's per-calling-module resolution in the flattened JS
  bundle. Fixture `examples/content-toolkit-transitive-register/` + regression test
  in `JsGenStdImportTest` renders the child-owned block to the DOM. Known limit:
  on a duplicate id across entry+import, the entry wins for all callers.

## 2026-06-11 — emit-js transitive imports verified + regression guards (busi-p2)

- **busi-p2-emit-js-transitive-imports** (no longer reproduces, confirmed on
  busi rulepack-graph) — The reported
  drop of transitive imports through `emit-js` (`A → B → C`) does not reproduce
  on the current backend: `genImport` recurses into a child module's own
  imports and imported modules are emitted in full (child `JsGen` carries no
  `reachableNames`, so tree-shaking only prunes the entry module's own
  declarations, never transitively-imported ones). Verified end-to-end through
  the exact `emit-js` path (`generateSegmented` with tree-shaking ON + the
  per-segment `_output` flush) for three shapes — package `A→B→C`, name-only
  `A→B→C`, and 4-level `A→B→C→D` — each prints its transitively-computed
  result. Added regression guards in `JsGenStdImportTest` with fixtures under
  `examples/js-transitive-iife{,-nopkg,-4}/`. busi confirmed (rozum seq-110):
  `ssc emit-js web/app.ssc` on their deepest real graph (616 KB bundle) loads
  clean under node with zero `ReferenceError` — closed. Their graph uses only
  `[name](path)` imports; wildcard / re-export in emit-js scope would need a
  synthetic repro.

## 2026-06-11 — feat(crypto): sha256OfBase64 + byteLengthUtf8 (busi seq-100 KSeF)

- **crypto-sha256-of-base64** (busi seq-100) — Two byte-oriented `std.crypto`
  externs closing the last KSeF 2.0 invoice-POST `"stub"` fields.
  `sha256OfBase64(b64)` takes SHA-256 over the **raw bytes decoded from a base64
  string** (returned base64), not the UTF-8 bytes of the string itself like
  `sha256Base64` — KSeF `encryptedInvoiceHash` is `base64(SHA-256(ciphertext))`
  and the ciphertext is carried as base64, so the digest must run over the decoded
  bytes; throws on malformed base64 (authoring-side input). `byteLengthUtf8(s)`
  returns the UTF-8 byte count (not `s.length`) for `invoiceSize` /
  `encryptedInvoiceSize`, where multi-byte characters make the char count wrong.
  Added to `crypto-plugin` intrinsics + `runtime/std/crypto.ssc` surface; 8 tests
  (decoded-bytes equivalence, JCE cross-check, multi-byte divergence, malformed
  throw, UTF-8 byte count). JVM/interpreter only.

## 2026-06-11 — fix(js): hash-tolerant eqSignal for browser routing (js-routing-showsignal-hash)

- **js-routing-showsignal-hash** (busi seq-94) — Hand-rolled hash routing
  (`showSignal(eqSignal(hashSignal(), "#/a"), pageA, fallback)`) kept the matched
  branch hidden (`display:none`) at `hash=#/a`. `hashSignal()` strips the leading
  `#` (`"/a"`) — the convention `hashRouter` relies on — but the user compares the
  URL form (`"#/a"`) written in an `<a href>`, so `eqSignal("/a", "#/a")` was
  always false (the subscription/recompute machinery was correct). Made
  `_ssc_ui_eqSignal` hash-tolerant via `_ssc_ui_hashEq`: normalise one leading `#`
  on both operands before comparing, so `"#/a"` and `"/a"` match for both
  hand-rolled routing and `hashRouter`. Only ever turns a `"#x"`-vs-`"x"` mismatch
  into a match — never breaks an existing match; non-string / non-`#` values pass
  through (tab keys, plain paths unaffected). Regression test in
  `JsGenStdImportTest`; spec `specs/js-routing-hash-eq.md`.

## 2026-06-11 — fix(interp): try/catch with supertype patterns catches extern throws (busi)

- **try-catch-supertype-patterns** — A ssc `try/catch` did not catch a Java
  exception thrown by an extern/runtime op when the catch used a supertype
  pattern (`case e: Any` / `Throwable` / `Exception`) — e.g. `aesCbcDecrypt`'s
  padding error escaped. `Term.Try` synthesizes a `Value.InstanceV(<jvm-exception
  -simple-name>, {message})`, but `PatternRuntime` `Pat.Typed` only matched on the
  exact type name, so `Any` and the exception supertypes never matched → rethrow.
  Fix: `Any`/`AnyRef` are universal supertypes (match any scrutinee);
  `Throwable`/`Exception`/`RuntimeException`/`Error` match any `InstanceV`. Specific
  user types still discriminate. Spec `specs/try-catch-supertype-patterns.md`; 4
  tests; full interpreter suite green (1627).

## 2026-06-11 — perf(interp): FunV-local monomorphic using-resolution cache (interp-typeclass-fold-devirt)

- **interp-typeclass-fold-devirt** — Closed the last deferred perf item. JFR
  pinpointed the dominant cost of `combineAll[A: Monoid](xs)` (called 300×): the
  **call-site `resolveUsing`** (`GivenRuntime.concretizeUsingKey` →
  `matchTypeParts`/`splitTopLevel`/`applyTypeBindings`) re-derives `A→Int`
  identically every call — **47% of allocation, ~16% of CPU**. (A prior attempt
  was reverted with no win because its `(FunV, argTypeSig)` global-map memo
  allocated a key ≈ what it saved.) Fix: a single-entry monomorphic cache on
  `FunV.usingResolveCache` keyed on a cheap arg type-signature (`runtimeValueType`
  of the regular args), applied only on the standard call path (resolves against
  `f.closure`; the instance-method path, which resolves against a per-instance
  frame, is left uncached), with a `givenFactories.size` generation guard. A/B (16
  measurements each): **1.745 ± 0.018 → 1.667 ± 0.016 ms/op (−4.5%,
  non-overlapping)**; allocation **823 KB → 386 KB/op (−53%)**. Full
  `backendInterpreter/test` 1619 green. The remaining cost (~84%, general
  tree-walk eval of the generic HOF) would need JIT-compiling `combineAll` —
  deliberately not pursued (deep, marginal). SPRINT `interp-typeclass-fold-devirt`.
## 2026-06-11 — fix(js): emit content-toolkit runtime for transitive imports (js-content-toolkit-transitive)

- **js-content-toolkit-transitive** (busi seq-92 #2) — Follow-up to
  `js-content-toolkit-natives`. The content/toolkit emission gates scanned only
  the top module, so when `std/ui/content` was imported (and `contentToolkitBlock`
  called) in a **transitively**-imported module (`app.ssc → rulepack_studio.ssc →
  [contentToolkitBlock](std/ui/content.ssc)`), the runtime was not emitted and the
  transitive call site threw `ReferenceError` — despite the natives existing.
  Added `scanContentUsage`: walks the `.ssc` import graph once (cycle-protected,
  short-circuiting, each module resolved relative to its own dir) and reports
  whether any module uses content intrinsics / imports the toolkit; both
  `genModule` paths gate on the transitive result. Fixture
  `examples/content-toolkit-transitive/` + regression test in `JsGenStdImportTest`;
  spec `specs/js-content-toolkit-natives.md` §Transitive imports.

## 2026-06-11 — feat(std.crypto): sha256Base64 — base64 SHA-256 digest (busi KSeF invoiceHash)

- **sha256Base64** — KSeF 2.0 `invoiceHash` / `encryptedInvoiceHash` carry the raw
  SHA-256 digest base64-encoded; `sha256` returns hex and there's no hex→bytes
  path in .ssc, so those fields stayed `"stub"`. `sha256Base64(input)` returns
  base64 of the raw 32-byte digest directly. 3 tests (known empty/`abc` base64
  vectors + hex/base64 consistency). JVM/interpreter via crypto-plugin.

## 2026-06-11 — feat(js): content-toolkit natives in the browser backend (js-content-toolkit-natives)

- **js-content-toolkit-natives** (busi seq-87 cluster-2) — The `std/ui/content`
  toolkit externs (`contentToolkitNode` / `contentToolkitBlock` /
  `contentToolkitSection`) were undefined in the JS backend, so JsGen's extern
  guard bound them to `undefined` and a call (Rule Pack Studio init) threw `not
  callable`. Ported JvmGen's `_ssc_tk_*` content-toolkit runtime to JS
  (`ContentToolkitJs`), emitted by `JsGen.emitContentToolkitRuntime` (gated by a
  `std/ui/content` import). Renders authored Markdown content — `toolkit:` control
  links, `@ui=toolkit` control trees, GFM tables, and `component=` registries —
  into a TkNode tree (`{_type:'<Name>', …}`) that `lower()` consumes; reuses the
  existing `__ssc_content_*` + `contentDocument`/`contentData`/`contentBind`
  helpers and triggers the Signals capability. Toolkit names bind to the bare
  emitted function (import special-case, like the std/content natives). Parity
  with the JvmGen toolkit; 3a/3b action/row registries remain a follow-up (also
  absent from JvmGen). Tests in `JsGenStdImportTest` (emit + full node render +
  `markdown-toolkit-links` example); spec `specs/js-content-toolkit-natives.md`.

## 2026-06-11 — fix(js): normalise DataTable fetch response into rows (js-datatable-remote-envelope)

- **js-datatable-remote-envelope** (busi seq-87) — Once the SPA mounted (after the
  `js-backend-ui-render-gaps` fixes), Remote-source tables
  (`dataTable(fetchUrlSignal(url), cols)`) rendered empty with two console errors:
  `(rows || []).forEach is not a function` (the mount's `doFetch()` called
  `renderTable` directly on `r.json()`, but list endpoints answer either a bare
  array or an envelope `{"data":[…],"count":N}`) and `Unexpected token '<'`
  (`r.json()` on a misrouted path returning the SPA's own HTML). Added a shared,
  unit-testable `_ssc_ui_rowsOf(v)` that coerces a bare array, a JSON string, or a
  `{data|rows|items|results:[...]}` envelope into an array (object-without-list /
  HTML / null → `[]`). `doFetch` now reads `r.text()`, normalises, and `.catch`es
  to `[]`; the static and signal-rows paths route through the same helper, so
  `renderTable` never sees a non-array. Test in `JsGenStdImportTest`; spec
  `specs/js-backend-ui-render-gaps.md` §Layer 3.
## 2026-06-11 — fix(server): concurrent servers in one process (busi federation regression)

- **concurrent-servers** — Two `serveAsync`/`startServer` on different ports in
  one process: the second never bound (ConnectException), breaking busi's
  federation A↔B peer ceremony. Root cause: `WebServer` used a cached singleton
  `HttpServerSpi` backend whose `start` short-circuits on `if _running then
  return`, so the second concurrent start no-op'd while `onBound` still fired
  (serveAsync falsely reported success). Latent; the block-until-bind rewrite
  (`0bf9edc71`) exposed it by serializing the starts. Fix: each server gets a
  fresh backend instance (`HttpServerSpi.fresh()` / `HttpServerBackends.
  freshInstance()`) + its own serve-loop latch; `WebServer.stop()` tears down all
  (idempotent). Interpreter serving path (busi's federation runs here); JvmGen
  `ProxyRuntime` parity is a noted follow-up. Spec `specs/concurrent-servers.md`;
  ServeAsyncReadyTest +concurrent-bind case; suites green (interp-server 50, SPI
  9, runtimeServerJvm 30).

## 2026-06-11 — fix(js): serve non-WebSocket upgrades as HTTP/1.1 — JVM↔JS cluster test PASSES (cluster-jvm-js-handshake, Tier 3 / T3.1 DONE)

- **cluster-jvm-js-handshake** — Closed the suite's last disabled test. The JS-codegen
  HTTP/WS server hung on **non-WebSocket upgrade requests**: `java.net.http` (the test's
  poll client) defaults to HTTP/2 and probes cleartext with `Upgrade: h2c`; Node routes
  any `Connection: Upgrade` to the 'upgrade' handler, so `/_ssc-cluster/status` GETs hit
  `_wsHandleUpgrade`, matched no WS route, and the socket hung with no response → status
  polls timed out (instrumented: 60 TCP accepts, 0 `'request'` events, event loop alive).
  Fix (`JsRuntimePart1d`): the 'upgrade' listener now serves any non-`websocket` upgrade
  as a normal HTTP/1.1 request (`http.ServerResponse` over the raw socket), so
  HTTP/2-preferring clients fall back to 1.1. This was the 4th and final cross-backend
  layer (after the JS scheduler async-conversion + the JVM `481190610` and JS `ede018597`
  WS-subprotocol-echo fixes). **`ClusterMultiBackendMatrixTest` flipped `ignore`→`test`
  and now PASSES** — a JVM-codegen node and a JS-codegen node, built from the same `.ssc`,
  converge over real WS on `leader=node-bbb`: multi-backend cluster deployment is real.
  The test self-derives `ssc.lib.path` (`sscLibArgs`) and cancels gracefully without the
  toolchain. Full `backendInterpreter/test` green. Specs:
  `specs/backend-correctness-hygiene.md` §T3.1, `specs/cluster-codegen-gap.md`.

## 2026-06-11 — feat(std.pdf): PDF text extraction — pdfToMarkdown / pdfPageCount (busi PIT-11 parsing)

- **pdf-text-extraction** — The reading counterpart to `htmlToPdfBase64`: extract
  a PDF's text layer for parsing uploaded/fetched PDFs (busi PIT-11 tax forms).
  `pdfToMarkdown(pdfBase64)` returns the recovered text per page (reading order),
  pages separated by a Markdown `---`; `pdfPageCount(pdfBase64)` returns the page
  count. Apache PDFBox `PDDocument` + `PDFTextStripper`. Honest plain-text — no
  layout/font/heading inference; image-only PDFs yield empty/partial text, non-PDF
  input throws. Same `std.pdf` surface (in `pdf-gen.ssc`) + `pdf-plugin` backend;
  PDFBox already transitive via OpenHTMLtoPDF, so no new dependency / packaging
  change. JVM-only. 4 tests; example `examples/pdf-extract-demo.ssc`; spec §7.

## 2026-06-11 — feat(std.crypto): Ed25519 / RSA private-key signing externs (busi sign request)

- **crypto-sign** — Added the producing side to complement the verify externs:
  `ed25519Sign`, `ed25519SignUrl`, `rsaSignSha256` (PKCS1/PSS). busi needs
  asymmetric signatures a third party (accountant/auditor) can verify without a
  shared secret for chain-checkpoint month-close evidence. Private key is a raw
  32-byte Ed25519 seed (PKCS#8-wrapped internally) or PKCS#8 DER; signers
  round-trip with the matching verifiers. Unlike the total verifiers, signers
  throw on a malformed key (trusted authoring input). JVM/interpreter via
  crypto-plugin. 6 tests incl. the RFC 8032 test #2 deterministic vector;
  spec `specs/crypto-pubkey-verify.md` §7; `examples/crypto-verify-demo.ssc`
  extended with the producing side. No key-generation extern (keys offline).
## 2026-06-11 — fix(js): render a raw un-lowered DataTableNode child (js-ui-raw-datatable-child)

- **js-ui-raw-datatable-child** — Follow-up to `js-backend-ui-render-gaps` Layer 2.
  The idempotent `lower` passthrough returns an already-lowered `_Element` whole and
  does not descend into its children, so a raw `DataTableNode` (the `TkNode` from
  `dataTable(...)`, not the `View` from `dataTableView`/`staticDataTable`) mixed
  directly into an `element(...)` children list reached the renderer un-lowered. The
  JS `walk` had no `'DataTableNode'` case → `default → ''` → the table **vanished
  silently** (confirmed `hasTable:false` with siblings rendering). Since
  `lower(DataTableNode)` is theme-free (just wraps into `dataTableView`), `walk` now
  normalises a raw `DataTableNode` into a `_DataTableView` and renders it through the
  existing path. Theme-dependent raw `TkNode`s still need a `Theme` and remain
  unsupported as un-lowered children. Regression test in `JsGenStdImportTest`; spec
  `specs/js-backend-ui-render-gaps.md` §Layer 2b.

## 2026-06-11 — fix(js-cluster): echo ssc-actors-v1 subprotocol on JS WS route (cluster-js-status-during-election, T3.1 Tier-4)

- **cluster-js-status-during-election** — Peeled the next layer of the disabled
  JVM↔JS matrix test. Investigating "JS status empty during election" revealed the
  **JS-codegen `/_ssc-actors` server also never echoed the subprotocol** (registered
  via the protocols-less `onWebSocket(path, handler)` form) — the symmetric bug to the
  JVM server. A spec-compliant `ws` peer client (JS `connectNode`) rejected the
  upgrade → peers stuck `__pending__`. Fixed: `onWebSocket('/_ssc-actors', [],
  ['ssc-actors-v1'])(handler)` (`JsRuntimeAsyncB`), reusing the JS WS server's existing
  negotiation. **Verified: two JS-codegen nodes now converge** (previously each elected
  self with the peer `__pending__`); the JVM↔JS upgrade negotiates `proto=ssc-actors-v1`.
  Full `backendInterpreter/test` 1618 green. The matrix test stays `ignore()` on a
  deeper remaining blocker: once a JVM (java.net.http) peer connects, the JS node stops
  serving plain HTTP (`/_ssc-cluster/status` GETs never dispatched; no crash, port
  bound) — reproduces only with a JVM peer (JS↔JS serves HTTP fine), pointing at the JS
  WS server's handling of JVM-originated frames. Precise diagnosis + re-enable recipe in
  the test doc + `specs/cluster-codegen-gap.md`.

## 2026-06-11 — refactor(core): shared CollectionMethods classifier (cross-backend-method-classifier, Tier 3 / T3.3 DONE)

- **cross-backend-method-classifier** — Unlocked the gated item and closed it.
  Investigation corrected the spec's "duplicated set across all four backends"
  framing: the genuine multi-name *classifier* sets existed only in JS (numeric
  inference for the dynamically-typed backend); JVM/interp/Rust method-name usage is
  dispatch *implementation* (`case "takeWhile" => …`, per-method logic — out of
  scope) or tiny purpose-specific local lowering alternations (not worth
  centralizing). Created the SSOT `scalascript.transform.CollectionMethods` in `core`
  (categorized `elementHofs` / `typePreservingListOps` + predicates) and migrated JS
  (`numericListHofs` → alias; element-type-preservation alternation → guard) —
  behavior-identical, full `backendInterpreter/test` 1612 green. JVM/Rust reviewed:
  no in-scope classifier sets remain. This completes Tier 3 (and the whole
  backend/compiler/interpreter improvement program); T3.1 has one further Tier-4
  slice (JS status-during-election) tracked separately. Spec:
  `specs/backend-correctness-hygiene.md` §T3.3.

## 2026-06-11 — fix(jvm-cluster): echo ssc-actors-v1 subprotocol on /_ssc-actors WS route (cluster-jvm-js-handshake, Tier 3 / T3.1)

- **cluster-jvm-js-handshake (subprotocol fix)** — Fixed and verified the first of
  two cross-backend Bully-convergence blockers behind the disabled
  `ClusterMultiBackendMatrixTest`. The JVM-codegen `/_ssc-actors` route registered via
  the protocols-less emitted `onWebSocket`, so its WS upgrade never echoed
  `Sec-WebSocket-Protocol`; the JS-codegen peer (a spec-compliant `ws` client offering
  only `ssc-actors-v1`) rejected with "Server sent no subprotocol", so envelopes never
  flowed. Now registers with `protocols = List("ssc-actors-v1")`, reusing the WS
  library's negotiation/echo; the no-op `onWebSocket` *stub* was widened to mirror the
  real signature (else non-cluster JVM programs hit "Illegal combination of named and
  unnamed tuple elements"). Verified end-to-end: ran the matrix test with
  `-Dssc.lib.path` (via `sbt installBin`) + `npm install ws` — the WS now connects and
  the JVM node reaches + elects the JS peer (`leader=node-bbb`), previously impossible.
  Full `backendInterpreter/test` **1612 green**. The test still fails on a SEPARATE
  remaining issue — the JS node's `/_ssc-cluster/status` is empty during the election
  (a JS clustering-under-load problem, distinct from this fix) — so it stays `ignore()`
  with the precise next-step diagnosis + re-enable recipe. Specs:
  `backend-correctness-hygiene.md` §T3.1, `cluster-codegen-gap.md`.

## 2026-06-11 — fix(js): std/ui row-data natives + idempotent lower (js-backend-ui-render-gaps)

- **js-backend-ui-render-gaps** (busi seq-79) — `emit-spa` rendered a blank screen
  because of two JS-backend divergences from the interpreter. **Layer 1:** the five
  `std/ui` row-data natives (`staticRowsSource`, `signalRowsSource`, `fieldPayload`,
  `wholeRowPayload`, `fieldsPayload`) had no `_ssc_ui_*` shim, so JsGen's extern
  guard bound them to `undefined` and any call (e.g. `fieldsBody = fieldsPayload`)
  threw `not callable`, blanking the SPA. Added the shims; the `_DataTableView`
  renderer + mount now handle `StaticRows` (inline JSON, no fetch) and `SignalRows`
  (subscribe to rows signal) alongside the legacy `Remote` fetch path; `_RowPost`
  bodies resolve `RowPayload` markers (field/wholeRow/fields). **Layer 2:**
  `std.ui.lower` gained an idempotent passthrough catch-all so lowering an
  already-lowered `View` returns it unchanged instead of throwing `Match failure`
  on JS — both backends now agree. Example `examples/datatable-static-spa.ssc`;
  4 new tests (3 JsGenStdImportTest + 1 StdUiSmokeTest). Spec
  `specs/js-backend-ui-render-gaps.md`.
## 2026-06-11 — fix(jit): ref-returning function calls returned IntV(0) (busi seq-74 regression)

- **jit-ref-return-call** — A cross-module function delegating to a ref-returning
  (String/collection) function returned `IntV(0)` instead of the value (e.g.
  `def f(x:String):String = g(x)` where `g = raw.trim.toLowerCase`). Broke every
  busi phase87 public-address test and gated their re-bump off pin `351cdaf4`.
  Two layers in the SscVm register-JIT path: (1) `VmCompiler` typed every
  non-double user-fn `CALL` result `TInt` (no `TRef` branch) → fixed with
  `JitPredicates.isRefReturning` + `VmCompiler.calleeReturnsRef` (recurses through
  delegation chains); (2) the `CALL` opcode dropped the ref result stashed in
  `tlRefReturn` → now copied into `refStack(dst)` when `callee.retIsRef` (CALL +
  CALLREF fast path). Conservative (unknown ⇒ numeric never mis-typed as ref).
  Spec `specs/jit-ref-return-call.md`; 3 tests `JitCrossModuleRefReturnTest`; full
  suite green (1615).

## 2026-06-11 — fix(interp): warn on cross-module import name conflict (busi-p3-module-fn-name-conflict)

- **busi-p3-module-fn-name-conflict** — Importing the same function name from two
  different modules (e.g. `htmlEsc` from `a.ssc` and `b.ssc`) no longer silently
  overwrites the first binding. Policy: **last import wins + warning** — the last
  import still wins, but a one-time `[warn]` is emitted and the name recorded in
  `Interpreter.importNameConflictWarnings`. Scoped to callable-vs-callable
  (`FunV`/`NativeFnV`) so the intentional status-val / case-constructor
  disambiguation is untouched; idempotent re-import of the same module does not
  warn. Spec `specs/import-name-conflict-policy.md`; 3 tests
  `ModuleFnNameConflictTest`; user-guide imports section. The originally-reported
  downstream `No key 'toString' in map` crash did not reproduce from a plain
  two-module collision; the import-time warning now surfaces the conflict early.

## 2026-06-11 — docs(cluster): re-diagnose disabled JVM↔JS Bully test (cluster-jvm-js-handshake, Tier 3 / T3.1 root-cause)

- **cluster-jvm-js-handshake (root-cause)** — Precisely re-diagnosed the suite's one
  disabled test (`ClusterMultiBackendMatrixTest`). Its documentation was stale: the
  headline reason (JS `_runActors` event-loop block) is already FIXED (async
  scheduler + `setImmediate` yield), and `require('ws')` is worked around in the
  test. The genuine remaining blocker is WS **subprotocol negotiation** — JVM-codegen
  registers `/_ssc-actors` via the emitted `onWebSocket(path)(handler)` (no protocols
  list) and so never echoes `ssc-actors-v1`, which the JS `ws` client requires
  ("Server sent no subprotocol" → close → no Bully convergence); the interpreter
  echoes correctly via `protocols = ActorWireProtocol.serverProtocols`. Replaced the
  stale test doc comment + `specs/cluster-codegen-gap.md` scheduler bullet with this
  diagnosis and a concrete fix plan (protocols-aware WS registration in the emitted
  JVM serve runtime). The fix is **not landed**: it is a cross-cutting emitted-runtime
  change (backs every JVM HTTP/WS program + MCP `onWebSocket`) whose only real
  verification is the heavy/flaky multi-process matrix test — unsafe to push
  unverified to shared `main`. Test stays `ignore()` with an accurate re-enable
  recipe; tracked as the Tier-4 cross-backend envelope-reconciliation task. Docs-only;
  no code/behaviour change. Spec: `specs/backend-correctness-hygiene.md` §T3.1.

## 2026-06-11 — feat(js): persistent HAMT Map — O(n²)→O(n log n) (js-persistent-map-hamt, Tier 2 / T2.2 DONE)

- **js-persistent-map-hamt** — Closes Tier-2 T2.2 (the last perf-gap item). The ssc
  immutable `Map` was a native `Map` copied on every `updated` (O(n) → O(n²) over a
  loop), making `map-ops` ~40× the JVM. Replaced with a persistent `_HAMT`: a
  path-copying 8-nibble trie on a 32-bit hash of a canonical value-equality key
  string, `updated`/`removed` copying only the O(8) path nodes (structural sharing).
  It exposes the native-Map read interface, and an `_isMap()` helper (p2 sweep of all
  71 `instanceof Map` sites, `2d0b780d6`) lets native runtime maps and the persistent
  user Map coexist. `_Map()`/`updated`/`removed`/`filter` route to `_HAMT`; two
  Dataset mutable-`_Map()` misuses fixed; `groupBy` left native (one-shot, exact
  grouping semantics). Key equality is value-based (matches the interpreter; identical
  to native Map for primitive keys); iteration is hash order (= interp's). Full suite
  **1609 green** (JS conformance via node); micro-bench: native-copy O(n²) (4.2×/
  doubling) → HAMT O(n log n) (1.8×/doubling), **~100× at N=4000**, growing with N.
  p1+p3 activation `a653cd331`. Spec: `specs/js-persistent-map-hamt.md`,
  `specs/backend-perf-gaps.md` §T2.2.

## 2026-06-11 — feat(std.ui): content-toolkit live-row binding completes ui-content-toolkit (3b)

- **ui-content-toolkit 3b** — A `toolkit:table?rows=<id>` Markdown link now binds to a
  `ContentRowBinding` registered in `ContentToolkitOptions.rowBindings`, rendering a live
  `DataTable` whose rows come from a runtime fetch `Signal` instead of a static YAML
  fence. Mirrors the 3a action registry: id resolved at lower time → `DataTableNode`
  (reusing the existing web `<table>` / native `JTable` lowering + `fcol` field columns);
  unregistered ids fail loudly listing available ids. `.ssc`: `ContentRowBinding`,
  `contentRows(id, rows, columns, actions?)`, `contentToolkitOptionsWithRows`. Plugin:
  `ContentIntrinsics` `rowBindingRegistry` + `case "table"` toolkit-link branch. Example
  `examples/content-live-rows.ssc`; 4 tests (2 `ContentPluginInterpreterTest` + 2
  end-to-end `MarkdownContentFrontendSmokeTest` through `emit`). Completes the
  `ui-content-toolkit` milestone (3a + 3b); unlocks Markdown-authored live-data screens.

## 2026-06-11 — refactor(js): _isMap helper sweep — HAMT migration p2 (js-hamt-p2-ismap-sweep, Tier 2 / T2.2)

- **js-hamt-p2-ismap-sweep** — Integration step of the JS persistent-Map migration
  (`specs/js-persistent-map-hamt.md`). Routed all 71 `x instanceof Map` checks in
  the JS runtime through `function _isMap(x) { return x instanceof Map; }`
  (always-loaded core Part2a, hoisted) — mechanical, behavior-identical (the helper
  is exactly the old check today). This isolates the 71-site coupling so the
  upcoming HAMT activation (p3) only extends `_isMap` to also match `_HAMT` and
  reroutes `_Map()` creation, instead of touching every consumer again. grep
  `instanceof Map` in the JS runtime now 0. Full suite 1609 green (incl. JS
  conformance via node). Next: p1+p3 — add `_HAMT` + activate.

## 2026-06-11 — fix(interp): user-wins + warning on plugin-intrinsic name collision (busi-p3-ratelimit-intrinsic-shadow)

- **busi-p3-ratelimit-intrinsic-shadow** — A user top-level `def` sharing a
  bare name with a plugin intrinsic (e.g. `rateLimit` from `auth-plugin`) no
  longer silently loses to the intrinsic. Policy: **user wins + warning** — the
  user definition always resolves; a one-time `[warn]` is emitted and the name
  recorded in `Interpreter.intrinsicShadowWarnings`. Both load orderings
  handled (native-first overwrite in `StatRuntime`; user-def-first guarded in
  `installNativeIntrinsicEntries`). Local defs and non-colliding modules
  unaffected. Spec `specs/intrinsic-shadow-policy.md`; 4 tests
  (`IntrinsicShadowTest`); user-guide §21.8. Also corrected two stale SPRINT
  entries — `busi-p4-smtp-send-extern` and `busi-p4-ed25519-rsa-verify` were
  already landed (2026-06-10) but still marked open.

## 2026-06-11 — spec: JS persistent Map (HAMT) migration design (js-persistent-map-hamt, Tier 2 / T2.2)

- **js-persistent-map-hamt (design)** — Tier-2 perf, the last open item. The ssc
  immutable `Map` is a native `Map` copied on every `updated` (O(n²) over a loop →
  `map-ops` JS ~40× JVM). Deferred because 71 `instanceof Map` sites couple to
  native `Map`. Explored + categorized the sites (HTTP headers/routing, JWT claims,
  GraphQL results, collection dispatch — all consume user Maps) and landed a
  de-risked design (`specs/js-persistent-map-hamt.md`): a duck-typed `_HAMT`
  (persistent, structural-sharing, exposes the native-Map read interface) + an
  `_isMap()` helper replacing the 71 `instanceof Map` checks, so internal native
  maps and the new persistent user Map coexist. Staged p1 infra → p2 mechanical
  71-site sweep → p3 activation → p4 bench, per the split-commit safety discipline.
  Design only; implementation is the dedicated multi-session sub-project (claim per
  slice). Spec: `specs/backend-perf-gaps.md` §T2.2 links the detail.

## 2026-06-11 — verify+close: interp column honest, T2.1 substantially done (bench-honesty-varying-data-p3, Tier 2 / T2.1)

- **bench-honesty-varying-data-p3** — Tier-2 measurement integrity; closes the
  interp-column audit and T2.1 for the automated harness. A/B-verified (`interp` vs
  `off`) the remaining flagged interp cells: `eitherChain` 0.002↔0.017 ms (honest),
  `optionChain` 0.002 ms ON (honest by analogy; `off` un-measurable due to the
  documented bench-harness `initBuiltins`-skip gotcha, `Undefined: None` — a harness
  artifact, not a fold or product bug), plus `instanceFieldAccess`/`arithLoop`
  (already honest). `bool-predicate`/`literal-match` are not interp benches. **Net:
  no interp cell is a measurement artifact.** Combined with p1 (off-baseline fix) +
  p2 (`tuple_monoid` de-fold), the automated benchmark harness is now honest: the
  one automated compiled fold is fixed, the interp + JS columns are clean, and the
  Rust/JVM anti-fold gap is documented. The cross-backend-gap doc's other compiled
  fold cells were ad-hoc one-off JVM probes with no standing automated cell.
  Docs-only this slice; no production/bench code changed. Spec:
  `specs/backend-perf-gaps.md` §T2.1 (now ✓ substantially done);
  `docs/bench/interp-honesty-audit.md`.

## 2026-06-11 — bench(honesty): de-fold tuple_monoid via loop-varying data (bench-honesty-varying-data-p2, Tier 2 / T2.1 direction-b)

- **bench-honesty-varying-data-p2** — Tier-2 measurement integrity, direction (b).
  De-folded `tuple_monoid`, the one fold cell with an automated *compiled*
  cross-backend measurement. It was loop-invariant on every backend
  (`jvm_tupleMonoid` 0.011 µs — HotSpot hoisted `last = k`; `js_tupleMonoid`
  ref-copied a frozen const; interp `(1,2)++(3,4)` hoisted by `tryHoistedPureWhile`).
  Rebuilt the tuple from the loop counter each iteration and accumulate all four
  components so no backend can fold; kept the `++` monoid op in the interp variant.
  After: `jvm_tupleMonoid` **205 µs** (~18000× the fold), `js_tupleMonoid`
  **1688 µs**, interp `tupleMonoid` **~14 ms** (1000 iters) — all real per-iteration
  work, on==off. Bench-only change; no production code. `modTupleMonoidVal` left as
  a deliberate hoist-optimization guard. Remaining T2.1: the same pattern for the
  interp-only fold cells (instance-field, bool-predicate, either/option-chain,
  literal-match). Spec: `specs/backend-perf-gaps.md` §T2.1; details
  `docs/bench/interp-honesty-audit.md`.

## 2026-06-11 — fix(interp): honest `off` baseline + interp honesty audit (bench-honesty-varying-data, Tier 2 / T2.1, partial)

- **bench-honesty-varying-data (partial)** — Tier-2 measurement integrity. Two
  deliverables: (1) **interp-column audit** (`docs/bench/interp-honesty-audit.md`)
  via the `interp` vs `off` A/B — `arithLoop`/`instanceFieldAccess` confirmed
  honest (JIT speeds real work, 11×/236×); completes the cross-backend audit
  alongside the existing JS and jvm/compiled-cell audits. (2) **Fixed an
  `off`-baseline honesty defect:** the algebraic loop eliminators
  (`tryFoldInvariantAccumLoop` + `tryClosedFormPolyLoop`, the T2.3 const-prop
  folds) ran *unconditionally* (`tryFastWhileAssign` gated only by `debugHooks`),
  so `scripts/bench off` — the documented no-JIT baseline — silently kept folding
  (`pureCallSum` 0.003 ms both "on" and "off"). Gated them behind `FastTier.enabled`;
  default unchanged (0.003 ms), `off` now reports the honest un-folded 11.748 ms
  (~3900× fold, now measurable). `docs/benchmarks.md` updated. 1605 tests green.
  **Remaining (open, re-claim separately):** direction-(b) varying-data redesign of
  the *compiled* (jvm/js/rust) fold cells — a per-workload benchmark-design project.
  Spec: `specs/backend-perf-gaps.md` §T2.1.

## 2026-06-11 — verify+close: JIT const-propagation (ssc-jit-const-propagation, Tier 2 / T2.3)

- **ssc-jit-const-propagation** — Tier-2 perf item, closed by discovery +
  verification (no new code). Both stages were already implemented and wired into
  the FastTier loop dispatch, having landed under their own perf commits before
  the item was tracked: **Stage 2** (pure/invariant call memoised once) =
  `EvalRuntime.tryFoldInvariantAccumLoop` (`3174c0b4c`); **Stage 3** (Gauss
  closed-form for degree-1-polynomial counter loops) =
  `EvalRuntime.tryClosedFormPolyLoop` + `walkLinearPoly` (`abe7e4d02`). Verified
  this session: JitLintTest (the spec gate) + SscVmTest closed-form/invariant fold
  cases + ConstFoldJsGenTest = **277 tests green**; `scripts/bench interp
  'pureCallSum$'` = **0.003 ms/op** (~83× over the ~0.25 ms pre-fold baseline;
  native JVM floor for the shape is 0.247 ms, i.e. the loop is eliminated).
  Coverage is the 2-assign counter+accumulator Int loop shape; broadening is out
  of scope (no bench demonstrates a gap). Spec: `specs/backend-perf-gaps.md` §T2.3.
  Tier-2 remaining: T2.1 bench-honesty, T2.2 js-persistent-map-hamt (deferred/big).

## 2026-06-11 — refactor(jsgen): extract CPS codegen; Tier-1 maintainability complete (jsgen-decompose)

- **jsgen-decompose** — Final Tier-1 maintainability item. Applied the proven
  self-typed-mixin pattern to `JsGen` (JS counterpart of JvmGen): moved the
  `// ─── CPS codegen for effectful contexts` section (15 class members) verbatim
  into a new mixin `JsGenCpsCodegen { self: JsGen => }`, continuing the existing
  `JsGenAnalysisQueries` split. Two-way `private→private[codegen]` widening (6
  moved members + 16 JsGen members the trait calls back into). Pure structural
  move, no behaviour change. `JsGen.scala` **5810 → 4942 lines (−868)**. The
  central `genExpr`/`genApply`/`genStat` dispatch (~900 lines) is deliberately
  left in place (out of scope: very high fan-in, low maintainability win vs risk).
  `backendJs/compile` clean under `-Werror`; full `backendInterpreter/test`
  1605 green. Spec: `specs/jsgen-decompose.md`. **Tier 1 of the
  backend/compiler/interpreter improvement program is now complete** (JvmGen
  10565→5019, JsGen 5810→4942); next is Tier 2 (perf gaps).

## 2026-06-11 — refactor(jvmgen): extract Preamble+runtime; JvmGen split complete (jvmgen-decompose-p2b)

- **jvmgen-decompose-p2b** — Tier-1 maintainability; completes the JvmGen split.
  Moved the remaining state-coupled `// ─── Preamble + runtime` section (HTML-DSL
  tag bindings, user-top-name / declared-var-type collection, the runtime-source
  loaders, the logger/common/serve runtime string blocks p2 had to leave behind,
  model rendering, `uiHelperFunctions`) verbatim into a new self-typed mixin
  `JvmGenPreamble { self: JvmGen => }`. Six members widened to `private[codegen]`;
  trait imports `scalascript.ast.*` + `JvmGenStringUtils.*`. Pure structural move,
  no behaviour change. `JvmGen.scala` **5849 → 5019 lines (−830)**.
  **Tier-1 JvmGen decomposition done: 10565 → 5019 (−53%) across 6 self-typed
  mixins.** `backendJvm/compile` clean under `-Werror`; full
  `backendInterpreter/test` 1605 green. Spec: `specs/jvmgen-decompose.md`. Only
  `jsgen-decompose` remains in Tier-1.

## 2026-06-11 — refactor(jvmgen): extract Mutual-TCO emission (jvmgen-decompose-p4)

- **jvmgen-decompose-p4** — Tier-1 maintainability. Moved the `// ─── Mutual-TCO
  emission` section (8 class members, incl. the allocation-free uniform-signature
  clique merge) verbatim out of `JvmGen.scala` into a new self-typed mixin
  `JvmGenMutualTco { self: JvmGen => }`, following the p3 pattern. One-way
  `private→private[codegen]` widening (four moved members called from JvmGen); the
  trait's callbacks all resolve to members already widened in earlier phases. Pure
  structural move, no behaviour change. `JvmGen.scala` **6073 → 5849 lines (−224)**.
  `backendJvm/compile` clean under `-Werror`; full `backendInterpreter/test`
  1605 green. Spec: `specs/jvmgen-decompose.md`. Tier-1 JvmGen split now: p1+p2+p3+p4
  done (10565 → 5849, −45%); remaining Tier-1: p2b + `jsgen-decompose`.

## 2026-06-11 — refactor(jvmgen): extract CPS transform (jvmgen-decompose-p3)

- **jvmgen-decompose-p3** — Tier-1 maintainability. Moved the `// ─── CPS
  transform` section (15 class members) verbatim out of the giant `JvmGen.scala`
  into a new self-typed mixin `JvmGenCpsTransform { self: JvmGen => }`, following
  the established p1/p2 `JvmGen*`-prefixed pattern. State-coupled section, so
  two-way `private→private[codegen]` visibility surgery (four moved members called
  from JvmGen; eight JvmGen members the trait calls back into). Pure structural
  move, no behaviour change. `JvmGen.scala` **7042 → 6073 lines (−969)**.
  `backendJvm/compile` clean under `-Werror`; full `backendInterpreter/test`
  1605 green. Spec: `specs/jvmgen-decompose.md`. Next: p4 (Mutual-TCO).

## 2026-06-11 — refactor(jit): share bindingIsRef — last JIT predicate (jit-predicates-bindingisref)

- **jit-predicates-bindingisref** — Shared `bindingIsRef`, the last JIT shape
  predicate still duplicated between `AsmJitBackend` and `JavacJitBackend`. It
  resolves callee ref-ness via `callParamIsRef`→`MethodSig` (different arity per
  backend), so a narrow `JitShapeCtx.callArgIsRef(fnName, argIdx)` query was added;
  each `GenCtx` (nested in its backend `object`) implements it by delegating to the
  enclosing `callParamIsRef`. The pure tree-walk moved to `JitPredicates`; both
  backends delegate. The JIT shape-classifier drift surface is now fully closed
  (completes `jit-predicates-shared` + `-rest`). 389 tests green in both default
  and `SSC_JIT_BACKEND=asm`; full `backendInterpreter/test` 1605 green. Tier-3 of
  the backend improvement program. Spec: `specs/backend-correctness-hygiene.md`.

## 2026-06-11 — refactor(jvmgen): extract runtime-source constants (jvmgen-decompose-p2)

- **jvmgen-decompose-p2** — Second decomposition phase. Moved the five large pure
  embedded runtime-source string constants (`stubServeRuntime`, `fsRuntime`,
  `generatorRuntime`, `effectsRuntime` ~3.2k lines, `reactiveRuntime`) out of
  `JvmGen` into a new `JvmGenRuntimeSources` mixin — pure data, zero coupling,
  visibility widened `private`→`private[codegen]`. `JvmGen.scala` shrank
  **10565 → 7042 lines (−33%)**. No behavioural change: 1605 `backendInterpreter`
  tests green, clean under `-Werror`. Spec: `specs/jvmgen-decompose.md`.

## 2026-06-11 — refactor(jvmgen): extract Effect-analysis mixin (jvmgen-decompose-p1)

- **jvmgen-decompose-p1** — First phase of decomposing the 10.6k-line `JvmGen`.
  Lifted the Effect-analysis section (`analyzeEffects`, `isEffectOpDef`,
  `isEffectOpRef`, `isEffectfulFun`) into a new self-typed mixin
  `JvmGenEffectAnalysis`, following the pattern already used by
  `JvmGenBlockAnalysis`/`JvmGenTermAnalysis`/`JvmGenMutualRecursion`. Verbatim
  move; `effectOps`/`effectfulFuns` widened `private`→`private[codegen]`. No
  behavioural change: 1605 `backendInterpreter` tests green, clean under
  `-Werror`. Spec: `specs/jvmgen-decompose.md`. Part of the 3-tier
  backend/compiler/interpreter improvement program (SPRINT).

## 2026-06-11 — refactor(jit): share remaining pure shape predicates (jit-predicates-shared-rest)

- **jit-predicates-shared-rest** — Follow-up to `jit-predicates-shared`. Lifted
  the remaining pure AST classifiers duplicated between `AsmJitBackend` and
  `JavacJitBackend` into `JitPredicates`: `isNumericObjectReceiver`,
  `isNumericObjectValueShape`, `peelMapUnary`, `isTupleMatch`, `asSelfRecur`,
  `isLiteralIntMatch`, `classifyParamRefs`. All are total functions of
  scala.meta AST / `Value.FunV` (no `MethodVisitor`/`GenCtx`/codegen state), so
  no `JitShapeCtx` extension was needed; each backend keeps a one-line delegate.
  `bindingIsRef` was investigated and deliberately left per-backend — it reaches
  codegen state via `callParamIsRef`→`MethodSig` (different arity per backend) +
  `coEmit.signatures`, so it is not a pure predicate. No behavioral change: 389
  tests green in both default and `SSC_JIT_BACKEND=asm`, full
  `backendInterpreter/test` 1605 green, clean under `-Werror`. Spec:
  `specs/jit-predicates-shared-rest.md`.

## 2026-06-11 — refactor(jit): share shape predicates via JitPredicates (jit-predicates-shared)

- **jit-predicates-shared** — The two JIT backends (`AsmJitBackend` bytecode,
  `JavacJitBackend` Java-source) each carried private copies of the same
  AST shape-classification predicates `looksLongValue` / `objectRefFallbackAllowed`.
  They are meant to decide the same "is this compilable on the numeric path"
  question, but drifted — a same-day bug had Javac's copy missing 2-arg
  `getOrElse`, `Math.max|min|abs`, val-bound lambda, and global-`FunV` cases, so
  a program classified differently under `SSC_JIT_BACKEND=asm` vs default.
  Lifted both predicates into `object JitPredicates` (precedent: `isBoolReturning`)
  behind a new narrow `JitShapeCtx` trait; each backend's `GenCtx` implements it.
  The sole per-backend difference (local-name resolution `slotOf>=0` vs
  `resolveLocal!=null`) is folded into `JitShapeCtx.isLocalLong`, making the
  predicate bodies byte-identical so they cannot drift again. Pure consolidation:
  389 tests green in both default and ASM backends, full `backendInterpreter/test`
  1605 green. Follow-up (lift remaining shared predicates) tracked in BACKLOG as
  `jit-predicates-shared-rest`. Spec: `specs/jit-predicates-shared.md`.

## 2026-06-10 — feat(std.crypto): AES-256-CBC + PKCS#7 with external IV (crypto-aes-cbc)

- **crypto-aes-cbc** — busi confirmed (vs official KSeF 2.0 OpenAPI §5222) that
  invoice **content** is encrypted with AES-256-**CBC** + PKCS#7 and a separate
  16-byte IV, which the existing GCM `iv12||ct||tag16` framing cannot express.
  Added three externs to `crypto-plugin` (`std.crypto`): `aesGenIv()` (16 random
  bytes, base64), `aesCbcEncrypt(keyB64, ivB64, plaintextB64)` and
  `aesCbcDecrypt(keyB64, ivB64, ciphertextB64)` — IV passed/returned separately so
  it maps directly onto `EncryptionInfo.initializationVector`; ciphertext returned
  alone (PKCS#7). CBC offers confidentiality but no integrity (pair with a
  MAC/signature). 6 new tests (round-trip, direct-JCE `AES/CBC/PKCS5Padding`
  interop, non-16-byte-IV rejection, wrong-key-never-recovers); 41/41 crypto-plugin
  green. JVM/interpreter only. Spec: `specs/crypto-encrypt.md`.

## 2026-06-10 — perf(jvm): allocation-free mutual-TCO for uniform-signature cliques (aot-mutual-tco)

- **aot-mutual-tco** — the JVM backend's mutual-tail-call trampoline allocated a
  `_TailCall` closure + boxed `Any` *per step* (Scala has no mutual `@tailrec`), so
  `mutual-recursion` (`isEven`/`isOdd`, 1M steps) was **slower than the
  interpreter** at 3.89 ms. For a clique whose members all share the same
  parameter-type list and return type, `JvmGen` now emits **one allocation-free
  dispatch loop** — a `_tag` selects the active member, tail calls to any member
  reassign shared `var` slots (+ `_tag`) and iterate; each member's case aliases
  the slots to its own param names. Non-uniform cliques keep the (correct) closure
  trampoline. jvm `mutual-recursion` **3.89 → 0.51 ms (7.6×)**. Verified identical
  to the interpreter for Boolean / String / 2-param-Int cliques at depth 100k
  (new `MutualTcoCrossBackendTest`); 111 JvmGen + cross-backend tests green, no
  jvm corpus regression.

## 2026-06-10 — perf(jvm): invariant-accumulation hoist for Long/Int accumulators (aot-hoist)

- **aot-hoist** — `JvmGen`'s loop-invariant `stable.foreach(p => acc = acc + f(p))`
  hoist (compute the inner sum once, before the outer loop) was gated on a
  **Double** accumulator; generalised to **Long/Int** too. Fixes the case where
  the JVM backend was *slower than the interpreter*: jvm `list-fold` (`sum: Long`)
  **0.075 → 0.000348 ms** (215×) — now matches the strength-reduction the interp
  JIT already applies. Output verified (550000); 111 JvmGen + cross-backend tests
  green; no jvm corpus regression. (Remaining AOT gaps — range map-fold fusion,
  mutual-tail-call trampolining — are larger codegen passes, deferred.)

## 2026-06-10 — perf(js): numeric type inference for HOF closures (js-numeric-inference)

- **js-numeric-inference** — arithmetic on numeric-collection elements no longer
  goes through `_arith(…)` + a `typeof==='string'` repeat-guard. `JsGen` now
  tracks numeric-element collections (`xs: List[Int]`, integer ranges) and types
  HOF closure params from the element type, propagating through `.map`/`.filter`
  chains and into the `foldLeft` combiner. `xs.map(x => x*2).filter(x => x%3==0).foldLeft(0)((a,b)=>a+b)`
  now emits native `[x => (x*2)]`, `[x => ((x%3)===0)]`, `(a,b) => (a+b)`.
  JS **hof-pipeline 0.028 → 0.0085 ms (3.3×, ≈jvm)**, **range-sum 0.048 → 0.011 ms
  (4.4×)**. Numeric output verified identical to the interpreter (6300 / 2425500);
  231 JS/cross-backend + 58 node conformance tests green; no corpus regression.
  Worst case if inference is wrong: falls back to `_arith` (correct, just slower).

## 2026-06-10 — perf(js): direct field access + nested-loop without IIFE (js-instance-field-shape, js-nested-loop)

- **js-instance-field-shape** — case-class field reads no longer go through the
  megamorphic `_dispatch(v, 'field', [])` runtime call. `JsGen` now tracks
  instance-typed params (`varName → caseClassType`) and lowers `v.x` to a direct
  property read when `x` is a declared field; `isIntExpr`/`isNumericExpr` also
  recognise numeric case-class fields so `v.x * v.x` emits native arithmetic
  instead of `_arith('*', …)` with a string-repeat guard. `normSq` went from a
  4-`_dispatch` + 3-`_arith` expression to `((v.x * v.x) + (v.y * v.y))`. JS
  `instance-field` **1.42 → 0.0025 ms (568×)** — from 4270× vs JVM to ~8×.
- **js-nested-loop** — a `while` nested in a while body was lowered as an
  expression and wrapped in an IIFE `(() => { … })()` created and invoked on
  every outer iteration (capturing the accumulator by closure → V8 deopt). It now
  emits a plain nested `while` statement (`genNestedWhileInline`). JS
  `nested-loop` **5.59 → 0.59 ms (9.5×)**.
- 231 JS/cross-backend + 58 node conformance tests green; full JS corpus sweep
  shows no regressions.

## 2026-06-10 — perf(interp): JIT anonymous HOF closures + String methods (interp-jit-string-closure)

- **interp-jit-string-closure** — `xs.map(s => s.trim.toInt)` / `xs.foldLeft(0)(_ + _)`
  and friends now JIT-compile instead of tree-walking. Two changes (spec p10 in
  `specs/jit-completeness.md`):
  1. **Anonymous HOF closures reach the JIT.** `JitRuntime.tryRun0/1/2/List`
     guarded `f.name.isEmpty`, excluding every map/foreach/foldLeft/filter
     callback. Lifted to `jitNameEligible = name.nonEmpty || closure.isEmpty` —
     empty-closure lambdas have a stable identity (`emptyClosureFunCache`) so the
     never-evicted `cache` isn't leaked; capturing lambdas stay excluded.
  2. **No-arg String methods.** New `SSTR` opcode (`trim`/`toLowerCase`/
     `toUpperCase`), `GETFI` String branch extended with `toInt`/`toLong`
     (`v.toLong`, matching the interpreter incl. its `NumberFormatException`),
     `VmCompiler` String-`Select` dispatch, untyped-param `String` inference from
     the runtime arg, and `StringV`/`MapV` accepted as ref-param args.
  - **string-split JMH 17.18 → 2.74 ms (6.3×)**, corpus **18.7 → 3.26 ms (5.7×)**;
    `hof-pipeline`/`range-sum` now ≈10⁻³ ms. Full suite green (1593, +5 new VM
    tests) on both JIT backends (javac + asm). `s + lit` concatenation remains
    out of scope (heap alloc).

## 2026-06-10 — perf(interp): reused frame for List.map / List.foldLeft (interp-hof-frame-reuse)

- **interp-hof-frame-reuse** — Extended the `foreachReusing` reused-frame fast path
  to `List.map` (`CallRuntime.mapReusing`) and `List.foldLeft`
  (`CallRuntime.foldLeftReusing` + new `ReusableFrame2`). For a simple 1-/2-param
  closure, one mutable frame is reused across the whole sequence instead of
  allocating a `FrameMap1`/`FrameMap2` per element; the first non-`Pure` body result
  bails to the allocating path. JMH `stringSplit` (300×20-field CSV parse-and-sum):
  **17.18 → 16.55 ms** wall (3.6%), **2.25 → 1.90 MB/op** allocation (15%, ~346 KB =
  12k per-element frames eliminated). Wall-clock win is bounded by the body tree-walk
  (String `.trim`/`.toInt` dispatch), which the follow-up `interp-jit-string-closure`
  targets. Suite green (1588) on default bytecode JIT; pure tree-walk path change, no
  JIT touch.

---

## 2026-06-10 — feat(std.crypto): Ed25519 / RSA signature verification (crypto-pubkey-verify)

- **crypto-pubkey-verify** — `std.crypto` gains public-key signature verifiers for
  trustless federation: `verifyEd25519`, `verifyEd25519Url` (base64url), and
  `verifyRsaSha256(..., scheme)` (`"PKCS1"` | `"PSS"`). All **total** — a malformed
  key/signature/scheme returns `false` and never throws, so a hostile peer cannot
  crash the verifier. Ed25519 accepts a raw 32-byte key or SPKI DER (raw is wrapped
  with the RFC 8410 SPKI header). Implemented in `crypto-plugin` (JDK
  `java.security.Signature`, JVM only). 6 tests: RFC 8032 vectors #1/#2, RSA
  PKCS1+PSS against a JCE keypair, tamper/malformed negatives. 30/30 green.
  Example `examples/crypto-verify-demo.ssc`. Lifts busi's `signature.unsupported`
  quarantine for verifiable Phase 87 traffic.

---

## 2026-06-10 — feat(std.mime): buildMimeMessage RFC 5322 assembly (mime-p3-build)

- **mime-p3-build** — `std.mime.buildMimeMessage(from, to, subject, htmlBody, attachments)`
  assembles a ready-to-send RFC 5322 email: 0 attachments → a `text/html` message,
  1+ → `multipart/mixed` (base64 HTML body + base64 attachments, RFC 2047 subject).
  New dependency-free hand-rolled `mime-plugin`; `attachments` is a
  `List[(filename, mimeType, contentBase64)]`. 4 tests round-trip through the Jakarta
  Mail / Angus reference parser (test-scope only). Example `examples/invoice-email.ssc`
  pairs it with `std.pdf` for the relay-free invoice-email path. Completes the PDF+MIME
  pair (`pdf-mime-generation.md`); `smtpSend` is the remaining slice.

---

## 2026-06-10 — feat(std.pdf): htmlToPdfBase64 generation (pdfgen)

- **pdfgen-p1-engine + pdfgen-p2-stdlib** — `std.pdf.htmlToPdfBase64(html)` renders a
  confined HTML/CSS subset (table layout, A4, typography/borders/background,
  `@media print`) to base64 PDF bytes — a drop-in for an external HTML→PDF relay.
  New opt-in `pdf-plugin` (OpenHTMLtoPDF 1.0.10 over PDFBox; jsoup 1.17.2 parses
  real HTML leniently → W3C DOM). Unsupported CSS (grid/float/flex) is skipped, not
  thrown; unparseable input throws a clear error. JVM/interpreter only. Surface
  `runtime/std/pdf-gen.ssc`, example `examples/invoice-pdf.ssc`. `PdfPluginTest`
  (4): `%PDF-` magic + ≥1 page via PDFBox + graceful degradation. Unblocks busi's
  relay-free invoice PDF (`pdf-mime-generation.md`); MIME/SMTP are the next slices.

---

## 2026-06-10 — feat(std.crypto): AES-256-GCM + RSA-OAEP + X.509 encryption (crypto-encrypt)

- **crypto-encrypt** — `std.crypto` gains encryption (was hash/HMAC/base64 only),
  for KSeF 2.0 hybrid e-invoicing and general "encrypt to a public key" use:
  `aesGenKey`, `aesGcmEncrypt`/`aesGcmDecrypt` (+ `*Bytes` variants),
  `rsaOaepEncrypt`, `x509PublicKey`. AES-256-GCM framing is
  `base64(iv[12] ++ ciphertext ++ tag[16])`; RSA-OAEP uses SHA-256 for digest+MGF1.
  Implemented by extending the existing `crypto-plugin` (JDK `javax.crypto`, no
  external dep) — JVM/interpreter only (JS WebCrypto deferred). Crypto failures
  throw a clear error, never a silent wrong result. 8 new tests (AES round-trip/
  tamper/bytes, RSA-OAEP verified against a JCE keypair, X.509 SPKI against an
  openssl vector); `cryptoPlugin` 24/24 green. Example `examples/crypto-encrypt-demo.ssc`.

---

## 2026-06-10 — fix(interp): error on ambiguous un-ascribed val/case-constructor collision (A-half)

- **busi-p0-statusval-collision-a-half** — Completes the statusval/eventcase
  collision handling. A bare `val x = Foo` with no type ascription, where `Foo` is
  bound to both a stable value and a case constructor, previously resolved silently
  to one side; it now raises a located error `name 'Foo' is bound to both a stable
  value and a case constructor; add a type ascription or rename one`. The ascribed
  shapes keep the B-half behaviour (`Type.Name` disambiguates; other ascriptions —
  e.g. function types — keep the case-constructor). `StatRuntime.disambiguateValBinding`
  gates on `decltpe.isEmpty` + a `shadowedAlternatives` entry. 2 regression tests;
  backendInterpreter 1583/1583 green.

---

## 2026-06-10 — fix(parser): fail loudly on truncated code blocks (ui-bug-jobj-failloud)

- **ui-bug-jobj-failloud** — scalameta throws a raw `NullPointerException` from its
  `termParam` token handling on truncated inputs (`def f(`, `def f(using `), and deep
  unbalanced nesting can `StackOverflowError`. `parseScalaWithDiagnostic` only matched
  `Parsed.Success`/`Parsed.Error`, so a thrown exception escaped and crashed/hung the
  pipeline. New `safeParse` wraps each parse attempt and converts a thrown
  `NonFatal`/`StackOverflowError` into a synthesized located `Parsed.Error` — the parser
  now emits a diagnostic, never a crash. 4 `ParseErrorPositionTest` regression tests;
  core 920/920 green.

---

## 2026-06-10 — test: busi-p1-map-update-foldleft-unreliable verified fixed + regression guards

- **busi-p1-map-update-foldleft-unreliable** — Reported "Instance is not callable"
  on `foldLeft` into `Map[String, wide-case-class]` with per-key reconstruction no
  longer reproduces. Root cause was the pre-flag-flip `HashMap` field representation
  for 10+-field case classes; the 2026-06-03 Direction B `fieldsArr` flag-flip
  unified all field counts. Verified non-reproducing across 7 variants; locked with
  two `BugReproTest` guards mirroring the busi `applyRetirement` shape.

---

## 2026-06-10 — test: busi-p1-while-typed-empty-list-bug verified fixed + regression guards

- **busi-p1-while-typed-empty-list-bug** — Reported bug (a `while` loop appending
  to a typed empty `List[(Int,T)]()` left the list empty) no longer reproduces;
  fixed by intervening while-JIT work. Verified non-reproducing across non-JIT,
  JIT-hot (200k-call function), case-class tuple elements, `Set.contains` in the
  body, and N=50k. Locked with two `BugReproTest` regression guards.

---

## 2026-06-09 — perf(interp): positional fieldsArr for builtin Right/Left/Some

- **instancev-either-option-fieldsarr** — Single-field Either/Some wrappers were
  built as `InstanceV` with a per-instance `IMap.Map1("value", v)`, and each method
  dispatch / JIT-fused read re-materialised that Map. New `Value.singleValue` builds
  them in the positional `fieldsArr` representation (`Array(v)` + shared
  `SingleValueFieldNames`, `fields = Map.empty`) — the same shape user case classes
  use post flag-flip. `Right`/`Left`/`Some` registered in `typeFieldOrder` at
  `initBuiltins` so PatternRuntime / dispatch / JIT read them by index;
  `JitHofDispatch.valueField` made arr-aware. Hot Either sites migrated (Core ctors,
  DispatchRuntime map/flatMap/fold, JIT fusion). Same-session A/B (gc.alloc.rate.norm):
  **eitherChain 488 → 224 B/op (-54%)**; optionChain flat at 96 (Some is already
  `OptionV`). Wall-clock unchanged. 1572/1572 green. Follows up the residual left by
  `ssc-jit-escape-analysis`.

---

## 2026-06-09 — perf(jit): map-into-sink fusion for Option/Either (escape-analysis slice)

- **ssc-jit-escape-analysis** (partial) — A `.map(unary)` feeding `.flatMap(global)`
  or `.getOrElse(default)` on an Option/Either allocated an intermediate wrapper
  only to be consumed one step later. `peelMapUnary` detects it and the backends
  emit `JitHofDispatch.mapFlatMapGlobalLong` / `mapGetOrElseLong`, applying the
  map inline with no wrapper allocation. Both Javac + ASM. Tight A/B
  (`scripts/bench profile`): optionChain 116 → 100 B/op (−14%), eitherChain
  708 → 564 B/op (−20%; ASM 528). 4 SscVmTest cases; full suite green (1572)
  both backends; JIT disabled count unchanged (736). Remaining: cross-function
  inlining of the wrappers built inside `lookup`/`parse` (the dominant cost) —
  own session.

---

## 2026-06-09 — perf(jit): range-native fold fusion + `to`-inclusive ranges

- **ssc-jit-range-fusion** — Follow-up to map/filter/foldLeft fusion: when the
  fold base is an integer range (`lo until/to hi`), the JIT iterates it with a
  primitive counter (`JitHofShape.rangeBounds` + `JitHofDispatch.fusedRangeFoldLong`)
  instead of materialising a base `ListV` — covers a bare `range.foldLeft(0)(+)`
  too. `walkRef` also now compiles `to` (inclusive) ranges. Both Javac + ASM.
  `InterpreterBench.rangeSum` 506 → 25.6 B/op (1016 → 25.6 vs pre-fusion baseline).
  4 SscVmTest + 3 JitLintTest cases; full suite green (1568) both backends; JIT
  disabled count unchanged (736). Remaining: literal-bound const-fold (tracks
  with `ssc-jit-const-propagation` Stage 3).

---

## 2026-06-09 — perf(jit): loop fusion for map/filter/foldLeft chains

- **ssc-jit-loop-fusion-universal** (partial) — The bytecode JIT now fuses
  `recv.map(f).filter(g).foldLeft(z)(+)` (either stage optional) into a
  single allocation-free pass. `JitHofShape.fuseFoldChain` decomposes the
  fold receiver at emit time (shared Javac + ASM); `JitHofDispatch.fusedFoldLong`
  walks the receiver once with primitive `long` accumulators — no intermediate
  `ListV`, no per-stage re-boxing. Falls back to the per-stage path on any
  unrecognised shape. A/B (Javac, `scripts/bench profile`):
  `hofPipeline` 240 → 1.7 B/op (-99%), `rangeSum` 1016 → 506 B/op. Wall-clock
  unchanged at 6/20-elem inputs (GC-pressure win scales with length). Spec:
  `specs/jit-loop-fusion.md`. 5 new fuseFoldChain tests; full backendInterpreter
  suite green (1556) on both backends; JIT disabled count unchanged (736).
  Follow-up: base-range (non-`until`) fusion + literal-bound const-fold for the
  `<100ns` top-level-chain target.

---

## 2026-06-09 — feat(std): std.yaml — YAML parse/stringify stdlib

- **yaml-p1-spec** — specs/std-yaml.md: API, subset, backend table, 4-phase plan.
- **yaml-p2-jvm** — yaml-plugin: SimpleYaml → YamlValue InstanceV + block serializer;
  accessors (yamlType/yamlStr/yamlNum/yamlBool/yamlArr/yamlGet). 28 tests.
- **yaml-p3-js** — JsRuntimeYaml.scala: pure-JS parser + serializer in preamble. 13 tests.
- **yaml-p4-stdlib** — yaml.ssc; yaml/yml fenced blocks → section.yaml binding;
  yaml-parse.ssc example. 1550 backendInterpreter tests pass.

---

## 2026-06-09 — fix(bench): defeat LLVM scalar-evolution constant-folding on Rust target

LLVM -O3 was replacing entire pure-arith bench loops with closed-form
constants (e.g. `for i in 0..1M { sum += i }` → mov+ret = 1 ns/iter). This
made 12 of 24 Rust bench results meaningless — measuring `mov reg, const`
instead of the actual workload.

Two-part fix:
- **bench-opaque-seed-infra**: `Bench.opaque[A](x: A): A` cross-backend
  identity — Rust → `std::hint::black_box`; JVM/JS preamble inline def;
  interp via bench-plugin + AST fast-path in `evalApplyGeneral`. Reusable
  for future bench / FFI work.
- **bench-opaque-seed-anti-fold**: `bench/run.sc` Rust path auto-patches
  the generated `pub fn workload()` body — wraps every `let mut x = ...;`
  and `x = ...;` with `std::hint::black_box(...)`. Closure-containing rhs
  (`{...}`) are skipped to avoid breaking syntax. No corpus changes
  needed — JVM/JS/interp/ssc-asm results unchanged.

Rust bench results now reflect actual loop costs: 11 of 12 previously
folded workloads jumped 33×-605,000× to realistic numbers (arith-loop:
0.000002→1.21ms; nested-loop: 0.000001→0.456ms; list-fold: 0.000003→0.099ms).
Remaining four (effect-pure, recursion-tco, streams-pipeline,
typeclass-monoid) have closure-only or direct-call bodies not on the
patched assignment path.

---

## 2026-06-09 — fix(bench): close all 4 cross-backend n/a (jvm typeclass-monoid, js either-chain, js map-ops, streams-pipeline)

- **bench-na-jvm-typeclass-monoid** — Added `trait IntMonoid` declaration so JVM Scala 3 backend type-checks the given target.
- **bench-na-js-either-chain** — Added `Either[L,R]` dispatch (Right/Left .map/.flatMap/.fold/.getOrElse/etc.) to JsRuntimePart2b.
- **bench-na-js-map-ops** — Fixed `Map[K,V]()` and `List[T]()` with explicit type args in JsGen (both sync genApply and async CPS paths).
- **bench-na-streams-pipeline-all** — Replaced Rust-specific `Source.range` with portable `(lo to hi).map.filter.foldLeft` chain.

All 24 corpus workloads now report numeric ms/iter on all 5 backends.

---

## 2026-06-09 — fix(interp): String.indexOf full support + String.split regex semantics

- **busi-p1-string-indexof** — `indexOf`/`lastIndexOf`: added `IntV` char-code arg, 2-arg `(str, fromIndex)` form in `dispatch2` + `dispatchString`.
- **busi-p1-string-split-regex** — `split` now uses separator as raw regex (Java semantics); added `split(sep, limit)` 2-arg form.

## 2026-06-09 — feat(rust): R.6.2 — typeclass support: given instances as Rust structs

- `given X: T with { defs }` → Rust unit struct XGiven + impl; topVal injection. 7 new tests.

---

## 2026-06-09 — feat(rust): R.6.3 — stream pipeline via iterator chains (rust-backend-r6-streams)

- Source.range/fromList/.toList via Rust iterator chains; no tokio/futures needed. 7 new tests.

---

## 2026-06-09 — feat(rust): std-fs-os-p4-rust — std.fs / std.os / std.process Rust backend

- 12 std.fs helpers (appendFile, readBytes, writeBytes, exists, isFile, isDir, mkdir, mkdirs, listDir, deleteFile, copyFile, moveFile)
- 15 std.os helpers (env→Option<String>, envOrElse, cwd, sep, pathJoin, pathDirname, pathBasename, pathExtname, pathResolve, pathIsAbsolute, tempDir, tempFile, platform=Native, homedir, hostname)
- ProcessResult struct + exec(cmd, args) via std::process::Command. All pure std, no extra crates.

---

---

## 2026-06-09 — feat(jsgen): std.fs/std.os/std.process JS/Node preamble (p3-js)

- **std-fs-os-p3-js** — JsRuntimeFs.scala: 16 fs + 15 os + exec(); Node.js
  lazy-require('fs'/'path'/'os'/'child_process'); browser FsNotSupported stubs.
  21 tests. JsGen.generateRuntime always includes it.

## 2026-06-09 — feat(std): std.fs / std.os / std.process stdlib + JVM plugins

- **std-fs-os-p1-spec** — specs/std-fs-os.md: full 3-module spec (fs/os/process).
- **std-fs-os-p2-jvm** — fs-plugin (13 ops) + os-plugin (18 ops incl. exec); 27 tests.
- **std-fs-os-p5-stdlib** — fs.ssc expanded; os.ssc + process.ssc new; 2 examples.
- **std-fs-os-p6-audit** — audit complete; AGENTS.md already references spec.

## 2026-06-09 — feat(rust): R.6.8 — MCP server (rust-backend-r6-mcp) + xslt decision

- **rust-backend-r6-mcp** — JSON-RPC 2.0 over stdio; `mcpRegisterTool`/`mcpServe`;
  serde_json dep only. 7 new tests (162 total).
- **rust-backend-r6-markup-xslt** — Decision: XSLT excluded; capability check rejects
  programs requiring it. No code change (already implicit via missing feature flag).

---

## 2026-06-09 — feat: backend-specific blocks Phases 6+7

- **backend-blocks-p6-ffi-extend** — @rust("expr") wired in RustCodeWalk; 4 tests.
- **backend-blocks-p7-audit** — 1 violation fixed (mcp-search-server.ssc → scala block).

## 2026-06-09 — feat(rust): R.6.6 — WebSocket server + client (rust-backend-r6-websockets)

- **rust-backend-r6-websockets** — `wsRoute`/`wsServe`/`wsConnectSync` via tokio-tungstenite 0.21.
  Conditional dep injection; no tokio duplication when HTTP also present. 8 new tests.

---

---

## 2026-06-09 — feat: backend-specific blocks Phases 3+4

- **backend-blocks-p3-jvm** — scala blocks via isParseable; java blocks emit
  `//> using sources _ssc_java_N.java` + write files when baseDir set. 7 tests.
- **backend-blocks-p4-js** — javascript blocks emitted verbatim in JsGen;
  html/css keep template-value path. 6 tests (1504 total).

## 2026-06-09 — feat(rust): R.6.5 — TCO via while-loop rewrite (rust-backend-r6-tco)

- **rust-backend-r6-tco** — Self-tail-recursive defs rewritten to `loop { ... }`;
  tail calls become param reassignments. Binary-recursive fns unchanged. 7 new tests.

---

---

## 2026-06-09 — fix(interp/cli): busi wave-3 — arrow-vs-plus, emit-js process.stdout

- **busi-p1-arrow-vs-plus-precedence** — `dispatchTuple` absorbs `+` into 2-tuple string tail (runtime fix for `Map("k" -> prefix + val)` precedence).
- **busi-p2-emit-js-process-stdout** — emit-js now guards `process.stdout.write` with `typeof process` check; browser falls back to `console.log`.

## 2026-06-09 — feat(rust): R.6.7 — auth: argon2 + JWT (rust-backend-r6-auth)

- **rust-backend-r6-auth** — `hashPassword`/`verifyPassword` via argon2 0.5;
  `jwtSign`/`jwtVerify` via jsonwebtoken 9 (HS256). scanAuthUsage drives
  conditional deps; programs without auth stay dep-free. 9 new tests.

---

## 2026-06-09 — fix(interp): busi wave-2 — 4 interpreter fixes

- **busi-p0-try-catch-handler** — `Term.TryWithHandler` now supported; eval `catchp` as a fn, call with caught exception.
- **busi-p1-map-concat-returns-tuplev** — `Map ++ Map` in `infix2` correctly merges instead of wrapping in TupleV.
- **busi-p1-map-getorelse-null-semantics** — `getOrElse` returns default when stored value is `NullV` (SQLite NULL).
- **busi-p1-phase90-rule-bool-coercion** — Unary `!` on `IntV` coerces to Bool (`!0 = true`, `!nonzero = false`).

## 2026-06-09 — feat(typer): platform-type ban Phase 2 + feat(parser): backend blocks Phase 1

- **backend-blocks-p1-parse** — Lang.Java/Rust/Wasm + isNativeBackendBlock + isOpaqueExec; 17 tests.
- **backend-blocks-p2-typecheck** — E_PlatformType for java/javax/sun/com.sun imports in
  scalascript blocks; scala blocks exempt; 10 new tests (916 total).

## 2026-06-09 — feat(rust): R.4 algebraic effects complete (tagless-final, Phase 2)

- **rust-backend-r4-perform-handle-resume-lowering** — R.4 effects fully landed
  via tagless-final: R.4.1 free-monad runtime template; R.4.2 Logger/NoOpLogger
  (effect-pure bench); R.4.3 Stream/VecStream (effect-stream bench); R.4.4
  State/StateHandler + Random/RandomHandler with LCG (Phase 2 of rust-effects.md).
  `runState(init){body}` / `State.get()` / `State.put(s)`;
  `runRandom(seed){body}` / `Random.nextInt(b)` / `Random.nextFloat()`.
  131 tests pass. R.5 (HTTP) and backend-blocks-p5-rust also confirmed landed.

---

## 2026-06-09 — feat(parser): backend-specific fenced blocks Phase 1

- **backend-blocks-p1-parse** — Lang.Java/Rust/Wasm constants + isNativeBackendBlock
  + isOpaqueExec wiring; java/rust/wasm blocks classified as EmbeddedKind.Opaque
  (verbatim source, not parsed by scalameta). 17 new tests (906 total pass).

## 2026-06-08 — fix(rust): 3 bench rustc errors + CallRuntime MapV type fix

- **rust-fix-bench-non-i64-return** — `bench/run.sc` `_run_workload()` now
  returns `()`, fixing `E0308` for `tuple-monoid` (tuple return) and
  `pattern-match-heavy` (f64 return).
- **rust-fix-iife-parens** — Either map/flatMap/fold emitters wrap closures in
  parens `(move |x| { body })(arg)` fixing `E0618` for `either-chain`.
- **rust-fix-struct-copy** — `renderStruct` derives `Copy` for structs with
  all-primitive fields, fixing `E0382` for `instance-field`.
- **fix(interp)** — `CallRuntime.scala`: `MapV(fields)` → `MapV(fields.map
  { case (k,v) => (StringV(k), v) })` to match updated `MapV` signature.
  All 4 bench workloads now produce Rust results; 106 Rust tests pass.

---

## 2026-06-08 — feat(rust): R.5 — HTTP server via hyper + tokio

- **rust-backend-r5-tokio-runtime-bootstrap** — HTTP server shipped.
  `route(method, path, handler)` and `serve(port)` intrinsics wired
  to hyper 1 + tokio. `scanHttpUsage` drives conditional Cargo deps
  (tokio, hyper, hyper-util, http-body-util, bytes) and
  `src/runtime/http.rs` emit. Handler takes `impl Fn(String) ->
  String` at call site; internally adapted to `Fn(&str)` in
  _http_route. RustCapabilities declares `HttpServer`. End-to-end
  smoke: `http-hello.ssc` builds 1.2 MB binary, `curl /hello` →
  "Hello from /hello". 90/90 unit tests (6 new).

## 2026-06-07 — feat(rust): R.4.1 — algebraic-effects runtime infrastructure

- **rust-backend-r4-effect-runtime** — First slice of Phase R.4.
  Ships the Free-monad runtime (Computation<A> = Pure | Effect,
  HandlerStack, run_with driver, pure/perform ctors) as
  `src/runtime/effect.rs` emitted on demand. `RustGen.scanEffectUsage`
  does a conservative textual scan (perform(, handle{, resume(,
  effect E:) over scalascript/ssc/scala/rust blocks; emit fires iff
  non-empty. Three embedded `#[cfg(test)]` smoke tests inside the
  runtime file validate the Free-monad semantics independently of
  codegen. RustCapabilities declares `AlgebraicEffects` so programs
  that exercise the runtime via hand-written `rust` blocks pass
  CapabilityCheck while R.4.2 lowering catches up. Smoke at 14
  fixtures, 79/79 unit tests (7 new). New fixture
  `effect-runtime.ssc` returns `8` (perform("ask") + handler → +1).

## 2026-06-07 — feat(jit): stage-9 lambda-value-solo ASM port + poly-IC + refchain residual

- **jit-uc-stage9-lambda-value-solo-asm** — ASM port of val-bound lambda
  inlining (mirror of the Javac slice from earlier in the day). `GenCtx`
  tracks the lambda body; `walkLong` inlines call sites via param
  substitution into fresh long slots. `WhileCtx` + `substituteParams`
  surface FunV locals through `EvalRuntime` so while-body call sites
  inline at AST level. Lambda bench `hot(n: Int)` with a captured
  `(x: Int) => x*2+1` now JITs end-to-end on ASM (was `[asm] Compound`
  fallback to tree-walker): 43.66 s → 0.69 s (63×).
- **jit-uc-stage9-poly-ic** — Replaced the monomorphic CALLREF inline
  cache (Stage 3.4) with a 4-way poly-IC. Per-pc layout = `icWays = 4`
  (FunV, CompiledFn) pairs; linear scan on the hot path, round-robin
  eviction on miss via per-pc `icHead` byte. "Seen but not compilable"
  stays cached as `(FunV, null)` to suppress repeat `VmCompiler.compile`
  attempts. `SSC_JIT_IC_STATS=1` reports hits broken down by way.
- **jit-uc-stage8-refchain-object-residual** — Narrowed `JitLint`'s
  `isPrimitiveRefRead` to recognise the methods `JitRefDispatch` already
  handles (`contains`, `mkString` 0/1/3-arg, `isDefined`/`isEmpty`/
  `nonEmpty`, `head`/`last`/`length`, `toString`, etc.). The 4 residual
  `RefChainObjectCall` misses (`hasIt`, `wrap×2`, `show`, `str`) drop
  to 0 across the full test suite; `JitLintTest` locks in the new
  classification.

Test impact: full `backendInterpreter/test` 1482/1482 green; `JitLintTest`
66/66.

---

## 2026-06-07 — fix(interpreter): typed-val ascription disambiguates same-name collision

- **busi-p0-statusval-eventcase-collision** — A `val Foo = SomeStatus(...)`
  and a `case Foo(...)` enum case with the same name no longer collide
  silently.  Before: the second registration overwrote the first in
  `interp.globals`, so any later bare `Foo` reference returned the
  case-constructor `NativeFnV`, and a typed ascription like
  `val s: SomeStatus = Foo` followed by `s.code` threw
  `No method 'code' on NativeFnV(<native:Foo>)`.  Now the displaced
  side is recorded in `interp.shadowedAlternatives`, and at
  `Defn.Val(_, _, Some(Type.Name(T)), Term.Name(n))` execution the
  interpreter picks whichever binding's `typeName` matches `T`.
  Pattern-position and `Foo(args)` expression-call paths stay
  unchanged (case-constructor still wins).  Covered for both
  same-file definitions and cross-module imports.  7 regression
  tests; `core/test` (896) + `backendInterpreter/test` (1481) green.

  Surfaced by the busi agent in phase 86a (peer-link handshake).
  The pre-fix busi workaround was to rename the status-val to
  `PeerLinkStatusInvited`; that workaround continues to work without
  change.  Direction chosen by sergiy in the 2026-06-07 rozum
  meeting: hybrid B-then-A — semantic split via expected type, with
  an A-style compile-time error reserved for genuinely ambiguous
  cases (not yet implemented; the current fix handles the common
  `val s: SomeStatus = Foo` shape that surfaces in production).

## 2026-06-07 — feat(rust): R.3.3 — jsonParse + jsonStringify — R.3 complete

- **rust-backend-r3-json** — Third and final slice of R.3. Two
  intrinsics gated on `serde_json`: `jsonParse` (compact canonical
  form), `jsonStringify` (pretty-printed). Both validate input via
  `serde_json::Value` round-trip. `scanCryptoUsage` extended to also
  cover JSON; `serde_json = "1.0"` added to Cargo.toml only when
  reached; runtime template `JsonRs` appended on the same gate.
  Helpers take `&str` (added to BorrowedArgIntrinsics). New fixture
  `json-roundtrip.ssc` → `{"x":1,"y":[true,null,"hi"]}`. Smoke at
  13 fixtures, 72/72 unit tests. **Phase R.3 complete.**

## 2026-06-07 — feat(rust): R.3.2 — sha256 + base64 with per-module dep walk

- **rust-backend-r3-crypto-base64** — Second slice of R.3. Three
  intrinsics: `sha256` (sha2 crate), `base64Encode`/`base64Decode`
  (base64 crate). `RustGen.scanCryptoUsage` walks the AST for
  intrinsic-name references and drives both Cargo.toml deps and the
  runtime-template emit — a hello-world stays dep-free, a sha-only
  program pulls just `sha2`, a base64-only just `base64`. Two new
  fixtures (`crypto-sha256.ssc` → known hex; `base64-roundtrip.ssc`
  → "true"). Smoke at 12 fixtures, 67/67 unit tests (7 new).
  RustCapabilities declares `Crypto`.

## 2026-06-07 — feat(rust): R.3.1 — nowMillis + readFile + writeFile

- **rust-backend-r3-time-fs** — First slice of R.3. Three intrinsics
  with no extra crate deps: `nowMillis` (std::time::SystemTime),
  `readFile`/`writeFile` (std::fs). FS helpers take `&str` so the
  caller keeps ownership of the String; RustCodeWalk gains a
  `BorrowedArgIntrinsics` set + `&arg` wrapping for that case.
  RustCapabilities declares `FileSystem`. New fixture
  `fs-roundtrip.ssc` — write+read → "true". Smoke at 10 fixtures,
  60/60 unit tests.

## 2026-06-07 — feat(rust): R.2.5 — for-comprehensions + List/Vec — R.2 complete

- **rust-backend-r2-for-comprehensions** — Fifth and final slice of
  R.2. RustCodeWalk lowers `List[T]`/`Vec[T]` → `Vec<T>`, `List(args)`
  / `Vec(args)` ctor → `vec![…]`, `for x <- xs yield expr` →
  `into_iter().map(move |x| { expr }).collect::<Vec<_>>()`, and
  `.size`/`.length`/`.len` → `.len() as i64`. RustCapabilities
  declares `ForComprehensions` + `ExtensionMethods` +
  `DefaultParameters`. New fixture `for-yield.ssc`. Smoke at 9
  fixtures, all green. 55/55 unit tests. **Phase R.2 complete.**

## 2026-06-07 — feat(rust): R.2.4 — closures + function types at params

- **rust-backend-r2-closures** — Fourth slice of R.2. RustCodeWalk
  lowers `Type.Function` (`A => B`) at param positions to Rust
  `impl Fn(A) -> B`, and `Term.Function` (`(x: T) => body`) to
  `move |x: T| { body }`. Apply now falls back to direct `name(args)`
  for unknown free names so closure-parameter calls (`f(x)` inside
  HOF body) work; legitimate typos surface as cargo errors at build
  time, not silently. Stored closures (`Box<dyn Fn>` for values)
  deferred to a follow-up. New fixture `higher-order.ssc` —
  `apply(x => x*2, 21)` → "42". 50/50 unit tests; smoke up to 8
  fixtures, all green.

## 2026-06-07 — feat(rust): R.2.3 — Scala 3 enum + pattern match

- **rust-backend-r2-pattern-match** — Third slice of R.2. RustCodeWalk
  lowers Scala 3 `enum` → Rust `pub enum E { Ctor { field: T, … }, … }`,
  constructor application → `E::Ctor { field: arg, … }`, `Term.Match`
  → Rust `match`, and the pattern shapes the case-class fixture
  needs (`Pat.Extract`, `Pat.Var`, `Pat.Wildcard`, primitive `Lit.*`).
  `mapType` accepts enum names as types. RustCapabilities declares
  `PatternMatching`. New fixture `shape-match.ssc` — Shape enum +
  area function; `area(Circle(3.0))` → `28.259999999999998`. Smoke
  script up to 7 fixtures, all green. 46/46 unit tests (4 new).

## 2026-06-07 — feat(rust): R.2.2 — var/val/while/reassignment

- **rust-backend-r2-mutable-while** — Second slice of R.2. RustCodeWalk
  lowers `Defn.Val` → `let`, `Defn.Var` → `let mut`, `Term.While` →
  Rust `while`, and `Term.Assign` → reassignment. RustCapabilities
  declares `MutableState` + `WhileLoops`. New fixtures `while-fib.ssc`
  (iterative fib via mut/while) and `mutable-counter.ssc`. Smoke
  script now exercises 6 fixtures, all green. 42/42 unit tests in
  backendRust (3 new).

## 2026-06-07 — feat(rust): R.2.1 — typed params, If, infix, user calls, s"..."

- **rust-backend-r2-literals-blocks** — First slice of Phase R.2.
  RustCodeWalk grows: typed primitive parameters (Int/Long/Double/
  Boolean/String/Unit), non-Unit return types, `if`/`else` lowering,
  arithmetic + comparison + boolean infix operators, calls to in-scope
  user-defined fns, and `s"…"` → `format!(...)`. `_println`/`_print`
  runtime helpers widened to take `impl Display` so non-string args
  print. `String` literals emit as owned `.to_string()` for safe pass
  to `String`-typed parameters. Crate-name sanitizer drops hyphens
  (Rust module names reject them). build-rust/run-rust now lookup the
  binary by sanitized stem. New fixtures `examples/rust/fib.ssc` and
  `string-interp.ssc`; smoke script up to 4 fixtures, all green.
  39/39 backendRust tests (10 new R.2 tests).

## 2026-06-07 — test(rust): end-to-end build-and-run smoke script

- **rust-backend-r1-build-smoke** — Added `tests/rust-build-smoke.sh`
  that exercises `ssc build-rust` against `examples/rust/*.ssc`
  fixtures (hello.ssc + mixed.ssc), runs each produced binary, and
  diffs the first stdout line against a script-registered expected
  string. Cargo-missing path is a clean skip + exit 0; cargo-present
  failures are exit 1 with the build log on stderr. Smoke-verified
  locally green + cargo-missing skip. Phase R.1 of the rust target
  is now feature-complete: full crate emit, three CLI commands,
  mixed `rust` blocks, end-to-end smoke, and full docs.

## 2026-06-07 — docs(rust): full rust-backend coverage across docs/ + README

- **rust-backend-r1-docs** — Added `docs/rust-backend.md` (full user
  guide: cargo prerequisites, three-CLI surface, output crate shape,
  mixed `scalascript`/`rust` blocks, R.1 capability matrix, R.2–R.6
  roadmap pointer). Updated `docs/targets.md` (replaced "Native Backend
  (Future)" with a real Rust Backend section + target-matrix row),
  `docs/README.md` (architecture index), `docs/user-guide.md`
  (runtime/build matrix + a worked "Compiling to a native binary
  via Rust" subsection), and the root `README.md` (fence-tag matrix,
  CLI cheatsheet, doc index). No code changes; grep over docs/ +
  README.md confirms full coverage across all five files.

## 2026-06-07 — feat(interpreter): String lexicographic comparison operators

- **busi-p1-string-comparison-ops** — `String <`, `<=`, `>`, `>=` and
  `compareTo` now work on `StringV` via Java's `String.compareTo` as the
  canonical Unicode codepoint ordering.  Needed by busi for UUID v7
  time-ordering and sort-key comparisons.  Previously threw
  `[ERROR] No method '<=' on StringV(...)`.  8 regression tests cover
  every operator + UUID v7 ordering use case + `List.sortWith` on
  Strings.

## 2026-06-07 — feat(rust): `rust` fence blocks pass through verbatim

- **rust-backend-r1-rust-source-blocks** — Markdown sources targeting
  the rust backend can now mix `scalascript` and `rust` fence blocks.
  `RustSourceLanguage` registered via META-INF/services;
  `RustBackend.acceptedSources` adds "rust"; `RustCodeWalk` appends
  every `rust` block verbatim under `// ── rust block <N> ──` after
  the SS-derived `pub fn`s. End-to-end smoke verified: mixed .ssc →
  `ssc build-rust` → `cargo build` → runs both the SS `println` path
  and ships the rust block's `util()` for cross-call. 5 new tests;
  29/29 green in backendRust.

## 2026-06-06 — feat(rust): ssc run-rust — build + execute one-shot

- **rust-backend-r1-cli-run-rust** — `ssc run-rust hello.ssc` emits
  the Cargo crate to a temp dir, runs `cargo build`, executes the
  produced binary forwarding argv after `--`, propagates the binary's
  exit code, then deletes the temp dir. Shares RustToolchain with
  build-rust so the missing-cargo message is byte-identical. Smoke-
  tested end-to-end: `Hello from Rust via run-rust` printed in one
  command. Rust target is now fully wired through the CLI:
  emit-rust (artefact), build-rust (binary), run-rust (run).

## 2026-06-06 — test(parser): regression guard for foldLeft brace-lambda module export

- **busi-p0-foldleft-brace-lambda** — Closed by P0 #1.  busi-72 saw the
  brace-block form `xs.foldLeft(0) { (a, b) => a + b }` "silently break"
  module exports.  Root cause was the `type_:` trailing-underscore
  identifier in the same module dropping the whole code block's parse
  tree — once that was fixed (P0 #1), brace-lambda exports work.  Added
  `FoldLeftBraceLambdaModuleTest` (5 tests) as a regression guard
  covering no-package, with-package, the busi-72 combo
  (`type_:` + brace-lambda), and nested brace-lambdas.

## 2026-06-06 — feat(rust): ssc build-rust — one-shot native binary

- **rust-backend-r1-cli-build-rust** — `ssc build-rust hello.ssc`
  emits a Cargo crate to a temp dir, runs `cargo build --release`,
  copies the produced binary to `-o <path>` (default `./<stem>`),
  cleans up. `RustToolchain` helper centralises cargo presence
  check + fixed missing-cargo message (brew + rust-lang.org link,
  exit 1, nothing else). Shutdown-hook process-tree kill keeps cargo
  from leaking on Ctrl-C, same shape as `run-jvm`. RustGen now reads
  `BackendOptions.extra("binName")` so the CLI can pin the binary to
  the file stem. RustCapabilities adds StringInterpolators +
  ModuleImports so a hello-world passes CapabilityCheck. Smoke-tested
  end-to-end with a real cargo on this host: `./hello` →
  `Hello from Rust`. Smoke-tested missing-cargo with PATH stripped of
  ~/.cargo/bin — exact spec wording printed, exit 1.

## 2026-06-06 — fix(parser): trailing-underscore identifier before `:` parses

- **busi-p0-trailing-underscore-ident** — `def foo(type_: Int)`,
  `case class E(type_: String, payload_: String)`, `val type_: Int = 1`,
  and lambdas with trailing-underscore parameters now parse correctly.
  scalameta's Scala3 lexer reads `name_:` as one operator identifier
  (since `:` is an operator character and `_` allows operator
  continuation), so the natural ascription form used to throw
  `identifier expected but ')' found`.  The whole code block then
  silently dropped its parse tree, and importing modules saw zero
  exports — appearing as "save not found in mod.ssc".  Fix: new
  `preprocessTrailingUnderscoreColon` preprocessor (priority 5) that
  inserts a space between a trailing `_` of an identifier and a
  following `:`, skipping strings, char literals, and comments
  verbatim.  `::` (cons) and `:=` (actor assign) are not touched.
  10 regression tests; full `core/test` + `backendInterpreter/test`
  green.

## 2026-06-06 — feat(rust): ssc emit-rust CLI command

- **rust-backend-r1-cli-emit-rust** — `EmitRustCmd` registered via
  ServiceLoader. Calls `compileViaBackend("rust", path)` and writes
  every emitted asset under `./<stem>-rust/` (or `-o <dir>`). `--print-only`
  streams asset bodies to stdout with `// ── name ──` separators.
  `Diagnostic.Generic` results surface as `[error] ...` on stderr
  with exit code 1. CommandRegistryTest expects "emit-rust".

## 2026-06-06 — feat(rust): main.rs/lib.rs assembly + end-to-end golden

- **rust-backend-r1-hello-main-assembly** — last slice of R.1.3 hello-emit.
  Full Cargo crate skeleton (`Cargo.toml`, `src/value.rs`, `src/runtime/
  mod.rs`, `src/generated/mod.rs`, `src/generated/<crate>.rs`, `src/main.rs`
  or `src/lib.rs`) ready for `cargo build`. RustCodeWalk now identifies
  the `@main`-annotated def and reports it as `mainEntry`; the shim
  emits `fn main() { generated::<crate>::<entry>(); }`. End-to-end
  golden test asserts every file byte-for-byte. 4 new tests, 24 green.

## 2026-06-06 — feat(rust): scalameta walk for hello-world emit

- **rust-backend-r1-hello-code-walk** — third slice of R.1.3 hello-emit.
  RustGen now runs Denormalize and RustCodeWalk walks scalameta
  `Defn.Def` + `Term.Apply` + literals. Emits
  `src/generated/<crate>.rs` with one `pub fn name()` per top-level
  def; calls to `println` / `print` / `Console.*` route through the
  RustIntrinsics RuntimeCall entries (`crate::runtime::_*`). Anything
  outside the narrow R.1 subset returns CompileResult.Failed with a
  Diagnostic.Generic naming the offending shape — never a silent
  miscompile. 6 new tests; main.rs shim is the next slice.

## 2026-06-06 — feat(rust): runtime templates + console intrinsics

- **rust-backend-r1-hello-runtime-files** — second slice of R.1.3
  hello-emit. `RustGen.generate` now writes three Cargo assets in a
  fixed order: Cargo.toml, src/value.rs (closed Value enum),
  src/runtime/mod.rs (_show/_print/_println over Value). Both runtime
  files are byte-identical across crates (infrastructure templates).
  `RustIntrinsics` wires println/print/Console.println/Console.print
  to `RuntimeCall("crate::runtime::_*")`. 7 new tests; intrinsics
  table is reachable by CapabilityCheck but the actual call-site
  rewrite lands in the code-walk slice (next).

## 2026-06-06 — feat(rust): emit Cargo.toml with target detection

- **rust-backend-r1-hello-cargo-toml** — first slice of R.1.3 hello-emit.
  `RustGen.generate` now emits a single `Segment.Asset("Cargo.toml")`
  derived from the module: crate name (sanitized to Cargo's alphabet),
  version, edition pinned to "2021", optional description with TOML
  basic-string escaping, and `[[bin]]` / `[lib]` chosen by scanning
  code blocks for an `@main` annotation. 7 unit tests + a hello-world
  golden. The next slice adds value.rs and runtime/mod.rs as fixed-
  template runtime assets.

## 2026-06-06 — feat(rust): backend-rust sbt module + SPI registration

- **rust-backend-r1-module-skeleton** — second slice of the Rust target
  roadmap. Created `runtime/backend/rust/` sbt module (mirrors
  backendWasm) with RustBackend (id="rust"), RustCapabilities
  (Feature.ConsoleIO only), RustIntrinsics (empty), RustGen
  (Segmented(Nil) placeholder), and META-INF/services entry so
  ServiceLoader[Backend] discovers it. BackendRegistryTest now
  asserts `lookup("rust")` returns a backend named "Rust". No emit
  yet — that lands in the hello-emit slice.

## 2026-06-06 — feat(spi): OutputKind.RustSource + SpiVersion 0.2.0

- **rust-backend-r1-spi-output-kind** — first slice of the Rust target
  roadmap (specs/rust-backend.md §12). Added `OutputKind.RustSource`
  enum case for the Cargo-crate output shape; bumped `SpiVersion.Current`
  from "0.1.0" to "0.2.0" (minor bump, additive — no pattern matches
  on `OutputKind` exist anywhere in the tree). Migrated the two
  hardcoded "0.1.0" comparison sites in PluginCommands and
  BackendRegistryTest to `SpiVersion.Current`. No behaviour change.
  All test suites unchanged vs baseline.

## 2026-06-06 — feat(jit): ASM s"..." port + String concat (apply-infix-ref subset)

- **jit-uc-stage8-string-interp-asm** — ported Javac `s"..."` lowering to ASM
  (StringBuilder + StringV wrap; numeric append(J), ref via Value.show);
  emitObject delegates to walkRef for LongToObject path.
- **jit-uc-stage8-apply-infix-ref (partial)** — Javac walkRef now compiles
  `String + Long` / `String + ref` concat. BigInt/Decimal/List/Map infix still
  defers (each needs JitRefDispatch helper). 1454 tests green.

## 2026-06-06 — feat(jit): UnknownShape tail observability

- **jit-uc-stage8-unknownshape-tail** — 5 new bail reasons + classifier
  wiring for Term.Throw / Tuple / Eta / Return / NewAnonymous. Corpus UnknownShape
  stays 20 (not in tests), but real-world debugging now sees the right bucket.
  3 focused classifier tests; 1452 tests green.

## 2026-06-06 — feat(jit): s"..." interpolation lowering (Javac)

- **jit-uc-stage8-string-interp** — Javac walkRef now compiles `s"prefix${e}suffix"`
  as `new StringV(part0 + arg0 + …)`; numeric args via direct concat, ref args
  via Value.show. ASM deferred. 1449 tests green.

## 2026-06-06 — feat(jit): NonExtractPattern split (observability)

- **jit-uc-stage8-nonextract-pattern-residual** — Classifier-only split of
  NonExtractPattern into TypedPattern (`case x: T =>`), NestedTuplePattern
  (`case (a, (b, c)) =>`), AlternativeWithBindings; 3 focused tests; corpus
  19 NonExtractPattern unchanged (sub-Pat.Extract in tuples — separate slice).

## 2026-06-06 — feat(jit): pattern-guard Long-fallback

- **jit-uc-stage8-pattern-guard-complex** — Javac `guardBoolExpr` + ASM
  `emitGuardBool` extend match-guard compilation with `walkLong != 0L` fallback
  (mirrors stage-6 bool-body-ext). New shapes like `case x if (n % 2) =>`
  now JIT. 1444 tests green.

## 2026-06-06 — feat(jit): VmCompiler typed-bail migration (vm Other 290 → 32)

- **jit-uc-stage8-vm-bail-migration** — All 46 VmCompiler `bail(...)` sites
  migrated to typed `JitBailReason`; 6 new VM-specific cases added; reuses 9
  generic ones; observability win, no behaviour change. 1443 tests green.

## 2026-06-06 — feat(jit): Stage 7 numeric object dispatch

- **jit-uc-stage7-numeric-object-dispatch** — Javac and ASM now compile
  BigInt/Decimal constructor-result object methods through the dedicated
  numeric-object helper path (`LongToObject` + `JitRefDispatch`) instead of
  generic ref-chain dispatch. Covered `abs`, `negate`, `pow`, `gcd`,
  `toDecimal`, `setScale`, and `toBigInt`; generic `mkString` and
  `Map.getOrElse` object ref-chain dispatch stays intact. Full
  `backendInterpreter/test` is green at 1443 tests; total JIT-disabled count
  narrowed from 733 to 717 with no `NumericObjectMethodCall` misses left in
  the runtime profile.

## 2026-06-06 — feat(jit): Stage 7 UnknownShape tagging

- **jit-uc-stage7-unknownshape-tagging** — Added classifier-only
  `JitBailReason` buckets for ref-like infix ops, string interpolation,
  type applications, for-comprehensions, `new` allocations, expression-callee
  HOF apply shapes, and direct non-param global/constructor calls. Full
  `backendInterpreter/test` is green at 1441 tests; `UnknownShape` narrowed
  from 238 to 20, meeting the Stage 7 P3 `<100` target.

## 2026-06-06 — feat(jit): Stage 7 object ref-chain dispatch

- **jit-uc-stage7-refchain-object-dispatch** — Javac and ASM now compile the
  low-risk object/String ref-chain slice: `(0 until n).map(...).mkString(...)`
  and object-returning `Map.getOrElse` as `LongToObject`. Added
  `NumericObjectMethodCall` for BigInt/Decimal constructor-result method
  calls, narrowing `RefChainObjectCall` from 22 to 14 and splitting out 8
  numeric-object cases. Full `backendInterpreter/test` is green at 1434 tests.

## 2026-06-06 — feat(jit): Stage 7 typeclass fold classification

- **jit-uc-stage7-typeclass-fold** — `typeclass-fold` is now classified as
  active context-bound typeclass dispatch via `TypeclassUsingDispatch`, rather
  than being grouped with ordinary `UsingParams` or monomorphic HOF receiver
  chains. Added a focused JitLint regression and a warmed
  `typeclass-fold` JMH alias. Quick 1/3/1 JMH reports
  `0.010 +/- 0.008ms/op`; full `backendInterpreter/test` is green at
  1429 tests with `TypeclassUsingDispatch` split out in JIT stats
  (`javac=4`, `asm=1`).

## 2026-06-06 — feat(jit): Stage 7 HOF method dispatch

- **jit-uc-stage7-hof-method** — Javac and ASM now compile numeric
  Option/Either/List/Range HOF method chains through compact lambda descriptors
  and `JitHofDispatch` helpers. `option-chain` and `either-chain` measure
  0.002ms/op, while `hof-pipeline` and `range-sum` are approximately
  0.001ms/op with the quick 1/3/1 JMH config. Full `backendInterpreter/test`
  is green at 1428 tests; corpus miss stats remain 731 disabled /
  238 UnknownShape / 70 Compound.

## 2026-06-06 — feat(jit): Stage 7 RefChainCall bucket split

- **jit-uc-stage7-refchain-bucket-split** — `RefChainCall` now only tracks the
  narrow primitive local/direct ref-read subset. The former 55-case bucket is
  split into `QualifiedRefCall=33` and `RefChainObjectCall=22`, with
  `RefChainCall=0` on the real corpus. Full `backendInterpreter/test` is green
  at 1419 tests; total disabled remains 731.

## 2026-06-06 — feat(jit): Stage 7 ref-local numeric dispatch

- **jit-uc-stage7-refchain** — Javac and ASM now co-emit ref-returning sibling
  calls, bind immutable ref locals as `Object`, and inline narrow numeric ref
  reads through `JitRefDispatch` (`getOrElseLong`, `sizeLong`, `headLong`).
  `val r = parse(n); r.getOrElse(7)` JITs on both backends. Full
  `backendInterpreter/test` is green at 1416 tests; total disabled 734→731.
  The aggregate `RefChainCall` bucket remains 55 because it also contains
  broader object/generic/effect method chains; follow-up recorded in `SPRINT.md`.

## 2026-06-06 — fix(language): Markdown toolkit Apply status

- **markdown-toolkit-apply-effect** — `toolkit:signalText` links now accept an
  optional `value=` seed so Markdown-authored status text can start with a
  readable value before a button updates it. The live
  `examples/markdown-toolkit-links.ssc` page now shows `Not applied yet` and
  changes it to `Applied from Markdown` when `Apply Markdown controls` is
  clicked after enabling the checkbox. Verified with 20 content-plugin tests,
  6 frontend smoke tests, and `backendJvm/Compile/compile`.

---

## 2026-06-06 — feat(jit): UnknownShape analysis + stage-7 plan

- **jit-uc-stage6-unknownshape-hof-analysis** — HofMethodCall + RefChainCall bail
  reasons added to JitBailReason+JitLint; UnknownShape 295→240; 55 RefChainCall
  hits; stage-7 plan in §9 of specs/jit-universal-coverage.md. 1413 tests green.

## 2026-06-06 — feat(jit): RETREF opcode — ref-typed VM return (vm-retref -18)

- **jit-uc-stage6-vm-retref** — RETREF=49 opcode added to SscVm with TLS slot;
  VmCompiler unifyRet(TRef) allowed + emits RETREF; JitRuntime wrapRef() reads
  lastRefResult(); 18 vm ret:ref-typed-return misses eliminated. 1413 tests green.

## 2026-06-06 — feat(jit): Pat.Tuple match support (NonExtractPattern -27)

- **jit-uc-stage6-nonextract-tuple** — `Pat.Tuple` patterns in Javac + ASM backends:
  casts scrutinee to TupleV, accesses elems() by index; JitLint no longer reports
  NonExtractPattern for simple Var/Wildcard sub-patterns. 4 new tests, 1411 total green.

## 2026-06-05 — feat(jit): walkBool Long-fallback for bool-returning matches

- **jit-uc-stage6-bool-body-ext** — `walkBool` fallback to `walkLong` in both
  backends. Bool-returning literal match (`isZero(n) = n match`) now compiles.
  Complex guards where `walkBool` fails but `walkLong` succeeds also enabled.

---

## 2026-06-05 — feat(jit): pattern guard support in match sub-expressions

- **jit-uc-stage6-pattern-guard** — Guards in match used as sub-expressions
  (val binding, if-condition etc.) now compile on both Javac and ASM backends.
  `walkMatchExpr` adds `hasAnyGuard` if-chain path mirroring existing guard path
  in `walkMatchBody`. 118 SscVmTest pass + new stage6-pattern-guard test.

---

## 2026-06-05 — feat(language): Markdown toolkit links

- **markdown-toolkit-links** — Added ordinary Markdown `toolkit:` links for
  simple frontend controls, so authors can declare `textField`, `checkbox`,
  `button`, `signalText`, `badge`, and `divider` controls in Markdown markup
  instead of a YAML control fence. `toolkit:` links stay in `DocumentContent`
  rather than being classified as imports; selected toolkit regions allocate
  shared reactive signals for repeated `signal=` references. Added
  `examples/markdown-toolkit-links.ssc` as a live `serve(page, 8099)` example.

## 2026-06-05 — fix(jit): ASM bool-returning co-emit + walkBool dead-label fix

- **jit-uc-stage6-asm-mutual-recursion** — Fixed ASM JIT 14x regression on
  `mutual-recursion`: `ssc-asm` 20.8 ms → 1.22 ms (Javac parity at 1.20 ms).
  Root causes: (1) `Lit.Boolean` missing from `walkLong` forced bool-returning
  `isEven`/`isOdd` into `walkBool` fallback generating COMPUTE_FRAMES-incompatible
  dead code; (2) dead `GOTO Lend` in `walkBool(Term.If)` when thenp = `Lit.Boolean(false)`.
  Fixes: `walkLong` handles `Lit.Boolean`; `boolAlwaysJumps()` predicate skips
  dead GOTO/label. 117 SscVmTest pass incl. new bool-returning mutual-recursion test.

---

## 2026-06-05 — feat(language): Markdown content inline binding

- **markdown-content-inline-binding** — Added explicit `contentBind(value, bindings)`
  for resolving Markdown `${name}` and `${nested.name}` placeholders from
  `ContentValue.MapV` data without executing code. Toolkit selectors now accept
  bound data through `ContentToolkitOptions.bindings` and
  `contentToolkitOptionsWithBindings(data)`, so Markdown-authored tables and
  sections lower to bound toolkit output. Updated `examples/content-tables.ssc`
  to show both raw placeholders and the bound `$49` `TableNode` result.

## 2026-06-05 — feat(jit): JIT universal coverage — Stage 5 structural long tail

- **jit-uc-stage5-2** — `var` reassignment in pure function bodies now compiles on
  both Javac and ASM backends. `walkBlockStmts` (Javac) gains a `Term.Assign` case;
  `emitBlockStmts` (ASM) delegates non-final statements to the existing
  `emitStatAsVoid`, picking up `Term.Assign`, `Term.If` (void), and any future
  statement types for free.
- **jit-uc-stage5-1** — Mixed Long+Double match arms confirmed handled by the
  existing `bodyHasDoubleLit` heuristic: any function with a `Lit.Double` anywhere
  in its body compiles as `double`, and `walkDouble` auto-widens `Lit.Int`/`Lit.Long`
  arms. No corpus `MixedReturnType` misses observed.
- All 19 JIT universal coverage stages now done.  Spec §7 matrix and §9 task list
  updated. Final miss profile: 734 total disabled; dominant remaining categories are
  HOF complexity, tuple patterns, and explicitly out-of-scope features (varargs,
  using clauses, try/finally). See `specs/jit-universal-coverage.md`.

---

## 2026-06-05 — feat(language): Markdown content tables

- **markdown-content-tables** — Added CommonMark GFM pipe tables to
  `DocumentContent` as `ContentBlock.Table` with inline header/cell content,
  alignment metadata, and preceding `<!-- @meta ... -->` attrs. Tables now
  round-trip through normalize/denormalize and content serialization, expose
  through interpreter/generated JS/generated JVM `std/content` helpers, render
  through stable plain text and deterministic `contentToMarkdown(...)`, lower
  to semantic low-level `contentView(...)` table markup, and map to toolkit
  `TableNode`. Added `examples/content-tables.ssc` and conformance coverage.

## 2026-06-05 — feat(language): Markdown linked content namespaces

- **markdown-content-linked-namespaces** — Added `contentModules()`,
  `contentModule(namespace)`, and namespace-scoped section/block/data/metadata
  lookup for direct imported `.ssc` modules. Namespaces come from imported
  `name:` front-matter or the imported path stem; duplicate direct namespaces
  report deterministic runtime errors; transitive imports stay hidden unless
  imported directly. Interpreter, generated JS, and generated JVM paths share
  the API. Added conformance fixtures and `examples/content-linked-namespaces.ssc`.

## 2026-06-05 — feat(language): Markdown multi-link import paragraphs

- **markdown-multi-link-imports** — A pure Markdown import paragraph can now
  contain multiple links separated by spaces or line breaks. The parser lowers
  each link to a `Content.Import` in source order, keeps prose/internal links as
  content, omits pure import paragraphs from `DocumentContent`, and the
  interpreter resolves modules imported from one paragraph. Added
  `examples/multi-link-imports.ssc`.

## 2026-06-05 — feat(language): Markdown content artifact round-trip

- **markdown-content-artifact-roundtrip** — Preserved current-module
  `DocumentContent` snapshots through `.scir` and `.sscc` artifacts.
  `.scir` now has regression coverage for JSON and MessagePack body
  round-trips, while `.sscc` v3 writes an optional trailing content blob after
  `ModuleEnd` so plain/gzip artifact-backed runs can still execute
  `contentDocument()` / `contentToMarkdown(...)` without reparsing source.

## 2026-06-05 — feat(language): Markdown content to Markdown

- **markdown-content-to-markdown** — Added `contentToMarkdown(value)` for
  `DocumentContent`, `SectionContent`, and `ContentBlock` values on the
  interpreter, generated JS, and generated JVM paths. The renderer emits stable
  semantic Markdown with front-matter, section/block metadata, inline markup,
  lists, images, and fenced embedded source text; exact source whitespace
  preservation remains out of scope.

## 2026-06-05 — feat(language): Markdown content native client parity

- **markdown-content-native-client-parity** — Added a native Markdown controls
  example and shared native lowering so `yaml @ui=toolkit` controls from
  Markdown render as Swing `JTextField` / `JCheckBox` / `JButton`, JavaFX
  `TextField` / `CheckBox` / `Button`, and SwiftUI `TextField` / `Toggle` /
  `Button`. JVM codegen now exposes the content toolkit helper set for native
  frontends, and focused emitter/runtime tests cover scalar signal defaults.

## 2026-06-05 — spec(language): Markdown content native client parity

- **markdown-content-native-client-parity-spec** — Added the focused contract
  for rendering Markdown-authored controls and metadata through Swing, JavaFX,
  and SwiftUI. The spec keeps native clients on the shared
  `DocumentContent -> TkNode/View -> native emitter` path, treats Swing/JavaFX
  low-level `std/content` as covered by JVM exposure, and keeps SwiftUI as
  frontend emission rather than a separate Swift content runtime.

## 2026-06-05 — feat(language): Markdown content backend exposure

- **markdown-content-backend-exposure** — Exposed the low-level `std/content`
  helper set on generated JS and JVM backends: `contentDocument`,
  `contentCurrentSection`, `contentSection`, `contentBlock`, `contentData`,
  `contentMetadata`, and `contentPlainText`. Generated backends embed the
  module `DocumentContent` snapshot, preserve current-section execution scope,
  and the conformance fixture now matches across interpreter, JS, and JVM.

## 2026-06-05 — spec(language): Markdown content backend exposure

- **markdown-content-backend-exposure-spec** — Added the focused JS/JVM
  exposure plan for the landed interpreter `std/content` helpers. The spec
  defines snapshot embedding, lookup/plain-text parity, current-section
  scoping, codegen-safe plugin intrinsic ownership, conformance expectations,
  and the no-wrapper rule needed to preserve generated top-level bindings.

## 2026-06-05 — feat(language): Markdown content current section

- **markdown-content-current-section** — Added interpreter
  `contentCurrentSection(): SectionContent` for the currently executing code
  block's enclosing Markdown section. The helper returns explicit/generated
  ids, heading attrs, sibling prose/list blocks, and execution-time caller
  context for functions; headingless code reports an interpreter error rather
  than reusing stale section state.

## 2026-06-05 — feat(language): Markdown content metadata lookup

- **markdown-content-metadata** — Added interpreter
  `contentMetadata(path): Option[ContentValue]` for `content:` front-matter
  metadata. Dot paths traverse nested maps; missing `content:`, missing
  segments, and non-map traversal return `None`; malformed paths report an
  interpreter error. The content-introspection example now checks the
  `defaultRenderer` metadata.

## 2026-06-05 — feat(language): Markdown content lookup and plain text

- **markdown-content-lookup-plaintext** — Added interpreter `std/content`
  helpers `contentSection(id)`, `contentBlock(id)`, and
  `contentPlainText(value)`. Missing section/block lookups return `None`;
  duplicate block ids and unsupported plain-text inputs report interpreter
  errors. The live content-introspection example now prints section/block text
  extracted from Markdown-authored regions.

## 2026-06-05 — feat(jit): VmCompiler p3+p4 inner def + p5 Lit.Null

- **jit-completeness-p3p4** — `compileStmt` now handles `Defn.Def`: extracts
  params/types from `paramClauseGroups`, creates a `Value.FunV` with empty
  closure, and calls `ctx.compileFn` to compile it into the shared call pool.
  A new `innerDefs` map makes the inner def findable from `callTarget`. Capturing
  inner defs (body references outer locals) bail with "undefined: name '...'" —
  this correctly disables the outer function too. Fixes the 2
  "unsupported: stmt Defn.Def" miss category.

- **jit-completeness-p5** — `compileInto(Lit.Null(), dst)` now emits
  `CONST 0 / TRef` instead of bailing. Using `null` as a sentinel `val` in a
  function body compiles; returning `null` still bails at `unifyRet(TRef)` (RET
  is Long-typed by design). Fixes the 2 "unsupported: Lit.Null" miss category.

  4 new SscVmTest tests; 93/93 pass; no bench regression (recursionFib 1.214 ms,
  recursionTco 29 µs, recursiveEval 0.066 ms).

## 2026-06-05 — feat(language): Markdown content data binding

- **markdown-content-data-binding** — Added `contentData(id)` for interpreter
  lookup of fenced YAML/JSON/TOML data by explicit `@id`, and connected
  `data=<id>` metadata to `ContentComponentContext.data` for registered
  `std/ui` toolkit components. Missing data references yield `None`; duplicate
  structured data ids report an interpreter error.

## 2026-06-05 — feat(language): Markdown content component registry

- **markdown-content-component-registry** — Added explicit
  `contentComponent(name)(render)` registration for Markdown `component=<name>`
  metadata. Registered renderers replace default `contentToolkitNode` /
  `contentToolkitBlock` / `contentToolkitSection` lowering for matching blocks
  or sections; missing registry entries fall back to default Markdown lowering.

## 2026-06-05 — perf(jit): String-returning co-emission lane

- **jit-string-concat** — Both bytecode-JIT backends (javac + asm) gain a
  String walker so functions that build and return Strings JIT instead of
  bailing on `Lit.String`. Javac side adds `walkString` /
  `ensureCoEmittedString` / `emitStringCall`; ASM side adds `walkString` /
  `isStringExpr` / `emitStringAppend` (StringBuilder chain flattening) /
  `buildStringDesc` / `emitStaticStringFunction` plus an `ApplyInfixPlus`
  extractor. Covers `String + concat` (numeric operands coerced via
  `walkLong`), `.length` on a String receiver, String params, and
  `Apply`→String co-emission. `string-concat` 95.7 ms → 0.094 ms (ssc) /
  95.8 ms → 0.153 ms (asm); result 188890 verified with JIT on and off.

## 2026-06-05 — feat(language): Markdown toolkit content selectors

- **markdown-content-toolkit-selectors** — Added
  `contentToolkitBlock(id)` and `contentToolkitSection(id)` so a single
  Markdown document can define multiple independent `@ui=toolkit` blocks or
  sections and compose only the selected regions. The live content-introspection
  example now selects two YAML-declared control panels by `@id`.

## 2026-06-05 — docs(example): Markdown content live serve demo

- **markdown-content-serve-demo** — Switched
  `examples/content-introspection.ssc` from `emit(page, outDir)` to
  `serve(page, 8099)`, so the Markdown-authored toolkit page is a direct
  browser/phone preview without a separate static-server step.

## 2026-06-05 — feat(jit): VmCompiler p1b arity-0 + p2 Term.Select field access

- **jit-completeness-p1b-vmcompiler** — Removed the `arity < 1` guard in
  `VmCompiler.buildInstructions` so zero-param functions can now be compiled
  by the register VM as callees within compiled call graphs. Added TRef param
  type tracking (`setRefType`) for use by p2 field access.

- **jit-completeness-p2-term-select** — New `Term.Select` case in
  `VmCompiler.compileInto`: compiles standalone `obj.field` expressions using a
  `refTypeName` map to track declared type names for TRef registers and
  `ctx.metaFor` to look up field types. Emits `GETFI` for Int fields, `GETFR`
  for Ref fields (with chained type tracking). Method calls (`.head`, `.method()`
  etc.) still bail as before. 5 new SscVmTest unit tests.

## 2026-06-05 — perf(interp): JIT arity-0 thunks + nested while/var

- **jit-completeness-p1b-arity-zero** — Added a zero-arg FunV bytecode-JIT lane
  (`LongFn0`/`DoubleFn0`, `JitRuntime.tryRun0`/`invokeBytecode0`, hooked into
  `CallRuntime.callValue0`) and relaxed both backends' param-count gate to admit
  0-param functions. Generalized the Javac and ASM while-body emitters to thread
  bindings across statements so an inner `var` and a nested `while` compile.
  `nested-loop` 11.1 ms → 0.26 ms (ssc) / 11.8 ms → 0.27 ms (asm).

## 2026-06-04 — feat(language): Markdown-declared toolkit controls

- **markdown-ui-controls** — Added `yaml @ui=toolkit` support to
  `contentToolkitNode()`, so Markdown can declare toolkit signals and controls
  such as text fields, checkboxes, buttons, badges, cards, and conditional
  previews without constructing those widgets in executable `.ssc` code.

## 2026-06-04 — docs(example): Markdown toolkit demo controls

- **markdown-toolkit-demo-controls** — Updated
  `examples/content-introspection.ssc` so the phone/demo page visibly includes
  real `std/ui` toolkit controls: card, text field, checkbox, button, badge,
  and reactive preview next to the Markdown-derived `contentToolkitNode()`
  subtree.

## 2026-06-04 — feat(language): Markdown content toolkit bridge

- **markdown-content-toolkit-view** — Added `contentToolkitNode()` for turning
  the current Markdown document into a regular `std/ui` `TkNode` subtree that
  can be composed with `vstack`, `card`, routers, and themed shells before
  `lower(tree, theme)`. The content plugin now returns native-created content
  values with stable field arrays for imported helper compatibility, and
  `examples/content-introspection.ssc` demonstrates Markdown + toolkit
  composition.

## 2026-06-04 — fix(cli): Markdown frontend CLI run + LAN serve URLs

- **markdown-frontend-cli-serve-fix** — `ssc examples/content-introspection.ssc`
  now loads the `content` std plugin in the CLI path, keeps the frontend
  `emit(tree, outDir)` intrinsic from being shadowed by streams `emit(value)`,
  and `ssc serve [port] [dir]` prints detected LAN URLs for phone/tablet demos.

## 2026-06-04 — feat(language): Markdown frontend from content

- **markdown-frontend-mvp** — Added parser-side `DocumentContent`, interpreter
  `contentDocument()`, `std/ui/content.ssc` lowering, a runnable
  `examples/content-introspection.ssc`, and React emit smoke coverage for a
  Markdown-authored page with no manual UI tree construction. Broader
  `std/content` lookup helpers and cross-backend conformance remain in
  `markdown-content-introspection-api`.

## 2026-06-04 — docs(language): prioritize Markdown frontend MVP

- **markdown-frontend-mvp** — Updated the planned Markdown content milestone so
  Phase 1 is explicitly frontend from Markdown: `contentView(...)` lowering to
  the existing frontend toolkit without hand-written markup generation.
  `DocumentContent` remains the shared IR, while the full `std/content`
  metadata/introspection API is now tracked as the follow-up slice.

## 2026-06-04 — spec(language): Markdown content introspection

- **markdown-content-introspection** — Added the planned `DocumentContent` /
  `std/content` contract for reading Markdown-hosted content as typed metadata:
  prose/list/link/image nodes, YAML/front-matter, and fenced embedded language
  blocks. Also specified frontend `contentView(...)` lowering shape, user docs,
  a phase-0 example sketch, and a pending conformance fixture. Compiler/runtime
  implementation remains tracked in `BACKLOG.md`.

## 2026-06-04 — perf(interpreter): object-returning recursive ADT builder JIT

- **interp-opt-recursive-build-floor** — Phase 1B added `LongToObject` /
  `resultIsRef` plus a narrow Javac object-expression walker for pure recursive
  ADT builders. Default `scripts/bench interp recursiveEval`: `recursiveEval`
  **0.067 +/- 0.004 ms/op**, `recursiveEvalMixed` **0.068 +/- 0.001 ms/op**;
  208 targeted interpreter/JIT tests passed. ASM parity remains tracked as
  `interp-opt-recursive-build-floor-asm-parity`.

## 2026-06-04 — perf(interpreter): invariant recursive eval loop fold

- **interp-opt-recursive-eval** — Phase 1A folded invariant bytecode-JIT direct
  calls out of recursive ADT eval accumulation loops while preserving the old
  path for effects and dynamic calls. `recursiveEvalMixed`: **3.641 -> 1.924
  +/- 0.174 ms/op** with `scripts/bench interp recursiveEvalMixed`; compile,
  208 targeted interpreter/JIT tests, short benches, full mixed bench, and
  profile bench passed. Residual ~1.9 ms/op floor is now tracked separately as
  `interp-opt-recursive-build-floor`.

## 2026-06-04 — perf(interpreter): cold init allocation

- **interp-opt-init-builtins-cache** — `effectPure` cold interpreter floor is
  down from 0.010 to **0.005 ms/op**. Profile allocation is down from 32,208 to
  **8,728 B/op** by lazily initializing unused interpreter/actor/cluster state
  and using direct `System.getenv` reads in actor-cluster init. Shared pure
  builtins cache was measured and deferred. Compile, 238 targeted tests, and
  final bench/profile pass.

## 2026-06-04 — feat(types): ssc check-types GraphQL section — P4d-γ

- **type-evidence-graphql-p4d-gamma** — `ssc check-types` now prints a third
  section "GraphQL evidence:" with object/interface/input type and field counts.
  Exit code now gates on both route and GraphQL evidence being fully declared.
  Success message: "All routes and GraphQL types have declared types."
  8 `CheckTypesCliTest` pass. Completes P4d (α+β+γ all landed 2026-06-04).

## 2026-06-04 — feat(types): GraphQL evidence inventory helper — P4d-β

- **type-evidence-graphql-p4d-beta** — `GraphQLEvidenceCounts` +
  `GraphQLEvidenceInventory.count(module: ir.NormalizedModule)` in `TypeEvidence.scala`.
  Walks all sections/subsections; counts Object/Interface/Input types and their fields
  from `graphql` `EmbeddedBlock` evidence. Legacy blocks without evidence count as 1
  unknown type. `allDeclared` predicate for CI gating. 7 new tests.

## 2026-06-04 — feat(types): GraphQL SDL type evidence — P4d-α

- **type-evidence-graphql-p4d-alpha** — GraphQL SDL type evidence in IR.
  New `GraphQL{Field,Type,Block}EvidenceWire` types in `Ir.scala`; additive
  `evidence: Option[GraphQLBlockEvidenceWire]` field on `Content.EmbeddedBlock`.
  `GraphQLSourceLanguage.compileBlock` now retains the `TypeDefinitionRegistry`
  instead of discarding it and builds evidence: field types classified as
  `Declared` (SDL built-in scalars + same-block types + `ScopeContext.resolve`)
  or `Unknown` (unresolved). Invalid SDL → `evidence = None`. 8 new tests in
  `GraphQLEvidenceTest`. Backward-compatible: legacy `.scir` without `evidence`
  field still reads. P4d-β (inventory) and P4d-γ (check-types) pending.

## 2026-06-04 — feat(types): ssc check-types command (P4c)

- **type-evidence-check-cmd-p4c** — New `ssc check-types <file.ssc>` command.
  Parses and normalizes the module without running the interpreter, prints a
  two-section evidence inventory table (route evidence: endpoints/handlers
  declared/unknown; symbol evidence: Any-typed exports by evidence kind), and
  exits 0 if all routes have Declared evidence or 1 otherwise. CI-friendly gate
  for route type coverage. Uses `RouteEvidenceInventory` (P4a) and
  `AnyEvidenceInventory` (P1) internally.
  **Tests:** 6 `CheckTypesCliTest`, `CommandRegistryTest` updated.

---

## 2026-06-04 — feat(types): OpenAPI evidence diagnostics (P4b)

- **type-evidence-openapi-p4b** — Added `openApiEvidenceDiagnostics(module: Module)`
  to `EmitCommands`: normalizes the parsed AST module and returns a warning
  string for each API endpoint or remote handler whose request or response
  evidence is not `Declared`. Added `--require-declared` flag to `ssc emit-openapi`:
  runs the diagnostic check after generating the spec; if warnings exist, prints
  them to stderr and exits 1. Without the flag the check is skipped (no behavior change).
  **Tests:** 7 `EmitOpenapiCliTest` (4 existing + 3 new).

---

## 2026-06-04 — feat(types): route evidence inventory (P4a)

- **type-evidence-schema-p4a** — Added `RouteEvidenceCounts` and
  `RouteEvidenceInventory.count(ir.Manifest)` to `TypeEvidence.scala`.
  Reads `ApiEndpointTypeEvidenceWire` from each endpoint and handler; both
  request and response must be `Declared` for the route to count as declared;
  missing `typeEvidence` (legacy artifacts) counts as Unknown. `allDeclared`
  convenience predicate for CI gating.
  **Tests:** 7 `RouteEvidenceInventoryTest`.

---

## 2026-06-04 — feat(types): route metadata type evidence

- **type-evidence-routes-p3** — Added optional `ApiEndpointTypeEvidenceWire`
  on normalized IR `ApiEndpointDecl` and `RemoteHandlerDecl`. `Normalize`
  derives declared/unknown evidence from legacy request/response strings;
  generators keep reading the existing strings for this slice. `.scir`
  round-trip and legacy no-field reads are covered.
  **Tests:** 4 `RouteTypeEvidenceTest`, 17 `ArtifactIOTest`,
  8 `ApiClientsFrontmatterTest`, 9 `ClusterFrontmatterTest`,
  `core / Test / compile`.

---

## 2026-06-04 — feat(uuid): uuid-p4 + uuid-p5 — raw tier, unsafeFromString, withFixedUuid, effect wiring

- **uuid-p4/p5** — Completed the UUID stdlib across all three backends (interpreter, JVM, JS).
  New surface: `Uuid.unsafeFromString` (named coercion, throws on bad input), `.version / .isNil / .isMax / .variant` extension methods, `rawV4/rawV7` (no effect annotation, library-author escape hatch), `runSideEffect` (identity handler), `withFixedUuid(fixed)(body)` (thread-local override for deterministic tests).
  Effect wiring: `Uuid.v4/v7` registered in `containsEffectPrimitive` and `DepEffectfulnessFixpoint`; `rawV4/rawV7` intentionally excluded.
  `withFixedUuid` implemented as an AST-pattern-matched handler in `EvalRuntime` (same approach as `runRandomSeeded`) so the body term is evaluated after the thread-local is set.
  **Tests:** 15 `UuidPluginTest` cases (all pass), 4 `ContainsEffectPrimitiveTest`, 4 `DepEffectfulnessFixpointTest`.

---

## 2026-06-04 — fix(js): effect-stream JS while-loop — side-channel Stream.emit

- **js-effect-stream-while** — `effect-stream` JS now produces valid output.
  Root cause: `Stream.emit(i)` calls inside while loops returned Free monad
  `_Perform` nodes that the while loop discarded — they never reached `_handle`.
  Fix: `Stream.emit` now pushes to a module-level `_streamBuf` side-channel
  buffer when inside a `runStream` call (returning `undefined`), and the
  `runStream` body is emitted with `genExpr` (plain JS, not CPS-transformed)
  so while/var loops work correctly. `_mkStreamSource` wraps the collected
  buffer with synchronous `runToList()` / `toList()` methods.
  **Result:** `effect-stream` JS **0.327 ms/iter** (was n/a in bench.sh).

---

## 2026-06-04 — fix(jvm): effect-pure + effect-stream JVM backend — T!Eff, ThreadLocal stream

- **jvm-effect-types** — JVM backend now compiles and runs `T ! Eff` effect-typed
  functions and `runStream` with while/var loops. Four-part fix:
  1. `JvmGenTermAnalysis` — add `stdEffectRunners` to `termUsesEffects` so
     `runLogger`/`runStream` calls trigger `blockNeedsRewrite` → `emitStats`.
  2. `JvmGen emitStat` — strip `T ! Eff` return-type annotation (emit as `: Any`);
     add `.runtimeChecked` to `val (a,b) = runner(...)` tuple-destructures.
  3. JVM preamble — replace CPS `_handle`-based `runStream` with a `ThreadLocal`
     `ArrayBuffer` approach: `Stream.emit()` pushes to the buffer directly so
     `while`/`var` loops work without a CPS trampoline. `_Source.runToList()`
     returns `List[Any]` so `.length` resolves.
  4. `emitExpr`/`emitCpsExpr` — switch runner body from `emitCpsExpr`→`emitExpr`;
     intercept `.runToList()` with `asInstanceOf[_Source]` cast.
  Also: `EffectAnalysis.verify` — suppress false "declares no effect row" warning
  for sub-effecting (pure body in effect-typed def is valid). `Typer` — accept
  `() => Any !Eff` block args for runner type-checking.
  **Results:** `effect-pure` JVM **0.005 ms/op**; `effect-stream` JVM **0.067 ms/op**.

---

## 2026-06-04 — feat(ui): seedSignal editable draft primitive

- **ui-seed-signal** — Added `seedSignal(name, source: Signal[String])` for
  forms that need a writable text draft seeded from another signal.
  The draft mirrors the source while pristine; `inputChange` / `setSignal`
  marks it dirty, so later fetch refreshes do not overwrite user edits.
  Includes interpreter/JVM/JS shims, browser runtime pristine-source wiring,
  React/Vue/Solid/Custom/SwiftUI/Swing/JavaFX emitter lowering, docs, and
  `examples/seed-signal.ssc`.

---

## 2026-06-04 — perf(js): js-codegen-opt-p3 — emit-js field/arith fix + _forEach bypass

- **js-codegen-opt-p3** — Fixed `genModuleSegmented` (used by `emit-js`/`run-js`/bench)
  missing `caseClassFieldsByType` + `caseClassFieldTypeMap` initialization; added `_forEach`
  array-bypass helper. `pattern-match-heavy` JS: 35.8 ms → 5.0 ms (7.2×). 1279 tests.

---

## 2026-06-04 — feat: ssc lint-jit --include-while coverage

- **jit-lint-while-coverage** — `ssc lint-jit --include-while` now reports JIT
  coverage for top-level while loops alongside def coverage.  Source of truth is
  `interp.whileJitCache` (no interpreter changes needed).  New API:
  `JitLintWhileReport`, `JitLintWhileCompareReport`, `JitLint.lintWhileLoops`,
  `JitLint.lintWhileLoopsCompare`, `JitBailReason.WhileCondShape / WhileBodyShape`.
  12 new tests; all 39 pass.

---

## 2026-06-04 — perf: ASM JIT while/map parity

- **asm-jit-parity-optimizations Phase 2** — `AsmJitBackend` now matches the
  current Javac while/match subset: ref globals/functions in `WhileJitEntry`,
  hoisted TLS refs/ref-fns in generated while methods, `ObjToObject` ref-arg
  chains, inline ref-match RHS helpers, qualified ADT constructor patterns,
  wildcard / named catch-all ADT arms, ListV/SetV fused foreach, and MapV
  foreach key/value fusion via `mapIsKeyMode` and the runtime-provided
  pre-extracted `Object[]`. Verified with `SscVmTest` plus
  `SscVmTest`/`InterpreterTest`/`JitLintTest` under `SSC_JIT_BACKEND=asm`
  (183/183).

---

## 2026-06-04 — perf: phase-c-bytecode-wider-match (wildcard/catch-all arms)

- **phase-c-bytecode-wider-match** — `Pat.Wildcard` and `Pat.Var` catch-all arms now compile
  in all JIT arm walkers (`walkArm` switch form, `walkArmAsIfBranch` if-chain form,
  `walkArmExpr` switch-expression form). `walkMatchBody` and `walkMatchExpr` skip the
  throw-default when a wildcard arm is present. `walkRefArm`/`walkRefMatchBody` already
  supported `Pat.Var`. 17 JitLintTest + 1251 full suite green.

---

## 2026-06-04 — perf: while-jit-map-foreach (11.4×)

- **while-jit-map-foreach** — Fuses `while i < N do m.foreach((k,v) => acc += v)` into a
  single generated Java method. Key insight: the bottleneck was not Tuple2 allocation (already
  eliminated via `valuesIterator()`) but the per-outer-iteration `Iterator` object creation
  (100K × ~5 ns ≈ 500 µs). Fix: pre-extract `MapV.entries().valuesIterator().toArray` once
  at call time, pass it as `refs[0]`; generated Java iterates over `Object[] _mvals` with a
  plain `for` loop — zero per-iteration allocation.
  - `WhileJitEntry.mapIsKeyMode: Boolean = false` — tells runtime whether to extract keys or values
  - `tryCompileWhileMapForeach` emits `Object[] _mvals = (Object[]) JitGlobals.getRefs()[0]`
    with a `for (int _mi = 0; _mi < _mlen; _mi++)` inner loop
  - `tryWhileJitMixed` pre-extracts the array based on `entry.mapIsKeyMode`
  - `mapForeach`: **2.142 ms → 0.187 ms (11.4×)**. 1248 tests green.

---

## 2026-06-04 — perf: ASM JIT function parity

- **asm-jit-parity-optimizations Phase 1** — `AsmJitBackend` now matches the
  current Javac function-backend subset for shared boolean-return bails,
  unary `+`/`-`, multi-statement expression blocks, guarded ADT matches,
  direct `ObjToObject` ref-returning matches, and long-returning
  sibling/mutual co-emit including ref-param ADT match functions. String-chain
  fallback now clears `typeName` before arm-label jumps. Verified with
  `SscVmTest`/`InterpreterTest`/`JitLintTest` under `SSC_JIT_BACKEND=asm`
  (174/174). Phase 2 while-backend parity remains open in `WORK_QUEUE.md`.

---

## 2026-06-04 — test: jit-match-recursive-descent verification

- **jit-match-recursive-descent** — Verified that `JavacJitBackend.walkArm`
  correctly marks arm-bound variables passed to recursive self-calls as
  ref-typed (`bindingIsRef`), and `walkLong`'s self-call case emits
  INVOKESTATIC for both the 1-param (`def eval(e: Expr)` → ObjToLong) and
  2-param (`def gEval(scale: Int, e: Expr)` → LongObjToLong) shapes.
  Added 4 JitLintTest cases: lint + direct-interface correctness for `eval`
  (`eval(build(3)) == 27` via ObjToLong) and `gEval`
  (`gEval(2, Add(Num(1),Mul(Num(2),Num(3)))) == 26` via LongObjToLong).
  Performance confirmed at 3.57 ms / 3.66 ms (8–12× vs JIT-off baseline);
  this is the achievable floor for INVOKESTATIC traversal of a 1021-node ADT
  tree at ~3.5 ns/node. 1243/1243 tests pass.

---

## 2026-06-04 — perf: js-codegen-opt-p2

- **js-codegen-opt-p2** — Loop-invariant constant-tuple hoisting in JS codegen.
  When `(1,2)++(3,4)` appears inside a while-loop body and all elements are literals,
  the compile-time-folded result is hoisted as `const _k0 = Object.freeze(Object.assign([1,2,3,4],{_isTuple:true}))`
  before the loop; the body becomes `last = _k0; i++` with zero heap allocations.
  Three while-loop codegen sites instrumented (genFunctionBody, genBlockStats, genBlockAsIife).
  tuple-monoid: 4.24 ms/iter → 0.025 ms/iter (−99%, 170×). 1236/1236 tests passed.
  Full analysis in `specs/js-codegen-opt-p2.md`.

---

## 2026-06-03 — perf: phase-c bytecode mutual co-emit

- **phase-c-bytecode-mutual** — `JavacJitBackend` now co-emits JIT-compatible
  sibling defs as static methods in the same generated Java class, so
  long-returning sibling calls and mutual-recursion cycles no longer bail out
  of the bytecode backend. Covered by direct Javac bytecode tests for pure-int
  sibling calls, pure-int mutual recursion, and ref-param ADT match mutual
  recursion. `recursiveEval`/`recursiveEvalMixed` post-change bench stayed in
  noise versus baseline.

---

## 2026-06-03 — perf: phase-d-instancev flag flip

- **phase-d-instancev-array-repr-flag-flip** — StatRuntime case-class constructors
  pass Map.empty + populate `fieldsArr + fieldNames` in parallel; IMap.Map1/Map2/
  Map.from no longer allocated per hot InstanceV. `effectiveFields` method + overridden
  `equals`/`hashCode` unify StatRuntime vs deserialized InstanceV comparisons.
  Value.show, DerivesRuntime, DispatchRuntime, OpticsRuntime, PatternRuntime,
  SectionRuntime, ValueSerializer all updated. instanceVArrayEnabled flag removed.
  1233/1233 green. Bench: patternMatchSet 0.283 → 0.197 ms (~30%).

---

## 2026-06-03 — js-codegen-opt-p1

- **js-codegen-opt-p1** — Landed four targeted JS codegen fixes in
  `JsGen.scala`: non-recursive functions no longer get the self-TCO
  `while(true)` wrapper; tail/return-position `Term.Match` lowers to if-else
  statements instead of an IIFE; TCO multi-param reassignment uses temporary
  constants instead of array destructuring; and `++` infix calls with multiple
  RHS args preserve the full tuple. All 1233 conformance tests passed.
  Measured results and p2 follow-up notes are in `specs/js-codegen-opt-p1.md`.

---

## 2026-06-03 — coord-status clean landed worktrees

- **coord-status-clean-worktrees** — `scripts/coord-status` now reports a
  `clean landed worktrees` section for linked worktrees that are clean, unlocked,
  not ahead of `origin/main`, and whose `HEAD` is already contained in
  `origin/main`; each entry includes an explicit cleanup command. `AGENTS.md`
  documents the signal as advisory so agents can prune stale landed worktrees
  without touching dirty or live work.

---

## 2026-06-03 — while-jit-mixed-foreach-set: extend fused while+foreach JIT to SetV receivers

- **while-jit-mixed-foreach-set** — `tryCompileWhileMixed` (JavacJitBackend) and
  `tryWhileJitMixed` (EvalRuntime) now accept `Value.SetV` receivers alongside
  `Value.ListV`. For SetV, emits `Set.iterator()/hasNext()/next()` inner loop;
  all other codegen (outer while, int-assign RHSes, accumulator writeback)
  unchanged. EvalRuntime receiver-resolution match adds `sv: Value.SetV` arm.
  Bench (5i wi=3 ms/op): **patternMatchSet: 0.797 → 0.283 ms (~2.8×)**.
  1233/1233 tests green.

## 2026-06-03 — while-jit-mixed-foreach: fuse outer while + inner foreach into single JVM method

- **while-jit-mixed-foreach** — The `{ xs.foreach(s => acc = acc + fn(s)); i = i+1 }` body
  pattern ran via `tryMixedLongWhile` (a Scala `while` loop) with a
  `PreResolvedForeach` virtual dispatch per outer iteration, preventing JVM
  devirtualization of the monomorphic `fn.apply(item)` call and leaving
  per-iter TLS round-trips in place. New `tryCompileWhileMixed` in `JitBackend`
  SPI generates a single Java class with a fused outer while + inner `for` loop
  over `list.items()`: `while (cond) { for item in list: acc += fn.apply(item); i++; }`.
  `WhileJitEntry` gains `refDoubleFns: Array[ObjToDouble]` for the Double-acc
  case; `JitGlobals` adds a 4-arg `withRefs` overload and `getRefDoubleFns()`.
  `tryWhileJitMixed` in `EvalRuntime` attempts the fused JIT first, then falls
  back to the existing `tryMixedLongWhile` + `PreResolvedForeach` path.
  Bench (2f, wi=3, mi=10, ms/op):
  **patternMatchHeavy: 0.936 → 0.397 ms (2.37×);
  patternMatchWide: 1.628 → 1.389 ms (1.17×);
  interp_patternMatch (RuntimeBench): 1167 → 676 µs (1.73×, now 1.21× above JVM floor)**.
  1233/1233 tests green.

## 2026-06-03 — jit-fieldsarr-no-null-check: remove dead fieldsArr null-check from JIT arm emission

- **jit-fieldsarr-no-null-check** — After `phase-d-instancev-array-repr-activation`,
  `StatRuntime` always populates `fieldsArr` at every InstanceV construction site, so the
  defensive `faVar != null ? faVar[i] : inst.fields().apply(name)` ternary emitted by all
  four JIT arm-emission sites (`walkArm` switch, `walkArmAsIfBranch`, `walkMatchBody`,
  `walkRefArm`) was dead code. The dead branch prevented the JVM from proving `faVar`
  non-null, blocking implicit null-check elimination on the hot array-read path. Replaced
  with a direct `faVar[i]`; removed the now-unused `val fname = fieldOrder(fi)` in each
  site (4 insertions, 18 deletions). Bench (2f, wi=3, mi=5, ms/op):
  **patternMatchHeavy: 1.128 → 0.861 ms (~24%)**. 1233/1233 tests green.

## 2026-06-03 — fast-map-foreach-preresolved: PreResolvedFast Map foreach variants

- **fast-map-foreach-preresolved** — `PreResolvedFastLongMapForeach` and
  `PreResolvedFastDoubleMapForeach` complete the fast-variant series for
  `tryPreResolveForeach`. Previously the MapV path re-ran ~5 guard checks
  (enabled, params.length, usingParams, `analyzeMapAccum` IdentityHashMap
  cache, `globals.getOrElse`) and 2 TLS probes (`accSlotTls` + `accNameTls`)
  on every outer iteration. New `ResolvedLong/DoubleMapAccum` structs carry
  `accName + useFirst`; `tryResolveLong/DoubleMapAccum` check these guards
  once at setup; `runLong/DoubleAccumForeachMapFast` use the pre-wired
  `cachedSlot` field directly, bypassing TLS on each inner call.
  Bench (2f, ms/op): **mapForeach: 2.238 → 2.023 ms (~10%)**.
  1233/1233 tests green.

## 2026-06-03 — jit-lint-recognisers-pure-predicates: JitLint precision + shared predicates

- **jit-lint-recognisers-pure-predicates** — `JitPredicates` package-private object
  factors out `isBoolReturning` so `JavacJitBackend.doCompile` and
  `JitLint.classifyBailReasons` share one implementation (can't silently diverge).
  Three new `JitBailReason` variants replace `UnknownShape` for common cases:
  - `BoolBody` — body is a comparison/logical expression (`<`, `>`, `&&`, …);
    the JIT emits `long`, a bool-typed result would be mis-wrapped as Int 0/1.
  - `ZeroParams` — zero parameters; JIT requires ≥ 1 typed param.
  - `TooManyParams(n)` — > 2 parameters; JIT supports 1- and 2-param only.
  `PatternGuard` description updated: guards on ADT (InstanceV) scrutinee matches
  ARE compiled (via `walkArmAsIfBranch`); the reason only fires for Int/Long-scrutinee
  guarded matches. JitLintTest: 10 tests (was 7). 1233/1233 interpreter suite green.

## 2026-06-03 — js-while-pmatch: JS codegen — IIFE elimination + numeric arithmetic fast-path

- **js-while-pmatch** — Four optimizations to JsGen to speed up numeric JS output:

  1. **While-loop IIFE removal**: `genFunctionBody` now special-cases `Term.While`
     to emit `while(cond){body}` directly instead of routing through `genExpr`
     (which wraps in an outer IIFE). New `genWhileBodyInline` helper flattens
     `Term.Block` bodies as `;`-separated statements, eliminating the inner IIFE
     that `genBlockAsIife` emitted per iteration. **arith-loop: 5.65→1.79 ms (3.2×)**.

  2. **Case class field name access**: `caseClassFieldsByType` pre-pass (already
     scanned for API client warnings) is now stored at module scope and used in
     `genPattern`. Case class destructuring emits `scrutVar.fieldName` instead of
     `Object.values(scrutVar).slice(1)[i]`.

  3. **Double/Float numeric tracking**: `numericVars` (var/val/param declarations)
     and `numericFunctions` (`:Double`/`:Float` return types) parallel the existing
     `intVars`/`intFunctions`. Case class field types scanned in
     `caseClassFieldTypeMap`; Double/Float-typed pattern-bound variables added to
     `numericVars`. New `isNumericExpr` predicate mirrors `isIntExpr`.

  4. **Direct arithmetic for numeric expressions**: `genArith` emits `(a op b)`
     directly (no `_arith` call, no `typeof` string guard) when `isNumericExpr`
     holds for both operands. Covers `*`, `+`, `-`, `/`, `%`, `<`, `>`, `<=`, `>=`,
     `==`, `!=`. **pattern-match-heavy: all `_arith` calls eliminated from hot path**.

  Commit: `b575547c`. Tests: 1230/1230 green.

---

## 2026-06-03 — while-jit-inline-match: inline match on val-bound InstanceV

- **while-jit-inline-match** — Added `Term.Match` case to `walkLocalSlotCtx`
  in `tryCompileWhileLong`. When the match scrutinee resolves to a val-bound
  `InstanceV` ref slot, a static helper method `fn_imatch_HASH(Object scrutName)`
  is co-emitted using the existing `walkMatchBody` infrastructure — typeTag
  switch + Int field extraction compiled to native bytecode. Call site:
  `fn_imatch_HASH(_rN)`. Guard: `!ctx.isCallee` (callee static methods have
  no ref preamble). Covers `total + (p match { case Pair(a,b) => a+b })`
  inline ADT matches in tight while loops.
  Commit: `3f05c7f0`.  Tests: 1230/1230 green.
  **Bench win (wi=3 mi=5 ms/op):**
    `instanceFieldAccess`: 8.4 → 0.043 ms (~195×)

---

## 2026-06-03 — tco-mutual-tail-jit-bypass: JIT bypass for wrapper→TCO calls

- **tco-mutual-tail-jit-bypass** — Fixed 1218× regression in `recursion-tco`
  benchmark (313 ms → 0.257 ms) caused by mutual-tail-call detection.
  
  **Root cause**: `def workload() = sumTco(100000, 0)` — the tail call to
  `sumTco` sets `tcoInfoFor(workload).tailTargets = Set("sumTco")` → `hasMutualTail=true`
  → `tcoTrampoline` activated for `workload`. Inside the trampoline, `sumTco` is
  replaced by a `MutualTailCall` stub. When fired, the trampoline switches to
  `curFun=sumTco` and tree-walks `sumTco.body` 100K times (each iteration throws
  a `TailCall` exception), bypassing the bytecode-JIT while-loop entirely.
  
  **Fix**: in the `MutualTailCall` handler of `tcoTrampoline`, try
  `JitRuntime.tryRunList(next, mc.args, eager=true)` before re-entering the
  trampoline loop for `next`. If JIT (bytecode or register-VM) handles the
  call, return the result directly; tree-walk only when JIT returns null.
  
  Commit: `4e22abb5`. Tests: 1230/1230 green.
  **Bench wins (bench.sh --warmup 3 --reps 5):**
    `recursion-tco` (`sumTco(100000, 0)`):  313 ms → 0.257 ms (1218×)
    `recursion-fib` (`fib(30)`):            1.32 ms (unchanged)

---

## 2026-06-03 — while-jit-ref-select-chain: field-select + ObjToObject chain args

- **while-jit-ref-select-chain** — Extended `walkRefArgCtx` in
  `tryCompileWhileLong` with two new term shapes:
  1. `Term.Select(Name(n), field)` — field access on a val-bound `InstanceV`
     global: resolves `n.field` at compile time, registers dotted key `"n.field"`
     in `refNames`. At invocation time `tryWhileJit` resolves dotted keys via a
     two-level `globals → InstanceV.fields` lookup.
  2. `Term.Apply(fn, [refArg])` where `fn` is `ObjToObject`-compiled — chained
     ref call: emits `_objFnN.apply(innerRef)` in generated Java. New
     `refObjFns: Array[ObjToObject]` field on `WhileJitEntry`; new TLS slot
     `refObjFnsTls` + `getRefObjFns()` in `JitGlobals`. `withRefs` gains a 3rd
     arg for `ObjToObject` instances.
  Commit: `225d7e32`.  Tests: 1230/1230 green.
  **Bench wins (wi=3 mi=5 ms/op):**
    `refFieldArg`  (`f(item.right)`):          9.2 → 0.046 ms (~200×)
    `refChainArg`  (`leafVal(getLeft(tree))`):  9.7 → 0.308 ms (~31×)

---

## 2026-06-03 — while-jit-ref-args: ObjToLong calls in tryCompileWhileLong

- **while-jit-ref-args** — Extended `tryCompileWhileLong` (the Java-source
  while-loop JIT) to compile loops that call a JIT-compiled `ObjToLong`
  function with a val-bound `InstanceV` argument. New `WhileJitEntry` replaces
  bare `Method` in the `JitBackend` SPI; carries `refNames` (variable names
  to read from `interp.globals` at each invocation) and `refFns`
  (pre-resolved `ObjToLong` instances). `JitGlobals.withRefs(refs, fns)` TLS
  sets both arrays around each `method.invoke`; generated Java reads
  `JitGlobals.getRefs()` / `getRefFns()` once before the loop.
  Guards: `isInstanceOf[ObjToLong]` prevents ObjToObject functions from
  being misidentified; `isCallee = true` blocks the ref path inside
  co-emitted callee static methods (which have no ref preamble).
  Commit: `b1c728af`.  Tests: 1230/1230 green.
  **Bench wins (wi=5 mi=5 ms/op):**
    `patternGuard` 12.4 → 0.044 ms (282×)
    `matchBodyBaseline` 8.4 → 0.043 ms (196×)
    `nestedMatchExpr` 8.6 → 0.042 ms (205×)

---

## 2026-06-03 — DataTable Phase 3 (ColumnKind + RowPayload)

- **datatable-column-action-expressiveness** — Added `ColumnKind` sealed trait
  (Text/Date/Money/StatusBadge/Link) and `RowPayload` sealed trait
  (Field/WholeRow/Fields) replacing the old `bodyField: String` contract.
  Extended `FieldColumnDef` with `kind` + `width`. Added `View.FormattedField`
  for kind-aware cell rendering. All emitters (React/Vue/Solid/Custom/SwiftUI/
  Swing/JavaFx), `ModelPathValidator`, `ViewTraversal`, `FetchIntrinsics`, and
  `std/ui/primitives.ssc` / `data.ssc` updated. New intrinsics:
  `fieldPayload`, `wholeRowPayload`, `fieldsPayload`, `dateColumn`,
  `moneyColumn`, `statusColumn`, `linkColumn`. Shorthands: `dcol/mcol/scol/lcol`,
  `fieldBody/wholeRowBody/fieldsBody`. 58 tests green.

## 2026-06-03 — claim protocol hardening

- **coord-claim-protocol-hardening** — Tightened coordination docs around the
  canonical `.work/active/<slug>.claim` filename, documented repair steps for
  suffix-less active markers, clarified that read-only status audits should use
  remote `git show`/`git ls-tree` from the main checkout, and updated
  `scripts/coord-status` to report invalid markers explicitly instead of
  silently printing "active claims: none".

---

## 2026-06-03 — datatable-source-abstraction (Phase 2)

- **datatable-source-abstraction** — Introduced `TableDataSource` sealed trait
  (`Remote(FetchUrlSignal)`, `StaticRows(List[Map[String,Any]])`,
  `SignalRows(ReactiveSignal[?])`) in `frontendCore`. Changed
  `View.DataTable(signal)` to `View.DataTable(source)`. All 7 backends gate
  Remote-specific logic on the Remote variant; StaticRows/SignalRows emit a
  header-only stub. New `staticRowsSource`/`signalRowsSource` intrinsics in
  `FetchIntrinsics`; `staticDataTable`/`signalDataTable` helpers in
  `std/ui/data.ssc`. FetchIntrinsics legacy path preserves bare-signal callers.
  47+56+58+6 tests green across frontendCore/React/Vue/fetchPlugin.

---

## 2026-06-03 — dual-bank-lapply-r1-to-ref + A.2/A.3 JIT slices

- **dual-bank-lapply-r1-to-ref** — `ObjToObject` typed JIT interface;
  `walkRefArm` + `walkRefMatchBody` in `JavacJitBackend` compile
  ref-returning match bodies to Java switch (handles `Pat.Extract` and
  `Pat.Var` wildcard arms with duplicate-`default` guard); `doCompile`
  tries ObjToObject path first for 1-param ref-scrutinee matches;
  `LApplyR1ToRef(argR: LRefExpr, ObjToObject)` in `EvalRuntime`;
  `compileRefExpr` `Term.Apply` case wired in both while-loop entries.
  **Bench `refChainArg` (`leafVal(getLeft(tree))` × 1M): 191 → 9.9 ms (19×).**
  1230/1230 green.

- **phase-c-bytecode-if-in-while** (Direction A.2, commit `b4ae788c`) —
  `walkLocalSlotCtx` covers `Term.If` ternary and single-stat `Term.Block`.
  Loops with `x = if cond then a else b` now compile via while-JIT.

- **phase-c-bytecode-pure-fn-call** (Direction A.3, commit `4a4a1e09`) —
  `walkLong` `Term.Apply` emits static call to globals-bound `def`.
  **Bench `pureCallSum`/`pureCallSum2`: 13 → 0.28 ms (47×, JVM parity).**

- **asm-jit-lapplyobjref-parity** — fully complete: `5152e001` (AsmJit
  2-param ref-mixed interfaces) + `f7fc2b34` (LApplyR1 routes through
  `JitBackend.default.tryCompile`). All ref-arg bench gates locked.

---

## 2026-06-03 — dual-bank-lref-match + AsmJit 2-param ref-mixed parity

- **dual-bank-lref-match** — `LRefMatch(scrutR: LRefExpr, cm: CompiledMatch)`
  extends `LRefExpr`. When `cm.valueCapable`, eval calls `cm.runValue(scrutV,
  emptyEnv)` — no Computation allocation. `compileRefExpr` in both
  `tryLongWhileAssign` and `tryMixedLongWhile` gains a `Term.Match` case,
  enabling `f(e match { ... })` ref-arg patterns inside hot while loops.
  Test: LRefMatch with val-bound Shape in 100-iteration loop. 1229/1229 green.
  Commit `2305e321`.

- **asm-jit-lapplyobjref-parity** (partial) — `AsmJitBackend.determineInterface`
  now returns `LongObjToLong`, `ObjLongToLong`, `LongObjToDouble`,
  `ObjLongToDouble` for 2-param ref-mixed functions, matching
  `JavacJitBackend`. Commit `5152e001`.

---

## 2026-06-03 — JIT Direction A.5 — multi-stat block bodies

- **phase-c-bytecode-block-multistat** — `JavacJitBackend` gains
  `walkBlockStmts` / `blockStmtsCtx`: multi-stat `Term.Block` bodies are now
  JIT-compiled. Non-final `Defn.Val` bindings emit as Java `long`/`double`
  locals; the final `Term` compiles via the existing walkers (including
  block-ends-with-match). Expression-context multi-stat blocks use a
  `LongSupplier`/`DoubleSupplier` IIFE. `AsmJitBackend` gets the parallel
  `emitValBindings` helper (LSTORE/DSTORE bytecode + slot allocation via
  `ctx.allocSlot`). Both backends handle the block-ends-with-`Term.Match`
  path. Test: `sumSquares(5)` (two val-bindings, recursive call) = 55.
  1228/1228 tests green. Commit `6e11cc62`.

---

## 2026-06-03 — perf/interpreter-opt merge (63 micro-opts + 5 fixes)

- **interpreter-opt branch merged** — 63-commit null-optimisation branch
  (`perf/interpreter-opt`) landed into main via `--no-ff` merge commit
  `06aa0a5b`. Brings null-based dispatch tables, IMap direct constructors,
  CharV pool, FrameMap chain iteration, pure-path short-circuit, and a
  ReusableFrame1 for hot iteration callbacks. Five correctness bugs found and
  fixed during test runs: (1) `ImportResolver.discoverStdRoot` / `jarDir`
  removed from the branch — restored 6-candidate std-path chain; (2)
  `evalBlock` closure iteration overwrote FrameMap params when a child
  interpreter's builtins share the same name as a param — fixed with
  `!b.contains(k)` guard; (3) `infix2` `++` fallback skipped
  `extensionDispatch` so `Doc.++` produced wrong output — fixed; (4) `callValue1/2`
  fast paths bypassed TCO for named self-tail-recursive functions — added TCO
  guard; (5) effect test used one-shot resume on a `multi effect` — fixed test
  declaration. All 1227 tests green. Merge commit `06aa0a5b`.

---

## 2026-06-03 — JIT pattern guard if-chain + unary minus

- **jit-pattern-guard-conditional-arm** — `walkMatchBody` now detects when
  any arm has a guard (`c.cond.nonEmpty`) and emits an if-chain form instead
  of a Java switch. New `walkArmAsIfBranch` (~80 lines) emits
  `if (inst.typeTag() == N) { bindings; if (guard) { return body; } }`;
  guard conditions are compiled via the existing `walkBool` walker. Also adds
  `Term.ApplyUnary("-"/"+"...)` support to `walkLong` and `walkDouble`.
  Bench `patternGuard` (4 × 1M pre-built val calls): 13,570 → 11.7 ms/op
  (~1,160×). JitLint updated: Pat.Extract guarded match now `willJit = true`.
  1227/1227 tests green. Commit `8924f4e6`.

## 2026-06-03 — AsmJitBackend — direct AST→JVM bytecode JIT

- **asm-jit-backend** — Second implementation of the `JitBackend` SPI: emits
  JVM class files directly via ASM 9.7 instead of roundtripping through
  `javax.tools.JavaCompiler`. Full parity with `JavacJitBackend`: arithmetic,
  TCO while-loop, ADT pattern match, instance field access, Double functions,
  free globals, pure-function call inlining. Selected via
  `SSC_JIT_BACKEND=asm`; Javac remains the default. Build dep: `org.ow2.asm
  9.7` in `backendInterpreter`. Three correctness bugs found and fixed during
  parity testing: (1) `Lit.Double(v)` in scalameta 4.17 yields `String` at
  runtime — must call `Double.parseDouble(v.toString)` before `visitLdcInsn`;
  (2) bridge method `bSlot` increment for Object (ref) params was `+= 2`,
  must be `+= 1`; (3) `returnsThrows` guard missing from `doCompile` —
  throws-typed functions must fall back to tree-walk so the interpreter can
  auto-wrap the result in `Right`. 1218/1220 tests pass in ASM mode (same
  pre-existing flaky failures as default mode). Bench parity: `recursionFib`
  1.4 ms, `recursionFibD` 1.7 ms, `recursionTco` 36 µs, `arithLoop` 0.27 ms.

## 2026-06-03 — CI green audit (batch-1 + batch-2)

- **ci-green-audit** — Fixed all CLI test failures introduced by the
  May 2026 directory refactor (`cli/`→`tools/cli/`, `std/`→`runtime/std/`)
  and MessagePack binary artifact format migration. Three commits:
  batch-1 (a800cb69, 11 failures), SimpleYaml colon-in-value parse error
  (bb6d5fa0), batch-2 (b15dbffb, 9 failures). All `cli/` tests now pass
  or cancel gracefully when optional prerequisites are absent.

## 2026-06-03 — DataTable path validation

- **datatable-path-validation** — Extended `ModelPathValidator` to validate
  typed `View.DataTable` column/action field paths against the row model carried
  by `FetchJsonSignal` / `CodecHint.Json`, while keeping raw fetch-backed tables
  permissive. Added focused frontend-core coverage.

## 2026-06-03 — DataTable authoring surface cleanup

- **datatable-authoring-surface-cleanup** — Documented the post-`FetchTable`
  `DataTable` authoring contract, fixed std/ui `rowEdit` wiring, made the
  interpreter fetch plugin accept std/ui default `editable`/`emptyHeaders`
  shapes, migrated examples/docs to `fetchUrlSignal(...)` +
  `dataTable(signal, columns, actions)`, and added focused regression coverage.

## 2026-06-02 — JvmGen UI bridge split

- **jvmgen-ui-bridge-split** — Extracted the frontend
  `std.ui.primitives` generated-source block from `JvmGen.scala` into
  `JvmRuntimeUiPrimitives.source`. Generated Scala for the dashboard frontend
  example remains byte-identical after normalizing absolute jar directive paths.

## 2026-06-02 — CLI command result flow

- **cli-command-result-exitcode** — Added internal `ExitCode` /
  `CommandResult`, `CliCommand.runResult`, registry result dispatch, and
  top-level exit-code propagation. `LspCmd` is the first migrated command; the
  public command SPI remains `run(args): Unit` for compatibility.

## 2026-06-02 — frontend view traversal core

- **frontend-view-traversal-core** — Added `frontend/core` `ViewTraversal`
  (`children` + `foreachDepthFirst`) so backend collectors can share one
  exhaustive `View[?]` walk without sharing renderer logic. The first migrated
  collector is React's fetch-signal pass, which now finds typed JSON fetches
  inside semantic containers such as `Column`.

## 2026-06-01 — ui-fetch-auth v1 + v2

- **ui-fetch-auth-v1** — `fetchAction`/`fetchActionClear` gain optional
  `headers: Signal[String] = emptyHeaders` param; header value read at click time
  from `_sv[headersId]` → `JSON.parse` → passed to `fetch()`; `data-ssc-fetch-headers`
  attr in `renderBody`; all frontend emitters (React/Vue/Solid/Custom/Swing/JavaFX/Swift)
  updated; interpreter intrinsic handles 4-arg and 5-arg forms.
- **ui-fetch-auth-v2** — `fetchUrlSignal` performs a real HTTP GET on mount + on
  `refreshTick` increment (was a stub returning `Signal('')`); `_fetchGet` metadata
  on the Signal object drives `data-ssc-fetch-get-*` attrs on the text node span;
  `_ssc_ui_mount` now queries `[data-ssc-fetch-get-url]` and sets up fetch + tick
  subscription; `fetchTableView` similarly gains `headers`; new
  `FetchUrlSignal.headersId: Option[String]` field. Example: `examples/fetch-auth.ssc`.

## 2026-05-30 — lightweight perf regression guard added

- **perf-regression-guard** — Added a checked-in performance workflow manifest,
  benchmark README, ignored raw runtime/JMH outputs, `ssc bench --smoke` with
  optional `--target-ms/--require-target`, and `scripts/perf-smoke.sh --jmh` for
  an opt-in short JMH smoke. README, docs/performance, user guide, baseline
  policy, queue, and backlog now distinguish informational runs from explicit
  blocking gates.

## 2026-05-30 — typer real-type roadmap specified

- **typer-real-types-roadmap-spec** — Added
  [`specs/typer-real-types-roadmap.md`](specs/typer-real-types-roadmap.md),
  defining the planned type-evidence pipeline for reducing accidental `Any` in
  exported symbols, interfaces, routes/remotes, OpenAPI/GraphQL schemas, typed
  data codecs, Dataset/Spark mapping, and plugin metadata. README, docs index,
  architecture, typed data, route clients, OpenAPI, GraphQL, contract validation,
  queue, and backlog now link the planned work.

## 2026-05-30 — contract validation platform specified

- **contract-validation-spec** — Added
  [`specs/contract-validation.md`](specs/contract-validation.md), defining the
  planned shared OpenAPI/GraphQL validation model: route/resolver source checks,
  type-shape compatibility, diagnostics, profile leak checks, overlays/imports,
  CLI commands, compatibility diffs, baselines, contract tests, and rollout
  phases. README, docs index, OpenAPI/GraphQL specs, user guide, queue, and
  backlog now mark it as planned and not implemented yet.

## 2026-05-30 — quality roadmap queued and JMH output ignored

- **quality-roadmap-and-jmh-ignore** — Added a new Quality / Contracts / Type
  System queue/backlog section for contract validation, real-type propagation,
  performance regression guarding, and the next CLI helper split. Ignored JMH
  per-benchmark output directories so local benchmark runs do not leave shared
  `main` visibly dirty.

## 2026-05-30 — interpreter FrameMap and Option hot-path follow-up

- **perf/interpreter-framemap-option-hotpaths** — Ported two more safe slices
  from `perf/interpreter-opt`: direct `FrameMap.foreachEntry` iteration plus
  cheaper `FrameMapN.flat`, and direct-match `Option.map` / `Option.flatMap` /
  `Option.filter` / one-arg `fold` / `toRight` / `toLeft` paths adapted to
  the current null-sentinel `OptionV` representation. Also added the
  one-argument `Double.max` / `min` / `pow` / `atan2` dispatch fast path and
  mixed `Int`/`Double` comparison fast paths in `infix2`. List single-arg
  calls for `mkString`, `zip`, `takeRight`, `dropRight`, `splitAt`,
  `intersect`, `diff`, `count`, `collect`, and `span` now bypass the generic
  `arg :: Nil` fallback. `List.takeWhile`, `dropWhile`, and `sortWith` now
  have working one-argument interpreter dispatch with regression coverage.
  Compound assignment (`x += e` and siblings) now uses an all-pure path when
  the variable read, RHS, and infix operation complete synchronously. Known
  non-pure continuations in `if`, assignment, and list count now construct
  `FlatMap` directly instead of re-checking through `.flatMap`. Pattern-match
  cache lookup now happens before scrutinee evaluation, so pure scrutinees avoid
  an extra continuation. Plugin and stdlib helper lookups in `globalOrStub`,
  `Using.resource`, `McpSchema.derived`, derives fallback, and `Storage.get`
  now use null-sentinel map access instead of temporary `Option` wrappers.
  Actor system messages and interpreter HTTP response helpers now use direct
  small immutable map constructors for fixed-shape values. User interpolator
  and `summon` / `summonInline` lookups now use null-sentinel direct lookup on
  the common path. Ordinary user instance dispatch now skips plugin-bridge
  fallback checks, and single-argument extension dispatch uses the direct
  two-argument call helper. Type-method calls now avoid allocating a self-ref
  native function when the method body never references its own name. Lambda
  parameter names and type annotations are cached per AST parameter clause.
  More single-argument `String` operations now stay on the direct dispatch path.
  `Map` higher-order single-argument calls now do the same for `foldLeft`,
  `exists`, `forall`, `count`, and `find`. Callable instances, signal handles,
  and response header updates now avoid temporary `Option` wrappers in their
  hot lookups. Coroutine handles, typed HTTP handler requests, remote-handler
  request bodies, and PID serialization now use the same direct field lookup.
  Actor PID send, monitor/link, registry, scheduling, seed-resolution, and
  actor-group delivery paths now avoid the same temporary `Option` wrappers.
  Actor receive-loop dispatch now avoids boxing the matched computation before
  returning it to the scheduler.
  Named one- and two-argument function fast paths now reuse the interpreter's
  cached self-closure frame, and top-level/block statement execution no longer
  allocates a `zipWithIndex` tuple list just to find the last statement.
  Function and type-method vararg checks now avoid temporary `Option` wrappers
  from `List.lift` on every generic call.
  Two-field case-class pattern matching now skips field materialization for
  wildcard-only slots, reducing work in compiled match handlers.
  Tail-recursive trampolines now snapshot profiler state per current function
  and build stable self/mutual-call environments with `FrameMap` instead of
  `Map.updated ++`, reducing overhead in TCO-heavy benchmarks.
  Primitive infix expressions whose operands are direct names or literals now
  bypass subterm `Pure` wrapper creation and the generic infix dispatcher when
  debugger hooks are disabled, improving tight arithmetic/recursive benchmarks.
  Top-level `while` loops now reuse the interpreter globals view directly when
  there are no local shadow slots, avoiding a synthetic side frame and per-iteration
  refresh in tight assignment loops. `while` bodies made only of primitive direct
  assignments now take a guarded JVM-loop fast path after a dry-run confirms that
  no generic calls/extensions are involved; nested primitive infix expressions use
  the same recursive fast evaluator.
  Built-in HTML rendering and `attr := value` dispatch now use direct field
  lookups for `_Raw`, `Attr`, component `css`/`render`, and `AttrKey`.
  ActorGroup state operations, `Async.await`, and optic composition helpers now
  avoid short-lived `Option` chains in their hot field lookup paths.
  Path optic `getOption`, `set`, and traversal modify paths now carry absence
  with the interpreter's existing null sentinel instead of transient `Option`s.
  `Traversal.getAll` field and map-key steps now avoid `Option.map/getOrElse`
  dispatch chains while preserving absent-field behavior.
  Stream effect finalization now resolves `Source.from` / `Source.failed` with
  direct global lookups.
  Cluster seed resolution and Source / RemoteSource / ReactiveSignal bridge
  dispatch now use direct global or field lookup paths.
  Typed row projection now avoids temporary `Option`/sequence allocations while
  mapping SQL result maps into case-class-shaped values.
  Fixed-shape built-in `McpSchema`, HTML raw nodes, `Response`, `Pipeline`, and
  `KeyedStateSpec` values now use direct small immutable map constructors.
  Actor cluster events, local PIDs, and timeout receive `Some` wrappers now use
  the same direct small-map construction path.
  Future, Signal, serialized Pid, and typed-handler Either wrappers now avoid
  tuple/array allocation from the generic `Map(...)` factory.
  Parametric-given factory markers and synthetic opaque-type companions now use
  direct small-map constructors in statement execution.
  Validation/Either wrappers, user interpolator `StringContext`, Dataset empty
  errors, and restartable exception shims now use direct one-field map values.

## 2026-05-30 — WebSocket 10k load test made explicit

- **fix/ws-load10k-env-gate** — Removed the last ScalaTest `@Ignore` from
  `WsLoad10kTest`; the expensive 10k WebSocket load test is now visible but
  env-gated behind `SSC_WS_LOAD10K=1` for default test runs.

## 2026-05-30 — JS WebSocket runtime smoke restored

- **fix/jsgen-ws-ignored-test** — Re-enabled `JsGenWsTest` and restored the
  Node JS runtime's public `serve(port[, tls])` alias so `JsHttpIntrinsics`
  no longer emits calls to an undefined runtime symbol.

## 2026-05-30 — Swing JvmGen runtime smoke restored

- **fix/swing-runtime-ignored-test** — Re-enabled
  `JvmGenSwingRuntimeTest` and updated typed-client assertions for the current
  headers/cancel-token route-client signature and BackendRequest transport.

## 2026-05-30 — shard module smoke restored

- **fix/shard-module-ignored-test** — Re-enabled `ShardModuleTest`; the
  in-process `std/cluster/shard.ssc` solo-node owner/send smoke passes without
  additional runtime changes.

## 2026-05-30 — obsolete core UI ignored tests removed

- **fix/obsolete-core-ui-ignored-tests** — Removed stale ignored
  `ToolkitDemoValidateTest` and `StdUiSmokeTest` copies from
  `backendInterpreter`; their active plugin-dependent coverage lives in
  `backendInterpreterServer`.

## 2026-05-30 — SQL ignored tests restored

- **fix/remaining-sql-ignored-tests** — Re-enabled the JvmGen SQL runtime
  scala-cli smoke tests, moved SQL examples/conformance interpreter coverage
  into `backendInterpreterPluginTests` where the SQL plugin is available, and
  updated conformance capture paths to `tests/conformance/...`.

## 2026-05-30 — interpreter-server test overlap cleanup

- **fix/interpreter-server-test-overlap** — Removed the duplicate
  `HttpClientTest` copy from `backendInterpreterServer` now that HTTP plugin
  coverage lives in `backendInterpreterPluginTests`, and made the active
  toolkit-demo validation run in headless emit-only mode so it does not bind
  or hang on port 8080.

## 2026-05-30 — ignored interpreter tests restored

- **fix/ignored-interpreter-tests** — Re-enabled the coordinator conformance
  smoke by reading `tests/conformance/actors-cluster-coordinator.ssc`, moved
  HTTP client integration coverage to interpreter plugin tests with explicit
  `HttpInterpreterPlugin`, and made interpreter call-stack tracking
  thread-local so `runAsyncParallel` thunks cannot race on shared stack
  buffers.

## 2026-05-29 — bench{} language block + ssc bench + cross-backend JMH

- **feat/bench-tooling** — Three complementary benchmark tools:
  1. `bench("label") { expr }` / `bench("label", warmup, reps) { expr }` special form in the interpreter: re-evaluates the body AST term warmup+reps times, prints `[bench] label  p50=Xms  min=Yms  max=Zms  (N reps)` to stdout, returns the last result. Works inside any ScalaScript program.
  2. `ssc bench [--no-interp|--no-jvm|--no-js] [--warmup N] [--reps N] [--baseline] <file.ssc>` — runs a file through all three backends and prints a markdown comparison table. Interpreter runs in-process (no JVM startup overhead); JvmGen + JsGen run as subprocesses via the running ssc.jar. Auto-detects scala-cli / node availability.
  3. `CrossBackendBench.scala` JMH suite in `interpreterBench`: `jvmGen_*/jsGen_*` benchmarks measure codegen time (in-process, no subprocess), `interp_*` benchmarks measure execution. Run via `sbt "interpreterBench/Jmh/run .*CrossBackend.*"`. `build.sbt` adds `backendJvm + backendJs` deps to `interpreterBench`.
  Also committed: `scripts/runtime-bench.sh` shell harness for full multi-workload wall-clock comparison with pre-warm, median calculation, and optional `--baseline` write.

## 2026-05-29 — runtime test blocker fixes

- **fix/runtime-test-blockers** — Added generic `Foreign` method dispatch through
  `<Type>.<method>` plugin globals (restoring `ReactiveSignal.bind`), updated
  OAuth/OIDC installer tests to use the `HttpCap` adapter, finished the remaining
  `OptionV` null-sentinel plugin call sites, and fixed optimized `List.sorted` /
  `sortBy` loops that could hang and exhaust heap.

## 2026-05-29 — OptionV optimization follow-up

- **fix/interpreter-optionv-followups** — Completed the null-sentinel
  `OptionV` migration across DAP, interpreter-server, JSON/request/auth/OAuth/
  MCP/GraphQL/graph/sql/streams/remote plugins, and added a regression test for
  optional optics preserving `None` as absent.

## 2026-05-29 — interpreter unary pure fast path

- **perf/interpreter-unary-pure** — Ported the direct-match fast path for
  `Term.ApplyUnary` from `perf/interpreter-opt`. Pure unary operands now avoid
  allocating an extra `flatMap` continuation.

## 2026-05-29 — interpreter mapSequence three-element fast path

- **perf/interpreter-mapseq3** — Ported the three-element specialization for
  `Computation.mapSequence` from `perf/interpreter-opt`, avoiding
  `ArrayBuffer` allocation for common three-field/three-value sequencing.

## 2026-05-29 — interpreter Map.updated tuple-allocation cut

- **perf/interpreter-map-updated** — Ported the map update allocation cut from
  `perf/interpreter-opt`. Map `updated` and `+` paths now use
  `m.updated(k, v)` instead of allocating an intermediate `(k -> v)` tuple.

## 2026-05-29 — interpreter Map.get hit-path allocation cut

- **perf/interpreter-map-get-sentinel** — Ported the `Map.get` null-sentinel
  fast path from `perf/interpreter-opt`. Map lookup hits now avoid creating an
  intermediate Scala `Some` before returning `Value.OptionV`.

## 2026-05-29 — interpreter toList and zipWithIndex builders

- **perf/interpreter-zip-tolist** — Ported reverse-cons builders for
  `String.toList`, `String.zipWithIndex`, and `List.zipWithIndex` from
  `perf/interpreter-opt`, preserving element order while avoiding
  `ArrayBuffer` allocation in these common collection conversions.

## 2026-05-29 — interpreter split and indices list builders

- **perf/interpreter-split-indices** — Ported reverse-cons builders for
  `String.split`, `String.lines`, and `List.indices` from
  `perf/interpreter-opt`, preserving element order and trailing empty string
  parts without allocating an intermediate `ArrayBuffer`.

## 2026-05-29 — interpreter range construction fast path

- **perf/interpreter-ranges-map2** — Ported the range cons-from-end fast path
  and `AttrKey :=` `Map2` allocation cut from `perf/interpreter-opt`.
  Empty ranges keep their existing empty-list behavior while non-empty
  `to`/`until`/`List.range` calls avoid the intermediate `ArrayBuffer`.

## 2026-05-29 — interpreter runtime Map1 constructors

- **perf/interpreter-runtime-map1** — Extended the direct immutable `Map1`
  constructor optimization to direct blocks, throws auto-wrapping, coroutine
  state values, and core `Left`/`Right` intrinsics.

## 2026-05-29 — interpreter small InstanceV maps

- **perf/interpreter-small-instance-maps** — Ported the small `InstanceV`
  field-map allocation cuts from `perf/interpreter-opt`. Common one- and
  two-field runtime instances now use immutable `Map1`/`Map2` constructors
  directly instead of building maps through tuple-based syntax.

## 2026-05-29 — interpreter case constructor fast paths

- **perf/interpreter-case-ctor-fast** — Ported a safe version of the
  `perf/interpreter-opt` case-class/enum constructor fast path. Constructors
  without defaults now avoid default-application overhead for valid arities
  while preserving the existing missing-argument error behavior.

## 2026-05-29 — interpreter parameter array cache

- **perf/interpreter-param-array-cache** — Ported the `params.toArray` cache
  from `perf/interpreter-opt`. Calls to functions, methods, and TCO frames
  with three or more parameters now reuse the parameter-name array instead of
  allocating it on every invocation.

## 2026-05-29 — interpreter closure capture allocation cuts

- **perf/interpreter-closure-capture** — Ported the closure-capture allocation
  reduction from `perf/interpreter-opt`. Lambda/def/block capture now uses
  mutable builders and `foreachEntry` to avoid per-slot `Tuple2` allocation,
  and the direct-monad lift path uses the existing one-argument dispatch fast
  path.

## 2026-05-29 — interpreter for-comprehension pure fast paths

- **perf/interpreter-for-pure** — Ported the `for`/`yield` and `for`/`do`
  pure RHS/guard fast paths from `perf/interpreter-opt`. Pure generator,
  guard, and value enumerator evaluations now avoid unnecessary `FlatMap`
  nodes while preserving the trampoline fallback for suspended computations.

## 2026-05-29 — interpreter while pure fast path

- **perf/interpreter-while-pure** — Ported the all-pure `while` loop fast path
  from `perf/interpreter-opt` and snapshots `Profiler.enabled` once per call
  helper invocation. Pure tight loops now avoid allocating immediate
  condition/body `FlatMap` nodes on every iteration.

## 2026-05-29 — interpreter String single-arg fast paths

- **perf/interpreter-string1** — Ported `dispatchString1` higher-order string
  fast paths from `perf/interpreter-opt`. `String.map`, `filter`, `foreach`,
  `takeWhile`, `dropWhile`, plus one-arg `indexOf` / `codePointAt`, now avoid
  the generic one-argument dispatch list allocation.

## 2026-05-29 — interpreter CharV ASCII pool

- **perf/interpreter-char-pool** — Ported the ASCII `CharV` pool from
  `perf/interpreter-opt`. Character literals and string character-producing
  paths now use `Value.charV`, so common ASCII string iteration and indexing
  avoid fresh `CharV` allocation.

## 2026-05-29 — interpreter Int single-arg fast path

- **perf/interpreter-int1** — Ported the `dispatchInt1` fast path from
  `perf/interpreter-opt`. Common `Int.max`, `Int.min`, `Int.to`, and
  `Int.until` calls now avoid building the one-argument dispatch list.

## 2026-05-29 — interpreter computation sequence fast path

- **perf/interpreter-sequence** — Ported the `Computation.sequence` fast path
  from `perf/interpreter-opt`. Mixed pure/suspended computation lists now resume
  from the first non-pure element instead of rebuilding a `FlatMap` chain over
  already-collected leading pure values.

## 2026-05-29 — interpreter instance one-arg fast path

- **perf/interpreter-instance1** — Ported a safe `dispatchInstance1` /
  `callTypeMethod1` slice from `perf/interpreter-opt`. One-argument
  user-defined class methods, `Right.map` / `Right.flatMap`, `Left.getOrElse`,
  `Left.map` / `Left.flatMap`, and `Pid.tell` now avoid the generic
  `arg :: Nil` dispatch allocation while preserving the existing two-argument
  `Either.fold` behavior.

## 2026-05-29 — interpreter list aggregator fast paths

- **perf/interpreter-list1-aggs** — Ported high-impact list aggregation
  fast paths from `perf/interpreter-opt`. Curried calls such as
  `foldLeft(init)(f)`, `foldRight(init)(f)`, `scanLeft(init)(f)`,
  `reduceLeft(f)`, `partition(f)`, and `groupBy(f)` now stay in
  `dispatchList1` and avoid the extra one-argument dispatch list allocation.

## 2026-05-29 — interpreter dispatch2 built-in fast path

- **perf/interpreter-dispatch2** — Ported the two-argument built-in dispatch
  fast path from `perf/interpreter-opt`. Common two-argument methods such as
  `Map.getOrElse`, `Map.updated`, string `substring`/`replace`/`slice`, integer
  `clamp`, and list `slice`/`zip` now avoid constructing `arg1 :: arg2 :: Nil`
  in the interpreter dispatch path while preserving primitive extension-method
  dispatch.

## 2026-05-29 — interpreter two-argument apply fast path

- **perf/interpreter-apply2** — Ported the two-argument `Term.Apply` fast path
  from `perf/interpreter-opt`. Calls like `obj.method(a, b)` now bypass generic
  argument collection when the receiver and two arguments can be evaluated
  directly, reducing overhead in fold/reduce-style call sites.

## 2026-05-29 — interpreter dispatch1 fast path

- **perf/interpreter-dispatch1** — Ported the single-argument dispatch fast path
  from `perf/interpreter-opt`. The interpreter now routes many one-argument
  built-in method calls through `dispatch1`, avoiding `arg :: Nil` allocation
  in common `map`/`filter`/collection/string/option-style calls.

## 2026-05-29 — interpreter FrameMap small-field fast path

- **perf/interpreter-small-hotpath** — `FrameMap.fromMap` and
  `FrameMap.fromMapWithSelf` now use `FrameMap1`/`FrameMap2` for empty, one,
  and two-field overlays instead of allocating `FrameMapN` arrays. This targets
  case-class and instance method dispatch, where one- and two-field objects are
  common.

## 2026-05-29 — openapi-p5 CLI OpenAPI export

- **openapi-p5** — Added `ssc emit-openapi` for standalone OpenAPI 3.1 export without starting a server. The command runs an abort-at-first-serve interpreter dry-run, writes JSON to stdout by default, supports YAML via `--format yaml` or `-o *.yaml`, and accepts `--title`, `--version`, and repeatable `--server` overrides while preserving route metadata and security schemes.

## 2026-05-29 — openapi-p4 OpenAPI security schemes

- **openapi-p4** — Added `openApiSecurity(...)` declarations and `@openapi(security = List(...))` route requirements. Shared OpenAPI generation now emits `components.securitySchemes` plus per-operation `security` arrays for bearer/http and api-key schemes; interpreter and JVM generated server paths carry the metadata. Updated the OpenAPI example to include bearer security.

## 2026-05-29 — openapi-p3 per-route OpenAPI metadata

- **openapi-p3** — Added user-facing `@openapi(...)` route metadata for summary, description, tags, and deprecation. The parser rewrites the annotation into a marker call before `route(...)`; interpreter and JVM generated runtimes consume the marker on the next route registration; shared OpenAPI output emits the metadata. Added `std/openapi.ssc`, `examples/openapi-annotation.ssc`, and parser/generator/interpreter/plugin/JVM coverage.

## 2026-05-29 — openapi-p2b JVM OpenAPI response schemas

- **openapi-p2b** — JVM generated front-matter routes now propagate non-`Any` response type metadata from matching `apiClients:` endpoints into the generated OpenAPI response schema. Raw `route(...)` handlers continue to use the generic `200 OK` fallback. Added code-shape coverage and a scala-cli JVM e2e check for `/_openapi.json` schema output.

## 2026-05-29 — openapi-p2 JVM OpenAPI routes

- **openapi-p2** — OpenAPI generation now has a shared `OpenApiGenerator` model in backend SPI, and JVM-generated HTTP servers register `GET /_openapi.json` plus `GET /_swagger` from the generated `_routes` table when `serve()` / `serveAsync()` starts. The interpreter uses the shared generator while preserving typed handler query/body inference. Added SPI, interpreter, JvmGen code-shape, and scala-cli JVM e2e tests. Automatic response-type propagation is split to `openapi-p2b`.

## 2026-05-29 — arch-meta-v2-p4d richer quoted macro diagnostics

- **arch-meta-v2-p4d** — Restricted quoted macro unsupported-body diagnostics now classify common misses before the generic fallback. `Expr.asValue match` points at not-yet-implemented compile-time branching, `Expr(...)` points users back to direct quote syntax, and nested/non-top-level quotes or splices outside a direct quoted expression explain the current body-shape restriction. Added linker tests for each targeted diagnostic while preserving direct quoted-expression expansion.

## 2026-05-29 — arch-meta-v2-p4c quoted macro diagnostics

- **arch-meta-v2-p4c** — Restricted quoted macros now fail explicitly for unsupported forms. Parser preprocessing turns entrypoints without quoted args, such as `${ impl(x) }`, into a diagnostic helper requiring `${ impl('x) }`; interpreter `ssc run` reports `quoted macro error: ...`; linker normalization rejects non-quoted macro implementation bodies and explains that the restricted subset must return a direct quoted expression like `'{ $x + 1 }`. Added parser/linker/interpreter negative tests while preserving the p4/p4b happy path.

## 2026-05-29 — checkout `bin/ssc` staging fix

- **fix-ssc-installbin-classpath** — `bin/ssc` in a fresh worktree failed before staging because `bin/lib/` is intentionally generated, and after `sbt cli/installBin` it failed with `NoClassDefFoundError: scalascript/compiler/plugin/deploy/DeployError`. `cli / assembly` itself was healthy. `installBin` now includes `deployPlugin / packageBin` in `bin/lib/jars/` because the CLI deploy subcommand directly references deploy SPI/runtime classes at startup; other std plugins remain lazily loaded from `.sscpkg`. Added project `.jvmopts` (`-Xmx4G`, G1) so `cli / installBin` does not OOM while compiling/staging all std plugins. Verified `bin/ssc --help` and `bin/ssc examples/hello.ssc`.

## 2026-05-29 — arch-meta-v2-p4b quoted macro interpreter parity

- **arch-meta-v2-p4b** — Restricted quoted macros now have interpreter/run-path parity for the direct quoted-body subset. Parser helper lowering now carries both quoted parameter names and runtime values (`'x` → `__ssc_quote__("x", x)`, `$x` → `__ssc_splice__("x", x)`); linker/interface extraction remain backward-compatible with the old helper shape. The interpreter registers lightweight `Expr`, `QuotedContext`, `__ssc_macro__`, `__ssc_quote__`, `__ssc_quote_expr__`, and `__ssc_splice__` helpers, so direct quoted macro bodies work under `ssc run`. `Expr.asValue` returns the quoted value as `Option[A]`; `Expr.asTerm` returns an opaque `ScalaScriptTerm(name, value)`. Added `examples/quoted-macro-interpreter.ssc`, 3 interpreter tests, and updated macro/linker/parser tests.

## 2026-05-29 — arch-meta-v2-p5 runtime Mirror derives

- **arch-meta-v2-p5** — Runtime/interpreter slice for Mirror-based user typeclass derivation. The interpreter now registers summon-able `Mirror.Of[T]`, `Mirror.ProductOf[T]`, `Mirror.SumOf[T]`, and `deriving.Mirror.*` aliases when product/sum types are declared; `Mirror.of[T]` returns the same metadata. Mirror values now expose `label`, `fields`, `elemLabels`, `elemTypes`, `variants`, `isProduct`, `isSum`, `fromProduct`, and `ordinal`. Custom `derives` now works for user-defined typeclasses that provide `derived(m: Mirror)`; the existing `TC.derived` dispatch reuses the richer mirror. Added focused tests for `summon[Mirror.Of[Person]]` and `case class Person(...) derives Csv`. Source-level `inline match` over Mirror and broader generated-backend conformance remain planned follow-ups.

## 2026-05-29 — arch-meta-v2-p4 restricted quoted macro slice

- **arch-meta-v2-p4** — First restricted `QuotedMacro[A]` slice. Parser preprocessing now accepts `${ impl('x) }` macro entrypoints and `'{ $x + ... }` quoted bodies by lowering them to stable helper calls for Scalameta while preserving original code-block source. `.scim` interfaces carry `MacroImplRef` metadata on inline entrypoints plus `isMacroImpl` / `macroQuotedBodySource` metadata on direct implementation helpers. IR now has a `MacroImpl` node and `AstToIr` lowers the parser helper call into it. `Linker` builds a macro expansion table and expands direct quoted-expression macro bodies in `CodeBlock.source` before existing inline/FQN rewrites. 3 parser tests, 1 interface test, and 3 linker tests cover preprocessing, metadata, IR/link expansion, and cross-module expansion. `Expr[A].asValue`, `Expr[A].asTerm`, richer quoted terms, diagnostics for unsupported bodies, and interpreter/run-path parity remain planned follow-ups.

## 2026-05-29 — opt-const-fold constant folding in JsGen / JvmGen

- **opt-const-fold** — Compile-time constant folding in JS and JVM code generators (roadmap §4b). `JsGen`: new `foldConstant` helper evaluates binary infix expressions at codegen time when both operands are literals — covers `Int`/`Long`/`Double` arithmetic (+, -, *, /, %), comparison (< > <= >= == !=), `Boolean` logic (&& ||), and `String` + concatenation. `if(true/false)` with literal condition eliminates the dead branch. Unary `-` and `!` fold on literals. `JvmGen`: matching `foldConstantScala` helper wired into `emitExprDeep` (effectful path) and `emitExpr` (non-effectful infix check) with `Defn.Val`/`Defn.Var` always routed through `emitExpr`; same `if(true/false)` elimination in `emitExprDeep`. For JvmGen non-effectful vals, scalac performs its own constant folding at compile time so codegen-level folding is only critical in the effectful CPS path (where `_binOp` dispatch is avoided). 25 new `ConstFoldJsGenTest` tests + 11 new `ConstFoldJvmGenTest` tests; all 36 pass. No regressions (897/1060 pass vs 878/1060 baseline).

## 2026-05-29 — arch-meta-v2-p3 cross-module inline expansion

- **arch-meta-v2-p3** — Cross-module `inline` expansion for `ssc link`. Extended `ExportedSymbol` in `lang/ir/Ir.scala` with three new fields (all defaulted for backward compatibility): `isInline: Boolean`, `inlineParamNames: List[String]`, `inlineBodySource: Option[String]`. `InterfaceExtractor` now populates these via a new `extractInlineInfo(d: Defn.Def): Option[(List[String], String)]` helper that checks for `Mod.Inline`, filters out `using` clauses, and captures all regular param names plus the body's `.syntax`. Top-level `inline def`s are handled via a new `topLevelInlineInfo` map that feeds into the `rawExports` builder; nested `inline def`s inside `Defn.Object` are handled via the existing `buildNestedSymbol` path. Linker gains `buildInlineTable` (collects `isInline = true` exports from all modules into a `Map[String, (List[String], String)]`) and `expandInlineSource` (source-level expansion using a parenthesis-counting scanner with string-literal skipping and word-boundary checks). The expansion strategy is lambda-lifting: `f(arg)` → `((p) => body)(arg)`, which is hygienic (no capture, no alpha-renaming needed) and reduces via normal compilation. `rewriteSections` renamed to `expandAndRewriteSections`; the new path runs inline expansion on `CodeBlock.source` before the existing `IrExpr` VarRef rewriter. 5 new tests in `InterfaceExtractorTest` (isInline population for top-level, zero-arg, multi-param, nested) and 11 new tests in `LinkerRewriteTest` (`expandInlineSource` unit tests + end-to-end link test).

## 2026-05-29 — arch-stable-spi-p3 full plugin SPI migration + evalLegacy

- **arch-stable-spi-p3** — Phase 3 of the stable plugin SPI. Added `RemoteCap` capability trait (exposes `remoteHandlers` + `invokeRemoteHandler`) and `PluginNative.evalLegacy` migration helper to `PluginApi.scala`. Inlined `LegacyNativeContext` anonymous class into `PluginContext.fromNative`; removed it as a named type. Migrated all 16 `*Intrinsics.scala` in `runtime/std/` from `NativeImpl { (ctx, args) => }` to `PluginNative.evalLegacy { (ctx, args) => }` (mechanical substitution via script). Fixed oauth-plugin helper method signatures: `OAuthHttp.installRoutes`/`register` now take `ctx: HttpCap`; `OidcHttp.installRoutes`/`register` likewise; `OAuthIntrinsicHelpers.serveAuthServer` takes `ctx: HttpCap`, `makeGuardCurry` takes `ctx: MountCap`; `OidcIntrinsicHelpers.serveOidc` takes `ctx: HttpCap`. Updated private helper method signatures in `HttpIntrinsics`, `StreamsIntrinsics`, `DStreamsIntrinsics`, `McpIntrinsics`, `FrontendIntrinsics`, `PwaIntrinsics`, `RemoteIntrinsics`, `SqlIntrinsics` from `ctx: NativeContext` to `ctx: PluginContext`. Deleted `isStdPluginInterpreterTest` band-aid function from `build.sbt` and the corresponding `Test / unmanagedSources` filter in `backendInterpreter`. Moved 59 plugin-dependent test files (`Mcp*`, `OAuth*`, `Oidc*`, `GraphInterpreterIntrinsicTest`, `MountHandlerTest`, `PubSubTest`, `SqlBlockInterpreterTest`, `TypedHandlerTest`, `TypedRpcBinaryTest`) from `runtime/backend/interpreter/src/test/` to the new proper home `runtime/backend/interpreter-plugin-tests/src/test/scala/scalascript/`; `backendInterpreterPluginTests` now uses standard source layout. Added classpath boundary test to `PluginApiTest` verifying `scalascript.interpreter.Value` cannot be loaded from the plugin-api classpath.

## 2026-05-29 — arch-registry-p3 GitHub Pages registry site generator

- **arch-registry-p3** — Static site generation for the package registry. New `RegistrySiteGenerator` object in `lang/core/imports`: `generate(entries, outputDir)` writes `site/packages/{group}/{artifact}/index.json` (per-package machine-readable JSON with all fields + `install` field), `site/search-index.json` (lunr.js-compatible array of `{ref, name, version, description, body}` documents for client-side indexing), and `site/index.html` (self-contained searchable HTML page with `<table>`, client-side JS filter on name/description/keywords, deprecated row opacity). `packageJson` escapes JSON special chars (`\`, `"`, `\n`, `\r`); `indexHtml` escapes HTML special chars (`&`, `<`, `>`, `"`); deprecated entries get `style="opacity:0.5"` and a `[deprecated]` badge. New standalone `tools/registry-site/generate.sc` scala-cli script (self-contained, no sbt dep): reads `registry/packages.yaml`, generates `registry/site/` with the same 3 outputs. New `registry/site/CNAME` pointing to `registry.scalascript.io`. 16 tests covering all public methods, filesystem output, HTML escaping, JSON structure, empty-list edge cases.

## 2026-05-29 — arch-registry-p4 private registry support

- **arch-registry-p4** — Private registry support. `RegistryClient.effectiveUrl(registryArg)` resolves the URL with priority: CLI `--registry <url>` arg > `registry.url` in `~/.config/scalascript/config.yaml` > built-in default. `registryUrlFromConfig()` reads `registry.url` from `config.yaml` (SimpleYaml). `fetchYaml` now handles `file://` and `file:` URLs (reads directly from the filesystem without HTTP, useful for local mirrors and tests). All three registry commands (`ssc search`, `ssc add`, `ssc info <pkg>`) accept `--registry <url>` and pass it through to `RegistryClient.load`. 9 tests: file:// fetch, URL priority (CLI > config > default), config.yaml read/absent, search on locally-fetched entries.

## 2026-05-29 — arch-registry-p2 ssc search/info/add + RegistryClient

- **arch-registry-p2** — Package registry CLI commands and client. New `RegistryClient` in `lang/core/imports`: `load(url, refresh)` fetches `packages.yaml` from the registry URL (default `https://registry.scalascript.io/packages.yaml`) or returns from `~/.cache/scalascript/registry/packages.yaml` when fresh (1-hour TTL); `fetchAndCache` writes cache + timestamp; `search(query, entries)` returns scored matches (name prefix > name contains > desc/keyword); `formatRow` and `formatInfo` produce CLI output. New `ssc search [<query>] [--refresh]` command; `ssc add <name> [<version>]` appends dep entry to `ssclib-manifest.yaml` (creates it if absent); `ssc info <group>/<artifact>` now also dispatches to registry info when the argument is a package name (no file extension). 14 tests: fetch+cache, TTL, search scoring, format helpers.

## 2026-05-29 — arch-registry-p1 packages.yaml schema

- **arch-registry-p1** — Package registry schema foundation. New `RegistryEntry` case class in `lang/core/imports` models the `packages.yaml` entry format: `name` (required, `<group>/<artifact>`), `version` (required, semver), `description`, `keywords`, `backends`, `url` (allowed schemes: `github:`, `jitpack:`, `dep:`, `https://`), `license`, `author`, `homepage`, `changelog`, `scala-script-version`, `deprecated`. `RegistryEntry.parseAll` returns `Either[List[String], List[RegistryEntry]]`; `validate` checks name format, version semver shape, URL scheme whitelist, and HTTPS homepage requirement. `toYaml` serialises the list back to YAML. New `registry/packages.yaml` seeds 5 first-party packages (`io.scalascript/json`, `http`, `streams`, `actors`, `sql`). 15 schema validation tests including seed file round-trip.

## 2026-05-29 — arch-dsl-hooks-p4 InterpolatorCheckRegistry

- **arch-dsl-hooks-p4** — Compile-time interpolator validation is now extensible. New `InterpolatorCheck` SPI trait (`interpolatorName`, `check(parts: List[String]): List[Diagnostic]`) in `runtime/backend/spi`. New `InterpolatorCheckRegistry` in `lang/core/compiler/plugin`: TrieMap-backed `register`/`checksFor`/`checkAll`/`registerFrom`; pre-registers `XmlInterpolatorCheck` (xml placeholder validation via `PureMarkupCodec`). `MarkupInterpolatorCheck` now dispatches ALL `name"..."` interpolations through `InterpolatorCheckRegistry.checkAll` rather than hard-checking only `xml`. `Backend.interpolatorChecks: List[InterpolatorCheck]` field (default `Nil`); `BackendRegistry.registerDslHooks` registers checks on backend load. 12 tests: MarkupInterpolatorCheck XML regression (10) + registry discovery + custom check via traversal (2).

## 2026-05-29 — arch-ffi-p4 js/glue.js injection + META-INF/services

- **arch-ffi-p4** — JS glue preamble injection and `META-INF/services` Backend discovery from `.ssclib` glue archives. New `GlueJsPreambleRegistry` (TrieMap-backed, `addPreamble`/`contains`/`preambles`/`isEmpty`/`clear`). `ImportResolver.extractSsclib` now registers `js/glue.js` content in `GlueJsPreambleRegistry` when `manifest.glueJs` is declared; `JsGen.generateRuntime` prepends all registered glue preambles (behind a `// ── glue preambles ──` header) before the standard runtime parts, so library-shipped JS helpers are available to consumer code. `addGlueJarToClasspath` now also calls `BackendRegistry.addPluginJar(jarPath)` after wiring the URLClassLoader, so any `META-INF/services/scalascript.backend.spi.Backend` entries in the glue JAR are picked up by `ServiceLoader` on the next `BackendRegistry.inProcess` scan. 10 tests: `GlueJsPreambleRegistry` unit tests, `ImportResolver` integration (JS preamble populated, no-JS case stays empty, META-INF/services graceful handling), `JsGen.generateRuntime` injection tests (preamble appears before runtime helpers, newline termination, multiple preambles).

## 2026-05-29 — arch-ffi-p3 jvm/glue.jar in .ssclib

- **arch-ffi-p3** — JVM glue JAR support in `.ssclib` archives. `SsclibManifest` gains `glueJvm: Option[String]` and `glueJs: Option[String]`; `parseString` reads them from a `glue: { jvm: ..., js: ... }` YAML map; `toYaml` emits the `glue:` section when non-empty; backward-compatible (manifests without `glue:` parse cleanly). New `GlueClasspathRegistry` (TrieMap-backed): `addJar`/`contains`/`jars`/`clear`. `ImportResolver.extractSsclib` now calls `addGlueJarToClasspath(jarPath)` when `manifest.glueJvm` is defined and the file exists in the extracted archive — the jar is wired into the JVM thread-context `URLClassLoader` (JDK 11 path; degrades gracefully on module-path JDKs) and always tracked in `GlueClasspathRegistry`. `ssc package --lib` gains `--jvm-glue <jar>` and `--js-glue <js>` flags: the external file is packed into the archive at `jvm/glue.jar` / `js/glue.js` and the generated manifest records the path. 8 unit + integration tests.

## 2026-05-29 — arch-dsl-hooks-p3 built-in SourceLanguage plugins

- **arch-dsl-hooks-p3** — Built-in fenced languages now route through SourceLanguage SPI. Added bundled SourceLanguage implementations for `javascript`/`js`, `xml`, bind-aware `sql`, and bind-aware `transaction` alongside existing `scala`, `html`, and `css`; added a backward-compatible attrs-aware `compileBlock` overload for fence attrs such as `@db` and `@side`; Normalize consults `SourceLanguageRegistry` for built-ins and keeps core-only SQL/transaction fallbacks. CLI registry/dispatch tests cover all built-ins plus legacy SQL normalize/capability regressions.

## 2026-05-29 — arch-dsl-hooks-p2 PreprocessorRegistry

- **arch-dsl-hooks-p2** — Extensible preprocessor pipeline for ScalaScript → Scala source transformation. New `Preprocessor` SPI trait in `runtime/backend/spi`: `name`, `priority: Int = 100`, `apply(String): String`. New `PreprocessorRegistry` in `lang/core/parser`: `TrieMap`-backed `register`/`lookup`/`all`/`applyAll`/`registerFrom`; sorted by `(priority, name)` ascending. All 6 built-in preprocessors pre-registered at `PreprocessorRegistry` init time via `private[parser]` method references: `inline-imports` (10), `list-literals` (20), `slash-imports` (30), `remote-defs` (40), `effects` (50), `extern` (60). `Parser.preprocessForScala` replaced with `PreprocessorRegistry.applyAll(code)`. `Backend.preprocessors: List[Preprocessor]` field (default `Nil`) allows backends to contribute custom preprocessors; `BackendRegistry` calls `PreprocessorRegistry.registerFrom(backend)` on load. Plugin preprocessors registered at priority ≥100. 10 tests: built-in registrations, priority ordering, custom preprocessor applyAll, backend `registerFrom`, Parser integration.

## 2026-05-29 — arch-lib-p6 precompiled ssclib interfaces

- **arch-lib-p6** — `ssc package --lib --precompile` now writes `.scim` interface artifacts under `ir/` inside `.ssclib` archives. Added `ssc check-compat old.ssclib new.ssclib`, which compares public symbol shapes from packaged `.scim` interfaces and falls back to deriving interfaces from `src/*.ssc` when needed. `SsclibPackageCliTest` covers the precompiled archive layout and removed-symbol detection.

## 2026-05-29 — arch-dsl-hooks-p1 InterpolatorRegistry

- **arch-dsl-hooks-p1** — `InterpolatorRegistry` extension point for typed string interpolators. New `InterpolatorImpl` SPI trait in `runtime/backend/spi`: `name`, `returnTypeName`, `requiredFeatures`, `jvmEmit(parts, args)`, `jsEmit(parts, args)`. New `InterpolatorRegistry` in `lang/core/compiler/plugin`: `TrieMap`-backed `register`/`lookup`/`all`/`registerFrom`; built-in `HtmlInterpolator` and `CssInterpolator` pre-registered at init. `Backend.interpolators: List[InterpolatorImpl]` field (default `Nil`) allows backends to ship custom interpolators. Integration in all five sites: Typer now falls through to `InterpolatorRegistry.lookup(prefix).map(impl => SType.named0(impl.returnTypeName)).getOrElse(SType.String)`; JvmGen adds `blockContainsRegisteredInterpolator` + `termContainsRegisteredInterpolator` detectors and a `Term.Interpolate` guard-match that calls `impl.jvmEmit`; JsGen dispatches both direct and CPS `Term.Interpolate` arms through `impl.jsEmit`; CapabilityCheck scans source for registered interpolator prefixes and adds `impl.requiredFeatures` to detected capabilities. 18 tests: 11 in `InterpolatorRegistryTest` (registry, typer), 7 in `DslHooksCodegenTest` (JvmGen + JsGen dispatch, fallback).

## 2026-05-29 — arch-lib-p5 transitive deps + lockfile

- **arch-lib-p5** — Transitive dependency resolution and lock file for `.ssclib`-based dependencies. New `SemVer` object with numeric segment comparison (`compare`, `max`). New `SscLibLock` case class (`ssc-lock.yaml`): `locked: Map[org/name → version]`; `parseString`/`toYaml`/`read`/`write`/`withResolved` API; alphabetically-sorted YAML output. `ImportResolver.resolveAll(depUris, strictDeps)` performs BFS using internal `ResolutionState` (mutable `resolved` LinkedHashMap + `visiting` HashSet): cycle detection throws `"Dependency cycle detected"`, version conflicts are resolved by latest-wins (or hard-error under `--strict-deps`); `prefetchTransitiveDeps` reads `dependencies:` from each fetched manifest and recurses. New `ssc update` CLI command reads all `dep:` imports from source files, calls `resolveAll`, and writes `ssc-lock.yaml`. 19 unit + integration tests: 14 in `SscLibLockTest` (lock YAML, SemVer), 5 in `TransitiveResolutionTest` (mock HTTP server verifying single dep, transitive pull-in, latest-wins, strict-deps error, cycle error).

## 2026-05-29 — arch-stable-spi-p2 plugin capability bridge

- **arch-stable-spi-p2** — `scalascript-plugin-api` now exposes `HttpCap`, `WsCap`, `DbCap`, `StorageCap`, `ValidateCap`, `MountCap`, `PluginContext.fromNative`, `LegacyNativeContext`, and `PluginNative.eval` for typed native intrinsic implementations. Representative intrinsics in `json-plugin`, `http-plugin`, and `auth-plugin` now use the typed bridge, and `auth-plugin` `verifyPassword` now delegates to `Password.verify`.

## 2026-05-29 — arch-lib-p4 ssclib format + ssc package --lib

- **arch-lib-p4** — `.ssclib` ZIP archive format for ScalaScript libraries. New `SsclibManifest` case class in `lang/core/.../imports/` with `parseString(yaml)` / `toYaml(m)` methods; fields: `name`, `version`, `entry`, `scala-script-version`, `dependencies`, `description`, `author`. `ImportResolver.resolveDep` extended: checks new `~/.cache/scalascript/libs/<org>/<name>/<version>/` extracted-lib cache alongside the existing `.ssc` cache; when fetching from dep-sources tries `.ssclib` before `.ssc`; `extractSsclib` unpacks the ZIP and returns the manifest entry-point path. New `ssc package --lib` CLI command (in `packageLib`): reads or auto-generates `ssclib-manifest.yaml`, walks `src/`, packs manifest + sources into a `.ssclib` ZIP; flags: `--manifest`, `-o`/`--output`. `PluginSpec` moved from bare `build.sbt` to `project/PluginSpec.scala` so it is visible in all sbt build segments (worktree compilation fix); `backendInterpreterPluginTests.dependsOn` uses `ClasspathDependency(p.project, None)` for correct type. 11 manifest unit tests in `SsclibManifestTest`.

## 2026-05-29 — arch-build-registry-p2 runtime PluginRegistry facade

- **arch-build-registry-p2** — Added `PluginRegistry`, `PluginMeta`, and `PluginSource` to backend SPI; made `BackendRegistry` implement the facade while preserving existing APIs; added `RemotePluginInstaller` for path/URL/registry `.sscpkg` installs; routed CLI plugin install and `pkg:` auto-install through the shared installer; extended `BackendRegistryTest` for facade/classpath install coverage.

## 2026-05-29 — arch-ssc-new-p3 standalone install docs

- **arch-ssc-new-p3** — Added `docs/getting-started-standalone.md`, updated user-guide installation and community plugin docs, and changed root `install.sh` to require `--dev` for monorepo staging. Plain `./install.sh` now prints standalone install options (`cs`, Homebrew, curl) instead of starting a local sbt build.

## 2026-05-29 — arch-ssc-new-p2 extra templates and standalone install inputs

- **arch-ssc-new-p2** — Added bundled `dsl`, `web-app`, and `wasm-app` templates for `ssc new` (`plugin` was already present), a repo-local Homebrew formula source at `releases/homebrew/ssc.rb`, and a lightweight `releases/install.sh` curl/wget installer for GitHub Release `ssc.jar` downloads. Updated scaffolding docs and expanded `NewProjectTest` coverage for the new templates.

## 2026-05-29 — arch-build-registry-p1 PluginSpec registry in build.sbt

- **arch-build-registry-p1** — `case class PluginSpec(id, project, jarPrefix)` + `lazy val allPlugins: Seq[PluginSpec]` registry introduced in `build.sbt`. Three of the five scattered plugin lists are now derived from `allPlugins`: `pluginJarPrefixes` Set in `installBin`, `backendInterpreterPluginTests.dependsOn`, and the root aggregate (via a separate `.aggregate(allPlugins.map(_.project: ProjectReference): _*)` call). `pluginPkgs` inside `installBin` stays explicit (sbt task-macro constraint) with a comment. Also fixes missing `deployPlugin`, `paymentRequestPlugin`, and `paymentsPlugin` from `installBin` pluginPkgs. The registry has 19 entries covering all std plugins.

## 2026-05-29 — arch-ssc-new-p1 app/lib scaffolds and Coursier channel

- **arch-ssc-new-p1** — `ssc new` now defaults to the `app` template while preserving explicit `--template plugin`. Added bundled `app` and `lib` templates under CLI resources, `releases/coursier.json` as the repository-side Coursier channel descriptor, documentation for the existing `sbt cli/assembly` fat JAR path, and `NewProjectTest` coverage for app/lib/plugin scaffolds. Also fixed freshly landed `JsonCodec` and `PluginSpec` build compatibility blockers found while verifying the CLI module.

## 2026-05-29 — arch-stable-spi-p1 scalascript-plugin-api module

- **arch-stable-spi-p1** — New `runtime/scalascript-plugin-api/` sbt subproject (`scalascript-plugin-api`). Stable plugin surface: `PluginValue` (opaque `Any`), `PluginError` (opaque `Throwable`), `PluginComputation` (opaque `Any`), `JsonCodec` (wraps `ujson.Value`), and `type PluginContext = NativeContext`. All 18 std plugin projects (`jsonPlugin`, `frontendPlugin`, `swingPlugin`, `requestPlugin`, `authPlugin`, `oauthPlugin`, `fetchPlugin`, `graphPlugin`, `sqlPlugin`, `httpPlugin`, `wsPlugin`, `mcpPlugin`, `remotePlugin`, `pwaPlugin`, `streamsPlugin`, `dstreamsPlugin`, `deployPlugin`, `paymentRequestPlugin`, `paymentsPlugin`) and the root aggregate gain `pluginApi` as a dependency. 11 tests in `PluginApiTest`.

## 2026-05-29 — arch-sbt-plugin-p4 developer tools and BSP setup

- **arch-sbt-plugin-p4** — Added interactive `SscRunner` support plus `sscRepl`, `sscRun`, `sscWatch`, and `sscBspSetup` tasks to the sbt plugin. `BspIntegration` emits `.bsp/scalascript.json` pointing editors at `ssc lsp --project <project>`. Added scripted `dev-tools` coverage for REPL/run/watch command wiring and BSP file emission.

## 2026-05-29 — arch-ffi-p2: @interpreterUnsupported + cross-backend parity

- **arch-ffi-p2** — Added `@interpreterUnsupported` annotation support in `StatRuntime`: extern defs annotated with `@interpreterUnsupported` register a `NativeFnV` that throws an `InterpretError` with a descriptive message when called from the interpreter. Custom message: `@interpreterUnsupported("msg")`. Default message includes the def name. Error is raised at call site, not at definition. Combined `@jvm`+`@interpreterUnsupported` works: the annotation wins in interpreter mode. Cross-backend parity tests verify the same `.ssc` source with `@jvm`+`@js` produces correct output on both JVM and JS backends including `$N` argument substitution. 9 tests.

## 2026-05-29 — arch-sbt-plugin-p3 sbt test integration

- **arch-sbt-plugin-p3** — Added `sscTestResultsDir`, `Test / sscTest`, and `SscTestFramework` JUnit XML parsing to the sbt plugin. `sscTest` scans `src/test/scalascript`, runs `ssc test <dir> --backend <id> --output-format junit-xml --output <dir>`, maps failures/errors to sbt `TestResult`, and is wired into `Test / test`. Added scripted `test-integration` coverage for `sbt test`.

## 2026-05-29 — arch-sbt-plugin-p2 sscLink and packageBin

- **arch-sbt-plugin-p2** — Added `sscLinkedJar` and `Compile / sscLink` to the sbt plugin. `sscLink` depends on `sscCompile`, runs `ssc link --backend <id> --output <jar> <artifact-dir>` through `SscRunner`, skips cleanly when there are no ScalaScript artifacts, and is wired into `Compile / packageBin`. Added scripted `package-link` coverage where `sbt package` produces the configured linked JAR.

## 2026-05-29 — arch-lib-p2 @internal access control

- **arch-lib-p2** — `@internal` cross-package access control. `ExportedSymbol.isInternal: Boolean = false` field added (backward-compatible, derives ReadWriter). `InterfaceExtractor` detects `@internal` annotations via `Mod.Annot` on top-level `Defn.Def/Val/Var/Class/Object/Trait` and sets `isInternal = true` in the emitted interface. Typer builds `internalImportedNames: Set[String]` from all `importedInterfaces` entries where `isInternal = true`; at `Term.Name` call sites, if the name is in `internalImportedNames`, a hard `TypeError` is emitted with a message naming the `@internal` symbol. 8 tests in `TyperInternalAccessTest`.

## 2026-05-29 — arch-ffi-p1 @jvm / @js inline FFI annotations

- **arch-ffi-p1** — Tier-1 inline FFI annotations for `extern def`. `@jvm("expr")` on an extern def causes `JvmGen` to emit the expression as the method body (instead of skipping); `$0`/`$1`/… placeholders are substituted with the parameter names. `@js("expr")` causes `JsGen` to emit the expression as a JS function body. `@jvm`-only extern defs (no `@js`) get an error-throwing JS stub so the failure is explicit rather than silent. `Diagnostic.JvmOnlyExternDef` added to the `Diagnostic` enum; `CapabilityCheck.jvmOnlyExternDefs` detects `@jvm`-without-`@js` extern defs in modules compiled for the JS family and emits the diagnostic. 13 tests in `FfiAnnotationTest`.

## 2026-05-29 — arch-lib-p3 namespace collision detection

- **arch-lib-p3** — Import namespace collision detection. When two imported modules export the same top-level name the Typer emits a warning; `--strict-namespaces` flag on `ssc check` turns warnings into hard errors. `NamespaceCollision(name, aliasA, aliasB)` case class with a human-readable `.message` suggesting the qualified import form. `InterfaceScope.detectCollisions` accepts a `suppressed: Set[(String, String)]` to silence known collisions. Qualified import syntax `[Name from Module](path)` parses into `ImportBinding(fromModule = Some("Module"))` and suppresses the collision for that name. `Typer` gains `strictNamespaces` and `suppressedCollisions` parameters; `typeCheckWithCollisionWarnings` and `typeCheckStrictNamespaces` companion factories added. 12 tests in `NamespaceCollisionTest`.

## 2026-05-29 — arch-sbt-plugin-p1 source convention and sscCompile

- **arch-sbt-plugin-p1** — Extended the existing `ScalascriptInteropPlugin` with `SscRunner`, `sscSourceDirectories`, `sscBackend`, `sscExtraArgs`, config-scoped `sscArtifactDir`, `Compile / sscCompile`, and `Compile / compile` wiring. `sscCompile` discovers `.ssc` files under `src/main/scalascript`, runs `ssc build --incremental <src-dir> --artifact-dir <target>/ssc-artifacts --backend <id>`, and returns generated artifact files. Added scripted `compile-sources` coverage while preserving existing facade scripted tests.

## 2026-05-29 — arch-lib-p1 @deprecated / @experimental annotation warnings

- **arch-lib-p1** — Added `@deprecated` and `@experimental` call-site warnings to the Typer. `fatalWarnings: Boolean` parameter on `Typer`; `TypeError.isWarning` flag; `TypedModule.hasErrors` ignores warnings, `TypedModule.warnings` returns the warning-only subset. `Typer.typeCheckFatalWarnings` factory promotes all warnings to errors (`--fatal-warnings` semantics). Annotation extraction from `Mod.Annot` mods on `Defn.Def`: `@deprecated("msg", since = "v")` populates `deprecatedDefs`; `@experimental("notice")` populates `experimentalDefs`; both emit warnings at every `Term.Name` call site. 11 tests in `TyperAnnotationWarningsTest`.

## 2026-05-29 — arch-distribution-p4 community plugin starter template

- **arch-distribution-p4** — Added bundled `templates/plugin` resources, `NewProject` scaffolding, `ssc new <name> --template plugin`, a GitHub Actions release workflow template, and `specs/community-plugins.md`. The template emits Backend SPI skeleton code, ServiceLoader registration, `.sscpkg` manifest/source files, and release packaging steps.

## 2026-05-29 — arch-distribution-p2 Coursier and JitPack dependency resolver

- **arch-distribution-p2** — Added `MavenDepResolver` for Maven-shaped `dep:group:artifact:version` / `dep:group::artifact:version` imports via Coursier command wiring, preserved legacy `dep:org/name:version` dep-sources behavior, and added `JitpackResolver` as a thin Coursier repository wrapper. Added deterministic fake-Coursier tests over a local Maven-layout fixture.

## 2026-05-29 — arch-distribution-p1 GitHub release dependency resolver

- **arch-distribution-p1** — Added `DepResolver`/`DepSpec` SPI, content-addressed `DepCache`, built-in `GithubReleaseResolver` for `github:owner/repo@tag[#asset]`, and `ImportResolver` dispatch for `github:` imports with `sha256:` suffix pins. Added mock GitHub API coverage for release lookup, asset download, cache reuse, and pin verification.

## 2026-05-29 — v1.63.8 dynamic code ops hardening

- **v1.63.8-dynamic-code-ops-hardening** — `WorkerBundle` with `verify(zipBytes, hmacSecret, knownDeps)` — SHA-256 hash check + HMAC-SHA256 signature verification + dep set check — and `sign(zipBytes, hmacSecret, keyId)` that injects signature into `manifest.json`; `BundleManifest`/`BundleVerificationError`/`VerifiedBundle` types; `parseManifest` regex-based JSON extractor. `ArtifactCache` — content-addressed LRU store (ConcurrentHashMap + access-ordered LinkedHashMap with `removeEldestEntry` eviction from both maps); global singleton `ArtifactCache.global(128)`. `AuditLog` — concurrent ring-buffer (ConcurrentLinkedDeque, capacity-bounded) with `record`/`recent`/`toJson`; `AuditEntry` case class; `AuditEvents` string constants; global singleton `AuditLog.global(1000)`. `CircuitBreaker` — per-workerId state (failures, openedAt, open); opens at `threshold` consecutive failures; auto-resets after `resetAfterMs`; `allOpen`/`failureCount` query; global singleton. `ResourcePolicy` — `maxCpuMs`/`maxMemoryMb`/`maxThreads`/`maxQueueDepth` (0 = unlimited); `parse(Map[String,Any])`; `LoadTracker` — per-workerId `AtomicLong` counter with `acquire`/`release`/`activeCount` for load-shedding gates; global singletons. Interpreter integration: `shipWorker(workerId, zipBase64)`, `unloadWorker(workerId)`, `rollbackWorker(workerId)`, `workerStatus(workerId)`, `workerList()` actor globals in `ActorGlobals`/`ActorInterp`; wire messages `bundle_load`/`bundle_unload`/`bundle_rollback` recorded to per-node ring buffer; `GET /_ssc-cluster/audit` and `GET /_ssc-cluster/workers` routes registered on `startNode`. 24 new tests (148 total in `deployPlugin`).

## 2026-05-29 — v1.62.8 Wire binary compatibility and evolution

- **v1.62.8-wire-compatibility** — `WireSchemaId.hash` (SHA-256 truncated to 16 hex, `sha256:` prefix); `CompatibilityResult` enum (Identical/Compatible/Unknown/Incompatible); `WireSchemaRegistry` (directional `registerEvolution` + `check`); `WireCompatibilityGuard.check` (envelope schema-id guard with `requireSchemaId`/`allowUnknown` flags); `WireGoldenVectorRegistry` (Base64-stored cross-version decode test vectors + `byFormat`). Evolution policy: additive field additions are automatically forward-compatible because field-by-field decoders ignore unknown keys. 21 tests.

## 2026-05-29 — v1.62.7 Wire security and operations

- **v1.62.7-wire-security-ops** — Five modules in `backend/wire/.../security/`: `WireIntegrity` (HMAC-SHA256 sign/verify via `javax.crypto.Mac`, constant-time compare, `attachHmac`/`verifyEnvelope`); `WireCompression` (gzip compress/decompress via `java.util.zip.*`, ratio utility, stub for unsupported algorithms); `WireSession` (per-connection sequence counter + `stamp(env)`) + `WireReplayWindow` (sliding BitSet, configurable window size, `checkAndRecord`/`checkEnvelope`); `WireTlsConfig` (keystore/truststore/ciphers/protocols data type + `fromMap` front-matter parser); `WireMetrics` (`LongAdder` counters for frames/bytes/errors/hmac/replay/chunked/compressed + immutable snapshot + reset) + `WireDebug` (`summary()` one-liner, `dump()` multi-line pretty-print). 37 tests.

## 2026-05-29 — v1.62.4c Dataset binary actor frames

- **v1.62.4c-dataset-actor-binary-frames** — `runDistributedWire`, `runDistributedShuffleWire`, and `DistributedDataset.run/runShuffle` now accept non-JSON `wireFormat` as direct actor-frame selection: MsgPack/CBOR paths send `DatasetWire` envelope bytes for partition, shuffle-bucket, and key-result messages; JSON keeps the existing object-message fallback. Updated dataset wire examples to exercise CBOR.

## 2026-05-29 — v1.62.6 ObjectStore sync binary wire protocol

- **v1.62.6-object-sync-binary** — `ObjectSyncMsg` sealed trait (4 kinds: `PullRequest`, `PullResponse`, `PushRequest`, `PushResponse`) + value types (`SyncChange`, `SyncMutation`, `SyncResult`, `SyncConflict`) with full `WireCodec` instances and `ObjectSyncEnvelope` helpers. Mirrors the generated `/__ssc/sync/<store>/changes` GET and `/__ssc/sync/<store>/push` POST routes. `correlationId` passed through for request/response pairing. JSON/MsgPack/CBOR round-trips, 31 tests.

## 2026-05-29 — v1.62.5 DStream native wire protocol

- **v1.62.5-dstream-native-wire** — `DStreamMsg` sealed trait (7 kinds: `ElementBatch`, `Watermark`, `Trigger`, `SideInput`, `SideOutput`, `CheckpointMetadata`, `DStreamError`) with full `WireCodec[DStreamMsg]` instances and `DStreamEnvelope` helpers for building/decoding `WireEnvelope(protocol="dstream")`. `TriggerKind` enum (EventTime/ProcessingTime/CountBased/AfterWatermark) with `WireCodec[TriggerKind]`. All 7 message kinds round-trip through JSON, MsgPack, and CBOR (58 tests). External Spark/Kafka/Flink/Beam protocols untouched.

## 2026-05-29 — v1.62.4b Dataset runner wire-format boundary

- **v1.62.4b-dataset-runner-binary-wire** — `DistributedDataset.run` and `DistributedDataset.runShuffle` now accept `wireFormat`; non-JSON formats round-trip input and output `DatasetWirePartition` values through `DatasetWire` so runner-facing map/shuffle boundaries are checked under MsgPack/CBOR while preserving the current actor messages. Updated `examples/distributed-dataset-typed-helpers.ssc` to use `wireFormat = "cbor"`. Direct binary actor frames remain tracked in `v1.62.4c`.

## 2026-05-29 — v1.62.4 Dataset binary partition envelopes

- **v1.62.4-dataset-binary-partitions** — Added `DatasetWire`, a typed-data bridge that wraps `DatasetWirePartition` in shared `WireEnvelope(protocol = "dataset")` frames and encodes/decodes partitions as JSON, MsgPack, or CBOR. JSON numbers are preserved exactly via tagged string representation. Large partitions can now be chunked at element boundaries and reassembled using `chunk-id`, `chunk-index`, and `chunk-count` envelope headers. Added focused `DatasetCodecTest` coverage and updated wire/mapreduce/user docs. Runner transport selection remains tracked in `v1.62.4b`.

## 2026-05-29 — v1.63.7 cluster-aware deploy operations

- **v1.63.7-cluster-aware-deploy-ops** — `ClusterTarget` trait extending `DeployTarget` with `seedUrlsFor`, `injectAuthToken`, `emitWorkloadManifest`, `emitHeadlessService`, `emitAutoscaler`; `WorkloadMode` enum (Deployment/StatefulSet/DaemonSet) and `ScalePolicy`; `K8sTarget` now implements `ClusterTarget` — cluster mode emits StatefulSet + headless Service + token Secret bundle via new `K8sManifestGenerator` methods (`statefulSet`, `headlessService`, `tokenSecret`, `hpa`, `clusterBundle`); `HpaConfig`/`AutoscaleTarget.Cpu`/`AutoscaleTarget.Custom` types with YAML parser and HPA YAML emitter using `autoscaling/v2`; `ComposeTarget` generates docker-compose.yml with cluster mode token injection; `TargetFactory` wired for `compose`/`docker-compose` kinds; `RollingStrategy` enum (Rolling/BlueGreen/Canary); `Deploy.rollingCluster` orchestrates drain → deploy → health-check per node; `Deploy.multiRegion` runs regions sequentially checking quorum; `rotateClusterToken(newToken, overlapMs)` broadcasts `{"t":"token_rotate"}` to peers and schedules local commit after overlap window; incoming `token_rotate` wire message accepted and applied; `ClusterRoutesRuntime` auth check extended to accept pending new token during overlap; `clusterConfigSet/Get` persists to `.ssc-cluster-config-<nodeId>.json` JSON file (loaded on `startNode`); `DeployEnvironment` gains `autoscale: Option[AutoscalePolicy]` field parsed from manifest `autoscale:` block. 19 new tests (124 total in `deployPlugin`).

## 2026-05-29 — v1.63.6 stream/actor placement adapters

- **v1.63.6-stream-actor-placement-adapters** — `Source[A].remote(name, policy)` registers a named remote source and SSE route, returns a `RemoteSource[A]`; `remoteSourceLocal(rs, buffer)` retrieves in-process source; `RemoteSource.local(buffer)` / `RemoteSource.distributed` extension methods via `DispatchRuntime` bridges. `DStream[A].remote(name)` runs the dag, collects to local source, and registers SSE route. `ActorGroup.router/sharded/role`, `actorGroupAdd/Remove/Members/Tell`, `proxyActor` — actor groups use `nativeFeatureSet` for mutable member state so successive `actorGroupAdd` calls are cumulative; `proxyActor` implements drain-on-step semantics (proxyFlush) instead of a virtual thread, staying within the cooperative scheduler. `RemoteStreamPolicy`, `SseOverflowPolicy`, `RoutingPolicy` companion constants assembled in `BuiltinsRuntime`; actor group globals wired in `ActorGlobals`. 10 tests: 5 stream (RemoteSourceTest) + 5 actor (ActorGroupTest).

## 2026-05-29 — v1.63.5 cluster runner, worker bundles, handlers route

- **v1.63.5-cluster-runner-worker-bundles** — `ssc cluster run` delegates to `ssc run` with `SSC_CLUSTER_ROLE`/`SSC_NODE_ID`/`SSC_BIND`/`SSC_JOIN_SEEDS`/`SSC_CLUSTER_TOKEN` env vars; `ssc cluster package` creates a zip containing the source file plus `manifest.json` with SHA-256 code identity and registry metadata (remoteHandlers, exportedBehaviors, exportedSources); `ssc cluster handlers` GETs `/_ssc-cluster/handlers` and displays the operation list; `ssc cluster stop` POSTs to drain then step-down. `GET /_ssc-cluster/handlers` is registered automatically on `startNode` in both the interpreter and JVM codegen. Also fixes a pre-existing DAP exhaustivity warning (`Value.OptionV(None)` case).

## 2026-05-29 — v1.63.4f remoteStub API type syntax

- **v1.63.4f-remote-trait-stubs-wire** — `remoteStub[Api](baseUrl)` and `Remote.stub[Api](baseUrl)` now accept a forward-compatible API type argument while returning the path-based `RemoteStub` facade. This lets source code move toward the planned trait-shaped call site without requiring runtime type-argument reflection in the interpreter. Generated trait methods, async effect-row lowering, WebSocket/internal-wire, and binary `WireCodec[A]` remain tracked in `v1.63.4g`.

## 2026-05-29 — v1.63.4e RemoteStub HTTP facade

- **v1.63.4e-remote-trait-stubs-wire** — Added `Remote.stub(baseUrl)` / `RemoteStub` as a lightweight path-based HTTP JSON fallback facade with `function`, `call`, and `tryCall`, all reusing the existing `Remote.http` transport and typed `RemoteCallError` mapping. Added interpreter plugin coverage with an embedded JDK HTTP server and updated docs. Trait-shaped compile-time `remoteStub[Api]`, async effect-row lowering, WebSocket/internal-wire, and binary `WireCodec[A]` remain tracked in `v1.63.4f`.

## 2026-05-29 — v1.61.7 MessagePack binary artifact format

- **v1.61.7-memory** — Switch `.scim`, `.scir`, and `.scjvm`/`.scjvm-runtime` artifact files to MessagePack binary format (via `upickle.default.writeBinary`/`readBinary`), yielding 5–10× smaller on-disk artifacts and faster serialization. All `*File` write methods now write binary; all `*File` read methods auto-detect format (first byte `{` = legacy JSON, otherwise binary) for backward compatibility with existing artifacts. String-returning write methods (`writeInterface`, `writeJvm`, etc.) are unchanged — still emit pretty-printed JSON for terminal display and test string-manipulation. No behavior change; 247/248 tests pass (1 pre-existing `CrossPlatformSmokeTest` hash-normalization failure unrelated to this change).
  - **Bench delta (v1.61 artifact I/O)**: `.scim` file write/read: ~8× smaller binary vs pretty JSON; `.scjvm` with embedded Scala source: ~6× smaller. Round-trip parse time: ~3× faster for large artifacts (MessagePack vs JSON string parsing).

## 2026-05-29 — v1.61.6 JS preamble sub-capabilities

- **v1.61.6-preamble-split** — Split the ~185 KB monolithic `JsRuntime` preamble into conditional sub-capability blocks. `JsRuntimePart2` refactored into `JsRuntimePart2a` (_show/List/Map/_copy), `JsRuntimeOptics` (Lens/Optional/Traversal/Prism), `JsRuntimePart2b` (_tupleConcat/_dispatch/JSON/Free Monad/fs), and `JsRuntimeSignals` (reactive signals). Six new `Capability` cases added: `HtmlDsl` (Part1b — serve/route/sessions/metrics/TOTP/password), `Jwt` (Part1c — JWT/OAuth2/CSRF), `WsServer` (Part1d — WebSocket/SSE/CORS), `Optics`, `Signals`, `IndexedDb`. `generateRuntime` now assembles from parts based on detected capabilities; `detectCapabilities` extended with text-scan rules for each new capability. `JsRuntime` val retained as full preamble for backward compat (WebServer, existing tests). Pre-existing `JsRuntimeBrowserPatch` test corrected (`mergedInit` vs stale `init`). Hello World bundle: ~50 KB Core-only vs ~185 KB full; HTTP-serve apps get ~127 KB (Core+HtmlDsl+Jwt); optics/signals programs only pay for those sections when used.

## 2026-05-29 — v1.61.5 JS codegen inlining

- **v1.61.5-js-inlining** — Three targeted JS codegen quality improvements: (1) Tuple literals now emit `Object.assign([...], {_isTuple: true})` instead of a three-step IIFE — saves ~20 chars per tuple, one fewer closure allocation per creation (4 emission sites); (2) `Term.While` in statement context emits a direct `while (cond) { body; }` statement without IIFE wrapper — saves ~28 chars per while loop in statement position; (3) Integer `*` skips the `typeof === 'string'` guard when both operands are known integers via `isIntExpr` — saves ~52 chars and one typeof check per int multiply. `PatternRuntime.scala`: remove stale `import Computation.Pure`. No behavior change; 183/184 tests pass (1 pre-existing Choose failure).

## 2026-05-28 — v1.63.4d RemoteRpc typed client bridge

- **v1.63.4d-remote-stubs-async-wire** — Added `RemoteClientDeriver`, which derives generated `RemoteRpc` typed HTTP client metadata from `remoteHandlers:` entries that declare `path:`. This reuses the existing JS/JVM typed-route client codegen for the first remote-stub bridge while preserving explicit `apiClients:`. Updated distributed runtime docs, user guide, README, example notes, and parser coverage. Trait-shaped `remoteStub[Api]`, async effect-row lowering, WebSocket/internal-wire, and binary `WireCodec[A]` remain tracked in `v1.63.4e`.

## 2026-05-28 — v1.63.4c Remote HTTP JSON client

- **v1.63.4c-remote-stubs-async-wire** — Added explicit `Remote.http[A, B](url)` / `remoteHttpFunction` client calls for remote handler POST HTTP JSON fallback routes. The client posts ScalaScript value JSON, decodes the response, and maps non-2xx/network/decode failures into typed `RemoteCallError` values through `tryCall`. Added embedded JDK HTTP server coverage in `RemotePluginInterpreterTest`; typed `remoteStub[Api]`, async effect-row lowering, WebSocket/internal-wire, and binary `WireCodec[A]` are tracked in `v1.63.4d`.

## 2026-05-28 — v1.63.4b Remote source sugar

- **v1.63.4b-remote-sugar-stubs-wire** — Parser now lowers source `@remote(name = ..., path = ...) def` and simple `remote def echo(...)` declarations into `remoteHandlers:` metadata, so annotated/sugared handlers reuse the same interpreter `RemoteHandlerRegistry`, validation, and HTTP JSON fallback from v1.63.4. Updated `examples/remote-registry-rpc.ssc`, docs, and parser coverage. Remaining RPC pieces are tracked as `v1.63.4c-remote-stubs-async-wire`.

## 2026-05-28 — v1.63.4 Remote registries and async RPC base

- **v1.63.4-remote-registries-async-rpc** — Added backend SPI `RemoteHandlerRegistry`, `RemoteHandlerInfo`, and `RemoteCallError`; interpreter now lowers manifest `remoteHandlers:` entries into a local registry and exposes POST HTTP JSON fallback routes for handlers with `path:`. Added `runtime/std/remote.ssc` plus `remote-plugin` intrinsics for `Remote.function`, `remoteCall`, `remoteTryCall`, and `remoteHandlers()`, with typed `Left(RemoteCallError)` results for unavailable handlers. Added `examples/remote-registry-rpc.ssc` and targeted interpreter-plugin tests. Remaining planned pieces are `@remote` / `remote def`, `remoteStub[Api]`, async effect-row lowering, WebSocket/internal-wire transport, and binary `WireCodec[A]` negotiation.

## 2026-05-28 — v1.63.3 Cluster capability base

- **v1.63.3-cluster-capability-seed-code-identity** — Added backend SPI `Cluster`, `SeedResolver`, and `CodeIdentity`; exposed ScalaScript `ClusterCapability`, `SeedResolver.staticList`, `clusterOf`, `resolveSeeds`, `codeIdentity`, and `assertCodeIdentity`; interpreter now returns cluster snapshots, resolves static/DNS/K8s seed descriptors, computes deterministic SHA-256 code identity, and reports explicit diagnostics for the still-planned Consul resolver. Added typed `cluster:` / `remoteHandlers:` / `remoteSources:` / `remoteBehaviors:` front-matter metadata in AST/IR/`.sscc`, parser validation for missing registry target definitions and missing registry types, and source-level `cluster Demo:` lowering.

## 2026-05-28 — v1.63.2 Typed actors and remote spawn

- **v1.63.2-typed-actors-remote-spawn** — Added typed ScalaScript `ActorRef[M]` / `LocalActorRef[M]` aliases over `Pid`, `ref.tell`, `ref.address`, `ref.isLocal`, `ref.tryLocal`, `ref.publishAs`, `registerBehavior`, and `spawnRemote`. Interpreter actor runtime now handles named behavior spawn via JSON `cluster_spawn` / `cluster_spawn_ack`; JVM lowering now supports bare `setClusterAuthToken(...)`; JVM codegen always emits the effect runtime needed by the inlined Logger facade. Added interpreter coverage, jar-gated two-node CLI remote-spawn smoke, docs, and `examples/actors-typed-remote-spawn.ssc`.

## 2026-05-28 — v1.63.1 Source↔DStream stream bridge

- **v1.63.1-stream-bridge-basic-ops** — Bidirectional bridge between local `Source[A]` and distributed `DStream[A]`: `Source[A].distributed` wraps a local source into a `_dag_source_local` DAG node (dispatched via `DispatchRuntime.dispatchInstanceFallback` → `interp.globals("Source.distributed")` to avoid circular plugin dependency); `DStream[A].local()` materialises the full DAG through DirectRunner and returns a `Source` InstanceV with all BasicStreamOps (map/filter/merge/runForeach/runFold/runToList) wired as NativeFnV fields; `DStream[A].localBounded(maxBytes)` raises `InterpretError` when the approximate byte count exceeds the limit. `runtime/std/streams-bridge.ssc` declares the bridge API and `BasicStreamOps[F[_]]` shared trait. 9 new tests; round-trip `Source(1,2,3).distributed.map(_*2).local.runToList == List(2,4,6)` verified. Also fixes `ActorGlobals` pattern-extractor bug from upstream `b806ef2a` commit (smart constructors `Value.intV`/`Value.doubleV` used in `case` patterns replaced with case-class extractors `Value.IntV`/`Value.DoubleV`).

## 2026-05-28 — Architecture roadmap queue

- **queue-architecture-themes** — Added Architecture & Extensibility roadmap items to `WORK_QUEUE.md` so agents can claim them directly. Official centralized publishing to Maven Central / sbt Plugin Portal remains deferred in `BACKLOG.md`; ScalaScript's own package registry tasks are queued.

## 2026-05-28 — v1.61.4 Pattern-match compilation

- **v1.61.4-pattern-compile** — Compile each `Term.Match` into a `CompiledMatch` handler array cached by AST identity (`IdentityHashMap`). Each handler is `(Value, Env) => Computation | Null`, avoiding `Option` allocation in the hot dispatch path. Fast-path cases: `Pat.Wildcard`, `Pat.Var`, `Lit`, `Pat.Extract` with simple `Var`/`Wildcard` subpatterns (field order lazily cached per type on first match; `FrameMap.one/two/of` for 0–N bindings), `Pat.Alternative`. Complex patterns fall back to `matchPat`. Guard evaluation extracted to `evalGuard` helper. **Benchmarks (median 3 runs):** pattern-match-heavy 6069ms (baseline) → 3960ms (**1.53× vs baseline**, 8% over v1.61.3); arith-loop unchanged at ~4500ms. No behavior change; 115/116 tests pass (pre-existing Choose multi-shot failure).

## 2026-05-28 — v1.61.3 Env overhaul

- **v1.61.3-env-overhaul** — Two targeted hot-path fixes eliminating O(N_globals) overhead per while-loop iteration: (1) While-loop frame now only copies env entries that differ from `interp.globals` (locally-declared vars), shrinking from O(N_globals) to O(N_local_vars) — 2-5 entries instead of 300+; (2) `evalBlock` intercepts ALL `Term.Assign(Name)` to write both `local` and `interp.globals` simultaneously, making the per-statement global refresh a cheap no-op for direct-assignment blocks. **Benchmarks (median 3 runs):** arith-loop 15600ms → 4480ms (**3.5×**); pattern-match-heavy 6070ms → 4300ms (**1.4×**); recursion-tco/fib/tuple-monoid unchanged. No behavior change; 115/116 tests pass (pre-existing Choose multi-shot failure).

## 2026-05-28 — v1.61.2 Computation pure-path elimination

- **v1.61.2-pure-path** — Smart `Computation.map` constructor (skips FlatMap allocation when sub is Pure); all-Pure fast path in `Computation.sequence` (skips N-deep FlatMap chain for pure list operations); `Term.Select` pure-path in `EvalRuntime` (skips FlatMap for field access when receiver is Pure); `Term.Assign` pure-path (skips FlatMap for global-var assignment with pure RHS); `BlockRuntime.evalBlock` pure-paths for local-var assignment and compound assignment. Reduces FlatMap allocations on hot interpreter paths. No behavior change.

## 2026-05-28 — v1.61.1 Interpreter dispatch table

- **v1.61.1-dispatch-table** — Replace flat 300-case `(recv, name, args)` triple-match in `DispatchRuntime` with two-level dispatch: `recv match` selects the per-type handler (one `instanceof` check, O(1)); each handler uses `name match` which Scala 3 compiles as a hashCode-based switch (O(1) average). Extensions early-exit: when `interp.extensions` is empty (the common case), the 7-way `HashMap` probe is skipped on every `dispatch` call. Also fixes `dispatchInstanceFallback` field-access ordering: no-arg field access checked before enum-companion call. No behavior change.

## 2026-05-28 — v1.61.0 Benchmark infrastructure

- **v1.61.0-bench** — Performance measurement framework for v1.61 optimization pass. 8-workload corpus in `bench/corpus/` covering interpreter hot paths (arith-loop, recursion-fib, recursion-tco, pattern-match-heavy, effect-pure, effect-stream, tuple-monoid, hello-world). `bench/run.sc` scala-cli timing harness (median of 7 runs, 2 warmup; invokes `ssc` CLI; `--baseline` flag writes `bench/BASELINE.md`). `runtime/backend/interpreter-bench` sbt submodule with `sbt-jmh` for microbenchmarks (`InterpreterBench.scala`: 6 JMH benchmarks covering all hot-path workloads). `scripts/bundle-size.sh` for tracking JS+JVM generated bundle sizes (gzip-aware; appends date-stamped rows to `bench/BUNDLE_SIZES.md`). `bench/BASELINE.md` placeholder with capture instructions. `bench/BUNDLE_SIZES.md` log. `WORK_QUEUE.md` / `BACKLOG.md` updated with v1.61.0–7 roadmap.

## 2026-05-28 — Distributed runtime spec

- **v1.63.0-distributed-runtime-spec** — New canonical `specs/distributed-runtime.md` merges the placement/remoting plan with the local/distributed cluster lifecycle architecture. Keeps operation names such as `users.get`, code identity, handler/source/behavior registries, worker bundles, `ssc cluster` UX, remote streams, actor remote spawn/proxies/groups, dynamic-code-shipping roadmap, and cluster operations (token rotation, persistent state, rolling upgrades, multi-region, autoscaling), while adopting `! Async`, `BasicStreamOps`, typed `ActorRef[M]`, `Cluster`, `SeedResolver`, cluster-aware deployment, and backlog phases v1.63.1-v1.63.8. Follow-up sync incorporated `docs/cluster-operations.md` details directly into the canonical spec: `rotateClusterToken`, `token_rotate` / `token_rotate_ack`, `clusterConfigSet/Get`, `Deploy.rollingCluster`, `FaultToleranceConfig` lowering, and HPA `HpaConfig`. The older specs now redirect to the canonical document.

## 2026-05-28 — Distributed wire protocol spec

- **v1.62.0-distributed-wire-spec** — New `specs/distributed-wire-protocol.md` planning an opt-in internal wire layer for distributed actors, cluster control, Dataset/MapReduce, native DStream, typed route clients/RPC, WebSocket subscriptions, and object sync. The spec includes JSON fallback, MsgPack and CBOR binary profiles, JS/browser support, same-version-only initial compatibility, negotiation, security, compression, limits, observability, and backlog phases v1.62.1-v1.62.8.

## 2026-05-28 — Coinbase Prime MPC adapter

- **wallet-vault-mpc-coinbase** — `CoinbaseRemoteSigningClient` extending `HttpRemoteSigningClient`; EC P-256 ECDSA request signing (`X-CB-ACCESS-KEY` / `X-CB-ACCESS-TIMESTAMP` / `X-CB-ACCESS-SIGNATURE`); `CoinbaseAuth` (SHA256withECDSA over `timestamp+method+path+body`); `CoinbaseWire` (signing request JSON, hex payload, SECP256K1/ED25519/P256 algorithm names, poll status decoding); `CoinbaseVault` named constructor + `CoinbasePlugin` ServiceLoader; `specs/wallet-vault-mpc.md §Coinbase`; 17 tests including ECDSA signature verification. sbt: `walletVaultMpcCoinbase`.

## 2026-05-28 — Fireblocks MPC wallet vault

- **wallet-vault-mpc-fireblocks** — Fireblocks provider adapter for the shared MPC vault SPI: dedicated sbt subproject, `FireblocksRemoteSigningClient` with RS256 JWT auth + `X-API-Key`, RAW transaction signing request generation, `/v1/transactions/{id}` polling, `FireblocksVault`, `FireblocksPlugin` ServiceLoader entry, `specs/wallet-vault-mpc.md`, `examples/wallet-mpc-fireblocks.ssc`, and 16 mock-HTTP/JWT/wire tests.

## 2026-05-28 — v1.60 Tuple Monoid

- **v1.60.1-tuple-monoid-types** — Type system: `SType.Unit = Tuple(Nil)` (0-tuple as canonical unit); `SType.tupleConcat(t1, t2)` smart constructor (eager flattening, 1-element collapse); `++` infix type operator in `InterfaceScope` parser + `Typer.typeAnnotToSType`; `(A,)` trailing-comma syntax for 1-element tuples; unifier handles 0-tuple identity and 1-tuple transparency. 49 tests in `ParseSTypeTest` (6 new `++` tests, 1-tuple test).

- **v1.60.2-tuple-monoid-values** — Value level + backends: `TupleV ++ TupleV` in `DispatchRuntime` (concat `as ++ bs`, `UnitV` as identity on both sides); JS: `_tupleConcat(a, b)` runtime helper in Core (spreads arrays, sets `_isTuple = true` when both operands are tuples — preserves list semantics for non-tuple arrays); JVM: `_tupleConcat` with `scala.Tuple.fromArray` for tuple operands, `List ++ List` fallback; both `++` codegen paths now route through `_tupleConcat`. 4 interpreter tests + 3 JsGen codegen tests.

- **v1.60.3-tuple-monoid-docs** — Docs: `algebraic-effects.md` §8.3 "Unified runner signature" with `Out(E) ++ (R,)` table covering all 8 built-in effects + the `Out(E) ++ (R,)` derivation formula; `streams.ssc` "Tuple monoid" section explaining `runStream`'s `(Source[A], R)` return as `Out(Stream[A]) ++ (R,)`; `BACKLOG.md` v1.60 section marked complete.

- **v1.60.4-tuple-bareconcat** — 1-tuple ≅ element equivalence at value level: bare (non-tuple) operands treated as 1-tuples in `++`. New dispatch cases in `DispatchRuntime`: `TupleV(as) ++ v = TupleV(as :+ v)`, `v ++ TupleV(bs) = TupleV(v :: bs)`, `bare ++ bare = TupleV(List(v, w))`, and identity `() ++ v = v` / `v ++ () = v` for bare values. JS `_tupleConcat` updated to use `Array.isArray` guard (non-array = bare scalar, wrapped to `[x]` before spread). JVM `_tupleConcat` extended with `Tuple ++ bare`, `bare ++ Tuple`, `bare ++ bare`, and bare-identity cases. 5 new `InterpreterTest` cases + 2 new `JsGenStreamsTest` cases. Docs: `tuple-monoid.md` §2 (1-tuple equivalence subsection), `user-guide.md` Tuples section, `algebraic-effects.md` §8.3, `streams.ssc` tuple monoid section, `streams.md` unified runner subsection.

## 2026-05-28 — Wallet Trezor vault adapter

- **wallet-vault-trezor** — `payments/wallet/vault-trezor/` sbt subproject: `TrezorEthVault` (implements `Vault` SPI; `unlock/lock/getSigner`; `ButtonRequest` auto-ack loop up to 10 retries); `TrezorBridge` trait + `HttpTrezorBridge` (java.net.http, `Origin: https://bridge.trezor.io`); `TrezorSession` (acquire/release with guaranteed release via `transformWith`); `TrezorMessages` (`TrezorDeviceInfo`, `TrezorResponse`, `Bip32.parse`, `TrezorMessageType` constants, `TrezorDeviceFailure`); `MockTrezorBridge` (per-messageType response queues, recorded calls); `enqueueFeatures/PublicKey/EthSignature/Failure` helpers. 29 tests (TrezorBridgeTest 11, TrezorSessionTest 4, TrezorEthVaultTest 14).

## 2026-05-28 — Ledger WebBLE transport (Scala.js)

- **wallet-vault-ledger-bluetooth-js** — `WebBleTransport` implementing `LedgerTransport` for Ledger Nano X / Stax via Web Bluetooth GATT; `BleFraming` with configurable MTU (default 23 bytes); `BrowserBluetoothDevice` live impl; `MockBluetoothDevice` for tests; 12 tests; `specs/wallet-vault-ledger.md §bluetooth-transport` created.

## 2026-05-28 — x402 Cardano Scalus thin-glue wiring

- **x402-cardano-scalus-wire** — `CardanoScalusFacilitator.preprod/mainnet` factory in `x402-facilitator-cardano-scalus` wires `ScalusSettler.asConfigHook` into `CardanoFacilitatorConfig.scalusSettle`; removes the "not yet implemented" stub; 8 new tests total (5 in `CardanoScalusFacilitatorTest` + 3 in `CardanoFacilitatorTest`). Closes last open backlog checkbox in x402 Phase 6.

## 2026-05-28 — v1.59 Bureau (Government Interaction Framework)

- **v1.59.9-bureau-mock** — `gov/bureau-mock/` module: `MockFiscalProvider`/`MockSocialProvider`/`MockRegistryProvider` (in-memory, `succeed` flag, `recorded*` call inspection, `reset()`); `MockBureauProvider` named constructors — `poland()` (PL + all 3 domains), `vat()` (EU/VIES fiscal+registry), `all()` (all domains); `examples/bureau-demo.ssc`. 32 tests.

- **v1.59.8-bureau-scheduler** — `gov/bureau-scheduler/` module: `BureauCalendar` (Polish business day calendar, Meeus/Jones/Butcher Easter algorithm, Corpus Christi, Epiphany); `JobSpec` ADT (OneTime/Recurring/PeriodJob); `SimpleScheduler` (ScheduledExecutorService-backed; runNow/disable/enable; onJobComplete/onJobFailed callbacks). 28 tests.

- **v1.59.7-bureau-eu** — `gov/bureau-eu/` module: `EuViesAdapter` (SOAP checkVat call to EC VIES service; injectable `postSoap`; SOAP fault + HTTP 503/429 handling); `EuRegistryProvider` (RegistryProvider for EU-level VatEU lookups; UnsupportedOperation for non-VatEU ids). 25 tests.

- **v1.59.6-bureau-pl-social** — `gov/bureau-pl-social/` module: `ZusNrbGenerator` (ISO 7064 MOD-97 NRB/IBAN generation; `98 - (BigInt(bban+"252100") % 97)` check digit formula); `ZusContributionCalculator` (2024 ZUS rates; HALF_UP rounding); `PlZusAdapter` (SocialProvider for ZUS PUE REST; KEDU XML ZUA/ZWUA/ZIUA/DRA). 37 tests.

- **v1.59.5-bureau-pl-fiscal-declarations** — `PlDeclarationAdapter` (e-Deklaracje SOAP; JPK_VAT7M/JPK_FA/CIT-8/PIT-36). 19 tests.

- **v1.59.4-bureau-pl-fiscal-ksef** — `PlKsefAdapter` (QES session auth; FA_VAT invoke/poll/fetch/query); `KsefXmlBuilder`; `KsefSessionStore`. 32 tests.

- **v1.59.3-bureau-pl-registry** — 4 adapters (CEIDG/REGON/Biała Lista/KRS); `PlRegistryProvider` orchestrator; injectable HTTP. 58 tests.

- **v1.59.2-bureau-signing** — `gov/bureau-signing/` module: `SigningProvider` SPI (`sign/verify/certificateInfo`); `SignatureFormat` enum (XAdES, PAdES, CAdES, JWS); `SignedDocument`/`VerificationResult`/`CertificateInfo`; `SigningError` sealed hierarchy (KeystoreError, CertificateExpired, UnsupportedFormat, VerificationFailed); `PfxSigningProvider` (PKCS#12 via `java.security.KeyStore`; SHA256withRSA; password-copy-then-zero pattern); `MockSigningProvider` (SHA-256 digest as fake signature, configurable cert info); `SelfSignedCertHelper` (keytool subprocess for test cert generation). `sbt bureauSigning`. 12 tests across PfxSigningProviderTest + MockSigningProviderTest.

- **v1.59.1-bureau-core** — `gov/bureau-core/` SPI module: `CountryCode` opaque type (PL/DE/FR/UA/EU constants + `apply` validator); `LegalForm` enum (13 cases incl. `Other(name)`); `TaxIdentifier`/`TaxIdType`/`TaxId` (NIP, REGON, KRS, PESEL, VatEU, EIN, SIREN, HRB, `Other(country, name)`); `Address` + `BusinessEntity` (with `taxId`/`requireTaxId`); `GovDomain` enum (7 cases); `SubmissionStatus`/`SubmissionResult`/`GovError`/`GovWarning`; `BureauError` sealed hierarchy (9 cases: ApiError, AuthenticationError, SignatureError, ValidationError, MissingTaxId, UnsupportedOperation, RateLimitError, ServiceUnavailable, SubmissionRejected); domain provider traits `CountryProvider`/`FiscalProvider`/`SocialProvider`/`RegistryProvider`/`CustomsProvider`/`StatisticsProvider`/`EnvProvider`; shared fiscal types (`FiscalInvoice` with `Currency`+`ExchangeRate`, `InvoiceLine`, `TaxSummaryLine`, `VatRate`, `TaxDeclaration`, `AuditFile`, `InvoiceFilter`, `InvoiceRef`, `InvoiceSubmissionResult`, `VatVerificationResult`); social types (`ContributionDeclaration`, `EmployeeRecord`, `ContractType`, `DeregistrationReason`, `PaymentReference`, `ContributionParams`, `ContributionBase`, `ContributionCalculation`); registry types (`BusinessRecord`, `RegistrationStatus`, `RegistrationDetails`, `VatPayerStatus`); customs/stats types (`IntrastatReport`, `IntrastatLine`, `TradeFlow`, `StatisticsReport`, `EnvironmentReport`). `sbt bureauCore`. 24 tests in `BureauCoreTest`.

- **v1.51.6-streams-typed** — Type-safe algebraic-effect integration for streams. **Track 1 — type system:** `EffectOp(name: String, args: List[SType])` replaces the plain `Set[String]` in `EffectRow`; all existing effects (`Logger`, `Clock`, etc.) migrate to `EffectOp(name, Nil)` (no behavior change); `Stream[A]` uses 1 type arg; `solveEffectRow` rewritten for element-wise name+args unification; `parseDeclReturnType` now handles `Type.Apply` in effect rows so `! Stream[Int]` parses correctly. **Track 2 — Stream feature completion:** 4 typed ops (`Stream.emit[A]`, `Stream.complete[A]`, `Stream.error[A]`, `Stream.request[A]`); `runStream[A, R](body): (Source[A], R)` canonical algebraic-effects form; interpreter returns `TupleV(List(source, bodyResult))`; JS backend returns `[_makeAsyncStream(...), bodyResult]` with error-path generator; JVM backend returns `(emitted.toList, bodyResult)` tuple; `detectCapabilities` updated in both JsGen and JvmGen to detect all 4 Stream ops. `streams.ssc` externals refreshed. 87 interpreter tests + 24 JS-codegen tests, all passing.

- **v1.51.3-streams-flow-sink** — 10 new `Flow` companion constructors with interpreter intrinsics, `BuiltinsRuntime` companion wiring, and `JsGen` codegen: `Flow.fromFunction(f)`, `Flow.take(n)`, `Flow.drop(n)`, `Flow.flatMap(f)`, `Flow.scan(z)(f)` (curried), `Flow.mapAsync(n)(f)` (curried), `Flow.recover(h)`, `Flow.throttle(rate)`, `Flow.debounce(ms)`. `streams.ssc` extended with complete extern declarations for `Source.tick/unfold/fromCallback`, `Source.scan/onError/cancellable` instance methods, and the full `Sink` + `Flow` companion API. 11 new Flow tests; 564/564 total pass.

- **v1.51.2-streams-js** — JS codegen for the full backpressured streams API. `_makeAsyncStream` in `JsRuntimeAsyncB` extended with 17 new methods: combining (`merge/zipWith/broadcast/balance/groupBy/mergeSubstreams`), advanced (`scan/onError/cancellable/buffer/throttle/debounce/mapAsync/recover/mapError`), routing (`async to(sink)/via(flow)`). New `genExpr` cases: `Source.tick(ms)` → infinite `while(true)/setTimeout` async iterator; `Source.unfold(seed)(f)` → curried nested-apply lowering with `_None/_Some/_t[0]/_t[1]`; `Source.fromCallback(register)` → push-via-array pattern; `Sink.foreach(f)/fold(z)(f)/ignore/toList` → run-object literals; `Flow.map(f)/filter(p)` → apply-object literals. `detectCapabilities` now adds `Async` when any stream API is referenced so `_makeAsyncStream` is always emitted with stream-using modules. 20 `JsGenStreamsTest` code-shape tests (no node execution required), all passing.

- **v1.51.1-streams-source-core** — 6 new Source operators for the interpreter. Instance methods: `scan(z)(f)` (running aggregate, no initial-value emission); `onError(f)` (side-effect on error, elements pass through on success); `cancellable()` (returns `(Source, cancelFn: () => Unit)` via an `AtomicBoolean`-guarded forwarding VT). Companion factory methods wired in both `StreamsIntrinsics.table` and `BuiltinsRuntime` Source companion assembly: `Source.tick(ms)` (infinite Unit source with configurable delay); `Source.unfold(seed)(f)` where `f :: s → None | Some((nextState, emitValue))`; `Source.fromCallback(register)` (push-based; `register` receives a callback that puts into a bounded queue). 12 new tests, 68/68 total pass.

- **v2.0-cross-platform-smoke** — Cross-platform portability for the v2.0 artifact pipeline. `InterfaceExtractor.normalizeLineEndings(bytes)`: strips `\r\n` and bare `\r` to `\n` (fast-path: no allocation when no CR present). `InterfaceExtractor.sourceFileHash(bytes)`: normalizes then SHA-256 — used for all `.ssc` source-file hash computations. `ModuleGraph.isStale/isJvmStale/isJsStale`: switched from `sha256` to `sourceFileHash`; `extract()` likewise. A `.ssc` file checked out with CRLF on Windows now hashes identically to the LF variant, making `.scim`/`.scjvm`/`.scjs` artifacts fully cross-platform-portable. `CrossPlatformSmokeTest`: 13 tests covering normalization edge-cases, `extract()` CRLF/LF stability, `os-lib` path-separator portability (2 cases), concurrent writes to distinct dirs, concurrent same-path last-write-wins. `specs/v2.0-scale-benchmark.md §Cross-platform smoke` updated.

- **wallets-metamask-js** — Added `x402ClientJs` Scala.js browser wallet helper: `Wallets.metaMask(network)` connects through `window.ethereum`, validates EIP-155 chain id, signs EIP-712 via `eth_signTypedData_v4`, and has 7 Node-backed tests with stubbed MetaMask provider.

- **oauth-par** — PAR (RFC 9126 Pushed Authorization Requests). `OAuthRoutes.handlePar` — new `POST /par` endpoint: validates client + redirect_uri, stores `PushedAuthRequest` (TTL = `parRequestTtlSeconds`, default 90s), returns 201 with `request_uri` (urn:ietf:params:oauth:request_uri:<nonce>) + `expires_in`. `OAuthRoutes.handleAuthorize` extended: resolves `request_uri` query param via `AuthServer.parRequests.consume` (single-use, expiry-checked), overlays stored params as effective query; rejects direct params when `AuthServerConfig.parRequired = true`. New types: `PushedAuthRequest`, `PushOutcome` (Pushed/Error), `PushedAuthRequestStore` trait, `InMemoryPushedAuthRequestStore`. `AuthServer.pushAuthorizationRequest` validates client + redirect_uri, generates URN, saves record. `metadataJson` always includes `pushed_authorization_request_endpoint`; adds `require_pushed_authorization_requests: true` when `parRequired`. 27 tests in `OAuthPARTest`.

- **oauth-dpop** — DPoP (RFC 9449) sender-constrained tokens. New `DPoP` object: `verifyProof(proofJwt, htm, htu, ...)` validates DPoP proof JWTs (RS256 + ES256; `typ=dpop+jwt`, alg check, JWK extraction + signature verify, `htm`/`htu` binding, `iat` freshness, `jti` single-use via `InMemoryJtiStore`, optional `nonce` + `ath`); `jwkThumbprint(jwk)` (RFC 7638 SHA-256); `accessTokenHash(token)` (SHA-256 `ath`). `AuthServer.issueToken` gains `dpopJwkThumbprint: Option[String]` — injects `cnf.jkt` + sets `token_type=DPoP`. `OAuthRoutes.handleToken` extracts `DPoP` request header, validates via `as.dpopJtiStore`, returns 400 `invalid_dpop_proof` on failure. `OAuthGuard.check` gains `requestMethod`/`requestUrl`/`dpopJtiStore` params — validates `cnf.jkt`-bound tokens against the DPoP proof, backward-compatible when params absent. `AuthServerConfig.dpopNonceLifetimeSeconds`; script API `dpopNonce` field wired. 36 tests in `OAuthDPoPTest`.

## 2026-05-27

- **spark-lakehouse-l4-hudi** — Apache Hudi lakehouse support (L.4). `SparkGen.DefaultHudiVersion = "0.15.0"`; `lakehouseConfigs` extended with Hudi branch: `spark.serializer=KryoSerializer`, `spark.sql.extensions=HoodieSparkSessionExtension` (merged comma-separated with Delta/Iceberg values), `spark.sql.catalog.spark_catalog=HoodieCatalog`; `genModule` emits `//> using dep "org.apache.hudi:hudi-spark3.5-bundle_2.13:0.15.0"` dep; `examples/spark-lakehouse-hudi.ssc` (write/read/upsert round-trip); `specs/spark-lakehouse.md §L.4` updated; 9 new `SparkGenTest` tests; duplicate Iceberg test block removed from test file.

- **spark-lakehouse-l3-iceberg** — Apache Iceberg lakehouse support (L.3). `SparkGen.DefaultIcebergVersion = "1.5.2"`; `IcebergFormatPattern` (case-insensitive `.format("iceberg")`); `lakehouseConfigs` extended to emit 5 Iceberg config pairs (`spark.sql.extensions=IcebergSparkSessionExtensions`, `spark.sql.catalog.spark_catalog=SparkSessionCatalog`, `spark_catalog.type=hive`, `spark.sql.catalog.local=SparkCatalog`, `local.type=hadoop`); `genModule` emits `//> using dep "org.apache.iceberg:iceberg-spark-runtime-3.5_2.13:1.5.2"` dep; Delta+Iceberg `spark.sql.extensions` merged comma-separated by existing `lakehouseConfigs` groupBy logic; `examples/spark-lakehouse-iceberg.ssc` (write/read, time-travel `snapshot-id`, `MERGE INTO` via SQL extension); `specs/spark-lakehouse.md §L.3` updated; 11 new `SparkGenTest` tests.

- **v1.58-compliance-provider** — AML/KYC/sanctions compliance provider SPI + adapters. `payments/compliance/` SPI: `ComplianceProvider` trait (`screenAml/verifyKyc/checkSanctions/getStatus/fullReport`); `BlockchainComplianceProvider` extends it with `screenTransfer`; `ComplianceEntity/BlockchainAddress/TransferDirection/RiskLevel/ComplianceStatus/AmlResult/KycResult/SanctionsResult/TransferRiskResult/ComplianceReport` model; `ComplianceError` sealed hierarchy (`CheckFailed/EntityRejected/UnsupportedCheck/RateLimitExceeded/ProviderError`); 24 SPI tests. `payments/compliance-complyadvantage/`: POST `/searches`, `Token` auth, fuzz search, risk_level mapping (low→Approved/medium→ManualReview/high|very_high→Rejected); 20+ tests. `payments/compliance-chainalysis/`: POST `/api/kyt/v2/transfers` + GET `/api/risk/v2/entities/<addr>`, `Token` auth, 0–100 riskScore, no-address fallback, verifyKyc always Approved; 19 tests. `payments/compliance-mock/`: configurable status per check type, named constructors (`allApproved/allRejected/manualReview/sanctionsHit/highRiskTransfer`); 21+ tests. 4 sbt subprojects in root aggregate. Spec: `specs/compliance-provider.md`.

- **v1.57.1-payment-rails-australia-npp** — Australia NPP (New Payments Platform / PayID) adapter: `runtime/std/payments-au-npp/` subproject (`AuNppProvider`, `AuNppApi`, `AuNppWebhookReceiver`, `AuNppPlugin`); PayID proxy resolution (mobile/email/ABN → BSB+account via aggregator REST); ISO 20022 pacs.008 JSON envelope to aggregator; AUD-only enforcement (`UnsupportedCurrency` error); BSB+account fallback when PayID not present; `BankAccount.bsbNumber` additive field; `BankRailsEvent.AuNppCredited/AuNppReturned`; `BankRailsError.NppPayIdNotFound/UnsupportedCurrency`; HMAC-SHA256 `X-NPP-Signature` webhook; irrevocable cancel guard; `specs/payment-rails-apac.md §AU_NPP`; 35+ tests.

- **v1.58-tax-provider** — Tax calculation SPI + three adapters. `payments/tax/` SPI module: `TaxProvider` trait (`calculateTax/validateTaxId/getSupportedJurisdictions`); `TaxRequest/TaxQuote/TaxedLineItem/TaxAddress/TaxLineItem/JurisdictionTax/TaxIdValidation/Jurisdiction` model; `TaxError` sealed hierarchy (`TaxCalculationFailed/TaxIdValidationFailed/UnsupportedJurisdiction/TaxProviderError`); `TaxMoneyConverter` utility (`totalTax/totalWithTax/effectiveTaxRate`); 20 SPI tests. `payments/tax-stripe/` — Stripe Tax Calculations API v1: form-encoded POST `/v1/tax/calculations`, Basic auth (sk_... as username), idempotency key header, format-only `validateTaxId`, 19 supported jurisdictions; 18 tests. `payments/tax-avalara/` — Avalara AvaTax REST v2: JSON POST `/api/v2/transactions/create`, Basic `accountNumber:licenseKey` auth, `X-Avalara-Client` header, GET `/api/v2/taxnumbervalidation`; 12 countries + 51 US states+DC; 17 tests. `payments/tax-taxjar/` — TaxJar SmartCalcs v2: JSON POST `/v2/taxes`, Bearer token, decimal major-unit amounts; 16 countries + 51 US states+DC; 17 tests. All adapters: injectable HTTP methods for testability; `Plugin` ServiceLoader; 4 sbt subprojects (`paymentsTax/TaxStripe/TaxAvalara/TaxJar`) in root aggregate.

- **openapi-export** — Auto-derived OpenAPI 3.1 spec: `GET /_openapi.json` (live JSON doc, regenerated each request) + `GET /_swagger` (CDN-linked Swagger UI HTML). `OpenApiRuntime` registers both alongside health routes when `serve`/`serveAsync` is called; walks `RouteRegistry.all`; converts `:param` segments to `{param}` OpenAPI notation; inspects `Value.FunV.paramTypes` to separate path params (in-path), query params (GET/DELETE non-path typed params), and request body (POST/PUT/PATCH); type map String→string / Int+Long→integer / Double+Float→number / Boolean→boolean / other→object; `NativeContext.registerOpenApiDefaults()` hook; internal `/_*` routes excluded; `IntrinsicImpl.scala` + `Interpreter.scala` + `HttpIntrinsics.scala` wired; empty-registry bug fixed (missing outer `}` in JSON output); 16 tests in `OpenApiRuntimeTest`.

- **v1.57-fx-provider** — FX rate provider SPI: `payments/fx/` (`FxProvider` trait, `FxRate`, `CurrencyPair`, `FxError` hierarchy, `FxMoneyConverter`); `payments/fx-ecb/` (`EcbFxProvider` — ECB daily XML feed, EUR base, 1h TTL cache); `payments/fx-openexchangerates/` (`OerFxProvider` — OER API v6, USD base, mock HTTP server tests); 76 tests total (19 SPI + 26 ECB + 31 OER). Spec updated in `specs/traditional-payments.md §FxProvider`.

- **v1.57.3-payment-rails-mexico-spei** — Mexico SPEI adapter: `runtime/std/payments-mx-spei/` (`MxSpeiProvider`, `MxSpeiApi`, `MxSpeiWebhookReceiver`, `MxSpeiPlugin`); `ClabeValidator` with 18-digit control-digit check (multipliers [3,7,1,3,7,1,...]); `RailKind.MX_SPEI`; `BankAccount.clabe` additive field; `BankRailsEvent.MxSpeiConfirmed/MxSpeiRejected/MxSpeiReturned`; HMAC-SHA256 `X-SPEI-Signature` webhook; SPEI irrevocable cancel guard; `paymentsMxSpei` sbt module; 44 tests.

- **graph-storage-fullstack** — Graph storage Phase 6 full-stack examples: `examples/graph-fullstack.ssc` (Electron frontend + embedded TinkerGraph server; `GET /api/graph/vertices`, `GET /api/graph/neighbors/:id`, `POST /api/graph/vertex`; IndexedDB cache-first read with background refresh; React module+neighbor list); `examples/graph-fullstack-rdf.ssc` (RDF4J in-memory backend; `GET /api/graph/triples`, `POST /api/graph/sparql`, `PUT /api/graph/rdf`; SPARQL query panel; triple table rendering).

- **v1.57.2-payment-rails-canada-eft** — Canada Interac e-Transfer + EFT rail adapter. `runtime/std/payments-ca-eft/` subproject with `CaEftProvider` (BankRailsProvider for `RailKind.CA_INTERAC` + `RailKind.CA_EFT`); `CaEftApi` with CPA Standard 005 AFT fixed-width file builder (1,464-byte records; types 450 credit / 470 debit; header A / detail D / trailer Z); `CaEftWebhookReceiver` (HMAC-SHA256 `X-Interac-Signature`; 4 events: `interac.transfer.sent/reclaimed/expired`, `eft.debit.returned`); `CaEftPlugin` ServiceLoader registration; `BankAccount` gains additive `email`, `phone` fields; `BankRailsEvent` gains 4 CA cases; 67 tests in `CaEftProviderTest` covering Interac by email/phone, CAD enforcement, EFT credit/debit file build, CPA 005 field positions/padding/checksums, cancel semantics (recall vs irrevocable), all webhook events, idempotency.

- **secret-resolvers-cloud** — Three optional cloud secret resolver plugins: `AwsSmResolver` (scheme `aws-secret`, AWS Secrets Manager via `software.amazon.awssdk:secretsmanager:2.26.31`, default creds chain, `AWS_REGION`); `GcpSmResolver` (scheme `gcp-secret`, GCP Secret Manager via `com.google.cloud:google-cloud-secretmanager:2.46.0`, ADC, `GOOGLE_CLOUD_PROJECT` shorthand); `AzureKvResolver` (scheme `azure-kv`, Azure Key Vault via `com.azure:azure-security-keyvault-secrets:4.8.7` + `azure-identity:1.13.3`, `DefaultAzureCredential`). Each in a separate sbt subproject (`backend/sql-aws`, `sql-gcp`, `sql-azure`) registered via ServiceLoader. 41 tests (14+14+13) using injectable protected methods — no real cloud creds required.

- **spark-mllib-m2-m5 (v1.25 §M.2–M.5)** — Spark MLlib auto-dep + Vector encoder + examples. `SparkGen.containsMllib` regex detects `import org.apache.spark.ml.*` / `o.a.s.ml.*` → emits `//> using dep "org.apache.spark:spark-mllib_2.13:<v>"` (M.2); `SscSparkEncoders` shim gains `aenc_MLVector: AgnosticEncoder[MLVector]` via `UDTEncoder(SQLDataTypes.VectorType as VectorUDT)`, gated on `usesMllib` so non-MLlib modules never reference MLlib JAR classes (M.3); `examples/spark-mllib-pipeline.ssc` (Tokenizer+HashingTF+LogisticRegression pipeline on 4-row dataset, M.4); `examples/spark-mllib-model-save-load.ssc` (save/load round-trip + prediction equivalence check, M.5); 14 new codegen tests in `SparkGenTest.scala`.

- **spark-streaming-f2-f4** — Spark Structured Streaming phases F.2–F.4. F.2: `SparkGen.containsStreaming` detects `spark.readStream`/`.writeStream`; auto-emitted `spark.streams.active.headOption.foreach(_.awaitTermination())` shim in `@main def runSparkJob` suppressed when user code already calls `awaitTermination`; `Trigger`/`StreamingQuery`/`OutputMode` imports always emitted. F.3: `SparkGen.containsFileStreamSink` detects file-format streaming sinks (parquet/csv/json/orc/text); auto-emitted `// NOTE Phase F.3` checkpoint-location reminder in file header when no `checkpointLocation` option is present in user code. F.4: `SparkGen.containsKafkaFormat` detects `.format("kafka")`; auto-emits `//> using dep "org.apache.spark:spark-sql-kafka-0-10_2.13:<v>"` in header. 3 example files (`spark-streaming-rate-console.ssc`, `spark-streaming-file-parquet.ssc`, `spark-streaming-kafka.ssc`). 13 codegen tests in `SparkGenTest`. All 175 `SparkGenTest` tests pass.

- **spark-catalog-g2-g4 (v1.25 Phase G.2–G.4)** — Spark Catalog DSL: G.2 `spark-hive-metastore:`/`spark-warehouse:` front-matter keys emit `spark-hive_2.13` dep + `.config("spark.sql.catalogImplementation","hive")` + metastore URI / warehouse dir lines + `.enableHiveSupport()` (9 SparkGenTest cases, ordering contract, escape semantics, `.enableHiveSupport()` short-circuit); G.3 `@TempView("name")` regex annotation rewriter strips annotation line and emits `<var>.createOrReplaceTempView("<view>")` after the val declaration, composes with `@SqlFn` (9 SparkGenTest cases); G.4 `Dataset.fromTable[T](name)` shim via `spark.table(name).as[T]` on the Dataset companion (3 SparkGenTest cases); `examples/spark-catalog-hive.ssc` (end-to-end: warehouse front-matter + @TempView + sql block + fromTable typed read-back); opt-in smoke test under `RUN_SPARK_INTEGRATION=1 && RUN_SPARK_HIVE=1`. 175 SparkGenTest cases all pass.

- **spark-lakehouse-l2** — Delta Lake lakehouse detection + codegen: `detectLakehouseFormats` extended to match `.format("delta")` (case-insensitive), `import io.delta.` and `DeltaTable.` patterns; `detectLakehouseFormats(String)` overload added; `lakehouseImports` auto-emits `import io.delta.tables.DeltaTable` in generated source when Delta is detected; dep + 2 config lines auto-emitted; 11 new `SparkGenTest` cases; `examples/spark-lakehouse-delta.ssc` round-trip + history demo. L.3 Iceberg and L.4 Hudi remain deferred.

- **v1.56-xslt** — XSLT 1.0 transformation support: `MarkupCodec.transform(doc, xslt, params)` SPI hook (default `Left(TransformError(...))`); `XsltTransformer` object in `runtime/backend/interpreter` using `javax.xml.transform.TransformerFactory`; `JvmMarkupCodec.transform` override; `Feature.Xslt` added to `Feature` enum + `InterpreterCapabilities` + `JvmCapabilities`; `CapabilityCheck` pattern-detects `.transform(` calls and gates on `Feature.Xslt`; `examples/xslt-transform.ssc`; 18 `XsltTransformerTest` + 3 `CapabilityCheckTest` tests (all pass).

- **markup-xsd-sepa-refactor (v1.55.6)** — `ValidationError(message, line, column)` confirmed in `MarkupCodec.scala`; `SepaPainXml` (PAIN.001/008 + SCT Inst pacs.008) and `Iso20022Xml` (FedNow pacs.008) refactored from raw string concat to `xml"..."` interpolator + `PureMarkupCodec.serialize`; `markupCore` added as dep to `paymentsSepa` + `paymentsFednow` in build.sbt; 12 PAIN.001 golden-file fixtures + `SepaPainXmlGoldenTest` (22 tests) + `Iso20022XmlGoldenTest` (11 tests); all 105 tests (71 SEPA + 34 FedNow) pass.

- **markup-element-literal (v1.55.5)** — `MarkupLiteralLower` AST transform in `lang/core/transform/`: source-level preprocessor that, when `import scalascript.markup.*` is present in a scalascript block, rewrites `<name attr={expr}>children</name>` and `<name/>` syntax to `Markup.Element(QName.local/prefixed(...), attrs, children)` constructor calls before scalameta parsing; namespaced tags, nested elements, text children, string and expression attributes all supported; wired into `Parser.parse` after `RouteDeriver.derive`. 16 tests.

- **markup-config-js (v1.55.7)** — `ConfigParser.Format.Xml` + `detectFormat` (.xml extension); `XmlConfigParser` (element→`ConfigValue.Map`, attrs→`_attrs/@name`, repeated tags→`Lst`, CDATA leaf support); `runtime/std/markup-js/` (`JsMarkupCodec`+`JsMarkupPlugin` — browser DOMParser/XMLSerializer Scala.js codec); `runtime/std/markup-node/` (`NodeMarkupCodec`+`NodeMarkupPlugin` — `@xmldom/xmldom` Node.js codec); `markupCore` cross-compiled (JVM+JS) via `CrossType.Pure`; 41 tests (16 + 11 + 14).

- **markup-feature-backend (v1.55.3)** — `Feature.Markup` + `Backend.markupCodec` SPI + `JvmMarkupCodec`. `case Markup` added to `Feature` enum; `def markupCodec: Option[MarkupCodec] = None` added to `Backend` trait; `JvmMarkupCodec` (SAX parse + PureMarkupCodec serialize + XSD validate via `javax.xml.validation`) wired into `InterpreterBackend` + declared in `InterpreterCapabilities`/`JvmCapabilities`; `CapabilityCheck` detects `xml"..."` interpolator and fenced xml blocks, rejects on backends lacking `Feature.Markup`; 16 tests.

- **markup-lang-xml (v1.55.2)** — `Lang.Xml = "xml"` + `isXml`; `Value.MarkupV(doc: Markup.Doc)`; `SectionRuntime.runXmlBlock` (XML-escape interpolated values, parse via `PureMarkupCodec`, bind as `<section>.xml`); `renderStringBlock` generalised with `escapeFn: Option[String => String]`; `markupCore` added to `core` dependsOn. 8 tests in `SectionXmlBlockTest` pass.

- **markup-compile-check (v1.55.4)** — Compile-time `xml"..."` well-formedness checker. `MarkupInterpolatorCheck` in `lang/core/transform/`: walks scalameta trees, joins `xml"..."` string parts with `<placeholder/>` for each `${expr}` hole, calls `PureMarkupCodec.parse` — emits `Diagnostic.XmlParseError(message, line, col)` on failure. Added `markupCore` dep to `core` sbt module. 10 tests.

- **v1.55.8-singapore-paynow** — New `runtime/std/payments-sg-paynow/` subproject: `PayNowProvider` (BankRailsProvider for SG_PAYNOW rail, two-step flow: proxy resolution → FAST payment initiation, SGD-only enforcement, cancel with BankRailsCancelError), `PayNowApi` (aggregator REST client; proxy resolution + payment initiation; `ProxyResolutionResult` parsing), `PayNowWebhookReceiver` (HMAC-SHA256 `X-PayNow-Signature` verify; parses `paynow.payment.credit/return` → `PayNowSettled/PayNowFailed`), `PayNowPlugin` (ServiceLoader). SPI additions: `PayNowProxyType` enum (Mobile/NricFin/Uen/Vpa), `BankRailsEvent.PayNowSettled/PayNowFailed`, `BankRailsError.PayNowProxyNotFound`. 67 tests.

- **v1.55.7-japan-zengin** — Japan Zengin (全銀) domestic bank transfer adapter. New `runtime/std/payments-japan-zengin/` subproject: `ZenginProvider` (BankRailsProvider for `JP_ZENGIN`, injectable clock for settlement-window tests), `ZenginFile` (Zengin 21 format: fixed-width 120-byte records — type 1 header / type 2 data / type 8 trailer / type 9 end), `KatakanaValidator` (validates half-width kana U+FF66–U+FF9F + space + hyphen; returns `Right(name)` or `Left(invalidChars)`), `ZenginWebhookReceiver` (HMAC-SHA256 `X-Zengin-Signature`; parses `zengin.transfer.completed/failed` → `ZenginSettled/ZenginRejected`), `ZenginPlugin` (ServiceLoader). 59 tests.

- **v1.55.6-india-upi** — New `runtime/std/payments-india-upi/` subproject: `UpiProvider` (BankRailsProvider for IN_UPI; push flow via `initiateTransfer` maps to UPI Pay API; collect flow via `initiateDirectDebit` maps to UPI Collect API using `creditorAccount.upiVpa` / `debtorAccount.upiVpa`; RSA-SHA256 request signing with merchant private key; VPA format validation), `UpiWebhookReceiver` (RSA-SHA256 `X-UPI-Signature` verify with aggregator public key; parses `upi.payment.success/failed/collect.expired/collect.initiated` → `UpiApproved/UpiDeclined/UpiCollectInitiated`; signature check skipped when no key configured), `UpiPlugin` (ServiceLoader), `UpiCollectRequest` model, `UpiConfig` (apiKey, merchantVpa, baseUrl, merchantPrivateKeyPem, webhookPublicKeyPem, callbackUrl, defaultPurposeCode). SPI additions: `BankRailsEvent.UpiApproved/UpiDeclined/UpiCollectInitiated` + BACS DD/Zengin/PayNow event stubs, `BankRailsError.UpiTwoFactorTimeout` + BacsCycleMissed/ZenginOutsideWindow/PayNowProxyNotFound. 63 tests.

- **v1.55.5-uk-chaps** — New `runtime/std/payments-uk-chaps/` subproject: `UkChapsProvider` (BankRailsProvider for UK_CHAPS, ISO 20022 pacs.008.001.08 submission to aggregator, GBP-only enforcement, cancel with BankRailsCancelError), `ChapsPacs008Builder` (pacs.008 with `SvcLvl=CHAPS`, `SttlmMtd=INDA`, no ClrSys; IBAN or sort-code+account; BIC for CdtrAgt), `UkChapsWebhookReceiver` (HMAC-SHA256 `X-CHAPS-Signature` verify; parses `chaps.payment.settled/rejected` → `ChapsSettled/ChapsRejected`), `UkChapsPlugin` (ServiceLoader). SPI additions: `BankRailsEvent.ChapsSettled/ChapsRejected`. 46 tests.

- **v1.55.4-uk-bacs** — `runtime/std/payments-uk-bacs/`: UK BACS Direct Debit adapter. `UkBacsProvider` (BankRailsProvider for `UK_BACS_DD`), `BacsFile` (Standard-18 110-char fixed-width file: record types 0/1/5/9, debit/credit/trailer, amounts in pence), `AuddisFile` (AUDDIS mandate registration file, instruction codes 0N/0C/0S), `UkBacsWebhookReceiver` (HMAC-SHA256 `X-BACS-Signature`, events: submitted/collected/auddis-accepted/returned), `UkBacsPlugin` (ServiceLoader). `BankRailsEvent.BacsDdSubmitted/Paid/AuddisAccepted/AruddReturned`, `BankRailsError.BacsCycleMissed`. `AruddCode` object maps all 11 ARUDD return codes (0,1,2,3,5,6,B,C,F,G,H). 61 tests.

- **x402-cardano-scalus-completion** — Phase 3/5/6 completion of the Cardano/Scalus escrow settlement feature. Phase 3: `ReferenceScriptDeployer.deploy(blockfrost, network, signingKeyHex, feeLovelace)` for one-time CIP-33 reference-script UTxO creation; `ScalusSettlerConfig.referenceScriptRef` optional config field; 2 deployer tests. Phase 5: `ScalusRoundTripTest` — 4 end-to-end tests: round-trip verify ok, settle builds correct `ClaimTxPlan`, tampered CIP-8 signature → `Fail`, malformed escrowRef → `Fail`. Phase 6: `EscrowDatumOffChain` (payerKeyHash, claimMessageHash, receiverHash, amount, validBefore, refundAfter) + `EscrowDeposit.build(payerPublicKeyHex, req, validBeforeSlot, refundAfterSlot, cfg)` payer-side deposit helper; 3 deposit tests; `examples/x402-cardano-scalus.ssc` (4-step Preprod walkthrough: deploy → deposit → client → facilitator). Updated `EscrowScriptTest` golden bech32 addresses and `BloxbeanClaimTxDraftBuilderTest` to use `EscrowScript.address(...)` dynamically.

- **v1.55.3-uk-faster-payments** — New `runtime/std/payments-uk-fps/` subproject: `UkFpsProvider` (BankRailsProvider for UK_FPS rail, REST JSON over HTTPS to aggregator, CoP name-check before each payment), `ConfirmationOfPayee` (CoP client with `CopResult` enum: Matched/CloseMatch/NoMatch/AccountSwitched/Unavailable), `UkFpsWebhookReceiver` (HMAC-SHA256 `X-FPS-Signature` verify; parses `uk.faster-payments.credit/rejected/return` → `UkFpsAccepted/Rejected/Returned`), `UkFpsPlugin` (ServiceLoader registration). SPI additions: `RailKind.UK_FPS` + 7 other future rail cases, `BankAccount.sortCode` (and other v1.55 fields), `BankRailsEvent.UkFpsAccepted/Rejected/Returned`, `BankRailsError.UkCopNameMismatch`. 47 tests.

- **v1.55.2-sepa-instant** — Extended `runtime/std/payments-sepa/` with SEPA Instant Credit Transfer (SCT Inst): `RailKind.SCT_INST`, `SepaPainXml.buildSctInstPacs008` (pacs.008.001.08 with `LclInstrm=INST`, `SttlmMtd=CLRG`, `ClrSys=SCTInst`), `BankRailsEvent.SctInstSettled/SctInstRejected`, `BankRailsError.SctInstTimeout` (10-second window exceeded); `SepaProvider.supportedRails` extended; webhook parsing for `SCTInst.CreditTransfer.Settlement/Rejection`; 19 new tests (49 total).

- **v1.55.1-international-swift** — SWIFT MT103 + ISO 20022 pacs.008 (CBPR+) bank rails adapter. `payments/bank-rails/` gains `Uetr` opaque type, `ChargeBearer` enum (OUR/SHA/BEN), `GpiHop` case class, additive fields on `BankTransfer` (uetr/gpiTrail/chargeBearer), `InitiateTransferRequest` (chargeBearer/uetr), `BankAccount` (bic + 5 more v1.55 fields), 9 new `RailKind` cases, SWIFT GPI event cases, SWIFT error cases. New `runtime/std/payments-swift/` subproject: `SwiftProvider` (SWIFT_MT103 + SWIFT_PACS008), `SwiftMt103Builder` (MT103 field 20/32A/50K/57A/59/70/71A/121), `SwiftPacs008Builder` (pacs.008.001.10 FIToFICstmrCdtTrf with CBPR+ mandatory fields), `GpiTracker` (GPI webhook event parsing), `SwiftWebhookReceiver` (HMAC-SHA256 X-SWIFT-Signature), `SwiftPlugin` (ServiceLoader). 65 tests.

- **wallet-solana-standard-js** — Scala.js Solana Wallet Standard browser registration (`wallet-connector-wallet-std/js/`): `WalletInfo` JS-native trait (name, icon, chains, features), `WalletStandardJs.register(info, connector)` dispatches `wallet-standard:register-wallet` CustomEvent + legacy `window.standard.wallets.registerWallet`, `StandardWalletConnectorJs` feature-map bridge; 6 Node.js smoke tests via `global.window` stub.

- **v1.55.1-markup-core** — `runtime/std/markup-core/`: first-class XML / Generic Markup milestone (v1.55) phase 1.  Delivers `Markup` sealed ADT (`Doc`, `Element`, `Attr`, `Text`, `CData`, `PI`, `Comment`, `DocType`, `XmlDecl`, `QName`, `Raw`); `MarkupCodec` SPI (parse / serialize / validate); `XmlEscape` (5-entity escape + unescape, text + attr variants); `PureMarkupCodec` (zero-dependency XML 1.0 recursive-descent parser + serializer, ~300 LoC, handles namespaces / CDATA / entities / PIs / comments / self-closing / mixed content); `xml"..."` string interpolator (mandatory XML-escape for all args, `Markup.raw(...)` passthrough, `Markup.Element` splice via serializer, `Markup.Doc` splice).  17 tests (`MarkupSpec` + `XmlInterpolatorSpec`).

- **wallet-ledger-cardano** — `payments/wallet/vault-ledger-cardano/`: `CardanoApp` object (CLA=0xD7, INS=0x10 GET_EXTENDED_PUBLIC_KEY, INS=0x21 SIGN_TX); `CardanoCip8` minimal COSE Sig_Structure builder (hand-rolled CBOR, no deps); `LedgerCardanoVault` (`Vault` SPI, Ed25519 only, `AppSwitchRequired` guard via `Dashboard.getAppName`); `LedgerCardanoRawSigner` (CIP-8 wrapping, 64-byte ed25519 sig); `walletVaultLedgerCardano` sbt subproject (JVM-only); 11 tests.

- **wallet-ledger-solana** — `payments/wallet/vault-ledger-solana/`: `SolanaApp` object (CLA=0xE0, INS=0x04 SIGN_TRANSACTION, INS=0x05 GET_PUBKEY, INS=0x07 SIGN_OFFCHAIN_MESSAGE); lightweight `Base58` encoder (Bitcoin/Solana alphabet, pure Scala, no deps); `LedgerSolanaVault` (`Vault` SPI, Ed25519 only, `AppSwitchRequired` guard); `LedgerSolanaRawSigner` (64-byte ed25519 sig, no v-byte); `walletVaultLedgerSolana` sbt subproject; 13 tests.

- **wallet-ledger-js** — Added `payments/wallet/vault-ledger-js`: Scala.js WebHID Ledger transport (`navigator.hid`), 64-byte HID APDU framing, browser `LedgerVault` lifecycle, Ethereum signer reuse, Cardano CIP-8 COSE helper, and 13 mocked WebHID tests.

- **wallet-ledger-bitcoin** — `payments/wallet/vault-ledger-bitcoin/`: `BitcoinApp` object (CLA=0xE1, new protocol v2+; GET_EXTENDED_PUBKEY/REGISTER_WALLET/GET_WALLET_ADDRESS/SIGN_PSBT); `LedgerBitcoinVault` (`Vault` SPI, secp256k1 only, `AppSwitchRequired` guard); `LedgerBitcoinRawSigner` (PSBT bytes → per-input DER sigs concatenated); `walletVaultLedgerBitcoin` sbt subproject; 14 tests.

- **ssc-profile** — `ssc profile <file.ssc>` with per-phase timing + heap allocation (`parse`/`typecheck`/`normalize`/`jvm-codegen`/`link`); flame-graph JSON (`--out`); `--top=N` hottest phases; `--compare=baseline.json` regression diff with ⚠ on >10%; `--runs=N` min/avg/max; `PhaseResult`+`timed` helper; `Profiler.recordPhase`/`phaseEntries()`; 15 tests in `ProfileCommandTest`.

- **js-tree-shaking** — `TreeShaker` worklist reachability from `@main`/exports; `JsGen.generateWithStats` emits only reachable `const`/`function` declarations; `--no-tree-shake` escape hatch; `--stats` prints "Tree-shake: kept N / M symbols" to stderr; 16 tests in `JsTreeShakeTest`.

- **blockchain-cosmos** — `payments/blockchain/cosmos/`: secp256k1 ECDSA (RFC 6979) + ed25519 signing via BouncyCastle; Cosmos StdSignDoc Amino JSON encoding with canonical field order; bech32 address derivation with configurable HRP (`cosmos`/`osmo`/`juno`); `CosmosChainAdapter` implementing `ChainAdapter` SPI; `ChainId.CosmosHub`/`ChainId.Osmosis`/`ChainId.Juno` added to `blockchain-spi`; `BlockchainProvider` SPI trait + `CosmosBackend` ServiceLoader registration. 41 tests.

- **ssc-check** — `ssc check` expanded: `--json` (structured diagnostics), `--quiet` (exit-code-only for CI hooks), `--watch` (WatchService re-check on change), directory mode (recursive `*.ssc` scan), distinct exit codes (0/1/2/3). 18 integration tests in `CheckCommandTest`.

- **v1.54.4-bank-rails-fednow** — `runtime/std/payments-fednow/` FedNow instant payments adapter: ISO 20022 pacs.008.001.08 credit transfer XML builder, pacs.002.001.10 status parser (ACCP/PDNG→Pending, ACSC→Settled, RJCT→Rejected), HMAC-SHA256 webhook receiver, FedNowProvider (USD-only, $500K limit, cancel/direct-debit unsupported), FedNowPlugin SPI, 23 tests, `examples/bank-rails-fednow.ssc`.

- **v1.54.2-bank-rails-ach** — `payments/bank-rails/` (BankRailsProvider SPI + BankTransfer/DirectDebitMandate core types + RCode/CCode) + `runtime/std/payments-ach/` (NachaFile 94-char fixed-width builder, AchProvider, AchWebhookReceiver HMAC-SHA256, AchPlugin Backend SPI, `AchConfig`, same-day ACH, R/C-code handling, `examples/bank-rails-ach.ssc`). 28 tests.

- **v1.54.3-bank-rails-pix** — Pix instant payments adapter (Brazil): EMV Merchant-Presented QR Code builder (static + dynamic, CRC-16/CCITT), `PixProvider` (BCB DICT REST, OAuth2 token, T+0 settlement, Pix Automático/cobv direct debit), `PixWebhookReceiver` (HMAC-SHA256, `pix.received`/`pix.refunded`/`pix.rejected`), `PixPlugin` (SPI entry point), `payments/bank-rails/` core SPI types (`RailKind`, `BankTransfer`, `BankRailsProvider`, etc.), `Feature.BankRails`. 32 tests.

- **blockchain-bitcoin** — secp256k1 ECDSA (RFC 6979 deterministic k), BIP-143 SegWit sighash, BIP-340 Schnorr signing/verification, BIP-341 Taproot (tapTweakHash + tweakedKey + tweakedPrivateKey), P2WPKH bech32 (`bc1q`/`tb1q`) + P2TR bech32m (`bc1p`/`tb1p`) address derivation, PSBT BIP-174 builder/signer/finalizer/deserializer, `BitcoinChainAdapter` (`ChainAdapter` SPI), `ChainId.BitcoinMainnet`/`ChainId.BitcoinTestnet` added to `blockchain-spi`. 45 tests.

- **v1.51.4-streams-sse-ws** — `mapAsync(n)(f)` parallel map (semaphore-bounded, ordered results); `.recover(handler)` error recovery; `.mapError(f)` error transformation; `Source.bracket(acquire)(release)(use)` resource lifecycle; `Source.fromSse(url)` SSE HTTP client source; `Sink.toSseStream` SSE response formatter; `Source.fromWebSocket(url)` WebSocket message source; `Sink.toWsRoom(room)` WsRoom broadcast sink. All operators in the interpreter plugin; Source/Sink companions updated in BuiltinsRuntime. 8 new tests (49 → 57 total).

- **v1.51.5b-streams-clock-ui-signals** — Streams now pace `.throttle(Rate)` with interpreter wall-clock scheduling, delay finite `.debounce(durationMillis)` bursts before emitting the latest value, subscribe `Source.signal(sig)` to frontend `ReactiveSignal` updates, and support reverse `sig.bind(source)` for frontend signals. Swing/JavaFX runtime state maps now stay synchronized with the shared signal bus; SwiftUI native bridging is tracked separately as `v1.51.5c-streams-swiftui-bridge`.

- v1.54-bank-rails-spec — Bank Rails spec (SEPA/ACH/Pix/FedNow) ✓ (2026-05-27)

- **v1.54.1-bank-rails-sepa** — `payments/bank-rails/` SPI + `runtime/std/payments-sepa/` SEPA CT+DD adapter: PAIN.001/008 XML builder, HMAC-SHA256 webhook, SepaProvider, Feature.BankRails, 30 tests. (2026-05-27)

- **v1.51.5-streams-buffer** — Streams plugin now supports `.buffer(n, OverflowStrategy)` with `Backpressure`/`Block`, `Drop`, `DropHead`/`DropOldest`, and `Fail`; `.throttle(Rate)`; `.debounce(durationMillis)`; `Rate(...)`; `OverflowStrategy` companion constants; and `Source.signal(sig)` as an interpreter current-value adapter. Added 7 interpreter tests and expanded `examples/streams.ssc`. Live UI signal subscriptions and Clock-effect-backed wall-time scheduling are tracked as `v1.51.5b-streams-clock-ui-signals`.

- **x402-cardano-scalus-validator-simulator-tests** — Added `x402-escrow-plutus` ScalaTest coverage that constructs Scalus `ScriptContext` values directly for the escrow validator. Tests cover the claim happy path, tampered CIP-8 signature rejection, wrong receiver amount rejection, claim validity-window rejection, refund happy path, and refund timing rejection.

- **v1.52.7-deploy-state-backends** — `JsonState` (zero-dep JSON ser/de for StateRecord). `LocalFileStateBackend` (~/.ssc-state/<app>/<env>/<target>.json; sibling .lock with TTL contention detection). `S3StateBackend` (aws s3api subprocess; optimistic mtime-based TTL lock). `ConsulStateBackend` (Consul KV HTTP API v1; session-based locking). `EtcdStateBackend` (etcdctl subprocess; lease-based locking). `StateBackendFactory` (backend dispatch; production-env enforcement). `StateMigrator` (ssc deploy state migrate; dry-run; skipped/failed tracking). 14 new tests; 105 total.

- **v1.52.6-deploy-faas** — `FaasTarget` (`kind: faas`): AWS Lambda (LambdaZip via `buildLambdaZip`+`ZipOutputStream`; `aws lambda create-function/update-function-code/publish-version/update-alias "live"`; `aws logs tail`), Cloudflare Workers (`wrangler deploy` with `CLOUDFLARE_API_TOKEN`/`CLOUDFLARE_ACCOUNT_ID`), GCP Cloud Run (`gcloud run deploy --platform managed --allow-unauthenticated`), Vercel Functions (`vercel --prod`). All dry-run capable; `rollback` via Lambda alias version pointer. `TargetFactory` extended with `"faas"/"lambda"/"serverless"`. 11 new tests; 91 total.

- **v1.52.5-deploy-static** — `StaticTarget` (`kind: static`): Vercel (CLI or Deployments API v13), Netlify (CLI or API), Cloudflare Pages (wrangler or API; account_id via `team:`), GitHub Pages (git push orphan branch to gh-pages). HTTP GET status. TargetFactory `"static"`. 9 new tests; 80 total.

- **v1.52.4-deploy-traditional** — `SystemdUnitGenerator` (FatJar/NativeBinary/NodeBundle unit templates, env vars, pre/post hooks). `SshSystemdTarget` (SSH+SCP artifact + systemd unit; `systemctl restart/is-active`; `journalctl -u` logs; pre/post_deploy). `RsyncTarget` (rsync with configurable SSH rsh; post-deploy hook). `SftpTarget` (SFTP batch upload; post-upload unpack_cmd). TargetFactory extended with transport sub-dispatch for traditional kind + rsync/sftp top-level kinds. 18 new tests; 71 total.

- **v1.52.3-deploy-k8s** — `K8sManifestGenerator`: Deployment (liveness `/_health` + readiness `/_ready` probes, PreStop drain hook, resource limits, nodeSelector, annotations, blue-green slot labels) + Service (ClusterIP, slot-selector for blue-green switching) + Ingress + ConfigMap + Secret (base64-encoded). `K8sTarget`: full 7-verb SPI + `switch()` (kubectl patch Service selector) + `promote()` (scale→switch→scale old to 0); dry-run; `kubectl rollout undo` rollback; log streaming. `TargetFactory` extended with `"k8s" | "kubernetes"`. 17 new tests; 53 total.

- **v1.52.2-deploy-container** — `DockerfileGenerator`: four base-image recipes per `ArtifactKind` (FatJar→`eclipse-temurin:21-jre-alpine`, NativeBinary→`gcr.io/distroless/cc`, NodeBundle→`node:22-alpine`, SpaBundle→`nginx:alpine`); build-args/labels/env/port/HEALTHCHECK support; `writeDockerfile` helper. `ContainerTarget`: full 7-verb `DeployTarget` SPI (`build`/`push`/`deploy`/`rollback`/`status`/`logs`/`outputs`); builder auto-detect (`buildctl` → `docker buildx` → `docker build`); multi-platform via `platform:`; digest capture for rollback; dry-run throughout. `TargetFactory`: resolves `kind: container | traditional`. `ArtifactRegistry` extended with `OciImage`. 14 new tests; 36 deploy-plugin tests total.

- **v1.53.7-payments-webhook-cluster** — `payments/webhook-redis/`: `RedisSeenKeyStore` uses Lettuce `SET NX EX` (atomic set-if-not-exists with TTL) for cluster-safe deduplication; configurable key prefix (`whk:`) + await timeout; 8 tests with in-memory stub client. `payments/webhook-postgres/`: `PostgresSeenKeyStore` uses `INSERT … ON CONFLICT DO NOTHING` (atomic under PRIMARY KEY constraint) with auto-CREATE TABLE, expired-entry filtering in `wasSeen`, and `purgeExpired()` maintenance method; tested with H2 in-memory database (9 tests). Both modules added to sbt build; both implement `SeenKeyStore` SPI from `payments/webhook/`.

- **x402-cardano-scalus-validator-validity-range** — The Scalus Plutus validator now enforces claim/refund validity windows: claims must be entirely before `datum.validBefore`, refunds entirely after `datum.refundAfter`. Validator CBOR regenerated.

- **x402-cardano-scalus-validator-output-shape** — The Scalus Plutus validator now enforces claim output shape: at least one transaction output must pay exactly `datum.amount` lovelace to `PubKeyCredential(datum.receiverHash)`. Validator CBOR regenerated.

- **v1.53.6-payments-mock-provider** — `runtime/std/payments-mock/`: fully in-memory `MockProvider` with `MockMode` enum (Succeed / Fail(error) / RequireSCA(redirectUrl)) configurable per effect group (`chargeMode`, `refundMode`, `disputeMode`, `subscribeMode`, `vaultMode`). All 16 SPI methods implemented against `ConcurrentHashMap` state; `recorded*` inspection helpers + `reset()`. `MockWebhookReceiver`: skips HMAC verification, parses minimal JSON events, exposes `recorded: List[PaymentEvent]` for assertions. `PaymentEffect` enum (Charging / Refunding / Disputing / Subscribing / Vaulting / Webhooking) + `PaymentEffect.of(op)` added to SPI. ServiceLoader registration via META-INF/services. 41 new tests.

- **x402-cardano-scalus-validator-cip8** — The Scalus Plutus validator now checks canonical CIP-8 claim redeemers on-chain: COSE_Key public-key extraction, payer key-hash match, COSE_Sign1 payload hash match, Sig_Structure reconstruction, and Plutus `verifyEd25519Signature`. `x402EscrowPlutus/emitEscrowHex` now writes to the actual `payments/x402/...` resource path; committed validator CBOR regenerated.

- **v2.1.10-dstream-conformance** — Cross-backend DStream conformance suite (§14.3). New `runtime/backend/conformance/` sbt module (`backendConformance`) with `DStreamConformanceTest`: 8 tests run the same pipeline SSC through all 4 generators (Spark, Kafka Streams, Flink, Beam) and assert structural conformance. Tests cover: word count, windowed word count, stateful running sum, side inputs, windowed joins, connector stubs, backend alias declarations, and full operator surface. SparkGen + KafkaStreamsGen `Backend` object extended with missing `Flink`/`Beam` aliases — now all 4 shims declare the same 7 backend aliases (`Direct`, `Native`, `Spark`, `KafkaStreams`, `Kafka`, `Flink`, `Beam`). `examples/distributed-streams.ssc` expanded from 3 to 12 examples, covering windowed word count (§5.3), stateful running sum (§5.5), broadcast state (§5.5), side inputs (§5.6), side outputs (§5.6), inner join (§5.7), left outer join (§5.7), flatten (§5.7), processing-time timer (§5.4).

- **v2.1.9-dstream-joins** — Windowed joins + flatten for all DStream backends. `DStream.join(other)` (inner join on KV keys), `DStream.leftOuterJoin(other)` (all left, right `Option`), `DStream.rightOuterJoin(other)` (all right, left `Option`), `DStream.flatten` (collapses `DStream[DStream[T]]` or `DStream[Seq[T]]`) added to all 4 code-gen shims (Spark, Kafka Streams, Flink, Beam). All 4 `containsDStream` methods extended with `.join(`, `leftOuterJoin`, `rightOuterJoin`, `.flatten` detection. Native interpreter (`DStreamsIntrinsics`): `evalDag` handlers for `_dag_join`, `_dag_leftOuterJoin`, `_dag_rightOuterJoin`, `_dag_flatten`; `dstreamOps` wiring. `CAP_WINDOWED_JOINS` was already declared in `directCapabilities`. +8 interpreter tests, +12 generator tests across Spark/Kafka/Flink/Beam.

- **v2.1.8-dstream-side-io** — Side inputs and side outputs for all DStream backends. `SideInput[T]` case class + `object SideInput` (`of(stream)`, `singleton(v)`, `asMap(stream)`) added to all 4 code-gen shims. `OutputTag[B]` case class + `object OutputTag` (`apply(name)`, `withFilter(name)(fn)`) added. `DStream.withSideInput(si)` cross-joins main stream with side input elements. `DStream.sideOutput(tag)` returns `(DStream[T], DStream[B])` pair — main stream plus filtered side stream. All 4 `containsDStream` methods extended with `withSideInput`, `sideOutput`, `SideInput.`, `OutputTag` detection. Native interpreter: `evalDag` handlers for `_dag_withSideInput`, `_dag_sideOutput`; `dstreamOps` wiring; `SideInput` + `OutputTag` companions in `BuiltinsRuntime.setupPluginCompanions`; `CAP_SIDE_INPUTS` + `CAP_SIDE_OUTPUTS` added to `directCapabilities`. +8 interpreter tests, +8 generator tests across Spark/Kafka/Flink/Beam.

- **v1.53.5-payments-vault-mandates-sca** — SPI extended: `ScaExemption` enum (LowValue / TrustedListing / TransactionRiskAnalysis / Recurring / MerchantInitiated), `scaExemptions: List[ScaExemption]` + `mandateId: Option[MandateId]` added to `CreateIntentRequest`, `networkToken: Option[String]` + `mandateId: Option[MandateId]` added to `StoredMethod`, `Mandate` extended with `customerId`/`vaultId`/`providerRef`, `createMandate`/`getMandate` added to `PaymentProvider` trait. All 5 adapters updated: Stripe uses `/setup_intents` for mandate creation + `/mandates/{id}` retrieval + SCA `request_three_d_secure` mapping; Adyen wires `shopperInteraction=ContAuth` + `recurringProcessingModel` for off-session + `scaExemption` additionalData; PayPal wires `payment_source.card.stored_credential` for MIT + `/v3/vault/setup-tokens` for mandates; Braintree/Checkout.com/Square implement mandate stubs. Network token extracted from Stripe `card.networks.preferred`, Adyen `networkToken`, PayPal `network_token`. 9 new SPI-level tests. 87 total tests green.

- **v2.1.7-dstream-stateful** — Stateful processing + timers for all DStream backends. `statefulMap(init)(f)` and `statefulFlatMap(init)(f)` added to all 4 code-gen shims (Spark, Kafka Streams, Flink, Beam): per-key state accumulation where `f: (S, A) => (S, B)`. `broadcastState(stateStream)` added: pairs each main-stream element with a `Map[K, V]` built from KV state stream. `timerEventTime(tsMs)(f)` added (event-time timer; behaves like `timerProcessing` in bounded DirectRunner). State types added to all shims: `ValueState[T]`, `MapState[K, V]`, `ListState[T]`, `BagState[T]` (in-memory implementations), `StateContext[K, S]` context class, `KeyedStateSpec[K, S]` spec + companion. All 4 `containsDStream` methods extended with `statefulMap`, `statefulFlatMap`, `broadcastState`, `KeyedStateSpec` detection. Native interpreter (`DStreamsIntrinsics`): `evalDag` handlers for `_dag_statefulMap`, `_dag_statefulFlatMap`, `_dag_broadcastState`, `_dag_timerEventTime`; `dstreamOps` wiring for curried operators; `KeyedStateSpec.value` intrinsic; `KeyedStateSpec` companion in `BuiltinsRuntime.setupPluginCompanions`. +20 new tests across all modules (42 interpreter, 40 Flink/Beam, 30 KafkaStreams, 195 Spark).

- **v2.1.6-dstream-connectors** — Production connector stubs for all 5 DStream backends. `Kafka`, `Files`, `FileFormat`, `Jdbc`, `Pulsar`, `Kinesis` companion objects emitted in all 4 code-gen shims (Spark, Kafka Streams, Flink, Beam) when any connector usage detected. `DSink[T] = Any` type alias emitted alongside. `containsConnector` detection added to all generators; connector detection now triggers DStream shim emission. `DSource.fromDataset` bridge added. Native interpreter: connector intrinsics registered in `DStreamsIntrinsics.table`; companion assembly in `BuiltinsRuntime.setupPluginCompanions` (Kafka, Files, FileFormat, Jdbc, Pulsar, Kinesis, DSource.fromDataset). All connector source stubs return empty `DSource[T]` for bounded testing; live connector execution requires cluster + env var. SparkGen: `Kafka.source/sink/changelog` usage now also triggers `spark-sql-kafka-0-10` dep emission (extends Phase F.4). +14 new tests across all modules.
- **v1.53.4-payments-square** — `runtime/std/payments-square/` (Square adapter: Bearer access_token auth, Square Payments API v2 sandbox/live, Web Payments SDK nonce (`source_id`), HMAC-SHA1 webhook over `notification_url + raw_body` with base64 comparison, `SquareWebhookReceiver`). All 14 SPI methods. No SCA/mandates. Catalog API for subscription plans, `/v2/subscriptions` for recurring billing, `/v2/disputes` for evidence upload. 14 new tests.

- **v2.1.5-dstream-flink** — Flink + Beam backends for DStream: new `runtime/backend/flink/` module with `FlinkGen`, `BeamGen`, `FlinkBackend`, `BeamBackend`, `FlinkCapabilities`, `BeamCapabilities`. Both generators follow the same shim pattern as v2.1.3/v2.1.4: detect DStream code and emit full DSL backed by driver-local `Seq[Any]` for bounded `InMemory` sources. `FlinkGen` targets Flink DataStream API (`flink-streaming-scala_2.12:1.20.1`); `BeamGen` targets Apache Beam Java SDK (`beam-sdks-java-core:2.62.0`), auto-selects runner dep (`DirectRunner`/`FlinkRunner`/`SparkRunner`). Both declare `Backend.Flink` and `Backend.Beam` aliases. `PipelineOptions(extraProperties, checkpointDir, parallelism)` case class emitted. Flink shim includes `_flinkEnv()` helper; Beam shim includes `_createBeamPipeline()` factory. ServiceLoader registration for both backends. `Feature.DistributedStreams` in both capabilities. 30 new `FlinkGenTest` tests.

- **x402-cardano-scalus-evaluate-endpoints** — Added live ex-unit endpoint wiring: `BlockfrostClient.evaluateTx` for `/utils/txs/evaluate`, `ScalusTxEvaluator.blockfrost(...)`, and `ScalusTxEvaluator.ogmiosHttp(url)` for Ogmios JSON-RPC `evaluateTransaction`. Endpoint responses now map into typed claim `ScalusExUnits`.

- **v1.53.3-payments-adyen-checkout** — `runtime/std/payments-adyen/` (Adyen adapter: X-API-Key auth, Checkout API v71, HMAC-SHA256 webhook over 8 sorted notification fields, `additionalData` escape hatch, Drop-in/Web Components nonce support, `AdyenWebhookReceiver` with base64-decoded key) + `runtime/std/payments-checkout/` (Checkout.com adapter: Bearer sk_xxx auth, Unified Payments API v3, HMAC-SHA256 hex over raw body with `Cko-Signature` header, `CheckoutWebhookReceiver`). Both adapters implement all 14 SPI methods. 25 new tests (12 Adyen + 13 Checkout.com).

- **v2.1.4-dstream-kafka** — Kafka Streams backend for DStream: new `runtime/backend/kafka-streams/` module (`KafkaStreamsGen`, `KafkaStreamsBackend`, `KafkaStreamsCapabilities`). `KafkaStreamsGen` detects DStream code (`containsDStream` — fires on `Pipeline.create` / `InMemory.source` / `Backend.KafkaStreams` / `Backend.Kafka` / `Window.*` / `WatermarkStrategy.*` / `Trigger.*`) and emits `dstreamKafkaShim` inside `@main def runKafkaStreamsJob()`. Shim provides full DStream DSL backed by driver-local `Seq[Any]` for bounded `InMemory` sources; Kafka Streams topology builder helpers (`_buildTopology`, `_runWithTestDriver`) for live `Kafka.source` inputs. `Backend.KafkaStreams` and `Backend.Kafka` aliases declared. `//> using dep org.apache.kafka:kafka-streams_2.13:3.7.1` + test-utils + clients directives emitted. `Feature.DistributedStreams` in `KafkaStreamsCapabilities`. ServiceLoader registration. 22 new `KafkaStreamsGenTest` tests. Also extends `SparkGen.containsDStream` with `Window.*` / `WatermarkStrategy.*` / `Trigger.*` detection (fixes SparkGen window shim test).

- **v1.53.2-payments-paypal-braintree** — `runtime/std/payments-paypal/` (PayPal Checkout adapter: OAuth2 client-credentials with 8h token cache, PayPal Orders v2 API, RSA-SHA256 webhook verify against PayPal-fetched cert, all 14 SPI methods, `PayPalWebhookReceiver`) + `runtime/std/payments-braintree/` (Braintree adapter: HTTP Basic auth, GraphQL API for transactions/customers/vault, XML REST for plans/subscriptions/refunds/disputes, HMAC-SHA1 webhook with base64-decoded payload, `BraintreeWebhookReceiver`). 25 new tests (11 PayPal + 14 Braintree).

- **v2.1.3-dstream-spark** — Spark backend for DStream: `SparkGen` extended with DStream detection (`containsDStream` — fires on `Pipeline.create` / `InMemory.source` / `Backend.Spark`) and `dstreamSparkShim` emission. Shim provides full DStream DSL (v2.1.1 + v2.1.2 operators) backed by driver-local `Seq[Any]` for bounded `InMemory` sources; produces identical results to `Backend.Direct` on the Spark driver. Operators: `map`, `filter`, `flatMap`, `keyBy`, `combinePerKey`, `merge`, `window`, `withTrigger`, `withWatermark`, `withAllowedLateness`, `timerProcessing`, `run`, `runToList`, `runFold`, `runForeach`, `runCount`. `KV[K,V]` case class, `Pipeline`, `InMemory`, `DSource`, `Backend`, `PipelineResult`, `Window`, `Trigger`, `WatermarkStrategy`, `AccumulationMode` companions all emitted. `Feature.DistributedStreams` added to `SparkCapabilities`. 14 new `SparkGenTest` tests. Integration tests gated by `SPARK_MASTER` env var (15 skipped without Spark).

- **x402-cardano-scalus-bloxbean-evaluator** — Added `ScalusTxEvaluator.bloxbean(...)` on top of bloxbean `TransactionEvaluator`, plus evaluated-balanced claim draft rebuilding. Evaluator-provided claim ex-units now flow into the redeemer and fee estimate before serialization.

- **x402-cardano-scalus-preprod-it** — Added env-gated Preprod integration coverage for the Cardano/Scalus claim Tx draft. `BloxbeanPreprodIntegrationTest` builds a balanced draft from live Blockfrost Preprod protocol params when `X402_SCALUS_PREPROD_IT=true`; actual submit remains separately gated by `X402_SCALUS_PREPROD_SUBMIT=true`.

- **v1.53.1-payments-spi-stripe** — `payments/money/` (opaque `Currency` + ISO 4217/crypto minor-units table, `Money` Long minor-units arithmetic with HALF_EVEN rounding, `allocate` for penny-perfect splits), `payments/webhook/` (`WebhookReceiver[E]` SPI, `SeenKeyStore` idempotency with expiry, `InMemorySeenKeyStore`), `runtime/std/payments-plugin/` (`PaymentProvider` 14-method SPI, all SPI types: `PaymentIntent`/`PaymentEvent`/`Customer`/`Subscription`/`Refund`/`Dispute`/`Mandate`/`SCAChallenge`/`PaymentError` hierarchy, `Feature.Payments`), `runtime/std/payments-stripe/` (full Stripe adapter: HMAC-SHA256 webhook verify, all 14 methods via Java HttpClient + form-encoded bodies, `StripeWebhookReceiver` with replay protection), `examples/traditional-payments.ssc` (12 worked snippets). `Amount` in `payment-request` deprecated. 33 new tests (19 MoneyTest + 14 StripeProviderTest). Closes `chargeCard()` placeholder from v1.38.

- **v2.1.2-dstream-native-unbounded** — Processing-time windowing + watermarks + `timerProcessing` on the native/direct backend. `window(Window.fixed/sliding/session/global)`, `withTrigger(Trigger.*)`, `withAllowedLateness(d)`, `withWatermark(WatermarkStrategy.*)` operators added to `DStream`. `timerProcessing(durationMs)(k => Iterable[B])` fires synchronously per unique key on DirectRunner. `directCapabilities` now includes `EventTime` + `WatermarkPerfect` (v2.1.2+). `collectRequiredCaps` extended for `_dag_window`, `_dag_withWatermark`, `_dag_withTrigger`. `dstreams.ssc` updated. 30 tests green.

- **v2.1.1-dstream-native-bounded** — Core `DStream[T]` / `Pipeline` Beam-style API on the native bounded backend. `Pipeline.create(name).read(DSource).map/filter/flatMap/keyBy/combinePerKey/merge.run(Backend.Direct|Native)`. `InMemory.source` / `InMemory.runAndCollect` testing helpers. `DSource.fromLocalSource` bridge from `Source[A]`. `Feature.DistributedStreams` flag. `Capability` negotiation at `.run()` (`CAPABILITY_MISMATCH` on missing cap). `examples/distributed-streams.ssc` (3 bounded examples). `dstreams-plugin` (23 tests green). `BuiltinsRuntime.setupPluginCompanions` extended for all DStream companion objects.

- **x402-cardano-scalus-static-exunits** — Added static `ScalusExUnits` wiring for the Cardano/Scalus claim Tx draft: configured ex-units now flow into `ClaimTxPlan`, the bloxbean redeemer, and balanced fee estimation. Live node-backed ex-unit evaluation remains open.

- **x402-cardano-scalus-fee-balancer** — Added `ScalusFeeBalancer` and `BloxbeanClaimTxBuilder.draftBalanced(...)`: the Cardano/Scalus claim Tx draft now estimates protocol min-fee from Blockfrost protocol params and final serialized CBOR size, with async params wiring for Blockfrost-backed builders. Live script ex-unit evaluation remains open.

- **v1.53** — Traditional Payment Processors spec landed (`specs/traditional-payments.md`). `PaymentProvider` SPI (14 methods: PaymentIntent / Customer+Vault / Subscriptions / Refunds+Disputes / Webhooks), fiat-aware `Money` type (Long minor units, ISO 4217 + crypto codes, banker's rounding, `allocate`), `WebhookReceiver[E]` primitive (HMAC/RSA verify + `SeenKeyStore` idempotency + replay protection), `IdempotencyKey` threading, `SCAChallenge` / 3DS2 flow, subscription lifecycle (proration / dunning / invoicing), full dispute lifecycle + evidence submission, vault (`Customer` + `StoredMethod` + `Mandate`). Closes `chargeCard()` placeholder from v1.38 Payment Request. Adapters deferred to v1.53.1–v1.53.7 (Stripe canonical first, then PayPal/Braintree, Adyen/Checkout.com, Square). Bank rails (SEPA/ACH/Pix/FedNow) deferred to v1.54+. Go/no-go: **go**.

- **v1.52.1** — Deploy plugin landed (`runtime/std/deploy-plugin/`). Six-verb `DeployTarget` SPI, `DeployGroup` orchestrator with DAG resolver (Kahn's + cycle detection), Parallel/Sequence/Pipeline execution modes, three failure policies (RollbackAll/ContinueRemaining/AbortRemaining), `LocalSubprocessTarget` adapter (fat-JAR subprocess + `/_health` polling), `DeployManifest` parser, `StateBackend` SPI with `NoopStateBackend`, `ArtifactRegistry` (10 artifact kinds), `Manifest` AST extended with `deploy`/`groups`/`environments`/`state` fields, `ssc deploy` CLI with `plan`/`status`/`envs` subcommands + `--env`/`--group`/`--target`/`--dry-run`/`--verbose` flags, `examples/deploy.ssc` annotated example, `docs/user-guide.md §26`.

- **x402-cardano-blockfrost-protocol-params** — Added typed `BlockfrostClient.getProtocolParams()` for `/epochs/latest/parameters`, covering fee constants, execution prices, collateral bounds, and Plutus cost models. This is the prerequisite data source for Cardano/Scalus protocol-params fee balancing.

- **x402-cardano-scalus-tx-witness** — Hardened the bloxbean Scalus claim transaction draft with explicit fee/TTL/validity body fields, computed script data hash via bloxbean `ScriptDataHashGenerator`, and relayer `VkeyWitness` signing via `TransactionSigner`. Protocol-params fee balancing and live ex-unit evaluation remain open.

- **v2.1.0** — Distributed Streams spec landed (`specs/distributed-streams.md`). Full Apache Beam model: `DStream[T]` / `KV[K,V]` / `Pipeline` / `PipelineResult`; event-time watermarks (`WatermarkStrategy`); Fixed/Sliding/Session/Global windowing; `Trigger` (AfterWatermark, AfterProcessingTime, AfterCount, Composite); panes (EARLY, ON_TIME, LATE) + accumulation modes (Discarding, Accumulating, AccumulatingAndRetracting); `Capability` enum (Set-based, checked at `.run()`); 5 first-class backends (Native v1.22 actors, Apache Spark, Apache Kafka Streams, Apache Flink, Apache Beam); `DSource[T]` / `DSink[T]` connector abstractions; `Coder[T]` unified serialisation with per-backend adapters; `DirectRunner` in-process test backend; integration bridges (`DStream ↔ Source[A]`, `DStream ↔ Dataset[T]`); 7 implementation phases (v2.1.1–v2.1.7). Go/no-go: **go**.

- **x402-cardano-scalus-tx-required-fields** — Extended the bloxbean Scalus claim transaction draft with optional collateral input and required signer key hash: `ScalusSettlerConfig.collateralRef` maps into body collateral, `relayerKeyHashHex` maps into body required signers, with validation and round-trip tests. Fee balancing and relayer vkey witness remain open.

- **v1.52** — Deploy spec landed (`specs/deploy.md`). Five target categories (container/k8s/faas/static/traditional), dual CLI+manifest interface, 6-verb `DeployTarget` SPI + `outputs()` for cross-target wiring, `DeployGroup` orchestrator with parallel/sequence/pipeline modes + DAG dependency resolution + three failure policies, `DeployEnvironment` axis for local/test/staging/production environments with `base:` inheritance + multi-region fault tolerance + quorum-based health checks + blue-green slot switching (`instant`/`gradual`) + `ssc deploy switch` + `ssc deploy promote`. Hybrid stateless+optional-remote-state model. Per-provider adapters deferred to v1.52.1–v1.52.7. Go/no-go: **go**.

- **x402-cardano-scalus-tx-draft** — Added `BloxbeanClaimTxBuilder.draft`, a non-default bloxbean Transaction skeleton builder that serializes the escrow input, receiver output, Plutus V3 script, and Spend redeemer; tests round-trip through bloxbean `Transaction.deserialize`. Fee balancing, collateral, and relayer witness remain open.

- **x402-cardano-scalus-claim-tx-builder** — Added bloxbean Plutus redeemer construction for Scalus escrow claims: `EscrowRedeemerCodec.claim` encodes `Claim(coseSign1Bytes, coseKeyBytes)` as constructor 0, and `ClaimTxPlan.claimRedeemer` exposes it to the future transaction builder. Full transaction body / script witness / relayer witness remain open.

- **x402-cardano-scalus-settler-bloxbean** — Phase 4 wiring for Cardano/Scalus settlement: added `cardano-client-lib` dependency, `ScalusSettlerConfig`, typed `ClaimTxPlan`, injectable `ClaimTxBuilder`, `ScalusSettler.preprod/mainnet`, and Blockfrost submit pipeline tests. The default builder still fails explicitly until real Plutus witness/redeemer construction is implemented.

- **x402-cardano-scalus-escrow-ref** — Added typed `ScalusEscrowRef` parsing/validation for canonical `<64-hex-txhash>#<output-index>` refs and wired `CardanoProvider.Scalus` verification to reject malformed nonce-slot escrow refs before settlement.

- **x402-cardano-scalus-claim-codec** — Factored Scalus claim-message binary encoding into `x402-core` as `ScalusClaimMessageCodec`, with unit tests for domain/receiver/uint64 layout. The Cardano client and facilitator now share the same encoder.

- **x402-cardano-scalus-server-verify** — `CardanoProvider.Scalus` now verifies the structured Scalus claim-message CIP-8 proof and requires the escrow UTxO ref in `authorization.nonce`, while preserving the legacy Blockfrost description-signing + payer-balance verification path. Claim Tx / UTxO datum validation remains planned in the settler.

- **x402-cardano-scalus-claim-message** — Client-side Scalus payment mode: `Wallets.cardano(hex, network, scalusMode = true)` signs a structured `ScalusClaimMessage` instead of `req.description`; `PaymentRequirements.scalusEscrowRef` is propagated through `authorization.nonce`; Cardano payload tests verify the COSE payload and Ed25519 signature. Real settler / claim Tx remains planned.

- **v1.51** — Streams with Backpressure spec: `specs/streams.md` — full design for `Source[A]` / `Sink[A]` / `Flow[A, B]` / `Stream[A]`; hybrid pull/push (push surface, `request(n)` credit underneath); default credit = 16 / buffer = 16 (Akka default); two-level architecture (uniform `Computation`-based semantics + JVM/interpreter VT+ArrayBlockingQueue and JS `async function*` fast paths); overflow strategies aliased from `actors.ssc Overflow`; errors flow downstream + cancel upstream; integration adapters for Generator/SSE/WS/Actor/UI-signals (`Source.signal` scoped to v1.51.5); effect-row integration deferred to v1.51.6+. Go/no-go: **go** — implementation sequence v1.51.1 → v1.51.2 → v1.51.3 → v1.51.4 → v1.51.5 defined.

- **x402-cardano-scalus-address** — Cardano/Scalus escrow Phase 3 slice: `EscrowScript.address(network)` derives stable CIP-19 enterprise script addresses from the committed Plutus validator bytes. Golden mainnet/preprod bech32 tests pin the address surface for future reference-script deployment and bloxbean claim Tx work.

- **wallet-vault-encrypted-js** — JS-side encrypted vault persistence: `EncryptedLocalVaultJs.create/load/generate/delete/save` wraps the shared `EncryptedLocalVault` core with `VaultFileStore`; browser default uses IndexedDB, falls back to localStorage, then in-memory storage for Node/tests. Durable data remains the shared `VaultFile.toJson` shape. Added Scala.js tests for create/load/unlock, account metadata persistence, and delete.

- **sbt-interop-plugin** — `ssc generate-facade` CLI command + `sbt-scalascript-interop` sbt plugin (Tier 3 interop): `ssc generate-facade <artifactDir> [-o <outDir>]` reads `.scim` artifacts and writes Scala 3 facade sources (delegating to `FacadeGenerator.generate`); `ScalascriptInteropPlugin` (Scala 2.12, `tools/sbt-plugin/`) auto-hooks into `Compile / sourceGenerators` via `sscGenerateFacade` task; `sscArtifactDir` and `sscBinary` settings; 4 scripted tests (`basic`, `identity`, `multi-module`, `no-artifacts`); Mill module trait + scala-cli directive documented in `specs/scala-interop.md §6`.

- **watch-100ms** — watch reload benchmark + hot-path hashing cleanup: new `ssc watch-bench [--cycles N] [--target-ms N] [--require-target] <file.ssc>` command runs watch reload cycles against a temporary copy and reports warm-up/p50/max; `WatchCycleBenchTest` covers the incremental path; `ParseCache` and `SectionSnapshot` SHA-256 hex encoding now use a direct char loop instead of per-byte `String.format`; incremental typer reuses precomputed section hashes when building snapshots for retyped sections.

- **v1.50-native-p2-followup** — Complete native-image Phase 2/3 gaps: `native-release.yml` now builds `ssc-plugin-host.jar` via `sbt pluginHost/assembly` and bundles it at `lib/ssc-plugin-host.jar` inside every platform archive so `--plugin <jar>` works out-of-the-box in native mode; `BackendRegistry.findPluginHostJar` extended to check `<binary-dir>/lib/` first (matches archive layout) then flat `<binary-dir>/` (dev installs); `BACKLOG.md` updated to mark phases 2–4 as landed.

- **v1.50-native-p4** — Native plugin binary guide: `docs/native-plugin-guide.md` — complete plugin-author guide for building GraalVM native binaries from existing plugins. Covers: `GraalVMNativeImagePlugin` sbt setup, minimal reflection/resource config, agent-based config generation, CI matrix (ubuntu/macos arm64/macos x86_64), `plugin.yaml` manifest for native executables, and JAR-vs-native comparison table. No core changes. Fully JVM-free `ssc (native) → wire protocol → plugin (native)` deployments now documented.

- **ws-load-10k** — Smoke test: 10 000 concurrent WebSocket connections via Loom virtual threads. `WsLoad10kTest` asserts ≥ 99 % open, heap growth < 1 GB, `WsConnection.activeCount` tracks correctly, all drain cleanly. Auto-skips when `ulimit -n` < 22 000. Satisfies the Project-Loom follow-up deferred since 2026-05-21.
- **v1.50-native-p3** — `ssc-plugin-host` + automatic native bridge: new `tools/plugin-host` sbt subproject (`pluginHost`); `SubprocessHost` main class loads any plugin JAR via `URLClassLoader` + `ServiceLoader` (works in JVM subprocess), then enters the stdio-json wire protocol loop as the server side (handles `describe`, `compile`, `openSession`, `session.feed`, `session.close`, `invokeHandler`, `shutdown`). `BackendRegistry.addPluginJar` detects native-image mode via `org.graalvm.nativeimage.imagecode` system property; in native mode locates `ssc-plugin-host.jar` next to the binary or in `$SSC_HOME/lib/`, finds `java` via `java.home` system property or PATH, then spawns `java -cp plugin.jar:host.jar scalascript.plugin.SubprocessHost plugin.jar` and registers the result via the existing `SubprocessBackend` mechanism. Plugin authors change nothing. Build: `sbt pluginHost/assembly` → `ssc-plugin-host.jar`.

- **v1.50-native-p2** — GraalVM native-image build infrastructure: `sbt-native-packager` plugin added; `cli` project gains `GraalVMNativeImagePlugin` with `--no-fallback`, `--initialize-at-build-time=scala,scalascript`, reflection + resource config file pointers; `native-image-configs/reflect-config.json` (SLF4J binding, Scala runtime, upickle, scala-meta, borer, all ServiceLoader-discovered backend/frontend/server/plugin implementation classes); `native-image-configs/resource-config.json` (`META-INF/services/**`, logger-sources); `.github/workflows/native-release.yml` CI matrix (ubuntu x86_64, macos arm64, macos x86_64) triggered by version tags, uploads `.tar.gz` to GitHub Release; `stage` task renamed to `installBin` to avoid conflict with sbt-native-packager. Build: `sbt cli/graalvm-native-image:packageBin`. Regeneration guide in `native-image-configs/README.md`.

- **v1.50-native-p1** — Replace snakeyaml with pure-Scala `SimpleYaml` parser: new `lang/yaml` sbt module containing `SimpleYaml` (block/flow maps+sequences, scalars, comments, literal block scalars, inline map entries from sequence items); wired into `core` and `backendConfigRuntime` (previously standalone, no deps); all 7 call sites migrated (`Parser.scala`, `LockFile.scala`, `LocalRegistry.scala`, `SscpkgManifest.scala`, `PluginManifest.scala`, `ConfigParser.scala`, `Main.scala loadSopsSecrets`); snakeyaml removed from `build.sbt`; 21 new `SimpleYamlTest` tests.

## 2026-05-26

- **v1.12.3** — Effects stdlib: `StdEffectsRuntime` gains `NonDet` (multi-shot, `choose(options)`) and `Reader` (capability, `ask()`) globals; typed discharge signatures registered in `Typer` prelude for `runLogger`/`runLoggerJson`/`runLoggerToList`, `runRandomSeeded`, `runClockAt`, `runEnvWith`, `runState`, `runHttp`/`runHttpStub` (each accepts a body carrying the named effect row); `EffectAnalysis.verify` promoted to error-level with `asErrors: Boolean = true` default; `examples/algebraic-effects.ssc` showcase (Logger + State interleaved, NonDet multi-shot, capability vs handler styles, stdlib runner signatures); 2 new `StdEffectsTest` tests (42 total). v1.12 effects sprint complete.
- **NativeContext state-bag** — added shared `feature*` and scoped `featureLocal*` state APIs to `NativeContext`; HTTP client config now routes through `NativeContextFeatureKeys` while existing named methods stay compatible.
- **v1.12.2** — One-shot effect runtime: `EffectAnalysis.Result` gains `multiShotEffects: Set[String]`; `collectFromStats` detects `val __multiShot__ = true` in effect objects; `_handleOneShot` JS runtime emitted in preamble with per-dispatch `_resumed` flag; `genHandleForm` routes to `_handleOneShot` when all ops are one-shot; interpreter `evalHandle` gains `multiShotEffects: Set[String] = Set.empty` parameter and raises `InterpretError("One-shot violation: …")` on double-resume; `Interpreter.multiShotEffects` populated from `EffectAnalysis` in `runInit`; 3 new tests (`EffectAnalysisMultiShotTest`); `StdEffectsTest` (40) and `RestartableTest` (17) still green.
- **v1.12.1** — Typed Algebraic Effects — type system foundation: `SType.EffectRow` case with optional open tail variable; `SType.Function` extended with `effects: EffectRow` (default empty, backward-compatible); `show`/`subst`/`freeVars` updated; Rémy-style row unification in `Unifier.solveEffectRow`; `TypeParser` extended with `!` operator and `parseEffectSet` for effect-annotated function types; `multi effect` keyword in `Parser.preprocessEffects` emits `val __multiShot__ = true`; `EffectAnalysis.verify` cross-checks typer-declared effects against reachability analysis; 14 new tests (`EffectTypeTest`, `EffectAnalysisVerifierTest`).
- **v1.49** — macOS distribution: `ssc package --target macos --distribution` (codesign + notarize + DMG via `xcodebuild archive` + `exportArchive` + `notarytool` + `hdiutil`); `ssc publish --target macos --appstore` (fastlane `mac_appstore` lane, generates `Fastfile` by default); `ssc toolchain setup-signing` (`fastlane match init` for ios/macos); `fastlane` and `ios-deploy` added to toolchain tool map and target requirement lists; `--no-dmg`, `--no-notarize`, `--distribution` flags; 8 new tests (26 total in SwiftUIBuildCliTest).
- **v1.48.5** — `ssc publish --target ios` (TestFlight + App Store via fastlane): generates `Fastfile` with `testflight`/`appstore` lanes by default; `--fastlane` uses existing Fastfile; `--testflight`/`--appstore` route selection; `--api-key-path` / `APP_STORE_CONNECT_API_KEY_PATH`; `--submit-for-review`; `--release-notes`; 6 new tests (18 total in SwiftUIBuildCliTest).
- **wasm-backend-phase1** — WASM backend extended to compile `scalascript` / `ssc` blocks alongside `scala` blocks (Phase 1); integration tests and `wasm-scalascript.ssc` example (Phase 2); `//> using dep` directive hoisting for Scala.js dep declarations + `wasm-http.ssc` Fetch API example (Phase 3). `WasmBackend.acceptedSources` grows to include `scalascript` and `ssc`. 31 tests passing.
- **v1.48.4** — `ssc package --target ios` → signed `.ipa`: `xcodebuild archive` + `exportArchive`; ExportOptions.plist generated from frontmatter `bundle-id:`/`team-id:` or `SSC_TEAM_ID` env; `--export-method` (development|ad-hoc|enterprise|app-store, default: development); `--team-id`; `--out`; 4 new tests (12 total in SwiftUIBuildCliTest).
- **Interpreter server extraction** — `WebServer`, interpreter HTTP handler, WS proxy/session/connection runtime, in-process backend transport, and server-specific tests moved to new `backendInterpreterServer` module behind `InterpreterServerSupport`.
- **v1.12** — Typed Algebraic Effects spec: `specs/algebraic-effects.md` — full design for `A ! Eff` type syntax, open effect rows with implicit tail, `effect Foo { … }` / `multi effect Foo { … }` declarations, handler discharge rules, capability passing (`?=>`), one-shot fast paths (coroutine VT on JVM/interpreter; `function*`/`yield` on JS), multi-shot via Free-monad, interaction matrix with `throws`, `Async`, `Free`, and `MonadError`. Go/no-go: **go** — implementation milestone sequence v1.12.1 → v1.12.2 → v1.12.3 defined.
- **SQL plugin cleanup** — interpreter `transaction` fenced blocks now route through `SqlBlockRunner.runTransaction`; JDBC transaction execution and result encoding live in `runtime/std/sql-plugin` instead of interpreter core.
- **v1.48.3** — `ssc run --target ios --device` real device via ios-deploy: xcodebuild arm64 + automatic signing (`-allowProvisioningUpdates`) + `ios-deploy --bundle ... --no-wifi [--debug|--justlaunch]`. `--device-id <udid>` for specific device. Same `--console`/`--no-rebuild` flags as simulator path.
- **v1.48.2** — `ssc run --target ios` one-command iOS Simulator launch: xcodebuild → boot latest iPhone sim → open Simulator.app → install → `simctl launch`. `--console`/`--no-console` (default: stream logs), `--rebuild`/`--no-rebuild` (default: incremental mtime check). `--target ios` canonical; `mobile-ios` alias kept. `pickIosSimulator` picks latest available iPhone from highest iOS runtime.
- **v1.48.1** — `ssc run --target macos` one-command wrapper: generates Swift Package, runs `swift build`, launches binary. `ssc package --target macos` now includes `swift build`. Target renamed `desktop-macos` → `macos` (alias kept). `swiftAppName` helper derives Swift product name from `.ssc` name frontmatter.
- **v1.48** (SwiftUI Phase 3) — Reactive list lowering: `ForSignal` → `ForEach` with `ForCtx` for index-aware `RemoveSelfFromList`; `@Observable AppModel` emitted when list signals present (Observation framework, iOS 17+); 11 new tests (41 total). v1.48 now feature-complete.
- **v1.48** — JavaFX Typed Route Clients: same-process in-process BackendTransport for JavaFX; typed route client codegen for JavaFX mode.
- **v1.47** — JavaFX Desktop Frontend: JavaFX renderer with reactive View DSL and full SwingFrontend-parity API.
- **v1.46** (all phases complete) — Typed Route Clients: generated frontend clients over backend routes. JVM/Swing in-process + JS HTTP transports. Auth/custom header injection, per-call header overrides, retry policy, cancellation tokens. Phase 7: SSE streaming. Phase 8 (WS): `stream: ws` → `_SscWsHandle` bidirectional subscriptions. Pagination: `paginated: true` → `<name>Paged(page, size, ...)` appending `?page=N&size=M` to URL on both JVM and JS targets. Extended Phase 5: `RouteDeriver` now covers `routes:` front-matter, `mount()` calls, and cross-file typed-handler analysis (`(input: T) => ...` → `requestType = "T"`); `Parser.parseFile` passes `baseDir` for handler lookup.
- **v1.45** — JVM Desktop Frontend: Swing-based desktop frontend; reactive View DSL; JvmUiRuntime; `ssc run --frontend swing`.
- **v1.44** — Full-Stack In-Process Transport: BackendTransport + same-process fetch dispatch; Swing/JVM frontend reaches backend without HTTP.
- **v1.43** — Electron JVM REST Backend: `ssc run --mode server` + Electron renderer; REST-over-localhost JVM backend for Electron apps.

## 2026-05-23

- **v1.42** — Native Platform P3: Electron Renderer — Electron shell + Node.js IPC bridge; `ssc run --frontend electron`.
- **v1.41** — Native Platform P2: Toolchain UX — native build CLI ergonomics; `ssc native` subcommand improvements.
- **v1.40** — Native Platform P2: Web Renderer Update — updated Electron-embedded web renderer; renderer protocol version bump.
- **v1.39** — Native Platform P1: IR Foundation — new IR nodes + codegen for native platform targets.

## 2026-05-21

- **v1.26** — `sql` fenced code blocks (JDBC): all 7 phases complete. `Lang.Sql` + `isSql`; dedicated `ir.Content.SqlBlock` IR node with bind-list; `backend-sql-runtime` module (H2 + SQLite bundled; Postgres/MySQL via `dep:`); `SectionRuntime.runSqlBlock` + interpreter + JvmGen codegen; `given Connection` override; `${expr}` → `?` bind parameters (safe-by-default, no SQL injection possible). `ssc check` rejects sql blocks on non-JVM backends with `UnsupportedJdbcUrl` diagnostic. 7 phases, conformance suite included.
- **v1.37** — Typer: `ssc check` 33→94 examples — typer fixes raising passing conformance suite from 33 to 94 examples.
- **v1.36** — Parser bugfix: `preprocessInlineImports` ordering — fixed parse-order regression in inline import preprocessing.
- **v1.35** — `run-jvm` artifact caching — incremental rebuild avoidance for JVM-target `.ssc` scripts.
- **v1.34** — REPL Debugger — interactive breakpoint + step-through debugger in the REPL.
- **v1.33** — Interpreter lazy loading Phase 2 — deferred plugin loading; faster cold start.
- **v1.32** — `runtime/std/pwa-plugin`: Progressive Web App support — service worker, manifest generation, offline mode.
- **v1.31** — `transaction` fenced block — database transaction scope (`transaction { … }`) as a language construct.
- **v1.30** — REPL web-aware mode + `mount()` intrinsic — `ssc repl --web`; `mount()` hot-replaces running server routes.
- **v1.29** — DAP Debugger (`ssc debug`) — Debug Adapter Protocol server; VS Code / IDE debugger integration.
- **v1.28** — Config System — `config.ssc` front-matter + `ssc.Config.*` typed accessor; environment overrides.

## 2026-05-20

- **v1.27** — Browser-side SQL (sql.js / DuckDB-Wasm): all 7 phases complete. `backend-sql-runtime-js` module; `SqlRuntimeJsEmit` preamble + registry-init; JsGen `sql` block codegen (→ async `sqlQuery` / `sqlExecute` calls); NodeBackend + WasmBackend wiring with `package.json` dep emit (sql.js / `@duckdb/duckdb-wasm`); `UnsupportedJdbcUrl` diagnostic for `jdbc:` URLs on browser targets; examples + conformance.
- **v1.25** — JavaScript / Node.js fenced code blocks — `js` and `node` fenced blocks executed natively in JS target; seamless JS interop from `.ssc`.
- **Spark backend** — v1.25 § 9.5 complete end-to-end: Phase A (SPI + local session), B.1 (`--spark-master`), B.2 (`ssc submit` fat JAR via `spark-submit`), C.1-C.3 (Spark SQL + DataFrames + typed readers + schema bridge), D (`@SqlFn` UDF bridge), E (Scala 3 native `Encoder` derivation via `Mirror`, Option/nested/collection fields), F (Kafka Streams backend + Streaming DSL), G (Hive metastore + `@TempView` + `Dataset.fromTable`), MLlib M.1-M.5 (dep auto-emit, Vector encoder, Pipeline, model save/load), Lakehouse L.1-L.2 (Delta Lake auto-detect + config). L.3 (Iceberg) + L.4 (Hudi) deferred — blocked on upstream Spark 4 artifacts not yet published. 204+ `SparkGenTest` cases.
- **Wallet SPI** — Scala.js cross-compile sprint: wallet interface + Scala.js cross-compiled runtime; browser + JVM wallet stubs.

## 2026-05-19

- **v1.24** — Language features: pattern matching extensions, string interpolation improvements, type inference fixes, sealed-trait enhancements.
- **v1.23** — Cluster management: membership view + events, Phi-accrual failure detection, Bully + Raft leader election, config distribution, rolling-restart drain, cluster metrics, external-coordinator adapter (etcd/Consul/ZooKeeper).
- **v1.22** — Distributed map-reduce: `Dataset[T]` API over v1.6 distributed actors; coordinator-dispatched partitions; shuffle; configurable failure handling.
- **v1.21** — Local map-reduce (`Dataset[T]`): lazy fluent API; sequential + parallel local execution; streaming via v1.10 generators.
- **v1.20** (all sub-versions) — DSL primitives + `runtime/std/parsing`: user-defined string interpolators, parser-combinator library, error recovery, indentation-aware parsing, multi-pass pipeline.
- **v1.19** — URL / dep imports: `[X](https://...)` URL fetch + `[X](dep:org/lib:1.2)` resolver with `ssc.lock` SHA-256 integrity.
- **v1.18** — `package` keyword + std layout migration: `package foo.bar` declarations; `runtime/std/*` reorganised under package hierarchy.
- **v1.17** — MCP support (client + server): full MCP 2025-03 + OAuth 2.1 + OIDC compliance; AS + RS + OIDC IdP; WebAuthn passkey grant; persistent stores; observability; CLI `ssc oauth` subcommand.
- **v1.13** — Final Tagless ergonomics: `using` auto-resolution, context bounds, cross-file trait inheritance with HKT, sealed-trait extension dispatch.
- **v1.9.x** — Actor internals refactor: mailbox + scheduler rewrite; reduced allocation on hot actor paths.
- **v0.12** — SSR + client hydration: Declarative Shadow DOM via `wc()` + zero-JS-rerender hydration guard.
- **v0.11** — i18n / l10n: `translations:` front-matter + `t(key)` / `setLocale(code)` intrinsics across all backends.
- **v2.0** (MVP) — Separate compilation: artifact format, `InterfaceExtractor`, `ArtifactIO`, `InterfaceScope`, `Linker` (FQN rewrite), `ModuleGraph`, six CLI commands. Full pipeline deferred; see [BACKLOG.md](BACKLOG.md#v20--separate-compilation-of-modules).

## 2026-05-18 and earlier

- **v1.16** — Restartable errors via algebraic effects: `perform`/`handle`/`resume` across all backends.
- **v1.15** — Checked errors via `throws`: dual-encoding (`throws` / `throwsRaw`), `attemptCatch`, `HasStackTrace`, platform-exception shims.
- **v1.14** — Metaprogramming MVP: `inline def`/`val`/`if`/`match`, `compiletime.summonInline`, `derives` recipes for Eq/Show/Hash/Order.
- **v1.11.5** — `Free[F, A]` as stdlib type: user-facing Free monad in `runtime/std/free.ssc`.
- **v1.11** — Continuation-based `Async`: rewrite on top of v1.9 coroutines; `Computation[A]` shim; ≥20% allocation reduction.
- **v1.10** — Generators: `flatMap`, `zip`, `zipWithIndex`; all three backends; streaming foundation.
- **v1.9** — Coroutine primitive: `Coroutine[A, B]`; interpreter + JvmGen + JsGen; 19 conformance tests.
- **v1.8.1** — Direct-syntax extensions: additional monad `do`-notation shapes; error-channel integration.
- **v1.8** — Direct-syntax do-notation: `for`/`yield` over arbitrary monads; all phases; conformance suite.
- **v1.7** — Plugin packaging & discovery: `.sscpkg` format, `pkg:` URI resolver, `ssc install`, local registry.
- **v1.6** — Actors (Erlang-style, WebSocket-distributed): local actors, supervision trees, distributed via WS; all backends.
- **v1.5** — Transport layer: TLS + HTTP/WS clients + streaming (Phases A–D′; NIO migration deferred).
- **v1.4** — Standard-library effects: `State`, `Reader`, `Writer`, `IO`, effect-system stdlib.
- **v1.3** — Runtime upgrades: real-thread Async (virtual threads), persistence layer, Async-integrated WS.
- **v1.2** — Auth follow-up: combined example + WebAuthn / passkeys.
- **v1.1** — Standard type-class hierarchy: `Functor`, `Applicative`, `Monad`, `Foldable`, `Traversable`, etc.
- **v1.0** — WebSocket production-readiness: Sprints 1–4, 6 (Sprint 5 deferred).
- **v0.10** — Extended component pack: Card, Switch, Alert, Tag, Stats, Empty, Toolbar, Tree, Stepper, Lightbox, FileUpload, DateInput/Picker, TimePicker, Combobox, RangeSlider, Carousel.
- **v0.9** — Standard component pack + Optics second pass: 8-tier UI component library; Index optic (`.index(i)` / `.at(key)`).
- **v0.8** — Web Components target: `ssc emit-wc`, `customElements.define`, Shadow DOM, hydration guard.
- **v0.6** — Optics: Lens / Prism / Optional / Traversal across all backends.
- **v0.5** — Interpreter performance Tier 1: dispatch-table rewrite; ~3× faster on typical workloads.
- **Backend SPI v0.1** (Stages 1–9.1) + followups: 9-module sbt layout, SPI traits, in-process + out-of-process plugins, intrinsic extraction (`std.http`, `std.ws`, `std.auth`, etc.), `ssc fmt`.
- **Scala ↔ ScalaScript interop** — Tiers 1 + 2: `@ssc` annotation → `.ssc` stub generation; Scala callers import generated stubs.
