package scalascript.uniml

import org.scalatest.funsuite.AnyFunSuite

final class TreeVmSpec extends AnyFunSuite:
  private val source = SourceId("memory:test")

  test("balanced instructions build an ordered lossless branch") {
    val vm = VmDriver()
    assert(vm.push(vmToken(0, "{", VmInstruction.Open("json.object"))).values.isEmpty)
    assert(vm.push(vmToken(1, "name", VmInstruction.Emit(Some("member.key")))).values.isEmpty)
    val closed = vm.push(vmToken(2, "}", VmInstruction.Close(Some("json.object"), Some("delimiter.close"))))

    assert(closed.diagnostics.isEmpty)
    assert(closed.values.size == 1)
    val branch = closed.values.head.asInstanceOf[UniNode.Branch]
    assert(branch.kind == "json.object")
    assert(branch.edges.map(_.role) == Vector(None, Some("member.key"), Some("delimiter.close")))
    assert(UniNode.sourceTokens(branch).map(_.lexeme) == Vector("{", "name", "}"))
    assert(branch.span.start.offset == 0)
    assert(branch.span.end.offset == 3)
    assert(vm.finish().values.isEmpty)
  }

  test("a mismatched close is retained but does not close another frame") {
    val vm = VmDriver()
    vm.push(vmToken(0, "{", VmInstruction.Open("json.object")))
    val mismatch = vm.push(vmToken(1, "]", VmInstruction.Close(Some("json.array"))))
    assert(mismatch.values.isEmpty)
    assert(mismatch.diagnostics.map(_.code) == Vector("uniml.vm.mismatched-close"))

    val closed = vm.push(vmToken(2, "}", VmInstruction.Close(Some("json.object"))))
    val branch = closed.values.head
    assert(UniNode.sourceTokens(branch).map(_.lexeme) == Vector("{", "]", "}"))
  }

  test("nested branches attach to their parent with the opening role") {
    val vm = VmDriver()
    vm.push(vmToken(0, "[", VmInstruction.Open("json.array")))
    vm.push(vmToken(1, "{", VmInstruction.Open("json.object", Some("item"))))
    vm.push(vmToken(2, "}", VmInstruction.Close(Some("json.object"))))
    val closed = vm.push(vmToken(3, "]", VmInstruction.Close(Some("json.array"))))

    val outer = closed.values.head.asInstanceOf[UniNode.Branch]
    val nested = outer.edges.collectFirst { case UniEdge(Some("item"), branch: UniNode.Branch) => branch }
    assert(nested.exists(_.kind == "json.object"))
    assert(UniNode.sourceTokens(outer).map(_.lexeme) == Vector("[", "{", "}", "]"))
  }

  test("depth limit halts before opening an excessive frame") {
    val vm = VmDriver(Limits(maxDepth = 1))
    vm.push(vmToken(0, "{", VmInstruction.Open("outer")))
    val rejected = vm.push(vmToken(1, "[", VmInstruction.Open("inner")))

    assert(rejected.values.isEmpty)
    assert(rejected.diagnostics.map(_.code) == Vector("uniml.limit.depth"))
    assert(rejected.diagnostics.head.severity == Severity.Fatal)
    val partial = vm.finish().values.head
    assert(UniNode.sourceTokens(partial).map(_.lexeme) == Vector("{"))
  }

  test("node and token-size limits reject input with fatal diagnostics") {
    val nodeLimited = VmDriver(Limits(maxNodes = 1))
    val nodeResult = nodeLimited.push(vmToken(0, "{", VmInstruction.Open("object")))
    assert(nodeResult.diagnostics.map(_.code) == Vector("uniml.limit.nodes"))
    assert(nodeResult.diagnostics.head.severity == Severity.Fatal)

    val tokenLimited = VmDriver(Limits(maxTokenCodePoints = 1))
    val tokenResult = tokenLimited.push(vmToken(0, "ab", VmInstruction.Emit()))
    assert(tokenResult.diagnostics.map(_.code) == Vector("uniml.limit.token"))
    assert(tokenResult.diagnostics.head.severity == Severity.Fatal)
  }

  test("report instructions retain their source token") {
    val vm = VmDriver()
    val batch = vm.push(vmToken(0, "?", VmInstruction.Report("test.problem", "problem")))
    assert(batch.diagnostics.map(_.code) == Vector("test.problem"))
    assert(batch.values.collect { case UniNode.Token(token) => token.lexeme } == Vector("?"))
  }

  test("reframe atomically closes, opens, emits once, and closes after the carrier") {
    val vm = VmDriver()
    vm.push(vmToken(0, "doc", VmInstruction.Open("yaml.document")))
    vm.push(vmToken(1, "a", VmInstruction.Open("yaml.mapping", Some("document.value"))))
    vm.push(vmToken(2, ":", VmInstruction.Emit(Some("mapping.colon"))))
    val result = vm.push(vmToken(
      3,
      "b",
      VmInstruction.Reframe(
        closeBefore = Vector("yaml.mapping"),
        open = Vector(FrameSpec("yaml.sequence", Some("document.next"))),
        closeAfter = Vector("yaml.sequence", "yaml.document"),
        role = Some("sequence.item"),
      ),
    ))

    assert(result.diagnostics.isEmpty)
    assert(result.values.size == 1)
    val document = result.values.head.asInstanceOf[UniNode.Branch]
    assert(document.kind == "yaml.document")
    assert(document.edges.collect { case UniEdge(role, branch: UniNode.Branch) => role -> branch.kind } ==
      Vector(Some("document.value") -> "yaml.mapping", Some("document.next") -> "yaml.sequence"))
    assert(UniNode.sourceTokens(document).map(_.lexeme) == Vector("doc", "a", ":", "b"))
    assert(vm.finish().values.isEmpty)
  }

  test("invalid reframe is atomic and retains its carrier as an emit") {
    val vm = VmDriver()
    vm.push(vmToken(0, "{", VmInstruction.Open("outer")))
    val invalid = vm.push(vmToken(
      1,
      "x",
      VmInstruction.Reframe(
        closeBefore = Vector("wrong"),
        open = Vector(FrameSpec("inner")),
        role = Some("fallback"),
      ),
    ))

    assert(invalid.diagnostics.map(_.code) == Vector("uniml.vm.mismatched-reframe"))
    val closed = vm.push(vmToken(2, "}", VmInstruction.Close(Some("outer"))))
    val outer = closed.values.head.asInstanceOf[UniNode.Branch]
    assert(outer.edges.exists(edge => edge.role.contains("fallback")))
    assert(!outer.edges.exists(_.child match
      case UniNode.Branch("inner", _, _, _) => true
      case _                                 => false
    ))
    assert(UniNode.sourceTokens(outer).map(_.lexeme) == Vector("{", "x", "}"))
  }

  test("reframe depth rejection leaves existing frames unchanged") {
    val vm = VmDriver(Limits(maxDepth = 1))
    vm.push(vmToken(0, "{", VmInstruction.Open("outer")))
    val rejected = vm.push(vmToken(1, "x", VmInstruction.Reframe(open = Vector(FrameSpec("inner")))))

    assert(rejected.diagnostics.map(_.code) == Vector("uniml.limit.depth"))
    assert(rejected.diagnostics.head.severity == Severity.Fatal)
    val partial = vm.finish().values.head.asInstanceOf[UniNode.Branch]
    assert(partial.kind == "outer")
    assert(UniNode.sourceTokens(partial).map(_.lexeme) == Vector("{"))
  }

  /** Test-only stateful driver over the pure TreeVm fold, so the test bodies read
    * as a sequence of pushes. (Production code threads VmState immutably.) */
  private final class VmDriver(limits: Limits = Limits.default):
    private val vm = TreeVm(limits)
    private var state = vm.start
    def push(token: VmToken): ProcessBatch[UniNode] =
      val stepped = vm.step(state, token)
      state = stepped.state
      stepped.batch
    def finish(): ProcessBatch[UniNode] = vm.stop(state)

  private def vmToken(id: Long, lexeme: String, instruction: VmInstruction): VmToken =
    val start = SourcePosition(id.toInt, 1, id.toInt + 1)
    val end = SourcePosition(id.toInt + 1, 1, id.toInt + 2)
    VmToken(SourceToken(id, "test.token", lexeme, SourceSpan(source, start, end)), instruction)
