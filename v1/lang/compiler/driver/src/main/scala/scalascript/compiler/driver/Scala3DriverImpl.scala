package scalascript.compiler.driver

import java.io.File
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.reporting.{Diagnostic, Reporter}
import dotty.tools.dotc.{Compiler, Driver}
import scalascript.compiler.Scala3CompilerService

/** ServiceLoader implementation of [[Scala3CompilerService]] backed by the
 *  Scala 3 in-process compiler (dotty).  Lives in `compiler/driver` module
 *  so that `scala3-compiler` is NOT on the startup classpath — this class
 *  is loaded lazily via URLClassLoader only when a compile command runs. */
class Scala3DriverImpl extends Scala3CompilerService:

  def compile(
    sources:   Seq[os.Path],
    outDir:    os.Path,
    classpath: Seq[os.Path],
    options:   Seq[String] = Nil
  ): Either[String, Unit] =
    if sources.isEmpty then
      return Left("Scala3DriverImpl.compile: no source files supplied")

    os.makeDir.all(outDir)

    val stdlib = ScalaStdlibLocator.locate()
    if stdlib.isEmpty then
      return Left(
        "Scala3DriverImpl: could not locate scala3-library_3 / scala-library JARs " +
        "(looked in Coursier cache and JVM classpath)"
      )

    val cpString = (classpath.toList ++ stdlib)
      .map(_.toString)
      .mkString(File.pathSeparator)

    val sourceRoot: os.Path =
      if sources.size == 1 then sources.head / os.up
      else
        val segs = sources.map(_.segments.toList)
        val common = segs.reduce((a, b) =>
          a.zip(b).takeWhile { case (x, y) => x == y }.map(_._1))
        if common.isEmpty then os.root
        else os.Path("/" + common.mkString("/"))

    val args: Array[String] = Array(
      "-d",          outDir.toString,
      "-classpath",  cpString,
      "-sourceroot", sourceRoot.toString,
      "-color:never",
      "-source", "3.0",
    ) ++ options.toArray ++ sources.map(_.toString).toArray

    val reporter = new CapturingReporter
    val driver = new Driver:
      override def newCompiler(using ctx: Context): Compiler = new Compiler

    try
      driver.process(args, reporter)
      if reporter.hasErrors then Left(reporter.rendered)
      else Right(())
    catch
      case e: Throwable =>
        Left(
          s"Scala3DriverImpl: compiler crashed with ${e.getClass.getSimpleName}: ${e.getMessage}\n" +
          (if reporter.rendered.nonEmpty then reporter.rendered else "")
        )


private final class CapturingReporter extends Reporter:
  private val buf = scala.collection.mutable.ListBuffer.empty[String]

  override def doReport(dia: Diagnostic)(using ctx: Context): Unit =
    val level = dia.level match
      case dotty.tools.dotc.interfaces.Diagnostic.ERROR   => "Error"
      case dotty.tools.dotc.interfaces.Diagnostic.WARNING => "Warning"
      case _                                               => "Info"
    val pos = dia.pos
    val locationStr =
      if pos != null && pos.exists then
        val file = scala.util.Try(pos.source.path).toOption.getOrElse("<unknown>")
        s" at $file:${pos.line + 1}:${pos.column + 1}"
      else ""
    val msg = scala.util.Try(dia.message).toOption.getOrElse(dia.toString)
    buf += s"$level$locationStr"
    msg.linesIterator.foreach(l => buf += s"  $l")

  def rendered: String = buf.mkString("\n")


private object ScalaStdlibLocator:
  def locate(): List[os.Path] =
    val fromJvm = locateOnJvmClasspath()
    if fromJvm.nonEmpty then fromJvm
    else locateInCoursierCache()

  private def locateOnJvmClasspath(): List[os.Path] =
    val cp = Option(System.getProperty("java.class.path")).getOrElse("")
    cp.split(File.pathSeparator).iterator
      .filter(_.endsWith(".jar"))
      .filter { p =>
        val n = new File(p).getName
        n.startsWith("scala3-library_3-") || n.startsWith("scala-library-")
      }
      .map(p => os.Path(p))
      .filter(os.exists)
      .toList.distinct

  private def locateInCoursierCache(): List[os.Path] =
    val home = sys.props.getOrElse("user.home", ".")
    val roots = List(
      os.Path(home) / "Library" / "Caches" / "Coursier" / "v1",
      os.Path(home) / ".cache" / "coursier" / "v1"
    ).filter(os.exists)
    val out = scala.collection.mutable.ListBuffer.empty[os.Path]
    for root <- roots do
      val scalaVer = scala.util.Properties.versionNumberString
      val s3 = root / "https" / "repo1.maven.org" / "maven2" / "org" / "scala-lang" /
               "scala3-library_3" / scalaVer / s"scala3-library_3-$scalaVer.jar"
      if os.exists(s3) then out += s3
      val s2dir = root / "https" / "repo1.maven.org" / "maven2" / "org" / "scala-lang" / "scala-library"
      if os.exists(s2dir) then
        os.list(s2dir).filter(os.isDir)
          .map(d => d / s"scala-library-${d.last}.jar")
          .filter(os.exists)
          .toList.sortBy(_.last)
          .lastOption.foreach(out += _)
    out.toList.distinct
