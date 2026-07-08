
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
// User-facing signal NAME → Signal object (the `signal("name", init)` first arg).
// The bridge keys everything by numeric id, but `formBody` fields reference
// signals by NAME from .ssc — this registry lets the walk resolve name → id.
const _signalsByName = new Map();
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
function _ssc_ui_signal(name, initial) {
  const sig = Signal(initial);
  if (name != null && String(name) !== '') _signalsByName.set(String(name), sig);
  return sig;
}
function _ssc_ui_seedSignal(name, source) {
  const initial = _seedInitial(source);
  const sig = Signal(initial == null ? '' : String(initial));
  if (name != null && String(name) !== '') _signalsByName.set(String(name), sig);
  const state = _signals.get(sig.id);
  if (state) { state._seedSource = source || null; state._seedPristine = true; }
  sig._seedSource = source || null;
  const baseSet = sig.set;
  sig.set = (v) => { if (state) state._seedPristine = false; baseSet(v); };
  return sig;
}
// ── std/ui/offline.ssc externs (tkv2-offline) ──────────────────────────────
// Browser: real localStorage + navigator.onLine; Node: per-process map + true.
const _ssc_ls_mem = new Map();
function _ssc_ls() {
  // window.localStorage (not the bare global) — Node 26+ defines a global
  // localStorage getter that warns unless --localstorage-file is set.
  try { return (typeof window !== 'undefined' && window.localStorage) ? window.localStorage : null; }
  catch (_e) { return null; }  // privacy modes can throw on access
}
function _ssc_ui_localStorageGet(key) {
  const ls = _ssc_ls();
  const v = ls ? ls.getItem(key) : (_ssc_ls_mem.has(key) ? _ssc_ls_mem.get(key) : null);
  return (v === null || v === undefined) ? {_type: '_None'} : {_type: '_Some', value: v};
}
function _ssc_ui_localStorageSet(key, value) {
  const ls = _ssc_ls();
  if (ls) ls.setItem(key, value); else _ssc_ls_mem.set(key, value);
}
function _ssc_ui_localStorageRemove(key) {
  const ls = _ssc_ls();
  if (ls) ls.removeItem(key); else _ssc_ls_mem.delete(key);
}
let _ssc_online_sig = null;
function _ssc_ui_onlineSignal() {
  if (_ssc_online_sig) return _ssc_online_sig;
  const init = (typeof navigator !== 'undefined' && typeof navigator.onLine === 'boolean')
    ? navigator.onLine : true;
  _ssc_online_sig = Signal(init);
  if (typeof window !== 'undefined' && window.addEventListener) {
    window.addEventListener('online',  () => _ssc_online_sig.set(true));
    window.addEventListener('offline', () => _ssc_online_sig.set(false));
  }
  return _ssc_online_sig;
}
function _ssc_ui_persistedSignal(name, def) {
  const cur = _ssc_ui_localStorageGet(name);
  const sig = Signal(cur._type === '_Some' ? cur.value : def);
  // Persist via an effect subscription, NOT a set-wrapper: DOM wiring
  // (data-ssc-change) and fetch actions write through _signalSet by id,
  // bypassing this object's .set. The first run only registers the
  // dependency — write-back starts with the first actual change (parity
  // with the interpreter lane's subscribe semantics).
  let first = true;
  effect(() => {
    const v = sig.get();
    if (first) { first = false; return; }
    _ssc_ui_localStorageSet(name, v);
  });
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
// Hash-tolerant equality.  hashSignal() yields the location hash with its
// leading '#' stripped ('/a' for '#/a'), but users routing by hand naturally
// compare against the URL form ('#/a') they wrote in an <a href>.  Normalising a
// leading '#' on both operands makes '#/a' and '/a' compare equal, so
// `showSignal(eqSignal(hashSignal(), "#/a"), …)` shows its branch when the hash
// matches.  This only ever turns a '#x'-vs-'x' mismatch into a match — it never
// breaks an already-equal comparison (non-string / non-'#' values pass through),
// so tab keys / plain route paths are unaffected.
function _ssc_ui_hashEq(a, b) {
  function _n(x) { return (typeof x === 'string' && x.charCodeAt(0) === 35) ? x.slice(1) : x; }
  return _n(a) === _n(b);
}
function _ssc_ui_eqSignal(s, value) { return computed(() => (s && s.get) ? _ssc_ui_hashEq(s.get(), value) : false); }
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
        let   _bAttrs = null;
        for (const [k, val] of Object.entries(attrs)) {
          const _isSig = val && typeof val === 'object' && typeof val.get === 'function';
          const r = _isSig ? (collectSig(val), val.get()) : val;
          if (r !== undefined && r !== null && r !== false)
            aStr += ` ${k}="${_esc(r)}"`;
          // A signal-valued attribute (other than an input's `value`, which has its own
          // change/read binding via data-ssc-change) is bound reactively so it updates on
          // signal change — e.g. a localized placeholder switching with the locale.
          if (_isSig && k !== 'value' && val.id != null) (_bAttrs = _bAttrs || {})[k] = String(val.id);
        }
        if (_bAttrs) aStr += ` data-ssc-bind-attrs="${_esc(JSON.stringify(_bAttrs))}"`;
        for (const [, h] of Object.entries(events)) {
          if (!h || typeof h !== 'object') continue;
          if (h._type === '_ToggleSignal' && h.s) { collectSig(h.s); aStr += ` data-ssc-toggle="${h.s.id}"`; }
          else if (h._type === '_SetSignal'  && h.s) { collectSig(h.s); aStr += ` data-ssc-set="${h.s.id}" data-ssc-set-val="${_esc(JSON.stringify(h.v))}"`; }
          else if (h._type === '_InputChange' && h.s) { collectSig(h.s); aStr += ` data-ssc-change="${h.s.id}"`; }
          else if ((h._type === '_FetchAction' || h._type === '_FetchActionClear') && (h.url || h.urlSig)) {
            // Scope B.4+ — body may be a formBody descriptor (assemble from named
            // field signals at submit) instead of a single body signal.
            const bodyFields = (h.body && h.body._body === 'fields') ? h.body.fields : null;
            if (h.body && !bodyFields) collectSig(h.body); if (h.tick) collectSig(h.tick);
            if (h.headers) collectSig(h.headers); if (h.into) collectSig(h.into);
            // Reactive URL (fetchActionTo): the URL is a signal resolved at click,
            // not a static string — collect it so its _sv entry is kept fresh.
            if (h.urlSig) collectSig(h.urlSig);
            const bId = (h.body && h.body.id) ? h.body.id : '';
            const tId = (h.tick && h.tick.id) ? h.tick.id : '';
            const hId = (h.headers && h.headers.id != null) ? String(h.headers.id) : '';
            const iId = (h.into && h.into.id != null) ? String(h.into.id) : '';
            const uSigId = (h.urlSig && h.urlSig.id != null) ? String(h.urlSig.id) : '';
            aStr += ` data-ssc-fetch-method="${_esc(h.method||'POST')}" data-ssc-fetch-url="${_esc(h.url || '')}" data-ssc-fetch-body="${_esc(bId)}" data-ssc-fetch-tick="${_esc(tId)}"`;
            if (uSigId) aStr += ` data-ssc-fetch-url-sig="${_esc(uSigId)}"`;
            if (hId) aStr += ` data-ssc-fetch-headers="${_esc(hId)}"`;
            if (iId) aStr += ` data-ssc-fetch-into="${_esc(iId)}"`;
            if (bodyFields) {
              // Field refs arrive as signal NAMES from .ssc (`formBody([("k","sigName")])`),
              // but the submit-time lookup reads the bridge store `_sv`, which is keyed by
              // NUMERIC signal id — resolve name → id here and COLLECT each signal so its
              // _sv entry exists and stays fresh. Unresolvable refs pass through verbatim
              // (non-bridge runtimes key sv by name).
              const rf = _ssc_ui_resolveFormFields(bodyFields);
              rf.signals.forEach(collectSig);
              aStr += ` data-ssc-fetch-body-fields="${_esc(JSON.stringify(rf.fields))}"`;
            }
            if (h._type === '_FetchActionClear') aStr += ` data-ssc-fetch-clear="1"`;
            if (h.onSuccess && h.onSuccess.length) {                // Scope B.4
              const effs = h.onSuccess.map(function(e) {
                if (e._eff === 'bumpTick' && e.tick) { collectSig(e.tick); return { eff: 'bumpTick', id: String(e.tick.id) }; }
                if (e._eff === 'setSignal' && e.s)   { collectSig(e.s);    return { eff: 'setSignal', id: String(e.s.id), v: e.v }; }
                if (e._eff === 'navigate')           { return { eff: 'navigate', path: String(e.path || '') }; }
                if (e._eff === 'openJson')           { return { eff: 'openJson', urlTemplate: String(e.urlTemplate || ''), field: String(e.field || '') }; }
                return null;
              }).filter(function(x) { return x; });
              if (effs.length) aStr += ` data-ssc-fetch-onsuccess="${_esc(JSON.stringify(effs))}"`;
            }
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
        if (fg && fg.url) {
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
        if (fg && fg.urlSig)  collectSig(fg.urlSig);  // fetchUrlSignalTo: reactive URL signal
        (v.actions || []).forEach(function(a) {
          if (a.tick) collectSig(a.tick);
          if (a.headers) collectSig(a.headers);
          if (a._type === '_RowLink' && a.signal) collectSig(a.signal);
        });
        const dtSigId  = (src && src.id != null) ? String(src.id) : '';
        const dtUrl    = (fg && fg.url) ? _esc(fg.url) : '';
        const dtUrlSig = (fg && fg.urlSig && fg.urlSig.id != null) ? String(fg.urlSig.id) : '';
        const dtTick   = (fg && fg.tick && fg.tick.id != null) ? String(fg.tick.id) : '';
        const dtHdr    = (fg && fg.headers && fg.headers.id != null) ? String(fg.headers.id) : '';
        const dtCols   = _esc(JSON.stringify((v.columns || []).map(function(c) {
          // A column title may be a reactive Signal[String] (e.g. an i18n-translated
          // title): collect it (+ its deps, e.g. a locale signal) and encode it as a
          // {__sig} ref so the client binds it reactively instead of stringifying the ref.
          var _t = c.title;
          if (_t && typeof _t === 'object' && _t._type === 'Signal') { collectSig(_t); _t = { __sig: String(_t.id) }; }
          return { title: _t, fieldPath: c.fieldPath, align: c.align || '', kind: c.kind || { type: 'text' } };
        })));
        const dtActs   = _esc(JSON.stringify((v.actions || []).map(function(a) {
          // A row-action label may likewise be a reactive Signal[String].
          if (a && a.label && typeof a.label === 'object' && a.label._type === 'Signal') {
            collectSig(a.label); var _a = Object.assign({}, a); _a.label = { __sig: String(a.label.id) }; return _a;
          }
          return a;
        })));
        let dtAttrs = `data-ssc-datatable="${dtSigId}" data-ssc-datatable-url="${dtUrl}" data-ssc-datatable-cols="${dtCols}" data-ssc-datatable-acts="${dtActs}"`;
        if (isStatic) dtAttrs += ` data-ssc-datatable-rows="${_esc(JSON.stringify(src.rows || []))}"`;
        if (isSigRows && rowsSig && rowsSig.id != null) dtAttrs += ` data-ssc-datatable-rows-sig="${String(rowsSig.id)}"`;
        const _rp = (v && v._rowsPath) || (src && src._rowsPath);  // node snapshot wins (per-table); shared signal is the fallback
        if (_rp) dtAttrs += ` data-ssc-datatable-rows-path="${_esc(String(_rp))}"`;
        if (dtTick) dtAttrs += ` data-ssc-datatable-tick="${dtTick}"`;
        if (dtHdr)  dtAttrs += ` data-ssc-datatable-headers="${dtHdr}"`;
        if (dtUrlSig) dtAttrs += ` data-ssc-datatable-url-sig="${dtUrlSig}"`;
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
  // A computedSignal read only at event time (e.g. a fetchAction body) is never
  // _sub'd by any display binding, so without this its _sv entry would stay frozen
  // at the load-time seed while its dependency signals change — the reactive graph
  // recomputes it, but only _sb-tracked ids are bridged back into _sv by
  // _syncBridgeSignals. Subscribe every collected computed so its _sv value tracks
  // the (always-fresh) reactive graph.
  sigs.forEach(function(v, id) {
    var rs = _signals.get(parseInt(String(id), 10));
    if (rs && rs._isComputed) _sub(String(id), function(){});
  });
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
  if (typeof window !== 'undefined' && window.addEventListener) {
    window.addEventListener('hashchange', function() { _syncBridgeSignals(); });
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
  function _mountFetchGet(sigId, url, tickId, headersId, urlSig) {
    var urlSigId = (urlSig && urlSig.id != null) ? String(urlSig.id) : '';
    if (!sigId || (!url && !urlSigId) || _fetchGetMounted[sigId]) return;
    _fetchGetMounted[sigId] = true;
    function doGet() {
      // fetchUrlSignalTo: resolve the URL from its signal (fresh reactive value) at fetch time.
      var theUrl = urlSig ? String(urlSig.get() == null ? '' : urlSig.get()) : url;
      if (!theUrl) return;
      var opts = {};
      if (headersId) {
        var hs = _sv[headersId];
        if (hs) { try { opts.headers = JSON.parse(hs); } catch(_e) {} }
      }
      fetch(theUrl, opts).then(function(r) { return r.text(); }).then(function(t) { _set(sigId, t); });
    }
    // urlSig present: _sub fires doGet on mount AND whenever the URL signal changes; else fetch once.
    if (urlSigId) _sub(urlSigId, function() { doGet(); });
    else doGet();
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
      _mountFetchGet(sigId, fg.url, tickId, headersId, fg.urlSig);
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
  // reactive element attributes (e.g. a localized placeholder that switches with the locale)
  document.querySelectorAll('[data-ssc-bind-attrs]').forEach(function(el) {
    var m; try { m = JSON.parse(el.getAttribute('data-ssc-bind-attrs')); } catch(_e) { m = null; }
    if (!m) return;
    Object.keys(m).forEach(function(attr) {
      _sub(m[attr], function(v) { if (v == null) el.removeAttribute(attr); else el.setAttribute(attr, String(v)); });
    });
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
    var onSuccessRaw = el.getAttribute('data-ssc-fetch-onsuccess');   // Scope B.4
    var bodyFieldsRaw = el.getAttribute('data-ssc-fetch-body-fields'); // Scope B.4+
    var urlSigId  = el.getAttribute('data-ssc-fetch-url-sig'); // fetchActionTo: reactive URL
    el.addEventListener('click', function() {
      // fetchActionTo resolves its URL from a signal at click time (kept fresh in
      // _sv by the computed→_sv bridge); plain fetchAction uses the static attr.
      var theUrl = urlSigId ? String(_sv[urlSigId] == null ? '' : _sv[urlSigId]) : url;
      var body = bodyFieldsRaw ? _ssc_ui_buildFormBody(bodyFieldsRaw, _sv)
               : (bodyId ? String(_sv[bodyId] == null ? '' : _sv[bodyId]) : '');
      var opts = {method: method, body: body};
      if (headersId) {
        var hs = _sv[headersId];
        if (hs) { try { opts.headers = JSON.parse(hs); } catch(_e) {} }
      }
      var ok = true;
      fetch(theUrl, opts)
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
          // Scope B.4 — run the structured onSuccess effects in order, but only on
          // a 2xx (a failed write must not navigate / flip a status signal).
          _ssc_ui_runOnSuccess(onSuccessRaw, ok, _set, _sv, text);
        });
    });
  });
  // DataTable — generalised fetch-backed table with columns + actions
  document.querySelectorAll('[data-ssc-datatable]').forEach(function(container) {
    var sigId      = container.getAttribute('data-ssc-datatable');
    var fetchUrl   = container.getAttribute('data-ssc-datatable-url');
    var urlSigId   = container.getAttribute('data-ssc-datatable-url-sig'); // fetchUrlSignalTo: reactive URL
    var rawCols    = container.getAttribute('data-ssc-datatable-cols');
    var rawActs    = container.getAttribute('data-ssc-datatable-acts');
    var headersId  = container.getAttribute('data-ssc-datatable-headers');
    var tickId     = container.getAttribute('data-ssc-datatable-tick');
    var rawRows    = container.getAttribute('data-ssc-datatable-rows');
    var rowsSigId  = container.getAttribute('data-ssc-datatable-rows-sig');
    var rowsPath   = container.getAttribute('data-ssc-datatable-rows-path') || '';
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
        case 'stacked': {
          var subRaw = kind.subFieldPath ? getField(row, kind.subFieldPath) : null;
          var subText = subRaw == null ? '' : String(subRaw);
          if (!subText) return String(value);
          var wrap = document.createElement('div');
          var main = document.createElement('div');
          main.textContent = String(value);
          wrap.appendChild(main);
          var sub = document.createElement('div');
          sub.textContent = subText;
          sub.setAttribute('style', 'font-size:0.8em;opacity:0.75;line-height:1.4');
          wrap.appendChild(sub);
          return wrap;
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
    // Set a button caption that may be a reactive {__sig} ref (i18n label) or a string.
    function _actLabel(btn, label) {
      if (label && typeof label === 'object' && label.__sig != null) {
        _sub(label.__sig, function(v){ btn.textContent = v == null ? '' : String(v); });
      } else { btn.textContent = label || ''; }
    }
    function renderTable(rows) {
      container.innerHTML = '';
      var tbl = document.createElement('table');
      tbl.setAttribute('style', 'border-collapse:collapse;width:100%;font-family:'+ff+';font-size:'+fs);
      var thead = document.createElement('thead'); thead.setAttribute('style', 'background:#f9fafb');
      var trH = document.createElement('tr');
      cols.forEach(function(col) {
        var th = document.createElement('th'); th.setAttribute('style', thStyle + (col.align ? ';text-align:' + col.align : ''));
        if (col.title && typeof col.title === 'object' && col.title.__sig != null) {
          _sub(col.title.__sig, function(v){ th.textContent = v == null ? '' : String(v); });
        } else { th.textContent = col.title; }
        trH.appendChild(th);
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
                var _postBody = resolvePayload(r, act.bodyField);
                // Render nothing when this row's body is empty — a rowPost that would POST nothing is
                // never intended, and skipping it lets a table show a per-row conditional action
                // (e.g. load XOR unload, one field empty per row) without a hidden column / CSS hack.
                if (_postBody == null || _postBody === '') return;
                btn.setAttribute('style', btnStyle); _actLabel(btn, act.label);
                btn.addEventListener('click', function() {
                  if (btn.disabled) return;
                  // Immediate feedback: a slow POST (e.g. loading a model) otherwise looks dead and
                  // invites a second tap. Disable + append '…'; a 2xx re-renders the table (clearing
                  // this button); a failure restores it.
                  var _orig = btn.textContent;
                  btn.disabled = true; btn.style.opacity = '0.6'; btn.textContent = _orig + ' …';
                  var pOpts = {method: act.method || 'POST', body: _postBody};
                  var dh = getHeaders(act.headers && act.headers.id); if (dh) pOpts.headers = dh;
                  // Path templating (tkv2): '/api/paid/:id' → ':id' replaced with the row's
                  // field (URL-encoded). Mirrors rowLink's ':value'. Unknown fields stay
                  // verbatim so ':'-containing literal URLs keep working.
                  var theUrl = String(act.url).replace(/\/:([A-Za-z_][A-Za-z0-9_]*)/g, function(m, f) {
                    var v = getField(r, f);
                    return (v === undefined || v === null) ? m : '/' + encodeURIComponent(String(v));
                  });
                  fetch(theUrl, pOpts).then(function(res) { return res.text(); })
                    .then(function() { if (act.tick && act.tick.id) _set(String(act.tick.id), ((_sv[String(act.tick.id)] || 0) | 0) + 1); })
                    .catch(function() { btn.disabled = false; btn.style.opacity = ''; btn.textContent = _orig; });
                });
              } else if (act._type === '_RowLink') {
                btn.setAttribute('style', btnStyle); _actLabel(btn, act.label);
                var rowVal = String(getField(r, act.fieldPath) || '');
                btn.addEventListener('click', function() {
                  if (act.signal && act.signal.id) _set(String(act.signal.id), rowVal);
                });
                // Selection-aware picker: mark the button whose row value the bound signal
                // currently holds (✓ via CSS ::before + accent — ::before is not a text node,
                // so an i18n text-walker keeps translating the label). The _sub survives
                // re-renders; the isConnected guard makes stale entries no-ops.
                if (act.signal && act.signal.id && rowVal !== '') {
                  _ssc_ui_ensureRowlinkCss();
                  _sub(String(act.signal.id), function(v) {
                    if (!btn.isConnected && document.body && !document.body.contains(btn)) return;
                    btn.classList.toggle('ssc-rowlink-selected', String(v == null ? '' : v) === rowVal);
                  });
                }
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
      // fetchUrlSignalTo: resolve the URL from its (collected, _sv-fresh) signal at fetch
      // time, so a filter/branch change re-fetches the new URL; else the baked url.
      var theUrl = urlSigId ? String(_sv[urlSigId] == null ? '' : _sv[urlSigId]) : fetchUrl;
      if (!theUrl) { renderTable([]); return; }
      // Read as text and normalise: the endpoint may answer a bare array, an
      // envelope ({data:[...]}), or — on a misrouted path — the SPA's own HTML.
      // _ssc_ui_rowsOf turns all of them into an array (HTML/non-JSON → []) so
      // renderTable never crashes on `.forEach`.
      fetch(theUrl, opts)
        .then(function(r) { return r.text(); })
        .then(function(text) { renderTable(_ssc_ui_rowsOf(text, rowsPath)); })
        .catch(function() { renderTable([]); });
    }
    if (rawRows != null) {
      // Static rows — inline JSON, no fetch.
      var staticRows; try { staticRows = JSON.parse(rawRows); } catch(_e) { staticRows = []; }
      renderTable(_ssc_ui_rowsOf(staticRows));
    } else if (rowsSigId) {
      // Signal-backed rows — render the current value and re-render on change.
      renderTable(_ssc_ui_rowsOf(_sv[rowsSigId]));
      _sub(rowsSigId, function(rows) { renderTable(_ssc_ui_rowsOf(rows)); });
    } else {
      // Remote (FetchUrlSignal) — fetch + tick-driven re-fetch.
      doFetch();
      if (tickId) _sub(tickId, function(t) { if ((t | 0) > 0) doFetch(); });
      // fetchUrlSignalTo — re-fetch whenever the URL signal changes (filter/branch switch).
      if (urlSigId) _sub(urlSigId, function() { doFetch(); });
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
// fetchUrlSignalTo — like fetchUrlSignal but the URL is a Signal[String] resolved at
// fetch time, re-fetching whenever it changes (filter/branch switch). The read-side
// counterpart of fetchActionTo. urlSig is collected + kept fresh by the computed→_sv bridge.
function _ssc_ui_fetchUrlSignalTo(name, urlSig, tick, headers) {
  var sig = Signal('');
  if (urlSig) sig._fetchGet = { urlSig: urlSig, tick: tick || null, headers: headers || null };
  var state = _signals.get(sig.id);
  if (state) state._fetchGet = sig._fetchGet;
  return sig;
}
function _ssc_ui_fetchAction(method, url, body, tick, headers) { return { _type: '_FetchAction', method, url, body, tick, headers: headers || null }; }
// fetchActionTo — like fetchAction but the URL is a Signal[String] resolved at
// click time (path-id endpoints, e.g. /documents/<selectedId>/submit). urlSig is
// collected into _sv (kept fresh by the computed→_sv bridge).
function _ssc_ui_fetchActionTo(method, urlSig, body, tick, headers) { return { _type: '_FetchAction', method, urlSig: urlSig || null, body, tick, headers: headers || null }; }
function _ssc_ui_incSignal(s) { return { _type: '_IncSignal', s }; }
function _ssc_ui_fetchActionClear(method, url, body, tick, headers) { return { _type: '_FetchActionClear', method, url, body, tick, headers: headers || null }; }
// Scope B.4 — structured onSuccess effects + fetchActionWith.
function _ssc_ui_onBumpTick(tick) { return { _eff: 'bumpTick', tick }; }
function _ssc_ui_onSetSignal(sig, value) { return { _eff: 'setSignal', s: sig, v: value }; }
function _ssc_ui_ensureRowlinkCss() {
  if (typeof document === 'undefined' || document.getElementById('ssc-rowlink-css')) return;
  var st = document.createElement('style'); st.id = 'ssc-rowlink-css';
  st.textContent = '.ssc-rowlink-selected::before{content:"✓ "}.ssc-rowlink-selected{font-weight:700;filter:brightness(1.25)}';
  document.head.appendChild(st);
}
function _ssc_ui_onNavigate(path) { return { _eff: 'navigate', path: String(path == null ? '' : path) }; }
function _ssc_ui_onOpenJson(urlTemplate, field) { return { _eff: 'openJson', urlTemplate: String(urlTemplate == null ? '' : urlTemplate), field: String(field == null ? '' : field) }; }
function _ssc_ui_fetchActionWith(method, url, body, onSuccess, headers) { return { _type: '_FetchAction', method, url, body, headers: headers || null, onSuccess: onSuccess || [] }; }
// Scope B.4+ — a request-body source assembled from named field signals at submit.
function _ssc_ui_formBody(fields) { return { _body: 'fields', fields: fields || [] }; }
// Resolve formBody field refs — each entry a bare ref or a [jsonKey, ref] pair, where
// ref is a signal NAME string (the common .ssc form) or a Signal object — into
// [[jsonKey, sigIdStr]] pairs plus the resolved Signal objects (for collectSig).
// Unresolvable refs pass through verbatim for back-compat with sv-by-name runtimes.
function _ssc_ui_resolveFormFields(fields) {
  const out = [], signals = [];
  (fields || []).forEach(function(f) {
    let key = f, ref = f;
    if (Array.isArray(f)) { key = f[0]; ref = f.length > 1 ? f[1] : f[0]; }
    const sig = (ref && ref._type === 'Signal') ? ref
              : (typeof _signalsByName !== 'undefined' ? _signalsByName.get(String(ref)) : null);
    if (sig && sig._type === 'Signal') { signals.push(sig); out.push([String(key), String(sig.id)]); }
    else out.push([String(key), String(ref)]);
  });
  return { fields: out, signals };
}
// Assemble a flat JSON object body `{ key: <signal value> }` from the named field
// signals (pure + testable: `sv` is the mount's value store).  Each entry is either
// a bare field name (the JSON key equals the signal id) or a `[jsonKey, signalId]`
// pair (Scope B.4+ keyed mapping — the JSON key differs from the signal id; a
// ScalaScript `(jsonKey, signalId)` tuple serialises to exactly this 2-array).
function _ssc_ui_buildFormBody(raw, sv) {
  try {
    var obj = {};
    JSON.parse(raw).forEach(function(f) {
      var key = f, sig = f;
      if (Array.isArray(f)) { key = f[0]; sig = f.length > 1 ? f[1] : f[0]; }
      obj[key] = sv[sig] == null ? '' : sv[sig];
    });
    return JSON.stringify(obj);
  } catch(_e) { return '{}'; }
}
// Run a fetch action's serialised onSuccess effects, in order, on a 2xx (`ok`).
// `setFn`/`sv` are the mount's signal setter + value store (passed in so the
// effect runner is a pure, testable top-level function).  A non-2xx runs nothing.
function _ssc_ui_runOnSuccess(raw, ok, setFn, sv, respText) {
  if (!ok || !raw) return;
  try {
    JSON.parse(raw).forEach(function(e) {
      if (e.eff === 'bumpTick' && e.id) setFn(e.id, ((sv[e.id] || 0) | 0) + 1);
      else if (e.eff === 'setSignal' && e.id) setFn(e.id, e.v);
      else if (e.eff === 'navigate' && typeof window !== 'undefined' && window.location) window.location.hash = e.path;
      else if (e.eff === 'openJson' && e.urlTemplate) {
        // Navigate to a URL built from the RESPONSE body: parse it as JSON, take e.field,
        // substitute :value. Lets a launch button open the created resource (e.g. the rozum
        // UCC session terminal) without a hand-rolled fetch. No-op if the field is absent.
        try {
          var j = JSON.parse(respText == null ? '{}' : String(respText));
          var v = j[e.field];
          if (v != null && typeof window !== 'undefined' && window.location)
            window.location.href = String(e.urlTemplate).replace(':value', encodeURIComponent(String(v)));
        } catch (_e2) {}
      }
    });
  } catch(_e) {}
}
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
function _ssc_ui_stackedColumn(title, fieldPath, subFieldPath, align) {
  return _ssc_ui_makeColumn(title, fieldPath, align, { type: 'stacked', subFieldPath: subFieldPath || '' }, null);
}
function _ssc_ui_rowDeleteAction(url, idField, tick, headers) { return { _type: '_RowDelete', url, idField, tick, headers: headers || null }; }
function _ssc_ui_rowPostAction(label, method, url, bodyField, tick, headers) { return { _type: '_RowPost', label, method, url, bodyField, tick, headers: headers || null }; }
function _ssc_ui_rowLinkAction(label, signal, fieldPath) { return { _type: '_RowLink', label, signal, fieldPath }; }
function _ssc_ui_rowEditAction(method, url, idField, tick, headers) { return { _type: '_RowInlineEdit', method, url, idField, tick, headers: headers || null }; }
// Snapshot the source's `_rowsPath` onto the node at BUILD time so multiple
// DataTables sharing one fetch signal each keep their OWN dotted path: a later
// `fetchRowsSource(sameSig, otherPath)` mutates `sig._rowsPath`, but each node
// already froze the path it was built with (the shared-signal collision fix).
function _ssc_ui_dataTableView(signal, columns, actions) { return { _type: '_DataTableView', signal, columns: columns || [], actions: actions || [], _rowsPath: (signal && signal._rowsPath) || '' }; }
// TableDataSource markers — first argument to dataTableView (parity with the
// interpreter TableDataSource.StaticRows / SignalRows; the legacy bare
// FetchUrlSignal Remote path is still accepted via `._fetchGet`).
function _ssc_ui_staticRowsSource(rows) { return { _source: 'static', rows: rows || [] }; }
function _ssc_ui_signalRowsSource(sig) { return { _source: 'signal', sig }; }
// fetchRowsSource — a managed-fetch Remote source carrying an optional dotted
// envelope path (Scope B.3).  Attaches `_rowsPath` to the fetch signal so the
// existing Remote render path (`._fetchGet`) is reused unchanged.
function _ssc_ui_fetchRowsSource(sig, rowsPath) { if (sig) sig._rowsPath = rowsPath || ''; return sig; }
// RowPayload markers — the `payload` argument of rowPostAction (parity with the
// interpreter RowPayload.Field / WholeRow / Fields).  Resolved against a row in
// _ssc_ui_mount's _RowPost handler (see _ssc_ui_resolveRowPayload).
function _ssc_ui_fieldPayload(name) { return { _payload: 'field', name }; }
function _ssc_ui_wholeRowPayload() { return { _payload: 'wholeRow' }; }
function _ssc_ui_fieldsPayload(names) { return { _payload: 'fields', names: names || [] }; }
// Normalise a DataTable data value into an array of row objects.  A fetch
// response / rows signal may be a bare array, a JSON string, or a common list
// envelope ({data|rows|items|results:[...]}, e.g. {"data":[...],"count":N}).
// Anything else (a plain object with no list field, an HTML body, null) → [] so
// the renderer never does `(rows||[]).forEach` on a non-array.
function _ssc_ui_rowsOf(v, rowsPath) {
  if (Array.isArray(v)) return v;
  if (typeof v === 'string') {
    var t = v.trim();
    if (!t || t.charAt(0) === '<') return [];   // empty or an HTML body, not JSON
    try { return _ssc_ui_rowsOf(JSON.parse(t), rowsPath); } catch(_e) { return []; }
  }
  if (v && typeof v === 'object') {
    if (rowsPath) {
      // Scope B.3 — drill a dotted envelope path (`result.items`) first; if it
      // yields an array use it, else fall through to the built-in keys below.
      var cur = v, parts = String(rowsPath).split('.');
      for (var i = 0; i < parts.length && cur != null; i++) cur = cur[parts[i]];
      if (Array.isArray(cur)) return cur;
    }
    if (Array.isArray(v.data))    return v.data;
    if (Array.isArray(v.rows))    return v.rows;
    if (Array.isArray(v.items))   return v.items;
    if (Array.isArray(v.results)) return v.results;
  }
  return [];
}
