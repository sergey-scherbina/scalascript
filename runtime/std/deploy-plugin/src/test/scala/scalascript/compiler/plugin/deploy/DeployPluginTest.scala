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
      TargetFactory.make("kubernetes", Map.empty)
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
