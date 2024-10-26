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

import cats.effect.Async
import cats.effect.Resource
import cats.effect.Sync
import cats.effect.std.Console
import cats.effect.std.Dispatcher
import cats.effect.syntax.resource._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import com.sun.management.GarbageCollectionNotificationInfo
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.BucketBoundaries
import org.typelevel.otel4s.metrics.Histogram
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.semconv.attributes.JvmAttributes
import org.typelevel.otel4s.semconv.metrics.JvmMetrics

import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import javax.management.Notification
import javax.management.NotificationEmitter
import javax.management.NotificationListener
import javax.management.openmbean.CompositeData
import scala.jdk.CollectionConverters._

/** @see
  *   [[https://opentelemetry.io/docs/specs/semconv/runtime/jvm-metrics/#jvm-garbage-collection]]
  */
object GarbageCollectorMetrics {
  private val Buckets = BucketBoundaries(0.01, 0.1, 1, 10)
  private val MillisPerSecond = TimeUnit.MILLISECONDS.toNanos(1)

  def register[F[_]: Async: Meter: Console]: Resource[F, Unit] =
    Async[F]
      .delay(isNotificationAvailable)
      .toResource
      .ifM(create[F], warn[F].toResource)

  private def create[F[_]: Async: Meter]: Resource[F, Unit] =
    for {
      beans <- Async[F].delay(ManagementFactory.getGarbageCollectorMXBeans).toResource
      dispatcher <- Dispatcher.sequential[F]
      histogram <- JvmMetrics.GcDuration.create[F, Double](Buckets).toResource
      _ <- beans.asScala.toList.traverse_ {
        case e: NotificationEmitter => register(histogram, dispatcher, e)
        case _                      => Resource.unit[F]
      }
    } yield ()

  private def warn[F[_]: Console]: F[Unit] =
    Console[F].errorln(
      "GarbageCollectorMetrics: " +
        "the com.sun.management.GarbageCollectionNotificationInfo class is missing. " +
        "GC metrics will not be reported."
    )

  private def register[F[_]: Sync](
      histogram: Histogram[F, Double],
      dispatcher: Dispatcher[F],
      emitter: NotificationEmitter
  ): Resource[F, Unit] = {
    def add: F[NotificationListener] = Sync[F].delay {
      val listener = new NotificationListener {
        def handleNotification(notification: Notification, handback: Any): Unit = {
          val info = GarbageCollectionNotificationInfo.from(
            notification.getUserData.asInstanceOf[CompositeData]
          )

          val duration = info.getGcInfo.getDuration.toDouble / MillisPerSecond

          val attributes = Attributes(
            JvmAttributes.JvmGcName(info.getGcName),
            JvmAttributes.JvmGcAction(info.getGcAction)
          )

          dispatcher.unsafeRunSync(histogram.record(duration, attributes))
        }
      }

      emitter.addNotificationListener(
        listener,
        _.getType == GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION,
        null
      )

      listener
    }

    Resource
      .make(add)(listener => Sync[F].delay(emitter.removeNotificationListener(listener)).voidError)
      .void
  }

  private def isNotificationAvailable: Boolean =
    Either
      .catchOnly[ClassNotFoundException](
        Class.forName(
          "com.sun.management.GarbageCollectionNotificationInfo",
          false,
          classOf[GarbageCollectorMXBean].getClassLoader
        )
      )
      .fold(_ => false, _ => true)

}
