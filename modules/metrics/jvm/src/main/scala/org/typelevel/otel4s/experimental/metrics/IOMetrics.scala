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

import cats.effect.Resource
import cats.effect.Sync
import cats.effect.std.Console
import cats.effect.syntax.resource._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.Meter

import java.lang.management.ManagementFactory
import javax.management.AttributeList
import javax.management.MBeanServer
import javax.management.ObjectName
import scala.jdk.CollectionConverters._

object IOMetrics {

  /** Registers the runtime compute metrics:
    *   - `cats.effect.runtime.compute.thread.count`
    *   - `cats.effect.runtime.compute.thread.active.count`
    *   - `cats.effect.runtime.compute.thread.blocked.count`
    *   - `cats.effect.runtime.compute.thread.searching.count`
    *   - `cats.effect.runtime.compute.fiber.enqueued.count`
    *
    * Built-in attributes:
    *   - `hash` - the hash of the work-stealing thread pool
    *
    * @param attributes
    *   the attributes to attach to the metrics
    */
  def registerComputeMetrics[F[_]: Sync: Meter: Console](
      attributes: Attributes = Attributes.empty
  ): Resource[F, Unit] = {
    def computePools(server: MBeanServer) = server
      .queryNames(MBeans.ComputePool.Name, null)
      .asScala
      .toList
      .map(name => ComputePool(name, name.getKeyProperty("type").split("-")(1)))

    withMBeanServer[F] { mBeanServer =>
      computeMetrics(mBeanServer, computePools(mBeanServer), attributes)
    }
  }

  /** Registers the runtime local queue metrics:
    *   - `cats.effect.runtime.local.queue.fiber.enqueued.count`
    *   - `cats.effect.runtime.local.queue.fiber.spillover.count`
    *   - `cats.effect.runtime.local.queue.fiber.steal.attempt.count`
    *   - `cats.effect.runtime.local.queue.fiber.stolen.count`
    *   - `cats.effect.runtime.local.queue.fiber.total.count`
    *   - `cats.effect.runtime.local.queue.head.index`
    *   - `cats.effect.runtime.local.queue.tag.head.real`
    *   - `cats.effect.runtime.local.queue.tag.head.steal`
    *   - `cats.effect.runtime.local.queue.tag.tail`
    *   - `cats.effect.runtime.local.queue.tail.index`
    *
    * Built-in attributes:
    *   - `hash` - the hash of the work-stealing thread pool the queue is used by
    *   - `idx` - the index of the queue
    *
    * @param attributes
    *   the attributes to attach to the metrics
    */
  def registerLocalQueueMetrics[F[_]: Sync: Meter: Console](
      attributes: Attributes = Attributes.empty
  ): Resource[F, Unit] = {
    def queues(server: MBeanServer) = server
      .queryNames(MBeans.LocalQueue.Name, null)
      .asScala
      .toList
      .map { name =>
        val extra = name.getKeyProperty("type").split("-")
        LocalQueue(name, extra(1), extra(2).toLong)
      }

    withMBeanServer[F] { mBeanServer =>
      localQueueMetrics(mBeanServer, queues(mBeanServer), attributes)
    }
  }

  /** Registers the CPU starvation:
    *   - `cats.effect.runtime.cpu.starvation.count`
    *   - `cats.effect.runtime.cpu.starvation.clock.drift.current`
    *   - `cats.effect.runtime.cpu.starvation.clock.drift.max`
    *
    * @param attributes
    *   the attributes to attach to the metrics
    */
  def registerCpuStarvationMetrics[F[_]: Sync: Meter: Console](
      attributes: Attributes = Attributes.empty
  ): Resource[F, Unit] =
    withMBeanServer[F] { mBeanServer =>
      cpuStarvationMetrics(mBeanServer, attributes)
    }

