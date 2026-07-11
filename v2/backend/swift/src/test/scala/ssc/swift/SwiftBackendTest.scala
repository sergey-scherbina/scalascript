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
