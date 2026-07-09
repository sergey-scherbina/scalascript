package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsGen

import java.nio.charset.StandardCharsets

class JsRuntimeKeyedForTest extends AnyFunSuite:

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def writeTempJs(prefix: String, js: String): java.io.File =
    val tmp = java.io.File.createTempFile(prefix, ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    tmp

  private def runNode(js: String): String =
    assume(hasNode, "node not available")
    val tmp = writeTempJs("ssc-js-keyed-for-", js)
    val r = ProcTestUtil.runCaptured(Seq("node", tmp.getAbsolutePath))
    assert(r.exit == 0, s"node run failed (${r.exit}):\n${r.err}")
    r.out

  test("signals runtime keyed-for reorders, removes, inserts, and preserves row identity"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Signals))
    val script =
      runtime + "\n" +
      """
        |function assertRuntime(c, m) { if (!c) throw new Error(m); }
        |
        |class TextNode {
        |  constructor(text) { this.nodeType = 3; this.parentNode = null; this._text = String(text || ''); }
        |  get textContent() { return this._text; }
        |  set textContent(v) { this._text = String(v == null ? '' : v); }
        |}
        |class Element {
        |  constructor(tag) {
        |    this.nodeType = 1; this.tagName = String(tag).toUpperCase(); this.parentNode = null;
        |    this.attributes = Object.create(null); this.childNodes = []; this.listeners = Object.create(null); this.style = {};
        |  }
        |  get children() { return this.childNodes.filter(n => n && n.nodeType === 1); }
        |  get firstChild() { return this.childNodes[0] || null; }
        |  appendChild(n) {
        |    if (n.parentNode) n.parentNode.removeChild(n);
        |    this.childNodes.push(n); n.parentNode = this; return n;
        |  }
        |  removeChild(n) {
        |    const i = this.childNodes.indexOf(n);
        |    if (i >= 0) { this.childNodes.splice(i, 1); n.parentNode = null; }
        |    return n;
        |  }
        |  remove() { if (this.parentNode) this.parentNode.removeChild(this); }
        |  setAttribute(k, v) { this.attributes[String(k)] = String(v); if (k === 'style') this.style.cssText = String(v); }
        |  getAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attributes, String(k)) ? this.attributes[String(k)] : null; }
        |  removeAttribute(k) { delete this.attributes[String(k)]; }
        |  addEventListener(name, fn) { (this.listeners[name] = this.listeners[name] || []).push(fn); }
        |  click() { (this.listeners.click || []).forEach(fn => fn({ target: this })); }
        |  matches(sel) {
        |    const m = String(sel).match(/^\[([^=\]]+)(?:="([^"]*)")?\]$/);
        |    if (!m) return false;
        |    const v = this.getAttribute(m[1]);
        |    return m[2] === undefined ? v !== null : v === m[2];
        |  }
        |  querySelectorAll(sel) {
        |    const out = [];
        |    const walk = n => {
        |      (n.children || []).forEach(ch => { if (ch.matches(sel)) out.push(ch); walk(ch); });
        |    };
        |    walk(this);
        |    return out;
        |  }
        |  get textContent() { return this.childNodes.map(n => n.textContent).join(''); }
        |  set textContent(v) { this.childNodes = [new TextNode(v)]; this.childNodes[0].parentNode = this; }
        |  set innerHTML(html) { this.childNodes = parseNodes(String(html || ''), this); }
        |  get innerHTML() { return this.textContent; }
        |}
        |function parseAttrs(raw, el) {
        |  const re = /([A-Za-z0-9_-]+)="([^"]*)"/g; let m;
        |  while ((m = re.exec(raw || '')) !== null) el.setAttribute(m[1], m[2].replace(/&quot;/g, '"').replace(/&amp;/g, '&'));
        |}
        |function parseNodes(html, parent) {
        |  const root = new Element('root'); const stack = [root];
        |  const re = /<([^>]+)>|([^<]+)/g; let m;
        |  while ((m = re.exec(html)) !== null) {
        |    if (m[2]) { const t = new TextNode(m[2].replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')); stack[stack.length - 1].appendChild(t); continue; }
        |    const tag = m[1];
        |    if (tag[0] === '/') { stack.pop(); continue; }
        |    const mm = tag.match(/^([A-Za-z0-9_-]+)(.*)$/);
        |    if (!mm) continue;
        |    const el = new Element(mm[1]); parseAttrs(mm[2], el); stack[stack.length - 1].appendChild(el);
        |    if (!/\/$/.test(tag) && !['input','br','hr','img','meta','link'].includes(mm[1])) stack.push(el);
        |  }
        |  root.childNodes.forEach(n => n.parentNode = parent);
        |  return root.childNodes;
        |}
        |const body = new Element('body');
        |globalThis.document = {
        |  body,
        |  createElement: tag => new Element(tag),
        |  createTextNode: text => new TextNode(text),
        |  querySelectorAll: sel => body.querySelectorAll(sel)
        |};
        |globalThis.window = { addEventListener: function(){}, location: { hash: '' } };
        |globalThis.fetch = function() { return Promise.resolve({ ok: true, text: () => Promise.resolve('') }); };
        |
        |const items = _ssc_ui_signal('items', [{id:'a', label:'A'}, {id:'b', label:'B'}]);
        |const selected = _ssc_ui_signal('selected', '');
        |function row(item) {
        |  return _ssc_ui_element('button',
        |    {'data-row': item.id},
        |    new Map([['click', _ssc_ui_setSignal(selected, item.id)]]),
        |    [_ssc_ui_textNode(item.label)]);
        |}
        |function action(name, rows) {
        |  return _ssc_ui_element('button',
        |    {'data-action': name},
        |    new Map([['click', _ssc_ui_setSignal(items, rows)]]),
        |    [_ssc_ui_textNode(name)]);
        |}
        |const view = _ssc_ui_fragment([
        |  action('reorder', [{id:'b', label:'B'}, {id:'a', label:'A'}, {id:'c', label:'C'}]),
        |  action('remove',  [{id:'a', label:'A'}, {id:'c', label:'C'}]),
        |  action('insert',  [{id:'c', label:'C'}, {id:'a', label:'A'}, {id:'d', label:'D'}]),
        |  _ssc_ui_forKeyedView(items, item => item.id, row)
        |]);
        |const out = _ssc_ui_renderBody(view);
        |body.innerHTML = out.body;
        |_ssc_ui_mount(out.sigs, out.keyed);
        |
        |const keyed = body.querySelectorAll('[data-ssc-forkeyed="0"]')[0];
        |const keys = () => keyed.children.map(ch => ch.getAttribute('data-ssc-key')).join(',');
        |const rowButton = key => keyed.children.find(ch => ch.getAttribute('data-ssc-key') === key).querySelectorAll('[data-row]')[0];
        |const byAction = name => body.querySelectorAll('[data-action="' + name + '"]')[0];
        |
        |assertRuntime(keys() === 'a,b', 'initial order: ' + keys());
        |const aNode = keyed.children[0], bNode = keyed.children[1];
        |byAction('reorder').click();
        |assertRuntime(keys() === 'b,a,c', 'after reorder/insert: ' + keys());
        |assertRuntime(keyed.children[0] === bNode, 'b node identity lost on move');
        |assertRuntime(keyed.children[1] === aNode, 'a node identity lost on move');
        |rowButton('a').click();
        |assertRuntime(selected.get() === 'a', 'moved row button did not fire');
        |const cNode = keyed.children[2];
        |byAction('remove').click();
        |assertRuntime(keys() === 'a,c', 'after remove: ' + keys());
        |assertRuntime(bNode.parentNode === null, 'removed b node still attached');
        |assertRuntime(keyed.children[0] === aNode && keyed.children[1] === cNode, 'survivor identity lost after remove');
        |byAction('insert').click();
        |assertRuntime(keys() === 'c,a,d', 'after second insert: ' + keys());
        |assertRuntime(keyed.children[0] === cNode && keyed.children[1] === aNode, 'survivor identity lost after second insert');
        |rowButton('d').click();
        |assertRuntime(selected.get() === 'd', 'inserted row button did not bind');
        |console.log('keyed-for-ok');
        |""".stripMargin

    assert(runNode(script) == "keyed-for-ok")

