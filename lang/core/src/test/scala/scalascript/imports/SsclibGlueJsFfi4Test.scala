package scalascript.imports

import org.scalatest.funsuite.AnyFunSuite
import java.util.zip.{ZipOutputStream, ZipEntry}
import java.io.ByteArrayOutputStream

/** arch-ffi-p4 — js/glue.js injection + META-INF/services in glue.jar tests. */
class SsclibGlueJsFfi4Test extends AnyFunSuite:

  // ── GlueJsPreambleRegistry ─────────────────────────────────────────────

  test("GlueJsPreambleRegistry: addPreamble / contains / preambles") {
    GlueJsPreambleRegistry.clear()
    GlueJsPreambleRegistry.addPreamble("dep:io.example/lib:1.0.0", "function _glueHelper() {}")
    assert(GlueJsPreambleRegistry.contains("dep:io.example/lib:1.0.0"))
    assert(!GlueJsPreambleRegistry.isEmpty)
    assert(GlueJsPreambleRegistry.preambles.exists(_.contains("_glueHelper")))
  }

  test("GlueJsPreambleRegistry.clear removes all entries") {
    GlueJsPreambleRegistry.clear()
    GlueJsPreambleRegistry.addPreamble("dep:io.example/lib:1.0.0", "// glue")
    GlueJsPreambleRegistry.clear()
    assert(GlueJsPreambleRegistry.isEmpty)
    assert(GlueJsPreambleRegistry.preambles.isEmpty)
    assert(!GlueJsPreambleRegistry.contains("dep:io.example/lib:1.0.0"))
  }

  test("GlueJsPreambleRegistry: second addPreamble for same key overwrites") {
    GlueJsPreambleRegistry.clear()
    GlueJsPreambleRegistry.addPreamble("dep:io.example/lib:1.0.0", "// v1")
    GlueJsPreambleRegistry.addPreamble("dep:io.example/lib:1.0.0", "// v2")
    val ps = GlueJsPreambleRegistry.preambles
    assert(ps.size == 1, s"expected 1 preamble, got ${ps.size}")
    assert(ps.head == "// v2")
    GlueJsPreambleRegistry.clear()
  }

  // ── .ssclib with js/glue.js ────────────────────────────────────────────

  private def buildSsclibWithGlueJs(
      name:    String,
      version: String,
      glueJs:  Option[String],
  ): Array[Byte] =
    val manifest = SsclibManifest(
      name    = name,
      version = version,
      entry   = "src/main.ssc",
      glueJs  = glueJs.map(_ => "js/glue.js"),
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
      glueJs.foreach { content =>
        zip.putNextEntry(new ZipEntry("js/glue.js"))
        zip.write(content.getBytes("UTF-8"))
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

  test("ImportResolver: .ssclib with js/glue.js registers preamble in GlueJsPreambleRegistry") {
    GlueJsPreambleRegistry.clear()
    val glueContent = "function _myGlueHelper(x) { return x * 2; }"
    val libBytes = buildSsclibWithGlueJs("io.example/glued-js", "1.0.0", glueJs = Some(glueContent))
    withDepServer(Map("io.example/glued-js/1.0.0.ssclib" -> libBytes)) { _ =>
      try
        ImportResolver.resolveAll(List("dep:io.example/glued-js:1.0.0"))
        val preambles = GlueJsPreambleRegistry.preambles
        assert(preambles.nonEmpty, s"glue.js preamble should have been registered; preambles=$preambles")
        assert(preambles.exists(_.contains("_myGlueHelper")),
          s"expected _myGlueHelper in preamble; preambles=$preambles")
      finally
        GlueJsPreambleRegistry.clear()
    }
  }

  test("ImportResolver: .ssclib without js/glue.js does not add to GlueJsPreambleRegistry") {
    GlueJsPreambleRegistry.clear()
    val libBytes = buildSsclibWithGlueJs("io.example/nojs", "1.0.0", glueJs = None)
    withDepServer(Map("io.example/nojs/1.0.0.ssclib" -> libBytes)) { _ =>
      try
        ImportResolver.resolveAll(List("dep:io.example/nojs:1.0.0"))
        assert(GlueJsPreambleRegistry.isEmpty,
          s"no glue.js declared — registry should be empty; preambles=${GlueJsPreambleRegistry.preambles}")
      finally
        GlueJsPreambleRegistry.clear()
    }
  }

  // ── META-INF/services in glue.jar (BackendRegistry wired) ─────────────

  private def buildSsclibWithGlueJarAndServices(
      name:      String,
      version:   String,
      servicesContent: String,
  ): Array[Byte] =
    val manifest = SsclibManifest(
      name    = name,
      version = version,
      entry   = "src/main.ssc",
      glueJvm = Some("jvm/glue.jar"),
    )
    // Build a minimal JAR that only contains META-INF/services/<Backend FQN>
    val jarBaos = new ByteArrayOutputStream
    val jarZip  = new ZipOutputStream(jarBaos)
    try
      jarZip.putNextEntry(new ZipEntry("META-INF/services/scalascript.backend.spi.Backend"))
      jarZip.write(servicesContent.getBytes("UTF-8"))
      jarZip.closeEntry()
    finally jarZip.close()
    val jarBytes = jarBaos.toByteArray

    val baos = new ByteArrayOutputStream
    val zip  = new ZipOutputStream(baos)
    try
      zip.putNextEntry(new ZipEntry(SsclibManifest.FileName))
      zip.write(SsclibManifest.toYaml(manifest).getBytes("UTF-8"))
      zip.closeEntry()
      zip.putNextEntry(new ZipEntry("src/main.ssc"))
      zip.write("// source".getBytes("UTF-8"))
      zip.closeEntry()
      zip.putNextEntry(new ZipEntry("jvm/glue.jar"))
      zip.write(jarBytes)
      zip.closeEntry()
    finally zip.close()
    baos.toByteArray

  test("ImportResolver: glue.jar with META-INF/services wires BackendRegistry (no crash on empty services)") {
    GlueClasspathRegistry.clear()
    val libBytes = buildSsclibWithGlueJarAndServices(
      "io.example/services-glue", "1.0.0",
      servicesContent = "# no actual class\n",
    )
    withDepServer(Map("io.example/services-glue/1.0.0.ssclib" -> libBytes)) { _ =>
      try
        ImportResolver.resolveAll(List("dep:io.example/services-glue:1.0.0"))
        // GlueClasspathRegistry should have received the jar (Phase 3 still works).
        assert(GlueClasspathRegistry.jars.nonEmpty,
          s"glue.jar should be in GlueClasspathRegistry after resolveAll")
        // BackendRegistry.inProcess should not crash when the services file has no valid entries.
        // (ServiceLoader skips blank/comment lines gracefully.)
        import scalascript.compiler.plugin.BackendRegistry
        val backends = scala.util.Try(BackendRegistry.inProcess)
        assert(backends.isSuccess,
          s"BackendRegistry.inProcess should not throw even with empty glue services: ${backends.failed.getOrElse("")}")
      finally
        GlueClasspathRegistry.clear()
    }
  }
