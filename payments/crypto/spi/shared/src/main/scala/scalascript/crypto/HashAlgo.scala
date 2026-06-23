package scalascript.crypto

/** Hash / KDF algorithm identifiers used across CryptoBackend operations.
 *  `None` is used when the caller has pre-hashed the input. */
enum HashAlgo:
  case None
  case Sha256
  case Sha512
  case Keccak256
  case Ripemd160
  case HmacSha512
  /** BLAKE2b with a 224-bit (28-byte) digest (RFC 7693). */
  case Blake2b224
  /** BLAKE2b with a 256-bit (32-byte) digest (RFC 7693).  Cardano's address hash. */
  case Blake2b256
