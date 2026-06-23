package scalascript.crypto.frost

import java.math.BigInteger
import scalascript.crypto.{CryptoBackend, HashAlgo}

/** An [[Ed25519Ops]] backend that delegates the substitutable PRIMITIVES — SHA-512 and secure randomness — to
 *  the project's `CryptoBackend` SPI (BouncyCastle on the JVM, `@noble` on JS), while keeping the pure-`BigInteger`
 *  group arithmetic of the reference (`CryptoBackend` exposes no Ed25519 *group* operations). This is the
 *  "use the platform-native crypto provider" backend for FROST — register it and FROST's hashing/RNG transparently
 *  run on whatever crypto provider the platform has:
 *  {{{
 *  Ed25519Ops.register(new CryptoBackedEd25519Ops())   // FROST now hashes/randomizes via BC (JVM) / noble (JS)
 *  // … keygen / sign …
 *  Ed25519Ops.reset()                                   // back to the pure reference
 *  }}}
 *  Requires a `CryptoBackend` to be registered (ServiceLoader on JVM, `register(...)` on JS). */
class CryptoBackedEd25519Ops extends Ed25519Ops:
  private val ref = Ed25519Ops.Reference
  private def crypto: CryptoBackend = CryptoBackend.get()

  def id: String = s"crypto-backed(${crypto.id})"

  // Group + scalar arithmetic: keep the reference (no native Ed25519 group ops in the CryptoBackend SPI).
  export ref.{base, identity, add, mul, mulBase, encode, decode, samePoint, L,
              scalarAdd, scalarMul, scalarInv, scalarReduce, secretScalar}

  // PRIMITIVES delegated to the platform crypto provider:
  def sha512(parts: Array[Byte]*): Array[Byte] =
    crypto.hash(HashAlgo.Sha512, parts.foldLeft(Array.emptyByteArray)(_ ++ _))
  def randomBytes(n: Int): Array[Byte] = crypto.randomBytes(n)
  // `randomScalar()` is inherited from the trait and uses the delegated `randomBytes` above.
