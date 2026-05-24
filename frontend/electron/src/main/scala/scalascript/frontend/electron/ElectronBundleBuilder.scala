package scalascript.frontend.electron

import scalascript.ast.*
import scalascript.codegen.{JsGen, JsRuntimeBrowserPatch}
import scalascript.parser.Parser

/** Builds the five-file Electron project consumed by both CLI dev-run/build
 *  and regression tests. */
object ElectronBundleBuilder:

  def build(sscFile: os.Path, outDir: os.Path): Unit =
    val module  = Parser.parse(os.read(sscFile))
    val title   = module.manifest.flatMap(_.name).getOrElse(sscFile.last.stripSuffix(".ssc"))
    val baseDir = Some(sscFile / os.up)
    write(module, title, baseDir, outDir)

  def write(module: Module, title: String, baseDir: Option[os.Path], outDir: os.Path): Unit =
    val rawJs     = rawJavaScriptBlocks(module)
    val moduleJs  = JsGen.generate(module, baseDir)
    val databases = module.manifest.toList.flatMap(_.databases)
    val caps =
      JsGen.detectCapabilities(module, baseDir) -
        JsGen.Capability.Mcp -
        JsGen.Capability.Dataset
    val frontendInit = "_ssc_frontend_name = 'electron'; // injected by ssc\n"
    val appJs = s"${JsGen.generateRuntime(caps)}\n$JsRuntimeBrowserPatch\n$frontendInit$rawJs\n$moduleJs"

    os.makeDir.all(outDir)
    os.write.over(outDir / "index.html", ElectronEmitter.indexHtml(title))
    os.write.over(outDir / "app.js", appJs)
    os.write.over(outDir / "main.js", ElectronEmitter.mainJs(title, databases = databases))
    os.write.over(outDir / "preload.js", ElectronEmitter.preloadJs(databases))
    os.write.over(outDir / "package.json", ElectronEmitter.packageJson(title, databases = databases))

  private def rawJavaScriptBlocks(module: Module): String =
    def collect(section: Section): List[String] =
      section.content.collect {
        case cb: Content.CodeBlock if Lang.isJavaScript(cb.lang) => cb.source
      } ++ section.subsections.flatMap(collect)

    module.sections.flatMap(collect).filter(_.nonEmpty).mkString("\n")
