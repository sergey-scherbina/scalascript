import sbt.Project

/** Registry entry for a ScalaScript std plugin.
 *
 *  Defined in project/ so it is visible across all build.sbt segments
 *  without incremental-compilation scope issues in worktrees. */
final case class PluginSpec(
  id:        String,
  project:   Project,
  jarPrefix: String,
)
