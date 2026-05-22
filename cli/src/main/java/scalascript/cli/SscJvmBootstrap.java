package scalascript.cli;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * Entry point for thin JARs produced by {@code ssc build --target jvm}.
 *
 * <p>The thin JAR contains:
 * <ul>
 *   <li>This bootstrap class (pure Java, no Scala runtime dep at startup).</li>
 *   <li>The compiled app bytecode (from JvmGen → scala-cli {@code --library}).</li>
 *   <li>{@code META-INF/MANIFEST.MF} with {@code Main-Class} and {@code Ssc-Main-Class}.</li>
 * </ul>
 *
 * <p>At runtime this bootstrap locates the ssc lib, adds both lib/ and this
 * JAR itself to a URLClassLoader, reads {@code Ssc-Main-Class} from the
 * manifest, and delegates.
 *
 * <p>Lib lookup order (same as {@link SscThinBootstrap}):
 * {@code ssc.lib.path} → {@code $SSC_HOME} → {@code ~/.local/lib/ssc} → cwd.
 */
public class SscJvmBootstrap {

    public static void main(String[] argv) throws Exception {
        // Locate this JAR on disk — its compiled app classes are inside
        File thisJar = new File(SscJvmBootstrap.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());

        // Read Ssc-Main-Class from this JAR's manifest
        String mainClass;
        try (JarFile jf = new JarFile(thisJar)) {
            mainClass = jf.getManifest().getMainAttributes().getValue("Ssc-Main-Class");
        }
        if (mainClass == null) {
            System.err.println("ssc: corrupt JAR — no Ssc-Main-Class in manifest");
            System.exit(1);
            return;
        }

        // Find Scala + ssc runtime from lib/
        URL[] libUrls = findSscLib();
        if (libUrls.length == 0) {
            System.err.println("ssc: cannot locate scala runtime.");
            System.err.println("  Set SSC_HOME, run 'ssc install', or run from the ssc project directory.");
            System.exit(1);
            return;
        }

        // URLClassLoader: lib/ + this JAR (compiled app bytecode lives here)
        List<URL> allUrls = new ArrayList<>(Arrays.asList(libUrls));
        allUrls.add(thisJar.toURI().toURL());

        URLClassLoader cl = new URLClassLoader(allUrls.toArray(new URL[0]),
                                               ClassLoader.getPlatformClassLoader());
        Thread.currentThread().setContextClassLoader(cl);
        cl.loadClass(mainClass)
          .getMethod("main", String[].class)
          .invoke(null, (Object) argv);
    }

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

            List<URL> urls = new ArrayList<>();
            for (File f : orEmpty(libDir.listFiles(fi -> fi.getName().endsWith(".jar"))))
                urls.add(f.toURI().toURL());
            for (File f : orEmpty(jarsDir.listFiles(fi -> fi.getName().endsWith(".jar"))))
                urls.add(f.toURI().toURL());
            if (!urls.isEmpty()) return urls.toArray(new URL[0]);
        }
        return new URL[0];
    }

    private static File[] orEmpty(File[] arr) { return arr != null ? arr : new File[0]; }
}
