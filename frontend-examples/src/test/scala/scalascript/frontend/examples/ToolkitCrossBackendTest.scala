package scalascript.frontend.examples

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.custom.CustomFrameworkBackend
import scalascript.frontend.react.ReactFrameworkBackend
import scalascript.frontend.solid.SolidFrameworkBackend
import scalascript.frontend.vue.VueFrameworkBackend
import scalascript.frontend.toolkit.{Theme, Ssr, Tk}

/** v1.18 Phase C — proves that a UI assembled through the
 *  high-level toolkit (`Tk` facade) flows correctly through every
 *  backend.  The toolkit's promise is "same widget tree, every
 *  framework"; this test exercises that promise with real backend
 *  emitters, not just the View-AST level.
 *
 *  For each backend we:
 *    - emit the `ToolkitDemo` module
 *    - assert the framework-specific runtime markers are present
 *      (the toolkit's `AttrValue.Dynamic` value bindings + `Signal`
 *      subscriptions must produce the matching backend wiring)
 *    - assert toolkit-specific content surfaces in the JS / HTML
 *      (heading text, button label, the `id="toolkit-demo"` root)
 *    - assert theme tokens reach the rendered output
 *
 *  Plus a separate SSR smoke check that the same tree renders to a
 *  static HTML string without any backend involvement. */
