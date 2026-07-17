package scalascript.cli

import scalascript.compiler.Scala3CompilerService
import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/** Lazy loader for the Scala 3 compiler service.
 *
 *  `lib/compiler/jars/` is NOT on the startup classpath.  This object
 *  creates a URLClassLoader from that directory the first time a compile
 *  command runs and discovers [[Scala3CompilerService]] via ServiceLoader.
 *  Subsequent calls reuse the same classloader instance. */
object CompilerLoader:

  /** Read the live launcher property before falling back to ImportResolver's
   * process-start snapshot. This matters for an embedded caller that configures
   * the supported installation root before the compiler service is first used. */
  private def configuredLibPath: Option[os.Path] =
    sys.props.get("ssc.lib.path")
      .orElse(sys.env.get("SSC_LIB_PATH"))
      .flatMap(s => scala.util.Try(os.Path(s, os.pwd)).toOption)
      .filter(os.exists)
      .orElse(scalascript.imports.ImportResolver.libPath)

  private lazy val service: Scala3CompilerService =
    val libPath = configuredLibPath.getOrElse(
      throw RuntimeException(
        "CompilerLoader: ssc.lib.path is not set — cannot locate lib/compiler/jars/.\n" +
        "Run ssc via the installed bin/ssc launcher or set -Dssc.lib.path=<root>."
      )
    )
    val jarDir = libPath / "bin" / "lib" / "compiler" / "jars"
    if !os.exists(jarDir) then
      throw RuntimeException(
        s"CompilerLoader: $jarDir does not exist.\n" +
        "Run `sbt cli/stage` to populate lib/compiler/jars/."
      )
    val urls = os.list(jarDir)
      .filter(_.ext == "jar")
      .map(_.toIO.toURI.toURL)
      .toArray
    val cl = new java.net.URLClassLoader(urls, classOf[Scala3CompilerService].getClassLoader)
    ServiceLoader.load(classOf[Scala3CompilerService], cl)
      .iterator.asScala
      .nextOption()
      .getOrElse(
        throw RuntimeException(
          s"CompilerLoader: no Scala3CompilerService found in $jarDir.\n" +
          "Expected compiler-driver*.jar with META-INF/services entry."
        )
      )

  /** Compile `sources` to `outDir`.  Lazily initialises the compiler
   *  classloader on first call; subsequent calls reuse it. */
  def compile(
    sources:   List[os.Path],
    outDir:    os.Path,
    classpath: List[os.Path] = Nil,
    options:   Seq[String]   = Nil
  ): Either[String, Unit] =
    service.compile(sources, outDir, classpath, options)
