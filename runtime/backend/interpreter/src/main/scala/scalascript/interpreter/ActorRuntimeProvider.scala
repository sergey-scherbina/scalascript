package scalascript.interpreter

/** Interpreter-local actor runtime seam.
 *
 *  Actors own a cooperative scheduler and must step `Computation` trees, so they cannot use
 *  the host-neutral `BlockForm` SPI without leaking interpreter internals into every plugin.
 *  This narrow seam lets the current core implementation be delegated first, then moved into
 *  an interpreter-only actors plugin in a later slice.
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
  def runCoreActorRuntime(initial: Computation): Computation

trait ActorRuntimeProviderBackend:
  def actorRuntimeProvider: ActorRuntimeProvider

object CoreActorRuntimeProvider extends ActorRuntimeProvider:
  def open(host: ActorRuntimeHost): ActorRuntimeSession =
    new ActorRuntimeSession:
      def runActors(initial: Computation): Computation =
        host.runCoreActorRuntime(initial)
