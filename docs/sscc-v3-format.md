# `.sscc` v3 Binary Format

`.sscc` files are compiled representations of `.ssc` source modules.
Version 3 replaces the v2 CBOR+gzip layout with a flat token stream over a patricia-trie
string dictionary.

---

## File layout

```
bytes  0-3   magic        = "sscc"  (0x73 0x73 0x63 0x63)
byte   4     version      = 0x03
byte   5     compression  = 0x00 (no compression) | 0x01 (gzip)
bytes  6-9   crc32        big-endian CRC32 of payload bytes (bytes 10+)
bytes 10+    payload      (decompressed before parsing if compression != 0)
```

### Payload

```
payload = trieSection streamSection extension*

trieSection  = trieLenBE32(4 bytes)  trieBytes[trieLen]
streamSection= token*   (self-delimited by ModuleEnd sentinel)
extension    = DocumentBlob
```

All integers inside the stream and trie are unsigned LEB128 varints unless noted
otherwise.  Big-endian 32-bit integers are used only for `trieLen` and raw-blob
lengths.

---

## Patricia-trie dictionary

Every string in the stream is stored as a varint ID that indexes into the trie
dictionary.  The trie encodes all unique strings with shared-prefix compression.

### Trie encoding

```
trie = nodeCount:varint  node[nodeCount]

node = edgeLen:varint
       edgeBytes[edgeLen]          UTF-8 bytes of this edge label
       terminal:byte               0x00=non-terminal  0x01=terminal
       [terminalId:varint]         present only when terminal=0x01
       childCount:varint
       child[childCount]:
         firstByte:byte            first byte of child edge (routing key)
         childIdx:varint           DFS pre-order index of child node
```

Nodes are numbered in DFS pre-order; root = index 0.  The decoder reconstructs an
`Array[String]` indexed by `terminalId`.

---

## Stream token kinds

Token `kind` values 0-15 cover module structure and the optional content
extension. Kind values 16+ are reserved for future sections (YAML events, Phase
C).

| Kind | Name           | Payload |
|------|----------------|---------|
| 0    | ModuleStart    | spanOpt |
| 1    | ModuleEnd      | — |
| 2    | ManifestBlob   | len:BE32, bytes[len]  (CBOR-pickled Manifest) |
| 3    | SectionStart   | level:varint, headingRef:varint, headingSpanOpt, sectionSpanOpt |
| 4    | SectionEnd     | — |
| 5    | Prose          | textRef:varint, spanOpt |
| 6    | CodeStart      | langRef:varint, lineOffset:varint, attrCount:varint, (keyRef valRef)*attrCount, spanOpt |
| 7    | CodeBlob       | len:BE32, utf8bytes[len]  (raw source, non-parseable lang) |
| 8    | CodeEnd        | hasError:byte, [msgRef lineV colV snippetRef if hasError=1] |
| 9    | Import         | pathRef:varint, bindingCount:varint, binding*bindingCount, spanOpt |
| 10   | ListStart      | ordered:byte, spanOpt |
| 11   | ListItemStart  | contentRef:varint, spanOpt |
| 12   | ListItemEnd    | — |
| 13   | ListEnd        | — |
| 14   | CodeSmTokens   | tokenCount:varint, smToken*tokenCount  (scalameta token stream) |
| 15   | DocumentBlob   | len:BE32, bytes[len]  (CBOR-pickled `DocumentContent`, after `ModuleEnd`) |

**Import binding** = nameRef:varint, hasAlias:byte, [aliasRef if hasAlias=1],
                     hasFrom:byte, [fromRef if hasFrom=1], spanOpt

**spanOpt** = 0x00 (absent) | 0x01 startLine startCol startOffset endLine endCol endOffset (all varints)

### Structural grammar

```
stream   = ModuleStart spanOpt ManifestBlob? section* ModuleEnd extension*
section  = SectionStart level headRef hSpan sSpan
           content*
           section*
           SectionEnd
content  = Prose textRef spanOpt
         | CodeStart … (CodeBlob | CodeSmTokens) CodeEnd hasError …
         | Import …
         | ListStart … ListItemStart* ListEnd
extension = DocumentBlob len bytes
```

`DocumentBlob` is optional and appears only after `ModuleEnd`. It carries the
semantic Markdown-hosted `DocumentContent` snapshot used by `std/content` and
frontend toolkit lowering. Because the executable stream is self-delimited,
older v3 readers stop at `ModuleEnd` and ignore the trailing blob.

---

## Scalameta token sub-stream (CodeSmTokens)

For parseable code blocks (`lang = "scalascript"`), the preprocessor
(`PreprocessorRegistry.applyAll`) runs at **write-time**.  The resulting
vanilla-Scala 3 source is tokenised with scalameta and stored as a token
sub-stream.  At read-time the source is reconstructed by concatenating token
texts; scalameta re-parses the block without calling the preprocessor.

Each `smToken` in the sub-stream:

```
smToken = kind:varint  [dictRef:varint]
```

`dictRef` is present only for _variable-text_ kinds (identifiers, string
literals, comments, etc.).  _Fixed-text_ kinds (keywords, single-character
punctuation, whitespace) carry no payload; their text is implied by the kind
number.

### Sm kind table

