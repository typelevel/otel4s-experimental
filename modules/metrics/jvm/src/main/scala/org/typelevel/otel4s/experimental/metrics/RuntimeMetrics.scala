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

import cats.effect.Async
import cats.effect.Resource
import cats.effect.std.Console
import org.typelevel.otel4s.experimental.metrics.jvm._
import org.typelevel.otel4s.metrics.Meter

object RuntimeMetrics {

  /** Registers the JVM runtime metrics:
    *   - Class
    *     - `jvm.class.count`
    *     - `jvm.class.loaded`
    *     - `jvm.class.unloaded`
    *   - CPU
    *     - `jvm.cpu.count`
    *     - `jvm.cpu.recent_utilization`
    *     - `jvm.cpu.time`
    *   - GC
    *     - `jvm.gc.duration`
    *   - Memory
    *     - `jvm.memory.committed`
    *     - `jvm.memory.limit`
    *     - `jvm.memory.used`
    *     - `jvm.memory.used_after_last_gc`
    *   - Thread
    *     - `jvm.thread.count`
    */
  def register[F[_]: Async: Meter: Console]: Resource[F, Unit] =
    for {
      _ <- ClassMetrics.register
      _ <- CpuMetrics.register
      _ <- GarbageCollectorMetrics.register
      _ <- MemoryPoolMetrics.register
      _ <- ThreadMetrics.register
    } yield ()

}
