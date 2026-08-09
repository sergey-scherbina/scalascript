package scalascript.compiler.plugin.deploy

enum WorkloadMode:
  case Deployment
  case StatefulSet
  case DaemonSet

enum ScalePolicy:
  case Cpu(targetPercent: Int)
  case Custom(metricName: String, targetValue: Long)
  case None

trait ClusterTarget extends DeployTarget:
  def seedUrlsFor(env: DeployEnvironment): List[String]
  def injectAuthToken(env: DeployEnvironment, secretRef: String): Unit
  def emitWorkloadManifest(mode: WorkloadMode): String
  def emitHeadlessService(): String
  def emitAutoscaler(policy: ScalePolicy): Option[String]
