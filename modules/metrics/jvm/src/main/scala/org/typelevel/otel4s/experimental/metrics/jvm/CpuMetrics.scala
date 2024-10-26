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
import cats.effect.std.Console
import cats.effect.syntax.resource._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.semconv.metrics.JvmMetrics

import java.lang.management.ManagementFactory
import java.lang.management.OperatingSystemMXBean
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

/** @see
  *   [[https://opentelemetry.io/docs/specs/semconv/runtime/jvm-metrics/#jvm-cpu]]
  */
object CpuMetrics {
  private val NanosPerSecond = TimeUnit.SECONDS.toNanos(1)

  private val TargetBeans = List(
    // Hotspot
    "com.sun.management.OperatingSystemMXBean",
    // OpenJ9
    "com.ibm.lang.management.OperatingSystemMXBean"
  )

  def register[F[_]: Sync: Meter: Console]: Resource[F, Unit] = {

    def cpuTime(accessor: F[Long]) =
      JvmMetrics.CpuTime.createWithCallback[F, Double] { measurement =>
        for {
          cpuTimeNanos <- accessor
          _ <- Option(cpuTimeNanos)
            .filter(_ >= 0)
            .traverse_(time => measurement.record(time.toDouble / NanosPerSecond))
        } yield ()
      }

    def recentUtilization(accessor: F[Double]) =
      JvmMetrics.CpuRecentUtilization.createWithCallback[F, Double] { measurement =>
        for {
          cpuUsage <- accessor
          _ <- Option(cpuUsage).filter(_ >= 0).traverse_(usage => measurement.record(usage))
        } yield ()
      }

    val cpuCount = JvmMetrics.CpuCount.createWithCallback[F, Long] { measurement =>
      for {
        availableProcessors <- Sync[F].delay(Runtime.getRuntime.availableProcessors())
        _ <- measurement.record(availableProcessors.toLong)
      } yield ()
    }

    for {
      bean <- Sync[F].delay(ManagementFactory.getOperatingSystemMXBean).toResource

      cpuTimeAccessor <- makeAccessor(
        bean,
        "getProcessCpuTime",
        classOf[java.lang.Long],
        Long.unbox
      ).toResource

      recentUtilizationAccessor <- makeAccessor(
        bean,
        "getProcessCpuLoad",
        classOf[java.lang.Double],
        Double.unbox
      ).toResource

      _ <- cpuTimeAccessor.traverse_(accessor => cpuTime(accessor))
      _ <- recentUtilizationAccessor.traverse_(accessor => recentUtilization(accessor))
      _ <- cpuCount
    } yield ()
  }

  private def makeAccessor[F[_]: Sync: Console, J, A](
      bean: OperatingSystemMXBean,
      methodName: String,
      target: Class[J],
      cast: J => A
  ): F[Option[F[A]]] = {
    def tryCreateAccessor(targetClass: String): F[Option[F[A]]] =
      Sync[F]
        .delay {
          val osBeanClass = Class.forName(targetClass)
          osBeanClass.cast(bean)
          val method = osBeanClass.getDeclaredMethod(methodName)
          method.setAccessible(true)

          def invoke() =
            cast(target.cast(method.invoke(bean)))

          invoke() // make sure it works

          Option(Sync[F].delay(invoke()))
        }
        .handleErrorWith {
          case _: ClassNotFoundException | _: ClassCastException | _: NoSuchMethodException =>
            Sync[F].pure(None)

          case _: IllegalAccessException | _: InvocationTargetException =>
            Console[F]
              .errorln(
                s"CpuMetrics: cannot invoke [$methodName] in [$targetClass]. This metric will not be reported."
              )
              .as(None)

          case _: Throwable =>
            Sync[F].pure(None)
        }

    Sync[F].tailRecM(TargetBeans) {
      case head :: tail =>
        for {
          accessor <- tryCreateAccessor(head)
        } yield accessor.map(Option(_)).toRight(tail)

      case Nil =>
        Sync[F].pure(Right(None))
    }
  }

}
