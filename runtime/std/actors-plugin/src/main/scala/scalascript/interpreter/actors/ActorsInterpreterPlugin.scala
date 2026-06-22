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

  override def preludeSymbols: List[ExportedSymbol] = List(
    ExportedSymbol("runActors", "runActors", "def", "Any"),
    ExportedSymbol("spawn", "spawn", "def", "Any"),
    ExportedSymbol("spawnBounded", "spawnBounded", "def", "Any"),
    ExportedSymbol("spawn_link", "spawn_link", "def", "Any"),
    ExportedSymbol("self", "self", "def", "Any"),
    ExportedSymbol("actorRef", "actorRef", "def", "Any"),
    ExportedSymbol("actorRefAddress", "actorRefAddress", "def", "Any"),
    ExportedSymbol("actorRefIsLocal", "actorRefIsLocal", "def", "Any"),
    ExportedSymbol("actorRefTryLocal", "actorRefTryLocal", "def", "Any"),
    ExportedSymbol("actorRefPublish", "actorRefPublish", "def", "Any"),
    ExportedSymbol("registerBehavior", "registerBehavior", "def", "Any"),
    ExportedSymbol("spawnRemote", "spawnRemote", "def", "Any"),
    ExportedSymbol("exit", "exit", "def", "Any"),
    ExportedSymbol("stop", "stop", "def", "Any"),
    ExportedSymbol("link", "link", "def", "Any"),
    ExportedSymbol("monitor", "monitor", "def", "Any"),
    ExportedSymbol("demonitor", "demonitor", "def", "Any"),
    ExportedSymbol("trapExit", "trapExit", "def", "Any"),
  )

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("actors-plugin — interpreter only")))
