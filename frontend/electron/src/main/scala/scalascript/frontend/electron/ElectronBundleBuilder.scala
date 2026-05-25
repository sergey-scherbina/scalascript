package scalascript.frontend.electron

import scalascript.ast.*
import scalascript.codegen.{JsGen, JsRuntimeBrowserPatch}
import scalascript.parser.Parser

/** Builds the five-file Electron project consumed by both CLI dev-run/build
 *  and regression tests. */
object ElectronBundleBuilder:

  def build(sscFile: os.Path, outDir: os.Path, backendBaseUrl: Option[String] = None): Unit =
    val module  = Parser.parse(os.read(sscFile))
    val title   = module.manifest.flatMap(_.name).getOrElse(sscFile.last.stripSuffix(".ssc"))
    val baseDir = Some(sscFile / os.up)
    write(module, title, baseDir, outDir, backendBaseUrl = backendBaseUrl)

  def write(
      module:         Module,
      title:          String,
      baseDir:        Option[os.Path],
      outDir:         os.Path,
      backendBaseUrl: Option[String] = None
  ): Unit =
    val rawJs     = rawJavaScriptBlocks(module)
    val moduleJs  = JsGen.generate(module, baseDir)
    val databases = module.manifest.toList.flatMap(_.databases)
    val caps =
      JsGen.detectCapabilities(module, baseDir) -
        JsGen.Capability.Mcp -
        JsGen.Capability.Dataset
    val frontendInit = "_ssc_frontend_name = 'electron'; // injected by ssc\n"
    val backendInit = backendBaseUrl.fold("") { url =>
      s"globalThis.__sscBackendBaseUrl = ${jsString(url)}; // injected by ssc jvm-rest\n"
    }
    val appJs = s"${JsGen.generateRuntime(caps)}\n$JsRuntimeBrowserPatch\n$backendInit$frontendInit$rawJs\n$moduleJs"

    os.makeDir.all(outDir)
    os.write.over(outDir / "index.html", ElectronEmitter.indexHtml(title))
    os.write.over(outDir / "app.js", appJs)
    os.write.over(outDir / "main.js", ElectronEmitter.mainJs(title, databases = databases))
    os.write.over(outDir / "preload.js", ElectronEmitter.preloadJs(databases))
    os.write.over(outDir / "package.json", ElectronEmitter.packageJson(title, databases = databases))
    if databases.nonEmpty then copySqlJsVendor(outDir)

  private def rawJavaScriptBlocks(module: Module): String =
    def collect(section: Section): List[String] =
      section.content.collect {
        case cb: Content.CodeBlock if Lang.isJavaScript(cb.lang) => cb.source
      } ++ section.subsections.flatMap(collect)

    module.sections.flatMap(collect).filter(_.nonEmpty).mkString("\n")

  private def copySqlJsVendor(outDir: os.Path): Unit =
    val vendorDir = outDir / "vendor" / "sqljs"
    os.makeDir.all(vendorDir)
    copyResource("scalascript/electron/vendor/sqljs/sql-wasm.js", vendorDir / "sql-wasm.js")
    copyResource("scalascript/electron/vendor/sqljs/sql-wasm.wasm", vendorDir / "sql-wasm.wasm")
    copyResource("scalascript/electron/vendor/sqljs/LICENSE.sql.js", vendorDir / "LICENSE.sql.js")

  private def copyResource(resource: String, dest: os.Path): Unit =
    val cl = Thread.currentThread().getContextClassLoader
    val in = Option(cl.getResourceAsStream(resource))
      .orElse(Option(getClass.getResourceAsStream("/" + resource)))
      .getOrElse(throw IllegalStateException(s"Electron vendor resource missing: $resource"))
    try
      val bytes = in.readAllBytes()
      os.write.over(dest, bytes, createFolders = true)
    finally in.close()

  private def jsString(s: String): String =
    "\"" + s
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r") + "\""
