package scalascript.cli

import java.nio.charset.StandardCharsets

object NewProject:
  final case class Options(template: String = "app", outputDir: os.Path = os.pwd)

  def create(name: String, template: String = "app", outputDir: os.Path = os.pwd): os.Path =
    val cleanName = sanitizeName(name)
    if cleanName.isEmpty then throw new IllegalArgumentException("project name must contain at least one letter or digit")

    val target = outputDir / cleanName
    if os.exists(target) && os.list(target).nonEmpty then
      throw new RuntimeException(s"target directory already exists and is not empty: $target")

    val files = templateFiles(template)
    val vars = variables(cleanName)
    files.foreach { case (resourceRel, outputRel) =>
      val renderedRel = replaceVars(outputRel, vars)
      val out = target / os.RelPath(renderedRel)
      val bytes = readResource(s"templates/$template/$resourceRel")
      val text = String(bytes, StandardCharsets.UTF_8)
      os.makeDir.all(out / os.up)
      os.write.over(out, replaceVars(text, vars))
    }
    target

  def parseOptions(args: List[String]): Options =
    var template = "app"
    var outputDir = os.pwd
    val it = args.iterator
    while it.hasNext do
      it.next() match
        case "--template" | "-t" if it.hasNext =>
          template = it.next()
        case "--output-dir" | "--dir" | "-o" if it.hasNext =>
          outputDir = os.Path(it.next(), os.pwd)
        case other =>
          throw new IllegalArgumentException(s"unknown ssc new option: $other")
    Options(template, outputDir)

  private def templateFiles(template: String): List[(String, String)] =
    val raw = String(readResource(s"templates/$template/template-files.txt"), StandardCharsets.UTF_8)
    raw.linesIterator.map(_.trim).filter(s => s.nonEmpty && !s.startsWith("#")).map { line =>
      line.split("->", 2).map(_.trim).toList match
        case resource :: output :: Nil => resource -> output
        case resource :: Nil => resource -> resource
        case _ => throw new RuntimeException(s"invalid template manifest line: $line")
    }.toList

  private def readResource(path: String): Array[Byte] =
    val loader = Thread.currentThread().getContextClassLoader
    val in = Option(loader.getResourceAsStream(path)).getOrElse {
      throw new RuntimeException(s"template resource not found: $path")
    }
    try in.readAllBytes()
    finally in.close()

  private def variables(name: String): Map[String, String] =
    val pascal = name.split("[^A-Za-z0-9]+").filter(_.nonEmpty)
      .map(part => part.head.toUpper.toString + part.drop(1))
      .mkString
    val className = if pascal.isEmpty then "Plugin" else pascal
    val pkg = "com.example." + name.replace('-', '.').replaceAll("[^A-Za-z0-9.]", "").stripPrefix(".")
    Map(
      "name"        -> name,
      "Name"        -> className,
      "packageName" -> pkg,
      "packagePath" -> pkg.replace('.', '/'),
      "version"     -> "0.1.0",
    )

  private def replaceVars(text: String, vars: Map[String, String]): String =
    vars.foldLeft(text) { case (acc, (k, v)) => acc.replace(s"$${$k}", v) }

  private def sanitizeName(raw: String): String =
    raw.trim.toLowerCase
      .replaceAll("[^a-z0-9._-]+", "-")
      .replaceAll("^-+", "")
      .replaceAll("-+$", "")
