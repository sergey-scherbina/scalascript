# Backend SPI follow-ups — Stage 5+ / 9+ execution plan

> Working plan for the `feature/spi-followups` branch.  Companion to
> [`docs/backend-spi.md`](backend-spi.md) and the landed Stage 1–9.1
> summary in MILESTONES.md.
>
> Process: `AGENTS.md` §"Big-feature workflow (long-lived branch)".

## What lands in this branch

After the SPI proper (Stages 1–9.1, merged in `cf721b0`), three
multi-stage extractions remain.  This branch carries the ones that
don't conflict with a parallel session through to completion.

### Parallel-session coordination

A parallel session owns `worktree-feature-ws-v1.0` and is actively
landing v1.0 WS sprints (Sprint 4 observability + Sprint 6
convenience: ws.id / ws.subprotocol / per-route maxConnections /
rate-limit / pre-upgrade auth hook / close-echo wait).  Their work
modifies every `server/*.scala` file in `backend-interpreter/`.

**Conflict scope.**  Stage 5+/C (`std.http`) and 5+/D (`std.ws`)
would rewrite the same server files the parallel session is
editing.  Land both in close succession + a parallel session
without coordination → guaranteed nasty merge conflicts.

**Decision.**  Defer 5+/C and 5+/D until the parallel WS work is
fully merged to main.  Do everything else in this branch first;
revisit 5+/C and 5+/D as a separate branch later.

