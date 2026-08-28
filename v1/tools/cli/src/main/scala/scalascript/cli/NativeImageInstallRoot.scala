package scalascript.cli

import java.nio.file.{Files, Path, Paths}
import scala.util.Try

/** Discovers the relocatable installation root for the native-image CLI.
 *
 * JVM launchers set `ssc.lib.path` explicitly. A native executable has no
 * launcher, so it derives the root from its own real path before any eager
 * `ImportResolver.libPath` access. Both release layouts are accepted:
 *
 *   - `<root>/ssc`
 *   - `<root>/bin/ssc`
 */
private[cli] object NativeImageInstallRoot:
  private val ImageCode = "org.graalvm.nativeimage.imagecode"
  private val LibPath   = "ssc.lib.path"
  private val EnvLibPath = "SSC_LIB_PATH"
  private val FrontPath = Paths.get("bin", "lib", "standard", "native-front")

  /** Shown at the two call sites that need `ssc.lib.path` and find it unset
   *  (`RunNativeV2.nativeFrontLayout`, `NativeJvmArtifact.runCommand`) — never written twice, because
   *  the two messages already drifted once (one told a contributor to run `scripts/sbtc`, the other
   *  didn't) while both actually fire in the SAME one situation.
   *
   *  THAT SITUATION IS NARROW, and the old wording was written for the wrong audience: `configure()`
   *  only calls `discoverRoot` when `isNativeRuntime` is true, i.e. only inside a native-image binary
   *  — a JVM launcher (`bin/ssc`, `bin/ssc-tools`) always sets `ssc.lib.path` itself and can never
   *  reach either throw site. So a checkout with `scripts/sbtc` never exists for anyone who sees this;
   *  it is always someone running a downloaded native binary. Measured 2026-08-28: the release
   *  publishes a BARE `ssc-<platform>` executable at the top level of the GitHub Release page,
   *  alongside `ssc-<platform>.tar.gz` — indistinguishable at a glance, and the bare one can never
   *  work alone, because `discoverRoot` requires `bin/lib/standard/native-front` next to it (this
   *  file's own layout doc above), which only the archive provides. Reproduced: `curl`-ing the bare
   *  asset by itself and running it against any `.ssc` file throws this exact message.
   */
  private[cli] val MissingInstallRootMessage =
    "this native binary could not find its compiler front staged next to itself " +
    "(ssc.lib.path is unset). If you downloaded the bare 'ssc-<platform>' file from the GitHub " +
    "release page, that is the cause: it has no bin/lib/standard/native-front beside it and can " +
    "never work alone. Get 'ssc-<platform>.tar.gz' instead and unpack it WHOLE — the archive lays " +
    "the front out next to the binary. Already have that layout somewhere else? Point SSC_LIB_PATH " +
    "at its root."

  def configure(): Unit =
    resolve(
      isNativeRuntime = System.getProperty(ImageCode) == "runtime",
      configuredLibPath = Option(System.getProperty(LibPath)),
      environmentLibPath = Option(System.getenv(EnvLibPath)),
      executable = currentExecutable
    ).foreach(root => System.setProperty(LibPath, root))

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
        .orElse(executable.flatMap(discoverRoot).map(_.toString))

  private[cli] def discoverRoot(executable: Path): Option[Path] =
    val realExecutable =
      Try(executable.toRealPath()).getOrElse(executable.toAbsolutePath.normalize())
    Option(realExecutable.getParent).toList
      .flatMap(parent => parent :: Option(parent.getParent).toList)
      .distinct
      .find(root => Files.isDirectory(root.resolve(FrontPath)))

  private def currentExecutable: Option[Path] =
    Try(ProcessHandle.current().info().command()).toOption.flatMap { command =>
      if command.isPresent then Some(Paths.get(command.get()))
      else None
    }
