package scalascript.frontend.electron

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.ast.DatabaseDecl

class ElectronEmitterTest extends AnyFunSuite with Matchers:

  test("mainJs contains BrowserWindow creation with given title") {
    val js = ElectronEmitter.mainJs("My App")
    js should include ("require('electron')")
    js should include ("new BrowserWindow(")
    js should include ("\"My App\"")
    js should include ("win.loadFile('index.html')")
    js should include ("app.whenReady()")
    js should include ("window-all-closed")
  }

  test("mainJs respects custom width and height") {
    val js = ElectronEmitter.mainJs("App", width = 800, height = 600)
    js should include ("width: 800")
    js should include ("height: 600")
  }

  test("mainJs has contextIsolation and no nodeIntegration") {
    val js = ElectronEmitter.mainJs("App")
    js should include ("contextIsolation: true")
    js should include ("nodeIntegration: false")
  }

  test("mainJs has macOS activate / quit logic") {
    val js = ElectronEmitter.mainJs("App")
    js should include ("'activate'")
    js should include ("process.platform !== 'darwin'")
    js should include ("app.quit()")
  }

  test("preloadJs is non-empty valid JS stub") {
    val js = ElectronEmitter.preloadJs
    js should include ("'use strict'")
    js.trim.nonEmpty shouldBe true
  }

  test("preloadJs exposes narrow database bridge when databases are declared") {
    val js = ElectronEmitter.preloadJs(List(DatabaseDecl("default", "sqlite:./todos.db")))
    js should include ("contextBridge.exposeInMainWorld('__sscElectron'")
    js should include ("list()")
    js should include ("ipcRenderer.sendSync('ssc:db:list', {})")
    js should include ("query(dbName, sql, params)")
    js should not include ("ipcRenderer:")
  }

  test("mainJs registers database IPC handlers only when databases are declared") {
    val inert = ElectronEmitter.mainJs("App")
    inert should not include ("ipcMain.on('ssc:db:list'")
    inert should not include ("__sscDbRegistry")
    inert should not include ("require('fs')")

    val active = ElectronEmitter.mainJs("App", databases = List(DatabaseDecl("default", "sqlite:./todos.db")))
    active should include ("const { app, BrowserWindow, ipcMain } = require('electron')")
    active should include ("const fs = require('fs')")
    active should include ("await __sscInitDatabases()")
    active should include ("__sscDbRegistry")
    active should include ("default")
    active should include ("sqlite:./todos.db")
    active should include ("ipcMain.on('ssc:db:list'")
    active should include ("ipcMain.on('ssc:db:query'")
    active should include ("require('sql.js')")
    active should include ("app.asar.unpacked")
  }

  test("packageJson includes sql.js only for database-backed bundles") {
    val inert = ElectronEmitter.packageJson("app")
    inert should not include ("\"sql.js\"")
    inert should not include ("asarUnpack")

    val active = ElectronEmitter.packageJson("app", databases = List(DatabaseDecl("default", "sqlite:./todos.db")))
    active should include ("\"sql.js\"")
    active should include ("\"asarUnpack\": [\"node_modules/sql.js/dist/*.wasm\"]")
  }

  test("packageJson has required npm fields") {
    val json = ElectronEmitter.packageJson("my-app")
    json should include ("\"main\": \"main.js\"")
    json should include ("\"start\": \"electron .\"")
    json should include ("\"build\": \"electron-builder\"")
    json should include ("\"electron\":")
    json should include ("\"electron-builder\":")
  }

  test("packageJson sanitises name to npm-compatible form") {
    val json = ElectronEmitter.packageJson("My Cool App!")
    json should include ("\"my-cool-app\"")
  }

  test("packageJson includes electron-builder platform targets") {
    val json = ElectronEmitter.packageJson("app")
    json should include ("\"target\": \"dmg\"")
    json should include ("\"target\": \"nsis\"")
    json should include ("\"target\": \"AppImage\"")
  }

  test("indexHtml loads app.js as module and has CSP") {
    val html = ElectronEmitter.indexHtml("Hello")
    html should include ("<title>Hello</title>")
    html should include ("src=\"./app.js\"")
    html should include ("type=\"module\"")
    html should include ("Content-Security-Policy")
    html should include ("style-src 'self' 'unsafe-inline'")
    html should include ("<div id=\"app\">")
  }

  test("indexHtml inlines extra CSS when provided") {
    val html = ElectronEmitter.indexHtml("App", css = "body { margin: 0 }")
    html should include ("<style>")
    html should include ("body { margin: 0 }")
  }
