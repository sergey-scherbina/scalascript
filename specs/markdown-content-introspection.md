# Markdown Content Introspection

Status: Phase 1 landed 2026-06-04. This document is the implementation
contract for the content milestone: building frontend UI from Markdown-hosted
content first, then exposing broader metadata/introspection helpers. The same
`DocumentContent` snapshot supports both paths, but Phase 1 is judged by
whether a page or screen authored mostly as Markdown can lower to the existing
frontend toolkit without hand-written markup generation.

## Overview

ScalaScript source should be useful as both executable code and structured
content. The immediate goal is frontend from Markdown: authors describe a page,
screen, product offer, docs view, or app shell in Markdown/YAML, then use code
only for behavior, data fetching, validation, and custom components.

Today front-matter is available as module metadata and headings create scopes,
but ordinary prose, lists, links, embedded YAML blocks, and other
Markdown-hosted language blocks are not exposed through a stable renderer input.
This feature introduces a Document Content IR so frontend helpers can lower the
parsed document to `std/ui` nodes or backend agnostic `View` trees. The
lower-level `std/content` introspection API is the same foundation exposed for
CLI, compiler, server, and metadata use cases after the frontend MVP is in
place.

The intended authoring model is:

1. Write the page, screen, manifest, or product description as Markdown.
2. Use YAML/front-matter or fenced YAML/JSON/TOML/etc. when the content is
   naturally structured.
3. Attach lightweight metadata where plain Markdown is ambiguous.
4. Use executable code blocks only for behavior, data fetching, validation, and
   custom renderers.
5. Let `std/ui/content.ssc` render the surrounding document to frontend views.
6. Expose the same content snapshot through `std/content` for metadata and
   non-frontend use cases once the frontend path is proven.

## Interface

### Markdown authoring surface

The content layer reads the existing Markdown body, front-matter, embedded
language blocks, and two small metadata extensions:

````markdown
---
name: pricing-page
frontend: react
content:
  defaultRenderer: toolkit
---

# Pricing {#pricing route=/pricing layout=marketing}

Simple plans for small teams.

<!-- @meta component=PlanList source=plans -->
## Plans

- Starter: $19
- Pro: $49

```yaml @id=plans-data
plans:
  - id: starter
    price: 19
  - id: pro
    price: 49
```

```scalascript
val doc = contentDocument()
println(doc.title.getOrElse(""))
println(contentSection("plans").get.title)
println(contentData("plans-data").isDefined)
```
````

Heading attributes use the CommonMark-compatible trailing attribute form:

```markdown
## Heading {#stable-id .class key=value flag}
```

- `#stable-id` overrides the generated node id.
- `.class` appends to `attrs("class")` as a space-separated class list.
- `key=value` stores a string metadata value.
- `flag` stores boolean `true`.

For non-heading blocks, the preceding HTML directive attaches metadata to the
next non-comment Markdown block in the same section:

```markdown
<!-- @meta component=Hero priority=primary -->
Welcome to ScalaScript.
```

Plain HTML comments without the `@meta` directive remain comments and do not
enter the content IR.

Fenced code blocks are also content nodes. The language tag decides whether the
block is executable, structured data, string content, or opaque source:

| Fence tag class | Content behavior |
|---|---|
| `yaml`, `yml`, `json`, `toml` | Parsed into `ContentValue` when a parser is available; original source is preserved |
| `scalascript`, `ssc`, `scala`, `sql`, `graphql`, `node.js` | Preserved as source with `kind = Executable`; normal execution/lowering stays with the existing backend rules |
| `html`, `css`, `javascript` | Preserved as source with `kind = StringBlock`; interpolation/evaluation is renderer/backend specific |
| Unknown or plugin-defined tags | Preserved as source with `kind = Opaque` unless a source-language plugin classifies them |

Front-matter remains the module manifest, but the same parsed YAML is exposed in
`DocumentContent.manifest` as `ContentValue` data so code can inspect it without
depending on raw YAML maps.

### `std/content.ssc`

The shared model is deliberately data-first because frontend rendering,
metadata, CLI, compiler, and server use cases must all read the same source of
truth. Phase 1 ships the stable model plus `contentDocument()` for the
interpreter; the remaining lookup and markdown-conversion helpers are Phase 2.
Names are prefixed with `content` to avoid collisions with existing `doc(...)`
and `render(...)` helpers.

