package scalascript.imports

import org.scalatest.funsuite.AnyFunSuite

class SscLibLockTest extends AnyFunSuite:

  test("empty lock parses and round-trips"):
    val yaml   = SscLibLock.toYaml(SscLibLock.empty)
    val parsed = SscLibLock.parseString(yaml).get
    assert(parsed.locked.isEmpty)

  test("parseString: reads locked map"):
    val yaml =
      """locked:
        |  "io.example/utils": "2.1.0"
        |  "io.scalascript/json": "1.0.0"
        |""".stripMargin
    val lock = SscLibLock.parseString(yaml).get
    assert(lock.locked("io.example/utils")    == "2.1.0")
    assert(lock.locked("io.scalascript/json") == "1.0.0")

  test("toYaml round-trip preserves entries"):
    val original = SscLibLock(Map("io.example/a" -> "1.0.0", "io.example/b" -> "2.3.0"))
    val yaml     = SscLibLock.toYaml(original)
    val parsed   = SscLibLock.parseString(yaml).get
    assert(parsed.locked == original.locked)

  test("toYaml sorts entries alphabetically"):
    val lock = SscLibLock(Map("io.z/z" -> "1.0", "io.a/a" -> "2.0", "io.m/m" -> "3.0"))
    val yaml = SscLibLock.toYaml(lock)
    val lines = yaml.linesIterator.filter(_.startsWith("  \"")).toList
    assert(lines(0).contains("io.a/a"))
    assert(lines(1).contains("io.m/m"))
    assert(lines(2).contains("io.z/z"))

  test("read: missing file returns Failure"):
    val result = SscLibLock.read(os.temp.dir() / "nonexistent.yaml")
    assert(result.isFailure)

  test("write / read round-trip"):
    val lock    = SscLibLock(Map("io.example/lib" -> "1.2.3"))
    val tmpDir  = os.temp.dir()
    try
      val path = tmpDir / SscLibLock.FileName
      SscLibLock.write(lock, path)
      val loaded = SscLibLock.read(path).get
      assert(loaded.locked == lock.locked)
    finally os.remove.all(tmpDir)

  test("withResolved adds and updates entries"):
    val lock = SscLibLock.empty
      .withResolved("io.example/a", "1.0.0")
      .withResolved("io.example/b", "2.0.0")
      .withResolved("io.example/a", "1.5.0")  // update
    assert(lock.locked("io.example/a") == "1.5.0")
    assert(lock.locked("io.example/b") == "2.0.0")

  // ── SemVer tests ──────────────────────────────────────────────────────────

  test("SemVer.compare: major version ordering"):
    assert(SemVer.compare("2.0.0", "1.0.0") > 0)
    assert(SemVer.compare("1.0.0", "2.0.0") < 0)
    assert(SemVer.compare("1.0.0", "1.0.0") == 0)

  test("SemVer.compare: minor version ordering"):
    assert(SemVer.compare("1.2.0", "1.1.0") > 0)
    assert(SemVer.compare("1.1.3", "1.1.10") < 0)  // numeric, not lexicographic

  test("SemVer.compare: patch version ordering"):
    assert(SemVer.compare("1.0.2", "1.0.1") > 0)

  test("SemVer.compare: different segment counts"):
    assert(SemVer.compare("1.0", "1") == 0)
    assert(SemVer.compare("1.1", "1.0.0") > 0)

  test("SemVer.max picks the higher version"):
    assert(SemVer.max("1.0.0", "2.0.0")  == "2.0.0")
    assert(SemVer.max("2.0.0", "1.0.0")  == "2.0.0")
    assert(SemVer.max("1.9.0", "1.10.0") == "1.10.0")

  // ── TransitiveResolution tests ────────────────────────────────────────────

  test("resolveAll: empty list returns empty lock"):
    val lock = scalascript.imports.ImportResolver.resolveAll(Nil)
    assert(lock.locked.isEmpty)

  test("resolveAll: non-dep URIs are ignored"):
    val lock = scalascript.imports.ImportResolver.resolveAll(
      List("https://example.com/foo.ssc", "pkg:some/lib"),
    )
    assert(lock.locked.isEmpty)
