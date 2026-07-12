# UniML Markdown dialect — lossless CommonMark/GFM/ScalaScript profile

## Overview

The UniML Markdown module reads Markdown as a lossless presentation language through the common
token-as-instruction VM. Its baseline dialect is CommonMark 0.31.2; GFM 0.29 and ScalaScript document
syntax are explicit profiles rather than silent extensions. The CST preserves every marker, delimiter,
indent, line ending, blank line, raw-HTML fragment, reference definition, code-fence spelling and
embedded source byte-for-byte. Semantic projection is separate and never executes HTML, links,
interpolations, or fenced languages.

Normative sources:

- [CommonMark 0.31.2](https://spec.commonmark.org/0.31.2/)
- [GitHub Flavored Markdown 0.29](https://github.github.com/gfm/)
- ScalaScript Markdown-hosting rules in `SPEC.md` and `docs/markdown-as-syntax.md`

## Interface

Package: `scalascript.uniml.dialect.markdown`.

```scala
object CommonMarkDialect extends DialectAdapter:
  val id = "markdown.commonmark.0.31.2"

object GfmDialect extends DialectAdapter:
  val id = "markdown.gfm.0.29"

object ScalaScriptMarkdownDialect extends DialectAdapter:
  val id = "markdown.scalascript"

object Markdown:
  def parse(
    source: SourceInput,
    profile: MarkdownProfile = MarkdownProfile.CommonMark,
    limits: MarkdownLimits = MarkdownLimits.default,
  ): ParseResult
  def project(result: ParseResult, profile: MarkdownProfile): MarkdownProjectionResult

enum MarkdownProfile:
  case CommonMark, Gfm, ScalaScript

final case class MarkdownLimits(
  core: Limits = Limits.default,
  maxSourceCodePoints: Long = 64L * 1024 * 1024,
  maxLineCodePoints: Int = 1024 * 1024,
  maxDelimiterRun: Int = 1024 * 1024,
  maxFenceCodePoints: Int = 16 * 1024 * 1024,
  maxReferences: Int = 1_000_000,
  maxBlocks: Int = 10_000_000,
)

enum MarkdownBlock:
  case Paragraph(inlines: Vector[MarkdownInline])
  case Heading(level: Int, inlines: Vector[MarkdownInline], setext: Boolean)
  case ThematicBreak
  case BlockQuote(blocks: Vector[MarkdownBlock])
  case ListBlock(ordered: Boolean, start: Option[Long], tight: Boolean, items: Vector[ListItem])
  case CodeBlock(info: Option[String], literal: String, fenced: Boolean)
  case HtmlBlock(raw: String)
  case LinkDefinition(label: String, destination: String, title: Option[String])
  case Table(header: Vector[TableCell], rows: Vector[Vector[TableCell]])

enum MarkdownInline:
  case Text(value: String)
  case Emphasis(children: Vector[MarkdownInline])
  case Strong(children: Vector[MarkdownInline])
  case Strikethrough(children: Vector[MarkdownInline])
  case Code(value: String)
  case Link(label: Vector[MarkdownInline], destination: String, title: Option[String])
  case Image(alt: Vector[MarkdownInline], destination: String, title: Option[String])
  case Autolink(destination: String, label: String)
  case RawHtml(raw: String)
  case SoftBreak
  case HardBreak
  case Expression(source: String)

final case class MarkdownDocument(
  blocks: Vector[MarkdownBlock],
  references: Vector[LinkDefinition],
)
```

The production leaf module depends only on `unimlCross`. A separate optional bridge may depend on
ScalaScript `core`/`ir` and project compatible nodes to the existing `DocumentContent`; the compiler
model is not the canonical Markdown representation and never becomes a dependency of the leaf.

## Behavior

### Lossless CST and CommonMark

- [ ] Empty input and arbitrary Unicode text round-trip exactly; source spans use code-point offsets
      and CR/LF/CRLF spellings remain distinct.
- [ ] ATX/setext headings, paragraphs, thematic breaks, block quotes, bullet/ordered lists and items,
      indented/fenced code, HTML blocks, link definitions, and blank-line/container structure build
      balanced source-backed branches.
- [ ] Backslash escapes, character/entity references, code spans, emphasis/strong delimiter runs,
      links/images, reference links, autolinks, raw inline HTML, soft breaks and hard breaks retain
      exact tokens while projecting CommonMark semantics.
- [ ] Lazy continuation, tight/loose lists, tab expansion, fence indentation/info strings, delimiter
      flanking and reference-label normalization follow CommonMark 0.31.2.

### Explicit profiles

- [ ] GFM adds tables/alignment, task-list items, strikethrough and extended autolinks only under
      `MarkdownProfile.Gfm`; CommonMark input is never silently reinterpreted as GFM.
- [ ] ScalaScript adds heading scopes, typed fenced blocks, Markdown links as references/imports,
      YAML front matter, and inline `${expr}` boundary nodes only under `MarkdownProfile.ScalaScript`.
- [ ] Fenced embedded source remains opaque and exact in M4. Delegating its content to JSON/YAML/XML/
      language adapters is explicit, bounded, source-mapped processor composition; it is never eager
      execution or heuristic language detection.

### Diagnostics, safety and limits

- [ ] Unterminated fences/code spans/HTML constructs and malformed link/reference syntax retain partial
      CSTs and deterministic diagnostics without losing source tokens or throwing platform exceptions.
- [ ] Raw HTML, URI destinations, reference titles and `${expr}` text are inert data. Parsing/projecting
      performs no rendering, sanitization bypass, URI fetch, file include, environment expansion,
      reflection, compiler invocation, interpolation evaluation, or fenced-code execution.
- [ ] Source/line/delimiter/fence/reference/block plus core depth/node/token/diagnostic limits fail with
      structured diagnostics before unbounded memory, allocation, or recursion.
- [ ] Tokens, CST, projection and diagnostics are identical for every `SourceChunk` split, including
      CRLF, surrogate pairs, delimiter runs, entities, links, HTML, fences and interpolations.

### Compatibility gates

- [ ] Pinned CommonMark 0.31.2 examples pass in both JVM and Scala.js lanes with a documented supported
      count and failure profile; focused GFM 0.29 examples cover every enabled extension.
- [ ] The optional ScalaScript bridge is differential-tested against the existing CommonMark-based
      `Parser.buildDocumentContent` path for representable paragraphs/lists/images/tables/fences, and
      reports model loss for raw HTML, definitions and constructs `DocumentContent` cannot express.

## Token and CST model

Stable token kinds use the `markdown.` prefix. Families include:

| Family | Representative kinds |
|---|---|
| line/container | `indent`, `line-break`, `blank`, `blockquote-marker`, `list-marker` |
| block | `atx-marker`, `setext-underline`, `thematic-marker`, `fence-open`, `fence-close`, `info` |
| inline | `text`, `escape`, `entity`, `delimiter-run`, `backtick-run`, `link-open`, `link-close` |
| destination | `destination`, `title`, `reference-label`, `autolink` |
| embedded | `code-content`, `html`, `expression-open`, `expression-content`, `expression-close` |
| GFM | `table-pipe`, `task-marker`, `strikethrough-run` |

Trivia is never discarded. Block branches use `markdown.document`, `heading`, `paragraph`,
`blockquote`, `list`, `list-item`, `code-block`, `html-block`, `definition`, and `table`. Inline
branches use `emphasis`, `strong`, `strikethrough`, `code-span`, `link`, and `image`. When several
containers begin/end on one source token, `Reframe` opens outermost-first, emits once, and closes
innermost-first.

## Scanner and structural processing

M4 may retain one bounded source buffer so arbitrary transport chunks cannot change parsing. The
scanner first produces exact line/container tokens, then assigns block ranges with explicit container,
list and fence stacks. Inline processing runs only over paragraph/heading/link-label ranges and uses
delimiter/bracket stacks; source depth never becomes unbounded host recursion.

CommonMark ambiguities are resolved by its precedence rules, not by guessing: thematic break versus
list marker, setext underline versus paragraph continuation, HTML block types, backtick-run equality,
left/right flanking delimiter runs, and link-label/reference precedence are all profile-owned state.
Malformed input recovers at a line/container boundary or an inline bracket/delimiter boundary.

## Semantic projection and `DocumentContent` bridge

Projection normalizes escapes/entities, code-span whitespace, soft/hard breaks, reference labels and
destinations while retaining the source CST separately. Duplicate normalized reference labels keep
their exact definitions; CommonMark's first definition wins semantically and later definitions produce
a tooling warning.

The optional bridge maps headings to `SectionContent`, paragraphs/lists/images/tables to matching
`ContentBlock` cases, inline emphasis/strong/code/link/expression to matching `ContentInline`, and
fences to `Embedded`. It must return diagnostics for model losses: block quotes, thematic breaks,
raw HTML, standalone definitions, hard/soft-break distinction, task checked state, and any inline kind
the target model cannot preserve.

## Security

- Raw HTML is retained, never rendered or trusted by the parser.
- Link/image/autolink destinations are strings; no scheme is opened, normalized into a request, or
  fetched. Sanitization is a renderer/application policy outside the parser.
- ScalaScript expressions and fenced languages are inert source regions until an explicitly selected
  downstream compiler/dialect processor runs under its own limits.
- Entity decoding is a finite local table/algorithm and performs no DTD or network lookup.
- Every buffer, stack, token, branch, reference table and delegated region is bounded.

## Module layout

```text
v1/lang/uniml-markdown/
  src/main/scala/scalascript/uniml/dialect/markdown/
    MarkdownDialect.scala
    MarkdownLexer.scala
    MarkdownBlocks.scala
    MarkdownInlines.scala
    MarkdownValue.scala
    MarkdownProjection.scala
  src/test/scala/scalascript/uniml/dialect/markdown/
```

`unimlMarkdownCross` uses `CrossType.Pure`; aliases are `unimlMarkdown` and `unimlMarkdownJs`;
artifact name is `scalascript-uniml-markdown`.

## Out of scope

- Rendering HTML, sanitizing HTML/URLs, fetching links/images, or executing embedded content.
- Unspecified vendor Markdown extensions outside the selected CommonMark/GFM/ScalaScript profile.
- Replacing the existing compiler Markdown/frontend path during M4.
- Incremental edit-delta reparse, formatter/rewrite protocols, or a DOM compatibility layer.

## Decisions

- **CommonMark 0.31.2 baseline, GFM 0.29 explicit** — chosen because compatibility must name a
  version/profile. Rejected: an unversioned “Markdown-ish” parser.
- **Lossless CST is canonical** — chosen for source tooling and exact rewrites. Rejected: using the
  existing CommonMark Java AST as canonical because it discards presentation trivia and is JVM-only.
- **Bounded whole-source M4 pass** — chosen for deterministic chunk invariance before incremental
  parsing exists. Rejected: chunk-sensitive speculative emission.
- **Optional `DocumentContent` bridge** — chosen to reuse the compiler model without coupling the leaf
  or hiding model loss. Rejected: making `DocumentContent` the universal Markdown AST.
- **All active content is inert** — chosen for security and target parity. Rejected: HTML rendering,
  URI loading, expression evaluation, or language execution during parse/projection.

## Results

To be filled after implementation and verification.
