package scalascript.sbt

import sbt._
import sbt.Keys._
import complete.DefaultParsers._
import sbt.plugins.JvmPlugin

/** sbt plugin for ScalaScript v2.0 interop (Tier 3).
 *
 *  Wires `FacadeGenerator` source generation into the standard sbt
 *  `Compile / sourceGenerators` lifecycle.  Plugin authors must opt in
 *  explicitly — no auto-trigger.
 *
 *  Minimal usage:
 *  {{{
 *  // project/plugins.sbt
 *  addSbtPlugin("org.scalascript" % "sbt-scalascript-interop" % "0.1.0")
 *
 *  // build.sbt
 *  enablePlugins(ScalascriptInteropPlugin)
 *  sscArtifactDir := baseDirectory.value / ".ssc-artifacts"
 *  }}}
 *
 *  The plugin forks `ssc generate-facade <artifactDir> -o <managedSrc>` to
 *  produce Scala 3 source files in `target/scala-<v>/src_managed/main/ssc-facade/`.
 *  No compile-time dependency on the ScalaScript runtime — facade sources
 *  only contain `export` aliases that are resolved by the Scala 3 compiler.
 *
 *  Settings:
 *  - `sscArtifactDir`  — directory containing `.scim` artifacts (required).
 *  - `sscLinkedJar`    — linked ScalaScript runnable JAR output.
 *  - `sscTestResultsDir` — directory for `ssc test` JUnit XML output.
 *  - `sscBinary`       — path to the `ssc` binary (default: "ssc" on PATH).
 *  - `sscSourceDirectories` — source directories containing `.ssc` files.
 *  - `sscBackend`      — backend passed to `ssc build --incremental`.
 *  - `sscBackends`     — cross-build target backends; `sscCompile` builds each
 *    in one `compile` (single = flat dir, multiple = per-backend subdirs).
 *  - `sscManagedDependencies` — Maven `dep:` coordinates lifted from `.ssc`
 *    front-matter `dependencies:` and added to `libraryDependencies` (Phase 5).
 *
 *  Tasks:
 *  - `sscCompile` — compile `.ssc` sources via `ssc build --incremental`.
 *  - `sscLink` — link `.ssc` artifacts via `ssc link`.
 *  - `sscTest` — run `.ssc` tests via `ssc test`.
 *  - `sscRepl`, `sscRun`, `sscWatch`, `sscBspSetup` — developer tooling.
 *  - `sscGenerateFacade` — generate facade sources (hooked into sourceGenerators).
 */
object ScalascriptInteropPlugin extends AutoPlugin {

