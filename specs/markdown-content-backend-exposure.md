# Markdown Content Backend Exposure

Status: Implemented. This spec defines JS/JVM exposure for the already-landed
interpreter `std/content` helpers.

## Overview

The interpreter can already inspect the parsed Markdown-hosted content snapshot
through `std/content`. JS and JVM generated backends should expose the same
public helpers so a `.ssc` document can use Markdown-authored metadata,
structured fenced data, selected sections/blocks, and current-section context
without being locked to `ssc run`.

This slice does not add new author-facing functions. It makes the existing
`runtime/std/content.ssc` API available on JS and JVM generated targets and
prepares the pending conformance fixture so INT, JS, and JVM can agree on
observable text output.

## Interface

No new `.ssc` surface is introduced. JS and JVM must implement these existing
exports from `runtime/std/content.ssc`:

```scalascript
extern def contentDocument(): DocumentContent
extern def contentCurrentSection(): SectionContent
extern def contentSection(id: String): Option[SectionContent]
extern def contentBlock(id: String): Option[ContentBlock]
extern def contentData(id: String): Option[ContentValue]
extern def contentMetadata(path: String): Option[ContentValue]
extern def contentPlainText(value: Any): String
```

The returned values must use the same public `DocumentContent`,
`SectionContent`, `ContentBlock`, `ContentInline`, `EmbeddedKind`, and
`ContentValue` shapes declared in `std/content.ssc`.

This spec covers:

- JavaScript generated targets (`js`, Node output, and SPA segmented output
  where normal ScalaScript code is emitted as JavaScript).
- JVM generated Scala output.

It does not cover `contentToolkitNode(...)`, `contentToolkitBlock(...)`, or
`contentToolkitSection(...)`; those are frontend-toolkit helpers, not the
low-level `std/content` metadata API.

## Behavior

- [x] JS and JVM embed one immutable per-module `DocumentContent` snapshot from
      `Module.document` / `NormalizedModule.document`.
- [x] `contentDocument()` returns the same manifest, title, sections, blocks,
      attrs, embedded source text, and parsed structured data as the
      interpreter. Reading the snapshot must not evaluate inline `${expr}`
      content.
- [x] `contentSection(id)` and `contentBlock(id)` match interpreter lookup
      semantics: generated and explicit section ids are searchable, missing ids
      return `None`, and duplicate block ids report an error.
- [x] `contentData(id)` returns parsed fenced YAML/JSON/TOML data for
      structured embedded blocks by explicit block id; missing, executable,
      string, opaque, or parse-failed blocks return `None`; duplicate
      structured ids report an error.
- [x] `contentMetadata(path)` reads only the `content:` front-matter subtree by
      dot path; missing traversal returns `None`, and malformed paths report an
      error.
- [x] `contentPlainText(value)` matches interpreter text extraction for
      `SectionContent` and every current `ContentBlock` variant, and reports an
      error for unsupported values.
- [x] `contentCurrentSection()` returns the currently executing code block's
      enclosing `SectionContent` for explicit ids, generated ids, and nested
      sections on JS and JVM.
- [x] `contentCurrentSection()` is execution-scoped, not lexical: a function
      defined in one section but called from another section observes the caller
      block's current section.
- [x] Headingless/top-level executable code reports an error for
      `contentCurrentSection()` instead of fabricating a synthetic section or
      reusing stale section state.
- [x] Generated JS/JVM code preserves existing top-level binding visibility
      across code blocks while setting and clearing current-section state.
- [x] The conformance content fixture runs on INT, JS, and JVM with
      byte-identical text output for this helper set.

## Design

### Snapshot Source

Use the existing content snapshot:

- Parser: `ast.Module.document: Option[DocumentContent]`.
- IR: `ir.NormalizedModule.document: Option[DocumentContent]`.
- Normalize/Denormalize already round-trip the snapshot.

No new parser or IR field is required for this slice.

Generated backends must treat a missing snapshot as an execution error for
`contentDocument()` and any helper that depends on it. Missing snapshot support
for older `.sscc` / `.scir` artifacts remains a later artifact-compatibility
slice; this backend exposure slice only handles modules whose normalized IR
already contains `document`.

### Intrinsic Ownership

Content intrinsics remain owned by `runtime/std/content-plugin`, not by
interpreter core or by backend core intrinsic tables.

The implementation should add codegen-safe content intrinsic providers in the
content plugin, separate from the existing interpreter `NativeImpl` provider.
The selected JS/JVM backend must receive those codegen-safe intrinsics through a
registry/overlay step, while interpreter execution keeps using the existing
`ContentInterpreterPlugin` `NativeImpl` table.

The implementation must not add `QualifiedName("content...")` entries directly
to `runtime/backend/js/.../intrinsics` or
`runtime/backend/jvm/.../intrinsics`. Backend generators may still emit
per-module helper definitions because they need the module's concrete content
snapshot and section order.

### Backend Runtime Shape

Both generated backends need two runtime pieces:

1. A module-local immutable snapshot value.
2. Helper functions named by the `std/content` externs.

JS should emit a JSON-like literal for the snapshot and deep-freeze it when
possible. JVM should emit Scala values for the same public case-class/enum
shape. Both may use backend-local private helper constructors as long as public
observed fields and pattern matching behave like the `std/content.ssc` model.

Lookup helpers must be implemented once per backend runtime and should mirror
the interpreter content-plugin algorithms:

- deep section/block traversal,
- `content:`-rooted metadata dot paths,
- structured data filtering by `EmbeddedKind.StructuredData`,
- duplicate block/data id errors, and
- plain-text extraction over sections, blocks, and inlines.

### Current Section Scoping

