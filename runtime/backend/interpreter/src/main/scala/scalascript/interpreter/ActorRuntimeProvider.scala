package scalascript.interpreter

/** Interpreter-local actor runtime seam.
 *
 *  Actors own a cooperative scheduler and must step `Computation` trees, so they cannot use
 *  the host-neutral `BlockForm` SPI without leaking interpreter internals into every plugin.
 *  This narrow seam lets the current core implementation be delegated first, then moved into
 *  an interpreter-only actors plugin in a later slice.
 */
private[interpreter] trait ActorRuntimeProvider:
  def runActors(host: ActorRuntimeHost, initial: Computation): Computation

private[interpreter] trait ActorRuntimeHost:
  private[interpreter] def runCoreActorRuntime(initial: Computation): Computation

private[interpreter] object CoreActorRuntimeProvider extends ActorRuntimeProvider:
  def runActors(host: ActorRuntimeHost, initial: Computation): Computation =
    host.runCoreActorRuntime(initial)
