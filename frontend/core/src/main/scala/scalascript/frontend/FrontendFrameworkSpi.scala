package scalascript.frontend

/** A pluggable frontend-framework backend.  Implementations
 *  translate the framework-agnostic primitives (`Signal`,
 *  `Computed`, `Effect`, `View`, `Component[P]`) into framework-
 *  specific JS source code.
 *
 *  Four production impls exist:
 *
 *    - `scalascript.frontend.custom.CustomFrameworkBackend` — the
 *      default; emits a tiny in-house runtime in Scala-compiled
 *      JS (~3-5 KB bundle, zero npm deps).
 *    - `scalascript.frontend.react.ReactFrameworkBackend` — Signal
 *      lowered to React `useState`, Component to function
 *      components, View to `React.createElement`.
 *    - `scalascript.frontend.solid.SolidFrameworkBackend` — Signal
 *      lowered to Solid `createSignal`, fine-grained subscriptions,
 *      component runs once.
 *    - `scalascript.frontend.vue.VueFrameworkBackend` — Signal
 *      lowered to Vue `ref`, setup-function components, render
 *      functions or template emission.
 *
 *  The user-facing API in `.ssc` source is identical across all
 *  four — the framework choice happens at build time via
 *  `setFrontendFramework("solid")` intrinsic or
 *  `ssc compile --frontend solid app.ssc`.
 *
 *  See `docs/frontend-framework-spi-plan.md` for the SPI mechanics
 *  and `docs/frontend-abstract-model.md` for the primitive
 *  contract this trait promises to translate. */
trait FrontendFrameworkSpi:

  /** Short identifier — `custom`, `react`, `vue`, `solid`.
   *  Used by `setFrontendFramework(name)` to pick when multiple
   *  impls are on the classpath. */
  def name: String

  /** What this backend can be configured to produce.  Drives
   *  feature-gating in the lowering pass — `.ssc` code that uses
   *  `suspense { … }` fails compile-time if the chosen backend
   *  doesn't declare `Capability.Suspense`. */
  def capabilities: Set[Capability]

  /** Maven / npm coordinates of any JS lib the user's bundler
   *  needs to pull in.  Empty for `custom` (no external runtime).
   *  Used by `ssc compile --frontend <name>` to inject the right
   *  `package.json` deps / `<script>` tags. */
  def jsDeps: List[JsDep]

  /** Emit the SPA from a framework-agnostic IR.  Returns the
   *  framework-specific JS bundle entrypoint + HTML shell +
   *  global CSS.  The IR-to-JS translation is where each backend
   *  earns its keep — same input, different idiomatic output. */
  def emit(module: FrontendModule): EmittedSpa

/** External-dependency descriptor — what the bundler / package
 *  manager needs to resolve so the emitted JS can run.  Backends
 *  declare these via `jsDeps`; `ssc compile --frontend <name>`
 *  injects them into the user's `package.json` / `//> using
 *  npm` directives. */
final case class JsDep(
    npmName:    String,
    version:    String,
    importPath: String
)

/** Output of `FrontendFrameworkSpi.emit(...)`.  Three artifacts:
 *  the SPA bundle entrypoint JS, the HTML shell that loads it,
 *  and any global CSS to inject. */
final case class EmittedSpa(
    js:   String,
    html: String,
    css:  String
)

/** Framework-agnostic IR — what the codegen lowering produces and
 *  what backends consume.  Empty in S1a; flesh out with actual
 *  IR types in A2 / A3 as the first impls force decisions about
 *  what the IR needs to carry.  See the spec doc for the
 *  primitive contract. */
final case class FrontendModule(
    components:   List[ComponentDef],
    entryPoint:   String,    // name of the root Component to mount
    initialRoute: String,    // for SPA routing; ignored if no router
    extraCss:     String = ""
)

/** Lowered component description.  Body is a function that, when
 *  called with props, returns a View tree.  Each backend's
 *  `emit` reads this and produces framework-specific source. */
final case class ComponentDef(
    name:   String,
    props:  List[PropDef],
    body:   Any => View      // (props) => View; type erased at IR level
)

final case class PropDef(name: String, paramType: String, default: Option[Any] = None)
