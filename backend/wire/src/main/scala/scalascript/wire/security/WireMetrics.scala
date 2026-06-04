package scalascript.wire.security

import java.util.concurrent.atomic.LongAdder

/** Concurrent wire-layer metrics.
 *
 *  One `WireMetrics` instance per connection/node.  Thread-safe via
 *  `LongAdder` (low-contention increment) and `AtomicLong` (read + CAS).
 *
 *  Spec: docs/specs/distributed-wire-protocol.md §Observability */
class WireMetrics:
  private val _framesSent      = LongAdder()
  private val _framesReceived  = LongAdder()
  private val _bytesSent       = LongAdder()
  private val _bytesReceived   = LongAdder()
  private val _decodeErrors    = LongAdder()
  private val _hmacFailures    = LongAdder()
  private val _replayRejected  = LongAdder()
  private val _chunkedFrames   = LongAdder()
  private val _compressedSent  = LongAdder()
  private val _compressedRecv  = LongAdder()

  def recordFrameSent(bytes: Long): Unit =
    _framesSent.increment()
    _bytesSent.add(bytes)

  def recordFrameReceived(bytes: Long): Unit =
    _framesReceived.increment()
    _bytesReceived.add(bytes)

  def recordDecodeError(): Unit   = _decodeErrors.increment()
  def recordHmacFailure(): Unit   = _hmacFailures.increment()
  def recordReplayRejected(): Unit = _replayRejected.increment()
  def recordChunkedFrame(): Unit  = _chunkedFrames.increment()
  def recordCompressedSent(): Unit = _compressedSent.increment()
  def recordCompressedRecv(): Unit = _compressedRecv.increment()

  def framesSent: Long      = _framesSent.sum()
  def framesReceived: Long  = _framesReceived.sum()
  def bytesSent: Long       = _bytesSent.sum()
  def bytesReceived: Long   = _bytesReceived.sum()
  def decodeErrors: Long    = _decodeErrors.sum()
  def hmacFailures: Long    = _hmacFailures.sum()
  def replayRejected: Long  = _replayRejected.sum()
  def chunkedFrames: Long   = _chunkedFrames.sum()
  def compressedSent: Long  = _compressedSent.sum()
  def compressedRecv: Long  = _compressedRecv.sum()

  def snapshot: WireMetricsSnapshot = WireMetricsSnapshot(
    framesSent     = framesSent,
    framesReceived = framesReceived,
    bytesSent      = bytesSent,
    bytesReceived  = bytesReceived,
    decodeErrors   = decodeErrors,
    hmacFailures   = hmacFailures,
    replayRejected = replayRejected,
    chunkedFrames  = chunkedFrames,
    compressedSent = compressedSent,
    compressedRecv = compressedRecv,
  )

  def reset(): Unit =
    _framesSent.reset(); _framesReceived.reset()
    _bytesSent.reset(); _bytesReceived.reset()
    _decodeErrors.reset(); _hmacFailures.reset()
    _replayRejected.reset(); _chunkedFrames.reset()
    _compressedSent.reset(); _compressedRecv.reset()

/** Immutable point-in-time snapshot of wire metrics. */
case class WireMetricsSnapshot(
  framesSent:     Long,
  framesReceived: Long,
  bytesSent:      Long,
  bytesReceived:  Long,
  decodeErrors:   Long,
  hmacFailures:   Long,
  replayRejected: Long,
  chunkedFrames:  Long,
  compressedSent: Long,
  compressedRecv: Long,
)

object WireMetricsSnapshot:
  val zero: WireMetricsSnapshot = WireMetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
