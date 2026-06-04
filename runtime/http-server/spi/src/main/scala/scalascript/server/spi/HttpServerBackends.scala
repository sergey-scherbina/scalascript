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
 *  See `docs/specs/http-server-spi-plan.md` for the full design rationale. */
object HttpServerBackends:

  @volatile private var _selectedName: Option[String]   = None
  @volatile private var _cached:       HttpServerSpi | Null = null
  // Programmatic registry — populated either by `register(impl)` from
  // user / codegen code, or implicitly by `ServiceLoader`-discovery in
  // sbt-driven JVM apps.  The codegen-emitted scala-cli script ends up
  // without `META-INF/services` (the resource is in
  // `runtime-server-jvm`'s jar but the generated script links sources,
  // not jars), so the inlined `_main` calls `register(JdkServerBackend)`
  // to seed the default.
  private val _registered: java.util.concurrent.ConcurrentLinkedQueue[HttpServerSpi] =
    java.util.concurrent.ConcurrentLinkedQueue[HttpServerSpi]()

  /** Explicitly register an `HttpServerSpi` impl.  Used by the
   *  codegen-emitted runtime (where `META-INF/services` doesn't survive
   *  the source-inlining pipeline) to seed the default `JdkServerBackend`
   *  at startup.  Production sbt apps don't need this — ServiceLoader
   *  discovery already covers them.  Idempotent for the same impl
   *  instance. */
  def register(impl: HttpServerSpi): Unit =
    if !_registered.contains(impl) then
      _registered.add(impl)
      _cached = null  // re-resolve on next current()

  /** All discovered backends, in (registered-then-ServiceLoader) order.
   *  Re-runs the ServiceLoader each call (cheap; the loader is cached
   *  internally). */
  def all(): List[HttpServerSpi] =
    val explicit = _registered.iterator().asScala.toList
    val viaSpi   = ServiceLoader.load(classOf[HttpServerSpi]).iterator().asScala.toList
    // De-dup by class name in case the same impl is both registered
    // and ServiceLoader-discovered.
    val seen = scala.collection.mutable.Set.empty[String]
    (explicit ++ viaSpi).filter { impl =>
      val cls = impl.getClass.getName
      if seen.contains(cls) then false else { seen.add(cls); true }
    }

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
