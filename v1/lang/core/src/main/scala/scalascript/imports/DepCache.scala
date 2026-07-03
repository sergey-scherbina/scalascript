package scalascript.imports

import java.security.MessageDigest

object DepCache:
  final case class CacheKey(scheme: String, coord: String, sha256: Option[String])

  val root: os.Path = os.home / ".cache" / "scalascript" / "deps"

  def pathFor(key: CacheKey, fileName: String, cacheRoot: os.Path = root): os.Path =
    val safeCoord = key.coord.replace('\\', '/').split('/').filter(_.nonEmpty)
    safeCoord.foldLeft(cacheRoot / key.scheme)((p, part) => p / sanitize(part)) / sanitize(fileName)

  def get(key: CacheKey, fileName: String, cacheRoot: os.Path = root): Option[os.Path] =
    val p = pathFor(key, fileName, cacheRoot)
    Option.when(os.exists(p) && verifyIfPinned(p, key.sha256))(p)

  def put(key: CacheKey, fileName: String, bytes: Array[Byte], cacheRoot: os.Path = root): os.Path =
    key.sha256.foreach { expected =>
      val actual = sha256hex(bytes)
      if !actual.equalsIgnoreCase(expected) then
        throw new RuntimeException(s"sha256 mismatch for ${key.scheme}:${key.coord}: expected $expected, got $actual")
    }
    val p = pathFor(key, fileName, cacheRoot)
    os.makeDir.all(p / os.up)
    os.write.over(p, bytes)
    p

  def verifyIfPinned(path: os.Path, sha256: Option[String]): Boolean =
    sha256.forall(expected => sha256hex(os.read.bytes(path)).equalsIgnoreCase(expected))

  def sha256hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .map("%02x".format(_))
      .mkString

  private def sanitize(s: String): String =
    s.replaceAll("[^A-Za-z0-9._@#-]", "_")
