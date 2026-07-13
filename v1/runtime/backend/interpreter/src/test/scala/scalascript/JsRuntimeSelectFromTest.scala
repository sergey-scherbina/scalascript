package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsGen

import java.nio.charset.StandardCharsets

/** Proves `selectFromView`'s reactive <option> reconciliation is REAL — not
 *  just "compiles" — by running the actual `signals.mjs` runtime
 *  (`JsGen.generateRuntime`) under real Node against a minimal DOM mock,
 *  mirroring `JsRuntimeKeyedForTest`'s method for the general
 *  `forKeyedView` mechanism. See specs/std-ui-select.md §
 *  "Reactive options (selectFrom)". */
class JsRuntimeSelectFromTest extends AnyFunSuite:

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def writeTempJs(prefix: String, js: String): java.io.File =
    val tmp = java.io.File.createTempFile(prefix, ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    tmp

  private def runNode(js: String): String =
    assume(hasNode, "node not available")
    val tmp = writeTempJs("ssc-js-select-from-", js)
    val r = ProcTestUtil.runCaptured(Seq("node", tmp.getAbsolutePath))
    assert(r.exit == 0, s"node run failed (${r.exit}):\n${r.err}")
    r.out

  test("signals runtime selectFromView reconciles <option> children on a list change, preserving DOM identity"):
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
        |  // Mimics a real <option>'s `.value` IDL property: reflects the `value`
        |  // content attribute until explicitly assigned via the property setter
        |  // (which is how a real browser's HTMLOptionElement.value behaves, and
        |  // how selectFromView's own JS-created <option>s -- via `opt.value = ...`
        |  // in _mountSelectFrom's makeOption -- differ from the server-rendered
        |  // ones, which only ever set the `value` attribute).
        |  get value() { return this._value !== undefined ? this._value : (this.getAttribute('value') || ''); }
        |  set value(v) { this._value = String(v == null ? '' : v); }
        |  setAttribute(k, v) { this.attributes[String(k)] = String(v); if (k === 'style') this.style.cssText = String(v); }
        |  getAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attributes, String(k)) ? this.attributes[String(k)] : null; }
        |  removeAttribute(k) { delete this.attributes[String(k)]; }
        |  addEventListener(name, fn) { (this.listeners[name] = this.listeners[name] || []).push(fn); }
        |  click() { (this.listeners.click || []).forEach(fn => fn({ target: this })); }
        |  input() { (this.listeners.input || []).forEach(fn => fn({ target: this })); }
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
        |const items = _ssc_ui_signal('items', [{id:'a', title:'Alpha'}, {id:'b', title:'Beta'}]);
        |const selected = _ssc_ui_signal('selected', 'b');
        |function action(name, rows) {
        |  return _ssc_ui_element('button',
        |    {'data-action': name},
        |    new Map([['click', _ssc_ui_setSignal(items, rows)]]),
        |    [_ssc_ui_textNode(name)]);
        |}
        |const selectView = _ssc_ui_selectFromView(
        |  items, item => item.id, item => [item.id, item.title], selected,
        |  '', '', false);
        |const view = _ssc_ui_fragment([
        |  action('append',  [{id:'a', title:'Alpha'}, {id:'b', title:'Beta'}, {id:'c', title:'Gamma'}]),
        |  action('reorder', [{id:'b', title:'Beta'}, {id:'a', title:'Alpha'}, {id:'c', title:'Gamma'}]),
        |  action('remove',  [{id:'a', title:'Alpha'}, {id:'c', title:'Gamma'}]),
        |  selectView
        |]);
        |const out = _ssc_ui_renderBody(view);
        |body.innerHTML = out.body;
        |_ssc_ui_mount(out.sigs, out.keyed);
        |
        |const sel = body.querySelectorAll('[data-ssc-forkeyed-options="0"]')[0];
        |const keys = () => sel.children.map(ch => ch.getAttribute('data-ssc-key')).join(',');
        |const values = () => sel.children.map(ch => ch.value).join(',');
        |const byAction = name => body.querySelectorAll('[data-action="' + name + '"]')[0];
        |
        |// Initial render: N=2 options, in list order, tag name really is OPTION
        |// (proving selectFromView built real <option> elements, not some other
        |// shape), and the tag itself carries the reconcile marker attribute
        |// (not a wrapper -- <select>'s content model has nowhere else for it).
        |assertRuntime(sel.tagName === 'SELECT', 'container is not a <select>: ' + sel.tagName);
        |assertRuntime(sel.children.every(ch => ch.tagName === 'OPTION'), 'children are not <option>s');
        |assertRuntime(keys() === 'a,b', 'initial keys: ' + keys());
        |assertRuntime(values() === 'a,b', 'initial values: ' + values());
        |const aNode = sel.children[0], bNode = sel.children[1];
        |
        |// N -> N+1: append a third item (busi's "owner signs a new contract" case).
        |// The proof that matters: this is NOT a teardown+rebuild -- a and b keep
        |// their exact DOM node identity, only a new node is added.
        |byAction('append').click();
        |assertRuntime(keys() === 'a,b,c', 'after append (want 3 options): ' + keys());
        |assertRuntime(sel.children.length === 3, 'expected 3 <option>s after append, got ' + sel.children.length);
        |assertRuntime(sel.children[0] === aNode, 'a node identity lost on append');
        |assertRuntime(sel.children[1] === bNode, 'b node identity lost on append');
        |const cNode = sel.children[2];
        |
        |// Reorder: b,a,c -- identity preserved across a move, not just a value match.
        |byAction('reorder').click();
        |assertRuntime(keys() === 'b,a,c', 'after reorder: ' + keys());
        |assertRuntime(sel.children[0] === bNode, 'b node identity lost on reorder');
        |assertRuntime(sel.children[1] === aNode, 'a node identity lost on reorder');
        |assertRuntime(sel.children[2] === cNode, 'c node identity lost on reorder');
        |
        |// Remove: b drops out -- its node is actually detached, survivors keep identity.
        |byAction('remove').click();
        |assertRuntime(keys() === 'a,c', 'after remove: ' + keys());
        |assertRuntime(bNode.parentNode === null, 'removed b <option> still attached');
        |assertRuntime(sel.children[0] === aNode && sel.children[1] === cNode, 'survivor identity lost after remove');
        |
        |// Selection round-trip: selecting a different option still writes back
        |// to the bound `selected` signal (same inputChange wiring as the base
        |// select()) -- reactive options didn't regress the two-way binding.
        |sel.value = 'c';
        |sel.input();
        |assertRuntime(selected.get() === 'c', 'selecting an option did not update the selected signal');
        |
        |console.log('select-from-ok');
        |""".stripMargin

    assert(runNode(script) == "select-from-ok")
