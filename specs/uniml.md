# UniML — universal token-to-tree markup VM

## Overview

UniML is a standalone, target-neutral module for turning an ordered input stream into a
lossless tree. Its common execution model is deliberately smaller than any concrete syntax:
each item in the final input stream pairs one source token with exactly one virtual-machine
instruction, and the instruction incrementally builds the output tree. Streaming processors
may be chained before or after that VM, so Markdown, JSON, YAML, XML, ScalaScript, and other
programming-language dialects can share the same transport, diagnostics, tree, and composition
contracts without pretending that their grammars are identical.

The canonical UniML tree is a concrete syntax tree (CST), not a lowest-common-denominator data
model. It preserves punctuation, comments, whitespace, duplicate keys, ordering, source spans,
and dialect-specific node kinds. A dialect may additionally provide a semantic projection (for
example JSON values, a Markdown document, `scalascript.markup.Markup`, or a compiler AST), but
that projection is not the universal representation.

## Interface

The Scala package is `scalascript.uniml`. The first public surface is dependency-free Scala 3
and is cross-compiled for JVM and Scala.js.

```scala
package scalascript.uniml

final case class SourceId(value: String)
final case class SourcePosition(offset: Int, line: Int, column: Int)
final case class SourceSpan(source: SourceId, start: SourcePosition, end: SourcePosition)

enum TokenChannel:
  case Syntax, Trivia, Comment, Embedded, Error

final case class SourceToken(
  id: Long,
  kind: String,
  lexeme: String,
  span: SourceSpan,
  channel: TokenChannel = TokenChannel.Syntax,
)

enum VmInstruction:
  case Open(kind: String, role: Option[String] = None)
  case Close(expectedKind: Option[String] = None, role: Option[String] = None)
  case Emit(role: Option[String] = None)
  case Report(code: String, message: String, severity: Severity = Severity.Error)

final case class VmToken(token: SourceToken, instruction: VmInstruction)

enum UniNode:
  case Branch(kind: String, edges: Vector[UniEdge], span: SourceSpan, origin: Origin)
  case Token(value: SourceToken)

final case class UniEdge(role: Option[String], child: UniNode)

trait Processor[I, O]:
  def push(input: I): ProcessBatch[O]
  def finish(): ProcessBatch[O]
  final def andThen[P](next: Processor[O, P]): Processor[I, P]

final case class ProcessBatch[+A](values: Vector[A], diagnostics: Vector[Diagnostic])

trait DialectAdapter:
  def id: String
  def aliases: Set[String] = Set.empty
  def instructions(source: SourceInput): Processor[SourceChunk, VmToken]
  def project(tree: UniNode): Projection = Projection.Identity(tree)

object UniML:
  def parse(source: SourceInput, dialect: DialectAdapter, limits: Limits = Limits.default): ParseResult
```

Exact case-class defaults and helper constructors may grow compatibly, but the following are
stable contracts:

- source offsets are zero-based Unicode code-point offsets; line and column are one-based;
- spans are half-open (`start` inclusive, `end` exclusive) and never cross a `SourceId`;
- token ids are unique and monotonically increasing within one source stream;
- dialect and node kinds are stable opaque strings namespaced as `<dialect>.<kind>`;
- roles label ordered edges and never replace or reorder them;
- every `VmToken` contains exactly one `SourceToken` and one `VmInstruction`;
- `ProcessBatch` may contain zero, one, or many values, allowing lexers and projections to be
  composed without requiring the whole document in memory;
- `finish()` is called exactly once after the final input and flushes buffered state;
- parsing returns all completed roots, diagnostics, and an explicit completion status; an empty
  document is not confused with a failed parse.

`SourceInput` owns a `SourceId` and ordered text chunks. Chunk boundaries are transport details:
the same text and dialect must produce the same tokens, instructions, tree, and diagnostics for
every possible chunking.

## Behavior

### M0 — core VM and processor scaffold

