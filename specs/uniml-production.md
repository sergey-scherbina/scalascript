# UniML production completion contract

> Status: accepted for implementation by Sergiy on 2026-07-27.
>
> This specification closes the production boundary left open by
> [`uniml.md`](uniml.md), [`uniml-yaml.md`](uniml-yaml.md),
> [`uniml-markdown.md`](uniml-markdown.md), and
> [`v2.2-self-hosted-dialect.md`](v2.2-self-hosted-dialect.md). The executable queue is
> `SPRINT.md` section **UniML production completion**.

## 1. Purpose and completion boundary

UniML already has a dependency-free lossless tree VM and production source modules for JSON, XML,
the safe YAML M3 profile, and the original Markdown M4 profile. Production completion means that
the remaining declared roadmap and integration obligations are implemented and measured:

1. YAML 1.2.2 M3.1 grammar and lexical hardening pass the pinned official corpus.
2. CommonMark 0.31.2 and the enabled GFM 0.29 extensions pass their pinned official corpora,
   including the complete applicable HTML5 named-entity set.
3. A publishable ScalaScript language adapter and safe hybrid `.ssc` composer exist as the first M5
   programming-language artifact.
4. The six M6 protocols — query, rewrite, diff, source map, incremental reparse, and formatter —
   have cross-platform reference implementations and law tests.
5. A portable additive `.ssc` ABI exposes lossless documents without breaking the existing
   `std.json`, `std.yaml`, or content/bootstrap surfaces.
6. The JSON document address resolver is exposed through the standard ScalaScript surface with
   honest physical units and all supported backends.
7. The already-selected self-hosted compiler front `F` reaches a fail-closed zero-delegate state;
   fixed-point and assembled release gates defend it.
8. Versioned artifacts, standalone and root CI, examples, migration notes, security limits, and
   release evidence exist.

The following are explicitly not release blockers:

- programming-language dialects other than ScalaScript until selected by demand;
- automatic dialect detection;
- user-declared or user-executable grammars;
- execution of non-ScalaScript embedded dialects;
- deep incremental typing or an LSP semantic engine;
- globally minimal reparse or globally optimal text diffs;
- an opinionated family of formatter styles;
- legacy YAML 1.1 implicit typing and application-specific object constructors.

Those are future features, not omissions hidden behind the word “production”.

## 2. Three independent products

The production program must keep three concerns separate:

| Concern | Product | Authority |
|---|---|---|
| Lossless parsing and composition | UniML format leaves + M5 ScalaScript adapter | `scalascript.uniml.*` artifacts |
| Source tooling | M6 reference protocols and implementations | `scalascript.uniml.tooling` |
| Executing ScalaScript compiler front | self-hosted front `F` | v2 front-selection and fixed-point gates |

The M5 ScalaScript adapter is a lossless CST/tooling adapter and differential oracle. It is not a
second canonical compiler front. The compiler convergence decision already selected Option A:
self-hosted `F` is the product front. Moving the old Scala spike from a test source root does not
supersede that decision.

Sergiy's 2026-07-27 instruction authorizes the formerly gated P6.22 production work. It does not
authorize bypassing the zero-delegate, corpus, conformance, or fixed-point gates.

## 3. Core contracts

### 3.1 Source coordinates

All public source offsets and lengths are Unicode code-point counts:

- `offset` is zero-based;
- `line` and `column` are one-based;
- spans are half-open;
- a span never crosses `SourceId`;
- line endings retain their exact spelling;
- invalid unpaired UTF-16 surrogates remain diagnosable source input and never cause a platform
  exception.

Any API that additionally reports UTF-8 bytes names the unit explicitly as `utf8ByteOffset` and
`utf8ByteLength`. An unqualified `offset`, `length`, or “bit address” may not silently mix
code-point, UTF-16, UTF-8, or bit units.

### 3.2 Immutable processor

The shipped processor interface is normative:

