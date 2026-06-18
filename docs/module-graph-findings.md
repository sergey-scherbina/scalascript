# Module-graph grouping — investigation findings (2026-06-18)

Investigation of the `module-graph-grouping` BACKLOG item: the build has **197 `lazy val` module
definitions** in `build.sbt`, ~150 of them thin SPI implementation modules. Question: can the thinnest
families be grouped/consolidated to reduce build-graph + cognitive load *without* collapsing the SPI
boundaries?

## What's there

Thin SPI-impl families (count of `lazy val <fam>*` defs):

| Family        | Modules | What each is |
|---------------|---------|--------------|
| `wallet*`     | 42      | one module per wallet connector / vault SPI impl |
| `payments*`   | 35      | one module per payment processor (Stripe, Adyen, ACH, SEPA, …) + SPI |
| `walletVault*`| 18      | one module per vault backend |
| `blockchain*` | 13      | one module per chain adapter |
| `x402*`       | 13      | one module per x402 facilitator/impl |

Modules are genuinely thin (representative impls are 20–300 LOC). The split is **one module per SPI
implementation** — the same pattern as `runtime/std/*-plugin`.

## Why grouping doesn't help (recommendation: **leave as-is**)

1. **The per-impl module boundary *is* the SPI boundary.** Each impl module owns its own package,
   `META-INF/services` registration, and (potentially) its own published artifact, so a host can depend
   on exactly the processors/chains/vaults it needs and load them independently. Merging N impls into one
   module collapses that: shared package, shared service file, single artifact — you can no longer take
   one processor without the rest. The BACKLOG item's own constraint ("WITHOUT collapsing the SPI
   boundaries") therefore rules out the only consolidation that would actually shrink the module count.

2. **sbt aggregation already covers the ergonomic concern** without merging. A family can be grouped
   under an aggregate `lazy val` (`aggregate(...)`) for `compile`/`test`/`publish` convenience while each
   impl stays its own module/artifact. Where that's wanted it's a one-liner; it does **not** reduce the
   build graph (the impl modules still exist) — it only reduces typing.

3. **The cold-build cost is the price of the modularity, not accidental debt.** 197 modules is a lot, but
   it's a direct consequence of "one module per SPI impl," which is a deliberate, documented design
   choice (payments-reorg 2026-06-17 kept exactly this shape). The fix for slow cold builds is build
   caching / `bloop` / incremental, not collapsing the architecture.

## Conclusion

**Leave as-is.** Grouping the thin families would either (a) collapse the SPI boundaries the design
depends on, or (b) be a no-op on the build graph (aggregation only). There is no consolidation that
satisfies "shrink the graph *and* keep the boundaries." If a specific family is later found to have
**true duplication** (identical code across impls, not just structural similarity), factor the shared
part into a single library module the impls depend on — but that's a targeted refactor, not a
family-wide grouping. The `module-graph-grouping` item is closed as **investigated → no action**.
