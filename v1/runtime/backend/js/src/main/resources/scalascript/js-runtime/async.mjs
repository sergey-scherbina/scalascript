

// ── Async — built-in effect on top of the Free Monad ───────────────────────
//
// `Async.delay(ms)`, `Async.async(thunk)`, `Async.await(fut)`, and
// `Async.parallel(thunks)` produce `_Perform("Async", op, args)` nodes,
// indistinguishable from user-declared effect ops.  `_runAsync(bodyFn)`
// is the default handler — single-threaded so output is deterministic
// and byte-identical to the interpreter and JVM backends.  On Node
// `delay(ms)` blocks via `Atomics.wait` on a SharedArrayBuffer flag (no
// child process, no async coloring of caller code).  In the browser
// (no SharedArrayBuffer without crossOriginIsolation) we fall back to
// `Date.now()` spin, which is fine for the small delays used in demos.
const Async = {
  delay:    (ms)     => _perform('Async', 'delay',    [ms]),
  async:    (thunk)  => _perform('Async', 'async',    [thunk]),
  await:    (fut)    => _perform('Async', 'await',    [fut]),
  parallel: (thunks) => _perform('Async', 'parallel', [thunks]),
  recvFrom: (ws)     => _perform('Async', 'recvFrom', [ws]),
};
function Future(value) { return { _type: 'Future', value }; }

function _asyncSleep(ms) {
  if (!(ms > 0)) return;
  if (typeof SharedArrayBuffer !== 'undefined' && typeof Atomics !== 'undefined') {
    try {
      const sab = new SharedArrayBuffer(4);
      Atomics.wait(new Int32Array(sab), 0, 0, ms);
      return;
    } catch (_) { /* not allowed in main thread of some envs — fall through */ }
  }
  // Last-resort spin (browser without isolation, etc.).  Tight but
  // accurate enough for the small ms values typical in demos.
  const end = Date.now() + ms;
  while (Date.now() < end) { /* spin */ }
}

function _asyncDispatch(op, args, resume) {
  switch (op) {
    case 'delay': {
      const ms = args[0];
      if (typeof ms !== 'number') throw new Error('Async.delay(ms: Int)');
      _asyncSleep(ms);
      return resume(undefined);
    }
    case 'async': {
      const thunk = args[0];
      if (typeof thunk !== 'function') throw new Error('Async.async(thunk)');
      const v = _runAsyncInner(thunk());
      return resume(Future(v));
    }
    case 'await': {
      const fut = args[0];
      if (!fut || fut._type !== 'Future') throw new Error('Async.await(future)');
      return resume(fut.value);
    }
    case 'parallel': {
      const thunks = args[0];
      if (!Array.isArray(thunks)) throw new Error('Async.parallel(thunks: List[() => A])');
      const out = [];
      for (const t of thunks) {
        if (typeof t !== 'function') throw new Error('Async.parallel(thunks: List[() => A])');
        out.push(_runAsyncInner(t()));
      }
      return resume(out);
    }
    default:
      throw new Error('Unknown Async operation: ' + op);
  }
}

// Drive a Computation to a plain value, dispatching `Async.*` ops along
// the way.  Non-Async Performs propagate outward — useful for nested
// handlers (`runAsync` inside `handle`, or vice versa).
function _runAsyncInner(initial) {
  let current = initial;
  while (true) {
    if (current instanceof _Perform) {
      if (current.eff === 'Async') {
        current = _asyncDispatch(current.op, current.args, (v) => v);
      } else {
        return current;  // propagate
      }
    } else if (current instanceof _FlatMap) {
      const sub = current.sub;
      if (sub instanceof _FlatMap) {
        const sub2 = sub.sub, g = sub.k, f = current.k;
        current = new _FlatMap(sub2, (x) => new _FlatMap(g(x), f));
      } else if (sub instanceof _Perform) {
        const f = current.k;
        if (sub.eff === 'Async') {
          current = _asyncDispatch(sub.op, sub.args, (v) => _runAsyncInner(f(v)));
        } else {
          return new _FlatMap(sub, (v) => _runAsyncInner(f(v)));
        }
      } else {
        current = current.k(sub);
      }
    } else {
      return current;  // plain value
    }
  }
}

function _runAsync(bodyFn) { return _runAsyncInner(bodyFn()); }

// ── runAsyncParallel: true I/O concurrency on Node via Promises ────────────
//
// Each `Async.async(thunk)` launches an independent Promise-driven run of
// `thunk`, returning a handle that `Async.await` can resolve.  `parallel`
// fans out all thunks concurrently with `Promise.all`.  `recvFrom(ws)`
// delegates to `ws._nextMessage()` — a Promise that resolves on the next
// incoming WebSocket frame, or `None` on close.  The outer `async function`
// keeps the Node.js event loop live while awaiting I/O.
async function _runAsyncParallelInner(node) {
  // Nested runAsyncParallel calls return a Promise (no await inside CPS lambdas);
  // await the Promise to unwrap the actual value before further processing.
  if (node && typeof node.then === 'function') node = await node;
  while (true) {
    // Right-associate nested _FlatMap nodes to avoid stack growth.
    while (node instanceof _FlatMap && node.sub instanceof _FlatMap) {
      const sub2 = node.sub.sub, g = node.sub.k, f = node.k;
      node = new _FlatMap(sub2, (x) => new _FlatMap(g(x), f));
    }
    if (node instanceof _FlatMap) {
      const result = await _runAsyncParallelInner(node.sub);
      node = node.k(result);
    } else if (node instanceof _Perform && node.eff === 'Async') {
      switch (node.op) {
        case 'delay': {
          const ms = node.args[0];
          if (ms > 0) await new Promise(r => setTimeout(r, ms));
          node = undefined;
          break;
        }
        case 'async': {
          const thunk = node.args[0];
          const p = _runAsyncParallelInner(thunk());
          return { _type: 'Future', _isParFut: true, _promise: p };
        }
        case 'await': {
          const fut = node.args[0];
          if (!fut || !fut._isParFut) throw new Error('Async.await: expected parallel Future');
          return await fut._promise;
        }
        case 'parallel': {
          const thunks = node.args[0];
          return await Promise.all(thunks.map(t => _runAsyncParallelInner(t())));
        }
        case 'recvFrom': {
          const ws = node.args[0];
          const nextMsg = typeof ws._nextMessage === 'function' ? ws._nextMessage
                        : (_isMap(ws) ? ws.get('_nextMessage') : null);
          if (typeof nextMsg !== 'function') throw new Error('Async.recvFrom: ws has no _nextMessage');
          node = await nextMsg();
          break;
        }
        default:
          throw new Error('Unknown Async operation in runAsyncParallel: ' + node.op);
      }
    } else {
      return node;  // plain value
    }
  }
}
async function _runAsyncParallel(bodyFn) {
  try { return await _runAsyncParallelInner(bodyFn()); }
  catch (e) {
    if (typeof process !== 'undefined') process.stderr.write('runAsyncParallel error: ' + (e && e.message || String(e)) + '\n');
    throw e;
  }
}

// ── v1.6 Actors — Phase 1 cooperative scheduler ────────────────────────
//
// Mirrors the interpreter's `actorInterp` / `handleActorOp`.  Each
// `_runActors(bodyFn)` invocation creates a fresh actor registry,
// spawns `bodyFn` as the root actor, and drives all spawned actors
// cooperatively until quiescence.  Mailboxes are plain JS arrays
// (v1.9.x: JS is single-threaded so LinkedBlockingQueue N/A; array
// IS the cooperative-mailbox equivalent for this backend); the
// scheduler is a simple round-robin ready queue.  `receive` with a
// non-empty matching head returns the case body's value; with an
// empty mailbox it suspends.  `receive(timeout = N)` arms a deadline;
// when the ready queue empties and no other progress is possible the
// scheduler sleeps until the earliest deadline, then resumes that
// actor with None.

function Pid(nodeId, localId) { return { _type: 'Pid', nodeId, localId }; }

const Actor = {
  spawn:      (thunk)      => _perform('Actor', 'spawn',      [thunk]),
  spawn_link: (thunk)      => _perform('Actor', 'spawnLink',  [thunk]),
  self:       ()           => _perform('Actor', 'self',       []),
  // Stop the current actor exactly like the interpreter global: resolve self,
  // then exit it with the normal reason. The explicit bind keeps this a Free
  // computation that the actor scheduler can sequence.
  stop:       ()           => _bind(_perform('Actor', 'self', []),
                                    (pid) => _perform('Actor', 'exit', [pid, 'normal'])),
  exit:      (pid, reason) => _perform('Actor', 'exit',      [pid, reason]),
  send:      (pid, msg)    => _perform('Actor', 'send',      [pid, msg]),
  receive_:  (specId)              => _perform('Actor', 'receive',   [specId]),
  receive_t: (specId, timeoutMs)   => _perform('Actor', 'receive_t', [specId, timeoutMs]),
  // v1.6 Phase 2 — supervision
  link:      (pid)         => _perform('Actor', 'link',      [pid]),
  monitor:   (pid)         => _perform('Actor', 'monitor',   [pid]),
  demonitor: (ref)         => _perform('Actor', 'demonitor', [ref]),
  trapExit:  (b)           => _perform('Actor', 'trapExit',  [b]),
  // v1.6 Phase 3 — distributed nodes
  startNode:   (nodeId, url)  => _perform('Actor', 'startNode',   [nodeId, url || '']),
  connectNode: (url, token)   => _perform('Actor', 'connectNode', [url, token]),
  register:    (name, pid)    => _perform('Actor', 'register',    [name, pid]),
  whereis:     (name)         => _perform('Actor', 'whereis',     [name]),
  // v1.6.x — cluster discovery
  joinCluster:     (seeds, token) => _perform('Actor', 'joinCluster',     [seeds, token || '']),
  // v1.6.x — cluster-wide registry
  globalRegister:  (name, pid)   => _perform('Actor', 'globalRegister',  [name, pid]),
  globalWhereis:   (name)        => _perform('Actor', 'globalWhereis',   [name]),
  // v1.6.x — scheduled sends
  sendAfter:   (delayMs, pid, msg)  => _perform('Actor', 'sendAfter',   [delayMs, pid, msg]),
  sendInterval:(periodMs, pid, msg) => _perform('Actor', 'sendInterval',[periodMs, pid, msg]),
  cancelTimer: (ref)                => _perform('Actor', 'cancelTimer', [ref]),
  // v1.6.x — bounded mailbox spawn
  spawnBounded:(cap, overflow, thunk) => _perform('Actor', 'spawnBounded', [cap, overflow, thunk]),
  // v1.6.x — process introspection
  processInfo: (pid) => _perform('Actor', 'processInfo', [pid]),
  // v1.23 — cluster visibility
  clusterMembers:         ()  => _perform('Actor', 'clusterMembers',         []),
  subscribeClusterEvents: ()  => _perform('Actor', 'subscribeClusterEvents', []),
  // v1.23 — phi-accrual failure detector
  phiOf:     (nid)              => _perform('Actor', 'phiOf',     [nid]),
  isSuspect: (nid, thr)         => _perform('Actor', 'isSuspect', [nid, thr == null ? 8.0 : thr]),
  // v1.23 — local node identity + phi vector
  selfNode:      ()             => _perform('Actor', 'selfNode',      []),
  clusterHealth: ()             => _perform('Actor', 'clusterHealth', []),
  // v1.23 — cluster-wide failure detector
  broadcastHealth: ()           => _perform('Actor', 'broadcastHealth', []),
  clusterIsDown:   (nid, thr)   => _perform('Actor', 'clusterIsDown',
                                            [nid, thr == null ? 8.0 : thr]),
  // v1.23 — leader election (Bully)
  electLeader:           ()  => _perform('Actor', 'electLeader',           []),
  currentLeader:         ()  => _perform('Actor', 'currentLeader',         []),
  subscribeLeaderEvents: ()  => _perform('Actor', 'subscribeLeaderEvents', []),
  setAutoReelect:        (b) => _perform('Actor', 'setAutoReelect', [b]),
  // v1.23 — protocol switch + history (cluster-raft.md §6)
  useRaftLeaderElection: ()  => _perform('Actor', 'useRaftLeaderElection', []),
  useExternalCoordinator:(acq, ren, rel, hol) =>
                              _perform('Actor', 'useExternalCoordinator', [acq, ren, rel, hol]),
  leaderProtocol:        ()  => _perform('Actor', 'leaderProtocol', []),
  leaderHistory:         ()  => _perform('Actor', 'leaderHistory', []),
  // v1.23 — auto-reconnect policy (2- or 3-arg form)
  setReconnectPolicy: function() {
    const args = Array.prototype.slice.call(arguments);
    return _perform('Actor', 'setReconnectPolicy', args);
  },
  // v1.23 — per-link heartbeat tuning
  setHeartbeatTimeout: (iv, dead) => _perform('Actor', 'setHeartbeatTimeout', [iv, dead]),
  // v1.23 — quorum-aware Bully threshold (split-brain guard)
  setQuorumSize: (n) => _perform('Actor', 'setQuorumSize', [n]),
  // v1.23 — shared-secret Bearer token for /_ssc-cluster/* endpoints.
  setClusterAuthToken: (t) => _perform('Actor', 'setClusterAuthToken', [t]),
  // v1.23 — periodic gossip re-discovery
  requestGossip: () => _perform('Actor', 'requestGossip', []),
  // v1.23 — cluster configuration distribution
  clusterConfigSet:      (key, value) => _perform('Actor', 'clusterConfigSet',      [key, value]),
  clusterConfigGet:      (key)        => _perform('Actor', 'clusterConfigGet',      [key]),
  clusterConfigKeys:     ()           => _perform('Actor', 'clusterConfigKeys',     []),
  subscribeConfigEvents: ()           => _perform('Actor', 'subscribeConfigEvents', []),
  // v1.23 — drain / rolling-restart
  setDraining:           (b)          => _perform('Actor', 'setDraining',           [b]),
  isDraining:            ()           => _perform('Actor', 'isDraining',            []),
  drainingPeers:         ()           => _perform('Actor', 'drainingPeers',         []),
  subscribeDrainEvents:  ()           => _perform('Actor', 'subscribeDrainEvents',  []),
  // v1.23 — cluster metrics aggregation
  clusterMetricSet:      (name, value) => _perform('Actor', 'clusterMetricSet',     [name, value]),
  clusterMetricGet:      (name)        => _perform('Actor', 'clusterMetricGet',     [name]),
  clusterMetricSum:      (name)        => _perform('Actor', 'clusterMetricSum',     [name]),
  clusterMetricNames:    ()            => _perform('Actor', 'clusterMetricNames',   []),
  subscribeMetricEvents: ()            => _perform('Actor', 'subscribeMetricEvents',[]),
};

