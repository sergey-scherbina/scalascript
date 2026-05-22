package scalascript.interpreter

import Computation.{Pure, Perform, FlatMap}

/** Storage effect handler — Free-Monad walker for Storage.get/put/remove/has/keys.
 *  All methods are standalone (no Interpreter state accessed).
 */
private[interpreter] object StorageRuntime:

  def defaultPath: String =
    Option(java.lang.System.getenv("SSC_STORAGE_PATH"))
      .filter(_.nonEmpty)
      .getOrElse("./ssc-storage.json")

  def interp(
    initial: Computation,
    path:    Option[String]
  ): Computation =
    val state = scala.collection.mutable.LinkedHashMap.empty[String, String]
    path.foreach { p => load(p, state) }
    run(initial, state, path)

  def run(
    initial: Computation,
    state:   scala.collection.mutable.LinkedHashMap[String, String],
    path:    Option[String]
  ): Computation =
    def flush(): Unit = path.foreach { p => save(p, state) }
    def dispatch(op: String, args: List[Value], resume: Value => Computation): Computation =
      op match
        case "get" => args match
          case List(Value.StringV(k)) =>
            resume(state.get(k).map(v => Value.OptionV(Some(Value.StringV(v))))
                                .getOrElse(Value.OptionV(None)))
          case _ => throw InterpretError("Storage.get(key: String)")
        case "put" => args match
          case List(Value.StringV(k), v) =>
            state(k) = Value.show(v); flush(); resume(Value.UnitV)
          case _ => throw InterpretError("Storage.put(key: String, value)")
        case "remove" => args match
          case List(Value.StringV(k)) =>
            state.remove(k); flush(); resume(Value.UnitV)
          case _ => throw InterpretError("Storage.remove(key: String)")
        case "has" => args match
          case List(Value.StringV(k)) => resume(Value.BoolV(state.contains(k)))
          case _ => throw InterpretError("Storage.has(key: String)")
        case "keys" => args match
          case Nil => resume(Value.ListV(state.keys.toList.map(Value.StringV.apply)))
          case _   => throw InterpretError("Storage.keys()")
        case _ => throw InterpretError(s"Unknown Storage operation: $op")
    var current: Computation = initial
    while true do
      current match
        case Pure(_) => return current
        case Perform("Storage", op, args) =>
          current = dispatch(op, args, v => Pure(v))
        case Perform(_, _, _) => return current
        case FlatMap(sub, f) => sub match
          case Pure(v)          => current = f(v)
          case FlatMap(s2, g)   => current = FlatMap(s2, x => FlatMap(g(x), f))
          case Perform("Storage", op, args) =>
            current = dispatch(op, args, v => run(f(v), state, path))
          case Perform(_, _, _) =>
            return FlatMap(sub, v => run(f(v), state, path))
    throw InterpretError("unreachable")

  private def load(
    path:  String,
    state: scala.collection.mutable.LinkedHashMap[String, String]
  ): Unit =
    val p = java.nio.file.Paths.get(path)
    if java.nio.file.Files.exists(p) then
      val src = java.nio.file.Files.readString(p)
      parseJson(src).foreach { case (k, v) => state(k) = v }

  private def save(
    path:  String,
    state: scala.collection.mutable.LinkedHashMap[String, String]
  ): Unit =
    val json = renderJson(state.toList)
    java.nio.file.Files.writeString(java.nio.file.Paths.get(path), json)

  private def parseJson(src: String): List[(String, String)] =
    val s   = src.trim
    val buf = scala.collection.mutable.ListBuffer.empty[(String, String)]
    if !s.startsWith("{") || !s.endsWith("}") then return Nil
    var i = 1
    val end = s.length - 1
    def skipWs(): Unit = while i < end && s.charAt(i).isWhitespace do i += 1
    def readStr(): String =
      if i >= end || s.charAt(i) != '"' then
        throw InterpretError(s"Storage JSON: expected string at index $i")
      i += 1
      val sb = StringBuilder()
      while i < end && s.charAt(i) != '"' do
        if s.charAt(i) == '\\' && i + 1 < end then
          s.charAt(i + 1) match
            case '"'  => sb.append('"');  i += 2
            case '\\' => sb.append('\\'); i += 2
            case 'n'  => sb.append('\n'); i += 2
            case 't'  => sb.append('\t'); i += 2
            case 'r'  => sb.append('\r'); i += 2
            case c    => sb.append(c);    i += 2
        else
          sb.append(s.charAt(i)); i += 1
      i += 1
      sb.toString
    skipWs()
    while i < end do
      val k = readStr(); skipWs()
      if i >= end || s.charAt(i) != ':' then
        throw InterpretError("Storage JSON: expected ':'")
      i += 1; skipWs()
      val v = readStr(); skipWs()
      buf += (k -> v)
      if i < end && s.charAt(i) == ',' then i += 1
      skipWs()
    buf.toList

  private def renderJson(entries: List[(String, String)]): String =
    def esc(s: String): String =
      val sb = StringBuilder()
      sb.append('"')
      s.foreach {
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case c    => sb.append(c)
      }
      sb.append('"').toString
    entries.map { case (k, v) => s"${esc(k)}:${esc(v)}" }.mkString("{", ",", "}")
