# scljet address — every value has an address

Status: **spec, first slice**
Module: `scljet/address.ssc`
Depends on: `specs/scljet.md` (engine)

## The model

Every value SclJet can reach has an **address**. An address is the **link between a logical
and a physical location** — both are true at once, and the link between them is what the
address *is*:

```
logical:   emp/7/name          the table, the row, the column
physical:  page 4, offset 231, 3 bytes
```

Neither half alone is the address. The logical half says what the value *means*; the physical
half says where the bits *are*. Keeping the two connected — and never reporting one as the
other — is the whole job.

## The protocol

One shape, two directions:

```
read      address                  →  (type, value)
write     (address, type, value)
```

That triple — address, type, value — is the elementary unit. A write is an update packet; a
read is the same triple with type and value left to be filled in by the answer.

## Type

The type is the format's own type where we know it, and the raw extent where we do not:

| Case | Type |
|---|---|
| we know the format's type | that type — SQLite `INTEGER`/`REAL`/`TEXT`/`BLOB`/`NULL` |
| we do not | `Raw(n)` — n bits, exactly as stored |

`Raw(n)` is not a failure. Knowing the extent is knowing something true, and it is always
enough to hand the bits back unchanged. There is no universal type above this and no coercion:
we never claim a value means more than we know it means.

Where even the extent is unknown, we refuse rather than guess. (SQLite: serial types 10 and 11
carry no length, which is why a well-formed database never contains them; `record.ssc` already
rejects them.)

## Addressing SQLite

```
<table>/<rowid>/<column>
```

- `<table>` — a name from `sqlite_schema`.
- `<rowid>` — the row's identity.
- `<column>` — a column name from the table's `CREATE TABLE`.

Resolution walks the existing engine: `findTable` → root page → B-tree seek by rowid → the
record's field for that column. The logical half is the path above; the physical half is the
(page, cell, field offset, length) that walk lands on.

### The link is not the identity function

An `INTEGER PRIMARY KEY` column is an **alias for the rowid**: real SQLite stores NULL in the
record and keeps the value in the rowid. So for `emp/7/id`:

```
physical:  the record's field  →  NULL
logical:   the value           →  7      (it lives in the rowid)
```

Both statements are true. Reporting the physical bit as the logical value is exactly the bug
`BUGS.md → scljet-ipk-rowid-alias-not-substituted` (we returned 0). This is the canonical case
for this spec: an address whose two halves disagree, and which is only correct when the link
between them is honoured.

### Stability

Stability is a property of an address, like its two halves — not a guarantee we can make
everywhere. It must always be **knowable**, because a reference to an address that moves
silently is worse than no reference.

In SQLite it is decided by the same mechanism as above:

| Table | rowid | Address |
|---|---|---|
| has `INTEGER PRIMARY KEY` | the declared value; survives `VACUUM` | **stable** |
| no `INTEGER PRIMARY KEY` | assigned; `VACUUM` may renumber | **not stable** |

So `emp/7/name` on an IPK table is a durable name for that value; on a non-IPK table the same
form is a positional address that a `VACUUM` (by any writer — including a real `sqlite3`
between two of our reads) can quietly point at a different row.

## Writing

`write (address, type, value)` — the type travels inside the value, so the signature is
`addressWrite(image, address, value) → Either[String, ByteSlice]`.

**A write resolves its address first, and fails when it does not resolve.** This is the layer's
whole contribution here. The engine's `executeUpdate` is correct SQL: a `WHERE` matching nothing is
`changes() = 0`, not an error, and the reference agrees. But an address names ONE cell, so
"nothing matched" is a failed write. Measured on the engine before this was built — a missing rowid,
a missing column, and an IPK assignment ALL returned success with no change. Each is now an explicit
error; the engine's SQL semantics are left exactly as they are.

**An `INTEGER PRIMARY KEY` column is refused.** It is the row's *identity*, not a value: real SQLite
relocates the row (`UPDATE emp SET id = 5 WHERE id = 1` → rowid 5), which would make the packet's own
address stop existing. (Our engine silently drops such an assignment today — `BUGS.md` →
`scljet-update-ipk-column-silently-ignored`.) Refusal is the honest answer until relocation is a
thing we can do and name.

### The commit boundary

```
addressWriteAll(image, [packet…]) → Either[String, ByteSlice]
```

N packets, ONE image, all-or-nothing: the first failure aborts and yields no image, so a caller
never sees a half-applied set. The whole-image model gives the atomicity for free — every mutation
already returns a complete image — which is why grouping needs no new engine machinery. A future
file-backed writer changes where the image lands, not this contract.

## First slice — read

```
addressRead(image, address) → Either[String, (type, value)]
```

- resolve the address over the existing read path,
- report the format's type, or `Raw(n)`,
- honour the logical/physical link (IPK reads the rowid),
- report whether the address is stable.

Writes, non-SQLite formats, and remote references are later slices. The packet shape
`(address, type, value)` is fixed now so they do not change it: a write is that triple applied,
and a set of triples that must land together needs a commit boundary (SQLite touches several
pages per row change) — that grouping is deliberately not in this slice.

## Beyond SQLite — addressing a document

The same model and the same triple, a different resolver. Landed for JSON
(`v1/lang/uniml-address`, `JsonAddress.read(text, path)`), over UniML.

```
users/0/name  →  logical:  the path
                 physical: offset 19, length 5, line 1 col 20   (the bytes "ann" in the source)
```

**Both halves come from UniML's CST, deliberately — not from its semantic projection.** UniML's
canonical tree is lossless and spans EVERY node; `JsonValue`, the projection, carries a span only on
`JsonMember`, so an array element resolved through it would have no physical half and `users/0`
would quietly degrade to logical-only. The spec's own rule — the CST is canonical, the projection is
optional — has teeth here.

**The `Raw(n)` floor is free and total.** Every node has a span, so `n = end.offset − start.offset`
is always known: an unrecognised node is `Raw(n)`, never "unknown".

**Stability separates a NAME from a POSITION**, and this is where the property earns its keep:

| address | names by | stable |
|---|---|---|
| `active` (object key) | name | **yes** |
| `users/0` (array index) | position | **no** — insert a sibling and it means another value |
| `users/0/name` (key under an index) | position | **no** — stability cannot be regained by descending |

That is the same distinction as an IPK vs a plain rowid, in a different format: identity survives its
neighbours, position does not. A test inserts a sibling and watches `users/0/name` change from `ann`
to `zoe` while `active` stays put — the divergence the flag exists to announce.

**Reaching it from `.ssc`** is a plugin, not an import: UniML's surface is Scala and its dialects are
not dual-compilable to `.ssc` yet (`uniml-portable-1c-compat`). This is the same arrangement as host
files (`jvmVfs*`). Note that `jsonParse` — what `.ssc` has today — returns values with **no spans**
and so cannot serve the physical half at all.

## Not yet

- the `.ssc` bridge for document addresses (a plugin, per above), then YAML/XML/Markdown — the
  resolver differs, the triple does not
- remote references (`DurableRef`)
- remote references (`DurableRef`) — same model, different resolver
- addressing an interior node (a whole row or table); an address names a leaf, and a row is
  the set of leaves sharing its prefix
