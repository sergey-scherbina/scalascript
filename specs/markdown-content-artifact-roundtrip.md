# Markdown Content Artifact Round-Trip

## Overview

Markdown-hosted frontend content must remain available after a module is saved
or consumed through compiled artifacts. The current `DocumentContent` snapshot
already powers `std/content`, `contentToolkitNode()`, native clients, generated
JS/JVM helpers, and `contentToMarkdown(value)`. This slice makes artifact
preservation explicit: `.scir` carries the normalized content snapshot, and
`.sscc` carries the parsed-module snapshot without requiring source reparsing.

The goal is not lossless source archival. Artifacts preserve the semantic
`DocumentContent` tree that renderers and introspection helpers consume.

## Interface

No new `.ssc` user function is added.

The artifact contract becomes:

- `.scir` `ModuleIrArtifact.body` contains a serialized `NormalizedModule`.
  `NormalizedModule.document` must be preserved when present.
- `.sscc` v3 remains a token-stream artifact for `Module`, with an optional
  trailing `DocumentContent` payload after `ModuleEnd`.
- Readers that see old artifacts with no content snapshot return
  `document = None`; `contentDocument()` and dependent helpers then fail through
  the existing missing-snapshot path rather than fabricating content.

The `.sscc` trailing payload is internal and not a new CLI surface. Its wire
shape is:

```text
ModuleEnd [DocumentBlob]
DocumentBlob = kind:varint(15) len:BE32 cbor(DocumentContent)
```

`DocumentBlob` is written only when `Module.document` is `Some`. The payload is
after `ModuleEnd` so older v3 readers, which stop at `ModuleEnd`, can still load
the executable module while silently ignoring the extra content payload.

## Behavior

- [ ] `.scir` JSON and binary file round-trips preserve
      `NormalizedModule.document`, including manifest data, heading attrs,
      selected blocks, inlines, embedded fenced source, parsed structured data,
      and nested sections.
- [ ] `.sscc` v3 round-trips preserve `Module.document` without reparsing the
      original `.ssc` source text.
- [ ] `.sscc` v3 gzip round-trips preserve the same `Module.document` snapshot
      as the plain v3 form.
- [ ] Old or manually constructed modules/artifacts without a content snapshot
      remain valid and expose `document = None`.
- [ ] `.sscc` readers still populate lazy scalameta trees for executable code
      blocks after adding the content payload.
- [ ] `Normalize` after `.sscc` read carries the restored AST snapshot into
      `NormalizedModule.document`.
- [ ] `contentToMarkdown(contentDocument())` can run from a `.sscc` input file
      and render the restored semantic Markdown snapshot.

## Out of Scope

- No exact source/whitespace/byte-offset preservation.
- No mutation/edit API for artifact content.
- No linked-module runtime namespace for inspecting another module's content
  snapshot by module id.
- No `.scim`, `.scjvm`, or `.scjs` content payload fields in this slice.
- No source-unavailable import component resolution beyond preserving each
  artifact's own current-module snapshot.

## Design

### `.scir`

`ArtifactIO.writeIr` and `writeIrFile` already serialize the whole
`NormalizedModule` body with upickle. Because `NormalizedModule.document` and
the IR content model derive `ReadWriter`, no new field is needed. The missing
piece is a focused regression test that proves content trees survive both the
legacy JSON helper path and the MessagePack file path.

### `.sscc`

The v3 token stream intentionally does not carry `Module.sourceText`, and the
execution-oriented `Module.sections` tree is not enough to reconstruct the full
frontend content snapshot: inline structure, heading attrs after normalization,
block metadata, embedded parsed data, and source-preserved fenced text belong in
`DocumentContent`.

Therefore `.sscc` writes an optional content blob after `KModuleEnd`. This keeps
the main execution stream unchanged and avoids rebuilding content from a lossy
tree. The blob uses the same CBOR family as the existing manifest payload and is
decoded only by readers that know about the extension. Old v3 readers stop at
`KModuleEnd`; new readers accept both old artifacts with no trailing blob and
new artifacts with the blob.

### Legacy Missing Snapshot

Missing snapshots are not reconstructed silently. A module with
`document = None` continues to mean "this artifact has no content snapshot."
Runtime helpers that require content keep reporting the existing
missing-snapshot diagnostic. This avoids giving renderers partial content that
looks authoritative but lost Markdown metadata.

## Decisions

- **Preserve `DocumentContent` directly in `.sscc`** - chosen because the AST
  execution tree is lossy for frontend content. Rejected: reconstructing
  `DocumentContent` after read from `Module.sections`, because it would miss
  inline structure, metadata, and structured fenced data.
- **Use a trailing v3 payload after `ModuleEnd`** - chosen because it lets older
  v3 readers load executable code while ignoring content. Rejected: inserting a
  new structural token before sections, because that would make old readers fail
  on otherwise executable artifacts.
- **Keep `.scim/.scjvm/.scjs` unchanged** - chosen because their current
  artifact roles are interface and backend-code cache. Rejected: duplicating
  content into every backend artifact before a linked-module content namespace
  exists.

## Results

To be filled after implementation and verification.
