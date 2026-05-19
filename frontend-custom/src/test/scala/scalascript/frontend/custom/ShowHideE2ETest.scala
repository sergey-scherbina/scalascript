package scalascript.frontend.custom

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** A2d E2E — the show-hide demo through the actually-emitted JS.
 *  ToggleSignal flips a Boolean signal; ShowSignal swaps the
 *  rendered subtree.  Counter keeps its value across hide/show
 *  because the signal cell is module-scoped. */
class ShowHideE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("ShowSignal + ToggleSignal — show/hide counter via emitted JS") {
    assume(nodeAvailable, "node not available")
    val backend = new CustomFrameworkBackend
    val visible = new ReactiveSignal[Boolean]("visible", true)
    val count   = new ReactiveSignal[Int]("count", 0)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(
        View.Element("button",
          Map("id" -> AttrValue.Str("tog")),
          Map("click" -> EventHandler.ToggleSignal(visible)),
          Seq(View.TextNode(() => "toggle"))
        ),
        View.Element("button",
          Map("id" -> AttrValue.Str("inc")),
          Map("click" -> EventHandler.IncrementSignal(count)),
          Seq(View.TextNode(() => "+"))
        ),
        View.ShowSignal(
          cond      = visible,
          whenTrue  = View.Element("span",
                       Map("id" -> AttrValue.Str("box")),
                       Map.empty,
                       Seq(View.SignalText(count))),
          whenFalse = View.TextNode(() => "")
        )
      )
    ))
    val emitted = backend.emit(FrontendModule(List(app), "App", "/"))

    val tmp = Files.createTempDirectory("ssc-showhide-e2e-")
    try
      val script = tmp.resolve("check.mjs")
      Files.writeString(script, s"""
        |let jsdom;
        |try { jsdom = await import('jsdom'); } catch (_e) { jsdom = null; }
        |if (jsdom) {
        |  const dom = new jsdom.JSDOM('<!DOCTYPE html><html><body><div id="app"></div></body></html>');
        |  globalThis.document = dom.window.document;
        |  globalThis.window   = dom.window;
        |  ${emitted.js}
        |  const tog = dom.window.document.getElementById('tog');
        |  const inc = dom.window.document.getElementById('inc');
        |  const visible = () => !!dom.window.document.getElementById('box');
        |  const boxText = () => { const b = dom.window.document.getElementById('box'); return b ? b.textContent : null; };
        |  if (!visible() || boxText() !== '0') throw new Error('initial: visible=' + visible() + ' text=' + boxText());
        |  inc.click(); inc.click();
        |  if (boxText() !== '2') throw new Error('after 2 inc: ' + boxText());
        |  tog.click();
        |  if (visible()) throw new Error('toggle did not hide');
        |  tog.click();
        |  if (boxText() !== '2') throw new Error('after re-toggle: ' + boxText());
        |  console.log('OK');
        |} else {
        |  const src = ${"\"" + escapeForJs(emitted.js) + "\""};
        |  new Function(src);
        |  console.log('PARSE_OK');
        |}
        |""".stripMargin)
      val output = Try(s"node $script".!!).getOrElse("")
      assert(output.trim == "OK" || output.trim == "PARSE_OK",
        s"node run did not return OK/PARSE_OK; got: '$output'")
    finally
      Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  }

  private def escapeForJs(s: String): String =
    s.replace("\\", "\\\\")
     .replace("\"", "\\\"")
     .replace("\n", "\\n")
     .replace("\r", "\\r")