| Kind | Name | Text | Notes |
|------|------|------|-------|
| 0 | Ident | — | dict-ref |
| 1 | Space | `" "` | fixed |
| 2 | LF | `"\n"` | fixed |
| 3 | Dot | `"."` | fixed |
| 4 | Comma | `","` | fixed |
| 5 | Colon | `":"` | fixed |
| 6 | LeftParen | `"("` | fixed |
| 7 | RightParen | `")"` | fixed |
| 8 | LeftBrace | `"{"` | fixed |
| 9 | RightBrace | `"}"` | fixed |
| 10 | LeftBracket | `"["` | fixed |
| 11 | RightBracket | `"]"` | fixed |
| 12 | KwDef | `"def"` | fixed |
| 13 | KwVal | `"val"` | fixed |
| 14 | KwVar | `"var"` | fixed |
| 15 | ConstInt | — | dict-ref |
| 16 | ConstString | — | dict-ref |
| 17 | Comment | — | dict-ref |
| 18 | Equals | `"="` | fixed |
| 19 | FunctionArrow | `"=>"` | fixed |
| 20 | RightArrow | `"<-"` | fixed |
| 21 | At | `"@"` | fixed |
| 22 | Hash | `"#"` | fixed |
| 23 | Underscore | `"_"` | fixed |
| 24 | KwCase | `"case"` | fixed |
| 25 | KwClass | `"class"` | fixed |
| 26 | KwObject | `"object"` | fixed |
| 27 | KwTrait | `"trait"` | fixed |
| 28 | KwIf | `"if"` | fixed |
| 29 | KwElse | `"else"` | fixed |
| 30 | KwMatch | `"match"` | fixed |
| 31 | KwReturn | `"return"` | fixed |
| 32 | KwNew | `"new"` | fixed |
| 33 | KwImport | `"import"` | fixed |
| 34 | KwType | `"type"` | fixed |
| 35 | KwExtends | `"extends"` | fixed |
| 36 | KwFor | `"for"` | fixed |
| 37 | KwWith | `"with"` | fixed |
| 38 | EOF | `""` | fixed |
| 39 | BOF | `""` | fixed |
| 40 | Semicolon | `";"` | fixed |
| 41 | ConstIntXL | — | dict-ref |
| 42 | InterpolId | — | dict-ref |
| 43 | InterpolStart | — | dict-ref |
| 44 | InterpolPart | — | dict-ref |
| 45 | InterpolEnd | — | dict-ref |
| 46 | InterpolSpliceStart | — | dict-ref |
| 47 | ConstLong | — | dict-ref |
| 48 | ConstFloat | — | dict-ref |
| 49 | ConstFloatXL | — | dict-ref |
| 50 | ConstDouble | — | dict-ref |
| 51 | ConstChar | — | dict-ref |
| 52 | ConstSymbol | — | dict-ref |
| 53 | InterpolSpliceEnd | — | dict-ref |
| 54 | Ellipsis | `"..."` | fixed |
| 55 | TypeLambdaArrow | `"=>>"` | fixed |
| 56 | ContextArrow | `"?=>"` | fixed |
| 57 | Subtype | `"<:"` | fixed |
| 58 | Supertype | `">:"` | fixed |
| 59 | Viewbound | `"<%"` | fixed |
| 60–86 | KwAbstract … KwYield | fixed keywords | fixed (see source for full list) |
| 87 | Tab | `"\t"` | fixed |
| 88 | CR | `"\r"` | fixed |
| 89 | CRLF | `"\r\n"` | fixed |
| 90 | FF | `"\f"` | fixed |
| 91 | Indent | `""` | fixed (scalameta indentation token) |
| 92 | Outdent | `""` | fixed |
| 93 | Symbolic | — | dict-ref (abstract supertype catch-all, unused in practice) |
| 94 | HSpace | — | dict-ref (abstract, unused in practice) |
| 95 | EOL | — | dict-ref (abstract, unused in practice) |
| 96–103 | LFLF … MultiToken | — | dict-ref |
| 104–118 | Invalid … XmlSpliceEnd | — | dict-ref (various rare/private tokens) |
| 119 | Unknown | — | dict-ref (any token not in SmKindMap: private[meta] types etc.) |

**Class-map dispatch**: the writer uses a `HashMap[Class[?], Int]` keyed by
`tok.getClass`.  This avoids scalameta 4.17's type-pattern ambiguity where abstract
supertypes (`T.Symbolic`, `T.HSpace`, `T.EOL`, `T.MultiToken`) shadow their concrete
subtypes.  Six `private[meta]` classes (`T.Invalid`, `T.Unquote`, `T.MacroSplice`,
`T.LFLF`, `T.InfixLF`, `T.Ellipsis`) are absent from the map and fall to `Sm.Unknown`.

---

## Backward compatibility

`SsccFormat.read` dispatches on the `version` byte:

- `0x01` / `0x02` → v2 CBOR reader (unchanged)
- `0x03` → v3 token-stream reader (this document)

v3 reader still handles `CodeBlob` (kind 7) for non-parseable blocks and as a
fallback when scalameta tokenisation fails.

The optional trailing `DocumentBlob` is backward-compatible at the executable
module level: old v3 readers ignore it, and new readers accept old files where
the blob is absent. A file without the blob loads with `Module.document = None`.

---

## Versioning policy

Any new structural kind inside the executable stream, sm-token kind, or
YAML-event kind (Phase C) is a breaking change to the v3 format. Such additions
bump the minor byte planned for the header (reserved; not yet emitted). Optional
trailing extensions after `ModuleEnd` are backward-compatible only when old
readers can ignore them without parsing the extension. Readers encountering an
unknown minor version return an error rather than silently misinterpreting the
stream.

---

## ABI note: preprocessor dependency

The scalameta token sub-stream stores _post-preprocessor_ source.  A `.sscc` v3 file
therefore encodes the exact set of preprocessors active at write-time (including any
plugin-provided ones).  Reading the file with a different plugin set will still
reconstruct a syntactically valid Scala 3 source, but the semantics may differ from
the original `.ssc` text.  This is intentional: `.sscc` is an opaque compiled
artifact, not a lossless archive of the source.
