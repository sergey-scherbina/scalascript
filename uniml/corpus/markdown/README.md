# UniML Markdown conformance corpus

This directory pins the external data used by the UniML Markdown production
gate.  The parser is never used as its own oracle: every case first checks exact
source reconstruction and then compares the test-only semantic renderer with
the upstream expected HTML.

## Pinned inputs

- `commonmark-0.31.2-spec.json` is the complete 652-case CommonMark 0.31.2
  corpus from <https://spec.commonmark.org/0.31.2/spec.json>, corresponding to
  `commonmark/commonmark-spec` revision
  `9103e341a973013013bb1a80e13567007c5cef6f`.
- `gfm-0.29-spec.txt` is `github/cmark-gfm`'s official GFM 0.29 spec at tag
  `0.29.0.gfm.13`, revision
  `587a12bb54d95ac37241377e6ddc93ea0e45439b`.  The gate selects all 23 examples
  in the enabled extension sections: tables (8), task-list items (2),
  strikethrough (2), and extended autolinks (11).  The two task-list examples
  are normative examples in the spec even though its C test runner marks them
  `disabled`; the generator deliberately retains them.

- `whatwg-entities.json` is the named-character-reference table of the WHATWG
  HTML Living Standard, taken verbatim from <https://html.spec.whatwg.org/entities.json>.
  It has 2231 entries, of which the generator emits the **2125 semicolon-terminated**
  ones: CommonMark 6.2 recognises no others, and the 106 legacy names without the
  semicolon must stay literal text. The generated table lands in the MAIN source
  tree — `uniml/markdown/src/main/scala/.../markdown/generated/` — because the
  decoder is production code, so `generate.py` has a third controlled root and the
  manifest covers it like the other two. It replaced a hand-typed table of roughly
  250 names, which is why `&copy;` used to decode while `&Dcaron;` stayed literal.

`MANIFEST.json` records immutable URLs, revisions, raw and canonical SHA-256
digests, counts, licenses, and the exact closed roster plus digest of every
controlled corpus and generated file.  Symlinks, directories, caches, missing
files, and unmanifested files are rejected. `LICENSE.commonmark.txt` and
`LICENSE.gfm.txt` are the upstream notices. The spec examples are CC BY-SA 4.0.

## Reproducible generation

From the repository root:

```sh
python3 uniml/corpus/markdown/generate.py --check
python3 uniml/corpus/markdown/generate.py --write
```

`--check` is the CI-safe form. It verifies raw SHA-256 digests, regenerates all
output and the manifest in memory, checks the exact two controlled directory
rosters, and runs five isolated negative probes. Those probes prove that raw
snapshot tampering, a generated-shard symlink, an extra generated Scala file,
an extra corpus file, and a cache directory all fail closed. `--write` updates
`gfm-0.29-enabled-extensions.json`, the portable corpus and complete-baseline
Scala shards below `uniml/markdown/src/test/scala/.../corpus/generated/`, and
the deterministic manifest.

## Gates

The regular ScalaTest suite performs a full census on both JVM and Scala.js.
Before any filter is applied it authenticates the exact CommonMark ids 1..652,
the exact 23 GFM id/extension pairs, canonical corpus digests, uniqueness, and
the complete baseline roster. `BASELINE.tsv` has all 675 x 5 = 3375 axis rows,
including matches. Every row pins expected and actual digests plus a canonical
full diagnostic observable (severity, code, message, span, dialect, details)
and the exact token/tree observable (roots, edges and roles, kinds, channels,
lexemes, spans, origins, and order). The iterative tree validator independently
checks source slicing, Unicode position coordinates, bounds, containment,
branch envelopes, and explicit source-backed/synthetic origin semantics.
Semantic mismatches are counted rather than skipped, so the suite can
characterize an in-progress parser without hiding a case. Any baseline change
is an explicit review point; only the separate strict gate can certify
conformance.

The portable test-only HTML renderer is calibrated to cmark's HTML and URI
boundary rules (including text/attribute escaping, URI safe bytes, image alt
plain-text rendering, list whitespace, task markers, and table row width).
Hand-built AST fixtures and official CommonMark/GFM cases lock those rules so
renderer defects cannot be recorded as parser failures.

The release gate is deliberately separate and fail-closed.  Normal `test`
performs the reviewed census; `run-strict.sh` links/runs the explicit gate main
on the selected platform:

```sh
uniml/corpus/markdown/run-strict.sh jvm
uniml/corpus/markdown/run-strict.sh js
```

The JVM-only convenience main supports narrower local diagnosis:

```sh
scripts/sbtc "unimlMarkdown / Test / runMain scalascript.uniml.dialect.markdown.corpus.MarkdownCorpusGate --commonmark --example 1"
```

Its optional arguments are `--commonmark`, `--gfm`, and `--example <id>`.  Both
strict forms print corpus/version, example id, section, embedded source,
expected and actual values for every independent axis, the first code-point
mismatch, and a readable diff.  They fail after reporting if even one axis
differs. `run-strict.sh` accepts only that closed argument set; full runs use
batch sbt so large failure reports and the final census are not truncated,
while single-example JVM diagnosis uses the project's thin-client wrapper.
