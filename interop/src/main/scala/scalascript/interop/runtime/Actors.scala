package scalascript.interop.runtime

/** Tier 2 of the Scala ↔ ScalaScript interop (docs/scala-interop.md).
 *
 *  Typed Scala-side façade for ScalaScript actors.  Same v0.1 caveat as
 *  [[Effects]]: the underlying runtime types (`ActorRef`, `_runActors`)
 *  live inside the user-linked JAR.  Today's API documents intent and
 *  provides a thin wrapper boundary; deeper integration follows once
 *  Tier 4 (`--emit-scala-facade`) puts the runtime classes on a stable
 *  classpath OR the runtime ships as a published library.
 *
 *  The typing here is intentionally simple: `ActorRef[T]` is a phantom
 *  type tag plus an opaque `Any` reference to the underlying actor.
 *  Real type-safety arrives when the runtime exposes type-tagged
 *  references; the boundary is correct shape-wise either way. */
object Actors:

  /** Opaque-shaped typed reference to a ScalaScript actor.  The phantom
   *  `T` type parameter lets the Scala compiler enforce message-type
   *  discipline at the call site; the underlying `ref` is the raw
   *  ScalaScript actor reference produced by `_ssc_runtime.spawn`. */
  final case class ActorRef[T](private[Actors] val ref: AnyRef):
    /** Send a typed message.  v0.1 dispatches through whatever method
     *  is exposed by the runtime's actor-ref class via reflection (the
     *  spawn loader sets the dispatch hook once at startup). */
    def send(msg: T): Unit = SendHook.dispatch(ref, msg)

  /** Hook that the runtime registers once it's loaded.  Lets the
   *  interop library remain decoupled from the actual runtime types
   *  while still routing `actorRef.send(msg)` to the right place.
   *
   *  Users who wire up the runtime via `ScalascriptLoader` should
   *  call `SendHook.install` with a function that knows how to invoke
   *  the runtime's `!` operator.  Without an install, `send` throws a
   *  clear error. */
  object SendHook:
    @volatile private var dispatcher: Option[(AnyRef, Any) => Unit] = None

    /** Register the dispatch function.  Replaces any prior install. */
    def install(fn: (AnyRef, Any) => Unit): Unit = dispatcher = Some(fn)

    /** Clear the hook — primarily useful in tests so suites don't
     *  pollute each other's state. */
    def clear(): Unit = dispatcher = None

    /** Internal: dispatch a send.  Throws clearly when no hook is set. */
    private[Actors] def dispatch(ref: AnyRef, msg: Any): Unit =
      dispatcher match
        case Some(fn) => fn(ref, msg)
        case None     =>
          throw new IllegalStateException(
            "scalascript-interop: Actors.SendHook not installed.  " +
            "Call Actors.SendHook.install(...) once at startup with a " +
            "dispatcher that knows how to invoke the runtime's `!` operator " +
            "on its actor-ref type.  Until then, `actorRef.send` is a no-op."
          )

  /** Wrap a raw ScalaScript actor reference into a typed [[ActorRef]].
   *
   *  Use this only when interoperating with code that hands you a
   *  runtime-typed `AnyRef` (e.g. from `ScalascriptLoader.call` against
   *  a function that returns an actor).  The `T` is purely phantom —
   *  the caller asserts the message type.  Mistype it and `send` will
   *  fail at runtime inside the underlying actor. */
  def wrap[T](rawRef: AnyRef): ActorRef[T] = ActorRef[T](rawRef)

  /** Spawn a Scala-side actor backed by the ScalaScript runtime.
   *
   *  v0.1 is a placeholder — true spawn requires the runtime types.
   *  The signature is published so consumers can write call sites that
   *  will type-check forever; the implementation lights up once the
   *  runtime exposes a stable entrypoint. */
  def spawn[T](behavior: T => Unit): ActorRef[T] =
    throw new NotImplementedError(
      "scalascript-interop v0.1: Actors.spawn is not yet implemented.  " +
      "For now, spawn actors inside ScalaScript code and pass the " +
      "resulting reference to Scala via `Actors.wrap`."
    )

end Actors
