# Markdown Content Metadata

Status: Landed 2026-06-05 for the interpreter `std/content` API.

This slice adds the interpreter `std/content` helper `contentMetadata(path)` so
code blocks can read structured `content:` front-matter without unpacking the
whole `DocumentContent.manifest` tree by hand.

## Overview

Markdown-authored frontend pages already use front-matter for module metadata
and content-rendering defaults:

```yaml
---
name: pricing-page
frontend: react
content:
  defaultRenderer: toolkit
  theme:
    density: compact
  flags:
    showBeta: true
---
```

`contentMetadata(path)` reads only the `content:` subdocument. This keeps the
helper focused on content/rendering metadata and avoids becoming a general
front-matter accessor. Callers that need arbitrary manifest keys continue to
use `contentDocument().manifest`.

## Interface

`runtime/std/content.ssc` exports:

```scalascript
extern def contentMetadata(path: String): Option[ContentValue]
```

The helper is interpreter-only in this slice. JS/JVM native-context exposure is
tracked separately by `markdown-content-backend-exposure`.

## Behavior

- [x] `contentMetadata("defaultRenderer")` reads
      `frontMatter.content.defaultRenderer` and returns `Some(ContentValue.Str)`.
- [x] Dot paths traverse nested `ContentValue.MapV` values, for example
      `contentMetadata("theme.density")`.
- [x] Scalar, boolean, numeric, list, map, and null metadata values preserve the
      existing `ContentValue` shape returned by `contentDocument().manifest`.
- [x] Missing `content:` front-matter returns `None`.
- [x] Missing path segments return `None`.
- [x] Traversing through a non-map value returns `None`.
- [x] Empty paths, leading/trailing dots, repeated dots, and whitespace-only
      paths report an interpreter error.
- [x] The helper is available only while running a parsed `.ssc` module, with
      the same availability rule as `contentDocument()`.

## Design

Lookup starts from `DocumentContent.manifest`. If the manifest root is not a
map, or if it has no `content` key, the result is `None`.

The `path` grammar is intentionally narrow:

```text
segment ("." segment)*
segment = non-empty string without "."
```

Segments are matched literally and case-sensitively. List indexing is out of
scope; callers can retrieve a list value and inspect it themselves once typed
content optics exist.

## Decisions

- **Scope to `content:` only** — chosen because this helper is content-layer
  metadata, not a general module manifest API. Rejected: arbitrary front-matter
  dot paths, because that makes `contentMetadata("name")` ambiguous with module
  manifest concerns already available through `contentDocument().manifest`.
- **Malformed paths are errors** — chosen because `""`, `.foo`, and `foo..bar`
  are caller mistakes. Rejected: treating malformed paths as `None`, because it
  hides bugs while editing code.
- **Missing traversal is `None`** — chosen because optional metadata is normal
  during Markdown authoring. Rejected: throwing on missing keys, because that
  forces every preview/demo to fully populate optional metadata.

## Out of Scope

- No arbitrary front-matter access outside `content:`.
- No list indexing or typed path optics.
- No JS/JVM backend exposure in this slice.
- No `contentCurrentSection()` or section-local metadata lookup.
- No metadata mutation.

## Verification

- Added focused interpreter plugin tests for existing, nested, missing, non-map,
  and malformed paths.
- Updated `runtime/std/content.ssc` exports.
- Updated user-facing docs and `examples/content-introspection.ssc` to show
  `contentMetadata("defaultRenderer")`.
- Verified with:

```bash
cd <worktree> && sbt "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest"
```

## Results

Landed on 2026-06-05 with focused interpreter plugin coverage. Verified with:

```bash
cd /Users/sergiy/work/my/scalascript/.worktrees/feature/markdown-content-metadata && sbt "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest"
```

Result: 9 content-plugin tests passed.
