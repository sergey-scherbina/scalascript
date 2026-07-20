# Backlog

Open and planned milestones — what still needs to be done.
Active pending work is in [SPRINT.md](SPRINT.md); ownership is authoritative only
through `.work/active/*.claim` on `origin/main`.
Completed work is in [CHANGELOG.md](CHANGELOG.md).

## Open work — what's left (2026-06-15)

This backlog was tidied 2026-06-15: completed milestones moved to `CHANGELOG.md` + git
history; only sections with open `[ ]` items remain below. The full detailed history of the
55 archived milestones is recoverable from git (`git log -p BACKLOG.md`).

Status hygiene (2026-06-23): open `[ ]` rows below are intentionally still open, but many are
explicitly `BLOCKED` or `DEFERRED` product/external-decision items. History-only / wontfix notes
are plain bullets without checkboxes so agents do not claim them as build work.

## `site-playground` — in-browser `.ssc` playground — BLOCKED on compiler recursion (2026-07-20)

A client-side playground on `scalascript.dev` (type `.ssc`, run it, no server). Wanted by
Sergiy 2026-07-20; **deferred with a named blocker**, not dropped.

**Why it is tractable in principle.** The whole `.ssc` pipeline is already "VM + text": the
compiler ships as *data* (`v2/lib/ssc1-front.ssc0` 154 KB, `v2/lib/ssc1-lower.ssc0` 258 KB) run
by the kernel VM (`v2/ssc1:8-9`). So porting **one** artifact — `v2/src` — puts the entire
frontend in a browser. Across its 6,355 lines the JVM coupling is ~a dozen lines:
`Runtime.scala:2913-2915` (three `java.nio.file` prims), `Ssc0.scala:252-258` (`java.io.File`
import resolution), `Main.scala:29,35` (CLI only); `PortableDecimal`/`PortableEffects` use
`java.math.BigDecimal`/`AtomicBoolean`, both in Scala.js's javalib. No reflection, no
`ServiceLoader`, no threads. The file prims would be replaced by a virtual FS over bundled
strings — which is what a playground wants anyway. `sbt-scalajs` is already in
`project/plugins.sbt:26-28` (27 JS-enabled projects, all domain modules — no compiler module).

**The blocker (this is what gates it).** `BUGS.md:1537` `coreir-compiler-unbounded-depth`:
`Compiler.valuePositionsNeedEffectThreading` / `FastCode.tryFC` recurse unbounded and overflow
**at ~depth 500 on a 1 MB stack**. Every launcher works around it with `-Xss512m`
(`v2/ssc:9`, `v2/ssc0c:8`, `v2/ssc1:8`). **Browser JS stacks are ~1 MB and not configurable —
there is no workaround on that side.** Status DEFERRED, unowned. `BUGS.md:1578` notes real IR
is shallow (depth 25), so a compiler-side depth bound has enormous headroom — but it is unwritten.

**Ordering — steps 1 and 2 are DONE (2026-07-20, `coreir-compiler-depth`):**
- [x] 1. ✓ `coreir-compiler-unbounded-depth` closed. Compile-time recursion is bounded
      (`Compiler.MaxDepth`, diagnostic instead of `StackOverflowError`) and the VM now runs on an
      explicitly sized thread (`Main.onSizedStack`) instead of an OS default that differs by platform.
- [x] 2. ✓ `v2/conformance/check.sh` is green: **644 ok / 0 FAIL, exit 0**.
- [ ] 3. **The remaining blocker, now precisely sized.** The step-1 cure for the *runtime* half is a
      bigger stack, and a browser grants ~1 MB with no way to raise it. Measured 2026-07-20: `ssc0c`
      compiling `examples/uselib.ssc0` needs **4 MB** — so the real gap is **4x**, not the 512x the
      old `-Xss512m` launcher flag implied, and not "unbounded". Two ways to close it:
      - (a) **Explicit continuation stack (CEK-style)** in the evaluator, so non-tail calls live on
        the heap. Fully browser-proof; the honest fix; a rewrite of the evaluator core.
        `evaluateRemainingAsStep` → `Runtime.value` (`Runtime.scala:1512`) is the site — ~3 frames per
        user-level call today.
      - (b) **Cut frames per level.** ✗ **MEASURED 2026-07-20 — wrong lever, do not start here.**
        See the measurement below: it buys a constant factor against a cost that grows *linearly
        with program size*.
      - (c) ⭐ **Make the self-hosted compiler's list traversals tail-recursive.** This is the real
        fix and it is in `.ssc0` source, not in the VM.

**The measurement that redirected this (2026-07-20).** Minimum stack for `ssc0c` to compile a file,
via binary search (`-Dssc.stackSize=0` to defeat the sized VM thread):

| Input | Min stack |
|---|---|
| `fact.ssc0` (6 lines) | 512k |
| `calc.ssc0` (25 lines) | 1m |
| `uselib.ssc0` (5 lines + 43-line import) | 4m |
| 10 / 40 / 160 / 320 **trivial defs, zero nesting** | 512k / 2m / 4m / 8m |

Stack is **linear in program size** — ~25 KB per top-level definition, ~40 KB per source line — and
320 definitions with *no nesting at all* already need 8 MB. So this was never a nesting-depth
problem. Meanwhile the VM's tail calls are perfect: **1,000,000 tail calls run in 256k**.

**Root cause, in the compiler's own source.** `lib/ssc0c.ssc0:368`:
```
def lowDefs = (defs, globals) => match defs { case Nil => Nil
  case Cons(d, t) => match d { case Pair(n, e) =>
    Cons(IrDef(n, low(Nil, globals, e)), lowDefs(t, globals)) } }
```
The recursive call sits in a **constructor argument**, so it is non-tail and lowering N definitions
costs N frames of the compiler's own recursion. `mapFst` (:329) has the same shape, and the shape
`Cons(x, recurse(t))` occurs **10 times** in the 371-line source. This matches the stack profile of
the real workload: `compileEffectAwareConstructor` + `evaluate$2` account for 15% of frames.

**Why (b) cannot rescue this.** Full stack profile at overflow (3844 frames, `-XX:MaxJavaStackTraceDepth=0`):
`If` closures 65.2%, `Let` body 11.2%, ctor-field evaluation 15.1%, trampoline 7.9%. Routing the
tail-position closure calls (`If` + `Let`) through a new trampoline `Step` would remove 76% — a
**4.25x** win — at the cost of a `Step` allocation per taken branch in the hottest path of a
heavily perf-tuned VM. Against a linear law, 4.25x moves a browser's ~1 MB budget from ~25 to ~100
definitions. That is a postponement, not a fix. Static `If`-chain flattening is cheaper (no
allocation) but only **2.0x** — the chains measured 6 and 9 deep.

**So (c) first.** Rewriting those 10 traversals with an accumulator + reverse turns O(program size)
stack into O(nesting), which the existing TCO already handles for free. Must be verified against the
byte-identical self-compilation fixpoint: the output IR must not change, only the stack profile.
Reconsider (b)/(a) only if a browser budget is still short *after* (c).
- [ ] 4. Scala.js cross-build of `v2/src` + virtual-FS shim + playground page.

**Two dead ends already ruled out — do not re-investigate.** (a) `./ssc0-js` self-hosting: the
tower JS backend emits a whole program as one expression with **no `#io.*` prim support**
(`v2/lib/backend-js-gen.ssc0:139-140`), while its own driver needs `#io.args()`/`#io.print`
(`v2/bin/ssc0-js.ssc0:8-9`) — the backend cannot compile its own driver. (b) Cross-compiling the
**v1** JVM compiler: 228 files under `v1/` use `java.io`/`java.nio`/`reflect`/`ServiceLoader`,
and the plugin architecture is ServiceLoader-based with no Scala.js equivalent.

Also note WASM does not shortcut this: tower WASM is WASI-via-Node (`v2/ssc0-wasm:20-25`,
needs `rustc`), and v1 `backendWasm` shells out to `scala-cli --js-emit-wasm`
(`specs/wasm-backend.md:3-5`) — i.e. it needs a server to produce browser output.

## `ci-crossbackend-differential-runtime` — full `sbt test` runs ~90+ min, cross-backend suites dominate (2026-07-20)

Raised by `ci-testtimeout`. The `Test via sbt` CI step needed its `timeout-minutes` bumped 90 → 150
because the full `sbt test` genuinely exceeds 90 min. From the run-29700272865 log, per-suite timing:
`CrossBackendPropertyTest` alone = **44.7 min**, top-10 suites = 70.8 min, total measured (incomplete,
killed at 90) = 86.6 min. The cost is subprocess compiles: the cross-backend differential suites
(`CrossBackendPropertyTest` and siblings, in `v1/runtime/backend/interpreter`) run generated programs
through `scala-cli run --server=false` (deliberate COLD per-program compile for concurrency isolation,
see the comment at `CrossBackendPropertyTest.scala:559`) and `node`.

- [ ] Bring the test phase back under ~60 min WITHOUT losing interp==JVM==JS coverage. Options to weigh:
  a warm/pooled scala-cli compiler shared across the suite's programs (must not reintroduce the
  concurrency collisions `--server=false` guards against); sharding `sbt test` into parallel CI steps;
  or `ParallelTestExecution` scoped to the differential suites with a bounded subprocess pool. Do NOT
  env-gate the suite out of CI (that would be a measurement-lies-green coverage regression).

## `scala` fences vs `scalascript` fences — a LATENT `Int`-width hole behind dead code (2026-07-17)

Raised by `int-width-conformance` W5, which asked: a file may hold both ` ```scalascript ` blocks
(ssc `Int` = 64) and ` ```scala ` blocks, which `README.md` calls *"Standard Scala 3 — no
ScalaScript extensions"* and which real `scalac` compiles with `Int` = **32**. If both run in one
file, does one word mean two things?

**MEASURED 2026-07-17 — today, NO. There is no divergence.** A `scala` fence behaves identically to
a `scalascript` fence on every lane (`runScalaFences: true`, `println(2147483647 + 1)`): v1 interp
`2147483648`/`2147483648`; v2 native `2147483648`/`2147483648`; v1 JVM codegen and v1 JS codegen
both `-2147483648`/`-2147483648`. **The width follows the BACKEND, not the fence tag.**

**Why — and here is the latent part.** `Lang.isParseable = isScalaScript || isStandardScala`, so a
`scala` block is parsed and executed by the **ScalaScript** toolchain, not by scalac. In
`JsGen.genModuleSegmented` the `isParseable` case is matched **before** the `isStandardScala` case,
which means the `isStandardScala` branch — the one that builds `JsGen.Segment.ScalaSource` and hands
it to `ScalaJsBackend.compileSourceToJs` — is **unreachable dead code**. Confirmed empirically:
emitted JS for both a mixed file and a `scala`-only file contains **0** Scala.js markers.
`ScalaJsBackend.compileSourceToJs` is **real** (it shells out to `scala-cli --power package --js`
⇒ real scalac ⇒ `Int` = 32) and is also referenced by `emit-spa` / `emit-wc` / `emit-wasm` through
the same unreachable segment.

**The hole is one case-reorder away.** If anyone "fixes" that dead branch so `scala` fences really
compile via Scala.js, the same fence text becomes `Int`=32 on the JS lane and `Int`=64 on the
interpreter — one word meaning two things, silently. Two docs already promise exactly that
behaviour: `README.md`'s block table (` ```scala ` → "interpreter · **Scala.js** (JS) · JVM") and
`SPEC.md` §3.3 ("JS backend compiles via Scala.js"). `Lang.scala`'s own comment is the honest one:
*"The JavaScript backend **will eventually** compile them via Scala.js; for now they are skipped…
The interpreter runs them using the same Scala 3 subset it already supports."*

- [ ] Decide the boundary and write it down, **before** anyone revives the branch: either (a) `scala`
      fences are explicitly *ScalaScript-subset* fences (rename the docs' "Standard Scala 3" claim,
      keep `Int`=64, delete or gate the dead Scala.js path), or (b) they are genuinely Scala 3 (then
      `Int`=32 inside them is a *declared* boundary that `specs/numeric-widths.md` §2 must carve out,
      and mixed files need a diagnostic). Today's docs describe (b) while the code implements (a).
      Not urgent — nothing diverges today — but it is a trap primed for the next person who reads
      `README.md` and "fixes" the unreachable branch.

## Residual risk from the codex-lane salvage — descriptor v3 Slice B parser markers (2026-07-16)

`feature/ssc-api-descriptor-v3-slice-b` landed as `cf14fb5b4` after an independent re-verification
(see the `codex-lane-salvage` SPRINT section for the observed numbers — every claimed gate reproduced;
nothing below is known-broken). It was the only one of the three salvaged branches to touch **shared
compiler core**, so its two structural decisions are recorded here rather than left implicit in a
5,636-line diff nobody else has read. Neither is a defect; both are things a future agent should know
about *before* being surprised by them.

- **`preprocessEffects` now injects marker types into every effect object.**
  `v1/lang/core/src/main/scala/scalascript/parser/Parser.scala` rewrites `effect Name:` into
  `object Name { private type __effectDecl__ = true; … }`, plus `private type
  __effectUnsupportedShape__ = true` for generic/`extends` effects. This is how declaration facts
  reach `PreBodyApiDescriptorProducer` (a consumer that only retains the *preprocessed* tree
  otherwise cannot tell an empty effect from an ordinary empty object). It is a string-injected
  marker in a desugaring, i.e. it touches **every `.ssc` program that declares an effect**, on every
  lane. Verified inert: `core/test` 1132/1132, and the forced (`--no-memo`) effect conformance slice
  9/9 across INT/JS/JVM against a freshly built jar. Full-corpus is CI's radius, per AGENTS.md §4b.
- **The effect-line `extends` regex widened from `\S+` to `[^:]+`** (same file, `effectLinePat`).
  Behaviour change worth knowing: `effect Foo extends Bar with Baz:` previously did **not** match
  (`\S+` stops at `Bar`, so `\s*:` failed) and fell through to the Scala parser un-rewritten; it now
  matches, is rewritten, and is flagged unsupported-shape so the producer fails closed. Strictly more
  cases are handled — but if an effect-declaration parse ever behaves unexpectedly around `extends`,
  this is the commit to look at first.
- **`core` now `dependsOn(v2InteropDescriptor)`** (`build.sbt`), i.e. the v1 lang core takes a
  compile dependency on a v2 interop module. Deliberate (the producer lives in `core` and emits v2
  descriptor types) and the whole graph builds + `installBin` stages cleanly, but it is a new v1→v2
  coupling direction — relevant to anyone untangling the v1/v2 module graph.

## Saved-continuation optional policies (2026-07-14)