```scala
trait Processor[S, I, O]:
  def start: S
  def step(state: S, input: I): Stepped[S, O]
  def stop(state: S): ProcessBatch[O]

final case class Stepped[S, O](state: S, batch: ProcessBatch[O])
final case class ProcessBatch[A](
  values: Vector[A],
  diagnostics: Vector[Diagnostic],
)
```

All state is explicit and immutable. A driver may use local variables while folding states, but a
processor object has no hidden mutable lifecycle. `stop` is called once for a completed fold.
Transport chunking remains observationally invisible.

### 3.3 Canonical tree and source coverage

`UniNode` remains the canonical presentation CST. A complete parse satisfies:

- concatenating source-backed tokens in token-id order reconstructs the exact source;
- token ids are unique and strictly increasing within one composed document;
- source-backed spans are valid, monotone, and contained by the owning source;
- every source code point is represented exactly once by a source-backed token;
- synthetic branches/segments are explicitly marked and never claim source bytes;
- projections may normalize semantics but never replace the CST;
- a projection failure returns diagnostics and a typed absence/error, not an exception.

### 3.4 Composition

An embedded dialect may be invoked only at a boundary identified by the host grammar. Composition:

1. retains the host boundary and its raw body tokens;
2. parses only the bounded child region;
3. maps child spans to the parent `SourceId` and absolute code-point offsets;
4. assigns globally unique, source-ordered token identities;
5. attaches every child root in order, not only `headOption`;
6. keeps child errors local while contributing them to the composed result;
7. never executes the child language;
8. leaves unknown language identifiers inert and lossless.

The built-in registry is immutable and non-overridable. Callers may add a fresh id/alias but cannot
replace an existing built-in name.

## 4. Modules and published artifacts

The root and standalone builds must describe the same production source roots.

| Module | Artifact | Targets | Production dependencies |
|---|---|---|---|
| `uniml/core` | `scalascript-uniml` | JVM, Scala.js | none |
| `uniml/json` | `scalascript-uniml-json` | JVM, Scala.js | core |
| `v1/lang/uniml-xml` | `scalascript-uniml-xml` | JVM, Scala.js | core, markup core |
| `uniml/yaml` | `scalascript-uniml-yaml` | JVM, Scala.js | core |
| `uniml/markdown` | `scalascript-uniml-markdown` | JVM, Scala.js | core |
| `uniml/scala` | `scalascript-uniml-scalascript` | JVM, Scala.js | core + format leaves used by composition |
| `uniml/tooling` | `scalascript-uniml-tooling` | JVM, Scala.js | core |
| optional bridges | separately named artifacts | only stated targets | leaf + target semantic model |

No production module may depend on another module's test configuration. The root aggregate, the
standalone aggregate, publication metadata, and CI matrix include every production artifact.

The XML and compiler-specific bridges may remain outside the standalone leaf repository layout if
their dependencies are not standalone; the standalone build must state that boundary explicitly
rather than silently omitting a claimed artifact.

## 5. External conformance gates

### 5.1 Compare first

Every official case is executed before classification. A gate may classify an already-compared
failure, but no marker, expected-failure list, heuristic, empty projection, or known unsupported
production may skip the comparison.

On mismatch the gate prints:

- corpus/version/case id and section;
- input path or embedded source;
- expected and actual validity/status;
- expected and actual normalized semantic observable;
- the first mismatch and a readable diff;
- source reconstruction mismatch separately from semantic mismatch.

An exclusion manifest is permitted only for transport encodings that the in-memory Unicode
`String` contract cannot represent. The manifest is pinned by exact case id and reason, and the
gate fails when an exclusion disappears from or is added to the corpus.

Generated corpus/data files record upstream URL, immutable revision/version, license, generator
command, and SHA-256 digest. Tests verify the digest.

### 5.2 YAML 1.2.2 M3.1

The pinned baseline is `yaml/yaml-test-suite` `data-2022-01-17`, including all 402 `in.yaml`
cases. For representable cases:

