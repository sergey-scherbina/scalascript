package scalascript.backend.spi

/** A registered string interpolator for the `InterpolatorRegistry`.
 *
 *  Phase 1: returnTypeName as string (parsed to SType by the Typer),
 *  jvmEmit/jsEmit as source-fragment strings.
 *  evalInterp is deferred to Phase 2 when PluginValue/PluginComputation
 *  are wired into the interpreter eval path (arch-stable-spi-p2).
 *
 *  See docs/specs/arch-dsl-hooks.md §4a — InterpolatorRegistry. */
trait InterpolatorImpl:
  /** Interpolator prefix, e.g. "gql", "sql", "html". */
  def name: String

  /** Return-type name assigned by the Typer, e.g. "String", "GqlQuery".
   *  Defaults to "String" so existing user-defined interpolators keep
   *  their inferred String type. */
  def returnTypeName: String = "String"

  /** Backend features required to use this interpolator.
   *  CapabilityCheck gates on these when scanning source. */
  def requiredFeatures: Set[Feature] = Set.empty

  /** Emit the JVM (Scala 3) source fragment for this interpolator.
   *  @param parts the literal string parts between interpolations
   *  @param args  the interpolated expression strings (already emitted)
   *  @return a Scala 3 source expression */
  def jvmEmit(parts: List[String], args: List[String]): String

  /** Emit the JS source fragment for this interpolator.
   *  @param parts the literal string parts between interpolations
   *  @param args  the interpolated expression strings (already emitted)
   *  @return a JavaScript expression */
  def jsEmit(parts: List[String], args: List[String]): String
