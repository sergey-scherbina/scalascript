package scalascript.cli

import java.io.File

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.reporting.{Diagnostic, Reporter}
import dotty.tools.dotc.{Compiler, Driver}

/** v2.0 Phase 3 — in-process Scala 3 compiler driver.
 *
 *  Replaces the `scala-cli compile` subprocess used by
 *  `JvmBytecode.compileAndPack` with a direct invocation of
 *  `dotty.tools.dotc.Driver` inside the running JVM.  A `scala-cli`
 *  invocation pays a ~1 s JVM-start tax per module; with 50 modules
 *  that's 50+ s of pure subprocess overhead.  In-process invocation
 *  avoids the JVM start, the Bloop daemon dance, and the network/socket
 *  warmup — measured (see [[scalascript.cli.JvmDirectDriverTest]]) at
 *  roughly an order-of-magnitude speedup on small fixtures, dominated
 *  by reused classloader state across invocations.
 *
 *  Design notes:
 *
 *   - **Fresh `Driver` + `Compiler` per call.**  The Scala 3 `Driver`
 *     internally builds a `ContextBase` that caches the compiler's
 *     classpath, symbol table and run state.  Reusing the Driver
 *     across two compilations of unrelated modules leaks the previous
 *     run's classpath into the next run (we observed
 *     `NoClassDefFoundError`-shaped failures the first iteration of
 *     this code took).  A new `Driver()` per [[compile]] call is the
 *     simple, correct, low-overhead path — only the per-invocation
 *     `ContextBase` allocation, not the classloader, is repeated.
 *
 *   - **Custom Reporter.**  The default Scala 3 reporter writes to the
 *     process's stdout/stderr unconditionally.  In a CLI context that
 *     means user-visible compile errors would interleave with our own
 *     CLI output (`ssc compile-jvm: error: …`).  We install a
 *     [[CapturingReporter]] that collects every diagnostic into an
 *     in-memory buffer; on a failing compile [[compile]] returns
 *     `Left(formatted-buffer)`, the same shape `JvmBytecode.compileAndPack`
 *     already produces for the scala-cli path, so call sites don't
 *     change.
 *
 *   - **Classpath construction.**  Scala 3's Driver expects a colon-
 *     (or platform-separator-) joined string of JARs and directories.
 *     We pass through `extraClasspath` from the caller and additionally
 *     probe for `scala3-library_3-<version>.jar` and `scala-library-2.13.x.jar`
 *     in Coursier's local cache (or, as a last resort, on the running
 *     JVM's classpath — Scala 3 unit tests run with both libraries on
 *     the test JVM's classpath, so this is the path that lights up the
 *     test).  When the stdlib JARs cannot be located [[compile]] returns
 *     a `Left(diagnostic)` so callers can fall back to scala-cli.
 *
 *  This object is intentionally side-effect-free except for writing
 *  `.class` / `.tasty` files into `outDir`; in particular it does
 *  not print to stdout or System.err.
 *
 *  v2.0 Phase 3 — direct compiler-driver MVP.
 */
