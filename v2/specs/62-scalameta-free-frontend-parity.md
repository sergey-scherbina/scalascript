# 62 — Scalameta-free frontend parity

> Status: **in progress** (2026-07-09). Scope estimate + active work on dropping
> scalameta from ScalaScript 2.

## Progress log — native end-to-end run via the PLUGIN runtime (`BridgeCli run-ir`)

Measured as: native `ssc1` lower → plugin-enabled v2 runtime, OK+TIMEOUT / 195
(TIMEOUT = a server that started). Each slice keeps conformance at 640/640.

| After | OK+TIMEOUT | Slice |
|---|---|---|
| baseline (bare kernel VM) | 3 | measurement artifact — empty registry (K62.5b) |
| + K62.7a | (measured later) | method-call dispatch → `__method__` |
| + K62.6 | 10 | skip Markdown-link imports `[…](path)` + `import a.b` |
| + K62.6b | 12 | top-level `var` + assignment (global cells) |
| + K62.6d | 14 | skip `@Name(...)` annotations |
| + K62.6c-ops | 18 | wildcard `import a.b.*`, cons `::`, pair `->`, char `'x'`, bitwise `& | ^ ~`, shifts `<< >> >>>` |
| + K62.6c-for | 18 | `for x <- xs [if g] yield/do e` (single generator + guard) — parse gap closed; those files have other blockers so run-count is flat |
| + K62.6c-map | 22 | `Map(k -> v, …)` initial entries (was silently empty), `new Foo(x)` == `Foo(x)`, multi-generator `for` (flatMap; + List.flatMap default arm) |
| + K62.6c-under | 22 | underscore placeholder `filter(_ % 2 == 0)` → `filter(x => x % 2 == 0)` (arg-level tree-walk `ph`→lambda) — closed the `_` class; run-count flat (those files have other blockers) |
| + K62.6c-indent | **33** | **significant indentation** — the lexer emits `NL <indent>` tokens; a layout pass turns brace-less indented blocks (`def f() = <indent> stmts`, `if/while/for` bodies) into virtual `{ ; }`, with continuation handling (`else`/infix/`.` on a new line). +11 files — indented def bodies are pervasive. |
| + K62.7b | **38** | **uid-static dispatch** — `Dataset.of`/`Graph.vertices`/`Transport.spawn` (unknown uppercase objects) route through `IrPrim(__method__, [str m, Ctor(Foo,[]), args])` → the plugin runtime's `__fallback__.Foo.m`; user objects keep static `Foo_m`. Pure lowering, no runtime change. +5 files. |
| + K62.6c-forbind | 38 | `for (a, b) <- pairs [yield/do]` tuple-pattern binder (destructure via `val a = __fp._1; …`) — parse gap closed (that file has other blockers). |

Remaining after 22 (all harder / cross-territory): brace-less indented multi-stmt
def bodies (needs newline/indentation tracking — a lexer change), `$`, missing
methods (`takeWhile`/`dropWhile`/…), uid-static `Dataset_of`/`Graph_*` (K62.7b,
needs runtime object registration), `_sel_get`/`_sel_env` field access (blocked by
the K62.7a `__method__` VM bug — sibling-owned), arity, `spark`.

**Session arc:** end-to-end via plugin runtime **3 → 22**; the common Scala idioms
(imports, `::`/`->`, char, bitwise, shifts, `for`, `Map`, `new`, multi-gen, `_`)
are now closed on the native front. The tail is hard/cross-team.

**Known latent bug (from K62.7a):** 3 files (`mcp-search-server`,
`traditional-payments`, `x402-metamask`) now FRONTERR with `match: scrutinee not
Data: "__method__"` — a `__method__`-dispatched call feeds a match scrutinee in a
shape the VM compile mishandles. Not yet minimised. This also blocks extending the
`__method__` routing to `resolveField` (field access), which was tried and reverted
(net −1). Fixing this unblocks both.

