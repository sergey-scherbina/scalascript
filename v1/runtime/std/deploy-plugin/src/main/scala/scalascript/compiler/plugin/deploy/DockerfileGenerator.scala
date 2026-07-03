package scalascript.compiler.plugin.deploy

/** Generates a Dockerfile for each supported ArtifactKind.
 *  Base images chosen for minimal footprint:
 *    FatJar       → eclipse-temurin:21-jre-alpine
 *    NativeBinary → gcr.io/distroless/cc (no shell, minimal libc stubs)
 *    NodeBundle   → node:22-alpine
 *    SpaBundle    → nginx:alpine (static serve under /usr/share/nginx/html)
 */
object DockerfileGenerator:

  case class DockerfileConfig(
    appPort:   Int             = 8080,
    buildArgs: Map[String,String] = Map.empty,
    labels:    Map[String,String] = Map.empty,
    extraEnv:  Map[String,String] = Map.empty,
  )

  def generate(kind: ArtifactKind, cfg: DockerfileConfig = DockerfileConfig()): String =
    kind match
      case ArtifactKind.FatJar       => fatJar(cfg)
      case ArtifactKind.NativeBinary => nativeBinary(cfg)
      case ArtifactKind.NodeBundle   => nodeBundle(cfg)
      case ArtifactKind.SpaBundle    => spaBundle(cfg)
      case other =>
        throw DeployError(s"[deploy/dockerfile-unsupported] DockerfileGenerator does not support $other")

  private def labelLines(labels: Map[String,String]): String =
    if labels.isEmpty then ""
    else labels.map { case (k,v) => s"""LABEL $k="$v"""" }.mkString("\n") + "\n"

  private def envLines(env: Map[String,String]): String =
    if env.isEmpty then ""
    else env.map { case (k,v) => s"""ENV $k="$v"""" }.mkString("\n") + "\n"

  private def buildArgLines(args: Map[String,String]): String =
    if args.isEmpty then ""
    else args.map { case (k,v) => s"ARG $k=$v" }.mkString("\n") + "\n"

  private def fatJar(cfg: DockerfileConfig): String =
    s"""|FROM eclipse-temurin:21-jre-alpine
        |WORKDIR /app
        |${buildArgLines(cfg.buildArgs)}${labelLines(cfg.labels)}${envLines(cfg.extraEnv)}COPY app.jar app.jar
        |EXPOSE ${cfg.appPort}
        |HEALTHCHECK --interval=10s --timeout=3s CMD wget -qO- http://localhost:${cfg.appPort}/_health || exit 1
        |ENTRYPOINT ["java", "-jar", "app.jar"]
        |""".stripMargin

  private def nativeBinary(cfg: DockerfileConfig): String =
    s"""|FROM gcr.io/distroless/cc
        |WORKDIR /app
        |${buildArgLines(cfg.buildArgs)}${labelLines(cfg.labels)}${envLines(cfg.extraEnv)}COPY app app
        |EXPOSE ${cfg.appPort}
        |ENTRYPOINT ["/app/app"]
        |""".stripMargin

  private def nodeBundle(cfg: DockerfileConfig): String =
    s"""|FROM node:22-alpine
        |WORKDIR /app
        |${buildArgLines(cfg.buildArgs)}${labelLines(cfg.labels)}${envLines(cfg.extraEnv)}COPY app.js app.js
        |EXPOSE ${cfg.appPort}
        |HEALTHCHECK --interval=10s --timeout=3s CMD wget -qO- http://localhost:${cfg.appPort}/_health || exit 1
        |ENTRYPOINT ["node", "app.js"]
        |""".stripMargin

  private def spaBundle(cfg: DockerfileConfig): String =
    s"""|FROM nginx:alpine
        |${buildArgLines(cfg.buildArgs)}${labelLines(cfg.labels)}${envLines(cfg.extraEnv)}COPY dist/ /usr/share/nginx/html
        |EXPOSE 80
        |HEALTHCHECK --interval=10s --timeout=3s CMD wget -qO- http://localhost:80/ || exit 1
        |""".stripMargin

  /** Writes the generated Dockerfile to `dir/Dockerfile`. */
  def writeDockerfile(kind: ArtifactKind, dir: os.Path, cfg: DockerfileConfig = DockerfileConfig()): os.Path =
    val content = generate(kind, cfg)
    val path = dir / "Dockerfile"
    os.write.over(path, content)
    path
