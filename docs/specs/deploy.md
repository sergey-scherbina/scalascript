# v1.52 — Deploy to Hostings, Clouds & Kubernetes-like Environments

**Branch:** `feature/v152-deploy-spec`
**Status:** spec — go/no-go pending (§18)
**Deliverable:** this document + planning-file updates only; no code in v1.52

---

## 1. Motivation & relation to v1.48–v1.50 packaging

ScalaScript has shipped progressively richer artifact builders:

- **v1.48–v1.49** — `ssc package --target ios` (`.ipa`), `ssc publish --target ios` (TestFlight / App Store), `ssc package/publish --target macos` (notarize + DMG + Mac App Store) — all delegating to fastlane.
- **v1.50** — GraalVM native-image for the `ssc` binary itself (`build.sbt:912-924`); `ssc-plugin-host.jar` subprocess bridge for native + plugin combos (`tools/plugin-host/src/main/scala/scalascript/plugin/SubprocessHost.scala`).
- v1.45–v1.47 — JVM desktop and JavaFX frontends, packaged via `ssc build --target desktop|jvm`.
- `buildFatJar` (`tools/cli/src/main/scala/scalascript/cli/Main.scala:1370-1417`) produces a self-contained JAR today; JS and SPA bundles follow at `Main.scala:1481-1494`.

The **server runtime** is equally mature:

- `/_health` and `/_ready` are auto-registered by `runtime/http-server/jvm/src/main/scala/scalascript/server/jvm/RestRuntime.scala:640-648`.
- Prometheus metrics endpoint (`GET /_ssc-cluster/metrics-prom`) lives in `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/ClusterRoutesRuntime.scala:127-160`.
- Graceful draining + leader step-down are available via `runtime/std/actors.ssc:416-428` and `runtime/std/cluster/index.ssc:46,58`.
- TLS, typed routes, WS, SSE — all production-ready.

Yet two explicit future-work markers exist: `docs/specs/cluster-management.md:54-56` says *"what you'd build on top of a Kubernetes deployment of ScalaScript, not how Kubernetes itself deploys ScalaScript"*; `docs/specs/electron-jvm-rest-backend.md:699` calls deploy *"a deployment-time concern that warrants its own milestone."*

**Five concrete gaps today:**

1. **No `ssc deploy` subcommand.** The dispatch table `Main.scala:84-137` has `package`, `publish`, `serve`, `build`, `bundle` — but nothing that ships an artifact to a remote environment.
2. **No container / OCI generation.** No `Dockerfile` anywhere in the repo, no image-build path.
3. **No Kubernetes or cloud-platform manifests.** Zero Helm charts, K8s YAML, FaaS adapter code, static-host push logic.
4. **No traditional-hosting story.** SSH, systemd unit, rsync, WAR drop — all missing.
5. **No environment model.** No way to express *local vs test-PR vs staging vs multi-region production* in one manifest; no blue-green switching.

v1.52 fills that gap **at the design level**. It produces `docs/specs/deploy.md` (this file) and planning-file updates. No code lands in v1.52; implementation follows as v1.52.1–v1.52.7 after sign-off.

---

## 2. Conceptual model

Five roles. Two independent axes: *what to ship to* (targets + topology) and *which context to deploy in* (environment).

### 2.1 Roles

**Artifact** — the output of v1.48–v1.50 builders. Enumerated as `ArtifactKind`: `FatJar | ThinJar | NativeBinary | NodeBundle | SpaBundle | OciImage | War | LambdaZip | Tarball | RsyncTree`.

**Target** — a named deployment destination: a container registry + runtime, a Kubernetes cluster, a Lambda function, an SSH host, a static-site CDN. Each target has a `kind` (one of the five categories in §6).

**DeployTarget adapter** — the SPI bridge that knows how to take an `ArtifactKind` to a target. Six lifecycle methods + post-deploy `outputs()`. Adapter authors implement only the methods their target supports.

**Topology / DeployGroup** — composition over targets. Declares how a *set* of targets deploys together: in parallel, in sequence, or as a pipeline of stages. Carries inter-target dependency edges, cross-target output→input wiring, and partial-failure policy. This is what makes "backend on FaaS + frontend on static hosting" or "backend across three regions in parallel" a single `ssc deploy` invocation.

**Environment** — the orthogonal axis. Same app exists simultaneously in multiple named environments: `local` (developer machine, subprocess-mode), `test-*` (ephemeral per-PR or per-nightly), `staging` (pre-production), `production` (distributed, fault-tolerant, blue-green). Each environment selects a target subset, overrides per-target config, inherits from a base environment, and optionally enables fault-tolerance and blue-green slot switching.

### 2.2 The two-axis grid

```
              │ local      │ test-pr-42 │ staging    │ production
──────────────┼────────────┼────────────┼────────────┼─────────────────────────────
backend       │ subprocess │ k8s (1×)   │ k8s (2×)   │ k8s (3 regions, 3× each, blue-green)
frontend      │ http :5173 │ pages-prev │ pages-stg  │ cloudflare-pages (gradual switch)
db-migration  │ skip       │ run        │ run        │ run, then gated promotion
```

Mental model anchors: adapter shape mirrors `runtime/backend/spi/src/main/scala/scalascript/backend/spi/Backend.scala` (same ServiceLoader pattern); pluggable-target selection mirrors `docs/specs/http-server-backends.md` (pluggable jdk / jetty / netty); environment inheritance mirrors `docs/specs/config-system.md` layered overlay.

---

## 3. Type-level surface

### 3.1 Manifest AST extension

`lang/core/src/main/scala/scalascript/ast/AST.scala:19-70` defines `case class Manifest`. v1.52.1 adds three optional fields:

```scala
deploy:       Option[Map[String, TargetConfig]]      // named deploy targets
groups:       Option[Map[String, GroupConfig]]        // multi-target topology
environments: Option[Map[String, EnvironmentConfig]] // env × target overlay
state:        Option[StateConfig]                     // remote state backend
```

These are parsed from the YAML front-matter the same way existing fields (`routes:`, `databases:`, `graphs:`) are parsed. **Zero parser changes** — `lang/core/src/main/scala/scalascript/artifact/InterfaceScope.scala:107-281` (`parseNamedOrApp`) already handles the field shapes, and all new types are representable as `SType.Named(name, args)` per `lang/core/src/main/scala/scalascript/typer/Types.scala:19-45`.

### 3.2 `DeployTarget` SPI trait

```scala
trait DeployTarget:
  def kind: String                                    // "container" | "k8s" | "faas" | "static" | "traditional"
  def artifactKind: ArtifactKind                      // which builder feeds this adapter
  def build(ctx: DeployContext): BuildResult
  def push(ctx: DeployContext, art: BuildResult): PushResult
  def deploy(ctx: DeployContext, ref: PushResult): DeployResult
  def rollback(ctx: DeployContext, to: RevisionRef): RollbackResult
  def status(ctx: DeployContext): StatusReport
  def logs(ctx: DeployContext, opts: LogOpts): Stream[LogLine]  // ← v1.51 Stream[A]
  def outputs(ctx: DeployContext): Map[String, Value]           // post-deploy values for dependent targets
```

