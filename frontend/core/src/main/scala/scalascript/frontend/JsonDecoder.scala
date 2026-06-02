package scalascript.frontend

/** Backend-neutral JSON decoder SPI for JVM frontends (Swing, JavaFX).
 *
 *  Web backends (React, Vue, Solid) decode JSON at the JS runtime via `response.json()`.
 *  SwiftUI uses `JSONDecoder().decode(T.self, ...)`.
 *  JVM backends (Swing, JavaFX) need a pluggable JVM decoder — the default implementation
 *  ships as `frontend-core-json-upickle` (uPickle); users inject their own via `RuntimeState`.
 *
 *  Usage:
 *  {{{
 *    val decoder: JsonDecoder = JsonDecoder.default  // uPickle default
 *    val balance: Any = decoder.decode(bytes, "BalanceSheet")
 *    // result is an untyped AnyRef; Swing/JavaFX emitters generate typed accessors
 *  }}}
 */
trait JsonDecoder:
  /** Decode `bytes` into an untyped object representing the named model type.
   *  Returns a `Map[String, Any]`-shaped object tree that emitters can pattern-match
   *  against for Swing/JavaFX observer binding.
   *
   *  Throws `JsonDecodeError` on malformed input or unknown type. */
  def decode(bytes: Array[Byte], typeName: String): Any

  /** Convenience overload accepting a UTF-8 JSON string. */
  def decodeString(json: String, typeName: String): Any =
    decode(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), typeName)

object JsonDecoder:
  /** Sentinel NoOp decoder — returns an empty `Map[String, Any]` for every call.
   *  Used as a compile-time placeholder until a real decoder is registered. */
  val noOp: JsonDecoder = (_, _) => Map.empty[String, Any]

  /** Thread-local registry for the active decoder in JVM contexts.
   *  Set by `RuntimeState` before the first fetch; read by JVM emitter runtimes. */
  private val _current = new ThreadLocal[JsonDecoder]:
    override def initialValue(): JsonDecoder = noOp

  def current: JsonDecoder = _current.get()

  def setCurrent(d: JsonDecoder): Unit = _current.set(d)

  def withDecoder[A](d: JsonDecoder)(thunk: => A): A =
    val prev = _current.get()
    try { _current.set(d); thunk }
    finally { _current.set(prev) }

case class JsonDecodeError(message: String) extends RuntimeException(message)
