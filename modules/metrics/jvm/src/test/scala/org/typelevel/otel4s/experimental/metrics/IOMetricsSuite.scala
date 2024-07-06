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
import munit.CatsEffectSuite
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.sdk.metrics.data.MetricData
import org.typelevel.otel4s.sdk.metrics.data.MetricPoints
import org.typelevel.otel4s.sdk.metrics.data.PointData.LongNumber
import org.typelevel.otel4s.sdk.testkit.metrics.MetricsTestkit

class IOMetricsSuite extends CatsEffectSuite {

  test("register compute metrics") {
    val cpus = Runtime.getRuntime.availableProcessors()

    def metric(name: String, unit: String, value: Long) =
      Metric(name, unit, List("hash"), value)

    def snapshot(fibers: Long, active: Long, blocked: Long, searching: Long) =
      List(
        metric("cats.effect.runtime.compute.fiber.enqueued.count", "{fiber}", fibers),
        metric("cats.effect.runtime.compute.thread.active.count", "{thread}", active),
        metric("cats.effect.runtime.compute.thread.blocked.count", "{thread}", blocked),
        metric("cats.effect.runtime.compute.thread.count", "{thread}", cpus.toLong),
        metric("cats.effect.runtime.compute.thread.searching.count", "{thread}", searching)
      )

    MetricsTestkit.inMemory[IO]().use { testkit =>
      testkit.meterProvider.get("meter").flatMap { implicit meter: Meter[IO] =>
        IOMetrics.registerComputeMetrics[IO]().surround {
          for {
            metrics <- testkit.collectMetrics
          } yield assertEquals(
            toMetrics(metrics),
            snapshot(fibers = 0L, active = 1L, blocked = 0L, searching = 0L)
          )
        }
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
