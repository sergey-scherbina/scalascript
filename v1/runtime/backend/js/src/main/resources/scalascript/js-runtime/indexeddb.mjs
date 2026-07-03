
// ── Client IndexedDB object store facade ───────────────────────────────
//
// `IndexedDb.store[Todo]("todos")` returns a Promise-based local object
// store. Browser/Electron clients use native IndexedDB. Node/tests fall back
// to an in-memory Map, persisted through localStorage when available.
const _ssc_indexeddb_memory = globalThis.__sscIndexedDbMemory || (globalThis.__sscIndexedDbMemory = new Map());

function _ssc_indexeddb_store_key(dbName, storeName) {
  return String(dbName || "ssc") + "::" + String(storeName || "default");
}

function _ssc_indexeddb_local_storage() {
  try {
    if (typeof window !== "undefined" && window.localStorage) return window.localStorage;
  } catch (_) {}
  return null;
}

function _ssc_indexeddb_memory_store(dbName, storeName) {
  const key = _ssc_indexeddb_store_key(dbName, storeName);
  if (!_ssc_indexeddb_memory.has(key)) {
    const map = new Map();
    const storage = _ssc_indexeddb_local_storage();
    if (storage) {
      try {
        const raw = storage.getItem("__ssc_indexeddb:" + key);
        if (raw) {
          const obj = JSON.parse(raw);
          for (const k of Object.keys(obj)) map.set(k, obj[k]);
        }
      } catch (_) {}
    }
    _ssc_indexeddb_memory.set(key, map);
  }
  return _ssc_indexeddb_memory.get(key);
}

function _ssc_indexeddb_memory_flush(dbName, storeName, map) {
  const storage = _ssc_indexeddb_local_storage();
  if (!storage) return;
  try {
    const obj = {};
    for (const [k, v] of map.entries()) obj[k] = v;
    storage.setItem("__ssc_indexeddb:" + _ssc_indexeddb_store_key(dbName, storeName), JSON.stringify(obj));
  } catch (_) {}
}

function _ssc_indexeddb_decode(value, typeName) {
  return _ssc_typed_json_decode_value(value, typeName || "");
}

function _ssc_indexeddb_encode(value, typeName) {
  return _ssc_typed_json_plain(value, typeName || "");
}

function _ssc_indexeddb_key(value, plain, keyField) {
  if (value != null && typeof value === "object" && value[keyField] !== undefined && value[keyField] !== null) {
    return String(value[keyField]);
  }
  if (plain != null && typeof plain === "object" && plain[keyField] !== undefined && plain[keyField] !== null) {
    return String(plain[keyField]);
  }
  throw new Error("IndexedDb.put: missing key field '" + keyField + "'; pass an explicit key or choose another key field");
}

function _ssc_indexeddb_native_available() {
  return typeof indexedDB !== "undefined" && indexedDB && typeof indexedDB.open === "function";
}

function _ssc_indexeddb_open_native(dbName, storeName) {
  return new Promise((resolve, reject) => {
    const first = indexedDB.open(dbName);
    first.onerror = () => reject(first.error || new Error("IndexedDB open failed"));
    first.onupgradeneeded = () => {
      const db = first.result;
      if (!db.objectStoreNames.contains(storeName)) db.createObjectStore(storeName);
    };
    first.onsuccess = () => {
      const db = first.result;
      if (db.objectStoreNames.contains(storeName)) {
        resolve(db);
        return;
      }
      const nextVersion = db.version + 1;
      db.close();
      const upgrade = indexedDB.open(dbName, nextVersion);
      upgrade.onerror = () => reject(upgrade.error || new Error("IndexedDB upgrade failed"));
      upgrade.onupgradeneeded = () => {
        const upgraded = upgrade.result;
        if (!upgraded.objectStoreNames.contains(storeName)) upgraded.createObjectStore(storeName);
      };
      upgrade.onsuccess = () => resolve(upgrade.result);
    };
  });
}

function _ssc_indexeddb_request(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error || new Error("IndexedDB request failed"));
  });
}

