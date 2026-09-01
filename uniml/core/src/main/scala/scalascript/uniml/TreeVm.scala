package scalascript.uniml

final case class FrameSpec(kind: String, role: Option[String] = None)

enum VmInstruction:
  case Open(kind: String, role: Option[String] = None)
  case Close(expectedKind: Option[String] = None, role: Option[String] = None)
  case Emit(role: Option[String] = None)
  case Reframe(
      closeBefore: Vector[String] = Vector.empty,
      open: Vector[FrameSpec] = Vector.empty,
      closeAfter: Vector[String] = Vector.empty,
      role: Option[String] = None,
  )
  case Report(code: String, message: String, severity: Severity = Severity.Error)

final case class VmToken(token: SourceToken, instruction: VmInstruction)

final case class Limits(
    maxDepth: Int = 512,
    maxNodes: Long = 10_000_000L,
    maxTokenCodePoints: Int = 16 * 1024 * 1024,
    maxDiagnostics: Int = 10_000,
)

object Limits:
  val default: Limits = Limits()

/** One open branch on the VM's stack — immutable. The stack is a `Vector` with
  * the top at the end. */
final case class VmFrame(kind: String, role: Option[String], edges: Vector[UniEdge], openingSpan: SourceSpan)

/** The whole VM state, threaded immutably by the driver — `TreeVm` itself has no
  * mutable fields. */
final case class VmState(
    stack: Vector[VmFrame],
    nodeCount: Long,
    lastTokenId: Option[Long],
    diagnosticCount: Int,
    diagnosticLimitReported: Boolean,
    finished: Boolean,
    halted: Boolean,
)

object VmState:
  val initial: VmState = VmState(Vector.empty, 0L, None, 0, diagnosticLimitReported = false, finished = false, halted = false)

/** `step`/`stop`'s working state: the `VmState` fields being edited plus the batch
  * being built. TOP LEVEL because v2 does not lower type decls nested in a class
  * body (the same hoist the markdown dialect documents on `OpenLeaf`). */
private final case class VmWork(
    stack: Vector[VmFrame],
    nodeCount: Long,
    lastTokenId: Option[Long],
    diagCount: Int,
    diagLimitReported: Boolean,
    halted: Boolean,
    roots: Vector[UniNode],
    diags: Vector[Diagnostic],
)

/** `reframeProblem`'s dry-run state: the frame kinds as they would be after each
  * transition, and the first problem found. */
private final case class ReframeCheck(kinds: Vector[String], problem: Option[Diagnostic])

/** The universal tree-building VM as a **pure incremental fold**: `step` folds one
  * `VmToken` into the next `VmState` and any roots it completed; `stop` closes
  * unclosed frames at end of input. No mutable state anywhere — `step`/`stop`
  * thread a [[VmWork]] value through their transitions. */
