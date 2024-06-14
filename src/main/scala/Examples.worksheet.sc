import java.net.InetAddress
import java.net.NetworkInterface

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import cats.effect.IO
import fs2.kafka._

import ExampleProducer.consumerSettings

InetAddress.getLocalHost

InetAddress.getLoopbackAddress

InetAddress.getAllByName("localhost")

InetAddress.getAllByName("192.168.64.1")

val interfaces = NetworkInterface.getNetworkInterfaces().asScala.toList
////NetworkInterface.getByName("bridge100").getSubInterfaces().asScala.toList
//NetworkInterface.getByName("bridge100").getHardwareAddress()
//NetworkInterface.getByName("bridge0").getSubInterfaces().asScala.toList
NetworkInterface.getByName("lo0").getSubInterfaces().asScala.toList

NetworkInterface.getByName("en0").getSubInterfaces().asScala.toList

interfaces.map(_.getParent())

interfaces.map(_.isPointToPoint())

interfaces.map(_.getInetAddresses().asScala.toList)

//let's say we have three records with keys "1", "2" and "3" in the same topic and the same partition:

KafkaConsumer
  .stream(consumerSettings)
  .evalTap(_.subscribeTo("some_topic"))
  .flatMap(_.stream)
  .debug(x => "Offset before: " + x.offset + " with a key: " + x.record.key)
  .chunkN(1)
  .map(
    fs2
      .Stream
      .chunk(_)
      .covary[IO]
      .map { x =>
        val sleepTime = if (x.record.key == "1") 3000 else 0
        println(s"I sleep $sleepTime seconds for ${x.offset}")
        Thread.sleep(sleepTime)
        x.offset
      }
  )
  .parJoinUnbounded
  .debug("Offset after: " + _)
  .through(commitBatchWithin[IO](2, 15.seconds))
  .debug(_ => "Committing an offset")

// Offset before: CommittableOffset(test_topic-0 -> 1, test_consumer_group) with a key: 3
// Offset before: CommittableOffset(test_topic-0 -> 2, test_consumer_group) with a key: 1
// Offset before: CommittableOffset(test_topic-0 -> 3, test_consumer_group) with a key: 2
// I sleep 0 seconds for CommittableOffset(test_topic-0 -> 1, test_consumer_group) Offset after: CommittableOffset(test_topic-0 -> 1, test_consumer_group)
// I sleep 3000 seconds for CommittableOffset(test_topic-0 -> 2, test_consumer_group)
// I sleep 0 seconds for CommittableOffset(test_topic-0 -> 3, test_consumer_group) Offset after: CommittableOffset(test_topic-0 -> 3, test_consumer_group) Committing an offset
// Offset after: CommittableOffset(test_topic-0 -> 2, test_consumer_group) Committing an offset