function _ssc_indexeddb_tx_done(tx) {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error || new Error("IndexedDB transaction failed"));
    tx.onabort = () => reject(tx.error || new Error("IndexedDB transaction aborted"));
  });
}

function _ssc_indexeddb_make_store(storeName, typeName, dbName, keyField) {
  const store = String(storeName || "default");
  const tpe = String(typeName || "");
  const db = String(dbName || "ssc");
  const key = String(keyField || "id");

  async function withNative(mode, f) {
    const handle = await _ssc_indexeddb_open_native(db, store);
    try {
      const tx = handle.transaction(store, mode);
      const objectStore = tx.objectStore(store);
      const result = await f(objectStore);
      if (mode !== "readonly") await _ssc_indexeddb_tx_done(tx);
      return result;
    } finally {
      handle.close();
    }
  }

  function withMemory(f) {
    const map = _ssc_indexeddb_memory_store(db, store);
    const result = f(map);
    _ssc_indexeddb_memory_flush(db, store, map);
    return Promise.resolve(result);
  }

  return {
    put(value, explicitKey) {
      const plain = _ssc_indexeddb_encode(value, tpe);
      const objectKey = explicitKey === undefined || explicitKey === null ? _ssc_indexeddb_key(value, plain, key) : String(explicitKey);
      if (_ssc_indexeddb_native_available()) {
        return withNative("readwrite", os => _ssc_indexeddb_request(os.put(plain, objectKey))).then(() => value);
      }
      return withMemory(map => { map.set(objectKey, plain); return value; });
    },
    get(objectKey) {
      const k = String(objectKey);
      if (_ssc_indexeddb_native_available()) {
        return withNative("readonly", os => _ssc_indexeddb_request(os.get(k)))
          .then(value => value === undefined ? _None : _Some(_ssc_indexeddb_decode(value, tpe)));
      }
      return withMemory(map => map.has(k) ? _Some(_ssc_indexeddb_decode(map.get(k), tpe)) : _None);
    },
    remove(objectKey) {
      const k = String(objectKey);
      if (_ssc_indexeddb_native_available()) {
        return withNative("readwrite", os => _ssc_indexeddb_request(os.delete(k))).then(() => undefined);
      }
      return withMemory(map => { map.delete(k); return undefined; });
    },
    delete(objectKey) {
      return this.remove(objectKey);
    },
    keys() {
      if (_ssc_indexeddb_native_available()) {
        return withNative("readonly", os => {
          if (typeof os.getAllKeys === "function") return _ssc_indexeddb_request(os.getAllKeys()).then(keys => keys.map(String));
          return new Promise((resolve, reject) => {
            const keys = [];
            const req = os.openCursor();
            req.onerror = () => reject(req.error || new Error("IndexedDB cursor failed"));
            req.onsuccess = () => {
              const cursor = req.result;
              if (!cursor) resolve(keys);
              else { keys.push(String(cursor.key)); cursor.continue(); }
            };
          });
        });
      }
      return withMemory(map => Array.from(map.keys()));
    },
    all() {
      if (_ssc_indexeddb_native_available()) {
        return withNative("readonly", os => _ssc_indexeddb_request(os.getAll()))
          .then(values => values.map(value => _ssc_indexeddb_decode(value, tpe)));
      }
      return withMemory(map => Array.from(map.values()).map(value => _ssc_indexeddb_decode(value, tpe)));
    },
    entries() {
      if (_ssc_indexeddb_native_available()) {
        return withNative("readonly", os => {
          return new Promise((resolve, reject) => {
            const rows = [];
            const req = os.openCursor();
            req.onerror = () => reject(req.error || new Error("IndexedDB cursor failed"));
            req.onsuccess = () => {
              const cursor = req.result;
              if (!cursor) resolve(rows);
              else {
                rows.push({ key: String(cursor.key), value: _ssc_indexeddb_decode(cursor.value, tpe) });
                cursor.continue();
              }
            };
          });
        });
      }
      return withMemory(map => Array.from(map.entries()).map(([k, v]) => ({ key: String(k), value: _ssc_indexeddb_decode(v, tpe) })));
    },
    clear() {
      if (_ssc_indexeddb_native_available()) {
        return withNative("readwrite", os => _ssc_indexeddb_request(os.clear())).then(() => undefined);
      }
      return withMemory(map => { map.clear(); return undefined; });
    }
  };
}

