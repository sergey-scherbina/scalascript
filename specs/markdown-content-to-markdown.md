# Markdown Content To Markdown

## Overview

`std/content` already exposes the parsed Markdown-hosted content snapshot for
lookup, metadata, plain-text extraction, frontend toolkit rendering, and native
client lowering. This slice adds the reverse rendering helper:
`contentToMarkdown(value)`. It turns the structured `DocumentContent`,
`SectionContent`, or `ContentBlock` value back into stable Markdown text so
code can reuse selected source regions, export generated documentation, build
editable previews, and prepare later `.scir` / `.sscc` round-trip work.

The goal is semantic Markdown regeneration, not exact source preservation. The
content snapshot intentionally stores normalized structure, parsed metadata,
and embedded source text, not byte offsets or original whitespace.

## Interface

Add one exported helper to `runtime/std/content.ssc`:

```scalascript
extern def contentToMarkdown(value: Any): String
```

`value` may be:

- `DocumentContent`
- `SectionContent`
- any current `ContentBlock` variant

Unsupported values report a runtime error with the same shape as
`contentPlainText(value)`:

```text
contentToMarkdown: expected DocumentContent, SectionContent, or ContentBlock, got <value>
```

The helper is part of the low-level `std/content` API. It must be exposed in
the interpreter, generated JavaScript, and generated JVM Scala backends. It is
not a frontend toolkit helper and does not depend on `std/ui`.

## Behavior

- [ ] `contentToMarkdown(contentDocument())` renders a complete Markdown
      document with optional front-matter, top-level blocks, and nested
      sections.
- [ ] `contentToMarkdown(section)` renders the selected section heading,
      section metadata, blocks, and child sections without unrelated siblings.
- [ ] `contentToMarkdown(block)` renders every current `ContentBlock` variant:
      paragraph, bullet list, ordered list, image, and embedded fenced block.
- [ ] Inline rendering preserves text, emphasis, strong emphasis, inline code,
      links with optional titles, and `${expr}` interpolation markers.
- [ ] Structured embedded blocks preserve their original fenced source text and
      language tag. The parsed `data` value is used only when original source is
      unavailable in a future artifact format, not in this slice.
- [ ] Metadata round-trip is explicit and stable: section metadata renders as a
      trailing heading attribute group, and block/fence metadata renders as
      CommonMark-compatible metadata where the current content IR can express
      it.
- [ ] Formatting is deterministic across interpreter, JS, and JVM generated
      output for the same content tree.
- [ ] Exact byte-for-byte source preservation is not required: spacing,
      wrapping, quote style, generated ids, and equivalent Markdown spellings
      may differ from the original source.
- [ ] Unsupported input values fail clearly instead of returning an empty
      string or silently calling `show`.

## Formatting Rules

### Document

If `DocumentContent.manifest` is a non-empty map, render it as YAML
front-matter:

```markdown
---
key: value
---
```

After front-matter, render top-level `DocumentContent.blocks`, then render all
top-level sections in source order. Separate top-level blocks and sections with
a blank line. If the manifest is empty, no front-matter is emitted.

### Sections

Render a section as:

```markdown
## Title {#id key=value flag}
```

Use `section.level` for heading depth, clamped to the Markdown heading range
`1..6`. Always render `#section.id` when `id` is non-empty because
`DocumentContent` does not currently record whether an id was explicit or
generated in the original source. Render remaining `section.attrs` in sorted
key order for deterministic output. Classes render as `.class` tokens when the
stored `class` value is a string.

Then render the section's blocks and children in source order with blank lines
between block-level elements.

### Blocks

Paragraphs render their inline children on one line.

Bullet list items render with `- `. Ordered list items render from their stored
`start` value. Nested block content inside a list item is indented by two
spaces after the first line; exact original loose/tight list spacing is not
preserved.

Images render as:

```markdown
![alt](src "title")
```

The title part is omitted when absent.

Embedded blocks render as fenced Markdown:

````markdown
```lang @id=block-id @key=value
source
```
````

Fence metadata comes from `Embedded.attrs`, sorted by key except that `id`
renders first as `@id=value` for compatibility with existing fenced content
metadata. Other scalar attributes render as `@key=value`. Boolean values render
as `@key=true` / `@key=false`; the current fence parser does not support
standalone boolean flags. The renderer may choose a longer fence delimiter if
the source itself contains a triple-backtick line.

### Inlines

Text is escaped only where needed to avoid accidentally creating Markdown
syntax. Emphasis renders as `*text*`, strong emphasis as `**text**`, inline code
as backticks, links as `[label](href)` or `[label](href "title")`, and
`ContentInline.Expr(source)` as `${source}`.

### Structured Data Values

Front-matter rendering uses `ContentValue` because the manifest is stored only
as data. The YAML renderer supports:

- strings
- booleans
- numbers
- null
- lists
- maps with string keys

Keys render in sorted order for deterministic output. Strings are emitted as
plain scalars only when safe; otherwise they are double-quoted with escapes.

## Out of Scope

- No exact source/whitespace/quote preservation.
- No byte offsets or source-map style links back to the original Markdown.
- No new Markdown parser features such as tables or block quotes.
- No mutation API for editing `DocumentContent`.
- No linked-module `.scir` / `.sscc` artifact round-trip support.
- No automatic reconstruction of structured data from `ContentValue` when an
  embedded block already has source text.
- No frontend/native rendering changes.

## Design

Interpreter implementation should live in `runtime/std/content-plugin`, next
to `contentPlainText`, because `contentToMarkdown` is another `std/content`
intrinsic and new intrinsics belong in std plugins. Generated JS and JVM should
reuse their existing per-module content runtime helpers and add one public
runtime function named `contentToMarkdown`.

The implementation should prefer a small shared algorithm shape in each target
over adding new IR fields. The current IR already contains enough information
for semantic Markdown regeneration: manifest data, section tree, block tree,
inline tree, embedded source text, and attrs.

## Decisions

- **Use `contentToMarkdown(value: Any)`** - chosen to mirror
  `contentPlainText(value)` and avoid overloading friction in intrinsic and
  codegen dispatch. Rejected: separate overloaded
  `contentToMarkdown(DocumentContent)`, `contentToMarkdown(SectionContent)`,
  and `contentToMarkdown(ContentBlock)` signatures, because the public name
  maps to one runtime intrinsic.
- **Render semantic Markdown, not exact source** - chosen because
  `DocumentContent` is a normalized content IR without byte offsets. Rejected:
  source-preserving round-trip, because it requires a separate parser/source
  mapping feature.
- **Always render non-empty section ids** - chosen so selected/serialized
  content keeps stable references. Rejected: trying to infer whether the id was
  explicit, because that information is currently not stored.
- **Preserve embedded source text** - chosen because fenced blocks already store
  exact source. Rejected: regenerating structured YAML/JSON from parsed `data`
  in this slice, because that would discard author formatting and comments.

## Results

Fill in after implementation and verification.