The base contract is deliberately small: a reusable `SavedContinuation` is a typed opaque
envelope around a closed portable CoreIR capsule (or a target/toolchain-pinned exact artifact for
managed host code), and every explicit `run` starts a fresh suffix execution without replaying
the prefix. JS/TS, Rust, Swift, and JVM host/runners plus the WASM/WASI runner are mandatory
SPRINT milestones, not optional tail work. These policy additions are useful but are not
prerequisites for base save/run:

- [ ] **saved-continuation-once-policy** — add an explicitly selected one-shot workflow mode
      with a linearizable cross-machine claim. The mode must be chosen before any reusable run
      (or use a distinct saved type); crash after claim is terminal `Unknown`, and the guarantee
      is at-most-one body start, never exactly-once external effects.
- [ ] **saved-continuation-version-migration** — opt-in, audited migrations between compatible
      CoreIR/control ABI, frame, codec, and plugin versions. Base behavior rejects a mismatch and
      retains/loads the exact artifact when the payload is not a fully portable capsule.
- [ ] **saved-continuation-durable-state-graph** — extend the baseline immutable/codec-safe
      captured graph with richer explicit alias-preserving codecs for selected cyclic mutable
      state. The base `DurableRef` contract remains available for explicit resolvers; neither
      mechanism infers serialization of arbitrary host objects or live resources.
- [ ] **saved-continuation-effect-delivery** — optional idempotency keys, outcome persistence,
      transactional outbox/inbox, and effect journals for applications that need stronger delivery
      protocols. These remain application/runtime protocols, not a claim that continuation resume
      itself makes external effects exactly once.
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

## Standard-tier compiler correctness (2026-07-13)

- [ ] **standard-tier-named-arg-skip-default** — `bin/ssc run` (default, and
      `--v2` explicitly) — the self-hosted "standard tier" pipeline, a
      *different* codebase area from `v1/runtime/backend/interpreter` — mis-
      binds a named argument to the FIRST defaulted parameter instead of the
      actual named one, whenever a call names a trailing defaulted param
      other than the first while skipping an earlier one (e.g. `f(a, c =
      "C1")` where `b`/`c`/`d` all default — binds `C1` to `b`, not `c`).
      Silent wrong value, not a crash. Verified NOT to affect `bin/ssc-tools
      run` (v1), `bin/ssc-tools run --v2`, or `bin/ssc-tools emit-js` — i.e.
      not this repo's own `tests/conformance/run.sh` / `StdUiSmokeTest.scala`
      harness. See `BUGS.md` § `standard-tier-named-arg-skip-default` for the
      full repro/lane matrix. Found 2026-07-13 building `std-ui-select`
      (`specs/std-ui-select.md`); worked around there (examples/docs always
      name every trailing param from the first one overridden onward) but
      not fixed — likely bites any `.ssc` author who calls a multi-default
      function/constructor the natural way via plain `bin/ssc run`. Worth a
      dedicated fix + regression test given how common "skip the middle
      default" call shapes are, and given the standard tier is the
      forward-looking default (no `--v1` fallback exists on `bin/ssc`
      itself).

## Custom-backend (StaticJsEmitter) correctness (2026-07-13)

- [ ] **custom-jsemitter-signal-list-literal** — `frontend/custom/StaticJsEmitter.scala`'s
      `jsLiteral` (`registerSignal`, called from `compileEventHandler`) has no
      `List`/`Seq` (or `InstanceV`) case — only bare scalars. Any program
      where a `Signal[List[_]]` (of scalars or case-class instances) is
      referenced by an event handler crashes `ssc run` (both the default
      v2-VM/`custom`-frontend path and `--v1`) at startup, before serving
      anything. Not new — already affects the previously-shipped
      `examples/frontend/keyed-for-demo/keyed-for-demo.ssc` the same way
      (its own docstring's `ssc run` instructions are currently stale).
      `emit-js`/`emit-spa` (the production static-compile pipeline) are
      unaffected. Found 2026-07-13 building `select-from-signal`
      (`specs/std-ui-select.md` § "Reactive options (selectFrom)"); not
      fixed there — see `BUGS.md` § `custom-jsemitter-signal-list-literal`
      for the full repro. A real fix means teaching `jsLiteral` to
      recursively encode `List`/`InstanceV`/`Map` values.

## Swift backend hardening (2026-07-13)

- [ ] **v2-swift-machine-deep-nontail-stack** — `Machine.evaluate`/`runTerm`/
      `value` (`v2/backend/swift/.../SwiftRuntime.scala`'s embedded Swift
      source) recurse on native Swift call frames per non-tail Prim/App
      argument. A single Term nested >~1300-1500 levels deep in one non-tail
      chain (e.g. `(i.add 1 (i.add 1 (i.add 1 ...)))`) genuinely stack-
      overflows at runtime (SIGSEGV; confirmed via a real macOS crash report,
      "Thread stack size exceeded due to excessive recursion"). Found
      2026-07-13 while picking a safe depth for the
      `v2-swift-coreir-sexpr-embed` regression test — previously unreachable
      because the OLD codegen (whole Program as one nested Swift literal)
      could never even COMPILE a term this deep (hit Swift's 256
      structure-nesting compile-time limit first, well before runtime).
      Real business logic essentially never nests one non-tail expression
      chain this deep, so this is not believed to block busi's real
      `app.ssc` — not urgent. Eventual fix needs the evaluator to stop
      relying on the native call stack for non-tail argument evaluation
      (an explicit heap-allocated work stack or CPS transform), mirroring
      how the JVM/JS backends presumably already handle (or bound) this.

## UniML conformance hardening (2026-07-12)

- [ ] **uniml-yaml-m31-full-grammar** — extend the safe M3 YAML 1.2.2 profile through the remaining
      grammar/lexical productions: multiline single/double-quoted folding and continuation escapes,
      every printable/noncharacter restriction, `%TAG` handle expansion/validation, indentationless
      sequences after property-only nodes, additional complex-key forms, and strict block indentation
      recovery. Grow the pinned `yaml/yaml-test-suite` `data-2022-01-17` subset beyond the eight M3
      cases and keep JVM/Scala.js behavior identical. This is explicitly deferred from M3 rather than
      silently counted as compatibility already delivered.

- [x] **uniml-markdown-m41-conformance** — ✓ Landed (2026-07-13). Lazy paragraph continuation,
      tight/loose list classification and the full CommonMark HTML-block type table (1–7 with correct
      start/end conditions) all implemented and tested (leaf 30/30 JVM+JS); the example corpus was
      grown (curated 34 + ~70 adversarial edge cases). Remaining tail (multi-line inline spans across
      a continuation marker; deep/mixed container nesting) is tracked in `uniml-markdown-m41-tail`.
- [x] **uniml-markdown-m41-tail** — ✓ Landed (2026-07-13). Paragraphs are buffered as per-line
      segments so continuation markers (`> `, list indents) are emitted as trivia at their source
      position instead of leaking into inline text; multi-line emphasis/links now resolve cleanly
      across a marker. Deep/mixed container nesting (nested quotes/lists, quote-in-list, list-in-quote,
      lazy continuation into a nested quote) is correct. Leaf 32/32 JVM+JS. UniML Markdown M4 + all
      M4.1 follow-ups are now complete; only the exotic HTML5-only entity long-tail remains deferred.
- [x] **uniml-markdown-m41-doccontent-bridge** — ✓ Landed (2026-07-13). `unimlMarkdownBridge`
      (JVM-only, depends on `core` + the Markdown leaf) projects a compatible `MarkdownDocument` into
      `DocumentContent` (headings→sections, paragraphs/lists/images/tables→content blocks,
      fences→embedded), differential-tested against `Parser.buildDocumentContent`, reporting model
      loss for block quotes, thematic breaks, raw HTML, definitions, hard/soft-break distinction,
      task state, inline images and strikethrough. 11 tests green. The leaf does not depend on it.
- [x] **uniml-markdown-m41-entities** — ✓ Landed (2026-07-13). Expanded `MarkdownProjection`'s
      named-entity table to the full HTML4/XHTML set (~250: Latin-1 generated from its contiguous
      block, plus Greek/punctuation/arrow/math). Numeric decode + unknown-stays-literal unchanged.
      Remaining exotic HTML5-only names (obscure math/legacy no-semicolon forms) still deferred.

## SclJet interoperability follow-ups (2026-07-12)

- [~] **scljet-standalone-library** — DONE 2026-07-13 via a compatibility symlink;
      resolver-native decoupling remains. Spec: `specs/scljet-standalone-library.md`.
      SclJet source now lives at the repo-root **`scljet/`** (standalone, not under
      `v1/`); `v1/runtime/std/scljet` is a symlink to it, so `installBin`'s glob and
      every `std/`-import resolver (interpreter `ImportResolver`, native/JS loaders)
      find it unchanged. Verified: `scljet-*` 11/11 on `[int, js]`, native `ssc run`,
      and a non-scljet std case still green. REMAINING polish: drop the symlink by
      teaching the resolvers a first-class library root — build.sbt `installBin`
      (stage from `scljet/` directly), `ImportResolver`
      (`v1/lang/core/.../imports/ImportResolver.scala`), the native/JS +
      `check-stdlib-interface-load` loaders (`Main.scala`) — all mapping `std/scljet`
      → `scljet/`. Needs FULL conformance + native + JS verification.


- [ ] **scljet-m3-write-followups** — edge cases beyond the m3b–m3d write path
      (`scljet/write.ssc`). `SqlReal` record encoding is DONE
      (2026-07-13): `encodeReal` decomposes a Double into IEEE-754 binary64 by
      normalizing to `[1,2)` (exact powers-of-two arithmetic) — byte-exact vs
      `struct.pack('>d')` for 1.5/-2.5/3.14159/0/100/0.1/1e20/-0.001, reads back
      through a real DB, int/VM/ASM/fallback/JS (subnormals/non-finite out of
      scope). Cell-overflow (single-leaf) is DONE (2026-07-13):
      `buildOverflowTableDatabase` keeps each cell's local portion on the leaf
      (SQLite `localPayloadBytes` formula) and spills the remainder onto a chain
      of `[u32 next][content]` overflow pages — byte-exact vs reference SQLite,
      `integrity_check` ok, reads back exact, int==js (conformance
      `scljet-write-overflow`). Multi-leaf overflow is DONE too (2026-07-13):
      `buildOverflowBtreeDatabase` packs rows into leaves (and a table-interior
      root) like `buildTableDatabase` while spilling overflowing cells onto chains
      appended after the leaves — a two-pass build (probe to fix the leaf count,
      then number the overflow pages from `3+L`); byte-exact vs reference SQLite,
      byte-identical to `buildTableDatabase` for non-overflow input, int==js
      (conformance `scljet-write-btree-overflow`). Arbitrary-depth (3+ level)
      trees are DONE too (2026-07-13): `buildDeepTableDatabase` stacks interior
      levels bottom-up until a single-page root, numbering pages top-down so each
      node's children sit in a known contiguous range — verified on a real
      3-level tree (80 pages, `integrity_check` ok, depth 3, all rows exact),
      byte-identical to `buildTableDatabase` for a two-level tree, int==js
      (conformance `scljet-write-deep-btree`, fingerprinted since the file exceeds
      the byte-list size). This required making the page assembly iterative
      (`cellsFlatten`/`buildLeafPages`) — see the byteslice-zeros item below.
      Deep + overflow together is DONE too (2026-07-13):
      `buildDeepOverflowTableDatabase` builds a tree of any depth whose oversized
      rows also spill onto chains numbered after the whole tree (two-pass: fix the
      tree shape from placeholder cells, then number overflow from `2+T`) —
      verified on an 88-page 3-level tree with overflow chains (integrity_check ok,
      depth 3, all rows exact), int==js (conformance `scljet-write-deep-overflow`).
      The bulk-build write matrix (single/multi-table × small/overflow ×
      2-level/deep) is now complete. The explicit-rowid writer `buildKeyedDatabase`
      is DONE too (2026-07-13): rows carry their OWN (strictly ascending, gapped)
      rowids — preserved across a rewrite instead of renumbered 1..n — composing
      with overflow and any depth; verified rowids `[10,25,100,500,1000]` read back
      exact incl. a 1016-char overflow row, int==js (conformance
      `scljet-write-keyed`). This is the write-side foundation for m3f. Row DELETE
      is DONE (2026-07-13): `mutate.ssc` `deleteRowids`/`keepRowids` open the DB
      read-only over its own bytes (`ImageVfs`), read each surviving row as its raw
      record payload (`reconstructRecordBytes` from the reader's `DecodedRecord` —
      no value/text round-trip), and rebuild via `buildFromRawSchema` preserving
      the raw `sqlite_schema` record and original rowids. Verified vs reference
      `integrity_check` incl. an overflow row, int==js (conformance
      `scljet-mutate-delete`). Row UPDATE is DONE too (2026-07-13):
      `updateRowValues` re-encodes ONLY the changed row from a caller-supplied
      `List[SqliteValue]` (the new value is given, not decoded — so NO
      code-point→String needed; my earlier note here was wrong) and passes the
      rest through as raw records; verified updating a row to a 1016-char overflow
      value, int==js (conformance `scljet-mutate-update`). Row INSERT is DONE too
      (2026-07-14): `insertRow` adds a row at an explicit rowid kept in ascending
      order (`insertSorted`; errors on a duplicate rowid), existing rows pass
      through raw; verified middle-insert, append, duplicate rejection, and an
      inserted 1016-char overflow value, int==js (conformance
      `scljet-mutate-insert`). `mutate.ssc` now does the full row-level CRUD
      (insert/delete/keep/update) on an existing DB. Multi-table WRITE is DONE
      (2026-07-14): `buildMultiTableDatabase` lays out several rowid tables in one
      file — page 1 = `sqlite_schema` with a CREATE TABLE entry per table (each with
      its own root page), then each table's B-tree in declaration order (interiors
      built root-page-relative via `buildTableTreeAt`/`buildInteriorLevels`);
      verified 3 tables incl. a multi-leaf table at a non-page-2 root, all read back
      exact, int==js (conformance `scljet-write-multitable`). Multi-table MUTATE is
      DONE too (2026-07-14): `mutate.ssc` `deleteRowidsInTable`/`updateRowInTable`
      (+ `readAllTables`) read every table (raw schema record + raw rows), modify
      the one at a given index, and rebuild via write.ssc `buildMultiTableRaw` —
      which reassigns root pages and re-encodes each schema record's rootpage field
      (`patchSchemaRootpage`, keeping name/tbl_name/sql byte-for-byte, so no text is
      decoded to a String). Verified deleting/updating one table of three, others
      preserved, int==js (conformance `scljet-multitable-mutate`). Index WRITE is
      DONE (2026-07-14): `buildTableWithIndexDatabase` writes a rowid table plus a
      single-leaf index B-tree (page kind 10) of `(column, rowid)` records sorted by
      `(value, rowid)` — reference `integrity_check` cross-validates the index
      against the table AND the query planner uses it (`SEARCH t USING INDEX idx`),
      int==js (conformance `scljet-write-index`). Text-column index keys work too
      (2026-07-14): `compareKeys`/`valueClass` sort by SQLite storage class then
      numeric / BINARY-text order (String `<`, ASCII/BMP-exact), so an index on a
      TEXT column validates and the planner uses it (conformance
      `scljet-write-index-text`). Multi-column (composite) index keys work too
      (2026-07-14): `buildTableWithIndexDatabase` takes `keyColumns: List[Int]`,
      records are `[keycols…, rowid]` sorted lexicographically (`compareKeyList`);
      a two-column index validates and the planner uses it for `a=? AND b=?`
      (conformance `scljet-write-index-composite`; single-column via `List(col)` is
      byte-identical). Multi-leaf indexes work too (2026-07-14): when the entries
      exceed one leaf, `packIndexTree` packs them into leaves with a PROMOTED
      separator entry between each pair (the separator lives in the interior, not a
      leaf — unlike a table interior which copies a rowid), and `buildIndexTree`
      builds an index-interior root (page kind 2) over the leaves
      (`buildInteriorPageKind`/`indexInteriorCell`); verified a 100-row two-leaf
      index — reference integrity_check cross-validates it and the planner uses it
      (conformance `scljet-write-index-multileaf`); single-leaf stays byte-identical.
      Index maintenance on mutate is DONE (2026-07-14): `mutate.ssc`
      `deleteRowidsIndexed`/`updateRowIndexed` (via `rebuildIndexed` + write.ssc
      `buildTableWithIndexRaw`) read a table+index DB's rows, apply the edit, and
      rebuild BOTH the table and the index from the surviving rows so the index
      never goes stale — reference `integrity_check`'s index cross-check passes
      after delete AND update; the caller supplies the key columns
      (conformance `scljet-index-mutate`). TEXT-key index maintenance works too
      (2026-07-14): `fieldToValue` rebuilds a text key from its code points
      (`codepointsToString` via `Int.toChar` — which works now, contrary to the old
      note), so a TEXT index stays consistent on delete/update (conformance
      `scljet-index-mutate-text`). The m3e CORE — transactional in-place
      page write — is DONE (2026-07-14): `journal.ssc` `writePagesJournaled` journals
      the pre-images of the pages about to change, overwrites them in place, and
      returns the mutated database + rollback journal; `applyRollbackJournal` undoes
      it, so a crash before commit is recoverable (conformance
      `scljet-journal-write`, verified differs+restores int==js). Remaining: 3+-level
      indexes, and a full mutable pager (dirty-page tracking, transactions,
      cell-level in-place edits) built ON TOP of `writePagesJournaled`. (JIT codegen
      bug found on the way — BUGS.md `interp-jit-nested-match-duplicate-var`.)