const IndexedDb = {
  store(storeName, typeName = "", dbName = "ssc", keyField = "id") {
    return _ssc_indexeddb_make_store(storeName, typeName, dbName, keyField);
  }
};

function _ssc_sync_base_url(serverUrl) {
  const raw = serverUrl === undefined || serverUrl === null ? "" : String(serverUrl);
  return raw.endsWith("/") ? raw.slice(0, -1) : raw;
}

function _ssc_sync_cursor_key(dbName, storeName) {
  return "__ssc_sync_cursor:" + _ssc_indexeddb_store_key(dbName, storeName);
}

function _ssc_sync_queue_key(dbName, storeName) {
  return "__ssc_sync_queue:" + _ssc_indexeddb_store_key(dbName, storeName);
}

function _ssc_sync_versions_key(dbName, storeName) {
  return "__ssc_sync_versions:" + _ssc_indexeddb_store_key(dbName, storeName);
}

function _ssc_sync_conflicts_key(dbName, storeName) {
  return "__ssc_sync_conflicts:" + _ssc_indexeddb_store_key(dbName, storeName);
}

function _ssc_sync_json_get(key, fallback) {
  const storage = _ssc_indexeddb_local_storage();
  if (!storage) {
    const mem = globalThis.__sscSyncMemory || (globalThis.__sscSyncMemory = new Map());
    return mem.has(key) ? mem.get(key) : fallback;
  }
  try {
    const raw = storage.getItem(key);
    return raw == null ? fallback : JSON.parse(raw);
  } catch (_) {
    return fallback;
  }
}

function _ssc_sync_json_set(key, value) {
  const storage = _ssc_indexeddb_local_storage();
  if (!storage) {
    const mem = globalThis.__sscSyncMemory || (globalThis.__sscSyncMemory = new Map());
    mem.set(key, value);
    return;
  }
  storage.setItem(key, JSON.stringify(value));
}

function _ssc_sync_get_cursor(dbName, storeName) {
  const raw = _ssc_sync_json_get(_ssc_sync_cursor_key(dbName, storeName), 0);
  const parsed = raw == null ? 0 : Number(raw);
  return Number.isFinite(parsed) ? parsed : 0;
}

function _ssc_sync_set_cursor(dbName, storeName, cursor) {
  _ssc_sync_json_set(_ssc_sync_cursor_key(dbName, storeName), Number(cursor || 0));
}

function _ssc_sync_get_queue(dbName, storeName) {
  const raw = _ssc_sync_json_get(_ssc_sync_queue_key(dbName, storeName), []);
  return Array.isArray(raw) ? raw : [];
}

function _ssc_sync_set_queue(dbName, storeName, queue) {
  _ssc_sync_json_set(_ssc_sync_queue_key(dbName, storeName), Array.isArray(queue) ? queue : []);
}

function _ssc_sync_get_versions(dbName, storeName) {
  const raw = _ssc_sync_json_get(_ssc_sync_versions_key(dbName, storeName), {});
  return raw && typeof raw === "object" && !Array.isArray(raw) ? raw : {};
}

function _ssc_sync_set_versions(dbName, storeName, versions) {
  _ssc_sync_json_set(_ssc_sync_versions_key(dbName, storeName), versions || {});
}

function _ssc_sync_expected_version(dbName, storeName, key) {
  const versions = _ssc_sync_get_versions(dbName, storeName);
  const value = versions[String(key)];
  return value === undefined || value === null ? undefined : Number(value);
}

function _ssc_sync_set_version(dbName, storeName, key, version) {
  const n = Number(version);
  if (!Number.isFinite(n)) return;
  const versions = _ssc_sync_get_versions(dbName, storeName);
  versions[String(key)] = n;
  _ssc_sync_set_versions(dbName, storeName, versions);
}

