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
const _signals = new Map();   // id → { value, subs:Set<eid>, _isComputed?, _f?, _deps?, _seedSource? }
const _effects = new Map();   // eid → { thunk, deps:Set<sid> }
const _effectStack = [];
const _pendingEffects = new Set();  // insertion-ordered in JS Sets
let _reactiveFlushing = false;
let _trackingDeps = null;     // Set<id> while inside computedSignal init

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
  if (_trackingDeps !== null) _trackingDeps.add(id);
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

function _seedInitial(source) {
  try { return (source && source.get) ? source.get() : ''; }
  catch(_e) { return ''; }
}

function effect(thunk) {
  const eid = _freshReactiveId();
  _effects.set(eid, { thunk, deps: new Set() });
  _runEffect(eid);
}

function computed(thunk) {
  const sid = _freshReactiveId();
  const eid = _freshReactiveId();
  _signals.set(sid, { value: undefined, subs: new Set(), _isComputed: true, _f: thunk, _deps: new Set(), _effectId: eid });
  const updater = () => _signalSet(sid, thunk());
  _effects.set(eid, { thunk: updater, deps: new Set() });
  _runEffect(eid);
  const state = _signals.get(sid);
  const effectState = _effects.get(eid);
  if (state && effectState) state._deps = effectState.deps;
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
function _ssc_ui_seedSignal(name, source) {
  const initial = _seedInitial(source);
  const sig = Signal(initial == null ? '' : String(initial));
  const state = _signals.get(sig.id);
  if (state) { state._seedSource = source || null; state._seedPristine = true; }
  sig._seedSource = source || null;
  const baseSet = sig.set;
  sig.set = (v) => { if (state) state._seedPristine = false; baseSet(v); };
  return sig;
}
function _ssc_ui_element(tag, attrs, events, children) {
  return { _type: '_Element', tag,
    attrs: (_isMap(attrs)) ? Object.fromEntries(attrs) : (attrs || {}),
    events: (_isMap(events)) ? Object.fromEntries(events) : (events || {}),
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
function _ssc_ui_currentHash() {
  if (typeof window === 'undefined' || !window.location) return '';
  const raw = String(window.location.hash || '');
  return raw.startsWith('#') ? raw.slice(1) : raw;
}
function _ssc_ui_hashSignal() {
  const sig = Signal(_ssc_ui_currentHash());
  if (typeof window !== 'undefined' && window.addEventListener) {
    window.addEventListener('hashchange', function() { sig.set(_ssc_ui_currentHash()); });
  }
  return sig;
}
function _ssc_ui_computedSignal(f) {
  return computed(function() {
    try { return (typeof f === 'function') ? f() : (f && f.apply) ? f.apply() : ''; }
    catch(_e) { return ''; }
  });
}
function _ssc_ui_emit(tree, outDir) {}
function _ssc_ui_truthy(v) {
  if (typeof v === 'boolean') return v;
  if (v === null || v === undefined) return false;
  if (typeof v === 'string') return v.length > 0 && v.toLowerCase() !== 'false';
  return !!v;
}

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
    if (!s || s._type !== 'Signal') return;
    if (!sigs.has(s.id)) sigs.set(s.id, s.get());
    const state = _signals.get(s.id);
    if (state && state._deps) {
      for (const depId of state._deps) {
        const dep = _signals.get(depId);
        if (dep && !sigs.has(depId)) sigs.set(depId, dep.value);
      }
    }
    if (s._seedSource) collectSig(s._seedSource);
    const fg = s._fetchGet;
    if (fg) {
      if (fg.tick) collectSig(fg.tick);
      if (fg.headers) collectSig(fg.headers);
    }
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
            if (h.headers) collectSig(h.headers); if (h.into) collectSig(h.into);
            const bId = (h.body && h.body.id) ? h.body.id : '';
            const tId = (h.tick && h.tick.id) ? h.tick.id : '';
            const hId = (h.headers && h.headers.id != null) ? String(h.headers.id) : '';
            const iId = (h.into && h.into.id != null) ? String(h.into.id) : '';
            aStr += ` data-ssc-fetch-method="${_esc(h.method||'POST')}" data-ssc-fetch-url="${_esc(h.url)}" data-ssc-fetch-body="${_esc(bId)}" data-ssc-fetch-tick="${_esc(tId)}"`;
            if (hId) aStr += ` data-ssc-fetch-headers="${_esc(hId)}"`;
            if (iId) aStr += ` data-ssc-fetch-into="${_esc(iId)}"`;
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
        if (!id) return txt;
        const fg = v.sig && v.sig._fetchGet;
        if (fg) {
          if (fg.tick) collectSig(fg.tick);
          if (fg.headers) collectSig(fg.headers);
          const fgTick = (fg.tick && fg.tick.id != null) ? String(fg.tick.id) : '';
          const fgHdr  = (fg.headers && fg.headers.id != null) ? String(fg.headers.id) : '';
          let fgAttrs = ` data-ssc-fetch-get-url="${_esc(fg.url)}"`;
          if (fgTick) fgAttrs += ` data-ssc-fetch-get-tick="${_esc(fgTick)}"`;
          if (fgHdr)  fgAttrs += ` data-ssc-fetch-get-headers="${_esc(fgHdr)}"`;
          return `<span data-ssc-text="${id}"${fgAttrs}>${txt}</span>`;
        }
        return `<span data-ssc-text="${id}">${txt}</span>`;
      }
      case '_ShowSignal': {
        collectSig(v.cond);
        const id   = v.cond && v.cond.id;
        const show = _ssc_ui_truthy(v.cond && v.cond.get ? v.cond.get() : false);
        const tHtml = walk(v.whenTrue);
        const fHtml = walk(v.whenFalse);
        if (!id) return show ? tHtml : fHtml;
        const tStyle = show ? ' style="display:contents"' : ' style="display:none"';
        const fStyle = show ? ' style="display:none"' : ' style="display:contents"';
        return `<span data-ssc-cond="${id}" style="display:contents"><span data-ssc-branch="true"${tStyle}>${tHtml}</span><span data-ssc-branch="false"${fStyle}>${fHtml}</span></span>`;
      }
      case '_Fragment':      return (v.children || []).map(walk).join('');
      case '_DataTableView': {
        // The source may be a TableDataSource marker (static / signal rows) or a
        // legacy bare FetchUrlSignal (Remote, `._fetchGet`).
        const src   = v.signal;
        const isStatic = src && src._source === 'static';
        const isSigRows = src && src._source === 'signal';
        const rowsSig = isSigRows ? src.sig : null;
        if (isStatic) {
          // nothing reactive to collect for static rows
        } else if (isSigRows) {
          if (rowsSig) collectSig(rowsSig);
        } else if (src) {
          collectSig(src);
        }
        const fg = src && src._fetchGet;
        if (fg && fg.tick)    collectSig(fg.tick);
        if (fg && fg.headers) collectSig(fg.headers);
        (v.actions || []).forEach(function(a) {
          if (a.tick) collectSig(a.tick);
          if (a.headers) collectSig(a.headers);
          if (a._type === '_RowLink' && a.signal) collectSig(a.signal);
        });
        const dtSigId  = (src && src.id != null) ? String(src.id) : '';
        const dtUrl    = fg ? _esc(fg.url) : '';
        const dtTick   = (fg && fg.tick && fg.tick.id != null) ? String(fg.tick.id) : '';
        const dtHdr    = (fg && fg.headers && fg.headers.id != null) ? String(fg.headers.id) : '';
        const dtCols   = _esc(JSON.stringify((v.columns || []).map(function(c) {
          return { title: c.title, fieldPath: c.fieldPath, align: c.align || '', kind: c.kind || { type: 'text' } };
        })));
        const dtActs   = _esc(JSON.stringify((v.actions || []).map(function(a) { return a; })));
        let dtAttrs = `data-ssc-datatable="${dtSigId}" data-ssc-datatable-url="${dtUrl}" data-ssc-datatable-cols="${dtCols}" data-ssc-datatable-acts="${dtActs}"`;
        if (isStatic) dtAttrs += ` data-ssc-datatable-rows="${_esc(JSON.stringify(src.rows || []))}"`;
        if (isSigRows && rowsSig && rowsSig.id != null) dtAttrs += ` data-ssc-datatable-rows-sig="${String(rowsSig.id)}"`;
        if (dtTick) dtAttrs += ` data-ssc-datatable-tick="${dtTick}"`;
        if (dtHdr)  dtAttrs += ` data-ssc-datatable-headers="${dtHdr}"`;
        return `<div ${dtAttrs} style="overflow-x:auto"></div>`;
      }
      // A raw, un-lowered DataTableNode (a TkNode that reached the renderer
      // because it was placed directly in an element()/container's children
      // instead of being lowered).  lower(DataTableNode) is theme-free —
      // it just wraps into dataTableView — so normalise and render it here
      // rather than dropping it silently.  See specs/js-backend-ui-render-gaps.md.
      case 'DataTableNode':
        return walk(_ssc_ui_dataTableView(v.signal, v.columns, v.actions));
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
  // Seed reactive system with initial values so computedSignal reads are current
  sigs.forEach(function(v, id) {
    var s = _signals.get(id) || _signals.get(parseInt(String(id), 10));
    if (s && !s._isComputed) s.value = v;
  });
  var _sb = {};
  function _sub(id, fn) { (_sb[id] = _sb[id] || []).push(fn); fn(_sv[id]); }
  function _notifyBridge(idStr, v) {
    _sv[idStr] = v;
    (_sb[idStr] || []).forEach(function(fn){ fn(v); });
  }
  function _syncBridgeSignals() {
    Object.keys(_sb).forEach(function(idStr) {
      var rs = _signals.get(parseInt(idStr, 10));
      if (!rs) return;
      var nv = rs.value;
      if (_sv[idStr] !== nv) _notifyBridge(idStr, nv);
    });
  }
  function _set(id, v, opts) {
    var idStr = String(id);
    var rid = parseInt(idStr, 10);
    var rs = _signals.get(rid);
    if (rs && rs._seedSource && !(opts && opts.preserveSeedPristine)) rs._seedPristine = false;
    if (rs && !rs._isComputed) _signalSet(rid, v);
    else if (rs) rs.value = v;
    _notifyBridge(idStr, rs ? rs.value : v);
    _syncBridgeSignals();
  }
  var _fetchGetMounted = {};
  function _mountFetchGet(sigId, url, tickId, headersId) {
    if (!sigId || !url || _fetchGetMounted[sigId]) return;
    _fetchGetMounted[sigId] = true;
    function doGet() {
      var opts = {};
      if (headersId) {
        var hs = _sv[headersId];
        if (hs) { try { opts.headers = JSON.parse(hs); } catch(_e) {} }
      }
      fetch(url, opts).then(function(r) { return r.text(); }).then(function(t) { _set(sigId, t); });
    }
    doGet();
    if (tickId) _sub(tickId, function(t) { if ((t | 0) > 0) doGet(); });
  }
  _signals.forEach(function(s, rawId) {
    var sigId = String(rawId);
    var collected = sigs.has(rawId) || sigs.has(sigId);
    if (!collected) return;
    if (s._seedSource && s._seedSource.id != null) {
      var sourceId = String(s._seedSource.id);
      if (sigs.has(s._seedSource.id) || sigs.has(sourceId)) {
        s._seedPristine = true;
        _sub(sourceId, function(v) {
          if (s._seedPristine) _set(sigId, v == null ? '' : String(v), { preserveSeedPristine: true });
        });
      }
    }
    if (s._fetchGet) {
      var fg = s._fetchGet;
      var tickId = (fg.tick && fg.tick.id != null) ? String(fg.tick.id) : '';
      var headersId = (fg.headers && fg.headers.id != null) ? String(fg.headers.id) : '';
      _mountFetchGet(sigId, fg.url, tickId, headersId);
    }
  });
  // show/hide branches
  document.querySelectorAll('[data-ssc-cond]').forEach(function(el) {
    var id = el.getAttribute('data-ssc-cond');
    var tBranch = el.querySelector('[data-ssc-branch="true"]');
    var fBranch = el.querySelector('[data-ssc-branch="false"]');
    _sub(id, function(v) {
      var show = _ssc_ui_truthy(v);
      if (tBranch) tBranch.style.display = show ? 'contents' : 'none';
      if (fBranch) fBranch.style.display = show ? 'none' : 'contents';
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
    var method    = el.getAttribute('data-ssc-fetch-method') || 'POST';
    var url       = el.getAttribute('data-ssc-fetch-url');
    var bodyId    = el.getAttribute('data-ssc-fetch-body');
    var tickId    = el.getAttribute('data-ssc-fetch-tick');
    var headersId = el.getAttribute('data-ssc-fetch-headers');
    var clear     = el.getAttribute('data-ssc-fetch-clear');
    var intoId    = el.getAttribute('data-ssc-fetch-into');
    el.addEventListener('click', function() {
      var body = bodyId ? String(_sv[bodyId] == null ? '' : _sv[bodyId]) : '';
      var opts = {method: method, body: body};
      if (headersId) {
        var hs = _sv[headersId];
        if (hs) { try { opts.headers = JSON.parse(hs); } catch(_e) {} }
      }
      var ok = true;
      fetch(url, opts)
        .then(function(r) { ok = !!(r && r.ok); return r.text(); })
        .then(function(text) {
          // Capture variant: on a 2xx, stash the response body into `into`,
          // then proceed (tick/clear).  A non-2xx leaves `into` and the tick
          // untouched so a failed POST does not look like a success.
          if (intoId) {
            if (!ok) return;
            _set(intoId, text == null ? '' : String(text));
          }
          if (tickId) _set(tickId, ((_sv[tickId] || 0) | 0) + 1);
          if (clear && bodyId) _set(bodyId, '');
        });
    });
  });
  // DataTable — generalised fetch-backed table with columns + actions
  document.querySelectorAll('[data-ssc-datatable]').forEach(function(container) {
    var sigId      = container.getAttribute('data-ssc-datatable');
    var fetchUrl   = container.getAttribute('data-ssc-datatable-url');
    var rawCols    = container.getAttribute('data-ssc-datatable-cols');
    var rawActs    = container.getAttribute('data-ssc-datatable-acts');
    var headersId  = container.getAttribute('data-ssc-datatable-headers');
    var tickId     = container.getAttribute('data-ssc-datatable-tick');
    var rawRows    = container.getAttribute('data-ssc-datatable-rows');
    var rowsSigId  = container.getAttribute('data-ssc-datatable-rows-sig');
    var cols, acts;
    try { cols = JSON.parse(rawCols || '[]'); } catch(_e) { cols = []; }
    try { acts = JSON.parse(rawActs || '[]'); } catch(_e) { acts = []; }
    var cs = window.getComputedStyle(container);
    var fs = cs.fontSize; var ff = cs.fontFamily;
    var thStyle = 'text-align:left;padding:6px 12px;border-bottom:2px solid #e5e7eb;font-weight:600;color:#111827;font-size:'+fs+';font-family:'+ff;
    var tdStyle = 'padding:6px 12px;border-bottom:1px solid #e5e7eb;color:#374151;vertical-align:middle;font-size:'+fs+';font-family:'+ff;
    var btnStyle = 'background:#3b82f6;color:#fff;border:none;padding:4px 12px;border-radius:4px;cursor:pointer;font-size:'+fs+';font-family:'+ff+';margin-right:4px';
    var delStyle  = 'background:#ef4444;color:#fff;border:none;padding:4px 12px;border-radius:4px;cursor:pointer;font-size:'+fs+';font-family:'+ff+';margin-right:4px';
    function getField(row, path) {
      return path.split('.').reduce(function(o, k) { return o && o[k]; }, row);
    }
    // Resolve a rowPostAction payload against a row.  A payload is either a
    // plain field name (back-compat) or a RowPayload marker (field/wholeRow/
    // fields) produced by _ssc_ui_fieldPayload / wholeRowPayload / fieldsPayload.
    function resolvePayload(row, payload) {
      if (payload && typeof payload === 'object') {
        if (payload._payload === 'wholeRow') return JSON.stringify(row);
        if (payload._payload === 'fields') {
          var obj = {};
          (payload.names || []).forEach(function(n) { obj[n] = getField(row, n); });
          return JSON.stringify(obj);
        }
        if (payload._payload === 'field') return String(getField(row, payload.name) || '');
      }
      return String(getField(row, payload) || '');
    }
    function getHeaders(headersId) {
      if (!headersId) return undefined;
      var hs = _sv[headersId];
      if (!hs) return undefined;
      try { return JSON.parse(hs); } catch(_e) { return undefined; }
    }
    function getKind(col) {
      var kind = (col && col.kind) || {};
      return String(kind.type || kind.kind || kind._type || 'text');
    }
    function fmtDate(value, format) {
      var v = String(value == null ? '' : value);
      if (!v) return '';
      try {
        var d = new Date(v);
        if (Number.isNaN(d.getTime())) return v;
        if (format === 'short' || format === 'medium' || format === 'long' || format === 'full') {
          return d.toLocaleDateString(undefined, { dateStyle: format });
        }
        return d.toLocaleDateString(format || undefined);
      } catch(_e) { return v; }
    }
    function fmtMoney(value, currency, locale) {
      var v = String(value == null ? '' : value);
      if (!v) return '';
      try {
        return new Intl.NumberFormat(locale || undefined, { style: 'currency', currency: currency || 'USD' }).format(Number(v));
      } catch(_e) { return v; }
    }
    function renderCellValue(row, col) {
      var raw = getField(row, col.fieldPath);
      var value = raw != null ? raw : '';
      var kind = (col && col.kind) || {};
      switch (getKind(col)) {
        case 'date':
          return fmtDate(value, kind.format || '');
        case 'money':
          return fmtMoney(value, kind.currency || 'USD', kind.locale || '');
        case 'status': {
          var text = String(value);
          var colors = kind.colorMap || {};
          var color = colors[text] || '';
          var span = document.createElement('span');
          span.textContent = text;
          if (color) {
            span.style.background = color;
            span.style.padding = '2px 8px';
            span.style.borderRadius = '9999px';
            span.style.fontSize = '0.85em';
          }
          return span;
        }
        case 'link': {
          var href = kind.urlTemplate ? String(kind.urlTemplate).replace(':value', encodeURIComponent(String(value))) : '#';
          var a = document.createElement('a');
          a.href = href;
          a.textContent = String(value);
          return a;
        }
        default:
          return String(value);
      }
    }
    function appendCellValue(td, row, col) {
      var rendered = renderCellValue(row, col);
      if (rendered && rendered.nodeType) td.appendChild(rendered);
      else td.textContent = String(rendered == null ? '' : rendered);
    }
    function renderTable(rows) {
      container.innerHTML = '';
      var tbl = document.createElement('table');
      tbl.setAttribute('style', 'border-collapse:collapse;width:100%;font-family:'+ff+';font-size:'+fs);
      var thead = document.createElement('thead'); thead.setAttribute('style', 'background:#f9fafb');
      var trH = document.createElement('tr');
      cols.forEach(function(col) {
        var th = document.createElement('th'); th.setAttribute('style', thStyle + (col.align ? ';text-align:' + col.align : '')); th.textContent = col.title; trH.appendChild(th);
      });
      if (acts.length > 0) { var thA = document.createElement('th'); thA.setAttribute('style', thStyle); trH.appendChild(thA); }
      thead.appendChild(trH); tbl.appendChild(thead);
      var tbody = document.createElement('tbody');
      (rows || []).forEach(function(row) {
        var tr = document.createElement('tr');
        cols.forEach(function(col) {
          var td = document.createElement('td'); td.setAttribute('style', tdStyle + (col.align ? ';text-align:' + col.align : ''));
          appendCellValue(td, row, col);
          tr.appendChild(td);
        });
        if (acts.length > 0) {
          var tdA = document.createElement('td'); tdA.setAttribute('style', tdStyle);
          acts.forEach(function(act) {
            (function(r) {
              var btn = document.createElement('button');
              if (act._type === '_RowDelete') {
                btn.setAttribute('style', delStyle); btn.textContent = 'Delete';
                btn.addEventListener('click', function() {
                  var dOpts = {method: 'POST', body: String(getField(r, act.idField) || '')};
                  var dh = getHeaders(act.headers && act.headers.id); if (dh) dOpts.headers = dh;
                  fetch(act.url, dOpts).then(function(res) { return res.text(); })
                    .then(function() { if (act.tick && act.tick.id) _set(String(act.tick.id), ((_sv[String(act.tick.id)] || 0) | 0) + 1); });
                });
              } else if (act._type === '_RowPost') {
                btn.setAttribute('style', btnStyle); btn.textContent = act.label || '';
                btn.addEventListener('click', function() {
                  var pOpts = {method: act.method || 'POST', body: resolvePayload(r, act.bodyField)};
                  var dh = getHeaders(act.headers && act.headers.id); if (dh) pOpts.headers = dh;
                  fetch(act.url, pOpts).then(function(res) { return res.text(); })
                    .then(function() { if (act.tick && act.tick.id) _set(String(act.tick.id), ((_sv[String(act.tick.id)] || 0) | 0) + 1); });
                });
              } else if (act._type === '_RowLink') {
                btn.setAttribute('style', btnStyle); btn.textContent = act.label || '';
                btn.addEventListener('click', function() {
                  if (act.signal && act.signal.id) _set(String(act.signal.id), String(getField(r, act.fieldPath) || ''));
                });
              }
              tdA.appendChild(btn);
            })(row);
          });
          tr.appendChild(tdA);
        }
        tbody.appendChild(tr);
      });
      tbl.appendChild(tbody); container.appendChild(tbl);
    }
    function doFetch() {
      var opts = {};
      if (headersId) { var hs = getHeaders(headersId); if (hs) opts.headers = hs; }
      fetch(fetchUrl, opts).then(function(r) { return r.json(); }).then(renderTable);
    }
    function asRows(v) {
      if (Array.isArray(v)) return v;
      if (typeof v === 'string') { try { var p = JSON.parse(v); return Array.isArray(p) ? p : []; } catch(_e) { return []; } }
      return v == null ? [] : v;
    }
    if (rawRows != null) {
      // Static rows — inline JSON, no fetch.
      var staticRows; try { staticRows = JSON.parse(rawRows); } catch(_e) { staticRows = []; }
      renderTable(asRows(staticRows));
    } else if (rowsSigId) {
      // Signal-backed rows — render the current value and re-render on change.
      renderTable(asRows(_sv[rowsSigId]));
      _sub(rowsSigId, function(rows) { renderTable(asRows(rows)); });
    } else {
      // Remote (FetchUrlSignal) — fetch + tick-driven re-fetch.
      doFetch();
      if (tickId) _sub(tickId, function(t) { if ((t | 0) > 0) doFetch(); });
      acts.forEach(function(act) {
        if ((act._type === '_RowDelete' || act._type === '_RowPost') && act.tick && act.tick.id) {
          var tId = String(act.tick.id);
          _sub(tId, function(t) { if ((t | 0) > 0) doFetch(); });
        }
      });
    }
  });
  // fetchUrlSignal GET — mount + tick-driven re-fetch
  document.querySelectorAll('[data-ssc-fetch-get-url]').forEach(function(el) {
    var sigId     = el.getAttribute('data-ssc-text');
    var url       = el.getAttribute('data-ssc-fetch-get-url');
    var tickId    = el.getAttribute('data-ssc-fetch-get-tick');
    var headersId = el.getAttribute('data-ssc-fetch-get-headers');
    _mountFetchGet(sigId, url, tickId, headersId);
  });
}

// Backward-compat wrapper: walk the view and return { body, script } where
// script is an inline <script> IIFE — used by _ssc_ui_serve for server-rendered pages.
function _ssc_ui_renderPage(view) {
  const { body, sigs } = _ssc_ui_renderBody(view);
  const sigJson = JSON.stringify(Object.fromEntries(sigs));
  const script = `<script>_ssc_ui_mount(new Map(Object.entries(${sigJson})));<\/script>`;
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
  _ssc_http_route('GET', '/')((_req) => {
    const { body, sigs } = _ssc_ui_renderBody(view);
    const sigJson = JSON.stringify(Object.fromEntries(sigs));
    const script = `<script>_ssc_ui_mount(new Map(Object.entries(${sigJson})));<\/script>`;
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
function _ssc_ui_emptyHeaders() { return Signal(''); }
function _ssc_ui_fetchUrlSignal(name, url, tick, headers) {
  var sig = Signal('');
  if (url) sig._fetchGet = { url, tick: tick || null, headers: headers || null };
  var state = _signals.get(sig.id);
  if (state) state._fetchGet = sig._fetchGet;
  return sig;
}
function _ssc_ui_fetchAction(method, url, body, tick, headers) { return { _type: '_FetchAction', method, url, body, tick, headers: headers || null }; }
function _ssc_ui_incSignal(s) { return { _type: '_IncSignal', s }; }
function _ssc_ui_fetchActionClear(method, url, body, tick, headers) { return { _type: '_FetchActionClear', method, url, body, tick, headers: headers || null }; }
function _ssc_ui_fetchCaptureAction(method, url, body, into, tick, headers) { return { _type: '_FetchAction', method, url, body, tick, into: into || null, headers: headers || null }; }
function _ssc_ui_colorMapObject(colorMap) {
  if (!colorMap) return {};
  if (typeof globalThis !== 'undefined' && typeof globalThis.Map === 'function' && colorMap instanceof globalThis.Map) {
    return Object.fromEntries(colorMap.entries());
  }
  return (typeof colorMap === 'object') ? colorMap : {};
}
function _ssc_ui_makeColumn(title, fieldPath, align, kind, editAction) {
  return { title, fieldPath, align: align || '', editAction: editAction || null, kind: kind || { type: 'text' } };
}
function _ssc_ui_fieldColumn(title, fieldPath, align, editAction) {
  return _ssc_ui_makeColumn(title, fieldPath, align, { type: 'text' }, editAction);
}
function _ssc_ui_dateColumn(title, fieldPath, align, format) {
  return _ssc_ui_makeColumn(title, fieldPath, align, { type: 'date', format: format || '' }, null);
}
function _ssc_ui_moneyColumn(title, fieldPath, align, currency, locale) {
  return _ssc_ui_makeColumn(title, fieldPath, align, { type: 'money', currency: currency || 'USD', locale: locale || '' }, null);
}
function _ssc_ui_statusColumn(title, fieldPath, align, colorMap) {
  return _ssc_ui_makeColumn(title, fieldPath, align, { type: 'status', colorMap: _ssc_ui_colorMapObject(colorMap) }, null);
}
function _ssc_ui_linkColumn(title, fieldPath, align, urlTemplate) {
  return _ssc_ui_makeColumn(title, fieldPath, align, { type: 'link', urlTemplate: urlTemplate || '' }, null);
}
function _ssc_ui_rowDeleteAction(url, idField, tick, headers) { return { _type: '_RowDelete', url, idField, tick, headers: headers || null }; }
function _ssc_ui_rowPostAction(label, method, url, bodyField, tick, headers) { return { _type: '_RowPost', label, method, url, bodyField, tick, headers: headers || null }; }
function _ssc_ui_rowLinkAction(label, signal, fieldPath) { return { _type: '_RowLink', label, signal, fieldPath }; }
function _ssc_ui_rowEditAction(method, url, idField, tick, headers) { return { _type: '_RowInlineEdit', method, url, idField, tick, headers: headers || null }; }
function _ssc_ui_dataTableView(signal, columns, actions) { return { _type: '_DataTableView', signal, columns: columns || [], actions: actions || [] }; }
// TableDataSource markers — first argument to dataTableView (parity with the
// interpreter TableDataSource.StaticRows / SignalRows; the legacy bare
// FetchUrlSignal Remote path is still accepted via `._fetchGet`).
function _ssc_ui_staticRowsSource(rows) { return { _source: 'static', rows: rows || [] }; }
function _ssc_ui_signalRowsSource(sig) { return { _source: 'signal', sig }; }
// RowPayload markers — the `payload` argument of rowPostAction (parity with the
// interpreter RowPayload.Field / WholeRow / Fields).  Resolved against a row in
// _ssc_ui_mount's _RowPost handler (see _ssc_ui_resolveRowPayload).
function _ssc_ui_fieldPayload(name) { return { _payload: 'field', name }; }
function _ssc_ui_wholeRowPayload() { return { _payload: 'wholeRow' }; }
function _ssc_ui_fieldsPayload(names) { return { _payload: 'fields', names: names || [] }; }
"""
