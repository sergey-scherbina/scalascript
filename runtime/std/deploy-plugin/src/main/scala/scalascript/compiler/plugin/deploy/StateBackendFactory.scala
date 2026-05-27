package scalascript.compiler.plugin.deploy

/** Resolves state backend kind → `StateBackend` implementation.
 *
 *  Config block (from manifest `state:` key):
 *    backend  — local | s3 | consul | etcd (default: local)
 *    + backend-specific keys (bucket, prefix, address, endpoints, …)
 */
object StateBackendFactory:

  def make(config: Map[String, Any]): StateBackend =
    config.get("backend").collect { case s: String => s.toLowerCase }.getOrElse("local") match
      case "local" | "file"  => new LocalFileStateBackend(config)
      case "s3"               => new S3StateBackend(config)
      case "consul"           => new ConsulStateBackend(config)
      case "etcd"             => new EtcdStateBackend(config)
      case other              => throw DeployError(s"[deploy/state/unknown-backend] '$other'. Supported: local, s3, consul, etcd")

  def requireRemoteForProduction(env: DeployEnvironment, backend: StateBackend): Unit =
    if env.purpose == EnvironmentPurpose.Production && backend.isInstanceOf[LocalFileStateBackend] then
      throw DeployError(
        s"[deploy/state/production-requires-remote] environment '${env.name}' has purpose: production " +
        "but is using a local file state backend. Configure state: { backend: s3 | consul | etcd } in the manifest.")
