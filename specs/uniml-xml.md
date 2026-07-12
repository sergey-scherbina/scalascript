# UniML XML dialect — XML 1.0 lossless profile

## Overview

The UniML XML dialect reads XML 1.0 documents as a lossless token/instruction stream and builds an
ordered concrete syntax tree with the common UniML VM. It preserves the XML declaration, DOCTYPE,
start/end/empty tags, exact QNames, namespace declarations, attribute quote/spelling/order, mixed
character data, references, CDATA sections, comments, processing instructions, and top-level
whitespace. A validated CST may be projected into the existing `scalascript.markup.Markup` semantic
model; facts that `Markup` cannot represent remain available only in the CST.

Normative sources:

- [XML 1.0, Fifth Edition](https://www.w3.org/TR/xml/)
- [Namespaces in XML 1.0, Third Edition](https://www.w3.org/TR/xml-names/)

The adapter is a non-validating, no-I/O processor. It checks the well-formedness rules covered below
but does not fetch external DTD subsets, schemas, stylesheets, or entities and never executes
processing instructions.

## Interface

Package: `scalascript.uniml.dialect.xml`.

```scala
object XmlDialect extends DialectAdapter:
  val id: String = "xml.1.0"
  override val aliases: Set[String] = Set("xml", "application/xml", "text/xml")

object Xml:
  def parse(source: SourceInput, limits: XmlLimits = XmlLimits.default): ParseResult
  def validate(result: ParseResult): XmlValidationResult
  def projectMarkup(result: ParseResult): XmlMarkupProjection

final case class XmlLimits(
  core: Limits = Limits.default,
  maxSourceCodePoints: Long = 64L * 1024 * 1024,
  maxNameCodePoints: Int = 4096,
  maxAttributeCodePoints: Int = 16 * 1024 * 1024,
  maxTextCodePoints: Int = 16 * 1024 * 1024,
  maxDoctypeCodePoints: Int = 1024 * 1024,
  maxAttributesPerElement: Int = 100_000,
)

final case class XmlValidationResult(
  roots: Vector[UniNode],
  diagnostics: Vector[Diagnostic],
  complete: Boolean,
)

final case class XmlMarkupProjection(
  document: Option[scalascript.markup.Markup.Doc],
  diagnostics: Vector[Diagnostic],
)
```

`Xml.parse` performs lexical and structural well-formedness checks required to build balanced
elements. `Xml.validate` adds namespace/expanded-name checks. `projectMarkup` validates first and
returns no `Markup.Doc` when any Error/Fatal diagnostic exists.

## Behavior

### Lossless XML CST

- [ ] A document with optional XML declaration, prolog Misc, optional DOCTYPE, exactly one root
      element, and trailing Misc produces exact source tokens in order with code-point spans.
- [ ] Start, end, and empty-element tags build balanced `xml.element` branches. The CST retains exact
      prefix/local spelling, attribute order, quote choice, whitespace, `=`, `>`, `/>`, and `</...>`.
- [ ] Nested elements and mixed content preserve text, predefined/numeric/general references, CDATA,
      comments, and processing instructions in their original order without executing or expanding
      source-backed constructs in the CST.
- [ ] XML declarations preserve version/encoding/standalone spelling and are accepted only at the
      beginning. XML 1.1 declarations are rejected under `xml.1.0`.
- [ ] DOCTYPE declarations, including a balanced internal subset, are retained as exact opaque CST
      tokens. External identifiers are data only; no SYSTEM/PUBLIC resource is opened.
- [ ] Documents with zero or multiple root elements, non-whitespace character data outside the root,
      mismatched/unclosed tags, duplicate raw attribute QNames, illegal XML characters/names,
      malformed references/comments/CDATA/PIs/declarations/DOCTYPE, or extra declaration/DOCTYPE
      positions are incomplete with stable diagnostics.

### Namespaces and attributes

- [ ] Namespace scopes are immutable per element: `xmlns` changes the default element namespace;
      `xmlns:p` binds prefixes; default namespace never applies to unprefixed attributes.
- [ ] Element and attribute QNames have at most one colon, non-empty prefix/local parts, and valid XML
      Name characters. Every used prefix is bound except reserved `xml`; `xmlns` is declaration-only.
- [ ] Reserved bindings follow Namespaces in XML: `xml` is fixed to its standard URI, `xmlns` cannot
      be rebound/used normally, other prefixes cannot bind the XML/XMLNS URIs, and prefixed undeclare
      is rejected in the XML Namespaces 1.0 profile.
- [ ] Attributes are unique by expanded name `(namespace URI, local name)`, not merely raw QName;
      namespace declarations themselves are unique by declared prefix.

### References and security

- [ ] `&lt;`, `&gt;`, `&amp;`, `&apos;`, `&quot;`, decimal `&#...;`, and hexadecimal `&#x...;` project to
      legal XML characters while their exact reference spellings stay in the CST.
- [ ] Other general entity references remain exact unresolved reference nodes. Parsing performs no
      entity expansion; `Markup` projection fails explicitly unless a future caller supplies a
      bounded resolver. Parameter entity references outside the opaque DOCTYPE are errors.
- [ ] Literal `<`/`&` in character/attribute data, `]]>` in ordinary character data, `--` inside a
      comment, `?>` misuse, `<` inside attribute values, invalid numeric code points, and raw unpaired
      surrogates are rejected.
- [ ] External entities, XInclude, schemas, XSLT, network, filesystem, environment, reflection, and
      code execution are absent from parse/validate/project code paths.

### Streaming, limits, and targets

- [ ] Tokens, instructions, CST, diagnostics, validation, and projection are identical for every
      `SourceChunk` boundary, including boundaries inside delimiters, QNames, references, CDATA,
      comments, PIs, DOCTYPE subsets, and Unicode surrogate pairs.
- [ ] The lexer retains one incomplete lexical construct plus completed token metadata; the structural
      processor and namespace validator use explicit stacks, never input-controlled recursion.
- [ ] Source/name/attribute/text/DOCTYPE plus core depth/node/token/diagnostic limits produce bounded
      fatal diagnostics rather than allocation, stack, or platform failures.
- [ ] Focused XML suites pass unchanged on JVM and Scala.js; `Markup` projection agrees with
      `PureMarkupCodec` on their shared secure subset.

## Token model

Stable token kinds:

| Kind | Examples | Channel |
|---|---|---|
| `xml.declaration` | `<?xml version="1.0"?>` | Syntax |
| `xml.doctype` | `<!DOCTYPE root [...]>` | Syntax |
| `xml.start-open` | `<` | Syntax |
| `xml.end-open` | `</` | Syntax |
| `xml.name` | `p:item`, `id` | Syntax |
| `xml.equals` | `=` | Syntax |
| `xml.attribute-value` | `'v'`, `"&amp;"` | Syntax |
| `xml.tag-close` | `>` | Syntax |
| `xml.empty-close` | `/>` | Syntax |
| `xml.text` | maximal ordinary character data | Syntax |
| `xml.reference` | `&amp;`, `&#10;`, `&name;` | Syntax |
| `xml.cdata` | `<![CDATA[...]]>` | Syntax |
| `xml.comment` | `<!--...-->` | Comment |
| `xml.pi` | `<?target data?>` | Syntax |
| `xml.whitespace` | whitespace inside markup or top-level Misc | Trivia |
| `xml.invalid` | maximal recoverable invalid construct | Error |

Inside element content, whitespace is `xml.text` because it is semantic character data. Inside tags
and outside the root it is `xml.whitespace`. Tokens remain maximal across chunk boundaries. End-of-line
normalization is a semantic projection concern: raw CR/CRLF spelling stays lossless in the CST.

## VM instruction mapping

The format does not add VM opcodes:

| Token/context | Instruction / role |
|---|---|
| root/nested `<` | `Open("xml.element", "document.root" | "content.child")` |
| element QName | `Emit(Some("element.name"))` |
| attribute QName | `Emit(Some("attribute.name"))` |
| `=` | `Emit(Some("attribute.equals"))` |
| quoted attribute value | `Emit(Some("attribute.value"))` |
| start `>` | `Emit(Some("start-tag.close"))` |
| `</` | `Emit(Some("end-tag.open"))` |
| end QName | `Emit(Some("end-tag.name"))` |
| end `>` | `Close(Some("xml.element"), Some("end-tag.close"))` |
| `/>` | `Close(Some("xml.element"), Some("empty-tag.close"))` |
| text/reference/CDATA/comment/PI | `Emit(Some("content.*"))` |
| declaration/DOCTYPE/top-level Misc | root `Emit(Some("document.*"))` |
| offending source token | `Report("uniml.xml.*", ...)` |

The `<` token opens an element before its QName is known; the structural processor records the QName
in its parallel element frame. On a normal end tag, `</` and the end QName are emitted into the current
element before the final `>` closes the UniML frame. A mismatched QName is diagnosed without closing a
different element. An empty-element `/>` closes the frame opened by the same tag.

## Lexical and structural states

The lexer distinguishes `Content`, `StartTag`, `EndTag`, and opaque constructs introduced by `<!` or
`<?`. It recognizes complete delimiters across chunks and scans quoted attribute values without
interpreting markup. Comments, CDATA, PI/declaration, and DOCTYPE are single exact tokens in M2;
DOCTYPE scanning balances `[...]` and quote delimiters so `>` inside an internal subset/literal does
not terminate it.

The structural processor is iterative. An element frame stores start QName, start-tag phase,
attribute triples, and namespace declarations. Document state tracks declaration/DOCTYPE/root/Misc
positions. Fatal XML well-formedness errors stop normal structural interpretation, but already
consumed source tokens remain available as partial/error CST data.

## Namespaces

Namespace validation is a separate pass over the balanced CST. Each element copies the immutable
parent binding map, applies its `xmlns` attributes, resolves its element QName and ordinary attributes,
and descends with that scope. The predefined binding is:

```text
xml -> http://www.w3.org/XML/1998/namespace
```

`xmlns` declarations are not ordinary projected attributes. Unprefixed attributes have no namespace.
The validator stores resolved QNames in projection-local data; it never rewrites source tokens.

## References and DTD policy

M2 recognizes reference syntax everywhere XML permits it. CST construction never expands a reference.
The semantic projection expands only the five predefined entity names and legal numeric character
references. Other entity names produce `uniml.xml.unresolved-entity` and block `Markup` projection.

DOCTYPE is retained and lexically balanced, and its root name/public/system identifiers may be
projected to `Markup.DocType`. Internal declarations are not interpreted in M2. This deliberately
means M2 is not a validating XML processor and cannot project documents whose semantic content
depends on internal/external entity replacement. A later bounded DTD module may add that capability
without changing CST compatibility.

## Markup projection

Projection reuses `scalascript.markup.Markup`:

- element names/attributes become resolved `Markup.QName`/`Markup.Attr`;
- character data plus supported references become `Markup.Text` with XML end-of-line normalization;
- CDATA, comments, and PIs become their matching `Markup` nodes;
- declaration and simple DOCTYPE metadata become `Markup.XmlDecl`/`Markup.DocType`;
- empty and explicit start/end forms both become `Markup.Element`.

`Markup.Doc` has no field for comments/PIs before the root, so projection reports
`uniml.xml.projection-lossy-prolog` warning when such nodes exist; the CST remains authoritative.
Comments/PIs after the root fit `Markup.Doc.trailing`. Attribute quote choice, whitespace, reference
spelling, empty-tag choice, namespace declaration spelling, and internal subset text are intentionally
not recoverable from `Markup`; callers needing them use the CST.

## Diagnostics

Stable codes include:

- `uniml.xml.invalid-character`, `uniml.xml.invalid-name`
- `uniml.xml.invalid-declaration`, `uniml.xml.declaration-position`
- `uniml.xml.invalid-doctype`, `uniml.xml.doctype-position`
- `uniml.xml.expected-name`, `uniml.xml.expected-equals`, `uniml.xml.expected-attribute-value`
- `uniml.xml.invalid-comment`, `uniml.xml.invalid-cdata`, `uniml.xml.invalid-pi`
- `uniml.xml.invalid-reference`, `uniml.xml.unresolved-entity`
- `uniml.xml.mismatched-end-tag`, `uniml.xml.unexpected-end-tag`, `uniml.xml.unexpected-eof`
- `uniml.xml.missing-root`, `uniml.xml.multiple-roots`, `uniml.xml.text-outside-root`
- `uniml.xml.duplicate-attribute`, `uniml.xml.duplicate-expanded-attribute`
- `uniml.xml.unbound-prefix`, `uniml.xml.invalid-namespace-binding`
- `uniml.xml.projection-invalid-cst`, `uniml.xml.projection-lossy-prolog`
- `uniml.xml.limit.source`, `.name`, `.attribute`, `.text`, `.doctype`

XML well-formedness violations are Error or Fatal. Projection loss notices are Warning. External
resource refusal is deterministic data handling, not an environmental exception.

## Module layout

```text
v1/lang/uniml-xml/
  src/main/scala/scalascript/uniml/dialect/xml/
    XmlDialect.scala
    XmlLexer.scala
    XmlStructure.scala
    XmlValidation.scala
    XmlMarkupProjection.scala
  src/test/scala/scalascript/uniml/dialect/xml/
```

`unimlXmlCross` uses `CrossType.Pure`; aliases are `unimlXml` and `unimlXmlJs`; artifact name is
`scalascript-uniml-xml`. Compile dependencies are only `unimlCross` and `markupCoreCross`.

## Out of scope

- XML validity checking against DTD declarations, XSD, RELAX NG, or Schematron.
- Loading or expanding external/internal custom entities in M2.
- XInclude, XPath, XQuery, XSLT, canonical XML, signatures, encryption, or HTML recovery.
- XML 1.1 and Namespaces 1.1 under the `xml.1.0` id.
- Byte-stream encoding detection; `SourceInput` is already-decoded Unicode text.
- Replacing the existing `MarkupCodec` parser/serializer.

## Decisions

- **Lossless CST remains authoritative** — chosen because `Markup` intentionally omits lexical facts.
  Rejected: parsing directly into a DOM and attempting to reconstruct source.
- **Non-validating, no-I/O M2** — chosen to prevent XXE/resource access and keep JVM/JS deterministic.
  Rejected: transparent SYSTEM/PUBLIC resolution.
- **Opaque DOCTYPE/internal subset** — chosen to preserve source safely without implementing a DTD
  engine in the XML lexer. Rejected: silently deleting DOCTYPE or expanding declarations partially.
- **Unresolved general entities block projection** — chosen to avoid inventing replacement text.
  Rejected: replacing unknown entities with empty strings or literal spellings.
- **Namespace validation after CST construction** — chosen so malformed bindings never destroy source
  evidence. Rejected: rewriting QName tokens during lexing.
- **Reuse `Markup` projection** — chosen to integrate with existing XML/XSLT APIs. Rejected: a second
  competing semantic XML DOM.

## Results

To be filled after implementation and verification.
