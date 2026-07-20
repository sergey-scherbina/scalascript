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

  /** Native JS binary operators that the ApplyInfix `case other` fallback emits
   *  as a RAW operator (the both-Int arithmetic/comparison fast path lands here).
   *  Used to keep the symbolic-operator → `_dispatch` routing from swallowing
   *  them: only operators OUTSIDE this set are treated as user extensions. */
  private[codegen] val nativeInfixOps: Set[String] =
    Set("+", "-", "*", "/", "%", "<", ">", "<=", ">=",
        "==", "!=", "===", "!==", "<<", ">>", ">>>", "&", "|", "^", "&&", "||")

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
    // arch-meta-v2 macro-codegen-backends — expand restricted quoted macros to
    // plain code before codegen (no-op for macro-free modules).
    gen.genModule(scalascript.artifact.MacroCodegen.expand(module, baseDir))

  /** Generate segments in document order, preserving scala/scalascript interleaving.
   *  Tree-shaking is OFF by default to preserve the existing API behaviour.
   *  Pass `noTreeShake = false` explicitly (or use [[generateWithStats]]) to enable it. */
  def generateSegmented(
      moduleIn:    Module,
      baseDir:     Option[os.Path] = None,
      intrinsics:  Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
      lockPath:    Option[os.Path] = None,
      noTreeShake: Boolean = true
  ): List[Segment] =
    // arch-meta-v2 macro-codegen-backends — expand macros before tree-shaking + codegen.
    val module = scalascript.artifact.MacroCodegen.expand(moduleIn, baseDir)
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
      case cb: Content.CodeBlock => Lang.isParseable(cb.lang)
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
    case object Effects   extends Capability  // `JsRuntimeEffects` — Logger/Random/Clock/Env/Auth
    case object Mcp       extends Capability  // `JsRuntimeMcp` — MCP server / client
    case object Dataset   extends Capability  // `JsRuntimeDataset` — Dataset[T] lazy pipeline
    case object Payment   extends Capability  // `JsRuntimePayment` — Payment Request API
    case object HtmlDsl   extends Capability  // `JsRuntimeHttpServer` — HTTP serve/route/sessions/metrics
    case object Jwt       extends Capability  // `JsRuntimeJwtAuth` — JWT/OAuth2/CSRF
    case object WsServer  extends Capability  // `JsRuntimeWsServer` — WebSocket/SSE/CORS
    case object Optics    extends Capability  // `JsRuntimeOptics` — Lens/Optional/Traversal/Prism
    case object Signals   extends Capability  // `JsRuntimeSignals` — reactive signals
    case object IndexedDb extends Capability  // `JsRuntimeIndexedDb` — client-side storage
    case object Graphql   extends Capability  // `JsRuntimeGraphql` — GraphQL server + client
    case object WebAuthn  extends Capability  // `JsRuntimeWebAuthn` — FIDO2 server verifier (Node)

    val all: Set[Capability] = Set(Core, Async, Effects, Mcp, Dataset, Payment,
                                   HtmlDsl, Jwt, WsServer, Optics, Signals, IndexedDb,
                                   Graphql, WebAuthn)

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
      case WebAuthn  => "webauthn"

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
      case "webauthn"  => Some(WebAuthn)
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
    // v1.61.6: build from individual fragments so unused sections are omitted.
    // JsRuntimeCore (the base) is always included.
    sb.append(JsRuntimeCore)
    if !JsRuntimeCore.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.HtmlDsl) then
      sb.append(JsRuntimeHttpServer)
      if !JsRuntimeHttpServer.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Jwt) then
      sb.append(JsRuntimeJwtAuth)
      if !JsRuntimeJwtAuth.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.WsServer) then
      sb.append(JsRuntimeWsServer)
      if !JsRuntimeWsServer.endsWith("\n") then sb.append('\n')
    // CoreDispatch + CoreCollections are always included (dispatch, _show, _tupleConcat, Free Monad, fs).
    sb.append(JsRuntimeCoreDispatch)
    if !JsRuntimeCoreDispatch.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Optics) then
      sb.append(JsRuntimeOptics)
      if !JsRuntimeOptics.endsWith("\n") then sb.append('\n')
    sb.append(JsRuntimeCoreCollections)
    if !JsRuntimeCoreCollections.endsWith("\n") then sb.append('\n')
    // std.fs / std.os / std.process — always included; Node.js uses node:fs/os/path,
    // browser stubs throw FsNotSupported / ProcessNotSupported (std-fs-os-p3-js).
    sb.append(JsRuntimeFs.source)
    if !JsRuntimeFs.source.endsWith("\n") then sb.append('\n')
    // std.yaml — parseYaml / toYaml / accessor helpers (std-yaml-p3-js).
    sb.append(JsRuntimeYaml.source)
    if !JsRuntimeYaml.source.endsWith("\n") then sb.append('\n')
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
      sb.append(JsRuntimeEffects)
      if !JsRuntimeEffects.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Mcp) then
      sb.append(JsRuntimeMcp)
      if !JsRuntimeMcp.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Dataset) then
      sb.append(JsRuntimeDataset)
      if !JsRuntimeDataset.endsWith("\n") then sb.append('\n')
    if caps.contains(Capability.Payment) then
      sb.append(JsRuntimePayment)
      if !JsRuntimePayment.endsWith("\n") then sb.append('\n')
    // GraphQL runtime references `route` (HttpServer) / `serve` (WsServer) /
    // `_mkResp` / `_jsonConvert` (defined above), so it is appended last.
    // Capability detection forces HtmlDsl + WsServer + Async whenever Graphql
    // is present, so those parts are emitted.
    if caps.contains(Capability.Graphql) then
      sb.append(JsRuntimeGraphql)
      if !JsRuntimeGraphql.endsWith("\n") then sb.append('\n')
    // WebAuthn server verifier (reuses `_nodeCrypto` from Part2b + Core Option/Map).
    if caps.contains(Capability.WebAuthn) then
      sb.append(JsRuntimeWebAuthn)
      if !JsRuntimeWebAuthn.endsWith("\n") then sb.append('\n')
    sb.toString

  // Anchor at column 0 (no leading whitespace): the runtime shares a FLAT
  // classic-script scope, so only its true TOP-LEVEL declarations can collide
  // with user code. `^\s*` also matched INDENTED (nested) declarations — loop
  // counters (`i`, `k`), temporaries (`buf`, `result`) and inner helpers
  // (YAML `parseBlockMap`, …) — polluting the reserved set and forcing spurious
  // `__ssc` renames of innocent user top-level names (e.g. `val x`, `val result`).
  private val runtimeTopLevelDecl =
    """(?m)^(?:async\s+function|function|const|let|var|class)\s+([A-Za-z_$][A-Za-z0-9_$]*)\b""".r

  /** Runtime declarations share the classic-script top-level scope with user
   *  code after the CLI concatenates runtime + user JS. A user `val doc = ...`
   *  must therefore emit under a generated name instead of redeclaring the
   *  runtime `function doc(...)`. */
  private[codegen] lazy val runtimeTopLevelNames: Set[String] =
    runtimeTopLevelDecl.findAllMatchIn(generateRuntime(Capability.all)).map(_.group(1)).toSet

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
    generateWithStats(scalascript.artifact.MacroCodegen.expand(module, baseDir), baseDir, intrinsics, lockPath, noTreeShake)._1

  /** Stdlib runtime singleton methods that can be emitted as direct JS calls
   *  (`Stream.emit(x)`) instead of `_dispatch(Stream, 'emit', [x])`.
   *  Avoids per-call `[args]` array allocation and the _dispatch type-check chain.
   *  Safe when the receiver name is not shadowed by a user binding at the call site. */
  val stdlibDirectCall: Set[(String, String)] = Set(
    ("Stream", "emit"), ("Stream", "complete"), ("Stream", "error"), ("Stream", "request"),
    ("Logger", "log"), ("Logger", "warn"), ("Logger", "error"),
  )

  /** Single-element-param list HOFs whose closure param can be typed to the
   *  receiver's numeric element type (see `genClosureWithParamType`). Taxonomy
   *  owned by the shared `CollectionMethods` classifier (T3.3). */
  val numericListHofs: Set[String] = scalascript.transform.CollectionMethods.elementHofs








val JsRuntime: String =
  JsRuntimeCore + JsRuntimeHttpServer + JsRuntimeJwtAuth + JsRuntimeWsServer +
  JsRuntimeCoreDispatch + JsRuntimeOptics + JsRuntimeCoreCollections + JsRuntimeSignals +
  JsRuntimeFs.source + TypedJsonCodecRuntime.jsFacade + JsRuntimeIndexedDb

/** Built-in `Async` effect runtime (loaded from the `async.mjs` resource).  Same semantics as
 *  the interpreter and JvmGen: `delay` blocks via Atomics on Node, thunks passed to `async` /
 *  `parallel` run synchronously, results come back in declared order.  (Was formerly split into
 *  `JsRuntimeAsyncA` + `JsRuntimeAsyncB` Scala string literals to stay under the JVM's
 *  65 535-byte string-constant cap; that limit no longer applies now the JS lives in a `.mjs`
 *  resource — see `specs/js-runtime-resources.md`.) */
val JsRuntimeAsync: String = JsRuntimeResource.load("async.mjs")