  object autoImport {
    val sscSourceDirectories = settingKey[Seq[File]](
      "ScalaScript source directories."
    )
    val sscArtifactDir = settingKey[File](
      "Directory containing ScalaScript .scim artifacts for facade generation."
    )
    val sscLinkedJar = settingKey[File](
      "Runnable JAR produced by `ssc link`."
    )
    val sscTestResultsDir = settingKey[File](
      "Directory where `ssc test --output-format junit-xml` writes results."
    )
    val sscBinary = settingKey[String](
      "Path to the ssc binary used by sbt-scalascript tasks (default: 'ssc')."
    )
    val sscBackend = settingKey[String](
      "ScalaScript backend passed to `ssc build --incremental`."
    )
    val sscBackends = settingKey[Seq[String]](
      "Cross-build target backends. `sscCompile` builds each in one `compile`; a " +
      "single backend (the default, Seq(sscBackend)) writes to the flat sscArtifactDir, " +
      "multiple write to per-backend subdirectories sscArtifactDir/<backend>/."
    )
    val sscExtraArgs = settingKey[Seq[String]](
      "Extra arguments appended to `ssc build --incremental`."
    )
    val sscCompile = taskKey[Seq[File]](
      "Compile .ssc sources via `ssc build --incremental`."
    )
    val sscLink = taskKey[File](
      "Link .ssc artifacts into a runnable JAR via `ssc link`."
    )
    val sscTest = taskKey[TestResult](
      "Run .ssc tests via `ssc test --output-format junit-xml`."
    )
    val sscRepl = taskKey[Unit](
      "Start the ScalaScript REPL."
    )
    val sscRun = inputKey[Unit](
      "Run a ScalaScript file through `ssc run`."
    )
    val sscWatch = taskKey[Unit](
      "Start ScalaScript watch mode for this project."
    )
    val sscBspSetup = taskKey[File](
      "Emit .bsp/scalascript.json for editor integration."
    )
    val sscGenerateFacade = taskKey[Seq[File]](
      "Generate Scala 3 facade sources from .scim artifacts via `ssc generate-facade`."
    )
    /** Classifier for the linked ScalaScript jar in `publishLocal` / `publish`.
      *
      *  A classifier rather than the main artifact: the plain jar carries the Scala facades that
      *  `sscGenerateFacade` generates and sbt compiles, so the two are different things. */
    val sscDistDir = settingKey[File](
      "Where distribution outputs go (executable JAR, native binary). Defaults to target/ssc-dist."
    )
    val sscMainSource = settingKey[Option[File]](
      "The single .ssc entry point for distributions that need exactly one. `ssc build-rust` takes " +
      "ONE file, unlike `build-jvm` which takes many, so this is required for sscBuildRust and " +
      "ignored by sscBuildJvm. Left as None the task fails with the list of candidates rather " +
      "than picking one."
    )
    val sscBuildJvm = taskKey[File](
      "Build a compiler-free executable JAR via `ssc build-jvm` into sscDistDir."
    )
    val sscBundle = taskKey[File](
      "Package .ssc sources into an .sscpkg via `ssc bundle`, into sscDistDir."
    )
    val sscBuildTarget = inputKey[File](
      "Build a platform bundle: `sscBuildTarget desktop|ios|macos <file.ssc>` via `ssc build " +
      "--target`, into sscDistDir/<target>/."
    )
    val sscEmitSpa = taskKey[Seq[File]](
      "Render each .ssc source to a standalone SPA HTML file in sscDistDir via `ssc emit-spa`."
    )
    val sscEmitLib = taskKey[File](
      "Emit a host-native library via `ssc emit-lib`, into sscDistDir."
    )
    val sscBuildRust = taskKey[File](
      "Build a native binary via `ssc build-rust` (rust backend + cargo) into sscDistDir."
    )

    val SscClassifier = "ssc"

    val sscManagedDependencies = settingKey[Seq[ModuleID]](
      "Maven deps lifted from .ssc front-matter `dependencies:` (dep: coordinates) " +
      "and added to libraryDependencies so Coursier resolves them onto the classpath."
    )
  }

  import autoImport._

  override def requires: Plugins = JvmPlugin

  // Opt-in: users call `enablePlugins(ScalascriptInteropPlugin)`.
  override def trigger: PluginTrigger = noTrigger

  /** The classified artifact descriptor for the linked ScalaScript jar. */
  private def sscArtifactFor(module: String): Artifact =
    Artifact(module, "jar", "jar", Some(autoImport.SscClassifier), Vector.empty, None)

