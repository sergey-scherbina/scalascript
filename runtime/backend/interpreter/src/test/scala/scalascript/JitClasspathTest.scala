package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.vm.jit.JavacJitBackend
import javax.tools.{ToolProvider, JavaFileObject, SimpleJavaFileObject, ForwardingJavaFileManager, JavaFileManager, FileObject}
import java.net.URI
import java.io.{ByteArrayOutputStream, OutputStream}

/** jit-runmain-classpath — the runtime `javac` JIT now passes the running
 *  classloader's URLs (+ `java.class.path`) as `-classpath`, so the generated
 *  Java resolves the runtime classes it references and the JIT actually compiles
 *  (under sbt the compiler's default classpath is `.`, so with `null` options it
 *  bailed to tree-walk — leaving the codegen untested). These tests compile a
 *  tiny Java class that references a runtime type to prove the classpath works. */
class JitClasspathTest extends AnyFunSuite:

  /** Compile a Java class referencing `scalascript.interpreter.Value`; true iff
   *  javac resolved it. Class output is discarded (no cwd pollution). */
  private def canResolveRuntime(opts: java.util.List[String] | Null): Boolean =
    val compiler = ToolProvider.getSystemJavaCompiler
    if compiler == null then return true
    val src = "public class _SscCpProbe { public static Object f() { return scalascript.interpreter.Value.class; } }"
    val javaFile = new SimpleJavaFileObject(URI.create("string:///_SscCpProbe.java"), JavaFileObject.Kind.SOURCE):
      override def getCharContent(ignoreEncodingErrors: Boolean): CharSequence = src
    val sink = new ByteArrayOutputStream()
    val standard = compiler.getStandardFileManager(null, null, null)
    val fm = new ForwardingJavaFileManager[JavaFileManager](standard):
      override def getJavaFileForOutput(loc: JavaFileManager.Location, name: String, kind: JavaFileObject.Kind, sib: FileObject) =
        new SimpleJavaFileObject(URI.create(s"mem:///$name.class"), kind):
          override def openOutputStream(): OutputStream = sink
    val task = compiler.getTask(null, fm, null, opts, null, java.util.Arrays.asList(javaFile))
    try task.call().booleanValue() catch case _: Throwable => false

  test("jitClasspathOptions harvests the classpath and resolves runtime classes"):
    assume(ToolProvider.getSystemJavaCompiler != null, "no system java compiler")
    assert(!JavacJitBackend.jitClasspathOptions.isEmpty,
      "jitClasspathOptions should harvest classpath entries (classloader URLs / java.class.path)")
    assert(canResolveRuntime(JavacJitBackend.jitClasspathOptions),
      "javac must resolve scalascript.interpreter.Value with jitClasspathOptions — the JIT compiles regardless of launch mode")
    // NOTE: under sbt's *forked* tests `java.class.path` already carries the runtime
    // classes, so `null` options resolve too here; the fix is load-bearing for the
    // unforked `runMain` / layered-classloader case where it does not. Passing the
    // harvested classpath makes the JIT compile in every launch mode, harmlessly.
