package scalascript.server

import scalascript.interpreter.{Interpreter, Value, Computation, InterpretError}

/** Outbound WebSocket client session bridge for the interpreter.
 *
 *  After the outbound-side runtime-consolidation refactor the actual
 *  per-connection state, JDK `WebSocket.Listener`, recv-queue, close
 *  latch and frame dispatch all live in the shared class
 *  `_root_.scalascript.server.jvm.WsClient` — the same way Phase 3b/3c
 *  collapsed the inbound-side duplicate into the shared
 *  `scalascript.server.jvm.WebSocket` class behind
 *  [[scalascript.server.WsConnection]].
 *
 *  This file is now a thin wrapper that:
 *   - Constructs a `WsClient` with the interpreter's log stream.
 *   - Wraps `Value.Closure` callbacks into the `String => Unit` /
 *     `() => Unit` shapes the shared class expects, dispatching them
 *     through `Interpreter.invoke` so user-visible callbacks run on
 *     the actor scheduler.
 *   - Builds the `Value.InstanceV("WebSocket", …)` user code touches
 *     — byte-identical to the pre-refactor map shape (same keys, same
 *     NativeFn names, same StringV/OptionV wrapping).
 *
 *  Keeps the constructor and public API the same so the two call sites
 *  in `Interpreter.scala` (cluster peer connect + `wsConnectSync`) don't
 *  need to change. */
final class WsClientSession(
    url:         String,
    extraHdrs:   Map[String, String],
    protocols:   List[String],
    interpreter: Interpreter,
    log:         java.io.PrintStream):
  private val client = new _root_.scalascript.server.jvm.WsClient(url, extraHdrs, protocols, log)

  def id: String          = client.id
  def subprotocol: String = client.subprotocol
  def connect(): Unit     = client.connect()
  def awaitClose(): Unit  = client.awaitClose()

  /** Scala-side send — bypasses the Value/Computation machinery for use
   *  on virtual threads without involving the interpreter's actor
   *  scheduler. */
  def sendText(text: String): Unit = client.send(text)

  /** Send raw bytes as a binary WebSocket frame. */
  def sendBinary(bytes: Array[Byte]): Unit = client.sendBinary(bytes)

  /** Blocking receive — returns None on close.  Safe to call on virtual
   *  threads; blocks the carrier only while the OS is waiting for data.
   *  Binary frames arrive as ISO-8859-1-encoded strings. */
  def recvText(): Option[String] = client.recv()

  /** Hard-close the underlying WebSocket (e.g. heartbeat timeout). */
  def abort(): Unit = client.abort()

  /** Build the user-facing `Value.InstanceV("WebSocket", …)` wrapper
   *  around the shared `WsClient`.  Same Map keys, NativeFn names and
   *  OptionV / StringV shapes the interpreter tests already
   *  pattern-match on. */
  def wsObj: Value =
    import Value.*; import Computation.*
    val sendFn = NativeFnV("WebSocket.send", pureFn {
      case List(StringV(s)) => client.send(s); UnitV
      case _ => throw InterpretError("ws.send(text)")
    })
    val sendBytesFn = NativeFnV("WebSocket.sendBytes", pureFn {
      case List(StringV(s)) => client.sendBytes(s); UnitV
      case _ => throw InterpretError("ws.sendBytes(bytes)")
    })
    val closeFn = NativeFnV("WebSocket.close", pureFn {
      case Nil =>
        client.close(1000, ""); UnitV
      case List(IntV(code)) =>
        client.close(code.toInt, ""); UnitV
      case List(IntV(code), StringV(r)) =>
        client.close(code.toInt, r); UnitV
      case _ => throw InterpretError("ws.close() or ws.close(code) or ws.close(code, reason)")
    })
    val onMessageFn = NativeFnV("WebSocket.onMessage", pureFn {
      case List(cb) =>
        client.onMessage { (msg: String) =>
          try interpreter.invoke(cb, List(StringV(msg)))
          catch case e: Throwable =>
            log.println(s"wsConnect callback error: ${e.getMessage}")
        }
        UnitV
      case _ => throw InterpretError("ws.onMessage { msg => … }")
    })
    val onCloseFn = NativeFnV("WebSocket.onClose", pureFn {
      case List(cb) =>
        client.onClose { () =>
          try interpreter.invoke(cb, Nil)
          catch case e: Throwable =>
            log.println(s"wsConnect callback error: ${e.getMessage}")
        }
        UnitV
      case _ => throw InterpretError("ws.onClose { () => … }")
    })
    val pingFn = NativeFnV("WebSocket.ping", pureFn {
      case Nil                    => client.ping(); UnitV
      case List(StringV(s))       => client.ping(s); UnitV
      case _ => throw InterpretError("ws.ping() or ws.ping(payload)")
    })
    val onPongFn = NativeFnV("WebSocket.onPong", pureFn {
      case List(cb) =>
        client.onPong { (payload: String) =>
          try interpreter.invoke(cb, List(StringV(payload)))
          catch case e: Throwable =>
            log.println(s"wsConnect callback error: ${e.getMessage}")
        }
        UnitV
      case _ => throw InterpretError("ws.onPong { payload => … }")
    })
    val recvFn = NativeFnV("WebSocket.recv", pureFn {
      case Nil =>
        client.recv() match
          case Some(msg) => OptionV(Some(StringV(msg)))
          case None      => Value.NoneV
      case _ => throw InterpretError("ws.recv()")
    })
    val isClosedFn = NativeFnV("WebSocket.isClosed", pureFn {
      case Nil => BoolV(client.isClosed)
      case _   => throw InterpretError("ws.isClosed")
    })
    InstanceV("WebSocket", Map(
      "id"          -> StringV(id),
      "send"        -> sendFn,
      "sendBytes"   -> sendBytesFn,
      "close"       -> closeFn,
      "onMessage"   -> onMessageFn,
      "onClose"     -> onCloseFn,
      "ping"        -> pingFn,
      "onPong"      -> onPongFn,
      "recv"        -> recvFn,
      "isClosed"    -> isClosedFn,
      "subprotocol" -> StringV(subprotocol)
    ))
