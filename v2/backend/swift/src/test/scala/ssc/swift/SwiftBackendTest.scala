package ssc.swift

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import org.scalatest.funsuite.AnyFunSuite

import ssc.{Arm, Const, Program, Reader, Term}
import ssc.bridge.{FrontendBridge, PluginBridge}

final class SwiftBackendTest extends AnyFunSuite:
  private val repoRoot =
    Iterator.iterate(Paths.get(sys.props("user.dir")).toAbsolutePath.normalize())(_.getParent)
      .takeWhile(_ != null)
      .find(path => Files.isRegularFile(path.resolve("build.sbt")) && Files.isDirectory(path.resolve("v2/conformance")))
      .getOrElse(fail("cannot locate repository root"))

  test("generated AppCore package is deterministic and structurally separated"):
    val program = fixture("fact")
    val first = SwiftBackend.generate(program, "Fact-App")
    val second = SwiftBackend.generate(program, "Fact-App")
    assert(first == second)
    assert(first.files.map(_._1) == Vector(
      "Package.swift",
      "Sources/AppCore/SscRuntime.swift",
      "Sources/AppCore/GeneratedProgram.swift",
      "Sources/Fact_App/main.swift",
    ))
    val all = first.files.map(_._2).mkString("\n")
    assert(all.contains("SscProgram(definitions:"))
    assert(!all.contains("scalascript.codegen.JvmGen"))
    assert(!all.contains("SwiftUIEmitter"))

  test("NativeUi programs add the AppCore host while domain packages stay host-free"):
    val domain = SwiftBackend.generate(fixture("fact"), "Fact-App")
    assert(!domain.files.exists(_._1.endsWith("NativeUiHost.swift")))

    val ui = SwiftBackend.generate(nativeUiProgram(), "Native-App")
    assert(ui.files.map(_._1) == Vector(
      "Package.swift",
      "Sources/AppCore/SscRuntime.swift",
      "Sources/AppCore/NativeUiHost.swift",
      "Sources/AppCore/GeneratedProgram.swift",
      "Sources/Native_AppCli/main.swift",
    ))
    assert(ui.executable == "Native_AppCli")
    assert(ui.files.find(_._1.endsWith("NativeUiHost.swift")).exists(!_._2.contains("import SwiftUI")))

  test("unsupported globals and primitives fail during generation with their names"):
    val badGlobal = Program(Nil, Term.Global("host.secret"))
    val globalError = intercept[IllegalArgumentException](SwiftBackend.generate(badGlobal))
    assert(globalError.getMessage == "swift backend: unsupported global 'host.secret'")

    val badPrimitive = Program(Nil, Term.Prim("host.secret", Nil))
    val primitiveError = intercept[IllegalArgumentException](SwiftBackend.generate(badPrimitive))
    assert(primitiveError.getMessage == "swift backend: unsupported primitive 'host.secret'")

  test("domain definitions named like UI globals do not switch package mode"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val identity = ssc.Def("signal", Term.Lam(1, Term.Local(0)))
    val domain = Program(List(identity), Term.App(Term.Global("signal"), List(str("domain"))))
    val generated = SwiftBackend.generate(domain, "DomainSignal")
    assert(generated.executable == "DomainSignal")
    assert(!generated.files.exists(_._1.endsWith("NativeUiHost.swift")))
    assert(runSwift("domainSignal", domain) == "\"domain\"")

  test("generated package declares the selected Apple deployment platform"):
    val program = fixture("fact")
    val macos = SwiftBackend.generate(program, "Fact", SwiftPlatform.MacOS).files.head._2
    val ios = SwiftBackend.generate(program, "Fact", SwiftPlatform.IOS).files.head._2
    assert(macos.contains("platforms: [.macOS(.v13)]"))
    assert(ios.contains("platforms: [.iOS(.v16)]"))

  test("real swift run matches VM structural fixtures fact tco and map"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val cases = List(
      "fact" -> "120",
      "tco" -> "500000500000",
      "mutual-tco" -> "true",
      "map" -> "List(2, 4, 6)",
    )
    cases.foreach { (name, expected) =>
      assert(runSwift(name, fixture(name)) == expected)
    }

  test("real swift run keeps arbitrary precision BigInt arithmetic"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val left = BigInt("123456789012345678901234567890")
    val right = BigInt("98765432109876543210")
    val product = Term.Prim("big.mul", List(
      Term.Lit(Const.CBig(left)),
      Term.Lit(Const.CBig(right)),
    ))
    val roundTrip = Term.Prim("big.div", List(product, Term.Lit(Const.CBig(right))))
    assert(runSwift("bigint", Program(Nil, roundTrip)) == left.toString)

  test("real swift run preserves portable Decimal scale rounding and map identity"):
    assume(swiftAvailable, "Swift toolchain is not available")
    def str(value: String) = Term.Lit(Const.CStr(value))
    def int(value: Long) = Term.Lit(Const.CInt(value))
    def dec(value: String) = Term.Prim("dec.parse", List(str(value)))
    val add = Term.Prim("dec.add", List(dec("1.20"), dec("2.3")))
    val div = Term.Prim("dec.div", List(dec("1"), dec("8"), int(3), str("HALF_UP")))
    val rounded = Term.Prim("dec.set-scale", List(dec("2.345"), int(2), str("HALF_UP")))
    val numericEquality = Term.Prim("__arith__", List(str("=="), dec("1.0"), dec("1.00")))
    val fromUnscaled = Term.Prim("dec.from-unscaled", List(Term.Lit(Const.CBig(BigInt(1230))), int(2)))
    val mapGet = Term.Prim("map.get", List(Term.Local(0), dec("1.00")))
    val body = Term.Seq(List(
      Term.Prim("map.put", List(Term.Local(0), dec("1.0"), int(7))),
      Term.Ctor("Tuple6", List(add, div, rounded, numericEquality, fromUnscaled, mapGet)),
    ))
    val program = Program(Nil, Term.Let(List(Term.Prim("map.new", Nil)), body))
    assert(runSwift("decimal", program) == "(3.50, 0.125, 2.35, true, 12.30, Some(7))")

  test("real swift run preserves reusable multi-shot Pure Op continuations"):
    assume(swiftAvailable, "Swift toolchain is not available")
    def int(value: Long) = Term.Lit(Const.CInt(value))
    def resume(value: Long) = Term.App(Term.Local(0), List(int(value)))
    val chooseBody = Term.Prim("i.add", List(resume(10), resume(20)))
    val handler = Term.Lam(1, Term.Match(
      Term.Local(0),
      List(
        Arm("Choose", 2, chooseBody),
        Arm("Return", 1, Term.Local(0)),
      ),
      None,
    ))
    val computation = Term.Prim("effect.perform", List(Term.Lit(Const.CStr("Demo.Choose")), int(1)))
    val program = Program(Nil, Term.Prim("effect.handle", List(computation, handler)))
    assert(runSwift("effects", program) == "30")

  test("real swift run exposes executable arguments through io.args"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val program = Program(Nil, Term.Prim("io.args", Nil))
    assert(runSwift("args", program, List("one", "two")) == "List(\"one\", \"two\")")

  test("real Swift NativeUi host evaluates signal dispatch and one root"):
    assume(swiftAvailable, "Swift toolchain is not available")
    assert(runSwift("nativeUi", nativeUiProgram()) ==
      "NativeUiAbi(version=1, root=NativeUiFragment, operation=emit)")

  test("extracted NativeUi session retains mutable computed and render closures"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val result = runSwiftResult("nativeUiSession", nativeUiSessionProgram(), probe = Some(nativeUiSessionProbe))
    assert(result.exit == 0, result.stderr)
    assert(result.stdout == "after|computed|row|NativeUiFragment")

  test("NativeUi evaluation failure aborts and the same host recovers"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val result = runSwiftResult("nativeUiRecovery", nativeUiProgram(), probe = Some(nativeUiRecoveryProbe))
    assert(result.exit == 0, result.stderr)
    assert(result.stdout == "fragment(children)|NativeUiSignalText")

  test("real Swift NativeUi host executes descriptor defaults and trusted HTML"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val descriptors = runSwiftResult("nativeUiDescriptors", nativeUiDescriptorProgram(), probe = Some(nativeUiDescriptorProbe))
    assert(descriptors.exit == 0, descriptors.stderr)
    assert(descriptors.stdout ==
      "source=static::;column=text:Id:id::edit=();delete=delete:Delete:POST:/delete:field:id;post=post:Save:POST:/save:field:id")
    assert(runSwift("nativeUiRawHtml", nativeUiRawHtmlProgram()) ==
      "NativeUiAbi(version=1, root=NativeUiTrustedHtml, operation=emit)")

  test("real Swift NativeUi host rejects missing and duplicate roots"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val missing = runSwiftResult("nativeUiMissing", Program(Nil, Term.App(Term.Global("signal"), List(str("orphan"), str("")))))
    assert(missing.exit != 0)
    assert(missing.stderr.contains("native UI program did not register a root"))

    def source(file: String, line: Long, column: Long, operation: String) =
      Term.Ctor("NativeUiSourceRef", List(str(file), Term.Lit(Const.CInt(line)), Term.Lit(Const.CInt(column)), str(operation)))
    val duplicate = runSwiftResult("nativeUiDuplicate", Program(Nil, Term.Seq(List(
      Term.App(Term.Global("__ssc_nativeui_v1.emit"), List(source("first.ssc", 10, 3, "emit"), Term.App(Term.Global("textNode"), List(str("first"))), str("out"))),
      Term.App(Term.Global("__ssc_nativeui_v1.serve"), List(source("second.ssc", 20, 7, "serve"), Term.App(Term.Global("textNode"), List(str("second"))), Term.Lit(Const.CInt(8080)), str(""))),
    ))))
    assert(duplicate.exit != 0)
    assert(duplicate.stderr.contains("native UI program registered multiple roots"))
    assert(duplicate.stderr.contains("emit at first.ssc:10:3 [emit]"))
    assert(duplicate.stderr.contains("serve at second.ssc:20:7 [serve]"))

  test("real Swift NativeUi host accepts exact mobile CSS and rejects a near miss"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val valid = "@media(max-width:600px){body,p,label,span{font-size:16px!important}h1{font-size:28px!important}h2{font-size:24px!important}h3{font-size:20px!important}h4,h5,h6{font-size:18px!important}button{font-size:16px!important;padding:8px 12px!important;border-radius:6px!important}input[type=text],input[type=email],input[type=password]{font-size:16px!important;padding:8px!important;border-radius:6px!important}}"
    def program(css: String) = Program(Nil, Term.App(Term.Global("serve"), List(
      Term.App(Term.Global("textNode"), List(str("root"))),
      Term.Lit(Const.CInt(8080)),
      str(css),
    )))
    assert(runSwift("nativeUiMobileCss", program(valid)) ==
      "NativeUiAbi(version=1, root=NativeUiText, operation=serve)")
    val invalid = runSwiftResult("nativeUiBadMobileCss", program(valid + " "), probe = Some(nativeUiUnsupportedProbe))
    assert(invalid.exit == 0, invalid.stderr)
    assert(invalid.stdout == "root extraCss|<entry>:0:0:serve|only std/ui mobileOverrideCss is supported")

  test("checked real money source runs through FrontendBridge and SwiftPM"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val source = repoRoot.resolve("tests/conformance/money-portable-v2.ssc")
    FrontendBridge.resetState()
    PluginBridge.loadAll()
    val program = FrontendBridge.convertSource(
      Files.readString(source, StandardCharsets.UTF_8),
      Some(source.getParent.toFile),
    )
    val expected = Files.readString(
      repoRoot.resolve("tests/conformance/expected/money-portable-v2.txt"),
      StandardCharsets.UTF_8,
    ).trim
    assert(runSwift("money", program) == expected)

  test("checked transitive effect source runs through FrontendBridge and SwiftPM"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val source = repoRoot.resolve("tests/conformance/effect-transitive-handler.ssc")
    FrontendBridge.resetState()
    PluginBridge.loadAll()
    val program = FrontendBridge.convertSource(
      Files.readString(source, StandardCharsets.UTF_8),
      Some(source.getParent.toFile),
    )
    val expected = Files.readString(
      repoRoot.resolve("tests/conformance/expected/effect-transitive-handler.txt"),
      StandardCharsets.UTF_8,
    ).trim
    assert(runSwift("transitiveEffects", program) == expected)

  test("checked std/ui source runs through FrontendBridge and Swift NativeUi host"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val source = repoRoot.resolve("examples/swift/appcore-nativeui.ssc")
    FrontendBridge.resetState()
    PluginBridge.loadAll()
    val program = FrontendBridge.convertSource(
      Files.readString(source, StandardCharsets.UTF_8),
      Some(source.getParent.toFile),
    )
    assert(runSwift("checkedNativeUi", program) ==
      "NativeUiAbi(version=1, root=NativeUiFragment, operation=emit)")

  private def fixture(name: String): Program =
    val path = repoRoot.resolve(s"v2/conformance/$name.coreir")
    Reader.parseProgram(Files.readString(path, StandardCharsets.UTF_8))

  private def str(value: String): Term = Term.Lit(Const.CStr(value))

  private def list(values: List[Term]): Term =
    values.foldRight[Term](Term.Ctor("Nil", Nil))((head, tail) => Term.Ctor("Cons", List(head, tail)))

  private def nativeUiProgram(): Program =
    val signal = Term.App(Term.Global("signal"), List(str("message"), str("before")))
    val set = Term.Prim("__method__", List(str("set"), Term.Local(0), str("after")))
    val get = Term.Prim("__method__", List(str("get"), Term.Local(0)))
    val id = Term.Prim("__method__", List(str("id"), Term.Local(0)))
    val children = list(List(
      Term.App(Term.Global("textNode"), List(id)),
      Term.App(Term.Global("textNode"), List(get)),
      Term.App(Term.Global("signalText"), List(Term.Local(0))),
    ))
    val root = Term.App(Term.Global("fragment"), List(children))
    Program(Nil, Term.Let(List(signal), Term.Seq(List(
      set,
      Term.App(Term.Global("emit"), List(root, str("out"))),
    ))))

  private def nativeUiDescriptorProgram(): Program =
    val refresh = Term.App(Term.Global("signal"), List(str("refresh"), Term.Lit(Const.CInt(0))))
    val url = Term.App(Term.Global("signal"), List(str("url"), str("/dynamic")))
    val body = Term.App(Term.Global("signal"), List(str("body"), str("{}")))
    val rows = Term.App(Term.Global("staticRowsSource"), List(Term.Ctor("Nil", Nil)))
    val column = Term.App(Term.Global("fieldColumn"), List(str("Id"), str("id")))
    val delete = Term.App(Term.Global("rowDeleteAction"), List(str("/delete"), str("id"), Term.Local(4)))
    val post = Term.App(Term.Global("rowPostAction"), List(str("Save"), str("POST"), str("/save"), str("id"), Term.Local(5)))
    val fetchLiteral = Term.App(Term.Global("fetchAction"), List(str("POST"), str("/submit"), Term.Local(4), Term.Local(6)))
    val fetchDynamic = Term.App(Term.Global("fetchActionTo"), List(str("POST"), Term.Local(5), Term.Local(4), Term.Local(6)))
    val table = Term.App(Term.Global("dataTableView"), List(
      Term.Local(3),
      list(List(Term.Local(2))),
      list(List(Term.Local(1), Term.Local(0))),
    ))
    Program(Nil, Term.Let(List(refresh, url, body, rows, column, delete, post), Term.Seq(List(
      fetchLiteral,
      fetchDynamic,
      Term.App(Term.Global("emit"), List(table, str("out"))),
    ))))

  private def nativeUiRawHtmlProgram(): Program =
    val attrs = Term.Prim("map.new", Nil)
    val events = Term.Prim("map.new", Nil)
    val putStyle = Term.Prim("map.put", List(Term.Local(0), str("style"), str("display:contents")))
    val putSource = Term.Prim("map.put", List(Term.Local(0), str("data-ssc-raw-html"), str("<strong data-x=\"ok\">raw</strong>")))
    val raw = Term.App(Term.Global("element"), List(str("span"), Term.Local(0), Term.Local(1), Term.Ctor("Nil", Nil)))
    Program(Nil, Term.Let(List(attrs, events), Term.Seq(List(
      putStyle,
      putSource,
      Term.App(Term.Global("emit"), List(raw, str("out"))),
    ))))

  private def nativeUiSessionProgram(): Program =
    val mutable = Term.App(Term.Global("signal"), List(str("message"), str("before")))
    val items = Term.App(Term.Global("signal"), List(str("items"), list(List(str("row")))))
    val computed = Term.App(Term.Global("computedSignal"), List(Term.Lam(0, str("computed"))))
    val keyed = Term.App(Term.Global("forKeyedView"), List(
      Term.Local(1),
      Term.Lam(1, Term.Local(0)),
      Term.Lam(1, Term.App(Term.Global("fragment"), List(list(List(
        Term.App(Term.Global("textNode"), List(Term.Local(0))),
        Term.App(Term.Global("fetchAction"), List(str("POST"), str("/post-root"), Term.Local(3), Term.Local(2))),
      ))))),
    ))
    val root = Term.App(Term.Global("fragment"), List(list(List(
      Term.App(Term.Global("signalText"), List(Term.Local(2))),
      Term.App(Term.Global("signalText"), List(Term.Local(0))),
      keyed,
    ))))
    Program(Nil, Term.Let(List(mutable, items, computed),
      Term.App(Term.Global("emit"), List(root, str("out")))))

  private val nativeUiSessionProbe = """
public enum SessionProbe {
    private static func fields(_ value: SscValue, _ tag: String) -> [SscValue] {
        guard case let .data(actual, fields) = value, actual == tag else { fatalError("expected \(tag)") }
        return fields
    }
    private static func closure(_ value: SscValue) -> SscClosure {
        guard case let .closure(result) = value else { fatalError("expected closure") }
        return result
    }
    private static func string(_ value: SscValue) -> String {
        guard case let .string(result) = value else { fatalError("expected string") }
        return result
    }
    private static func list(_ value: SscValue) -> [SscValue] {
        var current = value, result: [SscValue] = []
        while true {
            switch current {
            case let .data("Cons", fields) where fields.count == 2: result.append(fields[0]); current = fields[1]
            case .data("Nil", _): return result
            default: fatalError("expected list")
            }
        }
    }
    public static func run() {
        do {
            let session = try SscGeneratedProgram.makeNativeUiRoot()
            let abi = fields(session.root, "NativeUiAbi")
            let root = fields(abi[1], "NativeUiFragment")
            let children = list(root[0])
            let mutable = fields(fields(children[0], "NativeUiSignalText")[0], "NativeUiSignal")
            _ = try session.invoke(closure(mutable[4]), [.string("after")])
            let after = string(try session.invoke(closure(mutable[3]), []))
            let computed = fields(fields(children[1], "NativeUiSignalText")[0], "NativeUiSignal")
            let computedValue = string(try session.invoke(closure(computed[3]), []))
            let keyed = fields(children[2], "NativeUiForKeyed")
            let key = string(try session.invoke(closure(keyed[2]), [.string("row")]))
            let rendered = try session.invoke(closure(keyed[3]), [.string("row")])
            let renderTag: String
            if case let .data(tag, _) = rendered { renderTag = tag } else { fatalError("expected rendered data") }
            Swift.print("\(after)|\(computedValue)|\(key)|\(renderTag)")
        } catch { fatalError(String(describing: error)) }
    }
}
"""

  private val nativeUiRecoveryProbe = """
public enum SessionProbe {
    private static func program(_ entry: SscTerm) -> SscProgram {
        SscProgram(definitions: [], entry: entry, fieldLayouts: [:])
    }
    public static func run() {
        let host = NativeUiHost()
        let bad = program(.letBindings([
            .apply(.global("signal"), [.literal(.string("temp")), .literal(.string("dirty"))])
        ], .apply(.apply(.global("fragment"), []), [])))
        let first: String
        do {
            _ = try host.evaluate(bad)
            fatalError("expected failure")
        } catch { first = String(describing: error) }

        let good = program(.letBindings([
            .apply(.global("signal"), [.literal(.string("temp")), .literal(.string("clean"))])
        ], .apply(.global("emit"), [
            .apply(.global("signalText"), [.local(0)]),
            .literal(.string("out"))
        ])))
        do {
            let session = try host.evaluate(good)
            guard case let .data("NativeUiAbi", abi) = session.root,
                  case let .data(rootTag, _) = abi[1] else { fatalError("bad root") }
            Swift.print("\(first)|\(rootTag)")
        } catch { fatalError(String(describing: error)) }
    }
}
"""

  private val nativeUiDescriptorProbe = """
public enum SessionProbe {
    private static func fields(_ value: SscValue, _ tag: String) -> [SscValue] {
        guard case let .data(actual, fields) = value, actual == tag else { fatalError("expected \(tag)") }
        return fields
    }
    private static func string(_ value: SscValue) -> String {
        guard case let .string(result) = value else { fatalError("expected string") }
        return result
    }
    private static func list(_ value: SscValue) -> [SscValue] {
        var current = value, result: [SscValue] = []
        while true {
            switch current {
            case let .data("Cons", fields) where fields.count == 2: result.append(fields[0]); current = fields[1]
            case .data("Nil", _): return result
            default: fatalError("expected list")
            }
        }
    }
    private static func payload(_ value: SscValue) -> String {
        let fields = fields(value, "NativeUiRowPayload")
        return "\(string(fields[0])):\(string(list(fields[1])[0]))"
    }
    private static func action(_ value: SscValue) -> String {
        let fields = fields(value, "NativeUiRowAction")
        let request = self.fields(fields[2], "NativeUiFetchRequest")
        return "\(string(fields[0])):\(string(fields[1])):\(string(request[0])):\(string(request[1])):\(payload(fields[3]))"
    }
    public static func run() {
        do {
            let session = try SscGeneratedProgram.makeNativeUiRoot()
            let abi = fields(session.root, "NativeUiAbi")
            let table = fields(abi[1], "NativeUiDataTable")
            let source = fields(table[1], "NativeUiTableSource")
            let column = fields(list(table[2])[0], "NativeUiColumn")
            guard case let .map(options) = column[4], case .unit = options.get(.string("editAction")) else { fatalError("bad column options") }
            let actions = list(table[3])
            Swift.print("source=\(string(source[0])):\(string(source[2])):;column=\(string(column[0])):\(string(column[1])):\(string(column[2])):\(string(column[3])):edit=();delete=\(action(actions[0]));post=\(action(actions[1]))")
        } catch { fatalError(String(describing: error)) }
    }
}
"""

  private val nativeUiUnsupportedProbe = """
public enum SessionProbe {
    private static func fields(_ value: SscValue, _ tag: String) -> [SscValue] {
        guard case let .data(actual, fields) = value, actual == tag else { fatalError("expected \(tag)") }
        return fields
    }
    private static func string(_ value: SscValue) -> String {
        guard case let .string(result) = value else { fatalError("expected string") }
        return result
    }
    public static func run() {
        do {
            let session = try SscGeneratedProgram.makeNativeUiRoot()
            let abi = fields(session.root, "NativeUiAbi")
            let unsupported = fields(abi[1], "NativeUiUnsupported")
            let source = fields(unsupported[1], "NativeUiSourceRef")
            guard case let .int(line) = source[1], case let .int(column) = source[2] else { fatalError("bad source") }
            Swift.print("\(string(unsupported[0]))|\(string(source[0])):\(line):\(column):\(string(source[3]))|\(string(unsupported[2]))")
        } catch { fatalError(String(describing: error)) }
    }
}
"""

  private def swiftAvailable: Boolean =
    try
      val process = new ProcessBuilder("swift", "--version").start()
      process.waitFor() == 0
    catch case _: Exception => false

  private def runSwift(name: String, program: Program, programArgs: List[String] = Nil): String =
    val result = runSwiftResult(name, program, programArgs)
    assert(result.exit == 0, s"swift run $name failed (${result.exit}):\n${result.stderr}\n${result.stdout}")
    result.stdout

  private final case class SwiftResult(exit: Int, stdout: String, stderr: String)

  private def runSwiftResult(
      name: String,
      program: Program,
      programArgs: List[String] = Nil,
      probe: Option[String] = None,
  ): SwiftResult =
    val root = Files.createTempDirectory(s"ssc-swift-$name-")
    val errors = root.resolve("swift.stderr")
    try
      val requestedProduct = s"Ssc${name.capitalize}"
      val generated = SwiftBackend.generate(program, requestedProduct)
      val product = generated.executable
      val emitted = probe match
        case None => generated
        case Some(source) =>
          val mainPath = s"Sources/$product/main.swift"
          val files = generated.files.map {
            case (`mainPath`, _) => mainPath -> "import AppCore\n\nSessionProbe.run()\n"
            case other => other
          } :+ ("Sources/AppCore/SessionProbe.swift" -> source)
          SwiftPackage(files, generated.executable)
      emitted.writeTo(root)
      val process = new ProcessBuilder(
        (List("swift", "run", "--package-path", root.toString, "--quiet", product) ++ programArgs)*
      ).redirectError(errors.toFile).start()
      val stdout = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val exit = process.waitFor()
      val stderr = Files.readString(errors, StandardCharsets.UTF_8)
      SwiftResult(exit, stdout, stderr)
    finally deleteRecursively(root)

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val stream = Files.walk(root)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists)
      finally stream.close()
