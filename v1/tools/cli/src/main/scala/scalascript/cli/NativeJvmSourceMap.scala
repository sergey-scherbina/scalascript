package scalascript.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.mutable
import _root_.ssc.{Program, Term}
import _root_.ssc.bytecode.{JvmSourceDebug, JvmSourceFile, JvmSourceLocation}

/** Scalameta-free source-coordinate recovery for the native artifact lane.
 *
 * The self-hosted frontend currently serializes canonical CoreIR, whose wire
 * format intentionally has no positions. This scanner follows the same fenced
 * source order and records top-level declaration/entry starts before the IR is
 * handed to ASM. It is deliberately lexical: no v1 parser or compiler class is
 * loaded merely to produce debug metadata. */
private[cli] object NativeJvmSourceMap:
  private final case class Statement(
      location: JvmSourceLocation,
      definition: Option[String],
      entersEntry: Boolean)

  private val Definition =
    """^(?:(?:private|protected|override|inline|transparent|final|lazy)\s+)*def\s+([A-Za-z_$][A-Za-z0-9_$]*)\b.*""".r
  private val ValueDefinition =
    """^(?:(?:private|protected|override|inline|transparent|final|lazy)\s+)*(?:val|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)\b.*""".r

  def build(program: Program, sourceUnits: List[NativeSourceUnit]): JvmSourceDebug =
    require(sourceUnits.nonEmpty, "native JVM source mapping requires at least one root")
    val orderedUnits = sourceUnits.filter(_.explicitRoot) ++ sourceUnits.filterNot(_.explicitRoot)
    val files = orderedUnits.map { unit =>
      JvmSourceFile(unit.file.getName, unit.displayPath)
    }.toVector
    val fileIds = orderedUnits.zipWithIndex.map { case (unit, index) =>
      unit.file.getCanonicalPath -> (index + 1)
    }.toMap
    val statements = sourceUnits.flatMap { unit =>
      scan(unit.file, fileIds(unit.file.getCanonicalPath), unit.explicitRoot)
    }
    val definitions = mutable.LinkedHashMap.empty[String, JvmSourceLocation]
    statements.foreach(statement => statement.definition.foreach { name =>
      definitions(name) = statement.location // frontend/top-level defs are last-wins
    })

    val candidateEntryLines = statements.collect {
      case statement if statement.entersEntry => statement.location
    }.toVector
    val entryCount = program.entry match
      case Term.Seq(terms) => terms.length
      case _               => 1
    val fallback = candidateEntryLines.headOption
      .orElse(statements.headOption.map(_.location))
      .getOrElse(JvmSourceLocation(1, 1))
    val entryLines =
      if candidateEntryLines.length == entryCount then candidateEntryLines
      else if candidateEntryLines.length > entryCount then candidateEntryLines.takeRight(entryCount)
      else Vector.fill(entryCount - candidateEntryLines.length)(fallback) ++ candidateEntryLines

    JvmSourceDebug(files, entryLines, definitions.toMap)

  private def scan(file: java.io.File, fileId: Int, explicitRoot: Boolean): List[Statement] =
    val lines = Files.readAllLines(file.toPath, StandardCharsets.UTF_8)
    val result = mutable.ListBuffer.empty[Statement]
    var index = 0
    while index < lines.size do
      val trimmed = lines.get(index).trim
      if trimmed.startsWith("```") then
        val language = trimmed.dropWhile(_ == '`').trim.takeWhile(!_.isWhitespace).toLowerCase
        val accepted = language == "scalascript" || language == "scala"
        val block = mutable.ArrayBuffer.empty[(Int, String)]
        index += 1
        while index < lines.size && !lines.get(index).trim.startsWith("```") do
          if accepted then block += ((index + 1, lines.get(index)))
          index += 1
        if accepted then result ++= scanBlock(block.toVector, fileId, explicitRoot)
      index += 1
    result.toList

  private def scanBlock(
      lines: Vector[(Int, String)],
      fileId: Int,
      explicitRoot: Boolean): List[Statement] =
    val nonEmpty = lines.filterNot(_._2.trim.isEmpty)
    val baseIndent = nonEmpty.map(row => indent(row._2)).minOption.getOrElse(0)
    val result = mutable.ListBuffer.empty[Statement]
    var braceDepth = 0
    lines.foreach { case (lineNumber, raw) =>
      val text = raw.drop(math.min(baseIndent, raw.length))
      val trimmed = text.trim
      val topLevel = indent(text) == 0 && braceDepth == 0
      if topLevel && isStatementStart(trimmed) then
        val valueDefinition = trimmed match
          case ValueDefinition(_) => true
          case _                  => false
        val definition = trimmed match
          case Definition(name)      => Some(name)
          case ValueDefinition(name) => Some(name)
          case _                     => None
        result += Statement(
          JvmSourceLocation(fileId, lineNumber),
          definition,
          entersEntry = entersEntry(trimmed) && (explicitRoot || valueDefinition))
      braceDepth = math.max(0, braceDepth + braceDelta(text))
    }
    result.toList

  private def isStatementStart(line: String): Boolean =
    line.nonEmpty &&
      !line.startsWith("//") &&
      !line.startsWith("--") &&
      !line.startsWith("}") &&
      !line.startsWith(")") &&
      !line.startsWith("]")

  private def entersEntry(line: String): Boolean =
    val normalized = line.replaceAll("^(?:private|protected|override|inline|transparent|final|lazy)\\s+", "")
    !(normalized.startsWith("def ") ||
      normalized.startsWith("extern def ") ||
      normalized.startsWith("case class ") ||
      normalized.startsWith("class ") ||
      normalized.startsWith("trait ") ||
      normalized.startsWith("enum ") ||
      normalized.startsWith("type ") ||
      normalized.startsWith("object ") ||
      normalized.startsWith("given ") ||
      normalized.startsWith("import ") ||
      normalized.startsWith("export ") ||
      normalized.startsWith("package ") ||
      normalized.startsWith("@"))

  private def indent(line: String): Int =
    line.iterator.takeWhile(ch => ch == ' ' || ch == '\t').length

  /** Braces outside strings/comments; sufficient for deciding whether a
   * physical line starts a top-level statement. */
  private def braceDelta(line: String): Int =
    var result = 0
    var index = 0
    var quote: Char = 0.toChar
    var escaped = false
    while index < line.length do
      val ch = line.charAt(index)
      if quote != 0 then
        if escaped then escaped = false
        else if ch == '\\' then escaped = true
        else if ch == quote then quote = 0.toChar
      else if ch == '/' && index + 1 < line.length && line.charAt(index + 1) == '/' then
        index = line.length
      else if ch == '"' || ch == '\'' then quote = ch
      else if ch == '{' then result += 1
      else if ch == '}' then result -= 1
      index += 1
    result
