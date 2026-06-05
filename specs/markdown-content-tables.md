# Markdown Content Tables

## Overview

Markdown tables should participate in the same `DocumentContent` snapshot as
paragraphs, lists, images, and fenced data. This slice adds a table content
node for GitHub-Flavored Markdown pipe tables and lowers it through the existing
content/plain-text/Markdown/toolkit paths so Markdown-authored comparison grids,
pricing matrices, and simple data tables can drive frontend output without
hand-written table markup.

## Interface

`ContentBlock` gains a table variant:

```scalascript
case Table(
  headers: List[List[ContentInline]],
  rows: List[List[List[ContentInline]]],
  alignments: List[String],
  attrs: Map[String, ContentValue]
)
```

`alignments` uses one string per column: `"left"`, `"center"`, `"right"`, or
`"default"`. It mirrors the GFM separator row and is metadata for renderers;
runtime lookup and table identity do not depend on alignment.

The `std/content.ssc` public model, parser AST/IR, `.scir`/`.sscc` content
serialization, interpreter values, JS codegen content embedding, and JVM codegen
content embedding all carry the new table node.

`std/ui/content.ssc` lowers `ContentBlock.Table` to:

- low-level `View` tables through ordinary `element("table", ...)` markup, and
- toolkit `TableNode` in `contentToolkitNode()` / selected toolkit regions.

## Behavior

- [x] Parser recognizes GFM pipe tables with a header row, separator row, and
      zero or more body rows.
- [x] Table headers and cells preserve inline Markdown content, including
      emphasis, strong text, inline code, links, and `${expr}` source nodes.
- [x] `<!-- @meta ... -->` before a table attaches attrs to the table node.
- [x] `contentDocument()`, `contentSection(id)`, `contentBlock(id)`, and linked
      namespace helpers expose table nodes on interpreter, JS, and JVM paths.
- [x] `contentPlainText(table)` and enclosing section plain text include table
      headers and rows in a stable readable form.
- [x] `contentToMarkdown(table)` and enclosing document/section rendering emit a
      deterministic pipe table.
- [x] `contentView(...)` renders tables as semantic table markup.
- [x] `contentToolkitNode()` / `contentToolkitBlock(id)` lower tables to the
      existing `TableNode` toolkit node.
- [x] Existing paragraph/list/image/fenced-block behavior remains unchanged.

## Out of scope

- Full Markdown table dialect compatibility beyond the GFM pipe-table extension.
- Rowspan/colspan, captions, footers, sortable dynamic tables, or typed data
  table actions.
- Evaluating inline `${expr}` while rendering table cells. Expression nodes stay
  source-only until a separate explicit renderer evaluates them.
- Inferring table schemas or typed row models from Markdown tables.

## Design

Parser support uses `org.commonmark.ext.gfm.tables.TablesExtension`, matching the
project principle to reuse established Markdown machinery rather than inventing
a parser. The ScalaScript content parser translates CommonMark table nodes into
`ContentBlock.Table`.

The table case stores inline content instead of pre-rendered strings so every
existing renderer can choose the appropriate target: plain text flattens inline
content, `contentToMarkdown` round-trips semantic Markdown, low-level `View`
lowering keeps inline tags, and toolkit lowering maps cells to `TkNode` text.

The first toolkit lowering is intentionally simple: columns use plain-text
header labels, rows use plain-text cell nodes, and sorting is disabled
(`sortCol = null`). Richer interactive tables remain the domain of `DataTable`
and are not implied by Markdown table syntax.

## Decisions

- **Use CommonMark GFM tables** - chosen because table parsing is a solved
  Markdown problem and the project already uses CommonMark. Rejected: a custom
  pipe-line parser, which would immediately need escaping, alignment, and inline
  parsing edge cases.
- **Inline content in cells** - chosen so links/code/emphasis survive in the
  content IR and low-level renderers. Rejected: storing only strings, which
  would make `contentToMarkdown` and `contentView` lossy.
- **Simple toolkit lowering** - chosen to make authored Markdown tables render
  through the existing `TableNode` without conflating them with data-backed
  application tables. Rejected: auto-converting Markdown tables into
  `DataTableNode`, which would invent schema/action semantics the source does
  not declare.

## Results

Implemented on 2026-06-05. Verification:

- `sbt "core/testOnly scalascript.parser.ContentDocumentTest"`
- `sbt "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest"`
- `sbt "backendInterpreter/testOnly scalascript.ContentBackendExposureTest -- -z tables"`
- `sbt "cli/runMain scalascript.cli.ssc run examples/content-tables.ssc"`

Observed coverage: parser table shape and normalize/denormalize round-trip,
interpreter lookup/plain-text/Markdown/toolkit `TableNode`, and generated JS/JVM
table content exposure all pass. A full
`backendInterpreter/testOnly scalascript.ContentBackendExposureTest` run was
attempted after the JS fixture fence was corrected from `scala` to
`scalascript`; all JS cases passed, but the JVM subprocess cases hit unrelated
Scala CLI/Bloop socket timeouts (`Address already in use` / BSP socket timeout)
while other long-running scala-cli jobs were active.
