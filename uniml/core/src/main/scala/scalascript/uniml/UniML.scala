package scalascript.uniml

final case class ParseResult(
    roots: Vector[UniNode],
    diagnostics: Vector[Diagnostic],
    status: CompletionStatus,
)

object UniML:
  /** Drives the two pure processors immutably: thread the source chunks through
    * the dialect processor to get the whole `VmToken` stream, then fold that
    * stream through the `TreeVm`. All state lives in local `var`s over immutable
    * values — nothing mutable is shared or held in an object. */
  def parse(
      source: SourceInput,
      dialect: DialectAdapter,
      limits: Limits = Limits.default,
  ): ParseResult =
    // 1. dialect: chunks -> VmTokens (dialects buffer the whole source, so the
    //    result is independent of how the input is split into chunks)
    val lexer = dialect.instructions(source)
    var lexState = lexer.start
    var tokens: Vector[VmToken] = Vector.empty
    var diagnostics: Vector[Diagnostic] = Vector.empty
    source.chunks.foreach { chunk =>
      val stepped = lexer.step(lexState, chunk)
      lexState = stepped.state
      tokens = tokens ++ stepped.batch.values
      diagnostics = diagnostics ++ stepped.batch.diagnostics
    }
    val lexFinal = lexer.stop(lexState)
    tokens = tokens ++ lexFinal.values
    diagnostics = diagnostics ++ lexFinal.diagnostics

    // 2. TreeVm: VmTokens -> UniNode roots
    val vm = TreeVm(limits)
    var vmState = vm.start
    var roots: Vector[UniNode] = Vector.empty
    tokens.foreach { token =>
      val stepped = vm.step(vmState, token)
      vmState = stepped.state
      roots = roots ++ stepped.batch.values
      diagnostics = diagnostics ++ stepped.batch.diagnostics
    }
    val vmFinal = vm.stop(vmState)
    roots = roots ++ vmFinal.values
    diagnostics = diagnostics ++ vmFinal.diagnostics

    val status =
      if diagnostics.exists(_.severity == Severity.Fatal) then CompletionStatus.Halted
      else if diagnostics.exists(_.severity == Severity.Error) then CompletionStatus.Incomplete
      else CompletionStatus.Complete
    ParseResult(roots, diagnostics, status)
