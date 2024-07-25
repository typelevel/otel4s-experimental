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

package org.typelevel.otel4s.experimental.metrics

import cats.effect.IO
import cats.effect.std.Queue
import munit.CatsEffectSuite
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.sdk.metrics.data.MetricData
import org.typelevel.otel4s.sdk.metrics.data.MetricPoints
import org.typelevel.otel4s.sdk.metrics.data.PointData.LongNumber
import org.typelevel.otel4s.sdk.testkit.metrics.MetricsTestkit

import scala.concurrent.duration._

class InstrumentedQueueSuite extends CatsEffectSuite {

  test("record offered and taken elements") {
    MetricsTestkit.inMemory[IO]().use { testkit =>
      testkit.meterProvider.get("meter").flatMap { implicit meter: Meter[IO] =>
        val attributes = Attributes.empty

        val expected1 = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", attributes, 5L),
          Metric("cats.effect.std.queue.offer.blocked", "", attributes, 0L),
          Metric("cats.effect.std.queue.size", "{element}", attributes, 5L)
        )

        val expected2 = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", attributes, 5L),
          Metric("cats.effect.std.queue.offer.blocked", "", attributes, 0L),
          Metric("cats.effect.std.queue.size", "{element}", attributes, 0L),
          Metric("cats.effect.std.queue.take.blocked", "", attributes, 0L)
        )

        val element = "value"

        for {
          source <- Queue.unbounded[IO, String]
          queue <- InstrumentedQueue.instrument(source)

          _ <- queue.offer(element).replicateA_(5)
          _ <- testkit.collectMetrics.map(toMetrics).assertEquals(expected1)

          _ <- queue.take.replicateA_(5)
          _ <- testkit.collectMetrics.map(toMetrics).assertEquals(expected2)
        } yield ()
      }
    }
  }

  test("recorded blocked takers") {
    MetricsTestkit.inMemory[IO]().use { testkit =>
      testkit.meterProvider.get("meter").flatMap { implicit meter: Meter[IO] =>
        val attributes = Attributes.empty

        val expected1 = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", attributes, 0L),
          Metric("cats.effect.std.queue.size", "{element}", attributes, 0L),
          Metric("cats.effect.std.queue.take.blocked", "", attributes, 1L)
        )

        val expected2 = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", attributes, 1L),
          Metric("cats.effect.std.queue.offer.blocked", "", attributes, 0L),
          Metric("cats.effect.std.queue.size", "{element}", attributes, 0L),
          Metric("cats.effect.std.queue.take.blocked", "", attributes, 0L)
        )

        val element = "value"

        for {
          source <- Queue.bounded[IO, String](2)
          queue <- InstrumentedQueue.instrument(source)

          fiber <- queue.take.start
          _ <- IO.sleep(100.millis)
          _ <- testkit.collectMetrics.map(toMetrics).assertEquals(expected1)

          _ <- queue.offer(element)
          _ <- fiber.joinWithNever

          _ <- testkit.collectMetrics.map(toMetrics).assertEquals(expected2)
        } yield ()
      }
    }
  }

  test("recorded blocked offerers") {
    MetricsTestkit.inMemory[IO]().use { testkit =>
      testkit.meterProvider.get("meter").flatMap { implicit meter: Meter[IO] =>
        val attributes = Attributes.empty

        val expected1 = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", attributes, 2L),
          Metric("cats.effect.std.queue.offer.blocked", "", attributes, 1L),
          Metric("cats.effect.std.queue.size", "{element}", attributes, 2L)
        )

        val expected2 = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", attributes, 3L),
          Metric("cats.effect.std.queue.offer.blocked", "", attributes, 0L),
          Metric("cats.effect.std.queue.size", "{element}", attributes, 0L),
          Metric("cats.effect.std.queue.take.blocked", "", attributes, 0L)
        )

        val element = "value"

        for {
          source <- Queue.bounded[IO, String](2)
          queue <- InstrumentedQueue.instrument(source)

          fiber <- queue.offer(element).replicateA_(3).start
          _ <- IO.sleep(100.millis)
          _ <- testkit.collectMetrics.map(toMetrics).assertEquals(expected1)

          _ <- queue.take.replicateA_(3)
          _ <- fiber.joinWithNever

          _ <- testkit.collectMetrics.map(toMetrics).assertEquals(expected2)
        } yield ()
      }
    }
  }

  test("use provided attributes and prefix") {
    MetricsTestkit.inMemory[IO]().use { testkit =>
      testkit.meterProvider.get("meter").flatMap { implicit meter: Meter[IO] =>
        val capacity = 5
        val prefix = "prefix"
        val attributes = Attributes(
          Attribute("queue.type", "bounded"),
          Attribute("queue.capacity", capacity.toLong),
          Attribute("queue.name", "auth events")
        )

        val expected1 = List(
          Metric(s"$prefix.enqueued", "{element}", attributes, capacity.toLong),
          Metric(s"$prefix.offer.blocked", "", attributes, 0L),
          Metric(s"$prefix.size", "{element}", attributes, capacity.toLong)
        )

        val expected2 = List(
          Metric("prefix.enqueued", "{element}", attributes, capacity.toLong),
          Metric("prefix.offer.blocked", "", attributes, 0L),
          Metric("prefix.size", "{element}", attributes, 0L),
          Metric("prefix.take.blocked", "", attributes, 0L)
        )

        val element = ""

        for {
          source <- Queue.unbounded[IO, String]
          queue <- InstrumentedQueue.instrument(source, prefix, attributes)

          _ <- queue.offer(element).replicateA_(capacity)
          _ <- testkit.collectMetrics.map(toMetrics).assertEquals(expected1)

          _ <- queue.take.replicateA_(capacity)
          _ <- testkit.collectMetrics.map(toMetrics).assertEquals(expected2)
        } yield ()
      }
    }
  }

  test("respect initial size") {
    MetricsTestkit.inMemory[IO]().use { testkit =>
      testkit.meterProvider.get("meter").flatMap { implicit meter: Meter[IO] =>
        val capacity = 5
        val attributes = Attributes.empty

        val expected = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", attributes, capacity.toLong),
          Metric("cats.effect.std.queue.size", "{element}", attributes, capacity.toLong)
        )

        val element = ""

        for {
          source <- Queue.unbounded[IO, String]
          _ <- source.offer(element).replicateA_(capacity)
          _ <- InstrumentedQueue.instrument(source)
          _ <- testkit.collectMetrics.map(toMetrics).assertEquals(expected)
        } yield ()
      }
    }
  }

  test("create an unbounded queue") {
    MetricsTestkit.inMemory[IO]().use { testkit =>
      testkit.meterProvider.get("meter").flatMap { implicit meter: Meter[IO] =>
        val attributes = Attributes(Attribute("queue.type", "unbounded"))

        val expected = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", attributes, 0L),
          Metric("cats.effect.std.queue.size", "{element}", attributes, 0L)
        )

        for {
          _ <- InstrumentedQueue.unbounded[IO, String]()
          _ <- testkit.collectMetrics.map(toMetrics).assertEquals(expected)
        } yield ()
      }
    }
  }

  test("create a bounded queue") {
    MetricsTestkit.inMemory[IO]().use { testkit =>
      testkit.meterProvider.get("meter").flatMap { implicit meter: Meter[IO] =>
        val capacity = 100
        val attributes = Attributes(
          Attribute("queue.type", "bounded"),
          Attribute("queue.capacity", capacity.toLong)
        )

        val expected = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", attributes, 0L),
          Metric("cats.effect.std.queue.size", "{element}", attributes, 0L)
        )

        for {
          _ <- InstrumentedQueue.bounded[IO, String](capacity)
          _ <- testkit.collectMetrics.map(toMetrics).assertEquals(expected)
        } yield ()
      }
    }
  }

  test("create a dropping queue") {
    MetricsTestkit.inMemory[IO]().use { testkit =>
      testkit.meterProvider.get("meter").flatMap { implicit meter: Meter[IO] =>
        val capacity = 100
        val attributes = Attributes(
          Attribute("queue.type", "dropping"),
          Attribute("queue.capacity", capacity.toLong)
        )

        val expected = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", attributes, 0L),
          Metric("cats.effect.std.queue.size", "{element}", attributes, 0L)
        )

        for {
          _ <- InstrumentedQueue.dropping[IO, String](capacity)
          _ <- testkit.collectMetrics.map(toMetrics).assertEquals(expected)
        } yield ()
      }
    }
  }

  test("create a circular buffer") {
    MetricsTestkit.inMemory[IO]().use { testkit =>
      testkit.meterProvider.get("meter").flatMap { implicit meter: Meter[IO] =>
        val capacity = 100
        val attributes = Attributes(
          Attribute("queue.type", "circularBuffer"),
          Attribute("queue.capacity", capacity.toLong)
        )

        val expected = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", attributes, 0L),
          Metric("cats.effect.std.queue.size", "{element}", attributes, 0L)
        )

        for {
          _ <- InstrumentedQueue.circularBuffer[IO, String](capacity)
          _ <- testkit.collectMetrics.map(toMetrics).assertEquals(expected)
        } yield ()
      }
    }
  }

  test("create a synchronous queue") {
    MetricsTestkit.inMemory[IO]().use { testkit =>
      testkit.meterProvider.get("meter").flatMap { implicit meter: Meter[IO] =>
        val attributes = Attributes(Attribute("queue.type", "synchronous"))

        val expected = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", attributes, 0L),
          Metric("cats.effect.std.queue.size", "{element}", attributes, 0L)
        )

        for {
          _ <- InstrumentedQueue.synchronous[IO, String]()
          _ <- testkit.collectMetrics.map(toMetrics).assertEquals(expected)
        } yield ()
      }
    }
  }

  private case class Metric(name: String, unit: String, attributes: Attributes, value: Long)

  private def toMetrics(metrics: List[MetricData]): List[Metric] =
    metrics.sortBy(_.name).map { md =>
      val (attributes, value) = md.data match {
        case sum: MetricPoints.Sum =>
          sum.points.head match {
            case long: LongNumber =>
              (long.attributes, long.value)
            case _ =>
              sys.error("long expected")
          }

        case gauge: MetricPoints.Gauge =>
          gauge.points.head match {
            case long: LongNumber =>
              (long.attributes, long.value)
            case _ =>
              sys.error("long expected")
          }

        case _: MetricPoints.Histogram =>
          sys.error("gauge or sum expected")
      }

      Metric(md.name, md.unit.getOrElse(""), attributes, value)
    }

}
