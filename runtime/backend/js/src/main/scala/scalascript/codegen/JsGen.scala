package scalascript.codegen

import scalascript.ast.*
import scalascript.transform.{DirectAnorm, DirectTypeUtils, EffectAnalysis}
import scalascript.typeddata.TypedJsonCodecRuntime
import scala.collection.mutable
// jsgen-split-p1: pure helpers extracted out of this file.
import JsGenStringUtils.*

/** JavaScript code generator for ScalaScript modules.
 *
 *  Walks the same Module type the interpreter uses, generating JS for each
 *  Scala code block. The generated JS can be embedded in HTML and run in-browser.
 */
object JsGen:

  enum Segment:
    case ScalaScriptJs(code: String)
    case ScalaSource(source: String)

  /** Statistics from a tree-shaking pass.  Returned by [[generateWithStats]]
   *  and printed to stderr by the CLI when `--stats` is active.
   *
   *  @param kept   number of top-level declarations retained in the output
   *  @param total  total number of top-level declarations before shaking
   */
  case class TreeShakeStats(kept: Int, total: Int):
    def pruned: Int = total - kept
    /** One-line human-readable summary, e.g. "Tree-shake: kept 42 / 78 symbols (removed 36, -46%)" */
    def summary: String =
      val pct = if total == 0 then 0 else (pruned * 100) / total
      s"Tree-shake: kept $kept / $total symbols (removed $pruned, -$pct%)"

  /** Generate JS source with tree-shaking (default) or without (`noTreeShake = true`).
   *  Returns both the generated code and the shaking statistics.
   *
   *  Use [[generate]] when you only need the code and don't need stats. */
  def generateWithStats(
      module:      Module,
      baseDir:     Option[os.Path] = None,
      intrinsics:  Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:    Option[os.Path] = None,
      noTreeShake: Boolean = false
  ): (String, Option[TreeShakeStats]) =
    val shakeResult =
      if noTreeShake then None
      else Some(TreeShaker.shake(module))
    val reachable = shakeResult.map(_.reachable)
    val gen  = new JsGen(baseDir, intrinsics, lockPath, reachableNames = reachable)
    val code = gen.genModule(module)
    val stats = shakeResult.map(r => TreeShakeStats(kept = r.kept, total = r.total))
    (code, stats)

  /** Generate JS source for all scalascript code blocks in a module.
   *  Tree-shaking is OFF by default here to preserve the existing API behaviour;
   *  use [[generateWithStats]] to enable it. */
  def generate(
      module:     Module,
      baseDir:    Option[os.Path] = None,
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:   Option[os.Path] = None
  ): String =
    val gen = new JsGen(baseDir, intrinsics, lockPath)
    gen.genModule(module)

  /** Generate segments in document order, preserving scala/scalascript interleaving.
   *  Tree-shaking is OFF by default to preserve the existing API behaviour.
   *  Pass `noTreeShake = false` explicitly (or use [[generateWithStats]]) to enable it. */
  def generateSegmented(
      module:      Module,
      baseDir:     Option[os.Path] = None,
      intrinsics:  Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:    Option[os.Path] = None,
      noTreeShake: Boolean = true
  ): List[Segment] =
    val shakeResult =
      if noTreeShake then None
      else Some(TreeShaker.shake(module))
    val reachable = shakeResult.map(_.reachable)
    val gen = new JsGen(baseDir, intrinsics, lockPath, reachableNames = reachable)
    gen.genModuleSegmented(module)

  /** True if the module contains at least one scalascript block. */
  def hasBlocks(module: Module): Boolean =
    module.sections.exists(sectionHas)

  private def sectionHas(s: Section): Boolean =
    s.content.exists {
      case cb: Content.CodeBlock => Lang.isScalaScript(cb.lang)
      case _                     => false
    } || s.subsections.exists(sectionHas)

  // ─── v2.0 Phase 2 — split-runtime emit (JS) ────────────────────────────
  //
  // Mirrors `JvmGen.{generateRuntime, generateUserOnly, detectCapabilities}`.
  //
  // The legacy `generate(module)` returns user code only (no preamble).  The
  // CLI's `buildScjsSource` historically prepended the full runtime preamble
  // before persisting to `.scjs`, so every module artifact carried ~80 KB
  // of duplicate runtime JS.  Phase 2 factors the preamble into a separate
  // `_runtime.scjs-runtime` artifact compiled once per artifact dir and
  // textually concatenated before each module's user code at link time.
  //
  // For JS the equivalent of JVM's `package _ssc_runtime` is just textual
  // concatenation: classic-script JS has a single global namespace, so
  // emitting `function _show(...) { … }` at the top of `out.js` makes the
  // symbol reachable from every later module without imports or namespacing.
  // An IIFE wrapper (`const _ssc_runtime = (function(){ … return { … }; })();`)
  // was considered but rejected — the runtime exports 200+ identifiers and
  // wrapping/destructuring each one is fragile (typed-tag DSL, `Console`,
  // `attr`, `Async`, `Logger`, `Random`, etc. would all need explicit
  // re-exports).  Flat-scope concatenation keeps the runtime body verbatim.

  /** Identifier for a runtime capability that the user module depends on.
   *  Drives the `generateRuntime` capability switch and determines which
   *  helper blocks (effects, async, mcp, dataset) are emitted. */
  sealed trait Capability
  object Capability:
    /** Core runtime: `_show`, `_println`, `_tupleConcat`, `_dispatch`, given
     *  registry, JSON, Free Monad, fs helpers.  Always present. */
    case object Core      extends Capability
    case object Async     extends Capability  // `JsRuntimeAsync` — Async + Actor + Storage
    case object Effects   extends Capability  // `JsRuntimeV14Effects` — Logger/Random/Clock/Env/Auth
    case object Mcp       extends Capability  // `JsRuntimeMcp` — MCP server / client
    case object Dataset   extends Capability  // `JsRuntimeDataset` — Dataset[T] lazy pipeline
    case object Payment   extends Capability  // `JsRuntimePayment` — Payment Request API
    case object HtmlDsl   extends Capability  // `JsRuntimePart1b` — HTTP serve/route/sessions/metrics
    case object Jwt       extends Capability  // `JsRuntimePart1c` — JWT/OAuth2/CSRF
    case object WsServer  extends Capability  // `JsRuntimePart1d` — WebSocket/SSE/CORS
    case object Optics    extends Capability  // `JsRuntimeOptics` — Lens/Optional/Traversal/Prism
    case object Signals   extends Capability  // `JsRuntimeSignals` — reactive signals
    case object IndexedDb extends Capability  // `JsRuntimeIndexedDb` — client-side storage
    case object Graphql   extends Capability  // `JsRuntimeGraphql` — GraphQL server + client

    val all: Set[Capability] = Set(Core, Async, Effects, Mcp, Dataset, Payment,
                                   HtmlDsl, Jwt, WsServer, Optics, Signals, IndexedDb,
                                   Graphql)

    /** Encode a capability as a stable, persistence-safe string.
     *  These strings appear in `.scjs-runtime` envelopes — do not rename. */
    def encode(c: Capability): String = c match
      case Core      => "core"
      case Async     => "async"
      case Effects   => "effects"
      case Mcp       => "mcp"
      case Dataset   => "dataset"
      case Payment   => "payment"
      case HtmlDsl   => "htmldsl"
      case Jwt       => "jwt"
      case WsServer  => "wsserver"
      case Optics    => "optics"
      case Signals   => "signals"
      case IndexedDb => "indexeddb"
      case Graphql   => "graphql"

    def decode(s: String): Option[Capability] = s match
      case "core"      => Some(Core)
      case "async"     => Some(Async)
      case "effects"   => Some(Effects)
      case "mcp"       => Some(Mcp)
      case "dataset"   => Some(Dataset)
      case "payment"   => Some(Payment)
      case "htmldsl"   => Some(HtmlDsl)
      case "jwt"       => Some(Jwt)
      case "wsserver"  => Some(WsServer)
      case "optics"    => Some(Optics)
      case "signals"   => Some(Signals)
      case "indexeddb" => Some(IndexedDb)
      case "graphql"   => Some(Graphql)
      case _           => None

  /** Inspect `module` and return the capability set its emitted JS would
   *  depend on.  `Core` is always included.  Other capabilities are
   *  toggled by scanning the parsed scalascript blocks for references
   *  to the corresponding effect / DSL names (`Async.*`, `Actor.*`,
   *  `Logger.*` / `Random.*` / `Clock.*` / `Env.*`, `mcpServer` /
   *  `serveMcp`, `Dataset.*`). */
  def detectCapabilities(
      module:     Module,
      baseDir:    Option[os.Path] = None,
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:   Option[os.Path] = None
  ): Set[Capability] =
    new JsGen(baseDir, intrinsics, lockPath).detectCapabilities(module)

  /** Emit the runtime preamble for the given capability set.  No user code
   *  is included.  Capability `Core` is always added regardless of input —
   *  the per-module emit relies on `_show` / `_println` / HTML DSL helpers
   *  that live in the core block.
   *
   *  v2.0 Phase 2 — split-runtime emit. */
  def generateRuntime(capabilities: Set[Capability]): String =
    val caps = capabilities + Capability.Core
    val sb   = new StringBuilder
    sb.append("// ── scalascript JS runtime ──────────────────────────────────────────\n")
    // Inject js/glue.js preambles from loaded .ssclib archives (Phase 4 FFI).
    val gluePreambles = scalascript.imports.GlueJsPreambleRegistry.preambles
    if gluePreambles.nonEmpty then
      sb.append("// ── glue preambles ─────────────────────────────────────────────────\n")
      gluePreambles.foreach { glue =>
        sb.append(glue)
        if !glue.endsWith("\n") then sb.append('\n')
      }
    // v1.61.6: build from individual parts so unused sections are omitted.
    // Part1a is always included (Core).
    sb.append(JsRuntimePart1a)
    if !JsRuntimePart1a.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.HtmlDsl) then
      sb.append(JsRuntimePart1b)
      if !JsRuntimePart1b.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Jwt) then
      sb.append(JsRuntimePart1c)
      if !JsRuntimePart1c.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.WsServer) then
      sb.append(JsRuntimePart1d)
      if !JsRuntimePart1d.endsWith("\n") then sb.append('\n')
    // Part2a and Part2b are always included (Core dispatch, _show, _tupleConcat, Free Monad, fs).
    sb.append(JsRuntimePart2a)
    if !JsRuntimePart2a.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Optics) then
      sb.append(JsRuntimeOptics)
      if !JsRuntimeOptics.endsWith("\n") then sb.append('\n')
    sb.append(JsRuntimePart2b)
    if !JsRuntimePart2b.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Signals) then
      sb.append(JsRuntimeSignals)
      if !JsRuntimeSignals.endsWith("\n") then sb.append('\n')
    sb.append(TypedJsonCodecRuntime.jsFacade)
    if !TypedJsonCodecRuntime.jsFacade.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.IndexedDb) then
      sb.append(JsRuntimeIndexedDb)
      if !JsRuntimeIndexedDb.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Async) then
      sb.append(JsRuntimeAsync)
      if !JsRuntimeAsync.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Effects) then
      sb.append(JsRuntimeV14Effects)
      if !JsRuntimeV14Effects.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Mcp) then
      sb.append(JsRuntimeMcp)
      if !JsRuntimeMcp.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Dataset) then
      sb.append(JsRuntimeDataset)
      if !JsRuntimeDataset.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Payment) then
      sb.append(JsRuntimePayment)
      if !JsRuntimePayment.endsWith("\n") then sb.append('\n')
    // GraphQL runtime references `route` (Part1b) / `serve` (Part1d) /
    // `_mkResp` / `_jsonConvert` (defined above), so it is appended last.
    // Capability detection forces HtmlDsl + WsServer + Async whenever Graphql
    // is present, so those parts are emitted.
    if caps.contains(Capability.Graphql) then
      sb.append(JsRuntimeGraphql)
      if !JsRuntimeGraphql.endsWith("\n") then sb.append('\n')
    sb.toString

  /** Emit user code only — no runtime preamble.  In JS this is a synonym
   *  for [[generate]] today (`genModule` never prepended the preamble; the
   *  preamble was concatenated by the CLI's `buildScjsSource`).  The alias
   *  exists so call sites read symmetrically with the JVM split-runtime
   *  emit (`JvmGen.generateUserOnly`).
   *
   *  Tree-shaking is OFF by default to preserve the existing API behaviour.
   *  The CLI passes `noTreeShake = false` to enable dead-code elimination
   *  for artifact builds.
   *
   *  v2.0 Phase 2 — split-runtime emit. */
  def generateUserOnly(
      module:      Module,
      baseDir:     Option[os.Path] = None,
      intrinsics:  Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:    Option[os.Path] = None,
      noTreeShake: Boolean = true
  ): String =
    generateWithStats(module, baseDir, intrinsics, lockPath, noTreeShake)._1










val JsRuntime: String =
  JsRuntimePart1a + JsRuntimePart1b + JsRuntimePart1c + JsRuntimePart1d +
  JsRuntimePart2a + JsRuntimeOptics + JsRuntimePart2b + JsRuntimeSignals +
  TypedJsonCodecRuntime.jsFacade + JsRuntimeIndexedDb

/** Built-in `Async` effect runtime — concatenated onto `JsRuntime`.
 *  Lives in its own val because together with the rest of the runtime
 *  it overflows the JVM's 65 535-byte string-literal limit.  Same
 *  semantics as the interpreter and JvmGen: `delay` blocks via
 *  Atomics on Node, thunks passed to `async` / `parallel` run
 *  synchronously, results come back in declared order.  Itself split
 *  into two halves to stay under that 65 KiB string-literal cap. */
lazy val JsRuntimeAsync: String = JsRuntimeAsyncA + JsRuntimeAsyncB

