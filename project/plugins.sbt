addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.0")

// JMH microbenchmarks (runtime/backend/interpreter-bench).
// Run: sbt "interpreterBench/Jmh/run"
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7")
addSbtPlugin("com.github.sbt" % "sbt-proguard" % "0.5.0")

// GraalVM native-image build (v1.50-native-p2).  Run:
//   sbt cli/graalvm-native-image:packageBin
// Output: tools/cli/target/graalvm-native-image/ssc
// GraalVMNativeImagePlugin is part of sbt-native-packager.
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.4")

// Scala.js cross-compile (docs/wallet-spi-scalajs.md). Stage 1 cross-
// compiles crypto-spi / blockchain-spi / wallet-spi; later stages add
// strategy + connector + vault modules.
//
// Pin to Scala.js 1.20.x — Scala 3.8.3 ships with a `scala3-library_sjs1`
// artefact built against 1.20.x, and the `scalajs-javalib` 1.20.2 jar
// uses `js.async` which only exists from 1.20+. Pinning sbt-scalajs to
// 1.16 leaves the codegen with mismatched stdlib references and crashes
// genSJSIR (see "package scala.scalajs.js does not have a member method
// async").
addSbtPlugin("org.scala-js"       % "sbt-scalajs"                 % "1.20.2")
addSbtPlugin("org.portable-scala" % "sbt-crossproject"            % "1.3.2")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject"    % "1.3.2")
