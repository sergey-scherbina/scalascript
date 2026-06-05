# Markdown Content Lookup and Plain Text

Status: Planned for the `markdown-content-lookup-plaintext` slice.

This slice extends the already-landed Markdown content snapshot with three
low-level `std/content` helpers for interpreter code:

- `contentSection(id)` selects a heading section by stable id.
- `contentBlock(id)` selects any explicitly identified content block.
- `contentPlainText(value)` extracts readable text from a selected
  `SectionContent` or `ContentBlock`.

The goal is to let code blocks inspect and reuse Markdown-authored frontend
content without forcing authors to render the whole document or manually rebuild
the same prose/list/data shape in code.

## Public API

`runtime/std/content.ssc` exports:

```scalascript
extern def contentSection(id: String): Option[SectionContent]
extern def contentBlock(id: String): Option[ContentBlock]
extern def contentPlainText(value: Any): String
```

`contentPlainText` intentionally takes `Any` instead of overloaded
`ContentBlock` / `SectionContent` signatures because the plugin intrinsic table
dispatches by qualified name. Runtime validation keeps the supported surface
explicit.

## Lookup Semantics

`contentSection(id)` searches the whole document section tree, including nested
children. It accepts generated heading ids and explicit CommonMark-style ids:

```markdown
## Plans {#pricing-plans}
```

Results:

- no matching section: `None`
- exactly one matching section: `Some(section)`
- more than one matching section: interpreter error

Duplicate explicit section ids should normally be rejected by parsing or id
canonicalization before runtime. The interpreter duplicate check is still
required as a defensive guard for malformed or plugin-created snapshots.

`contentBlock(id)` searches all document blocks, including blocks nested inside
list items and section children. A block id comes from block metadata:

````markdown
<!-- @meta id=hero-copy -->
Welcome to ScalaScript.

```yaml @id=plans-data
plans:
  - id: starter
```
````

Results:

- no matching block: `None`
- exactly one matching block: `Some(block)`
- more than one matching block: interpreter error

Unlike `contentToolkitBlock(id)`, the low-level lookup helper does not throw on
missing ids. Absence is a normal metadata query result and is represented as
`None`.

## Plain Text Semantics

`contentPlainText(value)` accepts:

- `SectionContent`
- `ContentBlock.Paragraph`
- `ContentBlock.BulletList`
- `ContentBlock.OrderedList`
- `ContentBlock.Image`
- `ContentBlock.Embedded`

Any other value is an interpreter error.

Section text is assembled from the section title, direct blocks, and child
sections in source order. Empty fragments are skipped. Fragments are joined with
newlines so callers can print, index, search, or preview the content without
renderer-specific layout nodes.

Block text follows the same readable-text rules already used by toolkit
fallback rendering:

| Input | Plain text |
|---|---|
| Paragraph | inline text concatenation |
| Bullet list | item text joined as separate lines prefixed with `- ` |
| Ordered list | item text joined as separate lines prefixed with `N. ` |
| Image | `alt` when non-empty, otherwise `src` |
| Embedded | `source` when `lang` is empty, otherwise `lang: source` |

Inline text keeps semantic hints where they are useful in logs/search:

| Inline | Plain text |
|---|---|
| Text | raw value |
| Emphasis / Strong | child text |
| Code | value wrapped in backticks |
| Link | label plus ` (href)` |
| Expr | `${source}` |

## Example

````scalascript
---
name: content-lookup-demo
---

# Pricing

Intro paragraph.

## Plans {#plans}

<!-- @meta id=hero-copy -->
Simple plans for **small teams**.

- Starter
- Pro

```yaml @id=plans-data
plans:
  - id: starter
  - id: pro
```

[contentSection, contentBlock, contentPlainText, contentData](std/content.ssc)

```scala
val plans = contentSection("plans").get
val hero = contentBlock("hero-copy").get

println(contentPlainText(plans))
println(contentPlainText(hero))
println(contentData("plans-data").isDefined.toString)
```
````

## Non-Goals

- No `contentCurrentSection()` in this slice.
- No `contentMetadata(path)` or `contentToMarkdown(...)` in this slice.
- No JS/JVM native context exposure in this slice.
- No table-specific plain-text behavior until Markdown table support exists in
  the content IR.
- No renderer execution, inline expression evaluation, or frontend node
  lowering in `std/content`; those remain explicit renderer/toolkit concerns.

## Behavior Checklist

- [ ] `contentSection(id)` returns `Some(SectionContent)` for generated and
      explicit section ids.
- [ ] `contentSection(id)` returns `None` when absent.
- [ ] `contentSection(id)` reports a duplicate-id interpreter error when the
      snapshot contains duplicate section ids.
- [ ] `contentBlock(id)` returns `Some(ContentBlock)` for explicitly identified
      paragraphs, lists, images, and fenced embedded blocks.
- [ ] `contentBlock(id)` returns `None` when absent.
- [ ] `contentBlock(id)` reports a duplicate-id interpreter error when the
      snapshot contains duplicate block ids.
- [ ] `contentPlainText(section)` includes section title, direct blocks, and
      child sections in source order.
- [ ] `contentPlainText(block)` extracts readable text for paragraph, list,
      image, and embedded block values.
- [ ] `contentPlainText(value)` reports an interpreter error for unsupported
      values.

## Verification Plan

- Add focused interpreter plugin tests in
  `runtime/std/content-plugin/src/test/scala/scalascript/compiler/plugin/content/ContentPluginInterpreterTest.scala`.
- Update the public `runtime/std/content.ssc` exports.
- Update user-facing docs and the Markdown content spec to show the new helpers
  as landed interpreter API while keeping JS/JVM and `contentCurrentSection()`
  marked as future work.
- Run:

```bash
cd <worktree> && sbt "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest"
```