final case class TreeVm(limits: Limits = Limits.default) extends Processor[VmState, VmToken, UniNode]:

  def start: VmState = VmState.initial

  def step(state: VmState, input: VmToken): Stepped[VmState, UniNode] =
    if state.finished then Stepped(state, ProcessBatch(Vector.empty, Vector(TreeVm.finishedDiagnostic)))
    else if state.halted then Stepped(state, ProcessBatch.empty)
    else
      val w0 = VmWork(state.stack, state.nodeCount, state.lastTokenId, state.diagnosticCount,
        state.diagnosticLimitReported, state.halted, Vector.empty, Vector.empty)

      val done = preflight(state.stack, state.nodeCount, input) match
        case Some(diagnostic) =>
          val h = if diagnostic.severity == Severity.Fatal then w0.copy(halted = true) else w0
          recordWork(h, diagnostic, haltOnLimit = true)
        case None =>
          val validated = TreeVm.validateToken(state.lastTokenId, input.token)
            .foldLeft(w0)((w, d) => recordWork(w, d, haltOnLimit = true))
          val w1 = validated.copy(lastTokenId = Some(input.token.id))
          input.instruction match
            case VmInstruction.Open(kind, role) =>
              w1.copy(
                nodeCount = w1.nodeCount + 2L,
                stack = w1.stack :+ VmFrame(kind, role, Vector(UniEdge(None, UniNode.Token(input.token))), input.token.span))

            case VmInstruction.Emit(role) =>
              attachToken(w1.copy(nodeCount = w1.nodeCount + 1L), role, UniNode.Token(input.token))

            case instruction @ VmInstruction.Reframe(closeBefore, open, closeAfter, role) =>
              reframeProblem(w1.stack, instruction) match
                case Some(problem) =>
                  val recorded = recordWork(w1.copy(nodeCount = w1.nodeCount + 1L), problem.copy(span = Some(input.token.span)), haltOnLimit = true)
                  attachToken(recorded, role, UniNode.Token(input.token))
                case None =>
                  val counted = w1.copy(nodeCount = w1.nodeCount + 1L + open.size)
                  val closedBefore = closeBefore.foldLeft(counted)((w, _) => closeFrame(w))
                  val opened = open.foldLeft(closedBefore)((w, spec) =>
                    w.copy(stack = w.stack :+ VmFrame(spec.kind, spec.role, Vector.empty, input.token.span)))
                  val emitted = attachToken(opened, role, UniNode.Token(input.token))
                  closeAfter.foldLeft(emitted)((w, _) => closeFrame(w))

            case VmInstruction.Report(code, message, severity) =>
              val recorded = recordWork(w1.copy(nodeCount = w1.nodeCount + 1L),
                Diagnostic(code, message, severity, Some(input.token.span)), haltOnLimit = true)
              val h = if severity == Severity.Fatal then recorded.copy(halted = true) else recorded
              attachToken(h, None, UniNode.Token(input.token))

            case VmInstruction.Close(expectedKind, role) =>
              val counted = w1.copy(nodeCount = w1.nodeCount + 1L)
              val tokenNode = UniNode.Token(input.token)
              // the field bound into a collection-typed local before the member call — the Rust
              // backend resolves `.isEmpty` on a param or a collection local, but not through a
              // record-typed LOCAL's field (BUGS: rust-backend-member-read-through-local-val)
              val countedStack: Vector[VmFrame] = counted.stack
              if countedStack.isEmpty then
                recordWork(counted, Diagnostic(
                  code = "uniml.vm.orphan-close",
                  message = "close instruction has no open node",
                  severity = Severity.Error,
                  span = Some(input.token.span),
                ), haltOnLimit = true).copy(roots = counted.roots :+ tokenNode)
              else
                val frame = counted.stack.last
                val extended = addTop(counted, UniEdge(role, tokenNode))
                expectedKind match
                  case Some(expected) if expected != frame.kind =>
                    recordWork(extended, Diagnostic(
                      code = "uniml.vm.mismatched-close",
                      message = s"expected to close '$expected' but current node is '${frame.kind}'",
                      severity = Severity.Error,
                      span = Some(input.token.span),
                      details = Vector("expected" -> expected, "actual" -> frame.kind),
                    ), haltOnLimit = true)
                  case _ =>
                    // pop the frame we just extended
                    val closed = extended.stack.last
                    attach(extended.copy(stack = extended.stack.dropRight(1)),
                      TreeVm.buildBranch(closed, Origin.SourceBacked), closed.role)

      Stepped(
        VmState(done.stack, done.nodeCount, done.lastTokenId, done.diagCount, done.diagLimitReported,
          finished = false, halted = done.halted),
        ProcessBatch(done.roots, done.diags),
      )

  def stop(state: VmState): ProcessBatch[UniNode] =
    if state.finished then ProcessBatch(Vector.empty, Vector(TreeVm.finishedDiagnostic))
    else
      val done = drainUnclosed(VmWork(state.stack, state.nodeCount, state.lastTokenId, state.diagnosticCount,
        state.diagnosticLimitReported, state.halted, Vector.empty, Vector.empty))
      ProcessBatch(done.roots, done.diags)

  // ── VmWork transitions. PRIVATE METHODS, not local defs, deliberately: the Rust backend
  // does not resolve a member read on a value whose type comes from a LIFTED LOCAL def (the
  // lifted def's return type never reaches the global table — the same gap `refDefAt`'s
  // comment records in the markdown dialect, filed as
  // `rust-backend-member-read-through-lifted-local-def`). As methods their types are global
  // and `recorded.stack.nonEmpty` lowers. `haltOnLimit`: `step` HALTS the VM when the
  // diagnostic limit trips; `stop` does not (there is nothing left to halt). ──

  private def recordWork(w: VmWork, d: Diagnostic, haltOnLimit: Boolean): VmWork =
    if w.diagCount < limits.maxDiagnostics then
      w.copy(diagCount = w.diagCount + 1, diags = w.diags :+ d)
    else if !w.diagLimitReported then
      w.copy(diagLimitReported = true, halted = w.halted || haltOnLimit, diags = w.diags :+ Diagnostic(
        code = "uniml.limit.diagnostics",
        message = s"diagnostic count exceeds the ${limits.maxDiagnostics} limit",
        severity = Severity.Fatal,
        span = d.span,
      ))
    else w

  private def addTop(w: VmWork, edge: UniEdge): VmWork =
    val top = w.stack.last
    w.copy(stack = w.stack.dropRight(1) :+ VmFrame(top.kind, top.role, top.edges :+ edge, top.openingSpan))

  private def attach(w: VmWork, branch: UniNode.Branch, role: Option[String]): VmWork =
    if w.stack.nonEmpty then addTop(w, UniEdge(role, branch)) else w.copy(roots = w.roots :+ branch)

  private def attachToken(w: VmWork, role: Option[String], tokenNode: UniNode): VmWork =
    if w.stack.nonEmpty then addTop(w, UniEdge(role, tokenNode)) else w.copy(roots = w.roots :+ tokenNode)

  private def closeFrame(w: VmWork): VmWork =
    val frame = w.stack.last
    attach(w.copy(stack = w.stack.dropRight(1)), TreeVm.buildBranch(frame, Origin.SourceBacked), frame.role)

  private def drainUnclosed(w: VmWork): VmWork =
    if w.stack.isEmpty then w
    else
      val frame = w.stack.last
      val recorded = recordWork(w.copy(stack = w.stack.dropRight(1)), Diagnostic(
        code = "uniml.vm.unclosed-node",
        message = s"unclosed '${frame.kind}' node at end of input",
        severity = Severity.Error,
        span = Some(frame.openingSpan),
      ), haltOnLimit = false)
      val branch = TreeVm.buildBranch(frame, Origin.Synthetic(s"unclosed:${frame.kind}"))
      // collection-typed local for the same backend reason as `countedStack` in step
      val recordedStack: Vector[VmFrame] = recorded.stack
      if recordedStack.nonEmpty then
        val top = recordedStack.last
        drainUnclosed(recorded.copy(stack = recorded.stack.dropRight(1) :+
          VmFrame(top.kind, top.role, top.edges :+ UniEdge(frame.role, branch), top.openingSpan)))
      else drainUnclosed(recorded.copy(roots = recorded.roots :+ branch))

  private def preflight(stack: Vector[VmFrame], nodeCount: Long, input: VmToken): Option[Diagnostic] =
    val token = input.token
    if Unicode.codePointCount(token.lexeme) > limits.maxTokenCodePoints then
      Some(Diagnostic(
        code = "uniml.limit.token",
        message = s"token exceeds the ${limits.maxTokenCodePoints} code-point limit",
        severity = Severity.Fatal,
        span = Some(token.span),
      ))
    else
      val requiredNodes: Long =
        input.instruction match
          case _: VmInstruction.Open => 2L
          case r: VmInstruction.Reframe =>
            reframeProblem(stack, r) match
              case None    => 1L + r.open.size
              case Some(_) => 1L
          case _ => 1L
      val peakDepth: Int =
        input.instruction match
          case _: VmInstruction.Open => stack.size + 1
          case r: VmInstruction.Reframe =>
            reframeProblem(stack, r) match
              case None    => stack.size - r.closeBefore.size + r.open.size
              case Some(_) => stack.size
          case _ => stack.size
      if peakDepth > limits.maxDepth then
        Some(Diagnostic(
          code = "uniml.limit.depth",
          message = s"tree depth exceeds the ${limits.maxDepth} frame limit",
          severity = Severity.Fatal,
          span = Some(token.span),
        ))
      else if nodeCount + requiredNodes > limits.maxNodes then
        Some(Diagnostic(
          code = "uniml.limit.nodes",
          message = s"tree exceeds the ${limits.maxNodes} node limit",
          severity = Severity.Fatal,
          span = Some(token.span),
        ))
      else None

  private def reframeProblem(stack: Vector[VmFrame], instruction: VmInstruction.Reframe): Option[Diagnostic] =
    // Field access rather than `val Reframe(...) = instruction` destructuring:
    // a val-pattern binding of an enum case is not portable to ScalaScript v2.
    val closeBefore = instruction.closeBefore
    val open = instruction.open
    val closeAfter = instruction.closeAfter
    if closeBefore.exists(_.isEmpty) || closeAfter.exists(_.isEmpty) || open.exists(_.kind.isEmpty) then
      Some(Diagnostic(
        code = "uniml.vm.invalid-reframe",
        message = "reframe kinds must be non-empty",
        severity = Severity.Error,
        span = None,
      ))
    else
      val afterBefore = closeBefore.foldLeft(ReframeCheck(stack.map(_.kind), None))(reframeClose)
      afterBefore.problem match
        case None =>
          val opened = afterBefore.copy(kinds = open.foldLeft(afterBefore.kinds)((k, spec) => k :+ spec.kind))
          closeAfter.foldLeft(opened)(reframeClose).problem
        case some => some

  // a method for the same backend reason as the VmWork transitions above
  private def reframeClose(st: ReframeCheck, expected: String): ReframeCheck =
    if st.problem.isDefined then st
    else if st.kinds.isEmpty then
      st.copy(problem = Some(Diagnostic(
        code = "uniml.vm.reframe-underflow",
        message = s"reframe cannot close '$expected' because no frame is open",
        severity = Severity.Error,
        span = None,
      )))
    else if st.kinds.last != expected then
      st.copy(problem = Some(Diagnostic(
        code = "uniml.vm.mismatched-reframe",
        message = s"expected to reframe '$expected' but current node is '${st.kinds.last}'",
        severity = Severity.Error,
        span = None,
        details = Vector("expected" -> expected, "actual" -> st.kinds.last),
      )))
    else st.copy(kinds = st.kinds.dropRight(1))

