package scalascript.frontend

import java.util.ServiceLoader
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** Discovery + selection of `FrontendFrameworkSpi` implementations
 *  at codegen / runtime.  Mirrors the
 *  `scalascript.server.spi.HttpServerBackends` registry pattern
 *  from the v1.17.6 HTTP/WS SPI.
 *
 *  Hybrid mechanism:
 *
 *  - `ServiceLoader[FrontendFrameworkSpi]` returns every impl
 *    registered via
 *    `META-INF/services/scalascript.frontend.FrontendFrameworkSpi`
 *    that is on the classpath.  Default ssc bundles only
 *    `CustomFrameworkBackend`; adding React / Vue / Solid is an
 *    sbt opt-in.
 *
 *  - If multiple impls are on the classpath, `setBackend(name)`
 *    picks by `FrontendFrameworkSpi.name`.  Unset = first-found
 *    wins.  User code:
 *    ```scala
 *    setFrontendFramework("solid")
 *    // … define components …
 *    mount(App(), into = "#app")
 *    ```
 *
 *  - `current` returns the resolved backend (cached after first
 *    call); reset when `setBackend` changes the choice.
 *
 *  - `register(impl)` adds an impl programmatically — used by
 *    codegen-emitted scripts where the script-level classpath
 *    doesn't carry `META-INF/services` files.
 *
 *  See `docs/frontend-framework-spi-plan.md`. */
object FrontendFrameworks:

  @volatile private var _selectedName: Option[String]              = None
  @volatile private var _cached:       FrontendFrameworkSpi | Null = null

  private val _programmatic: ConcurrentLinkedQueue[FrontendFrameworkSpi] =
    new ConcurrentLinkedQueue[FrontendFrameworkSpi]()

  /** Add an impl outside the ServiceLoader path.  Idempotent —
   *  calling twice with the same impl class is a no-op. */
  def register(impl: FrontendFrameworkSpi): Unit =
    val existing = _programmatic.iterator().asScala.exists(_.name == impl.name)
    if !existing then _programmatic.add(impl)
    _cached = null

  /** All discovered impls — programmatic registrations first
   *  (so they take priority if names overlap), then ServiceLoader
   *  in classpath order. */
  def all(): List[FrontendFrameworkSpi] =
    val prog = _programmatic.iterator().asScala.toList
    val svc  = ServiceLoader.load(classOf[FrontendFrameworkSpi]).iterator().asScala.toList
    // Dedup by name, preserving first occurrence.
    (prog ++ svc).foldLeft(List.empty[FrontendFrameworkSpi]) { (acc, impl) =>
      if acc.exists(_.name == impl.name) then acc else acc :+ impl
    }

  /** Pick a backend by name.  Subsequent `current` calls return
   *  the matching impl.  If no impl with that name is on the
   *  classpath, throws `IllegalStateException` (loud failure
   *  beats silent fallback).  Pass `null` to reset to first-found. */
  def setBackend(name: String | Null): Unit =
    _selectedName = Option(name)
    _cached = null
    if name != null then
      val impls = all()
      if !impls.exists(_.name == name) then
        throw new IllegalStateException(
          s"No FrontendFrameworkSpi impl named '$name' on classpath.  " +
          s"Available: [${impls.map(_.name).mkString(", ")}].  " +
          s"Add the right module to your build (e.g. " +
          s"`dependsOn(frontendReact)` for react)."
        )

  /** Resolve the active backend.  Cached. */
  def current(): FrontendFrameworkSpi =
    val cached = _cached
    if cached != null then cached
    else
      val impls = all()
      if impls.isEmpty then
        throw new IllegalStateException(
          "No FrontendFrameworkSpi impl on classpath — add at least " +
          "`frontend-custom` (the default in-house runtime) to your " +
          "dependencies."
        )
      val chosen = _selectedName match
        case Some(n) => impls.find(_.name == n).getOrElse(
                          throw new IllegalStateException(
                            s"FrontendFrameworkSpi name='$n' selected but no " +
                            s"impl with that name registered."))
        case None    => impls.head
      _cached = chosen
      chosen

  /** Currently-selected backend name, or `None` for first-found
   *  semantics. */
  def selectedName: Option[String] = _selectedName
