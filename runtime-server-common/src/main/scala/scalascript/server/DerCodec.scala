package scalascript.server

/** Minimal DER (Distinguished Encoding Rules) helpers shared between
 *  the JWT RSA key loader (`JwtRsa`) and the TLS cert/key loader
 *  (`WebServer.buildSslContext`).  Kept here so the lower runtime layer
 *  has everything it needs to parse RSA keys without depending on
 *  WebServer.scala. */
object DerCodec:

  /** Wrap a PKCS#1 RSA key (no envelope) into the PKCS#8 DER structure
   *  that `PKCS8EncodedKeySpec` expects.  The RSA OID is 1.2.840.113549.1.1.1. */
  def wrapPkcs1InPkcs8(pkcs1: Array[Byte]): Array[Byte] =
    val oidSeq = Array[Byte](
      0x30, 0x0d,
      0x06, 0x09,
      0x2a, 0x86.toByte, 0x48, 0x86.toByte, 0xf7.toByte, 0x0d, 0x01, 0x01, 0x01,
      0x05, 0x00
    )
    val octetStr = encodeDerTlv(0x04, pkcs1)
    encodeDerTlv(0x30, Array[Byte](0x02, 0x01, 0x00) ++ oidSeq ++ octetStr)

  def encodeDerTlv(tag: Byte, value: Array[Byte]): Array[Byte] =
    val len = value.length
    val lenBytes =
      if len < 128 then Array(len.toByte)
      else if len < 256 then Array(0x81.toByte, len.toByte)
      else Array(0x82.toByte, (len >> 8).toByte, (len & 0xff).toByte)
    Array(tag) ++ lenBytes ++ value
