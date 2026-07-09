# 62 — Scalameta-free frontend parity

> Status: **measured** (2026-07-09). Scope estimate for dropping scalameta from
> ScalaScript 2. Answers the question: *"Can v2 give up scalameta, and how far
> are we?"*

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

## Honest scope — three independent axes

Dropping scalameta means the native tower must reach parity on **all three**, but
only the first is a *parser* problem:

| Axis | Native artifact | Measured coverage | Cost |
|---|---|---|---|
| **1. Parse + lower** (surface syntax) | `ssc1-front` + `ssc1-lower` | **194/195** ✅ (only non-code `deploy.ssc` left) | **DONE** — 2 bugs fixed 2026-07-09 |
| **2. Type-check** | `ssc1-check` (425-line HM subset) | **unmeasured** — the run path skips it | medium — measure, then close |
| **3. Runtime / stdlib / plugin / effect semantics** | `Runtime.scala` + native stdlib | not this metric | **large, but scalameta-independent** |

Axis 3 is the bulk. Parse+lower emitting IR does **not** mean a program *runs*:
`rozum-agent` needs `route`/`serveAsync`/`agentTool`/`jObj`; `ws-chat` needs
`onWebSocket`/`serve`; the compat path inherits all of these from the whole v1
ecosystem. Re-growing that on the native VM is real work — but it is orthogonal to
whether the parser is scalameta or hand-written.

## Path to scalameta-free

1. **Close the parse+lower gap** ✅ DONE (2026-07-09): fence policy + the two
   surface bugs above → **194/195**. Compile-recursion robustness (`-J-Xss` in the
   launchers) is the last polish item here.
2. **Measure axis 2**: run `ssc1-check` over the corpus, classify type-check gaps
   the same way, size the work. ← next.
3. **Grow axis 3** on the native VM (independent, already ongoing via the K3
   stdlib tracks). Measure native end-to-end *run* coverage to turn this from an
   unknown into a categorized backlog.
4. Only when native parse+typecheck close the corpus does scalameta become an
   **optional, frontend-only** dependency — then delete it from `v1/lang/core` and
   drop the `v2FrontendBridge` seam. Kernel + 4 backends are already free of it.

**Bottom line for planning:** "give up scalameta" is a *frontend-parity* milestone.
The parse level is now done (194/195); it is gated mostly by axis 3
(stdlib/runtime), **not** a compiler rewrite. The scary-sounding dependency is the
cheap part.
