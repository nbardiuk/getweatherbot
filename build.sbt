name := "http4s-kafka"

version := "0.1"

scalaVersion := "2.12.4"

scalacOptions ++= Seq("-Ypartial-unification")

addCompilerPlugin(
  "org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)

val http4sVersion = "0.18.1"
val circeVersion = "0.9.1"

libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-blaze-client" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion,

  "io.circe" %% "circe-optics" % circeVersion
)