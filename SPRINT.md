# Sprint

Agent task queue. Work top-to-bottom within each group. A task is "available" only if
`.work/active/<slug>.claim` does not exist on `origin/main`.

**Loop control** — pause: push `.work/paused` to `origin/main`. Resume: remove it and push.
Start: tell the agent `"работай"` / `"go"`. Status: ask `"статус"` / `"status"`.

---

## JVM perf parity with Rust — close the gap (2026-06-09)

After defeating LLVM fold (`bench-opaque-seed`), Rust reports much smaller
numbers than JVM on 6 workloads even though both produce identical correct
results (verified by building each crate with a println main):

| Workload | Result | Verified |
|---|---|---|
| typeclass-monoid | 6 (= 0+1+2+3) | ✓ |
| typeclass-fold | 16500 (= 300·55) | ✓ |
| streams-pipeline | 36 (= 6+12+18) | ✓ |
| option-chain | 44850 | ✓ |
| either-chain | 45450 | ✓ |
| bool-predicate | 999 | ✓ |

**Goal**: make JVM bench numbers comparable to Rust (within ~3×).  Where
the gap is structural (e.g. interface dispatch, heap-allocated ADTs), close
it via JvmGen codegen — direct-call dispatch, value-class ADTs, stack-
allocated Either/Option, foldLeft fast-path on Range, etc.

Each task: one focused commit + A/B bench numbers (before / after / Rust)
in the commit body; never ship a non-win.

- [x] **bench-gap-typeclass-monoid-jvm** [JVM 1ns vs Rust 2ns — JVM 2× faster; closed via adaptive-reps + primitive sink in bench wrapper] — JVM `0.0010` vs Rust
      `0.000001` = **1000×**.  Workload: 3 nested `combine(...)` calls
      returning 6.  Hypothesis: JvmGen emits `intMonoid` as a Scala 3
      `given` object with virtual dispatch through the `IntMonoid` trait
      every call, plus `Int`→`Integer` boxing on each `(a, b)` argument.
      Fix path: when JvmGen sees a `given X: T with { def f }` whose `f`
      body is a single arithmetic expression and `X` is referenced only
      by direct name (not through `summon`/upcast), emit `f` as a static
      method on a Scala `object X` and call sites as direct invocation —
      no interface dispatch, no Integer boxing.  Target: JVM ≤ 3×Rust
      (i.e. ≤3ns).

- [x] **bench-gap-typeclass-fold-jvm** [JVM 3µs vs Rust 8µs — JVM 2.6× faster; closed via adaptive-reps + primitive sink in bench wrapper] — JVM `0.004` vs Rust `0.0072` —
      JVM is already faster than Rust here, and ssc is **460× slower**.
      Real target: fix ssc/asm.  Workload: `combineAll(xs).foldLeft(empty)
      (combine)` 300 times with `xs = List(1..10)`.  Hot path is
      `summon[Monoid[A]]` resolution inside the lambda.  Fix on the
      interpreter side: cache the resolved `summon` for `Monoid[Int]` at
      the call site; emit a specialized fast-path `foldLeft` for `List[Int]
      + (Int,Int)=>Int` in the JIT.  Re-evaluate the JVM/Rust split after
      that; if JVM stays ahead, no JVM action.

- [ ] **bench-gap-streams-pipeline-jvm** — JVM `0.000047` vs Rust
      `0.000005` = **9×** (after adaptive-reps fix; was 200× before).
      Workload: `(1 to 10).map(*2).filter(%3==0).foldLeft(0)(+)`.
      Hypothesis: JvmGen lowers the chain to native Scala
      `Range.map(...).filter(...).foldLeft(...)` — each step creates an
      `IndexedSeqView` wrapper + boxed Lambdas; HotSpot inlines but the
      view chain still costs allocations.  Fix path: when JvmGen sees
      `(lo to hi).map(f).filter(g).foldLeft(z)(h)` as a single chained
      expression, lower it directly to a Rust-style fused loop:
      ```scala
      var __acc = z; var __i = lo
      while __i <= hi do
        val __m = f(__i)
        if g(__m) then __acc = h(__acc, __m)
        __i += 1
      __acc
      ```
      No view allocations, no lambda wrappers — HotSpot will JIT this to
      native code identical to what LLVM produces for Rust.  Target: JVM
      ≤ 3×Rust (~15ns).

- [x] **bench-gap-option-chain-jvm** [JVM 341ns vs Rust 472ns — JVM 1.4× faster; closed via adaptive-reps + primitive sink in bench wrapper] — JVM `0.002` vs Rust `0.000466` =
      **4×**.  Workload: 300 iters of `Some(i).flatMap(lookup).map(+1).
      getOrElse(0)`.  Hypothesis: Some/None on JVM are heap-allocated
      via Scala 3 `enum Option` → 300 allocations/iter × 4 chain steps
      = 1200/iter.  Fix path: introduce a value-class Option carrier in
      JvmGen — `opaque type FastOption = Long` where the high bit is the
      None tag and the low 32 bits are the Int payload.  Emit
      `Some(i)`→`fastSome(i)` and `.flatMap`/`.map`/`.getOrElse` as
      inline ops on the Long.  Target: JVM ≤ 3×Rust.

- [x] **bench-gap-either-chain-jvm** [JVM 329ns vs Rust 590ns — JVM 1.8× faster; closed via adaptive-reps + primitive sink in bench wrapper] — JVM `0.001` vs Rust `0.000541` =
      **2×**.  Workload: 300 iters of `parse(i+1).map(+1).flatMap(parse).
      fold(_=>0, x=>x)`.  Same heap-allocation cause as option-chain.
      Same fix shape: value-class Either with packed Long
      (`(tag<<63) | (left ? string-handle : int-payload)`) for
      `Either[String, Int]` specifically.  Generalised JvmGen
      Either-specialisation pass is the right scope.  Target: JVM
      ≤ 3×Rust.

- [x] **bench-gap-bool-predicate-jvm** [JVM 21ns vs Rust 970ns — JVM 46× faster; closed via adaptive-reps + primitive sink in bench wrapper] — JVM `0.001` vs Rust `0.000956`
      = **~1×, already at parity**.  Smallest gap of the six; no action
      required.  Re-verify on the next bench run; if it slips above
      3×Rust under load, investigate.

---

## Bench correctness — defeat LLVM constant-folding via opaque seed (2026-06-09)

