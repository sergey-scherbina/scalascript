# Markdown Content Data Binding

## Overview

Markdown-authored frontend components need a direct way to consume structured
data that is already present in the same `.ssc` document. This slice connects
metadata such as `data=plans-data` to fenced YAML/JSON/TOML blocks with matching
`@id`, exposes the lookup as `contentData(id)`, feeds the resolved
`ContentValue` into `ContentComponentContext.data` for registered toolkit
components, and adds an explicit `contentBind(value, bindings)` render step for
Markdown inline `${name}` placeholders.

## Interface

Markdown authors bind structured data by giving a fenced structured block a
stable id and referencing that id from section or block metadata:

````markdown
```yaml @id=plans-data
plans:
  - id: starter
    title: Starter
    price: 19
  - id: pro
    title: Pro
    price: 49
```

<!-- @meta component=PlanList data=plans-data -->
## Plans
````

The `std/content.ssc` surface gains the first lookup helper from the broader
metadata API and the explicit binding helper:

```scalascript
extern def contentData(id: String): Option[ContentValue]
extern def contentBind(value: Any, bindings: ContentValue): Any
```

`contentData(id)` searches the current `DocumentContent` snapshot for an
embedded structured data block whose explicit `@id` or block metadata id equals
`id`. It returns the parsed `ContentValue` when the block is structured and
parsed successfully; otherwise it returns `None`.

`contentBind(value, bindings)` accepts a `DocumentContent`, `SectionContent`, or
`ContentBlock` plus a `ContentValue.MapV` binding object. It returns the same
kind of content value with inline `ContentInline.Expr(source)` placeholders
resolved to `ContentInline.Text(...)` when `source` is a simple dot path present
in `bindings`. For example, `${proPrice}` resolves from a top-level
`proPrice:` key, and `${plan.price}` resolves through nested `MapV` values.
Unresolved placeholders and non-path expressions remain as `Expr(source)` so
plain introspection never executes arbitrary ScalaScript code.

The `std/ui/content.ssc` component API keeps the existing
`ContentComponentContext.data: Option[ContentValue]` field and defines its
resolution rules:

1. If the selected section or block has string metadata `data=<id>`, `ctx.data`
   is `contentData(id)`.
2. If the selected block is itself an embedded structured data block and no
   `data=<id>` metadata is present, `ctx.data` is that block's own parsed data.
3. If neither rule produces a parsed structured value, `ctx.data` is `None`.

Example use from a toolkit component:

```scalascript
[contentComponent, contentToolkitSection, contentToolkitOptionsWithComponents](std/ui/content.ssc)
[ContentValue](std/content.ssc)
[vstack](std/ui/layout.ssc)
[heading](std/ui/typography.ssc)
[rawText](std/ui/reactive.ssc)

val planList = contentComponent("PlanList") { ctx =>
  val label = ctx.data match
    case Some(ContentValue.MapV(fields)) => "data keys=" + fields.keys.toList.length.toString
    case Some(_)                         => "data"
    case None                            => "no data"
  vstack(gap = 4)(
    heading(2, "Plans"),
    rawText(label)
  )
}

val options = contentToolkitOptionsWithComponents([planList])
contentToolkitSection("plans", options)
```

## Behavior

- [x] `contentData(id)` returns `Some(ContentValue)` for a parsed YAML/JSON/TOML
      structured data block with matching explicit id.
- [x] `contentData(id)` returns `None` when the id is absent, points at a
      non-structured block, or the structured block has no parsed data.
- [x] Duplicate structured data ids report an interpreter error instead of
      choosing an arbitrary block.
- [x] A registered section component with `data=<id>` receives the referenced
      structured value in `ContentComponentContext.data`.
- [x] A registered block component with `data=<id>` receives the referenced
      structured value in `ContentComponentContext.data`.
- [x] A registered embedded structured block component without `data=<id>`
      keeps receiving that block's own parsed data.
- [x] Missing `data=<id>` references produce `ctx.data = None`; they do not
      prevent component rendering.
- [ ] `contentBind(block, bindings)` replaces scalar `${name}` and
      `${nested.name}` inline placeholders with text before
      `contentPlainText(...)`, `contentToMarkdown(...)`, and content renderers
      consume the block.
- [ ] `contentBind(sectionOrDocument, bindings)` applies the same replacement
      recursively through paragraphs, lists, tables, and child sections.
- [ ] Missing names and non-path expressions are preserved as `${source}` rather
      than evaluated or silently erased.
- [ ] Interpreter, JS, and JVM backends expose matching `contentBind(...)`
      behavior for the low-level `std/content` API.

## Out of Scope

- Typed decoding helpers such as `contentDataAs[T](id)`.
- Path queries inside `ContentValue`.
- Automatically rendering referenced data in the default Markdown renderer.
- Dynamic component or data lookup by Scala symbol name.
- Evaluating arbitrary ScalaScript expressions embedded in Markdown
  placeholders. `contentBind` resolves data paths only.

## Design

The implementation stays inside the existing `runtime/std/content-plugin`
intrinsics. It already owns the native bridge from parser-side
`ast.DocumentContent` to `.ssc` values and the toolkit component callback path.
`contentData(id)` and `ctx.data` both resolve from the same immutable
`DocumentContent` snapshot, so code blocks and component callbacks observe
identical data.

Resolution is deliberately by explicit metadata id, not by heading title or
source order. This keeps binding stable when authors rearrange prose around the
data block.

`contentBind` is a pure structural transform over the public content model. It
does not mutate the document snapshot, does not call the interpreter evaluator,
and does not ask a renderer to special-case every placeholder. Renderers keep
their existing behavior because they receive ordinary `Text` inline nodes after
binding.

## Decisions

- **Use `data=<id>` metadata rather than implicit nearest-data lookup** — chosen
  because explicit references are stable and easy to inspect. Rejected:
  nearest preceding YAML block, because moving prose would silently change
  component input.
- **Return `None` for missing references** — chosen because components can
  render empty/loading/fallback states. Rejected: hard error for every missing
  reference, because authors often iterate on content order and component
  registration independently.
- **Error on duplicate structured data ids** — chosen because duplicate ids make
  a data binding ambiguous. Rejected: first match wins, because it hides author
  mistakes.
- **Bind only simple data paths** — chosen because it is deterministic across
  interpreter, JS, JVM, and native clients. Rejected: evaluating arbitrary
  `${expr}` source from Markdown, because that would make frontend content
  rendering depend on backend-specific code execution.
- **Preserve unresolved placeholders** — chosen because authors can still see
  incomplete content in previews and existing introspection remains
  non-destructive. Rejected: replacing missing values with an empty string,
  because that hides broken content bindings.

## Results

Implemented on 2026-06-05 in the interpreter content plugin. Verified with:

```bash
cd /Users/sergiy/work/my/scalascript/.worktrees/feature/content-data-binding && sbt "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest" "backendInterpreterServer/testOnly scalascript.MarkdownContentFrontendSmokeTest" "cli/testOnly scalascript.cli.MarkdownContentFrontendCliTest"
```

Results: 3 content-plugin tests, 5 Markdown frontend smoke tests, and 2 CLI
Markdown frontend tests passed. Coverage includes direct `contentData(id)`
lookup, missing/non-structured ids, duplicate structured ids, section and block
`data=<id>` component binding, embedded structured block fallback data, and
missing data references.
