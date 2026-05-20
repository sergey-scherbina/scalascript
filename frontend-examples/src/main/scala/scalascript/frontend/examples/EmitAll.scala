package scalascript.frontend.examples

import java.nio.file.{Files, Path, Paths}
import scalascript.frontend.*
import scalascript.frontend.custom.CustomFrameworkBackend
import scalascript.frontend.react.ReactFrameworkBackend
import scalascript.frontend.solid.SolidFrameworkBackend
import scalascript.frontend.vue.VueFrameworkBackend

/** Lowers each of the three reference demos through each of the
 *  four frontend backends and writes the resulting `index.html` +
 *  `app.js` pair to
 *  `<outDir>/<demo>/<backend>/{index.html,app.js}`.
 *
 *  Run via:
 *  {{{
 *    sbt "frontendExamples/runMain scalascript.frontend.examples.EmitAll"
 *    # or with an explicit out-dir argument:
 *    sbt "frontendExamples/runMain scalascript.frontend.examples.EmitAll /tmp/ssc-spa"
 *  }}}
 *
 *  Default out-dir is `target/frontend-examples/` (relative to the
 *  process cwd — typically the repo root when launched from sbt). */
object EmitAll:

  /** All shipped demos in canonical order. */
  val demos: List[(String, () => FrontendModule)] = List(
    CounterDemo.Name  -> (() => CounterDemo.buildModule()),
    ShowHideDemo.Name -> (() => ShowHideDemo.buildModule()),
    TodoListDemo.Name -> (() => TodoListDemo.buildModule())
  )

  /** Fresh instances per call — backends carry no state but it's
   *  honest to instantiate them where we use them. */
  def allBackends(): List[FrontendFrameworkSpi] = List(
    new CustomFrameworkBackend,
    new ReactFrameworkBackend,
    new SolidFrameworkBackend,
    new VueFrameworkBackend
  )

  def main(args: Array[String]): Unit =
    val outDir = args.headOption.map(Paths.get(_)).getOrElse(
      Paths.get("target", "frontend-examples")
    )
    val written = emitAll(outDir)
    println(s"Wrote ${written.size} files to $outDir")
    written.foreach(p => println(s"  $p"))

  /** Emit every (demo, backend) combination under `outDir`.
   *  Returns the list of files written. */
  def emitAll(outDir: Path): List[Path] =
    Files.createDirectories(outDir)
    val backends = allBackends()
    val written  = scala.collection.mutable.ListBuffer.empty[Path]
    for (demoName, buildIr) <- demos do
      val demoDir = outDir.resolve(demoName)
      Files.createDirectories(demoDir)
      for backend <- backends do
        // Build fresh IR per backend — ReactiveSignal cells are
        // currently mutable JVM objects; we don't want a click in one
        // backend's emit-time evaluation to leak into the next.
        val emitted = backend.emit(buildIr())
        val sub = demoDir.resolve(backend.name)
        Files.createDirectories(sub)
        val htmlPath = sub.resolve("index.html")
        val jsPath   = sub.resolve("app.js")
        Files.writeString(htmlPath, emitted.html)
        Files.writeString(jsPath,   emitted.js)
        written += htmlPath += jsPath
        if emitted.css.nonEmpty then
          val cssPath = sub.resolve("app.css")
          Files.writeString(cssPath, emitted.css)
          written += cssPath
    written.toList
