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

import cats.effect.Concurrent
import cats.effect.MonadCancelThrow
import cats.effect.std.Queue
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.Meter

object InstrumentedQueue {

  /** Creates an unbounded instrumented queue.
    *
    * The provided metrics:
    *   - `cats.effect.std.queue.size` - the current number of the elements in the queue
    *   - `cats.effect.std.queue.enqueued` - the total (lifetime) number of enqueued elements
    *   - `cats.effect.std.queue.offer.blocked` - the current number of the 'blocked' offerers
    *   - `cats.effect.std.queue.take.blocked`- the current number of the 'blocked' takers
    *
    * @param prefix
    *   the prefix to use with the metrics
    *
    * @param attributes
    *   the additional attributes to add to the metrics
    */
  def unbounded[F[_]: Concurrent: Meter, A](
      prefix: String = "cats.effect.std.queue",
      attributes: Attributes = Attributes.empty
  ): F[Queue[F, A]] = {
    val attrs = Attributes(Attribute("queue.type", "unbounded")) ++ attributes
    Queue.unbounded[F, A].flatMap(q => instrument(q, prefix, attrs))
  }

  /** Creates a bounded instrumented queue with the given capacity.
    *
    * The provided metrics:
    *   - `cats.effect.std.queue.size` - the current number of the elements in the queue
    *   - `cats.effect.std.queue.enqueued` - the total (lifetime) number of enqueued elements
    *   - `cats.effect.std.queue.offer.blocked` - the current number of the 'blocked' offerers
    *   - `cats.effect.std.queue.take.blocked`- the current number of the 'blocked' takers
    *
    * @param capacity
    *   the capacity of the queue
    *
    * @param prefix
    *   the prefix to use with the metrics
    *
    * @param attributes
    *   the additional attributes to add to the metrics
    */
  def bounded[F[_]: Concurrent: Meter, A](
      capacity: Int,
      prefix: String = "cats.effect.std.queue",
      attributes: Attributes = Attributes.empty
  ): F[Queue[F, A]] = {
    val attrs = Attributes(
      Attribute("queue.type", "bounded"),
      Attribute("queue.capacity", capacity.toLong)
    ) ++ attributes
    Queue.bounded[F, A](capacity).flatMap(q => instrument(q, prefix, attrs))
  }

  /** Creates a dropping instrumented queue with the given capacity.
    *
    * The provided metrics:
    *   - `cats.effect.std.queue.size` - the current number of the elements in the queue
    *   - `cats.effect.std.queue.enqueued` - the total (lifetime) number of enqueued elements
    *   - `cats.effect.std.queue.offer.blocked` - the current number of the 'blocked' offerers
    *   - `cats.effect.std.queue.take.blocked`- the current number of the 'blocked' takers
    *
    * @param capacity
    *   the capacity of the queue
    *
    * @param prefix
    *   the prefix to use with the metrics
    *
    * @param attributes
    *   the additional attributes to add to the metrics
    */
  def dropping[F[_]: Concurrent: Meter, A](
      capacity: Int,
      prefix: String = "cats.effect.std.queue",
      attributes: Attributes = Attributes.empty
  ): F[Queue[F, A]] = {
    val attrs = Attributes(
      Attribute("queue.type", "dropping"),
      Attribute("queue.capacity", capacity.toLong)
    ) ++ attributes
    Queue.dropping[F, A](capacity).flatMap(q => instrument(q, prefix, attrs))
  }

  /** Creates an instrumented circular buffer with the given capacity.
    *
    * The provided metrics:
    *   - `cats.effect.std.queue.size` - the current number of the elements in the queue
    *   - `cats.effect.std.queue.enqueued` - the total (lifetime) number of enqueued elements
    *   - `cats.effect.std.queue.offer.blocked` - the current number of the 'blocked' offerers
    *   - `cats.effect.std.queue.take.blocked`- the current number of the 'blocked' takers
    *
    * @param capacity
    *   the capacity of the circular buffer
    *
    * @param prefix
    *   the prefix to use with the metrics
    *
    * @param attributes
    *   the additional attributes to add to the metrics
    */
  def circularBuffer[F[_]: Concurrent: Meter, A](
      capacity: Int,
      prefix: String = "cats.effect.std.queue",
      attributes: Attributes = Attributes.empty
  ): F[Queue[F, A]] = {
    val attrs = Attributes(
      Attribute("queue.type", "circularBuffer"),
      Attribute("queue.capacity", capacity.toLong)
    ) ++ attributes
    Queue.circularBuffer[F, A](capacity).flatMap(q => instrument(q, prefix, attrs))
  }