private val JsRuntimeAsyncA: String = """

// ── Async — built-in effect on top of the Free Monad ───────────────────────
//
// `Async.delay(ms)`, `Async.async(thunk)`, `Async.await(fut)`, and
// `Async.parallel(thunks)` produce `_Perform("Async", op, args)` nodes,
// indistinguishable from user-declared effect ops.  `_runAsync(bodyFn)`
// is the default handler — single-threaded so output is deterministic
// and byte-identical to the interpreter and JVM backends.  On Node
// `delay(ms)` blocks via `Atomics.wait` on a SharedArrayBuffer flag (no
// child process, no async coloring of caller code).  In the browser
// (no SharedArrayBuffer without crossOriginIsolation) we fall back to
// `Date.now()` spin, which is fine for the small delays used in demos.
const Async = {
  delay:    (ms)     => _perform('Async', 'delay',    [ms]),
  async:    (thunk)  => _perform('Async', 'async',    [thunk]),
  await:    (fut)    => _perform('Async', 'await',    [fut]),
  parallel: (thunks) => _perform('Async', 'parallel', [thunks]),
  recvFrom: (ws)     => _perform('Async', 'recvFrom', [ws]),
};
function Future(value) { return { _type: 'Future', value }; }

function _asyncSleep(ms) {
  if (!(ms > 0)) return;
  if (typeof SharedArrayBuffer !== 'undefined' && typeof Atomics !== 'undefined') {
    try {
      const sab = new SharedArrayBuffer(4);
      Atomics.wait(new Int32Array(sab), 0, 0, ms);
      return;
    } catch (_) { /* not allowed in main thread of some envs — fall through */ }
  }
  // Last-resort spin (browser without isolation, etc.).  Tight but
  // accurate enough for the small ms values typical in demos.
  const end = Date.now() + ms;
  while (Date.now() < end) { /* spin */ }
}

function _asyncDispatch(op, args, resume) {
  switch (op) {
    case 'delay': {
      const ms = args[0];
      if (typeof ms !== 'number') throw new Error('Async.delay(ms: Int)');
      _asyncSleep(ms);
      return resume(undefined);
    }
    case 'async': {
      const thunk = args[0];
      if (typeof thunk !== 'function') throw new Error('Async.async(thunk)');
      const v = _runAsyncInner(thunk());
      return resume(Future(v));
    }
    case 'await': {
      const fut = args[0];
      if (!fut || fut._type !== 'Future') throw new Error('Async.await(future)');
      return resume(fut.value);
    }
    case 'parallel': {
      const thunks = args[0];
      if (!Array.isArray(thunks)) throw new Error('Async.parallel(thunks: List[() => A])');
      const out = [];
      for (const t of thunks) {
        if (typeof t !== 'function') throw new Error('Async.parallel(thunks: List[() => A])');
        out.push(_runAsyncInner(t()));
      }
      return resume(out);
    }
    default:
      throw new Error('Unknown Async operation: ' + op);
  }
}

// Drive a Computation to a plain value, dispatching `Async.*` ops along
// the way.  Non-Async Performs propagate outward — useful for nested
// handlers (`runAsync` inside `handle`, or vice versa).
function _runAsyncInner(initial) {
  let current = initial;
  while (true) {
    if (current instanceof _Perform) {
      if (current.eff === 'Async') {
        current = _asyncDispatch(current.op, current.args, (v) => v);
      } else {
        return current;  // propagate
      }
    } else if (current instanceof _FlatMap) {
      const sub = current.sub;
      if (sub instanceof _FlatMap) {
        const sub2 = sub.sub, g = sub.k, f = current.k;
        current = new _FlatMap(sub2, (x) => new _FlatMap(g(x), f));
      } else if (sub instanceof _Perform) {
        const f = current.k;
        if (sub.eff === 'Async') {
          current = _asyncDispatch(sub.op, sub.args, (v) => _runAsyncInner(f(v)));
        } else {
          return new _FlatMap(sub, (v) => _runAsyncInner(f(v)));
        }
      } else {
        current = current.k(sub);
      }
    } else {
      return current;  // plain value
    }
  }
}

function _runAsync(bodyFn) { return _runAsyncInner(bodyFn()); }

// ── runAsyncParallel: true I/O concurrency on Node via Promises ────────────
//
// Each `Async.async(thunk)` launches an independent Promise-driven run of
// `thunk`, returning a handle that `Async.await` can resolve.  `parallel`
// fans out all thunks concurrently with `Promise.all`.  `recvFrom(ws)`
// delegates to `ws._nextMessage()` — a Promise that resolves on the next
// incoming WebSocket frame, or `None` on close.  The outer `async function`
// keeps the Node.js event loop live while awaiting I/O.
async function _runAsyncParallelInner(node) {
  while (true) {
    // Right-associate nested _FlatMap nodes to avoid stack growth.
    while (node instanceof _FlatMap && node.sub instanceof _FlatMap) {
      const sub2 = node.sub.sub, g = node.sub.k, f = node.k;
      node = new _FlatMap(sub2, (x) => new _FlatMap(g(x), f));
    }
    if (node instanceof _FlatMap) {
      const result = await _runAsyncParallelInner(node.sub);
      node = node.k(result);
    } else if (node instanceof _Perform && node.eff === 'Async') {
      switch (node.op) {
        case 'delay': {
          const ms = node.args[0];
          if (ms > 0) await new Promise(r => setTimeout(r, ms));
          node = undefined;
          break;
        }
        case 'async': {
          const thunk = node.args[0];
          const p = _runAsyncParallelInner(thunk());
          return { _type: 'Future', _isParFut: true, _promise: p };
        }
        case 'await': {
          const fut = node.args[0];
          if (!fut || !fut._isParFut) throw new Error('Async.await: expected parallel Future');
          return await fut._promise;
        }
        case 'parallel': {
          const thunks = node.args[0];
          return await Promise.all(thunks.map(t => _runAsyncParallelInner(t())));
        }
        case 'recvFrom': {
          const ws = node.args[0];
          const nextMsg = typeof ws._nextMessage === 'function' ? ws._nextMessage
                        : (ws instanceof Map ? ws.get('_nextMessage') : null);
          if (typeof nextMsg !== 'function') throw new Error('Async.recvFrom: ws has no _nextMessage');
          node = await nextMsg();
          break;
        }
        default:
          throw new Error('Unknown Async operation in runAsyncParallel: ' + node.op);
      }
    } else {
      return node;  // plain value
    }
  }
}
async function _runAsyncParallel(bodyFn) {
  try { return await _runAsyncParallelInner(bodyFn()); }
  catch (e) {
    if (typeof process !== 'undefined') process.stderr.write('runAsyncParallel error: ' + (e && e.message || String(e)) + '\n');
    throw e;
  }
}

// ── v1.6 Actors — Phase 1 cooperative scheduler ────────────────────────
//
// Mirrors the interpreter's `actorInterp` / `handleActorOp`.  Each
// `_runActors(bodyFn)` invocation creates a fresh actor registry,
// spawns `bodyFn` as the root actor, and drives all spawned actors
// cooperatively until quiescence.  Mailboxes are plain JS arrays
// (v1.9.x: JS is single-threaded so LinkedBlockingQueue N/A; array
// IS the cooperative-mailbox equivalent for this backend); the
// scheduler is a simple round-robin ready queue.  `receive` with a
// non-empty matching head returns the case body's value; with an
// empty mailbox it suspends.  `receive(timeout = N)` arms a deadline;
// when the ready queue empties and no other progress is possible the
// scheduler sleeps until the earliest deadline, then resumes that
// actor with None.

function Pid(nodeId, localId) { return { _type: 'Pid', nodeId, localId }; }

const Actor = {
  spawn:      (thunk)      => _perform('Actor', 'spawn',      [thunk]),
  spawn_link: (thunk)      => _perform('Actor', 'spawnLink',  [thunk]),
  self:       ()           => _perform('Actor', 'self',       []),
  exit:      (pid, reason) => _perform('Actor', 'exit',      [pid, reason]),
  send:      (pid, msg)    => _perform('Actor', 'send',      [pid, msg]),
  receive_:  (specId)              => _perform('Actor', 'receive',   [specId]),
  receive_t: (specId, timeoutMs)   => _perform('Actor', 'receive_t', [specId, timeoutMs]),
  // v1.6 Phase 2 — supervision
  link:      (pid)         => _perform('Actor', 'link',      [pid]),
  monitor:   (pid)         => _perform('Actor', 'monitor',   [pid]),
  demonitor: (ref)         => _perform('Actor', 'demonitor', [ref]),
  trapExit:  (b)           => _perform('Actor', 'trapExit',  [b]),
  // v1.6 Phase 3 — distributed nodes
  startNode:   (nodeId, url)  => _perform('Actor', 'startNode',   [nodeId, url || '']),
  connectNode: (url, token)   => _perform('Actor', 'connectNode', [url, token]),
  register:    (name, pid)    => _perform('Actor', 'register',    [name, pid]),
  whereis:     (name)         => _perform('Actor', 'whereis',     [name]),
  // v1.6.x — cluster discovery
  joinCluster:     (seeds, token) => _perform('Actor', 'joinCluster',     [seeds, token || '']),
  // v1.6.x — cluster-wide registry
  globalRegister:  (name, pid)   => _perform('Actor', 'globalRegister',  [name, pid]),
  globalWhereis:   (name)        => _perform('Actor', 'globalWhereis',   [name]),
  // v1.6.x — scheduled sends
  sendAfter:   (delayMs, pid, msg)  => _perform('Actor', 'sendAfter',   [delayMs, pid, msg]),
  sendInterval:(periodMs, pid, msg) => _perform('Actor', 'sendInterval',[periodMs, pid, msg]),
  cancelTimer: (ref)                => _perform('Actor', 'cancelTimer', [ref]),
  // v1.6.x — bounded mailbox spawn
  spawnBounded:(cap, overflow, thunk) => _perform('Actor', 'spawnBounded', [cap, overflow, thunk]),
  // v1.6.x — process introspection
  processInfo: (pid) => _perform('Actor', 'processInfo', [pid]),
  // v1.23 — cluster visibility
  clusterMembers:         ()  => _perform('Actor', 'clusterMembers',         []),
  subscribeClusterEvents: ()  => _perform('Actor', 'subscribeClusterEvents', []),
  // v1.23 — phi-accrual failure detector
  phiOf:     (nid)              => _perform('Actor', 'phiOf',     [nid]),
  isSuspect: (nid, thr)         => _perform('Actor', 'isSuspect', [nid, thr == null ? 8.0 : thr]),
  // v1.23 — local node identity + phi vector
  selfNode:      ()             => _perform('Actor', 'selfNode',      []),
  clusterHealth: ()             => _perform('Actor', 'clusterHealth', []),
  // v1.23 — cluster-wide failure detector
  broadcastHealth: ()           => _perform('Actor', 'broadcastHealth', []),
  clusterIsDown:   (nid, thr)   => _perform('Actor', 'clusterIsDown',
                                            [nid, thr == null ? 8.0 : thr]),
  // v1.23 — leader election (Bully)
  electLeader:           ()  => _perform('Actor', 'electLeader',           []),
  currentLeader:         ()  => _perform('Actor', 'currentLeader',         []),
  subscribeLeaderEvents: ()  => _perform('Actor', 'subscribeLeaderEvents', []),
  setAutoReelect:        (b) => _perform('Actor', 'setAutoReelect', [b]),
  // v1.23 — protocol switch + history (cluster-raft.md §6)
  useRaftLeaderElection: ()  => _perform('Actor', 'useRaftLeaderElection', []),
  useExternalCoordinator:(acq, ren, rel, hol) =>
                              _perform('Actor', 'useExternalCoordinator', [acq, ren, rel, hol]),
  leaderProtocol:        ()  => _perform('Actor', 'leaderProtocol', []),
  leaderHistory:         ()  => _perform('Actor', 'leaderHistory', []),
  // v1.23 — auto-reconnect policy (2- or 3-arg form)
  setReconnectPolicy: function() {
    const args = Array.prototype.slice.call(arguments);
    return _perform('Actor', 'setReconnectPolicy', args);
  },
  // v1.23 — per-link heartbeat tuning
  setHeartbeatTimeout: (iv, dead) => _perform('Actor', 'setHeartbeatTimeout', [iv, dead]),
  // v1.23 — quorum-aware Bully threshold (split-brain guard)
  setQuorumSize: (n) => _perform('Actor', 'setQuorumSize', [n]),
  // v1.23 — shared-secret Bearer token for /_ssc-cluster/* endpoints.
  setClusterAuthToken: (t) => _perform('Actor', 'setClusterAuthToken', [t]),
  // v1.23 — periodic gossip re-discovery
  requestGossip: () => _perform('Actor', 'requestGossip', []),
  // v1.23 — cluster configuration distribution
  clusterConfigSet:      (key, value) => _perform('Actor', 'clusterConfigSet',      [key, value]),
  clusterConfigGet:      (key)        => _perform('Actor', 'clusterConfigGet',      [key]),
  clusterConfigKeys:     ()           => _perform('Actor', 'clusterConfigKeys',     []),
  subscribeConfigEvents: ()           => _perform('Actor', 'subscribeConfigEvents', []),
  // v1.23 — drain / rolling-restart
  setDraining:           (b)          => _perform('Actor', 'setDraining',           [b]),
  isDraining:            ()           => _perform('Actor', 'isDraining',            []),
  drainingPeers:         ()           => _perform('Actor', 'drainingPeers',         []),
  subscribeDrainEvents:  ()           => _perform('Actor', 'subscribeDrainEvents',  []),
  // v1.23 — cluster metrics aggregation
  clusterMetricSet:      (name, value) => _perform('Actor', 'clusterMetricSet',     [name, value]),
  clusterMetricGet:      (name)        => _perform('Actor', 'clusterMetricGet',     [name]),
  clusterMetricSum:      (name)        => _perform('Actor', 'clusterMetricSum',     [name]),
  clusterMetricNames:    ()            => _perform('Actor', 'clusterMetricNames',   []),
  subscribeMetricEvents: ()            => _perform('Actor', 'subscribeMetricEvents',[]),
};

// `receive { case … }` lowers to a registered matcher function whose
// integer token is passed to Actor.receive_/receive_t.  Codegen emits
// a fresh closure per `receive` site that returns either { matched:
// false } or { matched: true, body: () => <case-body Computation> }.
let _receiveSpecNext = 0;
const _receiveSpecs = new Map();
function _registerReceive(matcher) {
  const id = _receiveSpecNext++;
  _receiveSpecs.set(id, matcher);
  return id;
}

async function _runActors(bodyFn) {
  // id -> { mailbox: [], pending: _Computation | null,
  //         blocked: { matcher, k, deadline, wrapSome } | null }
  // (async) The scheduler is an async function so it can `await
  // setImmediate`/`setTimeout` between ticks — that's what lets Node's
  // event loop dispatch the HTTP/WS requests libuv `accept`ed on the
  // server bound by a `serveAsync(...)` call from inside the actor body.
  // Previously the scheduler was a synchronous `while (true)` loop with
  // `Atomics.wait(...)` waits — fast but it monopolised the main thread,
  // so any actor blocking on a long-armed `receive` left every HTTP
  // route permanently unreachable until the next mailbox delivery.
  const actors     = new Map();
  // Phase 2 supervision state (per _runActors invocation)
  const links      = new Map();   // id -> Set<id>
  const monitors   = new Map();   // watchedId -> Map<monRef, observerId>
  const trapExitMap = new Map();  // id -> bool
  let   nextMonRef = 0;
  // v1.6.x scheduled sends — timerId -> { fireAt, periodMs, targetId, msg }
  const _timers    = new Map();
  let   _nextTimerId = 0;
  // Phase 3 distributed node state
  let   _localNodeId  = "";
  const _nodeRegistry   = new Map();  // name -> localId
  const _globalRegistry = new Map();  // cluster-wide name -> Pid
  // Node-down tracking — drained by the scheduler loop.
  const _nodeDownQueue   = [];
  const _remoteLinks     = new Map();  // nodeId -> [[localActorId, remotePid], ...]
  const _remoteMonitors  = new Map();  // nodeId -> [[monRef, localActorId, remoteLocalId], ...]
  // Phase 3: outbound peer channels (nodeId -> { worker, send })
  const _peerChannels = new Map();
  // Inbound messages from peer workers (raw JSON strings), drained each tick.
  const _remoteRawInbox = [];
  // Ring buffer constants (shared with workers via workerData).
  const _RING_SLOTS = 64, _RING_SLOT_BYTES = 2048, _RING_HDR = 8;
  // v1.6.x cluster discovery state
  let _selfUrl  = '';          // set by startNode(nodeId, url)
  let _joinMode = false;       // true once joinCluster has been called
  let _joinToken = '';         // token for auto-connect to gossip'd peers
  const _peerUrls = new Map(); // nodeId -> url (populated from connectNode + peers_resp)
  // v1.23 — cluster visibility
  const _clusterEventSubs  = new Set();   // actor ids subscribed to NodeJoined/NodeLeft
  const _clusterEventQueue = [];          // {tag, nodeId, reason} pending delivery
  // v1.23 — bounded ring buffer of every cluster event as JSON lines.
  // Independent of the in-process subscription system: events land here
  // whether or not any actor has called subscribe*Events, so the
  // `GET /_ssc-cluster/events` endpoint always has data for ops tooling.
  // Mirrors `Interpreter.clusterEventLog` (cap 200, oldest-first drop).
  const _CLUSTER_EVENT_LOG_MAX = 200;
  const _clusterEventLog       = []; // JSON-string entries
  function _recordEventLog(jsonObj) {
    _clusterEventLog.push(jsonObj);
    while (_clusterEventLog.length > _CLUSTER_EVENT_LOG_MAX) _clusterEventLog.shift();
  }
  function _fireClusterEvent(tag, nodeId, reason) {
    // Mirror into the ops event log regardless of in-process subscribers.
    const ts = Date.now();
    const logEntry = (tag === 'NodeJoined')
      ? '{"ts":' + ts + ',"type":"NodeJoined","nodeId":' + JSON.stringify(nodeId) + '}'
      : '{"ts":' + ts + ',"type":"NodeLeft","nodeId":' + JSON.stringify(nodeId) +
        ',"reason":' + JSON.stringify(reason || '') + '}';
    _recordEventLog(logEntry);
    if (_clusterEventSubs.size === 0) return;
    _clusterEventQueue.push({ tag, nodeId, reason: reason || '' });
  }
  // v1.23 — shared-secret Bearer token for /_ssc-cluster/* endpoints.
  // Default reads SSC_CLUSTER_TOKEN env at runtime; runtime override via
  // the setClusterAuthToken intrinsic.  Empty string ⇒ endpoints are open
  // (backwards compatible).  Mirrors `Interpreter.clusterAuthToken`.
  let _clusterAuthToken = (typeof process !== 'undefined' && process.env && process.env.SSC_CLUSTER_TOKEN) || '';
  function _clusterAuthReject(req) {
    if (!_clusterAuthToken) return null;
    let hdr = '';
    try {
      if (req && req.headers && typeof req.headers.get === 'function') {
        hdr = req.headers.get('authorization') || req.headers.get('Authorization') || '';
      }
    } catch (_) {}
    const expected = 'Bearer ' + _clusterAuthToken;
    if (hdr === expected) return null;
    return {
      _type: 'Response', status: 401,
      headers: new Map([['Content-Type', 'application/json']]),
      body: '{"error":"unauthorized","hint":"set Authorization: Bearer <token>"}'
    };
  }
  // v1.23 — phi-accrual failure detector
  const _PHI_HIST_MAX  = 100;
  const _peerPongHist  = new Map();   // nodeId -> [intervalMs, ...] up to PHI_HIST_MAX
  const _peerLastPong  = new Map();   // nodeId -> epoch ms of last pong (mirrors INT/JVM)
  // v1.23 — cluster-wide FD: peerNodeId -> Map<targetNodeId, phi>
  const _peerPhiViews  = new Map();
  // v1.23 — leader election (Bully) state.
  let   _currentLeader      = "";
  let   _electionInProgress = false;
  let   _electionStartedAt  = 0;
  let   _gotAliveResponse   = false;
  let   _autoReelect         = false;
  const _ELECTION_TIMEOUT_MS = 2000;
  // v1.23 — protocol dispatch (cluster-raft.md §6).  "bully" today;
  //   Phase 3a flips to "raft", Phase 3b flips to "coord".
  let   _leaderProtocol      = "bully";
  let   _leaderCoordinator   = null;
  // v1.23 — coordinator lease state (cluster-raft.md §5).
  let   _coordAcquireFn      = null;
  let   _coordRenewFn        = null;
  let   _coordReleaseFn      = null;
  let   _coordHolderFn       = null;
  let   _coordIsLeader       = false;
  let   _coordTickHandle     = null;
  const _COORD_LEASE_TIMEOUT_MS  = 5000;
  const _COORD_RENEW_INTERVAL_MS = 1000;
  function _ensureCoordTickThread() {
    if (_coordTickHandle != null) return;
    _coordTickHandle = setInterval(() => {
      if (_leaderProtocol !== 'coord') { clearInterval(_coordTickHandle); _coordTickHandle = null; return; }
      try {
        if (!_coordIsLeader) {
          if (_coordAcquireFn) {
            let got = false;
            try { got = !!_coordAcquireFn(_localNodeId, _COORD_LEASE_TIMEOUT_MS); } catch (_) {}
            if (got) {
              _coordIsLeader = true;
              const prev = _currentLeader;
              _currentLeader = _localNodeId;
              if (prev !== _localNodeId) {
                _fireLeaderEvent("LeaderElected", _localNodeId);
                _recordLeaderHist(_localNodeId);
              }
            }
          }
        } else {
          if (_coordRenewFn) {
            let ok = false;
            try { ok = !!_coordRenewFn(_localNodeId); } catch (_) {}
            if (!ok) {
              _coordIsLeader = false;
              const prev = _currentLeader;
              _currentLeader = "";
              if (prev) _fireLeaderEvent("LeaderLost", prev);
            }
          }
        }
      } catch (_) {}
    }, _COORD_RENEW_INTERVAL_MS);
    if (_coordTickHandle && typeof _coordTickHandle.unref === 'function') _coordTickHandle.unref();
  }
  // v1.23 — drain-aware step-down (cluster-raft.md §7).
  function _stepDownIfLeader() {
    if (_leaderProtocol === 'raft') {
      if (_raftStateName === 'leader') {
        _raftStateName = 'follower';
        _raftLeaderId  = '';
        const prev = _currentLeader; _currentLeader = '';
        if (prev) _fireLeaderEvent("LeaderLost", prev);
      }
    } else if (_leaderProtocol === 'coord') {
      if (_coordIsLeader) {
        _coordIsLeader = false;
        if (_coordReleaseFn) {
          try { _coordReleaseFn(_localNodeId); } catch (_) {}
        }
        const prev = _currentLeader; _currentLeader = '';
        if (prev) _fireLeaderEvent("LeaderLost", prev);
      }
    } else {
      if (_currentLeader === _localNodeId) {
        _currentLeader = '';
        _fireLeaderEvent("LeaderLost", _localNodeId);
      }
    }
  }
  // v1.23 — bounded leader-claim history.
  const _LEADER_HIST_MAX     = 100;
  let   _leaderHistTermSeq   = 0;
  const _leaderHist          = []; // [[term, leaderId, ms], ...]
  function _recordLeaderHist(leaderId) {
    _leaderHistTermSeq += 1;
    _leaderHist.push([_leaderHistTermSeq, leaderId, Date.now()]);
    while (_leaderHist.length > _LEADER_HIST_MAX) _leaderHist.shift();
  }
  // v1.23 — Raft state (cluster-raft.md §4.1).
  let _raftCurrentTerm = 0;
  let _raftVotedFor    = "";
  let _raftStateName   = "follower"; // follower | candidate | leader
  let _raftLeaderId    = "";
  let _raftElectionDue = 0;
  let _raftVotes       = 0;
  const _RAFT_ELECTION_LO  = 150;
  const _RAFT_ELECTION_HI  = 300;
  const _RAFT_HEARTBEAT_MS = 50;
  let _raftTickHandle = null;
  function _raftRandTimeout() {
    return _RAFT_ELECTION_LO + Math.floor(Math.random() * (_RAFT_ELECTION_HI - _RAFT_ELECTION_LO + 1));
  }
  function _raftBroadcastHeartbeat() {
    const payload = JSON.stringify({ t: 'raft_append', from: _localNodeId, term: _raftCurrentTerm });
    for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
  }
  function _raftAdoptLeader(newLeader) {
    const prev = _currentLeader;
    _currentLeader = newLeader;
    if (prev !== newLeader) {
      _fireLeaderEvent("LeaderElected", newLeader);
      _recordLeaderHist(newLeader);
    }
  }
  function _startRaftElection() {
    _raftStateName    = "candidate";
    _raftCurrentTerm  = _raftCurrentTerm + 1;
    _raftVotedFor     = _localNodeId;
    _raftVotes        = 1;
    _raftElectionDue  = Date.now() + _raftRandTimeout();
    _raftPersist();
    const peerIds = [];
    for (const nid of _peerChannels.keys()) peerIds.push(nid);
    const total = peerIds.length + 1;
    if (_raftVotes > Math.floor(total / 2)) {
      _raftStateName = "leader";
      _raftLeaderId  = _localNodeId;
      _raftAdoptLeader(_localNodeId);
      _raftBroadcastHeartbeat();
    } else {
      const payload = JSON.stringify({
        t: 'raft_vote_req', from: _localNodeId, term: _raftCurrentTerm, lastLogTerm: 0
      });
      for (const nid of peerIds) {
        const ch = _peerChannels.get(nid);
        if (ch) { try { ch.send(payload); } catch (_) {} }
      }
    }
  }
  // v1.23 — Raft persistence (cluster-raft.md §4.1).  Node has `fs`;
  // browser does not run distributed Raft anyway (no inbound WS).
  let _raftFs = null;
  try { _raftFs = require('fs'); } catch (_) { _raftFs = null; }
  function _raftStatePath() {
    const key = _localNodeId ? _localNodeId.replace(/[^A-Za-z0-9._-]/g, '_') : 'default';
    return '.ssc-raft-state-' + key + '.json';
  }
  function _raftPersist() {
    if (!_raftFs) return;
    try {
      const voted = _raftVotedFor.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
      const json  = '{"currentTerm":' + _raftCurrentTerm + ',"votedFor":"' + voted + '"}';
      _raftFs.writeFileSync(_raftStatePath(), json, 'utf8');
    } catch (_) {}
  }
  function _raftLoad() {
    if (!_raftFs) return;
    try {
      const p = _raftStatePath();
      if (!_raftFs.existsSync(p)) return;
      const s = _raftFs.readFileSync(p, 'utf8');
      const o = JSON.parse(s);
      if (typeof o.currentTerm === 'number') _raftCurrentTerm = o.currentTerm;
      if (typeof o.votedFor    === 'string') _raftVotedFor    = o.votedFor;
    } catch (_) {}
  }
  function _ensureRaftTickThread() {
    if (_raftTickHandle != null) return;
    _raftTickHandle = setInterval(() => {
      if (_leaderProtocol !== 'raft') { clearInterval(_raftTickHandle); _raftTickHandle = null; return; }
      const now = Date.now();
      if (_raftStateName === 'leader') {
        _raftBroadcastHeartbeat();
      } else if (now >= _raftElectionDue) {
        _startRaftElection();
      }
    }, _RAFT_HEARTBEAT_MS);
    // Don't keep the Node event loop alive on the timer — apps end naturally.
    if (_raftTickHandle && typeof _raftTickHandle.unref === 'function') _raftTickHandle.unref();
  }
  const _leaderEventSubs    = new Set();
  const _leaderEventQueue   = [];
  function _fireLeaderEvent(tag, leaderId) {
    // Mirror into ops event log regardless of subscribers (parity with INT).
    const ts = Date.now();
    _recordEventLog('{"ts":' + ts + ',"type":' + JSON.stringify(tag) +
      ',"nodeId":' + JSON.stringify(leaderId) + '}');
    if (_leaderEventSubs.size === 0) return;
    _leaderEventQueue.push({ tag, leaderId });
  }
  // v1.23 — auto-reconnect: exponential-backoff retry per peer URL after a
  // disconnect.  Both 0 ⇒ disabled (default).  giveUp caps wall-clock
  // retry budget per URL (0 = retry forever).
  let _reconnectInitialMs = 0;
  let _reconnectMaxMs     = 0;
  let _reconnectGiveUpMs  = 0;
  // v1.23 — per-link heartbeat cadence + dead-after.  Defaults match
  // pre-v1.23 hardcoded 30s/40s; `setHeartbeatTimeout` tunes them.
  let _peerHeartbeatIntervalMs  = 30000;
  let _peerHeartbeatDeadAfterMs = 40000;
  // v1.23 — quorum-aware Bully threshold.  0 = no quorum check.
  let _quorumSize = 0;
  function _hasQuorum() {
    return _quorumSize <= 0 || (_peerChannels.size + 1) >= _quorumSize;
  }
  // v1.23 — cluster configuration distribution.  LWW per key by (ts, origin).
  const _clusterConfig    = new Map();   // key -> { value, ts, origin }
  const _configEventSubs  = new Set();
  const _configEventQueue = [];
  function _fireConfigEvent(key, value) {
    if (_configEventSubs.size === 0) return;
    _configEventQueue.push({ key, value });
  }
  function _applyConfigUpdate(key, value, ts, origin) {
    const prev = _clusterConfig.get(key);
    const accept =
      !prev || ts > prev.ts || (ts === prev.ts && origin > prev.origin);
    if (accept) {
      _clusterConfig.set(key, { value, ts, origin });
      _fireConfigEvent(key, value);
    }
    return accept;
  }
  // Snapshot every locally-known config entry to a single peer.  Called on
  // every successful handshake so late-joining nodes pick up entries set
  // before they joined (LWW on the receiver protects existing values).
  function _sendConfigSnapshot(sendFn) {
    for (const [key, entry] of _clusterConfig) {
      const payload = JSON.stringify({
        t: 'config_set', key, value: entry.value, ts: entry.ts, origin: entry.origin
      });
      try { sendFn(payload); } catch (_) {}
    }
  }
  // v1.23 — drain / rolling-restart state
  let _isDrainingSelf = false;
  const _drainingPeers   = new Map(); // nodeId -> bool
  const _drainEventSubs  = new Set();
  const _drainEventQueue = [];
  function _fireDrainEvent(nodeId, draining) {
    const ts = Date.now();
    _recordEventLog('{"ts":' + ts + ',"type":"DrainStateChanged","nodeId":' +
      JSON.stringify(nodeId) + ',"draining":' + (draining ? 'true' : 'false') + '}');
    if (_drainEventSubs.size === 0) return;
    _drainEventQueue.push({ nodeId, draining });
  }
  function _sendDrainState(sendFn) {
    if (!_isDrainingSelf) return;
    const payload = JSON.stringify({ t: 'drain', from: _localNodeId, draining: true });
    try { sendFn(payload); } catch (_) {}
  }
  // v1.23 — cluster metrics aggregation: per-node gauges.
  const _clusterMetrics    = new Map(); // name -> Map<nodeId, value>
  const _metricEventSubs   = new Set();
  const _metricEventQueue  = [];
  function _fireMetricEvent(name, nodeId, value) {
    const ts = Date.now();
    _recordEventLog('{"ts":' + ts + ',"type":"MetricChanged","name":' +
      JSON.stringify(name) + ',"nodeId":' + JSON.stringify(nodeId) +
      ',"value":' + value + '}');
    if (_metricEventSubs.size === 0) return;
    _metricEventQueue.push({ name, nodeId, value });
  }
  function _applyMetricUpdate(name, nodeId, value) {
    let inner = _clusterMetrics.get(name);
    if (!inner) { inner = new Map(); _clusterMetrics.set(name, inner); }
    const prev = inner.get(nodeId);
    inner.set(nodeId, value);
    if (prev !== value) _fireMetricEvent(name, nodeId, value);
  }
  function _sendMetricSnapshot(sendFn) {
    for (const [name, inner] of _clusterMetrics) {
      const v = inner.get(_localNodeId);
      if (v != null) {
        const payload = JSON.stringify({ t: 'metric', from: _localNodeId, name, value: v });
        try { sendFn(payload); } catch (_) {}
      }
    }
  }
  // ── v1.23 — operational /_ssc-cluster/* HTTP routes ────────────────
  // Mirrors `Interpreter.registerCluster*Route` (status / drain / events /
  // step-down / metrics-prom).  Installed by `startNode` so codegen-built
  // Node bundles expose the same ops surface as interpreter-run nodes —
  // see docs/cluster-codegen-gap.md (Tier 4 gating gap #3).  Idempotent
  // via a module-level flag; double `startNode` calls are no-ops.
  let _clusterRoutesInstalled = false;
  function _hasRoute(method, path) {
    return _routes.some(r => r.method === method && r.path === path);
  }
  function _registerClusterStatusRoute() {
    const path = '/_ssc-cluster/status';
    if (_hasRoute('GET', path)) return;
    const handler = (req) => {
      const rej = _clusterAuthReject(req);
      if (rej) return rej;
      const members = [];
      for (const nid of _peerChannels.keys()) members.push(nid);
      const drainPeers = [];
      for (const [nid, dr] of _drainingPeers) if (dr) drainPeers.push(nid);
      const leaderNow = (_leaderProtocol === 'raft') ? _raftLeaderId : _currentLeader;
      const body = '{' +
        '"nodeId":'        + JSON.stringify(_localNodeId) +
        ',"leader":'       + JSON.stringify(leaderNow) +
        ',"protocol":'     + JSON.stringify(_leaderProtocol) +
        ',"members":'      + JSON.stringify(members) +
        ',"drainingSelf":' + (_isDrainingSelf ? 'true' : 'false') +
        ',"drainingPeers":'+ JSON.stringify(drainPeers) +
        ',"raftTerm":'     + String(_raftCurrentTerm) +
        ',"raftState":'    + JSON.stringify(_raftStateName) +
        '}';
      return {
        _type: 'Response', status: 200,
        headers: new Map([['Content-Type', 'application/json']]),
        body
      };
    };
    _routes.push({ method: 'GET', path, pattern: _parsePath(path), handler });
  }
  function _registerClusterDrainRoute() {
    const path = '/_ssc-cluster/drain';
    if (_hasRoute('POST', path)) return;
    const handler = (req) => {
      const rej = _clusterAuthReject(req);
      if (rej) return rej;
      const body = (req && typeof req.body === 'string') ? req.body : '';
      let enabled;
      if (body.trim().length === 0) {
        enabled = true;
      } else {
        const needle = '"enabled":';
        const i = body.indexOf(needle);
        if (i < 0) enabled = true;
        else {
          const rest = body.substring(i + needle.length).trim();
          enabled = !rest.startsWith('false');
        }
      }
      const prev = _isDrainingSelf;
      _isDrainingSelf = enabled;
      if (prev !== enabled) {
        const payload = '{"t":"drain","from":' + JSON.stringify(_localNodeId) +
          ',"draining":' + (enabled ? 'true' : 'false') + '}';
        for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
        _fireDrainEvent(_localNodeId, enabled);
        if (enabled) _stepDownIfLeader();
      }
      return {
        _type: 'Response', status: 200,
        headers: new Map([['Content-Type', 'application/json']]),
        body: '{"drainingSelf":' + (enabled ? 'true' : 'false') + '}'
      };
    };
    _routes.push({ method: 'POST', path, pattern: _parsePath(path), handler });
  }
  function _registerClusterEventsRoute() {
    const path = '/_ssc-cluster/events';
    if (_hasRoute('GET', path)) return;
    const handler = (req) => {
      const rej = _clusterAuthReject(req);
      if (rej) return rej;
      let sinceMs = 0;
      try {
        if (req && req.query && typeof req.query.get === 'function') {
          const s = req.query.get('since');
          if (s) { const n = Number(s); if (Number.isFinite(n)) sinceMs = n; }
        }
      } catch (_) {}
      const parts = [];
      const tsPrefix = '{"ts":';
      for (const line of _clusterEventLog) {
        let pass = true;
        if (sinceMs > 0) {
          pass = false;
          if (line.startsWith(tsPrefix)) {
            const end = line.indexOf(',', tsPrefix.length);
            if (end > 0) {
              const v = Number(line.substring(tsPrefix.length, end));
              if (Number.isFinite(v) && v > sinceMs) pass = true;
            }
          }
        }
        if (pass) parts.push(line);
      }
      return {
        _type: 'Response', status: 200,
        headers: new Map([['Content-Type', 'application/json']]),
        body: '[' + parts.join(',') + ']'
      };
    };
    _routes.push({ method: 'GET', path, pattern: _parsePath(path), handler });
  }
  function _registerClusterStepDownRoute() {
    const path = '/_ssc-cluster/step-down';
    if (_hasRoute('POST', path)) return;
    const handler = (req) => {
      const rej = _clusterAuthReject(req);
      if (rej) return rej;
      let wasLeader = false;
      if (_leaderProtocol === 'raft')       wasLeader = (_raftStateName === 'leader');
      else if (_leaderProtocol === 'coord') wasLeader = !!_coordIsLeader;
      else                                  wasLeader = (_currentLeader === _localNodeId);
      if (!wasLeader) {
        return {
          _type: 'Response', status: 409,
          headers: new Map([['Content-Type', 'application/json']]),
          body: '{"error":"not_leader","leader":' + JSON.stringify(_currentLeader) + '}'
        };
      }
      _stepDownIfLeader();
      return {
        _type: 'Response', status: 200,
        headers: new Map([['Content-Type', 'application/json']]),
        body: '{"steppedDown":true,"nodeId":' + JSON.stringify(_localNodeId) + '}'
      };
    };
    _routes.push({ method: 'POST', path, pattern: _parsePath(path), handler });
  }
  function _registerClusterMetricsPromRoute() {
    const path = '/_ssc-cluster/metrics-prom';
    if (_hasRoute('GET', path)) return;
    const sanitize = (s) => {
      let out = '';
      for (let i = 0; i < s.length; i++) {
        const c = s.charCodeAt(i);
        const ok =
          (c >= 97 && c <= 122) /* a-z */ ||
          (c >= 65 && c <= 90)  /* A-Z */ ||
          (c >= 48 && c <= 57)  /* 0-9 */ ||
          c === 95 /* _ */ || c === 58 /* : */;
        out += ok ? s.charAt(i) : '_';
      }
      if (out.length > 0 && out.charCodeAt(0) >= 48 && out.charCodeAt(0) <= 57) out = '_' + out;
      return out;
    };
    const escLabel = (s) => s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
    const handler = (req) => {
      const rej = _clusterAuthReject(req);
      if (rej) return rej;
      let out = '';
      for (const [name, inner] of _clusterMetrics) {
        const pName = sanitize(name);
        out += '# TYPE ' + pName + ' gauge\n';
        for (const [nodeId, value] of inner) {
          out += pName + '{nodeId="' + escLabel(nodeId) + '"} ' + Number(value) + '\n';
        }
      }
      return {
        _type: 'Response', status: 200,
        headers: new Map([['Content-Type', 'text/plain; version=0.0.4; charset=utf-8']]),
        body: out
      };
    };
    _routes.push({ method: 'GET', path, pattern: _parsePath(path), handler });
  }
  function _installClusterRoutes() {
    if (_clusterRoutesInstalled) return;
    _clusterRoutesInstalled = true;
    _registerClusterStatusRoute();
    _registerClusterDrainRoute();
    _registerClusterEventsRoute();
    _registerClusterStepDownRoute();
    _registerClusterMetricsPromRoute();
  }
  // v1.23 — URL-keyed dedupe so concurrent peer-loss + dial-failure
  // events for the same URL don't each spin up an independent
  // exponential-backoff loop.
  const _reconnectActive = new Set();
  function _scheduleReconnect(rurl, rtok) {
    if (_reconnectActive.has(rurl)) return;
    _reconnectActive.add(rurl);
    const startedAt = Date.now();
    let delay = Math.max(_reconnectInitialMs, 1);
    const attempt = () => {
      if (_reconnectInitialMs <= 0) { _reconnectActive.delete(rurl); return; }
      // Already reconnected?  `_peerUrls` is populated on successful handshake.
      for (const u of _peerUrls.values()) if (u === rurl) { _reconnectActive.delete(rurl); return; }
      // v1.23 — give-up budget: stop after the configured wall-clock.
      if (_reconnectGiveUpMs > 0 && (Date.now() - startedAt) >= _reconnectGiveUpMs) {
        _reconnectActive.delete(rurl); return;
      }
      try { _connectNodeAsync(rurl, rtok); } catch (_) {}
      const cap = _reconnectMaxMs > 0 ? _reconnectMaxMs : delay;
      delay = Math.min(delay * 2, Math.max(cap, delay));
      setTimeout(attempt, delay);
    };
    setTimeout(attempt, delay);
  }
  function _broadcastCoordinator() {
    const payload = '{"t":"coordinator","from":' + JSON.stringify(_localNodeId) + '}';
    for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
  }
  function _startElection() {
    if (!_localNodeId) {
      const prev = _currentLeader; _currentLeader = _localNodeId;
      if (prev !== _localNodeId) { _fireLeaderEvent("LeaderElected", _localNodeId); _recordLeaderHist(_localNodeId); }
      return;
    }
    const higher = [];
    for (const nid of _peerChannels.keys()) if (nid > _localNodeId) higher.push(nid);
    if (higher.length === 0) {
      // v1.23 — quorum gate: refuse self-claim when below quorum
      // (split-brain guard).  No-op when `_quorumSize = 0`.
      if (_hasQuorum()) {
        const prev = _currentLeader; _currentLeader = _localNodeId;
        _broadcastCoordinator();
        if (prev !== _localNodeId) { _fireLeaderEvent("LeaderElected", _localNodeId); _recordLeaderHist(_localNodeId); }
      }
    } else {
      _electionInProgress = true;
      _electionStartedAt  = Date.now();
      _gotAliveResponse   = false;
      const payload = '{"t":"election","from":' + JSON.stringify(_localNodeId) + '}';
      for (const nid of higher) {
        const ch = _peerChannels.get(nid);
        if (ch) { try { ch.send(payload); } catch (_) {} }
      }
    }
  }
  function _recordPongInterval(nid) {
    const now = Date.now();
    const last = _peerLastPong.get(nid);
    if (last != null && last > 0) {
      let hist = _peerPongHist.get(nid);
      if (!hist) { hist = []; _peerPongHist.set(nid, hist); }
      hist.push(now - last);
      while (hist.length > _PHI_HIST_MAX) hist.shift();
    }
    _peerLastPong.set(nid, now);
  }
  function _computePhi(nid) {
    const hist = _peerPongHist.get(nid);
    if (!hist || hist.length === 0) return Number.POSITIVE_INFINITY;
    const n = hist.length;
    let s = 0; for (let i = 0; i < n; i++) s += hist[i];
    const mean = s / n;
    let sq = 0; for (let i = 0; i < n; i++) { const d = hist[i] - mean; sq += d * d; }
    const variance = n > 1 ? sq / (n - 1) : 1.0;
    const stddev   = Math.max(Math.sqrt(variance), 50.0);
    const last     = _peerLastPong.get(nid);
    const now      = Date.now();
    const elapsed  = last == null ? Number.POSITIVE_INFINITY : now - last;
    if (elapsed <= mean) return 0.0;
    // Right-tail Normal approximation (Akka / Cassandra style):
    //   phi = -log10( exp(-z² / 2) / (z * sqrt(2π)) )  with  z = (elapsed - μ) / σ
    const z    = (elapsed - mean) / stddev;
    const tail = Math.exp(-z * z / 2) / (z * Math.sqrt(2 * Math.PI));
    if (tail <= 0) return Number.POSITIVE_INFINITY;
    return -Math.log10(Math.min(tail, 1.0));
  }

  // Drain all peer ring buffers into _remoteRawInbox.
  function _drainPeerBuffers() {
    for (const [, peer] of _peerChannels) {
      // Inbound (server-side) peers route messages through the WS
      // `onMessage` callback which pushes straight into
      // `_remoteRawInbox`; they have no SharedArrayBuffer ring (`sab:
      // null`).  Skipping them here avoids `new Int32Array(null, ...)`
      // which throws "Invalid atomic access index" under Node ≥ 16.
      if (!peer.sab) continue;
      const hdr = new Int32Array(peer.sab, 0, 2);
      for (;;) {
        const wc = Atomics.load(hdr, 0);
        const rc = Atomics.load(hdr, 1);
        if (wc === rc) break;
        const slotOff = (rc % _RING_SLOTS) * _RING_SLOT_BYTES;
        const len  = new DataView(peer.sab).getInt32(_RING_HDR + slotOff, true);
        if (len > 0) {
          const bytes = new Uint8Array(peer.sab, _RING_HDR + slotOff + 4, len);
          _remoteRawInbox.push(Buffer.from(bytes).toString('utf8'));
        }
        Atomics.store(hdr, 1, rc + 1);
      }
    }
  }

  // Deserialise a $t-tagged JSON object into a ScalaScript value.
  function _deserActorVal(o) {
    if (o == null) return undefined;
    switch (o.$t) {
      case 'i': case 'd': return o.v;
      case 's': return o.v;
      case 'b': return o.v;
      case 'u': return undefined;
      case 'pid': return Pid(o.n, o.id);
      case 'l': return o.v.map(_deserActorVal);
      case 'o': {
        const obj = { _type: o.cls };
        for (const [k, v] of Object.entries(o.f)) obj[k] = _deserActorVal(v);
        return obj;
      }
      default: return String(o.v != null ? o.v : '');
    }
  }

  // Serialise a ScalaScript value to a $t-tagged JSON string.
  function _serActorVal(v) {
    if (v === null || v === undefined) return '{"$t":"u"}';
    if (typeof v === 'number' && Number.isInteger(v)) return '{"$t":"i","v":' + v + '}';
    if (typeof v === 'number') return '{"$t":"d","v":' + v + '}';
    if (typeof v === 'string')  return '{"$t":"s","v":' + JSON.stringify(v) + '}';
    if (typeof v === 'boolean') return '{"$t":"b","v":' + v + '}';
    if (v && v._type === 'Pid') return '{"$t":"pid","n":' + JSON.stringify(v.nodeId) + ',"id":' + v.localId + '}';
    if (Array.isArray(v)) return '{"$t":"l","v":[' + v.map(_serActorVal).join(',') + ']}';
    if (v && v._type) {
      const flds = Object.entries(v).filter(([k]) => k !== '_type').map(([k, vv]) => JSON.stringify(k) + ':' + _serActorVal(vv)).join(',');
      return '{"$t":"o","cls":' + JSON.stringify(v._type) + ',"f":{' + flds + '}}';
    }
    return '{"$t":"s","v":' + JSON.stringify(String(v)) + '}';
  }

  // Establish a new outbound peer connection (shared by connectNode op and joinCluster gossip).
  function _connectNodeAsync(peerUrl, token) {
    if (_peerChannels.has('__pending__' + peerUrl)) return; // already connecting
    const sab = new SharedArrayBuffer(_RING_HDR + _RING_SLOTS * _RING_SLOT_BYTES);
    const ownNodeId = _localNodeId;
    const ringSlots = _RING_SLOTS, ringSlotBytes = _RING_SLOT_BYTES, ringHdr = _RING_HDR;
    const workerSrc = [
      "const { workerData, parentPort } = require('worker_threads');",
      "const { url, token, sab, ownNodeId, RS, RSB, RH } = workerData;",
      "let WsClass; try { WsClass = require('ws'); } catch(_) { throw new Error('connectNode requires the ws npm package'); }",
      "const hdr  = new Int32Array(sab, 0, 2);",
      "function ringWrite(json) {",
      "  const wc = Atomics.load(hdr, 0), rc = Atomics.load(hdr, 1);",
      "  if (((wc - rc + RS) % RS) >= RS - 1) return;",
      "  const bytes = Buffer.from(json, 'utf8');",
      "  if (bytes.length + 4 > RSB) return;",
      "  const off = RH + (wc % RS) * RSB;",
      "  new DataView(sab).setInt32(off, bytes.length, true);",
      "  new Uint8Array(sab).set(bytes, off + 4);",
      "  Atomics.store(hdr, 0, wc + 1);",
      "}",
      "const hdrs = token ? { Authorization: 'Bearer ' + token } : {};",
      "const ws = new WsClass(url, ['ssc-actors-v1'], { headers: hdrs });",
      "let peerNodeId = '';",
      "ws.on('open', () => ws.send(JSON.stringify({ nodeId: ownNodeId })));",
      "ws.on('message', (data) => {",
      "  const msg = Buffer.isBuffer(data) ? data.toString('utf8') : String(data);",
      "  if (!peerNodeId) {",
      "    try { const h = JSON.parse(msg); if (h.nodeId) { peerNodeId = h.nodeId; ringWrite(JSON.stringify({ t: 'handshake', nodeId: peerNodeId })); } } catch(_) {}",
      "  } else {",
      "    // v1.23 — inject peer nodeId into 'pong' frames so the main thread can",
      "    // attribute the inter-arrival sample to the right peer (phi-accrual).",
      "    try { const p = JSON.parse(msg); if (p && p.t === 'pong') { ringWrite(JSON.stringify({ t: 'pong', from: peerNodeId })); return; } } catch(_) {}",
      "    ringWrite(msg);",
      "  }",
      "});",
      "ws.on('close', () => { if (peerNodeId) ringWrite(JSON.stringify({ t: 'down', nodeId: peerNodeId })); });",
      "ws.on('error', (e) => { console.error('ssc-actors connectNode error [' + url + ']:', e.message); if (peerNodeId) ringWrite(JSON.stringify({ t: 'down', nodeId: peerNodeId })); });",
      "parentPort.on('message', ({ json }) => { try { if (ws.readyState === 1) ws.send(json); } catch(_) {} });",
      "setInterval(() => { try { if (ws.readyState === 1) ws.send(JSON.stringify({ t: 'ping' })); } catch(_) {} }, 30000);"
    ].join('\n');
    const { Worker } = require('worker_threads');
    const worker = new Worker(workerSrc, { eval: true,
      workerData: { url: peerUrl, token, sab, ownNodeId, RS: ringSlots, RSB: ringSlotBytes, RH: ringHdr } });
    const sendFn = (json) => worker.postMessage({ json });
    _peerChannels.set('__pending__' + peerUrl, { sab, send: sendFn, worker, pending: true, url: peerUrl });
  }

  // Process _remoteRawInbox: deliver messages, handle handshakes, node-downs.
  function _processRemoteInbox() {
    while (_remoteRawInbox.length > 0) {
      const raw = _remoteRawInbox.shift();
      try {
        const env = JSON.parse(raw);
        if (env.t === 'handshake') {
          // Worker completed WS handshake — upgrade pending entry to real nodeId.
          const peerNodeId = env.nodeId;
          for (const [key, peer] of _peerChannels) {
            if (peer.pending) {
              _peerChannels.delete(key);
              _peerChannels.set(peerNodeId, { sab: peer.sab, send: peer.send, worker: peer.worker });
              _peerUrls.set(peerNodeId, peer.url || '');
              _fireClusterEvent('NodeJoined', peerNodeId);
              // If in joinCluster mode, request this peer's peer list.
              if (_joinMode) {
                peer.send(JSON.stringify({ t: 'peers_req', from: _localNodeId }));
              }
              // v1.23 — snapshot the cluster config + drain state to the new peer.
              _sendConfigSnapshot(peer.send);
              _sendDrainState(peer.send);
              _sendMetricSnapshot(peer.send);
              break;
            }
          }
        } else if (env.t === 'msg') {
          const toId = env.to && env.to.localId;
          if (toId != null) {
            const body = _deserActorVal(env.body);
            const st   = actors.get(toId);
            if (st) {
              st.mailbox.push(body);
              tryWakeBlocked(toId);
            }
          }
        } else if (env.t === 'ping') {
          const peer = _peerChannels.get(env.from);
          if (peer) peer.send(JSON.stringify({ t: 'pong' }));
        } else if (env.t === 'pong') {
          // v1.23 — phi-accrual: record inter-pong interval.  The worker
          // injects `from: peerNodeId` so we can attribute the sample.
          if (env.from) _recordPongInterval(env.from);
        } else if (env.t === 'down') {
          _nodeDownQueue.push(env.nodeId);
          const _lostUrl = _peerUrls.get(env.nodeId) || '';
          _peerChannels.delete(env.nodeId);
          _peerUrls.delete(env.nodeId);
          _peerPongHist.delete(env.nodeId);
          _peerLastPong.delete(env.nodeId);
          _peerPhiViews.delete(env.nodeId);
          _drainingPeers.delete(env.nodeId);
          for (const [, inner] of _clusterMetrics) inner.delete(env.nodeId);
          _fireClusterEvent('NodeLeft', env.nodeId, 'disconnect');
          if (_currentLeader === env.nodeId) {
            _currentLeader = "";
            _fireLeaderEvent("LeaderLost", env.nodeId);
            if (_autoReelect) _startElection();
          }
          if (_reconnectInitialMs > 0 && _lostUrl) _scheduleReconnect(_lostUrl, _joinToken || '');
        } else if (env.t === 'peers_req') {
          // Respond with our known peer URLs + self URL.
          const myPeers = [];
          if (_selfUrl) myPeers.push({ nodeId: _localNodeId, url: _selfUrl });
          for (const [nid] of _peerChannels) {
            const u = _peerUrls.get(nid);
            if (u) myPeers.push({ nodeId: nid, url: u });
          }
          const reqFrom = env.from;
          const reqChan = reqFrom ? _peerChannels.get(reqFrom) : null;
          if (reqChan) reqChan.send(JSON.stringify({ t: 'peers_resp', peers: myPeers }));
        } else if (env.t === 'peers_resp') {
          // Connect to any peers we don't yet know.
          const peers = env.peers || [];
          for (const { nodeId: pnid, url: purl } of peers) {
            if (pnid && purl && pnid !== _localNodeId && !_peerChannels.has(pnid)) {
              _connectNodeAsync(purl, _joinToken);
            }
          }
        } else if (env.t === 'global_reg') {
          if (env.name && env.nodeId != null) {
            _globalRegistry.set(env.name, Pid(env.nodeId, Number(env.localId)));
          }
        } else if (env.t === 'phi_vector') {
          // v1.23 — peer broadcasted its phi vector; record its view.
          if (env.from && Array.isArray(env.view)) {
            const m = new Map();
            for (const pair of env.view) {
              if (Array.isArray(pair) && pair.length === 2) {
                m.set(String(pair[0]), Number(pair[1]));
              }
            }
            _peerPhiViews.set(env.from, m);
          }
        } else if (env.t === 'election') {
          // v1.23 — Bully: lower-id peer is calling an election.
          if (env.from && env.from < _localNodeId) {
            const reply = '{"t":"alive","from":' + JSON.stringify(_localNodeId) + '}';
            const ch = _peerChannels.get(env.from);
            if (ch) { try { ch.send(reply); } catch (_) {} }
            if (!_electionInProgress) _startElection();
          }
        } else if (env.t === 'alive') {
          _gotAliveResponse = true;
        } else if (env.t === 'coordinator') {
          if (env.from) {
            const prev = _currentLeader;
            _currentLeader = env.from;
            _electionInProgress = false;
            if (prev !== env.from) { _fireLeaderEvent("LeaderElected", env.from); _recordLeaderHist(env.from); }
          }
        } else if (env.t === 'config_set') {
          const k = env.key != null ? String(env.key) : '';
          const v = env.value != null ? String(env.value) : '';
          const o = env.origin != null ? String(env.origin) : '';
          const t = env.ts != null ? Number(env.ts) : 0;
          if (k) _applyConfigUpdate(k, v, t, o);
        } else if (env.t === 'drain') {
          const from = env.from != null ? String(env.from) : '';
          if (from) {
            const dr = !!env.draining;
            const prev = _drainingPeers.get(from);
            _drainingPeers.set(from, dr);
            if (prev !== dr) _fireDrainEvent(from, dr);
          }
        } else if (env.t === 'metric') {
          const from  = env.from != null ? String(env.from) : '';
          const name  = env.name != null ? String(env.name) : '';
          const value = env.value != null ? Number(env.value) : 0;
          if (from && name) _applyMetricUpdate(name, from, value);
        } else if (env.t === 'raft_vote_req') {
          const from = env.from != null ? String(env.from) : '';
          const term = env.term != null ? Number(env.term) : 0;
          if (from) {
            let granted = false;
            let mutated = false;
            if (term < _raftCurrentTerm) granted = false;
            else {
              if (term > _raftCurrentTerm) {
                _raftCurrentTerm = term;
                _raftVotedFor    = "";
                _raftStateName   = "follower";
                mutated = true;
              }
              if (_raftVotedFor === "" || _raftVotedFor === from) {
                _raftVotedFor    = from;
                _raftElectionDue = Date.now() + _raftRandTimeout();
                mutated = true;
                granted = true;
              }
            }
            if (mutated) _raftPersist();
            const reply = JSON.stringify({
              t: 'raft_vote_resp', from: _localNodeId, term: _raftCurrentTerm, granted
            });
            const ch = _peerChannels.get(from);
            if (ch) { try { ch.send(reply); } catch (_) {} }
          }
        } else if (env.t === 'raft_vote_resp') {
          const term = env.term != null ? Number(env.term) : 0;
          const granted = !!env.granted;
          if (term === _raftCurrentTerm && _raftStateName === "candidate" && granted) {
            _raftVotes = _raftVotes + 1;
            const total = _peerChannels.size + 1;
            if (_raftVotes > Math.floor(total / 2)) {
              _raftStateName = "leader";
              _raftLeaderId  = _localNodeId;
              _raftAdoptLeader(_localNodeId);
              _raftBroadcastHeartbeat();
            }
          }
        } else if (env.t === 'raft_append') {
          const from = env.from != null ? String(env.from) : '';
          const term = env.term != null ? Number(env.term) : 0;
          if (from && term >= _raftCurrentTerm) {
            const termChanged = term > _raftCurrentTerm;
            _raftCurrentTerm = term;
            _raftStateName   = "follower";
            const prevLeader = _raftLeaderId;
            _raftLeaderId    = from;
            _raftElectionDue = Date.now() + _raftRandTimeout();
            if (termChanged) _raftPersist();
            if (prevLeader !== from) _raftAdoptLeader(from);
          }
        }
      } catch (_e) {}
    }
  }

  function _fireNodeDown(deadNodeId) {
    const deadPidOf = (localId) => Pid(deadNodeId, localId);
    const mons = _remoteMonitors.get(deadNodeId) || [];
    _remoteMonitors.delete(deadNodeId);
    for (const [monRef, observerId, remoteLocalId] of mons) {
      const st = actors.get(observerId);
      if (st) {
        st.mailbox.push({ _type: 'Down', ref: monRef, from: deadPidOf(remoteLocalId), reason: 'noconnection' });
        tryWakeBlocked(observerId);
      }
    }
    const lnks = _remoteLinks.get(deadNodeId) || [];
    _remoteLinks.delete(deadNodeId);
    for (const [localActorId, remotePid] of lnks) {
      const ls = links.get(localActorId);
      if (ls) ls.delete(remotePid.localId);
      if (trapExitMap.get(localActorId)) {
        const st = actors.get(localActorId);
        if (st) {
          st.mailbox.push({ _type: 'Exit', from: deadPidOf(remotePid.localId), reason: 'noconnection' });
          tryWakeBlocked(localActorId);
        }
      } else {
        killActor(localActorId, 'noconnection');
      }
    }
  }

  const ready  = [];
  let   nextId = 0;
  let   rootResult = undefined;

  function spawnActor(thunk, cap, overflow) {
    const id = nextId++;
    actors.set(id, { mailbox: [], pending: thunk(), blocked: null,
                     cap: cap || 0, overflow: overflow || '',
                     blockedSends: [] });
    ready.push(id);
    return Pid(_localNodeId, id);
  }
  const rootId = spawnActor(bodyFn);

  function _resumeBlockedSender(state) {
    if (!state.cap || !state.blockedSends || state.blockedSends.length === 0) return;
    if (state.mailbox.length >= state.cap) return;
    while (state.blockedSends.length > 0) {
      const { senderId, msg, k } = state.blockedSends.shift();
      const ss = actors.get(senderId);
      if (!ss) continue;  // dead sender — skip
      state.mailbox.push(msg);
      ss.pending = k(undefined);
      ready.push(senderId);
      return;
    }
  }

  function tryDeliver(state, matcher, wrapSome) {
    while (state.mailbox.length > 0) {
      const msg = state.mailbox[0];
      const r   = matcher(msg);
      if (r && r.matched) {
        state.mailbox.shift();
        _resumeBlockedSender(state);
        const bodyC = r.body();
        return wrapSome
          ? new _FlatMap(bodyC, (v) => _Some(v))
          : bodyC;
      }
      state.mailbox.shift();  // dead-letter
      _resumeBlockedSender(state);
    }
    return null;
  }

  function tryWakeBlocked(id) {
    const st = actors.get(id);
    if (!st || !st.blocked) return;
    const b = st.blocked;
    const delivered = tryDeliver(st, b.matcher, b.wrapSome);
    if (delivered !== null) {
      st.pending = new _FlatMap(delivered, b.k);
      st.blocked = null;
      ready.push(id);
    }
  }

  // Kill actor targetId with reason, propagate through links, fire monitors.
  // Idempotent: if actor not in `actors` map it's already dead.
  function killActor(targetId, reason) {
    if (!actors.has(targetId)) return;
    const _dying = actors.get(targetId);
    actors.delete(targetId);
    trapExitMap.delete(targetId);
    // Resume blocked senders: target died → send becomes silent no-op.
    if (_dying && _dying.blockedSends && _dying.blockedSends.length > 0) {
      for (const { senderId, k } of _dying.blockedSends) {
        const ss = actors.get(senderId);
        if (ss) { ss.pending = k(undefined); ready.push(senderId); }
      }
      _dying.blockedSends.length = 0;
    }

    const deadPid = Pid(_localNodeId, targetId);

    // Notify linked actors.
    const linkedSet = links.get(targetId);
    links.delete(targetId);
    if (linkedSet) {
      for (const linkedId of linkedSet) {
        const ls = links.get(linkedId);
        if (ls) ls.delete(targetId);
        if (trapExitMap.get(linkedId)) {
          const st = actors.get(linkedId);
          if (st) {
            st.mailbox.push({ _type: 'Exit', from: deadPid, reason });
            tryWakeBlocked(linkedId);
          }
        } else {
          killActor(linkedId, reason);
        }
      }
    }

    // Fire Down to all monitors watching targetId.
    const monMap = monitors.get(targetId);
    monitors.delete(targetId);
    if (monMap) {
      for (const [monRef, observerId] of monMap) {
        const st = actors.get(observerId);
        if (st) {
          st.mailbox.push({ _type: 'Down', ref: monRef, from: deadPid, reason });
          tryWakeBlocked(observerId);
        }
      }
    }
  }

  function handleActorOp(id, state, op, args, k) {
    switch (op) {
      case 'spawn': {
        const childPid = spawnActor(args[0]);
        return { suspend: false, next: k(childPid) };
      }
      case 'spawnLink': {
        const childPid = spawnActor(args[0]);
        // Atomic bidirectional link
        const childId = childPid.localId;
        if (!links.has(id))      links.set(id,      new Set());
        if (!links.has(childId)) links.set(childId, new Set());
        links.get(id).add(childId);
        links.get(childId).add(id);
        return { suspend: false, next: k(childPid) };
      }
      case 'spawnBounded': {
        const cap      = args[0];
        const overflow = args[1] && (args[1]._type || '');
        const childPid = spawnActor(args[2], cap, overflow);
        return { suspend: false, next: k(childPid) };
      }
      case 'self':
        return { suspend: false, next: k(Pid(_localNodeId, id)) };
      case 'processInfo': {
        const target = args[0];
        if (!target || target._type !== 'Pid') return { suspend: false, next: k(undefined) };
        const targetId = target.localId;
        const ts = actors.get(targetId);
        if (!ts) return { suspend: false, next: k(undefined) };  // dead → None
        const lnks = links.get(targetId);
        const linkList = lnks ? Array.from(lnks).map(lid => Pid(_localNodeId, lid)) : [];
        const status   = ts.blocked ? 'blocked' : 'running';
        const info = { _type: 'ProcessInfo', mailboxSize: ts.mailbox.length,
                       links: linkList, status };
        return { suspend: false, next: k({ _type: 'Some', value: info }) };
      }
      case 'send': {
        const target = args[0];
        if (target && target._type === 'Pid') {
          const targetNode = target.nodeId || '';
          if (targetNode && targetNode !== _localNodeId) {
            // Remote send — serialise and deliver via peer channel.
            const peer = _peerChannels.get(targetNode);
            if (peer) {
              const body = _serActorVal(args[1]);
              const env  = '{"t":"msg","to":{"nodeId":' + JSON.stringify(targetNode) +
                           ',"localId":' + target.localId + '},"from":{"nodeId":' +
                           JSON.stringify(_localNodeId) + ',"localId":' + id + '},"body":' + body + '}';
              peer.send(env);
            }
          } else {
            const ts = actors.get(target.localId);
            if (ts) {
              // Bounded mailbox: apply overflow strategy when at capacity.
              if (ts.cap > 0 && ts.mailbox.length >= ts.cap) {
                switch (ts.overflow) {
                  case 'DropOldest':
                    ts.mailbox.shift();
                    ts.mailbox.push(args[1]);
                    break;
                  case 'DropNewest':
                    return { suspend: false, next: k(undefined) };
                  case 'Fail':
                    killActor(id, { _type: 'mailbox_overflow' });
                    if (!actors.has(id)) return { suspend: true };
                    return { suspend: false, next: k(undefined) };
                  case 'Block':
                    ts.blockedSends.push({ senderId: id, msg: args[1], k });
                    return { suspend: true };
                  default:
                    ts.mailbox.push(args[1]);
                }
              } else {
                ts.mailbox.push(args[1]);
              }
              if (ts.blocked) {
                const b = ts.blocked;
                const delivered = tryDeliver(ts, b.matcher, b.wrapSome);
                if (delivered !== null) {
                  ts.pending = new _FlatMap(delivered, b.k);
                  ts.blocked = null;
                  ready.push(target.localId);
                }
              }
            }
          }
        }
        return { suspend: false, next: k(undefined) };
      }
      case 'exit': {
        const target = args[0];
        if (target && target._type === 'Pid') {
          killActor(target.localId, args[1]);
        }
        // The caller may have been killed by a link; check.
        if (!actors.has(id)) return { suspend: true };
        return { suspend: false, next: k(undefined) };
      }
      case 'receive': {
        const matcher = _receiveSpecs.get(args[0]);
        const c = tryDeliver(state, matcher, false);
        if (c !== null) return { suspend: false, next: new _FlatMap(c, k) };
        state.blocked = { matcher, k, wrapSome: false, deadline: null };
        return { suspend: true };
      }
      case 'receive_t': {
        const matcher = _receiveSpecs.get(args[0]);
        const c = tryDeliver(state, matcher, true);
        if (c !== null) return { suspend: false, next: new _FlatMap(c, k) };
        state.blocked = { matcher, k, wrapSome: true, deadline: Date.now() + args[1] };
        return { suspend: true };
      }
"""