```scalascript
case class DocumentContent(
  manifest: ContentValue,
  title: Option[String],
  description: Option[String],
  attrs: Map[String, ContentValue],
  sections: List[SectionContent],
  blocks: List[ContentBlock]
)

case class SectionContent(
  id: String,
  level: Int,
  title: String,
  attrs: Map[String, ContentValue],
  blocks: List[ContentBlock],
  children: List[SectionContent]
)

enum ContentBlock:
  case Paragraph(inlines: List[ContentInline], attrs: Map[String, ContentValue])
  case BulletList(items: List[List[ContentBlock]], attrs: Map[String, ContentValue])
  case OrderedList(items: List[List[ContentBlock]], start: Int, attrs: Map[String, ContentValue])
  case Image(src: String, alt: String, title: Option[String], attrs: Map[String, ContentValue])
  case Embedded(lang: String, source: String, kind: EmbeddedKind, data: Option[ContentValue], attrs: Map[String, ContentValue])

enum EmbeddedKind:
  case StructuredData
  case Executable
  case StringBlock
  case Opaque

enum ContentInline:
  case Text(value: String)
  case Emphasis(children: List[ContentInline])
  case Strong(children: List[ContentInline])
  case Code(value: String)
  case Link(label: List[ContentInline], href: String, title: Option[String])
  case Expr(source: String)

enum ContentValue:
  case Str(value: String)
  case Bool(value: Boolean)
  case Num(value: Double)
  case ListV(values: List[ContentValue])
  case MapV(values: Map[String, ContentValue])
  case NullV

extern def contentDocument(): DocumentContent
extern def contentCurrentSection(): SectionContent
extern def contentSection(id: String): Option[SectionContent]
extern def contentBlock(id: String): Option[ContentBlock]
extern def contentData(id: String): Option[ContentValue]
extern def contentMetadata(path: String): Option[ContentValue]
extern def contentPlainText(block: ContentBlock): String
extern def contentPlainText(section: SectionContent): String
extern def contentToMarkdown(doc: DocumentContent): String
extern def contentToMarkdown(section: SectionContent): String
```

`contentDocument()` returns a parse-time snapshot of the whole module. It is
available from every `scalascript` / `ssc` block in the interpreter. Phase 2
adds JS/JVM exposure plus `contentCurrentSection()`, which returns the enclosing
section for the code block that called it.

`contentMetadata(path)` reads `content:` front-matter by dot path, for example
`contentMetadata("defaultRenderer")`. It does not read arbitrary front-matter
keys; callers that need the whole manifest use `contentDocument().manifest`.

`contentBlock(id)` looks up any content node with an explicit `@id=...` fence
attribute or `{#id}` metadata. `contentData(id)` is a convenience for embedded
structured data blocks; it returns `None` for executable/string/opaque blocks or
for structured blocks that failed to parse.

### Frontend helper API

Frontend lowering is the first user-facing target for this feature. It lives
outside `std/content` so the shared content model can later be used by CLI,
compiler, server, and data use cases without pulling in UI dependencies. The
`runtime/std/ui/content.ssc` surface is:

```scalascript
case class ContentToolkitOptions(
  includeCode: Boolean = false,
  sectionGap: Int = 16,
  blockGap: Int = 8,
  listGap: Int = 4,
  wrapDocumentInCard: Boolean = false,
  wrapTopLevelSectionsInCards: Boolean = false
)

extern def contentToolkitNode(options: ContentToolkitOptions = ContentToolkitOptions()): TkNode

case class ContentRenderOptions(
  includeCode: Boolean = false,
  evaluateInlineExpr: Boolean = true,
  sectionIdsAsAnchors: Boolean = true
)

def contentView(doc: DocumentContent, options: ContentRenderOptions = ContentRenderOptions()): View
def contentViewSection(section: SectionContent, options: ContentRenderOptions = ContentRenderOptions()): View
def contentViewBlock(block: ContentBlock, options: ContentRenderOptions = ContentRenderOptions()): View
```

`contentToolkitNode()` is the preferred composition bridge for Phase 1. It reads
the current parsed `.ssc` document from the content plugin and returns a regular
`TkNode`, so callers can place Markdown content inside `vstack`, `card`,
routers, and themed application shells before `lower(tree, theme)`.

Structured fences marked `@ui=toolkit` are consumed by `contentToolkitNode()`
as declarative toolkit controls. They let authors define the controls in
Markdown/YAML and keep executable code limited to the final bridge call:

