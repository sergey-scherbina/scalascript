package scalascript.interpreter

import scala.meta.*

/** Interpreter-side of the actor runtime seam.
 *
 *  This trait is mixed into `Interpreter` and implements `ActorRuntimeHost` by
 *  forwarding each method to the interpreter internals it knows about.  The
 *  scheduler itself (`ActorScheduler`) lives in `runtime/std/actors-plugin` and
 *  is loaded via the `ActorRuntimeProvider` seam.
 *
 *  `receive` syntax and `ActorGlobals` stay in core because they capture
 *  ScalaMeta `Case` trees and lexical `Env`; the plugin accesses them through
 *  the typed host methods. */
private[interpreter] trait ActorInterp extends ActorRuntimeHost:
  this: Interpreter =>

  // ── Provider / session lifecycle ────────────────────────────────────────
  @volatile private var _actorRuntimeActive: Boolean = false
  private var actorRuntimeProvider: ActorRuntimeProvider = MissingActorRuntimeProvider
  private var actorRuntimeSession: ActorRuntimeSession   = null

  private[interpreter] def installActorRuntimeProvider(provider: ActorRuntimeProvider): Unit =
    actorRuntimeProvider = provider
    actorRuntimeSession  = null   // clear cached session so the new provider opens a fresh one

  private def currentActorRuntimeSession: ActorRuntimeSession =
    if actorRuntimeSession == null then
      actorRuntimeSession = actorRuntimeProvider.open(this)
    actorRuntimeSession

  // ── ActorRuntimeHost implementation ────────────────────────────────────
  // `out` is satisfied by Interpreter's constructor parameter `val out`.

  def actorCallValue(fn: Value, args: List[Value], env: Env): Computation =
    callValue(fn, args, env)

  def actorCallValue1(fn: Value, arg: Value, env: Env): Computation =
    callValue1(fn, arg, env)

  def actorReceiveSpec(specId: Long): (List[Case], Env) =
    receiveSpecs(specId)

  def actorMatchReceive(cases: List[Case], env: Env, msg: Value): Option[Computation] =
    val it = cases.iterator
    while it.hasNext do
      val c = it.next()
      val extEnv = PatternRuntime.matchPat(c.pat, msg, env, this)
      if extEnv != null then
        val guardOk = c.cond.forall { g =>
          Computation.run(eval(g, extEnv)) match
            case Value.BoolV(b) => b
            case _              => false
        }
        if guardOk then return Some(eval(c.body, extEnv))
    None

  def actorNativeFeatureGet(key: String): Option[Any]        = nativeFeatureGet(key)
  def actorNativeFeatureSet(key: String, value: Any): Unit   = nativeFeatureSet(key, value)
  def actorNativeFeatureRemove(key: String): Option[Any]     = nativeFeatureRemove(key)

  def actorOpenWsClient(
      url: String,
      headers: Map[String, String],
      protocols: List[String]
  ): InterpreterWsClientSession =
    InterpreterServerSupport.current.openWsClient(this, url, headers, protocols, out)

  def actorRegisterWsRoute(path: String, handler: Value, protocols: List[String]): Unit =
    wsRoutes.register(path = path, handler = handler, interp = this, protocols = protocols)

  def actorRegisterHttpRoute(method: String, path: String, handler: Value): Unit =
    routeRegistry.register(method, path, handler, this)

  def actorRemoteHandlerInfos: List[scalascript.backend.spi.RemoteHandlerInfo] =
    remoteHandlerInfos

  def actorCodeIdentity: Value = currentCodeIdentity

  // Cluster HTTP routes are registered by ActorScheduler during `startNode`.
  // This no-op satisfies the interface for hosts that don't run a live cluster.
  def actorRegisterClusterRoutes(): Unit = ()

  def actorIsRuntimeActive: Boolean                      = _actorRuntimeActive
  def actorSetRuntimeActive(on: Boolean): Unit           = _actorRuntimeActive = on

  /** True when `runActors { ... }` is active.  Used by `ActorGlobals.install`
   *  to make `stop()` a no-op outside actor scope. */
  private[interpreter] def isActorRuntimeActive: Boolean = _actorRuntimeActive

  // ── Entry point called by EvalRuntime ──────────────────────────────────
  private[interpreter] def actorInterp(initial: Computation): Computation =
    currentActorRuntimeSession.runActors(initial)

  /** @deprecated The scheduler now lives in ActorScheduler (actors-plugin). */
  def runCoreActorRuntime(initial: Computation): Computation =
    throw InterpretError("runCoreActorRuntime: use ActorScheduler via the provider seam (load ActorsInterpreterPlugin)")
