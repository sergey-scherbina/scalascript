package scalascript.codegen

import scalascript.ast.*

/** Generates a Scala 3 script (.sc) from a ScalaScript module.
 *
 *  The output is valid scala-cli script syntax: top-level definitions and
 *  bare expressions are both allowed. //> using directives are emitted first
 *  so scala-cli can resolve the right Scala version and dependencies.
 */
object JvmGen:

  def generate(module: Module): String =
    val sb = StringBuilder()

    // //> using directives from YAML front-matter
    module.manifest.foreach { m =>
      m.dependencies.foreach { (dep, version) =>
        sb.append(s"""//> using dep "$dep:$version"\n""")
      }
    }

    // Built-in helpers: md interpolator, doc/render
    sb.append(preamble)

    // Collect all scala/ssc code blocks across all sections (depth-first)
    val blocks = collectBlocks(module.sections)

    if blocks.nonEmpty then
      blocks.foreach { src =>
        sb.append(src.stripTrailing())
        sb.append("\n\n")
      }

    // If front-matter declares a main entry point, call it
    val mainEntry = module.manifest
      .flatMap(_.raw.get("main"))
      .collect { case s: String => s }
    mainEntry.foreach { name =>
      sb.append(s"$name()\n")
    }

    sb.toString.stripTrailing() + "\n"

  private val preamble: String =
    """|
       |extension (sc: StringContext)
       |  def md(args: Any*): String =
       |    val s = sc.s(args*)
       |    val lines = s.split("\n", -1).toSeq
       |    val body = lines.dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse
       |    if body.isEmpty then ""
       |    else
       |      val indent = body.filter(_.trim.nonEmpty).map(_.takeWhile(_ == ' ').length).min
       |      body.map(_.drop(indent)).mkString("\n")
       |
       |case class _Doc(parts: Seq[Any])
       |def doc(args: Any*): _Doc = _Doc(args.toSeq)
       |def render(args: Any*): Unit =
       |  def toStr(v: Any): String = v match
       |    case d: _Doc => d.parts.map(toStr).mkString("\n")
       |    case other   => other.toString
       |  val text =
       |    if args.length == 1 && args(0).isInstanceOf[_Doc] then toStr(args(0).asInstanceOf[_Doc])
       |    else args.map(toStr).mkString("\n")
       |  println(text)
       |
       |""".stripMargin

  private def collectBlocks(sections: List[Section]): List[String] =
    sections.flatMap { s =>
      val own = s.content.collect {
        case Content.CodeBlock(lang, src, _, _)
            if lang == "scala" || lang == "ssc" => src
      }
      own ++ collectBlocks(s.subsections)
    }
