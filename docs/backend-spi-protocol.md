# Backend SPI — Out-of-Process Wire Protocol

> Companion to `docs/backend-spi.md` §12.2.  Specifies the framing, message
> shapes, and method semantics a subprocess plugin must implement to be
> a first-class `Backend` or `SourceLanguage`.

## 1. Framing

Two framings are recognised, selected by the `protocol` field of
`plugin.yaml`:

| `protocol`        | Frame                                                 |
|-------------------|--------------------------------------------------------|
| `stdio-json`      | Newline-delimited JSON, one message per line, UTF-8.  |
| `stdio-msgpack`   | 4-byte big-endian length prefix + MsgPack payload.    |

Core sends requests on the plugin's stdin and reads responses from
its stdout.  Stderr is forwarded verbatim to the user — diagnostic
logs are expected there; everything on stdout MUST be a framed
response.

Stage 6.1 of the implementation ships `stdio-json` only.
`stdio-msgpack` is the same case-class shapes round-tripped via
upickle's `writeBinary` / `readBinary`.

## 2. Message envelope

Every request:

```json
{ "method": "describe", "params": {}, "id": 17 }
```

| Field    | Type                            | Notes                                   |
|----------|---------------------------------|-----------------------------------------|
| `method` | `string`                        | Required.  See §3 for the recognised set. |
| `params` | object                          | Required.  Method-specific schema (§4). |
| `id`     | integer                         | Required.  Echoed verbatim in response. |

Every response:

```json
{ "id": 17, "result": { "..." : "..." } }
```

or

```json
{ "id": 17, "error": { "code": -32601, "message": "unknown method: foo" } }
```

Exactly one of `result` or `error` MUST be populated.  The `id`
field is echoed unchanged so callers can correlate concurrent
requests (Stage 6.1 dispatch is sync; correlation matters once the
core supports pipelined calls).

## 3. Methods

Method names are scoped to the plugin's `roles` (one or both of
`backend`, `source-language`):

| Role             | Method            | Direction       |
|------------------|-------------------|-----------------|
| common           | `describe`        | core → plugin   |
| common           | `shutdown`        | core → plugin   |
| `backend`        | `compile`         | core → plugin   |
| `backend` (interactive) | `openSession` / `session.feed` / `session.close` / `invokeHandler` | core → plugin |
| `source-language` | `signatures`     | core → plugin   |
| `source-language` | `compileBlock`   | core → plugin   |

A plugin MUST respond to `describe` and `shutdown` regardless of
declared role.  Methods outside the declared roles MUST return
`error.code: -32601` (method-not-found).

## 4. Method semantics

### 4.1 `describe`

**Params:** `{}`

**Result:**

```json
{
  "id":              "canned",
  "displayName":     "Canned (smoke)",
  "spiVersion":      "0.1.0",
  "role":            "backend",
  "acceptedSources": [],
  "features":        ["MutableState", "PatternMatching"],
  "outputs":         ["ExecutionResult"]
}
```

| Field             | Notes                                                       |
|-------------------|--------------------------------------------------------------|
| `id`              | Plugin's declared id.  MUST match `plugin.yaml#id`.         |
| `displayName`     | Human-friendly label.                                       |
| `spiVersion`      | SPI version the plugin was built against.  Currently `"0.1.0"`. |
| `role`            | `"backend"`, `"source-language"`, or `"both"`.              |
| `acceptedSources` | Source languages this target backend can embed.             |
| `features`        | Enum names from `spi.Feature` — see §5.                     |
| `outputs`         | Enum names from `spi.OutputKind` — see §5.                  |

Called once at startup; core caches the result.

### 4.2 `shutdown`

**Params:** `{}`

**Result:** `{ "ok": true }` (or anything truthy; core ignores).

The plugin MUST exit cleanly within 2 seconds of replying; core
calls `destroyForcibly()` past that.

### 4.3 `compile`

**Params:**