// `receive { case … }` lowers to a registered matcher function whose
// integer token is passed to Actor.receive_/receive_t.  Codegen emits
// a fresh closure per `receive` site that returns either { matched:
// false } or { matched: true, body: () => <case-body Computation> }.
let _receiveSpecNext = 0;
const _receiveSpecs = new Map();
function _registerReceive(matcher) {
  const id = _receiveSpecNext++;
  _receiveSpecs.set(id, matcher);
  return id;
}

async function _runActors(bodyFn) {
  // id -> { mailbox: [], pending: _Computation | null,
  //         blocked: { matcher, k, deadline, wrapSome } | null }
  // (async) The scheduler is an async function so it can `await
  // setImmediate`/`setTimeout` between ticks — that's what lets Node's
  // event loop dispatch the HTTP/WS requests libuv `accept`ed on the
  // server bound by a `serveAsync(...)` call from inside the actor body.
  // Previously the scheduler was a synchronous `while (true)` loop with
  // `Atomics.wait(...)` waits — fast but it monopolised the main thread,
  // so any actor blocking on a long-armed `receive` left every HTTP
  // route permanently unreachable until the next mailbox delivery.
  const actors     = new Map();
  // Phase 2 supervision state (per _runActors invocation)
  const links      = new Map();   // id -> Set<id>
  const monitors   = new Map();   // watchedId -> Map<monRef, observerId>
  const trapExitMap = new Map();  // id -> bool
  let   nextMonRef = 0;
  // v1.6.x scheduled sends — timerId -> { fireAt, periodMs, targetId, msg }
  const _timers    = new Map();
  let   _nextTimerId = 0;
  // Phase 3 distributed node state
  let   _localNodeId  = "";
  const _nodeRegistry   = new Map();  // name -> localId
  const _globalRegistry = new Map();  // cluster-wide name -> Pid
  // Node-down tracking — drained by the scheduler loop.
  const _nodeDownQueue   = [];
  const _remoteLinks     = new Map();  // nodeId -> [[localActorId, remotePid], ...]
  const _remoteMonitors  = new Map();  // nodeId -> [[monRef, localActorId, remoteLocalId], ...]
  // Phase 3: outbound peer channels (nodeId -> { worker, send })
  const _peerChannels = new Map();
  // Inbound messages from peer workers (raw JSON strings), drained each tick.
  const _remoteRawInbox = [];
  // Ring buffer constants (shared with workers via workerData).
  const _RING_SLOTS = 64, _RING_SLOT_BYTES = 2048, _RING_HDR = 8;
  // v1.6.x cluster discovery state
  let _selfUrl  = '';          // set by startNode(nodeId, url)
  let _joinMode = false;       // true once joinCluster has been called
  let _joinToken = '';         // token for auto-connect to gossip'd peers
  const _peerUrls = new Map(); // nodeId -> url (populated from connectNode + peers_resp)
  // v1.23 — cluster visibility
  const _clusterEventSubs  = new Set();   // actor ids subscribed to NodeJoined/NodeLeft
  const _clusterEventQueue = [];          // {tag, nodeId, reason} pending delivery
  // v1.23 — bounded ring buffer of every cluster event as JSON lines.
  // Independent of the in-process subscription system: events land here
  // whether or not any actor has called subscribe*Events, so the
  // `GET /_ssc-cluster/events` endpoint always has data for ops tooling.
  // Mirrors `Interpreter.clusterEventLog` (cap 200, oldest-first drop).
  const _CLUSTER_EVENT_LOG_MAX = 200;
  const _clusterEventLog       = []; // JSON-string entries
  function _recordEventLog(jsonObj) {
    _clusterEventLog.push(jsonObj);
    while (_clusterEventLog.length > _CLUSTER_EVENT_LOG_MAX) _clusterEventLog.shift();
  }
  function _fireClusterEvent(tag, nodeId, reason) {
    // Mirror into the ops event log regardless of in-process subscribers.
    const ts = Date.now();
    const logEntry = (tag === 'NodeJoined')
      ? '{"ts":' + ts + ',"type":"NodeJoined","nodeId":' + JSON.stringify(nodeId) + '}'
      : '{"ts":' + ts + ',"type":"NodeLeft","nodeId":' + JSON.stringify(nodeId) +
        ',"reason":' + JSON.stringify(reason || '') + '}';
    _recordEventLog(logEntry);
    if (_clusterEventSubs.size === 0) return;
    _clusterEventQueue.push({ tag, nodeId, reason: reason || '' });
  }
  // v1.23 — shared-secret Bearer token for /_ssc-cluster/* endpoints.
  // Default reads SSC_CLUSTER_TOKEN env at runtime; runtime override via
  // the setClusterAuthToken intrinsic.  Empty string ⇒ endpoints are open
  // (backwards compatible).  Mirrors `Interpreter.clusterAuthToken`.
  let _clusterAuthToken = (typeof process !== 'undefined' && process.env && process.env.SSC_CLUSTER_TOKEN) || '';
  function _clusterAuthReject(req) {
    if (!_clusterAuthToken) return null;
    let hdr = '';
    try {
      if (req && req.headers && typeof req.headers.get === 'function') {
        hdr = req.headers.get('authorization') || req.headers.get('Authorization') || '';
      }
    } catch (_) {}
    const expected = 'Bearer ' + _clusterAuthToken;
    if (hdr === expected) return null;
    return {
      _type: 'Response', status: 401,
      headers: new Map([['Content-Type', 'application/json']]),
      body: '{"error":"unauthorized","hint":"set Authorization: Bearer <token>"}'
    };
  }
  // v1.23 — phi-accrual failure detector
  const _PHI_HIST_MAX  = 100;
  const _peerPongHist  = new Map();   // nodeId -> [intervalMs, ...] up to PHI_HIST_MAX
  const _peerLastPong  = new Map();   // nodeId -> epoch ms of last pong (mirrors INT/JVM)
  // v1.23 — cluster-wide FD: peerNodeId -> Map<targetNodeId, phi>
  const _peerPhiViews  = new Map();
  // v1.23 — leader election (Bully) state.
  let   _currentLeader      = "";
  let   _electionInProgress = false;
  let   _electionStartedAt  = 0;
  let   _gotAliveResponse   = false;
  let   _autoReelect         = false;
  const _ELECTION_TIMEOUT_MS = 2000;
  // v1.23 — protocol dispatch (cluster-raft.md §6).  "bully" today;
  //   Phase 3a flips to "raft", Phase 3b flips to "coord".
  let   _leaderProtocol      = "bully";
  let   _leaderCoordinator   = null;
  // v1.23 — coordinator lease state (cluster-raft.md §5).
  let   _coordAcquireFn      = null;
  let   _coordRenewFn        = null;
  let   _coordReleaseFn      = null;
  let   _coordHolderFn       = null;
  let   _coordIsLeader       = false;
  let   _coordTickHandle     = null;
  const _COORD_LEASE_TIMEOUT_MS  = 5000;
  const _COORD_RENEW_INTERVAL_MS = 1000;
  function _ensureCoordTickThread() {
    if (_coordTickHandle != null) return;
    _coordTickHandle = setInterval(() => {
      if (_leaderProtocol !== 'coord') { clearInterval(_coordTickHandle); _coordTickHandle = null; return; }
      try {
        if (!_coordIsLeader) {
          if (_coordAcquireFn) {
            let got = false;
            try { got = !!_coordAcquireFn(_localNodeId, _COORD_LEASE_TIMEOUT_MS); } catch (_) {}
            if (got) {
              _coordIsLeader = true;
              const prev = _currentLeader;
              _currentLeader = _localNodeId;
              if (prev !== _localNodeId) {
                _fireLeaderEvent("LeaderElected", _localNodeId);
                _recordLeaderHist(_localNodeId);
              }
            }
          }
        } else {
          if (_coordRenewFn) {
            let ok = false;
            try { ok = !!_coordRenewFn(_localNodeId); } catch (_) {}
            if (!ok) {
              _coordIsLeader = false;
              const prev = _currentLeader;
              _currentLeader = "";
              if (prev) _fireLeaderEvent("LeaderLost", prev);
            }
          }
        }
      } catch (_) {}
    }, _COORD_RENEW_INTERVAL_MS);
    if (_coordTickHandle && typeof _coordTickHandle.unref === 'function') _coordTickHandle.unref();
  }
  // v1.23 — drain-aware step-down (cluster-raft.md §7).
  function _stepDownIfLeader() {
    if (_leaderProtocol === 'raft') {
      if (_raftStateName === 'leader') {
        _raftStateName = 'follower';
        _raftLeaderId  = '';
        const prev = _currentLeader; _currentLeader = '';
        if (prev) _fireLeaderEvent("LeaderLost", prev);
      }
    } else if (_leaderProtocol === 'coord') {
      if (_coordIsLeader) {
        _coordIsLeader = false;
        if (_coordReleaseFn) {
          try { _coordReleaseFn(_localNodeId); } catch (_) {}
        }
        const prev = _currentLeader; _currentLeader = '';
        if (prev) _fireLeaderEvent("LeaderLost", prev);
      }
    } else {
      if (_currentLeader === _localNodeId) {
        _currentLeader = '';
        _fireLeaderEvent("LeaderLost", _localNodeId);
      }
    }
  }
  // v1.23 — bounded leader-claim history.
  const _LEADER_HIST_MAX     = 100;
  let   _leaderHistTermSeq   = 0;
  const _leaderHist          = []; // [[term, leaderId, ms], ...]
  function _recordLeaderHist(leaderId) {
    _leaderHistTermSeq += 1;
    _leaderHist.push([_leaderHistTermSeq, leaderId, Date.now()]);
    while (_leaderHist.length > _LEADER_HIST_MAX) _leaderHist.shift();
  }
  // v1.23 — Raft state (cluster-raft.md §4.1).
  let _raftCurrentTerm = 0;
  let _raftVotedFor    = "";
  let _raftStateName   = "follower"; // follower | candidate | leader
  let _raftLeaderId    = "";
  let _raftElectionDue = 0;
  let _raftVotes       = 0;
  const _RAFT_ELECTION_LO  = 150;
  const _RAFT_ELECTION_HI  = 300;
  const _RAFT_HEARTBEAT_MS = 50;
  let _raftTickHandle = null;
  function _raftRandTimeout() {
    return _RAFT_ELECTION_LO + Math.floor(Math.random() * (_RAFT_ELECTION_HI - _RAFT_ELECTION_LO + 1));
  }
  function _raftBroadcastHeartbeat() {
    const payload = JSON.stringify({ t: 'raft_append', from: _localNodeId, term: _raftCurrentTerm });
    for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
  }
  function _raftAdoptLeader(newLeader) {
    const prev = _currentLeader;
    _currentLeader = newLeader;
    // Record every accepted claim (mirror ActorScheduler.raftAdoptLeader):
    // single-node mode has both prev and newLeader empty, so the prev-changed
    // guard would drop the initial entry. _fireLeaderEvent stays guarded.
    _recordLeaderHist(newLeader);
    if (prev !== newLeader) {
      _fireLeaderEvent("LeaderElected", newLeader);
    }
  }
  function _startRaftElection() {
    _raftStateName    = "candidate";
    _raftCurrentTerm  = _raftCurrentTerm + 1;
    _raftVotedFor     = _localNodeId;
    _raftVotes        = 1;
    _raftElectionDue  = Date.now() + _raftRandTimeout();
    _raftPersist();
    const peerIds = [];
    for (const nid of _peerChannels.keys()) peerIds.push(nid);
    const total = peerIds.length + 1;
    if (_raftVotes > Math.floor(total / 2)) {
      _raftStateName = "leader";
      _raftLeaderId  = _localNodeId;
      _raftAdoptLeader(_localNodeId);
      _raftBroadcastHeartbeat();
    } else {
      const payload = JSON.stringify({
        t: 'raft_vote_req', from: _localNodeId, term: _raftCurrentTerm, lastLogTerm: 0
      });
      for (const nid of peerIds) {
        const ch = _peerChannels.get(nid);
        if (ch) { try { ch.send(payload); } catch (_) {} }
      }
    }
  }
  // v1.23 — Raft persistence (cluster-raft.md §4.1).  Node has `fs`;
  // browser does not run distributed Raft anyway (no inbound WS).
  let _raftFs = null;
  try { _raftFs = require('fs'); } catch (_) { _raftFs = null; }
  function _raftStatePath() {
    const key = _localNodeId ? _localNodeId.replace(/[^A-Za-z0-9._-]/g, '_') : 'default';
    return '.ssc-raft-state-' + key + '.json';
  }
  function _raftPersist() {
    if (!_raftFs) return;
    try {
      const voted = _raftVotedFor.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
      const json  = '{"currentTerm":' + _raftCurrentTerm + ',"votedFor":"' + voted + '"}';
      _raftFs.writeFileSync(_raftStatePath(), json, 'utf8');
    } catch (_) {}
  }
  function _raftLoad() {
    if (!_raftFs) return;
    try {
      const p = _raftStatePath();
      if (!_raftFs.existsSync(p)) return;
      const s = _raftFs.readFileSync(p, 'utf8');
      const o = JSON.parse(s);
      if (typeof o.currentTerm === 'number') _raftCurrentTerm = o.currentTerm;
      if (typeof o.votedFor    === 'string') _raftVotedFor    = o.votedFor;
    } catch (_) {}
  }
  function _ensureRaftTickThread() {
    if (_raftTickHandle != null) return;
    _raftTickHandle = setInterval(() => {
      if (_leaderProtocol !== 'raft') { clearInterval(_raftTickHandle); _raftTickHandle = null; return; }
      const now = Date.now();
      if (_raftStateName === 'leader') {
        _raftBroadcastHeartbeat();
      } else if (now >= _raftElectionDue) {
        _startRaftElection();
      }
    }, _RAFT_HEARTBEAT_MS);
    // Don't keep the Node event loop alive on the timer — apps end naturally.
    if (_raftTickHandle && typeof _raftTickHandle.unref === 'function') _raftTickHandle.unref();
  }
  const _leaderEventSubs    = new Set();
  const _leaderEventQueue   = [];
  function _fireLeaderEvent(tag, leaderId) {
    // Mirror into ops event log regardless of subscribers (parity with INT).
    const ts = Date.now();
    _recordEventLog('{"ts":' + ts + ',"type":' + JSON.stringify(tag) +
      ',"nodeId":' + JSON.stringify(leaderId) + '}');
    if (_leaderEventSubs.size === 0) return;
    _leaderEventQueue.push({ tag, leaderId });
  }
  // v1.23 — auto-reconnect: exponential-backoff retry per peer URL after a
  // disconnect.  Both 0 ⇒ disabled (default).  giveUp caps wall-clock
  // retry budget per URL (0 = retry forever).
  let _reconnectInitialMs = 0;
  let _reconnectMaxMs     = 0;
  let _reconnectGiveUpMs  = 0;
  // v1.23 — per-link heartbeat cadence + dead-after.  Defaults match
  // pre-v1.23 hardcoded 30s/40s; `setHeartbeatTimeout` tunes them.
  let _peerHeartbeatIntervalMs  = 30000;
  let _peerHeartbeatDeadAfterMs = 40000;
  // v1.23 — quorum-aware Bully threshold.  0 = no quorum check.
  let _quorumSize = 0;
  function _hasQuorum() {
    return _quorumSize <= 0 || (_peerChannels.size + 1) >= _quorumSize;
  }
  // v1.23 — cluster configuration distribution.  LWW per key by (ts, origin).
  const _clusterConfig    = new Map();   // key -> { value, ts, origin }
  const _configEventSubs  = new Set();
  const _configEventQueue = [];
  function _fireConfigEvent(key, value) {
    if (_configEventSubs.size === 0) return;
    _configEventQueue.push({ key, value });
  }
  function _applyConfigUpdate(key, value, ts, origin) {
    const prev = _clusterConfig.get(key);
    const accept =
      !prev || ts > prev.ts || (ts === prev.ts && origin > prev.origin);
    if (accept) {
      _clusterConfig.set(key, { value, ts, origin });
      _fireConfigEvent(key, value);
    }
    return accept;
  }
  // Snapshot every locally-known config entry to a single peer.  Called on
  // every successful handshake so late-joining nodes pick up entries set
  // before they joined (LWW on the receiver protects existing values).
  function _sendConfigSnapshot(sendFn) {
    for (const [key, entry] of _clusterConfig) {
      const payload = JSON.stringify({
        t: 'config_set', key, value: entry.value, ts: entry.ts, origin: entry.origin
      });
      try { sendFn(payload); } catch (_) {}
    }
  }
  // v1.23 — drain / rolling-restart state
  let _isDrainingSelf = false;
  const _drainingPeers   = new Map(); // nodeId -> bool
  const _drainEventSubs  = new Set();
  const _drainEventQueue = [];
  function _fireDrainEvent(nodeId, draining) {
    const ts = Date.now();
    _recordEventLog('{"ts":' + ts + ',"type":"DrainStateChanged","nodeId":' +
      JSON.stringify(nodeId) + ',"draining":' + (draining ? 'true' : 'false') + '}');
    if (_drainEventSubs.size === 0) return;
    _drainEventQueue.push({ nodeId, draining });
  }
  function _sendDrainState(sendFn) {
    if (!_isDrainingSelf) return;
    const payload = JSON.stringify({ t: 'drain', from: _localNodeId, draining: true });
    try { sendFn(payload); } catch (_) {}
  }
  // v1.23 — cluster metrics aggregation: per-node gauges.
  const _clusterMetrics    = new Map(); // name -> Map<nodeId, value>
  const _metricEventSubs   = new Set();
  const _metricEventQueue  = [];
  function _fireMetricEvent(name, nodeId, value) {
    const ts = Date.now();
    _recordEventLog('{"ts":' + ts + ',"type":"MetricChanged","name":' +
      JSON.stringify(name) + ',"nodeId":' + JSON.stringify(nodeId) +
      ',"value":' + value + '}');
    if (_metricEventSubs.size === 0) return;
    _metricEventQueue.push({ name, nodeId, value });
  }
  function _applyMetricUpdate(name, nodeId, value) {
    let inner = _clusterMetrics.get(name);
    if (!inner) { inner = new Map(); _clusterMetrics.set(name, inner); }
    const prev = inner.get(nodeId);
    inner.set(nodeId, value);
    if (prev !== value) _fireMetricEvent(name, nodeId, value);
  }
  function _sendMetricSnapshot(sendFn) {
    for (const [name, inner] of _clusterMetrics) {
      const v = inner.get(_localNodeId);
      if (v != null) {
        const payload = JSON.stringify({ t: 'metric', from: _localNodeId, name, value: v });
        try { sendFn(payload); } catch (_) {}
      }
    }
  }
  // ── v1.23 — operational /_ssc-cluster/* HTTP routes ────────────────
  // Mirrors `Interpreter.registerCluster*Route` (status / drain / events /
  // step-down / metrics-prom).  Installed by `startNode` so codegen-built
  // Node bundles expose the same ops surface as interpreter-run nodes —
  // see specs/cluster-codegen-gap.md (Tier 4 gating gap #3).  Idempotent
  // via a module-level flag; double `startNode` calls are no-ops.
  let _clusterRoutesInstalled = false;
  function _hasRoute(method, path) {
    return _routes.some(r => r.method === method && r.path === path);
  }
  function _registerClusterStatusRoute() {
    const path = '/_ssc-cluster/status';
    if (_hasRoute('GET', path)) return;
    const handler = (req) => {
      const rej = _clusterAuthReject(req);
      if (rej) return rej;
      const members = [];
      for (const nid of _peerChannels.keys()) members.push(nid);
      const drainPeers = [];
      for (const [nid, dr] of _drainingPeers) if (dr) drainPeers.push(nid);
      const leaderNow = (_leaderProtocol === 'raft') ? _raftLeaderId : _currentLeader;
      const body = '{' +
        '"nodeId":'        + JSON.stringify(_localNodeId) +
        ',"leader":'       + JSON.stringify(leaderNow) +
        ',"protocol":'     + JSON.stringify(_leaderProtocol) +
        ',"members":'      + JSON.stringify(members) +
        ',"drainingSelf":' + (_isDrainingSelf ? 'true' : 'false') +
        ',"drainingPeers":'+ JSON.stringify(drainPeers) +
        ',"raftTerm":'     + String(_raftCurrentTerm) +
        ',"raftState":'    + JSON.stringify(_raftStateName) +
        '}';
      return {
        _type: 'Response', status: 200,
        headers: new Map([['Content-Type', 'application/json']]),
        body
      };
    };
    _routes.push({ method: 'GET', path, pattern: _parsePath(path), handler });
  }
  function _registerClusterDrainRoute() {
    const path = '/_ssc-cluster/drain';
    if (_hasRoute('POST', path)) return;
    const handler = (req) => {
      const rej = _clusterAuthReject(req);
      if (rej) return rej;
      const body = (req && typeof req.body === 'string') ? req.body : '';
      let enabled;
      if (body.trim().length === 0) {
        enabled = true;
      } else {
        const needle = '"enabled":';
        const i = body.indexOf(needle);
        if (i < 0) enabled = true;
        else {
          const rest = body.substring(i + needle.length).trim();
          enabled = !rest.startsWith('false');
        }
      }
      const prev = _isDrainingSelf;
      _isDrainingSelf = enabled;
      if (prev !== enabled) {
        const payload = '{"t":"drain","from":' + JSON.stringify(_localNodeId) +
          ',"draining":' + (enabled ? 'true' : 'false') + '}';
        for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
        _fireDrainEvent(_localNodeId, enabled);
        if (enabled) _stepDownIfLeader();
      }
      return {
        _type: 'Response', status: 200,
        headers: new Map([['Content-Type', 'application/json']]),
        body: '{"drainingSelf":' + (enabled ? 'true' : 'false') + '}'
      };
    };
    _routes.push({ method: 'POST', path, pattern: _parsePath(path), handler });
  }
  function _registerClusterEventsRoute() {
    const path = '/_ssc-cluster/events';
    if (_hasRoute('GET', path)) return;
    const handler = (req) => {
      const rej = _clusterAuthReject(req);
      if (rej) return rej;
      let sinceMs = 0;
      try {
        if (req && req.query && typeof req.query.get === 'function') {
          const s = req.query.get('since');
          if (s) { const n = Number(s); if (Number.isFinite(n)) sinceMs = n; }
        }
      } catch (_) {}
      const parts = [];
      const tsPrefix = '{"ts":';
      for (const line of _clusterEventLog) {
        let pass = true;
        if (sinceMs > 0) {
          pass = false;
          if (line.startsWith(tsPrefix)) {
            const end = line.indexOf(',', tsPrefix.length);
            if (end > 0) {
              const v = Number(line.substring(tsPrefix.length, end));
              if (Number.isFinite(v) && v > sinceMs) pass = true;
            }
          }
        }
        if (pass) parts.push(line);
      }
      return {
        _type: 'Response', status: 200,
        headers: new Map([['Content-Type', 'application/json']]),
        body: '[' + parts.join(',') + ']'
      };
    };
    _routes.push({ method: 'GET', path, pattern: _parsePath(path), handler });
  }
  function _registerClusterStepDownRoute() {
    const path = '/_ssc-cluster/step-down';
    if (_hasRoute('POST', path)) return;
    const handler = (req) => {
      const rej = _clusterAuthReject(req);
      if (rej) return rej;
      let wasLeader = false;
      if (_leaderProtocol === 'raft')       wasLeader = (_raftStateName === 'leader');
      else if (_leaderProtocol === 'coord') wasLeader = !!_coordIsLeader;
      else                                  wasLeader = (_currentLeader === _localNodeId);
      if (!wasLeader) {
        return {
          _type: 'Response', status: 409,
          headers: new Map([['Content-Type', 'application/json']]),
          body: '{"error":"not_leader","leader":' + JSON.stringify(_currentLeader) + '}'
        };
      }
      _stepDownIfLeader();
      return {
        _type: 'Response', status: 200,
        headers: new Map([['Content-Type', 'application/json']]),
        body: '{"steppedDown":true,"nodeId":' + JSON.stringify(_localNodeId) + '}'
      };
    };
    _routes.push({ method: 'POST', path, pattern: _parsePath(path), handler });
  }
  function _registerClusterMetricsPromRoute() {
    const path = '/_ssc-cluster/metrics-prom';
    if (_hasRoute('GET', path)) return;
    const sanitize = (s) => {
      let out = '';
      for (let i = 0; i < s.length; i++) {
        const c = s.charCodeAt(i);
        const ok =
          (c >= 97 && c <= 122) /* a-z */ ||
          (c >= 65 && c <= 90)  /* A-Z */ ||
          (c >= 48 && c <= 57)  /* 0-9 */ ||
          c === 95 /* _ */ || c === 58 /* : */;
        out += ok ? s.charAt(i) : '_';
      }
      if (out.length > 0 && out.charCodeAt(0) >= 48 && out.charCodeAt(0) <= 57) out = '_' + out;
      return out;
    };
    const escLabel = (s) => s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
    const handler = (req) => {
      const rej = _clusterAuthReject(req);
      if (rej) return rej;
      let out = '';
      for (const [name, inner] of _clusterMetrics) {
        const pName = sanitize(name);
        out += '# TYPE ' + pName + ' gauge\n';
        for (const [nodeId, value] of inner) {
          out += pName + '{nodeId="' + escLabel(nodeId) + '"} ' + Number(value) + '\n';
        }
      }
      return {
        _type: 'Response', status: 200,
        headers: new Map([['Content-Type', 'text/plain; version=0.0.4; charset=utf-8']]),
        body: out
      };
    };
    _routes.push({ method: 'GET', path, pattern: _parsePath(path), handler });
  }
  function _installClusterRoutes() {
    if (_clusterRoutesInstalled) return;
    // The cluster status/drain/events/metrics endpoints live in the HTTP-server
    // runtime (`_routes`/`_parsePath`, http-server.mjs, HtmlDsl capability). An
    // actors-only bundle (startNode without `serve`) omits that module, so these
    // routes can never be served — installing them would throw on the undefined
    // `_routes`. No-op when the server runtime is absent. (js-cluster-routes-no-server.)
    if (typeof _routes === 'undefined') { _clusterRoutesInstalled = true; return; }
    _clusterRoutesInstalled = true;
    _registerClusterStatusRoute();
    _registerClusterDrainRoute();
    _registerClusterEventsRoute();
    _registerClusterStepDownRoute();
    _registerClusterMetricsPromRoute();
  }
  // v1.23 — URL-keyed dedupe so concurrent peer-loss + dial-failure
  // events for the same URL don't each spin up an independent
  // exponential-backoff loop.
  const _reconnectActive = new Set();
  function _scheduleReconnect(rurl, rtok) {
    if (_reconnectActive.has(rurl)) return;
    _reconnectActive.add(rurl);
    const startedAt = Date.now();
    let delay = Math.max(_reconnectInitialMs, 1);
    const attempt = () => {
      if (_reconnectInitialMs <= 0) { _reconnectActive.delete(rurl); return; }
      // Already reconnected?  `_peerUrls` is populated on successful handshake.
      for (const u of _peerUrls.values()) if (u === rurl) { _reconnectActive.delete(rurl); return; }
      // v1.23 — give-up budget: stop after the configured wall-clock.
      if (_reconnectGiveUpMs > 0 && (Date.now() - startedAt) >= _reconnectGiveUpMs) {
        _reconnectActive.delete(rurl); return;
      }
      try { _connectNodeAsync(rurl, rtok); } catch (_) {}
      const cap = _reconnectMaxMs > 0 ? _reconnectMaxMs : delay;
      delay = Math.min(delay * 2, Math.max(cap, delay));
      setTimeout(attempt, delay);
    };
    setTimeout(attempt, delay);
  }
  function _broadcastCoordinator() {
    const payload = '{"t":"coordinator","from":' + JSON.stringify(_localNodeId) + '}';
    for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
  }
  function _startElection() {
    if (!_localNodeId) {
      const prev = _currentLeader; _currentLeader = _localNodeId;
      // Record every accepted claim (mirror ActorScheduler.startElection):
      // single-node mode has empty _localNodeId, so the prev-changed guard
      // would drop the initial entry. Notification stays guarded.
      _recordLeaderHist(_localNodeId);
      if (prev !== _localNodeId) { _fireLeaderEvent("LeaderElected", _localNodeId); }
      return;
    }
    const higher = [];
    for (const nid of _peerChannels.keys()) if (nid > _localNodeId) higher.push(nid);
    if (higher.length === 0) {
      // v1.23 — quorum gate: refuse self-claim when below quorum
      // (split-brain guard).  No-op when `_quorumSize = 0`.
      if (_hasQuorum()) {
        const prev = _currentLeader; _currentLeader = _localNodeId;
        _broadcastCoordinator();
        // Record on every accepted claim (symmetric with the empty-id branch
        // above + the Raft / coordinator paths); _fireLeaderEvent stays guarded.
        _recordLeaderHist(_localNodeId);
        if (prev !== _localNodeId) { _fireLeaderEvent("LeaderElected", _localNodeId); }
      }
    } else {
      _electionInProgress = true;
      _electionStartedAt  = Date.now();
      _gotAliveResponse   = false;
      const payload = '{"t":"election","from":' + JSON.stringify(_localNodeId) + '}';
      for (const nid of higher) {
        const ch = _peerChannels.get(nid);
        if (ch) { try { ch.send(payload); } catch (_) {} }
      }
    }
  }
  function _recordPongInterval(nid) {
    const now = Date.now();
    const last = _peerLastPong.get(nid);
    if (last != null && last > 0) {
      let hist = _peerPongHist.get(nid);
      if (!hist) { hist = []; _peerPongHist.set(nid, hist); }
      hist.push(now - last);
      while (hist.length > _PHI_HIST_MAX) hist.shift();
    }
    _peerLastPong.set(nid, now);
  }
  function _computePhi(nid) {
    const hist = _peerPongHist.get(nid);
    if (!hist || hist.length === 0) return Number.POSITIVE_INFINITY;
    const n = hist.length;
    let s = 0; for (let i = 0; i < n; i++) s += hist[i];
    const mean = s / n;
    let sq = 0; for (let i = 0; i < n; i++) { const d = hist[i] - mean; sq += d * d; }
    const variance = n > 1 ? sq / (n - 1) : 1.0;
    const stddev   = Math.max(Math.sqrt(variance), 50.0);
    const last     = _peerLastPong.get(nid);
    const now      = Date.now();
    const elapsed  = last == null ? Number.POSITIVE_INFINITY : now - last;
    if (elapsed <= mean) return 0.0;
    // Right-tail Normal approximation (Akka / Cassandra style):
    //   phi = -log10( exp(-z² / 2) / (z * sqrt(2π)) )  with  z = (elapsed - μ) / σ
    const z    = (elapsed - mean) / stddev;
    const tail = Math.exp(-z * z / 2) / (z * Math.sqrt(2 * Math.PI));
    if (tail <= 0) return Number.POSITIVE_INFINITY;
    return -Math.log10(Math.min(tail, 1.0));
  }

  // Drain all peer ring buffers into _remoteRawInbox.
  function _drainPeerBuffers() {
    for (const [, peer] of _peerChannels) {
      // Inbound (server-side) peers route messages through the WS
      // `onMessage` callback which pushes straight into
      // `_remoteRawInbox`; they have no SharedArrayBuffer ring (`sab:
      // null`).  Skipping them here avoids `new Int32Array(null, ...)`
      // which throws "Invalid atomic access index" under Node ≥ 16.
      if (!peer.sab) continue;
      const hdr = new Int32Array(peer.sab, 0, 2);
      for (;;) {
        const wc = Atomics.load(hdr, 0);
        const rc = Atomics.load(hdr, 1);
        if (wc === rc) break;
        const slotOff = (rc % _RING_SLOTS) * _RING_SLOT_BYTES;
        const len  = new DataView(peer.sab).getInt32(_RING_HDR + slotOff, true);
        if (len > 0) {
          const bytes = new Uint8Array(peer.sab, _RING_HDR + slotOff + 4, len);
          _remoteRawInbox.push(Buffer.from(bytes).toString('utf8'));
        }
        Atomics.store(hdr, 1, rc + 1);
      }
    }
  }

  // Deserialise a $t-tagged JSON object into a ScalaScript value.
  function _deserActorVal(o) {
    if (o == null) return undefined;
    switch (o.$t) {
      case 'i': case 'd': return o.v;
      case 's': return o.v;
      case 'b': return o.v;
      case 'u': return undefined;
      case 'pid': return Pid(o.n, o.id);
      case 'l': return o.v.map(_deserActorVal);
      case 'o': {
        const obj = { _type: o.cls };
        for (const [k, v] of Object.entries(o.f)) obj[k] = _deserActorVal(v);
        return obj;
      }
      default: return String(o.v != null ? o.v : '');
    }
  }

  // Serialise a ScalaScript value to a $t-tagged JSON string.
  function _serActorVal(v) {
    if (v === null || v === undefined) return '{"$t":"u"}';
    if (typeof v === 'number' && Number.isInteger(v)) return '{"$t":"i","v":' + v + '}';
    if (typeof v === 'number') return '{"$t":"d","v":' + v + '}';
    if (typeof v === 'string')  return '{"$t":"s","v":' + JSON.stringify(v) + '}';
    if (typeof v === 'boolean') return '{"$t":"b","v":' + v + '}';
    if (v && v._type === 'Pid') return '{"$t":"pid","n":' + JSON.stringify(v.nodeId) + ',"id":' + v.localId + '}';
    if (Array.isArray(v)) return '{"$t":"l","v":[' + v.map(_serActorVal).join(',') + ']}';
    if (v && v._type) {
      const flds = Object.entries(v).filter(([k]) => k !== '_type').map(([k, vv]) => JSON.stringify(k) + ':' + _serActorVal(vv)).join(',');
      return '{"$t":"o","cls":' + JSON.stringify(v._type) + ',"f":{' + flds + '}}';
    }
    return '{"$t":"s","v":' + JSON.stringify(String(v)) + '}';
  }

  // Establish a new outbound peer connection (shared by connectNode op and joinCluster gossip).
  function _connectNodeAsync(peerUrl, token) {
    if (_peerChannels.has('__pending__' + peerUrl)) return; // already connecting
    const sab = new SharedArrayBuffer(_RING_HDR + _RING_SLOTS * _RING_SLOT_BYTES);
    const ownNodeId = _localNodeId;
    const ringSlots = _RING_SLOTS, ringSlotBytes = _RING_SLOT_BYTES, ringHdr = _RING_HDR;
    const workerSrc = [
      "const { workerData, parentPort } = require('worker_threads');",
      "const { url, token, sab, ownNodeId, RS, RSB, RH } = workerData;",
      "let WsClass; try { WsClass = require('ws'); } catch(_) { throw new Error('connectNode requires the ws npm package'); }",
      "const hdr  = new Int32Array(sab, 0, 2);",
      "function ringWrite(json) {",
      "  const wc = Atomics.load(hdr, 0), rc = Atomics.load(hdr, 1);",
      "  if (((wc - rc + RS) % RS) >= RS - 1) return;",
      "  const bytes = Buffer.from(json, 'utf8');",
      "  if (bytes.length + 4 > RSB) return;",
      "  const off = RH + (wc % RS) * RSB;",
      "  new DataView(sab).setInt32(off, bytes.length, true);",
      "  new Uint8Array(sab).set(bytes, off + 4);",
      "  Atomics.store(hdr, 0, wc + 1);",
      "}",
      "const hdrs = token ? { Authorization: 'Bearer ' + token } : {};",
      "const ws = new WsClass(url, ['ssc-actors-v1'], { headers: hdrs });",
      "let peerNodeId = '';",
      "ws.on('open', () => ws.send(JSON.stringify({ nodeId: ownNodeId })));",
      "ws.on('message', (data) => {",
      "  const msg = Buffer.isBuffer(data) ? data.toString('utf8') : String(data);",
      "  if (!peerNodeId) {",
      "    try { const h = JSON.parse(msg); if (h.nodeId) { peerNodeId = h.nodeId; ringWrite(JSON.stringify({ t: 'handshake', nodeId: peerNodeId })); } } catch(_) {}",
      "  } else {",
      "    // v1.23 — inject peer nodeId into 'pong' frames so the main thread can",
      "    // attribute the inter-arrival sample to the right peer (phi-accrual).",
      "    try { const p = JSON.parse(msg); if (p && p.t === 'pong') { ringWrite(JSON.stringify({ t: 'pong', from: peerNodeId })); return; } } catch(_) {}",
      "    ringWrite(msg);",
      "  }",
      "});",
      "ws.on('close', () => { if (peerNodeId) ringWrite(JSON.stringify({ t: 'down', nodeId: peerNodeId })); });",
      "ws.on('error', (e) => { console.error('ssc-actors connectNode error [' + url + ']:', e.message); if (peerNodeId) ringWrite(JSON.stringify({ t: 'down', nodeId: peerNodeId })); });",
      "parentPort.on('message', ({ json }) => { try { if (ws.readyState === 1) ws.send(json); } catch(_) {} });",
      "setInterval(() => { try { if (ws.readyState === 1) ws.send(JSON.stringify({ t: 'ping' })); } catch(_) {} }, 30000);"
    ].join('\n');
    const { Worker } = require('worker_threads');
    const worker = new Worker(workerSrc, { eval: true,
      workerData: { url: peerUrl, token, sab, ownNodeId, RS: ringSlots, RSB: ringSlotBytes, RH: ringHdr } });
    const sendFn = (json) => worker.postMessage({ json });
    _peerChannels.set('__pending__' + peerUrl, { sab, send: sendFn, worker, pending: true, url: peerUrl });
  }

  // Process _remoteRawInbox: deliver messages, handle handshakes, node-downs.
  function _processRemoteInbox() {
    while (_remoteRawInbox.length > 0) {
      const raw = _remoteRawInbox.shift();
      try {
        const env = JSON.parse(raw);
        if (env.t === 'handshake') {
          // Worker completed WS handshake — upgrade pending entry to real nodeId.
          const peerNodeId = env.nodeId;
          for (const [key, peer] of _peerChannels) {
            if (peer.pending) {
              _peerChannels.delete(key);
              _peerChannels.set(peerNodeId, { sab: peer.sab, send: peer.send, worker: peer.worker });
              _peerUrls.set(peerNodeId, peer.url || '');
              _fireClusterEvent('NodeJoined', peerNodeId);
              // If in joinCluster mode, request this peer's peer list.
              if (_joinMode) {
                peer.send(JSON.stringify({ t: 'peers_req', from: _localNodeId }));
              }
              // v1.23 — snapshot the cluster config + drain state to the new peer.
              _sendConfigSnapshot(peer.send);
              _sendDrainState(peer.send);
              _sendMetricSnapshot(peer.send);
              break;
            }
          }
        } else if (env.t === 'msg') {
          const toId = env.to && env.to.localId;
          if (toId != null) {
            const body = _deserActorVal(env.body);
            const st   = actors.get(toId);
            if (st) {
              st.mailbox.push(body);
              tryWakeBlocked(toId);
            }
          }
        } else if (env.t === 'ping') {
          const peer = _peerChannels.get(env.from);
          if (peer) peer.send(JSON.stringify({ t: 'pong' }));
        } else if (env.t === 'pong') {
          // v1.23 — phi-accrual: record inter-pong interval.  The worker
          // injects `from: peerNodeId` so we can attribute the sample.
          if (env.from) _recordPongInterval(env.from);
        } else if (env.t === 'down') {
          _nodeDownQueue.push(env.nodeId);
          const _lostUrl = _peerUrls.get(env.nodeId) || '';
          _peerChannels.delete(env.nodeId);
          _peerUrls.delete(env.nodeId);
          _peerPongHist.delete(env.nodeId);
          _peerLastPong.delete(env.nodeId);
          _peerPhiViews.delete(env.nodeId);
          _drainingPeers.delete(env.nodeId);
          for (const [, inner] of _clusterMetrics) inner.delete(env.nodeId);
          _fireClusterEvent('NodeLeft', env.nodeId, 'disconnect');
          if (_currentLeader === env.nodeId) {
            _currentLeader = "";
            _fireLeaderEvent("LeaderLost", env.nodeId);
            if (_autoReelect) _startElection();
          }
          if (_reconnectInitialMs > 0 && _lostUrl) _scheduleReconnect(_lostUrl, _joinToken || '');
        } else if (env.t === 'peers_req') {
          // Respond with our known peer URLs + self URL.
          const myPeers = [];
          if (_selfUrl) myPeers.push({ nodeId: _localNodeId, url: _selfUrl });
          for (const [nid] of _peerChannels) {
            const u = _peerUrls.get(nid);
            if (u) myPeers.push({ nodeId: nid, url: u });
          }
          const reqFrom = env.from;
          const reqChan = reqFrom ? _peerChannels.get(reqFrom) : null;
          if (reqChan) reqChan.send(JSON.stringify({ t: 'peers_resp', peers: myPeers }));
        } else if (env.t === 'peers_resp') {
          // Connect to any peers we don't yet know.
          const peers = env.peers || [];
          for (const { nodeId: pnid, url: purl } of peers) {
            if (pnid && purl && pnid !== _localNodeId && !_peerChannels.has(pnid)) {
              _connectNodeAsync(purl, _joinToken);
            }
          }
        } else if (env.t === 'global_reg') {
          if (env.name && env.nodeId != null) {
            _globalRegistry.set(env.name, Pid(env.nodeId, Number(env.localId)));
          }
        } else if (env.t === 'phi_vector') {
          // v1.23 — peer broadcasted its phi vector; record its view.
          if (env.from && Array.isArray(env.view)) {
            const m = new Map();
            for (const pair of env.view) {
              if (Array.isArray(pair) && pair.length === 2) {
                m.set(String(pair[0]), Number(pair[1]));
              }
            }
            _peerPhiViews.set(env.from, m);
          }
        } else if (env.t === 'election') {
          // v1.23 — Bully: lower-id peer is calling an election.
          if (env.from && env.from < _localNodeId) {
            const reply = '{"t":"alive","from":' + JSON.stringify(_localNodeId) + '}';
            const ch = _peerChannels.get(env.from);
            if (ch) { try { ch.send(reply); } catch (_) {} }
            if (!_electionInProgress) _startElection();
          }
        } else if (env.t === 'alive') {
          _gotAliveResponse = true;
        } else if (env.t === 'coordinator') {
          if (env.from) {
            const prev = _currentLeader;
            _currentLeader = env.from;
            _electionInProgress = false;
            if (prev !== env.from) { _fireLeaderEvent("LeaderElected", env.from); _recordLeaderHist(env.from); }
          }
        } else if (env.t === 'config_set') {
          const k = env.key != null ? String(env.key) : '';
          const v = env.value != null ? String(env.value) : '';
          const o = env.origin != null ? String(env.origin) : '';
          const t = env.ts != null ? Number(env.ts) : 0;
          if (k) _applyConfigUpdate(k, v, t, o);
        } else if (env.t === 'drain') {
          const from = env.from != null ? String(env.from) : '';
          if (from) {
            const dr = !!env.draining;
            const prev = _drainingPeers.get(from);
            _drainingPeers.set(from, dr);
            if (prev !== dr) _fireDrainEvent(from, dr);
          }
        } else if (env.t === 'metric') {
          const from  = env.from != null ? String(env.from) : '';
          const name  = env.name != null ? String(env.name) : '';
          const value = env.value != null ? Number(env.value) : 0;
          if (from && name) _applyMetricUpdate(name, from, value);
        } else if (env.t === 'raft_vote_req') {
          const from = env.from != null ? String(env.from) : '';
          const term = env.term != null ? Number(env.term) : 0;
          if (from) {
            let granted = false;
            let mutated = false;
            if (term < _raftCurrentTerm) granted = false;
            else {
              if (term > _raftCurrentTerm) {
                _raftCurrentTerm = term;
                _raftVotedFor    = "";
                _raftStateName   = "follower";
                mutated = true;
              }
              if (_raftVotedFor === "" || _raftVotedFor === from) {
                _raftVotedFor    = from;
                _raftElectionDue = Date.now() + _raftRandTimeout();
                mutated = true;
                granted = true;
              }
            }
            if (mutated) _raftPersist();
            const reply = JSON.stringify({
              t: 'raft_vote_resp', from: _localNodeId, term: _raftCurrentTerm, granted
            });
            const ch = _peerChannels.get(from);
            if (ch) { try { ch.send(reply); } catch (_) {} }
          }
        } else if (env.t === 'raft_vote_resp') {
          const term = env.term != null ? Number(env.term) : 0;
          const granted = !!env.granted;
          if (term === _raftCurrentTerm && _raftStateName === "candidate" && granted) {
            _raftVotes = _raftVotes + 1;
            const total = _peerChannels.size + 1;
            if (_raftVotes > Math.floor(total / 2)) {
              _raftStateName = "leader";
              _raftLeaderId  = _localNodeId;
              _raftAdoptLeader(_localNodeId);
              _raftBroadcastHeartbeat();
            }
          }
        } else if (env.t === 'raft_append') {
          const from = env.from != null ? String(env.from) : '';
          const term = env.term != null ? Number(env.term) : 0;
          if (from && term >= _raftCurrentTerm) {
            const termChanged = term > _raftCurrentTerm;
            _raftCurrentTerm = term;
            _raftStateName   = "follower";
            const prevLeader = _raftLeaderId;
            _raftLeaderId    = from;
            _raftElectionDue = Date.now() + _raftRandTimeout();
            if (termChanged) _raftPersist();
            if (prevLeader !== from) _raftAdoptLeader(from);
          }
        }
      } catch (_e) {}
    }
  }

  function _fireNodeDown(deadNodeId) {
    const deadPidOf = (localId) => Pid(deadNodeId, localId);
    const mons = _remoteMonitors.get(deadNodeId) || [];
    _remoteMonitors.delete(deadNodeId);
    for (const [monRef, observerId, remoteLocalId] of mons) {
      const st = actors.get(observerId);
      if (st) {
        st.mailbox.push({ _type: 'Down', ref: monRef, from: deadPidOf(remoteLocalId), reason: 'noconnection' });
        tryWakeBlocked(observerId);
      }
    }
    const lnks = _remoteLinks.get(deadNodeId) || [];
    _remoteLinks.delete(deadNodeId);
    for (const [localActorId, remotePid] of lnks) {
      const ls = links.get(localActorId);
      if (ls) ls.delete(remotePid.localId);
      if (trapExitMap.get(localActorId)) {
        const st = actors.get(localActorId);
        if (st) {
          st.mailbox.push({ _type: 'Exit', from: deadPidOf(remotePid.localId), reason: 'noconnection' });
          tryWakeBlocked(localActorId);
        }
      } else {
        killActor(localActorId, 'noconnection');
      }
    }
  }

  const ready  = [];
  let   nextId = 0;
  let   rootResult = undefined;

  function spawnActor(thunk, cap, overflow) {
    const id = nextId++;
    actors.set(id, { mailbox: [], pending: thunk(), blocked: null,
                     cap: cap || 0, overflow: overflow || '',
                     blockedSends: [] });
    ready.push(id);
    return Pid(_localNodeId, id);
  }
  const rootId = spawnActor(bodyFn);

  function _resumeBlockedSender(state) {
    if (!state.cap || !state.blockedSends || state.blockedSends.length === 0) return;
    if (state.mailbox.length >= state.cap) return;
    while (state.blockedSends.length > 0) {
      const { senderId, msg, k } = state.blockedSends.shift();
      const ss = actors.get(senderId);
      if (!ss) continue;  // dead sender — skip
      state.mailbox.push(msg);
      ss.pending = k(undefined);
      ready.push(senderId);
      return;
    }
  }

  function tryDeliver(state, matcher, wrapSome) {
    while (state.mailbox.length > 0) {
      const msg = state.mailbox[0];
      const r   = matcher(msg);
      if (r && r.matched) {
        state.mailbox.shift();
        _resumeBlockedSender(state);
        const bodyC = r.body();
        return wrapSome
          ? new _FlatMap(bodyC, (v) => _Some(v))
          : bodyC;
      }
      state.mailbox.shift();  // dead-letter
      _resumeBlockedSender(state);
    }
    return null;
  }

  function tryWakeBlocked(id) {
    const st = actors.get(id);
    if (!st || !st.blocked) return;
    const b = st.blocked;
    const delivered = tryDeliver(st, b.matcher, b.wrapSome);
    if (delivered !== null) {
      st.pending = new _FlatMap(delivered, b.k);
      st.blocked = null;
      ready.push(id);
    }
  }

  // Kill actor targetId with reason, propagate through links, fire monitors.
  // Idempotent: if actor not in `actors` map it's already dead.
  function killActor(targetId, reason) {
    if (!actors.has(targetId)) return;
    const _dying = actors.get(targetId);
    actors.delete(targetId);
    trapExitMap.delete(targetId);
    // Resume blocked senders: target died → send becomes silent no-op.
    if (_dying && _dying.blockedSends && _dying.blockedSends.length > 0) {
      for (const { senderId, k } of _dying.blockedSends) {
        const ss = actors.get(senderId);
        if (ss) { ss.pending = k(undefined); ready.push(senderId); }
      }
      _dying.blockedSends.length = 0;
    }

    const deadPid = Pid(_localNodeId, targetId);

    // Notify linked actors.
    const linkedSet = links.get(targetId);
    links.delete(targetId);
    if (linkedSet) {
      for (const linkedId of linkedSet) {
        const ls = links.get(linkedId);
        if (ls) ls.delete(targetId);
        if (trapExitMap.get(linkedId)) {
          const st = actors.get(linkedId);
          if (st) {
            st.mailbox.push({ _type: 'Exit', from: deadPid, reason });
            tryWakeBlocked(linkedId);
          }
        } else {
          killActor(linkedId, reason);
        }
      }
    }

    // Fire Down to all monitors watching targetId.
    const monMap = monitors.get(targetId);
    monitors.delete(targetId);
    if (monMap) {
      for (const [monRef, observerId] of monMap) {
        const st = actors.get(observerId);
        if (st) {
          st.mailbox.push({ _type: 'Down', ref: monRef, from: deadPid, reason });
          tryWakeBlocked(observerId);
        }
      }
    }
  }

  function handleActorOp(id, state, op, args, k) {
    switch (op) {
      case 'spawn': {
        const childPid = spawnActor(args[0]);
        return { suspend: false, next: k(childPid) };
      }
      case 'spawnLink': {
        const childPid = spawnActor(args[0]);
        // Atomic bidirectional link
        const childId = childPid.localId;
        if (!links.has(id))      links.set(id,      new Set());
        if (!links.has(childId)) links.set(childId, new Set());
        links.get(id).add(childId);
        links.get(childId).add(id);
        return { suspend: false, next: k(childPid) };
      }
      case 'spawnBounded': {
        const cap      = args[0];
        const overflow = args[1] && (args[1]._type || '');
        const childPid = spawnActor(args[2], cap, overflow);
        return { suspend: false, next: k(childPid) };
      }
      case 'self':
        return { suspend: false, next: k(Pid(_localNodeId, id)) };
      case 'processInfo': {
        const target = args[0];
        if (!target || target._type !== 'Pid') return { suspend: false, next: k(undefined) };
        const targetId = target.localId;
        const ts = actors.get(targetId);
        if (!ts) return { suspend: false, next: k(undefined) };  // dead → None
        const lnks = links.get(targetId);
        const linkList = lnks ? Array.from(lnks).map(lid => Pid(_localNodeId, lid)) : [];
        const status   = ts.blocked ? 'blocked' : 'running';
        const info = { _type: 'ProcessInfo', mailboxSize: ts.mailbox.length,
                       links: linkList, status };
        return { suspend: false, next: k({ _type: '_Some', value: info }) };
      }
      case 'send': {
        const target = args[0];
        if (target && target._type === 'Pid') {
          const targetNode = target.nodeId || '';
          if (targetNode && targetNode !== _localNodeId) {
            // Remote send — serialise and deliver via peer channel.
            const peer = _peerChannels.get(targetNode);
            if (peer) {
              const body = _serActorVal(args[1]);
              const env  = '{"t":"msg","to":{"nodeId":' + JSON.stringify(targetNode) +
                           ',"localId":' + target.localId + '},"from":{"nodeId":' +
                           JSON.stringify(_localNodeId) + ',"localId":' + id + '},"body":' + body + '}';
              peer.send(env);
            }
          } else {
            const ts = actors.get(target.localId);
            if (ts) {
              // Bounded mailbox: apply overflow strategy when at capacity.
              if (ts.cap > 0 && ts.mailbox.length >= ts.cap) {
                switch (ts.overflow) {
                  case 'DropOldest':
                    ts.mailbox.shift();
                    ts.mailbox.push(args[1]);
                    break;
                  case 'DropNewest':
                    return { suspend: false, next: k(undefined) };
                  case 'Fail':
                    killActor(id, { _type: 'mailbox_overflow' });
                    if (!actors.has(id)) return { suspend: true };
                    return { suspend: false, next: k(undefined) };
                  case 'Block':
                    ts.blockedSends.push({ senderId: id, msg: args[1], k });
                    return { suspend: true };
                  default:
                    ts.mailbox.push(args[1]);
                }
              } else {
                ts.mailbox.push(args[1]);
              }
              if (ts.blocked) {
                const b = ts.blocked;
                const delivered = tryDeliver(ts, b.matcher, b.wrapSome);
                if (delivered !== null) {
                  ts.pending = new _FlatMap(delivered, b.k);
                  ts.blocked = null;
                  ready.push(target.localId);
                }
              }
            }
          }
        }
        return { suspend: false, next: k(undefined) };
      }
      case 'exit': {
        const target = args[0];
        if (target && target._type === 'Pid') {
          killActor(target.localId, args[1]);
        }
        // The caller may have been killed by a link; check.
        if (!actors.has(id)) return { suspend: true };
        return { suspend: false, next: k(undefined) };
      }
      case 'receive': {
        const matcher = _receiveSpecs.get(args[0]);
        const c = tryDeliver(state, matcher, false);
        if (c !== null) return { suspend: false, next: new _FlatMap(c, k) };
        state.blocked = { matcher, k, wrapSome: false, deadline: null };
        return { suspend: true };
      }
      case 'receive_t': {
        const matcher = _receiveSpecs.get(args[0]);
        const c = tryDeliver(state, matcher, true);
        if (c !== null) return { suspend: false, next: new _FlatMap(c, k) };
        // Source Long values are JS BigInt. Host clocks/timers use Number, so
        // cross that boundary explicitly instead of mixing Date.now() + BigInt.
        const timeoutMs = Number(args[1]);
        state.blocked = { matcher, k, wrapSome: true, deadline: Date.now() + timeoutMs };
        return { suspend: true };
      }

      // ── v1.6 Phase 2 — supervision ────────────────────────────────────
      case 'link': {
        const target = args[0];
        if (target && target._type === 'Pid') {
          const targetNodeId = target.nodeId;
          const targetId     = target.localId;
          if (targetNodeId && targetNodeId !== _localNodeId) {
            // Remote pid — register for node-down notification.
            if (!_remoteLinks.has(targetNodeId)) _remoteLinks.set(targetNodeId, []);
            _remoteLinks.get(targetNodeId).push([id, target]);
          } else if (actors.has(targetId)) {
            if (!links.has(id))       links.set(id,       new Set());
            if (!links.has(targetId)) links.set(targetId, new Set());
            links.get(id).add(targetId);
            links.get(targetId).add(id);
          } else {
            // Target already dead — noproc exit signal.
            const noproc = { _type: 'noproc' };
            if (trapExitMap.get(id)) {
              const st = actors.get(id);
              if (st) st.mailbox.push({ _type: 'Exit', from: target, reason: noproc });
            } else {
              killActor(id, noproc);
            }
          }
        }
        if (!actors.has(id)) return { suspend: true };
        return { suspend: false, next: k(undefined) };
      }
      case 'monitor': {
        const target = args[0];
        if (target && target._type === 'Pid') {
          const targetNodeId = target.nodeId;
          const targetId     = target.localId;
          const monRef = nextMonRef++;
          if (targetNodeId && targetNodeId !== _localNodeId) {
            // Remote pid — register for node-down notification.
            if (!_remoteMonitors.has(targetNodeId)) _remoteMonitors.set(targetNodeId, []);
            _remoteMonitors.get(targetNodeId).push([monRef, id, targetId]);
            return { suspend: false, next: k(monRef) };
          } else if (actors.has(targetId)) {
            if (!monitors.has(targetId)) monitors.set(targetId, new Map());
            monitors.get(targetId).set(monRef, id);
          } else {
            // Already dead — immediate Down(noproc).
            const st = actors.get(id);
            if (st) {
              st.mailbox.push({ _type: 'Down', ref: monRef, from: target, reason: { _type: 'noproc' } });
              tryWakeBlocked(id);
            }
          }
          return { suspend: false, next: k(monRef) };
        }
        return { suspend: false, next: k(-1) };
      }
      case 'demonitor': {
        const monRef = args[0];
        for (const [, monMap] of monitors) monMap.delete(monRef);
        return { suspend: false, next: k(undefined) };
      }
      case 'trapExit': {
        trapExitMap.set(id, args[0] === true || args[0]);
        return { suspend: false, next: k(undefined) };
      }
      // ── v1.6 Phase 3 — distributed node primitives ────────────────────
      case 'startNode': {
        _localNodeId = String(args[0]);
        if (args[1] != null) _selfUrl = String(args[1]);
        // v1.23 — auto-register operational /_ssc-cluster/* HTTP routes
        // so `ssc cluster status / drain / events / step-down /
        // metrics-prom` work against codegen-built Node nodes.  Idempotent.
        _installClusterRoutes();
        // Register /_ssc-actors WS handler for inbound peer connections.
        // Peers connect, exchange nodeId handshake, then send actor messages.
        if (typeof onWebSocket === 'function') {
          // protocols: echo `ssc-actors-v1` so a spec-compliant `ws` peer client
          // (JS `connectNode`, and any RFC-6455 client) doesn't reject the upgrade
          // with "Server sent no subprotocol" — which would leave peers __pending__
          // and block Bully convergence. Mirrors the JVM-codegen server fix.
          onWebSocket('/_ssc-actors', [], ['ssc-actors-v1'])((ws) => {
            let peerNodeId = '';
            ws.onMessage((msg) => {
              if (!peerNodeId) {
                try {
                  const h = JSON.parse(msg);
                  if (h.nodeId) {
                    peerNodeId = h.nodeId;
                    ws.send(JSON.stringify({ nodeId: _localNodeId }));
                    // Register send channel for this inbound peer.
                    _peerChannels.set(peerNodeId, { sab: null, send: (json) => ws.send(json) });
                    _fireClusterEvent('NodeJoined', peerNodeId);
                    // v1.23 — snapshot the cluster config + drain state to the new peer.
                    _sendConfigSnapshot((json) => ws.send(json));
                    _sendDrainState((json) => ws.send(json));
                    _sendMetricSnapshot((json) => ws.send(json));
                  }
                } catch (_) {}
              } else {
                // v1.23 — tag pongs inbound on this server-side channel.
                try {
                  const p = JSON.parse(msg);
                  if (p && p.t === 'pong') {
                    _remoteRawInbox.push(JSON.stringify({ t: 'pong', from: peerNodeId }));
                    return;
                  }
                } catch (_) {}
                _remoteRawInbox.push(msg);
              }
            });
            ws.onClose(() => {
              if (peerNodeId) {
                _nodeDownQueue.push(peerNodeId);
                _peerChannels.delete(peerNodeId);
                _peerUrls.delete(peerNodeId);
                _peerPongHist.delete(peerNodeId);
                _peerLastPong.delete(peerNodeId);
                _peerPhiViews.delete(peerNodeId);
                _drainingPeers.delete(peerNodeId);
                for (const [, inner] of _clusterMetrics) inner.delete(peerNodeId);
                _fireClusterEvent('NodeLeft', peerNodeId, 'disconnect');
                if (_currentLeader === peerNodeId) {
                  _currentLeader = "";
                  _fireLeaderEvent("LeaderLost", peerNodeId);
                  if (_autoReelect) _startElection();
                }
              }
            });
          });
        }
        return { suspend: false, next: k(undefined) };
      }
      case 'connectNode': {
        const peerUrl = String(args[0]);
        const token   = args[1] != null ? String(args[1]) : '';
        _connectNodeAsync(peerUrl, token);
        return { suspend: false, next: k(undefined) };
      }
      case 'joinCluster': {
        _joinMode  = true;
        _joinToken = args[1] != null ? String(args[1]) : '';
        const seeds = args[0];
        const urls  = Array.isArray(seeds) ? seeds : [];
        for (const u of urls) { if (typeof u === 'string' && u) _connectNodeAsync(u, _joinToken); }
        return { suspend: false, next: k(undefined) };
      }
      case 'register': {
        const regName = args[0];
        const regPid  = args[1];
        if (regPid && regPid._type === 'Pid') _nodeRegistry.set(regName, regPid.localId);
        return { suspend: false, next: k(undefined) };
      }
      case 'whereis': {
        const lookName = args[0];
        const found = _nodeRegistry.has(lookName)
          ? { _type: '_Some', value: Pid(_localNodeId, _nodeRegistry.get(lookName)) }
          : { _type: '_None' };
        return { suspend: false, next: k(found) };
      }
      case 'globalRegister': {
        const grName = args[0];
        const grPidRaw = args[1];
        if (grPidRaw && grPidRaw._type === 'Pid') {
          // v1.23 — stamp local nodeId on Pids that came back from a
          // local spawn (which sets nodeId='').  Without this the
          // broadcast payload's `nodeId` is empty and remote nodes
          // silently drop every cross-node send to this name.
          const grNid = grPidRaw.nodeId ? grPidRaw.nodeId : _localNodeId;
          const grPid = Pid(grNid, grPidRaw.localId);
          _globalRegistry.set(grName, grPid);
          const payload = JSON.stringify({ t: 'global_reg', name: grName, nodeId: grNid, localId: String(grPid.localId) });
          for (const [, peer] of _peerChannels) { try { peer.send(payload); } catch (_) {} }
        }
        return { suspend: false, next: k(undefined) };
      }
      case 'globalWhereis': {
        const gwName = args[0];
        const gwPid  = _globalRegistry.get(gwName);
        const found  = gwPid ? { _type: '_Some', value: gwPid } : { _type: '_None' };
        return { suspend: false, next: k(found) };
      }
      // v1.23 — cluster visibility
      case 'clusterMembers': {
        const mems = [];
        for (const [nid, peer] of _peerChannels) {
          if (peer && !peer.pending) mems.push(nid);
        }
        return { suspend: false, next: k(mems) };
      }
      case 'subscribeClusterEvents': {
        _clusterEventSubs.add(id);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — phi-accrual failure detector
      case 'phiOf': {
        return { suspend: false, next: k(_computePhi(String(args[0]))) };
      }
      case 'isSuspect': {
        const thr = args[1] == null ? 8.0 : Number(args[1]);
        return { suspend: false, next: k(_computePhi(String(args[0])) >= thr) };
      }
      // v1.23 — local node identity
      case 'selfNode': {
        return { suspend: false, next: k(_localNodeId) };
      }
      // v1.23 — cluster health (phi vector for connected peers)
      case 'clusterHealth': {
        const m = new Map();
        for (const [nid, peer] of _peerChannels) {
          if (peer && !peer.pending) m.set(nid, _computePhi(nid));
        }
        return { suspend: false, next: k(m) };
      }
      // v1.23 — cluster-wide FD: broadcast phi vector to peers.
      case 'broadcastHealth': {
        const view = [];
        for (const [nid, peer] of _peerChannels) {
          if (peer && !peer.pending) {
            const phi = _computePhi(nid);
            if (Number.isFinite(phi)) view.push([nid, phi]);
          }
        }
        const payload = JSON.stringify({ t: 'phi_vector', from: _localNodeId, view });
        for (const [, peer] of _peerChannels) {
          if (peer && !peer.pending) { try { peer.send(payload); } catch (_) {} }
        }
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — cluster-wide FD: majority vote of phi >= threshold across peers.
      case 'clusterIsDown': {
        const target = String(args[0]);
        const thr    = args[1] == null ? 8.0 : Number(args[1]);
        let votes = 0;
        let total = 0;
        if (_peerChannels.has(target) && _peerChannels.get(target) && !_peerChannels.get(target).pending) {
          total += 1;
          if (_computePhi(target) >= thr) votes += 1;
        }
        for (const [peerNid, peerView] of _peerPhiViews) {
          if (peerNid === target) continue;
          const p = peerView.get(target);
          if (p != null) { total += 1; if (p >= thr) votes += 1; }
        }
        const majority = Math.floor((total + 1) / 2);
        return { suspend: false, next: k(total > 0 && votes >= majority) };
      }
      // v1.23 — leader election (Bully or Raft, picked by _leaderProtocol)
      case 'electLeader': {
        if (_leaderProtocol === 'raft') _startRaftElection();
        else _startElection();
        return { suspend: false, next: k(undefined) };
      }
      case 'currentLeader': {
        if (_leaderProtocol === 'raft') return { suspend: false, next: k(_raftLeaderId) };
        if (_leaderProtocol === 'coord') {
          let held = "";
          if (_coordHolderFn) {
            try {
              const opt = _coordHolderFn();
              // Option emits as { _type: '_Some', value } or { _type: '_None' }.
              if (opt && opt._type === '_Some') held = String(opt.value || "");
            } catch (_) {}
          }
          return { suspend: false, next: k(held) };
        }
        return { suspend: false, next: k(_currentLeader) };
      }
      case 'subscribeLeaderEvents': {
        _leaderEventSubs.add(id);
        return { suspend: false, next: k(undefined) };
      }
      case 'setAutoReelect': {
        _autoReelect = !!args[0];
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — protocol switch + history (cluster-raft.md §6)
      case 'useRaftLeaderElection': {
        _leaderProtocol = 'raft';
        _raftLoad();
        _raftStateName  = 'follower';
        _raftElectionDue = Date.now() + _raftRandTimeout();
        _ensureRaftTickThread();
        return { suspend: false, next: k(undefined) };
      }
      case 'useExternalCoordinator': {
        _leaderProtocol = 'coord';
        if (args.length >= 4) {
          _coordAcquireFn = typeof args[0] === 'function' ? args[0] : null;
          _coordRenewFn   = typeof args[1] === 'function' ? args[1] : null;
          _coordReleaseFn = typeof args[2] === 'function' ? args[2] : null;
          _coordHolderFn  = typeof args[3] === 'function' ? args[3] : null;
          if (_coordAcquireFn) {
            let got = false;
            try { got = !!_coordAcquireFn(_localNodeId, _COORD_LEASE_TIMEOUT_MS); } catch (_) {}
            if (got) {
              _coordIsLeader = true;
              const prev = _currentLeader;
              _currentLeader = _localNodeId;
              // Record every accepted claim (mirror ActorScheduler): single-node
              // mode has empty _localNodeId, so the prev-changed guard would skip
              // the initial history entry. Only _fireLeaderEvent stays guarded.
              _recordLeaderHist(_localNodeId);
              if (prev !== _localNodeId) {
                _fireLeaderEvent("LeaderElected", _localNodeId);
              }
            }
            _ensureCoordTickThread();
          }
        }
        return { suspend: false, next: k(undefined) };
      }
      case 'leaderProtocol': {
        return { suspend: false, next: k(_leaderProtocol) };
      }
      case 'leaderHistory': {
        // Return a copy so callers can't mutate our buffer.
        return { suspend: false, next: k(_leaderHist.map(e => [e[0], e[1], e[2]])) };
      }
      // v1.23 — auto-reconnect policy
      case 'setReconnectPolicy': {
        const ini = Number(args[0]) | 0;
        const mx  = Number(args[1]) | 0;
        // v1.23 — optional 3rd arg: total wall-clock retry budget per
        // URL; 0 = no cap (retry forever).
        const giveUp = args.length > 2 ? (Number(args[2]) | 0) : 0;
        _reconnectInitialMs = Math.max(0, ini);
        _reconnectMaxMs     = Math.max(_reconnectInitialMs, mx);
        _reconnectGiveUpMs  = Math.max(0, giveUp);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — heartbeat cadence + dead-after
      case 'setHeartbeatTimeout': {
        const iv   = Number(args[0]) | 0;
        const dead = Number(args[1]) | 0;
        _peerHeartbeatIntervalMs  = Math.max(1, iv);
        _peerHeartbeatDeadAfterMs = Math.max(_peerHeartbeatIntervalMs, dead);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — quorum-aware Bully threshold
      case 'setQuorumSize': {
        const n = Number(args[0]) | 0;
        _quorumSize = Math.max(0, n);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — shared-secret Bearer token for /_ssc-cluster/* endpoints.
      case 'setClusterAuthToken': {
        _clusterAuthToken = args[0] != null ? String(args[0]) : '';
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — periodic gossip re-discovery
      case 'requestGossip': {
        const payload = '{"t":"peers_req","from":' + JSON.stringify(_localNodeId) + '}';
        for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — cluster configuration distribution
      case 'clusterConfigSet': {
        const key   = String(args[0]);
        const value = String(args[1]);
        const ts    = Date.now();
        _applyConfigUpdate(key, value, ts, _localNodeId);
        const payload = JSON.stringify({ t: 'config_set', key, value, ts, origin: _localNodeId });
        for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
        return { suspend: false, next: k(undefined) };
      }
      case 'clusterConfigGet': {
        const entry = _clusterConfig.get(String(args[0]));
        const result = entry ? { _type: '_Some', value: entry.value } : { _type: '_None' };
        return { suspend: false, next: k(result) };
      }
      case 'clusterConfigKeys': {
        return { suspend: false, next: k(Array.from(_clusterConfig.keys())) };
      }
      case 'subscribeConfigEvents': {
        _configEventSubs.add(id);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — drain / rolling-restart
      case 'setDraining': {
        const b = !!args[0];
        const prev = _isDrainingSelf;
        _isDrainingSelf = b;
        if (prev !== b) {
          const payload = JSON.stringify({ t: 'drain', from: _localNodeId, draining: b });
          for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
          _fireDrainEvent(_localNodeId, b);
          if (b) _stepDownIfLeader();
        }
        return { suspend: false, next: k(undefined) };
      }
      case 'isDraining': {
        return { suspend: false, next: k(_isDrainingSelf) };
      }
      case 'drainingPeers': {
        const buf = [];
        for (const [nid, dr] of _drainingPeers) if (dr) buf.push(nid);
        return { suspend: false, next: k(buf) };
      }
      case 'subscribeDrainEvents': {
        _drainEventSubs.add(id);
        return { suspend: false, next: k(undefined) };
      }
      // v1.23 — cluster metrics aggregation
      case 'clusterMetricSet': {
        const name = String(args[0]);
        const value = Number(args[1]);
        _applyMetricUpdate(name, _localNodeId, value);
        const payload = JSON.stringify({ t: 'metric', from: _localNodeId, name, value });
        for (const [, ch] of _peerChannels) { try { ch.send(payload); } catch (_) {} }
        return { suspend: false, next: k(undefined) };
      }
      case 'clusterMetricGet': {
        const inner = _clusterMetrics.get(String(args[0]));
        const m = new Map();
        if (inner) for (const [nid, v] of inner) m.set(nid, v);
        return { suspend: false, next: k(m) };
      }
      case 'clusterMetricSum': {
        const inner = _clusterMetrics.get(String(args[0]));
        let sum = 0;
        if (inner) for (const v of inner.values()) sum += v;
        return { suspend: false, next: k(sum) };
      }
      case 'clusterMetricNames': {
        return { suspend: false, next: k(Array.from(_clusterMetrics.keys())) };
      }
      case 'subscribeMetricEvents': {
        _metricEventSubs.add(id);
        return { suspend: false, next: k(undefined) };
      }
      // v1.6.x — scheduled sends
      case 'sendAfter': {
        // `delayMs` is declared Long at the language surface and therefore may
        // be BigInt; Date/setTimeout deadlines are host Numbers.
        const delayMs  = Number(args[0]);
        const tgtPid   = args[1];
        const tMsg     = args[2];
        const fireAt   = Date.now() + delayMs;
        const tRef     = _nextTimerId++;
        _timers.set(tRef, { fireAt, period: null, targetId: tgtPid.localId, msg: tMsg });
        return { suspend: false, next: k(tRef) };
      }
      case 'sendInterval': {
        const periodMs = Number(args[0]);
        const tgtPid2  = args[1];
        const tMsg2    = args[2];
        const fireAt2  = Date.now() + periodMs;
        const tRef2    = _nextTimerId++;
        _timers.set(tRef2, { fireAt: fireAt2, period: periodMs, targetId: tgtPid2.localId, msg: tMsg2 });
        return { suspend: false, next: k(tRef2) };
      }
      case 'cancelTimer': {
        _timers.delete(args[0]);
        return { suspend: false, next: k(undefined) };
      }
      default:
        throw new Error('Unknown Actor op: ' + op);
    }
  }

  function stepActor(id) {
    const state = actors.get(id);
    if (!state) return;
    let current = state.pending;
    state.pending = null;
    while (true) {
      if (current instanceof _Perform) {
        if (current.eff !== 'Actor')
          throw new Error('Unhandled effect inside actor: ' + current.eff + '.' + current.op);
        const r = handleActorOp(id, state, current.op, current.args, (v) => v);
        if (r.suspend) return;
        current = r.next;
      } else if (current instanceof _FlatMap) {
        const sub = current.sub;
        if (sub instanceof _FlatMap) {
          const sub2 = sub.sub, g = sub.k, f = current.k;
          current = new _FlatMap(sub2, (x) => new _FlatMap(g(x), f));
        } else if (sub instanceof _Perform) {
          if (sub.eff !== 'Actor')
            throw new Error('Unhandled effect inside actor: ' + sub.eff + '.' + sub.op);
          const r = handleActorOp(id, state, sub.op, sub.args, current.k);
          if (r.suspend) return;
          current = r.next;
        } else {
          current = current.k(sub);
        }
      } else {
        // Pure value — actor done normally; fire monitors with reason "normal".
        if (id === rootId) rootResult = current;
        const myPid = Pid(_localNodeId, id);
        const monMap = monitors.get(id);
        monitors.delete(id);
        if (monMap) {
          for (const [monRef, observerId] of monMap) {
            const st = actors.get(observerId);
            if (st) {
              st.mailbox.push({ _type: 'Down', ref: monRef, from: myPid, reason: 'normal' });
              tryWakeBlocked(observerId);
            }
          }
        }
        // Clean up link entries (normal exit does not propagate link signals).
        const linkedSet = links.get(id);
        links.delete(id);
        if (linkedSet) {
          for (const linkedId of linkedSet) {
            const ls = links.get(linkedId);
            if (ls) ls.delete(id);
          }
        }
        actors.delete(id);
        return;
      }
    }
  }

  // Lightweight yield to Node's event loop.  `setImmediate` runs after
  // pending I/O callbacks (HTTP request handlers, WS frames, accept'ed
  // connections) drain — exactly what we need so a `serveAsync(...)`
  // bound from inside the actor body becomes reachable while the
  // scheduler keeps spinning.  Falls back to a resolved Promise when
  // `setImmediate` is missing (non-Node hosts), which still yields one
  // microtask between iterations.
  const _yieldToIO = () => (typeof setImmediate === 'function')
    ? new Promise(r => setImmediate(r))
    : Promise.resolve();
  while (true) {
    // Drain peer ring buffers then node-down queue before each scheduler tick.
    if (_peerChannels.size > 0) {
      _drainPeerBuffers();
      _processRemoteInbox();
    }
    while (_nodeDownQueue.length > 0) _fireNodeDown(_nodeDownQueue.shift());
    // v1.23 — deliver cluster events to subscribers.
    while (_clusterEventQueue.length > 0) {
      const _ev = _clusterEventQueue.shift();
      const _msg = _ev.tag === 'NodeJoined'
        ? { _type: 'NodeJoined', nodeId: _ev.nodeId }
        : { _type: 'NodeLeft',   nodeId: _ev.nodeId, reason: _ev.reason };
      for (const _sid of _clusterEventSubs) {
        const _st = actors.get(_sid);
        if (_st) { _st.mailbox.push(_msg); tryWakeBlocked(_sid); }
      }
    }
    // v1.23 — Bully election timeout: claim self if no higher-id peer responded.
    if (_electionInProgress && Date.now() - _electionStartedAt >= _ELECTION_TIMEOUT_MS) {
      _electionInProgress = false;
      // v1.23 — quorum gate: same as `_startElection.higher.length === 0`
      // branch — even though no higher peer responded, decline self-
      // claim when below quorum.
      if (!_gotAliveResponse && _hasQuorum()) {
        const _prev = _currentLeader;
        _currentLeader = _localNodeId;
        _broadcastCoordinator();
        if (_prev !== _localNodeId) { _fireLeaderEvent("LeaderElected", _localNodeId); _recordLeaderHist(_localNodeId); }
      }
    }
    // v1.23 — deliver leader events to subscribers.
    while (_leaderEventQueue.length > 0) {
      const _lev = _leaderEventQueue.shift();
      const _lmsg = _lev.tag === 'LeaderElected'
        ? { _type: 'LeaderElected', nodeId: _lev.leaderId }
        : { _type: 'LeaderLost',    nodeId: _lev.leaderId };
      for (const _sid of _leaderEventSubs) {
        const _st = actors.get(_sid);
        if (_st) { _st.mailbox.push(_lmsg); tryWakeBlocked(_sid); }
      }
    }
    // v1.23 — deliver config-change events to subscribers.
    while (_configEventQueue.length > 0) {
      const _cev = _configEventQueue.shift();
      const _cmsg = { _type: 'ConfigChanged', key: _cev.key, value: _cev.value };
      for (const _sid of _configEventSubs) {
        const _st = actors.get(_sid);
        if (_st) { _st.mailbox.push(_cmsg); tryWakeBlocked(_sid); }
      }
    }
    // v1.23 — deliver drain-state events to subscribers.
    while (_drainEventQueue.length > 0) {
      const _dev = _drainEventQueue.shift();
      const _dmsg = { _type: 'DrainStateChanged', nodeId: _dev.nodeId, draining: _dev.draining };
      for (const _sid of _drainEventSubs) {
        const _st = actors.get(_sid);
        if (_st) { _st.mailbox.push(_dmsg); tryWakeBlocked(_sid); }
      }
    }
    // v1.23 — deliver metric events to subscribers.
    while (_metricEventQueue.length > 0) {
      const _mev = _metricEventQueue.shift();
      const _mmsg = { _type: 'MetricChanged', name: _mev.name, nodeId: _mev.nodeId, value: _mev.value };
      for (const _sid of _metricEventSubs) {
        const _st = actors.get(_sid);
        if (_st) { _st.mailbox.push(_mmsg); tryWakeBlocked(_sid); }
      }
    }
    // Fire scheduled sends whose deadline has passed.
    if (_timers.size > 0) {
      const _nowMs = Date.now();
      for (const [_tRef, _te] of Array.from(_timers)) {
        if (_nowMs >= _te.fireAt) {
          const _tSt = actors.get(_te.targetId);
          if (_tSt) { _tSt.mailbox.push(_te.msg); tryWakeBlocked(_te.targetId); }
          if (_te.period != null) {
            _timers.set(_tRef, { fireAt: _te.fireAt + _te.period, period: _te.period,
                                  targetId: _te.targetId, msg: _te.msg });
          } else {
            _timers.delete(_tRef);
          }
        }
      }
    }
    if (ready.length > 0) {
      const id = ready.shift();
      const state = actors.get(id);
      if (state && state.pending !== null) stepActor(id);
      // When distributed, give Node's event loop a tick between every
      // actor step — peer-channel I/O, accept'ed HTTP connections, and
      // remote-inbox writes from worker threads all flow through the
      // libuv queue, and a tight `ready.shift() → stepActor` loop would
      // otherwise starve them.  Pure-actor (non-distributed) workloads
      // skip this — the scheduler runs straight through and matches the
      // pre-async tick performance to within a few %.
      if (_peerChannels.size > 0) await _yieldToIO();
    } else {
      // Quiescence — but timeout-armed receives, timers, remote messages, or node-downs may fire.
      if (_nodeDownQueue.length > 0) continue;
      let earliest = null;
      for (const [aid, st] of actors) {
        if (st && st.blocked && st.blocked.deadline != null) {
          if (earliest === null || st.blocked.deadline < earliest.d)
            earliest = { id: aid, d: st.blocked.deadline };
        }
      }
      // Earliest pending timer deadline.
      let _timerMin = null;
      for (const [, _te] of _timers) {
        if (_timerMin === null || _te.fireAt < _timerMin) _timerMin = _te.fireAt;
      }
      // Distributed: keep running while peer channels are open and actors are blocked.
      const isDistributed = _peerChannels.size > 0;
      const hasBlockedActors = isDistributed && actors.size > 0 &&
        [...actors.values()].some(st => st && st.blocked !== null);
      if (!earliest && _timers.size === 0 && !hasBlockedActors &&
          !_electionInProgress && _leaderEventQueue.length === 0 &&
          _configEventQueue.length === 0 && _drainEventQueue.length === 0 &&
          _metricEventQueue.length === 0) break;
      const _sleepUntil = [earliest != null ? earliest.d : null, _timerMin].filter(v => v != null);
      const _minDeadline = _sleepUntil.length > 0 ? Math.min(..._sleepUntil) : null;
      const sleepFor = _minDeadline != null ? Math.min(_minDeadline - Date.now(), 10) : 10;
      // Promise-based sleep — `setTimeout(r, ms)` registers a libuv
      // timer and `await`-ing the Promise releases the main thread, so
      // Node's event loop can dispatch HTTP/WS callbacks, ring-buffer
      // writes from peer workers, and any other libuv-driven I/O.
      // Previously this was `_asyncSleep` (Atomics.wait on the main
      // thread), which Node ≥ 16 permits but blocks the event loop
      // entirely — every HTTP request `accept`ed by libuv queued and
      // never dispatched, making `/_ssc-cluster/*` endpoints
      // unreachable for the duration of the sleep.
      if (sleepFor > 0) await new Promise(r => setTimeout(r, sleepFor));
      else              await _yieldToIO();
      if (earliest != null) {
        const now = Date.now();
        const s = actors.get(earliest.id);
        if (s && s.blocked && s.blocked.deadline != null && now >= s.blocked.deadline) {
          const kk = s.blocked.k;
          s.blocked = null;
          s.pending = kk(_None);
          ready.push(earliest.id);
        }
      }
    }
  }
  // v1.23 — graceful cluster shutdown: release the coord lease if we
  // hold it, so the next leader can claim immediately.
  if (_leaderProtocol === 'coord' && _coordIsLeader && _coordReleaseFn) {
    try { _coordReleaseFn(_localNodeId); } catch (_) {}
    _coordIsLeader = false;
  }
  // Clear tick intervals so they don't leak across reused processes.
  if (_raftTickHandle  != null) { clearInterval(_raftTickHandle);  _raftTickHandle  = null; }
  if (_coordTickHandle != null) { clearInterval(_coordTickHandle); _coordTickHandle = null; }
  return rootResult;
}

// ── v1.10 Generator — pull-based lazy streams via JS native generators ────
// generator { () => ...; suspend(v); ... } lowers to
//   _makeGenerator(function*() { ...; yield v; ... })
// The returned object wraps the JS iterator with the ScalaScript API.
function _makeGenerator(genFn) {
  const iter = genFn();
  function _wrap(iter2) {
    return {
      // `next()` yields a real `Option` (Some(v)/None) — matching the interpreter —
      // so `println(g.next())` renders `Some(10)`/`None` and `g.next() match { case
      // Some(v) => … }` works. (Previously returned a bespoke `{_isSome,_value}`/null
      // shape that `_show` rendered as `[object Object]`/`null`.) The
      // fromGenerator→async-stream bridge (JsGen `_makeAsyncStream`) reads this same
      // Option shape. (js-generator-next-option.)
      next()    { const r = iter2.next(); return r.done ? _None : _Some(r.value); },
      nextOpt() { const r = iter2.next(); return r.done ? _None : _Some(r.value); },
      foreach(f) { for (const v of { [Symbol.iterator]() { return iter2; } }) f(v); },
      toList()  { const a = []; for (const v of { [Symbol.iterator]() { return iter2; } }) a.push(v); return a; },
      map(f)    { return _wrap((function*(it) { for (const v of { [Symbol.iterator]() { return it; } }) yield f(v); })(iter2)); },
      filter(p) { return _wrap((function*(it) { for (const v of { [Symbol.iterator]() { return it; } }) if (p(v)) yield v; })(iter2)); },
      take(n)   { return _wrap((function*(it) { let i=0; for (const v of { [Symbol.iterator]() { return it; } }) { if(i++>=n) break; yield v; } })(iter2)); },
      drop(n)   { return _wrap((function*(it) { let i=0; for (const v of { [Symbol.iterator]() { return it; } }) { if(i++<n) continue; yield v; } })(iter2)); },
      flatMap(f){ return _wrap((function*(it) { for (const v of { [Symbol.iterator]() { return it; } }) { const inner = f(v); yield* { [Symbol.iterator]() { const i2 = inner._iter(); return { next() { const r = i2.next(); return r; } }; } }; } })(iter2)); },
      zip(other){ return _wrap((function*(it) { const oit = other._iter(); for (const v of { [Symbol.iterator]() { return it; } }) { const ob = oit.next(); if (ob.done) break; const t = [v, ob.value]; t._isTuple=true; yield t; } })(iter2)); },
      zipWithIndex(){ return _wrap((function*(it) { let i=0; for (const v of { [Symbol.iterator]() { return it; } }) { const t=[v,i++]; t._isTuple=true; yield t; } })(iter2)); },
      _iter()   { return iter2; },
    };
  }
  return _wrap(iter);
}

// ── v1.51.2 Streams — backpressured Source[A] via async function* ──────────
// stream { emit(x) } compiles to _makeAsyncStream((async function*() { body })())
// emit(x) inside the body lowers to yield x (same as suspend for generators).
// Terminal ops (runForeach, runFold, runToList, runDrain) are async and must be
// awaited at the call site — JsGen auto-inserts await at statement level.
function _makeAsyncStream(iter) {
  const self = {
    [Symbol.asyncIterator]() { return iter; },
    map(f)    { return _makeAsyncStream((async function*(it) { for await (const v of it) yield f(v); })(iter)); },
    filter(p) { return _makeAsyncStream((async function*(it) { for await (const v of it) if (p(v)) yield v; })(iter)); },
    take(n)   { return _makeAsyncStream((async function*(it) { let i=0; for await (const v of it) { if (i++>=n) break; yield v; } })(iter)); },
    drop(n)   { return _makeAsyncStream((async function*(it) { let i=0; for await (const v of it) { if (i++<n) continue; yield v; } })(iter)); },
    flatMap(f){ return _makeAsyncStream((async function*(it) { for await (const v of it) { for await (const x of f(v)) yield x; } })(iter)); },
    concat(other){ return _makeAsyncStream((async function*(it) { for await (const v of it) yield v; for await (const v of other) yield v; })(iter)); },
    zip(other){ return _makeAsyncStream((async function*(it) { const it2 = other[Symbol.asyncIterator](); for await (const v of it) { const b = await it2.next(); if (b.done) break; const t=[v,b.value]; t._isTuple=true; yield t; } })(iter)); },
    async runForeach(f) { for await (const v of iter) f(v); },
    async runFold(z)    { return async (f) => { let acc = z; for await (const v of iter) acc = f(acc, v); return acc; }; },
    async runToList()   { const a = []; for await (const v of iter) a.push(v); return a; },
    async runDrain()    { for await (const _ of iter) {} },
    // ── v1.51.1 scan / onError / cancellable ─────────────────────────────
    scan(z)    { return (f) => _makeAsyncStream((async function*(it) { let acc=z; for await (const v of it) { acc=f(acc,v); yield acc; } })(iter)); },
    onError(f) { return _makeAsyncStream((async function*(it) { try { for await (const v of it) yield v; } catch(e) { f(e&&e.message||String(e)); } })(iter)); },
    cancellable() { let _c=false; const src=_makeAsyncStream((async function*(it) { try { for await (const v of it) { if(_c) break; yield v; } } catch(e){} })(iter)); const t=[src,()=>{_c=true;}]; t._isTuple=true; return t; },
    // ── v1.51.2 combining operators ───────────────────────────────────────
    merge(other)    { return _makeAsyncStream((async function*(it) { for await (const v of it) yield v; for await (const v of other) yield v; })(iter)); },
    zipWith(other)  { return (f) => _makeAsyncStream((async function*(it) { const it2=other[Symbol.asyncIterator](); for await (const v of it) { const b=await it2.next(); if(b.done) break; yield f(v,b.value); } })(iter)); },
    async broadcast(n) { const a=[]; for await (const v of iter) a.push(v); return Array.from({length:n},()=>_makeAsyncStream((async function*(xs){for(const v of xs)yield v;})(a))); },
    async balance(n)   { const a=[]; for await (const v of iter) a.push(v); return Array.from({length:n},(_,i)=>_makeAsyncStream((async function*(xs){for(const v of xs)yield v;})(a.filter((_,j)=>j%n===i)))); },
    async groupBy(kf)  { const a=[]; for await (const v of iter) a.push(v); const g=new Map(); for(const v of a){const k=kf(v);if(!g.has(k))g.set(k,[]);g.get(k).push(v);} const ps=[]; for(const [k,vs] of g){const t=[k,_makeAsyncStream((async function*(xs){for(const v of xs)yield v;})(vs))];t._isTuple=true;ps.push(t);} return _makeAsyncStream((async function*(arr){for(const v of arr)yield v;})(ps)); },
    mergeSubstreams()  { return _makeAsyncStream((async function*(it) { for await (const v of it) { for await (const x of v) yield x; } })(iter)); },
    // ── v1.51.2 advanced operators ────────────────────────────────────────
    buffer(n,s)    { return this; },
    throttle(rate) { return _makeAsyncStream((async function*(it) { let cnt=0,start=Date.now(); for await (const v of it) { cnt++; if(cnt>rate.elements){const e=Date.now()-start;if(e<rate.perMillis)await new Promise(r=>setTimeout(r,rate.perMillis-e));start=Date.now();cnt=1;} yield v; } })(iter)); },
    debounce(ms)   { return _makeAsyncStream((async function*(it) { let last,has=false; for await (const v of it){last=v;has=true;} if(has){await new Promise(r=>setTimeout(r,ms));yield last;} })(iter)); },
    mapAsync(n)    { return (f) => _makeAsyncStream((async function*(it) { const a=[]; for await (const v of it) a.push(v); const r=await Promise.all(a.map(f)); for(const v of r) yield v; })(iter)); },
    recover(h)     { return _makeAsyncStream((async function*(it) { try { for await (const v of it) yield v; } catch(e) { yield h(e&&e.message||String(e)); } })(iter)); },
    mapError(f)    { return _makeAsyncStream((async function*(it) { try { for await (const v of it) yield v; } catch(e) { f(e&&e.message||String(e)); } })(iter)); },
    // ── v1.51.2 routing ───────────────────────────────────────────────────
    async to(sink) { return sink.run(this); },
    via(flow)      { return flow.apply(this); },
  };
  return self;
}

// ── v1.9 Coroutine primitive — JS native generators ────────────────────────
// coroutineCreate(fn) wraps a function* generator; coroutineResume steps it.
// suspend(v) inside a coroutineCreate body compiles to `yield v` (JsGen
// emits it via genGeneratorBody / genGenExpr, same path as generator bodies).
function Yielded(value)   { return { _type: 'Yielded',   value }; }
function Returned(value)  { return { _type: 'Returned',  value }; }
function Errored(message) { return { _type: 'Errored',  message }; }

function _coroutineCreate(genFn) {
  const gen = genFn();
  return { _type: '_Coroutine', _gen: gen, _done: false };
}

function _coroutineResume(co, input) {
  if (co._done) throw new Error('coroutineResume: coroutine already completed');
  let r;
  try { r = co._gen.next(input); }
  catch (e) { co._done = true; return Errored(e.message || String(e)); }
  if (r.done) { co._done = true; return Returned(r.value); }
  return Yielded(r.value);
}
// Runtime stub: suspend called outside a generator/coroutine body throws.
// genGenExpr rewrites suspend(v) → (yield v) inside function* bodies.
function suspend(v) { throw new Error('suspend called outside a coroutine or generator body'); }

// ── Storage: built-in key-value effect ────────────────────────────────────
//
// `Storage.{get,put,remove,has,keys}` produce `_Perform("Storage", op,
// args)` nodes; `_runStorage(bodyFn, path)` is the handler — when
// `path` is non-null it hydrates from / flushes to a JSON file on
// every mutation (file-backed mode), otherwise it stays in memory
// (ephemeral mode, for tests).  Same Free-Monad walking shape as
// `_runAsync`; non-Storage Performs propagate outward so an outer
// handler picks them up.
const Storage = {
  get:    (key)        => _perform('Storage', 'get',    [key]),
  put:    (key, value) => _perform('Storage', 'put',    [key, value]),
  remove: (key)        => _perform('Storage', 'remove', [key]),
  has:    (key)        => _perform('Storage', 'has',    [key]),
  keys:   ()           => _perform('Storage', 'keys',   []),
};

function _storageDefaultPath() {
  if (typeof process !== 'undefined' && process.env && process.env.SSC_STORAGE_PATH) {
    return process.env.SSC_STORAGE_PATH;
  }
  return './ssc-storage.json';
}

function _storageLoad(path, state) {
  if (typeof require === 'undefined') return;
  try {
    const fs = require('fs');
    if (!fs.existsSync(path)) return;
    const src = fs.readFileSync(path, 'utf-8');
    const obj = JSON.parse(src);
    for (const [k, v] of Object.entries(obj)) state.set(k, String(v));
  } catch (e) { /* corrupt file — start empty */ }
}

function _storageSave(path, state) {
  if (typeof require === 'undefined') return;
  const fs  = require('fs');
  const obj = {};
  for (const [k, v] of state) obj[k] = v;
  fs.writeFileSync(path, JSON.stringify(obj));
}

function _runStorage(bodyFn, path) {
  const state = new Map();
  if (path) _storageLoad(path, state);
  function flush() { if (path) _storageSave(path, state); }

  function dispatch(op, args, resume) {
    switch (op) {
      case 'get': {
        const k = args[0];
        return resume(state.has(k) ? _Some(state.get(k)) : _None);
      }
      case 'put': {
        const k = args[0], v = args[1];
        state.set(k, _show(v));
        flush();
        return resume(undefined);
      }
      case 'remove': {
        state.delete(args[0]);
        flush();
        return resume(undefined);
      }
      case 'has':  return resume(state.has(args[0]));
      case 'keys': return resume([...state.keys()]);
      default:     throw new Error('Unknown Storage operation: ' + op);
    }
  }

  function interp(initial) {
    let current = initial;
    while (true) {
      if (current instanceof _Perform) {
        if (current.eff === 'Storage') {
          current = dispatch(current.op, current.args, (v) => v);
        } else { return current; }
      } else if (current instanceof _FlatMap) {
        const sub = current.sub;
        if (sub instanceof _FlatMap) {
          const sub2 = sub.sub, g = sub.k, f = current.k;
          current = new _FlatMap(sub2, (x) => new _FlatMap(g(x), f));
        } else if (sub instanceof _Perform) {
          const f = current.k;
          if (sub.eff === 'Storage') {
            current = dispatch(sub.op, sub.args, (v) => interp(f(v)));
          } else {
            return new _FlatMap(sub, (v) => interp(f(v)));
          }
        } else {
          current = current.k(sub);
        }
      } else {
        return current;
      }
    }
  }
  return interp(bodyFn());
}

// Collection sequencing helpers (_seqForeach, _seqExists, _seqForall,
// _seqCount, _seqFind, _seqFoldLeft) live in core-collections.mjs. Keep this
// fragment free of duplicate top-level declarations: runtime fragments are
// concatenated into one classic-script scope by JsGen.generateRuntime.