`DeployContext` carries: working dir, manifest, resolved config (reusing `backend/config/src/main/scala/scalascript/config/ConfigLoader.scala`), secrets handle (forwarding `${vault:…}` / `${sops:…}` resolution from `backend/config/src/main/scala/scalascript/config/SubstitutionEngine.scala`), target name, dry-run flag, verbosity, `outputsOf: String => Map[String, Value]` (lookup of already-deployed targets' outputs for cross-target wiring), and group + environment context.

### 3.3 `DeployGroup` SPI

```scala
case class DeployGroup(
  name:           String,
  members:        List[String],               // target names from deploy: block
  mode:           ExecMode,                   // Parallel | Sequence | Pipeline(stages)
  deps:           Map[String, List[String]],  // target → prerequisite target names (DAG)
  onFailure:      FailurePolicy,              // RollbackAll | ContinueRemaining | AbortRemaining
  maxParallelism: Option[Int]
)

enum ExecMode:
  case Parallel
  case Sequence
  case Pipeline(stages: List[List[String]])  // each stage is a parallel set; stages run in sequence

enum FailurePolicy:
  case RollbackAll         // undo all deployed members in reverse order; default
  case ContinueRemaining   // skip failed target's dependents; keep deploying independents
  case AbortRemaining      // stop; leave deployed members as-is; no rollback
```

### 3.4 `DeployEnvironment` SPI

```scala
case class DeployEnvironment(
  name:            String,
  purpose:         EnvironmentPurpose,                    // Local | Test | Staging | Production
  base:            Option[String],                        // name of env to inherit from
  targetOverrides: Map[String, ConfigOverlay],
  activeGroups:    List[String],
  faultTolerance:  Option[FaultToleranceConfig],
  blueGreen:       Option[BlueGreenConfig]
)

enum EnvironmentPurpose:
  case Local        // developer machine; subprocess targets; no health gates; hot-reload friendly
  case Test         // ephemeral; scaled-down; evidence-preserving on failure
  case Staging      // long-lived pre-production mirror; optional blue-green
  case Production   // distributed; fault-tolerant; blue-green required; remote state required

case class FaultToleranceConfig(
  multiRegion:      List[String],             // e.g. ["us-east", "eu-west", "ap-south"]
  quorum:           Int,                      // min healthy regions for env to be "up"
  failoverStrategy: FailoverStrategy          // ActiveActive | ActivePassive | ActiveStandby
)

case class BlueGreenConfig(
  enabled:        Boolean,
  activeSlot:     String,                     // "blue" or "green"
  switchStrategy: SwitchStrategy,             // Instant | Gradual(steps, interval)
  healthGate:     String,                     // URL path to poll after deploy (e.g. "/_health")
  smokeTests:     List[String],               // local executables to run before switch
  holdDuration:   Duration                    // keep old slot warm after switch (default 30min)
)
```

### 3.5 `StateBackend` SPI

```scala
trait StateBackend:
  def read(key: StateKey): Option[StateRecord]
  def write(key: StateKey, record: StateRecord): Unit
  def lock(key: StateKey, ttl: Duration): LockHandle
  def unlock(handle: LockHandle): Unit
```

State records are keyed by `StateKey(env: String, target: String, slot: Option[String])` so multiple environments and blue-green slots coexist in the same store.

### 3.6 `ArtifactKind` and `ArtifactRegistry`

```scala
enum ArtifactKind:
  case FatJar        // Main.scala:1370-1417
  case ThinJar       // Main.scala:1479
  case NativeBinary  // scala-cli --native passthrough, Main.scala:633-636
  case NodeBundle    // Main.scala:1481-1490
  case SpaBundle     // Main.scala:1492-1494
  case OciImage      // NEW — Dockerfile generation + local docker/podman build
  case War           // NEW — Maven WAR layout for Tomcat drop
  case LambdaZip     // NEW — zip + handler wrapper for AWS-style FaaS
  case Tarball       // NEW — tar.gz for scp / rsync
  case RsyncTree     // NEW — directory tree for rsync
```

`ArtifactRegistry` maps `ArtifactKind` → builder invocation. Existing builders are reused unchanged; new kinds are added per adapter phase (§16).

---

## 4. Deploy declarations & manifest syntax

### 4.1 Full annotated example

```yaml
---
name: my-app
version: 1.4.2

deploy:
  # ── JVM backend targets ──────────────────────────────────────────────
  backend-prod:
    kind: k8s
    cluster: prod-east-1
    namespace: app
    image: registry.example.com/app:${version}
    replicas: 3
    secrets: ${vault:secret/app/prod}
    artifact: fat-jar        # override default artifact kind

  backend-eu:
    kind: k8s
    cluster: prod-eu-west-1
    namespace: app
    image: registry.example.com/app:${version}

  backend-ap:
    kind: k8s
    cluster: prod-ap-south-1
    namespace: app
    image: registry.example.com/app:${version}

  # ── FaaS edge target ─────────────────────────────────────────────────
  backend-edge:
    kind: faas
    provider: cloudflare-workers
    script: ${env:WRANGLER_ACCOUNT_ID}
    route: /api/*

  # ── Static frontend ──────────────────────────────────────────────────
  frontend:
    kind: static
    provider: cloudflare-pages
    project: my-app
    env:
      VITE_API_URL: ${deploy.backend-prod.outputs.url}   # cross-target output wiring

  # ── Traditional VPS (old-school SSH + systemd) ───────────────────────
  legacy-vps:
    kind: traditional
    transport: ssh
    host: ${env:VPS_HOST}
    user: deploy
    path: /opt/app
    service: systemd
    unit: app.service

groups:
  # ── multi-region: 3 backends in parallel ─────────────────────────────
  multi-region:
    mode: parallel
    members: [backend-prod, backend-eu, backend-ap]
    max_parallelism: 3
    on_failure: continue_remaining      # partial deploy acceptable

  # ── full-stack: backend first, then frontend reading its URL ─────────
  full-stack:
    mode: pipeline
    stages:
      - [backend-prod]                  # stage 1: deploy backend
      - [frontend]                      # stage 2: frontend reads backend.outputs.url
    on_failure: rollback_all

  # ── hybrid: FaaS + K8s + static with explicit DAG ───────────────────
  hybrid:
    mode: parallel
    members: [backend-edge, backend-prod, frontend]
    depends_on:
      frontend: [backend-prod, backend-edge]   # both backends must be up first
    on_failure: rollback_all

environments:
  local:
    purpose: local
    targets:
      backend-local:
        kind: traditional
        transport: subprocess
        port: 8080
      frontend-local:
        kind: traditional
        transport: subprocess
        port: 5173
    active_groups: [local-stack]

  test-pr-42:
    purpose: test
    base: production                    # inherit production topology
    target_overrides:
      backend-prod:
        replicas: 1
        cluster: test-east-1
        image: registry.example.com/app:pr-42-${git.sha}

  staging:
    purpose: staging
    base: production
    target_overrides:
      backend-prod: { replicas: 2 }
      backend-eu: { replicas: 1 }
      backend-ap: { replicas: 1 }

  production:
    purpose: production
    active_groups: [multi-region, full-stack]
    fault_tolerance:
      multi_region: [us-east, eu-west, ap-south]
      quorum: 2
      failover_strategy: active-active
    blue_green:
      enabled: true
      active_slot: blue
      switch_strategy: gradual
      steps: [10, 50, 100]
      step_interval: 5m
      health_gate: /_health
      smoke_tests: [./scripts/smoke.sh]
      hold_duration: 30m

state:
  backend: s3
  bucket: ssc-deploy-state
  prefix: my-app/
---
```

### 4.2 Variable interpolation

Reuses the resolver chain from `docs/specs/config-system.md` and `backend/config/src/main/scala/scalascript/config/SubstitutionEngine.scala`. Available in all `deploy:`, `groups:`, and `environments:` values:

| Syntax | Source |
|--------|--------|
| `${env:NAME}` | `System.getenv` / `process.env` |
| `${vault:path}` | Vault KV read |
| `${sops:file.yaml/key}` | SOPS-decrypted file key |
| `${file:path}` | File contents |
| `${version}` | `manifest.version` |
| `${git.sha}` | Short commit hash |
| `${git.tag}` | Current git tag |
| `${git.branch}` | Current branch name |
| `${build.timestamp}` | ISO-8601 timestamp at deploy start |
| `${deploy.<target>.outputs.<key>}` | Post-deploy value from a named target |

`${deploy.<target>.outputs.<key>}` is resolved **after** the named target's `deploy()` returns, in topological order. At parse time, this reference introduces an implicit dependency edge from the referencing target to the producing target; a cycle produces a fatal error citing both manifest lines.

---

## 5. CLI surface

Every subcommand accepts `--env=<name>` to select the environment. When omitted, defaults to the env declared as `purpose: local` if one exists, otherwise the first-declared env.

### 5.1 Core subcommands

```
ssc deploy [--env=<env>]                       deploy all active groups in env
ssc deploy <target> [--env=<env>]              deploy one named target
ssc deploy <group> [--env=<env>]               deploy a named group (topology-aware)
ssc deploy --env=all [--env-parallelism N]     deploy across all environments
```

### 5.2 Verb subcommands

```
ssc deploy build <target|group> [--env=<env>]       build artifact(s) only; no push/deploy
ssc deploy push  <target|group> [--env=<env>]       build + push to registry; no activation
ssc deploy rollback <target|group> [--env=<env>] [--to <rev>]
ssc deploy status  [<target|group>] [--env=<env>]   shows table across all envs when env omitted
ssc deploy logs <target> [--env=<env>] [--tail] [--since 1h]
ssc deploy diff <target|group> [--env=<env>]
ssc deploy destroy <target|group> [--env=<env>]     requires --confirm
```

### 5.3 Topology & environment subcommands

```
ssc deploy plan  <group> [--env=<env>]    print resolved DAG + execution stages; no action
ssc deploy envs                           list all declared environments with purpose + slot
ssc deploy switch --env=<env> [--to <slot>]    flip blue-green active slot
ssc deploy promote --from=<env> --to=<env>     carry artifact hash across envs; respects blue-green
```

### 5.4 Flags

| Flag | Effect |
|------|--------|
| `--dry-run` | Parse manifest + validate SPI; print what would happen; no side effects |
| `--verbose` | Structured event stream to stdout; masks secret values |
| `--manifest <path>` | Override the `.ssc` file to read |
| `--state <backend>` | Override state backend from manifest (e.g. `--state s3://bucket/prefix`) |
| `--no-build` | Skip artifact rebuild; use last cached artifact |
| `--force` | Skip diff check; re-apply even if desired == current |
| `--parallelism N` | Override `max_parallelism` within a group |
| `--env-parallelism N` | Cap how many envs run concurrently for `--env=all` (default 1) |
| `--fail-fast` / `--no-fail-fast` | Override group's `on_failure` for this run |
| `--only <t1,t2>` | Deploy subset of a group's members |
| `--skip <t1,t2>` | Exclude members from this run |
| `--slot <blue\|green>` | Override active slot for this invocation only |

`rollback` within a blue-green env's `hold_duration` window is a slot re-switch (instant). Past the hold window, rollback re-deploys the previous artifact to the inactive slot, then switches.

---

## 6. Target categories & adapter taxonomy

Five categories, defined by the **SPI contract** each adapter must satisfy. Per-provider implementations are deferred to phase docs (§13 non-goals; §14 future work; §16 phasing).

### 6.1 Container (OCI)

**What it does:** Generates or accepts a `Dockerfile`, builds an OCI image, pushes to a registry, and activates on a container runtime.

**Required SPI methods:** `build` (Dockerfile gen + `docker build` / `buildctl`), `push` (registry push), `deploy` (runtime activation — restart service, update compose file, trigger rolling update). `rollback` via previous image digest. `status` via runtime healthcheck. `logs` via `docker logs -f`.

**Dockerfile generation** (per `ArtifactKind`):

| ArtifactKind | Base image | Entry |
|---|---|---|
| `FatJar` | `eclipse-temurin:21-jre-alpine` | `java -jar app.jar` |
| `NativeBinary` | `gcr.io/distroless/cc` | `./app` |
| `NodeBundle` | `node:22-alpine` | `node app.js` |
| `SpaBundle` | `nginx:alpine` + `COPY dist/ /usr/share/nginx/html` | static serve |

**`outputs()`:** `{ "digest": "sha256:…", "image": "registry/app:tag" }`.

**Config keys:** `registry`, `tag`, `platform` (e.g. `linux/amd64,linux/arm64`), `build_args`, `cache_from`.

### 6.2 Kubernetes-like

**What it does:** Generates K8s manifest (`Deployment` + `Service` + optional `Ingress` + `ConfigMap` + `Secret`) and applies it via `kubectl` subprocess or direct API, or hydrates a Helm chart values file.

**Required SPI methods:** All six + `outputs()`.

**Probe wiring:** Uses `/_health` (liveness) and `/_ready` (readiness) auto-registered by `RestRuntime.scala:640-648`. No adapter code needed for these paths — they're always present in a running ScalaScript HTTP server.

**PreStop hook:** Wired to cluster draining (`actors.ssc:416-428`, `cluster/index.ssc:46,58`) so in-flight requests complete before the pod terminates.

**`rollback`:** Via `kubectl rollout undo deployment/<name>` (uses K8s built-in revision history).

**`logs`:** Via `kubectl logs -f pod/<name>` piped into `Stream[LogLine]` (v1.51 streams).

**Blue-green slots:** Two `Deployment` objects (`<name>-blue`, `<name>-green`) + one `Service` whose `spec.selector` is flipped on `switch`. Preserves zero-downtime invariant.

**Multi-region orchestration:** Each region maps to a separate `DeployTarget` instance (or uses target-level `region:` override). The `multi-region` group deploys them in parallel; the orchestrator collects `outputs()` from each and polls each region's `/_health` to determine quorum.

**`outputs()`:** `{ "url": "https://…", "ingressHostname": "…", "serviceIP": "…" }`.

**Config keys:** `cluster`, `namespace`, `kubeconfig`, `context`, `replicas`, `resources`, `nodeSelector`, `annotations`, `ingress_class`.

### 6.3 Serverless / FaaS

**What it does:** Wraps the app entry point in a provider-specific handler shape, packages it as `LambdaZip` or `OciImage` or `NodeBundle`, and deploys to the FaaS platform.

**Required SPI methods:** `build` (wrap + package), `push` (upload zip / image), `deploy` (create/update function + alias). `rollback` via alias version pointer. `status` via function invoke test. `logs` via provider log stream.

**Provider shapes:**

| Provider | Artifact | Handler wrapper |
|---|---|---|
| AWS Lambda | `LambdaZip` (JVM or Node) | `fun.handler = "scalascript.cli.LambdaHandler"` |
| GCP Cloud Run | `OciImage` (reuse container adapter) | standard container |
| Cloudflare Workers | `NodeBundle` (sync) or `Wasm` | `export default { fetch: handler }` |
| Vercel / Netlify Functions | `NodeBundle` | adapter-specific export |

**`outputs()`:** `{ "functionArn": "…", "invokeUrl": "https://…", "aliasVersion": "3" }`.

**Config keys:** `provider`, `region`, `runtime`, `handler`, `memory_mb`, `timeout_s`, `env_vars`, `vpc_config`.

### 6.4 Static hosting

**What it does:** Builds an SPA bundle (`SpaBundle`) and pushes it to a static hosting provider. No server, no health probes.

**Required SPI methods:** `build` (reuses `Main.scala:1492-1494`), `push` (CDN upload), `deploy` (activate / purge cache). `rollback` via provider's deployment revision history. `status` via HTTP GET to published URL. `logs` — not applicable (redirects to CDN access logs if provider exposes them).

**Provider shapes:** Two patterns — API-based (Vercel, Netlify, Cloudflare Pages API) and git-based (GitHub Pages: push to `gh-pages` branch; Cloudflare Pages: push to main triggers Pages CI).

**`outputs()`:** `{ "url": "https://…", "deploymentId": "…" }`.

**Config keys:** `provider`, `project`, `team`, `branch` (for git-based), `headers` (custom HTTP headers), `redirects`.

### 6.5 Traditional hosting

**What it does:** Ships an artifact to a server that lacks a container runtime or managed orchestration — bare-metal VPS, shared hosting, legacy enterprise environments.

**Sub-kinds:**

| Sub-kind | Transport | Artifact | Activation |
|---|---|---|---|
| `ssh+systemd` | SSH / SCP | `FatJar` or `NativeBinary` | `systemctl restart <unit>` |
| `rsync` | SSH + rsync | `RsyncTree` or `SpaBundle` | optional post-rsync hook |
| `sftp` / `ftp` | SFTP or FTP | `Tarball` | server-side `unpack.sh` hook |
| `tomcat-war` | SSH + SCP | `War` | copy to `$CATALINA_HOME/webapps/` |
| `iis-publish` | MSDeploy / WinRM | `Tarball` | application pool restart |
| `shared-host` | SFTP / cPanel API | `Tarball` or `SpaBundle` | optional htaccess update |

**`outputs()`:** `{ "host": "vps.example.com", "port": "8080", "url": "http://…" }`.

**Config keys:** `transport` (`ssh` / `sftp` / `ftp` / `msdeploy`), `host`, `port`, `user`, `key_file`, `path`, `service`, `unit`, `pre_deploy`, `post_deploy` (shell commands run before/after copy).

---

## 7. Multi-target deployment & topology

The orchestration layer that makes "backend on FaaS + frontend on static hosting + DB migration on SSH" expressible as one `ssc deploy` invocation.

### 7.1 Execution modes

**`Parallel`** — all group members launch concurrently, bounded by `max_parallelism`. Best for: same artifact to N regions, independent slices. Example: `multi-region` group deploying us-east + eu-west + ap-south simultaneously.

**`Sequence`** — members execute one after another in declared list order. Best for: migrations first, then API server, then frontend. Dependencies are implicit from order.

**`Pipeline(stages)`** — list of stages; each stage is a parallel set; stages execute in sequence. Most flexible. Example: stage 1 = deploy backend in three regions concurrently; stage 2 = run smoke tests; stage 3 = deploy frontend reading any backend's URL.

### 7.2 Dependency resolution

`depends_on` declares an explicit DAG: `{ frontend: [backend-prod, backend-edge] }` means frontend waits for both backends. The orchestrator performs a topological sort and launches targets as soon as all their prerequisites finish. Cycle detection happens at parse time (fatal error showing the cycle path and both manifest line numbers).

Implicit dependencies are inferred from `${deploy.<target>.outputs.<key>}` references in config: if `frontend.env.VITE_API_URL` references `backend-prod.outputs.url`, the orchestrator adds `frontend → backend-prod` to the DAG automatically.

### 7.3 Cross-target output → input wiring

After a target's `deploy()` returns, the orchestrator calls `outputs()` and stores results in `DeployContext.outputsOf(targetName)`. Dependent targets receive this map through their `DeployContext` and all `${deploy.<target>.outputs.<key>}` references resolve against it. If a target's `outputs()` returns a URL that is not yet reachable (service still warming up), an optional `health_gate:` per-target polls `/_health` until 200 OK before marking that target's outputs available to dependents.

### 7.4 Failure semantics

**`RollbackAll`** (default) — on first failure, the orchestrator invokes `rollback()` on every already-deployed member in reverse deployment order. Group exits non-zero. Produces a per-member status table (§12). Safest for atomic "all or nothing" deploys.

**`ContinueRemaining`** — skips the failed target's transitive dependents but keeps deploying independent branches. Final summary reports per-target status with `✓ deployed`, `✗ failed: <reason>`, `— skipped (depends on failed)`. Exit code non-zero if any target failed. Best for best-effort multi-region rollouts where partial deployment is acceptable.

**`AbortRemaining`** — stops the orchestrator; leaves already-deployed targets as-is. No rollback. Preserves deployment evidence for investigation. Best for long deploys where rollback is expensive.

### 7.5 Concurrency primitive

The orchestrator uses v1.51 `Stream[DeployEvent]` + v1.9 virtual threads (`runtime/std/coroutine.ssc:27-36`) for parallel execution. Each target's deploy lifecycle runs in its own VT; the group orchestrator joins them via `Stream.merge`. Cancellation (Ctrl+C during deploy) propagates via `coroutineCancel` to terminate in-flight deployments; the orchestrator then applies the group's `on_failure` policy as if a failure had occurred.

### 7.6 Progress reporting

Structured event stream from the orchestrator:

```
Started(target, env, slot)
Building(target, progress)
Pushed(target, ref)
Deployed(target, outputs)
SwitchStarted(target, from, to, percentage)
SwitchStep(target, percentage)
SwitchComplete(target, slot)
Failed(target, error)
RolledBack(target)
SkippedDependency(target, blockedBy)
GroupComplete(group, status)
```

CLI renders this as a live multi-line table (one row per target + group summary row). CI mode (`--json`) emits NDJSON — one event per line — suitable for log aggregators.

---

## 8. Environments, fault tolerance & blue-green

### 8.1 Environment lifecycle

**`purpose: local`** — developer machine. Default behaviors: targets run as subprocesses (`transport: subprocess`), no health gates, no blue-green, no state backend required. Port conflicts fail fast with a clear error. Hot-reload integration with `ssc watch` is deferred (§13).

**`purpose: test`** — ephemeral. Default behaviors: `on_failure: abort_remaining` (preserve evidence on failure — don't clean up so the failing state is inspectable), `--env-parallelism ≥ 2` for concurrent PR env deploys, `hold_duration: 0` (no blue-green warmth — test envs are short-lived). Typically inherits from `production` via `base:` and overrides `replicas`, `image`, and `cluster`.

**`purpose: staging`** — long-lived pre-production mirror. Default behaviors: same fault-tolerance shape as production, `quorum: 1` (single region is fine), blue-green optional.

**`purpose: production`** — enforced constraints: remote `state:` backend required (error if omitted), `fault_tolerance:` required, `health_gate:` required for blue-green targets, smoke tests run before switch. Default behaviors: `on_failure: rollback_all`, blue-green enabled if declared.

### 8.2 Environment inheritance

`base: <env>` causes full inheritance of the base's target set, group selection, and config values. `target_overrides:` applies a shallow merge over the base per named target — only declared keys are overridden; others inherit. Inheritance chains are resolved depth-first; cycles are fatal.

Example: `test-pr-42` with `base: production` inherits all three K8s targets + the frontend, but overrides `replicas: 1` and a different `cluster:` for the backend, and a PR-tagged image for the frontend.

### 8.3 Fault tolerance

**`multi_region:`** — list of region names. Each region maps to one or more `DeployTarget` instances (declared in `deploy:` with a matching region tag, or derived from the `multi_region:` list by convention). The orchestrator deploys them via the `multi-region` group.

**`quorum: N`** — minimum number of healthy regions for the environment to be considered "up." After all regional targets deploy, the orchestrator polls each region's health endpoint and counts healthy ones. If count ≥ quorum, the deploy is declared successful. If count < quorum, it fails as if `RollbackAll` was triggered.

**`failover_strategy:`**:
- `active-active` — all regions serve traffic. Load balancer round-robins. On regional failure, traffic redistributes to remaining regions. Adapter responsibility: e.g. k8s adapter updates a global `Service` with multi-cluster endpoints.
- `active-passive` — one primary region serves traffic; others are warm standbys. Manual failover. Adapter exposes a `ssc deploy failover --region eu-west --env production` command.
- `active-standby` — automatic failover on health failure via DNS/anycast switch. Adapter responsibility.

### 8.4 Blue-green

**Slot model:** two parallel instances — `blue` and `green` — exist for every covered target. Each is a complete deploy (full replicas, current image, all config). Exactly one slot is *active* (receiving production traffic via load balancer / Service selector / DNS / Cloudflare routing rule). The other slot is *inactive*.

**Deploy flow:** `ssc deploy --env=production` always targets the *inactive* slot. The active slot continues serving with zero interruption. The new image is deployed, probed, and tested before traffic shifts.

**Switch flow:**
1. `ssc deploy switch --env=production [--to green]` — if `--to` omitted, switches to whichever slot is inactive.
2. Orchestrator polls `health_gate:` URL on the inactive slot until 200 OK.
3. Runs each `smoke_tests:` script. Failure aborts the switch; inactive slot stays deployed-but-idle for investigation.
4. `switch_strategy: instant` — atomic: update K8s `Service.spec.selector` / Cloudflare routing rule in one API call. Zero-downtime but no gradual ramp.
5. `switch_strategy: gradual` — percentage-stepped: emit `steps: [10, 50, 100]` of traffic to the new slot with `step_interval:` wait between steps. Between steps, the orchestrator polls metrics + health on the new slot and aborts + re-shifts to old slot on regression.
6. Post-switch: both slots are live; old slot kept warm for `hold_duration:` (default 30 min). `ssc deploy rollback --env=production` within the hold window is a slot re-switch (instant, no rebuild). Past the hold window, rollback re-deploys the previous artifact to the inactive slot, then switches.

**`ssc deploy promote --from=test-pr-42 --to=staging`** carries the exact artifact digest (image SHA, JAR hash) from the source env's state record to the destination env's blue-green flow. Does not rebuild; uses the cached artifact from the source deploy. Validates that the destination env's blue-green prerequisites are met before promoting.

### 8.5 Env state isolation

State records are keyed by `StateKey(env, target, slot)`. Multiple environments sharing the same S3 state backend are fully isolated by key. `purpose: local` defaults to `state: { backend: local }` (file-based, no remote coordination) even if the manifest declares a remote state backend — local env never shares state with remote envs.

### 8.6 Concurrency across envs

`ssc deploy --env=all --env-parallelism 4` deploys all declared environments with at most 4 running concurrently (global semaphore). Default is 1 (sequential) to avoid resource contention on small CI runners. Within each env, group topology drives its own parallelism independently.

---

## 9. Build artifact pipeline

### 9.1 How adapters obtain their artifact

At the start of `build()`, each adapter specifies its required `artifactKind`. `ArtifactRegistry` maps the kind to the appropriate builder:

| `ArtifactKind` | Builder | Source |
|---|---|---|
| `FatJar` | `buildFatJar(...)` | `Main.scala:1370-1417` |
| `ThinJar` | `buildJvmBootstrapJar(...)` | `Main.scala:1479` |
| `NativeBinary` | `scala-cli package --native` passthrough | `Main.scala:633-636` |
| `NodeBundle` | `emitJsCommand` capture | `Main.scala:1481-1490` |
| `SpaBundle` | `buildSingleFileSite(...)` | `Main.scala:1492-1494` |
| `OciImage` | Dockerfile gen + `docker build` | NEW — v1.52.2 |
| `War` | Maven WAR layout wrapper | NEW — v1.52.x |
| `LambdaZip` | zip + handler wrapper | NEW — v1.52.6 |
| `Tarball` | `tar czf` of build output | NEW — v1.52.4 |
| `RsyncTree` | build output dir (rsync at push time) | NEW — v1.52.4 |

### 9.2 Caching

The artifact path scheme `.ssc-artifacts/<basename>.<ext>` from `lang/core/src/main/scala/scalascript/imports/ImportResolver.scala:381-393` is reused. A content hash of the source + manifest version gates rebuild — `--no-build` skips the builder and uses the cached artifact directly.

For blue-green deploys, the orchestrator tags artifacts with `(env, slot, git.sha)` so both slots' artifacts are independently cached and the rollback slot's artifact is never evicted during the hold window.

### 9.3 Cross-compilation and platform notes

Building `NativeBinary` targeting `linux/amd64` from a macOS machine requires a cross-toolchain. The v1.52 spec documents the "build inside a Linux container" workaround (`docker run --rm -v $PWD:/src graalvm-linux-builder ssc package --native`). A first-class cross-compilation flow is deferred to a future native-image milestone.

`OciImage` with `platform: linux/amd64,linux/arm64` requires BuildKit (`buildctl` or `docker buildx`). The adapter invokes the available tool in order: `buildctl` → `docker buildx` → `docker build` (single platform fallback).

---

## 10. State backend semantics

### 10.1 Stateless default (no `state:` block)

Each `ssc deploy` reads current state from the target's own API — k8s API for current image/replicas, Lambda API for current alias version, SSH + `systemctl status` for traditional targets. `status` queries live; `rollback` queries target-native history. No file is written locally. Idempotent re-applies are safe.

Limitation: no cross-machine deploy history, no audit trail, no distributed lock. Acceptable for single-developer or CI-with-one-runner setups.

### 10.2 Remote state backends (optional)

When `state: { backend: s3, bucket: …, prefix: … }` is declared, the `StateBackend` SPI is activated. Implementations:

| Backend | Description |
|---|---|
| `local` | File `~/.ssc-state/<app>/<env>/<target>.json`; no locking |
| `s3` | S3 object with conditional put for optimistic locking; object lock for `ttl`-based mutex |
| `consul` | Consul KV with session-based locking |
| `etcd` | etcd v3 with lease-based locking |

All implementations are deferred to v1.52.7.

### 10.3 State record schema

```json
{
  "env": "production",
  "target": "backend-prod",
  "slot": "green",
  "revision": "v1.4.2",
  "artifact_hash": "sha256:abc123",
  "deployed_at": "2026-05-27T14:30:00Z",
  "deployed_by": "ci@github.com",
  "manifest_snapshot": { "…": "…" },
  "outputs": { "url": "https://backend-prod-green.example.com" }
}
```

Group-level records track which member is at which revision for cross-machine rollback of a whole group in one command.

### 10.4 Lock semantics

`StateBackend.lock(key, ttl)` acquires an exclusive per-(env, target) lock before any mutating operation (deploy, rollback, switch). TTL prevents lock orphaning if the deploying machine crashes. The lock holder is recorded in the state record — `ssc deploy status` shows holder + age when a lock is detected. `--force` breaks a stale lock after user confirmation.

---

## 11. Runtime semantics & backend notes

### 11.1 Where deploy code runs

Deploy is **build-time / CLI-time only**, executed by the `ssc` binary on the developer's machine or in CI. It is **not** part of the running app runtime. This is unlike `Feature.Streams` (v1.51), which required runtime changes across every backend. Deploy adapters run entirely in the `ssc` process (or in a subprocess for heavy operations like `docker build`).

### 11.2 No new `Feature` enum case in v1.52

`runtime/backend/spi/src/main/scala/scalascript/backend/spi/Feature.scala:12-38` enumerates runtime capabilities. Deploy is a CLI capability, not a runtime one — **no new `Feature` case is added in v1.52**. Future milestones may add `Feature.SelfDeploy` if an in-app rolling-deploy API is warranted, but that is out of scope.

### 11.3 Plugin layout

Follows the four-file canonical layout per `runtime/std/http-plugin/` and `runtime/std/ws-plugin/` and the forthcoming `runtime/std/streams-plugin/` (v1.51.1):

```
runtime/std/deploy-plugin/
  src/main/scala/scalascript/compiler/plugin/deploy/
    DeployPlugin.scala           # BackendPlugin entrypoint
    DeployIntrinsics.scala       # extern def wiring (none in v1.52; scaffold for future)
    DeployTarget.scala           # SPI trait + ArtifactKind + FailurePolicy enums
    DeployGroup.scala            # DeployGroup + ExecMode + orchestrator
    DeployEnvironment.scala      # DeployEnvironment + BlueGreenConfig + FaultToleranceConfig
    ArtifactRegistry.scala       # kind → builder dispatch
    StateBackend.scala           # StateBackend trait
  resources/META-INF/services/scalascript.backend.spi.Backend
```

`build.sbt` adds the module as a dependency of `cli` so `ssc deploy` can load it.

### 11.4 CLI wiring

`tools/cli/src/main/scala/scalascript/cli/Main.scala:84-137` dispatch table gains:

```scala
case "deploy" => deployCommand(rest, env, opts)
```

`deployCommand` parses the remaining tokens to extract the subverb (`build`, `push`, `rollback`, `status`, `logs`, `diff`, `destroy`, `plan`, `switch`, `promote`, `envs`) and delegates. The new help text block mirrors the structure of `packageCommand` (`Main.scala:7266-7356`) and `publishCommand` (`Main.scala:7364-7416`).

### 11.5 Concurrency implementation

Orchestrator parallelism uses virtual threads (Java 21, available in the JVM that already runs `ssc`). Each target's lifecycle runs in a VT; the orchestrator joins via `CountDownLatch` or — once v1.51.1 ships `Stream.merge` — via the v1.51 stream combinator. Cancellation via JVM shutdown hook (`ProxyRuntime.scala:244-247` precedent): on Ctrl+C, orchestrator catches the interrupt, applies the group's `on_failure` policy, and exits.

---

## 12. Diagnostics

Error format mirrors `docs/specs/algebraic-effects.md §9` — each error has a type tag, source location in the manifest, and a "fix:" hint.

**[deploy/unknown-target]** — `--env=local` references target `backend-prod` which is not declared in `deploy:` or the env's own targets. Available targets: `backend-local`, `frontend-local`. Did you mean `backend-local`?

**[deploy/unknown-env]** — `--env=prod` matches no declared environment. Available: `local`, `test-pr-42`, `staging`, `production`. Did you mean `production`?

**[deploy/missing-secret]** — `${vault:secret/app/prod}` in target `backend-prod` resolved to no value. Vault path not found or token expired. Run `vault kv get secret/app/prod` to verify.

**[deploy/artifact-build-failed]** — `[deploy/build] buildFatJar` exited 1: (underlying sbt error). Fix: resolve the compile error in `src/main/scala/…` and retry.

**[deploy/target-unreachable]** — k8s cluster `prod-east-1` unreachable. `kubectl get nodes` returned: `connection refused`. Check that `~/.kube/config` context `prod-east-1` points to a live cluster.

**[deploy/state-lock-contention]** — lock on `(production, backend-prod)` held by `ci@github.com` since 14:23 UTC (7 min ago). TTL: 15 min. To break early: `ssc deploy --force --env=production backend-prod`.

**[deploy/dag-cycle]** — manifest `groups.hybrid.depends_on` forms a cycle: `frontend → backend-prod → frontend` (lines 42, 67). Remove one dependency edge.

**[deploy/unresolved-output-ref]** — `${deploy.backend-prod.outputs.url}` in target `frontend.env.VITE_API_URL` (line 38): `backend-prod` is not a member of group `hybrid` and has not yet been deployed in env `production`. Either add `backend-prod` to group `hybrid` or declare `depends_on: { frontend: [backend-prod] }`.

**[deploy/quorum-failure]** — env `production` requires quorum ≥ 2 of [us-east, eu-west, ap-south]. After deploy: us-east: healthy ✓, eu-west: pending (timeout after 60s), ap-south: unhealthy (/_health returned 503). Quorum: 1 of 3. Initiating `RollbackAll`…

**[deploy/blue-green-health-gate-failed]** — smoke test `./scripts/smoke.sh` returned exit 1 for slot `green` of target `backend-prod` in env `production`. Switch aborted. Slot `green` remains deployed-but-inactive. Inspect: `ssc deploy logs backend-prod --env=production --slot green --since 5m`.

---

## 13. Non-goals for v1.52

- **Per-provider adapter implementations.** Fly.io, AWS Lambda, GCP Cloud Run, Cloudflare Workers, Vercel, Netlify, Render, Railway, Heroku — all named as future phase docs in §16 phasing. The SPI and taxonomy are specified; no provider code lands in v1.52.
- **Infrastructure provisioning.** v1.52 deploys to *existing* targets. No VPC creation, no k8s cluster provisioning, no DNS management, no TLS cert issuance. Use Terraform / Pulumi / CDK for those. A future `provision:` SPI alongside `deploy:` could open this door.
- **CI/CD pipeline generation.** No GitHub Actions / GitLab CI / CircleCI YAML emitted.
- **Staged-percentage canary as a general topology mode.** Canary semantics are in scope only as the blue-green `gradual` switch strategy (percentage-stepped within a two-slot model). A general "deploy to X% of a homogeneous fleet" mode — independent of blue-green slots — is deferred.
- **Distributed orchestrator (coordinator-worker model).** The v1.52 orchestrator runs on a single machine. Very large fleets may want the orchestrator to dispatch per-region work to remote workers. Deferred.
- **Ephemeral env auto-lifecycle.** Creating `test-pr-N` envs on PR open and destroying them on PR close is typically handled by CI integration. Out of scope; spec documents the manifest structure for ephemeral envs but does not generate CI pipelines.
- **Effect-row integration.** Deploy is CLI-time, not runtime; no `! Deploy` effect annotation.
- **In-app self-deploy.** A running app re-deploying a new version of itself is out of scope.
- **Cross-env traffic shifting at the env level.** Blue-green within an environment is in scope; shifting production traffic between two entire environments (e.g. for disaster failover at a global level) is deferred.
- **Cost estimation.** `ssc deploy cost` querying provider pricing APIs is deferred.
- **Hot-reload for local env.** `purpose: local` targets run once and stay up in v1.52.1. Integration with `ssc watch` for hot-reload is deferred to v1.52.x.

---

## 14. Future work

- **Per-provider plugins** — `runtime/std/deploy-plugin-fly/`, `runtime/std/deploy-plugin-aws/`, `runtime/std/deploy-plugin-gcp/`, `runtime/std/deploy-plugin-cloudflare/`, etc. Each is its own milestone released independently from the deploy-plugin core.
- **Infrastructure provisioning** — `provision:` SPI (Terraform-shaped) alongside `deploy:`. Greenfield environment management.
- **Distributed orchestrator** — coordinator dispatches per-region deploys to remote workers. Suitable for very large multi-region fleets where single-machine orchestration creates a bottleneck.
- **General canary topology mode** — `traffic:` block per target (10% → 50% → 100%) as a standalone `ExecMode.Canary(weights, interval)` group mode.
- **Ephemeral env auto-lifecycle** — `ssc deploy create-env --name test-pr-${PR_NUMBER} --from production` + `ssc deploy destroy-env`. Suitable for scripting from CI webhooks.
- **CI/CD pipeline generation** — `ssc deploy gen-ci [github|gitlab|circleci]` emits a starter workflow YAML that invokes `ssc deploy` per environment.
- **Hot-reload integration** — `ssc deploy --env=local --watch` combines `ssc watch` hot-rebuild with subprocess restart for the local environment.
- **Env-level blue-green** — shifting between two complete production environments for disaster failover.
- **Automatic failover** — `active-standby` failover strategy triggering on health threshold breach without human intervention.
- **Cost estimation** — `ssc deploy cost --env=production` queries cloud pricing APIs for the declared target configs.
- **Deploy-time effect-row integration** — `Deploy[T] ! Provisioning` if the provisioning SPI is added, enabling purely functional deploy pipelines.

---

## 15. Examples

These snippets use the manifest format defined in §4 and the CLI defined in §5. Full working examples land in `examples/deploy.ssc` in v1.52.1.

### 15.1 Single container target

```yaml
deploy:
  staging:
    kind: container
    registry: registry.example.com
    tag: staging-${git.sha}

environments:
  default:
    purpose: staging
    active_groups: []
```

```sh
ssc deploy staging --env=default --dry-run  # validate
ssc deploy staging --env=default            # build fat-JAR → Dockerfile → docker build → push → restart
```

### 15.2 Kubernetes production

```yaml
deploy:
  backend:
    kind: k8s
    cluster: prod-east-1
    namespace: app
    image: registry.example.com/app:${version}
    replicas: 3
```

```sh
ssc deploy backend --env=production         # generates Deployment + Service + Ingress + probes
ssc deploy rollback backend --env=production --to v1.4.1
ssc deploy logs backend --env=production --tail
```

### 15.3 Traditional SSH + systemd

```yaml
deploy:
  legacy:
    kind: traditional
    transport: ssh
    host: ${env:VPS_HOST}
    user: deploy
    path: /opt/app
    service: systemd
    unit: myapp.service
```

```sh
ssc deploy legacy --env=staging             # scp fat-JAR → systemctl restart myapp.service
ssc deploy status legacy --env=staging      # SSH → systemctl is-active → reports uptime
```

### 15.4 Static hosting

```yaml
deploy:
  site:
    kind: static
    provider: cloudflare-pages
    project: my-app
```

```sh
ssc deploy build site                       # emit-spa only
ssc deploy site --env=production            # emit-spa → pages deploy → CDN purge
```

### 15.5 FaaS edge

```yaml
deploy:
  edge:
    kind: faas
    provider: cloudflare-workers
    route: /api/*
```

```sh
ssc deploy edge --env=production            # emit node bundle → wrangler deploy
ssc deploy status edge --env=production     # invokes health check via worker route
```

### 15.6 Full-stack pipeline with output wiring

```yaml
deploy:
  api:
    kind: k8s
    cluster: prod
    namespace: app
    image: registry.example.com/api:${version}
  ui:
    kind: static
    provider: vercel
    project: my-ui
    env:
      NEXT_PUBLIC_API: ${deploy.api.outputs.url}   # wired from api deployment

groups:
  full-stack:
    mode: pipeline
    stages:
      - [api]
      - [ui]
    on_failure: rollback_all
```

```sh
ssc deploy full-stack --env=production      # api deploys → url captured → ui deploys with real URL
ssc deploy plan full-stack --env=production # show DAG before acting
```

### 15.7 Multi-region parallel deploy

```yaml
deploy:
  backend-us:  { kind: k8s, cluster: prod-us-east-1, namespace: app, image: …:${version} }
  backend-eu:  { kind: k8s, cluster: prod-eu-west-1, namespace: app, image: …:${version} }
  backend-ap:  { kind: k8s, cluster: prod-ap-south-1, namespace: app, image: …:${version} }

groups:
  multi-region:
    mode: parallel
    members: [backend-us, backend-eu, backend-ap]
    max_parallelism: 3
    on_failure: continue_remaining
```

```sh
ssc deploy multi-region --env=production    # all 3 regions in parallel; continue if any region fails
ssc deploy status --env=production          # quorum check: N of 3 healthy
```

### 15.8 Blue-green production switch

```yaml
environments:
  production:
    purpose: production
    blue_green:
      enabled: true
      active_slot: blue
      switch_strategy: gradual
      steps: [10, 50, 100]
      step_interval: 5m
      health_gate: /_health
      smoke_tests: [./scripts/smoke.sh]
      hold_duration: 30m
```

```sh
ssc deploy --env=production                 # deploy to inactive (green) slot; blue stays live
ssc deploy switch --env=production          # health-gate → smoke → 10%→50%→100% gradual switch
ssc deploy rollback --env=production        # re-switch to blue (within 30min hold); no rebuild
ssc deploy envs                             # shows: production: active=blue, green=deployed, hold 18min remaining
```

### 15.9 Local environment for debugging

```yaml
environments:
  local:
    purpose: local
    targets:
      api-local: { kind: traditional, transport: subprocess, port: 8080 }
      ui-local:  { kind: traditional, transport: subprocess, port: 5173 }
```

```sh
ssc deploy --env=local                      # starts api + ui as subprocesses; Ctrl+C to stop all
ssc deploy status --env=local               # polls localhost:8080/_health and localhost:5173
```

### 15.10 Promote across environments

```sh
# PR #42 environment deployed and tested; promote its artifact to staging
ssc deploy promote --from=test-pr-42 --to=staging  # reuses exact image digest; no rebuild
```

---

## 16. Implementation phasing

Seven milestones post-v1.52 sign-off. The orchestrator core lands in v1.52.1 so that v1.52.2+ adapter phases plug into a working runtime without re-architecting.

### v1.52.1 — Plugin scaffolding + AST + CLI stub + local env + orchestrator core

Files:
- `runtime/std/deploy-plugin/` — four-file layout (§11.3). All SPI traits defined. No real remote-target adapters yet.
- `lang/core/src/main/scala/scalascript/ast/AST.scala:19-70` — add `deploy`, `groups`, `environments`, `state` optional fields to `Manifest`.
- `tools/cli/src/main/scala/scalascript/cli/Main.scala:84-137` — add `case "deploy" => deployCommand(...)`.
- **Multi-target orchestrator core** — DAG resolver (cycle detection), parallel/sequence/pipeline executor, output→input wiring, partial-failure handler, structured event stream, progress reporter (multi-line live table + NDJSON CI mode).
- **Local environment runner** — `transport: subprocess` adapter: spawn fat-JAR process (`java -jar`), capture port, poll `/_health`. Only real adapter in v1.52.1; proves orchestrator + `--env=local` end-to-end.
- `ssc deploy plan <group> --env=<env>` — renders resolved DAG + env overlay without acting.
- `ssc deploy --dry-run` — validates manifest + SPI shapes; prints what would happen.
- `examples/deploy.ssc` — all snippets from §15.

### v1.52.2 — Container target (generic OCI)

- Dockerfile generator per `ArtifactKind` (four base-image choices from §6.1 table).
- `docker build` / `docker buildx` / `buildctl` invocation. Multi-platform via `platform:`.
- OCI registry push with auth (Docker Hub, GHCR, ECR, GCR — auth via `${env:DOCKER_USERNAME}` / `${vault:…}`).
- `status` via `docker inspect` or registry manifest API.
- `rollback` via previous image digest from state record.
- `outputs()`: `{ "digest": "…", "image": "…" }`.

### v1.52.3 — Kubernetes target + blue-green + multi-region fault tolerance

- K8s manifest generator: `Deployment` + `Service` + `Ingress` + `ConfigMap` + `Secret`.
- Apply via `kubectl` subprocess (`kubeconfig` / `context` from config).
- Probe wiring to `RestRuntime.scala:640-648` `/_health` + `/_ready`.
- PreStop lifecycle hook wired to `actors.ssc:416-428` + `cluster/index.ssc:46,58` draining.
- `rollback` via `kubectl rollout undo`.
- `logs` via `kubectl logs -f` → `Stream[LogLine]` (v1.51 streams, requires v1.51.1 or later).
- **Blue-green slot management** — two `Deployment`s (`<name>-blue`, `<name>-green`) + `Service` selector flip.
- `ssc deploy switch` + `ssc deploy promote` ship here.
- **Multi-region orchestration + quorum health check** — orchestrator polls each region's `/_health` and counts healthy against `quorum:`.
- `outputs()`: `{ "url": "…", "ingressHostname": "…", "serviceIP": "…" }`.

### v1.52.4 — Traditional hosting (SSH + systemd, rsync)

- SSH + SCP adapter: copies fat-JAR / native binary; renders systemd unit template; `systemctl restart`.
- Rsync adapter: syncs `RsyncTree` / `SpaBundle` to webroot.
- SFTP and FTP adapters: upload `Tarball`.
- `status` via SSH + `systemctl is-active` / HTTP GET.
- `logs` via SSH + `journalctl -f` → `Stream[LogLine]`.
- `outputs()`: `{ "host": "…", "port": "…", "url": "http://…" }`.

### v1.52.5 — Static hosting (generic)

- SPA bundle push (reuses `buildSingleFileSite`, `Main.scala:1492-1494`).
- API-based adapter (Vercel / Netlify / Cloudflare Pages — one each as reference implementations).
- Git-based adapter (push to `gh-pages` branch for GitHub Pages).
- Cache invalidation hook post-push.
- `outputs()`: `{ "url": "https://…", "deploymentId": "…" }`.

### v1.52.6 — FaaS (generic)

- Lambda zip adapter (AWS-shaped): zip + handler wrapper; create/update function; alias management.
- Cloudflare Workers: `NodeBundle` (for sync handlers) + Wasm bundle (for compute-intensive handlers).
- `rollback` via Lambda alias version pointer.
- `logs` via CloudWatch Logs API / Wrangler tail → `Stream[LogLine]`.
- `outputs()`: `{ "functionArn": "…", "invokeUrl": "…", "aliasVersion": "…" }`.

### v1.52.7 — Remote state backends

- `StateBackend` implementations: `LocalFileStateBackend`, `S3StateBackend`, `ConsulStateBackend`, `EtcdStateBackend`.
- Lock semantics with TTL; lock-break via `--force`.
- State migration CLI: `ssc deploy state migrate --from local --to s3`.
- Group-level state records for cross-machine rollback of whole group.
- Enforce: `purpose: production` envs require non-local state backend (validated at deploy start).

---

## 17. Risks & open questions

### 17.1 Adapter sprawl

**Risk:** 30+ per-provider adapters become unmaintained over time.

**Mitigation:** Core ships five generic adapters only (one per category). Each per-provider adapter is its own plugin module (`runtime/std/deploy-plugin-aws/`, `runtime/std/deploy-plugin-fly/`, etc.) releasable independently. Adapter authors outside the core team can publish their own plugins via the `BackendTransport` protocol.

### 17.2 Subprocess dependency fragility

**Risk:** Adapters that shell out to `docker` / `kubectl` / `ssh` / `wrangler` are fragile — tools may not be installed or may change their CLI interface.

**Mitigation:** SPI returns structured `ToolNotFound(name, installHint)` and `ToolVersionMismatch(name, got, want, upgradeHint)` diagnostics. Future phases can swap shell-outs for native SDKs (e.g. k8s Go client via JNA binding, AWS SDK v2). The adapter's plugin boundary isolates breaking changes.

### 17.3 Secrets exfiltration in verbose output

**Risk:** `${vault:…}` resolves to plaintext before deploy; verbose logging might leak values.

**Mitigation:** Resolved secret values are tagged in `DeployContext` as `Sensitive` and never appear in logs, `--verbose` output, or NDJSON event stream. Only the *key name* is logged (e.g. `vault:secret/app/prod → [MASKED]`). State records store only the *reference*, not the resolved value.

### 17.4 State lock orphan after crash

**Risk:** If the deploying machine crashes mid-deploy, the state lock is held indefinitely and blocks future deploys.

**Mitigation:** Lock has a TTL (default 15 min, configurable). `ssc deploy status` shows holder + age. `--force` breaks a stale lock after user confirmation and logs the break event to the state backend.

### 17.5 Config drift between manifest and actual state

**Risk:** User edits a K8s manifest directly (via `kubectl apply`), then runs `ssc deploy`. The manifest desired-state diverges from reality.

**Mitigation:** `ssc deploy diff --env=production` computes desired-vs-actual diff before applying. The orchestrator shows the diff in `--dry-run` output. `--force` suppresses the diff check and applies unconditionally. `ssc deploy status` always reflects live state, not last-known-deployed state.

### 17.6 Blue-green resource doubling

**Risk:** Running two slots (blue + green) during the hold window doubles cluster resource cost.

**Mitigation:** `hold_duration:` is tunable (set to 0 to disable the hold window entirely, at the cost of losing zero-rebuild rollback). `scale_inactive_to_zero: true` opt-in (deferred — requires adapter support) scales the inactive slot to 0 replicas after switch; rollback then re-scales before re-switching, adding ~30s to the rollback path. The trade-off is documented in §8.4.

### 17.7 Quorum interpretation ambiguity

**Risk:** "is the env up?" depends on `quorum:` — partial regional deployment may be acceptable to some teams and unacceptable to others.

**Mitigation:** `quorum: N` is explicit in the manifest. `ssc deploy status --env=production` shows quorum target vs actual + per-region health with a clear `✓` / `✗`. Exit code is non-zero if below quorum. `--quorum-override N` flag for one-off emergency promotion without changing the manifest.

### 17.8 GraalVM native binary cross-compilation

**Risk:** Building a Linux native binary for a container from a macOS dev machine requires a cross-toolchain.

**Mitigation:** The v1.52.2 container adapter documents the "build inside a Linux container" workaround: `docker run --rm -v $PWD:/src eclipse-temurin:21 ssc package --native && docker build .`. First-class macOS → Linux cross-compilation is deferred to a future native-image milestone.

### 17.9 Env config drift via inheritance chains

**Risk:** Many envs inheriting from `production` via `base:` silently inherit a change when `production`'s targets change.

**Mitigation:** `ssc deploy plan --env=<env>` always renders the fully-resolved overlay after inheritance, not the raw manifest. `ssc deploy diff` highlights env-level changes. v1.52.7 state backend snapshots resolved manifests per deploy, creating an auditable history.

### 17.10 Multi-target partial-rollback failure

**Risk:** Rolling back 8 of 10 deployed targets when target 9 fails is a distributed operation that can itself partially fail.

**Mitigation:** Orchestrator records per-target rollback state machine (`rolling-back` → `rolled-back | rollback-failed`). Final summary always includes a per-member status table. State backend (if configured) persists the partial state for resumption via a future `ssc deploy repair --env=production` command (deferred).

### 17.11 Output→input wiring pre-readiness race

**Risk:** A target's `outputs()` may return a URL before the underlying service is reachable (image pulled but not warmed up).

**Mitigation:** Optional `health_gate: <path>` per target — orchestrator polls the path with exponential backoff (max 120s by default, configurable) before marking outputs available. Independent of the K8s readiness probe (which gates pod-level traffic only); this gate applies between stages in the topology DAG.

---

## 18. Go / no-go

**Recommendation: go.**

### Why the foundation is solid

- **Artifact builders are mature** (v1.48–v1.50): `buildFatJar` (`Main.scala:1370-1417`), node bundle (`Main.scala:1481-1490`), SPA (`Main.scala:1492-1494`), scala-cli native passthrough — all proven. The new `OciImage` / `LambdaZip` / `Tarball` kinds are thin wrappers over these existing builders.
- **Server runtime has everything Kubernetes needs**: health/ready probes (`RestRuntime.scala:640-648`), Prometheus metrics (`ClusterRoutesRuntime.scala:127-160`), graceful draining (`actors.ssc:416-428`, `cluster/index.ssc:46,58`) — all present and tested.
- **Config + secret resolution covers the manifest**: `${env:…}` / `${vault:…}` / `${sops:…}` / `${file:…}` resolvers in `backend/config/src/main/scala/scalascript/config/SubstitutionEngine.scala` handle every variable in the manifest examples. Zero new config infrastructure.
- **Plugin pattern is proven**: `http-plugin`, `ws-plugin`, `json-plugin`, `sql-plugin`, `auth-plugin`, `oauth-plugin` all follow the same four-file layout. The `deploy-plugin` scaffold is straightforward.
- **Topology executor sits cleanly on existing primitives**: v1.51 `Stream[DeployEvent]` (v1.51.1) + v1.9 VTs (`coroutine.ssc:27-36`) handle parallel group execution without new concurrency infrastructure.
- **Type system needs zero changes**: all manifest extensions are optional map fields; all SPI types are plain Scala case classes not involving the `SType` hierarchy.

### What is genuinely new

- AST `Manifest` extension (`deploy:` / `groups:` / `environments:` / `state:` fields).
- `DeployTarget` + `DeployGroup` + `DeployEnvironment` + `StateBackend` SPI traits.
- Orchestrator core: DAG resolver, parallel/pipeline executor, output→input wiring, failure handler, event stream, progress reporter.
- Five generic adapters across seven phases.
- Blue-green slot management (K8s: two `Deployment`s + `Service` selector flip).
- Remote state backends (four implementations in v1.52.7).

### Implementation sequence

- **v1.52.1 immediately after sign-off** — orchestrator core + local env adapter + all SPI traits. This is the load-bearing phase; everything else plugs into it.
- **v1.52.2 + v1.52.3 + v1.52.4 may run in parallel** after v1.52.1 lands — they are independent adapter directories with no shared mutable state.
- **v1.52.3 should prioritize** over v1.52.2 and v1.52.4 if capacity is limited — K8s is the canonical fault-tolerance + blue-green target and the most commonly requested.
- v1.52.5, v1.52.6, v1.52.7 follow in any order.

**Go.**
