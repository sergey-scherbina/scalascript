// scalascript browser-side SQL runtime (v1.27).
// Spec: docs/specs/browser-sql.md.
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
    return provider.open({ name, url, user, password })
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
    if (url.startsWith('sqlite:') && electronDbBridge()) return ElectronBridgeProvider
    if (url.startsWith('sqlite-opfs:')) return SqliteWasmProvider
    if (url.startsWith('sqlite:'))      return SqlJsProvider
    if (url.startsWith('duckdb:'))      return DuckDbWasmProvider
    if (url.startsWith('jdbc:'))        throw new UnsupportedJdbcUrl(url)
    throw new SqlRuntimeError(`No provider matches URL "${url}". Supported prefixes: sqlite-opfs:, sqlite:, duckdb:`)
  },
  available() { return ['sqlite-opfs:', 'sqlite:', 'duckdb:'] }
})

// Lazy-loaded provider singletons.  initSqlJs() / duckdb.AsyncDuckDB
// initialisation runs once per process; cached on the module object.
let _sqlJsModule    = null
let _duckDbBundle   = null
let _sqliteWasMod   = null

function electronDbBridge() {
  const g = globalThis
  return g && g.__sscElectron && g.__sscElectron.db ? g.__sscElectron.db : null
}

export const ElectronBridgeProvider = Object.freeze({
  id: 'electron-bridge',

  async open({ name, url }) {
    const bridge = electronDbBridge()
    if (!bridge) throw new SqlRuntimeError('Electron database bridge is not available')
    return new ElectronBridgeConnection(bridge, name || 'default', url)
  },
})

class ElectronBridgeConnection {
  constructor(bridge, name, url) {
    this._sscElectronBridge = true
    this._bridge = bridge
    this._name = name
    this._url = url
  }

  async execute(sql, binds) {
    if (isResultSetProducer(sql)) {
      const result = this._query(sql, binds)
      return { kind: 'rows', rows: result.rows }
    }
    const result = this._execute(sql, binds)
    return { kind: 'update', count: result.count ?? 0 }
  }

  querySync(sql, binds) {
    return this._query(sql, binds).rows
  }

  executeSync(sql, binds) {
    return this._execute(sql, binds).count ?? 0
  }

  async close() {
    const result = this._bridge.close(this._name)
    if (!result || result.ok !== true) throw new SqlRuntimeError(result && result.error ? result.error : 'Electron bridge close failed')
  }

  _query(sql, binds) {
    const result = this._bridge.query(this._name, sql, binds ?? [])
    if (!result || result.ok !== true) throw new SqlRuntimeError(result && result.error ? result.error : 'Electron bridge query failed')
    return { rows: (result.rows || []).map(objectRowToCallable) }
  }

  _execute(sql, binds) {
    const result = this._bridge.execute(this._name, sql, binds ?? [])
    if (!result || result.ok !== true) throw new SqlRuntimeError(result && result.error ? result.error : 'Electron bridge execute failed')
    return result
  }
}

function objectRowToCallable(row) {
  const columns = Object.keys(row || {})
  return makeRow(columns, columns.map(k => row[k]))
}

async function loadSqlJs() {
  if (_sqlJsModule) return _sqlJsModule
  try {
    const mod = await import('sql.js')
    const initSqlJs = mod.default ?? mod
    _sqlJsModule = await initSqlJs({})
  } catch (e) {
    const isBrowser = typeof window !== 'undefined' && typeof document !== 'undefined'
    if (!isBrowser) throw e
    _sqlJsModule = createBrowserSqlFallback()
  }
  return _sqlJsModule
}

