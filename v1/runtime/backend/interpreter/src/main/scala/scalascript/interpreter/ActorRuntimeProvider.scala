package scalascript.interpreter

/** Interpreter-local actor runtime seam.
 *
 *  Actors own a cooperative scheduler and must step `Computation` trees, so they cannot use
 *  the host-neutral `BlockForm` SPI without leaking interpreter internals into every plugin.
 *  The scheduler implementation lives in `runtime/std/actors-plugin/ActorScheduler.scala`;
 *  this file holds only the seam interfaces and the fallback stub.
 */
trait ActorRuntimeProvider:
  def open(host: ActorRuntimeHost): ActorRuntimeSession

trait ActorRuntimeSession:
  def runActors(initial: Computation): Computation

trait ActorRuntimeHost:
  def out: java.io.PrintStream
  def actorCallValue(fn: Value, args: List[Value], env: Env): Computation
  def actorCallValue1(fn: Value, arg: Value, env: Env): Computation
  def actorReceiveSpec(specId: Long): (List[scala.meta.Case], Env)
  def actorMatchReceive(cases: List[scala.meta.Case], env: Env, msg: Value): Option[Computation]
  def actorNativeFeatureGet(key: String): Option[Any]
  def actorNativeFeatureSet(key: String, value: Any): Unit
  def actorNativeFeatureRemove(key: String): Option[Any]
  def actorOpenWsClient(
      url: String,
      headers: Map[String, String],
      protocols: List[String]
  ): InterpreterWsClientSession
  def actorRegisterWsRoute(path: String, handler: Value, protocols: List[String]): Unit
  def actorRegisterHttpRoute(method: String, path: String, handler: Value): Unit
  def actorRemoteHandlerInfos: List[scalascript.backend.spi.RemoteHandlerInfo]
  def actorCodeIdentity: Value
  def actorRegisterClusterRoutes(): Unit
  def actorIsRuntimeActive: Boolean
  def actorSetRuntimeActive(on: Boolean): Unit
  def runCoreActorRuntime(initial: Computation): Computation

trait ActorRuntimeProviderBackend:
  def actorRuntimeProvider: ActorRuntimeProvider

/** Fallback provider used when no actors plugin has been installed.
 *  Fails with a clear diagnostic so the user knows to load the plugin. */
object MissingActorRuntimeProvider extends ActorRuntimeProvider:
  def open(host: ActorRuntimeHost): ActorRuntimeSession =
    new ActorRuntimeSession:
      def runActors(initial: Computation): Computation =
        throw InterpretError("runActors requires the actors plugin — load ActorsInterpreterPlugin or call installPlugins")

/** @deprecated kept for test backwards-compat; will be removed in a follow-up. */
object CoreActorRuntimeProvider extends ActorRuntimeProvider:
  def open(host: ActorRuntimeHost): ActorRuntimeSession =
    new ActorRuntimeSession:
      def runActors(initial: Computation): Computation =
        host.runCoreActorRuntime(initial)