private val JsRuntimeAsyncB: String = """
      // ── v1.6 Phase 2 — supervision ────────────────────────────────────
      case 'link': {
        const target = args[0];
        if (target && target._type === 'Pid') {
          const targetNodeId = target.nodeId;
          const targetId     = target.localId;
          if (targetNodeId && targetNodeId !== _localNodeId) {
            // Remote pid — register for node-down notification.
            if (!_remoteLinks.has(targetNodeId)) _remoteLinks.set(targetNodeId, []);
            _remoteLinks.get(targetNodeId).push([id, target]);
          } else if (actors.has(targetId)) {
            if (!links.has(id))       links.set(id,       new Set());
            if (!links.has(targetId)) links.set(targetId, new Set());
            links.get(id).add(targetId);
            links.get(targetId).add(id);
          } else {
            // Target already dead — noproc exit signal.
            const noproc = { _type: 'noproc' };
            if (trapExitMap.get(id)) {
              const st = actors.get(id);
              if (st) st.mailbox.push({ _type: 'Exit', from: target, reason: noproc });
            } else {
              killActor(id, noproc);
            }
          }
        }
        if (!actors.has(id)) return { suspend: true };
        return { suspend: false, next: k(undefined) };
      }
      case 'monitor': {
        const target = args[0];
        if (target && target._type === 'Pid') {
          const targetNodeId = target.nodeId;
          const targetId     = target.localId;
          const monRef = nextMonRef++;
          if (targetNodeId && targetNodeId !== _localNodeId) {
            // Remote pid — register for node-down notification.
            if (!_remoteMonitors.has(targetNodeId)) _remoteMonitors.set(targetNodeId, []);
            _remoteMonitors.get(targetNodeId).push([monRef, id, targetId]);
            return { suspend: false, next: k(monRef) };
          } else if (actors.has(targetId)) {
            if (!monitors.has(targetId)) monitors.set(targetId, new Map());
            monitors.get(targetId).set(monRef, id);
          } else {
            // Already dead — immediate Down(noproc).
            const st = actors.get(id);
            if (st) {
              st.mailbox.push({ _type: 'Down', ref: monRef, from: target, reason: { _type: 'noproc' } });
              tryWakeBlocked(id);
            }
          }
          return { suspend: false, next: k(monRef) };
        }
        return { suspend: false, next: k(-1) };
      }
      case 'demonitor': {
        const monRef = args[0];
        for (const [, monMap] of monitors) monMap.delete(monRef);
        return { suspend: false, next: k(undefined) };
      }
      case 'trapExit': {
        trapExitMap.set(id, args[0] === true || args[0]);
        return { suspend: false, next: k(undefined) };
      }
      // ── v1.6 Phase 3 — distributed node primitives ────────────────────
      case 'startNode': {
        _localNodeId = String(args[0]);
        if (args[1] != null) _selfUrl = String(args[1]);
        // v1.23 — auto-register operational /_ssc-cluster/* HTTP routes
        // so `ssc cluster status / drain / events / step-down /
        // metrics-prom` work against codegen-built Node nodes.  Idempotent.
        _installClusterRoutes();
        // Register /_ssc-actors WS handler for inbound peer connections.
        // Peers connect, exchange nodeId handshake, then send actor messages.
        if (typeof onWebSocket === 'function') {
          onWebSocket('/_ssc-actors', (ws) => {
            let peerNodeId = '';
            ws.onMessage((msg) => {
              if (!peerNodeId) {
                try {
                  const h = JSON.parse(msg);
                  if (h.nodeId) {
                    peerNodeId = h.nodeId;
                    ws.send(JSON.stringify({ nodeId: _localNodeId }));
                    // Register send channel for this inbound peer.
                    _peerChannels.set(peerNodeId, { sab: null, send: (json) => ws.send(json) });
                    _fireClusterEvent('NodeJoined', peerNodeId);
                    // v1.23 — snapshot the cluster config + drain state to the new peer.
                    _sendConfigSnapshot((json) => ws.send(json));
                    _sendDrainState((json) => ws.send(json));
                    _sendMetricSnapshot((json) => ws.send(json));
                  }
                } catch (_) {}
              } else {
                // v1.23 — tag pongs inbound on this server-side channel.
                try {
                  const p = JSON.parse(msg);
                  if (p && p.t === 'pong') {
                    _remoteRawInbox.push(JSON.stringify({ t: 'pong', from: peerNodeId }));
                    return;
                  }
                } catch (_) {}
                _remoteRawInbox.push(msg);
              }
            });
            ws.onClose(() => {
              if (peerNodeId) {
                _nodeDownQueue.push(peerNodeId);
                _peerChannels.delete(peerNodeId);
                _peerUrls.delete(peerNodeId);
                _peerPongHist.delete(peerNodeId);
                _peerLastPong.delete(peerNodeId);
                _peerPhiViews.delete(peerNodeId);
                _drainingPeers.delete(peerNodeId);
                for (const [, inner] of _clusterMetrics) inner.delete(peerNodeId);
                _fireClusterEvent('NodeLeft', peerNodeId, 'disconnect');
                if (_currentLeader === peerNodeId) {
                  _currentLeader = "";
                  _fireLeaderEvent("LeaderLost", peerNodeId);
                  if (_autoReelect) _startElection();
                }
              }
            });
          });
        }
        return { suspend: false, next: k(undefined) };
      }
      case 'connectNode': {
        const peerUrl = String(args[0]);
        const token   = args[1] != null ? String(args[1]) : '';
        _connectNodeAsync(peerUrl, token);
        return { suspend: false, next: k(undefined) };
      }
      case 'joinCluster': {
        _joinMode  = true;
        _joinToken = args[1] != null ? String(args[1]) : '';
        const seeds = args[0];
        const urls  = Array.isArray(seeds) ? seeds : [];
        for (const u of urls) { if (typeof u === 'string' && u) _connectNodeAsync(u, _joinToken); }
        return { suspend: false, next: k(undefined) };
      }
      case 'register': {
        const regName = args[0];
        const regPid  = args[1];
        if (regPid && regPid._type === 'Pid') _nodeRegistry.set(regName, regPid.localId);
        return { suspend: false, next: k(undefined) };
      }
      case 'whereis': {
        const lookName = args[0];
        const found = _nodeRegistry.has(lookName)
          ? { _type: 'Some', value: Pid(_localNodeId, _nodeRegistry.get(lookName)) }
          : { _type: 'None' };
        return { suspend: false, next: k(found) };
      }
      case 'globalRegister': {
        const grName = args[0];
        const grPidRaw = args[1];
        if (grPidRaw && grPidRaw._type === 'Pid') {
          // v1.23 — stamp local nodeId on Pids that came back from a
          // local spawn (which sets nodeId='').  Without this the
          // broadcast payload's `nodeId` is empty and remote nodes
          // silently drop every cross-node send to this name.
          const grNid = grPidRaw.nodeId ? grPidRaw.nodeId : _localNodeId;
          const grPid = Pid(grNid, grPidRaw.localId);
          _globalRegistry.set(grName, grPid);
          const payload = JSON.stringify({ t: 'global_reg', name: grName, nodeId: grNid, localId: String(grPid.localId) });
          for (const [, peer] of _peerChannels) { try { peer.send(payload); } catch (_) {} }
        }
        return { suspend: false, next: k(undefined) };
      }
      case 'globalWhereis': {
        const gwName = args[0];
        const gwPid  = _globalRegistry.get(gwName);
        const found  = gwPid ? { _type: 'Some', value: gwPid } : { _type: 'None' };
        return { suspend: false, next: k(found) };
      }
      // v1.23 — cluster visibility
      case 'clusterMembers': {
        const mems = [];
        for (const [nid, peer] of _peerChannels) {
          if (peer && !peer.pending) mems.push(nid);
        }
        return { suspend: false, next: k(mems) };
      }
      case 'subscribeClusterEvents': {
        _clusterEventSubs.add(id);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — phi-accrual failure detector
      case 'phiOf': {
        return { suspend: false, next: k(_computePhi(String(args[0]))) };
      }
      case 'isSuspect': {
        const thr = args[1] == null ? 8.0 : Number(args[1]);
        return { suspend: false, next: k(_computePhi(String(args[0])) >= thr) };
      }
      // v1.23 — local node identity
      case 'selfNode': {
        return { suspend: false, next: k(_localNodeId) };
      }
      // v1.23 — cluster health (phi vector for connected peers)
      case 'clusterHealth': {
        const m = new Map();
        for (const [nid, peer] of _peerChannels) {
          if (peer && !peer.pending) m.set(nid, _computePhi(nid));
        }
        return { suspend: false, next: k(m) };
      }
      // v1.23 — cluster-wide FD: broadcast phi vector to peers.
      case 'broadcastHealth': {
        const view = [];
        for (const [nid, peer] of _peerChannels) {
          if (peer && !peer.pending) {
            const phi = _computePhi(nid);
            if (Number.isFinite(phi)) view.push([nid, phi]);
          }
        }
        const payload = JSON.stringify({ t: 'phi_vector', from: _localNodeId, view });
        for (const [, peer] of _peerChannels) {
          if (peer && !peer.pending) { try { peer.send(payload); } catch (_) {} }
        }
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — cluster-wide FD: majority vote of phi >= threshold across peers.
      case 'clusterIsDown': {
        const target = String(args[0]);
        const thr    = args[1] == null ? 8.0 : Number(args[1]);
        let votes = 0;
        let total = 0;
        if (_peerChannels.has(target) && _peerChannels.get(target) && !_peerChannels.get(target).pending) {
          total += 1;
          if (_computePhi(target) >= thr) votes += 1;
        }
        for (const [peerNid, peerView] of _peerPhiViews) {
          if (peerNid === target) continue;
          const p = peerView.get(target);
          if (p != null) { total += 1; if (p >= thr) votes += 1; }
        }
        const majority = Math.floor((total + 1) / 2);
        return { suspend: false, next: k(total > 0 && votes >= majority) };
      }
      // v1.23 — leader election (Bully or Raft, picked by _leaderProtocol)
      case 'electLeader': {
        if (_leaderProtocol === 'raft') _startRaftElection();
        else _startElection();
        return { suspend: false, next: k(undefined) };
      }
      case 'currentLeader': {
        if (_leaderProtocol === 'raft') return { suspend: false, next: k(_raftLeaderId) };
        if (_leaderProtocol === 'coord') {
          let held = "";
          if (_coordHolderFn) {
            try {
              const opt = _coordHolderFn();
              // Option emits as { _type: 'Some', value } or { _type: 'None' }.
              if (opt && opt._type === 'Some') held = String(opt.value || "");
            } catch (_) {}
          }
          return { suspend: false, next: k(held) };
        }
        return { suspend: false, next: k(_currentLeader) };
      }
      case 'subscribeLeaderEvents': {
        _leaderEventSubs.add(id);
        return { suspend: false, next: k(undefined) };
      }
      case 'setAutoReelect': {
        _autoReelect = !!args[0];
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — protocol switch + history (cluster-raft.md §6)
      case 'useRaftLeaderElection': {
        _leaderProtocol = 'raft';
        _raftLoad();
        _raftStateName  = 'follower';
        _raftElectionDue = Date.now() + _raftRandTimeout();
        _ensureRaftTickThread();
        return { suspend: false, next: k(undefined) };
      }
      case 'useExternalCoordinator': {
        _leaderProtocol = 'coord';
        if (args.length >= 4) {
          _coordAcquireFn = typeof args[0] === 'function' ? args[0] : null;
          _coordRenewFn   = typeof args[1] === 'function' ? args[1] : null;
          _coordReleaseFn = typeof args[2] === 'function' ? args[2] : null;
          _coordHolderFn  = typeof args[3] === 'function' ? args[3] : null;
          if (_coordAcquireFn) {
            let got = false;
            try { got = !!_coordAcquireFn(_localNodeId, _COORD_LEASE_TIMEOUT_MS); } catch (_) {}
            if (got) {
              _coordIsLeader = true;
              const prev = _currentLeader;
              _currentLeader = _localNodeId;
              if (prev !== _localNodeId) {
                _fireLeaderEvent("LeaderElected", _localNodeId);
                _recordLeaderHist(_localNodeId);
              }
            }
            _ensureCoordTickThread();
          }
        }
        return { suspend: false, next: k(undefined) };
      }
      case 'leaderProtocol': {
        return { suspend: false, next: k(_leaderProtocol) };
      }
      case 'leaderHistory': {
        // Return a copy so callers can't mutate our buffer.
        return { suspend: false, next: k(_leaderHist.map(e => [e[0], e[1], e[2]])) };
      }
      // v1.23 — auto-reconnect policy
      case 'setReconnectPolicy': {
        const ini = Number(args[0]) | 0;
        const mx  = Number(args[1]) | 0;
        // v1.23 — optional 3rd arg: total wall-clock retry budget per
        // URL; 0 = no cap (retry forever).
        const giveUp = args.length > 2 ? (Number(args[2]) | 0) : 0;
        _reconnectInitialMs = Math.max(0, ini);
        _reconnectMaxMs     = Math.max(_reconnectInitialMs, mx);
        _reconnectGiveUpMs  = Math.max(0, giveUp);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — heartbeat cadence + dead-after
      case 'setHeartbeatTimeout': {
        const iv   = Number(args[0]) | 0;
        const dead = Number(args[1]) | 0;
        _peerHeartbeatIntervalMs  = Math.max(1, iv);
        _peerHeartbeatDeadAfterMs = Math.max(_peerHeartbeatIntervalMs, dead);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — quorum-aware Bully threshold
      case 'setQuorumSize': {
        const n = Number(args[0]) | 0;
        _quorumSize = Math.max(0, n);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — shared-secret Bearer token for /_ssc-cluster/* endpoints.
      case 'setClusterAuthToken': {
        _clusterAuthToken = args[0] != null ? String(args[0]) : '';
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — periodic gossip re-discovery
      case 'requestGossip': {
        const payload = '{"t":"peers_req","from":' + JSON.stringify(_localNodeId) + '}';
        for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — cluster configuration distribution
      case 'clusterConfigSet': {
        const key   = String(args[0]);
        const value = String(args[1]);
        const ts    = Date.now();
        _applyConfigUpdate(key, value, ts, _localNodeId);
        const payload = JSON.stringify({ t: 'config_set', key, value, ts, origin: _localNodeId });
        for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
        return { suspend: false, next: k(undefined) };
      }
      case 'clusterConfigGet': {
        const entry = _clusterConfig.get(String(args[0]));
        const result = entry ? { _type: 'Some', value: entry.value } : { _type: 'None' };
        return { suspend: false, next: k(result) };
      }
      case 'clusterConfigKeys': {
        return { suspend: false, next: k(Array.from(_clusterConfig.keys())) };
      }
      case 'subscribeConfigEvents': {
        _configEventSubs.add(id);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — drain / rolling-restart
      case 'setDraining': {
        const b = !!args[0];
        const prev = _isDrainingSelf;
        _isDrainingSelf = b;
        if (prev !== b) {
          const payload = JSON.stringify({ t: 'drain', from: _localNodeId, draining: b });
          for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
          _fireDrainEvent(_localNodeId, b);
          if (b) _stepDownIfLeader();
        }
        return { suspend: false, next: k(undefined) };
      }
      case 'isDraining': {
        return { suspend: false, next: k(_isDrainingSelf) };
      }
      case 'drainingPeers': {
        const buf = [];
        for (const [nid, dr] of _drainingPeers) if (dr) buf.push(nid);
        return { suspend: false, next: k(buf) };
      }
      case 'subscribeDrainEvents': {
        _drainEventSubs.add(id);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — cluster metrics aggregation
      case 'clusterMetricSet': {
        const name = String(args[0]);
        const value = Number(args[1]);
        _applyMetricUpdate(name, _localNodeId, value);
        const payload = JSON.stringify({ t: 'metric', from: _localNodeId, name, value });
        for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
        return { suspend: false, next: k(undefined) };
      }
      case 'clusterMetricGet': {
        const inner = _clusterMetrics.get(String(args[0]));
        const m = new Map();
        if (inner) for (const [nid, v] of inner) m.set(nid, v);
        return { suspend: false, next: k(m) };
      }
      case 'clusterMetricSum': {
        const inner = _clusterMetrics.get(String(args[0]));
        let sum = 0;
        if (inner) for (const v of inner.values()) sum += v;
        return { suspend: false, next: k(sum) };
      }
      case 'clusterMetricNames': {
        return { suspend: false, next: k(Array.from(_clusterMetrics.keys())) };
      }
      case 'subscribeMetricEvents': {
        _metricEventSubs.add(id);
        return { suspend: false, next: k(undefined) };
      }
      // v1.6.x — scheduled sends
      case 'sendAfter': {
        const delayMs  = args[0];
        const tgtPid   = args[1];
        const tMsg     = args[2];
        const fireAt   = Date.now() + delayMs;
        const tRef     = _nextTimerId++;
        _timers.set(tRef, { fireAt, period: null, targetId: tgtPid.localId, msg: tMsg });
        return { suspend: false, next: k(tRef) };
      }
      case 'sendInterval': {
        const periodMs = args[0];
        const tgtPid2  = args[1];
        const tMsg2    = args[2];
        const fireAt2  = Date.now() + periodMs;
        const tRef2    = _nextTimerId++;
        _timers.set(tRef2, { fireAt: fireAt2, period: periodMs, targetId: tgtPid2.localId, msg: tMsg2 });
        return { suspend: false, next: k(tRef2) };
      }
      case 'cancelTimer': {
        _timers.delete(args[0]);
        return { suspend: false, next: k(undefined) };
      }
      default:
        throw new Error('Unknown Actor op: ' + op);
    }
  }

  function stepActor(id) {
    const state = actors.get(id);
    if (!state) return;
    let current = state.pending;
    state.pending = null;
    while (true) {
      if (current instanceof _Perform) {
        if (current.eff !== 'Actor')
          throw new Error('Unhandled effect inside actor: ' + current.eff + '.' + current.op);
        const r = handleActorOp(id, state, current.op, current.args, (v) => v);
        if (r.suspend) return;
        current = r.next;
      } else if (current instanceof _FlatMap) {
        const sub = current.sub;
        if (sub instanceof _FlatMap) {
          const sub2 = sub.sub, g = sub.k, f = current.k;
          current = new _FlatMap(sub2, (x) => new _FlatMap(g(x), f));
        } else if (sub instanceof _Perform) {
          if (sub.eff !== 'Actor')
            throw new Error('Unhandled effect inside actor: ' + sub.eff + '.' + sub.op);
          const r = handleActorOp(id, state, sub.op, sub.args, current.k);
          if (r.suspend) return;
          current = r.next;
        } else {
          current = current.k(sub);
        }
      } else {
        // Pure value — actor done normally; fire monitors with reason "normal".
        if (id === rootId) rootResult = current;
        const myPid = Pid(_localNodeId, id);
        const monMap = monitors.get(id);
        monitors.delete(id);
        if (monMap) {
          for (const [monRef, observerId] of monMap) {
            const st = actors.get(observerId);
            if (st) {
              st.mailbox.push({ _type: 'Down', ref: monRef, from: myPid, reason: 'normal' });
              tryWakeBlocked(observerId);
            }
          }
        }
        // Clean up link entries (normal exit does not propagate link signals).
        const linkedSet = links.get(id);
        links.delete(id);
        if (linkedSet) {
          for (const linkedId of linkedSet) {
            const ls = links.get(linkedId);
            if (ls) ls.delete(id);
          }
        }
        actors.delete(id);
        return;
      }
    }
  }

  // Lightweight yield to Node's event loop.  `setImmediate` runs after
  // pending I/O callbacks (HTTP request handlers, WS frames, accept'ed
  // connections) drain — exactly what we need so a `serveAsync(...)`
  // bound from inside the actor body becomes reachable while the
  // scheduler keeps spinning.  Falls back to a resolved Promise when
  // `setImmediate` is missing (non-Node hosts), which still yields one
  // microtask between iterations.
  const _yieldToIO = () => (typeof setImmediate === 'function')
    ? new Promise(r => setImmediate(r))
    : Promise.resolve();
  while (true) {
    // Drain peer ring buffers then node-down queue before each scheduler tick.
    if (_peerChannels.size > 0) {
      _drainPeerBuffers();
      _processRemoteInbox();
    }
    while (_nodeDownQueue.length > 0) _fireNodeDown(_nodeDownQueue.shift());
    // v1.23 — deliver cluster events to subscribers.
    while (_clusterEventQueue.length > 0) {
      const _ev = _clusterEventQueue.shift();
      const _msg = _ev.tag === 'NodeJoined'
        ? { _type: 'NodeJoined', nodeId: _ev.nodeId }
        : { _type: 'NodeLeft',   nodeId: _ev.nodeId, reason: _ev.reason };
      for (const _sid of _clusterEventSubs) {
        const _st = actors.get(_sid);
        if (_st) { _st.mailbox.push(_msg); tryWakeBlocked(_sid); }
      }
    }
    // v1.23 — Bully election timeout: claim self if no higher-id peer responded.
    if (_electionInProgress && Date.now() - _electionStartedAt >= _ELECTION_TIMEOUT_MS) {
      _electionInProgress = false;
      // v1.23 — quorum gate: same as `_startElection.higher.length === 0`
      // branch — even though no higher peer responded, decline self-
      // claim when below quorum.
      if (!_gotAliveResponse && _hasQuorum()) {
        const _prev = _currentLeader;
        _currentLeader = _localNodeId;
        _broadcastCoordinator();
        if (_prev !== _localNodeId) { _fireLeaderEvent("LeaderElected", _localNodeId); _recordLeaderHist(_localNodeId); }
      }
    }
    // v1.23 — deliver leader events to subscribers.
    while (_leaderEventQueue.length > 0) {
      const _lev = _leaderEventQueue.shift();
      const _lmsg = _lev.tag === 'LeaderElected'
        ? { _type: 'LeaderElected', nodeId: _lev.leaderId }
        : { _type: 'LeaderLost',    nodeId: _lev.leaderId };
      for (const _sid of _leaderEventSubs) {
        const _st = actors.get(_sid);
        if (_st) { _st.mailbox.push(_lmsg); tryWakeBlocked(_sid); }
      }
    }
    // v1.23 — deliver config-change events to subscribers.
    while (_configEventQueue.length > 0) {
      const _cev = _configEventQueue.shift();
      const _cmsg = { _type: 'ConfigChanged', key: _cev.key, value: _cev.value };
      for (const _sid of _configEventSubs) {
        const _st = actors.get(_sid);
        if (_st) { _st.mailbox.push(_cmsg); tryWakeBlocked(_sid); }
      }
    }
    // v1.23 — deliver drain-state events to subscribers.
    while (_drainEventQueue.length > 0) {
      const _dev = _drainEventQueue.shift();
      const _dmsg = { _type: 'DrainStateChanged', nodeId: _dev.nodeId, draining: _dev.draining };
      for (const _sid of _drainEventSubs) {
        const _st = actors.get(_sid);
        if (_st) { _st.mailbox.push(_dmsg); tryWakeBlocked(_sid); }
      }
    }
    // v1.23 — deliver metric events to subscribers.
    while (_metricEventQueue.length > 0) {
      const _mev = _metricEventQueue.shift();
      const _mmsg = { _type: 'MetricChanged', name: _mev.name, nodeId: _mev.nodeId, value: _mev.value };
      for (const _sid of _metricEventSubs) {
        const _st = actors.get(_sid);
        if (_st) { _st.mailbox.push(_mmsg); tryWakeBlocked(_sid); }
      }
    }
    // Fire scheduled sends whose deadline has passed.
    if (_timers.size > 0) {
      const _nowMs = Date.now();
      for (const [_tRef, _te] of Array.from(_timers)) {
        if (_nowMs >= _te.fireAt) {
          const _tSt = actors.get(_te.targetId);
          if (_tSt) { _tSt.mailbox.push(_te.msg); tryWakeBlocked(_te.targetId); }
          if (_te.period != null) {
            _timers.set(_tRef, { fireAt: _te.fireAt + _te.period, period: _te.period,
                                  targetId: _te.targetId, msg: _te.msg });
          } else {
            _timers.delete(_tRef);
          }
        }
      }
    }
    if (ready.length > 0) {
      const id = ready.shift();
      const state = actors.get(id);
      if (state && state.pending !== null) stepActor(id);
      // When distributed, give Node's event loop a tick between every
      // actor step — peer-channel I/O, accept'ed HTTP connections, and
      // remote-inbox writes from worker threads all flow through the
      // libuv queue, and a tight `ready.shift() → stepActor` loop would
      // otherwise starve them.  Pure-actor (non-distributed) workloads
      // skip this — the scheduler runs straight through and matches the
      // pre-async tick performance to within a few %.
      if (_peerChannels.size > 0) await _yieldToIO();
    } else {
      // Quiescence — but timeout-armed receives, timers, remote messages, or node-downs may fire.
      if (_nodeDownQueue.length > 0) continue;
      let earliest = null;
      for (const [aid, st] of actors) {
        if (st && st.blocked && st.blocked.deadline != null) {
          if (earliest === null || st.blocked.deadline < earliest.d)
            earliest = { id: aid, d: st.blocked.deadline };
        }
      }
      // Earliest pending timer deadline.
      let _timerMin = null;
      for (const [, _te] of _timers) {
        if (_timerMin === null || _te.fireAt < _timerMin) _timerMin = _te.fireAt;
      }
      // Distributed: keep running while peer channels are open and actors are blocked.
      const isDistributed = _peerChannels.size > 0;
      const hasBlockedActors = isDistributed && actors.size > 0 &&
        [...actors.values()].some(st => st && st.blocked !== null);
      if (!earliest && _timers.size === 0 && !hasBlockedActors &&
          !_electionInProgress && _leaderEventQueue.length === 0 &&
          _configEventQueue.length === 0 && _drainEventQueue.length === 0 &&
          _metricEventQueue.length === 0) break;
      const _sleepUntil = [earliest != null ? earliest.d : null, _timerMin].filter(v => v != null);
      const _minDeadline = _sleepUntil.length > 0 ? Math.min(..._sleepUntil) : null;
      const sleepFor = _minDeadline != null ? Math.min(_minDeadline - Date.now(), 10) : 10;
      // Promise-based sleep — `setTimeout(r, ms)` registers a libuv
      // timer and `await`-ing the Promise releases the main thread, so
      // Node's event loop can dispatch HTTP/WS callbacks, ring-buffer
      // writes from peer workers, and any other libuv-driven I/O.
      // Previously this was `_asyncSleep` (Atomics.wait on the main
      // thread), which Node ≥ 16 permits but blocks the event loop
      // entirely — every HTTP request `accept`ed by libuv queued and
      // never dispatched, making `/_ssc-cluster/*` endpoints
      // unreachable for the duration of the sleep.
      if (sleepFor > 0) await new Promise(r => setTimeout(r, sleepFor));
      else              await _yieldToIO();
      if (earliest != null) {
        const now = Date.now();
        const s = actors.get(earliest.id);
        if (s && s.blocked && s.blocked.deadline != null && now >= s.blocked.deadline) {
          const kk = s.blocked.k;
          s.blocked = null;
          s.pending = kk(_None);
          ready.push(earliest.id);
        }
      }
    }
  }
  // v1.23 — graceful cluster shutdown: release the coord lease if we
  // hold it, so the next leader can claim immediately.
  if (_leaderProtocol === 'coord' && _coordIsLeader && _coordReleaseFn) {
    try { _coordReleaseFn(_localNodeId); } catch (_) {}
    _coordIsLeader = false;
  }
  // Clear tick intervals so they don't leak across reused processes.
  if (_raftTickHandle  != null) { clearInterval(_raftTickHandle);  _raftTickHandle  = null; }
  if (_coordTickHandle != null) { clearInterval(_coordTickHandle); _coordTickHandle = null; }
  return rootResult;
}

// ── v1.10 Generator — pull-based lazy streams via JS native generators ────
// generator { () => ...; suspend(v); ... } lowers to
//   _makeGenerator(function*() { ...; yield v; ... })
// The returned object wraps the JS iterator with the ScalaScript API.
function _makeGenerator(genFn) {
  const iter = genFn();
  function _wrap(iter2) {
    return {
      next()    { const r = iter2.next(); return r.done ? null : { _isSome: true, _value: r.value }; },
      nextOpt() { const r = iter2.next(); return r.done ? _None : _Some(r.value); },
      foreach(f) { for (const v of { [Symbol.iterator]() { return iter2; } }) f(v); },
      toList()  { const a = []; for (const v of { [Symbol.iterator]() { return iter2; } }) a.push(v); return a; },
      map(f)    { return _wrap((function*(it) { for (const v of { [Symbol.iterator]() { return it; } }) yield f(v); })(iter2)); },
      filter(p) { return _wrap((function*(it) { for (const v of { [Symbol.iterator]() { return it; } }) if (p(v)) yield v; })(iter2)); },
      take(n)   { return _wrap((function*(it) { let i=0; for (const v of { [Symbol.iterator]() { return it; } }) { if(i++>=n) break; yield v; } })(iter2)); },
      drop(n)   { return _wrap((function*(it) { let i=0; for (const v of { [Symbol.iterator]() { return it; } }) { if(i++<n) continue; yield v; } })(iter2)); },
      flatMap(f){ return _wrap((function*(it) { for (const v of { [Symbol.iterator]() { return it; } }) { const inner = f(v); yield* { [Symbol.iterator]() { const i2 = inner._iter(); return { next() { const r = i2.next(); return r; } }; } }; } })(iter2)); },
      zip(other){ return _wrap((function*(it) { const oit = other._iter(); for (const v of { [Symbol.iterator]() { return it; } }) { const ob = oit.next(); if (ob.done) break; const t = [v, ob.value]; t._isTuple=true; yield t; } })(iter2)); },
      zipWithIndex(){ return _wrap((function*(it) { let i=0; for (const v of { [Symbol.iterator]() { return it; } }) { const t=[v,i++]; t._isTuple=true; yield t; } })(iter2)); },
      _iter()   { return iter2; },
    };
  }
  return _wrap(iter);
}

// ── v1.51.2 Streams — backpressured Source[A] via async function* ──────────
// stream { emit(x) } compiles to _makeAsyncStream((async function*() { body })())
// emit(x) inside the body lowers to yield x (same as suspend for generators).
// Terminal ops (runForeach, runFold, runToList, runDrain) are async and must be
// awaited at the call site — JsGen auto-inserts await at statement level.
function _makeAsyncStream(iter) {
  const self = {
    [Symbol.asyncIterator]() { return iter; },
    map(f)    { return _makeAsyncStream((async function*(it) { for await (const v of it) yield f(v); })(iter)); },
    filter(p) { return _makeAsyncStream((async function*(it) { for await (const v of it) if (p(v)) yield v; })(iter)); },
    take(n)   { return _makeAsyncStream((async function*(it) { let i=0; for await (const v of it) { if (i++>=n) break; yield v; } })(iter)); },
    drop(n)   { return _makeAsyncStream((async function*(it) { let i=0; for await (const v of it) { if (i++<n) continue; yield v; } })(iter)); },
    flatMap(f){ return _makeAsyncStream((async function*(it) { for await (const v of it) { for await (const x of f(v)) yield x; } })(iter)); },
    concat(other){ return _makeAsyncStream((async function*(it) { for await (const v of it) yield v; for await (const v of other) yield v; })(iter)); },
    zip(other){ return _makeAsyncStream((async function*(it) { const it2 = other[Symbol.asyncIterator](); for await (const v of it) { const b = await it2.next(); if (b.done) break; const t=[v,b.value]; t._isTuple=true; yield t; } })(iter)); },
    async runForeach(f) { for await (const v of iter) f(v); },
    async runFold(z)    { return async (f) => { let acc = z; for await (const v of iter) acc = f(acc, v); return acc; }; },
    async runToList()   { const a = []; for await (const v of iter) a.push(v); return a; },
    async runDrain()    { for await (const _ of iter) {} },
    // ── v1.51.1 scan / onError / cancellable ─────────────────────────────
    scan(z)    { return (f) => _makeAsyncStream((async function*(it) { let acc=z; for await (const v of it) { acc=f(acc,v); yield acc; } })(iter)); },
    onError(f) { return _makeAsyncStream((async function*(it) { try { for await (const v of it) yield v; } catch(e) { f(e&&e.message||String(e)); } })(iter)); },
    cancellable() { let _c=false; const src=_makeAsyncStream((async function*(it) { try { for await (const v of it) { if(_c) break; yield v; } } catch(e){} })(iter)); const t=[src,()=>{_c=true;}]; t._isTuple=true; return t; },
    // ── v1.51.2 combining operators ───────────────────────────────────────
    merge(other)    { return _makeAsyncStream((async function*(it) { for await (const v of it) yield v; for await (const v of other) yield v; })(iter)); },
    zipWith(other)  { return (f) => _makeAsyncStream((async function*(it) { const it2=other[Symbol.asyncIterator](); for await (const v of it) { const b=await it2.next(); if(b.done) break; yield f(v,b.value); } })(iter)); },
    async broadcast(n) { const a=[]; for await (const v of iter) a.push(v); return Array.from({length:n},()=>_makeAsyncStream((async function*(xs){for(const v of xs)yield v;})(a))); },
    async balance(n)   { const a=[]; for await (const v of iter) a.push(v); return Array.from({length:n},(_,i)=>_makeAsyncStream((async function*(xs){for(const v of xs)yield v;})(a.filter((_,j)=>j%n===i)))); },
    async groupBy(kf)  { const a=[]; for await (const v of iter) a.push(v); const g=new Map(); for(const v of a){const k=kf(v);if(!g.has(k))g.set(k,[]);g.get(k).push(v);} const ps=[]; for(const [k,vs] of g){const t=[k,_makeAsyncStream((async function*(xs){for(const v of xs)yield v;})(vs))];t._isTuple=true;ps.push(t);} return _makeAsyncStream((async function*(arr){for(const v of arr)yield v;})(ps)); },
    mergeSubstreams()  { return _makeAsyncStream((async function*(it) { for await (const v of it) { for await (const x of v) yield x; } })(iter)); },
    // ── v1.51.2 advanced operators ────────────────────────────────────────
    buffer(n,s)    { return this; },
    throttle(rate) { return _makeAsyncStream((async function*(it) { let cnt=0,start=Date.now(); for await (const v of it) { cnt++; if(cnt>rate.elements){const e=Date.now()-start;if(e<rate.perMillis)await new Promise(r=>setTimeout(r,rate.perMillis-e));start=Date.now();cnt=1;} yield v; } })(iter)); },
    debounce(ms)   { return _makeAsyncStream((async function*(it) { let last,has=false; for await (const v of it){last=v;has=true;} if(has){await new Promise(r=>setTimeout(r,ms));yield last;} })(iter)); },
    mapAsync(n)    { return (f) => _makeAsyncStream((async function*(it) { const a=[]; for await (const v of it) a.push(v); const r=await Promise.all(a.map(f)); for(const v of r) yield v; })(iter)); },
    recover(h)     { return _makeAsyncStream((async function*(it) { try { for await (const v of it) yield v; } catch(e) { yield h(e&&e.message||String(e)); } })(iter)); },
    mapError(f)    { return _makeAsyncStream((async function*(it) { try { for await (const v of it) yield v; } catch(e) { f(e&&e.message||String(e)); } })(iter)); },
    // ── v1.51.2 routing ───────────────────────────────────────────────────
    async to(sink) { return sink.run(this); },
    via(flow)      { return flow.apply(this); },
  };
  return self;
}

// ── v1.9 Coroutine primitive — JS native generators ────────────────────────
// coroutineCreate(fn) wraps a function* generator; coroutineResume steps it.
// suspend(v) inside a coroutineCreate body compiles to `yield v` (JsGen
// emits it via genGeneratorBody / genGenExpr, same path as generator bodies).
function Yielded(value)   { return { _type: 'Yielded',   value }; }
function Returned(value)  { return { _type: 'Returned',  value }; }
function Errored(message) { return { _type: 'Errored',  message }; }

function _coroutineCreate(genFn) {
  const gen = genFn();
  return { _type: '_Coroutine', _gen: gen, _done: false };
}

function _coroutineResume(co, input) {
  if (co._done) throw new Error('coroutineResume: coroutine already completed');
  let r;
  try { r = co._gen.next(input); }
  catch (e) { co._done = true; return Errored(e.message || String(e)); }
  if (r.done) { co._done = true; return Returned(r.value); }
  return Yielded(r.value);
}
// Runtime stub: suspend called outside a generator/coroutine body throws.
// genGenExpr rewrites suspend(v) → (yield v) inside function* bodies.
function suspend(v) { throw new Error('suspend called outside a coroutine or generator body'); }

// ── Storage: built-in key-value effect ────────────────────────────────────
//
// `Storage.{get,put,remove,has,keys}` produce `_Perform("Storage", op,
// args)` nodes; `_runStorage(bodyFn, path)` is the handler — when
// `path` is non-null it hydrates from / flushes to a JSON file on
// every mutation (file-backed mode), otherwise it stays in memory
// (ephemeral mode, for tests).  Same Free-Monad walking shape as
// `_runAsync`; non-Storage Performs propagate outward so an outer
// handler picks them up.
const Storage = {
  get:    (key)        => _perform('Storage', 'get',    [key]),
  put:    (key, value) => _perform('Storage', 'put',    [key, value]),
  remove: (key)        => _perform('Storage', 'remove', [key]),
  has:    (key)        => _perform('Storage', 'has',    [key]),
  keys:   ()           => _perform('Storage', 'keys',   []),
};

function _storageDefaultPath() {
  if (typeof process !== 'undefined' && process.env && process.env.SSC_STORAGE_PATH) {
    return process.env.SSC_STORAGE_PATH;
  }
  return './ssc-storage.json';
}

function _storageLoad(path, state) {
  if (typeof require === 'undefined') return;
  try {
    const fs = require('fs');
    if (!fs.existsSync(path)) return;
    const src = fs.readFileSync(path, 'utf-8');
    const obj = JSON.parse(src);
    for (const [k, v] of Object.entries(obj)) state.set(k, String(v));
  } catch (e) { /* corrupt file — start empty */ }
}

function _storageSave(path, state) {
  if (typeof require === 'undefined') return;
  const fs  = require('fs');
  const obj = {};
  for (const [k, v] of state) obj[k] = v;
  fs.writeFileSync(path, JSON.stringify(obj));
}

function _runStorage(bodyFn, path) {
  const state = new Map();
  if (path) _storageLoad(path, state);
  function flush() { if (path) _storageSave(path, state); }

  function dispatch(op, args, resume) {
    switch (op) {
      case 'get': {
        const k = args[0];
        return resume(state.has(k) ? _Some(state.get(k)) : _None);
      }
      case 'put': {
        const k = args[0], v = args[1];
        state.set(k, _show(v));
        flush();
        return resume(undefined);
      }
      case 'remove': {
        state.delete(args[0]);
        flush();
        return resume(undefined);
      }
      case 'has':  return resume(state.has(args[0]));
      case 'keys': return resume([...state.keys()]);
      default:     throw new Error('Unknown Storage operation: ' + op);
    }
  }

  function interp(initial) {
    let current = initial;
    while (true) {
      if (current instanceof _Perform) {
        if (current.eff === 'Storage') {
          current = dispatch(current.op, current.args, (v) => v);
        } else { return current; }
      } else if (current instanceof _FlatMap) {
        const sub = current.sub;
        if (sub instanceof _FlatMap) {
          const sub2 = sub.sub, g = sub.k, f = current.k;
          current = new _FlatMap(sub2, (x) => new _FlatMap(g(x), f));
        } else if (sub instanceof _Perform) {
          const f = current.k;
          if (sub.eff === 'Storage') {
            current = dispatch(sub.op, sub.args, (v) => interp(f(v)));
          } else {
            return new _FlatMap(sub, (v) => interp(f(v)));
          }
        } else {
          current = current.k(sub);
        }
      } else {
        return current;
      }
    }
  }
  return interp(bodyFn());
}

// ── CPS-aware collection helpers ──────────────────────────────────────────
//
// When a higher-order method like `xs.map(fn)` is called inside an
// effectful context, `fn(x)` may return a Free tree instead of a plain
// value.  These helpers detect that and stitch the per-element Free
// values into a single sequenced Free that yields the final array.
// Pure callbacks pass straight through with no overhead.
// (_seq, _seqMap, _seqFlatMap, _seqFilter are defined in JsRuntimePart2 so
// they are available in the base runtime too.)
function _seqForeach(arr, fn) {
  const comps = arr.map(x => fn(x));
  const s     = _seq(comps);
  if (_isFree(s)) return _bind(s, () => undefined);
  return undefined;
}
function _seqExists(arr, fn) {
  const seq = _seq(arr.map(x => fn(x)));
  if (_isFree(seq)) return _bind(seq, (bs) => bs.some(b => b));
  return seq.some(b => b);
}
function _seqForall(arr, fn) {
  const seq = _seq(arr.map(x => fn(x)));
  if (_isFree(seq)) return _bind(seq, (bs) => bs.every(b => b));
  return seq.every(b => b);
}
function _seqCount(arr, fn) {
  const seq = _seq(arr.map(x => fn(x)));
  if (_isFree(seq)) return _bind(seq, (bs) => bs.filter(b => b).length);
  return seq.filter(b => b).length;
}
function _seqFind(arr, fn) {
  const seq = _seq(arr.map(x => fn(x)));
  const pick = (bs) => {
    const i = bs.findIndex(b => b);
    return i < 0 ? _None : _Some(arr[i]);
  };
  if (_isFree(seq)) return _bind(seq, pick);
  return pick(seq);
}
function _seqFoldLeft(arr, init, fn) {
  function loop(i, acc) {
    if (i === arr.length) return acc;
    const next = fn(acc, arr[i]);
    if (_isFree(next)) return _bind(next, (v) => loop(i + 1, v));
    return loop(i + 1, next);
  }
  return loop(0, init);
}
"""



