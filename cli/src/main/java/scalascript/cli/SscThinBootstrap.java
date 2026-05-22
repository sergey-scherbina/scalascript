package scalascript.cli;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/**
 * Entry point for thin JARs produced by {@code ssc build --target ssc}.
 *
 * <p>The thin JAR contains only this class and the embedded {@code .ssc} source.
 * This bootstrap locates the ssc installation root, sets {@code ssc.lib.path} so
 * that {@code ssc.main()} can discover plugins, then loads the ssc runtime via
 * URLClassLoader and delegates to {@code scalascript.cli.SscThinLauncher}.
 *
 * <p>Because the parent of the URLClassLoader is the system classloader (which
 * already sees the thin JAR), {@code SscThinLauncher} can read
 * {@code META-INF/ssc/main.ssc} through the parent classloader chain.
 *
 * <p>Install root lookup order (first {@code <root>/bin/lib/jars/} that exists wins):
 * <ol>
 *   <li>{@code ssc.lib.path} system property  (set by {@code bin/ssc} dev launcher)
 *   <li>{@code $SSC_HOME}
 *   <li>{@code ~/.local/lib/ssc}              (default {@code ssc install} target)
 *   <li>current working directory             (running from the ssc project root)
 * </ol>
 */
public class SscThinBootstrap {

    public static void main(String[] argv) throws Exception {
        URL[] urls = findSscLib();
        if (urls.length == 0) {
            System.err.println("ssc: cannot locate ssc runtime.");
            System.err.println("  Set SSC_HOME to the ssc install root, run 'ssc install',");
            System.err.println("  or run from the ssc project directory.");
            System.exit(1);
            return;
        }

        // Try .sscc (pre-compiled AST) first, fall back to .ssc (source)
        InputStream stream = SscThinBootstrap.class.getClassLoader()
                .getResourceAsStream("META-INF/ssc/main.sscc");
        String suffix;
        if (stream != null) {
            suffix = ".sscc";
        } else {
            stream = SscThinBootstrap.class.getClassLoader()
                    .getResourceAsStream("META-INF/ssc/main.ssc");
            suffix = ".ssc";
        }
        if (stream == null) {
            System.err.println("ssc: no embedded main.sscc or main.ssc found (corrupt thin JAR?)");
            System.exit(1);
            return;
        }
        Path tmp = Files.createTempFile("ssc-jar-", suffix);
        tmp.toFile().deleteOnExit();
        Files.write(tmp, stream.readAllBytes());
        stream.close();

        String[] allArgs = new String[argv.length + 1];
        allArgs[0] = tmp.toString();
        System.arraycopy(argv, 0, allArgs, 1, argv.length);

        // Call ssc.main() — runs the full startup (plugin loading via ssc.lib.path, etc.)
        // Parent = system classloader keeps META-INF/ssc/main.ssc visible if needed later.
        URLClassLoader cl = new URLClassLoader(urls, SscThinBootstrap.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(cl);
        cl.loadClass("scalascript.cli.ssc")
          .getMethod("main", String[].class)
          .invoke(null, (Object) allArgs);
    }

    /**
     * Finds the ssc install root, sets {@code ssc.lib.path} if not already set,
     * and returns all JAR URLs needed at runtime.
     *
     * <p>The expected directory layout under each candidate root is:
     * <pre>
     *   &lt;root&gt;/bin/lib/
     *     ssc.jar
     *     jars/          ← runtime dependencies
     *     compiler/
     *       jars/        ← Scala compiler (needed for --target jvm)
     *       plugins/     ← *.sscpkg   loaded by ssc startup via ssc.lib.path
     * </pre>
     */
    private static URL[] findSscLib() throws Exception {
        String home    = System.getProperty("user.home");
        String sscHome = System.getenv("SSC_HOME");
        String libProp = System.getProperty("ssc.lib.path");
        String cwd     = System.getProperty("user.dir");

        List<String> roots = new ArrayList<>();
        if (libProp != null) roots.add(libProp);
        if (sscHome != null) roots.add(sscHome);
        if (home    != null) roots.add(home + "/.local/lib/ssc");
        if (cwd     != null) roots.add(cwd);

        for (String root : roots) {
            File libDir  = Paths.get(root, "bin", "lib").toFile();
            File jarsDir = new File(libDir, "jars");
            if (!jarsDir.isDirectory()) continue;

            List<URL> urls = collectJars(libDir, jarsDir);
            if (urls.isEmpty()) continue;

            // Set ssc.lib.path so ssc startup code can find compiler/plugins/*.sscpkg
            if (System.getProperty("ssc.lib.path") == null)
                System.setProperty("ssc.lib.path", new File(root).getAbsolutePath());

            return urls.toArray(new URL[0]);
        }
        return new URL[0];
    }

    private static List<URL> collectJars(File libDir, File jarsDir) throws Exception {
        List<URL> urls = new ArrayList<>();
        // ssc.jar at the lib root
        for (File f : orEmpty(libDir.listFiles(fi -> fi.getName().endsWith(".jar"))))
            urls.add(f.toURI().toURL());
        // runtime dependency jars
        for (File f : orEmpty(jarsDir.listFiles(fi -> fi.getName().endsWith(".jar"))))
            urls.add(f.toURI().toURL());
        // Scala compiler jars (for --target jvm)
        File compilerJars = new File(libDir, "compiler/jars");
        if (compilerJars.isDirectory())
            for (File f : orEmpty(compilerJars.listFiles(fi -> fi.getName().endsWith(".jar"))))
                urls.add(f.toURI().toURL());
        return urls;
    }

    private static File[] orEmpty(File[] arr) { return arr != null ? arr : new File[0]; }
}
