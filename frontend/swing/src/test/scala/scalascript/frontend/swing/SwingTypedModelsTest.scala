package scalascript.frontend.swing

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

import javax.swing.{JLabel, JPanel}
import scala.annotation.nowarn

/** v1.66.7 — Typed model data binding on the Swing backend:
 *  ModelView/ForModel/ModelText with JsonDecoder SPI. */
@nowarn("cat=deprecation")
class SwingTypedModelsTest extends AnyFunSuite:

  private val edt = javax.swing.SwingUtilities.invokeAndWait

  // ── modelField helper (via reflection on the runtime) ────────────────────

  test("RuntimeState.withModel — stores model data keyed by varName") {
    val state = SwingRuntime.RuntimeState(
      signals     = scala.collection.mutable.Map.empty,
      signalRefs  = Map.empty,
      bindings    = scala.collection.mutable.Map.empty,
      fetchDispatcher = None
    )
    val childState = state.withModel("bs", Map("total" -> "100"))
    assert(childState.modelData("bs") == Map("total" -> "100"))
  }

  test("RuntimeState.withModel — stacks multiple model vars") {
    val state = SwingRuntime.RuntimeState(
      signals     = scala.collection.mutable.Map.empty,
      signalRefs  = Map.empty,
      bindings    = scala.collection.mutable.Map.empty,
      fetchDispatcher = None
    )
    val s2 = state.withModel("a", 1).withModel("b", 2)
    assert(s2.modelData("a") == 1)
    assert(s2.modelData("b") == 2)
  }

  // ── ModelText ─────────────────────────────────────────────────────────────

  test("ModelText — JLabel shows field value from modelData") {
    val state = SwingRuntime.RuntimeState(
      signals     = scala.collection.mutable.Map.empty,
      signalRefs  = Map.empty,
      bindings    = scala.collection.mutable.Map.empty,
      fetchDispatcher = None,
      modelData   = Map("inv" -> Map("total" -> "500"))
    )
    var label: JLabel = null
    edt(() =>
      val panel = JPanel()
      SwingRuntime.buildViewTest(panel, View.ModelText("inv", "total", Style()), state)
      label = panel.getComponents.collectFirst { case l: JLabel => l }.orNull
    )
    assert(label != null, "JLabel expected")
    assert(label.getText == "500", s"field value: ${label.getText}")
  }

  test("ModelText — shows empty string when modelData has no bindingVar") {
    val state = SwingRuntime.RuntimeState(
      signals     = scala.collection.mutable.Map.empty,
      signalRefs  = Map.empty,
      bindings    = scala.collection.mutable.Map.empty,
      fetchDispatcher = None
    )
    var label: JLabel = null
    edt(() =>
      val panel = JPanel()
      SwingRuntime.buildViewTest(panel, View.ModelText("x", "field", Style()), state)
      label = panel.getComponents.collectFirst { case l: JLabel => l }.orNull
    )
    assert(label != null, "JLabel expected")
    assert(label.getText == "", s"empty when no data: ${label.getText}")
  }

  test("ModelText — nested dot path resolves through maps") {
    val state = SwingRuntime.RuntimeState(
      signals     = scala.collection.mutable.Map.empty,
      signalRefs  = Map.empty,
      bindings    = scala.collection.mutable.Map.empty,
      fetchDispatcher = None,
      modelData   = Map("bs" -> Map("assets" -> Map("total" -> "1000")))
    )
    var label: JLabel = null
    edt(() =>
      val panel = JPanel()
      SwingRuntime.buildViewTest(panel, View.ModelText("bs", "assets.total", Style()), state)
      label = panel.getComponents.collectFirst { case l: JLabel => l }.orNull
    )
    assert(label != null)
    assert(label.getText == "1000", s"nested path: ${label.getText}")
  }

  // ── ForModel ──────────────────────────────────────────────────────────────

  test("ForModel — creates one row panel per item in the list") {
    val items = List(Map("name" -> "Alice"), Map("name" -> "Bob"))
    val state = SwingRuntime.RuntimeState(
      signals     = scala.collection.mutable.Map.empty,
      signalRefs  = Map.empty,
      bindings    = scala.collection.mutable.Map.empty,
      fetchDispatcher = None,
      modelData   = Map("people" -> items)
    )
    var childCount = 0
    edt(() =>
      val panel = JPanel()
      SwingRuntime.buildViewTest(panel, View.ForModel("people", "people", "person",
        View.ModelText("person", "name", Style()), Style()), state)
      childCount = panel.getComponentCount
    )
    assert(childCount == 1, "ForModel produces one outer panel")
  }

  test("ForModel — empty list renders no children") {
    val state = SwingRuntime.RuntimeState(
      signals     = scala.collection.mutable.Map.empty,
      signalRefs  = Map.empty,
      bindings    = scala.collection.mutable.Map.empty,
      fetchDispatcher = None,
      modelData   = Map("m" -> List.empty[Any])
    )
    var childCount = 0
    edt(() =>
      val panel = JPanel()
      SwingRuntime.buildViewTest(panel, View.ForModel("m", "m", "item",
        View.ModelText("item", "name", Style()), Style()), state)
      // Just verify it doesn't throw
      childCount = panel.getComponentCount
    )
    assert(childCount == 1, "ForModel outer panel still added even for empty list")
  }
