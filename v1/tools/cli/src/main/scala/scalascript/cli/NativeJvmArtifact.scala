package scalascript.cli

import java.io.{BufferedOutputStream, File, FileOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.{CRC32, ZipEntry, ZipFile, ZipOutputStream}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Deterministic self-contained JAR writer for the native CoreIR→ASM lane. */
private[cli] object NativeJvmArtifact:
  private val EntryClass = "ssc/gen/Entry.class"
  private val ServicePrefix = "META-INF/services/"
  private val FixedZipTime = 315532800000L // 1980-01-01T00:00:00Z (DOS epoch)

  private val RuntimePrefixes = List(
    "scala-library-",
    "scala3-library_3-",
    "scalascript-v2-core_",
    "scalascript-v2-native-plugin-spi_",
    "scalascript-v2-native-host-plugin_",
    "scalascript-v2-native-crypto-plugin_",
    "scalascript-v2-native-os-plugin_",
    "scalascript-v2-native-fs-plugin_",
    "scalascript-v2-native-json-plugin_",
    "scalascript-v2-native-http-plugin_",
    "scalascript-v2-native-ui-plugin_",
    "scalascript-v2-native-state-effect-plugin_",
    "ujson_3-",
    "upickle-core_3-",
    "geny_3-",
  )

  private val RequiredPrefixes = List(
    "scala-library-",
    "scala3-library_3-",
    "scalascript-v2-core_",
    "scalascript-v2-native-plugin-spi_",
    "scalascript-v2-native-host-plugin_",
  )

  def write(
      program: _root_.ssc.Program,
      sources: List[File],
      runtimeDir: File,
      output: File): Unit =
    if !runtimeDir.isDirectory then
      throw new IllegalStateException(s"staged standard runtime not found: ${runtimeDir.getPath}")

    val runtimeJars = Option(runtimeDir.listFiles()).toList.flatten
      .filter(f => f.isFile && f.getName.endsWith(".jar"))
      .filter(f => RuntimePrefixes.exists(f.getName.startsWith))
      .sortBy(_.getName)
    RequiredPrefixes.foreach { prefix =>
      if !runtimeJars.exists(_.getName.startsWith(prefix)) then
        throw new IllegalStateException(s"staged standard runtime is missing $prefix*.jar")
    }

    val entries = mutable.TreeMap.empty[String, (Array[Byte], String)]
    val services = mutable.TreeMap.empty[String, mutable.TreeSet[String]]

    def add(name0: String, bytes: Array[Byte], origin: String): Unit =
      val name = name0.replace('\\', '/')
      entries.get(name) match
        case Some((previous, previousOrigin)) if !java.util.Arrays.equals(previous, bytes) =>
          throw new IllegalStateException(
            s"conflicting artifact entry '$name' from $previousOrigin and $origin")
        case Some(_) => ()
        case None    => entries(name) = bytes -> origin

    runtimeJars.foreach { jar =>
      val zip = new ZipFile(jar)
      try
        zip.entries().asScala.toList
          .filterNot(_.isDirectory)
          .sortBy(_.getName)
          .foreach { entry =>
            val name = entry.getName
            if name.startsWith(ServicePrefix) then
              val lines = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8)
                .linesIterator.map(_.trim).filter(line => line.nonEmpty && !line.startsWith("#"))
              val target = services.getOrElseUpdate(name, mutable.TreeSet.empty[String])
              target ++= lines
            else if !skipPackagingEntry(name) then
              add(name, zip.getInputStream(entry).readAllBytes(), jar.getName)
          }
      finally zip.close()
    }

    services.foreach { case (name, implementations) =>
      add(name, (implementations.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8), "merged services")
    }

    val providers = services
      .get("META-INF/services/ssc.plugin.NativePlugin")
      .map(_.toList)
      .getOrElse(Nil)
    add("META-INF/MANIFEST.MF", manifestBytes, "generated manifest")
    add(
      "META-INF/scalascript/artifact.properties",
      metadataBytes(sources, runtimeJars.map(_.getName), providers),
      "generated metadata")
    add(EntryClass, _root_.ssc.bytecode.JvmByteGen.emitProgram(program), "generated CoreIR class")

    Option(output.getParentFile).foreach(parent => Files.createDirectories(parent.toPath))
    val out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))
    try
      entries.foreach { case (name, (bytes, _)) =>
        val crc = new CRC32()
        crc.update(bytes)
        val entry = new ZipEntry(name)
        entry.setTime(FixedZipTime)
        entry.setMethod(ZipEntry.STORED)
        entry.setSize(bytes.length.toLong)
        entry.setCompressedSize(bytes.length.toLong)
        entry.setCrc(crc.getValue)
        out.putNextEntry(entry)
        out.write(bytes)
        out.closeEntry()
      }
    finally out.close()

  private def skipPackagingEntry(name: String): Boolean =
    val upper = name.toUpperCase(java.util.Locale.ROOT)
    name == "META-INF/MANIFEST.MF" ||
    name == "module-info.class" ||
    (name.startsWith("META-INF/versions/") && name.endsWith("/module-info.class")) ||
    upper == "META-INF/INDEX.LIST" ||
    upper.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)") ||
    upper.matches("META-INF/(LICENSE|NOTICE|DEPENDENCIES)(\\..*)?")

  private def manifestBytes: Array[Byte] =
    "Manifest-Version: 1.0\r\nMain-Class: ssc.gen.Entry\r\nMulti-Release: true\r\n\r\n"
      .getBytes(StandardCharsets.UTF_8)

  private def metadataBytes(
      sources: List[File],
      runtimeJars: List[String],
      providers: List[String]): Array[Byte] =
    val sourceRows = sources.map { file =>
      file.getName -> sha256(Files.readAllBytes(file.toPath))
    }.sortBy(identity)
    val lines = List(
      "format=scalascript-jvm-2.1",
      "main=ssc.gen.Entry",
      s"source.count=${sourceRows.length}",
      s"runtime.jars=${escape(runtimeJars.sorted.mkString(","))}",
      s"providers=${escape(providers.sorted.mkString(","))}",
    ) ++ sourceRows.zipWithIndex.flatMap { case ((name, digest), index) =>
      List(
        s"source.$index.name=${escape(name)}",
        s"source.$index.sha256=$digest",
      )
    }
    (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8)

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  private def escape(value: String): String =
    value.flatMap {
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '='  => "\\="
      case ':'  => "\\:"
      case c    => c.toString
    }

