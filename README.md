# otel4s-experimental

![Typelevel Organization Project](https://img.shields.io/badge/typelevel-organization%20project-FF6169.svg)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.typelevel/otel4s-experimental-metrics_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.typelevel/otel4s-experimental-metrics_2.13)

> [!WARNING]  
> The `otel4s-experimental` project provides **no binary compatibility guarantees** between releases. 
> Changes made in this repository may be **completely** incompatible with previous versions.

`otel4s-experimental` is a companion project for the [otel4s][otel4s]. The key points of the repository:
* Develop and test new experimental features
* Provide access to the unstable functionality without breaking the `otel4s`
* Some features may be upstreamed to the `otel4s` eventually

## Metrics - getting started

Add the `otel4s-experimental-metrics` dependency to the `build.sbt`:
```scala
libraryDependencies ++= Seq(
  "org.typelevel" %% "otel4s-experimental-metrics" % "<version>"
)
```

### 1) `IOMetrics` - cats-effect runtime metrics

```scala
import cats.effect.{IO, IOApp}
import org.typelevel.otel4s.experimental.metrics._
import org.typelevel.otel4s.oteljava.OtelJava

object Main extends IOApp.Simple {
  def app: IO[Unit] = ???

  def run: IO[Unit] =
    OtelJava.autoConfigured[IO]().use { otel4s =>
      otel4s.meterProvider.get("service.meter").flatMap { implicit meter =>
        IOMetrics.register[IO]().surround(app)
      }
    }
}
```

The metrics can be visualized in Grafana using this [dashboard][grafana-ce-dashboard].

## Trace - getting started

Add the `otel4s-experimental-trace` dependency to the `build.sbt`:
```scala
libraryDependencies ++= Seq(
  "org.typelevel" %%% "otel4s-experimental-trace" % "<version>"
)
```

### 1) `@span` annotation

The body of a method annotated with `@span` will be wrapped into a span:
```scala
import org.typelevel.otel4s.experimental.{attribute, span}

@span
def findUser(
  @attribute userId: Long, 
  @attribute("user.hash") hash: String
): F[User] = ???

// expands into

def findUser(
    userId: Long, 
    hash: String
): F[User] = 
  Tracer[F].span(
    "findUser", 
    Attribute("userId", userId), Attribute("user.hash", hash)
  ).surround(???)
```

The macro works with variables too:
```scala
@span("custom_name")
val findUser: IO[User] = ???

// expands into

val findUser: IO[User] = 
  Tracer[IO].span("custom_name").surround(???)
```

[otel4s]: https://github.com/typelevel/otel4s
[grafana-ce-dashboard]: https://grafana.com/grafana/dashboards/21487-cats-effect-runtime-metrics/
