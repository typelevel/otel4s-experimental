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

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.std.Random
import org.typelevel.otel4s.experimental.metrics.RuntimeMetrics
import org.typelevel.otel4s.sdk.OpenTelemetrySdk
import org.typelevel.otel4s.sdk.exporter.otlp.autoconfigure.OtlpExportersAutoConfigure

object Main extends IOApp.Simple {

  def run: IO[Unit] =
    OpenTelemetrySdk
      .autoConfigured[IO](_.addExportersConfigurer(OtlpExportersAutoConfigure[IO]))
      .use { otel4s =>
        otel4s.sdk.meterProvider.get("service").flatMap { implicit meter =>
          RuntimeMetrics.register[IO].surround(compute.useForever)
        }
      }

  private def compute =
    Random
      .scalaUtilRandom[IO]
      .flatMap { random =>
        val io = random.betweenLong(10, 3000).flatMap { delay =>
          if (delay % 2 == 0) IO.blocking(Thread.sleep(delay))
          else IO.delay(Thread.sleep(delay))
        }
        IO.parReplicateAN(30)(100, io).void
      }
      .foreverM
      .background

}
