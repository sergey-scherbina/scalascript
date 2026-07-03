ThisBuild / scalaVersion := "3.8.3"
ThisBuild / organization := "com.example"
ThisBuild / version := "${version}"

lazy val root = project
  .in(file("."))
  .settings(
    name := "${name}",
    libraryDependencies ++= Seq(
      "io.scalascript" %% "scalascript-backend-spi"         % "0.1.0" % Provided,
      "io.scalascript" %% "scalascript-backend-interpreter" % "0.1.0" % Provided,
    ),
  )
