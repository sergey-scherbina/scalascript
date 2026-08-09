package scalascript.imports

import org.scalatest.funsuite.AnyFunSuite

/** std-root-resolution — precedence of `ImportResolver.discoverStdRoot`. */
class StdRootResolutionTest extends AnyFunSuite:

  /** Make a temp dir that contains a `std/` subdir HOLDING A MODULE; returns the root.
   *
   *  The `.ssc` is not decoration. Since `std-to-repo-root` a std root is a directory
   *  whose `std/` actually has modules in it, because `v1/runtime/plugins` survived the move
   *  with 42 Scala plugin modules and zero `.ssc` — and an existence-only test accepted
   *  it, stopping the ancestor walk at a directory under which nothing resolves. */
  private def withStd(label: String): os.Path =
    val root = os.temp.dir(prefix = s"ssc-stdroot-$label-")
    os.makeDir.all(root / "std")
    os.write(root / "std" / "index.ssc", "# std index\n")
    root

  /** A directory with a `std/` subdir that holds NO modules — the shape
   *  `v1/runtime/plugins` has since the move. Must never be accepted. */
  private def withEmptyStd(label: String): os.Path =
    val root = os.temp.dir(prefix = s"ssc-stdroot-empty-$label-")
    os.makeDir.all(root / "std" / "some-plugin" / "src")
    os.write(root / "std" / "some-plugin" / "src" / "Plugin.scala", "class Plugin\n")
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
    os.write(repo / "runtime" / "std" / "index.ssc", "# std\n")
    val jar = repo / "tools" / "cli" / "target" / "scala-3.8.3"
    os.makeDir.all(jar)
    check(disc(lib = Some(repo), jar = Some(jar), home = os.temp.dir()), Some(repo / "runtime"))

  // The counterpart, so the fix cannot be "ignore lib": when the lib root DOES contain std/ it
  // still wins over the dev walk-up.
  test("a lib root WITH std/ still wins over the dev tree"):
    val repo = os.temp.dir(prefix = "ssc-repo-")
    os.makeDir.all(repo / "runtime" / "std")
    os.write(repo / "runtime" / "std" / "index.ssc", "# std\n")
    os.makeDir.all(repo / "std")
    os.write(repo / "std" / "index.ssc", "# std\n")
    val jar = repo / "tools" / "cli" / "target" / "scala-3.8.3"
    os.makeDir.all(jar)
    check(disc(lib = Some(repo), jar = Some(jar), home = os.temp.dir()), Some(repo))

  test("dev walk-up finds an ancestor's runtime/std"):
    val repo = os.temp.dir(prefix = "ssc-repo-")
    os.makeDir.all(repo / "runtime" / "std")
    os.write(repo / "runtime" / "std" / "index.ssc", "# std\n")
    val jar = repo / "tools" / "cli" / "target" / "scala-3.8.3"
    os.makeDir.all(jar)
    check(disc(jar = Some(jar), home = os.temp.dir()), Some(repo / "runtime"))

  test("home ~/.scalascript/std used as last resort"):
    val home = os.temp.dir(prefix = "ssc-home-")
    os.makeDir.all(home / ".scalascript" / "std")
    os.write(home / ".scalascript" / "std" / "index.ssc", "# std\n")
    check(disc(jar = None, home = home), Some(home / ".scalascript"))

  test("nothing available → None"):
    val emptyJar = os.temp.dir()
    check(disc(jar = Some(emptyJar), home = os.temp.dir()), None)

  test("override path that does not exist is skipped"):
    val l = withStd("lib")
    val missing = os.temp.dir() / "does-not-exist"
    check(disc(prop = Some(missing), lib = Some(l), home = os.temp.dir()), Some(l))

  // ── std-to-repo-root regression: a `std/` with no modules is not a std root ──
  //
  // The 108 `.ssc` modules left `v1/runtime/plugins` for the repo-root `std/`, and 42 Scala
  // plugin modules stayed. `v1/runtime/plugins` therefore still EXISTS and holds no `.ssc`.
  // The ancestor walk runs BOTTOM-UP, so from a jar under `v1/runtime/backend/…` it
  // reaches `<root>/v1/runtime` long before the repo root — and an existence-only probe
  // stopped it there, after which every `std/…` import failed with `Import not found`.
  // Ordering the probes root-first could not fix that; the predicate had to get stricter.

  test("a std/ directory holding no .ssc is rejected, not accepted as a std root"):
    val empty = withEmptyStd("lib")
    check(disc(lib = Some(empty), home = os.temp.dir()), None)

  test("the ancestor walk skips an empty std/ and keeps climbing to the real one"):
    // Shape of the tree after the move: <root>/std has modules, <root>/v1/runtime/plugins
    // does not, and the jar sits below the latter.
    val root = withStd("walk-root")
    val stale = root / "v1" / "runtime"
    os.makeDir.all(stale / "std" / "auth-plugin")
    os.write(stale / "std" / "auth-plugin" / "P.scala", "class P\n")
    val jar = stale / "backend" / "interpreter" / "target"
    os.makeDir.all(jar)
    check(disc(jar = Some(jar), home = os.temp.dir()), Some(root))

  test("the walk still finds a legacy runtime/std that DOES hold modules"):
    // An installed tree stages std to <root>/runtime/std — that arm must keep working.
    val root = os.temp.dir(prefix = "ssc-stdroot-installed-")
    os.makeDir.all(root / "runtime" / "std")
    os.write(root / "runtime" / "std" / "http.ssc", "# http\n")
    val jar = root / "lib"
    os.makeDir.all(jar)
    check(disc(jar = Some(jar), home = os.temp.dir()), Some(root / "runtime"))
