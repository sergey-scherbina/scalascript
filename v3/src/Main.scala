package ssc3

// `ssc3` — the SSC IR tool. `SSC3-2` gives it three verbs over the IR itself; `run` arrives with
// the executor (`SSC3-3b`) and the v2 bridge (`SSC3-3`).
//
//   ssc3 check <file.ssir>     verify, and say why not
//   ssc3 fmt   <file.ssir>     read and re-emit in the canonical form
//   ssc3 selftest              the gates for SSC3-2

object Sample:

  /** A module exercising EVERY instruction, used by both gates.
    *
    * One sample rather than a random generator: what the round-trip needs is total opcode coverage,
    * and coverage asserted against a closed list is a fact where a generator's is a probability. */
  val consts: List[Lit] = List(
    Lit.LUnit,                                        // 0
    Lit.LBool(true),                                  // 1
    Lit.LInt(2L),                                     // 2
    Lit.LBig("170141183460469231731687303715884105727"), // 3
    Lit.LFloat(1.5),                                  // 4
    Lit.LStr("hi\n\"quoted\"\ttab\\slash"),           // 5 — every escape the writer emits
    Lit.LBytes("0a1b"),                               // 6
    Lit.LInt(1L),                                     // 7
  )

  /** `fib` from the spec's worked example (v3/specs/10-ssc-ir.md §7). */
  val fib: Func = Func("fib", 1, 6, List(
    Instr.Const(1, 2),
    Instr.Bin(BinOp.Lt, NumKind.I64, 2, 0, 1),
    Instr.If(2, List(Instr.Ret(0)), Nil),
    Instr.Const(1, 7),
    Instr.Bin(BinOp.Sub, NumKind.I64, 3, 0, 1),
    Instr.Call(4, 0, List(3)),
    Instr.Const(1, 2),
    Instr.Bin(BinOp.Sub, NumKind.I64, 5, 0, 1),
    Instr.Call(5, 0, List(5)),
    Instr.Bin(BinOp.Add, NumKind.I64, 3, 4, 5),
    Instr.Ret(3),
  ))

  /** Everything `fib` does not reach. Not a program anyone would write — a coverage vehicle, and
    * labelled as one so nobody mistakes it for an example. */
  val kitchen: Func = Func("kitchen", 0, 8, List(
    Instr.Const(0, 2),
    Instr.Move(1, 0),
    Instr.Un(UnOp.Neg, NumKind.I64, 2, 1),
    Instr.Bin(BinOp.Add, NumKind.Dyn, 3, 1, 2),
    Instr.Block(List(Instr.Loop(List(Instr.BrIf(3, 1), Instr.Br(0))))),
    Instr.NewArr(4, 0),
    Instr.ArrSet(4, 1, 2),
    Instr.ArrLen(5, 4),
    Instr.ArrGet(6, 4, 1),
    Instr.GlobGet(7, 0),
    Instr.GlobSet(0, 7),
    Instr.MkData(4, 0, List(1, 2)),
    Instr.Field(5, 4, 0, 1),
    Instr.Tag(6, 4),
    Instr.Switch(6, List(SwitchArm(0, List(Instr.Const(7, 0)))), List(Instr.Const(7, 1))),
    Instr.MkClos(5, 0, List(1)),
    Instr.CallV(6, 5, List(1)),
    Instr.Call(6, 0, List(1)),
    Instr.Perform(6, 0, List(1)),
    Instr.Handle(6, List(Instr.Const(7, 0)), List(HandlerArm(0, List(1), 7, List(Instr.Const(7, 1))))),
    Instr.Resume(6, 7, 1),
    Instr.Prim(6, 0, List(1)),
    Instr.Invoke(6, 5, 4, List(1)),
    Instr.Try(6, List(Instr.Const(6, 0)), 7, List(Instr.Const(6, 1))),
    Instr.TailCall(0, List(1)),
  ))

  val module: Module = Module(
    consts = consts,
    types = List(TypeDef("Box", 2)),
    globals = List(GlobalDef("counter")),
    prims = List("println"),
    funcs = List(fib, kitchen),
    entry = 0,
  )

  /** The closed opcode vocabulary. A new `Instr` case that nobody adds here makes `coverage` fail,
    * which is the point: an instruction the round-trip has never seen is an instruction whose
    * writer and reader have never been compared. */
  val opcodes: List[String] = List(
    "const", "move", "un", "bin",
    "block", "loop", "if", "br", "brif",
    "call", "callv", "mkclos", "tailcall", "ret",
    "mkdata", "field", "tag", "switch",
    "newarr", "arrget", "arrset", "arrlen", "globget", "globset",
    "perform", "handle", "resume", "prim", "invoke", "try",
  )

