package scalascript.server.spi

import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/** Discovery + selection of `HttpServerSpi` implementations at
 *  runtime.  Hybrid mechanism:
 *
 *  - `ServiceLoader[HttpServerSpi]` returns every impl registered via
 *    `META-INF/services/scalascript.server.spi.HttpServerSpi` that
 *    is on the classpath.  Default ssc distribution bundles only
 *    `JdkServerBackend` (zero deps); adding Jetty or Netty is an
 *    sbt opt-in via `dependsOn(runtimeServerJvmJetty)` /
 *    `dependsOn(runtimeServerJvmNetty)`.
 *
 *  - If multiple impls are on the classpath, `setBackend(name)`
 *    picks by `HttpServerSpi.name` (e.g. `"jdk"`, `"jetty"`,
 *    `"netty"`).  Unset = first-found wins.  This lets user code
 *    do:
 *    ```scala
 *    setHttpServerBackend("jetty")
 *    serve(8080)
 *    ```
 *
 *  - `current` returns the resolved backend (cached after first
 *    call); resets to fresh discovery when `setBackend` changes
 *    the choice.
 *
 *  See `docs/http-server-spi-plan.md` for the full design rationale. */
object HttpServerBackends:

  @volatile private var _selectedName: Option[String]   = None
  @volatile private var _cached:       HttpServerSpi | Null = null

  /** All discovered backends, in classpath order.  Re-runs the
   *  ServiceLoader each call (cheap; the loader is cached internally). */
  def all(): List[HttpServerSpi] =
    ServiceLoader.load(classOf[HttpServerSpi]).iterator().asScala.toList

  /** Pick a backend by name.  Subsequent `current` calls return the
   *  matching impl.  If no impl with that name is on the classpath,
   *  throws `IllegalStateException` (loud failure better than silent
   *  fallback when the user explicitly asked for jetty / netty).
   *
   *  Pass `null` to reset to first-found semantics. */
  def setBackend(name: String | Null): Unit =
    _selectedName = Option(name)
    _cached = null
    // Eagerly resolve so a wrong name fails fast.
    if name != null then
      val impls = all()
      if !impls.exists(_.name == name) then
        throw new IllegalStateException(
          s"No HttpServerSpi impl named '$name' on classpath.  " +
          s"Available: [${impls.map(_.name).mkString(", ")}].  " +
          s"Add the right module to your build (e.g. " +
          s"`dependsOn(runtimeServerJvmJetty)` for jetty)."
        )

  /** Resolve the active backend.  Cached; reset by `setBackend`. */
  def current(): HttpServerSpi =
    val cached = _cached
    if cached != null then cached
    else
      val impls = all()
      if impls.isEmpty then
        throw new IllegalStateException(
          "No HttpServerSpi impl on classpath — add at least " +
          "`runtimeServerJvm` (the default JDK backend) to your " +
          "dependencies."
        )
      val chosen = _selectedName match
        case Some(n) => impls.find(_.name == n).getOrElse(
                          throw new IllegalStateException(
                            s"HttpServerSpi name='$n' selected but no " +
                            s"impl with that name registered."))
        case None    => impls.head
      _cached = chosen
      chosen

  /** Currently-selected backend name (whatever was passed to
   *  `setBackend`), or `None` if first-found discovery is in effect. */
  def selectedName: Option[String] = _selectedName
