import sbt._

object V {
  val scala      = "3.7.3"
  val catsCore   = "2.10.0"
  val catsEffect = "3.5.2"
  val circe      = "0.14.6"
  val http4s     = "0.23.24"
  val fs2        = "3.9.3"
  val log4cats   = "2.6.0"
  val logback    = "1.4.14"
  val weaver     = "0.8.3"
}

object Dependencies {

  // Core FP
  val catsCore       = "org.typelevel" %% "cats-core"       % V.catsCore
  val catsEffect     = "org.typelevel" %% "cats-effect"     % V.catsEffect
  val catsEffectStd  = "org.typelevel" %% "cats-effect-std" % V.catsEffect

  // JSON
  val circeCore    = "io.circe" %% "circe-core"    % V.circe
  val circeGeneric = "io.circe" %% "circe-generic"  % V.circe
  val circeParser  = "io.circe" %% "circe-parser"   % V.circe
  val circeLiteral = "io.circe" %% "circe-literal"  % V.circe

  // Streaming
  val fs2Core = "co.fs2" %% "fs2-core" % V.fs2
  val fs2IO   = "co.fs2" %% "fs2-io"   % V.fs2

  // HTTP
  val http4sDsl   = "org.http4s" %% "http4s-dsl"          % V.http4s
  val http4sEmber = "org.http4s" %% "http4s-ember-server"  % V.http4s
  val http4sCirce = "org.http4s" %% "http4s-circe"         % V.http4s

  // Logging
  val log4catsSlf4j  = "org.typelevel"  %% "log4cats-slf4j" % V.log4cats
  val logbackClassic = "ch.qos.logback" %  "logback-classic" % V.logback

  // Testing
  val weaverCats  = "com.disneystreaming" %% "weaver-cats"       % V.weaver
  val weaverCheck = "com.disneystreaming" %% "weaver-scalacheck" % V.weaver

  val coreDeps = Seq(
    catsCore, catsEffect, catsEffectStd,
    circeCore, circeGeneric, circeParser, circeLiteral,
    fs2Core, fs2IO,
    http4sDsl, http4sEmber, http4sCirce,
    log4catsSlf4j, logbackClassic
  )

  val testDeps = Seq(
    weaverCats, weaverCheck
  ).map(_ % Test)
}
