import sbt.Project

final case class PluginSpec(
  id: String,
  project: Project,
  jarPrefix: String
)
