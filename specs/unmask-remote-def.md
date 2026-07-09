# Unmask Remote Def on V2

## Overview
The v2 runner currently fails `examples/remote-registry-rpc.ssc` before execution because `remote def` is not valid scala.meta input, and even after parsing the v2 bridge has no in-process `std.remote` registry. This slice makes the self-contained remote registry path production-honest on v2: manifest `remoteHandlers:`, source `@remote(...) def`, and simple `remote def` declarations register local handlers that can be called through `Remote.function`, `remoteCall`, `remoteTryCall`, and `Remote.handlers()`.

## Interface
- Source sugar: `remote def f(args...): R = body` is accepted by the v2 bridge and behaves like `def f...` plus a remote handler named `f`.
- Source annotation: `@remote(name = "...", path = "...", request = "...", response = "...") def f...` registers handler metadata for `f`; omitted `name` defaults to the function name.
- Manifest metadata: front-matter `remoteHandlers:` entries with `function`, optional `path`, `request`, and `response` register the same in-process handler table.
- Runtime API from `runtime/std/remote.ssc`: `remoteFunction`, `remoteCall`, `remoteTryCall`, and `remoteHandlers` are available on v2. The imported `Remote` object remains ordinary ScalaScript library code over those externs; `RemoteFunction.call` and `tryCall` are also intercepted by v2 method hooks so they do not depend on the bridge's class-method fallback path.

## Behavior
- [x] `remote def localEcho(value: String): String = ...` no longer reaches scala.meta as an unsupported modifier and registers handler `localEcho`.
- [x] `@remote(name = "demo.upper", path = "/rpc/upper") def upper...` registers `demo.upper` with function `upper`, path metadata, and in-process transport.
- [x] Manifest `remoteHandlers: demo.echo: function: echo` registers `demo.echo` and calls the top-level `echo` definition.
- [x] `Remote.function[String, String](name).call(value)` and `remoteCall(name, value)` invoke the registered local handler closure.
- [x] `remoteTryCall` returns `Right(value)` for success and `Left(HandlerNotFound(name))` for missing handlers without throwing.
- [x] `Remote.handlers()` returns `RemoteHandlerInfo` values sorted by handler name, with `path`, `requestType`, `responseType`, and `transports` fields shaped like `std.remote`.

## Out of scope
- HTTP JSON fallback route registration for `path:` handlers.
- `Remote.http`, `Remote.stub`, trait-shaped `remoteStub[Api]`, async effect-row lowering, WebSocket/internal-wire transport, and binary `WireCodec` negotiation.
- Fixing the current v1 `examples/remote-registry-rpc.ssc` behavior; v2 expected behavior is taken from `docs/user-guide.md`, `specs/distributed-runtime.md`, and the existing remote-plugin interpreter tests.

## Design
`FrontendBridge` owns source discovery because it is the component that already extracts runnable fences and resolves `.ssc` imports for v2. It asks the existing v1 parser for `remoteHandlers:` metadata before scala.meta sees the merged code; that parser already folds manifest entries, source `@remote(...) def`, and simple `remote def` sugar into the same manifest metadata. Then a text pre-pass rewrites simple `remote def f...` lines to ordinary `def f...` lines so scala.meta can parse the executable fence.

After normal definition lowering, `FrontendBridge.convertStats` prepends entry statements that call a plugin-owned `remote.registerHandler` global. Each registration passes both metadata and the actual compiled handler closure (`Global(function)`), so the v2 compiler does not need to expose its private globals map or register every top-level `def` globally.

`PluginBridge` owns the v2 remote registry. It registers `remote.registerHandler` as an internal v2 global plus user-facing extern globals from `std.remote`: `remoteFunction`, `remoteCall`, `remoteTryCall`, and `remoteHandlers`. `remoteFunction(name)` returns `DataV("RemoteFunction", [name])`. `RemoteFunction.call` and `tryCall` are handled through `__method__.call` / `__method__.tryCall` hooks for that data shape; this keeps the public stdlib shape while avoiding a FastCode crash in the generic class-method fallback path.

## Decisions
- **Store the closure in the remote registry** — chosen because top-level v2 `def` values live in the compiler's private globals map. Rejected: adding a compiler/runtime API to expose globals, because this slice should stay in the bridge/plugin boundary.
- **Keep HTTP/stub support out of this slice** — chosen because `remote-registry-rpc.ssc` exercises only in-process calls and handler listing. Rejected: porting the full v1 HTTP fallback now, because it expands into server route registration and value JSON transport work.
- **Use `std.remote` case-class/data shapes with narrow method hooks** — chosen so imported library pattern matches and field access keep the public shape while `call`/`tryCall` avoid the bridge fallback crash. Rejected: returning a v1-style method object from `remoteFunction`, because that would not match the `RemoteFunction` case-class shape exposed by `std.remote`.

## Results
- Focused remote bridge tests: `scripts/sbtc 'v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest -- -z remote'` passed, 2 tests.
- Full bridge regression suite: `scripts/sbtc 'v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest'` passed, 38 tests.
- CLI build and example smoke: `scripts/sbtc 'installBin'` passed; `bin/ssc run --v2 examples/remote-registry-rpc.ssc` exits 0 and prints `echo:hello`, `HELLO`, `local:hello`, `echo:typed`, and the three handler listing lines.
- Affected conformance: `tests/conformance/run.sh --only 'distributed*'` passed, 5/5.
- Runtime safety gate: `./v2/conformance/check.sh` passed after the runtime/plugin rebase; after a later unrelated native-front rebase, the final tip re-ran the focused/full bridge, CLI, example, affected conformance, and `git diff --check` gates.