  private def computeMetrics[F[_]: Sync: Meter](
      server: MBeanServer,
      computePools: List[ComputePool],
      extraAttributes: Attributes,
  ): Resource[F, Unit] = {
    val prefix = "cats.effect.runtime.compute"

    Meter[F].batchCallback.of(
      Meter[F]
        .observableGauge[Long](s"$prefix.thread.count")
        .withDescription(
          "The number of worker thread instances backing the work-stealing thread pool (WSTP)."
        )
        .withUnit("{thread}")
        .createObserver,
      Meter[F]
        .observableGauge[Long](s"$prefix.thread.active.count")
        .withDescription(
          "The number of active worker thread instances currently executing fibers on the compute thread pool."
        )
        .withUnit("{thread}")
        .createObserver,
      Meter[F]
        .observableGauge[Long](s"$prefix.thread.searching.count")
        .withDescription(
          "The number of worker thread instances currently searching for fibers to steal from other worker threads."
        )
        .withUnit("{thread}")
        .createObserver,
      Meter[F]
        .observableGauge[Long](s"$prefix.thread.blocked.count")
        .withDescription(
          "The number of worker thread instances which are currently blocked due to running blocking actions on the compute thread pool."
        )
        .withUnit("{thread}")
        .createObserver,
      Meter[F]
        .observableGauge[Long](s"$prefix.fiber.enqueued.count")
        .withDescription("The total number of fibers enqueued on all local queues.")
        .withUnit("{fiber}")
        .createObserver,
      /*Meter[F]
        .observableGauge[Long](s"$prefix.fiber.suspended.count")
        .withDescription("The number of fibers which are currently asynchronously suspended.")
        .withUnit("{fiber}")
        .createObserver*/
    ) { (total, active, searching, blocked, enqueued /*, suspended*/ ) =>
      computePools.traverse_ { pool =>
        val attributes = Attributes(Attribute("hash", pool.hash)) ++ extraAttributes

        for {
          snapshot <- Sync[F].delay(server.getAttributes(pool.mbean, MBeans.ComputePool.Attributes))
          _ <- total.record(MBeans.getValue[Int](snapshot, 0), attributes)
          _ <- active.record(MBeans.getValue[Int](snapshot, 1), attributes)
          _ <- searching.record(MBeans.getValue[Int](snapshot, 2), attributes)
          _ <- blocked.record(MBeans.getValue[Int](snapshot, 3), attributes)
          _ <- enqueued.record(MBeans.getValue[Long](snapshot, 4), attributes)
          // _ <- suspended.record(MBeans.getValue[Long](snapshot, 5), attributes)
        } yield ()
      }
    }
  }

  private def localQueueMetrics[F[_]: Sync: Meter](
      server: MBeanServer,
      queues: List[LocalQueue],
      extraAttributes: Attributes,
  ): Resource[F, Unit] = {
    val prefix = "cats.effect.runtime.local.queue"

    for {
      fiberEnqueued <- Meter[F]
        .observableUpDownCounter[Long](s"$prefix.fiber.enqueued.count")
        .withDescription("The number of enqueued fibers.")
        .withUnit("{fiber}")
        .createObserver
        .toResource

      fiberTotal <- Meter[F]
        .observableCounter[Long](s"$prefix.fiber.total.count")
        .withDescription(
          "The total number of fibers enqueued during the lifetime of the local queue."
        )
        .withUnit("{fiber}")
        .createObserver
        .toResource

      fiberSpillover <- Meter[F]
        .observableCounter[Long](s"$prefix.fiber.spillover.count")
        .withDescription("The total number of fibers spilt over to the external queue.")
        .withUnit("{fiber}")
        .createObserver
        .toResource

      stealAttemptCount <- Meter[F]
        .observableCounter[Long](s"$prefix.fiber.steal.attempt.count")
        .withDescription("The total number of successful steal attempts by other worker threads.")
        .withUnit("{fiber}")
        .createObserver
        .toResource

      stolenCount <- Meter[F]
        .observableCounter[Long](s"$prefix.fiber.stolen.count")
        .withDescription("The total number of stolen fibers by other worker threads.")
        .withUnit("{fiber}")
        .createObserver
        .toResource

      headIndex <- Meter[F]
        .observableGauge[Long](s"$prefix.head.index")
        .withDescription("The index representing the head of the queue.")
        .createObserver
        .toResource

      headTagReal <- Meter[F]
        .observableGauge[Long](s"$prefix.head.tag.real")
        .withDescription("The 'real' value of the head of the local queue.")
        .createObserver
        .toResource

      headTagSteal <- Meter[F]
        .observableGauge[Long](s"$prefix.head.tag.steal")
        .withDescription("The 'steal' tag of the head of the local queue.")
        .createObserver
        .toResource

      tailIndex <- Meter[F]
        .observableGauge[Long](s"$prefix.tail.index")
        .withDescription("The index representing the tail of the queue.")
        .createObserver
        .toResource

      tailTag <- Meter[F]
        .observableGauge[Long](s"$prefix.tail.tag")
        .withDescription("The 'tail' tag of the tail of the local queue.")
        .createObserver
        .toResource

      callback = queues.traverse_ { queue =>
        val attributes = Attributes(
          Attribute("hash", queue.hash),
          Attribute("idx", queue.idx)
        ) ++ extraAttributes

        for {
          snapshot <- Sync[F].delay(server.getAttributes(queue.mbean, MBeans.LocalQueue.Attributes))
          _ <- fiberEnqueued.record(MBeans.getValue[Int](snapshot, 0), attributes)
          _ <- headIndex.record(MBeans.getValue[Int](snapshot, 1), attributes)
          _ <- tailIndex.record(MBeans.getValue[Int](snapshot, 2), attributes)
          _ <- fiberTotal.record(MBeans.getValue[Long](snapshot, 3), attributes)
          _ <- fiberSpillover.record(MBeans.getValue[Long](snapshot, 4), attributes)
          _ <- stealAttemptCount.record(MBeans.getValue[Long](snapshot, 5), attributes)
          _ <- stolenCount.record(MBeans.getValue[Long](snapshot, 6), attributes)
          _ <- headTagReal.record(MBeans.getValue[Int](snapshot, 7), attributes)
          _ <- headTagSteal.record(MBeans.getValue[Int](snapshot, 8), attributes)
          _ <- tailTag.record(MBeans.getValue[Int](snapshot, 9), attributes)
        } yield ()
      }
      _ <- Meter[F].batchCallback(
        callback,
        fiberEnqueued,
        headIndex,
        tailIndex,
        fiberTotal,
        fiberSpillover,
        stealAttemptCount,
        stolenCount,
        headTagReal,
        headTagSteal,
        tailTag
      )
    } yield ()
  }

