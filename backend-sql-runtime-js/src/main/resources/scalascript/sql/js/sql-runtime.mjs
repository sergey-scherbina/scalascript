// scalascript browser-side SQL runtime (v1.27).
// Spec: docs/browser-sql.md.
//
// Loaded verbatim into bundles emitted by backend-js / backend-node /
// backend-wasm when the source has any `sql` fenced block.  Exposes:
//
//   * SqlResult — { kind: 'rows', rows: Row[] } | { kind: 'update', count }
//   * Row      — callable returning column values by position or name,
//                with .toMap()
//   * ConnectionRegistry — lazy-open + cached connect(name), fresh(name),
//                          close()
//   * execute(conn, sql, binds) — async wrapper, statement-type detection
//   * Providers — URL prefix → provider object
//
// Provider lifecycle: providers lazy-import their npm package the first
// time `connect` is called.  Idempotent.

// ── Errors ───────────────────────────────────────────────────────────────────

export class SqlRuntimeError extends Error {
  constructor(message, cause) {
    super(message)
    this.name = 'SqlRuntimeError'
    if (cause !== undefined) this.cause = cause
  }
}

export class UnknownDatabase extends SqlRuntimeError {
  constructor(name, available) {
    super(`Unknown database "${name}". Available: ${available.length ? available.join(', ') : '(none)'}`)
    this.name = 'UnknownDatabase'
  }
}

export class MissingEnv extends SqlRuntimeError {
  constructor(varName, dbName, field) {
    super(`Environment variable "${varName}" referenced by databases["${dbName}"].${field} is not set`)
    this.name = 'MissingEnv'
  }
}

export class MissingFs extends SqlRuntimeError {
  constructor(url) {
    super(`File-backed database URL "${url}" is only supported on Node.js (no fs in the browser)`)
    this.name = 'MissingFs'
  }
}

export class UnsupportedJdbcUrl extends SqlRuntimeError {
  constructor(url) {
    super(`JDBC URL "${url}" is not supported on JS-family targets; use sqlite: or duckdb: instead, or run on the JVM target`)
    this.name = 'UnsupportedJdbcUrl'
  }
}

// ── Row ──────────────────────────────────────────────────────────────────────

/** Make a callable Row: row(0), row("name") (case-insensitive), row.toMap().
 *  `columns` is the ordered list of column names as returned by the engine.
 *  `values`  is the parallel array of cell values. */
export function makeRow(columns, values) {
  const byNameLower = new Map()
  for (let i = 0; i < columns.length; i++) {
    byNameLower.set(String(columns[i]).toLowerCase(), i)
  }
  const row = (key) => {
    if (typeof key === 'number') {
      if (key < 0 || key >= values.length) {
        throw new SqlRuntimeError(`Row index ${key} out of range [0, ${values.length})`)
      }
      return values[key]
    }
    if (typeof key === 'string') {
      const idx = byNameLower.get(key.toLowerCase())
      if (idx === undefined) {
        throw new SqlRuntimeError(`Row has no column "${key}". Columns: ${columns.join(', ')}`)
      }
      return values[idx]
    }
    throw new SqlRuntimeError(`Row index must be number or string, got ${typeof key}`)
  }
  row.toMap = () => {
    const out = {}
    for (let i = 0; i < columns.length; i++) out[columns[i]] = values[i]
    return out
  }
  row.columns = () => columns.slice()
  row.values  = () => values.slice()
  // function objects have a read-only `length` (declared param count),
  // so expose column count under a distinct name.
  Object.defineProperty(row, 'columnCount', { value: values.length })
  return row
}

// ── Statement-type detection ────────────────────────────────────────────────

const RESULT_SET_KEYWORDS = /^\s*(SELECT|WITH|VALUES|SHOW|EXPLAIN|PRAGMA)\b/i

/** True if `sql` begins with a keyword whose canonical execution returns
 *  a result set rather than an update count.  PRAGMA included for SQLite
 *  parity — many `PRAGMA` statements return rows. */
export function isResultSetProducer(sql) {
  return RESULT_SET_KEYWORDS.test(sql)
}

// ── execute ──────────────────────────────────────────────────────────────────

/** Run a `?`-templated statement against a Connection.  Returns
 *  { kind: 'rows', rows: Row[] } for SELECT-family, { kind: 'update', count }
 *  for DML / DDL.  Statement-type detection mirrors JVM SqlRuntime. */
export async function execute(conn, sql, binds) {
  return conn.execute(sql, binds ?? [])
}

// ── ConnectionRegistry ───────────────────────────────────────────────────────

