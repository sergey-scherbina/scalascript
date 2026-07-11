package ssc.swift

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

final case class SwiftAppMetadata(
    bundleId: String,
    displayName: String,
    marketingVersion: String = "1.0.0",
    buildVersion: String = "1",
)

final case class XcodeAppArtifact(
    project: String,
    scheme: String,
    target: String,
    appProduct: String,
    bundleId: String,
    displayName: String,
    marketingVersion: String,
    buildVersion: String,
)

private[swift] object SwiftXcodeProject:
  private val sourcePaths = Vector(
    "Sources/AppCore/SscRuntime.swift",
    "Sources/AppCore/NativeUiHost.swift",
    "Sources/AppCore/GeneratedProgram.swift",
    "AppleApp/NativeUiStore.swift",
    "AppleApp/NativeUiRenderer.swift",
    "AppleApp/NativeUiStyles.swift",
    "AppleApp/NativeUiHtml.swift",
  )
  val assetsPath = "AppleApp/Resources/Assets.xcassets"
  val assetsContentsPath = s"$assetsPath/Contents.json"
  val assetsContents = """{
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
"""

  def generate(
      product: String,
      metadata: SwiftAppMetadata,
      existingResources: Vector[String],
  ): (Vector[(String, String)], XcodeAppArtifact) =
    validate(metadata)
    val appSource = s"AppleApp/${product}App.swift"
    val sources = (sourcePaths :+ appSource).sorted
    val project = s"$product.xcodeproj"
    val artifact = XcodeAppArtifact(
      project = project,
      scheme = product,
      target = product,
      appProduct = s"$product.app",
      bundleId = metadata.bundleId,
      displayName = metadata.displayName,
      marketingVersion = metadata.marketingVersion,
      buildVersion = metadata.buildVersion,
    )
    val files = Vector(
      assetsContentsPath -> assetsContents,
      s"$project/project.pbxproj" -> pbxproj(
        product, metadata, sources,
        (Vector(assetsPath) ++ existingResources).distinct.sorted),
      s"$project/xcshareddata/xcschemes/$product.xcscheme" -> scheme(product),
    )
    files -> artifact

  private[swift] def validate(metadata: SwiftAppMetadata): Unit =
    val bundlePattern = "[A-Za-z0-9][A-Za-z0-9-]*(\\.[A-Za-z0-9][A-Za-z0-9-]*)+".r
    if bundlePattern.matches(metadata.bundleId) == false then
      invalid("bundle-id", metadata.bundleId, "must be reverse-DNS ASCII segments")
    if metadata.displayName.trim.isEmpty then invalid("display-name", metadata.displayName, "must be non-empty")
    validateVersion("version", metadata.marketingVersion)
    validateVersion("build-version", metadata.buildVersion)

  private def validateVersion(key: String, value: String): Unit =
    if !"[0-9]+(\\.[0-9]+){0,2}".r.matches(value) then
      invalid(key, value, "must contain one to three dotted decimal components")

  private def invalid(key: String, value: String, reason: String): Nothing =
    val bounded = value.codePoints().limit(256).toArray.map(Character.toChars).map(String(_)).mkString
    throw new IllegalArgumentException(s"Swift app metadata '$key' value '$bounded' $reason")

  private def id(key: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(key.getBytes(StandardCharsets.UTF_8))
      .take(12).map(b => f"${b & 0xff}%02X").mkString

  private def q(value: String): String =
    "\"" + value.flatMap {
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case c => c.toString
    } + "\""

  private def pbxproj(
      product: String,
      metadata: SwiftAppMetadata,
      sources: Vector[String],
      resources: Vector[String],
  ): String =
    val allKeys = Vector(
      "project", "main-group", "products-group", "sources-group", "apple-group", "resources-group",
      "target", "product-ref", "sources-phase", "resources-phase", "frameworks-phase",
      "project-config-list", "target-config-list", "project-debug", "project-release",
      "target-debug", "target-release",
    ) ++ sources.flatMap(path => Vector(s"source-ref:$path", s"source-build:$path")) ++
      resources.flatMap(path => Vector(s"resource-ref:$path", s"resource-build:$path"))
    val ids = allKeys.map(key => key -> id(key)).toMap
    require(ids.values.toSet.size == ids.size, "deterministic PBX object id collision")
    def i(key: String) = ids(key)
    val sourceRefs = sources.map { path =>
      s"\t\t${i(s"source-ref:$path")} /* ${path.split('/').last} */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = ${q(path)}; sourceTree = SOURCE_ROOT; };"
    }.mkString("\n")
    val sourceBuilds = sources.map { path =>
      s"\t\t${i(s"source-build:$path")} /* ${path.split('/').last} in Sources */ = {isa = PBXBuildFile; fileRef = ${i(s"source-ref:$path")} /* ${path.split('/').last} */; };"
    }.mkString("\n")
    val sourceChildren = sources.map(path => s"\t\t\t\t${i(s"source-ref:$path")} /* ${path.split('/').last} */,").mkString("\n")
    val sourcePhaseFiles = sources.map(path => s"\t\t\t\t${i(s"source-build:$path")} /* ${path.split('/').last} in Sources */,").mkString("\n")
    val resourceRefs = resources.map { path =>
      val fileType = if path.endsWith(".xcassets") then "folder.assetcatalog" else "text"
      s"\t\t${i(s"resource-ref:$path")} /* ${path.split('/').last} */ = {isa = PBXFileReference; lastKnownFileType = $fileType; path = ${q(path)}; sourceTree = SOURCE_ROOT; };"
    }.mkString("\n")
    val resourceBuilds = resources.map { path =>
      s"\t\t${i(s"resource-build:$path")} /* ${path.split('/').last} in Resources */ = {isa = PBXBuildFile; fileRef = ${i(s"resource-ref:$path")} /* ${path.split('/').last} */; };"
    }.mkString("\n")
    val resourceChildren = resources.map(path => s"${i(s"resource-ref:$path")} /* ${path.split('/').last} */").mkString(", ")
    val resourcePhaseFiles = resources.map(path => s"${i(s"resource-build:$path")} /* ${path.split('/').last} in Resources */").mkString(", ")
    val targetSettings = Vector(
      "CODE_SIGN_STYLE = Automatic;",
      "CURRENT_PROJECT_VERSION = " + q(metadata.buildVersion) + ";",
      "GENERATE_INFOPLIST_FILE = YES;",
      "INFOPLIST_KEY_CFBundleDisplayName = " + q(metadata.displayName) + ";",
      "IPHONEOS_DEPLOYMENT_TARGET = 16.0;",
      "MACOSX_DEPLOYMENT_TARGET = 13.0;",
      "MARKETING_VERSION = " + q(metadata.marketingVersion) + ";",
      "PRODUCT_BUNDLE_IDENTIFIER = " + q(metadata.bundleId) + ";",
      "PRODUCT_NAME = \"$(TARGET_NAME)\";",
      "SUPPORTED_PLATFORMS = \"iphoneos iphonesimulator macosx\";",
      "SUPPORTS_MACCATALYST = NO;",
      "SWIFT_STRICT_CONCURRENCY = complete;",
      "SWIFT_VERSION = 6.0;",
      "TARGETED_DEVICE_FAMILY = \"1,2\";",
    ).map("\t\t\t\t" + _).mkString("\n")
    s"""// !$$*UTF8*$$!
{
\tarchiveVersion = 1;
\tclasses = {};
\tobjectVersion = 56;
\tobjects = {

/* Begin PBXBuildFile section */
$sourceBuilds
$resourceBuilds
/* End PBXBuildFile section */

/* Begin PBXFileReference section */
$sourceRefs
$resourceRefs
\t\t${i("product-ref")} /* $product.app */ = {isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = ${q(s"$product.app")}; sourceTree = BUILT_PRODUCTS_DIR; };
/* End PBXFileReference section */

/* Begin PBXFrameworksBuildPhase section */
\t\t${i("frameworks-phase")} = {isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; };
/* End PBXFrameworksBuildPhase section */

/* Begin PBXGroup section */
\t\t${i("main-group")} = {isa = PBXGroup; children = (${i("sources-group")}, ${i("resources-group")}, ${i("products-group")}); sourceTree = "<group>"; };
\t\t${i("sources-group")} = {isa = PBXGroup; children = (
$sourceChildren
\t\t\t); name = Sources; sourceTree = "<group>"; };
\t\t${i("resources-group")} = {isa = PBXGroup; children = ($resourceChildren); name = Resources; sourceTree = "<group>"; };
\t\t${i("products-group")} = {isa = PBXGroup; children = (${i("product-ref")}); name = Products; sourceTree = "<group>"; };
/* End PBXGroup section */

/* Begin PBXNativeTarget section */
\t\t${i("target")} = {isa = PBXNativeTarget; buildConfigurationList = ${i("target-config-list")}; buildPhases = (${i("sources-phase")}, ${i("frameworks-phase")}, ${i("resources-phase")}); buildRules = (); dependencies = (); name = ${q(product)}; productName = ${q(product)}; productReference = ${i("product-ref")}; productType = "com.apple.product-type.application"; };
/* End PBXNativeTarget section */

/* Begin PBXProject section */
\t\t${i("project")} = {isa = PBXProject; attributes = {BuildIndependentTargetsInParallel = YES; LastSwiftUpdateCheck = 1400; LastUpgradeCheck = 1400; }; buildConfigurationList = ${i("project-config-list")}; compatibilityVersion = "Xcode 14.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, Base); mainGroup = ${i("main-group")}; productRefGroup = ${i("products-group")}; projectDirPath = ""; projectRoot = ""; targets = (${i("target")}); };
/* End PBXProject section */

/* Begin PBXResourcesBuildPhase section */
\t\t${i("resources-phase")} = {isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = ($resourcePhaseFiles); runOnlyForDeploymentPostprocessing = 0; };
/* End PBXResourcesBuildPhase section */

/* Begin PBXSourcesBuildPhase section */
\t\t${i("sources-phase")} = {isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = (
$sourcePhaseFiles
\t\t\t); runOnlyForDeploymentPostprocessing = 0; };
/* End PBXSourcesBuildPhase section */

/* Begin XCBuildConfiguration section */
\t\t${i("project-debug")} = {isa = XCBuildConfiguration; buildSettings = {CLANG_ENABLE_MODULES = YES;}; name = Debug; };
\t\t${i("project-release")} = {isa = XCBuildConfiguration; buildSettings = {CLANG_ENABLE_MODULES = YES;}; name = Release; };
\t\t${i("target-debug")} = {isa = XCBuildConfiguration; buildSettings = {
$targetSettings
\t\t\t}; name = Debug; };
\t\t${i("target-release")} = {isa = XCBuildConfiguration; buildSettings = {
$targetSettings
\t\t\t}; name = Release; };
/* End XCBuildConfiguration section */

/* Begin XCConfigurationList section */
\t\t${i("project-config-list")} = {isa = XCConfigurationList; buildConfigurations = (${i("project-debug")}, ${i("project-release")}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; };
\t\t${i("target-config-list")} = {isa = XCConfigurationList; buildConfigurations = (${i("target-debug")}, ${i("target-release")}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; };
/* End XCConfigurationList section */
\t};
\trootObject = ${i("project")};
}
"""

  private def scheme(product: String): String =
    val targetId = id("target")
    val ref = s"container:$product.xcodeproj"
    s"""<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1400" version="1.7">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES">
    <BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="$targetId" BuildableName="$product.app" BlueprintName="$product" ReferencedContainer="$ref"/></BuildActionEntry></BuildActionEntries>
  </BuildAction>
  <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.DebuggerFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"/>
  <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.DebuggerFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="$targetId" BuildableName="$product.app" BlueprintName="$product" ReferencedContainer="$ref"/></BuildableProductRunnable></LaunchAction>
  <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="$targetId" BuildableName="$product.app" BlueprintName="$product" ReferencedContainer="$ref"/></BuildableProductRunnable></ProfileAction>
  <AnalyzeAction buildConfiguration="Debug"/>
  <ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
"""
