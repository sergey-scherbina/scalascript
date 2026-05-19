package scalascript.artifact

import scalascript.ir.*
import upickle.default.{read, write}

/** Serialise / deserialise `.scjs` (JS-backend cached source) artifacts.
 *
 *  A `.scjs` artifact stores the JS backend's emitted JavaScript source for a
 *  single module (no merged transitive-dep code).  `ssc link --backend js`
 *  reads one `.scjs` per module and textually concatenates them — avoiding
 *  per-link re-codegen for modules whose source has not changed since the
 *  artifact was written.
 *
 *  The envelope (`magic`, `abiVersion`) is checked before deserialising the
 *  payload — mismatched artifacts produce a clear error instead of silently
 *  splicing incompatible source into the combined output.
 *
 *  v2.0 — JS incremental codegen cache. */
object JsArtifactIO:

  /** Serialise a `ModuleJsArtifact` to a pretty-printed JSON string.
   *  The result is suitable for writing to a `.scjs` file. */
  def writeJs(art: ModuleJsArtifact): String =
    require(art.magic == ArtifactVersion.magic,
      s"BUG: ModuleJsArtifact.magic must be '${ArtifactVersion.magic}', got '${art.magic}'")
    require(art.abiVersion == ArtifactVersion.current,
      s"BUG: ModuleJsArtifact.abiVersion must be '${ArtifactVersion.current}', got '${art.abiVersion}'")
    write(art, indent = 2)

  /** Build + serialise a `ModuleJsArtifact` from its constituent fields. */
  def writeJs(
      moduleId:    String,
      pkg:         List[String],
      moduleName:  Option[String],
      sourceHash:  String,
      jsSource:    String,
      imports:     List[String]
  ): String =
    val art = ModuleJsArtifact(
      magic       = ArtifactVersion.magic,
      abiVersion  = ArtifactVersion.current,
      moduleId    = moduleId,
      pkg         = pkg,
      moduleName  = moduleName,
      sourceHash  = sourceHash,
      jsSource    = jsSource,
      imports     = imports
    )
    writeJs(art)

  /** Deserialise a `.scjs` JSON string into a `ModuleJsArtifact`.
   *
   *  Returns `Left(error message)` if:
   *  - The JSON is malformed.
   *  - The `magic` field does not equal `ArtifactVersion.magic`.
   *  - The `abiVersion` field does not equal `ArtifactVersion.current`.
   *
   *  Returns `Right(artifact)` on success. */
  def readJs(json: String): Either[String, ModuleJsArtifact] =
    scala.util.Try(read[ModuleJsArtifact](json)).toEither.left.map { e =>
      s"Failed to parse .scjs artifact: ${e.getMessage}"
    }.flatMap { art =>
      checkEnvelope(art.magic, art.abiVersion, ".scjs").map(_ => art)
    }

  /** Read a `.scjs` file from `path`.  Convenience wrapper over `readJs`. */
  def readJsFile(path: os.Path): Either[String, ModuleJsArtifact] =
    if !os.exists(path) then Left(s"JS artifact not found: $path")
    else readJs(os.read(path))

  /** Write a `.scjs` file to `path` (creates parent directories). */
  def writeJsFile(art: ModuleJsArtifact, path: os.Path): Unit =
    os.makeDir.all(path / os.up)
    os.write.over(path, writeJs(art))

  /** Write a `.scjs` file to `path` from constituent fields. */
  def writeJsFile(
      moduleId:    String,
      pkg:         List[String],
      moduleName:  Option[String],
      sourceHash:  String,
      jsSource:    String,
      imports:     List[String],
      path:        os.Path
  ): Unit =
    os.makeDir.all(path / os.up)
    os.write.over(path, writeJs(moduleId, pkg, moduleName, sourceHash, jsSource, imports))

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
