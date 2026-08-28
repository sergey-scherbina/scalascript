package scalascript.cli

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import org.scalatest.funsuite.AnyFunSuite

class NativeImageInstallRootTest extends AnyFunSuite:

  private def createLayout(root: Path, executable: Path): Unit =
    Files.createDirectories(root.resolve("bin/lib/standard/native-front"))
    Files.createDirectories(executable.getParent)
    Files.writeString(executable, "native executable")

  test("discovers archive-root native executable layout"):
    val sandbox = Files.createTempDirectory("ssc-native-root-")
    try
      val executable = sandbox.resolve("ssc")
      createLayout(sandbox, executable)
      assert(NativeImageInstallRoot.discoverRoot(executable).contains(sandbox.toRealPath()))
    finally os.remove.all(os.Path(sandbox))

  test("discovers conventional bin native executable layout"):
    val sandbox = Files.createTempDirectory("ssc-native-bin-")
    try
      val executable = sandbox.resolve("bin/ssc")
      createLayout(sandbox, executable)
      assert(NativeImageInstallRoot.discoverRoot(executable).contains(sandbox.toRealPath()))
    finally os.remove.all(os.Path(sandbox))

  test("resolves a symlink to the real archive root"):
    val sandbox = Files.createTempDirectory("ssc-native-link-")
    try
      val root = sandbox.resolve("distribution")
      val executable = root.resolve("ssc")
      createLayout(root, executable)
      val links = sandbox.resolve("links")
      Files.createDirectories(links)
      val link = Files.createSymbolicLink(links.resolve("ssc"), executable)
      assert(NativeImageInstallRoot.discoverRoot(link).contains(root.toRealPath()))
    finally os.remove.all(os.Path(sandbox))

  test("does not invent a root when native frontend data is absent"):
    val sandbox = Files.createTempDirectory("ssc-native-missing-")
    try
      val executable = sandbox.resolve("ssc")
      Files.writeString(executable, "native executable")
      assert(NativeImageInstallRoot.discoverRoot(executable).isEmpty)
    finally os.remove.all(os.Path(sandbox))

  test("native resolution preserves property then environment precedence"):
    val sandbox = Files.createTempDirectory("ssc-native-policy-")
    try
      val executable = sandbox.resolve("ssc")
      createLayout(sandbox, executable)
      val expected = Some(sandbox.toRealPath().toString)
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
  // only path that ever calls `discoverRoot`, so a checkout (where `scripts/sbtc` and `bin/ssc`
  // exist) can never be the audience. The old wording named them anyway; this asserts it cannot
  // happen again by naming what the message must and must not say.
  test("the missing-install-root message is actionable for a downloaded release binary, not a checkout"):
    val message = NativeImageInstallRoot.MissingInstallRootMessage
    assert(message.contains("ssc-<platform>.tar.gz"), message)
    assert(message.contains("SSC_LIB_PATH"), message)
    assert(!message.contains("sbtc"), message)
    assert(!message.contains("bin/ssc"), message)

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
