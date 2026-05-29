package scalascript.compiler.plugin.ws

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{Value, InterpretError, Computation}
import scalascript.plugin.api.PluginNative

/** WebSocket server intrinsics for the tree-walking interpreter (Stage 5+/D).
 *
 *  Covers process-wide metrics, the active-connection cap, and the
 *  thread-safe `WsRoom` broadcast helper.  Route registration
 *  (`onWebSocket`, `onWebSocketAuth`) lives in `HttpIntrinsics`. */
object WsIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // metrics() — snapshot of process-wide counters as Map[String, Long].
    QualifiedName("metrics") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case Nil =>
          val snap = scalascript.server.Metrics.snapshot()
          Value.MapV(snap.map((k, v) => Value.StringV(k) -> Value.intV(v)))
        case _ => throw InterpretError("metrics() — no arguments")
    },

    // setMaxWsConnections(n) — process-wide cap on simultaneously-open WebSocket sessions.
    QualifiedName("setMaxWsConnections") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(n: Long) =>
          ctx.setMaxWsConnections(if n > Int.MaxValue.toLong || n < 0 then Int.MaxValue else n.toInt)
          ()
        case _ => throw InterpretError("setMaxWsConnections(n)")
    },

    // setHttpServerBackend(name) — pick which HttpServerSpi implementation
    // handles the next serve(port).  Valid names are determined by which
    // backend modules are on the classpath: default is "jdk" (zero deps);
    // "jetty" and "netty" are available if their sbt modules are pulled in.
    // Throws if the name doesn't match any registered impl (loud failure
    // is better than silent fallback when the user explicitly asked).
    QualifiedName("setHttpServerBackend") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(Value.StringV(name)) =>
          scalascript.server.spi.HttpServerBackends.setBackend(name)
          ()
        case _ => throw InterpretError("setHttpServerBackend(name)")
    },

    // WsRoom() — thread-safe registry with built-in broadcast helper.
    QualifiedName("WsRoom") -> PluginNative.evalLegacy { (_, args) =>
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
            case Nil => Value.intV(members.size.toLong)
            case _   => throw InterpretError("room.size()")
          })
          Value.InstanceV("WsRoom", Map(
            "add"       -> add,
            "remove"    -> remove,
            "broadcast" -> broadcast,
            "size"      -> size
          ))
        case _ => throw InterpretError("WsRoom()")
    },
  )
