package scalascript.server

import com.sun.net.httpserver.HttpExchange

/** Drive the wire side of a streaming HTTP response — set headers
 *  (defaulting Content-Type), apply CORS, send chunked transfer
 *  encoding (`Content-Length` -1 / 0 = unknown length), then hand a
 *  `chunk: String => Unit` writer callback to user code via the
 *  `runWriter` lambda.  Each chunk gets flushed immediately so SSE
 *  events / progress messages reach the client without buffering.
 *
 *  Backend differences live entirely inside `runWriter`:
 *    - codegen builds a `(write: String => Unit) => Unit` from
 *      `StreamResponse.writer` directly;
 *    - interpreter wraps `Interpreter.invoke(callback, List(writeNative))`
 *      where `writeNative` is a `Value.NativeFnV` that forwards into
 *      the supplied `write` closure. */
object StreamResponseWriter:

  def write(
      ex:        HttpExchange,
      status:    Int,
      headers:   Map[String, String],
      cors:      ResponseWriter.Config,
      runWriter: (String => Unit) => Unit
  ): Unit =
    headers.foreach((k, v) => ex.getResponseHeaders.add(k, v))
    if !headers.contains("Content-Type") then
      ex.getResponseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    CorsHelpers(ex, cors.corsOrigins, cors.corsMethods, cors.corsHeaders)
    ex.sendResponseHeaders(status, 0)
    val out = ex.getResponseBody
    try
      runWriter { chunk =>
        out.write(chunk.getBytes("UTF-8"))
        out.flush()
      }
    finally out.close()