object TreeVm:
  private val finishedDiagnostic = Diagnostic(
    code = "uniml.vm.finished",
    message = "tree VM cannot accept input or finish more than once",
    severity = Severity.Error,
    span = None,
  )

  private def buildBranch(frame: VmFrame, origin: Origin): UniNode.Branch =
    val end = frame.edges.lastOption match
      case Some(UniEdge(_, UniNode.Token(token)))          => token.span.end
      case Some(UniEdge(_, UniNode.Branch(_, _, span, _))) => span.end
      case None                                            => frame.openingSpan.end
    UniNode.Branch(
      kind = frame.kind,
      edges = frame.edges,
      span = SourceSpan(frame.openingSpan.source, frame.openingSpan.start, end),
      origin = origin,
    )

  private def validateToken(lastTokenId: Option[Long], token: SourceToken): Vector[Diagnostic] =
    val monotonic = lastTokenId.filter(previous => token.id <= previous).map(previous => Diagnostic(
      code = "uniml.token.non-monotonic-id",
      message = s"token id ${token.id} must be greater than previous id $previous",
      severity = Severity.Error,
      span = Some(token.span),
    )).toVector
    val spanOrder =
      if token.span.end.offset < token.span.start.offset then
        Vector(Diagnostic(
          code = "uniml.token.invalid-span",
          message = "token span end precedes its start",
          severity = Severity.Error,
          span = Some(token.span),
        ))
      else Vector.empty
    monotonic ++ spanOrder
