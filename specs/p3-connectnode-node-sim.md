# P3 ConnectNode Node Simulation

## Overview

The remaining distributed corpus tail is not a v2 default-runner blocker, but it
keeps the distributed map-reduce examples from being runnable offline. The
examples call `Cluster.connect(Node("worker@host:port"), ...)` with documentation
addresses, while the v2 actor bridge only provides a local loopback actor runtime.
This slice adds an explicit local map-reduce cluster helper and rewires the
offline examples to use it, so corpus and smoke gates exercise real worker
actors without pretending to open remote WebSocket nodes.

## Interface

- Add a top-level helper in `std.mapreduce.distributed`:
  `localLoopbackCluster(ns: Node*): Cluster`.
- The helper returns `Cluster(ns.toList, pids)` where each pid is a local actor
  running `WorkerProtocol.handleMessages()`.
- `Cluster(nodes, pids)` remains the lower-level explicit constructor.
- `Cluster.connect(...)` remains the remote-node API surface and continues to be
  documented as a real distributed connection hook. This slice does not change
  `connectNode`'s return type or WebSocket semantics.

## Behavior

- [ ] Offline distributed examples can build a deterministic local worker cluster
      with `localLoopbackCluster(...)` and do not hang on `connectNode` address
      strings.
- [ ] `distributed-word-count.ssc` runs through the default v2 runner and prints
      a non-empty top-10 word count.
- [ ] Existing distributed conformance stays green:
      `tests/conformance/run.sh --only 'cluster-connect,distributed-*' --no-memo`.
- [ ] The implementation does not alter `connectNode`, `startNode`, real WS actor
      routing, or the explicit `Cluster(nodes, pids)` constructor.

## Out Of Scope

- Real multi-process worker discovery or auto-starting remote nodes.
- Making `Cluster.connect(...)` infer whether an address is reachable.
- Changing actor bridge SPI or `connectNode` return type.
- Making the distributed examples depend on external data files or live network
  services.

## Design

`localLoopbackCluster` belongs in `std.mapreduce.distributed`, not
`std.mapreduce.cluster`, because it needs `WorkerProtocol`. Keeping the helper in
the distributed module avoids an import cycle between `cluster.ssc` and
`distributed.ssc`, and keeps `Cluster.connect(...)` free to remain the remote
node API.

The helper is explicit rather than fallback magic: examples that are meant to run
offline say so in source. Production code that wants a real remote cluster still
uses `Cluster.connect(...)` or constructs `Cluster(nodes, pids)` from real worker
registration.

## Decisions

- **Explicit local helper** — chosen because the current bridge has a deliberate
  local actor simulation, and offline examples should exercise that path without
  changing remote actor primitives. Rejected: changing `connectNode` to return a
  pid, because v1 declares it as `Unit` and the WS actor stack has broader
  semantics than map-reduce worker lookup.
- **Do not make `Cluster.connect` fallback to local workers** — chosen because a
  silent fallback would hide deployment mistakes. Rejected: automatic
  reachability probing, because it would make examples depend on timing/network
  behavior and blur the boundary between local simulation and real distributed
  execution.

## Results

Fill after verification with the exact commands and output counts.
