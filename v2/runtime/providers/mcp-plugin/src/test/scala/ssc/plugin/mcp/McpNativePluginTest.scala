package ssc.plugin.mcp

import org.scalatest.funsuite.AnyFunSuite
import ssc.{V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

final class McpNativePluginTest extends AnyFunSuite:
  test("only explicit Spawn transport is accepted") {
    NativePluginHost.installProviders(List(McpNativePlugin()))
    val connect = V2PluginRegistry.lookup("mcpConnect").get
    val error = intercept[IllegalArgumentException] {
      connect(List(Value.DataV("Stdio", Vector.empty)))
    }
    assert(error.getMessage.contains("supports Transport.Spawn"))
  }

  test("descriptor field layouts are registered") {
    NativePluginHost.installProviders(List(McpNativePlugin()))
    assert(V2PluginRegistry.lookupFieldNames("ToolDescriptor", 3).contains(
      Vector("name", "description", "schema")))
    assert(V2PluginRegistry.lookupFieldNames("PromptDescriptor", 3).contains(
      Vector("name", "description", "args")))
  }
