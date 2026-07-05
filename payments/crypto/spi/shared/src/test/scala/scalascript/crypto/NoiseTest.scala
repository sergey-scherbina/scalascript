package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.charset.StandardCharsets.UTF_8

/** Noise (25519/ChaChaPoly/SHA256) — full handshakes for the NN / XX / IK patterns, with a functional
 *  gate (matching transport keys, correct authentication semantics, encrypted transport, tamper reject),
 *  on JVM and Scala.js. The underlying X25519 / ChaCha20-Poly1305 / HKDF / SHA-256 are byte-exact-pinned. */
class NoiseTest extends AnyFunSuite with Matchers:

  private def unhex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private def hex(b: Array[Byte]): String   = b.map(x => f"${x & 0xff}%02x").mkString
  private def utf8(s: String): Array[Byte]  = s.getBytes(UTF_8)

  private val iStatic = Noise.keyPair(unhex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"))
  private val rStatic = Noise.keyPair(unhex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"))
  private val iEph    = unhex("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f")
  private val rEph    = unhex("606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f")

  /** Drive a full handshake to completion; assert every carried payload decrypts. */
  private def drive(ini: Noise.HandshakeState, res: Noise.HandshakeState, nMessages: Int): Unit =
    ini.useEphemeral(iEph); res.useEphemeral(rEph)
    var writer = ini; var reader = res; var idx = 0
    while idx < nMessages do
      val payload = utf8(s"handshake payload $idx")
      val msg = writer.writeMessage(payload)
      new String(reader.readMessage(msg), UTF_8) shouldBe s"handshake payload $idx"
      val t = writer; writer = reader; reader = t; idx += 1

  private def assertTransport(ini: Noise.HandshakeState, res: Noise.HandshakeState): Unit =
    val (ic1, ic2) = ini.split()
    val (rc1, rc2) = res.split()
    hex(ic1.k) shouldBe hex(rc1.k)                      // both sides agree on the two directional keys
    hex(ic2.k) shouldBe hex(rc2.k)
    hex(ic1.k) should not equal hex(ic2.k)
    val a = ic1.encryptWithAd(Array.emptyByteArray, utf8("ping"))
    new String(rc1.decryptWithAd(Array.emptyByteArray, a), UTF_8) shouldBe "ping"     // initiator → responder
    val b = rc2.encryptWithAd(Array.emptyByteArray, utf8("pong"))
    new String(ic2.decryptWithAd(Array.emptyByteArray, b), UTF_8) shouldBe "pong"     // responder → initiator

  test("Noise_XX — mutual authentication, matching transport keys, encrypted transport") {
    val ini = new Noise.HandshakeState(Noise.XX, initiator = true,  iStatic)
    val res = new Noise.HandshakeState(Noise.XX, initiator = false, rStatic)
    drive(ini, res, Noise.XX.messages.length)
    hex(ini.remoteStatic) shouldBe hex(rStatic.pub)     // each side learned the peer's static
    hex(res.remoteStatic) shouldBe hex(iStatic.pub)
    assertTransport(ini, res)
  }

  test("Noise_NN — unauthenticated key agreement with matching transport keys") {
    val ini = new Noise.HandshakeState(Noise.NN, initiator = true,  null)
    val res = new Noise.HandshakeState(Noise.NN, initiator = false, null)
    drive(ini, res, Noise.NN.messages.length)
    ini.remoteStatic shouldBe null                       // NN exchanges no static keys
    res.remoteStatic shouldBe null
    assertTransport(ini, res)
  }

  test("Noise_IK — initiator pre-knows the responder static; both authenticate") {
    val ini = new Noise.HandshakeState(Noise.IK, initiator = true,  iStatic, rsKnown = rStatic.pub)
    val res = new Noise.HandshakeState(Noise.IK, initiator = false, rStatic)
    drive(ini, res, Noise.IK.messages.length)
    hex(ini.remoteStatic) shouldBe hex(rStatic.pub)      // pre-shared
    hex(res.remoteStatic) shouldBe hex(iStatic.pub)      // learned from message 1
    assertTransport(ini, res)
  }

  test("Noise_N — one-way sealed box: anonymous sender, known + authenticated recipient") {
    val ini = new Noise.HandshakeState(Noise.N, initiator = true,  null, rsKnown = rStatic.pub)
    val res = new Noise.HandshakeState(Noise.N, initiator = false, rStatic)
    drive(ini, res, Noise.N.messages.length)
    hex(ini.remoteStatic) shouldBe hex(rStatic.pub)     // recipient pre-known
    res.remoteStatic shouldBe null                       // sender stays anonymous
    assertTransport(ini, res)
  }

  test("Noise_NK — responder pre-known + authenticated, anonymous initiator") {
    val ini = new Noise.HandshakeState(Noise.NK, initiator = true,  null, rsKnown = rStatic.pub)
    val res = new Noise.HandshakeState(Noise.NK, initiator = false, rStatic)
    drive(ini, res, Noise.NK.messages.length)
    hex(ini.remoteStatic) shouldBe hex(rStatic.pub)
    res.remoteStatic shouldBe null
    assertTransport(ini, res)
  }

  test("Noise_XK — responder pre-known; initiator transmits its static for mutual auth") {
    val ini = new Noise.HandshakeState(Noise.XK, initiator = true,  iStatic, rsKnown = rStatic.pub)
    val res = new Noise.HandshakeState(Noise.XK, initiator = false, rStatic)
    drive(ini, res, Noise.XK.messages.length)
    hex(ini.remoteStatic) shouldBe hex(rStatic.pub)
    hex(res.remoteStatic) shouldBe hex(iStatic.pub)      // learned in the final message
    assertTransport(ini, res)
  }

  test("a tampered handshake message fails authentication") {
    val ini = new Noise.HandshakeState(Noise.XX, initiator = true,  iStatic)
    val res = new Noise.HandshakeState(Noise.XX, initiator = false, rStatic)
    ini.useEphemeral(iEph); res.useEphemeral(rEph)
    val m1 = ini.writeMessage(Array.emptyByteArray); res.readMessage(m1)
    val m2 = res.writeMessage(utf8("payload"))
    val bad = m2.clone(); bad(bad.length - 1) = (bad(bad.length - 1) ^ 0x01).toByte
    an [IllegalStateException] should be thrownBy ini.readMessage(bad)
  }
