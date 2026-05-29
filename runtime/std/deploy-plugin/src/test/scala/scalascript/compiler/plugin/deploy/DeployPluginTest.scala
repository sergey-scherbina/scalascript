package scalascript.compiler.plugin.deploy

import org.scalatest.funsuite.AnyFunSuite

class DeployPluginTest extends AnyFunSuite:

  // ── DeployDag ──────────────────────────────────────────────────────────────

  test("topoSort: linear chain"):
    val sorted = DeployDag.topoSort(
      List("a", "b", "c"),
      Map("b" -> List("a"), "c" -> List("b"))
    )
    assert(sorted == List("a", "b", "c"))

  test("topoSort: independent targets"):
    val sorted = DeployDag.topoSort(List("x", "y", "z"), Map.empty)
    assert(sorted.toSet == Set("x", "y", "z"))

  test("topoSort: diamond dependency"):
    val sorted = DeployDag.topoSort(
      List("a", "b", "c", "d"),
      Map("b" -> List("a"), "c" -> List("a"), "d" -> List("b", "c"))
    )
    assert(sorted.indexOf("a") < sorted.indexOf("b"))
    assert(sorted.indexOf("a") < sorted.indexOf("c"))
    assert(sorted.indexOf("b") < sorted.indexOf("d"))
    assert(sorted.indexOf("c") < sorted.indexOf("d"))

  test("topoSort: cycle detection"):
    val ex = intercept[DeployError] {
      DeployDag.topoSort(List("a", "b"), Map("a" -> List("b"), "b" -> List("a")))
    }
    assert(ex.getMessage.contains("dag-cycle"))

  test("toStages: two independent targets form one stage"):
    val stages = DeployDag.toStages(List("a", "b"), Map.empty)
    assert(stages.exists(s => s.contains("a") && s.contains("b")) || stages.size == 1)

  test("toStages: sequential deps form separate stages"):
    val stages = DeployDag.toStages(
      List("a", "b", "c"),
      Map("b" -> List("a"), "c" -> List("b"))
    )
    assert(stages.size >= 2)
    assert(stages(0).contains("a"))
    assert(stages.last.contains("c"))

  // ── DeployManifest ─────────────────────────────────────────────────────────

  test("DeployManifest.parse: empty raw map"):
    val dm = DeployManifest.parse(Map.empty)
    assert(dm.targets.isEmpty)
    assert(dm.groups.isEmpty)
    assert(dm.environments.isEmpty)

  test("DeployManifest.parse: target config preserved"):
    val rawDeploy = new java.util.LinkedHashMap[String, Any]()
    val targetCfg = new java.util.LinkedHashMap[String, Any]()
    targetCfg.put("kind", "traditional")
    targetCfg.put("transport", "subprocess")
    targetCfg.put("port", Integer.valueOf(8080))
    rawDeploy.put("api", targetCfg)
    val dm = DeployManifest.parse(Map("deploy" -> rawDeploy))
    assert(dm.targets.contains("api"))
    assert(dm.targets("api")("kind") == "traditional")

  test("DeployManifest.defaultEnv: prefers local purpose"):
    val envs = new java.util.LinkedHashMap[String, Any]()
    val prod = new java.util.LinkedHashMap[String, Any]()
    prod.put("purpose", "production")
    val local = new java.util.LinkedHashMap[String, Any]()
    local.put("purpose", "local")
    envs.put("production", prod)
    envs.put("local", local)
    val dm = DeployManifest.parse(Map("environments" -> envs))
    assert(DeployManifest.defaultEnv(dm) == "local")

  // ── Orchestrator ───────────────────────────────────────────────────────────

  test("orchestrator: parallel — all succeed"):
    val group = DeployGroup("g", List("a", "b", "c"), ExecMode.Parallel, Map.empty, FailurePolicy.RollbackAll, None)
    val (succeeded, failed, skipped) = DeployOrchestrator.run(
      group,
      (t, _) => Some(Map("url" -> s"http://$t")),
      _ => ()
    )
    assert(failed.isEmpty)
    assert(skipped.isEmpty)
    assert(succeeded.toSet == Set("a", "b", "c"))

  test("orchestrator: sequence — respects order"):
    val order = scala.collection.mutable.ListBuffer.empty[String]
    val group = DeployGroup("g", List("a", "b", "c"), ExecMode.Sequence, Map.empty, FailurePolicy.AbortRemaining, None)
    DeployOrchestrator.run(group, (t, _) => { order += t; Some(Map.empty) }, _ => ())
    assert(order.toList == List("a", "b", "c"))

  test("orchestrator: RollbackAll on failure rolls back succeeded"):
    val rolled = scala.collection.mutable.ListBuffer.empty[String]
    val group  = DeployGroup("g", List("a", "b", "c"), ExecMode.Sequence, Map.empty, FailurePolicy.RollbackAll, None)
    val (_, failed, _) = DeployOrchestrator.run(
      group,
      { case ("b", _) => None; case (_, _) => Some(Map.empty) },
      {
        case DeployEvent.RolledBack(t) => rolled += t
        case _                         => ()
      }
    )
    assert(failed.contains("b"))
    assert(rolled.contains("a"))

  test("orchestrator: ContinueRemaining skips dependents but deploys independents"):
    val group = DeployGroup(
      "g",
      List("backend", "frontend", "migrate"),
      ExecMode.Parallel,
      Map("frontend" -> List("backend")),
      FailurePolicy.ContinueRemaining,
      None
    )
    val (succeeded, failed, skipped) = DeployOrchestrator.run(
      group,
      { case ("backend", _) => None; case (_, _) => Some(Map.empty) },
      _ => ()
    )
    assert(failed.contains("backend"))
    assert(skipped.contains("frontend"))
    assert(succeeded.contains("migrate"))

  test("orchestrator: Pipeline — stages execute in order"):
    val order  = scala.collection.mutable.ListBuffer.empty[String]
    val stages = List(List("backend"), List("frontend"))
    val group  = DeployGroup("g", List("backend", "frontend"), ExecMode.Pipeline(stages), Map.empty, FailurePolicy.AbortRemaining, None)
    DeployOrchestrator.run(group, (t, _) => { order += t; Some(Map.empty) }, _ => ())
    assert(order.indexOf("backend") < order.indexOf("frontend"))

  // ── EnvironmentPurpose ─────────────────────────────────────────────────────

  test("EnvironmentPurpose.parse: known values"):
    assert(EnvironmentPurpose.parse("local")      == EnvironmentPurpose.Local)
    assert(EnvironmentPurpose.parse("production") == EnvironmentPurpose.Production)
    assert(EnvironmentPurpose.parse("staging")    == EnvironmentPurpose.Staging)
    assert(EnvironmentPurpose.parse("test")       == EnvironmentPurpose.Test)

  test("EnvironmentPurpose.parse: unknown value throws"):
    intercept[DeployError] { EnvironmentPurpose.parse("unknown") }

  // ── StateBackend ───────────────────────────────────────────────────────────

  test("NoopStateBackend: read returns None"):
    assert(NoopStateBackend.read(StateKey("prod", "api")) == None)

  test("NoopStateBackend: write + lock + unlock are no-ops"):
    val key = StateKey("prod", "api", Some("blue"))
    val rec = StateRecord("prod", "api", Some("blue"), "v1", "sha256:abc", "2026-01-01T00:00:00Z", "ci", Map.empty)
    NoopStateBackend.write(key, rec)
    val handle = NoopStateBackend.lock(key, 60)
    NoopStateBackend.unlock(handle)
    assert(handle.key == key)

  // ── ArtifactRegistry ───────────────────────────────────────────────────────

  test("ArtifactRegistry.artifactKindFor: fat-jar"):
    assert(ArtifactRegistry.artifactKindFor("fat-jar") == ArtifactKind.FatJar)
    assert(ArtifactRegistry.artifactKindFor("FatJar")  == ArtifactKind.FatJar)

  test("ArtifactRegistry.artifactKindFor: spa-bundle"):
    assert(ArtifactRegistry.artifactKindFor("spa")        == ArtifactKind.SpaBundle)
    assert(ArtifactRegistry.artifactKindFor("spa-bundle") == ArtifactKind.SpaBundle)

  test("ArtifactRegistry.artifactKindFor: oci-image"):
    assert(ArtifactRegistry.artifactKindFor("oci")       == ArtifactKind.OciImage)
    assert(ArtifactRegistry.artifactKindFor("oci-image") == ArtifactKind.OciImage)

  test("ArtifactRegistry.artifactKindFor: unknown defaults to FatJar"):
    assert(ArtifactRegistry.artifactKindFor("unknown-kind") == ArtifactKind.FatJar)

  // ── DockerfileGenerator ────────────────────────────────────────────────────

  test("DockerfileGenerator: FatJar uses eclipse-temurin base image"):
    val df = DockerfileGenerator.generate(ArtifactKind.FatJar)
    assert(df.contains("eclipse-temurin:21-jre-alpine"))
    assert(df.contains("COPY app.jar app.jar"))
    assert(df.contains("app.jar"))
    assert(df.contains("EXPOSE 8080"))

  test("DockerfileGenerator: NativeBinary uses distroless base image"):
    val df = DockerfileGenerator.generate(ArtifactKind.NativeBinary)
    assert(df.contains("gcr.io/distroless/cc"))
    assert(df.contains("COPY app app"))
    assert(df.contains("/app/app"))

  test("DockerfileGenerator: NodeBundle uses node:22-alpine"):
    val df = DockerfileGenerator.generate(ArtifactKind.NodeBundle)
    assert(df.contains("node:22-alpine"))
    assert(df.contains("COPY app.js app.js"))
    assert(df.contains("app.js"))

  test("DockerfileGenerator: SpaBundle uses nginx:alpine"):
    val df = DockerfileGenerator.generate(ArtifactKind.SpaBundle)
    assert(df.contains("nginx:alpine"))
    assert(df.contains("COPY dist/ /usr/share/nginx/html"))
    assert(df.contains("EXPOSE 80"))

  test("DockerfileGenerator: custom appPort in FatJar"):
    val cfg = DockerfileGenerator.DockerfileConfig(appPort = 9090)
    val df  = DockerfileGenerator.generate(ArtifactKind.FatJar, cfg)
    assert(df.contains("EXPOSE 9090"))
    assert(df.contains("localhost:9090/_health"))

  test("DockerfileGenerator: build args emitted for FatJar"):
    val cfg = DockerfileGenerator.DockerfileConfig(buildArgs = Map("VERSION" -> "1.0"))
    val df  = DockerfileGenerator.generate(ArtifactKind.FatJar, cfg)
    assert(df.contains("ARG VERSION=1.0"))

  test("DockerfileGenerator: labels emitted for NativeBinary"):
    val cfg = DockerfileGenerator.DockerfileConfig(labels = Map("maintainer" -> "ci@example.com"))
    val df  = DockerfileGenerator.generate(ArtifactKind.NativeBinary, cfg)
    assert(df.contains("""LABEL maintainer="ci@example.com""""))

  test("DockerfileGenerator: unsupported kind throws"):
    intercept[DeployError] {
      DockerfileGenerator.generate(ArtifactKind.War)
    }

  test("DockerfileGenerator: writeDockerfile creates file on disk"):
    val dir = os.temp.dir()
    try
      val path = DockerfileGenerator.writeDockerfile(ArtifactKind.FatJar, dir)
      assert(os.exists(path))
      assert(path.last == "Dockerfile")
      val content = os.read(path)
      assert(content.contains("eclipse-temurin"))
    finally
      os.remove.all(dir)

  // ── TargetFactory ──────────────────────────────────────────────────────────

  test("TargetFactory: resolves container kind"):
    val t = TargetFactory.make("container", Map.empty)
    assert(t.kind == "container")
    assert(t.artifactKind == ArtifactKind.OciImage)

  test("TargetFactory: resolves traditional kind"):
    val t = TargetFactory.make("traditional", Map("port" -> Integer.valueOf(9090)))
    assert(t.kind == "traditional")

  test("TargetFactory: unknown kind throws"):
    intercept[DeployError] {
      TargetFactory.make("oracle-cloud", Map.empty)
    }

  // ── ContainerTarget dry-run ─────────────────────────────────────────────────

  test("ContainerTarget.outputs: returns image and digest keys"):
    val target = new ContainerTarget()
    val ctx = DeployContext(
      targetName = "myapp",
      config     = Map("registry" -> "ghcr.io/org", "tag" -> "v1.0"),
      env        = "local",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val outs = target.outputs(ctx)
    assert(outs.contains("image"))
    assert(outs("image").contains("ghcr.io/org/myapp:v1.0"))

  test("ContainerTarget.build: dry-run prints message without running docker"):
    val target = new ContainerTarget()
    val workDir = os.temp.dir()
    try
      // Create a dummy artifact so it can be located
      os.makeDir.all(workDir / ".ssc-artifacts")
      os.write(workDir / ".ssc-artifacts" / "app.jar", "")
      val ctx = DeployContext(
        targetName = "api",
        config     = Map("registry" -> "registry.example.com", "tag" -> "latest"),
        env        = "local",
        slot       = None,
        dryRun     = true,
        verbose    = false,
        outputsOf  = _ => Map.empty,
        workDir    = workDir,
      )
      val result = target.build(ctx)
      assert(result.artifactKind == ArtifactKind.OciImage)
      assert(result.artifactPath.contains("api"))
    finally
      os.remove.all(workDir)

  // ── K8sManifestGenerator ───────────────────────────────────────────────────

  test("K8sManifestGenerator.deployment: contains apiVersion apps/v1"):
    val cfg = K8sManifestGenerator.K8sConfig(namespace = "prod", replicas = 3, image = "myapp:v1")
    val yaml = K8sManifestGenerator.deployment("myapp", cfg)
    assert(yaml.contains("apiVersion: apps/v1"))
    assert(yaml.contains("kind: Deployment"))
    assert(yaml.contains("replicas: 3"))
    assert(yaml.contains("namespace: prod"))
    assert(yaml.contains("image: myapp:v1"))

  test("K8sManifestGenerator.deployment: liveness and readiness probes wired to /_health and /_ready"):
    val cfg  = K8sManifestGenerator.K8sConfig(appPort = 9090)
    val yaml = K8sManifestGenerator.deployment("api", cfg)
    assert(yaml.contains("/_health"))
    assert(yaml.contains("/_ready"))
    assert(yaml.contains("containerPort: 9090"))

  test("K8sManifestGenerator.deployment: PreStop exec sleep hook present"):
    val yaml = K8sManifestGenerator.deployment("svc", K8sManifestGenerator.K8sConfig())
    assert(yaml.contains("preStop"))
    assert(yaml.contains("sleep 5"))

  test("K8sManifestGenerator.service: selector points to app label"):
    val yaml = K8sManifestGenerator.service("myapp", K8sManifestGenerator.K8sConfig())
    assert(yaml.contains("kind: Service"))
    assert(yaml.contains("app: myapp"))
    assert(yaml.contains("port: 80"))

  test("K8sManifestGenerator.service: blue-green slot selector appended"):
    val yaml = K8sManifestGenerator.service("myapp", K8sManifestGenerator.K8sConfig(), activeSlot = Some("blue"))
    assert(yaml.contains("slot: blue"))

  test("K8sManifestGenerator.ingress: host and ingress class emitted"):
    val cfg  = K8sManifestGenerator.K8sConfig(ingressClass = "traefik")
    val yaml = K8sManifestGenerator.ingress("myapp", "api.example.com", cfg)
    assert(yaml.contains("kind: Ingress"))
    assert(yaml.contains("api.example.com"))
    assert(yaml.contains("traefik"))

  test("K8sManifestGenerator.configMap: emitted when configData is non-empty"):
    val cfg = K8sManifestGenerator.K8sConfig(configData = Map("LOG_LEVEL" -> "info"))
    val cm  = K8sManifestGenerator.configMap("myapp", cfg)
    assert(cm.isDefined)
    assert(cm.get.contains("kind: ConfigMap"))
    assert(cm.get.contains("LOG_LEVEL"))

  test("K8sManifestGenerator.configMap: absent when configData is empty"):
    val cm = K8sManifestGenerator.configMap("myapp", K8sManifestGenerator.K8sConfig())
    assert(cm.isEmpty)

  test("K8sManifestGenerator.secret: base64-encodes values"):
    val cfg = K8sManifestGenerator.K8sConfig(secretData = Map("DB_PASS" -> "s3cr3t"))
    val sec = K8sManifestGenerator.secret("myapp", cfg)
    assert(sec.isDefined)
    assert(sec.get.contains("kind: Secret"))
    val encoded = java.util.Base64.getEncoder.encodeToString("s3cr3t".getBytes("UTF-8"))
    assert(sec.get.contains(encoded))

  test("K8sManifestGenerator.bundle: Deployment + Service separated by ---"):
    val cfg  = K8sManifestGenerator.K8sConfig()
    val yaml = K8sManifestGenerator.bundle("app", cfg)
    assert(yaml.contains("kind: Deployment"))
    assert(yaml.contains("kind: Service"))
    assert(yaml.contains("---"))

  test("K8sManifestGenerator.blueGreenDeployments: blue replicas > 0, green starts at 0"):
    val cfg  = K8sManifestGenerator.K8sConfig(replicas = 3)
    val yaml = K8sManifestGenerator.blueGreenDeployments("app", cfg, "app:v2")
    assert(yaml.contains("name: app-blue"))
    assert(yaml.contains("name: app-green"))
    assert(yaml.contains("replicas: 3"))
    assert(yaml.contains("replicas: 0"))

  // ── K8sTarget ──────────────────────────────────────────────────────────────

  test("K8sTarget.kind and artifactKind"):
    val t = new K8sTarget()
    assert(t.kind == "k8s")
    assert(t.artifactKind == ArtifactKind.OciImage)

  test("K8sTarget.build: returns OciImage result with image from config"):
    val target = new K8sTarget()
    val ctx = DeployContext(
      targetName = "api",
      config     = Map("image" -> "registry.example.com/api:v2"),
      env        = "staging",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val result = target.build(ctx)
    assert(result.artifactKind == ArtifactKind.OciImage)
    assert(result.artifactPath == "registry.example.com/api:v2")

  test("K8sTarget.deploy: dry-run applies manifest without kubectl"):
    val target  = new K8sTarget()
    val workDir = os.temp.dir()
    try
      val ctx = DeployContext(
        targetName = "api",
        config     = Map("image" -> "app:v1", "namespace" -> "default"),
        env        = "staging",
        slot       = None,
        dryRun     = true,
        verbose    = false,
        outputsOf  = _ => Map.empty,
        workDir    = workDir,
      )
      val result = target.deploy(ctx, PushResult("app:v1"))
      assert(result.revision == "app:v1")
    finally
      os.remove.all(workDir)

  test("K8sTarget.rollback: dry-run reports without kubectl"):
    val target = new K8sTarget()
    val ctx = DeployContext(
      targetName = "api",
      config     = Map("namespace" -> "default"),
      env        = "staging",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val result = target.rollback(ctx, RevisionRef("v1"))
    assert(result.revision == "v1")

  test("K8sTarget.switch: dry-run flips slot blue → green"):
    val target = new K8sTarget()
    val ctx = DeployContext(
      targetName = "api",
      config     = Map("active_slot" -> "blue"),
      env        = "production",
      slot       = Some("blue"),
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val next = target.switch(ctx)
    assert(next == "green")

  test("TargetFactory: resolves k8s kind"):
    val t = TargetFactory.make("k8s", Map.empty)
    assert(t.kind == "k8s")
    val t2 = TargetFactory.make("kubernetes", Map.empty)
    assert(t2.kind == "k8s")

  // ── SystemdUnitGenerator ───────────────────────────────────────────────────

  test("SystemdUnitGenerator: FatJar unit contains java -jar"):
    val cfg  = SystemdUnitGenerator.SystemdConfig(artifactKind = ArtifactKind.FatJar, workingDir = "/opt/myapp")
    val unit = SystemdUnitGenerator.generate("myapp", cfg)
    assert(unit.contains("ExecStart=java"))
    assert(unit.contains("app.jar"))
    assert(unit.contains("/opt/myapp"))
    assert(unit.contains("[Service]"))
    assert(unit.contains("[Install]"))

  test("SystemdUnitGenerator: NativeBinary unit uses plain binary path"):
    val cfg  = SystemdUnitGenerator.SystemdConfig(artifactKind = ArtifactKind.NativeBinary, workingDir = "/srv/app")
    val unit = SystemdUnitGenerator.generate("svc", cfg)
    assert(unit.contains("/srv/app/app"))
    assert(!unit.contains("java"))

  test("SystemdUnitGenerator: NodeBundle unit uses node"):
    val cfg  = SystemdUnitGenerator.SystemdConfig(artifactKind = ArtifactKind.NodeBundle, workingDir = "/srv/node")
    val unit = SystemdUnitGenerator.generate("api", cfg)
    assert(unit.contains("node /srv/node/app.js"))

  test("SystemdUnitGenerator: env vars emitted as Environment= lines"):
    val cfg  = SystemdUnitGenerator.SystemdConfig(envVars = Map("PORT" -> "9000", "LOG_LEVEL" -> "info"))
    val unit = SystemdUnitGenerator.generate("svc", cfg)
    assert(unit.contains("Environment=PORT=9000"))
    assert(unit.contains("Environment=LOG_LEVEL=info"))

  test("SystemdUnitGenerator: unsupported kind throws"):
    intercept[DeployError] {
      SystemdUnitGenerator.generate("svc", SystemdUnitGenerator.SystemdConfig(artifactKind = ArtifactKind.War))
    }

  test("SystemdUnitGenerator.unitName: returns <name>.service"):
    assert(SystemdUnitGenerator.unitName("myapp") == "myapp.service")

  // ── SshSystemdTarget ──────────────────────────────────────────────────────

  test("SshSystemdTarget.kind and artifactKind"):
    val t = new SshSystemdTarget()
    assert(t.kind == "traditional")
    assert(t.artifactKind == ArtifactKind.FatJar)

  test("SshSystemdTarget.deploy: dry-run without SSH"):
    val target = new SshSystemdTarget()
    val ctx = DeployContext(
      targetName = "api",
      config     = Map("host" -> "example.com", "user" -> "deploy"),
      env        = "staging",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val result = target.deploy(ctx, PushResult("example.com:/opt/api/app.jar"))
    assert(result.url.exists(_.contains("example.com")))

  test("SshSystemdTarget.rollback: dry-run"):
    val target = new SshSystemdTarget()
    val ctx = DeployContext(
      targetName = "api",
      config     = Map("host" -> "example.com"),
      env        = "staging",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val result = target.rollback(ctx, RevisionRef(""))
    assert(result.revision == "stopped")

  test("SshSystemdTarget.outputs: returns host port url"):
    val target = new SshSystemdTarget()
    val ctx = DeployContext(
      targetName = "api",
      config     = Map("host" -> "vps.example.com", "app_port" -> Integer.valueOf(9090)),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val outs = target.outputs(ctx)
    assert(outs("host") == "vps.example.com")
    assert(outs("port") == "9090")
    assert(outs("url").contains("vps.example.com"))

  // ── RsyncTarget ───────────────────────────────────────────────────────────

  test("RsyncTarget.kind and artifactKind"):
    val t = new RsyncTarget()
    assert(t.kind == "rsync")
    assert(t.artifactKind == ArtifactKind.RsyncTree)

  test("RsyncTarget.push: dry-run without rsync"):
    val target  = new RsyncTarget()
    val workDir = os.temp.dir()
    try
      os.makeDir.all(workDir / ".ssc-artifacts" / "dist")
      val ctx = DeployContext(
        targetName = "frontend",
        config     = Map("host" -> "cdn.example.com", "user" -> "deploy"),
        env        = "staging",
        slot       = None,
        dryRun     = true,
        verbose    = false,
        outputsOf  = _ => Map.empty,
        workDir    = workDir,
      )
      val art    = BuildResult((workDir / ".ssc-artifacts" / "dist").toString, ArtifactKind.RsyncTree)
      val result = target.push(ctx, art)
      assert(result.ref.contains("cdn.example.com"))
    finally
      os.remove.all(workDir)

  test("RsyncTarget.outputs: returns host path url"):
    val target = new RsyncTarget()
    val ctx = DeployContext(
      targetName = "frontend",
      config     = Map("host" -> "cdn.example.com", "remote_path" -> "/var/www/site"),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val outs = target.outputs(ctx)
    assert(outs("host") == "cdn.example.com")
    assert(outs("path") == "/var/www/site")

  // ── SftpTarget ────────────────────────────────────────────────────────────

  test("SftpTarget.kind and artifactKind"):
    val t = new SftpTarget()
    assert(t.kind == "sftp")
    assert(t.artifactKind == ArtifactKind.Tarball)

  test("SftpTarget.push: dry-run without sftp"):
    val target = new SftpTarget()
    val ctx = DeployContext(
      targetName = "app",
      config     = Map("host" -> "files.example.com", "user" -> "uploader"),
      env        = "staging",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val art    = BuildResult("/tmp/deploy.tar.gz", ArtifactKind.Tarball)
    val result = target.push(ctx, art)
    assert(result.ref.contains("files.example.com"))

  // ── TargetFactory: traditional transport dispatch ─────────────────────────

  test("TargetFactory: traditional + transport=ssh+systemd → SshSystemdTarget"):
    val t = TargetFactory.make("traditional", Map("transport" -> "ssh+systemd", "host" -> "h"))
    assert(t.kind == "traditional")
    assert(t.isInstanceOf[SshSystemdTarget])

  test("TargetFactory: rsync kind → RsyncTarget"):
    val t = TargetFactory.make("rsync", Map.empty)
    assert(t.kind == "rsync")
    assert(t.isInstanceOf[RsyncTarget])

  test("TargetFactory: sftp kind → SftpTarget"):
    val t = TargetFactory.make("sftp", Map.empty)
    assert(t.kind == "sftp")
    assert(t.isInstanceOf[SftpTarget])

  // ── StaticTarget ──────────────────────────────────────────────────────────

  test("StaticTarget.kind and artifactKind"):
    val t = new StaticTarget()
    assert(t.kind == "static")
    assert(t.artifactKind == ArtifactKind.SpaBundle)

  test("StaticTarget.push: Vercel dry-run returns vercel.app URL"):
    val target = new StaticTarget()
    val ctx = DeployContext(
      targetName = "mysite",
      config     = Map("provider" -> "vercel", "project" -> "mysite", "token" -> "tok_xxx"),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val art    = BuildResult("/tmp/dist", ArtifactKind.SpaBundle)
    val result = target.push(ctx, art)
    assert(result.ref.contains("vercel.app"))
    assert(result.metadata.get("url").exists(_.contains("vercel.app")))

  test("StaticTarget.push: Netlify dry-run returns netlify.app URL"):
    val target = new StaticTarget()
    val ctx = DeployContext(
      targetName = "mysite",
      config     = Map("provider" -> "netlify", "project" -> "mysite", "token" -> "tok_xxx"),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val art    = BuildResult("/tmp/dist", ArtifactKind.SpaBundle)
    val result = target.push(ctx, art)
    assert(result.ref.contains("netlify.app"))

  test("StaticTarget.push: Cloudflare Pages dry-run returns pages.dev URL"):
    val target = new StaticTarget()
    val ctx = DeployContext(
      targetName = "mysite",
      config     = Map("provider" -> "cloudflare-pages", "project" -> "mysite",
        "token" -> "tok_xxx", "team" -> "acct123"),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val art    = BuildResult("/tmp/dist", ArtifactKind.SpaBundle)
    val result = target.push(ctx, art)
    assert(result.ref.contains("pages.dev"))

  test("StaticTarget.push: GitHub Pages dry-run returns github.io URL"):
    val target = new StaticTarget()
    val ctx = DeployContext(
      targetName = "myorg",
      config     = Map("provider" -> "github-pages", "project" -> "myorg", "token" -> "ghp_xxx"),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val art    = BuildResult("/tmp/dist", ArtifactKind.SpaBundle)
    val result = target.push(ctx, art)
    assert(result.ref.contains("github.io"))
    assert(result.metadata.get("branch").exists(_ == "gh-pages"))

  test("StaticTarget.push: unknown provider throws"):
    val target = new StaticTarget()
    val ctx = DeployContext(
      targetName = "mysite",
      config     = Map("provider" -> "aws-amplify", "token" -> "tok"),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    intercept[DeployError] {
      target.push(ctx, BuildResult("/tmp/dist", ArtifactKind.SpaBundle))
    }

  test("StaticTarget.deploy: wraps push result URL into DeployResult"):
    val target = new StaticTarget()
    val ctx = DeployContext(
      targetName = "mysite",
      config     = Map("provider" -> "vercel", "token" -> "tok"),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val ref    = PushResult("https://mysite.vercel.app", metadata = Map("url" -> "https://mysite.vercel.app"))
    val result = target.deploy(ctx, ref)
    assert(result.url.exists(_.contains("vercel.app")))

  test("StaticTarget.outputs: returns url from outputsOf"):
    val target = new StaticTarget()
    val ctx = DeployContext(
      targetName = "mysite",
      config     = Map("provider" -> "netlify", "project" -> "mysite", "token" -> "tok"),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = name => if name == "mysite" then Map("url" -> "https://mysite.netlify.app") else Map.empty,
      workDir    = os.temp.dir(),
    )
    val outs = target.outputs(ctx)
    assert(outs("url") == "https://mysite.netlify.app")

  test("TargetFactory: static kind → StaticTarget"):
    val t = TargetFactory.make("static", Map.empty)
    assert(t.kind == "static")
    assert(t.isInstanceOf[StaticTarget])

  // ── FaasTarget ─────────────────────────────────────────────────────────────

  test("FaasTarget: kind and artifactKind"):
    val t = new FaasTarget()
    assert(t.kind == "faas")
    assert(t.artifactKind == ArtifactKind.LambdaZip)

  test("FaasTarget: Lambda dry-run build returns zip path"):
    val target = new FaasTarget()
    val ctx = DeployContext(
      targetName = "my-fn",
      config     = Map("provider" -> "lambda"),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val result = target.build(ctx)
    assert(result.artifactPath.endsWith("lambda.zip"))
    assert(result.artifactKind == ArtifactKind.LambdaZip)

  test("FaasTarget: Lambda dry-run push returns function name"):
    val target = new FaasTarget()
    val ctx = DeployContext(
      targetName = "my-fn",
      config     = Map("provider" -> "lambda"),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val art    = BuildResult("/tmp/lambda.zip", ArtifactKind.LambdaZip)
    val result = target.push(ctx, art)
    assert(result.ref == "my-fn")
    assert(result.metadata.contains("functionArn"))

  test("FaasTarget: Lambda dry-run deploy returns alias version"):
    val target = new FaasTarget()
    val ctx = DeployContext(
      targetName = "my-fn",
      config     = Map("provider" -> "lambda"),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val ref    = PushResult("my-fn", metadata = Map("functionArn" -> "my-fn"))
    val result = target.deploy(ctx, ref)
    assert(result.revision == "my-fn")
    assert(result.metadata.get("aliasVersion") == Some("dry-run"))

  test("FaasTarget: Lambda dry-run rollback returns revision id"):
    val target = new FaasTarget()
    val ctx = DeployContext(
      targetName = "my-fn",
      config     = Map("provider" -> "lambda"),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val result = target.rollback(ctx, RevisionRef("42"))
    assert(result.revision == "42")

  test("FaasTarget: Lambda outputs includes functionArn and invokeUrl"):
    val target = new FaasTarget()
    val ctx = DeployContext(
      targetName = "my-fn",
      config     = Map("provider" -> "lambda"),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val outs = target.outputs(ctx)
    assert(outs.contains("functionArn"))
    assert(outs.contains("invokeUrl"))
    assert(outs("aliasVersion") == "live")

  test("FaasTarget: Cloudflare Workers dry-run push"):
    val target = new FaasTarget()
    val ctx = DeployContext(
      targetName = "my-worker",
      config     = Map("provider" -> "cloudflare-workers", "team" -> "myaccount", "token" -> "tok"),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val art    = BuildResult("/tmp/worker.js", ArtifactKind.NodeBundle)
    val result = target.push(ctx, art)
    assert(result.ref == "my-worker")
    assert(result.metadata.get("url").exists(_.contains("workers.dev")))

  test("FaasTarget: Cloud Run dry-run deploy returns url"):
    val target = new FaasTarget()
    val ctx = DeployContext(
      targetName = "my-service",
      config     = Map("provider" -> "cloud-run", "region" -> "us-central1"),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    val ref    = PushResult("gcr.io/proj/my-service:latest")
    val result = target.deploy(ctx, ref)
    assert(result.url.exists(u => u.contains("run.app") || u.contains("my-service")))

  test("FaasTarget: unknown provider throws DeployError"):
    val target = new FaasTarget()
    val ctx = DeployContext(
      targetName = "fn",
      config     = Map("provider" -> "unknown-cloud"),
      env        = "production",
      slot       = None,
      dryRun     = true,
      verbose    = false,
      outputsOf  = _ => Map.empty,
      workDir    = os.temp.dir(),
    )
    assertThrows[DeployError](target.build(ctx))

  test("TargetFactory: faas kind → FaasTarget"):
    val t = TargetFactory.make("faas", Map.empty)
    assert(t.kind == "faas")
    assert(t.isInstanceOf[FaasTarget])

  test("TargetFactory: lambda kind → FaasTarget"):
    val t = TargetFactory.make("lambda", Map.empty)
    assert(t.isInstanceOf[FaasTarget])

  // ── JsonState ──────────────────────────────────────────────────────────────

  test("JsonState: round-trip with slot"):
    val rec = StateRecord("production", "backend", Some("green"), "v2", "sha256:abc", "2026-05-27T00:00:00Z", "ci", Map("url" -> "https://example.com"))
    val json   = JsonState.serialize(rec)
    val parsed = JsonState.parse(json)
    assert(parsed.env          == "production")
    assert(parsed.target       == "backend")
    assert(parsed.slot         == Some("green"))
    assert(parsed.revision     == "v2")
    assert(parsed.artifactHash == "sha256:abc")
    assert(parsed.deployedBy   == "ci")
    assert(parsed.outputs      == Map("url" -> "https://example.com"))

  test("JsonState: round-trip without slot"):
    val rec    = StateRecord("staging", "api", None, "v1", "sha256:def", "2026-05-27T00:00:00Z", "dev", Map.empty)
    val parsed = JsonState.parse(JsonState.serialize(rec))
    assert(parsed.slot   == None)
    assert(parsed.target == "api")

  // ── LocalFileStateBackend ──────────────────────────────────────────────────

  test("LocalFileStateBackend: write and read round-trip"):
    val dir     = os.temp.dir()
    val backend = new LocalFileStateBackend(Map("path" -> dir.toString, "app" -> "testapp"))
    val key     = StateKey("staging", "api")
    val rec     = StateRecord("staging", "api", None, "v3", "sha256:xyz", "2026-05-27T00:00:00Z", "ci", Map("url" -> "https://api.example.com"))
    backend.write(key, rec)
    val read = backend.read(key)
    assert(read.isDefined)
    assert(read.get.revision     == "v3")
    assert(read.get.artifactHash == "sha256:xyz")
    assert(read.get.outputs      == Map("url" -> "https://api.example.com"))

  test("LocalFileStateBackend: read returns None for missing key"):
    val dir     = os.temp.dir()
    val backend = new LocalFileStateBackend(Map("path" -> dir.toString, "app" -> "testapp"))
    assert(backend.read(StateKey("prod", "missing")) == None)

  test("LocalFileStateBackend: write with slot creates slot-qualified file"):
    val dir     = os.temp.dir()
    val backend = new LocalFileStateBackend(Map("path" -> dir.toString, "app" -> "testapp"))
    val key     = StateKey("production", "backend", Some("blue"))
    val rec     = StateRecord("production", "backend", Some("blue"), "v1", "sha256:aaa", "2026-05-27T00:00:00Z", "ci", Map.empty)
    backend.write(key, rec)
    val read = backend.read(key)
    assert(read.isDefined)
    assert(read.get.slot == Some("blue"))

  test("LocalFileStateBackend: lock and unlock"):
    val dir     = os.temp.dir()
    val backend = new LocalFileStateBackend(Map("path" -> dir.toString, "app" -> "testapp"))
    val key     = StateKey("prod", "svc")
    val handle  = backend.lock(key, 60)
    assert(handle.key == key)
    assert(handle.token.nonEmpty)
    backend.unlock(handle)
    // after unlock, can lock again
    val handle2 = backend.lock(key, 60)
    backend.unlock(handle2)
    assert(handle2.token != handle.token)

  test("LocalFileStateBackend: lock contention throws DeployError when fresh"):
    val dir     = os.temp.dir()
    val backend = new LocalFileStateBackend(Map("path" -> dir.toString, "app" -> "testapp"))
    val key     = StateKey("prod", "svc2")
    val handle  = backend.lock(key, 3600)
    assertThrows[DeployError](backend.lock(key, 3600))
    backend.unlock(handle)

  // ── StateBackendFactory ────────────────────────────────────────────────────

  test("StateBackendFactory: local backend (default)"):
    val dir = os.temp.dir()
    val b   = StateBackendFactory.make(Map("backend" -> "local", "path" -> dir.toString))
    assert(b.isInstanceOf[LocalFileStateBackend])

  test("StateBackendFactory: unknown backend throws"):
    assertThrows[DeployError](StateBackendFactory.make(Map("backend" -> "oracle-nosql")))

  test("StateBackendFactory: production env with local backend throws"):
    val dir = os.temp.dir()
    val env = DeployEnvironment.parse("production", Map("purpose" -> "production"))
    val b   = StateBackendFactory.make(Map("backend" -> "local", "path" -> dir.toString))
    assertThrows[DeployError](StateBackendFactory.requireRemoteForProduction(env, b))

  test("StateBackendFactory: production env with noop backend does NOT throw"):
    val env = DeployEnvironment.parse("production", Map("purpose" -> "production"))
    StateBackendFactory.requireRemoteForProduction(env, NoopStateBackend)

  // ── StateMigrator ──────────────────────────────────────────────────────────

  test("StateMigrator: dry-run reports copies without writing"):
    val dir  = os.temp.dir()
    val src  = new LocalFileStateBackend(Map("path" -> dir.toString, "app" -> "srcapp"))
    val dst  = new LocalFileStateBackend(Map("path" -> dir.toString, "app" -> "dstapp"))
    val key  = StateKey("prod", "svc")
    val rec  = StateRecord("prod", "svc", None, "v1", "sha256:aaa", "2026-05-27T00:00:00Z", "ci", Map.empty)
    src.write(key, rec)
    val result = StateMigrator.migrate(src, dst, List(key), dryRun = true)
    assert(result.copied  == 1)
    assert(result.skipped == 0)
    assert(result.failed.isEmpty)
    assert(dst.read(key) == None)

  test("StateMigrator: migrates records from src to dst"):
    val dir  = os.temp.dir()
    val src  = new LocalFileStateBackend(Map("path" -> dir.toString, "app" -> "srcapp2"))
    val dst  = new LocalFileStateBackend(Map("path" -> dir.toString, "app" -> "dstapp2"))
    val key  = StateKey("staging", "api")
    val rec  = StateRecord("staging", "api", None, "v2", "sha256:bbb", "2026-05-27T00:00:00Z", "dev", Map("url" -> "https://api.staging.example.com"))
    src.write(key, rec)
    val result = StateMigrator.migrate(src, dst, List(key))
    assert(result.copied  == 1)
    assert(result.failed.isEmpty)
    val dstRec = dst.read(key)
    assert(dstRec.isDefined)
    assert(dstRec.get.revision == "v2")
    assert(dstRec.get.outputs  == Map("url" -> "https://api.staging.example.com"))

  test("StateMigrator: missing keys counted as skipped"):
    val dir  = os.temp.dir()
    val src  = new LocalFileStateBackend(Map("path" -> dir.toString, "app" -> "srcapp3"))
    val dst  = new LocalFileStateBackend(Map("path" -> dir.toString, "app" -> "dstapp3"))
    val key  = StateKey("prod", "ghost")
    val result = StateMigrator.migrate(src, dst, List(key))
    assert(result.skipped == 1)
    assert(result.copied  == 0)

  // ── v1.63.7: K8sManifestGenerator (cluster-aware) ─────────────────────────

  test("K8sManifestGenerator: StatefulSet emits serviceName and replicas"):
    val cfg = K8sManifestGenerator.K8sConfig(namespace = "prod", replicas = 3, clusterMode = true, workloadMode = WorkloadMode.StatefulSet)
    val yaml = K8sManifestGenerator.statefulSet("myapp", cfg)
    assert(yaml.contains("kind: StatefulSet"))
    assert(yaml.contains("serviceName: myapp-headless"))
    assert(yaml.contains("replicas: 3"))

  test("K8sManifestGenerator: headlessService emits clusterIP None"):
    val cfg  = K8sManifestGenerator.K8sConfig()
    val yaml = K8sManifestGenerator.headlessService("myapp", cfg)
    assert(yaml.contains("clusterIP: None"))
    assert(yaml.contains("name: myapp-headless"))

  test("K8sManifestGenerator: tokenSecret base64-encodes token"):
    val yaml = K8sManifestGenerator.tokenSecret("myapp", "default", "secret123")
    assert(yaml.contains("kind: Secret"))
    val b64 = java.util.Base64.getEncoder.encodeToString("secret123".getBytes("UTF-8"))
    assert(yaml.contains(b64))

  test("K8sManifestGenerator: hpa emits cpu target"):
    val cfg = K8sManifestGenerator.K8sConfig()
    val hpaConfig = HpaConfig(min = 2, max = 10, targets = List(AutoscaleTarget.Cpu(CpuTarget(70))))
    val yaml = K8sManifestGenerator.hpa("myapp", cfg, hpaConfig)
    assert(yaml.contains("kind: HorizontalPodAutoscaler"))
    assert(yaml.contains("minReplicas: 2"))
    assert(yaml.contains("maxReplicas: 10"))
    assert(yaml.contains("averageUtilization: 70"))

  test("K8sManifestGenerator: hpa targets StatefulSet when workloadMode is StatefulSet"):
    val cfg = K8sManifestGenerator.K8sConfig(workloadMode = WorkloadMode.StatefulSet)
    val hpaConfig = HpaConfig(min = 1, max = 5, targets = List(AutoscaleTarget.Cpu(CpuTarget(80))))
    val yaml = K8sManifestGenerator.hpa("myapp", cfg, hpaConfig)
    assert(yaml.contains("kind: StatefulSet"))

  test("K8sManifestGenerator: clusterBundle includes StatefulSet + headlessService"):
    val cfg = K8sManifestGenerator.K8sConfig(clusterMode = true, workloadMode = WorkloadMode.StatefulSet, authTokenSecret = Some("ssc-token"))
    val bundle = K8sManifestGenerator.clusterBundle("myapp", cfg)
    assert(bundle.contains("kind: StatefulSet"))
    assert(bundle.contains("clusterIP: None"))
    assert(bundle.contains("myapp-cluster-token"))

  // ── v1.63.7: HpaConfig parser ──────────────────────────────────────────────

  test("HpaConfig.parse: defaults"):
    val hpa = HpaConfig.parse(Map("min" -> Integer.valueOf(2), "max" -> Integer.valueOf(8)))
    assert(hpa.min == 2)
    assert(hpa.max == 8)
    assert(hpa.targets.isEmpty)

  test("HpaConfig.parse: cpu target from target list"):
    val target = new java.util.LinkedHashMap[String, Any]()
    target.put("kind", "cpu")
    target.put("percent", Integer.valueOf(75))
    val targets = new java.util.ArrayList[Any]()
    targets.add(target)
    val hpa = HpaConfig.parse(Map("min" -> Integer.valueOf(1), "max" -> Integer.valueOf(5), "target" -> targets))
    assert(hpa.targets.size == 1)
    assert(hpa.targets.head.isInstanceOf[AutoscaleTarget.Cpu])
    assert(hpa.targets.head.asInstanceOf[AutoscaleTarget.Cpu].t.percent == 75)

  // ── v1.63.7: Deploy.rollingCluster ────────────────────────────────────────

  test("Deploy.rollingCluster: Rolling — all succeed"):
    val result = Deploy.rollingCluster(
      nodeUrls    = List("http://a", "http://b", "http://c"),
      runNode     = _ => Right(()),
      strategy    = RollingStrategy.Rolling(maxUnavailable = 1, waitDrainMs = 0),
      healthCheck = _ => true,
      dryRun      = true,
    )
    assert(result.upgraded == List("http://a", "http://b", "http://c"))
    assert(result.failed.isEmpty)
    assert(!result.aborted)

  test("Deploy.rollingCluster: BlueGreen — dry-run"):
    val result = Deploy.rollingCluster(
      nodeUrls    = List("http://a", "http://b"),
      runNode     = _ => Right(()),
      strategy    = RollingStrategy.BlueGreen(observeMs = 0),
      dryRun      = true,
    )
    assert(result.upgraded == List("http://a", "http://b"))
    assert(!result.aborted)

  test("Deploy.rollingCluster: Canary — dry-run deploys canary then rest"):
    val deployed = scala.collection.mutable.ListBuffer.empty[String]
    val result = Deploy.rollingCluster(
      nodeUrls    = List("http://a", "http://b", "http://c", "http://d"),
      runNode     = url => { deployed += url; Right(()) },
      strategy    = RollingStrategy.Canary(percent = 25, observeMs = 0),
      dryRun      = true,
    )
    assert(result.upgraded.size == 4)
    assert(!result.aborted)

  test("Deploy.rollingCluster: Rolling — aborts on second failure exceeding maxUnavailable"):
    val result = Deploy.rollingCluster(
      nodeUrls    = List("http://a", "http://b", "http://c"),
      runNode     = url => if url == "http://b" then Left("deploy failed") else Right(()),
      strategy    = RollingStrategy.Rolling(maxUnavailable = 1, waitDrainMs = 0),
      healthCheck = _ => true,
      dryRun      = true,
    )
    assert(result.failed.contains("http://b"))
    assert(result.aborted)

  // ── v1.63.7: Deploy.multiRegion ───────────────────────────────────────────

  test("Deploy.multiRegion: dry-run returns all as succeeded"):
    val result = Deploy.multiRegion(
      regions   = List("us-east-1", "eu-west-1"),
      runRegion = _ => Right(()),
      quorum    = 1,
      dryRun    = true,
    )
    assert(result.succeeded == List("us-east-1", "eu-west-1"))
    assert(result.failed.isEmpty)

  test("Deploy.multiRegion: one failure partial success"):
    val result = Deploy.multiRegion(
      regions   = List("us-east-1", "eu-west-1", "ap-southeast-1"),
      runRegion = r => if r == "eu-west-1" then Left("net timeout") else Right(()),
      quorum    = 2,
    )
    assert(result.succeeded.toSet == Set("us-east-1", "ap-southeast-1"))
    assert(result.failed == List("eu-west-1"))

  // ── v1.63.7: ComposeTarget ─────────────────────────────────────────────────

  test("ComposeTarget: generateCompose includes port and image"):
    val target = new ComposeTarget()
    val ctx    = DeployContext(
      targetName = "myapp",
      config     = Map("image" -> "myapp:1.0", "app_port" -> Integer.valueOf(9090)),
      workDir    = os.temp.dir(),
      verbose    = false,
      dryRun     = true,
      env        = "local",
      slot       = None,
      outputsOf  = _ => Map.empty,
    )
    val compose = target.generateCompose("myapp", ctx, "myapp:1.0")
    assert(compose.contains("image: myapp:1.0"))
    assert(compose.contains("9090:9090"))

  test("ComposeTarget: cluster_mode injects SSC_CLUSTER_TOKEN env"):
    val target = new ComposeTarget()
    val ctx    = DeployContext(
      targetName = "myapp",
      config     = Map("image" -> "myapp:1.0", "cluster_mode" -> "true"),
      workDir    = os.temp.dir(),
      verbose    = false,
      dryRun     = true,
      env        = "local",
      slot       = None,
      outputsOf  = _ => Map.empty,
    )
    val compose = target.generateCompose("myapp", ctx, "myapp:1.0")
    assert(compose.contains("SSC_CLUSTER_TOKEN"))

  // ── v1.63.7: TargetFactory compose ────────────────────────────────────────

  test("TargetFactory: compose kind → ComposeTarget"):
    val t = TargetFactory.make("compose", Map.empty)
    assert(t.isInstanceOf[ComposeTarget])

  test("TargetFactory: docker-compose kind → ComposeTarget"):
    val t = TargetFactory.make("docker-compose", Map.empty)
    assert(t.isInstanceOf[ComposeTarget])

  // ── v1.63.7: DeployEnvironment autoscale ──────────────────────────────────

  test("DeployEnvironment: parse autoscale block"):
    val raw      = new java.util.LinkedHashMap[String, Any]()
    val autoscale = new java.util.LinkedHashMap[String, Any]()
    autoscale.put("min_replicas", Integer.valueOf(2))
    autoscale.put("max_replicas", Integer.valueOf(10))
    autoscale.put("cpu_percent", Integer.valueOf(80))
    raw.put("purpose", "production")
    raw.put("autoscale", autoscale)
    import scala.jdk.CollectionConverters.*
    val env = DeployEnvironment.parse("prod", raw.asScala.toMap.view.mapValues(v => v: Any).toMap)
    assert(env.autoscale.isDefined)
    val pol = env.autoscale.get
    assert(pol.minReplicas == 2)
    assert(pol.maxReplicas == 10)
    assert(pol.targets.size == 1)

  // ── v1.63.8: WorkerBundle ─────────────────────────────────────────────────

  private def makeBundle(sourceContent: String = "hello"): (Array[Byte], String) =
    val sourceBytes = sourceContent.getBytes("UTF-8")
    val hash        = WorkerBundle.sha256Hex(sourceBytes)
    val manifest    = s"""{"bundleVersion":"1","target":"jvm","sourceFile":"main.ssc","hash":"$hash","runtimeVersion":"1.0","deps":[]}"""
    val bos  = new java.io.ByteArrayOutputStream()
    val zos  = new java.util.zip.ZipOutputStream(bos)
    zos.putNextEntry(new java.util.zip.ZipEntry("manifest.json"))
    zos.write(manifest.getBytes("UTF-8"))
    zos.closeEntry()
    zos.putNextEntry(new java.util.zip.ZipEntry("main.ssc"))
    zos.write(sourceBytes)
    zos.closeEntry()
    zos.close()
    (bos.toByteArray, hash)

  test("WorkerBundle.verify: valid unsigned bundle succeeds"):
    val (zip, hash) = makeBundle()
    WorkerBundle.verify(zip) match
      case Right(b) =>
        assert(b.manifest.codeIdentityHash == hash)
        assert(b.sourceFile == "main.ssc")
      case Left(e) => fail(s"Expected success, got $e")

  test("WorkerBundle.verify: hash mismatch returns HashMismatch"):
    // Build a valid zip with a deliberately wrong hash in the manifest
    val sourceBytes = "hello".getBytes("UTF-8")
    val wrongHash   = "0" * 64  // all-zero hash, doesn't match actual sha256
    val manifest    = s"""{"bundleVersion":"1","target":"jvm","sourceFile":"main.ssc","hash":"$wrongHash","runtimeVersion":"1.0","deps":[]}"""
    val bos  = new java.io.ByteArrayOutputStream()
    val zos  = new java.util.zip.ZipOutputStream(bos)
    zos.putNextEntry(new java.util.zip.ZipEntry("manifest.json"))
    zos.write(manifest.getBytes("UTF-8"))
    zos.closeEntry()
    zos.putNextEntry(new java.util.zip.ZipEntry("main.ssc"))
    zos.write(sourceBytes)
    zos.closeEntry()
    zos.close()
    WorkerBundle.verify(bos.toByteArray) match
      case Left(_: BundleVerificationError.HashMismatch) => // expected
      case other => fail(s"Expected HashMismatch, got $other")

  test("WorkerBundle.verify: missing manifest returns ManifestMissing"):
    val bos = new java.io.ByteArrayOutputStream()
    val zos = new java.util.zip.ZipOutputStream(bos)
    zos.putNextEntry(new java.util.zip.ZipEntry("something.txt"))
    zos.write("hello".getBytes())
    zos.closeEntry()
    zos.close()
    assert(WorkerBundle.verify(bos.toByteArray) == Left(BundleVerificationError.ManifestMissing))

  test("WorkerBundle.verify: HMAC signature missing when secret provided"):
    val (zip, _) = makeBundle()
    WorkerBundle.verify(zip, hmacSecret = Some("key".getBytes())) match
      case Left(BundleVerificationError.SignatureMissing(_)) => // expected
      case other => fail(s"Expected SignatureMissing, got $other")

  test("WorkerBundle.sign and verify round-trip"):
    val (zip, _) = makeBundle()
    val secret   = "mysecret".getBytes("UTF-8")
    val signed   = WorkerBundle.sign(zip, secret, keyId = "key-1")
    WorkerBundle.verify(signed, hmacSecret = Some(secret)) match
      case Right(b) => assert(b.manifest.signedBy.contains("key-1"))
      case Left(e)  => fail(s"Expected success after sign, got $e")

  test("WorkerBundle.verify: tampered signature returns SignatureMismatch"):
    val (zip, _) = makeBundle()
    val secret   = "mysecret".getBytes("UTF-8")
    val signed   = WorkerBundle.sign(zip, secret)
    WorkerBundle.verify(signed, hmacSecret = Some("wrongkey".getBytes())) match
      case Left(BundleVerificationError.SignatureMismatch(_, _)) => // expected
      case other => fail(s"Expected SignatureMismatch, got $other")

  test("WorkerBundle.verify: missing dep returns MissingDep"):
    val (zip, _) = makeBundle()
    WorkerBundle.verify(zip, knownDeps = Set("foo")) match
      case Left(_) => // no missing dep, succeeds
      case Right(_) => // ok, deps is empty in test bundle so no MissingDep
    // Now build bundle with a dep
    val sourceBytes = "hello".getBytes("UTF-8")
    val hash        = WorkerBundle.sha256Hex(sourceBytes)
    val manifest    = s"""{"bundleVersion":"1","target":"jvm","sourceFile":"main.ssc","hash":"$hash","runtimeVersion":"1.0","deps":["some-lib-1.0"]}"""
    val bos  = new java.io.ByteArrayOutputStream()
    val zos  = new java.util.zip.ZipOutputStream(bos)
    zos.putNextEntry(new java.util.zip.ZipEntry("manifest.json"))
    zos.write(manifest.getBytes("UTF-8"))
    zos.closeEntry()
    zos.putNextEntry(new java.util.zip.ZipEntry("main.ssc"))
    zos.write(sourceBytes)
    zos.closeEntry()
    zos.close()
    WorkerBundle.verify(bos.toByteArray, knownDeps = Set.empty) match
      case Left(BundleVerificationError.MissingDep("some-lib-1.0")) => // expected
      case other => fail(s"Expected MissingDep, got $other")

  // ── v1.63.8: ArtifactCache ────────────────────────────────────────────────

  test("ArtifactCache: put and get by hash"):
    val cache = new ArtifactCache(maxEntries = 4)
    cache.put("sha256:abc", Array(1, 2, 3))
    assert(cache.get("sha256:abc").map(_.toSeq) == Some(Seq(1, 2, 3)))

  test("ArtifactCache: missing key returns None"):
    val cache = new ArtifactCache()
    assert(cache.get("sha256:nope") == None)

  test("ArtifactCache: evicts LRU when maxEntries exceeded"):
    val cache = new ArtifactCache(maxEntries = 3)
    cache.put("a", Array(1)); cache.put("b", Array(2)); cache.put("c", Array(3))
    // access 'a' to make it recently used
    cache.get("a")
    // add 'd' — should evict 'b' (LRU)
    cache.put("d", Array(4))
    assert(cache.size <= 3)

  test("ArtifactCache: remove deletes entry"):
    val cache = new ArtifactCache()
    cache.put("sha256:x", Array(9))
    cache.remove("sha256:x")
    assert(!cache.contains("sha256:x"))

  // ── v1.63.8: AuditLog ────────────────────────────────────────────────────

  test("AuditLog: records entries and recent() returns latest first"):
    val log = new AuditLog(capacity = 5)
    log.record("bundle_loaded", "node1", "hash=abc", "admin")
    log.record("bundle_unloaded", "node1", "hash=abc", "admin")
    val entries = log.recent(10)
    assert(entries.size == 2)
    assert(entries.head.event == "bundle_unloaded")
    assert(entries(1).event == "bundle_loaded")

  test("AuditLog: capacity eviction drops oldest entries"):
    val log = new AuditLog(capacity = 3)
    (1 to 5).foreach(i => log.record(s"event$i", "n", "", ""))
    assert(log.recent(10).size == 3)
    assert(log.recent(10).exists(_.event == "event5"))
    assert(!log.recent(10).exists(_.event == "event1"))

  test("AuditLog: toJson produces valid JSON array"):
    val log = new AuditLog()
    log.record("test", "n1", "d", "a")
    val json = log.toJson(10)
    assert(json.startsWith("["))
    assert(json.contains("\"event\":\"test\""))

  // ── v1.63.8: CircuitBreaker ───────────────────────────────────────────────

  test("CircuitBreaker: stays closed below threshold"):
    val cb = new CircuitBreaker(threshold = 3, resetAfterMs = 60_000L)
    cb.recordFailure("w1")
    cb.recordFailure("w1")
    assert(!cb.isOpen("w1"))

  test("CircuitBreaker: opens at threshold"):
    val cb = new CircuitBreaker(threshold = 3, resetAfterMs = 60_000L)
    cb.recordFailure("w1"); cb.recordFailure("w1"); cb.recordFailure("w1")
    assert(cb.isOpen("w1"))

  test("CircuitBreaker: recordSuccess resets state"):
    val cb = new CircuitBreaker(threshold = 2, resetAfterMs = 60_000L)
    cb.recordFailure("w1"); cb.recordFailure("w1")
    assert(cb.isOpen("w1"))
    cb.recordSuccess("w1")
    assert(!cb.isOpen("w1"))
    assert(cb.failureCount("w1") == 0L)

  test("CircuitBreaker: resets after resetAfterMs"):
    val cb = new CircuitBreaker(threshold = 1, resetAfterMs = 1L) // 1 ms
    cb.recordFailure("w1")
    assert(cb.isOpen("w1"))
    Thread.sleep(10)
    assert(!cb.isOpen("w1"))

  test("CircuitBreaker: allOpen lists open workers"):
    val cb = new CircuitBreaker(threshold = 1, resetAfterMs = 60_000L)
    cb.recordFailure("w1"); cb.recordFailure("w2")
    val open = cb.allOpen
    assert(open.contains("w1"))
    assert(open.contains("w2"))

  // ── v1.63.8: ResourcePolicy / LoadTracker ─────────────────────────────────

  test("ResourcePolicy.parse: parses limits from map"):
    val raw = Map[String, Any]("max_cpu_ms" -> Integer.valueOf(500), "max_queue_depth" -> Integer.valueOf(10))
    val pol = ResourcePolicy.parse(raw)
    assert(pol.maxCpuMs == 500L)
    assert(pol.maxQueueDepth == 10)

  test("ResourcePolicy.unlimited: all zeroes"):
    assert(ResourcePolicy.unlimited == ResourcePolicy(0, 0, 0, 0))

  test("LoadTracker: acquire allows within limit"):
    val lt  = new LoadTracker
    val pol = ResourcePolicy(maxQueueDepth = 2)
    assert(lt.acquire("w1", pol))
    assert(lt.acquire("w1", pol))
    assert(!lt.acquire("w1", pol))

  test("LoadTracker: release decrements count"):
    val lt  = new LoadTracker
    val pol = ResourcePolicy(maxQueueDepth = 1)
    assert(lt.acquire("w1", pol))
    assert(!lt.acquire("w1", pol))
    lt.release("w1")
    assert(lt.acquire("w1", pol))

  test("LoadTracker: unlimited policy always allows"):
    val lt  = new LoadTracker
    val pol = ResourcePolicy.unlimited
    (1 to 100).foreach(_ => assert(lt.acquire("w1", pol)))
