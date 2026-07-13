# SclJet

Pure ScalaScript SQLite-compatible engine module.

**Current status: M2 read-only format codecs in progress.** The module provides immutable
64-byte-chunk `ByteSlice` storage, bounds-checked functional updates and slices,
big/little-endian integer codecs, exact SQLite 1–9 byte varints, and a
deterministic immutable in-memory VFS. M2 adds exact 100-byte database headers,
all four B-tree page/cell layouts, X/M/K local-payload sizing, overflow pages and
chains, record serial types, IEEE binary64, and lossless UTF-8/UTF-16 decoding
to encoded bytes plus code points. The VFS models random access, durable
sync/crash snapshots, rollback locks, eight WAL lock bytes, shared regions,
logical time/randomness, traces, and scripted faults. Portable connection,
statement, cursor, function, and collation contracts are also defined; a
working pager, schema cursor, and SQL engine are not exposed yet.

The dedicated JVM VFS plugin adds positioned file I/O, force/truncate,
canonical identity, SQLite rollback lock bytes, 32-KiB WAL shared-memory
regions and their eight lock bytes. It is an explicit host adapter only: pager,
WAL policy, codecs, and SQL remain pure ScalaScript. See the runnable
[`examples/scljet-jvm-vfs.ssc`](../../../examples/scljet-jvm-vfs.ssc), executed
with `bin/ssc-tools run --v1 examples/scljet-jvm-vfs.ssc`.

The canonical design and implementation gates are in
[`specs/scljet.md`](../../../specs/scljet.md).

A runnable introduction is
[`examples/scljet-bytes.ssc`](../../../examples/scljet-bytes.ssc):

```scalascript
val bytes = ByteSlice.fromList(List(0x12, 0x34, 0x56, 0x78))
val value = bytes match
  case Right(slice) => readU32Be(slice, 0)
  case Left(error) => Left(error)
```

The replayable VFS transition model is demonstrated by
[`examples/scljet-memory-vfs.ssc`](../../../examples/scljet-memory-vfs.ssc).
The M2 codec path over an official SQLite 3.53.3 header/cell is demonstrated by
[`examples/scljet-readonly-codecs.ssc`](../../../examples/scljet-readonly-codecs.ssc).

`DecodedText` keeps the original encoded bytes authoritative, matching
SQLite's invalid-UTF GIGO policy, and exposes a deterministic code-point list.
It intentionally does not fake a `SqlText(String)` projection while portable
code-point-to-string construction differs between ScalaScript runtimes.

The implementation must remain pure ScalaScript above `SqliteVfs`. Host
filesystem adapters and any required intrinsics belong in separate
`runtime/std/<feature>-plugin/` modules.
