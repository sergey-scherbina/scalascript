ThisBuild / scalaVersion := "3.8.3"
ThisBuild / organization := "io.scalascript"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val compiler = project
  .in(file("compiler"))
  .settings(
    name := "scalascript",
    libraryDependencies ++= Seq(
      "org.yaml"         %  "snakeyaml"  % "2.6",
      "com.lihaoyi"      %% "os-lib"     % "0.11.4",
      "com.lihaoyi"      %% "upickle"    % "4.1.0",
      "com.lihaoyi"      %% "pprint"     % "0.9.6",
      "org.scalameta"    %% "scalameta"  % "4.17.0",
      "org.commonmark"   %  "commonmark" % "0.28.0",
      "org.scalatest"    %% "scalatest"  % "3.2.18" % Test
    ),
    scalacOptions ++= Seq("-Wunused:all", "-deprecation", "-feature")
  )

lazy val root = project
  .in(file("."))
  .aggregate(compiler)
  .settings(
    publish / skip := true
  )
