package scalascript.codegen

/** v1.4 built-in effects: Logger, Random, Clock, Env.
 *  Concatenated after `JsRuntimeAsync` wherever effects are available. */
val JsRuntimeV14Effects: String = """

// ── v1.4 Logger effect ──────────────────────────────────────────────────────
//
// Logger.{info,warn,error,debug}  → _perform("Logger", op, [msg])
// runLogger(bodyFn)               — "[LEVEL] msg" to process.stdout
// runLoggerJson(bodyFn)           — {"level":"…","msg":"…"} newline-JSON
// runLoggerToList(bodyFn)         — [result, [[level, msg], …]]

const Logger = {
  info:  (msg) => _perform('Logger', 'info',  [msg]),
  warn:  (msg) => _perform('Logger', 'warn',  [msg]),
  error: (msg) => _perform('Logger', 'error', [msg]),
  debug: (msg) => _perform('Logger', 'debug', [msg]),
};

function _loggerJsonStr(s) {
  return JSON.stringify(String(s));
}

function _loggerMakeHandlers(fmt) {
  function makeHandler(level) {
    return function(args) {
      const msg = args[0];
      if (fmt === 'json') {
        process.stdout.write('{"level":"' + level + '","msg":' + _loggerJsonStr(msg) + '}\n');
      } else {
        process.stdout.write('[' + level.toUpperCase() + '] ' + msg + '\n');
      }
      return args[args.length - 1](undefined);
    };
  }
  return {
    'Logger.info':  makeHandler('info'),
    'Logger.warn':  makeHandler('warn'),
    'Logger.error': makeHandler('error'),
    'Logger.debug': makeHandler('debug'),
  };
}

function runLogger(bodyFn) {
  const handled = new Set(['Logger.info', 'Logger.warn', 'Logger.error', 'Logger.debug']);
  return _handle(bodyFn, handled, _loggerMakeHandlers('text'));
}

function runLoggerJson(bodyFn) {
  const handled = new Set(['Logger.info', 'Logger.warn', 'Logger.error', 'Logger.debug']);
  return _handle(bodyFn, handled, _loggerMakeHandlers('json'));
}

function runLoggerToList(bodyFn) {
  const log = [];
  const handlers = {};
  for (const level of ['info', 'warn', 'error', 'debug']) {
    handlers['Logger.' + level] = function(args) {
      log.push([level, String(args[0])]);
      return args[args.length - 1](undefined);
    };
  }
  const handled = new Set(Object.keys(handlers));
  const result = _handle(bodyFn, handled, handlers);
  return [result, log];
}

// ── v1.4 Random effect ──────────────────────────────────────────────────────
//
// Random.{nextInt,nextDouble,uuid,pick}  → _perform("Random", op, args)
// runRandom(bodyFn)           — Math.random()-based (non-deterministic)
// runRandomSeeded(seed)(body) — seeded LCG for deterministic output

const Random = {
  nextInt:    (n)  => _perform('Random', 'nextInt',    [n]),
  nextDouble: ()   => _perform('Random', 'nextDouble', []),
  uuid:       ()   => _perform('Random', 'uuid',       []),
  pick:       (xs) => _perform('Random', 'pick',       [xs]),
};

function _randomHandlers(rng) {
  return {
    'Random.nextInt': function(args) {
      const n = args[0] >>> 0;
      return args[args.length - 1](n > 0 ? (rng() * n) | 0 : 0);
    },
    'Random.nextDouble': function(args) {
      return args[args.length - 1](rng());
    },
    'Random.uuid': function(args) {
      const b = new Array(16);
      for (let i = 0; i < 16; i++) b[i] = (rng() * 256) | 0;
      b[6] = (b[6] & 0x0f) | 0x40;
      b[8] = (b[8] & 0x3f) | 0x80;
      const hex = (x) => x.toString(16).padStart(2, '0');
      const u = b.map(hex).join('');
      const uuid = u.slice(0,8)+'-'+u.slice(8,12)+'-'+u.slice(12,16)+'-'+u.slice(16,20)+'-'+u.slice(20);
      return args[args.length - 1](uuid);
    },
    'Random.pick': function(args) {
      const xs = args[0];
      return args[args.length - 1](xs[(rng() * xs.length) | 0]);
    },
  };
}

function _lcgRng(seed) {
  let s = seed >>> 0;
  return function() {
    s = (Math.imul(1664525, s) + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

function runRandom(bodyFn) {
  const ops = new Set(['Random.nextInt', 'Random.nextDouble', 'Random.uuid', 'Random.pick']);
  return _handle(bodyFn, ops, _randomHandlers(Math.random));
}

function runRandomSeeded(seed) {
  return function(bodyFn) {
    const ops = new Set(['Random.nextInt', 'Random.nextDouble', 'Random.uuid', 'Random.pick']);
    return _handle(bodyFn, ops, _randomHandlers(_lcgRng(seed)));
  };
}

// ── v1.4 Clock effect ───────────────────────────────────────────────────────
//
// Clock.{now,nowIso,sleep}  → _perform("Clock", op, args)
// runClock(bodyFn)          — real wall clock; sleep → Atomics.wait spin
// runClockAt(t0)(bodyFn)    — frozen at t0 ms since epoch; sleep is no-op

const Clock = {
  now:    ()   => _perform('Clock', 'now',    []),
  nowIso: ()   => _perform('Clock', 'nowIso', []),
  sleep:  (ms) => _perform('Clock', 'sleep',  [ms]),
};

function _clockHandlers(frozen) {
  function nowMs()  { return frozen !== null ? frozen : Date.now(); }
  function nowIso() { return new Date(nowMs()).toISOString(); }
  return {
    'Clock.now':    function(args) { return args[args.length - 1](nowMs()); },
    'Clock.nowIso': function(args) { return args[args.length - 1](nowIso()); },
    'Clock.sleep':  function(args) {
      const ms = args[0];
      if (frozen === null && ms > 0) { _asyncSleep(ms); }
      return args[args.length - 1](undefined);
    },
  };
}

function runClock(bodyFn) {
  const ops = new Set(['Clock.now', 'Clock.nowIso', 'Clock.sleep']);
  return _handle(bodyFn, ops, _clockHandlers(null));
}

function runClockAt(t0) {
  return function(bodyFn) {
    const ops = new Set(['Clock.now', 'Clock.nowIso', 'Clock.sleep']);
    return _handle(bodyFn, ops, _clockHandlers(t0));
  };
}

// ── v1.4 Env effect ─────────────────────────────────────────────────────────
//
// Env.{get,set,required}  → _perform("Env", op, args)
// runEnv(bodyFn)          — reads process.env; Env.set mutates local overlay
// runEnvWith(map)(bodyFn) — fixture map; Env.set mutates overlay

const Env = {
  get:      (key)        => _perform('Env', 'get',      [key]),
  set:      (key, value) => _perform('Env', 'set',      [key, value]),
  required: (key)        => _perform('Env', 'required', [key]),
};

function _envHandlers(overlay, useReal) {
  function lookup(k) {
    if (k in overlay) return overlay[k];
    if (useReal && typeof process !== 'undefined' && process.env) {
      const v = process.env[k];
      return v !== undefined && v !== '' ? v : undefined;
    }
    return undefined;
  }
  return {
    'Env.get': function(args) {
      const v = lookup(String(args[0]));
      return args[args.length - 1](v !== undefined ? v : null);
    },
    'Env.set': function(args) {
      overlay[String(args[0])] = String(args[1]);
      return args[args.length - 1](undefined);
    },
    'Env.required': function(args) {
      const k = String(args[0]);
      const v = lookup(k);
      if (v === undefined) throw new Error("Env.required: key '" + k + "' not found in environment");
      return args[args.length - 1](v);
    },
  };
}

function runEnv(bodyFn) {
  const ops = new Set(['Env.get', 'Env.set', 'Env.required']);
  return _handle(bodyFn, ops, _envHandlers({}, true));
}

function runEnvWith(initMap) {
  return function(bodyFn) {
    const ops = new Set(['Env.get', 'Env.set', 'Env.required']);
    const overlay = {};
    if (initMap instanceof Map) {
      for (const [k, v] of initMap) overlay[k] = v;
    } else if (initMap && typeof initMap === 'object') {
      Object.assign(overlay, initMap);
    }
    return _handle(bodyFn, ops, _envHandlers(overlay, false));
  };
}

// ── v1.4 Http effect ────────────────────────────────────────────────────────
//
// Http.{get,post,request}  → _perform("Http", op, args)
// runHttp(bodyFn)                — delegates to real _httpSyncFetchWithRetry
// runHttpStub(routes)(bodyFn)    — test stub: returns {status:200,…} for known urls

const Http = {
  get:     (url)                         => _perform('Http', 'get',     [url]),
  post:    (url, body)                   => _perform('Http', 'post',    [url, body]),
  request: (method, url, headers, body)  => _perform('Http', 'request', [method, url, headers, body]),
};

function _httpEffectHandlers(routes) {
  function stubResponse(url) {
    if (routes instanceof Map && routes.has(url)) {
      return { status: 200, headers: new Map(), body: String(routes.get(url)) };
    }
    return { status: 404, headers: new Map(), body: '' };
  }
  return {
    'Http.get': function(args) {
      const url = args[0];
      const resp = routes ? stubResponse(url) : _httpSyncFetchWithRetry('GET', url, null, {});
      return args[args.length - 1](resp);
    },
    'Http.post': function(args) {
      const url = args[0]; const body = args[1];
      const resp = routes ? stubResponse(url) : _httpSyncFetchWithRetry('POST', url, body, {});
      return args[args.length - 1](resp);
    },
    'Http.request': function(args) {
      const method = args[0]; const url = args[1];
      const headers = args[2] instanceof Map ? Object.fromEntries(args[2].entries()) : (args[2] || {});
      const body = args[3] != null ? String(args[3]) : null;
      const resp = routes ? stubResponse(url) : _httpSyncFetchWithRetry(method, url, body, headers);
      return args[args.length - 1](resp);
    },
  };
}

function runHttp(bodyFn) {
  const ops = new Set(['Http.get', 'Http.post', 'Http.request']);
  return _handle(bodyFn, ops, _httpEffectHandlers(null));
}

function runHttpStub(routes) {
  return function(bodyFn) {
    const ops = new Set(['Http.get', 'Http.post', 'Http.request']);
    return _handle(bodyFn, ops, _httpEffectHandlers(routes));
  };
}

// ── v1.4 Retry effect ───────────────────────────────────────────────────────
//
// Retry.attempt(n, delayMs)(thunk)  → _perform("Retry", "attempt", [n, delayMs, thunk])
// runRetry(bodyFn)         — real sleep between attempts (_asyncSleep)
// runRetryNoSleep(bodyFn)  — no sleep (test mode)

const Retry = {
  attempt: (n, delayMs) => function(thunk) {
    return _perform('Retry', 'attempt', [n, delayMs, thunk]);
  },
};

function _retryHandlers(doSleep) {
  return {
    'Retry.attempt': function(args) {
      const n = args[0]; const delayMs = args[1]; const thunk = args[2];
      const resume = args[args.length - 1];
      let lastErr = null;
      for (let attempt = 0; attempt <= n; attempt++) {
        try {
          const result = thunk();
          return resume(result);
        } catch(e) {
          lastErr = e;
          if (attempt < n && doSleep && delayMs > 0) _asyncSleep(delayMs);
        }
      }
      throw lastErr;
    },
  };
}

function runRetry(bodyFn) {
  const ops = new Set(['Retry.attempt']);
  return _handle(bodyFn, ops, _retryHandlers(true));
}

function runRetryNoSleep(bodyFn) {
  const ops = new Set(['Retry.attempt']);
  return _handle(bodyFn, ops, _retryHandlers(false));
}

// ── v1.4 Cache effect ───────────────────────────────────────────────────────
//
// Cache.memoize(key, ttlSeconds)(thunk)  → _perform("Cache", "memoize", args)
// runCache(bodyFn)        — uses module-level _cacheStore
// runCacheBypass(bodyFn)  — always recomputes; skips cache

const _cacheStore = new Map();
let _cacheBypass = false;

const Cache = {
  memoize: (key, ttlSeconds) => function(thunk) {
    return _perform('Cache', 'memoize', [key, ttlSeconds, thunk]);
  },
};

function _cacheHandlers(bypass) {
  return {
    'Cache.memoize': function(args) {
      const key = String(args[0]); const ttlMs = Number(args[1]) * 1000;
      const thunk = args[2]; const resume = args[args.length - 1];
      if (bypass) return resume(thunk());
      const nowMs = Date.now();
      const entry = _cacheStore.get(key);
      if (entry && nowMs < entry[0]) return resume(entry[1]);
      const v = thunk();
      _cacheStore.set(key, [nowMs + ttlMs, v]);
      return resume(v);
    },
  };
}

function runCache(bodyFn) {
  const ops = new Set(['Cache.memoize']);
  return _handle(bodyFn, ops, _cacheHandlers(false));
}

function runCacheBypass(bodyFn) {
  const ops = new Set(['Cache.memoize']);
  return _handle(bodyFn, ops, _cacheHandlers(true));
}

// ── v1.4 State effect ───────────────────────────────────────────────────────
//
// State.get              → _perform("State", "get",    [])
// State.set(s)           → _perform("State", "set",    [s])
// State.modify(f)        → _perform("State", "modify", [f])
// runState(s0)(bodyFn)   — returns [finalState, result]

const State = {
  get:    ()  => _perform('State', 'get',    []),
  set:    (s) => _perform('State', 'set',    [s]),
  modify: (f) => _perform('State', 'modify', [f]),
};

function runState(s0) {
  return function(bodyFn) {
    let state = s0;
    const handlers = {
      'State.get': function(args) {
        return args[args.length - 1](state);
      },
      'State.set': function(args) {
        state = args[0];
        return args[args.length - 1](undefined);
      },
      'State.modify': function(args) {
        state = args[0](state);
        return args[args.length - 1](undefined);
      },
    };
    const ops = new Set(['State.get', 'State.set', 'State.modify']);
    const result = _handle(bodyFn, ops, handlers);
    return [state, result];
  };
}

// ── v1.4 Tx effect ──────────────────────────────────────────────────────────
//
// Tx.atomic(thunk)  — signals transactional scope; default is no-op
// runTx(bodyFn)     — default no-op handler (just runs body directly)

const Tx = {
  atomic: (thunk) => thunk(),
};

function runTx(bodyFn) {
  return bodyFn();
}

// ── v1.4 Auth effect ────────────────────────────────────────────────────────
//
// Auth.currentUser  — returns current user or null (Option-like)
// Auth.require      — returns current user or throws
// runAuthWith(user)(bodyFn)  — injects a fixed user

let _authUser = null;

const Auth = {
  currentUser: () => _authUser,
  require:     () => {
    if (_authUser === null) throw new Error('Auth.require: no authenticated user in context');
    return _authUser;
  },
};

function runAuthWith(user) {
  return function(bodyFn) {
    const prior = _authUser;
    _authUser = user;
    try { return bodyFn(); }
    finally { _authUser = prior; }
  };
}

// ── v1.51.6 Stream algebraic effect ────────────────────────────────────────
//
// Stream.emit(x)       — produce element (! Stream[A])
// Stream.complete()    — early termination
// Stream.error(msg)    — fail the stream
// Stream.request(n)    — advisory demand hint (no-op in v1.51.6)
// runStream(bodyFn)    — discharge Stream effect; returns [_SourceSync, R]
//   where _SourceSync.runToList() returns the emitted values synchronously.
//
// Implementation: Stream.emit pushes to a module-level side-channel buffer
// when inside a runStream body.  This makes while/var loops work without a
// CPS trampoline — the emit is a direct side effect, not a Free monad node.
// The CPS path (_bind chains) still works: _bind(undefined, k) → k(undefined).

let _streamBuf = null;

const Stream = {
  emit:     (x)   => { if (_streamBuf !== null) { _streamBuf.push(x); return undefined; } return _perform('Stream', 'emit', [x]); },
  complete: ()    => _perform('Stream', 'complete',  []),
  error:    (msg) => _perform('Stream', 'error',    [msg]),
  request:  (n)   => _perform('Stream', 'request',  [n]),
};

function _mkStreamSource(data) {
  return {
    _data: data,
    runToList() { return data; },
    toList()    { return data; },
    [Symbol.asyncIterator]: async function*() { for (const v of data) yield v; },
  };
}

function runStream(bodyFn) {
  const emitted = [];
  _streamBuf = emitted;
  let terminated = false;
  let errorMsg = null;
  let bodyResult;
  try {
    const handlers = {
      'Stream.emit': function(args) {
        // CPS path: emit was not intercepted by side-channel (body used _perform directly).
        if (!terminated && errorMsg === null) emitted.push(args[0]);
        return args[args.length - 1](undefined);
      },
      'Stream.complete': function(args) {
        terminated = true;
        return args[args.length - 1](undefined);
      },
      'Stream.error': function(args) {
        errorMsg = args[0] ?? 'Stream error';
        return args[args.length - 1](undefined);
      },
      'Stream.request': function(args) {
        return args[args.length - 1](undefined);
      },
    };
    bodyResult = _handle(bodyFn, new Set(['Stream.emit','Stream.complete','Stream.error','Stream.request']), handlers);
  } finally {
    _streamBuf = null;
  }
  if (errorMsg !== null) throw new Error(String(errorMsg));
  return [_mkStreamSource(emitted), bodyResult];
}
"""
