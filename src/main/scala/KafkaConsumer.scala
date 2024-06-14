import scala.concurrent.duration._

import cats.effect._
import cats.syntax.all._
import cats.Functor
import fs2._
import fs2.kafka.{KafkaConsumer, _}
import fs2.kafka.consumer._

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.typelevel.log4cats.syntax._
import org.typelevel.log4cats.Logger

final class Consumer[F[_]: Async: Logger](
  consumer: KafkaConsumer[F, String, String]
) {

  def consume(
    topicName: String
  )(fn: Vector[String] => F[Unit]): Stream[F, Unit] = {
    Stream(consumer)
      .covary[F]
      .subscribeTo(topicName)
      .evalTap(_ => info"Subscribed to $topicName topic")
      .partitionedRecords
      .map {
        _.evalTap(s => debug"Received: ${s.record.value}")
          .groupWithin(200, 1.seconds)
          .evalMap { chunk =>
            fn(chunk.map(_.record.value).toVector).as(chunk.map(_.offset))
          }
          .unchunks
      }
      .parJoinUnbounded
      .through(commitBatchWithin[F](500, 15.seconds))

  }

}

object Consumer {

  def resource[F[_]: Async: Logger](
    bootstrapServers: String,
    groupId: String
  ): Resource[F, Consumer[F]] = {
    val settings =
      ConsumerSettings[F, String, String]
        .withAutoOffsetReset(AutoOffsetReset.Earliest)
        .withBootstrapServers(bootstrapServers)
        .withGroupId(groupId)
        .withAllowAutoCreateTopics(true)
        // .withClientId()
        // .withCustomBlockingContext(scala.concurrent.ExecutionContext.global)
        .withProperty(
          "partition.assignment.strategy",
          "org.apache.kafka.clients.consumer.RangeAssignor"
        )
        .withProperty(
          ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
          "org.apache.kafka.clients.consumer.RoundRobinAssignor"
        )
        .withProperty(
          ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
          "org.apache.kafka.clients.consumer.StickyAssignor"
        )
        .withProperty(
          "partition.assignment.strategy",
          "org.apache.kafka.clients.consumer.CooperativeStickyAssignor"
        )

    // TODO: graceful shutdown: https://fd4s.github.io/fs2-kafka/docs/consumers#graceful-shutdown
    KafkaConsumer.resource[F, String, String](settings).map(new Consumer(_))
  }

}
