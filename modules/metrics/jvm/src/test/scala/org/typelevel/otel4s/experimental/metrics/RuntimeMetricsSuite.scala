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
import org.typelevel.otel4s.sdk.testkit.metrics.MetricsTestkit
import org.typelevel.otel4s.semconv.MetricSpec
import org.typelevel.otel4s.semconv.Requirement
import org.typelevel.otel4s.semconv.metrics.JvmMetrics

class RuntimeMetricsSuite extends CatsEffectSuite {

  test("specification check") {
    val specs = List(
      // class
      JvmMetrics.ClassCount,
      JvmMetrics.ClassLoaded,
      JvmMetrics.ClassUnloaded,
      // cpu
      JvmMetrics.CpuTime,
      JvmMetrics.CpuCount,
      JvmMetrics.CpuRecentUtilization,
      // gc,
      JvmMetrics.GcDuration,
      // memory
      JvmMetrics.MemoryUsed,
      JvmMetrics.MemoryCommitted,
      JvmMetrics.MemoryLimit,
      JvmMetrics.MemoryUsedAfterLastGc,
      // thread
      JvmMetrics.ThreadCount
    )

    assertEquals(specs.sortBy(_.name), JvmMetrics.specs.sortBy(_.name))

    MetricsTestkit.inMemory[IO]().use { testkit =>
      testkit.meterProvider.get("meter").flatMap { implicit meter: Meter[IO] =>
        RuntimeMetrics.register[IO].surround {
          for {
            _ <- IO.delay(System.gc())
            metrics <- testkit.collectMetrics
          } yield specs.foreach(spec => specTest(metrics, spec))
        }
      }
    }
  }

  private def specTest(metrics: List[MetricData], spec: MetricSpec): Unit = {
    val metric = metrics.find(_.name == spec.name)
    assert(
      metric.isDefined,
      s"${spec.name} metric is missing. Available [${metrics.map(_.name).mkString(", ")}]",
    )

    val clue = s"[${spec.name}] has a mismatched property"

    metric.foreach { md =>
      assertEquals(md.name, spec.name, clue)
      assertEquals(md.description, Some(spec.description), clue)
      assertEquals(md.unit, Some(spec.unit), clue)

      val required = spec.attributeSpecs
        .filter(_.requirement.level == Requirement.Level.Required)
        .map(_.key)
        .toSet

      val current = md.data.points.toVector
        .flatMap(_.attributes.map(_.key))
        .filter(key => required.contains(key))
        .toSet

      assertEquals(current, required, clue)
    }
  }

}
