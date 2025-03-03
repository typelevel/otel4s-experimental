ThisBuild / tlBaseVersion    := "0.6"
ThisBuild / organization     := "org.typelevel"
ThisBuild / organizationName := "Typelevel"
ThisBuild / licenses         := Seq(License.Apache2)
ThisBuild / startYear        := Some(2024)

// publish to s01.oss.sonatype.org (set to true to publish to oss.sonatype.org instead)
ThisBuild / sonatypeCredentialHost := xerial.sbt.Sonatype.sonatypeLegacy

// the project does not provide any binary guarantees
ThisBuild / tlMimaPreviousVersions := Set.empty

ThisBuild / developers := List(
  // your GitHub handle and name
  tlGitHubDev("iRevive", "Maksym Ochenashko")
)

ThisBuild / githubWorkflowBuildPostamble ++= Seq(
  WorkflowStep.Sbt(
    List("doc", "docs/mdoc"),
    name = Some("Verify docs"),
    cond = Some("matrix.project == 'rootJVM' && matrix.scala == '2.13'")
  )
)

ThisBuild / githubWorkflowJavaVersions := Seq(
  JavaSpec.temurin("8"),
  JavaSpec.semeru("21")
)

val Versions = new {
  val Scala213        = "2.13.16"
  val Scala3          = "3.3.5"
  val Otel4s          = "0.12.0-RC3"
  val Munit           = "1.0.0"
  val MUnitScalaCheck = "1.0.0-M11" // we aren't ready for Scala Native 0.5.x
  val MUnitCatsEffect = "2.0.0"
}

ThisBuild / crossScalaVersions := Seq(Versions.Scala213, Versions.Scala3)
ThisBuild / scalaVersion       := Versions.Scala213 // the default Scala

lazy val root = tlCrossRootProject
  .settings(name := "otel4s-experimental")
  .aggregate(metrics, trace, examples)

lazy val metrics = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/metrics"))
  .settings(munitDependencies)
  .settings(
    name := "otel4s-experimental-metrics",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "otel4s-core-metrics"        % Versions.Otel4s,
      "org.typelevel" %%% "otel4s-sdk-metrics-testkit" % Versions.Otel4s % Test
    )
  )
  .jvmSettings(
    Test / fork := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "otel4s-semconv-metrics"              % Versions.Otel4s,
      "org.typelevel" %%% "otel4s-semconv-metrics-experimental" % Versions.Otel4s % Test,
    )
  )

lazy val trace = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/trace"))
  .settings(munitDependencies)
  .settings(scalaReflectDependency)
  .settings(
    name := "otel4s-experimental-trace",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "otel4s-core-trace" % Versions.Otel4s
    ),
    scalacOptions ++= {
      if (tlIsScala3.value) Nil else Seq("-Ymacro-annotations")
    }
  )

lazy val examples = project
  .in(file("modules/examples"))
  .enablePlugins(NoPublishPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "otel4s-sdk"          % Versions.Otel4s,
      "org.typelevel" %%% "otel4s-sdk-exporter" % Versions.Otel4s
    ),
    javaOptions += "-Dotel.service.name=ce-runtime",
    javaOptions += "-Dotel.exporter.otlp.protocol=http/protobuf",
    javaOptions += "-Dotel.exporter.otlp.endpoint=http://localhost:4318",
    javaOptions += "-Dotel.metric.export.interval=15s",
    run / fork := true,
  )
  .dependsOn(metrics.jvm)

lazy val docs = project
  .in(file("modules/docs"))
  .enablePlugins(MdocPlugin, NoPublishPlugin)
  .dependsOn(metrics.jvm, trace.jvm)
  .settings(
    mdocIn  := file("docs/index.md"),
    mdocOut := file("README.md"),
    mdocVariables := Map(
      "VERSION" -> tlLatestVersion.value.getOrElse(version.value),
    ),
    scalacOptions += "-Ymacro-annotations",
    tlFatalWarnings := false,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "otel4s-oteljava" % Versions.Otel4s,
      "org.typelevel" %% "otel4s-sdk"      % Versions.Otel4s
    )
  )

lazy val scalaReflectDependency = Def.settings(
  libraryDependencies ++= {
    if (tlIsScala3.value) Nil
    else Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided)
  }
)

lazy val munitDependencies = Def.settings(
  libraryDependencies ++= Seq(
    "org.scalameta" %%% "munit"             % Versions.Munit           % Test,
    "org.scalameta" %%% "munit-scalacheck"  % Versions.MUnitScalaCheck % Test,
    "org.typelevel" %%% "munit-cats-effect" % Versions.MUnitCatsEffect % Test
  )
)