Remaining top blockers (measured): parse-completeness `_err` (~20 — bitwise/`$`/
char/indented brace-less def bodies), **uid-static dispatch** `Dataset_of`/`Graph_*`
(K62.7b — harder: needs method-object *registration*, see below), `_sel_get`/
`_sel_env` field access (extend K62.7a to `resolveField`), named-args
(`authServer: missing 'issuer'`).

**K62.7b is not a pure lowering change.** `Dataset.of(x)` in the bridge dispatches
via `__method__("of", <Dataset method-object>, x)` → `__fallback__.Dataset.of`. The
bridge knows `Dataset` is a method-object because it parsed the `object Dataset {…}`
in the std import; the native path skips imports and the registry does **not**
register `Dataset` as a resolvable global. So closing it needs the receiver object
registered/synthesised, not just an emit change — more than K62.7a.

## TL;DR

**Yes — and the parser is not the hard part.** scalameta in this codebase is only
the **parser + typer** of the v1 frontend. v2 already has a clean-room,
scalameta-free frontend tower (`mira-md` → `ssc1-front` → `ssc1-check` →
`ssc1-lower`, written in ssc0/Mira, running on the 913-line kernel). Measured
against the **real** `examples/*.ssc` corpus (195 files), that native frontend
already parses+lowers **186/195 (95.4%)** — after a one-line fence-tag fix.

The remaining surface gap is **8 files / 2 localized statement-sequence lowering
bugs** in `ssc1-lower.ssc0` — not missing language features, not a typer rewrite.

The real cost of "v2 without v1" is **not** removing scalameta. It is
**runtime/stdlib/plugin/effect semantic parity** on the native VM — an axis that
is entirely independent of scalameta.

## Background — the two frontends

| Path | Pipeline | scalameta? | Runs |
|---|---|---|---|
| **Compat / bridge** (`--v2`, busi 61/61) | `.ssc` → v1 parser+**Typer** → IrExpr → Core IR → v2 VM/JS/Rust | **yes, deeply** | today |
| **Native tower** (`v2/ssc1`) | `.ssc` → `mira-md` → `ssc1-front` → `ssc1-check` → `ssc1-lower` → Core IR → v2 VM | **no** | subset |

How deeply scalameta is wired into the compat path:

- It **is** the parser for the code inside fences:
  `dialects.Scala3(Input.VirtualFile(...)).parse[Source]` (`v1/.../parser/Parser.scala:114`).
- The entire `Typer.scala` (2004 lines) is written directly against
  `scala.meta.Tree` — every `inferType`, `checkAssignable`, pattern case.
- Footprint: 16 files / 29 imports in `v1/lang/core`; ~11k lines of frontend
  (parser+typer+transform+artifact) coupled to it.
- The kernel and all four v2 backends (VM/JS/Rust/WASM) are **already**
  scalameta-free. The only seam where scalameta enters v2 is `v2FrontendBridge`.

## Method

Ran the native frontend's parse+lower stage (`bin/ssc1-run.ssc0`, which emits Core
IR — exit 0 ⇔ the frontend accepted the file) over every `examples/*.ssc`
(195 files), via an assembly jar of `v2/src` (`java -Xss16m -jar ssc.jar run
bin/ssc1-run.ssc0 <file>`). Failures were grouped by the VM exception message,
which names the exact unhandled AST node (`match: no arm for <Tag>/<arity>`).

Reproduce: package `v2/src` (`scala-cli --power package v2/src --assembly`), then
loop the jar over `examples/*.ssc`.

## Result

| Config | PASS | FAIL |
|---|---|---|
| As-shipped (`scalascript` fence only, default stack) | 152/195 | 43 |
| **+ fence-tag fix (accept `scala`) + `-Xss16m`** | **186/195** | **9** |

Two cheap, non-surface issues accounted for 34 of the original 43 "failures":

