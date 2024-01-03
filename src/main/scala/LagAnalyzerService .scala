import fs2.kafka.admin.MkAdminClient
import cats.effect.IO
import fs2._
import fs2.kafka.KafkaAdminClient
import fs2.kafka._
import org.apache.kafka.common.TopicPartition
import fs2.kafka.ConsumerSettings
import fs2.kafka.AutoOffsetReset
import scala.concurrent.duration._
import cats.effect

object LagAnalyzerService {

  // val keyDerializer = Deserializer.int[IO]
  // val valueDerializer = Deserializer.int[IO]
  val consumerSettings = ConsumerSettings[IO, Int, Int]
    // .withAutoCommitInterval(5.seconds)
    .withAutoOffsetReset(AutoOffsetReset.Earliest)
    .withHeartbeatInterval(20.seconds)
    .withClientId("client1")
    .withBootstrapServers("localhost:9092,localhost:9093,localhost:9094")
    .withEnableAutoCommit(false)
    .withGroupId("groupid")
    .withMaxPollRecords(12)
    // .withRecordMetadata(_.partition.toString())
    .withPollInterval(50.milliseconds)
    .withHeartbeatInterval(3.seconds)
    .withMaxPollInterval(
      10.seconds
    )

  // Consumer lag is simply the delta between the consumer’s last committed offset and the producer’s end offset in the log.
  // In other words, the consumer lag measures the delay between producing and consuming messages in any producer-consumer system.

  // Monitor the Consumer Lag in Apache Kafka
  // Kafka consumer group lag is a key performance indicator of any Kafka-based event-driven system.

  // Consumer Group Offset
  // First, we can use the listConsumerGroupOffsets() method of the AdminClient class to fetch the offset information of a specific consumer group id
  // Next, our focus is mainly on the offset values, so we can invoke the partitionsToOffsetAndMetadata() method to get a map of TopicPartition vs. OffsetAndMetadata values:
  val adminClientSettings = AdminClientSettings("localhost:9092")

  val kafkaAdminClient: Stream[IO, KafkaAdminClient[IO]] =
    KafkaAdminClient.stream[IO](adminClientSettings)

  def getLags(groupId: String): Stream[IO, Map[TopicPartition, Long]] =
    for {
      consumer <- KafkaConsumer
        .stream(consumerSettings)
      topicPartitionoffsetAndMetadata <- kafkaAdminClient.parEvalMap(25)(
        _.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata
      )

      consumerGrpOffsets = topicPartitionoffsetAndMetadata.map {
        case (topicPartition, offsetAndMetadata) =>
          topicPartition -> offsetAndMetadata.offset()
      }
      topicPartitions = topicPartitionoffsetAndMetadata.map(_._1).toSet
      producerOffsets <- Stream.eval(consumer.endOffsets(topicPartitions))
    } yield computeLags(consumerGrpOffsets, producerOffsets)

//Lastly, we can notice the iteration over the topicPartitionOffsetAndMetadataMap to limit our fetched results to the offset values per each topic and partition.

//The only thing left for finding the consumer group lag is a way of getting the end offset values. For this, we can use the endOffsets() method of the KafkaConsumer class.

  def getProducerOffsets(
      consumerGrpOffset: Map[TopicPartition, Long]
  ): Map[TopicPartition, Long] =
    consumerGrpOffset.map { case (topicPartition, _) =>
      topicPartition -> topicPartition.partition().toLong
    }

  // Finally, let’s write a method that uses consumer offsets and producer’s endoffsets to generate the lag for each TopicPartition:

  def computeLags(
      consumerGrpOffsets: Map[TopicPartition, Long],
      producerOffsets: Map[TopicPartition, Long]
  ): Map[TopicPartition, Long] =
    consumerGrpOffsets.flatMap { case (topicPartition, cosnumerOffset) =>
      producerOffsets.map { case (topicPartition1, producerOffset) =>
        topicPartition -> math.abs((producerOffset - cosnumerOffset))
      }
    }

  // Kafka provides a property “auto.offset.reset” that indicates what should be done when there’s no initial offset in Kafka or if the current offset doesn’t exist anymore on the server
  // Since we want to read from the beginning of the Kafka topic, we set the value of the “auto.offset.reset” property to “earliest”:

  val consumerFromBeginning = KafkaConsumer
    .stream(consumerSettings)
    // .evalMap(_.seekToBeginning(List.empty[TopicPartition]))//This method accepts a collection of TopicPartition and points the offset of the consumer to the beginning of the partition
    // we pass the value of KafkaConsumer.assignment() to the seekToBeginning() method. The KafkaConsumer.assignment() method returns the set of partitions currently assigned to the consumer.
    .parEvalMap(25)(consumer =>
      consumer.assignment.flatMap(consumer.seekToBeginning(_))
    )

}
