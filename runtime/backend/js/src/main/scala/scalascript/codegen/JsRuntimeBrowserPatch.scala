package scalascript.codegen

/** Browser-SPA overlay loaded AFTER `JsRuntime` so its `serve(...)` /
 *  `console.log`-based output flush replace the Node-target versions.
 *  The Node-only helpers (`_serveStatic`, `_contentTypeFor`, `require('http')`)
 *  are never invoked, so they sit as dead code without crashing the page. */
val JsRuntimeBrowserPatch: String = """
// ── Browser / Electron SPA overlay ───────────────────────────────────────
// Replaces serve(view, port) with a route-register + popstate dispatcher.
// _ssc_http_serve is stubbed so _ssc_ui_serve can register routes without
// trying to require('http') in the renderer process.
// Same route(method, path)(handler) surface as the Node target; same
// Response shape; same _routes / _matchPath / _mkRequest reused unchanged.

_ssc_http_serve = function() {}   // no-op: no TCP server in the browser/Electron renderer

function _spaFlush() {
  if (_output.length) {
    for (const line of _output) console.log(line);
    _output = [];
  }
}

function _spaRender(response) {
  if (!response) { _spaFlush(); return; }
  const status  = response.status ?? 200;
  const headers = _isMap(response.headers) ? response.headers : new Map();
  const ct      = (headers.get('Content-Type') || headers.get('content-type') || '').toLowerCase();
  if (status >= 300 && status < 400) {
    const loc = headers.get('Location') || headers.get('location');
    if (loc) { _spaNavigate(loc, true); return; }
  }
  const body = response.body ?? '';
  if (ct.startsWith('text/html')) {
    // Replace just the body so <head>/<title>/<script> stay intact across
    // navigations — the SPA runtime itself lives in the original <script>.
    document.body.innerHTML = body;
  } else if (ct.startsWith('application/json')) {
    document.body.innerHTML = '<pre>' + body.replace(/&/g, '&amp;').replace(/</g, '&lt;') + '</pre>';
  } else {
    document.body.textContent = body;
  }
  _spaFlush();
}

function _spaDispatch(method, pathname, body) {
  const response = _spaRouteResponse(method, pathname, body);
  if (!response) {
    document.body.textContent = 'Not Found: ' + pathname;
    _spaFlush();
    return false;
  }
  _spaRender(response);
  return true;
}

function _spaRouteResponse(method, pathname, body) {
  const segs = pathname.split('/').filter(s => s.length > 0);
  for (const r of _routes) {
    if (r.method !== method) continue;
    const params = _matchPath(r.pattern, segs);
    if (params == null) continue;
    const request = {
      _type: 'Request',
      method,
      path:    pathname,
      params,
      query:   new Map(),
      headers: new Map(),
      body:    body || '',
      form:    new Map(),
    };
    try { return r.handler(request); }
    catch (e) {
      return Response.status(500, 'SPA route error: ' + (e && e.message ? e.message : e));
    }
  }
  return null;
}

function _spaNavigate(pathname, replace) {
  if (replace) history.replaceState({}, '', pathname);
  else         history.pushState({}, '', pathname);
  _spaDispatch('GET', pathname);
}

function _spaFetchResponse(resp) {
  const status = resp && resp.status ? resp.status : 200;
  const headers = resp && _isMap(resp.headers) ? resp.headers : new Map();
  const body = resp && resp.body != null ? String(resp.body) : '';
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: {
      get(name) {
        const wanted = String(name).toLowerCase();
        for (const [k, v] of headers) if (String(k).toLowerCase() === wanted) return String(v);
        return null;
      }
    },
    text() { return Promise.resolve(body); },
    json() { return Promise.resolve(JSON.parse(body)); }
  };
}

function _spaFetchPath(input) {
  if (typeof input === 'string') {
    if (input.startsWith('/') && !input.startsWith('//')) return input;
    try {
      const u = new URL(input, location.href);
      if (u.origin === location.origin && u.pathname) return u.pathname + u.search;
    } catch (_) {}
  } else if (input && typeof input.url === 'string') {
    return _spaFetchPath(input.url);
  }
  return null;
}

const _ssc_native_fetch = globalThis.fetch ? globalThis.fetch.bind(globalThis) : null;
globalThis.fetch = function(input, init) {
  const rawPath = _spaFetchPath(input);
  if (!rawPath) {
    if (_ssc_native_fetch) return _ssc_native_fetch(input, init);
    return Promise.reject(new Error('fetch is not available'));
  }
  if (globalThis.__sscBackendBaseUrl && _ssc_native_fetch) {
    const target = new URL(rawPath, String(globalThis.__sscBackendBaseUrl)).toString();
    const mergedInit = globalThis.__sscDesktopToken
      ? { ...init, headers: { ...(init && init.headers), 'x-scalascript-desktop-token': globalThis.__sscDesktopToken } }
      : init;
    return _ssc_native_fetch(target, mergedInit);
  }
  const method = String((init && init.method) || (input && input.method) || 'GET').toUpperCase();
  const pathOnly = rawPath.split('?')[0] || '/';
  const rawBody = init && init.body != null ? init.body : '';
  return Promise.resolve(rawBody)
    .then(body => {
      const response = _spaRouteResponse(method, pathOnly, body == null ? '' : String(body));
      if (response) return _spaFetchResponse(response);
      if (_ssc_native_fetch) return _ssc_native_fetch(input, init);
      return _spaFetchResponse(Response.notFound('Not Found: ' + pathOnly));
    });
};

// In browser/Electron there's no port to bind.
// Overrides _ssc_ui_serve so that std.ui.primitives.serve = _ssc_ui_serve
// dispatches here.  No eval(), no DOM <script> injection — both blocked by
// script-src 'self' CSP (Electron default).
_ssc_ui_serve = function(treeOrPort, portOrUndef, extraCssOrUndef) {
  if (typeof treeOrPort !== 'number') {
    const extraCss = extraCssOrUndef || '';
    const { body, sigs } = _ssc_ui_renderBody(treeOrPort);
    const style = document.createElement('style');
    style.textContent = [
      '*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}',
      'body{margin:0;padding:0;background:#fff;-webkit-text-size-adjust:100%}',
      '.ssc-page{max-width:700px;margin:0 auto;padding:24px 20px;font-size:16px;',
      'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",system-ui,sans-serif}',
      'input[type=checkbox]{width:22px;height:22px;accent-color:#2563eb;cursor:pointer;flex-shrink:0}',
      'button{touch-action:manipulation;cursor:pointer}',
      'button:disabled{opacity:.5;cursor:default}',
      '[data-ssc-cond]{display:contents}',
      'hr{border:none;border-top:1px solid #e5e7eb;margin:0}',
      '[data-ssc-fetch-table] table,[data-ssc-fetch-table] th,',
      '[data-ssc-fetch-table] td,[data-ssc-fetch-table] button{font-size:inherit;font-family:inherit}',
      '@keyframes spin{from{transform:rotate(0deg)}to{transform:rotate(360deg)}}',
      '.ssc-spin{animation:spin 0.8s linear infinite}',
      extraCss
    ].join('');
    document.head.appendChild(style);
    const app = document.getElementById('app') || document.body;
    const wrapper = document.createElement('div');
    wrapper.className = 'ssc-page';
    wrapper.innerHTML = body;
    app.appendChild(wrapper);
    _ssc_ui_mount(sigs);
    return;
  }
  // Port-only serve: SPA router for apps that register routes via get()/post()
  document.addEventListener('click', e => {
    const a = e.target && e.target.closest && e.target.closest('a');
    if (!a) return;
    const href = a.getAttribute('href');
    if (!href) return;
    if (/^(https?:)?\/\//.test(href) || href.startsWith('#') || href.startsWith('mailto:')) return;
    e.preventDefault();
    _spaNavigate(href, false);
  });
  window.addEventListener('popstate', () => {
    _spaDispatch('GET', location.pathname || '/');
  });
  _spaDispatch('GET', '/');
}
"""