- [ ] A balanced `Open → Emit* → Close` instruction stream produces one branch whose token leaves
      retain source order, exact lexemes, spans, channels, and edge roles.
- [ ] Nested `Open` instructions create nested branches; `Close(expectedKind)` diagnoses a mismatch
      deterministically and never silently closes a different frame.
- [ ] Every consumed source-backed VM input appears exactly once as a token leaf in the resulting
      tree, including opening/closing punctuation, trivia, comments, and `Report` tokens.
- [ ] A processor chain forwards values and diagnostics in order, flushes every stage on `finish()`,
      and produces the same result whether values arrive singly or in batches.
- [ ] A literal/code-point adapter demonstrates the universal fallback: arbitrary Unicode text,
      including an unknown programming language, can be ingested losslessly as `Emit` instructions
      without claiming a semantic parse.
- [ ] VM limits reject excessive nesting and node/token counts with structured diagnostics rather
      than stack overflow, unbounded allocation, or a platform exception.
- [ ] The module compiles and its focused tests pass on JVM and Scala.js.

### Dialect compatibility gates

Concrete adapters are complete only when their named gate passes. Merely ingesting text with the
literal fallback does not satisfy a semantic compatibility claim.

- **JSON gate:** RFC 8259 tokenization and grammar; ordered object members; duplicate keys preserved;
  exact number and string spellings; strict rejection of comments/trailing commas in the standard
  profile; optional JSON5-style extensions isolated in a different dialect id.
- **YAML gate:** YAML 1.2 core schema; block and flow collections; directives, anchors, aliases,
  tags, block scalars, comments, document boundaries, and duplicate mapping entries preserved.
  Alias expansion is a bounded semantic projection, never a mutation of the CST.
- **XML gate:** XML 1.0 names, namespaces, attributes, text, CDATA, comments, processing instructions,
  declarations, and DOCTYPE tokens preserved. External entity resolution is disabled by default.
  A projection may reuse `scalascript.markup.Markup` rather than duplicate that DOM.
- **Markdown gate:** CommonMark blocks/inlines plus the repository's ScalaScript extensions (YAML
  front matter, fenced languages, `${...}`, import links, metadata directives, and GFM tables), with
  embedded fence bodies retained as nested or opaque regions according to an explicit policy.
- **Programming-language gate:** an adapter declares its grammar/version and produces a lossless CST;
  embedded-language regions may delegate to another registered adapter. Unknown languages always
  remain losslessly readable through the literal dialect, but are not advertised as semantically
  parsed until a dialect adapter passes its own corpus.

## VM semantics

The VM is a deterministic pushdown tree builder. It owns a stack of open branch frames and an
ordered output-root buffer. A frame contains a node kind, the role by which the finished branch
will attach to its parent, ordered edges, a combined span, and origin metadata.

For each `VmToken(token, instruction)`, the VM performs exactly one atomic transition:

1. validate token monotonicity, span ordering, and configured limits;
2. execute the instruction effect;
3. record `token` exactly once as a `UniNode.Token` in source order;
4. return any newly completed root and diagnostics in a `ProcessBatch`.

Instruction effects are normative:

- `Open(kind, role)` creates and pushes a branch frame, then records its token as the first edge of
  that frame. The finished branch uses `role` when attached to its parent.
- `Emit(role)` appends the token to the current frame using `role`.
- `Close(expectedKind, role)` checks the current frame, appends the closing token using `role`, and
  closes only that frame. The resulting branch is appended to its parent or emitted as a root.
- `Report(...)` appends the token and emits the corresponding structured diagnostic. It does not
  smuggle recovery policy into arbitrary exceptions.

An instruction that requires a current frame while the stack is empty produces an orphan-token
diagnostic. In recovery mode it is emitted as a token root; in strict mode it is retained in the
partial result and parsing is marked incomplete. End-of-stream with open frames produces one
diagnostic per unclosed frame, innermost first. Recovery may synthesize zero-width closing nodes,
which must have `Origin.Synthetic`; it may never invent source-backed tokens.

