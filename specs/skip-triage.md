# Corpus SKIP triage — why 77 of 528 cases measure NOTHING

**Measured 2026-07-30** by `skip-triage-golden-lane`, from one freshly built worktree
(`./install.sh --dev`), probing every SKIPped case exactly the way the contract's own golden probe
does: `bin/ssc-tools run --v1 <file>` with a 30 s budget.

## Why this matters more than a FAIL row

A `SKIP` is not "one lane is red". `tests/conformance/contract.sc:690` establishes the golden
BEFORE the `backends:` gate is applied: a case with no `expected/` file gets its golden by running
the **int** lane, and if int cannot produce a stable answer the case is dropped for **every** lane.

So these 77 cases are not measuring v2, or js, or jvm. They are 15% of the corpus (77 / 528) where
the v2 lane has no verdict at all — larger than the 30 v2 non-PASS rows the freeze does record.
Reducing this number is the cheapest way to widen real v2 coverage, and it is invisible in the
PASS-cell count, which only counts cells that exist.

**A caution about `bin/ssc run`.** It is the NATIVE lane, not `int`. Probing with it gives
believable, wrong answers: three cases that fail on the golden lane exit 0 under it. The golden lane
is `ssc-tools run --v1` (`contract.sc:655`).

## The 77, by measured cause

| # | bucket | verdict |
|---|---|---|
| 21 | **rc=124, server binds a port and blocks** | SKIP is CORRECT and permanent |
| 25 | **`[ERROR] Undefined: <Name>`** — provider/plugin type absent from the int graph | needs a declared gate + a frozen golden |
| 11 | **std module symbol gap** — a name is imported from a module that does not export or does not define it | REAL BUG. 4 fixed, see below |
| 2 | **nondeterministic** (`uuid-v7`, `mcp-server-protected`) | SKIP is CORRECT |
| 18 | assorted: network, wrong frontend, unhandled effect | mixed, itemised below |

`uuid-v7` and `mcp-server-protected` exit 0 on a single run, which is why a one-shot probe calls them
runnable. Both differ between two consecutive runs, so the contract's second probe is what catches
them. Verified, not assumed.

## Bucket 3 — the real bug class (the only one that is v1's fault)

11 cases fail on the GOLDEN lane because of a std-module defect. Nothing to do with ports, networks
or providers. Three distinct shapes, and the two diagnostics do NOT reliably distinguish them —
`'X' is not exported by M` and `'X' not found in M` both appear whether X exists in M or not, which
sent this investigation looking for an export omission when the symbol was simply absent.

### 3a. Export list incomplete — FIXED (`9b758d807`)

`std/mcp/server.ssc` imported 14 names from `./types.ssc` and re-exported 3. **v1's own
`std/agent-mcp.ssc:26` imports `Content` from it** and could not resolve it, so this was never just
an example being sloppy. `client.ssc` had the same omission (`Transport`).

Now run on the golden lane, byte-identical across two runs, with real output:
`mcp-server-tool`, `mcp-server-tools`, `mcp-server-resource`, `agent-mcp-server`.

They are still frozen as `SKIP`, so the next full contract run reports four improvements. Refreshing
`corpus-baseline.tsv` / `contract-roster.tsv` belongs to whoever holds those files.

### 3b. The import resolver cannot see `type` aliases or `extension` methods — FIXED (`91c326d1f`)

Reproduced with a one-line consumer against the real std modules:

| symbol | declared as | result |
|---|---|---|
| `Pass` (`std/dsl/passes.ssc:33`) | `type Pass[A, B] = ...` | `'Pass' not found` |
| `ParseErrors` (`std/parsing/recovery.ssc:132`) | `type ParseErrors = List[ParseError]` | `'ParseErrors' not found` |
| `withIndent` (`std/parsing/layout.ssc:210`) | `def` inside `extension [A](p: Parser[A])` | `'withIndent' not found` |
| `heading` (`std/ui/typography.ssc:17`) | plain `def` | imports fine |
| `Rec`, `plainDef`, `defDefault` (probe module) | `case class` / `def` / `def` with defaults | import fine |

Both shapes are pure `.ssc` with no backend dependency, and both are listed in their module's
`exports:`. This blocked `dsl-mini-language`, `dsl-sql-recovery`, `dsl-yaml-like` — the three cases the
source-shape heuristic had flagged as pure compute, i.e. exactly the cases that would give v2 real
coverage.

**Outcome, measured after the fix:**

| case | before | after |
|---|---|---|
| `dsl-sql-recovery` | `'ParseErrors' not found` | **rc=0**, stable across two runs |
| `dsl-yaml-like` | `'withIndent' not found` | `'columnOf' is not exported` -> **rc=0** after `layout.ssc` exports (17 lines of real output) |
| `dsl-mini-language` | `'Pass' not found` | advances to `No method 'andThen' on FunV` — a different, deeper bug |

`dsl-yaml-like` needed a second, ordinary export omission fixed in `std/parsing/layout.ssc` (six names
the module defines). That one only became visible once the resolver stopped failing first — a useful
reminder that one diagnostic can hide the next.

`dsl-mini-language` is blocked by `int-extension-on-function-type-alias-does-not-dispatch`: the
extension's receiver type `Pass[A,B]` is an alias of a FUNCTION type, and dispatch keys on the
receiver's runtime type name, which is `FunV`. Registration-time alias resolution is a change to
shared extension dispatch, so it is filed rather than patched here.

