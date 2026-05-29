package scalascript.imports

import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.util.zip.{ZipEntry, ZipOutputStream}

class MavenDepResolverTest extends AnyFunSuite:

  test("MavenDepResolver detects Maven coordinates and leaves legacy dep-sources coordinates alone"):
    assert(MavenDepResolver.isMavenCoordinate("dep:com.example:demo:1.0.0"))
    assert(MavenDepResolver.isMavenCoordinate("dep:com.example::demo:1.0.0"))
    assert(!MavenDepResolver.isMavenCoordinate("dep:com.example/demo:1.0.0"))

  test("MavenDepResolver resolves artifact paths through a Coursier command"):
    withFakeCoursier { (artifact, log, command) =>
      val oldCommand = System.getProperty("ssc.coursier.command")
      val oldRepos = System.getProperty("ssc.coursier.repositories")
      try
        System.setProperty("ssc.coursier.command", command.toString)
        System.setProperty("ssc.coursier.repositories", s"file://${artifact / os.up / os.up / os.up / os.up / os.up}")
        val path = MavenDepResolver().resolve(
          scalascript.backend.spi.DepSpec(
            raw = "dep:com.example:demo:1.0.0",
            sha256 = Some(DepCache.sha256hex(os.read.bytes(artifact))),
            cacheRoot = os.temp.dir(prefix = "ssc-coursier-cache").toNIO,
          )
        )
        assert(os.Path(path) == artifact)
        val args = os.read(log)
        assert(args.contains("fetch"))
        assert(args.contains("--classpath"))
        assert(args.contains("com.example:demo:1.0.0"))
      finally
        restoreProperty("ssc.coursier.command", oldCommand)
        restoreProperty("ssc.coursier.repositories", oldRepos)
    }

  test("ImportResolver dispatches Maven-shaped dep URI through MavenDepResolver"):
    withFakeCoursier { (artifact, _, command) =>
      val oldCommand = System.getProperty("ssc.coursier.command")
      val oldRepos = System.getProperty("ssc.coursier.repositories")
      try
        System.setProperty("ssc.coursier.command", command.toString)
        System.setProperty("ssc.coursier.repositories", s"file://${artifact / os.up / os.up / os.up / os.up / os.up}")
        val resolved = ImportResolver.resolve("dep:com.example:demo:1.0.0", os.temp.dir(prefix = "ssc-import-base"))
        assert(resolved == artifact)
      finally
        restoreProperty("ssc.coursier.command", oldCommand)
        restoreProperty("ssc.coursier.repositories", oldRepos)
    }

  test("JitpackResolver delegates to Coursier with jitpack repository"):
    withFakeCoursier { (artifact, log, command) =>
      val oldCommand = System.getProperty("ssc.coursier.command")
      val oldRepos = System.getProperty("ssc.coursier.repositories")
      try
        System.setProperty("ssc.coursier.command", command.toString)
        System.setProperty("ssc.coursier.repositories", "")
        val path = JitpackResolver().resolve(
          scalascript.backend.spi.DepSpec(
            raw = "jitpack:com.github.owner:repo:v1.0.0",
            cacheRoot = os.temp.dir(prefix = "ssc-jitpack-cache").toNIO,
          )
        )
        assert(os.Path(path) == artifact)
        val args = os.read(log)
        assert(args.contains("--repository jitpack"))
        assert(args.contains("com.github.owner:repo:v1.0.0"))
      finally
        restoreProperty("ssc.coursier.command", oldCommand)
        restoreProperty("ssc.coursier.repositories", oldRepos)
    }

  private def withFakeCoursier(body: (os.Path, os.Path, os.Path) => Unit): Unit =
    val root = os.temp.dir(prefix = "ssc-maven-fixture")
    try
      val artifact = root / "repo" / "com" / "example" / "demo" / "1.0.0" / "demo-1.0.0.jar"
      os.makeDir.all(artifact / os.up)
      writeJar(artifact)
      os.write(
        artifact / os.up / "demo-1.0.0.pom",
        """<project>
          |  <modelVersion>4.0.0</modelVersion>
          |  <groupId>com.example</groupId>
          |  <artifactId>demo</artifactId>
          |  <version>1.0.0</version>
          |</project>
          |""".stripMargin,
      )
      val log = root / "coursier.args"
      val fake = root / "cs"
      os.write(
        fake,
        s"""#!/usr/bin/env sh
           |printf '%s\\n' "$$*" > '${log.toString}'
           |printf '%s\\n' '${artifact.toString}'
           |""".stripMargin,
      )
      os.perms.set(fake, "rwxr-xr-x")
      body(artifact, log, fake)
    finally os.remove.all(root)

  private def writeJar(path: os.Path): Unit =
    val zos = ZipOutputStream(java.io.FileOutputStream(path.toIO))
    try
      zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
      zos.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8))
      zos.closeEntry()
    finally zos.close()

  private def restoreProperty(name: String, oldValue: String | Null): Unit =
    if oldValue == null then System.clearProperty(name)
    else System.setProperty(name, oldValue)