The VM is not a bytecode evaluator for user programs. Its instructions only build syntax trees and
diagnostics; dialect adapters do not execute embedded code, resolve XML entities, expand YAML aliases,
or evaluate Markdown/ScalaScript interpolation while parsing.

## Processor chain

A processor is a single-consumer streaming transducer. A pipeline is ordinary left-to-right
composition:

```text
SourceChunk
  → Unicode/code-point decoder
  → dialect lexer
  → dialect structural processor
  → VmToken stream
  → TreeVm
  → optional validation/projection/render processors
```

Each stage owns only its local state. `push` and `finish` are synchronous, deterministic, and do not
spawn threads. Backpressure is caller-controlled: the caller does not push the next input until it has
consumed the returned batch. A composed processor forwards outputs immediately and preserves the
diagnostic order `(upstream diagnostics, downstream diagnostics caused by those outputs)`. On
completion it flushes upstream, forwards all flushed values through downstream, and then flushes
downstream exactly once.

Processor instances are deliberately not thread-safe and not reusable after `finish()`. Factories,
dialects, tokens, nodes, batches, and diagnostics are immutable and safe to share.

## Dialect adapters and embedding

Dialect selection is explicit by id, media type, file extension, or a caller-owned detector. UniML
core does not guess from content because JSON is valid YAML, Markdown can contain arbitrary text,
and code snippets are frequently ambiguous.

A dialect adapter is normally two processors: a chunk-stable lexer and a structural processor that
maps lexemes to `VmToken`s. It may delegate an embedded span to another adapter only when the host
grammar identifies the boundary (for example a Markdown fenced block). Delegation records the child
dialect id and source span; failures remain local diagnostics and never discard the host tokens.

Adapters are registered outside the core through ordinary values or a small immutable registry. No
JVM `ServiceLoader`, browser global, filesystem access, or platform type is required by the core.

## Diagnostics, recovery, and limits

```scala
enum Severity:
  case Info, Warning, Error, Fatal

final case class Diagnostic(
  code: String,
  message: String,
  severity: Severity,
  span: Option[SourceSpan],
  dialect: Option[String] = None,
  details: Vector[(String, String)] = Vector.empty,
)

final case class Limits(
  maxDepth: Int,
  maxNodes: Long,
  maxTokenCodePoints: Int,
  maxDiagnostics: Int,
)
```

Diagnostic codes are stable machine-readable identifiers. Human messages may improve without a
breaking change. The default limits are finite and documented. Exceeding a limit emits one fatal
diagnostic and stops accepting further structural growth while retaining the partial tree and already
consumed tokens. Dialect adapters may add tighter limits but may not silently disable core limits.

## Module layout

```text
v1/lang/uniml/
  src/main/scala/scalascript/uniml/
    Source.scala          # source ids, positions, spans, chunks, tokens
    Tree.scala            # UniNode, UniEdge, origin and traversal helpers
    Processor.scala       # streaming transducer and composition
    TreeVm.scala          # instructions, limits, deterministic builder
    Dialect.scala         # adapter/registry/projection contracts
    UniML.scala           # convenience parse entry point
    dialect/Literal.scala # lossless arbitrary-text fallback
  src/test/scala/scalascript/uniml/
```

The sbt projects are `uniml` (JVM alias) and `unimlJs`, backed by a
`CrossType.Pure` cross-project named `unimlCross`. The artifact name is `scalascript-uniml`.
The module has no dependency on ScalaScript `core`, `backendSpi`, `markupCore`, parser libraries,
or platform APIs. Dialect modules may depend on UniML and existing format libraries separately.

## Relationship to existing ScalaScript models

- `scalascript.markup.Markup` remains the semantic XML/HTML/SVG model and serialization surface.
  UniML is the lossless streaming CST layer; a future XML adapter may project into `Markup`.
