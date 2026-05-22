package scalascript.compiler.plugin

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Tests for v1.7 Tier 6 — local registry stub. */
class LocalRegistryTest extends AnyFunSuite with Matchers:

  private def writeRegistry(content: String): os.Path =
    val p = os.temp(suffix = ".yaml")
    os.write.over(p, content)
    p

  test("resolve finds an entry by full id"):
    val reg = writeRegistry("""
      packages:
        org.example.redis:
          url: https://example.com/redis-1.0.sscpkg
          version: 1.0.0
          description: Redis client
    """)
    val entry = LocalRegistry.resolve("org.example.redis", List(reg))
    entry shouldBe defined
    entry.get.url     shouldBe "https://example.com/redis-1.0.sscpkg"
    entry.get.version shouldBe "1.0.0"

  test("resolve finds entry by short alias"):
    val reg = writeRegistry("""
      packages:
        redis:
          url: https://example.com/redis-1.0.sscpkg
          version: 1.0.0
    """)
    LocalRegistry.resolve("redis", List(reg)) shouldBe defined

  test("resolve returns None for unknown name"):
    val reg = writeRegistry("packages:\n  foo:\n    url: https://example.com/foo.sscpkg\n")
    LocalRegistry.resolve("bar", List(reg)) shouldBe empty

  test("loadAll merges multiple registry files"):
    val reg1 = writeRegistry("packages:\n  a:\n    url: https://example.com/a.sscpkg\n")
    val reg2 = writeRegistry("packages:\n  b:\n    url: https://example.com/b.sscpkg\n")
    val entries = LocalRegistry.loadAll(List(reg1, reg2))
    entries.map(_.id) should contain allOf ("a", "b")

  test("later registry wins on id collision"):
    val reg1 = writeRegistry("packages:\n  pkg:\n    url: https://example.com/old.sscpkg\n    version: 1.0\n")
    val reg2 = writeRegistry("packages:\n  pkg:\n    url: https://example.com/new.sscpkg\n    version: 2.0\n")
    val entry = LocalRegistry.resolve("pkg", List(reg1, reg2))
    entry.get.url     shouldBe "https://example.com/new.sscpkg"
    entry.get.version shouldBe "2.0"

  test("missing registry file is silently skipped"):
    val absent = os.temp.dir() / "nonexistent.yaml"
    LocalRegistry.loadAll(List(absent)) shouldBe empty

  test("empty YAML file returns empty list"):
    val reg = writeRegistry("")
    LocalRegistry.loadAll(List(reg)) shouldBe empty

  test("toYaml round-trips entries"):
    val entries = List(
      LocalRegistry.Entry("org.example.foo", "https://x.com/foo.sscpkg", "1.0.0", "Foo package"),
      LocalRegistry.Entry("org.example.bar", "https://x.com/bar.sscpkg", "2.0.0"),
    )
    val yaml = LocalRegistry.toYaml(entries)
    yaml should include ("org.example.foo")
    yaml should include ("https://x.com/foo.sscpkg")
    yaml should include ("Foo package")
    yaml should include ("org.example.bar")
    // Round-trip: parse the YAML back
    val reg = writeRegistry(yaml)
    val loaded = LocalRegistry.loadAll(List(reg))
    loaded.map(_.id)  should contain allOf ("org.example.foo", "org.example.bar")
    loaded.find(_.id == "org.example.foo").get.url shouldBe "https://x.com/foo.sscpkg"
