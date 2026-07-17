package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

class StandaloneInstallFixturesTest extends AnyFunSuite:
  test("root install.sh defaults to standalone guidance; --dev is the monorepo build path"):
    val script = os.read(repoRoot / "install.sh")
    assert(script.contains("./install.sh --dev"))
    assert(script.contains("cs install ssc --channel https://releases.scalascript.io/coursier.json"))
    assert(script.contains("brew install scalascript/tap/ssc"))
    assert(script.contains("curl -fsSL https://get.scalascript.io | sh"))
    assert(script.contains("""case "${1:-}" in"""))

  test("standalone release fixtures provide the three documented install channels"):
    val root = repoRoot
    val coursier = os.read(root / "releases" / "coursier.json")
    val curlInstaller = os.read(root / "releases" / "install.sh")
    val homebrew = os.read(root / "releases" / "homebrew" / "ssc.rb")

    assert(coursier.contains("\"name\": \"ssc\""))
    assert(coursier.contains("\"io.scalascript:scalascript-cli_3:0.1.0\""))
    assert(curlInstaller.contains("https://github.com/sergey-scherbina/scalascript/releases/download"))
    assert(curlInstaller.contains("""exec java -Xss"\${SSC_XSS:-64m}" -jar"""))
    assert(curlInstaller.contains("\"$LIB_DIR/ssc.jar\""))
    assert(homebrew.contains("class Ssc < Formula"))
    assert(homebrew.contains("REPLACE_WITH_RELEASE_SHA256"))

  private def repoRoot: os.Path =
    Iterator.iterate(os.pwd)(_ / os.up)
      .take(12)
      .find(p => os.exists(p / "build.sbt") && os.exists(p / "releases" / "install.sh"))
      .getOrElse(throw new RuntimeException(s"could not locate repo root from ${os.pwd}"))