/** specs:    { [name]: { url, user?, password?, driver? } }
 *  envLookup: (name) => string | undefined.  Defaults to
 *             globalThis.process?.env in Node, undefined elsewhere. */
export class ConnectionRegistry {
  constructor(specs, envLookup) {
    this._specs    = specs ?? {}
    this._env      = envLookup ?? defaultEnvLookup
    this._cache    = new Map()    // name -> Promise<Connection>
  }

  /** Lazy-open + cached.  Calling connect(name) twice returns the same
   *  Connection (same Promise resolution). */
  async connect(name) {
    if (this._cache.has(name)) return this._cache.get(name)
    const p = this._open(name).catch((e) => {
      // Don't cache failures — let the user retry after fixing config.
      this._cache.delete(name)
      throw e
    })
    this._cache.set(name, p)
    return p
  }

  /** Fresh, uncached connection.  Use when the caller needs guaranteed
   *  isolation (test fixtures, override path). */
  async fresh(name) {
    return this._open(name)
  }

  /** Idempotent: closes every cached connection.  Safe to call multiple
   *  times; later opens after close work fine (the cache resets). */
  async close() {
    const conns = [...this._cache.values()]
    this._cache.clear()
    for (const p of conns) {
      try {
        const c = await p
        await c.close()
      } catch (_) { /* swallow; close is best-effort */ }
    }
  }

  /** Available database names. */
  names() { return Object.keys(this._specs) }

  async _open(name) {
    const spec = this._specs[name]
    if (!spec) throw new UnknownDatabase(name, this.names())
    const url      = resolveEnvRefs(spec.url,      this._env, name, 'url')
    const user     = spec.user     != null ? resolveEnvRefs(spec.user,     this._env, name, 'user')     : undefined
    const password = spec.password != null ? resolveEnvRefs(spec.password, this._env, name, 'password') : undefined
    if (url.startsWith('jdbc:')) throw new UnsupportedJdbcUrl(url)
    const provider = Providers.fromUrl(url)
    return provider.open({ url, user, password })
  }
}

ConnectionRegistry.empty = () => new ConnectionRegistry({})

// ── EnvResolver: ${env:NAME} substring expansion ────────────────────────────

const ENV_REF = /\$\{env:([A-Za-z_][A-Za-z0-9_]*)\}/g

export function resolveEnvRefs(template, envLookup, dbName, field) {
  return template.replace(ENV_REF, (_, varName) => {
    const v = envLookup(varName)
    if (v === undefined || v === null) {
      throw new MissingEnv(varName, dbName, field)
    }
    return v
  })
}

function defaultEnvLookup(name) {
  // In Node: globalThis.process.env[name].  In the browser: undefined
  // for everything, so user must pass an explicit envLookup if they
  // want browser-side env injection (e.g. from a bootstrap fetch).
  const proc = globalThis.process
  if (proc && proc.env) return proc.env[name]
  return undefined
}

// ── Providers ────────────────────────────────────────────────────────────────

/** URL prefix → provider object.  Each provider has an `id` string and
 *  an async `open({ url, user?, password? }) -> Connection`. */
export const Providers = Object.freeze({
  fromUrl(url) {
    if (typeof url !== 'string') throw new SqlRuntimeError(`URL must be a string, got ${typeof url}`)
    if (url.startsWith('sqlite:')) return SqlJsProvider
    if (url.startsWith('duckdb:')) return DuckDbWasmProvider
    if (url.startsWith('jdbc:'))   throw new UnsupportedJdbcUrl(url)
    throw new SqlRuntimeError(`No provider matches URL "${url}". Supported prefixes: sqlite:, duckdb:`)
  },
  available() { return ['sqlite:', 'duckdb:'] }
})

// Lazy-loaded provider singletons.  initSqlJs() / duckdb.AsyncDuckDB
// initialisation runs once per process; cached on the module object.
let _sqlJsModule = null
let _duckDbBundle = null

async function loadSqlJs() {
  if (_sqlJsModule) return _sqlJsModule
  const mod = await import('sql.js')
  const initSqlJs = mod.default ?? mod
  _sqlJsModule = await initSqlJs({})
  return _sqlJsModule
}

