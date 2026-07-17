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

## Not yet

- addresses into UniML documents (JSON/YAML/XML/Markdown) — same model, different resolver
- remote references (`DurableRef`) — same model, different resolver
- addressing an interior node (a whole row or table); an address names a leaf, and a row is
  the set of leaves sharing its prefix
