package scalascript.imports

import org.scalatest.funsuite.AnyFunSuite
import java.util.zip.{ZipOutputStream, ZipEntry}
import java.io.ByteArrayOutputStream

/** arch-ffi-p3 — .ssclib with jvm/glue.jar + GlueClasspathRegistry tests. */
class SsclibGlueJarTest extends AnyFunSuite:

  // ── Manifest serialisation ─────────────────────────────────────────────

  test("SsclibManifest: glue fields round-trip through toYaml / parseString") {
    val m = SsclibManifest(
      name    = "io.example/glue-lib",
      version = "1.0.0",
      glueJvm = Some("jvm/glue.jar"),
      glueJs  = Some("js/glue.js"),
    )
    val yaml   = SsclibManifest.toYaml(m)
    val parsed = SsclibManifest.parseString(yaml).get
    assert(parsed.glueJvm.contains("jvm/glue.jar"), s"glueJvm not preserved; yaml=\n$yaml")
    assert(parsed.glueJs.contains("js/glue.js"),   s"glueJs not preserved; yaml=\n$yaml")
  }

  test("SsclibManifest: glue fields absent when not set") {
    val m    = SsclibManifest(name = "io.example/no-glue", version = "1.0.0")
    val yaml = SsclibManifest.toYaml(m)
    assert(!yaml.contains("glue:"), s"unexpected glue section: $yaml")
    val parsed = SsclibManifest.parseString(yaml).get
    assert(parsed.glueJvm.isEmpty)
    assert(parsed.glueJs.isEmpty)
  }

  test("SsclibManifest.parseString: reads glue.jvm from nested map") {
    val yaml =
      """name: io.example/test
        |version: 1.0.0
        |entry: src/main.ssc
        |scala-script-version: ">=1.60"
        |glue:
        |  jvm: jvm/glue.jar
        |""".stripMargin
    val m = SsclibManifest.parseString(yaml).get
    assert(m.glueJvm.contains("jvm/glue.jar"))
    assert(m.glueJs.isEmpty)
  }

  test("SsclibManifest.parseString: reads both glue fields") {
    val yaml =
      """name: io.example/test
        |version: 2.0.0
        |entry: src/main.ssc
        |scala-script-version: ">=1.60"
        |glue:
        |  jvm: jvm/my-glue.jar
        |  js: js/my-glue.js
        |""".stripMargin
    val m = SsclibManifest.parseString(yaml).get
    assert(m.glueJvm.contains("jvm/my-glue.jar"))
    assert(m.glueJs.contains("js/my-glue.js"))
  }

  // ── GlueClasspathRegistry ──────────────────────────────────────────────

  test("GlueClasspathRegistry: addJar / contains / jars") {
    GlueClasspathRegistry.clear()
    val tmpJar = os.temp(suffix = ".jar")
    try
      GlueClasspathRegistry.addJar(tmpJar)
      assert(GlueClasspathRegistry.contains(tmpJar))
      assert(GlueClasspathRegistry.jars.contains(tmpJar))
    finally
      GlueClasspathRegistry.clear()
      os.remove(tmpJar)
  }

  test("GlueClasspathRegistry.clear removes all entries") {
    GlueClasspathRegistry.clear()
    val tmpJar = os.temp(suffix = ".jar")
    try
      GlueClasspathRegistry.addJar(tmpJar)
      GlueClasspathRegistry.clear()
      assert(!GlueClasspathRegistry.contains(tmpJar))
      assert(GlueClasspathRegistry.jars.isEmpty)
    finally
      GlueClasspathRegistry.clear()
      os.remove(tmpJar)
  }

  // ── .ssclib extraction with glue.jar ──────────────────────────────────

  private def buildSsclibWithGlue(
      name:    String,
      version: String,
      glueJar: Option[Array[Byte]],
  ): Array[Byte] =
    val manifest = SsclibManifest(
      name    = name,
      version = version,
      entry   = "src/main.ssc",
      glueJvm = glueJar.map(_ => "jvm/glue.jar"),
    )
    val baos = new ByteArrayOutputStream
    val zip  = new ZipOutputStream(baos)
    try
      zip.putNextEntry(new ZipEntry(SsclibManifest.FileName))
      zip.write(SsclibManifest.toYaml(manifest).getBytes("UTF-8"))
      zip.closeEntry()
      zip.putNextEntry(new ZipEntry("src/main.ssc"))
      zip.write("// source".getBytes("UTF-8"))
      zip.closeEntry()
      glueJar.foreach { jarBytes =>
        zip.putNextEntry(new ZipEntry("jvm/glue.jar"))
        zip.write(jarBytes)
        zip.closeEntry()
      }
    finally zip.close()
    baos.toByteArray

  private val depSourcesFile: os.Path =
    os.home / ".config" / "scalascript" / "dep-sources"
  private val libCacheBase: os.Path =
    os.home / ".cache" / "scalascript" / "libs"

  private def withDepServer(libs: Map[String, Array[Byte]])(body: String => Unit): Unit =
    import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
    import java.net.InetSocketAddress
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port   = server.getAddress.getPort
    val baseUrl = s"http://127.0.0.1:$port"
    for (path, bytes) <- libs do
      server.createContext("/" + path.stripPrefix("/"), new HttpHandler:
        override def handle(ex: HttpExchange): Unit =
          ex.sendResponseHeaders(200, bytes.length.toLong)
          val out = ex.getResponseBody
          try out.write(bytes)
          finally out.close()
      )
    val oldDepSrc = if os.exists(depSourcesFile) then Some(os.read(depSourcesFile)) else None
    try
      os.makeDir.all(depSourcesFile / os.up)
      os.write.over(depSourcesFile, baseUrl + "\n")
      server.start()
      body(baseUrl)
    finally
      server.stop(0)
      oldDepSrc match
        case Some(c) => os.write.over(depSourcesFile, c)
        case None    => if os.exists(depSourcesFile) then os.remove(depSourcesFile)
      libs.keys.foreach { path =>
        val noExt    = path.stripSuffix(".ssclib")
        val segments = noExt.split('/').toList
        if segments.length >= 3 then
          val cacheDir = segments.dropRight(1).foldLeft(libCacheBase)(_ / _) / segments.last
          if os.exists(cacheDir) then os.remove.all(cacheDir)
      }

  test("ImportResolver: .ssclib with glue.jar registers jar in GlueClasspathRegistry") {
    GlueClasspathRegistry.clear()
    val minimalJarBytes = Array[Byte](0x50, 0x4b, 0x05, 0x06) ++ Array.fill(18)(0.toByte)  // empty ZIP = minimal JAR
    val libBytes = buildSsclibWithGlue("io.example/glued", "1.0.0", glueJar = Some(minimalJarBytes))
    withDepServer(Map("io.example/glued/1.0.0.ssclib" -> libBytes)) { _ =>
      try
        ImportResolver.resolveAll(List("dep:io.example/glued:1.0.0"))
        val jarsAfter = GlueClasspathRegistry.jars
        assert(jarsAfter.nonEmpty, s"glue.jar should have been registered; jars=$jarsAfter")
        assert(jarsAfter.exists(_.last == "glue.jar"),
          s"expected glue.jar in registry; jars=$jarsAfter")
      finally
        GlueClasspathRegistry.clear()
    }
  }

  test("ImportResolver: .ssclib without glue.jar does not add to GlueClasspathRegistry") {
    GlueClasspathRegistry.clear()
    val libBytes = buildSsclibWithGlue("io.example/noglue", "1.0.0", glueJar = None)
    withDepServer(Map("io.example/noglue/1.0.0.ssclib" -> libBytes)) { _ =>
      try
        ImportResolver.resolveAll(List("dep:io.example/noglue:1.0.0"))
        assert(GlueClasspathRegistry.jars.isEmpty,
          s"no glue.jar declared — registry should be empty; jars=${GlueClasspathRegistry.jars}")
      finally
        GlueClasspathRegistry.clear()
    }
  }
