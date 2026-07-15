package ssc.plugin;

/** Provider bytecode with the pre-profile shape: only id plus install. */
public final class JavaLegacyServicePlugin implements NativePlugin {
  @Override
  public String id() {
    return "service-legacy";
  }

  @Override
  public void install(NativePluginContext context) {
    // The compatibility assertion is class linkage plus inherited default dispatch.
  }
}
