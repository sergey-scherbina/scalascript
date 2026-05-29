package scalascript.imports

import org.scalatest.funsuite.AnyFunSuite

/** arch-registry-p4 — private registry support tests:
 *  `registry.url` config key, `--registry <url>` via effectiveUrl,
 *  `file://` URL fetch, and config.yaml reading. */
class RegistryPrivateTest extends AnyFunSuite:

  private val fixtureYaml =
    """- name: io.private/lib-a
      |  version: 1.0.0
      |  description: "Private library A"
      |- name: io.private/lib-b
      |  version: 2.0.0
      |  description: "Private library B"
      |""".stripMargin

  // ── file:// URL support ────────────────────────────────────────────────

  test("RegistryClient.fetchAndCache: supports file:// URL for local registry") {
    RegistryClient.clearCache()
    val tmpFile = os.temp(suffix = ".yaml")
    try
      os.write.over(tmpFile, fixtureYaml)
      val fileUrl = tmpFile.toIO.toURI.toString   // file:///...
      val entries = RegistryClient.fetchAndCache(fileUrl)
      assert(entries.length == 2, s"expected 2 entries; got ${entries.length}")
      assert(entries.exists(_.name == "io.private/lib-a"))
      assert(entries.exists(_.name == "io.private/lib-b"))
    finally
      os.remove(tmpFile)
      RegistryClient.clearCache()
  }

  test("RegistryClient.load: file:// URL loads and caches entries") {
    RegistryClient.clearCache()
    val tmpFile = os.temp(suffix = ".yaml")
    try
      os.write.over(tmpFile, fixtureYaml)
      val fileUrl = tmpFile.toIO.toURI.toString
      val entries = RegistryClient.load(fileUrl, refresh = true)
      assert(entries.length == 2)
      assert(entries.head.name == "io.private/lib-a")
    finally
      os.remove(tmpFile)
      RegistryClient.clearCache()
  }

  // ── effectiveUrl: URL priority ─────────────────────────────────────────

  test("RegistryClient.effectiveUrl: explicit arg takes highest priority") {
    val url = RegistryClient.effectiveUrl(Some("https://my.registry/packages.yaml"))
    assert(url == "https://my.registry/packages.yaml")
  }

  test("RegistryClient.effectiveUrl: falls back to default when no arg and no config") {
    val configPath = RegistryClient.ConfigFile
    val hadConfig  = os.exists(configPath)
    if hadConfig then cancel("config.yaml present — skip default-fallback test")
    val url = RegistryClient.effectiveUrl(None)
    assert(url == RegistryClient.DefaultRegistryUrl)
  }

  // ── registry.url config ────────────────────────────────────────────────

  test("RegistryClient.registryUrlFromConfig: returns None when config file absent") {
    val configPath = RegistryClient.ConfigFile
    if os.exists(configPath) then cancel("config.yaml present — skip missing-file test")
    assert(RegistryClient.registryUrlFromConfig().isEmpty)
  }

  test("RegistryClient.registryUrlFromConfig: reads registry.url from config.yaml") {
    val configPath = RegistryClient.ConfigFile
    val oldContent = if os.exists(configPath) then Some(os.read(configPath)) else None
    try
      os.makeDir.all(configPath / os.up)
      os.write.over(configPath, "registry.url: https://internal.corp/packages.yaml\n")
      val url = RegistryClient.registryUrlFromConfig()
      assert(url.contains("https://internal.corp/packages.yaml"),
        s"expected config URL; got $url")
    finally
      oldContent match
        case Some(c) => os.write.over(configPath, c)
        case None    => if os.exists(configPath) then os.remove(configPath)
  }

  test("RegistryClient.effectiveUrl: config file URL beats default") {
    val configPath = RegistryClient.ConfigFile
    val oldContent = if os.exists(configPath) then Some(os.read(configPath)) else None
    try
      os.makeDir.all(configPath / os.up)
      os.write.over(configPath, "registry.url: https://internal.corp/packages.yaml\n")
      val url = RegistryClient.effectiveUrl(None)
      assert(url == "https://internal.corp/packages.yaml",
        s"config URL should beat default; got $url")
    finally
      oldContent match
        case Some(c) => os.write.over(configPath, c)
        case None    => if os.exists(configPath) then os.remove(configPath)
  }

  test("RegistryClient.effectiveUrl: explicit arg beats config file") {
    val configPath = RegistryClient.ConfigFile
    val oldContent = if os.exists(configPath) then Some(os.read(configPath)) else None
    try
      os.makeDir.all(configPath / os.up)
      os.write.over(configPath, "registry.url: https://internal.corp/packages.yaml\n")
      val url = RegistryClient.effectiveUrl(Some("https://cli-override.io/packages.yaml"))
      assert(url == "https://cli-override.io/packages.yaml",
        s"CLI arg should beat config URL; got $url")
    finally
      oldContent match
        case Some(c) => os.write.over(configPath, c)
        case None    => if os.exists(configPath) then os.remove(configPath)
  }

  // ── ssc search --registry loads from custom URL ────────────────────────

  test("RegistryClient: search on file:// entries returns correct results") {
    val entries = RegistryEntry.parseAll(fixtureYaml).toOption.get
    val results = RegistryClient.search("lib-a", entries)
    assert(results.nonEmpty, "lib-a search should match")
    assert(results.head.name == "io.private/lib-a")
  }