````markdown
```yaml @ui=toolkit
signals:
  teamName: "ScalaScript team"
  enabled: false
  applied: false
controls:
  type: card
  children:
    - type: heading
      level: 2
      text: Toolkit controls
    - type: textField
      signal: teamName
      label: Team name
    - type: checkbox
      signal: enabled
      label: Enable toolkit renderer
    - type: button
      signal: applied
      value: true
      label: Apply toolkit
      enabledWhen: enabled
```

```scalascript
val page = lower(contentToolkitNode(), defaultTheme)
```
````

The block schema is intentionally small:

- `signals` is a map from stable signal id to a scalar default (`String`,
  `Boolean`, integer, decimal, or `null`).
- `controls` / `control` is either a control object or a list of control
  objects. Lists lower to `FragmentNode`.
- Supported control `type` values are `vstack`, `hstack`, `fragment`,
  `divider`, `heading`, `text`, `rawText`, `signalText`, `show`, `textField`,
  `checkbox`, `button`, `badge`, and `card`.
- Signal-backed controls reference signals by name through fields such as
  `signal`, `value`, `checked`, or `condition`. `button.enabledWhen` lowers to
  an enabled/disabled `SignalButtonNode` pair guarded by `ShowWhenNode`.

`contentView(contentDocument())` remains the lower-level renderer for callers
that need direct `View` nodes and closer HTML-like Markdown shapes.

The default lowering maps:

| Content IR | `std/ui` shape |
|---|---|
| `SectionContent` | heading node + lowered blocks + children |
| `Paragraph` | paragraph/text nodes |
| `BulletList` / `OrderedList` | list nodes |
| `Link` | link node |
| `Image` | image node with `alt` |
| `Embedded(StructuredData)` | omitted by default unless it is marked `@ui=toolkit` or a component/custom renderer consumes it |
| `Embedded(Executable/StringBlock/Opaque)` | omitted by default; rendered as code/pre when `includeCode = true` |

If a block or section has `component=<name>` metadata, the generic lowering
first asks a component registry for `<name>`. If no renderer is registered, it
falls back to the default Markdown lowering. A missing custom renderer is never
a compile error by itself.

## Behavior

- [x] The first implementation slice renders a Markdown-authored page or screen
      through the existing frontend toolkit without user-written HTML or manual
      UI tree construction.
- [x] The parser preserves Markdown-hosted content as a stable
      `DocumentContent` snapshot without changing existing section scoping or
      code block execution.
- [x] YAML front-matter is exposed as `DocumentContent.manifest: ContentValue`
      while still serving as the existing module manifest.
- [x] Fenced YAML/JSON/TOML structured blocks preserve source and expose parsed
      `ContentValue` data when parsing succeeds and a parser is available.
- [x] Structured `yaml @ui=toolkit` blocks lower directly to interactive
      `std/ui` controls through `contentToolkitNode()`.
- [x] Every fenced code block enters the content tree as an embedded language
      node, even when its execution is handled by a backend or plugin.
- [x] Generated section ids are deterministic: slugify heading text; if a slug
      is repeated, append `-2`, `-3`, etc. Explicit `{#id}` wins and duplicate
      explicit ids are a compile-time diagnostic.
- [x] Heading attributes and `<!-- @meta ... -->` directives lower into
      `attrs: Map[String, ContentValue]` on the targeted node.
- [x] `contentDocument()` is available from every ScalaScript block in the
      interpreter and returns the same immutable parse-time snapshot during one
      module execution.
- [ ] `contentCurrentSection()` returns the code block's enclosing section,
      including metadata and sibling prose/list blocks in that section.
- [ ] Inline `${expr}` in prose is represented as `ContentInline.Expr(source)`
      until an explicit renderer evaluates it. Content introspection itself does
      not execute inline expressions.
- [x] `contentToolkitNode()` renders the current Markdown document to a regular
      toolkit `TkNode`, and `contentView(...)` remains available for direct
      low-level `View` lowering.
- [ ] Interpreter, JS, and JVM backends expose byte-identical textual results
      for the non-frontend `std/content` API.
- [ ] `.sscc` / `.scir` artifacts preserve enough content metadata for linked
      modules and downstream tooling to inspect the document without reparsing
      the original source when source text is unavailable.

## Out of Scope

- Full Markdown-to-HTML compatibility. The goal is a typed content IR, not a
  browser-perfect CommonMark renderer.
- Runtime mutation of the document content. The snapshot is immutable for one
  module execution.
- User-defined Markdown block parsers in the first implementation slice.
- Full semantic parsing for every programming language. Unknown languages keep
  source text plus metadata; plugins may add richer classification later.
- Executing code fences through the content API. Code fences are represented as
  source metadata only; normal code block execution stays in the existing
  section runtime.
