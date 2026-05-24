package scalascript.frontend.electron

import scalascript.ast.DatabaseDecl

/** Generates the narrow Electron main/preload database bridge.
 *
 *  Phase 1 exposes declared database names through IPC. Query/execute are
 *  registered now so the preload contract is stable, but intentionally return
 *  a clear "not implemented" error until the SQLite engine lands. */
object ElectronPersistenceBridge:

  def enabled(databases: List[DatabaseDecl]): Boolean = databases.nonEmpty

  def mainProcessJs(databases: List[DatabaseDecl]): String =
    if databases.isEmpty then ""
    else
      s"""
         |const __sscDbRegistry = Object.freeze(${registryJson(databases)})
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
         |ipcMain.handle('ssc:db:list', async () => {
         |  return { ok: true, names: __sscDbNames() }
         |})
         |
         |ipcMain.handle('ssc:db:query', async (_event, payload) => {
         |  try {
         |    __sscRequireDbName(payload)
         |    return { ok: false, error: 'Electron SQL bridge query is not implemented yet' }
         |  } catch (error) {
         |    return __sscDbError(error)
         |  }
         |})
         |
         |ipcMain.handle('ssc:db:execute', async (_event, payload) => {
         |  try {
         |    __sscRequireDbName(payload)
         |    return { ok: false, error: 'Electron SQL bridge execute is not implemented yet' }
         |  } catch (error) {
         |    return __sscDbError(error)
         |  }
         |})
         |
         |ipcMain.handle('ssc:db:close', async (_event, payload) => {
         |  try {
         |    __sscRequireDbName(payload)
         |    return { ok: true }
         |  } catch (error) {
         |    return __sscDbError(error)
         |  }
         |})
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
         |      return ipcRenderer.invoke('ssc:db:query', { dbName, sql, params })
         |    },
         |    execute(dbName, sql, params) {
         |      return ipcRenderer.invoke('ssc:db:execute', { dbName, sql, params })
         |    },
         |    close(dbName) {
         |      return ipcRenderer.invoke('ssc:db:close', { dbName })
         |    },
         |    list() {
         |      return ipcRenderer.invoke('ssc:db:list', {})
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
