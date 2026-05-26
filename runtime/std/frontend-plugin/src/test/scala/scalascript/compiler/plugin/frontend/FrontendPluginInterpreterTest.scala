package scalascript.compiler.plugin.frontend

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.{AttrValue, EventHandler, FrontendFrameworks, ReactiveSignal, View}
import scalascript.interpreter.Value
import scalascript.testkit.TestInterpreter

class FrontendPluginInterpreterTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(FrontendInterpreterPlugin()))

  test("Frontend plugin creates signals and event handlers in isolation"):
    val input = interp.eval(
      """
      val name = signal("name", "Ada")
      inputChange(name)
      """
    )

    input match
      case Value.Foreign("EventHandler", EventHandler.InputChange(signal)) =>
        assert(signal.id == "name")
        assert(signal() == "Ada")
      case other => fail(s"expected InputChange foreign value, got $other")

    val toggle = interp.eval(
      """
      val enabled = signal("enabled", true)
      toggleSignal(enabled)
      """
    )

    toggle match
      case Value.Foreign("EventHandler", EventHandler.ToggleSignal(signal)) =>
        assert(signal.id == "enabled")
        assert(signal())
      case other => fail(s"expected ToggleSignal foreign value, got $other")

    val set = interp.eval(
      """
      val count = signal("count", 0)
      setSignal(count, 42)
      """
    )

    set match
      case Value.Foreign("EventHandler", EventHandler.SetSignalLiteral(signal, value)) =>
        assert(signal.id == "count")
        assert(value == 42L)
      case other => fail(s"expected SetSignalLiteral foreign value, got $other")

  test("Frontend plugin creates derived signals and views in isolation"):
    val eq = interp.eval(
      """
      val route = signal("route", "home")
      eqSignal(route, "home")
      """
    )

    eq match
      case Value.Foreign("ReactiveSignal", signal: ReactiveSignal[?]) =>
        assert(signal.id == "route__eq__home")
        assert(signal() == true)
      case other => fail(s"expected ReactiveSignal foreign value, got $other")

    val view = interp.eval(
      """
      val name = signal("name", "Ada")
      element(
        "button",
        Map("class" -> "primary", "disabled" -> false),
        Map("onInput" -> inputChange(name)),
        List(textNode("Save"), signalText(name))
      )
      """
    )

    view match
      case Value.Foreign("View", View.Element(tag, attrs, events, children)) =>
        assert(tag == "button")
        assert(attrs("class") == AttrValue.Str("primary"))
        assert(attrs("disabled") == AttrValue.Bool(false))
        assert(events.keySet == Set("onInput"))
        assert(children.size == 2)
      case other => fail(s"expected Element view foreign value, got $other")

  test("Frontend plugin setFrontendFramework runs through interpreter plugin install"):
    try
      interp.eval("""setFrontendFramework("react")""")
      assert(FrontendFrameworks.selectedName == Some("react"))
    finally
      FrontendFrameworks.setBackend(null)
