import javax.tools.*;
import java.util.*;
public class JcProbe {
  public static void main(String[] a) throws Exception {
    JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
    System.out.println("getSystemJavaCompiler() -> " + jc);
    if (jc == null) { System.out.println("ABSENT: a JRE-only image has no jdk.compiler"); return; }
    java.nio.file.Path d = java.nio.file.Files.createTempDirectory("jc");
    java.nio.file.Path f = d.resolve("Gen.java");
    java.nio.file.Files.writeString(f, "public class Gen { public static void main(String[] x){ int n=0; for(int i=0;i<3;i++) n+=i; System.out.println(\"in-process javac produced this: \"+n); } }");
    StandardJavaFileManager fm = jc.getStandardFileManager(null, null, null);
    boolean ok = jc.getTask(null, fm, null, Arrays.asList("-d", d.toString()), null,
        fm.getJavaFileObjects(f.toFile())).call();
    System.out.println("compile ok = " + ok);
    ClassLoader cl = new java.net.URLClassLoader(new java.net.URL[]{ d.toUri().toURL() });
    cl.loadClass("Gen").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
  }
}
