package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Unit tests for the v1.48 Phase 2 SwiftUI CLI integration.
 *
 *  Verifies:
 *  - `validFrontendNames` includes "swiftui"
 *  - `ToolchainCommand` mobile-ios target maps to swift + xcode (not kotlin)
 *  - Build targets "mobile-ios", "macos", and "desktop-macos" (alias) are known
 *  - `swiftAppName` derives the correct Swift identifier from .ssc name frontmatter
 */
class SwiftUIBuildCliTest extends AnyFunSuite:

  test("validFrontendNames includes swiftui") {
    assert(validFrontendNames.contains("swiftui"))
  }

  test("ToolchainCommand mobile-ios uses swift + xcode (not kotlin)") {
    val tools = ToolchainCommand.targetTools("mobile-ios")
    assert(tools.contains("swift"),          "mobile-ios must require swift")
    assert(tools.contains("xcode"),          "mobile-ios must require xcode")
    assert(!tools.contains("kotlin"),        "mobile-ios must NOT require kotlin (SwiftUI, not KMM)")
    assert(!tools.contains("kotlin-native"), "mobile-ios must NOT require kotlin-native")
  }

  test("ToolchainCommand macos (canonical name) requires swift") {
    val tools = ToolchainCommand.targetTools("macos")
    assert(tools.contains("swift"), "macos must require swift")
    assert(tools.contains("jdk"),   "macos must require jdk")
  }

  test("ToolchainCommand desktop-macos (alias) still works") {
    val tools = ToolchainCommand.targetTools("desktop-macos")
    assert(tools.contains("swift"), "desktop-macos alias must require swift")
  }

  test("swiftAppName derives correct Swift identifier") {
    assert(swiftAppName(Some("MyApp"))          == "MyApp")
    assert(swiftAppName(Some("My App"))         == "MyApp")
    assert(swiftAppName(Some("my-app"))         == "Myapp")
    assert(swiftAppName(Some("ScalaScript App"))== "ScalaScriptApp")
    assert(swiftAppName(None)                   == "ScalaScriptApp")
    assert(swiftAppName(Some(""))               == "App")
  }