- source reconstruction is exact;
- acceptance/rejection agrees with the case;
- normalized event semantics agree with `test.event`;
- status and diagnostic severity are deterministic;
- JVM and Scala.js results are identical.

M3.1 covers:

- the YAML printable character set, BOM handling, document markers and directive placement;
- `%YAML` and `%TAG` validation, document-scoped handles, duplicates and tag expansion;
- block and flow sequences/mappings, explicit and complex keys, compact nodes, flow pairs,
  property-only nodes, and indentationless sequences;
- strict indentation/dedent rules and local recovery;
- plain, single-quoted, double-quoted, literal and folded scalars, including multiline folding,
  escaped breaks, more-indented lines, chomping, and explicit indentation;
- anchors, aliases, tags, duplicate mappings, multiple documents and all three selected schemas;
- finite source/line/scalar/indent/depth/node/token/diagnostic/anchor/alias/expansion limits;
- bounded linear accumulation rather than repeated whole-buffer copying.

YAML 1.1 implicit booleans/timestamps, arbitrary constructors, merge-key application semantics, and
schema-specific object creation remain out.

### 5.3 CommonMark 0.31.2 and GFM 0.29

The pinned CommonMark baseline is all 652 examples from 0.31.2 `spec.json`. A test-only
deterministic semantic renderer compares exact official HTML/semantic output on JVM and Scala.js
after source reconstruction succeeds. The enabled GFM features use the official 0.29 cases for:

- tables and alignment;
- task-list items;
- strikethrough;
- extended autolinks.

The parser must implement the official block and inline precedence, including tabs/indentation,
coalesced indented code, fences, HTML blocks, definitions and multiline titles, containers/lists,
delimiter runs, bracket/reference state, destinations/titles, autolinks/email, raw inline HTML,
entities, escapes, code spans and line breaks.

The entity table is generated from a pinned WHATWG `entities.json`:

- every semicolon-terminated named reference applicable to CommonMark is present;
- one- and two-code-point replacements are retained;
- unknown names remain ordinary text;
- numeric replacements follow the CommonMark/HTML replacement rules and reject surrogate values;
- decoding happens only in contexts allowed by CommonMark and never inside code spans/blocks or raw
  HTML.

Line, delimiter-run, fence, reference, block, source and core limits are enforced and diagnosed.

### 5.4 Frozen UPR-1 characterization baseline

The compare-first apparatus landed before production grammar changes. These numbers are the
reviewed starting state, not a conformance verdict; the release gates remain deliberately red until
UPR-2 and UPR-3 close every mismatch.

YAML uses all 402 cases and 94 upstream error markers from
`yaml-test-suite` `data-2022-01-17`:

```text
cases=402 expected-errors=94 actual-errors=220
source=402/402 chunks=402/402 validity=210/402
semantics=128/402 strict=112/402 crashes=0
baseline-sha256=6a02cd7f47ab532b265ca2429e2051d418a98971301e15df7bba3f72a5be9e3c
category-sha256=03abbe294e1b24ef1df37fd45150dcaaef8f53c0550f2e5772ba77523a82c98a
```

The closed 1,300-file case roster has logical payload SHA-256
`97e131ad015f478c85318061d7e1b3c12ab517f8b922a5c005fa25ab4be5b7b5` and manifest SHA-256
`51c3212589a9c51ecb5de32ea9037be2c5a5aa38e2a6e710afa60f427403bf45`.
The census command exits zero only for that exact full-observable baseline. The strict command
exits one and reports exactly 290 non-conforming cases; zero or any different roster/count is a
gate failure.

Markdown uses all 652 CommonMark 0.31.2 examples plus the 23 enabled official GFM 0.29 examples
(8 tables, 2 task lists, 2 strikethrough, 11 extended autolinks). The pinned canonical case
digests are:

