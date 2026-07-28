# UniML YAML dialect — YAML 1.2.2 lossless safe profile

## Overview

The UniML YAML dialect reads YAML 1.2.2 presentation streams into the common token-as-instruction
tree VM. It preserves document boundaries, directives, indentation, block/flow collection style,
scalar style/header/chomping, exact scalar spelling, tags, anchors, aliases, comments, whitespace,
mapping order, and duplicate entries. Parsing constructs a lossless serialization/presentation CST;
it does not instantiate application objects, invoke tag constructors, or expand aliases.

Normative source: [YAML 1.2.2 specification](https://yaml.org/spec/1.2.2/). The initial semantic
projection uses its recommended Core Schema. YAML 1.1 implicit typing is explicitly not enabled.

## Interface

Package: `scalascript.uniml.dialect.yaml`.

```scala
object YamlDialect extends DialectAdapter:
  val id: String = "yaml.1.2.2"
  override val aliases: Set[String] = Set("yaml", "yml", "application/yaml")

object Yaml:
  def parse(source: SourceInput, limits: YamlLimits = YamlLimits.default): ParseResult
  def project(result: ParseResult, options: YamlProjectionOptions = YamlProjectionOptions.default): YamlProjectionResult

final case class YamlLimits(
  core: Limits = Limits.default,
  maxSourceCodePoints: Long = 64L * 1024 * 1024,
  maxLineCodePoints: Int = 1024 * 1024,
  maxScalarCodePoints: Int = 16 * 1024 * 1024,
  maxIndentation: Int = 4096,
  maxAnchors: Int = 1_000_000,
  maxAliases: Int = 1_000_000,
)

enum YamlValue:
  case Stream(documents: Vector[YamlDocument])
  case Mapping(entries: Vector[YamlEntry], tag: Option[String], anchor: Option[String])
  case Sequence(values: Vector[YamlValue], tag: Option[String], anchor: Option[String])
  case Scalar(value: YamlScalar, tag: Option[String], anchor: Option[String])
  case Alias(name: String)

final case class YamlDocument(value: Option[YamlValue], directives: Vector[YamlDirective])
final case class YamlEntry(key: YamlValue, value: YamlValue, span: SourceSpan)

enum YamlScalar:
  case StringValue(value: String, lexeme: String, style: ScalarStyle)
  case NullValue(lexeme: String)
  case BooleanValue(value: Boolean, lexeme: String)
  case IntegerValue(lexeme: String)
  case FloatValue(lexeme: String)

enum ScalarStyle:
  case Plain, SingleQuoted, DoubleQuoted, Literal, Folded

final case class YamlProjectionOptions(
  schema: YamlSchema = YamlSchema.Core,
  aliases: AliasPolicy = AliasPolicy.Preserve,
  maxAliasExpansions: Int = 100_000,
  maxExpandedNodes: Int = 1_000_000,
)

enum AliasPolicy:
  case Preserve, Resolve

enum YamlSchema:
  case Failsafe, Json, Core
```

There is no canonical `Map` projection. `YamlValue.Mapping.entries` preserves source order and
duplicates; any conversion to a host map requires an explicit duplicate-key policy outside this API.

## Behavior

### Streams and lossless CST

- [x] A YAML stream with zero or more implicit/explicit documents preserves `%YAML`/`%TAG`/reserved
      directives, `---`/`...` markers, comments, blank lines, indentation, line endings, and exact
      tokens in source order.
- [x] Block mappings/sequences and flow mappings/sequences build balanced UniML branches with ordered
      source-token edges. Explicit `?` keys, empty keys/values, compact collections, and JSON-compatible
      flow syntax remain distinguishable through exact indicator tokens and semantic entries/items.
- [ ] Plain, single-quoted, double-quoted, literal `|`, and folded `>` scalars retain exact spelling,
      multiline indentation, explicit indentation indicators, strip/clip/keep chomping, escapes, and
      separation comments while projecting the YAML-defined cooked value.
- [x] Tags, tag handles/verbatim tags, anchors, and aliases are exact source tokens attached to their
      nodes. Anchor names are document-local; aliases must refer to a preceding anchor in the same
      document for a resolvable graph.
- [x] Mapping order and duplicate keys remain observable. Duplicate keys produce a stable warning in
      semantic projection and are never silently first/last-wins.

### Grammar and diagnostics

- [ ] Indentation is spaces-only, context-sensitive, and bounded. Tabs used for indentation,
      inconsistent dedent, illegal compact nesting, missing mapping separators, malformed flow
      delimiters/separators, and illegal document/directive positions are errors.
- [ ] YAML printable-character, BOM, line-break, indicator, separation, comment, escape, URI/tag,
      anchor/alias, and scalar restrictions follow YAML 1.2.2; raw unpaired surrogates are rejected.
- [x] Flow delimiter stacks and block indentation stacks are explicit and deterministic; malformed or
      truncated input returns partial/error CST plus structured diagnostics, never a platform exception.
- [x] Tokenization/CST/diagnostics/projection are identical for every `SourceChunk` split, including
      inside CRLF, surrogate pairs, quoted escapes, directives, tags, anchors, flow collections, and
      block scalar headers/content.

### Safe semantic projection

- [x] Core Schema resolves only the YAML 1.2 null, boolean, integer, and floating productions;
      strings such as `yes`, `no`, `on`, `off`, and arbitrary timestamps remain strings. Exact numeric
      lexemes are retained and never forced through `Double`.
- [x] Failsafe and JSON schemas are separately selectable. Explicit unknown/local/application tags are
      preserved as data and never dispatched to constructors, reflection, class loading, or code.
- [x] `AliasPolicy.Preserve` returns alias nodes. `Resolve` uses a document-local anchor table, detects
      undefined aliases/cycles, and enforces expansion/node limits; parsing itself never expands.
- [x] Direct self-reference and mutually recursive alias graphs remain representable in preserved
      projection but fail bounded tree expansion explicitly rather than looping.

### Limits and targets

- [x] Source/line/scalar/indentation/anchor/alias plus core depth/node/token/diagnostic limits are finite
      and produce fatal structured diagnostics before unbounded memory or stack growth.
- [x] Parse/project code performs no filesystem, network, environment, reflection, schema fetch,
      custom constructor, expression interpolation, or code execution.
- [x] Focused suites pass unchanged on JVM and Scala.js, including exhaustive two-chunk splits for
      CRLF, escapes, supplementary Unicode, directives, tags, anchors, flow collections, and block
      scalar content.
- [x] The M3 corpus gate includes a pinned official YAML test-suite **subset** and differential
      Core Schema values against an independent YAML 1.2 implementation.
- [ ] The M3.1 production gate compares all representable cases in the pinned 402-case
      `yaml/yaml-test-suite` `data-2022-01-17` corpus, including validity and normalized
      `test.event` semantics, on JVM and Scala.js.

## Token model

Stable token families (specific kinds use the `yaml.` prefix):

| Family | Examples |
|---|---|
| stream/document | `%YAML 1.2`, `%TAG !e! tag:example:`, `---`, `...` |
| block structure | indentation, `-`, `?`, `:` |
| flow structure | `[`, `]`, `{`, `}`, `,`, `?`, `:` |
| node properties | `!tag`, `!<uri>`, `&anchor`, `*alias` |
| scalar | plain, single-quoted, double-quoted, literal/folded header and content |
| presentation | spaces, line break, blank line, comment, BOM |
| error | maximal recoverable invalid spelling |

Token kinds include `yaml.directive`, `yaml.document-start`, `yaml.document-end`,
`yaml.indentation`, `yaml.sequence-indicator`, `yaml.explicit-key`, `yaml.value-indicator`,
`yaml.flow-open`, `yaml.flow-close`, `yaml.flow-separator`, `yaml.tag`, `yaml.anchor`, `yaml.alias`,
`yaml.scalar.plain`, `.single`, `.double`, `.literal`, `.folded`, `yaml.comment`, `yaml.whitespace`,
`yaml.line-break`, and `yaml.invalid`.

Line breaks preserve exact CR/LF/CRLF spelling in CST spans. Projection applies YAML normalization and
folding. Comments never become scalar content. A `#` begins a comment only where separation rules
permit; `:`/`-`/`?` become indicators only in their grammar contexts.

## CST and VM mapping

The adapter uses only universal VM instructions, including the format-neutral `Reframe` operation:

- document start (explicit marker or first source-backed node token) opens `yaml.document`;
- block/flow mapping open tokens open `yaml.mapping`; sequence open/first dash opens `yaml.sequence`;
- mapping key/value and sequence item nodes attach with `mapping.key`, `mapping.value`, or
  `sequence.item` roles;
- a first source token after dedent carries `Reframe.closeBefore`; a final source token carries
  `Reframe.closeAfter` for implicit end-of-document/end-of-stream closures; expected close kinds are
  checked innermost-first, new frames are opened outermost-first, and the carrier token remains exactly
  once in the tree;
- flow, block, document, and stream ranges are known after the bounded scan; their first/last source
  tokens carry `Reframe.open`/`closeAfter`, including multiple same-token transitions;
- presentation tokens use ordered trivia/comment roles;
- lexical/structural errors retain their ordinary source tokens and travel as processor-batch
  diagnostics; no synthetic error or EOF token is created.

Because block YAML has implicit closures, the bounded structural pass records branch ranges with a
parallel indentation stack and assigns transitions after the final source chunk. One instruction can
open several scopes, emit its carrier, and close final scopes atomically, so neither synthetic
DEDENT/EOF tokens nor token duplication is required.

## Lexer and structural parser

M3 may retain one source buffer bounded by `maxSourceCodePoints` for a linear, chunk-invariant scan.
The scanner emits line-break/indentation/presentation tokens separately and recognizes quoted/flow
constructs across physical lines. The semantic projection pass recognizes block-scalar headers and
consumes all subsequent lines belonging to the detected/explicit indentation.

The iterative CST structural pass owns stream/document ranges plus block indentation and flow
delimiter stacks. The separate bounded semantic projection parser owns:

- stream/document phase and directive handles;
- block frames `(indent, kind, expectation)`;
- flow frames `(kind, expectation)`;
- pending node properties (tag/anchor);
- per-document anchor declarations and alias references.

Semantic recursion is capped at 512 in addition to the VM's configurable core depth. Recovery
synchronizes at a line break/document marker in block context or comma/closing delimiter in flow
context.

### M3.1 scanner and parser-state contract

M3.1 replaces the line-oriented recognition shortcuts with one shared portable syntax scan and an
explicit parser-state stack. The implementation is split as follows:

- `YamlSyntax.scala` owns the code-point cursor, physical-line records, lexical classes, directive
  and property spellings, scalar fragments, and exact source spans. It is the single lexical
  authority used by CST token emission and semantic parsing.
- `YamlLexer.scala` maps syntax records to source-backed `yaml.*` tokens. It does not re-guess
  indicator context from individual `Char` values.
- `YamlStructure.scala` receives source-ordered parser ranges. It may assign VM reframes but may not
  infer YAML nesting from a per-line colon/dash heuristic.
- `YamlSemanticParser.scala` consumes syntax records with explicit stream/document, block, flow,
  property, scalar, and recovery frames.
- `YamlSemanticEvents.scala` owns the ordered test-event-compatible trace described below. It has no
  corpus data or expected-value knowledge.

The cursor carries a UTF-16 index plus public code-point offset, line, and column. CR and LF are
line breaks; CRLF performs one line transition while consuming two code-point offsets. A scanner
must never split CRLF cursor state or a surrogate pair merely because the transport chunk ended.
The YAML printable set is exactly tab, CR, LF, `U+0020..U+007E`, `U+0085`,
`U+00A0..U+D7FF`, `U+E000..U+FFFD`, and `U+10000..U+10FFFF`. Other controls and unpaired
surrogates produce `uniml.yaml.invalid-character`. A BOM is presentation only at a YAML-permitted
stream/document boundary; elsewhere it is an error.

Transport accumulation keeps an explicit immutable chunk sequence and running code-point/UTF-16
sizes, rejects limits before joining, and materializes the bounded source at most once. Repeated
`state + chunk` or another whole-prefix copy per input chunk is forbidden. The dialect adapter's
hidden processor-state type may be generalized to carry this buffer; the public
`Processor[S, I, O]` contract and parse result do not change.

The parser stack, not indentation lookahead alone, decides whether `-`, `?`, `:`, `#`, flow
delimiters, and scalar continuations are syntax. Required states cover:

- directive prelude and document boundaries, including one `%YAML`, document-scoped `%TAG`
  handles, duplicate/undefined handles, reserved directives, and the mandatory explicit start
  following directives;
- block sequence/mapping frames with required indentation, indentationless sequence entries,
  compact/property-only nodes, explicit/complex keys, and empty keys/values;
- flow sequence/mapping frames with pair/key/value/separator expectations and multiline
  separation;
- pending tag/anchor properties, which attach once to the following node;
- all five scalar styles, including multiline quote folding and block-scalar
  indentation/chomping.

Recovery is deterministic and local. In block context it synchronizes at a same-or-lower-indented
line or document marker; in flow context at a comma or the matching/outer closing delimiter.
Skipped source is still emitted once as source-backed syntax/error tokens. CST construction
continues after a recoverable error and never manufactures a successful semantic node from skipped
text.

### Ordered semantic event trace

The official semantic observable is generated while parsing, not reconstructed afterward from
`YamlValue`. The private result contains both the public projection tree and
`Vector[YamlSemanticEvent]`. Events are emitted in source order:

```text
stream-start
document-start
mapping-start | sequence-start | scalar | alias
mapping-end | sequence-end
document-end
stream-end
```

Start/scalar events retain the effective tag, anchor, scalar value/style where applicable. `%TAG`
expansion uses the current document's handle table. The lossless CST retains the exact tag-property
spelling, including handle and `%HH` bytes. `YamlValue.tag` carries the expanded representation tag:
the handle is replaced by its prefix, while each percent escape and its hex-letter case remain
exactly as presented, following YAML 1.2.2 section 5.6.

The parser-event trace is a distinct lower-level observable. To match YAML 1.2.2 Example 6.26 and
the pinned `yaml-test-suite` event notation, it decodes a contiguous `%HH` run only when the bytes
form valid UTF-8; an opaque non-UTF-8 run remains percent-escaped. This compatibility view never
changes the CST or `YamlValue`. Keeping representation tags separate from parser-event tags is
intentional because the normative section 5.6 preservation rule and Example 6.26's decoded event
spelling contradict each other (the conflict is also tracked upstream as
`yaml/yaml-test-suite#9`).

Only malformed percent-triplet syntax is a YAML error: `%`, `%0`, or a non-hexadecimal pair.
Percent octets are otherwise opaque, so overlong, surrogate-valued, or out-of-range UTF-8 byte
patterns remain valid representation-tag spelling. Validation and optional event normalization are
linear in the bounded tag spelling; repeated whole-prefix concatenation is forbidden.

The bare `!` property is the non-specific tag, not a shorthand with an empty suffix. It remains
exactly `!` even when a `%TAG ! ...` directive overrides the primary handle; only a non-empty
primary-handle shorthand such as `!name` uses that declaration. Bare `!` must be followed by YAML
separation or end-of-input. A flow indicator immediately after it (`![...]`, `!{...}`) is not
silently reinterpreted as tagged collection content.

Anchor declarations and alias lookup use source order; an alias never binds to a later declaration,
and a duplicate anchor affects only later aliases.

At the first `Error` or `Fatal` diagnostic, the semantic trace freezes at the events already
observed. Recovery continues to produce the lossless partial CST and bounded diagnostics, but it
cannot append speculative semantic events. This matches the official invalid-case `test.event`
prefix without using expected-error markers to skip parsing. Warnings do not freeze the trace.
Valid input must close every event frame and finish with `stream-end`.

`YamlValue` remains the public projection. It is derived from the same successful parser frames,
not reparsed from concatenated CST tokens. Projection validates source identity, monotone spans,
unique token ids, and traversal/source order; it never sorts tokens to turn an invalid CST into an
apparently valid source stream.

### Baseline evolution

The UPR-1 baseline is the immutable historical starting point. During UPR-2, each independently
shippable grammar slice runs every official case first and produces a complete candidate row set.
The checked-in live baseline may advance only in the same reviewed slice after:

1. the old and new per-case rows are diffed;
2. every changed row is attributed to that grammar behavior;
3. source/chunk exactness remains 402/402 and crashes remain zero;
4. validity/semantic/strict counts do not regress; and
5. JVM and Scala.js produce byte-identical rows and category totals.

Aggregate counts alone never authorize a baseline update. The final UPR-2 baseline is 402 strict
matches; no expected-failure list or production-based preclassification is permitted.

## Scalar projection

Projection separates presentation from resolution:

1. cook style-specific content (quote escapes/folding, block indentation/chomping);
2. determine explicit tag or selected schema's implicit tag;
3. return a `YamlScalar` retaining both cooked value and exact lexeme/type spelling.

Core Schema plain-scalar resolution follows YAML 1.2.2 exactly. Quoted and block scalars default to
strings. Integers/floats retain their lexeme; optional numeric conversion is a later caller decision.
Non-specific `!`, local tags, and unknown global tags remain explicit data.

## Anchors and aliases

The CST records anchors/aliases without graph construction. Projection builds a document-local table
in source order. Duplicate anchor names replace the previous binding for later aliases as specified by
YAML serialization semantics, with a warning because it is often accidental. An alias before any
matching anchor is an error.

Resolution walks the already parsed representation in source order and snapshots the binding visible
at each alias occurrence. A document-wide last-wins table is forbidden: it would bind a preceding
alias to a later duplicate declaration. Alias names remain the public preserved representation; the
binding snapshot is private resolution state.

Preserved projection can represent cycles/reuse. Resolved projection clones through a visiting set and
counts expansions/nodes; cycle or limit failure returns diagnostics and no partially trusted expansion.
Merge key `<<` has no special Core Schema behavior in M3; it is an ordinary key unless a future explicit
merge extension is selected.

## Security

- Tags are strings, never constructor/function/class identifiers.
- Alias expansion is off by default and independently bounded when enabled.
- Directives/tag URIs are never fetched.
- No environment substitution, templating, interpolation, filesystem include, or application-specific
  object creation exists in the dialect module.
- Exact numeric/string text is retained without eager large-number allocation.
- Scanner/parser/projection buffers and stacks obey explicit limits.

## Diagnostics

Stable groups include:

- `uniml.yaml.invalid-character`, `.invalid-indentation`, `.tab-indentation`
- `uniml.yaml.invalid-directive`, `.directive-position`, `.document-marker`
- `uniml.yaml.expected-node`, `.expected-key`, `.expected-value`, `.expected-separator`
- `uniml.yaml.unclosed-flow`, `.unexpected-flow-close`, `.unexpected-eof`
- `uniml.yaml.invalid-plain`, `.invalid-single-quoted`, `.invalid-double-quoted`
- `uniml.yaml.invalid-block-scalar`, `.invalid-chomping`, `.invalid-indent-indicator`
- `uniml.yaml.invalid-tag`, `.invalid-anchor`, `.invalid-alias`, `.undefined-alias`, `.alias-cycle`
- `uniml.yaml.duplicate-key`, `.duplicate-anchor`
- `uniml.yaml.projection-invalid-cst`, `.projection-unknown-tag`
- `uniml.yaml.limit.source`, `.line`, `.scalar`, `.indentation`, `.anchors`, `.aliases`, `.expansion`

## Module layout

```text
uniml/yaml/
  src/main/scala/scalascript/uniml/dialect/yaml/
    YamlDialect.scala
    YamlLexer.scala
    YamlStructure.scala
    YamlSemanticParser.scala
    YamlValue.scala
    YamlProjection.scala
  src/test/scala/scalascript/uniml/dialect/yaml/
```

`unimlYamlCross` uses `CrossType.Pure`; aliases are `unimlYaml` and `unimlYamlJs`; artifact name is
`scalascript-uniml-yaml`. Its only production dependency is `unimlCross`. Existing ScalaScript YAML
parsers may be test/differential or later projection consumers but are not the lossless CST source.

## Out of scope

- YAML 1.1 implicit booleans/timestamps and implementation-specific legacy tags.
- Application object constructors, arbitrary tags, reflection, or code execution.
- Implicit merge-key semantics, schema validation, querying, patching, formatting, or generation.
- Incremental reparse in the dialect leaf; edit-delta reuse belongs to the M6 tooling layer.
- Replacing current ScalaScript front-matter/runtime YAML parsing in M3.

The former “exotic productions” deferral is no longer out of scope. Multiline quoted-scalar folding,
the YAML printable profile, complete `%TAG` handle expansion, and indentationless/property-only
combinations are required by the M3.1 production contract in
[`uniml-production.md`](uniml-production.md).

## Decisions

- **YAML 1.2.2 Core Schema default** — chosen for current interoperable semantics. Rejected: YAML 1.1
  `yes/no/on/off` coercions.
- **Presentation CST is canonical** — chosen because comments/styles/order/directives are not part of
  the representation graph. Rejected: delegating directly to a map/list loader.
- **Ordered duplicate-preserving mappings** — chosen because source tooling must retain all entries.
  Rejected: canonical host `Map` with silent last-wins behavior.
- **Alias preservation by default** — chosen to avoid expansion bombs and retain graph identity.
  Rejected: automatic recursive cloning during parse.
- **Tags are inert data** — chosen for portability/security. Rejected: JVM/JS-specific constructors.
- **Generic VM reframing plus a separate cross-module** — chosen to keep `TreeVm` aware only of frame
  transitions while all YAML indentation grammar remains in the dialect. Rejected: YAML-specific
  indentation rules in core and synthetic DEDENT tokens.

## Results

The cross-module landed in `48720429c`; malformed-flow recovery followed in `371e99abc`, nested
property-only nodes in `c9f599589`, limit/split reinforcement in `d608a8dd2` plus `ab3acdf81`, and
official/differential corpus coverage in `0cf72b971`.
Verification on 2026-07-12:

```text
scripts/sbtc ";unimlYaml/test;unimlYamlJs/test"
# JVM:      18 tests, 2 suites, all passed
# Scala.js: 17 tests, 1 suite, all passed

tests/conformance/run.sh --only 'yaml*,content*'
# 6 passed, 0 failed; existing content cases were memoized green

git diff --check
# clean
```

The focused suite covers lossless block/flow structures, explicit/empty/compact nodes, all five
scalar styles in the implemented profile, directives and multi-document streams, three schemas,
ordered duplicates, inert tags, nested anchors, preserved/resolved aliases, self/mutual cycles,
finite expansion and parse limits, malformed flow recovery, and every two-chunk split of two dense
documents. The official subset pins eight valid presentation cases from `yaml/yaml-test-suite`
`data-2022-01-17` (`6e6c296a`), while the JVM-only differential suite compares 27 Core Schema scalar
classes with SnakeYAML Engine 2.9. The unchecked grammar/lexical behavior items and explicit
out-of-scope paragraph bound M3 honestly; they are the queued M3.1 hardening work, not hidden claims.

UPR-2a.1 landed in `3341a35a9` on 2026-07-28. Document-scoped `%TAG` handles now expand on
scalar and collection nodes, reset between documents, preserve raw and representation `%HH`
spellings, and expose the separately normalized parser-event compatibility view described above.
Bare non-specific `!` remains independent of the primary handle and requires separation from flow
collection content. Duplicate declarations, undefined handles, and malformed percent triplets
produce structured diagnostics.

Verification for that slice:

```text
YamlDialectSpec
# JVM: 21/21 passed; Scala.js: 21/21 passed

YamlOfficialCorpusSpec
# JVM: 18/18 passed; Scala.js: 18/18 passed

full YAML suites
# JVM: 40/40 passed; Scala.js: 39/39 passed

uniml/lint-portable-subset.sh
# passed

scripts/verify-uniml-dual-build
# root and standalone products remained isolated across two no-clean transitions

tests/conformance/run.sh --only '*yaml*'
# 1/1 passed (memoized from the unchanged green case)
```

The compare-first 402-case census moved without exclusions from validity `210→214`, semantics
`128→138`, strict `112→126`, actual errors `220→216`, and failures `290→276`, with zero crashes.
The frozen post-slice baseline SHA-256 is
`7cd2d76efd0e26252097722ac1fb7d577936fb9f665ae6e852c811a9098123e3`; its category SHA-256 is
`25d786c1259235b820b550e58c67ef0c31e513771d226bc3ba60ed52d77fe5ad`. Two independent read-only
reviews accepted the final JVM/Scala.js-portable implementation. Full tag/property lexical grammar
remains UPR-2a.2; this progress is not an M3.1 conformance claim.

M3.1 is complete only when the three unchecked behavior rows above are checked from the full
compare-first corpus and resource gates defined in
[`uniml-production.md`](uniml-production.md). The eight-case M3 subset remains useful regression
coverage but is not a production YAML 1.2.2 conformance claim.
