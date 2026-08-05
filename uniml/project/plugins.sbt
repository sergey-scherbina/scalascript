// Scala.js cross-compile — versions pinned to match the root ScalaScript build.
addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % "1.20.2")
addSbtPlugin("org.portable-scala" % "sbt-crossproject"         % "1.3.2")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")

// JMH — the SSC3-M measurement arm. Settings mirror v1's `ParserBench` exactly (3 warmup x 5
// measurement x 1 fork, average time) so the two are comparable; see specs/uniml-ssc3-frontend.md
// §4.2b for why they cannot share a build.
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7")
