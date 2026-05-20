package scalascript.wallet.vault.encrypted

import java.nio.file.{Files, Path}

/** JVM-only filesystem I/O for [[VaultFile]].  Reads / writes the
 *  same JSON shape produced by `VaultFile.toJson` / `fromJson` from
 *  shared code.  The Scala.js side has no `java.nio.file`; a future
 *  IndexedDB-backed store will live next to this in `js/`. */
object VaultFileIo:

  def write(file: VaultFile, path: Path): Unit =
    Files.writeString(path, VaultFile.toJson(file))

  def read(path: Path): VaultFile =
    VaultFile.fromJson(Files.readString(path))
