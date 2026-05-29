package scalascript.wire.security

import org.scalatest.funsuite.AnyFunSuite
import scalascript.wire.{WireEnvelope, WireFormat, WireValue}

/** Tests for wire security and operations: HMAC, compression, sessions,
 *  replay windows, metrics, and debug dump.
 *
 *  Spec: docs/distributed-wire-protocol.md §Phase 7 */
class WireSecurityOpsTest extends AnyFunSuite:

  private val key      = "super-secret-key".getBytes("UTF-8")
  private val payload  = "hello, wire world!".getBytes("UTF-8")

  private val sampleEnv = WireEnvelope(
    protocol = "rpc", protocolVer = 1, format = WireFormat.Json,
    kind = "request", correlationId = Some("req-1"), schemaId = None,
    flags = Set.empty, headers = Map.empty, payload = WireValue.Str("data"),
  )

  // ── HMAC signing / verification ───────────────────────────────────────────

  test("WireIntegrity.sign produces a non-empty Base64 string"):
    val hmac = WireIntegrity.sign(payload, key)
    assert(hmac.nonEmpty)
    assert(hmac.forall(c => c.isLetterOrDigit || c == '+' || c == '/' || c == '='))

  test("WireIntegrity.verify accepts correct signature"):
    val sig = WireIntegrity.sign(payload, key)
    assert(WireIntegrity.verify(payload, key, sig))

  test("WireIntegrity.verify rejects tampered payload"):
    val sig = WireIntegrity.sign(payload, key)
    val tampered = "tampered data".getBytes("UTF-8")
    assert(!WireIntegrity.verify(tampered, key, sig))

  test("WireIntegrity.verify rejects tampered signature"):
    val sig = WireIntegrity.sign(payload, key)
    val tampered = sig.dropRight(4) + "XXXX"
    assert(!WireIntegrity.verify(payload, key, tampered))

  test("WireIntegrity.verify rejects wrong key"):
    val sig    = WireIntegrity.sign(payload, key)
    val altKey = "different-key".getBytes("UTF-8")
    assert(!WireIntegrity.verify(payload, altKey, sig))

  test("attachHmac adds hmac flag and header"):
    val stamped = WireIntegrity.attachHmac(sampleEnv, payload, key)
    assert(stamped.flags.contains("hmac"))
    assert(stamped.headers.contains("hmac-sha256"))

  test("verifyEnvelope accepts valid HMAC"):
    val stamped = WireIntegrity.attachHmac(sampleEnv, payload, key)
    WireIntegrity.verifyEnvelope(stamped, payload, key) match
      case Right(_)  => ()
      case Left(err) => fail(err.message)

  test("verifyEnvelope rejects missing hmac flag"):
    WireIntegrity.verifyEnvelope(sampleEnv, payload, key) match
      case Left(err) => assert(err.message.contains("flag"))
      case Right(_)  => fail("expected rejection")

  test("verifyEnvelope rejects tampered payload"):
    val stamped  = WireIntegrity.attachHmac(sampleEnv, payload, key)
    val tampered = "different content".getBytes("UTF-8")
    WireIntegrity.verifyEnvelope(stamped, tampered, key) match
      case Left(err) => assert(err.message.contains("HMAC") || err.message.contains("verif"))
      case Right(_)  => fail("expected verification failure")

  // ── Compression ───────────────────────────────────────────────────────────

  test("WireCompression.compress none is identity"):
    WireCompression.compress(payload, WireCompression.None) match
      case Right(out) => assert(out.toSeq == payload.toSeq)
      case Left(err)  => fail(err)

  test("WireCompression.decompress none is identity"):
    WireCompression.decompress(payload, WireCompression.None) match
      case Right(out) => assert(out.toSeq == payload.toSeq)
      case Left(err)  => fail(err.message)

  test("WireCompression gzip compress/decompress round-trip"):
    val data = ("hello world " * 100).getBytes("UTF-8")
    WireCompression.compress(data, WireCompression.Gzip) match
      case Left(err)  => fail(err)
      case Right(compressed) =>
        WireCompression.decompress(compressed, WireCompression.Gzip) match
          case Right(decompressed) =>
            assert(decompressed.toSeq == data.toSeq,
              s"decompressed length=${decompressed.length}, expected=${data.length}")
          case Left(err) => fail(err.message)

  test("WireCompression gzip reduces size for repetitive data"):
    val data = ("aaa bbb ccc " * 200).getBytes("UTF-8")
    WireCompression.compress(data, WireCompression.Gzip) match
      case Right(compressed) =>
        val ratio = WireCompression.ratio(data, compressed)
        assert(ratio < 0.5, s"Expected >50% compression, got ratio=$ratio")
      case Left(err) => fail(err)

  test("WireCompression.compress unsupported algorithm returns Left"):
    WireCompression.compress(payload, "brotli") match
      case Left(err)  => assert(err.contains("Unsupported"))
      case Right(_)   => fail("expected error for unsupported algorithm")

  test("WireCompression.decompress corrupt data returns Left"):
    val garbage = Array.fill[Byte](64)(0xFF.toByte)
    WireCompression.decompress(garbage, WireCompression.Gzip) match
      case Left(err) => assert(err.message.contains("gzip") || err.message.nonEmpty)
      case Right(_)  => fail("expected decompression error for garbage")

  // ── Session IDs and sequence numbers ─────────────────────────────────────

  test("WireSession.nextSeq increments from 1"):
    val session = WireSession()
    assert(session.nextSeq() == 1L)
    assert(session.nextSeq() == 2L)
    assert(session.nextSeq() == 3L)

  test("WireSession.stamp adds session-id and seq headers"):
    val session = WireSession("test-session-id")
    val stamped = session.stamp(sampleEnv)
    assert(stamped.headers("session-id") == "test-session-id")
    assert(stamped.headers("seq") == "1")

  test("WireSession IDs are unique by default"):
    val s1 = WireSession()
    val s2 = WireSession()
    assert(s1.id != s2.id)

  test("Two sessions produce independent sequence numbers"):
    val s1 = WireSession()
    val s2 = WireSession()
    s1.nextSeq(); s1.nextSeq()  // advance s1 to 2
    assert(s2.nextSeq() == 1L)  // s2 starts at 1

  // ── Replay window ─────────────────────────────────────────────────────────

  test("WireReplayWindow accepts sequential frames"):
    val window = WireReplayWindow()
    for i <- 1 to 10 do
      window.checkAndRecord(i.toLong) match
        case Right(_)  => ()
        case Left(err) => fail(s"rejected seq=$i: ${err.message}")

  test("WireReplayWindow rejects duplicate sequence number"):
    val window = WireReplayWindow()
    assert(window.checkAndRecord(1L).isRight)
    window.checkAndRecord(1L) match
      case Left(err) => assert(err.message.contains("Replay") || err.message.contains("replay"))
      case Right(_)  => fail("expected replay rejection")

  test("WireReplayWindow rejects sequence outside window"):
    val window = WireReplayWindow(windowSize = 8)
    for i <- 1 to 16 do window.checkAndRecord(i.toLong)  // advance to 16
    // seq=1 is now more than 8 behind highest=16
    window.checkAndRecord(1L) match
      case Left(err) => assert(err.message.contains("window") || err.message.contains("Replay"))
      case Right(_)  => fail("expected out-of-window rejection")

  test("WireReplayWindow rejects seq=0"):
    val window = WireReplayWindow()
    window.checkAndRecord(0L) match
      case Left(err) => assert(err.message.contains("Invalid"))
      case Right(_)  => fail("expected rejection of seq=0")

  test("WireReplayWindow.checkEnvelope rejects missing seq header"):
    val window = WireReplayWindow()
    WireReplayWindow.checkEnvelope(sampleEnv, window) match
      case Left(err) => assert(err.message.contains("seq"))
      case Right(_)  => fail("expected error for missing seq")

  test("WireReplayWindow.checkEnvelope accepts valid seq header"):
    val window = WireReplayWindow()
    val stamped = sampleEnv.copy(headers = Map("seq" -> "1"))
    WireReplayWindow.checkEnvelope(stamped, window) match
      case Right(_)  => ()
      case Left(err) => fail(err.message)

  // ── WireMetrics ───────────────────────────────────────────────────────────

  test("WireMetrics records frames sent"):
    val m = WireMetrics()
    m.recordFrameSent(100)
    m.recordFrameSent(200)
    assert(m.framesSent == 2)
    assert(m.bytesSent == 300)

  test("WireMetrics records frames received"):
    val m = WireMetrics()
    m.recordFrameReceived(512)
    assert(m.framesReceived == 1)
    assert(m.bytesReceived == 512)

  test("WireMetrics records error counters"):
    val m = WireMetrics()
    m.recordDecodeError()
    m.recordHmacFailure()
    m.recordReplayRejected()
    assert(m.decodeErrors == 1)
    assert(m.hmacFailures == 1)
    assert(m.replayRejected == 1)

  test("WireMetrics snapshot is immutable"):
    val m  = WireMetrics()
    m.recordFrameSent(100)
    val snap = m.snapshot
    m.recordFrameSent(200)
    assert(snap.framesSent == 1)
    assert(m.framesSent    == 2)

  test("WireMetrics reset clears all counters"):
    val m = WireMetrics()
    m.recordFrameSent(1); m.recordFrameReceived(2); m.recordDecodeError()
    m.reset()
    val snap = m.snapshot
    assert(snap == WireMetricsSnapshot.zero)

  // ── WireTlsConfig ─────────────────────────────────────────────────────────

  test("WireTlsConfig.fromMap parses full config"):
    val m = Map[String, Any](
      "keystorePath"       -> "/certs/server.p12",
      "keystorePassword"   -> "changeit",
      "truststorePath"     -> "/certs/ca.p12",
      "truststorePassword" -> "trustpass",
      "requireClientAuth"  -> true,
    )
    WireTlsConfig.fromMap(m) match
      case Some(cfg) =>
        assert(cfg.keystorePath == "/certs/server.p12")
        assert(cfg.requireClientAuth == true)
        assert(cfg.protocols.contains("TLSv1.3"))
      case None => fail("expected config to parse")

  test("WireTlsConfig.fromMap returns None when required fields missing"):
    val m = Map[String, Any]("keystorePath" -> "/certs/server.p12")
    assert(WireTlsConfig.fromMap(m).isEmpty)

  // ── WireDebug ─────────────────────────────────────────────────────────────

  test("WireDebug.summary produces a non-empty one-liner"):
    val line = WireDebug.summary(sampleEnv)
    assert(line.nonEmpty)
    assert(line.contains("rpc"))
    assert(line.contains("request"))
    assert(!line.contains("\n"))

  test("WireDebug.summary includes correlation id"):
    val line = WireDebug.summary(sampleEnv)
    assert(line.contains("req-1"))

  test("WireDebug.summary includes session and seq from headers"):
    val env = sampleEnv.copy(headers = Map("session-id" -> "abc-def-123", "seq" -> "42"))
    val line = WireDebug.summary(env)
    assert(line.contains("seq=42"))
    assert(line.contains("sid=abc-def-"))

  test("WireDebug.dump produces multi-line output"):
    val dump = WireDebug.dump(sampleEnv)
    assert(dump.contains("WireEnvelope"))
    assert(dump.contains("protocol"))
    assert(dump.contains("rpc"))
    assert(dump.contains("request"))
    assert(dump.split("\n").length > 3)

  test("WireDebug.dump includes flags when present"):
    val env  = sampleEnv.copy(flags = Set("hmac", "compressed"))
    val dump = WireDebug.dump(env)
    assert(dump.contains("hmac"))
    assert(dump.contains("compressed"))