- Making `component=<name>` dynamically instantiate arbitrary symbols by name.
  Custom component resolution must be explicit through a registry or helper.
- Replacing front-matter. Front-matter remains the module manifest; content
  metadata complements it.

## Design

### Pipeline

```text
.ssc source
  -> CommonMark AST + YAML front-matter
  -> source-language classification for fenced blocks
  -> existing Module/Section/Content AST
  -> DocumentContent snapshot
  -> std/ui content lowering (first public MVP)
  -> optional std/content runtime value for introspection
  -> backend-specific UI or textual output
```

The content snapshot is built in `lang/core` after the CommonMark parse and
before backend splitting. The snapshot records the host Markdown structure plus
embedded language nodes. It should not live in the interpreter core. The
interpreter, JS codegen, JVM codegen, and artifact writer all consume the same
serialized content representation.

### Embedded language classification

Markdown is the host language. YAML front-matter and fenced code blocks are
embedded languages inside that host. Classification is conservative:

1. Built-in data languages (`yaml`, `yml`, `json`, `toml`) parse into
   `ContentValue`.
2. Built-in ScalaScript executable/string block languages map to
   `EmbeddedKind.Executable` or `EmbeddedKind.StringBlock`.
3. Source-language plugins may classify additional tags and optionally provide
   a structured-data decoder.
4. Unknown tags stay `Opaque` with exact source text preserved.

This keeps the feature open-ended: any reasonable language that can be expressed
as a legitimate Markdown block can participate in introspection without forcing
ScalaScript to understand its full semantics in core.

### AST and IR placement

Add typed content metadata beside existing section content rather than replacing
`ast.Content`:

- `ast.Module` gains an optional `document: Option[DocumentContentDecl]` or an
  equivalent field once source compatibility is handled.
- `ir.NormalizedModule` gains a serialized content snapshot for artifact and
  backend use.
- `Normalize` and `Denormalize` round-trip the snapshot.
- Existing `Content.Prose`, `Content.Import`, and `Content.CodeBlock` behavior
  remains valid.

This avoids overloading the current `Content` enum, which is execution-oriented
and already carries code block parse trees.

### Runtime exposure

`runtime/std/content.ssc` declares externs and pure helper functions. The native
implementation belongs in a `runtime/std/content-plugin` backend plugin, not in
the interpreter core. The plugin reads a `NativeContext` feature slot populated
by the runner/codegen with the module's content snapshot.

For codegen backends:

- JS emits the snapshot as a frozen JSON-like literal and implements
  `contentDocument()` by returning it.
- JVM emits the snapshot as case-class construction or JSON decoded through a
  small runtime codec.
- Interpreter stores the same value in native context state before running the
  first section.

### Inline expression policy

Inline `${expr}` in prose is source, not a value, inside `DocumentContent`.
Renderers can evaluate it only when they have an evaluation context. This keeps
parsing total and prevents content introspection from causing side effects by
merely reading `contentDocument()`.

### Metadata conflict rules

Priority order for section ids:

1. Explicit `{#id}` on the heading.
2. Generated slug from heading text.
3. Synthetic `section-N` only when the heading has no alphanumeric characters.

Priority order for attributes:

1. Heading trailing attributes.
2. Immediately preceding `<!-- @meta ... -->` directive.
3. Front-matter defaults under `content.defaults`.

Later sources override earlier sources for the same key except `class`, which
concatenates classes in source order.

## Decisions

- **Frontend from Markdown is the first public milestone** - chosen because the
  immediate product value is eliminating hand-written markup generation for
  pages and screens. `DocumentContent` remains the supporting shared IR so the
  renderer, metadata API, CLI, compiler, and server use cases do not fork their
  own parsers. Rejected: direct Markdown-to-HTML as the only API, because it
  would bypass the existing frontend toolkit and lose backend-agnostic UI
  semantics.
- **Markdown is the host; embedded languages keep their own dialects** - chosen
  because YAML, JSON, SQL, GraphQL, ScalaScript, and future plugin languages
  each have existing syntax and semantics. Rejected: translating every fenced
  block into a Markdown-only model, because that would erase useful structure or
  force ScalaScript core to parse too many languages.
- **Parse structured data opportunistically** - chosen because YAML/JSON/TOML
  are valuable as metadata/data blocks today. Rejected: requiring every
  embedded language to have a parser before it can enter the content tree.
- **Prefix public functions with `content`** - chosen to avoid collisions with
  existing `doc(...)` / `render(...)` content helpers. Rejected:
  `document()` / `section()` as too likely to conflict with user names.