function _ssc_sync_forget_version(dbName, storeName, key) {
  const versions = _ssc_sync_get_versions(dbName, storeName);
  delete versions[String(key)];
  _ssc_sync_set_versions(dbName, storeName, versions);
}

function _ssc_sync_enqueue(dbName, storeName, mutation) {
  const key = String(mutation.key);
  const queue = _ssc_sync_get_queue(dbName, storeName).filter(item => String(item.key) !== key);
  queue.push({ ...mutation, key, queuedAt: new Date().toISOString() });
  _ssc_sync_set_queue(dbName, storeName, queue);
  return mutation;
}

function _ssc_sync_ack(dbName, storeName, results, conflicts) {
  if (Array.isArray(results)) {
    for (const row of results) {
      if (row && row.key !== undefined && row.version !== undefined) _ssc_sync_set_version(dbName, storeName, row.key, row.version);
    }
  }
  const conflictKeys = new Set(Array.isArray(conflicts) ? conflicts.map(row => String(row.key)) : []);
  const resultKeys = new Set(Array.isArray(results) ? results.map(row => String(row.key)) : []);
  const queue = _ssc_sync_get_queue(dbName, storeName).filter(item => !resultKeys.has(String(item.key)) || conflictKeys.has(String(item.key)));
  _ssc_sync_set_queue(dbName, storeName, queue);
  const existingConflicts = _ssc_sync_get_conflicts(dbName, storeName).filter(item => !resultKeys.has(String(item.key)));
  const merged = existingConflicts.filter(item => !conflictKeys.has(String(item.key)));
  if (Array.isArray(conflicts)) {
    for (const row of conflicts) {
      if (row && row.key !== undefined) merged.push(row);
    }
  }
  _ssc_sync_set_conflicts(dbName, storeName, merged);
}

function _ssc_sync_get_conflicts(dbName, storeName) {
  const raw = _ssc_sync_json_get(_ssc_sync_conflicts_key(dbName, storeName), []);
  return Array.isArray(raw) ? raw : [];
}

function _ssc_sync_set_conflicts(dbName, storeName, conflicts) {
  _ssc_sync_json_set(_ssc_sync_conflicts_key(dbName, storeName), Array.isArray(conflicts) ? conflicts : []);
}

function _ssc_sync_clear_conflict(dbName, storeName, key) {
  const k = String(key);
  _ssc_sync_set_conflicts(dbName, storeName, _ssc_sync_get_conflicts(dbName, storeName).filter(item => String(item.key) !== k));
}

function _ssc_sync_drop_queue_key(dbName, storeName, key) {
  const k = String(key);
  _ssc_sync_set_queue(dbName, storeName, _ssc_sync_get_queue(dbName, storeName).filter(item => String(item.key) !== k));
}

function _ssc_sync_find_conflict(dbName, storeName, key) {
  const k = String(key);
  return _ssc_sync_get_conflicts(dbName, storeName).find(item => String(item.key) === k);
}

function _ssc_sync_last_pulled_key(dbName, storeName) {
  return "__ssc_sync_last_pulled:" + _ssc_indexeddb_store_key(dbName, storeName);
}
function _ssc_sync_last_pushed_key(dbName, storeName) {
  return "__ssc_sync_last_pushed:" + _ssc_indexeddb_store_key(dbName, storeName);
}
function _ssc_sync_get_last_pulled(dbName, storeName) {
  const raw = _ssc_sync_json_get(_ssc_sync_last_pulled_key(dbName, storeName), null);
  return typeof raw === "number" ? raw : null;
}
function _ssc_sync_set_last_pulled(dbName, storeName) {
  _ssc_sync_json_set(_ssc_sync_last_pulled_key(dbName, storeName), Date.now());
}
function _ssc_sync_get_last_pushed(dbName, storeName) {
  const raw = _ssc_sync_json_get(_ssc_sync_last_pushed_key(dbName, storeName), null);
  return typeof raw === "number" ? raw : null;
}
function _ssc_sync_set_last_pushed(dbName, storeName) {
  _ssc_sync_json_set(_ssc_sync_last_pushed_key(dbName, storeName), Date.now());
}

