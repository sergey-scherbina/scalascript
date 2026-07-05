package scalascript.crypto

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.charset.StandardCharsets.UTF_8

/** Noise_XX_25519_ChaChaPoly_SHA256 — a full initiator↔responder handshake with a functional gate
 *  (matching transport keys, mutual static-key authentication, encrypted transport both ways), on JVM
 *  and Scala.js. The underlying X25519 / ChaCha20-Poly1305 / HKDF / SHA-256 are each byte-exact-pinned. */
class NoiseXXTest extends AnyFunSuite with Matchers:

  private def unhex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private def hex(b: Array[Byte]): String   = b.map(x => f"${x & 0xff}%02x").mkString
  private def utf8(s: String): Array[Byte]  = s.getBytes(UTF_8)

  private val iStatic = NoiseXX.keyPair(unhex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"))
  private val rStatic = NoiseXX.keyPair(unhex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"))
  private val iEph    = unhex("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f")
  private val rEph    = unhex("606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f")

  private def runHandshake(): (NoiseXX.HandshakeState, NoiseXX.HandshakeState, Array[Byte], Array[Byte]) =
    val ini = NoiseXX.HandshakeState(initiator = true,  iStatic)
    val res = NoiseXX.HandshakeState(initiator = false, rStatic)
    ini.useEphemeral(iEph)
    val m1 = ini.writeMessage(NoiseXX.Msg1, Array.emptyByteArray)
    res.readMessage(NoiseXX.Msg1, m1)
    res.useEphemeral(rEph)
    val m2 = res.writeMessage(NoiseXX.Msg2, utf8("hello from responder"))
    (ini, res, m1, m2)

  test("Noise_XX handshake completes with matching keys, mutual auth, and an encrypted transport") {
    val (ini, res, _, m2) = runHandshake()
    new String(ini.readMessage(NoiseXX.Msg2, m2), UTF_8) shouldBe "hello from responder"
    val m3 = ini.writeMessage(NoiseXX.Msg3, utf8("hello from initiator"))
    new String(res.readMessage(NoiseXX.Msg3, m3), UTF_8) shouldBe "hello from initiator"

    // XX mutual authentication: each side ends up holding the other's static public key
    hex(ini.remoteStatic) shouldBe hex(rStatic.pub)
    hex(res.remoteStatic) shouldBe hex(iStatic.pub)

    // both sides derive the same pair of transport keys
    val (ic1, ic2) = ini.split()
    val (rc1, rc2) = res.split()
    hex(ic1.k) shouldBe hex(rc1.k)
    hex(ic2.k) shouldBe hex(rc2.k)
    hex(ic1.k) should not equal hex(ic2.k)                  // the two directions use distinct keys

    // encrypted transport in both directions (initiator→responder on c1, responder→initiator on c2)
    val a = ic1.encryptWithAd(Array.emptyByteArray, utf8("ping"))
    new String(rc1.decryptWithAd(Array.emptyByteArray, a), UTF_8) shouldBe "ping"
    val b = rc2.encryptWithAd(Array.emptyByteArray, utf8("pong"))
    new String(ic2.decryptWithAd(Array.emptyByteArray, b), UTF_8) shouldBe "pong"
  }

  test("a tampered handshake message fails authentication") {
    val (ini, _, _, m2) = runHandshake()
    val bad = m2.clone(); bad(bad.length - 1) = (bad(bad.length - 1) ^ 0x01).toByte   // flip the last payload/tag byte
    an [IllegalStateException] should be thrownBy ini.readMessage(NoiseXX.Msg2, bad)
  }
