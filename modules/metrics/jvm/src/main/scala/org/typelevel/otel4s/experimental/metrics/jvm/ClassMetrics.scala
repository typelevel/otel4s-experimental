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

import cats.effect.Resource
import cats.effect.Sync
import cats.effect.syntax.resource._
import cats.syntax.flatMap._
import cats.syntax.functor._
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.semconv.metrics.JvmMetrics

import java.lang.management.ManagementFactory

/** @see
  *   [[https://opentelemetry.io/docs/specs/semconv/runtime/jvm-metrics/#jvm-classes]]
  */
object ClassMetrics {

  def register[F[_]: Sync: Meter]: Resource[F, Unit] =
    Sync[F].delay(ManagementFactory.getClassLoadingMXBean).toResource.flatMap { bean =>
      Meter[F].batchCallback.of(
        JvmMetrics.ClassCount.createObserver[F, Long],
        JvmMetrics.ClassLoaded.createObserver[F, Long],
        JvmMetrics.ClassUnloaded.createObserver[F, Long]
      ) { (classCount, classLoaded, classUnloaded) =>
        for {
          count <- Sync[F].delay(bean.getTotalLoadedClassCount)
          loaded <- Sync[F].delay(bean.getLoadedClassCount)
          unloaded <- Sync[F].delay(bean.getUnloadedClassCount)
          _ <- classCount.record(count)
          _ <- classLoaded.record(loaded.toLong)
          _ <- classUnloaded.record(unloaded)
        } yield ()
      }
    }

}
