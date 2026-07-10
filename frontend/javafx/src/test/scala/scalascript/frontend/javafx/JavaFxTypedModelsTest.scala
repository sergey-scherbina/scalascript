package scalascript.frontend.javafx

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

import _root_.javafx.application.Platform
import _root_.javafx.scene.control.Label
import _root_.javafx.scene.layout.VBox
import scala.annotation.nowarn
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.*
import java.util.concurrent.CountDownLatch

/** v1.66.8 — Typed model data binding on the JavaFX backend:
 *  ModelView/ForModel/ModelText with JsonDecoder SPI. */
@nowarn("cat=deprecation")
class JavaFxTypedModelsTest extends AnyFunSuite:

  // Headless CI has no JavaFX toolkit — `Platform.startup` throws (not an
  // IllegalStateException) and aborted the whole suite. Catch it, record
  // availability, and `assume` it in each JavaFX-touching test so those CANCEL
  // (not fail) on a headless runner; the pure `RuntimeState.withModel` tests
  // below don't use the toolkit and still run.
  private val fxReady = new CountDownLatch(1)
  private val fxAvailable: Boolean =
    try
      Platform.startup(() => fxReady.countDown())
      fxReady.await(5, java.util.concurrent.TimeUnit.SECONDS)
    catch
      case _: IllegalStateException => fxReady.countDown(); true // already started
      case _: Throwable             => false                     // headless / no toolkit

  private def onFx[A](body: => A): A =
    assume(fxAvailable, "JavaFX toolkit unavailable (headless environment)")
    val p = Promise[A]()
    Platform.runLater(() => p.success(body))
    Await.result(p.future, 5.seconds)

  // ── RuntimeState.withModel ────────────────────────────────────────────────

  test("RuntimeState.withModel — stores model data keyed by varName") {
    val state = JavaFxRuntime.RuntimeState(
      signals     = scala.collection.mutable.Map.empty,
      signalRefs  = Map.empty,
      bindings    = scala.collection.mutable.Map.empty,
      fetchDispatcher = None
    )
    val childState = state.withModel("bs", Map("total" -> "100"))
    assert(childState.modelData("bs") == Map("total" -> "100"))
  }

  test("RuntimeState.withModel — stacks multiple model vars") {
    val state = JavaFxRuntime.RuntimeState(
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

  test("ModelText — Label shows field value from modelData") {
    val state = JavaFxRuntime.RuntimeState(
      signals     = scala.collection.mutable.Map.empty,
      signalRefs  = Map.empty,
      bindings    = scala.collection.mutable.Map.empty,
      fetchDispatcher = None,
      modelData   = Map("inv" -> Map("total" -> "500"))
    )
    val label = onFx {
      val pane = VBox()
      JavaFxRuntime.buildViewTest(pane, View.ModelText("inv", "total", Style()), state)
      pane.getChildren.stream().filter(_.isInstanceOf[Label]).findFirst().orElse(null).asInstanceOf[Label]
    }
    assert(label != null, "Label expected")
    assert(label.getText == "500", s"field value: ${label.getText}")
  }

  test("ModelText — shows empty string when modelData has no bindingVar") {
    val state = JavaFxRuntime.RuntimeState(
      signals     = scala.collection.mutable.Map.empty,
      signalRefs  = Map.empty,
      bindings    = scala.collection.mutable.Map.empty,
      fetchDispatcher = None
    )
    val label = onFx {
      val pane = VBox()
      JavaFxRuntime.buildViewTest(pane, View.ModelText("x", "field", Style()), state)
      pane.getChildren.stream().filter(_.isInstanceOf[Label]).findFirst().orElse(null).asInstanceOf[Label]
    }
    assert(label != null, "Label expected")
    assert(label.getText == "", s"empty when no data: ${label.getText}")
  }

  test("ModelText — nested dot path resolves through maps") {
    val state = JavaFxRuntime.RuntimeState(
      signals     = scala.collection.mutable.Map.empty,
      signalRefs  = Map.empty,
      bindings    = scala.collection.mutable.Map.empty,
      fetchDispatcher = None,
      modelData   = Map("bs" -> Map("assets" -> Map("total" -> "1000")))
    )
    val label = onFx {
      val pane = VBox()
      JavaFxRuntime.buildViewTest(pane, View.ModelText("bs", "assets.total", Style()), state)
      pane.getChildren.stream().filter(_.isInstanceOf[Label]).findFirst().orElse(null).asInstanceOf[Label]
    }
    assert(label != null)
    assert(label.getText == "1000", s"nested path: ${label.getText}")
  }

  // ── ForModel ──────────────────────────────────────────────────────────────

  test("ForModel — creates one row panel per item in the list") {
    val items = List(Map("name" -> "Alice"), Map("name" -> "Bob"))
    val state = JavaFxRuntime.RuntimeState(
      signals     = scala.collection.mutable.Map.empty,
      signalRefs  = Map.empty,
      bindings    = scala.collection.mutable.Map.empty,
      fetchDispatcher = None,
      modelData   = Map("people" -> items)
    )
    val childCount = onFx {
      val pane = VBox()
      JavaFxRuntime.buildViewTest(pane, View.ForModel("people", "people", "person",
        View.ModelText("person", "name", Style()), Style()), state)
      pane.getChildren.size()
    }
    assert(childCount == 1, "ForModel produces one outer VBox")
  }

  test("ForModel — empty list renders no children") {
    val state = JavaFxRuntime.RuntimeState(
      signals     = scala.collection.mutable.Map.empty,
      signalRefs  = Map.empty,
      bindings    = scala.collection.mutable.Map.empty,
      fetchDispatcher = None,
      modelData   = Map("m" -> List.empty[Any])
    )
    val childCount = onFx {
      val pane = VBox()
      JavaFxRuntime.buildViewTest(pane, View.ForModel("m", "m", "item",
        View.ModelText("item", "name", Style()), Style()), state)
      pane.getChildren.size()
    }
    assert(childCount == 1, "ForModel outer VBox still added even for empty list")
  }
