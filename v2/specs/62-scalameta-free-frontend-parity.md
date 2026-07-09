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

## The genuine remaining surface gap — 8 files, 2 lowering bugs

All 8 files **parse** fine; `ssc1-lower.ssc0` has no arm for a node the parser
produced. Both are statement-sequence lowering edge cases in one 86 KB ssc0 file.

### Gap 1 — `Pair/2`: assignment as a non-final statement in a block (6 files)

Files: `rozum-agent.ssc`, `rozum-agent-pool.ssc`,
`rozum-agent-schema-derived.ssc`, `ws-chat.ssc`, `dsl-yaml-like.ssc`,
`mcp-search-server.ssc`.

Minimal repro (bisected from `ws-chat.ssc`):

```scala
var c = 0
foo("x") { y =>
  c = c + 1     // assignment in NON-final position of a multi-statement block
  bar(y)
}
```

- `{ y => c = c + 1 }` (assignment as the block's **last** expr) → OK.
- `{ y => val z = y; bar(z) }` (val binding, non-final) → OK.
- `{ y => bar(y); baz(y) }` (expr statements, non-final) → OK.
- Assignment (`Assign` node) in **non-final** position → `no arm for Pair/2`.

Root cause: the block-sequence lowering in `ssc1-lower.ssc0` folds statements but
has no arm for an `Assign` node in the middle of a sequence.

### Gap 2 — `Nil/0`: an empty-tail statement-sequence edge case (2 files)

Files: `dsl-mini-language.ssc`, `webauthn-demo.ssc`.

A related sequence-folding defect: a specific combination of top-level definitions
followed by a trailing statement sequence yields an empty tail (`Nil`) reaching
`lowerE`, which has no arm for it. Not yet reduced to a one-liner — repro is the
full `webauthn-demo.ssc` / `dsl-mini-language.ssc`. Likely the same
`ssc1-lower.ssc0` sequence-fold as Gap 1; fix them together.

### Correctly out of scope (1 file)

`deploy.ssc` — contains only ` ```sh ` fences (a deploy manifest), no executable
ScalaScript. The "no scalascript blocks found" result is correct.

## Honest scope — three independent axes

Dropping scalameta means the native tower must reach parity on **all three**, but
only the first is a *parser* problem:

| Axis | Native artifact | Measured coverage | Cost |
|---|---|---|---|
| **1. Parse + lower** (surface syntax) | `ssc1-front` + `ssc1-lower` | **186/195**, gap = 2 bugs | **small** — close 2 lowering arms + fence policy |
| **2. Type-check** | `ssc1-check` (425-line HM subset) | **unmeasured** — the run path skips it | medium — measure, then close |
| **3. Runtime / stdlib / plugin / effect semantics** | `Runtime.scala` + native stdlib | not this metric | **large, but scalameta-independent** |

Axis 3 is the bulk. Parse+lower emitting IR does **not** mean a program *runs*:
`rozum-agent` needs `route`/`serveAsync`/`agentTool`/`jObj`; `ws-chat` needs
`onWebSocket`/`serve`; the compat path inherits all of these from the whole v1
ecosystem. Re-growing that on the native VM is real work — but it is orthogonal to
whether the parser is scalameta or hand-written.

## Path to scalameta-free

1. **Close the parse+lower gap** (this milestone, small): fence policy (done) +
   Gap 1/Gap 2 lowering arms + compile-recursion robustness. Then re-run: target
   **194/195** (all but `deploy.ssc`).
2. **Measure axis 2**: run `ssc1-check` over the corpus, classify type-check gaps
   the same way, size the work.
3. **Grow axis 3** on the native VM (independent, already ongoing via the K3
   stdlib tracks).
4. Only when native parse+typecheck close the corpus does scalameta become an
   **optional, frontend-only** dependency — then delete it from `v1/lang/core` and
   drop the `v2FrontendBridge` seam. Kernel + 4 backends are already free of it.

**Bottom line for planning:** "give up scalameta" is a *frontend-parity* milestone
that is ~95% done at the parse level and gated mostly by axis 3 (stdlib/runtime),
**not** a compiler rewrite. The scary-sounding dependency is the cheap part.
