package scalascript.codegen

/** Reactive-signals JS runtime preamble (fine-grained reactivity).
 *  Extracted verbatim from `JsGen.scala` (jsgen-split-p2); referenced
 *  unqualified as a package-level `val`, keeping emitted JS byte-identical. */
val JsRuntimeSignals: String = """
// ── Reactive signals (fine-grained reactivity) ────────────────────────────
//
// Same push model as the interpreter and JvmGen: signals are mutable
// cells with a subscriber set; reading inside an active `effect`/
// `computed` registers a mutual subscription; writes queue subscribers
// into a `LinkedHashSet` and a single flush drains it, so the diamond
// (root → derived → consumer; consumer also reads root) sees each
// effect at most once per synchronous transaction.

let _signalSeq = 0;
const _signals = new Map();   // id → { value, subs:Set<eid> }
const _effects = new Map();   // eid → { thunk, deps:Set<sid> }
const _effectStack = [];
const _pendingEffects = new Set();  // insertion-ordered in JS Sets
let _reactiveFlushing = false;

function _freshReactiveId() { _signalSeq += 1; return _signalSeq; }

function _signalGet(id) {
  const s = _signals.get(id);
  if (!s) throw new Error('Signal disposed or unknown id');
  if (_effectStack.length > 0) {
    const eid = _effectStack[_effectStack.length - 1];
    s.subs.add(eid);
    const e = _effects.get(eid);
    if (e) e.deps.add(id);
  }
  return s.value;
}

function _signalSet(id, v) {
  const s = _signals.get(id);
  if (!s) throw new Error('Signal disposed or unknown id');
  s.value = v;
  for (const eid of s.subs) {
    if (_effectStack.indexOf(eid) < 0) _pendingEffects.add(eid);
  }
  if (!_reactiveFlushing) _reactiveFlush();
}

function _reactiveFlush() {
  _reactiveFlushing = true;
  try {
    while (_pendingEffects.size > 0) {
      const eid = _pendingEffects.values().next().value;
      _pendingEffects.delete(eid);
      _runEffect(eid);
    }
  } finally { _reactiveFlushing = false; }
}

function _clearEffectDeps(eid) {
  const e = _effects.get(eid);
  if (!e) return;
  for (const sid of e.deps) {
    const s = _signals.get(sid);
    if (s) s.subs.delete(eid);
  }
  e.deps.clear();
}

function _runEffect(eid) {
  const e = _effects.get(eid);
  if (!e) return;
  _clearEffectDeps(eid);
  _effectStack.push(eid);
  try { e.thunk(); }
  finally { _effectStack.pop(); }
}

function Signal(initial) {
  const id = _freshReactiveId();
  _signals.set(id, { value: initial, subs: new Set() });
  return {
    _type: 'Signal',
    id,
    get:   () => _signalGet(id),
    set:   (v) => _signalSet(id, v),
    apply: () => _signalGet(id),
  };
}

function effect(thunk) {
  const eid = _freshReactiveId();
  _effects.set(eid, { thunk, deps: new Set() });
  _runEffect(eid);
}

function computed(thunk) {
  const sid = _freshReactiveId();
  const eid = _freshReactiveId();
  _signals.set(sid, { value: undefined, subs: new Set() });
  const updater = () => _signalSet(sid, thunk());
  _effects.set(eid, { thunk: updater, deps: new Set() });
  _runEffect(eid);
  return {
    _type: 'Signal',
    id: sid,
    get:   () => _signalGet(sid),
    set:   () => { throw new Error('computed signal is read-only'); },
    apply: () => _signalGet(sid),
  };
}

// ── Node.js stubs for std/ui/primitives.ssc extern defs ───────────────────
// Provide real implementations for run-js mode so extern def symbols
// are non-undefined when extracted from std.ui.primitives namespace.
function _ssc_ui_signal(name, initial) { return Signal(initial); }
function _ssc_ui_element(tag, attrs, events, children) {
  return { _type: '_Element', tag,
    attrs: (attrs instanceof Map) ? Object.fromEntries(attrs) : (attrs || {}),
    events: (events instanceof Map) ? Object.fromEntries(events) : (events || {}),
    children: children || [] };
}
function _ssc_ui_textNode(s) { return { _type: '_TextNode', s }; }
function _ssc_ui_signalText(sig) { return { _type: '_SignalText', sig }; }
function _ssc_ui_showSignal(cond, whenTrue, whenFalse) { return { _type: '_ShowSignal', cond, whenTrue, whenFalse }; }
function _ssc_ui_fragment(children) { return { _type: '_Fragment', children: children || [] }; }
function _ssc_ui_setSignal(s, v) { return { _type: '_SetSignal', s, v }; }
function _ssc_ui_inputChange(s) { return { _type: '_InputChange', s }; }
function _ssc_ui_toggleSignal(s) { return { _type: '_ToggleSignal', s }; }
function _ssc_ui_eqSignal(s, value) { return computed(() => (s && s.get) ? s.get() === value : false); }
function _ssc_ui_hashSignal() { return Signal(''); }
function _ssc_ui_emit(tree, outDir) {}

// Walk the View IR tree and produce a static HTML string + a Map of signal
// ids to their current values.  Split from _ssc_ui_renderPage so that the
// BrowserPatch's serve can call _ssc_ui_mount(sigs) directly — avoiding
// eval() or DOM <script> injection, both blocked by script-src 'self' CSP.
function _ssc_ui_renderBody(view) {
  const _esc  = s => String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  const _escT = s => String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  const sigs  = new Map(); // Signal id -> initial value (collected during walk)
  const voids = new Set(['br','hr','img','input','link','meta','area','base','col','embed','param','source','track','wbr']);

  function collectSig(s) {
    if (s && s._type === 'Signal' && !sigs.has(s.id))
      sigs.set(s.id, s.get());
  }

  function walk(v) {
    if (v === null || v === undefined) return '';
    if (typeof v === 'string') return _escT(v);
    if (typeof v !== 'object') return String(v);
    switch (v._type) {
      case '_Element': {
        const tag    = v.tag || 'div';
        const attrs  = v.attrs  || {};
        const events = v.events || {};
        let   aStr   = '';
        for (const [k, val] of Object.entries(attrs)) {
          const r = (val && typeof val === 'object' && typeof val.get === 'function') ? (collectSig(val), val.get()) : val;
          if (r !== undefined && r !== null && r !== false)
            aStr += ` ${k}="${_esc(r)}"`;
        }
        for (const [, h] of Object.entries(events)) {
          if (!h || typeof h !== 'object') continue;
          if (h._type === '_ToggleSignal' && h.s) { collectSig(h.s); aStr += ` data-ssc-toggle="${h.s.id}"`; }
          else if (h._type === '_SetSignal'  && h.s) { collectSig(h.s); aStr += ` data-ssc-set="${h.s.id}" data-ssc-set-val="${_esc(JSON.stringify(h.v))}"`; }
          else if (h._type === '_InputChange' && h.s) { collectSig(h.s); aStr += ` data-ssc-change="${h.s.id}"`; }
          else if ((h._type === '_FetchAction' || h._type === '_FetchActionClear') && h.url) {
            if (h.body) collectSig(h.body); if (h.tick) collectSig(h.tick);
            const bId = (h.body && h.body.id) ? h.body.id : '';
            const tId = (h.tick && h.tick.id) ? h.tick.id : '';
            aStr += ` data-ssc-fetch-method="${_esc(h.method||'POST')}" data-ssc-fetch-url="${_esc(h.url)}" data-ssc-fetch-body="${_esc(bId)}" data-ssc-fetch-tick="${_esc(tId)}"`;
            if (h._type === '_FetchActionClear') aStr += ` data-ssc-fetch-clear="1"`;
          }
        }
        const kids = (v.children || []).map(walk).join('');
        return voids.has(tag) ? `<${tag}${aStr}>` : `<${tag}${aStr}>${kids}</${tag}>`;
      }
      case '_TextNode':   return walk(v.s);
      case '_SignalText': {
        collectSig(v.sig);
        const id  = v.sig && v.sig.id;
        const txt = _escT(v.sig && v.sig.get ? String(v.sig.get()) : '');
        return id ? `<span data-ssc-text="${id}">${txt}</span>` : txt;
      }
      case '_ShowSignal': {
        collectSig(v.cond);
        const id   = v.cond && v.cond.id;
        const show = v.cond && v.cond.get ? v.cond.get() : false;
        const tHtml = walk(v.whenTrue);
        const fHtml = walk(v.whenFalse);
        if (!id) return show ? tHtml : fHtml;
        const tStyle = show ? ' style="display:contents"' : ' style="display:none"';
        const fStyle = show ? ' style="display:none"' : ' style="display:contents"';
        return `<span data-ssc-cond="${id}" style="display:contents"><span data-ssc-branch="true"${tStyle}>${tHtml}</span><span data-ssc-branch="false"${fStyle}>${fHtml}</span></span>`;
      }
      case '_Fragment':      return (v.children || []).map(walk).join('');
      case '_FetchTableView': {
        if (v.tick) collectSig(v.tick);
        const ftTick = (v.tick && v.tick.id) ? v.tick.id : '';
        return `<div data-ssc-fetch-table="${_esc(v.fetchUrl)}" data-ssc-fetch-delete="${_esc(v.deleteUrl||'')}" data-ssc-fetch-tick="${_esc(ftTick)}" style="overflow-x:auto"></div>`;
      }
      default: return '';
    }
  }

  const body = walk(view);
  return { body, sigs };
}

// Set up DOM reactivity after _ssc_ui_renderBody has been injected into the
// page.  Called directly from the BrowserPatch serve (no eval / no DOM script
// injection) with the sigs Map returned by _ssc_ui_renderBody.
function _ssc_ui_mount(sigs) {
  var _sv = {};
  sigs.forEach(function(v, id) { _sv[String(id)] = v; });
  var _sb = {};
  function _sub(id, fn) { (_sb[id] = _sb[id] || []).push(fn); fn(_sv[id]); }
  function _set(id, v) { _sv[id] = v; (_sb[id] || []).forEach(function(fn){ fn(v); }); }
  // show/hide branches
  document.querySelectorAll('[data-ssc-cond]').forEach(function(el) {
    var id = el.getAttribute('data-ssc-cond');
    var tBranch = el.querySelector('[data-ssc-branch="true"]');
    var fBranch = el.querySelector('[data-ssc-branch="false"]');
    _sub(id, function(v) {
      if (tBranch) tBranch.style.display = v ? 'contents' : 'none';
      if (fBranch) fBranch.style.display = v ? 'none' : 'contents';
    });
  });
  // signal text spans
  document.querySelectorAll('[data-ssc-text]').forEach(function(el) {
    var id = el.getAttribute('data-ssc-text');
    _sub(id, function(v) { el.textContent = v == null ? '' : String(v); });
  });
  // checkbox toggle
  document.querySelectorAll('[data-ssc-toggle]').forEach(function(el) {
    var id = el.getAttribute('data-ssc-toggle');
    el.addEventListener('change', function() { _set(id, el.checked); });
    _sub(id, function(v) { el.checked = !!v; });
  });
  // button setSignal
  document.querySelectorAll('[data-ssc-set]').forEach(function(el) {
    var id  = el.getAttribute('data-ssc-set');
    var val = JSON.parse(el.getAttribute('data-ssc-set-val'));
    el.addEventListener('click', function() { _set(id, val); });
  });
  // text input change
  document.querySelectorAll('[data-ssc-change]').forEach(function(el) {
    var id = el.getAttribute('data-ssc-change');
    el.addEventListener('input', function() { _set(id, el.value); });
    _sub(id, function(v) { el.value = v == null ? '' : String(v); });
  });
  // fetch action buttons (fetchAction / fetchActionClear)
  document.querySelectorAll('[data-ssc-fetch-url]').forEach(function(el) {
    var method = el.getAttribute('data-ssc-fetch-method') || 'POST';
    var url    = el.getAttribute('data-ssc-fetch-url');
    var bodyId = el.getAttribute('data-ssc-fetch-body');
    var tickId = el.getAttribute('data-ssc-fetch-tick');
    var clear  = el.getAttribute('data-ssc-fetch-clear');
    el.addEventListener('click', function() {
      var body = bodyId ? String(_sv[bodyId] == null ? '' : _sv[bodyId]) : '';
      fetch(url, {method: method, body: body})
        .then(function(r) { return r.text(); })
        .then(function() {
          if (tickId) _set(tickId, ((_sv[tickId] || 0) | 0) + 1);
          if (clear && bodyId) _set(bodyId, '');
        });
    });
  });
  // fetch tables (fetchTableView)
  document.querySelectorAll('[data-ssc-fetch-table]').forEach(function(container) {
    var fetchUrl  = container.getAttribute('data-ssc-fetch-table');
    var deleteUrl = container.getAttribute('data-ssc-fetch-delete');
    var tickId    = container.getAttribute('data-ssc-fetch-tick');
    var cs = window.getComputedStyle(container);
    var fs = cs.fontSize; var ff = cs.fontFamily;
    var thStyle  = 'text-align:left;padding:6px 12px;border-bottom:2px solid #e5e7eb;font-weight:600;color:#111827;font-size:'+fs+';font-family:'+ff;
    var tdStyle  = 'padding:6px 12px;border-bottom:1px solid #e5e7eb;color:#374151;vertical-align:middle;font-size:'+fs+';font-family:'+ff;
    var btnStyle = 'background:#ef4444;color:#fff;border:none;padding:6px 16px;border-radius:4px;cursor:pointer;font-size:'+fs+';font-family:'+ff;
    function renderTable(rows) {
      container.innerHTML = '';
      var tbl = document.createElement('table');
      tbl.setAttribute('style', 'border-collapse:collapse;width:100%;font-family:'+ff+';font-size:'+fs);
      var thead = document.createElement('thead'); thead.setAttribute('style', 'background:#f9fafb');
      var trH = document.createElement('tr');
      var th1 = document.createElement('th'); th1.setAttribute('style', thStyle); th1.textContent = 'Task'; trH.appendChild(th1);
      var th2 = document.createElement('th'); th2.setAttribute('style', thStyle); th2.textContent = ''; trH.appendChild(th2);
      thead.appendChild(trH); tbl.appendChild(thead);
      var tbody = document.createElement('tbody');
      (rows || []).forEach(function(row) {
        var tr = document.createElement('tr');
        var td1 = document.createElement('td'); td1.setAttribute('style', tdStyle); td1.textContent = String(row.text); tr.appendChild(td1);
        var td2 = document.createElement('td'); td2.setAttribute('style', tdStyle);
        var btn = document.createElement('button'); btn.setAttribute('style', btnStyle); btn.textContent = 'Delete';
        btn.addEventListener('click', function() {
          fetch(deleteUrl, {method: 'POST', body: String(row.id)}).then(function(r) { return r.text(); })
            .then(function() { if (tickId) _set(tickId, ((_sv[tickId] || 0) | 0) + 1); });
        });
        td2.appendChild(btn); tr.appendChild(td2); tbody.appendChild(tr);
      });
      tbl.appendChild(tbody); container.appendChild(tbl);
    }
    function doFetch() { fetch(fetchUrl).then(function(r) { return r.json(); }).then(renderTable); }
    doFetch();
    if (tickId) _sub(tickId, function(t) { if ((t | 0) > 0) doFetch(); });
  });
}

// Backward-compat wrapper: walk the view and return { body, script } where
// script is an inline <script> IIFE — used by _ssc_ui_serve for server-rendered pages.
function _ssc_ui_renderPage(view) {
  const { body, sigs } = _ssc_ui_renderBody(view);
  const sigJson = JSON.stringify(Object.fromEntries(sigs));
  const script = `<script>_ssc_ui_mount(new Map(Object.entries(${sigJson})));</script>`;
  return { body, script };
}

// Handles both serve(port) [port-only] and serve(view, port[, extraCss]).
// std.ui.primitives.serve extern def routes here; no top-level 'serve'
// needed — the binding 'const serve = std.ui.primitives.serve' works.
function _ssc_ui_serve(treeOrPort, portOrUndef, extraCssOrUndef) {
  if (typeof treeOrPort === 'number') { _ssc_http_serve(treeOrPort); return; }
  const view     = treeOrPort;
  const port     = portOrUndef;
  const extraCss = extraCssOrUndef || '';
  route('GET', '/')((_req) => {
    const { body, sigs } = _ssc_ui_renderBody(view);
    const sigJson = JSON.stringify(Object.fromEntries(sigs));
    const script = `<script>_ssc_ui_mount(new Map(Object.entries(${sigJson})));</script>`;
    const html = `<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ScalaScript App</title>
<style>*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;padding:0;background:#fff;-webkit-text-size-adjust:100%}
.ssc-page{max-width:700px;margin:0 auto;padding:24px 20px;font-size:16px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,sans-serif}
input[type=checkbox]{width:22px;height:22px;accent-color:#2563eb;cursor:pointer;flex-shrink:0}
button{touch-action:manipulation;cursor:pointer}
button:disabled{opacity:.5;cursor:default}
[data-ssc-cond]{display:contents}
hr{border:none;border-top:1px solid #e5e7eb;margin:0}
[data-ssc-fetch-table] table,[data-ssc-fetch-table] th,[data-ssc-fetch-table] td,[data-ssc-fetch-table] button{font-size:inherit;font-family:inherit}
@keyframes spin{from{transform:rotate(0deg)}to{transform:rotate(360deg)}}
.ssc-spin{animation:spin 0.8s linear infinite}
${extraCss}</style></head><body><div class="ssc-page">${body}</div>${script}</body></html>`;
    return Response.html(html);
  });
  _ssc_http_serve(port);
}
function _ssc_ui_fetchUrlSignal(name, url, tick) { return Signal(''); }
function _ssc_ui_fetchAction(method, url, body, tick) { return { _type: '_FetchAction', method, url, body, tick }; }
function _ssc_ui_incSignal(s) { return { _type: '_IncSignal', s }; }
function _ssc_ui_fetchActionClear(method, url, body, tick) { return { _type: '_FetchActionClear', method, url, body, tick }; }
function _ssc_ui_fetchTableView(fetchUrl, deleteUrl, tick) { return { _type: '_FetchTableView', fetchUrl, deleteUrl, tick }; }
"""
