package scalascript.frontend.examples

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.custom.CustomFrameworkBackend
import scalascript.frontend.react.ReactFrameworkBackend
import scalascript.frontend.solid.SolidFrameworkBackend
import scalascript.frontend.vue.VueFrameworkBackend

/** Smoke + shape tests for the three reference apps.  Each test
 *  builds the IR for one demo, lowers it through every backend, and
 *  asserts that the emitted JS contains backend-idiomatic markers
 *  (`useState` for React, `createSignal` for Solid, `ref(` for Vue,
 *  the `__ssc_signals` registry for Custom).
 *
 *  These tests are intentionally string-grep level — the per-backend
 *  modules already have full unit + E2E coverage of the emit output.
 *  This suite's job is to prove the reference-app IR is well-formed
 *  for every backend, and that `EmitAll` produces a non-empty file
 *  per backend per demo. */
class ReferenceAppsTest extends AnyFunSuite:

  private val backends = List(
    new CustomFrameworkBackend,
    new ReactFrameworkBackend,
    new SolidFrameworkBackend,
    new VueFrameworkBackend
  )

  // Backend-specific markers we expect to appear in every demo's JS.
  // Custom: one of the runtime registry keys (signals OR lists; the
  // todo demo has no scalar signal so it only uses __ssc_lists).
  // React: `useState`.  Solid: `createSignal`.  Vue: `ref(`.  If any
  // of these go missing the emit pipeline has regressed for that
  // backend.
  private val markersForBackend: Map[String, Seq[String]] = Map(
    "custom" -> Seq("__ssc_signals", "__ssc_lists"),
    "react"  -> Seq("useState"),
    "solid"  -> Seq("createSignal"),
    "vue"    -> Seq("ref(")
  )

  /** Per-(demo, backend) override of the standard markers.  Used to
   *  document known limitations — e.g. the toolkit demo emits static
   *  HTML through the Custom backend because the Custom emitter
   *  can't yet translate JVM lambdas (`EventHandler.WithEvent`) or
   *  subscribe to `AttrValue.Dynamic`.  See
   *  `frontend-custom/src/main/scala/.../StaticJsEmitter.scala`'s
   *  "richer IR coming later" comment. */
  private val markerOverrides: Map[(String, String), Seq[String]] = Map(
    // Toolkit-demo through Custom: assert only that the static
    // HTML scaffold made it out — signal-binding is acknowledged
    // as a Phase D follow-up.
    (ToolkitDemo.Name, "custom") -> Seq("document.createElement", "toolkit-demo")
  )

  for (demoName, build) <- EmitAll.demos do
    for backend <- backends do
      test(s"$demoName demo emits non-empty JS through ${backend.name}") {
        val emitted = backend.emit(build())
        assert(emitted.js.nonEmpty, s"${backend.name} emitted empty JS for $demoName")
        assert(emitted.html.nonEmpty, s"${backend.name} emitted empty HTML for $demoName")
        val markers = markerOverrides.getOrElse(
          (demoName, backend.name), markersForBackend(backend.name))
        assert(
          markers.exists(emitted.js.contains),
          s"${backend.name} JS for $demoName missing all markers [${markers.mkString(", ")}].  " +
          s"Got:\n${emitted.js.take(400)}"
        )
      }

  test("counter demo wires Increment + reset through every backend") {
    val ir = CounterDemo.buildModule()
    // The counter demo is the canonical IncrementSignal + SetSignalLiteral exercise;
    // every backend's emit MUST mention the signal name `count` or
    // its setter so we know the wiring went through.
    for backend <- backends do
      val emitted = backend.emit(ir)
      assert(emitted.js.contains("count"),
        s"${backend.name} counter JS does not reference the 'count' signal")
  }

  test("show-hide demo emits both branches of the ShowSignal across backends") {
    val ir = ShowHideDemo.buildModule()
    for backend <- backends do
      val emitted = backend.emit(ir)
      assert(emitted.js.contains("box"),
        s"${backend.name} show-hide JS does not reference the 'box' span id")
      assert(emitted.js.contains("visible"),
        s"${backend.name} show-hide JS does not reference the 'visible' signal")
  }

  test("todo demo references the list signal name across backends") {
    val ir = TodoListDemo.buildModule()
    for backend <- backends do
      val emitted = backend.emit(ir)
      assert(emitted.js.contains("todos"),
        s"${backend.name} todo JS does not reference the 'todos' list signal")
  }

  test("EmitAll writes one HTML + JS pair per (demo, backend)") {
    val tmp = Files.createTempDirectory("ssc-frontend-examples-")
    try
      val written = EmitAll.emitAll(tmp)
      // 4 demos x 4 backends x 2 files (html + js).  No backend
      // currently emits non-empty CSS.
      assert(written.size == 4 * 4 * 2,
        s"Expected 32 files, got ${written.size}:\n${written.mkString("\n")}")
      for (demoName, _) <- EmitAll.demos do
        for backend <- backends do
          val sub = tmp.resolve(demoName).resolve(backend.name)
          val html = sub.resolve("index.html")
          val js   = sub.resolve("app.js")
          assert(Files.exists(html), s"missing $html")
          assert(Files.exists(js),   s"missing $js")
          assert(Files.size(html) > 0, s"empty $html")
          assert(Files.size(js)   > 0, s"empty $js")
    finally
      // Best-effort recursive delete.
      if Files.exists(tmp) then
        Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).forEach { p =>
          val _ = Files.deleteIfExists(p)
        }
  }

  test("each backend declares a distinct, non-empty name") {
    val names = backends.map(_.name)
    assert(names.toSet.size == names.size, s"backend names collide: $names")
    names.foreach(n => assert(n.nonEmpty, s"empty backend name in $names"))
  }