- [x] **scljet-byteslice-zeros-js-recursion** — DONE 2026-07-13. The core list
      helpers in `scljet/bytes.ssc` were made iterative (`while`+`var`, not linear
      recursion): `zerosList` (`ByteSlice.zeros`), `validateBytes`/`buildChunks`
      (`ByteSlice.fromList`), and `collectBytes` (`byteSliceToList`). The v1
      interpreter TCO'd these, but the JS backend does not, so they overflowed
      node's stack for large byte lists (`RangeError: Maximum call stack size
      exceeded`) — blocking full-size empty-DB writes and any 3+-level / large
      table on JS. Now a 40 KB three-level DB builds and round-trips identically on
      `[int, js]` (conformance `scljet-write-deep-btree`), and all 14 scljet cases
      stay green `--no-memo` on both backends. NB the interpreter-side var-scope
      leak (BUGS.md `interp-var-scope-leak-across-calls`) means the new iterative
      helpers use uniquely-prefixed var names.

- [ ] **scljet-m2d-hardening-overflow-traversal** — the last M2d corpus hardening
      item after `scljet-m2d-hardening` slices 1-2 landed (2026-07-13). Index-btree
      payload thresholds (`index-overflow-thresholds.db`) and deep page-1
      record/freeblock/schema corruptions are done; what remains is **user-table
      overflow-chain traversal corruption** — truncated or looped overflow chains on
      non-schema pages (e.g. corrupt the `next` pointer inside an overflow page of
      `overflow-thresholds.db`). The current `scljet-corrupt-check` tool only runs
      `openReadonly` (header + pager + page-1 schema validation), which never
      traverses user tables, so these cannot be caught there. Add a traversal-based
      negative check: run the corpus dumper (which already prints `ERROR:<msg>` on a
      `Left`) over the corrupt file and assert the expected overflow diagnostic
      (`overflow chain ended early or points out of range`, `overflow chain contains
      a cycle`, `overflow page is truncated`). Reproducible with the byte-exact SQLite
      3.53.3; extend the byte-mutation path so a full regeneration reproduces it.

- [ ] **scljet-portable-text-projection** — specify and implement a general
      target-neutral `code points/UTF-16 units -> String` construction API, then
      project SclJet `DecodedText` to `SqlText` without a host/JSON decoder.
      Current real-harness repro is in `BUGS.md`: v1 lacks `Int.toChar`, while
      v2 renders dynamic chars as decimal numbers. Keep raw encoded bytes as the
      SQLite GIGO source of truth and prove interpreter/VM/ASM/JS parity before
      the M4 value API depends on this projection.

- [x] **scljet-js-m1-parity** — DONE 2026-07-13. All 6 scljet conformance cases
      now pass `[JS]` and are declared `backends: [int, js]` (CI-locks the parity).
      Two findings: (1) the byte-codec/page-record/memory-VFS/cursor "diverges on
      JS" reports were all **stale-binary artifacts** — the fixes had landed in
      `70dfb5a1f` and later (always rebuild `installBin` before re-checking JS
      codegen). (2) `scljet-readonly-pager-btree` exposed a real JsGen bug —
      case-class body methods **with parameters** were dropped (only zero-param
      registered), so `_dispatch(vfs, 'fullPath', …)` threw
      `Method not found: fullPath on FixtureVfs`; fixed in JsGen (`BUGS.md`
      `js-caseclass-body-method-params-dropped`). Remaining scljet JS work is only
      the v2 self-hosted path's `__mk_method_obj__` import primitive (`BUGS.md`
      `v2-js-imported-method-object-primitive`) — tracked with the v2 work.

- [ ] **scljet-same-jvm-reference-lock-bridge** — before SclJet may replace the
      existing `sqlite:` provider, make SclJet locks conflict with an official
      native SQLite/Xerial connection running in the same JVM. POSIX record
      locks are process-owned, so `FileChannel` plus the SclJet-local canonical-
      path coordinator only covers SclJet↔SclJet in-process and reference SQLite
      across processes. Evaluate a small lock-broker process first; a native
      bridge into SQLite's per-process inode lock table is the alternative.
      Done when rollback and WAL contention tests mix both implementations in
      one JVM without unsafe simultaneous writers.

## ScalaScript 2.1 native provider parity follow-ups (2026-07-10)

TI-5's representative Scalameta-free boundary is complete; these full-surface
parity slices are intentionally non-blocking for the artifact/packaging cutover:

- [ ] **v21-native-http-advanced** — native middleware/CORS/gzip, TLS,
      streaming responses, SSE, uploads, WebSockets, and static UI serving;
      replace each current bounded unavailable diagnostic with a tested host
      hook, never a compatibility fallback.
- [ ] **v21-native-sql-advanced** — typed `Db.insert/update`, PostgreSQL
      LISTEN/NOTIFY, and native lowering of fenced `sql`/`transaction` blocks.
- [ ] **v21-native-ui-advanced** — framework SPA generation, `serve(view)`,
      keyed/fetch/data-table actions, storage/WebAuthn, and desktop/mobile
      renderers without `frontendCore`.
- [ ] **v21-native-effects-remaining** — Random, Clock, Env, Retry, and Cache
      providers over `NativePluginContext.withEffect`, without v1
      `BlockForm`/`SpiValue` adapters. Logger, State, Stream, and Async are now
      core-free standard providers.
- [ ] **v21-native-generator-dataset-bridge** — define a provider-neutral
      factory/pull contract so `Dataset.fromGenerator` and `Dataset.toGenerator`
      compose without either provider depending on the other's implementation.
      Until then both directions must remain bounded explicit errors, never a
      compatibility value or transparent fallback.
- [ ] **v21-native-actors-advanced** — add provider-owned network transport,
      discovery/cluster membership, links/monitors, supervision trees, durable
      mailboxes, and timer APIs on top of the core-free local actor contract.
      Keep these surfaces explicit until implemented; never route the standard
      launcher through the v1 actor scheduler or compatibility bridge.
- [ ] **v21-native-distributed-advanced** — add explicit provider-owned remote
      workers, network transport, discovery/membership, failure detection,
      retry/partial-result semantics, durable queues, and deployed named-handler
      agreement on top of the deterministic local-loopback MapReduce contract.
      Never serialize closures or route the standard launcher through the v1
      actor scheduler/compatibility bridge.

## ssc-toolkit-v2 P2 follow-ups (2026-07-07) — see `specs/ssc-toolkit-v2.md`

Queued behind the SPRINT tkv2-* slices (P0/P1). Requirements source: busi
`src/v2/specs/frontend-on-scalascript.md`.

- **tkv2-dev-loop** — ✓ Already satisfied / verified (2026-07-10):
      `ssc serve file.ssc` dispatches to `watch`; server-mode watch starts the
      port once and headlessly reloads the route table on saves; `watch-bench`
      measures the same reload path on a temp copy. Verification gates:
      CLI focused tests 11/11 (watch-cycle p50 5ms / max 8ms), `installBin`,
      real `bin/ssc watch-bench --cycles 2 --target-ms 1000 --require-target
      examples/rest-api.ssc` server-mode smoke (warm 433ms, hot max 42ms),
      and `tkv2-*` conformance 11/11.
- **tkv2-tri-state** — ✓ Landed (2026-07-10, `10273703c`): loading/empty/error
      helper for fetched views (busi P2-10), scoped as pure `.ssc`
      `std.ui.state` helpers over existing signals.
- **tkv2-raw-html** — ✓ Landed (2026-07-10, `bb5342f08`):
      `rawHtml(html: String): TkNode` now injects trusted raw markup through a
      toolkit-owned sentinel handled by the custom SPA runtime and SSR; `rawText`
      remains escaped text. Static `std/ui` SPA modules also force the Signals/UI
      runtime so toolkit primitives are present even without explicit
      `signal(...)` calls.
- **tkv2-spa-i18n-parity** — ✓ Landed (2026-07-10, `7e5d55e4f`):
      custom emitted SPA now respects the collision-renamed
      `std.ui.primitives.serve` import (`serve__ssc`) instead of dispatching a
      bare `serve` intrinsic, and the i18n demo live-switches EN/RU/UK/PL/EN in
      jsdom over the production custom browser runtime.

## v1→v2 migration follow-ups (2026-07-03)

- [ ] **v2-imported-receiver-methods-not-linked** (2026-07-12) — native
      self-hosted imports lose extension receiver shape (`row []`) and emit
      `Stub` for real case-class method bodies. Add a multi-file VM/ASM
      regression and preserve/link both receiver operation forms.

- [x] **v1-explicit-companion-shadows-case-constructor** — DONE (git): Defn.Object preserves the ctor as `apply`; Defn.Class merges ctor into an existing companion. Order-independent. Conformance companion-case-class-order.
      interpreter sometimes resolves `CaseClass(...)` to an explicit companion
      value in later imported functions/methods. Reproduce cross-module and make
      generated constructor dispatch independent of declaration order.

- [x] **v1-args-native-method-gap** — DONE (git): dispatch auto-calls a parameterless
      plugin-native receiver + re-dispatches (gated on pluginNativeNames). Verified
      args.length / cwd.startsWith. Bare-value position (println(args)) still open (separate).

- **v2-arith-unification** (2026-07-08) — ✓ Landed (2026-07-09,
      `a2985d911`): TWO diverged arith implementations:
      `Prims.arithOp` (full: Op-lifting, Map+(k->v), char comparisons, Cons-minus) used
      when the op name is a LITERAL, vs the resolve-table `__arith__` entry (weaker,
      string-concat fallback) for non-literal names. The busi litdoc bug was exactly this
      divergence (BUGS.md v2-arith-table-divergence). Map+Tuple2 was patched into the
      table; the honest fix is delegation (table entry → Prims.arithOp) after auditing
      the table-only cases (actor `!`, BigDecimal) into arithOp. Same lesson as T5.4:
      "a fast path stricter than the general table silently diverges".
      `resolve("__arith__")` is now a thin delegate to `Prims.arithOp`; focused
      non-literal CoreIR regressions cover Map+Tuple2, char-code comparisons,
      Decimal, actor-send, and unknown declaration fallback behavior.

- [x] **v1-jvm-state-threaded-handler-codegen** — DONE (2026-07-12, opus, see git):
      run-jvm now compiles + runs the deep-handler state-threading idiom (3 layers of
      Any-typing fixed — lambda param types + Any-value-as-function casts at
      `resume(())(x)` and `threaded(0)`). Conformance `effect-deep-handler-state`
      PASS on INT/JS/JVM; effects/async/actor/generator suites green.

- [x] **v2-ssc1c-globals-bug** — ✓ Landed (2026-07-05). Root cause: `lowerE`'s
      expression-position `"assign"` case missed `@@name` LongCell vars → bogus
      `(global @count)`. Fixed in `v2/lib/ssc1-lower.ssc0`; bool-predicate +
      mutual-recursion now correct on VM/JVM/JS/Rust. See SPRINT T5.1 and
      `v2/backend/check.sh` (new parity harness).
- [x] **v2-float-cell-fastpath** — INVESTIGATED + CLOSED 2026-07-05 (probe before build):
      a 3M-iteration float-accumulation loop already runs at **11 ns/iter** (33 ms/op)
      through the existing Float-safe FC lane (`tryFCValue`/`arithOp`) — the T3.2b
      FC-dispatch floor. A dcell/FDC tier (kernel prim + ssc1c lowering + 3 generators)
      would buy at most 2–3× on synthetic micros; pattern-match-heavy — the original
      motivation — is closure/match-dispatch bound and would NOT move. Not worth the
      cross-cutting churn; the real lever remains a v2 JIT backend (T3.2b conclusion).
- [x] **v2-rust-backend-tco** — ✓ Landed (2026-07-05). Step-trampoline port from the
      ssc0-level backend: `Step::Val|Bounce`, `call_fn` loop, `genTail` emitter for tail
      positions. Stack back to 256MB; tco.coreir (1M tail calls) PROVEN at a 1MB stack.
      Parity 8×3 GREEN; 4 corpus programs byte-match the VM.
- [x] **v2-js-backend-smallint-fastmode** — ✓ Landed (2026-07-05) as an opt-in flag:
      `--ints=number` on the JS generator (plain JS numbers; arith-loop ~6×, fib ~3×
      faster in node). Default stays exact BigInt — number mode is wrong for 64-bit
      wrap-around programs (bool-predicate 6≠243, demonstrated). A future typed-IR
      selective lowering could pick the mode per-value automatically.
- **v2-jvm-backend-echo-macos** — ✓ Landed (2026-07-10, `a4f7662be`):
      verified that `v2/backend/check.sh` already uses direct redirects for
      generated JVM/JS/Rust sources, then fixed the remaining live helper
      hazards by replacing source/IR `echo "$..."` pipes in `v2/scripts/bench.sh`
      and `v2/ssc1` with `printf '%s\n'`. The same verification found and fixed
      stale Scala CLI `-J-Xss512m` usage in `v2/ssc`, `v2/ssc0c`, and `v2/ssc1`
      by switching to `--java-opt=-Xss512m`. Gates: backend source smoke
      (`fact` x JVM/JS/Rust), wrapper smokes, `installBin`, `litdoc`
      conformance 1/1, and `git diff --check`.
- **v2-backend-check-ssc1c-wrapper-app-lit** (2026-07-09) — ✓ Landed
      (2026-07-09, `043039b61`): `v2/backend/check.sh bool` and
      `v2/backend/check.sh mutual-recursion` are restored as source-backend
      parity gates. Root cause: `indent2braces.py` converted
      `while i < 1000 do` to unparenthesized `while i < 1000 { ... }`, while
      ssc1c expects `while (cond) body`, producing app-lit CoreIR. The converter
      now emits parenthesized while conditions; backend `bool`, `mutual-recursion`,
      `tco`, `letrec`, and affected conformance are green.
- [x] **v2-litdoc-js-jvm-backend-lanes** ✓ Landed 2026-07-09 (`782f07438`) —
      `tests/conformance/litdoc.ssc` now runs across INT/JS/JVM. Landed fixes:
      JS runtime-colliding top-level user `val`/`var` bindings are renamed,
      JS `String.split` uses regex semantics, JVM omits the `doc` helper when
      user code owns top-level `doc`, and JVM no-arg `.mkString()` rewrites to
      parameterless Scala `.mkString`.
- [x] **v2-backend-performance-harness** — ✓ Landed (2026-07-09) in
      `01d9abf32`/`677969e1a`: `scripts/bench v2-backends [workload]` and
      `./bench.sh --v2-backends ...` now time the same corpus rows through v2
      VM, v2 JVM source backend, and v2 Rust source backend. The harness closed
      the measurement gap only; it did not close the Phase-3 backend performance
      thresholds.
- [x] **v2-source-backend-production-perf-gates** — ✓ Landed (2026-07-09,
      `1e7598394` closing slice): use the new
      `scripts/bench v2-backends` baseline to close the Phase-3 v2 JVM/Rust
      source backend performance gates. Current bounded local numbers are
      mixed: `v2-jvm` is excellent on `arith-loop` but slow on
      `recursion-fib`, while `v2-rust` is slow on all four probe rows
      (`arith-loop` 65.9 ms, `pattern-match-heavy` 304.2 ms,
      `recursion-fib` 221.2 ms, `recursion-tco` 12.1 ms). Scope the next
      slice to one backend/workload family at a time, using
      `scripts/bench v2-backends <workload>` as the before/after command.
      Progress 2026-07-09: the `v2-source-jvm-recursion-fib-perf` slice closes
      the JVM source `recursion-fib` row with Long-specialized recursive global
      helpers: default `scripts/bench v2-backends recursion-fib` moved
      `v2-jvm` from 67.5 ms to 1.37 ms. The broader item remains open for Rust
      source performance and other workload-family rows.
      Progress 2026-07-09: the `v2-source-rust-recursion-fib-perf` slice closes
      the Rust source `recursion-fib` row with Long-specialized recursive global
      helpers plus benchmark-only v2-rust anti-folding:
      `scripts/bench v2-backends recursion-fib` moved `v2-rust` from
      226.7 ms to 1.44 ms (`v2=6.03 ms`, `v2-jvm=1.25 ms`). The broader item
      remains open for other Rust/source workload-family rows.
      Progress 2026-07-09: the `v2-source-backend-production-perf-sweep` slice
      measured the remaining rows after the recursion-fib fixes. Fresh public
      rows: `arith-loop` => `v2=0.000016 ms`, `v2-jvm=0.267 ms`,
      `v2-rust=0.000025 ms`; `recursion-tco` initially exposed a false
      `v2-rust=0.000000 ms` LLVM fold, fixed by benchmark-only tail-recursive
      anti-folding, and now reports `v2=0.279 ms`, `v2-jvm=3.11 ms`,
      `v2-rust=0.721 ms`; `pattern-match-heavy` remains the largest real Rust
      source blocker at `v2=14.8 ms`, `v2-jvm=10.7 ms`, `v2-rust=318.2 ms`.
      Next recommended slice: `v2-source-rust-pattern-match-heavy-perf`. Also
      track `v2-jvm recursion-tco=3.11 ms` as a smaller JVM source-backend gap.
      Progress 2026-07-09: the `v2-source-rust-pattern-match-heavy-perf`
      slice closes the Rust source `pattern-match-heavy` row with structural
      Float helpers and a static top-level-list reduction path:
      `scripts/bench v2-backends pattern-match-heavy` moved `v2-rust` from
      319.1 ms to 0.278 ms (`v2=15.6 ms`, `v2-jvm=10.6 ms`). Rust source rows
      are no longer the blocker in the four-row source-backend sweep. The
      remaining recommended source-backend slice is
      `v2-source-jvm-recursion-tco-perf` (`v2-jvm=3.20 ms` in the regression
      row from this slice).
      Progress 2026-07-09: the `v2-source-jvm-recursion-tco-perf` slice closes
      the remaining JVM source `recursion-tco` row by prioritizing proven Long
      helpers over boxed direct tail-recursive methods:
      `scripts/bench v2-backends recursion-tco` moved `v2-jvm` from 3.09 ms to
      0.027 ms (`v2=0.253 ms`, `v2-rust=0.658 ms`). Fresh sweep/regression rows
      in the closing worktree: `arith-loop` => `v2=0.000016 ms`,
      `v2-jvm=0.267 ms`, `v2-rust=0.000026 ms`; `recursion-fib` =>
      `v2=11.0 ms`, `v2-jvm=1.71 ms`, `v2-rust=1.53 ms`;
      `pattern-match-heavy` => `v2=14.0 ms`, `v2-jvm=10.7 ms`,
      `v2-rust=0.265 ms`. Known JVM/Rust source-backend performance rows are
      closed; continue production-performance work under the separate
      `v2-vm-production-jit-gate`.
- **v2-vm-production-jit-gate** — ✓ Closed (2026-07-10) as route-policy gate;
      implementation slices landed across 2026-07-09 and 2026-07-10:
      three narrow VM slices have shipped. The first recognized the exact
      bridge-lowered local Long-cell summation loop from
      `bench/corpus/arith-loop.ssc`, moving the v2 VM row from 9.91 ms to
      0.000018 ms. The second (`v2-vm-pattern-match-heavy-fast-tier`) reused
      scratch env arrays for compact arithmetic-only `Match` fast arms,
      moving `pattern-match-heavy` from 35.1 ms to 16.4-17.0 ms. The third
      (`v2-vm-foreach-match-boundary`) evaluates supported inline `foreach`
      lambda bodies against a virtual appended element instead of allocating
      `Runtime.appendOne(env, elem)` per list element, moving the single-row
      `pattern-match-heavy` v2 result from 18.2 ms to 14.4 ms. The overall
      Phase-3 v2 VM production-performance gate remains open: the latest
      bounded four-row probe still shows `pattern-match-heavy` at 15.2 ms
      vs `ssc` 0.058 ms, `recursion-fib` at 5.80 ms vs 1.18 ms, and
      `recursion-tco` at 0.272 ms vs 0.031 ms. Keep closing this as one
      workload-family slice at a time; after these local VM hand paths, the
      next slice should be profile-backed and likely move toward broader
      bytecode-JIT/source-backend gate work rather than speculative new
      `FastCode` cases.
      Progress 2026-07-09: `v2-bytecode-production-gate-sweep` measured the
      existing JVM bytecode lane against the four representative rows. It is a
      strong production route for recursion (`recursion-fib`: `v2=5.89 ms`,
      `v2-bytecode=1.16 ms`; `recursion-tco`: `v2=0.258 ms`,
      `v2-bytecode=0.028 ms`) but not a universal default
      (`arith-loop`: `v2=0.000015 ms`, `v2-bytecode=0.609 ms`;
      `pattern-match-heavy`: `v2=13.7 ms`, `v2-bytecode=19.3 ms`). Current
      source-route comparison keeps `pattern-match-heavy` best on v2 Rust
      (`v2-rust=0.266 ms`) while pure VM/bytecode remain far behind. Next
      concrete blocker: a profile/inspection-backed `pattern-match-heavy`
      production slice; avoid another speculative VM `FastCode` recognizer
      without measured evidence.
      Progress 2026-07-10: `v2-pattern-match-heavy-production-profile`
      closed that concrete blocker for the VM route. The recognized structural
      shape is a static top-level list `foreach` accumulating a Float cell with
      a pure one-arg Float global. The VM now precomputes the pure per-element
      Float additions once and runs the hot loop as unboxed Double additions;
      the fallback test proves impure globals still execute per element.
      `scripts/bench v2-bytecode pattern-match-heavy` moved the VM row from
      `v2=14.6 ms` to `v2=0.266 ms` (`v2-bytecode=19.3 ms`), and
      `scripts/bench v2-backends pattern-match-heavy` now reports
      `v2=0.266 ms`, `v2-jvm=10.9 ms`, `v2-rust=0.265 ms`. Next
      recommended production slice: rerun the bounded four-row route gate and
      record which rows should default to VM, bytecode, JVM source, or Rust
      source before declaring the v2 production route policy closed.
      Progress 2026-07-10: `v2-four-row-route-policy-sweep` closed the
      representative public-route policy gate without code changes. Fresh
      rows: bytecode wins recursion (`recursion-fib` `1.19 ms`,
      `recursion-tco` `0.028 ms`) but regresses scalar/pattern rows
      (`arith-loop` `0.595 ms`, `pattern-match-heavy` `19.4 ms`); JVM source
      is the best TCO route (`0.027 ms`) but not pattern-heavy (`10.9 ms`);
      Rust source ties VM on scalar/pattern rows (`arith-loop` `0.000026 ms`,
      `pattern-match-heavy` `0.269 ms`) but not recursion. Global default
      stays VM because no single non-VM route improves all four rows. The
      production policy is explicit route selection by workload/deployment
      family: bytecode/JVM source for recursion, VM/Rust source for
      scalar/pattern-heavy. Pure-VM recursion remains a known non-default
      performance gap only if a deployment forbids bytecode/source routes.
      Reconcile verification (2026-07-10): `scripts/sbtc "installBin"`,
      `scripts/bench v2-backends pattern-match-heavy` (`v2=0.266 ms`,
      `v2-jvm=10.4 ms`, `v2-rust=0.293 ms`), and
      `tests/conformance/run.sh --only 'list-companion' --no-memo` 1/1
      passed. `v2-auto-route-selector` remains a can-wait follow-up, not a
      production blocker while explicit public route flags are available.
- [ ] **v2-auto-route-selector** — can-wait follow-up after the manual route
      policy: design and implement a conservative program-shape/profile-based
      selector that can choose VM, bytecode, JVM source, or Rust source per
      workload family. This is not a v2 production blocker while the public
      route flags are available; do not pick it ahead of correctness or
      packaging blockers.

## Conformance test performance (2026-07-06) — see `specs/conformance-perf.md`

The conformance suite is expensive: `tests/conformance/run.sc` spawns a subprocess per case × 3 lanes
(INT/JS/JVM), the JVM lane a **cold scala-cli Scala-3 compile each time**; run bare in ~15 parallel
worktrees with uncapped forked test JVMs the aggregate saturates host RAM (it starved a co-tenant rozum
GPU run). Shipped: `scripts/conformance` (opt-in, additive) bounds concurrent runs host-wide + caps child
JVM heap — adopt it as the default conformance command (README updated). Remaining, ordered by value —
each needs scala-cli to implement + verify, so an owning ScalaScript agent should claim it:

- [x] **conformance-affected-only** — DONE 2026-07-06 (`run.sc --only`, measured 1 case = 8.8s). — `run.sc --only <glob|files>` (+ a change→case index) so the
  fix→test loop runs just the touched cases, not the full 193. BIGGEST iteration-speed win and it's the
  agents' OWN loop that speeds up. Full corpus stays for CI. (specs/conformance-perf.md F1)
- [x] **conformance-memoize** — DONE 2026-07-06 (green-run memo, re-run 0.43s ~20x; --no-memo escape). — skip a case whose `(input .ssc, ssc/compiler version, expected)` hash is
  unchanged since the last green run. (F2)
- [x] **conformance-warm-runner** — DONE 2026-07-06 (F3 subset: SSC_SCALACLI_SERVER=1 warm bloop for run-jvm, 5.4->2.8s/case; full resident-JVM F4 still open). — replace cold fork-per-run with a resident warm JVM (compiler loaded +
  JIT-warmed); reuse one warm compiler for the JVM lane instead of a cold scala-cli compile per case;
  `conformance / Test / fork := false` if pure. (F3/F4)
- [x] **conformance-test-heap-default** — DONE 2026-07-06 (build.sbt SSC_TEST_XMX default 2g). — give forked test JVMs a sane env-gated default `-Xmx` in
  `build.sbt` (currently uncapped → ~9 GB default) instead of relying on the wrapper. Measure real peak
  first. (L1)

## Crypto/finance roadmap — later epics (2026-06-23, with Sergiy)

The larger / later items of the crypto/blockchain/identity/payments roadmap. Near-term codeable slices are in
`SPRINT.md` → "Crypto/finance roadmap". Full plan + per-item "what / why / where / benefit":
**[`docs/crypto-finance-roadmap.md`](docs/crypto-finance-roadmap.md)** (explainer) +
**[`specs/crypto-finance-roadmap.md`](specs/crypto-finance-roadmap.md)** (engineering plan). All follow the
**reference → seam → gate → native** FROST template. Grouped here so the area isn't scattered.

**Track 1 — chains & currencies (deeper):**
- [~] **crypto-spi-pure-references** — pure-Scala references for Keccak-256, Blake2b, RIPEMD-160, secp256k1
      scalar/point math, `register`-able as the SPI fallback so each primitive runs with no native provider
      (deepens `crypto-spi-blake2b`). Gate: bit-for-bit vs BouncyCastle/`@noble` over RFC vectors + random inputs.
      **ALL FOUR REFERENCES NOW EXIST:** Blake2b / RIPEMD-160 / secp256k1 (+ SHA-256/512, Ed25519) landed with
      `chains-backend-agnostic`; **Keccak-256 added 2026-07-05** (`Keccak256.scala` in `crypto-spi/shared`,
      pure Keccak-f[1600] sponge, Ethereum pad 0x01) — bit-for-bit vs BouncyCastle over rate-boundary + multi-
      block inputs, JVM+JS byte-identical. **P-256 added 2026-07-05** (`P256Group.scala` + `P256Ecdsa.scala`,
      a=-3 Jacobian curve + ECDSA) — byte-for-byte vs BouncyCastle. **REMAINING:** a `register`-able
      pure-reference `CryptoBackend` that wires them all as the SPI fallback so primitives run with no
      native provider.
- [ ] **chains-new-adapters** (epic) — a `ChainAdapter` per new chain: Aptos / Sui / Stellar / XRPL / Polkadot
      (Ed25519 or secp256k1 + tidy encoding). "Mostly another adapter" once the primitive is in the SPI.
      **Polkadot is BLOCKED on an sr25519 (Schnorrkel) reference** — `Curve.Sr25519` is enumerated but
      unimplemented. Gate: address derivation + a signed-tx fixture per chain.

**Track 2 — threshold & MPC (heavier, after `frost-secp256k1`):**
- [ ] **musig2** — Bitcoin n-of-n as a single on-chain key; MuSig2 2-round aggregation over the secp256k1
      Schnorr base from `frost-secp256k1`. Gate: aggregated sig verifies as an ordinary BIP-340 single-key sig;
      BIP-327 vectors.
- [ ] **threshold-ecdsa** (heaviest — genuinely multi-round MPC, NOT "implement a trait") — GG/Lindell
      threshold ECDSA (Paillier/OT) for legacy Bitcoin/Ethereum (ECDSA) addresses. Own module; reuses only the
      Shamir/Lagrange base. Gate: output verifies as standard ECDSA vs a reference for random t-of-n.
- [ ] **vrf-bls** — VRF (RFC 9381 ECVRF) for leader-election/lottery randomness; BLS aggregate signatures over
      **BLS12-381** (`Curve.Bls12_381` enumerated, unimplemented → **BLOCKED on a pairing-friendly-curve
      reference**). Gate: VRF + BLS aggregate verify and match RFC/IETF vectors.

**Track 3 — identity & token services (clusters):**
- [~] **webauthn-server-verify** — server-side passkey assertion verification (P-256/Ed25519 verify + CBOR
      attestation), closing the loop with our existing client-assertion path (ERC-4337 passkey owner). Gate:
      W3C WebAuthn vectors + round-trip with our own client assertions.
      **Assertion-verify core DONE 2026-07-05** (`WebAuthnVerify.scala` in `crypto-spi/shared`): COSE_Key
      parse (EC2/P-256 → ES256, OKP/Ed25519 → EdDSA) via `Cbor` + signature check over
      `authenticatorData ‖ SHA-256(clientDataJSON)` (ES256 DER via `P256Ecdsa`, EdDSA raw via `Ed25519`),
      round-trip + tamper/wrong-key rejection, JVM+JS. **REMAINING:** registration/attestation-statement
      verification (packed/tpm/…) and the caller-side policy checks (challenge/origin/rpIdHash/UP-UV/signCount).
- [~] **token-formats** — PASETO / JWT / COSE token sign+verify over the crypto SPI (COSE pairs with
      webauthn-server-verify). Gate: RFC 7519 (JWT) / PASETO / RFC 8152 (COSE) vectors.
      **JWS/JWT DONE 2026-07-05** (`Jws.scala` + `Jwt` in `crypto-spi/shared`): portable compact JWS
      (RFC 7515) sign+verify for **HS256** (HmacSha256) and **EdDSA** (Ed25519) on the portable crypto
      primitives — byte-exact vs RFC 7515 A.1 + RFC 8037 A.4, JVM+JS, with constant-time MAC compare and
      tamper/malformed-token rejection.
      **PASETO v4.public DONE 2026-07-05** (`PasetoV4.scala` in `crypto-spi/shared`): portable
      `v4.public` sign+verify (Ed25519 over PAE), footer + implicit-assertion binding, version/purpose/
      tamper rejection — PAE pinned to the PASETO spec vectors + verified against the official `v4.json`
      "4-S-1" public key, JVM+JS.
      **COSE_Sign1 EdDSA DONE 2026-07-05** (`Cbor.scala` + `CoseSign1.scala` in `crypto-spi/shared`):
      a minimal portable CBOR codec (gated by RFC 8949 Appendix A) + COSE_Sign1 (RFC 8152/9052) sign+verify
      with EdDSA (`alg -8`), external-AAD binding, alg/tamper rejection — round-tripped under the RFC 8037
      key, JVM+JS. Unblocks `webauthn-server-verify` (COSE structures now available).
      **JWS ES256K DONE 2026-07-05** (`Secp256k1Ecdsa.derToRaw`/`rawToDer` + `Jws.signES256K`/`verifyES256K`
      + `Jwt.es256k`): ECDSA secp256k1 + SHA-256 with the fixed 64-byte R‖S encoding — byte-for-byte equal
      to the BouncyCastle secp256k1 backend (both RFC-6979 + low-S), JVM+JS.
      **COSE ES256K DONE 2026-07-05** (`CoseSign1.signES256K`/`verifyES256K`, protected `{1:-47}`):
      COSE_Sign1 now covers EdDSA + ES256K over the same R‖S helper, with an authenticated alg guard
      (cross-alg confusion rejected), round-tripped JVM+JS.
      **Portable P-256 reference DONE 2026-07-05** (`P256Group.scala` + `P256Ecdsa.scala` in
      `crypto-spi/shared`): NIST P-256 group (a=-3 Jacobian doubling) + ECDSA (RFC-6979 + SHA-256, DER +
      64-byte R‖S) — byte-for-byte equal to the BouncyCastle P-256 backend (derivePublic + verify interop),
      JVM+JS. **Unblocks ES256** (`Curve.P256` no longer BouncyCastle-only) and `webauthn-server-verify`.
      **ES256 DONE 2026-07-05** (`Jws.signES256`/`verifyES256` + `Jwt.es256`; `CoseSign1.signES256`/
      `verifyES256`, COSE alg `-7` / protected `{1:-7}`): ECDSA P-256 + SHA-256, 64-byte R‖S — the JWS path
      **verifies the published RFC 7515 A.3 ES256 token**, COSE round-trips with the authenticated alg guard,
      JVM+JS. token-formats now covers JWS HS256/EdDSA/ES256K/ES256, PASETO v4.public, and COSE_Sign1
      EdDSA/ES256K/ES256.
      **COSE_Encrypt0 DONE 2026-07-05** (`CoseEncrypt0.scala`, RFC 8152 §5.2, alg 24 ChaCha20-Poly1305
      over `Cbor` + `ChaCha20Poly1305`): encrypt/decrypt with the `Enc_structure` AAD + `{5:iv}` header,
      round-trip + tamper/wrong-key/wrong-AAD rejection, JVM+JS — COSE now covers sign (COSE_Sign1) and
      encrypt (COSE_Encrypt0). **REMAINING:** PASETO **v4.local** (XChaCha20 + keyed BLAKE2b — extend
      `ChaCha20Poly1305` with HChaCha20 + add keyed `Blake2b`); multi-recipient COSE_Encrypt.
- [~] **noise-protocol** — Noise handshake patterns over the existing X25519 + ChaCha20-Poly1305 primitives
      (short hop — WalletConnect already uses them). Gate: Noise spec vectors (XX, IK).
      Primitives portable: `ChaCha20Poly1305.scala` (RFC 8439) + `X25519.scala` (RFC 7748) +
      `HkdfSha256.scala` (RFC 5869), byte-exact, JVM+JS.
      **Noise 11 patterns DONE 2026-07-05** (N/NN/NK/NX/XN/XX/XK/KK/IN/IK/IX; `Noise.scala`): a pattern-driven engine (CipherState +
      SymmetricState + HandshakeState, pre-message support + the `e s ee es se ss` tokens) over the
      25519/ChaChaPoly/SHA256 suite. Built-in `NN` (unauthenticated), `XX` (mutual auth), and `IK`
      (initiator pre-knows the responder static — WireGuard/Lightning style). Functional gate per pattern:
      a full handshake derives matching transport keys, the auth semantics hold (NN: no statics; XX/IK:
      mutual), encrypted transport round-trips both ways, and a tampered message fails auth — JVM+JS.
      **REMAINING:** more patterns (NK/XK/…) + a byte-exact check against the cacophony/snow Noise
      test-vectors. The same primitives still unblock `age-encryption`; PASETO **v4.local** additionally
      needs keyed BLAKE2b (the XChaCha20 extended-nonce variant now exists — `ChaCha20Poly1305.xseal`/
      `xopen` + `hchacha20`, draft-irtf-cfrg-xchacha, 2026-07-05).
- [~] **did-vc** (epic) — did:key / did:web resolvers + Verifiable Credential signing (JSON-LD or JWT) over the
      crypto SPI; a whole decentralized-identity stack. Gate: W3C DID/VC test suites.
      **did:key DONE 2026-07-05** (`DidKey.scala` + a portable `Base58` btc codec in `crypto-spi/shared`):
      encode + resolve for Ed25519 (multicodec `0xed01` → `did:key:z6Mk…`) and compressed P-256 (`0x1200`
      → `did:key:zDn…`), matching the W3C did:key registry prefixes; base58 hand-vectors + round-trip, JVM+JS.
      With the JWS layer, JWT-VC issuance is now within reach. **REMAINING:** did:web resolver; VC data
      model (JWT-VC / JSON-LD) signing + verification; W3C DID/VC test-suite conformance.
- [ ] **age-encryption** — encrypt-to-public-key: age (X25519 + ChaCha20) first, PGP interop only if demanded.
      Gate: age reference vectors; round-trip with the `age` CLI.

**Track 4 — "invent our own" products:**
- [x] **threshold-custody-wallet** ✓ DONE 2026-06-24 — composed `cryptoFrost` (FROST-Ed25519) +
      `walletVaultMpcFrost` + an HTTP transport into a working distributed threshold-custody wallet.
      `FrostParticipantServer` (JDK `HttpServer`, holds ONE share, exposes `/round1` `/round2` `/health`) +
      `DistributedFrostSigningClient` (a `RemoteSigningClient` coordinator holding the group key + participant
      URLs but **no shares**, runs the 2-round protocol over HTTP/JSON and aggregates a standard Ed25519
      signature). **Gate MET:** a multi-host test (each share on its own localhost port = its own "host") signs
      with no co-located shares and the sig verifies under standard Ed25519 (2-of-3, 3-of-5); it drops straight
      into `McpVault` (unlock→getSigner→sign) — the threshold-custody-wallet end to end; `health()` is false when
      `<t` participants are reachable. walletVaultMpcFrost 8/0. Transport is HTTP/JSON; a WS or actor-cluster
      transport is the same protocol over a different pipe (bodies unchanged). No new deps (JDK http + ujson).
- [x] **micropayment-own-scheme** ✓ DONE 2026-06-24 — **PayWord hash-chain** scheme (`payments/micropayment/
      hashchain`, `ChannelKind.HashChain`): a from-scratch off-chain scheme over the portable crypto — one
      Ed25519-signed commitment at open (payer authorizes the chain tip `wₙ = SHA256ⁿ(seed)`), then **signature-free
      per-payment preimage reveals** (`w₍ₙ₋ᵤ₎`, verified with one SHA-256, no round-trip). `HashChain` (crypto) +
      `HashChainChannel` (MicropaymentChannel: pay reveals, receive verifies vs tip / incrementally, settle redeems
      the deepest reveal) + `HashChainProvider` (ChannelProvider). **Gate MET:** open→pay→receive→settle lifecycle +
      signed-commitment verify, forged-preimage / replay / over-capacity / non-multiple rejection, and the
      deepest-reveal-proves-cumulative property (payee may skip intermediates). 7/7; all other micropayment
      consumers recompile clean (ChannelKind addition safe). Settlement is off-chain accounting + the redemption
      proof (deepest preimage + signed commitment) — parity with the probabilistic provider's deferred on-chain
      claim.
- [ ] **distributed-infra** (speculative) — reference-first oracle/attestation, content-addressed storage, and
      gossip/CRDT layers over the actor/cluster substrate + crypto SPI. Gate: per-component correctness + a
      cluster integration test.

## Roadmap — agreed priority order (2026-06-17, with Sergiy) — ⚠️ SUPERSEDED 2026-07-16

> **HISTORY, NOT THE CURRENT DIRECTION.** This order (agent-sdk → package-registry →
> sbt-plugin → …) described mid-June. Since then the work has been the three streams in
> `MILESTONES.md` §"Where we are going" — v2 self-hosting, dogfood (scljet/uniml), control/interop
> — confirmed with Sergiy on 2026-07-16. The entries below stay for their findings and their
> still-open follow-ups, which remain valid work; they are just **not** what to pick next.
> Do not start a theme from this list without asking.

Drive top-to-bottom, one major theme at a time. **Maven/centralized publication is dead
last — after everything else.**

1. **payments-reorg** ✓ DONE 2026-06-17 — all 24 payment-domain interp plugins moved under
   `payments/` (hybrid: `payments/processors/{spi,stripe,…}` for the 21 providers + SPI;
   `payments/crypto/plugin` + `payments/payment-request/plugin` next to their libs). Build-config
   only (git mv + `file()` paths); packages/services/val-names/aggregate/PluginSpec unchanged →
   user `.ssc` untouched. 5 slices, all compiled; sepa 71 / stripe 23 / crypto 58 tests green;
   installBin stages all plugins; 0 payment dirs left in runtime/std. spec `specs/payments-reorg.md`.
   **→ Next theme: agent-sdk-remainder (#2).**
2. **agent-sdk-remainder** (MINE) — the generic LLM-agent SDK is ~P0–P2 built
   (`runtime/std/agent.ssc`; specs `rozum-agent-{endpoint-pool,schema-derivation,streaming}`;
   4 interp test suites; 5 examples). Remaining: **P3** (embedded transport + MCP-server
   framework so external agents can drive an app), a **consolidated scalascript-side
   `specs/agent-sdk.md`** (mirroring rozum's `docs/specs/agent-sdk.md` + `integration.md` —
   the 3-contract model: ModelClient/AgentLoop/ToolRegistry/SchemaDerivation/EndpointPool/
   Transcript), and broader **conformance** (mock gateway + golden transcripts + live rozum).
   Coordinate via claims — core is shared with the rozum/busi effort.
   **Progress 2026-06-17:** ✓ consolidated `specs/agent-sdk.md` (P0–P2 confirmed shipped).
   ✓ **P3a MCP bridge COMPLETE (both directions)** — `runtime/std/agent-mcp.ssc`:
   `serveAgentToolsMcp(tools, transport)` (expose AgentTools over `mcpServer`) +
   `mcpToolSource(client)` (wrap an MCP server's tools as AgentTools; JSON→Map via the existing
   `jsonParse` intrinsic surfaced as a local extern; jvm/js only). Examples
   `agent-mcp-{server,toolsource}.ssc`; module + both examples `ssc check` OK; pushed. The two
   `ToolResult` types never meet by name → no collision. **Remaining:** (b) round-trip test
   (server+client; needs an MCP transport workable in a jvm/js test — Http is JS-only, Stdio blocks;
   mirror `McpEndToEndTest`); (c) conformance (mock gateway + golden transcripts). P3b Embedded =
   deferred (needs rozum `rozum-embed`).
3. **package-registry** ✓ DONE 2026-06-20 (CLI + no-domain static registry) — `ssc search`/`info`/`add`
   over `RegistryClient` (URL-priority + 1h-TTL cache + `--refresh` + `--offline`) + seed
   `registry/packages.yaml`; generated `registry/site/` serves `packages.yaml`, HTML, search JSON, and
   per-package JSON through GitHub Pages project URL
   `https://sergey-scherbina.github.io/scalascript/`. spec `specs/arch-registry.md` reconciled. REMAINING:
   optional custom-domain alias (`registry.scalascript.io`) and cross-repo/community governance.
