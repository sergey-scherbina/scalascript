package scalascript.cli;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/**
 * Entry point for thin JARs produced by {@code ssc build --target ssc}.
 *
 * <p>The thin JAR contains only this class and the embedded {@code .ssc} source.
 * This bootstrap locates the ssc lib directory, builds a URLClassLoader whose
 * <em>parent</em> is the system classloader (which already sees the thin JAR), then
 * loads {@code scalascript.cli.SscThinLauncher} from lib/ssc.jar and delegates.
 *
 * <p>Because the parent classloader has the thin JAR on its path,
 * {@code SscThinLauncher} can find {@code META-INF/ssc/main.ssc} via
 * {@code getClass.getClassLoader.getResourceAsStream(...)} without any special wiring.
 *
 * <p>Lib lookup order:
 * <ol>
 *   <li>{@code $SSC_HOME/lib/}
 *   <li>{@code ~/.local/lib/ssc/}
 *   <li>{@code <ssc.lib.path>/bin/lib/}  (set by {@code bin/ssc} dev launcher)
 *   <li>{@code ./bin/lib/}  relative to working directory
 *   <li>{@code lib/}  sibling directory next to the JAR
 * </ol>
 */
public class SscThinBootstrap {

    public static void main(String[] argv) throws Exception {
        URL[] urls = findSscLib();
        if (urls.length == 0) {
            System.err.println("ssc: cannot locate ssc runtime.");
            System.err.println("  Set SSC_HOME, run 'ssc install', or run from the ssc project directory.");
            System.exit(1);
            return;
        }

        // Parent = system classloader: keeps the thin JAR (and its resources) visible
        // to everything loaded from lib/, so SscThinLauncher can read META-INF/ssc/main.ssc.
        URLClassLoader cl = new URLClassLoader(urls, SscThinBootstrap.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(cl);
        cl.loadClass("scalascript.cli.SscThinLauncher")
          .getMethod("main", String[].class)
          .invoke(null, (Object) argv);
    }

    private static URL[] findSscLib() throws Exception {
        String home    = System.getProperty("user.home");
        String sscHome = System.getenv("SSC_HOME");
        String libProp = System.getProperty("ssc.lib.path");
        String cwd     = System.getProperty("user.dir");

        // Each candidate is the root lib/ directory (contains ssc.jar + jars/ subdir)
        List<File> candidates = new ArrayList<>();
        if (sscHome != null) candidates.add(Paths.get(sscHome,  "lib").toFile());
        candidates.add(Paths.get(home, ".local", "lib", "ssc").toFile());
        if (libProp != null) candidates.add(Paths.get(libProp,  "bin", "lib").toFile());
        if (cwd     != null) candidates.add(Paths.get(cwd,      "bin", "lib").toFile());
        candidates.add(siblingLib());

        for (File libDir : candidates) {
            if (libDir == null || !libDir.isDirectory()) continue;
            List<URL> urls = new ArrayList<>();
            // ssc.jar at lib root
            for (File f : orEmpty(libDir.listFiles(fi -> fi.getName().endsWith(".jar"))))
                urls.add(f.toURI().toURL());
            // dependency jars at lib/jars/
            File jarsDir = new File(libDir, "jars");
            if (jarsDir.isDirectory())
                for (File f : orEmpty(jarsDir.listFiles(fi -> fi.getName().endsWith(".jar"))))
                    urls.add(f.toURI().toURL());
            if (!urls.isEmpty()) return urls.toArray(new URL[0]);
        }
        return new URL[0];
    }

    private static File siblingLib() {
        try {
            java.security.CodeSource cs =
                SscThinBootstrap.class.getProtectionDomain().getCodeSource();
            if (cs == null) return null;
            return new File(cs.getLocation().toURI()).getParentFile().toPath()
                     .resolve("lib").toFile();
        } catch (Exception e) { return null; }
    }

    private static File[] orEmpty(File[] arr) { return arr != null ? arr : new File[0]; }
}
