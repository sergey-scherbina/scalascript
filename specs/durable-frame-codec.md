# Durable-frame byte codec — canonical §9.1 format (Part 1)

> Part 1 of `saved-continuation-format`. Defines the canonical, deterministic,
> bounded byte encoding for the baseline `DurableValue` frame algebra
> ([`control-interoperability.md`](control-interoperability.md) §9.1) and its
> reference implementation `DurableCodec[S]` in the Scala control leaf. This is the
> **cross-lane authoritative format**: every lane (scala, js, native, …) that encodes
> a `DurableValue` frame MUST produce byte-identical output for the same value, so a
> capsule frozen on one lane restores on another (§10). The reference row proves the
> semantics; it does not own the laws.
>
> Out of scope here (follow-on slices): the capsule envelope + `frameDigest`/signature
> (§10), `DurableRef` (§9.2), explicit graph codecs for aliased/cyclic state (§9.3),
> nominal versioned-schema identity, canonical-key maps/sets, and the
> Portable/ExactArtifact runners. Part 1 is the value codec those all build on.

## 1. Why this exists

The keystone (`specs/durable-continuation-save-run.md`) made `save()`/`run()` work
in-process with a `DurableValue[S]` that only did an in-memory `snapshot(S): S`. That
is enough for same-process multi-shot but cannot cross a process, machine, or restart
because the frame is never reduced to bytes. `DurableCodec[S]` adds the canonical byte
encoding; `snapshot` is then defined as the round-trip `decode ∘ encode`, so the §8.2
snapshot law is backed by real serialization rather than a structural copy.

## 2. Canonical wire format

Deterministic and bounded. All multi-byte integers are **big-endian**. A codec is
**typed** — the caller supplies `DurableCodec[S]`, so structure is known and fields are
written positionally (no per-field type tag). Every encoding is **self-delimiting**:
fixed-width scalars, or a length prefix for variable-width data, so positional
composition decodes unambiguously.

| Type | Encoding |
|---|---|
| `Unit` | 0 bytes |
| `Boolean` | 1 byte: `0x00` false, `0x01` true |
| `Int` (32-bit) | 4 bytes, two's-complement big-endian |
| `Long` / ssc `I64` | 8 bytes, two's-complement big-endian |
| `BigInt` | `u32` byte-count `n` + `n` bytes of minimal two's-complement big-endian magnitude (`BigInteger.toByteArray`); the value `0` encodes as `n=1, [0x00]` |
| `Double` / `F64` | 8 bytes = `doubleToRawLongBits`, big-endian. Signed zero and every finite/infinite value keep their exact bits (`-0.0` stays `-0.0`). **NaN policy: any NaN normalizes to the single canonical `0x7ff8000000000000`.** Payloads are not preserved, because a JS engine cannot round-trip a NaN payload through a `Number`; canonicalizing on every lane is what makes the encoding byte-identical cross-lane. |
| `String` | `u32` UTF-8 byte length + those UTF-8 bytes |
| `Bytes` | `u32` length + raw bytes |
| product / `pair(a, b)` | `encode(a)` then `encode(b)`, in field order |
| sum / `either(a, b)` | 1 tag byte (`0x00` left / `0x01` right) + the chosen branch's encoding |
| `list(a)` | `u32` element count + each element encoded in order |
| `map(k, v)` | `u32` entry count + entries sorted by the unsigned lexicographic order of each key's own encoding — so the bytes are independent of insertion order (§9.1). Decode rejects keys not in strictly ascending canonical order. |

`u32` is an unsigned 32-bit big-endian count; a length or count above `2^31−1` is
rejected (bounded). Decoding is **exact**: `decode` fails if input is truncated or has
trailing bytes, if a tag is unknown, or if a declared length exceeds the remaining
input. Failures are typed `DurableDecodeError`, never a partial value.

## 3. Reference API (`scalascript.control`)