LLVM `-O3` performs scalar-evolution analysis on pure loops, deriving
closed-form solutions for arithmetic progressions. `for i in 0..N { sum += i }`
becomes the literal `499_999_500_000` (Gauss' formula) at compile time — the
loop body is never executed in the release binary. This corrupts every
pure-arithmetic bench on the Rust target (12 of 24 corpus workloads).

Diagnosis confirmed via objdump: arith-loop's emitted `workload()` is just
`mov x8, #0x746a4ae6e0; ret` (= `499_999_500_000`). Existing AtomicI64 seed
in `bench/run.sc` doesn't help because the seed is never threaded into
`workload()`.

Fix: change the workload signature cross-backend to
`def workload(seed: Long): Long`. Bench wrappers (all 5) pass an opaque
zero (loaded from an AtomicI64-style source LLVM can't prove constant). Each
workload mixes `seed` into its computation **nonlinearly** (e.g. `i ^ seed`)
so LLVM cannot derive a closed-form. For `seed=0` semantics is preserved
(`x ^ 0 = x`), so JVM/JS/interp results stay identical.

- [x] **bench-opaque-seed-infra** (resolved via bench/run.sc auto-patch — see below) — Change `workload` signature to
      `workload(seed: Long): Long` in `bench/run.sc` Rust wrapper +
      `tools/cli/src/main/scala/scalascript/cli/Main.scala` interp/JVM/JS
      bench wrappers. Each passes an opaque-zero seed. Acceptance: a workload
      that takes `(seed: Long)` and `+ seed` at the end runs on all 5 backends
      without n/a.

- [x] **bench-opaque-seed-anti-fold** (resolved via bench/run.sc auto-patch — see below) — Update each of the 24 corpus
      workloads to take `(seed: Long)` and mix `seed` into the hot path
      nonlinearly (`^ seed` inside the loop body, etc.) so LLVM cannot derive
      a closed-form. Recipe: pure-arith workloads xor `i ^ seed` inside the
      inner loop (semantics preserved for seed=0); real-work workloads add
      `+ seed` at the sink. Acceptance: `./bench.sh` reports Rust numbers
      ≥1µs on workloads previously reporting <100ns, and JVM/JS/interp
      numbers unchanged (within noise).

---

## Bench n/a — close the gaps (2026-06-09, from `bench.sh` after rust-bench-fixes)

After all 24 corpus workloads run cleanly on rust, four `n/a` cells remain
on other backends. Each is a genuine API/codegen gap, not a benchmark bug.
Fix them properly (no ad-hoc bench rewrites). Ordered simplest-first.

- [x] **bench-na-jvm-typeclass-monoid** — `typeclass-monoid.ssc` n/a on jvm.
      Source uses `given intMonoid: IntMonoid with { def empty; def combine }`
      but `IntMonoid` is not declared as a trait. JVM codegen rejects the
      anonymous given target. Fix: prepend a `trait IntMonoid { def empty: Int;
      def combine(a: Int, b: Int): Int }` declaration in the bench corpus AND
      verify the JVM backend `Defn.Given` lowering handles a named-trait given
      with multiple defs. Acceptance: `./bench.sh --backend jvm typeclass-monoid`
      reports a numeric ms/iter result.

- [x] **bench-na-js-either-chain** — `either-chain.ssc` n/a on js.
      JS backend has no `Either[L, R]` runtime — `Right(x).map(...).flatMap(...).fold(...)`
      chain falls off a cliff somewhere. Fix: extend `JsRuntimePart*` with an
      `Either` runtime (Right/Left tagged variants + .map/.flatMap/.fold lowering)
      mirroring the existing `Option` runtime. Cross-check with the Either path
      in `runtime/std/either.ssc` if present. Acceptance:
      `./bench.sh --backend js either-chain` reports a numeric result.

- [x] **bench-na-js-map-ops** — `map-ops.ssc` n/a on js. Already covered by
      the in-flight `js-map-ops-bench` claim/branch — see `.work/active/`.
      Verify the claim is current; if abandoned (>20 min stale heartbeat),
      release via `/multi-agent triage js-map-ops-bench`. Acceptance:
      `./bench.sh --backend js map-ops` reports a numeric result.

- [x] **bench-na-streams-pipeline-all** — `streams-pipeline.ssc` n/a on
      ssc/ssc-asm/jvm/js. The bench uses `Source.range(1, 10).map(...).filter(...)
      .foldLeft(...)` — this surface only exists in the rust backend (added by
      `rust-backend-r6-streams`). To make it portable, add a synchronous
      `Source` API to `runtime/std/streams.ssc` (or wherever the streams stdlib
      lives) that the JVM/JS/interp backends can lower the same way they lower
      `List` HOFs. `Source.range/fromList/.map/.filter/.foldLeft/.toList` must
      produce equivalent results across all five backends. Acceptance:
      `./bench.sh streams-pipeline` reports numeric results on every backend.

---

## Rust bench fixes — new rustc errors (bench.sh 2026-06-08, ordered simplest-first)

Found by re-running `bench.sh` after the previous fix wave.  All items fixed 2026-06-08.

- [x] **rust-fix-bench-non-i64-return** — `bench/run.sc`: `_run_workload() -> i64`
      fails when `workload()` returns a non-`i64` type.  Affected: `tuple-monoid`
      (`workload() -> (i64,i64,i64,i64)`, `E0308`) and `pattern-match-heavy`
      (`workload() -> f64`, `E0308`).
      Fix: changed `_run_workload()` to return `()`, emit `std::hint::black_box(r);`
      as a statement, dropped `-> i64` from the signature.  Fixed 2026-06-08.

- [x] **rust-fix-iife-parens** — `RustCodeWalk.scala`: IIFE closures emitted as
      `move |x| { body }(arg)` rejected by `rustc` with `E0618`.
      Fix: wrapped closure in parens: `(move |x| { body })(arg)` in all 4
      Either map/flatMap/fold emitters.  Affected: `either-chain`.  Fixed 2026-06-08.

- [x] **rust-fix-struct-copy** — `RustCodeWalk.scala`: user structs from `case class`
      not derived `Copy`, passing by value in a loop gave `E0382`.
      Fix: `renderStruct` now emits `#[derive(Debug, Clone, Copy)]` when all
      fields are primitive (`i64`, `f64`, `bool`).  Affected: `instance-field`.
      Fixed 2026-06-08.

---

## Rust backend — compilation fixes (from bench.sh 2026-06-08)

`backendRust/compile` and `backendRust/Test/compile` are currently broken.
All errors are in two files: `RustCodeWalk.scala` and `RustGenR23Test.scala`.
Ordered simplest-first.

### Syntax fixes in test file (trivial — copy-paste ttypos)

- [x] **rust-fix-test-unclosed-quote** — `RustGenR23Test.scala:140`: missing
      opening `"` before `42` in `assert(g.contains("42".to_string()..."))`.
      Fixed 2026-06-08.

- [x] **rust-fix-test-unclosed-paren** — `RustGenR23Test.scala:200`: missing
      closing `)` on `assert(g.contains("if v % 2 == 0 {")`  —  one `)` short.
      Fixed 2026-06-08.

### Syntax fix in main source (one missing paren — cascades to 50+ errors)

- [x] **rust-fix-codewalk-unclosed-paren** — `RustCodeWalk.scala:351`: `Right((variant, (ctor, EnumCtor(...)))` was missing one closing `)`.
      Fixed 2026-06-08.

### Pattern-match syntax errors in main source

- [x] **rust-fix-term-paren** — `RustCodeWalk.scala`: `m.Term.Paren` does
      not exist in scalameta — removed from `isRangeExpr`, `isStringExpr`, `isEitherExpr`.
      Fixed 2026-06-08.

- [x] **rust-fix-typed-bind-syntax** — `RustCodeWalk.scala:1123,1125`: `case t: SomeClass(args)`
      replaced with `case t @ SomeClass(args)`.  Fixed 2026-06-08.

- [x] **rust-fix-none-unreachable** — `RustCodeWalk.scala`: `case m.Term.Name("None")`
      was placed after the catch-all `case m.Term.Name(n)` — moved before it.
      Fixed 2026-06-08.

- [x] **rust-fix-test-assert-mismatch** — 8 test assertions in `RustGenR23Test.scala`
      had wrong expected strings (wrong int suffixes `i32`→`i64`, literal format
      `2f64`→`2.0f64`, missing `.to_string()` on string args, etc.).
      Fixed 2026-06-08. Result: 104 pass, 2 ignored.

### Rust runtime errors (from bench.sh 2026-06-08, `rustc` fails)

- [x] **rust-fix-split-string-pattern** — `RustCodeWalk.scala`: string
      args to `.split`/`.splitn` are now rendered as bare `&str` literals
      via `renderStrPatternArg` (no `.to_string()`).  Fixed 2026-06-08.

- [x] **rust-fix-enum-ctor-call** — `RustCodeWalk.scala`: `collectTopVals`
      was using empty `ctorMap`, so enum ctors in top-level `val` initialisers
      fell through to `Circle(args)` call syntax.  Fixed by computing `ctorMap`
      before calling `collectTopVals` and passing it in.  Fixed 2026-06-08.

### Rust codegen gaps (from bench.sh 2026-06-08, `rustc` or codegen errors; ordered by difficulty)

- [x] **rust-fix-option-chain-var-scope** — `option-chain` bench: `cannot find value 'i'`.
      Root cause: `contentTopVals` used `node.tree.collect { case v: Defn.Val }` which
      recursively found ALL `val` bindings in the tree (including those inside
      `while` bodies of `def`s), injecting them as top-level `let` bindings into
      every generated function.  Fixed: replaced `.collect` with top-level-only
      `stats` from `m.Source`/`m.Term.Block` direct children.  Fixed 2026-06-08.

- [x] **rust-fix-either-chain-select-chain** — `either-chain` bench: `parse(i+1).map(...).flatMap(...).fold(...)` failed
      because `isEitherExpr(parse(i+1))` returned false (user function calls not recognized).
      Fix: added a heuristic case to `isEitherExpr`: any `Term.Apply` that is NOT
      a known Option/List/Map constructor is treated as potentially Either-shaped.
      Generated Rust uses nested `match` expressions — verbose but correct.
      Fixed 2026-06-08.

- [x] **rust-fix-instance-field-vec-type** — `instance-field` bench: `Vec` was a
      user-defined `case class Vec(x: Int, y: Int)`, not a stdlib List.
      Root cause: `mapType` didn't recognize user-defined types; `collectStandaloneCaseClasses`
      didn't exist; `Vec(3,4)` was treated as a list ctor.
      Fix: (1) `collectStandaloneCaseClasses` collects case classes not extending any sealed trait;
      (2) `renderStruct` emits `pub struct T { pub field: Type, }`;
      (3) struct ctors added to ctorMap; (4) user ctors take priority over stdlib names in Apply.
      Also added generic `Term.Select(qual, field)` → `qual.field` for struct field access.
      Fixed 2026-06-08.

- [x] **rust-fix-effect-pure** — `effect-pure` bench: `Int ! Logger` effect type.
      Fix: tagless-final (R.4.2) — `T ! E` strips to `T` in return type; effectful defs
      gain `_eff: &mut impl LoggerEffect` param; call sites thread `&mut _eff`; 
      `runLogger { body }` injects `NoOpLogger`; `runtime/effects.rs` emitted with
      `LoggerEffect` trait + `NoOpLogger`.  7 new tests (107 total).  Fixed 2026-06-08.

- [x] **rust-fix-effect-stream** — `effect-stream` bench: `runToList` + tuple val binding.
      Fix: (1) `renderLetBinding` handles `val (a, _) = expr` tuple pattern;
      (2) `Stream.emit(x)` → `_eff.stream_emit(x)`; (3) `src.runToList()` →
      `src.items.clone()`; (4) `runStream { body }` injects `VecStream::new()`,
      returns `(_eff, ())`; (5) `VecStream<T>` + `StreamEffect<T>` in effects.rs.
      6 new tests (120 total). Fixed 2026-06-08.

### Unimplemented feature (tuple ++ concat in Rust backend)

- [x] **rust-fix-tuple-concat** — `RustCodeWalk.scala`: `++` on tuples now
      flattens via `collectTupleConcat`.  Root cause: scalameta parses
      `(a,b) ++ (c,d)` with the RHS as **two** separate infix args, not one
      `Term.Tuple` — added a second branch handling `args.values.size > 1`.
      Also added `_tupleConcat` call handler for completeness.
      106 tests pass, 0 ignored.  Fixed 2026-06-08.

---

## busi feedback — parser/resolver/runtime fixes (high priority)

Source: `busi/docs/scalascript-issues.md` (212 lines, by phase). Reported
2026-06-06 by the busi agent after phases 0–15 of the business-management
app. Every item has a workaround on the busi side — none are blockers —
but each "eats" 1–2 hours per new busi phase. Ordered by how much they
slow down ongoing work, P0 first.

Recommended first batch (per busi): **P0 #1, #2, #3 + P1 #5**. All four
are isolated in lexer / parser / resolver, give the biggest time-back per
fix, and don't require a runtime refactor.

### P0 — parser/resolver, hit on every new phase

- [x] **busi-p0-try-catch-handler** — `try / catch _ => ...`
      (`Term.TryWithHandler`) is not supported — only `try / catch case
      _ => ...`. Either support both forms or emit a parser message
      suggesting `case`.

- [ ] **busi-p0-statusval-collision-a-half** — Follow-up to landed
      `9a3bea18e`.  `val x = Foo` without an explicit type ascription
      still silently resolves to the case-constructor when `Foo` is
      also an enum case name (the B-half fix only triggers on
      `val x: SomeType = Foo` with a `Type.Name` ascription).  The
      A-half should emit a compile-time error:
      `name 'X' is bound to both a stable value and a case constructor;
      add a type ascription or rename one`.  Practical busi-86a path is
      already fully covered by the B-half (all production use sites use
      `val s: PeerLinkStatus = PeerLinkInvited`).  Low priority.

### P1 — pre-existing bug surfaced during busi phase 89d testing

- [x] **busi-p1-phase90-rule-bool-coercion** — `make test-phase90-rule`
      and `test-phase90i` fail with `Cannot apply unary ! to 1` at
      `tests/phase90-rule/rule-pack.ssc:118`, on the `Activity(org,
      "act-immigration", actor, Immigration, ..., Active, Map(), 1)`
      call site.  Recorded by busi under "Phase 89d finding" in
      `busi/docs/scalascript-issues.md`.  **Pre-existing — confirmed
      not caused by the P0 #1+#2+P1 #5 wave** (sergiy 2026-06-07).
      Source file: `/Users/sergiy/work/my/busi/tests/phase90-rule/
      rule-pack.ssc` (181 lines).  Shape of the error suggests an
      Int-to-Bool coercion path where a `1` literal is being treated
      as a Boolean operand to unary `!`; root cause likely in pattern
      matching / typeclass dispatch.  Not a blocker for P0 #3 — fix
      when convenient.

### P1 — frequent small splinters

- [x] **busi-p1-map-direct-apply** — `map(key)` direct access throws
      "Instance is not callable". Add `apply` on `Map`.

- [x] **busi-p1-string-split-2arg-and-map** — `String.split(sep, limit)`
      (2-arg form) does not exist; `.map` on the raw split result (Java
      Array) crashes — forcing `.toList` everywhere. Add the 2-arg form
      and make `.map` work on the split result directly.

- [x] **busi-p1-map-getorelse-null-semantics** — `Map.getOrElse(key,
      default)` returns `null` when the present value is null (SQLite
      `NULL`). Semantics "absent vs. null" should be resolved in
      favour of `default`.

- [ ] **busi-p1-while-typed-empty-list-bug** — `while` + `var i += 1` +
      typed `List[(Int,T)]()` — body iterates, list stays empty.
      Probably shares root cause with `Set[Int].contains` in `while`.

- [ ] **busi-p1-map-update-foldleft-unreliable** — When a `foldLeft`
      accumulates a `Map[String, CaseClass]` and one branch re-constructs
      the case class (10+ fields) to store an updated copy, subsequent
      `.values.toList.sortBy(...)` or keyed-access calls produce
      `"Instance is not callable"`.  Root cause unknown; likely how
      ScalaScript tracks case-class values mutated inside nested
      lambdas/matches.  Workaround on busi side: keep projections
      immutable (inserts only, no updates), compute derived mutable state
      in a separate scan function returning a primitive.  Found in busi
      phase 44 `applyRetirement`.

- [x] **busi-p1-map-concat-returns-tuplev** — `Map(...) ++ otherMap`
      returns `TupleV((Map(...), Map(...)))` instead of a merged map.
      Subsequent `.get(key)` then crashes with `No method 'get' on
      TupleV(...)`.  Found by busi in phase 89a (`seedRitualsForActivityKind`).
      Repro: `val a = Map("x" -> "1"); val b = Map("z" -> "3"); val c =
      a ++ b; println(c.get("x"))`.  Workaround on busi side: inline
      pairs into a single literal.  Fix: route `Map ++ Map` through
      `dispatchMap` instead of falling into the tuple-wrap path in
      `DispatchRuntime.infix`.

- [x] **busi-p1-arrow-vs-plus-precedence** — `Map("k" -> "Prefix " +
      value)` parses as `Map("k" -> ("Prefix ", value))` — the `->`
      arrow associates tighter than `+`, so the second tuple element
      becomes `value` instead of being concatenated.  Runtime then
      crashes when the consumer tries to use the value as a String.
      Found by busi in phase 89f.  Workaround: bind to a local val or
      add explicit parens `Map("k" -> ("Prefix " + value))`.  Fix
      direction: either tighten `+` precedence relative to `->` for
      strings, OR emit a parse-time warning when `->` RHS is a binary
      `+` with the LHS being a string literal (likely user intent
      mismatch).

### P2 — `emit-js` / browser

- [x] **busi-p2-emit-js-process-stdout** — `emit-js` always appends
      `process.stdout.write(...)` → `ReferenceError: process is not
      defined` in the browser on every load. Fix: guard with `typeof
      process !== 'undefined'` or use `console.log`. busi worked around
      via `emit-spa`, but `emit-js` is effectively unusable in the
      browser today.

- [ ] **busi-p2-emit-js-transitive-imports** — `emit-js` does not
      propagate transitive imports into sub-module IIFEs. With `A → B →
      C`, the bundle of `A` does not close `B`'s IIFE over `C`. Linker
      must hoist transitives.

### P3 — name shadowing from plugin intrinsics

- [ ] **busi-p3-ratelimit-intrinsic-shadow** — `rateLimit` plugin
      intrinsic shadows a user-defined `rateLimit(req: Any)` without
      warning. Need a policy: user wins / qualified resolution /
      compile-time error on collision.

- [ ] **busi-p3-module-fn-name-conflict** — Function-name conflicts
      across imported modules (`htmlEsc` defined in two modules)
      surface as a runtime `No key 'toString' in map` in unrelated
      code. Module-scoped resolution with an explicit conflict error.

### P4 — future externs (not blocking today)

- [ ] **busi-p4-ed25519-rsa-verify** — Ed25519 / RSA `verify` externs
      for upcoming busi phase 87g (signature verification). Phase spec
      already plans HMAC fallback with `TODO(scalascript-signatures)`,
      so not a blocker, but once phase 87g lands without it there will
      be a `signature.unsupported` flag in prod. Nice to have before
      87g enters the active queue.

- [ ] **busi-p4-smtp-send-extern** — Native `smtpSend` extern. Today
      live email goes through an HTTP relay (`BUSI_EMAIL_HTTP_URL`).
      Standalone installations without a relay will need a JavaMail
      extern, or an explicit "relay-only forever" decision.

### P1 — new busi-side bugs (2026-06-09)

- [x] **busi-p1-string-indexof** — `String.indexOf` not found for certain
      arg types (IntV char code); 2-arg form `indexOf(str, fromIndex)` missing.
      Fix: add `IntV` branch in `dispatchString1`; add 2-arg forms in
      `dispatch2` and `dispatchString`. Same for `lastIndexOf`.

- [x] **busi-p1-string-split-regex** — `String.split` wrapped separator in
      `Pattern.quote` — regex escapes like `\\.` and `\\s+` did not work.
      Fix: remove `Pattern.quote`; use separator as raw regex (Java semantics).
      Also added 2-arg `split(sep, limit)` form.

---

## Language Surface - Markdown Content (next)

Broad spec exists:
[`specs/markdown-content-introspection.md`](specs/markdown-content-introspection.md).
Focused slice specs already exist for landed lookup/plain-text, metadata,
current-section, backend exposure, native-client parity, reverse Markdown
rendering, current-module artifact round-trip, multi-link import paragraphs,
linked imported content namespaces, GFM tables, and explicit inline content
binding, and Markdown toolkit links:
[`specs/markdown-content-lookup-plaintext.md`](specs/markdown-content-lookup-plaintext.md),
[`specs/markdown-content-metadata.md`](specs/markdown-content-metadata.md),
[`specs/markdown-content-current-section.md`](specs/markdown-content-current-section.md),
and
[`specs/markdown-content-backend-exposure.md`](specs/markdown-content-backend-exposure.md),
and
[`specs/markdown-content-native-client-parity.md`](specs/markdown-content-native-client-parity.md),
and
[`specs/markdown-content-to-markdown.md`](specs/markdown-content-to-markdown.md),
and
[`specs/markdown-content-artifact-roundtrip.md`](specs/markdown-content-artifact-roundtrip.md),
and
[`specs/markdown-multi-link-imports.md`](specs/markdown-multi-link-imports.md),
and
[`specs/markdown-content-linked-namespaces.md`](specs/markdown-content-linked-namespaces.md),
and
[`specs/markdown-content-tables.md`](specs/markdown-content-tables.md),
and
[`specs/markdown-content-data-binding.md`](specs/markdown-content-data-binding.md),
and
[`specs/markdown-toolkit-links.md`](specs/markdown-toolkit-links.md).
For the next slices, write and commit the focused spec first, then implement.

## VmCompiler completeness (focus)

Make `VmCompiler.compile` succeed for as many real functions as possible so
`JitRuntime` can run them on SscVm instead of tree-walking. Baseline (2026-06-05):
310 functions disabled. Miss profile:

  201  call: no compilable target (closures/HOF — skip for now)
   54  unsupported: Term.Select
   26  unsupported: Lit.String
   17  undefined: name 'inner'
    4  undefined: name '_VNODES_PER_NODE'
    3  unsupported: Term.Function
    2  unsupported: Lit.Null
    2  unsupported: stmt Defn.Def

Run `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` to track progress.
Each slice: one VmCompiler change + tests + bench A/B, never ship a non-win.

- [x] **jit-completeness-p2-term-select** — Compile `obj.field` access
      (`Term.Select` outside match). Requires meta lookup for field type; emit
      `GETFI` (int) or `GETFR` (ref) using existing field-info infrastructure.
      Expected: 54 → 0 for pure field-access cases. Skip method calls
      (`.head`, `.size` etc.) — bail as before.

- [x] **jit-completeness-p3-inner-def** — Compile functions that contain
      local `def inner(...)` bodies (`undefined: name 'inner'`, 17 misses).
      Strategy: treat inner defs as closures over params — compile the outer
      function only if `inner` has no free variables beyond outer params.

- [x] **jit-completeness-p4-defn-def** — Handle `stmt Defn.Def` in
      `compileStmt` (2 misses). A local def inside a block; same as p3 but in
      stmt position.

- [x] **jit-completeness-p5-lit-null** — `Lit.Null` (2 misses): emit CONST 0,
      set type TRef. Simple.

- [x] **jit-completeness-p6-lit-string** — `Lit.String` intermediate + LOADS/EQREF/NEREF opcodes.
- [x] **jit-completeness-p7-string-meta** — `String.length/isEmpty/nonEmpty` via JitRuntime meta + GETFI StringV.

## JIT universal coverage (new focus)

Spec: [`specs/jit-universal-coverage.md`](specs/jit-universal-coverage.md).
Goal: make JIT work for **all** real programs, not just benchmarks.
All three engines (SscVm, Javac bytecode, ASM bytecode) reach a unified
compilable subset.  Stages worked sequentially.

**Miss profile after Stage 2.1 (2026-06-05, 718 total disabled):**
```
345  [javac] UnknownShape       — falls through classifier; mostly HOF calls + bool-sibling gap
300  [vm] Other                 — VmCompiler raw-string bails (not yet migrated to typed reasons)
 32  [javac] NonExtractPattern
 14  [javac] Compound
  9  [javac] BoolBody           — bool body too complex even for walkBool fallback
  7  [javac] PatternGuard
  6  [javac] TryCatch
  2  [javac] VarargParam
  2  [javac] UsingParams
  1  [asm] TryCatch
```
Root-cause analysis of the 345 UnknownShape:
- `jitCompatibleSibling` still excludes bool-returning fns → callers of bool fns bail
- `walkBool` only handles `ApplyInfix`; misses `Lit.Boolean`, `Term.Name` (bool local),
  `Term.If`, `Term.Apply` on a bool-returning sibling, `!` unary
- HOF calls (passing/receiving fn values) — Stage 3 territory
- `walkForBailCliffs` doesn't detect HOF patterns → they all land in UnknownShape

- [x] **jit-uc-stage1-partial** — Unified per-engine `JitMissStats` with `JitBailReason`
      typed vocab; `JitBailReason.scala` extracted; Javac + ASM record misses.
      (CLI `ssc check-jit-coverage` deferred to after HOF slice.)

- [x] **jit-uc-stage2-1** — Bool body wrap: both backends emit `return (boolExpr)?1L:0L`
      instead of bailing; `JitResult.resultIsBool` unwraps to `BoolV` at call site.

- [x] **jit-uc-stage2-1b** — Bool sibling gap: remove `!isBoolReturning` gate from
      `jitCompatibleSibling` so bool-returning fns can be co-emitted; extend
      `walkBool` in both backends to handle `Lit.Boolean`, `!`, `Term.Name` (bool
      local/param → `!= 0L`), `Term.If`, `Term.Apply` (bool-returning sibling call →
      `call() != 0L`).  Extend `walkForBailCliffs` to report `HofCall` when
      `Term.Apply` target is a param name (not a global fn), turning most UnknownShape
      into a named category.  Target: UnknownShape < 100.

- [x] **jit-uc-stage2-2** — Ref+Ref 2-param dispatch (`ObjObjToLong/Double/Object` interfaces).
- [x] **jit-uc-stage2-3** — ASM ref-match guard parity (port `walkArmAsIfBranch`).
- [x] **jit-uc-stage2-4** — `Pat.Lit` arm in match (literal patterns).
- [x] **jit-uc-stage2-5** — Free-name → top-level `FunV` call (non-HOF case).

### Bench findings (2026-06-05, from `asm-jit-parity` worktree, post-2.4 main build)

Verified empirically via `./bench.sh`. New regression-guard corpus cases added:
`bench/corpus/bool-predicate.ssc`, `bench/corpus/literal-match.ssc`,
`bench/corpus/mutual-recursion.ssc`.

- [x] **jit-uc-finding-asm-bool-parity** — Fixed by the combined effect of stage 2.3
      (ASM guarded ref-match parity) + void `Term.If` in `emitStatAsVoid` /
      `walkStatAsVoid` (this commit): `workload()` in `mutual-recursion.ssc` uses
      `if isEven(i) then sum = sum + 1L` inside a while loop; that void Term.If was
      not emitable on either backend, so `workload()` bailed. Now both Javac and ASM
      compile `workload()`. Re-bench: `./bench.sh mutual-recursion`.

- [x] **jit-uc-finding-litmatch-not-firing** — Root cause was `.toLong` in
      `sum = sum + classify(i).toLong` blocking `workload()` JIT compilation.
      Fix: `.toLong`/`.toInt` emit as identity (Int=Long in ScalaScript);
      `.toDouble` emits L2D. Both backends. `n % 5 match` with ApplyInfix
      scrutinee was already supported via `walkLong` for the scrutinee.

- [x] **jit-uc-stage3-1** — `Value.FunV` as JIT-visible ref operand in `JitGlobals`.
- [x] **jit-uc-stage3-2** — SscVm `CALLREF` opcode + monomorphic IC.
- [x] **jit-uc-stage3-3** — Lambda / closure compilation (capturing + non-capturing).
- [x] **jit-uc-stage3-4** — IC hit-rate validation (`SSC_JIT_IC_STATS=1`).
- [x] **jit-uc-stage3-5** — Bytecode JIT HOF emission (Javac + ASM `INVOKEINTERFACE` to `RefCallable`).

- [x] **jit-uc-stage4** — Arity 3–4 ceiling lift (code-generated dispatch interfaces).

- [x] **jit-uc-stage5-1** — Mixed Long+Double arms auto-promotion (already handled: `bodyHasDoubleLit` classifies any fn with a Double literal as Double; `walkDouble` auto-widens Int/Long literals; no corpus `MixedReturnType` misses).
- [x] **jit-uc-stage5-2** — `var` in pure bodies: add `Term.Assign` to `walkBlockStmts` (Javac) and delegate non-final stats to `emitStatAsVoid` in `emitBlockStmts` (ASM).
- [x] **jit-uc-stage5-3** — `try/catch` in bodies (JVM try block + tree-walker fallback).
- [x] **jit-uc-stage5-4** — `Pat.Alternative` / `@`-binding pattern support.
- [x] **jit-uc-stage5-5** — Non-`Term.Name` match scrutinee (auto-hoist to local).

### Stage 6 — Post-merge long tail (new queue)

Baseline (post-stage-5, 2026-06-05, 734 total disabled):
```
 300  [vm] Other            — VmCompiler: HOF/ref-return/complex (unmigrated vocab)
 294  [javac] UnknownShape  — remaining HOF + complex closure shapes
  48  [javac] LambdaValue   — non-trivial Term.Function captures
  37  [javac] Compound      — multiple simultaneous bail reasons
  27  [javac] NonExtractPattern — tuple / typed patterns in match arms
   8  [javac] PatternGuard  — `if` guards in match arms
   7  [javac] NonAdtScrutinee — complex scrutinee remaining after 5.5
   7  [javac] BoolBody      — bool bodies too complex for walkBool
```
Each item: one commit + bench A/B. Run `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` to track.

- [x] **jit-uc-stage6-bench-baseline** — Bench 2026-06-05 post-merge: bool-predicate
      4.37→0.004ms, literal-match 3.51→0.004ms, mutual-recursion ssc/asm at parity (~1.2ms).
      Remaining HOF gaps: either-chain 3.46ms, hof-pipeline 2.79ms, option-chain 2.98ms,
      range-sum 3.57ms, typeclass-fold 2.99ms (all ~0.001–0.020ms on jvm).

- [x] **jit-uc-stage6-asm-mutual-recursion** — Fix ASM JIT regression on
      `mutual-recursion`: `ssc-asm` 20.8 ms → 1.22 ms (parity with Javac 1.20 ms).
      Root cause: `Lit.Boolean` missing from `walkLong` caused bool-returning functions
      to fall into `walkBool` fallback which generated COMPUTE_FRAMES-incompatible dead
      labels. Also fixed dead `GOTO Lend` in `walkBool(Term.If)` when thenp always jumps.

- [x] **jit-uc-stage6-pattern-guard** — Guards in match sub-expressions (val RHS,
      if-branch etc.) now compile. `walkMatchExpr` in both backends adds `hasAnyGuard`
      if-chain path via `walkArmAsIfBranch`/`emitArmBodyGuarded`. Remaining 8 PatternGuard
      misses have complex guard conditions (`walkBool` can't compile them) — see
      `jit-uc-stage6-bool-body-ext`.

- [x] **jit-uc-stage6-bool-body-ext** — Added `walkLong` fallback to `walkBool`
      in both backends. Enables bool-returning match expressions and complex guards
      where `walkBool` fails but `walkLong` succeeds (Long != 0 = true).
      New test: `isZero(n): Boolean = n match { 0 => false; _ => true }` compiles.

- [x] **jit-uc-stage6-nonextract-tuple** — `Pat.Tuple` in Javac + ASM backends;
      JitLint accepts Var/Wildcard sub-patterns; 27 NonExtractPattern misses eliminated.

- [x] **jit-uc-stage6-vm-retref** — RETREF=49 opcode; SscVm TLS slot; VmCompiler
      unifyRet(TRef) allowed; JitRuntime wrapRef(); 18 vm-retref misses eliminated.

- [x] **jit-uc-stage6-unknownshape-hof-analysis** — HofMethodCall + RefChainCall bail
      reasons added; UnknownShape 295→240; stage-7 plan in specs/jit-universal-coverage.md §9.

## JIT universal coverage — Stage 7

Spec: [`specs/jit-universal-coverage.md §9`](specs/jit-universal-coverage.md).
Baseline (2026-06-06): 734 disabled, 240 UnknownShape, 55 RefChainCall, 70 Compound.
Current after bucket split (2026-06-06): 731 disabled, 238 UnknownShape,
70 Compound, 33 QualifiedRefCall, 22 RefChainObjectCall, 0 RefChainCall.
Current after HOF method slice (2026-06-06): 731 disabled, 238 UnknownShape,
70 Compound, 33 QualifiedRefCall, 22 RefChainObjectCall; warmed HOF benches
are now `option-chain=0.002ms`, `either-chain=0.002ms`,
`hof-pipeline≈0.001ms`, `range-sum≈0.001ms`
(`BENCH_WI=1 BENCH_MI=3 BENCH_F=1 scripts/bench interp <name>`).
Current after typeclass classification (2026-06-06): 733 disabled,
238 UnknownShape, 70 Compound, `TypeclassUsingDispatch` split out as
`javac=4` / `asm=1`; `typeclass-fold=0.010 +/- 0.008ms/op`
(`BENCH_WI=1 BENCH_MI=3 BENCH_F=1 scripts/bench interp typeclass-fold`).
Current after object ref-chain dispatch (2026-06-06): 733 disabled,
238 UnknownShape, 70 Compound, 33 QualifiedRefCall, `RefChainObjectCall=14`,
`NumericObjectMethodCall=8`; object `mkString` / `Map.getOrElse` fixtures
now JIT on Javac+ASM as `LongToObject`.
Current after UnknownShape tagging (2026-06-06): 733 disabled,
20 UnknownShape, 178 Compound, `DirectGlobalOrCtorCall=148`,
`ApplyInfixRefOp=19`, `InterpolatedString=14`; classifier-only P3 target met.
Current after numeric-object dispatch (2026-06-06): 717 disabled,
20 UnknownShape, 170 Compound, no `NumericObjectMethodCall` misses in the
runtime profile; BigInt/Decimal constructor-result methods now compile on
Javac+ASM as `LongToObject`.
Each item: one commit + bench A/B (or test A/B), never ship a non-win.

- [x] **jit-uc-stage7-refchain** — Ref-val propagation low-risk subset landed:
      Javac + ASM co-emit ref-returning sibling calls, bind immutable ref locals
      as `Object`, and inline numeric reads through `JitRefDispatch`
      (`getOrElseLong`, `sizeLong`, `headLong`). Regression:
      `val r = parse(n); r.getOrElse(7)` JITs on both backends. Verified by
      `JitLintTest -z stage7-refchain`, `SscVmTest -z stage7-refchain`, and
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1416 tests green).
      Result: total disabled 734→731; aggregate `RefChainCall` stayed 55.
      Detail trace showed the remaining bucket is broader than this subset
      (`Parser.string`, `Free.Pure`, `BigInt.pow`, `map(...).mkString`, effect
      calls, object-returning `Map.getOrElse`). See spec §9 Stage 7.1.

- [x] **jit-uc-stage7-refchain-bucket-split** — Split the remaining broad
      `RefChainCall` bucket before adding object/String/generic ref-returning
      dispatch interfaces. Added `QualifiedRefCall` for module/companion/native
      simple receivers and `RefChainObjectCall` for computed object/String/generic
      chains; `JitPredicates` now tracks immutable local `val` names so
      numeric local/direct ref reads stay in `RefChainCall`. Verified by
      `JitLintTest -z stage7-refchain-bucket-split`, full `JitLintTest`, and
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1419 tests green).
      Result: total disabled stayed 731, `RefChainCall` 55→0,
      `QualifiedRefCall=33`, `RefChainObjectCall=22`. See spec §9 Stage 7.2.

- [x] **jit-uc-stage7-hof-method** — Monomorphic IC for HOF method dispatch:
      `.map(x => …)`, `.flatMap(x => …)`, `.filter(x => …)`, `.foldLeft(z)((a,b) => …)`.
      Landed a narrow numeric receiver subset for Option/Either/List/Range:
      compact lambda descriptors (`JitHofShape`), shared dispatch helpers
      (`JitHofDispatch`), top-level ref globals via `JitGlobals.readGlobalRef`,
      and builtin `Right`/`Left` object co-emit. Verified by
      `JitLintTest -z stage7-hof-method`, `SscVmTest -z stage7-hof-method`,
      four warmed JMH commands (`scripts/bench interp option-chain`,
      `either-chain`, `hof-pipeline`, `range-sum` with
      `BENCH_WI=1 BENCH_MI=3 BENCH_F=1`), and full
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1428 tests green).
      Result: focused benches are all <0.1ms/op; corpus miss profile unchanged
      at 731 disabled / 238 UnknownShape / 70 Compound. `typeclass-fold`
      remains a separate generic/given-dispatch follow-up. See spec §9
      Stage 7.3.

