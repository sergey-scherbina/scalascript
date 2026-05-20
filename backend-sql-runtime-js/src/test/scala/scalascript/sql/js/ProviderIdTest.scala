package scalascript.sql.js

import org.scalatest.funsuite.AnyFunSuite

/** v1.27 Phase 2 — URL → provider dispatch, build-time view.
 *  Mirrors the JS-side `Providers.fromUrl` in `sql-runtime.mjs`. */
class ProviderIdTest extends AnyFunSuite:

  // ── happy paths: prefix → provider ─────────────────────────────────

  test("ProviderId: sqlite::memory: → SqlJs"):
    assert(ProviderId.fromUrl("sqlite::memory:") == Right(ProviderId.SqlJs))

  test("ProviderId: sqlite:./audit.db → SqlJs"):
    assert(ProviderId.fromUrl("sqlite:./audit.db") == Right(ProviderId.SqlJs))

  test("ProviderId: sqlite: → SqlJs (empty path also routes to sql.js)"):
    assert(ProviderId.fromUrl("sqlite:") == Right(ProviderId.SqlJs))

  test("ProviderId: duckdb: → DuckDbWasm"):
    assert(ProviderId.fromUrl("duckdb:") == Right(ProviderId.DuckDbWasm))

  test("ProviderId: duckdb:./events.duckdb → DuckDbWasm"):
    assert(ProviderId.fromUrl("duckdb:./events.duckdb") == Right(ProviderId.DuckDbWasm))

  // ── error paths ────────────────────────────────────────────────────

  test("ProviderId: jdbc:postgresql:... → dedicated UnsupportedJdbcUrl phrasing"):
    val err = ProviderId.fromUrl("jdbc:postgresql://localhost:5432/x")
    assert(err.isLeft)
    val msg = err.swap.toOption.get
    assert(msg.contains("jdbc:postgresql://localhost:5432/x"))
    assert(msg.contains("sqlite:") || msg.contains("duckdb:"))
    assert(msg.contains("JVM target"))

  test("ProviderId: jdbc:h2:mem:x → UnsupportedJdbcUrl (any jdbc subscheme)"):
    val err = ProviderId.fromUrl("jdbc:h2:mem:x")
    assert(err.isLeft)
    assert(err.swap.toOption.get.startsWith("JDBC URL"))

  test("ProviderId: unknown scheme → lists supported prefixes"):
    val err = ProviderId.fromUrl("postgres://localhost/x")
    assert(err.isLeft)
    val msg = err.swap.toOption.get
    assert(msg.contains("postgres://localhost/x"))
    assert(msg.contains("sqlite:"))
    assert(msg.contains("duckdb:"))

  test("ProviderId: null URL → error"):
    val err = ProviderId.fromUrl(null)
    assert(err.isLeft)
    assert(err.swap.toOption.get.contains("non-null"))

  test("ProviderId: empty URL → error"):
    val err = ProviderId.fromUrl("")
    assert(err.isLeft)

  // ── enum surface ───────────────────────────────────────────────────

  test("ProviderId.SqlJs: id / urlPrefix / npmPackage stable"):
    assert(ProviderId.SqlJs.id              == "sql.js")
    assert(ProviderId.SqlJs.urlPrefix       == "sqlite:")
    assert(ProviderId.SqlJs.npmPackage      == "sql.js")
    assert(ProviderId.SqlJs.npmVersionRange.startsWith("^"))

  test("ProviderId.DuckDbWasm: id / urlPrefix / npmPackage stable"):
    assert(ProviderId.DuckDbWasm.id              == "duckdb-wasm")
    assert(ProviderId.DuckDbWasm.urlPrefix       == "duckdb:")
    assert(ProviderId.DuckDbWasm.npmPackage      == "@duckdb/duckdb-wasm")
    assert(ProviderId.DuckDbWasm.npmVersionRange.startsWith("^"))

  test("ProviderId.all: both providers present"):
    assert(ProviderId.all == Set(ProviderId.SqlJs, ProviderId.DuckDbWasm))
