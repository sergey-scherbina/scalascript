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
      case "rsync" =>
        new RsyncTarget()
      case "sftp" | "ftp" =>
        new SftpTarget()
      case "static" =>
        new StaticTarget()
      case "traditional" =>
        val transport = config.get("transport").collect { case s: String => s }.getOrElse("subprocess")
        transport match
          case "ssh+systemd" | "ssh" => new SshSystemdTarget()
          case "rsync"               => new RsyncTarget()
          case "sftp" | "ftp"       => new SftpTarget()
          case _                    =>
            val port = config.get("port").collect { case n: Int => n }.getOrElse(8080)
            new LocalSubprocessTarget(port)
      case other =>
        throw DeployError(s"[deploy/unknown-target-kind] Unknown target kind '$other'. Supported: container, k8s, static, traditional, rsync, sftp")