```scala
final class DurableBytes private[control] (...):
  def length: Int
  def toArray: Array[Byte]          // defensive copy
  // value equality + stable hex toString for vectors

trait DurableCodec[S] extends DurableValue[S]:
  def encode(value: S): DurableBytes
  def decode(bytes: DurableBytes): S
  // inherited: snapshot(value) = decode(encode(value))

object DurableCodec:
  val unit: DurableCodec[Unit]
  val boolean: DurableCodec[Boolean]
  val int: DurableCodec[Int]
  val long: DurableCodec[Long]
  val bigInt: DurableCodec[BigInt]
  val double: DurableCodec[Double]
  val string: DurableCodec[String]
  val bytes: DurableCodec[DurableBytes]
  def pair[A, B](a: DurableCodec[A], b: DurableCodec[B]): DurableCodec[(A, B)]
  def either[A, B](a: DurableCodec[A], b: DurableCodec[B]): DurableCodec[Either[A, B]]
  def list[A](a: DurableCodec[A]): DurableCodec[List[A]]
  def map[K, V](k: DurableCodec[K], v: DurableCodec[V]): DurableCodec[Map[K, V]]
  def imap[A, B](a: DurableCodec[A])(to: A => B)(from: B => A): DurableCodec[B]
```

`imap` is how a caller builds a codec for a nominal type (case class ↔ tuple) without
reflection — the same "typed defunctionalized builder supplies evidence" principle as
`Continuation.savable`. Because `DurableCodec[S] <: DurableValue[S]`, any
`DurableCodec` is directly usable as the `savable` codec, and the saved frame then
genuinely serializes on every `save`/`run`.

## 4. Laws / behavior checklist

- [ ] round-trip: `decode(encode(v)) == v` for every scalar incl. `-0.0`, a NaN with a
      non-zero payload, `Long.MinValue`/`MaxValue`, negative & zero `BigInt`, empty and
      unicode `String`, empty `List`.
- [ ] canonical/deterministic: `encode(v)` is byte-identical across repeated calls and
      independent of construction path; equal values ⇒ equal bytes.
- [ ] float canonical form: `encode(-0.0) != encode(0.0)`; every NaN encodes to
      `0x7ff8000000000000` and decodes back to a NaN.
- [ ] bounded/exact: `decode` rejects truncated input, trailing bytes, an unknown sum
      tag, and a length prefix larger than the remaining input — each a typed
      `DurableDecodeError`.
- [ ] integration: a `Continuation.savable` built with a `DurableCodec`-backed state
      round-trips through bytes on `save`/`run` and still honors multi-shot + no-replay +
      the snapshot law (mutation of a mutable field decoded per run stays isolated).
- [ ] ABI gate stays green; no forbidden runtime reference leaks from the new public
      surface; `DurableBytes` exposes no shared mutable array.

## 4a. Golden vectors (cross-lane byte identity)

The format is authoritative only if every lane produces the *same* bytes. Rather than
a live cross-process harness, both lanes assert one shared golden hex table — if the
Scala `DurableCodecTest` and the JS `control.test.js` both pass it, the encodings are
byte-identical. A representative subset (full table in the tests):

| value | codec | hex |
|---|---|---|
| `true` | `boolean` | `01` |
| `-1` | `int` | `ffffffff` |
| `-1` (64-bit) | `long` | `ffffffffffffffff` |
| `1.5` | `double` | `3ff8000000000000` |
| `-0.0` | `double` | `8000000000000000` |
| `NaN` | `double` | `7ff8000000000000` |
| `"é"` | `string` | `00000002c3a9` |
| `128` | `bigInt` | `000000020080` |
| `-1` | `bigInt` | `00000001ff` |
| `List(1,2)` | `list(int)` | `000000020000000100000002` |
| `Right("A")` | `either(int,string)` | `010000000141` |

Changing the wire format means changing both tables and this spec together.

## 5. Follow-on (queued in SPRINT)

Part 2 (landed, `specs/durable-capsule-envelope.md`): the versioned capsule envelope +
`frameDigest` + `ResumePoint.freeze/restore`. Remaining: `DurableRef` (§9.2), canonical-key
maps and nominal versioned schema (§9.1), graph codecs (§9.3), and the JS-lane mirror of the
**capsule** (the codec mirror landed here). The Portable/ExactArtifact runners and cross-lane
capsule vectors ride on those.
