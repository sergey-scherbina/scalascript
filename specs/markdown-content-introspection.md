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
truth. Phase 1 shipped the stable model plus `contentDocument()` for the
interpreter; follow-up interpreter slices added `contentData(id)`,
`contentSection(id)`, `contentBlock(id)`, `contentMetadata(path)`, and
`contentPlainText(value)`, and `contentCurrentSection()`. The same low-level
helper set now runs on generated JS and JVM backends, `contentToMarkdown`
reverse rendering landed as the Markdown conversion helper, and current-module
`.scir` / `.sscc` artifact round-trip preserves the content snapshot.
Linked-module content namespace support landed 2026-06-05, specified in
[`specs/markdown-content-linked-namespaces.md`](markdown-content-linked-namespaces.md).
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
  case Table(headers: List[List[ContentInline]],
             rows: List[List[List[ContentInline]]],
             alignments: List[String],
             attrs: Map[String, ContentValue])
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
extern def contentPlainText(value: Any): String
extern def contentToMarkdown(value: Any): String
extern def contentModules(): Map[String, DocumentContent]
extern def contentModule(namespace: String): Option[DocumentContent]
extern def contentModuleSection(namespace: String, id: String): Option[SectionContent]
extern def contentModuleBlock(namespace: String, id: String): Option[ContentBlock]
extern def contentModuleData(namespace: String, id: String): Option[ContentValue]
extern def contentModuleMetadata(namespace: String, path: String): Option[ContentValue]
```

`contentDocument()` returns a parse-time snapshot of the whole module. It is
available from every `scalascript` / `ssc` block in the interpreter.
`contentCurrentSection()` returns the enclosing section for the currently
executing code block when it runs inside a real Markdown heading section.
JS/JVM exposure for the same helpers is a later Phase 2 slice.

`contentMetadata(path)` reads `content:` front-matter by dot path, for example
`contentMetadata("defaultRenderer")`. It does not read arbitrary front-matter
keys; callers that need the whole manifest use `contentDocument().manifest`.

`contentSection(id)` looks up generated or explicit heading ids.
`contentBlock(id)` looks up content blocks with an explicit `@id=...` fence
attribute or `<!-- @meta id=... -->` block metadata. `contentData(id)` is a
convenience for embedded structured data blocks; it returns `None` for
executable/string/opaque blocks or for structured blocks that failed to parse.

`contentPlainText(value)` accepts a `SectionContent` or any `ContentBlock`
variant and returns readable text for logging, indexing, search, and component
previews. Unsupported values report an interpreter error.

`contentToMarkdown(value)` accepts a `DocumentContent`, `SectionContent`, or any
current `ContentBlock` variant and returns deterministic semantic Markdown.
It preserves section/block metadata and embedded fenced source text, but does
not promise byte-for-byte source whitespace preservation.

`contentModules()` and `contentModule(namespace)` expose direct imported module
snapshots by stable namespace. The namespace is the imported module's `name:`
front-matter value or its path stem when `name:` is absent. The namespace-scoped
lookup helpers mirror current-module section, block, data, and metadata lookup.
Helper imports of `std/content.ssc` and `std/ui/content.ssc` are omitted from
the namespace table.

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
  wrapTopLevelSectionsInCards: Boolean = false,
  components: List[ContentToolkitComponent] = [],
  bindings: ContentValue = ContentValue.MapV(Map())
)

case class ContentComponentContext(
  name: String,
  kind: String,
  id: String,
  title: Option[String],
  attrs: Map[String, ContentValue],
  section: Option[SectionContent],
  block: Option[ContentBlock],
  data: Option[ContentValue]
)

case class ContentToolkitComponent(
  name: String,
  render: ContentComponentContext => TkNode
)

def contentComponent(name: String)(render: ContentComponentContext => TkNode): ContentToolkitComponent

def contentToolkitOptionsWithBindings(
  bindings: ContentValue,
  components: List[ContentToolkitComponent] = [],
  includeCode: Boolean = false,
  sectionGap: Int = 16,
  blockGap: Int = 8,
  listGap: Int = 4,
  wrapDocumentInCard: Boolean = false,
  wrapTopLevelSectionsInCards: Boolean = false
): ContentToolkitOptions

def contentToolkitOptionsWithComponents(
  components: List[ContentToolkitComponent],
  includeCode: Boolean = false,
  sectionGap: Int = 16,
  blockGap: Int = 8,
  listGap: Int = 4,
  wrapDocumentInCard: Boolean = false,
  wrapTopLevelSectionsInCards: Boolean = false,
  bindings: ContentValue = ContentValue.MapV(Map())
): ContentToolkitOptions

extern def contentToolkitNode(options: ContentToolkitOptions = ContentToolkitOptions()): TkNode
extern def contentToolkitBlock(id: String,
                               options: ContentToolkitOptions = ContentToolkitOptions()): TkNode
extern def contentToolkitSection(id: String,
                                 options: ContentToolkitOptions = ContentToolkitOptions()): TkNode

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

`contentToolkitBlock(id)` and `contentToolkitSection(id)` are the explicit
selection helpers for pages that define multiple independent Markdown-authored
regions. `contentToolkitBlock` searches all document blocks, including blocks
inside sections and list items, by explicit block metadata id such as
`@id=filters`; it renders exactly that block through the same lowering rules as
`contentToolkitNode()`. `contentToolkitSection` searches the section tree by the
stable heading id, including generated ids and explicit `{#id}` values, and
renders that section as a standalone toolkit subtree. A missing id is an
interpreter error. Duplicate block ids are also an interpreter error; section id
duplicates are rejected by the parser when explicit ids collide.

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
serve(page, 8099)
```
````

When a document contains more than one declarative control tree, give each
fenced block an id and select the parts to compose:

````markdown
```yaml @id=filters @ui=toolkit
signals:
  search: ""
controls:
  type: textField
  signal: search
  label: Search
```

```yaml @id=actions @ui=toolkit
signals:
  submitted: false
controls:
  type: button
  signal: submitted
  value: true
  label: Apply
```

[contentToolkitBlock](std/ui/content.ssc)
[vstack](std/ui/layout.ssc)
[lower](std/ui/lower.ssc)
[defaultTheme](std/ui/theme.ssc)
[serve](std/ui/primitives.ssc)

```scalascript
val page = lower(
  vstack(gap = 16)(
    contentToolkitBlock("filters"),
    contentToolkitBlock("actions")
  ),
  defaultTheme
)
serve(page, 8099)
```
````

For simple controls, the same toolkit bridge also consumes ordinary Markdown
links with a `toolkit:` destination. This keeps the source as plain Markdown
instead of a structured YAML fence:

````markdown
<!-- @meta id=markdown-controls -->
- [Team name](toolkit:textField?signal=teamName&value=ScalaScript%20team)
- [Enable preview](toolkit:checkbox?signal=enabled&value=false)
- [Apply](toolkit:button?signal=applied&value=true&enabledWhen=enabled)
- [Team name](toolkit:signalText?signal=teamName)

```scalascript
val page = lower(contentToolkitBlock("markdown-controls"), defaultTheme)
serve(page, 8099)
```
````

Supported controls and behavior are specified in
[`markdown-toolkit-links.md`](markdown-toolkit-links.md). YAML remains the
preferred format for nested layout trees and larger forms.

For live demos and phone/tablet previews, `serve(page, port)` is the preferred
final bridge because the `.ssc` file is self-contained: it builds the View and
starts the preview server in one run. `emit(page, outDir)` remains the static
artifact path when callers need `index.html` + `app.js` for packaging or a
separate static host.

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

The Phase 1 component registry is explicit and callback based: callers pass
`ContentToolkitComponent` entries through `ContentToolkitOptions.components`.
The renderer receives a `ContentComponentContext` with the selected block or
section plus metadata. `kind` is `"section"` or `"block"`. `id` is the stable
section id or explicit block id when present. `data` is populated for embedded
structured data blocks and is `None` otherwise. The callback returns the full
replacement `TkNode`; document/card wrapping options apply only to default
lowering, not to custom component output.

````markdown
<!-- @meta component=PlanList source=plans -->
## Plans

- Starter: $19
- Pro: $49

[contentComponent, contentToolkitSection](std/ui/content.ssc)
[vstack](std/ui/layout.ssc)
[heading](std/ui/typography.ssc)
[rawText](std/ui/reactive.ssc)
[lower](std/ui/lower.ssc)
[defaultTheme](std/ui/theme.ssc)

```scalascript
val planList = contentComponent("PlanList") { ctx =>
  vstack(gap = 4)(
    heading(2, "Plans from component"),
    rawText("source=" + ctx.attrs("source").toString)
  )
}

val page = lower(
  contentToolkitSection(
    "plans",
    contentToolkitOptionsWithComponents([planList])
  ),
  defaultTheme
)
```
````

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
- [x] `contentToolkitBlock(id)` and `contentToolkitSection(id)` select exactly
      one Markdown-authored block or section by stable id so one document can
      contain multiple independent frontend regions.
- [x] `component=<name>` metadata uses an explicit
      `ContentToolkitOptions.components` registry to replace default toolkit
      lowering for matching blocks or sections, with fallback when no renderer
      is registered.
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
- [x] `contentData(id)` is available from interpreter code and returns parsed
      fenced YAML/JSON/TOML `ContentValue` data by explicit block id.
- [x] `contentSection(id)` and `contentBlock(id)` are available from
      interpreter code and return `None` for missing metadata lookups.
- [x] `contentMetadata(path)` is available from interpreter code and reads
      `content:` front-matter metadata by dot path.
- [x] `contentPlainText(value)` is available from interpreter code for
      `SectionContent` and `ContentBlock` values.
- [x] `contentCurrentSection()` returns the code block's enclosing section,
      including metadata and sibling prose/list blocks in that section.
- [x] Inline `${expr}` in prose is represented as `ContentInline.Expr(source)`
      until explicit `contentBind(value, bindings)` data-path binding resolves
      it. Content introspection itself does not execute inline expressions.
- [x] `contentToolkitNode()` renders the current Markdown document to a regular
      toolkit `TkNode`, and `contentView(...)` remains available for direct
      low-level `View` lowering.
- [x] The runnable content-introspection demo can use `serve(page, port)` as
      the final bridge for direct LAN/mobile preview; `emit(page, outDir)`
      remains available for static artifact generation.
- [x] Interpreter, JS, and JVM backends expose byte-identical textual results
      for the non-frontend `std/content` API.
- [x] `.sscc` / `.scir` artifacts preserve current-module content metadata so
      artifact-backed runs and downstream tooling can inspect the document
      without reparsing the original source when source text is unavailable.

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
- Populate native context state for JS and JVM backends. Focused plan:
  [`specs/markdown-content-backend-exposure.md`](markdown-content-backend-exposure.md).
- Extend the already-landed interpreter `contentData(id)`,
  `contentSection(id)`, `contentBlock(id)`, `contentMetadata(path)`,
  `contentPlainText(value)`, `contentCurrentSection()`, and
  `contentToMarkdown(value)` helpers to JS and JVM native context exposure.
- Enable the pending conformance test across INT, JS, and JVM.

### Phase 3 - IR and artifact round-trip

- Thread the snapshot through `Normalize`, `Denormalize`, `.scir`, and `.sscc`
  formats with backward-compatible default handling. Current-module artifact
  round-trip landed on 2026-06-05; linked-module content namespace support is
  separate and keeps imported snapshots outside the `DocumentContent` payload.
- Add round-trip tests for old artifacts without content metadata and new
  artifacts with content metadata. Covered by
  [`specs/markdown-content-artifact-roundtrip.md`](markdown-content-artifact-roundtrip.md).

### Phase 4 - Custom component registry (started 2026-06-05)

- Add custom component registry hooks for `component=<name>` metadata.
- Support structured `ContentValue` props from YAML/front-matter and fenced data
  blocks. `data=<id>` bindings from sections/blocks to fenced structured data
  landed for interpreter toolkit components on 2026-06-05.
- Document the stable boundary between declarative Markdown content and explicit
  custom ScalaScript components.

### Phase 5 - Tables and richer authoring

- CommonMark GFM pipe tables landed on 2026-06-05 as `ContentBlock.Table`
  with inline header/cell content, column alignments, and preceding metadata
  directive attrs.
- `ContentBlock.Table` lowers to toolkit `TableNode`, low-level
  `contentView(...)` semantic table markup, stable plain text, and
  deterministic `contentToMarkdown(...)` pipe tables across interpreter, JS,
  and JVM paths.
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
`std/ui/content.ssc` lowering, and a React emit smoke. Later slices landed the
metadata lookup API, JS/JVM exposure, native-client parity, reverse Markdown
rendering, and current-module `.scir` / `.sscc` artifact round-trip. Remaining
work is linked-module content namespace support, tables, and broader
cross-backend conformance.

The selector slice landed on 2026-06-05: `contentToolkitBlock(id)` renders one
explicitly identified block such as `yaml @id=... @ui=toolkit`, and
`contentToolkitSection(id)` renders one heading section by stable id. Missing
block ids and duplicate block ids report interpreter errors. Verified with:
`cd /Users/sergiy/work/my/scalascript/.worktrees/feature/content-toolkit-selectors && sbt "backendInterpreterServer/testOnly scalascript.MarkdownContentFrontendSmokeTest" "cli/testOnly scalascript.cli.MarkdownContentFrontendCliTest"`
(3 interpreter-server tests + 2 CLI tests passed).

The component registry slice landed on 2026-06-05:
`contentComponent(name)(render)` defines an explicit toolkit renderer for
Markdown `component=<name>` metadata, and
`contentToolkitOptionsWithComponents([...])` passes the registry to
`contentToolkitNode`, `contentToolkitBlock`, or `contentToolkitSection`.
Registered renderers replace default lowering for matching blocks or sections;
missing registry entries fall back to default Markdown lowering. Verified with:
`cd /Users/sergiy/work/my/scalascript/.worktrees/feature/content-component-registry && sbt "backendInterpreterServer/testOnly scalascript.MarkdownContentFrontendSmokeTest" "cli/testOnly scalascript.cli.MarkdownContentFrontendCliTest"`
(4 interpreter-server tests + 2 CLI tests passed).

The data-binding slice landed on 2026-06-05: `contentData(id)` exposes parsed
structured fenced data by explicit id in interpreter code, and `data=<id>`
metadata on registered toolkit component sections/blocks resolves
`ContentComponentContext.data` from the same snapshot. Missing references
produce `None`; duplicate structured data ids report an interpreter error.
Verified with:
`cd /Users/sergiy/work/my/scalascript/.worktrees/feature/content-data-binding && sbt "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest" "backendInterpreterServer/testOnly scalascript.MarkdownContentFrontendSmokeTest" "cli/testOnly scalascript.cli.MarkdownContentFrontendCliTest"`
(3 content-plugin tests + 5 interpreter-server frontend tests + 2 CLI tests passed).

The inline content-binding extension landed on 2026-06-05:
`contentBind(value, bindings)` resolves simple Markdown `${name}` and
`${nested.name}` placeholders from `ContentValue.MapV` data without executing
arbitrary ScalaScript expressions. `ContentToolkitOptions.bindings` and
`contentToolkitOptionsWithBindings(data)` apply the same transform before
toolkit lowering. Verified with:
`cd /Users/sergiy/work/my/scalascript/.worktrees/feature/content-table-values && sbt "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest" "backendInterpreter/testOnly scalascript.ContentBackendExposureTest -- -z tables" "cli/runMain scalascript.cli.ssc run examples/content-tables.ssc"`
(19 content-plugin tests + 2 JS/JVM table exposure tests passed; CLI example
prints raw and bound table output).

The Markdown toolkit links slice landed on 2026-06-05:
ordinary Markdown links with `toolkit:` destinations now lower to simple
toolkit controls (`textField`, `checkbox`, `button`, `signalText`, `badge`,
`divider`) without a YAML control fence. The import classifier keeps these links
as content, and `contentToolkitNode`, `contentToolkitBlock`, and
`contentToolkitSection` allocate shared reactive signals for selected Markdown
regions. Verified with:
`cd /Users/sergiy/work/my/scalascript/.worktrees/feature/markdown-toolkit-markup-example && sbt "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest" "backendInterpreterServer/testOnly scalascript.MarkdownContentFrontendSmokeTest" "backendInterpreter/testOnly scalascript.ContentNativeClientParityTest" "backendJvm/Compile/compile"`
(20 content-plugin tests + 6 frontend smoke tests + 3 native parity tests
passed; JVM backend compiled). The live example
`examples/markdown-toolkit-links.ssc` served on port 8099 and emitted real
input/checkbox/button controls from Markdown links.

The lookup/plain-text slice landed on 2026-06-05:
`contentSection(id)` and `contentBlock(id)` expose interpreter `Option`
lookups for Markdown-authored regions, while `contentPlainText(value)` extracts
readable text from returned `SectionContent` and `ContentBlock` values. Missing
lookups return `None`; duplicate block ids and unsupported plain-text inputs
report interpreter errors. Verified with:
`cd /Users/sergiy/work/my/scalascript/.worktrees/feature/content-lookup-plaintext && sbt "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest"`
(6 content-plugin tests passed).

The metadata lookup slice landed on 2026-06-05:
`contentMetadata(path)` exposes interpreter `Option` lookup for `content:`
front-matter metadata by dot path. Missing `content:`, missing segments, and
non-map traversal return `None`; malformed paths report an interpreter error.
Verified with:
`cd /Users/sergiy/work/my/scalascript/.worktrees/feature/markdown-content-metadata && sbt "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest"`
(9 content-plugin tests passed).

The current-section slice landed on 2026-06-05:
`contentCurrentSection()` exposes the currently executing code block's
`SectionContent` in the interpreter. It returns explicit or generated section
ids, includes heading attrs and sibling prose/list blocks, uses execution-time
caller context for functions, and reports an interpreter error for
headingless/top-level code rather than leaking a stale section. Verified with:
`cd /Users/sergiy/work/my/scalascript/.worktrees/feature/markdown-content-current-section && sbt "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest"`
(12 content-plugin tests passed).

The backend exposure slice landed on 2026-06-05: the low-level `std/content`
helpers `contentDocument`, `contentCurrentSection`, `contentSection`,
`contentBlock`, `contentData`, `contentMetadata`, and `contentPlainText` are
available on interpreter, generated JS, and generated JVM paths. Generated
backends embed the module `DocumentContent` snapshot, preserve current-section
execution context without wrapping top-level declarations, and keep frontend
toolkit helpers out of the low-level metadata API. Verified with:
`cd /Users/sergiy/work/my/scalascript/.worktrees/feature/markdown-content-backend-exposure && sbt "backendSpi/compile" "core/compile" "backendJs/compile" "backendJvm/compile" "backendNode/compile" "contentPlugin/compile" "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest" "backendInterpreter/testOnly scalascript.ContentBackendExposureTest" "cli/runMain scalascript.cli.ssc run tests/conformance/content-introspection.ssc" "cli/runMain scalascript.cli.ssc run-js tests/conformance/content-introspection.ssc" "cli/runMain scalascript.cli.ssc run-jvm tests/conformance/content-introspection.ssc"`
(12 content-plugin tests + 2 backend exposure tests passed; conformance fixture
matched across INT, JS, and JVM).

The Markdown conversion slice landed on 2026-06-05:
`contentToMarkdown(value)` renders `DocumentContent`, `SectionContent`, and
`ContentBlock` values back to deterministic semantic Markdown on interpreter,
generated JS, and generated JVM paths. It preserves embedded fenced source text
and renders attrs through heading groups, metadata directives, or fenced
`@key=value` attrs while explicitly avoiding byte-for-byte source preservation.
Verified with:
`cd /Users/sergiy/work/my/scalascript/.worktrees/feature/markdown-content-to-markdown && sbt "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest" "backendInterpreter/testOnly scalascript.ContentBackendExposureTest" "contentPlugin/compile" "backendJs/compile" "backendJvm/compile"`
(13 content-plugin tests + 4 backend exposure tests passed, plus compile
targets).
