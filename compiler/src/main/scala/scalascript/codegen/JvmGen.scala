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

    // Collect all scala/ssc code blocks across all sections (depth-first)
    val blocks = collectBlocks(module.sections)

    if blocks.nonEmpty then
      sb.append("\n")
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

  private def collectBlocks(sections: List[Section]): List[String] =
    sections.flatMap { s =>
      val own = s.content.collect {
        case Content.CodeBlock(lang, src, _, _)
            if lang == "scala" || lang == "ssc" => src
      }
      own ++ collectBlocks(s.subsections)
    }
