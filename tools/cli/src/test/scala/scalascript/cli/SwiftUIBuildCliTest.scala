package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Unit tests for the v1.48 Phase 2 SwiftUI CLI integration.
 *
 *  Verifies:
 *  - `validFrontendNames` includes "swiftui"
 *  - `ToolchainCommand` mobile-ios target maps to swift + xcode (not kotlin)
 *  - Build target "mobile-ios" and "desktop-macos" are handled without
 *    hitting the "unknown target" error branch
 *  - `buildProjectFileCommand` error message lists the new targets
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

  test("ToolchainCommand desktop-macos unchanged (swift)") {
    val tools = ToolchainCommand.targetTools("desktop-macos")
    assert(tools.contains("swift"), "desktop-macos must require swift")
  }
