package scalascript.controlapi

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.jar.JarFile

import scala.jdk.CollectionConverters.*

import org.scalatest.funsuite.AnyFunSuite

import scalascript.control.{Effect, EffectId}

/** Black-box JVM ABI gates for the compiler-independent control API leaf. */
final class ControlApiAbiTest extends AnyFunSuite:
  private val controlPackagePath = "scalascript/control/"

  private lazy val productionLocation: Path =
    val source = classOf[Effect].getProtectionDomain.getCodeSource
    assert(source != null, "control API classes have no code-source location")
    Paths.get(source.getLocation.toURI)

  private lazy val productionClasses: Vector[String] =
    val entries =
      if Files.isDirectory(productionLocation) then
        val files = Files.walk(productionLocation)
        try
          files.iterator.asScala
            .filter(Files.isRegularFile(_))
            .map(productionLocation.relativize)
            .map(_.toString.replace(File.separatorChar, '/'))
            .toVector
        finally files.close()
      else
        val jar = new JarFile(productionLocation.toFile)
        try jar.entries.asScala.filterNot(_.isDirectory).map(_.getName).toVector
        finally jar.close()

    entries
      .filter(path => path.startsWith(controlPackagePath) && path.endsWith(".class"))
      .map(_.stripSuffix(".class").replace('/', '.'))
      .distinct
      .sorted

  private lazy val javapExecutable: String =
    val executable =
      if System.getProperty("os.name", "").toLowerCase.contains("win") then
        "javap.exe"
      else "javap"
    val inJavaHome = Paths.get(System.getProperty("java.home"), "bin", executable)
    assert(
      Files.isRegularFile(inJavaHome),
      s"JDK javap is required for the control API ABI gate: $inJavaHome"
    )
    inJavaHome.toString

  private lazy val javapClasspath: String =
    val current = Option(System.getProperty("java.class.path")).toVector
      .filter(_.nonEmpty)
    (productionLocation.toString +: current).mkString(File.pathSeparator)

  private def runJavap(className: String): String =
    val process = new ProcessBuilder(
      javapExecutable,
      "-classpath",
      javapClasspath,
      "-public",
      className
    ).redirectErrorStream(true).start()
    val output =
      try new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      finally process.getInputStream.close()
    val exitCode = process.waitFor()
    assert(
      exitCode == 0,
      s"javap failed for $className with exit code $exitCode:\n$output"
    )
    output

  private lazy val publicApi: Map[String, String] =
    productionClasses.iterator.map(name => name -> runJavap(name)).toMap

  private def publicMemberLines(className: String): Vector[String] =
    publicApi(className).linesIterator
      .map(_.trim)
      .filter(_.startsWith("public "))
      .filterNot(line =>
        line.contains(" class ") ||
          line.contains(" interface ") ||
          line.contains(" enum ")
      )
      .toVector

  private def hasPublicMethod(className: String, methodName: String): Boolean =
    publicMemberLines(className).exists { line =>
      val open = line.indexOf('(')
      open >= 0 && line.substring(0, open).trim.endsWith(s" $methodName")
    }

  private def constructorLines(className: String): Vector[String] =
    publicMemberLines(className).filter(_.startsWith(s"public $className("))

  private val guardedConstructors = Vector(
    "scalascript.control.EffectKey" ->
      "scalascript.control.EffectKey$Authority",
    "scalascript.control.Continuation" ->
      "scalascript.control.Continuation$Authority",
    "scalascript.control.Continuation$Local" ->
      "scalascript.control.Continuation$Authority",
    "scalascript.control.Continuation$Runtime" ->
      "scalascript.control.Continuation$Authority",
    "scalascript.control.Continuation$Savable" ->
      "scalascript.control.Continuation$Authority",
    "scalascript.control.SavedContinuation$Reusable" ->
      "scalascript.control.SavedContinuation$Authority",
    "scalascript.control.OneShotContinuation" ->
      "scalascript.control.OneShotContinuation$Authority",
    "scalascript.control.OneShotContinuation$Delegated" ->
      "scalascript.control.OneShotContinuation$Authority",
    "scalascript.control.OneShotContinuation$Runtime" ->
      "scalascript.control.OneShotContinuation$Authority",
    "scalascript.control.Prompt" ->
      "scalascript.control.Prompt$Authority",
    "scalascript.control.Prompt$Scoped" ->
      "scalascript.control.Prompt$Authority",
    "scalascript.control.Eff$Pending" ->
      "scalascript.control.Eff$Authority"
  )

  test("javap audits the complete compiled control API without project runtime dependencies") {
    val requiredInventory = Set(
      "scalascript.control.Effect",
      "scalascript.control.Eff",
      "scalascript.control.Operation",
      "scalascript.control.Handler",
      "scalascript.control.Continuation",
      "scalascript.control.OneShotContinuation",
      "scalascript.control.SavedContinuation",
      "scalascript.control.Prompt"
    )
    assert(productionClasses.nonEmpty, "no compiled control API classes discovered")
    assert(
      requiredInventory.subsetOf(productionClasses.toSet),
      s"incomplete control API inventory: missing ${requiredInventory -- productionClasses.toSet}"
    )

    val completePublicInventory = publicApi.toVector
      .sortBy(_._1)
      .map { case (name, api) => s"=== $name ===\n$api" }
      .mkString("\n")
    // Scala enums necessarily implement this compiler marker; it is not a
    // reflection handle or a runtime reflection dependency.
    val auditedInventory =
      completePublicInventory.replace("scala.reflect.Enum", "")
    val forbiddenReferences = Vector(
      "scalascript.backend.",
      "scalascript.compiler.",
      "scalascript.frontend.",
      "scalascript.interop.",
      "scalascript.interpreter.",
      "scalascript.ir.",
      "scalascript.runtime.",
      "scalascript.uniml.",
      "java.lang.reflect.",
      "scala.reflect.",
      "java.lang.ThreadLocal",
      "DataV",
      "ClosV",
      "SpiValue"
    )

    val leaked = forbiddenReferences.filter(auditedInventory.contains)
    assert(
      leaked.isEmpty,
      s"control API public ABI leaks forbidden runtime references: ${leaked.mkString(", ")}"
    )
  }

  test("sensitive request and prompt helpers are absent from the public ABI") {
    assert(!hasPublicMethod("scalascript.control.Eff", "request"))
    assert(!hasPublicMethod("scalascript.control.Eff$", "request"))
    assert(!hasPublicMethod("scalascript.control.Eff$", "deepResumption"))
    assert(!hasPublicMethod("scalascript.control.Eff$", "handleRequest"))
    assert(!hasPublicMethod("scalascript.control.Prompt", "fresh"))
    assert(!hasPublicMethod("scalascript.control.Prompt$", "fresh"))
    assert(
      publicMemberLines("scalascript.control.Prompt")
        .forall(!_.contains("EffectKey")),
      "Prompt leaked its private effect key"
    )

    val controlKeyFactories =
      publicMemberLines("scalascript.control.Control$")
        .filter(_.contains(" freshKey("))
    assert(controlKeyFactories.nonEmpty, "guarded Control key factory is missing")
    assert(
      controlKeyFactories.forall(
        _.contains("scalascript.control.Prompt$Authority")
      ),
      s"unguarded Control key factory: ${controlKeyFactories.mkString("; ")}"
    )
  }

  test("every JVM-visible guarded constructor carries its authority token") {
    guardedConstructors.foreach { case (className, authority) =>
      val constructors = constructorLines(className)
      assert(constructors.nonEmpty, s"guarded constructor disappeared from $className")
      assert(
        constructors.forall(_.contains(authority)),
        s"unguarded public constructor in $className: ${constructors.mkString("; ")}"
      )
    }

    // The SavedContinuation base and its library-owned Reusable success plan must
    // both keep their constructors behind the reserved post-X1 authority so user
    // code cannot forge a successful saved value.
    constructorLines("scalascript.control.SavedContinuation").foreach { line =>
      assert(line.contains("scalascript.control.SavedContinuation$Authority"), line)
    }
    constructorLines("scalascript.control.SavedContinuation$Reusable").foreach { line =>
      assert(line.contains("scalascript.control.SavedContinuation$Authority"), line)
    }
  }

  test("continuation runtime and delegate factories require Eff authority") {
    val factoryOwners = Vector(
      "scalascript.control.Continuation",
      "scalascript.control.Continuation$",
      "scalascript.control.OneShotContinuation",
      "scalascript.control.OneShotContinuation$"
    )
    val factories = factoryOwners.flatMap { owner =>
      publicMemberLines(owner).filter { line =>
        val open = line.indexOf('(')
        open >= 0 && {
          val prefix = line.substring(0, open).trim
          prefix.endsWith(" runtime") || prefix.endsWith(" delegate")
        }
      }
    }

    assert(factories.exists(_.contains(" runtime(")), "runtime factory ABI is missing")
    assert(factories.exists(_.contains(" delegate(")), "delegate factory ABI is missing")
    assert(
      factories.forall(_.contains("scalascript.control.Eff$Authority")),
      s"continuation factory without Eff authority: ${factories.mkString("; ")}"
    )
  }

  test("the private authority singletons are never issued by a public member") {
    val authorityTypes = productionClasses.filter(_.endsWith("$Authority"))
    assert(authorityTypes.nonEmpty, "authority classes are missing from the ABI inventory")

    val issuers = publicApi.keys.toVector.sorted.flatMap { className =>
      publicMemberLines(className).filter { line =>
        val open = line.indexOf('(')
        val isConstructor =
          open >= 0 && line.substring(0, open).trim.endsWith(className)
        val resultOrField = if open >= 0 then line.substring(0, open) else line
        !isConstructor && authorityTypes.exists(resultOrField.contains)
      }.map(line => s"$className: $line")
    }
    val namedAuthorityMembers = publicApi.keys.toVector.sorted.flatMap { className =>
      publicMemberLines(className)
        .filter(line => line.matches(".*[ .]authority(?:\\(.*|;)$"))
        .map(line => s"$className: $line")
    }

    assert(issuers.isEmpty, s"public API issues an authority token: ${issuers.mkString("; ")}")
    assert(
      namedAuthorityMembers.isEmpty,
      s"private authority singleton escaped into the public ABI: ${namedAuthorityMembers.mkString("; ")}"
    )
  }

  test("null and freshly forged authority objects fail the identity gate") {
    val loader = classOf[Effect].getClassLoader
    val guardedOwners = Vector(
      "scalascript.control.EffectKey",
      "scalascript.control.Eff",
      "scalascript.control.Continuation",
      "scalascript.control.OneShotContinuation",
      "scalascript.control.SavedContinuation",
      "scalascript.control.Prompt"
    )

    guardedOwners.foreach { owner =>
      val authorityClass = loader.loadClass(s"$owner$$Authority")
      val companionClass = loader.loadClass(s"$owner$$")
      val module = companionClass.getField("MODULE$").get(null)
      val validator = companionClass.getMethod("requireAuthority", authorityClass)
      val forged = authorityClass.getDeclaredConstructor().newInstance()

      Vector(null, forged).foreach { candidate =>
        val rejected = intercept[InvocationTargetException] {
          validator.invoke(module, candidate)
        }
        assert(
          rejected.getCause.isInstanceOf[IllegalArgumentException],
          s"$owner accepted an invalid authority: ${rejected.getCause}"
        )
      }
    }

    val concreteGuarded = guardedConstructors.filterNot { case (owner, _) =>
      owner == "scalascript.control.Continuation" ||
      owner == "scalascript.control.OneShotContinuation"
    }
    concreteGuarded.foreach { case (owner, authorityName) =>
      val ownerClass = loader.loadClass(owner)
      val authorityClass = loader.loadClass(authorityName)
      val constructor = ownerClass.getConstructors.find(
        _.getParameterTypes.contains(authorityClass)
      ).getOrElse(fail(s"no guarded constructor found for $owner"))
      val authorityIndex =
        constructor.getParameterTypes.indexOf(authorityClass)
      val forged = authorityClass.getDeclaredConstructor().newInstance()

      Vector(null, forged).foreach { candidate =>
        val arguments = Array.fill[Object](constructor.getParameterCount)(null)
        arguments(authorityIndex) = candidate
        val rejected = intercept[InvocationTargetException] {
          constructor.newInstance(arguments*)
        }
        assert(
          rejected.getCause.isInstanceOf[IllegalArgumentException],
          s"$owner accepted an invalid authority: ${rejected.getCause}"
        )
      }
    }

    val promptAuthority = loader.loadClass("scalascript.control.Prompt$Authority")
    val promptConstructor =
      loader.loadClass("scalascript.control.Prompt").getConstructor(promptAuthority)
    val forgedPromptAuthority = promptAuthority.getDeclaredConstructor().newInstance()
    Vector(null, forgedPromptAuthority).foreach { candidate =>
      val rejected = intercept[InvocationTargetException] {
        promptConstructor.newInstance(candidate)
      }
      assert(rejected.getCause.isInstanceOf[IllegalArgumentException])
    }

    val effectKeyCompanionClass =
      loader.loadClass("scalascript.control.EffectKey$")
    val effectKeyModule = effectKeyCompanionClass.getField("MODULE$").get(null)
    val named = effectKeyCompanionClass.getMethod(
      "named",
      classOf[EffectId],
      classOf[Object]
    )
    val nullOwner = intercept[InvocationTargetException] {
      named.invoke(effectKeyModule, EffectId("test.null-owner"), null)
    }
    assert(nullOwner.getCause.isInstanceOf[NullPointerException])
  }
