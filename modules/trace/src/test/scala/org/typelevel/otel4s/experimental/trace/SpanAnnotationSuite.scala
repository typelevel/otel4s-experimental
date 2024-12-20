/*
 * Copyright 2024 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.typelevel.otel4s.experimental.trace

import cats.Applicative
import cats.effect.IO
import cats.effect.Resource
import munit.CatsEffectSuite
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.AttributeKey
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.context.propagation.TextMapGetter
import org.typelevel.otel4s.context.propagation.TextMapUpdater
import org.typelevel.otel4s.meta.InstrumentMeta
import org.typelevel.otel4s.trace.Span
import org.typelevel.otel4s.trace.SpanBuilder
import org.typelevel.otel4s.trace.SpanContext
import org.typelevel.otel4s.trace.SpanOps
import org.typelevel.otel4s.trace.Tracer

import scala.collection.mutable

@experimental3
class SpanAnnotationSuite extends CatsEffectSuite {

  test("def - capture annotated attributes") {
    implicit val tracer: InMemoryTracer[IO] = new InMemoryTracer[IO]

    val userName = "user name"
    val attempts = Seq(1L, 2L, 3L)

    val expected = Vector(
      BuilderOp.Init("SpanAnnotationSuite.captureAttributes"),
      BuilderOp.ModifyState(
        SpanBuilder.State.init.addAttributes(
          Attributes(
            Attribute("name", userName),
            Attribute("attempts", attempts)
          )
        )
      ),
      BuilderOp.Build
    )

    @span
    def captureAttributes(
        @attribute("name") name: String,
        score: Long,
        @attribute attempts: Seq[Long]
    ): IO[Unit] =
      IO.pure(name).void

    for {
      _ <- captureAttributes(userName, 1L, attempts)
    } yield assertEquals(tracer.builders.map(_.ops), Vector(expected))
  }

  test("def - derive method name") {
    implicit val tracer: InMemoryTracer[IO] = new InMemoryTracer[IO]

    val expected = Vector(
      BuilderOp.Init("SpanAnnotationSuite.methodName"),
      BuilderOp.Build
    )

    @span
    def methodName: IO[Unit] = IO.unit

    for {
      _ <- methodName
    } yield assertEquals(tracer.builders.map(_.ops), Vector(expected))
  }

  test("resolve enclosing name - anonymous class") {
    trait Service[F[_]] {
      def find: F[Unit]
    }

    implicit val tracer: InMemoryTracer[IO] = new InMemoryTracer[IO]

    val service: Service[IO] = new Service[IO] {
      @span
      def find: IO[Unit] = IO.unit
    }

    val expected = Vector(
      BuilderOp.Init("SpanAnnotationSuite.$anon.find"),
      BuilderOp.Build
    )

    for {
      _ <- service.find
    } yield assertEquals(tracer.builders.map(_.ops), Vector(expected))
  }

  // tagless

  test("tagless - def - derive name") {
    implicit val tracer: InMemoryTracer[IO] = new InMemoryTracer[IO]
    val service = new Service[IO]

    val userName = "user name"
    val score = 1L
    val attempts = Seq(1L, 2L, 3L)

    val expected = Vector(
      BuilderOp.Init("Service.deriveNameDef"),
      BuilderOp.Build
    )

    for {
      _ <- service.deriveNameDef(userName, score, attempts)
    } yield assertEquals(tracer.builders.map(_.ops), Vector(expected))
  }

  test("tagless - def - custom name") {
    implicit val tracer: InMemoryTracer[IO] = new InMemoryTracer[IO]
    val service = new Service[IO]

    val userName = "user name"
    val score = 1L
    val isNew = false

    val expected = Vector(
      BuilderOp.Init("custom_span_name"),
      BuilderOp.ModifyState(
        SpanBuilder.State.init.addAttributes(
          Attributes(
            Attribute("user.name", userName),
            Attribute("score", score),
            Attribute("user.new", isNew)
          )
        )
      ),
      BuilderOp.Build
    )

    for {
      _ <- service.customNameDef(userName, score, isNew)
    } yield assertEquals(tracer.builders.map(_.ops), Vector(expected))
  }

  test("tagless - val - derive name") {
    implicit val tracer: InMemoryTracer[IO] = new InMemoryTracer[IO]
    val service = new Service[IO]

    val expected = Vector(
      BuilderOp.Init("Service.deriveNameVal"),
      BuilderOp.Build
    )

    for {
      _ <- service.deriveNameVal
    } yield assertEquals(tracer.builders.map(_.ops), Vector(expected))
  }

  test("tagless - val - custom name") {
    implicit val tracer: InMemoryTracer[IO] = new InMemoryTracer[IO]
    val service = new Service[IO]

    val expected = Vector(
      BuilderOp.Init("some_custom_name"),
      BuilderOp.Build
    )

    for {
      _ <- service.customNameVal
    } yield assertEquals(tracer.builders.map(_.ops), Vector(expected))
  }

  class Service[F[_]: Tracer: Applicative] {

    @span
    def deriveNameDef(
        name: String,
        score: Long,
        attempts: Seq[Long]
    ): F[Unit] = {
      val _ = (name, score, attempts)
      Applicative[F].unit
    }

    @span("custom_span_name")
    def customNameDef(
        @attribute("user.name") name: String,
        @attribute(name = "score") score: Long,
        @attribute(AttributeKey[Boolean]("user.new")) isNew: Boolean
    ): F[Unit] = {
      val _ = (name, score, isNew)
      Applicative[F].unit
    }

    @span
    lazy val deriveNameVal: F[Unit] =
      Applicative[F].unit

    @span(name = "some_custom_name")
    lazy val customNameVal: F[Unit] =
      Applicative[F].unit

  }

  // utility

  private sealed trait BuilderOp

  private object BuilderOp {
    case class Init(name: String) extends BuilderOp
    case class ModifyState(state: SpanBuilder.State) extends BuilderOp
    case object Build extends BuilderOp
  }

  private case class InMemoryBuilder[F[_]: Applicative](
      name: String
  ) extends SpanBuilder[F] {
    private var _state: SpanBuilder.State = SpanBuilder.State.init
    private val _ops: mutable.ArrayBuffer[BuilderOp] = new mutable.ArrayBuffer
    _ops.addOne(BuilderOp.Init(name))

    def ops: Vector[BuilderOp] = _ops.toVector

    def meta: InstrumentMeta[F] = InstrumentMeta.enabled

    def modifyState(f: SpanBuilder.State => SpanBuilder.State): SpanBuilder[F] = {
      val next = f(_state)
      _state = next
      _ops.addOne(BuilderOp.ModifyState(next))
      this
    }

    def build: SpanOps[F] =
      new SpanOps[F] {
        _ops.addOne(BuilderOp.Build)
        def startUnmanaged: F[Span[F]] = ???
        def resource: Resource[F, SpanOps.Res[F]] = ???
        def use[A](f: Span[F] => F[A]): F[A] =
          f(Span.fromBackend(Span.Backend.noop))
        def use_ : F[Unit] = Applicative[F].unit
      }
  }

  private class InMemoryTracer[F[_]: Applicative] extends Tracer[F] {
    private val _builders: mutable.ArrayBuffer[InMemoryBuilder[F]] =
      new mutable.ArrayBuffer

    def meta: InstrumentMeta[F] = InstrumentMeta.enabled
    def currentSpanContext: F[Option[SpanContext]] = ???
    def currentSpanOrNoop: F[Span[F]] = ???
    def currentSpanOrThrow: F[Span[F]] = ???
    def childScope[A](parent: SpanContext)(fa: F[A]): F[A] = ???
    def joinOrRoot[A, C: TextMapGetter](carrier: C)(fa: F[A]): F[A] = ???
    def rootScope[A](fa: F[A]): F[A] = ???
    def noopScope[A](fa: F[A]): F[A] = ???
    def propagate[C: TextMapUpdater](carrier: C): F[C] = ???

    def spanBuilder(name: String): SpanBuilder[F] = {
      val builder = new InMemoryBuilder[F](name)
      _builders.addOne(builder)
      builder
    }

    def builders: Vector[InMemoryBuilder[F]] =
      _builders.toVector
  }

}
