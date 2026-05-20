package scalascript.crypto.bouncycastle

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.{KeccakDigest, RIPEMD160Digest, SHA256Digest, SHA512Digest}
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter

import scalascript.crypto.HashAlgo

private[bouncycastle] object Hashes:

  private def newDigest(algo: HashAlgo): Digest = algo match
    case HashAlgo.Sha256    => new SHA256Digest()
    case HashAlgo.Sha512    => new SHA512Digest()
    case HashAlgo.Keccak256 => new KeccakDigest(256)
    case HashAlgo.Ripemd160 => new RIPEMD160Digest()
    case HashAlgo.None      => throw new IllegalArgumentException("HashAlgo.None is not a real digest")
    case HashAlgo.HmacSha512 =>
      throw new IllegalArgumentException("HashAlgo.HmacSha512 is a MAC, not a Digest; use Hashes.hmac")

  def hash(algo: HashAlgo, data: Array[Byte]): Array[Byte] =
    val d = newDigest(algo)
    d.update(data, 0, data.length)
    val out = new Array[Byte](d.getDigestSize)
    d.doFinal(out, 0)
    out

  def hmac(algo: HashAlgo, key: Array[Byte], data: Array[Byte]): Array[Byte] =
    val inner: Digest = algo match
      case HashAlgo.HmacSha512 => new SHA512Digest()
      case HashAlgo.Sha512     => new SHA512Digest()
      case HashAlgo.Sha256     => new SHA256Digest()
      case HashAlgo.Keccak256  => new KeccakDigest(256)
      case HashAlgo.Ripemd160  => new RIPEMD160Digest()
      case HashAlgo.None       => throw new IllegalArgumentException("HMAC needs a digest, not HashAlgo.None")
    val mac = new HMac(inner)
    mac.init(new KeyParameter(key))
    mac.update(data, 0, data.length)
    val out = new Array[Byte](mac.getMacSize)
    mac.doFinal(out, 0)
    out
