package scalascript.compiler.plugin.content

import scalascript.backend.spi.*
import scalascript.ir.{NormalizedModule, QualifiedName}

private object ContentCodegenIntrinsics:
  private val lowLevelHelperNames = List(
    "contentDocument",
    "contentCurrentSection",
    "contentSection",
    "contentBlock",
    "contentData",
    "contentMetadata",
    "contentBind",
    "contentPlainText",
    "contentToMarkdown",
    "contentModules",
    "contentModule",
    "contentModuleSection",
    "contentModuleBlock",
    "contentModuleData",
    "contentModuleMetadata"
  )

  private val toolkitHelperNames = List(
    "contentToolkitNode",
    "contentToolkitBlock",
    "contentToolkitSection"
  )

  val jsTable: Map[QualifiedName, IntrinsicImpl] =
    lowLevelHelperNames.map(name => QualifiedName(name) -> RuntimeCall(name)).toMap

  val jvmTable: Map[QualifiedName, IntrinsicImpl] =
    (lowLevelHelperNames ++ toolkitHelperNames).map(name => QualifiedName(name) -> RuntimeCall(name)).toMap

abstract class ContentCodegenPlugin extends Backend with TargetedIntrinsicProvider:
  def spiVersion: String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs = Set.empty,
    options = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics: Map[QualifiedName, IntrinsicImpl]
  def acceptedSources: Set[String] = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic(s"$displayName does not compile — intrinsic provider only")))

class ContentJsCodegenPlugin extends ContentCodegenPlugin:
  def id: String = "scalascript-content-js-codegen"
  def displayName: String = "Content Intrinsics (JS Codegen)"
  def targetBackendIds: Set[String] = Set("js", "node")
  def intrinsics: Map[QualifiedName, IntrinsicImpl] = ContentCodegenIntrinsics.jsTable

class ContentJvmCodegenPlugin extends ContentCodegenPlugin:
  def id: String = "scalascript-content-jvm-codegen"
  def displayName: String = "Content Intrinsics (JVM Codegen)"
  def targetBackendIds: Set[String] = Set("jvm")
  def intrinsics: Map[QualifiedName, IntrinsicImpl] = ContentCodegenIntrinsics.jvmTable
