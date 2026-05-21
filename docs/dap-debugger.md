# `ssc debug` — Debug Adapter Protocol server

**Status: Phase 1 in progress (2026-05-21)**

## Goals

Provide IDE-friendly step-debugging for `.ssc` files using the
[Debug Adapter Protocol](https://microsoft.github.io/debug-adapter-protocol/) (DAP).
A user runs `ssc debug file.ssc [--port 5678]`; their IDE (VS Code, IntelliJ, Cursor,
Zed, …) connects to the DAP TCP server and drives the debug session with standard
`setBreakpoints`, `next`, `stepIn`, `variables`, and `stackTrace` requests.

## Non-goals (v1.29)

- Debugging JVM-side generated code (use JVM debugger or attach to scala-cli for that).
- Hot-swap / REPL-eval inside a paused debug session.
- WASM or JS backend debugging.
- Multi-thread debugging of actor code (actors run on the cooperative scheduler;
  the debugger sees a single logical thread through the current `.ssc` evaluation).
- Source-map injection into emitted JS/JVM artifacts.

## Architecture

### Module layout

```
backend/interpreter/
  src/main/scala/scalascript/interpreter/
    debug/
      DebugHooks.scala        # SPI: DebugHooks trait + StepMode + StackFrame
      BreakpointRegistry.scala# thread-safe breakpoint storage
      DapSession.scala        # one debug session (wraps Interpreter + DebugHooks)

backend/dap/
  src/main/scala/scalascript/dap/
    DapServer.scala           # TCP accept loop + Content-Length framing
    DapProtocol.scala         # JSON encode/decode for DAP messages (requests/events/responses)
    DapHandler.scala          # dispatch table: request method → handler function

cli/
  src/main/scala/scalascript/cli/
    DebugCommand.scala        # `ssc debug` CLI entry point
```

New sbt project: `backendDap` (`backend/dap`), depending on `backendInterpreter`.

### Key types

```scala
// debug/DebugHooks.scala
trait DebugHooks:
  def onStep(frame: StackFrame): StepAction
  def isBreakpoint(sourceFile: String, line: Int): Boolean
  def onOutput(category: String, msg: String): Unit

enum StepMode:
  case Running, StepOver, StepIn, StepOut, Pause

enum StepAction:
  case Continue, Stop(reason: StopReason)

enum StopReason:
  case Breakpoint, Step, Pause, Entry

case class StackFrame(
  id:         Int,
  name:       String,  // section heading or "top-level"
  sourceFile: String,
  line:       Int,
  column:     Int = 0,
)
```

```scala
// debug/BreakpointRegistry.scala
final class BreakpointRegistry:
  private val table = java.util.concurrent.ConcurrentHashMap[String, Set[Int]]()
  def setBreakpoints(sourceFile: String, lines: Set[Int]): Unit
  def contains(sourceFile: String, line: Int): Boolean
```

```scala
// dap/DapProtocol.scala  — thin hand-rolled JSON-RPC over TCP
// Content-Length framing identical to LSP:
//   Content-Length: <N>\r\n\r\n<N bytes of UTF-8 JSON>
case class DapMessage(seq: Int, `type`: String, command: Option[String],
                      event: Option[String], body: ujson.Value)
```

### Interpreter integration

`Interpreter` gains an optional `debugHooks: Option[DebugHooks]` field (default
`None`). In `EvalRuntime.eval` (or the `trackPos` call-site that already exists),
when `debugHooks` is defined the interpreter:

1. Resolves current source file + line from the active `Term`'s span.
2. Calls `hooks.isBreakpoint(file, line)` → if true, calls `hooks.onStep(frame)`
   with `StepAction.Stop(Breakpoint)`.
3. If the previous `StepAction` was `Stop(Step)`, the hooks compare depth and
   decide whether to stop again (`StepOver` respects call depth).
4. `hooks.onStep` **blocks** the interpreter thread until the DAP session sends
   `continue`, `next`, `stepIn`, or `stepOut`.

No changes to the cooperative actor scheduler for v1.29 — actor code runs
uninterrupted while `debugHooks` is set; only the top-level evaluation thread
is paused.

### TCP server

`DapServer.listen(port)` opens a `ServerSocket` and loops: `accept()` → spin
up a `Thread` per connection → `DapSession(conn, interpreter)`. One debug session
per TCP connection. Content-Length framing with `BufferedReader` / `OutputStream`.

No external DAP library dependency for v1.29 — the protocol surface we need
is small (~15 request types, ~10 event types) and the JSON encoding is trivial
with `upickle` / `ujson` (already in `ir`'s deps).

## Migration / compatibility

`ssc debug` is a new command; no existing commands change. The `debugHooks` field
on `Interpreter` is `None` by default — zero overhead for normal non-debug runs.
`backendDap` is a new sbt project; existing projects do not depend on it.
`cli` gains a new case in its command dispatch and a `% Test` dep on `backendDap`.

## Phases

### Phase 1 — TCP skeleton + initialize/launch/disconnect ← in progress

- New `backendDap` sbt project.
- `DapServer`: TCP accept + Content-Length frame read/write.
- `DapProtocol`: parse/emit `Request`, `Response`, `Event`.
- Handle `initialize` (return capabilities), `launch`, `configurationDone`, `disconnect`.
- `DebugCommand.scala`: CLI `ssc debug <file.ssc> [--port N]` — start `DapServer` on
  given port, wait for one connection, interpret the file with a no-op `DebugHooks`.
- Add `backendDap` to CLI `% Test` dep + root aggregate.
- Tests: `DapFramingTest` (Content-Length read/write), `DapSessionPhase1Test` (mocked client does initialize→launch→disconnect lifecycle).

### Phase 2 — Breakpoints

- `BreakpointRegistry` + `DebugHooks.isBreakpoint`.
- Handle `setBreakpoints` request → update `BreakpointRegistry`, respond with
  verified breakpoints.
- In `EvalRuntime.eval` / `trackPos`: call `hooks.isBreakpoint` → if hit, send
  `stopped(reason: "breakpoint")` event, block until `continue`.
- Source file identity: normalise paths to repo-relative (`os.Path` relative to cwd).
- Tests: `DapBreakpointTest` — set one breakpoint, run script, assert `stopped` received.

### Phase 3 — Step execution

- `StepMode` + `DapSession.stepMode: AtomicReference[StepMode]`.
- Handle `next` / `stepIn` / `stepOut` / `continue` requests → update `stepMode`,
  unblock interpreter thread.
- `DebugHooks.onStep` logic: track call depth for `StepOver` (don't stop inside
  called functions); `StepIn` stops at next eval; `StepOut` stops when depth decreases.
- Send `stopped(reason: "step")` after each step.
- Tests: `DapStepTest` — launch, breakpoint, then N `next` steps, assert stop events.

### Phase 4 — Variable inspection

- Handle `scopes` → return "Locals" scope.
- Handle `variables` → walk `Interpreter.env` (top frame only for v1.29) → convert
  each `Value` to a DAP `Variable` with `name`, `value: String`, `type: String`.
- `Value.toDapString` helper (mirrors `Value.show` but includes type tag).
- Nested `Value.Obj` / `Value.List` → `variablesReference` > 0 so IDE can expand.
- Tests: `DapVariablesTest` — pause at breakpoint, assert variable names/values.

### Phase 5 — Stack frames + source mapping

- Handle `stackTrace` request → build `List[StackFrame]` from interpreter's call
  stack (section heading as frame name, `.ssc` file + line as source).
- `Interpreter` already has an internal call stack for error reporting — expose it
  to `DebugHooks`.
- Map heading names (e.g. `# Setup`) to frame names (`Setup`).
- Tests: `DapStackTraceTest` — recursive call, assert frames depth + names.

## Testing strategy

| Phase | Test type | Scope |
|-------|-----------|-------|
| 1 | Unit: framing encode/decode; mock session lifecycle | `backendDap / Test` |
| 2–5 | Integration: `TestDapClient` (thin TCP client) against real `DapServer` with a `.ssc` fixture | `backendDap / Test` |

`TestDapClient` sends JSON messages over a loopback socket; assertions on received events/responses are plain `assert` checks on `ujson.Value` fields — no external mock framework.

## Open questions

1. **lsp4j DAP vs. hand-rolled**: lsp4j's `org.eclipse.lsp4j.debug` gives typed
   request/response DTOs but adds ~2 MB and complex threading. The hand-rolled path
   is ~200 LOC and keeps deps minimal. Recommendation: hand-rolled for v1.29;
   migrate to lsp4j if request coverage grows significantly.

2. **Actor debugging**: actors run on a cooperative scheduler inside the interpreter
   thread; pausing the interpreter thread also pauses actor delivery. For v1.29 this
   is acceptable (actor scripts simply won't step correctly). A future milestone can
   add per-actor debug hooks once the scheduler exposes a step point.

3. **Port default**: DAP convention for custom adapters is 5678; VS Code's DAP
   extension uses 4711. Use 5678 as default `--port` value.
