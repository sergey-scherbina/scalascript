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
