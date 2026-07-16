package ssc.swift

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import org.scalatest.funsuite.AnyFunSuite

import ssc.{Arm, Const, HandlerDispatchShape, Program, Reader, Term}

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
      "Sources/AppCore/ContentModules.swift",
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
      "AppleApp/Native_AppApp.swift",
      "AppleApp/NativeUiStore.swift",
      "AppleApp/NativeUiRenderer.swift",
      "AppleApp/NativeUiStyles.swift",
      "AppleApp/NativeUiHtml.swift",
      "Sources/AppCore/ContentModules.swift",
    ))
    assert(ui.debugCli == "Native_AppCli")
    assert(ui.files.find(_._1.endsWith("NativeUiHost.swift")).exists(!_._2.contains("import SwiftUI")))
    assert(ui.files.find(_._1.endsWith("NativeUiStore.swift")).exists(_._2.contains("ObservableObject")))
    assert(ui.files.find(_._1.endsWith("NativeUiRenderer.swift")).exists(_._2.contains("NativeUiForKeyedView")))

  test("NativeUi application project and owned tree are deterministic across mode and product changes"):
    val metadata = SwiftAppMetadata("com.scalascript.native", "Native App", "1.2.3", "7")
    val first = SwiftBackend.generate(nativeUiProgram(), "Native-App", appMetadata = Some(metadata))
    val second = SwiftBackend.generate(nativeUiProgram(), "Native-App", appMetadata = Some(metadata))
    assert(first == second)
    val artifact = first.xcodeApp.getOrElse(fail("missing Xcode application artifact"))
    assert(artifact.project == "Native_App.xcodeproj")
    assert(artifact.appProduct == "Native_App.app")
    val paths = first.files.map(_._1)
    assert(paths.contains("AppleApp/Resources/Assets.xcassets/Contents.json"))
    assert(paths.contains("Native_App.xcodeproj/project.pbxproj"))
    assert(paths.contains("Native_App.xcodeproj/xcshareddata/xcschemes/Native_App.xcscheme"))
    val pbx = first.files.toMap.apply("Native_App.xcodeproj/project.pbxproj")
    assert(pbx.contains("objectVersion = 56"))
    assert(pbx.contains("com.apple.product-type.application"))
    assert(pbx.contains("Sources/AppCore/NativeUiHost.swift"))
    assert(!pbx.contains("Native_AppCli/main.swift"))

    val root = Files.createTempDirectory("ssc-swift-owned-")
    try
      first.writeTo(root)
      val userResource = root.resolve("AppleApp/Resources/user-owned.txt")
      Files.writeString(userResource, "keep", StandardCharsets.UTF_8)
      val manifest = Files.readString(root.resolve(".ssc-swift-generated.json"))
      assert(manifest.linesIterator.filter(_.trim.startsWith("\"")).toVector ==
        manifest.linesIterator.filter(_.trim.startsWith("\"")).toVector.sorted)
      SwiftBackend.generate(fixture("fact"), "Domain-Rename").writeTo(root)
      assert(!Files.exists(root.resolve("Native_App.xcodeproj")))
      assert(Files.readString(userResource) == "keep")
      val renamed = SwiftBackend.generate(
        nativeUiProgram(), "Renamed-App", appMetadata = Some(metadata.copy(bundleId = "com.scalascript.renamed")))
      renamed.writeTo(root)
      assert(Files.exists(root.resolve("Renamed_App.xcodeproj/project.pbxproj")))
      assert(!Files.exists(root.resolve("Sources/Domain_Rename/main.swift")))
      assert(Files.readString(userResource) == "keep")
    finally deleteRecursively(root)

    Vector("../escape", root.resolveSibling("absolute").toString).foreach { hostile =>
      val hostileRoot = Files.createTempDirectory("ssc-swift-hostile-")
      try
        Files.writeString(hostileRoot.resolve(".ssc-swift-generated.json"),
          "[\n  \"" + hostile.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n]\n")
        val error = intercept[IllegalArgumentException](
          SwiftBackend.generate(fixture("fact"), "Hostile").writeTo(hostileRoot))
        assert(error.getMessage.contains("invalid generated Swift ownership path"))
      finally deleteRecursively(hostileRoot)
    }

  test("generated Xcode application scheme builds real macOS and iOS Simulator apps"):
    assume(xcodebuildAvailable, "Xcode toolchain is not available")
    val root = Files.createTempDirectory("ssc-swift-xcode-")
    try
      val generated = SwiftBackend.generate(
        nativeUiProgram(),
        "NativeXcode",
        appMetadata = Some(SwiftAppMetadata("com.scalascript.native-xcode", "Native Xcode")),
      )
      generated.writeTo(root)
      val app = generated.xcodeApp.getOrElse(fail("missing Xcode application artifact"))
      val listResult = runProcess(root, "xcodebuild", "-list", "-project", app.project)
      assert(listResult._1 == 0, listResult._2)
      assert(listResult._2.contains(app.scheme))

      val macDerived = root.resolve("derived-macos")
      val mac = runProcess(root,
        "xcodebuild", "build", "-project", app.project, "-scheme", app.scheme,
        "-configuration", "Debug", "-destination", "platform=macOS",
        "-derivedDataPath", macDerived.toString,
        "CODE_SIGNING_ALLOWED=NO")
      assert(mac._1 == 0, mac._2)
      val macApp = macDerived.resolve(s"Build/Products/Debug/${app.appProduct}")
      assert(Files.isDirectory(macApp), s"missing $macApp\n${mac._2}")

      val iosDerived = root.resolve("derived-ios")
      val ios = runProcess(root,
        "xcodebuild", "build", "-project", app.project, "-scheme", app.scheme,
        "-configuration", "Debug", "-destination", "generic/platform=iOS Simulator",
        "-derivedDataPath", iosDerived.toString,
        "CODE_SIGNING_ALLOWED=NO", "CODE_SIGNING_REQUIRED=NO")
      assert(ios._1 == 0, ios._2)
      assert(Files.isDirectory(iosDerived.resolve(s"Build/Products/Debug-iphonesimulator/${app.appProduct}")), ios._2)
    finally deleteRecursively(root)

  test("SwiftUI renderer inventory covers every shipped lowerer tag and CSS property"):
    val sources = List(
      repoRoot.resolve("runtime/std/ui/lower.ssc"),
      repoRoot.resolve("runtime/std/ui/content.ssc"),
    ).map(Files.readString(_, StandardCharsets.UTF_8)).mkString("\n")
    val tagPattern = """element\("([^"]+)"""".r
    val cssPattern = """([a-z][a-z-]+):(?=\$|[0-9#a-z])""".r
    val tags = tagPattern.findAllMatchIn(sources).map(_.group(1)).toSet
    val css = cssPattern.findAllMatchIn(sources).map(_.group(1)).toSet - "toolkit"
    assert(tags.subsetOf(SwiftNativeUiApple.inventoryElementTags), "missing SwiftUI tag inventory")
    assert(css.subsetOf(SwiftNativeUiApple.inventoryCssProperties), "missing SwiftUI CSS inventory")
    SwiftNativeUiApple.inventoryElementTags.foreach(tag =>
      assert(SwiftNativeUiApple.rendererSource.contains("\"" + tag + "\""), "renderer tag missing"))
    SwiftNativeUiApple.inventoryCssProperties.foreach(property =>
      assert(SwiftNativeUiApple.stylesSource.contains("\"" + property + "\""), "renderer CSS missing"))

  test("unsupported globals and primitives fail during generation with their names"):
    val badGlobal = Program(Nil, Term.Global("host.secret"))
    val globalError = intercept[IllegalArgumentException](SwiftBackend.generate(badGlobal))
    assert(globalError.getMessage == "swift backend: unsupported global 'host.secret'")

    val badPrimitive = Program(Nil, Term.Prim("host.secret", Nil))
    val primitiveError = intercept[IllegalArgumentException](SwiftBackend.generate(badPrimitive))
    assert(primitiveError.getMessage == "swift backend: unsupported primitive 'host.secret'")

  test("real Swift preserves ordinary fallback semantics for handler decision markers"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val selected = Program(Nil, Term.Prim(
      HandlerDispatchShape.SelectedPrimitive,
      List(Term.Lit(Const.CUnit))))
    assert(runSwift("handlerMarkerSelected", selected).isEmpty)

    val missed = Program(Nil, Term.Prim(
      HandlerDispatchShape.MissPrimitive,
      List(Term.Lit(Const.CUnit))))
    val failure = runSwiftResult("handlerMarkerMiss", missed)
    assert(failure.exit != 0, failure.stdout)
    assert(failure.stderr.contains("match: no matching case"), failure.stderr)
    assert(!failure.stderr.toLowerCase.contains("unsupported primitive"), failure.stderr)
    assert(!failure.stderr.toLowerCase.contains("unknown primitive"), failure.stderr)

  test("Swift validation authorizes @-prefixed cell globals as lazily-vivified cells"):
    // Regression for the real gap found running examples/frontend/dashboard/dashboard.ssc
    // natively: `val refreshTick = Signal(0)` mutated via `refreshTick += 1` inside a
    // Button lambda lowers to cell.set(Global("@refreshTick"), …) with no matching
    // global.reg. The general interpreter (v2/src/Runtime.scala:686-689) auto-creates
    // `@`-cells on first access, so Swift generation must not reject them as unsupported.
    val cellGlobal = Program(Nil, Term.Seq(List(
      Term.Prim("cell.set", List(Term.Global("@refreshTick"), Term.Lit(Const.CInt(1)))),
      Term.Prim("cell.get", List(Term.Global("@refreshTick"))),
    )))
    assert(SwiftBackend.generate(cellGlobal).files.nonEmpty)
    // A bare (never-set) `@`-global reference is likewise authorized.
    assert(SwiftBackend.generate(Program(Nil, Term.Global("@fresh"))).files.nonEmpty)
    // But a non-`@` unbound global still fails.
    assert(intercept[IllegalArgumentException](
      SwiftBackend.generate(Program(Nil, Term.Global("host.secret")))).getMessage ==
      "swift backend: unsupported global 'host.secret'")

  test("Swift validation authorizes only unconditional entry-spine registrations"):
    val registered = Program(Nil,
      Term.Let(List(str("en")), Term.Seq(List(
        Term.Prim("global.reg", List(str("localeSignal"), Term.Local(0))),
        Term.Global("localeSignal"),
      ))))
    assert(SwiftBackend.generate(registered).files.nonEmpty)

    val hiddenInValue = Program(Nil, Term.Seq(List(
      Term.Prim("global.reg", List(str("outer"),
        Term.Prim("global.reg", List(str("ghost"), str("value"))))),
      Term.Global("ghost"),
    )))
    val hiddenError = intercept[IllegalArgumentException](SwiftBackend.generate(hiddenInValue))
    assert(hiddenError.getMessage == "swift backend: unsupported global 'ghost'")

    val hiddenInDef = Program(
      List(ssc.Def("never", Term.Lam(0,
        Term.Prim("global.reg", List(str("ghost"), str("value")))))),
      Term.Global("ghost"),
    )
    assert(intercept[IllegalArgumentException](SwiftBackend.generate(hiddenInDef)).getMessage ==
      "swift backend: unsupported global 'ghost'")

    val hiddenInBranch = Program(Nil, Term.Seq(List(
      Term.If(Term.Lit(Const.CBool(false)),
        Term.Prim("global.reg", List(str("ghost"), str("value"))),
        Term.Lit(Const.CUnit)),
      Term.Global("ghost"),
    )))
    assert(intercept[IllegalArgumentException](SwiftBackend.generate(hiddenInBranch)).getMessage ==
      "swift backend: unsupported global 'ghost'")

  test("real Swift try preserves values conversions and nested handler failures"):
    assume(swiftAvailable, "Swift toolchain is not available")
    def method(name: String, receiver: Term): Term =
      Term.Prim("__method__", List(str(name), receiver))
    def integer(value: Long): Term = Term.Lit(Const.CInt(value))
    def attempt(body: Term, handler: Term): Term =
      Term.Prim("__try__", List(Term.Lam(0, body), Term.Lam(1, handler)))
    def raise(value: Term): Term = Term.App(Term.Global("__throw__"), List(value))

    val boom = Term.Ctor("Boom", List(integer(7)))
    val nestedRethrow = attempt(
      attempt(raise(boom), raise(Term.Ctor("Again", List(Term.Local(0))))),
      Term.Local(0),
    )
    val nestedRuntime = attempt(
      attempt(raise(boom), method("toInt", str("bad"))),
      Term.Local(0),
    )
    val result = Term.Ctor("Tuple7", List(
      attempt(integer(7), str("handler-ran")),
      attempt(raise(boom), Term.Local(0)),
      attempt(method("toInt", str("md")), Term.Local(0)),
      attempt(method("toInt", str("\t 12 \r")), str("handler-ran")),
      attempt(method("toInt", str("\u00a012\u00a0")), Term.Local(0)),
      nestedRethrow,
      nestedRuntime,
    ))
    assert(runSwift("tryParity", Program(Nil, result)) ==
      "(7, Boom(7), \"String.toInt: invalid integer\", 12, \"String.toInt: invalid integer\", Again(Boom(7)), \"String.toInt: invalid integer\")")

  test("real Swift List method indexing and concatenation matrix is recoverable"):
    assume(swiftAvailable, "Swift toolchain is not available")
    def integer(value: Long): Term = Term.Lit(Const.CInt(value))
    def method(name: String, receiver: Term, args: Term*): Term =
      Term.Prim("__method__", str(name) :: receiver :: args.toList)
    def attempt(body: Term): Term =
      Term.Prim("__try__", List(Term.Lam(0, body), Term.Lam(1, Term.Local(0))))
    def concat(op: String, left: Term, right: Term): Term =
      Term.Prim("__arith__", List(str(op), left, right))

    val mixed = list(List(str("a"), integer(2)))
    val empty = list(Nil)
    val badTail = Term.Ctor("Cons", List(integer(1), Term.Ctor("BadTail", Nil)))
    val badCons = Term.Ctor("Cons", List(integer(1)))
    val badNil = Term.Ctor("Nil", List(integer(1)))
    val suffix = list(List(str("b")))
    val matrix = list(List(
      method("mkString", mixed),
      method("mkString", mixed, str("|")),
      method("mkString", mixed, str("<"), str("|"), str(">")),
      method("mkString", empty),
      method("mkString", empty, str("|")),
      method("mkString", empty, str("<"), str("|"), str(">")),
      Term.App(mixed, List(integer(1))),
      attempt(Term.App(mixed, List(integer(-1)))),
      attempt(Term.App(mixed, List(integer(2)))),
      attempt(Term.App(mixed, List(str("x")))),
      attempt(Term.App(mixed, Nil)),
      attempt(Term.App(badTail, List(integer(0)))),
      attempt(Term.App(badCons, List(integer(0)))),
      attempt(Term.App(badNil, List(integer(0)))),
      concat("+", mixed, suffix),
      concat("+", empty, mixed),
      concat("+", mixed, empty),
      concat("+", empty, empty),
      concat("++", empty, mixed),
      concat("++", mixed, empty),
      concat("++", mixed, suffix),
      concat("++", empty, empty),
      attempt(concat("+", mixed, str("not-list"))),
      attempt(concat("+", badTail, empty)),
      attempt(concat("+", badCons, empty)),
      attempt(concat("+", badNil, empty)),
      attempt(concat("+", mixed, badTail)),
      attempt(concat("+", mixed, badCons)),
      attempt(concat("+", mixed, badNil)),
      attempt(concat("++", mixed, str("not-list"))),
      attempt(concat("++", badTail, empty)),
      attempt(concat("++", badCons, empty)),
      attempt(concat("++", badNil, empty)),
      attempt(concat("++", mixed, badTail)),
      attempt(concat("++", mixed, badCons)),
      attempt(concat("++", mixed, badNil)),
    ))
    val output = runSwift("listParity", Program(Nil, matrix))
    val bounds = "\"app: list index out of bounds\""
    val indexType = "\"app: list index requires exactly one Int\""
    val malformedIndex = "\"app: malformed list\""
    val nonListConcat = "\"list concat: right operand must be List\""
    val malformedConcat = "\"list concat: malformed list\""
    assert(output.sliding(bounds.length).count(_ == bounds) == 2, output)
    assert(output.sliding(indexType.length).count(_ == indexType) == 2, output)
    assert(output.sliding(malformedIndex.length).count(_ == malformedIndex) == 3, output)
    assert(output.sliding(nonListConcat.length).count(_ == nonListConcat) == 2, output)
    assert(output.sliding(malformedConcat.length).count(_ == malformedConcat) == 12, output)
    assert(output ==
      "List(\"a2\", \"a|2\", \"<a|2>\", \"\", \"\", \"<>\", 2, " +
      s"$bounds, $bounds, $indexType, $indexType, $malformedIndex, $malformedIndex, $malformedIndex, " +
      "List(\"a\", 2, \"b\"), List(\"a\", 2), List(\"a\", 2), List(), " +
      "List(\"a\", 2), List(\"a\", 2), List(\"a\", 2, \"b\"), List(), " +
      s"$nonListConcat, $malformedConcat, $malformedConcat, $malformedConcat, $malformedConcat, $malformedConcat, $malformedConcat, " +
      s"$nonListConcat, $malformedConcat, $malformedConcat, $malformedConcat, $malformedConcat, $malformedConcat, $malformedConcat)", output)

  test("real Swift try never catches an unexpected host Error"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val probe = """
private enum ProbeHostError: Error { case boom }
private final class ProbeHost: SscRuntimeExtension {
    func bind(_ invoke: @escaping (SscClosure, [SscValue]) throws -> SscValue) {}
    func globals() throws -> [String: SscValue] {
        ["probe.host": .closure(SscClosure(arity: 0) { _ in throw ProbeHostError.boom })]
    }
    func apply(_ receiver: SscValue, _ arguments: [SscValue]) throws -> SscValue? { nil }
    func method(_ name: String, _ receiver: SscValue, _ arguments: [SscValue]) throws -> SscValue? { nil }
}
public enum SessionProbe {
    public static func run() {
        let body = SscTerm.apply(.global("probe.host"), [])
        let handler = SscTerm.lambda(1, .primitive("io.println", [.literal(.string("HANDLER-RAN"))]))
        let program = SscProgram(
            definitions: [],
            entry: .primitive("__try__", [.lambda(0, body), handler]),
            fieldLayouts: [:]
        )
        switch SscRuntime.session(program, nativeUiHost: ProbeHost()).evaluate() {
        case .success: fatalError("unexpected host error was swallowed")
        case let .failure(error): Swift.print(error.description)
        }
    }
}
"""
    val result = runSwiftResult("hostError", Program(Nil, Term.Lit(Const.CUnit)), probe = Some(probe))
    assert(result.exit == 0, result.stderr)
    assert(result.stdout.contains("unexpected host error: boom"), result.stdout)
    assert(!result.stdout.contains("HANDLER-RAN"), result.stdout)

  test("real Swift rejects unsupported List mkString invocation shapes"):
    assume(swiftAvailable, "Swift toolchain is not available")
    def integer(value: Long): Term = Term.Lit(Const.CInt(value))
    def method(receiver: Term, args: Term*): Term =
      Term.Prim("__method__", str("mkString") :: receiver :: args.toList)
    val mixed = list(List(str("a"), integer(2)))
    val wrongArity = runSwiftResult("mkStringArity", Program(Nil, method(mixed, str("["), str("]"))))
    val wrongType = runSwiftResult("mkStringType", Program(Nil, method(mixed, integer(7))))
    val nonList = runSwiftResult("mkStringReceiver", Program(Nil, method(integer(7), str("|"))))
    for result <- List(wrongArity, wrongType) do
      assert(result.exit != 0, result.stdout)
      assert(result.stderr.contains("method not found: mkString on List(\"a\", 2)"), result.stderr)
    assert(nonList.exit != 0, nonList.stdout)
    assert(nonList.stderr.contains("method not found: mkString on 7"), nonList.stderr)

  test("domain definitions named like UI globals do not switch package mode"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val identity = ssc.Def("signal", Term.Lam(1, Term.Local(0)))
    val domain = Program(List(identity), Term.App(Term.Global("signal"), List(str("domain"))))
    val generated = SwiftBackend.generate(domain, "DomainSignal")
    assert(generated.debugCli == "DomainSignal")
    assert(!generated.files.exists(_._1.endsWith("NativeUiHost.swift")))
    assert(runSwift("domainSignal", domain) == "\"domain\"")

  test("generated package declares the selected Apple deployment platform"):
    val program = fixture("fact")
    val macos = SwiftBackend.generate(program, "Fact", SwiftPlatform.MacOS).files.head._2
    val ios = SwiftBackend.generate(program, "Fact", SwiftPlatform.IOS).files.head._2
    assert(macos.contains("platforms: [.macOS(.v13)]"))
    assert(ios.contains("platforms: [.iOS(.v16)]"))

  test("NativeUi generation validates and embeds the sole normalized backend base URL"):
    val generated = SwiftBackend.generate(
      nativeUiSessionProgram(), "NativeBase", backendBaseUrl = Some("https://api.example.com/v1"))
    val program = generated.files.toMap.apply("Sources/AppCore/GeneratedProgram.swift")
    assert(program.contains("nativeUiBackendBaseURL"))
    assert(program.contains("https://api.example.com/v1/"))
    List(
      "ftp://api.example.com/v1",
      "https://user@api.example.com/v1",
      "https://api.example.com/v1?q=1",
      "https://api.example.com/v1#fragment",
      "/relative",
    ).foreach { invalid =>
      val error = intercept[IllegalArgumentException](
        SwiftBackend.generate(nativeUiSessionProgram(), "NativeBase", backendBaseUrl = Some(invalid)))
      assert(error.getMessage.contains("Swift --server-url"))
    }

  test("generated Swift request resolver executes absolute root-relative base-relative and rejection semantics"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    val root = Files.createTempDirectory("ssc-swiftui-request-url-")
    val compileErrors = root.resolve("compile.stderr")
    val runErrors = root.resolve("run.stderr")
    try
      val generated = SwiftBackend.generate(
        nativeUiSessionProgram(), "NativeRequestUrl", backendBaseUrl = Some("https://api.example/base"))
      generated.writeTo(root)
      val probe = root.resolve("RequestUrlProbe.swift")
      val binary = root.resolve("RequestUrlProbe")
      Files.writeString(probe, nativeUiRequestUrlProbe, StandardCharsets.UTF_8)
      val sources = generated.files.collect {
        case (path, _) if path.startsWith("Sources/AppCore/") || path == "AppleApp/NativeUiStore.swift" =>
          root.resolve(path).toString
      }
      val compile = new ProcessBuilder(
        (List(
          "xcrun", "swiftc", "-parse-as-library", "-swift-version", "6",
          "-strict-concurrency=complete", "-warnings-as-errors",
        ) ++ sources ++ List(probe.toString, "-o", binary.toString))*
      ).redirectError(compileErrors.toFile).start()
      val compileOut = new String(compile.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val compileExit = compile.waitFor()
      val compileErr = Files.readString(compileErrors, StandardCharsets.UTF_8)
      assert(compileExit == 0, s"Swift request URL compile failed ($compileExit):\n$compileErr\n$compileOut")

      val run = new ProcessBuilder(binary.toString).redirectError(runErrors.toFile).start()
      val stdout = new String(run.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val runExit = run.waitFor()
      val stderr = Files.readString(runErrors, StandardCharsets.UTF_8)
      assert(runExit == 0, s"Swift request URL probe failed ($runExit):\n$stderr\n$stdout")
      assert(stdout == "absolute|root|relative|rejected")
    finally deleteRecursively(root)

  test("generated Swift host rejects malformed payload constructors and row actions"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    val root = Files.createTempDirectory("ssc-swiftui-payload-validation-")
    val compileErrors = root.resolve("compile.stderr")
    val runErrors = root.resolve("run.stderr")
    try
      val generated = SwiftBackend.generate(nativeUiSessionProgram(), "NativePayloadValidation")
      generated.writeTo(root)
      val probe = root.resolve("PayloadValidationProbe.swift")
      val binary = root.resolve("PayloadValidationProbe")
      Files.writeString(probe, nativeUiPayloadValidationProbe, StandardCharsets.UTF_8)
      val sources = generated.files.collect {
        case (path, _) if path.startsWith("Sources/AppCore/") => root.resolve(path).toString
      }
      val compile = new ProcessBuilder(
        (List(
          "xcrun", "swiftc", "-parse-as-library", "-swift-version", "6",
          "-strict-concurrency=complete", "-warnings-as-errors",
        ) ++ sources ++ List(probe.toString, "-o", binary.toString))*
      ).redirectError(compileErrors.toFile).start()
      val compileOut = new String(compile.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val compileExit = compile.waitFor()
      val compileErr = Files.readString(compileErrors, StandardCharsets.UTF_8)
      assert(compileExit == 0, s"Swift payload validation compile failed ($compileExit):\n$compileErr\n$compileOut")

      val run = new ProcessBuilder(binary.toString).redirectError(runErrors.toFile).start()
      val stdout = new String(run.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val runExit = run.waitFor()
      val stderr = Files.readString(runErrors, StandardCharsets.UTF_8)
      assert(runExit == 0, s"Swift payload validation probe failed ($runExit):\n$stderr\n$stdout")
      assert(stdout == "constructors|actions|raw")
    finally deleteRecursively(root)

  test("native table ABI decodes five fields and rejects malformed payloads"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    assert(runNativeTableProbe("abi") == "abi")

  test("native table sources apply rowsPath fallback and retain exact states"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    assert(runNativeTableProbe("sources") == "sources")

  test("native table columns format dotted values deterministically"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    assert(runNativeTableProbe("columns") == "columns")

  test("native table row identity rejects missing empty compound and duplicates"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    assert(runNativeTableProbe("identity") == "identity")

  test("native table actions emit exact request bytes and lifecycle transitions"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    assert(runNativeTableProbe("actions") == "actions")

  test("native table generated Swift runs on macOS and typechecks for iOS"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    assert(runNativeTableProbe("apple", includeAppleSources = true) == "apple")

  test("semantic HTML table renders a real SwiftUI Grid and rejects malformed structure"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    val root = Files.createTempDirectory("ssc-swiftui-semantic-table-")
    val errors = root.resolve("swift.stderr")
    try
      val generated = SwiftBackend.generate(nativeUiSemanticTableProgram(), "NativeSemanticTable")
      generated.writeTo(root)
      val probe = root.resolve("SemanticTableProbe.swift")
      val binary = root.resolve("SemanticTableProbe")
      Files.writeString(probe, semanticTableProbe, StandardCharsets.UTF_8)
      val sources = generated.files.collect {
        case (path, _) if path.startsWith("Sources/AppCore/") ||
            (path.startsWith("AppleApp/") && !path.endsWith("App.swift")) =>
          root.resolve(path).toString
      }
      val compile = new ProcessBuilder((List(
        "xcrun", "swiftc", "-parse-as-library", "-swift-version", "6",
        "-strict-concurrency=complete", "-warnings-as-errors",
      ) ++ sources ++ List(probe.toString, "-o", binary.toString))*).redirectError(errors.toFile).start()
      val compileOut = new String(compile.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val compileExit = compile.waitFor()
      val compileErr = Files.readString(errors, StandardCharsets.UTF_8)
      assert(compileExit == 0, s"Swift semantic table compile failed ($compileExit):\n$compileErr\n$compileOut")
      val run = new ProcessBuilder(binary.toString).redirectError(errors.toFile).start()
      val stdout = new String(run.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val runExit = run.waitFor()
      val stderr = Files.readString(errors, StandardCharsets.UTF_8)
      assert(runExit == 0, s"Swift semantic table probe failed ($runExit):\n$stderr\n$stdout")
      assert(stdout == "header=1|body=2|cols=2|child=rejected|cell=rejected", stdout)
    finally deleteRecursively(root)

  test("reactive semantic attributes bind live SwiftUI modifiers and stay strict for unmapped or malformed signals"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    val root = Files.createTempDirectory("ssc-swiftui-reactive-attr-")
    val errors = root.resolve("swift.stderr")
    try
      val generated = SwiftBackend.generate(nativeUiReactiveAttrProgram(), "NativeReactiveAttr")
      generated.writeTo(root)
      val probe = root.resolve("ReactiveAttrProbe.swift")
      val binary = root.resolve("ReactiveAttrProbe")
      Files.writeString(probe, nativeUiReactiveAttrProbe, StandardCharsets.UTF_8)
      val sources = generated.files.collect {
        case (path, _) if path.startsWith("Sources/AppCore/") ||
            (path.startsWith("AppleApp/") && !path.endsWith("App.swift")) =>
          root.resolve(path).toString
      }
      val compile = new ProcessBuilder((List(
        "xcrun", "swiftc", "-parse-as-library", "-swift-version", "6",
        "-strict-concurrency=complete", "-warnings-as-errors",
      ) ++ sources ++ List(probe.toString, "-o", binary.toString))*).redirectError(errors.toFile).start()
      val compileOut = new String(compile.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val compileExit = compile.waitFor()
      val compileErr = Files.readString(errors, StandardCharsets.UTF_8)
      assert(compileExit == 0, s"Swift reactive-attr compile failed ($compileExit):\n$compileErr\n$compileOut")
      val run = new ProcessBuilder(binary.toString).redirectError(errors.toFile).start()
      val stdout = new String(run.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val runExit = run.waitFor()
      val stderr = Files.readString(errors, StandardCharsets.UTF_8)
      assert(runExit == 0, s"Swift reactive-attr probe failed ($runExit):\n$stderr\n$stdout")
      assert(stdout == "mapped=ok|unmapped=rejected|live=true->false|malformed=rejected", stdout)
    finally deleteRecursively(root)

  test("trusted HTML WebKit adapter isolates content navigation and lifecycle"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    assert(runNativeHtmlProbe() == "html")

  test("real swift run handles a Term nested far past Swift's 256 structure-nesting limit"):
    assume(swiftAvailable, "Swift toolchain is not available")
    // Regression for the real bug found compiling busi's actual production app.ssc
    // (3305 lines) as a native iOS app: the OLD codegen encoded the whole Program as
    // one nested Swift literal expression, and Swift 6's compiler enforces a hard
    // "structure nesting level exceeded maximum of 256" limit per expression. The
    // Program is now embedded as CoreIR S-expr TEXT and decoded via runtime
    // recursion instead, so a Term this deep must still compile and run correctly.
    // Depth 1000 clears the old 256 compile-time ceiling with a wide margin while
    // staying under a SEPARATE, pre-existing, unrelated limit found while choosing
    // this depth: Machine.evaluate/runTerm in SwiftRuntime.swift isn't stack-safe
    // for a single non-tail-nested Prim/App argument chain deeper than ~1300-1500
    // (real native stack overflow, confirmed via a real crash report) — tracked
    // separately, see SPRINT.md v2-swift-machine-deep-nontail-stack.
    def deepAdd(depth: Int): Term =
      if depth == 0 then Term.Lit(Const.CInt(0))
      else Term.Prim("i.add", List(Term.Lit(Const.CInt(1)), deepAdd(depth - 1)))
    val program = Program(Nil, deepAdd(1000))
    assert(runSwift("deep-nesting", program) == "1000")

  test("real swift run round-trips strings with quotes backslashes and control escapes through the CoreIR S-expr text embed"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val tricky = "a\"b\\cd"
    val program = Program(Nil, Term.Lit(Const.CStr(tricky)))
    val expected = "\"" + tricky.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    assert(runSwift("sexpr-string-escape", program) == expected)

  test("real swift run auto-vivifies @-prefixed cell globals like the general interpreter"):
    assume(swiftAvailable, "Swift toolchain is not available")
    // The dashboard.ssc `refreshTick += 1` shape: set an unregistered `@`-cell, then
    // read-modify-write it, matching v2/src/Runtime.scala auto-vivify semantics.
    def int(value: Long) = Term.Lit(Const.CInt(value))
    def tick = Term.Global("@tick")
    val program = Program(Nil, Term.Seq(List(
      Term.Prim("cell.set", List(tick, int(41))),
      Term.Prim("cell.set", List(tick, Term.Prim("i.add", List(Term.Prim("cell.get", List(tick)), int(1))))),
      Term.Prim("cell.get", List(tick)),
    )))
    assert(runSwift("cell-global", program) == "42")

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

  test("real swift rejects a second one-shot resume outside user try and before the suffix"):
    assume(swiftAvailable, "Swift toolchain is not available")
    def int(value: Long) = Term.Lit(Const.CInt(value))
    def str(value: String) = Term.Lit(Const.CStr(value))
    def resume(value: Long) = Term.App(Term.Local(0), List(int(value)))
    val chooseBody = Term.Seq(List(
      resume(10),
      resume(20),
      Term.Prim("io.println", List(str("LOSING-SUFFIX"))),
      int(0),
    ))
    val handler = Term.Lam(1, Term.Match(
      Term.Local(0),
      List(
        Arm("Choose", 2, chooseBody),
        Arm("Return", 1, Term.Local(0)),
      ),
      None,
    ))
    val performed = Term.Prim("effect.perform.oneshot", List(str("Demo"), str("Choose"), int(1)))
    val lifted = Term.Prim("__arith__", List(str("+"), performed, int(0)))
    val handled = Term.Prim("effect.handle", List(lifted, handler))
    val attempted = Term.Prim("__try__", List(
      Term.Lam(0, handled),
      Term.Lam(1, Term.Prim("io.println", List(str("CAUGHT")))),
    ))
    val result = runSwiftResult("oneShotFailure", Program(Nil, attempted))
    assert(result.exit != 0)
    assert(result.stderr.contains(
      "error [ONESHOT_VIOLATION]: One-shot violation: Demo.Choose resumed more than once"), result.stderr)
    assert(!result.stdout.contains("LOSING-SUFFIX"), result.stdout)
    assert(!result.stdout.contains("CAUGHT"), result.stdout)

  test("real swift one-shot claim is linearizable across concurrent native invocations"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val operation = Term.Prim("effect.perform.oneshot", List(
      Term.Lit(Const.CStr("Concurrent")),
      Term.Lit(Const.CStr("claim")),
    ))
    val probe = """
import Foundation
import Dispatch

private final class ContinuationBox: @unchecked Sendable {
    let invoke: ([SscValue]) throws -> SscValue
    init(_ continuation: SscClosure) {
        guard let native = continuation.native else { fatalError("expected native one-shot continuation") }
        invoke = native
    }
}

private final class ClaimResults: @unchecked Sendable {
    private let lock = NSLock()
    private var wins = 0
    private var losses = 0
    private var others = 0
    private var identities = Set<String>()
    private var diagnostics = Set<String>()

    func recordWin() {
        lock.lock(); defer { lock.unlock() }
        wins += 1
    }

    func recordLoss(_ failure: SscControlRunFailure) {
        lock.lock(); defer { lock.unlock() }
        losses += 1
        diagnostics.insert(failure.rendered)
        if case let .alreadyResumed(operation) = failure.rejection {
            identities.insert("\(operation.effect.value).\(operation.name)")
        }
    }

    func recordOther() {
        lock.lock(); defer { lock.unlock() }
        others += 1
    }

    func summary() -> String {
        lock.lock(); defer { lock.unlock() }
        return "wins=\(wins);losses=\(losses);others=\(others);ids=\(identities.sorted().joined(separator: ","));diagnostics=\(diagnostics.sorted().joined(separator: ","))"
    }
}

public enum SessionProbe {
    public static func run() {
        let program = sscDecodeProgram(sscProgramSExpr, fieldLayouts: sscProgramFieldLayouts)
        let value = SscRuntime.evaluate(program)
        guard case let .data("Op", fields) = value,
              fields.count == 3,
              case let .closure(continuation) = fields[2] else {
            fatalError("expected one-shot Op")
        }
        let box = ContinuationBox(continuation)
        let results = ClaimResults()
        DispatchQueue.concurrentPerform(iterations: 64) { index in
            do {
                _ = try box.invoke([.int(Int64(index))])
                results.recordWin()
            } catch let failure as SscControlRunFailure {
                results.recordLoss(failure)
            } catch {
                results.recordOther()
            }
        }
        Swift.print(results.summary())
    }
}
"""
    val result = runSwiftResult("oneShotConcurrent", Program(Nil, operation), probe = Some(probe))
    assert(result.exit == 0, result.stderr)
    assert(result.stdout ==
      "wins=1;losses=63;others=0;ids=Concurrent.claim;diagnostics=" +
      "error [ONESHOT_VIOLATION]: One-shot violation: Concurrent.claim resumed more than once", result.stdout)

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
      "source=static::;key=account.id;column=text:Id:id::edit=();delete=delete:Delete:POST:/delete:field:id;post=post:Save:POST:/save:field:id")
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

  test("generated NativeUi AppleApp sources typecheck with real SwiftUI"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    val root = Files.createTempDirectory("ssc-swiftui-typecheck-")
    val errors = root.resolve("swiftui.stderr")
    try
      val generated = SwiftBackend.generate(nativeUiSessionProgram(), "NativeTypecheck")
      generated.writeTo(root)
      val sources = generated.files.collect {
        case (path, _) if path.startsWith("Sources/AppCore/") || path.startsWith("AppleApp/") =>
          root.resolve(path).toString
      }
      val process = new ProcessBuilder((List("xcrun", "swiftc", "-typecheck", "-parse-as-library") ++ sources)*)
        .redirectError(errors.toFile)
        .start()
      val stdout = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val exit = process.waitFor()
      val stderr = Files.readString(errors, StandardCharsets.UTF_8)
      assert(exit == 0, s"SwiftUI typecheck failed ($exit):\n$stderr\n$stdout")
    finally deleteRecursively(root)

  test("generated NativeUiStore publishes each reactive dependency once under real Swift"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    val root = Files.createTempDirectory("ssc-swiftui-store-")
    val compileErrors = root.resolve("compile.stderr")
    val runErrors = root.resolve("run.stderr")
    try
      val generated = SwiftBackend.generate(nativeUiObservationProgram(), "NativeObservation")
      generated.writeTo(root)
      val probe = root.resolve("ObservationProbe.swift")
      val binary = root.resolve("ObservationProbe")
      Files.writeString(probe, nativeUiObservationProbe, StandardCharsets.UTF_8)
      val sources = generated.files.collect {
        case (path, _) if path.startsWith("Sources/AppCore/") || path == "AppleApp/NativeUiStore.swift" =>
          root.resolve(path).toString
      }
      val compile = new ProcessBuilder(
        (List("xcrun", "swiftc", "-parse-as-library") ++ sources ++ List(probe.toString, "-o", binary.toString))*
      ).redirectError(compileErrors.toFile).start()
      val compileOut = new String(compile.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val compileExit = compile.waitFor()
      val compileErr = Files.readString(compileErrors, StandardCharsets.UTF_8)
      assert(compileExit == 0, s"Swift store compile failed ($compileExit):\n$compileErr\n$compileOut")

      val run = new ProcessBuilder(binary.toString).redirectError(runErrors.toFile).start()
      val stdout = new String(run.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val runExit = run.waitFor()
      val stderr = Files.readString(runErrors, StandardCharsets.UTF_8)
      assert(runExit == 0, s"Swift store probe failed ($runExit):\n$stderr\n$stdout")
      assert(stdout == "stable|4|4|3|cycle")
    finally deleteRecursively(root)

  test("real Swift URLProtocol gates fetch signal and action lifecycle"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    val root = Files.createTempDirectory("ssc-swiftui-fetch-")
    val compileErrors = root.resolve("compile.stderr")
    val runErrors = root.resolve("run.stderr")
    try
      val generated = SwiftBackend.generate(nativeUiAsyncProgram(), "NativeAsync")
      generated.writeTo(root)
      val probe = root.resolve("AsyncProbe.swift")
      val binary = root.resolve("AsyncProbe")
      Files.writeString(probe, nativeUiAsyncProbe, StandardCharsets.UTF_8)
      val sources = generated.files.collect {
        case (path, _) if path.startsWith("Sources/AppCore/") || path == "AppleApp/NativeUiStore.swift" =>
          root.resolve(path).toString
      }
      val compile = new ProcessBuilder(
        (List(
          "xcrun", "swiftc", "-parse-as-library", "-strict-concurrency=complete",
          "-warnings-as-errors",
        ) ++ sources ++ List(probe.toString, "-o", binary.toString))*
      ).redirectError(compileErrors.toFile).start()
      val compileOut = new String(compile.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val compileExit = compile.waitFor()
      val compileErr = Files.readString(compileErrors, StandardCharsets.UTF_8)
      assert(compileExit == 0, s"Swift async compile failed ($compileExit):\n$compileErr\n$compileOut")

      val run = new ProcessBuilder(binary.toString).redirectError(runErrors.toFile).start()
      val stdout = new String(run.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val runExit = run.waitFor()
      val stderr = Files.readString(runErrors, StandardCharsets.UTF_8)
      assert(runExit == 0, s"Swift async probe failed ($runExit):\n$stderr\n$stdout")
      assert(stdout == "family|replace|snapshot|bounded|cancel|ordered|failure|form|projected|shared|open-json")
    finally deleteRecursively(root)

  test("surviving keyed owner cancels disposed action capability without view lifecycle"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    val root = Files.createTempDirectory("ssc-swiftui-keyed-action-")
    val compileErrors = root.resolve("compile.stderr")
    val runErrors = root.resolve("run.stderr")
    try
      val generated = SwiftBackend.generate(nativeUiKeyedActionLifecycleProgram(), "NativeKeyedAction")
      generated.writeTo(root)
      val probe = root.resolve("KeyedActionProbe.swift")
      val binary = root.resolve("KeyedActionProbe")
      Files.writeString(probe, nativeUiKeyedActionLifecycleProbe, StandardCharsets.UTF_8)
      val sources = generated.files.collect {
        case (path, _) if path.startsWith("Sources/AppCore/") || path == "AppleApp/NativeUiStore.swift" =>
          root.resolve(path).toString
      }
      val compile = new ProcessBuilder(
        (List(
          "xcrun", "swiftc", "-parse-as-library", "-strict-concurrency=complete",
          "-warnings-as-errors",
        ) ++ sources ++ List(probe.toString, "-o", binary.toString))*
      ).redirectError(compileErrors.toFile).start()
      val compileOut = new String(compile.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val compileExit = compile.waitFor()
      val compileErr = Files.readString(compileErrors, StandardCharsets.UTF_8)
      assert(compileExit == 0, s"Swift keyed action compile failed ($compileExit):\n$compileErr\n$compileOut")

      val run = new ProcessBuilder(binary.toString).redirectError(runErrors.toFile).start()
      val stdout = new String(run.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val runExit = run.waitFor()
      val stderr = Files.readString(runErrors, StandardCharsets.UTF_8)
      assert(runExit == 0, s"Swift keyed action probe failed ($runExit):\n$stderr\n$stdout")
      assert(stdout == "disposed|late-inert|fresh|unsafe|bounded")
    finally deleteRecursively(root)

  test("surviving keyed fetch restarts only when structural metadata changes"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    val root = Files.createTempDirectory("ssc-swiftui-keyed-fetch-")
    val compileErrors = root.resolve("compile.stderr")
    val runErrors = root.resolve("run.stderr")
    try
      val generated = SwiftBackend.generate(nativeUiKeyedFetchMetadataProgram(), "NativeKeyedFetch")
      generated.writeTo(root)
      val probe = root.resolve("KeyedFetchProbe.swift")
      val binary = root.resolve("KeyedFetchProbe")
      Files.writeString(probe, nativeUiKeyedFetchMetadataProbe, StandardCharsets.UTF_8)
      val sources = generated.files.collect {
        case (path, _) if path.startsWith("Sources/AppCore/") || path == "AppleApp/NativeUiStore.swift" =>
          root.resolve(path).toString
      }
      val compile = new ProcessBuilder(
        (List(
          "xcrun", "swiftc", "-parse-as-library", "-strict-concurrency=complete",
          "-warnings-as-errors",
        ) ++ sources ++ List(probe.toString, "-o", binary.toString))*
      ).redirectError(compileErrors.toFile).start()
      val compileOut = new String(compile.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val compileExit = compile.waitFor()
      val compileErr = Files.readString(compileErrors, StandardCharsets.UTF_8)
      assert(compileExit == 0, s"Swift keyed fetch compile failed ($compileExit):\n$compileErr\n$compileOut")

      val run = new ProcessBuilder(binary.toString).redirectError(runErrors.toFile).start()
      val stdout = new String(run.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val runExit = run.waitFor()
      val stderr = Files.readString(runErrors, StandardCharsets.UTF_8)
      assert(runExit == 0, s"Swift keyed fetch probe failed ($runExit):\n$stderr\n$stdout")
      assert(stdout == "identical|literal-restart|late-inert|ref-restart|coalesced|bounded")
    finally deleteRecursively(root)

  test("persisted and online signals own Apple adapters by subscription lifecycle"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    val root = Files.createTempDirectory("ssc-swiftui-platform-signals-")
    val compileErrors = root.resolve("compile.stderr")
    val runErrors = root.resolve("run.stderr")
    try
      val generated = SwiftBackend.generate(nativeUiPlatformSignalsProgram(), "NativePlatformSignals")
      generated.writeTo(root)
      val probe = root.resolve("PlatformSignalsProbe.swift")
      val binary = root.resolve("PlatformSignalsProbe")
      Files.writeString(probe, nativeUiPlatformSignalsProbe, StandardCharsets.UTF_8)
      val sources = generated.files.collect {
        case (path, _) if path.startsWith("Sources/AppCore/") || path == "AppleApp/NativeUiStore.swift" =>
          root.resolve(path).toString
      }
      val compile = new ProcessBuilder(
        (List(
          "xcrun", "swiftc", "-parse-as-library", "-swift-version", "6",
          "-strict-concurrency=complete", "-warnings-as-errors",
        ) ++ sources ++ List(probe.toString, "-o", binary.toString))*
      ).redirectError(compileErrors.toFile).start()
      val compileOut = new String(compile.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val compileExit = compile.waitFor()
      val compileErr = Files.readString(compileErrors, StandardCharsets.UTF_8)
      assert(compileExit == 0, s"Swift platform signals compile failed ($compileExit):\n$compileErr\n$compileOut")

      val run = new ProcessBuilder(binary.toString).redirectError(runErrors.toFile).start()
      val stdout = new String(run.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val runExit = run.waitFor()
      val stderr = Files.readString(runErrors, StandardCharsets.UTF_8)
      assert(runExit == 0, s"Swift platform signals probe failed ($runExit):\n$stderr\n$stdout")
      assert(stdout == "defaults|rollback|first-last|main-actor|restart")
    finally deleteRecursively(root)

  test("failed keyed Store batch drops provisional writes revisions and derived cache changes"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    val root = Files.createTempDirectory("ssc-swiftui-store-rollback-")
    val compileErrors = root.resolve("compile.stderr")
    val runErrors = root.resolve("run.stderr")
    try
      val generated = SwiftBackend.generate(nativeUiStoreRollbackProgram(), "NativeStoreRollback")
      generated.writeTo(root)
      val probe = root.resolve("StoreRollbackProbe.swift")
      val binary = root.resolve("StoreRollbackProbe")
      Files.writeString(probe, nativeUiStoreRollbackProbe, StandardCharsets.UTF_8)
      val sources = generated.files.collect {
        case (path, _) if path.startsWith("Sources/AppCore/") || path == "AppleApp/NativeUiStore.swift" =>
          root.resolve(path).toString
      }
      val compile = new ProcessBuilder(
        (List("xcrun", "swiftc", "-parse-as-library") ++ sources ++ List(probe.toString, "-o", binary.toString))*
      ).redirectError(compileErrors.toFile).start()
      val compileOut = new String(compile.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val compileExit = compile.waitFor()
      val compileErr = Files.readString(compileErrors, StandardCharsets.UTF_8)
      assert(compileExit == 0, s"Swift rollback compile failed ($compileExit):\n$compileErr\n$compileOut")
      val run = new ProcessBuilder(binary.toString).redirectError(runErrors.toFile).start()
      val stdout = new String(run.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val runExit = run.waitFor()
      val stderr = Files.readString(runErrors, StandardCharsets.UTF_8)
      assert(runExit == 0, s"Swift rollback probe failed ($runExit):\n$stderr\n$stdout")
      assert(stdout == "rollback|before|before|0|0")
    finally deleteRecursively(root)

  test("deferred fetch and invalid native inventory are executable sourced diagnostics"):
    assume(xcrunSwiftAvailable, "xcrun Swift toolchain is not available")
    val root = Files.createTempDirectory("ssc-swiftui-unsupported-")
    val compileErrors = root.resolve("compile.stderr")
    val runErrors = root.resolve("run.stderr")
    try
      val generated = SwiftBackend.generate(nativeUiDeferredProgram(), "NativeDeferred")
      generated.writeTo(root)
      val probe = root.resolve("DeferredProbe.swift")
      val binary = root.resolve("DeferredProbe")
      Files.writeString(probe, nativeUiDeferredProbe, StandardCharsets.UTF_8)
      val sources = generated.files.collect {
        case (path, _) if path.startsWith("Sources/AppCore/") ||
            (path.startsWith("AppleApp/") && !path.endsWith("App.swift")) =>
          root.resolve(path).toString
      }
      val compile = new ProcessBuilder(
        (List(
          "xcrun", "swiftc", "-parse-as-library", "-swift-version", "6",
          "-strict-concurrency=complete", "-warnings-as-errors",
        ) ++ sources ++ List(probe.toString, "-o", binary.toString))*
      ).redirectError(compileErrors.toFile).start()
      val compileOut = new String(compile.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val compileExit = compile.waitFor()
      val compileErr = Files.readString(compileErrors, StandardCharsets.UTF_8)
      assert(compileExit == 0, s"Swift deferred compile failed ($compileExit):\n$compileErr\n$compileOut")
      val run = new ProcessBuilder(binary.toString).redirectError(runErrors.toFile).start()
      val stdout = new String(run.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val runExit = run.waitFor()
      val stderr = Files.readString(runErrors, StandardCharsets.UTF_8)
      assert(runExit == 0, s"Swift deferred probe failed ($runExit):\n$stderr\n$stdout")
      assert(stdout == "fetch-live|css-value|attribute|behavior|source")
    finally deleteRecursively(root)

  test("real Swift keyed reconciliation preserves moves and disposes deleted component state"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val result = runSwiftResult(
      "nativeUiKeyedLifecycle",
      nativeUiKeyedLifecycleProgram(),
      probe = Some(nativeUiKeyedLifecycleProbe),
    )
    assert(result.exit == 0, result.stderr)
    assert(result.stdout == "dirty-a|dirty-b|fresh-a|rollback-ok")

  test("real Swift anonymous derived signals retain transactional owner identity"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val result = runSwiftResult(
      "nativeUiAnonymousLifecycle",
      nativeUiProgram(),
      probe = Some(nativeUiAnonymousLifecycleProbe),
    )
    assert(result.exit == 0, result.stderr)
    assert(result.stdout ==
      "locale|kind|named|failed-allocation|refresh|reorder|rollback|retry|delete|nested|reset")

  test("real Swift keyed reconciliation reference-counts shared component scopes"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val result = runSwiftResult(
      "nativeUiSharedScope",
      nativeUiSharedScopeProgram(),
      probe = Some(nativeUiSharedScopeProbe),
    )
    assert(result.exit == 0, result.stderr)
    assert(result.stdout == "shared-live|shared-disposed|fresh")

  test("real Swift node-bound owner hints survive reversed construction and tree order"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val result = runSwiftResult(
      "nativeUiOwnerHints",
      nativeUiOwnerHintsProgram(),
      probe = Some(nativeUiOwnerHintsProbe),
    )
    assert(result.exit == 0, result.stderr)
    assert(result.stdout == "component|occurrence-0|occurrence-1")

  test("real Swift JSON facade covers fallback Unicode numbers keys and bounded failures"):
    assume(swiftAvailable, "Swift toolchain is not available")
    def app(name: String, args: Term*): Term = Term.App(Term.Global(name), args.toList)
    def method(name: String, receiver: Term, args: Term*): Term =
      Term.Prim("__method__", str(name) :: receiver :: args.toList)
    def attempt(body: Term): Term =
      Term.Prim("__try__", List(Term.Lam(0, body), Term.Lam(1, Term.Local(0))))
    def codeUnits(text: String): Term = list(text.toList.map(ch => Term.Lit(Const.CInt(ch.toLong))))
    def jsonString(text: String): Term = Term.Ctor("JsonCoreString", List(codeUnits(text)))
    def jsonNumber(text: String): Term = Term.Ctor("JsonCoreNumber", List(str(text)))
    def wrap(core: Term): Term = app("__jsonCoreWrap", core)

    val fallbackRaw = method("raw", wrap(jsonString("A😀")))
    val mapRaw = Term.Let(List(Term.Prim("map.new", Nil)), Term.Seq(List(
      Term.Prim("map.put", List(Term.Local(0), Term.Lit(Const.CBool(true)), str("b"))),
      Term.Prim("map.put", List(Term.Local(0), Term.Lit(Const.CInt(1)), str("i"))),
      method("raw", wrap(app("__jsonCoreEncodeValue", Term.Local(0)))),
    )))
    val badSurrogate = Term.Ctor("JsonCoreString", List(list(List(Term.Lit(Const.CInt(0xd83d))))))
    val body = Term.Let(List(fallbackRaw, mapRaw), Term.Seq(List(
      app("__jsonCoreInstallRenderer", Term.Lam(1, str("SELF"))),
      Term.Ctor("Tuple8", List(
        Term.Local(1),
        Term.Local(0),
        method("optInt", wrap(jsonNumber("18446744073709551617"))),
        method("optInt", wrap(jsonNumber("1.0"))),
        method("optInt", wrap(jsonString("1e3"))),
        method("raw", wrap(jsonNumber("7"))),
        attempt(wrap(badSurrogate)),
        attempt(app("__jsonCoreEncodeValue", Term.Lit(Const.CFloat(Double.NaN)))),
      )),
    )))
    val output = runSwift("jsonFacadeEdges", Program(Nil, body))
    assert(output.contains("\\\\ud83d\\\\ude00"), output)
    assert(!output.contains("\\\\u1f600"), output)
    assert(output.contains("BoolV(true)"), output)
    assert(output.contains("IntV(1)"), output)
    assert(output.contains("Some(1), Some(1), Some(1000), \"SELF\""), output)
    assert(output.contains("invalid JsonCore UTF-16 surrogate pair"), output)
    assert(output.contains("jsonStringify cannot encode NaN or Infinity"), output)

  test("real Swift implicit Return fallback does not swallow handler match failures"):
    assume(swiftAvailable, "Swift toolchain is not available")
    val nestedFailure = Term.Match(Term.Local(0), Nil, None)
    val handler = Term.Lam(1, Term.Match(
      Term.Local(0),
      Nil,
      Some(nestedFailure),
    ))
    val handled = Term.Prim("effect.handle", List(Term.Ctor("Bad", Nil), handler))

    val result = runSwiftResult("returnHandlerFailure", Program(Nil, handled))
    assert(result.exit != 0)
    assert(result.stderr.contains("match: no arm for Return(Bad)"), result.stderr)
    assert(result.stdout.isEmpty, result.stdout)

  test("real Swift NativeUi normalizes association maps and sources malformed failures"):
    assume(swiftAvailable, "Swift toolchain is not available")
    def pair(key: Term, value: Term): Term = Term.Ctor("Tuple2", List(key, value))
    def source = Term.Ctor("NativeUiSourceRef", List(
      str("association.ssc"), Term.Lit(Const.CInt(12)), Term.Lit(Const.CInt(7)), str("element")))
    def element(attrs: Term, events: Term, sourced: Boolean = false): Term =
      val args = List(str("div"), attrs, events, list(Nil))
      if sourced then Term.App(Term.Global("__ssc_nativeui_v1.element"), str("association:element") :: source :: args)
      else Term.App(Term.Global("element"), args)
    def attempt(body: Term): Term =
      Term.Prim("__try__", List(Term.Lam(0, body), Term.Lam(1, Term.Local(0))))

    val duplicateAttrs = list(List(pair(str("style"), str("first")), pair(str("style"), str("last"))))
    val duplicateEvents = list(List(pair(str("tap"), str("first")), pair(str("tap"), str("last"))))
    val directAttrs = Term.Prim("__mk_map__", List(pair(str("style"), str("direct"))))
    val directEvents = Term.Prim("__mk_map__", List(pair(str("tap"), str("direct"))))
    val positiveRoot = Term.App(Term.Global("fragment"), List(list(List(
      element(duplicateAttrs, directEvents),
      element(directAttrs, duplicateEvents),
    ))))
    val positive = Program(Nil, Term.App(Term.Global("emit"), List(positiveRoot, str("out"))))
    val probe = """
public enum SessionProbe {
    private static func list(_ value: SscValue) -> [SscValue] {
        var current = value
        var result: [SscValue] = []
        while true {
            switch current {
            case let .data("Cons", fields) where fields.count == 2:
                result.append(fields[0]); current = fields[1]
            case let .data("Nil", fields) where fields.isEmpty: return result
            default: fatalError("expected proper list")
            }
        }
    }
    private static func elementMaps(_ value: SscValue) -> (SscMap, SscMap) {
        guard case let .data("NativeUiElement", fields) = value, fields.count == 5,
              case let .map(attrs) = fields[2], case let .map(events) = fields[3] else {
            fatalError("source map/list reached Apple ABI without normalization")
        }
        return (attrs, events)
    }
    public static func run() {
        do {
            let session = try SscGeneratedProgram.makeNativeUiRoot()
            guard case let .data("NativeUiAbi", abi) = session.root, abi.count == 3,
                  case let .data("NativeUiFragment", root) = abi[1], root.count == 1 else {
                fatalError("expected fragment root")
            }
            let children = list(root[0])
            guard children.count == 2 else { fatalError("expected two elements") }
            let first = elementMaps(children[0]), second = elementMaps(children[1])
            guard case let .string(firstStyle)? = first.0.get(.string("style")),
                  case let .string(firstTap)? = first.1.get(.string("tap")),
                  case let .string(secondStyle)? = second.0.get(.string("style")),
                  case let .string(secondTap)? = second.1.get(.string("tap")) else {
                fatalError("normalized map values missing")
            }
            Swift.print("\(firstStyle):\(firstTap):\(first.0.entries.count):\(first.1.entries.count)|\(secondStyle):\(secondTap):\(second.0.entries.count):\(second.1.entries.count)")
        } catch { fatalError(String(describing: error)) }
    }
}
"""
    val normalized = runSwiftResult("associationMap", positive, probe = Some(probe))
    assert(normalized.exit == 0, normalized.stderr)
    assert(normalized.stdout == "last:direct:1:1|direct:last:1:1", normalized.stdout)

    val validPair = pair(str("style"), str("ok"))
    val malformed = Term.Ctor("Cons", List(validPair, str("bad-tail")))
    val nonTuple = list(List(str("not-tuple")))
    val wrongArity = list(List(Term.Ctor("Tuple2", List(str("style")))))
    val nonStringKey = list(List(pair(Term.Lit(Const.CInt(1)), str("value"))))
    val nonPortableCell = list(List(pair(str("style"), Term.Prim("cell.new", List(str("mutable"))))))
    val nonPortableArray = list(List(pair(str("style"), Term.Prim("__mk_arr__", List(str("mutable"))))))
    val errors = Term.Ctor("Tuple6", List(
      attempt(element(malformed, directEvents, sourced = true)),
      attempt(element(nonTuple, directEvents, sourced = true)),
      attempt(element(wrongArity, directEvents, sourced = true)),
      attempt(element(nonStringKey, directEvents, sourced = true)),
      attempt(element(nonPortableCell, directEvents, sourced = true)),
      attempt(element(nonPortableArray, directEvents, sourced = true)),
    ))
    val fallbackRoot = Term.App(Term.Global("fragment"), List(list(Nil)))
    val negative = Program(Nil, Term.Let(List(errors), Term.Seq(List(
      Term.Prim("io.println", List(Term.Local(0))),
      Term.App(Term.Global("emit"), List(fallbackRoot, str("out"))),
    ))))
    val output = runSwift("associationMapErrors", negative)
    assert(output.contains("expected a proper List[Tuple2(String, Value)]"), output)
    assert(output.sliding("expected Tuple2(String, Value)".length)
      .count(_ == "expected Tuple2(String, Value)") == 2, output)
    assert(output.contains("requires a String key"), output)
    assert(output.sliding("contains a target-specific mutable value".length)
      .count(_ == "contains a target-specific mutable value") == 2, output)
    assert(output.sliding("association.ssc:12:7 [element]".length)
      .count(_ == "association.ssc:12:7 [element]") == 6, output)
    assert(output.linesIterator.toVector.last ==
      "NativeUiAbi(version=1, root=NativeUiFragment, operation=emit)", output)

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
    val post = Term.App(Term.Global("rowPostAction"), List(str("Save"), str("POST"), str("/save"),
      Term.App(Term.Global("fieldPayload"), List(str("id"))), Term.Local(5)))
    val fetchLiteral = Term.App(Term.Global("fetchAction"), List(str("POST"), str("/submit"), Term.Local(4), Term.Local(6)))
    val fetchDynamic = Term.App(Term.Global("fetchActionTo"), List(str("POST"), Term.Local(5), Term.Local(4), Term.Local(6)))
    val table = Term.App(Term.Global("dataTableView"), List(
      Term.Local(3),
      list(List(Term.Local(2))),
      list(List(Term.Local(1), Term.Local(0))),
      str("account.id"),
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

  private def nativeUiSemanticTableProgram(): Program =
    def pair(key: Term, value: Term): Term = Term.Ctor("Tuple2", List(key, value))
    def txt(s: String): Term = Term.App(Term.Global("textNode"), List(str(s)))
    def attrsOf(entries: (String, String)*): Term =
      list(entries.toList.map((key, value) => pair(str(key), str(value))))
    def eventsOf(entries: (String, Term)*): Term =
      list(entries.toList.map((key, value) => pair(str(key), value)))
    def el(tag: String, attrs: Term, events: Term, children: List[Term]): Term =
      Term.App(Term.Global("element"), List(str(tag), attrs, events, list(children)))
    val empty = list(Nil)
    val validTable = el("table", attrsOf("role" -> "table"), empty, List(
      el("thead", empty, empty, List(
        el("tr", attrsOf("role" -> "row"), empty, List(
          el("th", attrsOf("role" -> "columnheader"), eventsOf("click" -> str("noop")), List(txt("Name"))),
          el("th", attrsOf("role" -> "columnheader"), empty, List(txt("Age"))),
        )),
      )),
      el("tbody", empty, empty, List(
        el("tr", attrsOf("role" -> "row"), empty, List(
          el("td", attrsOf("role" -> "cell"), empty, List(txt("Ada"))),
          el("td", attrsOf("role" -> "cell"), empty, List(txt("36"))),
        )),
        el("tr", attrsOf("role" -> "row"), empty, List(
          el("td", attrsOf("role" -> "cell"), empty, List(txt("Alan"))),
          el("td", attrsOf("role" -> "cell"), empty, List(txt("41"))),
        )),
      )),
    ))
    val malformedChild = el("table", empty, empty, List(
      el("div", empty, empty, List(txt("nope"))),
    ))
    val malformedCell = el("table", empty, empty, List(
      el("tbody", empty, empty, List(
        el("tr", empty, empty, List(el("span", empty, empty, List(txt("bad"))))),
      )),
    ))
    val root = Term.App(Term.Global("fragment"),
      List(list(List(validTable, malformedChild, malformedCell))))
    Program(Nil, Term.App(Term.Global("emit"), List(root, str("out"))))

  private def nativeUiReactiveAttrProgram(): Program =
    def pair(key: Term, value: Term): Term = Term.Ctor("Tuple2", List(key, value))
    def txt(s: String): Term = Term.App(Term.Global("textNode"), List(str(s)))
    def el(tag: String, attrs: List[(String, Term)], child: Term): Term =
      Term.App(Term.Global("element"), List(
        str(tag),
        list(attrs.map((key, value) => pair(str(key), value))),
        list(Nil),
        list(List(child)),
      ))
    // Let(List(flag, count), body): inside body flag=Local(1), count=Local(0).
    val flag = Term.App(Term.Global("signal"), List(str("flag"), Term.Lit(Const.CBool(true))))
    val count = Term.App(Term.Global("signal"), List(str("count"), Term.Lit(Const.CInt(5))))
    // <div disabled={flag}>: mapped reactive attribute -> real .disabled() binding.
    val mapped = el("div", List("disabled" -> Term.Local(1)), txt("mapped"))
    // <div id={flag}>: id has no faithful reactive mapping -> strict Unsupported.
    val unmapped = el("div", List("id" -> Term.Local(1)), txt("unmapped"))
    // <div disabled={count}>: mapped name, but an Int signal is a malformed value.
    val malformed = el("div", List("disabled" -> Term.Local(0)), txt("malformed"))
    val root = Term.App(Term.Global("fragment"), List(list(List(mapped, unmapped, malformed))))
    Program(Nil, Term.Let(List(flag, count),
      Term.App(Term.Global("emit"), List(root, str("out")))))

  private def nativeUiTableProgram(): Program =
    val emptyMap = Term.Prim("map.new", Nil)
    val refresh = Term.App(Term.Global("signal"), List(str("refresh"), Term.Lit(Const.CInt(0))))
    val headers = Term.App(Term.Global("signal"), List(str("headers"), str("")))
    val selection = Term.App(Term.Global("signal"), List(str("selection"), str("")))
    // At this point: selection=0, headers=1, refresh=2, nested=3, row=4.
    val rows = Term.App(Term.Global("signal"), List(str("rows"), list(List(Term.Local(4)))))
    // rows=0, selection=1, headers=2, refresh=3, nested=4, row=5.
    val fetch = Term.App(Term.Global("fetchUrlSignal"), List(
      str("remote"), str("/rows"), Term.Local(3), Term.Local(2),
    ))
    // fetch=0, rows=1, selection=2, headers=3, refresh=4, nested=5, row=6.
    def editAction = Term.App(Term.Global("rowEditAction"), List(
      str("PATCH"), str("/edit/:id"), str("id"), Term.Local(4), Term.Local(3),
    ))
    def columns = list(List(Term.App(Term.Global("fieldColumn"), List(
      str("Name"), str("nested.name"), str(""), editAction,
    ))))
    def actions = list(List(
      Term.App(Term.Global("rowDeleteAction"), List(str("/delete/:id"), str("id"), Term.Local(4), Term.Local(3))),
      Term.App(Term.Global("rowPostAction"), List(
        str("Save"), str("POST"), str("/post/:id"),
        Term.App(Term.Global("wholeRowPayload"), Nil), Term.Local(4), Term.Local(3),
      )),
      Term.App(Term.Global("rowLinkAction"), List(str("Pick"), Term.Local(2), str("nested.name"))),
      Term.App(Term.Global("rowPostAction"), List(
        str("Fields"), str("POST"), str("/post/:id"),
        Term.App(Term.Global("fieldsPayload"), List(list(List(str("id"), str("nested.name"))))),
        Term.Local(4), Term.Local(3),
      )),
    ))
    val staticTable = Term.App(Term.Global("dataTableView"), List(
      Term.App(Term.Global("staticRowsSource"), List(list(List(Term.Local(6))))), columns, actions, str("id"),
    ))
    val signalTable = Term.App(Term.Global("dataTableView"), List(
      Term.App(Term.Global("signalRowsSource"), List(Term.Local(1))), columns, actions, str("id"),
    ))
    val fetchTable = Term.App(Term.Global("dataTableView"), List(
      Term.App(Term.Global("fetchRowsSource"), List(Term.Local(0), str("payload.items"))), columns, actions, str("id"),
    ))
    val root = Term.App(Term.Global("fragment"), List(list(List(staticTable, signalTable, fetchTable))))
    val entry = Term.Let(List(emptyMap, emptyMap), Term.Seq(List(
      Term.Prim("map.put", List(Term.Local(0), str("name"), str("Alpha"))),
      Term.Prim("map.put", List(Term.Local(1), str("id"), str("row-a"))),
      Term.Prim("map.put", List(Term.Local(1), str("nested"), Term.Local(0))),
      Term.Let(List(refresh, headers, selection, rows, fetch),
        Term.App(Term.Global("emit"), List(root, str("out")))),
    )))
    Program(Nil, entry)

  private def nativeUiObservationProgram(): Program =
    def source(operation: String) = Term.Ctor("NativeUiSourceRef", List(
      str("observation.ssc"),
      Term.Lit(Const.CInt(1)),
      Term.Lit(Const.CInt(1)),
      str(operation),
    ))
    def site(name: String, id: String, arguments: List[Term]) =
      Term.App(Term.Global(s"__ssc_nativeui_v1.$name"), str(id) :: source(name) :: arguments)
    val mutable = Term.App(Term.Global("signal"), List(str("source"), Term.Lit(Const.CInt(1))))
    val first = site("computedSignal", "observation:first", List(
      Term.Lam(0, Term.App(Term.Local(0), Nil)),
    ))
    val second = site("computedSignal", "observation:second", List(
      Term.Lam(0, Term.App(Term.Local(0), Nil)),
    ))
    val holder = Term.App(Term.Global("signal"), List(str("holder"), Term.Lit(Const.CUnit)))
    def cycleThrough(holderIndex: Int) = Term.Lam(0, Term.Let(
        List(Term.App(Term.Local(holderIndex), Nil)),
        Term.If(
          Term.Prim("__arith__", List(str("=="), Term.Local(0), Term.Lit(Const.CUnit))),
          Term.Lit(Const.CInt(0)),
          Term.App(Term.Local(0), Nil),
        ),
      ))
    val cyclic = site("computedSignal", "observation:cycle", List(cycleThrough(0)))
    val holderB = Term.App(Term.Global("signal"), List(str("holder-b"), Term.Lit(Const.CUnit)))
    val holderC = Term.App(Term.Global("signal"), List(str("holder-c"), Term.Lit(Const.CUnit)))
    val computedB = site("computedSignal", "observation:b", List(cycleThrough(1)))
    val computedC = site("computedSignal", "observation:c", List(cycleThrough(1)))
    val root = Term.App(Term.Global("fragment"), List(list(List(
      Term.App(Term.Global("signalText"), List(Term.Local(8))),
      Term.App(Term.Global("signalText"), List(Term.Local(7))),
      Term.App(Term.Global("signalText"), List(Term.Local(6))),
      Term.App(Term.Global("signalText"), List(Term.Local(4))),
      Term.App(Term.Global("signalText"), List(Term.Local(1))),
    ))))
    Program(Nil, Term.Let(
      List(mutable, first, second, holder, cyclic, holderB, holderC, computedB, computedC),
      Term.Seq(List(
      Term.Prim("__method__", List(str("set"), Term.Local(5), Term.Local(4))),
      Term.Prim("__method__", List(str("set"), Term.Local(3), Term.Local(0))),
      Term.Prim("__method__", List(str("set"), Term.Local(2), Term.Local(1))),
      Term.App(Term.Global("emit"), List(root, str("out"))),
    ))))

  private def nativeUiAsyncProgram(): Program =
    val url = Term.App(Term.Global("signal"), List(str("url"), str("https://example.test/first")))
    val refresh = Term.App(Term.Global("signal"), List(str("refresh"), Term.Lit(Const.CInt(0))))
    val headers = Term.App(Term.Global("signal"), List(str("headers"), str("{\"X-Snapshot\":\"one\"}")))
    val body = Term.App(Term.Global("signal"), List(str("body"), str("body-one")))
    val capture = Term.App(Term.Global("signal"), List(str("capture"), str("capture-old")))
    val clear = Term.App(Term.Global("signal"), List(str("clear"), str("clear-old")))
    val effect = Term.App(Term.Global("signal"), List(str("effect"), str("before")))
    val fetch = Term.App(Term.Global("fetchUrlSignalTo"), List(
      str("remote"), Term.Local(6), Term.Local(5), Term.Local(4),
    ))
    val seed = Term.App(Term.Global("seedSignal"), List(str("seed"), Term.Local(0)))
    val computed = Term.App(Term.Global("computedSignal"), List(
      Term.Lam(0, Term.App(Term.Local(1), Nil)),
    ))
    val effects = list(List(
      Term.App(Term.Global("onSetSignal"), List(Term.Local(3), str("after"))),
      Term.App(Term.Global("onBumpTick"), List(Term.Local(8))),
      Term.App(Term.Global("onBumpTick"), List(Term.Local(8))),
    ))
    val action = Term.App(Term.Global("fetchActionWith"), List(
      str("POST"), str("https://example.test/action"), Term.Local(7), Term.Local(0), Term.Local(8),
    ))
    val captureAction = Term.App(Term.Global("fetchCaptureAction"), List(
      str("POST"), str("https://example.test/capture"), Term.Local(8), Term.Local(7), Term.Local(10), Term.Local(9),
    ))
    val clearAction = Term.App(Term.Global("fetchActionClear"), List(
      str("POST"), str("https://example.test/clear"), Term.Local(9), Term.Local(11), Term.Local(10),
    ))
    val form = Term.App(Term.Global("formBody"), List(list(List(
      str("body"), Term.Ctor("Tuple2", List(str("renamed"), str("effect"))),
    ))))
    val formAction = Term.App(Term.Global("fetchActionWith"), List(
      str("POST"), str("https://example.test/form"), Term.Local(0), Term.Local(4), Term.Local(12),
    ))
    val openEffect = list(List(Term.App(Term.Global("onOpenJson"), List(
      str("https://example.test/items/:value"), str("id"),
    ))))
    val openAction = Term.App(Term.Global("fetchActionWith"), List(
      str("POST"), str("https://example.test/open"), Term.Local(13), Term.Local(0), Term.Local(14),
    ))
    val unsafeEffect = list(List(Term.App(Term.Global("onNavigate"), List(str("javascript:alert(1)")))))
    val unsafeAction = Term.App(Term.Global("fetchActionWith"), List(
      str("POST"), str("https://example.test/unsafe"), Term.Local(15), Term.Local(0), Term.Local(16),
    ))
    val hashEffect = list(List(Term.App(Term.Global("onNavigate"), List(str("#/route")))))
    val hashAction = Term.App(Term.Global("fetchActionWith"), List(
      str("POST"), str("https://example.test/hash"), Term.Local(17), Term.Local(0), Term.Local(18),
    ))
    val safeEffect = list(List(Term.App(Term.Global("onNavigate"), List(str("https://example.test/done")))))
    val safeAction = Term.App(Term.Global("fetchActionWith"), List(
      str("POST"), str("https://example.test/safe"), Term.Local(19), Term.Local(0), Term.Local(20),
    ))
    val aliasAction = Term.App(Term.Global("fetchCaptureAction"), List(
      str("POST"), str("https://example.test/alias"), Term.Local(20), Term.Local(22), Term.Local(22), Term.Local(21),
    ))
    val root = Term.App(Term.Global("fragment"), List(list(List(
      Term.App(Term.Global("signalText"), List(Term.Local(24))),
      Term.App(Term.Global("signalText"), List(Term.Local(23))),
      Term.App(Term.Global("signalText"), List(Term.Local(22))),
      Term.App(Term.Global("signalText"), List(Term.Local(21))),
      Term.App(Term.Global("signalText"), List(Term.Local(20))),
      Term.App(Term.Global("signalText"), List(Term.Local(19))),
      Term.App(Term.Global("signalText"), List(Term.Local(18))),
      Term.App(Term.Global("signalText"), List(Term.Local(17))),
      Term.App(Term.Global("signalText"), List(Term.Local(16))),
      Term.App(Term.Global("signalText"), List(Term.Local(15))),
      Term.Local(13), Term.Local(12), Term.Local(11), Term.Local(9),
      Term.Local(7), Term.Local(5), Term.Local(3), Term.Local(1), Term.Local(0),
    ))))
    Program(Nil, Term.Let(
      List(
        url, refresh, headers, body, capture, clear, effect, fetch, seed, computed,
        effects, action, captureAction, clearAction, form, formAction,
        openEffect, openAction, unsafeEffect, unsafeAction, hashEffect, hashAction,
        safeEffect, safeAction, aliasAction,
      ),
      Term.App(Term.Global("emit"), List(root, str("out"))),
    ))

  private def nativeUiKeyedActionLifecycleProgram(): Program =
    val mode = Term.App(Term.Global("signal"), List(str("mode"), str("with")))
    val items = Term.App(Term.Global("signal"), List(str("items"), list(List(str("row")))))
    val refresh = Term.App(Term.Global("signal"), List(str("refresh"), Term.Lit(Const.CInt(0))))
    val headers = Term.App(Term.Global("signal"), List(str("headers"), str("")))
    val body = Term.App(Term.Global("signal"), List(str("body"), str("payload")))
    val effect = Term.App(Term.Global("signal"), List(str("effect"), str("before")))
    def modeIs(value: String) = Term.Prim("__arith__", List(
      str("=="), Term.App(Term.Local(9), Nil), str(value),
    ))
    def navigate(destination: String) = list(List(
      Term.App(Term.Global("onNavigate"), List(str(destination))),
    ))
    def openJson(template: String) = list(List(
      Term.App(Term.Global("onOpenJson"), List(str(template), str("id"))),
    ))
    val success = list(List(
      Term.App(Term.Global("onSetSignal"), List(Term.Local(4), str("after"))),
      Term.App(Term.Global("onBumpTick"), List(Term.Local(2))),
    ))
    def action(method: String, path: String, effects: Term) =
      Term.App(Term.Global("fetchActionWith"), List(
        str(method), str(path), Term.Local(0), effects, Term.Local(1),
      ))
    val renderBranch =
      Term.If(modeIs("with"), action("POST", "https://example.test/keyed", success),
        Term.If(modeIs("data"), action("POST", "https://example.test/data", navigate("data:text/plain,x")),
          Term.If(modeIs("file"), action("POST", "https://example.test/file", navigate("file:///tmp/x")),
            Term.If(modeIs("hostless"), action("POST", "https://example.test/hostless", navigate("https:foo")),
              Term.If(modeIs("unsafe-open"), action("POST", "https://example.test/unsafe-open", openJson("javascript::value")),
                Term.If(modeIs("hostless-open"), action("POST", "https://example.test/hostless-open", openJson("https:foo/:value")),
                  Term.If(modeIs("bad-method"), action("POST\r\nX-Injected", "https://example.test/bad", success),
                    Term.App(Term.Global("textNode"), List(str("plain"))),
                  ),
                ),
              ),
            ),
          ),
        ),
      )
    val render = Term.Lam(1, Term.App(Term.Global("componentScope"), List(
      str("action-scope"),
      Term.Lam(0, Term.Let(List(
        Term.App(Term.Global("signal"), List(str("scoped-refresh"), Term.Lit(Const.CInt(0)))),
        Term.App(Term.Global("signal"), List(str("scoped-headers"), str(""))),
        Term.App(Term.Global("signal"), List(str("scoped-body"), str("payload"))),
      ), renderBranch)),
    )))
    val keyed = Term.App(Term.Global("forKeyedView"), List(
      Term.Local(5), Term.Lam(1, Term.Local(0)), Term.Local(0),
    ))
    val root = Term.App(Term.Global("fragment"), List(list(List(
      Term.App(Term.Global("signalText"), List(Term.Local(7))),
      Term.App(Term.Global("signalText"), List(Term.Local(5))),
      Term.App(Term.Global("signalText"), List(Term.Local(4))),
      Term.App(Term.Global("signalText"), List(Term.Local(3))),
      Term.App(Term.Global("signalText"), List(Term.Local(2))),
      Term.Local(0),
    ))))
    Program(Nil, Term.Let(
      List(mode, items, refresh, headers, body, effect, render, keyed),
      Term.App(Term.Global("emit"), List(root, str("out"))),
    ))

  private def nativeUiKeyedFetchMetadataProgram(): Program =
    val mode = Term.App(Term.Global("signal"), List(str("mode"), str("a")))
    val items = Term.App(Term.Global("signal"), List(str("items"), list(List(str("row")))))
    def modeIs(value: String) = Term.Prim("__arith__", List(
      str("=="), Term.App(Term.Local(5), Nil), str(value),
    ))
    val fetch = Term.If(modeIs("a"),
      Term.App(Term.Global("fetchUrlSignal"), List(
        str("remote"), str("https://example.test/a"), Term.Local(2), Term.Local(1),
      )),
      Term.If(modeIs("b"),
        Term.App(Term.Global("fetchUrlSignal"), List(
          str("remote"), str("https://example.test/b"), Term.Local(2), Term.Local(1),
        )),
        Term.App(Term.Global("fetchUrlSignalTo"), List(
          str("remote"), Term.Local(0), Term.Local(2), Term.Local(1),
        )),
      ),
    )
    val doubleFetch = Term.Let(List(
      Term.App(Term.Global("fetchUrlSignal"), List(
        str("remote"), str("https://example.test/a"), Term.Local(2), Term.Local(1),
      )),
      Term.App(Term.Global("fetchUrlSignal"), List(
        str("remote"), str("https://example.test/b"), Term.Local(3), Term.Local(2),
      )),
    ), Term.App(Term.Global("signalText"), List(Term.Local(0))))
    val renderedFetch = Term.If(modeIs("double"), doubleFetch,
      Term.App(Term.Global("signalText"), List(fetch)))
    val render = Term.Lam(1, Term.App(Term.Global("componentScope"), List(
      str("fetch-scope"),
      Term.Lam(0, Term.Let(List(
        Term.App(Term.Global("signal"), List(str("scoped-refresh"), Term.Lit(Const.CInt(0)))),
        Term.App(Term.Global("signal"), List(str("scoped-headers"), str(""))),
        Term.App(Term.Global("signal"), List(str("scoped-url"), str("https://example.test/b"))),
      ), renderedFetch)),
    )))
    val keyed = Term.App(Term.Global("forKeyedView"), List(
      Term.Local(1), Term.Lam(1, Term.Local(0)), Term.Local(0),
    ))
    val root = Term.App(Term.Global("fragment"), List(list(List(
      Term.App(Term.Global("signalText"), List(Term.Local(3))),
      Term.Local(0),
    ))))
    Program(Nil, Term.Let(
      List(mode, items, render, keyed),
      Term.App(Term.Global("emit"), List(root, str("out"))),
    ))

  private def nativeUiPlatformSignalsProgram(): Program =
    val persisted = Term.App(Term.Global("persistedSignal"), List(str("draft"), str("fallback")))
    val boot = Term.App(Term.Global("persistedSignal"), List(str("boot"), str("boot-fallback")))
    val hidden = Term.App(Term.Global("persistedSignal"), List(str("hidden"), str("hidden-fallback")))
    val online = Term.App(Term.Global("onlineSignal"), Nil)
    val derived = Term.App(Term.Global("computedSignal"), List(
      Term.Lam(0, Term.App(Term.Local(0), Nil)),
    ))
    def scopedOnline(scope: String) = Term.App(Term.Global("componentScope"), List(
      str(scope), Term.Lam(0, Term.App(Term.Global("onlineSignal"), Nil)),
    ))
    val items = Term.App(Term.Global("signal"), List(str("platform-items"), list(List(str("row")))))
    val scopedPersisted = Term.App(Term.Global("forKeyedView"), List(
      Term.Local(0),
      Term.Lam(1, Term.Local(0)),
      Term.Lam(1, Term.App(Term.Global("componentScope"), List(
        str("persisted-row"), Term.Lam(0, Term.App(Term.Global("signalText"), List(
          Term.App(Term.Global("persistedSignal"), List(str("scoped"), str("scoped-fallback"))),
        ))),
      ))),
    ))
    val root = Term.App(Term.Global("fragment"), List(list(List(
      Term.App(Term.Global("signalText"), List(Term.Local(7))),
      Term.App(Term.Global("signalText"), List(Term.Local(6))),
      Term.App(Term.Global("signalText"), List(Term.Local(5))),
      Term.App(Term.Global("signalText"), List(Term.Local(4))),
      Term.App(Term.Global("signalText"), List(Term.Local(3))),
      Term.App(Term.Global("signalText"), List(Term.Local(2))),
      Term.App(Term.Global("signalText"), List(Term.Local(1))),
      scopedPersisted,
    ))))
    Program(Nil, Term.Let(
      List(persisted, boot, hidden, online, derived, scopedOnline("first"), scopedOnline("second"), items),
      Term.Seq(List(
        Term.Prim("__method__", List(str("set"), Term.Local(6), str("boot-committed"))),
        Term.App(Term.Global("emit"), List(root, str("out"))),
      )),
    ))

  private def nativeUiKeyedLifecycleProgram(): Program =
    val items = Term.App(Term.Global("signal"), List(str("items"), list(List(str("a"), str("b")))))
    val render = Term.Lam(1, Term.App(Term.Global("componentScope"), List(
      Term.Local(0),
      Term.Lam(0, Term.Let(
        List(Term.App(Term.Global("signal"), List(str("local"), Term.Local(0)))),
        Term.App(Term.Global("signalText"), List(Term.Local(0))),
      )),
    )))
    val keyed = Term.App(Term.Global("forKeyedView"), List(
      Term.Local(0),
      Term.Lam(1, Term.Local(0)),
      render,
    ))
    Program(Nil, Term.Let(List(items),
      Term.App(Term.Global("emit"), List(keyed, str("out")))))

  private def nativeUiStoreRollbackProgram(): Program =
    def source(operation: String) = Term.Ctor("NativeUiSourceRef", List(
      str("rollback.ssc"), Term.Lit(Const.CInt(1)), Term.Lit(Const.CInt(1)), str(operation),
    ))
    val mutable = Term.App(Term.Global("signal"), List(str("message"), str("before")))
    val computed = Term.App(Term.Global("__ssc_nativeui_v1.computedSignal"), List(
      str("rollback:computed"), source("computedSignal"),
      Term.Lam(0, Term.App(Term.Local(0), Nil)),
    ))
    val items = Term.App(Term.Global("signal"), List(str("items"), list(List(str("row")))))
    val keyed = Term.App(Term.Global("__ssc_nativeui_v1.forKeyedView"), List(
      str("rollback:keyed"), source("forKeyedView"), Term.Local(0),
      Term.Lam(1, Term.Local(0)),
      Term.Lam(1, Term.App(Term.Global("textNode"), List(Term.Local(0)))),
    ))
    val root = Term.App(Term.Global("fragment"), List(list(List(
      Term.App(Term.Global("signalText"), List(Term.Local(3))),
      Term.App(Term.Global("signalText"), List(Term.Local(2))),
      Term.Local(0),
    ))))
    Program(Nil, Term.Let(List(mutable, computed, items, keyed),
      Term.App(Term.Global("emit"), List(root, str("out")))))

  private def nativeUiDeferredProgram(): Program =
    def source(line: Long, operation: String) = Term.Ctor("NativeUiSourceRef", List(
      str("deferred.ssc"), Term.Lit(Const.CInt(line)), Term.Lit(Const.CInt(3)), str(operation),
    ))
    val refresh = Term.App(Term.Global("signal"), List(str("refresh"), Term.Lit(Const.CInt(0))))
    val body = Term.App(Term.Global("signal"), List(str("body"), str("")))
    val fetch = Term.App(Term.Global("__ssc_nativeui_v1.fetchUrlSignal"), List(
      str("deferred:fetch"), source(10, "fetchUrlSignal"), str("remote"), str("/api"), Term.Local(1),
    ))
    val action = Term.App(Term.Global("__ssc_nativeui_v1.fetchAction"), List(
      str("deferred:action"), source(20, "fetchAction"), str("POST"), str("/api"), Term.Local(1), Term.Local(2),
    ))
    val intSource = Term.App(Term.Global("signal"), List(str("int-source"), Term.Lit(Const.CInt(1))))
    val intSeed = Term.App(Term.Global("seedSignal"), List(str("int-seed"), Term.Local(0)))
    val root = Term.App(Term.Global("fragment"), List(list(List(
      Term.App(Term.Global("signalText"), List(Term.Local(3))),
      Term.Local(2),
      Term.App(Term.Global("signalText"), List(Term.Local(1))),
      Term.App(Term.Global("signalText"), List(Term.Local(0))),
    ))))
    Program(Nil, Term.Let(List(refresh, body, fetch, action, intSource, intSeed),
      Term.App(Term.Global("emit"), List(root, str("out")))))

  private val nativeUiDeferredProbe = """
@main
struct DeferredProbe {
    private static func fields(_ value: SscValue, _ tag: String) -> [SscValue] {
        guard case let .data(actual, fields) = value, actual == tag else { fatalError("expected \(tag)") }
        return fields.asArray()
    }
    private static func list(_ value: SscValue) -> [SscValue] {
        var current = value, result: [SscValue] = []
        while true {
            switch current {
            case let .data("Cons", fields) where fields.count == 2:
                result.append(fields[0]); current = fields[1]
            case .data("Nil", _): return result
            default: fatalError("expected list")
            }
        }
    }
    private static func makeList(_ values: [SscValue]) -> SscValue {
        values.reversed().reduce(.data("Nil", [])) { .data("Cons", [$1, $0]) }
    }
    private static func string(_ value: SscValue) -> String {
        guard case let .string(result) = value else { fatalError("expected String") }
        return result
    }
    private static func int(_ value: SscValue) -> Int64 {
        guard case let .int(result) = value else { fatalError("expected Int") }
        return result
    }
    @MainActor
    static func main() {
        let store = NativeUiStore()
        let abi = fields(store.root, "NativeUiAbi")
        let children = list(fields(abi[1], "NativeUiFragment")[0])
        let fetch = fields(children[0], "NativeUiSignalText")[0]
        let intSource = fields(children[2], "NativeUiSignalText")[0]
        let intSeed = fields(children[3], "NativeUiSignalText")[0]
        guard store.signalKind(fetch) == "fetch",
              store.source(for: fetch) == "deferred.ssc:10:3 [fetchUrlSignal]" else {
            fatalError("fetch signal source missing")
        }
        guard store.cell(for: fetch).renderedDiagnostic() == nil else {
            fatalError("fetch signal adapter remained deferred")
        }

        let css = SscMap()
        css.put(.string("style"), .string("width:90vw"))
        guard NativeUiStyles.diagnostic(
            attrs: css, store: store, siteId: "deferred:action"
        )?.contains("unsupported native CSS value width:90vw") == true else {
            fatalError("invalid CSS was silent")
        }
        let attrs = SscMap()
        attrs.put(.string("mystery"), .string("x"))
        guard NativeUiRenderer.inventoryDiagnostic(attrs: attrs, events: SscMap()) ==
            "unsupported native attribute mystery" else {
            fatalError("unknown attribute was silent")
        }
        let layout = SscMap()
        layout.put(.string("style"), .string("align-items:center;font-weight:500"))
        let href = SscMap()
        href.put(.string("href"), .string("https://example.com"))
        let ordered = SscMap()
        ordered.put(.string("start"), .int(4))
        guard NativeUiRenderer.centeredAlignment(layout),
              NativeUiStyles.fontWeightBehavior("500") == "medium",
              NativeUiRenderer.semanticTextBehavior("strong") == "bold",
              NativeUiRenderer.semanticTextBehavior("em") == "italic",
              NativeUiRenderer.semanticTextBehavior("code") == "monospace",
              NativeUiRenderer.anchorBehavior(attrs: href, events: SscMap()) == "href",
              NativeUiRenderer.orderedListStart(ordered) == 4,
              NativeUiStyles.displayBehavior("none") == "hidden",
              NativeUiStyles.boxShadowBehavior("0 20px 60px rgba(0,0,0,0.3)") ==
                "x=0.0,y=20.0,blur=60.0" else {
            fatalError("shipped behavior was accepted but ignored")
        }
        let hashHref = SscMap()
        hashHref.put(.string("href"), .string("#/orders"))
        guard NativeUiRenderer.anchorBehavior(attrs: hashHref, events: SscMap()) == "unsupported" else {
            fatalError("hash route incorrectly became generic Link")
        }
        for invalid in [
            "display:banana", "flex-direction:banana", "gap:nope", "flex:2 bananas",
            "flex-grow:many", "text-align:sideways", "text-decoration:blink", "border:banana",
            "border:1px solid red junk", "border:1px solid banana;border-color:red", "box-shadow:banana",
            "box-sizing:banana", "border-collapse:banana", "cursor:banana", "user-select:banana"
        ] {
            let attrs = SscMap()
            attrs.put(.string("style"), .string(invalid))
            guard NativeUiStyles.diagnostic(
                attrs: attrs, store: store, siteId: "deferred:action"
            )?.contains("deferred.ssc:20:3 [fetchAction]") == true else {
                fatalError("recognized invalid CSS was silent: \(invalid)")
            }
        }
        // The cosmetic no-op properties the real std/ui toolkit emits (with these
        // exact values) must stay accepted — validating their value space must not
        // regress busi's production buttons/links/tables into Unsupported.
        for accepted in [
            "box-sizing:border-box", "border-collapse:collapse",
            "cursor:pointer", "cursor:not-allowed", "user-select:none"
        ] {
            let attrs = SscMap()
            attrs.put(.string("style"), .string(accepted))
            guard NativeUiStyles.diagnostic(
                attrs: attrs, store: store, siteId: "deferred:action"
            ) == nil else {
                fatalError("shipped cosmetic no-op CSS became Unsupported: \(accepted)")
            }
        }
        let invalidAria = SscMap()
        invalidAria.put(.string("aria-disabled"), .string("sometimes"))
        guard NativeUiStyles.diagnostic(
            attrs: invalidAria, store: store, siteId: "deferred:action"
        ) == "invalid native aria-disabled value sometimes at deferred.ssc:20:3 [fetchAction]",
              NativeUiRenderer.structuralDiagnostic(
                .data("NativeUiForKeyed", [.string("deferred:action")]), store: store
              ) == "malformed NativeUiForKeyed at deferred.ssc:20:3 [fetchAction]" else {
            fatalError("malformed semantic source was lost")
        }
        let invalidModal = SscMap()
        invalidModal.put(.string("aria-modal"), .string("sometimes"))
        guard NativeUiStyles.diagnostic(
            attrs: invalidModal, store: store, siteId: "deferred:action"
        ) == "invalid native aria-modal value sometimes at deferred.ssc:20:3 [fetchAction]" else {
            fatalError("invalid aria-modal was silent")
        }
        NativeUiActions.run(
            .data("NativeUiEvent", [.string("mystery"), .unit, .unit, .unit]),
            input: nil,
            store: store,
            siteId: "deferred:action",
            ownerPath: "root"
        )
        guard store.failure ==
            "unsupported native event kind mystery at deferred.ssc:20:3 [fetchAction]" else {
            fatalError("ordinary event source was lost")
        }
        NativeUiActions.run(
            .data("NativeUiEvent", [
                .string("increment"),
                .data("NativeUiSignal", [.string("x"), .string("root"), .string("mutable"), .unit, .unit, .unit]),
                .int(1),
                .map(SscMap())
            ]),
            input: nil,
            store: store,
            siteId: "deferred:action",
            ownerPath: "root"
        )
        guard store.failure ==
            "native event increment target must be NativeUiSignal at deferred.ssc:20:3 [fetchAction]" else {
            fatalError("malformed increment target was silent or unsourced")
        }
        NativeUiActions.run(
            .data("NativeUiEvent", [.string("set"), fetch, .string("x"), .unit]),
            input: nil,
            store: store,
            siteId: "deferred:action",
            ownerPath: "root"
        )
        guard store.failure ==
            "native event set metadata must be Map at deferred.ssc:20:3 [fetchAction]" else {
            fatalError("malformed event metadata was accepted")
        }
        let invalidMetadata = SscMap()
        invalidMetadata.put(.int(1), .string("bad"))
        NativeUiActions.run(
            .data("NativeUiEvent", [.string("set"), fetch, .string("x"), .map(invalidMetadata)]),
            input: nil,
            store: store,
            siteId: "deferred:action",
            ownerPath: "root"
        )
        guard store.failure ==
            "native event set metadata key must be String at deferred.ssc:20:3 [fetchAction]" else {
            fatalError("non-string event metadata key was accepted")
        }
        let fetchFields = fields(fetch, "NativeUiSignal")
        NativeUiActions.run(
            .data("NativeUiEvent", [.string("set"), fetch, .string("forbidden"), .map(SscMap())]),
            input: nil,
            store: store,
            siteId: "deferred:action",
            ownerPath: "root"
        )
        guard store.failure ==
            "native event set target must be writable NativeUiSignal at deferred.ssc:20:3 [fetchAction]",
              string(store.read(fetch)).isEmpty else {
            fatalError("read-only live event target mutated or lost source")
        }
        let fetchMetadata = fields(fetchFields[5], "NativeUiSignalMetaFetch")
        let refresh = fetchMetadata[1]
        store.cell(for: refresh).write(.int(Int64.max))
        NativeUiActions.run(
            .data("NativeUiEvent", [.string("increment"), refresh, .int(1), .map(SscMap())]),
            input: nil,
            store: store,
            siteId: "deferred:action",
            ownerPath: "root"
        )
        guard store.failure ==
            "native event increment would overflow Int64 at deferred.ssc:20:3 [fetchAction]",
              int(store.read(refresh)) == Int64.max else {
            fatalError("increment overflow trapped, mutated, or lost source")
        }
        store.cell(for: refresh).write(.int(5))
        var forgedReadRan = false
        var forgedWriteRan = false
        let refreshFields = fields(refresh, "NativeUiSignal")
        let forgedLiveWrapper = SscValue.data("NativeUiSignal", [
            refreshFields[0], refreshFields[1], refreshFields[2],
            .closure(SscClosure(arity: 0) { _ in forgedReadRan = true; return .int(-999) }),
            .closure(SscClosure(arity: 1) { _ in forgedWriteRan = true; return .unit }),
            refreshFields[5]
        ])
        NativeUiActions.run(
            .data("NativeUiEvent", [.string("increment"), forgedLiveWrapper, .int(1), .map(SscMap())]),
            input: nil,
            store: store,
            siteId: "deferred:action",
            ownerPath: "root"
        )
        guard !forgedReadRan, !forgedWriteRan, int(store.read(refresh)) == 6 else {
            fatalError("event authenticated one Host cell but executed forged wrapper closures")
        }
        store.cell(for: intSource).write(.int(9))
        NativeUiActions.run(
            .data("NativeUiEvent", [.string("increment"), intSeed, .int(1), .map(SscMap())]),
            input: nil,
            store: store,
            siteId: "deferred:action",
            ownerPath: "root"
        )
        store.cell(for: intSource).write(.int(20))
        guard int(store.read(intSeed)) == 10 else {
            fatalError("seed event read stale construction default or failed to become dirty")
        }
        let forgedKind = SscValue.data("NativeUiSignal", [
            fetchFields[0], fetchFields[1], .string("bogus"),
            fetchFields[3], fetchFields[4], fetchFields[5]
        ])
        NativeUiActions.run(
            .data("NativeUiEvent", [.string("increment"), forgedKind, .int(1), .map(SscMap())]),
            input: nil,
            store: store,
            siteId: "deferred:action",
            ownerPath: "root"
        )
        guard store.failure ==
            "native event increment target must be NativeUiSignal at deferred.ssc:20:3 [fetchAction]" else {
            fatalError("unknown signal kind was accepted")
        }
        let forgedMetadata = SscValue.data("NativeUiSignal", [
            fetchFields[0], fetchFields[1], .string("mutable"),
            fetchFields[3], fetchFields[4], .unit
        ])
        NativeUiActions.run(
            .data("NativeUiEvent", [.string("set"), forgedMetadata, .string("x"), .map(SscMap())]),
            input: nil,
            store: store,
            siteId: "deferred:action",
            ownerPath: "root"
        )
        guard store.failure ==
            "native event set target must be NativeUiSignal at deferred.ssc:20:3 [fetchAction]" else {
            fatalError("allowed signal kind with wrong metadata was accepted")
        }
        func forgedSignal(_ kind: String, _ metadata: SscValue) -> SscValue {
            .data("NativeUiSignal", [
                fetchFields[0], fetchFields[1], .string(kind),
                fetchFields[3], fetchFields[4], metadata
            ])
        }
        let badSeed = forgedSignal("seed", .data("NativeUiSignalMetaSeed", [.unit]))
        let badEquality = forgedSignal(
            "equality", .data("NativeUiSignalMetaEquality", [.unit, .string("x")])
        )
        let badFetch = forgedSignal(
            "fetch", .data("NativeUiSignalMetaFetch", [.unit, .unit, .unit, .unit, .unit])
        )
        guard store.signalKind(badSeed) == nil,
              store.signalKind(badEquality) == nil,
              store.signalKind(badFetch) == nil else {
            fatalError("correct-tag metadata with invalid nested field was accepted")
        }
        NativeUiActions.run(
            .data("NativeUiFetchAction", [.int(1)]), input: nil, store: store,
            siteId: "deferred:action", ownerPath: "root"
        )
        guard store.failure ==
            "malformed native fetch action at deferred.ssc:20:3 [fetchAction]" else {
            fatalError("malformed fetch action lost owning element source")
        }
        Swift.print("fetch-live|css-value|attribute|behavior|source")
    }
}
"""

  private val nativeUiStoreRollbackProbe = """
@main
struct StoreRollbackProbe {
    private static func fields(_ value: SscValue, _ tag: String) -> [SscValue] {
        guard case let .data(actual, fields) = value, actual == tag else { fatalError("expected \(tag)") }
        return fields.asArray()
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
            case let .data("Cons", fields) where fields.count == 2:
                result.append(fields[0]); current = fields[1]
            case .data("Nil", _): return result
            default: fatalError("expected list")
            }
        }
    }
    @MainActor
    static func main() {
        do {
            let store = NativeUiStore()
            let abi = fields(store.root, "NativeUiAbi")
            let children = list(fields(abi[1], "NativeUiFragment")[0])
            let mutable = fields(children[0], "NativeUiSignalText")[0]
            let computed = fields(children[1], "NativeUiSignalText")[0]
            let keyed = fields(children[2], "NativeUiForKeyed")
            let mutableCell = store.cell(for: mutable)
            let computedCell = store.cell(for: computed)
            let token = store.subscribe(computedCell)
            guard string(computedCell.read()) == "before" else { fatalError("bad computed baseline") }
            let failing = SscClosure(arity: 1) { _ in
                mutableCell.write(.string("provisional"))
                throw SscRuntimeFailure(description: "rollback render")
            }
            do {
                _ = try store.reconcileKeyed(
                    parentOwnerPath: "root",
                    siteId: string(keyed[0]),
                    items: [.string("row")],
                    key: closure(keyed[2]),
                    render: failing
                )
                fatalError("expected rollback")
            } catch {
                guard String(describing: error).contains("rollback render") else { throw error }
            }
            let clean = try store.reconcileKeyed(
                parentOwnerPath: "root",
                siteId: string(keyed[0]),
                items: [.string("row")],
                key: closure(keyed[2]),
                render: closure(keyed[3])
            )
            guard clean.entries.count == 1 else { fatalError("clean reconcile failed") }
            Swift.print("rollback|\(string(mutableCell.read()))|\(string(computedCell.read()))|\(mutableCell.revision)|\(computedCell.revision)")
            store.unsubscribe(token)
        } catch { fatalError(String(describing: error)) }
    }
}
"""

  private val nativeUiAsyncProbe = """
import Foundation

final class ControlledURLProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var instances: [ControlledURLProtocol] = []
    nonisolated(unsafe) private static var stoppedIndices = Set<Int>()

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lock.lock()
        Self.instances.append(self)
        Self.lock.unlock()
    }

    override func stopLoading() {
        Self.lock.lock()
        if let index = Self.instances.firstIndex(where: { $0 === self }) {
            Self.stoppedIndices.insert(index)
        }
        Self.lock.unlock()
    }

    static func reset() {
        lock.lock(); instances = []; stoppedIndices = []; lock.unlock()
    }

    static var count: Int {
        lock.lock(); defer { lock.unlock() }; return instances.count
    }

    static func request(_ index: Int) -> URLRequest {
        lock.lock(); defer { lock.unlock() }; return instances[index].request
    }

    static func body(_ index: Int) -> String? {
        let request = request(index)
        if let data = request.httpBody { return String(data: data, encoding: .utf8) }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open(); defer { stream.close() }
        var data = Data(), buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: buffer.count)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return String(data: data, encoding: .utf8)
    }

    static func wasStopped(_ index: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }; return stoppedIndices.contains(index)
    }

    static func respond(_ index: Int, status: Int, body: String) {
        respond(index, status: status, data: Data(body.utf8))
    }

    static func respond(_ index: Int, status: Int, data: Data) {
        lock.lock(); let instance = instances[index]; lock.unlock()
        let response = HTTPURLResponse(
            url: instance.request.url!, statusCode: status,
            httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "text/plain"]
        )!
        instance.client?.urlProtocol(instance, didReceive: response, cacheStoragePolicy: .notAllowed)
        instance.client?.urlProtocol(instance, didLoad: data)
        instance.client?.urlProtocolDidFinishLoading(instance)
    }

    static func fail(_ index: Int, _ error: URLError) {
        lock.lock(); let instance = instances[index]; lock.unlock()
        instance.client?.urlProtocol(instance, didFailWithError: error)
    }
}

@main
struct AsyncProbe {
    private static func fields(_ value: SscValue, _ tag: String) -> [SscValue] {
        guard case let .data(actual, fields) = value, actual == tag else { fatalError("expected \(tag)") }
        return fields.asArray()
    }
    private static func list(_ value: SscValue) -> [SscValue] {
        var current = value, result: [SscValue] = []
        while true {
            switch current {
            case let .data("Cons", fields) where fields.count == 2:
                result.append(fields[0]); current = fields[1]
            case .data("Nil", _): return result
            default: fatalError("expected list")
            }
        }
    }
    private static func makeList(_ values: [SscValue]) -> SscValue {
        values.reversed().reduce(SscValue.data("Nil", [])) {
            SscValue.data("Cons", [$1, $0])
        }
    }
    private static func string(_ value: SscValue) -> String {
        guard case let .string(result) = value else { fatalError("expected string") }
        return result
    }
    private static func int(_ value: SscValue) -> Int64 {
        guard case let .int(result) = value else { fatalError("expected int") }
        return result
    }
    private static func signal(_ node: SscValue) -> SscValue {
        fields(node, "NativeUiSignalText")[0]
    }
    private static func waitForRequests(_ count: Int) async {
        for _ in 0..<400 {
            if ControlledURLProtocol.count >= count { return }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        fatalError("timed out waiting for \(count) requests; got \(ControlledURLProtocol.count)")
    }
    private static func waitForStop(_ index: Int) async {
        for _ in 0..<400 {
            if ControlledURLProtocol.wasStopped(index) { return }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        fatalError("timed out waiting for request \(index) cancellation")
    }
    @MainActor
    private static func waitForPhase(_ store: NativeUiStore, _ phase: SscValue, _ expected: String) async {
        for _ in 0..<400 {
            if string(store.read(phase)) == expected { return }
            await Task.yield()
        }
        fatalError("timed out waiting for phase \(expected); got \(string(store.read(phase)))")
    }

    @MainActor
    private static func assertNoStoreLeak() async {
        ControlledURLProtocol.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ControlledURLProtocol.self]
        weak var leaked: NativeUiStore?
        do {
            let candidate = NativeUiStore(urlSession: URLSession(configuration: configuration))
            leaked = candidate
            let abi = fields(candidate.root, "NativeUiAbi")
            let children = list(fields(abi[1], "NativeUiFragment")[0])
            let fetch = signal(children[7])
            let metadata = fields(fields(fetch, "NativeUiSignal")[5], "NativeUiSignalMetaFetch")
            _ = candidate.subscribe(candidate.cell(for: metadata[3]))
            await waitForRequests(1)
        }
        await waitForStop(0)
        for _ in 0..<20 where leaked != nil { await Task.yield() }
        guard leaked == nil else { fatalError("NativeUiStore was retained by an async task") }
    }

    @MainActor
    static func main() async {
        ControlledURLProtocol.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ControlledURLProtocol.self]
        let store = NativeUiStore(urlSession: URLSession(configuration: configuration))
        let abi = fields(store.root, "NativeUiAbi")
        let children = list(fields(abi[1], "NativeUiFragment")[0])
        let url = signal(children[0])
        let refresh = signal(children[1])
        let headers = signal(children[2])
        let body = signal(children[3])
        let capture = signal(children[4])
        _ = signal(children[5])
        let effect = signal(children[6])
        let fetch = signal(children[7])
        let seed = signal(children[8])
        let computed = signal(children[9])
        let baseAction = children[10]
        let captureAction = children[11]
        let clearAction = children[12]
        let formAction = children[13]
        let openAction = children[14]
        let unsafeAction = children[15]
        let hashAction = children[16]
        let safeAction = children[17]
        let aliasAction = children[18]
        let fetchMetadata = fields(fields(fetch, "NativeUiSignal")[5], "NativeUiSignalMetaFetch")
        let phase = fetchMetadata[3]
        let errorSignal = fetchMetadata[4]

        let phaseFields = fields(phase, "NativeUiSignal")
        guard case let .closure(phaseRead) = phaseFields[3] else { fatalError("phase read missing") }
        let compute = SscClosure(arity: 0) { _ in try phaseRead.native!([]) }
        let computedPhase = SscValue.data("NativeUiSignal", [
            .string("phase-observer"), .string("root"), .string("computed"),
            .closure(SscClosure(arity: 0) { _ in try compute.native!([]) }),
            .closure(SscClosure(arity: 1) { _ in throw SscRuntimeFailure(description: "read-only") }),
            .data("NativeUiSignalMetaComputed", [.closure(compute)])
        ])
        let token = store.subscribe(store.cell(for: computedPhase))
        await waitForRequests(1)
        let familyToken = store.subscribe(store.cell(for: phase))
        await Task.yield()
        guard string(store.read(phase)) == "loading",
              store.fetchOwnerCount(for: fetch) == 2,
              ControlledURLProtocol.count == 1,
              ControlledURLProtocol.request(0).url?.absoluteString == "https://example.test/first",
              ControlledURLProtocol.request(0).value(forHTTPHeaderField: "X-Snapshot") == "one" else {
            fatalError("phase subscription did not own fetch family")
        }
        store.unsubscribe(familyToken)
        guard store.fetchOwnerCount(for: fetch) == 1 else { fatalError("reentrant dependency overcounted fetch") }
        store.cell(for: url).write(.string("https://example.test/first"))
        store.cell(for: headers).write(.string("{\"X-Snapshot\":\"one\"}"))
        await Task.yield()
        guard ControlledURLProtocol.count == 1 else { fatalError("equal source write restarted fetch") }

        store.cell(for: url).write(.string("https://example.test/second"))
        await waitForRequests(2)
        store.cell(for: headers).write(.string("{\"X-Snapshot\":\"two\"}"))
        await waitForRequests(3)
        guard ControlledURLProtocol.wasStopped(0), ControlledURLProtocol.wasStopped(1),
              ControlledURLProtocol.request(0).url?.absoluteString == "https://example.test/first",
              ControlledURLProtocol.request(1).value(forHTTPHeaderField: "X-Snapshot") == "one",
              ControlledURLProtocol.request(2).url?.absoluteString == "https://example.test/second",
              ControlledURLProtocol.request(2).value(forHTTPHeaderField: "X-Snapshot") == "two" else {
            fatalError("replacement or request snapshot was incorrect")
        }
        ControlledURLProtocol.respond(2, status: 200, body: "new")
        await waitForPhase(store, phase, "done")
        ControlledURLProtocol.respond(0, status: 200, body: "stale-zero")
        ControlledURLProtocol.respond(1, status: 200, body: "stale-one")
        await Task.yield()
        guard string(store.read(fetch)) == "new", string(store.read(computed)) == "new",
              string(store.read(errorSignal)).isEmpty else {
            fatalError("late completion overwrote current fetch")
        }

        store.cell(for: refresh).write(.int(int(store.read(refresh)) + 1))
        await waitForRequests(4)
        ControlledURLProtocol.respond(3, status: 503, body: String(repeating: "🙂", count: 1200))
        await waitForPhase(store, phase, "error")
        let bounded = string(store.read(errorSignal))
        guard bounded.hasPrefix("HTTP 503: "), bounded.unicodeScalars.count <= 1024,
              string(store.read(fetch)) == "new" else {
            fatalError("non-2xx error was unbounded or destroyed previous value")
        }

        store.cell(for: refresh).write(.int(int(store.read(refresh)) + 1))
        await waitForRequests(5)
        ControlledURLProtocol.respond(4, status: 200, data: Data([0xff]))
        await waitForPhase(store, phase, "error")
        guard string(store.read(errorSignal)).contains("not valid UTF-8"),
              string(store.read(fetch)) == "new" else {
            fatalError("invalid UTF-8 did not preserve previous fetch value")
        }
        store.cell(for: refresh).write(.int(int(store.read(refresh)) + 1))
        await waitForRequests(6)
        ControlledURLProtocol.fail(5, URLError(.timedOut))
        await waitForPhase(store, phase, "error")
        guard string(store.read(errorSignal)).hasPrefix("request failed: "),
              string(store.read(fetch)) == "new" else {
            fatalError("transport failure did not preserve previous fetch value")
        }
        store.cell(for: refresh).write(.int(int(store.read(refresh)) + 1))
        await waitForRequests(7)
        ControlledURLProtocol.fail(6, URLError(.cancelled))
        await waitForPhase(store, phase, "idle")
        guard string(store.read(errorSignal)).isEmpty else {
            fatalError("mounted cancellation did not return fetch to clean idle")
        }

        let authorityRequestCount = ControlledURLProtocol.count
        store.cell(for: url).write(.string("https:foo"))
        await waitForPhase(store, phase, "error")
        guard ControlledURLProtocol.count == authorityRequestCount,
              string(store.read(errorSignal)).contains("absolute http/https URL") else {
            fatalError("hostless https URL started transport")
        }
        store.cell(for: url).write(.string("http:///x"))
        await waitForPhase(store, phase, "error")
        guard ControlledURLProtocol.count == authorityRequestCount else {
            fatalError("empty-authority http URL started transport")
        }

        store.unsubscribe(token)
        guard store.fetchOwnerCount(for: fetch) == 0 else { fatalError("computed unsubscribe leaked fetch owner") }
        store.cell(for: url).write(.string("https://example.test/second"))
        let seedToken = store.subscribe(store.cell(for: seed))
        await waitForRequests(8)
        let keyClosure = SscClosure(arity: 1) { _ in .string("row") }
        let failingRender = SscClosure(arity: 1) { _ in
            MainActor.assumeIsolated { () -> Void in
                store.cell(for: seed).write(.string("provisional-seed"))
            }
            throw SscRuntimeFailure(description: "seed rollback")
        }
        do {
            _ = try store.reconcileKeyed(
                parentOwnerPath: "root", siteId: "seed-rollback",
                items: [.string("row")], key: keyClosure, render: failingRender)
            fatalError("seed rollback unexpectedly committed")
        } catch {
            guard String(describing: error).contains("seed rollback") else {
                fatalError("unexpected seed rollback error: \(error)")
            }
        }
        guard !ControlledURLProtocol.wasStopped(7), string(store.read(seed)) == "new" else {
            fatalError("rolled-back seed write released fetch dependency")
        }
        store.cell(for: seed).write(.string("local-seed"))
        await waitForStop(7)
        ControlledURLProtocol.respond(7, status: 200, body: "late-seed")
        await Task.yield()
        guard string(store.read(seed)) == "local-seed" else {
            fatalError("dirty seed retained fetch dependency or accepted late completion")
        }
        store.unsubscribe(seedToken)
        let secondToken = store.subscribe(store.cell(for: errorSignal))
        await waitForRequests(9)
        store.unsubscribe(secondToken)
        await waitForStop(8)
        ControlledURLProtocol.respond(8, status: 200, body: "disposed")
        await Task.yield()
        guard string(store.read(fetch)) == "new" else { fatalError("disposed completion mutated fetch") }

        ControlledURLProtocol.reset()
        let base = fields(baseAction, "NativeUiFetchAction")
        guard case let .map(baseStatus) = base[4] else { fatalError("action status missing") }
        let actionPhase = baseStatus.get(.string("phase"))!
        let actionError = baseStatus.get(.string("error"))!
        let owner = store.actionOwnerPath(for: baseAction, mountedAt: "root")

        store.runFetchAction(baseAction, ownerPath: owner)
        await waitForRequests(1)
        guard ControlledURLProtocol.body(0) == "body-one",
              ControlledURLProtocol.request(0).value(forHTTPHeaderField: "X-Snapshot") == "two" else {
            fatalError("action snapshot body=\(ControlledURLProtocol.body(0) ?? "<nil>") header=\(ControlledURLProtocol.request(0).value(forHTTPHeaderField: "X-Snapshot") ?? "<nil>")")
        }
        store.cell(for: body).write(.string("body-two"))
        store.cell(for: headers).write(.string("{\"X-Snapshot\":\"three\"}"))
        store.runFetchAction(baseAction, ownerPath: owner)
        await waitForRequests(2)
        guard ControlledURLProtocol.wasStopped(0),
              ControlledURLProtocol.body(1) == "body-two",
              ControlledURLProtocol.request(1).value(forHTTPHeaderField: "X-Snapshot") == "three" else {
            fatalError("action replacement lost click-time snapshot")
        }
        var mutations: [String] = []
        store.installMutationObserver { _, id in mutations.append(id) }
        ControlledURLProtocol.respond(1, status: 201, body: "reply")
        await waitForPhase(store, actionPhase, "done")
        ControlledURLProtocol.respond(0, status: 200, body: "stale-action")
        await Task.yield()
        let ordered = mutations.filter { ["effect", "refresh"].contains($0) }
        guard ordered == ["effect", "refresh", "refresh"],
              string(store.read(effect)) == "after", string(store.read(actionError)).isEmpty else {
            fatalError("source-ordered success effects did not run exactly")
        }

        store.cell(for: effect).write(.string("keep-effect"))
        store.runFetchAction(baseAction, ownerPath: owner)
        await waitForRequests(3)
        ControlledURLProtocol.respond(2, status: 500, body: "nope")
        await waitForPhase(store, actionPhase, "error")
        guard string(store.read(effect)) == "keep-effect" else {
            fatalError("non-2xx action ran success mutations")
        }

        store.runFetchAction(baseAction, ownerPath: owner)
        await waitForRequests(4)
        store.cancelFetchAction(baseAction, ownerPath: owner)
        await waitForStop(3)
        ControlledURLProtocol.respond(3, status: 200, body: "cancelled")
        await Task.yield()
        guard string(store.read(effect)) == "keep-effect",
              string(store.read(actionPhase)) == "idle",
              string(store.read(actionError)).isEmpty else {
            fatalError("cancelled action ran success mutations or left loading status")
        }
        mutations = []
        let captureFields = fields(captureAction, "NativeUiFetchAction")
        guard case let .map(captureStatus) = captureFields[4],
              let capturePhase = captureStatus.get(.string("phase")) else { fatalError("capture status missing") }
        let captureOwner = store.actionOwnerPath(for: captureAction, mountedAt: "root")
        store.runFetchAction(captureAction, ownerPath: captureOwner)
        await waitForRequests(5)
        ControlledURLProtocol.respond(4, status: 200, body: "captured")
        await waitForPhase(store, capturePhase, "done")
        let captureOrder = mutations.filter { ["capture", "refresh"].contains($0) }
        guard captureOrder == ["capture", "refresh"], string(store.read(capture)) == "captured" else {
            fatalError("capture did not precede its success effect")
        }
        mutations = []
        store.cell(for: body).write(.string("clear-me"))
        mutations = []
        let clearFields = fields(clearAction, "NativeUiFetchAction")
        guard case let .map(clearStatus) = clearFields[4],
              let clearPhase = clearStatus.get(.string("phase")) else { fatalError("clear status missing") }
        let clearOwner = store.actionOwnerPath(for: clearAction, mountedAt: "root")
        store.runFetchAction(clearAction, ownerPath: clearOwner)
        await waitForRequests(6)
        ControlledURLProtocol.respond(5, status: 200, body: "cleared")
        await waitForPhase(store, clearPhase, "done")
        let clearOrder = mutations.filter { ["body", "refresh"].contains($0) }
        guard clearOrder == ["body", "refresh"], string(store.read(body)).isEmpty else {
            fatalError("clear did not precede its success effect")
        }
        store.cell(for: body).write(.string("body-two"))
        let formOwner = store.actionOwnerPath(for: formAction, mountedAt: "root")
        store.runFetchAction(formAction, ownerPath: formOwner)
        await waitForRequests(7)
        guard ControlledURLProtocol.body(6) == "{\"body\":\"body-two\",\"renamed\":\"keep-effect\"}",
              ControlledURLProtocol.request(6).value(forHTTPHeaderField: "X-Snapshot") == "three" else {
            fatalError("formBody did not snapshot named signals into deterministic JSON")
        }
        ControlledURLProtocol.respond(6, status: 204, body: "")
        let formFields = fields(formAction, "NativeUiFetchAction")
        guard case let .map(formStatus) = formFields[4],
              let formPhase = formStatus.get(.string("phase")),
              let formError = formStatus.get(.string("error")) else { fatalError("form status missing") }
        await waitForPhase(store, formPhase, "done")

        ControlledURLProtocol.reset()
        store.cell(for: headers).write(.string("{\"X-Snapshot\":\"projected\"}"))
        store.cell(for: refresh).write(.int(Int64.max - 2))
        store.runFetchAction(baseAction, ownerPath: owner)
        await waitForRequests(1)
        store.cell(for: refresh).write(.int(Int64.max - 1))
        ControlledURLProtocol.respond(0, status: 200, body: "ok")
        await waitForPhase(store, actionPhase, "error")
        guard int(store.read(refresh)) == Int64.max - 1,
              string(store.read(actionError)).contains("bumpTick requires writable Int") else {
            fatalError("response-time projected plan trapped or partially bumped: refresh=\(int(store.read(refresh))) error=\(string(store.read(actionError))) phase=\(string(store.read(actionPhase)))")
        }
        store.cell(for: refresh).write(.int(0))
        store.cell(for: headers).write(.string("{\"Bad\":1}"))
        store.runFetchAction(formAction, ownerPath: formOwner)
        guard ControlledURLProtocol.count == 1,
              string(store.read(formPhase)) == "error",
              string(store.read(formError)).contains("header 'Bad' must be String") else {
            fatalError("malformed headers started transport or lost deterministic error: count=\(ControlledURLProtocol.count) phase=\(string(store.read(formPhase))) error=\(string(store.read(formError))) failure=\(store.failure ?? "nil")")
        }
        store.cell(for: headers).write(.string("{\"X-Bad\":\"ok\\r\\nInjected: yes\"}"))
        store.runFetchAction(formAction, ownerPath: formOwner)
        guard ControlledURLProtocol.count == 1,
              string(store.read(formError)).contains("control character") else {
            fatalError("header injection escaped deterministic preflight")
        }
        let aliasOwner = store.actionOwnerPath(for: aliasAction, mountedAt: "root")
        store.runFetchAction(aliasAction, ownerPath: aliasOwner)
        guard ControlledURLProtocol.count == 1,
              store.failure?.contains("bumpTick requires writable non-overflowing Int") == true else {
            fatalError("capture/effect alias escaped preflight and started transport")
        }
        let forgedStatus = SscValue.data("NativeUiFetchAction", [
            base[0], base[1], base[2], base[3], formFields[4]
        ])
        store.runFetchAction(forgedStatus, ownerPath: owner)
        guard ControlledURLProtocol.count == 1,
              store.failure?.contains("status capability mismatch") == true else {
            fatalError("cross-action phase/error capability was accepted")
        }
        ControlledURLProtocol.reset()
        store.cell(for: headers).write(.string("{\"X-Snapshot\":\"shared\"}"))
        store.cell(for: refresh).write(.int(0))
        let ownerA = store.actionOwnerPath(for: baseAction, mountedAt: "owner-a")
        let ownerB = store.actionOwnerPath(for: baseAction, mountedAt: "owner-b")
        guard ownerA != ownerB else { fatalError("shared action lost containing mount owner") }
        store.runFetchAction(baseAction, ownerPath: ownerA)
        store.runFetchAction(baseAction, ownerPath: ownerB)
        await waitForRequests(2)
        guard !ControlledURLProtocol.wasStopped(0), !ControlledURLProtocol.wasStopped(1) else {
            fatalError("shared action mounts cancelled each other")
        }
        store.cancelFetchAction(baseAction, ownerPath: ownerA)
        for _ in 0..<200 where !ControlledURLProtocol.wasStopped(0) && !ControlledURLProtocol.wasStopped(1) {
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        let stoppedIndex = ControlledURLProtocol.wasStopped(0) ? 0 : 1
        let liveIndex = stoppedIndex == 0 ? 1 : 0
        guard ControlledURLProtocol.wasStopped(stoppedIndex),
              !ControlledURLProtocol.wasStopped(liveIndex),
              string(store.read(actionPhase)) == "loading" else {
            fatalError("cancel A cancelled zero/both mounts or reset shared loading status")
        }
        ControlledURLProtocol.respond(liveIndex, status: 200, body: "owner-b")
        await waitForPhase(store, actionPhase, "done")
        ControlledURLProtocol.respond(stoppedIndex, status: 200, body: "late-owner-a")
        await Task.yield()
        guard string(store.read(actionError)).isEmpty,
              store.networkMetadataCount() == 0 else {
            fatalError("shared owner B lost completion or leaked task metadata")
        }

        ControlledURLProtocol.reset()
        store.runFetchAction(baseAction, ownerPath: ownerA)
        store.runFetchAction(baseAction, ownerPath: ownerB)
        await waitForRequests(2)
        store.cancelFetchAction(baseAction, ownerPath: ownerA)
        for _ in 0..<200 where !ControlledURLProtocol.wasStopped(0) && !ControlledURLProtocol.wasStopped(1) {
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        guard string(store.read(actionPhase)) == "loading" else {
            fatalError("first shared cancellation reset phase")
        }
        store.cancelFetchAction(baseAction, ownerPath: ownerB)
        for _ in 0..<200 where !ControlledURLProtocol.wasStopped(0) || !ControlledURLProtocol.wasStopped(1) {
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        guard ControlledURLProtocol.wasStopped(0), ControlledURLProtocol.wasStopped(1),
              string(store.read(actionPhase)) == "idle",
              string(store.read(actionError)).isEmpty,
              store.networkMetadataCount() == 0 else {
            fatalError("last shared cancellation did not reset idle or leaked metadata")
        }
        ControlledURLProtocol.reset()
        var opened: [String] = []
        store.installOpenURL { opened.append($0.absoluteString) }
        let openFields = fields(openAction, "NativeUiFetchAction")
        guard case let .map(openStatus) = openFields[4],
              let openPhase = openStatus.get(.string("phase")) else { fatalError("open status missing") }
        let openOwner = store.actionOwnerPath(for: openAction, mountedAt: "owner-open")
        store.runFetchAction(openAction, ownerPath: openOwner)
        await waitForRequests(1)
        ControlledURLProtocol.respond(0, status: 200, body: "{\"id\":\"a/b?x#y\"}")
        await waitForPhase(store, openPhase, "done")
        guard opened == ["https://example.test/items/a%2Fb%3Fx%23y"] else {
            fatalError("openJson did not use encodeURIComponent-equivalent substitution")
        }
        store.runFetchAction(openAction, ownerPath: openOwner)
        await waitForRequests(2)
        ControlledURLProtocol.respond(1, status: 200, body: "{\"id\":true}")
        await waitForPhase(store, openPhase, "error")
        guard opened.count == 1 else { fatalError("openJson accepted Bool as number") }
        let unsafeOwner = store.actionOwnerPath(for: unsafeAction, mountedAt: "root")
        store.runFetchAction(unsafeAction, ownerPath: unsafeOwner)
        guard ControlledURLProtocol.count == 2,
              store.failure?.contains("absolute http/https/mailto URL") == true else {
            fatalError("javascript navigation escaped sourced preflight")
        }
        let hashOwner = store.actionOwnerPath(for: hashAction, mountedAt: "root")
        store.runFetchAction(hashAction, ownerPath: hashOwner)
        guard ControlledURLProtocol.count == 2,
              store.failure?.contains("absolute http/https/mailto URL") == true else {
            fatalError("hash navigation escaped sourced preflight")
        }
        let safeFields = fields(safeAction, "NativeUiFetchAction")
        guard case let .map(safeStatus) = safeFields[4],
              let safePhase = safeStatus.get(.string("phase")) else { fatalError("safe status missing") }
        let safeOwner = store.actionOwnerPath(for: safeAction, mountedAt: "root")
        store.runFetchAction(safeAction, ownerPath: safeOwner)
        await waitForRequests(3)
        ControlledURLProtocol.respond(2, status: 200, body: "ok")
        await waitForPhase(store, safePhase, "done")
        guard opened.last == "https://example.test/done" else {
            fatalError("safe navigate did not use SwiftUI openURL")
        }
        await assertNoStoreLeak()
        Swift.print("family|replace|snapshot|bounded|cancel|ordered|failure|form|projected|shared|open-json")
    }
}
"""

  private val nativeUiKeyedActionLifecycleProbe = """
import Foundation

final class LifecycleURLProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var instances: [LifecycleURLProtocol] = []
    nonisolated(unsafe) private static var stoppedIndices = Set<Int>()

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); Self.instances.append(self); Self.lock.unlock()
    }
    override func stopLoading() {
        Self.lock.lock()
        if let index = Self.instances.firstIndex(where: { $0 === self }) {
            Self.stoppedIndices.insert(index)
        }
        Self.lock.unlock()
    }
    static var count: Int {
        lock.lock(); defer { lock.unlock() }; return instances.count
    }
    static func wasStopped(_ index: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }; return stoppedIndices.contains(index)
    }
    static func respond(_ index: Int, status: Int, body: String) {
        lock.lock(); let instance = instances[index]; lock.unlock()
        let response = HTTPURLResponse(
            url: instance.request.url!, statusCode: status,
            httpVersion: "HTTP/1.1", headerFields: nil)!
        instance.client?.urlProtocol(instance, didReceive: response, cacheStoragePolicy: .notAllowed)
        instance.client?.urlProtocol(instance, didLoad: Data(body.utf8))
        instance.client?.urlProtocolDidFinishLoading(instance)
    }
}

@main
struct KeyedActionProbe {
    private static func fields(_ value: SscValue, _ tag: String) -> SscFields {
        guard case let .data(actual, result) = value, actual == tag else { fatalError("expected \(tag)") }
        return result
    }
    private static func list(_ value: SscValue) -> [SscValue] {
        var current = value, result: [SscValue] = []
        while true {
            switch current {
            case let .data("Cons", fields) where fields.count == 2:
                result.append(fields[0]); current = fields[1]
            case .data("Nil", _): return result
            default: fatalError("expected list")
            }
        }
    }
    private static func signal(_ node: SscValue) -> SscValue {
        fields(node, "NativeUiSignalText")[0]
    }
    private static func string(_ value: SscValue) -> String {
        guard case let .string(result) = value else { fatalError("expected string") }
        return result
    }
    private static func closure(_ value: SscValue) -> SscClosure {
        guard case let .closure(result) = value else { fatalError("expected closure") }
        return result
    }
    private static func status(_ action: SscValue) -> (SscValue, SscValue) {
        let actionFields = fields(action, "NativeUiFetchAction")
        guard case let .map(values) = actionFields[4],
              let phase = values.get(.string("phase")),
              let error = values.get(.string("error")) else { fatalError("missing action status") }
        return (phase, error)
    }
    private static func waitForRequest(_ count: Int) async {
        for _ in 0..<200 {
            if LifecycleURLProtocol.count >= count { return }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        fatalError("timed out waiting for request \(count)")
    }
    private static func waitForStop(_ index: Int) async {
        for _ in 0..<200 {
            if LifecycleURLProtocol.wasStopped(index) { return }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        fatalError("timed out waiting for cancellation")
    }

    @MainActor
    static func main() async {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [LifecycleURLProtocol.self]
        let store = NativeUiStore(urlSession: URLSession(configuration: configuration))
        store.installOpenURL { _ in fatalError("unsafe navigation reached openURL") }
        let abi = fields(store.root, "NativeUiAbi")
        let children = list(fields(abi[1], "NativeUiFragment")[0])
        let mode = signal(children[0])
        let effect = signal(children[4])
        let keyed = fields(children[5], "NativeUiForKeyed")
        _ = list(store.read(keyed[1]))
        let stableKey = SscClosure(arity: 1) { _ in .string("row") }
        func reconcile(_ item: String) throws -> NativeUiKeyedResult {
            try store.reconcileKeyed(
                parentOwnerPath: "root", siteId: string(keyed[0]), items: [.string(item)],
                key: stableKey, render: closure(keyed[3]))
        }

        let baseSignals = store.hostSignalCount()
        let first = try! reconcile("v1")
        guard first.entries.count == 1 else { fatalError("missing first keyed action") }
        let firstAction = first.entries[0].value
        guard case let .data("NativeUiFetchAction", firstFields) = firstAction,
              store.actionStatusHintCount() == 1,
              store.hostSignalCount() == baseSignals + 5 else { fatalError("first action ownership was not exact") }
        let firstOwner = store.actionOwnerPath(for: firstAction, mountedAt: first.entries[0].ownerPath)
        store.runFetchAction(firstAction, ownerPath: firstOwner)
        await waitForRequest(1)

        let firstStatus = status(firstAction)
        let same = try! reconcile("v2")
        let sameAction = same.entries[0].value
        let sameStatus = status(sameAction)
        let firstPhaseFields = fields(firstStatus.0, "NativeUiSignal")
        let samePhaseFields = fields(sameStatus.0, "NativeUiSignal")
        guard string(firstPhaseFields[0]) == string(samePhaseFields[0]),
              string(firstPhaseFields[1]) == string(samePhaseFields[1]),
              string(store.read(sameStatus.0)) == "loading",
              !LifecycleURLProtocol.wasStopped(0),
              store.networkMetadataCount() == 1 else {
            fatalError("stable same-key action refresh cancelled or reset live work")
        }

        store.cell(for: mode).write(.string("without"))
        _ = try! reconcile("v3")
        await waitForStop(0)
        guard store.networkMetadataCount() == 0,
              store.actionStatusHintCount() == 0,
              store.hostSignalCount() == baseSignals + 3 else { fatalError("disposed action leaked task, hint, or signals") }
        LifecycleURLProtocol.respond(0, status: 200, body: "late")
        await Task.yield()
        guard string(store.read(effect)) == "before" else { fatalError("late disposed action committed success effects") }
        store.runFetchAction(firstAction, ownerPath: firstOwner)
        guard LifecycleURLProtocol.count == 1,
              store.failure?.contains("status capability mismatch") == true else {
            fatalError("disposed action capability remained executable")
        }

        store.cell(for: mode).write(.string("with"))
        let fresh = try! reconcile("v4")
        let freshAction = fresh.entries[0].value
        guard case let .data("NativeUiFetchAction", freshFields) = freshAction,
              firstFields !== freshFields else { fatalError("reinsertion reused action identity") }
        let freshStatus = status(freshAction)
        guard string(store.read(freshStatus.0)) == "idle",
              string(store.read(freshStatus.1)).isEmpty,
              store.actionStatusHintCount() == 1,
              store.hostSignalCount() == baseSignals + 5 else { fatalError("reinserted action was not fresh") }

        let freshOwner = store.actionOwnerPath(for: freshAction, mountedAt: fresh.entries[0].ownerPath)
        store.runFetchAction(freshAction, ownerPath: freshOwner)
        await waitForRequest(2)
        store.cancelFetchAction(firstAction, ownerPath: freshOwner)
        await Task.yield()
        guard !LifecycleURLProtocol.wasStopped(1),
              string(store.read(freshStatus.0)) == "loading",
              store.networkMetadataCount() == 1 else {
            fatalError("stale removed action cancelled fresh replacement")
        }
        store.cell(for: mode).write(.string("data"))
        let dataAction = try! reconcile("v5").entries[0]
        await waitForStop(1)
        let dataStatus = status(dataAction.value)
        guard string(store.read(dataStatus.0)) == "idle",
              string(store.read(dataStatus.1)).isEmpty,
              store.networkMetadataCount() == 0 else {
            fatalError("replaced action did not cancel and reset exact status")
        }
        let dataOwner = store.actionOwnerPath(for: dataAction.value, mountedAt: dataAction.ownerPath)
        store.runFetchAction(dataAction.value, ownerPath: dataOwner)
        guard LifecycleURLProtocol.count == 2,
              store.failure?.contains("absolute http/https/mailto URL") == true else {
            fatalError("unsafe data navigation started transport")
        }
        store.cell(for: mode).write(.string("file"))
        let fileAction = try! reconcile("v6").entries[0]
        let fileOwner = store.actionOwnerPath(for: fileAction.value, mountedAt: fileAction.ownerPath)
        store.runFetchAction(fileAction.value, ownerPath: fileOwner)
        guard LifecycleURLProtocol.count == 2,
              store.failure?.contains("absolute http/https/mailto URL") == true else {
            fatalError("unsafe file navigation started transport")
        }
        for unsafeMode in ["hostless", "unsafe-open", "hostless-open"] {
            store.cell(for: mode).write(.string(unsafeMode))
            let unsafe = try! reconcile("unsafe-\(unsafeMode)").entries[0]
            let unsafeOwner = store.actionOwnerPath(for: unsafe.value, mountedAt: unsafe.ownerPath)
            store.runFetchAction(unsafe.value, ownerPath: unsafeOwner)
            guard LifecycleURLProtocol.count == 2 else {
                fatalError("unsafe \(unsafeMode) descriptor started transport")
            }
        }
        store.cell(for: mode).write(.string("bad-method"))
        do {
            _ = try reconcile("v7")
            fatalError("invalid HTTP method was accepted")
        } catch {
            guard String(describing: error).contains("RFC HTTP token"),
                  LifecycleURLProtocol.count == 2,
                  store.actionStatusHintCount() == 1,
                  store.hostSignalCount() == baseSignals + 5 else {
                fatalError("invalid method rollback leaked state: \(error)")
            }
        }

        store.cell(for: mode).write(.string("without"))
        _ = try! reconcile("v8")
        for _ in 0..<20 {
            store.cell(for: mode).write(.string("with"))
            _ = try! reconcile("churn-with")
            guard store.actionStatusHintCount() == 1,
                  store.hostSignalCount() == baseSignals + 5 else { fatalError("action churn grew live metadata") }
            store.cell(for: mode).write(.string("without"))
            _ = try! reconcile("churn-without")
            guard store.actionStatusHintCount() == 0,
                  store.hostSignalCount() == baseSignals + 3 else { fatalError("action churn left tombstones") }
        }
        guard store.networkMetadataCount() == 0 else { fatalError("action churn leaked network metadata") }
        Swift.print("disposed|late-inert|fresh|unsafe|bounded")
    }
}
"""

  private val nativeUiKeyedFetchMetadataProbe = """
import Foundation

final class FetchURLProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var instances: [FetchURLProtocol] = []
    nonisolated(unsafe) private static var stoppedIndices = Set<Int>()
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); Self.instances.append(self); Self.lock.unlock()
    }
    override func stopLoading() {
        Self.lock.lock()
        if let index = Self.instances.firstIndex(where: { $0 === self }) { Self.stoppedIndices.insert(index) }
        Self.lock.unlock()
    }
    static var count: Int { lock.lock(); defer { lock.unlock() }; return instances.count }
    static func request(_ index: Int) -> URLRequest {
        lock.lock(); defer { lock.unlock() }; return instances[index].request
    }
    static func wasStopped(_ index: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }; return stoppedIndices.contains(index)
    }
    static func respond(_ index: Int, body: String) {
        lock.lock(); let instance = instances[index]; lock.unlock()
        let response = HTTPURLResponse(
            url: instance.request.url!, statusCode: 200,
            httpVersion: "HTTP/1.1", headerFields: nil)!
        instance.client?.urlProtocol(instance, didReceive: response, cacheStoragePolicy: .notAllowed)
        instance.client?.urlProtocol(instance, didLoad: Data(body.utf8))
        instance.client?.urlProtocolDidFinishLoading(instance)
    }
}

@main
struct KeyedFetchProbe {
    private static func fields(_ value: SscValue, _ tag: String) -> SscFields {
        guard case let .data(actual, result) = value, actual == tag else { fatalError("expected \(tag)") }
        return result
    }
    private static func list(_ value: SscValue) -> [SscValue] {
        var current = value, result: [SscValue] = []
        while true {
            switch current {
            case let .data("Cons", fields) where fields.count == 2:
                result.append(fields[0]); current = fields[1]
            case .data("Nil", _): return result
            default: fatalError("expected list")
            }
        }
    }
    private static func signal(_ node: SscValue) -> SscValue { fields(node, "NativeUiSignalText")[0] }
    private static func string(_ value: SscValue) -> String {
        guard case let .string(result) = value else { fatalError("expected string") }
        return result
    }
    private static func closure(_ value: SscValue) -> SscClosure {
        guard case let .closure(result) = value else { fatalError("expected closure") }
        return result
    }
    private static func waitForRequest(_ count: Int) async {
        for _ in 0..<200 {
            if FetchURLProtocol.count >= count { return }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        fatalError("timed out waiting for request \(count)")
    }
    private static func waitForStop(_ index: Int) async {
        for _ in 0..<200 {
            if FetchURLProtocol.wasStopped(index) { return }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        fatalError("timed out waiting for stop \(index)")
    }
    @MainActor
    private static func waitForValue(_ store: NativeUiStore, _ signal: SscValue, _ expected: String) async {
        for _ in 0..<200 {
            if string(store.read(signal)) == expected { return }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        fatalError("timed out waiting for fetch value")
    }

    @MainActor
    static func main() async {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [FetchURLProtocol.self]
        let store = NativeUiStore(urlSession: URLSession(configuration: configuration))
        let abi = fields(store.root, "NativeUiAbi")
        let children = list(fields(abi[1], "NativeUiFragment")[0])
        let mode = signal(children[0])
        let keyed = fields(children[1], "NativeUiForKeyed")
        let stableKey = SscClosure(arity: 1) { _ in .string("row") }
        func reconcile(_ item: String) throws -> NativeUiKeyedResult {
            try store.reconcileKeyed(
                parentOwnerPath: "root", siteId: string(keyed[0]), items: [.string(item)],
                key: stableKey, render: closure(keyed[3]))
        }
        func fetch(_ result: NativeUiKeyedResult) -> SscValue { signal(result.entries[0].value) }

        let firstFetch = fetch(try! reconcile("v1"))
        let firstCell = store.cell(for: firstFetch)
        let token = store.subscribe(firstCell)
        await waitForRequest(1)
        guard FetchURLProtocol.request(0).url?.absoluteString == "https://example.test/a" else {
            fatalError("initial literal URL was wrong")
        }

        let sameFetch = fetch(try! reconcile("v2"))
        let sameCell = store.cell(for: sameFetch)
        await Task.yield()
        guard sameCell === firstCell, FetchURLProtocol.count == 1,
              !FetchURLProtocol.wasStopped(0) else { fatalError("identical metadata restarted or replaced stable cell") }

        store.cell(for: mode).write(.string("b"))
        let secondFetch = fetch(try! reconcile("v3"))
        await waitForRequest(2)
        await waitForStop(0)
        guard FetchURLProtocol.request(1).url?.absoluteString == "https://example.test/b" else {
            fatalError("changed literal URL did not restart with B")
        }
        FetchURLProtocol.respond(0, body: "late-a")
        await Task.yield()
        guard string(store.read(secondFetch)).isEmpty else { fatalError("late A completion overwrote replacement") }
        FetchURLProtocol.respond(1, body: "current-b")
        await waitForValue(store, secondFetch, "current-b")

        store.cell(for: mode).write(.string("ref"))
        let refFetch = fetch(try! reconcile("v4"))
        await waitForRequest(3)
        guard store.cell(for: refFetch) === firstCell,
              FetchURLProtocol.request(2).url?.absoluteString == "https://example.test/b" else {
            fatalError("signal URL source did not resolve B")
        }
        _ = fetch(try! reconcile("v5"))
        await Task.yield()
        guard FetchURLProtocol.count == 3, !FetchURLProtocol.wasStopped(2) else {
            fatalError("identical signal-ref metadata duplicated request")
        }
        store.cell(for: mode).write(.string("double"))
        let doubleFetch = fetch(try! reconcile("v6"))
        await waitForRequest(4)
        await waitForStop(2)
        guard FetchURLProtocol.count == 4,
              FetchURLProtocol.request(3).url?.absoluteString == "https://example.test/b" else {
            fatalError("one transaction A then B did not coalesce to one final B restart")
        }
        _ = fetch(try! reconcile("v7"))
        await Task.yield()
        guard FetchURLProtocol.count == 4, !FetchURLProtocol.wasStopped(3) else {
            fatalError("transaction ending at unchanged B restarted the family")
        }
        store.unsubscribe(token)
        await waitForStop(3)
        guard store.fetchOwnerCount(for: doubleFetch) == 0,
              store.networkMetadataCount() == 0 else { fatalError("fetch metadata lifecycle leaked") }
        Swift.print("identical|literal-restart|late-inert|ref-restart|coalesced|bounded")
    }
}
"""

  private val nativeUiPlatformSignalsProbe = """
import Foundation

final class ControlledOnlineMonitor: NativeUiOnlineMonitoring, @unchecked Sendable {
    private let lock = NSLock()
    private var callbacks: [@Sendable (Bool) -> Void] = []
    private var cancels = 0

    func start(_ update: @escaping @Sendable (Bool) -> Void) {
        lock.lock()
        callbacks.append(update)
        lock.unlock()
    }

    func cancel() {
        lock.lock()
        cancels += 1
        lock.unlock()
    }

    func emitFromBackground(_ generation: Int, _ online: Bool) {
        lock.lock()
        let callback = callbacks[generation]
        lock.unlock()
        DispatchQueue.global().async { callback(online) }
    }

    var startCount: Int {
        lock.lock(); defer { lock.unlock() }; return callbacks.count
    }

    var cancelCount: Int {
        lock.lock(); defer { lock.unlock() }; return cancels
    }
}

@main
struct PlatformSignalsProbe {
    private static func fields(_ value: SscValue, _ tag: String) -> [SscValue] {
        guard case let .data(actual, fields) = value, actual == tag else {
            fatalError("expected \(tag)")
        }
        return fields.asArray()
    }

    private static func list(_ value: SscValue) -> [SscValue] {
        var current = value, result: [SscValue] = []
        while true {
            switch current {
            case let .data("Cons", fields) where fields.count == 2:
                result.append(fields[0]); current = fields[1]
            case .data("Nil", _): return result
            default: fatalError("expected list")
            }
        }
    }

    private static func signal(_ node: SscValue) -> SscValue {
        fields(node, "NativeUiSignalText")[0]
    }

    private static func string(_ value: SscValue) -> String {
        guard case let .string(result) = value else { fatalError("expected string") }
        return result
    }

    private static func bool(_ value: SscValue) -> Bool {
        guard case let .bool(result) = value else { fatalError("expected bool") }
        return result
    }

    private static func closure(_ value: SscValue) -> SscClosure {
        guard case let .closure(result) = value else { fatalError("expected closure") }
        return result
    }

    private static func signalKey(_ value: SscValue) -> String {
        let parts = fields(value, "NativeUiSignal")
        return string(parts[1]) + "\u{0}" + string(parts[0])
    }

    @MainActor
    private static func waitForBool(_ store: NativeUiStore, _ signal: SscValue, _ expected: Bool) async {
        for _ in 0..<400 {
            if bool(store.read(signal)) == expected { return }
            await Task.yield()
        }
        fatalError("timed out waiting for online=\(expected)")
    }

    @MainActor
    static func main() async {
        let suite = "ssc.swiftui.platform." + UUID().uuidString
        guard let defaults = UserDefaults(suiteName: suite) else { fatalError("suite defaults") }
        defaults.removePersistentDomain(forName: suite)
        defaults.set("disk-before", forKey: "draft")
        defaults.set("boot-before", forKey: "boot")
        defaults.set("failed-before", forKey: "failed-root")
        defer { defaults.removePersistentDomain(forName: suite) }

        let monitor = ControlledOnlineMonitor()
        weak var releasedStore: NativeUiStore?
        var releasedWrite: SscClosure?
        do {
            let store = NativeUiStore(
                userDefaults: defaults,
                onlineMonitorFactory: { monitor })
            releasedStore = store
            let abi = fields(store.root, "NativeUiAbi")
            let children = list(fields(abi[1], "NativeUiFragment")[0])
            let persisted = signal(children[0])
            let boot = signal(children[1])
            let hidden = signal(children[2])
            let online = signal(children[3])
            let derived = signal(children[4])
            let scopedFirst = signal(children[5])
            let scopedSecond = signal(children[6])
            let keyed = fields(children[7], "NativeUiForKeyed")

            guard defaults.string(forKey: "boot") == "boot-committed",
                  string(store.read(boot)) == "boot-committed" else {
                fatalError("successful root-evaluation write was not committed")
            }
            guard signalKey(online) == "root\u{0}__online__",
                  signalKey(scopedFirst) == signalKey(online),
                  signalKey(scopedSecond) == signalKey(online) else {
                fatalError("component online signals were not process-wide")
            }

            let hiddenWrite = closure(fields(hidden, "NativeUiSignal")[4])
            do { _ = try hiddenWrite.native!([.string("hidden-after")]) }
            catch { fatalError("no-cell persisted write failed: \(error)") }
            guard defaults.string(forKey: "hidden") == "hidden-after" else {
                fatalError("no-cell persisted write did not reach UserDefaults")
            }

            let persistedCell = store.cell(for: persisted)
            guard string(persistedCell.read()) == "disk-before" else {
                fatalError("UserDefaults did not override persisted fallback")
            }
            let persistedWrite = closure(fields(persisted, "NativeUiSignal")[4])
            do {
                _ = try persistedWrite.native!([.int(7)])
                fatalError("wrong-type persisted write unexpectedly succeeded")
            } catch {
                guard String(describing: error).contains("persisted value must be String") else {
                    fatalError("wrong persisted type diagnostic: \(error)")
                }
            }
            guard string(persistedCell.read()) == "disk-before",
                  defaults.string(forKey: "draft") == "disk-before" else {
                fatalError("wrong-type persisted write corrupted state")
            }
            persistedCell.write(.string("disk-after"))
            guard defaults.string(forKey: "draft") == "disk-after" else {
                fatalError("committed persisted write did not reach UserDefaults")
            }

            let key = SscClosure(arity: 1) { _ in .string("row") }
            let failing = SscClosure(arity: 1) { _ in
                MainActor.assumeIsolated { () -> Void in
                    persistedCell.write(.string("provisional"))
                }
                throw SscRuntimeFailure(description: "persisted rollback")
            }
            do {
                _ = try store.reconcileKeyed(
                    parentOwnerPath: "root", siteId: "persisted-rollback",
                    items: [.string("row")], key: key, render: failing)
                fatalError("persisted rollback unexpectedly committed")
            } catch {
                guard String(describing: error).contains("persisted rollback") else {
                    fatalError("wrong rollback error: \(error)")
                }
            }
            guard string(persistedCell.read()) == "disk-after",
                  defaults.string(forKey: "draft") == "disk-after" else {
                fatalError("rolled-back persisted write escaped")
            }

            let scopedFirstResult = try! store.reconcileKeyed(
                parentOwnerPath: "root", siteId: string(keyed[0]),
                items: [.string("row")], key: closure(keyed[2]), render: closure(keyed[3]))
            guard scopedFirstResult.entries.count == 1 else { fatalError("scoped persisted insert") }
            let firstScopedSignal = signal(scopedFirstResult.entries[0].value)
            let staleWrite = closure(fields(firstScopedSignal, "NativeUiSignal")[4])
            do { _ = try staleWrite.native!([.string("scoped-before")]) }
            catch { fatalError("scoped persisted commit failed: \(error)") }
            guard defaults.string(forKey: "scoped") == "scoped-before" else {
                fatalError("scoped persisted commit missed disk")
            }
            _ = try! store.reconcileKeyed(
                parentOwnerPath: "root", siteId: string(keyed[0]),
                items: [], key: closure(keyed[2]), render: closure(keyed[3]))
            do {
                _ = try staleWrite.native!([.string("scoped-stale")])
                fatalError("disposed persisted wrapper unexpectedly wrote")
            } catch {
                guard String(describing: error).contains("no longer live") else {
                    fatalError("wrong disposed wrapper error: \(error)")
                }
            }
            guard defaults.string(forKey: "scoped") == "scoped-before" else {
                fatalError("disposed persisted wrapper changed disk")
            }
            let scopedFreshResult = try! store.reconcileKeyed(
                parentOwnerPath: "root", siteId: string(keyed[0]),
                items: [.string("row")], key: closure(keyed[2]), render: closure(keyed[3]))
            let freshScopedSignal = signal(scopedFreshResult.entries[0].value)
            let freshWrite = closure(fields(freshScopedSignal, "NativeUiSignal")[4])
            do { _ = try freshWrite.native!([.string("scoped-fresh")]) }
            catch { fatalError("fresh persisted wrapper failed: \(error)") }
            guard defaults.string(forKey: "scoped") == "scoped-fresh" else {
                fatalError("fresh persisted wrapper missed disk")
            }
            releasedWrite = freshWrite

            let derivedCell = store.cell(for: derived)
            let first = store.subscribe(derivedCell)
            guard monitor.startCount == 1, store.onlineOwnerCount() == 1,
                  store.onlineMonitorActive() else { fatalError("derived owner did not start monitor") }
            monitor.emitFromBackground(0, false)
            await waitForBool(store, derived, false)
            guard derivedCell.revision == 1 else {
                fatalError("online callback did not recompute derived signal")
            }

            let onlineCell = store.cell(for: scopedFirst)
            guard bool(onlineCell.read()) == false, bool(store.read(scopedSecond)) == false else {
                fatalError("late component owner did not see current online state")
            }
            let second = store.subscribe(onlineCell)
            guard monitor.startCount == 1, store.onlineOwnerCount() == 2 else {
                fatalError("second owner started another monitor")
            }
            store.unsubscribe(first)
            guard monitor.cancelCount == 0, store.onlineOwnerCount() == 1 else {
                fatalError("first unsubscribe cancelled shared monitor")
            }
            store.unsubscribe(second)
            guard monitor.cancelCount == 1, store.onlineOwnerCount() == 0,
                  !store.onlineMonitorActive() else { fatalError("last unsubscribe did not cancel monitor") }

            let stoppedRevision = onlineCell.revision
            _ = store.subscribe(store.cell(for: scopedSecond))
            guard monitor.startCount == 2, store.onlineOwnerCount() == 1 else {
                fatalError("later first owner did not restart monitor")
            }
            monitor.emitFromBackground(0, true)
            for _ in 0..<40 { await Task.yield() }
            guard bool(onlineCell.read()) == false, onlineCell.revision == stoppedRevision else {
                fatalError("stale monitor generation mutated restarted online signal")
            }
            monitor.emitFromBackground(1, true)
            await waitForBool(store, online, true)

            let failedHost = NativeUiHost(
                persistedRead: { defaults.string(forKey: $0) },
                persistedWrite: { defaults.set($1, forKey: $0) })
            let failedProgram = SscProgram(definitions: [], entry: .letBindings([
                .apply(.global("persistedSignal"), [
                    .literal(.string("failed-root")), .literal(.string("fallback"))])
            ], .sequence([
                .primitive("__method__", [
                    .literal(.string("set")), .local(0), .literal(.string("escaped"))]),
                .literal(.unit)
            ])), fieldLayouts: [:])
            do {
                _ = try failedHost.evaluate(failedProgram)
                fatalError("failed root unexpectedly evaluated")
            } catch {
                guard String(describing: error).contains("did not register a root") else {
                    fatalError("wrong failed-root error: \(error)")
                }
            }
            guard defaults.string(forKey: "failed-root") == "failed-before" else {
                fatalError("failed root-evaluation write escaped")
            }
            let recoveredProgram = SscProgram(definitions: [], entry: .letBindings([
                .apply(.global("persistedSignal"), [
                    .literal(.string("failed-root")), .literal(.string("fallback"))])
            ], .apply(.global("emit"), [
                .apply(.global("signalText"), [.local(0)]), .literal(.string("out"))
            ])), fieldLayouts: [:])
            do {
                let recovered = try failedHost.evaluate(recoveredProgram)
                let recoveredAbi = fields(recovered.root, "NativeUiAbi")
                let recoveredSignal = signal(recoveredAbi[1])
                guard string(try recovered.read(recoveredSignal)) == "failed-before" else {
                    fatalError("failed root left staged persisted memory")
                }
            } catch { fatalError("failed Host did not recover: \(error)") }
        }

        for _ in 0..<40 where releasedStore != nil { await Task.yield() }
        guard releasedStore == nil, monitor.cancelCount == 2,
              defaults.string(forKey: "hidden") == "hidden-after" else {
            fatalError("root disposal did not release online monitor")
        }
        do {
            _ = try releasedWrite!.native!([.string("after-dispose")])
            fatalError("released persisted wrapper unexpectedly wrote")
        } catch {
            guard String(describing: error).contains("no longer live"),
                  defaults.string(forKey: "scoped") == "scoped-fresh" else {
                fatalError("released persisted wrapper was not inert: \(error)")
            }
        }
        Swift.print("defaults|rollback|first-last|main-actor|restart")
    }
}
"""

  private def nativeUiSharedScopeProgram(): Program =
    val items = Term.App(Term.Global("signal"), List(str("items"), list(List(str("a"), str("b")))))
    val render = Term.Lam(1, Term.App(Term.Global("componentScope"), List(
      str("shared"),
      Term.Lam(0, Term.Let(
        List(Term.App(Term.Global("signal"), List(str("local"), str("fresh")))),
        Term.App(Term.Global("signalText"), List(Term.Local(0))),
      )),
    )))
    val keyed = Term.App(Term.Global("forKeyedView"), List(
      Term.Local(0),
      Term.Lam(1, Term.Local(0)),
      render,
    ))
    Program(Nil, Term.Let(List(items),
      Term.App(Term.Global("emit"), List(keyed, str("out")))))

  private def nativeUiOwnerHintsProgram(): Program =
    def keyed(itemsIndex: Int, keyIndex: Int, renderIndex: Int) =
      Term.App(Term.Global("forKeyedView"), List(
      Term.Local(itemsIndex), Term.Local(keyIndex), Term.Local(renderIndex),
    ))
    val items = Term.App(Term.Global("signal"), List(str("items"), list(List(str("a")))))
    val scoped = Term.App(Term.Global("componentScope"), List(
      str("outer"),
      Term.Lam(0, Term.Let(
        List(
          Term.Lam(1, Term.Local(0)),
          Term.Lam(1, Term.App(Term.Global("textNode"), List(Term.Local(0)))),
          Term.Lam(1, Term.App(Term.Global("forKeyedView"), List(
            Term.Local(3), Term.Local(2), Term.Local(1),
          ))),
        ),
        Term.Let(
          List(
            keyed(itemsIndex = 3, keyIndex = 2, renderIndex = 0),
            keyed(itemsIndex = 4, keyIndex = 3, renderIndex = 1),
          ),
          Term.App(Term.Global("fragment"), List(list(List(Term.Local(0), Term.Local(1))))),
        ),
      )),
    ))
    Program(Nil, Term.Let(List(items),
      Term.App(Term.Global("emit"), List(scoped, str("out")))))

  private val nativeUiOwnerHintsProbe = """
public enum SessionProbe {
    public static func run() {
        do {
            let session = try SscGeneratedProgram.makeNativeUiRoot()
            guard case let .data("NativeUiAbi", abi) = session.root,
                  case let .data("NativeUiFragment", fragment) = abi[1] else {
                fatalError("bad root")
            }
            var current = fragment[0], children: [SscValue] = []
            while true {
                switch current {
                case let .data("Cons", fields):
                    children.append(fields[0]); current = fields[1]
                case .data("Nil", _): break
                default: fatalError("bad children")
                }
                if case .data("Nil", _) = current { break }
            }
            guard children.count == 2,
                  let first = session.ownerPath(for: children[0]),
                  let second = session.ownerPath(for: children[1]) else {
                fatalError("missing node-bound owner hints")
            }
            guard first.contains("/c5:outer/"), second.contains("/c5:outer/") else {
                fatalError("component scope missing")
            }
            guard first.hasSuffix(":1"), second.hasSuffix(":0"), first != second else {
                fatalError("construction/tree order swapped node owners")
            }
            guard case let .data("NativeUiForKeyed", firstFields) = children[0],
                  case let .data("NativeUiForKeyed", secondFields) = children[1],
                  case let .closure(firstRender) = firstFields[3],
                  case let .closure(secondRender) = secondFields[3],
                  firstRender === secondRender else {
                fatalError("forKeyed constructor changed shared render closure identity")
            }
            guard session.ownerHintCount() == 2,
                  case let .closure(key) = firstFields[2] else {
                fatalError("unexpected initial owner hint state")
            }
            for _ in 0..<25 {
                _ = try session.reconcileKeyed(
                    parentOwnerPath: first,
                    siteId: "outer-refresh",
                    items: [.string("a")],
                    key: key,
                    render: firstRender
                )
                guard session.ownerHintCount() == 3 else {
                    fatalError("owner hints grew across surviving refresh")
                }
            }
            do {
                _ = try session.reconcileKeyed(
                    parentOwnerPath: first,
                    siteId: "outer-refresh",
                    items: [.int(1)],
                    key: key,
                    render: firstRender
                )
                fatalError("non-string rollback unexpectedly succeeded")
            } catch {
                guard session.ownerHintCount() == 3 else {
                    fatalError("owner hint rollback changed committed state")
                }
            }
            _ = try session.reconcileKeyed(
                parentOwnerPath: first,
                siteId: "outer-refresh",
                items: [],
                key: key,
                render: firstRender
            )
            guard session.ownerHintCount() == 2 else {
                fatalError("owner hint deletion retained tombstone")
            }
            Swift.print("component|occurrence-0|occurrence-1")
        } catch { fatalError(String(describing: error)) }
    }
}
"""

  private val nativeUiSharedScopeProbe = """
public enum SessionProbe {
    private static func fields(_ value: SscValue, _ tag: String) -> [SscValue] {
        guard case let .data(actual, fields) = value, actual == tag else { fatalError("expected \(tag)") }
        return fields.asArray()
    }
    private static func closure(_ value: SscValue) -> SscClosure {
        guard case let .closure(result) = value else { fatalError("expected closure") }
        return result
    }
    private static func signal(_ entry: NativeUiKeyedEntryValue) -> SscValue {
        fields(entry.value, "NativeUiSignalText")[0]
    }
    private static func string(_ value: SscValue) -> String {
        guard case let .string(result) = value else { fatalError("expected string") }
        return result
    }
    public static func run() {
        do {
            let session = try SscGeneratedProgram.makeNativeUiRoot()
            let abi = fields(session.root, "NativeUiAbi")
            let keyed = fields(abi[1], "NativeUiForKeyed")
            let site = string(keyed[0])
            let key = closure(keyed[2])
            let render = closure(keyed[3])
            let both = try session.reconcileKeyed(
                parentOwnerPath: "root", siteId: site,
                items: [.string("a"), .string("b")], key: key, render: render)
            let shared = signal(both.entries[0])
            try session.write(shared, .string("dirty"))

            let one = try session.reconcileKeyed(
                parentOwnerPath: "root", siteId: site,
                items: [.string("b")], key: key, render: render)
            guard one.disposedSignalKeys.isEmpty,
                  string(try session.read(signal(one.entries[0]))) == "dirty" else {
                fatalError("shared scope disposed too early")
            }

            let none = try session.reconcileKeyed(
                parentOwnerPath: "root", siteId: site,
                items: [], key: key, render: render)
            guard none.disposedSignalKeys.contains("shared\u{0}local") else {
                fatalError("last shared owner did not dispose scope")
            }
            let fresh = try session.reconcileKeyed(
                parentOwnerPath: "root", siteId: site,
                items: [.string("a")], key: key, render: render)
            guard string(try session.read(signal(fresh.entries[0]))) == "fresh" else {
                fatalError("shared scope resurrected stale state")
            }
            Swift.print("shared-live|shared-disposed|fresh")
        } catch { fatalError(String(describing: error)) }
    }
}
"""

  private val nativeUiKeyedLifecycleProbe = """
public enum SessionProbe {
    private static func fields(_ value: SscValue, _ tag: String) -> [SscValue] {
        guard case let .data(actual, fields) = value, actual == tag else { fatalError("expected \(tag)") }
        return fields.asArray()
    }
    private static func closure(_ value: SscValue) -> SscClosure {
        guard case let .closure(result) = value else { fatalError("expected closure") }
        return result
    }
    private static func signal(_ entry: NativeUiKeyedEntryValue) -> SscValue {
        fields(entry.value, "NativeUiSignalText")[0]
    }
    private static func string(_ value: SscValue) -> String {
        guard case let .string(result) = value else { fatalError("expected string") }
        return result
    }
    private static func value(_ key: String, in result: NativeUiKeyedResult) -> NativeUiKeyedEntryValue {
        guard let entry = result.entries.first(where: { $0.id == key }) else { fatalError("missing \(key)") }
        return entry
    }
    public static func run() {
        do {
            let session = try SscGeneratedProgram.makeNativeUiRoot()
            let abi = fields(session.root, "NativeUiAbi")
            let keyed = fields(abi[1], "NativeUiForKeyed")
            let site = string(keyed[0])
            let key = closure(keyed[2])
            let render = closure(keyed[3])

            let first = try session.reconcileKeyed(
                parentOwnerPath: "root", siteId: site,
                items: [.string("a"), .string("b")], key: key, render: render)
            let firstA = signal(value("a", in: first))
            try session.write(firstA, .string("dirty-a"))

            let moved = try session.reconcileKeyed(
                parentOwnerPath: "root", siteId: site,
                items: [.string("b"), .string("a")], key: key, render: render)
            let movedA = signal(value("a", in: moved))
            guard string(try session.read(movedA)) == "dirty-a" else { fatalError("move lost state") }
            let movedB = signal(value("b", in: moved))
            try session.write(movedB, .string("dirty-b"))

            let deleted = try session.reconcileKeyed(
                parentOwnerPath: "root", siteId: site,
                items: [.string("b")], key: key, render: render)
            guard deleted.disposedSignalKeys.contains("a\u{0}local"),
                  deleted.disposedOwnerPaths.count == 1,
                  deleted.disposedOwnerPaths[0].hasSuffix("/k1:a") else {
                fatalError("delete retained a owner or state")
            }
            guard string(try session.read(signal(value("b", in: deleted)))) == "dirty-b" else {
                fatalError("delete damaged survivor")
            }

            do {
                _ = try session.reconcileKeyed(
                    parentOwnerPath: "root", siteId: site,
                    items: [.string("b"), .string("b")], key: key, render: render)
                fatalError("expected duplicate key")
            } catch {
                guard String(describing: error).contains("duplicate NativeUiForKeyed key 'b'") else { throw error }
            }

            let badKey = SscClosure(arity: 1) { _ in .int(7) }
            do {
                _ = try session.reconcileKeyed(
                    parentOwnerPath: "root", siteId: site,
                    items: [.string("b")], key: badKey, render: render)
                fatalError("expected non-string key")
            } catch {
                guard String(describing: error).contains("must be String") else { throw error }
            }

            let throwingRender = SscClosure(arity: 1) { args in
                if case .string("boom") = args[0] {
                    throw SscRuntimeFailure(description: "render boom")
                }
                return try session.invoke(render, args)
            }
            do {
                _ = try session.reconcileKeyed(
                    parentOwnerPath: "root", siteId: site,
                    items: [.string("b"), .string("boom")], key: key, render: throwingRender)
                fatalError("expected render rollback")
            } catch {
                guard String(describing: error).contains("render boom") else { throw error }
            }
            let afterRollback = try session.reconcileKeyed(
                parentOwnerPath: "root", siteId: site,
                items: [.string("b")], key: key, render: render)
            guard string(try session.read(signal(value("b", in: afterRollback)))) == "dirty-b" else {
                fatalError("rollback damaged committed owner")
            }

            let reinserted = try session.reconcileKeyed(
                parentOwnerPath: "root", siteId: site,
                items: [.string("a"), .string("b")], key: key, render: render)
            let freshA = string(try session.read(signal(value("a", in: reinserted))))
            Swift.print("dirty-a|dirty-b|fresh-\(freshA)|rollback-ok")
        } catch { fatalError(String(describing: error)) }
    }
}
"""

  private val nativeUiAnonymousLifecycleProbe = """
public enum SessionProbe {
    private static func fields(_ value: SscValue, _ tag: String) -> [SscValue] {
        guard case let .data(actual, fields) = value, actual == tag else {
            fatalError("expected \(tag), got \(value)")
        }
        return fields.asArray()
    }
    private static func string(_ value: SscValue) -> String {
        guard case let .string(result) = value else { fatalError("expected string, got \(value)") }
        return result
    }
    private static func signalIdentity(_ value: SscValue) -> String {
        let signal = fields(value, "NativeUiSignal")
        return string(signal[1]) + "|" + string(signal[0])
    }
    private static func entry(_ key: String, _ result: NativeUiKeyedResult) -> NativeUiKeyedEntryValue {
        guard let value = result.entries.first(where: { $0.id == key }) else {
            fatalError("missing keyed entry \(key)")
        }
        return value
    }
    private static func tuple(_ key: String, _ value: String) -> SscValue {
        .data("Tuple2", [.string(key), .string(value)])
    }
    public static func run() {
        do {
            let host = NativeUiHost()
            let program = SscProgram(
                definitions: [], entry: .literal(.unit), fieldLayouts: [:])
            let runtime = SscRuntime.session(program, nativeUiHost: host)
            _ = runtime
            var globals = try host.globals()
            let source: SscValue = .data("NativeUiSourceRef", [
                .string("anonymous-lifecycle.ssc"), .int(21), .int(9), .string("computedSignal")])

            func call(_ name: String, _ args: [SscValue]) throws -> SscValue {
                guard case let .closure(closure)? = globals[name], let native = closure.native else {
                    throw SscRuntimeFailure(description: "missing native \(name)")
                }
                return try native(args)
            }
            func computed(_ site: String, _ body: @escaping () throws -> SscValue) throws -> SscValue {
                try call("__ssc_nativeui_v1.computedSignal", [
                    .string(site), source, .closure(SscClosure(arity: 0) { _ in try body() })])
            }
            func equality(_ site: String, _ signal: SscValue, _ expected: SscValue) throws -> SscValue {
                try call("__ssc_nativeui_v1.eqSignal", [.string(site), source, signal, expected])
            }
            func named(_ id: String, _ value: SscValue) throws -> SscValue {
                try call("signal", [.string(id), value])
            }
            func read(_ signal: SscValue) throws -> SscValue {
                try host.readSignal(signal, "anonymous lifecycle probe")
            }

            let locale = try named("locale", .string("en"))
            let localeSignals = try ["title", "body", "footer"].map { prefix in
                try computed("localeText") {
                    .string(prefix + "-" + string(try read(locale)))
                }
            }
            guard localeSignals.map(signalIdentity) == [
                "root|__computed__10:localeText:0",
                "root|__computed__10:localeText:1",
                "root|__computed__10:localeText:2",
            ] else { fatalError("locale occurrence ids") }
            try host.writeSignal(locale, .string("ru"), "locale flip")
            guard try localeSignals.map({ string(try read($0)) }) == ["title-ru", "body-ru", "footer-ru"] else {
                fatalError("locale closures did not refresh")
            }

            let kindComputed0 = try computed("kind-site") { .string("c0") }
            let kindEq0 = try equality("kind-site", locale, .string("ru"))
            let kindComputed1 = try computed("kind-site") { .string("c1") }
            let kindEq1 = try equality("kind-site", locale, .string("ru"))
            guard signalIdentity(kindComputed0).hasSuffix("__computed__9:kind-site:0"),
                  signalIdentity(kindComputed1).hasSuffix("__computed__9:kind-site:1"),
                  signalIdentity(kindEq0).hasSuffix("__equality__9:kind-site:0"),
                  signalIdentity(kindEq1).hasSuffix("__equality__9:kind-site:1") else {
                fatalError("computed/equality counters collided")
            }

            let interleave0 = try computed("interleave") { .string("zero") }
            let explicit = try named("explicit", .string("strict"))
            let interleave1 = try computed("interleave") { .string("one") }
            guard signalIdentity(interleave0).hasSuffix("__computed__10:interleave:0"),
                  signalIdentity(interleave1).hasSuffix("__computed__10:interleave:1"),
                  signalIdentity(explicit) == "root|explicit" else { fatalError("named interleave shifted counters") }
            do {
                _ = try named("explicit", .string("conflict"))
                fatalError("explicit duplicate default was accepted")
            } catch let error as SscRuntimeFailure {
                guard error.description.contains("conflicting kind/default") else { throw error }
            }

            do {
                _ = try computed("failed-site") { throw SscRuntimeFailure(description: "compute failed") }
                fatalError("failed compute unexpectedly succeeded")
            } catch let error as SscRuntimeFailure {
                guard error.description == "compute failed" else { throw error }
            }
            let afterFailure = try computed("failed-site") { .string("retry") }
            guard signalIdentity(afterFailure).hasSuffix("__computed__11:failed-site:0") else {
                fatalError("failed allocation burned occurrence")
            }

            host.abort()
            globals = try host.globals()
            let baseline = host.signalCount()
            guard baseline == 1 else { fatalError("unexpected keyed baseline \(baseline)") }
            let key = SscClosure(arity: 1) { args in fields(args[0], "Tuple2")[0] }
            func renderRow(_ args: [SscValue]) throws -> SscValue {
                let item = fields(args[0], "Tuple2"), value = string(item[1])
                let first = try computed("row-site") { .string(value) }
                let equal = try equality("row-site", first, .string(value))
                let second = try computed("row-site") { .string(value + "-second") }
                return .data("Tuple3", [first, equal, second])
            }
            let render = SscClosure(arity: 1, native: renderRow)
            func rowSignals(_ value: NativeUiKeyedEntryValue) -> [SscValue] {
                fields(value.value, "Tuple3")
            }

            let first = try host.reconcileKeyed(
                parentOwnerPath: "root", siteId: "rows",
                items: [tuple("a", "v1"), tuple("b", "b1")], key: key, render: render)
            let firstA = rowSignals(entry("a", first)), firstB = rowSignals(entry("b", first))
            guard host.signalCount() == baseline + 6,
                  signalIdentity(firstA[0]) != signalIdentity(firstB[0]),
                  signalIdentity(firstA[0]).hasSuffix("__computed__8:row-site:0"),
                  signalIdentity(firstA[1]).hasSuffix("__equality__8:row-site:0"),
                  signalIdentity(firstA[2]).hasSuffix("__computed__8:row-site:1") else {
                fatalError("sibling/kind owner identities")
            }

            let refreshed = try host.reconcileKeyed(
                parentOwnerPath: "root", siteId: "rows",
                items: [tuple("a", "v2"), tuple("b", "b2")], key: key, render: render)
            let refreshedA = rowSignals(entry("a", refreshed))
            guard signalIdentity(refreshedA[0]) == signalIdentity(firstA[0]),
                  string(try read(refreshedA[0])) == "v2",
                  string(try read(refreshedA[2])) == "v2-second" else {
                fatalError("same-key derived closure/default stayed stale")
            }

            let moved = try host.reconcileKeyed(
                parentOwnerPath: "root", siteId: "rows",
                items: [tuple("b", "b3"), tuple("a", "v3")], key: key, render: render)
            guard signalIdentity(rowSignals(entry("a", moved))[0]) == signalIdentity(firstA[0]),
                  signalIdentity(rowSignals(entry("b", moved))[0]) == signalIdentity(firstB[0]),
                  host.signalCount() == baseline + 6 else { fatalError("reorder changed identity/count") }

            let throwingRender = SscClosure(arity: 1) { args in
                let rendered = try renderRow(args)
                if string(fields(args[0], "Tuple2")[0]) == "boom" {
                    throw SscRuntimeFailure(description: "render boom")
                }
                return rendered
            }
            do {
                _ = try host.reconcileKeyed(
                    parentOwnerPath: "root", siteId: "rows",
                    items: [tuple("a", "rollback"), tuple("boom", "bad")], key: key, render: throwingRender)
                fatalError("failed keyed render unexpectedly committed")
            } catch let error as SscRuntimeFailure {
                guard error.description == "render boom", host.signalCount() == baseline + 6,
                      string(try read(rowSignals(entry("a", moved))[0])) == "v3" else { throw error }
            }

            let retry = try host.reconcileKeyed(
                parentOwnerPath: "root", siteId: "rows",
                items: [tuple("a", "retry"), tuple("boom", "ok")], key: key, render: render)
            let retryA = rowSignals(entry("a", retry)), retryBoom = rowSignals(entry("boom", retry))
            guard signalIdentity(retryA[0]) == signalIdentity(firstA[0]),
                  signalIdentity(retryBoom[0]).hasSuffix("__computed__8:row-site:0"),
                  string(try read(retryA[0])) == "retry", host.signalCount() == baseline + 6 else {
                fatalError("rollback retry identity/count")
            }

            let deleted = try host.reconcileKeyed(
                parentOwnerPath: "root", siteId: "rows",
                items: [tuple("a", "last")], key: key, render: render)
            guard deleted.disposedSignalKeys.count == 3, host.signalCount() == baseline + 3 else {
                fatalError("key deletion retained derived cells")
            }
            let empty = try host.reconcileKeyed(
                parentOwnerPath: "root", siteId: "rows", items: [], key: key, render: render)
            guard empty.disposedSignalKeys.count == 3, host.signalCount() == baseline else {
                fatalError("empty keyed owner did not return to baseline")
            }

            func ownerPath(_ parent: String, _ site: String, _ key: String) -> String {
                parent + "/f" + String(site.utf8.count) + ":" + site + "/k" + String(key.utf8.count) + ":" + key
            }
            let nestedKey = SscClosure(arity: 1) { args in args[0] }
            let outerRender = SscClosure(arity: 1) { args in
                let outerKey = string(args[0])
                let outer = try computed("nested-site") { .string("outer-" + outerKey) }
                let nestedRender = SscClosure(arity: 1) { nestedArgs in
                    try computed("nested-site") { .string("inner-" + string(nestedArgs[0])) }
                }
                let nested = try host.reconcileKeyed(
                    parentOwnerPath: ownerPath("root", "outer", outerKey), siteId: "inner",
                    items: [.string("child")], key: nestedKey, render: nestedRender)
                return .data("Tuple2", [outer, entry("child", nested).value])
            }
            let nestedFirst = try host.reconcileKeyed(
                parentOwnerPath: "root", siteId: "outer",
                items: [.string("left"), .string("right")], key: nestedKey, render: outerRender)
            let left = fields(entry("left", nestedFirst).value, "Tuple2")
            let right = fields(entry("right", nestedFirst).value, "Tuple2")
            guard signalIdentity(left[0]) != signalIdentity(left[1]),
                  signalIdentity(left[0]) != signalIdentity(right[0]),
                  signalIdentity(left[1]) != signalIdentity(right[1]),
                  host.signalCount() == baseline + 4 else {
                fatalError("nested/sibling owners aliased: \(signalIdentity(left[0])) | \(signalIdentity(left[1])) | \(signalIdentity(right[0])) | \(signalIdentity(right[1])) count=\(host.signalCount())")
            }
            let nestedMoved = try host.reconcileKeyed(
                parentOwnerPath: "root", siteId: "outer",
                items: [.string("right"), .string("left")], key: nestedKey, render: outerRender)
            let movedLeft = fields(entry("left", nestedMoved).value, "Tuple2")
            guard signalIdentity(movedLeft[0]) == signalIdentity(left[0]),
                  signalIdentity(movedLeft[1]) == signalIdentity(left[1]) else {
                fatalError("nested reorder changed identity")
            }
            let throwingOuter = SscClosure(arity: 1) { args in
                let rendered = try outerRender.native!(args)
                if string(args[0]) == "boom" {
                    throw SscRuntimeFailure(description: "outer render boom")
                }
                return rendered
            }
            do {
                _ = try host.reconcileKeyed(
                    parentOwnerPath: "root", siteId: "outer",
                    items: [.string("left"), .string("boom")], key: nestedKey, render: throwingOuter)
                fatalError("outer failure after inner commit unexpectedly succeeded")
            } catch let error as SscRuntimeFailure {
                guard error.description == "outer render boom", host.signalCount() == baseline + 4 else {
                    throw error
                }
            }
            let nestedRetry = try host.reconcileKeyed(
                parentOwnerPath: "root", siteId: "outer",
                items: [.string("left"), .string("boom")], key: nestedKey, render: outerRender)
            let retryLeft = fields(entry("left", nestedRetry).value, "Tuple2")
            guard signalIdentity(retryLeft[0]) == signalIdentity(left[0]),
                  signalIdentity(retryLeft[1]) == signalIdentity(left[1]),
                  host.signalCount() == baseline + 4 else {
                fatalError("nested rollback/retry changed identity or count")
            }
            let onlyLeft = try host.reconcileKeyed(
                parentOwnerPath: "root", siteId: "outer",
                items: [.string("left")], key: nestedKey, render: outerRender)
            let retainedLeft = fields(entry("left", onlyLeft).value, "Tuple2")
            guard onlyLeft.disposedSignalKeys.count == 2,
                  host.signalCount() == baseline + 2 else { fatalError("outer sibling delete") }
            let deletedInnerHandle = retainedLeft[1]
            var innerReportedDisposals = -1
            let outerWithoutInner = SscClosure(arity: 1) { args in
                let outerKey = string(args[0])
                let outer = try computed("nested-site") { .string("outer-" + outerKey) }
                let inner = try host.reconcileKeyed(
                    parentOwnerPath: ownerPath("root", "outer", outerKey), siteId: "inner",
                    items: [], key: nestedKey,
                    render: SscClosure(arity: 1) { nestedArgs in
                        try computed("nested-site") { .string("unused-" + string(nestedArgs[0])) }
                    })
                innerReportedDisposals = inner.disposedSignalKeys.count
                return .data("Tuple2", [outer, .unit])
            }
            let innerDeleted = try host.reconcileKeyed(
                parentOwnerPath: "root", siteId: "outer",
                items: [.string("left")], key: nestedKey, render: outerWithoutInner)
            let outerAfterInnerDelete = fields(entry("left", innerDeleted).value, "Tuple2")[0]
            guard innerReportedDisposals == 0,
                  innerDeleted.disposedSignalKeys.count == 1,
                  signalIdentity(outerAfterInnerDelete) == signalIdentity(retainedLeft[0]),
                  string(try read(outerAfterInnerDelete)) == "outer-left",
                  host.signalCount() == baseline + 1 else {
                fatalError("inner delete did not defer to outer commit")
            }
            do {
                _ = try read(deletedInnerHandle)
                fatalError("deleted inner signal handle stayed live")
            } catch let error as SscRuntimeFailure {
                guard error.description.contains("is no longer live") else { throw error }
            }
            let innerReinserted = try host.reconcileKeyed(
                parentOwnerPath: "root", siteId: "outer",
                items: [.string("left")], key: nestedKey, render: outerRender)
            let reinsertedLeft = fields(entry("left", innerReinserted).value, "Tuple2")
            guard signalIdentity(reinsertedLeft[1]) == signalIdentity(deletedInnerHandle),
                  string(try read(reinsertedLeft[1])) == "inner-child",
                  host.signalCount() == baseline + 2 else {
                fatalError("inner reinsertion was not fresh/stable")
            }
            do {
                _ = try read(deletedInnerHandle)
                fatalError("old inner handle resurrected after reinsertion")
            } catch let error as SscRuntimeFailure {
                guard error.description.contains("is no longer live") else { throw error }
            }
            let nestedEmpty = try host.reconcileKeyed(
                parentOwnerPath: "root", siteId: "outer", items: [], key: nestedKey, render: outerRender)
            guard nestedEmpty.disposedSignalKeys.count == 2,
                  host.signalCount() == baseline else { fatalError("nested delete leaked signals") }

            let reset0 = try computed("reset-site") { .string("before") }
            host.abort()
            globals = try host.globals()
            let reset1 = try computed("reset-site") { .string("after") }
            guard signalIdentity(reset0) == signalIdentity(reset1), host.signalCount() == 2 else {
                fatalError("abort/new begin did not reset anonymous counters")
            }
            Swift.print("locale|kind|named|failed-allocation|refresh|reorder|rollback|retry|delete|nested|reset")
        } catch { fatalError(String(describing: error)) }
    }
}
"""

  private val nativeUiObservationProbe = """
@main
struct ObservationProbe {
    private static func fields(_ value: SscValue, _ tag: String) -> [SscValue] {
        guard case let .data(actual, fields) = value, actual == tag else {
            fatalError("expected \(tag), got \(value)")
        }
        return fields.asArray()
    }

    private static func list(_ value: SscValue) -> [SscValue] {
        var current = value
        var result: [SscValue] = []
        while true {
            switch current {
            case let .data("Cons", fields) where fields.count == 2:
                result.append(fields[0])
                current = fields[1]
            case .data("Nil", _):
                return result
            default:
                fatalError("expected list")
            }
        }
    }

    @MainActor
    static func main() {
        let store = NativeUiStore()
        let abi = fields(store.root, "NativeUiAbi")
        let fragment = fields(abi[1], "NativeUiFragment")
        let children = list(fragment[0])
        let source = fields(children[0], "NativeUiSignalText")[0]
        let first = fields(children[1], "NativeUiSignalText")[0]
        let second = fields(children[2], "NativeUiSignalText")[0]
        let cyclic = fields(children[3], "NativeUiSignalText")[0]
        let transitive = fields(children[4], "NativeUiSignalText")[0]
        let sourceCell = store.cell(for: source)
        let firstCell = store.cell(for: first)
        let secondCell = store.cell(for: second)
        guard sourceCell === store.cell(for: source) else { fatalError("unstable source cell") }

        let firstToken = store.subscribe(firstCell)
        let secondToken = store.subscribe(secondCell)
        guard firstCell.revision == 0, secondCell.revision == 0 else { fatalError("activation published") }

        sourceCell.write(.int(1))
        guard sourceCell.revision == 0, firstCell.revision == 0, secondCell.revision == 0 else {
            fatalError("semantic-equal write published")
        }

        sourceCell.write(.int(2))
        guard sourceCell.revision == 1, firstCell.revision == 1, secondCell.revision == 1 else {
            fatalError("dependent published more than once")
        }

        let retainedToken = store.subscribe(secondCell)
        store.unsubscribe(secondToken)
        sourceCell.write(.int(3))
        store.unsubscribe(secondToken)
        sourceCell.write(.int(4))
        guard secondCell.revision == 3 else { fatalError("opaque token released wrong subscription") }

        store.unsubscribe(retainedToken)
        sourceCell.write(.int(5))
        guard secondCell.revision == 3 else { fatalError("last unsubscribe retained dependencies") }
        store.unsubscribe(firstToken)
        guard store.failure == nil else { fatalError("unexpected observation failure") }
        let cyclicCell = store.cell(for: cyclic)
        let cyclicToken = store.subscribe(cyclicCell)
        guard store.failure?.contains("native UI signal dependency cycle") == true,
              cyclicCell.revision == 0 else { fatalError("cycle was not bounded") }
        store.unsubscribe(cyclicToken)
        let transitiveCell = store.cell(for: transitive)
        let transitiveToken = store.subscribe(transitiveCell)
        guard store.failure?.contains("__computed__13:observation:b:0 -> __computed__13:observation:c:0 -> __computed__13:observation:b:0") == true,
              transitiveCell.revision == 0 else { fatalError("transitive cycle was not ordered") }
        store.unsubscribe(transitiveToken)
        Swift.print("stable|\(sourceCell.revision)|\(firstCell.revision)|\(secondCell.revision)|cycle")
    }
}
"""

  private val nativeUiSessionProbe = """
public enum SessionProbe {
    private static func fields(_ value: SscValue, _ tag: String) -> [SscValue] {
        guard case let .data(actual, fields) = value, actual == tag else { fatalError("expected \(tag)") }
        return fields.asArray()
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
        return fields.asArray()
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
            Swift.print("source=\(string(source[0])):\(string(source[2])):;key=\(string(table[4]));column=\(string(column[0])):\(string(column[1])):\(string(column[2])):\(string(column[3])):edit=();delete=\(action(actions[0]));post=\(action(actions[1]))")
        } catch { fatalError(String(describing: error)) }
    }
}
"""

  private val nativeUiRequestUrlProbe = """
import Foundation

@main
struct RequestUrlProbe {
    @MainActor
    static func main() {
        let store = NativeUiStore(backendBaseURL: URL(string: "https://api.example/base/")!)
        do {
            guard try store.resolveRequestURL("https://other.example/x?q=1").absoluteString == "https://other.example/x?q=1" else { fatalError("absolute") }
            guard try store.resolveRequestURL("/root").absoluteString == "https://api.example/root" else { fatalError("root") }
            guard try store.resolveRequestURL("child").absoluteString == "https://api.example/base/child" else { fatalError("relative") }
        } catch { fatalError(String(describing: error)) }

        let withoutBase = NativeUiStore(backendBaseURL: nil)
        let rejected: [(NativeUiStore, String)] = [
            (withoutBase, "child"),
            (store, "//evil.example/x"),
            (store, "https://user@evil.example/x"),
            (store, "https://evil.example/x#fragment"),
            (store, "https:hostless"),
            (store, "ftp://evil.example/x"),
        ]
        for (candidate, text) in rejected {
            do {
                _ = try candidate.resolveRequestURL(text)
                fatalError("accepted rejected URL: \(text)")
            } catch is SscRuntimeFailure {
                continue
            } catch {
                fatalError("unexpected error for \(text): \(error)")
            }
        }
        Swift.print("absolute|root|relative|rejected")
    }
}
"""

  private val nativeUiPayloadValidationProbe = """
@main
struct PayloadValidationProbe {
    private static func list(_ values: [SscTerm]) -> SscTerm {
        values.reversed().reduce(.constructor("Nil", [])) { .constructor("Cons", [$1, $0]) }
    }
    private static func string(_ value: String) -> SscTerm { .literal(.string(value)) }
    private static func signal(_ name: String, _ value: SscTerm) -> SscTerm {
        .apply(.global("signal"), [string(name), value])
    }
    private static func withTick(_ body: SscTerm) -> SscTerm {
        .letBindings([signal("tick", .literal(.int(0)))], body)
    }
    private static func program(_ entry: SscTerm) -> SscProgram {
        SscProgram(definitions: [], entry: entry, fieldLayouts: [:])
    }
    private static func mustReject(_ entry: SscTerm, _ label: String) {
        do {
            _ = try NativeUiHost().evaluate(program(entry))
            fatalError("accepted \(label)")
        } catch {
            let message = String(describing: error)
            if message.contains("did not register a root") { fatalError("accepted \(label): \(message)") }
        }
    }
    static func main() {
        mustReject(.apply(.global("fieldPayload"), [string("")]), "empty field")
        mustReject(.apply(.global("fieldPayload"), [string("a..b")]), "malformed field")
        mustReject(.apply(.global("fieldsPayload"), [list([])]), "empty fields")
        mustReject(.apply(.global("fieldsPayload"), [list([string("id"), string("id")])]), "duplicate fields")
        mustReject(.apply(.global("fieldsPayload"), [list([string("id"), .literal(.int(1))])]), "non-string field")

        mustReject(withTick(.apply(.global("rowDeleteAction"), [string("/x"), string(""), .local(0)])), "delete field")
        mustReject(
            .letBindings([
                signal("target", string(""))
            ], .apply(.global("rowLinkAction"), [string("Pick"), .local(0), string("a..b")])),
            "link field")
        mustReject(withTick(.apply(.global("rowEditAction"), [string("PATCH"), string("/x"), string(""), .local(0)])), "edit field")

        let badPayload = SscTerm.constructor("NativeUiRowPayload", [string("bad"), list([])])
        mustReject(withTick(.apply(.global("rowPostAction"), [
            string("Save"), string("POST"), string("/x"), badPayload, .local(0)
        ])), "raw payload")
        Swift.print("constructors|actions|raw")
    }
}
"""

  private val semanticTableProbe = """
import Foundation
import SwiftUI

@main
struct SemanticTableProbe {
    static func properList(_ value: SscValue) -> [SscValue] {
        var current = value, result: [SscValue] = []
        while true {
            switch current {
            case let .data("Cons", fields) where fields.count == 2:
                result.append(fields[0]); current = fields[1]
            case .data("Nil", _): return result
            default: fatalError("expected proper list")
            }
        }
    }
    static func fields(_ value: SscValue, _ tag: String) -> [SscValue] {
        guard case let .data(actual, fields) = value, actual == tag else { fatalError("expected \(tag)") }
        return fields.asArray()
    }
    static func tableChildren(_ table: SscValue) -> [SscValue] {
        properList(fields(table, "NativeUiElement")[4])
    }
    @MainActor static func rejects(_ table: SscValue, _ store: NativeUiStore, contains needle: String) -> Bool {
        do {
            _ = try NativeUiRenderer.decodeSemanticTable(tableChildren(table), source: "probe", store: store)
            return false
        } catch let error as NativeUiRenderer.SemanticTableInvalid {
            return error.message.contains(needle)
        } catch {
            return false
        }
    }
    @MainActor static func main() async {
        let store = NativeUiStore()
        let abi = fields(store.root, "NativeUiAbi")
        let roots = properList(fields(abi[1], "NativeUiFragment")[0])
        guard roots.count == 3 else { fatalError("expected three tables, got \(roots.count)") }

        // A well-formed <table> decodes into the real header/body rows the
        // SwiftUI Grid renders (no "semantic table adapter pending" stub).
        let model: NativeUiRenderer.SemanticTableModel
        do {
            model = try NativeUiRenderer.decodeSemanticTable(
                tableChildren(roots[0]), source: "probe", store: store)
        } catch {
            fatalError("valid table did not decode into a real control: \(error)")
        }
        guard model.headerRows.count == 1, model.bodyRows.count == 2, model.columns == 2 else {
            fatalError("unexpected table shape: header=\(model.headerRows.count) body=\(model.bodyRows.count) cols=\(model.columns)")
        }
        // The real SwiftUI view constructs under strict concurrency.
        _ = NativeUiRenderer(store: store, value: roots[0]).body

        // A <table> child that is not a section/row is a strict Unsupported.
        guard rejects(roots[1], store, contains: "thead") else {
            fatalError("malformed table child was not rejected")
        }
        // A cell that is not <th>/<td> is a strict Unsupported.
        guard rejects(roots[2], store, contains: "<th> or <td>") else {
            fatalError("malformed table cell was not rejected")
        }
        Swift.print("header=\(model.headerRows.count)|body=\(model.bodyRows.count)|cols=\(model.columns)|child=rejected|cell=rejected")
    }
}
"""

  private val nativeUiReactiveAttrProbe = """
import Foundation
import SwiftUI

@main
struct ReactiveAttrProbe {
    static func properList(_ value: SscValue) -> [SscValue] {
        var current = value, result: [SscValue] = []
        while true {
            switch current {
            case let .data("Cons", fields) where fields.count == 2:
                result.append(fields[0]); current = fields[1]
            case .data("Nil", _): return result
            default: fatalError("expected proper list")
            }
        }
    }
    static func fields(_ value: SscValue, _ tag: String) -> [SscValue] {
        guard case let .data(actual, fields) = value, actual == tag else { fatalError("expected \(tag)") }
        return fields.asArray()
    }
    @MainActor static func main() async {
        let store = NativeUiStore()
        let abi = fields(store.root, "NativeUiAbi")
        let roots = properList(fields(abi[1], "NativeUiFragment")[0])
        guard roots.count == 3 else { fatalError("expected three elements, got \(roots.count)") }

        func attrs(_ element: SscValue) -> SscMap {
            guard case let .map(map) = fields(element, "NativeUiElement")[2] else {
                fatalError("element attrs must be a Map")
            }
            return map
        }
        let mappedAttrs = attrs(roots[0])
        let unmappedAttrs = attrs(roots[1])
        let malformedAttrs = attrs(roots[2])
        let mappedSite = store.string(fields(roots[0], "NativeUiElement")[0])
        let malformedSite = store.string(fields(roots[2], "NativeUiElement")[0])

        // (a) A mapped reactive attribute is accepted — NOT the strict Unsupported stub.
        guard NativeUiRenderer.reactiveAttributeDiagnostic(mappedAttrs, source: "probe") == nil else {
            fatalError("mapped reactive `disabled` was wrongly rejected")
        }
        // (c) An unmapped reactive attribute stays a strict, sourced Unsupported.
        guard NativeUiRenderer.reactiveAttributeDiagnostic(unmappedAttrs, source: "probe")
            == "reactive attribute id is not mapped at probe" else {
            fatalError("unmapped reactive `id` was not rejected")
        }

        // (b) The live modifier reflects the signal's current value — before and after a write.
        guard let flag = mappedAttrs.get(.string("disabled")) else { fatalError("missing disabled signal") }
        let before = NativeUiStyles.resolvedAttributes(mappedAttrs, store: store).get(.string("disabled")) ?? .unit
        guard case .bool(true) = before else { fatalError("initial disabled did not resolve to the live true") }
        // The resolved plain value maps to a real modifier — no Unsupported diagnostic.
        guard NativeUiStyles.diagnostic(
            attrs: NativeUiStyles.resolvedAttributes(mappedAttrs, store: store),
            store: store, siteId: mappedSite) == nil else {
            fatalError("resolved `disabled` produced an Unsupported diagnostic")
        }
        store.write(flag, .bool(false))
        let after = NativeUiStyles.resolvedAttributes(mappedAttrs, store: store).get(.string("disabled")) ?? .unit
        guard case .bool(false) = after else { fatalError("disabled did not track the signal write") }

        // The reactive SwiftUI view constructs under strict concurrency.
        _ = NativeUiRenderer(store: store, value: roots[0]).body

        // A malformed reactive value (an Int signal bound to a Bool attribute) stays Unsupported.
        let malformed = NativeUiStyles.diagnostic(
            attrs: NativeUiStyles.resolvedAttributes(malformedAttrs, store: store),
            store: store, siteId: malformedSite)
        guard let malformed, malformed.contains("invalid native disabled value") else {
            fatalError("malformed reactive `disabled` was not rejected: \(String(describing: malformed))")
        }

        Swift.print("mapped=ok|unmapped=rejected|live=true->false|malformed=rejected")
    }
}
"""

  private val nativeUiTableProbe = """
import CoreFoundation
import Foundation

final class TableURLProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var instances: [TableURLProtocol] = []
    nonisolated(unsafe) private static var stopped: Set<Int> = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); Self.instances.append(self); Self.lock.unlock()
    }
    override func stopLoading() {
        Self.lock.lock()
        if let index = Self.instances.firstIndex(where: { $0 === self }) { Self.stopped.insert(index) }
        Self.lock.unlock()
    }
    static var count: Int {
        lock.lock(); defer { lock.unlock() }; return instances.count
    }
    static func request(_ index: Int) -> URLRequest {
        lock.lock(); defer { lock.unlock() }; return instances[index].request
    }
    static func body(_ index: Int) -> String? {
        let request = request(index)
        if let data = request.httpBody { return String(data: data, encoding: .utf8) }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open(); defer { stream.close() }
        var data = Data(), buffer = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            data.append(buffer, count: count)
        }
        return String(data: data, encoding: .utf8)
    }
    static func wasStopped(_ index: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }; return stopped.contains(index)
    }
    static func respond(_ index: Int, status: Int, body: String) {
        lock.lock(); let instance = instances[index]; lock.unlock()
        let response = HTTPURLResponse(
            url: instance.request.url!, statusCode: status, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"])!
        instance.client?.urlProtocol(instance, didReceive: response, cacheStoragePolicy: .notAllowed)
        instance.client?.urlProtocol(instance, didLoad: Data(body.utf8))
        instance.client?.urlProtocolDidFinishLoading(instance)
    }
}

@main
struct NativeTableProbe {
    static func list(_ values: [SscValue]) -> SscValue {
        values.reversed().reduce(.data("Nil", [])) { .data("Cons", [$1, $0]) }
    }
    static func map(_ values: [(String, SscValue)]) -> SscMap {
        let result = SscMap()
        for (key, value) in values { result.put(.string(key), value) }
        return result
    }
    static func fields(_ value: SscValue, _ tag: String) -> [SscValue] {
        guard case let .data(actual, fields) = value, actual == tag else { fatalError("expected \(tag)") }
        return fields.asArray()
    }
    static func properList(_ value: SscValue) -> [SscValue] {
        var current = value, result: [SscValue] = []
        while true {
            switch current {
            case let .data("Cons", fields) where fields.count == 2:
                result.append(fields[0]); current = fields[1]
            case .data("Nil", _): return result
            default: fatalError("expected list")
            }
        }
    }
    @MainActor static func generatedTables(_ store: NativeUiStore) -> [SscValue] {
        let abi = fields(store.root, "NativeUiAbi")
        let fragment = fields(abi[1], "NativeUiFragment")
        return properList(fragment[0])
    }
    static func row(_ values: [(String, SscValue)]) -> SscValue { .map(map(values)) }
    static func source(_ rows: [SscValue]) -> SscValue {
        .data("NativeUiTableSource", [.string("static"), list(rows), .string("")])
    }
    static func column(
        _ kind: String, _ title: String, _ path: String, _ align: String,
        _ options: [(String, SscValue)]
    ) -> SscValue {
        .data("NativeUiColumn", [.string(kind), .string(title), .string(path), .string(align), .map(map(options))])
    }
    static func table(
        like existing: SscValue,
        rows: [SscValue],
        columns: [SscValue] = [],
        actions: [SscValue] = [],
        rowKeyPath: String = "id"
    ) -> SscValue {
        let base = fields(existing, "NativeUiDataTable")
        return .data("NativeUiDataTable", [
            base[0], source(rows), list(columns), list(actions), .string(rowKeyPath),
        ])
    }
    @MainActor static func makeStore(
        baseURL: URL? = URL(string: "https://api.example/base/")!
    ) -> NativeUiStore {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [TableURLProtocol.self]
        return NativeUiStore(
            urlSession: URLSession(configuration: configuration),
            backendBaseURL: baseURL)
    }
    static func mustReject(_ body: () throws -> Void, _ label: String) {
        do { try body(); fatalError("accepted \(label)") }
        catch is SscRuntimeFailure {}
        catch { fatalError("unexpected \(label): \(error)") }
    }
    static func waitFor(_ condition: @escaping @MainActor () -> Bool) async {
        for _ in 0..<10000 {
            if await MainActor.run(body: condition) { return }
            await Task.yield()
        }
        fatalError("timed out")
    }
    static func mark(_ value: String) {
        try? FileHandle.standardError.write(contentsOf: Data((value + "\n").utf8))
    }

    @MainActor static func abi() {
        let store = makeStore(), tables = generatedTables(store)
        let decoded = try! store.decodeNativeTable(tables[0])
        guard decoded.rowKeyPath == "id", decoded.columns.count == 1, decoded.actions.count == 4 else { fatalError("valid ABI") }
        let base = fields(tables[0], "NativeUiDataTable")
        mustReject({ _ = try store.decodeNativeTable(.data("NativeUiDataTable", SscFields(Array(base.dropLast())))) }, "four fields")
        mustReject({ _ = try store.decodeNativeTable(.data("NativeUiDataTable", SscFields(base + [.unit]))) }, "six fields")
        let badSource = SscValue.data("NativeUiTableSource", [.string("bogus"), list([]), .string("")])
        mustReject({ _ = try store.decodeNativeTable(.data("NativeUiDataTable", [base[0], badSource, base[2], base[3], base[4]])) }, "source kind")
        let staticPath = SscValue.data("NativeUiTableSource", [.string("static"), list([]), .string("a")])
        mustReject({ _ = try store.decodeNativeTable(.data("NativeUiDataTable", [base[0], staticPath, base[2], base[3], base[4]])) }, "static rowsPath")
        let fetchBase = fields(tables[2], "NativeUiDataTable")
        let fetchSourceFields = fields(fetchBase[1], "NativeUiTableSource")
        let malformedFetchPath = SscValue.data("NativeUiTableSource", [fetchSourceFields[0], fetchSourceFields[1], .string("a..b")])
        mustReject({ _ = try store.decodeNativeTable(.data("NativeUiDataTable", [fetchBase[0], malformedFetchPath, fetchBase[2], fetchBase[3], fetchBase[4]])) }, "fetch rowsPath")
        let action = fields(decoded.actions[1].request, "NativeUiFetchRequest")
        guard action.count == 4 else { fatalError("request") }
        let rawAction = properList(base[3])[1]
        let actionFields = fields(rawAction, "NativeUiRowAction")
        let badPayload = SscValue.data("NativeUiRowPayload", [.string("fields"), list([])])
        let forged = SscValue.data("NativeUiRowAction", [actionFields[0], actionFields[1], actionFields[2], badPayload, actionFields[4], actionFields[5]])
        mustReject({ _ = try store.decodeNativeTable(.data("NativeUiDataTable", [base[0], base[1], base[2], list([forged]), base[4]])) }, "payload")
        let whole = SscValue.data("NativeUiRowPayload", [.string("wholeRow"), list([])])
        let deleteFields = fields(properList(base[3])[0], "NativeUiRowAction")
        let badDelete = SscValue.data("NativeUiRowAction", [deleteFields[0], deleteFields[1], deleteFields[2], whole, deleteFields[4], deleteFields[5]])
        mustReject({ _ = try store.decodeNativeTable(.data("NativeUiDataTable", [base[0], base[1], base[2], list([badDelete]), base[4]])) }, "delete whole row")
        let linkFields = fields(properList(base[3])[2], "NativeUiRowAction")
        let badLink = SscValue.data("NativeUiRowAction", [linkFields[0], linkFields[1], linkFields[2], whole, linkFields[4], linkFields[5]])
        mustReject({ _ = try store.decodeNativeTable(.data("NativeUiDataTable", [base[0], base[1], base[2], list([badLink]), base[4]])) }, "link whole row")
        let columnFields = fields(properList(base[2])[0], "NativeUiColumn")
        guard case let .map(columnOptions) = columnFields[4], let editRaw = columnOptions.get(.string("editAction")) else { fatalError("edit option") }
        mustReject({ _ = try store.decodeNativeTable(.data("NativeUiDataTable", [base[0], base[1], base[2], list([editRaw]), base[4]])) }, "top-level edit")

        let badTargetStore = makeStore(), badTargetTables = generatedTables(badTargetStore)
        let badTargetDescriptor = try! badTargetStore.decodeNativeTable(badTargetTables[0])
        let target = badTargetDescriptor.actions[2].options.get(.string("signal"))!
        try! badTargetStore.writeUserTarget(target, .int(1))
        mustReject({ _ = try badTargetStore.decodeNativeTable(badTargetTables[0]) }, "non-string link target")

        let overflowStore = makeStore(), overflowTables = generatedTables(overflowStore)
        let overflowDescriptor = try! overflowStore.decodeNativeTable(overflowTables[0])
        try! overflowStore.writeUserTarget(overflowDescriptor.actions[0].refresh, .int(Int64.max))
        mustReject({ _ = try overflowStore.decodeNativeTable(overflowTables[0]) }, "overflow refresh")
        let malformed = SscValue.data("NativeUiDataTable", SscFields(Array(base.dropLast())))
        let malformedModel = NativeUiTableModel(store: store, value: malformed, ownerPath: "root/malformed")
        guard let diagnostic = malformedModel.snapshot.error, diagnostic.contains(" at "),
              diagnostic.unicodeScalars.count <= 1100 else { fatalError("bounded sourced init diagnostic") }
        malformedModel.mount()
        malformedModel.update(tables[0])
        guard malformedModel.descriptor != nil, malformedModel.snapshot.rows.map(\.id) == ["string:row-a"] else {
            fatalError("invalid to valid recovery")
        }
        malformedModel.update(malformed)
        guard malformedModel.descriptor != nil, malformedModel.snapshot.rows.map(\.id) == ["string:row-a"],
              malformedModel.snapshot.error != nil else { fatalError("transactional invalid replacement") }
        malformedModel.unmount()
        Swift.print("abi")
    }

    @MainActor static func sources() async {
        let store = makeStore(), tables = generatedTables(store)
        let staticDescriptor = try! store.decodeNativeTable(tables[0])
        let initial = store.nativeTableSnapshot(staticDescriptor)
        guard initial.rows.map(\.id) == ["string:row-a"], initial.status == nil else { fatalError("static") }
        let empty = table(like: tables[0], rows: [], columns: properList(fields(tables[0], "NativeUiDataTable")[2]))
        guard store.nativeTableSnapshot(try! store.decodeNativeTable(empty)).status == "No rows" else { fatalError("empty") }

        let signalDescriptor = try! store.decodeNativeTable(tables[1])
        let signalRows = signalDescriptor.sourceValue
        let replacement = row([("id", .string("row-b")), ("nested", .map(map([("name", .string("Beta"))])))])
        try! store.writeUserTarget(signalRows, list([replacement]))
        let signalSnapshot = store.nativeTableSnapshot(signalDescriptor)
        guard signalSnapshot.rows.map(\.id) == ["string:row-b"] else { fatalError("signal") }
        try! store.writeUserTarget(signalRows, list([.string("bad")]))
        let retained = store.nativeTableSnapshot(signalDescriptor, retaining: signalSnapshot.rows)
        guard retained.error != nil, retained.rows.map(\.id) == ["string:row-b"] else { fatalError("transactional") }

        let fetchModel = NativeUiTableModel(store: store, value: tables[2], ownerPath: "root/table")
        fetchModel.mount()
        await waitFor { TableURLProtocol.count == 1 }
        guard fetchModel.snapshot.status == "Loading…", fetchModel.snapshot.rows.isEmpty else { fatalError("loading") }
        TableURLProtocol.respond(0, status: 200, body: "{\"payload\":{\"items\":[{\"id\":\"remote-a\",\"nested\":{\"name\":\"Remote\"}}]}}")
        await waitFor { fetchModel.snapshot.rows.first?.id == "string:remote-a" }
        let fetchDescriptor = try! store.decodeNativeTable(tables[2])
        let refresh = try! store.decodeNativeTable(tables[0]).actions[0].refresh
        try! store.writeUserTarget(refresh, .int(1))
        await waitFor { TableURLProtocol.count == 2 }
        guard fetchModel.snapshot.status == "Loading…", fetchModel.snapshot.rows.first?.id == "string:remote-a" else { fatalError("last-good loading") }
        TableURLProtocol.respond(1, status: 500, body: "nope")
        await waitFor { fetchModel.snapshot.error != nil }
        guard fetchModel.snapshot.rows.first?.id == "string:remote-a" else { fatalError("last-good error") }
        try! store.writeUserTarget(refresh, .int(2))
        await waitFor { TableURLProtocol.count == 3 }
        TableURLProtocol.respond(2, status: 200, body: "{\"data\":[{\"id\":\"fallback\",\"nested\":{\"name\":\"Fallback\"}}]}")
        await waitFor { fetchModel.snapshot.rows.first?.id == "string:fallback" }
        let fallbackBodies = [
            "{\"data\":{},\"rows\":[{\"id\":\"rows\",\"nested\":{\"name\":\"Rows\"}}]}",
            "{\"data\":{},\"rows\":{},\"items\":[{\"id\":\"items\",\"nested\":{\"name\":\"Items\"}}]}",
            "{\"data\":{},\"rows\":{},\"items\":{},\"results\":[{\"id\":\"results\",\"nested\":{\"name\":\"Results\"}}]}",
        ]
        for (offset, body) in fallbackBodies.enumerated() {
            try! store.writeUserTarget(refresh, .int(Int64(offset + 3)))
            await waitFor { TableURLProtocol.count == offset + 4 }
            TableURLProtocol.respond(offset + 3, status: 200, body: body)
            let expected = ["rows", "items", "results"][offset]
            await waitFor { fetchModel.snapshot.rows.first?.id == "string:" + expected }
        }
        _ = fetchDescriptor
        fetchModel.unmount()

        let errorStore = makeStore(), errorTables = generatedTables(errorStore)
        let errorModel = NativeUiTableModel(store: errorStore, value: errorTables[2], ownerPath: "root/error")
        errorModel.mount()
        await waitFor { TableURLProtocol.count == 7 }
        TableURLProtocol.respond(6, status: 503, body: "initial")
        await waitFor { errorModel.snapshot.error != nil }
        guard errorModel.snapshot.rows.isEmpty,
              errorModel.snapshot.status?.hasPrefix("Error: HTTP 503") == true else { fatalError("initial error visibility") }
        errorModel.unmount()
        Swift.print("sources")
    }

    @MainActor static func columns() {
        let store = makeStore(), existing = generatedTables(store)[0]
        let colors = map([("ok", .string("rgba(1,2,3,0.5)"))])
        let columns: [SscValue] = [
            column("text", "Name", "nested.name", "left", [("editAction", .unit)]),
            column("date", "Date", "created", "center", [("format", .string("yyyy-MM-dd"))]),
            column("money", "Amount", "amount", "right", [("currency", .string("USD")), ("locale", .string("en_US"))]),
            column("status", "Status", "status", "", [("colorMap", .map(colors))]),
            column("link", "Link", "slug", "", [("urlTemplate", .string("https://example.test/u/:value"))]),
            column("stacked", "Stacked", "nested.name", "", [("subFieldPath", .string("nested.sub"))]),
        ]
        let value = row([
            ("id", .string("r")),
            ("nested", .map(map([("name", .string("Alpha")), ("sub", .string("Detail"))]))),
            ("created", .string("2024-01-02")), ("amount", .float(12.5)),
            ("status", .string("ok")), ("slug", .string("a/b")),
        ])
        let descriptor = try! store.decodeNativeTable(table(like: existing, rows: [value], columns: columns))
        let snapshot = store.nativeTableSnapshot(
            descriptor, locale: Locale(identifier: "en_US"), timeZone: TimeZone(secondsFromGMT: 0)!)
        guard snapshot.error == nil, snapshot.rows.count == 1 else { fatalError(snapshot.error ?? "columns") }
        let cells = snapshot.rows[0].cells
        guard cells[0].primary == "Alpha", cells[0].alignment == "leading",
              cells[1].primary == "2024-01-02", cells[2].primary.contains("12.50"),
              cells[3].statusColor == "rgba(1,2,3,0.5)",
              cells[4].link?.absoluteString == "https://example.test/u/a%2Fb",
              cells[5].secondary == "Detail" else { fatalError("formatted columns") }
        let floatRow = row([("id", .string("float")), ("nested", .map(map([("name", .float(1.5))])))])
        let rejected = store.nativeTableSnapshot(
            try! store.decodeNativeTable(table(like: existing, rows: [floatRow], columns: [columns[0]])),
            retaining: snapshot.rows)
        guard rejected.error != nil, rejected.rows.count == 1 else { fatalError("float display") }
        let badDate = row([("id", .string("date")), ("created", .string("2024-01-02tail"))])
        let dateSnapshot = store.nativeTableSnapshot(
            try! store.decodeNativeTable(table(like: existing, rows: [badDate], columns: [columns[1]])),
            locale: Locale(identifier: "en_US"), timeZone: TimeZone(secondsFromGMT: 0)!)
        guard dateSnapshot.rows[0].cells[0].primary == "2024-01-02tail" else { fatalError("exact date") }
        let badAlignment = column("text", "Bad", "nested.name", "justify", [("editAction", .unit)])
        mustReject({ _ = try store.decodeNativeTable(table(like: existing, rows: [value], columns: [badAlignment])) }, "alignment")
        let badColors = map([("ok", .string("rgba(256,2,3,0.5)"))])
        let badStatus = column("status", "Bad", "status", "", [("colorMap", .map(badColors))])
        mustReject({ _ = try store.decodeNativeTable(table(like: existing, rows: [value], columns: [badStatus])) }, "color")
        let unsafeLink = column("link", "Bad", "slug", "", [("urlTemplate", .string("javascript::value"))])
        let unsafeSnapshot = store.nativeTableSnapshot(
            try! store.decodeNativeTable(table(like: existing, rows: [value], columns: [unsafeLink])))
        guard unsafeSnapshot.error != nil else { fatalError("unsafe link") }
        Swift.print("columns")
    }

    @MainActor static func identity() {
        let store = makeStore(), existing = generatedTables(store)[0]
        let goodRows = [
            row([("id", .string("1"))]), row([("id", .int(1))]),
            row([("id", .big(SscBigInt("1")))]),
        ]
        let descriptor = try! store.decodeNativeTable(table(like: existing, rows: goodRows))
        let good = store.nativeTableSnapshot(descriptor)
        guard Set(good.rows.map(\.id)).count == 3 else { fatalError("typed identity") }
        let invalid: [[SscValue]] = [
            [row([])], [row([("id", .unit)])], [row([("id", .string(""))])],
            [row([("id", .map(map([])))])], [row([("id", .float(1.0))])],
            [row([("id", .string("x"))]), row([("id", .string("x"))])],
        ]
        let nonStringKeys = map([("id", .string("key"))])
        nonStringKeys.put(.int(1), .string("bad"))
        for rows in invalid + [[.map(nonStringKeys)]] {
            let candidate = store.nativeTableSnapshot(
                try! store.decodeNativeTable(table(like: existing, rows: rows)), retaining: good.rows)
            guard candidate.error != nil, candidate.rows.count == 3 else { fatalError("invalid identity") }
        }
        Swift.print("identity")
    }

    @MainActor static func actions() async {
        mark("actions:start")
        let store = makeStore(), tables = generatedTables(store)
        let descriptor = try! store.decodeNativeTable(tables[0])
        let signature = store.nativeTableDescriptorSignature(tables[0])
        let row = store.nativeTableSnapshot(descriptor).rows[0]
        store.installNativeTableCapability(
            ownerPath: "root/table", descriptor: descriptor, signature: signature, rows: [row])
        var phase = "idle", error: String?
        store.runNativeTableAction(
            descriptor.actions[0], row: row, actionIndex: 0, ownerPath: "root/table",
            siteId: descriptor.siteId, descriptorSignature: signature) { phase = $0; error = $1 }
        await waitFor { TableURLProtocol.count == 1 }
        guard TableURLProtocol.request(0).url?.absoluteString == "https://api.example/delete/row-a",
              TableURLProtocol.body(0) == "row-a",
              phase == "loading", error == nil else {
            fatalError("delete request url=\(TableURLProtocol.request(0).url?.absoluteString ?? "nil") body=\(TableURLProtocol.body(0) ?? "nil") phase=\(phase) error=\(error ?? "nil")")
        }
        TableURLProtocol.respond(0, status: 204, body: "")
        await waitFor { phase == "done" }
        mark("actions:basic")
        guard case .int(1) = store.read(descriptor.actions[0].refresh) else { fatalError("refresh") }

        store.runNativeTableAction(
            descriptor.actions[1], row: row, actionIndex: 1, ownerPath: "root/table",
            siteId: descriptor.siteId, descriptorSignature: signature) { phase = $0; error = $1 }
        await waitFor { TableURLProtocol.count == 2 }
        let post = TableURLProtocol.request(1)
        guard post.url?.absoluteString == "https://api.example/post/row-a",
              post.value(forHTTPHeaderField: "Content-Type") == "application/json",
              TableURLProtocol.body(1)?.contains("\"id\":\"row-a\"") == true else { fatalError("post request") }
        TableURLProtocol.respond(1, status: 500, body: "no")
        await waitFor { phase == "error" }
        guard case .int(1) = store.read(descriptor.actions[1].refresh) else { fatalError("failure refresh") }

        store.runNativeTableAction(
            descriptor.actions[2], row: row, actionIndex: 2, ownerPath: "root/table",
            siteId: descriptor.siteId, descriptorSignature: signature) { phase = $0; error = $1 }
        guard TableURLProtocol.count == 2, phase == "done",
              case let .string(selected) = store.read(descriptor.actions[2].options.get(.string("signal"))!),
              selected == "Alpha" else { fatalError("local link") }

        let editAction = try! store.decodeNativeTableActionForEdit(
            descriptor.columns[0].editAction!, columnIndex: 0)
        store.runNativeTableAction(
            editAction, row: row, actionIndex: 4, ownerPath: "root/table",
            siteId: descriptor.siteId, descriptorSignature: signature,
            editField: "nested.name", editValue: "Beta"
        ) { phase = $0; error = $1 }
        await waitFor { TableURLProtocol.count == 3 }
        let edit = TableURLProtocol.request(2)
        guard edit.url?.absoluteString == "https://api.example/edit/row-a",
              edit.value(forHTTPHeaderField: "Content-Type") == "application/json",
              TableURLProtocol.body(2) == "{\"id\":\"row-a\",\"nested.name\":\"Beta\"}" else { fatalError("edit request") }
        TableURLProtocol.respond(2, status: 200, body: "ok")
        await waitFor { phase == "done" }
        guard case .int(2) = store.read(editAction.refresh) else { fatalError("edit refresh") }

        guard case let .data("NativeUiFetchRequest", requestFields) = descriptor.actions[1].request,
              requestFields.count == 4 else { fatalError("post request descriptor") }
        try! store.writeUserTarget(requestFields[3], .string("{\"content-type\":\"application/custom\",\"X-Test\":\"yes\"}"))
        store.runNativeTableAction(
            descriptor.actions[3], row: row, actionIndex: 3, ownerPath: "root/table",
            siteId: descriptor.siteId, descriptorSignature: signature
        ) { phase = $0; error = $1 }
        await waitFor { TableURLProtocol.count == 4 }
        guard TableURLProtocol.body(3) == "{\"id\":\"row-a\",\"nested.name\":\"Alpha\"}",
              TableURLProtocol.request(3).value(forHTTPHeaderField: "Content-Type") == "application/custom",
              TableURLProtocol.request(3).value(forHTTPHeaderField: "X-Test") == "yes" else { fatalError("Fields/header override") }
        TableURLProtocol.respond(3, status: 200, body: "ok")
        await waitFor { phase == "done" }

        let requestCount = TableURLProtocol.count
        let malformedRequest = SscValue.data("NativeUiFetchRequest", [
            .string("POST"), .string("/bad/:bad-name"), .unit, requestFields[3],
        ])
        let base = fields(tables[0], "NativeUiDataTable")
        let postRawFields = fields(properList(base[3])[1], "NativeUiRowAction")
        let malformedRaw = SscValue.data("NativeUiRowAction", [
            postRawFields[0], postRawFields[1], malformedRequest,
            postRawFields[3], postRawFields[4], postRawFields[5],
        ])
        let malformedTable = SscValue.data("NativeUiDataTable", [
            base[0], base[1], base[2], list([malformedRaw]), base[4],
        ])
        let malformedDescriptor = try! store.decodeNativeTable(malformedTable)
        let malformedSignature = store.nativeTableDescriptorSignature(malformedTable)
        store.installNativeTableCapability(
            ownerPath: "root/malformed", descriptor: malformedDescriptor,
            signature: malformedSignature, rows: [row])
        store.runNativeTableAction(
            malformedDescriptor.actions[0], row: row, actionIndex: 0, ownerPath: "root/malformed",
            siteId: malformedDescriptor.siteId, descriptorSignature: malformedSignature
        ) { phase = $0; error = $1 }
        guard TableURLProtocol.count == requestCount, phase == "error", error?.contains("malformed /: token") == true else { fatalError("token preflight") }

        try! store.writeUserTarget(requestFields[3], .string("{"))
        store.runNativeTableAction(
            descriptor.actions[3], row: row, actionIndex: 3, ownerPath: "root/table",
            siteId: descriptor.siteId, descriptorSignature: signature
        ) { phase = $0; error = $1 }
        guard TableURLProtocol.count == requestCount, phase == "error" else { fatalError("header preflight") }

        let decimalRowMap = map([("id", .decimal(SscDecimal("1.0")))])
        let decimalRow = NativeUiTableRow(identity: row.identity, value: decimalRowMap, cells: [])
        store.runNativeTableAction(
            descriptor.actions[0], row: decimalRow, actionIndex: 0, ownerPath: "root/table",
            siteId: descriptor.siteId, descriptorSignature: signature
        ) { phase = $0; error = $1 }
        guard TableURLProtocol.count == requestCount, phase == "error" else { fatalError("payload scalar preflight") }
        mark("actions:negatives")

        try! store.writeUserTarget(requestFields[3], .string(""))
        let editModel = NativeUiTableModel(store: store, value: tables[0], ownerPath: "root/edit-model")
        editModel.mount()
        let editRow = editModel.snapshot.rows[0]
        let revision = editModel.beginEdit(row: editRow, columnIndex: 0)
        editModel.commitEdit(
            row: editRow, column: editModel.descriptor!.columns[0], columnIndex: 0,
            revision: revision, value: "Once")
        editModel.commitEdit(
            row: editRow, column: editModel.descriptor!.columns[0], columnIndex: 0,
            revision: revision, value: "Twice")
        await waitFor { TableURLProtocol.count == 5 }
        guard TableURLProtocol.body(4) == "{\"id\":\"row-a\",\"nested.name\":\"Once\"}" else { fatalError("edit dedupe") }
        TableURLProtocol.respond(4, status: 200, body: "ok")
        editModel.unmount()
        mark("actions:edit")

        let replacementModel = NativeUiTableModel(store: store, value: tables[0], ownerPath: "root/replacement")
        replacementModel.mount()
        let oldRow = replacementModel.snapshot.rows[0]
        replacementModel.run(replacementModel.descriptor!.actions[1], row: oldRow, index: 1)
        await waitFor { TableURLProtocol.count == 6 }
        let newRow = Self.row([("id", .string("row-new")), ("nested", .map(map([("name", .string("New"))])))])
        let replacement = table(
            like: tables[0], rows: [newRow], columns: properList(base[2]), actions: properList(base[3]))
        replacementModel.update(replacement)
        await waitFor { TableURLProtocol.wasStopped(5) }
        guard case let .int(beforeLate) = store.read(descriptor.actions[1].refresh) else { fatalError("refresh before late") }
        TableURLProtocol.respond(5, status: 200, body: "late")
        await Task.yield()
        guard case let .int(afterLate) = store.read(descriptor.actions[1].refresh), afterLate == beforeLate,
              replacementModel.snapshot.rows.first?.id == "string:row-new" else { fatalError("replacement stale completion") }
        replacementModel.run(descriptor.actions[1], row: oldRow, index: 1)
        guard TableURLProtocol.count == 6,
              replacementModel.actionState(row: oldRow, index: 1).error?.contains("stale") == true else {
            fatalError("model stale row/action capability")
        }
        phase = "idle"; error = nil
        store.runNativeTableAction(
            descriptor.actions[1], row: oldRow, actionIndex: 1, ownerPath: "root/replacement",
            siteId: descriptor.siteId, descriptorSignature: signature
        ) { phase = $0; error = $1 }
        guard TableURLProtocol.count == 6, phase == "error", error?.contains("stale") == true else { fatalError("stale descriptor capability") }
        let newDescriptor = replacementModel.descriptor!
        replacementModel.run(newDescriptor.actions[1], row: replacementModel.snapshot.rows[0], index: 1)
        await waitFor { TableURLProtocol.count == 7 }
        replacementModel.unmount()
        await waitFor { TableURLProtocol.wasStopped(6) }
        mark("actions:replacement")

        let signalModel = NativeUiTableModel(store: store, value: tables[1], ownerPath: "root/row-removal")
        signalModel.mount()
        let signalRow = signalModel.snapshot.rows[0]
        signalModel.run(signalModel.descriptor!.actions[1], row: signalRow, index: 1)
        await waitFor { TableURLProtocol.count == 8 }
        try! store.writeUserTarget(signalModel.descriptor!.sourceValue, list([]))
        await waitFor { TableURLProtocol.wasStopped(7) && signalModel.snapshot.rows.isEmpty }
        guard signalModel.actionStates.isEmpty else { fatalError("removed-row action state") }
        TableURLProtocol.respond(7, status: 200, body: "late-row")
        await Task.yield()
        signalModel.unmount()
        mark("actions:row-removal")

        var doomedStore: NativeUiStore? = makeStore()
        var doomedModel: NativeUiTableModel? = NativeUiTableModel(
            store: doomedStore!, value: generatedTables(doomedStore!)[0], ownerPath: "root/deinit")
        doomedModel!.mount()
        doomedModel!.run(doomedModel!.descriptor!.actions[1], row: doomedModel!.snapshot.rows[0], index: 1)
        await waitFor { TableURLProtocol.count == 9 }
        weak let weakStore = doomedStore
        doomedModel = nil
        doomedStore = nil
        await waitFor { weakStore == nil && TableURLProtocol.wasStopped(8) }
        mark("actions:deinit")

        let noBaseStore = makeStore(baseURL: nil), noBaseTables = generatedTables(noBaseStore)
        let noBaseDescriptor = try! noBaseStore.decodeNativeTable(noBaseTables[0])
        let noBaseSignature = noBaseStore.nativeTableDescriptorSignature(noBaseTables[0])
        let noBaseRows = noBaseStore.nativeTableSnapshot(noBaseDescriptor).rows
        noBaseStore.installNativeTableCapability(
            ownerPath: "root/no-base", descriptor: noBaseDescriptor,
            signature: noBaseSignature, rows: noBaseRows)
        phase = "idle"; error = nil
        noBaseStore.runNativeTableAction(
            noBaseDescriptor.actions[0], row: noBaseRows[0],
            actionIndex: 0, ownerPath: "root/no-base", siteId: noBaseDescriptor.siteId,
            descriptorSignature: noBaseSignature
        ) { phase = $0; error = $1 }
        guard TableURLProtocol.count == 9, phase == "error",
              error?.contains("requires --server-url") == true else { fatalError("base preflight") }
        mark("actions:done")
        Swift.print("actions")
    }

    @MainActor static func main() async {
        let mode = CommandLine.arguments.dropFirst().first ?? ""
        switch mode {
        case "abi": abi()
        case "sources": await sources()
        case "columns": columns()
        case "identity": identity()
        case "actions": await actions()
        case "apple":
            let store = makeStore()
            let table = generatedTables(store)[0]
            guard (try? store.decodeNativeTable(table)) != nil else { fatalError("apple") }
            #if SSC_TABLE_APPLE
            _ = NativeUiRenderer(store: store, value: table).body
            #endif
            Swift.print("apple")
        default: fatalError("unknown mode")
        }
    }
}
"""

  private val nativeUiHtmlProbe = """
import AppKit
import Foundation
import Network
import WebKit

enum HtmlProbeError: Error { case expected }

final class LoopbackServer: @unchecked Sendable {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "ssc.nativeui.html.loopback")
    private let lock = NSLock()
    private var hitCount = 0
    private var readyPort: UInt16?

    init() throws {
        listener = try NWListener(using: .tcp, on: .any)
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            self.lock.lock(); self.hitCount += 1; self.lock.unlock()
            connection.cancel()
        }
    }

    func start() -> UInt16 {
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.lock.lock(); self.readyPort = self.listener.port?.rawValue; self.lock.unlock()
                ready.signal()
            case .failed: ready.signal()
            default: break
            }
        }
        listener.start(queue: queue)
        guard ready.wait(timeout: .now() + 5) == .success else { fatalError("loopback listener timeout") }
        lock.lock(); defer { lock.unlock() }
        guard let readyPort else { fatalError("loopback listener failed") }
        return readyPort
    }

    func hits() -> Int { lock.lock(); defer { lock.unlock() }; return hitCount }
    deinit { listener.cancel() }
}

final class WeakCoordinatorBox {
    weak var value: NativeUiHtmlCoordinator?
    init(_ value: NativeUiHtmlCoordinator?) { self.value = value }
}

@main
struct NativeHtmlProbe {
    @MainActor static func waitFor(_ predicate: () -> Bool) async -> Bool {
        for _ in 0..<240 {
            if predicate() { return true }
            try? await Task.sleep(nanoseconds: 25_000_000)
        }
        return false
    }

    @MainActor static func main() async {
        _ = NSApplication.shared
        let store = NativeUiStore()
        var heights: [CGFloat] = []
        var errors: [String?] = []
        var opened: [URL] = []
        let loopback = try! LoopbackServer()
        let loopbackPort = loopback.start()
        var compiledRule: WKContentRuleList?
        var compileCount = 0
        var delayedCompiles: [((WKContentRuleList?, Error?) -> Void)] = []
        var policyGate = NativeUiHtmlDocumentPolicyGate()
        guard policyGate.begin(generation: 41),
              !policyGate.begin(generation: 42),
              policyGate.consume(currentGeneration: 42) == .cancel,
              policyGate.begin(generation: 42),
              policyGate.consume(currentGeneration: 42) == .allow,
              policyGate.awaitingGeneration == nil else {
            fatalError("trusted HTML serialized policy ordering")
        }
        var coordinator: NativeUiHtmlCoordinator? = NativeUiHtmlCoordinator(
            safeExternalURL: { store.safeExternalURL($0) },
            openURL: { opened.append($0) },
            publishHeight: { heights.append($0) },
            publishError: { errors.append($0) },
            _probeRuleCompiler: { completion in
                compileCount += 1
                if compileCount == 1 {
                    WKContentRuleListStore.default().compileContentRuleList(
                        forIdentifier: NativeUiHtmlAdapter.contentRuleIdentifier,
                        encodedContentRuleList: NativeUiHtmlAdapter.contentRuleJSON
                    ) { rule, error in
                        compiledRule = rule
                        completion(rule, error)
                    }
                } else {
                    delayedCompiles.append(completion)
                }
            })
        let initial = [
            "<strong id='strong' data-x='ok' style='color:rgb(255,0,0)'>raw</strong>",
            "<img id='data' src='data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==' />",
            "<img id='network' src='http://127.0.0.1:\(loopbackPort)/blocked.png' />",
            "<script>window.__sscPageScriptRan = true</script>",
            "<div style='height:160px'></div>",
        ].joined()
        let webView = coordinator!.makeWebView(html: initial, source: "raw.ssc:4:2 [rawHtml]")
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 320, height: 200),
            styleMask: [.borderless], backing: .buffered, defer: false)
        window.contentView = webView
        window.orderFront(nil)
        guard !webView.configuration.websiteDataStore.isPersistent,
              webView.configuration.defaultWebpagePreferences.allowsContentJavaScript == false,
              webView.url == nil else { fatalError("trusted HTML configuration/load ordering") }
        guard await waitFor({ heights.last.map { $0 > 100 } == true }) else {
            fatalError("timed out waiting for initial isolated height: \(heights)")
        }
        guard let forcedStaleNavigation = coordinator!.probeNavigation(at: 0) else {
            fatalError("trusted HTML missing issued navigation probe")
        }
        guard errors.compactMap({ $0 }).isEmpty else { fatalError("trusted HTML initial error") }
        try? await Task.sleep(nanoseconds: 150_000_000)
        guard loopback.hits() == 0 else { fatalError("trusted HTML network subresource escaped blocker") }
        do {
            let facts = try await webView.evaluateJavaScript(
                "[document.querySelector('#strong')?.dataset.x || '', getComputedStyle(document.querySelector('#strong')).color, (document.querySelector('#data')?.naturalWidth || 0) > 0, window.__sscPageScriptRan === true]")
            guard let values = facts as? [Any], values.count == 4,
                  values[0] as? String == "ok",
                  (values[1] as? String)?.contains("255") == true,
                  values[2] as? Bool == true,
                  values[3] as? Bool == false else { fatalError("trusted HTML inline/data/page-script isolation") }
        } catch { fatalError("trusted HTML DOM probe: \(error)") }
        let safe: (String) -> URL? = { store.safeExternalURL($0) }
        guard NativeUiHtmlAdapter.externalURL(
                "https://example.com/path", navigationType: .linkActivated,
                safeExternalURL: safe)?.absoluteString == "https://example.com/path",
              NativeUiHtmlAdapter.externalURL(
                "mailto:a@example.com", navigationType: .linkActivated,
                safeExternalURL: safe) != nil,
              NativeUiHtmlAdapter.externalURL(
                "https://example.com/path", navigationType: .other,
                safeExternalURL: safe) == nil,
              NativeUiHtmlAdapter.externalURL(
                "javascript:alert(1)", navigationType: .linkActivated,
                safeExternalURL: safe) == nil else { fatalError("trusted HTML external URL policy") }
        guard NativeUiHtmlAdapter.revisionKey(html: "same", source: "a.ssc:1:1") !=
                NativeUiHtmlAdapter.revisionKey(html: "same", source: "b.ssc:1:1") else {
            fatalError("trusted HTML source-only revision key")
        }
        guard coordinator!.handoffMainFrame(
                "https://example.com/main", navigationType: .linkActivated, isMainFrame: true),
              coordinator!.handoffNewWindow(
                "mailto:a@example.com", navigationType: .linkActivated, targetFrameIsNil: true),
              opened.map(\.absoluteString) == ["https://example.com/main", "mailto:a@example.com"],
              !coordinator!.handoffMainFrame(
                "https://example.com/subframe", navigationType: .linkActivated, isMainFrame: false),
              !coordinator!.handoffNewWindow(
                "javascript:alert(1)", navigationType: .linkActivated, targetFrameIsNil: true),
              !coordinator!.handoffNewWindow(
                "https://example.com/redirect", navigationType: .other, targetFrameIsNil: true),
              opened.count == 2 else { fatalError("trusted HTML external URL handoff count") }

        guard case let .data("NativeUiAbi", abiFields) = store.root,
              case let .data("NativeUiTrustedHtml", htmlFields) = abiFields[1] else {
            fatalError("trusted HTML generated descriptor")
        }
        func decodeRejects(_ value: SscValue) -> Bool {
            guard case let .data(_, candidateFields) = value else { return false }
            do { _ = try NativeUiHtmlAdapter.decode(candidateFields, store: store); return false }
            catch { return String(describing: error).contains("malformed NativeUiTrustedHtml at") }
        }
        do {
            let decoded = try NativeUiHtmlAdapter.decode(htmlFields, store: store)
            guard decoded.html.contains("data-x=\"ok\"") else { fatalError("trusted HTML exact descriptor") }
            guard decodeRejects(.data("NativeUiTrustedHtml", [htmlFields[0]])),
                  decodeRejects(.data("NativeUiTrustedHtml", [.int(1), .string("html")])),
                  decodeRejects(.data("NativeUiTrustedHtml", [.string("forged-site"), .string("html")])),
                  decodeRejects(.data("NativeUiTrustedHtml", [htmlFields[0], .int(1)])) else {
                fatalError("trusted HTML forged arity/site/source was accepted")
            }
        } catch { fatalError("trusted HTML descriptor probe: \(error)") }

        let openedBeforeProgrammatic = opened.count
        _ = try? await webView.evaluateJavaScript("location.href='https://example.com/programmatic'")
        try? await Task.sleep(nanoseconds: 100_000_000)
        guard opened.count == openedBeforeProgrammatic,
              webView.url?.absoluteString == "about:blank" else {
            fatalError("trusted HTML programmatic navigation escaped")
        }

        coordinator!.update(html: "<div style='height:420px'>grow</div>", source: "raw.ssc:4:2 [rawHtml]", in: webView)
        guard await waitFor({ delayedCompiles.count == 1 }), let compiledRule else {
            fatalError("trusted HTML pending replacement compile")
        }
        _ = try? await webView.evaluateJavaScript(
            "document.body.insertAdjacentHTML('beforeend', \"<img src='http://127.0.0.1:\(loopbackPort)/late.png'>\")")
        try? await Task.sleep(nanoseconds: 150_000_000)
        guard loopback.hits() == 0 else { fatalError("trusted HTML removed prior blocker during compile") }
        delayedCompiles.removeFirst()(compiledRule, nil)
        guard await waitFor({ heights.last.map { $0 > 350 } == true }) else {
            fatalError("timed out waiting for grown height: \(heights)")
        }
        let staleStart = heights.count
        coordinator!.update(html: "<div style='height:900px'>stale</div>", source: "raw.ssc:4:2 [rawHtml]", in: webView)
        coordinator!.update(html: "<div style='height:24px'>shrink</div>", source: "raw.ssc:4:2 [rawHtml]", in: webView)
        guard await waitFor({ delayedCompiles.count == 2 }) else {
            fatalError("trusted HTML stale/current compile queue")
        }
        delayedCompiles.removeFirst()(compiledRule, nil)
        delayedCompiles.removeFirst()(compiledRule, nil)
        guard await waitFor({ heights.last.map { $0 < 100 } == true }) else {
            fatalError("timed out waiting for shrunk height: \(heights)")
        }
        try? await Task.sleep(nanoseconds: 150_000_000)
        guard !heights.dropFirst(staleStart).contains(where: { $0 > 800 }) else {
            fatalError("trusted HTML stale generation published height")
        }

        let forcedTerminalErrorCount = errors.count
        let forcedTerminalHeightCount = heights.count
        coordinator!.webView(
            webView,
            didFail: forcedStaleNavigation,
            withError: NSError(domain: "NativeHtmlProbe", code: 41))
        coordinator!.webView(webView, didFinish: forcedStaleNavigation)
        guard errors.count == forcedTerminalErrorCount,
              heights.count == forcedTerminalHeightCount else {
            fatalError("trusted HTML stale terminal callback published")
        }

        var failureErrors: [String?] = []
        var failureCompileCount = 0
        let failingCoordinator = NativeUiHtmlCoordinator(
            safeExternalURL: safe, openURL: { _ in }, publishHeight: { _ in },
            publishError: { failureErrors.append($0) },
            _probeRuleCompiler: { completion in
                failureCompileCount += 1
                if failureCompileCount == 1 { completion(nil, HtmlProbeError.expected) }
                else { completion(compiledRule, nil) }
            })
        let recoveryHTML = "<strong>same html</strong>"
        let failingWebView = failingCoordinator.makeWebView(
            html: recoveryHTML, source: "failed.ssc:9:3 [rawHtml]")
        guard await waitFor({ failureErrors.compactMap({ $0 }).count == 1 }), failingWebView.url == nil,
              failureErrors.compactMap({ $0 }).first?.contains("failed.ssc:9:3 [rawHtml]") == true else {
            fatalError("trusted HTML compile failure loaded or lost source: \(failureErrors)")
        }
        failingCoordinator.update(
            html: recoveryHTML, source: "recovered.ssc:12:5 [rawHtml]", in: failingWebView)
        guard await waitFor({
            failureCompileCount == 2 && failureErrors.last.map { $0 == nil } == true &&
                failingCoordinator.probeNavigation(at: 0) != nil
        }) else {
            fatalError("trusted HTML same-html source-only recovery failed: \(failureErrors) \(failingCoordinator.probeState())")
        }
        failingCoordinator.dismantle(failingWebView)

        var nilLoadErrors: [String?] = []
        let nilLoadCoordinator = NativeUiHtmlCoordinator(
            safeExternalURL: safe, openURL: { _ in }, publishHeight: { _ in },
            publishError: { nilLoadErrors.append($0) },
            _probeRuleCompiler: { completion in completion(compiledRule, nil) },
            _probeNavigationLoader: { _, _ in nil })
        let nilLoadWebView = nilLoadCoordinator.makeWebView(
            html: "nil load", source: "nil-load.ssc:3:7 [rawHtml]")
        guard await waitFor({ nilLoadErrors.compactMap({ $0 }).count == 1 }),
              nilLoadWebView.url == nil,
              nilLoadErrors.compactMap({ $0 }).first?.contains("navigation did not start") == true,
              nilLoadErrors.compactMap({ $0 }).first?.contains("nil-load.ssc:3:7 [rawHtml]") == true else {
            fatalError("trusted HTML nil navigation start did not fail closed: \(nilLoadErrors)")
        }
        nilLoadCoordinator.dismantle(nilLoadWebView)

        var pendingCompiles: [((WKContentRuleList?, Error?) -> Void)] = []
        var staleErrors: [String?] = []
        let staleCoordinator = NativeUiHtmlCoordinator(
            safeExternalURL: safe, openURL: { _ in }, publishHeight: { _ in },
            publishError: { staleErrors.append($0) },
            _probeRuleCompiler: { completion in pendingCompiles.append(completion) })
        let staleWebView = staleCoordinator.makeWebView(html: "old", source: "old.ssc:1:1 [rawHtml]")
        staleCoordinator.update(html: "new", source: "new.ssc:2:1 [rawHtml]", in: staleWebView)
        guard pendingCompiles.count == 2 else { fatalError("trusted HTML generation compile count") }
        pendingCompiles[0](nil, HtmlProbeError.expected)
        guard staleErrors.compactMap({ $0 }).isEmpty, staleWebView.url == nil else {
            fatalError("trusted HTML stale compile failure published")
        }
        pendingCompiles[1](nil, HtmlProbeError.expected)
        guard staleErrors.compactMap({ $0 }).count == 1,
              staleErrors.compactMap({ $0 }).first?.contains("new.ssc:2:1 [rawHtml]") == true else {
            fatalError("trusted HTML current compile failure missing")
        }
        staleCoordinator.dismantle(staleWebView)

        var orphanCoordinator: NativeUiHtmlCoordinator? = NativeUiHtmlCoordinator(
            safeExternalURL: safe, openURL: { _ in }, publishHeight: { _ in },
            publishError: { _ in }, _probeRuleCompiler: { _ in })
        let orphanWebView = orphanCoordinator!.makeWebView(
            html: "orphan", source: "orphan.ssc:1:1 [rawHtml]")
        let weakOrphan = WeakCoordinatorBox(orphanCoordinator)
        orphanCoordinator = nil
        guard weakOrphan.value == nil,
              orphanWebView.navigationDelegate == nil,
              orphanWebView.uiDelegate == nil else {
            fatalError("trusted HTML coordinator deinit without dismantle")
        }
        let stableHeight = heights.last!
        let publishCount = heights.count
        coordinator!.dismantle(webView)
        _ = try? await webView.evaluateJavaScript("document.body.style.height='900px'")
        try? await Task.sleep(nanoseconds: 150_000_000)
        guard heights.count == publishCount, heights.last == stableHeight, opened.count == 2 else {
            fatalError("trusted HTML published after dismantle")
        }
        let weakCoordinator = WeakCoordinatorBox(coordinator)
        coordinator = nil
        guard weakCoordinator.value == nil else { fatalError("trusted HTML coordinator retained after dismantle") }
        window.close()
        print("html")
    }
}
"""

  private val nativeUiUnsupportedProbe = """
public enum SessionProbe {
    private static func fields(_ value: SscValue, _ tag: String) -> [SscValue] {
        guard case let .data(actual, fields) = value, actual == tag else { fatalError("expected \(tag)") }
        return fields.asArray()
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

  private def xcrunSwiftAvailable: Boolean =
    try
      val process = new ProcessBuilder("xcrun", "--find", "swiftc").start()
      process.waitFor() == 0
    catch case _: Exception => false

  private def xcodebuildAvailable: Boolean =
    try
      val process = new ProcessBuilder("xcodebuild", "-version").start()
      process.waitFor() == 0
    catch case _: Exception => false

  private def runProcess(root: Path, command: String*): (Int, String) =
    val process = new ProcessBuilder(command*)
      .directory(root.toFile)
      .redirectErrorStream(true)
      .start()
    val output = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    process.waitFor() -> output

  private def runNativeTableProbe(mode: String, includeAppleSources: Boolean = false): String =
    val root = Files.createTempDirectory(s"ssc-swift-table-$mode-")
    val errors = root.resolve("swift.stderr")
    try
      val generated = SwiftBackend.generate(
        nativeUiTableProgram(), "NativeTable", backendBaseUrl = Some("https://api.example/base"))
      generated.writeTo(root)
      val probe = root.resolve("NativeTableProbe.swift")
      val binary = root.resolve("NativeTableProbe")
      Files.writeString(probe, nativeUiTableProbe, StandardCharsets.UTF_8)
      val sources = generated.files.collect {
        case (path, _) if path.startsWith("Sources/AppCore/") || path == "AppleApp/NativeUiStore.swift" ||
            (includeAppleSources && path.startsWith("AppleApp/") && !path.endsWith("App.swift")) =>
          root.resolve(path).toString
      }
      if includeAppleSources then
        val appleSources = generated.files.collect {
          case (path, _) if path.startsWith("Sources/AppCore/") || path.startsWith("AppleApp/") =>
            root.resolve(path).toString
        }
        val appleErrors = root.resolve("apple-typecheck.stderr")
        val apple = new ProcessBuilder((List(
          "xcrun", "swiftc", "-typecheck", "-parse-as-library", "-swift-version", "6",
          "-strict-concurrency=complete", "-warnings-as-errors",
        ) ++ appleSources)*).redirectError(appleErrors.toFile).start()
        val appleOut = new String(apple.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        val appleExit = apple.waitFor()
        val appleErr = Files.readString(appleErrors, StandardCharsets.UTF_8)
        assert(appleExit == 0, s"Swift native table Apple typecheck failed ($appleExit):\n$appleErr\n$appleOut")
        val sdkProbe = new ProcessBuilder("xcrun", "--sdk", "iphonesimulator", "--show-sdk-path").start()
        val sdkPath = new String(sdkProbe.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
        val sdkExit = sdkProbe.waitFor()
        if sdkExit == 0 && sdkPath.nonEmpty then
          val architecture = if sys.props.getOrElse("os.arch", "").contains("aarch64") then "arm64" else "x86_64"
          val iosErrors = root.resolve("ios-typecheck.stderr")
          val ios = new ProcessBuilder((List(
            "xcrun", "swiftc", "-typecheck", "-parse-as-library", "-swift-version", "6",
            "-strict-concurrency=complete", "-warnings-as-errors", "-sdk", sdkPath,
            "-target", s"$architecture-apple-ios16.0-simulator",
          ) ++ appleSources)*).redirectError(iosErrors.toFile).start()
          val iosOut = new String(ios.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
          val iosExit = ios.waitFor()
          val iosErr = Files.readString(iosErrors, StandardCharsets.UTF_8)
          assert(iosExit == 0, s"Swift native table iOS typecheck failed ($iosExit):\n$iosErr\n$iosOut")
        else info("iOS Simulator SDK unavailable; native table iOS typecheck recorded as environment skip")
      val appleDefine = if includeAppleSources then List("-DSSC_TABLE_APPLE") else Nil
      val compile = new ProcessBuilder((List(
        "xcrun", "swiftc", "-parse-as-library", "-swift-version", "6",
        "-strict-concurrency=complete", "-warnings-as-errors",
      ) ++ appleDefine ++ sources ++ List(probe.toString, "-o", binary.toString))*).redirectError(errors.toFile).start()
      val compileOut = new String(compile.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val compileExit = compile.waitFor()
      val compileErr = Files.readString(errors, StandardCharsets.UTF_8)
      assert(compileExit == 0, s"Swift native table compile failed ($compileExit):\n$compileErr\n$compileOut")
      val run = new ProcessBuilder(binary.toString, mode).redirectError(errors.toFile).start()
      val stdout = new String(run.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val exit = run.waitFor()
      val stderr = Files.readString(errors, StandardCharsets.UTF_8)
      assert(exit == 0, s"Swift native table probe failed ($exit):\n$stderr\n$stdout")
      stdout
    finally deleteRecursively(root)

  private def runNativeHtmlProbe(): String =
    val root = Files.createTempDirectory("ssc-swift-trusted-html-")
    val errors = root.resolve("swift.stderr")
    try
      val generated = SwiftBackend.generate(nativeUiRawHtmlProgram(), "NativeHtml")
      generated.writeTo(root)
      val probe = root.resolve("NativeHtmlProbe.swift")
      val binary = root.resolve("NativeHtmlProbe")
      Files.writeString(probe, nativeUiHtmlProbe, StandardCharsets.UTF_8)
      val sources = generated.files.collect {
        case (path, _) if path.startsWith("Sources/AppCore/") ||
            (path.startsWith("AppleApp/") && !path.endsWith("App.swift")) =>
          root.resolve(path).toString
      }
      val compile = new ProcessBuilder((List(
        "xcrun", "swiftc", "-parse-as-library", "-swift-version", "6",
        "-strict-concurrency=complete", "-warnings-as-errors", "-DSSC_NATIVEUI_HTML_PROBE",
      ) ++ sources ++ List(probe.toString, "-o", binary.toString))*).redirectError(errors.toFile).start()
      val compileOut = new String(compile.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val compileExit = compile.waitFor()
      val compileErr = Files.readString(errors, StandardCharsets.UTF_8)
      assert(compileExit == 0, s"Swift trusted HTML compile failed ($compileExit):\n$compileErr\n$compileOut")
      val sdkProbe = new ProcessBuilder("xcrun", "--sdk", "iphonesimulator", "--show-sdk-path").start()
      val sdkPath = new String(sdkProbe.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val sdkExit = sdkProbe.waitFor()
      if sdkExit == 0 && sdkPath.nonEmpty then
        val architecture = if sys.props.getOrElse("os.arch", "").contains("aarch64") then "arm64" else "x86_64"
        val iosErrors = root.resolve("ios-typecheck.stderr")
        val appleSources = generated.files.collect {
          case (path, _) if path.startsWith("Sources/AppCore/") || path.startsWith("AppleApp/") =>
            root.resolve(path).toString
        }
        val ios = new ProcessBuilder((List(
          "xcrun", "swiftc", "-typecheck", "-parse-as-library", "-swift-version", "6",
          "-strict-concurrency=complete", "-warnings-as-errors", "-sdk", sdkPath,
          "-target", s"$architecture-apple-ios16.0-simulator",
        ) ++ appleSources)*).redirectError(iosErrors.toFile).start()
        val iosOut = new String(ios.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        val iosExit = ios.waitFor()
        val iosErr = Files.readString(iosErrors, StandardCharsets.UTF_8)
        assert(iosExit == 0, s"Swift trusted HTML iOS typecheck failed ($iosExit):\n$iosErr\n$iosOut")
      else info("iOS Simulator SDK unavailable; trusted HTML typecheck recorded as environment skip")
      val run = new ProcessBuilder(binary.toString).redirectError(errors.toFile).start()
      val stdout = new String(run.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val exit = run.waitFor()
      val stderr = Files.readString(errors, StandardCharsets.UTF_8)
      assert(exit == 0, s"Swift trusted HTML probe failed ($exit):\n$stderr\n$stdout")
      stdout
    finally deleteRecursively(root)

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
      val product = generated.debugCli
      val emitted = probe match
        case None => generated
        case Some(source) =>
          val mainPath = s"Sources/$product/main.swift"
          val files = generated.files.map {
            case (`mainPath`, _) => mainPath -> "import AppCore\n\nSessionProbe.run()\n"
            case other => other
          } :+ ("Sources/AppCore/SessionProbe.swift" -> source)
          SwiftPackage(files, generated.debugCli)
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
