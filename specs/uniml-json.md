# UniML JSON dialect — RFC 8259

## Overview

The UniML JSON dialect is the first concrete adapter for the universal token-to-tree VM. It accepts
exactly one JSON text as defined by RFC 8259, preserves every source token and lexical spelling in a
lossless UniML CST, and optionally projects that CST to an ordered semantic JSON value without
discarding duplicate object names. The adapter is a standalone JVM/Scala.js cross-module above the
dependency-free UniML core.

Normative syntax source: [RFC 8259 / STD 90](https://www.rfc-editor.org/rfc/rfc8259).

## Interface

The package is `scalascript.uniml.dialect.json`.

```scala
object JsonDialect extends DialectAdapter:
  val id: String = "json.rfc8259"
  override val aliases: Set[String] = Set("json", "application/json")
  def instructions(source: SourceInput): Processor[SourceChunk, VmToken]

object Json:
  def parse(source: SourceInput, limits: JsonLimits = JsonLimits.default): ParseResult
  def project(result: ParseResult): JsonProjectionResult

final case class JsonLimits(
  core: Limits = Limits.default,
  maxSourceCodePoints: Long = 64L * 1024 * 1024,
  maxNumberCodePoints: Int = 4096,
  maxStringCodePoints: Int = 16 * 1024 * 1024,
)

enum JsonValue:
  case ObjectValue(members: Vector[JsonMember])
  case ArrayValue(values: Vector[JsonValue])
  case StringValue(value: String, lexeme: String)
  case NumberValue(lexeme: String)
  case BooleanValue(value: Boolean)
  case NullValue

final case class JsonMember(
  name: String,
  nameLexeme: String,
  value: JsonValue,
  span: SourceSpan,
)

final case class JsonProjectionResult(
  value: Option[JsonValue],
  diagnostics: Vector[Diagnostic],
)

enum DuplicateKeyPolicy:
  case Reject, FirstWins, LastWins

object JsonProjection:
  def objectMap(
    value: JsonValue.ObjectValue,
    policy: DuplicateKeyPolicy,
  ): Either[Vector[Diagnostic], Map[String, JsonValue]]
```

`Json.parse` is a convenience wrapper over `UniML.parse(source, JsonDialect, limits.core)` plus the
JSON-specific lexical limits. `JsonDialect` itself remains usable in arbitrary processor chains.

## Behavior

### RFC syntax and lossless CST

- [ ] The adapter accepts every RFC 8259 value form at the document root: object, array, string,
      number, `true`, `false`, and `null`, with only JSON whitespace (`SP`, `HTAB`, `LF`, `CR`) around
      structural characters and the root value.
- [ ] Objects and arrays produce balanced `json.object` / `json.array` UniML branches; scalar roots
      remain exact token roots. All punctuation and whitespace are retained once, in source order.
- [ ] Object member order and duplicate names remain observable in CST edge order. Duplicate names
      are valid syntax and do not make parsing incomplete.
- [ ] String tokens preserve the original quotes and escapes. The projection decodes all short escapes
      and `\uXXXX` forms, combines escaped surrogate pairs, and reports unpaired surrogate escapes as
      interoperability warnings without rewriting the CST.
- [ ] Number tokens preserve their exact spelling and accept only
      `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`; leading zeroes, missing fraction/exponent
      digits, `NaN`, and infinities are rejected.
- [ ] JSON literals are lowercase only. Comments, trailing commas, single-quoted strings, unquoted
      names, hexadecimal numbers, leading plus, and extra data after the root are rejected with stable
      `uniml.json.*` diagnostics.
- [ ] Empty input, whitespace-only input, truncated strings/escapes/numbers/literals, and unclosed
      containers return `Incomplete` or `Halted`, never a platform exception.

### Streaming and limits

- [ ] Tokenization, spans, instructions, CST, projection, and diagnostics are identical for every
      possible `SourceChunk` boundary, including a boundary inside a surrogate pair or escape.
- [ ] Token ids increase monotonically and source offsets count Unicode code points while lexemes
      preserve the original UTF-16 string exactly.
- [ ] JSON source, string, number, depth, node, token, and diagnostic limits fail with structured fatal
      diagnostics and bounded parser/VM state.
- [ ] The adapter and projection have no filesystem, network, reflection, execution, or platform API.

### Projection

- [ ] Projection preserves object member order, duplicate names, exact key/string lexemes, and exact
      number lexemes. It never coerces a number through `Double`.
- [ ] Projection emits one `uniml.json.duplicate-key` warning for each repeated decoded key after its
      first occurrence. `objectMap` requires an explicit duplicate policy; `Reject` returns errors,
      while `FirstWins` and `LastWins` state their loss in the call site.
- [ ] Focused suites pass unchanged on JVM and Scala.js, including RFC examples, malformed corpus,
      chunk-split invariance, deep-limit cases, and differential semantic checks against a known JSON
      implementation for inputs without duplicate keys.

## Token model

The lexer emits these stable `SourceToken.kind` values:

| Kind | Lexeme | Channel |
|---|---|---|
| `json.lbrace` / `json.rbrace` | `{` / `}` | Syntax |
| `json.lbracket` / `json.rbracket` | `[` / `]` | Syntax |
| `json.colon` / `json.comma` | `:` / `,` | Syntax |
| `json.string` | complete quoted spelling | Syntax |
| `json.number` | complete number spelling | Syntax |
| `json.true` / `json.false` / `json.null` | literal spelling | Syntax |
| `json.whitespace` | maximal consecutive JSON whitespace | Trivia |
| `json.bom` | one leading U+FEFF | Trivia |
| `json.invalid` | maximal recoverable invalid spelling | Error |

Whitespace tokens are maximal across chunk boundaries. Punctuation is always one token. Strings,
numbers, and literals are never split because a transport chunk ended. A leading BOM is preserved and
accepted with `uniml.json.bom` warning, following RFC 8259's parser interoperability allowance; BOM
anywhere else is invalid.

The lexer operates on Unicode code points represented by the host `String`. Raw unpaired UTF-16
surrogates are invalid source characters. Escaped unpaired surrogates match the RFC grammar and remain
accepted with a projection warning, because RFC 8259 explicitly notes their unpredictable
interoperability.

## CST instruction mapping

JSON uses UniML's existing VM instructions without a format-specific VM:

| Context/token | Instruction / role |
|---|---|
| `{` starting a value | `Open("json.object", valueRole)` |
| `}` | `Close(Some("json.object"), Some("delimiter.close"))` |
| `[` starting a value | `Open("json.array", valueRole)` |
| `]` | `Close(Some("json.array"), Some("delimiter.close"))` |
| object name string | `Emit(Some("member.key"))` |
| object colon | `Emit(Some("member.colon"))` |
| object scalar value | `Emit(Some("member.value"))` |
| nested object/array value | `Open(..., Some("member.value"))` |
| object comma | `Emit(Some("member.separator"))` |
| array scalar value | `Emit(Some("array.element"))` |
| nested array/object element | `Open(..., Some("array.element"))` |
| array comma | `Emit(Some("array.separator"))` |
| root scalar | `Emit(Some("document.value"))` |
| whitespace | `Emit(Some("trivia"))` |
| token-backed syntax error | `Report(code, message)` |

There is no synthetic `json.member` branch in M1 because the universal VM deliberately pairs one
source token with one instruction and JSON has no separate member delimiters. A member is the ordered
`member.key → trivia → member.colon → trivia → member.value` edge sequence. The projection validates
that sequence rather than relying on a lossy map.

Leading/trailing root whitespace and a leading BOM are separate token roots; the single non-trivia
root is the JSON value token or branch. Trivia inside a container belongs to that container. This
keeps every source token in exact global order without inventing document delimiters.

## Lexer and structural parser

The adapter is two logical processors inside one `DialectAdapter`:

1. a chunk-stable lexer retains only the currently incomplete token between pushes;
2. a deterministic structural parser assigns one VM instruction to every completed token.

The first implementation may retain the completed token vector until `finish()` so it can enforce
the exactly-one-root contract and assign recovery instructions deterministically. It must not retain
duplicate copies of source chunks, and its observable output must remain the same as a future
incremental structural emitter.

Structural parsing is iterative with an explicit container-state stack. No recursive call depth is
controlled by input. Object states are `KeyOrEnd`, `Colon`, `Value`, `CommaOrEnd`; array states are
`ValueOrEnd`, `CommaOrEnd`; the document state is `Value`, then `End`. Trivia is accepted between
grammar tokens and assigned immediately. A malformed token is retained through `Report`; recovery
continues to the nearest comma/closing delimiter or end of input without executing input.

## Semantic projection

Projection consumes the CST, not the original source string. It ignores only `json.whitespace` and
`json.bom`, decodes scalar tokens, and follows ordered roles/branches to construct `JsonValue`.
Projection refuses an incomplete parse and returns the parse diagnostics unchanged plus a projection
diagnostic if the CST roles are inconsistent.

`ObjectValue.members` is the canonical semantic object representation. It is deliberately not a
`Map`: RFC 8259 says object names should be unique but documents divergent receiver behavior for
duplicates. Each repeated decoded name after the first produces a warning at that key's span.

`objectMap` is the only lossy conversion in M1 and requires the caller to select `Reject`,
`FirstWins`, or `LastWins`. No implicit/default duplicate policy exists.

## Diagnostics

Stable diagnostic codes include:

- `uniml.json.bom`
- `uniml.json.invalid-character`
- `uniml.json.invalid-literal`
- `uniml.json.invalid-number`
- `uniml.json.invalid-string`
- `uniml.json.unpaired-surrogate`
- `uniml.json.expected-value`
- `uniml.json.expected-key`
- `uniml.json.expected-colon`
- `uniml.json.expected-comma-or-end`
- `uniml.json.trailing-comma`
- `uniml.json.trailing-data`
- `uniml.json.unexpected-eof`
- `uniml.json.duplicate-key`
- `uniml.json.projection-invalid-cst`
- `uniml.json.limit.source`
- `uniml.json.limit.string`
- `uniml.json.limit.number`

Token-backed errors use `VmInstruction.Report`, so the offending spelling remains in the tree.
End-of-input errors have a zero-width EOF span and travel in `ProcessBatch.diagnostics` because no
source token exists to carry them.

## Module layout

```text
v1/lang/uniml-json/
  src/main/scala/scalascript/uniml/dialect/json/
    JsonDialect.scala
    JsonLexer.scala
    JsonStructure.scala
    JsonValue.scala
    JsonProjection.scala
  src/test/scala/scalascript/uniml/dialect/json/
```

The build exposes `unimlJson` (JVM alias) and `unimlJsonJs`, backed by `unimlJsonCross` with
`CrossType.Pure`. Artifact name: `scalascript-uniml-json`. Its only compile dependency is
`unimlCross`; ScalaTest is test-only.

## Security and limits

- Parsing and projection are pure local computation and never evaluate JSON as JavaScript.
- The lexer retains at most one incomplete token plus completed token metadata. `maxSourceCodePoints`,
  `maxStringCodePoints`, and `maxNumberCodePoints` are checked before unbounded growth.
- The structural parser uses an explicit stack and the core VM independently enforces depth/nodes.
- Exact number spellings are data; no parse-time exponentiation or arbitrary-precision allocation is
  required.
- Diagnostic count is bounded by the UniML core limit; recovery stops after a fatal limit.

## Out of scope

- JSON5, JSONC, comments, trailing commas, single quotes, identifiers, hexadecimal/NaN/Infinity.
- Schema validation, JSON Pointer, JSON Patch, query languages, and streaming semantic events.
- Automatic content/dialect detection.
- Replacing the existing ScalaScript `jsonParse` runtime intrinsic in M1.
- Serialization/generation; this milestone reads and projects JSON only.

## Decisions

- **Strict RFC 8259 profile only** — chosen for a testable compatibility claim. Rejected: silently
  accepting JSON5/JSONC extensions under the `json` id.
- **Lossless CST before semantics** — chosen to preserve whitespace, spelling, ordering, and duplicate
  keys. Rejected: delegating directly to `ujson`, whose value model cannot retain all source facts.
- **Ordered object members in the semantic model** — chosen because duplicates are valid grammar and
  receiver policies differ. Rejected: `Map[String, JsonValue]` as the canonical object.
- **Exact number lexeme** — chosen to avoid precision/range loss and cross-platform disagreement.
  Rejected: eager `Double` conversion.
- **Leading BOM preserved with warning** — chosen because RFC 8259 permits parsers to ignore it for
  interoperability. Rejected: deleting it from the lossless token stream.
- **Separate cross-module** — chosen so UniML core remains format-neutral. Rejected: adding JSON
  grammar branches to `TreeVm`.

## Results

To be filled after implementation and verification.
