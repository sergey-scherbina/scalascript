package ssc3.plugins

/** `ssc.cli` WITH THE FLEET LOADED — the bridge lane's entry point.
  *
  * The bridge is a SEPARATE JVM: `v3/ssc3` runs `java -cp … ssc.cli` and hands it Core IR. Putting
  * the plugin jars on that process's classpath is not enough, because `ssc.cli` never asks for
  * them — plugin loading lives in a larger launcher outside `v2/src`, which is why the bridge
  * answered `unimplemented primitive: mkdirs` with the fleet sitting right there on the classpath.
  *
  * NOTHING IN v2 NEEDED CHANGING, and that was checked before writing this rather than after:
  * `Prims.resolve` already falls back to `V2PluginRegistry.lookup(op)` before it throws
  * (`v2/src/Runtime.scala:3126`). So the registry is consulted; it was simply empty. One
  * `loadAll()` in this process fills it.
  *
  * WHY IT IS HERE AND NOT A CHANGE TO `ssc.cli`. `v2/src` is a different subsystem with its own
  * owner, and it does not need to know that v3 wants a fleet — this is v3 arranging its own lane.
  * The same reasoning puts the executor's adapter in this directory rather than in `v3/src`. */
@main def v2cli(args: String*): Unit =
  ssc.plugin.NativePluginHost.loadAll()
  ssc.cli(args*)