- [x] **jit-uc-stage7-typeclass-fold** — Classified the remaining
      `typeclass-fold` HOF workload as active context-bound typeclass dispatch
      instead of standard receiver method dispatch. Added
      `TypeclassUsingDispatch` for `summon[...]` and method selection on
      `using` params, plus a warmed `typeclass-fold` JMH target. Verified by
      `JitLintTest -z stage7-typeclass-fold`, `interpreterBench/compile`,
      quick JMH (`0.010 +/- 0.008ms/op`), and full
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1429 tests green).
      Result: generic/given dispatch is now a named follow-up; do not fold it
      into the monomorphic Option/Either/List/Range path. See spec §9
      Stage 7.4.

- [x] **jit-uc-stage7-refchain-object-dispatch** — Implemented the low-risk
      object/String-returning ref-chain dispatch slice and narrowed the rest.
      Javac + ASM now compile `(0 until n).map(...).mkString(...)` and
      object-returning `Map.getOrElse` as `LongToObject`, using
      `JitRefDispatch.getOrElseRef` / `mapGetOrElseRef` / `mkStringRef`.
      Added a guard so numeric `Option(...).getOrElse(0)` stays on the
      existing `LongFn1` path. Added `NumericObjectMethodCall` for
      `BigInt`/`Decimal` constructor-result method calls. Verified by focused
      `JitLintTest` / `SscVmTest` filters and full
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1434 tests green).
      Result: `RefChainObjectCall` narrowed `22 -> 14`, with
      `NumericObjectMethodCall=8`. See spec §9 Stage 7.5.