async function loadDuckDb() {
  if (_duckDbBundle) return _duckDbBundle
  const duckdb = await import('@duckdb/duckdb-wasm')
  // duckdb-wasm exports a `createWorker(path)` helper whose return
  // type is the browser-ish Worker shim the AsyncDuckDB constructor
  // needs — works identically in Node (over node:worker_threads) and
  // in the browser (over real Worker).  We use it on both sides.
  const isNode = !!(globalThis.process && globalThis.process.versions && globalThis.process.versions.node)
  let bundle
  if (isNode) {
    const path = await import('node:path')
    // Dual-mode require resolution:
    //   - CJS: `globalThis.require` is in scope, use it directly.
    //   - ESM: build a `require` rooted at the current working
    //          directory.  We deliberately avoid `import.meta.url`
    //          here — it's a syntax error when this source is embedded
    //          into the CJS bundle that NodeBackend emits (Phase 4).
    let require
    if (typeof globalThis.require !== 'undefined') {
      require = globalThis.require
    } else {
      const moduleNs = await import('node:module')
      require = moduleNs.createRequire(`file://${process.cwd()}/.`)
    }
    const DUCKDB_DIST = path.dirname(require.resolve('@duckdb/duckdb-wasm'))
    // duckdb-wasm's own `createWorker` is browser-only (uses fetch +
    // URL.createObjectURL).  In Node we use the `web-worker` package,
    // which exposes the browser Worker API over node:worker_threads.
    // duckdb-wasm declared this dependency on themselves until they
    // bundled it inline; we re-add it explicitly so we don't depend on
    // their internal packaging choice.
    const WebWorker = (await import('web-worker')).default
    const bundles = {
      mvp: {
        mainModule:   path.resolve(DUCKDB_DIST, './duckdb-mvp.wasm'),
        mainWorker:   path.resolve(DUCKDB_DIST, './duckdb-node-mvp.worker.cjs'),
      },
      eh: {
        mainModule:   path.resolve(DUCKDB_DIST, './duckdb-eh.wasm'),
        mainWorker:   path.resolve(DUCKDB_DIST, './duckdb-node-eh.worker.cjs'),
      },
    }
    bundle = await duckdb.selectBundle(bundles)
    bundle._workerFactory = () => new WebWorker(bundle.mainWorker, { type: 'module' })
  } else {
    const bundles = duckdb.getJsDelivrBundles()
    bundle = await duckdb.selectBundle(bundles)
    bundle._workerFactory = () => duckdb.createWorker(bundle.mainWorker)
  }
  _duckDbBundle = { duckdb, bundle }
  return _duckDbBundle
}

// ── sql.js provider ─────────────────────────────────────────────────────────

export const SqlJsProvider = Object.freeze({
  id: 'sql.js',

  async open({ url }) {
    const SQL = await loadSqlJs()
    // url shapes:
    //   sqlite::memory:    -> fresh in-memory DB
    //   sqlite:<path>      -> read file from <path> (Node only)
    const isMemory = url === 'sqlite::memory:' || url === 'sqlite:'
    let dbBytes
    if (!isMemory) {
      const path = url.slice('sqlite:'.length)
      const isNode = !!(globalThis.process && globalThis.process.versions && globalThis.process.versions.node)
      if (!isNode) throw new MissingFs(url)
      const fs = await import('node:fs/promises')
      try {
        dbBytes = await fs.readFile(path)
      } catch (e) {
        if (e.code === 'ENOENT') dbBytes = undefined          // create new file on first close
        else throw new SqlRuntimeError(`sqlite open failed for "${path}": ${e.message}`, e)
      }
      // Persist on close.
    }
    const db = new SQL.Database(dbBytes)
    return new SqlJsConnection(db, url)
  },
})

class SqlJsConnection {
  constructor(db, url) {
    this._db  = db
    this._url = url
    this._closed = false
  }

  async execute(sql, binds) {
    if (this._closed) throw new SqlRuntimeError(`Connection to "${this._url}" is closed`)
    const stmt = this._db.prepare(sql)
    try {
      stmt.bind(binds.map(toSqlJsBind))
      if (isResultSetProducer(sql)) {
        const rows = []
        let columns = null
        while (stmt.step()) {
          if (columns === null) columns = stmt.getColumnNames()
          rows.push(makeRow(columns, stmt.get().map(fromSqlJsValue)))
        }
        return { kind: 'rows', rows }
      }
      // DML / DDL — step returns false (no rows), then getRowsModified gives count.
      while (stmt.step()) { /* drain unexpected rows */ }
      const count = this._db.getRowsModified()
      return { kind: 'update', count }
    } finally {
      stmt.free()
    }
  }