const _ssc_sync_in_progress = new Set();
function _ssc_sync_begin(dbName, storeName) {
  _ssc_sync_in_progress.add(_ssc_indexeddb_store_key(dbName, storeName));
}
function _ssc_sync_finish(dbName, storeName) {
  _ssc_sync_in_progress.delete(_ssc_indexeddb_store_key(dbName, storeName));
}
function _ssc_sync_is_syncing(dbName, storeName) {
  return _ssc_sync_in_progress.has(_ssc_indexeddb_store_key(dbName, storeName));
}

async function _ssc_sync_json(method, url, body) {
  if (typeof fetch !== "function") throw new Error("Sync requires fetch in this JS runtime");
  const init = { method, headers: { "Content-Type": "application/json" } };
  if (body !== undefined) init.body = JSON.stringify(body);
  const res = await fetch(url, init);
  if (!res || res.ok === false) {
    const status = res && res.status !== undefined ? res.status : "unknown";
    throw new Error("Sync request failed: " + status + " " + url);
  }
  if (typeof res.json === "function") return await res.json();
  if (typeof res.text === "function") {
    const text = await res.text();
    return text ? JSON.parse(text) : {};
  }
  return {};
}

const Sync = {
  async put(storeName, typeName = "", value, dbName = "ssc", keyField = "id") {
    const store = IndexedDb.store(storeName, typeName, dbName, keyField);
    const plain = _ssc_indexeddb_encode(value, typeName || "");
    const key = _ssc_indexeddb_key(value, plain, keyField || "id");
    await store.put(value, key);
    const expected = _ssc_sync_expected_version(dbName, storeName, key);
    const mutation = { key, deleted: false, value: plain };
    if (expected !== undefined) mutation.expectedVersion = expected;
    _ssc_sync_enqueue(dbName, storeName, mutation);
    return value;
  },
  async remove(storeName, typeName = "", objectKey, dbName = "ssc", keyField = "id") {
    const key = String(objectKey);
    const store = IndexedDb.store(storeName, typeName, dbName, keyField);
    await store.remove(key);
    const expected = _ssc_sync_expected_version(dbName, storeName, key);
    const mutation = { key, deleted: true };
    if (expected !== undefined) mutation.expectedVersion = expected;
    _ssc_sync_enqueue(dbName, storeName, mutation);
    return undefined;
  },
  pending(storeName, dbName = "ssc") {
    return _ssc_sync_get_queue(dbName, storeName).slice();
  },
  conflicts(storeName, dbName = "ssc") {
    return _ssc_sync_get_conflicts(dbName, storeName).slice();
  },
  async resolve(storeName, typeName = "", objectKey, policy = "server", dbName = "ssc", keyField = "id") {
    const key = String(objectKey);
    const mode = String(policy || "server");
    const conflict = _ssc_sync_find_conflict(dbName, storeName, key);
    const store = IndexedDb.store(storeName, typeName, dbName, keyField);
    if (mode === "server") {
      if (conflict && conflict.deleted) {
        await store.remove(key);
        _ssc_sync_forget_version(dbName, storeName, key);
      } else if (conflict && Object.prototype.hasOwnProperty.call(conflict, "value") && conflict.value !== null && conflict.value !== undefined) {
        await store.put(_ssc_indexeddb_decode(conflict.value, typeName || ""), key);
        if (conflict.actualVersion !== undefined && conflict.actualVersion !== null) _ssc_sync_set_version(dbName, storeName, key, conflict.actualVersion);
      }
      _ssc_sync_drop_queue_key(dbName, storeName, key);
      _ssc_sync_clear_conflict(dbName, storeName, key);
    } else if (mode === "client") {
      _ssc_sync_clear_conflict(dbName, storeName, key);
    } else if (mode === "drop") {
      _ssc_sync_drop_queue_key(dbName, storeName, key);
      _ssc_sync_clear_conflict(dbName, storeName, key);
    } else {
      throw new Error("Sync.resolve: expected policy 'server', 'client', or 'drop', got " + mode);
    }
    return undefined;
  },
  async pull(storeName, typeName = "", dbName = "ssc", keyField = "id", serverUrl = "", limit = 100) {
    _ssc_sync_begin(dbName, storeName);
    try {
      const store = IndexedDb.store(storeName, typeName, dbName, keyField);
      const since = _ssc_sync_get_cursor(dbName, storeName);
      const url = _ssc_sync_base_url(serverUrl) + "/__ssc/sync/" + encodeURIComponent(String(storeName)) +
        "/changes?since=" + encodeURIComponent(String(since)) + "&limit=" + encodeURIComponent(String(limit));
      const payload = await _ssc_sync_json("GET", url);
      const changes = Array.isArray(payload.changes) ? payload.changes : [];
      for (const change of changes) {
        if (!change || change.key === undefined) continue;
        if (change.deleted) {
          await store.remove(change.key);
          _ssc_sync_forget_version(dbName, storeName, change.key);
        } else if (Object.prototype.hasOwnProperty.call(change, "value")) {
          await store.put(change.value, change.key);
          _ssc_sync_set_version(dbName, storeName, change.key, change.version);
        }
      }
      const nextCursor = payload.nextCursor === undefined ? changes.reduce((acc, change) => Math.max(acc, Number(change.version || 0)), since) : Number(payload.nextCursor);
      if (Number.isFinite(nextCursor)) _ssc_sync_set_cursor(dbName, storeName, nextCursor);
      _ssc_sync_set_last_pulled(dbName, storeName);
      return payload;
    } finally { _ssc_sync_finish(dbName, storeName); }
  },
  async push(storeName, typeName = "", dbName = "ssc", keyField = "id", serverUrl = "") {
    _ssc_sync_begin(dbName, storeName);
    try {
      const store = IndexedDb.store(storeName, typeName, dbName, keyField);
      let mutations = _ssc_sync_get_queue(dbName, storeName);
      if (mutations.length === 0) {
        const entries = await store.entries();
        mutations = entries.map(entry => {
          const mutation = {
            key: String(entry.key),
            deleted: false,
            value: _ssc_indexeddb_encode(entry.value, typeName || "")
          };
          const expected = _ssc_sync_expected_version(dbName, storeName, mutation.key);
          if (expected !== undefined) mutation.expectedVersion = expected;
          return mutation;
        });
      }
      const url = _ssc_sync_base_url(serverUrl) + "/__ssc/sync/" + encodeURIComponent(String(storeName)) + "/push";
      const payload = await _ssc_sync_json("POST", url, { mutations });
      const versions = [];
      if (Array.isArray(payload.results)) for (const row of payload.results) versions.push(Number(row.version || 0));
      if (versions.length > 0) _ssc_sync_set_cursor(dbName, storeName, Math.max(_ssc_sync_get_cursor(dbName, storeName), ...versions));
      _ssc_sync_ack(dbName, storeName, payload.results, payload.conflicts);
      _ssc_sync_set_last_pushed(dbName, storeName);
      return payload;
    } finally { _ssc_sync_finish(dbName, storeName); }
  },
  async sync(storeName, typeName = "", dbName = "ssc", keyField = "id", serverUrl = "") {
    const pushResult = await Sync.push(storeName, typeName, dbName, keyField, serverUrl);
    const pullResult = await Sync.pull(storeName, typeName, dbName, keyField, serverUrl);
    return { pushed: pushResult, pulled: pullResult };
  },
  status(storeName, dbName = "ssc") {
    return {
      pending: _ssc_sync_get_queue(dbName, storeName).length,
      conflicts: _ssc_sync_get_conflicts(dbName, storeName).length,
      lastPulled: _ssc_sync_get_last_pulled(dbName, storeName),
      lastPushed: _ssc_sync_get_last_pushed(dbName, storeName),
      isSyncing: _ssc_sync_is_syncing(dbName, storeName)
    };
  },
  get isOnline() {
    if (typeof navigator !== "undefined" && typeof navigator.onLine === "boolean") return navigator.onLine;
    return true;
  },
  isSyncing(storeName, dbName = "ssc") {
    return _ssc_sync_is_syncing(dbName, storeName);
  }
};
