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
        val expected1 = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", Nil, 5L),
          Metric("cats.effect.std.queue.offer.blocked", "", Nil, 0L),
          Metric("cats.effect.std.queue.size", "{element}", Nil, 5L)
        )

        val expected2 = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", Nil, 5L),
          Metric("cats.effect.std.queue.offer.blocked", "", Nil, 0L),
          Metric("cats.effect.std.queue.size", "{element}", Nil, 0L),
          Metric("cats.effect.std.queue.take.blocked", "", Nil, 0L)
        )

        val element = "value"

        for {
          source <- Queue.unbounded[IO, String]
          queue <- InstrumentedQueue(source)

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
        val expected1 = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", Nil, 0L),
          Metric("cats.effect.std.queue.size", "{element}", Nil, 0L),
          Metric("cats.effect.std.queue.take.blocked", "", Nil, 1L)
        )

        val expected2 = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", Nil, 1L),
          Metric("cats.effect.std.queue.offer.blocked", "", Nil, 0L),
          Metric("cats.effect.std.queue.size", "{element}", Nil, 0L),
          Metric("cats.effect.std.queue.take.blocked", "", Nil, 0L)
        )

        val element = "value"

        for {
          source <- Queue.bounded[IO, String](2)
          queue <- InstrumentedQueue(source)

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
        val expected1 = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", Nil, 2L),
          Metric("cats.effect.std.queue.offer.blocked", "", Nil, 1L),
          Metric("cats.effect.std.queue.size", "{element}", Nil, 2L)
        )

        val expected2 = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", Nil, 3L),
          Metric("cats.effect.std.queue.offer.blocked", "", Nil, 0L),
          Metric("cats.effect.std.queue.size", "{element}", Nil, 0L),
          Metric("cats.effect.std.queue.take.blocked", "", Nil, 0L)
        )

        val element = "value"

        for {
          source <- Queue.bounded[IO, String](2)
          queue <- InstrumentedQueue(source)

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
        val attributesKeys = attributes.toList.map(_.key.name)

        val expected1 = List(
          Metric(s"$prefix.enqueued", "{element}", attributesKeys, capacity.toLong),
          Metric(s"$prefix.offer.blocked", "", attributesKeys, 0L),
          Metric(s"$prefix.size", "{element}", attributesKeys, capacity.toLong)
        )

        val expected2 = List(
          Metric("prefix.enqueued", "{element}", attributesKeys, capacity.toLong),
          Metric("prefix.offer.blocked", "", attributesKeys, 0L),
          Metric("prefix.size", "{element}", attributesKeys, 0L),
          Metric("prefix.take.blocked", "", attributesKeys, 0L)
        )

        val element = ""

        for {
          source <- Queue.unbounded[IO, String]
          queue <- InstrumentedQueue(source, prefix, attributes)

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

        val expected = List(
          Metric("cats.effect.std.queue.enqueued", "{element}", Nil, capacity.toLong),
          Metric("cats.effect.std.queue.size", "{element}", Nil, capacity.toLong)
        )

        val element = ""

        for {
          source <- Queue.unbounded[IO, String]
          _ <- source.offer(element).replicateA_(capacity)
          _ <- InstrumentedQueue(source)
          _ <- testkit.collectMetrics.map(toMetrics).assertEquals(expected)
        } yield ()
      }
    }
  }

  private case class Metric(name: String, unit: String, attributesKeys: List[String], value: Long)

  private def toMetrics(metrics: List[MetricData]): List[Metric] =
    metrics.sortBy(_.name).map { md =>
      val (keys, value) = md.data match {
        case sum: MetricPoints.Sum =>
          sum.points.head match {
            case long: LongNumber =>
              (long.attributes.map(_.key.name), long.value)
            case _ =>
              sys.error("long expected")
          }

        case gauge: MetricPoints.Gauge =>
          gauge.points.head match {
            case long: LongNumber =>
              (long.attributes.map(_.key.name), long.value)
            case _ =>
              sys.error("long expected")
          }

        case _: MetricPoints.Histogram =>
          sys.error("gauge or sum expected")
      }

      Metric(md.name, md.unit.getOrElse(""), keys.toList, value)
    }

}
