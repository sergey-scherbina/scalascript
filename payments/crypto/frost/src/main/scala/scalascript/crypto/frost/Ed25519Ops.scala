package scalascript.crypto.frost

import java.math.BigInteger

/** The Ed25519 primitive operations FROST is built on — the **pluggable backend seam**, mirroring the project's
 *  `CryptoBackend` SPI (registry + per-platform substitution).
 *
 *  FROST (`FrostKeygen` / `FrostSign`) calls only through this interface, never `Ed25519Group` directly. The
 *  DEFAULT is [[Ed25519Ops.Reference]] — the pure-`BigInteger` implementation (`Ed25519Group` + JDK SHA-512),
 *  which compiles to every backend. A platform may `register` a NATIVE implementation (BouncyCastle group
 *  ops/hash on the JVM, `@noble` on JS, a Rust crate on Rust) that FROST then uses **transparently** — the
 *  reference stays the correctness fallback, the native is the fast path. */
trait Ed25519Ops:
  def id: String

  // ── points ──
  def base: Ed25519Group.Point
  def identity: Ed25519Group.Point
  def add(a: Ed25519Group.Point, b: Ed25519Group.Point): Ed25519Group.Point
  def mul(s: BigInteger, p: Ed25519Group.Point): Ed25519Group.Point
  def mulBase(s: BigInteger): Ed25519Group.Point
  def encode(p: Ed25519Group.Point): Array[Byte]
  def decode(bytes: Array[Byte]): Option[Ed25519Group.Point]
  def samePoint(a: Ed25519Group.Point, b: Ed25519Group.Point): Boolean

  // ── scalar field (mod L) ──
  def L: BigInteger
  def scalarAdd(a: BigInteger, b: BigInteger): BigInteger
  def scalarMul(a: BigInteger, b: BigInteger): BigInteger
  def scalarInv(a: BigInteger): BigInteger
  def scalarReduce(a: BigInteger): BigInteger

  // ── hashing / keygen ──
  def secretScalar(seed: Array[Byte]): BigInteger
  /** SHA-512 over the concatenation of `parts`. The first thing a native backend typically overrides. */
  def sha512(parts: Array[Byte]*): Array[Byte]

object Ed25519Ops:

  /** Pure reference implementation — `Ed25519Group` (BigInteger) + JDK SHA-512. Compiles to every backend
   *  that has `BigInteger`; the universal correctness fallback. */
  object Reference extends Ed25519Ops:
    def id = "reference-bigint"
    def base     = Ed25519Group.B
    def identity = Ed25519Group.Identity
    def add(a: Ed25519Group.Point, b: Ed25519Group.Point)  = Ed25519Group.add(a, b)
    def mul(s: BigInteger, p: Ed25519Group.Point)          = Ed25519Group.mul(s, p)
    def mulBase(s: BigInteger)                             = Ed25519Group.mulBase(s)
    def encode(p: Ed25519Group.Point)                     = Ed25519Group.encode(p)
    def decode(bytes: Array[Byte])                        = Ed25519Group.decode(bytes)
    def samePoint(a: Ed25519Group.Point, b: Ed25519Group.Point) = Ed25519Group.samePoint(a, b)
    def L = Ed25519Group.L
    def scalarAdd(a: BigInteger, b: BigInteger) = Ed25519Group.scalarAdd(a, b)
    def scalarMul(a: BigInteger, b: BigInteger) = Ed25519Group.scalarMul(a, b)
    def scalarInv(a: BigInteger)                = Ed25519Group.scalarInv(a)
    def scalarReduce(a: BigInteger)             = Ed25519Group.scalarReduce(a)
    def secretScalar(seed: Array[Byte])         = Ed25519Group.secretScalar(seed)
    def sha512(parts: Array[Byte]*): Array[Byte] =
      Sha512.digest(parts.foldLeft(Array.emptyByteArray)(_ ++ _))

  @volatile private var _current: Ed25519Ops = Reference

  /** The ambient backend FROST uses by default. */
  def current: Ed25519Ops = _current

  /** Install a backend (e.g. a native JVM/JS implementation). Subsequent FROST calls use it transparently. */
  def register(ops: Ed25519Ops): Unit = _current = ops

  /** Restore the pure reference backend. */
  def reset(): Unit = _current = Reference
