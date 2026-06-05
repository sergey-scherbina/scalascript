# Markdown Multi-Link Imports

## Overview

ScalaScript already treats a Markdown paragraph containing exactly one link as
a module import. This feature extends that same rule to paragraphs containing
multiple import links separated only by Markdown whitespace. Authors can group
related imports visually in one Markdown paragraph without falling back to code
imports or repeating blank lines.

## Interface

The public syntax is a pure Markdown paragraph whose non-whitespace inline
children are links:

```markdown
[contentDocument](std/content.ssc) [contentToolkitNode, lower](std/ui/content.ssc)

[Card as UICard](./ui/card.ssc)
[Chart as MetricsChart](./charts.ssc)
```

Each link lowers to one `Content.Import(path, bindings)` entry in source order.
Binding text keeps the existing forms:

| Link text | Binding |
|-----------|---------|
| `Name` | `ImportBinding("Name")` |
| `Name, Other` | two bindings from one module |
| `Name as Alias` | aliased binding |
| `Name from Module` | qualified collision-suppression binding |

Paragraphs containing prose or non-whitespace inline nodes that are not import
links remain normal `Content.Prose`. Links whose destination starts with `#`
remain internal Markdown cross-references and are not imports.

## Behavior

- [x] A single-link import paragraph keeps the existing `Content.Import`
      lowering.
- [x] A pure paragraph with two or more import links lowers to multiple
      `Content.Import` entries in source order.
- [x] Multiple bindings, aliases, and `from` qualifiers are preserved per link.
- [x] Whitespace, soft line breaks, and hard line breaks may separate import
      links in the same paragraph.
- [x] Paragraphs that mix prose text with links stay prose and do not import.
- [x] Internal `#...` links stay prose/cross-references and do not import.
- [x] Pure import paragraphs are omitted from `DocumentContent`, including
      paragraphs with multiple import links.
- [x] The interpreter can resolve and execute symbols imported from two
      different modules declared in one Markdown paragraph.

## Out of scope

- Inline imports embedded inside prose sentences.
- Merging separate links that target the same module path. Authors should keep
  using `[A, B](same.ssc)` when one import entry is desired.
- New code-fence or YAML import forms.
- Changing URL, `dep:`, `github:`, `jitpack:`, or local path resolution.

## Design

`Parser.asImport` becomes a plural import recognizer. It scans paragraph inline
children, accepts only import links plus whitespace line-break nodes, and
returns `List[Content.Import]`. The `extractSections` conversion changes from
`toContent(...): Option[Content]` to `toContents(...): List[Content]` so one
Markdown node can lower to several AST content entries. The content snapshot
builder reuses the same recognizer to continue omitting pure import paragraphs
from `DocumentContent`.

This keeps the feature parser-local: import resolution, typer collision
handling, interpreter execution, and generated backends already consume a flat
sequence of `Content.Import` nodes.

## Decisions

- **Recognize only pure import paragraphs** - chosen because a prose paragraph
  with links is a user-visible content block, not an import declaration.
  Rejected: treating every link in prose as an import, which would make normal
  Markdown references surprising.
- **One AST import per link** - chosen because it preserves source order and
  avoids inventing path-merge semantics. Rejected: merging same-path links,
  because existing `[A, B](same.ssc)` syntax already expresses that case.
- **Exclude `#...` destinations** - chosen to align parser behavior with the
  documented cross-reference rule. Rejected: preserving the old accidental
  interpretation of a paragraph containing only `[Section](#section)` as an
  import.

## Results

Implemented on 2026-06-05. Verification command:

```bash
cd /Users/sergiy/work/my/scalascript/.worktrees/feature/markdown-multi-link-imports && sbt "core/testOnly scalascript.parser.MarkdownMultiLinkImportTest" "backendInterpreter/testOnly scalascript.MarkdownMultiLinkImportInterpreterTest"
```

Results: 6 parser tests and 1 interpreter test passed. The parser test suite
covers single-link compatibility, multi-link order, alias/from bindings,
whitespace plus soft/hard line-break separators, prose/internal-link boundaries,
and `DocumentContent` omission. The interpreter smoke verifies two local modules
imported from one Markdown paragraph resolve and execute.
