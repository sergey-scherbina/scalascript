package scalascript.backend.spi

import java.net.URI
import java.nio.file.Path
import scala.concurrent.Future

/** Unified runtime plugin lookup/install facade.
 *
 *  Implementations may combine classpath ServiceLoader plugins, `.sscpkg`
 *  archives, subprocess plugins, and remote installer sources behind one
 *  cache.  The initial implementation is `BackendRegistry` in core.
 */
trait PluginRegistry:
  def lookup(id: String): Option[Backend]
  def listInstalled(): Seq[PluginMeta]
  def install(source: PluginSource): Future[Unit]

case class PluginMeta(
    id:          String,
    displayName: String,
    spiVersion:  String,
    kind:        String,
    source:      String = ""
)

sealed trait PluginSource

/** In-process plugin class instantiated directly. */
case class ClasspathPlugin(fqcn: String) extends PluginSource

/** Local `.sscpkg` archive. */
case class SscpkgPlugin(path: Path) extends PluginSource

/** Out-of-process plugin executable using the existing subprocess wire protocol. */
case class SubprocessPlugin(
    binary:           Path,
    args:             Seq[String]  = Nil,
    workingDirectory: Option[Path] = None,
    protocol:         String       = "stdio-json"
) extends PluginSource

/** Remote `.sscpkg` URL, or a registry URI resolved by the implementation. */
case class RemotePlugin(uri: URI) extends PluginSource
