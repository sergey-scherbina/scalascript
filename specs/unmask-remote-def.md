# Unmask Remote Def on V2

## Overview
The v2 runner currently fails `examples/remote-registry-rpc.ssc` before execution because `remote def` is not valid scala.meta input, and even after parsing the v2 bridge has no in-process `std.remote` registry. This slice makes the self-contained remote registry path production-honest on v2: manifest `remoteHandlers:`, source `@remote(...) def`, and simple `remote def` declarations register local handlers that can be called through `Remote.function`, `remoteCall`, `remoteTryCall`, and `Remote.handlers()`.

## Interface
- Source sugar: `remote def f(args...): R = body` is accepted by the v2 bridge and behaves like `def f...` plus a remote handler named `f`.
- Source annotation: `@remote(name = "...", path = "...", request = "...", response = "...") def f...` registers handler metadata for `f`; omitted `name` defaults to the function name.
- Manifest metadata: front-matter `remoteHandlers:` entries with `function`, optional `path`, `request`, and `response` register the same in-process handler table.
- Runtime API from `runtime/std/remote.ssc`: `remoteFunction`, `remoteCall`, `remoteTryCall`, and `remoteHandlers` are available on v2. The imported `Remote` object and `RemoteFunction` methods remain ordinary ScalaScript library code over those externs.

## Behavior
- [ ] `remote def localEcho(value: String): String = ...` no longer reaches scala.meta as an unsupported modifier and registers handler `localEcho`.
- [ ] `@remote(name = "demo.upper", path = "/rpc/upper") def upper...` registers `demo.upper` with function `upper`, path metadata, and in-process transport.
- [ ] Manifest `remoteHandlers: demo.echo: function: echo` registers `demo.echo` and calls the top-level `echo` definition.
- [ ] `Remote.function[String, String](name).call(value)` and `remoteCall(name, value)` invoke the registered local handler closure.
- [ ] `remoteTryCall` returns `Right(value)` for success and `Left(HandlerNotFound(name))` for missing handlers without throwing.
- [ ] `Remote.handlers()` returns `RemoteHandlerInfo` values sorted by handler name, with `path`, `requestType`, `responseType`, and `transports` fields shaped like `std.remote`.

## Out of scope
- HTTP JSON fallback route registration for `path:` handlers.
- `Remote.http`, `Remote.stub`, trait-shaped `remoteStub[Api]`, async effect-row lowering, WebSocket/internal-wire transport, and binary `WireCodec` negotiation.
- Fixing the current v1 `examples/remote-registry-rpc.ssc` behavior; v2 expected behavior is taken from `docs/user-guide.md`, `specs/distributed-runtime.md`, and the existing remote-plugin interpreter tests.

## Design
`FrontendBridge` owns source discovery because it is the component that already extracts runnable fences and resolves `.ssc` imports for v2. Before scala.meta parsing, a text pre-pass rewrites simple `remote def f...` lines to ordinary `def f...` lines and records handler metadata. The bridge also scans parsed top-level definitions for `@remote(...)` annotations and parses manifest `remoteHandlers:` through the existing v1 parser when possible, falling back only if parsing fails.

After normal definition lowering, `FrontendBridge.convertStats` prepends entry statements that call a plugin-owned `remote.registerHandler` global. Each registration passes both metadata and the actual compiled handler closure (`Global(function)`), so the v2 compiler does not need to expose its private globals map or register every top-level `def` globally.

`PluginBridge` owns the v2 remote registry. It registers `remote.registerHandler` as an internal v2 global plus user-facing extern globals from `std.remote`: `remoteFunction`, `remoteCall`, `remoteTryCall`, and `remoteHandlers`. `remoteFunction(name)` returns `DataV("RemoteFunction", [name])` so `RemoteFunction.call` and `tryCall` continue to run from `runtime/std/remote.ssc`; invocation goes through `remoteCall` / `remoteTryCall`.

## Decisions
- **Store the closure in the remote registry** — chosen because top-level v2 `def` values live in the compiler's private globals map. Rejected: adding a compiler/runtime API to expose globals, because this slice should stay in the bridge/plugin boundary.
- **Keep HTTP/stub support out of this slice** — chosen because `remote-registry-rpc.ssc` exercises only in-process calls and handler listing. Rejected: porting the full v1 HTTP fallback now, because it expands into server route registration and value JSON transport work.
- **Use `std.remote` case-class/data shapes** — chosen so imported library methods and pattern matches work without bespoke method objects. Rejected: returning a v1-style method object from `remoteFunction`, because v2 already has the source-level `RemoteFunction` methods.

## Results
Pending implementation.
