import cats.effect._
import cats.syntax.all._
import scala.concurrent.duration._
import fs2.kafka.{KafkaConsumer, _}
import fs2.kafka.consumer._
import cats.Functor
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import fs2._

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

    // TODO: graceful shutdown: https://fd4s.github.io/fs2-kafka/docs/consumers#graceful-shutdown
    KafkaConsumer.resource[F, String, String](settings).map(new Consumer(_))
  }

}
