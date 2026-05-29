# Distributed Wire Protocol

Status: planned. This document is the source of truth for ScalaScript's
internal distributed binary wire protocol.

The first implementation is opt-in. Existing JSON-over-HTTP and
JSON-over-WebSocket behavior remains the default until the binary path is
covered by conformance tests across JVM, interpreter, JS/browser, and Electron
clients.

Companion document: [`docs/distributed-runtime.md`](distributed-runtime.md)
defines how this wire layer is used by local/remote/distributed runtime
adapters and by cluster code deployment.

## Goals

- Provide one shared internal wire layer for ScalaScript-to-ScalaScript
  distributed features.
- Keep JSON as the debug/fallback format while adding compact binary formats.
- Support JVM, interpreter, JS/browser, Electron renderer, and Node targets.
- Cover actor clusters, distributed Dataset/MapReduce, native DStream runner,
  typed route clients/RPC, WebSocket subscriptions, and object sync.
- Make transport negotiation explicit instead of baking one codec into each
  subsystem.
- Add security, compression, size limits, tracing, and test-vector discipline
  early enough that later implementations do not grow incompatible ad hoc
  protocols.
- Keep the initial compatibility contract simple: same ScalaScript runtime
  version only. Cross-version evolution is planned before the first stable
  release.

## Non-goals

- Do not replace public external protocols such as MCP, OAuth, OpenAPI, DAP,
  x402 facilitator APIs, blockchain JSON-RPC, or vendor wallet APIs. Those keep
  their standard wire formats.
- Do not make binary the default in the first implementation.
- Do not require every REST route to support binary. The binary route-client
  path is opt-in and generated.
- Do not promise cross-version decoding until the compatibility phase lands.
- Do not serialize functions, native handles, open files, threads, or arbitrary
  host objects. These remain local runtime values.

## Covered Distributed Surfaces

| Surface | Current shape | Binary plan |
|---------|---------------|-------------|
| Actor data plane | JSON text WebSocket frames in `docs/actors-dist.md` | Binary WS frames for `msg`, `link`, `monitor`, registry replies, pub/sub, and user messages |
| Actor cluster control plane | JSON envelopes for membership, gossip, heartbeat, phi vectors, leader election, config, drain, token rotation, metrics | Same envelope model over negotiated binary frames |
| Distributed Dataset / MapReduce | Actor messages with `DatasetWirePartition` payload helpers | Binary partitions and shuffle payloads through `WireCodec[A]` / `DatasetCodec[A]` |
| Native DStream runner | Actor-backed pipeline execution | Binary element batches, watermark/control messages, checkpoint metadata |
| Typed route clients / RPC | JSON HTTP request/response via generated clients | Generated binary HTTP body with content negotiation and JSON fallback |
| WebSocket subscriptions | Text JSON frames | Binary WS frames where the peer negotiates binary; SSE remains text/base64 only |
| Client/server object sync | JSON sync routes | Optional binary pull/push/sync payloads for ScalaScript clients |
| Plugin subprocess protocol | Already supports stdio JSON/MsgPack | Reuse shared codec test vectors; keep subprocess framing separate |

## Format Profiles

ScalaScript supports named format profiles rather than hard-coding one binary
library into every feature.

| Format | Status | Purpose |
|--------|--------|---------|
| `json` | Existing / default | Human-readable debugging, fallback, curl/browser inspection |
| `msgpack` | Planned first binary profile | Fast path that aligns with existing plugin-host MsgPack framing |
| `cbor` | Planned first-class binary profile | Standard deterministic binary profile, good long-term candidate for stable wire compatibility |

CBOR is explicitly in scope. The implementation should prefer a small,
portable profile that can be implemented in JVM and JS without pulling a large
browser dependency. If a library is used on one target, conformance vectors
still define the portable ScalaScript behavior.

### Canonical Model

All formats encode the same logical model:

```scala
enum WireValue:
  case Null
  case Unit
  case Bool(value: Boolean)
  case Int64(value: Long)
  case Float64(value: Double)
  case String(value: String)
  case Bytes(value: Array[Byte])
  case List(values: Vector[WireValue])
  case Map(entries: Vector[(WireValue, WireValue)])
  case Object(typeName: String, fields: Vector[(String, WireValue)])
  case Tuple(values: Vector[WireValue])
  case Enum(typeName: String, caseName: String, value: Option[WireValue])
  case Pid(nodeId: String, localId: Long)
  case Error(code: String, message: String, details: Option[WireValue])
```

`WireValue` is not the final user-facing typed-data model. It is the common
runtime boundary used by untyped actor messages and by generated bridge code.
Typed APIs should prefer:

