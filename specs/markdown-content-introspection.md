# Markdown Content Introspection

Status: planned. This document is the implementation contract for exposing
Markdown body content as typed metadata and renderable frontend input. The
current parser already treats Markdown as syntax; this spec defines the next
layer: code blocks can inspect the non-code document that surrounds them, and
frontend code can render that document without hand-writing markup generation.

## Overview

ScalaScript source should be useful as both executable code and structured
content. Today front-matter is available as module metadata and headings create
scopes, but ordinary prose, lists, links, and other Markdown body nodes are not
exposed through a stable public API. This feature introduces a Document Content
IR and a `std/content` API so code can read the parsed Markdown body as data.
The frontend layer then lowers the same content IR to `std/ui` nodes or backend
agnostic `View` trees.

The intended authoring model is:

1. Write the page, screen, manifest, or product description as Markdown.
2. Attach lightweight metadata where plain Markdown is ambiguous.
3. Use code blocks only for behavior, data fetching, validation, and custom
   renderers.
4. Let `std/content` expose the surrounding document to code and frontend
   helpers.

## Interface

### Markdown authoring surface

The content layer reads the existing Markdown body plus two small metadata
extensions:

````markdown
---
name: pricing-page
frontend: react
content:
  defaultRenderer: std-ui
---

# Pricing {#pricing route=/pricing layout=marketing}

Simple plans for small teams.

<!-- @meta component=PlanList source=plans -->
## Plans

- Starter: $19
- Pro: $49