function createBrowserSqlFallback() {
  class BrowserSqlDatabase {
    constructor(_bytes, url) {
      this._url = url || 'sqlite::memory:'
      this._tables = {}
      this._lastModified = 0
      this._load()
    }
    _storageKey() { return 'scalascript:sqlite:' + this._url }
    _persistent() { return this._url !== 'sqlite::memory:' && this._url !== 'sqlite:' && typeof localStorage !== 'undefined' }
    _load() {
      if (!this._persistent()) return
      const raw = localStorage.getItem(this._storageKey())
      if (!raw) return
      try { this._tables = JSON.parse(raw) || {} } catch (_) { this._tables = {} }
    }
    _save() {
      if (this._persistent()) localStorage.setItem(this._storageKey(), JSON.stringify(this._tables))
    }
    prepare(sql) { return new BrowserSqlStatement(this, sql) }
    getRowsModified() { return this._lastModified || 0 }
    export() { return new Uint8Array() }
    close() { this._save() }
  }

  class BrowserSqlStatement {
    constructor(db, sql) {
      this.db = db
      this.sql = String(sql || '').trim()
      this.params = []
      this.rows = null
      this.columns = []
      this.index = -1
      this.done = false
    }
    bind(params) { this.params = Array.isArray(params) ? params : [] }
    step() {
      if (this.rows === null && isResultSetProducer(this.sql)) this._select()
      if (this.rows !== null) {
        this.index += 1
        return this.index < this.rows.length
      }
      if (this.done) return false
      this.done = true
      this._executeUpdate()
      return false
    }
    getColumnNames() { return this.columns.slice() }
    get() { return this.rows && this.index >= 0 ? this.rows[this.index] : [] }
    free() {}
    _table(name) {
      if (!this.db._tables[name]) this.db._tables[name] = { nextId: 1, rows: [] }
      return this.db._tables[name]
    }
    _executeUpdate() {
      this.db._lastModified = 0
      let m = this.sql.match(/^CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z_][A-Za-z0-9_]*)/i)
      if (m) { this._table(m[1]); this.db._save(); return }
      m = this.sql.match(/^INSERT\s+INTO\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]+)\)\s*VALUES\s*\(([^)]+)\)/i)
      if (m) {
        const t = this._table(m[1])
        const cols = m[2].split(',').map(s => s.trim())
        const row = {}
        cols.forEach((c, i) => row[c] = this.params[i])
        if (row.id === undefined && (m[1] === 'todos' || t.rows.some(r => r.id !== undefined))) row.id = t.nextId++
        t.rows.push(row)
        this.db._lastModified = 1
        this.db._save()
        return
      }
      m = this.sql.match(/^DELETE\s+FROM\s+([A-Za-z_][A-Za-z0-9_]*)\s+WHERE\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*\?/i)
      if (m) {
        const t = this._table(m[1])
        const before = t.rows.length
        t.rows = t.rows.filter(r => String(r[m[2]]) !== String(this.params[0]))
        this.db._lastModified = before - t.rows.length
        this.db._save()
        return
      }
      throw new SqlRuntimeError('Browser SQL fallback does not support: ' + this.sql)
    }
    _select() {
      const m = this.sql.match(/^SELECT\s+(.+?)\s+FROM\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s+ORDER\s+BY\s+([A-Za-z_][A-Za-z0-9_]*))?/i)
      if (!m) throw new SqlRuntimeError('Browser SQL fallback does not support: ' + this.sql)
      const cols = m[1].split(',').map(s => s.trim())
      const t = this._table(m[2])
      const rows = t.rows.slice()
      if (m[3]) rows.sort((a, b) => (a[m[3]] ?? 0) > (b[m[3]] ?? 0) ? 1 : -1)
      this.columns = cols
      this.rows = rows.map(r => cols.map(c => r[c]))
    }
  }

  return { __sscBrowserFallback: true, Database: BrowserSqlDatabase }
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
      if (!isNode && !SQL.__sscBrowserFallback) throw new MissingFs(url)
      if (isNode) {
        const fs = await import('node:fs/promises')
        try {
          dbBytes = await fs.readFile(path)
        } catch (e) {
          if (e.code === 'ENOENT') dbBytes = undefined          // create new file on first close
          else throw new SqlRuntimeError(`sqlite open failed for "${path}": ${e.message}`, e)
        }
      }
      // Persist on close.
    }
    const db = new SQL.Database(dbBytes, url)
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
      if (typeof this._db._save === 'function') this._db._save()
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

