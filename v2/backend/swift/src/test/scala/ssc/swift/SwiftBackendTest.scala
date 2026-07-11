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
      "AppleApp/Native_AppApp.swift",
      "AppleApp/NativeUiStore.swift",
      "AppleApp/NativeUiRenderer.swift",
      "AppleApp/NativeUiStyles.swift",
      "AppleApp/NativeUiHtml.swift",
    ))
    assert(ui.executable == "Native_AppCli")
    assert(ui.files.find(_._1.endsWith("NativeUiHost.swift")).exists(!_._2.contains("import SwiftUI")))
    assert(ui.files.find(_._1.endsWith("NativeUiStore.swift")).exists(_._2.contains("ObservableObject")))
    assert(ui.files.find(_._1.endsWith("NativeUiRenderer.swift")).exists(_._2.contains("NativeUiForKeyedView")))

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
        (List("xcrun", "swiftc", "-parse-as-library") ++ sources ++ List(probe.toString, "-o", binary.toString))*
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
      assert(stdout == "fetch-signal|fetch-action|css-value|attribute|behavior|source")
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
    val root = Term.App(Term.Global("fragment"), List(list(List(
      Term.App(Term.Global("signalText"), List(Term.Local(1))),
      Term.Local(0),
    ))))
    Program(Nil, Term.Let(List(refresh, body, fetch, action),
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
    @MainActor
    static func main() {
        let store = NativeUiStore()
        let abi = fields(store.root, "NativeUiAbi")
        let children = list(fields(abi[1], "NativeUiFragment")[0])
        let fetch = fields(children[0], "NativeUiSignalText")[0]
        let action = children[1]
        guard store.signalKind(fetch) == "fetch",
              store.source(for: fetch) == "deferred.ssc:10:3 [fetchUrlSignal]",
              store.cell(for: fetch).renderedDiagnostic() ==
                "fetch signal adapter pending at deferred.ssc:10:3 [fetchUrlSignal]" else {
            fatalError("fetch signal source missing")
        }
        NativeUiActions.run(action, input: nil, store: store, siteId: "deferred:action")
        guard store.failure == "fetch action adapter pending at deferred.ssc:20:3 [fetchAction]" else {
            fatalError("fetch action was silent")
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
            "border:1px solid red junk", "border:1px solid banana;border-color:red", "box-shadow:banana"
        ] {
            let attrs = SscMap()
            attrs.put(.string("style"), .string(invalid))
            guard NativeUiStyles.diagnostic(
                attrs: attrs, store: store, siteId: "deferred:action"
            )?.contains("deferred.ssc:20:3 [fetchAction]") == true else {
                fatalError("recognized invalid CSS was silent: \(invalid)")
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
            siteId: "deferred:action"
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
            siteId: "deferred:action"
        )
        guard store.failure ==
            "native event increment target must be NativeUiSignal at deferred.ssc:20:3 [fetchAction]" else {
            fatalError("malformed increment target was silent or unsourced")
        }
        NativeUiActions.run(
            .data("NativeUiEvent", [.string("set"), fetch, .string("x"), .unit]),
            input: nil,
            store: store,
            siteId: "deferred:action"
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
            siteId: "deferred:action"
        )
        guard store.failure ==
            "native event set metadata key must be String at deferred.ssc:20:3 [fetchAction]" else {
            fatalError("non-string event metadata key was accepted")
        }
        let fetchFields = fields(fetch, "NativeUiSignal")
        let forgedKind = SscValue.data("NativeUiSignal", [
            fetchFields[0], fetchFields[1], .string("bogus"),
            fetchFields[3], fetchFields[4], fetchFields[5]
        ])
        NativeUiActions.run(
            .data("NativeUiEvent", [.string("increment"), forgedKind, .int(1), .map(SscMap())]),
            input: nil,
            store: store,
            siteId: "deferred:action"
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
            siteId: "deferred:action"
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
        Swift.print("fetch-signal|fetch-action|css-value|attribute|behavior|source")
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
            guard deleted.disposedSignalKeys.contains("a\u{0}local") else { fatalError("delete retained a") }
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
        guard store.failure?.contains("__computed__observation:b -> __computed__observation:c -> __computed__observation:b") == true,
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
            Swift.print("source=\(string(source[0])):\(string(source[2])):;column=\(string(column[0])):\(string(column[1])):\(string(column[2])):\(string(column[3])):edit=();delete=\(action(actions[0]));post=\(action(actions[1]))")
        } catch { fatalError(String(describing: error)) }
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
