package scalascript.frontend.electron

import scalascript.ast.DatabaseDecl

/** Generates the narrow Electron main/preload database bridge. */
object ElectronPersistenceBridge:

  def enabled(databases: List[DatabaseDecl]): Boolean = databases.nonEmpty

  def mainProcessJs(databases: List[DatabaseDecl]): String =
    if databases.isEmpty then ""
    else
      s"""
         |const __sscDbRegistry = Object.freeze(${registryJson(databases)})
         |const __sscDbConnections = new Map()
         |let __sscSqlJs = null
         |let __sscSqlJsDistDir = null
         |
         |function __sscDbNames() {
         |  return Object.keys(__sscDbRegistry)
         |}
         |
         |function __sscRequireDbName(payload) {
         |  const dbName = payload && typeof payload.dbName === 'string' ? payload.dbName : ''
         |  if (!Object.prototype.hasOwnProperty.call(__sscDbRegistry, dbName)) {
         |    throw new Error('No declared database named ' + JSON.stringify(dbName))
         |  }
         |  return dbName
         |}
         |
         |function __sscDbError(error) {
         |  return { ok: false, error: error && error.message ? error.message : String(error) }
         |}
         |
         |function __sscResolveSqlitePath(url) {
         |  if (url === 'sqlite::memory:' || url === 'sqlite:') return null
         |  if (!url.startsWith('sqlite:')) throw new Error('Electron bridge supports only sqlite: URLs, got ' + url)
         |  const raw = url.slice('sqlite:'.length)
         |  if (!raw || raw === ':memory:') return null
         |  if (path.isAbsolute(raw)) throw new Error('Absolute sqlite paths are not allowed in Electron bundles: ' + raw)
         |  const normalized = path.normalize(raw.replace(/^\\.\\//, ''))
         |  if (normalized === '..' || normalized.startsWith('..' + path.sep) || path.isAbsolute(normalized)) {
         |    throw new Error('Parent-relative sqlite paths are not allowed in Electron bundles: ' + raw)
         |  }
         |  return path.join(app.getPath('userData'), normalized)
         |}
         |
         |async function __sscInitSqlJs() {
         |  if (__sscSqlJs) return __sscSqlJs
         |  const initSqlJs = __sscLoadSqlJsFactory()
         |  __sscSqlJs = await initSqlJs({ locateFile: __sscSqlJsLocateFile })
         |  return __sscSqlJs
         |}
         |
         |function __sscLoadSqlJsFactory() {
         |  try {
         |    const resolved = require.resolve('sql.js')
         |    __sscSqlJsDistDir = path.dirname(resolved)
         |    return require('sql.js')
         |  } catch (_) {
         |    __sscSqlJsDistDir = path.join(__dirname, 'vendor', 'sqljs')
         |    return require(path.join(__sscSqlJsDistDir, 'sql-wasm.js'))
         |  }
         |}
         |
         |function __sscSqlJsLocateFile(file) {
         |  const dirs = [
         |    __sscSqlJsDistDir,
         |    path.join(__dirname, 'vendor', 'sqljs')
         |  ].filter(Boolean)
         |  for (const dir of dirs) {
         |    const candidate = path.join(dir, file)
         |    const unpacked = candidate.replace(path.sep + 'app.asar' + path.sep, path.sep + 'app.asar.unpacked' + path.sep)
         |    if (fs.existsSync(unpacked)) return unpacked
         |    if (fs.existsSync(candidate)) return candidate
         |  }
         |  return path.join(__sscSqlJsDistDir || path.join(__dirname, 'vendor', 'sqljs'), file)
         |}
         |
         |async function __sscOpenDb(name) {
         |  if (__sscDbConnections.has(name)) return __sscDbConnections.get(name)
         |  const spec = __sscDbRegistry[name]
         |  if (!spec) throw new Error('No declared database named ' + JSON.stringify(name))
         |  const SQL = await __sscInitSqlJs()
         |  const filePath = __sscResolveSqlitePath(spec.url)
         |  let bytes = undefined
         |  if (filePath) {
         |    fs.mkdirSync(path.dirname(filePath), { recursive: true })
         |    if (fs.existsSync(filePath)) bytes = fs.readFileSync(filePath)
         |  }
         |  const conn = { name, url: spec.url, filePath, db: new SQL.Database(bytes) }
         |  __sscDbConnections.set(name, conn)
         |  return conn
         |}
         |
         |async function __sscInitDatabases() {
         |  for (const name of __sscDbNames()) await __sscOpenDb(name)
         |}
         |
         |function __sscPersistDb(conn) {
         |  if (!conn.filePath) return
         |  fs.mkdirSync(path.dirname(conn.filePath), { recursive: true })
         |  fs.writeFileSync(conn.filePath, Buffer.from(conn.db.export()))
         |}
         |
         |function __sscRows(conn, sql, params) {
         |  const stmt = conn.db.prepare(sql)
         |  try {
         |    stmt.bind(Array.isArray(params) ? params.map(__sscSqlBind) : [])
         |    const rows = []
         |    let columns = null
         |    while (stmt.step()) {
         |      if (columns === null) columns = stmt.getColumnNames()
         |      const values = stmt.get()
         |      const row = {}
         |      for (let i = 0; i < columns.length; i++) row[columns[i]] = values[i]
         |      rows.push(row)
         |    }
         |    return rows
         |  } finally {
         |    stmt.free()
         |  }
         |}
         |
         |function __sscExecute(conn, sql, params) {
         |  const stmt = conn.db.prepare(sql)
         |  try {
         |    stmt.bind(Array.isArray(params) ? params.map(__sscSqlBind) : [])
         |    while (stmt.step()) {}
         |    const count = conn.db.getRowsModified()
         |    __sscPersistDb(conn)
         |    return count
         |  } finally {
         |    stmt.free()
         |  }
         |}
         |
         |function __sscSqlBind(value) {
         |  if (value === undefined || value === null) return null
         |  if (typeof value === 'boolean') return value ? 1 : 0
         |  if (typeof value === 'bigint') throw new Error('BigInt bind values are not supported by the Electron SQL bridge yet')
         |  return value
         |}
         |
         |function __sscDbReply(kind, payload) {
         |  try {
         |    if (kind === 'list') return { ok: true, names: __sscDbNames() }
         |    const name = __sscRequireDbName(payload)
         |    const conn = __sscDbConnections.get(name)
         |    if (!conn) throw new Error('Database ' + JSON.stringify(name) + ' was not initialized')
         |    if (kind === 'query') return { ok: true, rows: __sscRows(conn, String(payload.sql || ''), payload.params) }
         |    if (kind === 'execute') return { ok: true, count: __sscExecute(conn, String(payload.sql || ''), payload.params) }
         |    if (kind === 'close') {
         |      __sscPersistDb(conn)
         |      conn.db.close()
         |      __sscDbConnections.delete(name)
         |      return { ok: true }
         |    }
         |    throw new Error('Unknown Electron database operation: ' + kind)
         |  } catch (error) {
         |    return __sscDbError(error)
         |  }
         |}
         |
         |ipcMain.on('ssc:db:list', event => { event.returnValue = __sscDbReply('list', {}) })
         |ipcMain.on('ssc:db:query', (event, payload) => { event.returnValue = __sscDbReply('query', payload) })
         |ipcMain.on('ssc:db:execute', (event, payload) => { event.returnValue = __sscDbReply('execute', payload) })
         |ipcMain.on('ssc:db:close', (event, payload) => { event.returnValue = __sscDbReply('close', payload) })
         |""".stripMargin

  def preloadJs(databases: List[DatabaseDecl]): String =
    if databases.isEmpty then
      s"""'use strict'
         |// Preload script intentionally empty: this bundle declares no databases.
         |""".stripMargin
    else
      s"""'use strict'
         |const { contextBridge, ipcRenderer } = require('electron')
         |
         |contextBridge.exposeInMainWorld('__sscElectron', {
         |  db: {
         |    query(dbName, sql, params) {
         |      return ipcRenderer.sendSync('ssc:db:query', { dbName, sql, params })
         |    },
         |    execute(dbName, sql, params) {
         |      return ipcRenderer.sendSync('ssc:db:execute', { dbName, sql, params })
         |    },
         |    close(dbName) {
         |      return ipcRenderer.sendSync('ssc:db:close', { dbName })
         |    },
         |    list() {
         |      return ipcRenderer.sendSync('ssc:db:list', {})
         |    }
         |  }
         |})
         |""".stripMargin

  private def registryJson(databases: List[DatabaseDecl]): String =
    val entries = databases.map { db =>
      s"${jsString(db.name)}: { url: ${jsString(db.url)} }"
    }
    "{ " + entries.mkString(", ") + " }"

  private def jsString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
