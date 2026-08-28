package scalascript.cli

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import org.scalatest.funsuite.AnyFunSuite

class NativeImageInstallRootTest extends AnyFunSuite:

  private def createLayout(lib: Path, executable: Path): Unit =
    Files.createDirectories(lib.resolve("native-front"))
    Files.createDirectories(executable.getParent)
    Files.writeString(executable, "native executable")

  test("discovers archive layout: ssc + lib/* (no bin/)"):
    val sandbox = Files.createTempDirectory("ssc-native-root-")
    try
      val executable = sandbox.resolve("ssc")
      createLayout(sandbox.resolve("lib"), executable)
      assert(
        NativeImageInstallRoot.discoverLib(executable)
          .contains(sandbox.resolve("lib").toRealPath()))
    finally os.remove.all(os.Path(sandbox))

  test("discovers checkout layout: bin/ssc + bin/lib/*"):
    val sandbox = Files.createTempDirectory("ssc-native-bin-")
    try
      val executable = sandbox.resolve("bin/ssc")
      createLayout(sandbox.resolve("bin/lib"), executable)
      assert(
        NativeImageInstallRoot.discoverLib(executable)
          .contains(sandbox.resolve("bin/lib").toRealPath()))
    finally os.remove.all(os.Path(sandbox))

  test("discovers install-prefix layout: bin/ssc + lib/* one level up (bin/ a sibling of lib/)"):
    val sandbox = Files.createTempDirectory("ssc-native-prefix-")
    try
      val executable = sandbox.resolve("bin/ssc")
      createLayout(sandbox.resolve("lib"), executable)
      assert(
        NativeImageInstallRoot.discoverLib(executable)
          .contains(sandbox.resolve("lib").toRealPath()))
    finally os.remove.all(os.Path(sandbox))

  test("a lib/ beside the executable wins over one one level up"):
    val sandbox = Files.createTempDirectory("ssc-native-precedence-")
    try
      val executable = sandbox.resolve("bin/ssc")
      // Both a sibling bin/lib/* AND a one-level-up lib/* exist; the sibling must win.
      createLayout(sandbox.resolve("bin/lib"), executable)
      createLayout(sandbox.resolve("lib"), executable)
      assert(
        NativeImageInstallRoot.discoverLib(executable)
          .contains(sandbox.resolve("bin/lib").toRealPath()))
    finally os.remove.all(os.Path(sandbox))

  test("resolves a symlink to the real archive lib/"):
    val sandbox = Files.createTempDirectory("ssc-native-link-")
    try
      val root = sandbox.resolve("distribution")
      val executable = root.resolve("ssc")
      createLayout(root.resolve("lib"), executable)
      val links = sandbox.resolve("links")
      Files.createDirectories(links)
      val link = Files.createSymbolicLink(links.resolve("ssc"), executable)
      assert(NativeImageInstallRoot.discoverLib(link).contains(root.resolve("lib").toRealPath()))
    finally os.remove.all(os.Path(sandbox))

  test("does not invent a lib/ when native frontend data is absent"):
    val sandbox = Files.createTempDirectory("ssc-native-missing-")
    try
      val executable = sandbox.resolve("ssc")
      Files.writeString(executable, "native executable")
      assert(NativeImageInstallRoot.discoverLib(executable).isEmpty)
    finally os.remove.all(os.Path(sandbox))

  test("native resolution preserves property then environment precedence"):
    val sandbox = Files.createTempDirectory("ssc-native-policy-")
    try
      val executable = sandbox.resolve("ssc")
      createLayout(sandbox.resolve("lib"), executable)
      val expected = Some(sandbox.resolve("lib").toRealPath().toString)
      assert(
        NativeImageInstallRoot
          .resolve(
            isNativeRuntime = true,
            configuredLibPath = Some("/property/install"),
            environmentLibPath = Some("/environment/install"),
            executable = Some(executable)
          )
          .isEmpty)
      assert(
        NativeImageInstallRoot.resolve(
          isNativeRuntime = true,
          configuredLibPath = None,
          environmentLibPath = Some("/environment/install"),
          executable = Some(executable)
        ).contains("/environment/install"))
      assert(
        NativeImageInstallRoot.resolve(
          isNativeRuntime = true,
          configuredLibPath = None,
          environmentLibPath = None,
          executable = Some(executable)
        ) == expected)
      assert(
        NativeImageInstallRoot.resolve(
          isNativeRuntime = true,
          configuredLibPath = None,
          environmentLibPath = Some(""),
          executable = Some(executable)
        ) == expected)
    finally os.remove.all(os.Path(sandbox))

  test("does not query the executable unless native discovery is required"):
    var queries = 0
    def queriedExecutable: Option[Path] =
      queries += 1
      None

    assert(
      NativeImageInstallRoot.resolve(
        isNativeRuntime = false,
        configuredLibPath = None,
        environmentLibPath = None,
        executable = queriedExecutable
      ).isEmpty)
    assert(queries == 0)

    assert(
      NativeImageInstallRoot.resolve(
        isNativeRuntime = true,
        configuredLibPath = Some("/property/install"),
        environmentLibPath = None,
        executable = queriedExecutable
      ).isEmpty)
    assert(queries == 0)

    assert(
      NativeImageInstallRoot.resolve(
        isNativeRuntime = true,
        configuredLibPath = None,
        environmentLibPath = Some("/environment/install"),
        executable = queriedExecutable
      ).contains("/environment/install"))
    assert(queries == 0)

    assert(
      NativeImageInstallRoot.resolve(
        isNativeRuntime = true,
        configuredLibPath = None,
        environmentLibPath = None,
        executable = queriedExecutable
      ).isEmpty)
    assert(queries == 1)

  // The message this class provides for RunNativeV2/NativeJvmArtifact to throw when discovery
  // fails. It is asserted HERE, not only where it is thrown, because the situation that reaches
  // either throw site is exactly the one `configure()` documents above: `isNativeRuntime` is the
  // only path that ever calls `discoverLib`, so a checkout (where `scripts/sbtc` and `bin/ssc`
  // exist) can never be the audience. The old wording named them anyway; this asserts it cannot
  // happen again by naming what the message must and must not say.
  test("the missing-install-root message is actionable for a downloaded release binary, not a checkout"):
    val message = NativeImageInstallRoot.MissingInstallRootMessage
    assert(message.contains("ssc-<platform>.tar.gz"), message)
    assert(message.contains("SSC_LIB_PATH"), message)
    assert(message.contains("lib/"), message)
    assert(!message.contains("sbtc"), message)
    assert(!message.contains("bin/ssc"), message)

  // resolveUnderLib / isLibShaped / rootAbove: the dual-shape contract RunNativeV2 and
  // NativeJvmArtifact rely on so the SAME code works whether ssc.lib.path holds the new lib/-dir
  // shape (native-image discovery, or SSC_LIB_PATH pointed at lib/) or the older checkout-ROOT
  // shape a JVM launcher still sets explicitly and unmodified.
  test("resolveUnderLib prefers the direct lib/-dir shape over the bin/lib ROOT shape"):
    val sandbox = Files.createTempDirectory("ssc-resolve-lib-shaped-")
    try
      Files.createDirectories(sandbox.resolve("native-front"))
      val resolved = NativeImageInstallRoot.resolveUnderLib(sandbox.toFile, "native-front")
      assert(resolved.toPath == sandbox.resolve("native-front"))
    finally os.remove.all(os.Path(sandbox))

  test("resolveUnderLib falls back to the bin/lib ROOT shape when there is no direct lib/ dir"):
    val sandbox = Files.createTempDirectory("ssc-resolve-root-shaped-")
    try
      Files.createDirectories(sandbox.resolve("bin/lib/native-front"))
      val resolved = NativeImageInstallRoot.resolveUnderLib(sandbox.toFile, "native-front")
      assert(resolved.toPath == sandbox.resolve("bin/lib/native-front"))
    finally os.remove.all(os.Path(sandbox))

  test("isLibShaped agrees with which shape resolveUnderLib actually took"):
    val libShaped = Files.createTempDirectory("ssc-shape-lib-")
    val rootShaped = Files.createTempDirectory("ssc-shape-root-")
    try
      Files.createDirectories(libShaped.resolve("native-front"))
      Files.createDirectories(rootShaped.resolve("bin/lib/native-front"))
      assert(NativeImageInstallRoot.isLibShaped(libShaped.toFile, "native-front"))
      assert(!NativeImageInstallRoot.isLibShaped(rootShaped.toFile, "native-front"))
    finally
      os.remove.all(os.Path(libShaped))
      os.remove.all(os.Path(rootShaped))

  test("rootAbove walks up one level, or two when the first level up is bin/"):
    val archiveLib = Path.of("/distribution/lib")
    assert(NativeImageInstallRoot.rootAbove(archiveLib.toFile).toPath == Path.of("/distribution"))
    val checkoutLib = Path.of("/checkout/bin/lib")
    assert(NativeImageInstallRoot.rootAbove(checkoutLib.toFile).toPath == Path.of("/checkout"))

  test("ships native-image runtime initialization for the application namespace"):
    val resource =
      "META-INF/native-image/scalascript/ssc/native-image.properties"
    val stream = Option(getClass.getClassLoader.getResourceAsStream(resource))
      .getOrElse(fail(s"missing classpath resource: $resource"))
    val actual =
      try String(stream.readAllBytes(), StandardCharsets.UTF_8)
      finally stream.close()
    val expected =
      "Args = --initialize-at-run-time=os.package$,scalascript,ssc\n"
    assert(actual == expected)
