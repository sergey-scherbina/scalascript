package scalascript.cli

import java.nio.charset.StandardCharsets

object NewProject:
  /** Every bundled template id — the source of truth `parseOptions` validates `--template`
   *  against, so an unknown name is refused with the list of real ones instead of leaking
   *  `create`'s resource-lookup failure ("template resource not found: templates/bogus/...").  */
  val Templates: Set[String] = Set("app", "lib", "plugin", "dsl", "web-app", "wasm-app")

  /** `plugin` is the one template `sbt` is never optional for: a compiler plugin's intrinsics are
   *  a real JAR, which needs an actual JVM compile — there is no bare-`.ssc` shape for it. */
  val AlwaysSbt: Set[String] = Set("plugin")

  final case class Options(template: String = "app", outputDir: os.Path = os.pwd, sbt: Boolean = false)

  def create(name: String, template: String = "app", outputDir: os.Path = os.pwd, sbt: Boolean = false): os.Path =
    if !Templates.contains(template) then
      throw new IllegalArgumentException(s"unknown template '$template' (available: ${Templates.toList.sorted.mkString(", ")})")
    val cleanName = sanitizeName(name)
    if cleanName.isEmpty then throw new IllegalArgumentException("project name must contain at least one letter or digit")

    val target = outputDir / cleanName
    if os.exists(target) && os.list(target).nonEmpty then
      throw new RuntimeException(s"target directory already exists and is not empty: $target")

    val effectiveSbt = sbt || AlwaysSbt.contains(template)
    val files = templateFiles(template, effectiveSbt)
    val vars = variables(cleanName, template, effectiveSbt)
    files.foreach { case (resourceRel, outputRel) =>
      val renderedRel = replaceVars(outputRel, vars)
      val out = target / os.RelPath(renderedRel)
      val bytes = readResource(s"templates/$template/$resourceRel")
      val text = String(bytes, StandardCharsets.UTF_8)
      os.makeDir.all(out / os.up)
      os.write.over(out, replaceVars(text, vars))
    }
    initGitIfAvailable(target)
    target

  def parseOptions(args: List[String]): Options =
    var template = "app"
    var outputDir = os.pwd
    var sbt = false
    val it = args.iterator
    while it.hasNext do
      it.next() match
        case "--template" | "-t" if it.hasNext =>
          val t = it.next()
          if !Templates.contains(t) then
            throw new IllegalArgumentException(s"unknown template '$t' (available: ${Templates.toList.sorted.mkString(", ")})")
          template = t
        case "--output-dir" | "--dir" | "-o" if it.hasNext =>
          outputDir = os.Path(it.next(), os.pwd)
        case "--sbt" =>
          sbt = true
        case other =>
          throw new IllegalArgumentException(s"unknown ssc new option: $other")
    Options(template, outputDir, sbt)

  /** `sbt:`-prefixed manifest lines are only included when `sbt` is true (the bundled `plugin`
   *  template has none — it needs no flag, since `create` already forces `effectiveSbt` for it). */
  private def templateFiles(template: String, sbt: Boolean): List[(String, String)] =
    val raw = String(readResource(s"templates/$template/template-files.txt"), StandardCharsets.UTF_8)
    raw.linesIterator.map(_.trim).filter(s => s.nonEmpty && !s.startsWith("#")).flatMap { rawLine =>
      val isSbtOnly = rawLine.startsWith("sbt:")
      val line = if isSbtOnly then rawLine.stripPrefix("sbt:").trim else rawLine
      if isSbtOnly && !sbt then None
      else Some(line.split("->", 2).map(_.trim).toList match
        case resource :: output :: Nil => resource -> output
        case resource :: Nil => resource -> resource
        case _ => throw new RuntimeException(s"invalid template manifest line: $rawLine"))
    }.toList

  private def readResource(path: String): Array[Byte] =
    val loader = Thread.currentThread().getContextClassLoader
    val in = Option(loader.getResourceAsStream(path)).getOrElse {
      throw new RuntimeException(s"template resource not found: $path")
    }
    try in.readAllBytes()
    finally in.close()

  private def initGitIfAvailable(target: os.Path): Unit =
    val gitAvailable = scala.util.Try {
      os.proc("git", "--version")
        .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
        .exitCode == 0
    }.getOrElse(false)
    if gitAvailable then
      scala.util.Try {
        os.proc("git", "init", "-q")
          .call(cwd = target, check = false, stdout = os.Pipe, stderr = os.Pipe)
      }.foreach(_ => ())

  private def variables(name: String, template: String, sbt: Boolean): Map[String, String] =
    val pascalRaw = name.split("[^A-Za-z0-9]+").filter(_.nonEmpty)
      .map(part => part.head.toUpper.toString + part.drop(1))
      .mkString
    // A leading digit makes the class/object name itself invalid too (`ssc new 123plugin` ->
    // `object 123plugin extends Backend` in the plugin template's real .scala source) — the SAME
    // rule as the package-segment fix below, applied here because `pascalRaw` has its own
    // independent path from `name` and does not go through that fix.
    val pascal =
      if pascalRaw.nonEmpty && pascalRaw.head.isDigit then s"_$pascalRaw" else pascalRaw
    val className = if pascal.isEmpty then "Plugin" else pascal
    // Each dot-segment must be a valid Scala/Java identifier on its own — a name starting with a
    // digit (`ssc new 123app`) used to produce `package com.example.123app`, which the `plugin`
    // template's real `.scala` sources would then fail to even PARSE. `123app` alone sanitizes to
    // a string with no invalid characters, so nothing upstream of this caught it.
    val nameSegs = name.replace('-', '.').replaceAll("[^A-Za-z0-9.]", "").split('.').filter(_.nonEmpty)
      .map(seg => if seg.head.isDigit then s"_$seg" else seg)
    val pkg = ("com.example" +: nameSegs.toIndexedSeq).mkString(".")
    // The one command that actually runs/builds this template's entry file. Bundled here (not
    // hardcoded per README) so a template's entry-file convention only needs to change in one
    // place if it ever does.
    val runCmd = template match
      case "lib"      => s"ssc check src/main/scalascript/$className.ssc"
      case "dsl"      => "ssc run examples/example.ssc"
      case "web-app"  => "ssc emit-spa src/main/scalascript/App.ssc --out dist"
      case "wasm-app" => "ssc compile-wasm src/main/scalascript/Main.ssc"
      case _          => "ssc run src/main/scalascript/Main.ssc"
    val sbtSection =
      if sbt then "\n\n## Build (sbt)\n\n```bash\nsbt compile\nsbt package\n```"
      else ""
    Map(
      "name"        -> name,
      "Name"        -> className,
      "packageName" -> pkg,
      "packagePath" -> pkg.replace('.', '/'),
      "version"     -> "0.1.0",
      "runCmd"      -> runCmd,
      "sbtSection"  -> sbtSection,
    )

  private def replaceVars(text: String, vars: Map[String, String]): String =
    vars.foldLeft(text) { case (acc, (k, v)) => acc.replace(s"$${$k}", v) }

  private def sanitizeName(raw: String): String =
    raw.trim.toLowerCase
      .replaceAll("[^a-z0-9._-]+", "-")
      .replaceAll("^-+", "")
      .replaceAll("-+$", "")
