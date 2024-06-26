import scala.collection.immutable.SortedSet
import scala.concurrent.duration._

import cats.data.NonEmptySet
import cats.effect
import cats.effect._
import cats.effect.std.Console
import cats.effect.syntax.all._
import cats.effect.SyncIO
import cats.kernel.Order
import fs2._
//import cats.syntax.all._
import fs2.kafka._
import fs2.kafka.admin
import fs2.kafka.commitBatchWithin
import fs2.kafka.consumer
import fs2.kafka.instances
import fs2.kafka.instances.fs2KafkaTopicPartitionOrder
import fs2.kafka.instances.fs2KafkaTopicPartitionOrdering
import fs2.kafka.internal
import fs2.kafka.producer
import fs2.kafka.security
import fs2.kafka.Acks
import fs2.kafka.AdminClientSettings
import fs2.kafka.AutoOffsetReset
import fs2.kafka.CommitRecovery
import fs2.kafka.CommitRecoveryException
import fs2.kafka.CommitTimeoutException
import fs2.kafka.CommittableConsumerRecord
import fs2.kafka.CommittableOffset
import fs2.kafka.CommittableOffsetBatch
import fs2.kafka.CommittableProducerRecords
import fs2.kafka.ConsumerGroupException
import fs2.kafka.ConsumerRecord
import fs2.kafka.ConsumerSettings
import fs2.kafka.ConsumerShutdownException
import fs2.kafka.Deserializer
//import fs2.kafka.GenericDeserializer
//import fs2.kafka.GenericSerializer
import fs2.kafka.Header
import fs2.kafka.HeaderDeserializer
import fs2.kafka.HeaderSerializer
import fs2.kafka.Headers
import fs2.kafka.Id
import fs2.kafka.IsolationLevel
import fs2.kafka.Jitter
import fs2.kafka.KafkaAdminClient
import fs2.kafka.KafkaByteConsumerRecord
import fs2.kafka.KafkaByteConsumerRecords
import fs2.kafka.KafkaByteProducer
import fs2.kafka.KafkaByteProducerRecord
import fs2.kafka.KafkaConsumer
import fs2.kafka.KafkaDeserializer
import fs2.kafka.KafkaHeader
import fs2.kafka.KafkaHeaders
import fs2.kafka.KafkaProducer
import fs2.kafka.KafkaProducerConnection
import fs2.kafka.KafkaSerializer
import fs2.kafka.Key
import fs2.kafka.KeyOrValue
import fs2.kafka.KeySerializer
import fs2.kafka.NotSubscribedException
import fs2.kafka.ProducerRecord
import fs2.kafka.ProducerRecords
import fs2.kafka.ProducerResult
import fs2.kafka.ProducerSettings
import fs2.kafka.Serializer
import fs2.kafka.Timestamp
import fs2.kafka.TransactionalKafkaProducer
import fs2.kafka.TransactionalProducerRecords
import fs2.kafka.TransactionalProducerSettings
import fs2.kafka.UnexpectedTopicException
import fs2.kafka.Value
import fs2.kafka.ValueDeserializer
import fs2.kafka.ValueSerializer

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.ClientDnsLookup
import org.apache.kafka.clients.ClientUtils
import org.apache.kafka.clients.ClientUtils.parseAndValidateAddresses
import org.apache.kafka.common.PartitionInfo
import org.apache.kafka.common.TopicPartition

object ExampleProducer extends IOApp.Simple {

  // val keySerializer = Serializer.int[IO]

  // val valueSerializer = Serializer.int[IO]

  val producerSettings = ProducerSettings[IO, Int, Int]
    .withAcks(Acks.All)
    .withEnableIdempotence(true)
    .withBatchSize(64)
    // .withMaxInFlightRequestsPerConnection(5)
    .withRetries(4)
    .withLinger(10.seconds)
    // .withProperties(ProducerConfig.COMPRESSION_TYPE_CONFIG->"gzip")
    .withBootstrapServers("localhost:9092")

