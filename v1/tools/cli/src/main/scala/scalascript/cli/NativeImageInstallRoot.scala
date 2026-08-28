package scalascript.cli

import java.nio.file.{Files, Path, Paths}
import scala.util.Try

/** Discovers `ssc.lib.path` for the native-image CLI, and resolves the native compiler front and
 *  staged JVM jars underneath it — the two things `RunNativeV2.nativeFrontLayout` and
 *  `NativeJvmArtifact.runCommand` need and cannot find any other way inside a native-image binary,
 *  which has no launcher to set the property itself.
 *
 *  `ssc.lib.path` is scoped NARROWLY here: only `discoverLib`/`resolveUnderLib` in this file, and
 *  their two callers, treat it this way. `ImportResolver`, `JarCommands`, `CompilerLoader`,
 *  `JvmGen`/`SparkGen`, and `InstallCommands`'s launcher templates all keep the older, UNCHANGED
 *  convention where the property names the checkout/install ROOT and each of them appends its own
 *  `bin/lib/...` suffix — that convention is load-bearing for every JVM-launcher run (`bin/ssc`
 *  sets `ssc.lib.path` to the checkout root itself, never touching this file) and is deliberately
 *  left alone; see `specs/arch-lib-path-resolution.md` for why the two conventions coexist instead
 *  of one replacing the other project-wide.
 *
 *  `discoverLib` finds a `lib/` directory next to the executable, or one level up — covering all
 *  three physical layouts a `ssc` binary ships in, checked in this order (so a `lib/` beside the
 *  executable always wins over one above it):
 *
 *   - `<root>/ssc`     + `<root>/lib/...`      (release archive)
 *   - `<root>/bin/ssc` + `<root>/lib/...`      (`bin/` a sibling of `lib/`)
 *   - `<root>/bin/ssc` + `<root>/bin/lib/...`  (the checkout's own `bin/` tree)
 *
 *  Because `ssc.lib.path` can ALSO arrive already set — either to a `lib/` directory directly (this
 *  file's own discovery, or a user exporting `SSC_LIB_PATH` at a `lib/` root) or to the older
 *  ROOT shape (inherited from a JVM-launcher environment, or a user exporting `SSC_LIB_PATH` the
 *  old way) — `resolveUnderLib` accepts either shape rather than assuming one.
 */
private[cli] object NativeImageInstallRoot:
  private val ImageCode = "org.graalvm.nativeimage.imagecode"
  private val LibPath   = "ssc.lib.path"
  private val EnvLibPath = "SSC_LIB_PATH"
  // The one file every layout is guaranteed to carry — used only to confirm a candidate `lib/` is
  // a real install, not an unrelated directory that happens to be named `lib`.
  private val FrontMarker = Paths.get("standard", "native-front")

  /** Shown at the two call sites that need `ssc.lib.path` and find it unset
   *  (`RunNativeV2.nativeFrontLayout`, `NativeJvmArtifact.runCommand`) — never written twice, because
   *  the two messages already drifted once (one told a contributor to run `scripts/sbtc`, the other
   *  didn't) while both actually fire in the SAME one situation.
   *
   *  THAT SITUATION IS NARROW, and the old wording was written for the wrong audience: `configure()`
   *  only calls `discoverLib` when `isNativeRuntime` is true, i.e. only inside a native-image binary
   *  — a JVM launcher (`bin/ssc`, `bin/ssc-tools`) always sets `ssc.lib.path` itself and can never
   *  reach either throw site. So a checkout with `scripts/sbtc` never exists for anyone who sees this;
   *  it is always someone running a downloaded native binary with no `lib/` anywhere it looks.
   */
  private[cli] val MissingInstallRootMessage =
    "this native binary could not find a lib/ directory staged next to itself, or one level " +
    "above itself (ssc.lib.path is unset). If you downloaded the bare 'ssc-<platform>' file " +
    "from the GitHub release page without the archive around it, that is the cause: it has no " +
    "lib/ beside it and can never work alone. Get 'ssc-<platform>.tar.gz' instead and unpack it " +
    "WHOLE. Already have a lib/ layout somewhere else? Point SSC_LIB_PATH directly at its lib/ " +
    "directory."

  def configure(): Unit =
    resolve(
      isNativeRuntime = System.getProperty(ImageCode) == "runtime",
      configuredLibPath = Option(System.getProperty(LibPath)),
      environmentLibPath = Option(System.getenv(EnvLibPath)),
      executable = currentExecutable
    ).foreach(lib => System.setProperty(LibPath, lib))

  private[cli] def resolve(
      isNativeRuntime: Boolean,
      configuredLibPath: Option[String],
      environmentLibPath: Option[String],
      executable: => Option[Path]
  ): Option[String] =
    if !isNativeRuntime || configuredLibPath.isDefined then None
    else
      environmentLibPath
        .filter(_.nonEmpty)
        .orElse(executable.flatMap(discoverLib).map(_.toString))

  private[cli] def discoverLib(executable: Path): Option[Path] =
    val realExecutable =
      Try(executable.toRealPath()).getOrElse(executable.toAbsolutePath.normalize())
    Option(realExecutable.getParent).toList
      .flatMap(execDir => execDir :: Option(execDir.getParent).toList)
      .distinct
      .map(_.resolve("lib"))
      .find(lib => Files.isDirectory(lib.resolve(FrontMarker)))

  private def currentExecutable: Option[Path] =
    Try(ProcessHandle.current().info().command()).toOption.flatMap { command =>
      if command.isPresent then Some(Paths.get(command.get()))
      else None
    }

  /** Resolve `suffix` (e.g. `"standard/native-front"`) against `ssc.lib.path`'s value, whichever
   *  of the two shapes it currently holds: the `lib/` directory itself (this file's own discovery,
   *  or `SSC_LIB_PATH` pointed straight at `lib/`) tried first, then the checkout ROOT shape a JVM
   *  launcher sets (`<root>/bin/lib/<suffix>`) as a fallback — so the SAME call works unmodified
   *  whether `ssc.lib.path` arrived from native-image discovery or from `bin/ssc`'s own launcher
   *  property, without either caller needing to know which one it got. */
  private[cli] def resolveUnderLib(installRoot: java.io.File, suffix: String): java.io.File =
    val direct = new java.io.File(installRoot, suffix)
    if direct.exists() then direct
    else new java.io.File(installRoot, s"bin/lib/$suffix")

  /** True when `installRoot` resolved `suffix` via the direct `lib/`-dir shape (as opposed to the
   *  `bin/lib/<suffix>` ROOT-shape fallback) — i.e. whichever branch `resolveUnderLib` actually
   *  took for that same `(installRoot, suffix)` pair. */
  private[cli] def isLibShaped(installRoot: java.io.File, suffix: String): Boolean =
    new java.io.File(installRoot, suffix).exists()

  /** Recovers a checkout/install ROOT-shaped value from a `lib/`-shaped `ssc.lib.path`, for the
   *  few consumers (the self-hosted tower's `--lib-root` flag, `NativeSourceClosure`'s bare
   *  repo-relative import fallback) that want "the root", not the lib dir — walks up one level,
   *  or two if the first level up is named `bin` (the checkout's own `<root>/bin/lib` shape). */
  private[cli] def rootAbove(lib: java.io.File): java.io.File =
    val parent = lib.getParentFile
    if parent == null then lib
    else if parent.getName == "bin" && parent.getParentFile != null then parent.getParentFile
    else parent
