package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

class StandaloneInstallFixturesTest extends AnyFunSuite:
  // WHAT THIS GUARDS, AND WHY IT IS NOT WHAT IT USED TO GUARD. Until 2026-08-18 these two tests
  // asserted four install channels — a coursier channel at releases.scalascript.io, a
  // scalascript/tap Homebrew tap, get.scalascript.io, and a jar-based releases/install.sh. Not one
  // of them worked: `dc39442c8` measured all four (two domains that do not resolve, a tap that
  // 404s, nothing ever published to Central) and deleted them, along with releases/coursier.json
  // and releases/homebrew/ssc.rb. The tests kept demanding all of it and went red — a guard
  // outliving the thing it guarded, which reads exactly like a regression and is not one.
  //
  // So they now assert what a user is ACTUALLY told to run and what the installer they are handed
  // ACTUALLY does. Every string below was read out of the file it is asserted against.

  test("root install.sh points at the one install route that exists, and --dev is the monorepo path"):
    val script = os.read(repoRoot / "install.sh")
    // The standalone route: the raw-GitHub URL of the installer, and the pinned/relocated form.
    assert(script.contains(
      "https://raw.githubusercontent.com/sergey-scherbina/scalascript/main/releases/install.sh"))
    assert(script.contains("SSC_VERSION=") && script.contains("PREFIX="))
    // …and the archive, for a user who would rather not pipe a script into a shell.
    assert(script.contains("https://github.com/sergey-scherbina/scalascript/releases/latest"))
    // The contributor route, and the dispatch that separates the two.
    assert(script.contains("./install.sh --dev"))
    assert(script.contains("""case "${1:-}" in"""))
    // THE ANTI-ASSERTIONS: the deleted channels must not come back without this test being read.
    // A route that cannot work is worse than no route — that is what dc39442c8 was for.
    assert(!script.contains("cs install ssc --channel"))
    assert(!script.contains("brew install scalascript/tap/ssc"))
    assert(!script.contains("curl -fsSL https://get.scalascript.io"))

  test("the standalone installer fetches a per-platform native binary and verifies it"):
    val installer = os.read(repoRoot / "releases" / "install.sh")
    // No version constant: GitHub serves /releases/latest/download, and a pin goes through SSC_VERSION.
    assert(installer.contains("releases/latest/download"))
    assert(installer.contains("releases/download/v$SSC_VERSION"))
    // One artifact per platform — the three the release publishes.
    assert(installer.contains("ARTIFACT=ssc-linux-x86_64"))
    assert(installer.contains("ARTIFACT=ssc-macos-arm64"))
    assert(installer.contains("ARTIFACT=ssc-macos-x86_64"))
    // Unverified bytes are REFUSED rather than installed. This is the row worth keeping most:
    // a silent fallback here would install whatever a partial download left behind.
    assert(installer.contains(".tar.gz.sha256"))
    assert(installer.contains("sha256sum -c") && installer.contains("shasum -a 256 -c"))
    assert(installer.contains("refusing to install unverified bytes"))
    // WHAT IT PLACES: the unpacked binary, linked onto PATH, and RUN once before the script claims
    // success. Stated as what the file does rather than as what it no longer does — the two
    // anti-assertions tried here first, on `ssc.jar` and on `java -jar`, both matched the header
    // comment that explains the jar form was abandoned. An anti-assertion a comment can trip
    // guards nothing.
    assert(installer.contains("""tar -xzf "$tmp/$ARTIFACT.tar.gz" -C "$LIB_DIR""""))
    assert(installer.contains("""chmod +x "$LIB_DIR/ssc""""))
    assert(installer.contains("""ln -sf "$LIB_DIR/ssc" "$BIN_DIR/ssc""""))
    assert(installer.contains("""--version"""))

  private def repoRoot: os.Path =
    Iterator.iterate(os.pwd)(_ / os.up)
      .take(12)
      .find(p => os.exists(p / "build.sbt") && os.exists(p / "releases" / "install.sh"))
      .getOrElse(throw new RuntimeException(s"could not locate repo root from ${os.pwd}"))
