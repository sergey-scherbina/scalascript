package ssc3

// The uniml front's entry point. A SEPARATE artifact from the kernel — `v3/src` has zero
// dependencies and must keep building when UniML is not built at all, which every gate relies on.
//
// It prints the canonical `Ast` and nothing else: the front-diff gate compares that text, and a
// second front exists to be COMPARED before it is trusted to run anything.
@main def ssc3uniml(args: String*): Unit =
  val code =
    if args.length < 1 then
      Console.err.println("usage: ssc3-uniml ast <file.ssc>")
      2
    else
      val path = args(if args.head == "ast" then 1 else 0)
      try
        // Through `Loader`, not `UniFront.parse` alone: a `.ssc` may import other files, and the
        // module graph is built from the source TEXT rather than from the tree
        // (`50-uniml-projection.md` §6). The first version of this parsed ONE file and every
        // cross-file import vanished without a diagnostic — caught by the front differential,
        // which is the only thing that could have caught it.
        print(AstText.render(Loader.merge(Loader.closureWith(path, UniFront.parse))))
        0
      catch
        case e: LoadError => Console.err.println("ssc3: " + e.message); 1
        case e: ParseFail => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
        case e: LexError  => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
        case e: java.io.IOException =>
          Console.err.println("ssc3: cannot read '" + path + "': " + e.getClass.getSimpleName); 2
  sys.exit(code)
