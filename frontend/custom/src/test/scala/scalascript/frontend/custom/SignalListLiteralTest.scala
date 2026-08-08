package scalascript.frontend.custom

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** `custom-jsemitter-signal-list-literal` — a signal holding a SEQUENCE could not be registered.
 *
 *  `registerSignal` seeds a signal's initial JS value with `jsLiteral(signal())`, and `jsLiteral`
 *  had cases for bare scalars only, so ANY program where an event handler targets a
 *  `Signal[List[_]]` threw at emit time:
 *
 *    jsLiteral: unsupported value type scala.collection.immutable.$colon$colon (List(a, b)).
 *    Supported: String / Int / Long / Double / Float / Boolean / null.
 *
 *  Note `ReactiveSignalList` is a DIFFERENT type with its own path — this is the plain
 *  `ReactiveSignal` whose value happens to be a sequence, which is what the report described.
 */
class SignalListLiteralTest extends AnyFunSuite:

  private def emit(initial: Any): String =
    val backend = new CustomFrameworkBackend
    val sig     = new ReactiveSignal[Any]("items", initial)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(View.Element("button",
        Map("id" -> AttrValue.Str("go")),
        Map("click" -> EventHandler.SetSignalLiteral(sig, initial)),
        Seq.empty)),
    ))
    backend.emit(FrontendModule(List(app), "App", "/")).js

  test("a signal holding a list of scalars registers and seeds a JS array") {
    val js = emit(List("a", "b"))
    assert(js.contains("['a','b']"), s"expected a JS array literal for the seed, got:\n$js")
  }

  test("nesting is encoded through, not flattened or stringified") {
    val js = emit(List(List(1, 2), List(3)))
    assert(js.contains("[[1,2],[3]]"), s"expected nested arrays, got:\n$js")
  }

  test("an empty sequence is an empty JS array, not null and not a crash") {
    assert(emit(List.empty[String]).contains("[]"))
  }

  // The CONTROL. A scalar seed must be untouched by the new arm — otherwise this "fix" could be
  // wrapping everything in brackets and the assertions above would still pass.
  test("a scalar signal still seeds exactly as before") {
    val js = emit("plain")
    assert(js.contains("'plain'"), s"scalar seed changed shape:\n$js")
    assert(!js.contains("['plain']"), "a scalar was wrapped in an array")
  }

  // The BOUNDARY, asserted rather than left implicit: a case-class instance is still refused, and
  // the refusal must SAY that it is a decision not yet taken rather than look like an oversight.
  // `frontend/custom` depends on `frontendCore` only — it cannot see the interpreter's value type —
  // so encoding instances is a question of where the encoder lives, not of what it prints.
  test("an unsupported value is still refused, and the message names the open decision") {
    final case class Todo(title: String)
    val thrown = intercept[IllegalArgumentException](emit(Todo("x")))
    assert(thrown.getMessage.contains("jsLiteral"))
    assert(thrown.getMessage.contains("custom-jsemitter-signal-list-literal"),
      s"the refusal should point at the entry that owns the decision, got: ${thrown.getMessage}")
  }
