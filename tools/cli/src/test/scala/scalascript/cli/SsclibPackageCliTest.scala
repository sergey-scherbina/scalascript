package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*

class SsclibPackageCliTest extends AnyFunSuite:

  test("package --lib --precompile adds scim interfaces under ir"):
    val root = os.temp.dir(prefix = "ssclib-precompile")
    try
      val out = root / "demo.ssclib"
      writeLib(root / "lib", "io.example/demo", "def kept: Int = 1")

      packageLib(List((root / "lib").toString, "--precompile", "-o", out.toString))

      val entries = zipEntries(out)
      assert(entries.contains("ssclib-manifest.yaml"))
      assert(entries.contains("src/main.ssc"))
      assert(entries.contains("ir/main.scim"))
    finally os.remove.all(root)

  test("checkSsclibCompat reports removed public symbols"):
    val root = os.temp.dir(prefix = "ssclib-compat")
    try
      val oldOut = root / "old.ssclib"
      val newOut = root / "new.ssclib"
      writeLib(root / "old", "io.example/demo", "def kept: Int = 1\ndef removed: Int = 2")
      writeLib(root / "new", "io.example/demo", "def kept: Int = 1")
      packageLib(List((root / "old").toString, "--precompile", "-o", oldOut.toString))
      packageLib(List((root / "new").toString, "--precompile", "-o", newOut.toString))

      val report = checkSsclibCompat(oldOut, newOut)
      assert(report.removed.exists(_.endsWith("removed")))
      assert(report.changed.isEmpty)
    finally os.remove.all(root)

  private def writeLib(dir: os.Path, name: String, scalaBody: String): Unit =
    os.makeDir.all(dir / "src")
    os.write.over(
      dir / "ssclib-manifest.yaml",
      s"""name: $name
         |version: 1.0.0
         |entry: src/main.ssc
         |scala-script-version: ">=1.60"
         |""".stripMargin
    )
    os.write.over(
      dir / "src" / "main.ssc",
      s"""# Main
         |
         |```scala
         |$scalaBody
         |```
         |""".stripMargin
    )

  private def zipEntries(path: os.Path): Set[String] =
    val zip = ZipFile(path.toIO)
    try zip.entries().asScala.map(_.getName).toSet
    finally zip.close()
