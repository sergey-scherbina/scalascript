package scalascript.imports

import scalascript.backend.spi.{DepResolver, DepSpec}

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** Resolves Maven-style `dep:` coordinates through Coursier.
 *
 *  Legacy ScalaScript `dep:org/name:version` source imports are intentionally
 *  left to ImportResolver's dep-sources fallback. This resolver handles Maven
 *  coordinates such as `dep:com.lihaoyi::os-lib:0.11.4`.
 */
class MavenDepResolver(extraRepositories: Seq[String] = Nil) extends DepResolver:
  override val scheme: String = "dep"

  override def resolve(spec: DepSpec): Path =
    val coord = MavenDepResolver.mavenCoordinate(spec.raw)
    val command = MavenDepResolver.coursierCommand.getOrElse {
      throw new RuntimeException(
        "Coursier command not found. Install `cs`, set SSC_COURSIER, or set -Dssc.coursier.command=/path/to/cs."
      )
    }

    val scalaVersion = sys.props.getOrElse("ssc.scala.version", MavenDepResolver.defaultScalaVersion)
    val cacheRoot = os.Path(spec.cacheRoot.toString, os.pwd) / "coursier"
    val args =
      command ++ Seq(
        "fetch",
        "--classpath",
        "--no-default",
        "--cache", cacheRoot.toString,
        "--scala-version", scalaVersion,
      ) ++
      MavenDepResolver.repositoriesFromConfig.flatMap(repo => Seq("--repository", repo)) ++
      extraRepositories.flatMap(repo => Seq("--repository", repo)) ++
      Seq(coord)

    val pb = ProcessBuilder(args.asJava)
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val out = proc.getInputStream.readAllBytes()
    val exit = proc.waitFor()
    val output = String(out, StandardCharsets.UTF_8).trim
    if exit != 0 then
      throw new RuntimeException(s"Coursier failed resolving '$coord' (exit $exit):\n$output")

    val paths = output
      .split(s"[${java.util.regex.Pattern.quote(File.pathSeparator)}\\r\\n]+")
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(p => os.Path(p, os.pwd))
      .filter(os.exists)
      .toVector

    val selected = paths.find(p => p.ext == "sscpkg" || p.ext == "ssc").orElse(paths.headOption).getOrElse {
      throw new RuntimeException(s"Coursier resolved '$coord' but returned no local artifacts")
    }
    verifyPin(selected, spec.sha256)
    selected.toNIO

  private def verifyPin(path: os.Path, sha256: Option[String]): Unit =
    sha256.foreach { expected =>
      val actual = DepCache.sha256hex(os.read.bytes(path))
      if !actual.equalsIgnoreCase(expected) then
        throw new RuntimeException(s"sha256 mismatch for ${path.last}: expected $expected, got $actual")
    }

object MavenDepResolver:
  val defaultScalaVersion: String = "3.8.3"

  def isMavenCoordinate(raw: String): Boolean =
    val body = raw.stripPrefix("dep:")
    !body.contains("/") && body.split(":", -1).length >= 3

  def mavenCoordinate(raw: String): String =
    val body = raw.stripPrefix("dep:")
    if !isMavenCoordinate(raw) then
      throw new RuntimeException(s"Invalid Maven dep coordinate '$raw' — expected dep:<group>:<artifact>:<version>")
    body

  def repositoriesFromConfig: Seq[String] =
    sys.props.get("ssc.coursier.repositories")
      .orElse(sys.env.get("SSC_COURSIER_REPOSITORIES"))
      .map(_.split(",").iterator.map(_.trim).filter(_.nonEmpty).toVector)
      .getOrElse(Vector("central"))

  def coursierCommand: Option[Seq[String]] =
    sys.props.get("ssc.coursier.command")
      .orElse(sys.env.get("SSC_COURSIER"))
      .map(cmd => Seq(cmd))
      .orElse(findOnPath("cs").map(p => Seq(p.toString)))
      .orElse(findOnPath("coursier").map(p => Seq(p.toString)))

  private def findOnPath(name: String): Option[Path] =
    sys.env.get("PATH").toSeq
      .flatMap(_.split(File.pathSeparator).toSeq)
      .map(dir => Path.of(dir, name))
      .find(p => java.nio.file.Files.isExecutable(p))
