package scalascript.interpreter.debug

import java.util.concurrent.ConcurrentHashMap

final class BreakpointRegistry:
  private val table = ConcurrentHashMap[String, Set[Int]]()
  def setBreakpoints(sourceFile: String, lines: Set[Int]): Unit =
    table.put(sourceFile, lines)
  def contains(sourceFile: String, line: Int): Boolean =
    Option(table.get(sourceFile)).exists(_.contains(line))
  def clear(): Unit = table.clear()
