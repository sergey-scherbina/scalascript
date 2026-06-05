# Markdown Content Current Section

Status: Landed 2026-06-05 for the interpreter `std/content` API.

## Overview

Markdown-authored frontend pages often put behavior beside the prose, lists,
structured data, and metadata that the behavior should inspect. Existing
helpers can select a section by id, but a code block already has an enclosing
Markdown section. `contentCurrentSection()` exposes that section directly so
authors do not need to repeat stable ids in every colocated code block.

The returned value is the same immutable `SectionContent` snapshot used by
`contentSection(id)`: it includes heading metadata, sibling prose/list/content
blocks in the section, and child sections.

## Interface

`runtime/std/content.ssc` exports:

```scalascript
extern def contentCurrentSection(): SectionContent
```

This slice is interpreter-only. JS/JVM native-context exposure is tracked by
`markdown-content-backend-exposure`.

The helper is available only while executing a parsed ScalaScript code fence
inside a real Markdown heading section. Recognized executable fence tags follow
the existing section runtime rules (`scalascript`, `ssc`, and `scala` via
`Lang.isParseable`).

`contentCurrentSection()` is execution-scoped, not lexical. A function defined
in one section and called from a later code block observes the later code
block's current section. A callback or route invoked after module loading only
has a current section when the invocation itself occurs under a section-scoped
execution context; otherwise the helper reports an interpreter error.

Top-level or headingless executable code has no `SectionContent` because the
parser may execute it through a synthetic section that is not present in the
content snapshot. Since the public return type is `SectionContent` rather than
`Option[SectionContent]`, this is an interpreter error.

## Behavior

- [x] A code block inside `# Heading {#explicit-id}` returns a section whose
      `id` is `explicit-id`, `title` is `Heading`, and `attrs` contain the
      parsed heading attributes.
- [x] A code block inside a nested heading returns the nearest nested
      `SectionContent`, including generated ids when no explicit id is present.
- [x] The returned section includes sibling Markdown prose and list blocks from
      the same section, so `contentPlainText(contentCurrentSection())` can
      summarize the authored region.
- [x] Multiple executable code blocks in the same section return the same
      immutable `SectionContent` snapshot and section id.
- [x] Headingless/top-level executable code reports an interpreter error
      instead of fabricating a synthetic content section.
- [x] The current-section feature-local value is cleared or restored after each
      executable code block, so a later no-section execution cannot reuse a
      stale section.
- [x] A function defined in one section but called from another section returns
      the caller execution section, not the definition section.

## Design

The parser already builds two related trees:

- `ast.Section`, used by the execution-oriented section runtime.
- `ast.DocumentContent.SectionContent`, used by the content snapshot and
  renderer/introspection APIs.

The implementation must use the parser-built `SectionContent` tree as the
source of ids, titles, attrs, blocks, and children. It must not derive ids by
slugifying `ast.Section.heading.text`, because the execution AST can preserve
raw heading attribute text such as `{#id route=/x}` while `SectionContent`
stores the normalized title and explicit id.

At runtime, `SectionRuntime` pairs `ast.Section` nodes with
`SectionContent` nodes by preserved tree order. While executing a parseable code
block, it stores the paired `SectionContent` in a native-context feature-local
slot:

```scala
NativeContextFeatureKeys.ContentCurrentSection
```

The slot is set only around the code block execution and the previous value is
restored afterward. This keeps nested/runtime-triggered executions from leaking
section state across unrelated blocks.

The `runtime/std/content-plugin` intrinsic reads that feature-local slot and
converts the stored `SectionContent` with the same value-construction logic used
by `contentSection(id)`. Missing state is reported as an interpreter error.

## Decisions

- **Execution context wins** - chosen because native calls are evaluated when a
  function body runs, not when the function is defined. Rejected: lexical
  definition section, because callbacks, imported functions, and later calls
  would need closure metadata unrelated to the existing interpreter model.
- **Error outside a real content section** - chosen because the public contract
  returns `SectionContent`, and top-level absence is an authoring/context error.
  Rejected: returning an artificial section, because it would not correspond to
  any Markdown-authored node in `DocumentContent`.
- **Pair section trees by parser order** - chosen because the parser already
  preserves the same hierarchy for execution and content. Rejected: deriving
  lookup ids from raw headings, because raw execution headings can contain
  attribute syntax that is stripped and normalized in `SectionContent`.
- **Feature-local native context** - chosen because current section is
  execution-local state. Rejected: shared native feature state, because that can
  leak across code blocks or async/runtime callbacks.

## Out of Scope

- No JS/JVM backend exposure in this slice.
- No `contentToMarkdown(...)` implementation.
- No `contentCurrentBlock()` or source-location API.
- No artificial top-level `SectionContent`.
- No runtime mutation or reparsing of Markdown content.
- No changes to `contentToolkitNode()`, component registries, or renderer
  selection.

## Verification

Implementation verification added focused interpreter plugin tests for:

- explicit ids and heading attrs,
- nested generated ids,
- sibling prose/list plain text,
- repeated code blocks in the same section,
- headingless/top-level error behavior,
- feature-local clearing/restoration, and
- execution-scoped function calls across sections.

Run:

```bash
cd <worktree> && sbt "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest"
```

## Results

Landed on 2026-06-05 with interpreter plugin coverage. Verified with:

```bash
cd /Users/sergiy/work/my/scalascript/.worktrees/feature/markdown-content-current-section && sbt "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest"
```

Result: 12 content-plugin tests passed.
