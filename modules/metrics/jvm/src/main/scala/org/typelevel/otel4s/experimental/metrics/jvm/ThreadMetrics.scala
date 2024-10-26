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
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.metrics.ObservableMeasurement
import org.typelevel.otel4s.semconv.attributes.JvmAttributes
import org.typelevel.otel4s.semconv.metrics.JvmMetrics

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.management.ManagementFactory
import java.lang.management.ThreadInfo
import java.lang.management.ThreadMXBean
import java.util.Locale

/** @see
  *   [[https://opentelemetry.io/docs/specs/semconv/runtime/jvm-metrics/#jvm-threads]]
  */
object ThreadMetrics {

  def register[F[_]: Sync: Meter]: Resource[F, Unit] =
    for {
      bean <- Sync[F].delay(ManagementFactory.getThreadMXBean).toResource
      threadInfoHandleOpt <- Sync[F].delay(java9ThreadInfoHandle).toResource
      _ <- JvmMetrics.ThreadCount.createWithCallback[F, Long](
        threadInfoHandleOpt.fold(java8[F](bean))(handle => java9(bean, handle))
      )
    } yield ()

  private def java8[F[_]: Sync](
      bean: ThreadMXBean
  )(measurement: ObservableMeasurement[F, Long]): F[Unit] =
    for {
      threadCount <- Sync[F].delay(bean.getThreadCount)
      daemonThreadCount <- Sync[F].delay(bean.getDaemonThreadCount)

      _ <- measurement.record(
        daemonThreadCount,
        JvmAttributes.JvmThreadDaemon(true)
      )

      _ <- measurement.record(
        threadCount.toLong - daemonThreadCount,
        JvmAttributes.JvmThreadDaemon(false)
      )
    } yield ()

  private def java9[F[_]: Sync](
      bean: ThreadMXBean,
      handle: MethodHandle
  )(measurement: ObservableMeasurement[F, Long]): F[Unit] = {
    def attributes(info: ThreadInfo): Attributes = {
      val isDaemon = handle.invoke(info).asInstanceOf[Boolean]
      val state = info.getThreadState.name().toLowerCase(Locale.ROOT)

      Attributes(
        JvmAttributes.JvmThreadDaemon(isDaemon),
        JvmAttributes.JvmThreadState(state)
      )
    }

    for {
      stats <- Sync[F].delay {
        val infos = bean.getThreadInfo(bean.getAllThreadIds)

        infos
          .filter(_ ne null)
          .toList
          .groupBy(attributes)
          .map { case (key, values) => (key, values.size) }
          .toList
      }

      _ <- stats.traverse_ { case (attributes, count) =>
        measurement.record(count.toLong, attributes)
      }
    } yield ()
  }

  private def java9ThreadInfoHandle: Option[MethodHandle] =
    Either.catchNonFatal {
      MethodHandles
        .publicLookup()
        .findVirtual(classOf[ThreadInfo], "isDaemon", MethodType.methodType(classOf[Boolean]))
    }.toOption

}
