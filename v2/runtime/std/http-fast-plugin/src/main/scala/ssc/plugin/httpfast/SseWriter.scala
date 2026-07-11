package ssc.plugin.httpfast

import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8

/** Backs the streaming `HttpStream` value handed to `sse`/`streamResponse` handlers. Writes
  * are serialized + flushed immediately; a broken pipe flips the stream closed so the handler
  * loop can stop. The engine closes the socket when the handler body returns. */
private[httpfast] final class SseWriter(out: OutputStream):
  private val lock = new Object
  @volatile private var closedFlag = false

  def isClosed: Boolean = closedFlag
  def close(): Unit     = closedFlag = true

  /** SSE `data:` event (multi-line data is split into multiple `data:` lines per the spec). */
  def event(data: String): Unit =
    frame(s"data: ${data.replace("\n", "\ndata: ")}\n\n")

  /** Named SSE event. */
  def event(name: String, data: String): Unit =
    frame(s"event: $name\ndata: ${data.replace("\n", "\ndata: ")}\n\n")

  /** SSE comment / heartbeat (`: …`). */
  def comment(text: String): Unit = frame(s": $text\n\n")

  /** Raw write (for `streamResponse` — no SSE framing). */
  def write(raw: String): Unit = frame(raw)

  private def frame(s: String): Unit = lock.synchronized {
    if !closedFlag then
      try { out.write(s.getBytes(UTF_8)); out.flush() }
      catch case _: java.io.IOException => closedFlag = true
  }
