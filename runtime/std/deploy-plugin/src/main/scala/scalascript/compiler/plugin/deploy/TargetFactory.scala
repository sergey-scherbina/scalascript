package scalascript.compiler.plugin.deploy

/** Resolves a target `kind` string to a concrete DeployTarget.
 *  Consulted by `ssc deploy` when executing a manifest target. */
object TargetFactory:

  def make(kind: String, config: Map[String, Any]): DeployTarget =
    kind match
      case "container" =>
        new ContainerTarget()
      case "k8s" | "kubernetes" =>
        new K8sTarget()
      case "traditional" =>
        val port = config.get("port").collect { case n: Int => n }.getOrElse(8080)
        new LocalSubprocessTarget(port)
      case other =>
        throw DeployError(s"[deploy/unknown-target-kind] Unknown target kind '$other'. Supported: container, k8s, traditional")
