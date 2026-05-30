package scalascript.cli

// JAR packaging for `ssc build`/`ssc package`: fat-JAR + thin-JAR + JVM
// bootstrap-JAR builders and their two embedded entry points (SscJarMain,
// SscThinLauncher). Extracted from Main.scala. All references are either
// fully-qualified, locally imported, or package-level helpers, so no
// top-level imports are needed.

// ─── Fat-JAR entry point ────────────────────────────────────────────────────

/** Entry point written into *fat* JARs by `ssc package --target ssc`.
 *  Reads the `.ssc` source packed at `META-INF/ssc/main.ssc`, writes it to a
 *  temp file, then calls `runCommand` exactly as `ssc run` would.
 *  (Thin-JAR builds use `SscThinLauncher` instead.) */
object SscJarMain:
  def main(argv: Array[String]): Unit =
    val stream = getClass.getClassLoader.getResourceAsStream("META-INF/ssc/main.ssc")
    if stream == null then
      System.err.println("ssc: no embedded main.ssc found in JAR (corrupt build?)")
      System.exit(1)
    val content = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    val tmp = java.nio.file.Files.createTempFile("ssc-jar-", ".ssc")
    tmp.toFile.deleteOnExit()
    java.nio.file.Files.writeString(tmp, content)
    runCommand(tmp.toString :: argv.toList)

// ─── Thin-JAR launcher (Scala, lives in lib/ssc.jar) ────────────────────────

/** Called by {@code SscThinBootstrap} (Java) after the ssc lib is loaded via
 *  URLClassLoader.  Reads the embedded `.sscc` (pre-compiled AST) or `.ssc`
 *  (source fallback) from the thin JAR and runs via {@code runCommand}. */
object SscThinLauncher:
  def main(argv: Array[String]): Unit =
    // Try .sscc first (pre-compiled AST), fall back to .ssc source
    val (bytes, suffix) =
      Option(getClass.getClassLoader.getResourceAsStream("META-INF/ssc/main.sscc"))
        .map(s => (s.readAllBytes(), ".sscc"))
        .orElse(
          Option(getClass.getClassLoader.getResourceAsStream("META-INF/ssc/main.ssc"))
            .map(s => (s.readAllBytes(), ".ssc"))
        )
        .getOrElse {
          System.err.println("ssc: no embedded main.sscc or main.ssc found (corrupt thin JAR?)")
          System.exit(1)
          throw new AssertionError()
        }
    val tmp = java.nio.file.Files.createTempFile("ssc-jar-", suffix)
    tmp.toFile.deleteOnExit()
    java.nio.file.Files.write(tmp, bytes)
    runCommand(tmp.toString :: argv.toList)

// ─── Thin-JAR build ──────────────────────────────────────────────────────────

/** Pack `sscFile` + `SscThinBootstrap*.class` (extracted from the live ssc.jar)
 *  into a minimal thin JAR.  The `.ssc` source is parsed at build time and
 *  embedded as a `.sscc` binary (magic + version + msgpack AST) so that the
 *  runtime can skip markdown/YAML/scalameta parsing entirely.
 *  `SscThinBootstrap` is a pure-Java class that locates the ssc lib at
 *  runtime and delegates via URLClassLoader — no Scala runtime bundled. */
private[cli] def buildThinJar(sscFile: os.Path, outJar: os.Path): Unit =
  import java.util.zip.{ZipEntry, ZipInputStream}
  import java.util.jar.JarOutputStream
  import java.io.{FileOutputStream, FileInputStream}
  import scalascript.ast.SsccFormat
  import scalascript.parser.Parser

  val libRoot = os.Path(System.getProperty("ssc.lib.path", os.pwd.toString)) / "bin" / "lib"
  val sscJar  = libRoot / "ssc.jar"

  // Parse and serialize the AST now so the runtime skips parsing entirely.
  val module    = Parser.parse(os.read(sscFile))
  val ssccBytes = SsccFormat.write(module)

  os.makeDir.all(outJar / os.up)

  val fos  = new FileOutputStream(outJar.toIO)
  val jos  = new JarOutputStream(fos)
  val seen = scala.collection.mutable.HashSet.empty[String]
  try
    // Extract only SscThinBootstrap*.class from ssc.jar (pure Java, no Scala runtime dep)
    if os.exists(sscJar) then
      val zis = new ZipInputStream(new FileInputStream(sscJar.toIO))
      try
        var entry = zis.getNextEntry()
        while entry != null do
          val n = entry.getName
          if !entry.isDirectory && n.contains("SscThinBootstrap") && seen.add(n) then
            jos.putNextEntry(new ZipEntry(n))
            zis.transferTo(jos)
            jos.closeEntry()
          zis.closeEntry()
          entry = zis.getNextEntry()
      finally zis.close()

    // Embed the pre-compiled AST as .sscc (SscThinBootstrap tries this first)
    jos.putNextEntry(new ZipEntry("META-INF/ssc/main.sscc"))
    jos.write(ssccBytes)
    jos.closeEntry()

    val manifest =
      s"""Manifest-Version: 1.0
Main-Class: scalascript.cli.SscThinBootstrap
Ssc-Source: ${sscFile.last}
Ssc-Format: sscc/${SsccFormat.CurrentVersion}
"""
    jos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"))
    jos.write(manifest.getBytes("UTF-8"))
    jos.closeEntry()
  finally
    jos.close()

// ─── JVM bootstrap-JAR build ─────────────────────────────────────────────────

/** Compile `sscFile` via JvmGen → scala-cli `--library`, then pack the compiled
 *  classes together with `SscJvmBootstrap.class` into a thin bootstrap JAR.
 *
 *  The `.sc` temp file is named `<sanitizedName>.sc` so the compiled main class
 *  is predictably `<sanitizedName>_sc` (scala-cli derives the class name from
 *  the filename).  The name is stored in `Ssc-Main-Class` in the manifest so
 *  `SscJvmBootstrap` can find it at runtime without scanning. */
