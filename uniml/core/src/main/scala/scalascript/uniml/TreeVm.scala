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

/** The universal tree-building VM as a **pure incremental fold**: `step` folds one
  * `VmToken` into the next `VmState` and any roots it completed; `stop` closes
  * unclosed frames at end of input. No mutable object state — inside `step`/`stop`
  * a local imperative shell mutates only local `var`s over immutable values. */
final case class TreeVm(limits: Limits = Limits.default) extends Processor[VmState, VmToken, UniNode]:

  def start: VmState = VmState.initial

  def step(state: VmState, input: VmToken): Stepped[VmState, UniNode] =
    if state.finished then Stepped(state, ProcessBatch(Vector.empty, Vector(TreeVm.finishedDiagnostic)))
    else if state.halted then Stepped(state, ProcessBatch.empty)
    else
      // local imperative shell over the immutable state
      var stack = state.stack
      var nodeCount = state.nodeCount
      var lastTokenId = state.lastTokenId
      var diagCount = state.diagnosticCount
      var diagLimitReported = state.diagnosticLimitReported
      var halted = state.halted
      var roots: Vector[UniNode] = Vector.empty
      var diags: Vector[Diagnostic] = Vector.empty

      def record(d: Diagnostic): Unit =
        if diagCount < limits.maxDiagnostics then
          diagCount += 1
          diags = diags :+ d
        else if !diagLimitReported then
          diagLimitReported = true
          halted = true
          diags = diags :+ Diagnostic(
            code = "uniml.limit.diagnostics",
            message = s"diagnostic count exceeds the ${limits.maxDiagnostics} limit",
            severity = Severity.Fatal,
            span = d.span,
          )

      def addTop(edge: UniEdge): Unit =
        val top = stack.last
        stack = stack.dropRight(1) :+ VmFrame(top.kind, top.role, top.edges :+ edge, top.openingSpan)

      def attach(branch: UniNode.Branch, role: Option[String]): Unit =
        if stack.nonEmpty then addTop(UniEdge(role, branch)) else roots = roots :+ branch

      def closeFrame(): Unit =
        val frame = stack.last
        stack = stack.dropRight(1)
        attach(TreeVm.buildBranch(frame, Origin.SourceBacked), frame.role)

      preflight(stack, nodeCount, input) match
        case Some(diagnostic) =>
          if diagnostic.severity == Severity.Fatal then halted = true
          record(diagnostic)
        case None =>
          TreeVm.validateToken(lastTokenId, input.token).foreach(record)
          lastTokenId = Some(input.token.id)
          input.instruction match
            case VmInstruction.Open(kind, role) =>
              nodeCount += 2L
              stack = stack :+ VmFrame(kind, role, Vector(UniEdge(None, UniNode.Token(input.token))), input.token.span)

            case VmInstruction.Emit(role) =>
              nodeCount += 1L
              val tokenNode = UniNode.Token(input.token)
              if stack.nonEmpty then addTop(UniEdge(role, tokenNode)) else roots = roots :+ tokenNode

            case instruction @ VmInstruction.Reframe(closeBefore, open, closeAfter, role) =>
              reframeProblem(stack, instruction) match
                case Some(problem) =>
                  nodeCount += 1L
                  record(problem.copy(span = Some(input.token.span)))
                  val tokenNode = UniNode.Token(input.token)
                  if stack.nonEmpty then addTop(UniEdge(role, tokenNode)) else roots = roots :+ tokenNode
                case None =>
                  nodeCount += 1L + open.size
                  closeBefore.foreach(_ => closeFrame())
                  open.foreach(spec => stack = stack :+ VmFrame(spec.kind, spec.role, Vector.empty, input.token.span))
                  val tokenNode = UniNode.Token(input.token)
                  if stack.nonEmpty then addTop(UniEdge(role, tokenNode)) else roots = roots :+ tokenNode
                  closeAfter.foreach(_ => closeFrame())

            case VmInstruction.Report(code, message, severity) =>
              nodeCount += 1L
              val tokenNode = UniNode.Token(input.token)
              record(Diagnostic(code, message, severity, Some(input.token.span)))
              if severity == Severity.Fatal then halted = true
              if stack.nonEmpty then addTop(UniEdge(None, tokenNode)) else roots = roots :+ tokenNode

            case VmInstruction.Close(expectedKind, role) =>
              nodeCount += 1L
              val tokenNode = UniNode.Token(input.token)
              if stack.isEmpty then
                record(Diagnostic(
                  code = "uniml.vm.orphan-close",
                  message = "close instruction has no open node",
                  severity = Severity.Error,
                  span = Some(input.token.span),
                ))
                roots = roots :+ tokenNode
              else
                val frame = stack.last
                addTop(UniEdge(role, tokenNode))
                expectedKind match
                  case Some(expected) if expected != frame.kind =>
                    record(Diagnostic(
                      code = "uniml.vm.mismatched-close",
                      message = s"expected to close '$expected' but current node is '${frame.kind}'",
                      severity = Severity.Error,
                      span = Some(input.token.span),
                      details = Vector("expected" -> expected, "actual" -> frame.kind),
                    ))
                  case _ =>
                    // pop the frame we just extended
                    val closed = stack.last
                    stack = stack.dropRight(1)
                    attach(TreeVm.buildBranch(closed, Origin.SourceBacked), closed.role)

      Stepped(
        VmState(stack, nodeCount, lastTokenId, diagCount, diagLimitReported, finished = false, halted = halted),
        ProcessBatch(roots, diags),
      )

  def stop(state: VmState): ProcessBatch[UniNode] =
    if state.finished then ProcessBatch(Vector.empty, Vector(TreeVm.finishedDiagnostic))
    else
      var stack = state.stack
      var diagCount = state.diagnosticCount
      var diagLimitReported = state.diagnosticLimitReported
      var roots: Vector[UniNode] = Vector.empty
      var diags: Vector[Diagnostic] = Vector.empty

      def record(d: Diagnostic): Unit =
        if diagCount < limits.maxDiagnostics then
          diagCount += 1
          diags = diags :+ d
        else if !diagLimitReported then
          diagLimitReported = true
          diags = diags :+ Diagnostic(
            code = "uniml.limit.diagnostics",
            message = s"diagnostic count exceeds the ${limits.maxDiagnostics} limit",
            severity = Severity.Fatal,
            span = d.span,
          )

      while stack.nonEmpty do
        val frame = stack.last
        stack = stack.dropRight(1)
        record(Diagnostic(
          code = "uniml.vm.unclosed-node",
          message = s"unclosed '${frame.kind}' node at end of input",
          severity = Severity.Error,
          span = Some(frame.openingSpan),
        ))
        val branch = TreeVm.buildBranch(frame, Origin.Synthetic(s"unclosed:${frame.kind}"))
        if stack.nonEmpty then
          val top = stack.last
          stack = stack.dropRight(1) :+ VmFrame(top.kind, top.role, top.edges :+ UniEdge(frame.role, branch), top.openingSpan)
        else roots = roots :+ branch
      ProcessBatch(roots, diags)

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
    val VmInstruction.Reframe(closeBefore, open, closeAfter, _) = instruction
    if closeBefore.exists(_.isEmpty) || closeAfter.exists(_.isEmpty) || open.exists(_.kind.isEmpty) then
      Some(Diagnostic(
        code = "uniml.vm.invalid-reframe",
        message = "reframe kinds must be non-empty",
        severity = Severity.Error,
        span = None,
      ))
    else
      var kinds = stack.map(_.kind)
      var problem: Option[Diagnostic] = None
      def close(expected: String): Unit =
        if problem.isEmpty then
          if kinds.isEmpty then
            problem = Some(Diagnostic(
              code = "uniml.vm.reframe-underflow",
              message = s"reframe cannot close '$expected' because no frame is open",
              severity = Severity.Error,
              span = None,
            ))
          else if kinds.last != expected then
            problem = Some(Diagnostic(
              code = "uniml.vm.mismatched-reframe",
              message = s"expected to reframe '$expected' but current node is '${kinds.last}'",
              severity = Severity.Error,
              span = None,
              details = Vector("expected" -> expected, "actual" -> kinds.last),
            ))
          else kinds = kinds.dropRight(1)
      closeBefore.foreach(close)
      if problem.isEmpty then
        open.foreach(spec => kinds = kinds :+ spec.kind)
        closeAfter.foreach(close)
      problem

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
    var diagnostics: Vector[Diagnostic] = Vector.empty
    lastTokenId.foreach { previous =>
      if token.id <= previous then
        diagnostics = diagnostics :+ Diagnostic(
          code = "uniml.token.non-monotonic-id",
          message = s"token id ${token.id} must be greater than previous id $previous",
          severity = Severity.Error,
          span = Some(token.span),
        )
    }
    if token.span.end.offset < token.span.start.offset then
      diagnostics = diagnostics :+ Diagnostic(
        code = "uniml.token.invalid-span",
        message = "token span end precedes its start",
        severity = Severity.Error,
        span = Some(token.span),
      )
    diagnostics
