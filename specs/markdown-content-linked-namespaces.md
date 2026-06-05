# Markdown Content Linked Namespaces

## Overview

Current `std/content` helpers expose only the Markdown content snapshot of the
module that is executing. This slice exposes direct imported modules as a
namespace-indexed content map so one `.ssc` file can reuse Markdown-authored
metadata, copy, structured data, and sections from another `.ssc` file without
re-parsing source in user code.

## Interface

`runtime/std/content.ssc` gains namespace accessors:

```scalascript
extern def contentModules(): Map[String, DocumentContent]
extern def contentModule(namespace: String): Option[DocumentContent]
extern def contentModuleSection(namespace: String, id: String): Option[SectionContent]
extern def contentModuleBlock(namespace: String, id: String): Option[ContentBlock]
extern def contentModuleData(namespace: String, id: String): Option[ContentValue]
extern def contentModuleMetadata(namespace: String, path: String): Option[ContentValue]
```

The namespace for a direct import is:

1. The imported module's `name:` front-matter value, when present and non-empty.
2. Otherwise, the imported path basename without a trailing `.ssc`.

For example:

````markdown
[introCopy](./copy/intro.ssc)
[contentModuleSection](std/content.ssc)
[contentViewSection](std/ui/content.ssc)

```scalascript
val intro = contentModuleSection("intro-copy", "hero").get
val view = contentViewSection(intro)
```
````

`contentModules()` returns the direct imported content modules as a
`Map[String, DocumentContent]`. `contentModule(namespace)` returns `None` when
no direct imported module has that namespace. Namespace lookup reports an error
if more than one direct import resolves to the same namespace; the runtime must
not silently pick one.

## Behavior

- [ ] A module with no direct imports returns an empty `contentModules()` map
      and `None` from `contentModule("missing")`.
- [ ] A direct imported module with `name:` is visible under that exact name.
- [ ] A direct imported module without `name:` is visible under its path stem.
- [ ] `contentModule(namespace)` returns the imported module's full
      `DocumentContent`, including manifest, top-level blocks, sections,
      structured data blocks, and metadata attrs.
- [ ] `contentModuleSection`, `contentModuleBlock`, `contentModuleData`, and
      `contentModuleMetadata` apply the existing current-document lookup
      semantics to the selected imported document.
- [ ] Duplicate direct import namespaces produce a deterministic runtime error
      for namespace lookup.
- [ ] Transitive imports are not exposed to the parent module unless the parent
      imports them directly.
- [ ] Existing current-module helpers (`contentDocument`, `contentSection`,
      `contentBlock`, `contentData`, `contentMetadata`, `contentCurrentSection`,
      `contentPlainText`, and `contentToMarkdown`) keep their current behavior.
- [ ] Interpreter, JS codegen, and JVM codegen expose the same namespace API;
      native clients that run through generated JVM frontends see the same
      data.

## Out of scope

- Runtime mutation of imported content.
- Transitive imported content namespaces.
- Automatic duplicate namespace disambiguation.
- Changing `DocumentContent` fields or artifact body formats.
- Toolkit-specific convenience wrappers such as `contentToolkitModule(...)`;
  callers can pass `contentModule(...).get` to existing `contentView(...)` /
  `contentViewSection(...)` helpers.

## Design

The current module keeps using `NativeContextFeatureKeys.ContentDocument`.
Imported documents use a separate feature key whose value is a duplicate-aware
namespace table, so existing artifact payloads and `DocumentContent` case-class
shape remain stable.

Interpreter import execution already parses and runs direct imported modules in
`SectionRuntime.runImport`. After the child module runs successfully, the parent
records the child `DocumentContent` under the derived namespace. The record is
kept separate from symbol imports: a content namespace exists only for a direct
import that otherwise resolves and runs.

Generated JS and JVM backends already parse imported modules while inlining code
for `Content.Import`. They can collect the same direct imported module
`DocumentContent` snapshots during import generation and embed a namespace map
next to the current-module content runtime.

Lookup helper implementation reuses the existing section/block/data/metadata
functions; the only new operation is selecting an imported `DocumentContent` by
namespace before applying the same lookup.

## Decisions

- **Namespace from module identity, not imported symbol names** - chosen because
  `[A, B](lib.ssc)` imports symbols while `name:` identifies the content
  document. Rejected: using each imported binding as a content namespace, which
  would create surprising aliases for the same document.
- **Direct imports only** - chosen because users can make dependencies explicit
  and avoid exposing large transitive content graphs accidentally. Rejected:
  recursive namespace export, which would need conflict policy and dependency
  provenance UI.
- **Separate namespace map** - chosen to preserve the stable `DocumentContent`
  ABI and artifact formats. Rejected: adding `imports` to `DocumentContent`,
  which would require broader round-trip and compatibility updates.

## Results

Pending implementation and verification.