```scalascript
val doc = contentDocument()
println(doc.title.getOrElse(""))
println(contentSection("plans").get.title)
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

### `std/content.ssc`

The planned public API is deliberately data-first. Names are prefixed with
`content` to avoid collisions with existing `doc(...)` and `render(...)`
helpers.

```scalascript
case class DocumentContent(
  manifest: Map[String, ContentValue],
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
  case Quote(blocks: List[ContentBlock], attrs: Map[String, ContentValue])
  case Image(src: String, alt: String, title: Option[String], attrs: Map[String, ContentValue])
  case CodeFence(lang: String, source: String, attrs: Map[String, ContentValue])
  case RawHtml(source: String, attrs: Map[String, ContentValue])
  case Table(header: List[String], rows: List[List[String]], attrs: Map[String, ContentValue])

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
extern def contentMetadata(path: String): Option[ContentValue]
extern def contentPlainText(block: ContentBlock): String
extern def contentPlainText(section: SectionContent): String
extern def contentToMarkdown(doc: DocumentContent): String
extern def contentToMarkdown(section: SectionContent): String
```

`contentDocument()` returns a parse-time snapshot of the whole module. It is
available from every `scalascript` / `ssc` block. `contentCurrentSection()`
returns the enclosing section for the code block that called it.

`contentMetadata(path)` reads `content:` front-matter by dot path, for example
`contentMetadata("defaultRenderer")`. It does not read arbitrary front-matter
keys; callers that need the whole manifest use `contentDocument().manifest`.

### Frontend helper API

Frontend lowering lives outside `std/content` so introspection can be used by
CLI, compiler, server, and data use cases without pulling in UI dependencies.
The planned `runtime/std/ui/content.ssc` surface is:

```scalascript
case class ContentRenderOptions(
  includeCode: Boolean = false,
  evaluateInlineExpr: Boolean = true,
  sectionIdsAsAnchors: Boolean = true
)

extern def contentView(doc: DocumentContent, options: ContentRenderOptions = ContentRenderOptions()): UiNode
extern def contentView(section: SectionContent, options: ContentRenderOptions = ContentRenderOptions()): UiNode
extern def contentViewBlock(block: ContentBlock, options: ContentRenderOptions = ContentRenderOptions()): UiNode
```

The default lowering maps:

| Content IR | `std/ui` shape |
|---|---|
| `SectionContent` | heading node + lowered blocks + children |
| `Paragraph` | paragraph/text nodes |
| `BulletList` / `OrderedList` | list nodes |
| `Link` | link node |
| `Image` | image node with `alt` |
| `CodeFence` | omitted by default; rendered as code/pre when `includeCode = true` |
| `Table` | table node when table parsing is enabled; otherwise not emitted |

If a block or section has `component=<name>` metadata, the generic lowering
first asks a component registry for `<name>`. If no renderer is registered, it
falls back to the default Markdown lowering. A missing custom renderer is never
a compile error by itself.

## Behavior

- [ ] The parser preserves non-code Markdown body content as a stable
      `DocumentContent` snapshot without changing existing section scoping or
      code block execution.
- [ ] Generated section ids are deterministic: slugify heading text; if a slug
      is repeated, append `-2`, `-3`, etc. Explicit `{#id}` wins and duplicate
      explicit ids are a compile-time diagnostic.
- [ ] Heading attributes and `<!-- @meta ... -->` directives lower into
      `attrs: Map[String, ContentValue]` on the targeted node.
- [ ] `contentDocument()` is available from every ScalaScript block and returns
      the same immutable parse-time snapshot during one module execution.
- [ ] `contentCurrentSection()` returns the code block's enclosing section,
      including metadata and sibling prose/list blocks in that section.
- [ ] Inline `${expr}` in prose is represented as `ContentInline.Expr(source)`
      until an explicit renderer evaluates it. Content introspection itself does
      not execute inline expressions.
- [ ] `contentView(...)` can render a document or section to the existing
      frontend toolkit without user-written HTML generation.
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
  -> existing Module/Section/Content AST
  -> DocumentContent snapshot
  -> std/content runtime value
  -> optional std/ui content lowering
  -> backend-specific UI or textual output
```

The content snapshot is built in `lang/core` after the CommonMark parse and
before backend splitting. It should not live in the interpreter core. The
interpreter, JS codegen, JVM codegen, and artifact writer all consume the same
serialized content representation.

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

- **Expose content as data before UI** - chosen because metadata, CLI, server,
  compiler, and frontend use cases all need the same source of truth. Rejected:
  direct Markdown-to-View lowering as the only API, because it would make
  non-frontend introspection depend on the UI toolkit.
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

### Phase 1 - Core content snapshot

- Add AST data classes for `DocumentContentDecl`, `SectionContentDecl`,
  block/inline content, and `ContentValue`.
- Convert CommonMark body nodes into the snapshot while preserving source order,
  section hierarchy, ids, attributes, spans, and code fence metadata.
- Add parser tests for headings, duplicate ids, metadata comments, lists,
  links, inline code, and inline expression capture.

### Phase 2 - IR and artifact round-trip

- Thread the snapshot through `Normalize`, `Denormalize`, `.scir`, and `.sscc`
  formats with backward-compatible default handling.
- Add round-trip tests for old artifacts without content metadata and new
  artifacts with content metadata.

### Phase 3 - `std/content` runtime API

- Create `runtime/std/content.ssc` and `runtime/std/content-plugin`.
- Populate native context state for interpreter, JS, and JVM backends.
- Implement `contentDocument`, `contentCurrentSection`, `contentSection`,
  `contentMetadata`, `contentPlainText`, and `contentToMarkdown`.
- Enable the pending conformance test across INT, JS, and JVM.

### Phase 4 - Frontend lowering

- Add `runtime/std/ui/content.ssc` helpers that lower `DocumentContent` /
  `SectionContent` to the existing `std/ui` toolkit nodes.
- Add generic renderer coverage for headings, paragraphs, lists, links, images,
  and code fences behind `includeCode`.
- Add custom component registry hooks for `component=<name>` metadata.

### Phase 5 - Tables and richer authoring

- Enable a table-capable Markdown extension or implement a narrow table parser.
- Lower `ContentBlock.Table` into the toolkit table node and document the
  capability.
- Evaluate whether paragraph/list block attributes need a lighter syntax than
  the `<!-- @meta ... -->` directive.

## Testing

- Parser unit tests for content snapshot construction and metadata syntax.
- Normalize / Denormalize / artifact compatibility tests.
- Interpreter plugin tests for the `std/content` externs.
- JS and JVM code-shape tests proving the snapshot is embedded once and exposed
  through the same API.
- Conformance test `tests/conformance/content-introspection.ssc` to verify
  identical text output across INT, JS, and JVM after Phase 3.
- Frontend tests for `contentView(...)` generic lowering, plus one e2e smoke
  for `serve(lower(contentView(contentDocument()), theme), port)`.

## Results

Phase 0 landed the planned public contract, docs, example, and pending
conformance fixture. No runtime/compiler tests are expected to pass for the new
API until Phase 1+ implementation starts.
