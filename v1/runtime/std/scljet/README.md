# SclJet

Pure ScalaScript SQLite-compatible engine module.

**Current status: M0 contracts only.** The files in this directory define the
portable public values, errors, options, VFS capability, connection, statement,
cursor, function, and collation interfaces. They deliberately do not expose a
working engine constructor yet.

The canonical design and implementation gates are in
[`specs/scljet.md`](../../../specs/scljet.md).

The implementation must remain pure ScalaScript above `SqliteVfs`. Future host
filesystem adapters and any required intrinsics belong in separate
`runtime/std/<feature>-plugin/` modules.
