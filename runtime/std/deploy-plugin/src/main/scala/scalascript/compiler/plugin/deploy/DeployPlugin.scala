package scalascript.compiler.plugin.deploy

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Backend-registry entry for the deploy system.
 *  All real deploy logic lives in DeployOrchestrator / DeployTarget.
 *  This class merely makes the plugin discoverable via ServiceLoader. */
class DeployPlugin extends Backend:
  def id:          String = "scalascript-deploy"
  def displayName: String = "Deploy Plugin"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("DeployPlugin is a CLI-time component — use `ssc deploy`")))