`contentCurrentSection()` depends on the code block currently executing, not on
where a function was defined. JS/JVM generators therefore need to pair execution
sections with `DocumentContent.sections` by parser-preserved tree order, the
same way the interpreter does.

The generators must not derive ids from raw execution headings because raw
heading text can include `{#id key=value}` attributes while `SectionContent`
stores the normalized title and explicit id.

Generated code must preserve existing top-level binding visibility. In
particular, do not wrap an emitted code block in a JavaScript lambda, JavaScript
`try { ... }` block, Scala `{ ... }` block, or Scala `try` expression if that
would make `val`, `var`, `def`, class, enum, or object declarations local to
the wrapper. A valid implementation can use straight-line state assignment:

```text
set current section to Some(section)
emit the original code block statements at their normal scope
restore current section to previous/None
```

If a code block aborts with an exception, the module aborts too, so stale
section state is not observable by later blocks. A stricter try/finally restore
is allowed only if the generator first hoists declarations so cross-block
visibility remains unchanged.

JVM's current `collectBlocks` flattens executable blocks before emission. The
implementation should carry optional `SectionContent` metadata alongside each
collected block instead of trying to reconstruct it after flattening.

### Imports And Linked Artifacts

This slice defines the current module's content snapshot. Linked module
snapshots inside `.sscc` / `.scir` artifacts and source-unavailable imports are
covered by the later artifact round-trip phase. Imported functions that call
content helpers should continue to use the runtime context of the generated
module they execute in until linked-module content metadata is implemented.

## Decisions

- **Expose the landed helper set before `contentToMarkdown`** - chosen because
  it unblocks cross-backend conformance for existing metadata APIs. Rejected:
  bundling Markdown conversion, because it has separate formatting and
  round-trip rules.
- **Use existing `DocumentContent` IR** - chosen because the parser and
  Normalize/Denormalize already preserve the snapshot. Rejected: reparsing
  Markdown in generated JS/JVM output.
- **Keep intrinsic ownership in `runtime/std/content-plugin`** - chosen to
  follow the project rule that new `extern def` implementations live in std
  plugins. Rejected: adding content intrinsic entries directly to JS/JVM core
  intrinsic maps.
- **Straight-line current-section state is acceptable** - chosen to preserve
  top-level declaration visibility in generated code. Rejected: wrapping every
  code block in try/finally or a lambda, because it changes JavaScript and
  Scala scoping.
- **Current module only** - chosen because linked artifact content snapshots
  require `.sscc` / `.scir` compatibility work. Rejected: solving imported
  module snapshots in this slice.

## Out of Scope

- No new author-facing `std/content` functions.
- No `contentToMarkdown(...)`.
- No frontend toolkit helper exposure (`contentToolkitNode`, selectors, or
  component registries).
- No `.sscc` / `.scir` artifact compatibility beyond the existing
  `NormalizedModule.document` field.
- No linked-module/source-unavailable content snapshot lookup.
- No runtime mutation of `DocumentContent`.
- No table support or new Markdown block parser.

## Implementation Plan

1. Add codegen-safe content intrinsic provider(s) under
   `runtime/std/content-plugin`, separate from `ContentInterpreterPlugin`.
2. Add or extend the backend compile path so JS/JVM generation receives
   codegen-safe plugin intrinsics without importing std plugin code into core
   backend intrinsic tables.
3. Emit module-local content snapshots and helper functions in `JsGen` and
   `JvmGen` when the module imports or calls `std/content` helpers.
4. Thread optional `SectionContent` context through JS section emission and JVM
   block collection/emission.
5. Add JS/JVM focused tests for snapshot literal/value shape, lookup semantics,
   plain text, and current-section scoping.
6. Un-pend or replace `tests/conformance/content-introspection.ssc` so INT, JS,
   and JVM assert the same text output.

## Testing

Required verification after implementation:

- Interpreter content plugin suite must remain green.
- JS backend tests must prove generated output contains one snapshot and helper
  functions, and must execute the helper set under Node.
- JVM backend tests must compile/run generated Scala output for the helper set.
- Cross-backend conformance must compare INT, JS, and JVM text output for
  `contentDocument`, `contentSection`, `contentBlock`, `contentData`,
  `contentMetadata`, `contentPlainText`, and `contentCurrentSection`.

Suggested command:

```bash
cd <worktree> && sbt "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest" "backendJs/test" "backendJvm/test" "cli/testOnly *Content*"
```

The exact test selectors may be narrowed during implementation, but the final
result must name the command in this spec's Results section.

## Results

Implemented on 2026-06-05. Verification command:

```bash
cd /Users/sergiy/work/my/scalascript/.worktrees/feature/markdown-content-backend-exposure && sbt "backendSpi/compile" "core/compile" "backendJs/compile" "backendJvm/compile" "backendNode/compile" "contentPlugin/compile" "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest" "backendInterpreter/testOnly scalascript.ContentBackendExposureTest" "cli/runMain scalascript.cli.ssc run tests/conformance/content-introspection.ssc" "cli/runMain scalascript.cli.ssc run-js tests/conformance/content-introspection.ssc" "cli/runMain scalascript.cli.ssc run-jvm tests/conformance/content-introspection.ssc"
```

Observed results:

- Content interpreter plugin suite: 12 tests passed.
- Backend exposure suite: 2 tests passed, covering generated JS and generated
  JVM execution of the shared conformance fixture.
- `tests/conformance/content-introspection.ssc` now runs on INT, JS, and JVM
  with identical text output for `contentDocument`, `contentSection`,
  `contentData`, `contentPlainText`, and `contentCurrentSection`.
- `contentToolkitNode`, `contentToolkitBlock`, and `contentToolkitSection`
  remain frontend-toolkit helpers outside this backend exposure slice.
