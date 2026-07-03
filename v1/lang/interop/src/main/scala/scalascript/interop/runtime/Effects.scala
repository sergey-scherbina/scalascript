package scalascript.interop.runtime

/** Tier 2 of the Scala ↔ ScalaScript interop (specs/scala-interop.md).
 *
 *  Scala-friendly wrappers around the ScalaScript effect runtime
 *  (`_ssc_runtime._handle`, `_ssc_runtime.runActors`, …).
 *
 *  **v0.1 surface (this file).**  Today's wrappers are deliberately
 *  thin: the ScalaScript effect runtime types (`Cont`, `Eff`, `Handler`)
 *  live inside the user-linked JAR — we can't import them at compile
 *  time from the interop library (chicken-and-egg).  So v0.1 exposes
 *  the API shape with synchronous + best-effort error reporting, and
 *  defers the deeper handler-composition surface to v0.2 (once the
 *  runtime types stabilise as a published artifact, or once Tier 4
 *  ships `--emit-scala-facade` and the runtime classes are on a
 *  predictable classpath).
 *
 *  Today these wrappers are stubs that document the intent and the
 *  TODOs.  Their existence makes the API contract reviewable without
 *  blocking on internal-runtime reshaping; downstream consumers can
 *  build against the public surface and only the bodies change as
 *  the runtime stabilises. */
object Effects:

  /** A ScalaScript computation that may perform effects, packaged for
   *  cross-language consumption.
   *
   *  The thunk is by-name so the caller can defer evaluation until
   *  inside `runEffects` (where the handler scope is set up).  Once the
   *  runtime types are properly bridged, the inner type will change
   *  from `Any` to something more precise (`Eff[A]` or similar). */
  type Effectful[A] = () => A

  /** Wrap a ScalaScript-compiled call into an `Effectful[A]` value.
   *  Sugar for `() => body`; lets call sites read naturally:
   *
   *  {{{
   *  val sum = Effects.runEffects(effect { std.foo.compute(42) })
   *  }}}
   */
  def effect[A](body: => A): Effectful[A] = () => body

  /** Run an effectful ScalaScript computation from regular Scala code.
   *
   *  v0.1 semantics — best-effort synchronous execution:
   *    - Invokes the thunk on the calling thread.
   *    - Catches any throwable; converts `_ssc_runtime`-thrown
   *      `UnhandledEffect` (recognised by class name) into
   *      `Left(EffectError.UnhandledEffect(name))`.
   *    - Other throwables surface as `Left(EffectError.Crash(t))`.
   *    - Success: `Right(a)`.
   *
   *  v0.2 will replace the catch-by-class-name with proper handler
   *  registration via reflection against the loaded runtime; the API
   *  shape stays the same. */
  def runEffects[A](computation: Effectful[A]): Either[EffectError, A] =
    try Right(computation())
    catch
      case t: Throwable =>
        val className = t.getClass.getName
        if className.contains("UnhandledEffect") then
          // ScalaScript's runtime carries the unhandled effect name in
          // its message.  Strip the class prefix for cleaner downstream
          // display.
          Left(EffectError.UnhandledEffect(Option(t.getMessage).getOrElse("<unknown>")))
        else
          Left(EffectError.Crash(t))

  /** Async variant returning `Future[A]`.  Currently a thin wrapper
   *  using `scala.concurrent.Future.apply`; calls `runEffects` inside
   *  the future to preserve the same error mapping.
   *
   *  v0.2 will integrate with the runtime's own actor/coroutine pool
   *  for true non-blocking dispatch. */
  def runEffectsAsync[A](
      computation: Effectful[A]
  )(using ec: scala.concurrent.ExecutionContext): scala.concurrent.Future[A] =
    scala.concurrent.Future {
      runEffects(computation) match
        case Right(a)  => a
        case Left(err) => throw EffectExecutionException(err)
    }

  /** ADT of error variants `runEffects` can return.  Distinct cases
   *  let callers branch on cause without string-matching. */
  enum EffectError:
    /** ScalaScript performed an effect that no handler intercepted.
     *  `name` is the effect operation's identifier as recorded by the
     *  runtime (best-effort from the exception message). */
    case UnhandledEffect(name: String)
    /** Any other Throwable thrown inside the computation. */
    case Crash(cause: Throwable)

  /** Thin wrapper exception used by the async variant to surface an
   *  `EffectError` through the `Future` failure channel without
   *  losing the structured cause. */
  class EffectExecutionException(val error: EffectError)
    extends RuntimeException(s"ScalaScript effect failed: $error")

end Effects
