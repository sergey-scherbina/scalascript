package scalascript.compiler.plugin.graphql

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Interpreter plugin that wires GraphQL schema + resolver intrinsics.
 *
 *  Registered via META-INF/services/scalascript.backend.spi.Backend (ServiceLoader).
 *
 *  Each plugin instance owns one `GraphQLJvmBlockRunner` — the same runner is
 *  exposed via `graphqlBlockRunner` (so `SectionRuntime` populates the SDL) and
 *  captured in the intrinsics closures (so `serveGraphQL` reads the SDL). */
class GraphQLInterpreterPlugin extends Backend:
  private val blockRunner   = new GraphQLJvmBlockRunner()
  private val intrinsicsObj = new GraphQLIntrinsics(blockRunner)

  def id:          String = "scalascript-graphql-interpreter"
  def displayName: String = "GraphQL Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = intrinsicsObj.table
  def acceptedSources: Set[String]                       = Set.empty

  override def graphqlBlockRunner: Option[GraphQLBlockRunner] = Some(blockRunner)

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic(
      "GraphQLInterpreterPlugin does not compile — intrinsic provider only")))