```scala
trait WireCodec[A]:
  def encode(value: A): WireValue
  def decode(value: WireValue): Either[WireDecodeError, A]
  def schemaId: String
```

`WireCodec[A]` composes with existing `JsonCodec[A]`, `DatasetCodec[A]`,
`ObjectCodec[A]`, graph codecs, and route-client codecs. Early phases may
derive `WireCodec[A]` through the existing typed-data metadata; later phases
can optimize direct binary encode/decode for hot paths.

## Frame Envelope

Every internal message carries a small envelope independent of transport:

```scala
case class WireEnvelope(
  protocol:      String,             // "actors", "dataset", "dstream", "rpc", "object-sync"
  protocolVer:   Int,                // per-surface protocol version
  format:        String,             // "json" | "msgpack" | "cbor"
  kind:          String,             // surface-specific message kind
  correlationId: Option[String],     // request/reply, stream chunks, tracing
  schemaId:      Option[String],     // typed payload schema hash/id
  flags:         Set[String],        // compressed, hmac, final, error, etc.
  headers:       Map[String, String],
  payload:       WireValue,
)
```

Binary transports encode the envelope as the selected binary format. The
transport may add its own outer frame:

- WebSocket: one WS message equals one `WireEnvelope`; binary formats use WS
  binary frames, JSON uses text frames.
- HTTP: body is one `WireEnvelope` or one typed payload plus `X-ScalaScript-*`
  headers, depending on route-client mode.
- stdio/plugin: keep the existing 4-byte length prefix for MsgPack; future
  shared code may reuse the same codec implementation.

Large payloads can be chunked:

```scala
flags = Set("chunked")
headers = Map(
  "chunk-id"    -> "...",
  "chunk-index" -> "0",
  "chunk-count" -> "12"
)
```

Chunking is required for Dataset/DStream partitions that exceed
`maxFrameBytes`.

## Negotiation

### Global and Front-matter Configuration

Initial default:

```yaml
wire:
  enabled: false
  format: json
```

Opt-in example:

```yaml
wire:
  enabled: true
  format: cbor          # json | msgpack | cbor
  jsonFallback: true
  compression: zstd     # none | gzip | zstd
  integrity: hmac-sha256 # none | hmac-sha256
  maxFrameBytes: 16777216
  surfaces:
    actors: true
    dataset: true
    dstream: true
    rpc: true
    objectSync: true
```

CLI flags override front matter:

```text
ssc run app.ssc --wire-format cbor
ssc run app.ssc --wire-format msgpack --wire-compression gzip
ssc run app.ssc --wire-json-fallback=false
ssc run app.ssc --wire-max-frame-bytes 33554432
```

### WebSocket

Actors and binary subscriptions negotiate via subprotocol first:

```text
ssc-actors-v2.cbor
ssc-actors-v2.msgpack
ssc-actors-v2.json
ssc-rpc-ws-v1.cbor
```

If no binary subprotocol is accepted, the runtime can fall back to JSON only
when `jsonFallback = true`.

The first frame after connection is still a `hello` envelope containing:

```scala
case class WireHello(
  nodeId:        String,
  runtime:       String,        // ScalaScript runtime version
  formats:       List[String],
  compression:   List[String],
  surfaces:      List[String],
  auth:          Option[String],
  maxFrameBytes: Int,
)
```

Same-version rule: peers reject each other if `runtime` differs, unless a later
compatibility phase explicitly enables version negotiation.

### HTTP / Typed Route Clients

Generated route clients use ordinary HTTP negotiation:

```text
Accept: application/vnd.scalascript.wire+cbor, application/json;q=0.5
Content-Type: application/vnd.scalascript.wire+cbor
X-ScalaScript-Wire-Version: 1
X-ScalaScript-Runtime: <runtime-version>
```

Server behavior:

1. If the route supports the requested binary format, decode binary.
2. If not and JSON fallback is allowed, use JSON.
3. Otherwise return `406 Not Acceptable` or `415 Unsupported Media Type` with a
   JSON error body for debuggability.

SSE remains a text protocol. Binary SSE payloads are base64-encoded chunks only
when explicitly requested; WebSocket is preferred for binary subscriptions.

## Security and Operations

Security is part of the spec, not a follow-up note.

### Transport Security

- Use TLS/WSS for cross-machine traffic.
- Auth tokens continue to flow through existing HTTP headers or WS upgrade
  headers.
- mTLS is planned for service-to-service deployments.
- The binary wire layer must not invent a replacement for TLS.

### Frame Integrity

