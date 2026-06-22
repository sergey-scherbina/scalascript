import sbt.Project

sealed trait PluginTier {
  def autoLoad: Boolean
}

object PluginTier {
  case object Essential extends PluginTier {
    override val autoLoad: Boolean = true
  }

  case object Advanced extends PluginTier {
    override val autoLoad: Boolean = false
  }
}

/** Registry entry for a ScalaScript std plugin.
 *
 *  Defined in project/ so it is visible across all build.sbt segments
 *  without incremental-compilation scope issues in worktrees. */
final case class PluginSpec(
  id:        String,
  project:   Project,
  jarPrefix: String,
  tier:      PluginTier = PluginTier.Essential,
) {
  def autoLoad: Boolean = tier.autoLoad
}
