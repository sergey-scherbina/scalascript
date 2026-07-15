# SclJet JDBC — JVM `java.sql.Driver` shim (implementation notes)

Status: **implemented (J3 core)** — companion to the normative spec
[`specs/scljet-jdbc.md`](scljet-jdbc.md), which owns the URL grammar, type map,
autocommit/transaction semantics, supported subset, and error→SQLState mapping.
This file records how the JVM lane is *built* — the bridge mechanism, the file
layout, the build wiring, and the deliberate deviations/follow-ups.

Module: `runtime/std/scljet-jdbc-plugin/` (JVM-only; not `[int, js]`).

## Feasibility finding — how JVM Scala reaches the engine

The scljet SQL executor and the JDBC façade (`scljet/sql.ssc`,
`scljet/jdbc.ssc`) are **ScalaScript, not Scala** — there is no Scala entry point
that runs `queryImage` / `executeMutation` directly. The clean, existing bridge
is the embedded tree-walking interpreter `scalascript.interpreter.Interpreter`
(the same class the `scljet-vfs-plugin` test drives):

1. Construct one `Interpreter` and `run` a tiny **bootstrap module** whose only
   content is the two import links the green conformance case
   `tests/conformance/scljet-jdbc-basic.ssc` uses —
   `[…](std/scljet/index.ssc)` and `[…](std/scljet/jdbc.ssc)`. Import resolution
   (`ImportResolver`, std-root auto-discovery via the dev-tree `runtime/std`
   walk-up; `runtime/std/scljet` is a symlink to the engine) evaluates the whole
   engine once and, through the interpreter's **transitive call-time dump**
   (`SectionRuntime.runImport`, "make the dependency's module-level names
   available here"), lands every façade function *and* every non-re-exported
   helper (`queryImageParams`, `bindParams`, `rsValueAt`, …) plus every value
   constructor (`SqlInteger`, `SqlText`, `emptyDatabase`, `byteSliceUnsafe`, …)
   in the interpreter's `globals`.
2. The JVM shim looks a global up by name in `interp.globalsView` and calls it
   with `Interpreter.invoke(fn, args): Value` — the public call gate.

Because the same interpreter runs the *same* import structure the conformance
script does, `invoke(jdbcExecuteQueryParams, …)` resolves its transitive
`queryImageParams` exactly as the green script does. The database **image** and
the **result-set cursor** are threaded as opaque `Value`s that never have to be
understood structurally on the JVM side; only a handful of leaves cross the
boundary (`SqlInteger`/`SqlText`/… params in, scalars out), and those are built
and read through the engine's own constructors/getters — so no representation is
hand-guessed. Bridge = **clean**; no engine changes were needed.

## Architecture — Proxy over the pure façade

`java.sql.*` interfaces are huge (ResultSet ≈ 190 methods) and drift across JDK
versions. Rather than hand-implement every abstract method as a concrete class
(which must enumerate the full, version-specific method set to compile), every
interface except `Driver` is served by a `java.lang.reflect.Proxy` backed by one
`InvocationHandler` that dispatches the supported subset and throws
`SQLFeatureNotSupportedException` for the rest. This is the "unsupported methods
are one-line throws" route recommended in `scljet-jdbc.md` §"Open decisions",
made compact and version-proof. `Driver` is the one real concrete class (it must
be, for `ServiceLoader` / `DriverManager`).

```
DriverManager.getConnection("jdbc:scljet:…")
      │
ScljetDriver (real class, META-INF/services/java.sql.Driver)
      │  parseUrl → openTarget → jdbcOpen
      ▼
ScljetConnectionState ── ConnectionHandler (Proxy java.sql.Connection)
      │                        │ createStatement / prepareStatement
      │                        ▼
      │                  StatementHandler / PreparedStatementHandler (Proxy)
      │                        │ executeQuery / executeUpdate / setXxx
      ▼                        ▼
ScljetEngine.call(name, args) ─┴─► Interpreter.invoke(globals(name), args)
      │                                    │
      ▼                                    ▼
ScljetResultSetState ── ResultSetHandler / ResultSetMetaHandler (Proxy)
ScljetMeta            ── DatabaseMetaData / ParameterMetaData (Proxy)
```

## File layout

| File | Role |
|---|---|
| `ScljetEngine.scala` | bootstraps the shared `Interpreter`; Value ⇄ JVM helpers; `call(name, args*)` (serialized) |
| `ScljetErrors.scala` | engine `Left(message)` → `SQLException` subclass + SQLState |
| `ProxySupport.scala` | `ProxyHandler` base (Object + Wrapper methods), boxing/arg helpers |
| `ScljetDriver.scala` | real `java.sql.Driver`; URL grammar parse; target open (`:memory:` / `classpath:` / host file) |
| `ScljetConnection.scala` | connection state (autocommit/commit/rollback threading, host-file durability) + Connection proxy |
| `ScljetStatement.scala` | Statement + PreparedStatement proxies; shared `StatementCommon` dispatch; `?`-param binding |
| `ScljetResultSet.scala` | forward-only cursor state + ResultSet + ResultSetMetaData proxies; SqliteValue→Types map |
| `ScljetMeta.scala` | minimal DatabaseMetaData + ParameterMetaData proxies; version constants |
| `resources/META-INF/services/java.sql.Driver` | ServiceLoader registration |

## Build wiring (and why it is not in `allPlugins`)

```scala
lazy val scljetJdbcPlugin = project
  .in(file("v1/runtime/std/scljet-jdbc-plugin"))
  .dependsOn(backendInterpreter, backendSqlRuntime % Test, testUtils % Test)
  .settings(name := "scalascript-scljet-jdbc-plugin", libraryDependencies ++= Seq(scalatestTest), …)
```

Unlike a normal std plugin, this shim **embeds** the interpreter (it is a
consumer of the engine, not an intrinsic *provider* the interpreter loads), so it
depends on `backendInterpreter` at Compile scope. It is therefore deliberately
**not** added to the `allPlugins` registry — doing so would (a) create a
dependency cycle `backendInterpreterPluginTests → scljetJdbcPlugin →
backendInterpreter`, and (b) demand `.sscpkg` packaging it does not need
(`DriverManager` discovers it via `META-INF/services`, a runtime jar resource).
The only wiring is the `lazy val` plus one entry in the root `.aggregate(…)` list
so `sbt compile` / CI build it. `backendSqlRuntime % Test` supplies
`org.xerial:sqlite-jdbc` as the conformance **oracle** only.

## Durability model

Whole-image-rewrite (Model A of `scljet-jdbc.md` §"Connection"). The façade
`JdbcConnection` carries `committed` + `working`; the shim mirrors autocommit:
each `executeUpdate` gets a new image, promoted to durable immediately in
autocommit or staged as `working` and promoted on `commit()`. For a **host
file** the durable image is flushed to disk (read-modify-rewrite — read the whole
file to a `ByteSlice` on open, write the whole committed image back on each
durable change). `:memory:` and `classpath:` images never touch disk.

## Deliberate deviations / follow-ups (not J3 blockers)

- **Host locking.** The MVP host-file path is single-process read-modify-rewrite;
  it does **not** yet route through `jvmSqliteVfs()` for SQLite-compatible file
  locking (`scljet-jdbc.md` J3's `driver-smoke` diff-against-`sqlite-jdbc`-on-the-
  same-file is the hardening target). Correct for single-process use.
- **`getGeneratedKeys` / `RETURN_GENERATED_KEYS`** — the last-insert-rowid is
  already threaded (`JdbcUpdate.lastInsertRowid`); exposing it as a one-column
  `ResultSet` is a small follow-up (J4). Currently throws.
- **`setMaxRows`** is stored but advisory (not enforced by truncating the row
  list) — J4.
- **DatabaseMetaData** implements only the identifying subset; `getTables` /
  `getColumns` over `sqlite_schema` are J4.
- **Concurrency.** All `invoke`s are serialized on the shared interpreter (its
  AST-identity caches are not thread-safe). JDBC connections are normally
  single-threaded; this only serializes the rare concurrent-connection case.
```