```text
commonmark-cases-sha256=f636418b09346809aa605ee4d52c3e600bf0f057251b77c386e49fae67a184a3
gfm-enabled-cases-sha256=56ec730753789fa2a39db08f0dbfe7b63c9eec3b612494ff3fb0f75fef1facdd
```

Every case runs five independent axes (`source`, `tokens`, `status`, `html`, `chunks`), including
matches:

```text
cases=675 passing-cases=460 failing-cases=215
axes=3375 match=3131 diff=240 error=4 non-pass-axes=244
full-rows-sha256=9094aa2a7469be431b06565b91ca44c721c93a50ed69faf2991a7c17b2702f62
non-pass-rows-sha256=9211ced6fdd0b51f6c839836377aa59d7d1d7a608f063e76994abb9b9c3fd9da
sections-sha256=b2b6c9d920fd9430bc9347dfbc55a4edf027d4c5e067b1d412a7c07c7f2107a5
baseline-file-sha256=c1dc525945cece8137cba4f2ade1215b423eb91cb5a6d90b049f3688d006f1b5
```

The JVM and Scala.js census suites must pass with those exact rows. Both strict platform gates
must currently exit one with the same 244 non-pass axes and digests; the known-good filtered
CommonMark case 652 must exit zero. Generator checks authenticate the closed file rosters before
either baseline runs. Gate qualification also requires the no-clean
`scripts/verify-uniml-dual-build` root→standalone transition twice, preventing incompatible Zinc
products from manufacturing parser failures.

## 6. M5 ScalaScript language adapter

### 6.1 Declared profile

The first programming-language artifact declares its exact grammar/version in its dialect id. It
must not use an unqualified “full ScalaScript” id while its external front differential has holes.
A subset profile is a production profile when its boundary is explicit and its complete corpus gate
is green.

The public package is:

```text
scalascript.uniml.dialect.scalascript
```

with separate public/internal components for:

- `ScalaScriptDialect` / limits / facade;
- lossless lexer;
- total parser and recovery;
- semantic/compiler projection;
- hybrid `SscCompose`.

The existing test spike may remain temporarily as a deprecated thin alias or differential oracle,
but production code cannot import it from test scope.

### 6.2 Adapter requirements

- String and interpolation tokens retain their exact quotes, escapes and raw source lexemes.
- Positions use the common code-point coordinate contract.
- Parsing/projecting is total for malformed input and bounded by finite limits.
- Attacker-controlled nesting cannot overflow the host stack.
- Holes are typed projection results with diagnostics; `__notImplemented__` is not silently treated
  as a well-typed success.
- Every declared construct has an external `ssc1-front`/`F` differential case.
- Single- and multi-file gates compare exact normalized compiler input/output for the declared
  profile.
- JVM and Scala.js CST/projection results agree.

### 6.3 Hybrid `.ssc` composer

The composer parses YAML front matter, Markdown prose and typed fences into one source-accurate
tree using the composition rules in §3.4. Untyped fences select ScalaScript; registered format names
select their data dialect; unknown fences remain inert. Multiple ScalaScript fences preserve their
individual boundaries and expose a separately defined compilation-source view.

The compilation-source view must state how separators and line mappings are produced. Dropping a
trailing line ending or joining fences with a synthetic newline requires an explicit source-map
segment; it may not mutate the canonical CST.

## 7. M6 tooling

M6 is a standalone JVM/Scala.js module. Public APIs may gain convenience overloads, but these
reference semantics are stable.

### 7.1 Paths and queries

```scala
final case class NodePath(indices: Vector[Int])

final case class NodeRef(
  path: NodePath,
  node: UniNode,
  parentRole: Option[String],
)

final case class Query(
  kind: Option[String] = None,
  role: Option[String] = None,
  channel: Option[TokenChannel] = None,
  within: Option[SourceSpan] = None,
)
```

Queries are iterative, deterministic and source ordered. `NodePath` addresses ordered child edges,
not semantic map keys. Invalid paths return a typed absence/error. Query complexity is bounded by a
caller-supplied node/result limit.