**Net: 6 of the 77 closed** — 4 from the std/mcp exports plus these two.

### What the 6 actually buy on the v2 lane — measured, not assumed

Un-skipping a case gives it a VERDICT; it does not promise a pass. Measured on all six:

| case | int | v2 |
|---|---|---|
| `dsl-sql-recovery` | rc=0 | **PASS** |
| `dsl-yaml-like` | rc=0 | **PASS** |
| `mcp-server-tool` | rc=0 | FAIL — `ssc: unbound global: mcpServer` |
| `mcp-server-tools` | rc=0 | FAIL — same |
| `mcp-server-resource` | rc=0 | FAIL — same |
| `agent-mcp-server` | rc=0 | FAIL — same |

So the freeze gains **2 real v2 passes and 4 v2 FAIL rows**. The 4 FAILs are one cause: the native
runtime has no `mcpServer` binding, i.e. no MCP server provider is registered on the v2 lane — the
same class as the opt-in-provider cases, now visible instead of hidden. That is the point of removing
a SKIP: a FAIL row you can act on beats a blind spot you cannot see.


### 3c. `fetchUrlSignalTo` is declared, exported, documented — and implemented nowhere — OPEN

`std/ui/primitives.ssc:185` declares it with a nine-line doc comment. It has **zero** registrations
in the whole tree: absent from the interpreter's `FetchIntrinsics`, absent from `JsGen.scala:1406`'s
list, absent from Rust. Its sibling `fetchUrlSignal` — same `extern def` shape, same default
argument, declared nine lines above — has three. So this is not about `extern`, nor about default
arguments; the symbol simply does not exist on any backend, including the JS one its own comment
describes. Blocks `control-center-live` on every lane. Filed as
`std-ui-fetchUrlSignalTo-declared-never-implemented`.

### 3d. `code` does not exist in `std/ui/typography.ssc` — OPEN, needs a decision

`graph-fullstack-rdf:70` imports `[heading, text, code]`. The module defines `heading`, `text`,
`signalHeading` and nothing else, and `std/ui/nodes.ssc` has no code/inline-code node to build one
from. So this is a missing FEATURE (an inline-code constructor), not a forgotten export line.
Fixing it does not un-skip the case: it also needs an rdf4j provider and binds a port.

### 3e. Reached further, still blocked — `Undefined: __extern__`

`mcp-client-invoke` and `agent-mcp-toolsource` now get past the export error and fail calling the
extern client, which `std/mcp/client.ssc`'s own front-matter says is unavailable on the interpreter.
Correct treatment is a declared gate plus a frozen golden from the lane they target. Note the
diagnostic is poor: it points at an EMPTY source line and names the internal `__extern__` rather
than saying `mcpConnect` is not available on this backend.

## Bucket 2 — 25 × `Undefined: <Name>`

`AchConfig`, `SepaConfig`, `PixConfig`, `FedNowConfig`, `PaymentProvider`, `ObjectStore`, `Gremlin`,
`Cypher`, `Sparql`, `RdfNode`, `Wallets`, `FireblocksVault`, `MockBureauProvider`, `Transport`,
`SwiftProvider`, `HandlerRegistry` (×2), `sys` (×4), `mutable`, `send`, `awaitClient`, `__extern__`.

These are provider types deliberately kept out of the standard launcher graph (`bank-rails-*`,
`x402-*`, `graph-*`, `wallet-*`). The int lane cannot host them by design, so the golden must come
from a frozen `expected/` file, and the case must declare the lanes it targets. That is a
freeze-touching change; not done here.

`sys` and `mutable` are different and worth a second look — they read like Scala-standard names
(`sys.exit`, `scala.collection.mutable`) rather than provider types.

## Bucket 5 — the assorted 18

Network (`ConnectException` ×2, a live `api.spacexdata.com` call ×1) · frontend mismatch
(`--frontend swing` ×3, `swiftui` ×1) · `No HttpServerSpi impl named 'jetty'` · `Unhandled effect:
Actor.clusterMembers` · `emit called outside a stream body` · `remote handler not found: demo.echo` ·
`No method 'scalar' on GraphQL` · `Unknown interpolator 'xml'` · `UnknownBlockLanguage(node.js)` ·
`route(method, path) { handler }` · `fetchActionClear(...)` · an slf4j binding warning · one case that
starts a web server and never returns.

The three `swing` and one `swiftui` cases are mis-declared rather than broken: they name a frontend
the interpreter cannot drive and the runtime says so clearly. They want `backends:`.

## What would actually shrink the 77

In cost order, cheapest first:

1. **Fix 3b** — one resolver change, un-skips 3 pure-compute cases and gives v2 three new verdicts.
   Nothing about it touches the freeze until those cases start passing.
2. **Declare the 21 servers + 25 provider cases** so the contract stops paying a 30 s golden probe
   (twice, for the nondeterminism check) on cases that can never produce one. That is up to
   ~46 × 2 × 30 s of wall clock per full run spent proving the already-known.
3. **Frozen goldens for the declared-lane cases** — the only way a case gated away from int can ever
   be measured, since the golden probe runs int regardless of the gate.

Item 2 needs a front-matter key the contract honours (`skip:` with a reason), which means changing
`contract.sc` — held by another claim at the time of writing.