final class BuildJvmCmd extends CliCommand:
  def name = "build-jvm"
  override def summary = "Build a compiler-free executable JAR via native CoreIR and ASM"
  override def category = "Build, bundle & package"
  override def details = List("Usage: ssc build-jvm file.ssc [more.ssc ...] -o app.jar")

  def run(args: List[String]): Unit =
    var output: Option[String] = None
    val files = mutable.ArrayBuffer.empty[String]
    val it = args.iterator
    while it.hasNext do
      it.next() match
        case "-o" | "--output" if it.hasNext => output = Some(it.next())
        case flag if flag.startsWith("-") =>
          throw new IllegalArgumentException(s"build-jvm: unknown option: $flag")
        case file => files += file

    if files.isEmpty || output.isEmpty then
      throw new IllegalArgumentException(
        "usage: ssc build-jvm file.ssc [more.ssc ...] -o app.jar")

    try
      val compilation = RunNativeV2.compile(files.toList)
      val installRoot = Option(System.getProperty("ssc.lib.path")).map(new File(_)).getOrElse {
        throw new IllegalStateException("build-jvm requires a staged installation (ssc.lib.path is unset)")
      }
      val out = new File(output.get).getCanonicalFile
      NativeJvmArtifact.write(
        compilation.program,
        compilation.sources,
        new File(installRoot, "bin/lib/jars"),
        out)
      println(s"JVM artifact written to ${out.getPath}")
    catch
      case e: Exception =>
        System.err.println(s"build-jvm: ${e.getMessage}")
        System.exit(1)
