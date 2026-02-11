import Dependencies._

ThisBuild / organization := "com.clocksync"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := V.scala

lazy val root = (project in file("."))
  .settings(
    name := "distributed-clock-sync",
    Compile / mainClass := Some("com.clocksync.rapp.Main"),
    scalacOptions ++= Seq(
      "-language:implicitConversions",
      "-language:higherKinds",
      "-source:future"
    ),
    javacOptions ++= Seq("-source", "17", "-target", "17"),
    libraryDependencies ++= coreDeps ++ testDeps,
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
    assembly / assemblyJarName := "distributed-clock-sync.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*)          => MergeStrategy.concat
      case PathList("META-INF", "MANIFEST.MF")           => MergeStrategy.discard
      case PathList("META-INF", "versions", _*)          => MergeStrategy.first
      case PathList("META-INF", _*)                      => MergeStrategy.discard
      case "module-info.class"                           => MergeStrategy.discard
      case x if x.endsWith(".conf")                      => MergeStrategy.concat
      case x if x.endsWith(".proto")                     => MergeStrategy.first
      case _                                             => MergeStrategy.first
    }
  )
