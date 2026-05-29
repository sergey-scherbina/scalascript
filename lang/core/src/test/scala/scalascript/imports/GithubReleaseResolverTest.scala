package scalascript.imports

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.scalatest.funsuite.AnyFunSuite

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class GithubReleaseResolverTest extends AnyFunSuite:

  test("GithubReleaseResolver parses github URI"):
    val parsed = GithubReleaseResolver.parse("github:owner/repo@v1.2.3#dist/plugin.sscpkg")
    assert(parsed.owner == "owner")
    assert(parsed.repo == "repo")
    assert(parsed.tag == "v1.2.3")
    assert(parsed.assetPath.contains("dist/plugin.sscpkg"))

  test("GithubReleaseResolver downloads release asset into cache and verifies sha256"):
    withMockGithub("plugin-bytes".getBytes(StandardCharsets.UTF_8)) { (_, bytes) =>
      val cache = os.temp.dir(prefix = "ssc-github-cache")
      try
        val resolver = GithubReleaseResolver()
        val path = resolver.resolve(
          scalascript.backend.spi.DepSpec(
            raw = "github:owner/repo@v1.0.0",
            sha256 = Some(DepCache.sha256hex(bytes)),
            cacheRoot = cache.toNIO,
          )
        )
        val resolved = os.Path(path)
        assert(os.exists(resolved))
        assert(os.read.bytes(resolved).sameElements(bytes))

        val cached = resolver.resolve(
          scalascript.backend.spi.DepSpec(
            raw = "github:owner/repo@v1.0.0",
            sha256 = Some(DepCache.sha256hex(bytes)),
            cacheRoot = cache.toNIO,
          )
        )
        assert(cached == path)
      finally os.remove.all(cache)
    }

  test("ImportResolver dispatches github scheme through DepResolver"):
    withMockGithub("resolver-import".getBytes(StandardCharsets.UTF_8)) { (_, bytes) =>
      val oldHome = System.getProperty("user.home")
      val home = os.temp.dir(prefix = "ssc-github-home")
      try
        System.setProperty("user.home", home.toString)
        val resolved = ImportResolver.resolve(
          s"github:owner/repo@v1.0.0 sha256:${DepCache.sha256hex(bytes)}",
          os.temp.dir(prefix = "ssc-github-base"),
        )
        assert(os.read.bytes(resolved).sameElements(bytes))
      finally
        System.setProperty("user.home", oldHome)
        os.remove.all(home)
    }

  private def withMockGithub(bytes: Array[Byte])(body: (String, Array[Byte]) => Unit): Unit =
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port = server.getAddress.getPort
    val baseUrl = s"http://127.0.0.1:$port"
    server.createContext("/repos/owner/repo/releases/tags/v1.0.0", new HttpHandler:
      override def handle(ex: HttpExchange): Unit =
        val json =
          s"""{"assets":[{"name":"repo.sscpkg","browser_download_url":"$baseUrl/assets/repo.sscpkg"}]}"""
        val out = json.getBytes(StandardCharsets.UTF_8)
        ex.sendResponseHeaders(200, out.length.toLong)
        val os = ex.getResponseBody
        try os.write(out)
        finally os.close()
    )
    server.createContext("/assets/repo.sscpkg", new HttpHandler:
      override def handle(ex: HttpExchange): Unit =
        ex.sendResponseHeaders(200, bytes.length.toLong)
        val os = ex.getResponseBody
        try os.write(bytes)
        finally os.close()
    )
    val oldBase = System.getProperty("ssc.github.api.base")
    try
      System.setProperty("ssc.github.api.base", baseUrl)
      server.start()
      body(baseUrl, bytes)
    finally
      if oldBase == null then System.clearProperty("ssc.github.api.base")
      else System.setProperty("ssc.github.api.base", oldBase)
      server.stop(0)
