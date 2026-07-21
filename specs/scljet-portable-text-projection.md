# SclJet — portable code-points / UTF-16-units → String projection

Status: **implementation**
Module: `scljet/text.ssc`
Package: `scljet`

## Overview

SclJet decodes a SQLite text field into a `DecodedText` whose payload is a list
of Unicode **code points** plus the raw **encoded bytes** it came from (see
`scljet/record.ssc`). To hand that field to the SQL layer it must become an
`SqlText(value: String)`. This spec defines the single, named, target-neutral
API that turns code points (or raw UTF-16 code units) into a `String`, and the
`DecodedText → SqlText` projection built on top of it.

Two properties are non-negotiable:

1. **Target-neutral.** The construction uses only `Int.toChar` + `String`
   concatenation, which behaves identically on the v1 interpreter (tree-walk,
   bytecode VM, and ASM JIT tiers) and on the JS backend. No host decoder
   (`TextDecoder`, `new String(bytes, charset)`, `String.fromCharCode`,
   `JSON.parse`, `Charset`, `StandardCharsets`, `decodeURIComponent`) is ever
   used — the String is built arithmetically from integers.
2. **Raw bytes stay the source of truth (GIGO).** The projection is a *decoded
   view* for query/compare/print. `DecodedText.encoded` (the original bytes)
   is never mutated and remains the storage round-trip source. Malformed input
   already produced U+FFFD replacement code points at decode time; the
   projection faithfully carries those through — garbage in, garbage out — while
   the stored bytes are preserved verbatim.

## Why a dedicated module

Before this change two ad-hoc, byte-identical copies of the same fold existed —
`codepointsToString` (`scljet/mutate.ssc`) and `cpToString` (`scljet/sql.ssc`).
Both truncated astral code points (`Int.toChar` keeps only the low 16 bits), so
any code point ≥ U+10000 rendered as a garbage BMP character. Consolidating them
into one spec'd module removes the duplication (design principle: *reuse, don't
invent*) and fixes the astral bug in one place.

## API (`scljet/text.ssc`)

```
def utf16UnitsToString(units: List[Int]): String
```
Fold a list of UTF-16 **code units** (each a 16-bit value `0..65535`) into a
`String`, one `unit.toChar.toString` per unit. This is the lowest-level,
fully target-neutral primitive: on every backend a `String` *is* a sequence of
UTF-16 code units, so `.toChar` on a 16-bit int yields exactly one code unit.

```
def codePointToUtf16(codePoint: Int): List[Int]
```
Convert one Unicode **code point** to its UTF-16 code unit(s):

- `0 .. 65535` (BMP)     → one unit `[codePoint]` (surrogate values in
  `0xD800..0xDFFF` pass through unchanged, matching the historical fold).
- `65536 .. 1114111` (astral) → a surrogate pair
  `[0xD800 + (v >> 10), 0xDC00 + (v & 0x3FF)]` where `v = codePoint - 65536`.
- anything else (negative / above U+10FFFF) → `[65533]` (U+FFFD).

This is the **only** place surrogate arithmetic lives.

```
def codePointsToString(codePoints: List[Int]): String
```
The general projection: map each code point through `codePointToUtf16` and fold
the resulting units. Astral-safe. For all-BMP input it is byte-identical to the
old naive fold, so existing (BMP) corpus output is unchanged.

```
def decodedTextToSqlText(text: DecodedText): SqlText
```
Project a decoded SQLite text field to `SqlText(codePointsToString(text.codePoints))`.
The `text.encoded` bytes are untouched.

## Callers

- `scljet/mutate.ssc` `fieldToValue` projects a text `RecordField` via
  `decodedTextToSqlText`. `codepointsToString` is removed; its sole caller now
  goes through the shared API.
- `scljet/sql.ssc` `cpToString` delegates to `codePointsToString` (name kept so
  its ~15 call sites are untouched; implementation shared and astral-safe).
- Both names re-exported from the `scljet/index.ssc` barrel.

## Parity obligation

The projection must produce the identical `String` (observed as `.length` and
per-index `.charAt(i).toInt`) on:

- v1 tree-walk fallback  (`SSC_JIT_BYTECODE=off SSC_FASTTIER=off`)
- v1 bytecode VM         (default)
- v1 ASM JIT             (`SSC_JIT_BACKEND=asm`)
- JS backend             (`emit-js | node`)

Proven by the conformance case `scljet-portable-text` (`backends: [int, js]`)
and the runnable example `examples/scljet-text-projection.ssc`, both decoding
UTF-8 bytes containing an ASCII, a 2-byte, and a 4-byte (astral) character and
asserting the reconstructed UTF-16 units.

## Non-goals

- The **encode** direction (`String → UTF-8 bytes`, `scljet/write.ssc`
  `utf8Bytes`) is out of scope; its astral follow-up is tracked there.
- The v2 native front is verified separately; if it renders dynamic chars as
  decimal numbers the gap is filed in `BUGS.md` and this task stays scoped to
  the v1 interpreter tiers + JS.
