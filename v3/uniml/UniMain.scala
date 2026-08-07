package ssc3

// The uniml front's entry point. A SEPARATE artifact from the kernel — `v3/src` has zero
// dependencies and must keep building when UniML is not built at all, which every gate relies on.
//
// IT IS NO LONGER AN `ast` PRINTER. It registers UniML's projection as a front and then runs the
// SAME dispatch the kernel's entry point runs, so `build`, `ir`, `exec` and `emit-v2` all go
// through it — not just `ast`, which took a front argument and was therefore the only command the
// differential could ever reach. That is the swap: `40-front-on-uniml.md` §7's number is met at
// 48 of 48 fixtures and 101 of 101 corpus cases, so the front that answers by DEFAULT is this one
// whenever this artifact is the one being run.
//
// The registration happens BEFORE the dispatch and exactly once. `Front.default` reads it, so
// every command that does not name a front gets UniML here and v3's own front in the kernel jar.
@main def ssc3uniml(args: String*): Unit =
  // Through `Loader`, not `UniFront.parse` alone: a `.ssc` may import other files and the module
  // graph is built from the source TEXT (`50-uniml-projection.md` §6). Registering the per-FILE
  // parser is what keeps that true — `Loader` calls it once per unit in the closure. The first
  // version of this parsed ONE file and every cross-file import vanished with no diagnostic.
  Front.register(Front.uniml, UniFront.parse)
  sys.exit(Cli.run(args.toList))
