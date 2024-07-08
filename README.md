# otel4s-experimental

![Typelevel Organization Project](https://img.shields.io/badge/typelevel-organization%20project-FF6169.svg)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.typelevel/otel4s-experimental_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.typelevel/otel4s-experimental_2.13)

> [!WARNING]  
> The `otel4s-experimental` project provides **no binary compatibility guarantees** between releases. 
> Changes made in this repository may be **completely** incompatible with previous versions.

`otel4s-experimental` is a companion project for the [otel4s][otel4s]. The key points of the repository:
* Develop and test new experimental features
* Provide access to the unstable functionality without breaking the `otel4s`
* Some features may be upstreamed to the `otel4s` eventually

## Getting started

Add the `otel4s-experimental-metrics` dependency to the `build.sbt`:
```scala
libraryDependencies ++= Seq(
  "org.typelevel" %% "otel4s-experimental-metrics" % "<version>"
)
```

### 1) `IOMetrics` - cats-effect runtime metrics

```scala
import cats.effect.{IO, Resource}
import cats.syntax.all._
import org.typelevel.otel4s.experimental.metrics._
import org.typelevel.otel4s.metrics.Meter

implicit val meter: Meter[IO] = ???

val setup: Resource[IO, Unit] =
  IOMetrics.registerComputeMetrics[IO]() >> IOMetrics.registerCpuStarvationMetrics[IO]()

def app: IO[Unit] = ???

def run: IO[Unit] =
  setup.surround(app)
```

[otel4s]: https://github.com/typelevel/otel4s