  // .withCredentials()
  // val keyDerializer = Deserializer.int[IO]
  // val valueDerializer = Deserializer.int[IO]
  val consumerSettings = ConsumerSettings[IO, Int, Int]
    // .withAutoCommitInterval(5.seconds)
    .withAutoOffsetReset(AutoOffsetReset.Earliest)
    .withHeartbeatInterval(20.seconds)
    // .withClientId("client1")
    .withBootstrapServers("localhost:9092")
    .withEnableAutoCommit(false)
    .withGroupId("groupid")
    .withMaxPollRecords(12)
    // .withGroupInstanceId("id1")
    // .withRecordMetadata(_.partition.toString())
    // .withPollInterval(50.milliseconds)
    // .withHeartbeatInterval(3.seconds)
    .withMaxPollInterval(
      10.seconds
    ) // poll for new messages from the topic until the time specified by the Duration parameter

  /**
    * A new consumer group won’t have any offset associated with it. In such cases, Kafka provides a
    * property “auto.offset.reset” that indicates what should be done when there’s no initial offset
    * in Kafka or if the current offset doesn’t exist anymore on the server. Since we want to read
    * from the beginning of the Kafka topic, we set the value of the “auto.offset.reset” property to
    * “earliest”
    */
  val records = KafkaConsumer
    .stream(consumerSettings)
    .subscribeTo("topic-A")
    .records
    // .mapAsync(23)(commitbale=>IO(commitbale.offset))
    // .through(commitBatchWithin(500,12.seconds))
    // .records
    // .groupWithin(5, 10.seconds)
    .evalTapChunk(commitableRecord =>
      Console[IO]
        .println(s"Record has been processed ${commitableRecord.record.value}")
        .*>(commitableRecord.offset.commit)
    )
  // .groupWithin(2,20.seconds)
  // .evalTapChunk(chunk =>
  // CommittableOffsetBatch.fromFoldable(chunk.map(_.offset)).commit
  // )

  // kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic test

  // https://www.baeldung.com/java-apache-kafka-get-last-n-messages
//Get the Number of Messages in an Apache Kafka Topic
//A Kafka topic may have multiple partitions.
//Our technique should make sure we’ve counted the number of messages from every partition.
// first we’ll introduce a consumer:
//We’ve to go through each partition and check their latest offset
//then offset the consumer at the end of each partition and record the result in a partition map:
  val consumer: Stream[IO, KafkaConsumer[IO, Int, Int]] = KafkaConsumer.stream(consumerSettings)

  val partitions: Stream[IO, List[TopicPartition]] = consumer
    .evalMap(_.partitionsFor("topic-A"))
    .evalMap(partitioninfos =>
      partitioninfos.parTraverseN(partitioninfos.length)(partitionInfo =>
        IO.delay(
          new TopicPartition(partitionInfo.topic(), partitionInfo.partition())
        )
      )
    )
  // implicit val partitionInfoOrder:Order[PartitionInfo]=Order.by(tp => (tp.topic, tp.partition))

//implicit val partitionInfoOrdering: Ordering[PartitionInfo]=partitionInfoOrder.toOrdering

  def numberOfMessages(consumer: Stream[IO, KafkaConsumer[IO, Int, Int]]) =
    for {
      partitions <- consumer
                      .evalMap(_.partitionsFor("topic-A"))
                      .evalMap(partitioninfos =>
                        partitioninfos.parTraverseN(partitioninfos.length)(partitionInfo =>
                          IO.delay(
                            new TopicPartition(
                              partitionInfo.topic(),
                              partitionInfo.partition()
                            )
                          )
                        )
                      )

      _ <- NonEmptySet
             .fromSet(SortedSet(partitions: _*))
             .fold(Stream.unit.covary[IO])(prts => consumer.evalMap(_.assign(prts)))

      _ <- Stream
             .fromOption[IO](
               NonEmptySet.fromSet(SortedSet(partitions: _*))
             )
             .flatMap(partitions => consumer.map(_.assign(partitions)))

      // _<- Stream.eval(consumer.assign(NonEmptySet.fromSetUnsafe(SortedSet.from(partitions))))
      _ <- consumer.map(_.seekToEnd)

      numberOfMessages <- Stream
                            .emits(partitions)
                            .flatMap(partition => consumer.evalMap(_.position(partition)))
                            .fold(0L)(_ + _)
      // _ <- Stream.eval(IO.parTraverseN(partitions.size)(partitions)(a=>consumer.assign(NonEmptySet.one(a))))

    } yield numberOfMessages

  override def run: IO[Unit] =
    records.compile.drain
  // numberOfMessages(consumer).evalTap(IO.println).compile.drain

}
