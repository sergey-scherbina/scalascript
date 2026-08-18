// The plugin is published to the project's static Maven tree, not to Maven Central: `io.scalascript`
// cannot be claimed there without owning `scalascript.io`, which does not resolve. Without this
// resolver line `sbt compile` in a fresh scaffold answers "Error downloading
// org.scalascript:sbt-scalascript-interop … Not found" for everyone who is not a contributor with a
// publishLocal behind them. (BUGS.md scaffolded-project-cannot-resolve-its-sbt-plugin.)
resolvers += "scalascript" at "https://sergey-scherbina.github.io/scalascript/maven"

addSbtPlugin("org.scalascript" % "sbt-scalascript-interop" % "0.2.0")