object SelfTest:

  private var failures: Int = 0

  private def ok(name: String): Unit = println("  ok   " + name)

  private def bad(name: String, detail: String): Unit =
    failures += 1
    println("  FAIL " + name + " — " + detail)

  private def check(name: String, cond: Boolean, detail: String): Unit =
    if cond then ok(name) else bad(name, detail)

  /** Rule-by-rule planted defects. Each takes the VALID sample and breaks exactly one thing, so a
    * refusal is attributable: the same module verifies clean without the plant. A verifier that has
    * never refused anything is untested, and one that refuses everything is untested too. */
  private def planted: List[(String, Module, String)] =
    val m = Sample.module
    val f = Sample.fib
    def withFib(body: List[Instr], nregs: Int): Module =
      m.copy(funcs = List(f.copy(nregs = nregs, body = body), Sample.kitchen))
    List(
      (
        "rule 1 · register outside the frame",
        withFib(List(Instr.Const(99, 2), Instr.Ret(0)), 6),
        "outside the frame",
      ),
      (
        "rule 2 · br leaves more regions than enclose it",
        withFib(List(Instr.Br(0)), 6),
        "leaves more regions",
      ),
      (
        "rule 3 · constant-pool index out of range",
        withFib(List(Instr.Const(1, 99), Instr.Ret(0)), 6),
        "out of range",
      ),
      (
        "rule 3 · call arity disagrees with the callee",
        withFib(List(Instr.Call(1, 0, List(0, 0)), Instr.Ret(0)), 6),
        "argument(s), it takes",
      ),
      (
        "rule 4 · field index beyond the type's field count",
        withFib(List(Instr.Field(1, 0, 0, 5), Instr.Ret(0)), 6),
        "which has 2 field(s)",
      ),
      (
        "rule 5 · body does not end in a terminator",
        withFib(List(Instr.Const(1, 2)), 6),
        "does not end in a terminator",
      ),
      (
        "module · entry is not a function",
        m.copy(entry = 7),
        "is not a function",
      ),
    )

  def run(): Int =
    failures = 0
    val m = Sample.module
    val text = Text.write(m)

    println("SSC3-2 — IR core: round-trip, coverage, verifier")

    // The base module must be VALID. Without this the planted-defect suite proves nothing: every
    // plant would "fail" for whatever was already wrong.
    Verify.module(m) match
      case None    => ok("the sample module verifies clean")
      case Some(e) => bad("the sample module verifies clean", e.render)

    // Structural, not textual. The first version grepped the rendered text for "(block " and broke
    // the moment the layout put a newline there instead of a space — a coverage check that reports
    // a FORMATTING change as missing coverage is worse than none, because the real signal is then
    // indistinguishable from noise.
    val used = m.funcs.flatMap(f => Instr.flatten(f.body)).map(Text.opcode).distinct
    val missing = Sample.opcodes.filter(op => !used.contains(op))
    val unlisted = used.filter(op => !Sample.opcodes.contains(op))
    check("every opcode appears in the sample", missing.isEmpty, "missing: " + missing.mkString(", "))
    check("no opcode is emitted outside the closed vocabulary", unlisted.isEmpty, "unlisted: " + unlisted.mkString(", "))

    // Round-trip, both directions. `read(write(m)) == m` alone would pass for a writer that lost
    // information the reader also invents back; comparing the TEXT closes that.
    val back = Text.read(text)
    check("read(write(m)) == m", back == m, "structural mismatch after one round-trip")
    check("write(read(t)) == t", Text.write(back) == text, "text is not a fixpoint")

    // A second round-trip: a formatter that is not idempotent makes every diff of two .ssir files
    // noise, and the text form is canonical for equality precisely so diffs mean something.
    check("write is idempotent", Text.write(Text.read(Text.write(back))) == text, "second pass differs")

    planted.foreach { (name, bad0, expect) =>
      Verify.module(bad0) match
        case None => bad(name, "ACCEPTED — the verifier cannot see this defect")
        case Some(e) =>
          if e.render.contains(expect) && e.path.nonEmpty then ok(name + "  [" + e.render + "]")
          else bad(name, "refused, but the message does not name it: " + e.render)
    }

    if failures == 0 then println("SSC3-2 self-test: OK") else println("SSC3-2 self-test: " + failures + " FAILED")
    failures

