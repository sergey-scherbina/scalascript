package scalascript.wallet.vault.encrypted

/** On-disk representation of an encrypted vault (data-only).
 *
 *  Layout:
 *  {{{
 *  {
 *    "version":    1,
 *    "id":         "<uuid>",
 *    "accounts":   [{"id":"…","label":"…","derivationPath":"…"}],
 *    "kdf":        {"m":65536,"t":3,"p":1,"salt":"<hex>"},
 *    "cipher":     {"iv":"<hex>","aad":"<hex>"},
 *    "ciphertext": "<hex>"
 *  }
 *  }}}
 *
 *  The `ciphertext` field is AES-256-GCM(Argon2id(password), plaintext=seed64).
 *  The 64-byte BIP-39 seed is the plaintext; individual signing keys are
 *  derived on demand via HD derivation.
 *
 *  Cross-compiled (JVM + Scala.js).  This `case class` is pure data; the
 *  filesystem `read(path)` / `write(file, path)` lives in JVM-only
 *  `wallet-vault-encrypted/jvm/.../VaultFileIo.scala`.  A future
 *  IndexedDB-backed JS store (deferred, see specs/wallet-spi-scalajs.md
 *  §5) will read / write the same JSON shape from a Scala.js-side
 *  helper. */
case class VaultFile(
  version:    Int,
  id:         String,
  accounts:   Seq[VaultAccount],
  kdfM:       Int,
  kdfT:       Int,
  kdfP:       Int,
  kdfSalt:    Array[Byte],
  iv:         Array[Byte],
  aad:        Array[Byte],
  ciphertext: Array[Byte],
)

case class VaultAccount(
  id:             String,
  label:          String,
  derivationPath: String,
)

/** JSON serialisation for [[VaultFile]] — round-trips through the
 *  in-tree `ujson` dep (cross-compiled).  Pure: no file I/O.  Use the
 *  JVM-only `VaultFileIo.{read,write}` for filesystem persistence. */
object VaultFile:

  def toJson(file: VaultFile): String =
    val json = ujson.Obj(
      "version"    -> ujson.Num(file.version.toDouble),
      "id"         -> ujson.Str(file.id),
      "accounts"   -> ujson.Arr(file.accounts.map(a => ujson.Obj(
        "id"             -> ujson.Str(a.id),
        "label"          -> ujson.Str(a.label),
        "derivationPath" -> ujson.Str(a.derivationPath),
      ))*),
      "kdf"        -> ujson.Obj(
        "m"    -> ujson.Num(file.kdfM.toDouble),
        "t"    -> ujson.Num(file.kdfT.toDouble),
        "p"    -> ujson.Num(file.kdfP.toDouble),
        "salt" -> ujson.Str(hex(file.kdfSalt)),
      ),
      "cipher"     -> ujson.Obj(
        "iv"  -> ujson.Str(hex(file.iv)),
        "aad" -> ujson.Str(hex(file.aad)),
      ),
      "ciphertext" -> ujson.Str(hex(file.ciphertext)),
    )
    ujson.write(json, indent = 2)

  def fromJson(s: String): VaultFile =
    val v        = ujson.read(s)
    val accounts = v("accounts").arr.map { a =>
      VaultAccount(a("id").str, a("label").str, a("derivationPath").str)
    }.toSeq
    val kdf      = v("kdf")
    val cipher   = v("cipher")
    VaultFile(
      version    = v("version").num.toInt,
      id         = v("id").str,
      accounts   = accounts,
      kdfM       = kdf("m").num.toInt,
      kdfT       = kdf("t").num.toInt,
      kdfP       = kdf("p").num.toInt,
      kdfSalt    = unhex(kdf("salt").str),
      iv         = unhex(cipher("iv").str),
      aad        = unhex(cipher("aad").str),
      ciphertext = unhex(v("ciphertext").str),
    )

  private def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString
  private def unhex(s: String): Array[Byte] =
    s.grouped(2).map(h => Integer.parseInt(h, 16).toByte).toArray
