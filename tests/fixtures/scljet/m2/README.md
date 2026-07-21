# SclJet M2 interoperability corpus

Ordinary tests consume the committed databases and never invoke a reference
SQLite library. `manifest.tsv` pins the SHA-256 and header metadata for every
valid file; `oracle-storage.txt` pins its ordered schema and physical values;
`corrupt-manifest.tsv` and `corrupt-errors.txt` pin the invalid inputs and
localized SclJet results.

The valid corpus is generated and checked with official SQLite 3.53.3 source
id `2026-06-26 20:14:12
d4c0e51e4aeb96955b99185ab9cde75c339e2c29c3f3f12428d364a10d782c62`.
The current slice has 25 valid databases, 33 named corruptions (30 open-time
plus 3 overflow-chain traversal), 643 exact dump lines, and 32 deterministic
bounded mutations. It covers every legal
page size, UTF-8/UTF-16LE/UTF-16BE and empty encoding 0, rowid and WITHOUT
ROWID trees, explicit/automatic indexes, multi-level B-trees, overflow,
freelist, full/incremental auto-vacuum pointer maps, clean WAL header versions,
serial/rowid edges, application/user versions, and reserved-byte counts 0, 1,
7, and 32 (the 512-byte-page boundary with exactly 480 usable bytes). It also
covers every schema format 0 through 4: format 0 is the empty database,
formats 1/2/3 come from canonical SQLite 3.2.0, and format 4 comes from the
current oracle.

`overflow-thresholds.db` pins table-leaf cells straddling SQLite's exact
overflow boundary on a 512-byte page (usable `u = 512`, so `X = u - 35 = 477`
and `m = 39`): total payloads `p = 476/477` stay fully local, `p = 478` is the
sharp one-byte overflow that falls back to the `m`-byte residue because
`K = m + ((p - m) % (u - 4)) > X`, `p = 900` exercises the `K <= X` branch that
keeps `K` bytes local, and `p = 1100` spans a multi-page overflow chain. The
reader reproduces every row byte-for-value across all interpreter tiers.

`index-overflow-thresholds.db` does the same for index-btree cells, whose
threshold uses the index formula `X = ((u - 12) * 64 / 255) - 23 = 102` on a
512-byte page. A single BLOB-keyed index over a rowid table has index key
records `(blob, rowid)` with total payload `p = L + 5`; the keys hit
`p = 101/102` (local, including the inclusive boundary), `p = 103` (the sharp
`K > X` fall to `m = 39`), `p = 200`, and `p = 1100` (a `K <= X` multi-page
overflow chain). Both the table and the index cells are read byte-for-value.

`generate.py` requires a Python `sqlite3` module built from that exact source.
Reserved bytes cannot be selected through SQL, so `generate-reserved.c` is
compiled against the separately downloaded official amalgamation and invokes
`SQLITE_FCNTL_RESERVE_BYTES`, followed by `VACUUM`. The release archive is
`sqlite-amalgamation-3530300.zip`, whose published SHA3-256 is
`d45c688a8cb23f68611a894a756a12d7eb6ab6e9e2468ca70adbeab3808b5ab9`.
A representative reproduction is:

```sh
unzip sqlite-amalgamation-3530300.zip
cc -std=c11 -O2 -Isqlite-amalgamation-3530300 \
  generate-reserved.c sqlite-amalgamation-3530300/sqlite3.c \
  -lpthread -ldl -lm -o scljet-generate-reserved
SCLJET_RESERVED_GENERATOR="$PWD/scljet-generate-reserved" ./generate.py
```

Schema formats 1/2/3 use the canonical source archive for the official Fossil
tag `version-3.2.0`. Its manifest UUID is
`debf40e8ffa35406685ec027ced1f147ef0487df`; the archive used for the committed
fixtures has SHA3-256
`1b82ba33675022028b37fc067b1dbf399168cfafcbb653f74edfe7d950044cce`.
After building its `sqlite3` shell, pass both the executable and the source
manifest to the generator:

```sh
curl -fL https://sqlite.org/src/tarball/version-3.2.0/sqlite.tar.gz \
  -o sqlite-version-3.2.0.tar.gz
tar -xzf sqlite-version-3.2.0.tar.gz
(cd sqlite && ./configure --disable-tcl && make sqlite3)

SCLJET_RESERVED_GENERATOR="$PWD/scljet-generate-reserved" \
SCLJET_HISTORICAL_SQLITE="$PWD/sqlite/sqlite3" \
SCLJET_HISTORICAL_MANIFEST_UUID="$PWD/sqlite/manifest.uuid" \
./generate.py
```

The 3.2.0 shell prints `3.2.0` for `-version` but returns status 1; the
generator deliberately validates its exact stdout and canonical manifest UUID
instead of treating that historical exit code as failure. On modern compilers
the 2005 source may require compatibility warning flags, but no source change
is permitted: the manifest UUID is the provenance gate.

Regeneration verifies the exact oracle and generator identities, checks every valid database
with reference `PRAGMA integrity_check`, recreates the manifests/oracle, and
fails before deleting fixtures when either helper is unavailable or has the
wrong identity. The current corruption matrix covers the database magic and
length, page size/read-write versions/reservation/payload fractions, schema
format/encoding/reserved header region, incremental-vacuum and freelist header
relations, trusted page count, B-tree kind/fragments/content offset/pointer
array/cell pointer, pointer-map kind/ownership, and freelist range/cycle/count/
duplicate invariants. It also covers deep page-1 sqlite_schema record damage:
a reserved serial type (10/11), an unknown schema object type, a rootpage that
is negative or past the page count, and a page freeblock chain that is not
increasing and in range. Exact table-leaf and index-btree payload thresholds
are pinned by `overflow-thresholds.db` and `index-overflow-thresholds.db`.

The matrix also covers user-table overflow-chain traversal corruptions on
non-schema pages: `overflow-chain-truncated.db`, `overflow-chain-out-of-range.db`,
and `overflow-chain-cycle.db` mutate the `next` pointer of page 11 (the first
overflow page of the `p = 1100` row's two-page chain in `overflow-thresholds.db`)
to 0, 99, and 11 (self-loop) respectively. Unlike every other corruption these
are accepted by open-time `openReadonly` and fail only during forward table
traversal, so they are checked by `tests/tools/scljet-corrupt-traverse.ssc`
(pinned in `corrupt-traversal-errors.txt`) rather than the open-time
`scljet-corrupt-check.ssc`. Conformance `scljet-overflow-traversal-corrupt`
(`[int, js]`) reproduces the same chain in memory and adds the length-short
`overflow page is truncated` case that an on-disk file cannot express.

## Original pure codec vector

`codec-vectors.tsv` is the first committed slice of the M2 corpus. Its
generator operations, in order, are:

```sql
PRAGMA page_size=512;
PRAGMA journal_mode=delete;
CREATE TABLE t(a,b,c);
INSERT INTO t VALUES(-2,'Hi',x'00ff');
```

The database is committed and closed before bytes are read. Its SHA-256 is
`25d1f3a0e0ba454cb12a5d9a918f99489bd07cbe46cf5e3aee5f8516eb2ca59a`.
Page 2 is the `t` root; the cell offset is read from its pointer array rather
than assumed by the generator. The conformance case embeds these exact vectors
so pure codecs run without filesystem or SQLite dependencies.
