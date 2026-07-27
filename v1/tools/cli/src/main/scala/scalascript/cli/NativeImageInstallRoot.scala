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
