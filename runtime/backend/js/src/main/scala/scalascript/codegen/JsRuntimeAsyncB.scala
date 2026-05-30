package scalascript.codegen

/** Async-effect JS runtime preamble (half B) — see `JsRuntimeAsyncA`. */
val JsRuntimeAsyncB: String = """
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
          onWebSocket('/_ssc-actors', (ws) => {
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
          ? { _type: 'Some', value: Pid(_localNodeId, _nodeRegistry.get(lookName)) }
          : { _type: 'None' };
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
        const found  = gwPid ? { _type: 'Some', value: gwPid } : { _type: 'None' };
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
              // Option emits as { _type: 'Some', value } or { _type: 'None' }.
              if (opt && opt._type === 'Some') held = String(opt.value || "");
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
              if (prev !== _localNodeId) {
                _fireLeaderEvent("LeaderElected", _localNodeId);
                _recordLeaderHist(_localNodeId);
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
        const result = entry ? { _type: 'Some', value: entry.value } : { _type: 'None' };
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
        const delayMs  = args[0];
        const tgtPid   = args[1];
        const tMsg     = args[2];
        const fireAt   = Date.now() + delayMs;
        const tRef     = _nextTimerId++;
        _timers.set(tRef, { fireAt, period: null, targetId: tgtPid.localId, msg: tMsg });
        return { suspend: false, next: k(tRef) };
      }
      case 'sendInterval': {
        const periodMs = args[0];
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
      next()    { const r = iter2.next(); return r.done ? null : { _isSome: true, _value: r.value }; },
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

// ── CPS-aware collection helpers ──────────────────────────────────────────
//
// When a higher-order method like `xs.map(fn)` is called inside an
// effectful context, `fn(x)` may return a Free tree instead of a plain
// value.  These helpers detect that and stitch the per-element Free
// values into a single sequenced Free that yields the final array.
// Pure callbacks pass straight through with no overhead.
// (_seq, _seqMap, _seqFlatMap, _seqFilter are defined in JsRuntimePart2 so
// they are available in the base runtime too.)
function _seqForeach(arr, fn) {
  const comps = arr.map(x => fn(x));
  const s     = _seq(comps);
  if (_isFree(s)) return _bind(s, () => undefined);
  return undefined;
}
function _seqExists(arr, fn) {
  const seq = _seq(arr.map(x => fn(x)));
  if (_isFree(seq)) return _bind(seq, (bs) => bs.some(b => b));
  return seq.some(b => b);
}
function _seqForall(arr, fn) {
  const seq = _seq(arr.map(x => fn(x)));
  if (_isFree(seq)) return _bind(seq, (bs) => bs.every(b => b));
  return seq.every(b => b);
}
function _seqCount(arr, fn) {
  const seq = _seq(arr.map(x => fn(x)));
  if (_isFree(seq)) return _bind(seq, (bs) => bs.filter(b => b).length);
  return seq.filter(b => b).length;
}
function _seqFind(arr, fn) {
  const seq = _seq(arr.map(x => fn(x)));
  const pick = (bs) => {
    const i = bs.findIndex(b => b);
    return i < 0 ? _None : _Some(arr[i]);
  };
  if (_isFree(seq)) return _bind(seq, pick);
  return pick(seq);
}
function _seqFoldLeft(arr, init, fn) {
  function loop(i, acc) {
    if (i === arr.length) return acc;
    const next = fn(acc, arr[i]);
    if (_isFree(next)) return _bind(next, (v) => loop(i + 1, v));
    return loop(i + 1, next);
  }
  return loop(0, init);
}
"""