  override def projectSettings: Seq[Setting[_]] = Seq(
    sscBinary := "ssc",
    sscBackend := "jvm",
    sscBackends := Seq(sscBackend.value),
    sscExtraArgs := Seq.empty,
    Compile / sscSourceDirectories := Seq((Compile / sourceDirectory).value / "scalascript"),
    Test / sscSourceDirectories := Seq((Test / sourceDirectory).value / "scalascript"),
    Compile / sscArtifactDir := (Compile / target).value / "ssc-artifacts",
    Compile / sscLinkedJar := (Compile / sscArtifactDir).value / "linked.jar",
    Compile / sscDistDir := (Compile / target).value / "ssc-dist",
    Compile / sscMainSource := None,

    // Distributions. These are NOT `build --backend` under another name -- checked against
    // `ssc --help`: build-jvm produces a compiler-free executable JAR and build-rust a native
    // binary via cargo, neither of which the cross-build path can make. That is why the plugin's
    // existing multi-backend support did not already cover them.
    Compile / sscBuildJvm := {
      val sources = (Compile / sscSourceDirectories).value
        .filter(_.isDirectory).flatMap(dir => (dir ** "*.ssc").get())
      val out = (Compile / sscDistDir).value / (moduleName.value + ".jar")
      val log = streams.value.log
      if (sources.isEmpty) sys.error("sscBuildJvm: no .ssc sources under " +
        (Compile / sscSourceDirectories).value.mkString(", "))
      IO.createDirectory(out.getParentFile)
      SscRunner.run(
        binary = sscBinary.value,
        args = Seq("build-jvm", "-o", out.getAbsolutePath) ++
          sources.map(_.getAbsolutePath) ++ sscExtraArgs.value,
        log = log
      )
      out
    },

    // `ssc build-rust` takes EXACTLY ONE .ssc file, unlike build-jvm. Rather than pick a file for
    // the user -- silently building the wrong entry point is worse than not building -- an ambiguous
    // project is told what to set and which candidates it has.
    // `ssc bundle <file.ssc>... [-o name.sscpkg]` — many sources, one package, like build-jvm.
    // `ssc emit-spa` PRINTS the HTML and writes nothing — its parser takes files, --frontend and
    // --server-url, and there is no output flag. So the task captures stdout and names the file
    // itself, which is what a build tool is for. One html per source, because emit-spa renders each
    // file it is given and a single concatenated blob would be nonsense.
    // `ssc build --target <t> --out <dir>` — note --out, not -o, and it defaults to target/build
    // when omitted. An input task rather than a setting per platform: the target is a choice made at
    // the command line ("build me the ios one"), not a property of the project.
    Compile / sscBuildTarget := {
      import complete.DefaultParsers._
      val parsed = spaceDelimited("<target> <file.ssc>").parsed
      if (parsed.lengthCompare(2) != 0)
        sys.error("Usage: sscBuildTarget <desktop|ios|macos> <file.ssc>")
      val Seq(target, file) = parsed.take(2)
      val src = new File(file)
      val entry = if (src.isAbsolute) src else (baseDirectory.value / file)
      if (!entry.exists()) sys.error(s"sscBuildTarget: no such file: $entry")
      val out = (Compile / sscDistDir).value / target
      IO.createDirectory(out)
      SscRunner.run(
        binary = sscBinary.value,
        args = Seq("build", "--target", target, "--out", out.getAbsolutePath, entry.getAbsolutePath) ++
          sscExtraArgs.value,
        log = streams.value.log
      )
      out
    },

    Compile / sscEmitSpa := {
      val sources = (Compile / sscSourceDirectories).value
        .filter(_.isDirectory).flatMap(dir => (dir ** "*.ssc").get())
      if (sources.isEmpty) sys.error("sscEmitSpa: no .ssc sources found")
      val outDir = (Compile / sscDistDir).value / "spa"
      IO.createDirectory(outDir)
      val binary = sscBinary.value
      val extraArgs = sscExtraArgs.value
      val log = streams.value.log
      sources.map { src =>
        val html = SscRunner.runCapture(binary, Seq("emit-spa", src.getAbsolutePath) ++ extraArgs, log)
        val out = outDir / (src.getName.stripSuffix(".ssc") + ".html")
        IO.write(out, html)
        out
      }
    },

    Compile / sscBundle := {
      val sources = (Compile / sscSourceDirectories).value
        .filter(_.isDirectory).flatMap(dir => (dir ** "*.ssc").get())
      if (sources.isEmpty) sys.error("sscBundle: no .ssc sources found")
      val out = (Compile / sscDistDir).value / (moduleName.value + ".sscpkg")
      IO.createDirectory(out.getParentFile)
      SscRunner.run(
        binary = sscBinary.value,
        args = Seq("bundle") ++ sources.map(_.getAbsolutePath) ++
          Seq("-o", out.getAbsolutePath) ++ sscExtraArgs.value,
        log = streams.value.log
      )
      out
    },

    // `ssc emit-lib ... -o <dir> --version <semver>` — a DIRECTORY, not a file, and it carries a
    // version, so the project's own `version` is passed rather than letting the CLI default to
    // 0.1.0 and quietly disagreeing with what sbt publishes.
    Compile / sscEmitLib := {
      val sources = (Compile / sscSourceDirectories).value
        .filter(_.isDirectory).flatMap(dir => (dir ** "*.ssc").get())
      if (sources.isEmpty) sys.error("sscEmitLib: no .ssc sources found")
      val out = (Compile / sscDistDir).value / (moduleName.value + "-lib")
      IO.createDirectory(out.getParentFile)
      SscRunner.run(
        binary = sscBinary.value,
        args = Seq("emit-lib") ++ sources.map(_.getAbsolutePath) ++
          Seq("-o", out.getAbsolutePath, "--version", version.value) ++ sscExtraArgs.value,
        log = streams.value.log
      )
      out
    },

    Compile / sscBuildRust := {
      val sources = (Compile / sscSourceDirectories).value
        .filter(_.isDirectory).flatMap(dir => (dir ** "*.ssc").get())
      val chosen = (Compile / sscMainSource).value.orElse {
        // lengthCompare, not sizeCompare: sbt plugins compile on Scala 2.12, where the latter
        // does not exist. The file already uses lengthCompare a few lines up for the same reason.
        if (sources.lengthCompare(1) == 0) sources.headOption else None
      }
      val entry = chosen.getOrElse(sys.error(
        if (sources.isEmpty) "sscBuildRust: no .ssc sources found"
        else "sscBuildRust: `ssc build-rust` builds ONE entry point and this project has " +
             sources.size + ". Set `Compile / sscMainSource := Some(file(\"...\"))`. Candidates: " +
             sources.map(_.getName).sorted.mkString(", ")
      ))
      val out = (Compile / sscDistDir).value / moduleName.value
      IO.createDirectory(out.getParentFile)
      SscRunner.run(
        binary = sscBinary.value,
        args = Seq("build-rust", "-o", out.getAbsolutePath, entry.getAbsolutePath) ++ sscExtraArgs.value,
        log = streams.value.log
      )
      out
    },
    Test / sscTestResultsDir := (Test / target).value / "ssc-test-results",
    sscArtifactDir := (Compile / sscArtifactDir).value,
    sscLinkedJar := (Compile / sscLinkedJar).value,
    sscTestResultsDir := (Test / sscTestResultsDir).value,

    // Phase 5 — dep resolution: lift Maven `dep:` coordinates from the
    // front-matter `dependencies:` of Compile `.ssc` sources into
    // libraryDependencies so Coursier resolves them onto the classpath
    // (spec §3h). Evaluated at project-load; `reload` to pick up edits.
    sscManagedDependencies := SscFrontMatter.mavenDeps(
      (Compile / sscSourceDirectories).value
        .filter(_.isDirectory)
        .flatMap(dir => (dir ** "*.ssc").get())
    ),
    libraryDependencies ++= sscManagedDependencies.value,

    Compile / sscCompile := {
      val dirs = (Compile / sscSourceDirectories).value.filter(_.isDirectory)
      val sourceDirs = dirs.filter(dir => (dir ** "*.ssc").get().nonEmpty)
      val artifactDir = (Compile / sscArtifactDir).value
      val backends = sscBackends.value
      val log = streams.value.log
      // Hoisted: sbt's macro forbids `.value` inside the cached closure below.
      val binary = sscBinary.value
      val extraArgs = sscExtraArgs.value
      val cacheStore = streams.value.cacheDirectory / "ssc-compile"
      if (sourceDirs.isEmpty) {
        log.info("[ssc] no .ssc sources found")
        Seq.empty[File]
      } else {
        // Cross-build: one `compile` builds every backend in `sscBackends`. A
        // single backend writes to the flat artifactDir (backward-compatible);
        // multiple backends each write to artifactDir/<backend>/ so outputs
        // don't collide.
        // Wrapped in sbt's change tracking. Measured before this: two `sbt compile` runs with
        // nothing edited forked `ssc build` TWICE. ssc is incremental internally, but that happens
        // where sbt cannot see it — sbt knew neither the inputs nor the outputs, so it could not
        // skip the task nor invalidate what depends on it. A build tool that reruns the compiler on
        // every no-op command is the difference between used and merely tolerated.
        //
        // The backend list is part of the key, via a stamp file: change `sscBackends` and nothing
        // under src/ moves, so a source-only key would keep yesterday's targets and call it fresh.
        // The cache lives under streams' cacheDirectory, i.e. below target/, so `sbt clean` clears
        // it — which is what a user means by clean.
        // Written only when it CHANGES. Rewriting it unconditionally updates its mtime on every
        // invocation, so a lastModified-keyed cache misses every time — which is exactly what the
        // first attempt did, and the invocation-counting scenario said `saw 2` rather than letting
        // it look like it worked.
        // In the CACHE directory, not the artifact directory. Putting it beside the artifacts made
        // it one: sscCompile returns `(outDir ** "*").filter(_.isFile)`, so the stamp came back as
        // a compile output, fed sscLink, and would have reached anything published. Every mocked
        // scenario missed it because their mocks write nothing else there; the real-ssc scenario
        // showed it in the first artifact listing it printed.
        val backendStamp = cacheStore.getParentFile / "ssc-backends"
        IO.createDirectory(artifactDir)
        IO.createDirectory(backendStamp.getParentFile)
        val backendsText = backends.mkString("\n")
        val currentStamp = if (backendStamp.exists()) IO.read(backendStamp) else null
        if (currentStamp != backendsText) IO.write(backendStamp, backendsText)
        val inputs = sourceDirs.flatMap(dir => (dir ** "*.ssc").get()).toSet + backendStamp
        val build = FileFunction.cached(cacheStore, FilesInfo.lastModified, FilesInfo.exists) {
          (_: Set[File]) =>
            backends.flatMap { backend =>
              val outDir = if (backends.lengthCompare(1) <= 0) artifactDir else artifactDir / backend
              IO.createDirectory(outDir)
              sourceDirs.foreach { dir =>
                SscRunner.run(
                  binary = binary,
                  args = Seq(
                    "build",
                    "--incremental",
                    dir.getAbsolutePath,
                    "--artifact-dir",
                    outDir.getAbsolutePath,
                    "--backend",
                    backend
                  ) ++ extraArgs,
                  log = log
                )
              }
              (outDir ** "*").get().filter(_.isFile).toSeq
            }.distinct.toSet
        }
        build(inputs).toSeq
      }
    },

    Compile / sscLink := {
      val compileArtifacts = (Compile / sscCompile).value
      val artifactDir = (Compile / sscArtifactDir).value
      val linkedJar = (Compile / sscLinkedJar).value
      val log = streams.value.log
      if (!artifactDir.exists() || compileArtifacts.isEmpty) {
        log.info("[ssc] no .ssc artifacts to link")
      } else {
        IO.createDirectory(linkedJar.getParentFile)
        SscRunner.run(
          binary = sscBinary.value,
          args = Seq(
            "link",
            "--backend",
            sscBackend.value,
            "--output",
            linkedJar.getAbsolutePath,
            artifactDir.getAbsolutePath
          ) ++ sscExtraArgs.value,
          log = log
        )
      }
      linkedJar
    },

    sscGenerateFacade := {
      val artifactDir = sscArtifactDir.value
      val outDir      = (Compile / sourceManaged).value / "ssc-facade"
      val ssc         = sscBinary.value
      val log         = streams.value.log

      if (!artifactDir.exists()) {
        log.warn(s"[ssc] sscArtifactDir does not exist: $artifactDir — skipping facade generation.")
        Seq.empty[File]
      } else {
        IO.createDirectory(outDir)

        SscRunner.run(
          binary = ssc,
          args = Seq("generate-facade", artifactDir.getAbsolutePath, "-o", outDir.getAbsolutePath),
          log = log
        )

        (outDir ** "*.scala").get().toSeq
      }
    },

    Test / sscTest := {
      val dirs = (Test / sscSourceDirectories).value.filter(_.isDirectory)
      val testDirs = dirs.filter(dir => (dir ** "*.ssc").get().nonEmpty)
      val resultsDir = (Test / sscTestResultsDir).value
      val log = streams.value.log

      if (testDirs.isEmpty) {
        log.info("[ssc] no .ssc tests found")
        TestResult.Passed
      } else {
        IO.delete(resultsDir)
        IO.createDirectory(resultsDir)
        testDirs.foreach { dir =>
          SscRunner.run(
            binary = sscBinary.value,
            args = Seq(
              "test",
              dir.getAbsolutePath,
              "--backend",
              sscBackend.value,
              "--output-format",
              "junit-xml",
              "--output",
              resultsDir.getAbsolutePath
            ) ++ sscExtraArgs.value,
            log = log
          )
        }
        val result = SscTestFramework.parseJUnitXml(resultsDir, log)
        result match {
          case TestResult.Passed => result
          case TestResult.Failed => sys.error("ssc tests failed")
          case TestResult.Error  => sys.error("ssc tests errored")
        }
      }
    },

    sscRepl := {
      SscRunner.runInteractive(
        binary = sscBinary.value,
        args = Seq("repl", "--backend", sscBackend.value) ++ sscExtraArgs.value,
        log = streams.value.log
      )
    },

    sscRun := {
      val args = spaceDelimited("<ssc-run-args>").parsed
      SscRunner.runInteractive(
        binary = sscBinary.value,
        args = Seq("run", "--backend", sscBackend.value) ++ args ++ sscExtraArgs.value,
        log = streams.value.log
      )
    },

    sscWatch := {
      SscRunner.runInteractive(
        binary = sscBinary.value,
        args = Seq("watch", "--backend", sscBackend.value, baseDirectory.value.getAbsolutePath) ++ sscExtraArgs.value,
        log = streams.value.log
      )
    },

    sscBspSetup := {
      BspIntegration.write(
        baseDirectory = baseDirectory.value,
        binary = sscBinary.value,
        log = streams.value.log
      )
    },

    Compile / compile := ((Compile / compile) dependsOn (Compile / sscCompile)).value,

    // The linked JAR reaches sbt's artifacts, so `publishLocal` / `publish` carry it. Before this
    // `sscLink` produced a jar and handed it to nobody, so a ScalaScript library could not be
    // published from sbt at all.
    //
    // PROJECT scope, not `Compile /`: publishLocal reads packagedArtifacts at the project level,
    // and scoping them to Compile publishes nothing while saying nothing about it.
    //
    // ADDITIVE, with a classifier, rather than replacing packageBin: the plain jar holds the Scala
    // facades sscGenerateFacade emits and sbt compiles, so replacing it would publish a library
    // missing them.
    //
    // Registered ONLY when the jar exists. sscLink returns its output path even when it skipped the
    // work ("no .ssc artifacts to link"), so a project with no .ssc sources would otherwise claim
    // an artifact for a file nobody wrote and publishing would die on "Missing files for
    // publishing". Nothing consumed sscLink's result before, so that phantom had never mattered.
    packagedArtifacts ++= {
      val jar = (Compile / sscLink).value
      if (jar.exists()) Map(sscArtifactFor(moduleName.value) -> jar) else Map.empty[Artifact, File]
    },
    Compile / packageBin := ((Compile / packageBin) dependsOn (Compile / sscLink)).value,
    Test / test := ((Test / test) dependsOn (Test / sscTest)).value,
    Compile / sourceGenerators += sscGenerateFacade.taskValue
  )
}
