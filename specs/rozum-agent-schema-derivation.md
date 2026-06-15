# Rozum Agent Schema Derivation

## Overview

Add a typed tool helper to `std.agent` that derives OpenAI-compatible JSON
Schema parameters from ScalaScript case-class metadata. Existing explicit
schemas stay authoritative through the current `agentTool(name, description,
parametersJson)` API; derivation is an ergonomic path for ordinary typed tool
inputs, not a replacement for hand-written schemas.

## Interface

Public module: `runtime/std/agent.ssc`, package `std.agent`.

New exported type and helper:

```scalascript
trait AgentSchema[A]:
  def parametersJson: String
  def decode(argsJson: String): A

object AgentSchema:
  def derived(m: Mirror): AgentSchema[Any]

def agentToolFor[A](name: String, description: String, schema: AgentSchema[A])
                   (handler: A => ToolResult): AgentTool
```

Usage:

```scalascript
case class PostTransaction(amount: Int, memo: String) derives AgentSchema

val postTransaction =
  agentToolFor[PostTransaction](
    "post_transaction",
    "Post one transaction.",
    summon[AgentSchema[PostTransaction]]
  ) { args =>
    toolOk(jObj(List(
      jField("amount", jNum(args.amount.toString)),
      jField("memo", jStr(args.memo))
    )))
  }
```

Supported field types for derived schemas:

| ScalaScript type | JSON Schema |
|---|---|
| `String` | `{ "type": "string" }` |
| `Int` | `{ "type": "integer" }` |
| `Double` | `{ "type": "number" }` |
| `Boolean` | `{ "type": "boolean" }` |
| `List[T]` | `{ "type": "array", "items": <T schema> }` when `T` is supported |
| `Option[T]` | nullable `anyOf` and omitted from `required` when `T` is supported |
| case class `A` | top-level object schema using `Mirror.Of[A]` labels/types |

Unsupported field types fail while constructing the derived `AgentSchema`, so
bad tool declarations surface before the first model call.

## Behavior

- [x] `AgentSchema` can be derived for a top-level case-class record with
      scalar fields and produces an object schema with all non-optional fields
      in `required`.
- [x] `List[T]` fields derive array schemas and decode JSON arrays into
      ScalaScript lists when `T` is a supported scalar.
- [x] `Option[T]` fields derive nullable schemas, are not required, and decode
      missing/null JSON values to `None`.
- [x] `agentToolFor[A]` constructs a normal `AgentTool`: the outbound OpenAI
      tool JSON contains the derived schema, and local handlers receive a typed
      `A` decoded from raw tool-call arguments.
- [x] Explicit `agentTool(..., parametersJson)` remains unchanged and remains
      the fallback for unsupported or custom schemas.
- [x] Unsupported types produce a diagnostic containing the field name and type
      instead of silently emitting an underspecified schema.
- [x] Examples show both derived and explicit schemas in the same `std.agent`
      surface.

## Design

Keep this slice pure `.ssc`, layered on top of the existing `Mirror` metadata
that is already available to custom `derives` implementations. `AgentSchema`
uses `Mirror.elemLabels` and `Mirror.elemTypes` to build `parametersJson`, and
`Mirror.fromProduct` to construct the typed argument record from `JsonValue`
fields at dispatch time.

The derived helper produces the same `AgentTool` shape as the explicit helper,
so `runAgent`, streaming, and endpoint-pool code paths need no special cases.
That also preserves the current raw JSON handler API for applications that need
custom validators or schema features not expressible by the bounded derived
subset.

## Decisions

- **Explicit schema argument on the typed helper** -- chosen because
  ScalaScript already supports `derives` through runtime `Mirror` metadata, and
  `summon[AgentSchema[A]]` keeps derivation visible at the call site. Rejected:
  a trailing `using AgentSchema[A]` clause after the handler, because the current
  interpreter call path treats that shape as a missing curried handler argument.
  Rejected: compiler rewriting of arbitrary handler signatures, because that
  would require a larger typer/codegen contract and is not needed for the first
  usable slice.
- **Explicit schemas remain authoritative** -- chosen to keep custom JSON Schema
  constraints, enums, descriptions, and one-off validation under application
  control. Rejected: trying to merge explicit and derived schemas in this slice,
  because conflict rules would be more complex than the base feature.
- **Fail fast on unsupported field types** -- chosen so a tool never reaches a
  model with a misleading schema. Rejected: falling back to `{ "type": "object" }`
  for unknown types, because it hides missing coverage.
- **Nested records are out of scope** -- chosen because the public `Mirror`
  metadata available to `.ssc` derivation gives nested type names, not nested
  mirrors. A future compiler-assisted derivation can add `$defs` or inline
  nested objects without changing `agentToolFor[A]`.

## Out of scope

- Automatic rewriting of `agentTool("name") { (args: A) => ... }` syntax.
- Nested record derivation, sealed ADTs, maps, tuples, enums, BigInt, Long,
  Decimal, or custom field annotations.
- JSON Schema descriptions, enum values, min/max constraints, and defaults.
- Compile-time diagnostics; this slice reports unsupported derived schemas when
  the `AgentSchema` value is constructed.
- Changing `runAgent`, streaming, or endpoint-pool loop semantics.

## Results

Implemented in `runtime/std/agent.ssc` as pure `.ssc` library code with no new
backend intrinsic. Added `examples/rozum-agent-schema-derived.ssc`, covering
derived and explicit schemas side by side.

Verification:

- `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-agent-schema-derivation && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSchemaDerivationInterpreterTest"` -- 4 tests passed, covering derived schema JSON, typed handler dispatch/decode, explicit schema fallback, unsupported-type diagnostics, and the new example.
- `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-agent-schema-derivation && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSchemaDerivationInterpreterTest scalascript.AgentSdkInterpreterTest scalascript.AgentSdkStreamingInterpreterTest scalascript.AgentEndpointPoolInterpreterTest"` -- 25 tests passed across schema derivation, P0 agent loop, streaming, and endpoint-pool regression suites.