### Execution order

  1. **5+/A — Intrinsic plumbing** (foundational, no server/* touched)
  2. **5+/B — Migrate `Console.println` / `print`** (proves pattern on real consumer)
  3. **9+/A — Parser consults `SourceLanguageRegistry`** (foundational, parser-only)
  4. **9+/B — `backend-html` extraction**
  5. **9+/C — `backend-css` extraction**
  6. **6+ — Subprocess protocol completions** (msgpack framing,
     InteractiveBackend over subprocess, HostCallback dispatch)
  7. **(deferred)** — 5+/C `std.http`, 5+/D `std.ws / auth / fs / crypto`

5+/A and 9+/A are foundational — they enable the rest with no
moving parts beyond themselves.

## Stage map

| # | Stage | What | Iterations | Estimate |
|---|-------|------|------------|----------|
| 1 | 5+/A | IrExpr concrete + intrinsic-table consultation pattern | 3 | ~1d |
| 2 | 5+/B | Migrate `Console.println` + `Console.print` end-to-end | 2 | ~0.5d |
| 3 | 9+/A | Parser routes unknown fence tags through `SourceLanguageRegistry` | 2 | ~0.5–1d |
| 4 | 9+/B | `backend-html` extraction (containerTagNames + DSL prelude) | 4 | ~1.5d |
| 5 | 9+/C | `backend-css` extraction                                | 3 | ~1d |
| 6 | 6+  | Subprocess protocol completions (msgpack, InteractiveBackend, HostCallback) | 3 | ~1d |
| 7 | 10  | Final integration + merge                              | 1 | ~0.5d |
| — | 5+/C | `std.http` (`route`/`serve`/`stop`) — **deferred until parallel WS work merges** | 4 | ~2d |
| — | 5+/D | `std.ws` / `std.auth` / `std.fs` / `std.crypto` — **deferred** | 8 | ~3–4d |

Total in-flight: ~5–6 working days.  Deferred: ~5–6 days for a
follow-up branch once WS v1.0 lands.

---

## Stage 5+/A — Intrinsic plumbing

**Goal:** at least one symbol flows through `Backend.intrinsics` end
to end.  Pick something that doesn't yet exist as a hardcoded function
so the migration is purely additive (no risk of regressing existing
programs while the pattern beds in).

### 5+/A.1 Concrete IrExpr + EmitContext

The Stage 1.3 stubs in `ir/Ir.scala` are abstract traits — they need
enough structure for an intrinsic's `InlineCode.emit` to actually
produce target source.  Minimum types:

```scala
sealed trait IrExpr
case class Lit(value: ir.LitValue) extends IrExpr
case class Var(name: String) extends IrExpr
case class Call(target: SymbolRef, args: List[IrExpr]) extends IrExpr
case class Extern(name: QualifiedName, args: List[IrExpr]) extends IrExpr  // promoted from placeholder
case class Block(stmts: List[IrExpr], result: IrExpr) extends IrExpr
case class Lambda(params: List[String], body: IrExpr) extends IrExpr

enum LitValue:
  case IntL(value: Long), DoubleL(value: Double), StringL(value: String),
       BoolL(value: Boolean), UnitL
```

`EmitContext` exposes minimum surface backends need:

```scala
trait EmitContext:
  def emitArg(arg: IrExpr): TargetCode    // recursively emit a sub-expression
  def freshLocal(prefix: String): String  // unique local name
  def importRuntime(symbol: String): Unit // mark a runtime helper as needed
```

- **Done when:** ir compiles with the new IrExpr hierarchy + codecs
  derived; no consumers exist yet.

### 5+/A.2 Demo intrinsic — `Sys.nowMillis()` end-to-end (interpreter)

A brand-new builtin so there's nothing to migrate away from.

- `Sys` object stub recognised by Normalize: call site
  `Sys.nowMillis()` becomes `Extern(QualifiedName("sys.nowMillis"), Nil)`.
- `InterpreterBackend.intrinsics` populated:
  `Map(QualifiedName("sys.nowMillis") -> InlineCode((_, _) => TargetCode("0")))`
  — placeholder body returning constant 0.  (Real implementation
  lives in step 5+/A.3.)
- Interpreter's `eval` looks up Extern calls in the backend's
  intrinsics map; calls the configured `InlineCode.emit` and uses
  the returned `TargetCode` as a Value.
- Test:  `Sys.nowMillis()` evaluated through the interpreter returns
  the canned value.

### 5+/A.3 Repeat for JvmBackend + JsBackend

Same intrinsic, real implementation per backend:

- `JvmBackend.intrinsics(QualifiedName("sys.nowMillis"))` →
  `RuntimeCall("java.lang.System.currentTimeMillis")` — JvmGen
  emits the Scala call.
- `JsBackend.intrinsics(QualifiedName("sys.nowMillis"))` →
  `RuntimeCall("Date.now")`.
- Codegens consult `intrinsics` when emitting `Extern` IR nodes —
  if found, emit the configured target; otherwise fall through to
  existing hardcoded paths.

**Done when:** all 3 backends print the current millis when running
a program with `println(Sys.nowMillis())`.  Existing programs
unaffected (Extern only fires for the specific registered names).

---

## Stage 5+/B — `Console` migration (proof on a real consumer)

Stage 5+/A was new code, no migration.  5+/B migrates an EXISTING
universal builtin — `println` / `print` — to prove the pattern
handles real-world traffic.

### 5+/B.1 Add `Console.println` / `Console.print` aliases

Don't remove the bare `println` yet — add `Console.println` /
`Console.print` as recognised intrinsic names with the same backends'
emit paths.  Once both work, the bare aliases can be migrated.

### 5+/B.2 Migrate bare `println` → `Console.println` in Normalize

Normalize rewrites bare `println(x)` → `Extern(QualifiedName("std.console.println"), [x])`.  Backends emit through the intrinsic table.  Hardcoded `_println` runtime helpers stay (they're
the implementation under `RuntimeCall("_println")`).

**Done when:** every conformance fixture using println passes (38+
fixtures).  Hardcoded `nativeP("println")` block in Interpreter is
gone or thinned.

---

## Stage 5+/C — `std.http` extraction

This is the multi-iteration meat of the original Phase 9 spec.

### 5+/C.1 `extern` modifier in parser

Recognise a new modifier on `def`:
```scala
package std.http
extern def route(method: String, path: String, handler: Request => Response): Unit
extern def serve(port: Int): Unit
extern def stop(): Unit
```

Parser change + typer treats `extern def` symbols as intrinsic call
sites (lowered to `Extern` in Normalize).

### 5+/C.2 `std/http.ssc` prelude

Ships the `extern def` declarations + `Request` / `Response` case
classes.  Loaded as a `PreludeContribution` by the backend(s) that
declare `Feature.HttpServer`.

### 5+/C.3 Per-backend intrinsic implementation

- `JvmBackend.intrinsics`:  `route` / `serve` / `stop` → wrap
  `com.sun.net.httpserver` via `RuntimeCall`.
- `JsBackend.intrinsics`: same names → Node `http` module via
  emitted runtime helpers.
- `InterpreterBackend.intrinsics`: same names → existing
  `server/WebServer.scala` runtime via `RuntimeCall`.

### 5+/C.4 Remove hardcoded HTTP from codegens

- `nativeP("route")` / `nativeP("serve")` etc. in Interpreter — gone.
- HTTP emission cases in JvmGen / JsGen — gone (replaced by generic
  Extern emit).
- Verify: every auth / REST / WS example runs unchanged.
- Verify: `backend-interpreter dependsOn backendJs` finally removed
  (`WebServer.scala` no longer imports `JsGen` for SPA runtime —
  the runtime preamble is now an intrinsic-shipped string).

---

## Stage 5+/D — `std.ws` / `std.auth` / `std.fs` / `std.crypto`

Same pattern as 5+/C, one package per iteration:

  - **5+/D.1** `std.ws` — `accept` / `send` / `recv` / `close`,
    framing helpers, `WsRoom`.
  - **5+/D.2** `std.auth` — `hashPassword` / `signJwt` /
    `verifyJwt` / `csrfToken` / OAuth helpers.
  - **5+/D.3** `std.fs` — `os.read` / `os.write` / `os.list`.
  - **5+/D.4** `std.crypto` — primitives wrapping platform JCE / `crypto`.

By end of 5+/D, `core` is genuinely free of platform-specific code:
all calls to platform APIs flow through `Backend.intrinsics`.

---

## Stage 9+/A — Parser → SourceLanguageRegistry

Currently `Lang.isParseable` / `Lang.isStringBlock` hardcode the
fence-tag → handler mapping in `core/ast/Lang.scala`.  9+/A flips
this so unknown tags route through the registry.

### 9+/A.1 Registry consultation in parser

When parser encounters a fence-tagged block that isn't
`scalascript`/`ssc`:

- Ask `SourceLanguageRegistry.lookup(language)` for a plugin.
- If found, plugin's `signatures()` runs during the cross-block
  signature pass; `compileBlock()` runs to produce an
  `ir.Content.EmbeddedBlock` payload.
- If not, emit `Diagnostic.UnknownBlockLanguage(language)`.

Hardcoded `Lang.isStringBlock` / `Lang.isParseable` stay as a fast
path for the language names core already knows (until 9+/B and
9+/C move them out).

### 9+/A.2 Two-pass typing (cross-block references)

`signatures()` walks every block first to populate the module's
symbol table; `compileBlock()` then runs with the full scope
visible (spec §10).  Cycle support: if `signatures()` calls itself
for a block already in progress, return a partial typing result.

**Done when:** a no-op SourceLanguage plugin can claim a new fence
tag (e.g. `toml`) and the parser routes blocks to it; an unknown
fence tag produces `Diagnostic.UnknownBlockLanguage`.

---

## Stage 9+/B — `backend-html` extraction

Move every piece of `html`-specific handling out of core / shared
codegens into a `backend-html/` SourceLanguage plugin.

### 9+/B.1 Plugin skeleton + `Html` type

`backend-html/` ships:
- `HtmlSourceLanguage extends SourceLanguage` with `canonicalName = "html"`.
- `preludeFiles` containing `Html` case class + the `containerTagNames`
  list (currently in `JvmGen.scala:1415`).
- Initial `signatures()` returning the top-level constants from the
  prelude.

### 9+/B.2 Move `html"…"` interpolator handling

`html"…"` is recognised in `JvmGen`/`JsGen`/`Interpreter` today.  Move
parsing of the interpolator into the plugin so each backend just
emits the IR fragment the plugin produced.

### 9+/B.3 Move `nativeP("div")` etc. block from Interpreter

The big chunk at `Interpreter.scala:485` registering every HTML tag
as a native function moves to the plugin (or its prelude).

### 9+/B.4 Move `_Raw` emission + `containerTagNames` from JvmGen

JvmGen's hardcoded HTML container/void tag knowledge moves into
the plugin's IR-fragment emission.

**Done when:** every `html`-using example still runs identically;
`grep "containerTagNames\|nativeP(\"div\")"` in `backend-jvm/` and
`backend-interpreter/` returns zero hits.

---

## Stage 9+/C — `backend-css` extraction

Same shape as 9+/B for `css` blocks + `css"…"` interpolator.  Smaller
because CSS has no DSL tag bindings — just the `Css` case class +
escape helpers.

---

## Stage 6+ — Subprocess protocol completions

Reserved from Stage 6: out-of-scope items in the original SPI rollout
that come back when the foundation is in place.

### 6+/A — `stdio-msgpack` framing

Same `Request` / `Response` shapes round-tripped via upickle's
`writeBinary` / `readBinary`.  4-byte big-endian length prefix +
MsgPack payload.  Plugin manifest selects with
`protocol: stdio-msgpack`.  ~half a session.

### 6+/B — InteractiveBackend over subprocess

Needs concrete `ir.Value` wire shape (lands as part of 5+/A.1 — the
`LitValue` hierarchy).  Once `Value` is serializable, subprocess
plugins can implement `Session.feed` / `Session.invokeHandler`
through the wire.

### 6+/C — HostCallback intrinsic dispatch

Out-of-process backends call back into core via a named host
callback.  Core's `SubprocessBackend` exposes a callback channel
on the wire: plugin emits a request with method `host.<name>`,
core dispatches to a registered host function, replies on the same
channel.  Pairs with the intrinsic-table plumbing from 5+/A.

---

## Stage 10 — Final integration

- Rebase / merge onto current `origin/main`.
- Full check suite.
- Update MILESTONES.md (move 5+/A,B,C,D and 9+/A,B,C to "landed" in
  the Backend SPI section).
- Update README if any new user-visible flags appear.
- Single FF merge into main.
- `ExitWorktree(action: "remove")`.

---

## Status

| # | Stage  | Iterations done | Notes |
|---|--------|-----------------|-------|
| 1 | 5+/A   | 0 / 3           | Not started |
| 2 | 5+/B   | 0 / 2           | Not started |
| 3 | 9+/A   | 0 / 2           | Not started |
| 4 | 9+/B   | 0 / 4           | Not started |
| 5 | 9+/C   | 0 / 3           | Not started |
| 6 | 6+     | 0 / 3           | Not started |
| 7 | 10     | 0 / 1           | Not started |
| — | 5+/C   | 0 / 4           | **DEFERRED** — parallel WS work on feature/ws-v1.0 |
| — | 5+/D   | 0 / 8           | **DEFERRED** — same reason |
