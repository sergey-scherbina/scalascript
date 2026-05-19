package scalascript.artifact

import scalascript.ir.*
import upickle.default.{read, write}

/** Serialise / deserialise `.scjvm` (JVM-backend cached source) artifacts.
 *
 *  A `.scjvm` artifact stores the JVM backend's emitted Scala 3 source for a
 *  single module (no merged transitive-dep code).  `ssc link --backend jvm`
 *  reads one `.scjvm` per module and textually concatenates them — avoiding
 *  per-link re-codegen for modules whose source has not changed since the
 *  artifact was written.
 *
 *  The envelope (`magic`, `abiVersion`) is checked before deserialising the
 *  payload — mismatched artifacts produce a clear error instead of silently
 *  splicing incompatible source into the combined output.
 *
 *  v2.0 — JVM incremental codegen cache. */
object JvmArtifactIO:

  /** Serialise a `ModuleJvmArtifact` to a pretty-printed JSON string.
   *  The result is suitable for writing to a `.scjvm` file. */
  def writeJvm(art: ModuleJvmArtifact): String =
    require(art.magic == ArtifactVersion.magic,
      s"BUG: ModuleJvmArtifact.magic must be '${ArtifactVersion.magic}', got '${art.magic}'")
    require(art.abiVersion == ArtifactVersion.current,
      s"BUG: ModuleJvmArtifact.abiVersion must be '${ArtifactVersion.current}', got '${art.abiVersion}'")
    write(art, indent = 2)

  /** Build + serialise a `ModuleJvmArtifact` from its constituent fields. */
  def writeJvm(
      moduleId:     String,
      pkg:          List[String],
      moduleName:   Option[String],
      sourceHash:   String,
      scalaSource:  String,
      imports:      List[String],
      classBundle:  Option[String]      = None,
      capabilities: List[String]        = Nil,
      sectionHashes: Map[String, String] = Map.empty
  ): String =
    val art = ModuleJvmArtifact(
      magic         = ArtifactVersion.magic,
      abiVersion    = ArtifactVersion.current,
      moduleId      = moduleId,
      pkg           = pkg,
      moduleName    = moduleName,
      sourceHash    = sourceHash,
      scalaSource   = scalaSource,
      imports       = imports,
      classBundle   = classBundle,
      capabilities  = capabilities.sorted,
      sectionHashes = sectionHashes
    )
    writeJvm(art)

  /** Deserialise a `.scjvm` JSON string into a `ModuleJvmArtifact`.
   *
   *  Returns `Left(error message)` if:
   *  - The JSON is malformed.
   *  - The `magic` field does not equal `ArtifactVersion.magic`.
   *  - The `abiVersion` field does not equal `ArtifactVersion.current`.
   *
   *  Returns `Right(artifact)` on success. */
  def readJvm(json: String): Either[String, ModuleJvmArtifact] =
    scala.util.Try(read[ModuleJvmArtifact](json)).toEither.left.map { e =>
      s"Failed to parse .scjvm artifact: ${e.getMessage}"
    }.flatMap { art =>
      checkEnvelope(art.magic, art.abiVersion, ".scjvm").map(_ => art)
    }

  /** Read a `.scjvm` file from `path`.  Convenience wrapper over `readJvm`. */
  def readJvmFile(path: os.Path): Either[String, ModuleJvmArtifact] =
    if !os.exists(path) then Left(s"JVM artifact not found: $path")
    else readJvm(os.read(path))

  /** Write a `.scjvm` file to `path` (creates parent directories). */
  def writeJvmFile(art: ModuleJvmArtifact, path: os.Path): Unit =
    os.makeDir.all(path / os.up)
    os.write.over(path, writeJvm(art))

  /** Write a `.scjvm` file to `path` from constituent fields. */
  def writeJvmFile(
      moduleId:     String,
      pkg:          List[String],
      moduleName:   Option[String],
      sourceHash:   String,
      scalaSource:  String,
      imports:      List[String],
      path:         os.Path,
      classBundle:  Option[String]      = None,
      capabilities: List[String]        = Nil,
      sectionHashes: Map[String, String] = Map.empty
  ): Unit =
    os.makeDir.all(path / os.up)
    os.write.over(path, writeJvm(moduleId, pkg, moduleName, sourceHash, scalaSource, imports, classBundle, capabilities, sectionHashes))

  // ─── Shared runtime artifact (.scjvm-runtime) ────────────────────────────

  /** Serialise a `ModuleJvmRuntimeArtifact` to a pretty-printed JSON string. */
  def writeRuntime(art: ModuleJvmRuntimeArtifact): String =
    require(art.magic == ArtifactVersion.magic,
      s"BUG: ModuleJvmRuntimeArtifact.magic must be '${ArtifactVersion.magic}', got '${art.magic}'")
    require(art.abiVersion == ArtifactVersion.current,
      s"BUG: ModuleJvmRuntimeArtifact.abiVersion must be '${ArtifactVersion.current}', got '${art.abiVersion}'")
    write(art, indent = 2)

  /** Build + serialise a `ModuleJvmRuntimeArtifact` from its constituent fields. */
  def writeRuntime(
      capabilities: List[String],
      sourceHash:   String,
      classBundle:  String
  ): String =
    val art = ModuleJvmRuntimeArtifact(
      magic        = ArtifactVersion.magic,
      abiVersion   = ArtifactVersion.current,
      capabilities = capabilities.sorted,
      sourceHash   = sourceHash,
      classBundle  = classBundle
    )
    writeRuntime(art)

  /** Deserialise a `.scjvm-runtime` JSON string into a `ModuleJvmRuntimeArtifact`. */
  def readRuntime(json: String): Either[String, ModuleJvmRuntimeArtifact] =
    scala.util.Try(read[ModuleJvmRuntimeArtifact](json)).toEither.left.map { e =>
      s"Failed to parse .scjvm-runtime artifact: ${e.getMessage}"
    }.flatMap { art =>
      checkEnvelope(art.magic, art.abiVersion, ".scjvm-runtime").map(_ => art)
    }

  /** Read a `.scjvm-runtime` file from `path`. */
  def readRuntimeFile(path: os.Path): Either[String, ModuleJvmRuntimeArtifact] =
    if !os.exists(path) then Left(s"JVM runtime artifact not found: $path")
    else readRuntime(os.read(path))

  /** Write a `.scjvm-runtime` file to `path` (creates parent directories). */
  def writeRuntimeFile(art: ModuleJvmRuntimeArtifact, path: os.Path): Unit =
    os.makeDir.all(path / os.up)
    os.write.over(path, writeRuntime(art))

  /** Write a `.scjvm-runtime` file to `path` from constituent fields. */
  def writeRuntimeFile(
      capabilities: List[String],
      sourceHash:   String,
      classBundle:  String,
      path:         os.Path
  ): Unit =
    os.makeDir.all(path / os.up)
    os.write.over(path, writeRuntime(capabilities, sourceHash, classBundle))

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