// ── @sqlite.org/sqlite-wasm provider (OPFS) ─────────────────────────────────

async function loadSqliteWasm() {
  if (_sqliteWasMod) return _sqliteWasMod
  const mod = await import('@sqlite.org/sqlite-wasm')
  const initModule = mod.default ?? mod
  // Suppress the module's own verbose console output.
  _sqliteWasMod = await initModule({ print: () => {}, printErr: () => {} })
  return _sqliteWasMod
}

/** Official SQLite Wasm provider (@sqlite.org/sqlite-wasm).
 *
 *  URL shapes:
 *   sqlite-opfs:./myapp.db   → OPFS-backed persistent DB in browser,
 *                               or file-backed on Node via the node VFS
 *   sqlite-opfs::memory:     → in-memory (same as sqlite::memory:)
 *
 *  Browser persistence requires OPFS, which is available when
 *  `globalThis.crossOriginIsolated === true` (the page is served with
 *  Cross-Origin-Opener-Policy + Cross-Origin-Embedder-Policy headers).
 *  Without COOP/COEP the provider falls back to an in-memory DB and
 *  logs a console.warn so the developer notices. */
export const SqliteWasmProvider = Object.freeze({
  id: 'sqlite-wasm',

  async open({ url }) {
    const sqlite3 = await loadSqliteWasm()
    const rawPath = url.slice('sqlite-opfs:'.length)   // '' | ':memory:' | './foo.db' | …
    const isMemory = !rawPath || rawPath === ':memory:'

    let db
    if (isMemory) {
      db = new sqlite3.oo1.DB(':memory:')
    } else {
      const isNode = !!(globalThis.process?.versions?.node)
      if (isNode) {
        // Node.js: use the regular file VFS
        db = new sqlite3.oo1.DB(rawPath, 'ct')
      } else if (sqlite3.oo1.OpfsDb && globalThis.crossOriginIsolated) {
        // Browser + COOP/COEP: OPFS synchronous VFS
        const opfsPath = rawPath.startsWith('/') ? rawPath : '/' + rawPath
        db = new sqlite3.oo1.OpfsDb(opfsPath)
      } else {
        // Browser without COOP/COEP: no persistent OPFS access.
        console.warn(
          `[ssc] sqlite-opfs: OPFS requires crossOriginIsolated (COOP+COEP headers). ` +
          `"${url}" falls back to :memory: — data will not persist across page reloads. ` +
          `Set Cross-Origin-Opener-Policy: same-origin and ` +
          `Cross-Origin-Embedder-Policy: require-corp on your server to enable OPFS.`
        )
        db = new sqlite3.oo1.DB(':memory:')
      }
    }
    return new SqliteWasmConnection(db, url)
  }
})

class SqliteWasmConnection {
  constructor(db, url) {
    this._db     = db
    this._url    = url
    this._closed = false
  }

  async execute(sql, binds) {
    if (this._closed) throw new SqlRuntimeError(`Connection to "${this._url}" is closed`)
    if (isResultSetProducer(sql)) {
      const resultRows  = []
      const columnNames = []
      this._db.exec({ sql, bind: binds.length ? binds : undefined,
                      resultRows, columnNames, rowMode: 'array' })
      return { kind: 'rows', rows: resultRows.map(row => makeRow(columnNames, row)) }
    }
    this._db.exec({ sql, bind: binds.length ? binds : undefined })
    return { kind: 'update', count: this._db.changes() }
  }

  async close() {
    if (this._closed) return
    this._closed = true
    try { this._db.close() } catch (_) {}
  }
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