/** The command dispatch, as a FUNCTION.
  *
  * It was the body of `@main def ssc3` and had to come out, because there are two entry points
  * now: the kernel's, and `v3/uniml`'s, which registers the UniML front and then wants exactly
  * this. Duplicating a 130-line dispatch so that the second front could run more than `ast` is how
  * the two would have drifted — and drift between two things that are supposed to be the same is
  * the failure this whole front differential exists to catch. */
/** Joining a path to a run-time message.
  *
  * A message that ALREADY names a source position — `136:18: …` — is joined with no space, so the
  * line reads `file.ssc:136:18: …` and points somewhere. Everything else keeps the space.
  *
  * It is not cosmetic. `corpus-report.sh` classifies an unpositioned failure as CRASH and a
  * positioned one as UNSUPPORTED, and its rule is right: a refusal a reader can act on names a
  * place. A run-time failure that KNOWS its position and prints it in a form nothing can read is
  * the worst of both. Measured: the same failure counted 13 CRASH without this and 0 with it. */
object Diag:
  def at(path: String, msg: String): String =
    val i = msg.indexOf(": ")
    val head = if i > 0 then msg.substring(0, i) else ""
    val positioned = head.nonEmpty && head.count(_ == ':') == 1 &&
      head.split(':').forall(part => part.nonEmpty && part.forall(c => c >= '0' && c <= '9'))
    if positioned then path + ":" + msg else path + ": " + msg

