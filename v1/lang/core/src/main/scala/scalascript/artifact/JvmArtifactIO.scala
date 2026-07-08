package scalascript.artifact

import scalascript.ir.*
import upickle.default.{read, write, writeBinary, readBinary}

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

  /** Cache key for JVM generated Scala.
   *
   *  Bump this when a JVM backend/runtime codegen change can alter
   *  `scalaSource` without changing the `.ssc` source bytes.  This is not the
   *  artifact ABI: old artifacts should remain readable, then be treated as
   *  stale by `ModuleGraph.isJvmStale`.
   */
  val CurrentCodegenVersion: String = "jvm-codegen-2026-07-08-1"

  def hasCurrentCodegenVersion(art: ModuleJvmArtifact): Boolean =
    art.codegenVersion == CurrentCodegenVersion

  /** Serialise a `ModuleJvmArtifact` to a pretty-printed JSON string.
   *  Use `writeJvmFile` to write to disk — it uses the binary MessagePack format. */
  def writeJvm(art: ModuleJvmArtifact): String =
    require(art.magic == ArtifactVersion.magic,
      s"BUG: ModuleJvmArtifact.magic must be '${ArtifactVersion.magic}', got '${art.magic}'")
    require(art.abiVersion == ArtifactVersion.current,
      s"BUG: ModuleJvmArtifact.abiVersion must be '${ArtifactVersion.current}', got '${art.abiVersion}'")
    write(art, indent = 2)

  /** Build + serialise a `ModuleJvmArtifact` from its constituent fields to pretty-printed JSON. */
  def writeJvm(
      moduleId:     String,
      pkg:          List[String],
      moduleName:   Option[String],
      sourceHash:   String,
      scalaSource:  String,
      imports:      List[String],
      classBundle:  Option[String]      = None,
      capabilities: List[String]        = Nil,
      sectionHashes: Map[String, String] = Map.empty,
      lineMap:       Map[String, Int]    = Map.empty
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
      codegenVersion = CurrentCodegenVersion,
      classBundle   = classBundle,
      capabilities  = capabilities.sorted,
      sectionHashes = sectionHashes,
      lineMap       = lineMap
    )
    writeJvm(art)

  /** Deserialise a `.scjvm` artifact from bytes (MessagePack) or String (legacy JSON).
   *
   *  Returns `Left(error message)` if:
   *  - The data is malformed.
   *  - The `magic` field does not equal `ArtifactVersion.magic`.
   *  - The `abiVersion` field does not equal `ArtifactVersion.current`.
   *
   *  Returns `Right(artifact)` on success. */
  def readJvm(data: Array[Byte]): Either[String, ModuleJvmArtifact] =
    val tryParse: scala.util.Try[ModuleJvmArtifact] =
      if data.nonEmpty && data(0) == '{'.toByte
      then scala.util.Try(read[ModuleJvmArtifact](new String(data, "UTF-8")))
      else scala.util.Try(readBinary[ModuleJvmArtifact](data))
    tryParse.toEither.left.map { e =>
      s"Failed to parse .scjvm artifact: ${e.getMessage}"
    }.flatMap { art =>
      checkEnvelope(art.magic, art.abiVersion, ".scjvm").map(_ => art)
    }

  /** Deserialise a `.scjvm` JSON string (legacy format — kept for backward compat). */
  def readJvm(json: String): Either[String, ModuleJvmArtifact] =
    readJvm(json.getBytes("UTF-8"))

  /** Read a `.scjvm` file from `path`.  Convenience wrapper over `readJvm`. */
  def readJvmFile(path: os.Path): Either[String, ModuleJvmArtifact] =
    if !os.exists(path) then Left(s"JVM artifact not found: $path")
    else readJvm(os.read.bytes(path))

  /** Write a `.scjvm` file to `path` in MessagePack binary format (creates parent directories). */
  def writeJvmFile(art: ModuleJvmArtifact, path: os.Path): Unit =
    os.makeDir.all(path / os.up)
    os.write.over(path, writeBinary(art))

  /** Write a `.scjvm` file to `path` from constituent fields in MessagePack binary format. */
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
      sectionHashes: Map[String, String] = Map.empty,
      lineMap:       Map[String, Int]    = Map.empty
  ): Unit =
    val art = ModuleJvmArtifact(
      magic = ArtifactVersion.magic, abiVersion = ArtifactVersion.current,
      moduleId = moduleId, pkg = pkg, moduleName = moduleName,
      sourceHash = sourceHash, scalaSource = scalaSource, imports = imports,
      codegenVersion = CurrentCodegenVersion,
      classBundle = classBundle, capabilities = capabilities.sorted,
      sectionHashes = sectionHashes, lineMap = lineMap
    )
    os.makeDir.all(path / os.up)
    os.write.over(path, writeBinary(art))

  // ─── Shared runtime artifact (.scjvm-runtime) ────────────────────────────

  /** Serialise a `ModuleJvmRuntimeArtifact` to a pretty-printed JSON string.
   *  Use `writeRuntimeFile` to write to disk — it uses binary MessagePack format. */
  def writeRuntime(art: ModuleJvmRuntimeArtifact): String =
    require(art.magic == ArtifactVersion.magic,
      s"BUG: ModuleJvmRuntimeArtifact.magic must be '${ArtifactVersion.magic}', got '${art.magic}'")
    require(art.abiVersion == ArtifactVersion.current,
      s"BUG: ModuleJvmRuntimeArtifact.abiVersion must be '${ArtifactVersion.current}', got '${art.abiVersion}'")
    write(art, indent = 2)

  /** Build + serialise a `ModuleJvmRuntimeArtifact` from its constituent fields to pretty-printed JSON. */
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

  /** Deserialise a `.scjvm-runtime` artifact from bytes (MessagePack) or String (legacy JSON). */
  def readRuntime(data: Array[Byte]): Either[String, ModuleJvmRuntimeArtifact] =
    val tryParse: scala.util.Try[ModuleJvmRuntimeArtifact] =
      if data.nonEmpty && data(0) == '{'.toByte
      then scala.util.Try(read[ModuleJvmRuntimeArtifact](new String(data, "UTF-8")))
      else scala.util.Try(readBinary[ModuleJvmRuntimeArtifact](data))
    tryParse.toEither.left.map { e =>
      s"Failed to parse .scjvm-runtime artifact: ${e.getMessage}"
    }.flatMap { art =>
      checkEnvelope(art.magic, art.abiVersion, ".scjvm-runtime").map(_ => art)
    }

  /** Deserialise a `.scjvm-runtime` JSON string (legacy format — kept for backward compat). */
  def readRuntime(json: String): Either[String, ModuleJvmRuntimeArtifact] =
    readRuntime(json.getBytes("UTF-8"))

  /** Read a `.scjvm-runtime` file from `path`. */
  def readRuntimeFile(path: os.Path): Either[String, ModuleJvmRuntimeArtifact] =
    if !os.exists(path) then Left(s"JVM runtime artifact not found: $path")
    else readRuntime(os.read.bytes(path))

  /** Write a `.scjvm-runtime` file to `path` in MessagePack binary format (creates parent directories). */
  def writeRuntimeFile(art: ModuleJvmRuntimeArtifact, path: os.Path): Unit =
    os.makeDir.all(path / os.up)
    os.write.over(path, writeBinary(art))

  /** Write a `.scjvm-runtime` file to `path` from constituent fields in MessagePack binary format. */
  def writeRuntimeFile(
      capabilities: List[String],
      sourceHash:   String,
      classBundle:  String,
      path:         os.Path
  ): Unit =
    val art = ModuleJvmRuntimeArtifact(
      magic = ArtifactVersion.magic, abiVersion = ArtifactVersion.current,
      capabilities = capabilities.sorted, sourceHash = sourceHash, classBundle = classBundle
    )
    os.makeDir.all(path / os.up)
    os.write.over(path, writeBinary(art))

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
