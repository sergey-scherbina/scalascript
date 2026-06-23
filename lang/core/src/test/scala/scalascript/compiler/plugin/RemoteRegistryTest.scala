package scalascript.compiler.plugin

import org.scalatest.funsuite.AnyFunSuite

class RemoteRegistryTest extends AnyFunSuite:

  private def freshRoot(): os.Path = os.temp.dir(prefix = "ssc-remote-registry-")

  private def bytes(s: String): Array[Byte] = s.getBytes("UTF-8")

  test("publish records an entry with the artifact's SHA-256"):
    val reg = FileRegistry(freshRoot())
    val e   = reg.publish("org.example.redis", "1.0.0", bytes("REDIS-PKG-v1"), "Redis client")
    assert(e.id == "org.example.redis" && e.version == "1.0.0")
    assert(e.sha256 == RemoteRegistry.sha256Hex(bytes("REDIS-PKG-v1")))
    assert(reg.index.contains(e))

  test("round-trip: publish → search → resolve → fetch (checksum verified)"):
    val reg = FileRegistry(freshRoot())
    reg.publish("org.example.redis", "1.0.0", bytes("v1"), "Redis client for ScalaScript")
    reg.publish("org.example.kafka", "2.3.0", bytes("kafka"), "Kafka streams")
    // search by id substring and by description substring
    assert(reg.search("redis").map(_.id) == List("org.example.redis"))
    assert(reg.search("streams").map(_.id) == List("org.example.kafka"))
    assert(reg.search("").map(_.id).toSet == Set("org.example.redis", "org.example.kafka"))
    // resolve + fetch
    val resolved = reg.resolve("org.example.redis", "1.0.0").get
    val fetched  = reg.fetch("org.example.redis", "1.0.0").get
    assert(new String(fetched, "UTF-8") == "v1")
    assert(RemoteRegistry.sha256Hex(fetched) == resolved.sha256)

  test("resolve latest picks the highest version (numeric, not lexical)"):
    val reg = FileRegistry(freshRoot())
    reg.publish("p", "1.2.9",  bytes("a"))
    reg.publish("p", "1.2.10", bytes("b"))   // 1.2.10 > 1.2.9 numerically
    reg.publish("p", "1.2.2",  bytes("c"))
    assert(reg.resolve("p").get.version == "1.2.10")
    assert(reg.resolve("p", "latest").get.version == "1.2.10")
    assert(reg.resolve("p", "1.2.9").get.version == "1.2.9")
    assert(reg.versions("p") == List("1.2.2", "1.2.9", "1.2.10"))

  test("resolve / fetch return None for an unknown package or version"):
    val reg = FileRegistry(freshRoot())
    reg.publish("p", "1.0.0", bytes("x"))
    assert(reg.resolve("missing").isEmpty)
    assert(reg.resolve("p", "9.9.9").isEmpty)
    assert(reg.fetch("p", "9.9.9").isEmpty)

  test("releases are immutable: re-publish identical = idempotent, different = rejected"):
    val reg = FileRegistry(freshRoot())
    val first = reg.publish("p", "1.0.0", bytes("same"))
    val again = reg.publish("p", "1.0.0", bytes("same"))   // idempotent
    assert(first == again)
    assert(reg.index.count(e => e.id == "p" && e.version == "1.0.0") == 1)
    val ex = intercept[IllegalStateException](reg.publish("p", "1.0.0", bytes("DIFFERENT")))
    assert(ex.getMessage.contains("different checksum"))

  test("the JSON index round-trips and persists across registry instances"):
    val root = freshRoot()
    FileRegistry(root).publish("p", "1.0.0", bytes("x"), "desc")
    // a fresh instance over the same root sees the published catalog (it's on disk as JSON)
    val reopened = FileRegistry(root)
    assert(reopened.resolve("p", "1.0.0").map(_.description).contains("desc"))
    assert(RemoteRegistry.indexFromJson(RemoteRegistry.indexToJson(reopened.index)) == reopened.index)

  test("compareVersions orders dotted-numeric versions"):
    import RemoteRegistry.compareVersions
    assert(compareVersions("1.2.10", "1.2.9") > 0)
    assert(compareVersions("1.0.0", "1.0.0") == 0)
    assert(compareVersions("2.0", "1.9.9") > 0)
    assert(compareVersions("1.2", "1.2.0") < 0)   // shorter prefix is smaller

  // slice 2 — the packages.yaml bridge: a FileRegistry-served directory is consumable by the EXISTING
  // client (LocalRegistry/RegistryClient/ssc search), which reads `packages.yaml`.
  // slice 3 — `ssc plugin registry publish`: read id/version from a real .sscpkg manifest, publish into a
  // FileRegistry, regenerate packages.yaml. (Tests the substantive logic the CLI command wires together.)
  private def writeSscpkg(dir: os.Path, name: String, manifestYaml: String): os.Path =
    val p = dir / name
    val zos = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(p.toNIO))
    zos.putNextEntry(new java.util.zip.ZipEntry("manifest.yaml"))
    zos.write(manifestYaml.getBytes("UTF-8"))
    zos.closeEntry()
    zos.close()
    p

  test("publish flow: loadManifest a .sscpkg, FileRegistry.publish, regenerate packages.yaml"):
    val work = freshRoot()
    val pkg  = writeSscpkg(work, "foo-2.1.0.sscpkg",
      "id: org.example.foo\nversion: 2.1.0\nkind:\n  - plugin\n")
    // read id/version from the package manifest (what the CLI does)
    val manifest = SscpkgLoader.loadManifest(pkg)
    assert(manifest.id == "org.example.foo" && manifest.version == "2.1.0")
    // publish into a server-side registry
    val regRoot = freshRoot() / "registry"
    val reg     = FileRegistry(regRoot)
    val entry   = reg.publish(manifest.id, manifest.version, os.read.bytes(pkg), "Foo plugin")
    assert(entry.sha256 == RemoteRegistry.sha256Hex(os.read.bytes(pkg)))
    assert(reg.fetch("org.example.foo", "2.1.0").map(_.toSeq).contains(os.read.bytes(pkg).toSeq))
    // regenerate the client-facing packages.yaml; the existing client parser sees the published package
    val yamlPath = reg.writePackagesYaml("https://reg.example.com")
    val clientEntry = LocalRegistry.resolve("org.example.foo", List(yamlPath)).get
    assert(clientEntry.version == "2.1.0")
    assert(clientEntry.url == "https://reg.example.com/packages/org.example.foo/2.1.0.sscpkg")

  test("exportPackagesYaml emits the client format, latest version per id, round-trips via LocalRegistry"):
    val root = freshRoot()
    val reg  = FileRegistry(root)
    reg.publish("org.example.redis", "1.0.0", bytes("r1"), "Redis client")
    reg.publish("org.example.redis", "1.2.0", bytes("r2"), "Redis client")   // newer → wins in packages.yaml
    reg.publish("org.example.kafka", "2.3.0", bytes("k"),  "Kafka streams")
    val p = reg.writePackagesYaml("https://registry.example.com")
    // the EXISTING client parser reads it
    val entries = LocalRegistry.parseFile(p).get.sortBy(_.id)
    assert(entries.map(_.id) == List("org.example.kafka", "org.example.redis"))
    val redis = entries.find(_.id == "org.example.redis").get
    assert(redis.version == "1.2.0")   // latest, not 1.0.0
    assert(redis.url == "https://registry.example.com/packages/org.example.redis/1.2.0.sscpkg")
    assert(redis.description == "Redis client")
    // and LocalRegistry.resolve (the client alias lookup) finds it
    assert(LocalRegistry.resolve("org.example.kafka", List(p)).map(_.version).contains("2.3.0"))