Optional frame-level HMAC:

```text
X-ScalaScript-Wire-Integrity: hmac-sha256
X-ScalaScript-Wire-Signature: base64(...)
```

For WebSocket binary frames, the signature is carried in envelope headers. The
signature covers the encoded payload bytes plus session id, sequence number,
protocol, kind, and runtime version. This prevents accidental cross-protocol
frame replay.

### Replay and Ordering

Long-lived sessions maintain:

- `sessionId`
- monotonically increasing `seq`
- optional `ack` for reliable subprotocols
- replay window for recently seen sequence numbers

Actors keep at-most-once delivery initially. DStream/Dataset reliability stays
owned by their existing retry/checkpoint layers. The wire layer supplies ids and
acks where those layers need them; it does not promise exactly-once by itself.

### Compression

Compression is opt-in per connection or per request.

| Compression | Use |
|-------------|-----|
| `none` | Default and safest for small messages |
| `gzip` | Portable baseline, easy JVM/Node/browser support |
| `zstd` | Preferred high-throughput option where target support exists |

Compression is applied after serialization and before HMAC calculation. Do not
compress secret-bearing mixed attacker-controlled content unless the caller
explicitly opts in; this avoids known compression side-channel classes.

### Resource Limits

Every decoder must enforce:

- max frame size
- max chunk count
- max nesting depth
- max map/list element count
- max string and bytes length
- decode timeout or cooperative budget for interpreter paths

On violation, the peer receives `WireError(code = "wire.limit_exceeded", ...)`
and the runtime may close the connection.

### Observability

The runtime should expose counters per surface/format:

- frames sent/received
- bytes sent/received before and after compression
- decode/encode failures
- fallback-to-JSON count
- dropped frames by limit
- negotiation failures

These counters should feed existing cluster metrics when actors are enabled.

## Compatibility Plan

Until stable release:

- same runtime version required for binary traffic;
- JSON fallback available for debugging and mixed deployments;
- `schemaId` is emitted but not yet used for cross-version migration.

Before first stable release:

- define canonical `schemaId` hashing for case classes, enums, tuples, and
  collections;
- define allowed additive changes;
- define default values / unknown-field policy;
- add compatibility conformance tests with old/new test vectors;
- document how stable public distributed APIs declare compatibility promises.

## Migration

Existing users keep working because JSON remains default. Opt-in paths:

1. Set `wire.enabled: true` and `wire.format: msgpack` or `cbor`.
2. Run existing actor/Dataset/route-client tests with `--wire-format json`.
3. Run the same tests with `--wire-format msgpack`.
4. Run the same tests with `--wire-format cbor`.
5. Only after all target backends pass conformance can a project choose binary
   as its local default.

## Phases

### Phase 0 - Spec and Backlog

Land this document, link it from README, and add implementation phases to
`BACKLOG.md` / `WORK_QUEUE.md`. No runtime changes.

### Phase 1 - Wire Core

- Add a shared wire module for `WireValue`, `WireEnvelope`, `WireCodec[A]`,
  errors, limits, and negotiation types.
- Implement `json`, `msgpack`, and `cbor` profiles for JVM/interpreter.
- Implement matching JS/browser codecs.
- Add cross-format golden vectors and round-trip tests.
- Add CLI/front-matter parsing for `wire:` config but do not route traffic yet.

### Phase 2 - Actor Cluster Binary WebSocket

- Add `ssc-actors-v2.<format>` subprotocols.
- Send actor envelopes as binary WS frames when negotiated.
- Cover user messages, registry, heartbeat, gossip, leader election, pub/sub,
  config, drain, token rotation (`token_rotate` / `token_rotate_ack`),
  metrics, and phi vectors.
- Preserve JSON `ssc-actors-v1` for fallback.

### Phase 3 - Typed Route Clients and RPC

- Add generated binary HTTP request/response support for typed route clients.
- Add content negotiation and JSON fallback.
- Add binary WebSocket subscription frames where the endpoint supports WS.
- Keep SSE text-only except explicit base64 chunk mode.

### Phase 4 - Distributed Dataset / MapReduce

- Route `DatasetWirePartition` through `WireCodec[A]`. ✓ Landed 2026-05-29
  as `DatasetWire`, a shared typed-data bridge that wraps
  `DatasetWirePartition` in `WireEnvelope(protocol = "dataset")`.
- Add binary partition and shuffle payloads. ✓ Landed 2026-05-29 for explicit
  JSON, MsgPack, and CBOR partition envelope encode/decode helpers. Wiring the
  actor MapReduce runner to select this binary envelope on transport remains a
  follow-up.
