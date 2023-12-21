// The simplest possible sbt build file is just one line:

scalaVersion := "2.13.12"

name := "fs-kafka-example"
version := "1.0"

val logbackVersion = "1.4.11"
val log4catsVersion = "2.6.0"
val fs2KafkaVersion = "3.1.0"

val fs2Kafka = "com.github.fd4s" %% "fs2-kafka" % fs2KafkaVersion

val logback = "ch.qos.logback" % "logback-classic" % logbackVersion
val log4cats = "org.typelevel" %% "log4cats-slf4j" % log4catsVersion
//Compile / run / fork := true

libraryDependencies ++= Seq(
  fs2Kafka,
  log4cats,
  logback
)

// by default sbt run runs the program in the same JVM as sbt
//in order to run the program in a different JVM, we add the following
fork in run := true