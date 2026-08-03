package scalascript.imports

import org.scalatest.funsuite.AnyFunSuite

/** std-root-resolution — precedence of `ImportResolver.discoverStdRoot`. */
class StdRootResolutionTest extends AnyFunSuite:

  /** Make a temp dir that contains a `std/` subdir; returns the root. */
  private def withStd(label: String): os.Path =
    val root = os.temp.dir(prefix = s"ssc-stdroot-$label-")
    os.makeDir.all(root / "std")
    root

  private def disc(
      prop: Option[os.Path] = None,
      env:  Option[os.Path] = None,
      lib:  Option[os.Path] = None,
      jar:  Option[os.Path] = None,
      home: os.Path
  ): Option[os.Path] =
    ImportResolver.discoverStdRoot(prop.map(_.toString), env.map(_.toString), lib, jar, home)

  private def check(actual: Option[os.Path], expected: Option[os.Path]): Unit =
    assert(actual == expected, s"\n  expected: $expected\n  actual:   $actual")

  test("ssc.std.path override wins over everything"):
    val p = withStd("prop"); val l = withStd("lib"); val h = withStd("home")
    check(disc(prop = Some(p), lib = Some(l), home = h / os.up), Some(p))

  test("SSC_STD_PATH wins over libPath when no prop"):
    val e = withStd("env"); val l = withStd("lib")
    check(disc(env = Some(e), lib = Some(l), home = os.temp.dir()), Some(e))

  test("libPath used when no override"):
    val l = withStd("lib")
    check(disc(lib = Some(l), home = os.temp.dir()), Some(l))

  test("jar-dir/std used when it has a std subdir"):
    val j = withStd("jar")
    check(disc(jar = Some(j), home = os.temp.dir()), Some(j))

  // THE PRODUCTION SHAPE, and the one every other test here misses: `lib` is set — every `bin/ssc*`
  // passes `-Dssc.lib.path=<repo root>` — but a dev tree keeps its std at `runtime/std`, so the
  // root does NOT contain `std/`. Rule 3 was unfiltered, so it returned the root regardless and
  // rules 4-6 never ran. Every other test builds `lib` with `withStd("lib")`, which is the one
  // shape where a filtered and an unfiltered rule 3 behave identically.
  test("a lib root WITHOUT std/ does not win over the dev tree"):
    val repo = os.temp.dir(prefix = "ssc-repo-")
    os.makeDir.all(repo / "runtime" / "std")           // dev layout: std is under runtime/
    val jar = repo / "tools" / "cli" / "target" / "scala-3.8.3"
    os.makeDir.all(jar)
    check(disc(lib = Some(repo), jar = Some(jar), home = os.temp.dir()), Some(repo / "runtime"))

  // The counterpart, so the fix cannot be "ignore lib": when the lib root DOES contain std/ it
  // still wins over the dev walk-up.
  test("a lib root WITH std/ still wins over the dev tree"):
    val repo = os.temp.dir(prefix = "ssc-repo-")
    os.makeDir.all(repo / "runtime" / "std")
    os.makeDir.all(repo / "std")
    val jar = repo / "tools" / "cli" / "target" / "scala-3.8.3"
    os.makeDir.all(jar)
    check(disc(lib = Some(repo), jar = Some(jar), home = os.temp.dir()), Some(repo))

  test("dev walk-up finds an ancestor's runtime/std"):
    val repo = os.temp.dir(prefix = "ssc-repo-")
    os.makeDir.all(repo / "runtime" / "std")
    val jar = repo / "tools" / "cli" / "target" / "scala-3.8.3"
    os.makeDir.all(jar)
    check(disc(jar = Some(jar), home = os.temp.dir()), Some(repo / "runtime"))

  test("home ~/.scalascript/std used as last resort"):
    val home = os.temp.dir(prefix = "ssc-home-")
    os.makeDir.all(home / ".scalascript" / "std")
    check(disc(jar = None, home = home), Some(home / ".scalascript"))

  test("nothing available → None"):
    val emptyJar = os.temp.dir()
    check(disc(jar = Some(emptyJar), home = os.temp.dir()), None)

  test("override path that does not exist is skipped"):
    val l = withStd("lib")
    val missing = os.temp.dir() / "does-not-exist"
    check(disc(prop = Some(missing), lib = Some(l), home = os.temp.dir()), Some(l))
