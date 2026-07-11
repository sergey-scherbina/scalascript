package ssc.plugin.storage

import java.nio.file.{Files, Path}
import scala.collection.mutable
import ssc.{Show, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free dynamically scoped Storage effect for the standard native runtime. */
final class StorageNativePlugin extends NativePlugin:
  def id: String = "58-storage-effect"

  private def list(values: IterableOnce[Value]): Value =
    values.iterator.toList.foldRight[Value](Value.DataV("Nil", Vector.empty)) { (value, rest) =>
      Value.DataV("Cons", Vector(value, rest))
    }

  private def pathText(value: Value): String = value match
    case Value.StrV(path) => path
    case _ => throw new IllegalArgumentException("runStorage body[, path]")

  private def defaultPath: String =
    Option(System.getenv("SSC_STORAGE_PATH")).filter(_.nonEmpty).getOrElse("./ssc-storage.json")

  def install(context: NativePluginContext): Unit =
    context.registerValue("Storage", Value.DataV("Storage", Vector.empty))

    def run(args: List[Value], persistent: Boolean): Value = args match
      case body :: rest if rest.size <= 1 =>
        val path = if persistent then Some(rest.headOption.map(pathText).getOrElse(defaultPath)) else None
        val state = mutable.LinkedHashMap.empty[String, String]
        path.foreach(load(_, state))
        def flush(): Unit = path.foreach(save(_, state))
        val handler: (String, List[Value]) => Value =
          case ("get", List(Value.StrV(key))) => state.get(key) match
            case Some(value) => Value.DataV("Some", Vector(Value.StrV(value)))
            case None => Value.DataV("None", Vector.empty)
          case ("put", List(Value.StrV(key), value)) =>
            state(key) = value match
              case Value.StrV(text) => text
              case other => Show.show(other)
            flush()
            Value.UnitV
          case ("remove", List(Value.StrV(key))) =>
            state.remove(key)
            flush()
            Value.UnitV
          case ("has", List(Value.StrV(key))) => Value.BoolV(state.contains(key))
          case ("keys", Nil) => list(state.keysIterator.map(Value.StrV.apply))
          case (operation, _) =>
            throw new IllegalArgumentException(s"invalid Storage.$operation arguments")
        context.withEffect("Storage")(handler) {
          context.invoke(body, Nil)
        }
      case _ =>
        if persistent then throw new IllegalArgumentException("runStorage body[, path]")
        else throw new IllegalArgumentException("runEphemeralStorage body")

    context.register("runEphemeralStorage")(args => run(args, persistent = false))
    context.registerGlobal("runEphemeralStorage", 1)(args => run(args, persistent = false))
    context.register("runStorage")(args => run(args, persistent = true))
    context.registerGlobal("runStorage", -1)(args => run(args, persistent = true))

  private def load(path: String, state: mutable.LinkedHashMap[String, String]): Unit =
    val file = Path.of(path)
    if Files.exists(file) then parse(Files.readString(file)).foreach(state.addOne)

  private def save(path: String, state: mutable.LinkedHashMap[String, String]): Unit =
    val file = Path.of(path)
    Option(file.getParent).foreach(Files.createDirectories(_))
    Files.writeString(file, render(state.toList))

  private def parse(source: String): List[(String, String)] =
    val text = source.trim
    if !text.startsWith("{") || !text.endsWith("}") then
      throw new RuntimeException("Storage JSON: expected object")
    val result = mutable.ListBuffer.empty[(String, String)]
    var index = 1
    val end = text.length - 1

    def skipWhitespace(): Unit =
      while index < end && text.charAt(index).isWhitespace do index += 1

    def readString(): String =
      if index >= end || text.charAt(index) != '"' then
        throw new RuntimeException(s"Storage JSON: expected string at index $index")
      index += 1
      val value = StringBuilder()
      var closed = false
      while index < end && !closed do
        text.charAt(index) match
          case '"' => closed = true; index += 1
          case '\\' if index + 1 < end =>
            text.charAt(index + 1) match
              case '"' => value.append('"')
              case '\\' => value.append('\\')
              case 'n' => value.append('\n')
              case 'r' => value.append('\r')
              case 't' => value.append('\t')
              case other => value.append(other)
            index += 2
          case character => value.append(character); index += 1
      if !closed then throw new RuntimeException("Storage JSON: unterminated string")
      value.toString

    skipWhitespace()
    while index < end do
      val key = readString()
      skipWhitespace()
      if index >= end || text.charAt(index) != ':' then
        throw new RuntimeException("Storage JSON: expected ':'")
      index += 1
      skipWhitespace()
      val value = readString()
      result += key -> value
      skipWhitespace()
      if index < end then
        if text.charAt(index) != ',' then
          throw new RuntimeException("Storage JSON: expected ','")
        index += 1
        skipWhitespace()
    result.toList

  private def render(entries: List[(String, String)]): String =
    def escape(value: String): String =
      val result = StringBuilder("\"")
      value.foreach {
        case '"' => result.append("\\\"")
        case '\\' => result.append("\\\\")
        case '\n' => result.append("\\n")
        case '\r' => result.append("\\r")
        case '\t' => result.append("\\t")
        case character => result.append(character)
      }
      result.append('"').toString
    entries.map { case (key, value) => s"${escape(key)}:${escape(value)}" }.mkString("{", ",", "}")
