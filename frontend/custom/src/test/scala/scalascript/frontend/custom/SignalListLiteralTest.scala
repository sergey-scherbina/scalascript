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

  private final case class Todo(title: String, done: Boolean)

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

  // ── case classes, maps and tuples ───────────────────────────────────────────
  // The shape is MIRRORED from `RestRuntime._toJsonValue`, which already answers this question for
  // this language. These assertions are what stops the two drifting apart silently.

  test("a case class encodes as a JS object keyed by field name") {
    val js = emit(Todo("write", true))
    assert(js.contains("""{'title':'write','done':true}"""), s"got:\n$js")
  }

  test("a list of case classes encodes through") {
    val js = emit(List(Todo("a", false), Todo("b", true)))
    assert(js.contains("""[{'title':'a','done':false},{'title':'b','done':true}]"""), s"got:\n$js")
  }

  test("a tuple is an array, because _1 and _2 name nothing a consumer can use") {
    assert(emit((1, "x")).contains("""[1,'x']"""))
  }

  test("a map is a JS object with stringified keys") {
    assert(emit(Map("a" -> 1)).contains("""{'a':1}"""))
  }

  // Recorded as an assertion rather than left to be discovered: `Some` is a Product whose element
  // is named `value`, so it encodes as an object — the SAME as the REST side. Agreeing with the
  // existing convention beats being separately clever, and if either side changes this fails.
  test("Option follows the same rule as the REST encoder, deliberately") {
    assert(emit(Some(3)).contains("""{'value':3}"""))
  }

  // The BOUNDARY still exists, just further out, and the refusal must keep pointing at the entry
  // that owns the decision rather than reading as an oversight.
  test("a value with no shape rule is still refused, and the message names the entry") {
    val thrown = intercept[IllegalArgumentException](emit(new Object))
    assert(thrown.getMessage.contains("jsLiteral"))
    assert(thrown.getMessage.contains("custom-jsemitter-signal-list-literal"),
      s"the refusal should point at the entry that owns the decision, got: ${thrown.getMessage}")
  }
