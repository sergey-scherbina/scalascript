package scalascript.interpreter

// Health route registration: /_health, /_ready.
// Cluster-control routes moved to actorsPlugin/ClusterRoutesRuntime (registered by ActorScheduler).
private[interpreter] object ClusterRoutesRuntime:

  def registerHealthDefaults(interp: Interpreter): Unit =
    def isRegistered(path: String): Boolean =
      interp.routeRegistry.all.exists(e => e.method == "GET" && e.path == path)
    val okResponse = Value.InstanceV("Response", Map(
      "status"  -> Value.intV(200),
      "headers" -> Value.MapV(Map(
        Value.StringV("Content-Type") -> Value.StringV("application/json")
      )),
      "body"    -> Value.StringV("""{"status":"ok"}""")
    ))
    val handler = Value.NativeFnV("_healthOk", Computation.pureFn(_ => okResponse))
    if !isRegistered("/_health") then
      interp.routeRegistry.register("GET", "/_health", handler, interp)
    if !isRegistered("/_ready") then
      interp.routeRegistry.register("GET", "/_ready", handler, interp)