object Scala3Driver:

  /** Compile the given Scala source files in-process.
   *
   *  @param srcFiles        Absolute paths of `.scala` / `.sc` files to compile.
   *  @param outDir          Directory under which `.class` / `.tasty` outputs
   *                         will be written.  Created if it doesn't exist.
   *  @param classpath       Extra classpath entries (directories of `.class`
   *                         files or `.jar` files) to feed the compiler.  The
   *                         Scala 3 stdlib JARs are appended automatically.
   *  @param scalaVersion    The Scala 3 version we're compiling against.  Used
   *                         only for diagnostic-message context; the actual
   *                         compiler version is determined by the `scala3-compiler`
   *                         dependency on the CLI's classpath at link time.
   *
   *  @return                `Right(())` when the compile succeeded (every
   *                         requested file type-checks, with `.class` /
   *                         `.tasty` files emitted under `outDir`).
   *                         `Left(diagnostic)` when the compile failed; the
   *                         diagnostic text is the human-readable concatenation
   *                         of every captured error / warning, one per line,
   *                         scala-cli-style.
   */
  def compile(
      srcFiles:     List[os.Path],
      outDir:       os.Path,
      classpath:    List[os.Path] = Nil,
      scalaVersion: String        = "3.8.3"
  ): Either[String, Unit] =
    if srcFiles.isEmpty then
      return Left("Scala3Driver.compile: no source files supplied")

    os.makeDir.all(outDir)

    // Locate the Scala 3 stdlib JARs.  Without them every compile would
    // fail at the first reference to `String`, `Int`, `println`, …
    val stdlib = ScalaStdlibLocator.locate(scalaVersion)
    if stdlib.isEmpty then
      return Left(
        s"Scala3Driver: could not locate scala3-library_3 / scala-library JARs " +
        s"for Scala $scalaVersion (looked in Coursier cache and JVM classpath)"
      )

    val cpString = (classpath ++ stdlib)
      .map(_.toString)
      .mkString(File.pathSeparator)

    // Compute a workspace root for `-sourceroot`.  The compiler embeds
    // the source file's path (relative to `-sourceroot`) in the SourceFile
    // class attribute and in TASTY metadata; without a `-sourceroot` the
    // path is absolute, which is sensitive to the random `os.temp.dir`
    // suffix chosen by `compileAndPackDirect` — two runs of the same
    // source produce different absolute paths, and TASTY/`.class` bytes
    // differ on byte length alone.  Pinning `-sourceroot` to the
    // common parent of `srcFiles` collapses the absolute path to just
    // the file's basename ("a_sc.scala") which is stable across runs.
    //
    // v2.0 Phase 3 follow-up — reproducibility fix.
    val sourceRoot: os.Path =
      if srcFiles.size == 1 then srcFiles.head / os.up
      else
        // Multi-file case: pick the longest common path prefix.
        val segs = srcFiles.map(_.segments.toList)
        val common = segs.reduce((a, b) =>
          a.zip(b).takeWhile { case (x, y) => x == y }.map(_._1)
        )
        if common.isEmpty then os.root
        else os.Path("/" + common.mkString("/"))

    val args: Array[String] = Array(
      "-d", outDir.toString,
      "-classpath", cpString,
      "-sourceroot", sourceRoot.toString,
      // Match `scala-cli`'s defaults: emit `.tasty` alongside `.class`,
      // no progress bar, no banner.  `-color:never` keeps reporter
      // output ANSI-free, which makes the captured buffer pleasant to
      // print verbatim from `compileAndPack`'s error path.
      "-color:never",
      // Quieten "newer source 3.x" warnings — we control the source.
      "-source", "3.0",
      // `-Yretain-trees` is NOT used; we don't need the tree cache.
    ) ++ srcFiles.map(_.toString).toArray

    val reporter = new CapturingReporter
    val driver   = new Driver:
      // Fresh `ContextBase` per invocation — see class-level comment.
      override def newCompiler(using ctx: Context): Compiler = new Compiler

    try
      driver.process(args, reporter)
      if reporter.hasErrors then
        Left(reporter.rendered)
      else
        Right(())
    catch
      case e: Throwable =>
        Left(
          s"Scala3Driver: compiler crashed with ${e.getClass.getSimpleName}: ${e.getMessage}\n" +
          (if reporter.rendered.nonEmpty then reporter.rendered else "")
        )

  /** A `Reporter` that swallows every diagnostic into an in-memory buffer
   *  rather than writing to stdout/stderr.  Renders errors in a format
   *  loosely modelled on scala-cli's:
   *
   *      Error: at /tmp/foo.sc:3:12
   *        expected: ;
   *
   *  Warnings and infos are recorded too but `hasErrors` only flips on
   *  `Diagnostic.Error`. */
  private final class CapturingReporter extends Reporter:
    private val buf = scala.collection.mutable.ListBuffer.empty[String]

    override def doReport(dia: Diagnostic)(using ctx: Context): Unit =
      // Diagnostic level constants live on the `interfaces.Diagnostic`
      // Java interface (ERROR=2, WARNING=1, INFO=0) — NOT on the Scala
      // companion object.  Read them via the interface to stay binary-
      // compatible across compiler versions.
      val level = dia.level match
        case dotty.tools.dotc.interfaces.Diagnostic.ERROR   => "Error"
        case dotty.tools.dotc.interfaces.Diagnostic.WARNING => "Warning"
        case _                                              => "Info"
      val pos = dia.pos
      val locationStr =
        if pos != null && pos.exists then
          val file = scala.util.Try(pos.source.path).toOption.getOrElse("<unknown>")
          val line = pos.line + 1   // dotc uses 0-based lines
          val col  = pos.column + 1 // and 0-based columns
          s" at $file:$line:$col"
        else ""
      val msg = scala.util.Try(dia.message).toOption.getOrElse(dia.toString)
      buf += s"$level$locationStr"
      // Indent the message body two spaces so the "Error at …" header
      // stays scannable in CLI output.
      msg.linesIterator.foreach(l => buf += s"  $l")

    /** Concatenate every captured diagnostic line into a single string,
     *  newline-separated. */
    def rendered: String = buf.mkString("\n")

  /** Probe well-known locations for the Scala 3 stdlib JARs.
   *
   *  Order:
   *    1. The running JVM's classpath (`java.class.path`).  This wins
   *       when the CLI runs under `sbt cli/run` / `sbt cli/test` and the
   *       scala3-compiler/library JARs are already on the test JVM's
   *       classpath via the sbt project's managed deps.  It's also the
   *       happy path for the assembled fat JAR: the JARs we resolve
   *       here get shaded into `ssc.jar`.
   *    2. Coursier's local cache (`~/.cache/coursier/v1/...` or
   *       `~/Library/Caches/Coursier/v1/...` on macOS).  This is the
   *       fallback when the test harness only loads a subset of the
   *       compiler's transitive deps.
   *
   *  Returns the path of every JAR we found that looks like a stdlib
   *  (`scala3-library_3-*.jar`, `scala-library-*.jar`).  Empty list →
   *  caller should fall back to scala-cli. */
  private object ScalaStdlibLocator:
    def locate(scalaVersion: String): List[os.Path] =
      val fromJvm    = locateOnJvmClasspath()
      if fromJvm.nonEmpty then fromJvm
      else locateInCoursierCache(scalaVersion)

    /** Walk `java.class.path` for the two stdlib JARs we need. */
    private def locateOnJvmClasspath(): List[os.Path] =
      val cp = Option(System.getProperty("java.class.path")).getOrElse("")
      cp.split(File.pathSeparator).iterator
        .filter(_.endsWith(".jar"))
        .filter { p =>
          val n = new File(p).getName
          n.startsWith("scala3-library_3-") ||
          n.startsWith("scala-library-")    ||
          // The fat-JAR build may flatten the stdlib classes directly
          // into ssc.jar — in that case `java.class.path` is just the
          // ssc.jar itself, and we don't need to add anything extra.
          n == "ssc.jar"
        }
        .map(p => os.Path(p))
        .filter(os.exists)
        .toList
        .distinct

    /** Look under `~/Library/Caches/Coursier/v1` (macOS) and
     *  `~/.cache/coursier/v1` (Linux) for the stdlib JARs. */
    private def locateInCoursierCache(scalaVersion: String): List[os.Path] =
      val home = sys.props.getOrElse("user.home", ".")
      val roots = List(
        os.Path(home) / "Library" / "Caches" / "Coursier" / "v1",
        os.Path(home) / ".cache" / "coursier" / "v1"
      ).filter(os.exists)
      val out = scala.collection.mutable.ListBuffer.empty[os.Path]
      for root <- roots do
        // scala3-library_3
        val s3 = root / "https" / "repo1.maven.org" / "maven2" / "org" / "scala-lang" /
                 "scala3-library_3" / scalaVersion / s"scala3-library_3-$scalaVersion.jar"
        if os.exists(s3) then out += s3
        // scala-library 2.13.x — Scala 3 depends on a 2.13 stdlib for its
        // SAM bridge / Symbol classes.  Pick the highest version we find.
        val s2dir = root / "https" / "repo1.maven.org" / "maven2" / "org" / "scala-lang" /
                    "scala-library"
        if os.exists(s2dir) then
          val s2jars = os.list(s2dir)
            .filter(os.isDir)
            .map(d => d / s"scala-library-${d.last}.jar")
            .filter(os.exists)
            .toList
            .sortBy(_.last)
          s2jars.lastOption.foreach(out += _)
      out.toList.distinct
