package scalascript.wire.security

import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import scalascript.wire.{WireDecodeError, WireEnvelope}

/** Session identity and sequence-number tracking for replay prevention.
 *
 *  Each logical connection creates a `WireSession` and:
 *  - Stamps outbound frames with `session-id` and `seq` headers.
 *  - Checks inbound frames with `WireReplayWindow` to reject replays.
 *
 *  Spec: docs/specs/distributed-wire-protocol.md §Replay and Ordering */
class WireSession(val id: String = UUID.randomUUID().toString):
  private val counter = AtomicLong(0L)

  /** Next outbound sequence number (monotonically increasing, starts at 1). */
  def nextSeq(): Long = counter.incrementAndGet()

  /** Stamp an envelope with this session's id and next sequence number. */
  def stamp(env: WireEnvelope): WireEnvelope =
    env.copy(headers = env.headers ++ Map(
      "session-id" -> id,
      "seq"        -> nextSeq().toString,
    ))

object WireSession:
  def apply(): WireSession = new WireSession()
  def apply(id: String): WireSession = new WireSession(id)

/** Sliding-window replay guard for inbound frames.
 *
 *  Accepts a sequence number if it is:
 *  1. Greater than the highest seen (advances the window).
 *  2. Within `windowSize` of the highest seen AND not already seen.
 *
 *  Rejects sequence numbers outside the window or already seen. */
class WireReplayWindow(windowSize: Int = 64):
  private var highest = 0L
  private val seen    = scala.collection.mutable.BitSet()

  private def slot(seq: Long): Int = (seq % windowSize).toInt

  /** Check and record a sequence number.
   *  Returns `Right(())` if accepted, `Left(error)` if rejected. */
  def checkAndRecord(seq: Long): Either[WireDecodeError, Unit] =
    synchronized:
      if seq <= 0 then
        Left(WireDecodeError.MalformedInput(s"Invalid sequence number: $seq"))
      else if seq > highest + windowSize then
        Left(WireDecodeError.MalformedInput(
          s"Sequence number $seq too far ahead of window (highest=$highest, window=$windowSize)"
        ))
      else if seq > highest then
        // Advance window — clear slots between old highest and new
        (highest + 1L to seq).foreach { s => seen -= slot(s) }
        highest = seq
        seen += slot(seq)
        Right(())
      else if highest - seq >= windowSize then
        Left(WireDecodeError.MalformedInput(
          s"Sequence number $seq outside replay window (highest=$highest, window=$windowSize)"
        ))
      else if seen.contains(slot(seq)) then
        Left(WireDecodeError.MalformedInput(s"Replayed sequence number: $seq"))
      else
        seen += slot(seq)
        Right(())

  def highestSeen: Long = synchronized(highest)

/** Convenience: extract seq from envelope headers, run replay check. */
object WireReplayWindow:
  def checkEnvelope(env: WireEnvelope, window: WireReplayWindow): Either[WireDecodeError, Unit] =
    env.headers.get("seq") match
      case None =>
        Left(WireDecodeError.MalformedInput("Missing 'seq' header in envelope"))
      case Some(s) =>
        scala.util.Try(s.toLong).toEither.left.map { _ =>
          WireDecodeError.MalformedInput(s"Invalid seq header value: '$s'")
        }.flatMap(window.checkAndRecord)