### 7.2 Rewrites

```scala
final case class TextEdit(span: SourceSpan, replacement: String)
final case class RewritePlan(edits: Vector[TextEdit], diagnostics: Vector[Diagnostic])
```

A valid plan:

- references one `SourceId`;
- contains sorted, non-overlapping, in-range half-open spans;
- applies from the end or by an equivalent offset-safe algorithm;
- leaves every non-edited code point byte-for-byte/code-point-for-code-point identical;
- returns the original source for an empty plan.

Overlap, cross-source, invalid range, surrogate-splitting and configured-size violations are
rejected with both conflicting spans. They are never silently ordered or truncated.

### 7.3 Diff

`TreeDiff` returns typed changes and a `RewritePlan` when source text is available. Required laws:

```text
diff(a, a).changes == empty
apply(diff(a, b).rewrite, source(a)) == source(b)
```

The reference algorithm may be non-minimal, but it is deterministic, bounded, and preserves common
prefix/suffix regions. Structural changes distinguish token text/channel/kind, edge role, branch
kind, insertion, deletion and replacement.

### 7.4 Source maps

A source map consists of monotone segments between generated/output ranges and source ranges.
Synthetic or unmapped output is explicit. Forward and reverse lookup return zero, one, or multiple
typed results; ambiguity is never resolved by an undocumented first-wins rule.

Mappings validate:

- non-negative ordered half-open ranges;
- matching source identities;
- monotone generated ranges;
- no source claim for synthetic bytes;
- round-trip containment for one-to-one segments.

### 7.5 Incremental reparse

```scala
final case class StableNodeId(value: Long)
final case class DamageRange(oldSpan: SourceSpan, newSpan: SourceSpan)
final case class ParseSnapshot(
  source: String,
  result: ParseResult,
  identities: Map[NodePath, StableNodeId],
)
```

After a valid edit sequence:

- incremental observable output equals a clean parse of the edited source;
- diagnostics/status equal the clean parse;
- unchanged nodes wholly outside the damage closure retain their stable sidecar ids;
- nodes inside damage may receive new ids;
- reuse statistics report actual reused/reparsed regions.

A full-reparse fallback is allowed for correctness. It reports zero reuse and cannot satisfy a
claim that a dialect has incremental reuse. Production M6 includes at least one dialect-aware
damage/reuse strategy. Random edit-sequence tests cover every shipped dialect and compare to clean
parsing on every step.

Stable ids are sidecar tooling identities, not changes to `UniNode` equality or serialized source
tokens.

### 7.6 Formatter

A formatter consumes a tree plus explicit profile/options and returns `RewritePlan`; it never
mutates a tree. Required laws:

```text
format(format(source)) == format(source)
parse(format(source)).projection == parse(source).projection
```

The second law is profile-specific and applies only to well-formed input with a successful semantic
projection. Broken or inert regions are preserved unless an explicit recovery option says otherwise.
The default reference formatter is conservative: it normalizes only rules owned by the selected
dialect profile and preserves unknown/embedded regions exactly.

## 8. Portable ScalaScript ABI

### 8.1 Additive surface

`runtime/std/uniml.ssc` defines portable ScalaScript ADTs for:

- code-point positions and spans;
- severity and diagnostics;
- ordered edges and lossless token/branch nodes;
- parse status/result;
- query matches;
- edits/rewrite/diff/source-map results.

No Scala/JVM `UniNode`, Java/Scala collection, host parser object, or platform type crosses this ABI.
Ordered entries and exact scalar lexemes are retained. YAML mappings are not silently coerced to
`Map`; YAML numeric lexemes are not silently coerced to `Double`.

Public additive format entry points are exposed through:

- `std.uniml` for generic/dialect-explicit operations;
- `std.json` for lossless JSON wrappers;
- `std.yaml` for lossless YAML wrappers;
- a new public `std.markdown`;
- the existing content APIs through explicit projection adapters.

