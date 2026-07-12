# SclJet

Pure ScalaScript SQLite-compatible engine module.

**Current status: M1 bytes and codecs.** The module now provides immutable
64-byte-chunk `ByteSlice` storage, bounds-checked functional updates and slices,
big/little-endian integer codecs, exact SQLite 1–9 byte varints, and a
deterministic immutable in-memory VFS. The VFS models random access, durable
sync/crash snapshots, rollback locks, eight WAL lock bytes, shared regions,
logical time/randomness, traces, and scripted faults. Portable connection,
statement, cursor, function, and collation contracts are also defined; a
working pager and SQL engine are not exposed yet.

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

The implementation must remain pure ScalaScript above `SqliteVfs`. Future host
filesystem adapters and any required intrinsics belong in separate
`runtime/std/<feature>-plugin/` modules.
