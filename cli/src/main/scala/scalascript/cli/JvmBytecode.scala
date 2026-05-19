package scalascript.cli

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.Base64
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}
import java.util.jar.{JarEntry, JarOutputStream, Manifest as JarManifest, Attributes}

/** Helpers for the v2.0 Phase 2 bytecode-level JVM linker.
 *
 *  Three concerns live here:
 *
 *   1. **Compiling Scala source to `.class` files** via `scala-cli`, then
 *      packing those classes into a base64-encoded ZIP suitable for the
 *      `ModuleJvmArtifact.classBundle` field.
 *
 *   2. **Extracting a `classBundle`** back to a directory of `.class` files,
 *      typically into a temp dir used as scala-cli's classpath when
 *      compiling a downstream module.
 *
 *   3. **Packing one-or-more class bundles into a JAR** for `ssc link
 *      --backend jvm --bytecode -o out.jar`.  Duplicate entries (same FQN
 *      across modules — e.g. the shared runtime preamble's `_handle`) are
 *      deduplicated, first-write-wins.
 *
 *  All temp dirs created here are cleaned by the caller via `try/finally`.
 */
object JvmBytecode:

  // ─── scala-cli discovery ─────────────────────────────────────────────────

  /** True when `scala-cli` is invocable on PATH and returns 0 from
   *  `scala-cli --version`. */
  def scalaCliAvailable: Boolean =
    scala.util.Try {
      os.proc("scala-cli", "--version")
        .call(check = false, stderr = os.Pipe, stdout = os.Pipe)
    }.toOption.exists(_.exitCode == 0)

  /** Diagnostic message for the failure path — printed verbatim when the
   *  user asked for `--bytecode` but scala-cli isn't available. */
  val scalaCliMissingMessage: String =
    "--bytecode requires `scala-cli` on PATH.\n" +
    "Install it from https://scala-cli.virtuslab.org/install or " +
    "rerun without --bytecode to produce source-only `.scjvm` artifacts."

  // ─── Compile Scala source → .class files → base64 ZIP ────────────────────

  /** Compile `scalaSource` via scala-cli into a fresh temp dir, walk the
   *  resulting `.class` outputs, and return them packed as a base64-encoded
   *  ZIP suitable for `ModuleJvmArtifact.classBundle`.
   *
   *  @param scalaSource    The full Scala 3 source emitted by `JvmGen.generate`.
   *                        Must include any `//> using` directives needed for
   *                        compilation; the source is fed verbatim to scala-cli.
   *  @param classpathDirs  Extra directories of `.class` files to add to the
   *                        compile-time classpath — used to wire transitive
   *                        deps' compiled classes when building a downstream
   *                        module.  Each directory is passed via `--jar`.
   *
   *  @return Right(base64-zip) on success; Left(diagnostic) on scala-cli
   *          failure (with stdout+stderr captured for the caller to print).
   */
  def compileAndPack(
      scalaSource:   String,
      classpathDirs: List[os.Path] = Nil,
      scriptName:    String        = "Main"
  ): Either[String, String] =
    val workDir = os.temp.dir(prefix = "ssc-bytecode-")
    try
      // Write the Scala source as a `.sc` script.  scala-cli treats `.sc`
      // and `.scala` differently; `.sc` wraps top-level statements in an
      // enclosing object.  The file's base name becomes the generated
      // wrapper class name (`<scriptName>_sc`), so giving each module a
      // unique base name prevents the per-module script wrappers from
      // colliding when packed into a single JAR.
      val safeName = scriptName.replaceAll("[^A-Za-z0-9_]", "_") match
        case ""   => "Main"
        case s    => if s.head.isDigit then "M" + s else s
      val srcFile = workDir / s"$safeName.sc"
      os.write(srcFile, scalaSource)

      val outDir = workDir / "out"
      os.makeDir.all(outDir)

      val baseArgs: Seq[os.Shellable] = Seq(
        "scala-cli",
        "compile",
        srcFile.toString,
        "--compilation-output", outDir.toString,
        // `--server=false` disables Bloop daemon reuse across invocations.
        // Without it, parallel sbt test runs share a single Bloop daemon
        // that serialises compile requests; under load the daemon's
        // socket queue stalls and a compile hangs indefinitely (observed
        // in `cli/testOnly *V2Artifact* *JvmBytecode*` when both suites
        // hit scala-cli concurrently).  Each invocation pays a ~1 s
        // JVM-start tax but the parallel-safe property is worth it.
        "--server=false"
      )
      val jarArgs: Seq[os.Shellable] = classpathDirs.flatMap(d =>
        Seq[os.Shellable]("--jar", d.toString)
      )

      val res = os.proc(baseArgs ++ jarArgs).call(
        check  = false,
        stderr = os.Pipe,
        stdout = os.Pipe
      )
      if res.exitCode != 0 then
        Left(
          s"scala-cli compile failed (exit ${res.exitCode}):\n" +
          s"stdout:\n${res.out.text()}\nstderr:\n${res.err.text()}"
        )
      else
        // Walk outDir for *.class AND *.tasty files and pack them into a
        // ZIP whose entries are FQN-shaped (`pkg/sub/Name.{class,tasty}`).
        // Downstream modules' scala-cli invocations need the `.tasty`
        // files to resolve cross-module references; the linker strips
        // them when assembling the final runtime JAR.
        val classFiles = collectClassFiles(outDir)
        if classFiles.isEmpty then
          Left(s"scala-cli compile produced no .class files in $outDir")
        else
          val allFiles = collectCompileOutputs(outDir)
          val zipBytes = packAsZip(outDir, allFiles)
          Right(Base64.getEncoder.encodeToString(zipBytes))
    finally
      scala.util.Try(os.remove.all(workDir))

  /** Compile a shared runtime source — produced by `JvmGen.generateRuntime`
   *  with a `package _ssc_runtime` wrapper — via scala-cli, then pack the
   *  resulting `.class` + `.tasty` files as a base64-encoded ZIP suitable
   *  for `ModuleJvmRuntimeArtifact.classBundle`.
   *
   *  The runtime source is written as a `.scala` file (NOT `.sc`) because
   *  it carries a package declaration; scala-cli's `.sc` script wrapper
   *  would conflict with the `package _ssc_runtime` block.
   *
   *  @return Right(base64-zip) on success; Left(diagnostic) on failure.
   *
   *  v2.0 Phase 2 — split-runtime shared classBundle. */
  def compileRuntimeAndPack(
      runtimeSource: String
  ): Either[String, String] =
    val workDir = os.temp.dir(prefix = "ssc-bytecode-runtime-")
    try
      val srcFile = workDir / "_ssc_runtime.scala"
      os.write(srcFile, runtimeSource)

      val outDir = workDir / "out"
      os.makeDir.all(outDir)

      val args: Seq[os.Shellable] = Seq(
        "scala-cli",
        "compile",
        srcFile.toString,
        "--compilation-output", outDir.toString,
        "--server=false"
      )
      val res = os.proc(args).call(check = false, stderr = os.Pipe, stdout = os.Pipe)
      if res.exitCode != 0 then
        Left(
          s"scala-cli compile of shared runtime failed (exit ${res.exitCode}):\n" +
          s"stdout:\n${res.out.text()}\nstderr:\n${res.err.text()}"
        )
      else
        val classFiles = collectClassFiles(outDir)
        if classFiles.isEmpty then
          Left(s"scala-cli compile produced no .class files in $outDir")
        else
          val allFiles = collectCompileOutputs(outDir)
          val zipBytes = packAsZip(outDir, allFiles)
          Right(Base64.getEncoder.encodeToString(zipBytes))
    finally
      scala.util.Try(os.remove.all(workDir))

  /** Walk `root` recursively for `.class` files.  Returned paths are
   *  absolute; callers compute their entry name as `relativeTo(root)`. */
  def collectClassFiles(root: os.Path): List[os.Path] =
    if !os.exists(root) then Nil
    else
      os.walk(root).filter(p => os.isFile(p) && p.last.endsWith(".class")).toList

  /** Walk `root` recursively for the scala-cli outputs we want to ship
   *  in a class bundle — both `.class` and `.tasty` files.
   *
   *  The `.tasty` files are required at COMPILE time by downstream modules
   *  (otherwise scala-cli fails with "Loading Scala 3 binary from
   *  X.class.  It should have been loaded from `.tasty`").  The linker
   *  strips them when assembling the final runtime JAR. */
  def collectCompileOutputs(root: os.Path): List[os.Path] =
    if !os.exists(root) then Nil
    else os.walk(root).filter { p =>
      os.isFile(p) && (p.last.endsWith(".class") || p.last.endsWith(".tasty"))
    }.toList

  /** Pack a list of `.class` file paths (under `root`) into an in-memory ZIP
   *  whose entries are `pkg/sub/Name.class` keyed on `path.relativeTo(root)`. */
  def packAsZip(root: os.Path, files: List[os.Path]): Array[Byte] =
    val baos = new ByteArrayOutputStream()
    val zos  = new ZipOutputStream(baos)
    try
      // Sort for deterministic output — important so `sourceHash` consumers
      // and round-trip equality checks behave predictably.
      for f <- files.sortBy(_.toString) do
        val entryName = f.relativeTo(root).toString.replace('\\', '/')
        val entry     = new ZipEntry(entryName)
        zos.putNextEntry(entry)
        zos.write(os.read.bytes(f))
        zos.closeEntry()
    finally zos.close()
    baos.toByteArray

  // ─── Extract base64 ZIP → directory of .class files ──────────────────────

  /** Decode a base64-encoded class-bundle ZIP and unpack its entries under
   *  `dest`.  Creates `dest` (and any parent dirs) if missing. */
  def extractBundleTo(base64Zip: String, dest: os.Path): Unit =
    os.makeDir.all(dest)
    val bytes = Base64.getDecoder.decode(base64Zip)
    val zis   = new ZipInputStream(new ByteArrayInputStream(bytes))
    try
      var entry = zis.getNextEntry
      while entry != null do
        if !entry.isDirectory then
          val target = dest / os.RelPath(entry.getName)
          os.makeDir.all(target / os.up)
          val out = new ByteArrayOutputStream()
          val buf = new Array[Byte](8192)
          var n   = zis.read(buf)
          while n > 0 do
            out.write(buf, 0, n)
            n = zis.read(buf)
          os.write.over(target, out.toByteArray)
        zis.closeEntry()
        entry = zis.getNextEntry
    finally zis.close()

  // ─── Pack multiple class bundles into a single JAR ───────────────────────

  /** Pack the union of all `.class` entries across `bundles` into a JAR at
   *  `outJar`.  Duplicate entry names (same FQN appearing in two bundles —
   *  typically because the per-module emitter ships the shared runtime
   *  preamble with each module) are deduplicated first-write-wins; the
   *  caller may optionally collect a list of skipped duplicates via the
   *  returned tuple's second element.
   *
   *  @param bundles  ordered list of (moduleId, base64Zip) pairs.  Order
   *                  matters: when two bundles ship the same FQN the one
   *                  that appears first wins.
   *  @param outJar   absolute path where the JAR will be written.
   *  @return         (numUniqueClasses, duplicateEntries) where
   *                  duplicateEntries is a list of (entryName, losingModuleId)
   *                  pairs for diagnostic purposes.
   */
  def packBundlesAsJar(
      bundles: List[(String, String)],
      outJar:  os.Path
  ): (Int, List[(String, String)]) =
    os.makeDir.all(outJar / os.up)

    // Stage 1: extract every bundle into a per-module subdirectory so we can
    // walk them and assemble the JAR in a deterministic order.
    val workDir = os.temp.dir(prefix = "ssc-jar-build-")
    val duplicates = scala.collection.mutable.ListBuffer.empty[(String, String)]
    try
      // entryName → (moduleId, sourcePath) for the FIRST module to claim it.
      val winners = scala.collection.mutable.LinkedHashMap.empty[String, (String, os.Path)]

      for (moduleId, base64Zip) <- bundles do
        // Sanitise moduleId into a safe directory name (FQN-shaped IDs
        // contain dots; underscore-mangling avoids surprising the FS).
        val safeId = moduleId.replaceAll("[^A-Za-z0-9_.-]", "_")
        val modDir = workDir / safeId
        extractBundleTo(base64Zip, modDir)
        // The bundle may also carry `.tasty` files (needed when compiling
        // downstream modules); strip them here so they don't bloat the
        // final runtime JAR — `java -cp out.jar` only needs `.class`.
        for classFile <- collectClassFiles(modDir) do
          val entryName = classFile.relativeTo(modDir).toString.replace('\\', '/')
          if winners.contains(entryName) then
            duplicates += (entryName -> moduleId)
          else
            winners(entryName) = (moduleId, classFile)

      // Stage 2: write the JAR.  Add an empty manifest so `java -jar` still
      // works when callers later set Main-Class via `jar uf` (out of scope
      // for the MVP; we just need a structurally valid JAR).
      val manifest = new JarManifest()
      manifest.getMainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
      val fos = java.nio.file.Files.newOutputStream(outJar.toNIO)
      val jos = new JarOutputStream(fos, manifest)
      try
        for (entryName, (_, sourcePath)) <- winners do
          val entry = new JarEntry(entryName)
          jos.putNextEntry(entry)
          jos.write(os.read.bytes(sourcePath))
          jos.closeEntry()
      finally jos.close()

      (winners.size, duplicates.toList)
    finally
      scala.util.Try(os.remove.all(workDir))