  /** Creates an instrumented synchronous queue with the given capacity.
    *
    * The provided metrics:
    *   - `cats.effect.std.queue.size` - the current number of the elements in the queue
    *   - `cats.effect.std.queue.enqueued` - the total (lifetime) number of enqueued elements
    *   - `cats.effect.std.queue.offer.blocked` - the current number of the 'blocked' offerers
    *   - `cats.effect.std.queue.take.blocked`- the current number of the 'blocked' takers
    *
    * @param prefix
    *   the prefix to use with the metrics
    *
    * @param attributes
    *   the additional attributes to add to the metrics
    */
  def synchronous[F[_]: Concurrent: Meter, A](
      prefix: String = "cats.effect.std.queue",
      attributes: Attributes = Attributes.empty
  ): F[Queue[F, A]] = {
    val attrs = Attributes(Attribute("queue.type", "synchronous")) ++ attributes
    Queue.synchronous[F, A].flatMap(q => instrument(q, prefix, attrs))
  }

  /** Instruments the given queue.
    *
    * The provided metrics:
    *   - `cats.effect.std.queue.size` - the current number of the elements in the queue
    *   - `cats.effect.std.queue.enqueued` - the total (lifetime) number of enqueued elements
    *   - `cats.effect.std.queue.offer.blocked` - the current number of the 'blocked' offerers
    *   - `cats.effect.std.queue.take.blocked`- the current number of the 'blocked' takers
    *
    * @example
    *   {{{
    * implicit val meter: Meter[IO] = ???
    *
    * val attributes = Attributes(
    *   Attribute("queue.type", "bounded"),
    *   Attribute("queue.capacity", 200L),
    *   Attribute("queue.name", "auth events")
    * )
    *
    * for {
    *   source <- Queue.bounded[IO, String](200)
    *   queue <- InstrumentedQueue(source, attributes = attributes)
    * } yield ()
    *   }}}
    *
    * @param prefix
    *   the prefix to use with the metrics
    *
    * @param attributes
    *   the attributes to add to the metrics
    *
    * @param queue
    *   the queue to instrument
    */
  def instrument[F[_]: MonadCancelThrow: Meter, A](
      queue: Queue[F, A],
      prefix: String = "cats.effect.std.queue",
      attributes: Attributes = Attributes.empty
  ): F[Queue[F, A]] =
    for {
      queueSize <- Meter[F]
        .upDownCounter[Long](s"$prefix.size")
        .withDescription("The current number of the elements in the queue")
        .withUnit("{element}")
        .create

      enqueued <- Meter[F]
        .counter[Long](s"$prefix.enqueued")
        .withDescription("The total number of the elements enqueued into the queue")
        .withUnit("{element}")
        .create

      offerBlocked <- Meter[F]
        .upDownCounter[Long](s"$prefix.offer.blocked")
        .withDescription("The current number of 'blocked' offer operations")
        .create

      takeBlocked <- Meter[F]
        .upDownCounter[Long](s"$prefix.take.blocked")
        .withDescription("The current number of 'blocked' take operations")
        .create

      initialSize <- queue.size
      _ <- queueSize.add(initialSize.toLong, attributes)
      _ <- enqueued.add(initialSize.toLong, attributes)
    } yield new Queue[F, A] {
      def offer(a: A): F[Unit] =
        MonadCancelThrow[F].bracket(offerBlocked.inc(attributes)) { _ =>
          queue.offer(a) *> enqueued.inc(attributes) *> queueSize.inc(attributes)
        }(_ => offerBlocked.dec(attributes))

      def tryOffer(a: A): F[Boolean] =
        queue.tryOffer(a).flatTap { offered =>
          (enqueued.inc(attributes) *> queueSize.inc(attributes)).whenA(offered)
        }

      def take: F[A] =
        MonadCancelThrow[F].bracket(takeBlocked.inc(attributes)) { _ =>
          queue.take <* queueSize.dec(attributes)
        }(_ => takeBlocked.dec(attributes))

      def tryTake: F[Option[A]] =
        queue.tryTake.flatTap(available => queueSize.dec(attributes).whenA(available.isDefined))

      def size: F[Int] =
        queue.size
    }

}