  async close() {
    if (this._closed) return
    this._closed = true
    // Persist file-backed DB if applicable.
    const isMemory = this._url === 'sqlite::memory:' || this._url === 'sqlite:'
    if (!isMemory) {
      const isNode = !!(globalThis.process && globalThis.process.versions && globalThis.process.versions.node)
      if (isNode) {
        const path = this._url.slice('sqlite:'.length)
        const fs   = await import('node:fs/promises')
        const data = this._db.export()
        try { await fs.writeFile(path, Buffer.from(data)) }
        catch (e) { /* best-effort */ }
      }
    }
    this._db.close()
  }
}

/** Coerce a JS value into a shape sql.js's `Statement.bind` accepts. */
function toSqlJsBind(v) {
  if (v === undefined || v === null) return null
  if (typeof v === 'boolean')        return v ? 1 : 0
  if (typeof v === 'bigint') {
    // sql.js marshals through Number; throw on overflow rather than
    // silently lose precision.
    if (v > BigInt(Number.MAX_SAFE_INTEGER) || v < BigInt(Number.MIN_SAFE_INTEGER)) {
      throw new SqlRuntimeError(`BigInt ${v} exceeds Number.MAX_SAFE_INTEGER on sql.js bind path`)
    }
    return Number(v)
  }
  if (v instanceof Uint8Array)      return v
  if (typeof Buffer !== 'undefined' && v instanceof Buffer) return new Uint8Array(v)
  if (v instanceof Date)            return v.toISOString()
  return v
}

/** Normalise sql.js result values: Uint8Array stays, everything else
 *  passes through.  sql.js already returns numbers / strings / null. */
function fromSqlJsValue(v) {
  return v
}

// ── DuckDB-Wasm provider ────────────────────────────────────────────────────

export const DuckDbWasmProvider = Object.freeze({
  id: 'duckdb-wasm',

  async open({ url }) {
    const { duckdb, bundle } = await loadDuckDb()
    const worker = bundle._workerFactory()
    const logger = new duckdb.ConsoleLogger(duckdb.LogLevel.WARNING)
    const db     = new duckdb.AsyncDuckDB(logger, worker)
    await db.instantiate(bundle.mainModule, bundle.pthreadWorker)

    // url shapes:
    //   duckdb:           -> in-memory DB
    //   duckdb:<path>     -> file (Node only)
    const target = url === 'duckdb:' ? null : url.slice('duckdb:'.length)
    if (target !== null) {
      const isNode = !!(globalThis.process && globalThis.process.versions && globalThis.process.versions.node)
      if (!isNode) throw new MissingFs(url)
      const path = await import('node:path')
      const fs   = await import('node:fs/promises')
      const abs  = path.resolve(target)
      try { await fs.access(abs) } catch (_) { /* ok — duckdb creates */ }
      await db.open({ path: abs, accessMode: duckdb.DuckDBAccessMode.READ_WRITE })
    } else {
      await db.open({ path: ':memory:', accessMode: duckdb.DuckDBAccessMode.READ_WRITE })
    }
    const conn = await db.connect()
    return new DuckDbConnection(db, conn, worker, url)
  },
})

class DuckDbConnection {
  constructor(db, conn, worker, url) {
    this._db     = db
    this._conn   = conn
    this._worker = worker
    this._url    = url
    this._closed = false
  }

  async execute(sql, binds) {
    if (this._closed) throw new SqlRuntimeError(`Connection to "${this._url}" is closed`)
    const stmt = await this._conn.prepare(sql)
    try {
      const result = await stmt.query(...binds.map(toDuckDbBind))
      if (isResultSetProducer(sql)) {
        const columns = result.schema.fields.map((f) => f.name)
        const rows    = []
        for (const row of result.toArray()) {
          // Arrow Row → plain values array via column lookup.
          const values = columns.map((c) => normalizeDuckDbValue(row[c]))
          rows.push(makeRow(columns, values))
        }
        return { kind: 'rows', rows }
      }
      // DML / DDL: duckdb returns affected-row counts in the result;
      // a portable surface is to inspect numRows.
      return { kind: 'update', count: result.numRows ?? 0 }
    } finally {
      await stmt.close()
    }
  }

  async close() {
    if (this._closed) return
    this._closed = true
    try { await this._conn.close() } catch (_) {}
    try { await this._db.terminate() } catch (_) {}
    try { await this._worker.terminate() } catch (_) {}
  }
}

function toDuckDbBind(v) {
  if (v === undefined) return null
  if (v instanceof Date) return v   // duckdb-wasm handles Date natively
  return v
}

function normalizeDuckDbValue(v) {
  // Arrow can return BigInt / Date / Uint8Array etc — pass through.
  // Convert Arrow Vector to JS array if encountered.
  return v
}
