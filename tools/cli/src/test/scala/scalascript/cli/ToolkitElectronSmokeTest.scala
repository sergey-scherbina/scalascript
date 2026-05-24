package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.ast.DatabaseDecl
import scalascript.frontend.electron.ElectronBundleBuilder
import scalascript.frontend.electron.ElectronPersistenceBridge

class ToolkitElectronSmokeTest extends AnyFunSuite with Matchers:

  test("toolkit-demo Electron bundle renders, routes Add, and persists after restart"):
    val electronAvailable =
      scala.util.Try(os.proc("electron", "--version").call(check = false).exitCode == 0)
        .getOrElse(false)
    if !electronAvailable then cancel("electron is not available on PATH")

    val root = repoRoot()
    val out  = os.temp.dir(prefix = "ssc-electron-smoke-", deleteOnExit = true)
    val src  = root / "examples" / "frontend" / "toolkit-demo" / "toolkit-demo.ssc"

    ElectronBundleBuilder.build(src, out)
    val bridgeJs = ElectronPersistenceBridge.mainProcessJs(List(DatabaseDecl("default", "sqlite:./todos.db")))
    os.write.over(out / "main.js", smokeMainJs(bridgeJs))

    val result = os.proc("electron", out.toString)
      .call(cwd = out, check = false, timeout = 15000)

    val output = result.out.text() + result.err.text()
    withClue(output) {
      result.exitCode shouldBe 0
    }
    output should include ("SMOKE_OK")

    os.write.over(out / "main.js", persistenceMainJs(bridgeJs))
    val restartResult = os.proc("electron", out.toString)
      .call(cwd = out, check = false, timeout = 15000)

    val restartOutput = restartResult.out.text() + restartResult.err.text()
    withClue(restartOutput) {
      restartResult.exitCode shouldBe 0
    }
    restartOutput should include ("PERSIST_OK")

  private def smokeMainJs(bridgeJs: String): String =
    s"""'use strict'
      |const { app, BrowserWindow, ipcMain } = require('electron')
      |const path = require('path')
      |const fs = require('fs')
      |app.setPath('userData', path.join(__dirname, '.ssc-user-data'))
      |$bridgeJs
      |
      |function sleep(ms) {
      |  return new Promise(resolve => setTimeout(resolve, ms))
      |}
      |
      |function fail(win, message) {
      |  console.error('SMOKE_FAIL ' + message)
      |  app.exit(10)
      |}
      |
      |function createWindow() {
      |  const win = new BrowserWindow({
      |    width: 900,
      |    height: 700,
      |    show: false,
      |    webPreferences: {
      |      preload: path.join(__dirname, 'preload.js'),
      |      contextIsolation: true,
      |      nodeIntegration: false
      |    }
      |  })
      |
      |  win.webContents.on('console-message', (_event, _level, message) => {
      |    console.log('renderer: ' + message)
      |  })
      |
      |  win.webContents.on('did-fail-load', (_event, code, description) => {
      |    fail(win, code + ' ' + description)
      |  })
      |
      |  win.webContents.on('did-finish-load', async () => {
      |    try {
      |      await sleep(1200)
      |
      |      const initialText = await win.webContents.executeJavaScript('document.body.innerText')
      |      if (!initialText.includes('Toolkit demo (electron)')) {
      |        fail(win, 'initial render missing: ' + initialText)
      |        return
      |      }
      |
      |      const result = await win.webContents.executeJavaScript(`
      |        (async () => {
      |          if (!window.__sscElectron || !window.__sscElectron.db) {
      |            throw new Error('missing Electron database bridge');
      |          }
      |          const dbs = await window.__sscElectron.db.list();
      |          if (!dbs.ok || !dbs.names.includes('default')) {
      |            throw new Error('database bridge did not list default: ' + JSON.stringify(dbs));
      |          }
      |
      |          localStorage.clear();
      |          const accept = document.querySelector('input[type=checkbox]');
      |          if (!accept) throw new Error('missing accept checkbox');
      |          accept.checked = true;
      |          accept.dispatchEvent(new Event('change', { bubbles: true }));
      |
      |          await new Promise(resolve => setTimeout(resolve, 100));
      |          const acceptButtons = [...document.querySelectorAll('button')]
      |            .filter(button => button.textContent.trim() === 'Accept');
      |          if (acceptButtons.length === 0) throw new Error('missing Accept button');
      |          acceptButtons.forEach(button => button.click());
      |
      |          await new Promise(resolve => setTimeout(resolve, 200));
      |          const addButton = [...document.querySelectorAll('[data-ssc-fetch-url="/api/todos"]')][0];
      |          if (!addButton) throw new Error('missing Add fetch button');
      |          const textInputs = [...document.querySelectorAll('input[type=text]')];
      |          const itemInput = textInputs[textInputs.length - 1];
      |          if (!itemInput) throw new Error('missing new-item input');
      |          itemInput.value = 'Electron smoke todo';
      |          itemInput.dispatchEvent(new Event('input', { bubbles: true }));
      |          addButton.click();
      |
      |          await new Promise(resolve => setTimeout(resolve, 500));
      |          const text = document.body.innerText;
      |          const api = await fetch('/api/todos').then(r => r.text());
      |          return { text, api };
      |        })()
      |      `)
      |
      |      if (!result.text.includes('Electron smoke todo') || !result.api.includes('Electron smoke todo')) {
      |        fail(win, 'todo was not added; body=' + result.text + '; api=' + result.api)
      |        return
      |      }
      |
      |      console.log('SMOKE_OK')
      |      app.exit(0)
      |    } catch (error) {
      |      fail(win, error && error.stack ? error.stack : String(error))
      |    }
      |  })
      |
      |  win.loadFile('index.html')
      |}
      |
      |app.whenReady().then(async () => {
      |  await __sscInitDatabases()
      |  createWindow()
      |})
      |app.on('window-all-closed', () => app.quit())
      |""".stripMargin

  private def persistenceMainJs(bridgeJs: String): String =
    s"""'use strict'
      |const { app, BrowserWindow, ipcMain } = require('electron')
      |const path = require('path')
      |const fs = require('fs')
      |app.setPath('userData', path.join(__dirname, '.ssc-user-data'))
      |$bridgeJs
      |
      |function sleep(ms) {
      |  return new Promise(resolve => setTimeout(resolve, ms))
      |}
      |
      |function fail(win, message) {
      |  console.error('PERSIST_FAIL ' + message)
      |  app.exit(20)
      |}
      |
      |function createWindow() {
      |  const win = new BrowserWindow({
      |    width: 900,
      |    height: 700,
      |    show: false,
      |    webPreferences: {
      |      preload: path.join(__dirname, 'preload.js'),
      |      contextIsolation: true,
      |      nodeIntegration: false
      |    }
      |  })
      |
      |  win.webContents.on('console-message', (_event, _level, message) => {
      |    console.log('renderer: ' + message)
      |  })
      |
      |  win.webContents.on('did-fail-load', (_event, code, description) => {
      |    fail(win, code + ' ' + description)
      |  })
      |
      |  win.webContents.on('did-finish-load', async () => {
      |    try {
      |      await sleep(800)
      |      const api = await win.webContents.executeJavaScript(`
      |        fetch('/api/todos').then(r => r.text())
      |      `)
      |      if (!api.includes('Electron smoke todo')) {
      |        fail(win, 'persisted todo missing; api=' + api)
      |        return
      |      }
      |      console.log('PERSIST_OK')
      |      app.exit(0)
      |    } catch (error) {
      |      fail(win, error && error.stack ? error.stack : String(error))
      |    }
      |  })
      |
      |  win.loadFile('index.html')
      |}
      |
      |app.whenReady().then(async () => {
      |  await __sscInitDatabases()
      |  createWindow()
      |})
      |app.on('window-all-closed', () => app.quit())
      |""".stripMargin

  private def repoRoot(): os.Path =
    LazyList.iterate(os.pwd)(p => p / os.up)
      .take(8)
      .find(p => os.exists(p / "build.sbt") && os.exists(p / "examples" / "frontend" / "toolkit-demo" / "toolkit-demo.ssc"))
      .getOrElse(os.pwd)
