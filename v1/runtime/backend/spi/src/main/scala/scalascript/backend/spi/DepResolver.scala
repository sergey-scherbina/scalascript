package scalascript.backend.spi

import java.nio.file.Path
import java.nio.file.Paths

/** Pluggable resolver for non-local ScalaScript dependency imports.
 *
 *  Resolvers return a local artifact path for a scheme-specific dependency
 *  URI such as `github:owner/repo@tag`.
 */
trait DepResolver:
  def scheme: String
  def resolve(spec: DepSpec): Path

case class DepSpec(
    raw:       String,
    sha256:    Option[String] = None,
    cacheRoot: Path = Paths.get(System.getProperty("user.home"), ".cache", "scalascript", "deps"),
)