1. **Fence-tag filter (32 files).** `ssc1-run.ssc0` collected only
   ` ```scalascript ` blocks; the corpus writes ` ```scala `. In v1 **both** are
   executable ScalaScript — `Lang.isParseable = isScalaScript || isStandardScala`
   (`v1/.../ast/Lang.scala:99-101`); both go through the same scalameta parse +
   Typer. Fix = broaden the native runner's filter to accept `scala`. Landed in
   this milestone (1 line). Note: spec `61-fence-languages.md` lists `scala` as a
   "deferred passthrough" — that aspiration does **not** match how v1/the corpus
   actually treat `scala`; 61 should be reconciled.
2. **Compiler stack depth (StackOverflowError, ~3 files).** The VM's compile
   stage recurses on large programs; `-Xss16m` clears it. Real fix = make the
   lowering/compile loops in `Runtime.scala` iterative (or set a bigger default
   `-Xss` in the launchers). Robustness, not a surface gap.

## The two surface bugs — ROOT-CAUSED and FIXED (2026-07-09) → 194/195

Both `Pair/2` and `Nil/0` were mis-diagnosed at first as "statement-sequence
lowering edge cases". Bisected to minimal repros, they were unrelated and both
upstream of where the crash surfaced. After the fixes, native parse+lower reaches
**194/195** (all but `deploy.ssc`), zero regressions across the corpus.

### Bug 1 — `Pair/2`: `buildPostfix` never consumed a trailing `{ block }` (6 files) ✅ FIXED

Files: `rozum-agent{,-pool,-schema-derived}`, `ws-chat`, `dsl-yaml-like`,
`mcp-search-server` — all use top-level `route(...) { req => ... }` / `foo { x => ... }`.

Root cause: `buildPostfix` (`ssc1-front.ssc0`) handled `.field`, `(args)`,
`[types]`, `match`, but **not** a trailing `{ … }` block argument. So
`route("POST","/x") { req => … }` at expression/statement-head position parsed as
*two* things — the call `route("POST","/x")` and a **separate standalone block**
`{ req => … }` — instead of `route(...)(lambda)`. Inside that mis-attached block,
`id = expr` (e.g. `calls = calls + 1`) was then parsed as `idx_assign` (`a(i) = rhs`),
whose lowering does `match ldata { case Pair(arrFn, idxArgs) => … }` on a bare
variable → `no arm for Pair/2`. The "OK" siblings (`foo { y => bar(y) }`) were also
silently mis-parsed — the call was dropped — they just didn't crash.

Minimal repro: `var c = 0` ⏎ `foo { y => c = c + 1 }`.

Fix: add a trailing-`{` arm to `buildPostfix` that consumes the block via the
**lambda-aware** `parseBlockArg` (strips a `param =>` header) and applies it:
`e { body } → e(body)`. This is the same block-arg handling `parseBlock`'s `go`
loop already did inside braces; it was just missing at the top level.

### Bug 2 — `Nil/0`: single-arg `String.substring(from)` (2 files) ✅ FIXED

Files: `dsl-mini-language`, `webauthn-demo`.

Root cause: `resolveMethodCall` (`ssc1-lower.ssc0`) resolved `.substring` with
`match rargs { case Cons(frm, r0) => match r0 { case Cons(too, r1) => sslice(...) } }`
— **only** the two-arg `substring(from, to)` form. Single-arg `s.substring(from)`
gives `r0 = Nil`, and the inner match has no `Nil` arm → `no arm for Nil/0`.
(The tuple/`var`/`while` context in the corpus files was a red herring; the atomic
repro is just `val s = "abc"` ⏎ `s.substring(1)`.)

Fix: add the `Nil` arm — Scala's `substring(from) == substring(from, length)`, so
emit `sslice(robj, frm, slen(robj))`. Verified semantically: `"hello".substring(2)`
→ `"llo"`.

### Correctly out of scope (1 file)

