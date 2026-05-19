package scalascript.interpreter

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** WebSocket server intrinsics for the tree-walking interpreter (Stage 5+/D).
 *
 *  Covers process-wide metrics, the active-connection cap, and the
 *  thread-safe `WsRoom` broadcast helper.  Route registration
 *  (`onWebSocket`, `onWebSocketAuth`) lives in `HttpIntrinsics`. */
val WsIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(

  // metrics() — snapshot of process-wide counters as Map[String, Long].
  QualifiedName("metrics") -> NativeImpl((_, args) =>
    args match
      case Nil =>
        val snap = scalascript.server.Metrics.snapshot()
        Value.MapV(snap.map((k, v) => Value.StringV(k) -> Value.IntV(v)))
      case _ => throw InterpretError("metrics() — no arguments")
  ),

  // setMaxWsConnections(n) — process-wide cap on simultaneously-open WebSocket sessions.
  QualifiedName("setMaxWsConnections") -> NativeImpl((_, args) =>
    args match
      case List(n: Long) =>
        scalascript.server.WsConnection.maxActive.set(
          if n > Int.MaxValue.toLong || n < 0 then Int.MaxValue else n.toInt
        )
        ()
      case _ => throw InterpretError("setMaxWsConnections(n)")
  ),

  // WsRoom() — thread-safe registry with built-in broadcast helper.
  QualifiedName("WsRoom") -> NativeImpl((_, args) =>
    args match
      case Nil =>
        val members = java.util.concurrent.CopyOnWriteArrayList[Value]()
        val add = Value.NativeFnV("WsRoom.add", Computation.pureFn {
          case List(ws) => members.add(ws); Value.UnitV
          case _ => throw InterpretError("room.add(ws)")
        })
        val remove = Value.NativeFnV("WsRoom.remove", Computation.pureFn {
          case List(ws) => members.remove(ws); Value.UnitV
          case _ => throw InterpretError("room.remove(ws)")
        })
        val broadcast = Value.NativeFnV("WsRoom.broadcast", Computation.pureFn {
          case List(Value.StringV(msg)) =>
            val it = members.iterator()
            while it.hasNext do
              it.next() match
                case Value.InstanceV("WebSocket", fields) =>
                  fields.get("send") match
                    case Some(f: Value.NativeFnV) =>
                      try Computation.run(f.f(List(Value.StringV(msg))))
                      catch case _: Throwable => () // dead client; reaped via onClose
                    case _ => ()
                case _ => ()
            Value.UnitV
          case _ => throw InterpretError("room.broadcast(msg)")
        })
        val size = Value.NativeFnV("WsRoom.size", Computation.pureFn {
          case Nil => Value.IntV(members.size.toLong)
          case _   => throw InterpretError("room.size()")
        })
        Value.InstanceV("WsRoom", Map(
          "add"       -> add,
          "remove"    -> remove,
          "broadcast" -> broadcast,
          "size"      -> size
        ))
      case _ => throw InterpretError("WsRoom()")
  ),
)
