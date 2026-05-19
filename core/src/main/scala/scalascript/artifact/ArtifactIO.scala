package scalascript.artifact

import scalascript.ir.*
import upickle.default.{read, write}

/** Serialise / deserialise `.scim` (interface) and `.scir` (IR) artifacts.
 *
 *  Every artifact is a JSON document.  Before deserialising the payload the
 *  reader validates the `magic` and `abiVersion` fields so mismatched
 *  artifacts are detected early with a clear error message.
 *
 *  v2.0 / Stage 2 — artifact I/O.
 */
object ArtifactIO:

  // ─── .scim — module interface ────────────────────────────────────────────

  /** Serialise a `ModuleInterface` to a pretty-printed JSON string.
   *  The result is suitable for writing to a `.scim` file. */
  def writeInterface(iface: ModuleInterface): String =
    require(iface.magic == ArtifactVersion.magic,
      s"BUG: ModuleInterface.magic must be '${ArtifactVersion.magic}', got '${iface.magic}'")
    require(iface.abiVersion == ArtifactVersion.current,
      s"BUG: ModuleInterface.abiVersion must be '${ArtifactVersion.current}', got '${iface.abiVersion}'")
    write(iface, indent = 2)

  /** Deserialise a `.scim` JSON string into a `ModuleInterface`.
   *
   *  Returns `Left(error message)` if:
   *  - The JSON is malformed.
   *  - The `magic` field does not equal `ArtifactVersion.magic`.
   *  - The `abiVersion` field does not equal `ArtifactVersion.current`.
   *
   *  Returns `Right(interface)` on success.
   */
  def readInterface(json: String): Either[String, ModuleInterface] =
    scala.util.Try(read[ModuleInterface](json)).toEither.left.map { e =>
      s"Failed to parse .scim artifact: ${e.getMessage}"
    }.flatMap { iface =>
      checkEnvelope(iface.magic, iface.abiVersion, ".scim")
        .map(_ => iface)
    }

  /** Read a `.scim` file from `path`.  Convenience wrapper over `readInterface`. */
  def readInterfaceFile(path: os.Path): Either[String, ModuleInterface] =
    if !os.exists(path) then Left(s"Interface artifact not found: $path")
    else readInterface(os.read(path))

  /** Write a `.scim` file to `path` (creates parent directories). */
  def writeInterfaceFile(iface: ModuleInterface, path: os.Path): Unit =
    os.makeDir.all(path / os.up)
    os.write.over(path, writeInterface(iface))

  // ─── .scir — module IR artifact ──────────────────────────────────────────

  /** Serialise a `(NormalizedModule, pkg, moduleName, sourceHash)` tuple
   *  into a `ModuleIrArtifact` JSON string suitable for writing to a `.scir` file. */
  def writeIr(
      nm:         scalascript.ir.NormalizedModule,
      pkg:        List[String],
      moduleName: Option[String],
      sourceHash: String
  ): String =
    val bodyJson = write(nm)
    val artifact = ModuleIrArtifact(
      magic      = ArtifactVersion.magic,
      abiVersion = ArtifactVersion.current,
      pkg        = pkg,
      moduleName = moduleName,
      sourceHash = sourceHash,
      body       = bodyJson
    )
    write(artifact, indent = 2)

  /** Deserialise a `.scir` JSON string.
   *
   *  Returns `Left(error)` on version mismatch or parse failure.
   *  Returns `Right((NormalizedModule, pkg, moduleName, sourceHash))` on success.
   */
  def readIr(json: String): Either[String, (scalascript.ir.NormalizedModule, List[String], Option[String], String)] =
    scala.util.Try(read[ModuleIrArtifact](json)).toEither.left.map { e =>
      s"Failed to parse .scir artifact: ${e.getMessage}"
    }.flatMap { artifact =>
      checkEnvelope(artifact.magic, artifact.abiVersion, ".scir").flatMap { _ =>
        scala.util.Try(read[scalascript.ir.NormalizedModule](artifact.body)).toEither.left.map { e =>
          s"Failed to parse NormalizedModule body in .scir: ${e.getMessage}"
        }.map { nm =>
          (nm, artifact.pkg, artifact.moduleName, artifact.sourceHash)
        }
      }
    }

  /** Read a `.scir` file from `path`. */
  def readIrFile(path: os.Path): Either[String, (scalascript.ir.NormalizedModule, List[String], Option[String], String)] =
    if !os.exists(path) then Left(s"IR artifact not found: $path")
    else readIr(os.read(path))

  /** Write a `.scir` file to `path` (creates parent directories). */
  def writeIrFile(
      nm:         scalascript.ir.NormalizedModule,
      pkg:        List[String],
      moduleName: Option[String],
      sourceHash: String,
      path:       os.Path
  ): Unit =
    os.makeDir.all(path / os.up)
    os.write.over(path, writeIr(nm, pkg, moduleName, sourceHash))

  // ─── Envelope validation ─────────────────────────────────────────────────

  private def checkEnvelope(magic: String, abiVersion: String, ext: String): Either[String, Unit] =
    if magic != ArtifactVersion.magic then
      Left(
        s"Invalid $ext artifact: expected magic '${ArtifactVersion.magic}', got '$magic'.\n" +
        s"This file may not be a ScalaScript artifact."
      )
    else if abiVersion != ArtifactVersion.current then
      Left(
        s"Incompatible $ext artifact ABI: expected version '${ArtifactVersion.current}', " +
        s"got '$abiVersion'.\n" +
        s"Recompile the module with the current compiler to regenerate the artifact."
      )
    else
      Right(())
