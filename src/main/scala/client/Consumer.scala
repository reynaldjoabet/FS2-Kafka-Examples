package client

import scala.collection.immutable.SortedSet

import cats.effect.{Async, Resource}
import cats.effect.kernel.Concurrent
import cats.effect.syntax.resource._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.option._
import cats.syntax.show._
import cats.Show
import fs2.kafka._
import fs2.Stream

import config.ConsumerConfig
import org.apache.kafka.clients.admin
import org.apache.kafka.clients.consumer
import org.apache.kafka.clients.consumer.internals.CommitRequestManager
import org.apache.kafka.clients.consumer.internals.CoordinatorRequestManager
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.consumer.OffsetAndTimestamp
import org.apache.kafka.clients.consumer.OffsetResetStrategy
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.clients.FetchSessionHandler
import org.apache.kafka.clients.KafkaClient
import org.apache.kafka.clients.ManualMetadataUpdater
import org.apache.kafka.clients.Metadata
//import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.MetadataCache
import org.apache.kafka.common.{PartitionInfo, TopicPartition}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger

trait Consumer[F[_], K, V]
    extends Consume[F, K, V]
    with Assignment[F]
    with Offsets[F]
    with Topics[F]

trait Consume[F[_], K, V] {

  def partitionedStream: Stream[F, Stream[F, CommittableConsumerRecord[F, K, V]]]

  def partitionsMapStream: Stream[F, Map[
    TopicPartition,
    Stream[F, CommittableConsumerRecord[F, K, V]]
  ]]

}

trait Assignment[F[_]] {
  def assignmentStream: Stream[F, SortedSet[TopicPartition]]
}

trait Offsets[F[_]] {

  def seek(partition: TopicPartition, offset: Long): F[Unit]
  def position(partition: TopicPartition): F[Long]

  def committed(
    partitions: Set[TopicPartition]
  ): F[Map[TopicPartition, OffsetAndMetadata]]

}

trait Topics[F[_]] {

  def partitionsFor(topic: String): F[List[PartitionInfo]]

  def beginningOffsets(
    partitions: Set[TopicPartition]
  ): F[Map[TopicPartition, Long]]

  def endOffsets(partitions: Set[TopicPartition]): F[Map[TopicPartition, Long]]

}

object Consumer {

  def makeResource[F[_]: Async, K: Show, V: Show](
    config: ConsumerConfig
  )(implicit
    keyDeserializer: Deserializer[F, K],
    valueDeserializer: Deserializer[F, V]
  ): Resource[F, Consumer[F, K, V]] =
    for {
      consumer <-
        KafkaConsumer
          .resource(
            ConsumerSettings[F, K, V]
              .withBootstrapServers(config.bootstrapServers.toString)
              .withGroupId(config.groupId)
              .withAutoOffsetReset(config.autoOffsetReset)
              .withEnableAutoCommit(config.autoCommitEnabled)
              .withDefaultApiTimeout(config.apiTimeout)
          )
          .evalTap(_.subscribe(config.topics))
      log <- Slf4jLogger.create.toResource
    } yield new Impl(consumer, log)

  final private class Impl[F[_]: Concurrent, K: Show, V: Show](
    consumer: KafkaConsumer[F, K, V],
    log: Logger[F]
  ) extends Consumer[F, K, V] {

    private def processRecord(
      committable: CommittableConsumerRecord[F, K, V],
      partition: Option[TopicPartition] = None
    ): F[Unit] =
      log.info {
        "New message received: " +
          partition.foldMap(x => s"partition = '$x', ") +
          s"key = '${committable.record.key.show}', " +
          s"value = '${committable.record.value.show}', " +
          s"offset = ${committable.offset.offsetAndMetadata.offset()}"
      } *> committable.offset.commit // TODO use commitOffsetBatch

    def partitionedStream: Stream[F, Stream[F, CommittableConsumerRecord[F, K, V]]] =
      Stream.eval(log.info("Partitioned stream started")) *>
        consumer
          .partitionedStream
          .map {
            _.evalTap(processRecord(_))
          }

    def partitionsMapStream: Stream[F, Map[
      TopicPartition,
      Stream[F, CommittableConsumerRecord[F, K, V]]
    ]] =
      Stream.eval(log.info("Partitions map stream started")) *>
        consumer
          .partitionsMapStream
          .map { streamMap =>
            streamMap.map { case (partition, stream) =>
              partition -> stream.evalTap(processRecord(_, partition.some))
            }
          }

    def assignmentStream: Stream[F, SortedSet[TopicPartition]] =
      Stream.eval(log.info("Assignment stream started")) *>
        consumer
          .assignmentStream
          .evalTap { partitions =>
            log.info(
              "Consumer partitions assignment changed by rebalance: " + partitions.mkString(", ")
            )
          }

    def seek(partition: TopicPartition, offset: Long): F[Unit] =
      consumer.seek(partition, offset) *>
        log.info(
          s"Fetch offset of the partition $partition is shifted to $offset"
        )

    def position(partition: TopicPartition): F[Long] =
      consumer
        .position(partition)
        .flatTap { position =>
          log.info(s"Current fetch offset of the $partition is $position")
        }

    def committed(
      partitions: Set[TopicPartition]
    ): F[Map[TopicPartition, OffsetAndMetadata]] =
      consumer
        .committed(partitions)
        .flatTap { committed =>
          log.info {
            "Committed offsets for partitions:\n" +
              committed
                .map { case (partition, offset) =>
                  s"partition: $partition, offset: ${offset.offset()}"
                }
                .mkString("\n")
          }
        }

    def partitionsFor(topic: String): F[List[PartitionInfo]] =
      consumer
        .partitionsFor(topic)
        .flatTap { partitions =>
          log.info(
            s"Partitions of the topic $topic:\n" + partitions.mkString("\n")
          )
        }

    def beginningOffsets(
      partitions: Set[TopicPartition]
    ): F[Map[TopicPartition, Long]] =
      consumer
        .beginningOffsets(partitions)
        .flatTap { offsets =>
          log.info {
            "Beginning offsets:\n" +
              offsets
                .map { case (partition, offset) =>
                  s"partition = $partition, offset = $offset"
                }
                .mkString("\n")
          }
        }

    def endOffsets(
      partitions: Set[TopicPartition]
    ): F[Map[TopicPartition, Long]] =
      consumer
        .endOffsets(partitions)
        .flatTap { offsets =>
          log.info {
            "End offsets:\n" +
              offsets
                .map { case (partition, offset) =>
                  s"partition = $partition, offset = $offset"
                }
                .mkString("\n")
          }
        }

  }

}
