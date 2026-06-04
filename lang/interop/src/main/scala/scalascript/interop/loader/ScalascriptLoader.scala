package scalascript.interop.loader

import scalascript.ir.ModuleInterface
import scalascript.artifact.ArtifactIO
import scalascript.interop.facade.FacadeGenerator

import java.net.URLClassLoader
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*

/** Raised when a natural FQN doesn't appear in any loaded `.scim`. */
case class NoSuchScalascriptSymbol(naturalFqn: String, loadedCount: Int)
  extends RuntimeException(
    s"unknown ScalaScript symbol: $naturalFqn (loader holds $loadedCount entries)")

/** Raised when the mangled FQN exists in the facade but the underlying
 *  JVM class/method can't be located on the classloader.  Usually
 *  means the linked `.jar` is missing from the classpath, OR the JAR
 *  was built against a different ScalaScript ABI version. */
case class UnresolvedJvmMember(mangledFqn: String, detail: String)
  extends RuntimeException(s"cannot resolve JVM member: $mangledFqn — $detail")

/** Tier 2 of the Scala ↔ ScalaScript interop (docs/specs/scala-interop.md).
 *
 *  Dynamic / reflection-based bridge for **plugin-style** consumption of
 *  ScalaScript-compiled JARs.  Use cases:
 *    - hot-load a `.ssc`-compiled artifact at runtime,
 *    - drive a ScalaScript library from a host Scala application
 *      without compile-time bindings,
 *    - tooling (REPL helpers, runners, IDE inspectors).
 *
 *  Cold path: every call resolves the mangled FQN, looks up the method
 *  reflectively, then invokes.  Not for hot loops; use the facade-
 *  generated bindings (compile-time `export`s) for those.
 *
 *  Discovery:
 *    1. Construct with a JAR path OR a directory of `.scim` artifacts.
 *    2. JAR mode: looks for `META-INF/scalascript/{module}.scim` resources
 *       inside the JAR (emitted by `ssc link --emit-scala-facade`,
 *       Tier 4).  Falls back to scanning the artifact dir at construct
 *       time when called from `fromArtifactDir`.
 *    3. Builds an in-memory `natural → mangled` index.
 *
 *  Thread safety: index built at construction is immutable; reflection
 *  cache (`memberCache`) uses a thread-safe `ConcurrentHashMap`. */
class ScalascriptLoader private (
    private val interfaces: List[ModuleInterface],
    private val classLoader: ClassLoader
):

  /** Natural-FQN → mangled-JVM-FQN. */
  private val facade: Map[String, String] =
    interfaces
      .flatMap(FacadeGenerator.facadeEntriesOf)
      .toMap

  /** Reverse: mangled-JVM-FQN → natural-FQN.  Useful for stack-trace /
   *  diagnostic rewriting. */
  private val reverseFacade: Map[String, String] =
    facade.map(_.swap)

  /** Reflection cache: (className, methodName) → resolved java.lang.reflect.Method. */
  private val memberCache =
    new java.util.concurrent.ConcurrentHashMap[(String, String), java.lang.reflect.Method]()

  /** All natural FQNs this loader can dispatch to. */
  def naturalNames: Set[String] = facade.keySet

  /** Look up the mangled JVM FQN for a natural ScalaScript name.
   *  Returns `None` for unknown names. */
  def mangle(naturalFqn: String): Option[String] = facade.get(naturalFqn)

  /** Reverse lookup: natural FQN for a mangled name.
   *  Returns `None` when the mangled name doesn't appear in any loaded
   *  interface — useful for filtering "this is ScalaScript" vs. "this
   *  is unrelated Scala stack frame" in diagnostic tooling. */
  def naturalFor(mangledFqn: String): Option[String] = reverseFacade.get(mangledFqn)

  /** Reflectively call a ScalaScript symbol by its natural FQN.
   *
   *  Throws [[NoSuchScalascriptSymbol]] if the name doesn't appear in
   *  any loaded `.scim`; [[UnresolvedJvmMember]] if the mangled name
   *  exists in the facade but the JVM class/method isn't on the
   *  classloader's path.
   *
   *  No type coercion: `args` are passed through to `Method.invoke` as-is.
   *  Caller is responsible for matching the underlying Scala signature
   *  (the `.scim` `tpe` field is best-effort and not enforced here).
   *
   *  Result is cast to `A` — caller picks the type; mismatch yields a
   *  `ClassCastException` at the use site. */
  def call[A](naturalFqn: String, args: Any*): A =
    val mangled = facade.getOrElse(naturalFqn,
      throw NoSuchScalascriptSymbol(naturalFqn, naturalNames.size))
    val (cls, member) = splitMangled(mangled)
    val method = memberCache.computeIfAbsent((cls, member), { _ =>
      resolveMethod(cls, member, args.length)
    })
    val javaArgs: Array[AnyRef] = args.toArray.map(_.asInstanceOf[AnyRef])
    // For top-level objects emitted as `object Foo`, Scala places statics
    // on `Foo$.MODULE$`.  Resolve the singleton instance once.
    val instance: AnyRef =
      if java.lang.reflect.Modifier.isStatic(method.getModifiers) then null
      else
        val clazz = Class.forName(cls + "$", true, classLoader)
        clazz.getField("MODULE$").get(null)
    method.invoke(instance, javaArgs*).asInstanceOf[A]

  /** Split a mangled FQN like `_ssc_runtime.std_eq_eqv` into a
   *  (className, memberName) pair.
   *
   *  ScalaScript v2.0 wraps everything in `object _ssc_runtime` at the
   *  Scala source level; the class on disk is `_ssc_runtime$`.  We
   *  ALWAYS use `_ssc_runtime` as the carrier class and treat the
   *  remainder of the mangled name as the member.  This matches how
   *  JvmGen actually emits the runtime today. */
  private def splitMangled(mangledFqn: String): (String, String) =
    val sep = mangledFqn.indexOf('.')
    if sep < 0 then ("", mangledFqn)
    else (mangledFqn.substring(0, sep), mangledFqn.substring(sep + 1))

  /** Resolve a Java reflection `Method` on `cls$` (the Scala 3 module
   *  class) for a member of the given name with the given arity.
   *
   *  We don't currently disambiguate overloads by argument types — we
   *  pick the first method matching `name + arity`.  Overloaded
   *  ScalaScript defs are rare in practice; if they become common, this
   *  resolver gets a type-aware variant. */
  private def resolveMethod(cls: String, member: String, arity: Int): java.lang.reflect.Method =
    val carrierClassName = cls + "$"
    val clazz =
      try Class.forName(carrierClassName, true, classLoader)
      catch
        case _: ClassNotFoundException =>
          throw UnresolvedJvmMember(s"$cls.$member", s"class $carrierClassName not found on classpath")
    clazz.getDeclaredMethods.find(m =>
      m.getName == member && m.getParameterCount == arity
    ).getOrElse:
      throw UnresolvedJvmMember(s"$cls.$member",
        s"no method `$member` with $arity parameter(s) on $carrierClassName " +
        s"(found: ${clazz.getDeclaredMethods.map(m => s"${m.getName}/${m.getParameterCount}").mkString(", ")})")


