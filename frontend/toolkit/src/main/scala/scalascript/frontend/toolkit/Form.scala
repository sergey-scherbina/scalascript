package scalascript.frontend.toolkit

import scala.collection.mutable
import scalascript.frontend.{Signal, ReactiveSignal, View, AttrValue, EventHandler}

/** v1.18 / Phase B — `Form` widget with built-in validation.
 *
 *  Centralises the four concerns every real form ends up wiring by
 *  hand: per-field reactive value cells, per-field error state, a
 *  submit gate that runs all validators before invoking user code,
 *  and a "submitting" flag for disabling controls during async work.
 *
 *  Usage from user code:
 *  {{{
 *    Tk.form(onSubmit = ctx => api.create(ctx.values())) { form =>
 *      val name  = form.field[String]("name",  "", Validators.required)
 *      val email = form.field[String]("email", "",
 *        Validators.and(Validators.required, Validators.email))
 *
 *      Tk.vstack(gap = 8)(
 *        Tk.textField(value = name.value,  label = Some("Name"),
 *                     error = Some(name.error)),
 *        Tk.textField(value = email.value, label = Some("Email"),
 *                     error = Some(email.error)),
 *        Tk.button("Create", onClick = () => (), formSubmit = true)
 *      )
 *    }
 *  }}}
 *
 *  Like every other toolkit widget, the form lowers via
 *  `Toolkit.lower(node, theme)` to a backend-agnostic `View` —
 *  here a real HTML `<form>` element whose `submit` listener
 *  runs `validate() && onSubmit(ctx)`. */

// ─── FormField + FormContext ────────────────────────────────────────

/** A single registered form field — its name, its reactive value
 *  cell, its reactive error cell, and the pure validator used to
 *  populate the error on `validate()`.  Field instances are returned
 *  by `FormContext.field(...)` and embedded into user widgets such
 *  as `TextField(value = field.value, error = Some(field.error))`. */
final case class FormField[T](
  name:     String,
  value:    Signal[T],
  error:    Signal[Option[String]],
  validate: T => Option[String]
):
  /** Snapshot of the field's default value.  Captured at field
   *  registration time; used by `FormContext.reset()`. */
  private[toolkit] var default: T = value()

/** Mutable per-form state container.  Lives for the lifetime of a
 *  single rendered `FormNode`; user code receives it inside the
 *  `build` callback and uses it to register fields and (optionally)
 *  read submit status.
 *
 *  All fields are keyed by `name` and registration is idempotent —
 *  calling `ctx.field("email", ...)` twice with the same key returns
 *  the same `FormField` instance (the second call's `default` /
 *  `validate` arguments are ignored). */
final class FormContext:
  /** Registered fields, keyed by `name`.  Insertion-ordered so that
   *  `values()` / `validate()` traverse in declaration order. */
  val fields: mutable.LinkedHashMap[String, FormField[?]] =
    mutable.LinkedHashMap.empty

  /** True while an async submit is in flight.  The form lowering
   *  flips this signal to `true` before invoking `onSubmit` and back
   *  to `false` in a `finally`.  Bind to `Button.disabled` to
   *  prevent double-submit. */
  val submitting: Signal[Boolean] =
    new ReactiveSignal[Boolean]("form_submitting", false)

  /** Form-level error message (network failure, server-rejected
   *  payload, etc.).  User code sets via `ctx.globalError.set(...)`
   *  inside the `onSubmit` callback. */
  val globalError: Signal[Option[String]] =
    new ReactiveSignal[Option[String]]("form_global_error", None)

  /** Register (or re-fetch) a field by name.  Idempotent: subsequent
   *  calls with the same `name` return the previously registered
   *  field, ignoring the `default` and `validate` arguments. */
  def field[T](
    name:     String,
    default:  T,
    validate: T => Option[String] = (_: T) => None
  ): FormField[T] =
    fields.get(name) match
      case Some(existing) => existing.asInstanceOf[FormField[T]]
      case None =>
        val valueCell = new ReactiveSignal[T](s"form_field_${name}_value",  default)
        val errorCell = new ReactiveSignal[Option[String]](s"form_field_${name}_error", None)
        val f = FormField[T](name, valueCell, errorCell, validate)
        f.default = default
        fields(name) = f
        f

  /** Snapshot of the current value of every registered field. */
  def values(): Map[String, Any] =
    fields.iterator.map { case (k, f) => k -> f.value() }.toMap

  /** Run every field's validator, populate its error signal, and
   *  return whether every field passed.  Always touches every
   *  field's error signal — passing fields get their error cleared
   *  to `None`.  Form-level `globalError` is left untouched. */
  def validate(): Boolean =
    var allOk = true
    fields.values.foreach { f =>
      val typed = f.asInstanceOf[FormField[Any]]
      val err   = typed.validate(typed.value())
      typed.error.set(err)
      if err.isDefined then allOk = false
    }
    allOk

  /** Reset every field's value to its registration-time default and
   *  clear every error signal (including `globalError`). */
  def reset(): Unit =
    fields.values.foreach { f =>
      val typed = f.asInstanceOf[FormField[Any]]
      typed.value.set(typed.default)
      typed.error.set(None)
    }
    globalError.set(None)

