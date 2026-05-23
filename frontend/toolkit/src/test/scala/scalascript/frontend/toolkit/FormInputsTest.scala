package scalascript.frontend.toolkit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.frontend.{View, AttrValue, EventHandler, ReactiveSignal}

/** v1.18 Phase B++ — FormInputs tests.  Each input lowers to the
 *  right HTML primitive with correct value binding, change events,
 *  ARIA attributes, theme colours, and form-error integration. */
class FormInputsTest extends AnyFunSuite with Matchers:

  private val theme = Theme.default

  // ─── Select ───────────────────────────────────────────────────

  enum Tier { case Free, Pro, Team }

  test("Select: lowers to <select> with one <option> per choice"):
    val v = new ReactiveSignal[Tier]("tier", Tier.Free)
    val node = SelectNode(v,
      options = Seq(Tier.Free -> "Free", Tier.Pro -> "Pro", Tier.Team -> "Team"))
    val view = Toolkit.lower(node, theme)
    val select = collectTag(view, "select").head
    val opts   = select.children.collect { case e: View.Element => e }
    opts.length shouldBe 3
    opts.foreach(_.tag shouldBe "option")
    opts.map(_.attrs("value").asInstanceOf[AttrValue.Str].value) shouldBe
      Seq("Free", "Pro", "Team")

  test("Select: currently-selected option carries selected=true"):
    val v = new ReactiveSignal[Tier]("tier", Tier.Pro)
    val node = SelectNode(v,
      options = Seq(Tier.Free -> "Free", Tier.Pro -> "Pro"))
    val select = collectTag(Toolkit.lower(node, theme), "select").head
    val proOpt = select.children.collect { case e: View.Element => e }
      .find(_.attrs("value").asInstanceOf[AttrValue.Str].value == "Pro").get
    proOpt.attrs.get("selected") shouldBe Some(AttrValue.Bool(true))

  test("Select: change event updates the signal"):
    val v = new ReactiveSignal[Tier]("tier", Tier.Free)
    val node = SelectNode(v,
      options = Seq(Tier.Free -> "Free", Tier.Pro -> "Pro"))
    val select = collectTag(Toolkit.lower(node, theme), "select").head
    select.events("change") match
      case EventHandler.WithEvent(fn) => fn("Pro")
      case _                          => fail("no change handler")
    v() shouldBe Tier.Pro

  test("Select: change to unknown value is ignored"):
    val v = new ReactiveSignal[Tier]("tier", Tier.Free)
    val node = SelectNode(v, options = Seq(Tier.Free -> "Free"))
    val select = collectTag(Toolkit.lower(node, theme), "select").head
    select.events("change") match
      case EventHandler.WithEvent(fn) => fn("Unknown")
      case _                          => fail("no change")
    v() shouldBe Tier.Free  // unchanged

  test("Select: placeholder renders as a disabled first option"):
    val v = new ReactiveSignal[Tier]("tier", Tier.Free)
    val node = SelectNode(v,
      options = Seq(Tier.Free -> "Free"),
      placeholder = Some("Pick one"))
    val select = collectTag(Toolkit.lower(node, theme), "select").head
    val first  = select.children.head.asInstanceOf[View.Element]
    first.tag shouldBe "option"
    first.attrs("disabled") shouldBe AttrValue.Bool(true)
    extractText(first) should include ("Pick one")

  test("Select: error signal flips border + emits aria-invalid"):
    val v   = new ReactiveSignal[Tier]("tier", Tier.Free)
    val err = new ReactiveSignal[Option[String]]("err", Some("Required"))
    val node = SelectNode(v,
      options = Seq(Tier.Free -> "Free"),
      error   = Some(err))
    val view   = Toolkit.lower(node, theme)
    val select = collectTag(view, "select").head
    select.attrs.get("aria-invalid") shouldBe Some(AttrValue.Bool(true))
    select.attrs("style").asInstanceOf[AttrValue.Str].value should
      include (theme.colors.danger)
    val alert = collectTag(view, "span").find(s =>
      s.attrs.get("role").contains(AttrValue.Str("alert")))
    alert.map(extractText) shouldBe Some("Required")

  test("Select: label binds to the select via for/id"):
    val v = new ReactiveSignal[Tier]("tier", Tier.Free)
    val node = SelectNode(v,
      options = Seq(Tier.Free -> "Free"),
      label   = Some("Plan"))
    val view   = Toolkit.lower(node, theme)
    val select = collectTag(view, "select").head
    val label  = collectTag(view, "label").head
    label.attrs("for").asInstanceOf[AttrValue.Str].value shouldBe
      select.attrs("id").asInstanceOf[AttrValue.Str].value

  test("Select: required + disabled flags propagate"):
    val v = new ReactiveSignal[Tier]("tier", Tier.Free)
    val node = SelectNode(v,
      options = Seq(Tier.Free -> "Free"),
      required = true, disabled = true)
    val select = collectTag(Toolkit.lower(node, theme), "select").head
    select.attrs.get("required") shouldBe Some(AttrValue.Bool(true))
    select.attrs.get("disabled") shouldBe Some(AttrValue.Bool(true))

  // ─── RadioGroup ───────────────────────────────────────────────

  test("RadioGroup: one <input type=radio> per option, shared name"):
    val v = new ReactiveSignal[Tier]("tier", Tier.Free)
    val node = RadioGroupNode(v,
      options = Seq(Tier.Free -> "Free", Tier.Pro -> "Pro", Tier.Team -> "Team"))
    val view = Toolkit.lower(node, theme)
    val radios = collectTag(view, "input").filter(_.attrs.get("type") ==
      Some(AttrValue.Str("radio")))
    radios.length shouldBe 3
    val names = radios.map(_.attrs("name").asInstanceOf[AttrValue.Str].value).toSet
    names.size shouldBe 1  // all share the same name

  test("RadioGroup: clicking a radio updates the signal"):
    val v = new ReactiveSignal[Tier]("tier", Tier.Free)
    val node = RadioGroupNode(v,
      options = Seq(Tier.Free -> "Free", Tier.Pro -> "Pro"))
    val proRadio = collectTag(Toolkit.lower(node, theme), "input")
      .find(_.attrs("value") == AttrValue.Str("Pro")).get
    proRadio.events("change") match
      case EventHandler.Simple(fn) => fn()
      case _                       => fail("no change")
    v() shouldBe Tier.Pro

  test("RadioGroup: disabled group ignores clicks"):
    val v = new ReactiveSignal[Tier]("tier", Tier.Free)
    val node = RadioGroupNode(v,
      options = Seq(Tier.Free -> "Free", Tier.Pro -> "Pro"),
      disabled = true)
    val proRadio = collectTag(Toolkit.lower(node, theme), "input")
      .find(_.attrs("value") == AttrValue.Str("Pro")).get
    proRadio.attrs.get("disabled") shouldBe Some(AttrValue.Bool(true))
    proRadio.events("change") match
      case EventHandler.Simple(fn) => fn()
      case _                       => fail("no change")
    v() shouldBe Tier.Free  // unchanged

  test("RadioGroup: container has role=radiogroup"):
    val v = new ReactiveSignal[Tier]("tier", Tier.Free)
    val node = RadioGroupNode(v, options = Seq(Tier.Free -> "Free"))
    val group = collectTag(Toolkit.lower(node, theme), "div")
      .find(_.attrs.get("role") == Some(AttrValue.Str("radiogroup")))
    group should not be empty

  test("RadioGroup: horizontal orientation uses flex-row"):
    val v = new ReactiveSignal[Tier]("tier", Tier.Free)
    val node = RadioGroupNode(v,
      options = Seq(Tier.Free -> "Free"),
      orientation = RadioOrientation.Horizontal)
    val group = collectTag(Toolkit.lower(node, theme), "div")
      .find(_.attrs.get("role") == Some(AttrValue.Str("radiogroup"))).get
    group.attrs("style").asInstanceOf[AttrValue.Str].value should
      include ("flex-direction: row")

  test("RadioGroup: error signal sets aria-invalid + shows message"):
    val v   = new ReactiveSignal[Tier]("tier", Tier.Free)
    val err = new ReactiveSignal[Option[String]]("err", Some("Choose one"))
    val node = RadioGroupNode(v,
      options = Seq(Tier.Free -> "Free"),
      error   = Some(err))
    val view  = Toolkit.lower(node, theme)
    val group = collectTag(view, "div")
      .find(_.attrs.get("role") == Some(AttrValue.Str("radiogroup"))).get
    group.attrs.get("aria-invalid") shouldBe Some(AttrValue.Bool(true))
    extractText(view) should include ("Choose one")

  // ─── Textarea ─────────────────────────────────────────────────

  test("Textarea: lowers to <textarea> with rows + placeholder"):
    val v = new ReactiveSignal[String]("ta", "")
    val node = TextareaNode(v, placeholder = Some("Write..."), rows = 6)
    val ta = collectTag(Toolkit.lower(node, theme), "textarea").head
    ta.attrs("rows").asInstanceOf[AttrValue.Num].value shouldBe 6.0
    ta.attrs("placeholder").asInstanceOf[AttrValue.Str].value shouldBe "Write..."

  test("Textarea: input event updates the signal"):
    val v = new ReactiveSignal[String]("ta", "")
    val node = TextareaNode(v)
    val ta = collectTag(Toolkit.lower(node, theme), "textarea").head
    ta.events("input") match
      case EventHandler.WithEvent(fn) => fn("hello")
      case _                          => fail("no input")
    v() shouldBe "hello"

  test("Textarea: maxLength attribute propagates"):
    val v = new ReactiveSignal[String]("ta", "")
    val node = TextareaNode(v, maxLength = Some(280))
    val ta = collectTag(Toolkit.lower(node, theme), "textarea").head
    ta.attrs("maxlength").asInstanceOf[AttrValue.Num].value shouldBe 280.0

  test("Textarea: error renders inline alert + aria-invalid"):
    val v   = new ReactiveSignal[String]("ta", "")
    val err = new ReactiveSignal[Option[String]]("err", Some("Too long"))
    val node = TextareaNode(v, error = Some(err))
    val view = Toolkit.lower(node, theme)
    val ta   = collectTag(view, "textarea").head
    ta.attrs.get("aria-invalid") shouldBe Some(AttrValue.Bool(true))
    extractText(view) should include ("Too long")

  // ─── DatePicker ───────────────────────────────────────────────

  test("DatePicker: lowers to <input type=date> with ISO value"):
    val v = new ReactiveSignal[String]("d", "2026-05-20")
    val node = DatePickerNode(v)
    val input = collectTag(Toolkit.lower(node, theme), "input").head
    input.attrs("type") shouldBe AttrValue.Str("date")
    input.attrs("value") match
      case AttrValue.Dynamic(thunk) => thunk() shouldBe "2026-05-20"
      case other                     => fail(s"got $other")

  test("DatePicker: change event writes signal"):
    val v = new ReactiveSignal[String]("d", "")
    val node = DatePickerNode(v)
    val input = collectTag(Toolkit.lower(node, theme), "input").head
    input.events("change") match
      case EventHandler.WithEvent(fn) => fn("2027-01-15")
      case _                           => fail("no change")
    v() shouldBe "2027-01-15"

  test("DatePicker: min + max attributes propagate"):
    val v = new ReactiveSignal[String]("d", "")
    val node = DatePickerNode(v, min = Some("2026-01-01"), max = Some("2026-12-31"))
    val input = collectTag(Toolkit.lower(node, theme), "input").head
    input.attrs("min").asInstanceOf[AttrValue.Str].value shouldBe "2026-01-01"
    input.attrs("max").asInstanceOf[AttrValue.Str].value shouldBe "2026-12-31"

  test("DatePicker: required label gets an asterisk"):
    val v = new ReactiveSignal[String]("d", "")
    val node = DatePickerNode(v, label = Some("DOB"), required = true)
    val view = Toolkit.lower(node, theme)
    extractText(view) should include ("DOB *")

  // ─── NumberInput ──────────────────────────────────────────────

  test("NumberInput: lowers to <input type=number>"):
    val v = new ReactiveSignal[Double]("n", 0.0)
    val node = NumberInputNode(v)
    val input = collectTag(Toolkit.lower(node, theme), "input").head
    input.attrs("type") shouldBe AttrValue.Str("number")

  test("NumberInput: input event accepts Double / Int / String payloads"):
    val v = new ReactiveSignal[Double]("n", 0.0)
    val node = NumberInputNode(v)
    val input = collectTag(Toolkit.lower(node, theme), "input").head
    val fn = input.events("input").asInstanceOf[EventHandler.WithEvent].action
    fn(42.5);          v() shouldBe 42.5
    fn(7);             v() shouldBe 7.0
    fn("3.14");        v() shouldBe 3.14
    fn("not a number"); v() shouldBe 3.14  // unchanged on parse failure

  test("NumberInput: min + max + step propagate"):
    val v = new ReactiveSignal[Double]("n", 0.0)
    val node = NumberInputNode(v, min = Some(0.0), max = Some(100.0), step = 0.5)
    val input = collectTag(Toolkit.lower(node, theme), "input").head
    input.attrs("min").asInstanceOf[AttrValue.Num].value  shouldBe 0.0
    input.attrs("max").asInstanceOf[AttrValue.Num].value  shouldBe 100.0
    input.attrs("step").asInstanceOf[AttrValue.Num].value shouldBe 0.5

  // ─── Tk facade ────────────────────────────────────────────────

  test("Tk.select / .radioGroup / .textarea / .datePicker / .numberInput compose"):
    import Tk.*
    val tier = new ReactiveSignal[Tier]("tier", Tier.Free)
    val pref = new ReactiveSignal[Tier]("pref", Tier.Pro)
    val note = new ReactiveSignal[String]("note", "")
    val date = new ReactiveSignal[String]("date", "")
    val num  = new ReactiveSignal[Double]("num", 0.0)
    val tree = vstack(gap = 8)(
      select(tier,    Seq(Tier.Free -> "Free", Tier.Pro -> "Pro")),
      radioGroup(pref, Seq(Tier.Free -> "Free", Tier.Pro -> "Pro")),
      textarea(note, rows = 3),
      datePicker(date, label = Some("Date")),
      numberInput(num, min = Some(0), max = Some(10))
    )
    val view = Toolkit.lower(tree, theme).asInstanceOf[View.Element]
    view.children.length shouldBe 5
    collectTag(view, "select").length    shouldBe 1
    collectTag(view, "textarea").length  shouldBe 1
    collectTag(view, "input").filter(_.attrs("type") == AttrValue.Str("date"))
      .length shouldBe 1
    collectTag(view, "input").filter(_.attrs("type") == AttrValue.Str("number"))
      .length shouldBe 1
    collectTag(view, "input").filter(_.attrs.get("type") ==
      Some(AttrValue.Str("radio"))).length shouldBe 2

  test("FormInputs: dark theme picks up dark background colour"):
    val v = new ReactiveSignal[String]("d", "")
    val light = Toolkit.lower(DatePickerNode(v), Theme.default)
    val dark  = Toolkit.lower(DatePickerNode(v), Theme.dark)
    val lightStyle = collectTag(light, "input").head.attrs("style")
      .asInstanceOf[AttrValue.Str].value
    val darkStyle  = collectTag(dark, "input").head.attrs("style")
      .asInstanceOf[AttrValue.Str].value
    lightStyle should include (Theme.default.colors.background)
    darkStyle  should include (Theme.dark.colors.background)
    lightStyle should not equal darkStyle

  // ─── helpers ──────────────────────────────────────────────────

  private def collectTag(v: View[?], tag: String): Seq[View.Element] = v match
    case e @ View.Element(t, _, _, kids) =>
      val here = if t == tag then Seq(e) else Seq.empty
      here ++ kids.flatMap(collectTag(_, tag))
    case _ => Seq.empty

  private def extractText(v: View[?]): String = v match
    case View.Element(_, _, _, kids) => kids.map(extractText).mkString
    case View.TextNode(thunk)        =>
      try thunk() catch case _: Throwable => ""
    case _                            => ""
