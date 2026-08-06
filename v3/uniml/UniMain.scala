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
        val text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8")
        print(AstText.render(UniFront.parse(text)))
        0
      catch
        case e: ParseFail => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
        case e: LexError  => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
        case e: java.io.IOException =>
          Console.err.println("ssc3: cannot read '" + path + "': " + e.getClass.getSimpleName); 2
  sys.exit(code)