// ─── FormNode + lowering ────────────────────────────────────────────

/** The form container.  Lowering wires the user-supplied `onSubmit`
 *  to the HTML `<form>` element's submit listener and gates it on
 *  `ctx.validate()`.  The `build` callback receives a fresh
 *  `FormContext` and returns whatever toolkit subtree the form body
 *  should contain (typically a `Stack` of inputs + a submit button). */
final case class FormNode(
  onSubmit: FormContext => Unit,
  build:    FormContext => ToolkitNode,
  /** Optional pre-constructed `FormContext`.  Defaults to a fresh
   *  one per lowering.  Passing in your own lets test or driver code
   *  observe the registered fields + drive submit / reset from
   *  outside the rendered tree. */
  ctx:      Option[FormContext] = None
) extends ToolkitNode

object FormNode:
  def lower(n: FormNode, theme: Theme): View[?] =
    val ctx  = n.ctx.getOrElse(new FormContext)
    val body = Toolkit.lower(n.build(ctx), theme)

    // The submit listener: cancel default browser submit, set the
    // submitting flag, run validation, invoke user `onSubmit` only
    // if validation passed, and always clear `submitting` in the
    // finally block.  `EventHandler.WithEvent` receives the
    // backend's native event object as `Any`; we attempt to call
    // `preventDefault` + `stopPropagation` reflectively so that
    // pure-JVM tests (which pass `null` or a literal) don't blow up.
    val handler = EventHandler.WithEvent { ev =>
      try preventDefault(ev) catch case _: Throwable => ()
      ctx.submitting.set(true)
      try
        if ctx.validate() then n.onSubmit(ctx)
      finally
        ctx.submitting.set(false)
    }

    View.Element(
      tag      = "form",
      attrs    = Map(
        "role"     -> AttrValue.Str("form"),
        "noValidate" -> AttrValue.Bool(true)
      ),
      events   = Map("submit" -> handler),
      children = Seq(body)
    )

  /** Reflectively call `preventDefault` + `stopPropagation` on the
   *  raw event.  Backends pass their framework-native event whose
   *  shape varies; we don't want to depend on a `js.Dynamic` cast
   *  from the toolkit module (it must stay JVM-buildable for SSR).
   *  No-op on null / non-object payloads. */
  private def preventDefault(ev: Any): Unit =
    if ev == null then return
    val cls = ev.getClass
    try
      val m = cls.getMethod("preventDefault")
      m.invoke(ev)
    catch case _: Throwable => ()
    try
      val m = cls.getMethod("stopPropagation")
      m.invoke(ev)
    catch case _: Throwable => ()

// ─── Built-in validators ────────────────────────────────────────────

/** Built-in validator library.  Each validator is a pure function
 *  `T => Option[String]`: `None` means OK, `Some(msg)` means the
 *  field failed and the message goes into the field's error signal.
 *
 *  Combine multiple via `Validators.and(v1, v2, ...)` — the first
 *  validator that fails wins (short-circuits). */
object Validators:

  /** Fails on "empty" values.  The notion of empty is type-driven:
   *    - `String`        → fails if blank (length 0 after trim)
   *    - `Option[X]`     → fails if `None`
   *    - `Boolean`       → fails if `false` (opt-in pattern — "I
   *                        accept the terms" must be true)
   *    - anything else   → fails only if `null`
   *  Returned message is `"required"`. */
  def required[T]: T => Option[String] = { (v: T) =>
    val isEmpty = v match
      case null            => true
      case s: String       => s.trim.isEmpty
      case o: Option[?]    => o.isEmpty
      case b: Boolean      => !b
      case _               => false
    if isEmpty then Some("required") else None
  }

  /** Fails when the input is shorter than `n` characters. */
  def minLength(n: Int): String => Option[String] = (s: String) =>
    if s == null || s.length < n then Some(s"must be at least $n characters")
    else None

  /** Fails when the input is longer than `n` characters. */
  def maxLength(n: Int): String => Option[String] = (s: String) =>
    if s != null && s.length > n then Some(s"must be at most $n characters")
    else None

  /** Fails when the input doesn't match `re`.  `msg` is the error
   *  message returned on failure. */
  def pattern(re: scala.util.matching.Regex, msg: String): String => Option[String] =
    (s: String) => if s == null || re.findFirstIn(s).isEmpty then Some(msg) else None

  /** Simple email validator — pragmatic regex, not RFC 5322.  Good
   *  enough for client-side hints; the server should always re-check. */
  private val emailRe = """^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$""".r
  val email: String => Option[String] =
    pattern(emailRe, "must be a valid email")

  /** Combine validators — first failure wins.  `Validators.and()`
   *  with zero args returns the always-pass validator. */
  def and[T](vs: (T => Option[String])*): T => Option[String] = (v: T) =>
    vs.iterator.map(_(v)).collectFirst { case Some(msg) => msg }
