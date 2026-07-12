package scalascript.uniml

import org.scalatest.funsuite.AnyFunSuite

final class TreeVmSpec extends AnyFunSuite:
  private val source = SourceId("memory:test")

  test("balanced instructions build an ordered lossless branch") {
    val vm = TreeVm()
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
    val vm = TreeVm()
    vm.push(vmToken(0, "{", VmInstruction.Open("json.object")))
    val mismatch = vm.push(vmToken(1, "]", VmInstruction.Close(Some("json.array"))))
    assert(mismatch.values.isEmpty)
    assert(mismatch.diagnostics.map(_.code) == Vector("uniml.vm.mismatched-close"))

    val closed = vm.push(vmToken(2, "}", VmInstruction.Close(Some("json.object"))))
    val branch = closed.values.head
    assert(UniNode.sourceTokens(branch).map(_.lexeme) == Vector("{", "]", "}"))
  }

  test("nested branches attach to their parent with the opening role") {
    val vm = TreeVm()
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
    val vm = TreeVm(Limits(maxDepth = 1))
    vm.push(vmToken(0, "{", VmInstruction.Open("outer")))
    val rejected = vm.push(vmToken(1, "[", VmInstruction.Open("inner")))

    assert(rejected.values.isEmpty)
    assert(rejected.diagnostics.map(_.code) == Vector("uniml.limit.depth"))
    assert(rejected.diagnostics.head.severity == Severity.Fatal)
    val partial = vm.finish().values.head
    assert(UniNode.sourceTokens(partial).map(_.lexeme) == Vector("{"))
  }

  test("report instructions retain their source token") {
    val vm = TreeVm()
    val batch = vm.push(vmToken(0, "?", VmInstruction.Report("test.problem", "problem")))
    assert(batch.diagnostics.map(_.code) == Vector("test.problem"))
    assert(batch.values.collect { case UniNode.Token(token) => token.lexeme } == Vector("?"))
  }

  private def vmToken(id: Long, lexeme: String, instruction: VmInstruction): VmToken =
    val start = SourcePosition(id.toInt, 1, id.toInt + 1)
    val end = SourcePosition(id.toInt + 1, 1, id.toInt + 2)
    VmToken(SourceToken(id, "test.token", lexeme, SourceSpan(source, start, end)), instruction)