- **Store inline expressions as source** - chosen to keep introspection pure and
  deterministic. Rejected: eager evaluation during parse or document snapshot
  creation, because it would execute user code while reading metadata.
- **Use explicit metadata extensions sparingly** - chosen because plain Markdown
  remains the authoring default, while ambiguous routing/frontend decisions need
  stable data. Rejected: inferring components from heading text or CSS classes.
- **Keep intrinsics in a std plugin** - chosen to match the project rule that
  new intrinsics live under `runtime/std/<feature>-plugin`. Rejected: adding
  native implementations to interpreter core.

## Implementation Plan

### Phase 0 - Spec, examples, and pending conformance

- Add this spec, global `SPEC.md` planned section, user-guide notes, README
  entries, one example, and a pending conformance fixture.
- No compiler/runtime behavior change in this phase.

### Phase 1 - Markdown-to-frontend MVP (landed 2026-06-04)

- Add the rendering-grade `DocumentContentDecl` / `SectionContentDecl` /
  `ContentBlock` / `ContentInline` / `ContentValue` snapshot needed by the UI
  renderer.
- Convert CommonMark body nodes into the snapshot while preserving source order,
  section hierarchy, ids, attributes, spans, and embedded-language metadata.
- Parse YAML/front-matter and fenced YAML/JSON/TOML into `ContentValue` so the
  renderer and component hooks can consume structured authoring data.
- Add `runtime/std/ui/content.ssc` helpers that lower `DocumentContent` /
  `SectionContent` to the existing `std/ui` toolkit nodes.
- Cover headings, paragraphs, lists, links, images, and default handling for
  embedded executable/string/opaque blocks behind `includeCode`.
- Add one end-to-end frontend smoke where a Markdown-authored page emits through
  an existing frontend backend with no hand-written UI construction code.

### Phase 2 - Full `std/content` introspection API

- Extend `runtime/std/content.ssc` and `runtime/std/content-plugin` beyond the
  Phase 1 `contentDocument()` interpreter API.
- Populate native context state for JS and JVM backends.
- Implement `contentCurrentSection`, `contentSection`,
  `contentBlock`, `contentData`, `contentMetadata`, `contentPlainText`, and
  `contentToMarkdown`.
- Enable the pending conformance test across INT, JS, and JVM.

### Phase 3 - IR and artifact round-trip

- Thread the snapshot through `Normalize`, `Denormalize`, `.scir`, and `.sscc`
  formats with backward-compatible default handling.
- Add round-trip tests for old artifacts without content metadata and new
  artifacts with content metadata.

### Phase 4 - Custom component registry

- Add custom component registry hooks for `component=<name>` metadata.
- Support structured `ContentValue` props from YAML/front-matter and fenced data
  blocks.
- Document the stable boundary between declarative Markdown content and explicit
  custom ScalaScript components.

### Phase 5 - Tables and richer authoring

- Enable a table-capable Markdown extension or implement a narrow table parser.
- Lower `ContentBlock.Table` into the toolkit table node and document the
  capability.
- Evaluate whether paragraph/list block attributes need a lighter syntax than
  the `<!-- @meta ... -->` directive.

## Testing

- Frontend tests for `contentToolkitNode()` toolkit composition and
  `contentView(...)` generic lowering are the Phase 1 test priority, plus an
  e2e smoke for emitted React assets.
- Parser unit tests for the subset required by frontend lowering: headings,
  generated/explicit ids, metadata comments, paragraphs, lists, links, images,
  inline code, inline expression capture as source, and embedded data blocks.
- Parser/unit tests for front-matter and fenced YAML/JSON/TOML data conversion
  to `ContentValue`, including parse failure preserving source.
- Later parser/runtime tests for the full non-frontend introspection API.
- Normalize / Denormalize / artifact compatibility tests.
- Interpreter plugin tests for the `std/content` externs.
- JS and JVM code-shape tests proving the snapshot is embedded once and exposed
  through the same API.
- Conformance test `tests/conformance/content-introspection.ssc` verifies
  identical text output across INT, JS, and JVM after the full `std/content`
  API lands.

## Results

Phase 1 landed the Markdown-to-frontend MVP on 2026-06-04:
parser-side `DocumentContent`, interpreter `contentDocument()`,
`std/ui/content.ssc` lowering, and a React emit smoke. The remaining work is the
full metadata lookup API, JS/JVM exposure, artifact round-trip, custom renderer
registry, tables, and cross-backend conformance.
