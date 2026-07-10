package ssc.bytecode

/** A source file advertised by a generated direct-ASM class. `path` is a
 * reproducible display path, never an absolute checkout path. */
final case class JvmSourceFile(name: String, path: String)

/** One-based file id and one-based source line. */
final case class JvmSourceLocation(fileId: Int, line: Int)

/** Source coordinates for the generated entry statements and named defs.
 *
 * The JVM LineNumberTable stores generated output lines. Primary-file lines
 * retain their original number so ordinary stack traces stay useful even on a
 * JVM that does not interpret JSR-45. Secondary files receive deterministic
 * synthetic output lines and are translated by the embedded SMAP. */
final case class JvmSourceDebug(
    files: Vector[JvmSourceFile],
    entryLines: Vector[JvmSourceLocation],
    definitionLines: Map[String, JvmSourceLocation]):

  require(files.nonEmpty, "JVM source debug info requires at least one source")
  files.foreach { file =>
    require(file.name.nonEmpty && !file.name.exists(c => c == '\n' || c == '\r'),
      "JVM source names must be non-empty single lines")
    require(file.path.nonEmpty && !file.path.exists(c => c == '\n' || c == '\r'),
      "JVM source paths must be non-empty single lines")
  }

  private val locations = (entryLines ++ definitionLines.values)
    .distinct
    .sortBy(location => (location.fileId, location.line))
  locations.foreach { location =>
    require(location.fileId >= 1 && location.fileId <= files.length,
      s"invalid JVM source file id ${location.fileId}")
    require(location.line >= 1 && location.line <= 65535,
      s"invalid JVM source line ${location.line}")
  }

  private val primaryLines = locations.collect {
    case location if location.fileId == 1 => location.line
  }.toSet
  private val secondary = locations.filter(_.fileId != 1)
  private val secondaryOutputs =
    var next = primaryLines.maxOption.getOrElse(0) + 1
    secondary.map { location =>
      while primaryLines.contains(next) do next += 1
      require(next <= 65535, "too many JVM source-map output lines")
      val result = location -> next
      next += 1
      result
    }.toMap

  def sourceFile: String = files.head.name

  def outputLine(location: JvmSourceLocation): Int =
    if location.fileId == 1 then location.line
    else secondaryOutputs.getOrElse(location, location.line)

  /** JSR-45 SourceDebugExtension with every explicit root in the file table. */
  def smap: String =
    val out = new StringBuilder
    out.append("SMAP\n")
    out.append(sourceFile).append('\n')
    out.append("SSC\n")
    out.append("*S SSC\n")
    out.append("*F\n")
    files.zipWithIndex.foreach { case (file, index) =>
      out.append("+ ").append(index + 1).append(' ').append(file.name).append('\n')
      out.append(file.path).append('\n')
    }
    out.append("*L\n")
    locations.foreach { location =>
      out.append(location.line).append('#').append(location.fileId)
        .append(':').append(outputLine(location)).append('\n')
    }
    out.append("*E\n")
    out.toString