Existing names and behavior remain compatible:

- `jsonParse`, `jsonRead`, `jsonValue` and builders;
- `parseYaml`, `toYaml` and the existing legacy YAML value model;
- bootstrap Markdown parsing and `std.content`.

They may delegate internally only after their existing byte/semantic/backend gates pass. The new
lossless API uses new names/types and explicit duplicate-key, alias-expansion, schema and numeric
policies.

### 8.2 Backend implementation

The preferred implementation is one portable `.ssc` source compiled by the ordinary v2/JS path.
There must not be a second handwritten JavaScript parser.

Where a host bridge is temporarily or necessarily used:

- it lives in `runtime/std/<feature>-plugin` and an equivalent v2 native plugin;
- it uses `TargetedIntrinsicProvider` and a runtime preamble for source-codegen targets when
  applicable;
- it is never hard-coded into interpreter core or generic JS/JVM generators;
- its public behavior is compared against the portable implementation;
- unsupported backends are stated explicitly until parity lands.

Final production completion for the standard lossless surface requires interpreter, v2
native/ASM, JVM and JS/Node agreement.

## 9. Document addressing

`JsonAddress` remains a shared resolver over the canonical JSON CST, not the semantic projection.
Before public exposure it must:

- accept RFC 6901 JSON Pointer escaping (`~0`, `~1`) with a documented root-path rule;
- compare decoded JSON object keys, including escapes and surrogate pairs;
- reject both `Error` and `Fatal` parse diagnostics;
- return the exact source slice for scalar and composite nodes;
- preserve the documented duplicate-key selection policy;
- expose code-point coordinates and, if required, separately named UTF-8 byte coordinates;
- define stability as key-stable until a positional segment is crossed;
- pass array, escaped-key, duplicate-key, emoji, malformed-input and composite tests.

The portable surface is:

```scala
case class DocAddressedValue(
  typeName: String,
  value: String,
  codePointOffset: Int,
  codePointLength: Int,
  line: Int,
  column: Int,
  stable: Boolean,
)

extern def addressReadDoc(text: String, pointer: String): Either[String, DocAddressedValue]
```

The actual field types follow the language's portable integer convention. The names, not comments,
must make units unambiguous.

A v1 interpreter plugin and v2 native plugin adapt the same resolver. Build aggregation,
ServiceLoader resources, CLI dependencies, package specs, installed standard-JAR allowlists,
native-plugin boundary tests and dependency gates include them. Full backend production is reached
through the portable §8 implementation; an interpreter/native-only bridge is an intermediate slice,
not an all-backend claim.

The acceptance differential first compares the logical value with an independent JSON
implementation, then slices the original source using the reported coordinates and compares that
physical observable. A gate that checks only the resolver against itself proves nothing.

## 10. Canonical self-hosted front

The self-hosted `F` remains the canonical compiler front. Production closure requires a fresh
compare-first census and then:

```text
single-file corpus: MATCH = all; DIFF = HOLE = EMPTY = TIMEOUT = DELEGATE = 0
multi-file corpus:  MATCH = all; DIFF = HOLE = EMPTY = TIMEOUT = DELEGATE = 0
```

Every breadth slice reruns:

- `specs/v2.2-p6.5-fsub.sh --self`;
- `specs/v2.2-p6.5-semantic.sh`;
- single- and multi-file byte differentials;
- `v2/conformance/check.sh`;
- affected root conformance.

The gate records whether `F` or legacy executed. A legacy delegate can preserve correct output but
does not count as front completion.

Release CI builds the tools-tier fat jar and runs:

- the Scala/UniML spike differential;
- C_min and X1 `stage1 == stage2`;
- the capstone programs;
- an assembled end-to-end no-delegate probe.

