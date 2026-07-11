package scalascript.compiler.plugin.fetch

import org.scalatest.funsuite.AnyFunSuite

import scalascript.backend.spi.NativeImpl
import scalascript.frontend.{ReactiveSignal, RowPayload}
import scalascript.interpreter.InterpretError
import scalascript.ir.QualifiedName
import scalascript.plugin.api.PluginValue

final class FetchIntrinsicsTest extends AnyFunSuite:
  private def call(name: String, args: List[Any]): Any =
    FetchIntrinsics.table(QualifiedName(name)).asInstanceOf[NativeImpl].eval(null, args)

  test("row payload constructors enforce exact dotted unique names"):
    val field = call("fieldPayload", List("meta.id"))
    assert(field match
      case PluginValue.Foreign("RowPayload", RowPayload.Field("meta.id")) => true
      case _ => false)

    val fields = call("fieldsPayload", List(PluginValue.list(List(
      PluginValue.string("id"), PluginValue.string("meta.name")))))
    assert(fields match
      case PluginValue.Foreign("RowPayload", RowPayload.Fields(List("id", "meta.name"))) => true
      case _ => false)

    val invalid = List(
      () => call("fieldPayload", List("")),
      () => call("fieldPayload", List("meta..id")),
      () => call("fieldsPayload", List(PluginValue.list(Nil))),
      () => call("fieldsPayload", List(PluginValue.list(List(PluginValue.string("id"), PluginValue.string("id"))))),
      () => call("fieldsPayload", List(PluginValue.list(List(PluginValue.string("id"), PluginValue.int(1))))),
      () => call("wholeRowPayload", List("unexpected")))
    invalid.foreach(build => intercept[InterpretError](build()))

  test("row actions revalidate payload and dotted field paths"):
    val tick = PluginValue.foreign("ReactiveSignal", new ReactiveSignal[Int]("tick", 0))
    val target = PluginValue.foreign("ReactiveSignal", new ReactiveSignal[String]("target", ""))
    val malformed = List(
      () => call("rowDeleteAction", List("/items", "", tick)),
      () => call("rowLinkAction", List("Pick", target, "meta..id")),
      () => call("rowEditAction", List("PATCH", "/items", "", tick)),
      () => call("rowPostAction", List("Save", "POST", "/items",
        PluginValue.foreign("RowPayload", RowPayload.Fields(Nil)), tick)),
      () => call("rowPostAction", List("Save", "POST", "/items",
        PluginValue.foreign("RowPayload", RowPayload.Fields(List("id", "id"))), tick)))
    malformed.foreach(build => intercept[InterpretError](build()))
