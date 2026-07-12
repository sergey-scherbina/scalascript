package scalascript.uniml

import scala.collection.mutable.ArrayBuffer

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

final class TreeVm(limits: Limits = Limits.default) extends Processor[VmToken, UniNode]:
  private final class Frame(
      val kind: String,
      val role: Option[String],
      val edges: ArrayBuffer[UniEdge],
      val openingSpan: SourceSpan,
  )

  private val stack = ArrayBuffer.empty[Frame]
  private var nodeCount = 0L
  private var lastTokenId: Option[Long] = None
  private var diagnosticCount = 0
  private var diagnosticLimitReported = false
  private var finished = false
  private var halted = false

  def push(input: VmToken): ProcessBatch[UniNode] =
    if finished then ProcessBatch(Vector.empty, Vector(finishedDiagnostic))
    else if halted then ProcessBatch.empty
    else
      preflight(input) match
        case Some(diagnostic) =>
          halted = diagnostic.severity == Severity.Fatal
          ProcessBatch(Vector.empty, record(diagnostic))
        case None => execute(input)

  def finish(): ProcessBatch[UniNode] =
    if finished then ProcessBatch(Vector.empty, Vector(finishedDiagnostic))
    else
      finished = true
      val roots = Vector.newBuilder[UniNode]
      val diagnostics = Vector.newBuilder[Diagnostic]
      while stack.nonEmpty do
        val frame = stack.remove(stack.size - 1)
        val diagnostic = Diagnostic(
          code = "uniml.vm.unclosed-node",
          message = s"unclosed '${frame.kind}' node at end of input",
          severity = Severity.Error,
          span = Some(frame.openingSpan),
        )
        diagnostics ++= record(diagnostic)
        val branch = buildBranch(frame, Origin.Synthetic(s"unclosed:${frame.kind}"))
        attach(branch, frame.role, roots)
      ProcessBatch(roots.result(), diagnostics.result())

  private def preflight(input: VmToken): Option[Diagnostic] =
    val token = input.token
    if Unicode.codePointCount(token.lexeme) > limits.maxTokenCodePoints then
      Some(Diagnostic(
        code = "uniml.limit.token",
        message = s"token exceeds the ${limits.maxTokenCodePoints} code-point limit",
        severity = Severity.Fatal,
        span = Some(token.span),
      ))
    else
      val (requiredNodes, peakDepth) = input.instruction match
        case _: VmInstruction.Open => (2L, stack.size + 1)
        case instruction: VmInstruction.Reframe =>
          reframeProblem(instruction) match
            case None          => (1L + instruction.open.size, stack.size - instruction.closeBefore.size + instruction.open.size)
            case Some(_)       => (1L, stack.size)
        case _ => (1L, stack.size)
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

  private def execute(input: VmToken): ProcessBatch[UniNode] =
    val diagnostics = Vector.newBuilder[Diagnostic]
    validateToken(input.token).foreach(diagnostics ++= record(_))
    lastTokenId = Some(input.token.id)
    input.instruction match
      case VmInstruction.Open(kind, role) =>
        nodeCount += 2L
        val frame = Frame(kind, role, ArrayBuffer.empty, input.token.span)
        stack += frame
        frame.edges += UniEdge(None, UniNode.Token(input.token))
        ProcessBatch(Vector.empty, diagnostics.result())

      case VmInstruction.Emit(role) =>
        nodeCount += 1L
        val tokenNode = UniNode.Token(input.token)
        if stack.nonEmpty then
          stack.last.edges += UniEdge(role, tokenNode)
          ProcessBatch(Vector.empty, diagnostics.result())
        else ProcessBatch(Vector(tokenNode), diagnostics.result())

      case instruction @ VmInstruction.Reframe(closeBefore, open, closeAfter, role) =>
        reframeProblem(instruction) match
          case Some(problem) =>
            nodeCount += 1L
            diagnostics ++= record(problem.copy(span = Some(input.token.span)))
            val tokenNode = UniNode.Token(input.token)
            if stack.nonEmpty then
              stack.last.edges += UniEdge(role, tokenNode)
              ProcessBatch(Vector.empty, diagnostics.result())
            else ProcessBatch(Vector(tokenNode), diagnostics.result())

          case None =>
            nodeCount += 1L + open.size
            val roots = Vector.newBuilder[UniNode]
            closeBefore.foreach(expected => closeFrame(expected, roots))
            open.foreach { spec =>
              stack += Frame(spec.kind, spec.role, ArrayBuffer.empty, input.token.span)
            }
            val tokenNode = UniNode.Token(input.token)
            if stack.nonEmpty then stack.last.edges += UniEdge(role, tokenNode)
            else roots += tokenNode
            closeAfter.foreach(expected => closeFrame(expected, roots))
            ProcessBatch(roots.result(), diagnostics.result())

      case VmInstruction.Report(code, message, severity) =>
        nodeCount += 1L
        val tokenNode = UniNode.Token(input.token)
        val report = Diagnostic(code, message, severity, Some(input.token.span))
        diagnostics ++= record(report)
        if severity == Severity.Fatal then halted = true
        if stack.nonEmpty then
          stack.last.edges += UniEdge(None, tokenNode)
          ProcessBatch(Vector.empty, diagnostics.result())
        else ProcessBatch(Vector(tokenNode), diagnostics.result())

      case VmInstruction.Close(expectedKind, role) =>
        nodeCount += 1L
        val tokenNode = UniNode.Token(input.token)
        if stack.isEmpty then
          diagnostics ++= record(Diagnostic(
            code = "uniml.vm.orphan-close",
            message = "close instruction has no open node",
            severity = Severity.Error,
            span = Some(input.token.span),
          ))
          ProcessBatch(Vector(tokenNode), diagnostics.result())
        else
          val frame = stack.last
          frame.edges += UniEdge(role, tokenNode)
          expectedKind match
            case Some(expected) if expected != frame.kind =>
              diagnostics ++= record(Diagnostic(
                code = "uniml.vm.mismatched-close",
                message = s"expected to close '$expected' but current node is '${frame.kind}'",
                severity = Severity.Error,
                span = Some(input.token.span),
                details = Vector("expected" -> expected, "actual" -> frame.kind),
              ))
              ProcessBatch(Vector.empty, diagnostics.result())
            case _ =>
              stack.remove(stack.size - 1)
              val branch = buildBranch(frame, Origin.SourceBacked)
              val roots = Vector.newBuilder[UniNode]
              attach(branch, frame.role, roots)
              ProcessBatch(roots.result(), diagnostics.result())

  private def reframeProblem(instruction: VmInstruction.Reframe): Option[Diagnostic] =
    val VmInstruction.Reframe(closeBefore, open, closeAfter, _) = instruction
    if closeBefore.exists(_.isEmpty) || closeAfter.exists(_.isEmpty) || open.exists(_.kind.isEmpty) then
      Some(Diagnostic(
        code = "uniml.vm.invalid-reframe",
        message = "reframe kinds must be non-empty",
        severity = Severity.Error,
        span = None,
      ))
    else
      val kinds = ArrayBuffer.from(stack.iterator.map(_.kind))
      def close(expected: String): Option[Diagnostic] =
        if kinds.isEmpty then
          Some(Diagnostic(
            code = "uniml.vm.reframe-underflow",
            message = s"reframe cannot close '$expected' because no frame is open",
            severity = Severity.Error,
            span = None,
          ))
        else if kinds.last != expected then
          Some(Diagnostic(
            code = "uniml.vm.mismatched-reframe",
            message = s"expected to reframe '$expected' but current node is '${kinds.last}'",
            severity = Severity.Error,
            span = None,
            details = Vector("expected" -> expected, "actual" -> kinds.last),
          ))
        else
          kinds.remove(kinds.size - 1)
          None

      closeBefore.iterator.map(close).collectFirst { case Some(problem) => problem }
        .orElse {
          open.foreach(spec => kinds += spec.kind)
          closeAfter.iterator.map(close).collectFirst { case Some(problem) => problem }
        }

  private def closeFrame(expected: String, roots: scala.collection.mutable.Builder[UniNode, Vector[UniNode]]): Unit =
    val frame = stack.remove(stack.size - 1)
    assert(frame.kind == expected)
    attach(buildBranch(frame, Origin.SourceBacked), frame.role, roots)

  private def validateToken(token: SourceToken): Vector[Diagnostic] =
    val diagnostics = Vector.newBuilder[Diagnostic]
    lastTokenId.foreach { previous =>
      if token.id <= previous then
        diagnostics += Diagnostic(
          code = "uniml.token.non-monotonic-id",
          message = s"token id ${token.id} must be greater than previous id $previous",
          severity = Severity.Error,
          span = Some(token.span),
        )
    }
    if token.span.end.offset < token.span.start.offset then
      diagnostics += Diagnostic(
        code = "uniml.token.invalid-span",
        message = "token span end precedes its start",
        severity = Severity.Error,
        span = Some(token.span),
      )
    diagnostics.result()

  private def buildBranch(frame: Frame, origin: Origin): UniNode.Branch =
    val end = frame.edges.lastOption match
      case Some(UniEdge(_, UniNode.Token(token)))       => token.span.end
      case Some(UniEdge(_, UniNode.Branch(_, _, span, _))) => span.end
      case None                                         => frame.openingSpan.end
    UniNode.Branch(
      kind = frame.kind,
      edges = frame.edges.toVector,
      span = SourceSpan(frame.openingSpan.source, frame.openingSpan.start, end),
      origin = origin,
    )

  private def attach(branch: UniNode.Branch, role: Option[String], roots: scala.collection.mutable.Builder[UniNode, Vector[UniNode]]): Unit =
    if stack.nonEmpty then stack.last.edges += UniEdge(role, branch)
    else roots += branch

  private def record(diagnostic: Diagnostic): Vector[Diagnostic] =
    if diagnosticCount < limits.maxDiagnostics then
      diagnosticCount += 1
      Vector(diagnostic)
    else if !diagnosticLimitReported then
      diagnosticLimitReported = true
      halted = true
      Vector(Diagnostic(
        code = "uniml.limit.diagnostics",
        message = s"diagnostic count exceeds the ${limits.maxDiagnostics} limit",
        severity = Severity.Fatal,
        span = diagnostic.span,
      ))
    else Vector.empty

  private val finishedDiagnostic = Diagnostic(
    code = "uniml.vm.finished",
    message = "tree VM cannot accept input or finish more than once",
    severity = Severity.Error,
    span = None,
  )