object ScalascriptLoader:

  /** Build a loader from a directory of `.scim` files.  The classloader
   *  used for reflective lookups defaults to the current thread's
   *  context classloader — caller must ensure the matching `.jar` (or
   *  classes dir) is reachable. */
  def fromArtifactDir(
      artifactDir: os.Path,
      classLoader: ClassLoader = Thread.currentThread().getContextClassLoader
  ): ScalascriptLoader =
    if !os.exists(artifactDir) then
      throw IllegalArgumentException(s"artifact dir does not exist: $artifactDir")
    if !os.isDir(artifactDir) then
      throw IllegalArgumentException(s"artifact dir is not a directory: $artifactDir")
    val scims = os.list(artifactDir).filter(p =>
      os.isFile(p) && p.ext == "scim"
    ).toList
    val ifaces = scims.flatMap(p => ArtifactIO.readInterfaceFile(p).toOption)
    new ScalascriptLoader(ifaces, classLoader)

  /** Build a loader from a JAR that was produced by
   *  `ssc link --backend jvm --bytecode --emit-scala-facade -o lib.jar`
   *  (Tier 4 — when it ships).  Reads embedded `META-INF/scalascript/{module}.scim`
   *  resources from the JAR.
   *
   *  Adds the JAR to the classloader so reflection can find the
   *  carrier classes without external setup.
   *
   *  When the JAR was built without `--emit-scala-facade` (no embedded
   *  `.scim` resources), returns a loader with an empty index — caller
   *  can detect this via `naturalNames.isEmpty` and fall back to manual
   *  setup. */
  def fromJar(
      jar: os.Path,
      parent: ClassLoader = ClassLoader.getSystemClassLoader
  ): ScalascriptLoader =
    if !os.exists(jar) then
      throw IllegalArgumentException(s"jar does not exist: $jar")
    val cl = new URLClassLoader(Array(jar.toIO.toURI.toURL), parent)
    val ifaces = readEmbeddedInterfaces(jar)
    new ScalascriptLoader(ifaces, cl)

  /** Read `META-INF/scalascript/{module}.scim` entries from a JAR.  Tier-4
   *  output convention; safe to call against any JAR (returns empty for
   *  non-conforming inputs). */
  private def readEmbeddedInterfaces(jar: os.Path): List[ModuleInterface] =
    val zf = new ZipFile(jar.toIO)
    try
      zf.entries.asScala
        .filter(e => !e.isDirectory && e.getName.startsWith("META-INF/scalascript/") && e.getName.endsWith(".scim"))
        .toList
        .flatMap { e =>
          val text = new String(zf.getInputStream(e).readAllBytes(), "UTF-8")
          ArtifactIO.readInterface(text).toOption
        }
    finally zf.close()

end ScalascriptLoader