Only after the census is zero may the F4a delegate and legacy `ssc1-front.ssc0` /
`ssc1-lower.ssc0` be removed. Their removal is followed by the complete fixed-point, semantic,
conformance, example and root-test matrix. This sequence protects the bootstrap; deleting the oracle
to make the fallback count zero is forbidden.

## 11. Limits, safety and complexity

Every parser/tool operation has finite defaults and caller-overridable tighter limits. Limit
families include source code points, line code points, token code points/count, node count, branch
depth, diagnostics, format-specific stacks/tables, rewrite edit count/output size, query results,
diff work, source-map segments, incremental damage/reuse work, and formatter output.

Production invariants:

- no network, filesystem, environment, reflection, class loading, URI fetch, include, constructor
  execution, interpolation execution, embedded-code execution, XML external entity resolution, or
  YAML custom construction occurs during parsing/tooling;
- hostile nesting does not drive unbounded host recursion;
- repeated transport chunks or scalar lines do not cause quadratic whole-buffer copying;
- expansion operations such as YAML aliases have independent cycle/count/node limits and are off by
  default;
- raw HTML and links remain inert;
- malformed input returns a bounded partial CST and diagnostics without a platform exception.

## 12. Verification and release evidence

### 12.1 Required lanes

- focused core and every dialect on JVM and Scala.js;
- standalone `uniml` build;
- portable-subset lint;
- official YAML/CommonMark/GFM corpora;
- ScalaScript adapter single/multi/hybrid differentials;
- M6 law/property/random-edit tests on JVM and Scala.js;
- legacy std compatibility and new `.ssc` examples on interpreter/v2/JVM/JS;
- address unit/plugin/assembled/differential tests;
- self-host fixed points, semantics, v2 conformance and assembled no-delegate probe;
- affected conformance after each slice and full conformance before release;
- root tests and publication/package smoke.

Tests that compare expected output always print the observable diff. Corpus gates are sharded only
by a stable case-id partition and aggregate all shards without rewriting a shared baseline.

### 12.2 Release evidence

The claim release states exactly one of:

1. exact landed-SHA `scripts/ci-status` success;
2. the specific green CI job/descendant run and merge base that covers the change;
3. named local gates and results when CI cannot produce a verdict.

Cancelled CI is red. Pending is pending. Neither may be described as green.

### 12.3 Documentation closeout

Only measured gates may change compatibility checkboxes or milestone labels. Production closeout
updates:

- this contract and the component specs;
- `SPRINT.md`, `BACKLOG.md`, `CHANGELOG.md`, `MILESTONES.md`;
- root and standalone READMEs;
- API, migration, security/limits and generated-data provenance docs;
- examples and package/publication coordinates.

## 13. Decisions and rejected alternatives

- **Self-hosted `F` stays canonical.** The Scala UniML adapter ships for CST/tooling/oracle use.
  Rejected: reviving the test spike as a competing compiler front.
- **Official external corpora gate compatibility.** Rejected: curated lossless/no-throw inputs or
  JVM-vs-JS self-parity as proof of grammar conformance.
- **Compare before classify.** Rejected: known-failure markers that skip the observable.
- **One portable `.ssc` ABI, additive to legacy std APIs.** Rejected: returning Scala objects or
  breaking the existing JSON/YAML/content models in place.
- **One portable implementation across codegen targets.** Rejected: a separately maintained
  handwritten JS UniML parser.
- **M6 stable ids are sidecar state.** Rejected: changing canonical tree equality/serialization to
  embed session identities.
- **A correctness-only full-reparse fallback is honest but not incremental reuse.** Rejected:
  labelling full reparse as an incremental implementation.
- **Formatter output is a validated rewrite plan.** Rejected: mutating the CST or silently
  normalizing inert/unknown regions.
- **Document address units are explicit.** Rejected: conflating code points, UTF-16 indices, UTF-8
  bytes and bits under `offset`/`length`.
- **Legacy front removal follows zero fallback.** Rejected: deleting the delegate/oracle to make a
  metric appear green.
