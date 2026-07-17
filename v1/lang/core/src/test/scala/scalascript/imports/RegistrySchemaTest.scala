package scalascript.imports

import org.scalatest.funsuite.AnyFunSuite

/** arch-registry-p1 — packages.yaml schema parsing and validation tests. */
class RegistrySchemaTest extends AnyFunSuite:

  private def registrySeedCandidates(starts: Seq[os.Path]): List[os.Path] =
    starts.iterator
      .flatMap(start => Iterator.iterate(start)(_ / os.up).take(start.segments.length + 1))
      .map(_ / "registry" / "packages.yaml")
      .toList
      .distinct

  private def registrySearchRoots: List[os.Path] =
    val classLocation = scala.util.Try {
      os.Path(getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
    }.toOption
    (List(os.pwd, os.Path(sys.props("user.dir"))) ++ classLocation).distinct

  // ── parseAllRaw / parseAll ────────────────────────────────────────────

  test("RegistryEntry.parseAll: minimal valid entry (name + version)") {
    val yaml =
      """- name: io.example/minimal
        |  version: 1.0.0
        |""".stripMargin
    val result = RegistryEntry.parseAll(yaml)
    assert(result.isRight, s"parse failed: $result")
    val entries = result.toOption.get
    assert(entries.length == 1)
    assert(entries.head.name    == "io.example/minimal")
    assert(entries.head.version == "1.0.0")
  }

  test("RegistryEntry.parseAll: full-featured entry round-trips") {
    val yaml =
      """- name: io.example/full
        |  version: 2.3.1
        |  description: "A full-featured library"
        |  keywords: [json, utils]
        |  backends: [jvm, js]
        |  url: "github:example/full@v2.3.1"
        |  license: MIT
        |  author: "Alice Example"
        |  homepage: "https://example.com/full"
        |  changelog: "https://example.com/full/CHANGELOG.md"
        |  scala-script-version: ">=1.60"
        |""".stripMargin
    val result = RegistryEntry.parseAll(yaml)
    assert(result.isRight, s"parse failed: $result")
    val e = result.toOption.get.head
    assert(e.name               == "io.example/full")
    assert(e.version            == "2.3.1")
    assert(e.description        == "A full-featured library")
    assert(e.keywords           == List("json", "utils"))
    assert(e.backends           == List("jvm", "js"))
    assert(e.url                == "github:example/full@v2.3.1")
    assert(e.license            == "MIT")
    assert(e.author             == "Alice Example")
    assert(e.homepage           == "https://example.com/full")
    assert(e.changelog          == "https://example.com/full/CHANGELOG.md")
    assert(e.scalaScriptVersion == ">=1.60")
    assert(!e.deprecated)
  }

  test("RegistryEntry.parseAll: multiple entries parsed correctly") {
    val yaml =
      """- name: io.example/lib-a
        |  version: 1.0.0
        |- name: io.example/lib-b
        |  version: 2.0.0
        |- name: io.example/lib-c
        |  version: 0.1.0
        |""".stripMargin
    val result = RegistryEntry.parseAll(yaml)
    assert(result.isRight, s"parse failed: $result")
    val names = result.toOption.get.map(_.name)
    assert(names == List("io.example/lib-a", "io.example/lib-b", "io.example/lib-c"))
  }

  test("RegistryEntry.parseAll: deprecated flag parsed") {
    val yaml =
      """- name: io.example/old
        |  version: 1.0.0
        |  deprecated: true
        |""".stripMargin
    val result = RegistryEntry.parseAll(yaml)
    assert(result.isRight)
    assert(result.toOption.get.head.deprecated)
  }

  test("RegistryEntry.parseAll: empty YAML returns empty list") {
    assert(RegistryEntry.parseAll("").isRight)
    assert(RegistryEntry.parseAll("").toOption.get.isEmpty)
  }

  // ── validate ─────────────────────────────────────────────────────────

  test("RegistryEntry.validate: valid entry returns None") {
    val e = RegistryEntry(name = "io.example/lib", version = "1.0.0")
    assert(RegistryEntry.validate(e).isEmpty)
  }

  test("RegistryEntry.validate: name without slash is invalid") {
    val e = RegistryEntry(name = "badname", version = "1.0.0")
    val errs = RegistryEntry.validate(e).getOrElse(Nil)
    assert(errs.exists(_.contains("<group>/<artifact>")), s"expected group/artifact error; got $errs")
  }

  test("RegistryEntry.validate: non-semver version is invalid") {
    val e = RegistryEntry(name = "io.example/lib", version = "latest")
    val errs = RegistryEntry.validate(e).getOrElse(Nil)
    assert(errs.exists(_.contains("semver")), s"expected semver error; got $errs")
  }

  test("RegistryEntry.validate: disallowed URL scheme is invalid") {
    val e = RegistryEntry(name = "io.example/lib", version = "1.0.0", url = "ftp://example.com/lib.ssclib")
    val errs = RegistryEntry.validate(e).getOrElse(Nil)
    assert(errs.exists(_.contains("url scheme")), s"expected url scheme error; got $errs")
  }

  test("RegistryEntry.validate: allowed URL schemes pass") {
    for scheme <- List("github:", "jitpack:", "dep:", "https://") do
      val e = RegistryEntry(name = "io.example/lib", version = "1.0.0",
        url = s"${scheme}example/lib@v1.0.0")
      assert(RegistryEntry.validate(e).isEmpty, s"scheme '$scheme' should be allowed")
  }

  test("RegistryEntry.validate: non-https homepage is invalid") {
    val e = RegistryEntry(name = "io.example/lib", version = "1.0.0",
      homepage = "ftp://example.com")
    val errs = RegistryEntry.validate(e).getOrElse(Nil)
    assert(errs.exists(_.contains("homepage")), s"expected homepage error; got $errs")
  }

  test("RegistryEntry.parseAll: missing required 'name' field returns Left") {
    val yaml =
      """- version: 1.0.0
        |  description: no name here
        |""".stripMargin
    val result = RegistryEntry.parseAll(yaml)
    assert(result.isLeft, s"expected Left for missing name; got $result")
    assert(result.left.toOption.get.exists(_.contains("'name'")))
  }

  test("RegistryEntry.parseAll: missing required 'version' field returns Left") {
    val yaml =
      """- name: io.example/no-version
        |  description: no version here
        |""".stripMargin
    val result = RegistryEntry.parseAll(yaml)
    assert(result.isLeft, s"expected Left for missing version; got $result")
    assert(result.left.toOption.get.exists(_.contains("'version'")))
  }

  // ── toYaml round-trip ─────────────────────────────────────────────────

  test("RegistryEntry.toYaml / parseAll round-trip") {
    val entries = List(
      RegistryEntry(
        name        = "io.example/alpha",
        version     = "1.0.0",
        description = "Alpha library",
        keywords    = List("a", "b"),
        backends    = List("jvm"),
        url         = "github:example/alpha@v1.0.0",
        license     = "Apache-2.0",
        homepage    = "https://example.com/alpha",
        scalaScriptVersion = ">=1.60",
      ),
      RegistryEntry(name = "io.example/beta", version = "0.2.0"),
    )
    val yaml   = RegistryEntry.toYaml(entries)
    val result = RegistryEntry.parseAll(yaml)
    assert(result.isRight, s"round-trip parse failed: $result\nyaml:\n$yaml")
    val reparsed = result.toOption.get
    assert(reparsed.length == entries.length)
    assert(reparsed.head.name        == "io.example/alpha")
    assert(reparsed.head.description == "Alpha library")
    assert(reparsed.head.keywords    == List("a", "b"))
    assert(reparsed.head.backends    == List("jvm"))
    assert(reparsed(1).name          == "io.example/beta")
    assert(reparsed(1).version       == "0.2.0")
  }

  // ── seed registry/packages.yaml parses cleanly ────────────────────────

  test("seed registry/packages.yaml parses and validates without errors") {
    val searched = registrySeedCandidates(registrySearchRoots)
    val yamlPath = searched.find(os.isFile).getOrElse:
      fail(s"tracked registry/packages.yaml not found; searched:\n${searched.mkString("  ", "\n  ", "")}")

    // Pin the CI shape explicitly: aggregate sbt may execute this suite with
    // user.dir at the module base rather than at the repository root.
    val repoRoot = yamlPath / os.up / os.up
    val moduleCwd = repoRoot / "v1" / "lang" / "core"
    assert(registrySeedCandidates(List(moduleCwd)).exists(p => os.isFile(p) && p == yamlPath),
      s"module-CWD search did not resolve tracked seed $yamlPath")

    val result = RegistryEntry.parseAll(os.read(yamlPath))
    assert(result.isRight,
      s"seed registry/packages.yaml failed validation:\n${result.left.getOrElse(Nil).mkString("\n")}")
    val entries = result.toOption.get
    assert(entries.nonEmpty, "seed registry should have at least one entry")
    assert(entries.exists(_.name.startsWith("io.scalascript/")),
      "seed should contain at least one io.scalascript/* first-party entry")
  }
