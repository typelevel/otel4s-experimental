ThisBuild / tlBaseVersion    := "0.1"
ThisBuild / organization     := "org.typelevel"
ThisBuild / organizationName := "Typelevel"
ThisBuild / licenses         := Seq(License.Apache2)
ThisBuild / startYear        := Some(2024)

// publish to s01.oss.sonatype.org (set to true to publish to oss.sonatype.org instead)
ThisBuild / tlSonatypeUseLegacyHost := false

// the project does not not provide any binary guarantees
ThisBuild / tlMimaPreviousVersions := Set.empty

ThisBuild / developers := List(
  // your GitHub handle and name
  tlGitHubDev("iRevive", "Maksym Ochenashko")
)

val Versions = new {
  val Scala213 = "2.13.14"
  val Scala3   = "3.3.3"
  val Otel4s   = "0.8.0"
  val Munit    = "1.0.0"
}

ThisBuild / crossScalaVersions := Seq(Versions.Scala213, Versions.Scala3)
ThisBuild / scalaVersion       := Versions.Scala213 // the default Scala

lazy val root = tlCrossRootProject
  .settings(name := "otel4s-experimental")

lazy val scalaReflectDependency = Def.settings(
  libraryDependencies ++= {
    if (tlIsScala3.value) Nil
    else Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided)
  }
)

lazy val munitDependencies = Def.settings(
  libraryDependencies ++= Seq(
    "org.scalameta" %%% "munit" % Versions.Munit % Test
  )
)