private[cli] def buildJvmBootstrapJar(sscFile: os.Path, name: String, outJar: os.Path): Unit =
  import java.util.zip.{ZipEntry, ZipInputStream}
  import java.util.jar.JarOutputStream
  import java.io.{FileOutputStream, FileInputStream}

  val scalaSource   = expectText(compileViaBackend("jvm", sscFile), "build --target jvm")
  val sanitized     = name.replaceAll("[^a-zA-Z0-9_]", "_")
  val mainClass     = s"${sanitized}_sc"

  val libRoot = os.Path(System.getProperty("ssc.lib.path", os.pwd.toString)) / "bin" / "lib"
  val sscJar  = libRoot / "ssc.jar"

  val tmpDir      = os.temp.dir()
  val tmpSc       = tmpDir / s"$sanitized.sc"
  val compiledJar = tmpDir / "compiled.jar"
  try
    os.write(tmpSc, scalaSource)
    val r = os.proc(
      "scala-cli", "--power", "package", tmpSc,
      "--library", "--server=false", "-o", compiledJar.toString
    ).call(stdout = os.Pipe, stderr = os.Inherit, cwd = tmpDir, check = false)
    if r.exitCode != 0 then System.exit(r.exitCode)

    os.makeDir.all(outJar / os.up)
    val seen = scala.collection.mutable.HashSet.empty[String]
    val jos  = new JarOutputStream(new FileOutputStream(outJar.toIO))
    try
      // SscJvmBootstrap.class from ssc.jar
      if os.exists(sscJar) then
        val zis = new ZipInputStream(new FileInputStream(sscJar.toIO))
        try
          var entry = zis.getNextEntry()
          while entry != null do
            val n = entry.getName
            if !entry.isDirectory && n.contains("SscJvmBootstrap") && seen.add(n) then
              jos.putNextEntry(new ZipEntry(n))
              zis.transferTo(jos)
              jos.closeEntry()
            zis.closeEntry()
            entry = zis.getNextEntry()
        finally zis.close()

      // Compiled app classes from scala-cli --library output
      val zis2 = new ZipInputStream(new FileInputStream(compiledJar.toIO))
      try
        var entry = zis2.getNextEntry()
        while entry != null do
          val n = entry.getName
          if n != "META-INF/MANIFEST.MF" && seen.add(n) then
            jos.putNextEntry(new ZipEntry(n))
            if !entry.isDirectory then zis2.transferTo(jos)
            jos.closeEntry()
          zis2.closeEntry()
          entry = zis2.getNextEntry()
      finally zis2.close()

      val manifest =
        s"""Manifest-Version: 1.0
Main-Class: scalascript.cli.SscJvmBootstrap
Ssc-Main-Class: $mainClass
Ssc-Source: ${sscFile.last}
"""
      jos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"))
      jos.write(manifest.getBytes("UTF-8"))
      jos.closeEntry()
    finally jos.close()
    println(s"→ ${displayPath(outJar)}")
  finally
    os.remove.all(tmpDir)

// ─── Fat-JAR packaging ───────────────────────────────────────────────────────

/** Pack `sscFile` together with the full ssc interpreter runtime into
 *  a self-contained fat JAR at `outJar`.  Running `java -jar outJar`
 *  launches the `.ssc` program via the embedded interpreter.
 *
 *  All JARs from `<ssc.lib.path>/bin/lib/jars/` and `bin/lib/ssc.jar`
 *  are merged; duplicate entries (other than `META-INF/MANIFEST.MF`) are
 *  silently deduplicated (first-seen wins — safe for class files). */
private[cli] def buildFatJar(sscFile: os.Path, outJar: os.Path): Unit =
  import java.util.zip.{ZipEntry, ZipInputStream}
  import java.util.jar.JarOutputStream
  import java.io.{FileOutputStream, FileInputStream}

  val libRoot = os.Path(System.getProperty("ssc.lib.path", os.pwd.toString)) / "bin" / "lib"
  val runtimeJars =
    os.list(libRoot / "jars").filter(_.ext == "jar").sorted.toList :+
    (libRoot / "ssc.jar")

  os.makeDir.all(outJar / os.up)

  val seen = scala.collection.mutable.HashSet.empty[String]
  val fos  = new FileOutputStream(outJar.toIO)
  val jos  = new JarOutputStream(fos)
  try
    // Merge runtime JARs (skip META-INF/MANIFEST.MF — we write our own)
    for jar <- runtimeJars if os.exists(jar) do
      val zis = new ZipInputStream(new FileInputStream(jar.toIO))
      try
        var entry = zis.getNextEntry()
        while entry != null do
          val name = entry.getName
          if name != "META-INF/MANIFEST.MF" && !seen.contains(name) then
            seen += name
            jos.putNextEntry(new ZipEntry(name))
            if !entry.isDirectory then zis.transferTo(jos)
            jos.closeEntry()
          zis.closeEntry()
          entry = zis.getNextEntry()
      finally zis.close()

    // Embed the .ssc source
    jos.putNextEntry(new ZipEntry("META-INF/ssc/main.ssc"))
    jos.write(os.read.bytes(sscFile))
    jos.closeEntry()

    // Write manifest last so Main-Class wins over any runtime manifest
    val manifest =
      s"""Manifest-Version: 1.0
Main-Class: scalascript.cli.SscJarMain
Ssc-Source: ${sscFile.last}
"""
    jos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"))
    jos.write(manifest.getBytes("UTF-8"))
    jos.closeEntry()
  finally
    jos.close()
