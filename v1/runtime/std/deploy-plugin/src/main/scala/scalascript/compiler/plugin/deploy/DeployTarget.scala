package scalascript.compiler.plugin.deploy

// ── Artifact kinds ──────────────────────────────────────────────────────────

enum ArtifactKind:
  case FatJar       // buildFatJar — Main.scala:1370-1417
  case ThinJar      // buildJvmBootstrapJar — Main.scala:1479
  case NativeBinary // scala-cli --native — Main.scala:633-636
  case NodeBundle   // emitJsCommand — Main.scala:1481-1490
  case SpaBundle    // buildSingleFileSite — Main.scala:1492-1494
  case OciImage     // Dockerfile gen + docker build (v1.52.2)
  case War          // Maven WAR layout (v1.52.x)
  case LambdaZip    // zip + handler wrapper (v1.52.6)
  case Tarball      // tar.gz (v1.52.4)
  case RsyncTree    // directory tree for rsync (v1.52.4)

// ── Lifecycle result types ──────────────────────────────────────────────────

case class BuildResult(artifactPath: String, artifactKind: ArtifactKind, metadata: Map[String, String] = Map.empty)

case class PushResult(ref: String, registry: Option[String] = None, metadata: Map[String, String] = Map.empty)

case class DeployResult(revision: String, url: Option[String] = None, metadata: Map[String, String] = Map.empty)

case class RollbackResult(revision: String, metadata: Map[String, String] = Map.empty)

case class StatusReport(
  target:   String,
  healthy:  Boolean,
  revision: Option[String] = None,
  slot:     Option[String] = None,
  message:  String = ""
)

case class LogLine(timestamp: String, level: String, message: String)

case class LogOpts(tail: Boolean = false, since: Option[String] = None, slot: Option[String] = None)

case class RevisionRef(id: String, slot: Option[String] = None)

// ── Failure / execution policies ───────────────────────────────────────────

enum FailurePolicy:
  case RollbackAll        // undo all deployed members in reverse order (default)
  case ContinueRemaining  // skip failed target's dependents; keep deploying independents
  case AbortRemaining     // stop; leave deployed members as-is; no rollback

// ── Deploy context ──────────────────────────────────────────────────────────

case class DeployContext(
  targetName:    String,
  config:        Map[String, Any],
  env:           String,
  slot:          Option[String],
  dryRun:        Boolean,
  verbose:       Boolean,
  outputsOf:     String => Map[String, String],
  workDir:       os.Path,
)

// ── DeployTarget SPI trait ──────────────────────────────────────────────────

trait DeployTarget:
  def kind:         String
  def artifactKind: ArtifactKind

  def build(ctx: DeployContext): BuildResult
  def push(ctx: DeployContext, art: BuildResult): PushResult
  def deploy(ctx: DeployContext, ref: PushResult): DeployResult
  def rollback(ctx: DeployContext, to: RevisionRef): RollbackResult
  def status(ctx: DeployContext): StatusReport
  def logs(ctx: DeployContext, opts: LogOpts): Iterator[LogLine]
  def outputs(ctx: DeployContext): Map[String, String]

// ── Structured deploy events ────────────────────────────────────────────────

enum DeployEvent:
  case Started(target: String, env: String, slot: Option[String])
  case Building(target: String, progress: String)
  case Pushed(target: String, ref: String)
  case Deployed(target: String, outputs: Map[String, String])
  case Failed(target: String, error: String)
  case RolledBack(target: String)
  case SkippedDependency(target: String, blockedBy: String)
  case GroupComplete(group: String, success: Boolean, statuses: Map[String, String])