class JsGen(
    baseDir:    Option[os.Path] = None,
    intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
    lockPath:   Option[os.Path] = None,
    // Shared across parent + all child generators to track which top-level `const X` names
    // have been declared.  When two package-qualified imports share the same top-level namespace
    // (e.g. std.ui.primitives and std.ui.nodes both wrap content in `object std { ... }`), the
    // second occurrence merges via _ssc_mergeDeep instead of re-declaring the const.
    private[codegen] val topLevelConsts: mutable.Set[String] = mutable.Set.empty,
    private[codegen] val mergeHelperEmitted: Array[Boolean]  = Array(false),
    // Shared across parent + all child generators to track which import-binding `const` names
    // have been declared.  A module imported transitively (e.g. nodes.ssc pulled in by both
    // primitives.ssc and layout.ssc) must not emit duplicate `const TkNode = …` lines.
    private[codegen] val declaredBindings: mutable.Set[String] = mutable.Set.empty,
    // When Some(set), only top-level declarations whose name is in the set are emitted.
    // None means no filtering (tree-shaking disabled — emit everything).
    // Populated by TreeShaker.shake() and threaded from the companion object entry points.
    private[codegen] val reachableNames: Option[Set[String]] = None) extends JsGenAnalysisQueries:
  import scala.meta.*

  private[codegen] val sb = StringBuilder()
  private var indent = 0
  private var tmpIdx = 0
  private var hasMain = false
  private var mainCalled = false
  // Active parameter renames for JS reserved-word param names (e.g. `default` → `default_p`).
  private val paramRenames = mutable.Map.empty[String, String]
  // Set when the module uses runAsyncParallel; causes the user code sections to
  // be wrapped in a top-level async IIFE so `await _runAsyncParallel(...)` works.
  private var usesRunAsyncParallel: Boolean = false
  // Set when the module uses runActors; same async-IIFE wrap so
  // `await _runActors(...)` works.  The async-aware `_runActors`
  // yields to Node's event loop between scheduler ticks (see the
  // runtime emission of `_runActors` in `JsRuntimeAsync`) so that
  // libuv-accepted HTTP requests still drain while actors are
  // long-blocked on a `receive` deadline.  Detected by
  // `scanForRunActors` and combined into the `needsAsync` decision
  // alongside `usesRunAsyncParallel` and `needSqlPreamble`.
  private var usesRunActors: Boolean = false
  // Set when client/browser code uses `awaitClient(promise)`.  This helper
  // lowers directly to JS `await` and therefore needs the top-level async IIFE.
  private var usesAwaitClient: Boolean = false
  // Set when the module uses stream terminal operations (runForeach, runFold,
  // runToList, runDrain) — these are async and need the top-level async IIFE.
  private var usesStreams: Boolean = false
  // Stack of placeholder counters: each AnonymousFunction pushes 0, Placeholder increments top
  private var phCounters: List[Int] = Nil
  // Names of variables known to hold integer values (for integer division detection)
  private val intVars = scala.collection.mutable.Set[String]()
  // fname → set of all group members (populated by analyzeMutualRecursion before emit)
  private var mutualGroups: Map[String, Set[String]] = Map.empty
  // Effect operations declared in the module, as "Eff.op" strings.
  private[codegen] val effectOps: scala.collection.mutable.Set[String] = scala.collection.mutable.Set.empty
  // Functions that transitively perform effects — emitted in CPS form so callers
  // get a Free value (Pure plain value or Perform node) and can compose them.
  private[codegen] val effectfulFuns: scala.collection.mutable.Set[String] = scala.collection.mutable.Set.empty
  // Effect object names that carry the `val __multiShot__ = true` marker.
  private val multiShotEffects: scala.collection.mutable.Set[String] = scala.collection.mutable.Set.empty
  // Maps summon key "TC_A" → local param name "A$TC" for context-bound params
  // of the current Defn.Def being emitted. Cleared before each function.
  private val cbSummonMap = scala.collection.mutable.Map.empty[String, String]
  // funcParamOrder: function name → ordered parameter names.
  // Populated by collectFuncParamOrders before the main emit pass.
  // Used at call sites with named args to reorder them to positional order.
  private val funcParamOrder = scala.collection.mutable.Map.empty[String, List[String]]
  // v1.27 Phase 3 — sql block emission state.  Mirrors JvmGen's
  // `sqlBlockCounter` / `sqlPerSection`: sequential `_sqlBlock_<n>`
  // names, and per-section "first-only" tracking so only the first
  // `sql` block in each section gets the friendly `<sectionId>.sql`
  // alias.  Cleared at the start of every `genModule` call.
  private var sqlBlockCounter: Int = 0
  private val sqlPerSection = scala.collection.mutable.Map.empty[String, Int]

  private def freshTmp(): String =
    tmpIdx += 1
    s"_t$tmpIdx"

  private def line(s: String): Unit =
    sb.append("  " * indent).append(s).append("\n")

  // ─── Named-arg param-order collection ────────────────────────────
  //
  // Pre-pass over all Defn.Def nodes in the module to record the ordered
  // parameter list for each user-defined function.  At call sites that
  // pass named args (Term.Assign), genApply consults funcParamOrder to
  // reorder the arguments into the declared positional order before
  // emitting a regular positional JS call.

  private def collectFuncParamOrders(module: Module): Unit =
    funcParamOrder.clear()
    def collectDefs(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Def =>
        val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value).toList
        if params.nonEmpty then funcParamOrder(d.name.value) = params
      case _ => ()
    }
    def scanSection(section: Section): Unit =
      section.content.foreach {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.foreach { node =>
            ScalaNode.fold(node) {
              case Source(stats)     => collectDefs(stats)
              case Term.Block(stats) => collectDefs(stats)
              case _                 => ()
            }
          }
        case _ => ()
      }
      section.subsections.foreach(scanSection)
    module.sections.foreach(scanSection)

  // ─── Mutual recursion analysis ───────────────────────────────────

  private def analyzeMutualRecursion(module: Module): Unit =
    val funcs = mutable.Map[String, Set[String]]()

    def collectFuncs(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Def if d.paramClauseGroups.nonEmpty =>
        funcs(d.name.value) = tailCallTargets(d.body, d.name.value, tailPos = true)
      case _ => ()
    }

    def scanSection(section: Section): Unit =
      section.content.foreach {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.foreach { node =>
            ScalaNode.fold(node) {
              case Source(stats)     => collectFuncs(stats)
              case Term.Block(stats) => collectFuncs(stats)
              case _                 => ()
            }
          }
        case _ => ()
      }
      section.subsections.foreach(scanSection)

    module.sections.foreach(scanSection)

    val funcNames = funcs.keySet.toSet
    val sccs = findSCCs(funcs.toMap, funcNames)
    mutualGroups = sccs.filter(_.size > 1).foldLeft(Map.empty[String, Set[String]]) { (acc, scc) =>
      acc ++ scc.map(name => name -> scc)
    }

  private def findSCCs(graph: Map[String, Set[String]], names: Set[String]): List[Set[String]] =
    var idx = 0
    val stack  = mutable.Stack[String]()
    val onStk  = mutable.Set[String]()
    val nodeIdx = mutable.Map[String, Int]()
    val low     = mutable.Map[String, Int]()
    val result  = mutable.ListBuffer[Set[String]]()

    def connect(v: String): Unit =
      nodeIdx(v) = idx; low(v) = idx; idx += 1
      stack.push(v); onStk += v
      for w <- graph.getOrElse(v, Set.empty) if names.contains(w) do
        if !nodeIdx.contains(w) then
          connect(w)
          low(v) = low(v) min low(w)
        else if onStk.contains(w) then
          low(v) = low(v) min nodeIdx(w)
      if low(v) == nodeIdx(v) then
        val scc = mutable.Set[String]()
        var w = ""
        while { w = stack.pop(); onStk -= w; scc += w; w != v } do ()
        result += scc.toSet

    for v <- names do
      if !nodeIdx.contains(v) then connect(v)
    result.toList

  // Returns names of functions called in tail position (excludes selfName).
  private def tailCallTargets(tree: scala.meta.Tree, selfName: String, tailPos: Boolean): Set[String] =
    tree match
      case Term.Apply.After_4_6_0(Term.Name(n), argClause) =>
        if tailPos && n != selfName then Set(n)
        else argClause.values.flatMap(a => tailCallTargets(a, selfName, false)).toSet
      case t: Term.If =>
        tailCallTargets(t.cond,  selfName, false) ++
        tailCallTargets(t.thenp, selfName, tailPos) ++
        tailCallTargets(t.elsep, selfName, tailPos)
      case Term.Block(stats) =>
        stats.dropRight(1).flatMap(s => tailCallTargets(s, selfName, false)).toSet ++
        stats.lastOption.map(s => tailCallTargets(s, selfName, tailPos)).getOrElse(Set.empty)
      case t: Term.Match =>
        tailCallTargets(t.expr, selfName, false) ++
        t.casesBlock.cases.flatMap(c => tailCallTargets(c.body, selfName, tailPos)).toSet
      case other =>
        other.children.flatMap(c => tailCallTargets(c, selfName, false)).toSet

  // ─── Module entry ─────────────────────────────────────────────────

  /** Module-level `dependencies:` from the front-matter (set at the top
   *  of `genModule` / `genModuleSegmented`).  Threaded into `genImport`
   *  so `<dep-name>://path` imports rewrite through the resolver. */
  private var moduleDeps: Map[String, String] = Map.empty

  /** Absolute paths of `.ssc` files that have already been inlined by
   *  `genImport`.  Mirrors the cycle-protection invariant in
   *  `JvmGen.importedFiles` — a child module re-importing something
   *  the parent already pulled in (or a diamond import) emits nothing
   *  the second time around. */
  private val importedFiles: scala.collection.mutable.Set[String] =
    scala.collection.mutable.Set.empty

  def genModule(module: Module): String =
    sb.clear()
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    collectFuncParamOrders(module)
    analyzeMutualRecursion(module)
    analyzeEffects(module)
    scanForRunAsyncParallel(module)
    scanForRunActors(module)
    scanForAwaitClient(module)
    scanForStreams(module)
    // v1.27 Phase 3 — sql state.  Reset every module to keep `genModule`
    // re-entrant; emit preamble once when at least one sql block is
    // present (URL-prefix providers + ConnectionRegistry init + resolver).
    sqlBlockCounter = 0
    sqlPerSection.clear()
    val needSqlPreamble = hasSqlBlocks(module)
    if needSqlPreamble then emitSqlPreamble(module)
    // Front-matter route declarations are emitted BEFORE the user blocks so
    // a typical user-side `serve(port)` (last statement of the script) sees
    // them already registered.  JS function declarations are hoisted, so
    // forward references to the handler defs resolve at call time.
    emitFrontmatterRoutes(module)
    emitI18nTable(module)
    emitHttpTypedRouteClients(module.manifest.toList.flatMap(_.apiClients), module)
    // Async wrap rationale (v1.27): sql blocks compile to `await
    // SqlRuntimeJs.execute(...)`, which is only legal at top level in
    // ESM with top-level-await support.  An async IIFE is the
    // universally-portable wrapper that also keeps the legacy
    // classic-script target working.  Same wrapper as the existing
    // `runAsyncParallel` path; the three flags collapse into one
    // `needsAsync` decision.  `runActors` joins this set because its
    // scheduler is `async` — it `await`s `setImmediate`/`setTimeout`
    // between ticks so Node's event loop drains its I/O queue while
    // any actor is blocked on a long-armed `receive` (otherwise an
    // HTTP server bound via `serveAsync(...)` from inside the same
    // `runActors { ... }` body would be unreachable).
    val needsAsync = usesRunAsyncParallel || usesRunActors || usesAwaitClient || needSqlPreamble || usesStreams
    if needsAsync then
      line("(async () => {")
      // Write-through `_println` for code that runs *inside* the
      // async IIFE.  Without this, every `_println` call after the
      // first `await` pushes to `_output` but the outer
      // `process.stdout.write(_output...)` (appended by
      // `Main.scala`'s emit-js / link pipeline) ran synchronously
      // *before* the IIFE's microtasks fire — so async-scheduled
      // prints (actor body output, post-await SQL prints, etc.) were
      // silently dropped.  Override `Console.println` / `Console.print`
      // too — Normalize rewrites bare `println(...)` to
      // `Console.println(...)`, and the runtime's `Console` object
      // captures `_println` by reference at init time, so
      // reassigning `_println` alone leaves `Console.println`
      // pointing at the *buffered* original.  The overrides do NOT
      // push to `_output` (the buffer is only useful for the browser
      // SPA overlay, which doesn't share the Node code path), so the
      // outer segment-end flush has nothing to re-emit and we avoid
      // the duplicate-print failure mode.  `NodeBackend` re-overrides
      // these with an equivalent body — the duplicate-assign is
      // intentional and harmless.
      line("if (typeof process !== 'undefined' && process.stdout && typeof _output !== 'undefined') {")
      line("  _println = function(s) { process.stdout.write(String(s) + '\\n'); };")
      line("  _print   = function(s) { process.stdout.write(String(s));        };")
      line("  if (typeof Console !== 'undefined') {")
      line("    Console.println = _println;")
      line("    Console.print   = _print;")
      line("  }")
      line("}")
    // Pre-connect all databases declared in frontmatter so that the
    // synchronous Db facade (defined in emitSqlPreamble) can serve
    // route-handler Db.query / Db.execute calls without awaiting.
    if needSqlPreamble then
      val dbNames = module.manifest.toList.flatMap(_.databases).map(_.name)
      for dbName <- dbNames do
        line(s"Db._conns[${jsQuote(dbName)}] = await _ssc_sql_resolve(${jsQuote(dbName)});")
    module.sections.foreach(genSection)
    // Auto-call main() if defined and not already called
    if hasMain && !mainCalled then
      line("if (typeof main === 'function') { main(); }")
    if needsAsync then
      line("})().catch(e => {")
      line("  const msg = String(e && e.stack ? e.stack : e);")
      line("  if (typeof process !== 'undefined' && process.stderr) { process.stderr.write(msg + '\\n'); process.exit(1); }")
      line("  else if (typeof document !== 'undefined') { document.body.textContent = msg; }")
      line("  else { console.error(msg); }")
      line("});")
    else
      // Flush synchronous _output to stdout (non-async, non-browser path).
      // Async mode redirects _println to process.stdout.write directly;
      // browser mode relies on JsRuntimeBrowserPatch. This flush covers
      // synchronous scripts running in Node (e.g. ssc run without awaitClient).
      line("if (typeof process !== 'undefined' && process.stdout && typeof _output !== 'undefined' && _output.length > 0) {")
      line("  for (const _l of _output) process.stdout.write(_l + '\\n');")
      line("  _output = [];")
      line("}")
    sb.toString

  /** Emit the v1.27 sql-block preamble: hand-written `sql-runtime.mjs`
   *  source from `backend-sql-runtime-js`, plus the per-module
   *  `_ssc_sql_registry` + `_ssc_sql_resolve(dbName)` dispatcher.
   *
   *  The runtime source is read once (lazy val) from the classpath
   *  resource shipped by `backend-sql-runtime-js`.  Calls into
   *  `ConnectionRegistry`, `execute`, etc. resolve inside that source —
   *  no `import` statements are required in user-emitted code.
   *
   *  Note on async: the runtime is itself ES module syntax (`export
   *  class …`) when run via `node --test`-style import, but JsGen
   *  embeds it textually into a classic script context.  Strip the
   *  `export` keyword so the names land at the function/class top of
   *  the IIFE scope. */
  private def emitSqlPreamble(module: Module): Unit =
    val databases = module.manifest.toList.flatMap(_.databases)
    val entries   = databases.map { d =>
      scalascript.sql.js.SqlRuntimeJsEmit.DatabaseEntry(
        name     = d.name,
        url      = d.url,
        user     = d.user,
        password = d.password,
        driver   = d.driver,
      )
    }
    val runtimeSrc = scalascript.sql.js.SqlRuntimeJsEmit.runtimeSource
    // Strip ESM `export` keywords so the names land at script-level
    // scope (works for both ESM and classic-script consumers).  The
    // SqlRuntimeJs namespace below re-exports the public surface for
    // `genSqlBlock`-emitted call sites.
    val stripped   = runtimeSrc.replace("export ", "")
    sb.append(stripped)
    sb.append("\nconst SqlRuntimeJs = { execute, ConnectionRegistry, makeRow, isResultSetProducer, Providers, SqlJsProvider, SqliteWasmProvider, DuckDbWasmProvider };\n")
    sb.append(scalascript.sql.js.SqlRuntimeJsEmit.emitRegistryInit(entries))
    // Synchronous Db facade.  Connections are pre-initialized inside the
    // async IIFE (see genModule) and stored in Db._conns so that route
    // handlers can call Db.query / Db.execute synchronously.  sql.js is
    // itself synchronous once connected; only the initial load is async.
    sb.append("""const Db = {
  _conns: {},
  query(dbName, sql, params) {
    const conn = Db._conns[dbName || 'default'];
    const p = Array.isArray(params) ? params : (params ? [...params] : []);
    if (conn && conn._sscElectronBridge) return conn.querySync(sql, p);
    if (!conn || !conn._db) throw new Error('Db: no connection for "' + (dbName || 'default') + '"');
    const stmt = conn._db.prepare(sql);
    try {
      stmt.bind(p.map(toSqlJsBind));
      if (isResultSetProducer(sql)) {
        const rows = [];
        let columns = null;
        while (stmt.step()) {
          if (columns === null) columns = stmt.getColumnNames();
          rows.push(makeRow(columns, stmt.get().map(fromSqlJsValue)));
        }
        return rows;
      }
      while (stmt.step()) {}
      return conn._db.getRowsModified();
    } finally {
      stmt.free();
    }
  },
  execute(dbName, sql, params) {
    const conn = Db._conns[dbName || 'default'];
    const p = Array.isArray(params) ? params : (params ? [...params] : []);
    if (conn && conn._sscElectronBridge) return conn.executeSync(sql, p);
    return Db.query(dbName, sql, p);
  }
};
""")
    sb.append("\n")

  // ─── v2.0 Phase 2 — capability detection ─────────────────────────────
  //
  // The split-runtime emit uses this to decide which optional preamble
  // blocks (`JsRuntimeAsync`, `JsRuntimeV14Effects`, `JsRuntimeMcp`,
  // `JsRuntimeDataset`) to include in the shared `_runtime.scjs-runtime`
  // artifact.  Heuristic: scan the module's scalascript blocks for
  // textual references to the corresponding effect/DSL namespaces.
  // Conservative — when the heuristic is unsure we include the block;
  // a missing-block false negative would surface as a `ReferenceError`
  // at runtime, far worse than the few KB of extra runtime size.
  def detectCapabilities(module: Module): Set[JsGen.Capability] =
    import JsGen.Capability.*
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    // Run the existing effect analysis to populate `effectOps` /
    // `effectfulFuns`.  `analyzeEffects` seeds `effectOps` with the
    // built-in `Async.*` / `Actor.*` / `Logger.*` / … names so the
    // CPS-emit pipeline recognises them, so we can't rely on the size
    // of that set — only on whether a user-declared `effect E:` block
    // appeared (those names won't be in the builtin seed).
    analyzeEffects(module)
    val caps = scala.collection.mutable.Set.empty[JsGen.Capability]
    caps += Core
    // Collect all parsed scalascript block sources (textual) so we can
    // grep for capability markers without rebuilding the AST traversal.
    def collectSources(s: Section): List[String] =
      s.content.collect {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) => cb.source
      } ++ s.subsections.flatMap(collectSources)
    val sources = module.sections.flatMap(collectSources)
    val allText = sources.mkString("\n")
    // True iff the user declared their own `effect E:` block in this
    // module — `EffectAnalysis.analyze(builtins=…)` seeds `effectOps`
    // with the builtin names, so size alone is not a signal.  We use a
    // textual marker: an `effect ` keyword at the start of a line (the
    // language's effect-declaration syntax).
    val userEffectDecl = sources.exists { s =>
      s.linesIterator.exists(l => l.stripLeading.startsWith("effect "))
    }
    // Async / Actor / Storage live in `JsRuntimeAsync`.  `effect E:` blocks
    // also need the Free Monad runtime that ships in `JsRuntimeAsync`.
    // Include bare actor API calls (`runActors`, `spawn`, `receive`, `send`)
    // in addition to the qualified `Actor.*` / `Async.*` namespace markers.
    // SQL blocks and Db.* calls also require the async runtime: sql blocks
    // compile to `await` expressions and `Db.*` intrinsics are async.
    val hasSql = hasSqlBlocks(module) || module.manifest.exists(_.databases.nonEmpty)
    val hasAsync = hasSql ||
                   allText.contains("Db.") ||
                   allText.contains("route(") ||
                   allText.contains("Async.") || allText.contains("Actor.") ||
                   allText.contains("Storage.") || userEffectDecl ||
                   allText.contains("runAsync") || allText.contains("runActors") ||
                   allText.contains("handle(") || allText.contains("spawn {") ||
                   allText.contains("spawn{") || allText.contains("receive {") ||
                   allText.contains("receive{") || allText.contains("receive(") ||
                   allText.contains("_perform") || allText.contains("perform ") ||
                   // v1.23 cluster intrinsics that lower to Actor.* in JsGen —
                   // bare-name calls in user source still need the actor
                   // runtime emitted (without it `Actor` is undefined at run
                   // time).  Listed explicitly so capability detection picks
                   // them up before the Term-level lowering runs.
                   allText.contains("startNode") || allText.contains("connectNode") ||
                   allText.contains("joinCluster")
    // v1.51.2 streams — Source.* / stream {} / runToList / etc. require the async runtime
    // (_makeAsyncStream uses async function*; terminal ops need await).
    val hasStreams = allText.contains("Source.") || allText.contains("stream {") ||
                    allText.contains("runToList") || allText.contains("runForeach") ||
                    allText.contains("runDrain")  || allText.contains("runFold") ||
                    allText.contains("runStream") || allText.contains("Sink.") ||
                    allText.contains("Flow.")
    if hasAsync || hasStreams then caps += Async
    // v1.4 effects: Logger / Random / Clock / Env / Auth — `JsRuntimeV14Effects`.
    // v1.51.6: Stream algebraic effect also lives in JsRuntimeV14Effects (at the end).
    val hasV14 = allText.contains("Logger.") || allText.contains("Random.") ||
                 allText.contains("Clock.")  || allText.contains("Env.")    ||
                 allText.contains("Auth.")   || allText.contains("runLogger") ||
                 allText.contains("runRandom") || allText.contains("runClock") ||
                 allText.contains("runEnv")  || allText.contains("runAuth")  ||
                 allText.contains("Stream.") || allText.contains("runStream")
    if hasV14 then caps += Effects
    // MCP — `JsRuntimeMcp`.
    val hasMcp = allText.contains("mcpServer") || allText.contains("serveMcp") ||
                 allText.contains("mcpConnect")
    if hasMcp then caps += Mcp
    // Dataset[T] — `JsRuntimeDataset`.
    val hasDataset = allText.contains("Dataset.") || allText.contains("Dataset(")
    if hasDataset then caps += Dataset
    // Payment Request — `JsRuntimePayment`.
    val hasPayment = allText.contains("PaymentRequest") || allText.contains("PaymentMethod.")
    if hasPayment then caps += Payment
    // v1.61.6 sub-capabilities
    // HtmlDsl — Part1b: HTTP serve/route/sessions/metrics/password/TOTP
    val hasHtmlDsl = allText.contains("serve(") || allText.contains("route(") ||
                     allText.contains("WsRoom(") || allText.contains(".session") ||
                     allText.contains("metrics.") || module.manifest.exists(_.routes.nonEmpty)
    if hasHtmlDsl then caps += HtmlDsl
    // Jwt — Part1c: JwtSign/JwtVerify/OAuth2/CSRF/BearerToken
    val hasJwt = allText.contains("JwtSign(") || allText.contains("JwtVerify(") ||
                 allText.contains("OAuth2.") || allText.contains("bearerToken") ||
                 allText.contains("csrf")
    if hasJwt then caps += Jwt
    // WsServer — Part1d: WebSocket connections, SSE, CORS
    val hasWsServer = allText.contains("WsConnection(") || allText.contains("WsRoom(") ||
                      allText.contains("sse(") || allText.contains("cors(")
    if hasWsServer then caps += WsServer
    // Optics — Lens/Optional/Traversal/Prism
    val hasOptics = allText.contains("Lens(") || allText.contains("Optional(") ||
                    allText.contains("Prism(") || allText.contains("Traversal(") ||
                    allText.contains(".focus")
    if hasOptics then caps += Optics
    // Signals — reactive signals
    val hasSignals = allText.contains("signal(") || allText.contains("computed(")
    if hasSignals then caps += Signals
    // IndexedDb — client-side IndexedDB storage
    val hasIndexedDb = allText.contains("IndexedDb.")
    if hasIndexedDb then caps += IndexedDb
    // GraphQL — `JsRuntimeGraphql`.  Triggered by a `graphql` fenced block or
    // by any of the server/client intrinsics.  The runtime mounts on the full
    // HTTP server stack, which spans `route` (Part1b/HtmlDsl), `_mkRequest`'s
    // auth helpers `_bearerFromAuth` / `jwtVerify` (Part1c/Jwt), and `serve` /
    // `_ssc_http_serve` (Part1d/WsServer); resolvers run async.  Force all of
    // HtmlDsl + Jwt + WsServer + Async whenever GraphQL is used.
    def hasGraphqlBlock(s: Section): Boolean =
      s.content.exists { case cb: Content.CodeBlock => Lang.isGraphql(cb.lang); case _ => false } ||
      s.subsections.exists(hasGraphqlBlock)
    val hasGraphql = module.sections.exists(hasGraphqlBlock) ||
                     allText.contains("GraphQL.") || allText.contains("serveGraphQL") ||
                     allText.contains("graphqlMount") || allText.contains("graphqlHandler") ||
                     allText.contains("graphqlQuery") || allText.contains("graphqlSse") ||
                     allText.contains("graphqlSubscribe") || allText.contains("serveSubgraph") ||
                     allText.contains("graphqlSubgraphMount")
    if hasGraphql then { caps += Graphql; caps += HtmlDsl; caps += Jwt; caps += WsServer; caps += Async }
    caps.toSet

  /** Emit `route(method, path)(handler)` registrations for every
   *  `routes:` entry in the module's front-matter. */
  private def emitFrontmatterRoutes(module: Module): Unit =
    module.manifest.toList.flatMap(_.routes).foreach { r =>
      val m = jsQuote(r.method)
      val p = jsQuote(r.path)
      line(s"route($m, $p)(${r.handler});")
    }

  /** Emit `_i18nTable = { ... }` from the module's front-matter translations. */
  private def emitI18nTable(module: Module): Unit =
    module.manifest.foreach { m =>
      if m.translations.nonEmpty then
        val entries = m.translations.map { (locale, kvs) =>
          val pairs = kvs.map { (k, v) => s"${jsQuote(k)}: ${jsQuote(v)}" }.mkString(", ")
          s"${jsQuote(locale)}: {$pairs}"
        }.mkString(", ")
        line(s"_i18nTable = {$entries};")
    }

  private val endpointPrimitives = Set("Int", "Long", "String", "Boolean", "Double", "Float")

  private def pathParamNames(path: String): List[String] =
    path.split("/").toList.collect { case seg if seg.startsWith(":") => seg.drop(1) }

  private def caseClassFieldsInModule(module: Module): Map[String, List[String]] =
    val result = scala.collection.mutable.Map.empty[String, List[String]]
    def scanStats(stats: List[scala.meta.Stat]): Unit = stats.foreach {
      case d: Defn.Class if d.mods.exists(_.isInstanceOf[Mod.Case]) =>
        result(d.name.value) = d.ctor.paramClauses.flatMap(_.values).map(_.name.value).toList
      case _ => ()
    }
    def scanSection(s: Section): Unit =
      s.content.foreach {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.foreach { node => ScalaNode.fold(node) {
            case Source(stats)     => scanStats(stats); ()
            case Term.Block(stats) => scanStats(stats); ()
            case _                 => ()
          }}
        case _ => ()
      }
      s.subsections.foreach(scanSection)
    module.sections.foreach(scanSection)
    result.toMap

  private def endpointPathWarnings(
    clientName: String,
    ep: ApiEndpointDecl,
    classFields: Map[String, List[String]]
  ): List[String] =
    val params = pathParamNames(ep.path)
    if params.isEmpty then Nil
    else ep.requestType match
      case "Unit" =>
        params.map(p => s"apiClient $clientName.${ep.name}: path param ':$p' cannot be filled — request type is Unit")
      case prim if endpointPrimitives.contains(prim) =>
        if params.size > 1 then
          List(s"apiClient $clientName.${ep.name}: ${params.size} path params but request type '$prim' provides at most one value")
        else Nil
      case typeName =>
        classFields.get(typeName) match
          case Some(fields) =>
            params.filterNot(fields.contains).map { p =>
              s"apiClient $clientName.${ep.name}: path param ':$p' not found in case class '$typeName' (fields: ${fields.mkString(", ")})"
            }
          case None => Nil

  /** Emit browser/Electron HTTP clients declared by front-matter
   *  `apiClients:`. The generated methods intentionally return Promises:
   *  browser `fetch` is asynchronous, and SPA/Electron client-only bundles
   *  can route relative URLs to a JVM backend through
   *  `globalThis.__sscBackendBaseUrl`.
   */
  private def emitHttpTypedRouteClients(clients: List[ApiClientDecl], module: Module): Unit =
    val classFields = caseClassFieldsInModule(module)
    val warnings = clients.flatMap(c => c.endpoints.flatMap(e => endpointPathWarnings(c.name, e, classFields)))
    warnings.foreach { w =>
      System.err.println(s"[ssc warning] $w")
      line(s"// [ssc warning] $w")
    }
    val endpoints = clients.flatMap(client => client.endpoints.map(endpoint => client.name -> endpoint))
    if endpoints.nonEmpty then
      line("const _ssc_typedRouteClients = [")
      indent += 1
      endpoints.zipWithIndex.foreach { case ((client, endpoint), idx) =>
        val comma = if idx == endpoints.size - 1 then "" else ","
        line(
          "{client: " + jsQuote(client) +
            ", name: " + jsQuote(endpoint.name) +
            ", method: " + jsQuote(endpoint.method) +
            ", path: " + jsQuote(endpoint.path) +
            ", requestType: " + jsQuote(endpoint.requestType) +
            ", responseType: " + jsQuote(endpoint.responseType) +
            "}" + comma
        )
      }
      indent -= 1
      line("];")
      sb.append(httpTypedRouteClientRuntime)
      clients.foreach { client =>
        if client.endpoints.nonEmpty then
          line(s"const ${client.name} = {")
          indent += 1
          val allMethodLines = client.endpoints.flatMap { endpoint =>
            val method = jsQuote(endpoint.method)
            val path = jsQuote(endpoint.path)
            val requestType = jsQuote(endpoint.requestType)
            val responseType = jsQuote(endpoint.responseType)
            if ApiEndpointDecl.isWs(endpoint) then
              if endpoint.requestType == "Unit" then
                List(s"${endpoint.name}(onEvent, onError, onOpen, headers) { return _ssc_api_ws_request($path, undefined, onEvent, onError, onOpen, $responseType, headers); }")
              else
                List(s"${endpoint.name}(input, onEvent, onError, onOpen, headers) { return _ssc_api_ws_request($path, input, onEvent, onError, onOpen, $responseType, headers); }")
            else if ApiEndpointDecl.isSse(endpoint) then
              if endpoint.requestType == "Unit" then
                List(s"${endpoint.name}(onEvent, onError, headers) { return _ssc_api_stream_request($method, $path, undefined, onEvent, onError, $responseType, headers); }")
              else
                List(s"${endpoint.name}(input, onEvent, onError, headers) { return _ssc_api_stream_request($method, $path, input, onEvent, onError, $responseType, headers); }")
            else if endpoint.requestType == "Unit" then
              val base = s"${endpoint.name}(headers, cancelToken) { return _ssc_api_request($method, $path, undefined, $requestType, $responseType, headers, cancelToken); }"
              if endpoint.paginated then
                val pagedPath = s"""$path + "?page=" + page + "&size=" + size"""
                val paged = s"${endpoint.name}Paged(page, size, headers, cancelToken) { return _ssc_api_request($method, $pagedPath, undefined, $requestType, $responseType, headers, cancelToken); }"
                List(base, paged)
              else List(base)
            else
              val base = s"${endpoint.name}(input, headers, cancelToken) { return _ssc_api_request($method, $path, input, $requestType, $responseType, headers, cancelToken); }"
              if endpoint.paginated then
                val pagedPath = s"""$path + "?page=" + page + "&size=" + size"""
                val paged = s"${endpoint.name}Paged(input, page, size, headers, cancelToken) { return _ssc_api_request($method, $pagedPath, input, $requestType, $responseType, headers, cancelToken); }"
                List(base, paged)
              else List(base)
          }
          allMethodLines.zipWithIndex.foreach { case (mline, idx) =>
            val comma = if idx == allMethodLines.size - 1 then "" else ","
            line(s"$mline$comma")
          }
          indent -= 1
          line("};")
      }

  private val httpTypedRouteClientRuntime: String = JsRuntimeHttpClient.source

  private def jsTypedJsonRegisterProduct(typeName: String, fields: Seq[String], ctorName: String): String =
    jsTypedJsonRegisterProduct(typeName, fields, Some(ctorName))

  private def jsTypedJsonRegisterProduct(typeName: String, fields: Seq[String], ctorName: Option[String]): String =
    val fieldArray = fields.map(jsQuote).mkString("[", ", ", "]")
    val ctor = ctorName.getOrElse("undefined")
    s"""if (typeof _ssc_typed_json_register_product === "function") _ssc_typed_json_register_product(${jsQuote(typeName)}, $fieldArray, $ctor);"""

  // ─── Effect analysis ─────────────────────────────────────────────
  //
  // Walks the module to:
  //   (1) collect effect operation names: `effect Eff: def op(...) = __effectOp__`
  //       contributes the string "Eff.op" to effectOps.
  //   (2) determine the set of functions that may transitively perform effects.
  //       A function is effectful if its body calls an effect op or another
  //       effectful function. Iterate to a fixed point.
  //
  // Effectful functions are emitted in CPS form (returning a Free value).
  // Pure functions stay direct — plain values double as Pure(value), so they
  // compose with the Free Monad runtime without any wrapping.

  private def analyzeEffects(module: Module): Unit =
    val builtins = Set(
      "Async.delay", "Async.async", "Async.await", "Async.parallel", "Async.recvFrom",
      "Storage.get", "Storage.put", "Storage.remove", "Storage.has", "Storage.keys",
      "Actor.spawn", "Actor.spawn_link", "Actor.self", "Actor.send", "Actor.exit",
      "Actor.receive", "Actor.receive_t",
      "Actor.link", "Actor.monitor", "Actor.demonitor", "Actor.trapExit",
      "Actor.startNode", "Actor.connectNode", "Actor.joinCluster", "Actor.register", "Actor.whereis",
      "Actor.globalRegister", "Actor.globalWhereis",
      "Actor.clusterMembers", "Actor.subscribeClusterEvents",
      "Actor.phiOf", "Actor.isSuspect",
      "Actor.selfNode", "Actor.clusterHealth",
      "Actor.broadcastHealth", "Actor.clusterIsDown",
      "Actor.electLeader", "Actor.currentLeader", "Actor.subscribeLeaderEvents",
      "Actor.setAutoReelect",
      "Actor.useRaftLeaderElection", "Actor.useExternalCoordinator",
      "Actor.leaderProtocol", "Actor.leaderHistory",
      "Actor.setReconnectPolicy", "Actor.requestGossip",
      "Actor.clusterConfigSet", "Actor.clusterConfigGet",
      "Actor.clusterConfigKeys", "Actor.subscribeConfigEvents",
      "Actor.setDraining", "Actor.isDraining",
      "Actor.drainingPeers", "Actor.subscribeDrainEvents",
      "Actor.clusterMetricSet", "Actor.clusterMetricGet",
      "Actor.clusterMetricSum", "Actor.clusterMetricNames",
      "Actor.subscribeMetricEvents",
      "Actor.sendAfter", "Actor.sendInterval", "Actor.cancelTimer",
      "Logger.info", "Logger.warn", "Logger.error", "Logger.debug",
      "Random.nextInt", "Random.nextDouble", "Random.uuid", "Random.pick",
      "Clock.now", "Clock.nowIso", "Clock.sleep",
      "Env.get", "Env.set", "Env.required"
    )

    def collectTrees(s: Section): List[scala.meta.Tree] =
      s.content.collect {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.map(ScalaNode.fold(_)(identity))
      }.flatten ++ s.subsections.flatMap(collectTrees)

    val trees = module.sections.flatMap(collectTrees)
    val r     = EffectAnalysis.analyze(trees, builtins)

    effectOps.clear();        effectOps        ++= r.effectOps
    effectfulFuns.clear();    effectfulFuns    ++= r.effectfulFuns
    multiShotEffects.clear(); multiShotEffects ++= r.multiShotEffects

  /** Walk the module AST and set `usesRunAsyncParallel` if any `runAsyncParallel` call
   *  is present.  Called from `genModule` before emitting user code sections so the
   *  IIFE wrapper and `await` prefix can be applied consistently. */
  private def scanForRunAsyncParallel(module: Module): Unit =
    def collectTrees(s: Section): List[scala.meta.Tree] =
      s.content.collect {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.map(ScalaNode.fold(_)(identity))
      }.flatten ++ s.subsections.flatMap(collectTrees)
    usesRunAsyncParallel = module.sections.flatMap(collectTrees).exists { tree =>
      tree.collect {
        case scala.meta.Term.Apply.After_4_6_0(scala.meta.Term.Name("runAsyncParallel"), _) => ()
      }.nonEmpty
    }

  /** Walk the module AST and set `usesRunActors` if any `runActors` call is
   *  present.  Mirrors `scanForRunAsyncParallel` — sister flag that also feeds
   *  the async-IIFE wrap decision in `genModule` and toggles the `await`
   *  prefix on `_runActors(...)` callsites.  The async-aware scheduler yields
   *  to Node's event loop between ticks (see runtime emission), which is
   *  what unblocks `serveAsync(...)` bound from inside `runActors { ... }`. */
  private def scanForRunActors(module: Module): Unit =
    def collectTrees(s: Section): List[scala.meta.Tree] =
      s.content.collect {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.map(ScalaNode.fold(_)(identity))
      }.flatten ++ s.subsections.flatMap(collectTrees)
    usesRunActors = module.sections.flatMap(collectTrees).exists { tree =>
      tree.collect {
        case scala.meta.Term.Apply.After_4_6_0(scala.meta.Term.Name("runActors"), _) => ()
      }.nonEmpty
    }

  /** Walk client-side ScalaScript blocks and detect `awaitClient(promise)`.
   *  Server-only blocks are skipped because JS targets do not emit them.
   */
  private def scanForAwaitClient(module: Module): Unit =
    def collectTrees(s: Section): List[scala.meta.Tree] =
      s.content.collect {
        case cb: Content.CodeBlock
            if Lang.isScalaScript(cb.lang) && !cb.attrs.get("side").contains("server") =>
          cb.tree.map(ScalaNode.fold(_)(identity))
      }.flatten ++ s.subsections.flatMap(collectTrees)
    usesAwaitClient = module.sections.flatMap(collectTrees).exists { tree =>
      tree.collect {
        case scala.meta.Term.Apply.After_4_6_0(scala.meta.Term.Name("awaitClient"), _) => ()
      }.nonEmpty
    }

  private def scanForStreams(module: Module): Unit =
    def collectTrees(s: Section): List[scala.meta.Tree] =
      s.content.collect {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.map(ScalaNode.fold(_)(identity))
      }.flatten ++ s.subsections.flatMap(collectTrees)
    val terminalNames = Set("runForeach", "runToList", "runDrain", "runFold")
    usesStreams = module.sections.flatMap(collectTrees).exists { tree =>
      tree.collect {
        case scala.meta.Term.Apply.After_4_6_0(scala.meta.Term.Select(_, scala.meta.Term.Name(m)), _)
            if terminalNames.contains(m) => ()
        // v1.51.6: runStream { body } also requires the streams preamble
        case scala.meta.Term.Apply.After_4_6_0(scala.meta.Term.Name("runStream"), _) => ()
      }.nonEmpty
    }


  /** Walk the module in document order, grouping consecutive same-type blocks into
   *  Segment values.  ScalaScript blocks are transpiled to JS; scala blocks are
   *  collected as raw Scala source for later Scala.js compilation.
   */
  def genModuleSegmented(module: Module): List[JsGen.Segment] =
    sb.clear()
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    collectFuncParamOrders(module)
    analyzeMutualRecursion(module)
    analyzeEffects(module)
    scanForRunAsyncParallel(module)
    scanForRunActors(module)
    scanForAwaitClient(module)
    scanForStreams(module)
    // Emit `route(...)` registrations from front-matter before user blocks,
    // so a typical user-side `serve(port)` (last statement of the script)
    // sees them already registered.  JS function declarations are hoisted,
    // so forward references to handler defs resolve at call time.
    emitFrontmatterRoutes(module)
    emitI18nTable(module)
    emitHttpTypedRouteClients(module.manifest.toList.flatMap(_.apiClients), module)
    val result    = mutable.ListBuffer[JsGen.Segment]()
    val scalaBuf  = mutable.ListBuffer[String]()
    var ssStart   = 0

    def flushSS(): Unit =
      val code = sb.substring(ssStart)
      if code.trim.nonEmpty then result += JsGen.Segment.ScalaScriptJs(code)
      ssStart = sb.length

    def flushScala(): Unit =
      if scalaBuf.nonEmpty then
        result += JsGen.Segment.ScalaSource(scalaBuf.mkString("\n\n"))
        scalaBuf.clear()

    def walkSection(s: Section): Unit =
      s.content.foreach {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) && cb.attrs.get("side").contains("server") =>
          ()
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          flushScala()
          cb.tree.foreach(genScalaNode)
        case Content.CodeBlock(lang, src, _, _, _, _, _) if Lang.isStandardScala(lang) =>
          flushSS()
          scalaBuf += src.stripTrailing()
        case cb: Content.CodeBlock if Lang.isStringBlock(cb.lang) =>
          flushScala()
          genStringBlock(cb, s)
        case cb: Content.CodeBlock if Lang.isGraphql(cb.lang) =>
          flushScala()
          genGraphqlBlock(cb)
        case imp: Content.Import =>
          flushScala()
          genImport(imp)
        case _ => ()
      }
      s.subsections.foreach(walkSection)

    val needsAsyncSeg = usesRunAsyncParallel || usesRunActors || usesAwaitClient
    if needsAsyncSeg then
      sb.append("(async () => {\n")
      // See `genModule` for the rationale — install a write-through
      // `_println` (and rebind `Console.println` which captures the
      // original `_println` by reference) inside the IIFE so prints
      // from after the first `await` reach stdout instead of being
      // buffered into a `_output` array the outer segment-end flush
      // no longer sees.
      sb.append("if (typeof process !== 'undefined' && process.stdout && typeof _output !== 'undefined') {\n")
      sb.append("  _println = function(s) { process.stdout.write(String(s) + '\\n'); };\n")
      sb.append("  _print   = function(s) { process.stdout.write(String(s));        };\n")
      sb.append("  if (typeof Console !== 'undefined') {\n")
      sb.append("    Console.println = _println;\n")
      sb.append("    Console.print   = _print;\n")
      sb.append("  }\n")
      sb.append("}\n")
    module.sections.foreach(walkSection)
    flushScala()
    if hasMain && !mainCalled then
      sb.append("if (typeof main === 'function') { main(); }\n")
    if needsAsyncSeg then
      sb.append("})().catch(e => {\n")
      sb.append("  const msg = String(e && e.stack ? e.stack : e);\n")
      sb.append("  if (typeof process !== 'undefined' && process.stderr) { process.stderr.write(msg + '\\n'); process.exit(1); }\n")
      sb.append("  else if (typeof document !== 'undefined') { document.body.textContent = msg; }\n")
      sb.append("  else { console.error(msg); }\n")
      sb.append("});\n")
    val finalCode = sb.substring(ssStart)
    if finalCode.trim.nonEmpty then result += JsGen.Segment.ScalaScriptJs(finalCode)
    result.toList

  private[codegen] def genSection(section: Section): Unit =
    section.content.foreach {
      case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) && cb.attrs.get("side").contains("server") =>
        ()
      case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
        cb.tree.foreach(genScalaNode)
      case cb: Content.CodeBlock if Lang.isStandardScala(cb.lang) =>
        line(s"/* scala: standard Scala 3 block — compile via Scala.js for JS execution */")
      case cb: Content.CodeBlock if Lang.isStringBlock(cb.lang) =>
        genStringBlock(cb, section)
      case cb: Content.CodeBlock if Lang.isSql(cb.lang) =>
        genSqlBlock(cb, section)
      case cb: Content.CodeBlock if Lang.isGraphql(cb.lang) =>
        genGraphqlBlock(cb)
      case imp: Content.Import =>
        genImport(imp)
      case _ => ()
    }
    section.subsections.foreach(genSection)

  /** Emit a `graphql` fenced block as a `_registerGraphqlSdl(<sdl>)` call.
   *  The SDL string is stashed in the runtime so `graphqlMount` /
   *  `serveGraphQL` can build the schema later (mirrors the interpreter's
   *  `GraphQLJvmBlockRunner.registerSdl`). */
  private def genGraphqlBlock(cb: Content.CodeBlock): Unit =
    line(s"_registerGraphqlSdl(${jsStringLit(cb.source.stripTrailing)});")

  /** v1.27 Phase 3 — emit a `sql` fenced block as a `_sqlBlock_<n>`
   *  `const` initialised by `await SqlRuntimeJs.execute(...)`.
   *
   *  Mirrors `JvmGen.sqlBlockToScala` but emits JS:
   *    - `${expr}` interpolations have already been lifted to a `?`-
   *      template + ordered bind list by `SqlBindRewriter.rewriteJdbc`.
   *      Each bind text is parsed back as a Scala term and emitted as
   *      JS via the existing `genExpr` machinery, so the bind value
   *      evaluates in the surrounding scope at runtime.
   *    - Connection resolution funnels through the module-scope
   *      `_ssc_sql_resolve(dbName)` helper emitted once by
   *      `emitSqlPreamble`.
   *    - First sql block in each section also lands as
   *      `const <sectionId> = { ..., sql: _sqlBlock_<n> }` — the
   *      friendly `<sectionId>.sql` alias matches JvmGen's Phase
   *      6.C / Spark Phase C.2 convention.  Subsequent sql blocks in
   *      the same section skip the alias (the first-only book-keeping
   *      in `sqlPerSection` enforces this). */
  private def genSqlBlock(cb: Content.CodeBlock, section: Section): Unit =
    // v1.30 Phase 4 — @side=server blocks are server-only; skip in JS targets.
    if cb.attrs.get("side").contains("server") then return
    val n = sqlBlockCounter
    sqlBlockCounter += 1
    val rewrite = scalascript.transform.SqlBindRewriter.rewriteJdbc(cb.source)
    val sqlLit  = jsStringLit(rewrite.sql)
    val bindsJs = rewrite.binds.map(bindExprToJs).mkString(", ")
    val dbArg   = cb.attrs.get("db") match
      case Some(name) => jsStringLit(name)
      case None       => "undefined"
    val valName = s"_sqlBlock_$n"
    line(s"const $valName = await SqlRuntimeJs.execute(await _ssc_sql_resolve($dbArg), $sqlLit, [$bindsJs]);")
    sectionIdent(section.heading.text).foreach { id =>
      val prior = sqlPerSection.getOrElse(id, 0)
      sqlPerSection(id) = prior + 1
      if prior == 0 then
        line(s"if (typeof $id === 'undefined') var $id = {};")
        line(s"$id.sql = $valName;")
    }

  /** Parse a single bind-expression text (Scala source from inside
   *  `${...}`) back to a `Term` and emit it as JS.  Falls back to
   *  splicing the raw source verbatim when scala.meta can't parse it
   *  (defensive — the parser already rejected malformed source upstream,
   *  but never trust the boundary). */
  private def bindExprToJs(exprSrc: String): String =
    val trimmed = exprSrc.trim
    val parsed  =
      try
        Some(scala.meta.dialects.Scala3(scala.meta.Input.String(trimmed)).parse[scala.meta.Term].toOption).flatten
      catch case _: Throwable => None
    parsed match
      case Some(t) => genExpr(t)
      case None    => trimmed   // last-resort fallback

  /** Detect whether the module has any sql blocks — drives preamble
   *  emission + async wrap.  Walks sections recursively. */
  private def hasSqlBlocks(module: Module): Boolean =
    def go(s: Section): Boolean =
      s.content.exists {
        // v1.30 — @side=server blocks are server-only; don't count them as
        // requiring the SQL preamble in a JS-family bundle.
        case cb: Content.CodeBlock =>
          Lang.isSql(cb.lang) && !cb.attrs.get("side").contains("server")
        case _ => false
      } || s.subsections.exists(go)
    module.sections.exists(go)

  /** Emit a heading-bound html / css block: render the source as a JS
   *  template literal (using `_html_interp` for html), assign to
   *  `<sectionIdent>.<lang>`. */
  private def genStringBlock(cb: Content.CodeBlock, section: Section): Unit =
    sectionIdent(section.heading.text).foreach { id =>
      val rendered = stringBlockTemplate(cb.source, cb.lang == Lang.Html)
      // Use `var` so multiple kinds of block in one section (html + css)
      // can share a single object literal without each invocation
      // clobbering the previous.
      line(s"if (typeof $id === 'undefined') var $id = {};")
      line(s"$id.${cb.lang} = $rendered;")
    }

  /** Mirror Interpreter.sectionIdent: camelCase alphanumeric runs, preserve
   *  the first word's casing; None when the heading is all punctuation. */
  private def sectionIdent(text: String): Option[String] =
    val parts = text.split("[^A-Za-z0-9]+").filter(_.nonEmpty)
    if parts.isEmpty then None
    else
      val head = parts.head
      val tail = parts.tail.map(p => s"${p.head.toUpper}${p.tail}")
      val raw  = head + tail.mkString
      Some(if raw.head.isDigit then "_" + raw else raw)

  private def genImport(imp: Content.Import): Unit =
    import scalascript.parser.Parser
    val base = baseDir.getOrElse(os.pwd)
    val initiallyResolved =
      try scalascript.imports.ImportResolver.resolve(imp.path, base, moduleDeps, lockPath)
      catch case _: Throwable => base / os.RelPath(imp.path)
    val resolvedPath =
      if os.exists(initiallyResolved) then initiallyResolved
      else resolveStdImportFromProjectTree(imp.path, base).getOrElse(initiallyResolved)
    if !os.exists(resolvedPath) then return
    val key         = resolvedPath.toString
    val childModule = Parser.parse(os.read(resolvedPath))
    if !importedFiles.contains(key) then
      importedFiles += key
      val childDir = resolvedPath / os.up
      val childGen = new JsGen(Some(childDir), lockPath = lockPath, topLevelConsts = topLevelConsts, mergeHelperEmitted = mergeHelperEmitted, declaredBindings = declaredBindings)
      childGen.importedFiles ++= importedFiles
      // Emit only the definitions from the imported module (suppress top-level output)
      childModule.sections.foreach { section =>
        section.content.foreach {
          case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
            cb.tree.foreach(childGen.genScalaNode)
          case nestedImp: Content.Import =>
            // Propagate transitive imports — e.g., std/selective.ssc
            // pulls in std/either.ssc, and consumers of selective need
            // Either's constructors emitted too.
            childGen.genImport(nestedImp)
          case _ => ()
        }
        section.subsections.foreach(childGen.genSection)
      }
      sb.append(childGen.sb)
      // Pull cycle-protection state back so siblings don't re-import
      importedFiles ++= childGen.importedFiles
    // Always extract bindings, even when this module was already imported by
    // a transitive dependency.  Cycle-protection only guards code emission —
    // each explicit import still needs its `const x = pkg.x;` binding.
    // `declaredBindings` prevents duplicate `const` declarations when the same
    // symbol is extracted more than once (e.g. TkNode from nodes.ssc imported
    // by both primitives.ssc and layout.ssc).
    val childPkg     = childModule.manifest.flatMap(_.pkg).getOrElse(Nil)
    val pkgPrefix    = if childPkg.isEmpty then "" else childPkg.mkString("", ".", ".")
    val childExports = childModule.manifest.map(_.exports).getOrElse(Nil)
    imp.bindings.foreach { b =>
      val fullName  = s"$pkgPrefix${b.name}"
      val localName = b.alias.getOrElse(b.name)
      // If the child module declares an exports list and this name is absent,
      // skip — don't block a later import from the correct module.
      val notExported = childExports.nonEmpty && !childExports.contains(b.name)
      if fullName != localName && !notExported && !declaredBindings.contains(localName) then
        declaredBindings += localName
        line(s"const $localName = $fullName;")
    }

  private def resolveStdImportFromProjectTree(rawPath: String, base: os.Path): Option[os.Path] =
    if !rawPath.startsWith("std/") then None
    else
      val rel = os.RelPath(rawPath)
      var cur = base
      while true do
        val runtimeStd = cur / "runtime" / rel
        if os.exists(runtimeStd) then return Some(runtimeStd)
        val installedStd = cur / rel
        if os.exists(installedStd) then return Some(installedStd)
        val parent = cur / os.up
        if parent == cur then return None
        cur = parent
      None

  private[codegen] def genScalaNode(node: ScalaNode): Unit =
    ScalaNode.fold(node) {
      case Source(stats) => genBlockStats(stats, topLevel = true)
      case t: Term.Block => genBlockStats(t.stats, topLevel = true)
      case t: Term       => line(genExpr(t) + ";")
      case _             => ()
    }

  /** Returns true if the declaration should be emitted at the top level.
   *  When tree-shaking is active (`reachableNames.isDefined`), named
   *  declarations that are not in the reachable set are suppressed. */
  private def isReachableStat(stat: Stat, topLevel: Boolean): Boolean =
    if !topLevel then return true          // inner-scope stats: never filtered
    reachableNames match
      case None       => true              // tree-shaking off: emit everything
      case Some(reach) =>
        stat match
          case d: Defn.Def     => reach.contains(d.name.value)
          case Defn.Val(_, List(Pat.Var(n)), _, _) =>
            reach.contains(n.value)
          case _: Defn.Val     => true     // multi-pat: conservative keep
          case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, _) =>
            reach.contains(n.value)
          case _: Defn.Var     => true     // multi-pat or other var: conservative keep
          case d: Defn.Class   => reach.contains(d.name.value)
          case d: Defn.Object  => reach.contains(d.name.value)
          case d: Defn.Enum    => reach.contains(d.name.value)
          case d: Defn.Given   =>
            val n = d.name.value
            if n.nonEmpty then reach.contains(n)
            else true            // anonymous given: always keep (side-effectful registration)
          case _: Defn.Trait   => true     // erased anyway; never filtered
          case _: Term         => true     // top-level term/side effect: always keep
          case _               => true     // conservative: keep unknown node kinds

  private def genBlockStats(stats: List[Stat], topLevel: Boolean): Unit =
    stats.zipWithIndex.foreach { (s, i) =>
      val isLast = i == stats.length - 1
      // Skip unreachable top-level declarations when tree-shaking is active
      if !isReachableStat(s, topLevel) then ()
      else
      s match
        case tw: Term.While =>
          line(s"while (${genExpr(tw.expr)}) { ${genExpr(tw.body)}; }")
        case t: Term if isLast && topLevel =>
          // Track main() calls; auto-output non-unit last expression
          t match
            case Term.Apply.After_4_6_0(Term.Name("main"), _) => mainCalled = true
            case _ => ()
          val expr = genExpr(t)
          line(s"{ const _auto = $expr; if (_auto !== undefined && !(_auto === null)) _println(_show(_auto)); }")
        case t: Term =>
          t match
            case Term.Apply.After_4_6_0(Term.Name("main"), _) => mainCalled = true
            case _ => ()
          line(genExpr(t) + ";")
        case stat =>
          stat match
            case d: Defn.Object if topLevel =>
              val name = d.name.value
              if topLevelConsts.contains(name) then
                emitMergeHelper()
                line(s"_ssc_mergeDeep($name, ${genObjectAsExpr(d)});")
              else
                topLevelConsts += name
                genStat(stat)
            case _ =>
              genStat(stat)
    }

  private def emitMergeHelper(): Unit =
    if !mergeHelperEmitted(0) then
      mergeHelperEmitted(0) = true
      line("function _ssc_mergeDeep(dst, src) { for (const k of Object.keys(src)) { if (dst[k] !== null && typeof dst[k] === 'object' && typeof src[k] === 'object') _ssc_mergeDeep(dst[k], src[k]); else dst[k] = src[k]; } }")

  private def isStreamTerminalStat(t: Term): Boolean = t match
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("runForeach" | "runToList" | "runDrain")), _) => true
    case Term.Apply.After_4_6_0(Term.Apply.After_4_6_0(Term.Select(_, Term.Name("runFold")), _), _)   => true
    case _ => false

  /** arch-ffi-p1 — extract the first string literal arg from `@name("expr")` in `mods`. */
  private def extractAnnotationArg(mods: List[Mod], name: String): Option[String] =
    mods.collectFirst {
      case Mod.Annot(init) if (init.tpe match
        case Type.Name(n)                 => n == name
        case Type.Select(_, Type.Name(n)) => n == name
        case _                            => false) =>
          init.argClauses.headOption.flatMap(_.values.collectFirst { case Lit.String(s) => s })
    }.flatten

  /** Substitute `$0`, `$1`, … with the corresponding param names. */
  private def substituteJsArgs(expr: String, params: List[String]): String =
    params.zipWithIndex.foldLeft(expr) { case (e, (n, i)) => e.replace(s"$$$i", n) }

  private def genStat(stat: Stat): Unit = stat match
    case Defn.Val(_, pats, _, rhs) =>
      pats match
        case List(Pat.Var(n)) =>
          if isIntExpr(rhs) then intVars += n.value
          line(s"const ${n.value} = ${genExpr(rhs)};")
        case List(pat) =>
          // Tuple/pattern destructuring
          val patJs = genPatDestructure(pat)
          line(s"const $patJs = ${genExpr(rhs)};")
        case _ =>
          line(s"/* multi-pat val */")

    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      if isIntExpr(rhs) then intVars += n.value
      line(s"let ${n.value} = ${genExpr(rhs)};")

    // arch-ffi-p1 — @js("expr") / @jvm-only handling for extern defs.
    // @js("expr")     → emit a JS function with the inline expression body.
    // @jvm + no @js   → emit a stub that throws a clear runtime error.
    // no annotation   → skip (intrinsic table handles it at call sites).
    case d: Defn.Def if scalascript.transform.EffectAnalysis.isExternDef(d.body) =>
      val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value).toList
      extractAnnotationArg(d.mods, "js") match
        case Some(jsExpr) =>
          val body     = substituteJsArgs(jsExpr, params)
          val paramsStr = params.mkString(", ")
          line(s"function ${d.name.value}($paramsStr) { return $body; }")
        case None =>
          if extractAnnotationArg(d.mods, "jvm").isDefined then
            // @jvm-only: provide a stub that throws at runtime instead of a silent undefined
            val paramsStr = params.mkString(", ")
            line(s"function ${d.name.value}($paramsStr) { throw new Error('${d.name.value} is @jvm-only and cannot be called from the JS backend.'); }")

    case d: Defn.Def =>
      val paramVals   = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
      val params      = paramVals.map(_.name.value)
      val hasDefaults = paramVals.exists(_.default.isDefined)
      val fname       = d.name.value
      val defRenames  = params.collect { case p if jsReservedWords.contains(p) => p -> safeJsParam(p) }.toMap
      // Context-bound type params [A: TC] → synthetic JS param "A$TC", summon key "TC_A"
      @annotation.nowarn("msg=deprecated")
      val cbParams: List[(String, String)] =
        d.paramClauseGroups.flatMap(_.tparamClause.values).flatMap { tp =>
          tp.cbounds.map { cb =>
            val tvName = tp.name.value
            val tcName = cb match
              case Type.Name(n)   => n
              case ta: Type.Apply => ta.tpe match { case Type.Name(n) => n; case _ => "?" }
              case _              => "?"
            s"${tvName}$$${tcName}" -> s"${tcName}_${tvName}"
          }
        }
      val savedCbMap    = cbSummonMap.toMap
      cbSummonMap.clear()
      cbParams.foreach { (pname, skey) => cbSummonMap(skey) = pname }
      val baseParamsStr = paramListWithDefaults(paramVals)
      val cbParamsStr   = cbParams.map(_._1).mkString(", ")
      val paramsStr     =
        if baseParamsStr.isEmpty then cbParamsStr
        else if cbParamsStr.isEmpty then baseParamsStr
        else s"$baseParamsStr, $cbParamsStr"
      if fname == "main" && params.isEmpty then hasMain = true
      // Effectful function: body emitted in CPS form, returns Free value.
      if isEffectfulFun(fname) then
        d.body match
          case Term.Block(stats) =>
            line(s"function $fname($paramsStr) { return ${genCpsBlockAsIife(stats)}; }")
          case expr =>
            line(s"function $fname($paramsStr) { return ${genCpsExpr(expr)}; }")
      // Context-bound params: emit plain function with auto-resolve guards; skip TCO
      else if cbParams.nonEmpty then
        val hintParam = paramVals.headOption.map(_.name.value).getOrElse("undefined")
        val cbGuards = cbParams.map { (pname, skey) =>
          val tcName = skey.takeWhile(_ != '_')
          s"""if ($pname === undefined) $pname = _resolveGiven("${tcName}_" + _ssc_typeOf($hintParam));"""
        }
        d.body match
          case Term.Block(bodyStats) =>
            line(s"function $fname($paramsStr) {")
            indent += 1
            cbGuards.foreach(line)
            genFunctionBody(bodyStats)
            indent -= 1
            line("}")
          case expr =>
            line(s"function $fname($paramsStr) {")
            indent += 1
            cbGuards.foreach(line)
            line(s"return ${genExpr(expr)};")
            indent -= 1
            line("}")
      // Mutual recursion group → _impl + trampoline wrapper.
      // Defaults disable the TCO/mutual-TCO shadowing path since the _p shadow
      // names would shadow the original parameter names referenced in default
      // expressions; defaults are uncommon in tight recursive loops anyway.
      else if mutualGroups.contains(fname) && params.nonEmpty && !hasDefaults &&
              !containsAwaitClient(d.body) then
        genMutualTcoFun(d, fname, params)
      // Self-TCO: emit a while-loop trampoline when all self-calls are in tail position
      else if params.nonEmpty && fname.nonEmpty && !hasDefaults &&
              !containsAwaitClient(d.body) &&
              !hasNonTailSelfCall(d.body, fname, tailPos = true) then
        // Formals are _p shadow-names so we can declare mutable let params inside.
        // safeJsParam guards against JS reserved words (e.g. `default` → `default_p`).
        val renames  = params.collect { case p if jsReservedWords.contains(p) => p -> safeJsParam(p) }.toMap
        val formals  = params.map(p => s"_$p").mkString(", ")
        val letDecls = "let " + params.map(p => s"${safeJsParam(p)} = _$p").mkString(", ")
        line(s"function $fname($formals) {")
        indent += 1
        line(s"$letDecls;")
        line("while(true) {")
        indent += 1
        withParamRenames(renames)(genTcoBody(d.body, fname, params))
        indent -= 1
        line("}")
        indent -= 1
        line("}")
      else
        val fnKw = if containsAwaitClient(d.body) then "async function" else "function"
        d.body match
          case Term.Block(bodyStats) =>
            line(s"$fnKw $fname($paramsStr) {")
            indent += 1
            withParamRenames(defRenames)(genFunctionBody(bodyStats))
            indent -= 1
            line("}")
          case expr =>
            line(s"$fnKw $fname($paramsStr) { return ${withParamRenames(defRenames)(genExpr(expr))}; }")
      cbSummonMap.clear()
      cbSummonMap ++= savedCbMap

    case d: Defn.Class =>
      // case class → constructor function returning plain object
      val paramVals = d.ctor.paramClauses.flatMap(_.values)
      val params = paramVals.map(_.name.value)
      val typeName = d.name.value
      val paramsStr = paramListWithDefaults(paramVals)
      val fields = params.map(p => s"$p: $p").mkString(", ")
      line(s"function $typeName($paramsStr) { return {_type: '$typeName', $fields}; }")
      line(jsTypedJsonRegisterProduct(typeName, params, typeName))

    case d: Defn.Object =>
      line(s"const ${d.name.value} = ${genObjectAsExpr(d)};")

    case d: Defn.Enum =>
      val enumName = d.name.value
      val allCases = scala.collection.mutable.ListBuffer.empty[String]
      val nullary  = scala.collection.mutable.ListBuffer.empty[String]
      def emitNullary(caseName: String): Unit =
        line(s"const $caseName = {_type: '$caseName'};")
        line(jsTypedJsonRegisterProduct(caseName, Nil, None))
        allCases += caseName; nullary += caseName
      d.templ.body.stats.foreach {
        case ec: Defn.EnumCase =>
          val caseName = ec.name.value
          val paramVals = ec.ctor.paramClauses.flatMap(_.values)
          val params = paramVals.map(_.name.value)
          if params.isEmpty then emitNullary(caseName)
          else
            val paramsStr = paramListWithDefaults(paramVals)
            val fields = params.map(p => s"$p: $p").mkString(", ")
            line(s"function $caseName($paramsStr) { return {_type: '$caseName', $fields}; }")
            line(jsTypedJsonRegisterProduct(caseName, params, caseName))
            allCases += caseName
        // `case A, B` (comma-separated parameterless cases) → RepeatedEnumCase.
        case rec: Defn.RepeatedEnumCase =>
          rec.cases.foreach(nm => emitNullary(nm.value))
        case _ => ()
      }
      // Companion: qualified `EnumName.Case` refs + `EnumName.values` (the
      // parameterless cases, in declaration order).
      val members = allCases.map(c => s"$c: $c").mkString(", ")
      val sep     = if members.isEmpty then "" else ", "
      line(s"const $enumName = { $members${sep}values: [${nullary.mkString(", ")}] };")

    case _: Defn.Trait => () // erased

    case d: Defn.Given =>
      // given intShow: Show[Int] with { def show(x) = ... }
      // → const intShow = { show: (x) => ..., ... };
      // also register as Show_Int.
      //
      // Extension methods inside the given body (`extension [A](fa: F[A]) def fmap[B](f) = ...`)
      // are registered into the global `_extensions` table so `fa.fmap(f)` dispatches —
      // same machinery as top-level extension groups.
      d.templ.body.stats.foreach {
        case eg: Defn.ExtensionGroup => genStat(eg)
        case _                       => ()
      }
      d.templ.inits.headOption.foreach { init =>
        val typeKeyOpt: Option[String] = init.tpe match
          case n: Type.Name  => Some(n.value)
          case ta: Type.Apply =>
            (ta.tpe match { case n: Type.Name => Some(n.value); case _ => None }).map { tc =>
              val arg = ta.argClause.values match
                case List(n: Type.Name) => n.value
                case _                  => "_"
              s"${tc}_${arg}"
            }
          case _ => None
        typeKeyOpt.foreach { typeKey =>
          val members = d.templ.body.stats.collect { case dd: Defn.Def =>
            s"${dd.name.value}: ${genDefAsMethod(dd)}"
          }
          val obj = s"{${members.mkString(", ")}}"
          val explicitName = d.name.value
          if explicitName.nonEmpty then
            line(s"const $explicitName = $obj;")
            // Also register with typeclass key for summon and _ssc_givens
            line(s"const $typeKey = $explicitName;")
            line(s"""_ssc_givens["$typeKey"] = $explicitName;""")
          else
            line(s"const $typeKey = $obj;")
            line(s"""_ssc_givens["$typeKey"] = $typeKey;""")
        }
      }

    case _: Decl.Def => () // abstract

    case d: Defn.ExtensionGroup =>
      d.paramClauseGroup.foreach { pcg =>
        pcg.paramClauses.headOption.flatMap(_.values.headOption).foreach { recvParam =>
          val recvName = recvParam.name.value
          val recvType = recvParam.decltpe match
            case Some(Type.Name(n))   => n
            case Some(ta: Type.Apply) => ta.tpe match { case Type.Name(n) => n; case _ => "Any" }
            case _                    => "Any"
          d.body match
            case defn: Defn.Def =>
              genExtensionDef(recvName, recvType, defn)
            case Term.Block(stats) =>
              stats.foreach { case defn: Defn.Def => genExtensionDef(recvName, recvType, defn); case _ => () }
            case _ => ()
        }
      }

    case t: Term =>
      // Track if main() is explicitly called
      t match
        case Term.Apply.After_4_6_0(Term.Name("main"), _) => mainCalled = true
        case _ => ()
      if isStreamTerminalStat(t) then line(s"await ${genExpr(t)};")
      else line(genExpr(t) + ";")

    case _: Import => () // ignored
    case _: Export => () // ignored
    case _ => () // type aliases etc.

  private def genExtensionDef(recvName: String, recvType: String, defn: Defn.Def): Unit =
    val mparamVals = defn.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
    val paramsStr  = (recvName :: mparamVals.map(formalWithDefault)).mkString(", ")
    // Encode receiver TYPE into the function name so that the same extension
    // method on different types (e.g., Functor[List].ap and Functor[Option].ap)
    // does not collide and silently overwrite each other.
    val fnName = s"_ext_${recvType}_${defn.name.value}"
    defn.body match
      case Term.Block(bodyStats) =>
        line(s"function $fnName($paramsStr) {")
        indent += 1
        genFunctionBody(bodyStats)
        indent -= 1
        line("}")
      case expr =>
        line(s"function $fnName($paramsStr) { return ${genExpr(expr)}; }")
    // Register extension for dispatch.  The receiver type (when known)
    // disambiguates same-named extensions across typeclass instances
    // — e.g., Functor[List].map vs Functor[Option].map both register
    // `map` but route by `_typeOf(obj)` at the call site.  `Any` means
    // the legacy method-only registry handles it.
    val regType = if recvType == "Any" then "null" else s"'$recvType'"
    line(s"_registerExt('${defn.name.value}', ($recvName, ...args) => $fnName($recvName, ...args), $regType);")

  /** Emit a Scala `Defn.Object` as a JS expression — an IIFE that
   *  declares each member as a local const and returns them as an
   *  object literal.  Used both at top level (`const X = (iife)()`)
   *  and as the right-hand side of a nested `const inner = (iife)()`
   *  inside another object's body, which is how the `package:`
   *  front-matter wrapper survives JS emission. */
  private def genObjectAsExpr(d: Defn.Object): String =
    val objectName = d.name.value
    val decls = mutable.ArrayBuffer.empty[String]
    val names = mutable.ArrayBuffer.empty[String]
    d.templ.body.stats.foreach {
      case dd: Defn.Def if scalascript.transform.EffectAnalysis.isExternDef(dd.body) =>
        val fname = dd.name.value
        val stub = s"_ssc_ui_$fname"
        // Forward to the corresponding Node.js runtime stub if present,
        // otherwise leave as undefined.  Avoids TDZ issues that occur when
        // the parent scope later does `const fname = pkg.fname` (shadowing).
        decls += s"const $fname = (typeof $stub !== 'undefined') ? $stub : undefined;"
        names += fname
      case dd: Defn.Def if isEffectOpDef(dd.body) =>
        val opName = dd.name.value
        val paramVals = dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
        val params    = paramVals.map(_.name.value)
        val paramsStr = paramListWithDefaults(paramVals)
        val argsArr = if params.isEmpty then "[]" else s"[${params.mkString(", ")}]"
        decls += s"const $opName = ($paramsStr) => _perform('$objectName', '$opName', $argsArr);"
        names += opName
      case dd: Defn.Def =>
        val fname = dd.name.value
        val allClauses = dd.paramClauseGroups.flatMap(_.paramClauses).filterNot(_.mod.nonEmpty)
        val bodyJs = dd.body match
          case Term.Block(bodyStats) => genBlockAsIife(bodyStats)
          case expr                   => genExpr(expr)
        def clauseSig(params: List[Term.Param]): String =
          if params.nonEmpty && params.last.decltpe.exists(_.isInstanceOf[Type.Repeated]) then
            val nonVararg = params.init
            val vararg    = params.last.name.value
            if nonVararg.isEmpty then s"(...$vararg)"
            else s"(${paramListWithDefaults(nonVararg)}, ...$vararg)"
          else s"(${paramListWithDefaults(params)})"
        if allClauses.length <= 1 then
          val sig = clauseSig(allClauses.flatMap(_.values))
          decls += s"const $fname = $sig => $bodyJs;"
        else
          val innerFn = allClauses.init.foldRight(clauseSig(allClauses.last.values) + s" => $bodyJs") {
            (clause, inner) => clauseSig(clause.values) + s" => $inner"
          }
          decls += s"const $fname = $innerFn;"
        names += fname
      case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
        decls += s"const ${n.value} = ${genExpr(rhs)};"
        names += n.value
      case nested: Defn.Object =>
        decls += s"const ${nested.name.value} = ${genObjectAsExpr(nested)};"
        names += nested.name.value
      case d: Defn.Class =>
        val paramVals = d.ctor.paramClauses.flatMap(_.values)
        val params    = paramVals.map(_.name.value)
        val typeName  = d.name.value
        val paramsStr = paramListWithDefaults(paramVals)
        val fields    = params.map(p => s"$p: $p").mkString(", ")
        decls += s"function $typeName($paramsStr) { return {_type: '$typeName', $fields}; }"
        decls += jsTypedJsonRegisterProduct(typeName, params, typeName)
        names += typeName
      case d: Defn.Enum =>
        val enumName = d.name.value
        val allCases = scala.collection.mutable.ListBuffer.empty[String]
        val nullary  = scala.collection.mutable.ListBuffer.empty[String]
        def emitNullary(caseName: String): Unit =
          decls += s"const $caseName = {_type: '$caseName'};"
          decls += jsTypedJsonRegisterProduct(caseName, Nil, None)
          names += caseName; allCases += caseName; nullary += caseName
        d.templ.body.stats.foreach {
          case ec: Defn.EnumCase =>
            val caseName  = ec.name.value
            val paramVals = ec.ctor.paramClauses.flatMap(_.values)
            val params    = paramVals.map(_.name.value)
            if params.isEmpty then emitNullary(caseName)
            else
              val paramsStr = paramListWithDefaults(paramVals)
              val fields    = params.map(p => s"$p: $p").mkString(", ")
              decls += s"function $caseName($paramsStr) { return {_type: '$caseName', $fields}; }"
              decls += jsTypedJsonRegisterProduct(caseName, params, caseName)
              names += caseName; allCases += caseName
          case rec: Defn.RepeatedEnumCase =>
            rec.cases.foreach(nm => emitNullary(nm.value))
          case _ => ()
        }
        val members = allCases.map(c => s"$c: $c").mkString(", ")
        val sep     = if members.isEmpty then "" else ", "
        decls += s"const $enumName = { $members${sep}values: [${nullary.mkString(", ")}] };"
        names += enumName
      case _ => ()
    }
    val body = decls.mkString(" ")
    val ret  = names.mkString(", ")
    s"(() => { $body return { $ret }; })()"

  private def genDefAsMethod(dd: Defn.Def): String =
    val paramVals = dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
    val paramsStr = paramListWithDefaults(paramVals)
    dd.body match
      case Term.Block(bodyStats) =>
        val bodyJs = genBlockAsIife(bodyStats)
        s"($paramsStr) => $bodyJs"
      case expr =>
        s"($paramsStr) => ${genExpr(expr)}"

  /** Render a comma-separated formal parameter list with `= <expr>` for any
   *  parameter that has a default value. Used everywhere we emit a JS function
   *  signature from a scalameta `Term.Param` list. */
  private def paramListWithDefaults(paramVals: Seq[Term.Param]): String =
    paramVals.map(formalWithDefault).mkString(", ")

  // JS reserved words that cannot appear as parameter names (ES2022 strict mode).
  private val jsReservedWords = Set(
    "await", "break", "case", "catch", "class", "const", "continue", "debugger",
    "default", "delete", "do", "else", "export", "extends", "false", "finally",
    "for", "function", "if", "import", "in", "instanceof", "let", "new", "null",
    "return", "static", "super", "switch", "this", "throw", "true", "try",
    "typeof", "var", "void", "while", "with", "yield"
  )

  private def safeJsParam(name: String): String =
    if jsReservedWords.contains(name) then s"${name}_p" else name

  private def formalWithDefault(p: Term.Param): String =
    val n = safeJsParam(p.name.value)
    p.default match
      case Some(d) => s"$n = ${genExpr(d)}"
      case None    => n

  // ─── Mutual TCO helpers ──────────────────────────────────────────

  // Emits _fname_impl (while-loop + _tailCall for mutual calls) and the public wrapper.
  private def genMutualTcoFun(d: Defn.Def, fname: String, params: List[String]): Unit =
    val implName = s"_${fname}_impl"
    val friends  = mutualGroups(fname) - fname
    val renames  = params.collect { case p if jsReservedWords.contains(p) => p -> safeJsParam(p) }.toMap
    val formals  = params.map(p => s"_$p").mkString(", ")
    val letDecls = "let " + params.map(p => s"${safeJsParam(p)} = _$p").mkString(", ")
    line(s"function $implName($formals) {")
    indent += 1
    line(s"$letDecls;")
    line("while(true) {")
    indent += 1
    withParamRenames(renames)(genMutualTcoBody(d.body, fname, params, friends))
    indent -= 1
    line("}")
    indent -= 1
    line("}")
    // Public wrapper that starts the trampoline
    val wrapArgs = params.map(p => s"_$p").mkString(", ")
    line(s"function $fname($formals) { return _trampoline($implName, $wrapArgs); }")

  // Like genTcoBody but mutual tail calls return _tailCall thunks.
  private def genMutualTcoBody(term: Term, fname: String, params: List[String], friends: Set[String]): Unit =
    term match
      case Term.Apply.After_4_6_0(Term.Name(`fname`), argClause) =>
        val newArgs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
        if params.length == 1 then line(s"${params(0)} = ${newArgs(0)};")
        else line(s"[${params.mkString(", ")}] = [${newArgs.mkString(", ")}];")
        line("continue;")
      case Term.Apply.After_4_6_0(Term.Name(n), argClause) if friends.contains(n) =>
        val newArgs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
        line(s"return _tailCall(_${n}_impl, ${newArgs.mkString(", ")});")
      case t: Term.If =>
        line(s"if (${genExpr(t.cond)}) {")
        indent += 1; genMutualTcoBody(t.thenp, fname, params, friends); indent -= 1
        line("} else {")
        indent += 1; genMutualTcoBody(t.elsep, fname, params, friends); indent -= 1
        line("}")
      case Term.Block(stats) =>
        stats.dropRight(1).foreach {
          case t: Term => line(genExpr(t) + ";")
          case s       => genStat(s)
        }
        stats.lastOption.foreach {
          case t: Term => genMutualTcoBody(t, fname, params, friends)
          case _       => ()
        }
      case other =>
        line(s"return ${genExpr(other)};")

  // ─── Self-TCO helpers ─────────────────────────────────────────────

  // Emits statements for the body of a TCO while-loop.
  // Tail calls to fname become parameter reassignment + continue.
  // All other expressions become return statements.
  private def genTcoBody(term: Term, fname: String, params: List[String]): Unit = term match
    case Term.Apply.After_4_6_0(Term.Name(`fname`), argClause) =>
      val newArgs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      if params.length == 1 then
        line(s"${params(0)} = ${newArgs(0)};")
      else
        line(s"[${params.mkString(", ")}] = [${newArgs.mkString(", ")}];")
      line("continue;")
    case t: Term.If =>
      line(s"if (${genExpr(t.cond)}) {")
      indent += 1; genTcoBody(t.thenp, fname, params); indent -= 1
      line("} else {")
      indent += 1; genTcoBody(t.elsep, fname, params); indent -= 1
      line("}")
    case Term.Block(stats) =>
      stats.dropRight(1).foreach {
        case t: Term => line(genExpr(t) + ";")
        case s       => genStat(s)
      }
      stats.lastOption.foreach {
        case t: Term => genTcoBody(t, fname, params)
        case _       => ()
      }
    case other =>
      line(s"return ${genExpr(other)};")

  // Returns true if term contains a call to fname NOT in tail position.

  private def genFunctionBody(stats: List[Stat]): Unit =
    if stats.isEmpty then
      line("return undefined;")
    else
      stats.zipWithIndex.foreach { (s, i) =>
        val isLast = i == stats.length - 1
        s match
          case t: Term if isLast =>
            line(s"return ${genExpr(t)};")
          case t: Term =>
            line(genExpr(t) + ";")
          case stat =>
            genStat(stat)
        // After each non-last stat, continue with remaining
      }

  // ── Generator / coroutine body helpers ───────────────────────────────
  // The parser wraps `{ () => body }` in a Term.Block; this helper unwraps
  // it so we always call genGeneratorBody with just the body content.
  private def extractGenBody(arg: Term): String = arg match
    case Term.Function.After_4_6_0(_, body) =>
      genGeneratorBody(body.asInstanceOf[Term])
    case Term.Block(List(Term.Function.After_4_6_0(_, body))) =>
      genGeneratorBody(body.asInstanceOf[Term])
    case other =>
      s"function*() { return ${genExpr(other)}; }"

  private def extractCoroutineBody(arg: Term): String = arg match
    case Term.Function.After_4_6_0(_, body) =>
      genCoroutineBody(body.asInstanceOf[Term])
    case Term.Block(List(Term.Function.After_4_6_0(_, body))) =>
      genCoroutineBody(body.asInstanceOf[Term])
    case other =>
      s"function*() { return ${genExpr(other)}; }"

  private def genGeneratorBody(t: Term): String =
    s"function*() {\n${genGenStmt(t)}\n}"

  private def extractStreamBody(arg: Term): String = arg match
    case Term.Function.After_4_6_0(_, body) =>
      s"async function*() {\n${genGenStmt(body.asInstanceOf[Term])}\n}"
    case Term.Block(List(Term.Function.After_4_6_0(_, body))) =>
      s"async function*() {\n${genGenStmt(body.asInstanceOf[Term])}\n}"
    case other =>
      s"async function*() { return ${genExpr(other)}; }"

  // Like genGeneratorBody but emits `return` for the last expression so
  // the coroutine's completion value is propagated via gen.next().done.
  private def genCoroutineBody(t: Term): String =
    s"function*() {\n${genCoroutineStmts(t)}\n}"

  private def genCoroutineStmts(t: Term): String = t match
    case Term.Block(stats) if stats.nonEmpty =>
      val (init, last) = (stats.init, stats.last)
      val initJs = init.map(genGenStatItem).mkString("\n")
      val lastJs = last match
        case w: Term.While  => genGenStatItem(w)  // while returns Unit — no return needed
        case t: Term.If     => genGenStatItem(t)  // if-as-statement form
        case t: Term.Throw  => genGenStatItem(t)  // throw is a statement, not an expression
        case t: Term        => s"  return ${genGenExpr(t)};"
        case s              => s"  ${genStatInline(s)}"
      if init.isEmpty then lastJs else initJs + "\n" + lastJs
    case t: Term => s"  return ${genGenExpr(t)};"
    case null    => ""

  private def genGenStmt(t: Term): String = t match
    case Term.Block(stats) => stats.map(genGenStatItem).mkString("\n")
    case s: Stat           => genGenStatItem(s)

  private def genGenStatItem(s: Stat): String = s match
    case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
      s"  const ${n.value} = ${genGenExpr(rhs)};"
    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      s"  let ${n.value} = ${genGenExpr(rhs)};"
    case Term.Assign(Term.Name(n), rhs) =>
      s"  $n = ${genGenExpr(rhs)};"
    case t: Term.While =>
      val bodyStr = genGenStmt(t.body)
      s"  while (${genExpr(t.expr)}) {\n$bodyStr\n  }"
    case t: Term.If =>
      val elseStr = t.elsep match
        case Lit.Unit() => ""
        case ep         => s" else {\n${genGenStmt(ep)}\n  }"
      s"  if (${genExpr(t.cond)}) {\n${genGenStmt(t.thenp)}\n  }$elseStr"
    case t: Term.Match =>
      // Match as statement in a generator/coroutine body — must NOT wrap in an IIFE
      // because case branches may contain `yield` (via suspend), which is invalid inside arrow fns.
      val scrutVar = freshTmp()
      val scrutExpr = genGenExpr(t.expr)
      val casesJs = t.casesBlock.cases.map { c =>
        val (cond, bindings) = genPattern(scrutVar, c.pat)
        val bindingJs = if bindings.isEmpty then ""
          else bindings.map { case (n, e) => s"    const $n = $e;" }.mkString("\n") + "\n"
        val bodyJs = genGenStmt(c.body)
        val condStr = s"($cond) "
        s"  if $condStr{\n$bindingJs$bodyJs\n  }"
      }.mkString(" else ")
      s"  const $scrutVar = $scrutExpr;\n$casesJs"
    case Term.Throw(expr) =>
      val errMsg = expr match
        case Term.New(init) =>
          init.argClauses.headOption.flatMap(_.values.headOption)
            .map(v => genExpr(v.asInstanceOf[Term]))
            .getOrElse("'error'")
        case Term.Apply.After_4_6_0(Term.Name("RuntimeException" | "Exception" | "Error"), argClause)
            if argClause.values.size == 1 =>
          genExpr(argClause.values.head.asInstanceOf[Term])
        case _ => genGenExpr(expr)
      s"  throw new Error($errMsg);"
    case t: Term => s"  ${genGenExpr(t)};"
    case _       => s"  ${genStatInline(s)}"

  private def genGenExpr(t: Term): String = t match
    case Term.Apply.After_4_6_0(Term.Name("suspend" | "emit"), argClause) if argClause.values.size == 1 =>
      s"(yield ${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Block(stats) => stats.map(genGenStatItem).mkString("\n")
    case _                 => genExpr(t)

  private def genBlockAsIife(stats: List[Stat]): String =
    if stats.isEmpty then "undefined"
    else if stats.length == 1 then
      stats.head match
        case t: Term => genExpr(t)
        case stat =>
          s"(() => { ${genStatInline(stat)} return undefined; })()"
    else
      // Multi-stat IIFE
      val inner = StringBuilder()
      inner.append("(() => {\n")
      stats.zipWithIndex.foreach { (s, i) =>
        val isLast = i == stats.length - 1
        val pad = "  "
        s match
          case tw: Term.While =>
            val body = s"while (${genExpr(tw.expr)}) { ${genExpr(tw.body)}; }"
            if isLast then inner.append(pad).append(body).append(" return undefined;\n")
            else inner.append(pad).append(body).append("\n")
          case t: Term if isLast =>
            inner.append(pad).append("return ").append(genExpr(t)).append(";\n")
          case t: Term =>
            inner.append(pad).append(genExpr(t)).append(";\n")
          case stat =>
            inner.append(pad).append(genStatInline(stat)).append("\n")
      }
      inner.append("})()")
      inner.toString

  private def genStatInline(stat: Stat): String = stat match
    case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
      s"const ${n.value} = ${genExpr(rhs)};"
    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      s"let ${n.value} = ${genExpr(rhs)};"
    case d: Defn.Def =>
      val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
      d.body match
        case expr =>
          s"function ${d.name.value}(${params.mkString(", ")}) { return ${genExpr(expr)}; }"
    case t: Term => genExpr(t) + ";"
    case _ => "/* stat */;"

  private def genPatDestructure(pat: Pat): String = pat match
    case Pat.Tuple(pats) =>
      "[" + pats.map(p => p match
        case Pat.Var(n) => n.value
        case Pat.Wildcard() => "_"
        case inner => genPatDestructure(inner)
      ).mkString(", ") + "]"
    case Pat.Var(n) => n.value
    case Pat.Wildcard() => "_"
    case _ => "_"

  private def isEffectOpDef(body: Term): Boolean =
    scalascript.transform.EffectAnalysis.isEffectOpDef(body)

  /** Emits a JS matcher closure for a `receive { case … }` block.
   *  The closure takes the next mailbox message and returns either
   *  `{ matched: false }` or `{ matched: true, body: () => <computation> }`.
   *  Case bodies are CPS-emitted so any nested Actor / Async / handle
   *  effects compose into the actor's pending Computation. */
  private def genReceiveMatcher(cases: List[Case]): String =
    val scrut = "__rcv_msg__"
    val chain = cases.map { c =>
      val (cond, bindings) = genPattern(scrut, c.pat)
      val bindStmts = bindings.map { case (n, e) => s"const $n = $e;" }.mkString(" ")
      val bodyCps   = genCpsExpr(c.body)
      val condFinal = c.cond match
        case Some(g) =>
          val guardJs = genExpr(g)
          if cond == "true" then s"($guardJs)" else s"($cond) && ($guardJs)"
        case None => if cond == "true" then "true" else s"($cond)"
      s"if ($condFinal) { $bindStmts return { matched: true, body: () => $bodyCps }; }"
    }.mkString(" ")
    s"($scrut) => { $chain return { matched: false }; }"

  private def genHandleForm(body: Term, cases: List[Case]): String =
    val handledOps = cases.flatMap { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(Term.Select(Term.Name(eff), Term.Name(op)), _) => Some(s"'$eff.$op'")
        case _ => None
    }
    val handlerEntries = cases.flatMap { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(Term.Select(Term.Name(eff), Term.Name(op)), argClause) =>
          val paramNames = argClause.values.map {
            case Pat.Var(n)     => n.value
            case Pat.Wildcard() => "_"
            case _              => "_"
          }
          val paramsStr = s"[${paramNames.mkString(", ")}]"
          // Handler case bodies stay direct: they receive `resume` (a plain
          // function returning the value of the resumed branch) and compose it
          // with regular JS. Effects inside case bodies are uncommon and would
          // need their own handle.
          val bodyJs = c.body match
            case Term.Block(stats) =>
              val stmts = stats.dropRight(1).map {
                case t: Term => genExpr(t) + ";"
                case s       => genStatInline(s)
              }.mkString(" ")
              val last = stats.lastOption.map {
                case t: Term => s"return ${genExpr(t)};"
                case _       => ""
              }.getOrElse("")
              s"($paramsStr) => { $stmts $last }"
            case expr =>
              s"($paramsStr) => ${genExpr(expr)}"
          Some(s"'$eff.$op': $bodyJs")
        case _ => None
    }
    // The handle body is always emitted in CPS form so that effect ops build
    // a Free tree which _handle can interpret.
    val bodyThunk = s"() => ${genCpsExpr(body)}"
    // Use _handleOneShot when no op belongs to a multi-shot effect.
    val allOneShot = handledOps.forall { opStr =>
      val effName = opStr.stripPrefix("'").takeWhile(_ != '.')
      !multiShotEffects.contains(effName)
    }
    val handleFn = if allOneShot then "_handleOneShot" else "_handle"
    s"$handleFn($bodyThunk, [${handledOps.mkString(", ")}], {${handlerEntries.mkString(", ")}})"

  /** Stage 5+/A.5 — per-call-site intrinsic dispatch.  Returns the
   *  JS expression string to splice in, or `None` if no intrinsic
   *  claims this name.  Called from `genExpr` for Term.Apply
   *  (Term.Name(fname), args) sites BEFORE the existing hardcoded
   *  pattern matches, so a registered intrinsic always wins. */
  private def dispatchIntrinsicJs(fname: String, argClause: Term.ArgClause): Option[String] =
    val qn = scalascript.ir.QualifiedName(fname)
    intrinsics.get(qn).map {
      case scalascript.backend.spi.RuntimeCall(target) =>
        // Named args (`f(name = expr)`) lower to a trailing options object
        // `{ name: expr, ... }` after the positionals.  Positional-only
        // calls are unaffected.  The runtime intrinsic reads the options
        // object (e.g. `GraphQL.resolvers({ query, mutation })`).
        val (named, positional) = argClause.values.partition(_.isInstanceOf[Term.Assign])
        val posJs = positional.map(genExpr)
        val argsJs =
          if named.isEmpty then posJs
          else
            val obj = named.collect {
              case Term.Assign(Term.Name(n), rhs) => s"$n: ${genExpr(rhs)}"
            }.mkString("{", ", ", "}")
            posJs :+ obj
        s"$target(${argsJs.mkString(", ")})"
      case scalascript.backend.spi.InlineCode(emit) =>
        val irArgs = argClause.values.map(termToIrJs)
        val ctx    = JsEmitContext
        emit(irArgs, ctx).value
      case _ =>
        // NativeImpl / HostCallback don't emit target source; fall
        // through to scalameta's default emission.
        argClause.values.map(genExpr).mkString(s"$fname(", ", ", ")")
    }

  /** Minimum-viable IrExpr conversion for intrinsic dispatch — only
   *  string / int / double / bool literals survive shape; everything
   *  else becomes a `VarRef` carrying the genExpr-emitted JS. */
  private def termToIrJs(t: Term): scalascript.ir.IrExpr = t match
    case Lit.String(s)  => scalascript.ir.Lit(scalascript.ir.LitValue.StringL(s))
    case Lit.Int(n)     => scalascript.ir.Lit(scalascript.ir.LitValue.IntL(n.toLong))
    case Lit.Long(n)    => scalascript.ir.Lit(scalascript.ir.LitValue.IntL(n))
    case Lit.Double(d)  => scalascript.ir.Lit(scalascript.ir.LitValue.DoubleL(d.toDouble))
    case Lit.Boolean(b) => scalascript.ir.Lit(scalascript.ir.LitValue.BoolL(b))
    case Lit.Unit()     => scalascript.ir.Lit(scalascript.ir.LitValue.UnitL)
    case other          => scalascript.ir.VarRef(genExpr(other))

  /** Stage 5+/A.5 — `JsGen`'s per-call-site EmitContext.  Stub for
   *  now; future intrinsics extend the trait surface as needed. */
  private object JsEmitContext extends scalascript.ir.EmitContext

  /** Generate a JS expression string for a scalameta Term. */
  def genExpr(term: Term): String = term match
    // Stage 5+/A.5 intrinsic dispatch — fires first.
    case Term.Apply.After_4_6_0(Term.Name(fname), argClause)
        if dispatchIntrinsicJs(fname, argClause).isDefined =>
      dispatchIntrinsicJs(fname, argClause).get

    // Stage 5+/B.3 — qualified intrinsic dispatch for `Obj.method(args)`.
    case Term.Apply.After_4_6_0(Term.Select(Term.Name(obj), Term.Name(method)), argClause)
        if dispatchIntrinsicJs(s"$obj.$method", argClause).isDefined =>
      dispatchIntrinsicJs(s"$obj.$method", argClause).get

    // Literals
    case Lit.Int(v)     => v.toString
    case Lit.Long(v)    => v.toString
    case Lit.Double(v)  => v.toString
    case Lit.Float(v)   => v.toString
    case Lit.String(v)  =>
      // Escape for JS string literal
      "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
    case Lit.Boolean(v) => v.toString
    case Lit.Char(v)    => "\"" + v.toString.replace("\"", "\\\"") + "\""
    case Lit.Unit()     => "undefined"
    case Lit.Null()     => "null"

    // Name lookup
    case Term.Name(name) => mapName(name)

    // Block
    case Term.Block(stats) =>
      if stats.isEmpty then "undefined"
      else genBlockAsIife(stats)

    // If/else — short-circuit when condition is a boolean literal
    case t: Term.If =>
      t.cond match
        case Lit.Boolean(true)  => genExpr(t.thenp)
        case Lit.Boolean(false) =>
          t.elsep match
            case Lit.Unit() => "undefined"
            case e          => genExpr(e)
        case _ =>
          val cond  = genExpr(t.cond)
          val thenp = genExpr(t.thenp)
          val elsep = t.elsep match
            case Lit.Unit() => "undefined"
            case e          => genExpr(e)
          s"($cond ? $thenp : $elsep)"

    // String interpolation
    case Term.Interpolate(Term.Name(prefix), parts, args)
        if prefix == "s" || prefix == "f" || prefix == "md"
        || prefix == "html" || prefix == "css" =>
      val sb2 = StringBuilder()
      sb2.append("`")
      for i <- parts.indices do
        val part = parts(i).asInstanceOf[Lit.String].value
        // Backslash first — replacing `\\` AFTER `` ` `` would double-escape
        // the backslash inserted by the `` ` `` step, breaking the JS
        // template literal.
        sb2.append(part.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$"))
        if i < args.length then
          val arg = args(i).asInstanceOf[Term]
          val argJs = genExpr(arg)
          // html"..." escapes interpolated values unless they're a raw() marker.
          val wrapped =
            if prefix == "html" then s"_html_interp($argJs)"
            else                     s"_show($argJs)"
          sb2.append("${").append(wrapped).append("}")
      sb2.append("`")
      val templateLiteral = sb2.toString
      if prefix == "md" then s"_md($templateLiteral)" else templateLiteral

    // Registered interpolator (InterpolatorRegistry) takes precedence.
    // User-defined interpolator: _ext_StringContext_prefix(_sc([...]), [arg1, arg2])
    // Args are packed into an array so the `args: Any*` param binds a list.
    case Term.Interpolate(Term.Name(prefix), parts, args) =>
      val partStrs = parts.map(_.asInstanceOf[Lit.String].value)
      scalascript.compiler.plugin.InterpolatorRegistry.lookup(prefix) match
        case Some(impl) =>
          val argsExpr = args.map(a => genExpr(a.asInstanceOf[Term]))
          impl.jsEmit(partStrs, argsExpr)
        case None =>
          val partsJs = partStrs.map(s => "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .mkString("[", ", ", "]")
          val argsJs = args.map(a => genExpr(a.asInstanceOf[Term])).mkString("[", ", ", "]")
          s"_ext_StringContext_$prefix(_sc($partsJs), $argsJs)"

    // Anonymous function with _ placeholders — stack-based param counting
    case t: Term.AnonymousFunction =>
      phCounters = 0 :: phCounters          // push fresh counter
      val bodyJs = genExpr(t.body)
      val count  = phCounters.head
      phCounters = phCounters.tail           // pop
      val params = (0 until count).map(i => s"_$$${i}")
      if params.isEmpty then s"() => $bodyJs"
      else s"(${params.mkString(", ")}) => $bodyJs"

    // Placeholder _ — increment top counter and return indexed name
    case _: Term.Placeholder =>
      val i = phCounters.headOption.getOrElse(0)
      phCounters = phCounters match
        case h :: t => (h + 1) :: t
        case Nil    => Nil
      s"_$$$i"

    // Lambda
    case Term.Function.After_4_6_0(paramClause, body) =>
      val params = paramClause.values.map(_.name.value)
      val bodyJs = body match
        case Term.Block(stats) => genBlockAsIife(stats)
        case expr              => genExpr(expr)
      if params.length == 1 then s"${params.head} => $bodyJs"
      else
        // Auto-tuple: when this lambda is passed somewhere that supplies a
        // single tuple-arg (e.g. `pairs.foreach((n, s) => ...)`, where the
        // callback receives one `[n, s]` array), destructure on entry.
        val arity   = params.length
        val joined  = params.mkString(", ")
        s"((...__a) => { const [$joined] = (__a.length === 1 && Array.isArray(__a[0]) && __a[0].length === $arity) ? __a[0] : __a; return $bodyJs; })"

    // Partial function { case ... => ... }
    case Term.PartialFunction(cases) =>
      val scrutVar = freshTmp()
      val casesJs = cases.map { c =>
        genCase(scrutVar, c)
      }.mkString(" else ")
      s"(($scrutVar) => { $casesJs else { throw new Error('Match failure: ' + _show($scrutVar)); } })"

    // Match
    case t: Term.Match =>
      val scrutVar = freshTmp()
      val scrutExpr = genExpr(t.expr)
      val casesJs = t.casesBlock.cases.map { c =>
        genCase(scrutVar, c)
      }.mkString(" else ")
      s"(($scrutVar => { $casesJs else { throw new Error('Match failure: ' + _show($scrutVar)); } })($scrutExpr))"

    // Tuple
    case Term.Tuple(elems) =>
      val elemsJs = elems.map(genExpr).mkString(", ")
      s"Object.assign([$elemsJs], {_isTuple: true})"

    // Assignment
    case Term.Assign(lhs, rhs) =>
      s"${genExpr(lhs)} = ${genExpr(rhs)}"

    // While
    case t: Term.While =>
      val cond = genExpr(t.expr)
      val body = genExpr(t.body)
      s"(() => { while ($cond) { $body; } })()"

    // For-do
    case t: Term.For =>
      genForDo(t.enumsBlock.enums, t.body)

    // For-yield
    case t: Term.ForYield =>
      genForYield(t.enumsBlock.enums, t.body)

    // new ClassName(args)
    case Term.New(Init.After_4_6_0(tpe, _, argClauses)) =>
      val typeName = tpe match { case Type.Name(n) => n; case _ => "?" }
      val args = argClauses.toList.flatMap(_.values).map(genExpr)
      s"$typeName(${args.mkString(", ")})"

    // Return
    case Term.Return(expr) =>
      genExpr(expr)  // We can't easily return from JS like Scala; treat as expression

    // summon[TC[T]]
    case t: Term.ApplyType =>
      (t.fun, t.argClause.values) match
        case (Term.Name("summon"), List(typeArg)) =>
          val key = typeArg match
            case n: Type.Name  => n.value
            case ta: Type.Apply =>
              val tc  = ta.tpe match { case n: Type.Name => n.value; case _ => "?" }
              val arg = ta.argClause.values match { case List(n: Type.Name) => n.value; case _ => "_" }
              s"${tc}_${arg}"
            case _ => "undefined"
          // Prefer a local CB param if one shadows this summon key
          cbSummonMap.getOrElse(key, key)
        case (Term.Name("Prism"), List(_, variantType)) =>
          val variantName = variantType match
            case n: Type.Name => n.value
            case _            => return "(()=>{ throw new Error('Prism[Outer, Variant]: Variant must be a simple type name'); })()"
          s"_makePrism('$variantName')"
        case _ => genExpr(t.fun)

    // v1.51.2 Streams — Source.empty / Sink.ignore / Sink.toList (field access, no args)
    case Term.Select(Term.Name("Source"), Term.Name("empty")) =>
      "_makeAsyncStream((async function*() {})(  ))"
    case Term.Select(Term.Name("Sink"), Term.Name("ignore")) =>
      "({ run: (src) => src.runDrain() })"
    case Term.Select(Term.Name("Sink"), Term.Name("toList")) =>
      "({ run: (src) => src.runToList() })"
    // Field/method selection without arguments
    case Term.Select(qual, name) =>
      val qualJs = genExpr(qual)
      name.value match
        // Built-in collection/string methods that need runtime dispatch (computed properties)
        case "head" | "tail" | "last" | "init" | "reverse" | "distinct" | "sorted" |
             "toList" | "toSet" | "sum" | "min" | "max" | "flatten" | "isEmpty" |
             "nonEmpty" | "size" | "length" | "keys" | "values" | "isDefined" |
             "toUpperCase" | "toLowerCase" | "trim" | "toInt" | "toDouble" | "toLong" |
             "abs" | "round" | "floor" | "ceil" | "zipWithIndex" | "nonEmpty" |
             "_1" | "_2" | "_3" | "_4" =>
          s"_dispatch($qualJs, '${name.value}', [])"
        case other =>
          // Direct property access for regular objects (case classes, typeclasses, etc.)
          // Use _dispatch for extension methods, but try direct property first
          s"_dispatch($qualJs, '$other', [])"

    // Special form: handle(body) { case Eff.op(args, resume) => ... }
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          genHandleForm(bodyArgClause.values.head.asInstanceOf[Term], pf.cases)
        case _ => s"/* invalid handle */ undefined"

    // Special form: runAsync(body) — built-in Async-effect driver.  Body
    // is CPS-emitted so Async.* ops build a Free tree; `_runAsync`
    // walks it and dispatches each op against the default handler.
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runAsync(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val awaitPrefix = if usesRunAsyncParallel then "await " else ""
      s"${awaitPrefix}_runAsyncParallel(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // Browser/client helper for Promise-returning typed HTTP clients.
    // Source form: `awaitClient(Messages.list())` → JS: `await Messages.list()`.
    case Term.Apply.After_4_6_0(Term.Name("awaitClient"), argClause)
        if argClause.values.size == 1 =>
      s"await ${genExpr(argClause.values.head.asInstanceOf[Term])}"

    // Client IndexedDB typed helper.
    // Source: `IndexedDb.store[Draft]("drafts")`
    // JS:     `IndexedDb.store("drafts", "Draft")`
    case Term.Apply.After_4_6_0(
          Term.ApplyType.After_4_6_0(Term.Select(Term.Name("IndexedDb"), Term.Name("store")), typeArgs),
          argClause
        ) =>
      val typeName = typeArgs.values.headOption match
        case Some(Type.Name(name)) => name
        case Some(other) => other.syntax
        case None => ""
      val args = argClause.values.map(v => genExpr(v.asInstanceOf[Term])).toList
      val jsArgs = args.headOption.toList ++ List(jsQuote(typeName)) ++ args.drop(1)
      s"IndexedDb.store(${jsArgs.mkString(", ")})"

    // Client ObjectStore sync helpers.
    // Source: `Sync.pull[Draft]("drafts")`
    // JS:     `Sync.pull("drafts", "Draft")`
    case Term.Apply.After_4_6_0(
          Term.ApplyType.After_4_6_0(Term.Select(Term.Name("Sync"), Term.Name(method)), typeArgs),
          argClause
        ) if method == "pull" || method == "push" || method == "sync" =>
      val typeName = typeArgs.values.headOption match
        case Some(Type.Name(name)) => name
        case Some(other) => other.syntax
        case None => ""
      val args = argClause.values.map(v => genExpr(v.asInstanceOf[Term])).toList
      val jsArgs = args.headOption.toList ++ List(jsQuote(typeName)) ++ args.drop(1)
      s"Sync.$method(${jsArgs.mkString(", ")})"
    case Term.Apply.After_4_6_0(
          Term.ApplyType.After_4_6_0(Term.Select(Term.Name("Sync"), Term.Name(method)), typeArgs),
          argClause
        ) if method == "put" || method == "remove" || method == "resolve" =>
      val typeName = typeArgs.values.headOption match
        case Some(Type.Name(name)) => name
        case Some(other) => other.syntax
        case None => ""
      val args = argClause.values.map(v => genExpr(v.asInstanceOf[Term])).toList
      val jsArgs = args.headOption.toList ++ List(jsQuote(typeName)) ++ args.drop(1)
      s"Sync.$method(${jsArgs.mkString(", ")})"

    // Storage handlers — file-backed (with optional path arg) and ephemeral
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), bodyArgClause)
        if bodyArgClause.values.size >= 1 =>
      val bodyJs = genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      val pathJs = bodyArgClause.values.lift(1).map(p => genExpr(p.asInstanceOf[Term])).getOrElse("null")
      s"_runStorage(() => $bodyJs, $pathJs)"
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runStorage(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])}, null)"

    // ── v1.6 Actors Phase 1 ─────────────────────────────────────────
    // `runActors { body }` — body is CPS-emitted so Actor.* ops build
    // a Free tree the scheduler walks.  When the module uses `runActors`
    // anywhere, the whole top level runs inside an async IIFE (see
    // `genModule`'s `needsAsync`), and `_runActors` is async so it yields
    // to Node's event loop between scheduler ticks; the `await` here
    // makes the caller observe the body's last expression value (instead
    // of a Promise) and lets surrounding statements see the actor body's
    // side effects finish before they run.
    case Term.Apply.After_4_6_0(Term.Name("runActors"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val awaitPrefix = if usesRunActors then "await " else ""
      s"${awaitPrefix}_runActors(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // `receive(timeout = N) { case … }` — same machinery as `receive`
    // but the matcher is registered with `wrapSome=true` and the
    // driver tracks a deadline.
    case Term.Apply.After_4_6_0(
            Term.Apply.After_4_6_0(Term.Name("receive"), timeoutArgClause),
            pfArgClause)
        if pfArgClause.values.size == 1 && timeoutArgClause.values.size == 1 =>
      val timeoutTerm = timeoutArgClause.values.head match
        case Term.Assign(Term.Name("timeout"), v) => v
        case other: Term                          => other
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcherJs = genReceiveMatcher(pf.cases)
          s"Actor.receive_t(_registerReceive($matcherJs), ${genExpr(timeoutTerm.asInstanceOf[Term])})"
        case _ => "/* invalid receive */ undefined"

    // `receive { case … }` — special form so we can synthesise the
    // matcher closure with the right CPS-emitted bodies.
    case Term.Apply.After_4_6_0(Term.Name("receive"), pfArgClause)
        if pfArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcherJs = genReceiveMatcher(pf.cases)
          s"Actor.receive_(_registerReceive($matcherJs))"
        case _ => "/* invalid receive */ undefined"

    // Spawn / self / exit — emit through the Actor runtime so they
    // produce _Perform nodes the scheduler picks up.
    case Term.Apply.After_4_6_0(Term.Name("spawn"), argClause)
        if argClause.values.size == 1 =>
      val thunk = argClause.values.head.asInstanceOf[Term]
      // The thunk's body is CPS-emitted so its Actor.* ops chain.
      thunk match
        case Term.Function.After_4_6_0(_, body) =>
          s"Actor.spawn(() => ${genCpsExpr(body)})"
        case other => s"Actor.spawn(${genExpr(other)})"
    case Term.Apply.After_4_6_0(Term.Name("spawn_link"), argClause)
        if argClause.values.size == 1 =>
      val thunk = argClause.values.head.asInstanceOf[Term]
      thunk match
        case Term.Function.After_4_6_0(_, body) =>
          s"Actor.spawn_link(() => ${genCpsExpr(body)})"
        case other => s"Actor.spawn_link(${genExpr(other)})"
    case Term.Apply.After_4_6_0(Term.Name("spawnBounded"), argClause)
        if argClause.values.size == 3 =>
      val capJs      = genExpr(argClause.values(0).asInstanceOf[Term])
      val overflowJs = genExpr(argClause.values(1).asInstanceOf[Term])
      val thunk      = argClause.values(2).asInstanceOf[Term]
      val thunkJs = thunk match
        case Term.Function.After_4_6_0(_, body) => s"() => ${genCpsExpr(body)}"
        case other => genExpr(other)
      s"Actor.spawnBounded($capJs, $overflowJs, $thunkJs)"
    case Term.Apply.After_4_6_0(Term.Name("self"), argClause)
        if argClause.values.isEmpty =>
      "Actor.self()"
    case Term.Apply.After_4_6_0(Term.Name("exit"), argClause)
        if argClause.values.size == 2 =>
      val pidJs    = genExpr(argClause.values(0).asInstanceOf[Term])
      val reasonJs = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.exit($pidJs, $reasonJs)"
    // v1.6 Phase 2 — supervision primitives
    case Term.Apply.After_4_6_0(Term.Name("link"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.link(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("monitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.monitor(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("demonitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.demonitor(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("trapExit"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.trapExit(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6 Phase 3 — distributed node primitives
    case Term.Apply.After_4_6_0(Term.Name("startNode"), argClause)
        if argClause.values.size >= 1 =>
      val nodeId = genExpr(argClause.values(0).asInstanceOf[Term])
      val url    = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.startNode($nodeId, $url)"
    case Term.Apply.After_4_6_0(Term.Name("connectNode"), argClause)
        if argClause.values.size >= 1 =>
      val url   = genExpr(argClause.values(0).asInstanceOf[Term])
      val token = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.connectNode($url, $token)"
    case Term.Apply.After_4_6_0(Term.Name("joinCluster"), argClause)
        if argClause.values.size >= 1 =>
      val seeds = genExpr(argClause.values(0).asInstanceOf[Term])
      val token = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.joinCluster($seeds, $token)"
    case Term.Apply.After_4_6_0(Term.Name("register"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.register(${genExpr(argClause.values(0).asInstanceOf[Term])}, ${genExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("whereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.whereis(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalRegister"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.globalRegister(${genExpr(argClause.values(0).asInstanceOf[Term])}, ${genExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalWhereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.globalWhereis(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — cluster visibility
    case Term.Apply.After_4_6_0(Term.Name("clusterMembers"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterMembers()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeClusterEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeClusterEvents()"
    // v1.23 — phi-accrual failure detector
    case Term.Apply.After_4_6_0(Term.Name("phiOf"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.phiOf(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("isSuspect"), argClause)
        if argClause.values.size >= 1 =>
      val nid = genExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
      s"Actor.isSuspect($nid, $thr)"
    // v1.23 — local node identity + phi vector
    case Term.Apply.After_4_6_0(Term.Name("selfNode"), argClause)
        if argClause.values.isEmpty =>
      "Actor.selfNode()"
    case Term.Apply.After_4_6_0(Term.Name("clusterHealth"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterHealth()"
    // v1.23 — cluster-wide failure detector
    case Term.Apply.After_4_6_0(Term.Name("broadcastHealth"), argClause)
        if argClause.values.isEmpty =>
      "Actor.broadcastHealth()"
    case Term.Apply.After_4_6_0(Term.Name("clusterIsDown"), argClause)
        if argClause.values.size >= 1 =>
      val nid = genExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
      s"Actor.clusterIsDown($nid, $thr)"
    // v1.23 — leader election (Bully)
    case Term.Apply.After_4_6_0(Term.Name("electLeader"), argClause)
        if argClause.values.isEmpty =>
      "Actor.electLeader()"
    case Term.Apply.After_4_6_0(Term.Name("currentLeader"), argClause)
        if argClause.values.isEmpty =>
      "Actor.currentLeader()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeLeaderEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeLeaderEvents()"
    case Term.Apply.After_4_6_0(Term.Name("setAutoReelect"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.setAutoReelect(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — protocol switch + history
    case Term.Apply.After_4_6_0(Term.Name("useRaftLeaderElection"), argClause)
        if argClause.values.isEmpty =>
      "Actor.useRaftLeaderElection()"
    case Term.Apply.After_4_6_0(Term.Name("useExternalCoordinator"), argClause)
        if argClause.values.size == 4 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.useExternalCoordinator(${vs(0)}, ${vs(1)}, ${vs(2)}, ${vs(3)})"
    case Term.Apply.After_4_6_0(Term.Name("leaderProtocol"), argClause)
        if argClause.values.isEmpty =>
      "Actor.leaderProtocol()"
    case Term.Apply.After_4_6_0(Term.Name("leaderHistory"), argClause)
        if argClause.values.isEmpty =>
      "Actor.leaderHistory()"
    // v1.23 — drain / rolling-restart
    case Term.Apply.After_4_6_0(Term.Name("setDraining"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.setDraining(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("isDraining"), argClause)
        if argClause.values.isEmpty =>
      "Actor.isDraining()"
    case Term.Apply.After_4_6_0(Term.Name("drainingPeers"), argClause)
        if argClause.values.isEmpty =>
      "Actor.drainingPeers()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeDrainEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeDrainEvents()"
    // v1.23 — cluster metrics aggregation
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricSet"), argClause)
        if argClause.values.size == 2 =>
      val n0 = genExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterMetricSet($n0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricGet"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricGet(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricSum"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricSum(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricNames"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterMetricNames()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeMetricEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeMetricEvents()"
    // v1.23 — auto-reconnect policy
    case Term.Apply.After_4_6_0(Term.Name("setReconnectPolicy"), argClause)
        if argClause.values.size == 2 =>
      val ini = genExpr(argClause.values(0).asInstanceOf[Term])
      val mx  = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.setReconnectPolicy($ini, $mx)"
    // v1.23 — periodic gossip re-discovery
    case Term.Apply.After_4_6_0(Term.Name("requestGossip"), argClause)
        if argClause.values.isEmpty =>
      "Actor.requestGossip()"
    // v1.23 — cluster configuration distribution
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigSet"), argClause)
        if argClause.values.size == 2 =>
      val k0 = genExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterConfigSet($k0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigGet"), argClause)
        if argClause.values.size == 1 =>
      val k0 = genExpr(argClause.values(0).asInstanceOf[Term])
      s"Actor.clusterConfigGet($k0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigKeys"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterConfigKeys()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeConfigEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeConfigEvents()"
    // v1.6.x — scheduled sends
    case Term.Apply.After_4_6_0(Term.Name("sendAfter"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.sendAfter(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("sendInterval"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.sendInterval(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("cancelTimer"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.cancelTimer(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6.x — process introspection
    case Term.Apply.After_4_6_0(Term.Name("processInfo"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.processInfo(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.10 Generator — generator { () => body } / generator[T] { () => body } / suspend(v)
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("generator"), _) | Term.Name("generator"),
        argClause) if argClause.values.size == 1 =>
      s"_makeGenerator(${extractGenBody(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("suspend"), argClause)
        if argClause.values.size == 1 =>
      s"(yield ${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.51.2 Streams — stream { body } / emit(x) / Source.from / Source.single / Source.empty
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("stream"), _) | Term.Name("stream"),
        argClause) if argClause.values.size == 1 =>
      val bodyJs = extractStreamBody(argClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream(($bodyJs)())"
    case Term.Apply.After_4_6_0(Term.Name("emit"), argClause)
        if argClause.values.size == 1 =>
      s"(yield ${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Source"), _) | Term.Name("Source"), Term.Name("from")),
        argClause) if argClause.values.size == 1 =>
      val xs = genExpr(argClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream((async function*(xs) { for (const v of xs) yield v; })($xs))"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Source"), _) | Term.Name("Source"), Term.Name("single")),
        argClause) if argClause.values.size == 1 =>
      val x = genExpr(argClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream((async function*() { yield $x; })(  ))"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Source"), _) | Term.Name("Source"), Term.Name("fromGenerator")),
        argClause) if argClause.values.size == 1 =>
      val gen = genExpr(argClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream((async function*(g) { for await (const v of { [Symbol.asyncIterator]() { return { async next() { const r = g.next(); return r === null ? { done: true } : { done: false, value: r._value }; } }; } }) yield v; })($gen))"
    // v1.51.1 Source.tick / Source.unfold / Source.fromCallback
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Source"), _) | Term.Name("Source"), Term.Name("tick")),
        argClause) if argClause.values.size == 1 =>
      val ms = genExpr(argClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream((async function*() { while(true) { if($ms>0) await new Promise(r=>setTimeout(r,$ms)); yield undefined; } })(  ))"
    // Source.unfold(seed)(f) — curried application
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(
          Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Source"), _) | Term.Name("Source"), Term.Name("unfold")),
          seedClause),
        fClause) if seedClause.values.size == 1 && fClause.values.size == 1 =>
      val seed = genExpr(seedClause.values.head.asInstanceOf[Term])
      val f    = genExpr(fClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream((async function*() { let _s=${seed}; while(true) { const _r=(${f})(_s); if(!_r||_r._type==='_None') break; const _t=_r.value; _s=_t[0]; yield _t[1]; } })(  ))"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Source"), _) | Term.Name("Source"), Term.Name("fromCallback")),
        argClause) if argClause.values.size == 1 =>
      val reg = genExpr(argClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream((async function*() { const _vs=[]; (${reg})(v=>_vs.push(v)); for(const v of _vs) yield v; })(  ))"
    // Sink companion methods
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Sink"), _) | Term.Name("Sink"), Term.Name("foreach")),
        argClause) if argClause.values.size == 1 =>
      val f = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ run: (src) => src.runForeach(${f}) })"
    // Sink.fold(z)(f) — curried
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(
          Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Sink"), _) | Term.Name("Sink"), Term.Name("fold")),
          zClause),
        fClause) if zClause.values.size == 1 && fClause.values.size == 1 =>
      val z = genExpr(zClause.values.head.asInstanceOf[Term])
      val f = genExpr(fClause.values.head.asInstanceOf[Term])
      s"({ run: async (src) => { let acc=${z}; for await (const v of src) acc=(${f})(acc,v); return acc; } })"
    // Flow companion methods
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("map")),
        argClause) if argClause.values.size == 1 =>
      val f = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.map(${f}) })"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("filter")),
        argClause) if argClause.values.size == 1 =>
      val p = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.filter(${p}) })"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("fromFunction")),
        argClause) if argClause.values.size == 1 =>
      val f = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.map(${f}) })"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("take")),
        argClause) if argClause.values.size == 1 =>
      val n = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.take(${n}) })"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("drop")),
        argClause) if argClause.values.size == 1 =>
      val n = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.drop(${n}) })"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("flatMap")),
        argClause) if argClause.values.size == 1 =>
      val f = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.flatMap(${f}) })"
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(
          Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("scan")),
          zClause),
        fClause) if zClause.values.size == 1 && fClause.values.size == 1 =>
      val z = genExpr(zClause.values.head.asInstanceOf[Term])
      val f = genExpr(fClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.scan(${z})(${f}) })"
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(
          Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("mapAsync")),
          nClause),
        fClause) if nClause.values.size == 1 && fClause.values.size == 1 =>
      val n = genExpr(nClause.values.head.asInstanceOf[Term])
      val f = genExpr(fClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.mapAsync(${n})(${f}) })"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("recover")),
        argClause) if argClause.values.size == 1 =>
      val h = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.recover(${h}) })"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("throttle")),
        argClause) if argClause.values.size == 1 =>
      val r = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.throttle(${r}) })"
    case Term.Apply.After_4_6_0(
        Term.Select(Term.ApplyType.After_4_6_0(Term.Name("Flow"), _) | Term.Name("Flow"), Term.Name("debounce")),
        argClause) if argClause.values.size == 1 =>
      val ms = genExpr(argClause.values.head.asInstanceOf[Term])
      s"({ apply: (src) => src.debounce(${ms}) })"
    // v1.9 Coroutine — coroutineCreate { () => body } / coroutineResume(co, in)
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("coroutineCreate"), _) | Term.Name("coroutineCreate"),
        argClause) if argClause.values.size == 1 =>
      s"_coroutineCreate(${extractCoroutineBody(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("coroutineResume"), _) | Term.Name("coroutineResume"),
        argClause) if argClause.values.size == 2 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"_coroutineResume(${vs(0)}, ${vs(1)})"

    // Special forms: computed / effect — wrap the by-name body as a
    // zero-arg thunk so the reactive scheduler can rerun it when its
    // signal deps change.
    case Term.Apply.After_4_6_0(Term.Name(react @ ("computed" | "effect")), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val bodyJs = genExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"$react(() => $bodyJs)"

    // v1.5 Tier 5 #20 — `validate { body }` collects all `require*`
    // errors raised inside `body` and returns Right(value) / Left(map).
    case Term.Apply.After_4_6_0(Term.Name("validate"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val bodyJs = genExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"validate(() => $bodyJs)"

    // Function application
    case app: Term.Apply =>
      genApply(app)

    // Infix
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val args = argClause.values
      // Constant folding: both operands are compile-time literals
      val constResult =
        if args.length == 1 then foldConstant(lhs, op.value, args.head) else None
      constResult.getOrElse {
        val lhsJs = genExpr(lhs)
        val rhsJs = if args.length == 1 then genExpr(args.head) else args.map(genExpr).mkString(", ")
        op.value match
          case "::" => s"[${genExpr(lhs)}, ...(${genExpr(args.head)})]"
          case ":+" => s"[...($lhsJs), ${genExpr(args.head)}]"
          case "+:" => s"[${genExpr(lhs)}, ...(${genExpr(args.head)})]"
          case "++" | ":::" => s"_tupleConcat($lhsJs, ${genExpr(args.head)})"
          // HTML DSL: `attr.cls := "hero"` builds an Attr object.
          case ":=" => s"_attr($lhsJs, $rhsJs)"
          // v1.6 actors: `pid ! msg` enqueues into the receiver's mailbox.
          case "!" => s"Actor.send($lhsJs, $rhsJs)"
          case "->" =>
            s"Object.assign([$lhsJs, $rhsJs], {_isTuple: true})"
          case "*" =>
            if isIntExpr(lhs) && args.headOption.exists(isIntExpr) then s"($lhsJs * $rhsJs)"
            else s"(typeof ($lhsJs) === 'string' ? ($lhsJs).repeat($rhsJs) : _arith('*', $lhsJs, $rhsJs))"
          // Exact numerics: value-based `==` for Decimal/BigInt when operands
          // aren't both statically Int (then native === is correct and faster).
          case "==" if isIntExpr(lhs) && args.headOption.exists(isIntExpr) => s"($lhsJs === $rhsJs)"
          case "!=" if isIntExpr(lhs) && args.headOption.exists(isIntExpr) => s"($lhsJs !== $rhsJs)"
          case "==" => s"_arith('==', $lhsJs, $rhsJs)"
          case "!=" => s"_arith('!=', $lhsJs, $rhsJs)"
          case "&&" => s"($lhsJs && $rhsJs)"
          case "||" => s"($lhsJs || $rhsJs)"
          case "to" =>
            // n to m → array [n, n+1, ..., m]
            s"_dispatch($lhsJs, 'to', [$rhsJs])"
          case "until" =>
            // n until m → array [n, n+1, ..., m-1]
            s"_dispatch($lhsJs, 'until', [$rhsJs])"
          case "/" if isIntExpr(lhs) && args.headOption.exists(isIntExpr) =>
            s"Math.trunc($lhsJs / $rhsJs)"
          // Exact numerics (v1.64): when operands aren't both statically Int,
          // route arithmetic/comparison through _arith so BigInt/Decimal work
          // (native JS `+` throws on BigInt+Number and can't add Decimal objects).
          // `+` keeps string-concat semantics (handled inside _arith's number path).
          case "+" | "-" | "/" | "%" | "<" | ">" | "<=" | ">="
              if !(isIntExpr(lhs) && args.headOption.exists(isIntExpr)) =>
            s"_arith('${op.value}', $lhsJs, $rhsJs)"
          case other => s"($lhsJs $other $rhsJs)"
      }

    // Prefix unary operators: `!x`, `-x`, `+x`, `~x`.
    case t: Term.ApplyUnary =>
      // Constant folding for literal operands
      (t.op.value, t.arg) match
        case ("-", Lit.Int(n))     => (-n).toString
        case ("-", Lit.Long(n))    => (-n).toString
        case ("-", Lit.Double(ns)) => (-ns.toDouble).toString
        case ("+", Lit.Int(n))     => n.toString
        case ("+", Lit.Long(n))    => n.toString
        case ("!", Lit.Boolean(b)) => (!b).toString
        case _ =>
          val argJs = genExpr(t.arg)
          t.op.value match
            case "!" => s"!($argJs)"
            case "-" => s"-($argJs)"
            case "+" => s"+($argJs)"
            case "~" => s"~($argJs)"
            case op  => s"/* unsupported unary $op */"

    case Term.Ascribe(inner, _) =>
      genExpr(inner)

    // throw expr — JS `throw` is a statement, wrap in an IIFE so it's
    // usable in expression position (e.g. inside a ternary). Mirrors the
    // Term.Throw lowering in genGenStatItem.
    case Term.Throw(expr) =>
      val errMsg = expr match
        case Term.New(init) =>
          init.argClauses.headOption.flatMap(_.values.headOption)
            .map(v => genExpr(v.asInstanceOf[Term]))
            .getOrElse("'error'")
        case Term.Apply.After_4_6_0(Term.Name("RuntimeException" | "Exception" | "Error"), argClause)
            if argClause.values.size == 1 =>
          genExpr(argClause.values.head.asInstanceOf[Term])
        case _ => genExpr(expr)
      s"(() => { throw new Error($errMsg); })()"

    // try { body } catch { case ... => ... } finally { ... }
    // Lowered to an IIFE so it works in expression position (val x = try …).
    // The catch handler reuses genCase, which produces an if/else chain that
    // returns from the IIFE; a trailing `throw errVar` rethrows when no case
    // matches (Scala semantics). Finally runs regardless.
    case Term.Try.After_4_9_9(bodyExpr, catchClauseOpt, finallyOpt) =>
      val bodyJs = genExpr(bodyExpr)
      val errVar = freshTmp()
      val cases  = catchClauseOpt.toList.flatMap(_.cases)
      val catchJs =
        if cases.isEmpty then s"throw $errVar;"
        else cases.map(c => genCase(errVar, c)).mkString(" else ") + s" else { throw $errVar; }"
      val finallyJs = finallyOpt.map { f => s" finally { ${genExpr(f)}; }" }.getOrElse("")
      s"(() => { try { return $bodyJs; } catch ($errVar) { $catchJs }$finallyJs })()"

    case other =>
      s"/* unsupported: ${other.productPrefix} */"

  private def genApply(app: Term.Apply): String =
    // f(regular)(using tc) — flatten all curried arg lists when the outermost
    // Apply carries a `using` clause, so the JS call passes all args at once.
    if app.argClause.mod.nonEmpty then
      def collectAllArgs(t: Term, acc: List[Term]): (Term, List[Term]) = t match
        case inner: Term.Apply => collectAllArgs(inner.fun, inner.argClause.values ++ acc)
        case other             => (other, acc)
      val (baseFun, allArgs) = collectAllArgs(app.fun, app.argClause.values)
      return s"_call(${genExpr(baseFun)}, ${allArgs.map(genExpr).mkString(", ")})"

    // .copy(field = value, ...) — spread the receiver, override named fields.
    // Intercepted before argVals are computed so Term.Assign doesn't fall into
    // genExpr's `lhs = rhs` path (which would emit a JS assignment expression).
    app.fun match
      case Term.Select(qual, Term.Name("copy")) =>
        return genCopy(qual, app.argClause.values)
      case _ => ()

    // Focus[T](_.a.b) / Focus(_.a.b) — emit a Lens object built from the
    // syntactic field path. The lambda body is inspected at codegen time;
    // letting it through normal genExpr would lose the path information.
    app.fun match
      case ta: Term.ApplyType if isFocusFun(ta.fun) =>
        return genFocus(app.argClause.values)
      case Term.Name("Focus") =>
        return genFocus(app.argClause.values)
      case _ => ()

    // direct[M] { stmts } — v1.8 do-notation sugar
    app.fun match
      case Term.ApplyType.After_4_6_0(Term.Name("direct"), typeArgClause) if app.argClause.values.size == 1 =>
        val typeArg = typeArgClause.values.headOption.getOrElse(Type.Name("?"))
        DirectTypeUtils.validateDirectTypeArg(typeArg)
        return (app.argClause.values.head match
          case block: Term.Block => genDirectBlock(block.stats)
          case single: Term      => genExpr(single)
          case null              => "undefined")
      case _ => ()

    // Named-arg reordering: when any arg is Term.Assign (name = expr),
    // look up the function's param order and reorder args to positional form.
    // Falls back to the original (potentially wrong) order when the function
    // is not in funcParamOrder (e.g. higher-order / imported functions).
    val rawArgs = app.argClause.values
    val hasNamedArgs = rawArgs.exists(_.isInstanceOf[Term.Assign])
    val argVals: List[String] =
      if !hasNamedArgs then rawArgs.map(genExpr)
      else
        // Extract the function name for param-order lookup.
        val fnNameOpt: Option[String] = app.fun match
          case Term.Name(n)             => Some(n)
          case Term.Select(_, Term.Name(n)) => Some(n)
          case _                        => None
        fnNameOpt.flatMap(funcParamOrder.get) match
          case Some(params) =>
            // Reorder: fill slots by name, then fill remaining with positionals.
            val slots = Array.fill[Option[String]](params.length)(None)
            rawArgs.foreach {
              case Term.Assign(Term.Name(n), rhs) =>
                val idx = params.indexOf(n)
                if idx >= 0 then slots(idx) = Some(genExpr(rhs))
              case _ => ()
            }
            val positionals = rawArgs.collect { case t if !t.isInstanceOf[Term.Assign] => genExpr(t) }.iterator
            for i <- slots.indices do
              if slots(i).isEmpty && positionals.hasNext then slots(i) = Some(positionals.next())
            // Emit up to the last filled slot; trailing Nones become undefined (JS default).
            val lastFilled = slots.lastIndexWhere(_.isDefined)
            if lastFilled < 0 then Nil
            else slots.take(lastFilled + 1).map(_.getOrElse("undefined")).toList
          case None =>
            // Function not in table — fall back: positionals first, then named by RHS only.
            rawArgs.map {
              case Term.Assign(_, rhs) => genExpr(rhs)
              case other               => genExpr(other)
            }
    app.fun match
      // Map constructor - args are tuple pairs
      case Term.Name("Map") =>
        s"_Map(${argVals.mkString(", ")})"

      // List constructor
      case Term.Name("List") =>
        s"[${argVals.mkString(", ")}]"

      // Some / None
      case Term.Name("Some") | Term.Name("_Some") =>
        s"_Some(${argVals.mkString(", ")})"

      // assert
      case Term.Name("assert") =>
        s"assert(${argVals.mkString(", ")})"

      // foldLeft curried: Apply(Apply(Select(xs, "foldLeft"), [init]), [f])
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldLeft")), initArgClause) =>
        val qualJs = genExpr(qual)
        val initJs = initArgClause.values.map(genExpr).mkString(", ")
        val fJs = argVals.mkString(", ")
        s"_seqFoldLeft($qualJs, $initJs, $fJs)"

      // foldRight curried
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldRight")), initArgClause) =>
        val qualJs = genExpr(qual)
        val initJs = initArgClause.values.map(genExpr).mkString(", ")
        val fJs = argVals.mkString(", ")
        s"(($qualJs).reduceRight(($fJs === undefined ? (acc,x) => acc : (acc,x) => ($fJs)(x, acc)), $initJs))"

      // Method calls: obj.method(args) → _dispatch(obj, "method", [args])
      case Term.Select(qual, Term.Name(method)) =>
        val qualJs = genExpr(qual)
        method match
          // String * n handled specially
          case _ =>
            val argsJs = argVals.mkString(", ")
            s"_dispatch($qualJs, '$method', [$argsJs])"

      // Regular function call or constructor — wrap in `_call` so a
      // bare Array / Map reference (`xs(i)` / `m(k)`) is dispatched as
      // indexing rather than failing with "not a function".
      case fun =>
        val funJs = genExpr(fun)
        s"_call($funJs, ${argVals.mkString(", ")})"

  // ─── Lenses / Focus / .copy ──────────────────────────────────────

  private def isFocusFun(t: Term): Boolean = t match
    case Term.Name("Focus") => true
    case _                  => false

  private def genCopy(qual: Term, args: List[Term]): String =
    val qualJs = genExpr(qual)
    val positional = args.collect {
      case t if !t.isInstanceOf[Term.Assign] => genExpr(t)
    }
    val named = args.collect {
      case Term.Assign(Term.Name(field), rhs) => s"$field: ${genExpr(rhs)}"
    }
    if positional.isEmpty then
      // All-named — emit a plain spread for clarity / speed.
      if named.isEmpty then s"({...$qualJs})"
      else s"({...$qualJs, ${named.mkString(", ")}})"
    else
      // Mixed or all-positional — route through the `_copy` runtime helper,
      // which uses the object's own key order to map positionals to fields.
      val posArr = s"[${positional.mkString(", ")}]"
      val namedObj = if named.isEmpty then "{}" else s"{${named.mkString(", ")}}"
      s"_copy($qualJs, $posArr, $namedObj)"

  private def genFocus(args: List[Term]): String = args match
    case List(lambda) =>
      val stepsOpt: Option[List[String]] = lambda match
        case Term.AnonymousFunction(body) =>
          extractPathSteps(body, _.isInstanceOf[Term.Placeholder])
        case Term.Function.After_4_6_0(paramClause, body) =>
          paramClause.values.headOption.map(_.name.value).flatMap { p =>
            extractPathSteps(body, {
              case Term.Name(n) => n == p
              case _            => false
            })
          }
        case _ => None
      stepsOpt match
        case Some(steps) if steps.nonEmpty =>
          // Steps are JS-code fragments: each entry is a literal that goes
          // straight into the array.  Field / __some__ / __each__ encode
          // as plain strings ('field' / '__some__' / '__each__'); v0.9
          // `.index(i)` / `.at(k)` encode as small object literals
          // (`{kind:'index',i:3}` / `{kind:'at',key:'u-42'}`).
          val stepLiterals = s"[${steps.mkString(", ")}]"
          val hasIndexOrAt =
            steps.exists(s => s.startsWith("{kind:'index'") || s.startsWith("{kind:'at'"))
          if steps.contains("'__each__'")                      then s"_makeTraversal($stepLiterals)"
          else if steps.contains("'__some__'") || hasIndexOrAt then s"_makeOptional($stepLiterals)"
          else                                                       s"_makeLens($stepLiterals)"
        case _ =>
          s"(()=>{ throw new Error('Focus: expected a field-access lambda like _.field.subfield'); })()"
    case _ =>
      s"(()=>{ throw new Error('Focus expects exactly one lambda argument'); })()"

  private def extractPathSteps(body: Term, isBase: Term => Boolean): Option[List[String]] =
    def jsLit(lit: Lit): Option[String] = lit match
      case Lit.String(v)  => Some("\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
      case Lit.Int(v)     => Some(v.toString)
      case Lit.Long(v)    => Some(v.toString)
      case Lit.Double(v)  => Some(v.toString)
      case Lit.Boolean(v) => Some(v.toString)
      case _              => None
    def loop(t: Term, acc: List[String]): Option[List[String]] = t match
      case Term.Select(qual, Term.Name("some")) => loop(qual, "'__some__'" :: acc)
      case Term.Select(qual, Term.Name("each")) => loop(qual, "'__each__'" :: acc)
      // v0.9 pointwise — `.index(i)` / `.at(k)`.
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("index")), argClause)
          if argClause.values.size == 1 =>
        argClause.values.head match
          case Lit.Int(i)  => loop(qual, s"{kind:'index',i:$i}" :: acc)
          case Lit.Long(i) => loop(qual, s"{kind:'index',i:$i}" :: acc)
          case _           => None
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("at")), argClause)
          if argClause.values.size == 1 =>
        argClause.values.head match
          case lit: Lit => jsLit(lit).flatMap(js => loop(qual, s"{kind:'at',key:$js}" :: acc))
          case _        => None
      case Term.Select(qual, name)              => loop(qual, s"'${name.value}'" :: acc)
      case other if isBase(other)               => Some(acc)
      case _                                     => None
    loop(body, Nil)

  // ─── CPS codegen for effectful contexts ──────────────────────────
  //
  // Inside a handle body (or the body of an effectful function), expressions
  // are emitted in CPS form: every operation that may depend on a Free value
  // is threaded through `_bind`. Plain JS values double as Pure(value), so
  // pure sub-expressions don't pay any wrapping overhead.
  //
  // genCpsExpr(t) returns a JS expression that evaluates to a Free value:
  // either a plain JS value (Pure) or a {_tag:'Perform', eff, op, args, k} node.

  /** Whether `t` is a syntactically simple value reference: no sub-computation,
   *  guaranteed not to be a Perform. Used to avoid pointless `_bind` chains. */
  private def isSimpleCpsExpr(t: Term): Boolean = t match
    case _: Lit                                  => true
    case _: Term.Placeholder                     => true
    case Term.Name(n) if !isEffectfulFun(n)      => true
    case _                                       => false

  /** Bind a list of CPS sub-expressions; pass their resulting plain values to k.
   *  Simple sub-expressions are inlined without a bind. */
  private def bindArgsCps(args: List[Term])(k: List[String] => String): String =
    def loop(remaining: List[Term], acc: List[String]): String = remaining match
      case Nil       => k(acc.reverse)
      case t :: rest =>
        if isSimpleCpsExpr(t) then loop(rest, genExpr(t) :: acc)
        else
          val v = freshTmp()
          s"_bind(${genCpsExpr(t)}, $v => ${loop(rest, v :: acc)})"
    loop(args, Nil)

  /** Generate a JS expression in CPS form. */
  private def genCpsExpr(term: Term): String = term match
    // Literals / names — pure values pass straight through
    case _: Lit              => genExpr(term)
    case _: Term.Placeholder => genExpr(term)
    case Term.Name(_)        => genExpr(term)

    // Block — chain stats through _bind
    case Term.Block(stats) => genCpsBlockAsIife(stats)

    // If — bind cond, then branch (each branch is CPS)
    case t: Term.If =>
      val thenJs = genCpsExpr(t.thenp)
      val elseJs = t.elsep match
        case Lit.Unit() => "undefined"
        case e          => genCpsExpr(e)
      if isSimpleCpsExpr(t.cond) then s"(${genExpr(t.cond)} ? ($thenJs) : ($elseJs))"
      else
        val tmp = freshTmp()
        s"_bind(${genCpsExpr(t.cond)}, $tmp => $tmp ? ($thenJs) : ($elseJs))"

    // String interpolation — bind args
    case Term.Interpolate(Term.Name(prefix), parts, args)
        if prefix == "s" || prefix == "f" || prefix == "md" =>
      bindArgsCps(args.map(_.asInstanceOf[Term])) { vs =>
        val sb2 = StringBuilder()
        sb2.append("`")
        for i <- parts.indices do
          val part = parts(i).asInstanceOf[Lit.String].value
          // Backslash first — see twin in genExpr.
          sb2.append(part.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$"))
          if i < args.length then sb2.append("${_show(").append(vs(i)).append(")}")
        sb2.append("`")
        val templateLiteral = sb2.toString
        if prefix == "md" then s"_md($templateLiteral)" else templateLiteral
      }

    // Registered interpolator (CPS path) — InterpolatorRegistry takes precedence.
    // User-defined interpolator (CPS path): _ext_StringContext_prefix(_sc([...]), [...])
    case Term.Interpolate(Term.Name(prefix), parts, args) =>
      bindArgsCps(args.map(_.asInstanceOf[Term])) { vs =>
        val partStrs = parts.map(_.asInstanceOf[Lit.String].value)
        scalascript.compiler.plugin.InterpolatorRegistry.lookup(prefix) match
          case Some(impl) => impl.jsEmit(partStrs, vs.toList)
          case None =>
            val partsJs = partStrs.map(s => "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
              .mkString("[", ", ", "]")
            val argsJs = vs.mkString("[", ", ", "]")
            s"_ext_StringContext_$prefix(_sc($partsJs), $argsJs)"
      }

    // Tuple
    case Term.Tuple(elems) =>
      bindArgsCps(elems) { vs =>
        s"Object.assign([${vs.mkString(", ")}], {_isTuple: true})"
      }

    // Lambda — CPS body
    case Term.Function.After_4_6_0(paramClause, body) =>
      val params = paramClause.values.map(_.name.value)
      val bodyJs = body match
        case Term.Block(stats) => genCpsBlockAsIife(stats)
        case expr              => genCpsExpr(expr)
      if params.length == 1 then s"${params.head} => $bodyJs"
      else
        val arity  = params.length
        val joined = params.mkString(", ")
        s"((...__a) => { const [$joined] = (__a.length === 1 && Array.isArray(__a[0]) && __a[0].length === $arity) ? __a[0] : __a; return $bodyJs; })"

    // Anonymous function with placeholders — body is CPS
    case t: Term.AnonymousFunction =>
      phCounters = 0 :: phCounters
      val bodyJs = genCpsExpr(t.body)
      val count  = phCounters.head
      phCounters = phCounters.tail
      val params = (0 until count).map(i => s"_$$${i}")
      if params.isEmpty then s"() => $bodyJs"
      else s"(${params.mkString(", ")}) => $bodyJs"

    // Nested handle inside CPS body — returns Free that we treat like any value
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          genHandleForm(bodyArgClause.values.head.asInstanceOf[Term], pf.cases)
        case _ => "/* invalid handle */ undefined"

    // Nested runAsync inside CPS body
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runAsync(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val awaitPrefix = if usesRunAsyncParallel then "await " else ""
      s"${awaitPrefix}_runAsyncParallel(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // Nested storage handlers inside CPS body
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), bodyArgClause)
        if bodyArgClause.values.size >= 1 =>
      val bodyJs = genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      val pathJs = bodyArgClause.values.lift(1).map(p => genExpr(p.asInstanceOf[Term])).getOrElse("null")
      s"_runStorage(() => $bodyJs, $pathJs)"
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runStorage(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])}, null)"

    // ── v1.6 Actors Phase 1 (inside CPS body) ──────────────────────────
    case Term.Apply.After_4_6_0(Term.Name("runActors"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val awaitPrefix = if usesRunActors then "await " else ""
      s"${awaitPrefix}_runActors(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    case Term.Apply.After_4_6_0(
            Term.Apply.After_4_6_0(Term.Name("receive"), timeoutArgClause),
            pfArgClause)
        if pfArgClause.values.size == 1 && timeoutArgClause.values.size == 1 =>
      val timeoutTerm = timeoutArgClause.values.head match
        case Term.Assign(Term.Name("timeout"), v) => v
        case other: Term                          => other
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcherJs = genReceiveMatcher(pf.cases)
          s"Actor.receive_t(_registerReceive($matcherJs), ${genExpr(timeoutTerm.asInstanceOf[Term])})"
        case _ => "/* invalid receive */ undefined"

    case Term.Apply.After_4_6_0(Term.Name("receive"), pfArgClause)
        if pfArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcherJs = genReceiveMatcher(pf.cases)
          s"Actor.receive_(_registerReceive($matcherJs))"
        case _ => "/* invalid receive */ undefined"

    case Term.Apply.After_4_6_0(Term.Name("spawn"), argClause)
        if argClause.values.size == 1 =>
      // The spawn arg is a behavior thunk.  genCpsExpr on a Function
      // emits a lambda whose body is `_bind`-chained — exactly the
      // shape `Actor.spawn(thunk)` expects.
      s"Actor.spawn(${genCpsExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("spawn_link"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.spawn_link(${genCpsExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("spawnBounded"), argClause)
        if argClause.values.size == 3 =>
      val capJs      = genExpr(argClause.values(0).asInstanceOf[Term])
      val overflowJs = genExpr(argClause.values(1).asInstanceOf[Term])
      val thunkJs    = genCpsExpr(argClause.values(2).asInstanceOf[Term])
      s"Actor.spawnBounded($capJs, $overflowJs, $thunkJs)"
    case Term.Apply.After_4_6_0(Term.Name("self"), argClause)
        if argClause.values.isEmpty =>
      "Actor.self()"
    case Term.Apply.After_4_6_0(Term.Name("exit"), argClause)
        if argClause.values.size == 2 =>
      val pidJs    = genExpr(argClause.values(0).asInstanceOf[Term])
      val reasonJs = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.exit($pidJs, $reasonJs)"
    // v1.6 Phase 2 — supervision primitives (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("link"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.link(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("monitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.monitor(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("demonitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.demonitor(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("trapExit"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.trapExit(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6 Phase 3 — distributed node primitives (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("startNode"), argClause)
        if argClause.values.size >= 1 =>
      val nodeId = genExpr(argClause.values(0).asInstanceOf[Term])
      val url    = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.startNode($nodeId, $url)"
    case Term.Apply.After_4_6_0(Term.Name("connectNode"), argClause)
        if argClause.values.size >= 1 =>
      val url   = genExpr(argClause.values(0).asInstanceOf[Term])
      val token = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.connectNode($url, $token)"
    case Term.Apply.After_4_6_0(Term.Name("joinCluster"), argClause)
        if argClause.values.size >= 1 =>
      val seeds = genExpr(argClause.values(0).asInstanceOf[Term])
      val token = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.joinCluster($seeds, $token)"
    case Term.Apply.After_4_6_0(Term.Name("register"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.register(${genExpr(argClause.values(0).asInstanceOf[Term])}, ${genExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("whereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.whereis(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalRegister"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.globalRegister(${genExpr(argClause.values(0).asInstanceOf[Term])}, ${genExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalWhereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.globalWhereis(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — cluster visibility
    case Term.Apply.After_4_6_0(Term.Name("clusterMembers"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterMembers()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeClusterEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeClusterEvents()"
    // v1.23 — phi-accrual failure detector
    case Term.Apply.After_4_6_0(Term.Name("phiOf"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.phiOf(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("isSuspect"), argClause)
        if argClause.values.size >= 1 =>
      val nid = genExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
      s"Actor.isSuspect($nid, $thr)"
    // v1.23 — local node identity + phi vector
    case Term.Apply.After_4_6_0(Term.Name("selfNode"), argClause)
        if argClause.values.isEmpty =>
      "Actor.selfNode()"
    case Term.Apply.After_4_6_0(Term.Name("clusterHealth"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterHealth()"
    // v1.23 — cluster-wide failure detector
    case Term.Apply.After_4_6_0(Term.Name("broadcastHealth"), argClause)
        if argClause.values.isEmpty =>
      "Actor.broadcastHealth()"
    case Term.Apply.After_4_6_0(Term.Name("clusterIsDown"), argClause)
        if argClause.values.size >= 1 =>
      val nid = genExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
      s"Actor.clusterIsDown($nid, $thr)"
    // v1.23 — leader election (Bully)
    case Term.Apply.After_4_6_0(Term.Name("electLeader"), argClause)
        if argClause.values.isEmpty =>
      "Actor.electLeader()"
    case Term.Apply.After_4_6_0(Term.Name("currentLeader"), argClause)
        if argClause.values.isEmpty =>
      "Actor.currentLeader()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeLeaderEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeLeaderEvents()"
    case Term.Apply.After_4_6_0(Term.Name("setAutoReelect"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.setAutoReelect(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — protocol switch + history
    case Term.Apply.After_4_6_0(Term.Name("useRaftLeaderElection"), argClause)
        if argClause.values.isEmpty =>
      "Actor.useRaftLeaderElection()"
    case Term.Apply.After_4_6_0(Term.Name("useExternalCoordinator"), argClause)
        if argClause.values.size == 4 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.useExternalCoordinator(${vs(0)}, ${vs(1)}, ${vs(2)}, ${vs(3)})"
    case Term.Apply.After_4_6_0(Term.Name("leaderProtocol"), argClause)
        if argClause.values.isEmpty =>
      "Actor.leaderProtocol()"
    case Term.Apply.After_4_6_0(Term.Name("leaderHistory"), argClause)
        if argClause.values.isEmpty =>
      "Actor.leaderHistory()"
    // v1.23 — drain / rolling-restart
    case Term.Apply.After_4_6_0(Term.Name("setDraining"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.setDraining(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("isDraining"), argClause)
        if argClause.values.isEmpty =>
      "Actor.isDraining()"
    case Term.Apply.After_4_6_0(Term.Name("drainingPeers"), argClause)
        if argClause.values.isEmpty =>
      "Actor.drainingPeers()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeDrainEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeDrainEvents()"
    // v1.23 — cluster metrics aggregation
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricSet"), argClause)
        if argClause.values.size == 2 =>
      val n0 = genExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterMetricSet($n0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricGet"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricGet(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricSum"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricSum(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricNames"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterMetricNames()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeMetricEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeMetricEvents()"
    // v1.23 — auto-reconnect policy
    case Term.Apply.After_4_6_0(Term.Name("setReconnectPolicy"), argClause)
        if argClause.values.size == 2 =>
      val ini = genExpr(argClause.values(0).asInstanceOf[Term])
      val mx  = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.setReconnectPolicy($ini, $mx)"
    // v1.23 — periodic gossip re-discovery
    case Term.Apply.After_4_6_0(Term.Name("requestGossip"), argClause)
        if argClause.values.isEmpty =>
      "Actor.requestGossip()"
    // v1.23 — cluster configuration distribution
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigSet"), argClause)
        if argClause.values.size == 2 =>
      val k0 = genExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterConfigSet($k0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigGet"), argClause)
        if argClause.values.size == 1 =>
      val k0 = genExpr(argClause.values(0).asInstanceOf[Term])
      s"Actor.clusterConfigGet($k0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigKeys"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterConfigKeys()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeConfigEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeConfigEvents()"
    // v1.6.x — scheduled sends (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("sendAfter"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.sendAfter(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("sendInterval"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.sendInterval(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("cancelTimer"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.cancelTimer(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6.x — process introspection (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("processInfo"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.processInfo(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.10 Generator inside CPS body — generator { } / generator[T] { }
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("generator"), _) | Term.Name("generator"),
        argClause) if argClause.values.size == 1 =>
      val bodyJs = argClause.values.head match
        case Term.Function.After_4_6_0(_, body) =>
          genGeneratorBody(body.asInstanceOf[Term])
        case other => s"function*() { return ${genExpr(other.asInstanceOf[Term])}; }"
      s"_makeGenerator($bodyJs)"
    case Term.Apply.After_4_6_0(Term.Name("suspend" | "emit"), argClause)
        if argClause.values.size == 1 =>
      s"(yield ${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.51.2 Streams inside CPS body
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("stream"), _) | Term.Name("stream"),
        argClause) if argClause.values.size == 1 =>
      val bodyJs = extractStreamBody(argClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream(($bodyJs)())"

    // Nested computed / effect inside CPS body — same wrapping as the
    // non-CPS form: by-name body becomes a zero-arg thunk.
    case Term.Apply.After_4_6_0(Term.Name(react @ ("computed" | "effect")), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"$react(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // Apply — function or method call
    case app: Term.Apply =>
      genCpsApply(app)

    // Infix — bind both sides, then apply op
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val rhs = argClause.values.head
      bindArgsCps(List(lhs, rhs)) { case List(vl, vr) =>
        op.value match
          case "::"           => s"[$vl, ...$vr]"
          case ":+"           => s"[...$vl, $vr]"
          case "+:"           => s"[$vl, ...$vr]"
          case "++" | ":::"   => s"_tupleConcat($vl, $vr)"
          case "!"            => s"Actor.send($vl, $vr)"
          case "->"           => s"Object.assign([$vl, $vr], {_isTuple: true})"
          case "*"            =>
            if isIntExpr(lhs) && isIntExpr(rhs) then s"($vl * $vr)"
            else s"(typeof ($vl) === 'string' ? ($vl).repeat($vr) : ($vl) * ($vr))"
          case "=="           => s"($vl === $vr)"
          case "!="           => s"($vl !== $vr)"
          case "&&"           => s"($vl && $vr)"
          case "||"           => s"($vl || $vr)"
          case "to"           => s"_dispatch($vl, 'to', [$vr])"
          case "until"        => s"_dispatch($vl, 'until', [$vr])"
          case "/" if isIntExpr(lhs) && isIntExpr(rhs) => s"Math.trunc($vl / $vr)"
          case other          => s"($vl $other $vr)"
        case _ => "/* infix arity mismatch */"
      }

    // Select — bind qual, dispatch
    case Term.Select(qual, name) =>
      bindArgsCps(List(qual)) { case List(q) =>
        s"_dispatch($q, '${name.value}', [])"
        case _ => "/* select arity */"
      }

    // Match — bind scrutinee, then dispatch cases
    case t: Term.Match =>
      val scrutVar = freshTmp()
      val casesJs = t.casesBlock.cases.map(c => genCpsCase(scrutVar, c)).mkString(" else ")
      bindArgsCps(List(t.expr)) { case List(sv) =>
        s"(($scrutVar => { $casesJs else { throw new Error('Match failure: ' + _show($scrutVar)); } })($sv))"
        case _ => "/* match arity */"
      }

    // For-yield in CPS — fall back: the rhs collections / generators don't typically
    // perform effects, so direct codegen with bind on result suffices for now.
    case t: Term.ForYield => genForYield(t.enumsBlock.enums, t.body)
    case t: Term.For      => genForDo(t.enumsBlock.enums, t.body)

    // While — CPS not really meaningful (side-effecting loop). Fall back.
    case t: Term.While    => genExpr(t)

    // Return
    case Term.Return(expr) => genCpsExpr(expr)

    // Default: try direct codegen (covers values, partial functions, etc.)
    case other => genExpr(other)

  /** Call site in CPS mode: bind args, then call. Handles effect ops specially. */
  private def genCpsApply(app: Term.Apply): String =
    val args = app.argClause.values
    app.fun match
      // Effect op: Eff.op(args) → _bind args then _perform
      case Term.Select(Term.Name(eff), Term.Name(op)) if isEffectOpRef(eff, op) =>
        bindArgsCps(args) { vs =>
          s"_perform('$eff', '$op', [${vs.mkString(", ")}])"
        }

      // Builtin constructors
      case Term.Name("Map") =>
        bindArgsCps(args) { vs => s"_Map(${vs.mkString(", ")})" }
      case Term.Name("List") =>
        bindArgsCps(args) { vs => s"[${vs.mkString(", ")}]" }
      case Term.Name("Some") | Term.Name("_Some") =>
        bindArgsCps(args) { vs => s"_Some(${vs.mkString(", ")})" }
      case Term.Name("assert") =>
        bindArgsCps(args) { vs => s"assert(${vs.mkString(", ")})" }

      // foldLeft curried: bind qual + init + f
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldLeft")), initArgClause) =>
        bindArgsCps(qual :: initArgClause.values ++ args) { vs =>
          val q = vs.head; val init = vs(1); val f = vs(2)
          s"_seqFoldLeft($q, $init, $f)"
        }

      // foldRight curried
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldRight")), initArgClause) =>
        bindArgsCps(qual :: initArgClause.values ++ args) { vs =>
          val q = vs.head; val init = vs(1); val f = vs(2)
          s"(($q).reduceRight((acc, x) => ($f)(x, acc), $init))"
        }

      // Method call: obj.method(args) → _dispatch
      case Term.Select(qual, Term.Name(method)) =>
        bindArgsCps(qual :: args) { vs =>
          s"_dispatch(${vs.head}, '$method', [${vs.tail.mkString(", ")}])"
        }

      // Regular function call: bind args, then call (function value itself is simple)
      case fun =>
        if isSimpleCpsExpr(fun) then
          bindArgsCps(args) { vs =>
            s"${genExpr(fun)}(${vs.mkString(", ")})"
          }
        else
          bindArgsCps(fun :: args) { vs =>
            s"${vs.head}(${vs.tail.mkString(", ")})"
          }

  /** Block as IIFE in CPS form — chains statements through _bind. */
  private def genCpsBlockAsIife(stats: List[Stat]): String =
    if stats.isEmpty then "undefined"
    else
      def build(remaining: List[Stat]): String = remaining match
        case Nil => "undefined"
        case List(s) =>
          s match
            case t: Term => genCpsExpr(t)
            case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
              // Last statement is a binding — block evaluates to undefined.
              // Still bind it so its effects (if any) run.
              s"_bind(${genCpsExpr(rhs)}, ${n.value} => undefined)"
            case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
              s"_bind(${genCpsExpr(rhs)}, ${n.value} => undefined)"
            case stat =>
              s"(() => { ${genStatInline(stat)} return undefined; })()"
        case s :: rest =>
          s match
            case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
              s"_bind(${genCpsExpr(rhs)}, ${n.value} => ${build(rest)})"
            case Defn.Val(_, List(pat), _, rhs) =>
              val patJs = genPatDestructure(pat)
              val tmp = freshTmp()
              s"_bind(${genCpsExpr(rhs)}, $tmp => { const $patJs = $tmp; return ${build(rest)}; })"
            case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
              // For simplicity, treat var like val in CPS context.
              s"_bind(${genCpsExpr(rhs)}, ${n.value} => ${build(rest)})"
            case d: Defn.Def =>
              // Function definition in block — emit as nested function declaration
              val fnJs = genCpsInlineFn(d)
              s"((${d.name.value}) => ${build(rest)})($fnJs)"
            case t: Term =>
              if isSimpleCpsExpr(t) then s"(${genExpr(t)}, ${build(rest)})"
              else s"_bind(${genCpsExpr(t)}, _ => ${build(rest)})"
            case stat =>
              s"(() => { ${genStatInline(stat)} return ${build(rest)}; })()"
      build(stats)

  /** Emit a function definition as an inline function value in CPS form. */
  private def genCpsInlineFn(d: Defn.Def): String =
    val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
    val paramsStr = params.mkString(", ")
    d.body match
      case Term.Block(stats) => s"(${paramsStr}) => ${genCpsBlockAsIife(stats)}"
      case expr              => s"(${paramsStr}) => ${genCpsExpr(expr)}"

  /** CPS case generator — like genCase but the body is CPS. */
  private def genCpsCase(scrutVar: String, c: Case): String =
    val (cond, bindings) = genPattern(scrutVar, c.pat)
    val bindingStmts = bindings.map { case (name, expr) => s"const $name = $expr;" }.mkString(" ")
    val bodyJs = genCpsExpr(c.body)
    c.cond match
      case Some(guard) =>
        val guardExpr = genExpr(guard)
        val condStr = if cond == "true" then s"($guardExpr)" else s"($cond) && ($guardExpr)"
        s"if ($condStr) { $bindingStmts return $bodyJs; }"
      case None =>
        val condStr = if cond == "true" then "true" else s"($cond)"
        s"if ($condStr) { $bindingStmts return $bodyJs; }"

  private def genCase(scrutVar: String, c: Case): String =
    val (cond, bindings) = genPattern(scrutVar, c.pat)
    val bindingStmts = bindings.map { case (name, expr) => s"const $name = $expr;" }.mkString(" ")
    val bodyJs = genExpr(c.body)

    // Pattern guard: we need bindings set up before evaluating the guard.
    // We use a nested IIFE to set up bindings, evaluate guard, then run body.
    c.cond match
      case Some(guard) if bindings.nonEmpty =>
        // Put bindings and guard inside the if block
        val guardExpr = genExpr(guard)
        val patCond = if cond == "true" then "" else s"($cond) && "
        s"if (${patCond}(() => { $bindingStmts return $guardExpr; })()) { $bindingStmts return $bodyJs; }"
      case Some(guard) =>
        val guardExpr = genExpr(guard)
        val condStr = if cond == "true" then s"($guardExpr)" else s"($cond) && ($guardExpr)"
        s"if ($condStr) { return $bodyJs; }"
      case None =>
        val condStr = if cond == "true" then "true" else s"($cond)"
        s"if ($condStr) { $bindingStmts return $bodyJs; }"

  /** Returns (condition JS, list of (varName, expr) bindings).
   *  The condition must be true for the pattern to match.
   *  Bindings are set up when condition is true.
   */
  private def genPattern(scrutVar: String, pat: Pat): (String, List[(String, String)]) = pat match
    case Pat.Wildcard() =>
      ("true", Nil)

    case Pat.Var(n) =>
      ("true", List(n.value -> scrutVar))

    case lit: Lit =>
      val litJs = lit match
        case Lit.Int(v)     => v.toString
        case Lit.Long(v)    => v.toString
        case Lit.Double(v)  => v.toString
        case Lit.String(v)  => "\"" + v.replace("\"", "\\\"") + "\""
        case Lit.Boolean(v) => v.toString
        case Lit.Null()     => "null"
        case _              => "undefined"
      (s"$scrutVar === $litJs", Nil)

    case Pat.Typed(inner, tpe) =>
      // Emit a type-test guard for union-type narrowing: `case s: String =>`.
      // Map the declared type name to a JS typeof / instanceof check.
      val typeName = tpe match
        case Type.Name(n)   => n
        case ta: Type.Apply => ta.tpe match { case Type.Name(n) => n; case _ => "" }
        case _              => ""
      val typeCond = typeName match
        case "String"  => s"(typeof $scrutVar === 'string')"
        case "Int" | "Long" | "Double" | "Float" | "Number" =>
          s"(typeof $scrutVar === 'number')"
        case "Boolean" => s"(typeof $scrutVar === 'boolean')"
        case ""        => "true"    // unknown type — fall through
        case _         => s"($scrutVar && $scrutVar._type === '$typeName')"
      val (innerCond, bindings) = genPattern(scrutVar, inner)
      val cond =
        if typeCond == "true" then innerCond
        else if innerCond == "true" then typeCond
        else s"$typeCond && $innerCond"
      (cond, bindings)

    case Pat.Tuple(pats) =>
      val subConditions = pats.zipWithIndex.map { (p, i) =>
        genPattern(s"$scrutVar[$i]", p)
      }
      val cond = subConditions.map(_._1).filter(_ != "true").mkString(" && ")
      val bindings = subConditions.flatMap(_._2)
      (if cond.isEmpty then "true" else cond, bindings)

    case Pat.Extract.After_4_6_0(fn, argClause) =>
      val typeName = fn match
        case Term.Name(n)                 => n
        case Term.Select(_, Term.Name(n)) => n
        case _                            => "?"
      val args = argClause.values

      typeName match
        case "Some" =>
          val innerScrutVar = s"$scrutVar.value"
          val subConds = if args.isEmpty then Nil
            else args.zipWithIndex.map { (p, i) =>
              genPattern(if args.length == 1 then innerScrutVar else s"$innerScrutVar[$i]", p)
            }
          val subCond = subConds.map(_._1).filter(_ != "true").mkString(" && ")
          val bindings = subConds.flatMap(_._2)
          val cond = s"($scrutVar && $scrutVar._type === '_Some')" +
            (if subCond.nonEmpty then s" && $subCond" else "")
          (cond, bindings)

        case "None" =>
          (s"($scrutVar && $scrutVar._type === '_None')", Nil)

        case _ =>
          // Case class or enum case extract
          val fields = args.zipWithIndex.map { (p, i) =>
            genPattern(s"Object.values($scrutVar).slice(1)[$i]", p)
          }
          val typeCond = s"($scrutVar && $scrutVar._type === '$typeName')"
          val subCond = fields.map(_._1).filter(_ != "true").mkString(" && ")
          val bindings = fields.flatMap(_._2)
          val cond = typeCond + (if subCond.nonEmpty then s" && $subCond" else "")
          (cond, bindings)

    case Pat.Alternative(lhs, rhs) =>
      // Either alternative matches, no bindings from alternatives typically
      val (lCond, _) = genPattern(scrutVar, lhs)
      val (rCond, _) = genPattern(scrutVar, rhs)
      (s"($lCond || $rCond)", Nil)

    // @ binder: `xs @ pattern` — bind `xs` to the whole scrutinee, then match `pattern`
    case Pat.Bind(lhs: Pat.Var, rhs) =>
      val (cond, bindings) = genPattern(scrutVar, rhs)
      (cond, (lhs.name.value -> scrutVar) :: bindings)

    // Enum singleton reference: case Red => or case Color.Red =>
    case t: Term.Name =>
      t.value match
        case "None" => (s"($scrutVar && $scrutVar._type === '_None')", Nil)
        case n      => (s"($scrutVar === $n || ($scrutVar && $scrutVar._type === '$n'))", Nil)

    case Term.Select(_, Term.Name(n)) =>
      if n == "None" then (s"($scrutVar && $scrutVar._type === '_None')", Nil)
      else (s"($scrutVar === $n || ($scrutVar && $scrutVar._type === '$n'))", Nil)

    case _ =>
      ("true", Nil)

  private def genForDo(enums: List[Enumerator], body: Term): String =
    if enumeratorsNeedAsyncFor(enums) then genAsyncForDo(enums, body)
    else genForDoHelper(enums, genExpr(body))

  private def genForDoHelper(enums: List[Enumerator], bodyJs: String): String = enums match
    case Nil => s"(() => { $bodyJs; })()"
    case Enumerator.Generator(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val iterVar = freshTmp()
      val patJs = genForPatBinding(pat, iterVar)
      val inner = genForDoHelper(rest, bodyJs)
      s"(() => { _dispatch($rhsJs, 'forEach', [($iterVar) => { $patJs $inner; }]); })()"
    case Enumerator.Guard(cond) :: rest =>
      val condJs = genExpr(cond)
      val inner = genForDoHelper(rest, bodyJs)
      s"(() => { if ($condJs) { $inner; } })()"
    case Enumerator.Val(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val v = freshTmp()
      val patJs = genForPatBinding(pat, v)
      val inner = genForDoHelper(rest, bodyJs)
      s"(() => { const $v = $rhsJs; $patJs $inner; })()"
    case _ :: rest => genForDoHelper(rest, bodyJs)

  private def genForYield(enums: List[Enumerator], body: Term): String =
    if enumeratorsNeedAsyncFor(enums) then genAsyncForYield(enums, body)
    else genForYieldHelper(enums, genExpr(body))

  private def genForYieldHelper(enums: List[Enumerator], bodyJs: String): String = enums match
    case Nil => bodyJs
    case Enumerator.Generator(pat, rhs) :: Nil =>
      val rhsJs = genExpr(rhs)
      val iterVar = freshTmp()
      val patJs = genForPatBinding(pat, iterVar)
      if patJs.isEmpty then
        s"_dispatch($rhsJs, 'map', [($iterVar) => $bodyJs])"
      else
        s"_dispatch($rhsJs, 'map', [($iterVar) => { $patJs return $bodyJs; }])"
    case Enumerator.Generator(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val iterVar = freshTmp()
      val patJs = genForPatBinding(pat, iterVar)
      val inner = genForYieldHelper(rest, bodyJs)
      if patJs.isEmpty then
        s"_dispatch($rhsJs, 'flatMap', [($iterVar) => $inner])"
      else
        s"_dispatch($rhsJs, 'flatMap', [($iterVar) => { $patJs return $inner; }])"
    case Enumerator.Guard(cond) :: rest =>
      val condJs = genExpr(cond)
      val inner = genForYieldHelper(rest, bodyJs)
      // wrap in filter; but we need the generator context
      // For guard as first enum (unusual), filter is not trivially accessible
      // Return inner filtered - but we don't have a collection here, use conditional
      s"($condJs ? [$inner] : [])"
    case Enumerator.Val(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val v = freshTmp()
      val patJs = genForPatBinding(pat, v)
      val inner = genForYieldHelper(rest, bodyJs)
      s"(() => { const $v = $rhsJs; $patJs return $inner; })()"
    case _ :: rest => genForYieldHelper(rest, bodyJs)

  // Async for-yield: all generators use awaitClient → sequential awaits in async IIFE.
  // Returns a Promise; wrap with awaitClient(...) at the call site to get the value.
  private def genAsyncForYield(enums: List[Enumerator], body: Term): String =
    val stmts = scala.collection.mutable.ListBuffer[String]()
    for e <- enums do e match
      case Enumerator.Generator(pat, Term.Apply.After_4_6_0(Term.Name("awaitClient"), argClause))
          if argClause.values.size == 1 =>
        val promiseJs = genExpr(argClause.values.head.asInstanceOf[Term])
        val iterVar = freshTmp()
        val patJs = genForPatBinding(pat, iterVar)
        stmts += (if patJs.isEmpty then s"const $iterVar = await $promiseJs;"
                  else s"const $iterVar = await $promiseJs; $patJs")
      case Enumerator.Generator(pat, rhs) =>
        val rhsJs = genExpr(rhs)
        val iterVar = freshTmp()
        val patJs = genForPatBinding(pat, iterVar)
        stmts += (if patJs.isEmpty then s"const $iterVar = $rhsJs;"
                  else s"const $iterVar = $rhsJs; $patJs")
      case Enumerator.Guard(cond) =>
        stmts += s"if (!(${genExpr(cond)})) return undefined;"
      case Enumerator.Val(pat, rhs) =>
        val rhsJs = genExpr(rhs)
        val v = freshTmp()
        val patJs = genForPatBinding(pat, v)
        stmts += (if patJs.isEmpty then s"const $v = $rhsJs;"
                  else s"const $v = $rhsJs; $patJs")
      case _ => ()
    val bodyJs = genExpr(body)
    s"(async () => { ${stmts.mkString(" ")} return $bodyJs; })()"

  // Async for-do: all generators use awaitClient → sequential awaits in async IIFE.
  private def genAsyncForDo(enums: List[Enumerator], body: Term): String =
    val stmts = scala.collection.mutable.ListBuffer[String]()
    for e <- enums do e match
      case Enumerator.Generator(pat, Term.Apply.After_4_6_0(Term.Name("awaitClient"), argClause))
          if argClause.values.size == 1 =>
        val promiseJs = genExpr(argClause.values.head.asInstanceOf[Term])
        val iterVar = freshTmp()
        val patJs = genForPatBinding(pat, iterVar)
        stmts += (if patJs.isEmpty then s"const $iterVar = await $promiseJs;"
                  else s"const $iterVar = await $promiseJs; $patJs")
      case Enumerator.Generator(pat, rhs) =>
        val rhsJs = genExpr(rhs)
        val iterVar = freshTmp()
        val patJs = genForPatBinding(pat, iterVar)
        stmts += (if patJs.isEmpty then s"const $iterVar = $rhsJs;"
                  else s"const $iterVar = $rhsJs; $patJs")
      case Enumerator.Guard(cond) =>
        stmts += s"if (!(${genExpr(cond)})) return;"
      case Enumerator.Val(pat, rhs) =>
        val rhsJs = genExpr(rhs)
        val v = freshTmp()
        val patJs = genForPatBinding(pat, v)
        stmts += (if patJs.isEmpty then s"const $v = $rhsJs;"
                  else s"const $v = $rhsJs; $patJs")
      case _ => ()
    val bodyJs = genExpr(body)
    s"(async () => { ${stmts.mkString(" ")} $bodyJs; })()"

  // ─── direct[M] { ... } — v1.8 do-notation ────────────────────────

  private def checkDirectBlockStatics(stats: List[Stat]): Unit =
    def isNestedDirect(t: Tree): Boolean = t match
      case app: Term.Apply =>
        app.fun match
          case Term.ApplyType.After_4_6_0(Term.Name("direct"), _) => true
          case _ => false
      case _ => false
    def go(t: Tree): Unit = t match
      case _: Term.Return =>
        throw new RuntimeException("'return' inside a direct block escapes the flatMap chain — for early failure use the monad's zero (None, Nil, Left(err), …) instead")
      case _ if isNestedDirect(t) => ()
      case _: Defn.Def | _: Term.Function => ()
      case other => other.children.foreach(go)
    stats.foreach(go)

  private def genDirectBlock(stats: List[Stat]): String =
    checkDirectBlockStatics(stats)
    val expanded = DirectAnorm.expand(stats)
    if expanded.isEmpty then "undefined"
    else
      val varNames: Set[String] = expanded.collect {
        case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, _) => n.value
      }.toSet
      def go(remaining: List[Stat]): String = remaining match
        case Nil => "undefined"
        case List(t: Term)  => genExpr(t)
        case List(other)    => s"(() => { ${genStatInline(other)} return undefined; })()"
        case Term.Assign(Term.Name(x), rhs) :: rest if varNames.contains(x) =>
          s"(() => { $x = ${genExpr(rhs)}; return ${go(rest)}; })()"
        case Term.Assign(Term.Name(x), rhs) :: rest =>
          s"_dispatch(${genExpr(rhs)}, 'flatMap', [($x) => ${go(rest)}])"
        case Defn.Val(_, List(_: Pat.Wildcard), _, rhs) :: rest =>
          s"_dispatch(${genExpr(rhs)}, 'flatMap', [(_) => ${go(rest)}])"
        case Defn.Val(_, List(Pat.Var(n)), _, rhs) :: rest =>
          s"(() => { const ${n.value} = ${genExpr(rhs)}; return ${go(rest)}; })()"
        case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) :: rest =>
          s"(() => { let ${n.value} = ${genExpr(rhs)}; return ${go(rest)}; })()"
        case (t: Term) :: rest =>
          s"(() => { ${genExpr(t)}; return ${go(rest)}; })()"
        case _ :: rest => go(rest)
      go(expanded)

  private def genForPatBinding(pat: Pat, scrutVar: String): String = pat match
    case Pat.Var(n) if n.value == scrutVar => ""
    case Pat.Var(n) => s"const ${n.value} = $scrutVar;"
    case Pat.Wildcard() => ""
    case Pat.Tuple(pats) =>
      pats.zipWithIndex.map { (p, i) =>
        p match
          case Pat.Var(n) => s"const ${n.value} = $scrutVar[$i];"
          case Pat.Wildcard() => ""
          case inner => genForPatBinding(inner, s"$scrutVar[$i]")
      }.mkString(" ")
    case Pat.Extract.After_4_6_0(_, argClause) =>
      argClause.values.zipWithIndex.map { (p, i) =>
        p match
          case Pat.Var(n) => s"const ${n.value} = Object.values($scrutVar).slice(1)[$i];"
          case _ => ""
      }.mkString(" ")
    // @ binder: bind the whole scrutinee to the name, then destructure rhs
    case Pat.Bind(lhs: Pat.Var, rhs) =>
      val rhsBindings = genForPatBinding(rhs, scrutVar)
      val lhsBinding  = if lhs.name.value == scrutVar then "" else s"const ${lhs.name.value} = $scrutVar;"
      s"$lhsBinding $rhsBindings".trim
    case _ => ""

  private def mapName(name: String): String = name match
    case "println" => "_println"
    case "print"   => "_print"
    case "Some"    => "_Some"
    case "None"    => "_None"
    case other     => paramRenames.getOrElse(other, other)

  private def withParamRenames[A](renames: Map[String, String])(f: => A): A =
    paramRenames ++= renames
    try f finally paramRenames --= renames.keys

  /** Returns true if the term is provably integer-valued (no decimal arithmetic). */
  private def isIntExpr(t: Term): Boolean = t match
    case _: Lit.Int | _: Lit.Long                 => true
    case _: Lit.Double | _: Lit.Float              => false
    case Term.Name(n)                              => intVars.contains(n)
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("toInt" | "toLong")), _) => true
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("toDouble" | "toFloat")), _) => false
    // Collection / String methods that always return an Int regardless of the
    // element type — both the field-access (`xs.length`) and the apply
    // (`xs.indexOf(x)`) forms.
    case Term.Select(_, Term.Name("length" | "size")) => true
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("length" | "size" | "indexOf" | "lastIndexOf" | "count")), _) => true
    // `xs.foldLeft(init)(_ + _)` returns whatever `init` is — Int when the
    // seed literal is Int.
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Select(_, Term.Name("foldLeft" | "foldRight" | "fold")), initClause),
        _) => initClause.values.headOption.exists(isIntExpr)
    case Term.ApplyInfix.After_4_6_0(l, Term.Name(op), _, argClause)
        if Set("+", "-", "*", "/", "%").contains(op) =>
      argClause.values.headOption.exists(r => isIntExpr(l) && isIntExpr(r))
    case _ => false

  /** Escape a string value for a JS string literal (double-quoted). */
  private def escapeJsString(s: String): String =
    s.replace("\\", "\\\\")
     .replace("\"", "\\\"")
     .replace("\n", "\\n")
     .replace("\r", "\\r")
     .replace("\t", "\\t")

  /** Try to evaluate a binary infix expression at compile time.
   *  Returns Some(js) when both operands are literals and the op is foldable.
   *  Returns None when runtime evaluation is required.
   */
  private def foldConstant(lhs: Term, op: String, rhs: Term): Option[String] =
    (lhs, rhs) match
      case (Lit.Int(a), Lit.Int(b)) => op match
        case "+"  => Some((a + b).toString)
        case "-"  => Some((a - b).toString)
        case "*"  => Some((a * b).toString)
        case "/"  if b != 0 => Some((a / b).toString)
        case "%"  if b != 0 => Some((a % b).toString)
        case "<"  => Some((a < b).toString)
        case ">"  => Some((a > b).toString)
        case "<=" => Some((a <= b).toString)
        case ">=" => Some((a >= b).toString)
        case "==" => Some((a == b).toString)
        case "!=" => Some((a != b).toString)
        case _    => None
      case (Lit.Long(a), Lit.Long(b)) => op match
        case "+"  => Some((a + b).toString)
        case "-"  => Some((a - b).toString)
        case "*"  => Some((a * b).toString)
        case "/"  if b != 0 => Some((a / b).toString)
        case "%"  if b != 0 => Some((a % b).toString)
        case "<"  => Some((a < b).toString)
        case ">"  => Some((a > b).toString)
        case "<=" => Some((a <= b).toString)
        case ">=" => Some((a >= b).toString)
        case "==" => Some((a == b).toString)
        case "!=" => Some((a != b).toString)
        case _    => None
      case (Lit.Double(as), Lit.Double(bs)) =>
        // Lit.Double.value is a String in scalameta 4.x
        val a = as.toDouble; val b = bs.toDouble
        op match
          case "+"  => Some((a + b).toString)
          case "-"  => Some((a - b).toString)
          case "*"  => Some((a * b).toString)
          case "/"  => Some((a / b).toString)
          case "<"  => Some((a < b).toString)
          case ">"  => Some((a > b).toString)
          case "<=" => Some((a <= b).toString)
          case ">=" => Some((a >= b).toString)
          case "==" => Some((a == b).toString)
          case "!=" => Some((a != b).toString)
          case _    => None
      case (Lit.Boolean(a), Lit.Boolean(b)) => op match
        case "&&" => Some((a && b).toString)
        case "||" => Some((a || b).toString)
        case "==" => Some((a == b).toString)
        case "!=" => Some((a != b).toString)
        case _    => None
      case (Lit.String(a), Lit.String(b)) if op == "+" =>
        Some("\"" + escapeJsString(a + b) + "\"")
      case _ => None
