package scalascript.crypto.frost

/** JVM secure randomness for the `Ed25519Ops.Reference` backend. The platform-specific half of cryptoFrost
 *  (everything else is in `shared/`). */
private[frost] object PlatformEntropy:
  private val rng = new java.security.SecureRandom()
  def bytes(n: Int): Array[Byte] =
    val b = new Array[Byte](n); rng.nextBytes(b); b