class JsGen(
    private[codegen] val baseDir: Option[os.Path] = None,
    intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] = Map.empty,
    private[codegen] val lockPath: Option[os.Path] = None,
    // Shared across parent + all child generators to track which top-level `const X` names
    // have been declared.  When two package-qualified imports share the same top-level namespace
    // (e.g. std.ui.primitives and std.ui.nodes both wrap content in `object std { ... }`), the
    // second occurrence merges via _ssc_mergeDeep instead of re-declaring the const.
    private[codegen] val topLevelConsts: mutable.Set[String] = mutable.Set.empty,
    private[codegen] val mergeHelperEmitted: Array[Boolean]  = Array(false),
    // Shared across parent + all child generators: per namespace path, the member names
    // already declared by EARLIER sections.  A module split across markdown sections is
    // emitted as separate `_ssc_mergeDeep` IIFEs (e.g. std/money declares `Currency`/`Money`
    // in one section and `defaultCurrencies` in another); a later section re-binds the
    // earlier section's members from the live namespace (`const { Currency } = std.money;`)
    // so bare references resolve instead of throwing `ReferenceError`.
    private[codegen] val namespaceMembers: mutable.Map[String, mutable.Set[String]] = mutable.Map.empty,
    // Shared across parent + all child generators to track which import-binding `const` names
    // have been declared.  A module imported transitively (e.g. nodes.ssc pulled in by both
    // primitives.ssc and layout.ssc) must not emit duplicate `const TkNode = …` lines.
    // Pre-populated with preamble-defined globals so imports of the same name are skipped
    // (e.g. `[Left, Right](std/either.ssc)` must not emit `const Left = …` since
    // `function Left` is already in the preamble and `const` cannot redeclare it).
    // The std/fs file-ops are `extern def`s whose REAL implementation is the preamble
    // `function readFile` (etc., JsRuntimeFs); importing them (`[readFile,…](std/fs.ssc)`)
    // must NOT emit `const readFile = std.fs.readFile` — that both redeclares the preamble
    // function (SyntaxError) and would shadow the real impl with the UI stub.
    private[codegen] val declaredBindings: mutable.Set[String] =
      mutable.Set("Left", "Right", "Some", "None", "Nil",
        "readFile", "writeFile", "appendFile", "readBytes", "writeBytes", "exists",
        "isFile", "isDir", "mkdir", "mkdirs", "listDir", "deleteFile", "copyFile", "moveFile",
        // `Signal` is std/ui/primitives' opaque TYPE; its runtime value is the signals.mjs
        // preamble `function Signal`. Importing it (`[Signal, …](std/ui/primitives.ssc)`)
        // must not emit `const Signal = std.ui.primitives.Signal` — that redeclares the
        // preamble function (SyntaxError). Type positions erase; value uses correctly hit
        // the preamble constructor.
        "Signal"),
    // Shared across parent + all child generators: top-level (global) enum-case binding names
    // already emitted. Two enums in different modules that share a parameterless case name
    // (e.g. ObligationStatus.Pending and DeferredActionStatus.Pending) each emit a global
    // `const Pending = {_type:'Pending', _tag:N}`; tags are global-by-name so the objects are
    // structurally identical — the second declaration is skipped (its enum object references
    // the first) instead of emitting a duplicate `const` that breaks the bundle on Node.
    private[codegen] val declaredEnumCases: mutable.Set[String] = mutable.Set.empty,
    // Shared across parent + all child generators: the WHOLE-PROGRAM effect view,
    // populated once by the entry generator's `analyzeEffects` (which collects trees
    // across the entire import graph). Effect ops as "Eff.op"; functions that
    // transitively perform an effect (emitted in CPS form so callers get a Free
    // value); effect-object names carrying `val __multiShot__ = true`. Shared so a
    // function calling a transitively-imported effectful function is recognised
    // everywhere it is emitted (entry module or any child generator).
    private[codegen] val effectOps: mutable.Set[String] = mutable.Set.empty,
    private[codegen] val effectfulFuns: mutable.Set[String] = mutable.Set.empty,
    private[codegen] val multiShotEffects: mutable.Set[String] = mutable.Set.empty,
    // Shared across parent + all child generators so generated replacements for
    // runtime-colliding user top-level names stay unique in the final flat JS scope.
    private[codegen] val usedTopLevelJsNames: mutable.Set[String] = mutable.Set.empty,
    // Shared across parent + child generators: for each unqualified imported file,
    // remember how its source-level top-level names were emitted after runtime
    // collision renaming. Later import extraction can then bind the importer-local
    // name to the actual child binding (`query__ssc = query__ssc1`, etc.).
    private[codegen] val importedJsNames: mutable.Map[String, Map[String, String]] = mutable.Map.empty,
    // When Some(set), only top-level declarations whose name is in the set are emitted.
    // None means no filtering (tree-shaking disabled — emit everything).
    // Populated by TreeShaker.shake() and threaded from the companion object entry points.
    private[codegen] val reachableNames: Option[Set[String]] = None) extends JsGenAnalysisQueries, JsGenCpsCodegen, JsGenContentEmit:
  import scala.meta.*

  private[codegen] val sb = StringBuilder()
  private var indent = 0
  private var tmpIdx = 0
  private var hasMain = false
  private var mainCalled = false
  private val topLevelUserRenames = mutable.Map.empty[String, String]
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
  private[codegen] var usesRunActors: Boolean = false
  // Set when client/browser code uses `awaitClient(promise)`.  This helper
  // lowers directly to JS `await` and therefore needs the top-level async IIFE.
  private var usesAwaitClient: Boolean = false
  // Set when the module uses stream terminal operations (runForeach, runFold,
  // runToList, runDrain) — these are async and need the top-level async IIFE.
  private var usesStreams: Boolean = false
  // Stack of placeholder counters: each AnonymousFunction pushes 0, Placeholder increments top
  private[codegen] var phCounters: List[Int] = Nil
  // Names of variables known to hold integer values (for integer division detection)
  private val intVars = scala.collection.mutable.Set[String]()
  // fname → set of all group members (populated by analyzeMutualRecursion before emit)
  private var mutualGroups: Map[String, Set[String]] = Map.empty
  // effectOps / effectfulFuns / multiShotEffects are SHARED constructor params (see above)
  // so the whole-program effect view is consistent across the entry + child generators.
  // Maps summon key "TC_A" → local param name "A$TC" for context-bound params
  // of the current Defn.Def being emitted. Cleared before each function.
  private val cbSummonMap = scala.collection.mutable.Map.empty[String, String]
  // arch-meta-v2-p5 Track A (A2) — summon keys backed by a SYNTHESIZED given
  // (per-type `Mirror_T` + custom `derives` `TC_T`), registered up front in
  // `_ssc_givens`.  summon routes these through `_resolveGiven(key)` (registry,
  // lazy) instead of a bare name; explicit user-given summon is left untouched.
  private val jsSyntheticGivenKeys = scala.collection.mutable.Set.empty[String]
  // funcParamOrder: function name → ordered parameter names.
  // Populated by collectFuncParamOrders before the main emit pass.
  // Used at call sites with named args to reorder them to positional order.
  // NOTE: this map also gates the direct-call emission (`f(args)` vs
  // `_call(f, args)`), so it must hold ONLY top-level same-module `def`s whose
  // names are bound as callables at the call site — never imported names.
  private val funcParamOrder = scala.collection.mutable.Map.empty[String, List[String]]
  // importedParamOrder: param orders for functions / case-class ctors reachable
  // through imports (and inside `package:` namespace objects).  Used ONLY to
  // reorder named args for such callees — it deliberately does NOT feed the
  // direct-call gate, so imported calls keep going through `_call`.
  private val importedParamOrder = scala.collection.mutable.Map.empty[String, List[String]]
  // Functions with 2+ explicit (non-using) parameter clause groups.
  // Call sites for these need flattened args: _call(f, a, b) not _call(_call(f, a), b).
  private val multiParamGroupFns = scala.collection.mutable.Set.empty[String]
  // Zero-explicit-param defs (def f: T = body). In JS these compile to function f() { return body; }.
  // When called as f(x), we must generate _call(f(), x) so the returned function gets applied.
  private val zeroParamFns = scala.collection.mutable.Set.empty[String]
  // One-empty-param-clause defs (def f(): T). Direct call f() is safe — emit without _call wrapper.
  private val emptyParamFns = scala.collection.mutable.Set.empty[String]
  // User-defined functions with :Int or :Long return type — their call sites can use isIntExpr.
  private val intFunctions = scala.collection.mutable.Set.empty[String]
  // v1-js-long-precision-and-bitops: names/functions statically known to hold a
  // `Long`. Longs are JS BigInt, Ints are JS Number, and mixing the two in a
  // native JS operator throws — so any arithmetic/comparison touching a Long
  // operand is routed through the BigInt-aware `_arith` (which coerces the Int
  // side). Longs stay in `intVars` too (isIntExpr = true), so integer-division
  // detection and `.toInt` handling keep working; `longVars`/isLongExpr only
  // OVERRIDE the native-operator emission.
  private val longVars = scala.collection.mutable.Set[String]()
  private val longFunctions = scala.collection.mutable.Set.empty[String]
  // User-defined functions with :Double or :Float return type — their call sites can use isNumericExpr.
  private val numericFunctions = scala.collection.mutable.Set.empty[String]
  // v1.27 Phase 3 — sql block emission state.  Mirrors JvmGen's
  // `sqlBlockCounter` / `sqlPerSection`: sequential `_sqlBlock_<n>`
  // names, and per-section "first-only" tracking so only the first
  // `sql` block in each section gets the friendly `<sectionId>.sql`
  // alias.  Cleared at the start of every `genModule` call.
  private var sqlBlockCounter: Int = 0
  private val sqlPerSection = scala.collection.mutable.Map.empty[String, Int]
  private[codegen] val contentIntrinsicNames: Set[String] = Set(
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
  // The three std/ui/content toolkit externs.  Like contentIntrinsicNames they
  // are emitted as top-level runtime functions (emitContentToolkitRuntime), so
  // an import of them must bind to the bare name, not the `undefined` namespace
  // member the generic extern path would produce.
  private[codegen] val contentToolkitIntrinsicNames: Set[String] = Set(
    "contentToolkitNode",
    "contentToolkitBlock",
    "contentToolkitSection"
  )
  private[codegen] var contentRuntimeEnabled: Boolean = false
  // True when the module imports std/ui/content (the toolkit layer) — gates the
  // emission of the contentToolkitNode/Block/Section runtime so plain std/content
  // bundles stay lean.
  private[codegen] var contentToolkitRuntimeEnabled: Boolean = false
  private var contentSectionIndex: Int = 0

  // Typeclass parent map: TC -> List[parentTC], accumulated from all trait declarations.
  // Used when registering `given` instances to also register under parent TC keys.
  private val tcParentMap = scala.collection.mutable.Map.empty[String, List[String]]
  // Case class type name → ordered field names; populated per module by genModule.
  // Used in genPattern to emit scrutVar.fieldName instead of Object.values(...)[i].
  private[codegen] var caseClassFieldsByType: Map[String, List[String]] = Map.empty
  // Case class type name → (field name → declared type); populated per module by genModule.
  // Used in genPattern to add Double/Float-typed bound vars to numericVars.
  private[codegen] var caseClassFieldTypeMap: Map[String, Map[String, String]] = Map.empty
  // Case class / enum case type name → integer tag; populated per module by genModule.
  // Enables O(1) switch dispatch instead of O(n) string comparison in pattern matches.
  private[codegen] var caseClassTagMap: Map[String, Int] = Map.empty
  // Subtype graph for supertype type-tests. `case h: TkNode` must match a concrete
  // subtype instance, but emitted objects carry only their own leaf `_type`, so an
  // exact `_type === 'TkNode'` check never matches a `SignalHeadingNode`. We record
  // `child → direct parents` + the concrete leaf names, accumulating ACROSS imported
  // modules (a trait and its subtypes routinely live in a different module than the
  // `match`, and each imported module is emitted by a child `JsGen`). `subtypeClosure`
  // is the derived `supertype → transitive concrete-descendant _type names` map, read
  // by genPattern's Pat.Typed branch and refreshed by recomputeSubtypeClosure().
  private[codegen] val subtypeParents  = scala.collection.mutable.LinkedHashMap.empty[String, scala.collection.mutable.LinkedHashSet[String]]
  private[codegen] val subtypeConcrete = scala.collection.mutable.LinkedHashSet.empty[String]
  private[codegen] var subtypeClosure: Map[String, Set[String]] = Map.empty
  // Variables statically known to hold Double/Float values — arithmetic on them uses direct JS operators.
  private[codegen] val numericVars = scala.collection.mutable.Set[String]()
  // Variables statically known to hold a case-class instance (varName → typeName).
  // Lets `v.field` lower to a direct property read `v.field` (and `isIntExpr` see
  // an Int field) instead of the megamorphic `_dispatch(v, 'field', [])` call.
  private val instanceVars = scala.collection.mutable.Map[String, String]()
  // Variables statically known to hold a TUPLE (a JS array). Lets `t._N` lower to a
  // direct `t[N-1]` array read instead of the megamorphic `_dispatch(t, '_N', [])`,
  // which mattered a lot in a hot loop (tuple-monoid bench). Only set when the RHS is
  // provably a tuple (literal, tuple `++` concat, or another tuple var) — never a
  // case class, whose `._N` Product accessor stays on the runtime dispatch.
  private val tupleVars = scala.collection.mutable.Set[String]()
  // Variables holding a numeric-element collection (varName → "Int"/"Long"/"Double"/"Float").
  // Lets a HOF closure param over them be typed numeric, so `xs.map(x => x * 2)`
  // emits native `(x * 2)` instead of `_arith('*', x, 2)` + a string-repeat guard.
  private val listElemType = scala.collection.mutable.Map[String, String]()

  /** Numeric element type of a `List[T]`/`Seq[T]`/`Vector[T]`/`Array[T]`-style
   *  declared type, when `T` is a primitive numeric. */
  private def numericListElem(tpe: Type): Option[String] = tpe match
    case Type.Apply.After_4_6_0(Type.Name("List" | "Seq" | "Vector" | "IndexedSeq" | "Iterable" | "Array"), argClause) =>
      argClause.values.headOption.collect {
        case Type.Name(n) if n == "Int" || n == "Long" || n == "Double" || n == "Float" => n
      }
    case _ => None

  /** Run `thunk` with name `p` typed as numeric `elem` in the tracking sets,
   *  restoring afterwards (removing only what this call added, to respect an
   *  outer binding of the same name). Used to type HOF closure params. */
  private def withParamTyped[A](p: String, elem: String)(thunk: => A): A =
    val isInt    = elem == "Int" || elem == "Long"
    val addedInt = isInt && !intVars.contains(p)
    val addedNum = !isInt && !numericVars.contains(p)
    if isInt then intVars += p else numericVars += p
    try thunk
    finally { if addedInt then intVars -= p; if addedNum then numericVars -= p }

  /** SCOPE a def's declared-param numeric evidence for the duration of `f` (its body
   *  emission), then restore exactly. Crucially this both SETS the param's own type and
   *  REMOVES the wrong-set memberships: the intVars/longVars/numericVars sets are keyed
   *  by NAME and populated globally by recordDefTypeEvidence, so a sibling function's
   *  `value: Long` leaks `value` into longVars and makes an Int-param `value / 256` in
   *  ANOTHER function take the Long `_arith('/')` path (→ `1/256` = 0.0039 float instead
   *  of `Math.trunc`, corrupting serialized bytes — the 6 scljet-write-* js DIVERGEs).
   *  (js-imported-def-int-division-loses-truncation.) */
  private def withParamTypeEvidence[A](paramVals: Seq[Term.Param])(f: => A): A =
    val saved = paramVals.map { pv =>
      val n = pv.name.value
      (n, intVars.contains(n), longVars.contains(n), numericVars.contains(n))
    }
    paramVals.foreach { pv =>
      val n = pv.name.value
      pv.decltpe match
        case Some(Type.Name("Long"))             => longVars += n; intVars += n; numericVars -= n
        case Some(Type.Name("Int"))              => intVars += n; longVars -= n; numericVars -= n
        case Some(Type.Name("Double" | "Float")) => numericVars += n; intVars -= n; longVars -= n
        // Any other declared type shadows a leaked numeric evidence of the same name.
        case Some(_)                             => intVars -= n; longVars -= n; numericVars -= n
        case None                                => ()
    }
    try f
    finally saved.foreach { (n, wasInt, wasLong, wasNum) =>
      if wasInt then intVars += n else intVars -= n
      if wasLong then longVars += n else longVars -= n
      if wasNum then numericVars += n else numericVars -= n
    }

  /** Statically-known numeric type of a scalar expression, or None. */
  private def numericTypeOfExpr(t: Term): Option[String] =
    if isIntExpr(t) then Some("Int")
    else if isNumericExpr(t) then Some("Double")
    else None

  /** Numeric element type of a list/range-valued *expression*, propagated through
   *  `.map` (→ the closure's result type) and element-preserving ops, plus integer
   *  ranges (`a until b` / `a to b`). Enables typing closure params down a chain
   *  like `xs.map(x => x*2).filter(x => x%3==0)`. */
  private def numericElemOf(t: Term): Option[String] = t match
    case Term.Name(n) => listElemType.get(n)
    case Term.ApplyInfix.After_4_6_0(lo, Term.Name("until" | "to"), _, ac)
        if ac.values.lengthCompare(1) == 0 && isIntExpr(lo) && ac.values.headOption.exists(isIntExpr) =>
      Some("Int")
    case Term.Apply.After_4_6_0(Term.Select(q, Term.Name("map")), ac) if ac.values.lengthCompare(1) == 0 =>
      (numericElemOf(q), ac.values.head) match
        case (Some(src), Term.Function.After_4_6_0(pc, body)) if pc.values.lengthCompare(1) == 0 =>
          withParamTyped(pc.values.head.name.value, src)(numericTypeOfExpr(body))
        case _ => None
    case Term.Apply.After_4_6_0(Term.Select(q, Term.Name(m)), _)
        if scalascript.transform.CollectionMethods.isTypePreservingListOp(m) =>
      numericElemOf(q)
    case _ => None

  /** Generate a single-param closure with its parameter typed to `elem` so
   *  arithmetic on it lowers to native JS ops. Falls back to genExpr otherwise. */
  private def genClosureWithParamType(fn: Term, elem: String): String = fn match
    case Term.Function.After_4_6_0(pc, body) if pc.values.lengthCompare(1) == 0 =>
      val p = pc.values.head.name.value
      val bodyJs = withLocalBindings(List(p)) { withParamTyped(p, elem) {
        body match
          case Term.Block(stats) => genBlockAsIife(stats)
          case expr              => genExpr(expr)
      } }
      s"${localBindingName(p)} => $bodyJs"
    case other => genExpr(other)

  /** Generate a two-param closure `(a, b) => …` with both params typed numeric
   *  (`accElem` for the accumulator, `elem` for the element) — for `foldLeft`. */
  private def genFold2ClosureTyped(fn: Term, accElem: String, elem: String): String = fn match
    case Term.Function.After_4_6_0(pc, body) if pc.values.lengthCompare(2) == 0 =>
      val a = pc.values.head.name.value
      val b = pc.values(1).name.value
      val bodyJs = withLocalBindings(List(a, b)) { withParamTyped(a, accElem) { withParamTyped(b, elem) {
        body match
          case Term.Block(stats) => genBlockAsIife(stats)
          case expr              => genExpr(expr)
      } } }
      s"(${localBindingName(a)}, ${localBindingName(b)}) => $bodyJs"
    case other => genExpr(other)
  // js-codegen-opt-p2: loop-invariant constant tuple hoisting.
  // While-loop codegen sets this buffer; constant-tuple exprs append (name, frozenExpr)
  // and return the name instead of re-allocating on every iteration.
  private var loopHoistBuf: mutable.Buffer[(String, String)] | Null = null
  private var hoistIdx: Int = 0

  // Names of `var`s declared in the function body enclosing the current while
  // loop.  Set by genFunctionBody around while-body generation so that
  // inlineForeachOrGenStat can recognise the invariant-accumulation pattern
  // `stable.foreach(p => { acc = acc + f(p) })` and hoist the sum out of the loop.
  private var loopOuterVars: Set[String] = Set.empty

  private def termRefsAny(t: Term, names: Set[String]): Boolean =
    var found = false
    def walk(n: scala.meta.Tree): Unit =
      if !found then n match
        case Term.Name(v) => if names(v) then found = true
        case _            => n.children.foreach(walk)
    walk(t)
    found

  private[codegen] def freshTmp(): String =
    tmpIdx += 1
    s"_t$tmpIdx"

  private def isLiteralTerm(t: Term): Boolean = t match
    case _: Lit.Int | _: Lit.Double | _: Lit.Float | _: Lit.Long |
         _: Lit.String | _: Lit.Boolean | Lit.Unit() => true
    case _ => false

  private def isConstantTupleExpr(t: Term): Boolean = t match
    case Term.Tuple(elems) => elems.forall(isLiteralTerm)
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) if op.value == "++" || op.value == ":::" =>
      isConstantTupleExpr(lhs) && argClause.values.asInstanceOf[List[Term]].forall(e => isLiteralTerm(e) || isConstantTupleExpr(e))
    case _ => false

  private def collectConstantTupleElems(t: Term): List[String] = t match
    case Term.Tuple(elems) => elems.map(genExpr)
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) if op.value == "++" || op.value == ":::" =>
      collectConstantTupleElems(lhs) ++ argClause.values.asInstanceOf[List[Term]].flatMap {
        case te: Term.Tuple => collectConstantTupleElems(te)
        case other          => List(genExpr(other))
      }
    case _ => List(genExpr(t))

  private def freshHoistConst(value: String): String =
    val name = s"_k$hoistIdx"
    hoistIdx += 1
    loopHoistBuf.nn += ((name, value))
    name

  private[codegen] def line(s: String): Unit =
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
    importedParamOrder.clear()
    multiParamGroupFns.clear()
    zeroParamFns.clear()
    emptyParamFns.clear()
    intFunctions.clear()
    numericFunctions.clear()
    def collectDefs(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Def =>
        val paramVals = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
        val params = paramVals.map(_.name.value).toList
        if params.nonEmpty then funcParamOrder(d.name.value) = params
        val explicitGroups = d.paramClauseGroups.flatMap(_.paramClauses).filterNot(_.mod.nonEmpty)
        if explicitGroups.size > 1 then multiParamGroupFns += d.name.value
        if explicitGroups.isEmpty then zeroParamFns += d.name.value
        // def f(): T — one explicit empty param clause, no actual parameters
        if explicitGroups.size == 1 && params.isEmpty then emptyParamFns += d.name.value
        // Record Int/Long/Double/Float return- and param-type evidence for the
        // isIntExpr/isNumericExpr heuristics (shared with imported modules — see
        // registerImportedTypeEvidence).
        recordDefTypeEvidence(d)
      // Top-level `val xs: List[Int] = …` — track numeric-element collections for
      // HOF closure-param typing (e.g. bench `val xs: List[Int]`).
      case dv: Defn.Val =>
        dv.decltpe.flatMap(numericListElem).foreach { elem =>
          dv.pats.foreach { case Pat.Var(n) => listElemType(n.value) = elem; case _ => () }
        }
      case _ => ()
    }
    def scanSection(section: Section): Unit =
      section.content.foreach {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
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
    // The pass above only sees top-level statements, so a module with a
    // `package:` manifest — whose code compiles to a single wrapping
    // `Defn.Object` — would contribute nothing to funcParamOrder, and named-arg
    // calls to its functions / case-class constructors would not be reordered.
    // This second pass descends into namespace objects and also records
    // case-class primary constructors.  See collectParamOrdersFromModule.
    collectParamOrdersFromModule(module)

  /** Record Int/Long/Double/Float return- and param-type evidence for one def
   *  into the isIntExpr/isNumericExpr heuristic sets. Shared by
   *  collectFuncParamOrders (entry module) and registerImportedTypeEvidence
   *  (imported modules, whose bodies the childGen emits directly). */
  private def recordDefTypeEvidence(d: Defn.Def): Unit =
    d.decltpe match
      case Some(Type.Name("Int" | "Long"))     => intFunctions     += d.name.value
      case Some(Type.Name("Double" | "Float")) => numericFunctions += d.name.value
      case _ => ()
    // v1-js-long-precision-and-bitops: a Long-returning function's call sites are Long.
    // NB: use a PATTERN match, not `decltpe.contains(Type.Name("Long"))` — scalameta
    // tree equality includes source position, so a freshly-built `Type.Name("Long")`
    // never equals the PARSED one and `.contains` always returned false. Long params
    // and returns therefore never reached longVars/longFunctions, so `a + b` /
    // `f() + 1` (Long ± Int) emitted a raw JS `+` and threw BigInt+Number at runtime.
    // (js-long-param-evidence.)
    d.decltpe match
      case Some(Type.Name("Long")) => longFunctions += d.name.value
      case _                       => ()
    d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).foreach { pv =>
      pv.decltpe match
        // Long is tracked in BOTH sets: longVars (so isLongExpr → `_arith`) and
        // intVars (the `.toInt`/int-division heuristics; the isLongExpr guard runs
        // first so this is harmless).
        case Some(Type.Name("Long"))                 => longVars += pv.name.value; intVars += pv.name.value
        case Some(Type.Name("Int"))                  => intVars += pv.name.value
        case Some(Type.Name("Double" | "Float"))     => numericVars += pv.name.value
        // Any other simple named type: remember varName → typeName. The
        // direct-field decision is gated later on caseClassFieldsByType so a
        // non-case-class type harmlessly falls back to _dispatch.
        case Some(t) if numericListElem(t).isDefined => listElemType(pv.name.value) = numericListElem(t).get
        case Some(Type.Name(tn))                     => instanceVars(pv.name.value) = tn
        case _ => ()
    }

  /** Populate the isIntExpr/isNumericExpr type-evidence sets and case-class
   *  field-type maps for a module whose bodies THIS generator emits directly.
   *  genModule runs the equivalent pre-pass for the entry module, but the
   *  childGen in genImport emits imported bodies through genScalaNode, bypassing
   *  it — without this, an imported `Int / Int` lowers to floating `_arith('/')`
   *  instead of `Math.trunc(a / b)`, and imported case-class Int fields lose
   *  their integer evidence (v1-js-imported-int-division-loses-type). Descends
   *  into namespace/package `Defn.Object`s like collectParamOrdersFromModule. */
  private def registerImportedTypeEvidence(module: Module): Unit =
    caseClassFieldsByType = caseClassFieldsByType ++ caseClassFieldsInModule(module)
    caseClassFieldTypeMap = caseClassFieldTypeMap ++ caseClassFieldTypesInModule(module)
    def scanDefs(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Def    => recordDefTypeEvidence(d)
      case o: Defn.Object => scanDefs(o.templ.body.stats)
      case _              => ()
    }
    def scan(section: Section): Unit =
      section.content.foreach {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.foreach { node =>
            ScalaNode.fold(node) {
              case Source(stats)     => scanDefs(stats)
              case Term.Block(stats) => scanDefs(stats)
              case _                 => ()
            }
          }
        case _ => ()
      }
      section.subsections.foreach(scan)
    module.sections.foreach(scan)

  // Collect named-arg param orders (function defs + case-class primary ctors)
  // from a module into funcParamOrder, descending into namespace/package
  // `Defn.Object`s.  Used both for the entry module (above) and, in genImport,
  // for each imported module so a named-arg call to an imported function or
  // case-class constructor reorders to the declared positional order instead of
  // landing values in the wrong fields.  Only writes funcParamOrder — the other
  // heuristic sets (intVars, zeroParamFns, …) keep their top-level-only scope.
  private def collectParamOrdersFromModule(module: Module): Unit =
    def deep(stats: List[Stat]): Unit = stats.foreach {
      case o: Defn.Object => deep(o.templ.body.stats)
      case d: Defn.Def =>
        val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value).toList
        if params.nonEmpty then importedParamOrder(d.name.value) = params
      case c: Defn.Class if c.mods.exists(_.isInstanceOf[Mod.Case]) =>
        val ctorParams = c.ctor.paramClauses.flatMap(_.values).map(_.name.value).toList
        if ctorParams.nonEmpty then importedParamOrder(c.name.value) = ctorParams
      case _ => ()
    }
    def scan(section: Section): Unit =
      section.content.foreach {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.foreach { node =>
            ScalaNode.fold(node) {
              case Source(stats)     => deep(stats)
              case Term.Block(stats) => deep(stats)
              case _                 => ()
            }
          }
        case _ => ()
      }
      section.subsections.foreach(scan)
    module.sections.foreach(scan)

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
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
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
  private[codegen] var moduleDeps: Map[String, String] = Map.empty

  /** Absolute paths of `.ssc` files that have already been inlined by
   *  `genImport`.  Mirrors the cycle-protection invariant in
   *  `JvmGen.importedFiles` — a child module re-importing something
   *  the parent already pulled in (or a diamond import) emits nothing
   *  the second time around. */
  private val importedFiles: scala.collection.mutable.Set[String] =
    scala.collection.mutable.Set.empty
  private[codegen] val importedContentDocuments: scala.collection.mutable.Map[String, List[DocumentContent]] =
    scala.collection.mutable.Map.empty

  private case class TopLevelName(name: String, renameableBinding: Boolean)

  private def collectTopLevelNames(module: Module): List[TopLevelName] =
    val names = mutable.ListBuffer.empty[TopLevelName]

    def fromStats(stats: List[Stat]): Unit =
      stats.foreach {
        case d: Defn.Val =>
          d.pats.foreach {
            case Pat.Var(n) => names += TopLevelName(n.value, renameableBinding = true)
            case _          => ()
          }
        case Defn.Var.After_4_7_2(_, pats, _, _) =>
          pats.foreach {
            case Pat.Var(n) => names += TopLevelName(n.value, renameableBinding = true)
            case _          => ()
          }
        case d: Defn.Def    => names += TopLevelName(d.name.value, renameableBinding = true)
        case d: Defn.Object => names += TopLevelName(d.name.value, renameableBinding = true)
        case d: Defn.Class  => names += TopLevelName(d.name.value, renameableBinding = true)
        case d: Defn.Enum =>
          names += TopLevelName(d.name.value, renameableBinding = true)
          d.templ.body.stats.foreach {
            case ec: Defn.EnumCase =>
              names += TopLevelName(ec.name.value, renameableBinding = true)
            case rec: Defn.RepeatedEnumCase =>
              rec.cases.foreach(nm => names += TopLevelName(nm.value, renameableBinding = true))
            case _ => ()
          }
        case d: Defn.Given if d.name.value.nonEmpty =>
          names += TopLevelName(d.name.value, renameableBinding = true)
        case d: Defn.Trait => names += TopLevelName(d.name.value, renameableBinding = false)
        case d: Defn.Type  => names += TopLevelName(d.name.value, renameableBinding = false)
        case _              => ()
      }

    def fromNode(node: ScalaNode): Unit =
      node.tree match
        case Source(stats)     => fromStats(stats)
        case Term.Block(stats) => fromStats(stats)
        case s: Stat           => fromStats(List(s))
        case _                 => ()

    def walkSection(section: Section): Unit =
      section.content.foreach {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.foreach(fromNode)
        case imp: Content.Import =>
          imp.bindings.foreach { b =>
            val localName = b.alias.getOrElse(b.name)
            if !declaredBindings.contains(localName) then
              names += TopLevelName(localName, renameableBinding = true)
          }
        case _ => ()
      }
      section.subsections.foreach(walkSection)

    module.sections.foreach(walkSection)
    names.toList

  private def registerTopLevelUserRenames(module: Module): Unit =
    val names = collectTopLevelNames(module)
    val originalNames = names.map(_.name).toSet
    names.foreach { entry =>
      if !JsGen.runtimeTopLevelNames.contains(entry.name) then
        usedTopLevelJsNames += entry.name
    }
    names.foreach { entry =>
      if entry.renameableBinding && JsGen.runtimeTopLevelNames.contains(entry.name) &&
          !topLevelUserRenames.contains(entry.name) then
        topLevelUserRenames(entry.name) = freshTopLevelUserName(entry.name, originalNames)
    }

  private def freshTopLevelUserName(name: String, originalNames: Set[String]): String =
    var suffix = 0
    var candidate = s"${name}__ssc"
    while JsGen.runtimeTopLevelNames.contains(candidate) ||
        usedTopLevelJsNames.contains(candidate) ||
        originalNames.contains(candidate) do
      suffix += 1
      candidate = s"${name}__ssc$suffix"
    usedTopLevelJsNames += candidate
    candidate

  private def emittedName(name: String): String =
    topLevelUserRenames.getOrElse(name, name)

  def genModule(module: Module): String =
    sb.clear()
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    topLevelUserRenames.clear()
    registerTopLevelUserRenames(module)
    caseClassFieldsByType = caseClassFieldsInModule(module)
    caseClassFieldTypeMap = caseClassFieldTypesInModule(module)
    caseClassTagMap       = caseClassTagsInModule(module)
    subtypeParents.clear(); subtypeConcrete.clear()
    jsSyntheticGivenKeys.clear()
    collectSubtypeEdgesFromModule(module); recomputeSubtypeClosure()
    collectFuncParamOrders(module)
    analyzeMutualRecursion(module)
    analyzeEffects(module)
    scanForRunAsyncParallel(module)
    scanForRunActors(module)
    scanForAwaitClient(module)
    scanForStreams(module)
    val (_cUse, _tkUse) = scanContentUsage(module)
    contentRuntimeEnabled = _cUse
    contentToolkitRuntimeEnabled = _tkUse
    if contentRuntimeEnabled then collectImportedContent(module) else importedContentDocuments.clear()
    contentSectionIndex = 0
    // v1.27 Phase 3 — sql state.  Reset every module to keep `genModule`
    // re-entrant; emit preamble once when at least one sql block is
    // present (URL-prefix providers + ConnectionRegistry init + resolver).
    sqlBlockCounter = 0
    sqlPerSection.clear()
    val needSqlPreamble = hasSqlBlocks(module)
    if needSqlPreamble then emitSqlPreamble(module)
    if contentRuntimeEnabled then emitContentRuntime(module.document)
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
      line("  _println = function(...args) { process.stdout.write(args.map(_show).join(' ') + '\\n'); };")
      line("  _print   = function(...args) { process.stdout.write(args.map(_show).join(''));         };")
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
    // arch-meta-v2-p5 Track A (A2) — register Mirror metadata + custom-derives
    // givens before the user blocks (same scope as the emitted classes/objects,
    // inside the async IIFE when present).  Case classes are hoisted functions;
    // the custom-derives instance is a lazy getter so it can reference the
    // not-yet-initialised `const TC` object until its summon site runs.
    emitMirrorAndDerives(module)
    genSections(module.sections, module.document.map(_.sections).getOrElse(Nil))
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
  // blocks (`JsRuntimeAsync`, `JsRuntimeEffects`, `JsRuntimeMcp`,
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
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) => cb.source
      } ++ s.subsections.flatMap(collectSources)
    def collectImports(s: Section): List[Content.Import] =
      s.content.collect { case imp: Content.Import => imp } ++ s.subsections.flatMap(collectImports)
    val sources = module.sections.flatMap(collectSources)
    val allText = sources.mkString("\n")
    val hasStdUiImport = module.sections.flatMap(collectImports).exists { imp =>
      val path = imp.path.replace('\\', '/')
      path.startsWith("std/ui/") || path.contains("/std/ui/")
    }
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
                   allText.contains("joinCluster") ||
                   // v1.9 coroutines and v1.10 generators — both live in the Async runtime (async.mjs)
                   allText.contains("coroutineCreate") || allText.contains("coroutineResume") ||
                   allText.contains("generator {") || allText.contains("generator[") ||
                   allText.contains("_makeGenerator") || allText.contains("fromGenerator")
    // v1.51.2 streams — Source.* / stream {} / runToList / etc. require the async runtime
    // (_makeAsyncStream uses async function*; terminal ops need await).
    val hasStreams = allText.contains("Source.") || allText.contains("stream {") ||
                    allText.contains("runToList") || allText.contains("runForeach") ||
                    allText.contains("runDrain")  || allText.contains("runFold") ||
                    allText.contains("runStream") || allText.contains("Sink.") ||
                    allText.contains("Flow.")
    if hasAsync || hasStreams then caps += Async
    // v1.4 effects: Logger / Random / Clock / Env / Auth — `JsRuntimeEffects`.
    // v1.51.6: Stream algebraic effect also lives in JsRuntimeEffects (at the end).
    val hasV14 = allText.contains("Logger.") || allText.contains("Random.") ||
                 allText.contains("Clock.")  || allText.contains("Env.")    ||
                 allText.contains("Auth.")   || allText.contains("runLogger") ||
                 allText.contains("runRandom") || allText.contains("runClock") ||
                 allText.contains("runEnv")  || allText.contains("runAuth")  ||
                 allText.contains("Stream.") || allText.contains("runStream") ||
                 // v1 effect runners/ops that also live in effects.mjs but were
                 // missing from this trigger list — a program using only these
                 // (e.g. a bare `runState(0){…}` with no Logger/Random) emitted
                 // JS that referenced an undefined `runState`. (`run*` names
                 // substring-cover their seeded/stub variants: runHttpStub,
                 // runCacheBypass, runRetryNoSleep.) (js-effect-runner-preamble.)
                 allText.contains("State.")   || allText.contains("runState") ||
                 allText.contains("Http.")    || allText.contains("runHttp")  ||
                 allText.contains("runCache") || allText.contains("runRetry") ||
                 allText.contains("runTx")
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
    // HtmlDsl — HttpServer: HTTP serve/route/sessions/metrics/password/TOTP
    val hasHtmlDsl = allText.contains("serve(") || allText.contains("route(") ||
                     allText.contains("serveAsync(") ||
                     allText.contains("WsRoom(") || allText.contains(".session") ||
                     allText.contains("metrics.") || module.manifest.exists(_.routes.nonEmpty)
    if hasHtmlDsl then { caps += HtmlDsl; caps += Jwt }  // HttpServer uses _bearerFromAuth from JwtAuth
    // Jwt — JwtAuth: JwtSign/JwtVerify/OAuth2/CSRF/BearerToken
    val hasJwt = allText.contains("JwtSign(") || allText.contains("JwtVerify(") ||
                 allText.contains("OAuth2.") || allText.contains("bearerToken") ||
                 allText.contains("csrf")
    if hasJwt then caps += Jwt
    // WsServer — WsServer: WebSocket connections, SSE, CORS, outbound HTTP client
    val hasWsServer = allText.contains("WsConnection(") || allText.contains("WsRoom(") ||
                      allText.contains("serveAsync(") ||
                      allText.contains("sse(") || allText.contains("cors(") ||
                      allText.contains("httpGet(") || allText.contains("httpPost(") ||
                      allText.contains("httpPut(") || allText.contains("httpPatch(") ||
                      allText.contains("httpDelete(") || allText.contains("httpClient(")
    if hasWsServer then caps += WsServer
    // Optics — Lens/Optional/Traversal/Prism
    val hasOptics = allText.contains("Lens(") || allText.contains("Optional(") ||
                    allText.contains("Prism(") || allText.contains("Prism[") ||
                    allText.contains("Traversal(") || allText.contains(".focus") ||
                    allText.contains("Focus[") || allText.contains("_makeLens") ||
                    allText.contains("indexAt")
    if hasOptics then caps += Optics
    // Signals — reactive signals and the lightweight browser UI helpers.
    // UI/DataTable constructors are emitted as `_ssc_ui_*` extern shims from
    // std/ui modules, so they need the Signals runtime even without an explicit
    // user-level `signal(...)` call.
    val hasUiHelpers = List(
      "fetchUrlSignal", "fetchStreamSignal", "intervalTick", "fetchAction", "fetchActionClear", "fetchActionWith", "CaptureAction", "emptyHeaders",
      "onBumpTick", "onSetSignal", "onNavigate", "formBody",
      "dataTable", "dataTableView", "staticDataTable", "signalDataTable",
      "seedSignal",
      "fieldColumn", "dateColumn", "moneyColumn", "statusColumn", "linkColumn",
      "fcol", "dcol", "mcol", "scol", "lcol",
      "rowDelete", "rowPost", "rowLink", "rowEdit",
      "staticRowsSource", "signalRowsSource", "fetchRowsSource",
      "fieldPayload", "wholeRowPayload", "fieldsPayload",
      "contentToolkit",
      "localStorageGet", "localStorageSet", "localStorageRemove",
      "onlineSignal", "persistedSignal",
      "webauthnRegister", "webauthnAssert",
      // std/ui/form.ssc + component.ssc APIs — capability detection reads the
      // ENTRY module's code blocks only, so each std/ui module's user-facing
      // names must appear here or import-only usage emits without signals.mjs.
      "validateField", "fieldError", "formErrors", "formValid", "formField",
      "submitGate", "component(", "componentScope", "ctxSignal", "ctxSeedSignal",
      "textNode", "signalText", "showSignal", "fragment(", "forKeyed", "forJson", "itemField",
      "rawText", "rawHtml", "vstack", "hstack", "heading(", "lower(",
      "selectFrom"
    ).exists(allText.contains)
    val hasSignals = allText.contains("signal(") || allText.contains("Signal(") ||
                    allText.contains("computed(") || allText.contains("Computed(") ||
                    hasUiHelpers || hasStdUiImport
    if hasSignals then caps += Signals
    // IndexedDb — client-side IndexedDB storage
    val hasIndexedDb = allText.contains("IndexedDb.")
    if hasIndexedDb then caps += IndexedDb
    // WebAuthn — FIDO2 server verifier (challenge / verifyRegistration / verifyAssertion)
    val hasWebAuthn = allText.contains("webauthn")
    if hasWebAuthn then caps += WebAuthn
    // GraphQL — `JsRuntimeGraphql`.  Triggered by a `graphql` fenced block or
    // by any of the server/client intrinsics.  The runtime mounts on the full
    // HTTP server stack, which spans `route` (HttpServer/HtmlDsl), `_mkRequest`'s
    // auth helpers `_bearerFromAuth` / `jwtVerify` (JwtAuth/Jwt), and `serve` /
    // `_ssc_http_serve` (WsServer/WsServer); resolvers run async.  Force all of
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
      line(s"_ssc_http_route($m, $p)(${r.handler});")
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
      // jsgen-enum-payload-extract: enum cases carry a `_tag` field, so the positional
      // `Object.values(...).slice(1)` fallback binds the wrong slot — index field accessors by NAME.
      case d: Defn.Enum =>
        d.templ.body.stats.foreach {
          case ec: Defn.EnumCase =>
            val ps = ec.ctor.paramClauses.flatMap(_.values).map(_.name.value).toList
            if ps.nonEmpty then result(ec.name.value) = ps
          case _ => ()
        }
      case _ => ()
    }
    def scanSection(s: Section): Unit =
      s.content.foreach {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
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

  private def caseClassFieldTypesInModule(module: Module): Map[String, Map[String, String]] =
    val result = scala.collection.mutable.Map.empty[String, Map[String, String]]
    def scanStats(stats: List[scala.meta.Stat]): Unit = stats.foreach {
      case d: Defn.Class if d.mods.exists(_.isInstanceOf[Mod.Case]) =>
        val fields = d.ctor.paramClauses.flatMap(_.values).flatMap { pv =>
          pv.decltpe.collect { case Type.Name(t) => pv.name.value -> t }
        }.toMap
        result(d.name.value) = fields
      case _ => ()
    }
    def scanSection(s: Section): Unit =
      s.content.foreach {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
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

  private def caseClassTagsInModule(module: Module): Map[String, Int] =
    var tag = 0
    val result = scala.collection.mutable.LinkedHashMap.empty[String, Int]
    def assignTag(name: String): Unit = { result(name) = tag; tag += 1 }
    def scanStats(stats: List[scala.meta.Stat]): Unit = stats.foreach {
      case d: Defn.Class if d.mods.exists(_.isInstanceOf[Mod.Case]) => assignTag(d.name.value)
      case d: Defn.Enum =>
        d.templ.body.stats.foreach {
          case ec: Defn.EnumCase              => assignTag(ec.name.value)
          case rec: Defn.RepeatedEnumCase     => rec.cases.foreach(nm => assignTag(nm.value))
          case _                              => ()
        }
      case _ => ()
    }
    def scanSection(s: Section): Unit =
      s.content.foreach {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
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

  // ── arch-meta-v2-p5 Track A (A2) — JS Mirror metadata + custom derives ─────
  //
  // Mirrors JVM A1a/A1b.  `summon[Mirror.Of[T]]` and a custom `derives TC`
  // (`object TC: def derived(m: Mirror)`) were both interpreter-only on JS:
  // JsGen emits case classes as plain constructor functions (the `derives`
  // clause is already dropped — no stripping needed), and `summon` resolved to
  // an undefined name.  We register a per-product-type Mirror object and a lazy
  // custom-derives instance in `_ssc_givens`, and route the matching `summon`
  // keys through `_resolveGiven` (see `genExpr`'s summon case).

  /** Walk a module's top-level scalascript stats, applying `f` to each block's
   *  top statement list. */
  private def foreachTopStats(module: Module)(f: List[scala.meta.Stat] => Unit): Unit =
    def scanSection(s: Section): Unit =
      s.content.foreach {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.foreach { node => ScalaNode.fold(node) {
            case Source(stats)     => f(stats)
            case Term.Block(stats) => f(stats)
            case _                 => ()
          }}
        case _ => ()
      }
      s.subsections.foreach(scanSection)
    module.sections.foreach(scanSection)

  /** True when any scalascript block mentions the `Mirror` API. */
  private def moduleUsesMirror(module: Module): Boolean =
    def sectionHas(s: Section): Boolean =
      s.content.exists {
        case cb: Content.CodeBlock => Lang.isParseable(cb.lang) && cb.source.contains("Mirror")
        case _ => false
      } || s.subsections.exists(sectionHas)
    module.sections.exists(sectionHas)

  /** Emit per-product-type Mirror objects + custom `derives` instances (lazy),
   *  registered in `_ssc_givens`, BEFORE the user blocks.  Records the summon
   *  keys so `genExpr` routes them through `_resolveGiven`. */
  /** The stdlib typeclasses synthesized structurally on JS (A1c), parallel to
   *  the JVM `stdlibStructuralTCs`. */
  private val jsStdlibStructuralTCs: Set[String] = Set("Eq", "Show", "Hash", "Order")

  private def emitMirrorAndDerives(module: Module): Unit =
    val userTCs        = scala.collection.mutable.Set.empty[String]
    val productClasses = scala.collection.mutable.LinkedHashSet.empty[String]
    foreachTopStats(module) { stats => stats.foreach {
      case o: Defn.Object if o.templ.body.stats.exists {
            case dd: Defn.Def => dd.name.value == "derived"; case _ => false
          } => userTCs += o.name.value
      case d: Defn.Class if d.mods.exists(_.isInstanceOf[Mod.Case]) && d.tparamClause.values.isEmpty =>
        productClasses += d.name.value
      case _ => ()
    }}
    val handledTCs     = userTCs.toSet ++ jsStdlibStructuralTCs
    val handledDerives = scala.collection.mutable.LinkedHashMap.empty[String, List[String]]
    foreachTopStats(module) { stats => stats.foreach {
      case d: Defn.Class if d.mods.exists(_.isInstanceOf[Mod.Case]) && d.templ.derives.nonEmpty =>
        val tcs = d.templ.derives.map { case Type.Name(n) => n; case t => t.syntax }
        if tcs.forall(handledTCs.contains) then handledDerives(d.name.value) = tcs
      case _ => ()
    }}
    val hasCustom  = handledDerives.values.exists(_.exists(userTCs.contains))
    val hasStdlib  = handledDerives.values.exists(_.exists(jsStdlibStructuralTCs.contains))
    val emitMirror = moduleUsesMirror(module) || hasCustom
    if !(emitMirror || hasStdlib) then return
    line("// arch-meta-v2-p5 Track A — Mirror metadata + derived givens")
    // Per-type Mirror objects — needed for `summon[Mirror.Of[T]]` and the custom
    // `derives` path; skipped for stdlib-only modules (which don't use Mirror).
    if emitMirror then productClasses.foreach { t =>
      val labels  = caseClassFieldsByType.getOrElse(t, Nil)
      val typeMap = caseClassFieldTypeMap.getOrElse(t, Map.empty)
      val types   = labels.map(l => typeMap.getOrElse(l, "Any"))
      val labelsJs = labels.map(jsQuote).mkString("[", ", ", "]")
      val typesJs  = types.map(jsQuote).mkString("[", ", ", "]")
      val ctorArgs = labels.indices.map(i => s"xs[$i]").mkString(", ")
      line(s"""const _sscMirror_$t = _ssc_mkMirror(${jsQuote(t)}, $labelsJs, $typesJs, [], true, function(xs){ return $t($ctorArgs); }, function(x){ return -1; });""")
      line(s"""_ssc_givens[${jsQuote(s"Mirror_$t")}] = _sscMirror_$t;""")
      jsSyntheticGivenKeys += s"Mirror_$t"
    }
    handledDerives.foreach { (t, tcs) =>
      tcs.foreach { tc =>
        val key = s"${tc}_$t"
        if userTCs.contains(tc) then
          // custom derives: lazy (TC is a not-yet-initialised `const`)
          line(s"""_ssc_def_given(${jsQuote(key)}, function(){ return $tc.derived(_sscMirror_$t); });""")
        else
          // stdlib structural: eager (depends only on runtime helpers)
          val obj = tc match
            case "Eq"    => "{ eqv: function(a, b) { return _eq(a, b); }, neqv: function(a, b) { return !_eq(a, b); } }"
            case "Show"  => "{ show: function(a) { return _ssc_structShow(a); } }"
            case "Hash"  => "{ hash: function(a) { return _ssc_structHash(a); } }"
            case "Order" => "{ compare: function(a, b) { return _ssc_structCompare(a, b); }, " +
                            "lt: function(a, b) { return _ssc_structCompare(a, b) < 0; }, " +
                            "gt: function(a, b) { return _ssc_structCompare(a, b) > 0; }, " +
                            "lte: function(a, b) { return _ssc_structCompare(a, b) <= 0; }, " +
                            "gte: function(a, b) { return _ssc_structCompare(a, b) >= 0; }, " +
                            "min: function(a, b) { return _ssc_structCompare(a, b) <= 0 ? a : b; }, " +
                            "max: function(a, b) { return _ssc_structCompare(a, b) >= 0 ? a : b; } }"
            case _       => "undefined"
          line(s"""_ssc_givens[${jsQuote(key)}] = $obj;""")
        jsSyntheticGivenKeys += key
      }
    }

  // Records the subtype edges (`child → direct parents`) and concrete leaf names of one
  // module into the accumulators `subtypeParents` / `subtypeConcrete`. Descends into
  // namespace/`package:` `Defn.Object`s (a `package:` module compiles to a wrapping
  // object — without descent its types would be invisible). Called for the entry module
  // and, in genImport, for each imported module, so a `case h: <trait>` whose trait + its
  // subtypes live in a different module than the `match` resolves correctly. See
  // recomputeSubtypeClosure + genPattern's Pat.Typed branch.
  private def collectSubtypeEdgesFromModule(module: Module): Unit =
    def parentNames(inits: List[scala.meta.Init]): List[String] = inits.flatMap { init =>
      init.tpe match
        case Type.Name(n)   => List(n)
        case ta: Type.Apply => ta.tpe match { case Type.Name(n) => List(n); case _ => Nil }
        case _              => Nil
    }
    def record(name: String, parents: List[String], isConcrete: Boolean): Unit =
      if parents.nonEmpty then
        subtypeParents.getOrElseUpdate(name, scala.collection.mutable.LinkedHashSet.empty) ++= parents
      if isConcrete then subtypeConcrete += name
    def deep(stats: List[Stat]): Unit = stats.foreach {
      case o: Defn.Object if o.mods.exists(_.isInstanceOf[Mod.Case]) =>
        // case object Foo extends T — a concrete singleton; still descend in case it nests types.
        record(o.name.value, parentNames(o.templ.inits), isConcrete = true)
        deep(o.templ.body.stats)
      case o: Defn.Object => deep(o.templ.body.stats)  // namespace / `package:` wrapper
      case d: Defn.Class if d.mods.exists(_.isInstanceOf[Mod.Case]) =>
        record(d.name.value, parentNames(d.templ.inits), isConcrete = true)
      case td: Defn.Trait =>
        record(td.name.value, parentNames(td.templ.inits), isConcrete = false)
      case d: Defn.Enum =>
        record(d.name.value, parentNames(d.templ.inits), isConcrete = false)
        d.templ.body.stats.foreach {
          case ec: Defn.EnumCase          => record(ec.name.value, List(d.name.value), isConcrete = true)
          case rec: Defn.RepeatedEnumCase => rec.cases.foreach(nm => record(nm.value, List(d.name.value), isConcrete = true))
          case _                          => ()
        }
      case _ => ()
    }
    def scanSection(s: Section): Unit =
      s.content.foreach {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.foreach { node => ScalaNode.fold(node) {
            case Source(stats)     => deep(stats); ()
            case Term.Block(stats) => deep(stats); ()
            case _                 => ()
          }}
        case _ => ()
      }
      s.subsections.foreach(scanSection)
    module.sections.foreach(scanSection)

  // Derives `subtypeClosure` (supertype → transitive concrete-descendant `_type` names)
  // from the current `subtypeParents` accumulator. For every concrete leaf, walk its
  // ancestor chain and add it under each ancestor (cycle-guarded). Cheap; called whenever
  // the accumulator grows (entry module + each import) so the closure stays complete.
  private def recomputeSubtypeClosure(): Unit =
    val closure = scala.collection.mutable.LinkedHashMap.empty[String, scala.collection.mutable.LinkedHashSet[String]]
    for c <- subtypeConcrete do
      val seen = scala.collection.mutable.HashSet.empty[String]
      var frontier = subtypeParents.get(c).map(_.toList).getOrElse(Nil)
      while frontier.nonEmpty do
        val next = scala.collection.mutable.ListBuffer.empty[String]
        for p <- frontier if seen.add(p) do
          closure.getOrElseUpdate(p, scala.collection.mutable.LinkedHashSet.empty) += c
          next ++= subtypeParents.get(p).map(_.toList).getOrElse(Nil)
        frontier = next.toList
    subtypeClosure = closure.view.mapValues(_.toSet).toMap

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
      "Env.get", "Env.set", "Env.required",
      // js-effect-runner-preamble: these stdlib effects also `_perform` at
      // runtime (effects.mjs) but were absent from the builtin seed, so a
      // function using only them (e.g. `def counter(): Int ! State[Int]`) was
      // NOT recognised as effectful and got emitted as a PLAIN (non-CPS)
      // function — `State.get()` returned the raw `_Perform` object instead of
      // the threaded value and `State.set` ran eagerly and was discarded, so
      // `runState` reported the initial state. (Tx.atomic/Auth.* are
      // capability-style — they don't `_perform` — so they stay out.)
      "State.get", "State.set", "State.modify",
      "Http.get", "Http.post", "Http.request",
      "Cache.memoize", "Retry.attempt"
    )

    def collectTrees(s: Section): List[scala.meta.Tree] =
      s.content.collect {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.map(ScalaNode.fold(_)(identity))
      }.flatten ++ s.subsections.flatMap(collectTrees)

    def collectImports(s: Section): List[Content.Import] =
      s.content.collect { case imp: Content.Import => imp } ++ s.subsections.flatMap(collectImports)

    // Resolve an import to a file path, mirroring genImport's resolution.
    def resolveImport(path: String, dir: os.Path): Option[os.Path] =
      val initial =
        try scalascript.imports.ImportResolver.resolve(path, dir, moduleDeps, lockPath)
        catch case _: Throwable => dir / os.RelPath(path)
      if os.exists(initial) then Some(initial)
      else resolveStdImportFromProjectTree(path, dir).filter(os.exists)

    // Effect analysis must be WHOLE-PROGRAM: a function is effectful if it
    // transitively performs an effect, possibly via a function in an imported
    // module (e.g. `accountBalance` → imported `query` → `Journal.read`). Collect
    // the union of this module's trees and (recursively) every imported module's
    // trees, so EffectAnalysis derives the transitive fixpoint in one pass. A
    // visited-set guards diamonds/cycles; a module that fails to parse standalone
    // is skipped (its effects, if any, are conservatively unseen).
    val visited = scala.collection.mutable.Set.empty[String]
    def collectTreesDeep(m: Module, dir: os.Path): List[scala.meta.Tree] =
      val own = m.sections.flatMap(collectTrees)
      val imported = m.sections.flatMap(collectImports).flatMap { imp =>
        resolveImport(imp.path, dir) match
          case Some(p) if visited.add(p.toString) =>
            try collectTreesDeep(scalascript.artifact.MacroCodegen.expand(scalascript.parser.Parser.parse(os.read(p))), p / os.up)
            catch case _: Throwable => Nil
          case _ => Nil
      }
      own ++ imported

    val trees = collectTreesDeep(module, baseDir.getOrElse(os.pwd))
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
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
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
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
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
            if Lang.isParseable(cb.lang) && !cb.attrs.get("side").contains("server") =>
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
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
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
    topLevelUserRenames.clear()
    registerTopLevelUserRenames(module)
    caseClassFieldsByType = caseClassFieldsInModule(module)
    caseClassFieldTypeMap = caseClassFieldTypesInModule(module)
    caseClassTagMap       = caseClassTagsInModule(module)
    subtypeParents.clear(); subtypeConcrete.clear()
    jsSyntheticGivenKeys.clear()
    collectSubtypeEdgesFromModule(module); recomputeSubtypeClosure()
    collectFuncParamOrders(module)
    analyzeMutualRecursion(module)
    analyzeEffects(module)
    scanForRunAsyncParallel(module)
    scanForRunActors(module)
    scanForAwaitClient(module)
    scanForStreams(module)
    val (_cUse, _tkUse) = scanContentUsage(module)
    contentRuntimeEnabled = _cUse
    contentToolkitRuntimeEnabled = _tkUse
    if contentRuntimeEnabled then collectImportedContent(module) else importedContentDocuments.clear()
    contentSectionIndex = 0
    // Emit `route(...)` registrations from front-matter before user blocks,
    // so a typical user-side `serve(port)` (last statement of the script)
    // sees them already registered.  JS function declarations are hoisted,
    // so forward references to handler defs resolve at call time.
    emitFrontmatterRoutes(module)
    emitI18nTable(module)
    emitHttpTypedRouteClients(module.manifest.toList.flatMap(_.apiClients), module)
    if contentRuntimeEnabled then emitContentRuntime(module.document)
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

    def walkSection(s: Section, contentSection: Option[SectionContent]): Unit =
      val sectionIndex = contentSection.map { _ =>
        val index = contentSectionIndex
        contentSectionIndex += 1
        index
      }
      s.content.foreach {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) && cb.attrs.get("side").contains("server") =>
          ()
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          flushScala()
          withContentCurrentSection(sectionIndex) {
            cb.tree.foreach(genScalaNode)
          }
        case Content.CodeBlock(lang, src, _, _, _, _, _) if Lang.isStandardScala(lang) =>
          flushSS()
          scalaBuf += src.stripTrailing()
        // `javascript` blocks: backend-specific native JS (backend-blocks-p4).
        // Emit verbatim into the bundle so function definitions are callable.
        // html/css StringBlocks keep the template-value genStringBlock path.
        case cb: Content.CodeBlock if Lang.isJavaScript(cb.lang) =>
          flushScala()
          sb.append("// ── javascript block ─────────────────────────────────────────────────\n")
          sb.append(cb.source.stripTrailing())
          sb.append("\n")
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
      s.subsections.zipWithIndex.foreach { (child, index) =>
        walkSection(child, contentSection.flatMap(_.children.lift(index)))
      }

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
      sb.append("  _println = function(...args) { process.stdout.write(args.map(_show).join(' ') + '\\n'); };\n")
      sb.append("  _print   = function(...args) { process.stdout.write(args.map(_show).join(''));         };\n")
      sb.append("  if (typeof Console !== 'undefined') {\n")
      sb.append("    Console.println = _println;\n")
      sb.append("    Console.print   = _print;\n")
      sb.append("  }\n")
      sb.append("}\n")
    module.sections.zipWithIndex.foreach { (section, index) =>
      walkSection(section, module.document.flatMap(_.sections.lift(index)))
    }
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

  private def genSections(sections: List[Section], contentSections: List[SectionContent]): Unit =
    sections.zipWithIndex.foreach { (section, index) =>
      genSection(section, contentSections.lift(index))
    }

  private[codegen] def genSection(section: Section, contentSection: Option[SectionContent] = None): Unit =
    val sectionIndex = contentSection.map { _ =>
      val index = contentSectionIndex
      contentSectionIndex += 1
      index
    }
    section.content.foreach {
      case cb: Content.CodeBlock if Lang.isParseable(cb.lang) && cb.attrs.get("side").contains("server") =>
        ()
      case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
        withContentCurrentSection(sectionIndex) {
          cb.tree.foreach(genScalaNode)
        }
      case cb: Content.CodeBlock if Lang.isStandardScala(cb.lang) =>
        line(s"/* scala: standard Scala 3 block — compile via Scala.js for JS execution */")
      case cb: Content.CodeBlock if Lang.isJavaScript(cb.lang) =>
        sb.append("// ── javascript block ─────────────────────────────────────────────────\n")
        sb.append(cb.source.stripTrailing())
        sb.append("\n")
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
    section.subsections.zipWithIndex.foreach { (child, index) =>
      genSection(child, contentSection.flatMap(_.children.lift(index)))
    }

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
    // arch-meta-v2 macro-codegen-backends (cross-module) — strip the imported
    // module's own quoted-macro defs before inlining its blocks (the consumer's
    // call sites were already expanded against these by the entry-hook pass).
    // No baseDir → no recursive import resolution here (no-op for macro-free imports).
    val childModule = scalascript.artifact.MacroCodegen.expand(Parser.parse(os.read(resolvedPath)))
    if !importedFiles.contains(key) then
      importedFiles += key
      val childDir = resolvedPath / os.up
      val childGen = new JsGen(Some(childDir), intrinsics = intrinsics, lockPath = lockPath, topLevelConsts = topLevelConsts, mergeHelperEmitted = mergeHelperEmitted, namespaceMembers = namespaceMembers, declaredBindings = declaredBindings, declaredEnumCases = declaredEnumCases, effectOps = effectOps, effectfulFuns = effectfulFuns, multiShotEffects = multiShotEffects, usedTopLevelJsNames = usedTopLevelJsNames, importedJsNames = importedJsNames)
      childGen.registerTopLevelUserRenames(childModule)
      importedJsNames(key) = collectTopLevelNames(childModule)
        .map(entry => entry.name -> childGen.emittedName(entry.name))
        .toMap
      childGen.importedFiles ++= importedFiles
      // Record the imported module's function / case-class param orders so that
      // (a) named-arg call sites later in THIS module (the importer) reorder, and
      // (b) the childGen — which emits the imported module's own bodies via
      // genScalaNode, bypassing genModule's pre-pass — has them too for the
      // imported module's internal named-arg constructions.
      collectParamOrdersFromModule(childModule)
      childGen.importedParamOrder ++= importedParamOrder
      // Record the imported module's subtype edges so a `case x: <trait>` in THIS module
      // (or in the childGen as it emits the import's own bodies) matches a subtype whose
      // declaration lives in the imported module — the trait + its subtypes routinely sit
      // in a different file than the `match`. Refresh both gens' derived closures; the
      // childGen further grows its own as it processes its nested imports.
      collectSubtypeEdgesFromModule(childModule)
      recomputeSubtypeClosure()
      // Deep-copy the parent set values so the childGen's later in-place merges (as it
      // processes its own imports) don't mutate the parent's shared LinkedHashSets.
      subtypeParents.foreach { (k, v) =>
        childGen.subtypeParents.getOrElseUpdate(k, scala.collection.mutable.LinkedHashSet.empty) ++= v
      }
      childGen.subtypeConcrete ++= subtypeConcrete
      childGen.recomputeSubtypeClosure()
      // The childGen shares the WHOLE-PROGRAM effect sets (effectOps/effectfulFuns/
      // multiShotEffects), populated once by the entry generator's `analyzeEffects`,
      // which collects trees across the entire import graph. So an effect-performing
      // function defined here — or one calling a transitively-imported effectful
      // function — is already recognised; no per-import re-analysis is needed.
      // Give the childGen the imported module's Int/Long/case-class type evidence
      // so its bodies get the same integer-arithmetic lowering the entry module
      // gets — otherwise an imported `Int / Int` emits floating `_arith('/')`
      // (v1-js-imported-int-division-loses-type). Nested imports recurse through
      // childGen.genImport below, each populating its own grandchild gen.
      childGen.registerImportedTypeEvidence(childModule)
      // Emit only the definitions from the imported module (suppress top-level output)
      childModule.sections.foreach { section =>
        section.content.foreach {
          case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
            cb.tree.foreach(childGen.genScalaNode)
          case nestedImp: Content.Import =>
            // Propagate transitive imports — e.g., std/selective.ssc
            // pulls in std/either.ssc, and consumers of selective need
            // Either's constructors emitted too.
            childGen.genImport(nestedImp)
          case _ => ()
        }
        section.subsections.foreach(section => childGen.genSection(section))
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
      val bindsContentRuntimeFn =
        (imp.path.endsWith("std/content.ssc") && contentIntrinsicNames(b.name)) ||
          (imp.path.endsWith("std/ui/content.ssc") && contentToolkitIntrinsicNames(b.name))
      if bindsContentRuntimeFn then
        b.alias.foreach { localName =>
          val localJsName = emittedName(localName)
          if !declaredBindings.contains(localJsName) then
            declaredBindings += localJsName
            line(s"const $localJsName = ${b.name};")
        }
      else
        val fullName  = s"$pkgPrefix${b.name}"
        val localName = b.alias.getOrElse(b.name)
        val localJsName = emittedName(localName)
        val targetJsName =
          if childPkg.isEmpty then importedJsNames.get(key).flatMap(_.get(b.name)).getOrElse(b.name)
          else fullName
        // If the child module declares an exports list and this name is absent,
        // skip — don't block a later import from the correct module.
        val notExported = childExports.nonEmpty && !childExports.contains(b.name)
        if targetJsName != localJsName && !notExported && !declaredBindings.contains(localJsName) then
          declaredBindings += localJsName
          line(s"const $localJsName = $targetJsName;")
    }

  private[codegen] def resolveStdImportFromProjectTree(rawPath: String, base: os.Path): Option[os.Path] =
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
          case _: Defn.Given   =>
            // All givens register in _ssc_givens (a global side-effect) → always emit
            true
          case _: Defn.Trait   => true     // erased anyway; never filtered
          case _: Term         => true     // top-level term/side effect: always keep
          case _               => true     // conservative: keep unknown node kinds

  private def genBlockStats(stats: List[Stat], topLevel: Boolean): Unit =
    val companionClassNames = stats.collect { case d: Defn.Class => d.name.value }.toSet
    stats.zipWithIndex.foreach { (s, i) =>
      val isLast = i == stats.length - 1
      // Skip unreachable top-level declarations when tree-shaking is active
      if !isReachableStat(s, topLevel) then ()
      else
      s match
        case tw: Term.While =>
          val savedBuf = loopHoistBuf
          val newBuf   = mutable.Buffer.empty[(String, String)]
          loopHoistBuf = newBuf
          val bodyJs = genExpr(tw.body)
          val condJs = genExpr(tw.expr)
          loopHoistBuf = savedBuf
          newBuf.foreach { (k, v) => line(s"const $k = $v;") }
          line(s"while ($condJs) { $bodyJs; }")
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
            case d: Defn.Object if topLevel && companionClassNames.contains(d.name.value) =>
              val name = emittedName(d.name.value)
              topLevelConsts += name
              line(s"Object.assign($name, ${genObjectAsExpr(d, name)});")
            case d: Defn.Object if topLevel =>
              val name = emittedName(d.name.value)
              if topLevelConsts.contains(name) then
                emitMergeHelper()
                line(s"_ssc_mergeDeep($name, ${genObjectAsExpr(d, name)});")
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

  /** js-collection-perf: numeric element type of a seq ctor RHS (`Array(0,…)`/`Vector(1,2)`/`List(…)`)
   *  — Some("Int"/"Double") when the first element is numeric. Lets a LOCAL `val a = Array(…)` be
   *  tracked in `listElemType` (collectDefs only sees top-level declared types), so `a(i)` reads
   *  lower to `a[i]` and type as numeric. */
  private def numericSeqCtorElem(rhs: Term): Option[String] = rhs match
    case Term.Apply.After_4_6_0(fn, ac) =>
      val nm = fn match
        case Term.Name(n) => n
        case Term.ApplyType.After_4_6_0(Term.Name(n), _) => n
        case _ => ""
      if Set("Vector", "Array", "List", "Seq", "IndexedSeq", "Iterable").contains(nm) then
        ac.values.headOption.flatMap {
          case e if isIntExpr(e)     => Some("Int")
          case e if isNumericExpr(e) => Some("Double")
          case _                     => None
        }
      else None
    case _ => None

  /** js-collection-perf: recognise `LazyList.from(start).map(1-arg-lambda)?.take(n)` (the receiver
   *  of a terminal `.sum`) → (start, map?, n). `take` REQUIRED (unbounded `.sum` never terminates). */
  private def lazyFromMapTakeJs(recv: Term): Option[(Term, Option[(String, Term)], Term)] = recv match
    case Term.Apply.After_4_6_0(Term.Select(inner, Term.Name("take")), tArgs)
        if tArgs.values.lengthCompare(1) == 0 =>
      val nT = tArgs.values.head
      inner match
        case Term.Apply.After_4_6_0(Term.Select(
               Term.Apply.After_4_6_0(Term.Select(Term.Name("LazyList"), Term.Name("from")), fArgs),
               Term.Name("map")), mArgs)
            if fArgs.values.lengthCompare(1) == 0 && mArgs.values.lengthCompare(1) == 0 =>
          mArgs.values.head match
            case Term.Function.After_4_6_0(pc, body) if pc.values.lengthCompare(1) == 0 =>
              Some((fArgs.values.head, Some((pc.values.head.name.value, body)), nT))
            case _ => None
        case Term.Apply.After_4_6_0(Term.Select(Term.Name("LazyList"), Term.Name("from")), fArgs)
            if fArgs.values.lengthCompare(1) == 0 =>
          Some((fArgs.values.head, None, nT))
        case _ => None
    case _ => None

  /** Emit the fused native-loop IIFE for a recognised LazyList `.sum` pipeline (no `_lz*` thunks). */
  private def genLazySumFused(pipe: (Term, Option[(String, Term)], Term)): String =
    val (startT, mapOpt, nT) = pipe
    val bodyJs = mapOpt match
      case Some((p, body)) =>
        val lp = localBindingName(p)
        val bodyExpr = withLocalBindings(List(p)) { withParamTyped(p, "Int")(genExpr(body)) }
        s"const $lp = __st + __k; __acc += ($bodyExpr);"
      case None            => "__acc += (__st + __k);"
    s"(() => { const __st = (${genExpr(startT)}); const __n = (${genExpr(nT)}); let __acc = 0; let __k = 0; " +
    s"while (__k < __n) { $bodyJs __k += 1; } return __acc; })()"

  /** A local `val`/`var` binding is AUTHORITATIVE for its name: set the numeric
   *  evidence to match the RHS, overriding any same-named evidence that leaked in
   *  from another function's param (intVars/numericVars are name-keyed and
   *  module-global). Without the removal, a Char `val c = s.charAt(i)` inherits an
   *  Int param `c` from a sibling function, so `c == 34` wrongly takes the numeric
   *  fast path `c === 34` — which is always false on a boxed `_char` (=== does not
   *  call valueOf). Returns true if the RHS was int/numeric (so callers can still
   *  chain a tuple check). Also (authoritatively) tracks `longVars` — a Long-typed
   *  binding is a JS BigInt (v1-js-long-precision-and-bitops) — with the same
   *  name-shadowing so a leaked Long param can't misclassify a non-Long local. */
  private def rebindNumericEvidence(name: String, rhs: Term, declT: Option[Type] = None): Boolean =
    // `declT.contains(Type.Name("Long"))` never matched a PARSED type (scalameta tree
    // equality includes source position) — use a pattern check. (js-long-param-evidence.)
    val declaredLong = declT.exists { case Type.Name("Long") => true; case _ => false }
    if isLongExpr(rhs) || declaredLong then longVars += name else longVars -= name
    if isIntExpr(rhs) then { intVars += name; numericVars -= name; true }
    else if isNumericExpr(rhs) then { numericVars += name; intVars -= name; true }
    else { intVars -= name; numericVars -= name; false }

  private def genStat(stat: Stat): Unit = stat match
    case Defn.Val(_, pats, declT, rhs) =>
      pats match
        case List(Pat.Var(n)) =>
          if !rebindNumericEvidence(n.value, rhs, declT) && isTupleExpr(rhs) then tupleVars += n.value
          declT.flatMap(numericListElem).orElse(numericSeqCtorElem(rhs))
            .foreach(e => listElemType(n.value) = e)
          line(s"const ${emittedName(n.value)} = ${genExpr(rhs)};")
        case List(pat) =>
          // Tuple/pattern destructuring
          val patJs = genPatDestructure(pat)
          line(s"const $patJs = ${genExpr(rhs)};")
        case _ =>
          line(s"/* multi-pat val */")

    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), declT, rhs) =>
      rebindNumericEvidence(n.value, rhs, declT)
      declT.flatMap(numericListElem).orElse(numericSeqCtorElem(rhs))
        .foreach(e => listElemType(n.value) = e)
      line(s"let ${emittedName(n.value)} = ${genExpr(rhs)};")

    // arch-ffi-p1 — @js("expr") / @jvm-only handling for extern defs.
    // @js("expr")     → emit a JS function with the inline expression body.
    // @jvm + no @js   → emit a stub that throws a clear runtime error.
    // no annotation   → skip (intrinsic table handles it at call sites).
    case d: Defn.Def if scalascript.transform.EffectAnalysis.isExternDef(d.body) =>
      val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value).toList
      val sourceName = d.name.value
      val jsName = emittedName(sourceName)
      extractAnnotationArg(d.mods, "js") match
        case Some(jsExpr) =>
          val body     = substituteJsArgs(jsExpr, params)
          val paramsStr = params.mkString(", ")
          line(s"function $jsName($paramsStr) { return $body; }")
        case None =>
          if extractAnnotationArg(d.mods, "jvm").isDefined then
            // @jvm-only: provide a stub that throws at runtime instead of a silent undefined
            val paramsStr = params.mkString(", ")
            line(s"function $jsName($paramsStr) { throw new Error('$sourceName is @jvm-only and cannot be called from the JS backend.'); }")

    case d: Defn.Def =>
      val paramVals   = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
      val params      = paramVals.map(_.name.value)
      val hasDefaults = paramVals.exists(_.default.isDefined)
      val sourceName  = d.name.value
      val fname       = emittedName(sourceName)
      val defRenames  = paramRenameMap(params)
      // `using` params: explicit implicit parameter clauses `(using x: TC[T])`.
      // Emit guards to resolve them at runtime via _resolveGiven + _ssc_typeOf on a hint param.
      val nonUsingParams = d.paramClauseGroups.flatMap(_.paramClauses).filterNot { pc =>
        pc.mod match { case Some(_: scala.meta.Mod.Using) => true; case _ => false }
      }.flatMap(_.values)
      val usingGuards: List[String] = d.paramClauseGroups.flatMap(_.paramClauses).filter { pc =>
        pc.mod match { case Some(_: scala.meta.Mod.Using) => true; case _ => false }
      }.flatMap(_.values).flatMap { pv =>
        val pname = pv.name.value
        pv.decltpe.toList.flatMap { tpe =>
          val guardOpt: Option[String] = tpe match
            case Type.Apply.After_4_6_0(Type.Name(tc), args) =>
              // Find a hint param whose type matches the type argument of TC[A]
              val typeParamName = args.values match
                case List(Type.Name(n)) => n
                case _                  => ""
              // A concrete type: starts uppercase with >1 char (Option, List, Int...).
              // For concrete type args, use literal key; for type vars (A, F, T...) use runtime.
              def isConcrete(n: String): Boolean = n.length > 1 && n.head.isUpper
              val matchingParam = if typeParamName.nonEmpty then
                nonUsingParams.find { p =>
                  p.decltpe.exists {
                    case Type.Name(n) => n == typeParamName
                    case ta: Type.Apply => ta.tpe match { case Type.Name(n) => n == typeParamName; case _ => false }
                    case _ => false
                  }
                }
              else None
              if matchingParam.isDefined then
                // Type variable matched a non-using param → derive TC arg at runtime
                val hint = matchingParam.get.name.value
                Some(s"""if ($pname === undefined) $pname = _resolveGiven("${tc}_" + _ssc_typeOf($hint));""")
              else if typeParamName.nonEmpty && isConcrete(typeParamName) then
                // Concrete type argument (e.g. Console[Option]) → use literal key
                Some(s"""if ($pname === undefined) $pname = _ssc_givens["${tc}_${typeParamName}"] ?? _resolveGiven("${tc}_${typeParamName}");""")
              else
                // Type variable without matching param → best-effort with first non-using param
                val hint = nonUsingParams.headOption.map(_.name.value).getOrElse("undefined")
                Some(s"""if ($pname === undefined) $pname = _resolveGiven("${tc}_" + _ssc_typeOf($hint));""")
            case Type.Name(tc) =>
              Some(s"""if ($pname === undefined) $pname = _ssc_givens["$tc"] || _resolveGiven("$tc");""")
            case _ => None
          guardOpt.toList
        }
      }
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
      if sourceName == "main" && params.isEmpty then hasMain = true
      // Effectful function: body emitted in CPS form, returns Free value.
      if isEffectfulFun(sourceName) then
        d.body match
          case Term.Block(stats) =>
            line(s"function $fname($paramsStr) { return ${genCpsBlockAsIife(stats)}; }")
          case expr =>
            line(s"function $fname($paramsStr) { return ${genCpsExpr(expr)}; }")
      // Context-bound params: emit plain function with auto-resolve guards; skip TCO
      else if cbParams.nonEmpty || usingGuards.nonEmpty then
        val hintParam = paramVals.headOption.map(_.name.value).getOrElse("undefined")
        val cbGuards = cbParams.map { (pname, skey) =>
          val tcName = skey.takeWhile(_ != '_')
          s"""if ($pname === undefined) $pname = _resolveGiven("${tcName}_" + _ssc_typeOf($hintParam));"""
        }
        val allGuards = cbGuards ++ usingGuards
        d.body match
          case Term.Block(bodyStats) =>
            line(s"function $fname($paramsStr) {")
            indent += 1
            allGuards.foreach(line)
            genFunctionBody(bodyStats)
            indent -= 1
            line("}")
          case expr =>
            line(s"function $fname($paramsStr) {")
            indent += 1
            allGuards.foreach(line)
            line(s"return ${genExpr(expr)};")
            indent -= 1
            line("}")
      // Mutual recursion group → _impl + trampoline wrapper.
      // Defaults disable the TCO/mutual-TCO shadowing path since the _p shadow
      // names would shadow the original parameter names referenced in default
      // expressions; defaults are uncommon in tight recursive loops anyway.
      else if mutualGroups.contains(sourceName) && params.nonEmpty && !hasDefaults &&
              !containsAwaitClient(d.body) then
        genMutualTcoFun(d, sourceName, fname, params)
      // Self-TCO: emit a while-loop trampoline when the function calls itself and all
      // self-calls are in tail position. The anywhereContainsSelfCall guard is required
      // because hasNonTailSelfCall returns false for non-recursive functions too (zero
      // non-tail self-calls), which would incorrectly wrap every function in while(true).
      else if params.nonEmpty && fname.nonEmpty && !hasDefaults &&
              !containsAwaitClient(d.body) &&
              anywhereContainsSelfCall(d.body, sourceName) &&
              !hasNonTailSelfCall(d.body, sourceName, tailPos = true) then
        // Formals are _p shadow-names so we can declare mutable let params inside.
        // safeJsParam guards against JS reserved words (e.g. `default` → `default_p`).
        val renames  = paramRenameMap(params)
        val formals  = params.map(p => s"_$p").mkString(", ")
        val letDecls = "let " + params.map(p => s"${safeJsParam(p)} = _$p").mkString(", ")
        line(s"function $fname($formals) {")
        indent += 1
        line(s"$letDecls;")
        line("while(true) {")
        indent += 1
        withParamRenames(renames)(genTcoBody(d.body, sourceName, params))
        indent -= 1
        line("}")
        indent -= 1
        line("}")
      else
        val fnKw = if containsAwaitClient(d.body) then "async function" else "function"
        // Auto-curry guard for a multi-clause def `def add(a)(b)` so a partial application
        // `add(3)` returns a function instead of computing on `undefined` (NaN). Only for plain
        // multi-clause defs (no defaults/using/cb); full application `add(1)(2)` is unaffected
        // because it arrives flattened as `add(1, 2)` (arity reached). (jvmgen-js-curried-partial.)
        val explicitClauseCount = d.paramClauseGroups.flatMap(_.paramClauses).count { pc =>
          pc.mod match { case Some(_: scala.meta.Mod.Using) => false; case _ => true }
        }
        val curryGuard: Option[String] =
          if explicitClauseCount > 1 && !hasDefaults && params.nonEmpty then
            Some(s"if (arguments.length < ${params.size}) return _curry($fname, ${params.size}, arguments);")
          else None
        d.body match
          case Term.Block(bodyStats) =>
            line(s"$fnKw $fname($paramsStr) {")
            indent += 1
            curryGuard.foreach(line)
            withParamRenames(defRenames)(genFunctionBody(bodyStats))
            indent -= 1
            line("}")
          case tm: Term.Match =>
            line(s"$fnKw $fname($paramsStr) {")
            indent += 1
            curryGuard.foreach(line)
            withParamRenames(defRenames)(genMatchAsStmts(tm, t => line(s"return ${genExpr(t)};")))
            indent -= 1
            line("}")
          case expr =>
            curryGuard match
              case Some(g) =>
                line(s"$fnKw $fname($paramsStr) {")
                indent += 1
                line(g)
                line(s"return ${withParamRenames(defRenames)(genExpr(expr))};")
                indent -= 1
                line("}")
              case None =>
                line(s"$fnKw $fname($paramsStr) { return ${withParamRenames(defRenames)(genExpr(expr))}; }")
      cbSummonMap.clear()
      cbSummonMap ++= savedCbMap

    case d: Defn.Class =>
      // case class → constructor function returning plain object
      val paramVals = d.ctor.paramClauses.flatMap(_.values)
      val params = paramVals.map(_.name.value)
      val typeName = d.name.value
      val ctorName = emittedName(typeName)
      val paramsStr = paramListWithDefaults(paramVals)
      val fields   = params.map(p => s"$p: $p").mkString(", ")
      val tagField = caseClassTagMap.get(typeName).map(t => s"_tag: $t, ").getOrElse("")
      line(s"function $ctorName($paramsStr) { return {_type: '$typeName', ${tagField}$fields}; }")
      line(jsTypedJsonRegisterProduct(typeName, params, ctorName))
      // Compile case-class body methods (e.g. `override def toString`, or trait
      // methods on a class that `extends` an interface — a FixtureVfs's
      // `fullPath(path)`) as typed extension registrations so `_dispatch` finds
      // them via `_extensions['Type:method']`.  Both zero-param methods and
      // methods with a single (non-implicit) parameter clause are registered as
      // `(_self, p1, p2, …) => { const {fields} = _self; return body; }`.  A
      // method parameter that shadows a field is NOT re-destructured (a `const`
      // redeclaration of a lambda param is a JS syntax error).  Curried methods
      // (>1 param clause) are left unregistered — their calling convention would
      // not match `_dispatch`'s flat argument array.
      d.templ.body.stats.foreach {
        case meth: Defn.Def
            if meth.paramClauseGroups.flatMap(_.paramClauses).filterNot(_.mod.nonEmpty).sizeIs <= 1 =>
          val methName    = meth.name.value
          val methParams  = meth.paramClauseGroups.flatMap(_.paramClauses)
            .filterNot(_.mod.nonEmpty).flatMap(_.values).map(_.name.value)
          // A parameter that is a JS reserved word (e.g. `delete`) must be
          // renamed in both the lambda header and the body (`delete` -> `delete_p`).
          val methRenames = paramRenameMap(methParams)
          val fields      = params.filterNot(methParams.contains)
          val destructure = if fields.isEmpty then "" else s"const {${fields.mkString(", ")}} = _self; "
          val recv        = ("_self" :: methParams.map(safeJsParam)).mkString(", ")
          val bodyJs      = withParamRenames(methRenames)(genExpr(meth.body))
          line(s"_registerExt('$methName', ($recv) => { ${destructure}return $bodyJs; }, '$typeName');")
        case _ => ()
      }

    case d: Defn.Object =>
      val objName = emittedName(d.name.value)
      line(s"const $objName = ${genObjectAsExpr(d, objName)};")

    case d: Defn.Enum =>
      val enumName = emittedName(d.name.value)
      val allCases = scala.collection.mutable.ListBuffer.empty[(String, String)]
      val nullary  = scala.collection.mutable.ListBuffer.empty[String]
      def emitNullary(sourceCaseName: String): Unit =
        val caseName = emittedName(sourceCaseName)
        // Skip a duplicate global `const` when another enum already emitted this
        // parameterless case name (tags are global-by-name → structurally identical).
        if !declaredEnumCases.contains(caseName) then
          declaredEnumCases += caseName
          val tagField = caseClassTagMap.get(sourceCaseName).map(t => s", _tag: $t").getOrElse("")
          line(s"const $caseName = {_type: '$sourceCaseName'${tagField}};")
          line(jsTypedJsonRegisterProduct(sourceCaseName, Nil, None))
        allCases += sourceCaseName -> caseName; nullary += caseName
      d.templ.body.stats.foreach {
        case ec: Defn.EnumCase =>
          val sourceCaseName = ec.name.value
          val caseName = emittedName(sourceCaseName)
          val paramVals = ec.ctor.paramClauses.flatMap(_.values)
          val params = paramVals.map(_.name.value)
          if params.isEmpty then emitNullary(sourceCaseName)
          else
            if !declaredEnumCases.contains(caseName) then
              declaredEnumCases += caseName
              val paramsStr = paramListWithDefaults(paramVals)
              val fields    = params.map(p => s"$p: $p").mkString(", ")
              val tagField  = caseClassTagMap.get(sourceCaseName).map(t => s"_tag: $t, ").getOrElse("")
              line(s"function $caseName($paramsStr) { return {_type: '$sourceCaseName', ${tagField}$fields}; }")
              line(jsTypedJsonRegisterProduct(sourceCaseName, params, caseName))
            allCases += sourceCaseName -> caseName
        // `case A, B` (comma-separated parameterless cases) → RepeatedEnumCase.
        case rec: Defn.RepeatedEnumCase =>
          rec.cases.foreach(nm => emitNullary(nm.value))
        case _ => ()
      }
      // Companion: qualified `EnumName.Case` refs + `EnumName.values` (the
      // parameterless cases, in declaration order).
      val members = allCases.map((sourceCaseName, caseName) => s"$sourceCaseName: $caseName").mkString(", ")
      val sep     = if members.isEmpty then "" else ", "
      line(s"const $enumName = { $members${sep}values: [${nullary.mkString(", ")}] };")

    case td: Defn.Trait =>
      // Record parent typeclasses so given registrations can propagate up the hierarchy.
      val parents = td.templ.inits.flatMap { init =>
        init.tpe match
          case Type.Name(n)  => List(n)
          case ta: Type.Apply => ta.tpe match { case Type.Name(n) => List(n); case _ => Nil }
          case _             => Nil
      }
      if parents.nonEmpty then tcParentMap(td.name.value) = parents

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
        def extractTcArg(tpe: Type): Option[(String, String)] = tpe match
          case Type.Name(n) => Some(n -> n)
          case ta: Type.Apply => ta.tpe match
            case Type.Name(tc) =>
              val arg = ta.argClause.values match
                case List(Type.Name(n))    => n
                case List(ta2: Type.Apply) => ta2.tpe match { case Type.Name(n) => n; case _ => "_" }
                case _                     => "_"
              Some(tc -> arg)
            case _ => None
          case _ => None
        extractTcArg(init.tpe).foreach { (primaryTc, typeArg) =>
          val members = d.templ.body.stats.collect { case dd: Defn.Def =>
            s"${dd.name.value}: ${genDefAsMethod(dd)}"
          }
          val obj = s"{${members.mkString(", ")}}"
          val explicitName = d.name.value
          // Compute all typeclass keys transitively (primary + parent TCs)
          def allTcKeys(tc: String): List[String] =
            tc :: tcParentMap.getOrElse(tc, Nil).flatMap(allTcKeys)
          val allKeys = allTcKeys(primaryTc).map(tc => s"${tc}_${typeArg}").distinct
          if explicitName.nonEmpty then
            val explicitJsName = emittedName(explicitName)
            line(s"const $explicitJsName = $obj;")
            // Register under primary alias + all parent typeclass keys
            line(s"const ${primaryTc}_${typeArg} = $explicitJsName;")
            allKeys.foreach { key => line(s"""_ssc_givens["$key"] = $explicitJsName;""") }
          else
            val primaryKey = s"${primaryTc}_${typeArg}"
            line(s"const $primaryKey = $obj;")
            allKeys.foreach { key => line(s"""_ssc_givens["$key"] = $primaryKey;""") }
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
    // does not collide and silently overwrite each other.  A SYMBOLIC extension
    // operator (`~`, `<~`, `~>`, `++`, `|` — common in parser-combinator DSLs)
    // must be mangled: `~` is not a valid JS identifier char, so `_ext_Parser_~`
    // was a parse-time SyntaxError. The `_registerExt` dispatch key below keeps
    // the RAW symbol (it's a string), so only the function NAME needs mangling.
    val fnName = s"_ext_${recvType}_${jsSafeOpName(defn.name.value)}"
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
  /** `path` is the dotted JS access path of the namespace this object builds
   *  (e.g. `std.money`).  When a later section of the same namespace is emitted as a
   *  separate `_ssc_mergeDeep` IIFE, members declared by earlier sections are re-bound
   *  from `path` at the top of this IIFE so bare references (e.g. `Currency` inside
   *  `defaultCurrencies`) resolve. */
  private def genObjectAsExpr(d: Defn.Object, path: String): String =
    val objectName = d.name.value
    val decls = mutable.ArrayBuffer.empty[String]
    val names = mutable.ArrayBuffer.empty[String]
    val companionClassNames = d.templ.body.stats.collect { case cls: Defn.Class => cls.name.value }.toSet
    // Populate tcParentMap from trait declarations in this object (cross-block accumulation).
    d.templ.body.stats.foreach {
      case td: Defn.Trait =>
        val parents = td.templ.inits.flatMap { init =>
          init.tpe match
            case Type.Name(n)  => List(n)
            case ta: Type.Apply => ta.tpe match { case Type.Name(n) => List(n); case _ => Nil }
            case _             => Nil
        }
        if parents.nonEmpty then tcParentMap(td.name.value) = parents
      case _ => ()
    }
    d.templ.body.stats.foreach {
      case dd: Defn.Def if scalascript.transform.EffectAnalysis.isExternDef(dd.body) =>
        val fname = dd.name.value
        val stub = s"_ssc_ui_$fname"
        // Forward to the corresponding host UI runtime stub if present; else, if
        // this extern ALSO has a `RuntimeCall` intrinsic (e.g. `sha256` → the
        // preamble `_sha256`), fall back to that real impl so the namespace
        // member resolves in a standalone `emit-js` bundle (Node) instead of
        // `undefined`.  The inner `typeof` keeps it safe when the target isn't
        // emitted (capability off) — it stays `undefined`, the old behaviour.
        // Browser keeps preferring the `_ssc_ui_*` host stub. Avoids the TDZ
        // issue from the parent scope's later `const fname = pkg.fname`.
        // Only when the intrinsic renames to a DIFFERENT target (`sha256` →
        // `_sha256`).  An identity RuntimeCall (`csrfToken` → `csrfToken`, as in
        // std/auth) would make the fallback reference the very const being
        // declared (`const csrfToken = … : csrfToken`) → a TDZ self-reference.
        val fallback = intrinsics.get(scalascript.ir.QualifiedName(fname)).collect {
          case scalascript.backend.spi.RuntimeCall(target) if target != fname => target
        } match
          case Some(target) => s"(typeof $target !== 'undefined' ? $target : undefined)"
          case None         => "undefined"
        decls += s"const $fname = (typeof $stub !== 'undefined') ? $stub : $fallback;"
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
        // Context-bound params for functions inside objects/packages — must be included
        // in the function signature so that call sites can pass them (or trigger auto-resolve).
        @annotation.nowarn("msg=deprecated")
        val objCbParams: List[(String, String)] =
          dd.paramClauseGroups.flatMap(_.tparamClause.values).flatMap { tp =>
            tp.cbounds.map { cb =>
              val tvName = tp.name.value
              val tcName = cb match
                case Type.Name(n)   => n
                case ta: Type.Apply => ta.tpe match { case Type.Name(n) => n; case _ => "?" }
                case _              => "?"
              s"${tvName}$$${tcName}" -> s"${tcName}_${tvName}"
            }
          }
        val objCbGuards = objCbParams.map { (pname, skey) =>
          val tcName = skey.takeWhile(_ != '_')
          val hintParam = allClauses.flatMap(_.values).headOption.map(_.name.value).getOrElse("undefined")
          s"""if ($pname === undefined) $pname = _resolveGiven("${tcName}_" + _ssc_typeOf($hintParam));"""
        }
        val cbParamsStr = if objCbParams.isEmpty then "" else ", " + objCbParams.map(_._1).mkString(", ")
        val savedCbMap2 = cbSummonMap.toMap
        cbSummonMap.clear()
        objCbParams.foreach { (pname, skey) => cbSummonMap(skey) = pname }
        // Reserved-word params are renamed in the signature (safeJsParam, e.g.
        // `default` → `default_p`); the body must see the same renames or its
        // references emit the bare reserved word (SyntaxError on Node).
        val objDefRenames = paramRenameMap(allClauses.flatMap(_.values).map(_.name.value))
        val bodyJsRaw = withParamTypeEvidence(allClauses.flatMap(_.values)) {
         withParamRenames(objDefRenames) {
          dd.body match
            case Term.Block(bodyStats) =>
              if objCbGuards.isEmpty then genBlockAsIife(bodyStats)
              else s"{ ${objCbGuards.mkString(" ")} return ${genBlockAsIife(bodyStats)}; }"
            case expr =>
              if objCbGuards.isEmpty then genExpr(expr)
              else s"{ ${objCbGuards.mkString(" ")} return ${genExpr(expr)}; }"
         }
        }
        cbSummonMap.clear()
        cbSummonMap ++= savedCbMap2
        def clauseSig(params: List[Term.Param]): String =
          if params.nonEmpty && params.last.decltpe.exists(_.isInstanceOf[Type.Repeated]) then
            val nonVararg = params.init
            val vararg    = params.last.name.value
            if nonVararg.isEmpty then s"(...$vararg)"
            else s"(${paramListWithDefaults(nonVararg)}, ...$vararg)"
          else s"(${paramListWithDefaults(params)})"
        if allClauses.length <= 1 then
          val baseSig = clauseSig(allClauses.flatMap(_.values))
          // Append cb params to the signature if present
          val sig = if cbParamsStr.isEmpty then baseSig
                    else baseSig match
                      case s"($inner)" => s"($inner$cbParamsStr)"
                      case other       => s"($other$cbParamsStr)"
          decls += s"const $fname = $sig => $bodyJsRaw;"
        else
          val innerFn = allClauses.init.foldRight(clauseSig(allClauses.last.values) + s" => $bodyJsRaw") {
            (clause, inner) => clauseSig(clause.values) + s" => $inner"
          }
          decls += s"const $fname = $innerFn;"
        names += fname
      case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
        decls += s"const ${n.value} = ${genExpr(rhs)};"
        names += n.value
      case nested: Defn.Object if companionClassNames.contains(nested.name.value) =>
        val name = nested.name.value
        decls += s"Object.assign($name, ${genObjectAsExpr(nested, s"$path.$name")});"
        if !names.contains(name) then names += name
      case nested: Defn.Object =>
        decls += s"const ${nested.name.value} = ${genObjectAsExpr(nested, s"$path.${nested.name.value}")};"
        names += nested.name.value
      case d: Defn.Class =>
        val paramVals = d.ctor.paramClauses.flatMap(_.values)
        val params    = paramVals.map(_.name.value)
        val typeName  = d.name.value
        val paramsStr = paramListWithDefaults(paramVals)
        val fields    = params.map(p => s"$p: $p").mkString(", ")
        val tagField  = caseClassTagMap.get(typeName).map(t => s"_tag: $t, ").getOrElse("")
        decls += s"function $typeName($paramsStr) { return {_type: '$typeName', ${tagField}$fields}; }"
        decls += jsTypedJsonRegisterProduct(typeName, params, typeName)
        names += typeName
      case d: Defn.Enum =>
        val enumName = d.name.value
        val allCases = scala.collection.mutable.ListBuffer.empty[String]
        val nullary  = scala.collection.mutable.ListBuffer.empty[String]
        def emitNullary(caseName: String): Unit =
          val tagField = caseClassTagMap.get(caseName).map(t => s", _tag: $t").getOrElse("")
          decls += s"const $caseName = {_type: '$caseName'${tagField}};"
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
              val tagField  = caseClassTagMap.get(caseName).map(t => s"_tag: $t, ").getOrElse("")
              decls += s"function $caseName($paramsStr) { return {_type: '$caseName', ${tagField}$fields}; }"
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
      case eg: Defn.ExtensionGroup =>
        // Top-level extension groups inside packages register into _extensions (global).
        genStat(eg)
      case gd: Defn.Given =>
        // Extension groups inside givens register into _extensions (global side-effect).
        gd.templ.body.stats.foreach {
          case eg: Defn.ExtensionGroup => genStat(eg)
          case _                       => ()
        }
        // given intSum: Monoid[Int] with { ... } inside a package object:
        // define the instance object, register in _ssc_givens (and under parent TC keys too).
        gd.templ.inits.headOption.foreach { init =>
          def extractTcAndArg(tpe: Type): Option[(String, String)] = tpe match
            case Type.Name(n)  => Some(n -> n)
            case ta: Type.Apply =>
              ta.tpe match
                case Type.Name(tc) =>
                  val arg = ta.argClause.values match
                    case List(Type.Name(n))    => n
                    case List(ta2: Type.Apply) => ta2.tpe match { case Type.Name(n) => n; case _ => "_" }
                    case _                     => "_"
                  Some(tc -> arg)
                case _ => None
            case _ => None
          extractTcAndArg(init.tpe).foreach { (primaryTc, typeArg) =>
            val members = gd.templ.body.stats.collect { case dd: Defn.Def =>
              s"${dd.name.value}: ${genDefAsMethod(dd)}"
            }
            val obj = s"{${members.mkString(", ")}}"
            val explicitName = gd.name.value
            // Compute all typeclass keys: primary + parent TCs from tcParentMap (cross-block)
            def allTcKeys(tc: String): List[String] =
              tc :: tcParentMap.getOrElse(tc, Nil).flatMap(allTcKeys)
            val allKeys = allTcKeys(primaryTc).map(tc => s"${tc}_${typeArg}").distinct
            if explicitName.nonEmpty then
              decls += s"const $explicitName = $obj;"
              allKeys.foreach { key => decls += s"""_ssc_givens["$key"] = $explicitName;""" }
              names += explicitName
            else
              val primaryKey = s"${primaryTc}_${typeArg}"
              decls += s"const $primaryKey = $obj;"
              allKeys.foreach { key => decls += s"""_ssc_givens["$key"] = $primaryKey;""" }
              names += primaryKey
          }
        }
      case _ => ()
    }
    // Re-bind members declared by EARLIER sections of this same namespace (emitted as
    // separate `_ssc_mergeDeep` IIFEs) from the live namespace, so a later section's bare
    // references to them resolve. Skip members this section declares itself.
    val thisMembers = names.toSet
    val rebinds     = namespaceMembers.getOrElse(path, mutable.Set.empty[String])
                        .toList.filterNot(thisMembers.contains).sorted
    val rebindDecl  = if rebinds.isEmpty then "" else s"const { ${rebinds.mkString(", ")} } = $path; "
    namespaceMembers.getOrElseUpdate(path, mutable.Set.empty[String]) ++= thisMembers
    // Overloaded defs that share a JS name (e.g. std/http's `serve(port)` /
    // `serve(port, tls)`, and likewise serveAsync/httpGet/…) each emit a
    // `const NAME = …`; JS forbids re-declaring a `const`/`function` in the same
    // scope, so a duplicate crashed the whole bundle at parse time with
    // "Identifier 'NAME' has already been declared". Keep the FIRST declaration
    // per name — JS has no overloading and the extern shims for the duplicates are
    // identical — while non-declaring entries (registrations, `_ssc_mergeDeep`,
    // `_ssc_givens[…]`) always pass through. (js-namespace-dup-const-serve.)
    val seenDecl = mutable.Set.empty[String]
    def declaredName(s: String): Option[String] =
      val t = s.trim
      val kw = if t.startsWith("const ") then "const " else if t.startsWith("function ") then "function " else ""
      if kw.isEmpty then None
      else Some(t.stripPrefix(kw).takeWhile(c => c.isLetterOrDigit || c == '_' || c == '$'))
    val dedupedDecls = decls.filter { d =>
      declaredName(d) match
        case Some(nm) => seenDecl.add(nm)   // false when already declared → dropped
        case None     => true
    }
    val body = rebindDecl + dedupedDecls.mkString(" ")
    val ret  = names.distinct.mkString(", ")
    // A field-less `case object` must carry a `_type` discriminator (mirroring the
    // enum-nullary / case-class emission) so the user-level `==` operator — which
    // lowers to structural `_eq` — can tell distinct singletons apart. Without it a
    // field-less case object lowers to a bare `{}`, and `_eq` finds two empty records
    // with matching (undefined) `_type` equal, so EVERY field-less case object equals
    // every other (v1-js-scljet-readonly-leaf-depth, v1-js-scljet-shm-lock-divergence).
    // Pattern matching already keys on `._type === 'Name'`, so this is purely additive;
    // namespace/package/companion objects (no `Mod.Case`) keep their plain member record.
    val typeField =
      if d.mods.exists(_.isInstanceOf[Mod.Case]) then
        val tag = caseClassTagMap.get(objectName).map(t => s", _tag: $t").getOrElse("")
        val sep = if ret.isEmpty then "" else ", "
        s"_type: '$objectName'$tag$sep"
      else ""
    s"(() => { $body return { $typeField$ret }; })()"

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

  /** Mangle a (possibly symbolic) method/operator name into a valid JS
   *  identifier fragment. Alphanumeric names pass through unchanged; symbolic
   *  operators (`~`, `<~`, `++`, `|`, …) map each char to a `$word` token so the
   *  result is a legal JS identifier. Used for extension-function names — the
   *  runtime dispatch key keeps the original symbol, so this mapping only needs
   *  to be injective, not reversible. */
  private def jsSafeOpName(name: String): String =
    if name.forall(c => c.isLetterOrDigit || c == '_' || c == '$') then name
    else name.iterator.map {
      case '~' => "$tilde";  case '+' => "$plus";  case '-' => "$minus"
      case '*' => "$times";  case '/' => "$div";   case '%' => "$percent"
      case '<' => "$less";   case '>' => "$greater"; case '=' => "$eq"
      case '!' => "$bang";   case '&' => "$amp";    case '|' => "$bar"
      case '^' => "$up";     case ':' => "$colon";  case '#' => "$hash"
      case '@' => "$at";     case '?' => "$qmark";  case '\\' => "$bslash"
      case c if c.isLetterOrDigit || c == '_' || c == '$' => c.toString
      case c => "$u" + c.toInt.toHexString
    }.mkString

  private def paramRenameMap(params: Seq[String]): Map[String, String] =
    params.collect {
      case p if jsReservedWords.contains(p)      => p -> safeJsParam(p)
      case p if topLevelUserRenames.contains(p)  => p -> p
    }.toMap

  private[codegen] def localBindingName(name: String): String =
    safeJsParam(name)

  private[codegen] def localBindingRenames(names: Iterable[String]): Map[String, String] =
    names.iterator
      .filterNot(_ == "_")
      .map(name => name -> localBindingName(name))
      .filter { case (from, to) => from != to || topLevelUserRenames.contains(from) }
      .toMap

  private[codegen] def localBindingStmts(bindings: Iterable[(String, String)]): String =
    bindings.map { case (name, expr) => s"const ${localBindingName(name)} = $expr;" }.mkString(" ")

  private[codegen] def withLocalBindings[A](names: Iterable[String])(f: => A): A =
    withParamRenames(localBindingRenames(names))(f)

  private def formalWithDefault(p: Term.Param): String =
    val n = safeJsParam(p.name.value)
    p.default match
      case Some(d) => s"$n = ${genExpr(d)}"
      case None    => n

  // ─── Mutual TCO helpers ──────────────────────────────────────────

  // Emits _fname_impl (while-loop + _tailCall for mutual calls) and the public wrapper.
  private def genMutualTcoFun(d: Defn.Def, sourceName: String, jsName: String, params: List[String]): Unit =
    val implName = s"_${jsName}_impl"
    val friends  = mutualGroups(sourceName) - sourceName
    val renames  = paramRenameMap(params)
    val formals  = params.map(p => s"_$p").mkString(", ")
    val letDecls = "let " + params.map(p => s"${safeJsParam(p)} = _$p").mkString(", ")
    line(s"function $implName($formals) {")
    indent += 1
    line(s"$letDecls;")
    line("while(true) {")
    indent += 1
    withParamRenames(renames)(genMutualTcoBody(d.body, sourceName, params, friends))
    indent -= 1
    line("}")
    indent -= 1
    line("}")
    // Public wrapper that starts the trampoline
    val wrapArgs = params.map(p => s"_$p").mkString(", ")
    line(s"function $jsName($formals) { return _trampoline($implName, $wrapArgs); }")

  // Like genTcoBody but mutual tail calls return _tailCall thunks.
  private def genMutualTcoBody(term: Term, fname: String, params: List[String], friends: Set[String]): Unit =
    term match
      case Term.Apply.After_4_6_0(Term.Name(`fname`), argClause) =>
        val newArgs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
        if params.length == 1 then line(s"${params(0)} = ${newArgs(0)};")
        else
          val temps = newArgs.indices.map(i => s"_tco$i")
          line(s"const ${temps.zip(newArgs).map((t, v) => s"$t = $v").mkString(", ")};")
          params.zip(temps).foreach { (p, t) => line(s"$p = $t;") }
        line("continue;")
      case Term.Apply.After_4_6_0(Term.Name(n), argClause) if friends.contains(n) =>
        val newArgs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
        line(s"return _tailCall(_${emittedName(n)}_impl, ${newArgs.mkString(", ")});")
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
        // Use individual const temps to avoid temporary array allocation.
        // All RHS are evaluated before any LHS is written (cross-referencing is safe).
        val temps = newArgs.indices.map(i => s"_tco$i")
        line(s"const ${temps.zip(newArgs).map((t, v) => s"$t = $v").mkString(", ")};")
        params.zip(temps).foreach { (p, t) => line(s"$p = $t;") }
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
    case tm: Term.Match =>
      genMatchAsStmts(tm, t => genTcoBody(t, fname, params))
    case other =>
      line(s"return ${genExpr(other)};")

  // Returns true if term contains a call to fname NOT in tail position.

  private def genFunctionBody(stats: List[Stat]): Unit =
    if stats.isEmpty then
      line("return undefined;")
    else
      val outerVars: Set[String] = stats.collect {
        case Defn.Var.After_4_7_2(_, pats, _, _) =>
          pats.collect { case Pat.Var(n) => n.value }
      }.flatten.toSet
      stats.zipWithIndex.foreach { (s, i) =>
        val isLast = i == stats.length - 1
        s match
          case tw: Term.While =>
            val savedBuf  = loopHoistBuf
            val savedVars = loopOuterVars
            val newBuf    = mutable.Buffer.empty[(String, String)]
            loopHoistBuf = newBuf
            loopOuterVars = outerVars
            val body = genWhileBodyInline(tw.body)
            val cond = genExpr(tw.expr)
            loopHoistBuf = savedBuf
            loopOuterVars = savedVars
            newBuf.foreach { (k, v) => line(s"const $k = $v;") }
            line(s"while ($cond) { $body; }")
            if isLast then line("return undefined;")
          case tm: Term.Match if isLast =>
            genMatchAsStmts(tm, t => line(s"return ${genExpr(t)};"))
          case t: Term if isLast =>
            line(s"return ${genExpr(t)};")
          case t: Term =>
            line(genExpr(t) + ";")
          case stat =>
            genStat(stat)
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

  private[codegen] def genGeneratorBody(t: Term): String =
    s"function*() {\n${genGenStmt(t)}\n}"

  private[codegen] def extractStreamBody(arg: Term): String = arg match
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

  /** Scala-meta keeps `x += y` as an ApplyInfix, while the interpreter gives a
   *  mutable-name LHS assignment semantics inside blocks. Generator bodies have
   *  their own statement emitter, so route the base operation through the normal
   *  infix generator and write the result back instead of dispatching a method
   *  literally named `+=` on the current value. */
  private def genGeneratorCompoundAssign(t: Term.ApplyInfix): Option[String] = t match
    case Term.ApplyInfix.After_4_6_0(lhs: Term.Name, op, _, argClause)
        if op.value.lengthIs > 1 && op.value.last == '=' &&
           op.value != ">=" && op.value != "<=" && op.value != "!=" && op.value != "==" &&
           argClause.values.lengthCompare(1) == 0 =>
      val base = Term.ApplyInfix.After_4_6_0(
        lhs,
        Term.Name(op.value.init),
        Nil,
        argClause)
      Some(s"${emittedName(lhs.value)} = ${genExpr(base)}")
    case _ => None

  private def genGenStatItem(s: Stat): String = s match
    case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
      s"  const ${emittedName(n.value)} = ${genGenExpr(rhs)};"
    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      s"  let ${emittedName(n.value)} = ${genGenExpr(rhs)};"
    case Term.Assign(Term.Name(n), rhs) =>
      s"  ${emittedName(n)} = ${genGenExpr(rhs)};"
    case t: Term.ApplyInfix =>
      s"  ${genGeneratorCompoundAssign(t).getOrElse(genGenExpr(t))};"
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
        val (cond0, bindings) = genPattern(scrutVar, c.pat)
        val localNames = bindings.map(_._1)
        // jsgen-match-guard-bind: fold the case guard into the condition (bindings scoped in an IIFE).
        val cond = c.cond match
          case Some(g) =>
            val binds = localBindingStmts(bindings)
            val guardExpr = withLocalBindings(localNames)(genExpr(g))
            val guardIife = s"(() => { $binds return ($guardExpr); })()"
            if cond0 == "true" then guardIife else s"($cond0) && $guardIife"
          case None => cond0
        val bindingJs = if bindings.isEmpty then ""
          else bindings.map { case (n, e) => s"    const ${localBindingName(n)} = $e;" }.mkString("\n") + "\n"
        val bodyJs = withLocalBindings(localNames)(genGenStmt(c.body))
        val condStr = s"($cond) "
        s"  if $condStr{\n$bindingJs$bodyJs\n  }"
      }.mkString(" else ")
      s"  const $scrutVar = $scrutExpr;\n$casesJs"
    case Term.Throw(expr) =>
      expr match
        case Term.New(init) =>
          val errMsg = init.argClauses.headOption.flatMap(_.values.headOption)
            .map(v => genExpr(v.asInstanceOf[Term]))
            .getOrElse("'error'")
          s"  throw new Error($errMsg);"
        case Term.Apply.After_4_6_0(Term.Name("RuntimeException" | "Exception" | "Error"), argClause)
            if argClause.values.size == 1 =>
          val errMsg = genExpr(argClause.values.head.asInstanceOf[Term])
          s"  throw new Error($errMsg);"
        case _ =>
          s"  throw ${genGenExpr(expr)};"
    case t: Term => s"  ${genGenExpr(t)};"
    case _       => s"  ${genStatInline(s)}"

  private def genGenExpr(t: Term): String = t match
    case Term.Apply.After_4_6_0(Term.Name("suspend" | "emit"), argClause) if argClause.values.size == 1 =>
      s"(yield ${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Block(stats) => stats.map(genGenStatItem).mkString("\n")
    case _                 => genExpr(t)

  // Emits a Term.Match as an if-else chain of statements. Each case arm is emitted
  // with its bindings as const declarations followed by the arm body via `bodyEmit`.
  // This avoids the IIFE that genExpr(Term.Match) produces, eliminating arrow-fn
  // allocation + call overhead when the match is in a return/tail position.
  private def genMatchAsStmts(t: Term.Match, bodyEmit: Term => Unit): Unit =
    val scrutVar = freshTmp()
    line(s"const $scrutVar = ${genExpr(t.expr)};")
    val arms = t.casesBlock.cases.map { c =>
      val (cond0, bindings) = genPattern(scrutVar, c.pat)
      val localNames = bindings.map(_._1)
      // jsgen-match-guard-bind: fold a case GUARD (`case x if x < 0`) into the arm condition. The
      // guard references the pattern bindings, so scope them in an IIFE. Without this the guard was
      // dropped → a guarded `case x if …` looked like a catch-all mid-chain → malformed `} else if`.
      val cond = c.cond match
        case Some(g) =>
          val binds = localBindingStmts(bindings)
          val guardExpr = withLocalBindings(localNames)(genExpr(g))
          val guardIife = s"(() => { $binds return ($guardExpr); })()"
          if cond0 == "true" then guardIife else s"($cond0) && $guardIife"
        case None => cond0
      (cond, bindings, c.body.asInstanceOf[Term])
    }
    // If all non-wildcard arms are pure integer-tag checks, emit a switch for O(1) dispatch.
    val pureTagRx = java.util.regex.Pattern.compile(
      s"\\(${java.util.regex.Pattern.quote(scrutVar)} && ${java.util.regex.Pattern.quote(scrutVar)}\\._tag === (\\d+)\\)"
    )
    def tagOf(cond: String): Option[Int] =
      val m = pureTagRx.matcher(cond)
      if m.matches() then Some(m.group(1).toInt) else None
    val switchable = arms.forall { (cond, _, _) => cond == "true" || tagOf(cond).isDefined }
    if switchable && arms.nonEmpty then
      line(s"switch($scrutVar && ${scrutVar}._tag) {")
      indent += 1
      var hasDefault = false
      arms.foreach { (cond, bindings, body) =>
        val localNames = bindings.map(_._1)
        val label = if cond == "true" then { hasDefault = true; "default:" } else s"case ${tagOf(cond).get}:"
        line(s"$label {")
        indent += 1
        bindings.foreach { case (n, e) => line(s"const ${localBindingName(n)} = $e;") }
        withLocalBindings(localNames)(bodyEmit(body))
        line("break;")
        indent -= 1
        line("}")
      }
      if !hasDefault then
        line("default: {")
        indent += 1
        line(s"throw new Error('Match failure: ' + _show($scrutVar));")
        line("break;")
        indent -= 1
        line("}")
      indent -= 1
      line("}")
    else
      var lastWasWildcard = false
      arms.zipWithIndex.foreach { case ((cond, bindings, body), idx) =>
        val localNames = bindings.map(_._1)
        if cond == "true" then
          lastWasWildcard = true
          if idx > 0 then { indent -= 1; line("} else {"); indent += 1 }
          else { line("{"); indent += 1 }
        else
          lastWasWildcard = false
          val kw = if idx == 0 then "if" else "} else if"
          if idx > 0 then indent -= 1
          line(s"$kw ($cond) {")
          indent += 1
        bindings.foreach { case (n, e) => line(s"const ${localBindingName(n)} = $e;") }
        withLocalBindings(localNames)(bodyEmit(body))
      }
      indent -= 1
      if lastWasWildcard then line("}")
      else line(s"} else { throw new Error('Match failure: ' + _show($scrutVar)); }")

  // Inlines xs.foreach(p => body) as a flat for-loop when the callback is a
  // literal function — avoids closure allocation in hot loops.
  // Called from genWhileBodyInline so the result lands inline, never in an IIFE.
  private def inlineForeachOrGenStat(t: Term): String = t match
    case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foreach")), argClause)
         if argClause.values.length == 1 =>
      argClause.values.head match
        case Term.Function.After_4_6_0(paramClause, fnBody)
             if paramClause.values.length == 1 =>
          val param   = paramClause.values.head.name.value
          // Invariant-accumulation hoist: when the receiver is a loop-invariant
          // name and the body is `acc = acc + f(p)` with `acc` an outer var and
          // `f(p)` independent of any loop-mutated var, the inner sum is constant
          // across outer iterations.  Hoist it before the loop and replace the
          // foreach with `acc = acc + _sum` — the same O(1) strength reduction the
          // SSC and JVM backends apply.
          val hoisted: String | Null =
            if loopHoistBuf == null then null
            else (qual, fnBody) match
              case (Term.Name(stableName),
                    Term.Block(List(Term.Assign(Term.Name(accName),
                      Term.ApplyInfix.After_4_6_0(Term.Name(acc2), Term.Name("+"), _, ac)))))
                  if ac.values.length == 1
                    && accName == acc2
                    && loopOuterVars.contains(accName)
                    && !loopOuterVars.contains(stableName)
                    && !termRefsAny(ac.values.head.asInstanceOf[Term], loopOuterVars) =>
                val addend = ac.values.head.asInstanceOf[Term]
                // The addend lives inside the hoisted IIFE (a new scope), so it
                // must not itself be hoisted to the outer-loop preamble.
                val savedBuf = loopHoistBuf
                loopHoistBuf = null
                val addendJs = withLocalBindings(List(param))(genExpr(addend))
                val qualJs   = genExpr(qual)
                loopHoistBuf = savedBuf
                val sVar = freshTmp(); val xs = freshTmp(); val ix = freshTmp()
                val jsParam = localBindingName(param)
                val sumExpr =
                  s"(() => { let $sVar = 0; const $xs = $qualJs; if (Array.isArray($xs)) { for (let $ix = 0; $ix < $xs.length; $ix++) { const $jsParam = $xs[$ix]; $sVar += $addendJs; } } else { _forEach($xs, ($jsParam) => { $sVar += $addendJs; }); } return $sVar; })()"
                val sumName = freshHoistConst(sumExpr)
                s"$accName = $accName + $sumName"
              case _ => null
          if hoisted != null then hoisted
          else
            val qualJs  = genExpr(qual)
            val bodyStr = withLocalBindings(List(param))(genWhileBodyInline(fnBody)) // call once; reuse string in both branches
            val xsVar   = freshTmp()
            val idxVar  = freshTmp()
            val jsParam = localBindingName(param)
            s"const $xsVar = $qualJs; if (Array.isArray($xsVar)) { for (let $idxVar = 0; $idxVar < $xsVar.length; $idxVar++) { const $jsParam = $xsVar[$idxVar]; $bodyStr; } } else { _forEach($xsVar, ($jsParam) => { $bodyStr; }); }"
        case _ => genExpr(t)
    case _ => genExpr(t)

  // Emits while-body stats as a flat semicolon-separated string — no IIFE wrapper.
  private def genWhileBodyInline(body: Term): String = body match
    case Term.Block(stats) =>
      stats.map {
        // A nested `while` must stay a JS *statement* (`while (c) { … }`); routing
        // it through genExpr would wrap it in an IIFE created+invoked on every
        // outer iteration (capturing the accumulator by closure → V8 deopt).
        case tw: Term.While => genNestedWhileInline(tw)
        case t: Term        => inlineForeachOrGenStat(t)
        case stat           => genStatInline(stat)
      }.mkString("; ")
    case tw: Term.While => genNestedWhileInline(tw)
    case t => inlineForeachOrGenStat(t)

  /** A nested `while` as an inline JS statement (no IIFE). Mirrors the statement
   *  form in `genFunctionBody`/`genBlockAsIife`: inner loop-invariant hoists go to
   *  a fresh buffer emitted as `const` decls right before the loop. */
  private def genNestedWhileInline(tw: Term.While): String =
    val savedBuf = loopHoistBuf
    val newBuf   = mutable.Buffer.empty[(String, String)]
    loopHoistBuf = newBuf
    val twBody = genWhileBodyInline(tw.body)
    val twCond = genExpr(tw.expr)
    loopHoistBuf = savedBuf
    val hoists = newBuf.map { (k, v) => s"const $k = $v;" }.mkString(" ")
    val prefix = if hoists.nonEmpty then hoists + " " else ""
    s"$prefix while ($twCond) { $twBody; }"

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
            val savedBuf = loopHoistBuf
            val newBuf   = mutable.Buffer.empty[(String, String)]
            loopHoistBuf = newBuf
            val twBody = genWhileBodyInline(tw.body)
            val twCond = genExpr(tw.expr)
            loopHoistBuf = savedBuf
            newBuf.foreach { (k, v) => inner.append(pad).append(s"const $k = $v;\n") }
            val body = s"while ($twCond) { $twBody; }"
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

  private[codegen] def genStatInline(stat: Stat): String = stat match
    case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
      if !rebindNumericEvidence(n.value, rhs) && isTupleExpr(rhs) then tupleVars += n.value
      s"const ${emittedName(n.value)} = ${genExpr(rhs)};"
    // Destructuring val inside a block/IIFE: `val (left, right) = e`. Mirrors the
    // top-level genStat handler; without this it fell to the `/* stat */` default
    // and the pattern's binders silently vanished. (js-destructure-val-in-block.)
    case Defn.Val(_, List(pat), _, rhs) =>
      s"const ${genPatDestructure(pat)} = ${genExpr(rhs)};"
    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      rebindNumericEvidence(n.value, rhs)
      s"let ${emittedName(n.value)} = ${genExpr(rhs)};"
    case d: Defn.Def =>
      val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
      d.body match
        case expr =>
          s"function ${d.name.value}(${params.mkString(", ")}) { return ${genExpr(expr)}; }"
    case t: Term => genExpr(t) + ";"
    case _ => "/* stat */;"

  private[codegen] def genPatDestructure(pat: Pat): String = pat match
    case Pat.Tuple(pats) =>
      "[" + pats.map(p => p match
        case Pat.Var(n) => n.value
        // A `_` wildcard is a discard — emit a FRESH unique name, not the literal
        // `_`. Two `val (a, _) = …` at the same scope both bound `const _` → JS
        // "Identifier '_' has already been declared". (js-wildcard-destructure-dup.)
        case Pat.Wildcard() => freshTmp()
        case inner => genPatDestructure(inner)
      ).mkString(", ") + "]"
    case Pat.Var(n) => n.value
    case Pat.Wildcard() => freshTmp()
    case _ => freshTmp()

  private[codegen] def patternNames(pat: Pat): List[String] = pat match
    case Pat.Var(n) => List(n.value)
    case Pat.Tuple(pats) => pats.toList.flatMap(patternNames)
    case Pat.Typed(inner, _) => patternNames(inner)
    case Pat.Bind(lhs, rhs) => patternNames(lhs) ++ patternNames(rhs)
    case Pat.Extract.After_4_6_0(_, argClause) => argClause.values.toList.flatMap(patternNames)
    case _ => Nil

  private def isEffectOpDef(body: Term): Boolean =
    scalascript.transform.EffectAnalysis.isEffectOpDef(body)

  /** Emits a JS matcher closure for a `receive { case … }` block.
   *  The closure takes the next mailbox message and returns either
   *  `{ matched: false }` or `{ matched: true, body: () => <computation> }`.
   *  Case bodies are CPS-emitted so any nested Actor / Async / handle
   *  effects compose into the actor's pending Computation. */
  private[codegen] def genReceiveMatcher(cases: List[Case]): String =
    val scrut = "__rcv_msg__"
    val chain = cases.map { c =>
      val (cond, bindings) = genPattern(scrut, c.pat)
      val localNames = bindings.map(_._1)
      val bindStmts = localBindingStmts(bindings)
      val bodyCps   = withLocalBindings(localNames)(genCpsExpr(c.body))
      val condFinal = c.cond match
        case Some(g) =>
          // The guard references the pattern bindings — scope them in an IIFE (they aren't yet
          // declared at the `if` condition). (jsgen-match-guard-bind.)
          val guardExpr = withLocalBindings(localNames)(genExpr(g))
          val guardIife = s"(() => { $bindStmts return ($guardExpr); })()"
          if cond == "true" then guardIife else s"($cond) && $guardIife"
        case None => if cond == "true" then "true" else s"($cond)"
      s"if ($condFinal) { $bindStmts return { matched: true, body: () => $bodyCps }; }"
    }.mkString(" ")
    s"($scrut) => { $chain return { matched: false }; }"

  private[codegen] def genHandleForm(body: Term, cases: List[Case]): String =
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
          val paramsStr = s"[${paramNames.map(localBindingName).mkString(", ")}]"
          // Handler case bodies stay direct: they receive `resume` (a plain
          // function returning the value of the resumed branch) and compose it
          // with regular JS. Effects inside case bodies are uncommon and would
          // need their own handle.
          val bodyJs = c.body match
            case Term.Block(stats) =>
              val stmts = withLocalBindings(paramNames) {
                stats.dropRight(1).map {
                  case t: Term => genExpr(t) + ";"
                  case s       => genStatInline(s)
                }.mkString(" ")
              }
              val last = withLocalBindings(paramNames) {
                stats.lastOption.map {
                  case t: Term => s"return ${genExpr(t)};"
                  case _       => ""
                }.getOrElse("")
              }
              s"($paramsStr) => { $stmts $last }"
            case expr =>
              val exprJs = withLocalBindings(paramNames)(genExpr(expr))
              s"($paramsStr) => $exprJs"
          Some(s"'$eff.$op': $bodyJs")
        case _ => None
    }
    // The handle body is always emitted in CPS form so that effect ops build
    // a Free tree which _handle can interpret.
    val bodyThunk = s"() => ${genCpsExpr(body)}"
    // Use _handleOneShot when no op belongs to a multi-shot effect.
    // Also detect implicit multi-shot: if `resume` appears inside a nested lambda
    // in any handler body, the effect is being used multi-shot regardless of declaration.
    def resumeInLambda(caseBody: Term, resumeParam: String): Boolean =
      def check(t: scala.meta.Tree, depth: Int): Boolean = t match
        case Term.Name(n) if n == resumeParam => depth > 0
        case _: Term.Function           => t.children.exists(check(_, depth + 1))
        case _: Term.AnonymousFunction  => t.children.exists(check(_, depth + 1))
        case _                          => t.children.exists(check(_, depth))
      check(caseBody, 0)
    val allOneShot = handledOps.forall { opStr =>
      val effName = opStr.stripPrefix("'").takeWhile(_ != '.')
      if multiShotEffects.contains(effName) then false
      else
        // Check if any case for this effect uses resume inside a lambda
        val resumeUsedMultiShot = cases.exists { c =>
          c.pat match
            case Pat.Extract.After_4_6_0(Term.Select(Term.Name(eff), _), argClause) if eff == effName =>
              val resumeName = argClause.values.lastOption match
                case Some(Pat.Var(n)) => n.value
                case _                => ""
              resumeName.nonEmpty && resumeInLambda(c.body, resumeName)
            case _ => false
        }
        !resumeUsedMultiShot
    }
    val handleFn = if allOneShot then "_handleOneShot" else "_handle"
    // Optional return clause: `case Return(x) => expr` (unqualified `Return`, vs the
    // qualified `Eff.op` effect cases — those go through handledOps/handlerEntries,
    // which already skip `Return` since it's not a `Term.Select`). When present,
    // emit a retMap and route through _handleWithReturn so the body's pure
    // completion (and each resumed continuation) maps through it.
    val returnCase = cases.find { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(Term.Name("Return"), _) => true
        case _                                               => false
    }
    def caseBodyStmts(body: Term): String = body match
      case Term.Block(stats) =>
        val stmts = stats.dropRight(1).map {
          case t: Term => genExpr(t) + ";"
          case s       => genStatInline(s)
        }.mkString(" ")
        val last = stats.lastOption.map {
          case t: Term => s"return ${genExpr(t)};"
          case _       => ""
        }.getOrElse("")
        s"$stmts $last"
      case expr => s"return ${genExpr(expr)};"
    returnCase match
      case None =>
        s"$handleFn($bodyThunk, [${handledOps.mkString(", ")}], {${handlerEntries.mkString(", ")}})"
      case Some(c) =>
        val binder = c.pat match
          case Pat.Extract.After_4_6_0(_, ac) if ac.values.nonEmpty =>
            ac.values.head match
              case Pat.Var(n) => Some(n.value)
              case _          => None
          case _ => None
        val decl   = binder.map(n => s"const ${localBindingName(n)} = _rv; ").getOrElse("")
        val retBody = withLocalBindings(binder.toList)(caseBodyStmts(c.body))
        val retMap = s"(_rv) => { $decl$retBody }"
        s"_handleWithReturn($bodyThunk, [${handledOps.mkString(", ")}], {${handlerEntries.mkString(", ")}}, $retMap)"

  /** Stage 5+/A.5 — per-call-site intrinsic dispatch.  Returns the
   *  JS expression string to splice in, or `None` if no intrinsic
   *  claims this name.  Called from `genExpr` for Term.Apply
   *  (Term.Name(fname), args) sites BEFORE the existing hardcoded
   *  pattern matches, so a registered intrinsic always wins. */
  private def dispatchIntrinsicJs(fname: String, argClause: Term.ArgClause): Option[String] =
    // If the name has been shadowed by an explicit import/user binding, skip
    // the intrinsic so the local binding takes precedence. Collision-renamed
    // imports such as std.ui.primitives.serve bind as `serve__ssc`, so checking
    // only `fname` would let the HTTP `serve` intrinsic steal the call.
    if declaredBindings.contains(fname) ||
        declaredBindings.contains(emittedName(fname)) ||
        topLevelUserRenames.contains(fname)
    then return None
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

  /** The JS runtime target for a `RuntimeCall` intrinsic named `fname`
   *  (e.g. `nowMillis` → `Date.now`), or `None` if `fname` is not such an
   *  intrinsic (or is shadowed by a local binding).  `dispatchIntrinsicJs`
   *  applies this for `Term.Apply` sites in `genExpr`; the CPS path
   *  (`genCpsApply`) needs the same rewrite, but it binds args itself, so it
   *  only needs the target string — not the full dispatch.  Without it, a
   *  `nowMillis()` call inside an effectful (CPS-lowered) function emits the
   *  bare source name, which is undefined in a standalone `emit-js` bundle. */
  private[codegen] def intrinsicRuntimeTarget(fname: String): Option[String] =
    if declaredBindings.contains(fname) then None
    else intrinsics.get(scalascript.ir.QualifiedName(fname)).collect {
      case scalascript.backend.spi.RuntimeCall(target) => target
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

    // System.currentTimeMillis() → Date.now()  (milliseconds since epoch, same semantics)
    case Term.Apply.After_4_6_0(
          Term.Select(Term.Name("System"), Term.Name("currentTimeMillis")), _) =>
      "Date.now()"

    // System.nanoTime() → nanoseconds via performance.now() (μs precision in Node/browsers)
    case Term.Apply.After_4_6_0(
          Term.Select(Term.Name("System"), Term.Name("nanoTime")), _) =>
      "Math.round(performance.now() * 1e6)"

    // Stage 5+/B.3 — qualified intrinsic dispatch for `Obj.method(args)`.
    case Term.Apply.After_4_6_0(Term.Select(Term.Name(obj), Term.Name(method)), argClause)
        if dispatchIntrinsicJs(s"$obj.$method", argClause).isDefined =>
      dispatchIntrinsicJs(s"$obj.$method", argClause).get

    // Literals
    case Lit.Int(v)     => v.toString
    // v1-js-long-precision-and-bitops: a `Long` literal is emitted as a JS BigInt
    // (`123n`) so 64-bit values above 2^53 keep full precision (a plain JS number
    // rounds them at parse time) and Long arithmetic/bit-ops route through the
    // runtime's exact BigInt paths. Int stays a JS number.
    case Lit.Long(v)    => s"${v}n"
    case Lit.Double(v)  => v.toString
    case Lit.Float(v)   => v.toString
    case Lit.String(v)  =>
      // Escape for JS string literal
      "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
    case Lit.Boolean(v) => v.toString
    case Lit.Char(v)    => "\"" + v.toString.replace("\"", "\\\"") + "\""
    case Lit.Unit()     => "undefined"
    case Lit.Null()     => "null"

    // A parenless `def f: T = …` is re-evaluated on every reference (Scala
    // semantics), so a BARE reference to it — as a value or argument, NOT a call
    // (genApply handles `f(x)` → `f()(x)`) — must invoke it: `f` → `f()`. Without
    // this, `useIt(mkAdd)` passed the function object `mkAdd` itself instead of its
    // value, so `f(10)` returned a function. `zeroParamFns` holds only top-level
    // parenless defs (vals / multi-param defs are unaffected); the paramRenames
    // guard skips a renamed local that shadows the name. (js-parenless-def-value.)
    case Term.Name(name) if zeroParamFns(name) && !paramRenames.contains(name) =>
      s"${mapName(name)}()"

    // Scala Predef `???` — a placeholder that throws NotImplementedError when
    // evaluated. Emitting the literal `???` is a JS SyntaxError (fails to parse
    // the whole file even when the branch is never taken), so lower it to an IIFE
    // that throws only when the expression is actually reached.
    case Term.Name("???") =>
      "((()=>{ throw new Error('scala.NotImplementedError: an implementation is missing'); })())"

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
      // f"…" semantics: the literal following each ${} may start with a Java
      // printf-style format spec applied to the PRECEDING arg (same grammar the
      // interpreter uses). The spec for arg(i) lives at the start of parts(i+1);
      // we look ahead for it, wrap the arg in `_fmtSpec`, and strip it from the
      // literal text when that part is emitted. (js-f-interp-format-spec.)
      val fmtRe = "^%[-+# 0,(]*\\d*(?:\\.\\d+)?[bBhHsScCdoxXeEfgGaAtT%]".r
      def fSpecAt(i: Int): Option[String] =
        if prefix == "f" && i >= 0 && i < parts.length then
          fmtRe.findFirstIn(parts(i).asInstanceOf[Lit.String].value)
        else None
      val sb2 = StringBuilder()
      sb2.append("`")
      for i <- parts.indices do
        val partRaw = parts(i).asInstanceOf[Lit.String].value
        // Drop a spec belonging to the previous arg (it was consumed there).
        val part = fSpecAt(i) match
          case Some(spec) if i > 0 => partRaw.substring(spec.length)
          case _                   => partRaw
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
            else fSpecAt(i + 1) match
              case Some(spec) => s"""_fmtSpec(${jsQuote(spec)}, $argJs)"""
              case None       => s"_show($argJs)"
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
          val partsJs = partStrs.map(JsGenStringUtils.jsStringLit)
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
      // An effectful lambda (its body performs an effect or calls an effectful fun)
      // must produce a Free computation, not a direct value — otherwise an effect-
      // performing call in the body (e.g. a handler-body thunk `() => query(...)`)
      // gets `_run`-wrapped and throws "Unhandled effect" before the enclosing
      // handler can catch it. Emit such a body via the CPS path so the lambda
      // returns the Free value for the handler / `_bind` to interpret. (Lambdas in
      // an already-CPS context go through genCpsExpr; this covers the non-CPS ones.)
      val bodyJs = withLocalBindings(params) {
        if jsForTermPerforms(body) then body match
          case Term.Block(stats) => genCpsBlockAsIife(stats)
          case expr              => genCpsExpr(expr)
        else body match
          case Term.Block(stats) => genBlockAsIife(stats)
          case expr              => genExpr(expr)
      }
      val jsParams = params.map(localBindingName)
      if jsParams.length == 1 then s"${jsParams.head} => $bodyJs"
      else
        // Auto-tuple: when this lambda is passed somewhere that supplies a
        // single tuple-arg (e.g. `pairs.foreach((n, s) => ...)`, where the
        // callback receives one `[n, s]` array), destructure on entry.
        val arity   = jsParams.length
        val joined  = jsParams.mkString(", ")
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
      if loopHoistBuf != null && elems.forall(isLiteralTerm) then
        freshHoistConst(s"Object.freeze(Object.assign([$elemsJs], {_isTuple: true}))")
      else
        s"Object.assign([$elemsJs], {_isTuple: true})"

    // Assignment
    // `recv(idx) = rhs` — indexed assignment (Scala desugars to `recv.update(idx, rhs)`). For a JS array
    // backing this is a plain element write. Single-index only (the common Array case). (collection-real-type.)
    case Term.Assign(Term.Apply.After_4_6_0(recv, ac), rhs) if ac.values.lengthCompare(1) == 0 =>
      s"${genExpr(recv)}[${genExpr(ac.values.head)}] = ${genExpr(rhs)}"

    case Term.Assign(lhs, rhs) =>
      s"${genExpr(lhs)} = ${genExpr(rhs)}"

    // While
    case t: Term.While =>
      val savedBuf = loopHoistBuf
      val newBuf   = mutable.Buffer.empty[(String, String)]
      loopHoistBuf = newBuf
      val bodyJs = genWhileBodyInline(t.body)
      val condJs = genExpr(t.expr)
      loopHoistBuf = savedBuf
      val hoistJs = newBuf.map((k, v) => s"const $k = $v;").mkString(" ")
      if hoistJs.isEmpty then s"(() => { while ($condJs) { $bodyJs; } })()"
      else s"(() => { $hoistJs while ($condJs) { $bodyJs; } })()"

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
              // arch-meta-v2-p5 (A2): `Mirror.Of[T]` / `ProductOf` / `SumOf`
              // (a Type.Select) maps to the registered `Mirror_T` key.
              val tc = ta.tpe match
                case n: Type.Name => n.value
                // `Type.Select`'s qualifier is a `Term.Ref` (`Term.Name`), NOT a
                // `Type.Name` — the old `Type.Name("Mirror")` pattern never matched,
                // so `Mirror.Of[T]` fell to the "?" fallback and emitted `?_T`.
                case Type.Select(Term.Name("Mirror"), Type.Name(of))
                    if of == "Of" || of == "ProductOf" || of == "SumOf" => "Mirror"
                case _ => "?"
              val arg = ta.argClause.values match { case List(n: Type.Name) => n.value; case _ => "_" }
              s"${tc}_${arg}"
            case _ => "undefined"
          // arch-meta-v2-p5 (A2): synthesized givens (per-type Mirror + custom
          // `derives`) live in `_ssc_givens` (the Mirror eagerly, derives lazily)
          // — resolve them through the registry.  Explicit user givens keep the
          // bare-name resolution.
          if jsSyntheticGivenKeys.contains(key) then s"""_resolveGiven(${jsQuote(key)})"""
          else cbSummonMap.getOrElse(key, key)
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
    // js-collection-perf: fused `LazyList.from(s).map(f)?.take(n).sum` → native loop IIFE (no _lz thunks).
    case Term.Select(qual, Term.Name("sum")) if lazyFromMapTakeJs(qual).isDefined =>
      genLazySumFused(lazyFromMapTakeJs(qual).get)

    // Field/method selection without arguments
    case Term.Select(qual, name) =>
      val qualJs = genExpr(qual)
      name.value match
        // js-collection-perf: numeric-conversion no-ops on a provably-numeric receiver lower to
        // native JS (matching the runtime: `.toInt`/`.toLong` → Math.trunc, `.toDouble` → identity)
        // instead of the megamorphic `_dispatch(x, 'toInt', [])`. Gated on isNumericExpr so a String
        // receiver (`s.toInt` → parseInt) still routes through _dispatch.
        // `.toInt` on an INTEGER-typed receiver → `(x | 0)` (ToInt32). This matches Scala's 32-bit
        // `Int`/`Long.toInt` wrap (which `Math.trunc` does NOT — `Math.trunc(3e9)` stays 3e9 while
        // Scala wraps), AND forces a V8 int32 so an array indexed/filled by it stays SMI-packed
        // instead of falling to the slow double-elements path (~2.4× on array-update). A Double
        // receiver keeps `Math.trunc` (truncate toward zero).
        // v1-js-long-precision-and-bitops: the receiver may be a BigInt (a Long
        // value — and not every Long is statically provable, e.g. a case-class
        // field bound by a pattern), and `bigint | 0` throws in JS. `_toI32` does
        // the 32-bit Int wrap for both a plain number (`x | 0`) and a BigInt, so
        // it is used unconditionally for an integer receiver.
        case "toInt" if isIntExpr(qual)                => s"_toI32($qualJs)"
        case "toInt" if isNumericExpr(qual)            => s"Math.trunc($qualJs)"
        // `.toLong` on an integer receiver is identity (already integral, no 32-bit wrap — Long is
        // 64-bit); a Double truncates toward zero.
        case "toLong" if isIntExpr(qual)               => s"($qualJs)"
        case "toLong" if isNumericExpr(qual)           => s"Math.trunc($qualJs)"
        case "toDouble" if isNumericExpr(qual)         => s"($qualJs)"
        // `t._N` on a statically-known tuple → a direct `t[N-1]` array read, skipping the
        // megamorphic `_dispatch(t, '_N', [])` (a function call + type switch per access).
        // This was a hot-loop cost in tuple-monoid. Case classes never match `isTupleExpr`,
        // so their Product `._N` falls through to the runtime dispatch below.
        case "_1" | "_2" | "_3" | "_4" if isTupleExpr(qual) =>
          s"$qualJs[${name.value.drop(1).toInt - 1}]"
        // Built-in collection/string methods that need runtime dispatch (computed properties)
        case "head" | "tail" | "last" | "init" | "reverse" | "distinct" | "sorted" |
             "toList" | "toSet" | "sum" | "min" | "max" | "flatten" | "isEmpty" |
             "nonEmpty" | "size" | "length" | "keys" | "values" | "isDefined" |
             "toUpperCase" | "toLowerCase" | "trim" | "toInt" | "toDouble" | "toLong" |
             "abs" | "round" | "floor" | "ceil" | "zipWithIndex" | "nonEmpty" |
             "_1" | "_2" | "_3" | "_4" =>
          s"_dispatch($qualJs, '${name.value}', [])"
        case other =>
          // Direct property read when the receiver is a statically-known
          // case-class instance and `other` is one of its declared fields:
          // `v.x` → `v.x` instead of the megamorphic `_dispatch(v, 'x', [])`.
          val directField = qual match
            case Term.Name(v) =>
              instanceVars.get(v)
                .flatMap(caseClassFieldsByType.get)
                .filter(_.contains(other))
                .map(_ => s"$qualJs.$other")
            case _ => None
          directField.getOrElse(s"_dispatch($qualJs, '$other', [])")

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

    // v1.4 effect runners — body must be wrapped in a thunk so that effect ops
    // build a Free tree which the runner's _handle/_handleOneShot can interpret.
    // Simple runners: runF(body) → runF(() => cps(body))
    // Exception: runStream uses a side-channel buffer (Stream.emit pushes directly)
    // so while/var loops work without a CPS trampoline.  Body is emitted as plain
    // JS (genExpr) so mutable vars and while loops are preserved correctly.
    case Term.Apply.After_4_6_0(Term.Name("runStream"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val bodyJs = genExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"runStream(() => $bodyJs)"
    case Term.Apply.After_4_6_0(Term.Name(runner), bodyArgClause)
        if bodyArgClause.values.size == 1 &&
           Set("runLogger","runLoggerJson","runLoggerToList",
               "runRandom","runClock","runEnv","runHttp",
               "runRetry","runRetryNoSleep",
               "runCache","runCacheBypass","runTx").contains(runner) =>
      val bodyJs = genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"$runner(() => $bodyJs)"
    // Curried runners: runF(arg)(body) → runF(arg)(() => cps(body))
    case Term.Apply.After_4_6_0(
          Term.Apply.After_4_6_0(Term.Name(runner), argClause),
          bodyArgClause)
        if bodyArgClause.values.size == 1 &&
           Set("runRandomSeeded","runClockAt","runEnvWith",
               "runState","runAuthWith","runHttpStub").contains(runner) =>
      val argJs  = argClause.values.map(v => genExpr(v.asInstanceOf[Term])).mkString(", ")
      val bodyJs = genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"$runner($argJs)(() => $bodyJs)"

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
      s"_makeAsyncStream((async function*(g) { for await (const v of { [Symbol.asyncIterator]() { return { async next() { const r = g.next(); return (r && r._type === '_None') ? { done: true } : { done: false, value: r.value }; } }; } }) yield v; })($gen))"
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

    // httpClient(url) { block } — block must be a lazy thunk so the
    // base-URL ThreadLocal is set before the block executes.  Without this
    // special case the outer apply compiles as _call(httpClient(url), body()),
    // which evaluates httpClient(url) with no block argument → TypeError.
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("httpClient"), urlArgClause),
        blockArgClause)
        if blockArgClause.values.size == 1 =>
      val urlJs   = genExpr(urlArgClause.values.head.asInstanceOf[Term])
      val blockJs = genExpr(blockArgClause.values.head.asInstanceOf[Term])
      s"httpClient($urlJs, () => $blockJs)"

    // Function application
    case app: Term.Apply =>
      genApply(app)

    // Infix
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val args = argClause.values
      // Constant folding: both operands are compile-time literals
      val constResult =
        if args.length == 1 then foldConstant(lhs, op.value, args.head) else None
      // js-codegen-opt-p2: constant-tuple-concat hoisting.
      // When inside a while loop (loopHoistBuf set) and both sides are all-literal, compile-time
      // fold the concat into a single frozen array hoisted before the loop.
      val tupleHoist: Option[String] =
        if (op.value == "++" || op.value == ":::") && loopHoistBuf != null && isConstantTupleExpr(lhs) &&
           args.asInstanceOf[List[Term]].forall(e => isLiteralTerm(e) || isConstantTupleExpr(e)) then
          val elems = collectConstantTupleElems(lhs) ++ args.asInstanceOf[List[Term]].flatMap {
            case te: Term.Tuple => collectConstantTupleElems(te)
            case other          => List(genExpr(other))
          }
          Some(freshHoistConst(s"Object.freeze(Object.assign([${elems.mkString(", ")}], {_isTuple: true}))"))
        else None
      tupleHoist.orElse(constResult).getOrElse {
        val lhsJs = genExpr(lhs)
        val rhsJs = if args.length == 1 then genExpr(args.head) else args.map(genExpr).mkString(", ")
        op.value match
          case "::" => s"[${genExpr(lhs)}, ...(${genExpr(args.head)})]"
          case ":+" => s"[...($lhsJs), ${genExpr(args.head)}]"
          case "+:" => s"[${genExpr(lhs)}, ...(${genExpr(args.head)})]"
          case "++" | ":::" =>
            // A tuple-LITERAL concat (`(a, b) ++ (c, d)`) flattens into ONE array literal
            // instead of `_tupleConcat(Object.assign(..), Object.assign(..))` (3 allocations).
            // Semantically identical (the result is the same flat `_isTuple` array), but the
            // single allocation matters in a hot loop (tuple-monoid bench). Element exprs may
            // be variable — only the tuple SHAPE on both sides needs to be statically known.
            // Scalameta parses `a ++ (b, c)` as multiple infix args [b, c], not one Tuple.
            val rhsTupleElems: Option[List[Term]] = args match
              case List(t: Term.Tuple)      => Some(t.args)
              case multi if multi.sizeIs > 1 => Some(multi)
              case _                         => None
            (lhs, rhsTupleElems) match
              case (lt: Term.Tuple, Some(relems)) =>
                val all = (lt.args ++ relems).map(genExpr).mkString(", ")
                s"Object.assign([$all], {_isTuple: true})"
              case _ =>
                val rhsArgJs = args match
                  case List(single) => genExpr(single)
                  case multi        => s"Object.assign([${multi.map(a => genExpr(a.asInstanceOf[Term])).mkString(", ")}], {_isTuple: true})"
                s"_tupleConcat($lhsJs, $rhsArgJs)"
          // HTML DSL: `attr.cls := "hero"` builds an Attr object.
          case ":=" => s"_attr($lhsJs, $rhsJs)"
          // v1.6 actors: `pid ! msg` enqueues into the receiver's mailbox.
          case "!" => s"Actor.send($lhsJs, $rhsJs)"
          case "->" =>
            s"Object.assign([$lhsJs, $rhsJs], {_isTuple: true})"
          // v1-js-long-precision-and-bitops: any arithmetic/comparison touching a
          // Long operand (a JS BigInt) must go through the BigInt-aware `_arith`,
          // which coerces an Int/Number operand — a native JS operator would mix
          // BigInt with Number and throw. Placed before the Int/Double fast paths.
          case "+" | "-" | "*" | "/" | "%" | "<" | ">" | "<=" | ">=" | "==" | "!="
              if isLongExpr(lhs) || args.headOption.exists(isLongExpr) =>
            s"_arith('${op.value}', $lhsJs, $rhsJs)"
          case "*" =>
            val rIsNum = args.headOption.exists(isNumericExpr)
            if isIntExpr(lhs) && args.headOption.exists(isIntExpr) then s"($lhsJs * $rhsJs)"
            else if isNumericExpr(lhs) && rIsNum then s"($lhsJs * $rhsJs)"
            else s"(typeof ($lhsJs) === 'string' ? ($lhsJs).repeat($rhsJs) : _arith('*', $lhsJs, $rhsJs))"
          // Exact numerics: value-based `==` for Decimal/BigInt when operands
          // aren't both statically Int (then native === is correct and faster).
          case "==" if isNumericExpr(lhs) && args.headOption.exists(isNumericExpr) => s"($lhsJs === $rhsJs)"
          case "!=" if isNumericExpr(lhs) && args.headOption.exists(isNumericExpr) => s"($lhsJs !== $rhsJs)"
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
          case "by" =>
            // `(n to m) by step` → re-step the materialized range array. (xbackend-range-by-step.)
            s"_dispatch($lhsJs, 'by', [$rhsJs])"
          case "/" if isIntExpr(lhs) && args.headOption.exists(isIntExpr) =>
            s"Math.trunc($lhsJs / $rhsJs)"
          // Exact numerics (v1.64): when operands aren't both statically Int,
          // route arithmetic/comparison through _arith so BigInt/Decimal work
          // (native JS `+` throws on BigInt+Number and can't add Decimal objects).
          // `+` keeps string-concat semantics (handled inside _arith's number path).
          // When both sides are provably numeric (Double/Float/Int/Long), use JS operators directly.
          case "+" | "-" | "/" | "%" | "<" | ">" | "<=" | ">="
              if !(isIntExpr(lhs) && args.headOption.exists(isIntExpr)) =>
            if isNumericExpr(lhs) && args.headOption.exists(isNumericExpr) then s"($lhsJs ${op.value} $rhsJs)"
            else s"_arith('${op.value}', $lhsJs, $rhsJs)"
          // v1-js-long-precision-and-bitops: bitwise/shift operators. Native JS
          // `& | ^ << >> >>>` are 32-bit (ToInt32/ToUint32, shift count mod 32),
          // but ssc `Int`/`Long` are 64-bit. Route through the runtime `_bit`
          // helper which coerces both operands to BigInt, applies the op, and
          // masks to signed 64 bits — matching the interpreter/JVM exactly.
          case "&" | "|" | "^" | "<<" | ">>" | ">>>" =>
            s"_bit('${op.value}', $lhsJs, $rhsJs)"
          // A user-defined SYMBOLIC infix operator (`~>`, `<~`, `~`, `<*>`, … —
          // parser-combinator / applicative DSLs) is a method call in ssc:
          // `a ~> b` ≡ `a.~>(b)`. Emitting it as a raw JS operator `(a ~> b)` is
          // a parse-time SyntaxError. Route it through `_dispatch`, which consults
          // the `_extensions` registry keyed on the raw symbol (see `_registerExt`).
          // The native arithmetic/comparison operators fall here too (their both-Int
          // fast path emits a raw JS operator), so they are excluded — only genuine
          // user operators dispatch. Alphanumeric infix keeps the raw lowering.
          // (js-symbolic-infix-op.)
          case other if !JsGen.nativeInfixOps.contains(other) &&
                        other.exists(c => !(c.isLetterOrDigit || c == '_' || c == '$')) =>
            s"_dispatch($lhsJs, '$other', [$rhsJs])"
          case other => s"($lhsJs $other $rhsJs)"
      }

    // Prefix unary operators: `!x`, `-x`, `+x`, `~x`.
    case t: Term.ApplyUnary =>
      // Constant folding for literal operands
      (t.op.value, t.arg) match
        case ("-", Lit.Int(n))     => (-n).toString
        case ("-", Lit.Long(n))    => s"${-n}n"
        case ("-", Lit.Double(ns)) => (-ns.toDouble).toString
        case ("+", Lit.Int(n))     => n.toString
        case ("+", Lit.Long(n))    => s"${n}n"
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
      expr match
        case Term.New(init) =>
          val errMsg = init.argClauses.headOption.flatMap(_.values.headOption)
            .map(v => genExpr(v.asInstanceOf[Term]))
            .getOrElse("'error'")
          s"(() => { throw new Error($errMsg); })()"
        case Term.Apply.After_4_6_0(Term.Name("RuntimeException" | "Exception" | "Error"), argClause)
            if argClause.values.size == 1 =>
          val errMsg = genExpr(argClause.values.head.asInstanceOf[Term])
          s"(() => { throw new Error($errMsg); })()"
        case _ =>
          // User-defined throwable (e.g. McpError("msg")): throw the value directly so
          // catch clauses can match on _type rather than instanceof Error.
          s"(() => { throw ${genExpr(expr)}; })()"

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

  /** A call to an effectful function in a NON-CPS (direct) context produces a lazy
   *  `_FlatMap` on JS (JS `_bind` is always lazy, unlike JVM's eager-on-Pure `_bind`),
   *  which the direct context cannot bind — so its result must be RUN to get a value
   *  (e.g. a self-handling fn used as `println(workload())`, which otherwise prints
   *  `[object Object]`). `_run` is idempotent on an already-resolved plain value (so
   *  wrapping a direct-runner result like `_handleOneShot(…)` is harmless) and throws
   *  loudly on an unhandled effect (an invalid program). CPS-context calls go through
   *  `genCpsApply`, never here, so they are unaffected. See BUGS.md. */
  private def runIfEffectful(name: String, call: String): String =
    if isEffectfulFun(name) then s"_run($call)" else call

  private def genApply(app: Term.Apply): String =
    // f(regular)(using tc) — flatten all curried arg lists when the outermost
    // Apply carries a `using` clause, so the JS call passes all args at once.
    if app.argClause.mod.nonEmpty then
      def collectAllArgs(t: Term, acc: List[Term]): (Term, List[Term]) = t match
        case inner: Term.Apply => collectAllArgs(inner.fun, inner.argClause.values ++ acc)
        case other             => (other, acc)
      val (baseFun, allArgs) = collectAllArgs(app.fun, app.argClause.values)
      return s"_call(${genExpr(baseFun)}, ${allArgs.map(genExpr).mkString(", ")})"

    // f(a)(b) where f is a user-defined function with multiple explicit param groups:
    // flatten to _call(f, a, b) so the flat JS function receives all args at once.
    app.fun match
      case inner: Term.Apply =>
        def topName(t: Term): Option[String] = t match
          case Term.Name(n)       => Some(n)
          case a: Term.Apply      => topName(a.fun)
          case _                  => None
        topName(inner) match
          case Some(n) if multiParamGroupFns(n) =>
            def flattenArgs(t: Term, acc: List[Term]): (Term, List[Term]) = t match
              case a: Term.Apply => flattenArgs(a.fun, a.argClause.values ++ acc)
              case other         => (other, acc)
            val (baseFun, allArgs) = flattenArgs(app, Nil)
            return s"_call(${genExpr(baseFun)}, ${allArgs.map(genExpr).mkString(", ")})"
          case _ => ()
      case _ => ()

    // def f: T => U = x => body — zero-param def returning a function.
    // _call(f, x) would call f(x) in JS but f has no params, so x is ignored and the lambda is returned.
    // Fix: generate _call(f(), x) so the inner function receives x.
    app.fun match
      case Term.Name(n) if zeroParamFns(n) && app.argClause.values.nonEmpty =>
        val argsJs = app.argClause.values.map(genExpr).mkString(", ")
        return s"_call(${mapName(n)}(), $argsJs)"
      case _ => ()

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
        fnNameOpt.flatMap(n => funcParamOrder.get(n).orElse(importedParamOrder.get(n))) match
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
      // Map constructor - args are tuple pairs (Map(...) or Map[K,V](...))
      case Term.Name("Map") =>
        s"_Map(${argVals.mkString(", ")})"
      case Term.ApplyType.After_4_6_0(Term.Name("Map"), _) =>
        s"_Map(${argVals.mkString(", ")})"

      // Vector / IndexedSeq — backed by a JS array but tagged with their display type so
      // `println(Vector(1,2,3))` renders `Vector(1, 2, 3)` (matching interp/JVM). (collection-real-type.)
      case Term.Name("Vector" | "IndexedSeq") =>
        s"_seqKind('Vector', [${argVals.mkString(", ")}])"
      case Term.ApplyType.After_4_6_0(Term.Name("Vector" | "IndexedSeq"), _) =>
        s"_seqKind('Vector', [${argVals.mkString(", ")}])"
      // List / Seq / Array / Iterable constructor — backed by a plain JS array (eager — no distinct
      // runtime type; Seq/Iterable display as List, Array's toString is non-deterministic).
      // (`LazyList` is NOT here — it calls the real lazy `LazyList(...)` runtime. lazylist-all-backends.)
      case Term.Name("List" | "Seq" | "Array" | "Iterable") =>
        s"[${argVals.mkString(", ")}]"
      case Term.ApplyType.After_4_6_0(Term.Name("List" | "Seq" | "Array" | "Iterable"), _) =>
        s"[${argVals.mkString(", ")}]"

      // Set constructor → deduplicated array (`Set(...)` otherwise hit the JS global `Set`,
      // which requires `new`). Array repr makes the existing array `_dispatch` methods apply.
      case Term.Name("Set") =>
        s"_setOf(${argVals.mkString(", ")})"
      case Term.ApplyType.After_4_6_0(Term.Name("Set"), _) =>
        s"_setOf(${argVals.mkString(", ")})"

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
        // Type the `(acc, elem) => …` combiner when both the seed and the element
        // are numeric, so `(a, b) => a + b` lowers to native `(a + b)`.
        val fJs = (initArgClause.values.headOption.flatMap(numericTypeOfExpr), numericElemOf(qual)) match
          case (Some(accElem), Some(elem)) if rawArgs.lengthCompare(1) == 0 =>
            genFold2ClosureTyped(rawArgs.head, accElem, elem)
          case _ => argVals.mkString(", ")
        s"_seqFoldLeft($qualJs, $initJs, $fJs)"

      // foldRight curried
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldRight")), initArgClause) =>
        val qualJs = genExpr(qual)
        val initJs = initArgClause.values.map(genExpr).mkString(", ")
        val fJs = argVals.mkString(", ")
        s"(($qualJs).reduceRight(($fJs === undefined ? (acc,x) => acc : (acc,x) => ($fJs)(x, acc)), $initJs))"

      // Numeric-element-list HOFs: type the single closure param to the element
      // type so `xs.map(x => x * 2)` emits native `(x * 2)` rather than
      // `_arith('*', x, 2)` + a string-repeat guard. Same runtime shape as the
      // generic path below — only the closure body changes.
      case Term.Select(qual, Term.Name(hof))
          if rawArgs.length == 1
            && rawArgs.head.isInstanceOf[Term.Function]
            && JsGen.numericListHofs.contains(hof)
            && numericElemOf(qual).isDefined =>
        val closureJs = genClosureWithParamType(rawArgs.head, numericElemOf(qual).get)
        if hof == "foreach" then s"_forEach(${genExpr(qual)}, $closureJs)"
        else s"_dispatch(${genExpr(qual)}, '$hof', [$closureJs])"

      // Method calls: obj.method(args) → _dispatch(obj, "method", [args])
      case Term.Select(qual, Term.Name(method)) =>
        val qualJs = genExpr(qual)
        method match
          // foreach with a single fn arg: bypass _dispatch + avoid [fn] array allocation.
          // _forEach uses an indexed for-loop for arrays and falls back to _dispatch otherwise.
          case "foreach" if argVals.length == 1 =>
            s"_forEach($qualJs, ${argVals.head})"
          // Known stdlib runtime singleton direct calls — bypass _dispatch and the per-call
          // [args] array allocation.  Safe: the JS preamble defines these as plain function
          // properties on const objects; direct call and _dispatch are semantically equivalent
          // as long as the user hasn't shadowed the singleton name with a local binding.
          case _ if qual.isInstanceOf[Term.Name] &&
                    JsGen.stdlibDirectCall.contains(
                      (qual.asInstanceOf[Term.Name].value, method)) =>
            s"$qualJs.$method(${argVals.mkString(", ")})"
          case _ =>
            val argsJs = argVals.mkString(", ")
            s"_dispatch($qualJs, '$method', [$argsJs])"

      // Known user-defined function with params — emit a direct JS call.
      // Safe because funcParamOrder only contains `def f(...)` declarations;
      // Array/Map values are vals, never in funcParamOrder.
      case Term.Name(n) if funcParamOrder.contains(n) =>
        runIfEffectful(n, s"${mapName(n)}(${argVals.mkString(", ")})")

      // Known zero-param user-defined function — direct call, no _call wrapper.
      // Covers both def f(): T (one empty param clause) and def f: T (no clause).
      case Term.Name(n) if (emptyParamFns(n) || zeroParamFns(n)) && argVals.isEmpty =>
        runIfEffectful(n, s"${mapName(n)}()")

      // js-collection-perf: indexed read on a known numeric-seq val (Vector/Array/List/Seq, tracked
      // in listElemType) → direct `v[idx]` (the JS backing is a real array), skipping the megamorphic
      // `_call`. isIntExpr/isNumericExpr also type `v(idx)` numeric so surrounding arithmetic and a
      // trailing `.toLong`/`.toInt` emit native ops.
      case Term.Name(v) if listElemType.contains(v) && argVals.lengthCompare(1) == 0 =>
        s"${mapName(v)}[${argVals.head}]"

      // Regular function call or constructor — wrap in `_call` so a
      // bare Array / Map reference (`xs(i)` / `m(k)`) is dispatched as
      // indexing rather than failing with "not a function".
      case fun =>
        val funJs = genExpr(fun)
        val call  = s"_call($funJs, ${argVals.mkString(", ")})"
        fun match
          case Term.Name(n) => runIfEffectful(n, call)
          case _            => call

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

  private[codegen] def genForPatBinding(pat: Pat, scrutVar: String): String = pat match
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
    case other     => paramRenames.getOrElse(other, emittedName(other))

  private[codegen] def withParamRenames[A](renames: Map[String, String])(f: => A): A =
    val saved = renames.keysIterator.map(k => k -> paramRenames.get(k)).toMap
    paramRenames ++= renames
    try f
    finally
      renames.keysIterator.foreach { k =>
        saved(k) match
          case Some(v) => paramRenames(k) = v
          case None    => paramRenames -= k
      }

  /** Returns true if the term is provably a TUPLE (a JS array), so `t._N` can lower to a
   *  direct `t[N-1]` read. Conservative: a tuple literal, a tuple `++` concat of two
   *  provable tuples, or a var bound to one. A case class is an object (not a tuple) and
   *  never matches, so its Product `._N` stays on the runtime dispatch. */
  private def isTupleExpr(t: Term): Boolean = t match
    case _: Term.Tuple                                     => true
    case Term.Name(n)                                      => tupleVars.contains(n)
    // `tupleA ++ rhs` lowers to `_tupleConcat(...)`, an array; element access by index
    // is correct on the concatenation regardless of the `_isTuple` tag, so a tuple lhs
    // is enough to make `result._N → result[N-1]` safe.
    case Term.ApplyInfix.After_4_6_0(l, op, _, _) if op.value == "++" || op.value == ":::" =>
      isTupleExpr(l)
    case Term.Ascribe(e, _)                                => isTupleExpr(e)
    case _                                                 => false

  /** Returns true if the term is provably integer-valued (no decimal arithmetic). */
  private[codegen] def isIntExpr(t: Term): Boolean = t match
    case _: Lit.Int | _: Lit.Long                 => true
    case _: Lit.Double | _: Lit.Float              => false
    case Term.Name(n)                              => intVars.contains(n)
    // int-returning fn call, OR `seq(idx)` indexed read on an Int/Long-element seq (js-collection-perf).
    case Term.Apply.After_4_6_0(Term.Name(n), ac) =>
      intFunctions.contains(n) ||
        (ac.values.lengthCompare(1) == 0 && listElemType.get(n).exists(e => e == "Int" || e == "Long"))
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("toInt" | "toLong")), _) => true
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("toDouble" | "toFloat")), _) => false
    // js-collection-perf: bare-select `.toInt` / `.toLong` (no parens) is Int regardless of the
    // receiver (numeric → Math.trunc, String → parseInt) — recognise it so surrounding arithmetic
    // emits native operators instead of `_arith`.
    case Term.Select(_, Term.Name("toInt" | "toLong")) => true
    // Collection / String methods that always return an Int regardless of the
    // element type — both the field-access (`xs.length`) and the apply
    // (`xs.indexOf(x)`) forms.
    case Term.Select(_, Term.Name("length" | "size")) => true
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("length" | "size" | "indexOf" | "lastIndexOf" | "count")), _) => true
    // Case-class field of declared Int/Long type: `v.x` where `case class Vec(x: Int)`.
    case Term.Select(Term.Name(v), Term.Name(f)) =>
      instanceVars.get(v).flatMap(caseClassFieldTypeMap.get).flatMap(_.get(f)).exists(t => t == "Int" || t == "Long")
    // `xs.foldLeft(init)(_ + _)` returns whatever `init` is — Int when the
    // seed literal is Int.
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Select(_, Term.Name("foldLeft" | "foldRight" | "fold")), initClause),
        _) => initClause.values.headOption.exists(isIntExpr)
    case Term.ApplyInfix.After_4_6_0(l, Term.Name(op), _, argClause)
        if Set("+", "-", "*", "/", "%").contains(op) =>
      argClause.values.headOption.exists(r => isIntExpr(l) && isIntExpr(r))
    case _ => false

  /** Returns true if the term is provably a `Long` (a JS BigInt at runtime), so
   *  arithmetic/comparison on it must route through `_arith` rather than a native
   *  JS operator (which would mix BigInt with Number and throw).
   *  v1-js-long-precision-and-bitops. */
  private[codegen] def isLongExpr(t: Term): Boolean = t match
    case _: Lit.Long                                   => true
    case Term.Name(n)                                  => longVars.contains(n)
    case Term.Apply.After_4_6_0(Term.Name(n), _)       => longFunctions.contains(n)
    // `.toLong` widens to Long; a bit-op result is a 64-bit BigInt (Long).
    case Term.Select(_, Term.Name("toLong"))                                   => true
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("toLong")), _)        => true
    // Long-typed case-class field: `v.x` where `case class C(x: Long)`.
    case Term.Select(Term.Name(v), Term.Name(f)) =>
      instanceVars.get(v).flatMap(caseClassFieldTypeMap.get).flatMap(_.get(f)).contains("Long")
    // Arithmetic or bit-op that has a Long operand stays Long.
    case Term.ApplyInfix.After_4_6_0(l, Term.Name(op), _, argClause)
        if Set("+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>").contains(op) =>
      isLongExpr(l) || argClause.values.headOption.exists(r => isLongExpr(r.asInstanceOf[Term]))
    case Term.Ascribe(e, _)                            => isLongExpr(e)
    case _                                             => false

  /** Returns true if the term is provably numeric (Int, Long, Double, or Float — never a String). */
  private[codegen] def isNumericExpr(t: Term): Boolean = isIntExpr(t) || (t match
    case _: Lit.Double | _: Lit.Float => true
    case Term.Name(n)                 => numericVars.contains(n)
    // numeric-returning fn call, OR `seq(idx)` on a numeric-element seq (js-collection-perf;
    // listElemType holds only Int/Long/Double/Float-element seqs).
    case Term.Apply.After_4_6_0(Term.Name(n), ac) =>
      numericFunctions.contains(n) || (ac.values.lengthCompare(1) == 0 && listElemType.contains(n))
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name("toDouble" | "toFloat")), _) => true
    // js-collection-perf: bare-select `.toDouble` / `.toFloat` is numeric.
    case Term.Select(_, Term.Name("toDouble" | "toFloat")) => true
    // a fused LazyList `.sum` pipeline yields a number — keep a trailing `.toLong` native.
    case Term.Select(q, Term.Name("sum")) if lazyFromMapTakeJs(q).isDefined => true
    // Case-class field of declared numeric type: `v.x` where `x: Int|Long|Double|Float`.
    case Term.Select(Term.Name(v), Term.Name(f)) =>
      instanceVars.get(v).flatMap(caseClassFieldTypeMap.get).flatMap(_.get(f))
        .exists(t => t == "Int" || t == "Long" || t == "Double" || t == "Float")
    case Term.ApplyInfix.After_4_6_0(l, Term.Name(op), _, argClause)
        if Set("+", "-", "*", "/", "%").contains(op) =>
      argClause.values.headOption.exists(r => isNumericExpr(l) && isNumericExpr(r))
    case _ => false
  )

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
        // Longs are BigInt in JS — fold to a BigInt literal so the result stays
        // a Long (mixing a folded plain number with a BigInt would throw).
        case "+"  => Some(s"${a + b}n")
        case "-"  => Some(s"${a - b}n")
        case "*"  => Some(s"${a * b}n")
        case "/"  if b != 0 => Some(s"${a / b}n")
        case "%"  if b != 0 => Some(s"${a % b}n")
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
