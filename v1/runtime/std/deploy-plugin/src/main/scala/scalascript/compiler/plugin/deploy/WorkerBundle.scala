package scalascript.compiler.plugin.deploy

import java.util.zip.ZipInputStream

/** Worker bundle verification and manifest types.
 *  A bundle is a `.zip` produced by `ssc cluster package`. */

case class BundleManifest(
  bundleVersion:    String,
  target:           String,
  sourceFile:       String,
  codeIdentityHash: String,
  runtimeVersion:   String,
  signature:        Option[String],
  signedBy:         Option[String],
  deps:             List[String],
)

enum BundleVerificationError:
  case SignatureMissing(message: String)
  case SignatureMismatch(expected: String, actual: String)
  case HashMismatch(expected: String, actual: String)
  case MissingDep(dep: String)
  case ManifestMissing
  case SourceFileMissing

case class VerifiedBundle(
  manifest:    BundleManifest,
  sourceBytes: Array[Byte],
  sourceFile:  String,
)

object WorkerBundle:

  /** Parse a zip bundle and verify its source hash and optional HMAC signature. */
  def verify(
    zipBytes:   Array[Byte],
    hmacSecret: Option[Array[Byte]] = None,
    knownDeps:  Set[String]         = Set.empty,
  ): Either[BundleVerificationError, VerifiedBundle] =
    val entries = readZip(zipBytes)
    entries.get("manifest.json") match
      case None => Left(BundleVerificationError.ManifestMissing)
      case Some(manifestBytes) =>
        val manifest = parseManifest(new String(manifestBytes, "UTF-8"))
        entries.get(manifest.sourceFile) match
          case None => Left(BundleVerificationError.SourceFileMissing)
          case Some(sourceBytes) =>
            val actualHash = sha256Hex(sourceBytes)
            if actualHash != manifest.codeIdentityHash then
              Left(BundleVerificationError.HashMismatch(manifest.codeIdentityHash, actualHash))
            else
              val sigCheck: Either[BundleVerificationError, Unit] = hmacSecret match
                case None => Right(())
                case Some(secret) =>
                  manifest.signature match
                    case None => Left(BundleVerificationError.SignatureMissing("bundle has no signature field"))
                    case Some(sig) =>
                      val payload  = sourceBytes ++ stripSigFields(manifestBytes)
                      val expected = hmacSha256Hex(secret, payload)
                      if constantTimeEquals(sig, expected) then Right(())
                      else Left(BundleVerificationError.SignatureMismatch(expected, sig))
              sigCheck match
                case Left(e) => Left(e)
                case Right(_) =>
                  val missingDep = manifest.deps.find(d => !knownDeps.contains(d))
                  missingDep match
                    case Some(dep) => Left(BundleVerificationError.MissingDep(dep))
                    case None      => Right(VerifiedBundle(manifest, sourceBytes, manifest.sourceFile))

  /** Sign a bundle zip: compute HMAC-SHA256 and inject the `signature` field. */
  def sign(zipBytes: Array[Byte], hmacSecret: Array[Byte], keyId: String = ""): Array[Byte] =
    val entries = readZip(zipBytes)
    entries.get("manifest.json") match
      case None => zipBytes
      case Some(mBytes) =>
        val manifest = parseManifest(new String(mBytes, "UTF-8"))
        entries.get(manifest.sourceFile) match
          case None => zipBytes
          case Some(srcBytes) =>
            val payload  = srcBytes ++ stripSigFields(mBytes)
            val sig      = hmacSha256Hex(hmacSecret, payload)
            val newMText = injectSignature(new String(mBytes, "UTF-8"), sig, keyId)
            writeZip(entries.updated("manifest.json", newMText.getBytes("UTF-8")))

  // ── Helpers ────────────────────────────────────────────────────────────────

  private def readZip(bytes: Array[Byte]): Map[String, Array[Byte]] =
    val zis   = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))
    val out   = scala.collection.mutable.Map.empty[String, Array[Byte]]
    var entry = zis.getNextEntry
    while entry != null do
      out(entry.getName) = zis.readAllBytes()
      zis.closeEntry()
      entry = zis.getNextEntry
    zis.close()
    out.toMap

  private def writeZip(entries: Map[String, Array[Byte]]): Array[Byte] =
    val bos = new java.io.ByteArrayOutputStream()
    val zos = new java.util.zip.ZipOutputStream(bos)
    entries.foreach { (name, bytes) =>
      zos.putNextEntry(new java.util.zip.ZipEntry(name))
      zos.write(bytes)
      zos.closeEntry()
    }
    zos.close()
    bos.toByteArray

  private[deploy] def sha256Hex(bytes: Array[Byte]): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(bytes).map(b => "%02x".format(b)).mkString

  private def hmacSha256Hex(secret: Array[Byte], data: Array[Byte]): String =
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"))
    mac.doFinal(data).map(b => "%02x".format(b)).mkString

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then false
    else
      var result = 0
      for i <- 0 until a.length do result |= (a(i) ^ b(i))
      result == 0

  private def stripSigFields(manifestBytes: Array[Byte]): Array[Byte] =
    val text = new String(manifestBytes, "UTF-8")
    // Match optional leading comma+whitespace so injected fields are fully removed
    val p1   = """,?\s*"signature"\s*:\s*"[^"]*"""".r
    val p2   = """,?\s*"signedBy"\s*:\s*"[^"]*"""".r
    val r1   = p1.replaceFirstIn(text, "")
    val r2   = p2.replaceFirstIn(r1, "")
    // Trim trailing whitespace before closing brace to restore original form
    """\s*\}$""".r.replaceFirstIn(r2, "}").getBytes("UTF-8")

  def parseManifest(json: String): BundleManifest =
    def str(key: String): String =
      val pat = (s""""$key"""" + """\s*:\s*"([^"]*)"""").r
      pat.findFirstMatchIn(json).map(_.group(1)).getOrElse("")
    def optStr(key: String): Option[String] =
      val v = str(key); if v.isEmpty then None else Some(v)
    def arrStr(key: String): List[String] =
      val pat = (s""""$key"""" + """\s*:\s*\[([^\]]*)\]""").r
      pat.findFirstMatchIn(json)
        .map(_.group(1).split(",").toList
          .map(_.trim.stripPrefix("\"").stripSuffix("\""))
          .filter(_.nonEmpty))
        .getOrElse(Nil)
    BundleManifest(
      bundleVersion    = str("bundleVersion"),
      target           = str("target"),
      sourceFile       = str("sourceFile"),
      codeIdentityHash = str("hash"),
      runtimeVersion   = str("runtimeVersion"),
      signature        = optStr("signature"),
      signedBy         = optStr("signedBy"),
      deps             = arrStr("deps"),
    )

  private def injectSignature(json: String, sig: String, keyId: String): String =
    val withSig =
      if json.contains("\"signature\"") then
        """"signature"\s*:\s*"[^"]*"""".r.replaceFirstIn(json, s""""signature": "$sig"""")
      else
        json.trim.stripSuffix("}") + s""",\n  "signature": "$sig"\n}"""
    if keyId.nonEmpty then
      if withSig.contains("\"signedBy\"") then
        """"signedBy"\s*:\s*"[^"]*"""".r.replaceFirstIn(withSig, s""""signedBy": "$keyId"""")
      else
        withSig.trim.stripSuffix("}") + s""",\n  "signedBy": "$keyId"\n}"""
    else withSig
