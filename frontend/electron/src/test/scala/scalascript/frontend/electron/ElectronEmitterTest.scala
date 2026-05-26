package scalascript.frontend.electron

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.ast.{DatabaseDecl, Manifest, Module}

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
    active should include ("\"asarUnpack\": [")
    active should include ("\"node_modules/sql.js/dist/*.wasm\"")
    active should include ("\"vendor/sqljs/*.wasm\"")
  }

  test("bundle builder copies vendored sql.js assets for database-backed bundles") {
    val module = Module(
      manifest = Some(Manifest(
        name = Some("app"),
        version = None,
        description = None,
        dependencies = Map.empty,
        exports = Nil,
        targets = Nil,
        routes = Nil,
        pkg = None,
        translations = Map.empty,
        databases = List(DatabaseDecl("default", "sqlite:./todos.db")),
        raw = Map.empty
      )),
      sections = Nil
    )
    val out = os.temp.dir(prefix = "ssc-electron-vendor-", deleteOnExit = true)
    ElectronBundleBuilder.write(module, "app", baseDir = None, out)
    os.exists(out / "vendor" / "sqljs" / "sql-wasm.js") shouldBe true
    os.exists(out / "vendor" / "sqljs" / "sql-wasm.wasm") shouldBe true
    os.size(out / "vendor" / "sqljs" / "sql-wasm.wasm") should be > 0L
  }

  test("bundle builder injects JVM REST backend base URL when provided") {
    val module = Module(manifest = None, sections = Nil)
    val out = os.temp.dir(prefix = "ssc-electron-jvm-rest-", deleteOnExit = true)
    ElectronBundleBuilder.write(
      module,
      "app",
      baseDir = None,
      out,
      backendBaseUrl = Some("http://127.0.0.1:49152")
    )
    val appJs = os.read(out / "app.js")
    appJs should include ("globalThis.__sscBackendBaseUrl = \"http://127.0.0.1:49152\"")
    appJs should include ("_ssc_frontend_name = 'electron'")
  }

  test("bundle builder includes fetch overlay routing relative fetches to backend URL") {
    val module = Module(manifest = None, sections = Nil)
    val out = os.temp.dir(prefix = "ssc-electron-fetch-overlay-", deleteOnExit = true)
    ElectronBundleBuilder.write(module, "app", baseDir = None, out,
      backendBaseUrl = Some("http://127.0.0.1:49152"))
    val appJs = os.read(out / "app.js")
    appJs should include ("__sscBackendBaseUrl")
    appJs should include ("_spaFetchPath")
    appJs should include ("_ssc_native_fetch")
    appJs should include ("globalThis.fetch = function")
  }

  test("bundle builder without backend URL omits __sscBackendBaseUrl injection") {
    val module = Module(manifest = None, sections = Nil)
    val out = os.temp.dir(prefix = "ssc-electron-no-backend-", deleteOnExit = true)
    ElectronBundleBuilder.write(module, "app", baseDir = None, out, backendBaseUrl = None)
    val appJs = os.read(out / "app.js")
    appJs should not include ("__sscBackendBaseUrl = ")
    appJs should include ("_spaFetchPath")
  }

  test("bundle builder injects desktop security token when provided") {
    val module = Module(manifest = None, sections = Nil)
    val out = os.temp.dir(prefix = "ssc-electron-token-", deleteOnExit = true)
    ElectronBundleBuilder.write(module, "app", baseDir = None, out,
      backendBaseUrl = Some("http://127.0.0.1:49152"),
      desktopToken   = Some("test-uuid-1234"))
    val appJs = os.read(out / "app.js")
    appJs should include ("globalThis.__sscDesktopToken = \"test-uuid-1234\"")
    appJs should include ("x-scalascript-desktop-token")
  }

  test("bundle builder omits desktop token injection when not provided") {
    val module = Module(manifest = None, sections = Nil)
    val out = os.temp.dir(prefix = "ssc-electron-no-token-", deleteOnExit = true)
    ElectronBundleBuilder.write(module, "app", baseDir = None, out,
      backendBaseUrl = Some("http://127.0.0.1:49152"))
    val appJs = os.read(out / "app.js")
    appJs should not include ("__sscDesktopToken = ")
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
