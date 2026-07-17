package scalascript.cli

/** Shared access to the distribution produced by `cli/assembly; installBin`.
 *
 * Bytecode CLI tests must exercise the installed launcher contract: the launcher
 * supplies `ssc.lib.path`, while the staged compiler and plugin JARs live under
 * the same root. Looking only at the sbt process CWD or a platform-specific
 * Coursier cache made the tests cancel even after CI had installed everything.
 */
private[cli] object StagedCliTestSupport:

  private def codeSource(clazz: Class[?]): Option[os.Path] =
    scala.util.Try {
      val path = os.Path(clazz.getProtectionDomain.getCodeSource.getLocation.toURI)
      if os.isFile(path) then path / os.up else path
    }.toOption.filter(os.exists)

  /** Locate the actual classpath entry containing `clazz`. Coursier's test
   * classloader may report a Maven version directory as the protection-domain
   * code source even though the class resource itself is inside a JAR. */
  private def classpathEntry(clazz: Class[?]): Option[os.Path] =
    val resourceName = "/" + clazz.getName.replace('.', '/') + ".class"
    Option(clazz.getResource(resourceName)).flatMap { url =>
      if url.getProtocol == "jar" then
        scala.util.Try {
          val connection = url.openConnection().asInstanceOf[java.net.JarURLConnection]
          os.Path(connection.getJarFileURL.toURI)
        }.toOption.filter(os.isFile)
      else codeSource(clazz)
    }.orElse(codeSource(clazz))

  private def ancestors(start: os.Path): Iterator[os.Path] =
    Iterator.iterate(start)(_ / os.up).take(start.segments.length + 1)

  private lazy val searchStarts: List[os.Path] =
    List(
      Some(os.pwd),
      sys.props.get("user.dir").flatMap(s => scala.util.Try(os.Path(s, os.pwd)).toOption),
      codeSource(getClass)
    ).flatten.distinct

  lazy val installRoot: Option[os.Path] =
    searchStarts.iterator
      .flatMap(ancestors)
      .toList.distinct
      .find(root => os.isFile(root / "bin" / "ssc-tools"))

  lazy val toolsLauncher: Option[os.Path] =
    installRoot.map(_ / "bin" / "ssc-tools").filter(os.isFile)

  def compilerDriverAvailable: Boolean =
    installRoot.exists { root =>
      val jars = root / "bin" / "lib" / "compiler" / "jars"
      os.isDir(jars) && os.list(jars).exists(_.ext == "jar")
    }

  def runTools(
      launcher: os.Path,
      cwd: os.Path,
      env: Map[String, String] = Map.empty,
      args: Seq[String]
  ): os.CommandResult =
    val command: Seq[os.Shellable] =
      Seq[os.Shellable](launcher.toString) ++ args.map(a => a: os.Shellable)
    os.proc(command).call(
      cwd = cwd,
      env = env,
      stdin = "",
      check = false,
      stderr = os.Pipe,
      stdout = os.Pipe)

  def scalaRuntimeClasspath: Option[String] =
    for
      scala3 <- classpathEntry(classOf[scala.deriving.Mirror])
      scala2 <- classpathEntry(classOf[scala.Product])
    yield List(scala3, scala2).distinct.mkString(java.io.File.pathSeparator)

  /** Configure the current test JVM like the installed launcher before the
   * in-process compiler service is initialised. CLI tests execute serially, and
   * retaining the correct staged root lets the lazy service be reused safely. */
  def configureInstalledLibPath(): Option[os.Path] =
    installRoot.filter(_ => compilerDriverAvailable).map { root =>
      sys.props("ssc.lib.path") = root.toString
      root
    }
