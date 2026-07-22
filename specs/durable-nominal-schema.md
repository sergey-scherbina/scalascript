# Durable nominal versioned-schema codec — §9.1 schema identity

> A follow-on slice of [`durable-frame-codec.md`](durable-frame-codec.md) (Part 1).
> Adds one combinator, `DurableCodec.schema`, that stamps a value's canonical bytes
> with a **nominal schema identity** (a name + an integer version) and rejects, on
> decode, any bytes written under a different name or version. This is the
> "immutable nominal … data with versioned schema identity" of
> [`control-interoperability.md`](control-interoperability.md) §9.1 — the last item
> of the §9.1 baseline value algebra. Like the rest of Part 1 it is
> **cross-lane authoritative**: the Scala (`v2/host/scala/control`) and JS
> (`v2/host/js/control`) lanes MUST produce byte-identical output, proven by a shared
> golden hex vector.

## 1. Why this exists

`imap` (Part 1) builds a codec for a nominal type from its structural image, but the
bytes it produces are indistinguishable from the underlying structural codec's — a
value written as one nominal type decodes without complaint against any other codec
of the same shape, and a schema that evolves (a field added, a meaning changed) has
no way to reject bytes written under the old shape. `schema` closes that gap by
prefixing the value with a self-describing `(schemaId, version)` header and checking
it on decode, so a capsule frozen under one schema name/version is rejected when
restored against a different one instead of silently mis-decoding.

## 2. Wire format

`schema(schemaId, version, codec)` encodes as the positional concatenation

```
string(schemaId) ++ int(version) ++ codec.encode(value)
```

reusing the existing Part 1 encodings verbatim — `DurableCodec.string` for the
schemaId (u32 UTF-8 length + bytes), `DurableCodec.int` for the version (4-byte
big-endian two's-complement), then the wrapped codec's own bytes. No new primitive
is introduced; the header is two existing scalars. The encoding stays deterministic,
bounded, and self-delimiting.

Add to the §2 format table of `durable-frame-codec.md`:

| Type | Encoding |
|---|---|
| `schema(id, ver, a)` | `string(id)` ++ `int(ver)` ++ `a`'s encoding; decode verifies id and ver match, else `DurableDecodeError` |

## 3. Decode / mismatch rules

Decode reads the schemaId (String) then the version (Int), then:

- if the decoded `schemaId` differs from the codec's expected `schemaId`, throw
  `DurableDecodeError("schema identity mismatch: expected '<expected>', got '<got>'")`;
- else if the decoded version differs from the codec's expected version, throw
  `DurableDecodeError("schema version mismatch: expected <expected>, got <got>")`;
- else read and return the wrapped `codec`'s value.

Identity is checked before version so a wrong name is reported as a name mismatch
even when the versions happen to differ too. Both lanes emit byte-identical error
messages. As with every Part 1 decode, failures are typed `DurableDecodeError`,
never a partial value; trailing-byte / truncation checks are inherited from
`decode`.

## 4. Reference API

Scala (`object DurableCodec`, add to the §3 API list of `durable-frame-codec.md`):

```scala
def schema[S](schemaId: String, version: Int, codec: DurableCodec[S]): DurableCodec[S]
```

JS (`DurableCodec` frozen object) + `index.d.ts`:

```ts
schema<S>(schemaId: string, version: number, codec: DurableCodec<S>): DurableCodec<S>
```

`schema` is a method on the existing `DurableCodec` object — not a new package
export — so the JS export inventory is unchanged.

## 5. Golden vector (cross-lane byte identity)

Both lanes assert the exact hex of

```
schema("Point", 1, pair(int, int)).encode((3, 4))
```

which is `string("Point")` (`00000005` + `506f696e74`) ++ `int(1)` (`00000001`) ++
`pair(int,int)((3,4))` (`00000003` ++ `00000004`):

| value | codec | hex |
|---|---|---|
| `(3, 4)` | `schema("Point", 1, pair(int, int))` | `00000005506f696e74000000010000000300000004` |

Matching hex on the Scala (`.toString`) and JS (`.toHex()`) lanes proves byte
identity without a live cross-process harness. Changing this means changing both
test tables and this spec together.

## 6. Behavior checklist

- [ ] round-trip: `schema("Point", 1, pair(int, int)).decode(encode((3, 4))) == (3, 4)`.
- [ ] schema-identity mismatch: bytes written under `schema("Point", 1, …)` decoded
      with `schema("Line", 1, …)` throw `DurableDecodeError`.
- [ ] version mismatch: bytes written under `schema("Point", 1, …)` decoded with
      `schema("Point", 2, …)` throw `DurableDecodeError`.
- [ ] golden: the hex above matches on both lanes.
- [ ] ABI gate stays green; the new public `schema` method references only control
      types.
