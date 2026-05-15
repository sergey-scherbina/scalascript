package scalascript.codegen

import scalascript.ast.*

/** Compiles and runs standard `scala` code blocks via Scala.js (scala-cli --js).
 *
 *  `scala` blocks contain plain Scala 3 without ScalaScript extensions.
 *  When targeting the JavaScript platform they are compiled by the Scala.js
 *  compiler rather than our custom transpiler, which gives full language
 *  fidelity (all of Scala 3's type system, collections, etc.).
 *
 *  Two modes:
 *    - `compileToJs`  — compile to a self-contained Node.js script (for emit-js)
 *    - `run`          — compile and run via Node.js (for jssc / ssc-js --run)
 *
 *  Both modes write a temporary `.sc` file, invoke scala-cli with `--js`, and
 *  clean up the temp files afterwards.
 */
object ScalaJsBackend:

  // ─── Module inspection ─────────────────────────────────────────

  def hasBlocks(module: Module): Boolean =
    module.sections.exists(sectionHas)

  private def sectionHas(s: Section): Boolean =
    s.content.exists {
      case cb: Content.CodeBlock => Lang.isStandardScala(cb.lang)
      case _                     => false
    } || s.subsections.exists(sectionHas)

  // ─── Source collection ─────────────────────────────────────────

  /** Concatenate all `scala` block sources into one script. */
  def collectSource(module: Module): String =
    val sb = StringBuilder()
    module.sections.foreach(collectSection(_, sb))
    sb.toString

  private def collectSection(s: Section, sb: StringBuilder): Unit =
    s.content.foreach {
      case Content.CodeBlock(lang, src, _, _) if Lang.isStandardScala(lang) =>
        sb.append(src.stripTrailing()).append("\n\n")
      case _ => ()
    }
    s.subsections.foreach(collectSection(_, sb))

  // ─── Compile to JS ─────────────────────────────────────────────

  /** Compile all `scala` blocks to a self-contained Node.js script using
   *  `scala-cli package --js`.  Returns the full JS source as a String.
   *  Throws RuntimeException on compilation failure.
   */
  def compileToJs(module: Module, baseDir: Option[os.Path] = None): String =
    val src = collectSource(module)
    if src.isBlank then return ""
    val tmp = os.temp(src, suffix = ".sc", deleteOnExit = true)
    val out = os.temp(suffix = ".js", deleteOnExit = true)
    try
      val result = os.proc(
        "scala-cli", "--power", "package",
        "--js",
        "--force",
        "-o", out.toString,
        tmp
      ).call(
        cwd    = baseDir.getOrElse(os.pwd),
        check  = false,
        stderr = os.Pipe
      )
      if result.exitCode != 0 then
        throw RuntimeException(
          s"Scala.js compilation failed (exit ${result.exitCode}):\n${result.err.text()}"
        )
      if os.exists(out) then os.read(out) else ""
    finally
      if os.exists(tmp) then os.remove(tmp)
      if os.exists(out) then os.remove(out)

  // ─── Run ───────────────────────────────────────────────────────

  /** Compile and run all `scala` blocks via `scala-cli run --js` (Node.js).
   *  stdout / stderr pass through to the calling process.
   *  Exits with the scala-cli exit code on failure.
   */
  def run(module: Module, baseDir: Option[os.Path] = None): Unit =
    val src = collectSource(module)
    if src.isBlank then return
    val tmp = os.temp(src, suffix = ".sc", deleteOnExit = true)
    try
      val result = os.proc("scala-cli", "--power", "run", "--js", tmp)
        .call(
          stdout = os.Inherit,
          stderr = os.Inherit,
          cwd    = baseDir.getOrElse(os.pwd),
          check  = false
        )
      if result.exitCode != 0 then System.exit(result.exitCode)
    finally
      if os.exists(tmp) then os.remove(tmp)