- [x] **jit-uc-stage7-unknownshape-tagging** — Added classifier-only
      `walkForBailCliffs` buckets for ref-like infix ops, string interpolation,
      type applications, for-comprehensions, `new` object construction,
      expression-callee HOF apply shapes, and direct non-param global/constructor
      calls. Verified by focused `JitLintTest` filters and full
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1441 tests green).
      Result: `UnknownShape` narrowed `238 -> 20`, meeting the `<100` target.
      See spec §9 Stage 7.6.

- [x] **jit-uc-stage7-numeric-object-dispatch** — Implemented the dedicated
      BigInt/Decimal numeric-object helper path. Javac + ASM now compile
      `BigInt(...)` / `Decimal(...)` constructor-result object methods
      (`abs`, `negate`, `pow`, `gcd`, `toDecimal`, `setScale`, `toBigInt`) as
      `LongToObject` through `JitRefDispatch`, with receiver guards preserving
      the generic `mkString` / `Map.getOrElse` ref-chain object fallback.
      Verified by focused numeric/object-dispatch tests and full
      `SSC_JIT_STATS=1 sbt "backendInterpreter/test"` (1443 tests green).
      Result: total disabled `733 -> 717`, `Compound 178 -> 170`, no
      `NumericObjectMethodCall` misses in the runtime profile. See spec §9
      Stage 7.7.

