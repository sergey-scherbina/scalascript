# yaml-test-suite data-2022-01-17

This directory vendors the parser inputs needed by the UniML YAML conformance
gate. The files retain the upstream case-directory layout, including nested
case ids such as `3RLN/00`.

- Upstream: <https://github.com/yaml/yaml-test-suite>
- Release tag: `data-2022-01-17`
- Annotated tag object: `5f49729577242103ae23838ac2ad4d9145aec126`
- Peeled commit: `6e6c296ae9c9d2d5c4134b4b64d01b29ac19ff6f`
- Source archive:
  `https://codeload.github.com/yaml/yaml-test-suite/tar.gz/6e6c296ae9c9d2d5c4134b4b64d01b29ac19ff6f`
- Source archive SHA-256:
  `cc4a08f9ccc1cb2e66f32b3f1192bf1f07b3175682bbb715bf709bafa70322d4`
- Paired source tag: `v2022-01-17`
- Source annotated tag object: `6d48918a2320e767f6e2e57304f5ab42c19d71db`
- Source commit: `45db50aecf9b1520f8258938c88f396e96f30831`
- Source `src/` tree: `1b1a150cd127094828f120e3d4c1cbefef42f02a`
- Generated data `tags/` tree: `a971feec6d8c46ba38db47c4751c3366270157e1`
- License: MIT. `LICENSE` is the exact 1,075-byte `License` blob
  `5059e95ab21ff74438dd5af5a3f50a1a62c4b05f` from the paired source
  commit; its SHA-256 is
  `c9562189164244554a69ab3f29d2d93ed9492c165723aaaa5fffc932cdbbfc85`.

`cases/` contains, for every one of the 402 released parser cases:

- `===` — the upstream case title;
- `in.yaml` — the exact UTF-8 parser input;
- `test.event` — the upstream expected parser event stream;
- `error` — present only for the 94 cases expected to be rejected.

`categories.tsv` materializes the generated upstream `tags/` symlink index
without vendoring symlinks. It contains 1,150 sorted `(case id, tag)` rows,
covering all 402 cases and 33 upstream tags. Multi-case inheritance is already
resolved by upstream's `suite-to-data` generator, so cases such as `SM9W/00`
and `SM9W/01` retain their distinct `sequence` and `mapping` categories. Its
SHA-256 is
`cfbc81ae960db00e42822576d96ddeab2a6511da31b7e9a5e7e9f65b5474b755`.
These are upstream tags, not inferred base-id “families” or invented sections.

The local `.gitattributes` marks `cases/**` as `-text`; Git must not rewrite
CRLF, BOM, tabs, or any other upstream parser bytes on checkout.

There are no encoding exclusions in this release. Every selected file is valid
UTF-8, so all 402 inputs are representable by the UniML in-memory `String`
contract. `encoding-exclusions.txt` is intentionally empty apart from comments;
the generator fails if that fact changes.

## Reproduction

Starting from a clean upstream checkout, verify the two tag objects above.
Copy the four case file kinds from the peeled data commit without newline or
encoding conversion. Copy `License` from the paired source commit byte for
byte. Materialize `categories.tsv` by sorting the data commit's `tags/<tag>/<id>`
and `tags/<tag>/<id>/<subcase>` symlink paths into `(id, tag)` rows. Then run:

```text
python3 uniml/corpus/yaml-test-suite-data-2022-01-17/generate.py --write
```

Normal verification is read-only and must use:

```text
python3 uniml/corpus/yaml-test-suite-data-2022-01-17/generate.py --check
```

The generator fails unless the corpus has exactly 402 cases and 94 error
markers, every case and corpus-root entry is expected and non-symlink, and the
logical case payload, categories, exact license, generated source, and
tree-manifest SHA-256 values equal hard-coded pins. `--check` also
byte-compares `SHA256SUMS` and the shared JVM/Scala.js generated source without
rewriting either one. `--write` is explicit and still refuses to write from a
tree that misses the immutable pins. `SHA256SUMS` covers every vendored case
file, `categories.tsv`, and `LICENSE`. The generated Scala object records the
data/source tag objects and commits, source and tags trees, license blob and
digest, categories digest, tree-manifest digest, and logical payload digest;
cross-platform tests recompute all representable logical digests.

The generated source is split into shards of at most 48 cases so no JVM class
initializer approaches the 64 KiB bytecode limit.

## Gates

The named baseline census compares all 402 cases and exits successfully only
when integrity, the exact roster, aggregate census, all 402 full-observable
rows, and the 33 upstream-tag category rows match the frozen baseline:

```text
scripts/sbtc "unimlYaml/Test/runMain scalascript.uniml.dialect.yaml.yamlOfficialCorpusCensus"
```

The fail-closed strict command prints the same census plus every mismatching
case's expected/actual event streams and diff, then exits nonzero unless all
402 cases agree:

```text
scripts/sbtc "unimlYaml/Test/runMain scalascript.uniml.dialect.yaml.yamlOfficialCorpusStrict"
```

Initial red baseline before M3.1 grammar work:

```text
cases=402 expected-errors=94 actual-errors=220
source=402/402 chunks=402/402 validity=210/402
semantics=128/402 strict=112/402 crashes=0
baseline-sha256=6a02cd7f47ab532b265ca2429e2051d418a98971301e15df7bba3f72a5be9e3c
category-sha256=03abbe294e1b24ef1df37fd45150dcaaef8f53c0550f2e5772ba77523a82c98a
```

Every case is parsed under at most six deterministic schedules of at most four
chunks. Besides whole/midpoint/edge/quarter schedules, inputs containing CRLF
or an astral code point are split directly before, between, and after the first
CRLF or UTF-16 surrogate pair.

Every sorted case row in `YamlOfficialCorpusBaseline.scala` freezes expected
and actual source digests, reconstruction invariants and axis status,
validity/completion, ordered diagnostic `(severity, code)` digest, expected and
actual normalized-event digests, and every schedule's split offsets plus
canonical parse-tree/reconstruction result digest. Thus equal aggregate counts
cannot hide one failure replacing another, and JVM/Scala.js drift cannot hide
behind independently green totals. The reconstruction walk is iterative and
also checks span validity, child containment, exact descendant-token bounds,
source identity, and source-backed/synthetic-origin contracts.

The evaluator catches only `NonFatal` failures plus explicit
`StackOverflowError`, independently for whole/chunk parsing, reconstruction,
canonical snapshots, and semantic events. A semantic failure therefore cannot
erase already observed source/chunk results, one bad chunk schedule cannot
erase the whole-source result, and every one of the 402 cases is still
attempted.

Invalid upstream cases often end `test.event` at a partial parser-event prefix.
The current recovered semantic tree cannot reconstruct that ordered prefix, so
those differences remain honestly red until the parser exposes an ordered
semantic event trace.
