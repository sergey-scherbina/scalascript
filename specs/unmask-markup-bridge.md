# v2 Markup Bridge

## Overview

The v2 run lane must execute the documented markup/XSLT example instead of
silently producing empty output. The immediate production blocker is
`examples/xslt-transform.ssc`: after `scripts/sbtc "installBin"`,
`bin/ssc run --v2 examples/xslt-transform.ssc` currently exits 0 with empty
stdout, even though `README.md` and `docs/user-guide.md` describe XML markup
and `MarkupCodec.transform` as a user-facing JVM/interpreter feature.

This slice bridges the minimal markup runtime surface required by that example:
`xml"""..."""`, `MarkupCodec.default.transform`, `PureMarkupCodec.serialize`,
`SerializeOpts(...)`, `TransformError.message`, `Map[String, String]` XSLT
params, and `Either` `Right`/`Left` pattern matching.

## Interface

- `xml"..."/xml"""..."""` in v2 `scala` fences evaluates to a markup document
  backed by `scalascript.markup.Markup.Doc`.
- `MarkupCodec.default.transform(doc, stylesheet)` returns
  `Right(Markup.Doc)` on success or `Left(TransformError(message))` on failure.
- `MarkupCodec.default.transform(doc, stylesheet, params)` accepts
  `Map[String, String]` params and passes them to the JVM XSLT engine.
- `PureMarkupCodec.serialize(doc, opts)` serializes a bridged `Markup.Doc`.
- `SerializeOpts(pretty = ..., indent = ..., omitXmlDecl = ...)` is available
  from v2, including named arguments and default values.
- `TransformError.message` is readable after `case Left(err)`.

## Behavior

- [ ] `bin/ssc run --v2 examples/xslt-transform.ssc` prints the four documented
      sections instead of empty stdout.
- [ ] The identity transform keeps the `<catalog>` document and its two
      `<book>` elements.
- [ ] The rename transform returns a `<report>` document with `<item>` children.
- [ ] The HTML transform honors `Map("currency" -> "EUR")`.
- [ ] A malformed stylesheet returns `Left(TransformError(...))`, and
      `err.message` is available in pattern-match code.
- [ ] `SerializeOpts(omitXmlDecl = true, pretty = true)` is reordered through
      named-argument handling and suppresses the XML declaration.

## Out of scope

- JS, Rust, Wasm, and browser XSLT support. The documented capability is
  JVM/interpreter-only; unsupported non-JVM lanes remain future work.
- Full ` ```xml ``` ` fenced-block section binding as `<section>.xml`.
  This slice covers the inline `xml"""..."""` surface used by the production
  example.
- Expression interpolation inside XML literals beyond existing v2 string
  interpolation safety. The current example uses literal XML bodies.
- Reworking v1 behavior. The current v1 command rejects this example with
  `Unknown interpolator 'xml'`; the v2 production target is the documented
  `README.md`/`docs/user-guide.md` behavior, not the stale v1 failure.
- A new core intrinsic. The bridge should reuse the existing `markup-core`
  implementation rather than adding markup logic to the v2 runtime core.

## Design

The bridge should keep v2's own runtime small and delegate XML/XSLT work to the
existing JVM markup modules:

- Add `markupCore` to the `v2PluginBridge` build dependencies so the bridge can
  import `scalascript.markup.*`.
- Teach `FrontendBridge` enough about the built-in `xml` interpolator to lower
  it to a v2 bridge call instead of treating it as plain string concatenation.
- Register the markup bridge surface in `PluginBridge` as ordinary v2 globals
  and method objects:
  - `xml(raw: String): ForeignV(Markup.Doc)` parses via `PureMarkupCodec`.
  - `MarkupCodec.default` returns a method object exposing variadic
    `transform`.
  - `PureMarkupCodec` exposes variadic `serialize`.
  - `SerializeOpts` constructs the Scala case class with defaults.
  - transform failures become `DataV("Left", Vector(DataV("TransformError",
    Vector(StrV(message)))))`; successful transforms become `DataV("Right",
    Vector(ForeignV(doc)))`.
- Register field names for bridge-owned ADTs that v2 pattern matching or field
  access needs, especially `TransformError.message`.

## Decisions

- **Reuse JVM markup-core** — chosen because it already contains the
  zero-dependency XML parser/serializer plus `JvmMarkupCodec`/`XsltTransformer`.
  Rejected: reimplementing XML and XSLT in v2 core, which would duplicate a
  working std module and violate the "reuse, don't invent" project rule.
- **Represent `Markup.Doc` as a v2 `ForeignV`** — chosen because this slice only
  needs to pass documents between markup bridge calls and serialize them.
  Rejected: duplicating the full `Markup.*` ADT in v2, which is larger than the
  production blocker and would widen the blast radius.
- **Bridge the documented example first** — chosen because v2 production needs
  user-facing examples to be honest. Rejected: treating the stale v1
  `Unknown interpolator 'xml'` failure as the oracle.

## Results

Baseline before implementation, after staging the CLI with
`scripts/sbtc "installBin"`:

```text
bin/ssc run --v2 examples/xslt-transform.ssc
# rc=0, stdout is empty

bin/ssc run --v1 examples/xslt-transform.ssc
# rc=1, Unknown interpolator 'xml'
```

Fill this section after verification with focused tests, the real example
output shape, affected conformance, and any remaining follow-up.