```json
{
  "irJson":  { ... },          // ir.NormalizedModule as JSON
  "baseDir": "/abs/path",      // optional
  "extra":   { "mode": "..." } // free-form, backend-specific
}
```

The `irJson` payload is the upickle-serialised `ir.NormalizedModule`
defined in `ir/src/main/scala/scalascript/ir/Ir.scala`.  Plugins
re-parse it with the same codec to recover the structured IR.

**Result:**

```json
{ "kind": "text", "code": "...", "language": "..." }
```

One of five shapes, discriminated by `kind`:

| `kind`      | Other fields                                                         |
|-------------|----------------------------------------------------------------------|
| `text`      | `code: string`, `language: string`                                   |
| `segmented` | `segments: Segment[]`                                                |
| `binary`    | `bytes: byte[]`, `mime: string`                                      |
| `executed`  | `stdout: string`, `stderr: string`, `exit: int`                      |
| `failed`    | `diagnostics: Diagnostic[]`                                          |

`Segment` shape:

```json
{ "kind": "code"|"source", "language": "...", "code": "...", "source": "..." }
```

`Diagnostic` shape:

```json
{ "kind": "unsupported"|"unknown-block"|"generic", "message": "...", "feature": "...", "backend": "..." }
```

### 4.4 InteractiveBackend methods (Stage 6+ follow-up)

`openSession`, `session.feed`, `session.close`, `invokeHandler` —
declared here so plugin authors can plan, but the core's
`InteractiveBackend` wiring (used by `ssc serve`) currently goes
through in-process backends only.  Subprocess interactive support
lands once `ir.Value` has a concrete cross-process representation
(spec §5.2 / Stage 5+).

### 4.5 SourceLanguage methods (Stage 9)

`signatures(language, source, span)` and
`compileBlock(language, source, span, scope, opts)` are reserved
for Stage 9's bundled-plugin extraction.  Out-of-process plugins
implementing the source-language role today MAY return canned data;
core does not yet route foreign-block compilation through them.

## 5. Enum names

`features` and `outputs` values are the exact case names of
`scalascript.backend.spi.Feature` and `OutputKind`.  As of SPI
0.1.0:

- **Feature** (language): `AlgebraicEffects`, `MutableState`,
  `PatternMatching`, `TypeClasses`, `ExtensionMethods`,
  `DefaultParameters`, `ForComprehensions`, `WhileLoops`,
  `TailCallOptimization`, `StringInterpolators`, `ModuleImports`.
- **Feature** (platform): `ConsoleIO`, `HttpServer`, `WebSockets`,
  `Auth`, `FileSystem`, `Crypto`, `Database`.
- **OutputKind**: `ScalaSource`, `JavaScriptSource`, `CssSource`,
  `HtmlSource`, `JvmBytecode`, `WasmBytecode`, `NativeBinary`,
  `DotNetIL`, `ExecutionResult`.

Unknown names are silently dropped at the receiving end — bumping
either enum is a minor SPI version change.

## 6. Discovery

Plugin manifests live one-level deep under any directory listed in
`$SCALASCRIPT_PLUGIN_PATH` (colon-separated) or in
`~/.scalascript/compiler/plugins/`.  A manifest is a directory containing a
`plugin.yaml`:

```yaml
id: wasm
displayName: WebAssembly (wasm-tools)
spiVersion: "0.1.0"
protocol: stdio-json
executable: ./bin/wasm-backend
args: [--quiet]
roles: [backend]
backend:
  features: [PatternMatching, MutableState, TailCallOptimization]
  outputs: [WasmBytecode]
  acceptedSources: [wat]
```

A plain `plugin.yaml` directly under a search path also works (the
discoverer accepts both layouts).

Relative `executable` paths resolve against the manifest's directory.

## 7. Worked example

See `examples/plugins/canned-backend/` for a 50-line scala-cli
plugin implementing exactly the three core methods.  Drop it under
`~/.scalascript/compiler/plugins/canned` and `ssc --list-backends` shows it
alongside the bundled in-process backends.