## JIT universal coverage — Stage 8

Status (2026-06-07): mostly done. 1474 tests green. Bench wins:

| Bench | Before | ssc Javac | ssc-asm | JVM |
|---|---|---|---|---|
| map-ops | 3.16ms | 0.027ms (117×) | 0.026ms (113×) | 0.021ms ✓ |
| string-split | 14.5ms | 0.235ms (62×) | 0.170ms (84×) | 0.089ms |
| typeclass-fold | 2.97ms | 2.38ms (1.25×) | 2.18ms (1.36×) | 0.005ms |

Spec: [`specs/jit-universal-coverage.md §9`](specs/jit-universal-coverage.md).
Baseline (2026-06-06, post-stage7): 717 disabled, 20 UnknownShape,
170 Compound (`DirectGlobalOrCtorCall=148`, `ApplyInfixRefOp=19`,
`InterpolatedString=14`). Bench wins from stage 7 verified — either-chain,
hof-pipeline, option-chain, range-sum all <0.03 ms/op. Remaining buckets
do not show on bench corpus but block real-program JIT coverage.
Each item: one commit + bench A/B (or test A/B), never ship a non-win.

- [x] **jit-uc-stage8-direct-global-ctor** — Codegen done via
      `callGlobalLongAny` / `callGlobalRefAny` in JitGlobals: 1-arg ref,
      2/3-arg mixed ref+long (including callees with `using` clauses) now
      dispatch through `interp.invoke` (Javac+ASM). Classifier still reports
      144 DirectGlobalOrCtorCall — most are now false positives; refining
      `isKnownDirectJitCallee` requires runtime introspection (separate slice).

