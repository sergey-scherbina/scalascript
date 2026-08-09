package scalascript.compiler.plugin.ws

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginNative, PluginValue, PluginError}

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
          PluginValue.mapOf(snap.map((k, v) => PluginValue.string(k) -> PluginValue.int(v)).toMap)
        case _ => PluginError.raise("metrics() — no arguments")
    },

    // setMaxWsConnections(n) — process-wide cap on simultaneously-open WebSocket sessions.
    QualifiedName("setMaxWsConnections") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(n: Long) =>
          ctx.setMaxWsConnections(if n > Int.MaxValue.toLong || n < 0 then Int.MaxValue else n.toInt)
          ()
        case _ => PluginError.raise("setMaxWsConnections(n)")
    },

    // setHttpServerBackend(name) — pick which HttpServerSpi implementation
    // handles the next serve(port).  Valid names are determined by which
    // backend modules are on the classpath: default is "jdk" (zero deps);
    // "jetty" and "netty" are available if their sbt modules are pulled in.
    // Throws if the name doesn't match any registered impl (loud failure
    // is better than silent fallback when the user explicitly asked).
    QualifiedName("setHttpServerBackend") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(PluginValue.Str(name)) =>
          scalascript.server.spi.HttpServerBackends.setBackend(name)
          ()
        case _ => PluginError.raise("setHttpServerBackend(name)")
    },

    // WsRoom() — thread-safe registry with built-in broadcast helper.
    QualifiedName("WsRoom") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case Nil =>
          val members = java.util.concurrent.CopyOnWriteArrayList[PluginValue]()
          val add = PluginValue.nativeFn("WsRoom.add", {
            case List(ws) => members.add(ws); PluginValue.unit
            case _ => PluginError.raise("room.add(ws)")
          })
          val remove = PluginValue.nativeFn("WsRoom.remove", {
            case List(ws) => members.remove(ws); PluginValue.unit
            case _ => PluginError.raise("room.remove(ws)")
          })
          val broadcast = PluginValue.nativeFn("WsRoom.broadcast", {
            case List(PluginValue.Str(msg)) =>
              val it = members.iterator()
              while it.hasNext do
                it.next() match
                  case PluginValue.Inst("WebSocket", fields) =>
                    fields.get("send") match
                      case Some(sendFn) =>
                        try sendFn.callFn(List(PluginValue.string(msg)))
                        catch case _: Throwable => () // dead client; reaped via onClose
                      case None => ()
                  case _ => ()
              PluginValue.unit
            case _ => PluginError.raise("room.broadcast(msg)")
          })
          val size = PluginValue.nativeFn("WsRoom.size", {
            case Nil => PluginValue.int(members.size.toLong)
            case _   => PluginError.raise("room.size()")
          })
          PluginValue.instance("WsRoom", Map(
            "add"       -> add,
            "remove"    -> remove,
            "broadcast" -> broadcast,
            "size"      -> size
          ))
        case _ => PluginError.raise("WsRoom()")
    },
  )
