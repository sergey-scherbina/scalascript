package scalascript.crypto

/** BIP-32 / SLIP-0010 hierarchical-deterministic key node: a 32-byte
 *  private scalar plus a 32-byte chain code that authorises child
 *  derivation. The public key is recoverable from `privateKey` via the
 *  curve's base-point multiplication. */
case class HdKey(privateKey: Array[Byte], chainCode: Array[Byte])
