package scalascript.server

import java.util.concurrent.atomic.AtomicInteger
import scalascript.interpreter.{Interpreter, Value, Computation, InterpretError}

/** Bridge between the interpreter's [[WsProxy]] / [[TlsProxy]] and the
 *  shared `_root_.scalascript.server.jvm.WebSocket` class that both the
 *  interpreter and the JVM codegen runtime use for the per-connection
 *  state, write loop, heartbeat, and frame dispatch.
 *
 *  After Phase 3b of the runtime-consolidation refactor (Option B) the
 *  interpreter no longer carries its own duplicate of the WebSocket
 *  instance class — only this object (process-wide slot manager +
 *  Value-wrapping helper) lives in `backend-interpreter`.  The actual
 *  read-loop, writer-VT, heartbeat scheduler, and onMessage / onPong /
 *  onClose dispatch all run inside the shared class.
 *
 *  Static slot manager — delegates to `_root_.scalascript.server.jvm`'s
 *  process-wide `_wsActiveCount` / `_wsMaxActive` / `_wsTryReserve`
 *  so the interpreter and the codegen output share one cap. */
object WsConnection:
  /** Aliases for the codegen-side AtomicIntegers so the test suite's
   *  existing `WsConnection.activeCount.set(0)` /
   *  `WsConnection.maxActive.set(Int.MaxValue)` reset incantation
   *  keeps working against the shared counters. */
  val activeCount: AtomicInteger = _root_.scalascript.server.jvm._wsActiveCount
  val maxActive:   AtomicInteger = _root_.scalascript.server.jvm._wsMaxActive

  /** Atomic check-then-increment.  Returns true if the caller may
   *  proceed (counter was below the cap), false if the cap is reached.
   *  Failed reservations roll the counter back to its prior value so
   *  the cap can't drift. */
  def tryReserveSlot(): Boolean = _root_.scalascript.server.jvm._wsTryReserve()

  def releaseSlot(): Unit = _root_.scalascript.server.jvm._wsReleaseSlot()

  /** Build the user-facing `Value.InstanceV("WebSocket", …)` wrapper
   *  around a live `_root_.scalascript.server.jvm.WebSocket`.  Adapts the
   *  raw `String => Unit` / `() => Unit` callbacks the shared class
   *  expects into the interpreter's `Value.Closure`-friendly shape
   *  (the callback receives a `Value.StringV(msg)` and `interp.invoke`
   *  drives it).
   *
   *  Indistinguishable from the original `WsConnection.asValue`: same
   *  Map keys, same NativeFn signatures, same OptionV / StringV shapes
   *  the interpreter tests already pattern-match on. */
  def asValue(
      ws:      _root_.scalascript.server.jvm.WebSocket,
      interp:  Interpreter,
      log:     java.io.PrintStream,
      request: Value
  ): Value =
    val send = Value.NativeFnV("WebSocket.send", Computation.pureFn {
      case List(Value.StringV(s)) => ws.send(s); Value.UnitV
      case _ => throw InterpretError("ws.send(text)")
    })
    val sendBytes = Value.NativeFnV("WebSocket.sendBytes", Computation.pureFn {
      case List(Value.StringV(s)) => ws.sendBytes(s); Value.UnitV
      case _ => throw InterpretError("ws.sendBytes(bytes)")
    })
    val closeFn = Value.NativeFnV("WebSocket.close", Computation.pureFn {
      case Nil =>
        ws.close(1000, ""); Value.UnitV
      case List(Value.IntV(code)) =>
        ws.close(code.toInt, ""); Value.UnitV
      case List(Value.IntV(code), Value.StringV(reason)) =>
        ws.close(code.toInt, reason); Value.UnitV
      case _ => throw InterpretError("ws.close() or ws.close(code) or ws.close(code, reason)")
    })
    val onMessage = Value.NativeFnV("WebSocket.onMessage", Computation.pureFn {
      case List(cb) =>
        ws.onMessage { (msg: String) =>
          try interp.invoke(cb, List(Value.StringV(msg)))
          catch case e: Throwable =>
            log.println(s"WS handler error: ${e.getMessage}")
        }
        Value.UnitV
      case _ => throw InterpretError("ws.onMessage { msg => … }")
    })
    val onClose = Value.NativeFnV("WebSocket.onClose", Computation.pureFn {
      case List(cb) =>
        ws.onClose { () =>
          try interp.invoke(cb, Nil)
          catch case e: Throwable =>
            log.println(s"WS close handler error: ${e.getMessage}")
        }
        Value.UnitV
      case _ => throw InterpretError("ws.onClose { () => … }")
    })
    val ping = Value.NativeFnV("WebSocket.ping", Computation.pureFn {
      case Nil =>
        ws.ping(); Value.UnitV
      case List(Value.StringV(s)) =>
        ws.ping(s); Value.UnitV
      case _ => throw InterpretError("ws.ping() or ws.ping(payload)")
    })
    val onPong = Value.NativeFnV("WebSocket.onPong", Computation.pureFn {
      case List(cb) =>
        ws.onPong { (payload: String) =>
          try interp.invoke(cb, List(Value.StringV(payload)))
          catch case e: Throwable =>
            log.println(s"WS onPong handler error: ${e.getMessage}")
        }
        Value.UnitV
      case _ => throw InterpretError("ws.onPong { payload => … }")
    })
    val recv = Value.NativeFnV("WebSocket.recv", Computation.pureFn {
      case Nil =>
        ws.recv() match
          case Some(msg) => Value.OptionV(Some(Value.StringV(msg)))
          case None      => Value.OptionV(None)
      case _ => throw InterpretError("ws.recv()")
    })
    val isClosed = Value.NativeFnV("WebSocket.isClosed", Computation.pureFn {
      case Nil => Value.boolV(ws.isClosed)
      case _   => throw InterpretError("ws.isClosed")
    })
    val userValue: Value = ws.user match
      case Some(v: Value) => Value.OptionV(Some(v))
      case Some(other)    => Value.OptionV(Some(Value.StringV(other.toString)))
      case None           => Value.OptionV(None)
    Value.InstanceV("WebSocket", Map(
      "send"        -> send,
      "sendBytes"   -> sendBytes,
      "close"       -> closeFn,
      "ping"        -> ping,
      "onMessage"   -> onMessage,
      "onClose"     -> onClose,
      "onPong"      -> onPong,
      "recv"        -> recv,
      "isClosed"    -> isClosed,
      "request"     -> request,
      "id"          -> Value.StringV(ws.id),
      "subprotocol" -> Value.StringV(ws.subprotocol),
      "user"        -> userValue
    ))