object Cli:

  /** ONE decision site for "was this module specialized?", and it is here rather than in `Exec`.
    *
    * SSC3-J1b. `Specialize` proves the `kind` field and `Exec.step` now reads it, so every path that
    * hands a module to the executor has to agree about whether the pass ran — two subcommands
    * answering differently is the shape this repository keeps paying for.
    *
    * A CLI FLAG AND NOT AN ENV VAR, deliberately. No env var switches EXECUTOR BEHAVIOUR, and it
    * must run on ScalaScript 2 as well as on
    * scalac — invariant I-2. Putting an ambient toggle in `Exec` would add a host dependency to the
    * most portability-sensitive file in the module to save typing a flag. `Main` is the driver: it
    * already parses arguments and reads files, so the choice belongs to it.
    *
    * This used to read "the v3 kernel reads no environment anywhere (`grep -rn 'sys.env|getenv'
    * v3/src` is empty)". That grep now returns five lines and the sentence was quoted onward as
    * fact, so it is narrowed to what is actually true and checkable: `Loader` reads `SSC_STD` and
    * `SSC3_PRELUDE` to LOCATE files, and `Exec` reads `sys.env` only to implement the `env()`
    * INTRINSIC — a language feature, not a switch. A comment that states a grep result dates
    * itself; state the invariant instead.
    *
    * `--no-specialize` is what makes the A/B possible at all: it is the OFF arm of every measurement
    * of this tier, and the two arms have to be the same binary.
    */
  private def prepared(m: Module, args: List[String]): Module =
    // SSC3-J2. The lane is chosen HERE, next to the other one, so "which executor ran this" has one
    // answer per invocation and both switches are visible in the same three lines. `Exec` reads the
    // flag when it builds its tables, so it must be set before the module is handed over.
    Exec.useClosures(args.contains("--closures"))
    // SSC3-J4c. The type-tag cache is on unless asked otherwise; `--no-tag-cache` restores the
    // linear scan of the module's type table so the two can be A/B'd in one binary.
    Exec.useTagCache(!args.contains("--no-tag-cache"))
    // SSC3-J4d. `--no-prepare-cache` restores the per-call length guard, so the identity fast path
    // has an OFF arm in the same binary.
    Exec.usePrepareCache(!args.contains("--no-prepare-cache"))
    // SSC3-J5. `--no-fast-apply` restores the `cap :+ x` list, so the A/B lives in one binary.
    Exec.useFastApply(!args.contains("--no-fast-apply"))
    // SSC3-3c. The BRIDGE's temporary fold, set here with the executor's switches rather than in
    // `build`'s own arm, because this is the one place that answers "what is this invocation
    // running". It changes nothing on the executor lane — `Exec` never sees it — so `--no-fold-temps`
    // is a no-op for `exec` and the OFF arm for `run --bridge`.
    BridgeV2.useFoldTemps(!args.contains("--no-fold-temps"))
    // SSC3-3c. `--no-structured-loops` restores the CTL-flag form of every `while`, which is the OFF
    // arm the structured one is measured against — and the arm that says which of the two rewrites
    // a divergence belongs to, since both touch the same emitted text.
    BridgeV2.useStructuredLoops(!args.contains("--no-structured-loops"))
    // SSC3-3c-rest stage 1. `--no-invert-cond` restores the `(x == false)` negation the structured
    // `while` would otherwise emit, which is the OFF arm the comparison inversion is measured
    // against. (There is no `--no-direct-ops`: the direct `i.*` prims were measured SLOWER than
    // `__arith__` on two independent instruments and were not kept — see `BridgeV2` §operators.)
    BridgeV2.useInvertCond(!args.contains("--no-invert-cond"))
    // SSC3-3c-rest stage 2. `--no-cell-frame` puts the register file back in one mutable array,
    // which is V-0's representation and the OFF arm the cells are measured against.
    BridgeV2.useCellFrame(!args.contains("--no-cell-frame"))
    // SSC3-J4. `--no-fuse-cmpbr` restores the plain instruction-at-a-time walk, which is the OFF
    // arm the compare-and-branch fusion is measured against — in one binary, so a ratio cannot be a
    // difference between two builds. `--fuse-cmpbr` is accepted as the explicit ON arm, which the
    // harness needs while a pass is parked OFF.
    Exec.useFuseCmpBr(!args.contains("--no-fuse-cmpbr"))
    // SSC3-J1d. `Optimize` runs AFTER `Specialize`, because copy propagation folds a `Move` into the
    // instruction before it and that instruction's `kind` is what the specializer just proved — fold
    // first and the proof would be attached to an instruction that no longer exists.
    // `--no-optimize` is the OFF arm of its measurement, the same shape as `--no-specialize`.
    val specialized = if args.contains("--no-specialize") then m else Specialize.module(m)
    // SSC3-J4a adds `--no-hoist`: copy propagation still runs, only the loop-invariant `Const` lift
    // is off. `--no-optimize` turns off BOTH, so measuring the lift against it would charge one pass
    // for the other's effect — which is what the OFF arm of an A/B exists to prevent.
    if args.contains("--no-optimize") then specialized
    else Optimize.module(specialized, !args.contains("--no-hoist"), !args.contains("--no-invert"))

  /** Install the host-function fleet, IF ONE IS ON THE CLASSPATH, before anything is lowered.
    *
    * BY NAME AND NOT BY TYPE, which is what keeps invariant I-1 intact: a string is not a
    * dependency. The kernel must build with the JDK and repository files alone, and the adapter it
    * would otherwise import lives in `v3/plugins` and references v2's runtime. This is the same
    * trick `ServiceLoader` plays, spelled out.
    *
    * HERE AND NOT IN A WRAPPER `@main`, and that was measured rather than assumed. There are TWO
    * entry points — `ssc3` in this file and `ssc3uniml` in `v3/uniml` — and the driver picks the
    * second whenever the uniml front is built, which is almost always. A wrapper main that
    * installed the fleet was simply discarded by the branch that runs after it: everything
    * compiled, the fleet loaded nowhere, and `mkdirs` stayed refused. `Cli.run` is where the two
    * entry points already meet.
    *
    * BEFORE LOWERING, not at the first call: `Lower` reads `Plugins.registered` to decide whether
    * an `extern def` becomes a `Prim` or a positioned refusal, so a fleet that arrives later
    * arrives after the program has been refused.
    *
    * A MISSING FLEET IS THE NORMAL CASE and is silent. `ClassNotFoundException` here means the
    * providers were not built, which is exactly how `v3/uniml` is optional too. */
  private def installFleet(): Unit =
    try
      val c = Class.forName("ssc3.plugins.V2Fleet$")
      val m = c.getMethod("install")
      m.invoke(c.getField("MODULE$").get(null))
    catch case _: Throwable => ()

  def run(args: List[String]): Int =
    installFleet()
    try
      if args.isEmpty then
        println("usage: ssc3 build|ir|exec|ast <f.ssc> | check|fmt|emit-v2|exec <f.ssir> | sample | selftest | front")
        2
      else
        args.head match
          case "selftest" => SelfTest.run()
          // Prints the coverage module in canonical form. `v3/tests/sample.ssir` is this output,
          // frozen in git. It is a CHANGE DETECTOR, not a correctness oracle — the same code writes
          // and compares it, so it proves nothing about whether the format is right. What it does is
          // make a change to the canonical form show up as a reviewable diff instead of silently
          // moving under every gate that compares .ssir.
          // The whole front, end to end: .ssc -> tokens -> AST -> SSC IR -> verify -> v2 Core IR.
          // Emitting rather than running, because spawning v2 is a host call and the kernel's only
          // door to the host is `Prim` (invariant I-1). `bin/ssc3` does the piping.
          // The canonical `Ast`, as text. The apparatus the UniML swap is decided by: two fronts
          // are compared by diffing THIS, not by comparing what a program printed after passing
          // through the lowering, the verifier and a backend.
          case "ast" if args.length >= 2 =>
            val path = args(1)
            // `Front.default`, not `Front.v3`. Hard-coding v3 here made `ast` the ONE command
            // that ignored the registered front: `exec` ran on UniML while `ast` printed v3's tree
            // in the same artifact, so the text the differential compares and the tree that
            // actually runs could have drifted apart without a single gate noticing.
            val front = if args.length >= 3 then args(2) else Front.default
            try
              print(AstText.render(Loader.merge(Loader.closure(path, front))))
              0
            catch
              case e: LoadError => Console.err.println("ssc3: " + e.message); 1
              case e: LexError  => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
              case e: ParseFail => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1

          case "build" if args.length >= 2 =>
            val path = args(1)
            val src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8")
            try
              val m = Driver.moduleOf(path, src)
              Verify.module(m) match
                case Some(e) =>
                  // A lowering bug reaching the backend is exactly what I-4 exists to stop, and the
                  // message says WHERE rather than only that it happened.
                  Console.err.println("ssc3: " + path + ": lowering produced invalid IR: " + e.render)
                  1
                case None =>
                  // SSC3-3c. The bridge is handed the OPTIMIZED module, exactly as `exec` is. It
                  // used to receive `Driver.moduleOf` raw, so the lane that already pays most per
                  // instruction was the one lane running un-propagated `Move`s, constants reloaded
                  // every iteration and un-fused compares. `prepared` is one decision site for what
                  // a lane runs, and the flags (`--no-optimize`, `--no-hoist`, …) reach it here too.
                  println(BridgeV2.program(prepared(m, args)))
                  0
            catch
              case e: LoadError    => Console.err.println("ssc3: " + e.message); 1
              case e: LexError     => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
              case e: ParseFail    => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
              case e: LowerFail    => Console.err.println(Driver.render(e, path)); 1
              case e: BridgeV2.Unsupported =>
                Console.err.println("ssc3: " + path + ": " + e.getMessage); 1
          // The IR the front produced, in canonical form — for reading and for diffing.
          case "ir" if args.length >= 2 =>
            val path = args(1)
            val src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8")
            try
              print(Text.write(Driver.moduleOf(path, src)))
              0
            catch
              case e: LoadError => Console.err.println("ssc3: " + e.message); 1
              case e: LexError  => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
              case e: ParseFail => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
              case e: LowerFail => Console.err.println(Driver.render(e, path)); 1
          // Runs on v3's OWN executor — no v2, no bridge. This is the lane where TailCall is a real
          // tail call and a frame is data.
          case "exec" if args.length >= 2 =>
            // The first NON-FLAG argument, not `args(1)`. `exec` is the one subcommand that takes a
            // flag (`--no-specialize`, the OFF arm of every J1 measurement), and positional `args(1)`
            // read the flag AS THE PATH: `ssc3 exec --no-specialize f.ssc` died with
            // `cannot read '--no-specialize': NoSuchFileException`. Found by `jit-gate.sh --identity`
            // on its first run, which is the gate catching its own author's change.
            val path = args.tail.find(a => !a.startsWith("--")).getOrElse(args(1))
            val src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8")
            try
              val m = prepared(
                if path.endsWith(".ssir") then Text.read(src)
                else Driver.moduleOf(path, src),
                args)
              Exec.run(m)
              0
            catch
              case e: LoadError => Console.err.println("ssc3: " + e.message); 1
              case e: LexError  => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
              case e: ParseFail => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
              case e: LowerFail => Console.err.println(Driver.render(e, path)); 1
              case e: ParseError => Console.err.println("ssc3: " + path + ": " + e.message); 1
              case e: ExecError => Console.err.println("ssc3: " + Diag.at(path, e.getMessage)); 1
          // Times `workload()` so v3 can appear in bench/run.sc beside v1 and v2.
          //
          // WHY THE LOOP IS HERE AND NOT IN THE MEASURED LANGUAGE. Every other backend is timed by a
          // wrapper written in ScalaScript itself, calling `System.nanoTime()` as an ordinary call.
          // v3 has no clock: its prim table is `io.println` and collection operations, and the
          // charter says the kernel's only door to the host is `Prim` (I-1). Adding a clock prim to
          // put the timer inside the language would widen the kernel's host surface to make a
          // measurement, which is the wrong trade. The driver is already host-side, so the loop
          // lives here.
          //
          // WHAT IS AND IS NOT COMPARABLE. Compilation is excluded on both sides: the module is
          // lowered and verified once, before the clock starts, exactly as `ssc bench --machine`
          // excludes it. The adaptive window is the same rule (double the reps until the measured
          // span reaches 100 ms) for the same reason — nanoTime granularity, and on this lane the
          // interpreter's own warm-up. What differs is that the rep counter is a host `while` rather
          // than a loop the measured language executes, so v3 is NOT charged for its own loop
          // overhead the way an in-language wrapper charges the others. On an AST/IR walker that
          // difference is one host increment per iteration against a whole `workload()` call; it
          // flatters v3 slightly on the very cheapest rows and is stated here rather than hidden.
          //
          // `Exec.run` first, and it is not optional: it initialises the globals array and runs the
          // module entry. Calling `workload` against uninitialised globals would either fault or
          // measure a program in a state no real run ever has.
          case "bench" if args.length >= 2 =>
            val path = args.findLast(a => !a.startsWith("--") && a != "bench").getOrElse(args(1))
            def flag(name: String, dflt: Int): Int =
              val i = args.indexOf("--" + name)
              if i >= 0 && i + 1 < args.length then args(i + 1).toIntOption.getOrElse(dflt) else dflt
            val warmup  = flag("warmup", 3)
            val reps0   = flag("reps", 1)
            val src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8")
            try
              // Specialized OUTSIDE the timed region, like the compile: `bench` measures execution,
              // and charging one lane for a pass the other does not run would make the A/B a
              // comparison of compilers rather than of executors.
              val m = prepared(
                if path.endsWith(".ssir") then Text.read(src)
                else Driver.moduleOf(path, src),
                args)
              // ZERO OR ONE PARAMETER. 17 of the 36 corpus files declare `def workload(seed: Long)`
              // (one takes an `Int`): the seed is opaque varying input, there so a pure zero-input
              // body cannot be folded to a constant. Accepting only arity 0 blanked NINE rows that
              // v3 compiles and runs perfectly — array-update, bool-predicate, instance-field,
              // lambda-call, literal-match, option-chain, tuple-monoid and both var-expr-init rows.
              //
              // Read from the table that is indistinguishable from "v3 cannot run this program",
              // which is the exact failure `tests/e2e/bench-seed-type-gate.sh` was written for after
              // the shared wrapper did it twice. This was the third time, on a new lane.
              //
              // v3 needs no declared-type branch the way the source wrapper does: `Value.VInt` holds
              // a Long and the executor is dynamically typed at the Value level, so one seed shape
              // serves both `Int` and `Long` workloads.
              val idx = m.funcs.indexWhere(f => f.name == "workload" && (f.nparams == 0 || f.nparams == 1))
              if idx < 0 then
                // Named rather than silent: a corpus file with no `workload` of arity 0 or 1 is a
                // fixture problem, and a blank cell would read as "v3 cannot do this program".
                Console.err.println(
                  "ssc3: " + path + ": no `workload` of arity 0 or 1 to bench")
                1
              else
                val seeded = m.funcs(idx).nparams == 1
                // Monotonic, exactly as the shared wrapper's `_ssc_seed`: enough varying input to
                // keep the work honest without making the value depend on the measurement.
                var seed = 1L
                def once(): Value =
                  val v = Exec.callFunc(m, idx, if seeded then List(Value.VInt(seed)) else Nil)
                  seed = seed + 1
                  v
                Exec.run(m)
                var w = 0
                while w < warmup do
                  once()
                  w = w + 1
                var reps = if reps0 < 1 then 1 else reps0
                var ns   = 0L
                var sink: Value = Value.VUnit
                while ns < 100000000L && reps <= 268435456 do
                  val t0 = System.nanoTime()
                  var r  = 0
                  while r < reps do
                    sink = once()
                    r = r + 1
                  ns = System.nanoTime() - t0
                  if ns < 100000000L then reps = reps * 2
                println("BENCH_MS: " + (ns.toDouble / (reps.toDouble * 1000000.0)))
                // Printed for the same reason the other lanes print it: a result nothing consumes is
                // a result an optimiser is allowed to delete, and the sink is the evidence it did not.
                println("BENCH_SINK: " + sink)
                0
            catch
              case e: LoadError  => Console.err.println("ssc3: " + e.message); 1
              case e: LexError   => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
              case e: ParseFail  => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
              case e: LowerFail  => Console.err.println(Driver.render(e, path)); 1
              case e: ParseError => Console.err.println("ssc3: " + path + ": " + e.message); 1
              case e: ExecError  => Console.err.println("ssc3: " + Diag.at(path, e.getMessage)); 1
          // WHICH FRONT ANSWERED. Not decoration: the swap makes the front depend on the working
          // tree — the uniml artifact registers it, the kernel jar cannot — and v3's own front and
          // UniML's agree on every fixture and every corpus case, so their OUTPUT is identical by
          // construction and can never distinguish them. A gate that cannot see which of two
          // states it is in is not a gate; this repository has shipped that mistake and written it
          // down. `front-report-gate.sh` reads these two lines.
          // Which functions can PERFORM — step 1 of SSC3-7b, and observable so the step can be
          // checked on its own rather than only when CPS lands on top of it.
          // The split module, printed — SSC3-7b step 2, observable on its own rather than only
          // when the executor half lands on top of it. `check` on this output is the real test:
          // a split that does not verify is a split that moved a register and did not say so.
          case "cps" if args.length >= 2 =>
            val path = args(1)
            val src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8")
            try
              val m0 =
                if path.endsWith(".ssir") then Text.read(src)
                else Driver.moduleOf(path, src)
              print(Text.write(Cps(m0)))
              0
            catch
              case e: LoadError  => Console.err.println("ssc3: " + e.message); 1
              case e: LexError   => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
              case e: ParseFail  => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
              case e: LowerFail  => Console.err.println(Driver.render(e, path)); 1
              case e: ParseError => Console.err.println("ssc3: " + path + ": " + e.message); 1
          case "performs" if args.length >= 2 =>
            val path = args(1)
            val src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8")
            try
              val m =
                if path.endsWith(".ssir") then Text.read(src)
                else Driver.moduleOf(path, src)
              val ps = Perform.performing(m)
              // Printed in MODULE order, not set order: a set's iteration order is not stable across
              // runs and a gate that diffs this output would flap for no reason.
              m.funcs.filter(f => ps.contains(f.name)).foreach(f => println(f.name))
              0
            catch
              case e: LoadError  => Console.err.println("ssc3: " + e.message); 1
              case e: LexError   => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
              case e: ParseFail  => Console.err.println("ssc3: " + path + ":" + e.getMessage); 1
              case e: LowerFail  => Console.err.println(Driver.render(e, path)); 1
              case e: ParseError => Console.err.println("ssc3: " + path + ": " + e.message); 1
          case "front" =>
            println("front: " + Front.default)
            println("available: " + Front.available.mkString(" "))
            0
          case "sample" =>
            print(Text.write(Sample.module))
            0
          // Emits v2 Core IR text. Piping it into v2 is the GATE's job, not the kernel's: spawning a
          // process is a host call, and the kernel's only door to the host is `Prim` (invariant I-1).
          case "emit-v2" if args.length >= 2 =>
            val path = args(1)
            val src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8")
            try
              val m = Text.read(src)
              Verify.module(m) match
                case Some(e) =>
                  Console.err.println("ssc3: " + path + ": " + e.render)
                  1
                case None =>
                  println(BridgeV2.program(m))
                  0
            catch
              case e: ParseError =>
                Console.err.println("ssc3: " + path + ": " + e.message); 1
              case e: BridgeV2.Unsupported =>
                Console.err.println("ssc3: " + path + ": " + e.getMessage); 1
          case "check" | "fmt" if args.length >= 2 =>
            val path = args(1)
            val src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8")
            try
              val m = Text.read(src)
              // Loading ALWAYS verifies — invariant I-4. `fmt` is not an exception: re-emitting a
              // module nobody has validated would launder an invalid one into a tidy-looking file.
              Verify.module(m) match
                case Some(e) =>
                  Console.err.println("ssc3: " + path + ": " + e.render)
                  1
                case None =>
                  if args.head == "fmt" then print(Text.write(m)) else println("ok: " + path)
                  0
            catch
              case e: ParseError =>
                Console.err.println("ssc3: " + path + ": " + e.message)
                1
          case other =>
            Console.err.println("ssc3: unknown command '" + other + "'")
            2
    catch
      // EVERY command's first act is reading its input, and a missing file arrived as a raw
      // NoSuchFileException stack trace — the one diagnostic in the whole driver that was not
      // `ssc3: …`. It also mattered beyond tidiness: corpus-report.sh classifies a stack trace as
      // CRASH rather than as a clean refusal, so an unreadable path would have been reported as a
      // v3 defect. One catch here, because every command reads the same way.
      case e: java.io.IOException =>
        val what = if args.length >= 2 then args(1) else "the input"
        Console.err.println("ssc3: cannot read '" + what + "': " + e.getClass.getSimpleName)
        2

@main def ssc3(args: String*): Unit = sys.exit(Cli.run(args.toList))
