package scalascript.compiler.plugin.deploy

/** Generates a systemd service unit file for a ScalaScript application.
 *
 *  Supports FatJar (java -jar), NativeBinary (./app), and NodeBundle (node app.js).
 */
object SystemdUnitGenerator:

  case class SystemdConfig(
    description:     String             = "ScalaScript Application",
    user:            String             = "root",
    workingDir:      String             = "/opt/app",
    artifactKind:    ArtifactKind       = ArtifactKind.FatJar,
    jvmOpts:         String             = "-Xmx512m",
    appPort:         Int                = 8080,
    envVars:         Map[String,String] = Map.empty,
    restartPolicy:   String             = "always",
    timeoutStopSec:  Int                = 30,
  )

  def generate(appName: String, cfg: SystemdConfig): String =
    val description = if cfg.description == "ScalaScript Application" then s"ScalaScript $appName" else cfg.description
    val execStart = cfg.artifactKind match
      case ArtifactKind.FatJar       => s"java ${cfg.jvmOpts} -jar ${cfg.workingDir}/app.jar"
      case ArtifactKind.NativeBinary => s"${cfg.workingDir}/app"
      case ArtifactKind.NodeBundle   => s"node ${cfg.workingDir}/app.js"
      case other                     => throw DeployError(s"[deploy/systemd-unsupported] Cannot generate systemd unit for $other")

    val envLines = cfg.envVars.map { case (k, v) => s"Environment=$k=$v" }.mkString("\n")
    val envSection = if envLines.nonEmpty then s"\n$envLines" else ""

    s"""|[Unit]
        |Description=$description
        |After=network.target
        |
        |[Service]
        |Type=simple
        |User=${cfg.user}
        |WorkingDirectory=${cfg.workingDir}$envSection
        |ExecStart=$execStart
        |Restart=${cfg.restartPolicy}
        |TimeoutStopSec=${cfg.timeoutStopSec}
        |KillMode=mixed
        |
        |[Install]
        |WantedBy=multi-user.target
        |""".stripMargin

  /** Returns the standard unit file name for a service. */
  def unitName(appName: String): String = s"$appName.service"
