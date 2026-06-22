package scalascript.interpreter.actors

import scalascript.backend.spi.*
import scalascript.interpreter.{ActorRuntimeProvider, ActorRuntimeProviderBackend, CoreActorRuntimeProvider}
import scalascript.ir.{ExportedSymbol, NormalizedModule, QualifiedName}

/** Interpreter-only Actors runtime provider.
 *
 *  This skeleton makes Actors a bundled plugin in the same registry as the other extracted
 *  effects. For now it deliberately delegates to the existing core scheduler; a later slice
 *  moves the scheduler implementation behind this provider.
 */
class ActorsInterpreterPlugin extends Backend, ActorRuntimeProviderBackend:
  def id:          String = "scalascript-actors-interpreter"
  def displayName: String = "Actors runtime (Interpreter)"
  def spiVersion:  String = SpiVersion.Current
  def capabilities: Capabilities = Capabilities(
    features = Set.empty, outputs = Set.empty, options = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current))

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                       = Set.empty

  def actorRuntimeProvider: ActorRuntimeProvider = CoreActorRuntimeProvider

  private def sym(n: String): ExportedSymbol = ExportedSymbol(n, n, "def", "Any")

  /** core-min-prelude-migrate (actors): the actor/process/cluster keyword set, DECLARED here for
   *  `ssc check` (the keystone) and removed from the hardcoded Typer prelude `effectBuiltins`. The
   *  actors plugin is bundled (installBin stages it), so `BackendRegistry.inProcess` loads these in
   *  production. The runtime stays in core (the provider seam delegates to `CoreActorRuntimeProvider`),
   *  so these names still resolve at run time through `ActorInterp`/`ActorGlobals` — the typer does
   *  not enforce effect discharge, so a plain `Any` declaration is sufficient for `ssc check`. */
  override def preludeSymbols: List[ExportedSymbol] =
    List(
      // the runner
      "runActors",
      // process / actor primitives
      "spawn", "spawnLink", "spawn_link", "spawnBounded", "spawnRemote", "self", "send", "exit", "stop",
      "link", "monitor", "demonitor", "trapExit", "processInfo", "receive", "timeout", "recvFrom",
      // actor references / behaviors
      "actorRef", "actorRefAddress", "actorRefIsLocal", "actorRefTryLocal", "actorRefPublish",
      "registerBehavior",
      // cluster / node membership
      "startNode", "connectNode", "joinCluster",
      "register", "whereis", "globalRegister", "globalWhereis",
      "clusterMembers", "subscribeClusterEvents",
      "phiOf", "isSuspect", "selfNode", "clusterHealth", "broadcastHealth", "clusterIsDown",
      // leader election
      "electLeader", "currentLeader", "subscribeLeaderEvents", "setAutoReelect",
      "useRaftLeaderElection", "useExternalCoordinator", "leaderProtocol", "leaderHistory",
      // gossip / reconnect
      "setReconnectPolicy", "requestGossip",
      // cluster config
      "clusterConfigSet", "clusterConfigGet", "clusterConfigKeys", "subscribeConfigEvents",
      // draining
      "setDraining", "isDraining", "drainingPeers", "subscribeDrainEvents",
      // cluster metrics
      "clusterMetricSet", "clusterMetricGet", "clusterMetricSum", "clusterMetricNames",
      "subscribeMetricEvents",
      // timers
      "sendAfter", "sendInterval", "cancelTimer",
    ).map(sym)

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("actors-plugin — interpreter only")))
