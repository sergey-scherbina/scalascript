# 51 — Async / cooperative concurrency (on effects)

> Status: **v1 (2026-06-27)** — `v2/lib/async.ssc0`, built on `lib/effects.ssc0`
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

## Examples (`conformance/check.sh`)

```
async-tasks.ssc0   A: log1;yield;log2;yield;log3   B: log10;yield;log20
                   round-robin  =>  1, 10, 2, 20, 3
async-fork.ssc0    P: log1; fork(child); log2; yield; log3   child: log100; yield; log200
                                =>  1, 2, 100, 3, 200
```

## Why this matters

ssc 1.0 ships actors / async runtimes. v2 gets cooperative concurrency from ~15 lines of
ssc0 on top of the effect library — `await`, futures, channels, and actor mailboxes are all
further handlers/ops in the same style. The kernel stays strict, single-threaded, and
effect-free; concurrency is a *library*.

## Next

- `await` + futures (a `Promise` op resolved by the scheduler); channels; actor mailboxes.
- A real OS-thread / event-loop backend would be a kernel primitive group (`thread.*`), added
  only if true parallelism (not just concurrency) is needed.
