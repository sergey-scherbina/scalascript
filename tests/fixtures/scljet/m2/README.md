# SclJet M2 oracle vectors

`codec-vectors.tsv` is the first committed slice of the M2 corpus. It was
generated with official SQLite 3.53.3 source id
`2026-06-26 20:14:12 d4c0e51e4aeb96955b99185ab9cde75c339e2c29c3f3f12428d364a10d782c62`.

The generator operations, in order, are:

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

The broader page-size/encoding/reservation/freelist/corruption corpus remains
the M2d interoperability slice described in `specs/scljet.md`.