- Add chunking for large partitions. ✓ Landed 2026-05-29 for value-boundary
  partition chunking with `chunk-id`, `chunk-index`, and `chunk-count` envelope
  headers plus deterministic reassembly.
- Verify interpreter/JVM distributed MapReduce examples with JSON, MsgPack,
  and CBOR. ✓ Landed 2026-05-29 partial runner boundary: `DistributedDataset`
  `run` / `runShuffle` accept `wireFormat` and round-trip input/output
  `DatasetWirePartition` values through `DatasetWire` before/after the actor
  runner. Direct binary actor frame selection remains a follow-up.

### Phase 5 - Native DStream Runner

**Landed v1.62.5.** Implemented in `backend/wire/.../dstream/DStreamWireProtocol.scala`.

`DStreamMsg` sealed trait with seven message kinds, `WireCodec[DStreamMsg]` instances,
and `DStreamEnvelope` helpers for building and decoding `WireEnvelope(protocol="dstream")`:

| Kind                   | `env.kind`       | Description                                    |
|------------------------|------------------|------------------------------------------------|
| `ElementBatch`         | `element-batch`  | Batch of serialized stream elements + isFinal  |
| `Watermark`            | `watermark`      | Event-time watermark with optional side-input tag |
| `Trigger`              | `trigger`        | Window trigger (EventTime/ProcessingTime/CountBased/AfterWatermark) |
| `SideInput`            | `side-input`     | Broadcast side-input data delivered to a stage |
| `SideOutput`           | `side-output`    | Tagged output branch emitted by a stage        |
| `CheckpointMetadata`   | `checkpoint`     | Sequence number + state keys + offsets JSON    |
| `DStreamError`         | `error`          | Stage failure code + message + optional cause  |

All seven kinds round-trip through JSON, MsgPack, and CBOR (58 tests).
Spark/Kafka/Flink/Beam external engine protocols are unchanged.

### Phase 6 - Object Sync and Client Cache Traffic

**Landed v1.62.6.** Implemented in `backend/wire/.../sync/ObjectSyncWireProtocol.scala`.

Four message kinds over `WireEnvelope(protocol="object-sync")`:

| Kind            | `env.kind`       | Route counterpart                          |
|-----------------|------------------|--------------------------------------------|
| `PullRequest`   | `pull-request`   | `GET /__ssc/sync/<store>/changes` params   |
| `PullResponse`  | `pull-response`  | Changes + `nextCursor`                     |
| `PushRequest`   | `push-request`   | `POST /__ssc/sync/<store>/push` body       |
| `PushResponse`  | `push-response`  | Applied results + conflicts                |

Supporting value types: `SyncChange` (key/version/updatedAt/deleted/value),
`SyncMutation` (key/value/deleted/expectedVersion?), `SyncResult`, `SyncConflict`.
`correlationId` threaded through pull request/response pairs.
JSON REST routes remain the public/debug fallback. 31 tests.

### Phase 7 - Security, Compression, and Operations

- HMAC signatures, session ids, sequence numbers, replay windows.
- gzip/zstd negotiation.
- mTLS configuration hooks.
- Runtime metrics and debug dump tools.

### Phase 8 - Compatibility and Evolution

- Define `schemaId` hashing and evolution rules.
- Add old/new vector tests.
- Allow configured mixed-version binary traffic when schemas are compatible.

## Testing Strategy

- Golden vectors for every `WireValue` case in JSON, MsgPack, and CBOR.
- JVM <-> JS cross-decode tests: JVM encodes, JS decodes, and vice versa.
- Actor conformance matrix with `--wire-format json|msgpack|cbor`.
- Dataset and DStream partition round trips with typed case classes, enums,
  tuples, nested collections, bytes, and errors.
- HTTP typed-client tests for content negotiation, fallback, 406/415 failures,
  and binary response decode.
- Limit tests for depth, size, chunk count, and malformed frames.
- Security tests for HMAC mismatch, replayed sequence numbers, wrong session,
  wrong protocol binding, and compression negotiation failures.

## Open Questions

- Whether CBOR or MsgPack becomes the recommended production default after both
  pass conformance. MsgPack is faster to ship because the plugin subprocess
  path already uses it; CBOR is stronger as a long-term standard profile.
- Whether binary route-client payloads should use full `WireEnvelope` bodies or
  typed payload bodies plus HTTP headers in the hot path.
- Whether zstd should be a built-in dependency or a plugin capability.
- Whether object sync should advertise binary support in OpenAPI extensions.
- How much of compatibility/evolution belongs in `data-mapping.md` versus this
  protocol document.
