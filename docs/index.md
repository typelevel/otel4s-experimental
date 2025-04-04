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
  "org.typelevel" %% "otel4s-experimental-metrics" % "@VERSION@"
)
```

### 1) `InstrumentedQueue` - the instrumentation for `cats.effect.std.Queue`

The provided metrics:
- `cats.effect.std.queue.size` - the current number of the elements in the queue
- `cats.effect.std.queue.enqueued` - the total (lifetime) number of enqueued elements
- `cats.effect.std.queue.offer.blocked` - the current number of the 'blocked' offerers
- `cats.effect.std.queue.take.blocked`- the current number of the 'blocked' takers

Example:
```scala
import cats.effect.{IO, IOApp}
import cats.effect.std.Queue
import org.typelevel.otel4s.{Attribute, Attributes}
import org.typelevel.otel4s.experimental.metrics._
import org.typelevel.otel4s.oteljava.OtelJava

object Main extends IOApp.Simple {

  def run: IO[Unit] =
    OtelJava.autoConfigured[IO]().use { otel4s =>
      otel4s.meterProvider.get("service.meter").flatMap { implicit meter =>
        val attributes = Attributes(Attribute("queue.name", "auth events"))
        for {
          queue <- InstrumentedQueue.unbounded[IO, String](attributes = attributes)
          // use the instrumented queue
        } yield ()
      }
    }
}
```

### 2) `RuntimeMetrics` - the instrumentation for JVM

The provided metrics:
- Class
    - `jvm.class.count`
    - `jvm.class.loaded`
    - `jvm.class.unloaded`
- CPU
    - `jvm.cpu.count`
    - `jvm.cpu.recent_utilization`
    - `jvm.cpu.time`
- GC
    - `jvm.gc.duration`
- Memory
    - `jvm.memory.committed`
    - `jvm.memory.limit`
    - `jvm.memory.used`
    - `jvm.memory.used_after_last_gc`
- Thread
    - `jvm.thread.count`

Example:
```scala mdoc:reset:silent
import cats.effect.{IO, IOApp}
import org.typelevel.otel4s.experimental.metrics._
import org.typelevel.otel4s.sdk._

object Main extends IOApp.Simple {
  def app: IO[Unit] = ???

  def run: IO[Unit] =
    OpenTelemetrySdk.autoConfigured[IO]().use { autoConfigured =>
      val sdk = autoConfigured.sdk
      sdk.meterProvider.get("service.meter").flatMap { implicit meter =>
        RuntimeMetrics.register[IO].surround(app)
      }
    }
}
```

## Trace - getting started

Add the `otel4s-experimental-trace` dependency to the `build.sbt`:
```scala
libraryDependencies ++= Seq(
  "org.typelevel" %%% "otel4s-experimental-trace" % "@VERSION@"
)
```

### 1) `@span` annotation

The body of a method annotated with `@span` will be wrapped into a span:
```scala mdoc:reset:silent
import cats.effect.IO
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.experimental.trace.{attribute, span}

case class User(name: String)

class Service[F[_]: Tracer] {
  @span
  def findUser(
      @attribute userId: Long,
      @attribute("user.hash") hash: String
  ): F[User] = ???
}
```

expands into:
```scala
class Service[F[_]: Tracer] {
  def findUser(
      userId: Long,
      hash: String
  ): F[User] =
    Tracer[F].span(
      "findUser",
      Attribute("userId", userId), Attribute("user.hash", hash)
    ).surround(???)
}
```

The macro works with variables too:
```scala mdoc:compile-only
implicit val tracer: Tracer[IO] = ???

@span("custom_name")
val strictUser: IO[User] = ???
```
expands into:
```scala
val strictUser: IO[User] =
  Tracer[IO].span("custom_name").surround(???)
```

[otel4s]: https://github.com/typelevel/otel4s
[grafana-ce-dashboard]: https://grafana.com/grafana/dashboards/21487-cats-effect-runtime-metrics/