class ToolkitCrossBackendTest extends AnyFunSuite:

  private val backends = List(
    new CustomFrameworkBackend,
    new ReactFrameworkBackend,
    new SolidFrameworkBackend,
    new VueFrameworkBackend
  )

  // Per-backend signal-wiring signature.  The toolkit uses
  // `AttrValue.Dynamic(() => signal())` + `EventHandler.WithEvent`
  // lambdas — these translate cleanly under React / Vue / Solid (the
  // emitter passes JVM closures through to the framework's JS render
  // function), but the Custom backend currently can't serialise JVM
  // lambdas to standalone JS.  See `StaticJsEmitter.scala`'s "richer
  // IR coming later" comment.  Until that lands, the Custom backend
  // produces a static-HTML snapshot of the toolkit tree — useful for
  // SSR but non-reactive.
  private val reactiveBackends: Set[String] = Set("react", "solid", "vue")
  private val signalMarker: Map[String, Seq[String]] = Map(
    "react" -> Seq("useState"),
    "solid" -> Seq("createSignal"),
    "vue"   -> Seq("ref(")
  )

  for backend <- backends do
    test(s"ToolkitDemo emits non-empty JS + HTML through ${backend.name}"):
      val emitted = backend.emit(ToolkitDemo.buildModule())
      assert(emitted.js.nonEmpty,   s"${backend.name} emitted empty JS")
      assert(emitted.html.nonEmpty, s"${backend.name} emitted empty HTML")

  for backend <- backends if reactiveBackends.contains(backend.name) do
    test(s"ToolkitDemo wires Signal bindings through ${backend.name}"):
      val emitted = backend.emit(ToolkitDemo.buildModule())
      val markers = signalMarker(backend.name)
      assert(markers.exists(emitted.js.contains),
        s"${backend.name} missing signal markers ${markers.mkString(", ")} " +
        s"— toolkit signals didn't reach the runtime.  First 400 chars:\n" +
        emitted.js.take(400))

  test("ToolkitDemo through Custom: static-HTML scaffold (no reactivity yet)"):
    // Documented limitation: the Custom backend's StaticJsEmitter
    // emits JVM closures as marker comments and snapshots
    // AttrValue.Dynamic.  Verify that the static scaffold is
    // produced + theme tokens reach the output, so SSR-style use
    // works even though interactive reactivity is deferred.
    val emitted = (new CustomFrameworkBackend).emit(ToolkitDemo.buildModule())
    assert(emitted.js.contains("document.createElement"),
      "Custom emitter dropped the DOM-construction scaffold")
    assert(emitted.js.contains("toolkit-demo"),
      "Custom emitter dropped the toolkit-demo root id")
    assert(emitted.js.contains(Theme.default.colors.primary),
      "Custom emitter dropped the theme primary colour")

  for backend <- backends do
    test(s"ToolkitDemo carries toolkit content through ${backend.name}"):
      val emitted = backend.emit(ToolkitDemo.buildModule())
      val combined = emitted.html + "\n" + emitted.js
      // The demo's heading text, button label, and root id all flow
      // through the toolkit's lowering — any backend that loses one
      // of them has dropped data from the View.
      assert(combined.contains("Toolkit demo"),
        s"${backend.name}: heading text missing")
      assert(combined.contains("Submit"),
        s"${backend.name}: button label missing")
      assert(combined.contains("toolkit-demo"),
        s"${backend.name}: root id 'toolkit-demo' missing")

  for backend <- backends do
    test(s"ToolkitDemo carries theme tokens through ${backend.name}"):
      val emitted = backend.emit(ToolkitDemo.buildModule(Theme.default))
      val combined = emitted.html + "\n" + emitted.js
      // The default theme primary colour should appear in the
      // rendered output — every backend embeds the toolkit's inline
      // style strings verbatim.
      assert(combined.contains(Theme.default.colors.primary),
        s"${backend.name}: theme primary colour ${Theme.default.colors.primary} missing")

  test("Dark theme produces visibly different output from default"):
    val light = (new CustomFrameworkBackend).emit(ToolkitDemo.buildModule(Theme.default))
    val dark  = (new CustomFrameworkBackend).emit(ToolkitDemo.buildModule(Theme.dark))
    assert(light.html != dark.html || light.js != dark.js,
      "Dark theme produced output identical to default — theme tokens not flowing")
    assert(dark.html.contains(Theme.dark.colors.background)
        || dark.js.contains(Theme.dark.colors.background),
      "Dark theme background colour missing from emit output")

  test("Toolkit + SSR renders to standalone HTML (no backend needed)"):
    val module = ToolkitDemo.buildModule()
    // Pull the root component's View tree out, render to HTML.
    val app    = module.components.find(_.name == "App").get
    val view   = app.body(Nil)
    val html   = Ssr.renderToHtml(view)
    assert(html.startsWith("<div") || html.contains("id=\"toolkit-demo\""),
      s"SSR didn't produce the root div.  Got start: ${html.take(120)}")
    assert(html.contains("Toolkit demo"),
      s"SSR dropped the heading text.  Output excerpt: ${html.take(400)}")
    assert(html.contains("Submit"),
      s"SSR dropped the button label")
    assert(!html.contains("onclick="),
      "SSR leaked an event handler attribute — expected non-interactive HTML")

  test("Toolkit + SSR.renderDocument produces a full HTML5 shell"):
    val tree = Tk.heading(1, "Static page")
    val doc  = Ssr.renderDocument(tree, title = "Static page")
    assert(doc.startsWith("<!DOCTYPE html>"))
    assert(doc.contains("<title>Static page</title>"))
    assert(doc.contains("<h1"))

  test("Each backend produces consistent ToolkitDemo output across runs"):
    // Determinism: same input module → same output bytes per backend.
    // Bugs that introduce randomness (e.g. component-id hashes via
    // System.identityHashCode without seeding) would surface here.
    for backend <- backends do
      val a = backend.emit(ToolkitDemo.buildModule())
      val b = backend.emit(ToolkitDemo.buildModule())
      // The toolkit uses identityHashCode for stable label/id pairs;
      // for fresh signals across two builds these differ, but the
      // STRUCTURAL shape (number of tags, presence of key markers)
      // must be identical.
      assert(countOccurrences(a.js, "function") == countOccurrences(b.js, "function"),
        s"${backend.name}: function count drifted between two builds " +
        s"(${countOccurrences(a.js, "function")} vs ${countOccurrences(b.js, "function")})")

  private def countOccurrences(s: String, needle: String): Int =
    if needle.isEmpty then 0
    else
      var i = 0
      var c = 0
      while
        val n = s.indexOf(needle, i)
        if n < 0 then false
        else { c += 1; i = n + needle.length; true }
      do ()
      c
