package scalascript.imports

/** Resolves `jitpack:` coordinates by delegating to Coursier with the JitPack repository enabled. */
class JitpackResolver extends MavenDepResolver(extraRepositories = Seq("jitpack")):
  override val scheme: String = "jitpack"

  override def resolve(spec: scalascript.backend.spi.DepSpec): java.nio.file.Path =
    val depSpec = spec.copy(raw = "dep:" + spec.raw.stripPrefix("jitpack:"))
    super.resolve(depSpec)
