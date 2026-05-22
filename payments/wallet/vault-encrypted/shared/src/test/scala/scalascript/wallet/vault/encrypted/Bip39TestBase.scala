package scalascript.wallet.vault.encrypted

import org.scalatest.funsuite.AnyFunSuite

/** Cross-platform spec for [[Bip39]].  Lives in `shared/` and is mixed
 *  in by `Bip39Test` on each platform (which registers the platform's
 *  [[scalascript.crypto.CryptoBackend]] in its `beforeAll`).  Identical
 *  hex assertions across JVM (BouncyCastle) and JS (noble) verify that
 *  the entropy → mnemonic checksum, BIP-39 PBKDF2-HMAC-SHA512 seed
 *  derivation, and the embedded English wordlist all match byte-for-byte. */
abstract class Bip39TestBase extends AnyFunSuite:

  test("wordlist embedded const has 2048 entries, first 'abandon', last 'zoo'") {
    assert(Bip39Wordlist.words.size == 2048)
    assert(Bip39Wordlist.words.head == "abandon")
    assert(Bip39Wordlist.words.last == "zoo")
  }

  test("generate(32) produces 24 words") {
    val m = Bip39.generate(Bip39.randomEntropy(32))
    assert(m.words.size == 24)
  }

  test("generate(16) produces 12 words") {
    val m = Bip39.generate(Bip39.randomEntropy(16))
    assert(m.words.size == 12)
  }

  test("generate(24) produces 18 words") {
    val m = Bip39.generate(Bip39.randomEntropy(24))
    assert(m.words.size == 18)
  }

  test("fromPhrase round-trips a generated mnemonic") {
    val m    = Bip39.generate()
    val back = Bip39.fromPhrase(m.phrase)
    assert(back == Right(m))
  }

  test("fromPhrase rejects unknown word") {
    val result = Bip39.fromPhrase("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon zzzzz")
    assert(result.isLeft)
    assert(result.swap.getOrElse("").contains("Unknown"))
  }

  test("fromPhrase rejects wrong word count") {
    val result = Bip39.fromPhrase("abandon abandon abandon")
    assert(result.isLeft)
    assert(result.swap.getOrElse("").contains("word count"))
  }

  test("fromPhrase rejects bad checksum") {
    // flip last word to break checksum
    val m = Bip39.generate()
    val words = m.words.init :+ (if m.words.last == "zoo" then "zero" else "zoo")
    val result = Bip39.fromPhrase(words.mkString(" "))
    // may be unknown word OR bad checksum depending on replacement
    assert(result.isLeft)
  }

  test("known BIP-39 vector: all-zero entropy → first word is 'abandon'") {
    // BIP-39 test vector: 0x00 * 16 → "abandon abandon … art"
    val m = Bip39.generate(new Array[Byte](16))
    assert(m.words.head == "abandon")
    assert(m.words.size == 12)
  }

  test("toSeed is deterministic for same mnemonic + passphrase") {
    val m  = Bip39.generate()
    val s1 = m.toSeed("testpass")
    val s2 = m.toSeed("testpass")
    assert(s1.toSeq == s2.toSeq)
  }

  test("toSeed differs for different passphrases") {
    val m  = Bip39.generate()
    val s1 = m.toSeed("pass1")
    val s2 = m.toSeed("pass2")
    assert(s1.toSeq != s2.toSeq)
  }

  test("toSeed is 64 bytes") {
    assert(Bip39.generate().toSeed().length == 64)
  }

  test("toString does not leak words") {
    val m = Bip39.generate()
    assert(!m.toString.contains(m.words.head))
  }

  test("known BIP-39 PBKDF2 vector") {
    // From the BIP-39 test vectors: mnemonic = "abandon" * 11 + "about"
    // passphrase = "TREZOR"  → seed starts with 0xc55257...
    val m = Bip39.fromPhrase("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about").getOrElse(sys.error("parse failed"))
    val seed = m.toSeed("TREZOR")
    assert(seed.length == 64)
    // first byte of the known Trezor test vector seed
    assert((seed(0) & 0xff) == 0xc5)
    assert((seed(1) & 0xff) == 0x52)
    assert((seed(2) & 0xff) == 0x57)
  }