- [x] **jit-uc-stage8-apply-infix-ref** — String + Long/ref concat (Javac+ASM);
      BigInt/Decimal infix arithmetic (+/-/*/Div/Mod) via JitRefDispatch helpers
      (Javac+ASM); BigInt/Decimal comparison ops (<,<=,>,>=) (Javac+ASM);
      List/Map `++` collection concat via collectionConcat (Javac+ASM);
      ref ==/!= via Objects.equals (Javac+ASM).

- [x] **jit-uc-stage8-string-interp** — Javac+ASM: `s"..."` (Term.Interpolate
      prefix "s") lowers to `new Value.StringV(part + arg + ...)`; each arg
      via walkLong (numeric) or walkRef + Value.show. f-, md-, html-, css-
      prefixes still go through tree-walker.

- [x] **jit-uc-stage8-unknownshape-tail** — Added 5 new bail reasons
      (ThrowExpression, TupleConstruction, EtaExpansion, ExplicitReturn,
      NewAnonymousClass) + classifier wiring; corpus 20 UnknownShape unchanged
      (those shapes don't appear in tests); next agent debugging real code sees
      the right bucket. 3 focused tests; 1452 tests green.

### Stage-8 bench regressions (carryover from stage-6)

Three bench workloads remained slow through stages 6–7 because each needs a
distinct codegen path, not a classifier extension. Baseline (2026-06-06,
`./bench.sh`): `typeclass-fold` ssc 2.97 / ssc-asm 3.01 / jvm 0.004 ms/op;
`map-ops` ssc 3.16 / ssc-asm 3.91 / jvm 0.020 ms/op; `string-split` ssc 14.5 /
jvm 0.088 ms/op. Each item: one commit + bench A/B.

- [~] **jit-uc-stage8-typeclass-fold** — Partial (1.36× win, 2.97ms → 2.18ms).
      Codegen via `callGlobalLong1Ref` + `looksLongValue` fix: `workload()`
      JIT-compiles, the while-loop overhead removed. `combineAll` itself still
      tree-walked (uses `summon[T]`). Full win needs compile-time `summon[T]`
      specialization (monomorphic IC for given dispatch) — separate stage-9
      slice.

- [x] **jit-uc-stage8-map-ops** — Full bench-paritet with JVM on both backends
      (3.16ms → 0.027ms ssc Javac, 0.026ms ssc-asm vs JVM 0.021ms). Required
      changes: `Map[K,V](...)` ApplyType in walkRef + isRefValRhs; ref-typed
      Defn.Var/Term.Assign in walkBlockStmts + walkStatAsVoid + emitStatAsVoid;
      JitRefDispatch.mapUpdatedRef + mapGetOrElseLong.

- [x] **jit-uc-stage8-string-split** — Full bench-paritet with JVM on both
      backends (14.5ms → 0.235ms ssc Javac, 0.170ms ssc-asm vs JVM 0.089ms).
      Required: `String.split` via stringSplitRef; no-paren `.trim`/`.toUpperCase`
      Term.Select; `.toInt`/`.toLong` on ref fallback to emitRefChainLong;
      OpStringTrimToInt specialized op for `s => s.trim.toInt`-shape lambdas
      in JitHofDispatch + JitHofShape.

### Stage-8 residual bail buckets (gap analysis 2026-06-06)

After comparing the post-stage-7.7 miss profile against SPRINT, these
categories have no implementation task yet. Each item: one commit + miss-profile
A/B (or test A/B); never ship a non-win.

- [x] **jit-uc-stage8-vm-bail-migration** — Migrated 46 `bail(...)` sites in
      `VmCompiler` to typed `JitBailReason`; added 6 VM-specific cases
      (VmCallShape/VmFieldShape/VmUnsupportedTerm/VmEmptyBlock/VmNonBoolCond/
      VmUndefinedName) + reused 9 generic ones. Result: `[vm] Other` 290 → 32,
      new readable buckets dominated by `[vm] FreeNameUnresolvable=225`
      (HOF/closure call targets). 1443 tests green.

- [~] **jit-uc-stage8-qualified-ref-call** — Partial: `Math.max/min/abs`
      (Long) and `Math.sqrt/pow/floor/ceil/log/log10/exp/abs/sin/cos/tan/atan2`
      (Double) inline to `INVOKESTATIC java/lang/Math` in both backends.
      `.max(b)`/`.min(b)`/`.abs` on Long receivers also covered. Remaining:
      generic module/companion resolution for non-Math qualified calls
      (separate slice).

- [x] **jit-uc-stage8-nonextract-pattern-residual** — Classifier split:
      added TypedPattern + NestedTuplePattern + AlternativeWithBindings cases.
      Corpus 19 NonExtractPattern stayed (sub-Pat.Extract inside tuples — separate
      codegen slice). 3 focused classifier tests; 1447 tests green.

- [x] **jit-uc-stage8-pattern-guard-complex** — Long-fallback for match-guards:
      Javac `guardBoolExpr` + ASM `emitGuardBool` try `walkBool` first then
      `walkLong != 0L`. Targeted test exercises `Circle(r) if (r % 2)` style
      guards. Corpus profile unchanged (6 residual PatternGuard are Compound
      with other reasons), but new shapes now JIT. 1444 tests green.

## JIT universal coverage — Stage 9 (post-monomorphic follow-ups)

Stage 9 reopened two items previously parked as spec non-goals "for this sprint."
All current slices landed; remaining open items move to BACKLOG/CHANGELOG when
specific follow-ups are scoped.

---

## Interpreter perf — Phase C + D continuation (open)

Spec: [`docs/vm-jit-next.md §"Phase C+D roadmap"`](docs/vm-jit-next.md).
Each item below is one focused commit; same-session A/B, never ship a non-win.

- [x] **interp-opt-recursive-build-floor-asm-parity** — Port the Phase 1B
      `LongToObject` pure ADT builder path to `AsmJitBackend`. Landed as part
      of `asm-jit-parity` (2026-06-04). Verified 2026-06-05: ASM `recursiveEval`
      0.070 ms/op, `recursiveEvalMixed` 0.071 ms/op (vs Javac 0.066 ms/op).
      Spec: [`docs/interp-opt-recursive-eval.md`](docs/interp-opt-recursive-eval.md).

---

## Rust backend (new target)

Spec: [`specs/rust-backend.md`](specs/rust-backend.md). New AOT target —
emits a Cargo crate (`Cargo.toml` + `src/runtime/` + `src/generated/`) that
`cargo build` compiles to a self-contained binary. Phases R.1–R.6
(skeleton → core IR → intrinsics MVP → effects → http parity → polish).
Each task: one commit, baseline + acceptance recorded, never ship a
half-implemented phase under a flag. New backend module under
`runtime/backend/rust/`, plugin loaded via `META-INF/services` like every
other backend; no privileged hook in core.

**Coordination.** R.1 is the foundation — every later task `dependsOn` it.
Until R.1 lands, do not claim R.2+ from the queue. R.6 sub-tasks are
independent of each other once R.5 is in.

### Phase R.1 — Skeleton

R.1.3 hello-emit is split into four sequential sub-slices below.
Each one is a single commit with its own golden fixture so the next
slice has a verified base to extend. The cumulative result equals the
original `rust-backend-r1-hello-emit` description (Cargo.toml + main.rs
+ runtime/mod.rs + value.rs + generated/<module>.rs).



### Phase R.2 — Core IR coverage

Depends on R.1 complete. Each item: one commit, golden snapshots updated,
A/B vs the interpreter row.



### Phase R.3 — Intrinsics MVP

Depends on R.2. Capability additions: `FileSystem`, `Crypto`, `Markup`
(string-string xml only). Per-module Cargo dependency walk now becomes
load-bearing: the emitted `Cargo.toml` lists exactly the crates the
program reaches.

---

## Rust backend — benchmark coverage (spec: specs/rust-backend-bench-coverage.md)

16 of 22 bench corpus workloads return `n/a`.  Tasks below are ordered
by quick-win impact; P0 alone unlocks 7 benchmarks with tiny changes.

### P0 — quick wins (XS, each ≤ 100 lines)

- [x] **rust-bench-p0-to-numeric** — Add `.toLong` / `.toInt` /
      `.toDouble` / `.toFloat` conversions.  In `renderTerm`,
      recognise `Term.Select(expr, Term.Name("toLong"|"toInt"|...))` and
      lower to `(expr) as i64` / `as i32` / `as f64`.  Also lower
      `Term.ApplyInfix(lhs, "+", rhs)` where one operand is a `String`
      and the other is numeric to `format!("{}{}", lhs, rhs)` (fixes
      `string-concat`).  Acceptance: `string-concat.ssc`,
      `literal-match.ssc` green on `scripts/bench wall rust`.
      Spec: `specs/rust-backend-bench-coverage.md` §Gap A + C.

- [x] **rust-bench-p0-hello-bench** — Fix `hello-world` bench harness.
      In `bench/run.sc`'s injected `main.rs`, when `workload()` returns
      `Unit`, emit `generated::ssc_program::workload(); let r = 0i64;`
      instead of `let r = generated::ssc_program::workload();` so
      `std::hint::black_box(r)` receives an `i64`.  Acceptance:
      `hello-world.ssc` green on `scripts/bench wall rust`.
      Spec: §Gap B.

### P1 — collection method chaining (S–M)

- [x] **rust-bench-p1-vec-methods** — Add `.map(f)`, `.filter(f)`,
      `.foldLeft(z)(f)`, `.foreach(f)`, `.collect()` (as
      `.collect::<Vec<_>>()`) on Vec types in `renderTerm`.  Pattern:
      `Term.Select(qual, Term.Name("map"|"filter"|...))` + following
      `Term.Apply` with the lambda arg.  Acceptance: `list-fold.ssc`,
      `hof-pipeline.ssc` green.  Spec: §Gap D + G.

- [x] **rust-bench-p1-string-methods** — Add `String.split(sep)` →
      `s.split(sep).map(|p| p.to_string()).collect::<Vec<String>>()`,
      `.trim()` → `.trim().to_string()`, `.toInt` on String → 
      `.parse::<i32>().unwrap_or(0)`.  Acceptance: `string-split.ssc`
      green.  Spec: §Gap D.

### P2 — types + patterns (M)

- [x] **rust-bench-p2-sealed-trait-adt** — Recognise `sealed trait T`
      + `case class C extends T` pattern: collect both forms in a single
      ADT scan and lower to a Rust `pub enum T { C { … }, … }` just as
      the existing Scala 3 `enum` lowering does.  Acceptance:
      `pattern-match-heavy.ssc` green (requires `foreach` from P1 too).
      Spec: §Gap F.

- [x] **rust-bench-p2-tuple-types** — Map `Type.Tuple(elems)` to Rust
      tuple `(T1, T2, …)` in `mapType`; add `Lit.Tuple(elems)` / 
      `Term.Tuple(elems)` emit in `renderTerm`.  Lower the `++`
      concat operator on two tuple literals to a flat tuple.  Acceptance:
      `tuple-monoid.ssc` green.  Spec: §Gap H.

- [x] **rust-bench-p2-option-type** — Map `Option[T]` to `Option<T>`;
      add `.flatMap`, `.map`, `.getOrElse` methods on Option; `Some(x)`
      constructor → `Some(x)`, `None` → `None`.  Acceptance:
      `option-chain.ssc` green.  Spec: §Gap E.

### P3 — additional types (M–L)

- [x] **rust-bench-p3-hashmap-type** — Map `Map[K, V]` to
      `std::collections::HashMap<K, V>` (dep-free); add `.updated(k, v)`
      → `{ let mut m2 = m.clone(); m2.insert(k, v); m2 }`, `.getOrElse`
      → `.get(&k).copied().unwrap_or(default)`.  Acceptance:
      `map-ops.ssc` green.  Spec: §Gap E.

- [x] **rust-bench-p3-either-type** — Map `Either[L, R]` to a generated
      `pub enum Either<L, R> { Left(L), Right(R) }` emitted once per
      crate when reached; add `.map`, `.flatMap`, `.fold` methods.
      Acceptance: `either-chain.ssc` green.  Spec: §Gap E.

- [x] **rust-bench-p3-range-until** — Lower `(lo until hi)` and
      `(lo to hi)` to a `(lo..hi)` / `(lo..=hi)` Rust range; chain
      `.map` / `.foldLeft` via iterator adapters.  Acceptance:
      `range-sum.ssc` green.  Spec: §Gap E + D.

### Phase R.4 — Effects (algebraic effects + handlers)

Depends on R.2 closures and R.3 (for the runtime preamble layout). Free
monad in `Value::Computation(Box<Computation<Value>>)`. Capability adds
`AlgebraicEffects`. Multi-shot continuations panic with a clearly-labelled
runtime error; tracked as R.6 follow-up.


- [x] **rust-backend-r4-perform-handle-resume-lowering** — Implemented
      via tagless-final traits (not free-monad): Logger (effect-pure bench),
      Stream/VecStream (effect-stream bench), State/StateHandler (R.4.4),
      Random/RandomHandler LCG (R.4.4). R.4.1 supplies the free-monad
      runtime template for future CPS effects. 131 tests pass.

### Phase R.5 — Runtime parity (std.http server)

Depends on R.4 (handler bodies are effectful). Capability adds
`HttpServer`. Per-module walk pulls `tokio` + `hyper` + `http-body-util` +
`bytes` only when an `std.http.*` intrinsic is reached; programs without
HTTP stay dep-free.


- [x] **rust-backend-r5-http-serve-route** — `serve(port)` + `route(method, path, handler)`
      via `hyper::server::conn::http1::Builder` + `service_fn`; tokio+hyper deps
      pulled only when HTTP is reached; `src/runtime/http.rs` emitted conditionally.
      Landed 2026-06-08 (commit `0b3d179f0`). 7 tests in RustGenR5Test.

### Phase R.6 — Parity polish (independent tasks)

Each item is independent and stays parked until a real conformance test
or example demands it. Order below is priority for triage when claiming.

- [x] **rust-backend-r6-monomorphisation-pass** — Already implemented by design.
      The Rust backend emits `i64`, `bool`, `f64` directly for all numeric/boolean
      operations — no `Value` boxing in generated code. The `Value` enum in `value.rs`
      exists only for the `_show` helper. Every generated `pub fn` uses primitives
      throughout: no boxing overhead on hot paths. Closed 2026-06-09.

- [x] **rust-backend-r6-typeclasses** — `Feature.TypeClasses`: `given X: T with { defs }`
      emits a Rust unit struct XGiven + inherent impl; instance injected as topVal
      `let x = XGiven;`. `obj.method(args)` dispatch added to applyNonListCtor.
      Acceptance: bench/corpus/typeclass-monoid.ssc. 7 tests (190 total). Landed 2026-06-09.

- [x] **rust-backend-r6-streams** — `Feature.Streams` via synchronous iterator chains.
      Source.range(lo,hi)→(lo..=hi), Source.fromList(list)→list, .toList→.collect::<Vec<_>>().
      .map/.filter/.foldLeft already worked on ranges. No tokio/futures needed for these patterns.
      Acceptance: bench/corpus/streams-pipeline.ssc. 7 tests (183 total). Landed 2026-06-09.

- [x] **rust-backend-r6-multi-shot-continuations** — Resolved by the tagless-final
      approach (R.4.2–R.4.4): `VecStream` is inherently multi-shot (collects every
      `stream_emit` call); no Computation Clone needed. The original restriction was
      free-monad–specific and does not apply to tagless-final. Closed 2026-06-09.

- [x] **rust-backend-r6-tco** — `Feature.TailCallOptimization` via while-loop
      rewrite: `hasTailCallPath` detects self-calls in if/else + block tails;
      params get `mut`; tail calls → temp bindings + param reassignments; branches
      get `return`. Binary-recursive fns (e.g. fib) are NOT rewritten (safe).
      7 tests (147 total). Landed 2026-06-09.

- [x] **rust-backend-r6-websockets** — `Feature.WebSockets`: `wsRoute(path, handler:String->String)`,
      `wsServe(port)`, `wsConnectSync(url, handler:String->Unit)` via tokio-tungstenite 0.21.
      Conditional dep injection (tokio dedup when HTTP also present).
      src/runtime/ws.rs emitted on demand. 8 tests (155 total). Landed 2026-06-09.

- [x] **rust-backend-r6-auth** — `Feature.Auth`, intrinsics
      `hashPassword` (argon2id + random salt), `verifyPassword` (bool),
      `jwtSign` (HS256, payload as `sub` claim), `jwtVerify` (returns payload).
      argon2 0.5 + jsonwebtoken 9 + serde deps pulled only when any auth
      intrinsic is reached; hello-world stays dep-free. 9 tests (140 total).
      Landed 2026-06-09.

- [x] **rust-backend-r6-mcp** — `Feature.McpServer` via hand-rolled JSON-RPC 2.0
      over stdio (rmcp not stable enough). `mcpRegisterTool` + `mcpServe`;
      handles initialize/tools_list/tools_call. Only serde_json dep (no duplication
      when JSON intrinsics also present). 7 tests (162 total). Landed 2026-06-09.

- [x] **rust-backend-r6-markup-xslt** — Decision: XSLT excluded from Rust backend.
      No conformance test currently reaches it. `Feature.Xslt` is NOT in
      RustCapabilities.features — programs requiring XSLT are rejected at
      capability check time with a Diagnostic.Unsupported. Codec path (quick-xml
      XML read/write without XSLT) is out of scope for this sprint.
      Landed 2026-06-09 (this entry — no code change needed, capability rejection
      was already implicit).

---

## Backend-specific fenced blocks + platform-type ban (new)

**Motivation:** `.ssc` code must never reference `java.*`, `scala.*`, or any
other platform-specific type in a regular `scalascript` block — this should be
a compile error, not convention. The escape hatch for legitimate ad-hoc native
code is explicit backend-tagged fenced blocks: `scala`, `java`, `javascript`,
`rust`. The `java` fenced block tag is new (previously only `scala` existed for JVM).

Spec: [`specs/backend-specific-blocks.md`](specs/backend-specific-blocks.md)

### Phase 1 — parser

- [x] **backend-blocks-p1-parse** — Extend parser to recognise
      `scala`, `java`, `javascript`, `rust`, `wasm` fenced blocks as
      `BackendBlock(tag, source)` AST nodes. Existing `scalascript`
      blocks unchanged. Tests: mixed-block file parses correctly.
      Commit: `feat(parser): backend-specific fenced blocks`.
      ✓ Landed 2026-06-09 (745c963a): Lang.Java/Rust/Wasm + isNativeBackendBlock
      + isOpaqueExec wiring; 17 new tests, 906 core tests pass.

### Phase 2 — type-checker enforcement

- [x] **backend-blocks-p2-typecheck** — Banned-prefix check in
      type-checker: `java.*`, `javax.*`, `scala.*`, `sun.*`, `com.sun.*`
      in `scalascript` blocks → `E_PlatformType` compile error.
      Capability gate: `extern def` with no backend impl → `E_NoBackendImpl`.
      Test: `tests/conformance/backend-blocks-platform-type-ban.ssc`.
      Commit: `feat(typer): platform-type ban + capability gate`.
      ✓ Landed 2026-06-09 (33ca975): java/javax/sun/com.sun import ban in
      scalascript blocks; scala blocks exempt; 10 new tests, 916 total.

### Phase 3 — JVM backend emission

- [x] **backend-blocks-p3-jvm** — `JvmGen`: emit `scala` blocks verbatim
      after main module object; emit `java` blocks as separate `.java`
      source files via `//> using sources`. Test: `currentPid()` via
      `scala` block; `ssc run --target jvm` returns PID > 0.
      Commit: `feat(jvmgen): scala/java backend block emission`.
      ✓ Landed 2026-06-09 (5f8b969): scala blocks via isParseable, java blocks
      via javaBlocks buffer + //> using sources; 7 tests, 1490 backendInterpreter pass.

### Phase 4 — JS backend emission

- [x] **backend-blocks-p4-js** — `JsGen`: emit `javascript` blocks
      verbatim into the JS bundle after preamble. Test: `currentPid()`
      via `javascript` block; Node.js target returns `process.pid`.
      Commit: `feat(jsgen): javascript backend block emission`.
      ✓ Landed 2026-06-09 (462cb30): javascript verbatim in walkSection +
      genSection; html/css keep template path; 6 tests, 1504 total.

### Phase 5 — Rust backend emission

- [x] **backend-blocks-p5-rust** — `RustGen`: `rust` fence blocks emitted
      verbatim into `src/generated/<module>.rs` with numbered headers.
      5 tests in RustGenRustBlocksTest. Landed `26404e906`.

### Phase 6 — extend FFI annotations to `@rust` / `@wasm` + WASM boundary

- [x] **backend-blocks-p6-ffi-extend** — Add `RustInline`, `WasmInline`,
      `WasmExport`, `WasmImport` annotation AST nodes (alongside existing
      `JvmInline`, `JsInline`). Wire `@rust("...")` into `RustGen`.
      Wire `@wasmExport` / `@wasmImport` into WASM backend boundary emission
      (export/import table entries). Update `arch-ffi.md` to reference
      `backend-specific-blocks.md` for the full picture.
      Commit: `feat(ffi): @rust/@wasm annotations + WASM boundary annotations`.
      ✓ Landed 2026-06-09 (339cdff): @rust("expr") wired in RustCodeWalk.renderDef;
      extern defs without @rust skipped; arch-ffi.md updated; 4 tests, 151 Rust total.
      Note: @wasmExport/@wasmImport deferred (no WASM backend to wire into).

### Phase 7 — audit + flip ban to error

- [x] **backend-blocks-p7-audit** — Enable ban as warning, surface all
      violations in `runtime/std/`, `examples/`, `tests/conformance/`.
      Migrate violating `.ssc` files to `std.*` or backend blocks.
      Flip warning to error. Update `AGENTS.md` link (already added).
      Commit: `fix(typer): enable platform-type ban as hard error`.
      ✓ Landed 2026-06-09: audit found 1 violation (mcp-search-server.ssc had
      java.nio + scala.io in scalascript block); migrated to scala block.
      Ban already hard error from Phase 2. runtime/std/, conformance/ clean.

---

## std.fs / std.os / std.process — filesystem, OS & process abstraction (new)

**Motivation:** `.sc` tool scripts (`bench/run.sc`, `tests/e2e/spa-smoke.sc`)
use `java.io`/`java.nio` directly. `.ssc` user code must never reach for JVM
APIs. `runtime/std/fs.ssc` exists but is only 4 stubs with no backend plugin.
Goal: full cross-backend `fs-plugin` + `os-plugin` so `.ssc` code has zero
reason to touch platform APIs.

Three `.ssc` stdlib modules:
- `std.fs` — file-system operations (read/write/list/copy/move/delete/temp)
- `std.os` — OS environment (env vars, CLI args, cwd, paths, exit, platform info)
- `std.process` — process management (spawn, exec, stdin/stdout/stderr, wait, kill)

Spec to write first: `specs/std-fs-os.md`

### Phase 1 — spec + design

- [x] **std-fs-os-p1-spec** — Write `specs/std-fs-os.md`. Cover:

      **`std.fs`**: readFile, writeFile, appendFile, deleteFile, exists,
      isDir, isFile, mkdir, mkdirs, listDir, copyFile, moveFile,
      readBytes, writeBytes; `FsError` sealed trait (NotFound,
      PermissionDenied, NotSupported, IoError); `Feature.FileSystem` gate.

      **`std.os`**: env(key), envOrElse(key, default), args: List[String],
      exit(code), cwd, sep, pathJoin(parts*), pathDirname, pathBasename,
      pathExtname, pathResolve, pathIsAbsolute, tempDir, tempFile,
      platform: Platform (Jvm | NodeJs | Browser | Native),
      homedir, hostname.

      **`std.process`**: exec(cmd, args, opts) → ProcessResult
      (stdout, stderr, exitCode); spawn(cmd, args, opts) → Process
      (write to stdin, read stdout/stderr as streams, wait, kill);
      ProcessOptions (cwd, env, timeout); ProcessError sealed trait.
      Note: Browser target throws ProcessError.NotSupported for all ops.

      JS-Node vs JVM vs Rust vs browser-sandbox policy for each module.
      Commit: `spec: std-fs-os`.
      ✓ Landed 2026-06-09 (0757d27): 271-line spec, all 3 modules + 6 phases.

### Phase 2 — JVM backend (fs-plugin + os-plugin)

- [x] **std-fs-os-p2-jvm** — Create `runtime/std/fs-plugin/` with
      `FsPlugin.scala` + `FsIntrinsics.scala` (std.fs + std.os).
      Create `runtime/std/os-plugin/` with `OsPlugin.scala` +
      `OsIntrinsics.scala` (std.process via `ProcessBuilder`).
      JVM impl: `java.nio.file`, `System.getenv`, `ProcessBuilder`.
      Register both in `build.sbt`. Conformance tests:
      `tests/conformance/fs-*.ssc`, `tests/conformance/os-*.ssc`,
      `tests/conformance/process-*.ssc`. Commit: `feat(fs-plugin): JVM backend`.
      ✓ Landed 2026-06-09 (30134b8): fs-plugin (13 ops, 13 tests) + os-plugin
      (18 ops incl. exec, 14 tests). allPlugins registered.

### Phase 3 — JS/Node backend

- [x] **std-fs-os-p3-js** — Node.js preamble wiring `std.fs` → `node:fs`,
      `std.os` → `node:os` + `node:path`, `std.process` → `node:child_process`.
      Browser: `FsError.NotSupported` / `ProcessError.NotSupported` for
      fs/process ops; env returns `{}`, args returns `[]`, platform = Browser.
      Same conformance tests pass on Node target.
      Commit: `feat(fs-plugin): JS/Node backend`.
      ✓ Landed 2026-06-09 (d32bf9a): JsRuntimeFs.scala; 16 fs + 15 os + exec();
      lazy require; browser stubs; 21 tests.

### Phase 4 — Rust backend

- [x] **std-fs-os-p4-rust** — Full std.fs/std.os/std.process Rust lowering:
      12 fs helpers, 15 os helpers (env→Option, path*, platform=Native), ProcessResult+exec.
      All use pure std (no extra crates). 14 new tests (176 total). Landed 2026-06-09.

### Phase 5 — stdlib .ssc files + examples

- [x] **std-fs-os-p5-stdlib** — Add `runtime/std/os.ssc` and
      `runtime/std/process.ssc` alongside existing `fs.ssc`. Expand
      `fs.ssc` with new extern signatures. Add runnable examples:
      `examples/fs-roundtrip.ssc`, `examples/os-env.ssc`,
      `examples/process-exec.ssc`. Update `README.md` capabilities table.
      Commit: `feat(std): fs/os/process stdlib modules`.
      ✓ Landed 2026-06-09 (ee673a5): fs.ssc expanded (16 defs), os.ssc new,
      process.ssc new; 2 examples; README updated.

### Phase 6 — audit & boundary documentation

- [x] **std-fs-os-p6-cleanup** — Audit all `.ssc` files for `java.*`
      imports; migrate any found to `std.fs`/`std.os`/`std.process`.
      Note in `specs/std-fs-os.md` §"Scope": `.sc` Scala-CLI host
      scripts (bench/run.sc etc.) may use JVM APIs — that is intentional.
      Add one-liner to `AGENTS.md` §"Codebase architecture rules":
      "`.ssc` user code must never import `java.*` — use `std.fs`,
      `std.os`, `std.process` instead."
      Commit: `docs(std-fs-os): boundary rule in AGENTS.md + spec`.
      ✓ Landed 2026-06-09: audit done (covered by backend-blocks-p7);
      AGENTS.md already references specs/std-fs-os.md; specs/std-fs-os.md §6 scope note added.

---

## std.yaml — YAML parse / stringify (new)

**Motivation:** `.ssc` user code has no way to call `parseYaml(s)` or `toYaml(v)` today.
`SimpleYaml` already covers ~90% of real YAML (block/flow mappings+sequences, scalars,
quoted strings, comments, literal blocks) but only returns internal Java types — not
ScalaScript `Value`s.  A `yaml-plugin` + `std/yaml.ssc` closes this gap.

**Scope:**
- JVM: `SimpleYaml.load` → `Value` converter + plain-Scala YAML serializer (no snakeyaml needed).
- JS/Node: inline mini-parser + serializer in JsRuntimeYaml (subset matching SimpleYaml).
- Anchors/aliases, multi-document, YAML 1.2 tags: **out of scope** for this sprint.
- `yaml`/`yml` fenced blocks already produce `ContentValue` (content API) — Phase 4 wires
  them to ScalaScript-visible variables too.

Spec to write first: `specs/std-yaml.md`

### Phase 1 — spec

- [ ] **yaml-p1-spec** — Write `specs/std-yaml.md`. Cover:

      **`std.yaml`**: `parseYaml(s: String): YamlValue`;
      `toYaml(v: YamlValue): String`;
      `YamlValue` sealed trait (`YStr`, `YNum`, `YBool`, `YNull`, `YArr`, `YObj`);
      helper `.str`, `.num`, `.bool`, `.arr`, `.obj` accessors returning `Option[...]`;
      `YamlValue.from(v: Any)` bridge for dynamic values.

      Supported YAML subset: block/flow mappings+sequences, single+double-quoted strings,
      null/bool/int/double scalars, comments, literal/folded block scalars.
      Out of scope: anchors, aliases, merge keys, multi-document, YAML 1.2 tags.

      Backend policy table (JVM / JS-Node / Browser / Rust).
      Commit: `spec: std-yaml`.

### Phase 2 — JVM plugin

- [ ] **yaml-p2-jvm** — Create `runtime/std/yaml-plugin/` with
      `YamlInterpreterPlugin.scala` + `YamlIntrinsics.scala`.

      `parseYaml(s)`: `SimpleYaml.load[Any](s)` → recursive converter returning
      `Value.MapV` / `Value.ListV` / `Value.StringV` / `Value.IntV` / `Value.DoubleV` /
      `Value.BoolV` / `Value.NullV` (tag names matching `YamlValue` sealed trait).

      `toYaml(v)`: pure-Scala serializer — walks `Value` tree, emits block-style YAML
      (mappings indented 2, sequences with `- ` prefix, strings quoted when needed).

      Register in `build.sbt`. Tests: round-trip `parseYaml(toYaml(v)) == v` for
      Map, List, nested, scalars, edge cases (empty string, null, bool).
      Commit: `feat(yaml-plugin): JVM backend`.

### Phase 3 — JS/Node preamble

- [ ] **yaml-p3-js** — Add `JsRuntimeYaml.scala` to `runtime/backend/js/`.

      `parseYaml(s)`: port `SimpleYaml` subset to JS (or inline a ~200-line
      pure-JS block/flow parser matching the JVM subset exactly).

      `toYaml(v)`: JS serializer — same block-style output as JVM.

      Wire into `JsGen.generateRuntime` unconditionally.
      Tests: text-shape assertions that `parseYaml` and `toYaml` appear in preamble;
      round-trip conformance test against Node.js runner.
      Commit: `feat(jsgen): std.yaml JS/Node preamble`.

### Phase 4 — stdlib `.ssc` + examples + fenced-block wiring

- [ ] **yaml-p4-stdlib** — Add `runtime/std/yaml.ssc` with `YamlValue` sealed trait
      declarations and `parseYaml`/`toYaml` extern defs.

      Wire `yaml`/`yml` fenced blocks: bind block content as a `YamlValue` variable
      named `<sectionId>_yaml` (or `<sectionId>.yaml`) in the surrounding ScalaScript
      scope — same pattern as `html`/`css` string blocks bind `<sectionId>.html`.

      Add example: `examples/yaml-parse.ssc` — parse a YAML string, navigate it,
      round-trip through `toYaml`.  Update `README.md` capabilities table.
      Commit: `feat(std): std.yaml stdlib module + fenced-block wiring`.
