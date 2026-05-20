package scalascript.frontend.toolkit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.frontend.{View, AttrValue, EventHandler}

/** v1.18 / Phase B — `Form` widget validation + lowering tests.
 *  Verifies that fields register idempotently, the built-in
 *  validators agree with their spec, `validate()` populates error
 *  signals + returns the correct gate, `reset()` clears state, and
 *  the form lowering emits a real `<form>` element with a submit
 *  listener. */
class FormTest extends AnyFunSuite with Matchers:

  private val theme = Theme.default

  // ─── FormContext.field ─────────────────────────────────────────

  test("FormContext.field: registers a field with given default + validator"):
    val ctx = new FormContext
    val f = ctx.field[String]("name", "alice", Validators.required)
    f.name shouldBe "name"
    f.value() shouldBe "alice"
    f.error() shouldBe None
    ctx.fields should contain key "name"

  test("FormContext.field: idempotent on same name"):
    val ctx = new FormContext
    val a = ctx.field[String]("email", "first")
    val b = ctx.field[String]("email", "second")   // ignored
    a should be theSameInstanceAs b
    a.value() shouldBe "first"
    ctx.fields.size shouldBe 1

  test("FormContext.values: snapshot of all field values"):
    val ctx = new FormContext
    ctx.field[String]("name",  "alice")
    ctx.field[String]("email", "a@b.com")
    ctx.field[Boolean]("agreed", true)
    val v = ctx.values()
    v("name")   shouldBe "alice"
    v("email")  shouldBe "a@b.com"
    v("agreed") shouldBe true

  // ─── Validators ────────────────────────────────────────────────

  test("Validators.required: empty string fails"):
    Validators.required[String]("")     shouldBe Some("required")
    Validators.required[String]("   ")  shouldBe Some("required")
    Validators.required[String]("ok")   shouldBe None

  test("Validators.required: null fails"):
    Validators.required[String](null) shouldBe Some("required")
    Validators.required[Any](null)    shouldBe Some("required")

  test("Validators.required: Option[T] checks isDefined"):
    Validators.required[Option[Int]](None)    shouldBe Some("required")
    Validators.required[Option[Int]](Some(0)) shouldBe None

  test("Validators.required: Boolean opt-in pattern (false = empty)"):
    Validators.required[Boolean](false) shouldBe Some("required")
    Validators.required[Boolean](true)  shouldBe None

  test("Validators.required: non-null Int treated as present"):
    Validators.required[Int](0) shouldBe None
    Validators.required[Int](7) shouldBe None

  test("Validators.minLength: rejects strings shorter than n"):
    Validators.minLength(3)("ab")  shouldBe defined
    Validators.minLength(3)("abc") shouldBe None
    Validators.minLength(3)(null)  shouldBe defined

  test("Validators.maxLength: rejects strings longer than n"):
    Validators.maxLength(3)("abcd") shouldBe defined
    Validators.maxLength(3)("abc")  shouldBe None
    Validators.maxLength(3)(null)   shouldBe None  // null passes maxLength

  test("Validators.pattern: regex with custom message"):
    val v = Validators.pattern("""^\d+$""".r, "digits only")
    v("123") shouldBe None
    v("a12") shouldBe Some("digits only")
    v(null)  shouldBe Some("digits only")

  test("Validators.email: accepts well-formed addresses"):
    Validators.email("alice@example.com") shouldBe None
    Validators.email("a.b+tag@sub.example.co.uk") shouldBe None

  test("Validators.email: rejects malformed addresses"):
    Validators.email("not-an-email") shouldBe defined
    Validators.email("@example.com") shouldBe defined
    Validators.email("alice@")       shouldBe defined
    Validators.email("")             shouldBe defined

  test("Validators.and: combines — first failure wins"):
    val v = Validators.and[String](
      Validators.required,
      Validators.minLength(3),
      Validators.email
    )
    v("")    shouldBe Some("required")            // first failure
    v("ab")  shouldBe Some("must be at least 3 characters")
    v("abc") shouldBe defined                     // email check kicks in
    v("a@b.co") shouldBe None                     // all pass

  test("Validators.and: empty list always passes"):
    Validators.and[String]()("anything") shouldBe None

  // ─── FormContext.validate ──────────────────────────────────────

  test("FormContext.validate: true when all fields pass"):
    val ctx = new FormContext
    ctx.field[String]("name",  "alice", Validators.required)
    ctx.field[String]("email", "a@b.co", Validators.email)
    ctx.validate() shouldBe true

  test("FormContext.validate: false when any field fails"):
    val ctx = new FormContext
    ctx.field[String]("name",  "",       Validators.required)
    ctx.field[String]("email", "a@b.co", Validators.email)
    ctx.validate() shouldBe false

  test("FormContext.validate: populates each field's error signal"):
    val ctx = new FormContext
    val name  = ctx.field[String]("name",  "", Validators.required)
    val email = ctx.field[String]("email", "bad-email", Validators.email)
    ctx.validate() shouldBe false
    name.error()  shouldBe Some("required")
    email.error() shouldBe Some("must be a valid email")

  test("FormContext.validate: clears errors when re-validated to pass"):
    val ctx = new FormContext
    val name = ctx.field[String]("name", "", Validators.required)
    ctx.validate()
    name.error() shouldBe defined
    name.value.set("ok")
    ctx.validate() shouldBe true
    name.error() shouldBe None

  // ─── FormContext.reset ─────────────────────────────────────────

  test("FormContext.reset: restores defaults + clears errors"):
    val ctx = new FormContext
    val name  = ctx.field[String]("name",  "alice", Validators.required)
    val agreed = ctx.field[Boolean]("agreed", false)
    name.value.set("bob")
    agreed.value.set(true)
    ctx.validate()
    ctx.globalError.set(Some("oops"))
    ctx.reset()
    name.value()   shouldBe "alice"
    name.error()   shouldBe None
    agreed.value() shouldBe false
    ctx.globalError() shouldBe None

  // ─── FormNode lowering ─────────────────────────────────────────

  test("FormNode lowering: produces a <form> View.Element"):
    val node = FormNode(
      onSubmit = _ => (),
      build    = _ => TextNode("body")
    )
    Toolkit.lower(node, theme) match
      case View.Element("form", attrs, events, kids) =>
        events should contain key "submit"
        kids.length shouldBe 1
        attrs("role").asInstanceOf[AttrValue.Str].value shouldBe "form"
      case other => fail(s"got $other")

  test("FormNode lowering: submit listener runs validation + onSubmit"):
    val submitted = new java.util.concurrent.atomic.AtomicInteger(0)
    var lastValues: Map[String, Any] = Map.empty
    val node = FormNode(
      onSubmit = ctx => {
        submitted.incrementAndGet()
        lastValues = ctx.values()
      },
      build = ctx => {
        ctx.field[String]("name", "alice", Validators.required)
        TextNode("body")
      }
    )
    Toolkit.lower(node, theme) match
      case View.Element("form", _, events, _) =>
        events("submit") match
          case EventHandler.WithEvent(f) => f(null)
          case other                      => fail(s"got $other")
      case other => fail(s"got $other")
    submitted.get shouldBe 1
    lastValues("name") shouldBe "alice"

  test("FormNode lowering: validation failure blocks onSubmit"):
    val submitted = new java.util.concurrent.atomic.AtomicInteger(0)
    val node = FormNode(
      onSubmit = _ => submitted.incrementAndGet(),
      build = ctx => {
        ctx.field[String]("name", "", Validators.required)
        TextNode("body")
      }
    )
    Toolkit.lower(node, theme) match
      case View.Element("form", _, events, _) =>
        events("submit").asInstanceOf[EventHandler.WithEvent].action(null)
      case other => fail(s"got $other")
    submitted.get shouldBe 0

  test("FormNode lowering: submitting flag flips around the user callback"):
    // The user callback observes submitting=true; after the handler
    // returns, submitting is reset to false.
    var seenSubmittingDuringCallback = false
    var capturedCtx: FormContext = null
    val node = FormNode(
      onSubmit = ctx => {
        capturedCtx = ctx
        seenSubmittingDuringCallback = ctx.submitting()
      },
      build = ctx => {
        ctx.field[String]("ok", "x")
        TextNode("body")
      }
    )
    Toolkit.lower(node, theme) match
      case View.Element("form", _, events, _) =>
        events("submit").asInstanceOf[EventHandler.WithEvent].action(null)
      case other => fail(s"got $other")
    seenSubmittingDuringCallback shouldBe true
    capturedCtx.submitting() shouldBe false

  // ─── Tk.form fluent facade ─────────────────────────────────────

  test("Tk.form: fluent facade produces a FormNode that lowers to <form>"):
    import Tk.*
    val tree = form(onSubmit = _ => ()) { ctx =>
      val name = ctx.field[String]("name", "", Validators.required)
      vstack(gap = 8)(
        textField(value = name.value, label = Some("Name"),
                  error = Some(name.error)),
        button("Save", onClick = () => (), formSubmit = true)
      )
    }
    Toolkit.lower(tree, theme) match
      case View.Element("form", _, events, kids) =>
        events should contain key "submit"
        kids.length shouldBe 1
        // The body is the vstack <div>
        kids.head.asInstanceOf[View.Element].tag shouldBe "div"
      case other => fail(s"got $other")

  test("Tk.form: end-to-end submit flow"):
    import Tk.*
    val submitted = new java.util.concurrent.atomic.AtomicReference[Map[String, Any]](null)
    val tree = form(onSubmit = ctx => submitted.set(ctx.values())) { ctx =>
      ctx.field[String]("name",  "alice", Validators.required)
      ctx.field[String]("email", "a@b.co", Validators.email)
      TextNode("body")
    }
    Toolkit.lower(tree, theme) match
      case View.Element("form", _, events, _) =>
        events("submit").asInstanceOf[EventHandler.WithEvent].action(null)
      case other => fail(s"got $other")
    val values = submitted.get
    values should not be null
    values("name")  shouldBe "alice"
    values("email") shouldBe "a@b.co"