- `DocumentContent` remains ScalaScript's stable Markdown content ABI. A future Markdown adapter may
  project a UniML tree into it, but this initial module does not change `SPEC.md` parsing semantics.
- compiler AST/CoreIR remain executable language models. A programming-language adapter may project
  into them only after parsing and validation; UniML never executes the AST.

Therefore this feature does not change the ScalaScript language grammar or runtime semantics and does
not require a normative `SPEC.md` change in M0.

## Security and resource invariants

- Parsing performs no network, filesystem, environment, reflection, or code-execution operation.
- XML external entities and remote schemas, YAML custom constructors, Markdown links, imports, and
  embedded programs are inert syntax until an explicit later capability handles them.
- Source text and diagnostic messages are data, never dynamically loaded class names or commands.
- Recursive input depth is guarded by `Limits.maxDepth`; implementation may use a mutable internal
  stack but may not recurse according to attacker-controlled tree depth.
- Projections that resolve references (YAML aliases, imports, entities) require their own cycle,
  expansion, and size limits; they are outside the core VM.

## Out of scope

- A magical grammar that semantically understands every past and future programming language.
- Automatic dialect detection in the core.
- Executing, type-checking, formatting, or compiling embedded programming languages.
- Replacing `Markup`, `DocumentContent`, the ScalaScript parser, or compiler IR in M0.
- A universal semantic value model that erases comments, punctuation, ordering, duplicate keys,
  aliases, namespaces, or language-specific constructs.
- Full Markdown, JSON, YAML, and XML adapters in the initial scaffold; they are separate staged
  modules/features governed by the compatibility gates above.

## Decisions

- **Lossless CST is canonical** — chosen because the named formats have incompatible semantic models
  and because round-trip tooling needs punctuation/comments/order. Rejected: one JSON-like value tree,
  which cannot faithfully represent Markdown, XML mixed content, YAML aliases, or program syntax.
- **One token paired with one instruction at the VM boundary** — chosen to preserve the requested
  token-as-instruction model while allowing earlier processors to perform chunking and lexing.
  Rejected: treating raw UTF-8 bytes as VM opcodes, which conflates encoding with grammar.
- **Explicit dialect adapters** — chosen because syntax compatibility is testable per grammar/version.
  Rejected: heuristic universal parsing, which is ambiguous and cannot make reliable compatibility
  claims.
- **Synchronous composable processors** — chosen for deterministic JVM/JS behavior and caller-owned
  backpressure. Rejected: a mandatory reactive-stream dependency, which would enlarge the leaf module
  and force concurrency semantics unrelated to tree construction.
- **Standalone cross-compiled leaf module** — chosen so parsers, tools, backends, and browser code can
  share it without importing compiler/runtime internals. Rejected: adding the VM to `core` or
  `markup-core`, which would couple unrelated consumers and narrow UniML to XML.
- **Reuse existing semantic models through projections** — chosen to avoid duplicate XML/Markdown
  APIs. Rejected: replacing `Markup` or `DocumentContent` before adapters prove the abstraction.

## Roadmap

1. **M0 core:** source/token model, tree VM, processor composition, limits, literal adapter, JVM/JS
   tests.
2. **M1 JSON:** strict RFC 8259 adapter and differential corpus.
3. **M2 XML:** XML 1.0 adapter plus bounded projection to/from `Markup`.
4. **M3 YAML:** YAML 1.2 adapter with anchors/aliases retained structurally.
5. **M4 Markdown:** CommonMark/GFM/ScalaScript document adapter and `DocumentContent` projection.
6. **M5 language adapters:** ScalaScript first, then reusable adapters for programming-language
   grammars selected by demand; embedded-language delegation and corpus contracts.
7. **M6 tooling:** query, rewrite, diff, source-map, incremental reparse, and formatter protocols.

Each milestone is independently releasable. A compatibility label is added only after its gate has
focused tests, an external/differential corpus where available, chunk-boundary invariance tests, and
lossless token coverage.

## Results

To be filled after M0 implementation and verification.
