import sbt.Project

/** Registry entry for a ScalaScript frontend backend project.
 *
 *  Defined in project/ so it is visible across all build.sbt segments
 *  without incremental-compilation scope issues in worktrees.
 *
 *  `frontendCore` is excluded — it is the shared library that every
 *  backend depends on, not a backend itself. */
final case class FrontendSpec(
  id:      String,
  project: Project,
)
