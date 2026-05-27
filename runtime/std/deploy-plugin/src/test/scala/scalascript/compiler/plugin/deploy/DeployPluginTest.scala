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
