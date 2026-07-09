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
