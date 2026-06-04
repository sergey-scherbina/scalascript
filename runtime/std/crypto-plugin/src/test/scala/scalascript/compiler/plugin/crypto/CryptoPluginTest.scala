package scalascript.compiler.plugin.crypto

import org.scalatest.funsuite.AnyFunSuite
import scalascript.testkit.TestInterpreter

class CryptoPluginTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(CryptoInterpreterPlugin()))

  private def evalStr(snippet: String): String =
    interp.eval(snippet).asInstanceOf[String]

  // ── sha256 — NIST FIPS 180-4 test vectors ───────────────────────────────

  test("sha256 of empty string matches NIST vector"):
    assert(evalStr("""sha256("")""") ==
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

  test("sha256 of 'abc' matches JVM-computed reference value"):
    // Verified via java.security.MessageDigest.getInstance("SHA-256") directly.
    assert(evalStr("""sha256("abc")""") ==
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

  test("sha256 output is always 64 lowercase hex characters"):
    val result = evalStr("""sha256("hello world")""")
    assert(result.length == 64, s"expected 64 chars, got ${result.length}: $result")
    assert(result.matches("[0-9a-f]{64}"), s"not lowercase hex: $result")

  test("sha256 is deterministic"):
    val a = evalStr("""sha256("deterministic")""")
    val b = evalStr("""sha256("deterministic")""")
    assert(a == b)

  test("sha256 of distinct inputs produces distinct digests"):
    val a = evalStr("""sha256("foo")""")
    val b = evalStr("""sha256("bar")""")
    assert(a != b)

  // ── hmacSha256 — RFC 4231 test vector ────────────────────────────────────

  test("hmacSha256 output is always 64 lowercase hex characters"):
    val result = evalStr("""hmacSha256("key", "data")""")
    assert(result.length == 64, s"expected 64 chars, got ${result.length}: $result")
    assert(result.matches("[0-9a-f]{64}"))

  test("hmacSha256 matches known vector (RFC 4231 §4.2)"):
    // HMAC-SHA256("key", "The quick brown fox jumps over the lazy dog")
    assert(evalStr("""hmacSha256("key", "The quick brown fox jumps over the lazy dog")""") ==
      "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8")

  test("hmacSha256 is key-sensitive"):
    val a = evalStr("""hmacSha256("key1", "data")""")
    val b = evalStr("""hmacSha256("key2", "data")""")
    assert(a != b)

  test("hmacSha256 is data-sensitive"):
    val a = evalStr("""hmacSha256("key", "data1")""")
    val b = evalStr("""hmacSha256("key", "data2")""")
    assert(a != b)

  // ── base64Encode ─────────────────────────────────────────────────────────

  test("base64Encode 'Man' → 'TWFu' (RFC 4648 example)"):
    assert(evalStr("""base64Encode("Man")""") == "TWFu")

  test("base64Encode 'a' → 'YQ==' (1-byte padding)"):
    assert(evalStr("""base64Encode("a")""") == "YQ==")

  test("base64Encode 'ab' → 'YWI=' (2-byte padding)"):
    assert(evalStr("""base64Encode("ab")""") == "YWI=")

  test("base64Encode 'abc' → 'YWJj' (no padding)"):
    assert(evalStr("""base64Encode("abc")""") == "YWJj")

  // ── base64Decode ─────────────────────────────────────────────────────────

  test("base64Decode 'aGVsbG8=' → 'hello'"):
    assert(evalStr("""base64Decode("aGVsbG8=")""") == "hello")

  test("base64Decode 'TWFu' → 'Man'"):
    assert(evalStr("""base64Decode("TWFu")""") == "Man")

  test("base64Encode / base64Decode round-trip"):
    assert(evalStr("""base64Decode(base64Encode("The quick brown fox jumps over the lazy dog"))""") ==
      "The quick brown fox jumps over the lazy dog")