`deploy.ssc` — contains only ` ```sh ` fences (a deploy manifest), no executable
ScalaScript. The "no scalascript blocks found" result is correct.

## Honest scope — three independent axes (all measured 2026-07-09)

Dropping scalameta means the native tower must reach parity on **all three**. Only
the first is a *parser* problem; the third is the bulk.

| Axis | Native artifact | Measured coverage | Status |
|---|---|---|---|
| **1. Parse + lower** (produces IR) | `ssc1-front` + `ssc1-lower` | **194/195** lower to IR; but **~40** of those carry a silent parse-completeness gap (see K62.5) | axis-1 *lowering* DONE; ~40 parse-completeness gaps remain |
| **2. Type-check** | `ssc1-check` (425-line HM subset) | **162/195** pass; **32 false-positive rejections** | measured; off the critical path (run skips it) |
| **3. Runtime / stdlib / plugin / effect** | `Runtime.scala` + native stdlib | **3/195** run end-to-end; ~150 need missing intrinsics | **the bulk — a standing program** |

### K62.4 — native type-checker (`ssc1-check`) coverage: 162/195, 32 false positives

Harness: `bin/ssc1-check-run.ssc0` (fence-extract → `parse` → `ssc1TypeCheck`).
The checker is Dyn-lenient in most places (it does *not* reject `val x: Int =
"hello"`), yet **too strict on a few operators**, producing 32 false-positive
rejections of programs that run fine on v1 / the v2 bridge:

| Category | Files | Example message |
|---|---|---|
| `++` / `+` string-concat unification | 11 | `cannot unify function with non-function` |
| `/` `%` `*` "requires Int left operand" (Float args) | 8 | `/ requires Int left operand` |
| `String`/`Int`/`Bool` unify | 9 | `cannot unify String with non-String` |
| if-branch / comparison type | 4 | `if branches must have the same type` |

These are false positives — the type-checker rejects valid programs. It is **not**
on the critical path to scalameta-free (the `ssc1-run` execution path skips it
entirely), so it does not block dropping scalameta; it is a quality gate to close
before making `ssc1-check` mandatory.

### K62.5 — native end-to-end run (`ssc1-run` → `run-ir`): 3/195

Harness: parse+lower → execute on the native VM (12 s timeout). Result: **3 run to
completion, 191 error, 1 non-code.** The errors split cleanly:

- **Class A — hidden parse-completeness gaps (~40 files).** Surface as `unbound
  global: _err` / bare-keyword globals (`import`, `var`, `for`, `_`, `inline`).
  Root causes (instrumented `parseAtom`): **bitwise operators** (`& | ^ ~ << >>`
  — VM has `i.and`/`i.or`/`i.xor`, shifts/NOT missing), **`@` annotations**
  (`@main`/`@model`), **`$`**, **char literals in some positions**, **Markdown-link
  imports inside code**. These produce runnable-but-dangling IR, which is why
  axis-1's "194/195 lowers" over-counts. Fixing them is bounded frontend work but
  **low marginal value for run coverage** — those same files also need Class B.
- **Class B — unresolved intrinsic/method symbols (~150 files).** `Dataset_*`,
  `_sel_authServer`/`_sel_get`, `Graph_*`, `Db_query`, `spark`, `signal`, etc.

### K62.5b — CORRECTION: the bare-kernel 3/195 was a measurement artifact

The 3/195 above ran native IR on the **bare `v2/src` kernel VM**, where
`V2PluginRegistry` is **empty** — so every stdlib symbol is "unbound global". That
is not the real gap. The v2 runtime resolves `IrGlobal(name)` as
`globals.getOrElse(name, V2PluginRegistry.lookupGlobal(name))`, and `PluginBridge.
loadAll()` populates that registry with the **entire v1 plugin/effect stdlib**
(http, sql, crypto, mcp, actors, spark, …). The scalameta bridge path (`busi
61/61`) runs on exactly this registry.

Added a `run-ir` mode to `BridgeCli` (`loadAll()` + run native IR) and re-measured
against the **plugin-enabled** runtime. Findings:

- The intrinsics **exist and resolve** — e.g. `spark`, `serve`, `args` are live
  globals; `spark-schema-mapping.ssc` runs through the v1 bridge and dispatches
  `SparkSchemaCodec.schema` as an effect `Op`. Class B is **not** "missing stdlib".
- Native failures are a **name/dispatch mismatch**, not absence: `ssc1-lower`
  emits `_sel_method` globals and `Foo_method`/`Dataset_of` for method/typeclass
  calls, whereas the runtime dispatches method calls through
  `IrPrim("__method__", [str name, recv, …args])` (→ `V2PluginRegistry.lookup(
  "__method__.name")`, with unhandled ones becoming free `Op`s). Aligning the
  native method-call lowering to emit `__method__` (K62.7a) makes those calls
  resolve — verified: after the change, `_sel_authServer`/`_sel_get` files stop
  failing there and advance to the *next* blocker (their Class A `_err`).

**Conclusion (revised):** the parser is not the obstacle, and neither is the
stdlib — it already exists in the v2 runtime and the bridge already uses it.
Dropping scalameta reduces to **two bounded lowering/frontend jobs**, both
independent of scalameta:

1. **Parse-completeness (Class A, ~40 files)** — bitwise/`@`/`$`/char/link-imports
   in `ssc1-front`.
2. **Dispatch alignment (Class B)** — route native method/typeclass/`Foo.method`
   lowering through the runtime's existing `__method__`/`Op` mechanism instead of
   `_sel_`/`Foo_method` globals (K62.7a started this for the generic method case).

The two **compound per file** (a file needs all its blockers cleared before it
runs end-to-end), so end-to-end pass-count lags until both are closed — but the
work is bounded frontend/lowering, **not** a stdlib rewrite. This is a much smaller
program than the earlier "re-grow the stdlib" estimate.

## Path to scalameta-free (revised after K62.5b)

1. **Parse+lower to IR** ✅ DONE (2026-07-09): fence policy + 2 surface bugs +
   launcher stack → **194/195**.
2. **Dispatch alignment (K62.7)** — route native method/typeclass/object-method
   lowering through the runtime's `__method__`/`Op` mechanism. Started (K62.7a:
   generic `_sel_` method fallback → `__method__`). Remaining: the `Foo_method`
   uid-static case (`Dataset.of`, `Graph.vertices`, given/typeclass methods).
3. **Parse-completeness (K62.6)** — bitwise/`@`/`$`/char/link-imports in
   `ssc1-front` (the ~40 Class-A `_err` files).
4. Run native front → **plugin-enabled** runtime (`BridgeCli run-ir`, or wire the
   plugin registry into the `ssc`/`ssc1` launchers) and re-measure end-to-end.
5. **(Optional) axis 2** — close the 32 type-check false positives, only if
   `ssc1-check` becomes mandatory.
6. When native front + plugin runtime close the corpus, scalameta becomes an
   **optional, frontend-only** dependency — delete it from `v1/lang/core` and drop
   the `v2FrontendBridge` seam. Kernel + 4 backends are already free of it.

**Bottom line for planning:** "give up scalameta" is **not** a stdlib rewrite. The
stdlib already exists in the v2 runtime and the bridge already uses it (busi
61/61). What remains is two bounded frontend/lowering jobs — parse-completeness and
dispatch alignment — plus running the native front against the plugin-enabled
runtime. The scary-sounding dependency (scalameta) *and* the scary-sounding bulk
(the stdlib) are both cheaper than they first appear.

---

## K62.6e/6f/6g — 2026-07-09 session (field-access split, list-literals, default-params)

End-to-end (bare-kernel `BridgeCli run-ir`) **38 → 40**; parse-completeness `_err`
files **16 → 7**. Three landed changes, all in `ssc1-front`/`ssc1-lower` only,
conformance green:

1. **K62.6e — field-access `__method__` dispatch with `isCaseField` split.**
   `resolveField`'s `_sel_<field>` fallback (an *unbound* global) now routes to the
   VM's `__method__` dispatch, which resolves plugin fields and record field-index.
   A generated case-class accessor still wins: `collectCaseFields` pre-scans every
   `casecls` field name into `caseFieldsCell`, and `selOrMethod` keeps `_sel_<field>`
   for those (the naive route-everything version regressed `kc7 case class Point` →
   `Stub("Point.x")`). +2 e2e (`_sel_get`/`_sel_env`/`_sel_show`/… families).
2. **K62.6f — list-literal `[a, b, c]` → `List(a, …)`.** `ssc bracket sugar in
   expression position; `parseAtom` now emits `mkApp(List, elems)` (lowers to the
   existing `Cons`/`Nil` chain). Statement-level `[names](path)` link-imports are
   unaffected (handled earlier in `parseOneStmt`). Closed 9 `_err` files' parse.
