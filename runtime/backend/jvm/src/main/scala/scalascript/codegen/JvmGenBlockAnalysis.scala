package scalascript.codegen

import scalascript.ast.*
import scala.meta.*

/** Pure capability-detection predicates over code blocks, extracted from
 *  JvmGen to keep the generator navigable. Each `blocksUseXxx` answers
 *  "does any block reference feature X?" by scanning the block source text
 *  and its parsed scalameta AST. No generator state is touched, so this is a
 *  plain mixin trait rather than a self-typed one. */
private[codegen] trait JvmGenBlockAnalysis:
  // ─── Routing detection ───────────────────────────────────────────
  //
  // True when any code block calls `mcpServer`, `serveMcp`, or `mcpConnect`.
  private[codegen] def blocksUseMcp(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name("mcpServer"),  _) => found = true
          case Term.Apply.After_4_6_0(Term.Name("serveMcp"),   _) => found = true
          case Term.Apply.After_4_6_0(Term.Name("mcpConnect"), _) => found = true
        }
      }
      found
    }

  private[codegen] def blocksUseTypedData(blocks: List[JvmGen.Block]): Boolean =
    val names = Set(
      "JsonCodec", "JsonValue", "JsonFieldSpec",
      "RowCodec", "RowValue", "RowValueCodec", "RowFieldSpec",
      "ObjectCodec", "ObjectValue", "ObjectFieldSpec",
      "VertexCodec", "VertexValue", "EdgeCodec", "EdgeValue",
      "RdfCodec", "RdfValue", "RdfTriple", "RdfNode",
      "DatasetCodec", "DatasetPartition", "DatasetWire", "DatasetWirePartition",
      "SparkSchemaCodec", "SparkSchema", "SparkSchemaField", "SparkSchemaType"
    )
    blocks.exists { b =>
      var found = false
      if b.src.contains("scalascript.typeddata") || names.exists(name => b.src.contains(name)) then
        found = true
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Import(importers) if importers.exists(_.ref.syntax.startsWith("scalascript.typeddata")) => found = true
          case Term.Name(name) if names(name) => found = true
          case Type.Name(name) if names(name) => found = true
        }
      }
      found
    }

  private[codegen] def blocksUseWire(blocks: List[JvmGen.Block]): Boolean =
    val names = Set("WireFormat", "WireEnvelope", "WireValue", "WireCodec", "WireDecodeError")
    blocks.exists { b =>
      var found = false
      if b.src.contains("scalascript.wire") || names.exists(name => b.src.contains(name)) then
        found = true
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Import(importers) if importers.exists(_.ref.syntax.startsWith("scalascript.wire")) => found = true
          case Term.Name(name) if names(name) => found = true
          case Type.Name(name) if names(name) => found = true
        }
      }
      found
    }

  private[codegen] def blocksUseObjectStore(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = b.src.contains("ObjectStore.")
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Select(Term.Name("ObjectStore"), _) => found = true
          case Term.Name("ObjectStore") => found = true
        }
      }
      found
    }

  private[codegen] def blocksUseGraph(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = b.src.contains("Graph.") || b.src.contains("GraphRuntime") || b.src.contains("scalascript.graph")
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Import(importers) if importers.exists(_.ref.syntax.startsWith("scalascript.graph")) => found = true
          case Term.Select(Term.Name("Graph"), _) => found = true
          case Term.Name("Graph") => found = true
          case Term.Name("GraphRuntime") => found = true
          case Type.Name("GraphRuntime") => found = true
        }
      }
      found
    }

  // True when any code block uses `Dataset.of`, `Dataset.fromList`, etc.
  private[codegen] def blocksUseDataset(blocks: List[JvmGen.Block]): Boolean =
    val triggers = Set("Dataset", "_Dataset")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Select(Term.Name(n), _) if triggers(n) => found = true
          case Term.Apply.After_4_6_0(Term.Select(Term.Name(n), _), _) if triggers(n) => found = true
        }
      }
      found
    }

  // True when any code block invokes `route(...)`, in which case JvmGen
  // emits the serve runtime (Request/Response, registry, HTTP dispatcher).

  private[codegen] def blocksUseRoutes(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name("route"),        _) => found = true
          case Term.Apply.After_4_6_0(Term.Name("onWebSocket"),  _) => found = true
          case Term.Apply.After_4_6_0(Term.Name("wsConnect"),    _) => found = true
          // `serve(port)` on its own (no user routes) must still pull in
          // the runtime: Tier 5 #21 auto-registers `/_health` /_ready` at
          // serve() time so a bare `serve(8080)` is a valid program.
          case Term.Apply.After_4_6_0(Term.Name("serve"),        _) => found = true
          // `serveAsync(port[, tls])` is the non-blocking sibling of
          // `serve` (virtual-thread launch).  Same runtime dependency
          // — must pull `serveRuntime` in so the inlined ProxyRuntime
          // `def serveAsync` is in scope.
          case Term.Apply.After_4_6_0(Term.Name("serveAsync"),   _) => found = true
        }
      }
      found
    }

  /** True if any block references the standalone JSON / REST-validation
   *  helpers without going through `serve()`.  Pulls in `serveRuntime`
   *  — which carries `_toJson` / `_fromJson` / `_lookupKey` plus the
   *  `require*` / `validate` family — so the script compiles even when
   *  it never registers a route. */
  private[codegen] def blocksUseJson(blocks: List[JvmGen.Block]): Boolean =
    val triggers = Set(
      "jsonParse", "jsonStringify", "jsonRead",
      "lookup", "lookupOpt",
      "validate",
      "requireString",  "optionalString",
      "requireInt",     "optionalInt",
      "requireDouble",  "optionalDouble",
      "requireBool",    "optionalBool",
      "requireRange",   "requireRangeDouble",
      "requireOneOf",
    )
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _) if triggers(n) => found = true
        }
      }
      found
    }

  /** True if any block references the built-in `Async` effect — either
   *  via `runAsync(...)` or via a `Async.{delay,async,await,parallel}`
   *  call.  Used to gate registration of the four Async op names in
   *  `effectOps` (and therefore the emission of the effects runtime). */
  private[codegen] def blocksUseAsync(blocks: List[JvmGen.Block]): Boolean =
    val asyncOps = Set("delay", "async", "await", "parallel")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name("runAsync"),         _) => found = true
          case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), _) => found = true
          case Term.Select(Term.Name("Async"), Term.Name(op))
              if asyncOps(op) => found = true
        }
      }
      found
    }

  /** True if any block uses the reactive primitives `Signal(...)`,
   *  `computed { ... }`, or `effect { ... }`.  Gates emission of the
   *  reactive runtime preamble in the generated Scala script. */
  private[codegen] def blocksUseReactive(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name("Signal"),   _) => found = true
          case Term.Apply.After_4_6_0(Term.Name("computed"), _) => found = true
          case Term.Apply.After_4_6_0(Term.Name("effect"),   _) => found = true
        }
      }
      found
    }

  /** Bare-name intrinsics that route through the v1.6 actor model and
   *  must be rewritten to `Actor.<name>(...)` at emission time.  Listed
   *  once here so the analysis (`blocksUseActors`) and the rewrite-gate
   *  (`termUsesEffects`) stay in sync — a call like
   *  `val sub = subscribeClusterEvents()` needs the gate to fire so the
   *  rhs goes through `emitExpr` (which performs the rewrite).
   *
   *  Without this, top-level `val`-bound calls fall through to the
   *  scalameta verbatim printer and the unqualified `subscribeClusterEvents`
   *  is unresolved at scala-cli compile time (the runtime exposes it
   *  as `Actor.subscribeClusterEvents`). */
  private[codegen] val actorBareNames: Set[String] =
    Set("runActors", "spawn", "spawn_link", "spawnBounded", "self", "exit", "receive",
        "link", "monitor", "demonitor", "trapExit",
        "startNode", "connectNode", "joinCluster", "register", "whereis",
        "globalRegister", "globalWhereis",
        "clusterMembers", "subscribeClusterEvents",
        "phiOf", "isSuspect", "selfNode", "clusterHealth",
        "broadcastHealth", "clusterIsDown",
        "electLeader", "currentLeader", "subscribeLeaderEvents",
        "setAutoReelect",
        "useRaftLeaderElection", "useExternalCoordinator", "leaderProtocol",
        "leaderHistory",
        "setReconnectPolicy", "setHeartbeatTimeout", "setQuorumSize",
        "setClusterAuthToken", "requestGossip",
        "clusterConfigSet", "clusterConfigGet", "clusterConfigKeys",
        "subscribeConfigEvents",
        "setDraining", "isDraining", "drainingPeers", "subscribeDrainEvents",
        "clusterMetricSet", "clusterMetricGet", "clusterMetricSum",
        "clusterMetricNames", "subscribeMetricEvents",
        "sendAfter", "sendInterval", "cancelTimer", "processInfo")

  /** First-segment names of SSC dep modules whose types are accessed via
   *  `_dispatch` at runtime rather than as real Scala classes.  An `import`
   *  statement in a user code block that starts with one of these names is
   *  dropped by `emitStat` (the `actors` module is the canonical example:
   *  `import actors.ProcessInfo` / `import actors.Overflow`). */
  private[codegen] val sscDepModulePrefixes: Set[String] = Set("actors")

  /** Case-class names defined inside `effectsRuntime` whose presence in
   *  patterns or expressions should also pull in the runtime.  Without
   *  this, a module that does e.g.
   *  ```
   *  def describe(e: Any) = e match { case NodeJoined(id) => ... }
   *  ```
   *  would compile-error with "no pattern match extractor named NodeJoined". */
  private[codegen] val actorRuntimeCaseClasses: Set[String] =
    Set("NodeJoined", "NodeLeft", "LeaderElected", "LeaderLost",
        "ConfigChanged", "DrainStateChanged", "MetricChanged",
        "Exit", "Down")

  /** True if any block references the v1.6 actor model — via
   *  `runActors`, `spawn`, `self`, `exit`, `receive`, `link`, `monitor`,
   *  `demonitor`, `trapExit`, Phase 3 distributed primitives, or a
   *  pattern/expression that mentions one of the actor-runtime case
   *  classes (`NodeJoined` / `NodeLeft` / `Exit` / `Down`). */
  private[codegen] def blocksUseActors(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _) if actorBareNames(n) => found = true
          // Pattern match: `case NodeJoined(id) => ...` lowers to
          // `Pat.Extract(Term.Name("NodeJoined"), ...)`.  Detect those.
          case scala.meta.Pat.Extract.After_4_6_0(Term.Name(n), _) if actorRuntimeCaseClasses(n) => found = true
          // Bare expression reference (companion or constructor):
          // `NodeJoined`, `Exit(pid, "stop")` — covers both `Term.Name`
          // (apply target) and lone references.
          case Term.Name(n) if actorRuntimeCaseClasses(n) => found = true
        }
      }
      found
    }

  /** True if any block references the built-in `Storage` effect —
   *  either via `runStorage` / `runEphemeralStorage` or a
   *  `Storage.{get,put,remove,has,keys}` call. */
  private[codegen] def blocksUseStorage(blocks: List[JvmGen.Block]): Boolean =
    val storageOps = Set("get", "put", "remove", "has", "keys")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name("runStorage"),          _) => found = true
          case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), _) => found = true
          case Term.Select(Term.Name("Storage"), Term.Name(op))
              if storageOps(op) => found = true
        }
      }
      found
    }

  private[codegen] def blocksUseLogger(blocks: List[JvmGen.Block]): Boolean =
    val ops = Set("info", "warn", "error", "debug")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _)
              if Set("runLogger", "runLoggerJson", "runLoggerToList")(n) => found = true
          case Term.Select(Term.Name("Logger"), Term.Name(op)) if ops(op) => found = true
        }
      }
      found
    }

  private[codegen] def blocksUseRandom(blocks: List[JvmGen.Block]): Boolean =
    val ops = Set("nextInt", "nextDouble", "uuid", "pick")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _)
              if Set("runRandom", "runRandomSeeded")(n) => found = true
          case Term.Select(Term.Name("Random"), Term.Name(op)) if ops(op) => found = true
        }
      }
      found
    }

  private[codegen] def blocksUseClock(blocks: List[JvmGen.Block]): Boolean =
    val ops = Set("now", "nowIso", "sleep")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _)
              if Set("runClock", "runClockAt")(n) => found = true
          case Term.Select(Term.Name("Clock"), Term.Name(op)) if ops(op) => found = true
        }
      }
      found
    }

  private[codegen] def blocksUseEnv(blocks: List[JvmGen.Block]): Boolean =
    val ops = Set("get", "set", "required")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _)
              if Set("runEnv", "runEnvWith")(n) => found = true
          case Term.Select(Term.Name("Env"), Term.Name(op)) if ops(op) => found = true
        }
      }
      found
    }

  private[codegen] def blocksUseHttp(blocks: List[JvmGen.Block]): Boolean =
    val ops = Set("get", "post", "request")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _)
              if Set("runHttp", "runHttpStub")(n) => found = true
          case Term.Select(Term.Name("Http"), Term.Name(op)) if ops(op) => found = true
        }
      }
      found
    }

  private[codegen] def blocksUseRetry(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _)
              if Set("runRetry", "runRetryNoSleep")(n) => found = true
          case Term.Select(Term.Name("Retry"), Term.Name("attempt")) => found = true
        }
      }
      found
    }

  private[codegen] def blocksUseCache(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(n), _)
              if Set("runCache", "runCacheBypass")(n) => found = true
          case Term.Select(Term.Name("Cache"), Term.Name("memoize")) => found = true
        }
      }
      found
    }

  private[codegen] def blocksUseState(blocks: List[JvmGen.Block]): Boolean =
    val ops = Set("get", "set", "modify")
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name("runState"), _) => found = true
          case Term.Select(Term.Name("State"), Term.Name(op)) if ops(op) => found = true
        }
      }
      found
    }

  private[codegen] def blocksUseTx(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name("runTx"), _) => found = true
          case Term.Select(Term.Name("Tx"), Term.Name("atomic")) => found = true
        }
      }
      found
    }

  private[codegen] def blocksUseAuth(blocks: List[JvmGen.Block]): Boolean =
    blocks.exists { b =>
      var found = false
      ScalaNode.fold(b.node) { tree =>
        if !found then tree.collect {
          case Term.Apply.After_4_6_0(Term.Name("runAuthWith"), _) => found = true
          case Term.Select(Term.Name("Auth"), Term.Name(_)) => found = true
        }
      }
      found
    }
