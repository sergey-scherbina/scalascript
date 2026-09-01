package scalascript.uniml

final case class ParseResult(
    roots: Vector[UniNode],
    diagnostics: Vector[Diagnostic],
    status: CompletionStatus,
)

object UniML:
  /** The lex fold in flight: the dialect processor's state plus everything it emitted. */
  private final case class LexAcc(state: String, tokens: Vector[VmToken], diagnostics: Vector[Diagnostic])
  /** The VM fold in flight: the `VmState` plus the roots and diagnostics so far. */
  private final case class VmAcc(state: VmState, roots: Vector[UniNode], diagnostics: Vector[Diagnostic])

  /** Drives the two pure processors immutably: thread the source chunks through
    * the dialect processor to get the whole `VmToken` stream, then fold that
    * stream through the `TreeVm`. All state is threaded through the two folds —
    * nothing mutable exists at all. */
  def parse(
      source: SourceInput,
      dialect: DialectAdapter,
      limits: Limits = Limits.default,
  ): ParseResult =
    // 1. dialect: chunks -> VmTokens (dialects buffer the whole source, so the
    //    result is independent of how the input is split into chunks)
    val lexer = dialect.instructions(source)
    val lexed = source.chunks.foldLeft(LexAcc(lexer.start, Vector.empty, Vector.empty)) { (acc, chunk) =>
      val stepped = lexer.step(acc.state, chunk)
      LexAcc(stepped.state, acc.tokens ++ stepped.batch.values, acc.diagnostics ++ stepped.batch.diagnostics)
    }
    val lexFinal = lexer.stop(lexed.state)
    val tokens = lexed.tokens ++ lexFinal.values

    // 2. TreeVm: VmTokens -> UniNode roots
    val vm = TreeVm(limits)
    val folded = tokens.foldLeft(VmAcc(vm.start, Vector.empty, lexed.diagnostics ++ lexFinal.diagnostics)) { (acc, token) =>
      val stepped = vm.step(acc.state, token)
      VmAcc(stepped.state, acc.roots ++ stepped.batch.values, acc.diagnostics ++ stepped.batch.diagnostics)
    }
    val vmFinal = vm.stop(folded.state)
    val roots = folded.roots ++ vmFinal.values
    val diagnostics = folded.diagnostics ++ vmFinal.diagnostics

    val status =
      if diagnostics.exists(_.severity == Severity.Fatal) then CompletionStatus.Halted
      else if diagnostics.exists(_.severity == Severity.Error) then CompletionStatus.Incomplete
      else CompletionStatus.Complete
    ParseResult(roots, diagnostics, status)
