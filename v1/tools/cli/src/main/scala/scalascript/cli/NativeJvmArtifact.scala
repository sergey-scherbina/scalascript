package scalascript.cli

import java.io.{BufferedOutputStream, File, FileOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.{CRC32, ZipEntry, ZipFile, ZipOutputStream}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import _root_.ssc.plugin.{NativeContentModule, NativeRuntimeConfig}

/** Deterministic self-contained JAR writer for the native CoreIR→ASM lane. */
private[cli] object NativeJvmArtifact:
  private val EntryClass = "ssc/gen/Entry.class"
  private val ServicePrefix = "META-INF/services/"
  private val FixedZipTime = 315532800000L // 1980-01-01T00:00:00Z (DOS epoch)

  private val RuntimePrefixes = List(
    "scala-library-",
    "scala3-library_3-",
    "scalascript-v2-core_",
    // F5 kernel-slimming (2026-07-19): Emit and NativeUiSites were relocated OUT
    // of scalascript-v2-core into their own modules. The produced standalone
    // artifact links both by FQN at runtime — `NativeArtifactRuntime.initialize`
    // touches `ssc.Emit.globalsRef`, and `UiNativePlugin.install` (run by
    // NativePluginHost.loadAll) touches `ssc.NativeUiSites.internalName` — so both
    // jars must be bundled or the artifact throws NoClassDefFoundError at runtime.
    "scalascript-v2-jvm-runtime_",
    "scalascript-v2-nativeui_",
    "scalascript-v2-native-plugin-spi_",
    "scalascript-v2-native-host-plugin_",
    "scalascript-v2-native-crypto-plugin_",
    "scalascript-v2-native-os-plugin_",
    "scalascript-v2-native-fs-plugin_",
    "scalascript-v2-native-json-plugin_",
    "scalascript-v2-native-http-fast-plugin_",
    "scalascript-http-fast-engine_",
    "scalascript-v2-native-ui-plugin_",
    "scalascript-v2-native-state-effect-plugin_",
    "scalascript-v2-native-effect-runners-plugin_",
    "scalascript-v2-native-storage-effect-plugin_",
    "scalascript-v2-native-reactive-plugin_",
    "scalascript-v2-native-yaml-plugin_",
    "scalascript-v2-native-content-plugin_",
    "scalascript-v2-native-dataset-plugin_",
    "scalascript-v2-native-generator-plugin_",
    "scalascript-v2-native-actors-plugin_",
    "scalascript-v2-native-distributed-plugin_",
    "scalascript-v2-native-graph-plugin_",
    "scalascript-v2-native-optics-plugin_",
    "scalascript-yaml_",
  )

  private val RequiredPrefixes = List(
    "scala-library-",
    "scala3-library_3-",
    "scalascript-v2-core_",
    // F5: fail the build fast (with a clear message) if these relocated runtime
    // deps are not staged, instead of shipping an artifact that NoClassDefFounds.
    "scalascript-v2-jvm-runtime_",
    "scalascript-v2-nativeui_",
    "scalascript-v2-native-plugin-spi_",
    "scalascript-v2-native-host-plugin_",
    "scalascript-v2-native-dataset-plugin_",
    "scalascript-v2-native-generator-plugin_",
    "scalascript-v2-native-effect-runners-plugin_",
    "scalascript-v2-native-actors-plugin_",
    "scalascript-v2-native-distributed-plugin_",
    "scalascript-v2-native-graph-plugin_",
    "scalascript-v2-native-optics-plugin_",
    "scalascript-v2-native-http-fast-plugin_",
    "scalascript-http-fast-engine_",
  )

  private val SqlRuntimePrefixes = List(
    "scalascript-v2-native-sql-plugin_",
    "scalascript-backend-sql-runtime_",
    "scalascript-backend-config-runtime_",
    "scalascript-backend-typed-data-runtime_",
    "scalascript-markup-core_",
    "scalascript-yaml_",
    "scalascript-wire-core_",
    "HikariCP-",
    "h2-",
    "sqlite-jdbc-",
    "postgresql-",
    "checker-qual-",
    "slf4j-api-1.",
    "ujson_3-",
    "upickle-core_3-",
    "upack_3-",
    "geny_3-",
  )

  private val RequiredSqlPrefixes = List(
    "scalascript-v2-native-sql-plugin_",
    "scalascript-backend-sql-runtime_",
    "HikariCP-",
    "h2-",
    "sqlite-jdbc-",
    "postgresql-",
  )

  def runCommand(args: List[String]): Unit =
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

    val compilation = RunNativeV2.compile(files.toList)
    val installRoot = Option(System.getProperty("ssc.lib.path")).map(new File(_)).getOrElse {
      throw new IllegalStateException("build-jvm requires a staged installation (ssc.lib.path is unset)")
    }
    val out = new File(output.get).getCanonicalFile
    write(
      compilation.program,
      compilation.config,
      compilation.sourceUnits,
      new File(installRoot, "bin/lib/standard/jars"),
      out)
    println(s"JVM artifact written to ${out.getPath}")

  def write(
      program: _root_.ssc.Program,
      config: NativeRuntimeConfig,
      sourceUnits: List[NativeSourceUnit],
      runtimeDir: File,
      output: File): Unit =
    if !runtimeDir.isDirectory then
      throw new IllegalStateException(s"staged standard runtime not found: ${runtimeDir.getPath}")

    val selectedPrefixes = RuntimePrefixes ++
      (if config.databases.nonEmpty then SqlRuntimePrefixes else Nil)
    val runtimeJars = Option(runtimeDir.listFiles()).toList.flatten
      .filter(f => f.isFile && f.getName.endsWith(".jar"))
      .filter(f => selectedPrefixes.exists(f.getName.startsWith))
      .sortBy(_.getName)
    val requiredPrefixes = RequiredPrefixes ++
      (if config.databases.nonEmpty then RequiredSqlPrefixes else Nil)
    requiredPrefixes.foreach { prefix =>
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
            else if !skipPackagingEntry(name) && !skipRuntimeEntry(jar.getName, name) then
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
      metadataBytes(sourceUnits, runtimeJars.map(_.getName), providers, config),
      "generated metadata")
    if config.contentModules.nonEmpty then
      add(
        "META-INF/scalascript/content.bin",
        _root_.ssc.plugin.NativeContentCodec.encode(
          artifactContentModules(config.contentModules, sourceUnits)),
        "generated structural content")
    // Op-argument lifting: keep the AOT native artifact identical to the
    // `run --bytecode` native lane (see ssc.bytecode.OpAnfNative).
    val lifted = _root_.ssc.bytecode.OpAnfNative.lift(program)
    val sourceDebug = NativeJvmSourceMap.build(lifted, sourceUnits)
    add(
      EntryClass,
      _root_.ssc.bytecode.JvmByteGen.emitProgram(lifted, sourceDebug),
      "generated CoreIR class")

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

  /** Structural compilation validates canonical source identities, but an
   * executable artifact must not persist checkout or temporary-directory
   * paths. The source closure already owns a deterministic display identity
   * for every root/import; translate only the persisted artifact graph. */
  private def artifactContentModules(
      modules: List[NativeContentModule],
      sourceUnits: List[NativeSourceUnit]): List[NativeContentModule] =
    val displayBySource = sourceUnits.iterator.map { unit =>
      unit.file.getCanonicalPath.replace(java.io.File.separatorChar, '/') -> unit.displayPath
    }.toMap
    def display(path: String): String = displayBySource.getOrElse(path, path)
    modules.map { module =>
      module.copy(
        source = display(module.source),
        directImports = module.directImports.map(display))
    }

  private def skipPackagingEntry(name: String): Boolean =
    val upper = name.toUpperCase(java.util.Locale.ROOT)
    name == "META-INF/MANIFEST.MF" ||
    name == "module-info.class" ||
    (name.startsWith("META-INF/versions/") && name.endsWith("/module-info.class")) ||
    upper == "META-INF/INDEX.LIST" ||
    upper.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)") ||
    upper.matches("META-INF/(LICENSE|NOTICE|DEPENDENCIES)(\\..*)?")

  /** H2's optional CREATE ALIAS source compiler is the only driver surface
   * that references javax.tools. Standard JDBC/DDL/DML does not load it, so a
   * native artifact omits that isolated optional family and remains runnable
   * on a JRE without java.compiler. */
  private def skipRuntimeEntry(jarName: String, name: String): Boolean =
    jarName.startsWith("h2-") && name.startsWith("org/h2/util/SourceCompiler") &&
      name.endsWith(".class")

  private def manifestBytes: Array[Byte] =
    "Manifest-Version: 1.0\r\nMain-Class: ssc.gen.Entry\r\nMulti-Release: true\r\n\r\n"
      .getBytes(StandardCharsets.UTF_8)

  private def metadataBytes(
      sourceUnits: List[NativeSourceUnit],
      runtimeJars: List[String],
      providers: List[String],
      config: NativeRuntimeConfig): Array[Byte] =
    val sourceRows = sourceUnits.map { unit =>
      unit.displayPath -> sha256(Files.readAllBytes(unit.file.toPath))
    }.sortBy(identity)
    val databaseRows = config.databases.toList.sortBy(_._1)
    val lines = List(
      "format=scalascript-jvm-2.1",
      "main=ssc.gen.Entry",
      s"source.count=${sourceRows.length}",
      s"runtime.jars=${escape(runtimeJars.sorted.mkString(","))}",
      s"providers=${escape(providers.sorted.mkString(","))}",
      s"database.count=${databaseRows.length}",
      s"content.count=${config.contentModules.length}",
    ) ++ sourceRows.zipWithIndex.flatMap { case ((name, digest), index) =>
      List(
        s"source.$index.name=${escape(name)}",
        s"source.$index.sha256=$digest",
      )
    } ++ databaseRows.zipWithIndex.flatMap { case ((name, database), index) =>
      def option(field: String, value: Option[String]): List[String] = List(
        s"database.$index.$field.present=${value.isDefined}",
        s"database.$index.$field=${escape(value.getOrElse(""))}",
      )
      List(
        s"database.$index.name=${escape(name)}",
        s"database.$index.url=${escape(database.url)}",
      ) ++ option("user", database.user) ++
        option("password", database.password) ++
        option("driver", database.driver)
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
    try
      NativeJvmArtifact.runCommand(args)
    catch
      case e: Exception =>
        System.err.println(s"build-jvm: ${e.getMessage}")
        System.exit(1)
