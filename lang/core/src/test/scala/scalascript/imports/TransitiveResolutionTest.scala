package scalascript.imports

import org.scalatest.funsuite.AnyFunSuite
import java.util.zip.{ZipOutputStream, ZipEntry}
import java.io.ByteArrayOutputStream

/** Tests for ImportResolver transitive dependency resolution (arch-lib-p5). */
class TransitiveResolutionTest extends AnyFunSuite:

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def buildSsclib(
      name:   String,
      version: String,
      entry:  String = "src/main.ssc",
      deps:   List[String] = Nil,
      srcContent: String = "// source",
  ): Array[Byte] =
    val manifest = SsclibManifest(
      name    = name,
      version = version,
      entry   = entry,
      dependencies = deps,
    )
    val baos = new ByteArrayOutputStream
    val zip  = new ZipOutputStream(baos)
    try
      zip.putNextEntry(new ZipEntry(SsclibManifest.FileName))
      zip.write(SsclibManifest.toYaml(manifest).getBytes("UTF-8"))
      zip.closeEntry()
      zip.putNextEntry(new ZipEntry(entry))
      zip.write(srcContent.getBytes("UTF-8"))
      zip.closeEntry()
    finally zip.close()
    baos.toByteArray

  // dep-sources and lib cache use the real os.home (static paths in ImportResolver)
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
      // Restore dep-sources
      oldDepSrc match
        case Some(c) => os.write.over(depSourcesFile, c)
        case None    => if os.exists(depSourcesFile) then os.remove(depSourcesFile)
      // Clean up cached test libs (best-effort)
      libs.keys.foreach { path =>
        // path like "io.example/utils/1.0.0.ssclib" → clean libCacheBase / org / name / version
        val noExt    = path.stripSuffix(".ssclib")
        val segments = noExt.split('/').toList
        // org may be "io.example", name "utils", version "1.0.0" → 3 segments
        if segments.length >= 3 then
          val cacheDir = segments.dropRight(1).foldLeft(libCacheBase)(_ / _) / segments.last
          if os.exists(cacheDir) then os.remove.all(cacheDir)
      }

  // ── Tests ─────────────────────────────────────────────────────────────────

  test("resolveAll: single dep with no transitive — recorded in lock"):
    val libBytes = buildSsclib("io.example/utils", "1.0.0")
    withDepServer(Map("io.example/utils/1.0.0.ssclib" -> libBytes)) { _ =>
      val lock = ImportResolver.resolveAll(List("dep:io.example/utils:1.0.0"))
      assert(lock.locked.get("io.example/utils").contains("1.0.0"))
    }

  test("resolveAll: transitive dep pulled in automatically"):
    val utilsBytes = buildSsclib("io.example/utils", "1.0.0")
    val libBytes   = buildSsclib(
      "io.example/my-lib", "2.0.0",
      deps = List("dep:io.example/utils:1.0.0"),
    )
    withDepServer(Map(
      "io.example/utils/1.0.0.ssclib"   -> utilsBytes,
      "io.example/my-lib/2.0.0.ssclib"  -> libBytes,
    )) { _ =>
      val lock = ImportResolver.resolveAll(List("dep:io.example/my-lib:2.0.0"))
      assert(lock.locked.get("io.example/my-lib").contains("2.0.0"))
      assert(lock.locked.get("io.example/utils").contains("1.0.0"),
        s"transitive dep not recorded; locked=${lock.locked}")
    }

  test("resolveAll: conflict resolved as latest-wins"):
    val utils10 = buildSsclib("io.example/utils", "1.0.0")
    val utils20 = buildSsclib("io.example/utils", "2.0.0")
    val libA    = buildSsclib("io.example/a", "1.0.0", deps = List("dep:io.example/utils:1.0.0"))
    val libB    = buildSsclib("io.example/b", "1.0.0", deps = List("dep:io.example/utils:2.0.0"))
    withDepServer(Map(
      "io.example/utils/1.0.0.ssclib" -> utils10,
      "io.example/utils/2.0.0.ssclib" -> utils20,
      "io.example/a/1.0.0.ssclib"     -> libA,
      "io.example/b/1.0.0.ssclib"     -> libB,
    )) { _ =>
      val lock = ImportResolver.resolveAll(
        List("dep:io.example/a:1.0.0", "dep:io.example/b:1.0.0"),
        strictDeps = false,
      )
      // Latest-wins: 2.0.0 > 1.0.0
      assert(lock.locked.get("io.example/utils").contains("2.0.0"),
        s"expected 2.0.0 to win; locked=${lock.locked}")
    }

  test("resolveAll: --strict-deps throws on version conflict"):
    val utils10 = buildSsclib("io.example/utils", "1.0.0")
    val utils20 = buildSsclib("io.example/utils", "2.0.0")
    val libA    = buildSsclib("io.example/a", "1.0.0", deps = List("dep:io.example/utils:1.0.0"))
    val libB    = buildSsclib("io.example/b", "1.0.0", deps = List("dep:io.example/utils:2.0.0"))
    withDepServer(Map(
      "io.example/utils/1.0.0.ssclib" -> utils10,
      "io.example/utils/2.0.0.ssclib" -> utils20,
      "io.example/a/1.0.0.ssclib"     -> libA,
      "io.example/b/1.0.0.ssclib"     -> libB,
    )) { _ =>
      val ex = intercept[RuntimeException] {
        ImportResolver.resolveAll(
          List("dep:io.example/a:1.0.0", "dep:io.example/b:1.0.0"),
          strictDeps = true,
        )
      }
      assert(ex.getMessage.contains("conflict") || ex.getMessage.contains("Transitive"),
        s"unexpected error: ${ex.getMessage}")
    }

  test("resolveAll: cycle detection throws with informative message"):
    // A depends on B; B depends on A — cycle
    val libA = buildSsclib("io.example/a", "1.0.0", deps = List("dep:io.example/b:1.0.0"))
    val libB = buildSsclib("io.example/b", "1.0.0", deps = List("dep:io.example/a:1.0.0"))
    withDepServer(Map(
      "io.example/a/1.0.0.ssclib" -> libA,
      "io.example/b/1.0.0.ssclib" -> libB,
    )) { _ =>
      val ex = intercept[RuntimeException] {
        ImportResolver.resolveAll(List("dep:io.example/a:1.0.0"))
      }
      assert(ex.getMessage.contains("cycle") || ex.getCause != null && ex.getCause.getMessage.contains("cycle"),
        s"unexpected error: ${ex.getMessage}")
    }