  private def cpuStarvationMetrics[F[_]: Sync: Meter](
      server: MBeanServer,
      attributes: Attributes
  ): Resource[F, Unit] = {
    val prefix = "cats.effect.runtime.cpu.starvation"

    Meter[F].batchCallback.of(
      Meter[F]
        .observableCounter[Long](s"$prefix.count")
        .withDescription("The number of CPU starvation events.")
        .createObserver,
      Meter[F]
        .observableGauge[Long](s"$prefix.clock.drift.current")
        .withDescription("The current CPU drift in milliseconds")
        .withUnit("ms")
        .createObserver,
      Meter[F]
        .observableGauge[Long](s"$prefix.clock.drift.max")
        .withDescription("The max CPU drift in milliseconds")
        .withUnit("ms")
        .createObserver,
    ) { (count, driftCurrent, driftMax) =>
      for {
        snapshot <- Sync[F].delay(
          server.getAttributes(MBeans.CpuStarvation.Name, MBeans.CpuStarvation.Attributes)
        )
        _ <- count.record(MBeans.getValue[Long](snapshot, 0), attributes)
        _ <- driftCurrent.record(MBeans.getValue[Long](snapshot, 1), attributes)
        _ <- driftMax.record(MBeans.getValue[Long](snapshot, 2), attributes)
      } yield ()
    }
  }

  private def withMBeanServer[F[_]: Sync: Console](
      f: MBeanServer => Resource[F, Unit]
  ): Resource[F, Unit] =
    Sync[F]
      .delay(ManagementFactory.getPlatformMBeanServer)
      .toResource
      .flatMap(f)
      .handleErrorWith { (e: Throwable) =>
        Resource.eval(
          Console[F].errorln(
            s"Cannot access MBean due to ${e.getMessage}\n${e.getStackTrace.mkString("\n")}\nThe metrics are ignored."
          )
        )
      }

  private object MBeans {
    object ComputePool {
      val Name = new ObjectName("cats.effect.unsafe.metrics:type=ComputePoolSampler-*")
      val Attributes: Array[String] = Array(
        "WorkerThreadCount",
        "ActiveThreadCount",
        "SearchingThreadCount",
        "BlockedWorkerThreadCount",
        "LocalQueueFiberCount",
        // "SuspendedFiberCount" // may throw a NPE, so we aren't using it now
      )
    }

    object LocalQueue {
      val Name = new ObjectName("cats.effect.unsafe.metrics:type=LocalQueueSampler-*")
      val Attributes: Array[String] = Array(
        "FiberCount",
        "HeadIndex",
        "TailIndex",
        "TotalFiberCount",
        "TotalSpilloverCount",
        "SuccessfulStealAttemptCount",
        "StolenFiberCount",
        "RealHeadTag",
        "StealHeadTag",
        "TailTag"
      )
    }

    object CpuStarvation {
      val Name = new ObjectName("cats.effect.metrics:type=CpuStarvation")
      val Attributes: Array[String] = Array(
        "CpuStarvationCount",
        "CurrentClockDriftMs",
        "MaxClockDriftMs"
      )
    }

    def getValue[A](snapshot: AttributeList, idx: Int): A =
      snapshot.get(idx).asInstanceOf[javax.management.Attribute].getValue.asInstanceOf[A]
  }

  private final case class LocalQueue(mbean: ObjectName, hash: String, idx: Long)
  private final case class ComputePool(mbean: ObjectName, hash: String)

}
