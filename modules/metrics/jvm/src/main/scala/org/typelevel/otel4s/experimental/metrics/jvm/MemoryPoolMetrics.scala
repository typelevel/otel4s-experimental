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

package org.typelevel.otel4s.experimental.metrics.jvm

import cats.Monad
import cats.effect.Resource
import cats.effect.Sync
import cats.effect.syntax.resource._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.metrics.ObservableMeasurement
import org.typelevel.otel4s.semconv.attributes.JvmAttributes
import org.typelevel.otel4s.semconv.metrics.JvmMetrics

import java.lang.management.ManagementFactory
import java.lang.management.MemoryPoolMXBean
import java.lang.management.MemoryType
import java.lang.management.MemoryUsage
import scala.jdk.CollectionConverters._

/** @see
  *   [[https://opentelemetry.io/docs/specs/semconv/runtime/jvm-metrics/#jvm-memory]]
  */
object MemoryPoolMetrics {

  def register[F[_]: Sync: Meter]: Resource[F, Unit] =
    Sync[F].delay(ManagementFactory.getMemoryPoolMXBeans).toResource.flatMap { jBeans =>
      val beans = jBeans.asScala.toList

      Meter[F].batchCallback.of(
        JvmMetrics.MemoryUsed.createObserver[F, Long],
        JvmMetrics.MemoryCommitted.createObserver[F, Long],
        JvmMetrics.MemoryLimit.createObserver[F, Long],
        JvmMetrics.MemoryUsedAfterLastGc.createObserver[F, Long]
      ) { (memoryUsed, memoryCommitted, memoryLimit, memoryUsedAfterLastGC) =>
        for {
          _ <- record(memoryUsed, beans, _.getUsage, _.getUsed)
          _ <- record(memoryCommitted, beans, _.getUsage, _.getCommitted)
          _ <- record(memoryLimit, beans, _.getUsage, _.getMax)
          _ <- record(memoryUsedAfterLastGC, beans, _.getCollectionUsage, _.getUsed)
        } yield ()
      }
    }

  private def record[F[_]: Monad](
      measurement: ObservableMeasurement[F, Long],
      beans: List[MemoryPoolMXBean],
      focusUsage: MemoryPoolMXBean => MemoryUsage,
      focusValue: MemoryUsage => Long
  ): F[Unit] =
    beans.traverse_ { bean =>
      val memoryType = bean.getType match {
        case MemoryType.HEAP     => JvmAttributes.JvmMemoryTypeValue.Heap
        case MemoryType.NON_HEAP => JvmAttributes.JvmMemoryTypeValue.NonHeap
      }

      val attributes = Attributes(
        JvmAttributes.JvmMemoryPoolName(bean.getName),
        JvmAttributes.JvmMemoryType(memoryType.value)
      )

      // could be null in some cases
      Option(focusUsage(bean)) match {
        case Some(usage) =>
          val value = focusValue(usage)
          if (value != -1L) {
            measurement.record(value, attributes)
          } else {
            Monad[F].unit
          }

        case None =>
          Monad[F].unit
      }
    }

}
