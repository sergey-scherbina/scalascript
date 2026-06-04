package scalascript.wire.security

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}
import scalascript.wire.WireDecodeError

/** Frame-level compression for wire envelopes.
 *
 *  Compresses/decompresses serialised envelope bytes.  The `"compressed"` flag
 *  in `WireEnvelope.flags` signals that the payload bytes are compressed.
 *
 *  Supported algorithms (controlled by `WireConfig.compression`):
 *  - `"none"` — passthrough
 *  - `"gzip"` — DEFLATE with gzip header (standard JVM library)
 *
 *  Spec: docs/specs/distributed-wire-protocol.md §Compression */
object WireCompression:

  val None = "none"
  val Gzip = "gzip"

  /** Compress `data` using the named algorithm.
   *  Returns `Left` for unsupported/unknown algorithms. */
  def compress(data: Array[Byte], algorithm: String): Either[String, Array[Byte]] =
    algorithm match
      case None => Right(data)
      case Gzip => Right(gzipCompress(data))
      case other => Left(s"Unsupported compression algorithm: '$other'")

  /** Decompress `data` using the named algorithm.
   *  Returns `Left` for unsupported algorithms or corrupt data. */
  def decompress(data: Array[Byte], algorithm: String): Either[WireDecodeError, Array[Byte]] =
    algorithm match
      case None => Right(data)
      case Gzip =>
        scala.util.Try(gzipDecompress(data)).toEither.left.map { e =>
          WireDecodeError.MalformedInput(s"gzip decompression failed: ${e.getMessage}")
        }
      case other =>
        Left(WireDecodeError.MalformedInput(s"Unsupported compression algorithm: '$other'"))

  private def gzipCompress(data: Array[Byte]): Array[Byte] =
    val buf = ByteArrayOutputStream()
    val gz  = GZIPOutputStream(buf)
    gz.write(data)
    gz.close()
    buf.toByteArray

  private def gzipDecompress(data: Array[Byte]): Array[Byte] =
    val in  = GZIPInputStream(ByteArrayInputStream(data))
    val buf = ByteArrayOutputStream()
    val tmp = Array.ofDim[Byte](4096)
    var n   = in.read(tmp)
    while n != -1 do
      buf.write(tmp, 0, n)
      n = in.read(tmp)
    in.close()
    buf.toByteArray

  /** Estimate compression ratio (compressed / original).
   *  Returns 1.0 for empty input or passthrough. */
  def ratio(original: Array[Byte], compressed: Array[Byte]): Double =
    if original.isEmpty then 1.0
    else compressed.length.toDouble / original.length.toDouble
