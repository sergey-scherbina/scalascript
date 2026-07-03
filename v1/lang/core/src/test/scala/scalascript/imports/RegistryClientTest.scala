package scalascript.imports

import org.scalatest.funsuite.AnyFunSuite
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.InetSocketAddress

/** arch-registry-p2 — RegistryClient fetch, cache, search tests. */
class RegistryClientTest extends AnyFunSuite:

  private val fixtureYaml =
    """- name: io.scalascript/json
      |  version: 1.0.0
      |  description: "Built-in JSON support"
      |  keywords: [json, codec]
      |  backends: [jvm, js]
      |  url: "github:scalascript/scalascript@v1.0.0"
      |  license: Apache-2.0
      |- name: io.example/json-extra
      |  version: 1.2.0
      |  description: "Extra JSON utilities"
      |  keywords: [json, serialization]
      |  backends: [jvm, js]
      |  url: "github:example/json-extra@v1.2.0"
      |- name: io.example/http-client
      |  version: 2.0.0
      |  description: "HTTP client library"
      |  keywords: [http, web, client]
      |  backends: [jvm, js]
      |- name: io.other/deprecated-lib
      |  version: 0.1.0
      |  description: "Old library"
      |  deprecated: true
      |""".stripMargin

  private def withRegistryServer(yaml: String)(body: String => Unit): Unit =
    val bytes  = yaml.getBytes("UTF-8")
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port   = server.getAddress.getPort
    server.createContext("/packages.yaml", new HttpHandler:
      override def handle(ex: HttpExchange): Unit =
        ex.sendResponseHeaders(200, bytes.length.toLong)
        val out = ex.getResponseBody
        try out.write(bytes)
        finally out.close()
    )
    server.start()
    try body(s"http://127.0.0.1:$port/packages.yaml")
    finally server.stop(0)

  // ── fetchAndCache ──────────────────────────────────────────────────────

  test("RegistryClient.fetchAndCache: fetches from mock server and returns entries") {
    RegistryClient.clearCache()
    withRegistryServer(fixtureYaml) { url =>
      val entries = RegistryClient.fetchAndCache(url)
      assert(entries.length == 4, s"expected 4 entries; got ${entries.length}")
      assert(entries.exists(_.name == "io.scalascript/json"))
      assert(entries.exists(_.name == "io.example/json-extra"))
      RegistryClient.clearCache()
    }
  }

  test("RegistryClient.fetchAndCache: writes cache file") {
    RegistryClient.clearCache()
    withRegistryServer(fixtureYaml) { url =>
      RegistryClient.fetchAndCache(url)
      assert(RegistryClient.isCacheFresh, "cache should be fresh immediately after fetch")
      assert(RegistryClient.cacheTimestamp.isDefined, "cache timestamp should be written")
      RegistryClient.clearCache()
    }
  }

  // ── load with TTL ──────────────────────────────────────────────────────

  test("RegistryClient.load: returns cached entries when cache is fresh") {
    RegistryClient.clearCache()
    withRegistryServer(fixtureYaml) { url =>
      // Prime the cache.
      val first = RegistryClient.fetchAndCache(url)
      assert(first.nonEmpty)
      // Second load should hit cache without contacting server (server still running, so both would work, but we verify it returns same data).
      val second = RegistryClient.load(url)
      assert(second.length == first.length, "second load should return same entries from cache")
      RegistryClient.clearCache()
    }
  }

  test("RegistryClient.load with refresh=true: bypasses cache") {
    RegistryClient.clearCache()
    withRegistryServer(fixtureYaml) { url =>
      RegistryClient.fetchAndCache(url)
      val refreshed = RegistryClient.load(url, refresh = true)
      assert(refreshed.length == 4)
      RegistryClient.clearCache()
    }
  }

  test("RegistryClient.isCacheFresh: false when no cache file exists") {
    RegistryClient.clearCache()
    assert(!RegistryClient.isCacheFresh)
  }

  // ── search ─────────────────────────────────────────────────────────────

  private lazy val allEntries: List[RegistryEntry] =
    RegistryEntry.parseAll(fixtureYaml).toOption.get

  test("RegistryClient.search: empty query returns all entries") {
    val result = RegistryClient.search("", allEntries)
    assert(result.length == allEntries.length)
  }

  test("RegistryClient.search: exact name match scores highest") {
    val result = RegistryClient.search("io.scalascript/json", allEntries)
    assert(result.nonEmpty)
    assert(result.head.name == "io.scalascript/json")
  }

  test("RegistryClient.search: substring in name returns matches") {
    val result = RegistryClient.search("json", allEntries)
    assert(result.length == 2, s"expected 2 json entries; got ${result.map(_.name)}")
    assert(result.forall(e => e.name.contains("json") || e.description.contains("json") || e.keywords.exists(_.contains("json"))))
  }

  test("RegistryClient.search: keyword match") {
    val result = RegistryClient.search("serialization", allEntries)
    assert(result.exists(_.name == "io.example/json-extra"),
      s"serialization keyword should match json-extra; got ${result.map(_.name)}")
  }

  test("RegistryClient.search: no match returns empty list") {
    val result = RegistryClient.search("nonexistent-xyz-abc", allEntries)
    assert(result.isEmpty)
  }

  test("RegistryClient.search: http matches http-client") {
    val result = RegistryClient.search("http", allEntries)
    assert(result.exists(_.name == "io.example/http-client"),
      s"http should match http-client; got ${result.map(_.name)}")
  }

  // ── formatRow / formatInfo ─────────────────────────────────────────────

  test("RegistryClient.formatRow: includes name, version, description, backends") {
    val e = RegistryEntry(
      name        = "io.example/lib",
      version     = "1.0.0",
      description = "A test library",
      backends    = List("jvm", "js"),
    )
    val row = RegistryClient.formatRow(e)
    assert(row.contains("io.example/lib"))
    assert(row.contains("1.0.0"))
    assert(row.contains("A test library"))
    assert(row.contains("jvm") && row.contains("js"))
  }

  test("RegistryClient.formatRow: deprecated entry includes [DEPRECATED]") {
    val e = RegistryEntry(name = "io.example/old", version = "0.1.0", deprecated = true)
    assert(RegistryClient.formatRow(e).contains("[DEPRECATED]"))
  }

  test("RegistryClient.formatInfo: includes install snippet") {
    val e = RegistryEntry(
      name        = "io.example/lib",
      version     = "2.0.0",
      description = "Some library",
      author      = "Alice",
      license     = "MIT",
      backends    = List("jvm"),
      homepage    = "https://example.com/lib",
      scalaScriptVersion = ">=1.60",
      url         = "github:example/lib@v2.0.0",
    )
    val info = RegistryClient.formatInfo(e)
    assert(info.contains("io.example/lib 2.0.0"))
    assert(info.contains("Alice"))
    assert(info.contains("MIT"))
    assert(info.contains("jvm"))
    assert(info.contains("ssc >=1.60"))
    assert(info.contains("github:example/lib@v2.0.0"))
    assert(info.contains("https://example.com/lib"))
    assert(info.contains("dep:io.example/lib:2.0.0"))
  }