3. **K62.6g — default parameters `def f(x: T = expr)`.** `parseParam` consumes the
   `= <default>` after the type annotation (parse-only; call-site default *synthesis*
   remains a separate job, like the bridge's `buildWithDefaults`).

**KEY FINDING — the run-count ceiling is no longer the native front.** With parse
+ field-dispatch closed, the corpus files that still fail are blocked on **unbridged
v1 compiler plugins**, not on anything in `ssc1-front`/`ssc1-lower`:
- `staticDataTable`, `contentToolkitSection`, … → v1 `content-plugin`
  (`ContentIntrinsics.scala`) + `JvmRuntimeUiPrimitives`, not registered in the v2
  plugin runtime.
- `spark`, `Transport`, `System`, `localLoopbackCluster`, … → other unbridged
  plugins.
- `[names](std/…)` module-loading is *also* gated on this: those std modules are
  mostly `extern def` declarations of the SAME plugin intrinsics.

Moving the corpus further therefore needs **v2 plugin bridging** (the
payments-bridge pattern), a separate lane from the frontend. Remaining pure-front
items are small and semantically tricky (pattern guards `case P if g =>` need
fall-through; typeclass `derives`/`TC[T].encode` needs derivation+summon) and are
themselves downstream-blocked, so they do not move the e2e count on their own.

---

## K62.8 — 2026-07-10 (CORRECTION: the lever is module-loading, not plugin bridging)

The "unbridged plugins" finding above was **half right**. Probing the actual v2 plugin
runtime (`PluginBridge.loadAll()` → `ServiceLoader` over `Backend`) shows the leaf UI
primitives **ARE** registered: `signal`/`element`/`textNode`/`fragment`/`serve`/
`contentToolkitSection` all resolve (they are `NativeImpl` intrinsics in
`frontend-plugin`/`content-plugin`, both on the bridge classpath). What is *unbound* is
the **ssc-defined std helpers** built on top of them — `staticDataTable`, `jObj`,
`lower`, `fcol`, `contentToolkitOptionsWithActions` live in `v1/runtime/std/{json,
ui/data, ui/content, ui/lower}.ssc` as ordinary `def`s. So the real lever is
**module-loading** (load those std `.ssc` modules), NOT plugin bridging — and it is in
the frontend lane after all.

Module-loading is a **source-preprocessing** step: `FrontendBridge.resolveImportsCode`
(the v1 reference) resolves `[names](path)` link-imports, DFS-loads each module's code
(dedup by canonical path), and concatenates `prelude + main` BEFORE parsing. Path
resolution: relative-to-file → `std/…` root → dev-tree walk to `v1/runtime/std`.

**Landed this session (parse-completeness for std modules — the prerequisite):**
- **`/* … */` block comments** in the lexer (`skipBlockComment`, wired into `skipWS`
  and `skipToCode`). The native front previously had NO block-comment support — every
  stdlib module's doc comments were parsed as code. Biggest single unblock.
- **statement-level type/decl skips**: `type X = Y`, `opaque type`, `extern def …`
  (declares a plugin intrinsic — no body), `enum E { … }`. All erased/no-op.
- **`skipToStmt` boundary fix**: top-level statements have NO `;` separators (layout
  emits none at depth 0), so a declaration-body skip must halt at the next decl
  keyword — added `type`(kw) + `extern`/`opaque`/`enum`(id) stops (consecutive
  `extern def`s over-skipped before).

**REVERTED — brace-less `match`.** Adding `match` to `isLayoutOpener` (so `x match <NL>
case …` gets virtual `{ ; }`) parsed the isolated case but **regressed the corpus
40 → 22** with ~18 hangs/kills (`RUNERR(137)`): making `match` a layout opener desyncs
the many existing braced/inline `match` sites. Brace-less match needs a narrower
mechanism (only open the block when the next token is `case`, or handle it inside
`parseMatchExpr`), not a blanket layout opener. Left out of this batch.

Result: the **leaf** std modules parse clean (0 `_err`): `json`, `ui/primitives`,
`ui/data`, `ui/nodes`, `ui/layout`, `ui/typography`, `ui/input`, `mapreduce/index`.
(These use no brace-less match; the code-heavy modules that DO — `ui/lower`,
`ui/theme` — still need it, so they remain in the desync bucket below.)

**Still blocking module-loading (the remaining work, multi-session):**
1. **Code-heavy std modules desync** — `ui/content`, `ui/theme`, `ui/lower`, `agent`,
   `mcp/server`, `http`, `mapreduce/{dataset,distributed,shuffle}` still emit `_err`.
   Isolated typed lambdas `(x: T) =>`, multi-param lambdas, and brace-less match all
   parse fine on their own, so the residue is a **parser desync** inside multi-line
   block-lambda / nested constructs (a `}`-imbalance cascade shows up as dozens of
   spurious `=>` triggers). Needs targeted layout work, not a single feature.
2. **The driver** — replicate `resolveImportsCode` in `ssc1-run.ssc0`: scan imports,
   resolve paths (deterministic: `std/X` → `<main-dir>/../v1/runtime/std/X`; relatives
   vs the importing module's dir), DFS + dedup, concat. `#io.readFile` exists but
   **throws** on a missing path (`Files.readAllBytes`), so robust multi-candidate
   resolution wants a fault-tolerant read primitive (`io.tryReadFile`/`io.fileExists`).
3. Nearly every content/ui/mapreduce corpus file imports ≥1 code-heavy module, so the
   driver alone (with only leaf modules clean) unblocks ~1 file (`ui-typed-json`, which
   imports only `json.ssc`). The desync work in (1) gates the bulk.

---

## K62.9 — 2026-07-10 (partial functions + tuple patterns; layout for brace-less match investigated)

**Landed (safe, corpus 40 → 41, ssc1-front only):**
- **Partial-function literals `{ case P => B; … }`** → `__pf => __pf match { arms }`
  (`parseBlockArg`). Common in `xs.map { case (k, v) => … }`; was a pre-existing gap
  (both baseline and native produced `_err`).
- **Tuple patterns `case (a, b, …) =>`** → `cpat("Pair"/"TupleN", varnames)` in
  `parsePat` (binder names only — ssc0 patterns are shallow).

**Investigated but NOT landed (regresses the corpus — needs the block below first):**
The code-heavy std modules (`ui/{content,theme,lower}`, `agent`, `mapreduce/*`) fail on
**brace-less `match` with multi-statement arm bodies** — the module-loading critical
path. Two layout changes make it work in isolation but regress the corpus:
- `=>` and `match` as `isLayoutOpener`s (arm/lambda bodies get their own `L` block).
- **bracket-aware layout** (`go` tracks `(`/`[` depth; suppresses the offside rule
  inside brackets, reset per `{…}` via a pd stored in the `B` frame) — required so a
  lambda `map(x =>\n body)` doesn't open an `L` block that `)` cannot close.
With both, `ui/lower.ssc` goes 37 → 7 `_err` and multi-statement match arms / block
lambdas parse. BUT the corpus drops 40 → 37: `dsl-mini-language` and
`distributed-dataset-wire-protocol` flip OK → RUNERR. Root cause: they use **nested
constructor patterns** ssc0 cannot represent — `Some((l, '+', r))` (ctor over a tuple
with a **literal** `'+'`), `Some(TJsonValue.Num(value))` (ctor over ctor). In baseline
these files had 36 / 9 `_err` (the WHOLE nested match failed to parse) yet ran "OK"
because the dead `_err` was never reached; the openers parse the match FURTHER (16 / 11
`_err`) so the residual nested-pattern `_err` now lands in reached code and crashes.
Partial-parse is worse than no-parse for such files.

**Precise remaining path to land the openers (and unblock code-heavy modules):**
1. **Nested constructor patterns** — parser produces a nested pattern; `lowerMatch`
   emits `case Ctor(__t) => __t match { case <inner> => body }` with correct de Bruijn
   scoping. Recovers `distributed-…` (ctor-over-ctor).
2. **Literal patterns inside tuples + guards** — `case (l, '+', r) =>` needs the `'+'`
   as a literal match (guard `__t._2 == '+'`), not a binder. Recovers `dsl-mini-language`.
3. Only then do the `=>`/`match` openers + bracket-aware land corpus-neutral (≥41).
4. Beyond that, code-heavy modules ALSO need **named arguments** `f(field = value)`
   (theme.ssc: 78 `,` + 18 `=` triggers from `Theme(colors = …, spacing = …)`) and
   **multi-line case-class defs** before they reach 0 `_err`. So module-loading remains
   a multi-step frontend effort; each construct is real and landed incrementally to keep
   the corpus green.

---

## K62.10 — 2026-07-10 (nested constructor + tuple patterns; a real match-lowering step)

**Landed (correct, parse-safe — deterministic parse A/B across the corpus shows ZERO
regressions; all pattern probes produce correct output).** Pattern fields are now
SUB-PATTERNS (vpat/wpat/cpat/lpat), not bare binder-name strings, so nested patterns
destructure via inner matches:
- `parsePat` (ssc1-front): ctor/tuple fields recurse into `parsePat` (`goSubPats`);
  literals become `lpat` (int/str/float/bool).
- `lowerMatch` (ssc1-lower): `fldBinders` assigns a binder to each field (vpat→name,
  wpat/cpat/lpat→fresh `__npN` temp); `fldObligations` collects the NESTED (cpat) fields
  as `(localPos, subpat)`; `dischargeObs` emits an inner `IrMatch(IrLocal(pos), [arm])`
  per obligation, threading the de-Bruijn shift as each inner arm prepends its
  sub-binders. Verified: `Some(Left(x))→105`, `Some((a,b))→34`, `Cons(a, Cons(b,_))→3`,
  `((a,b),(c,d))→10` (multiple nested), `Some(x)→7` / `None→99` (no regression on flat).
- **lpat (literal sub-fields) bind a temp and are NOT checked** — ssc0 `IrMatch` has no
  per-arm guards, and same-tag arms (`Some((l,'+',r))` vs `Some((l,'-',r))`) collapse to
  ONE `Some/1` arm. So literal-discriminated arms take the first-matching-tag arm
  (graceful, no crash — same wrongness as the baseline where the whole nested match
  failed to parse). Correct literal matching needs a match-compiler pass (arm grouping +
  guards), out of scope here.

**Effect on the openers path (measured, NOT landed):** with nested patterns +the
`=>`/`match` layout openers + bracket-aware layout, the code-heavy std modules go to ~1
`_err` (ui/lower.ssc 37→1, ui/content.ssc 26→1, mapreduce/dataset.ssc 29→1,
mapreduce/distributed.ssc 63→1) — the module-loading prerequisite is nearly done. The
last shared `_err` is a **single-line block arm body** `case X => val n = …; expr`
(`val`+`;`+expr with no braces): `parseMatchArm` calls `parseExpr` on the body, which
can't parse the `val`. Needs `parseMatchArm` to read the body as a statement sequence
until the next `case`/`}`. After that, `dsl-mini-language` / `distributed-…` still
regress because the openers make them parse far enough to hit NEW blockers (dsl:
`_sel_andThen` missing method) — the openers remain corpus-negative until that per-file
chain is cleared, so they stay unlanded. Nested patterns landed standalone (safe).
