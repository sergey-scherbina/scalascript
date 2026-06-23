package scalascript.crypto.frost

import org.scalatest.funsuite.AnyFunSuite

class Sha512Test extends AnyFunSuite:

  private def hex(b: Array[Byte]): String = b.map("%02x".format(_)).mkString

  test("SHA-512('abc') matches the FIPS 180-4 vector"):
    assert(hex(Sha512.digest("abc".getBytes("UTF-8"))) ==
      "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
      "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f")

  test("SHA-512('') matches the empty-string vector"):
    assert(hex(Sha512.digest(Array.emptyByteArray)) ==
      "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
      "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e")

  test("matches java.security.MessageDigest across padding boundaries"):
    val md  = java.security.MessageDigest.getInstance("SHA-512")
    val rnd = new java.util.Random(0xABCL)
    for len <- List(0, 1, 55, 56, 57, 63, 64, 111, 112, 113, 127, 128, 129, 200, 1000) do
      val data = new Array[Byte](len); rnd.nextBytes(data)
      md.reset()
      assert(hex(Sha512.digest(data)) == hex(md.digest(data)), s"mismatch at len=$len")
