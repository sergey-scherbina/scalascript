package scalascript.codegen

import scalascript.typeddata.TypedJsonCodecRuntime

/** Runtime preamble for typed route clients over the HTTP transport
 *  (fetch-based request/retry/cancel, WebSocket, and SSE/streaming helpers
 *  plus the typed-JSON codec facade).
 *
 *  Extracted verbatim from `JsGen.scala` (jsgen-split-p1); `JsGen` references
 *  it as `JsRuntimeHttpClient.source`, keeping emitted JS byte-identical. */
object JsRuntimeHttpClient:

  val source: String =
    """|// ── Typed route clients: HTTP transport ───────────────────────────
       |function _ssc_api_url_encode(value) {
       |  return encodeURIComponent(String(value));
       |}
       |
       |function _ssc_api_product_fields(value) {
       |  if (value == null) return {};
       |  if (typeof value !== 'object') return {};
       |  const out = {};
       |  for (const key of Object.keys(value)) {
       |    if (key !== '_type' && typeof value[key] !== 'function') out[key] = value[key];
       |  }
       |  return out;
       |}
       |
       |function _ssc_api_path_param_names(pathTemplate) {
       |  return String(pathTemplate).split('/').filter(s => s.startsWith(':')).map(s => s.slice(1));
       |}
       |
       |function _ssc_api_path(pathTemplate, input) {
       |  const names = _ssc_api_path_param_names(pathTemplate);
       |  const fields = _ssc_api_product_fields(input);
       |  const primitiveForSingleParam =
       |    names.length === 1 && Object.keys(fields).length === 0 && input !== undefined && input !== null;
       |  return String(pathTemplate).split('/').map(segment => {
       |    if (!segment.startsWith(':')) return segment;
       |    const name = segment.slice(1);
       |    const value = primitiveForSingleParam ? input : fields[name];
       |    if (value === undefined || value === null) {
       |      throw new Error("typed route client: missing path field '" + name + "'");
       |    }
       |    return _ssc_api_url_encode(value);
       |  }).join('/');
       |}
       |
       |function _ssc_api_query(pathTemplate, input) {
       |  const used = new Set(_ssc_api_path_param_names(pathTemplate));
       |  const fields = _ssc_api_product_fields(input);
       |  const pairs = [];
       |  for (const key of Object.keys(fields)) {
       |    if (!used.has(key) && fields[key] !== undefined && fields[key] !== null) {
       |      pairs.push(_ssc_api_url_encode(key) + "=" + _ssc_api_url_encode(fields[key]));
       |    }
       |  }
       |  return pairs.length === 0 ? "" : "?" + pairs.join("&");
       |}
       |
       |function _ssc_api_body(method, input, requestType) {
       |  if (method === "GET" || input === undefined || input === null) return undefined;
       |  return _ssc_typed_json_encode(input, requestType);
       |}
       |
       |""".stripMargin + TypedJsonCodecRuntime.jsFacade + """|let _ssc_api_extra_headers = {};
       |function _ssc_api_set_headers(headers) {
       |  _ssc_api_extra_headers = Object.assign({}, headers || {});
       |}
       |function _ssc_set_auth_token(token) {
       |  if (!token) {
       |    const h = Object.assign({}, _ssc_api_extra_headers);
       |    delete h['Authorization'];
       |    _ssc_api_extra_headers = h;
       |  } else {
       |    _ssc_api_extra_headers = Object.assign({}, _ssc_api_extra_headers, {'Authorization': 'Bearer ' + token});
       |  }
       |}
       |let _ssc_api_retry_policy = {maxRetries: 0, delayMs: 0};
       |function _ssc_api_set_retry(maxRetries, delayMs) {
       |  _ssc_api_retry_policy = {maxRetries: maxRetries | 0, delayMs: delayMs | 0};
       |}
       |function _ssc_api_cancel_token() {
       |  const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
       |  const token = { signal: ctrl ? ctrl.signal : null, cancelled: false,
       |    cancel: function() { token.cancelled = true; if (ctrl) ctrl.abort(); } };
       |  return token;
       |}
       |async function _ssc_api_request(methodRaw, pathTemplate, input, requestType, responseType, callHeaders, cancelToken) {
       |  if (cancelToken && cancelToken.cancelled) throw new Error("typed route client: request cancelled");
       |  const method = String(methodRaw).toUpperCase();
       |  const url = _ssc_api_path(pathTemplate, input) + (method === "GET" ? _ssc_api_query(pathTemplate, input) : "");
       |  const body = _ssc_api_body(method, input, requestType);
       |  const maxRetries = _ssc_api_retry_policy.maxRetries;
       |  const delayMs = _ssc_api_retry_policy.delayMs;
       |  for (let attempt = 0; ; attempt++) {
       |    if (cancelToken && cancelToken.cancelled) throw new Error("typed route client: request cancelled");
       |    const init = { method: method, headers: Object.assign({}, _ssc_api_extra_headers, callHeaders || {}) };
       |    if (body !== undefined) { init.body = body; init.headers["Content-Type"] = "application/json"; }
       |    if (cancelToken && cancelToken.signal) init.signal = cancelToken.signal;
       |    let response, text;
       |    try {
       |      response = await fetch(url, init);
       |      text = await response.text();
       |    } catch (e) {
       |      if (cancelToken && cancelToken.cancelled) throw new Error("typed route client: request cancelled");
       |      if (attempt < maxRetries) { if (delayMs > 0) await new Promise(r => setTimeout(r, delayMs)); continue; }
       |      throw e;
       |    }
       |    if (!response.ok) {
       |      if (response.status >= 500 && attempt < maxRetries) { if (delayMs > 0) await new Promise(r => setTimeout(r, delayMs)); continue; }
       |      throw new Error("typed route client: " + method + " " + url + " returned " + response.status + ": " + text);
       |    }
       |    const contentType = response.headers && response.headers.get ? response.headers.get("content-type") || "" : "";
       |    return _ssc_typed_json_decode_response(text, contentType, responseType);
       |  }
       |}
       |
       |function _ssc_api_base_url() {
       |  if (typeof globalThis !== 'undefined' && globalThis.__sscBackendBaseUrl) return String(globalThis.__sscBackendBaseUrl).replace(/\/$/, '');
       |  return '';
       |}
       |
       |function _ssc_api_ws_request(pathTemplate, input, onEvent, onError, onOpen, responseType, callHeaders) {
       |  const base = _ssc_api_base_url();
       |  const path = _ssc_api_path(pathTemplate, input);
       |  const query = _ssc_api_query(pathTemplate, input);
       |  const httpBase = base || (typeof location !== 'undefined' ? location.origin : '');
       |  const wsBase = httpBase.replace(/^https?:\/\//, function(m) { return m === 'https://' ? 'wss://' : 'ws://'; });
       |  const url = wsBase + path + query;
       |  let _wsClosed = false;
       |  let _ws = null;
       |  const _pending = [];
       |  _ws = new WebSocket(url);
       |  if (input != null && input !== undefined) {
       |    _ws.addEventListener('open', function() {
       |      try { _ws.send(JSON.stringify(input)); } catch(e) {}
       |      if (typeof onOpen === 'function') onOpen({ send: function(msg) { _ws.send(typeof msg === 'string' ? msg : JSON.stringify(msg)); }, close: function() { _ws.close(); } });
       |    });
       |  } else {
       |    _ws.addEventListener('open', function() {
       |      if (typeof onOpen === 'function') onOpen({ send: function(msg) { _ws.send(typeof msg === 'string' ? msg : JSON.stringify(msg)); }, close: function() { _ws.close(); } });
       |    });
       |  }
       |  _ws.addEventListener('message', function(e) {
       |    try { if (typeof onEvent === 'function') onEvent(_ssc_typed_json_decode_response(e.data, 'application/json', responseType)); }
       |    catch (err) { if (typeof onError === 'function') onError(String(err)); }
       |  });
       |  _ws.addEventListener('error', function(e) { if (!_wsClosed && typeof onError === 'function') onError('WebSocket error'); });
       |  _ws.addEventListener('close', function(e) {
       |    _wsClosed = true;
       |    if (!e.wasClean && typeof onError === 'function') onError('WebSocket closed unexpectedly: ' + e.code);
       |  });
       |  return {
       |    close: function() { _wsClosed = true; if (_ws) _ws.close(); },
       |    send: function(msg) { if (_ws && _ws.readyState === 1) _ws.send(typeof msg === 'string' ? msg : JSON.stringify(msg)); }
       |  };
       |}
       |
       |function _ssc_api_stream_request(method, pathTemplate, input, onEvent, onError, responseType, callHeaders) {
       |  const base = _ssc_api_base_url();
       |  const path = _ssc_api_path(pathTemplate, input);
       |  const query = method === "GET" ? _ssc_api_query(pathTemplate, input) : "";
       |  const url = base + path + query;
       |  const allHeaders = Object.assign({}, _ssc_api_extra_headers, callHeaders || {});
       |  const hasCustomHeaders = Object.keys(allHeaders).length > 0;
       |  if (!hasCustomHeaders && method === "GET" && typeof EventSource !== 'undefined') {
       |    const es = new EventSource(url);
       |    es.onmessage = function(e) {
       |      try { if (typeof onEvent === 'function') onEvent(_ssc_typed_json_decode_response(e.data, 'application/json', responseType)); }
       |      catch (err) { if (typeof onError === 'function') onError(String(err)); }
       |    };
       |    es.onerror = function() { if (typeof onError === 'function') onError('EventSource error'); };
       |    return { close: function() { es.close(); } };
       |  }
       |  let _streamClosed = false;
       |  const _abort = typeof AbortController !== 'undefined' ? new AbortController() : null;
       |  (async function() {
       |    try {
       |      const init = { method: method, headers: Object.assign({ 'Accept': 'text/event-stream' }, allHeaders) };
       |      if (_abort) init.signal = _abort.signal;
       |      if (method !== "GET" && input != null && input !== undefined) {
       |        init.body = JSON.stringify(input);
       |        init.headers['Content-Type'] = 'application/json';
       |      }
       |      const res = await fetch(url, init);
       |      if (!res || !res.ok) {
       |        if (typeof onError === 'function') onError('stream request failed: ' + (res ? res.status : 'no response'));
       |        return;
       |      }
       |      if (!res.body || typeof res.body.getReader !== 'function') {
       |        if (typeof onError === 'function') onError('ReadableStream not supported');
       |        return;
       |      }
       |      const reader = res.body.getReader();
       |      const dec = typeof TextDecoder !== 'undefined' ? new TextDecoder() : null;
       |      let buf = '';
       |      while (!_streamClosed) {
       |        const { done, value } = await reader.read();
       |        if (done) break;
       |        const chunk = dec ? dec.decode(value, { stream: true }) : String.fromCharCode(...value);
       |        buf += chunk;
       |        const lines = buf.split('\n');
       |        buf = lines.pop();
       |        for (const line of lines) {
       |          if (line.startsWith('data: ')) {
       |            const data = line.slice(6);
       |            try { if (typeof onEvent === 'function') onEvent(_ssc_typed_json_decode_response(data, 'application/json', responseType)); }
       |            catch (err) { if (typeof onError === 'function') onError(String(err)); }
       |          }
       |        }
       |      }
       |    } catch (e) {
       |      if (!_streamClosed && typeof onError === 'function') onError(String(e));
       |    }
       |  })();
       |  return { close: function() { _streamClosed = true; if (_abort) _abort.abort(); } };
       |}
       |
       |""".stripMargin
