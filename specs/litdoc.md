# spec: `std/litdoc` — a resilient, incremental literate-document parser

## Why

ScalaScript already has a document model — `std/content` (`DocumentContent` → `SectionContent` →
`ContentBlock`/`ContentInline`) — and parses its *own* `.ssc` modules into it (`contentDocument()`,
implemented in the runtime/plugin layer). What it does **not** have is a runtime function that takes an
arbitrary markdown **string** and returns a tree: there is no `parse(md): …`. The `busi` project needed
exactly that (it interprets business models written as markdown), so it grew its own parser in-tree. This
module brings that capability into the stdlib so it is shared, not duplicated.

## Why not produce `content` directly

The natural wish is `parseContent(md): DocumentContent` — reuse the existing tree. We tried it and it does
not work today: importing `std/content` into a fresh caller and constructing its enums fails at import time
(`missing argument for parameter 'attrs'` from a transitively-imported `extern def` whose default is
evaluated in the caller's scope). `content`'s constructors are reached through native/extern wiring that an
ordinary `.ssc` caller cannot re-evaluate. So a pure-`.ssc` parser cannot reliably build `content` values.

`litdoc` is therefore **self-contained**: its tree is plain case classes with no externs, so it imports
cleanly into any caller (verified: INT == JS, and `busi` consumes it directly). A native `toContent(doc)`
bridge — mapping `litdoc.Doc` → `content.DocumentContent` on the Scala side, where the extern wiring lives —
can be added later if we want the two models to meet. That is a runtime/plugin change and out of scope here.

## What it recognises

A markdown document is **water** (prose) dotted with **islands** of structure. litdoc splits it into
territories and builds a typed tree; anything unrecognised is `Prose`, never an error.

- `FrontMatter(pairs, span)` — a leading `--- … ---` block of `key: value` lines.
- `Heading(level, text, span)` — `#`…`######`.
- `Quote(text, span)` — `> …`.
- `ListItem(text, span)` — `- …` (the raw item text; what it *means* is the caller's job).
- `Fence(lang, lines, span)` — a ` ``` ` block; `lines` is the raw body, uninterpreted.
- `Prose(text, span)` — everything else (water).

Plus inline laws (`inlinesOf` splits `**bold**`) and `dataOf` (mines phone/price/date tokens from the
text), and `reparse(oldLines, oldDoc, newLines)` for incremental edits.

## Four properties

1. **Structural** — a tree of typed nodes, not a flat token list.
2. **Resilient** — never throws; a malformed island (e.g. an unclosed fence) degrades to a node spanning to
   EOF, optionally with a `Diag`. Water is never an error.
3. **Spanned / incremental** — every node carries a line `Span`; `reparse` reuses the unaffected prefix and
   its result equals a full `parseDoc` of the new text.
4. **Domain-free** — litdoc recognises shapes; the caller reads meaning off them (a `ListItem` with `::` is
   an offer to `busi`; a `Fence` of `lang == scalascript` is executable commands — neither is litdoc's
   concern).

## Conformance

`tests/conformance/litdoc.ssc` exercises every island, the inline + data laws, resilience to an unclosed
fence, and `reparse == parseDoc`. Output is deterministic and verified identical on INT and JS.