4. **sbt-plugin-finish** ✓ ACTIONABLE SCOPE DONE — `specs/arch-sbt-plugin.md` build surface is closed:
   front-matter `dependencies:`→Coursier and cross-build targets (`sscBackends`) landed; LSP/BSP polish has
   no concrete remaining deliverable. Publication of the plugin itself is part of the deferred Maven step.
5. **metaprogramming-v2** ✓ ACTIONABLE SCOPE DONE — `specs/arch-metaprogramming-v2.md`. AUDIT 2026-06-17: NOT from-scratch.
   All three phases have working bases (P3 Linker inline expansion; P4 `${impl('x)}`+`'{…}`+interp
   parity+`MacroImpl` IR; P5 runtime `Mirror`+user `derived(m: Mirror)`). **Track A** ✓ DONE (P5 cross-backend
   derives conformance — A1a/b/c+A2+A3, 2026-06-17; deferred edge cases only), **B** (P4 const-fold:
   **B1+B2 ✓ DONE 2026-06-18**, **B3 ✓ DONE 2026-06-18 — JVM + JS** via `macro-codegen-backends`
   (`MacroCodegen.expand`); Track B complete), **C** ✓ ACTIONABLE SCOPE DONE (C1 multi-clause inline +
   C2's practical backend warning guard via `MacroCodegen.codegenWarnings`). The broader arbitrary
   post-expansion re-typecheck + source-positioned-error ambition is deferred by design (position-map
   requirement + false-positive risk), not current build work.

   *(macro-codegen-backends ✓ DONE 2026-06-18 — JVM + JS; moved to CHANGELOG. The default
   `emit`/`build`/`run` path does not use the Linker — `JvmGen`/`JsGen` inline imports at the
   source/tree level and rely on scalac's own `inline`; the `MacroCodegen.expand` pre-codegen pass
   handles macros for both backends.)*

   *(macro-crossmodule ✓ DONE 2026-06-18 — JVM (Approach B, `expandUnits`+`expandMacrosInBlocks`) + JS
   (Approach A entry-hook over local `.ssc` imports + `genImport` strip); moved to CHANGELOG. Follow-up:
   transitive cross-module macros on JS — the `genImport` strip uses no `baseDir`, so an imported module
   that itself calls a macro from its own imports isn't handled. Rare.)*
6. **deferred perf** — **CLOSED 2026-06-18 (re-measured; see the resolved entries below).**
   `hof-glue-jit-compile` → DEFERRED to the dual-bank `LExpr` VM roadmap (the only remaining lever is whole-fn
   JIT of `combineAll`, gated on that VM + `using`/given JIT support). `vectorize-pure-loop` → WONTFIX-until a
   non-polynomial hot-loop workload appears (targets already bypass the loop via Gauss). `direct-style-eval`
   → WONTFIX (data-disproven: `Pure` ≈16% alloc, dispatch ≈66% which it doesn't touch; 1261-site migration).
7. **other extensibility themes** — **AUDIT 2026-06-17: most are already BUILT; specs were stale.**
   A (Plugin SPI — `BackendRegistry` exists), E (`ssc new`/install — verified 2026-06-19: all bundled
   templates + standalone fixtures covered locally; live publication remains deferred), F (DSL hooks — spec
   "implemented through Phase 4", `InterpolatorRegistry`), H (library modularity — spec "implemented
   through Phase 6", `SsclibManifest`), J (FFI — `GlueClasspathRegistry`/`GlueJsPreambleRegistry` +
   `@jvm`/`@js` + `examples/js-glue-component.ssc`; spec stale at "planned"). **Action: reconcile these
   specs/BACKLOG to reality + verify any residual — NOT a from-scratch build.** **B** (build-time
   registry consolidation): Phases 1 AND 2 BOTH landed 2026-05-29 (spec confirms — `PluginRegistry`/
   `PluginMeta`/`PluginSource` + `BackendRegistry` facade + `SubprocessPlugin` + `RemotePluginInstaller`
   + `BackendRegistryTest`). **Phase 3 is MOOT (reconciled 2026-06-18):** `PluginManifest`/`LocalRegistry`
   are NOT removable "deprecated wrappers" — they are the **implementation** the facade is built ON
   (`BackendRegistry` uses `PluginManifest` for `manifestCache`/`defaultSearchPaths`; `ImportResolver` +
   `PluginCommands` use `LocalRegistry.resolve`/`loadAll` for the `~/.scalascript/registry.yaml`
   download-URL flow). There is nothing to "remove" — they're load-bearing. `isStdPluginInterpreterTest`
   is already gone. So Phase 3 = no action. OPTIONAL Phase 4 (family registries, "only where they remove
   real duplication") remains, demand-driven.
8. **arch-distribution-p3 / Maven Central + sbt Plugin Portal** — **LAST**, only on explicit go.

> **Roadmap reality check (2026-06-21):** the codebase is well ahead of these specs/BACKLOG entries —
> agent-sdk-remainder and package-registry were both found already built, and the audit shows A/E/F/H/J
> are largely built too. The previously listed autonomous build slices are now reconciled:
> `sbt-plugin-finish` dep-resolution/cross-build landed and publication is Maven-gated; build-registry
> Phase 3 is moot and Phase 4 is demand-driven; `metaprogramming-v2` Tracks A/B/C are actionable-scope done,
> with only explicitly deferred edge cases. Remaining work is now product/external (domain/governance/
> publication, browser/device harnesses, hardware, or a concrete demand signal), not an unclaimed
> "just build it" queue.

- [x] **v2-jvm-tco-manual** ✓ Landed (2026-07-09, `7f58b1516`) — source JVM
      backend now emits a conservative local `while` dispatcher for eligible
      mutual-tail `LetRec` groups; unsafe groups keep the closure-var fallback.
      Deep even/odd `mutual-tco.coreir` runs stack-safe and the full
      `./v2/conformance/check.sh` gate passed.

## Architecture Review follow-ups (2026-06-14)

Whole-project architecture survey (231 sbt modules, ~145K LOC main Scala). The project is
mature and low-debt (only 6 TODO/FIXME files, 21 "not yet supported"); these are *refinements*,
not blockers — hence BACKLOG, not SPRINT. Ordered by leverage/tractability. **#1 is the
recommended first pick** (bounded, measurable, compounds with the perf work).

- [x] **module-graph-grouping** ✓ INVESTIGATED → leave-as-is (2026-06-18, `docs/module-graph-findings.md`).
      197 `lazy val` module defs; thin SPI families (wallet 42, payments 35, walletVault 18, blockchain 13,
      x402 13). Conclusion: the per-impl module boundary **is** the SPI boundary — grouping the families
      either collapses it (shared package/service/artifact, can't take one impl) or is a no-op on the build
      graph (sbt `aggregate` only reduces typing). There is no consolidation that shrinks the graph AND
      keeps the boundaries, which the item's own constraint requires. The cold-build cost is the price of
      the deliberate "one module per SPI impl" design (cf. payments-reorg). **No action**; if a specific
      family is later found to have *true* code duplication, factor the shared part into one library module
      the impls depend on (targeted refactor, not family grouping).

- **remote-package-registry** → MOVED TO SPRINT 2026-06-23 (Sergiy "внеси в спринт"; active queue). Local
      story done (`~/.scalascript/registry.yaml` + `pkg:` resolver + `ssc install` + `.sscpkg`); the remote half
      (registry protocol + `ssc publish`/`search` + remote `pkg:` against a configurable endpoint, testable vs a
      local/mock server) is now active work. Public hosting (`registry.scalascript.io`) is a separate deploy step.

- [x] **rust-backend-cargo-smoke-coverage** ✓ Landed (2026-06-22, `2c8032a5c`, mellow-shrew) — added
      `RustGenCargoSmokeTest`: a Rust-toolchain-gated (`assume(cargoAvailable)` — probes `cargo --version`
      directly, since `backendRust` doesn't depend on the CLI's `RustToolchain`) suite that emits a
      feature-exercising program to a temp crate, `cargo run`s it, and asserts real stdout. Covers
      collection ops (take/drop/takeRight/dropRight/sorted/distinct/sum), string ops (replace/startsWith/
      endsWith/contains), and the `Vec<String>` index-read regression (E0507). Kept out of the fast
      string-match path; toolchain-less CI skips cleanly. `backendRust` 236/0. Closes the move/borrow/type
      bug class that string-match tests can't see. (http end-to-end coverage left as a future extension —
      needs a port/client, heavier than the pure collection/string program.)

## Native Platform follow-ups

- [ ] **std-nfc-packager-adapters** (BLOCKED: real packagers/device-browser harnesses) — Consume
      `scalascript.frontend.NativePlatformRequirements` in the SwiftUI/iOS,
      Android, and Web/PWA packagers, then implement real `std.nfc` read/write
      adapters where those targets exist. HOW: keep `runtime/std/nfc.ssc`
      unchanged; make native package generation use `Capability.NfcNdef` to
      emit Info.plist/entitlement, AndroidManifest, and Web permission/model
      declarations; add real device/browser harnesses for `readNdef()` and
      `writeNdef()`; check off the remaining hardware/manifest behavior items
      in `specs/std-nfc.md`. Deferred from `std-nfc-native-adapters` because
      the repo currently has the NFC API and requirements contract but no
      complete Android/Web-NFC packager integration path.

## WASM backend

The WASM backend (`runtime/backend/wasm`, Scala.js → `.wasm` via `scala-cli --js-emit-wasm`) now
handles `@wasm` externs, local `.ssc` import inlining, and quoted macros (2026-06-18). What remains:

- [x] **wasm-effects** — algebraic effects / handlers on WASM. **COMPLETE 2026-06-20.** **FIRST SLICE ✓ DONE 2026-06-18 — effects
      compile AND run on wasm.** The approach (probe-proven): `JvmGen.generateUserOnly` (CPS-lowered code,
      *without* the 300 KB JVM preamble — that preamble's `Thread`/`java.nio` parts are what crash the
      Scala.js linker) + a minimal **Scala.js-linkable effect runtime** (`WasmEffectRuntime` =
      `_Computation`/`_bind`/`_perform`/`_run`/`_handle`/`_handleWithReturn`, the pure-Scala subset of
      `JvmGenRuntimeSources`) emitted in `package _ssc_runtime`, + a re-added `@main` (generateUserOnly
      strips it). `backendWasm` now `dependsOn backendJvm`. Verified: `WasmBackendTest` compiles an effect
      program to a valid `.wasm` AND runs it via node (handler + resume → `hello\nworld`).
      **arithmetic ✓ DONE 2026-06-18 (slice 2a):** `_binOp` (+ `_bigIntOp`/`_bigDecOp`, all pure-Scala /
      Scala.js-linkable) added to `WasmEffectRuntime`; a probe showed `a + b` over `Any`-typed effect-op
      results lowers to `_binOp` — programs doing arithmetic in/around handlers now link + run (test
      'effects with arithmetic in body RUN on wasm' → 40). **`_dispatch` ✓ DONE 2026-06-18 (slice 2b):**
      collection/method calls on `Any` (e.g. `xs.map(..).filter(..).head` in a handler) lower to `_dispatch`;
      added the pure-Scala subset of `_dispatch` + its CPS-aware `_seqMap/_seqFlatMap/_seqFilter/_seqForeach/
      _seqExists/_seqForall/_seqCount/_seqFind/_seqFoldLeft` (+ `_seq`/`_isFree`) to `WasmEffectRuntime` —
      the JVM `getClass.getMethods…invoke` reflection `case _` (which the Scala.js linker rejects) is
      replaced by a clear error. Covers List/String/Option/Map/Set/numeric incl. sortBy/sorted. Test 'effects
      with collection HOFs in body RUN on wasm' → 6. **multi-shot ✓ DONE 2026-06-18 (slice 2c):** did NOT need
      a `_handle` rewrite (the wasm `_handle`'s `resume = (v) => interp(fn(v))` already supports repeated
      resume — same structure as the JVM one). A probe showed the canonical `opts.flatMap(o => resume(o))`
      handler lowers to `_anyFlatMap` + `_dispatch(all,"length")`; only `_anyFlatMap` was missing — added it
      (pure-Scala). Also fixed `usesEffects` to recognise the `multi effect Foo:` form (it keyed on a leading
      `effect`, so multi-shot modules skipped CPS lowering and hit scala-cli raw). Test 'multi-shot effects RUN
      on wasm' (NonDet `{1,2}×{10,20}`) → 4. **cross-module ✓ DONE 2026-06-18 (slice 2d, no code change):** an
      `effect` declared in an imported `.ssc` and only handled in the consumer already works — `generateUserOnly`
      resolves local imports via `baseDir` and lowers the whole graph (`object Log` + `_perform` + inlined
      `shout()`), and `collectSource` inlines the decl so `usesEffects` routes to the effect path. Verified by a
      run test 'cross-module effects RUN on wasm' (lib.ssc declares + performs, consumer handles) → `hello\nworld`.
      **`@main` args/non-Unit edge ✓ DONE 2026-06-20 (`wasm-main-edge`):** effectful WASM derives the user
      `@main` from the AST, preserves a single Scala 3 main parameter clause (including `String*` splicing),
      discards non-Unit returns in the synthetic wrapper, and rejects raw `Array[String]` args before scala-cli
      with a clear diagnostic. **Complete for wasm — common + advanced cases all run** (40 `WasmBackendTest`);
      any dynamic method outside the linkable `_dispatch` subset now errors clearly (was a reflection call on JVM).
      All additive, wasm-only.
- [x] **`@wasmExport` / `@wasmImport`** ✓ OUT OF SCOPE BY DESIGN — raw WASM ABI export/import would need a
      direct-emit wasm backend, not the current Scala.js-owned wasm path. Do not treat this as claimable
      backlog without a new backend decision.

## Interpreter Performance — Open Targets

Baselines from `scripts/bench interp` run 2026-06-04 (Javac JIT backend, `-wi 3 -i 5 -f 1`).

- [x] **hof-glue-jit-compile** — **RESOLVED 2026-06-19 with WORKING CODE + MEASUREMENT (not just analysis).
      Slice A SHIPPED to main default-on** (`LITER*` opcodes + `VmCompiler.tryCompileFoldLeft`; compiles a
      `List[Int].foldLeft` so it no longer bails the whole enclosing function; kill-switch `SSC_JIT_FOLDLEFT=0`;
      `JitFoldLeftTest` 17 differential tests + full interp suite 1878 green WITH IT ON). **No measured perf
      win** (interp `foldLeftReusing`/while-JIT already optimize the hot parts) — shipped per decision as a
      capability. **The typeclass case (`typeclassFoldMacro`) IS now sped up — ~19% — but via a SAFE
      interpreter memo, not the VM Slice C** (2026-06-19): a JFR profile showed the cost is ~79% evalCore
      tree-walk of the `summon[M].empty`/`summon[M].combine` sub-expressions, re-evaluated per call. So
      `evalFusedFoldLeft` memoizes the evaluated `(empty, combine)` per call-site keyed by given identity —
      repeat calls skip those sub-expressions. **DEFAULT-ON** (kill-switch `-Dssc.jit.foldtc=0`) — assumes a
      lawful, referentially-transparent monoid `empty`. `JitFoldTcTest` 8 differential tests (incl. polymorphic
      two-given soundness) + full interp suite green WITH IT ON (1839 tests, excl. infra-flaky cross-backend);
      typeclassFoldMacro 1.794 → 1.453 ms/op. The full VM Slice C (type-method opcode +
      hot-path using-guard relaxation) stays unbuilt — disproportionate, and the interp memo gets most of the
      win safely. Detail in `specs/jit-foldleft-compile.md`.
- [x] ~~**hof-glue-jit-compile** (superseded note)~~ — **RESOLVED 2026-06-19 with WORKING CODE + MEASUREMENT.**
      Slice A (inline-lambda `foldLeft` VM compilation) was BUILT + VERIFIED (`LITER*` opcodes +
      `VmCompiler.tryCompileFoldLeft`, flag-gated off-by-default; `JitFoldLeftTest` 12 differential tests +
      1873 interp green) and kept on branch `feature/jit-foldleft-a` (commit `4be211177`), NOT merged —
      because the **measurement showed no win**: `foldLeftLambda` 0.004→0.003 ms/op (within ±0.001 noise),
      since the plain-lambda fold is already fast via `foldLeftReusing`. The only slow case
      (`typeclassFoldMacro` 1.14 ms) needs Slice C, which tracing proved is disproportionate: generic
      `List[A]` (ref-domain fold, no safe unbox), a *type-method* combine (`lookupTypeMethod`/`invokeTypeMethod`,
      new opcode, still a dispatch per element even compiled), + relaxing the type-gate and the
      `usingParams.isEmpty` guards on the hottest call path (`CallRuntime` 137/239/257/284/632). A large
      multi-site hot-path change for a synthetic-bench bounded win — NOT pursued. Detail/build-log in
      `specs/jit-foldleft-compile.md`. Revisit only if a real runtime-typeclass-fold hot loop appears.
- [x] ~~**hof-glue-jit-compile** (prior design note)~~ — **DESIGNED + BUILD-READY 2026-06-19 (`specs/jit-foldleft-compile.md`).**
      Mapped the full "JIT-compile `combineAll`/`foldLeft`" lever against the real VM code: 6 interlocking
      pieces in dependency order, with a safe-first build order (Slice A = inline-lambda `foldLeft`, flag-gated
      off-by-default, differential-tested, measurable on a new `foldLeftLambda` bench → zero given/type-method
      risk; Slice B = `using`+`summon` plumbing; Slice C = type-method `.empty`/`.combine` opcodes → the
      `typeclassFoldMacro` win). KEY de-risking finding: the `using` arg is RESOLVED + APPENDED to the args
      array before invoke (`CallRuntime.bindArgs` ~430), so a compiled `combineAll` just gets the monoid as a
      trailing ref param. HARD WRINKLE: `summon[M].combine`/`.empty` are NOT InstanceV fields — they resolve
      via `lookupTypeMethod(typeName, name)` (DispatchRuntime:3180) + `invokeTypeMethod` (binds `this`+fields),
      so the per-element call is a type-method invocation needing a new `TMLOOKUP` opcode, not a bare CALLREF.
      Deliberately NOT one-shot: the JIT is on every hot path (silent-wrong-result risk), and the payoff is a
      synthetic bench (1.14 ms → ~0.1–0.3 ms). Next: build Slice A as a focused effort. (Prior history below.)
- [x] ~~**hof-glue-jit-compile** (history)~~ — **RESOLVED 2026-06-18 → DEFERRED to the dual-bank `LExpr` VM roadmap
      (closed; stop re-investigating in isolation).** Re-measured on current main: `typeclassFoldMacro` =
      **1.142 ms/op** vs `typeclassFold` = **0.005 ms/op** — the statically-typed fold fully JITs; the 228×
      gap is purely the macro version's per-call given/summon glue. The −10.5% fused fast-path is intact and
      `foldLeftReusing` (CallRuntime:212) already runs the fold as a native loop calling the bytecode-JIT'd
      `combine` per element, so loop+combine are fast. The ONLY remaining lever is whole-function JIT of
      `combineAll`, needing List-iteration opcodes in SscVm + a `foldLeft` recognizer in VmCompiler +
      `using`-param/given-member-access support in the JIT — a large architectural effort gated on the
      dual-bank `LExpr` VM work, risky (JIT is on every hot path). Big win is *possible* but it rides that VM
      roadmap; NOT a bounded slice. History below.
- **hof-glue-jit-compile** (history only; not claimable) — deep; reframed from `hof-dispatch-cpu-devirt`, investigated
      2026-06-13) — **PARTIAL interp slice landed 2026-06-13** (fused curried
      `List.foldLeft(z)(g)` fast-path in `evalApplyGeneral`: `typeclassFoldMacro` 1.259 → 1.127
      ms/op, **−10.5%**; `FusedFoldLeftTest`). The **full lever is still open.**
      `typeclassFoldMacro` (`combineAll[A: Monoid]` = `xs.foldLeft(empty)(combine)`, 300×).
      Investigation (spec `direct-style-eval-spec.md` §11.3) proved there is **no targeted
      ≥15% *devirt* win**: the inner `combine` is already bytecode-JIT'd (JIT on/off = 1.26 vs
      3.80 ms, 3×), and a fresh JFR CPU profile shows **78% leaf = `evalCore`** self-time (the
      megamorphic `term match`), with *no* devirtualizable callee — `trackPos` no-op and a
      `FunV` JIT-Entry cache (kill the `synchronized` `entryFor` lookup) both measured **0%**.
      The cost is the 300× tree-walk of `combineAll`'s HOF glue (the `foldLeft` Apply + the two
      `summon[Monoid[A]].{empty,combine}` Selects); the fused fast-path shaved the `foldLeft`
      dispatch portion (−10.5%) but the body is still re-interpreted 300×. The remaining lever
      is **compiling that glue**: `combineAll` bails the bytecode/VM JIT on the `foldLeft` HOF
      call (`call:no-compilable-target`, `VmCompiler.scala:521`). Closing it needs List-iteration
      opcodes in `SscVm` + a `foldLeft`-intrinsic recognizer in `VmCompiler` reusing the existing
      `CALLREF` opcode (the dual-bank `LExpr` roadmap, `project_dual_bank_lexpr`) so a
      `foldLeft`-with-a-runtime-monoid compiles to a tight loop. Large architectural effort, not
      a slice. A/B with `scripts/bench interp typeclassFoldMacro` (wall-clock).
      **Re-confirmed 2026-06-17 (perf-followups):** `CallRuntime.foldLeftReusing` ALREADY runs the
      fold as a native Scala `while` over a single reused `ReusableFrame2`, calling the
      bytecode-JIT'd `combine` per element (`JitRuntime.tryRun2`, CallRuntime.scala:221) — so the
      loop AND the combine are already fast. The residual is purely `combineAll`'s PER-CALL glue,
      tree-walked once per call: resolving the `using Monoid[A]` given + the two `summon`-member
      Selects + the `foldLeft` Apply dispatch. The only remaining lever is whole-function JIT of
      `combineAll` itself — which additionally needs **`using`-param + given-member-access support
      in the JIT** (not just a foldLeft recognizer). Confirmed DEFER: too large + too risky (JIT is
      on every hot path) for the ≤15% ceiling; revisit only with the dual-bank `LExpr` VM work.

- [x] **vectorize-pure-loop** — **RESOLVED 2026-06-18 → WONTFIX-until-a-motivating-workload (closed).**
      Confirmed on current main: `jdk.incubator.vector`/`LongVector` is referenced **nowhere** (truly
      unstarted), and `pureCallSum*` are computed by the Gauss closed-form in `walkLinearPoly`
      (EvalRuntime:1835/1872) — they **bypass the loop entirely**, so SIMD would help them 0%. There is no
      non-polynomial hot-loop benchmark that motivates it, and the cost (incubator `--add-modules`, ABI
      churn, tail-loop handling) is real. Do NOT build speculatively; revisit ONLY if a concrete
      non-polynomial pure-arithmetic hot loop appears as a real workload. Original sketch below.
- **vectorize-pure-loop** (history only; not claimable) — Use `jdk.incubator.vector.LongVector` inside
      `tryCompileWhileLong` to batch 4–8 lanes when the body is pure arithmetic
      on the counter. Expected 4–8× speedup on `pureCallSumIf` (if the recognized
      grammar for `walkLinearPoly` is extended) and similar shapes. `pureCallSum*`
      are now at the algebraic floor via Gauss; vector would help non-polynomial
      cases. Caveats: `--add-modules jdk.incubator.vector`, JDK incubator ABI
      churn, tail-loop handling for non-aligned N. Revisit after extending the
      closed-form recognizer or when a concrete non-polynomial bench motivates it.

## Quality / Contracts / Type System

These items come from the 2026-05-30 project-state review. They are intentionally
ordered to reduce risk: spec and hygiene first, broad implementation only after
the contracts are explicit.

- [x] **direct-style-eval** — **RESOLVED 2026-06-18 → WONTFIX (closed; data-disproven, do not start).**
      Re-confirmed on current main: `Computation.Pure` is constructed at **1261 sites** (even larger than the
      earlier ~530 estimate), and the allocation split is unchanged — `Pure` ≈16%, dispatch machinery ≈66%,
      which a direct-style `eval(...): Value` migration **does not touch**. So the wall-clock ceiling is below
      the ≥15% gate against a 1200-site, high-risk migration. The win these shapes want is JIT/devirt, not
      direct-style. Do NOT start without a real workload where `Pure` dominates a *tree-walked* path. Original
      below.
- **direct-style-eval** (history only; data-disproven, not claimable) — migrate `eval(...): Computation`
      to direct-style `eval(...): Value` to kill per-call `Pure` allocation. **Re-validated
      2026-06-13** (`specs/direct-style-eval-spec.md` §11.1): on the representative tree-walked
      workload `Computation.Pure` is only ~16% of allocation; the dispatch machinery (~66%)
      dominates and `evalDirect` doesn't touch it, so the wall-clock ceiling is below the ≥15%
      gate against a 530-site, high-risk migration. **Do NOT start** without a real workload
      where `Pure` dominates a *tree-walked* path. The win these shapes actually want is
      `hof-dispatch-devirt` (SPRINT) — pursue that instead.

## Architecture & Extensibility Roadmap (v1.x–v2.x)

Cross-cutting improvements to make ScalaScript easier to extend, consume, and
distribute — identified in the 2026-05-28 architectural review.  Ten themes
(A–J), roughly ordered by impact and risk.  Companion plan:
`~/.claude/plans/glowing-swinging-river.md`.

### Theme C — Distribution ecosystem (multi-channel, not Maven-only)

- [ ] **arch-distribution-p3** (DEFERRED: explicit publication go required) — First-party Maven Central publication
  (deferred; not queued):
  `project/Publishing.scala`; `io.scalascript` group ID unified; publish
  `scalascript-core`, `scalascript-runtime`, `sbt-scalascript` on tag push;
  sbt Plugin Portal registration. Deferred until Sergiy explicitly asks to
  publish to Maven Central, sbt Plugin Portal, or other official centralized
  repositories.  Spec: `specs/arch-distribution.md §5 Phase 3`.

### Theme D — sbt-scalascript plugin completion

### Theme E — `ssc new` + standalone installation

### Theme B — Build-time registry consolidation

### Theme A — Stable Plugin SPI

### Theme F — DSL platform hooks

### Theme H — Library Modularity

Identified 2026-05-28. Six concrete gaps in the library system: no multi-file
pure-ScalaScript package format, no transitive dep propagation, no access
control, namespace collision risk, no API lifecycle annotations, no versioning
enforcement.  Full analysis in `specs/arch-library-modularity.md`.

### Theme I — Package Registry (discoverability)

Identified 2026-05-28. Without a registry the ecosystem cannot grow: users
cannot find libraries, authors cannot reach users.  Current solution: in-repo
catalog + GitHub Pages project site, zero server infrastructure, PR-based
publishing. Custom domain/governance can layer on later.
Full spec: `specs/arch-registry.md`.

### Theme J — Lightweight FFI (@jvm / @js + glue.jar)

Identified 2026-05-28. Community libraries cannot call Java or JS APIs today —
only `std/` plugins can.  Two-tier FFI closes the gap without requiring a full
`BackendRegistry` plugin.  Full spec: `specs/arch-ffi.md`.

### Theme G — Metaprogramming v2.x (deferred)

---

## Blockchain SPI — chain abstraction for x402 + wallet

Spec in [`specs/blockchain-spi.md`](specs/blockchain-spi.md). Defines a
shared chain-abstraction layer (`ChainAdapter` / `ChainId` / `Asset`
/ `TypedData` / `recover` / queries) consumed by both `wallet-*` and
`x402-*`. Sits above a lower-level `crypto-spi` (BouncyCastle on JVM,
`@noble/curves` on Scala.js).

Fixes four concrete bugs in current x402:

- `EvmFacilitator.verify` never checks the signature
  (`x402-facilitator-evm/.../EvmFacilitator.scala:23-38`)
- `EvmFacilitator.settle` returns `0x00…00` as stub tx hash
  (`:40-43`)
- Hand-coded `0x70a08231` selector for `balanceOf` (`:32`)
- x402-client SHA-256 stubs (companion fix in
  [`specs/wallet-spi.md`](specs/wallet-spi.md))

### Phase 0 — Spec ✓ Landed (2026-05-19)

### Phase 1 — SPI + crypto + blockchain-evm minimum + x402 facilitator verify fix ✓ Landed (2026-05-19)

  - [ ] `EvmFacilitator.tokenBalance` to use blockchain-evm typed
        proxy — deferred to Phase 2 (depends on full ABI codec)
### Phase 2 — blockchain-evm full ChainAdapter + real x402 settle ✓ Landed (2026-05-19)

Shipped as four slices: RLP+broadcast (29344e6), ABI codec
(3679e68), typed Erc20 proxy + event decoder (a97e7e6), real
relayer-backed x402 settle (cbec71c). ~40 new tests, full Phase 1
regression test green.

  - [ ] End-to-end Anvil integration test deferred — mock-RPC test
        exercises the exact JSON-RPC sequence an Anvil node would
        receive; real network round-trip is a follow-on slice.

### Phase 3 — blockchain-solana ✓ Landed (2026-05-20)

### Phase 4 — Scala.js CryptoBackend ✓ Landed (2026-05-20)

### Phase 5 — blockchain-bitcoin ✓ Landed (2026-05-27)

### Phase 6 — blockchain-cardano + x402 Cardano facilitator ✓ Landed (2026-05-20)

### Phase 7 — blockchain-cosmos ✓ Landed (2026-05-27)

---

## Wallet SPI — Scala.js cross-compile ✓ Sprint complete (2026-05-20)

Spec in [`specs/wallet-spi-scalajs.md`](specs/wallet-spi-scalajs.md).
Six-stage migration that takes the wallet-spi track from JVM-only to
JVM + Scala.js so the same SPI artefacts power browser PWA wallets,
in-page dApp connectors (EIP-1193 / WalletConnect / Solana Wallet
Standard), and the x402 client in a browser context. Builds on the
existing wallet-spi (§ "Wallet SPI — key management + dApp
connectivity") which lands the JVM side first.

### Stage 1 — Plugin setup + cross-compile wallet-spi ✓ Landed (2026-05-20)

### Stage 2 — Scala.js CryptoBackend (crypto-noble-js) ✓ Landed (2026-05-20)

Resolves the `Scala.js registry pattern` open question
([`specs/wallet-spi.md`](specs/wallet-spi.md) §11.1) — first impl module
that registers itself through the Stage 1 cross-platform
`object CryptoBackend.register(...)`.

### Stage 3 — Strategy + connector cross-compile ✓ Landed (2026-05-20)

### Stage 4 — `wallet-strategy-erc4337` cross-compile ✓ Landed (2026-05-20)

### Stage 5 — `wallet-vault-encrypted` cross-compile ✓ Landed (2026-05-20)

Stage 5a — light up the deferred KDF + AEAD primitives in
`crypto-noble-js`:

### Stage 6 — `wallet-connect` cross-compile ✓ Landed (2026-05-20)

Stage 6a — extend `CryptoBackend` SPI with the primitives WC needs
(additive only — no existing-method breakage):

- [ ] **Real browser-WebSocket integration testing** (BLOCKED: real browser + WalletConnect relay/project) — JS tests
      currently mock `BrowserWebSocket` (Node has no native
      `WebSocket` in the test runtime).  Live integration against
      `wss://relay.walletconnect.com` lands in the future PWA-wallet
      sprint that surfaces WC v2 in an actual browser.

Sprint closure: every wallet-spi-track module that has a JS-relevant
surface now cross-compiles JVM + Scala.js.  All future
`CryptoBackend` implementations are mandated to implement
ChaCha20-Poly1305, X25519, and the Stage 5 AEAD / KDF set in
addition to the original signing / hash / KDF surface — see
[`specs/wallet-spi-scalajs.md`](specs/wallet-spi-scalajs.md) §6 for
the full SPI checklist a new backend has to cover.

---

## Wallet SPI — key management + dApp connectivity

Spec in [`specs/wallet-spi.md`](specs/wallet-spi.md). Sits above
blockchain-spi. Two extension axes: key management (`Vault` /
`RawSigner` / `AccountStrategy`) and dApp connectivity
(`DappConnector`: EIP-1193, Wallet Standard, WalletConnect v2).

Replaces the SHA-256 stub in `x402-client.PrivateKeyWallet` with real
secp256k1 ECDSA via an adapter shim — x402's public API is unchanged.

### Phase 1 — Skeleton SPI + EOA strategy + x402-client shim ✓ Landed (2026-05-19)

Landed in tandem with blockchain-spi Phase 1.

### Phase 2 — Encrypted Vault ✓ Landed JVM + Scala.js core (2026-05-20)

### Phase 3 — DappConnector EIP-1193 ✓ Scaffold landed (2026-05-20)

### Phase 4 — DappConnector WalletConnect v2 (scaffold landed 2026-05-20)

- [ ] **WC project-ID open question** (DEFERRED deployment config) — still pending; the transport
      accepts a `projectId` argument on both platforms but CI does
      not yet provision one. To resolve before first production
      deployment.

### Phase 5 — Solana DappConnector ✓ Landed (2026-05-27)

### Phase 6 — ERC-4337 SmartAccountStrategy ✓ Landed (2026-05-20)

### Phase 7 — Hardware wallet Vault (Ledger multi-chain)

Architecture in [`specs/wallet-spi.md`](specs/wallet-spi.md) §5.1. One
device, one seed, per-chain on-device apps; the Vault routes
`getSigner(curve, path)` to the right active app and surfaces
`AppSwitchRequired` to the host when the user must change apps.

### Phase 8 — MPC Vault

- **FROST-Ed25519** → MOVED TO SPRINT 2026-06-23 (Sergiy "внеси в спринт"; active queue). Threshold Ed25519
      (FROST) signing as a `walletVaultMpcFrost` variant is now active work. (Other future MPC variants stay
      deferred until a concrete use case/partner.)

---

## Strategic-review proposals (2026-06-15)

The feature roadmap is built out (729/740 done, 127 conformance cases, ~70 property/fuzz suites,
comprehensive docs). These are the higher-leverage *productization/hardening/enablement* directions.
The two active ones are in SPRINT (`compile-time-at-scale`, `xbackend-property-equivalence`).

- [x] **real-workload-perf** ✓ DONE 2026-06-20 (all three axes have harnesses + baselines) —
      micro-throughput is at floor; this is the real-workload axis.
      **(a) cold-start ✓ DONE 2026-06-20:** built `tests/perf/coldstart/` (pure-bash harness, no
      scala-cli/bloop → can't hang) measuring fresh `ssc run` wall-clock + peak RSS. Baseline ~378 ms /
      167 MB (JVM boot ~36 ms + classloading the 88 MB fat jar dominate). **Cut shipped:** AppCDS in
      `bin/ssc` + `install.sh` (`-XX:+AutoCreateSharedArchive`, auto-created first run, no build step,
      CDS-only — NOT TieredStopAtLevel which would hurt long-running `ssc serve`) → **378 → 182 ms (−51%)
      + peak RSS 167 → 114 MB (−32%)**; opt out `SSC_NO_CDS=1`. GraalVM native binary needs no CDS.
      **(b) steady-state RSS + (c) GC under load ✓ DONE 2026-06-20:** built `tests/perf/serverrss/` (boots
      a real `health-defaults.ssc` server on the JVM interp at `-Xmx512m` + GC log, drives concurrent load,
      samples RSS, reports footprint + start→end drift (leak signal) + GC pauses/time; pure bash, reliable
      teardown). Baseline (20s/4 loops, JDK 21): the interp server settles at **~195 MB RSS, STABLE** —
      ramps ~184→~195 MB then plateaus (no leak), **light GC** (~41 short pauses / 27 ms). Verdict flips to
      GROWING if drift >20%. **All three axes now have harnesses + baselines.** Complements
      `compile-time-at-scale` (the remaining unmeasured axis). Genuine open follow-up: a *long* (minutes)
      leak-hunt run is left to demand (the harness supports `secs=300+`).
- [x] **xbackend-property-equivalence (full suite)** ✓ DONE 2026-06-20. **Broaden:** already complete —
      the generator is at **12 kinds** incl. arith/List/match/enum/String/case-class/Option/Either/closures/
      nested-coll/string-ops/**effects** (the "REMAINING" list was stale); node leg verified 74 programs,
      interp==JS, 0 skipped. **CI-wired:** the `sbt` CI job had only Java+sbt so `CrossBackendPropertyTest`
      SKIPPED (assume node/scala-cli) — added Node.js setup so the interp==JS differential now runs in CI.
      **Made CI-safe first** (see `xbackend-test-hardening`): `ProcTestUtil.runCaptured` gives the subprocess
      runner a hard timeout that actually fires + deadlock-free stream draining, so a wedged scala-cli/node
      fails fast instead of hanging the job. The interp==JVM(scala-cli) leg stays gated (Conformance job
      covers it). Definitive cross-backend guarantee now standing in CI.
- [x] **registry.scalascript.io (remote package registry)** ✓ DUPLICATE — consolidated into the
      `remote-package-registry` item above. Keep the concrete registry-domain discussion there.
- **demand-driven-from-busi** (ongoing signal, not a claimable task) — the `busi` rozum channel is the live
      testbed and the highest-signal priority source; it is currently quiet. Proactively building one
      comprehensive real app (or asking busi what's painful) surfaces the gaps that matter more than any
      speculative backlog item. Keep sweeping the room per the rozum skill.

## Completed milestones — archived 2026-06-15 (detail in CHANGELOG.md + git history)

- Language Surface — Markdown Frontend from Content
- Codegen-time perf — jvmGen ~100× slower than jsGen (survey 2026-06-14)
- JS Codegen Performance
- Conformance Fixes — cross-backend gaps (2026-06-02)
- Tooling
- UUID Library — v1.65
- Crypto primitives — v1.66 ✓ DONE
- Codebase Maintenance / Architecture Hygiene
- Exact Numerics — BigInt, Decimal, Money (v1.64 ✓ DONE — all phases landed 2026-06; verified 2026-06-14)
- Distributed Runtime (v1.63 planned)
- Distributed Wire Protocol (v1.62 planned)
- Compiler extensibility roadmap
- Recommended implementation sequence
- v0.7 — Reusable libraries and packaging
- v0.13 — Component theming variants
- v1.12 — Typed Algebraic Effects
- v1.51 — Streams with Backpressure
- v1.52 — Deploy to Hostings, Clouds & Kubernetes-like Environments
- v1.53 — Traditional Payment Processors
- v1.60 — Tuple Monoid ✓ Landed 2026-05-28
- v1.61 — Performance & Memory Optimization
- Interpreter performance — next phases (post VM 2a)
- v1.55 — First-class XML / Generic Markup
- v2.1 — Distributed Streams (Beam-style)
- v2.0 — Separate compilation of modules
- Interpreter ergonomics — carried over from v1.1
- Known issues / latent flakes
- CLI — native binary (GraalVM native-image)
- Optimization and modularity roadmap
- Scala ↔ ScalaScript interop — Tiers 1 + 2 landed
- Next wave — post-v1.24 plan
- Beyond
- Speculative — Smart contracts backend
- Speculative — Apache Spark backend
- v1.26 — `sql` fenced code blocks (JDBC)
- v1.27 — browser-side SQL (sql.js / DuckDB-Wasm)
- Infrastructure clients — general-purpose ScalaScript libraries
- x402 — HTTP payment protocol
- MCP × x402 × Wallet — agentic payments
- Micropayment Platform — channel-based fee amortisation for microtransactions
- v1.30 — `@side=client|server` for SQL blocks in full-stack modules
- OpenAPI 3.1
- GraphQL
- v1.48 — SwiftUI Native Frontend (iOS + macOS)
- v1.48.1 — `ssc run` one-command wrapper for SwiftUI targets
- v1.48.2 — `ssc run --target ios` (iOS Simulator)
- v1.48.3 — `ssc run --target ios --device` (real device via ios-deploy)
- v1.48.4 — `ssc package --target ios` → distributable .ipa
- v1.48.5 — `ssc publish --target ios` (TestFlight + App Store via fastlane)
- v1.49 — macOS distribution: notarize + DMG + Mac App Store
- v1.65 — `ssc emit --frontend swiftui` pathway ✓ Landed 2026-06-02
- v1.66 — SwiftUI typed JSON models (`@model` + `FetchJsonSignal`)
- Backend-specific fenced blocks + platform-type ban
- std.fs / std.os / std.process — filesystem, OS & process abstraction
- Requested by busi (real testbed) — 2026-06-09

## Rust multi-shot effects (R.6) — unbounded loop-depth follow-up (2026-06-22)

Bounded Rust multi-shot support has landed: Tier-1 List (`effect-multishot` bench), Tier-1 Option, and
Tier-2 static-depth general handlers all cargo-run. The deferred remainder is narrower: support a `perform`
inside a loop or other shape where the number of continuation nests is not statically known. That likely needs
the explicit defunctionalized trampoline sketched in `specs/rust-effects.md §11`. No current benchmark/example
requires it; keep it in BACKLOG until a real consumer appears.

## security-hardening follow-ups (2026-07-12) — from specs/security-hardening.md

The implementable audit findings landed (see CHANGELOG / git `security-hardening`).
These remain, each needing its own slice:
- **M10 confined-fs API** — `readFileWithin(root, path)` family (normalize + startsWith(root) +
  NOFOLLOW) as new externs across backends; raw fs helpers stay trusted-input-only. Needs a spec.
- **H4-full artifact signing** — HMAC/sign `.scjvm`/`.scjs`/`classBundle` with an install-private
  key (cheap dir-permission half already landed).
- **L8 cross-backend conformance** — shared suite pinning identical fs/process/http semantics
  (deleteFile, redirects, timeout, cwd/env, listDir order) across JVM/JS/Rust/interp.
(M2-JS + M3-JS both done — JS client now matches JVM/interp/Rust on redirects + body cap.)
  (manual mode returns an opaque response; needs a response.url host re-check).
- **exec opts-wiring (Rust/JS)** — interp DONE (git). Rust `_exec<O>` is generic (needs codegen special-case to read struct fields); JS needs Option/Map unwrapping in the runtime. Both remain.
  wire them so M4/L3 apply on those lanes too.
