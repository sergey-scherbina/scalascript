# 51 — Async / cooperative concurrency (on effects)

> Status: **v2 (K46, 2026-06-29)** — `v2/lib/async.ssc0`, built on `lib/effects.ssc0`
> (`specs/50-effects.md`). Concurrency is **async-as-an-effect**: a few operations plus a
> scheduler handler, in ssc0, on the frozen kernel. No threads, no kernel scheduler.

## Operations & scheduler

```
yield        = perform "yield" ()        -- cooperatively give up control
fork child   = perform "fork" child      -- spawn a task, then continue
log x        = perform "log" x           -- emit a value (observable interleaving)
```

The scheduler is an effect handler over a queue of suspended computations. At each operation
it has the task's continuation `k` and decides scheduling:

```
runSched []              = []
runSched (Pure v   : q)  = runSched q                         -- task done
runSched (Op l a k : q)  =
    l == "log"   ->  a : runSched (k () : q)                  -- emit, keep running this task
    l == "yield" ->  runSched (q ++ [k ()])                   -- requeue at the tail (round-robin)
    l == "fork"  ->  runSched (k () : (q ++ [a]))             -- continue parent; enqueue child
```

The continuation `k` *is* the rest of the task; capturing and requeuing it is exactly
suspend/resume — the same mechanism as one-shot effects, used for cooperative scheduling.

## K46 richer scheduler: futures, channels, mailboxes

`runSched` remains the small v1 scheduler. K46 adds `runAsync`, a deterministic scheduler
that tracks task records `Task(fid, comp)` plus scheduler-owned maps for future results,
future waiters, channel buffers, and receive waiters.

New operations:

```
future child          -- spawn a result-producing child, return an integer future id
await id              -- suspend until the future resolves, then resume with its value
send channel value    -- send to an integer channel, waking one waiter or buffering FIFO
recv channel          -- receive from a channel, blocking if the buffer is empty
mailboxSend id msg    -- actor-facing alias for send
mailboxReceive id     -- actor-facing alias for recv
```

Examples:

```
async-future.ssc0          =>  [1, 2, 10, 20, 7, 7]
async-channel.ssc0         =>  [1, 2, 42]
async-channel-buffer.ssc0  =>  [1, 5, 6]
async-mailbox.ssc0         =>  [0, 1, 2, 3, 4]
```

All four examples run on VM, JS, and native Rust. The JS/Rust generation path uses the
same `-Xss512m` jar invocation as the large JSON showcase because the raw ssc0 backend
program plus the richer scheduler is stack-heavy on the Scala VM.

## Examples (`conformance/check.sh`)

```
async-tasks.ssc0   A: log1;yield;log2;yield;log3   B: log10;yield;log20
                   round-robin  =>  1, 10, 2, 20, 3
async-fork.ssc0    P: log1; fork(child); log2; yield; log3   child: log100; yield; log200
                                =>  1, 2, 100, 3, 200
```

## Why this matters

ssc 1.0 ships actors / async runtimes. v2 gets cooperative concurrency as ssc0 libraries
on top of the effect machinery: first the compact `yield`/`fork` scheduler, then K46
futures, channels, and actor mailboxes in the same style. The kernel stays strict,
single-threaded, and effect-free; concurrency is a *library*.

## Next

- A real OS-thread / event-loop backend would be a kernel primitive group (`thread.*`), added
  only if true parallelism (not just concurrency) is needed.
